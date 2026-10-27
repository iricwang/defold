"""Run from the isolated real-editor integration test; no visible Stage or user's project required."""
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

# Real graph snapshots and edits must affect isolated runtime pixels while original disk bytes remain unchanged.
async def main(project):
    directory=ROOT/'tmp/automation-editor-verification'/uuid.uuid4().hex
    before_hash=digest(project/'probe.gui')
    params=StdioServerParameters(command=sys.executable,args=['-X','utf8',str(ROOT/'scripts/automation/server.py'),
        '--project',str(project),'--artifacts',str(directory)])
    async with stdio_client(params) as (read,write):
        async with ClientSession(read,write) as client:
            await client.initialize()
            async def call(tool,**args):
                response=await client.call_tool('defold_'+tool,args)
                assert not response.isError,(tool,response.structuredContent)
                return response.structuredContent
            async def finish(job):
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    result=await call('job_status',job_id=job['job_id'])
                    if result['state']=='complete':return result['result']
                    assert result['state'] not in ('failed','cancelled'),result
                    await asyncio.sleep(.1)
                raise AssertionError('Editor snapshot build timed out')
            pid=(await call('project_discover'))['projects'][0]['project_id']
            def guard(snapshot):
                return {'project_id':pid,'path':'/probe.gui','editorSessionId':snapshot['editorSessionId'],
                        'expectedRevision':snapshot['editorGraphRevision'],'expectedHistory':snapshot['historyRevision']}
            cases=[]
            async def capture(label):
                build=await finish(await call('editor_preview_build',project_id=pid,resource_path='/probe.gui',operation_id='build-'+label))
                game=await finish(await call('session_start',build_id=build['build_id'],device={'width':640,'height':480},operation_id='start-'+label))
                try:
                    frame=await call('capture',session_id=game['session_id'])
                    cases.append({'label':label,'build':build,'frame':frame,'report':await call('report_export',session_id=game['session_id'])})
                    return frame
                finally:await call('session_stop',session_id=game['session_id'])
            initial=await call('editor_snapshot',project_id=pid,path='/probe.gui')
            first=await capture('before')
            edited=await call('editor_gui_edit',**guard(initial),operation_id='move',changes=[{'id':'button','property':'position','value':[200,180,0]}])
            assert edited['dirty'] and digest(project/'probe.gui')==before_hash
            second=await capture('edited')
            changed=await call('image_compare',before_id=first['artifact_id'],after_id=second['artifact_id'],channel_threshold=0,max_changed_ratio=0)
            assert changed['changed_pixels']>0,changed
            undone=await call('editor_history',**guard(edited),transactionId='move',action='undo',operation_id='undo')
            assert undone['source']==initial['source']
            third=await capture('undone')
            restored=await call('image_compare',before_id=first['artifact_id'],after_id=third['artifact_id'],channel_threshold=0,max_changed_ratio=0)
            assert restored['changed_pixels']==0,restored
            assert digest(project/'probe.gui')==before_hash
            result={'passed':True,'cases':cases,'changed':changed,'restored':restored,'original_disk_unchanged':True}
            (directory/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
            print(json.dumps({'passed':True,'directory':str(directory),'changed_pixels':changed['changed_pixels'],'restored_pixels':restored['changed_pixels']}),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--project',type=Path,required=True)
    asyncio.run(main(parser.parse_args().project))
