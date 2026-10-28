"""Check automatic build cancellation and an optional real project without touching source files."""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import psutil
from io import BytesIO
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]


def run(manifest, project=None):
    manifest=Path(manifest).resolve();runtime=json.loads(manifest.read_text(encoding='utf-8'))
    work=ROOT/'tmp/remote-manager-lifecycle'/uuid.uuid4().hex;work.mkdir(parents=True)
    command=[str(manifest.parent/runtime['java']),'-cp',str(manifest.parent/runtime['classes'])+os.pathsep+str(manifest.parent/runtime['jar']),
             'com.defold.editor.RemoteManager',str(manifest),str(work/'state'),'18540']
    report=[]
    with (work/'manager.log').open('wb') as log:
        process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        def rpc(method,**params):
            process.stdin.write(json.dumps({'method':method,'params':params})+'\n');process.stdin.flush()
            reply=json.loads(process.stdout.readline());assert reply['ok'],reply;return reply['result']
        def wait_for(predicate,timeout=180):
            deadline=time.monotonic()+timeout
            while time.monotonic()<deadline:
                state=rpc('status')
                if predicate(state):return state
                time.sleep(.1)
            raise AssertionError(state)
        try:
            fixture=work/'cancel-fixture';shutil.copytree(ROOT/'scripts/automation/fixture',fixture)
            entry=rpc('add',path=str(fixture));rpc('start',id=entry['id'])
            wait_for(lambda s:any(p['stage']=='构建游戏资源' for p in s['projects']))
            children=[(p.pid,p.create_time()) for p in psutil.Process(process.pid).children(recursive=True)]
            assert children,'No owned build process observed'
            rpc('cancel',id=entry['id'])
            deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                alive=[]
                for pid,born in children:
                    try:
                        if psutil.Process(pid).create_time()==born:alive.append(pid)
                    except psutil.NoSuchProcess:pass
                if not alive:break
                time.sleep(.1)
            assert not alive,alive
            report.append('cancel terminates owned builder and child processes')
            rpc('start',id=entry['id'])
            state=wait_for(lambda s:any(p['id']==entry['id'] and p['state']=='running' for p in s['projects']))
            built=next(p for p in state['projects'] if p['id']==entry['id'])['builtAt']
            rpc('stop',id=entry['id']);rpc('start',id=entry['id'])
            state=wait_for(lambda s:any(p['id']==entry['id'] and p['state']=='running' for p in s['projects']))
            assert next(p for p in state['projects'] if p['id']==entry['id'])['builtAt']==built
            report.append('unchanged reconnect reuses built resources')
            rpc('stop',id=entry['id'])
            if project:
                entry=rpc('add',path=str(Path(project).resolve()));rpc('start',id=entry['id'])
                state=wait_for(lambda s:any(p['id']==entry['id'] and p['state'] in ('running','error') for p in s['projects']),300)
                current=next(p for p in state['projects'] if p['id']==entry['id'])
                (work/'project-status.json').write_text(json.dumps(current,ensure_ascii=False,indent=2),encoding='utf-8')
                assert current['state']=='running',current['error']
                deadline=time.monotonic()+30
                while time.monotonic()<deadline:
                    data=rpc('thumbnail',id=entry['id'])['image'];pixels=base64.b64decode(data.split(',',1)[1])
                    image=Image.open(BytesIO(pixels)).convert('RGB')
                    if len(set(image.getdata()))>16:break
                    time.sleep(.5)
                (work/'project.jpg').write_bytes(pixels)
                assert len(set(image.getdata()))>16,'Real project frame stayed blank'
                (work/'project-logs.json').write_text(json.dumps(rpc('logs',id=entry['id']),ensure_ascii=False,indent=2),encoding='utf-8')
                report.append('real project automatically built and rendered from an isolated snapshot')
        finally:
            process.stdin.close();process.wait(timeout=40)
    (work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'work':str(work),'passed':report},ensure_ascii=False,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('manifest');parser.add_argument('--project');args=parser.parse_args();run(args.manifest,args.project)
