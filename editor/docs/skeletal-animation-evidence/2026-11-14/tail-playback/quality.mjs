// Copyright 2020-2026 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
if(process.argv.length!==4)throw Error('Usage: node quality.mjs SPINE_CORE/dist/index.js FIXTURE_DIRECTORY');
const spine=await import(pathToFileURL(path.resolve(process.argv[2])).href);
const dir=path.resolve(process.argv[3])+'/';
function frames(file) {
 const loader=Object.fromEntries(['Region','Mesh','BoundingBox','Path','Point','Clipping'].map(type=>['new'+type+'Attachment',(_skin,name)=>new spine[type+'Attachment'](name)]));
 const data=new spine.SkeletonJson(loader).readSkeletonData(JSON.parse(fs.readFileSync(dir+file+'.spinejson')));
 const sk=new spine.Skeleton(data);sk.setSkinByName('default');
 const anim=data.findAnimation('benchmark_sway');
 return Array.from({length:121},(_,i)=>{
  sk.setToSetupPose();anim.apply(sk,0,i/60,false,[],1,spine.MixBlend.setup,spine.MixDirection.mixIn);sk.updateWorldTransform(spine.Physics.update);
  const slot=sk.findSlot('05_tail'),a=slot.attachment,v=new Array(a.worldVerticesLength);a.computeWorldVertices(slot,0,v.length,v,0,2);
  if(v.some(x=>!Number.isFinite(x)))throw Error('Non-finite vertex');
  if(sk.pathConstraints.some(p=>!p.active))throw Error('Inactive path constraint');
  return v;
 });
}
const ref=frames('animated');let movement=0;
for(const f of ref)for(let i=0;i<f.length;i+=2)movement=Math.max(movement,Math.hypot(f[i]-ref[0][i],f[i+1]-ref[0][i+1]));
if(movement<1)throw Error('Animation is not moving mesh');
const result={frames:121,units:'world units at scale 1',maximum_motion:movement,variants:[]};
for(const n of [1,2,3]) {const vv=frames('influences-'+n);let sum=0,max=0,count=0;for(let f=0;f<vv.length;f++)for(let i=0;i<vv[f].length;i+=2){const e=Math.hypot(vv[f][i]-ref[f][i],vv[f][i+1]-ref[f][i+1]);sum+=e*e;max=Math.max(max,e);count++;}result.variants.push({influences:n,maximum_error:max,rms_error:Math.sqrt(sum/count)});}
fs.writeFileSync(dir+'quality.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
