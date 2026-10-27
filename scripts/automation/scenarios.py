"""Bounded, declarative background runs. No script evaluation or desktop input."""
import copy
import html
import json
import os
import time
import uuid
from pathlib import Path

from core import AutomationError, atomic_json
import conditions
import annotations
import gestures
import sequences


def schema(obj, string, device, action, project_config, region, element_selector, layout_rule):
    normalized = copy.deepcopy(action)
    for variant in normalized['oneOf']:
        for key in ('x', 'y', 'to_x', 'to_y'):
            if key in variant['properties']:
                variant['properties'][key] = {'type': 'number', 'minimum': 0, 'maximum': 1}
    timeout = {'type': 'integer', 'minimum': 1, 'maximum': 30000}
    selector = obj({key: string for key in ('id', 'type', 'text')})
    steps = [
        obj({'kind': {'const': 'capture'}, 'name': string}, ('kind', 'name')),
        obj({'kind': {'const': 'wait_log'}, 'contains': string,
             'after': {'enum': ['startup', 'last_input', 'last_wait']}, 'timeout_ms': timeout},
            ('kind', 'contains', 'after')),
        obj({'kind': {'const': 'scene_assert'}, 'selector': selector,
             'min_count': {'type': 'integer', 'minimum': 0, 'maximum': 100000},
             'max_count': {'type': 'integer', 'minimum': 0, 'maximum': 100000}}, ('kind', 'selector')),
        obj({'kind': {'const': 'compare'}, 'before': string, 'after': string,
             'expected': {'enum': ['match', 'different']}, 'region': region,
             'ignore': {'type': 'array', 'items': region, 'maxItems': 16},
             'channel_threshold': {'type': 'integer', 'minimum': 0, 'maximum': 255},
             'max_changed_ratio': {'type': 'number', 'minimum': 0, 'maximum': 1}},
            ('kind', 'before', 'after', 'expected'))]
    for coordinates, input_schema in (('frame_pixels', action), ('frame_normalized', normalized)):
        steps.append(obj({'kind': {'const': 'input'}, 'coordinate_space': {'const': coordinates},
                          'action': input_schema, 'expect_log': string, 'timeout_ms': timeout},
                         ('kind', 'coordinate_space', 'action')))
    properties = {'name': string,
                'devices': {'type': 'array', 'minItems': 1, 'maxItems': 8,
                            'items': obj({'name': string, 'device': device}, ('name', 'device'))},
                'project_config': project_config}
    semantic_steps = [
        obj({'kind': {'const': 'capture_sequence'}, **sequences.properties()}, ('kind',)),
        obj({'kind': {'const': 'element_drag'}, **gestures.properties(obj, element_selector),
             'expect_log': string, 'timeout_ms': timeout}, ('kind', 'selector', 'destination')),
        obj({'kind': {'const': 'element_drag_verified'}, **gestures.properties(obj, element_selector),
             'event': string, 'timeout_ms': timeout}, ('kind', 'selector', 'destination', 'event')),
        conditions.definition(obj, element_selector, {'kind': {'const': 'wait_element'}}, ('kind',)),
        obj({'kind': {'const': 'baseline_assert'},
             'baselines': {'type': 'object', 'minProperties': 1, 'maxProperties': 8,
                           'additionalProperties': {'type': 'string', 'pattern': '^[0-9a-f]{64}$'}},
             'channel_threshold': {'type': 'integer', 'minimum': 0, 'maximum': 255},
             'max_changed_ratio': {'type': 'number', 'minimum': 0, 'maximum': 1},
             'region': region, 'ignore': {'type': 'array', 'items': region, 'maxItems': 16}}, ('kind', 'baselines')),
        obj({'kind': {'const': 'element_click'}, 'selector': element_selector,
             'expect_log': string, 'timeout_ms': timeout}, ('kind', 'selector')),
        obj({'kind': {'const': 'element_click_verified'}, 'selector': element_selector,
             'event': string, 'timeout_ms': timeout}, ('kind', 'selector', 'event')),
        obj({'kind': {'const': 'layout_assert'}, 'annotate': {'type': 'boolean'},
             'rules': {'type': 'array', 'minItems': 1, 'maxItems': 64, 'items': layout_rule}}, ('kind', 'rules')),
        obj({'kind': {'const': 'application_command'},
             'name': {'type': 'string', 'pattern': '^[A-Za-z][A-Za-z0-9_.-]{0,63}$'},
             'arguments': {'type': 'object'}, 'expect_result': {}, 'timeout_ms': timeout}, ('kind', 'name'))]
    versions = []
    for version, available in ((1, steps), (2, steps + semantic_steps)):
        versions.append(obj({**properties, 'version': {'const': version},
                             **({'app_channel': {'type': 'boolean'}} if version == 2 else {}),
                             'steps': {'type': 'array', 'minItems': 1, 'maxItems': 32,
                                       'items': {'oneOf': available}}}, ('version', 'name', 'devices', 'steps')))
    return {'oneOf': versions}


