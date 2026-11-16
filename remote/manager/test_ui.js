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

'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, 'ui/app.js'), 'utf8').replace(/refresh\(\);\s*$/, '');
const project = (overrides = {}) => ({id:'a', name:'Game', root:'/game', state:'running', session_id:'s1', shared:true, ...overrides});
const snapshot = projects => ({computer:'Mac', projects, devices:[], settings:{maxSessions:2, prepare:true}, addresses:['127.0.0.1'], connectionAddress:'127.0.0.1', platform:'arm64-macos', runtime:'test', engine:{directory:'/Defold.app'}});
function deferred() {let resolve; const promise=new Promise(r=>resolve=r);return {promise,resolve};}
function harness(handler = async () => snapshot([project()])) {
 const nodes = new Map();
 function node(selector) {
  if(!nodes.has(selector))nodes.set(selector,{hidden:false,open:false,textContent:'',dataset:{},classList:{remove(){},add(){}},addEventListener(){},getAttribute(name){return this[name]??null;}});
  return nodes.get(selector);
 }
 let timerId=0;
 const timers=new Map(),events=new Map(),calls=[];
 const document={hidden:false,querySelector:node,querySelectorAll:()=>[],addEventListener:(event,fn)=>events.set(event,fn)};
 const context=vm.createContext({document,window:{__TAURI__:{core:{invoke(command,args){calls.push({command,...args});return handler(args?.method,args?.params);}}},addEventListener:(event,fn)=>events.set('window:'+event,fn)},Date,console,
  setTimeout(fn,ms){const id=++timerId;timers.set(id,{fn,ms});return id;},clearTimeout(id){timers.delete(id);},setInterval(){},clearInterval(){}});
 vm.runInContext(source,context);
 const run=code=>vm.runInContext(code,context);
 function state(value) {context.testState=value;run('state=testState;selected=state.projects[0]?.id||""');}
 function image(src='') {let assignments=0;const image={dataset:{sessionKey:'a:s1'},getAttribute:()=>src,get src(){return src;},set src(value){src=value;assignments++;}};nodes.set('#thumbnail',image);return {image,assignments:()=>assignments};}
 return {run,state,image,node,calls,timers,events,document};
}
// Streaming telemetry cannot invalidate a screen that does not display it.
test('presentation ignores protocol metadata but observes visible fields',()=>{
 const h=harness(),p=project();h.state(snapshot([p]));const before=h.run('presentationKey()');
 p.width=2048;p.device_revision=33;p.stamp='new';p.builtAt='now';assert.equal(h.run('presentationKey()'),before);
 p.state='preparing';assert.notEqual(h.run('presentationKey()'),before);
 h.run('page="devices"');const devices=h.run('presentationKey()');p.stage='new stage';assert.equal(h.run('presentationKey()'),devices);
 h.run('page="settings"');const settings=h.run('presentationKey()');p.state='running';assert.equal(h.run('presentationKey()'),settings);
});
// Identical markup must leave DOM nodes and decoded preview images intact.
test('unchanged markup is not replaced',()=>{
 const h=harness();h.run('globalThis.target={dataset:{},writes:0,set innerHTML(value){this.writes++;}}');
 assert.equal(h.run('replaceMarkup(target,"same")'),true);assert.equal(h.run('replaceMarkup(target,"same")'),false);assert.equal(h.run('target.writes'),1);
});
// A thumbnail request must not stall status refreshes or stack another decode.
test('slow preview does not block status or create overlapping thumbnail requests',async()=>{
 const response=deferred();let thumbnails=0,statuses=0;
 const h=harness(method=>method==='thumbnail'?(thumbnails++,response.promise):(statuses++,Promise.resolve(snapshot([project()]))));h.state(snapshot([project()]));h.image();h.run('render=()=>{}');
 await h.run('refresh()');await h.run('refresh()');await h.run('refreshThumbnail()');
 assert.equal(thumbnails,1);assert.equal(statuses,2);response.resolve({image:'new'});await new Promise(setImmediate);
});
// Old game/session responses must never be displayed in the newly selected game.
for(const change of ['selected="b"','state.projects[0].session_id="s2"','state.projects[0].state="ready"'])test(`drop stale preview: ${change}`,async()=>{
 const response=deferred(),h=harness(()=>response.promise);h.state(snapshot([project(),project({id:'b'})]));const image=h.image();const request=h.run('refreshThumbnail()');h.run(change);response.resolve({image:'stale'});await request;assert.equal(image.assignments(),0);
});
// Repeated identical JPEGs must not trigger image decoding again.
test('unchanged image data does not reset image src',async()=>{
 const h=harness(async()=>({image:'same'}));h.state(snapshot([project()]));const image=h.image('same');await h.run('refreshThumbnail()');assert.equal(image.assignments(),0);
});
// Background windows do not poll status or acquire/encode preview frames.
test('hidden window pauses timers and preview, resumes on visibility',async()=>{
 const h=harness();h.state(snapshot([project()]));h.image();h.run('render=()=>{};scheduleRefresh()');assert.equal(h.timers.size,1);
 h.document.hidden=true;h.events.get('visibilitychange')();assert.equal(h.timers.size,0);await h.run('refreshThumbnail()');assert.equal(h.calls.length,0);
 h.document.hidden=false;h.events.get('visibilitychange')();await new Promise(setImmediate);assert.equal(h.calls.filter(c=>c.method==='status').length,1);
});
// A force refresh after a command cannot be lost behind an older status request.
test('force refresh coalesces without concurrent status requests',async()=>{
 const first=deferred();let requests=0,active=0,maxActive=0;
 const h=harness(async method=>{assert.equal(method,'status');requests++;active++;maxActive=Math.max(maxActive,active);const result=requests===1?await first.promise:snapshot([]);active--;return result;});h.run('render=()=>{}');
 const pending=h.run('refresh()');await h.run('refresh(true)');await h.run('refresh(true)');first.resolve(snapshot([]));await pending;await new Promise(setImmediate);
 assert.equal(requests,2);assert.equal(maxActive,1);assert.equal(h.timers.size,1);
});
// Idle status work is reduced, while games and builds retain timely feedback.
test('poll interval follows work and never stacks timers',()=>{
 const h=harness();h.state(snapshot([project({state:'ready'})]));h.run('scheduleRefresh();scheduleRefresh()');assert.equal(h.timers.size,1);assert.equal([...h.timers.values()][0].ms,5000);
 h.run('state.projects[0].busy=true;scheduleRefresh()');assert.equal(h.timers.size,1);assert.equal([...h.timers.values()][0].ms,1500);
});

