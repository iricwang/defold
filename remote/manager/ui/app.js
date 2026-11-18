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
const $=s=>document.querySelector(s), esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const invoke=(command,args={})=>window.__TAURI__.core.invoke(command,args);
let state=null,page='projects',selected='',filter='',polling=false,pending=false,lastRender='',thumbAt=0,pairTimer=null,dialogRevision=0;
let refreshTimer=null,refreshRequested=false,thumbnailBusy=false,refreshError='';
let nativeVisible=typeof window.__REMOTE_WINDOW_VISIBLE__==='boolean'?window.__REMOTE_WINDOW_VISIBLE__:null;
function uiHidden(){return nativeVisible===null?document.hidden:!nativeVisible;}
const labels={new:'待准备',preparing:'准备中',ready:'可运行',running:'运行中',dirty:'有更新',error:'需要处理'};
function notice(message){$('#notice').hidden=!message;$('#notice').textContent=message;}
async function rpc(method,params={}){return invoke('rpc',{method,params});}
async function action(method,params={}){if(pending)return;pending=true;try{await rpc(method,params);notice('');await refresh(true);}catch(e){notice(String(e));}finally{pending=false;render();}}
function dialog(html){++dialogRevision;clearInterval(pairTimer);pairTimer=null;$('#dialog').classList.remove('pair-dialog');$('#dialog-content').innerHTML=html;if(!$('#dialog').open)$('#dialog').showModal();$('#dialog').querySelector('[data-close]')?.addEventListener('click',()=>$('#dialog').close());}
function confirmAction(title,text,button,fn){dialog(`<h2>${esc(title)}</h2><p>${esc(text)}</p><div class="actions"><button data-close>取消</button><button id="confirm" class="primary">${esc(button)}</button></div>`);$('#confirm').onclick=()=>{$('#dialog').close();fn();};}
async function add(){try{const path=await invoke('choose_project');if(path){const p=await rpc('add',{path});selected=p.id;page='projects';await refresh(true);}}catch(e){notice(String(e));}}
function addressOptions(selected){return `<option value="" disabled ${selected?'':'selected'}>选择连接 IP</option>`+state.addresses.map((a,i)=>`<option value="${esc(a)}" ${a===selected?'selected':''}>${esc(a)}${i===0?' · 推荐':''}</option>`).join('');}
function sizeQr(){const image=$('#pair-code .qr');if(!image)return;const dpr=window.devicePixelRatio||1,modules=Number(image.dataset.modules),available=Math.min(320,$('#pair-code').clientWidth-32);const scale=Math.max(1,Math.floor(available*dpr/modules));image.style.width=image.style.height=`${modules*scale/dpr}px`;image.style.transform='none';const bounds=image.getBoundingClientRect();image.style.transform=`translate(${(Math.round(bounds.x*dpr)-bounds.x*dpr)/dpr}px,${(Math.round(bounds.y*dpr)-bounds.y*dpr)/dpr}px)`;}
async function pair(){try{
 state=await rpc('status');let revision=0,pairing=null,status='waiting',updating=false,checking=false,compatibility=false,errorMessage='';
 dialog(`<div class="pair-heading"><h2>连接手机</h2><button data-close aria-label="关闭">关闭</button></div><p>首次扫码时手机与电脑需在同一局域网。电脑登录公网服务后，已配对手机可直接切换连接方式。</p><div class="pair-qr-column"><div id="pair-code" aria-busy="true"><p>正在生成二维码…</p></div><div id="pair-status" class="pair-status" role="status">等待扫码</div><button id="new-qr">刷新二维码</button></div><details class="pair-options"><summary>其他连接方式</summary><label class="address-choice">连接 IP<select id="pair-address" aria-label="连接地址">${addressOptions(state.connectionAddress)}</select></label><p id="pair-network" class="caption"></p><div class="actions"><button id="copy-link">复制配对链接</button><button id="qr-format" class="quiet">兼容旧版</button></div><textarea id="pair-link" readonly aria-label="完整配对链接" hidden></textarea><p id="pair-copy-status" class="caption" role="status"></p></details>`);
 $('#dialog').classList.add('pair-dialog');
 const owner=dialogRevision;
 function live(){return dialogRevision===owner&&$('#dialog').open&&$('#pair-address');}
 function controls(){if(!live())return;const busy=updating||status==='pairing';$('#new-qr').disabled=busy;$('#pair-address').disabled=busy;$('#qr-format').disabled=busy||!pairing||status!=='waiting';$('#copy-link').disabled=busy||!pairing||status!=='waiting';}
 async function draw(){if(!pairing||!live())return;const current=++revision,value=compatibility?pairing.uri:pairing.qrUri||pairing.uri;const svg=await invoke('qr_svg',{value});if(current!==revision||!live())return;const doc=new DOMParser().parseFromString(svg,'image/svg+xml');const modules=Number(doc.documentElement.getAttribute('width'));if(!Number.isFinite(modules)||modules<21||modules>185)throw new Error('二维码渲染尺寸无效');$('#pair-code').innerHTML=`<img class="qr" data-modules="${modules}" alt="${compatibility?'旧版兼容':'电脑配对'}二维码" src="data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}">`;$('#pair-code').setAttribute('aria-busy','false');$('#qr-format').textContent=compatibility?'使用标准二维码':'兼容旧版';sizeQr();}
 function feedback(){if(!live())return;const remaining=Math.max(0,Math.ceil(((pairing?.expiresAt||0)-Date.now())/1000));$('#pair-status').className=`pair-status ${status}`;$('#pair-code').dataset.state=status;$('#new-qr').textContent=status==='paired'?'连接另一台手机':'刷新二维码';$('#pair-status').textContent=status==='paired'?'已连接，请在手机选择游戏':status==='pairing'?'正在配对…':status==='error'?errorMessage||'连接失败，请重试':status==='expired'?'二维码已过期，正在刷新…':`等待扫码 · ${remaining} 秒有效`;controls();}
 async function update(change){if(updating||status==='pairing'||!live())return;updating=true;controls();try{const address=$('#pair-address').value;if(!address)throw new Error('请选择连接 IP');if(change)state=await rpc('connection-address',{address});pairing=await rpc('pairing');status='waiting';errorMessage='';await draw();if(live()){const local=pairing.address.includes('127.0.0.1');$('#pair-network').textContent=local?'仅有本机地址，请先接入局域网。':'';$('#pair-link').value=pairing.uri;$('#pair-copy-status').textContent='';$('#pair-link').hidden=true;}render();}catch(e){status='error';errorMessage=String(e);}finally{updating=false;feedback();}}
 async function tick(){if(!live()){clearInterval(pairTimer);return;}if(uiHidden()||checking||updating||!pairing||status==='paired')return;checking=true;try{const next=await rpc('pairing-status');if(!live())return;status=next.state;feedback();if(status==='expired')await update(false);}catch(e){if(live())$('#pair-status').textContent=`连接暂不可用：${e}`;}finally{checking=false;}}
 $('#pair-address').onchange=()=>update(true);$('#new-qr').onclick=()=>update(false);$('#qr-format').onclick=async()=>{compatibility=!compatibility;try{await draw();}catch(e){if(live())$('#pair-status').textContent=String(e);}};
 $('#copy-link').onclick=async()=>{if(!pairing)return;try{await navigator.clipboard.writeText(pairing.uri);if(live())$('#pair-copy-status').textContent='已复制';}catch(e){if(live()){$('#pair-link').hidden=false;$('#pair-link').select();$('#pair-copy-status').textContent='请复制链接，在手机 Remote 中粘贴。';}}};
 await update(false);feedback();if(live())pairTimer=setInterval(tick,1000);
}catch(e){notice(String(e));}}

