"""Bounded temporal screenshot evidence, with measured host timings rather than invented FPS."""
import html
import copy
import json
import os
from pathlib import Path
import time
import uuid

from PIL import Image, ImageDraw
from build import digest


def properties():
    return {'sample_count': {'type': 'integer', 'minimum': 2, 'maximum': 60},
            'interval_ms': {'type': 'integer', 'minimum': 100, 'maximum': 2000}}


def validate(sample_count, interval_ms):
    if type(sample_count) is not int or not 2 <= sample_count <= 60:
        raise ValueError('sample_count must be an integer from 2 to 60')
    if type(interval_ms) is not int or not 100 <= interval_ms <= 2000:
        raise ValueError('interval_ms must be an integer from 100 to 2000')
    if (sample_count - 1) * interval_ms > 15000:
        raise ValueError('Requested sampling span must not exceed 15 seconds')


def render(frames, directory, interval_ms):
    """Keep full-resolution originals; limit preview memory to 60 small images."""
    previews = []
    for frame in frames:
        if digest(frame['image_path']) != frame['image_sha256']:
            raise ValueError('Sequence source image was modified after capture')
        with Image.open(frame['image_path']) as image:
            image.thumbnail((640, 640))
            previews.append(image.convert('RGB'))
    durations = [max(1, round(b['capture_completed_ms'] - a['capture_completed_ms'])) for a, b in zip(frames, frames[1:])]
    durations.append(interval_ms)
    animation = directory / 'preview.webp'
    previews[0].save(animation, format='WEBP', save_all=True, append_images=previews[1:],
                     duration=durations, loop=0, lossless=True)
    # Overview shows evenly spaced samples; the manifest/report retains every original frame.
    indices = sorted({round(i * (len(frames) - 1) / (min(12, len(frames)) - 1))
                      for i in range(min(12, len(frames)))}) if len(frames) > 1 else [0]
    columns = min(3, len(indices))
    sheet = Image.new('RGB', (columns * 320, ((len(indices)+columns-1)//columns) * 266), '#171b22')
    draw = ImageDraw.Draw(sheet)
    for number, index in enumerate(indices):
        tile = previews[index].copy()
        tile.thumbnail((312, 236))
        x, y = (number % columns) * 320, (number // columns) * 266
        sheet.paste(tile, (x + (320-tile.width)//2, y + (240-tile.height)//2))
        draw.text((x+5, y+244), f"#{index+1}  {frames[index]['capture_completed_ms']:.0f} ms  render {frames[index]['render_sequence']}", fill='white')
    sheet.save(directory / 'overview.png')
    return {'width': sheet.width, 'height': sheet.height, 'selected_indices': indices,
            'preview_width': previews[0].width, 'preview_height': previews[0].height,
            'preview_durations_ms': durations}


def run(service, session_id, job, sample_count=12, interval_ms=250):
    from core import AutomationError, atomic_json
    try: validate(sample_count, interval_ms)
    except ValueError as error: raise AutomationError('INVALID_SEQUENCE', str(error)) from error
    service.session(session_id)
    with service.lock:
        if session_id in service.sequence_active:
            raise AutomationError('SEQUENCE_BUSY', 'Only one temporal capture may run per session')
        if sum(x['session_id'] == session_id for x in service.artifacts.values()) + sample_count + 1 > 256:
            raise AutomationError('ARTIFACT_LIMIT', 'Sequence needs room for its original frames and overview')
        service.sequence_active.add(session_id)
    identifier = uuid.uuid4().hex
    directory = service.root / 'sequences' / identifier
    try: directory.mkdir(parents=True)
    except Exception:
        with service.lock: service.sequence_active.discard(session_id)
        raise
    started = time.monotonic()
    result = {'sequence_id': identifier, 'session_id': session_id, 'state': 'running', 'frames': [],
              'requested_samples': sample_count, 'interval_ms': interval_ms, 'attempts': 0, 'duplicate_renders': 0,
              'timing_semantics': 'host_capture_start_and_completion_not_simulation_time',
              'report_path': str(directory / 'report.html'), 'json_path': str(directory / 'sequence.json'),
              'limitations': ['Discrete GPU sampling may miss intermediate engine frames; not fixed-FPS video.',
                              'Playback intervals use host capture completion times, not engine presentation timestamps.',
                              'Preview is resized and loops; final sample is held for the requested interval.',
                              'Inputs may run between samples; device changes end the sequence instead of mixing layouts.']}
    def publish():
        atomic_json(directory / 'sequence.json', result)
        service.sequences[identifier] = copy.deepcopy(result)
    first, previous, pending = None, None, None
    failure = None
    try:
        publish()
        for index in range(sample_count):
            if job['cancel'].is_set(): raise AutomationError('CANCELLED', 'Sequence cancelled; captured frames are retained')
            began = time.monotonic()
            pending = service.capture(session_id, publish=False)
            completed = time.monotonic()
            result['attempts'] += 1
            if first and (pending['device_revision'] != first['device_revision'] or pending['device'] != first['device']):
                raise AutomationError('DEVICE_CHANGED', 'Device changed while sampling; incompatible frame was excluded')
            if previous is None or pending['render_sequence'] != previous:
                service.publish_capture(pending)
                if first is None: first = pending
                previous = pending['render_sequence']
                result['frames'].append({key: pending[key] for key in (
                    'artifact_id', 'observation_id', 'image_path', 'image_sha256', 'uri', 'width', 'height',
                    'render_sequence', 'input_sequence', 'device_revision')})
                result['frames'][-1].update(capture_started_ms=round((began-started)*1000, 3),
                                           capture_completed_ms=round((completed-started)*1000, 3))
            else:
                result['duplicate_renders'] += 1
            service.discard_capture(pending)
            pending = None
            job['progress'] = {**job.get('progress', {}), 'phase': 'sampling', 'sequence_id': identifier, 'attempts': result['attempts'],
                               'captured_frames': len(result['frames']), 'requested_samples': sample_count}
            publish()
            if index < sample_count - 1:
                # Never burst to catch up after a slow readback.
                job['cancel'].wait(max(0, interval_ms/1000 - (time.monotonic()-began)))
        if job['cancel'].is_set(): raise AutomationError('CANCELLED', 'Sequence cancelled after final capture')
        result['state'] = 'complete'
    except Exception as error:
        failure = error
        result['state'] = 'cancelled' if job['cancel'].is_set() else 'failed'
        result['error'] = {'code': getattr(error, 'code', 'SEQUENCE_FAILED'), 'message': str(error)}
    finally:
        service.discard_capture(pending)
        try:
            if result['frames']:
                job['progress'] = {**job.get('progress', {}), 'phase': 'encoding', 'sequence_id': identifier, 'captured_frames': len(result['frames'])}
                metadata = render(result['frames'], directory, interval_ms)
                # A multi-frame sheet has no single-frame input coordinate mapping.
                def overview(path):
                    path.write_bytes((directory / 'overview.png').read_bytes())
                    return {'width': metadata['width'], 'height': metadata['height'], 'sequence_id': identifier,
                            'coordinate_space': 'contact_sheet_not_input', 'selected_indices': metadata['selected_indices']}
                result['overview'] = service.derived(first, 'sequence_sheet', overview)
                result['preview'] = {'path': str(directory / 'preview.webp'), 'mime_type': 'image/webp',
                                     'uri': f'defold://sequences/{identifier}/preview.webp',
                                     'sha256': digest(directory / 'preview.webp'),
                                     'width': metadata['preview_width'], 'height': metadata['preview_height'],
                                     'durations_ms': metadata['preview_durations_ms']}
        except Exception as error:
            result['encoding_error'] = str(error)
            if failure is None:
                failure = AutomationError('SEQUENCE_ENCODING_FAILED', str(error))
                result.update(state='failed', error={'code': failure.code, 'message': str(error)})
        finally:
            try:
                if job['cancel'].is_set() and failure is None:
                    failure = AutomationError('CANCELLED', 'Sequence cancelled during encoding; captured frames are retained')
                    result.update(state='cancelled', error={'code': failure.code, 'message': str(failure)})
                result['captured_frames'] = len(result['frames'])
                result['elapsed_ms'] = round((time.monotonic()-started)*1000)
                publish()
                report(result, directory)
            finally:
                with service.lock: service.sequence_active.discard(session_id)
    service.record(session_id, {'kind': 'capture_sequence', 'sequence_id': identifier, 'state': result['state'],
                                'captured_frames': result['captured_frames'], 'report_path': result['report_path']})
    if failure is not None:
        if not isinstance(failure, AutomationError): failure = AutomationError('SEQUENCE_FAILED', str(failure))
        failure.details = result
        raise failure
    return result


def report(result, directory):
    def relative(path): return html.escape(Path(os.path.relpath(path, directory)).as_posix(), quote=True)
    body = '<h1>Defold 连续帧查验</h1><p>' + html.escape(result['state']) + '</p>'
    body += '<p>离散 GPU 采样；时间为主机捕帧完成时间，不是引擎模拟时间或固定帧率录像。预览缩放并循环播放。</p>'
    if result.get('preview'): body += '<img src="preview.webp" alt="连续帧预览">'
    if result.get('overview'): body += '<p><a href="overview.png">打开帧总览</a></p>'
    body += '<main>'
    for index, frame in enumerate(result['frames'], 1):
        link = relative(frame['image_path'])
        body += f'<figure><a href="{link}"><img loading="lazy" src="{link}"></a><figcaption>#{index} · {frame["capture_completed_ms"]:.0f} ms · render {frame["render_sequence"]}</figcaption></figure>'
    body += '</main><details><summary>时序、哈希和限制</summary><pre>' + html.escape(json.dumps(result, ensure_ascii=False, indent=2)) + '</pre></details>'
    (directory / 'report.html').write_text('<!doctype html><meta charset="utf-8"><title>Defold temporal evidence</title>'
        '<style>body{background:#202124;color:#eee;font:16px system-ui;margin:24px}main{display:flex;flex-wrap:wrap;gap:20px}'
        'figure{margin:0}img{max-width:640px;max-height:480px}figure img{max-width:300px;max-height:300px}'
        'a{color:#83c5ff}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style>' + body, encoding='utf-8')
