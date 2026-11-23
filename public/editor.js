/* ProMultiCam Editor (Pro) — timeline cut/split/remove, auto cut suggestions, speed, slow zoom,
   animated text, voice-over, voice clean-up presets, music that ducks under speech, output formats,
   cover image, save as new or replace. Everything renders through one function, so the preview is
   exactly what gets exported. Uses helpers from index.html (t, $, toast, ask, Saver, savedBlob, …). */
(function(){
const H=`
<div class="eds" id="edScreen">
  <div class="topbar">
    <button class="icon-btn" aria-label="Close" onclick="edClose()"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M15 6l-6 6 6 6"/></svg></button>
    <div class="t"><span class="k" data-i18n="edK"></span><span class="n" id="edName"></span></div>
    <button class="btn btn-sm btn-gold" style="margin-left:auto" onclick="edExportAsk()" data-i18n="edExport"></button>
  </div>
  <div class="ed-body">
    <div class="ed-stage" id="edStage"><canvas id="edCv"></canvas></div>
    <div class="se-ctl">
      <button class="btn btn-sm btn-gold" id="edPlay" onclick="edToggle()" style="min-width:72px"></button>
      <span class="hint" id="edTime" style="flex:1"></span>
      <button class="btn btn-sm" onclick="edUndo()" data-i18n="edUndo"></button>
    </div>
    <div class="ed-tl" id="edTl"><div class="ed-thumbs" id="edThumbs"></div><div id="edSegs"></div><div class="ed-ph" id="edPh"></div></div>
    <span class="hint" data-i18n="edTlHint"></span>
    <div class="ed-tabs" id="edTabs"></div>
    <div class="ed-panel" id="edPanel"></div>
    <div class="se-prog" id="edProg"><div><i id="edBar"></i></div><span id="edProgTx"></span></div>
  </div>
</div>
<video id="edVid" playsinline preload="auto" style="display:none"></video>`;
const CSS=`
.eds{position:fixed;inset:0;z-index:100;background:var(--bg);display:none;flex-direction:column}
.eds.show{display:flex}
.ed-body{flex:1;overflow-y:auto;padding:10px 14px 24px;display:flex;flex-direction:column;gap:10px}
.ed-body>*{flex-shrink:0}
.ed-stage{flex:0 0 auto;height:40vh;display:flex;align-items:center;justify-content:center;background:#000;border-radius:14px;overflow:hidden}
.ed-stage canvas{max-width:100%;max-height:100%;display:block}
.ed-tl{position:relative;height:64px;border-radius:10px;overflow:hidden;background:#0b0f17;touch-action:none;cursor:pointer}
.ed-thumbs{position:absolute;inset:0;display:flex}.ed-thumbs img{flex:1;min-width:0;height:100%;object-fit:cover;opacity:.75}
.ed-seg{position:absolute;top:0;bottom:0;border-left:2px solid #d9b44a}
.ed-seg.off{background:repeating-linear-gradient(135deg,rgba(214,69,58,.55) 0 6px,rgba(0,0,0,.55) 6px 12px)}
.ed-seg.sug{outline:2px dashed #ff6b5b;outline-offset:-2px}
.ed-seg .sp{position:absolute;left:3px;top:3px;font-size:10px;font-weight:800;background:#d9b44a;color:#1b1503;border-radius:4px;padding:0 4px}
.ed-ph{position:absolute;top:0;bottom:0;width:3px;background:#fff;box-shadow:0 0 4px #000;pointer-events:none}
.ed-tabs{display:flex;gap:6px;overflow-x:auto;padding-bottom:2px}
.ed-tabs button{flex:0 0 auto;min-height:40px;border-radius:99px;border:1px solid var(--line);background:var(--card2);color:var(--text);font:inherit;font-size:13px;font-weight:700;padding:0 14px;cursor:pointer}
.ed-tabs button.on{background:var(--gold);color:var(--on-gold);border-color:var(--gold)}
.ed-panel{display:flex;flex-direction:column;gap:10px}
.ed-row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.ed-card{display:flex;flex-direction:column;gap:8px;background:var(--card2);border-radius:12px;padding:10px}
.ed-card .ed-row .input{flex:1;min-width:110px}
`;
let E=null; /* the open project */
const st=document.createElement('style');st.textContent=CSS;document.head.appendChild(st);
document.body.insertAdjacentHTML('beforeend',H);
const V=()=>document.getElementById('edVid'),CV=()=>document.getElementById('edCv'),G=id=>document.getElementById(id);
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
const fm=x=>{x=Math.max(0,x||0);const m=Math.floor(x/60),s=Math.floor(x%60);return m+':'+String(s).padStart(2,'0');};
const fm1=x=>{x=Math.max(0,x||0);const m=Math.floor(x/60),s=x-m*60;return m+':'+(s<10?'0':'')+s.toFixed(1);};

/* ---------- project ---------- */
function blank(dur){return{v:1,segs:[{a:0,b:dur,keep:true,speed:1,zoom:false}],texts:[],voices:[],origVol:100,voiceVol:100,enh:'none',music:'none',musVol:35,duck:true,fmt:'orig',fill:'blur',sugs:[]};}
function key(){return 'pmc_ed_'+E.name;}
function save(){try{const p=Object.assign({},E.p);localStorage.setItem(key(),JSON.stringify(p));}catch(e){}}
function snap(){E.hist.push(JSON.stringify(E.p));if(E.hist.length>40)E.hist.shift();}
window.edUndo=function(){if(!E||!E.hist.length){toast(t('edNoUndo'));return;}E.p=JSON.parse(E.hist.pop());save();renderAll();};
async function editsDir(){const r=await navigator.storage.getDirectory();return r.getDirectoryHandle('edits',{create:true});}
async function voiceDir(){const d=await editsDir();return d.getDirectoryHandle(E.name.replace(/[^\w.-]+/g,'_'),{create:true});}

window.openEditor=async function(it){
  if(!isPro()){openPro();return;}
  closeSheet('filesSheet');
  const blob=await savedBlob(it.h,mimeOf(it.name));
  E={name:it.name,h:it.h,blob,url:URL.createObjectURL(blob),hist:[],tab:'cut',raf:0,playing:false,voiceEls:{},sel:null};
  const v=V();v.src=E.url;v.muted=false;
  await new Promise((res,rej)=>{v.onloadedmetadata=res;v.onerror=rej;}).catch(()=>{});
  if(!isFinite(v.duration)){v.currentTime=1e9;await new Promise(r=>v.ondurationchange=r);v.currentTime=0;}
  E.dur=v.duration||0;
  let p=null;try{p=JSON.parse(localStorage.getItem(key())||'null');}catch(e){}
  E.p=p&&p.segs&&p.segs.length?Object.assign(blank(E.dur),p):blank(E.dur);
  G('edName').textContent=it.name;G('edScreen').classList.add('show');
  setupAudio();E.g.mus.gain.value=1;duckLvl=1;sizeCanvas();renderAll();thumbs();loadVoices();
  v.currentTime=firstKept();drawAt();
};
window.edClose=function(){if(E&&E.busy){toast(t('seBusy'));return;}edPause();stopVoiceRec();if(E&&E.g)E.g.mus.gain.value=0;G('edScreen').classList.remove('show');if(E){save();URL.revokeObjectURL(E.url);Object.values(E.voiceEls).forEach(a=>{try{a.pause();URL.revokeObjectURL(a.src);}catch(e){}});}E=null;};

/* ---------- segments (all times are in the original video's time) ---------- */
function segAt(x){return E.p.segs.find(s=>x>=s.a&&x<s.b)||E.p.segs[E.p.segs.length-1];}
function firstKept(){const s=E.p.segs.find(s=>s.keep);return s?s.a:0;}
function nextKeptFrom(x){for(const s of E.p.segs){if(s.keep&&s.b>x+0.02)return Math.max(s.a,x);}return null;}
function keptDur(){return E.p.segs.filter(s=>s.keep).reduce((a,s)=>a+(s.b-s.a)/(s.speed||1),0);}
function outTime(x){let o=0;for(const s of E.p.segs){if(!s.keep)continue;if(x>=s.b)o+=(s.b-s.a)/s.speed;else if(x>s.a){o+=(x-s.a)/s.speed;break;}}return o;}
function split(x){const s=segAt(x);if(x-s.a<0.15||s.b-x<0.15)return false;const n=Object.assign({},s,{a:x});s.b=x;E.p.segs.splice(E.p.segs.indexOf(s)+1,0,n);return true;}
function merge(){const o=[];for(const s of E.p.segs){const l=o[o.length-1];if(l&&!l.keep&&!s.keep&&Math.abs(l.b-s.a)<1e-3)l.b=s.b;else o.push(Object.assign({},s));}E.p.segs=o;}
function removeRange(a,b){split(a);split(b);E.p.segs.forEach(s=>{if(s.a>=a-1e-3&&s.b<=b+1e-3)s.keep=false;});merge();}

/* ---------- audio graph: original sound + voice-overs -> clean-up -> out; music ducks under speech ---------- */
function setupAudio(){
  ensureAudio();const A=actx;
  if(!E.g){
    const g={};g.speech=A.createGain();g.hpf=A.createBiquadFilter();g.hpf.type='highpass';g.hpf.frequency.value=80;
    g.low=A.createBiquadFilter();g.low.type='lowshelf';g.low.frequency.value=180;
    g.mud=A.createBiquadFilter();g.mud.type='peaking';g.mud.frequency.value=320;g.mud.Q.value=1;
    g.pres=A.createBiquadFilter();g.pres.type='peaking';g.pres.frequency.value=3500;g.pres.Q.value=0.9;
    g.air=A.createBiquadFilter();g.air.type='highshelf';g.air.frequency.value=9000;
    g.comp=A.createDynamicsCompressor();g.lim=A.createDynamicsCompressor();g.lim.threshold.value=-3;g.lim.ratio.value=20;g.lim.attack.value=0.002;g.lim.release.value=0.1;
    g.makeup=A.createGain();
    g.speech.connect(g.hpf);g.hpf.connect(g.low);g.low.connect(g.mud);g.mud.connect(g.pres);g.pres.connect(g.air);g.air.connect(g.comp);g.comp.connect(g.makeup);g.makeup.connect(g.lim);
    g.out=A.createGain();g.lim.connect(g.out);g.out.connect(A.destination);
    g.an=A.createAnalyser();g.an.fftSize=1024;g.lim.connect(g.an);g.buf=new Float32Array(1024);
    g.orig=A.createGain();g.orig.connect(g.speech);g.voice=A.createGain();g.voice.connect(g.speech);
    g.mus=A.createGain();musicGain.connect(g.mus);g.mus.connect(g.out);
    window._edG=g;
  }
  E.g=window._edG;
  const v=V();if(!v._src){v._src=actx.createMediaElementSource(v);v._src.connect(E.g.orig);}
  applyEnh();
}
const ENH={none:{hpf:40,low:0,mud:0,pres:0,air:0,thr:-50,ratio:1,mk:1},
  clear:{hpf:90,low:-2,mud:-3,pres:4,air:2,thr:-26,ratio:3.5,mk:1.6},
  warm:{hpf:70,low:3,mud:-1.5,pres:1.5,air:-1,thr:-24,ratio:3,mk:1.5},
  loud:{hpf:110,low:-3,mud:-4,pres:6,air:3,thr:-30,ratio:5,mk:2.1}};
function applyEnh(){
  const g=E.g,c=ENH[E.p.enh]||ENH.none;g.hpf.frequency.value=c.hpf;g.low.gain.value=c.low;g.mud.gain.value=c.mud;g.pres.gain.value=c.pres;g.air.gain.value=c.air;
  g.comp.threshold.value=c.thr;g.comp.ratio.value=c.ratio;g.comp.knee.value=12;g.comp.attack.value=0.004;g.comp.release.value=0.25;g.makeup.gain.value=c.mk;
  g.orig.gain.value=E.p.origVol/100;g.voice.gain.value=E.p.voiceVol/100;
  musicGain.gain.value=E.p.musVol/100*0.8;
}
let duckLvl=1;
function duckTick(){
  if(!E||!E.g)return;const g=E.g;g.an.getFloatTimeDomainData(g.buf);let e=0;for(let i=0;i<g.buf.length;i++)e+=g.buf[i]*g.buf[i];e=Math.sqrt(e/g.buf.length);
  const want=E.p.duck&&e>0.03?0.28:1;duckLvl+=(want-duckLvl)*(want<duckLvl?0.35:0.05);g.mus.gain.value=duckLvl;
}

/* ---------- drawing (preview = export) ---------- */
function outSize(){
  const v=V(),vw=v.videoWidth||720,vh=v.videoHeight||1280,f=E.p.fmt;
  const base=Math.min(1080,Math.min(vw,vh));
  if(f==='p')return[base,Math.round(base*16/9/2)*2];
  if(f==='l')return[Math.round(base*16/9/2)*2,base];
  if(f==='sq')return[base,base];
  const k=Math.max(vw,vh)>1920?1920/Math.max(vw,vh):1;return[Math.round(vw*k/2)*2,Math.round(vh*k/2)*2];
}
function sizeCanvas(){const[w,h]=outSize(),c=CV();if(c.width!==w||c.height!==h){c.width=w;c.height=h;}}
function drawVideo(c,W,H,x){
  const v=V(),vw=v.videoWidth,vh=v.videoHeight;if(!vw)return;
  const s=segAt(x);let z=1;if(s&&s.zoom){const p=clamp((x-s.a)/Math.max(0.1,s.b-s.a),0,1);z=1+0.18*p;}
  const sameShape=Math.abs(vw/vh-W/H)<0.02;
  if(!sameShape&&E.p.fill==='blur'){c.save();c.filter='blur(28px) brightness(0.55)';const k=Math.max(W/vw,H/vh)*1.1;c.drawImage(v,(W-vw*k)/2,(H-vh*k)/2,vw*k,vh*k);c.restore();}
  else if(!sameShape){c.fillStyle='#000';c.fillRect(0,0,W,H);}
  const k=(sameShape?Math.max(W/vw,H/vh):Math.min(W/vw,H/vh))*z,dw=vw*k,dh=vh*k;
  c.drawImage(v,(W-dw)/2,(H-dh)/2,dw,dh);
}
function drawText(c,W,H,tx,x){
  const S=Math.min(W,H),f=OV_FONTS[tx.font]||OV_FONTS.montserrat,fs=Math.round(S*(tx.size||7)/100),age=x-tx.s,left=tx.e-x;
  let alpha=1,sc=1,dx=0,txt=f.caps?tx.txt.toUpperCase():tx.txt;
  if(tx.anim==='fade')alpha=clamp(Math.min(age,left)/0.35,0,1);
  if(tx.anim==='pop'){const p=clamp(age/0.28,0,1);sc=0.55+0.45*(1-Math.pow(1-p,3))+(p<1?Math.sin(p*Math.PI)*0.08:0);alpha=clamp(left/0.25,0,1);}
  if(tx.anim==='slide'){const p=clamp(age/0.35,0,1);dx=(1-(1-Math.pow(1-p,3)))*-W*0.6;alpha=clamp(left/0.25,0,1);}
  if(tx.anim==='type'){const n=Math.floor(clamp(age/Math.min(1.6,txt.length*0.06),0,1)*txt.length);txt=txt.slice(0,n);}
  if(!txt)return;
  c.save();c.globalAlpha=alpha;c.font=`${f.w} ${fs}px ${f.fam},"Bricolage Grotesque",sans-serif`;c.textAlign='center';
  const maxW=W*0.86,lines=[];let cur='';txt.split(/\s+/).forEach(w=>{const tt=cur?cur+' '+w:w;if(c.measureText(tt).width>maxW&&cur){lines.push(cur);cur=w;}else cur=tt;});if(cur)lines.push(cur);
  const lh=fs*1.25,tot=lines.length*lh,cy=tx.pos==='t'?H*0.12:tx.pos==='b'?H*0.86-tot:H/2-tot/2;
  c.translate(W/2+dx,cy+tot/2);c.scale(sc,sc);let y=-tot/2;
  const col=(OV_COLORS[tx.col]||{c:'#ffffff'}).c;
  lines.forEach(l=>{const w=c.measureText(l).width;
    if(tx.box){c.fillStyle=col;c.beginPath();if(c.roundRect)c.roundRect(-w/2-fs*0.4,y,w+fs*0.8,lh,fs*0.25);else c.rect(-w/2-fs*0.4,y,w+fs*0.8,lh);c.fill();c.fillStyle=(OV_COLORS[tx.col]||{ink:'#111'}).ink;}
    else{c.lineWidth=Math.max(2,fs*0.16);c.strokeStyle='rgba(0,0,0,.85)';c.lineJoin='round';c.strokeText(l,0,y+fs*0.98);c.fillStyle=col;}
    c.fillText(l,0,y+fs*0.98);y+=lh;});
  c.restore();
}
function drawAt(){
  if(!E)return;const c=CV(),x=V().currentTime,ctx2=c.getContext('2d'),W=c.width,Hh=c.height;
  ctx2.fillStyle='#000';ctx2.fillRect(0,0,W,Hh);drawVideo(ctx2,W,Hh,x);
  E.p.texts.forEach(tx=>{if(x>=tx.s&&x<tx.e&&tx.txt.trim())drawText(ctx2,W,Hh,tx,x);});
  G('edTime').textContent=fm1(outTime(x))+' / '+fm1(keptDur());
  const ph=G('edPh');if(ph&&E.dur)ph.style.left=(x/E.dur*100)+'%';
  const sg=segAt(x);if(E.tab==='cut'&&sg&&E.lastSeg!==sg.a+'|'+sg.b+'|'+sg.keep){E.lastSeg=sg.a+'|'+sg.b+'|'+sg.keep;if(!E.busy)renderPanel();}
}

/* ---------- playback that skips removed parts ---------- */
function setRate(){const s=segAt(V().currentTime);if(s)V().playbackRate=s.speed||1;}
function tick(){
  if(!E)return;const v=V();const s=segAt(v.currentTime);
  if(s&&!s.keep){const n=nextKeptFrom(s.b);if(n===null){edPause();if(E.onEnd)E.onEnd();return;}v.currentTime=n;}
  setRate();voiceSync();duckTick();drawAt();
  if(E.playing)E.raf=requestAnimationFrame(tick);
}
window.edToggle=function(){if(E.playing)edPause();else edPlay();};
function edPlay(fromStart){
  const v=V();if(fromStart)v.currentTime=firstKept();
  const n=nextKeptFrom(v.currentTime);if(n===null)v.currentTime=firstKept();else v.currentTime=n;
  if(actx.state==='suspended')actx.resume();
  E.playing=true;G('edPlay').textContent=t('tpPause');v.play().catch(()=>{});
  if(E.p.music!=='none'){const keep=$('musicSel').value;$('musicSel').value=E.p.music;startMusic(false);$('musicSel').value=keep;}
  cancelAnimationFrame(E.raf);E.raf=requestAnimationFrame(tick);
}
function edPause(){if(!E)return;E.playing=false;const v=V();v.pause();stopMusic();Object.values(E.voiceEls).forEach(a=>a.pause());G('edPlay').textContent=t('sePlay');drawAt();}
window.edPause=edPause;
V().addEventListener('ended',()=>{if(E&&E.playing){edPause();if(E.onEnd)E.onEnd();}});
V().addEventListener('seeked',()=>{if(E&&!E.playing)drawAt();});
function voiceSync(){
  const x=V().currentTime;
  E.p.voices.forEach(vc=>{const a=E.voiceEls[vc.id];if(!a)return;const inside=x>=vc.t&&x<vc.t+vc.d;
    if(inside&&E.playing){if(a.paused){a.currentTime=clamp(x-vc.t,0,vc.d);a.play().catch(()=>{});}}else if(!a.paused)a.pause();});
}

/* ---------- timeline ---------- */
async function thumbs(){
  const box=G('edThumbs');box.innerHTML='';const v=document.createElement('video');v.src=E.url;v.muted=true;v.playsInline=true;
  await new Promise(r=>{v.onloadeddata=r;v.onerror=r;});const n=10,c=document.createElement('canvas');c.width=96;c.height=Math.round(96*(v.videoHeight||16)/(v.videoWidth||9));
  for(let i=0;i<n;i++){if(!E)return;v.currentTime=Math.min(E.dur-0.05,(i+0.5)*E.dur/n);await new Promise(r=>{v.onseeked=r;setTimeout(r,1500);});c.getContext('2d').drawImage(v,0,0,c.width,c.height);const im=new Image();im.src=c.toDataURL('image/jpeg',0.5);box.appendChild(im);}
}
function renderTl(){
  const box=G('edSegs');box.innerHTML='';
  E.p.segs.forEach(s=>{const d=document.createElement('div');d.className='ed-seg'+(s.keep?'':' off');d.style.left=(s.a/E.dur*100)+'%';d.style.width=((s.b-s.a)/E.dur*100)+'%';
    if(s.keep&&(s.speed!==1||s.zoom)){const b=document.createElement('span');b.className='sp';b.textContent=(s.speed!==1?s.speed+'x':'')+(s.zoom?' ⤢':'');d.appendChild(b);}box.appendChild(d);});
  (E.p.sugs||[]).filter(g=>g.st==='new').forEach(g=>{const d=document.createElement('div');d.className='ed-seg sug';d.style.left=(g.a/E.dur*100)+'%';d.style.width=((g.b-g.a)/E.dur*100)+'%';d.style.borderLeft='0';box.appendChild(d);});
}
(function(){const tl=G('edTl');let down=false;
  const go=e=>{if(!E)return;const r=tl.getBoundingClientRect(),x=clamp((e.clientX-r.left)/r.width,0,1)*E.dur;V().currentTime=x;if(!E.playing)setTimeout(drawAt,60);renderPanel();};
  tl.addEventListener('pointerdown',e=>{down=true;tl.setPointerCapture(e.pointerId);go(e);});tl.addEventListener('pointermove',e=>{if(down)go(e);});tl.addEventListener('pointerup',()=>down=false);})();

/* ---------- tools ---------- */
const TABS=['cut','sug','text','voice','music','fmt','cover'];
function renderTabs(){const b=G('edTabs');b.innerHTML='';TABS.forEach(k=>{const x=document.createElement('button');x.textContent=t('edT_'+k);x.className=E.tab===k?'on':'';x.onclick=()=>{E.tab=k;renderTabs();renderPanel();};b.appendChild(x);});}
function btn(label,fn,cls){const b=document.createElement('button');b.className='btn btn-sm '+(cls||'');b.textContent=label;b.onclick=fn;return b;}
function row(...els){const d=document.createElement('div');d.className='ed-row';els.forEach(e=>e&&d.appendChild(e));return d;}
function hint(txt){const s=document.createElement('span');s.className='hint';s.textContent=txt;return s;}
function sel(opts,val,fn){const s=document.createElement('select');s.className='input';opts.forEach(([v,l])=>{const o=document.createElement('option');o.value=v;o.textContent=l;s.appendChild(o);});s.value=val;s.onchange=()=>fn(s.value);return s;}
function range(min,max,val,fn,step){const r=document.createElement('input');r.type='range';r.min=min;r.max=max;r.step=step||1;r.value=val;r.style.flex='1';r.oninput=()=>fn(Number(r.value));return r;}
function changed(){merge();save();renderTl();drawAt();}
function renderPanel(){
  const P=G('edPanel');P.innerHTML='';const x=V().currentTime,s=segAt(x),k=E.tab;
  if(k==='cut'){
    P.appendChild(row(btn(t('edSplit'),()=>{snap();if(split(V().currentTime)){changed();renderPanel();toast(t('edSplitDone'));}else toast(t('edSplitNo'));}),
      btn(s.keep?t('edRemove'):t('edRestore'),()=>{snap();const q=segAt(V().currentTime);q.keep=!q.keep;changed();renderPanel();},s.keep?'btn-danger':'btn-gold')));
    P.appendChild(row(btn(t('edTrimS'),()=>{snap();removeRange(0,V().currentTime);changed();renderPanel();}),btn(t('edTrimE'),()=>{snap();removeRange(V().currentTime,E.dur);changed();renderPanel();})));
    if(s.keep){
      P.appendChild(row(hint(t('edSpeed')),sel([['0.5','0.5x'],['0.75','0.75x'],['1','1x'],['1.5','1.5x'],['2','2x'],['3','3x']],String(s.speed),v=>{snap();segAt(V().currentTime).speed=Number(v);changed();renderPanel();})));
      const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('edZoom');const i=cb.querySelector('input');i.checked=!!s.zoom;i.onchange=()=>{snap();segAt(V().currentTime).zoom=i.checked;changed();};P.appendChild(cb);
    }
    P.appendChild(hint(t('edCutHint',{a:fm1(s.a),b:fm1(s.b)})));
  }
  if(k==='sug'){
    P.appendChild(row(btn(t('edAnalyse'),analyse,'btn-gold'),(E.p.sugs||[]).some(g=>g.st==='new')?btn(t('edAcceptAll'),()=>{snap();E.p.sugs.filter(g=>g.st==='new').forEach(g=>{removeRange(g.a,g.b);g.st='ok';});changed();renderPanel();}):null));
    P.appendChild(hint(t('edSugHint')));
    (E.p.sugs||[]).filter(g=>g.st==='new').forEach(g=>{const c=document.createElement('div');c.className='ed-card';
      c.appendChild(row(hint(t('edWhy_'+g.why)+' · '+fm1(g.a)+' – '+fm1(g.b))));
      c.appendChild(row(btn('▶',()=>{V().currentTime=Math.max(0,g.a-1);edPlay();}),btn(t('edAccept'),()=>{snap();removeRange(g.a,g.b);g.st='ok';changed();renderPanel();},'btn-danger'),btn(t('edReject'),()=>{g.st='no';save();renderTl();renderPanel();})));P.appendChild(c);});
    if(E.marks&&E.marks.length)P.appendChild(row(hint(t('edMarks')),...E.marks.map(m=>btn('📍 '+fm(m),()=>{V().currentTime=m;drawAt();}))));
  }
  if(k==='text'){
    P.appendChild(row(btn(t('edAddText'),()=>{snap();const a=V().currentTime;E.p.texts.push({id:Date.now(),s:+a.toFixed(2),e:+Math.min(E.dur,a+3).toFixed(2),txt:'',anim:'pop',pos:'m',font:'montserrat',col:'white',box:false,size:7});save();renderPanel();},'btn-gold')));
    E.p.texts.slice().sort((a,b)=>a.s-b.s).forEach(tx=>{const c=document.createElement('div');c.className='ed-card';
      const ta=document.createElement('textarea');ta.className='input';ta.rows=2;ta.maxLength=120;ta.value=tx.txt;ta.placeholder=t('edTextPh');ta.oninput=()=>{tx.txt=ta.value;save();drawAt();};c.appendChild(ta);
      c.appendChild(row(btn('▶ '+fm1(tx.s)+' – '+fm1(tx.e),()=>{V().currentTime=tx.s;drawAt();}),btn(t('seSetS'),()=>{tx.s=Math.min(V().currentTime,tx.e-0.3);save();renderPanel();}),btn(t('seSetE'),()=>{tx.e=Math.max(V().currentTime,tx.s+0.3);save();renderPanel();})));
      c.appendChild(row(sel([['none',t('edAn_none')],['fade',t('edAn_fade')],['pop',t('edAn_pop')],['type',t('edAn_type')],['slide',t('edAn_slide')]],tx.anim,v=>{tx.anim=v;save();}),
        sel([['t',t('sePosT')],['m',t('sePosM')],['b',t('sePosB')]],tx.pos,v=>{tx.pos=v;save();drawAt();})));
      c.appendChild(row(sel(Object.keys(OV_FONTS).map(f=>[f,t('font_'+f)]),tx.font,v=>{tx.font=v;save();try{document.fonts.load(`${OV_FONTS[v].w} 30px ${OV_FONTS[v].fam}`).then(drawAt);}catch(e){}drawAt();}),
        sel(Object.keys(OV_COLORS).map(cc=>[cc,t('col_'+cc)]),tx.col,v=>{tx.col=v;save();drawAt();})));
      const bx=document.createElement('label');bx.className='check';bx.innerHTML='<input type="checkbox"><span></span>';bx.querySelector('span').textContent=t('edTextBox');bx.querySelector('input').checked=!!tx.box;bx.querySelector('input').onchange=e=>{tx.box=e.target.checked;save();drawAt();};
      c.appendChild(row(bx,hint(t('tpSize')),range(4,14,tx.size||7,v=>{tx.size=v;save();drawAt();},0.5),btn('✕',()=>{snap();E.p.texts=E.p.texts.filter(q=>q!==tx);save();renderPanel();drawAt();},'btn-danger')));
      P.appendChild(c);});
  }
  if(k==='voice'){
    P.appendChild(row(hint(t('edOrigVol')),range(0,150,E.p.origVol,v=>{E.p.origVol=v;applyEnh();save();})));
    P.appendChild(row(hint(t('edEnh')),sel([['none',t('edEnh_none')],['clear',t('edEnh_clear')],['warm',t('edEnh_warm')],['loud',t('edEnh_loud')]],E.p.enh,v=>{E.p.enh=v;applyEnh();save();})));
    P.appendChild(hint(t('edEnhHint')));
    P.appendChild(row(btn(E.vrec?t('edVoStop'):t('edVoRec'),()=>{if(E.vrec)stopVoiceRec();else startVoiceRec();},E.vrec?'btn-danger':'btn-gold')));
    P.appendChild(hint(t('edVoHint')));
    P.appendChild(row(hint(t('edVoiceVol')),range(0,150,E.p.voiceVol,v=>{E.p.voiceVol=v;applyEnh();save();})));
    E.p.voices.forEach((vc,i)=>{const c=document.createElement('div');c.className='ed-card';
      c.appendChild(row(hint(t('edVoN',{n:i+1})+' · '+fm1(vc.t)+' – '+fm1(vc.t+vc.d)),btn('▶',()=>{V().currentTime=vc.t;edPlay();}),btn('✕',async()=>{snap();E.p.voices=E.p.voices.filter(q=>q!==vc);save();try{(await voiceDir()).removeEntry(vc.id);}catch(e){}renderPanel();},'btn-danger')));P.appendChild(c);});
  }
  if(k==='music'){
    P.appendChild(row(sel([['none',t('mNone')],['lofi',t('mLofi')],['ceria',t('mCeria')]],E.p.music,v=>{E.p.music=v;save();if(E.playing){edPause();edPlay();}})));
    P.appendChild(row(hint(t('mVol')),range(0,100,E.p.musVol,v=>{E.p.musVol=v;applyEnh();save();})));
    const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('edDuck');const i=cb.querySelector('input');i.checked=E.p.duck;i.onchange=()=>{E.p.duck=i.checked;save();};P.appendChild(cb);
    P.appendChild(hint(t('musicNote')));
  }
  if(k==='fmt'){
    P.appendChild(row(sel([['orig',t('edF_orig')],['p',t('edF_p')],['l',t('edF_l')],['sq',t('edF_sq')]],E.p.fmt,v=>{E.p.fmt=v;sizeCanvas();save();drawAt();})));
    P.appendChild(row(hint(t('edFill')),sel([['blur',t('edFill_blur')],['black',t('edFill_black')]],E.p.fill,v=>{E.p.fill=v;save();drawAt();})));
  }
  if(k==='cover'){
    const inp=document.createElement('input');inp.className='input';inp.maxLength=40;inp.placeholder=t('edCoverPh');inp.value=E.coverTxt||$('titleTxt').value||'';inp.oninput=()=>E.coverTxt=inp.value;
    P.appendChild(hint(t('edCoverHint')));P.appendChild(inp);P.appendChild(row(btn(t('edCoverSave'),cover,'btn-gold')));
  }
}
function renderAll(){renderTabs();renderPanel();renderTl();G('edPlay').textContent=t('sePlay');applyEnh();sizeCanvas();drawAt();}

/* ---------- auto cut suggestions ---------- */
async function analyse(){
  const prog=G('edProg');prog.classList.add('show');const say=(p,m)=>{G('edBar').style.width=Math.round(p*100)+'%';G('edProgTx').textContent=m;};
  const out=[];
  try{
    say(0.02,t('edAnAudio'));
    const buf=await actx.decodeAudioData(await E.blob.arrayBuffer());const d=buf.getChannelData(0),sr=buf.sampleRate,win=Math.round(sr*0.1),n=Math.floor(d.length/win),rms=new Float32Array(n);
    for(let i=0;i<n;i++){let e=0;for(let j=i*win;j<(i+1)*win;j++)e+=d[j]*d[j];rms[i]=Math.sqrt(e/win);}
    const sorted=Array.from(rms).sort((a,b)=>a-b),med=sorted[Math.floor(n*0.5)]||0,thr=Math.max(0.006,med*0.35);
    let st=-1;for(let i=0;i<=n;i++){const q=i<n&&rms[i]<thr;if(q&&st<0)st=i;if(!q&&st>=0){const a=st*0.1+0.25,b=i*0.1-0.25;if(b-a>=1.2)out.push({a,b,why:'quiet'});st=-1;}}
  }catch(e){console.error('an-audio',e);}
  try{
    const v=document.createElement('video');v.src=E.url;v.muted=true;v.playsInline=true;await new Promise(r=>{v.onloadeddata=r;v.onerror=r;});
    const step=Math.max(0.5,E.dur/240),c=document.createElement('canvas');c.width=64;c.height=64;const g=c.getContext('2d',{willReadFrequently:true});const bad=[];
    for(let x=0;x<E.dur;x+=step){if(!E)return;v.currentTime=x;await new Promise(r=>{v.onseeked=r;setTimeout(r,1200);});g.drawImage(v,0,0,64,64);const px=g.getImageData(0,0,64,64).data;
      let L=0,edge=0;const lum=i=>0.3*px[i]+0.59*px[i+1]+0.11*px[i+2];for(let y=0;y<64;y++)for(let xx=0;xx<64;xx++){const i=(y*64+xx)*4;const l=lum(i);L+=l;if(xx<63)edge+=Math.abs(l-lum(i+4));}
      L/=4096;edge/=4032;bad.push({x,why:L<28?'dark':edge<1.4?'blur':null});say(0.1+0.85*x/E.dur,t('edAnVideo',{n:Math.round(x/E.dur*100)}));}
    let run=null;bad.push({x:E.dur,why:null});for(const b of bad){if(b.why&&!run)run={a:b.x,why:b.why};else if((!b.why||b.why!==run?.why)&&run){if(b.x-run.a>=1)out.push({a:run.a,b:b.x,why:run.why});run=b.why?{a:b.x,why:b.why}:null;}}
  }catch(e){}
  /* a picture that is plain all the way through (a wall, a pot from above) is not a mistake: only flag short bad stretches */
  for(const why of ['dark','blur']){const tot=out.filter(g=>g.why===why).reduce((a,g)=>a+g.b-g.a,0);if(tot>E.dur*0.4)for(let i=out.length-1;i>=0;i--)if(out[i].why===why)out.splice(i,1);}
  if(E.dur>4){out.push({a:0,b:0.7,why:'start'});out.push({a:E.dur-0.7,b:E.dur,why:'end'});}
  const kept=out.filter(g=>E.p.segs.some(s=>s.keep&&s.a<g.b&&s.b>g.a)).sort((a,b)=>a.a-b.a).map(g=>Object.assign(g,{st:'new',a:+g.a.toFixed(2),b:+g.b.toFixed(2)}));
  E.p.sugs=(E.p.sugs||[]).filter(g=>g.st!=='new').concat(kept);save();prog.classList.remove('show');renderTl();renderPanel();
  toast(kept.length?t('edSugN',{n:kept.length}):t('edSugNone'),3500);
}

/* ---------- voice-over ---------- */
async function loadVoices(){
  E.voiceEls={};if(!E.p.voices.length)return;try{const dir=await voiceDir();for(const vc of E.p.voices){try{const f=await (await dir.getFileHandle(vc.id)).getFile();mkVoiceEl(vc,f);}catch(e){}}}catch(e){}
  try{const mk=[];for await(const [k,h] of E.h.entries()){if(k==='_markers.txt'){const txt=await (await h.getFile()).text();txt.split('\n').forEach(l=>{const m=l.match(/(\d+):(\d+)(?::(\d+))?/);if(m)mk.push(m[3]?(+m[1]*3600+ +m[2]*60+ +m[3]):(+m[1]*60+ +m[2]));});}}E.marks=mk;}catch(e){}
}
function mkVoiceEl(vc,blob){const a=new Audio();a.src=URL.createObjectURL(blob);a.preload='auto';try{const s=actx.createMediaElementSource(a);s.connect(E.g.voice);}catch(e){}E.voiceEls[vc.id]=a;}
async function startVoiceRec(){
  let st;try{st=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});}catch(e){toast(t('seMicNeed'),4000);return;}
  const start=V().currentTime,mr=new MediaRecorder(st),ch=[];E.vrec={mr,st,start};
  E.p.origVol0=E.p.origVol;E.g.orig.gain.value=0;
  mr.ondataavailable=e=>{if(e.data.size)ch.push(e.data);};
  mr.onstop=async()=>{st.getTracks().forEach(tr=>tr.stop());applyEnh();const end=V().currentTime,blob=new Blob(ch,{type:mr.mimeType||'audio/webm'});
    const id='vo_'+Date.now()+'.webm',vc={id,t:+start.toFixed(2),d:+Math.max(0.3,end-start).toFixed(2)};
    try{const dir=await voiceDir();const fh=await dir.getFileHandle(id,{create:true});const w=await fh.createWritable();await w.write(blob);await w.close();}catch(e){}
    snap();E.p.voices.push(vc);mkVoiceEl(vc,blob);save();renderPanel();toast(t('edVoSaved'));};
  toast(t('edVoGo'),2500);
  setTimeout(()=>{if(!E||!E.vrec)return;mr.start(1000);E.onEnd=()=>stopVoiceRec();edPlay();renderPanel();},900);
  renderPanel();
}
function stopVoiceRec(){if(!E||!E.vrec)return;const r=E.vrec;E.vrec=null;E.onEnd=null;edPause();try{r.mr.stop();}catch(e){}renderPanel();}

