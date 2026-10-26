"""Opt-in Dragon Burst/Spine compiled-preview verification through real stdio MCP; no source builds."""
import argparse
import asyncio
import json
from pathlib import Path
import sys
import time
import uuid

from server import ROOT
from build import digest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


# Verify a native compiled Collection retains its engine, reference scene and input without rebuilding the project.
async def main(project):
    directory = ROOT / 'tmp/automation-native-preview' / uuid.uuid4().hex
    original = {str(p): digest(p) for p in (project / 'game.project', project / 'build/default/game.projectc',
                project / 'build/default/main/main.collectionc', project / 'build/default/main/game.guic')}
    parameters = StdioServerParameters(command=sys.executable, args=['-X', 'utf8', str(ROOT / 'scripts/automation/server.py'),
        '--project', str(project), '--artifacts', str(directory)])
    started = time.monotonic()
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()
            async def call(name, **arguments):
                response = await client.call_tool('defold_' + name, arguments)
                assert not response.isError, (name, response.structuredContent)
                return response.structuredContent
            async def finish(job):
                deadline = time.monotonic() + 600
                while time.monotonic() < deadline:
                    status = await call('job_status', job_id=job['job_id'])
                    if status['state'] in ('complete', 'failed', 'cancelled'):
                        assert status['state'] == 'complete', status
                        return status['result']
                    await asyncio.sleep(0.2)
                raise AssertionError('Native preview timed out')
            project_id = (await call('project_discover'))['projects'][0]['project_id']
            base = await finish(await call('build_import', project_id=project_id, operation_id='import'))
            print(json.dumps({'stage': 'imported', 'seconds': time.monotonic() - started,
                              'engine_sha256': base['engine_sha256']}), flush=True)
            assert base['native_extensions_detected']
            repeat = await finish(await call('build_import', project_id=project_id, operation_id='import-again'))
            assert repeat['cache_hit'] and repeat['copied_resource_bytes'] == 0, repeat
            print(json.dumps({'stage':'cached-import','seconds':repeat['import_seconds']}),flush=True)
            cases = []
            baseline_frame = None
            for resource in (None, '/main/main.collection', '/main/game.gui'):
                label = resource or 'complete-game'
                begin = time.monotonic()
                build = base if resource is None else await finish(await call('preview_import',
                    build_id=base['build_id'], resource_path=resource, operation_id='preview-' + label,
                    support_objects=[{'id':'audio','resource_path':'/main/audio.go'}] if resource == '/main/game.gui' else []))
                preview_seconds=time.monotonic()-begin
                if resource: assert build['copied_resource_bytes'] == 0
                assert build['engine_sha256'] == base['engine_sha256']
                session = await finish(await call('session_start', build_id=build['build_id'],
                    device={'width': 720, 'height': 1280}, project_config={'project.smoke_test': '1'}, operation_id='start-' + label))
                sid = session['session_id']
                try:
                    await call('wait', session_id=sid, contains='DRAGON_BURST_READY', after=0)
                    frame = await call('capture', session_id=sid)
                    cursor = (await call('logs_read', session_id=sid))['cursor']
                    await call('input_perform', session_id=sid, lease_id=session['lease_id'],
                        observation_id=frame['observation_id'], action={'type': 'key', 'key': 146}, operation_id='battle-' + label)
                    await call('wait', session_id=sid, contains='REFERENCE_READY', after=cursor)
                    frame = await call('capture', session_id=sid)
                    comparison = None
                    if baseline_frame:
                        comparison = await call('image_compare', before_id=baseline_frame['artifact_id'],
                            after_id=frame['artifact_id'], channel_threshold=0, max_changed_ratio=0)
                    else:
                        baseline_frame = frame
                    drag = await call('input_perform', session_id=sid, lease_id=session['lease_id'],
                        observation_id=frame['observation_id'], operation_id='drag-' + label,
                        action={'type': 'drag', 'x': 360, 'y': 640, 'to_x': 430, 'to_y': 700, 'steps': 4})
                    logs = await call('logs_read', session_id=sid)
                    errors = [entry['text'] for entry in logs['entries'] if 'ERROR:' in entry['text']]
                    if resource is not None:
                        assert comparison['changed_pixels'] == 0, comparison
                        assert not errors, errors
                    # Explicit support GO must eliminate the original GUI bootstrap dependency errors.
                    case = {'resource': label, 'build': build, 'comparison': comparison, 'frame': frame,
                            'drag': drag, 'logs': logs, 'runtime_errors': errors,
                            'report': await call('report_export', session_id=sid),
                            'seconds': time.monotonic() - begin,'preview_prepare_seconds':preview_seconds}
                    cases.append(case)
                    print(json.dumps({'stage': label, 'seconds': case['seconds'], 'report': case['report']}), flush=True)
                finally:
                    await call('session_stop', session_id=sid)
            assert original == {path: digest(path) for path in original}, 'Original project/build changed'
            result = {'completed': True, 'cases': cases, 'original_files_unchanged': list(original),
                      'elapsed_seconds': time.monotonic() - started,'cached_import_seconds':repeat['import_seconds'],
                      'limitations': ['Dragon Burst reference mode may still contain animated pixels; inspect metrics.',
                                      'Drag is window dispatch, not a business ACK.',
                                      'Isolated GUI explicitly includes /main/audio.go; other project services must also be declared when needed.']}
            (directory / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({'completed': True, 'directory': str(directory), 'seconds': result['elapsed_seconds']}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True, help='Already locally built Dragon Burst fixture; uses its QA save mode')
    asyncio.run(main(parser.parse_args().project.resolve()))
