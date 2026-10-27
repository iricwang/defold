"""Real stdio MCP: V2 node actions, frame layout, application ACKs and failure cleanup."""
import asyncio
import copy
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
    """Only this isolated QA fixture opts into the SDK; no user project is modified."""
    project = directory / 'fixture'
    shutil.copytree(ROOT / 'scripts/automation/fixture', project)
    shutil.copyfile(ROOT / 'scripts/automation/sdk/automation.lua', project / 'automation.lua')
    script = (project / 'main.gui_script').read_text(encoding='utf-8')
    script = 'local qa = require "automation"\n' + script
    script = script.replace('print("AUTOMATION_READY")', '''print("AUTOMATION_READY")
    qa.register("state", function() return {clicks=self.clicks} end)
    qa.ready()''')
    script = script.replace('function update(self, dt)', 'function update(self, dt)\n    qa.update(dt)')
    script = script.replace('print("BUTTON_CLICKED " .. self.clicks)',
                            'print("BUTTON_CLICKED " .. self.clicks)\n            qa.ack("button_clicked", {clicks=self.clicks})')
    (project / 'main.gui_script').write_text(script, encoding='utf-8')
    return project


# Each matrix case must reach business success through actual input, with original per-step GPU evidence.
async def main():
    directory = ROOT / 'tmp/automation-semantic-verification' / uuid.uuid4().hex
    project = fixture(directory)
    original = digest(ROOT / 'scripts/automation/fixture/main.gui_script')
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

            async def finish(job, expected='complete'):
                deadline = time.monotonic() + 150
                while time.monotonic() < deadline:
                    status = await call('job_status', job_id=job['job_id'])
                    if status['state'] in ('complete', 'failed', 'cancelled'):
                        assert status['state'] == expected, status
                        return status['result']
                    await asyncio.sleep(.1)
                raise AssertionError('Job timed out')

            discovered = await call('project_discover')
            assert discovered['scenario_versions'] == [1, 2]
            pid = discovered['projects'][0]['project_id']
            build = await finish(await call('build_run', project_id=pid, operation_id='build'))
            spec = json.loads((project / 'scenario-semantic.json').read_text())
            request = {'build_id': build['build_id'], 'scenario': spec, 'operation_id': 'semantic-matrix'}
            job = await call('scenario_run', **request)
            assert job == await call('scenario_run', **request), 'Duplicate request started another matrix'
            matrix = await finish(job)
            assert matrix['state'] == 'passed' and matrix['summary']['passed'] == 3, matrix
            runs = [matrix]
            operation_ids, screenshots = [], []
            for index, case in enumerate(matrix['cases']):
                first = await call('scenario_get', run_id=matrix['run_id'], case_index=index, limit=8)
                last = await call('scenario_get', run_id=matrix['run_id'], case_index=index, offset=8, limit=8)
                steps = first['steps'] + last['steps']
                assert len(steps) == 9 and case['cleanup'] == 'stopped'
                layout = steps[2]
                assert layout['result']['state'] == 'passed'
                assert layout['result']['observation_id'] == layout['observation']['observation_id']
                assert layout['result']['render_sequence'] == layout['observation']['render_sequence']
                ack = steps[3]['result']['application_ack']
                assert ack['operation_id'] == steps[3]['operation_id'] and ack['payload']['clicks'] == 1
                operation_ids.append(ack['operation_id'])
                assert steps[4]['result']['message']['payload'] == {'clicks': 1}
                assert steps[6]['result']['changed_pixels'] > 0
                assert steps[7]['result']['correlation'] == 'cursor_only'
                assert steps[8]['result']['message']['payload'] == {'clicks': 2}
                screenshots.append({'device': case['name'], 'before': steps[1]['result']['image_path'],
                                    'after': steps[5]['result']['image_path'], 'layout': layout['result']})
            assert len(set(operation_ids)) == 3
            report_text = Path(matrix['report_path']).read_text(encoding='utf-8')
            assert 'layout_assert' in report_text and 'element_click_verified' in report_text

            async def failure(name, steps, code):
                bad = {**copy.deepcopy(spec), 'name': name, 'devices': spec['devices'][:1], 'steps': [spec['steps'][0], *steps]}
                result = await finish(await call('scenario_run', build_id=build['build_id'], scenario=bad, operation_id=name))
                assert result['state'] == 'failed' and result['cases'][0]['error']['code'] == code, result
                assert result['cases'][0]['cleanup'] == 'stopped'
                frame = result['cases'][0]['failure_capture']
                assert digest(frame['image_path']) == frame['image_sha256']
                runs.append(result)
                return result

            unknown = await failure('unknown-layout', [{'kind': 'layout_assert', 'rules': [
                {'kind': 'inside_safe_area', 'selector': {'id': 'absent'}}]}], 'LAYOUT_UNKNOWN')
            unknown_steps = await call('scenario_get', run_id=unknown['run_id'], case_index=0)
            assert unknown_steps['steps'][1]['result']['state'] == 'unknown'
            assert ' open' in Path(unknown['report_path']).read_text(encoding='utf-8')
            await failure('overlap-layout', [{'kind': 'layout_assert', 'rules': [
                {'kind': 'no_overlap', 'selector': {'id': 'verify_button'}, 'other': {'id': 'verify_button'}}]}], 'ASSERTION_FAILED')
            await failure('wrong-business-state', [{'kind': 'application_command', 'name': 'state',
                                                   'expect_result': {'clicks': 99}}], 'ASSERTION_FAILED')
            await failure('wrong-event', [{'kind': 'element_click_verified', 'selector': {'id': 'verify_button'},
                                           'event': 'never_emitted', 'timeout_ms': 500}], 'BUSINESS_TIMEOUT')

            cancel = {**copy.deepcopy(spec), 'name': 'Cancel correlated wait after actual click', 'steps': [spec['steps'][0],
                {'kind': 'element_click_verified', 'selector': {'id': 'verify_button'}, 'event': 'never_emitted', 'timeout_ms': 30000}]}
            job = await call('scenario_run', build_id=build['build_id'], scenario=cancel, operation_id='cancel-ack')
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                status = await call('job_status', job_id=job['job_id'])
                assert status['state'] not in ('complete', 'failed', 'cancelled'), status
                progress = status.get('progress', {})
                if progress.get('step_index') == 1:
                    current = await call('scenario_get', run_id=progress['run_id'])
                    sid = current['cases'][0]['session_id']
                    logs = await call('logs_read', session_id=sid)
                    if any('BUTTON_CLICKED 1' in entry['text'] for entry in logs['entries']):
                        break
                await asyncio.sleep(.1)
            else:
                raise AssertionError('No business click observed before cancellation')
            cancelled_at = time.monotonic()
            await call('job_cancel', job_id=job['job_id'])
            cancelled = await finish(job, 'cancelled')
            cancel_seconds = time.monotonic() - cancelled_at
            assert cancelled['summary']['skipped'] == 2 and cancel_seconds < 10, cancelled
            runs.append(cancelled)
            for result in runs:
                assert Path(result['report_path']).is_file()
                for case in result['cases']:
                    if case.get('session_id'):
                        response = await client.call_tool('defold_session_status', {'session_id': case['session_id']})
                        assert response.isError, 'Scenario leaked a session'
                        if sys.platform == 'win32':
                            assert_process_exited(case['pid'])
            assert original == digest(ROOT / 'scripts/automation/fixture/main.gui_script')
            verification = {'passed': True, 'elapsed_seconds': time.monotonic() - started,
                            'cancel_seconds': cancel_seconds, 'screenshots': screenshots, 'runs': runs}
            (directory / 'verification.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'report': matrix['report_path'],
                              'elapsed_seconds': verification['elapsed_seconds'], 'cancel_seconds': cancel_seconds}), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