def validate(spec, definition):
    from jsonschema import validate as validate_json
    json.dumps(spec, allow_nan=False)
    validate_json(spec, definition)
    names = set()
    for item in spec['devices']:
        if item['name'] in names:
            raise AutomationError('INVALID_SCENARIO', 'Device names must be unique')
        names.add(item['name'])
        device = item['device']
        if device.get('top', 0) + device.get('bottom', 0) >= device['height'] or device.get('left', 0) + device.get('right', 0) >= device['width']:
            raise AutomationError('INVALID_SCENARIO', 'Device safe area is empty')
    captures, input_seen, wait_seen = set(), False, False
    for step in spec['steps']:
        kind = step['kind']
        if kind == 'capture_sequence': sequences.validate(step.get('sample_count', 12), step.get('interval_ms', 250))
        if kind == 'capture':
            if step['name'] in captures:
                raise AutomationError('INVALID_SCENARIO', 'Capture names must be unique')
            captures.add(step['name'])
        elif kind == 'compare' and not {step['before'], step['after']} <= captures:
            raise AutomationError('INVALID_SCENARIO', 'Comparison must reference earlier named captures')
        elif kind == 'wait_log':
            if (step['after'] == 'last_input' and not input_seen) or (step['after'] == 'last_wait' and not wait_seen):
                raise AutomationError('INVALID_SCENARIO', 'Log cursor refers to a step that has not run')
            wait_seen = True
        elif kind in ('input', 'element_click', 'element_click_verified', 'element_drag', 'element_drag_verified'):
            input_seen = True
            wait_seen |= 'expect_log' in step
        elif kind == 'scene_assert' and step.get('max_count', 100000) < step.get('min_count', 1):
            raise AutomationError('INVALID_SCENARIO', 'Invalid scene count range')
        if kind in ('element_click_verified', 'element_drag_verified', 'application_command') and not spec.get('app_channel'):
            raise AutomationError('INVALID_SCENARIO', 'Correlated QA steps require explicit app_channel:true and the game QA SDK')
        if kind == 'application_command' and len(json.dumps(step.get('arguments', {}), ensure_ascii=False).encode()) > 30000:
            raise AutomationError('INVALID_SCENARIO', 'Application arguments exceed 30000 bytes')
        if kind == 'baseline_assert' and set(step['baselines']) != names:
            raise AutomationError('INVALID_SCENARIO', 'Baseline mapping must contain exactly every device name')


def check_cancel(job):
    if job['cancel'].is_set():
        raise AutomationError('CANCELLED', 'Scenario cancelled; completed actions are not undone')


def mapped_action(action, space, image):
    result = dict(action)
    if space == 'frame_normalized':
        for key, dimension in (('x', 'width'), ('to_x', 'width'), ('y', 'height'), ('to_y', 'height')):
            if key in result:
                result[key] = round(result[key] * (image[dimension] - 1))
    return result


def json_equal(actual, expected):
    """Compare JSON values without Python treating a boolean as the number 0/1."""
    if isinstance(actual, bool) or isinstance(expected, bool):
        return type(actual) is type(expected) and actual == expected
    if isinstance(actual, dict) and isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(json_equal(actual[k], expected[k]) for k in actual)
    if isinstance(actual, list) and isinstance(expected, list):
        return len(actual) == len(expected) and all(json_equal(a, b) for a, b in zip(actual, expected))
    return actual == expected


