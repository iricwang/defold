"""Bounded, declarative background runs. No script evaluation or desktop input."""
import copy
import html
import json
import os
import time
import uuid
from pathlib import Path

from core import AutomationError, atomic_json


def schema(obj, string, device, action, project_config, region):
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
    return obj({'version': {'const': 1}, 'name': string,
                'devices': {'type': 'array', 'minItems': 1, 'maxItems': 8,
                            'items': obj({'name': string, 'device': device}, ('name', 'device'))},
                'steps': {'type': 'array', 'minItems': 1, 'maxItems': 32, 'items': {'oneOf': steps}},
                'project_config': project_config}, ('version', 'name', 'devices', 'steps'))


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
        elif kind == 'input':
            input_seen = True
            wait_seen |= 'expect_log' in step
        elif kind == 'scene_assert' and step.get('max_count', 100000) < step.get('min_count', 1):
            raise AutomationError('INVALID_SCENARIO', 'Invalid scene count range')


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
    run_id = uuid.uuid4().hex
    directory = service.root / 'runs' / run_id
    directory.mkdir(parents=True)
    atomic_json(directory / 'scenario.json', spec)
    started = time.monotonic()
    result = {'run_id': run_id, 'name': spec['name'], 'build_id': build_id, 'state': 'running',
              'pass_semantics': 'declared_steps_and_assertions_only',
              'build': service.builds[build_id], 'cases': [], 'limitations': [
                  'Normalized points scale the full frame; they do not locate GUI nodes.',
                  'Log evidence uses a cursor, not a correlated application operation ID.',
                  'Scene assertions use independent live samples, not screenshot geometry.',
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
            startup = service.start(build_id, device['device'], job, spec.get('project_config'))
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
                    elif kind == 'wait_log':
                        output = service.wait_log(session_id, step['contains'], cursors[step['after']],
                                                  step.get('timeout_ms', 5000), cancel=job['cancel'])
                        cursors['last_wait'] = output['evidence']['cursor']
                    elif kind == 'input':
                        frame = service.capture(session_id)
                        action = mapped_action(step['action'], step['coordinate_space'], frame)
                        cursors['last_input'] = service.logs(session_id)['cursor']
                        check_cancel(job)
                        service.lease(session_id, lease)
                        output = service.input(session_id, lease, action, frame['observation_id'])
                        entry['receipt'] = output
                        if 'expect_log' in step:
                            evidence = service.wait_log(session_id, step['expect_log'], cursors['last_input'],
                                                       step.get('timeout_ms', 5000), cancel=job['cancel'])
                            cursors['last_wait'] = evidence['evidence']['cursor']
                            output = {'receipt': output, 'log_evidence': evidence, 'correlation': 'cursor_only'}
                    elif kind == 'scene_assert':
                        output = service.scene(session_id, step['selector'])
                        entry['result'] = output
                        if not step.get('min_count', 1) <= output['matched'] <= step.get('max_count', 100000):
                            raise AutomationError('ASSERTION_FAILED', 'Live scene count outside expected range')
                    else:
                        options = {k: v for k, v in step.items() if k not in ('kind', 'before', 'after', 'expected')}
                        output = service.compare(images[step['before']], images[step['after']], **options)
                        entry['result'] = output
                        if output['verdict'] != step['expected']:
                            raise AutomationError('ASSERTION_FAILED', 'Pixel comparison did not match expected verdict')
                    entry.update(state='passed', result=output)
                except Exception as error:
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
        images_for_case = [a for a in service.artifacts.values()
                           if a['session_id'] == case.get('session_id') and a.get('artifact_kind') == 'runtime_frame']
        if images_for_case:
            link = Path(os.path.relpath(images_for_case[-1]['image_path'], directory)).as_posix()
            cards += '<p><img src="' + html.escape(link, quote=True) + '"></p>'
        cards += '</section>'
    (directory / 'report.html').write_text('<!doctype html><meta charset="utf-8"><title>Defold scenario</title>'
        '<style>body{background:#202124;color:#eee;font:16px system-ui;margin:24px}main{display:flex;flex-wrap:wrap;gap:24px}'
        'section{max-width:460px}img{max-width:420px;max-height:650px}a{color:#83c5ff}pre{white-space:pre-wrap}</style>'
        '<h1>' + html.escape(spec['name']) + '</h1><p>' + html.escape(result['state']) + '</p><main>' + cards + '</main>'
        '<p>独立后台会话；像素比较不等于业务成功；详细日志及限制见 JSON。</p>', encoding='utf-8')
    save()
    job['progress'] = {'run_id': run_id, 'phase': 'finished', 'summary': result['summary']}
    return summary(result)
