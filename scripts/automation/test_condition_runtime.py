"""Real stdio MCP GUI waits: async UI, three devices, timeout, cancellation and PNG cleanup."""
import asyncio
import json
from pathlib import Path
import shutil
import sys
import time
import uuid

from server import ROOT
from build import digest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from test_scenario_runtime import assert_process_exited


def fixture(directory):
    project = directory / 'fixture'
    shutil.copytree(ROOT / 'scripts/automation/fixture', project)
    path = project / 'main.gui_script'
    script = path.read_text(encoding='utf-8')
    script = script.replace('self.clicks = 0', 'self.clicks = 0\n    gui.set_id(self.title, "status")')
    script = script.replace('function update(self, dt)', '''function update(self, dt)
    if self.loading then
        self.loading = self.loading + dt
        if self.loading >= 1.2 then
            self.loading = nil
            gui.set_visible(self.result, true)
            gui.set_text(self.title, "READY")
        end
    end''')
    script = script.replace('print("BUTTON_CLICKED " .. self.clicks)', '''print("BUTTON_CLICKED " .. self.clicks)
            if self.clicks == 1 then
                self.result = gui.new_box_node(vmath.vector3(160, 80, 0), vmath.vector3(60, 16, 0))
                gui.set_id(self.result, "async_result")
                gui.set_color(self.result, vmath.vector4(0, 0.8, 0.9, 1))
                gui.set_visible(self.result, false)
                gui.set_text(self.title, "LOADING")
                self.loading = 0
            elseif self.clicks == 2 then
                gui.set_visible(self.result, false)
                gui.set_enabled(self.handle, false)
            elseif self.clicks == 3 then
                gui.delete_node(self.result)
                self.result = nil
            end''')
    path.write_text(script, encoding='utf-8')
    return project


