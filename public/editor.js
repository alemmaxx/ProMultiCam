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
    <button class="btn btn-sm btn-gold" onclick="edExportAsk()" data-i18n="edExport"></button>
  </div>
  <div class="ed-top">
    <div class="ed-stage" id="edStage"><canvas id="edCv"></canvas></div>
    <div class="se-ctl">
      <button class="btn btn-sm btn-gold" id="edPlay" onclick="edToggle()" style="min-width:72px"></button>
      <span class="hint" id="edTime" style="flex:1"></span>
      <button class="btn btn-sm" onclick="edUndo()" data-i18n="edUndo"></button>
    </div>
    <div class="ed-tl" id="edTl"><div class="ed-thumbs" id="edThumbs"></div><div id="edSegs"></div><div class="ed-ph" id="edPh"></div><div class="ed-rng" id="edRng"></div></div>
    <div class="se-prog" id="edProg"><div><i id="edBar"></i></div><span id="edProgTx"></span></div>
  </div>
  <div class="ed-body">
    <div class="ed-phead"><div class="ed-sub" id="edSub"></div><button class="ed-x" type="button" onclick="edPanelClose()" aria-label="Close"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg></button></div>
    <span class="hint" data-i18n="edTlHint"></span>
    <div class="ed-panel" id="edPanel"></div>
  </div>
  <div class="ed-bar" id="edTabs"></div>
</div>
<video id="edVid" playsinline preload="auto" style="display:none"></video>`;
const CSS=`

