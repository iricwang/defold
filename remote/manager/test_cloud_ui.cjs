const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../cloud/remote.js'),'utf8');
function harness(bridge={}){
 class Element{constructor(){this.children=[];this.hidden=false;this.textContent='';this.dataset={};this.attributes={};this.value='';this.open=false;this.classList={toggle(){}};}append(...v){this.children.push(...v);}replaceChildren(...v){this.children=v;}setAttribute(k,v){this.attributes[k]=v;}showModal(){this.open=true;}close(){this.open=false;}}
 const ids=new Map(),node=s=>{if(!ids.has(s))ids.set(s,new Element());return ids.get(s);};
 const html=fs.readFileSync(path.join(__dirname,'../cloud/index.html'),'utf8');
 const tabs=Array.from(html.matchAll(/data-page="([^"]+)"/g),m=>m[1]).map(page=>{let e=new Element();e.dataset.page=page;return e;});
 const timers=[],calls=[],releases=[];let stored=null;
 const document={visibilityState:'visible',querySelector:node,querySelectorAll:s=>s==='[data-page]'?tabs:[],createElement:()=>new Element()};
 const ctx=vm.createContext({document,window:{LivekitClient:{RoomEvent:{}},DefoldRemote:{close(){releases.push('close');},...bridge}},localStorage:{getItem:()=>stored,setItem:(k,v)=>stored=v,removeItem:()=>stored=null},console,URL,AbortSignal,TextEncoder,crypto:{randomUUID:()=> 'test-request'},performance:{now:()=>0},setTimeout(fn,ms){timers.push({fn,ms});return timers.length;},clearTimeout(){},setInterval(){},addEventListener(){},fetch:async()=>{throw Error('unexpected network');}});
 vm.runInContext(source,ctx);const run=s=>vm.runInContext(s,ctx);
 const setup=()=>run(`saved={server:'https://fixture.invalid',token:'test',grants:{desktop:{host:'desktop'}}};host='desktop';hosts=[{id:'desktop',name:'Mac',online:true},{id:'offline',name:'PC',online:false}];access={hostIdentity:'desktop',lease:'lease'};room={state:'connected'};generation=1;show();`);
 function rpc(handler){ctx.handler=handler;run('rpc=async(action,params)=>handler(action,params)');}
 return {run,node,tabs,timers,calls,releases,setup,rpc,ctx};
}
async function main(){const passed=[];function done(s){passed.push(s);}
 {const h=harness();assert.equal(h.node('#connection-dialog').open,false);h.node('#add-computer').onclick();assert.equal(h.node('#connection-dialog').open,true);assert.equal(h.node('#login').hidden,false);done('plus opens login as a dialog, not a separate page');}
 {const h=harness();h.setup();h.node('#add-computer').onclick();assert.equal(h.node('#authorization').hidden,false);assert.equal(h.node('#login').hidden,true);done('same plus opens authorization when signed in');}
 {const h=harness();h.setup();let data=Array.from({length:73},(_,i)=>({id:String(i),name:'Game '+i,state:i===0?'running':'ready'}));let calls=0;
 h.rpc(async(action,{cursor})=>{assert.equal(action,'games');calls++;let start=Number(cursor||0);return {games:data.slice(start,start+20),nextCursor:start+20<data.length?String(start+20):''};});
 await h.run('refreshGames()');assert.equal(h.run('games.length'),73);assert.equal(calls,4);assert.equal(h.node('#games').children.length,73);done('all pages populate the visible game list');
 let row=h.node('#games').children[0];await h.run('refreshGames()');assert.equal(h.node('#games').children[0],row);done('unchanged refresh preserves rows and focus');
 data=data.slice(1).concat({id:'new',name:'New game',state:'ready'});await h.run('refreshGames()');assert.equal(h.run('games.some(p=>p.id==="new")'),true);assert.equal(h.run('games.some(p=>p.id==="0")'),false);done('added and removed games are reflected without reconnecting');
 h.node('#search').oninput({target:{value:'New game'}});assert.equal(h.node('#games').children.length,1);done('search filters the shared catalog');
 assert.deepEqual(h.tabs.map(t=>t.dataset.page),['games','settings']);assert.equal(h.node('#host-name').textContent,'Mac');assert.equal(h.node('#host-status').textContent,'已连接');done('two tabs share a connected computer header');
 h.run('selectPage("settings")');assert.equal(h.node('#settings').hidden,false);assert.equal(h.node('#games').hidden,true);
 h.node('#choose-computer').onclick();assert.equal(h.node('#computer-dialog').open,true);assert.equal(h.node('#computer-options').children.length,2);done('header opens computer picker from settings');
 }
 {let awake=true;const h=harness({keepAwake:()=>awake,setKeepAwake:value=>{awake=value;}});
  const toggle=h.node('#awake-setting');assert.equal(toggle.attributes['aria-checked'],'true');
  toggle.onclick();assert.equal(awake,false);assert.equal(toggle.attributes['aria-checked'],'false');
  done('server settings read and update the Android screen-awake preference');
 }
 {const other={host:'other',name:'Other PC',grant:'test'};let records=[other];
  const h=harness({switchingComputer:()=>true,pairedComputers:()=>JSON.stringify(records)});
  assert.equal(h.run('host'),'');assert.match(h.node('#games').children[0].children[0].textContent,/没有服务器连接/);assert.equal(h.node('#message').textContent,'');
  h.ctx.connected=[];h.run('connect=async id=>connected.push(id)');
  records=[other,{host:'target',name:'Target PC',grant:'target-grant',selected:true}];
  h.run('window.remotePairingsChanged()');assert.deepEqual(h.ctx.connected,['target']);
  done('switch waits for the matching computer grant and skips unrelated computers');
 }
 {const h=harness();h.setup();let resolve;h.rpc(()=>new Promise(r=>resolve=r));const pending=h.run('refreshGames()');h.run('host="other";generation++;games=[]');resolve({games:[{id:'old'}],nextCursor:''});await pending;assert.equal(h.run('games.length'),0);done('late reply cannot populate another computer');}
 {const h=harness();h.setup();h.rpc(async()=>({games:[],nextCursor:'same'}));await assert.rejects(h.run('refreshGames()'),/分页异常/);done('repeated pagination cursor fails instead of looping');}
 {const h=harness();h.setup();h.ctx.release=()=>h.releases.push('release');h.run('window.remotePause=async()=>release()');await h.node('#lan-mode').onclick();assert.deepEqual(h.releases,['release','close']);done('LAN switch releases remote session before changing modes');}
 {const h=harness();h.setup();h.run("access.room='fixture';api=async path=>path==='defold/viewer/token'?{room:'fixture',lease:'lease',hostIdentity:'desktop'}:{hosts:[{id:'desktop',name:'Mac',online:true}]}");let reads=0;h.rpc(async()=>{reads++;return {games:[{id:'added',name:'Just added',state:'ready'}],nextCursor:''};});await h.run('renew(false,generation)');assert.equal(reads,1);assert.equal(h.run('games[0].id'),'added');assert.equal(h.timers.at(-1).ms,8000);done('lease heartbeat refreshes shared games while browsing');h.run('game="playing"');await h.run('renew(false,generation)');assert.equal(reads,1);done('active preview renews lease without catalog polling');}
 {const h=harness();h.setup();let request,reply;h.ctx.testPeer={performRpc:options=>{request=options;return new Promise(resolve=>reply=resolve);}};h.run('room.localParticipant=testPeer');const pending=h.run('rpc("games")');assert.ok(request.responseTimeout>=12000,'RPC deadline must allow WAN latency in milliseconds');reply(JSON.stringify({ok:true,result:{games:[{id:'wan-game'}]}}));assert.equal((await pending).games[0].id,'wan-game');done('actual RPC adapter passes a millisecond WAN response deadline');}
 {const h=harness();h.setup();h.run('device={width:1080,height:2400,device_revision:3};game="game"');const calls=[],acks=[];h.rpc((action,p)=>{calls.push({...p});return new Promise(resolve=>acks.push(resolve));});h.ctx.moves=[];h.run('room.localParticipant={publishData:async(...args)=>moves.push(args)}');
 const down=h.run('input(true,{x:540,y:1200})');await Promise.resolve();const up=h.run('input(false,{x:540,y:1200})');await h.run('input(true,{x:600,y:1200},true)');assert.equal(calls.length,1);assert.equal(h.ctx.moves.length,0);acks.shift()({ok:true});await down;await Promise.resolve();assert.equal(calls.length,2);assert.deepEqual(calls.map(p=>p.down),[true,false]);assert.deepEqual(calls.map(p=>p.sequence),[1,2]);acks.shift()({ok:true});await up;done('slow WAN preserves press then release and blocks overtaking motion');
 }
 {const h=harness();h.setup();h.run('device={width:1080,height:2400,device_revision:3}');let doneDown;const sent=[];h.rpc((action,p)=>{sent.push(p);return new Promise(r=>doneDown=r);});const down=h.run('input(true,{x:1,y:1})');await Promise.resolve();const up=h.run('input(false,{x:1,y:1})');h.run('device={width:2400,height:1080,device_revision:4}');doneDown({ok:true});await down;await up;assert.equal(sent.length,1);done('rotation discards queued events for the previous device revision');}
 {const h=harness();h.setup();h.run('device={width:1080,height:2400,device_revision:3}');h.node('#video').getBoundingClientRect=()=>({left:20,top:10,width:360,height:900});let p=h.run('point({clientX:200,clientY:460})');assert.equal(p.x,540);assert.equal(p.y,1200);done('letterboxed screen coordinates map to physical game pixels');}
 {let args;const h=harness({chooseQuality:(...a)=>args=a});
  h.node('#quality-setting').onclick();assert.equal(args[0],'720p');
  await h.ctx.window.remoteQualityPicked(args[1],'540p');assert.equal(h.node('#quality-value').textContent,'540p');
  h.node('#quality-preview').onclick();h.run('previewRevision++');await h.ctx.window.remoteQualityPicked(args[1],'1080p');
  assert.equal(h.node('#quality-value').textContent,'540p');done('both quality entries use the Android picker and ignore a stale selection');
 }
 {const h=harness();assert.equal(h.run('streamQuality'),'720p');await h.run('setQuality("1080p")');assert.equal(h.node('#quality-value').textContent,'1080p');assert.equal(h.node('#quality-preview').textContent,'1080p ▾');assert.equal(h.run('localStorage.getItem("defold-cloud-quality")'),'1080p');done('quality preference is shared by settings and game controls and saved');}
 {const h=harness();h.setup();h.run('game="game";device={width:1080,height:2400,device_revision:8};window.remoteMetricsChanged=()=>{}');const requests=[];h.rpc(async(action,p)=>{requests.push({action,...p});return {ok:true};});await h.run('setQuality("1080p")');assert.deepEqual(requests,[{action:'stream',resolution:'1080p'}]);assert.equal(h.run('device.device_revision'),8);assert.equal(h.run('device.width'),1080);done('live quality changes stream only and preserves game dimensions and input revision');}
 {const h=harness();h.setup();h.run('game="game";device={width:1080,height:2400,device_revision:8};window.remoteMetricsChanged=()=>{}');h.rpc(async()=>{throw Error('network unavailable');});await assert.rejects(h.run('setQuality("1080p")'),/network unavailable/);assert.equal(h.run('streamQuality'),'720p');assert.equal(h.node('#quality-preview').disabled,false);assert.equal(h.node('#quality-preview').textContent,'720p ▾');done('failed quality change restores previous selection and allows retry');}
 {const h=harness();await assert.rejects(h.run('setQuality("original")'),/不支持/);h.node('#video').videoWidth=864;h.node('#video').videoHeight=1920;h.node('#video').onresize();assert.equal(h.node('#stream-dimensions').textContent,'864 × 1920');done('controls display actual decoded dimensions and reject unsupported presets');}
 {const h=harness();h.setup();
  h.run("paired.set('desktop',{host:'desktop',server:'https://paired.invalid',grant:'paired.test',deviceToken:'scoped',name:'PC'});");
  let request;h.ctx.fetch=async(url,options)=>{request={url,options};return {ok:true,json:async()=>({lease:'lease'})};};
  await h.run("viewerApi('defold/viewer/token','desktop')");
  assert.equal(request.url,'https://paired.invalid/v1/defold/viewer/token');
  assert.equal(request.options.headers.Authorization,undefined);
  assert.deepEqual(JSON.parse(request.options.body),{host:'desktop',grant:'paired.test',deviceToken:'scoped'});
  h.run("saved=null;hosts=mergeHosts();show()");
  assert.equal(h.run("authorized('desktop')"),true);assert.equal(h.run("hosts.length"),1);
  done('scanned computer opens without phone login and receives only its scoped credential');
 }
 {const immersive=[];const h=harness({setPreviewing:value=>immersive.push(value)});h.setup();let started;const calls=[];
  h.rpc((action)=>{calls.push(action);if(action==='start')return new Promise(resolve=>started=resolve);return {games:[]};});
  const opening=h.run("openGame('pending')");assert.equal(h.node('#first-frame').hidden,false);assert.equal(h.node('#panel').hidden,true);
  h.run('window.remoteBack()');assert.equal(h.run('game'),'');started({state:'running'});await opening;
  assert.deepEqual(immersive,[true,false]);done('Android enters fullscreen for preview and restores system bars on return');
  assert.ok(!calls.includes('claim')&&!calls.includes('release'));done('leave during startup returns immediately and ignores a late start');
 }
 {const h=harness();h.setup();h.run("game='playing';hasControl=true;menuOpen=true;show()");
  h.rpc(async(action)=>{if(action==='stop')throw Error('no connection');return {games:[]};});
  await h.run('stopPreview()');assert.equal(h.run('game'),'playing');assert.equal(h.node('#message').textContent,'no connection');
  h.rpc(async()=>({games:[]}));await h.run('stopPreview()');assert.equal(h.run('game'),'');assert.equal(h.node('#catalog').hidden,false);
  done('stop waits for confirmation, reports failure, and returns to projects on success');
 }
 return passed;
}
module.exports=main;
if(require.main===module)main().then(p=>console.log(JSON.stringify({passed:p.length,checks:p},null,2))).catch(e=>{console.error(e);process.exitCode=1;});
