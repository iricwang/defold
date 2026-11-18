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

import argparse,json,subprocess,tempfile,time,threading,queue,urllib.request,uuid,os,shutil,struct,wave,math
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--manifest',required=True);ap.add_argument('--relay-source',required=True);ap.add_argument('--node',required=True);ap.add_argument('--probe',required=True);ap.add_argument('--credentials');a=ap.parse_args()
root=Path(__file__).resolve().parents[2]
work=Path(tempfile.mkdtemp(prefix='defold-cloud-test-'));children=[];results=[]
def check(name,value):
 assert value,name
 results.append(name);print('PASS '+name,flush=True)
def spawn(args,log):
 p=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(work/log).open('w'),text=True);children.append(p);q=queue.Queue()
 def reader():
  for line in p.stdout:
   try:q.put(json.loads(line))
   except ValueError:q.put({'invalid':line})
  q.put({'eof':True})
 threading.Thread(target=reader,daemon=True).start();return p,q
def write(p,v):p.stdin.write(json.dumps(v)+'\n');p.stdin.flush()
def read(q,event=None,timeout=30):
 until=time.monotonic()+timeout
 while time.monotonic()<until:
  v=q.get(timeout=max(.1,until-time.monotonic()));assert not v.get('eof'),('process exited',str(work))
  if event is None or v.get('event')==event:return v
 raise TimeoutError(event)
def post(base,path,value,token=''):
 req=urllib.request.Request(base+path,json.dumps(value).encode(),{'Content-Type':'application/json','Authorization':'Bearer '+token});return json.load(urllib.request.urlopen(req,timeout=15))
def manager(method,**params):
 write(m,{'method':method,'params':params});v=read(mq,timeout=60);assert v['ok'],v;return v['result']
renew_stop=threading.Event()
try:
 if a.credentials:
  credentials=json.loads(Path(a.credentials).read_text());base=credentials['server'];user={'token':credentials['token']};username=credentials['username'];password=credentials['password']
 else:
  source=Path(a.relay_source).resolve().as_uri();fixture=work/'relay.mjs';fixture.write_text(f"import {{createRelay}} from {json.dumps(source)};const s=createRelay({{registrationKey:'local-test-registration-key-only',livekit:{{url:'ws://127.0.0.1:7880',apiKey:'devkey',apiSecret:'secret'}}}});s.listen(0,'127.0.0.1',()=>console.log(JSON.stringify({{port:s.address().port}})));")
  relay,rq=spawn([a.node,str(fixture)],'relay.log');base='http://127.0.0.1:'+str(read(rq)['port'])
  user=post(base,'/v1/register',{'username':'remote-test','password':'local-test-password-only','registrationKey':'local-test-registration-key-only'})
  username='remote-test';password='local-test-password-only'
 manifest=Path(a.manifest).resolve();rt=json.loads(manifest.read_text());pkg=manifest.parent
 m,mq=spawn([str(pkg/rt['java']),'--enable-native-access=ALL-UNNAMED','-cp',str(pkg/rt['classes'])+os.pathsep+str(pkg/rt['jar']),'com.defold.editor.RemoteManager',str(manifest),str(work/'manager'),'0'],'manager.log')
 game=work/'game';shutil.copytree(root/'scripts/automation/fixture',game)
 with wave.open(str(game/'tone.wav'),'wb') as tone:
  tone.setparams((2,2,48000,0,'NONE','not compressed'));tone.writeframes(b''.join(struct.pack('<hh',int(6000*math.sin(2*math.pi*440*i/48000)),int(6000*math.sin(2*math.pi*660*i/48000))) for i in range(48000)))
 (game/'tone.sound').write_text('sound: "/tone.wav"\nlooping: 1\ngroup: "master"\ngain: 1\n')
 with (game/'main.collection').open('a') as f:f.write('\nembedded_instances { id: "tone" data: '+json.dumps('components { id: "sound" component: "/tone.sound" }\n')+' }\n')
 p=game/'main.gui_script';p.write_text(p.read_text().replace('function init(self)','function init(self)\n    msg.post("/tone#sound", "play_sound")'))
 game_id=manager('add',path=str(game))['id'];manager('start',id=game_id)
 for n in range(180):
  state=manager('status');project=next(p for p in state['projects'] if p['id']==game_id)
  assert project['state']!='error',project
  if project['state']=='running':break
  time.sleep(1)
 check('fixture game runs in original directory',project['state']=='running' and Path(project['build']).resolve()==game.resolve())
 manager('cloud-login',server=base,username=username,password=password)
 invitation=manager('cloud-invite');phone=post(base,'/v1/defold/claim',{'id':invitation['invitation']},user['token'])
 for n in range(20):
  try:access=post(base,'/v1/defold/viewer/token',phone,user['token']);break
  except Exception:time.sleep(1)
 def renew():
  while not renew_stop.wait(6):
   try:post(base,'/v1/defold/viewer/token',phone,user['token'])
   except Exception:pass
 threading.Thread(target=renew,daemon=True).start()
 time.sleep(9);probe,pq=spawn([a.probe],'probe.log');write(probe,access);read(pq,'connected')
 def rpc(action,expected=True,**params):
  write(probe,dict(action=action,project=game_id,lease=access['lease'],requestId=uuid.uuid4().hex,**params));v=read(pq,'reply',30)['result'];assert v['ok']==expected,v;return v.get('result',v)
 check('WebRTC RPC lists shared game',any(p['id']==game_id for p in rpc('games')['games']))
 rpc('claim');device=rpc('device',width=1080,height=2400,top=90,bottom=60,right=0,left=0)
 check('physical device resolution preserved',device['width']==1080 and device['height']==2400)
 rpc('stream');video=read(pq,'video');check('encoded video bounded to 960 pixels',max(video['width'],video['height'])<=960)
 time.sleep(5);write(probe,{'action':'stats'});stats=read(pq,'stats');check('H264 frames decoded through LiveKit',stats['frames']>=30);check('Opus audio decoded and audible',stats['samples']>=48000 and stats['rms']>300)
 rpc('input',x=30,y=30,down=True,sequence=1,deviceRevision=device['device_revision']);rpc('input',x=30,y=30,down=False,sequence=2,deviceRevision=device['device_revision'])
 check('touch round trip completes',True)
 rpc('input',expected=False,x=30,y=30,down=True,sequence=3,deviceRevision=-1);check('stale device revision rejected',True)
 device=rpc('device',width=2400,height=1080,top=0,bottom=60,right=0,left=90);rpc('stream');video=read(pq,'video');check('rotation rebuilds video track',video['width']>video['height'])
 rpc('release');check('release retains catalog access',len(rpc('games')['games'])==1)
 manager('cloud-revoke',id=phone['grant']);time.sleep(2);write(probe,dict(action='games',project=game_id,lease=access['lease'],requestId=uuid.uuid4().hex));read(pq,'rpc-error');check('revocation disconnects existing media participant',True)
 write(probe,{'action':'close'});probe.wait(timeout=10)
 manager('cloud-logout');check('host removal disables public connection',not manager('cloud-status')['enabled'])
 print(json.dumps({'passed':len(results),'work':str(work),'checks':results}),flush=True)
finally:
 renew_stop.set()
 for p in reversed(children):
  if p.poll() is None:
   try:p.stdin.close();p.wait(timeout=15)
   except Exception:p.kill()
