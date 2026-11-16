# Copyright 2020-2026 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

import sys,asyncio,json,time
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description='Run an isolated compiled benchmark through the Defold background MCP server.')
p.add_argument('repository', type=Path);p.add_argument('directory', type=Path)
a=p.parse_args();r=a.repository.resolve();w=a.directory.resolve()
sys.path.insert(0,str(r/'tmp/automation-python'))
from mcp import ClientSession,StdioServerParameters
from mcp.client.stdio import stdio_client
async def main():
 params=StdioServerParameters(command=sys.executable,args=[str(r/'scripts/automation/server.py'),'--manifest',str(w/'automation/manifest.json'),'--project',str(w/'project'),'--artifacts',str(w/'background'),'--engine',str(w/'automation/engine/dmengine')])
 async with stdio_client(params) as (read,write):
  async with ClientSession(read,write) as client:
   await client.initialize()
   async def call(name,**args):
    response=await client.call_tool('defold_'+name,args)
    if response.isError: raise RuntimeError((name,response.structuredContent,response.content))
    return response.structuredContent
   async def finish(job):
    while True:
     status=await call('job_wait',job_id=job['job_id'],timeout_ms=10000)
     if status['state']=='complete':return status['result']
     if status['state'] in ['failed','cancelled']:raise RuntimeError(status)
   discovered=await call('project_discover');pid=discovered['projects'][0]['project_id']
   build=await finish(await call('build_import',project_id=pid,operation_id='import'))
   (w/'background-build.json').write_text(json.dumps(build,indent=2))
   session=await finish(await call('session_start',build_id=build['build_id'],device={'width':1280,'height':720},operation_id='start'))
   (w/'background-session.json').write_text(json.dumps(session,indent=2));print('started',session,flush=True)
   sid=session['session_id'];cursor=0;entries=[];frames=[];deadline=time.monotonic()+150
   try:
    captured=False
    while time.monotonic()<deadline:
     log=await call('logs_read',session_id=sid,after=cursor);entries+=log.get('entries',[]);cursor=log.get('cursor',log.get('next_cursor',cursor))
     for e in log.get('entries',[]):
      print(e['text'],flush=True)
      if 'BENCH_PHASE' in e['text'] and '"count":1,' in e['text'] and not captured:
       frames.append(await call('capture',session_id=sid));captured=True
     (w/'background-logs.json').write_text(json.dumps(entries,indent=2))
     if any('BENCH_DONE' in e['text'] for e in log.get('entries',[])):break
     await asyncio.sleep(2)
    else:raise RuntimeError('benchmark did not complete')
    frames.append(await call('capture',session_id=sid))
    (w/'background-captures.json').write_text(json.dumps(frames,indent=2))
    report=await call('report_export',session_id=sid);(w/'background-report.json').write_text(json.dumps(report,indent=2))
   finally: print('stop',await call('session_stop',session_id=sid),flush=True)
asyncio.run(main())
