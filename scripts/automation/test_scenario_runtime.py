"""Real MCP scenario matrix, failure evidence and cooperative cancellation."""
import asyncio
import base64
import copy
import ctypes
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid

from server import ROOT
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


def assert_process_exited(pid):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.OpenProcess(0x100000, False, pid)
    if not handle:
        error=ctypes.get_last_error()
        if error==87:return
        # A PID may already have been reused by a protected unrelated process after earlier matrix cases.
        class Entry(ctypes.Structure):
            _fields_=[('size',ctypes.c_ulong),('usage',ctypes.c_ulong),('pid',ctypes.c_ulong),
                      ('heap',ctypes.c_size_t),('module',ctypes.c_ulong),('threads',ctypes.c_ulong),
                      ('parent',ctypes.c_ulong),('priority',ctypes.c_long),('flags',ctypes.c_ulong),('exe',ctypes.c_wchar*260)]
        kernel.CreateToolhelp32Snapshot.restype=ctypes.c_void_p
        kernel.Process32FirstW.argtypes=[ctypes.c_void_p,ctypes.POINTER(Entry)]
        kernel.Process32NextW.argtypes=[ctypes.c_void_p,ctypes.POINTER(Entry)]
        snapshot=kernel.CreateToolhelp32Snapshot(2,0)
        assert snapshot and snapshot!=ctypes.c_void_p(-1).value,'Cannot inspect process inventory'
        entry=Entry();entry.size=ctypes.sizeof(entry);matched=None
        try:
            found=kernel.Process32FirstW(snapshot,ctypes.byref(entry))
            assert found,'Could not read process inventory'
            while found:
                if entry.pid==pid:matched=entry.exe.lower();break
                found=kernel.Process32NextW(snapshot,ctypes.byref(entry))
            assert matched!='dmengine.exe',f'Owned game still exists but cannot be inspected: {pid}, error {error}'
        finally:kernel.CloseHandle(snapshot)
        return
    try:
        assert kernel.WaitForSingleObject(handle, 0) == 0, 'Scenario left a running game process'
    finally:
        kernel.CloseHandle(handle)


