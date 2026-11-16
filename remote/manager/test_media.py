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

"""Real manager TLS -> engine audio, device dimensions and stream lifecycle regression."""
import argparse, hashlib, http.client, json, math, os, shutil, ssl, struct, subprocess, time, uuid, wave
from pathlib import Path
from urllib.parse import urlparse, parse_qs
ROOT = Path(__file__).resolve().parents[2]

def run(manifest):
    manifest=Path(manifest).resolve(); runtime=json.loads(manifest.read_text())
    work=ROOT/'tmp/remote-media-tests'/uuid.uuid4().hex; work.mkdir(parents=True)
    folder=work/'game'; shutil.copytree(ROOT/'scripts/automation/fixture',folder)
    with wave.open(str(folder/'tone.wav'),'wb') as tone:
        tone.setparams((2,2,48000,0,'NONE','not compressed'))
        tone.writeframes(b''.join(struct.pack('<hh',int(6000*math.sin(2*math.pi*440*i/48000)),int(6000*math.sin(2*math.pi*660*i/48000))) for i in range(48000)))
    (folder/'tone.sound').write_text('sound: "/tone.wav"\nlooping: 1\ngroup: "master"\ngain: 1\n')
    with (folder/'main.collection').open('a') as f:
        f.write('\nembedded_instances { id: "tone" data: '+json.dumps('components { id: "sound" component: "/tone.sound" }\n')+' }\n')
    script=folder/'main.gui_script';script.write_text(script.read_text().replace('function init(self)','function init(self)\n    msg.post("/tone#sound", "play_sound")'))
    log=(work/'manager.log').open('w'); results=[]; streams=[]
    child=subprocess.Popen([str(manifest.parent/runtime['java']),'--enable-native-access=ALL-UNNAMED','-cp',str(manifest.parent/runtime['classes'])+os.pathsep+str(manifest.parent/runtime['jar']),'com.defold.editor.RemoteManager',str(manifest),str(work/'state'),'0'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True)
    def rpc(method,**params):
        child.stdin.write(json.dumps(dict(method=method,params=params))+'\n');child.stdin.flush()
        line=child.stdout.readline();assert line,(work/'manager.log').read_text()
        reply=json.loads(line);assert reply['ok'],reply;return reply['result']
    def check(name,ok):
        assert ok,name
        results.append(name);print('PASS '+name,flush=True)
    def request(path,token='',body=None,headers=None):
        c=http.client.HTTPSConnection('127.0.0.1',port,context=ssl._create_unverified_context(),timeout=12);c.connect()
        assert hashlib.sha256(c.sock.getpeercert(binary_form=True)).hexdigest()==pin
        c.request('POST' if body is not None else 'GET',path,json.dumps(body).encode() if isinstance(body,dict) else body,{'Authorization':'Bearer '+token,**(headers or {})})
        return c,c.getresponse()
    def json_request(path,token='',body=None,expected=200):
        c,res=request(path,token,body);raw=res.read();c.close();assert res.status==expected,(res.status,raw);return json.loads(raw)
    def start():
        json_request(base+'/start',host,b'',202);deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            state=json_request(base+'/status',host)
            if state['state']=='running':return state
            assert state['state']!='error',state
            time.sleep(.1)
        raise AssertionError('Game startup deadline')
    def audio(token):
        c,res=request(base+'/audio',token);assert res.status==200,(res.status,res.read());streams.append((c,res))
        magic,rate,channels,bits=struct.unpack('>IIII',res.read(16));check('PCM header stereo s16',magic==0x44464131 and channels==2 and bits==16 and 8000<=rate<=192000)
        return c,res,rate
    def samples(res,rate):
        pcm=bytearray();deadline=time.monotonic()+6
        while len(pcm)<rate*4 and time.monotonic()<deadline:
            size,sequence=struct.unpack('>II',res.read(8));assert size<=3840
            pcm.extend(res.read(size))
        check('one second PCM arrives',len(pcm)>=rate*4)
        values=struct.unpack('<'+'h'*(len(pcm)//2),pcm)
        for channel,freq in enumerate((440,660)):
            signal=values[channel::2];rms=math.sqrt(sum(x*x for x in signal)/len(signal));crossings=sum(a<=0<b for a,b in zip(signal,signal[1:]));hz=crossings*rate/len(signal)
            check('audible channel '+str(channel),rms>500)
            check('channel frequency '+str(freq),abs(hz-freq)<25)
    def closed(res):
        total=0
        try:
            while total<3_000_000:
                chunk=res.read(8192)
                if not chunk:return True
                total+=len(chunk)
        except (http.client.IncompleteRead,ConnectionError):return True
        return False
    try:
        state=rpc('status');port=state['port'];pin=state['fingerprint']
        code=parse_qs(urlparse(rpc('pairing')['uri']).query)['code'][0]
        host=json_request('/pair',body={'code':code,'name':'Media regression'})['token']
        pid=rpc('add',path=str(folder))['id'];base='/games/'+pid
        state=start();check('manager advertises actual audio capability',state['audio'])
        json_request(base+'/audio',expected=403);check('unpaired audio denied',True)
        json_request(base+'/audio',host,expected=409);check('host credential without control denied',True)
        claim=json_request(base+'/claim',host,{});control=claim['token']
        for metrics in [dict(width=1080,height=2400,top=90,right=0,bottom=60,left=0),dict(width=2400,height=1080,top=0,right=0,bottom=60,left=90)]:
            state=json_request(base+'/device',control,metrics)
            check('phone render dimensions '+str(metrics['width']),state['width']==metrics['width'] and state['height']==metrics['height'])
            check('device revision advances',state['device_revision']>claim['device_revision'])
            deadline=time.monotonic()+5;safe='SAFE_AREA {top} {right} {bottom} {left}'.format(**metrics)
            while time.monotonic()<deadline and safe not in json.dumps(rpc('logs',id=pid)):time.sleep(.05)
            check('Lua receives safe area '+safe,safe in json.dumps(rpc('logs',id=pid)))
        c,res=request(base+'/input',control,b'\x00'*5,{'X-Defold-Device-Revision':str(claim['device_revision'])});check('old input coordinates rejected',res.status==409);res.read();c.close()
        c,res=request(base+'/input',control,b'\x00'*5,{'X-Defold-Device-Revision':str(state['device_revision'])});check('synced input coordinates accepted',res.status==200);res.read();c.close()
        vc,video=request(base+'/video?resolution=540p',control);streams.append((vc,video));check('H264 alongside audio',video.status==200)
        w,h=struct.unpack('>II',video.read(8));check('quality preserves device aspect ratio',abs(w/h-2400/1080)<.02 and w<=960)
        ac,pcm,rate=audio(control)
        # Consume both streams concurrently so neither test client causes backpressure.
        import threading
        frames=[]
        def read_video():
            try:
                while True:
                    size,seq=struct.unpack('>II',video.read(8));assert len(video.read(size))==size;frames.append(seq)
            except (ValueError,struct.error,OSError,http.client.IncompleteRead):pass
        reader=threading.Thread(target=read_video,daemon=True);reader.start()
        samples(pcm,rate);check('video continues during PCM transfer',len(frames)>10)
        bc,newpcm,newrate=audio(control);samples(newpcm,newrate);check('replacement closes old audio',closed(pcm));ac.close()
        newpcm.close();bc.close();cc,again,rate=audio(control);samples(again,rate)
        check('audio reconnect keeps game session',json_request(base+'/status',control)['session_id']==claim['session_id'])
        rotated=json_request(base+'/device',control,dict(width=1080,height=2400,top=90,bottom=60,left=0,right=0))
        check('rotation retains game session',rotated['session_id']==claim['session_id'])
        check('rotation closes old audio subscription',closed(again));cc.close()
        reader.join(timeout=5);check('rotation closes old video subscription',not reader.is_alive())
        cc,again,rate=audio(control);samples(again,rate)
        code=parse_qs(urlparse(rpc('pairing')['uri']).query)['code'][0];second=json_request('/pair',body={'code':code,'name':'Takeover regression'})['token']
        takeover=json_request(base+'/claim',second,{'takeover':True})['token'];check('takeover closes previous audio',closed(again));cc.close()
        json_request(base+'/audio',control,expected=403);check('old controller audio denied',True)
        dc,current,rate=audio(takeover);samples(current,rate)
        json_request(base+'/release',takeover,b'');check('release closes audio',closed(current));dc.close()
        control=json_request(base+'/claim',second,{})['token'];ec,current,rate=audio(control)
        rpc('stop',id=pid);check('stop closes audio',closed(current));ec.close()
        state=start();control=json_request(base+'/claim',host,{})['token'];fc,current,rate=audio(control);samples(current,rate);fc.close();current.close()
        check('restart restores audio',True);rpc('stop',id=pid)
        report={'runtime':runtime['revision'],'passed':results,'work':str(work)};(work/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
    finally:
        for c,res in streams:
            try:res.close();c.close()
            except Exception:pass
        child.stdin.close()
        try:child.wait(timeout=35)
        except subprocess.TimeoutExpired:child.kill();child.wait()
        log.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('manifest');run(parser.parse_args().manifest)
