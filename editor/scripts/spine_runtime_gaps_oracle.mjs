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
const [runtime, output]=process.argv.slice(2);
if(!runtime || !output)throw Error('Usage: node spine_runtime_gaps_oracle.mjs RUNTIME/dist/index.js OUTPUT.json');
const {SkeletonJson,Skeleton,AnimationState,AnimationStateData,Physics,MixBlend}=await import(pathToFileURL(path.resolve(runtime)).href);
const version=JSON.parse(fs.readFileSync(path.resolve(runtime,'../../package.json'))).version;
if(version!=='4.2.120')throw Error('Oracle version mismatch');
const parser=new SkeletonJson({});
const source={skeleton:{spine:'4.2.43'},bones:[{name:'root'}],events:{tick:{}},animations:{
 base:{bones:{root:{translatex:[{value:20},{time:2,value:20}]} }},
 move:{bones:{root:{translatex:[{value:0},{time:2,value:200}],rotate:[{value:0},{time:2,value:720}]}},events:[{time:.2,name:'tick'},{time:.5,name:'tick'},{time:1,name:'tick'},{time:1.2,name:'tick'}]},
 other:{bones:{root:{translatex:[{value:200},{time:2,value:100}],translatey:[{value:30},{time:2,value:30}]}}}}};
const cases=[];
function run(name,src,configuration,commands=[],deltas=Array(121).fill(1/60)){
 const data=parser.readSkeletonData(src);const sk=new Skeleton(data);const state=new AnimationState(new AnimationStateData(data)); if(configuration.skin)sk.setSkinByName(configuration.skin);
 state.data.defaultMix=configuration['default-mix']||0;
 function params(entry,s){for(const [a,b] of Object.entries({'reverse':'reverse','hold-previous':'holdPrevious','shortest-rotation':'shortestRotation','animation-start':'animationStart','animation-end':'animationEnd','alpha':'alpha','time-scale':'timeScale','mix-duration':'mixDuration','attachment-threshold':'alphaAttachmentThreshold','event-threshold':'eventThreshold','draw-order-threshold':'mixDrawOrderThreshold'}))if(s[a]!==undefined)entry[b]=s[a];if(s.blend)entry.mixBlend=s.blend==='add'?MixBlend.add:MixBlend.replace;return entry;}
 function set(index,s,queue=false){return params(s.empty?(queue?state.addEmptyAnimation(index,s['mix-duration']||0,s.delay||0):state.setEmptyAnimation(index,s['mix-duration']||0)):(queue?state.addAnimation(index,s.animation,!!s.loop,s.delay||0):state.setAnimation(index,s.animation,!!s.loop)),s);}
 (configuration.tracks||[]).forEach((s,i)=>{if(s){set(i,s);(s.queue||[]).forEach(q=>set(i,q,true));}});
 let time=0,ev=[];state.addListener({event:(_entry,e)=>ev.push(e.data.name)});
 const frames=[];
 for(let n=0;n<deltas.length;n++){
  const delta=n===0?0:deltas[n];
  const action=commands.filter(c=>c.frame===n);
  for(const c of action)set(c.index,c.specification,c.operation==='queue');
  time+=delta;state.update(delta);state.apply(sk);sk.update(delta);sk.updateWorldTransform(Physics.update);
  frames.push({time,delta,bones:sk.bones.map(b=>({id:b.data.name,transform:[b.a,b.c,b.b,b.d,b.worldX,b.worldY]})),events:ev});ev=[];
 }
 cases.push({name,source:src,configuration:{...configuration,'step-mode':'runtime'},commands,frames});
}
run('reverse',source,{tracks:[{animation:'move',reverse:true}]});
run('cropped-loop-events',source,{tracks:[{animation:'move','animation-start':.5,'animation-end':1.2,loop:true}]});
run('cropped-reverse',source,{tracks:[{animation:'move','animation-start':.5,'animation-end':1.2,loop:true,reverse:true}]});
run('shortest-rotation',source,{tracks:[{animation:'base'},{animation:'move',alpha:.5,'shortest-rotation':true,loop:true}]});
run('hold-overlay',source,{tracks:[{animation:'base'},{animation:'move',alpha:.6}]},[{frame:20,index:1,operation:'set',specification:{animation:'other',alpha:.7,'hold-previous':true,'mix-duration':.4}}]);
run('empty-fade',source,{tracks:[{animation:'base'},{animation:'other'}]},[{frame:20,index:1,operation:'set',specification:{empty:true,'mix-duration':.4}}]);
run('interrupted-fade',source,{tracks:[{animation:'base'}]},[{frame:10,index:0,operation:'set',specification:{animation:'other','mix-duration':.4}},{frame:20,index:0,operation:'set',specification:{animation:'move','mix-duration':.5}}]);
const ik={skeleton:{spine:'4.2.43'},bones:[{name:'root'},{name:'p',parent:'root',length:10},{name:'c',parent:'p',x:10,length:10},{name:'target',parent:'root',x:12,y:8}],ik:[{name:'ik',bones:['p','c'],target:'target'}],animations:{move:{bones:{p:{inherit:[{}, {time:.4,inherit:'noScale'},{time:.8,inherit:'normal'}]},target:{translatex:[{}, {time:1,value:5}]}}}}};
run('animated-inherit-ik',ik,{tracks:[{animation:'move'}]});
const old=JSON.parse(fs.readFileSync(new URL('../test/resources/skeletal-animation/stateful-mixing-oracle.json',import.meta.url)));
const phys=structuredClone(old.find(c=>c.name==='mixed-physics'));
run('variable-dt-physics',phys.source,{...phys.configuration,'step-mode':'runtime'},[],Array.from({length:81},(_,i)=>[1/120,1/30,1/60,.025][i%4]));
const reset=structuredClone(phys.source);const first=Object.keys(reset.animations)[0];reset.animations[first].physics={'':{reset:[{time:.3},{time:.8}]}};
run('physics-reset',reset,{...phys.configuration,'step-mode':'runtime'},[],Array.from({length:81},(_,i)=>[1/120,1/30,1/60,.025][i%4]));
const skinned=structuredClone(phys.source);
skinned.physics.forEach(p=>p.skin=true);
skinned.skins=[{name:'default',attachments:{}},{name:'active',physics:skinned.physics.map(p=>p.name),attachments:{}}];
for(const skin of ['default','active'])run('physics-skin-'+skin,skinned,{...phys.configuration,'step-mode':'runtime',skin},[],Array.from({length:61},(_,i)=>[.011,.019,.027][i%3]));
const constrained=structuredClone(phys.source);
constrained.bones.push({name:'target',parent:'root',x:30,y:10});
constrained.transform=[{name:'follow',bones:['root'],target:'target',mixRotate:0,mixX:.2,mixY:.3,mixScaleX:0,mixScaleY:0,mixShearY:0,order:0}];
// A separate root target avoids a dependency cycle in the constraint graph.
delete constrained.bones[1].parent;
constrained.physics.forEach(p=>p.order=1);
run('transform-physics-variable-dt',constrained,{...phys.configuration,'step-mode':'runtime'},[],Array.from({length:61},(_,i)=>[.011,.019,.027][i%3]));
fs.writeFileSync(output,JSON.stringify({version,cases},null,2));
console.log(cases.map(c=>[c.name,c.frames.length]));
