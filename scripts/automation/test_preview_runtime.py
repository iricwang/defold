"""Real MCP resource preview equivalence, input, cache separation and source preservation."""
import asyncio
import json
from pathlib import Path
import sys
import shutil
import time
import uuid

from server import ROOT
from build import digest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main(compiled=False):
    project = ROOT / 'scripts/automation/fixture'
    directory = ROOT / 'tmp/automation-preview-verification' / uuid.uuid4().hex
    if compiled:
        # Build a reachable nested hierarchy so Bob emits every named compiled resource for import.
        destination = directory / 'fixture'
        shutil.copytree(project, destination)
        project = destination
        (project / 'main.collection').write_text('name: "main"\ncollection_instances { id: "nested" collection: "/nested.collection" }\n')
    original = {p.relative_to(project).as_posix(): digest(p) for p in project.rglob('*') if p.is_file()}
    parameters = StdioServerParameters(command=sys.executable, args=['-X', 'utf8', str(ROOT / 'scripts/automation/server.py'),
        '--project', str(project), '--artifacts', str(directory)])
    started = time.monotonic()
    checks = []
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()
            async def call(name, **arguments):
                result = await client.call_tool('defold_' + name, arguments)
                assert not result.isError, (name, result.model_dump())
                return result.structuredContent
            async def finish(job, state='complete'):
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    result = await call('job_status', job_id=job['job_id'])
                    if result['state'] in ('complete', 'cancelled', 'failed'):
                        assert result['state'] == state, result
                        return result.get('result', result)
                    await asyncio.sleep(0.05)
                raise AssertionError('Build/start job timed out')
            project_id = (await call('project_discover'))['projects'][0]['project_id']
            listed = await call('resource_search', project_id=project_id, types=['gui'])
            assert [x['path'] for x in listed['resources']] == ['/main.gui']
            baseline = await finish(await call('build_run', project_id=project_id, operation_id='baseline'))
            baseline_config = Path(baseline['compiled_project']).read_bytes()
            async def preview(path, operation):
                if compiled:
                    return await finish(await call('preview_import', build_id=baseline['build_id'],
                                                   resource_path=path, operation_id=operation))
                return await finish(await call('preview_build', project_id=project_id,
                                               resource_path=path, operation_id=operation))
            desktop = {'width': 640, 'height': 480}
            portrait = {'width': 1170, 'height': 2532, 'top': 156, 'bottom': 102}
            session = await finish(await call('session_start', build_id=baseline['build_id'], device=desktop, operation_id='baseline-start'))
            baseline_id = session['session_id']
            await call('wait', session_id=baseline_id, contains='AUTOMATION_READY', after=0)
            desktop_frame = await call('capture', session_id=baseline_id)
            portrait_frame = await call('device_apply', session_id=baseline_id, device=portrait,
                                        expected_revision=desktop_frame['device_revision'], operation_id='baseline-portrait')
            await call('session_stop', session_id=baseline_id)
            previews = []
            first_build = None
            for index, path in enumerate(('/main.gui', '/main.go', '/standalone.collection', '/nested.collection')):
                build = await preview(path, 'preview-' + str(index))
                assert build['preview']['resource_path'] == path and build['build_id'] != baseline['build_id']
                if index == 0: first_build = build
                session = await finish(await call('session_start', build_id=build['build_id'], device=desktop,
                                                 operation_id='start-' + str(index)))
                session_id = session['session_id']
                try:
                    await call('wait', session_id=session_id, contains='AUTOMATION_READY', after=0)
                    frame = await call('capture', session_id=session_id)
                    assert frame['preview']['resource_path'] == path
                    compare = await call('image_compare', before_id=desktop_frame['artifact_id'], after_id=frame['artifact_id'],
                                         channel_threshold=0, max_changed_ratio=0)
                    assert compare['verdict'] == 'match' and compare['changed_pixels'] == 0, (path, compare)
                    if index == 0:
                        frame = await call('device_apply', session_id=session_id, device=portrait,
                                           expected_revision=frame['device_revision'], operation_id='portrait')
                        compare_portrait = await call('image_compare', before_id=portrait_frame['artifact_id'], after_id=frame['artifact_id'],
                                                      channel_threshold=0, max_changed_ratio=0)
                        assert compare_portrait['verdict'] == 'match', compare_portrait
                    cursor = (await call('logs_read', session_id=session_id))['cursor']
                    await call('input_perform', session_id=session_id, lease_id=session['lease_id'],
                               observation_id=frame['observation_id'], operation_id='click-' + str(index),
                               action={'type': 'click', 'x': frame['width']//2, 'y': frame['height']//2})
                    await call('wait', session_id=session_id, contains='BUTTON_CLICKED 1', after=cursor)
                    report = await call('report_export', session_id=session_id)
                    previews.append({'resource_path': path, 'build_id': build['build_id'], 'comparison': compare,
                                     'report': report, 'scene': await call('scene_query', session_id=session_id, selector={'id': 'verify_button'})})
                finally:
                    await call('session_stop', session_id=session_id)
            cached = await preview('/main.gui', 'cached-preview')
            assert cached['cache_hit'] and cached['build_id'] == first_build['build_id']
            full = await finish(await call('build_run', project_id=project_id, operation_id='cached-full'))
            assert full['cache_hit'] and full['build_id'] == baseline['build_id'], 'Preview polluted normal build cache'
            await finish(await call('preview_build', project_id=project_id, resource_path='/../outside.gui', operation_id='invalid'), state='failed')
            if compiled:
                await finish(await call('preview_import', build_id=baseline['build_id'], resource_path='/missing.gui',
                                       operation_id='missing-compiled'), state='failed')
                await finish(await call('preview_import', build_id=first_build['build_id'], resource_path='/main.gui',
                                       operation_id='derived-base'), state='failed')
                assert baseline_config == Path(baseline['compiled_project']).read_bytes(), 'Preview changed original bootstrap'
            after = {p.relative_to(project).as_posix(): digest(p) for p in project.rglob('*') if p.is_file()}
            assert original == after, 'Preview mutated original project resources or created a build directory'
            verification = {'passed': True, 'compiled_preview': compiled, 'previews': previews, 'source_files_unchanged': len(original),
                            'checks': ['GUI/GO/Collection/nested Collection', 'exact desktop pixel equality with normal startup',
                                       'GUI portrait pixel equality', 'runtime button business callback', 'resource search',
                                       'preview cache reuse', 'normal/preview cache separation', 'path traversal rejection', 'source preservation'],
                            'elapsed_seconds': time.monotonic() - started}
            (directory / 'verification.json').write_text(json.dumps(verification, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'elapsed_seconds': verification['elapsed_seconds'],
                              'reports': [p['report']['report_path'] for p in previews]}, indent=2))


if __name__ == '__main__':
    asyncio.run(main(compiled='--compiled' in sys.argv))
