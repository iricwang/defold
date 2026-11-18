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
const $=s=>document.querySelector(s),LK=window.LivekitClient;
// The JS SDK takes milliseconds (the native SDK probe uses a Duration).
const RPC_RESPONSE_TIMEOUT_MS=12000;
let saved=JSON.parse(localStorage.getItem('defold-cloud')||'null'),room=null,access=null,host='',game='',device=null,paused=false,joining=false,timer=null,seq=0,generation=0,syncing=false,lastMetrics='',pointer=null,lastMove=0;
let hosts=[],games=[],page='games',query='',catalogSignature='',catalogRequest=0,catalogReady=false;
const nativeComputers=()=>JSON.parse(window.DefoldRemote?.pairedComputers?.()||'[]');
$('body').classList.toggle('android-viewport',!!window.DefoldRemote);
const switchingComputer=!!window.DefoldRemote?.switchingComputer?.();
const paired=new Map(nativeComputers().map(p=>[p.host,p]));
const authorized=id=>paired.has(id)||!!saved?.grants?.[id];
function mergeHosts(accountHosts=[]){
 const merged=new Map(accountHosts.map(h=>[h.id,h]));
 for(const p of paired.values())if(!merged.has(p.host))merged.set(p.host,{id:p.host,name:p.name,paired:true});
 return [...merged.values()];
}
let connectionStage='idle';
function trace(stage,error){connectionStage=stage;console.info('[Remote] '+stage+(error?' · '+String(error?.message||error):''));}
addEventListener('error',e=>trace('script-error',e.error||e.message));
addEventListener('unhandledrejection',e=>trace('unhandled-error',e.reason));
const QUALITY_OPTIONS=['540p','720p','1080p'];
let streamQuality=localStorage.getItem('defold-cloud-quality')||'720p',qualityChanging=false;
if(!QUALITY_OPTIONS.includes(streamQuality))streamQuality='720p';
let previewRevision=0,hasControl=false,menuOpen=false,frameVisible=false,videoTrack=null;
let controlQueue=Promise.resolve(),claimRequested=false;
function orderedControl(fn){
 const result=controlQueue.then(fn);
 controlQueue=result.catch(()=>{});
 return result;
}
let reportedPreview=false;
function previewUI(){
 if(reportedPreview!==!!game){reportedPreview=!!game;window.DefoldRemote?.setPreviewing?.(reportedPreview);}
 $('#first-frame').hidden=!game||frameVisible;
 $('#preview-message').hidden=!game||!$('#preview-message').textContent;
 $('#preview-menu').hidden=!game;
 $('#preview-menu').setAttribute('aria-expanded',String(menuOpen));
 $('#controls').hidden=!game||!menuOpen;
 $('#panel').hidden=!!game&&!menuOpen;
 $('#stop').disabled=!hasControl;
 if(game)window.remotePositionOrb?.();
}
function waitForFrame(label){
 frameVisible=false;$('#load-label').textContent=label;previewUI();
}
function showMenu(open){
 if(!game)return;
 menuOpen=open;if(open)endPointer();previewUI();
}
function attachVideo(track){
 const video=$('#video'),revision=previewRevision;
 videoTrack=track;waitForFrame('正在接收画面…');track.attach(video);
 const visible=()=>{
  if(!game||revision!==previewRevision||videoTrack!==track||!video.videoWidth)return;
  frameVisible=true;previewUI();renderQuality();
 };
 if(video.requestVideoFrameCallback)video.requestVideoFrameCallback(visible);
 else video.onplaying=visible;
 video.play().catch(()=>{if(videoTrack===track){message('画面播放中断，请重试');showMenu(true);}});
}
async function claimPreview(id,revision){
 await orderedControl(async()=>{
  if(game!==id||revision!==previewRevision)return;
  claimRequested=true;await rpc('claim',{project:id});
  if(game===id&&revision===previewRevision){hasControl=true;previewUI();}
 });
}
function renderQuality(){
 $('#quality-value').textContent=streamQuality;
 $('#quality-preview').textContent=streamQuality+' ▾';
 for(const id of ['#quality-setting','#quality-preview'])$(id).disabled=qualityChanging||syncing;
 const video=$('#video');
 $('#stream-dimensions').textContent=qualityChanging?'切换中':video.videoWidth&&video.videoHeight?video.videoWidth+' × '+video.videoHeight:'';
}
let qualityRequest=0, pendingQuality=null;
function chooseQuality(){
 if(qualityChanging||syncing)return;
 const request=++qualityRequest;
 pendingQuality={request,generation,previewRevision};
 if(window.DefoldRemote?.chooseQuality){
  window.DefoldRemote.chooseQuality(streamQuality,request);return;
 }
 const options=$('#quality-options');options.replaceChildren();
 const labels=['流畅','均衡','高清'];
 QUALITY_OPTIONS.forEach((value,i)=>{
  const option=button('',()=>{$('#quality-dialog').close();return window.remoteQualityPicked(request,value);});
  option.setAttribute('aria-pressed',String(value===streamQuality));
  const name=document.createElement('span'),resolution=document.createElement('span'),mark=document.createElement('span');
  name.textContent=labels[i];resolution.textContent=value;mark.textContent=value===streamQuality?'✓':'';
  option.append(name,resolution,mark);options.append(option);
 });
 $('#quality-dialog').showModal();
}
window.remoteQualityPicked=async(request,choice)=>{
 if(pendingQuality?.request!==request)return;
 const pending=pendingQuality;pendingQuality=null;
 if(pending.generation!==generation||pending.previewRevision!==previewRevision)return;
 try{await setQuality(choice);}catch(error){renderQuality();message(error.message);}
};
async function setQuality(choice){
 if(!QUALITY_OPTIONS.includes(choice))throw Error('不支持的预览清晰度');
 if(choice===streamQuality)return;
 if(qualityChanging||syncing)throw Error('画面正在切换，请稍后重试');
 qualityChanging=true;renderQuality();const currentGeneration=generation;
 try{
  if(room&&game&&device&&!paused){await endPointer();await rpc('stream',{resolution:choice});if(currentGeneration!==generation)throw Error('连接已变化，请重新选择画质');}
  streamQuality=choice;localStorage.setItem('defold-cloud-quality',choice);message('');
 }finally{qualityChanging=false;renderQuality();if(room&&game&&!paused)window.remoteMetricsChanged();}
}
const save=()=>localStorage.setItem('defold-cloud',JSON.stringify(saved));
function message(s){$('#preview-message').textContent=s||'';$('#preview-message').hidden=!game||!s;$('#message').textContent=s||'';$('#connection-message').textContent=s||'';}
function metrics(){if(window.DefoldRemote)return JSON.parse(window.DefoldRemote.metrics());return {width:Math.round(innerWidth*devicePixelRatio),height:Math.round(innerHeight*devicePixelRatio),top:0,right:0,bottom:0,left:0};}
function server(value){const url=new URL(value);if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||!['','/'].includes(url.pathname))throw Error('请使用 HTTPS 服务器地址');return url.origin;}
async function viewerApi(path,id){
 const p=paired.get(id);
 if(!p)return api(path,saved.grants[id]);
 const credentials={server:server(p.server)},body={host:p.host,grant:p.grant,deviceToken:p.deviceToken};
 const expected=generation;
 for(let attempt=0;;attempt++){
  try{return await api(path,body,credentials);}
  catch(e){
   if(path!=='defold/viewer/token'||attempt>=3||![403,503].includes(e.status)||paused||generation!==expected)throw e;
   await new Promise(resolve=>setTimeout(resolve,250*(attempt+1)));
   if(paused||generation!==expected)throw Error('连接已切换');
  }
 }
}
async function api(path,body,credential=saved){const r=await fetch(credential.server+'/v1/'+path,{method:body===undefined?'GET':'POST',redirect:'error',headers:{...(credential.token?{Authorization:'Bearer '+credential.token}:{}),'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});const v=await r.json();if(!r.ok)throw Object.assign(Error(v.error||'连接失败'),{status:r.status});return v;}
async function rpc(action,params={}){if(!room||!access)throw Error('正在连接电脑');const payload=JSON.stringify({action,project:game,lease:access.lease,requestId:crypto.randomUUID(),...params});const current=room,lease=access.lease,call=()=>current.localParticipant.performRpc({destinationIdentity:access.hostIdentity,method:'defold.control',payload,responseTimeout:RPC_RESPONSE_TIMEOUT_MS});let raw;try{raw=await call();}catch(e){if(room!==current||access.lease!==lease||current.state!=='connected')throw e;raw=await call();}const value=JSON.parse(raw);if(!value.ok)throw Error(value.error);return value.result;}
function show(){
 renderQuality();
 $('#login').hidden=!!saved?.token;$('#authorization').hidden=!saved?.token;
 $('#connection-title').textContent=saved?.token?'添加电脑':'登录服务器';
 $('#catalog').hidden=!!game;$('#controls').hidden=!game;$('#panel').classList.toggle('game',!!game);previewUI();
 renderCatalog();
}
function openConnection(){message('');$('#computer-dialog').close();$('#connection-dialog').showModal();}
function selectPage(next){page=next==='settings'?'settings':'games';renderCatalog();}
function empty(parent,title,description,action,run){const box=document.createElement('div');box.className='empty';const h=document.createElement('h3'),p=document.createElement('p');h.textContent=title;p.textContent=description;box.append(h,p);if(action)box.append(button(action,run));parent.append(box);}
function computerRow(h){
 const row=button('',async()=>{if(!authorized(h.id)){openConnection();return;}if(h.online===false)return;$('#computer-dialog').close();selectPage('games');await connect(h.id);});
 row.className='computer-row';row.setAttribute('aria-pressed',String(host===h.id));
 const labels=document.createElement('div'),name=document.createElement('h3'),detail=document.createElement('p'),state=document.createElement('span');labels.className='game-labels';name.textContent=h.name;detail.textContent=authorized(h.id)?'已授权':'等待授权';labels.append(name,detail);state.className='status'+(h.online?' online':'');state.textContent=!authorized(h.id)?'授权':h.paired?'已配对':h.online?'在线':'离线';row.append(labels,state);row.disabled=h.online===false&&!!authorized(h.id);return row;
}
function renderComputerOptions(){
 const options=$('#computer-options');options.replaceChildren();
 for(const h of hosts)options.append(computerRow(h));
 if(!hosts.length)empty(options,'还没有连接的电脑','从右上角添加电脑，授权后即可查看游戏。');
}
function chooseComputer(){
 if(!saved?.token&&!paired.size){openConnection();return;}
 renderComputerOptions();$('#computer-dialog').showModal();
 list().then(()=>{if($('#computer-dialog').open)renderComputerOptions();}).catch(e=>message(e.message));
}
function renderCatalog(){
 const connected=catalogReady&&room?.state==='connected';
 const status=!host?'未连接':connected?'已连接':joining?'连接中':'离线';
 const signature=JSON.stringify([page,query,hosts,games,host,status,!!saved?.token,saved?.grants]);if(signature===catalogSignature)return;catalogSignature=signature;
 const selected=hosts.find(h=>h.id===host);
 $('#host-name').textContent=selected?.name||'选择电脑';
 $('#host-status').textContent=status;
 $('#host-status').classList.toggle('online',!!connected);
 $('#choose-computer').setAttribute('aria-label',($('#host-name').textContent)+' · '+status+' · 选择电脑');
 $('#section-title').textContent=page==='games'?'你的游戏':'设置';
 $('#search').hidden=page!=='games';$('#settings').hidden=page!=='settings';$('#games').hidden=page!=='games';$('#logout').hidden=!saved?.token;
 for(const tab of document.querySelectorAll('[data-page]'))tab.setAttribute('aria-pressed',String(tab.dataset.page===page));
 const list=$('#games');list.replaceChildren();
 if(!host&&switchingComputer&&![...paired.values()].some(p=>p.selected)){
  empty(list,'这台电脑还没有服务器连接','在电脑上开启公网连接，或选择其他已配对的电脑。',hosts.length?'选择电脑':'添加电脑',hosts.length?chooseComputer:openConnection);return;
 }
 if(!saved?.token&&!paired.size){empty(list,'把电脑上的游戏带到手机上','添加一台电脑，选择游戏后电脑会自动准备并运行。','添加电脑',openConnection);return;}
 if(!selected){empty(list,'选择一台电脑','选择已授权的电脑，查看它共享的游戏。','选择电脑',chooseComputer);return;}
 const visible=games.filter(p=>!query||((p.name||'')+' '+selected.name).toLowerCase().includes(query));
 if(!visible.length)empty(list,query?'暂无匹配的项目':'暂无共享的游戏','项目由电脑 Remote Manager 共享。','选择电脑',chooseComputer);
 for(const p of visible){const row=document.createElement('div'),art=document.createElement('div'),labels=document.createElement('div'),name=document.createElement('h3'),computer=document.createElement('p'),state=document.createElement('small');row.className='game-row';art.className='game-art';art.textContent=Array.from(p.name||'G')[0].toUpperCase();labels.className='game-labels';name.textContent=p.name;computer.textContent=selected.name;state.textContent=p.state==='running'?'● 运行中':p.state==='preparing'?'正在准备':p.state==='error'?'编译失败':'未运行';state.className=p.state==='running'?'online':'';labels.append(name,computer,state);const enter=button(p.state==='running'?'继续 ›':p.state==='preparing'?'查看 ›':p.state==='error'?'处理 ›':'开始 ▷',()=>openGame(p.id));enter.setAttribute('aria-label',(p.state==='running'?'继续游戏 · ':'开始游戏 · ')+p.name);row.append(art,labels,enter);list.append(row);}
}
function button(text,fn){const b=document.createElement('button');b.textContent=text;b.onclick=()=>busy(b,fn);return b;}
async function busy(b,fn){b.disabled=true;try{message('');await fn();}catch(e){message(e.message);if(game)showMenu(true);else $('#panel').hidden=false;}finally{b.disabled=false;}}
async function list(){if(!saved?.token){hosts=mergeHosts();show();return;}const current=saved;const value=await api('defold/hosts');if(saved!==current)return;hosts=mergeHosts(value.hosts);show();}
let switchRequest=0;
async function connect(id){if(joining&&host===id)return;
 const request=++switchRequest;
 if(host&&host!==id){await window.remotePause();if(request!==switchRequest)return;}
 joining=true;catalogReady=false;clearTimeout(timer);paused=false;if(host!==id){games=[];game='';device=null;}host=id;localStorage.setItem('defold-selected-host',id);if(saved){saved.selectedHost=id;save();}renderCatalog();message('正在连接电脑…');const g=++generation;try{trace('authorization');await renew(true,g);trace('catalog-ready');message('');}catch(e){trace('connect-failed-'+connectionStage,e);throw e;}finally{if(g===generation){joining=false;renderCatalog();}}}
async function renew(force=false,expected=generation){if(paused||!host)return;if(!authorized(host))throw Error('请先添加这台电脑的邀请');const next=await viewerApi('defold/viewer/token',host);if(expected!==generation||paused)return;
 if(force||!room||access?.room!==next.room||room.state==='disconnected'){
  const old=room;room=null;if(old)await old.disconnect();access=next;const current=new LK.Room({adaptiveStream:true,dynacast:false});room=current;
  current.on(LK.RoomEvent.TrackSubscribed,track=>{if(room!==current||!game)return;if(track.kind==='video'){attachVideo(track);}else{const el=track.attach();el.autoplay=true;$('#audio').append(el);el.play().catch(()=>{message('点收起以启用声音');showMenu(true);});}});
  current.on(LK.RoomEvent.TrackUnsubscribed,track=>{if(videoTrack===track){videoTrack=null;waitForFrame('正在接收画面…');}for(const el of track.detach())if(el!==$('#video'))el.remove();});
  current.on(LK.RoomEvent.Disconnected,reason=>{trace('disconnected',String(reason));if(room===current){catalogReady=false;renderCatalog();hasControl=false;videoTrack=null;waitForFrame('正在重新连接…');if(!game)message('连接已中断，正在重连');}});
  trace('webrtc-connecting');await current.connect(next.url,next.token);trace('webrtc-connected');if(expected!==generation){await current.disconnect();return;}
  current.startAudio().catch(()=>{});trace('catalog-loading');
  // Host polls admission independently; retry read-only readiness, never repeat a mutation blindly.
  for(let n=0;n<12;n++){try{await refreshGames();break;}catch(e){if(n===11)throw e;await new Promise(r=>setTimeout(r,Math.min(1000,150*(n+1))));if(expected!==generation)return;}}
  if(game){const revision=previewRevision;await claimPreview(game,revision);if(revision===previewRevision){lastMetrics='';await syncDevice();}}
 }else {access=next;if(!game&&document.visibilityState!=='hidden'){await refreshGames();await list();}}
 clearTimeout(timer);timer=setTimeout(()=>renew(false,generation).catch(e=>{message(e.message);if(game)showMenu(true);else $('#panel').hidden=false;timer=setTimeout(()=>connect(host).catch(e=>message(e.message)),3000);}),8000);
}
async function refreshGames(){
 const request=++catalogRequest,current=room,selectedHost=host,currentGeneration=generation,items=new Map(),seen=new Set();let cursor='';
 do {
  const result=await rpc('games',{cursor});
  if(request!==catalogRequest||room!==current||host!==selectedHost||generation!==currentGeneration||paused)return;
  for(const p of result.games||[])items.set(p.id,p);
  cursor=result.nextCursor||'';
  if(cursor&&seen.has(cursor))throw Error('游戏目录分页异常，请重新连接电脑');
  seen.add(cursor);
 }while(cursor);
 catalogReady=true;renderGames([...items.values()]);
}
function renderGames(value){games=value||[];renderCatalog();}
async function openGame(id){
 const revision=++previewRevision,g=generation;
 const active=()=>revision===previewRevision&&generation===g&&game===id;
 game=id;hasControl=false;claimRequested=false;menuOpen=false;message('');waitForFrame('正在准备游戏…');show();
 try{
  let status=await rpc('start',{project:id});
  for(let n=0;active()&&status.state!=='running';n++){
   if(status.state==='error')throw Error(status.error||'构建失败');
   if(n===180)throw Error('准备超时，请查看电脑端构建状态');
   await new Promise(resolve=>setTimeout(resolve,500));
   if(active())status=await rpc('status',{project:id});
  }
  if(!active())return;
  await claimPreview(id,revision);
  if(!active())return;
  waitForFrame('正在接收画面…');lastMetrics='';await syncDevice();
 }catch(e){if(active()){message(e.message);showMenu(true);}}
}
async function syncDevice(){
 if(!room||!game||!hasControl||syncing||qualityChanging||paused)return;
 const m=metrics(),key=JSON.stringify(m),revision=previewRevision;
 if(m.width<32||m.height<32||key===lastMetrics)return;
 syncing=true;renderQuality();
 try{
  await endPointer();if(revision!==previewRevision)return;
  const result=await rpc('device',m);if(revision!==previewRevision)return;
  if(result.width!==m.width||result.height!==m.height)throw Error('设备尺寸同步失败');
  device=result;lastMetrics=key;seq=0;await rpc('stream',{resolution:streamQuality});
 }finally{syncing=false;renderQuality();if(JSON.stringify(metrics())!==key||revision!==previewRevision)setTimeout(()=>syncDevice().catch(e=>message(e.message)),180);}
}
function leavePreview(stopped=false){
 const id=game,current=room,shouldRelease=hasControl||claimRequested,releasePointer=endPointer();
 if(!id)return;
 ++previewRevision;game='';device=null;hasControl=false;menuOpen=false;frameVisible=false;lastMetrics='';
 if(videoTrack){videoTrack.detach($('#video'));videoTrack=null;}
 $('#audio').replaceChildren();message('');show();
 orderedControl(async()=>{
  await releasePointer;
  if(shouldRelease&&!stopped&&room===current&&current?.state==='connected'){
   try{await rpc('release',{project:id});}catch{/* A preparing project has no control lease. */}
  }
 }).then(()=>{if(!game&&room?.state==='connected')refreshGames().catch(e=>message(e.message));});
}
async function stopPreview(){
 if(!game||!hasControl)return;
 const revision=previewRevision,id=game;
 try{await orderedControl(()=>rpc('stop',{project:id}));if(revision===previewRevision)leavePreview(true);}
 catch(e){if(revision===previewRevision){message(e.message);showMenu(true);}}
}
function point(e){const r=$('#video').getBoundingClientRect();const scale=Math.min(r.width/device.width,r.height/device.height),w=device.width*scale,h=device.height*scale;return {x:Math.min(device.width-1,Math.max(0,Math.floor((e.clientX-r.left-(r.width-w)/2)/scale))),y:Math.min(device.height-1,Math.max(0,Math.floor((e.clientY-r.top-(r.height-h)/2)/scale)))};}
let inputChain=Promise.resolve(),pendingEdges=0,inputReceipts=0;
function inputError(error){pointer=null;trace('input-failed',error);message('触控同步失败：'+String(error?.message||error));showMenu(true);}
async function input(down,p,move=false){
 if(!room||!device||syncing)return;
 const current=room,currentGeneration=generation,revision=device.device_revision;
 const valid=()=>room===current&&generation===currentGeneration&&device?.device_revision===revision;
 if(move){
  // Lossy motion must never overtake an unacknowledged press or a queued release.
  if(pendingEdges)return;
  const values={...p,down,deviceRevision:revision,sequence:++seq};
  await current.localParticipant.publishData(new TextEncoder().encode(JSON.stringify({action:'move',project:game,lease:access.lease,...values})),{reliable:false,topic:'defold.move',destinationIdentities:[access.hostIdentity]});return;
 }
 if(pendingEdges>=32)throw Error('触控过快，请抬起手指后重试');
 ++pendingEdges;
 const sent=inputChain.then(async()=>{if(!valid())return;const result=await rpc('input',{...p,down,deviceRevision:revision,sequence:++seq});if(result?.stale)throw Error('输入顺序已变化，请重新触摸');if(inputReceipts++<4)console.info('[Remote] input-applied '+(down?'down':'up'));});
 inputChain=sent.catch(()=>{});
 try{await sent;}finally{--pendingEdges;}
}
async function endPointer(){if(pointer){const p=pointer;pointer=null;try{await input(false,p);}catch(e){inputError(e);}}}
$('#video').onpointerdown=e=>{if(!frameVisible||!device||syncing||!$('#panel').hidden)return;if(pointer){endPointer();return;}pointer=point(e);$('#video').setPointerCapture(e.pointerId);input(true,pointer).catch(inputError);};
$('#video').onpointermove=e=>{if(!pointer)return;pointer=point(e);if(performance.now()-lastMove<34)return;lastMove=performance.now();input(true,pointer,true).catch(()=>{});};
$('#video').onpointerup=$('#video').onpointercancel=()=>endPointer();
window.remoteEndPointer=endPointer;
setInterval(()=>{if(pointer&&!paused&&!syncing)input(true,pointer,true).catch(()=>{});},200);
window.remoteMenu=()=>showMenu(!menuOpen);
window.remoteBack=()=>{if($('#quality-dialog').open)$('#quality-dialog').close();else if(game)leavePreview();else if(document.querySelector('dialog[open]'))document.querySelector('dialog[open]').close();else selectPage('games');};
$('#preview-menu').onclick=window.remoteMenu;
window.remoteMetricsChanged=()=>syncDevice().catch(e=>{message(e.message);show();});
window.remotePause=async()=>{if(paused)return;paused=true;joining=false;++generation;clearTimeout(timer);await endPointer();const old=room,releasedHost=host;room=null;catalogReady=false;renderCatalog();if(old)await old.disconnect();if(releasedHost&&authorized(releasedHost))try{await viewerApi('defold/viewer/release',releasedHost);}catch{}};
window.remoteResume=()=>{paused=false;if(host)connect(host).catch(e=>{message(e.message);show();});};
$('#login').onsubmit=e=>{e.preventDefault();busy($('#login button'),async()=>{const r=await fetch(server($('#server').value)+'/v1/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:$('#username').value,password:$('#password').value}),signal:AbortSignal.timeout(10000)});$('#password').value='';const v=await r.json();if(!r.ok)throw Error(v.error||'登录失败');saved={server:server($('#server').value),token:v.token,grants:{}};save();paused=false;await list();$('#connection-dialog').close();chooseComputer();});};
$('#claim').onclick=()=>busy($('#claim'),async()=>{const invitation=JSON.parse($('#invitation').value);if(server(invitation.server)!==saved.server)throw Error('邀请服务器与登录地址不一致');const v=await api('defold/claim',{id:invitation.invitation});saved.grants[v.host]=v;save();$('#invitation').value='';await list();$('#connection-dialog').close();selectPage('games');await connect(v.host);});
const awakeSetting=$('#awake-setting');
if(window.DefoldRemote?.setKeepAwake){
 let keepAwake=window.DefoldRemote.keepAwake();
 const updateAwake=()=>awakeSetting.setAttribute('aria-checked',String(keepAwake));
 awakeSetting.hidden=false;updateAwake();
 awakeSetting.onclick=()=>{keepAwake=!keepAwake;window.DefoldRemote.setKeepAwake(keepAwake);updateAwake();};
}
$('#refresh').onclick=()=>busy($('#refresh'),async()=>{await list();if(room&&!game)await refreshGames();});
$('#logout').onclick=()=>busy($('#logout'),async()=>{await window.remotePause();try{await api('logout',{});}catch{}saved=null;host='';game='';hosts=[];games=[];access=null;device=null;localStorage.removeItem('defold-cloud');show();});
$('#resume').onclick=()=>{showMenu(false);room?.startAudio().catch(()=>{});if(videoTrack)$('#video').play().catch(e=>{message(e.message);showMenu(true);});};
$('#leave').onclick=()=>leavePreview();
$('#stop').onclick=()=>busy($('#stop'),stopPreview);
$('#scan-pair').hidden=!window.DefoldRemote?.scan;$('#scan-pair').onclick=async()=>{await window.remotePause();window.DefoldRemote.scan();};
$('#lan-mode').onclick=async()=>{await window.remotePause();window.DefoldRemote?.close(host);};
for(const id of ['#quality-setting','#quality-preview'])$(id).onclick=chooseQuality;
$('#video').onresize=renderQuality;
$('#add-computer').onclick=$('#picker-add').onclick=$('#account').onclick=openConnection;
$('#choose-computer').onclick=chooseComputer;
$('#search').oninput=e=>{query=e.target.value.trim().toLowerCase();renderCatalog();};
for(const tab of document.querySelectorAll('[data-page]'))tab.onclick=()=>selectPage(tab.dataset.page);
for(const close of document.querySelectorAll('[data-dismiss]'))close.onclick=()=>close.closest('dialog').close();
addEventListener('resize',()=>setTimeout(window.remoteMetricsChanged,180));hosts=mergeHosts();show();
window.remotePairingsChanged=()=>{
 for(const p of nativeComputers())paired.set(p.host,p);
 hosts=mergeHosts(hosts);renderCatalog();
 const target=matchingHost()||(!switchingComputer&&paired.size===1?[...paired.keys()][0]:null);
 if(!host&&!paused&&target)connect(target).catch(e=>message(e.message));
};
const matchingHost=()=>[...paired.values()].find(p=>p.selected)?.host;
const preferred=matchingHost()||(!switchingComputer&&(localStorage.getItem('defold-selected-host')||saved?.selectedHost));
const immediate=authorized(preferred)?preferred:!switchingComputer&&paired.size===1?[...paired.keys()][0]:null;
if(immediate)connect(immediate).catch(e=>message(e.message));
if(saved?.token)list().then(()=>{
 if(immediate||host||paused||switchingComputer)return;
 const available=hosts.filter(h=>h.online&&authorized(h.id));
 if(available.length===1)return connect(available[0].id);
}).catch(e=>{if(!immediate)message(e.message);});
