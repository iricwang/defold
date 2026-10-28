"""MCP protocol integration with a real hidden GPU game; never uses the desktop mouse."""
import asyncio
import base64
import ctypes
import hashlib
import io
import json
from pathlib import Path
import sys
import site
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tmp/automation-python'))
site.addsitedir(str(ROOT / 'tmp/automation-python'))
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from PIL import Image


async def run():
    directory = ROOT / 'tmp/automation-verification' / uuid.uuid4().hex
    timings = {}
    started = time.monotonic()
    parameters = StdioServerParameters(command=sys.executable,
        args=['-X', 'utf8', str(ROOT / 'scripts/automation/server.py'),
              '--project', str(ROOT / 'scripts/automation/fixture'), '--artifacts', str(directory)])
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()
            definitions = await client.list_tools()
            assert len(definitions.tools) >= 22

            async def call(name, **arguments):
                result = await client.call_tool('defold_' + name, arguments)
                if result.isError:
                    raise AssertionError((name, result.model_dump()))
                return result.structuredContent

            async def finished(job):
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    status = await call('job_wait', job_id=job['job_id'], timeout_ms=10000)
                    if status['state'] == 'complete':
                        assert status['terminal'] and not status['cancel_requested'], status
                        assert status['finished_at'] >= status['started_at'] >= status['created_at'], status
                        return status['result']
                    assert status['state'] not in ('failed', 'cancelled'), status
                raise AssertionError('Job completion timeout')

            projects = await call('project_discover')
            project_id = projects['projects'][0]['project_id']
            build_request = {'project_id': project_id, 'operation_id': 'build-1'}
            build_job = await call('build_run', **build_request)
            assert await call('build_run', **build_request) == build_job
            build = await finished(build_job)
            timings['build_seconds'] = time.monotonic() - started
            cached = await finished(await call('build_run', project_id=project_id, operation_id='build-cache'))
            assert cached['cache_hit'] and cached['build_id'] == build['build_id']
            at = time.monotonic()
            startup = await finished(await call('session_start', build_id=build['build_id'],
                    device={'width': 640, 'height': 480}, operation_id='start-1'))
            timings['start_seconds'] = time.monotonic() - at
            session_id, lease_id = startup['session_id'], startup['lease_id']
            pid = startup['pid']
            evidence = []
            async def capture():
                item = await call('capture', session_id=session_id)
                resource = await client.read_resource(item['uri'])
                raw = base64.b64decode(resource.contents[0].blob)
                assert hashlib.sha256(raw).hexdigest() == item['image_sha256']
                image = Image.open(io.BytesIO(raw))
                assert image.size == (item['width'], item['height'])
                assert len(image.convert('RGB').getcolors(image.width * image.height)) > 8, 'Black/empty frame'
                evidence.append(item)
                return item

            before = await capture()
            cursor = (await call('logs_read', session_id=session_id))['cursor']
            at = time.monotonic()
            request = dict(session_id=session_id, lease_id=lease_id, observation_id=before['observation_id'],
                           operation_id='click-1', action={'type': 'click', 'x': 320, 'y': 240})
            click = await call('input_perform', **request)
            timings['click_and_capture_seconds'] = time.monotonic() - at
            assert click['state'] == 'released' and not click['business_confirmed']
            ack = await call('wait', session_id=session_id, contains='BUTTON_CLICKED 1', after=cursor)
            assert ack['state'] == 'condition_satisfied'
            repeat = await call('input_perform', **request)
            assert repeat == click, 'Input replayed instead of returning original receipt'
            logs = await call('logs_read', session_id=session_id, after=cursor)
            assert sum('BUTTON_CLICKED' in e['text'] for e in logs['entries']) == 1
            after_click = await capture()
            assert after_click['image_sha256'] != before['image_sha256']
            cropped = await call('capture_region', artifact_id=after_click['artifact_id'],
                                 region={'x': 200, 'y': 192, 'width': 240, 'height': 96})
            assert cropped['inline_preview']['to_frame_offset'] == [200, 192]
            crop_resource = await client.read_resource(cropped['uri'])
            assert hashlib.sha256(base64.b64decode(crop_resource.contents[0].blob)).hexdigest() == cropped['image_sha256']
            difference = await call('image_compare', before_id=before['artifact_id'], after_id=after_click['artifact_id'])
            assert difference['verdict'] == 'different' and difference['changed_pixels'] > 0
            assert not difference['business_confirmed']
            equal = await call('image_compare', before_id=before['artifact_id'], after_id=before['artifact_id'])
            assert equal['verdict'] == 'match' and equal['changed_pixels'] == 0
            cursor = logs['cursor']
            drag = await call('input_perform', session_id=session_id, lease_id=lease_id,
                observation_id=after_click['observation_id'], operation_id='drag-1',
                action={'type': 'drag', 'x': 160, 'y': 370, 'to_x': 230, 'to_y': 330, 'steps': 8})
            await call('wait', session_id=session_id, contains='DRAG_COMPLETE', after=cursor)
            assert drag['after']['input_sequence'] >= click['after']['input_sequence']
            for number, device in enumerate(({'width': 1170, 'height': 2532, 'top': 156, 'bottom': 102},
                                              {'width': 2532, 'height': 1170, 'left': 156, 'right': 102})):
                old = await capture()
                device_cursor = (await call('logs_read', session_id=session_id))['cursor']
                changed = await call('device_apply', session_id=session_id, operation_id=f'device-{number}',
                    expected_revision=old['device_revision'], device=device)
                await call('wait', session_id=session_id, after=device_cursor,
                    contains='SAFE_AREA ' + ' '.join(str(device.get(key, 0)) for key in ('top', 'right', 'bottom', 'left')))
                stale = await client.call_tool('defold_input_perform', dict(request,
                    operation_id=f'stale-{number}', observation_id=old['observation_id']))
                assert stale.isError and stale.structuredContent['error']['code'] == 'STALE_OBSERVATION'
                fresh = await capture()
                cursor = (await call('logs_read', session_id=session_id))['cursor']
                await call('input_perform', session_id=session_id, lease_id=lease_id,
                    operation_id=f'orientation-click-{number}', observation_id=fresh['observation_id'],
                    action={'type': 'click', 'x': device['width'] // 2, 'y': device['height'] // 2})
                await call('wait', session_id=session_id, contains='BUTTON_CLICKED', after=cursor)
                await capture()
            tree = await call('scene_query', session_id=session_id)
            assert 'verify_button' in json.dumps(tree)
            assert tree['temporal_consistency'] == 'independent_live_sample'
            for number, action in enumerate(({'type': 'key', 'key': 32}, {'type': 'text', 'text': 'hello'},
                                             {'type': 'wheel', 'x': 1266, 'y': 585, 'delta': 1})):
                fresh = await capture()
                cursor = (await call('logs_read', session_id=session_id))['cursor']
                await call('input_perform', session_id=session_id, lease_id=lease_id,
                    operation_id=f'extra-{number}', observation_id=fresh['observation_id'], action=action)
                await call('wait', session_id=session_id, contains=['KEY_RECEIVED', 'TEXT_RECEIVED', 'WHEEL_RECEIVED'][number], after=cursor)
            # Inspect native window visibility and listeners; no UI activation or real mouse movement.
            visible = []
            callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
            user32 = ctypes.windll.user32
            user32.IsWindowVisible.argtypes = [ctypes.c_void_p]
            user32.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
            @callback_type
            def check_window(window, _):
                owner = ctypes.c_ulong()
                user32.GetWindowThreadProcessId(window, ctypes.byref(owner))
                if owner.value == pid and user32.IsWindowVisible(window): visible.append(window)
                return True
            user32.EnumWindows(check_window, 0)
            assert not visible, 'Owned runtime exposed a visible window'
            listeners = subprocess.check_output(['powershell', '-NoProfile', '-Command',
                f'Get-NetTCPConnection -OwningProcess {int(pid)} -State Listen | Select-Object LocalAddress,LocalPort | ConvertTo-Json -Compress'],
                text=True, creationflags=subprocess.CREATE_NO_WINDOW)
            listeners = json.loads(listeners)
            if isinstance(listeners, dict): listeners = [listeners]
            assert listeners and all(item['LocalAddress'] in ('127.0.0.1', '::1') for item in listeners), listeners
            report = await call('report_export', session_id=session_id)
            assert Path(report['report_path']).is_file()
            report_data = json.loads(Path(report['json_path']).read_text(encoding='utf-8'))
            assert report['comparisons'] == 2
            assert any(event['kind'] == 'log_condition' and event['contains'] == 'BUTTON_CLICKED 1'
                       for event in report_data['events']), 'Report omitted business evidence'
            await call('session_stop', session_id=session_id)
            # Original image remains available after the runtime has exited.
            await client.read_resource(before['uri'])
            status = await client.call_tool('defold_session_status', {'session_id': session_id})
            assert status.isError
            kernel = ctypes.windll.kernel32
            kernel.OpenProcess.restype = ctypes.c_void_p
            handle = kernel.OpenProcess(0x100000, False, pid)
            if handle:
                kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
                kernel.CloseHandle.argtypes = [ctypes.c_void_p]
                assert kernel.WaitForSingleObject(handle, 0) == 0, 'Owned game remained alive after stop'
                kernel.CloseHandle(handle)
            timings['total_seconds'] = time.monotonic() - started
            result = {'passed': True, 'tool_count': len(definitions.tools), 'timings': timings,
                      'evidence': evidence, 'report': report, 'game_pid': pid,
                      'checks': ['MCP initialization/schema', 'local build', 'hidden GPU', 'click business ACK',
                                 'crop coordinate mapping/resource', 'before-after pixel comparison', 'identical frame comparison',
                                 'idempotent input', 'drag release', 'orientation changes', 'stale coordinate rejection',
                                 'key/text/wheel', 'runtime tree', 'PNG resource SHA256', 'session cleanup',
                                 'snapshot build cache', 'loopback-only engine listeners']}
            (directory / 'verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'timings': timings, 'report': report}), flush=True)


if __name__ == '__main__':
    asyncio.run(run())