// A dialog suspends preview work, including an image already in flight.
test('dialogs suppress preview requests and late image assignment',async()=>{
 const response=deferred(),h=harness(()=>response.promise);h.state(snapshot([project()]));const image=h.image();
 h.node('#dialog').open=true;await h.run('refreshThumbnail()');assert.equal(h.calls.length,0);
 h.node('#dialog').open=false;const request=h.run('refreshThumbnail()');h.node('#dialog').open=true;response.resolve({image:'late'});await request;assert.equal(image.assignments(),0);
});
// Transport errors must release the polling guard and schedule a later retry.
test('status failure can recover on the next scheduled poll',async()=>{
 let attempts=0;const h=harness(async()=>{if(++attempts===1)throw new Error('offline');return snapshot([]);});h.run('render=()=>{}');
 await h.run('refresh()');assert.equal(h.run('polling'),false);assert.equal(h.timers.size,1);assert.match(h.node('#notice').textContent,/offline/);
 await [...h.timers.values()][0].fn();assert.equal(attempts,2);assert.equal(h.run('polling'),false);assert.equal(h.node('#notice').hidden,true);
});

// Native Tauri hide must pause work even when WebKit reports document.hidden=false.
test('native hide pauses requests and reopening resumes without browser visibility events',async()=>{
 const h=harness();h.state(snapshot([project()]));h.image();h.run('render=()=>{};scheduleRefresh()');
 h.events.get('window:remote-window-visibility')({detail:false});
 assert.equal(h.document.hidden,false);assert.equal(h.timers.size,0);
 await h.run('refresh(true)');await h.run('refreshThumbnail()');assert.equal(h.calls.length,0);
 h.events.get('window:remote-window-visibility')({detail:true});await new Promise(setImmediate);
 assert.equal(h.calls.filter(c=>c.method==='status').length,1);assert.equal(h.timers.size,1);
});

// Switching to settings during Stop must not leave its engine picker disabled.
test('finishing an action refreshes pending-dependent settings without extra RPC',async()=>{
 const stop=deferred(),h=harness(method=>method==='stop'?stop.promise:Promise.resolve(snapshot([project({state:'ready'})])));
 h.state(snapshot([project()]));h.run('globalThis.renders=[];render=()=>renders.push(pending)');
 const action=h.run('action("stop",{id:"a"})');h.run('page="settings"');
 const busyKey=h.run('presentationKey()');assert.equal(h.run('pending'),true);
 stop.resolve({});await action;
 assert.equal(h.run('pending'),false);assert.notEqual(h.run('presentationKey()'),busyKey);
 assert.equal(h.run('renders.at(-1)'),false);
 assert.deepEqual(h.calls.map(c=>c.method),['stop','status']);
});

// WebKit may start hidden without emitting visibilitychange after the window opens.
test('initial hidden WebKit still fetches status, then trusts native visibility',async()=>{
 const h=harness();h.document.hidden=true;h.run('render=()=>{}');await h.run('refresh()');
 assert.equal(h.calls.filter(c=>c.method==='status').length,1);assert.equal(h.timers.size,0);
 h.events.get('window:remote-window-visibility')({detail:true});await new Promise(setImmediate);
 assert.equal(h.document.hidden,true);assert.equal(h.run('uiHidden()'),false);assert.equal(h.timers.size,1);
});