// Only visible fields invalidate a page. Stream dimensions, hashes and other
// protocol metadata must not rebuild the UI or restart image decoding.
function projectPresentation(p){return [p.id,p.name,p.root,p.state,p.busy,p.stage,p.shared,p.modified,p.error,p.failed_stage,p.controller,p.session_id];}
function presentationKey(){
 const visible=page==='settings'?[pending,state.settings,state.addresses,state.connectionAddress,state.platform,state.runtime,state.engine,state.cloud]:page==='devices'?state.devices:state.projects.filter(p=>page!=='running'||p.state==='running').map(projectPresentation);
 return JSON.stringify([page,selected,filter,visible]);
}
function render(){
 if(!state)return;
 const running=state.projects.filter(p=>p.state==='running').length;
 $('#computer').textContent=state.computer;$('#host-address').textContent=state.connectionAddress||'未连接局域网';$('#project-count').textContent=state.projects.length;$('#running-count').textContent=running;
 $('#summary').textContent=`${running} 个运行中 · ${state.devices.length} 台设备`;
 document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',b.dataset.page===page));
 $('#page-title').textContent=({projects:'项目',running:'运行中',devices:'设备',settings:'设置'})[page];
 $('#page-caption').textContent='';
 const key=presentationKey();if(key===lastRender)return;
 if(page==='projects'||page==='running')renderProjects();else if(page==='devices')renderDevices();else renderSettings();
 lastRender=presentationKey();
}
function replaceMarkup(element,html){if(element.dataset.markup===html)return false;element.innerHTML=html;element.dataset.markup=html;return true;}
function previewKey(p){return p?`${p.id}:${p.session_id||''}`:'';}
function renderProjects(){
 const projects=state.projects.filter(p=>page!=='running'||p.state==='running');
 if(!projects.length){$('#content').innerHTML=`<div class="empty"><div class="empty-symbol">${page==='running'?'▷':'▦'}</div><h2>${page==='running'?'暂无运行中的游戏':'添加 Defold 项目'}</h2><p>${page==='running'?'从项目列表选择游戏运行。':'选择包含 game.project 的文件夹。'}</p><button id="empty-action" class="primary">${page==='running'?'查看项目':'添加项目'}</button></div>`;$('#empty-action').onclick=()=>{if(page==='running'){page='projects';render();}else add();};return;}
 if(!projects.some(p=>p.id===selected))selected=projects[0].id;
 const p=projects.find(p=>p.id===selected),list=projects.filter(p=>(p.name+' '+p.root).toLowerCase().includes(filter.toLowerCase()));
 if(!$('#project-items')){
  $('#content').innerHTML='<div class="workspace"><div class="project-list"><input class="search" id="search" placeholder="搜索项目" aria-label="搜索项目"><div id="project-items" class="project-items"></div></div><article id="project-detail" class="detail"></article></div>';
  $('#search').value=filter;$('#search').oninput=e=>{filter=e.target.value;render();};
 }
 const items=list.map(p=>`<button class="project ${p.id===selected?'selected':''}" data-id="${esc(p.id)}"><span class="project-icon">${esc(p.name.slice(0,1).toUpperCase())}</span><span class="project-main"><strong>${esc(p.name)}</strong><span class="meta"><span class="${esc(p.state)}">${p.state==='running'?'<i></i>':''}${labels[p.state]||esc(p.state)}</span><span>${p.shared?'已共享':'仅本机'}</span></span></button>`).join('')||'<p class="caption">没有匹配的项目</p>';
 if(replaceMarkup($('#project-items'),items))document.querySelectorAll('[data-id]').forEach(b=>b.onclick=()=>{selected=b.dataset.id;thumbAt=0;render();refreshThumbnail();});
 const detail=$('#project-detail'),image=$('#thumbnail'),session=previewKey(p),keepImage=image?.dataset.sessionKey===session?image:null;
 const expanded=!!detail.querySelector('details')?.open;
 const body=`<div class="detail-head"><h2>${esc(p.name)}</h2><span class="tag ${esc(p.state)}">${labels[p.state]||esc(p.state)}</span></div><div class="preview">${p.state==='running'?`<img id="thumbnail" data-session-key="${esc(session)}" alt="游戏预览">`:'<span>运行后显示画面</span>'}</div><div class="detail-body"><div class="actions"><button class="primary" data-action="${p.state==='running'?'stop':p.busy?'cancel':'start'}">${p.state==='running'?'停止':p.busy?'取消准备':'运行'}</button><label class="check"><input id="shared" type="checkbox" ${p.shared?'checked':''}>共享到手机</label></div>${p.busy?`<p class="caption" role="status">${esc(p.stage||'正在准备…')}</p>`:''}${p.error?`<div class="error-box" role="alert">${p.failed_stage?`${esc(p.failed_stage)}<br>`:''}${esc(p.error)}</div>`:''}${p.controller?`<p class="caption">控制设备：${esc(p.controller)}</p>`:''}<details class="detail-secondary"><summary>项目详情</summary><p class="path" title="${esc(p.root)}">${esc(p.root)}</p><div class="actions"><button data-action="logs">日志</button><button data-action="remove" class="quiet danger">移除项目</button></div></details></div>`;
 if(!replaceMarkup(detail,body))return;
 detail.querySelector('details').open=expanded;
 if(keepImage&&$('#thumbnail'))$('#thumbnail').replaceWith(keepImage);else thumbAt=0;
 $('#shared').onchange=e=>action('share',{id:p.id,shared:e.target.checked});
 detail.querySelectorAll('[data-action]').forEach(b=>b.onclick=async()=>{const op=b.dataset.action;if(op==='remove')confirmAction('移除项目？',p.state==='running'?'将停止游戏并移出列表，工程文件保留。':'仅移出列表，工程文件保留。','移除',()=>action(op,{id:p.id}));else if(op==='logs'){try{const data=await rpc('logs',{id:p.id});dialog(`<h2>${esc(p.name)} · 日志</h2><pre>${esc(data.text||JSON.stringify(data,null,2))}</pre><div class="actions"><button data-close>关闭</button></div>`);}catch(e){notice(String(e));}}else action(op,{id:p.id});});
}
function renderDevices(){ $('#content').innerHTML=state.devices.length?`<div class="settings">${state.devices.map(d=>`<div class="box device"><span class="project-icon">▣</span><div class="info"><h3>${esc(d.name)}</h3></div><button data-revoke="${esc(d.id)}" class="danger">解除配对</button></div>`).join('')}</div>`:'<div class="empty"><div class="empty-symbol">▣</div><h2>暂无已配对设备</h2><p>点击“连接手机”开始配对。</p></div>';document.querySelectorAll('[data-revoke]').forEach(b=>b.onclick=()=>confirmAction('解除配对？','该手机将断开连接，再次使用需重新扫码。','解除配对',()=>action('revoke',{id:b.dataset.revoke})));}
async function chooseEngine(){
 if(pending)return;
 try{
  const path=await invoke('choose_engine');if(!path)return;
  const apply=async()=>{pending=true;notice('正在校验引擎…');const button=$('#engine-directory');if(button)button.disabled=true;try{state=await rpc('engine-directory',{path});lastRender='';notice('');render();}catch(e){notice(String(e));}finally{pending=false;if($('#engine-directory'))$('#engine-directory').disabled=false;}};
  if(state.projects.some(p=>p.state==='running'||p.busy))confirmAction('切换引擎？','引擎变化时将重新准备并启动游戏，手机会暂时断开。','应用',apply);else await apply();
 }catch(e){notice(String(e));}
}
async function cloudDialog(){
 const c=await rpc('cloud-status');
 if(!c.enabled){
  dialog('<h2>公网连接</h2><p>使用现有 CodexCamp 账号</p><form id="cloud-form"><label>服务器<input id="cloud-server" type="url" value="https://120.48.110.91" required></label><label>账号<input id="cloud-user" autocomplete="username" required></label><label>密码<input id="cloud-password" type="password" autocomplete="current-password" required></label><p id="cloud-message" role="status"></p><div class="actions"><button type="button" data-close>取消</button><button class="primary" id="cloud-submit">登录</button></div></form>');
  $('#cloud-form').onsubmit=async e=>{e.preventDefault();$('#cloud-submit').disabled=true;try{await rpc('cloud-login',{server:$('#cloud-server').value,username:$('#cloud-user').value,password:$('#cloud-password').value});$('#cloud-password').value='';await cloudDialog();}catch(e){$('#cloud-message').textContent=String(e);$('#cloud-password').value='';$('#cloud-submit').disabled=false;}};
 }else{
  dialog(`<h2>公网连接</h2><p>${c.state==='online'?'已连接':'正在重连'}</p><div class="actions"><button id="cloud-pair" class="primary">扫码绑定手机</button><button id="cloud-invite">旧版邀请</button><button id="cloud-off">关闭公网</button><button data-close>关闭</button></div><textarea id="cloud-invitation" readonly hidden aria-label="手机邀请"></textarea><p id="cloud-message" role="status"></p><div id="cloud-devices"></div>`);
  $('#cloud-pair').onclick=pair;
  $('#cloud-invite').onclick=async()=>{try{const v=await rpc('cloud-invite');$('#cloud-invitation').hidden=false;$('#cloud-invitation').value=JSON.stringify(v);$('#cloud-invitation').select();try{await navigator.clipboard.writeText(JSON.stringify(v));$('#cloud-message').textContent='已复制，在手机“添加电脑 → 公网连接”中登录并粘贴。';}catch{$('#cloud-message').textContent='复制邀请，在手机公网连接中粘贴。';}}catch(e){$('#cloud-message').textContent=String(e);}};
  $('#cloud-off').onclick=async()=>{try{await rpc('cloud-logout');await cloudDialog();}catch(e){$('#cloud-message').textContent=String(e);}};
  for(const d of c.grants||[]){const b=document.createElement('button');b.textContent=(d.name||'手机')+' · 移除';b.onclick=async()=>{try{await rpc('cloud-revoke',{id:d.id});b.remove();}catch(e){$('#cloud-message').textContent=String(e);}};$('#cloud-devices').append(b);}
 }
}
function renderSettings(){
 const expanded=!!$('#connection-details')?.open;
 $('#content').innerHTML=`<div class="settings"><div class="box"><h2>Defold 引擎</h2><div class="engine-choice"><code id="engine-path" title="${esc(state.engine?.directory)}">${esc(state.engine?.directory||'尚未配置')}</code><button id="engine-directory" ${pending?'disabled':''}>选择目录</button></div>${state.engine?.error?`<p class="error-box" role="alert">${esc(state.engine.error)}</p>`:state.engine?.nativeCompatible===false?'<p class="error-box">缺少匹配的原生扩展 SDK。</p>':''}</div><div class="box"><h2>运行</h2><div class="row"><div>同时运行上限</div><select id="limit" aria-label="同时运行上限">${Array.from({length:8},(_,i)=>`<option value="${i+1}" ${state.settings.maxSessions===i+1?'selected':''}>${i+1} 个游戏</option>`).join('')}</select></div><div class="row"><label for="prepare">资源变化后自动准备</label><input id="prepare" type="checkbox" aria-label="资源变化后自动准备" ${state.settings.prepare?'checked':''}></div></div><div class="box"><div class="row"><h2>公网连接</h2><button id="cloud-settings">${state.cloud?.enabled?'管理连接':'登录账号'}</button></div></div><details id="connection-details" class="box"><summary>连接与版本</summary><dl class="kv"><dt>连接 IP</dt><dd><select id="connection-address" aria-label="连接地址">${addressOptions(state.connectionAddress)}</select></dd><dt>平台</dt><dd>${esc(state.platform)}</dd><dt>版本</dt><dd>${esc(state.runtime)}</dd></dl></details><div class="actions"><button id="quit" class="quiet danger">退出 Remote</button></div></div>`;
 $('#cloud-settings').onclick=()=>cloudDialog().catch(e=>notice(String(e)));$('#connection-details').open=expanded;$('#engine-directory').onclick=chooseEngine;$('#connection-address').onchange=e=>action('connection-address',{address:e.target.value});$('#limit').onchange=e=>action('settings',{maxSessions:Number(e.target.value)});$('#prepare').onchange=e=>action('settings',{prepare:e.target.checked});$('#quit').onclick=()=>confirmAction('退出 Remote？','将停止所有游戏并断开手机，项目与配对记录保留。','退出',()=>invoke('quit'));
}
async function refreshThumbnail(){
 if(thumbnailBusy||uiHidden()||$('#dialog').open||!['projects','running'].includes(page)||Date.now()-thumbAt<2500)return;
 const project=state?.projects.find(p=>p.id===selected),image=$('#thumbnail'),key=previewKey(project);
 if(!image||project?.state!=='running')return;
 thumbnailBusy=true;thumbAt=Date.now();
 try{const result=await rpc('thumbnail',{id:project.id});const current=state?.projects.find(p=>p.id===selected);
  if(!uiHidden()&&!$('#dialog').open&&previewKey(current)===key&&current?.state==='running'&&$('#thumbnail')===image&&result.image&&image.getAttribute('src')!==result.image)image.src=result.image;
 }catch(e){/* A preview failure must not hide project or build status. */}finally{thumbnailBusy=false;}
}
function scheduleRefresh(){
 clearTimeout(refreshTimer);refreshTimer=null;if(uiHidden())return;
 const active=state?.projects.some(p=>p.state==='running'||p.busy);
 refreshTimer=setTimeout(()=>refresh(),active?1500:5000);
}
async function refresh(force=false){
 if(state&&uiHidden()){clearTimeout(refreshTimer);refreshTimer=null;return;}
 if(polling){refreshRequested=refreshRequested||force;return;}
 polling=true;clearTimeout(refreshTimer);refreshTimer=null;
 try{state=await rpc('status');if(refreshError&&$('#notice').textContent===refreshError)notice('');refreshError='';render();void refreshThumbnail();}catch(e){refreshError=String(e);notice(refreshError);}finally{
  polling=false;if(refreshRequested){refreshRequested=false;void refresh(true);}else scheduleRefresh();
 }
}
document.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>{page=b.dataset.page;render();refreshThumbnail();});$('#add').onclick=add;$('#pair').onclick=pair;$('#hide').onclick=()=>invoke('hide');$('#dialog').addEventListener('click',e=>{if(e.target===$('#dialog'))$('#dialog').close();});
$('#dialog').addEventListener('close',()=>{++dialogRevision;clearInterval(pairTimer);pairTimer=null;refresh(true);});window.addEventListener('resize',sizeQr);
function visibilityChanged(){if(uiHidden()){clearTimeout(refreshTimer);refreshTimer=null;}else refresh(true);}
document.addEventListener('visibilitychange',visibilityChanged);
window.addEventListener('remote-window-visibility',event=>{const visible=event.detail===true;if(nativeVisible!==visible){nativeVisible=visible;visibilityChanged();}});
refresh();
