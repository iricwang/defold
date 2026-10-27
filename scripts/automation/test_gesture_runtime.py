"""Real stdio MCP node drags: three devices, business drop ACK, idempotency and failure evidence."""
import asyncio
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
import layout


def fixture(directory):
    project = directory / 'fixture'
    shutil.copytree(ROOT / 'scripts/automation/fixture', project)
    shutil.copyfile(ROOT / 'scripts/automation/sdk/automation.lua', project / 'automation.lua')
    path = project / 'main.gui_script'
    script = 'local qa = require "automation"\n' + path.read_text(encoding='utf-8')
    script = script.replace('self.clicks = 0', '''self.clicks = 0
    self.drops = 0
    gui.set_id(self.title, "status")
    self.drop = gui.new_box_node(vmath.vector3(240, 55, 0), vmath.vector3(48, 40, 0))
    gui.set_id(self.drop, "drop_zone")
    gui.set_color(self.drop, vmath.vector4(0.1, 0.5, 0.7, 1))
    gui.move_above(self.handle, self.drop)
    qa.register("state", function() return {drops=self.drops} end)
    qa.register("replace_drop", function()
        gui.delete_node(self.drop)
        self.drop = gui.new_box_node(vmath.vector3(240, 55, 0), vmath.vector3(48, 40, 0))
        gui.set_id(self.drop, "drop_zone")
        return {replaced=true}
    end)
    qa.ready()''')
    script = script.replace('function update(self, dt)', 'function update(self, dt)\n    qa.update(dt)')
    # action.x/y are input logical coordinates, not a node's layout space under every FIT/safe-area transform.
    # Exercise the engine's actual screen-to-node conversion instead of copying logical input into gui.position.
    script = script.replace('gui.set_position(self.handle, vmath.vector3(action.x, action.y, 0))',
                            'gui.set_screen_position(self.handle, vmath.vector3(action.screen_x, action.screen_y, 0))')
    script = script.replace('if self.dragging then print("DRAG_COMPLETE " .. action.screen_x .. " " .. action.screen_y) end', '''if self.dragging then
                print("DRAG_COMPLETE " .. action.screen_x .. " " .. action.screen_y)
                if gui.pick_node(self.drop, action.x, action.y) then
                    self.drops = self.drops + 1
                    gui.set_text(self.title, "DROPPED " .. self.drops)
                    qa.ack("dropped", {drops=self.drops})
                end
            end''')
    path.write_text(script, encoding='utf-8')
    return project