async def main():
    directory = ROOT / 'tmp/automation-scenario-verification' / uuid.uuid4().hex
    parameters = StdioServerParameters(command=sys.executable, args=['-X', 'utf8',
        str(ROOT / 'scripts/automation/server.py'), '--project', str(ROOT / 'scripts/automation/fixture'),
        '--artifacts', str(directory)])
    started = time.monotonic()
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()

            async def call(name, **arguments):
                response = await client.call_tool('defold_' + name, arguments)
                assert not response.isError, (name, response.model_dump())
                return response.structuredContent

            async def finish(job, expected='complete'):
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    status = await call('job_status', job_id=job['job_id'])
                    if status['state'] in ('complete', 'failed', 'cancelled'):
                        assert status['state'] == expected, status
                        return status['result']
                    await asyncio.sleep(0.05)
                raise AssertionError('Scenario job timed out')

            project = (await call('project_discover'))['projects'][0]['project_id']
            build = await finish(await call('build_run', project_id=project, operation_id='build'))
            spec = json.loads((ROOT / 'scripts/automation/fixture/scenario.json').read_text())
            request = dict(build_id=build['build_id'], scenario=spec, operation_id='matrix')
            job = await call('scenario_run', **request)
            assert job == await call('scenario_run', **request), 'Duplicate scenario created another run'
            matrix = await finish(job)
            assert matrix['state'] == 'passed', matrix
            assert matrix['summary']['passed'] == 3
            for index, case in enumerate(matrix['cases']):
                assert case['step_count'] == 7 and case['cleanup'] == 'stopped'
                first = await call('scenario_get', run_id=matrix['run_id'], case_index=index, limit=4)
                assert first['next_offset'] == 4
                second = await call('scenario_get', run_id=matrix['run_id'], case_index=index, offset=4, limit=4)
                steps = first['steps'] + second['steps']
                assert steps[3]['result']['log_evidence']['state'] == 'condition_satisfied'
                comparison = steps[5]['result']
                assert comparison['verdict'] == 'different' and comparison['changed_pixels'] > 0

            bad = copy.deepcopy(spec)
            bad['name'] = 'Expected failure: incorrect pixel assertion'
            bad['devices'] = bad['devices'][:1]
            bad['steps'][5]['expected'] = 'match'
            failure = await finish(await call('scenario_run', build_id=build['build_id'], scenario=bad, operation_id='failure'))
            assert failure['state'] == 'failed'
            case = failure['cases'][0]
            assert case['error']['code'] == 'ASSERTION_FAILED' and case['cleanup'] == 'stopped'
            failed_steps = await call('scenario_get', run_id=failure['run_id'], case_index=0, offset=5)
            assert failed_steps['steps'][-1]['result']['verdict'] == 'different'
            artifact = case['failure_capture']
            resource = await client.read_resource(artifact['uri'])
            assert hashlib.sha256(base64.b64decode(resource.contents[0].blob)).hexdigest() == artifact['image_sha256']
            assert Path(case['report']['report_path']).is_file()

            timeout = copy.deepcopy(bad)
            timeout['name'] = 'Expected failure: missing business log'
            timeout['steps'] = [{'kind': 'wait_log', 'contains': 'NEVER_EMITTED', 'after': 'startup', 'timeout_ms': 200}]
            timed = await finish(await call('scenario_run', build_id=build['build_id'], scenario=timeout, operation_id='timeout'))
            assert timed['cases'][0]['error']['code'] == 'CONDITION_TIMEOUT'
            assert timed['cases'][0]['cleanup'] == 'stopped'

            cancel = copy.deepcopy(spec)
            cancel['name'] = 'Expected cancellation while waiting for business event'
            cancel['steps'] = [{'kind': 'wait_log', 'contains': 'NEVER_EMITTED', 'after': 'startup', 'timeout_ms': 30000}]
            job = await call('scenario_run', build_id=build['build_id'], scenario=cancel, operation_id='cancel')
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                status = await call('job_status', job_id=job['job_id'])
                if status.get('progress', {}).get('phase') == 'step': break
                assert status['state'] not in ('failed', 'complete', 'cancelled'), status
                await asyncio.sleep(0.05)
            else: raise AssertionError('No cancellable step observed')
            cancelled_at = time.monotonic()
            await call('job_cancel', job_id=job['job_id'])
            cancelled = await finish(job, expected='cancelled')
            cancel_seconds = time.monotonic() - cancelled_at
            assert cancelled['state'] == 'cancelled' and cancelled['summary']['skipped'] == 2
            assert cancelled['cases'][0]['cleanup'] == 'stopped'
            assert cancel_seconds < 10, cancel_seconds
            results = [matrix, failure, timed, cancelled]
            for result in results:
                assert Path(result['report_path']).is_file()
                for case in result['cases']:
                    if case.get('session_id'):
                        response = await client.call_tool('defold_session_status', {'session_id': case['session_id']})
                        assert response.isError, 'Scenario left an accessible live session'
                        assert_process_exited(case['pid'])
            verification = {'passed': True, 'runs': results, 'elapsed_seconds': time.monotonic() - started,
                            'cancel_seconds': cancel_seconds, 'checks': ['3 device matrix', 'duplicate run idempotency',
                            'business log cursor evidence', 'scene assertion', 'pixel assertion', 'failure PNG resource SHA256',
                            'missing log timeout diagnostics', 'cancel wait and skip remaining devices', 'session cleanup']}
            (directory / 'verification.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({'passed': True, 'directory': str(directory), 'elapsed_seconds': verification['elapsed_seconds'],
                              'cancel_seconds': cancel_seconds, 'reports': [r['report_path'] for r in results]}, indent=2), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
