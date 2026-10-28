"""Real hidden GPU temporal capture: three devices, WebP resource, cancellation and device guards."""
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


async def main():
    directory = ROOT / 'tmp/automation-sequence-verification' / uuid.uuid4().hex
    project = directory / 'fixture'
    shutil.copytree(ROOT / 'scripts/automation/fixture', project)
    path = project / 'main.gui_script'
    script = path.read_text(encoding='utf-8').replace('self.clicks = 0', '''self.clicks = 0
    self.elapsed = 0
    self.moving = gui.new_box_node(vmath.vector3(40, 160, 0), vmath.vector3(18, 18, 0))
    gui.set_id(self.moving, "moving")
    gui.set_color(self.moving, vmath.vector4(0, 1, 1, 1))''')
    script = script.replace('function update(self, dt)', '''function update(self, dt)
    self.elapsed = self.elapsed + dt
    gui.set_position(self.moving, vmath.vector3(160 + 100 * math.sin(self.elapsed * 2), 160, 0))''')
    path.write_text(script, encoding='utf-8')
    params = StdioServerParameters(command=sys.executable, args=['-X','utf8',str(ROOT/'scripts/automation/server.py'),
        '--project',str(project),'--artifacts',str(directory/'artifacts')])
    started = time.monotonic()
    async with stdio_client(params) as (read,write):
        async with ClientSession(read,write) as client:
            await client.initialize()
            async def call(name, **args):
                response = await client.call_tool('defold_'+name,args)
                assert not response.isError,(name,response.structuredContent)
                return response.structuredContent
            async def finish(job, expected='complete'):
                deadline = time.monotonic()+150
                while time.monotonic()<deadline:
                    result = await call('job_wait',job_id=job['job_id'],timeout_ms=10000)
                    if result['terminal']:
                        assert result['state']==expected,result
                        return result
                raise AssertionError('Job timed out')
            pid = (await call('project_discover'))['projects'][0]['project_id']
            build = (await finish(await call('build_run',project_id=pid,operation_id='build')))['result']
            spec = json.loads((project/'scenario.json').read_text())
            spec.update(version=2,name='Temporal GPU sampling across devices',steps=[spec['steps'][0],
                {'kind':'capture_sequence','sample_count':8,'interval_ms':180}])
            (project/'scenario-sequences.json').write_text(json.dumps(spec,indent=2),encoding='utf-8')
            matrix = (await finish(await call('scenario_run',build_id=build['build_id'],scenario=spec,operation_id='matrix')))['result']
            assert matrix['state']=='passed' and matrix['summary']['passed']==3,matrix
            samples = []
            for index,case in enumerate(matrix['cases']):
                steps = (await call('scenario_get',run_id=matrix['run_id'],case_index=index))['steps']
                sequence = await call('sequence_get',sequence_id=steps[1]['result']['sequence_id'])
                assert sequence['captured_frames']==8 and sequence['duplicate_renders']==0,sequence
                assert len({frame['render_sequence'] for frame in sequence['frames']})==8
                assert len({frame['image_sha256'] for frame in sequence['frames']})>2
                assert sequence['inline_preview']['coordinate_space']=='contact_sheet_not_input'
                assert 'to_frame_scale' not in sequence['inline_preview']
                with Image.open(sequence['preview']['path']) as image:
                    assert image.n_frames==8 and max(image.size)<=640
                resource = await client.read_resource(sequence['preview']['uri'])
                assert resource.contents[0].mimeType=='image/webp'
                assert hashlib.sha256(base64.b64decode(resource.contents[0].blob)).hexdigest()==sequence['preview']['sha256']
                for frame in sequence['frames']:
                    assert digest(frame['image_path'])==frame['image_sha256']
                    assert frame['capture_started_ms']<=frame['capture_completed_ms']
                    node = (await call('element_query',artifact_id=frame['artifact_id'],selector={'id':'moving'}))['elements'][0]
                    with Image.open(frame['image_path']) as image:
                        rgb = image.convert('RGB')
                        mask = Image.frombytes('L',rgb.size,bytes(255 if p==(0,255,255) else 0 for p in rgb.getdata()))
                        actual = mask.getbbox()
                    q = node['quad']
                    expected = [min(p[0] for p in q),min(p[1] for p in q),max(p[0] for p in q),max(p[1] for p in q)]
                    assert actual and max(abs(a-b) for a,b in zip(actual,expected))<=1,(actual,expected)
                rejected = await client.call_tool('defold_element_query',{'artifact_id':sequence['overview']['artifact_id'],'selector':{'id':'moving'}})
                assert rejected.isError and rejected.structuredContent['error']['code']=='INVALID_OBSERVATION'
                assert case['cleanup']=='stopped'
                if sys.platform=='win32': assert_process_exited(case['pid'])
                samples.append({'device':case['name'],'sequence':sequence})
            session = (await finish(await call('session_start',build_id=build['build_id'],device=spec['devices'][0]['device'],operation_id='partial-session')))['result']
            sid = session['session_id']
            async def sampled(job):
                deadline = time.monotonic()+30
                while time.monotonic()<deadline:
                    status = await call('job_status',job_id=job['job_id'])
                    assert status['state'] not in ('complete','failed','cancelled'),status
                    if status.get('progress',{}).get('captured_frames',0)>=2: return status
                    await asyncio.sleep(.05)
                raise AssertionError('Sequence did not sample')
            try:
                args = {'session_id':sid,'sample_count':30,'interval_ms':250,'operation_id':'cancel'}
                job = await call('capture_sequence',**args)
                assert job==await call('capture_sequence',**args)
                await sampled(job)
                busy = await finish(await call('capture_sequence',session_id=sid,operation_id='busy'),'failed')
                assert busy['error_code']=='SEQUENCE_BUSY'
                waiting = asyncio.create_task(finish(job,'cancelled'))
                await call('job_cancel',job_id=job['job_id'])
                cancelled = await waiting
                assert cancelled['terminal'] and cancelled['cancel_requested'],cancelled
                partial = cancelled['error_details']
                assert 2<=partial['captured_frames']<30 and partial['state']=='cancelled'
                assert Path(partial['preview']['path']).is_file()
                await call('capture',session_id=sid)  # Recorder cancellation must not stop the caller's game.
                job = await call('capture_sequence',session_id=sid,sample_count=30,interval_ms=250,operation_id='resize')
                await sampled(job)
                frame = await call('capture',session_id=sid)
                await call('device_apply',session_id=sid,device=spec['devices'][1]['device'],expected_revision=frame['device_revision'],operation_id='rotate')
                changed = await finish(job,'failed')
                assert changed['error_code']=='DEVICE_CHANGED',changed
                assert all(f['width']==640 and f['height']==480 for f in changed['error_details']['frames'])
                await call('capture',session_id=sid)
                report = await call('report_export',session_id=sid)
            finally: await call('session_stop',session_id=sid)
            if sys.platform=='win32': assert_process_exited(session['pid'])
            proof = {'passed':True,'elapsed_seconds':time.monotonic()-started,'matrix':matrix,
                     'samples':samples,'cancelled':cancelled,'device_changed':changed,'partial_report':report}
            (directory/'verification.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
            print(json.dumps({'passed':True,'directory':str(directory),'report':matrix['report_path'],
                              'elapsed_seconds':proof['elapsed_seconds']}),flush=True)


if __name__=='__main__': asyncio.run(main())
