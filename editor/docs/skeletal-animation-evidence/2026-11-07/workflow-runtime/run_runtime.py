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

from pathlib import Path
import os,subprocess,mmap,struct,time,json,zlib,binascii,fcntl
out=Path(__file__).parent
engine=Path('/Users/applemima1111/Documents/Dev/Defold/engine/engine/build/arm64-macos/dmengine_spine_local')
shared=out/'frames';shared.write_bytes(b'')
slot_size=2048*2048*4
def png(path,w,h,data):
 def chunk(tag,body):return struct.pack('>I',len(body))+tag+body+struct.pack('>I',binascii.crc32(tag+body)&0xffffffff)
 raw=b''.join(b'\0'+data[y*w*4:(y+1)*w*4] for y in range(h))
 path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
result={'screenshots':[],'samples':[]}
with shared.open('r+b') as f:
 f.truncate(4096+2*slot_size)
 m=mmap.mmap(f.fileno(),0);struct.pack_into('=I',m,0,0x44464732)
 env=os.environ.copy();env.update(DM_EDITOR_GAME_FILE=str(shared),DM_EDITOR_GAME_PARENT=str(os.getpid()))
 with (out/'runtime.log').open('w') as log:
  proc=subprocess.Popen([str(engine),str(out/'project/build/default/game.projectc'),'--graphics-adapter','opengl'],cwd=out/'project',env=env,stdout=log,stderr=log)
  start=time.monotonic();last=0;wanted={12,30,60,90,120,180,240,300,360,410}
  while proc.poll() is None and time.monotonic()-start<25:
   try:fcntl.lockf(f,fcntl.LOCK_SH|fcntl.LOCK_NB)
   except BlockingIOError:time.sleep(.001);continue
   magic,w,h,frame=struct.unpack_from('=4I',m,0)
   slot=struct.unpack_from('=I',m,44)[0]
   data=bytes(m[4096+slot*slot_size:4096+slot*slot_size+w*h*4]) if frame!=last and w and h else None
   fcntl.lockf(f,fcntl.LOCK_UN)
   if data:
    last=frame
    rgba=bytearray(data);rgba[0::4]=data[2::4];rgba[2::4]=data[0::4];data=bytes(rgba)
    def pixel(x,y):
     i=(round((600-y)*h/600)*w+round(x*w/800))*4
     return list(data[i:i+4])
    result['samples'].append({'frame':frame,'center':pixel(400,300),'body':pixel(300,300),'linked':pixel(500,300),'clip_out':pixel(235,300),'background':pixel(100,300),'additive':pixel(280,165),'multiply':pixel(400,165),'screen':pixel(520,165)})
    capture=next((v for v in sorted(wanted) if frame>=v),None)
    if capture is not None:
     name=f'frame-{frame:04d}.png';png(out/name,w,h,data);result['screenshots'].append(name);wanted.remove(capture)
   time.sleep(.005)
  if proc.poll() is None:proc.kill();result['timeout']=True
  result['exit_code']=proc.wait();result['frames']=struct.unpack_from('=I',m,12)[0];result['width']=struct.unpack_from('=I',m,4)[0];result['height']=struct.unpack_from('=I',m,8)[0]
 m.close()
shared.unlink()
log=(out/'runtime.log').read_text()
result['callbacks']=[line for line in log.splitlines() if 'SPINE_WORKFLOW_EVENT ' in line]
result['passed']=result['exit_code']==0 and 'SPINE_WORKFLOW_RUNTIME_OK' in log and 'ERROR:' not in log
result['unique_center_colors']=sorted({tuple(s['center']) for s in result['samples']})
result['three_distinct_blend_results']=all(len({tuple(s[k]) for k in ['additive','multiply','screen']})==3 for s in result['samples'] if s['frame']>5)
result['clip_out_matches_background']=all(s['clip_out']==s['background'] for s in result['samples'] if s['frame']>5)
result['passed']=result['passed'] and result['three_distinct_blend_results'] and result['clip_out_matches_background'] and len(result['unique_center_colors'])==3
(out/'runtime-results.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='samples'},indent=2))
if not result['passed']:print(log);raise SystemExit(1)
