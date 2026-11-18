'use strict';
(() => {
 const area=document.querySelector('#orb-area'),ball=document.querySelector('#preview-menu'),panel=document.querySelector('#panel');
 const bridge=window.DefoldRemote,stored=bridge?.orbPosition?.()||localStorage.getItem('preview-orb')||'right,0.18';
 const [edge,height]=stored.split(',');
 let right=edge!=='left',fraction=Number(height),gesture=null,suppressClick=false;
 const clamp=(value,max)=>Math.max(0,Math.min(value,max));
 fraction=Number.isFinite(fraction)?clamp(fraction,1):0.18;

 function travel(){
  return {x:Math.max(0,area.clientWidth-ball.offsetWidth),y:Math.max(0,area.clientHeight-ball.offsetHeight)};
 }
 function move(x,y){ball.style.transform='translate('+x+'px,'+y+'px)';}
 function layout(){
  if(ball.hidden||gesture?.dragging)return;
  const t=travel(),x=right?t.x:0,y=t.y*fraction;
  move(x,y);
  if(panel.hidden)return;
  const a=area.getBoundingClientRect();
  panel.style.setProperty('--menu-space',a.width+'px');
  const below=y+ball.offsetHeight+2,h=panel.offsetHeight;
  panel.style.setProperty('--menu-x',(right?a.right-panel.offsetWidth:a.left)+'px');
  panel.style.setProperty('--menu-y',(a.top+clamp(below+h<=a.height?below:y-h-2,a.height-h))+'px');
 }
 ball.addEventListener('pointerdown',e=>{
  if(!e.isPrimary||e.button!==0)return;
  const r=ball.getBoundingClientRect(),a=area.getBoundingClientRect();
  gesture={id:e.pointerId,startX:e.clientX,startY:e.clientY,x:r.left-a.left,y:r.top-a.top,dragging:false};
  suppressClick=false;ball.setPointerCapture(e.pointerId);
 });
 ball.addEventListener('pointermove',e=>{
  if(gesture?.id!==e.pointerId)return;
  const dx=e.clientX-gesture.startX,dy=e.clientY-gesture.startY;
  if(!gesture.dragging){
   if(Math.hypot(dx,dy)<8)return;
   gesture.dragging=true;suppressClick=true;showMenu(false);
  }
  const t=travel();
  move(clamp(gesture.x+dx,t.x),clamp(gesture.y+dy,t.y));
 });
 ball.addEventListener('pointerup',e=>{
  if(gesture?.id!==e.pointerId)return;
  if(gesture.dragging){
   const t=travel();
   right=gesture.x+e.clientX-gesture.startX>=t.x/2;
   if(t.y>0)fraction=clamp((gesture.y+e.clientY-gesture.startY)/t.y,1);
   if(bridge?.saveOrbPosition)bridge.saveOrbPosition(right,fraction);
   else localStorage.setItem('preview-orb',(right?'right':'left')+','+fraction);
  }
  gesture=null;layout();
 });
 ball.addEventListener('lostpointercapture',()=>{if(gesture){gesture=null;suppressClick=true;layout();}});
 ball.addEventListener('click',e=>{
  if(suppressClick&&e.detail!==0){e.stopImmediatePropagation();e.preventDefault();}
  suppressClick=false;
 },true);
 const sizes=new ResizeObserver(layout);
 sizes.observe(area);sizes.observe(panel);
 window.remotePositionOrb=layout;
 layout();
})();
