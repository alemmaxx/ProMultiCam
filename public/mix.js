/* ProMultiCam — "Video & Photos": put videos and photos from the gallery together into one 9:16 video,
   with a slow move on each photo and a change effect between parts. The result opens in the editor. */
(function(){
const $=id=>document.getElementById(id);
const MX={items:[],trs:[],play:null,busy:false};
const KINDS=['fade','black','white','slide','slideup','zoom','whip','wipe','circle','doors'];
const tl=k=>{const o=document.querySelector('#transSel option[value="'+k+'"]');return o?o.textContent:k;};
const MOVES=['zin','zout','pan','none'];

document.body.insertAdjacentHTML('beforeend',`<div class="askbox" id="mixBox"><div class="askcard mx-card">
  <b id="mxT"></b>
  <div id="mxPrevBox" style="display:none;flex-direction:column;gap:6px">
    <canvas id="mxCv" width="360" height="640" style="width:100%;max-height:34vh;object-fit:contain;background:#000;border-radius:12px"></canvas>
    <div id="mxSeek"></div>
    <div style="display:flex;gap:6px;align-items:center"><button class="btn btn-sm" type="button" id="mxStop"></button><span id="mxTx" class="hint"></span></div>
  </div>
  <label class="btn btn-gold" style="cursor:pointer"><span id="mxAddL"></span><input type="file" id="mxIn" accept="image/*,video/*" multiple style="display:none"></label>
  <span class="hint" id="mxHint"></span>
  <div id="mxList" style="display:flex;flex-direction:column;gap:6px"></div>
  <div id="mxTr" style="display:flex;flex-direction:column;gap:6px"></div>
  <div class="se-prog" id="mxProg"><div><i id="mxBar"></i></div><span id="mxProgTx"></span></div>
  <div class="askbtns"><button class="btn" type="button" id="mxCancel"></button><button class="btn btn-gold" type="button" id="mxGo"></button></div>
</div></div>`);
document.head.insertAdjacentHTML('beforeend',`<style>
.mx-card{max-height:88vh;overflow-y:auto;overflow-x:hidden}.mx-card *{max-width:100%;box-sizing:border-box}
.mx-row{display:flex;align-items:center;gap:6px;background:var(--card2);border:1px solid var(--line);border-radius:10px;padding:5px 6px;touch-action:none}
.mx-row.drag{position:relative;z-index:3;border-color:var(--gold);box-shadow:0 6px 18px rgba(0,0,0,.5)}.mx-row.drop{border-color:var(--gold);border-style:dashed}
.mx-h{font-size:18px;width:30px;height:40px;display:grid;place-items:center;color:var(--sub);cursor:grab;touch-action:none;flex:0 0 auto}
.mx-th{width:40px;height:52px;border-radius:7px;background:#222 center/cover no-repeat;flex:0 0 auto;display:grid;place-items:center;font-size:14px}
.mx-mid{flex:1;min-width:0;display:flex;flex-direction:column;gap:4px}.mx-mid b{font-size:12px;color:var(--gold)}
.mx-mid select{min-height:32px;padding:2px 6px;font-size:12px}.mx-sel{display:flex;gap:4px}.mx-sel select{flex:1;min-width:0}.mx-sel select:first-child{flex:0 0 64px}
.mx-b{min-width:34px!important;padding:0!important}
#mxSeek{position:relative;height:30px;border-radius:8px;background:#141416;touch-action:none;cursor:pointer;overflow:hidden}
#mxSeek .s{position:absolute;top:4px;bottom:4px;border-radius:6px;background:rgba(200,164,92,.22);border:1px solid rgba(200,164,92,.45);font:800 10px var(--f);color:var(--gold);display:flex;align-items:center;padding-left:5px;overflow:hidden;pointer-events:none}
#mxSeek .s:nth-child(even){background:rgba(127,179,255,.2);border-color:rgba(127,179,255,.45);color:#9cc4ff}
#mxSeek .ph{position:absolute;top:0;bottom:0;width:3px;margin-left:-1px;background:#fff;box-shadow:0 0 4px #000;pointer-events:none}
</style>`);

const W=720,H=1280;
function txt(){$('mxT').textContent=t('mxT');$('mxAddL').textContent='＋ '+t('mxAdd');$('mxHint').textContent=t('mxHint');$('mxCancel').textContent=t('cancel');
  $('mxGo').textContent=t('mxGo',{n:MX.items.length});$('mxStop').textContent=MX.play&&!MX.play.ended?'■ '+t('jpStopL'):'▶ '+t('jpAgain');}

window.openMix=function(){
  if(!isPro()){openPro();return;}
  try{closeSheet('filesSheet');}catch(e){}
  stopPrev();MX.items.forEach(freeItem);MX.items=[];MX.trs=[];$('mxProg').classList.remove('show');$('mxPrevBox').style.display='none';
  paint();txt();$('mixBox').classList.add('show');
};
function closeMix(){if(MX.busy){toast(t('seBusy'));return;}stopPrev();MX.items.forEach(freeItem);MX.items=[];$('mixBox').classList.remove('show');try{openSheet('filesSheet');}catch(e){}}
$('mxCancel').onclick=closeMix;
$('mixBox').addEventListener('click',e=>{if(e.target.id==='mixBox')closeMix();});
function freeItem(it){try{if(it.el&&it.el.pause){it.el.pause();it.el.removeAttribute('src');it.el.load&&it.el.load();}}catch(e){}try{URL.revokeObjectURL(it.url);}catch(e){}}

/* ---------- adding files ---------- */
$('mxIn').onchange=async e=>{const fs=[...e.target.files];e.target.value='';for(const f of fs){const it=await loadFile(f);if(it)MX.items.push(it);}paint();auto();};
async function loadFile(f){const url=URL.createObjectURL(f);
  if(/^image\//.test(f.type)){const im=new Image();im.src=url;try{await im.decode();}catch(e){URL.revokeObjectURL(url);return null;}return {type:'img',name:f.name,url,el:im,dur:3,move:MOVES[MX.items.length%3]};}
  if(/^video\//.test(f.type)){const v=document.createElement('video');v.src=url;v.playsInline=true;v.preload='auto';await new Promise(r=>{v.onloadedmetadata=r;v.onerror=r;setTimeout(r,8000);});
    if(!v.videoWidth){URL.revokeObjectURL(url);toast(t('mxBadVid'),4000);return null;}
    try{v.currentTime=Math.min(0.5,(v.duration||1)/2);await new Promise(r=>{v.onseeked=r;setTimeout(r,1500);});}catch(e){}
    let th='';try{const c=document.createElement('canvas');c.width=80;c.height=Math.round(80*v.videoHeight/v.videoWidth);c.getContext('2d').drawImage(v,0,0,c.width,c.height);th=c.toDataURL('image/jpeg',.6);}catch(e){}
    return {type:'vid',name:f.name,url,el:v,dur:isFinite(v.duration)?v.duration:0,th};}
  URL.revokeObjectURL(url);return null;}

/* ---------- list: order, photo time and movement, remove ---------- */
function paint(){const L=$('mxList');L.innerHTML='';
  MX.items.forEach((it,i)=>{const r=document.createElement('div');r.className='mx-row';
    r.innerHTML='<span class="mx-h">☰</span><span class="mx-th"></span><div class="mx-mid"><b></b></div><button type="button" class="btn btn-sm mx-b">▲</button><button type="button" class="btn btn-sm mx-b">▼</button><button type="button" class="btn btn-sm btn-danger mx-b">✕</button>';
    const th=r.querySelector('.mx-th');th.style.backgroundImage='url('+(it.type==='img'?it.url:it.th||'')+')';if(it.type==='vid'&&!it.th)th.textContent='🎬';
    r.querySelector('b').textContent=(i+1)+'. '+(it.type==='img'?'🖼 '+t('mxPhoto'):'🎬 '+t('mxVideo')+' · '+Math.round(it.dur)+'s');
    if(it.type==='img'){const box=document.createElement('div');box.className='mx-sel';
      const d=document.createElement('select');d.className='input';[2,3,4,5,7,10].forEach(n=>{const o=document.createElement('option');o.value=n;o.textContent=n+' '+t('mxSec');d.appendChild(o);});d.value=it.dur;d.onchange=()=>{it.dur=+d.value;auto();};
      const m=document.createElement('select');m.className='input';MOVES.forEach(k=>{const o=document.createElement('option');o.value=k;o.textContent=t('mxMv_'+k);m.appendChild(o);});m.value=it.move;m.onchange=()=>{it.move=m.value;auto();};
      box.appendChild(d);box.appendChild(m);r.querySelector('.mx-mid').appendChild(box);}
    const [up,dn,x]=r.querySelectorAll('button');up.disabled=i===0;dn.disabled=i===MX.items.length-1;
    up.onclick=()=>{[MX.items[i-1],MX.items[i]]=[MX.items[i],MX.items[i-1]];paint();auto();};
    dn.onclick=()=>{[MX.items[i+1],MX.items[i]]=[MX.items[i],MX.items[i+1]];paint();auto();};
    x.onclick=()=>{freeItem(it);MX.items.splice(i,1);paint();auto();};
    r.querySelector('.mx-h').addEventListener('pointerdown',e=>{e.preventDefault();const rows=[...L.querySelectorAll('.mx-row')],rh=r.getBoundingClientRect().height+6,y0=e.clientY;let to=i;r.classList.add('drag');try{navigator.vibrate&&navigator.vibrate(8);}catch(er){}
      const mv=ev=>{const dy=ev.clientY-y0;r.style.transform='translateY('+dy+'px)';to=Math.max(0,Math.min(MX.items.length-1,i+Math.round(dy/rh)));rows.forEach((q,k)=>q.classList.toggle('drop',k===to&&k!==i));};
      const up2=()=>{window.removeEventListener('pointermove',mv);window.removeEventListener('pointerup',up2);window.removeEventListener('pointercancel',up2);if(to!==i){const [m2]=MX.items.splice(i,1);MX.items.splice(to,0,m2);auto();}paint();};
      window.addEventListener('pointermove',mv);window.addEventListener('pointerup',up2);window.addEventListener('pointercancel',up2);});
    L.appendChild(r);});
  paintTr();txt();}
function paintTr(){const T=$('mxTr');T.innerHTML='';const n=MX.items.length-1;if(n<1)return;while(MX.trs.length<n)MX.trs.push(KINDS[MX.trs.length%KINDS.length]);MX.trs.length=n;
  const h=document.createElement('b');h.textContent=t('joinTrT');T.appendChild(h);
  for(let i=0;i<n;i++){const r=document.createElement('label');r.style.cssText='display:flex;align-items:center;gap:8px;font-size:13px;font-weight:700';const sp=document.createElement('span');sp.textContent=(i+1)+' → '+(i+2);sp.style.minWidth='52px';
    const se=document.createElement('select');se.className='input';se.style.cssText='flex:1;min-width:0;min-height:36px';[['cut',t('joinTrCut')]].concat(KINDS.map(k=>[k,tl(k)])).forEach(([v,l])=>{const o=document.createElement('option');o.value=v;o.textContent=l;se.appendChild(o);});
    se.value=MX.trs[i];se.onchange=()=>{MX.trs[i]=se.value;auto();};r.appendChild(sp);r.appendChild(se);T.appendChild(r);}
  const mix=document.createElement('button');mix.type='button';mix.className='btn btn-sm';mix.textContent='🎲 '+t('joinTrMix');mix.onclick=()=>{const p=KINDS.slice().sort(()=>Math.random()-0.5);for(let i=0;i<n;i++)MX.trs[i]=p[i%p.length];paintTr();auto();};T.appendChild(mix);}

/* ---------- drawing one moment ---------- */
const lens=()=>MX.items.map(it=>Math.max(0.5,it.type==='img'?it.dur:it.dur||0));
const small=document.createElement('canvas');small.width=24;small.height=42;
function drawItem(g,it,p,Wd,Hd){ /* p = 0..1 through this part (for the photo movement) */
  const el=it.el,ew=it.type==='img'?el.naturalWidth:el.videoWidth,eh=it.type==='img'?el.naturalHeight:el.videoHeight;if(!ew||!eh)return;
  g.fillStyle='#000';g.fillRect(0,0,Wd,Hd);
  const far=Math.abs(ew/eh-Wd/Hd)>0.08;
  if(far){/* other shape: soft blurred copy behind, the full picture on top */
    const s=small.getContext('2d');s.drawImage(el,0,0,small.width,small.height);g.imageSmoothingQuality='high';g.drawImage(small,0,0,Wd,Hd);g.fillStyle='rgba(0,0,0,.35)';g.fillRect(0,0,Wd,Hd);}
  let k=far?Math.min(Wd/ew,Hd/eh):Math.max(Wd/ew,Hd/eh),dx=0;
  if(it.type==='img'){const e=p*p*(3-2*p);if(it.move==='zin')k*=1+0.12*e;else if(it.move==='zout')k*=1.12-0.12*e;else if(it.move==='pan'){k*=1.1;dx=(e-0.5)*Wd*0.08;}}
  const w=ew*k,h=eh*k;g.drawImage(el,(Wd-w)/2+dx,(Hd-h)/2,w,h);}

/* ---------- the player: used for the preview and for making the video ---------- */
async function runSeq(o){ /* o:{g,Wd,Hd,startAt,stop:()=>bool,onFrame(gt),audio:(el)=>void} */
  const L=lens(),tot=L.reduce((a,b)=>a+b,0);const snap=document.createElement('canvas');snap.width=o.Wd;snap.height=o.Hd;const fr=document.createElement('canvas');fr.width=o.Wd;fr.height=o.Hd;const fg=fr.getContext('2d');
  let i=0,acc=0,off=0;if(o.startAt>0){while(i<L.length-1&&o.startAt>=acc+L[i]){acc+=L[i];i++;}off=o.startAt-acc;}
  let jumped=o.startAt>0;
  for(;i<MX.items.length;i++){if(o.stop())return;const it=MX.items[i],len=L[i],kd=!jumped&&i>0&&MX.trs[i-1]&&MX.trs[i-1]!=='cut'?MX.trs[i-1]:null,tms=kd?Math.min(900,Math.max(500,(TRANS[kd]?TRANS[kd].ms:400)*1.8)):0;
    const start=off;off=0;jumped=false;
    if(it.type==='vid'){const v=it.el;v.currentTime=start;await new Promise(r=>{v.onseeked=r;setTimeout(r,700);});if(o.audio)o.audio(v);await v.play().catch(()=>{});}
    const t0=performance.now()-start*1000;
    await new Promise(res=>{const step=()=>{if(o.stop()){res();return;}const el=(performance.now()-t0)/1000;
        const p=Math.min(1,el/len),lt=it.type==='vid'?it.el.currentTime:el;
        if(it.type==='vid'&&(it.el.ended||it.el.currentTime>=len-0.03)){res();return;}if(it.type==='img'&&el>=len){res();return;}
        const tr=(el*1000)<tms?(el*1000)/tms:1;
        if(tr<1){drawItem(fg,it,p,o.Wd,o.Hd);o.g.fillStyle='#000';o.g.fillRect(0,0,o.Wd,o.Hd);drawTrans(kd,tr,(x,y,w,h)=>o.g.drawImage(snap,x,y,w,h),(x,y,w,h)=>o.g.drawImage(fr,x,y,w,h),0,0,o.Wd,o.Hd,o.g);}
        else drawItem(o.g,it,p,o.Wd,o.Hd);
        o.onFrame&&o.onFrame(acc+lt,tot);
        if(it.type==='vid'&&it.el.requestVideoFrameCallback)it.el.requestVideoFrameCallback(step);else requestAnimationFrame(step);};step();});
    if(it.type==='vid')it.el.pause();snap.getContext('2d').drawImage(o.g.canvas,0,0);acc+=len;}
  return tot;}

/* ---------- preview (small, starts by itself) ---------- */
let autoT=0;function auto(){clearTimeout(autoT);if(MX.items.length<1){stopPrev();$('mxPrevBox').style.display='none';return;}autoT=setTimeout(()=>prev(0),500);}
function stopPrev(){if(MX.play){MX.play.stop=true;}MX.items.forEach(it=>{if(it.type==='vid')try{it.el.pause();}catch(e){}});}
function bar(){const B=$('mxSeek');B.innerHTML='';const L=lens(),tot=L.reduce((a,b)=>a+b,0)||1;let a=0;
  L.forEach((d,i)=>{const s=document.createElement('div');s.className='s';s.style.left=(a/tot*100)+'%';s.style.width=(d/tot*100)+'%';s.textContent=(i+1)+(MX.items[i].type==='img'?'🖼':'🎬');B.appendChild(s);a+=d;});
  const ph=document.createElement('div');ph.className='ph';ph.id='mxPh';B.appendChild(ph);B._tot=tot;}
async function prev(at){stopPrev();const me=MX.play={stop:false,ended:false};$('mxPrevBox').style.display='flex';bar();txt();
  const c=$('mxCv'),g=c.getContext('2d');MX.items.forEach(it=>{if(it.type==='vid')it.el.muted=false;});
  await runSeq({g,Wd:c.width,Hd:c.height,startAt:at||0,stop:()=>me.stop,onFrame:(gt,tot)=>{const ph=$('mxPh');if(ph)ph.style.left=Math.min(100,gt/tot*100)+'%';$('mxTx').textContent=Math.floor(gt)+'s / '+Math.round(tot)+'s';}});
  if(MX.play===me){me.ended=true;txt();}}
$('mxStop').onclick=()=>{if(MX.play&&!MX.play.ended&&!MX.play.stop){stopPrev();MX.play.ended=true;txt();}else prev(0);};
(function(){const B=$('mxSeek');let down=false;const at=e=>{const r=B.getBoundingClientRect();return Math.max(0,Math.min(1,(e.clientX-r.left)/r.width));};
  B.addEventListener('pointerdown',e=>{down=true;e.preventDefault();try{B.setPointerCapture(e.pointerId);}catch(er){}const ph=$('mxPh');if(ph)ph.style.left=(at(e)*100)+'%';});
  B.addEventListener('pointermove',e=>{if(!down)return;const ph=$('mxPh');if(ph)ph.style.left=(at(e)*100)+'%';});
  B.addEventListener('pointerup',e=>{if(!down)return;down=false;prev(at(e)*(B._tot||1));});})();

/* ---------- make the video ---------- */
$('mxGo').onclick=async()=>{
  if(MX.busy)return;if(!MX.items.length){toast(t('mxNeed'));return;}stopPrev();MX.busy=true;const go=$('mxGo');go.disabled=true;$('mxProg').classList.add('show');
  try{ensureAudio();if(actx.state==='suspended')await actx.resume();
    const c=document.createElement('canvas');c.width=W;c.height=H;const g=c.getContext('2d',{alpha:false});g.fillStyle='#000';g.fillRect(0,0,W,H);
    const dest=actx.createMediaStreamDestination();const silent=actx.createConstantSource?actx.createConstantSource():null;if(silent){const z=actx.createGain();z.gain.value=0;silent.connect(z);z.connect(dest);silent.start();}
    const out=c.captureStream(30);dest.stream.getAudioTracks().forEach(tr=>out.addTrack(tr));
    const mime=pickMime(),ext=mime&&mime.includes('mp4')?'mp4':'webm',rec=new MediaRecorder(out,mime?{mimeType:mime,videoBitsPerSecond:6000000,audioBitsPerSecond:160000}:{});
    const name=seqName(t('mxFile'))+'.'+ext,sv=new Saver(name);sv.noDl=true;rec.ondataavailable=e=>sv.push(e.data);
    MX.items.forEach(it=>{if(it.type==='vid'){it.el.muted=false;if(!it.node){try{it.node=actx.createMediaElementSource(it.el);}catch(e){}}if(it.node){try{it.node.disconnect();}catch(e){}it.node.connect(dest);}}});
    rec.start(2000);const stopFlag={v:false};
    await runSeq({g,Wd:W,Hd:H,startAt:0,stop:()=>stopFlag.v,onFrame:(gt,tot)=>{const p=gt/tot;$('mxBar').style.width=Math.round(p*100)+'%';$('mxProgTx').textContent=t('seMaking',{n:Math.round(p*100)});}});
    await new Promise(r=>setTimeout(r,200));rec.stop();await new Promise(r=>rec.onstop=r);if(silent)try{silent.stop();}catch(e){}
    await sv.finish(rec.mimeType||mime||'video/webm');
    MX.items.forEach(it=>{if(it.node)try{it.node.disconnect();}catch(e){}});
    $('mxProgTx').textContent=t('seDone');MX.busy=false;go.disabled=false;MX.items.forEach(freeItem);MX.items=[];$('mixBox').classList.remove('show');
    toast(t('mxDone'),4000);listSaved('files');
    /* straight into the editor for text, music and brand */
    try{const root=await recRoot();const h=await root.getDirectoryHandle(name);openEditor({name,h,done:true});}catch(e){openSheet('filesSheet');}
  }catch(e){console.error('mix',e);toast(t('seFail'),4000);MX.busy=false;go.disabled=false;}
};
window.addEventListener('pmc-lang',()=>{if($('mixBox').classList.contains('show')){paint();}});
})();