def step_report(entry, directory):
    """Keep the assertion's own immutable frame visible beside its result."""
    label = f"{entry['index'] + 1}. {entry['step']['kind']} — {entry['state']}"
    content = '<details' + (' open' if entry['state'] != 'passed' else '') + '><summary>' + html.escape(label) + '</summary>'
    observation = entry.get('observation')
    if observation:
        link = html.escape(Path(os.path.relpath(observation['image_path'], directory)).as_posix(), quote=True)
        caption = f"观测帧 {observation['render_sequence']} · {observation['observation_id']}"
        content += f'<a href="{link}"><img loading="lazy" src="{link}" alt="步骤观测原图"></a><p>{html.escape(caption)}</p>'
    marked = entry.get('result', {}).get('annotation')
    temporal = entry.get('result', {})
    if temporal.get('sequence_id') and temporal.get('report_path'):
        link = html.escape(Path(os.path.relpath(temporal['report_path'], directory)).as_posix(), quote=True)
        content += f'<p><a href="{link}">连续帧、播放预览与采样时间</a></p>'
    if marked:
        link = html.escape(Path(os.path.relpath(marked['image_path'], directory)).as_posix(), quote=True)
        content += f'<a href="{link}"><img loading="lazy" src="{link}" alt="同帧布局标注"></a>'
        content += annotations.legend_html(marked['layout_annotation'])
    excluded = {'index', 'state', 'observation'}
    if 'result' in entry:
        excluded.add('receipt')
    evidence = {key: value for key, value in entry.items() if key not in excluded}
    content += '<pre>' + html.escape(json.dumps(evidence, ensure_ascii=False, indent=2)) + '</pre></details>'
    return content


def summary(result):
    compact = {key: value for key, value in result.items() if key not in ('cases', 'build')}
    compact['cases'] = [{**{key: value for key, value in case.items() if key not in ('steps', 'failure_logs')},
                         'step_count': len(case['steps']),
                         'passed_steps': sum(step['state'] == 'passed' for step in case['steps'])}
                        for case in result['cases']]
    return compact


def inspect(service, run_id, case_index=None, offset=0, limit=4):
    result = service.runs.get(run_id)
    if result is None:
        raise AutomationError('RUN_NOT_FOUND', run_id)
    if case_index is None:
        return summary(result)
    if case_index >= len(result['cases']):
        raise AutomationError('CASE_NOT_FOUND', 'This device case has not started or does not exist')
    case = result['cases'][case_index]
    return {'run_id': run_id, 'case_index': case_index, 'state': case['state'],
            'steps': case['steps'][offset:offset + limit],
            'next_offset': offset + limit if offset + limit < len(case['steps']) else None}