/* ---------- cover image ---------- */
function cover(){
  const c=document.createElement('canvas'),[w,h]=outSize();c.width=w;c.height=h;const g=c.getContext('2d');
  g.fillStyle='#000';g.fillRect(0,0,w,h);drawVideo(g,w,h,V().currentTime);
  const txt=(E.coverTxt||'').trim();
  if(txt){const S=Math.min(w,h);g.fillStyle='rgba(0,0,0,.35)';g.fillRect(0,0,w,h);drawText(g,w,h,{txt,s:-10,e:1e9,anim:'none',pos:'m',font:ovFontKey(),col:$('ovColor')?$('ovColor').value:'gold',box:true,size:9},0);
    if(logoImg&&logoImg.complete&&logoImg.naturalWidth){const b=Math.round(S*0.2),ar=logoImg.naturalWidth/logoImg.naturalHeight;g.drawImage(logoImg,w/2-b*ar/2,h*0.18,b*ar,b);}}
  c.toBlob(b=>{downloadBlob(b,E.name.replace(/\.\w+$/,'')+'_cover.jpg');toast(t('edCoverDone'));},'image/jpeg',0.92);
}

/* ---------- export ---------- */
window.edExportAsk=function(){
  if(!E||E.busy)return;if(!E.p.segs.some(s=>s.keep)){toast(t('edNothing'));return;}
  ask(t('edSaveT'),t('edSaveD'),t('edSaveNew'),()=>edExport(false));
  const box=G('askBox').querySelector('.askbtns');if(!G('askReplace')){const b=document.createElement('button');b.className='btn btn-danger';b.id='askReplace';box.parentNode.appendChild(b);}
  const r=G('askReplace');r.style.display='flex';r.textContent=t('edSaveReplace');r.onclick=()=>{askClose();ask(t('edRepT'),t('edRepD'),t('edSaveReplace'),()=>edExport(true));};
};
async function edExport(replace){
  if(E.busy)return;E.busy=true;edPause();stopVoiceRec();
  const prog=G('edProg');prog.classList.add('show');
  try{
    sizeCanvas();const c=CV(),out=c.captureStream(30),dest=actx.createMediaStreamDestination();
    E.g.out.connect(dest);dest.stream.getAudioTracks().forEach(tr=>out.addTrack(tr));
    const mime=pickMime(),ext=mime.includes('mp4')?'mp4':'webm';
    const rec=new MediaRecorder(out,mime?{mimeType:mime,videoBitsPerSecond:c.width*c.height>=1900000?8000000:5000000,audioBitsPerSecond:160000}:{});
    let base=E.name.replace(/\.\w+$/,'').replace(/_edit\d+$/,''),n=1;const root=await recRoot();
    const exists=async nm=>{try{await root.getDirectoryHandle(nm);return true;}catch(e){return false;}};
    while(await exists(`${base}_edit${n}.${ext}`))n++;const name=`${base}_edit${n}.${ext}`,sv=new Saver(name);
    rec.ondataavailable=e=>sv.push(e.data);
    const total=keptDur();let doneT=0;
    const kept=E.p.segs.filter(s=>s.keep);const v=V();
    rec.start(2000);rec.pause();
    for(const s of kept){
      v.currentTime=s.a;await new Promise(r=>{v.onseeked=r;setTimeout(r,2000);});drawAt();
      rec.resume();E.playing=true;v.playbackRate=s.speed;await v.play().catch(()=>{});
      if(E.p.music!=='none'&&!mTimer){const keep=$('musicSel').value;$('musicSel').value=E.p.music;startMusic(false);$('musicSel').value=keep;}
      await new Promise(res=>{const step=()=>{if(!E)return res();voiceSync();duckTick();drawAt();
        const p=(doneT+(v.currentTime-s.a)/s.speed)/total;G('edBar').style.width=Math.round(p*100)+'%';G('edProgTx').textContent=t('seMaking',{n:Math.round(p*100)});
        if(v.currentTime>=s.b-0.03||v.ended)return res();if(v.requestVideoFrameCallback)v.requestVideoFrameCallback(step);else requestAnimationFrame(step);};step();});
      v.pause();Object.values(E.voiceEls).forEach(a=>a.pause());rec.pause();doneT+=(s.b-s.a)/s.speed;
    }
    E.playing=false;stopMusic();rec.stop();await new Promise(r=>rec.onstop=r);try{E.g.out.disconnect(dest);}catch(e){}
    const r=await sv.finish(rec.mimeType||mime||'video/webm');
    if(replace){try{await root.removeEntry(E.name,{recursive:true});localStorage.removeItem(key());}catch(e){}}
    G('edProgTx').textContent=t('seDone');G('edBar').style.width='100%';toast(t('saved',{w:r.where}),5000);
    E.busy=false;listSaved('files');if(replace){edClose();}
  }catch(e){console.error('export',e);toast(t('seFail'),4000);if(E)E.busy=false;}
  if(E)E.playing=false;
}
window.addEventListener('pmc-lang',()=>{if(E)renderAll();});
})();
