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
import {createHash} from 'node:crypto';
const [runtime, directory] = process.argv.slice(2);
if (!runtime || !directory) throw new Error('Usage: node spine_tail_oracle.mjs RUNTIME/dist/index.js OUTPUT_DIRECTORY');
const spine = await import(pathToFileURL(path.resolve(runtime)).href);
const input = JSON.parse(fs.readFileSync(path.join(directory, 'input.json')));
const runtimePackage = JSON.parse(fs.readFileSync(path.resolve(runtime, '../../package.json')));
if (runtimePackage.version !== '4.2.120') throw new Error('Expected pinned official spine-core 4.2.120');
const loader=Object.fromEntries(['Region','Mesh','BoundingBox','Path','Point','Clipping'].map(type=>['new'+type+'Attachment',(_skin,name)=>new spine[type+'Attachment'](name)]));
const data=new spine.SkeletonJson(loader).readSkeletonData(JSON.parse(fs.readFileSync(directory+'/export.json')));
const actual=JSON.parse(fs.readFileSync(directory+'/actual.json'));
const expected=actual.map(clip=>{
 const skeleton=new spine.Skeleton(data);skeleton.setSkinByName(input.skin);const animation=data.findAnimation(clip.animation);
 return {animation:clip.animation,frames:clip.frames.map(({time})=>{
 skeleton.setToSetupPose();animation.apply(skeleton,0,time,false,[],1,spine.MixBlend.setup,spine.MixDirection.mixIn);skeleton.updateWorldTransform(spine.Physics.update);
 const slot=skeleton.findSlot(input.tail);const vertices=new Array(slot.attachment.worldVerticesLength);slot.attachment.computeWorldVertices(slot,0,vertices.length,vertices,0,2);
 return {time,bones:Object.fromEntries(skeleton.bones.map(b=>[b.data.name,[b.a,b.c,b.b,b.d,b.worldX,b.worldY]])),vertices,drawOrder:skeleton.drawOrder.map(s=>s.data.name),attachments:Object.fromEntries(skeleton.slots.map(s=>[s.data.name,s.attachment?.name??null]))};})};});
fs.writeFileSync(directory+'/expected.json',JSON.stringify(expected));
const clips=actual.map((clip,i)=>{let boneError=0,vertexError=0,worstBone=null,worstVertex=null,stateErrors=0;
 clip.frames.forEach((f,j)=>{const e=expected[i].frames[j];if (f.vertices.length !== e.vertices.length || Object.keys(f.bones).length !== Object.keys(e.bones).length) throw new Error('Different skeleton/mesh layout');for(const [name,values] of Object.entries(f.bones))values.forEach((v,k)=>{let error=Math.abs(v-e.bones[name][k]);if (!Number.isFinite(error)) throw new Error('Non-finite bone');if(error>boneError){boneError=error;worstBone={time:f.time,name,component:k,actual:v,expected:e.bones[name][k]};}});
 f.vertices.forEach((v,k)=>{let error=Math.abs(v-e.vertices[k]);if (!Number.isFinite(error)) throw new Error('Non-finite vertex');if(error>vertexError){vertexError=error;worstVertex={time:f.time,index:k,actual:v,expected:e.vertices[k]};}});
 if(JSON.stringify(f['draw-order'])!==JSON.stringify(e.drawOrder)||Object.keys(f.attachments).some(s=>f.attachments[s]!==e.attachments[s]))stateErrors++;});
 return {animation:clip.animation,frames:clip.frames.length,boneError,vertexError,worstBone,worstVertex,stateErrors};});
const report = {runtime: runtimePackage.name, version: runtimePackage.version,
  fixture: input.fixture, exportSha256: createHash('sha256').update(fs.readFileSync(directory+'/export.json')).digest('hex'),
  fps: input.fps, tolerance: 0.0001, frames: clips.reduce((n, c) => n + c.frames, 0),
  passed: clips.every(c => c.boneError < 0.0001 && c.vertexError < 0.0001 && c.stateErrors === 0), clips};
fs.writeFileSync(directory+'/report.json',JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));
if (!report.passed) process.exitCode = 1;
