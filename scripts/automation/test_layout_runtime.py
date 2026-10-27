"""Real MCP same-render geometry, semantic input, clipping, QA correlation and device-mask regression."""
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


# Moving nodes expose a one-frame scene/image mismatch; named input must reach the actual application callback.
async def main():
    root=ROOT/'tmp/automation-layout-verification'/uuid.uuid4().hex
    project=root/'fixture'
    shutil.copytree(ROOT/'scripts/automation/fixture',project)
    shutil.copyfile(ROOT/'scripts/automation/sdk/automation.lua',project/'automation.lua')
    script=(project/'main.gui_script').read_text()
    script='local qa = require "automation"\n'+script
    script=script.replace('print("AUTOMATION_READY")', '''print("AUTOMATION_READY")
    qa.register("state",function() return {clicks=self.clicks} end)
    qa.register("remove_moving",function() gui.delete_node(self.moving);self.moving=nil;return {removed=true} end)
    self.moving=gui.new_box_node(vmath.vector3(25,25,0),vmath.vector3(12,12,0))
    gui.set_color(self.moving,vmath.vector4(0,1,1,1));gui.set_id(self.moving,"moving")
    self.elapsed=0
    self.clip=gui.new_box_node(vmath.vector3(270,100,0),vmath.vector3(40,40,0))
    gui.set_id(self.clip,"clip");gui.set_clipping_mode(self.clip,gui.CLIPPING_MODE_STENCIL)
    gui.set_clipping_visible(self.clip,false)
    self.clipped=gui.new_box_node(vmath.vector3(30,0,0),vmath.vector3(60,20,0))
    gui.set_id(self.clipped,"clipped");gui.set_parent(self.clipped,self.clip)
    gui.set_color(self.clipped,vmath.vector4(1,0,1,1))
    qa.ready()''')
    script=script.replace('function update(self, dt)', '''function update(self, dt)
    qa.update(dt)
    self.elapsed=self.elapsed+dt
    if self.moving then gui.set_position(self.moving,vmath.vector3(25+((self.elapsed*100)%220),25,0)) end''')
    script=script.replace('print("BUTTON_CLICKED " .. self.clicks)','print("BUTTON_CLICKED " .. self.clicks)\n            qa.ack("button_clicked",{clicks=self.clicks})')
    (project/'main.gui_script').write_text(script,encoding='utf-8')
    params=StdioServerParameters(command=sys.executable,args=['-X','utf8',str(ROOT/'scripts/automation/server.py'),
        '--project',str(project),'--artifacts',str(root/'artifacts')])
    async with stdio_client(params) as (read,write):
        async with ClientSession(read,write) as client:
            await client.initialize()
            async def call(tool_name,**args):
                r=await client.call_tool('defold_'+tool_name,args)
                assert not r.isError,(tool_name,r.structuredContent)
                return r.structuredContent
            async def finish(job):
                deadline=time.monotonic()+120
                while time.monotonic()<deadline:
                    r=await call('job_status',job_id=job['job_id'])
                    if r['state'] in ('complete','failed','cancelled'):
                        assert r['state']=='complete',r
                        return r['result']
                    await asyncio.sleep(.1)
                raise AssertionError('job timed out')
            pid=(await call('project_discover'))['projects'][0]['project_id']
            build=await finish(await call('build_run',project_id=pid,operation_id='build'))
            session=await finish(await call('session_start',build_id=build['build_id'],device={'width':640,'height':480},
                                            app_channel=True,operation_id='start'))
            sid=session['session_id']; lease=session['lease_id']
            try:
                await call('wait',session_id=sid,contains='AUTOMATION_READY',after=0)
                samples=[]
                for _ in range(8):
                    frame=await call('capture',session_id=sid)
                    geometry=await call('element_query',artifact_id=frame['artifact_id'],selector={'id':'moving'})
                    element=geometry['elements'][0]
                    assert geometry['render_sequence']==frame['render_sequence']
                    assert element['geometry_precision']=='engine_gui_rectangle',element
                    with Image.open(frame['image_path']) as image:
                        points=[(x,y) for y in range(405,450) for x in range(640) if image.getpixel((x,y))[:3]==(0,255,255)]
                    assert points,'Moving cyan node not found'
                    actual=[min(x for x,y in points),min(y for x,y in points),max(x for x,y in points)+1,max(y for x,y in points)+1]
                    q=element['quad']; expected=[min(p[0] for p in q),min(p[1] for p in q),max(p[0] for p in q),max(p[1] for p in q)]
                    assert max(abs(a-b) for a,b in zip(actual,expected))<=1,(actual,expected)
                    samples.append({'render_sequence':frame['render_sequence'],'pixel_bounds':actual,'engine_bounds':expected})
                clipped=(await call('element_query',artifact_id=frame['artifact_id'],selector={'id':'clipped'}))['elements'][0]
                assert clipped['geometry_precision']=='engine_gui_rectangle'
                assert max(p[0] for p in clipped['visible_polygon'])<=580.01,clipped
                verified=await call('element_click_verified',session_id=sid,lease_id=lease,observation_id=frame['observation_id'],
                    selector={'id':'verify_button'},event='button_clicked',operation_id='named-click')
                assert verified['business_confirmed'] and verified['application_ack']['operation_id']=='named-click'
                state=await call('application_command',session_id=sid,lease_id=lease,operation_id='read-state',name='state')
                assert state['message']['payload']['clicks']==1
                duplicate=await call('element_click_verified',session_id=sid,lease_id=lease,observation_id=frame['observation_id'],
                    selector={'id':'verify_button'},event='button_clicked',operation_id='named-click')
                assert duplicate['application_ack']==verified['application_ack']
                check=await call('layout_check',artifact_id=verified['after']['artifact_id'],rules=[
                    {'kind':'inside_safe_area','selector':{'id':'verify_button'}},
                    {'kind':'no_overlap','selector':{'id':'verify_button'},'other':{'id':'drag_handle'}}])
                assert check['state']=='passed',check
                negative=[]
                for label,selector,code in [('ambiguous',{'type':'gui_node_box'},'AMBIGUOUS_ELEMENT'),('hidden',{'id':'clip'},'ELEMENT_NOT_INTERACTABLE')]:
                    response=await client.call_tool('defold_element_click',{'session_id':sid,'lease_id':lease,'observation_id':frame['observation_id'],'selector':selector,'operation_id':label})
                    assert response.isError and response.structuredContent['error']['code']==code,response.structuredContent
                    negative.append(response.structuredContent)
                response=await client.call_tool('defold_element_click_verified',{'session_id':sid,'lease_id':lease,'observation_id':frame['observation_id'],
                    'selector':{'id':'verify_button'},'event':'wrong_event','operation_id':'wrong-event','timeout_ms':100})
                assert response.isError and response.structuredContent['error']['code']=='BUSINESS_TIMEOUT',response.structuredContent
                state=await call('application_command',session_id=sid,lease_id=lease,operation_id='read-state-2',name='state')
                assert state['message']['payload']['clicks']==2,'Timeout must not undo or replay the actual click'
                await call('application_command',session_id=sid,lease_id=lease,operation_id='delete-node',name='remove_moving')
                response=await client.call_tool('defold_element_click',{'session_id':sid,'lease_id':lease,'observation_id':frame['observation_id'],
                    'selector':{'id':'moving'},'operation_id':'stale-node'})
                assert response.isError and response.structuredContent['error']['code']=='STALE_ELEMENT',response.structuredContent
                negative.append(response.structuredContent)
                preset=await call('device_resolve',name='Apple iPhone X',rotation=0)
                phone=await call('device_apply',session_id=sid,device=preset['device'],expected_revision=frame['device_revision'],operation_id='phone')
                before=digest(phone['image_path'])
                overlay=await call('device_overlay',artifact_id=phone['artifact_id'],name='Apple iPhone X',rotation=0)
                assert before==digest(phone['image_path']) and overlay['artifact_kind']=='device_overlay'
                landscape=await call('device_resolve',name='Apple iPhone X',rotation=1)
                wide=await call('device_apply',session_id=sid,device=landscape['device'],expected_revision=phone['device_revision'],operation_id='landscape')
                wide_overlay=await call('device_overlay',artifact_id=wide['artifact_id'],name='Apple iPhone X',rotation=1)
                report=await call('report_export',session_id=sid)
                result={'passed':True,'samples':samples,'clipped':clipped,'business_ack':verified['application_ack'],
                        'layout':check,'negative_cases':negative,'overlay':overlay,'landscape_overlay':wide_overlay,'report':report}
                (root/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
                print(json.dumps({'passed':True,'root':str(root),'report':report}),flush=True)
            finally: await call('session_stop',session_id=sid)


if __name__=='__main__':asyncio.run(main())
