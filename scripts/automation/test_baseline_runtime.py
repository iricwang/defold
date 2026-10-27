"""Real MCP restart, multi-device pinned baselines, differences and immutable evidence."""
import asyncio
import base64
import copy
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid

from server import ROOT
from build import digest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from test_scenario_runtime import assert_process_exited


async def call(client, tool_name, **arguments):
    response = await client.call_tool('defold_' + tool_name, arguments)
    assert not response.isError, (tool_name, response.structuredContent)
    return response.structuredContent


async def finish(client, job, expected='complete'):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        status = await call(client, 'job_status', job_id=job['job_id'])
        if status['state'] in ('complete', 'failed', 'cancelled'):
            assert status['state'] == expected, status
            return status
        await asyncio.sleep(.1)
    raise AssertionError('Background job timed out')


# Baseline PNGs must survive a complete stdio server exit and detect a later game's actual changed UI.
async def main():
    directory = ROOT / 'tmp/automation-baseline-verification' / uuid.uuid4().hex
    baselines = directory / 'baselines'
    devices = json.loads((ROOT / 'scripts/automation/fixture/scenario.json').read_text())['devices']
    saved = {}
    initial_frames = {}
    started = time.monotonic()

    def parameters(phase):
        return StdioServerParameters(command=sys.executable, args=['-X', 'utf8',
            str(ROOT / 'scripts/automation/server.py'), '--project', str(ROOT / 'scripts/automation/fixture'),
            '--artifacts', str(directory / phase), '--baselines', str(baselines)])

    async with stdio_client(parameters('save-session')) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()
            pid = (await call(client, 'project_discover'))['projects'][0]['project_id']
            build = (await finish(client, await call(client, 'build_run', project_id=pid, operation_id='build')))['result']
            for index, device in enumerate(devices):
                session = (await finish(client, await call(client, 'session_start', build_id=build['build_id'],
                    device=device['device'], operation_id='start-' + str(index))))['result']
                sid = session['session_id']
                try:
                    await call(client, 'wait', session_id=sid, contains='AUTOMATION_READY', after=0)
                    frame = await call(client, 'capture', session_id=sid)
                    await call(client, 'element_click', session_id=sid, lease_id=session['lease_id'],
                               observation_id=frame['observation_id'], selector={'id': 'verify_button'}, operation_id='click-' + str(index))
                    await call(client, 'wait', session_id=sid, contains='BUTTON_CLICKED 1', after=0)
                    frame = await call(client, 'capture', session_id=sid)
                    initial_frames[device['name']] = frame
                    request = {'project_id': pid, 'name': 'verified-button', 'artifact_id': frame['artifact_id'],
                               'operation_id': 'save-' + str(index)}
                    baseline = await call(client, 'baseline_save', **request)
                    assert baseline == await call(client, 'baseline_save', **request)
                    saved[device['name']] = baseline
                finally:
                    await call(client, 'session_stop', session_id=sid)
            listed = await call(client, 'baseline_list', project_id=pid, name='verified-button')
            assert listed['total'] == 3
    # The first service and all its in-memory IDs are gone; only the explicit baseline store is shared.
    async with stdio_client(parameters('verify-session')) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()
            assert (await call(client, 'baseline_list', project_id=pid))['total'] == 3
            for name, baseline in saved.items():
                loaded = await call(client, 'baseline_get', project_id=pid, baseline_id=baseline['baseline_id'])
                assert loaded['artifact_kind'] == 'baseline'
                image = await client.read_resource(loaded['uri'])
                assert hashlib.sha256(base64.b64decode(image.contents[0].blob)).hexdigest() == initial_frames[name]['image_sha256']
                old = await client.call_tool('defold_artifact_get', {'artifact_id': initial_frames[name]['artifact_id']})
                assert old.isError, 'Old live artifact IDs must not be restored as input observations'
            build = (await finish(client, await call(client, 'build_run', project_id=pid, operation_id='build')))['result']
            spec = {'version': 2, 'name': 'Cross-session GUI baseline regression', 'devices': devices, 'steps': [
                {'kind': 'wait_log', 'contains': 'AUTOMATION_READY', 'after': 'startup'},
                {'kind': 'element_click', 'selector': {'id': 'verify_button'}, 'expect_log': 'BUTTON_CLICKED 1'},
                {'kind': 'baseline_assert', 'baselines': {name: value['baseline_id'] for name, value in saved.items()},
                 'channel_threshold': 0, 'max_changed_ratio': 0}]}
            (directory / 'scenario-baseline.json').write_text(json.dumps(spec, ensure_ascii=False, indent=2), encoding='utf-8')
            matrix = (await finish(client, await call(client, 'scenario_run', build_id=build['build_id'],
                scenario=spec, operation_id='matrix')))['result']
            assert matrix['state'] == 'passed' and matrix['summary']['passed'] == 3, matrix
            comparisons = []
            for index in range(3):
                page = await call(client, 'scenario_get', run_id=matrix['run_id'], case_index=index)
                result = page['steps'][2]['result']
                assert result['verdict'] == 'match' and result['changed_pixels'] == 0, result
                assert result['baseline']['baseline_id'] == saved[devices[index]['name']]['baseline_id']
                comparisons.append(result)

            bad = copy.deepcopy(spec)
            bad['name'] = 'Expected failure: button changes after a second click'
            bad['steps'].insert(2, {'kind': 'element_click', 'selector': {'id': 'verify_button'}, 'expect_log': 'BUTTON_CLICKED 2'})
            failed = (await finish(client, await call(client, 'scenario_run', build_id=build['build_id'],
                scenario=bad, operation_id='changed-ui')))['result']
            assert failed['summary']['failed'] == 3, failed
            differences = []
            for index, case in enumerate(failed['cases']):
                assert case['error']['code'] == 'ASSERTION_FAILED' and case['cleanup'] == 'stopped'
                page = await call(client, 'scenario_get', run_id=failed['run_id'], case_index=index)
                result = page['steps'][3]['result']
                assert result['verdict'] == 'different' and result['changed_pixels'] > 0
                for key in ('before_id', 'after_id', 'comparison_id'):
                    artifact = await call(client, 'artifact_get', artifact_id=result[key])
                    assert Path(artifact['image_path']).is_file()
                differences.append(result)
            # Missing revisions fail preflight, not after starting a game or replacing a baseline.
            missing = copy.deepcopy(spec)
            missing['steps'][-1]['baselines'] = {name: '0' * 64 for name in saved}
            rejected = await finish(client, await call(client, 'scenario_run', build_id=build['build_id'],
                scenario=missing, operation_id='missing'), 'failed')
            assert rejected['error_code'] == 'BASELINE_NOT_FOUND', rejected
            assert (await call(client, 'baseline_list', project_id=pid))['total'] == 3
            for name, baseline in saved.items():
                loaded = await call(client, 'baseline_get', project_id=pid, baseline_id=baseline['baseline_id'])
                assert digest(loaded['image_path']) == initial_frames[name]['image_sha256']
            for run in (matrix, failed):
                for case in run['cases']:
                    assert case['cleanup'] == 'stopped'
                    if sys.platform == 'win32':
                        assert_process_exited(case['pid'])
            result = {'passed': True, 'elapsed_seconds': time.monotonic() - started,
                      'baselines': saved, 'matches': comparisons, 'differences': differences,
                      'matrix': matrix, 'expected_failure': failed, 'missing_baseline': rejected}
            (directory / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'elapsed_seconds': result['elapsed_seconds'],
                              'match_report': matrix['report_path'], 'difference_report': failed['report_path']}), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
