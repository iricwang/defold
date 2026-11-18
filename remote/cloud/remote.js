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
let hosts=[],games=[],page='games',query='',catalogSignature='',catalogRequest=0;
let connectionStage='idle';
function trace(stage,error){connectionStage=stage;console.info('[Remote] '+stage+(error?' · '+String(error?.message||error):''));}
addEventListener('error',e=>trace('script-error',e.error||e.message));
addEventListener('unhandledrejection',e=>trace('unhandled-error',e.reason));
const QUALITY_OPTIONS=['540p','720p','1080p'];
let streamQuality=localStorage.getItem('defold-cloud-quality')||'720p',qualityChanging=false;
if(!QUALITY_OPTIONS.includes(streamQuality))streamQuality='720p';
function renderQuality(){for(const id of ['#quality-setting','#quality-preview']){const select=$(id);select.value=streamQuality;select.disabled=qualityChanging||syncing;}$('#resume').disabled=qualityChanging||syncing;const video=$('#video');$('#stream-dimensions').textContent=qualityChanging?'正在切换清晰度…':video.videoWidth&&video.videoHeight?'接收画面 '+video.videoWidth+' × '+video.videoHeight:'画质选择会自动保存';}
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
function message(s){$('#message').textContent=s||'';$('#connection-message').textContent=s||'';}
function metrics(){if(window.DefoldRemote)return JSON.parse(window.DefoldRemote.metrics());return {width:Math.round(innerWidth*devicePixelRatio),height:Math.round(innerHeight*devicePixelRatio),top:0,right:0,bottom:0,left:0};}
function server(value){const url=new URL(value);if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||!['','/'].includes(url.pathname))throw Error('请使用 HTTPS 服务器地址');return url.origin;}
async function api(path,body){const r=await fetch(saved.server+'/v1/'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+saved.token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});const v=await r.json();if(!r.ok)throw Error(v.error||'连接失败');return v;}
async function rpc(action,params={}){if(!room||!access)throw Error('正在连接电脑');const payload=JSON.stringify({action,project:game,lease:access.lease,requestId:crypto.randomUUID(),...params});const current=room,lease=access.lease,call=()=>current.localParticipant.performRpc({destinationIdentity:access.hostIdentity,method:'defold.control',payload,responseTimeout:RPC_RESPONSE_TIMEOUT_MS});let raw;try{raw=await call();}catch(e){if(room!==current||access.lease!==lease||current.state!=='connected')throw e;raw=await call();}const value=JSON.parse(raw);if(!value.ok)throw Error(value.error);return value.result;}
function show(){
 renderQuality();
 $('#login').hidden=!!saved?.token;$('#authorization').hidden=!saved?.token;
 $('#connection-title').textContent=saved?.token?'添加电脑':'登录服务器';
 $('#catalog').hidden=!!game;$('#controls').hidden=!game;$('#panel').classList.toggle('game',!!game);$('#panel').hidden=false;
 renderCatalog();
}
function openConnection(){message('');$('#computer-dialog').close();$('#connection-dialog').showModal();}
function selectPage(next){page=next;renderCatalog();}
function empty(parent,title,description,action,run){const box=document.createElement('div');box.className='empty';const h=document.createElement('h3'),p=document.createElement('p');h.textContent=title;p.textContent=description;box.append(h,p);if(action)box.append(button(action,run));parent.append(box);}
function computerRow(h,choose){
 const row=button('',async()=>{if(!saved?.grants?.[h.id]){openConnection();return;}if(!h.online)return;$('#computer-dialog').close();selectPage('games');await connect(h.id);});
 row.className='computer-row';row.setAttribute('aria-pressed',String(host===h.id));
 const labels=document.createElement('div'),name=document.createElement('h3'),detail=document.createElement('p'),state=document.createElement('span');labels.className='game-labels';name.textContent=h.name;detail.textContent=saved?.grants?.[h.id]?'已授权':'等待授权';labels.append(name,detail);state.className='status'+(h.online?' online':'');state.textContent=!saved?.grants?.[h.id]?'授权':h.online?(choose?'查看游戏 ›':'在线'):'离线';row.append(labels,state);row.disabled=!h.online&&!!saved?.grants?.[h.id];return row;
}
async function chooseComputer(){if(!saved?.token){openConnection();return;}try{await list();}catch(e){message(e.message);}const options=$('#computer-options');options.replaceChildren();for(const h of hosts)options.append(computerRow(h,false));if(!hosts.length)empty(options,'还没有连接的电脑','从右上角添加电脑，授权后即可查看游戏。');$('#computer-dialog').showModal();}
function renderCatalog(){
 const signature=JSON.stringify([page,query,hosts,games,host,!!saved?.token,saved?.grants]);if(signature===catalogSignature)return;catalogSignature=signature;
 const titles={games:['游戏','你的游戏','game'],running:['运行中','随时继续游戏','layers'],computers:['电脑','你的电脑','monitor'],settings:['设置','按你的习惯使用','settings']},t=titles[page],selected=hosts.find(h=>h.id===host);
 $('#page-title').textContent=t[0];$('#section-title').textContent=t[1];$('#page-icon use').setAttribute('href','#icon-'+t[2]);
 $('#subtitle').textContent=hosts.length?hosts.length+' 台电脑':'服务器';$('#choose-computer').textContent=(selected?.name||'选择电脑')+' ▾';
 $('#choose-computer').hidden=!['games','running'].includes(page);$('#search').hidden=page!=='games';$('#hosts').hidden=page!=='computers';$('#settings').hidden=page!=='settings';$('#games').hidden=!['games','running'].includes(page);$('#logout').hidden=!saved?.token;
 for(const tab of document.querySelectorAll('[data-page]'))tab.setAttribute('aria-pressed',String(tab.dataset.page===page));
 $('#hosts').replaceChildren();for(const h of hosts)$('#hosts').append(computerRow(h,true));if(!hosts.length)empty($('#hosts'),'还没有连接的电脑','在电脑 Remote Manager 打开“公网连接”，然后添加电脑。','添加电脑',openConnection);
 const list=$('#games');list.replaceChildren();
 if(!saved?.token){empty(list,'把电脑上的游戏带到手机上','添加一台电脑，选择游戏后电脑会自动准备并运行。','添加电脑',openConnection);return;}
 if(!selected){empty(list,'选择一台电脑','选择已授权的电脑，查看它共享的游戏。','选择电脑',chooseComputer);return;}
 const visible=games.filter(p=>(page!=='running'||p.state==='running')&&(!query||((p.name||'')+' '+selected.name).toLowerCase().includes(query)));
 if(!visible.length)empty(list,page==='running'?'还没有运行中的游戏':query?'暂无匹配的项目':'暂无共享的游戏','项目由电脑 Remote Manager 共享。','选择电脑',chooseComputer);
 for(const p of visible){const row=document.createElement('div'),art=document.createElement('div'),labels=document.createElement('div'),name=document.createElement('h3'),computer=document.createElement('p'),state=document.createElement('small');row.className='game-row';art.className='game-art';art.textContent=Array.from(p.name||'G')[0].toUpperCase();labels.className='game-labels';name.textContent=p.name;computer.textContent=selected.name;state.textContent=p.state==='running'?'● 运行中':p.state==='preparing'?'正在准备':p.state==='error'?'编译失败':'未运行';state.className=p.state==='running'?'online':'';labels.append(name,computer,state);const enter=button(p.state==='running'?'继续 ›':p.state==='preparing'?'查看 ›':p.state==='error'?'处理 ›':'开始 ▷',()=>openGame(p.id));enter.setAttribute('aria-label',(p.state==='running'?'继续游戏 · ':'开始游戏 · ')+p.name);row.append(art,labels,enter);list.append(row);}
}
function button(text,fn){const b=document.createElement('button');b.textContent=text;b.onclick=()=>busy(b,fn);return b;}
async function busy(b,fn){b.disabled=true;try{await fn();message('');}catch(e){message(e.message);$('#panel').hidden=false;}finally{b.disabled=false;}}
async function list(){if(!saved?.token){show();return;}const current=saved;const value=await api('defold/hosts');if(saved!==current)return;hosts=value.hosts;show();}
async function connect(id){if(joining)return;joining=true;paused=false;if(host!==id){games=[];game='';device=null;}host=id;if(saved){saved.selectedHost=id;save();}renderCatalog();message('正在连接电脑…');const g=++generation;try{trace('authorization');await renew(true,g);trace('catalog-ready');message('');}catch(e){trace('connect-failed-'+connectionStage,e);throw e;}finally{joining=false;}}
async function renew(force=false,expected=generation){if(paused||!host||!saved)return;const grant=saved.grants?.[host];if(!grant)throw Error('请先添加这台电脑的邀请');const next=await api('defold/viewer/token',grant);if(expected!==generation||paused)return;
 if(force||!room||access?.room!==next.room||room.state==='disconnected'){
  const old=room;room=null;if(old)await old.disconnect();access=next;const current=new LK.Room({adaptiveStream:true,dynacast:false});room=current;
  current.on(LK.RoomEvent.TrackSubscribed,track=>{if(room!==current)return;if(track.kind==='video'){track.attach($('#video'));$('#video').play().catch(()=>{});}else{const el=track.attach();el.autoplay=true;$('#audio').append(el);el.play().catch(()=>{message('点击继续预览以启用声音');$('#panel').hidden=false;});}});
  current.on(LK.RoomEvent.TrackUnsubscribed,track=>{for(const el of track.detach())if(el!==$('#video'))el.remove();});
  current.on(LK.RoomEvent.Disconnected,reason=>{trace('disconnected',String(reason));if(room===current){message('连接中断（'+String(reason)+'），正在重连');$('#panel').hidden=false;}});
  trace('webrtc-connecting');await current.connect(next.url,next.token);trace('webrtc-connected');if(expected!==generation){await current.disconnect();return;}
  trace('audio-starting');await current.startAudio();trace('catalog-loading');
  // Host polls admission independently; retry read-only readiness, never repeat a mutation blindly.
  for(let n=0;n<12;n++){try{await refreshGames();break;}catch(e){if(n===11)throw e;await new Promise(r=>setTimeout(r,1000));if(expected!==generation)return;}}
  if(game){await rpc('claim');lastMetrics='';await syncDevice();}
 }else {access=next;if(!game&&document.visibilityState!=='hidden'){await refreshGames();await list();}}
 clearTimeout(timer);timer=setTimeout(()=>renew(false,generation).catch(e=>{message(e.message);$('#panel').hidden=false;timer=setTimeout(()=>connect(host).catch(e=>message(e.message)),3000);}),8000);
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
 renderGames([...items.values()]);
}
function renderGames(value){games=value||[];renderCatalog();}
async function openGame(id){const g=generation;game=id;show();message('准备游戏…');await rpc('start');let ready=false;for(let n=0;n<180;n++){if(g!==generation)return;const p=await rpc('status');if(p.state==='running'){ready=true;break;}if(p.state==='error')throw Error(p.error||'构建失败');await new Promise(r=>setTimeout(r,1000));}if(!ready)throw Error('准备超时，请在电脑端查看构建状态');await rpc('claim');lastMetrics='';await syncDevice();}
async function syncDevice(){if(!room||!game||syncing||qualityChanging||paused)return;const m=metrics(),key=JSON.stringify(m);if(m.width<32||m.height<32||key===lastMetrics)return;syncing=true;renderQuality();try{await endPointer();device=await rpc('device',m);if(device.width!==m.width||device.height!==m.height)throw Error('设备尺寸同步失败');lastMetrics=key;seq=0;await rpc('stream',{resolution:streamQuality});$('#dimensions').textContent=`游戏尺寸 ${device.width} × ${device.height}`;$('#panel').hidden=true;}finally{syncing=false;renderQuality();if(JSON.stringify(metrics())!==key)setTimeout(()=>syncDevice().catch(e=>message(e.message)),180);}}
function point(e){const r=$('#video').getBoundingClientRect();const scale=Math.min(r.width/device.width,r.height/device.height),w=device.width*scale,h=device.height*scale;return {x:Math.min(device.width-1,Math.max(0,Math.floor((e.clientX-r.left-(r.width-w)/2)/scale))),y:Math.min(device.height-1,Math.max(0,Math.floor((e.clientY-r.top-(r.height-h)/2)/scale)))};}
let inputChain=Promise.resolve(),pendingEdges=0,inputReceipts=0;
function inputError(error){pointer=null;trace('input-failed',error);message('触控同步失败：'+String(error?.message||error));$('#panel').hidden=false;}
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
$('#video').onpointerdown=e=>{if(!device||syncing||!$('#panel').hidden)return;if(pointer){endPointer();return;}pointer=point(e);$('#video').setPointerCapture(e.pointerId);input(true,pointer).catch(inputError);};
$('#video').onpointermove=e=>{if(!pointer)return;pointer=point(e);if(performance.now()-lastMove<34)return;lastMove=performance.now();input(true,pointer,true).catch(()=>{});};
$('#video').onpointerup=$('#video').onpointercancel=()=>endPointer();
window.remoteEndPointer=endPointer;
setInterval(()=>{if(pointer&&!paused&&!syncing)input(true,pointer,true).catch(()=>{});},200);
window.remoteMenu=()=>{endPointer();show();};
window.remoteMetricsChanged=()=>syncDevice().catch(e=>{message(e.message);show();});
window.remotePause=async()=>{paused=true;++generation;clearTimeout(timer);await endPointer();const old=room;room=null;if(old)await old.disconnect();if(host&&saved?.grants?.[host])try{await api('defold/viewer/release',saved.grants[host]);}catch{}};
window.remoteResume=()=>{paused=false;if(host)connect(host).catch(e=>{message(e.message);show();});};
$('#login').onsubmit=e=>{e.preventDefault();busy($('#login button'),async()=>{const r=await fetch(server($('#server').value)+'/v1/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:$('#username').value,password:$('#password').value}),signal:AbortSignal.timeout(10000)});$('#password').value='';const v=await r.json();if(!r.ok)throw Error(v.error||'登录失败');saved={server:server($('#server').value),token:v.token,grants:{}};save();paused=false;await list();$('#connection-dialog').close();chooseComputer();});};
$('#claim').onclick=()=>busy($('#claim'),async()=>{const invitation=JSON.parse($('#invitation').value);if(server(invitation.server)!==saved.server)throw Error('邀请服务器与登录地址不一致');const v=await api('defold/claim',{id:invitation.invitation});saved.grants[v.host]=v;save();$('#invitation').value='';await list();$('#connection-dialog').close();selectPage('games');await connect(v.host);});
$('#refresh').onclick=()=>busy($('#refresh'),async()=>{await list();if(room&&!game)await refreshGames();});
$('#logout').onclick=()=>busy($('#logout'),async()=>{await window.remotePause();try{await api('logout',{});}catch{}saved=null;host='';game='';hosts=[];games=[];access=null;device=null;localStorage.removeItem('defold-cloud');show();});
$('#resume').onclick=()=>busy($('#resume'),async()=>{await room?.startAudio();if(paused)window.remoteResume();else {lastMetrics='';await syncDevice();}});
$('#leave').onclick=()=>busy($('#leave'),async()=>{await endPointer();if(room&&game)await rpc('release');game='';device=null;show();await refreshGames();});
$('#stop').onclick=()=>busy($('#stop'),async()=>{await rpc('stop');game='';device=null;show();await refreshGames();});
$('#lan-mode').onclick=async()=>{await window.remotePause();window.DefoldRemote?.close();};
for(const id of ['#quality-setting','#quality-preview'])$(id).onchange=e=>setQuality(e.target.value).catch(error=>{renderQuality();message(error.message);});
$('#video').onresize=renderQuality;
$('#add-computer').onclick=$('#picker-add').onclick=$('#account').onclick=openConnection;
$('#choose-computer').onclick=chooseComputer;
$('#search').oninput=e=>{query=e.target.value.trim().toLowerCase();renderCatalog();};
for(const tab of document.querySelectorAll('[data-page]'))tab.onclick=()=>selectPage(tab.dataset.page);
for(const close of document.querySelectorAll('[data-dismiss]'))close.onclick=()=>close.closest('dialog').close();
addEventListener('resize',()=>setTimeout(window.remoteMetricsChanged,180));show();if(saved?.token)list().then(()=>{const available=hosts.filter(h=>h.online&&saved?.grants?.[h.id]);const remembered=available.find(h=>h.id===saved?.selectedHost);if(remembered||available.length===1)return connect((remembered||available[0]).id);}).catch(e=>message(e.message));