async def main():
    directory = ROOT / 'tmp/automation-gesture-verification' / uuid.uuid4().hex
    project = fixture(directory)
    source_hash = digest(ROOT / 'scripts/automation/fixture/main.gui_script')
    params = StdioServerParameters(command=sys.executable, args=['-X', 'utf8', str(ROOT / 'scripts/automation/server.py'),
        '--project', str(project), '--artifacts', str(directory / 'artifacts')])
    started = time.monotonic()
    async with stdio_client(params) as (read, write):
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
                        return status['result']
                    await asyncio.sleep(.1)
                raise AssertionError('Job timed out')

            pid = (await call('project_discover'))['projects'][0]['project_id']
            build = await finish(await call('build_run', project_id=pid, operation_id='build'))
            spec = json.loads((project / 'scenario.json').read_text())
            drag = {'kind': 'element_drag_verified', 'selector': {'id': 'drag_handle'},
                    'destination': {'selector': {'id': 'drop_zone'}}, 'event': 'dropped', 'steps': 16}
            spec.update(version=2, name='GUI node drop across devices', app_channel=True, steps=[spec['steps'][0],
                {'kind': 'capture', 'name': 'before'}, drag,
                {'kind': 'application_command', 'name': 'state', 'expect_result': {'drops': 1}},
                {'kind': 'layout_assert', 'rules': [{'kind': 'inside_safe_area', 'selector': {'id': 'drag_handle'}}]},
                {'kind': 'element_drag', 'selector': {'id': 'drag_handle'},
                 'destination': {'coordinate_space': 'frame_normalized', 'x': .3, 'y': .6}, 'expect_log': 'DRAG_COMPLETE'},
                {'kind': 'application_command', 'name': 'state', 'expect_result': {'drops': 1}}])
            (project / 'scenario-gestures.json').write_text(json.dumps(spec, indent=2), encoding='utf-8')
            matrix = await finish(await call('scenario_run', build_id=build['build_id'], scenario=spec, operation_id='matrix'))
            assert matrix['state'] == 'passed' and matrix['summary']['passed'] == 3, matrix
            pictures, ids = [], []
            for index, case in enumerate(matrix['cases']):
                steps = (await call('scenario_get', run_id=matrix['run_id'], case_index=index, limit=8))['steps']
                result = steps[2]['result']
                assert result['business_confirmed'] and result['application_ack']['payload']['drops'] == 1
                assert result['application_ack']['operation_id'] == steps[2]['operation_id']
                ids.append(steps[2]['operation_id'])
                assert result['source']['resolved_observation_id'] == result['destination']['resolved_observation_id']
                frame = result['after']
                node = (await call('element_query', artifact_id=frame['artifact_id'], selector={'id': 'drag_handle'}))['elements'][0]
                actual, expected = layout.point(node), result['destination']['point']
                assert max(abs(a-b) for a,b in zip(actual, expected)) <= 1, (actual, expected)
                with Image.open(frame['image_path']) as image:
                    color = image.getpixel(tuple(expected))[:3]
                    assert color[0] >= 240 and 100 <= color[1] <= 180 and color[2] <= 80, color
                plain = steps[5]['result']['receipt']
                target = [round(.3*(plain['after']['width']-1)), round(.6*(plain['after']['height']-1))]
                moved = (await call('element_query', artifact_id=plain['after']['artifact_id'], selector={'id': 'drag_handle'}))['elements'][0]
                assert max(abs(a-b) for a,b in zip(layout.point(moved), target)) <= 1
                assert plain['business_confirmed'] is False and steps[5]['result']['correlation'] == 'cursor_only'
                pictures.append({'device': case['name'], 'before': steps[1]['result']['image_path'],
                                 'after': frame['image_path'], 'drop_point': expected, 'actual_point': actual})
                assert case['cleanup'] == 'stopped'
                if sys.platform == 'win32': assert_process_exited(case['pid'])
            assert len(set(ids)) == 3

            session = await finish(await call('session_start', build_id=build['build_id'], device=spec['devices'][0]['device'],
                                             app_channel=True, operation_id='negative-session'))
            sid, lease = session['session_id'], session['lease_id']
            negatives = []
            try:
                await call('wait', session_id=sid, contains='AUTOMATION_READY', after=0)
                frame = await call('capture', session_id=sid)
                args = {'session_id': sid, 'lease_id': lease, 'observation_id': frame['observation_id'],
                        **{k:v for k,v in drag.items() if k != 'kind'}, 'operation_id': 'drop-once'}
                first = await call('element_drag_verified', **args)
                again = await call('element_drag_verified', **args)
                assert first['application_ack'] == again['application_ack']
                wrong = {**args, 'observation_id': first['after']['observation_id'], 'event': 'wrong', 'operation_id': 'wrong-event', 'timeout_ms': 200}
                failed = await client.call_tool('defold_element_drag_verified', wrong)
                assert failed.isError and failed.structuredContent['error']['code'] == 'BUSINESS_TIMEOUT', failed
                assert failed.structuredContent['error']['details']['input_dispatched']
                negatives.append(failed.structuredContent)
                duplicate = await client.call_tool('defold_element_drag_verified', wrong)
                assert duplicate.isError and duplicate.structuredContent['error']['code'] == 'OPERATION_FAILED'
                state = await call('application_command', session_id=sid, lease_id=lease, operation_id='state', name='state')
                assert state['message']['payload'] == {'drops': 2}, state
                await call('application_command', session_id=sid, lease_id=lease, operation_id='replace', name='replace_drop')
                for label, destination, expected in [('stale', {'selector': {'id': 'drop_zone'}}, 'STALE_ELEMENT'),
                    ('unknown', {'selector': {'id': 'status'}}, 'ELEMENT_NOT_INTERACTABLE'),
                    ('missing', {'selector': {'id': 'absent'}}, 'AMBIGUOUS_ELEMENT')]:
                    response = await client.call_tool('defold_element_drag', {'session_id': sid, 'lease_id': lease,
                        'observation_id': frame['observation_id'], 'selector': {'id': 'drag_handle'},
                        'destination': destination, 'operation_id': label})
                    assert response.isError and response.structuredContent['error']['code'] == expected, response
                    negatives.append(response.structuredContent)
            finally:
                await call('session_stop', session_id=sid)
            if sys.platform == 'win32': assert_process_exited(session['pid'])
            cancel_spec = {**spec, 'name': 'Cancel after real drag', 'steps': [spec['steps'][0],
                           {**drag, 'event': 'never_emitted', 'timeout_ms': 30000}]}
            job = await call('scenario_run', build_id=build['build_id'], scenario=cancel_spec, operation_id='cancel-matrix')
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                status = await call('job_status', job_id=job['job_id'])
                assert status['state'] not in ('complete', 'failed', 'cancelled'), status
                progress = status.get('progress', {})
                if progress.get('step_index') == 1:
                    current = await call('scenario_get', run_id=progress['run_id'])
                    logs = await call('logs_read', session_id=current['cases'][0]['session_id'])
                    if any('DRAG_COMPLETE' in entry['text'] for entry in logs['entries']): break
                await asyncio.sleep(.1)
            else: raise AssertionError('Drag did not reach release before cancellation')
            cancelled_at = time.monotonic()
            await call('job_cancel', job_id=job['job_id'])
            cancelled = await finish(job, 'cancelled')
            cancel_seconds = time.monotonic() - cancelled_at
            assert cancelled['summary']['skipped'] == 2 and cancel_seconds < 10, cancelled
            detail = await call('scenario_get', run_id=cancelled['run_id'], case_index=0)
            assert detail['steps'][1]['result']['input_dispatched']
            assert Path(detail['steps'][1]['observation']['image_path']).is_file()
            assert cancelled['cases'][0]['cleanup'] == 'stopped'
            if sys.platform == 'win32': assert_process_exited(cancelled['cases'][0]['pid'])
            assert source_hash == digest(ROOT / 'scripts/automation/fixture/main.gui_script')
            proof = {'passed': True, 'elapsed_seconds': time.monotonic()-started, 'matrix': matrix,
                     'screenshots': pictures, 'negative_cases': negatives, 'cancelled': cancelled, 'cancel_seconds': cancel_seconds}
            (directory / 'verification.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'report': matrix['report_path'],
                              'elapsed_seconds': proof['elapsed_seconds']}), flush=True)


if __name__ == '__main__': asyncio.run(main())
