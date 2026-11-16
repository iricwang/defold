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

import {pathToFileURL} from 'node:url';
import path from 'node:path';
const {Skeleton,SkeletonJson,RegionAttachment,MixBlend,MixDirection} = await import(pathToFileURL(path.resolve(process.argv[2])).href);
const directory=path.dirname(new URL(import.meta.url).pathname);
import fs from 'node:fs';
const cases=JSON.parse(fs.readFileSync(path.join(directory,'cases.json'),'utf8'));
const result=cases.map(c=>{
 const source={skeleton:{spine:'4.2.43'},bones:[{name:'root'}],slots:[{name:'body',bone:'root',attachment:'frame'}],
 skins:[{name:'default',attachments:{body:{frame:{path:'frame',sequence:c.config}}}}],
 animations:{move:{attachments:{default:{body:{frame:{sequence:c.frames}}}}}}};
 const loader={newRegionAttachment:(_skin,name)=>new RegionAttachment(name)};
 const data=new SkeletonJson(loader).readSkeletonData(source);
 return c.times.map(time=>{const skeleton=new Skeleton(data);data.findAnimation('move').apply(skeleton,0,Math.fround(time),false,[],1,MixBlend.setup,MixDirection.mixIn);
 const value=skeleton.slots[0].sequenceIndex;return value===-1?c.config.setup:Math.min(c.config.count-1,value);});
});
fs.writeFileSync(path.join(directory,'expected.json'),JSON.stringify(result));
