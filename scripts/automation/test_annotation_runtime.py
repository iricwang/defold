"""Real stdio MCP: same-frame layout marks, original PNG integrity and matrix failure evidence."""
import asyncio
import base64
import hashlib
import json
from pathlib import Path
import shutil
import sys
import time
import uuid

from server import ROOT
from build import digest
from PIL import Image
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from test_scenario_runtime import assert_process_exited


def fixture(directory):
    project = directory / 'fixture'
    shutil.copytree(ROOT / 'scripts/automation/fixture', project)
    path = project / 'main.gui_script'
    script = path.read_text(encoding='utf-8').replace('self.clicks = 0', '''self.clicks = 0
    gui.set_id(self.title, "status")
    local neighbor = gui.new_box_node(vmath.vector3(205, 120, 0), vmath.vector3(60, 36, 0))
    gui.set_id(neighbor, "overlap_neighbor")
    gui.set_color(neighbor, vmath.vector4(0.9, 0.2, 0.6, 1))
    local rotated = gui.new_box_node(vmath.vector3(270, 165, 0), vmath.vector3(40, 20, 0))
    gui.set_id(rotated, "rotated")
    gui.set_rotation(rotated, vmath.quat_rotation_z(math.rad(30)))
    gui.set_color(rotated, vmath.vector4(0, 1, 1, 1))''')
    path.write_text(script, encoding='utf-8')
    return project