def run(service, build_id, spec, job):
    if build_id not in service.builds:
        raise AutomationError('BUILD_NOT_FOUND', build_id)
    # Resolve every revision before launching a game. Missing/corrupt baselines cannot silently be created.
    for step in spec['steps']:
        if step['kind'] == 'baseline_assert':
            for baseline_id in set(step['baselines'].values()):
                service.baseline_get(service.builds[build_id]['project_id'], baseline_id)
    run_id = uuid.uuid4().hex
    directory = service.root / 'runs' / run_id
    directory.mkdir(parents=True)
    atomic_json(directory / 'scenario.json', spec)
    started = time.monotonic()
    result = {'run_id': run_id, 'name': spec['name'], 'version': spec['version'], 'build_id': build_id, 'state': 'running',
              'pass_semantics': 'declared_steps_and_assertions_only',
              'build': service.builds[build_id], 'cases': [], 'limitations': [
                  'Normalized points scale the full frame; they do not locate GUI nodes.',
                  'expect_log uses a cursor; verified node clicks/drags and QA commands use correlated operation IDs.',
                  'Scene assertions use independent live samples, not screenshot geometry.',
                  'Layout assertions use captured GUI rectangles, not text glyphs, shader coverage or pixel occlusion.',
                  'Each device has a new process; game save data is not reset. Use a project QA profile.']}
    service.runs[run_id] = result

    def save():
        result['elapsed_seconds'] = time.monotonic() - started
        atomic_json(directory / 'result.json', result)

    for index, device in enumerate(spec['devices']):
        case = {'name': device['name'], 'device': device['device'], 'state': 'pending', 'steps': []}
        result['cases'].append(case)
        if job['cancel'].is_set():
            case['state'] = 'skipped'
            continue
        session_id = None
        images, cursors = {}, {'startup': 0}
        job['progress'] = {'run_id': run_id, 'device_index': index, 'device_count': len(spec['devices']), 'phase': 'starting'}
        try:
            case['state'] = 'running'
            startup = service.start(build_id, device['device'], job, spec.get('project_config'),
                                    app_channel=spec.get('app_channel', False))
            session_id, lease = startup['session_id'], startup['lease_id']
            case.update(session_id=session_id, pid=startup['pid'])
            service.capture(session_id)
            for number, step in enumerate(spec['steps']):
                check_cancel(job)
                job['progress'] = {**job['progress'], 'phase': 'step', 'step_index': number, 'step_count': len(spec['steps'])}
                entry = {'index': number, 'step': step, 'state': 'running'}
                case['steps'].append(entry)
                save()
                try:
                    kind = step['kind']
                    if kind == 'capture':
                        output = service.capture(session_id)
                        images[step['name']] = output['artifact_id']
                    elif kind == 'capture_sequence':
                        output = sequences.run(service, session_id, job, **{k:v for k,v in step.items() if k != 'kind'})
                        if output['frames']:
                            entry['observation'] = service.artifacts[output['frames'][-1]['artifact_id']]
                    elif kind == 'wait_log':
                        output = service.wait_log(session_id, step['contains'], cursors[step['after']],
                                                  step.get('timeout_ms', 5000), cancel=job['cancel'])
                        cursors['last_wait'] = output['evidence']['cursor']
                    elif kind == 'wait_element':
                        output = service.wait_element(session_id, **{k: v for k, v in step.items() if k != 'kind'},
                                                      cancel=job['cancel'])
                        entry['observation'] = output['after']
                    elif kind in ('input', 'element_click', 'element_click_verified', 'element_drag', 'element_drag_verified'):
                        frame = service.capture(session_id)
                        entry['observation'] = frame
                        cursors['last_input'] = service.logs(session_id)['cursor']
                        check_cancel(job)
                        service.lease(session_id, lease)
                        if kind == 'input':
                            action = mapped_action(step['action'], step['coordinate_space'], frame)
                            output = service.input(session_id, lease, action, frame['observation_id'])
                        elif kind == 'element_click':
                            output = service.element_click(session_id, lease, frame['observation_id'], step['selector'])
                        elif kind in ('element_drag', 'element_drag_verified'):
                            options = {k: step[k] for k in ('selector', 'destination', 'steps', 'event', 'timeout_ms') if k in step}
                            if kind == 'element_drag_verified':
                                entry['operation_id'] = f'{run_id}:{index}:{number}'
                                options['operation_id'] = entry['operation_id']
                            else:
                                options.pop('timeout_ms', None)
                            output = getattr(service, kind)(session_id, lease, frame['observation_id'], **options, cancel=job['cancel'])
                        else:
                            entry['operation_id'] = f'{run_id}:{index}:{number}'
                            output = service.element_click_verified(session_id, lease, frame['observation_id'],
                                step['selector'], entry['operation_id'], step['event'], step.get('timeout_ms', 5000),
                                cancel=job['cancel'])
                        entry['receipt'] = output
                        if 'expect_log' in step:
                            evidence = service.wait_log(session_id, step['expect_log'], cursors['last_input'],
                                                       step.get('timeout_ms', 5000), cancel=job['cancel'])
                            cursors['last_wait'] = evidence['evidence']['cursor']
                            output = {'receipt': output, 'log_evidence': evidence, 'correlation': 'cursor_only'}
                    elif kind == 'layout_assert':
                        frame = service.capture(session_id)
                        entry['observation'] = frame
                        output = service.layout_check(frame['artifact_id'], step['rules'], annotate=step.get('annotate', True))
                        entry['result'] = output
                        if output['state'] != 'passed':
                            code = 'LAYOUT_UNKNOWN' if output['state'] == 'unknown' else 'ASSERTION_FAILED'
                            raise AutomationError(code, 'Layout assertion requires every rule to pass')
                    elif kind == 'baseline_assert':
                        frame = service.capture(session_id)
                        entry['observation'] = frame
                        options = {k: v for k, v in step.items() if k not in ('kind', 'baselines')}
                        output = service.baseline_compare(step['baselines'][device['name']], frame['artifact_id'], **options)
                        entry['result'] = output
                        if output['verdict'] != 'match':
                            raise AutomationError('ASSERTION_FAILED', 'Current frame differs from the pinned baseline')
                    elif kind == 'application_command':
                        entry['operation_id'] = f'{run_id}:{index}:{number}'
                        check_cancel(job)
                        service.lease(session_id, lease)
                        output = service.application_command(session_id, lease, entry['operation_id'], step['name'],
                            step.get('arguments'), step.get('timeout_ms', 5000), cancel=job['cancel'])
                        entry['result'] = output
                        if 'expect_result' in step and not json_equal(output['message'].get('payload'), step['expect_result']):
                            raise AutomationError('ASSERTION_FAILED', 'QA result payload differs from expect_result')
                    elif kind == 'scene_assert':
                        output = service.scene(session_id, step['selector'])
                        entry['result'] = output
                        if not step.get('min_count', 1) <= output['matched'] <= step.get('max_count', 100000):
                            raise AutomationError('ASSERTION_FAILED', 'Live scene count outside expected range')
                    elif kind == 'compare':
                        options = {k: v for k, v in step.items() if k not in ('kind', 'before', 'after', 'expected')}
                        output = service.compare(images[step['before']], images[step['after']], **options)
                        entry['result'] = output
                        if output['verdict'] != step['expected']:
                            raise AutomationError('ASSERTION_FAILED', 'Pixel comparison did not match expected verdict')
                    else:
                        raise AutomationError('INVALID_SCENARIO', 'Unsupported step kind: ' + kind)
                    entry.update(state='passed', result=output)
                except Exception as error:
                    if getattr(error, 'details', None) is not None:
                        entry['result'] = error.details
                        if error.details.get('after'):
                            entry['observation'] = error.details['after']
                    entry.update(state='cancelled' if job['cancel'].is_set() else 'failed',
                                 error={'code': getattr(error, 'code', 'INTERNAL_ERROR'), 'message': str(error)})
                    raise
                finally:
                    save()
            check_cancel(job)
            case['state'] = 'passed'
        except Exception as error:
            case.update(state='cancelled' if job['cancel'].is_set() else 'failed',
                        error={'code': getattr(error, 'code', 'INTERNAL_ERROR'), 'message': str(error)})
            if session_id:
                try:
                    case['failure_capture'] = service.capture(session_id)
                    case['failure_logs'] = service.logs(session_id)
                except Exception as diagnostic:
                    case['diagnostic_error'] = str(diagnostic)
        finally:
            if session_id:
                try:
                    case['report'] = service.report(session_id)
                except Exception as error:
                    case['report_error'] = str(error)
                    if case['state'] == 'passed': case['state'] = 'failed'
                try:
                    service.stop(session_id)
                    case['cleanup'] = 'stopped'
                except Exception as error:
                    case.update(cleanup='failed', cleanup_error=str(error), state='failed')
            save()
    result['state'] = 'cancelled' if job['cancel'].is_set() else ('passed' if all(c['state'] == 'passed' for c in result['cases']) else 'failed')
    result['summary'] = {state: sum(c['state'] == state for c in result['cases'])
                         for state in ('passed', 'failed', 'cancelled', 'skipped')}
    result.update(report_path=str(directory / 'report.html'), json_path=str(directory / 'result.json'))
    cards = ''
    for case in result['cases']:
        cards += '<section><h2>' + html.escape(case['name'] + ' — ' + case['state']) + '</h2>'
        if case.get('error'):
            cards += '<pre>' + html.escape(json.dumps(case['error'], ensure_ascii=False, indent=2)) + '</pre>'
        if case.get('report'):
            link = Path(os.path.relpath(case['report']['report_path'], directory)).as_posix()
            cards += '<a href="' + html.escape(link, quote=True) + '">截图、差异图与业务日志</a>'
        cards += ''.join(step_report(entry, directory) for entry in case['steps'])
        images_for_case = [a for a in service.artifacts.values()
                           if a['session_id'] == case.get('session_id') and a.get('artifact_kind') == 'runtime_frame']
        if images_for_case:
            link = Path(os.path.relpath(images_for_case[-1]['image_path'], directory)).as_posix()
            cards += '<p><img src="' + html.escape(link, quote=True) + '"></p>'
        cards += '</section>'
    (directory / 'report.html').write_text('<!doctype html><meta charset="utf-8"><title>Defold scenario</title>'
        '<style>body{background:#202124;color:#eee;font:16px system-ui;margin:24px}main{display:flex;flex-wrap:wrap;gap:24px}'
        'section{width:460px;max-width:100%}img{max-width:100%;max-height:650px}a{color:#83c5ff}'
        'pre{white-space:pre-wrap;overflow-wrap:anywhere}details{margin:12px 0;padding:8px;border:1px solid #555}'
        'summary{cursor:pointer}.layout-legend{border-collapse:collapse;font-size:12px;width:100%;overflow-wrap:anywhere}'
        '.layout-legend td,.layout-legend th{border:1px solid #555;padding:4px;text-align:left}</style>'
        '<h1>' + html.escape(spec['name']) + '</h1><p>' + html.escape(result['state']) + '</p><main>' + cards + '</main>'
        '<p>独立后台会话；像素比较不等于业务成功；详细日志及限制见 JSON。</p>', encoding='utf-8')
    save()
    job['progress'] = {'run_id': run_id, 'phase': 'finished', 'summary': result['summary']}
    return summary(result)
