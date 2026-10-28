"""Real hidden-engine integration checks for the independent manager (no editor required)."""
import argparse
import http.client
import json
import os
from pathlib import Path
import shutil
import ssl
import struct
import subprocess
import time
import uuid
from io import BytesIO
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def run(manifest, extension=None):
    manifest = Path(manifest).resolve()
    runtime = json.loads(manifest.read_text())
    work = ROOT/'tmp/remote-manager-tests'/uuid.uuid4().hex
    work.mkdir(parents=True)
    log = (work/'manager.log').open('w', encoding='utf-8')
    command = [str(manifest.parent/runtime['java']), '--enable-native-access=ALL-UNNAMED', '-cp', str(manifest.parent/runtime['classes'])+os.pathsep+str(manifest.parent/runtime['jar']), 'com.defold.editor.RemoteManager', str(manifest), str(work/'state'), '18490']
    child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True, encoding='utf-8', **({'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {}))
    results = []
    def rpc(method, **params):
        child.stdin.write(json.dumps(dict(method=method,params=params))+'\n'); child.stdin.flush()
        line = child.stdout.readline()
        if not line: raise RuntimeError((work/'manager.log').read_text(encoding='utf-8'))
        response=json.loads(line)
        assert response['ok'],response
        return response['result']
    def check(name, condition):
        assert condition,name
        results.append(name)
    def request(path, token='', body=None, headers=None):
        c=http.client.HTTPSConnection('127.0.0.1',port,context=ssl._create_unverified_context(),timeout=10)
        # Test-only TLS context. Assert the QR certificate pin on every request.
        c.connect()
        import hashlib
        assert hashlib.sha256(c.sock.getpeercert(binary_form=True)).hexdigest()==fingerprint
        h={'Authorization':'Bearer '+token, **(headers or {})}
        data=json.dumps(body).encode() if isinstance(body,dict) else body
        c.request('POST' if body is not None else 'GET',path,data,h)
        return c,c.getresponse()
    try:
        state=rpc('status');port=state['port'];fingerprint=state['fingerprint']
        selected=state['addresses'][-1]
        rpc('connection-address',address=selected)
        check('one selected connection address is persisted',rpc('status')['connectionAddress']==selected)
        c,r=request('/games');check('catalog rejects unpaired clients',r.status==403);r.read();c.close()
        pairing=rpc('pairing')
        from urllib.parse import urlparse,parse_qs
        check('QR uses only the selected address',parse_qs(urlparse(pairing['uri']).query)['address']==[selected])
        code=parse_qs(urlparse(pairing['uri']).query)['code'][0]
        rpc('connection-address',address=selected)
        c,r=request('/pair',body={'code':code});check('address selection invalidates previous QR',r.status==403);r.read();c.close()
        pairing=rpc('pairing');code=parse_qs(urlparse(pairing['uri']).query)['code'][0]
        c,r=request('/pair',body={'code':code,'name':'Integration Android'});auth=json.loads(r.read());c.close();token=auth['token']
        c,r=request('/pair',body={'code':code});check('pairing secret is single use',r.status==403);r.read();c.close()
        projects=[]
        for i in range(2):
            folder=work/f'game-{i}';shutil.copytree(ROOT/'scripts/automation/fixture',folder)
            if extension and i==0:
                shutil.copytree(extension, folder/Path(extension).name)
                if Path(extension).name=='event':
                    with (folder/'main.gui_script').open('a',encoding='utf-8') as out:
                        out.write('\nassert(event_context_manager, "Native Event extension missing")\nprint("NATIVE_EVENT_READY")\n')
            project=rpc('add',path=str(folder));projects.append(project['id'])
            c,r=request(f'/games/{project["id"]}/start',token,b'');check('phone connection starts automatic build '+project['id'],r.status==202);r.read();c.close()
        deadline=time.monotonic()+300
        while time.monotonic()<deadline:
            state=rpc('status');ps=state['projects']
            if any(p['state']=='error' for p in ps): raise RuntimeError(ps)
            if all(p['state']=='running' for p in ps):break
            time.sleep(.25)
        check('two independent games running without editor',all(p['state']=='running' for p in ps))
        for pid in projects:
            c,r=request(f'/games/{pid}/claim',token,{});claim=json.loads(r.read());c.close();control=claim['token']
            c,r=request(f'/games/{pid}/thumbnail',token);image=r.read();c.close();check('real GPU thumbnail '+pid,r.status==200 and image[:2]==b'\xff\xd8');(work/(pid+'.jpg')).write_bytes(image)
            # The asymmetric orange handle detects a double vertical flip on macOS.
            with Image.open(BytesIO(image)) as thumbnail:
                rgb=thumbnail.convert('RGB');w,h=rgb.size
                lower=rgb.getpixel((w//4,round(h*185/240)))
                upper=rgb.getpixel((w//4,round(h*55/240)))
                check('stream uses top-left image coordinates '+pid,lower[0]>220 and lower[1]>90 and lower[2]<100 and upper[0]<80)
            c,r=request(f'/games/{pid}/input',control,struct.pack('<HHB',32768,32768,1),{'X-Defold-Device-Revision':str(claim['device_revision'])});check('pointer accepted '+pid,r.status==200);r.read();c.close()
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                logs=rpc('logs',id=pid)
                if 'BUTTON_CLICKED 1' in json.dumps(logs):break
                time.sleep(.05)
            check('Lua button callback received '+pid,'BUTTON_CLICKED 1' in json.dumps(logs))
            c,r=request(f'/games/{pid}/input',control,struct.pack('<HHB',32768,32768,0),{'X-Defold-Device-Revision':str(claim['device_revision'])});r.read();c.close()
            c,r=request(f'/games/{pid}/thumbnail',token);(work/(pid+'-clicked.jpg')).write_bytes(r.read());c.close()
            c,r=request(f'/games/{pid}/video?resolution=540p',control);check('hardware video connects '+pid,r.status==200)
            width,height=struct.unpack('>II',r.read(8));check('video dimensions valid '+pid,width>0 and height>0)
            start=time.monotonic();count=0;video=bytearray()
            while count<60:
                size,sequence=struct.unpack('>II',r.read(8));data=r.read(size);assert len(data)==size;video.extend(data);count+=1
            elapsed=time.monotonic()-start
            (work/(pid+'.h264')).write_bytes(video);c.close()
            results.append(f'60 H264 frames {pid}: {count/elapsed:.1f} fps')
            if pid==projects[0]: previous=control
        c,r=request(f'/games/{projects[0]}/input',previous,b'\x00'*5,{'X-Defold-Device-Revision':'0'});check('old project control revoked after switch',r.status==403);r.read();c.close()
        state=rpc('status');check('switching did not stop either game',all(p['state']=='running' for p in state['projects']))
        if extension:
            native=projects[0]
            if Path(extension).name=='event':check('compiled Event module loaded by actual engine','NATIVE_EVENT_READY' in json.dumps(rpc('logs',id=native)))
            def await_project(expected):
                deadline=time.monotonic()+300
                while time.monotonic()<deadline:
                    c,r=request(f'/games/{projects[1]}/status',control);r.read();c.close()
                    c,r=request(f'/games/{native}/status',token);remote=json.loads(r.read());c.close()
                    if remote['state']==expected:return remote
                    if remote['state']=='error' and expected!='error':raise AssertionError(remote['error'])
                    time.sleep(.2)
                raise AssertionError('Timed out: '+expected)
            rpc('stop',id=native)
            with (work/'game-0/main.gui_script').open('a',encoding='utf-8') as out:out.write('\nprint("LUA_ONLY_UPDATE")\n')
            c,r=request(f'/games/{native}/start',token,b'');r.read();c.close();await_project('running')
            native_logs=list((work/'state/builds').glob('*/native-jobs/*/build.log'))
            check('Lua-only change reuses native engine cache',any('REMOTE_NATIVE_CACHE_HIT' in f.read_text(encoding='utf-8',errors='replace') for f in native_logs))
            rpc('stop',id=native)
            source=next((work/'game-0'/Path(extension).name/'src').glob('*.cpp'));original=source.read_bytes();source.write_bytes(original+b'\n#error REMOTE_EXPECTED_NATIVE_FAILURE\n')
            c,r=request(f'/games/{native}/start',token,b'');r.read();c.close();failure=await_project('error')
            check('native compiler error synchronized to phone','REMOTE_EXPECTED_NATIVE_FAILURE' in failure['error'])
            check('error includes failed stage and project classification',bool(failure['failed_stage']) and failure['error_kind']=='project')
            local=next(p for p in rpc('status')['projects'] if p['id']==native)
            check('desktop and phone receive the same error',local['error']==failure['error'])
            check('failed project does not stop another game',next(p for p in rpc('status')['projects'] if p['id']==projects[1])['state']=='running')
            source.write_bytes(original)
            c,r=request(f'/games/{native}/start',token,b'');r.read();c.close();await_project('running')
            check('fixed project reconnects without manual import',True)
        pairing=rpc('pairing');code=parse_qs(urlparse(pairing['uri']).query)['code'][0]
        c,r=request('/pair',body={'code':code,'name':'Second controller'});second=json.loads(r.read());c.close()
        c,r=request(f'/games/{projects[1]}/claim',second['token'],{});check('another device requires explicit takeover',r.status==409);r.read();c.close()
        c,r=request(f'/games/{projects[1]}/claim',second['token'],{'takeover':True});takeover=json.loads(r.read());check('explicit takeover succeeds',r.status==200);c.close()
        c,r=request(f'/games/{projects[1]}/input',control,b'\x00'*5,{'X-Defold-Device-Revision':'1'});check('takeover rejects old controller',r.status==403);r.read();c.close()
        c,r=request(f'/games/{projects[1]}/input',takeover['token'],b'\x00'*5,{'X-Defold-Device-Revision':'-1'});check('stale device dimensions rejected',r.status==409);r.read();c.close()
        rpc('share',id=projects[1],shared=False)
        c,r=request('/games',token);catalog=json.loads(r.read());c.close();check('unshared project removed from catalog',len(catalog['games'])==1)
        rpc('revoke',id=auth['device_id']);c,r=request('/games',token);check('revoked host credential rejected',r.status==403);r.read();c.close()
        for pid in projects:rpc('stop',id=pid)
        check('both sessions stopped',all(p['state']=='ready' for p in rpc('status')['projects']))
        (work/'report.json').write_text(json.dumps({'passed':results},ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'work':str(work),'passed':results},ensure_ascii=False,indent=2))
    finally:
        child.stdin.close()
        try: child.wait(timeout=35)
        except subprocess.TimeoutExpired:child.kill();raise
        log.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('manifest');parser.add_argument('--extension',type=Path);args=parser.parse_args();run(args.manifest,args.extension)