.eds:not(.pop) .ed-body{display:none!important}
.ed-phead{display:flex;align-items:center;gap:8px;position:sticky;top:-10px;z-index:2;background:var(--bg);margin:-10px -14px 0;padding:8px 14px 6px}
.ed-phead .ed-sub{flex:1;min-width:0}
.ed-sub button{flex:0 0 auto;min-height:32px;border-radius:99px;border:1px solid var(--line);background:var(--card2);color:var(--text);font:inherit;font-size:12px;font-weight:700;padding:0 13px;cursor:pointer}
.ed-sub button.on{background:var(--gold);color:var(--on-gold);border-color:var(--gold)}
.ed-sub b{font-size:15px;font-weight:800;padding:4px 2px}
.ed-x{flex:0 0 auto;width:40px;height:36px;border-radius:10px;border:1px solid var(--line);background:var(--card2);color:var(--ink);display:grid;place-items:center;cursor:pointer}
.ed-bar{flex:0 0 auto;display:flex;gap:2px;padding:6px 6px calc(6px + env(safe-area-inset-bottom));border-top:1px solid var(--line);background:#0d0d0e}
.ed-bar button{flex:1 1 0;min-width:0;display:flex;flex-direction:column;align-items:center;gap:4px;padding:7px 2px 6px;border:0;border-radius:12px;background:none;color:var(--sub);font:700 11.5px var(--f);cursor:pointer;-webkit-tap-highlight-color:transparent}
.ed-bar button svg{width:24px;height:24px}
.ed-bar button.on{color:var(--gold);background:rgba(200,164,92,.13)}
.ed-bar button:active{transform:translateY(1px)}
.eds{position:fixed;inset:0;z-index:100;background:var(--bg);display:none;flex-direction:column}
.eds.show{display:flex}
.ed-top{flex:1 1 auto;min-height:0;padding:8px 14px 8px;display:flex;flex-direction:column;gap:8px;border-bottom:1px solid var(--line);background:var(--bg)}
.ed-top>*{flex-shrink:0}.ed-top>.ed-stage{flex:1 1 auto;min-height:150px;height:auto}
.ed-body{flex:0 0 auto;max-height:46vh;border-top:1px solid var(--line);overflow-y:auto;-webkit-overflow-scrolling:touch;overscroll-behavior:contain;padding:10px 14px 24px;display:flex;flex-direction:column;gap:10px}
.eds .topbar .t{flex:1;min-width:0}.eds .topbar .n{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.eds .topbar .btn,.se-ctl .btn{flex-shrink:0;white-space:nowrap}
.ed-body>*{flex-shrink:0}
.ed-stage{flex:0 0 auto;height:34vh;display:flex;align-items:center;justify-content:center;background:#000;border-radius:14px;overflow:hidden}
.ed-stage canvas{max-width:100%;max-height:100%;display:block}
.ed-tl{position:relative;height:64px;border-radius:10px;overflow:hidden;background:#0e0e0f;touch-action:none;cursor:pointer}
.ed-thumbs{position:absolute;inset:0;display:flex}.ed-thumbs img{flex:1;min-width:0;height:100%;object-fit:cover;opacity:.75}
.ed-seg{position:absolute;top:0;bottom:0;border-left:2px solid #c8a45c}
.ed-seg.off{background:repeating-linear-gradient(135deg,rgba(214,69,58,.55) 0 6px,rgba(0,0,0,.55) 6px 12px)}
.ed-seg.sug{outline:2px dashed #ff6b5b;outline-offset:-2px}
.ed-seg .sp{position:absolute;left:3px;top:3px;font-size:10px;font-weight:800;background:#c8a45c;color:#17130b;border-radius:4px;padding:0 4px}
.ed-rng{position:absolute;inset:0;pointer-events:none;display:none}.ed-rng.on{display:block}
.rng-body{position:absolute;top:4px;bottom:4px;background:rgba(200,164,92,.28);border:2px solid var(--gold);border-radius:8px;pointer-events:auto;touch-action:none;cursor:grab}
.rng-h{position:absolute;top:-2px;bottom:-2px;width:26px;margin-left:-13px;pointer-events:auto;touch-action:none;cursor:ew-resize;display:flex;align-items:center;justify-content:center}
.rng-h::before{content:'';width:12px;height:70%;border-radius:6px;background:var(--gold);box-shadow:0 0 0 2px #111}
.rng-lb{position:absolute;top:-1px;transform:translateX(-50%);font-size:10px;font-weight:800;background:var(--gold);color:#111;border-radius:5px;padding:0 4px;white-space:nowrap;pointer-events:none}
.bg-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:6px}.bg-opt{display:flex;flex-direction:column;gap:4px;padding:4px;border-radius:12px;border:2px solid transparent;background:var(--card2);color:var(--ink);font:700 11px var(--f);cursor:pointer}.bg-opt i{display:block;aspect-ratio:3/4;border-radius:9px}.bg-opt.on{border-color:var(--gold)}
.ed-more{border:1px solid var(--line);border-radius:12px;padding:8px 10px}.ed-more>summary{cursor:pointer;font-weight:700;color:var(--muted);font-size:13px;padding:4px 0}.ed-more[open]{display:flex;flex-direction:column;gap:10px}
.ed-card.rsel{outline:2px solid var(--gold);outline-offset:1px}
.ed-ph{position:absolute;top:0;bottom:0;width:3px;background:#fff;box-shadow:0 0 4px #000;pointer-events:none}
.ed-tabs{display:flex;flex-direction:column;gap:6px}.ed-grp,.ed-sub{display:flex;gap:6px;overflow-x:auto;padding-bottom:2px}.ed-grp button{flex:1 1 0;padding:0 6px!important;min-width:58px}
.ed-tabs button{flex:0 0 auto;min-height:40px;border-radius:99px;border:1px solid var(--line);background:var(--card2);color:var(--text);font:inherit;font-size:13px;font-weight:700;padding:0 14px;cursor:pointer}
.ed-tabs button.on{background:var(--gold);color:var(--on-gold);border-color:var(--gold)}
.ed-panel{display:flex;flex-direction:column;gap:10px}
.ed-row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.ed-card{display:flex;flex-direction:column;gap:8px;background:var(--card2);border-radius:12px;padding:10px}
.vtpl-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}.vtpl{display:flex;flex-direction:column;align-items:flex-start;gap:3px;text-align:left;padding:12px;border-radius:12px;border:1px solid var(--line);background:var(--card2);color:var(--ink);font:inherit;cursor:pointer}.vtpl i{font-style:normal;font-size:22px}.vtpl b{font-size:14px}.vtpl span{font-size:11px;color:var(--muted)}.vtpl.on{border-color:var(--gold);box-shadow:inset 0 0 0 1px var(--gold)}
.ed-card .ed-row .input{flex:1;min-width:110px}
.ed-tin{display:flex;align-items:center;gap:6px;flex:1;min-width:130px}.ed-tin span{font-size:12px;color:var(--muted);white-space:nowrap}.ed-tin .input{min-width:0!important;width:100%;min-height:38px;padding:6px 10px;font-variant-numeric:tabular-nums}
`;
let E=null; /* the open project */
const st=document.createElement('style');st.textContent=CSS;document.head.appendChild(st);
document.body.insertAdjacentHTML('beforeend',H);
const edI18n=()=>{if(typeof t!=='function')return;document.querySelectorAll('#edScreen [data-i18n]').forEach(e=>{e.textContent=t(e.dataset.i18n);});};edI18n();
/* language changed while the editor is open: redraw its buttons and panel in the new language */
window.edRelang=()=>{edI18n();try{if(E){renderTabs();renderPanel();G('edPlay').textContent=E.playing?t('tpPause'):t('sePlay');drawAt();}}catch(e){}};
const V=()=>document.getElementById('edVid'),CV=()=>document.getElementById('edCv'),G=id=>document.getElementById(id);
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
const fm=x=>{x=Math.max(0,x||0);const m=Math.floor(x/60),s=Math.floor(x%60);return m+':'+String(s).padStart(2,'0');};
const fm1=x=>{x=Math.max(0,x||0);const m=Math.floor(x/60),s=x-m*60;return m+':'+(s<10?'0':'')+s.toFixed(1);};

/* ---------- project ---------- */
function blank(dur){return{v:1,segs:[{a:0,b:dur,keep:true,speed:1,zoom:false}],texts:[],voices:[],origVol:100,voiceVol:100,enh:'none',music:'none',musVol:35,duck:true,fmt:'orig',fill:'blur',sugs:[],cuts:[],offs:{},stickers:[],track:null,follow:false,look:'std',brand:false,col:{b:0,c:0,s:0,t:0},angCol:{},steps:[],stepBar:true,sfx:[],q:'normal',subs:false};}
function key(){return 'pmc_ed_'+E.name;}
/* brand text and style belong to this video (so a clean recording keeps its own price, title…) */
const BV_IDS=['titleTxt','priceTxt','phoneTxt','shopTxt','descTxt','menuTxt','ovStyle','ovColor','ovFont','logoPos'];
function bvInit(){if(!E.p.bv){E.p.bv={};BV_IDS.forEach(id=>{const el=$(id);if(el)E.p.bv[id]=el.value;});}}
function bvSwap(){if(!E||!E.p.bv)return null;const old={};BV_IDS.forEach(id=>{const el=$(id);if(el&&E.p.bv[id]!=null){old[id]=el.value;el.value=E.p.bv[id];}});return old;}
function bvRestore(old){if(!old)return;Object.entries(old).forEach(([id,v])=>{const el=$(id);if(el)el.value=v;});}
/* "Brand recipe": one tap to reuse the look, brand, sound and size of a video you liked */
const PRESET_KEYS=['look','brand','fmt','fill','enh','music','musVol','duck','col','stepBar','q'];
function presets(){try{return JSON.parse(localStorage.getItem('pmc_presets')||'[]');}catch(e){return[];}}
function presetsSave(l){try{localStorage.setItem('pmc_presets',JSON.stringify(l));}catch(e){}}
function defPreset(){const id=localStorage.getItem('pmc_preset_def');return presets().find(q=>q.id===id)||null;}
function presetApply(p,pr){if(!pr)return p;PRESET_KEYS.forEach(k=>{if(pr.data[k]!==undefined)p[k]=JSON.parse(JSON.stringify(pr.data[k]));});
  if(pr.ov){['ovStyle','ovColor','ovFont'].forEach(id=>{if(pr.ov[id]&&$(id))$(id).value=pr.ov[id];});if(pr.ov.ovFont&&$('ovFont'))$('ovFont').dataset.touched='1';try{saveSettings(true);renderOvUI();loadOvFont();}catch(e){}}
  if(pr.sub){try{localStorage.setItem('pmc_sub_style',JSON.stringify(pr.sub));}catch(e){}}return p;}
function presetFromNow(name){const d={};PRESET_KEYS.forEach(k=>d[k]=JSON.parse(JSON.stringify(E.p[k])));let sub=null;try{const x=JSON.parse(localStorage.getItem('pmc_sub_'+E.name)||'null');if(x){sub={font:x.font,col:x.col,pos:x.pos,size:x.size,box:x.box};}}catch(e){}
  return{id:'p'+Date.now(),name,data:d,ov:{ovStyle:$('ovStyle').value,ovColor:$('ovColor').value,ovFont:$('ovFont').value},sub};}
function save(){try{const p=Object.assign({},E.p);localStorage.setItem(key(),JSON.stringify(p));}catch(e){}}
function snap(){E.hist.push(JSON.stringify(E.p));if(E.hist.length>40)E.hist.shift();}
window.edUndo=function(){if(!E||!E.hist.length){toast(t('edNoUndo'));return;}E.p=JSON.parse(E.hist.pop());save();renderAll();};
async function editsDir(){const r=await navigator.storage.getDirectory();return r.getDirectoryHandle('edits',{create:true});}
async function voiceDir(){const d=await editsDir();return d.getDirectoryHandle(E.name.replace(/[^\w.-]+/g,'_'),{create:true});}

window.openEditor=async function(it){
  if(!isPro()){openPro();return;}
  closeSheet('filesSheet');
  const blob=await savedBlob(it.h,mimeOf(it.name));
  E={name:it.name,h:it.h,blob,url:URL.createObjectURL(blob),hist:[],tab:'tpl',raf:0,playing:false,voiceEls:{},sel:null};
  const v=V();v.src=E.url;v.muted=false;
  await new Promise((res,rej)=>{v.onloadedmetadata=res;v.onerror=rej;}).catch(()=>{});
  if(!isFinite(v.duration)){v.currentTime=1e9;await new Promise(r=>v.ondurationchange=r);v.currentTime=0;}
  E.dur=v.duration||0;
  let p=null;try{p=JSON.parse(localStorage.getItem(key())||'null');}catch(e){}
  E.p=p&&p.segs&&p.segs.length?Object.assign(blank(E.dur),p):presetApply(blank(E.dur),defPreset());
  if(!(p&&p.segs)){try{const fh=await it.h.getFileHandle('_brand.json');const meta=JSON.parse(await (await fh.getFile()).text());E.p.brand=true;E.p.look=meta.look||E.p.look;E.p.bv=meta.bv||null;E.fromClean=true;}catch(e){}}
  G('edName').textContent=it.name;G('edScreen').classList.add('show');
  document.querySelectorAll('.tile video').forEach(x=>{try{x.pause();}catch(e){}});   /* Director tiles are hidden behind the editor */
  if(E.p.bg&&E.p.bg!=='none'&&!window._edSeg)needSeg().then(()=>drawAt()).catch(()=>{E.p.bg='none';});
  E.p.dn=false;
  setupAudio();E.g.mus.gain.value=1;duckLvl=1;sizeCanvas();renderAll();thumbs();loadVoices();loadAngles().then(()=>renderAll());musicLoad();E.p.segs.filter(q=>q.keep&&q.fx&&q.fx!=='none').forEach(q=>buildFx(q).catch(()=>{}));
  v.addEventListener('loadeddata',()=>drawAt(),{once:true});v.currentTime=firstKept()+0.001;drawAt();setTimeout(()=>{if(E)drawAt();},600);
};
window.edClose=function(){if(E&&E.busy){toast(t('seBusy'));return;}document.querySelectorAll('.tile video').forEach(x=>{try{x.play().catch(()=>{});}catch(e){}});edPause();stopVoiceRec();if(E&&E.g)E.g.mus.gain.value=0;G('edScreen').classList.remove('show');if(E){save();URL.revokeObjectURL(E.url);Object.values(E.voiceEls).forEach(freeAudio);(E.angles||[]).forEach(a=>{try{a.v.pause();a.v.removeAttribute('src');a.v.load();URL.revokeObjectURL(a.url);}catch(e){}});if(E.musEl)freeAudio(E.musEl);try{const v=V();v.pause();v.removeAttribute('src');v.load();}catch(e){}try{cancelAnimationFrame(E.raf);}catch(e){}}E=null;try{G('edThumbs').innerHTML='';G('edSegs').innerHTML='';const c=CV();c.width=2;c.height=2;}catch(e){}if(window.PMC_after_edit)PMC_after_edit();};

/* ---------- segments (all times are in the original video's time) ---------- */
function segAt(x){return E.p.segs.find(s=>x>=s.a&&x<s.b)||E.p.segs[E.p.segs.length-1];}
function firstKept(){const s=E.p.segs.find(s=>s.keep);return s?s.a:0;}
function nextKeptFrom(x){for(const s of E.p.segs){if(s.keep&&s.b>x+0.02)return Math.max(s.a,x);}return null;}
function partLen(s){return (s.b-s.a)/(s.speed||1)*(s.fx==='boom'?2:1);}
function keptDur(){return outPos(E.dur+1);}
function outTime(x){return outPos(x);}
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
    g.orig=A.createGain();g.orig.connect(g.speech);g.dnG=A.createGain();g.dnG.connect(g.speech);g.voice=A.createGain();g.voice.connect(g.speech);
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
  const dnOn=!!(E.p.dn&&E.dn);g.orig.gain.value=dnOn?0:E.p.origVol/100;g.dnG.gain.value=dnOn?E.p.origVol/100:0;g.voice.gain.value=E.p.voiceVol/100;
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
  const base=Math.min(E.p.q==='small'?720:1080,Math.min(vw,vh));
  if(f==='p')return[base,Math.round(base*16/9/2)*2];
  if(f==='l')return[Math.round(base*16/9/2)*2,base];
  if(f==='sq')return[base,base];
  const cap=E.p.q==='small'?1280:1920,k=Math.max(vw,vh)>cap?cap/Math.max(vw,vh):1;return[Math.round(vw*k/2)*2,Math.round(vh*k/2)*2];
}
function sizeCanvas(full){let[w,h]=outSize();if(!full&&!(E&&E.busy)){const k=Math.min(1,960/Math.max(w,h));w=Math.round(w*k/2)*2;h=Math.round(h*k/2)*2;}const c=CV();if(c.width!==w||c.height!==h){c.width=w;c.height=h;}}
function angleAt(x){if(!E.angles||!E.angles.length||!E.p.cuts.length)return null;let a=null;for(const c of E.p.cuts){if(c.t<=x+1e-3)a=c.a;else break;}return a==null||a<0?null:E.angles.find(q=>q.label===a)||null;}
function trackAt(x){const tr=E.p.track;if(!E.p.follow||!tr||!tr.length)return null;let i=0;while(i<tr.length-1&&tr[i+1].t<=x)i++;const a=tr[i],b=tr[i+1];if(!b)return a;const k=clamp((x-a.t)/Math.max(0.01,b.t-a.t),0,1);return{x:a.x+(b.x-a.x)*k,y:a.y+(b.y-a.y)*k};}
function dimsOf(v){return[v.videoWidth||v.displayWidth||v.width||0,v.videoHeight||v.displayHeight||v.height||0];}
function colOf(label){const g=E.p.col||{},a=(label!=null&&E.p.angCol&&E.p.angCol[label])||{};return{b:(g.b||0)+(a.b||0),c:(g.c||0)+(a.c||0),s:(g.s||0)+(a.s||0),t:(g.t||0)+(a.t||0)};}
function colFilter(o){if(!o.b&&!o.c&&!o.s)return '';return ` brightness(${1+o.b/100}) contrast(${1+o.c/100}) saturate(${1+o.s/100})`;}
function tempOver(c,x,y,w,h,tv){if(!tv)return;c.save();c.filter='none';c.globalCompositeOperation='soft-light';c.globalAlpha=Math.min(0.6,Math.abs(tv)/100*0.6);c.fillStyle=tv>0?'#ff8a1e':'#1e7bff';c.fillRect(x,y,w,h);c.restore();}
function kbAt(s,x){if(!s||!s.kb)return null;const P={wide:[1,0.5,0.5],center:[1.5,0.5,0.5],left:[1.6,0.25,0.5],right:[1.6,0.75,0.5],top:[1.6,0.5,0.25],bottom:[1.6,0.5,0.75]};const a=P[s.kb.from]||P.wide,b=P[s.kb.to]||P.center;
  const p=clamp((x-s.a)/Math.max(0.1,s.b-s.a),0,1),e=p*p*(3-2*p);return{z:a[0]+(b[0]-a[0])*e,x:a[1]+(b[1]-a[1])*e,y:a[2]+(b[2]-a[2])*e};}
function srcFor(label){if(label==null||label===-1)return{v:V(),label:-1};const a=(E.angles||[]).find(q=>q.label===label);return a&&a.v.readyState>=2?{v:a.v,label}:{v:V(),label:-1};}
function drawVideo(c,W,H,x,src,force){
  let ag=null,lab=-1;if(!src){if(force!==undefined){const f=srcFor(force);src=null;ag=f.label!==-1?{v:f.v,label:f.label}:null;}else ag=angleAt(x);}
  let v=src||(ag&&ag.v.readyState>=2?ag.v:V());lab=ag?ag.label:-1;if(!dimsOf(v)[0])return;
  const base=(c.filter&&c.filter!=='none')?c.filter:'',co=colOf(lab),cf=(base+colFilter(co)).trim()||'none';
  if(E.p.bg&&E.p.bg!=='none'&&window._edSeg){try{v=bgProcess(v);}catch(e){}}
  const [vw,vh]=dimsOf(v);
  const s=segAt(x);let z=1;if(s&&s.zoom){const p=clamp((x-s.a)/Math.max(0.1,s.b-s.a),0,1);z=1+0.18*p;}
  const kb=kbAt(s,x);
  if(kb){const k=Math.max(W/vw,H/vh)*kb.z,dw=vw*k,dh=vh*k;const ox=clamp(W/2-kb.x*dw,W-dw,0),oy=clamp(H/2-kb.y*dh,H-dh,0);c.filter=cf;c.drawImage(v,ox,oy,dw,dh);c.filter='none';tempOver(c,0,0,W,H,co.t);return;}
  const sameShape=Math.abs(vw/vh-W/H)<0.02,tk=!sameShape&&!ag?trackAt(x):null;
  if(tk){const k=Math.max(W/vw,H/vh)*z,dw=vw*k,dh=vh*k;const ox=clamp(W/2-tk.x*dw,W-dw,0),oy=clamp(H/2-tk.y*dh,H-dh,0);c.filter=cf;c.drawImage(v,ox,oy,dw,dh);c.filter='none';tempOver(c,0,0,W,H,co.t);return;}
  if(!sameShape&&E.p.fill==='blur'){c.save();c.filter='blur(28px) brightness(0.55)';const k=Math.max(W/vw,H/vh)*1.1;c.drawImage(v,(W-vw*k)/2,(H-vh*k)/2,vw*k,vh*k);c.restore();}
  else if(!sameShape){c.fillStyle='#000';c.fillRect(0,0,W,H);}
  const k=(sameShape?Math.max(W/vw,H/vh):Math.min(W/vw,H/vh))*z,dw=vw*k,dh=vh*k;
  c.filter=cf;c.drawImage(v,(W-dw)/2,(H-dh)/2,dw,dh);c.filter='none';tempOver(c,Math.max(0,(W-dw)/2),Math.max(0,(H-dh)/2),Math.min(W,dw),Math.min(H,dh),co.t);
}
function layoutAt(x){if(!E.p.cuts||!E.p.cuts.length)return null;let c0=null;for(const c of E.p.cuts){if(c.t<=x+1e-3)c0=c;else break;}return c0&&c0.lay&&c0.lay!=='full'?c0:null;}
function drawText(c,W,H,tx,x){
  const age=x-tx.s,left=tx.e-x;let alpha=1,sc=1,dx=0,txt=tx.txt;
  if(tx.anim==='fade')alpha=clamp(Math.min(age,left)/0.35,0,1);
  if(tx.anim==='pop'){const p=clamp(age/0.28,0,1);sc=0.55+0.45*(1-Math.pow(1-p,3))+(p<1?Math.sin(p*Math.PI)*0.08:0);alpha=clamp(left/0.25,0,1);}
  if(tx.anim==='slide'){const p=clamp(age/0.35,0,1);dx=(1-(1-Math.pow(1-p,3)))*-W*0.6;alpha=clamp(left/0.25,0,1);}
  if(tx.anim==='type'){const n=Math.floor(clamp(age/Math.min(1.6,txt.length*0.06),0,1)*txt.length);txt=txt.slice(0,n);}
  if(!txt.trim())return;
  const o={txt,font:tx.font||'montserrat',col:tx.col||'white',size:tx.size||7,style:txStyleOf(tx,'bubble'),cs:tx.cs||'asis'};
  c.save();c.globalAlpha=alpha;const L=txLayout(c,o,W,H),tot=L.lines.length*L.lh,cy=tx.pos==='t'?H*0.12:tx.pos==='b'?H*0.86-tot:H/2-tot/2;
  c.translate(W/2+dx,cy+tot/2);c.scale(sc,sc);txPaint(c,L,0,-tot/2,o,age,tx.e-tx.s);
  c.restore();
}
function drawAt(){if(!E)return;composeAt(null,V().currentTime);uiAt(V().currentTime);}
function composeAt(src,x){
  const c=CV(),ctx2=c.getContext('2d'),W=c.width,Hh=c.height;
  ctx2.fillStyle='#000';ctx2.fillRect(0,0,W,Hh);
  const lk=(typeof LOOKS!=='undefined'&&LOOKS.find(l=>l.id===E.p.look))||null;
  let R={x:0,y:0,w:W,h:Hh};if(E.p.brand&&typeof videoRect==='function'){const sw=bvSwap();window._edDrawing=true;try{R=videoRect(W,Hh);}finally{window._edDrawing=false;bvRestore(sw);}if(R.bg){ctx2.fillStyle=R.bg;ctx2.fillRect(0,0,W,Hh);}}
  const LY=src?null:layoutAt(x);
  ctx2.save();ctx2.translate(R.x,R.y);
  if(LY&&LY.lay==='split'){const vert=R.h>R.w;
    ctx2.save();if(lk&&lk.f)ctx2.filter=lk.f;drawVideo(ctx2,vert?R.w:R.w/2,vert?R.h/2:R.h,x,null,LY.a);ctx2.restore();
    ctx2.save();ctx2.translate(vert?0:R.w/2,vert?R.h/2:0);ctx2.beginPath();ctx2.rect(0,0,vert?R.w:R.w/2,vert?R.h/2:R.h);ctx2.clip();if(lk&&lk.f)ctx2.filter=lk.f;drawVideo(ctx2,vert?R.w:R.w/2,vert?R.h/2:R.h,x,null,LY.b);ctx2.restore();
    ctx2.fillStyle=(OV_COLORS[$('ovColor').value]||OV_COLORS.gold).c;if(vert)ctx2.fillRect(0,R.h/2-2,R.w,4);else ctx2.fillRect(R.w/2-2,0,4,R.h);}
  else{if(lk&&lk.f)ctx2.filter=lk.f;drawVideo(ctx2,R.w,R.h,x,src,LY?LY.a:undefined);ctx2.filter='none';
    if(LY&&LY.lay==='pip'){const pw=Math.round(Math.min(R.w,R.h)*0.36),ph=Math.round(pw*(R.h>R.w?4/3:3/4)),px=R.w-pw-Math.round(R.w*0.04),py=R.h-ph-Math.round(R.h*0.12);
      ctx2.fillStyle=(OV_COLORS[$('ovColor').value]||OV_COLORS.gold).c;ctx2.fillRect(px-4,py-4,pw+8,ph+8);ctx2.save();ctx2.translate(px,py);ctx2.beginPath();ctx2.rect(0,0,pw,ph);ctx2.clip();if(lk&&lk.f)ctx2.filter=lk.f;drawVideo(ctx2,pw,ph,x,null,LY.b);ctx2.restore();}}
  ctx2.restore();ctx2.filter='none';
  if(lk&&lk.vig){const gr=ctx2.createRadialGradient(W/2,Hh/2,Math.min(W,Hh)*0.35,W/2,Hh/2,Math.max(W,Hh)*0.7);gr.addColorStop(0,'rgba(0,0,0,0)');gr.addColorStop(1,'rgba(0,0,0,.6)');ctx2.fillStyle=gr;ctx2.fillRect(0,0,W,Hh);}
  if(lk&&lk.bars){const bh=Math.round(Hh*0.08);ctx2.fillStyle='#000';ctx2.fillRect(0,0,W,bh);ctx2.fillRect(0,Hh-bh,W,bh);}
  {const sd=E.p.subs&&subData();window._ovLift=(sd&&sd.lines&&sd.lines.length&&(sd.pos||'b')==='b')?Math.round(Math.min(W,Hh)*0.17):0;}
  if(E.p.brand&&typeof drawOverlays==='function'){const keepCtx=ctx,keepLook=look,sw=bvSwap();try{ctx=ctx2;look=lk||LOOKS[0];window._edDrawing=true;drawOverlays(W,Hh);}catch(e){}finally{ctx=keepCtx;look=keepLook;window._edDrawing=false;bvRestore(sw);}}
  const sg0=segAt(x);
  if(sg0&&sg0.tin&&sg0.tin!=='none'&&E.prevFrame&&E.prevAt===sg0.a){const p=clamp((x-sg0.a)/0.45,0,1);if(p<1){ctx2.save();
    if(sg0.tin==='fade'){ctx2.globalAlpha=1-p;ctx2.drawImage(E.prevFrame,0,0,W,Hh);}
    else if(sg0.tin==='black'){ctx2.globalAlpha=1-p;ctx2.fillStyle='#000';ctx2.fillRect(0,0,W,Hh);}
    else if(sg0.tin==='zoom'){ctx2.globalAlpha=1-p;const z=1+0.25*p;ctx2.drawImage(E.prevFrame,W*(1-z)/2,Hh*(1-z)/2,W*z,Hh*z);}
    else if(sg0.tin==='slide'){ctx2.drawImage(E.prevFrame,-W*p,0,W,Hh);}
    ctx2.restore();}}
  E.p.texts.forEach(tx=>{if(x>=tx.s&&x<tx.e&&tx.txt.trim())drawText(ctx2,W,Hh,tx,x);});
  (E.p.stickers||[]).forEach(sk=>{if(x>=sk.s&&x<sk.e)drawSticker(ctx2,W,Hh,sk,x);});
  drawSteps(ctx2,W,Hh,x);
  if(E.p.subs)drawSubs(ctx2,W,Hh,x);window._ovLift=0;
}
/* recipe steps: "Step 2/5 · Boil the noodles" + a progress bar */
function drawSteps(c,W,H,x){
  const st=(E.p.steps||[]).slice().sort((a,b)=>a.t-b.t);if(!st.length)return;let i=-1;st.forEach((q,k)=>{if(q.t<=x+1e-3)i=k;});
  const S=Math.min(W,H),C=OV_COLORS[$('ovColor').value]||OV_COLORS.gold;
  if(E.p.stepBar){const tot=outPos(E.dur+1)||1,p=clamp(outPos(x)/tot,0,1),bh=Math.max(4,S*0.012);c.fillStyle='rgba(0,0,0,.45)';c.fillRect(0,0,W,bh);c.fillStyle=C.c;c.fillRect(0,0,W*p,bh);
    st.forEach(q=>{const px=outPos(q.t)/tot*W;c.fillStyle='#fff';c.fillRect(px-1,0,2,bh);});}
  if(i<0)return;const q=st[i],age=x-q.t,a=clamp(age/0.3,0,1);
  const f=OV_FONTS[$('ovFont').value]||OV_FONTS.montserrat,fs=Math.round(S/22),pad=fs*0.5,m=Math.round(S*0.04),y=m+Math.max(4,S*0.012);
  c.save();c.globalAlpha=a;c.font=`800 ${fs*0.8}px ${f.fam},"Bricolage Grotesque",sans-serif`;const tag=t('stepN',{n:i+1,m:st.length}),tw=c.measureText(tag).width;
  c.font=`${f.w} ${fs}px ${f.fam},"Bricolage Grotesque",sans-serif`;const txt=f.caps?(q.txt||'').toUpperCase():(q.txt||''),xw=Math.min(W-m*2-tw-pad*4,c.measureText(txt).width);
  const h=fs*1.6;c.fillStyle=C.c;c.beginPath();if(c.roundRect)c.roundRect(m,y,tw+pad*2,h,h*0.3);else c.rect(m,y,tw+pad*2,h);c.fill();
  c.fillStyle=C.ink;c.font=`800 ${fs*0.8}px ${f.fam},"Bricolage Grotesque",sans-serif`;c.fillText(tag,m+pad,y+h*0.66);
  if(txt){c.fillStyle='rgba(11,15,23,.85)';c.beginPath();if(c.roundRect)c.roundRect(m+tw+pad*2+4,y,xw+pad*2,h,h*0.3);else c.rect(m+tw+pad*2+4,y,xw+pad*2,h);c.fill();
    c.fillStyle='#fff';c.font=`${f.w} ${fs}px ${f.fam},"Bricolage Grotesque",sans-serif`;let tt=txt;while(tt.length>1&&c.measureText(tt).width>xw)tt=tt.slice(0,-1);c.fillText(tt,m+tw+pad*3+4,y+h*0.68);}
  c.restore();
}
/* subtitles made in the subtitle editor, burned in here with the same style */
const SUBC={white:'#ffffff',gold:'#d9b44a',yellow:'#ffe14d',green:'#5be08a'};
function subData(){const now=Date.now();if(!E.subAt||now-E.subAt>1500){E.subAt=now;try{E.sub=JSON.parse(localStorage.getItem('pmc_sub_'+E.name)||'null');}catch(e){E.sub=null;}}return E.sub;}
function drawSubs(c,W,H,x){
  const d=subData();if(!d||!d.lines)return;const l=d.lines.find(q=>x>=q.s&&x<q.e&&q.txt&&q.txt.trim());if(!l)return;
  c.save();txDraw(c,W,H,{txt:l.txt.trim(),font:OV_FONTS[d.font]?d.font:'poppins',col:d.col||'white',size:Number(d.size||6),pos:d.pos||'b',style:d.style||(d.box===false?'outline':'darkbox'),cs:d.cs||'asis',maxW:0.88,bottom:d.pos==='b'||!d.pos?0.965:0.9},x-l.s,l.e-l.s);c.restore();
}
function uiAt(x){
  G('edTime').textContent=fm1(outTime(x))+' / '+fm1(keptDur());
  const ph=G('edPh');if(ph&&E.dur)ph.style.left=(x/E.dur*100)+'%';
  const sg=segAt(x);if(E.tab==='cut'&&sg&&E.lastSeg!==sg.a+'|'+sg.b+'|'+sg.keep){E.lastSeg=sg.a+'|'+sg.b+'|'+sg.keep;if(!E.busy)renderPanel();}
}

function grabPrev(atA){const c=CV();if(!E.prevFrame)E.prevFrame=document.createElement('canvas');E.prevFrame.width=c.width;E.prevFrame.height=c.height;E.prevFrame.getContext('2d').drawImage(c,0,0);E.prevAt=atA;}
const STK=['arrow','ring','star','heart','check','pin','new','promo','hot'];
function drawSticker(c,W,H,sk,x){
  const S=Math.min(W,H),r=S*(sk.size||12)/100,cx=sk.x*W,cy=sk.y*H,age=x-sk.s,pop=clamp(age/0.25,0,1),sc=0.6+0.4*pop+(pop<1?Math.sin(pop*Math.PI)*0.12:0);
  const col=(OV_COLORS[sk.col]||OV_COLORS.gold).c,ink=(OV_COLORS[sk.col]||OV_COLORS.gold).ink;
  c.save();c.translate(cx,cy);c.rotate((sk.rot||0)*Math.PI/180);c.scale(sc,sc);c.lineJoin='round';c.lineCap='round';
  c.shadowColor='rgba(0,0,0,.5)';c.shadowBlur=r*0.15;c.lineWidth=r*0.16;c.strokeStyle=col;c.fillStyle=col;
  const k=sk.kind;
  if(k==='arrow'){c.beginPath();c.moveTo(-r,0);c.lineTo(r*0.7,0);c.stroke();c.beginPath();c.moveTo(r,0);c.lineTo(r*0.35,-r*0.5);c.lineTo(r*0.35,r*0.5);c.closePath();c.fill();}
  else if(k==='ring'){c.beginPath();c.ellipse(0,0,r,r*0.8,0,0,Math.PI*2);c.stroke();}
  else if(k==='star'){c.beginPath();for(let i=0;i<10;i++){const a=-Math.PI/2+i*Math.PI/5,rr=i%2?r*0.45:r;c.lineTo(Math.cos(a)*rr,Math.sin(a)*rr);}c.closePath();c.fill();}
  else if(k==='heart'){c.beginPath();c.moveTo(0,r*0.8);c.bezierCurveTo(-r*1.3,-r*0.1,-r*0.6,-r*1.1,0,-r*0.4);c.bezierCurveTo(r*0.6,-r*1.1,r*1.3,-r*0.1,0,r*0.8);c.fill();}
  else if(k==='check'){c.beginPath();c.arc(0,0,r,0,Math.PI*2);c.fill();c.shadowColor='transparent';c.strokeStyle=ink;c.beginPath();c.moveTo(-r*0.45,0);c.lineTo(-r*0.1,r*0.35);c.lineTo(r*0.5,-r*0.35);c.stroke();}
  else if(k==='pin'){c.beginPath();c.arc(0,-r*0.3,r*0.6,Math.PI,0);c.lineTo(0,r);c.closePath();c.fill();c.shadowColor='transparent';c.fillStyle=ink;c.beginPath();c.arc(0,-r*0.3,r*0.22,0,Math.PI*2);c.fill();}
  else{const txt=t('stk_'+k),fs=r*0.62;c.font=`800 ${fs}px Anton,"Bricolage Grotesque",sans-serif`;const w=c.measureText(txt).width+fs;c.beginPath();if(c.roundRect)c.roundRect(-w/2,-fs*0.85,w,fs*1.7,fs*0.4);else c.rect(-w/2,-fs*0.85,w,fs*1.7);c.fill();c.shadowColor='transparent';c.fillStyle=ink;c.textAlign='center';c.fillText(txt,0,fs*0.35);}
  c.restore();
}
/* angles: keep every angle video at the same moment as the main recording */
function angleSync(playing,rate){
  if(!E.angles)return;const x=V().currentTime;
  E.angles.forEach(a=>{const tg=x-(E.p.offs[a.label]||a.lag||0);const v=a.v;
    if(tg<0||tg>(v.duration||1e9)){if(!v.paused)v.pause();return;}
    if(Math.abs(v.currentTime-tg)>(playing?0.15:0.04))v.currentTime=tg;
    v.playbackRate=rate||1;if(playing&&v.paused)v.play().catch(()=>{});if(!playing&&!v.paused)v.pause();});
}
async function loadAngles(){
  (E.angles||[]).forEach(a=>{try{a.v.pause();URL.revokeObjectURL(a.url);}catch(e){}});E.angles=[];const id=recIdOf(E.name);if(!id)return;
  try{const a=await anglesRoot();const d=await a.getDirectoryHandle(id);
    for await(const [label,h] of d.entries()){if(h.kind!=='directory')continue;let meta={};try{meta=JSON.parse(await (await (await h.getFileHandle('_done')).getFile()).text());}catch(e){continue;}
      const blob=await savedBlob(h,meta.mime||'video/mp4');if(!blob.size)continue;const url=URL.createObjectURL(blob);const v=document.createElement('video');v.src=url;v.muted=true;v.playsInline=true;v.preload='auto';
      v.addEventListener('seeked',()=>{if(E&&!E.playing)drawAt();});E.angles.push({label,url,v,blob,lag:meta.lag||0});}
  }catch(e){}
  try{const fh=await E.h.getFileHandle('_switches.json');E.swLog=JSON.parse(await (await fh.getFile()).text());}catch(e){E.swLog=[];}
  if(E.angles.length&&!E.p.cuts.length&&E.swLog.length){E.p.cuts=E.swLog.map(s=>({t:s.t,a:E.angles.some(q=>q.label===s.name)?s.name:-1}));save();}
}
async function envOf(blob,hop){const buf=await actx.decodeAudioData(await blob.arrayBuffer());const d=buf.getChannelData(0),win=Math.round(buf.sampleRate*hop),n=Math.floor(d.length/win),o=new Float32Array(n);for(let i=0;i<n;i++){let e=0;for(let j=i*win;j<(i+1)*win;j+=2)e+=d[j]*d[j];o[i]=Math.sqrt(e/(win/2));}return o;}
/* line up each angle with the main recording by matching their sound */
async function autoSync(){
  const prog=G('edProg');prog.classList.add('show');G('edProgTx').textContent=t('angSyncing');
  try{const hop=0.02,base=await envOf(E.blob,hop),N=Math.min(base.length,1500);
    const norm=a=>{let m=0;for(let i=0;i<a.length;i++)m+=a[i];m/=a.length||1;return a.map(x=>x-m);};const b=norm(base.slice(0,N));
    for(const ag of E.angles){try{const e=norm((await envOf(ag.blob,hop)).slice(0,N+200));let best=0,bl=0;
      for(let lag=-100;lag<=100;lag++){let sum=0;for(let i=0;i<N;i++){const j=i-lag;if(j>=0&&j<e.length)sum+=b[i]*e[j];}if(sum>best){best=sum;bl=lag;}}
      E.p.offs[ag.label]=+(bl*hop).toFixed(2);}catch(e){}}
    save();toast(t('angSynced'),3000);
  }catch(e){toast(t('seFail'));}
  prog.classList.remove('show');renderPanel();drawAt();
}
/* switch to whoever is speaking: loudest angle (by its own microphone) with a minimum shot length */
async function autoSpeaker(){
  if(!E.angles.length)return;const prog=G('edProg');prog.classList.add('show');G('edProgTx').textContent=t('angListen');
  try{const hop=0.25,envs=[];for(const ag of E.angles){const e=await envOf(ag.blob,hop);const srt=Array.from(e).sort((a,b)=>a-b);const med=srt[Math.floor(srt.length*0.5)]||1e-4;envs.push({ag,e,med,off:E.p.offs[ag.label]||ag.lag||0});}
    const T=Math.floor(E.dur/hop),cuts=[];let cur=null,since=0;
    for(let i=0;i<T;i++){const x=i*hop;let best=null,bv=0;envs.forEach(o=>{const j=Math.round((x-o.off)/hop);const v=j>=0&&j<o.e.length?o.e[j]/o.med:0;if(v>bv){bv=v;best=o.ag.label;}});
      if(best&&best!==cur&&bv>2.2){const cv=cur?envs.find(o=>o.ag.label===cur):null;const cj=cv?Math.round((x-cv.off)/hop):-1;const cvv=cv&&cj>=0&&cj<cv.e.length?cv.e[cj]/cv.med:0;
        if(!cur||(x-since>=2&&bv>cvv*1.4)){cuts.push({t:+x.toFixed(2),a:best});cur=best;since=x;}}}
    snap();E.p.cuts=cuts;save();toast(t('angAutoDone',{n:cuts.length}),3500);
  }catch(e){toast(t('seFail'));}
  prog.classList.remove('show');renderPanel();drawAt();
}
/* make every angle look like the main recording: compare average brightness and warmth at a few moments */
async function frameStats(url,times,off){const v=document.createElement('video');v.src=url;v.muted=true;v.playsInline=true;await new Promise(r=>{v.onloadeddata=r;v.onerror=r;});
  const c=document.createElement('canvas');c.width=48;c.height=48;const g=c.getContext('2d',{willReadFrequently:true});let R=0,Gg=0,B=0,n=0;
  for(const x of times){const tt=x-(off||0);if(tt<0||tt>(v.duration||0))continue;v.currentTime=tt;await new Promise(r=>{v.onseeked=r;setTimeout(r,1200);});g.drawImage(v,0,0,48,48);const d=g.getImageData(0,0,48,48).data;for(let i=0;i<d.length;i+=4){R+=d[i];Gg+=d[i+1];B+=d[i+2];n++;}}
  return n?{r:R/n,g:Gg/n,b:B/n}:null;}
async function matchColours(){
  if(!E.angles.length)return;const prog=G('edProg');prog.classList.add('show');G('edProgTx').textContent=t('colMatching');
  try{const times=[0.2,0.4,0.6,0.8].map(k=>k*E.dur),ref=await frameStats(E.url,times,0);if(!ref)throw 0;const lr=0.3*ref.r+0.59*ref.g+0.11*ref.b;
    E.p.angCol=E.p.angCol||{};
    for(const a of E.angles){const st=await frameStats(a.url,times,E.p.offs[a.label]||a.lag||0);if(!st)continue;const la=0.3*st.r+0.59*st.g+0.11*st.b;
      E.p.angCol[a.label]={b:Math.round(clamp((lr/Math.max(1,la)-1)*100,-40,40)),c:0,s:0,t:Math.round(clamp(((ref.r-ref.b)-(st.r-st.b))*0.8,-60,60))};}
    save();toast(t('colMatched'),3000);}catch(e){toast(t('seFail'));}
  prog.classList.remove('show');renderPanel();drawAt();
}
/* best cover picture: sharp, well lit, colourful */
async function autoCover(){
  const prog=G('edProg');prog.classList.add('show');G('edProgTx').textContent=t('coverFinding');
  try{const v=document.createElement('video');v.src=E.url;v.muted=true;v.playsInline=true;await new Promise(r=>{v.onloadeddata=r;v.onerror=r;});
    const c=document.createElement('canvas');c.width=64;c.height=64;const g=c.getContext('2d',{willReadFrequently:true});let best=-1,bx=0;const n=24;
    for(let i=0;i<n;i++){const x=(i+0.5)*E.dur/n;if(!E.p.segs.some(s=>s.keep&&x>=s.a&&x<s.b))continue;v.currentTime=x;await new Promise(r=>{v.onseeked=r;setTimeout(r,1200);});g.drawImage(v,0,0,64,64);const d=g.getImageData(0,0,64,64).data;
      let L=0,ed=0,sat=0;for(let y=0;y<64;y++)for(let xx=0;xx<64;xx++){const k=(y*64+xx)*4,l=0.3*d[k]+0.59*d[k+1]+0.11*d[k+2];L+=l;sat+=Math.max(d[k],d[k+1],d[k+2])-Math.min(d[k],d[k+1],d[k+2]);if(xx<63)ed+=Math.abs(l-(0.3*d[k+4]+0.59*d[k+5]+0.11*d[k+6]));}
      L/=4096;ed/=4032;sat/=4096;const sc=ed*2+sat*0.25-Math.abs(L-130)*0.15;if(sc>best){best=sc;bx=x;}G('edBar').style.width=Math.round(i/n*100)+'%';}
    V().currentTime=bx;await new Promise(r=>setTimeout(r,400));drawAt();toast(t('coverFound',{n:fm1(bx)}),3500);}catch(e){toast(t('seFail'));}
  prog.classList.remove('show');
}
/* short version: keep the liveliest stretches (sound + your markers) until the target length */
async function shortVersion(target){
  const prog=G('edProg');prog.classList.add('show');G('edProgTx').textContent=t('edAnAudio');
  try{const hop=0.5,e=await envOf(E.blob,hop);const srt=Array.from(e).sort((a,b)=>a-b),med=srt[Math.floor(srt.length/2)]||1e-4;
    const L=3,ch=[];for(let i=0;i+L/hop<=e.length;i+=L/hop){let sc=0;for(let j=i;j<i+L/hop;j++)sc+=Math.min(4,e[j]/med);const a=i*hop;(E.marks||[]).forEach(m=>{if(m>=a-1&&m<a+L+1)sc+=6;});
      if(E.p.segs.some(s=>s.keep&&s.a<a+L&&s.b>a))ch.push({a,b:Math.min(E.dur,a+L),sc});}
    ch.sort((a,b)=>b.sc-a.sc);const pick=[];let tot=0;for(const c of ch){if(tot>=target)break;pick.push(c);tot+=c.b-c.a;}pick.sort((a,b)=>a.a-b.a);
    snap();const segs=[];let x=0;pick.forEach(c=>{if(c.a>x)segs.push({a:x,b:c.a,keep:false,speed:1,zoom:false});segs.push({a:c.a,b:c.b,keep:true,speed:1,zoom:false,tin:'fade'});x=c.b;});if(x<E.dur)segs.push({a:x,b:E.dur,keep:false,speed:1,zoom:false});
    E.p.segs=segs;merge();save();toast(t('edShortDone',{n:Math.round(tot)}),3500);
  }catch(e){toast(t('seFail'));}
  prog.classList.remove('show');renderTl();renderPanel();drawAt();
}
/* follow the subject: where things move in the picture, so a sideways video can be cut to vertical (and back) */
async function trackSubject(){
  const prog=G('edProg');prog.classList.add('show');
  try{const v=document.createElement('video');v.src=E.url;v.muted=true;v.playsInline=true;await new Promise(r=>{v.onloadeddata=r;v.onerror=r;});
    const c=document.createElement('canvas');c.width=64;c.height=36;const g=c.getContext('2d',{willReadFrequently:true});let prev=null,sx=0.5,sy=0.5;const tr=[];const step=Math.max(0.5,E.dur/240);
    for(let x=0;x<E.dur;x+=step){if(!E)return;v.currentTime=x;await new Promise(r=>{v.onseeked=r;setTimeout(r,1200);});g.drawImage(v,0,0,64,36);const px=g.getImageData(0,0,64,36).data;
      if(prev){let wx=0,wy=0,ws=0;for(let i=0;i<64*36;i++){const d=Math.abs(px[i*4]-prev[i*4])+Math.abs(px[i*4+1]-prev[i*4+1]);if(d>40){wx+=(i%64)*d;wy+=Math.floor(i/64)*d;ws+=d;}}
        if(ws>2000){sx=sx*0.6+(wx/ws/63)*0.4;sy=sy*0.6+(wy/ws/35)*0.4;}}
      prev=px;tr.push({t:+x.toFixed(2),x:+sx.toFixed(3),y:+sy.toFixed(3)});G('edBar').style.width=Math.round(x/E.dur*100)+'%';G('edProgTx').textContent=t('edTrackRun',{n:Math.round(x/E.dur*100)});}
    E.p.track=tr;E.p.follow=true;save();toast(t('edTrackDone'));
  }catch(e){toast(t('seFail'));}
  prog.classList.remove('show');renderPanel();drawAt();
}
const SFX=['whoosh','ding','pop','clap','cash','drum'];
function sfxPlay(A,dst,kind,at,vol){
  const g=A.createGain();g.gain.value=(vol==null?80:vol)/100;g.connect(dst);
  const noise=dur=>{const b=A.createBuffer(1,Math.ceil(A.sampleRate*dur),A.sampleRate),d=b.getChannelData(0);for(let i=0;i<d.length;i++)d[i]=Math.random()*2-1;const s=A.createBufferSource();s.buffer=b;return s;};
  const tone=(f,type,t0,dur,v)=>{const o=A.createOscillator(),e=A.createGain();o.type=type;o.frequency.value=f;e.gain.setValueAtTime(0.0001,t0);e.gain.exponentialRampToValueAtTime(v,t0+0.01);e.gain.exponentialRampToValueAtTime(0.0001,t0+dur);o.connect(e);e.connect(g);o.start(t0);o.stop(t0+dur+0.05);return o;};
  if(kind==='whoosh'){const n=noise(0.7),f=A.createBiquadFilter(),e=A.createGain();f.type='bandpass';f.Q.value=1.2;f.frequency.setValueAtTime(300,at);f.frequency.exponentialRampToValueAtTime(4000,at+0.35);f.frequency.exponentialRampToValueAtTime(800,at+0.65);
    e.gain.setValueAtTime(0.0001,at);e.gain.exponentialRampToValueAtTime(0.9,at+0.3);e.gain.exponentialRampToValueAtTime(0.0001,at+0.68);n.connect(f);f.connect(e);e.connect(g);n.start(at);n.stop(at+0.7);}
  else if(kind==='ding'){tone(1318,'sine',at,1.2,0.5);tone(2637,'sine',at,0.6,0.15);}
  else if(kind==='pop'){const o=tone(600,'sine',at,0.12,0.7);o.frequency.setValueAtTime(900,at);o.frequency.exponentialRampToValueAtTime(200,at+0.1);}
  else if(kind==='clap'){for(let k=0;k<14;k++){const t0=at+k*0.07+Math.random()*0.03,n=noise(0.08),f=A.createBiquadFilter(),e=A.createGain();f.type='bandpass';f.frequency.value=1500+Math.random()*800;e.gain.setValueAtTime(0.6,t0);e.gain.exponentialRampToValueAtTime(0.0001,t0+0.07);n.connect(f);f.connect(e);e.connect(g);n.start(t0);n.stop(t0+0.08);}}
  else if(kind==='cash'){tone(2093,'triangle',at,0.25,0.4);tone(2637,'triangle',at+0.09,0.5,0.4);const n=noise(0.15),f=A.createBiquadFilter(),e=A.createGain();f.type='highpass';f.frequency.value=3000;e.gain.setValueAtTime(0.3,at);e.gain.exponentialRampToValueAtTime(0.0001,at+0.15);n.connect(f);f.connect(e);e.connect(g);n.start(at);n.stop(at+0.15);}
  else if(kind==='drum'){for(let k=0;k<20;k++){const t0=at+k*0.05,n=noise(0.05),f=A.createBiquadFilter(),e=A.createGain();f.type='bandpass';f.frequency.value=900;e.gain.setValueAtTime(0.25+k*0.02,t0);e.gain.exponentialRampToValueAtTime(0.0001,t0+0.045);n.connect(f);f.connect(e);e.connect(g);n.start(t0);n.stop(t0+0.05);}
    const o=tone(80,'sine',at+1.0,0.5,0.9);o.frequency.setValueAtTime(120,at+1.0);o.frequency.exponentialRampToValueAtTime(50,at+1.4);}
}
let sfxLastX=-1;
function sfxSync(x){if(!E.p.sfx||!E.p.sfx.length){sfxLastX=x;return;}if(sfxLastX>=0&&x>sfxLastX&&x-sfxLastX<0.5){E.p.sfx.forEach(q=>{if(q.t>sfxLastX&&q.t<=x)sfxPlay(actx,E.g.out,q.kind,actx.currentTime,q.vol);});}sfxLastX=x;}
/* custom music file in the editor */
async function musicLoad(){if(E.p.music!=='custom'||E.musEl)return;try{const d=await editsDir();const f=await (await d.getFileHandle(E.name.replace(/[^\w.-]+/g,'_')+'.music')).getFile();musicSet(f);}catch(e){}}
function musicSet(blob){if(E.musEl)freeAudio(E.musEl);const a=new Audio();a.src=URL.createObjectURL(blob);a.loop=true;a.preload='auto';try{const s=actx.createMediaElementSource(a);s.connect(musicGain);a._node=s;}catch(e){}E.musEl=a;E.musBlob=blob;}
function musicPlay(x){if(E.p.music==='custom'&&E.musEl){const d=E.musEl.duration||0;E.musEl.currentTime=d?outPos(x)%d:0;E.musEl.play().catch(()=>{});}else if(MUSIC[E.p.music]){if(!mTimer){const keep=$('musicSel').value;$('musicSel').value=E.p.music;startMusic(false);$('musicSel').value=keep;}}}
function musicStop(){if(E&&E.musEl)E.musEl.pause();stopMusic();}
/* reverse and boomerang: the part's frames are kept in memory and played back */
async function buildFx(sg){
  E.fxCache=E.fxCache||{};const key2=sg.a.toFixed(2)+'_'+sg.b.toFixed(2);if(E.fxCache[key2])return E.fxCache[key2];
  const v=document.createElement('video');v.src=E.url;v.muted=true;v.playsInline=true;await new Promise(r=>{v.onloadeddata=r;v.onerror=r;});
  const [vw,vh]=dimsOf(v),k=Math.min(1,640/Math.max(vw,vh)),w=Math.round(vw*k),h=Math.round(vh*k),c=document.createElement('canvas');c.width=w;c.height=h;const g=c.getContext('2d');
  const fps=20,n=Math.max(2,Math.round((sg.b-sg.a)*fps)),fr=[];const prog=G('edProg');prog.classList.add('show');
  for(let i=0;i<n;i++){v.currentTime=sg.a+i/fps;await new Promise(r=>{v.onseeked=r;setTimeout(r,1000);});g.drawImage(v,0,0,w,h);fr.push(await createImageBitmap(c));G('edBar').style.width=Math.round(i/n*100)+'%';G('edProgTx').textContent=t('fxBuild',{n:Math.round(i/n*100)});}
  prog.classList.remove('show');E.fxCache[key2]=fr;return fr;
}
function fxFrame(sg,p){const fr=E.fxCache&&E.fxCache[sg.a.toFixed(2)+'_'+sg.b.toFixed(2)];if(!fr||!fr.length)return null;const n=fr.length;let i;
  if(sg.fx==='rev')i=n-1-Math.min(n-1,Math.floor(p*n));else{const q=p*2;i=q<1?Math.min(n-1,Math.floor(q*n)):n-1-Math.min(n-1,Math.floor((q-1)*n));}return{img:fr[i],x:sg.a+(i/n)*(sg.b-sg.a)};}
/* runs a reverse/boomerang part: returns a promise when done; drawing each frame */
function runFx(sg,onFrame){return new Promise(res=>{const dur=partLen(sg)*1000,t0=performance.now();const f=()=>{if(!E)return res();const p=(performance.now()-t0)/dur;if(p>=1)return res();const q=fxFrame(sg,p);if(q){composeAt(q.img,q.x);uiAt(q.x);}if(onFrame)onFrame(p);requestAnimationFrame(f);};f();});}
/* ---------- playback that skips removed parts ---------- */
function setRate(){const s=segAt(V().currentTime);if(s)V().playbackRate=s.speed||1;}
function tick(){
  if(!E)return;const v=V();
  if(E.frozenUntil){if(performance.now()<E.frozenUntil){drawAt();if(E.playing)E.raf=requestAnimationFrame(tick);return;}E.frozenUntil=0;v.currentTime=E.frozenNext;v.play().catch(()=>{});}
  let s=segAt(v.currentTime);
  if(E.curSeg&&s!==E.curSeg&&E.curSeg.keep){const was=E.curSeg;
    if(was.freeze&&!E.frozeDone){E.frozeDone=true;v.pause();E.frozenUntil=performance.now()+was.freeze*1000;E.frozenNext=v.currentTime;drawAt();E.raf=requestAnimationFrame(tick);return;}
    E.frozeDone=false;grabPrev(s.keep?s.a:(nextKeptFrom(s.b)??-1));}
  if(s&&!s.keep){const n=nextKeptFrom(s.b);if(n===null){edPause();if(E.onEnd)E.onEnd();return;}v.currentTime=n;s=segAt(n);dnStart(n,s?s.speed:1);}
  if(s&&s.keep&&s.fx&&s.fx!=='none'&&E.fxDone!==s&&!E.fxRun){E.fxRun=true;v.pause();dnStop();const sg=s;
    runFx(sg).then(()=>{if(!E)return;E.fxRun=false;E.fxDone=sg;E.curSeg=sg;v.currentTime=Math.min(E.dur,sg.b+0.01);if(E.playing){v.play().catch(()=>{});dnStart(sg.b,1);E.raf=requestAnimationFrame(tick);}});return;}
  if(s!==E.curSeg)E.fxDone=null;
  E.curSeg=s;
  setRate();angleSync(true,s?s.speed:1);voiceSync();sfxSync(v.currentTime);duckTick();
  const nw=performance.now();if(!E.lastDrawT||nw-E.lastDrawT>=32){E.lastDrawT=nw;drawAt();}
  if(E.playing)E.raf=requestAnimationFrame(tick);
}
window.edToggle=function(){if(E.playing)edPause();else edPlay();};
function edPlay(fromStart){
  const v=V();if(fromStart)v.currentTime=firstKept();
  const n=nextKeptFrom(v.currentTime);if(n===null)v.currentTime=firstKept();else v.currentTime=n;
  if(actx.state==='suspended')actx.resume();
  E.curSeg=segAt(v.currentTime);E.frozeDone=false;E.frozenUntil=0;setTimeout(()=>{if(E&&E.playing)dnStart(V().currentTime,V().playbackRate);},30);
  E.playing=true;G('edPlay').textContent=t('tpPause');v.play().catch(()=>{});
  musicPlay(v.currentTime);sfxLastX=v.currentTime;E.fxDone=null;E.fxRun=false;
  cancelAnimationFrame(E.raf);E.raf=requestAnimationFrame(tick);
}
function edPause(){if(!E)return;E.playing=false;E.frozenUntil=0;dnStop();const v=V();v.pause();try{angleSync(false);}catch(e){}musicStop();Object.values(E.voiceEls).forEach(a=>a.pause());G('edPlay').textContent=t('sePlay');drawAt();}
window.edPause=edPause;
V().addEventListener('ended',()=>{if(E&&E.playing){edPause();if(E.onEnd)E.onEnd();}});
V().addEventListener('seeked',()=>{if(E&&!E.playing){try{angleSync(false);}catch(e){}drawAt();}});
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
  try{v.removeAttribute('src');v.load();}catch(e){}
}
function renderTl(){
  const box=G('edSegs');box.innerHTML='';
  E.p.segs.forEach(s=>{const d=document.createElement('div');d.className='ed-seg'+(s.keep?'':' off');d.style.left=(s.a/E.dur*100)+'%';d.style.width=((s.b-s.a)/E.dur*100)+'%';
    if(s.keep&&(s.speed!==1||s.zoom)){const b=document.createElement('span');b.className='sp';b.textContent=(s.speed!==1?s.speed+'x':'')+(s.zoom?' ⤢':'');d.appendChild(b);}box.appendChild(d);});
  (E.p.sugs||[]).filter(g=>g.st==='new').forEach(g=>{const d=document.createElement('div');d.className='ed-seg sug';d.style.left=(g.a/E.dur*100)+'%';d.style.width=((g.b-g.a)/E.dur*100)+'%';d.style.borderLeft='0';box.appendChild(d);});
}
(function(){const tl=G('edTl');let down=false;
  /* one seek at a time (the next waits for the last to land), so dragging stays smooth */
  let want=null,busy=false;const v=V();
  const seek=()=>{if(want==null||busy)return;busy=true;const x=want;want=null;v.currentTime=x;};
  v.addEventListener('seeked',()=>{busy=false;if(want!=null)seek();});
  const go=e=>{if(!E)return;const r=tl.getBoundingClientRect(),x=clamp((e.clientX-r.left)/r.width,0,1)*E.dur;want=x;uiAt(x);seek();};
  const end=()=>{if(!down)return;down=false;if(!E)return;setTimeout(()=>{if(!E)return;if(E.playing){const x=v.currentTime,s=segAt(x);dnStart(x,s?s.speed:1);musicPlay(x);sfxLastX=x;}else drawAt();renderPanel();},120);};
  tl.addEventListener('pointerdown',e=>{if(e.target.closest&&e.target.closest('[data-k]'))return;down=true;tl.setPointerCapture(e.pointerId);go(e);});tl.addEventListener('pointermove',e=>{if(down)go(e);});tl.addEventListener('pointerup',end);tl.addEventListener('pointercancel',end);})();

CV().addEventListener('click',e=>{if(!E||E.tab!=='sticker')return;const sk=(E.p.stickers||[]).find(q=>q.id===E.sel);if(!sk)return;const r=CV().getBoundingClientRect();sk.x=clamp((e.clientX-r.left)/r.width,0,1);sk.y=clamp((e.clientY-r.top)/r.height,0,1);save();drawAt();});
/* ---------- tools ---------- */
const TABS=['tpl','bg','cut','brand','color','angle','sug','text','steps','sticker','voice','music','sfx','fmt','cover'];
/* five simple groups; a group with more than one tool shows small chips underneath */
const TGROUPS=[['tpl',['tpl']],['g_cut',['cut','sug']],['g_text',['text','steps','sticker','cover']],['g_sound',['music','voice','sfx']],['g_look',['color','bg','brand','fmt']]];
const GICON={tpl:'<path d="M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z"/>',g_cut:'<circle cx="6" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M8.1 8.1L20 20M8.1 15.9L20 4"/>',g_text:'<path d="M5 6V4h14v2M12 4v16M9 20h6"/>',g_sound:'<path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',g_look:'<circle cx="12" cy="12" r="9"/><circle cx="8" cy="10" r="1.3"/><circle cx="12" cy="7.5" r="1.3"/><circle cx="16" cy="10" r="1.3"/><path d="M12 21a3 3 0 0 1 0-6h1.5a2.5 2.5 0 0 0 0-5"/>'};
window.edPanelClose=()=>{if(!E)return;E.pop=false;renderTabs();};
function renderTabs(){const b=G('edTabs');b.innerHTML='';const cur=TGROUPS.find(g=>g[1].includes(E.tab))||TGROUPS[0];
  G('edScreen').classList.toggle('pop',!!E.pop);
  TGROUPS.forEach(([g,ks])=>{const x=document.createElement('button');x.type='button';x.className=E.pop&&cur[0]===g?'on':'';
    x.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+GICON[g]+'</svg><span></span>';x.querySelector('span').textContent=t('edG_'+g);
    x.onclick=()=>{if(E.pop&&cur[0]===g){edPanelClose();return;}if(cur[0]!==g)E.tab=(E.lastIn&&E.lastIn[g])||ks[0];E.pop=true;renderTabs();renderPanel();const bd=document.querySelector('#edScreen .ed-body');if(bd)bd.scrollTop=0;};b.appendChild(x);});
  const sb=G('edSub');sb.innerHTML='';
  if(cur[1].length>1)cur[1].forEach(k=>{const x=document.createElement('button');x.type='button';x.textContent=t('edT_'+k);x.className=E.tab===k?'on':'';x.onclick=()=>{E.tab=k;(E.lastIn=E.lastIn||{})[cur[0]]=k;renderTabs();renderPanel();};sb.appendChild(x);});
  else{const h=document.createElement('b');h.textContent=t('edG_'+cur[0]);sb.appendChild(h);}
}
function btn(label,fn,cls){const b=document.createElement('button');b.className='btn btn-sm '+(cls||'');b.textContent=label;b.onclick=fn;return b;}
/* ---------- pick times on the strip (like other editors): tap 📍, then drag the gold handles on the timeline ---------- */
function selRange(o,mode,list){E.rsel={o,mode,list};renderRange();renderPanel();toast(t(mode==='range'?'rngHint':'rngHint1'),3500);}
function rselAlive(){const r=E&&E.rsel;if(!r)return false;if(r.list==='_cut')return r.o===E.cutSel&&E.tab==='cut';if(r.list==='_tts')return r.o===E.ttsPos&&E.tab==='voice';return (E.p[r.list]||[]).includes(r.o);}
function renderRange(){const L=G('edRng');if(!L)return;if(!rselAlive()||!E.dur){L.classList.remove('on');L.innerHTML='';return;}
  const {o,mode}=E.rsel,D=E.dur,pc=x=>(clamp(x,0,D)/D*100)+'%';L.classList.add('on');L.innerHTML='';
  const a=mode==='range'?o.s:o.t,b=mode==='range'?o.e:mode==='len'?o.t+(o.d||0):null;
  if(b!=null){const bd=document.createElement('div');bd.className='rng-body';bd.dataset.k='move';bd.style.left=pc(a);bd.style.width=((Math.min(b,D)-a)/D*100)+'%';L.appendChild(bd);}
  const hd=(x,k)=>{const h=document.createElement('div');h.className='rng-h';h.dataset.k=k;h.style.left=pc(x);L.appendChild(h);const lb=document.createElement('span');lb.className='rng-lb';lb.style.left=pc(x);lb.textContent=fm1(x);L.appendChild(lb);};
  if(mode==='range'){hd(a,'s');hd(b,'e');}else if(mode==='point')hd(a,'t');else{const lb=document.createElement('span');lb.className='rng-lb';lb.style.left=pc(a);lb.textContent=fm1(a);L.appendChild(lb);}
}
(function(){const L=G('edRng'),tl=G('edTl');let dr=null;
  L.addEventListener('pointerdown',e=>{const el=e.target.closest('[data-k]');if(!el||!rselAlive())return;e.stopPropagation();e.preventDefault();if(E.rsel.list!=='_cut')snap();
    const r=tl.getBoundingClientRect(),o=E.rsel.o;dr={k:el.dataset.k,x0:e.clientX,w:r.width,left:r.left,a0:E.rsel.mode==='range'?o.s:o.t,b0:E.rsel.mode==='range'?o.e:(o.t+(o.d||0))};});
  /* follow the finger anywhere on screen while dragging (handles are redrawn as they move) */
  window.addEventListener('pointermove',e=>{if(!dr||!rselAlive())return;const {o,mode}=E.rsel,D=E.dur,x=clamp((e.clientX-dr.left)/dr.w,0,1)*D,dx=(e.clientX-dr.x0)/dr.w*D;let show;
    if(dr.k==='s'){o.s=+clamp(x,0,o.e-0.2).toFixed(2);show=o.s;}else if(dr.k==='e'){o.e=+clamp(x,o.s+0.2,D).toFixed(2);show=o.e;}
    else if(dr.k==='t'){o.t=+clamp(x,0,D).toFixed(2);show=o.t;}
    else{const len=dr.b0-dr.a0,na=clamp(dr.a0+dx,0,Math.max(0,D-len));if(mode==='range'){o.s=+na.toFixed(2);o.e=+(na+len).toFixed(2);}else o.t=+na.toFixed(2);show=na;}
    const v=V();if(!E.playing){v.currentTime=show;}uiAt(show);renderRange();});
  const up=()=>{if(!dr)return;dr=null;save();renderPanel();drawAt();renderTl();};window.addEventListener('pointerup',up);window.addEventListener('pointercancel',up);})();
function timeBtn(o,mode,list){const on=E.rsel&&E.rsel.o===o,a=mode==='range'?o.s:o.t;
  const lbl=mode==='range'?fm1(o.s)+' – '+fm1(o.e):mode==='len'?fm1(o.t)+' – '+fm1(o.t+(o.d||0)):fm1(o.t);
  return row(btn('▶',()=>{V().currentTime=a;drawAt();uiAt(a);}),btn('📍 '+lbl,()=>selRange(o,mode,list),on?'btn-gold':''));}
function row(...els){const d=document.createElement('div');d.className='ed-row';els.forEach(e=>e&&d.appendChild(e));return d;}
function hint(txt){const s=document.createElement('span');s.className='hint';s.textContent=txt;return s;}
function sel(opts,val,fn){const s=document.createElement('select');s.className='input';opts.forEach(([v,l])=>{const o=document.createElement('option');o.value=v;o.textContent=l;s.appendChild(o);});s.value=val;s.onchange=()=>fn(s.value);return s;}
function range(min,max,val,fn,step){const r=document.createElement('input');r.type='range';r.min=min;r.max=max;r.step=step||1;r.value=val;r.style.flex='1';r.oninput=()=>fn(Number(r.value));return r;}
function changed(){merge();save();renderTl();drawAt();}
/* ---------- one-tap video templates: trim, zoom moves, text, stickers, music, look and size in one go ---------- */
const VTPL=[
  {id:'promo',ic:'🏷️',L:15,k:5,music:'edm',look:'pop',kb:true,brand:true,stk:'promo',title:{style:'wordpop',font:'luckiest',col:'yellow',size:9},cta:'cta1'},
  {id:'resepi',ic:'🍳',L:30,k:6,music:'santai',look:'warm',steps:true,title:{style:'gradient',font:'fredoka',col:'orange',size:8},end:'end1'},
  {id:'produk',ic:'✨',L:20,k:5,music:'korporat',look:'cine',kb:true,stk:'new',title:{style:'neon',font:'righteous',col:'cyan',size:8},cta:'cta1'},
  {id:'testi',ic:'⭐',L:0,music:'romantik',musVol:18,look:'soft',top:{txt:'topTesti',style:'highlight',font:'baloo',col:'yellow',size:6}},
  {id:'menu',ic:'🍽️',L:15,k:5,music:'ceria',look:'pop',labels:true,stk:'hot',title:{style:'bubble',font:'fredoka',col:'red',size:8},cta:'cta1'},
  {id:'event',ic:'📣',L:10,k:4,music:'sinematik',look:'drama',kb:true,title:{style:'d3',font:'archivo',col:'gold',size:10},end:'end2'},
  {id:'before',ic:'🔁',L:12,k:2,music:'hiphop',look:'std',ba:true},
  {id:'vlog',ic:'🎒',L:60,k:12,music:'lofi',look:'soft',title:{style:'write',font:'caveat',col:'white',size:9}}
];
function applyVtpl(tp){
  snap();const D=E.dur,p=E.p,bv=id=>{const el=document.getElementById(id);return el?String(el.value||'').trim():'';};
  /* 1) pick the parts: evenly spread highlights (or the whole video, lightly trimmed) */
  const keep=[];
  if(!tp.L||D<=tp.L*1.1){keep.push([Math.min(0.3,D*0.05),Math.max(D-0.3,D*0.95)]);}
  else{const c=tp.L/tp.k;for(let i=0;i<tp.k;i++){const m=D*(i+0.5)/tp.k;let a=Math.max(0,m-c/2),b=Math.min(D,a+c);a=Math.max(0,b-c);keep.push([+a.toFixed(2),+b.toFixed(2)]);}}
  const segs=[];let at=0;const KBM=['center','left','right','top','bottom'];
  keep.forEach(([a,b],i)=>{if(a>at+0.01)segs.push({a:at,b:a,keep:false,speed:1,zoom:false});
    segs.push({a,b,keep:true,speed:1,zoom:false,kb:tp.kb?{from:i%2?'wide':KBM[(i/2|0)%KBM.length],to:i%2?KBM[(i/2|0)%KBM.length]:'wide'}:null});at=b;});
  if(at<D-0.01)segs.push({a:at,b:D,keep:false,speed:1,zoom:false});
  p.segs=segs;p.sugs=[];
  const K=segs.filter(q=>q.keep),first=K[0],last=K[K.length-1],T=(a,b,o)=>Object.assign({id:Date.now()+Math.random(),s:+a.toFixed(2),e:+b.toFixed(2),anim:'pop',pos:'m',cs:'title',size:8},o);
  /* 2) words on screen */
  p.texts=[];p.stickers=p.stickers||[];p.stickers=p.stickers.filter(q=>!q.tplMade);p.steps=p.steps||[];
  if(tp.title){const txt=bv('titleTxt')||t('vtTitlePh');p.texts.push(T(first.a,Math.min(first.b,first.a+2.6),Object.assign({txt,pos:'t'},tp.title)));}
  if(tp.top)p.texts.push(T(first.a,Math.min(first.b,first.a+4),Object.assign({pos:'t'},tp.top,{txt:t(tp.top.txt)})));
  if(tp.cta){const ph=bv('phoneTxt'),pr=bv('priceTxt'),own=!(tp.brand||p.brand);/* the brand card already shows price and phone */const txt=t('vtCta')+(own&&pr?'\n'+pr:'')+(own&&ph?'\n'+ph:'');p.texts.push(T(Math.max(last.a,last.b-2.6),last.b,{txt,style:'bubble',font:'baloo',col:'gold',size:8,pos:'m',anim:'pop'}));}
  if(tp.end)p.texts.push(T(Math.max(last.a,last.b-2.4),last.b,{txt:t(tp.end==='end1'?'vtEnd1':'vtEnd2'),style:'write',font:'caveat',col:'white',size:9,pos:'m',anim:'fade'}));
  if(tp.labels)K.forEach((q,i)=>{if(i===0||i===K.length-1)return;p.texts.push(T(q.a,q.b,{txt:t('vtMenuN',{n:i}),style:'bubble',font:'fredoka',col:'white',size:7,pos:'b',anim:'slide'}));});
  if(tp.ba&&K.length>=2){p.texts.push(T(K[0].a,K[0].b,{txt:t('vtBefore'),style:'retro',font:'bangers',col:'pink',size:10,pos:'t',anim:'pop',cs:'upper'}));p.texts.push(T(K[1].a,K[1].b,{txt:t('vtAfter'),style:'retro',font:'bangers',col:'green',size:10,pos:'t',anim:'pop',cs:'upper'}));}
  if(tp.stk)p.stickers.push({id:Date.now()+1,kind:tp.stk,x:0.8,y:0.22,s:first.a,e:+Math.min(first.b,first.a+3).toFixed(2),size:14,col:'gold',rot:-8,tplMade:1});
  p.steps=[];if(tp.steps){p.steps=K.map((q,i)=>({id:Date.now()+i,t:+q.a.toFixed(2),txt:t('vtStepN',{n:i+1})}));p.stepBar=true;}
  /* 3) sound, colour, size */
  p.music=tp.music;p.musVol=tp.musVol||35;p.duck=true;p.look=tp.look||'std';p.fmt='p';if(tp.brand)p.brand=true;
  try{if(localStorage.getItem('pmc_sub_'+E.name))p.subs=true;}catch(e){}
  p.tpl=tp.id;
  if(typeof sizeCanvas==='function')sizeCanvas();changed();renderPanel();toast(t('vtDone'),4500);
}
function renderPanel(){
  setTimeout(()=>{document.querySelectorAll('#edPanel .ed-card').forEach(c=>c.classList.toggle('rsel',!!c.querySelector('.ed-row .btn-gold')&&!!(E&&E.rsel)&&[...c.querySelectorAll('.btn-gold')].some(b=>b.textContent.startsWith('📍'))));renderRange();},0);
  const P=G('edPanel');P.innerHTML='';const x=V().currentTime,s=segAt(x),k=E.tab;
  if(k==='tpl'){
    P.appendChild(hint(t('vtplHint')));
    const g=document.createElement('div');g.className='vtpl-grid';
    VTPL.forEach(tp=>{const b=document.createElement('button');b.type='button';b.className='vtpl'+(E.p.tpl===tp.id?' on':'');
      b.innerHTML='<i></i><b></b><span></span>';b.querySelector('i').textContent=tp.ic;b.querySelector('b').textContent=t('vt_'+tp.id);
      b.querySelector('span').textContent=(tp.L?t('vtLen',{n:tp.L}):t('vtFull'))+' · '+t('m_'+tp.music);
      b.onclick=()=>ask(t('vt_'+tp.id),t('vtAsk'),t('vtApply'),()=>applyVtpl(tp));g.appendChild(b);});
    P.appendChild(g);
    if(E.hist.length)P.appendChild(row(btn('↶ '+t('vtUndo'),()=>{if(typeof edUndo==='function')edUndo();})));
    return;
  }
  if(k==='cut'){
    if(!E.cutSel||E.cutSel.e>E.dur){const a=clamp(V().currentTime,0,Math.max(0,E.dur-0.5));E.cutSel={s:+a.toFixed(2),e:+Math.min(E.dur,a+Math.max(1,E.dur*0.15)).toFixed(2)};}
    if(!E.rsel||E.rsel.o!==E.cutSel)E.rsel={o:E.cutSel,mode:'range',list:'_cut'};
    {const c=document.createElement('div');c.className='ed-card';c.appendChild(hint(t('cutHint')));
      c.appendChild(row(btn('✂️ '+t('cutDo',{a:fm1(E.cutSel.s),b:fm1(E.cutSel.e)}),()=>{snap();removeRange(E.cutSel.s,E.cutSel.e);const n=nextKeptFrom(E.cutSel.e);E.cutSel=null;changed();if(n!=null)V().currentTime=n;toast(t('cutDone'),3000);renderPanel();},'btn-danger'),
        btn('＋ '+t('cutNew'),()=>{const a=clamp(V().currentTime,0,Math.max(0,E.dur-0.5));E.cutSel={s:+a.toFixed(2),e:+Math.min(E.dur,a+Math.max(1,E.dur*0.15)).toFixed(2)};E.rsel=null;renderPanel();renderRange();}),
        btn('↶ '+t('edUndo'),()=>{edUndo();})));
      P.appendChild(c);}
  }
  if(k==='bg'){
    P.appendChild(hint(t('bgT')));
    const g=document.createElement('div');g.className='bg-grid';
    const opts=[['none',t('bg_none'),''],['blur',t('bg_blur'),'linear-gradient(135deg,#6a7a8a,#3a4450)'],['dark',t('bg_dark'),'#111']].concat(BG_IMGS.map(n=>['img:'+n,t('bgi_'+n),`url(${bgSrc(n)}) center/cover`]));
    opts.forEach(([v,l,bgcss])=>{const b=document.createElement('button');b.type='button';b.className='bg-opt'+((E.p.bg||'none')===v?' on':'');b.innerHTML='<i></i><span></span>';b.querySelector('i').style.background=bgcss||'repeating-linear-gradient(45deg,#2a2a2d 0 8px,#1f1f21 8px 16px)';b.querySelector('span').textContent=l;b.onclick=()=>bgEnable(v);g.appendChild(b);});
    P.appendChild(g);P.appendChild(hint(t('bgHint')));
  }
  if(k==='color'){
    const g=E.p.col=E.p.col||{b:0,c:0,s:0,t:0};
    const sl=(lab,o,kk,lo,hi)=>row(hint(t(lab)),range(lo,hi,o[kk]||0,v=>{o[kk]=v;save();drawAt();}));
    P.appendChild(hint(t('colAll')));P.appendChild(sl('colB',g,'b',-50,50));P.appendChild(sl('colC',g,'c',-50,50));P.appendChild(sl('colS',g,'s',-100,100));P.appendChild(sl('colT',g,'t',-100,100));
    P.appendChild(row(btn(t('colReset'),()=>{snap();E.p.col={b:0,c:0,s:0,t:0};E.p.angCol={};save();renderPanel();drawAt();})));
    if(E.angles&&E.angles.length){P.appendChild(row(btn(t('colMatch'),matchColours,'btn-gold')));P.appendChild(hint(t('colMatchHint')));
      E.angles.forEach(a=>{const o=E.p.angCol[a.label]=E.p.angCol[a.label]||{b:0,c:0,s:0,t:0};const cd=document.createElement('div');cd.className='ed-card';cd.appendChild(hint(a.label));cd.appendChild(sl('colB',o,'b',-50,50));cd.appendChild(sl('colT',o,'t',-100,100));P.appendChild(cd);});}
  }
  if(k==='steps'){
    P.appendChild(hint(t('stepHint')));
    P.appendChild(row(btn(t('stepAdd'),()=>{snap();{const q={id:Date.now(),t:+V().currentTime.toFixed(2),txt:''};E.p.steps.push(q);E.rsel={o:q,mode:'point',list:'steps'};}save();renderRange();renderPanel();drawAt();},'btn-gold'),(E.marks&&E.marks.length)?btn(t('stepFromMarks'),()=>{snap();E.marks.forEach(m=>{if(!E.p.steps.some(q=>Math.abs(q.t-m)<0.5))E.p.steps.push({id:Date.now()+m,t:m,txt:''});});save();renderPanel();drawAt();}):null));
    const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('stepBar');const i=cb.querySelector('input');i.checked=E.p.stepBar!==false;i.onchange=()=>{E.p.stepBar=i.checked;save();drawAt();};P.appendChild(cb);
    E.p.steps.slice().sort((a,b)=>a.t-b.t).forEach((q,n)=>{const c=document.createElement('div');c.className='ed-card';
      const inp=document.createElement('input');inp.className='input';inp.maxLength=40;inp.value=q.txt;inp.placeholder=t('stepPh');inp.oninput=()=>{q.txt=inp.value;save();drawAt();};
      c.appendChild(row(hint(t('stepN',{n:n+1,m:E.p.steps.length})),timeBtn(q,'point','steps'),btn('✕',()=>{snap();E.p.steps=E.p.steps.filter(z=>z!==q);save();renderPanel();drawAt();},'btn-danger')));c.appendChild(inp);P.appendChild(c);});
  }
  if(k==='sfx'){
    P.appendChild(hint(t('sfxHint')));
    P.appendChild(row(...SFX.map(kd=>btn('+ '+t('sfx_'+kd),()=>{ensureAudio();sfxPlay(actx,E.g.out,kd,actx.currentTime,80);snap();{const q={id:Date.now(),t:+V().currentTime.toFixed(2),kind:kd,vol:80};E.p.sfx.push(q);E.rsel={o:q,mode:'point',list:'sfx'};renderRange();}save();renderPanel();},'btn-gold'))));
    E.p.sfx.slice().sort((a,b)=>a.t-b.t).forEach(q=>{const c=document.createElement('div');c.className='ed-card';
      c.appendChild(row(hint(t('sfx_'+q.kind)),timeBtn(q,'point','sfx'),btn('✕',()=>{snap();E.p.sfx=E.p.sfx.filter(z=>z!==q);save();renderPanel();},'btn-danger')));
      c.appendChild(row(hint(t('sfxVol')),range(0,150,q.vol,v=>{q.vol=v;save();})));P.appendChild(c);});
  }
  if(k==='brand'){
    {const c=document.createElement('div');c.className='ed-card';c.appendChild(hint(t('preT')));
      const pl=presets(),dId=localStorage.getItem('pmc_preset_def');
      pl.forEach(pr=>c.appendChild(row(btn(pr.name+(pr.id===dId?' ★':''),()=>{snap();presetApply(E.p,pr);save();sizeCanvas();applyEnh();renderAll();toast(t('preUsed',{n:pr.name}));},'btn-gold'),
        btn(pr.id===dId?t('preUndef'):t('preDef'),()=>{if(pr.id===dId)localStorage.removeItem('pmc_preset_def');else localStorage.setItem('pmc_preset_def',pr.id);renderPanel();}),
        btn('✕',()=>{presetsSave(presets().filter(z=>z.id!==pr.id));if(pr.id===dId)localStorage.removeItem('pmc_preset_def');renderPanel();},'btn-danger'))));
      const nm=document.createElement('input');nm.className='input';nm.maxLength=24;nm.placeholder=t('prePh');
      c.appendChild(row(nm,btn(t('preSave'),()=>{const n=nm.value.trim()||t('preName',{n:presets().length+1});const l=presets();l.push(presetFromNow(n));presetsSave(l);renderPanel();toast(t('preSaved',{n}));})));
      c.appendChild(hint(t('preHint')));P.appendChild(c);}
    P.appendChild(row(hint(t('edLook')),sel(LOOKS.map(l=>[l.id,t('l_'+l.id)]),E.p.look||'std',v=>{E.p.look=v;save();drawAt();})));
    const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('edBrand');const i=cb.querySelector('input');i.checked=!!E.p.brand;i.onchange=()=>{E.p.brand=i.checked;if(i.checked)bvInit();save();drawAt();renderPanel();};P.appendChild(cb);
    if(E.p.brand){
      bvInit();if(E.fromClean)P.appendChild(hint(t('cleanEd')));
      const fld=(lab,id,ph)=>{const l=document.createElement('label');l.className='lbl';l.innerHTML='<span></span>';l.querySelector('span').textContent=t(lab);const inp=document.createElement('input');inp.className='input';inp.value=E.p.bv[id]||'';inp.placeholder=t(ph);inp.oninput=()=>{E.p.bv[id]=inp.value;save();drawAt();};l.appendChild(inp);return l;};
      P.appendChild(fld('titleL','titleTxt','titlePh'));P.appendChild(fld('price','priceTxt','pricePh'));P.appendChild(fld('phone','phoneTxt','phonePh'));
      P.appendChild(row(hint(t('ovT')),sel(OV_STYLES.map(k2=>[k2,t('ov_'+k2)]),E.p.bv.ovStyle||'card',v=>{E.p.bv.ovStyle=v;E.p.bv.ovFont=OV_DEF_FONT[v];save();try{document.fonts.load(`${OV_FONTS[OV_DEF_FONT[v]].w} 30px ${OV_FONTS[OV_DEF_FONT[v]].fam}`).then(drawAt);}catch(e){}drawAt();renderPanel();})));
      P.appendChild(row(sel(Object.keys(OV_COLORS).map(cc=>[cc,t('col_'+cc)]),E.p.bv.ovColor||'gold',v=>{E.p.bv.ovColor=v;save();drawAt();}),sel(Object.keys(OV_FONTS).map(f=>[f,t('font_'+f)]),E.p.bv.ovFont||'bricolage',v=>{E.p.bv.ovFont=v;save();try{document.fonts.load(`${OV_FONTS[v].w} 30px ${OV_FONTS[v].fam}`).then(drawAt);}catch(e){}drawAt();})));
      P.appendChild(hint(t('edBrandHint')));
    }
  }
  if(k==='angle'){
    const lab=document.createElement('label');lab.className='btn btn-sm btn-gold';lab.style.cursor='pointer';lab.innerHTML='<span></span><input type="file" accept="video/*" multiple style="display:none">';lab.querySelector('span').textContent=t('angAddFile');
    lab.querySelector('input').onchange=async e=>{const id=recIdOf(E.name);if(!id){toast(t('angNoId'));return;}const fs=[...e.target.files];toast(t('impRun',{n:fs.length}),3000);
      for(const f of fs){const w=new ChunkWriter(angleDir(id,cleanName(f.name),true));w.push(f);await w.finish({label:cleanName(f.name),mime:f.type||'video/mp4',lag:0});}
      await loadAngles();renderAll();toast(t('impAngDone',{n:fs.length}),3500);};
    P.appendChild(row(lab));
    if(!E.angles.length){P.appendChild(hint(t('angEmpty')));return;}
    P.appendChild(hint(t('angHint')));
    const cur=angleAt(x);
    P.appendChild(row(...[{label:-1,name:t('angProgram')}].concat(E.angles.map(a=>({label:a.label,name:a.label}))).map(o=>btn((((cur?cur.label:-1)===o.label)?'● ':'')+o.name,()=>{snap();const tt=+V().currentTime.toFixed(2);E.p.cuts=E.p.cuts.filter(c=>Math.abs(c.t-tt)>0.05);E.p.cuts.push({t:tt,a:o.label});E.p.cuts.sort((a,b)=>a.t-b.t);save();renderPanel();drawAt();},((cur?cur.label:-1)===o.label)?'btn-gold':''))));
    P.appendChild(row(btn(t('angAuto'),autoSpeaker,'btn-gold'),btn(t('angSync'),autoSync),E.p.cuts.length?btn(t('angClear'),()=>{snap();E.p.cuts=[];save();renderPanel();drawAt();},'btn-danger'):null));
    E.angles.forEach(a=>P.appendChild(row(hint(a.label+' · '+t('angOff')),range(-2,2,E.p.offs[a.label]||a.lag||0,v=>{E.p.offs[a.label]=v;save();angleSync(false);},0.02))));
    const angOpts=[[-1,t('angProgram')]].concat(E.angles.map(a=>[a.label,a.label]));
    E.p.cuts.forEach(c=>{const cd=document.createElement('div');cd.className='ed-card';
      cd.appendChild(row(btn('▶ '+fm1(c.t),()=>{V().currentTime=c.t;drawAt();}),hint(c.a===-1?t('angProgram'):c.a),btn('✕',()=>{snap();E.p.cuts=E.p.cuts.filter(q=>q!==c);save();renderPanel();drawAt();},'btn-danger')));
      cd.appendChild(row(hint(t('layT')),sel([['full',t('lay_full')],['split',t('lay_split')],['pip',t('lay_pip')]],c.lay||'full',v=>{snap();c.lay=v;if(v!=='full'&&c.b==null){const o=angOpts.find(q=>q[0]!==c.a);c.b=o?o[0]:-1;}save();renderPanel();drawAt();}),
        (c.lay&&c.lay!=='full')?sel(angOpts.map(q=>[String(q[0]),q[1]]),String(c.b),v=>{c.b=v==='-1'?-1:v;save();drawAt();}):null));
      P.appendChild(cd);});
  }
  if(k==='sticker'){
    P.appendChild(hint(t('stkHint')));
    P.appendChild(row(...STK.map(kd=>btn(t('stkN_'+kd),()=>{snap();const a=V().currentTime;const sk={id:Date.now(),kind:kd,x:0.5,y:0.4,s:+a.toFixed(2),e:+Math.min(E.dur,a+3).toFixed(2),size:12,col:'gold',rot:0};E.p.stickers.push(sk);E.sel=sk.id;E.rsel={o:sk,mode:'range',list:'stickers'};save();renderRange();renderPanel();drawAt();}))));
    E.p.stickers.forEach(sk=>{const c=document.createElement('div');c.className='ed-card';if(E.sel===sk.id)c.style.outline='2px solid var(--gold)';
      c.appendChild(row(btn(t('stkN_'+sk.kind)+(E.sel===sk.id?' ✓':''),()=>{E.sel=sk.id;renderPanel();}),));c.appendChild(timeBtn(sk,'range','stickers'));
      c.appendChild(row(sel(Object.keys(OV_COLORS).map(cc=>[cc,t('col_'+cc)]),sk.col,v=>{sk.col=v;save();drawAt();}),hint(t('tpSize')),range(5,30,sk.size,v=>{sk.size=v;save();drawAt();}),hint(t('stkRot')),range(-180,180,sk.rot||0,v=>{sk.rot=v;save();drawAt();},5),btn('✕',()=>{snap();E.p.stickers=E.p.stickers.filter(q=>q!==sk);save();renderPanel();drawAt();},'btn-danger')));
      P.appendChild(c);});
  }
  if(k==='sug'){
    P.appendChild(row(btn(t('edAnalyse'),analyse,'btn-gold'),(E.p.sugs||[]).some(g=>g.st==='new')?btn(t('edAcceptAll'),()=>{snap();E.p.sugs.filter(g=>g.st==='new').forEach(g=>{removeRange(g.a,g.b);g.st='ok';});changed();renderPanel();}):null));
    P.appendChild(hint(t('edSugHint')));
    P.appendChild(row(hint(t('edShort')),btn('15s',()=>shortVersion(15)),btn('30s',()=>shortVersion(30)),btn('60s',()=>shortVersion(60))));
    (E.p.sugs||[]).filter(g=>g.st==='new').forEach(g=>{const c=document.createElement('div');c.className='ed-card';
      c.appendChild(row(hint(t('edWhy_'+g.why)+' · '+fm1(g.a)+' – '+fm1(g.b))));
      c.appendChild(row(btn('▶',()=>{V().currentTime=Math.max(0,g.a-1);edPlay();}),btn(t('edAccept'),()=>{snap();removeRange(g.a,g.b);g.st='ok';changed();renderPanel();},'btn-danger'),btn(t('edReject'),()=>{g.st='no';save();renderTl();renderPanel();})));P.appendChild(c);});
    if(E.marks&&E.marks.length)P.appendChild(row(hint(t('edMarks')),...E.marks.map(m=>btn('📍 '+fm(m),()=>{V().currentTime=m;drawAt();}))));
  }
  if(k==='text'){
    {const c=document.createElement('div');c.className='ed-card';const d=subData();const n=d&&d.lines?d.lines.filter(q=>q.txt&&q.txt.trim()).length:0;
      c.appendChild(hint(t('subInEd',{n})));const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('subBurn');const i=cb.querySelector('input');i.checked=!!E.p.subs;i.onchange=()=>{E.p.subs=i.checked;save();drawAt();};
      c.appendChild(row(cb,btn(t('subOpen'),async()=>{const root=await recRoot();const h=await root.getDirectoryHandle(E.name);edPause();openSubEditor({name:E.name,h,done:true});})));P.appendChild(c);}
    P.appendChild(row(btn(t('edAddText'),()=>{snap();const a=V().currentTime;{const nt={id:Date.now(),s:+a.toFixed(2),e:+Math.min(E.dur,a+3).toFixed(2),txt:'',anim:'pop',pos:'m',font:'montserrat',col:'white',style:'outline',cs:'title',size:7};E.p.texts.push(nt);E.rsel={o:nt,mode:'range',list:'texts'};renderRange();}save();renderPanel();},'btn-gold')));
    E.p.texts.slice().sort((a,b)=>a.s-b.s).forEach(tx=>{const c=document.createElement('div');c.className='ed-card';
      const ta=document.createElement('textarea');ta.className='input';ta.rows=2;ta.maxLength=120;ta.value=tx.txt;ta.placeholder=t('edTextPh');ta.oninput=()=>{tx.txt=ta.value;save();drawAt();};c.appendChild(ta);
      c.appendChild(timeBtn(tx,'range','texts'));
      c.appendChild(row(sel([['none',t('edAn_none')],['fade',t('edAn_fade')],['pop',t('edAn_pop')],['type',t('edAn_type')],['slide',t('edAn_slide')]],tx.anim,v=>{tx.anim=v;save();}),
        sel([['t',t('sePosT')],['m',t('sePosM')],['b',t('sePosB')]],tx.pos,v=>{tx.pos=v;save();drawAt();})));
      const stBox=document.createElement('div');const stPaint=()=>stBox.replaceChildren(txStylePicker(txStyleOf(tx,'bubble'),tx.font,tx.col,k=>{tx.style=k;save();drawAt();}));stPaint();
      c.appendChild(hint(t('txStyleT')));c.appendChild(stBox);
      c.appendChild(row(txFontSel(tx.font||'montserrat',v=>{tx.font=v;save();stPaint();drawAt();}),txCaseSel(tx.cs||'asis',v=>{tx.cs=v;save();drawAt();})));
      c.appendChild(txSwatches(tx.col||'white',k=>{tx.col=k;save();stPaint();drawAt();}));
      c.appendChild(row(hint(t('tpSize')),range(4,14,tx.size||7,v=>{tx.size=v;save();drawAt();},0.5),btn('✕',()=>{snap();E.p.texts=E.p.texts.filter(q=>q!==tx);save();renderPanel();drawAt();},'btn-danger')));
      P.appendChild(c);});
  }
  if(k==='voice'){
    P.appendChild(row(hint(t('edOrigVol')),range(0,150,E.p.origVol,v=>{E.p.origVol=v;applyEnh();save();})));
    P.appendChild(row(hint(t('edEnh')),sel([['none',t('edEnh_none')],['clear',t('edEnh_clear')],['warm',t('edEnh_warm')],['loud',t('edEnh_loud')]],E.p.enh,v=>{E.p.enh=v;applyEnh();save();})));
    P.appendChild(hint(t('edEnhHint')));
    {const c=document.createElement('div');c.className='ed-card';c.appendChild(hint(t('dnT')));
      if(E.dn){const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('dnUse');const i=cb.querySelector('input');i.checked=!!E.p.dn;i.onchange=()=>{E.p.dn=i.checked;save();applyEnh();if(E.playing)dnStart(V().currentTime,V().playbackRate);};c.appendChild(cb);}
      else c.appendChild(row(btn(t('dnBtn'),denoise,'btn-gold')));
      c.appendChild(hint(t('dnHint')));P.appendChild(c);}
    {const c=document.createElement('div');c.className='ed-card';c.appendChild(hint('🗣️ '+t('ttsT')));
      const ta=document.createElement('textarea');ta.className='input';ta.rows=3;ta.maxLength=1000;ta.placeholder=t('ttsPh');ta.value=E.ttsDraft||'';ta.oninput=()=>{E.ttsDraft=ta.value;cnt.textContent=ta.value.length+'/1000';};c.appendChild(ta);
      const cnt=document.createElement('span');cnt.className='hint';cnt.textContent=(ta.value.length)+'/1000';
      const vs=sel([['ms-F',t('tts_msF')],['ms-M',t('tts_msM')],['ms-F2',t('tts_msF2')],['ms-M2',t('tts_msM2')],['en-F',t('tts_enF')],['en-M',t('tts_enM')],['id-F',t('tts_idF')],['id-M',t('tts_idM')]],E.ttsVoice||lsGet('pmc_tts_v','ms-F'),v=>{E.ttsVoice=v;lsSet('pmc_tts_v',v);});
      c.appendChild(row(vs,cnt));
      E.ttsRate=E.ttsRate||1;c.appendChild(row(hint(t('ttsRate')),range(0.75,1.35,E.ttsRate,v=>{E.ttsRate=v;},0.05)));
      /* where the voice starts: a gold handle on the strip, like text */
      if(!E.ttsPos)E.ttsPos={t:+V().currentTime.toFixed(2)};if(!E.rsel||E.rsel.list!=='_tts'&&!(E.rsel.list==='voices'))E.rsel={o:E.ttsPos,mode:'point',list:'_tts'};
      c.appendChild(row(hint(t('ttsAt')),btn('📍 '+fm1(E.ttsPos.t),()=>{E.rsel={o:E.ttsPos,mode:'point',list:'_tts'};renderRange();renderPanel();toast(t('rngHint1'),3000);},E.rsel&&E.rsel.o===E.ttsPos?'btn-gold':'')));
      const go=btn(t('ttsGo'),async()=>{const txt=ta.value.trim();if(!txt){toast(t('ttsEmpty'));return;}
        if(!isPro()){openPro();return;}if(!window.PMC||!PMC.tts){toast(t('eGeneric'));return;}
        go.disabled=true;go.textContent=t('ttsBusy');
        try{const r=await PMC.tts({text:txt,voice:E.ttsVoice||lsGet('pmc_tts_v','ms-F'),rate:E.ttsRate});
          const bin=atob(r.audio),u8=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);const blob=new Blob([u8],{type:'audio/mpeg'});
          const dur=await new Promise(res=>{const a=new Audio();a.preload='metadata';a.onloadedmetadata=()=>{res(a.duration);URL.revokeObjectURL(a.src);};a.onerror=()=>res(3);a.src=URL.createObjectURL(blob);});
          const id='tts_'+Date.now()+'.mp3',st=E.ttsPos?E.ttsPos.t:V().currentTime,vc={id,t:+st.toFixed(2),d:+Math.max(0.3,isFinite(dur)?dur:3).toFixed(2),tts:1};
          try{const dir=await voiceDir();const fh=await dir.getFileHandle(id,{create:true});const w=await fh.createWritable();await w.write(blob);await w.close();}catch(e){}
          snap();E.p.voices.push(vc);mkVoiceEl(vc,blob);save();toast(t('ttsDone',{n:r.left}),4000);E.ttsDraft='';E.ttsPos=null;E.rsel={o:vc,mode:'len',list:'voices'};renderRange();renderPanel();
        }catch(e){toast((e&&e.message)||t('eGeneric'),6000);go.disabled=false;go.textContent=t('ttsGo');}
      },'btn-gold');c.appendChild(row(go));c.appendChild(hint(t('ttsHint')));P.appendChild(c);}
    P.appendChild(row(btn(E.vrec?t('edVoStop'):t('edVoRec'),()=>{if(E.vrec)stopVoiceRec();else startVoiceRec();},E.vrec?'btn-danger':'btn-gold')));
    P.appendChild(hint(t('edVoHint')));
    P.appendChild(row(hint(t('edVoiceVol')),range(0,150,E.p.voiceVol,v=>{E.p.voiceVol=v;applyEnh();save();})));
    E.p.voices.forEach((vc,i)=>{const c=document.createElement('div');c.className='ed-card';
      c.appendChild(row(hint((vc.tts?'🗣️ ':'')+t('edVoN',{n:i+1})+' · '+fm1(vc.d)+'s'),timeBtn(vc,'len','voices'),btn('✕',async()=>{snap();E.p.voices=E.p.voices.filter(q=>q!==vc);save();if(E.voiceEls[vc.id]){freeAudio(E.voiceEls[vc.id]);delete E.voiceEls[vc.id];}try{(await voiceDir()).removeEntry(vc.id);}catch(e){}renderPanel();},'btn-danger')));P.appendChild(c);});
  }
  if(k==='music'){
    P.appendChild(row(sel([['none',t('mNone')]].concat(MUSIC_KEYS.map(k=>[k,t('m_'+k)])).concat([['custom',t('mOwn')]]),E.p.music,v=>{E.p.music=v;save();if(E.playing){edPause();edPlay();}renderPanel();})));
    if(E.p.music==='custom'){const lab=document.createElement('label');lab.className='btn btn-sm btn-soft';lab.style.cursor='pointer';lab.innerHTML='<span></span><input type="file" accept="audio/*" style="display:none">';lab.querySelector('span').textContent=E.musEl?t('mOwnChange'):t('mOwnPick');
      lab.querySelector('input').onchange=async e=>{const f=e.target.files[0];if(!f)return;musicSet(f);try{const d=await editsDir();const fh=await d.getFileHandle(E.name.replace(/[^\w.-]+/g,'_')+'.music',{create:true});const w=await fh.createWritable();await w.write(f);await w.close();}catch(e2){}toast(t('mOwnSet'));renderPanel();};
      P.appendChild(row(lab));P.appendChild(hint(t('mOwnHint')));}
    P.appendChild(row(hint(t('mVol')),range(0,100,E.p.musVol,v=>{E.p.musVol=v;applyEnh();save();})));
    const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('edDuck');const i=cb.querySelector('input');i.checked=E.p.duck;i.onchange=()=>{E.p.duck=i.checked;save();};P.appendChild(cb);
    P.appendChild(hint(t('musicNote')));
  }
  if(k==='fmt'){
    P.appendChild(row(sel([['orig',t('edF_orig')],['p',t('edF_p')],['l',t('edF_l')],['sq',t('edF_sq')]],E.p.fmt,v=>{E.p.fmt=v;sizeCanvas();save();drawAt();})));
    P.appendChild(row(hint(t('qT')),sel([['small',t('q_small')],['normal',t('q_normal')],['high',t('q_high')]],E.p.q||'normal',v=>{E.p.q=v;sizeCanvas();save();drawAt();})));
    P.appendChild(hint(t('qHint',{n:estMB()})));
    P.appendChild(row(hint(t('edFill')),sel([['blur',t('edFill_blur')],['black',t('edFill_black')]],E.p.fill,v=>{E.p.fill=v;save();drawAt();})));
    const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('edFollow');const i=cb.querySelector('input');i.checked=!!E.p.follow;
    i.onchange=()=>{if(i.checked&&!(E.p.track&&E.p.track.length)){trackSubject();}else{E.p.follow=i.checked;save();drawAt();}};P.appendChild(cb);P.appendChild(hint(t('edFollowHint')));
    {const c=document.createElement('div');c.className='ed-card';const cb=document.createElement('label');cb.className='check';cb.innerHTML='<input type="checkbox"><span></span>';cb.querySelector('span').textContent=t('fastT');const i=cb.querySelector('input');i.checked=fastOn();i.onchange=()=>{localStorage.setItem('pmc_fast',i.checked?'1':'0');};c.appendChild(cb);c.appendChild(hint(t('fastHint')));P.appendChild(c);}
  }
  if(k==='cover'){
    const inp=document.createElement('input');inp.className='input';inp.maxLength=40;inp.placeholder=t('edCoverPh');inp.value=E.coverTxt||$('titleTxt').value||'';inp.oninput=()=>E.coverTxt=inp.value;
    P.appendChild(hint(t('edCoverHint')));P.appendChild(row(btn(t('coverAuto'),autoCover)));P.appendChild(inp);P.appendChild(row(btn(t('edCoverSave'),cover,'btn-gold')));
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
function mkVoiceEl(vc,blob){const a=new Audio();a.src=URL.createObjectURL(blob);a.preload='auto';try{const s=actx.createMediaElementSource(a);s.connect(E.g.voice);a._node=s;}catch(e){}E.voiceEls[vc.id]=a;}
/* let go of an audio player completely: stop it, unplug it from the mixer, free its memory */
function freeAudio(a){try{a.pause();}catch(e){}try{a._node&&a._node.disconnect();}catch(e){}try{const u=a.src;a.removeAttribute('src');a.load();if(u&&u.startsWith('blob:'))URL.revokeObjectURL(u);}catch(e){}}
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

/* ================= EXPERIMENTAL: background AI, noise AI, fast export ================= */
function loadScript(u){return new Promise((res,rej)=>{const s=document.createElement('script');s.src=u;s.onload=res;s.onerror=rej;document.head.appendChild(s);});}
const MP_VER='0.10.14';
async function needSeg(){
  if(window._edSeg)return;
  const m=await import(`https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${MP_VER}/vision_bundle.mjs`);
  const fs=await m.FilesetResolver.forVisionTasks(`https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${MP_VER}/wasm`);
  const opt=d=>({baseOptions:{modelAssetPath:'https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite',delegate:d},runningMode:'VIDEO',outputCategoryMask:false,outputConfidenceMasks:true});
  try{window._edSeg=await m.ImageSegmenter.createFromOptions(fs,opt('GPU'));}catch(e){window._edSeg=await m.ImageSegmenter.createFromOptions(fs,opt('CPU'));}
}
async function needFace(){
  if(window._faceLm)return;
  const m=await import(`https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${MP_VER}/vision_bundle.mjs`);
  const fs=await m.FilesetResolver.forVisionTasks(`https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${MP_VER}/wasm`);
  const opt=d=>({baseOptions:{modelAssetPath:'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task',delegate:d},runningMode:'VIDEO',numFaces:2});
  try{window._faceLm=await m.FaceLandmarker.createFromOptions(fs,opt('GPU'));}catch(e){window._faceLm=await m.FaceLandmarker.createFromOptions(fs,opt('CPU'));}
}
let segTs=0;const BGC={};
const BG_IMGS=['hills','beach','city','office','cafe','garden','studio','gold','dusk','kitchen','podcast'],BGIMG={};
function bgSrc(n){return ['hills','dusk','kitchen','podcast'].includes(n)?`art/${n}.svg`:`art/bg/${n}.svg`;}
window.PMC_BG={list:BG_IMGS,src:n=>bgSrc(n),seg:()=>needSeg(),face:()=>needFace()};
function bgImg(n){let im=BGIMG[n];if(!im){im=BGIMG[n]=new Image();im.onload=()=>{if(E&&!E.playing)drawAt();};im.src=bgSrc(n);}return im;}
function bgCanvas(k,w,h){let c=BGC[k];if(!c){c=BGC[k]=document.createElement('canvas');}if(c.width!==w||c.height!==h){c.width=w;c.height=h;}return c;}
/* person stays sharp; what is behind them is blurred, darkened or replaced by a colour */
function bgProcess(v){
  const [vw,vh]=dimsOf(v),k=Math.min(1,720/Math.max(vw,vh)),w=Math.round(vw*k),h=Math.round(vh*k);
  segTs=Math.max(segTs+1,Math.round(performance.now()));
  const res=window._edSeg.segmentForVideo(v,segTs);const mk=res.confidenceMasks&&res.confidenceMasks[0];if(!mk){res.close&&res.close();return v;}
  const mw=mk.width,mh=mk.height,f=mk.getAsFloat32Array(),mc=bgCanvas('m',mw,mh),mg=mc.getContext('2d'),id=mg.createImageData(mw,mh);
  for(let i=0;i<f.length;i++){const a=f[i];id.data[i*4]=id.data[i*4+1]=id.data[i*4+2]=255;id.data[i*4+3]=a>0.6?255:a<0.3?0:Math.round((a-0.3)/0.3*255);}
  mg.putImageData(id,0,0);res.close&&res.close();
  const P=bgCanvas('p',w,h),pg=P.getContext('2d');pg.globalCompositeOperation='source-over';pg.clearRect(0,0,w,h);pg.drawImage(v,0,0,w,h);pg.globalCompositeOperation='destination-in';pg.drawImage(mc,0,0,w,h);pg.globalCompositeOperation='source-over';
  const O=bgCanvas('o',w,h),og=O.getContext('2d'),b=E.p.bg;og.filter='none';
  if(b==='blur'){og.filter='blur(14px)';og.drawImage(v,-10,-10,w+20,h+20);og.filter='none';}
  else if(b==='dark'){og.drawImage(v,0,0,w,h);og.fillStyle='rgba(0,0,0,.7)';og.fillRect(0,0,w,h);}
  else if(b&&b.startsWith('img:')){const im=bgImg(b.slice(4));og.fillStyle='#111';og.fillRect(0,0,w,h);if(im.complete&&im.naturalWidth){const kk=Math.max(w/im.naturalWidth,h/im.naturalHeight),iw=im.naturalWidth*kk,ih=im.naturalHeight*kk;og.drawImage(im,(w-iw)/2,(h-ih)/2,iw,ih);}}
  else{const col=(OV_COLORS[E.p.bgCol]||OV_COLORS.gold).c,gr=og.createLinearGradient(0,0,0,h);gr.addColorStop(0,col);gr.addColorStop(1,'#0e0e0f');og.fillStyle=gr;og.fillRect(0,0,w,h);}
  og.drawImage(P,0,0);return O;
}
async function bgEnable(mode){
  if(mode!=='none'&&!window._edSeg){const prog=G('edProg');prog.classList.add('show');G('edProgTx').textContent=t('bgLoading');G('edBar').style.width='30%';
    try{await needSeg();}catch(e){prog.classList.remove('show');toast(t('bgFail'),5000);E.p.bg='none';renderPanel();return;}prog.classList.remove('show');}
  if(mode&&mode.startsWith('img:'))bgImg(mode.slice(4));E.p.bg=mode;save();drawAt();renderPanel();
}
/* RNNoise: AI noise removal of the original sound (runs on the phone, no upload) */
async function needRnn(){
  if(window._rnn)return;let mod=null;
  for(const u of['https://cdn.jsdelivr.net/npm/@shiguredo/rnnoise-wasm@2022.2.0/+esm','https://esm.sh/@shiguredo/rnnoise-wasm@2022.2.0']){try{mod=await import(u);if(mod&&mod.Rnnoise)break;}catch(e){}}
  if(!mod||!mod.Rnnoise)throw new Error('rnnoise');window._rnn=await mod.Rnnoise.load();
}
async function origBuf(){if(!E.ob)E.ob=await actx.decodeAudioData(await E.blob.arrayBuffer());return E.ob;}
async function to48k(buf){if(buf.sampleRate===48000)return buf;const off=new OfflineAudioContext(buf.numberOfChannels,Math.ceil(buf.duration*48000),48000);const s=off.createBufferSource();s.buffer=buf;s.connect(off.destination);s.start();return off.startRendering();}
async function denoise(){
  const prog=G('edProg');prog.classList.add('show');G('edProgTx').textContent=t('dnLoading');G('edBar').style.width='5%';
  try{await needRnn();const src=await to48k(await origBuf()),ch=src.numberOfChannels,out=new AudioBuffer({length:src.length,numberOfChannels:ch,sampleRate:48000});
    const N=window._rnn.frameSize||480;
    for(let c=0;c<ch;c++){const d=src.getChannelData(c),o=out.getChannelData(c),st=window._rnn.createDenoiseState(),fr=new Float32Array(N);
      for(let i=0;i<d.length;i+=N){for(let j=0;j<N;j++)fr[j]=(d[i+j]||0)*32768;st.processFrame(fr);for(let j=0;j<N&&i+j<o.length;j++)o[i+j]=fr[j]/32768;
        if((i/N)%300===0){G('edBar').style.width=Math.round(((c+i/d.length)/ch)*100)+'%';G('edProgTx').textContent=t('dnRun',{n:Math.round(((c+i/d.length)/ch)*100)});await new Promise(r=>setTimeout(r,0));}}
      st.destroy&&st.destroy();}
    E.dn=out;E.p.dn=true;save();applyEnh();toast(t('dnDone'),3500);
  }catch(e){console.error('rnnoise',e);toast(t('dnFail'),5000);E.p.dn=false;}
  prog.classList.remove('show');renderPanel();
}
let dnSrc=null;
function dnStart(x,rate){dnStop();if(!E||!E.p.dn||!E.dn)return;const s=actx.createBufferSource();s.buffer=E.dn;s.playbackRate.value=rate||1;s.connect(E.g.dnG);try{s.start(0,Math.max(0,x));}catch(e){}dnSrc=s;}
function dnStop(){if(dnSrc){try{dnSrc.stop();}catch(e){}dnSrc=null;}}
/* fast export: read the MP4 directly, redraw each frame, and write a new MP4 using the phone's video chip */
function outPos(x){let o=0;for(const s of E.p.segs){if(!s.keep)continue;if(x>=s.b){o+=partLen(s)+(s.freeze||0);}else{if(x>s.a)o+=(x-s.a)/s.speed*(s.fx==='boom'?2:1);break;}}return o;}
function fastOn(){return localStorage.getItem('pmc_fast')!=='0';}
function canFast(){
  if(!fastOn()||typeof VideoEncoder==='undefined'||typeof VideoDecoder==='undefined'||typeof AudioEncoder==='undefined')return false;
  if(!/\.mp4$/i.test(E.name))return false;
  if(E.p.cuts&&E.p.cuts.some(c=>c.a!==-1||(c.lay&&c.lay!=='full')))return false;   /* other angles need live playback */
  if(E.p.segs.some(s=>s.keep&&s.fx&&s.fx!=='none'))return false;
  if(E.p.segs.some(s=>s.keep&&s.speed!==1)&&(E.p.origVol>0||E.p.voices.length))return false; /* keep voice pitch natural */
  return true;
}
async function needMp4(){
  if(!window.MP4Box)await loadScript('https://cdn.jsdelivr.net/npm/mp4box@0.5.2/dist/mp4box.all.min.js');
  if(!window.Mp4Muxer)await loadScript('https://cdn.jsdelivr.net/npm/mp4-muxer@5.1.3/build/mp4-muxer.min.js');
}
function buildChain(ctx,dst){
  const c=ENH[E.p.enh]||ENH.none,mk=(type,f,gain,q)=>{const b=ctx.createBiquadFilter();b.type=type;b.frequency.value=f;if(gain!=null)b.gain.value=gain;if(q)b.Q.value=q;return b;};
  const hpf=mk('highpass',c.hpf),low=mk('lowshelf',180,c.low),mud=mk('peaking',320,c.mud,1),pres=mk('peaking',3500,c.pres,0.9),air=mk('highshelf',9000,c.air);
  const comp=ctx.createDynamicsCompressor();comp.threshold.value=c.thr;comp.ratio.value=c.ratio;comp.knee.value=12;comp.attack.value=0.004;comp.release.value=0.25;
  const mkup=ctx.createGain();mkup.gain.value=c.mk;const lim=ctx.createDynamicsCompressor();lim.threshold.value=-3;lim.ratio.value=20;lim.attack.value=0.002;lim.release.value=0.1;
  hpf.connect(low);low.connect(mud);mud.connect(pres);pres.connect(air);air.connect(comp);comp.connect(mkup);mkup.connect(lim);lim.connect(dst);return hpf;
}
async function renderAudio(total){
  const SR=48000,len=Math.max(1,Math.ceil(total*SR));
  const off=new OfflineAudioContext(2,len,SR),inp=buildChain(off,off.destination);
  const ob=E.p.dn&&E.dn?E.dn:await origBuf().catch(()=>null);
  if(ob&&E.p.origVol>0){const g=off.createGain();g.gain.value=E.p.origVol/100;g.connect(inp);
    for(const s of E.p.segs){if(!s.keep)continue;const src=off.createBufferSource();src.buffer=ob;src.playbackRate.value=s.speed;src.connect(g);src.start(outPos(s.a),s.a,(s.b-s.a));}}
  if(E.p.voices.length){const g=off.createGain();g.gain.value=E.p.voiceVol/100;g.connect(inp);
    for(const vc of E.p.voices){const a=E.voiceEls[vc.id];if(!a)continue;try{const b=await (await fetch(a.src)).arrayBuffer();const vb=await off.decodeAudioData(b);const src=off.createBufferSource();src.buffer=vb;src.connect(g);src.start(outPos(vc.t));}catch(e){}}}
  (E.p.sfx||[]).forEach(q=>{if(E.p.segs.some(s=>s.keep&&q.t>=s.a&&q.t<s.b))sfxPlay(off,off.destination,q.kind,outPos(q.t),q.vol);});
  const speech=await off.startRendering();
  if(E.p.music==='none'||(E.p.music==='custom'&&!E.musBlob))return speech;
  const off2=new OfflineAudioContext(2,len,SR),sp=off2.createBufferSource();sp.buffer=speech;sp.connect(off2.destination);sp.start(0);
  const mg=off2.createGain();mg.gain.value=E.p.musVol/100*0.8;const dk=off2.createGain();mg.connect(dk);dk.connect(off2.destination);
  if(E.p.duck){const d=speech.getChannelData(0),hop=Math.round(SR*0.05);let lv=1;for(let i=0;i<d.length;i+=hop){let e=0;for(let j=i;j<Math.min(d.length,i+hop);j+=4)e+=d[j]*d[j];e=Math.sqrt(e/(hop/4));const want=e>0.03?0.28:1;lv+=(want-lv)*(want<lv?0.6:0.12);dk.gain.setValueAtTime(lv,i/SR);}}
  if(E.p.music==='custom'){try{const mb=await off2.decodeAudioData(await E.musBlob.arrayBuffer());const ms=off2.createBufferSource();ms.buffer=mb;ms.loop=true;ms.connect(mg);ms.start(0);}catch(e){}return off2.startRendering();}
  const keep={a:actx,m:musicGain,n:noiseBuf,st:mStyle,step:mStep,next:mNext};
  const fake={currentTime:total,sampleRate:SR,createOscillator:()=>off2.createOscillator(),createGain:()=>off2.createGain(),createBiquadFilter:()=>off2.createBiquadFilter(),createBuffer:(...a)=>off2.createBuffer(...a),createBufferSource:()=>off2.createBufferSource()};
  try{actx=fake;musicGain=mg;noiseBuf=null;mStyle=E.p.music;mStep=0;mNext=0.05;mSchedule();}
  finally{actx=keep.a;musicGain=keep.m;noiseBuf=keep.n;mStyle=keep.st;mStep=keep.step;mNext=keep.next;}
  return off2.startRendering();
}
async function fastExport(replace,suffix){
  const say=(p,m)=>{G('edBar').style.width=Math.round(p*100)+'%';G('edProgTx').textContent=m;};
  say(0.02,t('fastLoad'));await needMp4();
  /* 1. read the MP4 */
  const ab=await E.blob.arrayBuffer();ab.fileStart=0;
  const mp4=MP4Box.createFile();let info=null,desc=null;const chunks=[];
  const ready=new Promise((res,rej)=>{mp4.onError=rej;mp4.onReady=i=>{info=i;const tr=i.videoTracks[0];if(!tr)return rej(new Error('notrack'));
    const trak=mp4.getTrackById(tr.id);for(const en of trak.mdia.minf.stbl.stsd.entries){const bx=en.avcC||en.hvcC||en.vpcC||en.av1C;if(bx){const st=new DataStream(undefined,0,DataStream.BIG_ENDIAN);bx.write(st);desc=new Uint8Array(st.buffer,8);}}
    mp4.setExtractionOptions(tr.id,null,{nbSamples:1e9});mp4.start();res();};});
  mp4.onSamples=(id,u,samples)=>{for(const sm of samples)chunks.push(new EncodedVideoChunk({type:sm.is_sync?'key':'delta',timestamp:1e6*sm.cts/sm.timescale,duration:1e6*sm.duration/sm.timescale,data:sm.data}));};
  mp4.appendBuffer(ab);mp4.flush();await ready;await new Promise(r=>setTimeout(r,50));
  const vt=info.videoTracks[0];if(!chunks.length)throw new Error('nosamples');
  /* 2. writer */
  sizeCanvas(true);const W=CV().width,H=CV().height,total=outPos(E.dur+1);
  const bitrate=brate(W,H);let vcfg=null;
  for(const codec of['avc1.640028','avc1.4d0028','avc1.42e028','avc1.42001f']){const cfg={codec,width:W,height:H,bitrate,framerate:30,avc:{format:'avc'}};try{if((await VideoEncoder.isConfigSupported(cfg)).supported){vcfg=cfg;break;}}catch(e){}}
  if(!vcfg)throw new Error('noenc');
  let acfg=null,acodec='aac';for(const [codec,mx] of [['mp4a.40.2','aac'],['opus','opus']]){const cfg={codec,sampleRate:48000,numberOfChannels:2,bitrate:160000};try{if((await AudioEncoder.isConfigSupported(cfg)).supported){acfg=cfg;acodec=mx;break;}}catch(e){}}
  const target=new Mp4Muxer.ArrayBufferTarget();
  const muxer=new Mp4Muxer.Muxer({target,video:{codec:'avc',width:W,height:H},audio:acfg?{codec:acodec,numberOfChannels:2,sampleRate:48000}:undefined,fastStart:'in-memory',firstTimestampBehavior:'offset'});
  let fail=null;const enc=new VideoEncoder({output:(c,m)=>muxer.addVideoChunk(c,m),error:e=>fail=e});enc.configure(vcfg);
  /* 3. decode, redraw, encode */
  let lastOut=-1,nf=0,lastSeg=null;const FR=1e6/30;
  const emit=us=>{if(us<=lastOut)return;lastOut=us;const f=new VideoFrame(CV(),{timestamp:us,duration:FR});enc.encode(f,{keyFrame:nf++%60===0});f.close();};
  const freezeOut=sg=>{if(!sg||!sg.freeze)return;const st=Math.round(outPos(sg.b)*1e6)-sg.freeze*1e6;for(let k=1;k<=sg.freeze*30;k++)emit(Math.round(st+k*FR));};
  const dec=new VideoDecoder({output:fr=>{try{const x=fr.timestamp/1e6,sg=segAt(x);
      if(sg&&sg.keep&&x<sg.b){if(lastSeg&&sg!==lastSeg){freezeOut(lastSeg);grabPrev(sg.a);}composeAt(fr,x);emit(Math.round(outPos(x)*1e6));lastSeg=sg;}
    }catch(e){fail=e;}fr.close();},error:e=>fail=e});
  dec.configure({codec:vt.codec,codedWidth:vt.video.width,codedHeight:vt.video.height,description:desc||undefined});
  for(let i=0;i<chunks.length;i++){if(fail)throw fail;dec.decode(chunks[i]);
    while(dec.decodeQueueSize>8||enc.encodeQueueSize>8)await new Promise(r=>setTimeout(r,2));
    if(i%15===0){say(0.05+0.75*i/chunks.length,t('fastRun',{n:Math.round(i/chunks.length*100)}));await new Promise(r=>setTimeout(r,0));}}
  await dec.flush();freezeOut(lastSeg);await enc.flush();if(fail)throw fail;
  /* 4. sound */
  if(acfg){say(0.85,t('fastAudio'));const buf=await renderAudio(total);const aenc=new AudioEncoder({output:(c,m)=>muxer.addAudioChunk(c,m),error:e=>fail=e});aenc.configure(acfg);
    const L=buf.getChannelData(0),R=buf.numberOfChannels>1?buf.getChannelData(1):L,N=1024;
    for(let i=0;i<buf.length;i+=N){const n=Math.min(N,buf.length-i),d=new Float32Array(n*2);d.set(L.subarray(i,i+n),0);d.set(R.subarray(i,i+n),n);
      const a=new AudioData({format:'f32-planar',sampleRate:48000,numberOfFrames:n,numberOfChannels:2,timestamp:Math.round(i/48000*1e6),data:d});aenc.encode(a);a.close();
      if(aenc.encodeQueueSize>20)await new Promise(r=>setTimeout(r,1));}
    await aenc.flush();if(fail)throw fail;}
  muxer.finalize();say(0.97,t('fastSave'));
  const blob=new Blob([target.buffer],{type:'video/mp4'});
  const root=await recRoot();let base=E.name.replace(/\.\w+$/,'').replace(/_edit\d+$/,''),n=1;
  const exists=async nm=>{try{await root.getDirectoryHandle(nm);return true;}catch(e){return false;}};while(await exists(`${base}_edit${n}${suffix||''}.mp4`))n++;
  const sv=new Saver(`${base}_edit${n}${suffix||''}.mp4`);sv.push(blob);const r=await sv.finish('video/mp4');
  if(replace){try{await root.removeEntry(E.name,{recursive:true});localStorage.removeItem(key());}catch(e){}}
  return r;
}
/* ---------- export ---------- */
function estMB(){const[w,h]=outSize();return Math.max(1,Math.round(brate(w,h)/8/1048576*(outPos(E.dur+1)||1)+0.02*(outPos(E.dur+1)||1)));}
function brate(W,H){const big=W*H>=1900000;return E.p.q==='small'?2500000:E.p.q==='high'?(big?14000000:9000000):(big?8000000:5000000);}
window.edExportAsk=function(){
  if(!E||E.busy)return;if(!E.p.segs.some(s=>s.keep)){toast(t('edNothing'));return;}
  ask(t('edSaveT'),t('edSaveD'),t('edSaveNew'),()=>edExport(false));
  const card=G('askBox').querySelector('.askcard');
  if(!G('askReplace')){const b=document.createElement('button');b.className='btn btn-danger';b.id='askReplace';card.appendChild(b);}
  if(!G('askMulti')){const b=document.createElement('button');b.className='btn btn-gold';b.id='askMulti';card.insertBefore(b,G('askReplace'));}
  const r=G('askReplace');r.style.display='flex';r.textContent=t('edSaveReplace');r.onclick=()=>{askClose();ask(t('edRepT'),t('edRepD'),t('edSaveReplace'),()=>edExport(true));};
  const m=G('askMulti');m.style.display='flex';m.textContent=t('edSave3');m.onclick=()=>{askClose();edExport(false,['p','l','sq']);};
};
async function edExport(replace,fmts){
  if(E.busy)return;E.busy=true;edPause();stopVoiceRec();
  const prog=G('edProg');prog.classList.add('show');const keepF=E.p.fmt,list=fmts||[E.p.fmt];let last=null;
  try{
    for(let i=0;i<list.length;i++){E.p.fmt=list[i];sizeCanvas(true);const suf=fmts?({p:'_9x16',l:'_16x9',sq:'_1x1'}[list[i]]||''):'';
      if(fmts)toast(t('edSaving3',{n:i+1,m:list.length}),2500);
      last=await exportOne(replace&&!fmts,suf);}
    toast(t('saved',{w:last.where}),5000);
  }catch(e){console.error('export',e);toast(t('seFail'),4000);}
  if(E){E.p.fmt=keepF;sizeCanvas();drawAt();E.busy=false;E.playing=false;}
  listSaved('files');if(replace&&!fmts&&last)edClose();
}
async function exportOne(replace,suffix){
  if(canFast()){const t0=performance.now();try{const r=await fastExport(replace,suffix);G('edProgTx').textContent=t('fastDone',{n:Math.round((performance.now()-t0)/1000)});G('edBar').style.width='100%';return r;}
    catch(e){console.error('fast export',e);toast(t('fastFail'),4500);}}
  return rtExport(replace,suffix);
}
async function rtExport(replace,suffix){
  sizeCanvas(true);const c=CV(),out=c.captureStream(30),dest=actx.createMediaStreamDestination();
  E.g.out.connect(dest);dest.stream.getAudioTracks().forEach(tr=>out.addTrack(tr));
  const mime=pickMime(),ext=mime.includes('mp4')?'mp4':'webm';
  const rec=new MediaRecorder(out,mime?{mimeType:mime,videoBitsPerSecond:brate(c.width,c.height),audioBitsPerSecond:160000}:{});
  let base=E.name.replace(/\.\w+$/,'').replace(/_edit\d+(_\w+)?$/,''),n=1;const root=await recRoot();
  const exists=async nm=>{try{await root.getDirectoryHandle(nm);return true;}catch(e){return false;}};
  while(await exists(`${base}_edit${n}${suffix||''}.${ext}`))n++;const name=`${base}_edit${n}${suffix||''}.${ext}`,sv=new Saver(name);
  rec.ondataavailable=e=>sv.push(e.data);
  const total=outPos(E.dur+1)||1;let doneT=0;
  const kept=E.p.segs.filter(s=>s.keep);const v=V();
  const bar=p=>{G('edBar').style.width=Math.round(p*100)+'%';G('edProgTx').textContent=t('seMaking',{n:Math.round(p*100)});};
  rec.start(2000);rec.pause();
  for(const s of kept){
    if(s.fx&&s.fx!=='none'){await buildFx(s);const q=fxFrame(s,0);if(q)composeAt(q.img,q.x);rec.resume();
      const mm=E.p.music!=='none';if(mm)musicPlay(s.a);await runFx(s,p=>bar((doneT+partLen(s)*p)/total));if(mm)musicStop();rec.pause();doneT+=partLen(s);}
    else{
    v.currentTime=s.a;await new Promise(r=>{v.onseeked=r;setTimeout(r,2000);});angleSync(false);await new Promise(r=>setTimeout(r,250));drawAt();
    rec.resume();E.playing=true;v.playbackRate=s.speed;await v.play().catch(()=>{});angleSync(true,s.speed);dnStart(s.a,s.speed);musicPlay(s.a);sfxLastX=s.a-0.01;
    await new Promise(res0=>{let fin=false;const res=()=>{if(fin)return;fin=true;clearInterval(wd);res0();};
      const wd=setInterval(()=>{if(!E||v.ended||v.currentTime>=s.b-0.03)res();},120);v.addEventListener('ended',res,{once:true});
      const step=()=>{if(fin)return;if(!E)return res();angleSync(true,s.speed);voiceSync();sfxSync(v.currentTime);duckTick();drawAt();
        bar((doneT+(v.currentTime-s.a)/s.speed)/total);
        if(v.currentTime>=s.b-0.03||v.ended)return res();if(v.requestVideoFrameCallback)v.requestVideoFrameCallback(step);else requestAnimationFrame(step);};step();});
    v.pause();dnStop();musicStop();angleSync(false);Object.values(E.voiceEls).forEach(a=>a.pause());doneT+=partLen(s);}
    if(s.freeze){const until=performance.now()+s.freeze*1000;rec.resume();await new Promise(res=>{const f=()=>{drawAt();if(performance.now()>=until)return res();requestAnimationFrame(f);};f();});doneT+=s.freeze;}
    rec.pause();const nx=kept[kept.indexOf(s)+1];if(nx)grabPrev(nx.a);
  }
  E.playing=false;musicStop();rec.stop();await new Promise(r=>rec.onstop=r);try{E.g.out.disconnect(dest);}catch(e){}
  const r=await sv.finish(rec.mimeType||mime||'video/webm');
  if(replace){try{await root.removeEntry(E.name,{recursive:true});localStorage.removeItem(key());}catch(e){}}
  G('edProgTx').textContent=t('seDone');G('edBar').style.width='100%';return r;
}
/* ================= JOIN: several recordings into one video ================= */
document.body.insertAdjacentHTML('beforeend',`<div class="askbox" id="joinBox" onclick="if(event.target===this)this.classList.remove('show')"><div class="askcard" style="max-height:80vh;overflow:auto">
  <b data-i18n="joinT"></b><span data-i18n="joinD"></span><div id="joinList" style="display:flex;flex-direction:column;gap:6px"></div>
  <div class="askbtns"><button class="btn" onclick="G_('joinBox').classList.remove('show')" data-i18n="cancel"></button><button class="btn btn-gold" id="joinGo"></button></div>
  <div class="se-prog" id="joinProg"><div><i id="joinBar"></i></div><span id="joinTx"></span></div></div></div>`);
window.G_=id=>document.getElementById(id);
let joinSel=[];
/* used by drafts: join the given parts in order, then offer to delete the parts */
window.joinParts=function(items,name){
  document.querySelectorAll('#joinBox [data-i18n]').forEach(e=>e.textContent=t(e.dataset.i18n));G_('joinList').innerHTML='';G_('joinGo').textContent=t('draftFinish');G_('joinBox').classList.add('show');
  joinRun(items,name,async ok=>{if(!ok)return;const root=await recRoot();for(const it of items){try{await root.removeEntry(it.name,{recursive:true});}catch(e){}}listSaved('files');});
};
window.openJoin=async function(){
  if(!isPro()){openPro();return;}
  const root=await recRoot();const items=[];for await(const [n,h] of root.entries()){if(h.kind!=='directory')continue;let done=false;for await(const [k] of h.entries())if(k==='_done')done=true;if(done)items.push({name:n,h});}
  items.sort((a,b)=>a.name<b.name?-1:1);joinSel=[];const L=G_('joinList');L.innerHTML='';
  const paint=()=>{[...L.children].forEach(b=>{const i=joinSel.indexOf(b.dataset.n);b.classList.toggle('btn-gold',i>=0);b.querySelector('i').textContent=i>=0?(i+1)+'.':'';});G_('joinGo').textContent=t('joinGo',{n:joinSel.length});};
  items.forEach(it=>{const b=document.createElement('button');b.className='btn btn-sm';b.style.justifyContent='flex-start';b.dataset.n=it.name;b.innerHTML='<i style="font-style:normal;min-width:22px"></i><span></span>';b.querySelector('span').textContent=it.name;
    b.onclick=()=>{const i=joinSel.indexOf(it.name);if(i>=0)joinSel.splice(i,1);else joinSel.push(it.name);paint();};L.appendChild(b);});
  G_('joinGo').onclick=()=>{if(joinSel.length<2){toast(t('joinNeed2'));return;}joinRun(joinSel.map(n=>items.find(q=>q.name===n)));};
  G_('joinProg').classList.remove('show');paint();document.querySelectorAll('#joinBox [data-i18n]').forEach(e=>e.textContent=t(e.dataset.i18n));G_('joinBox').classList.add('show');
};
async function joinRun(list,outName,after){
  const go=G_('joinGo');go.disabled=true;G_('joinProg').classList.add('show');
  try{ensureAudio();if(actx.state==='suspended')await actx.resume();
    const vids=[];for(const it of list){const b=await savedBlob(it.h,mimeOf(it.name));const v=document.createElement('video');v.src=URL.createObjectURL(b);v.playsInline=true;v.preload='auto';await new Promise(r=>{v.onloadedmetadata=r;v.onerror=r;});vids.push(v);}
    const v0=vids[0],k=Math.min(1,1920/Math.max(v0.videoWidth||1280,v0.videoHeight||720)),W=Math.round((v0.videoWidth||1280)*k/2)*2,H=Math.round((v0.videoHeight||720)*k/2)*2;
    const c=document.createElement('canvas');c.width=W;c.height=H;const g=c.getContext('2d'),dest=actx.createMediaStreamDestination();
    vids.forEach(v=>{try{actx.createMediaElementSource(v).connect(dest);}catch(e){}});
    const out=c.captureStream(30);dest.stream.getAudioTracks().forEach(tr=>out.addTrack(tr));
    const mime=pickMime(),ext=mime.includes('mp4')?'mp4':'webm',rec=new MediaRecorder(out,mime?{mimeType:mime,videoBitsPerSecond:W*H>=1900000?8000000:5000000,audioBitsPerSecond:160000}:{});
    const sv=new Saver(`${outName||('Gabung_'+stamp())}.${ext}`);rec.ondataavailable=e=>sv.push(e.data);
    const tot=vids.reduce((a,v)=>a+(v.duration||0),0)||1;let done=0;
    const draw=v=>{const vw=v.videoWidth,vh=v.videoHeight;if(!vw)return;g.fillStyle='#000';g.fillRect(0,0,W,H);
      if(Math.abs(vw/vh-W/H)>0.02){g.save();g.filter='blur(28px) brightness(0.55)';const kk=Math.max(W/vw,H/vh)*1.1;g.drawImage(v,(W-vw*kk)/2,(H-vh*kk)/2,vw*kk,vh*kk);g.restore();}
      const kk=Math.abs(vw/vh-W/H)>0.02?Math.min(W/vw,H/vh):Math.max(W/vw,H/vh);g.drawImage(v,(W-vw*kk)/2,(H-vh*kk)/2,vw*kk,vh*kk);};
    rec.start(2000);rec.pause();
    for(const v of vids){v.currentTime=0;await new Promise(r=>{v.onseeked=r;setTimeout(r,800);});draw(v);rec.resume();await v.play().catch(()=>{});
      await new Promise(res0=>{let fin=false;const res=()=>{if(fin)return;fin=true;clearInterval(wd);res0();};const wd=setInterval(()=>{if(v.ended)res();},150);v.addEventListener('ended',res,{once:true});
        const f=()=>{if(fin)return;draw(v);const p=(done+v.currentTime)/tot;G_('joinBar').style.width=Math.round(p*100)+'%';G_('joinTx').textContent=t('seMaking',{n:Math.round(p*100)});if(v.requestVideoFrameCallback)v.requestVideoFrameCallback(f);else requestAnimationFrame(f);};f();});
      rec.pause();done+=v.duration||0;}
    rec.stop();await new Promise(r=>rec.onstop=r);const r=await sv.finish(rec.mimeType||mime||'video/webm');
    for(const nm of ['_brand.json','_switches.json']){try{const src=await (await list[0].h.getFileHandle(nm)).getFile();if(sv.dir){const w=await (await sv.dir.getFileHandle(nm,{create:true})).createWritable();await w.write(await src.text());await w.close();}}catch(e){}}
    vids.forEach(v=>URL.revokeObjectURL(v.src));G_('joinTx').textContent=t('seDone');toast(t('saved',{w:r.where}),5000);G_('joinBox').classList.remove('show');listSaved('files');if(after)after(true);
  }catch(e){if(after)after(false);console.error('join',e);toast(t('seFail'),4000);}
  go.disabled=false;
}
window.addEventListener('pmc-lang',()=>{if(E)renderAll();});
window._edT={compose:(x)=>composeAt(null,x),get E(){return E;}};
})();
