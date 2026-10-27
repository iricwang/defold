"""Run a JSON scenario through the real stdio MCP server and print its report paths."""
import argparse
import asyncio
import json
from pathlib import Path
import sys
import time
import uuid

from server import ROOT, SCENARIO
from scenarios import validate
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def execute(args):
    if args.scenario.stat().st_size > 1024 * 1024:
        raise ValueError('Scenario file exceeds 1 MiB')
    spec = json.loads(args.scenario.read_text(encoding='utf-8'))
    validate(spec, SCENARIO)
    command = [str(ROOT / 'scripts/automation/server.py'), '--manifest', str(args.manifest.resolve()),
               '--project', str(args.project.resolve()), '--artifacts', str(args.output.resolve())]
    parameters = StdioServerParameters(command=sys.executable, args=['-X', 'utf8', *command])
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()

            async def call(name, **arguments):
                response = await client.call_tool('defold_' + name, arguments)
                if response.isError:
                    raise RuntimeError(response.structuredContent)
                return response.structuredContent

            async def finish(job):
                previous = None
                try:
                    while True:
                        status = await call('job_status', job_id=job['job_id'])
                        if status['state'] in ('complete', 'failed', 'cancelled'):
                            if 'result' not in status:
                                raise RuntimeError(status)
                            return status['result']
                        progress = status.get('progress', {'state': status['state']})
                        if progress != previous:
                            print(json.dumps(progress, ensure_ascii=False), file=sys.stderr, flush=True)
                            previous = progress
                        await asyncio.sleep(0.1)
                except asyncio.CancelledError:
                    await call('job_cancel', job_id=job['job_id'])
                    deadline = time.monotonic() + 45
                    while time.monotonic() < deadline:
                        status = await call('job_status', job_id=job['job_id'])
                        if status['state'] in ('complete', 'failed', 'cancelled'):
                            print(json.dumps(status, ensure_ascii=False), file=sys.stderr, flush=True)
                            break
                        await asyncio.sleep(0.1)
                    raise

            project = (await call('project_discover'))['projects'][0]['project_id']
            source_preview = args.resource and args.source == 'disk'
            build_tool = 'preview_build' if source_preview else ('build_run' if args.source == 'disk' else 'build_import')
            options = {'resource_path': args.resource} if source_preview else {}
            build = await finish(await call(build_tool, project_id=project, operation_id=uuid.uuid4().hex, **options))
            if args.resource and args.source == 'existing_compiled':
                build = await finish(await call('preview_import', build_id=build['build_id'],
                                               resource_path=args.resource, operation_id=uuid.uuid4().hex))
            return await finish(await call('scenario_run', build_id=build['build_id'], scenario=spec,
                                           operation_id=uuid.uuid4().hex))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--scenario', type=Path, required=True)
    parser.add_argument('--source', choices=['disk', 'existing_compiled'], default='disk')
    parser.add_argument('--resource', help='Preview one saved .gui/.go/.collection project path, e.g. /gui/login.gui')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'tmp/automation-runtime/manifest.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'tmp/automation-runs' / uuid.uuid4().hex)
    args = parser.parse_args()
    result = asyncio.run(execute(args))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['state'] == 'passed' else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