async def main():
    directory = ROOT / 'tmp/automation-annotation-verification' / uuid.uuid4().hex
    project = fixture(directory)
    original_hash = digest(ROOT / 'scripts/automation/fixture/main.gui_script')
    parameters = StdioServerParameters(command=sys.executable, args=['-X', 'utf8',
        str(ROOT / 'scripts/automation/server.py'), '--project', str(project), '--artifacts', str(directory / 'artifacts')])
    started = time.monotonic()
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()

            async def call(name, **args):
                response = await client.call_tool('defold_' + name, args)
                assert not response.isError, (name, response.structuredContent)
                return response.structuredContent

            async def finish(job):
                deadline = time.monotonic() + 150
                while time.monotonic() < deadline:
                    status = await call('job_status', job_id=job['job_id'])
                    if status['state'] in ('complete', 'failed', 'cancelled'):
                        assert status['state'] == 'complete', status
                        return status['result']
                    await asyncio.sleep(.1)
                raise AssertionError('Job timed out')

            pid = (await call('project_discover'))['projects'][0]['project_id']
            build = await finish(await call('build_run', project_id=pid, operation_id='build'))
            spec = json.loads((project / 'scenario.json').read_text())
            devices = spec['devices']
            rules = [{'kind': 'inside_safe_area', 'selector': {'id': 'drag_handle'}},
                     {'kind': 'inside_viewport', 'selector': {'id': 'rotated'}},
                     {'kind': 'no_overlap', 'selector': {'id': 'verify_button'}, 'other': {'id': 'overlap_neighbor'}},
                     {'kind': 'inside_viewport', 'selector': {'id': 'status'}},
                     {'kind': 'inside_viewport', 'selector': {'id': 'absent'}}]
            session = await finish(await call('session_start', build_id=build['build_id'], device=devices[0]['device'], operation_id='start'))
            sid = session['session_id']
            samples = []
            try:
                await call('wait', session_id=sid, contains='AUTOMATION_READY', after=0)
                frame = await call('capture', session_id=sid)
                for index, device in enumerate(devices):
                    if index:
                        frame = await call('device_apply', session_id=sid, device=device['device'],
                                           expected_revision=frame['device_revision'], operation_id='device-' + str(index))
                    before = digest(frame['image_path'])
                    response = await client.call_tool('defold_layout_check', {'artifact_id': frame['artifact_id'], 'rules': rules, 'annotate': True})
                    assert not response.isError, response.structuredContent
                    result = response.structuredContent
                    assert any(part.type == 'image' for part in response.content)
                    marked = result['annotation']
                    assert result['state'] == 'failed'
                    assert [r['state'] for r in result['rules']] == ['passed', 'passed', 'failed', 'unknown', 'unknown']
                    assert marked['render_sequence'] == frame['render_sequence']
                    assert marked['observation_id'] == frame['observation_id']
                    assert marked['source_artifact_id'] == frame['artifact_id']
                    assert before == digest(frame['image_path']), 'Annotation rewrote raw GPU evidence'
                    plan = marked['layout_annotation']
                    assert plan['rules'][2]['overlap_polygon']
                    assert not plan['rules'][3]['targets'][0]['drawable_polygon']
                    assert not plan['rules'][4]['targets'][0]['drawable_polygon']
                    quad = plan['rules'][1]['targets'][0]['quad']
                    with Image.open(frame['image_path']) as raw, Image.open(marked['image_path']) as image:
                        assert raw.size == image.size == (frame['width'], frame['height'])
                        # Unique cyan rectangle proves that the annotation geometry corresponds to real GPU pixels.
                        rgb = raw.convert('RGB')
                        mask = Image.frombytes('L', rgb.size, bytes(255 if p == (0, 255, 255) else 0 for p in rgb.getdata()))
                        actual = mask.getbbox()
                        assert actual, 'Rotated cyan node not rendered'
                        expected = [min(p[0] for p in quad), min(p[1] for p in quad), max(p[0] for p in quad), max(p[1] for p in quad)]
                        assert max(abs(a - b) for a, b in zip(actual, expected)) <= 2, (actual, expected)
                    resource = await client.read_resource(marked['uri'])
                    assert hashlib.sha256(base64.b64decode(resource.contents[0].blob)).hexdigest() == marked['image_sha256']
                    rejected = await client.call_tool('defold_element_query', {'artifact_id': marked['artifact_id'], 'selector': {'id': 'rotated'}})
                    assert rejected.isError and rejected.structuredContent['error']['code'] == 'INVALID_OBSERVATION'
                    samples.append({'device': device['name'], 'raw': frame, 'annotation': marked,
                                    'pixel_bounds': actual, 'engine_bounds': expected})
                report = await call('report_export', session_id=sid)
                report_text = Path(report['report_path']).read_text(encoding='utf-8')
                assert 'layout-legend' in report_text and 'unknown_geometry' in report_text
            finally:
                await call('session_stop', session_id=sid)
            if sys.platform == 'win32': assert_process_exited(session['pid'])

            # Every failing device still publishes the assertion's own annotated frame before stopping its game.
            spec.update(version=2, name='Annotated layout failures across devices', steps=[spec['steps'][0],
                {'kind': 'layout_assert', 'rules': rules[:2], 'annotate': False},
                {'kind': 'layout_assert', 'rules': rules}])
            (project / 'scenario-annotations.json').write_text(json.dumps(spec, indent=2), encoding='utf-8')
            matrix = await finish(await call('scenario_run', build_id=build['build_id'], scenario=spec, operation_id='matrix'))
            assert matrix['state'] == 'failed' and matrix['summary']['failed'] == 3, matrix
            for index, case in enumerate(matrix['cases']):
                detail = await call('scenario_get', run_id=matrix['run_id'], case_index=index)
                steps = detail['steps']
                assert 'annotation' not in steps[1]['result']
                marked = steps[2]['result']['annotation']
                assert marked['observation_id'] == steps[2]['observation']['observation_id']
                assert case['error']['code'] == 'ASSERTION_FAILED' and case['cleanup'] == 'stopped'
                if sys.platform == 'win32': assert_process_exited(case['pid'])
            matrix_html = Path(matrix['report_path']).read_text(encoding='utf-8')
            assert matrix_html.count('alt="同帧布局标注"') == 3
            assert original_hash == digest(ROOT / 'scripts/automation/fixture/main.gui_script')
            verification = {'passed': True, 'elapsed_seconds': time.monotonic() - started, 'samples': samples,
                            'report': report, 'expected_failure_matrix': matrix}
            (directory / 'verification.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'report': report,
                              'matrix_report': matrix['report_path'], 'elapsed_seconds': verification['elapsed_seconds']}), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