# Conditions are established by real scene updates/input, not by test sleeps or additional business log markers.
async def main():
    directory = ROOT / 'tmp/automation-condition-verification' / uuid.uuid4().hex
    project = fixture(directory)
    parameters = StdioServerParameters(command=sys.executable, args=['-X', 'utf8',
        str(ROOT / 'scripts/automation/server.py'), '--project', str(project), '--artifacts', str(directory / 'artifacts')])
    started = time.monotonic()
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()

            async def call(tool_name, **args):
                response = await client.call_tool('defold_' + tool_name, args)
                assert not response.isError, (tool_name, response.structuredContent)
                return response.structuredContent

            async def finish(job, expected='complete'):
                deadline = time.monotonic() + 150
                while time.monotonic() < deadline:
                    status = await call('job_status', job_id=job['job_id'])
                    if status['state'] in ('complete', 'failed', 'cancelled'):
                        assert status['state'] == expected, status
                        return status
                    await asyncio.sleep(.1)
                raise AssertionError('Job timed out')

            pid = (await call('project_discover'))['projects'][0]['project_id']
            build = (await finish(await call('build_run', project_id=pid, operation_id='build')))['result']
            devices = json.loads((project / 'scenario.json').read_text())['devices']
            spec = {'version': 2, 'name': 'Async GUI condition verification', 'devices': devices, 'steps': [
                {'kind': 'wait_element', 'selector': {'id': 'verify_button'}, 'condition': 'present', 'stable_samples': 2},
                {'kind': 'wait_element', 'selector': {'id': 'drag_handle'}, 'condition': 'enabled'},
                {'kind': 'element_click', 'selector': {'id': 'verify_button'}},
                {'kind': 'wait_element', 'selector': {'id': 'status'}, 'condition': 'text_equals', 'text': 'READY', 'stable_samples': 2},
                {'kind': 'wait_element', 'selector': {'id': 'async_result'}, 'condition': 'visible', 'stable_samples': 2},
                {'kind': 'capture', 'name': 'ready'},
                {'kind': 'element_click', 'selector': {'id': 'verify_button'}},
                {'kind': 'wait_element', 'selector': {'id': 'async_result'}, 'condition': 'hidden', 'stable_samples': 2},
                {'kind': 'wait_element', 'selector': {'id': 'drag_handle'}, 'condition': 'disabled'},
                {'kind': 'element_click', 'selector': {'id': 'verify_button'}},
                {'kind': 'wait_element', 'selector': {'id': 'async_result'}, 'condition': 'absent', 'stable_samples': 2}]}
            (directory / 'scenario-condition.json').write_text(json.dumps(spec, indent=2), encoding='utf-8')
            matrix = (await finish(await call('scenario_run', build_id=build['build_id'], scenario=spec, operation_id='matrix')))['result']
            assert matrix['summary']['passed'] == 3, matrix
            screenshots, samples = [], []
            for index, case in enumerate(matrix['cases']):
                first = await call('scenario_get', run_id=matrix['run_id'], case_index=index, limit=8)
                second = await call('scenario_get', run_id=matrix['run_id'], case_index=index, offset=8, limit=8)
                steps = first['steps'] + second['steps']
                assert len(steps) == 11 and case['cleanup'] == 'stopped'
                for entry in steps:
                    if entry['step']['kind'] == 'wait_element':
                        result = entry['result']
                        assert result['state'] == 'condition_satisfied'
                        assert result['consecutive_matches'] >= result['required_samples']
                        assert entry['observation']['observation_id'] == result['after']['observation_id']
                assert steps[3]['result']['evidence']['element']['text'] == 'READY'
                assert steps[10]['result']['evidence']['matched'] == 0
                screenshots.append({'device': case['name'], 'ready': steps[5]['result']['image_path']})
                samples.append(steps[3]['result']['samples'])
                report = json.loads(Path(case['report']['json_path']).read_text(encoding='utf-8'))
                folder = Path(case['report']['report_path']).parent
                saved = {Path(item['image_path']).name for item in report['observations']}
                assert {path.name for path in folder.glob('*.png')} == saved | {'startup.png'}, 'Unpublished sampling PNG leaked'
                if sys.platform == 'win32': assert_process_exited(case['pid'])

            session = (await finish(await call('session_start', build_id=build['build_id'], device=devices[0]['device'],
                                               operation_id='negative-session')))['result']
            sid = session['session_id']
            failures = []
            try:
                request = {'session_id': sid, 'selector': {'id': 'verify_button'}, 'condition': 'present',
                           'stable_samples': 2, 'operation_id': 'present'}
                job = await call('element_wait', **request)
                assert job == await call('element_wait', **request)
                assert (await finish(job))['result']['state'] == 'condition_satisfied'
                for identifier, selector, condition, code in [
                        ('missing', {'id': 'absent'}, 'present', 'CONDITION_TIMEOUT'),
                        ('unknown', {'id': 'status'}, 'hidden', 'GEOMETRY_UNAVAILABLE'),
                        ('ambiguous', {'type': 'gui_node_box'}, 'visible', 'AMBIGUOUS_ELEMENT')]:
                    status = await finish(await call('element_wait', session_id=sid, selector=selector,
                        condition=condition, timeout_ms=500, operation_id=identifier), 'failed')
                    assert status['error_code'] == code, status
                    frame = status['error_details']['after']
                    assert digest(frame['image_path']) == frame['image_sha256']
                    failures.append(status)
                job = await call('element_wait', session_id=sid, selector={'id': 'absent'}, condition='present',
                                 timeout_ms=30000, operation_id='cancel')
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    status = await call('job_status', job_id=job['job_id'])
                    assert status['state'] not in ('failed', 'complete', 'cancelled'), status
                    if status.get('progress', {}).get('samples', 0) >= 2: break
                    await asyncio.sleep(.1)
                else: raise AssertionError('Wait never sampled the scene')
                cancelled_at = time.monotonic()
                await call('job_cancel', job_id=job['job_id'])
                cancelled = await finish(job, 'cancelled')
                cancel_seconds = time.monotonic() - cancelled_at
                assert cancelled['error_code'] == 'CANCELLED' and cancel_seconds < 5, cancelled
                assert Path(cancelled['error_details']['after']['image_path']).is_file()
                report = await call('report_export', session_id=sid)
                folder = Path(report['report_path']).parent
                recorded = json.loads(Path(report['json_path']).read_text(encoding='utf-8'))
                saved = {Path(item['image_path']).name for item in recorded['observations']}
                assert {path.name for path in folder.glob('*.png')} == saved | {'startup.png'}
            finally:
                await call('session_stop', session_id=sid)
            # A timed-out scenario retains the step's frame, fails and cleans up its owned process.
            bad = {**spec, 'name': 'Expected GUI wait timeout', 'devices': devices[:1], 'steps': [
                {'kind': 'wait_element', 'selector': {'id': 'absent'}, 'condition': 'present', 'timeout_ms': 500}]}
            failed = (await finish(await call('scenario_run', build_id=build['build_id'], scenario=bad, operation_id='timeout-scenario')))['result']
            assert failed['state'] == 'failed' and failed['cases'][0]['error']['code'] == 'CONDITION_TIMEOUT', failed
            page = await call('scenario_get', run_id=failed['run_id'], case_index=0)
            assert Path(page['steps'][0]['observation']['image_path']).is_file()
            assert failed['cases'][0]['cleanup'] == 'stopped'
            result = {'passed': True, 'elapsed_seconds': time.monotonic() - started, 'matrix': matrix,
                      'screenshots': screenshots, 'ready_wait_samples': samples, 'failures': failures,
                      'cancelled': cancelled, 'cancel_seconds': cancel_seconds, 'timeout_scenario': failed, 'report': report}
            (directory / 'verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'matrix_report': matrix['report_path'],
                              'failure_report': report['report_path'], 'ready_wait_samples': samples,
                              'elapsed_seconds': result['elapsed_seconds'], 'cancel_seconds': cancel_seconds}), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
