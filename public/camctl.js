/* ProMultiCam camera controls: focus (auto, tap, lock, manual), refocus after zoom, brightness,
   light colour (white balance) and torch. Works on the phone's own camera, and the Director can
   drive a connected camera phone remotely. Only what the phone/browser really supports is shown. */
(function(){
const ST=new WeakMap();                       /* per-track state */
const st=tr=>{let s=ST.get(tr);if(!s){s={lock:false,manual:false,ec:0,wb:'auto',torch:false,afZoom:true};ST.set(tr,s);}return s;};
const capsOf=tr=>{try{return tr&&tr.getCapabilities?tr.getCapabilities():{};}catch(e){return {};}};
const rng=r=>r&&typeof r.max==='number'&&r.max>r.min?{min:r.min,max:r.max,step:r.step||((r.max-r.min)/100)}:null;
const WB={day:5500,cloud:6500,warm:3200,white:4200};
/* short summary the camera phone sends to the Director */
window.ccCaps=function(tr){const c=capsOf(tr),fm=c.focusMode||[],wm=c.whiteBalanceMode||[];
  return{fm,fd:rng(c.focusDistance),poi:'pointsOfInterest' in c||fm.includes('single-shot'),ec:rng(c.exposureCompensation),
    wb:wm.includes('manual')&&!!rng(c.colorTemperature),wbAuto:wm.includes('continuous'),torch:!!c.torch};};
const any=k=>k&&(k.fm.length||k.fd||k.ec||k.wb||k.torch);
async function ap(tr,adv){try{await tr.applyConstraints({advanced:[adv]});return true;}catch(e){return false;}}
/* every action goes through here, so local and remote cameras behave the same */
window.ccDo=async function(tr,op,v){
  if(!tr)return false;const c=capsOf(tr),fm=c.focusMode||[],s=st(tr);
  if(op==='auto'){s.lock=false;s.manual=false;const adv={focusMode:fm.includes('continuous')?'continuous':fm.includes('single-shot')?'single-shot':undefined};
    if('pointsOfInterest' in c)adv.pointsOfInterest=[{x:0.5,y:0.5}];if(!adv.focusMode)delete adv.focusMode;return ap(tr,adv);}
  if(op==='at'){ /* tap to focus (and expose) at a point; x/y are 0..1 of the picture */
    if(s.lock||s.manual){s.lock=false;s.manual=false;}
    const adv={};if('pointsOfInterest' in c)adv.pointsOfInterest=[{x:v.x,y:v.y}];
    if(fm.includes('single-shot'))adv.focusMode='single-shot';else if(fm.includes('continuous'))adv.focusMode='continuous';
    if((c.exposureMode||[]).includes('continuous'))adv.exposureMode='continuous';
    const ok=await ap(tr,adv);
    if(ok&&adv.focusMode==='single-shot')setTimeout(()=>{if(!s.lock&&!s.manual&&fm.includes('continuous'))ap(tr,{focusMode:'continuous'});},2500);
    return ok;}
  if(op==='lock'){s.lock=!!v;s.manual=false;
    if(!v)return ccDo(tr,'auto');
    const cur=tr.getSettings?tr.getSettings():{};
    if(fm.includes('manual')&&cur.focusDistance!=null)return ap(tr,{focusMode:'manual',focusDistance:cur.focusDistance});
    if(fm.includes('single-shot'))return ap(tr,{focusMode:'single-shot'});return false;}
  if(op==='dist'){s.manual=true;s.lock=false;return ap(tr,{focusMode:'manual',focusDistance:Number(v)});}
  if(op==='zoomed'){ /* the picture changed size: focus again, unless the user fixed the focus */
    if(!s.afZoom||s.lock||s.manual)return false;
    clearTimeout(s.zt);s.zt=setTimeout(async()=>{
      const adv={};if('pointsOfInterest' in c)adv.pointsOfInterest=[{x:0.5,y:0.5}];
      if(fm.includes('single-shot')){adv.focusMode='single-shot';await ap(tr,adv);if(fm.includes('continuous'))setTimeout(()=>{if(!s.lock&&!s.manual)ap(tr,{focusMode:'continuous'});},1200);}
      else if(fm.includes('continuous')){adv.focusMode='continuous';await ap(tr,adv);}
    },350);return true;}
  if(op==='afz'){s.afZoom=!!v;return true;}
  if(op==='ec'){s.ec=Number(v);const adv={exposureCompensation:Number(v)};if((c.exposureMode||[]).includes('continuous'))adv.exposureMode='continuous';return ap(tr,adv);}
  if(op==='wb'){s.wb=v;if(v==='auto')return ap(tr,{whiteBalanceMode:'continuous'});
    const r=rng(c.colorTemperature);if(!r)return false;const k=Math.max(r.min,Math.min(r.max,WB[v]||5500));return ap(tr,{whiteBalanceMode:'manual',colorTemperature:k});}
  if(op==='torch'){s.torch=!!v;return ap(tr,{torch:!!v});}
  if(op==='reset'){s.ec=0;s.wb='auto';const adv={};if(rng(c.exposureCompensation))adv.exposureCompensation=0;if((c.whiteBalanceMode||[]).includes('continuous'))adv.whiteBalanceMode='continuous';if((c.exposureMode||[]).includes('continuous'))adv.exposureMode='continuous';await ap(tr,adv);return ccDo(tr,'auto');}
  return false;
};
window.ccState=tr=>tr?st(tr):null;
/* ---------- Auto Cantik: a one-time tidy-up when the camera opens ----------
   1) make sure focus, exposure and white balance are on full auto;
   2) look at a tiny 32x18 copy of the picture a few times over ~3 s and nudge brightness
      (and colour, only when it is far off). Then it stops — nothing keeps running. */
window.ccBeautyOn=()=>{try{return localStorage.getItem('pmc_beauty')!=='0';}catch(e){return true;}};
function sample(v){
  try{const W=32,H=18;const c=sample.c||(sample.c=document.createElement('canvas'));c.width=W;c.height=H;const x=c.getContext('2d',{willReadFrequently:true});
    x.drawImage(v,0,0,W,H);const d=x.getImageData(0,0,W,H).data;let Y=0,n=0,hi=0,lo=0,R=0,G=0,B=0,m=0;
    for(let i=0;i<d.length;i+=4){const r=d[i],g=d[i+1],b=d[i+2],y=0.2126*r+0.7152*g+0.0722*b;Y+=y;n++;if(y>245)hi++;if(y<14)lo++;if(y>50&&y<230&&Math.max(r,g,b)-Math.min(r,g,b)<90){R+=r;G+=g;B+=b;m++;}}
    /* colour is judged only on near-grey things (walls, plates, counters) so orange food does not fool it */
    return{y:Y/n,hi:hi/n,lo:lo/n,rb:m>=n*0.15?(R/m+1)/(B/m+1):1};}catch(e){return null;}
}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function avg(v,k){let a={y:0,hi:0,lo:0,rb:0},n=0;for(let i=0;i<k;i++){const q=sample(v);if(q){a.y+=q.y;a.hi+=q.hi;a.lo+=q.lo;a.rb+=q.rb;n++;}await sleep(250);}if(!n)return null;for(const z in a)a[z]/=n;return a;}
window.ccBeauty=async function(tr,v,quiet){
  if(!tr||!v||tr.readyState!=='live')return null;const c=capsOf(tr),s=st(tr),fm=c.focusMode||[],em=c.exposureMode||[],wm=c.whiteBalanceMode||[];
  if(s.beautyBusy)return null;s.beautyBusy=true;
  try{
    const adv={};if(fm.includes('continuous'))adv.focusMode='continuous';if(em.includes('continuous'))adv.exposureMode='continuous';if(wm.includes('continuous'))adv.whiteBalanceMode='continuous';
    const ecR=rng(c.exposureCompensation);if(ecR)adv.exposureCompensation=0;
    if(Object.keys(adv).length)await ap(tr,adv);s.lock=false;s.manual=false;s.ec=0;s.wb='auto';
    for(let i=0;i<20&&!(v.videoWidth&&v.readyState>=2);i++)await sleep(150);
    await sleep(1100);                                   /* let the phone's own auto settle first */
    let q=await avg(v,3);if(!q)return null;let ec=0,wb='auto';
    if(ecR){
      for(let pass=0;pass<2;pass++){
        let want=ec;
        if(q.y<92)want=ec+Math.min(1,(118-q.y)/45);else if(q.y>195||q.hi>0.12)want=ec-Math.min(0.7,Math.max((q.y-170)/45,q.hi*3));else break;
        want=Math.max(Math.max(ecR.min,-1),Math.min(Math.min(ecR.max,1.5),want));want=Math.round(want/ecR.step)*ecR.step;
        if(Math.abs(want-ec)<ecR.step/2)break;ec=want;await ap(tr,{exposureCompensation:ec});await sleep(700);q=await avg(v,2)||q;
      }
      s.ec=ec;
    }
    /* colour: only when the picture is clearly too yellow or too blue (food is often warm, so stay gentle) */
    const ctR=rng(c.colorTemperature);
    if(ctR&&wm.includes('manual')){if(q.rb>1.5){wb='white';}else if(q.rb<0.7){wb='cloud';}
      if(wb!=='auto'){const k=Math.max(ctR.min,Math.min(ctR.max,WB[wb]));await ap(tr,{whiteBalanceMode:'manual',colorTemperature:k});s.wb=wb;}}
    const changed=ec!==0||wb!=='auto';
    if(!quiet&&(ecR||ctR))toast(t(changed?'beautyDone':'beautyOk'),2600);
    return{ec,wb,y:Math.round(q.y)};
  }finally{s.beautyBusy=false;}
};

/* ---------- the control sheet (one for the Director, one for the camera phone) ---------- */
let target=null; /* {name, caps, state, run(op,v), tapHint} */
function el(tag,cls,txt){const e=document.createElement(tag);if(cls)e.className=cls;if(txt!=null)e.textContent=txt;return e;}
function seg(opts,cur,fn){const b=el('div','cc-seg');opts.forEach(([v,l])=>{const x=el('button','btn'+(v===cur?' on':''),l);x.type='button';x.onclick=()=>{[...b.children].forEach(y=>y.classList.toggle('on',y===x));fn(v);};b.appendChild(x);});return b;}
function slider(r,val,fn,fmt){const w=el('div','cc-rng');const i=document.createElement('input');i.type='range';i.min=r.min;i.max=r.max;i.step=r.step;i.value=val;const o=el('span','cc-val',fmt(val));
  let tm=0;i.oninput=()=>{o.textContent=fmt(Number(i.value));clearTimeout(tm);tm=setTimeout(()=>fn(Number(i.value)),60);};w.append(i,o);return w;}
function render(){
  const box=document.getElementById('ccBody');if(!box||!target)return;box.replaceChildren();const k=target.caps,s=target.state;
  document.getElementById('ccName').textContent=target.name||'';
  { const row=el('div','cc-row');const bb=el('button','btn btn-gold',t('beautyNow'));bb.type='button';bb.onclick=async()=>{bb.disabled=true;await target.run('beauty');setTimeout(()=>{bb.disabled=false;render();},target.remote?400:0);};
    const lb=document.createElement('label');lb.className='check';lb.innerHTML='<input type="checkbox"><span></span>';lb.querySelector('span').textContent=t('beautyAuto');const ci=lb.querySelector('input');ci.checked=ccBeautyOn();ci.onchange=()=>{try{localStorage.setItem('pmc_beauty',ci.checked?'1':'0');}catch(e){}target.run('beautyPref',ci.checked);};
    row.append(bb,lb);box.appendChild(row);}
  if(!any(k)){box.appendChild(el('p','hint',t('ccNone')));return;}
  if(k.fm.length||k.fd){
    box.appendChild(el('b','cc-h',t('ccFocus')));
    const opts=[['auto',t('ccAuto')]];if(k.fm.includes('manual')||k.fm.includes('single-shot'))opts.push(['lock',t('ccLock')]);if(k.fd)opts.push(['man',t('ccManual')]);
    const cur=s.manual?'man':s.lock?'lock':'auto';
    const fd=k.fd?slider(k.fd,(k.fd.min+k.fd.max)/2,v=>target.run('dist',v),v=>v.toFixed(2)):null;if(fd){fd.style.display=cur==='man'?'flex':'none';}
    box.appendChild(seg(opts,cur,v=>{if(fd)fd.style.display=v==='man'?'flex':'none';if(v==='auto'){s.lock=false;s.manual=false;target.run('auto');}else if(v==='lock'){s.lock=true;s.manual=false;target.run('lock',true);}else{s.manual=true;s.lock=false;}}));
    if(fd){const l=el('div','cc-ends');l.append(el('span',null,t('ccNear')),el('span',null,t('ccFar')));fd.prepend(l);box.appendChild(fd);}
    const row=el('div','cc-row');
    const c1=el('button','btn',t('ccCenter'));c1.type='button';c1.onclick=()=>{s.lock=false;s.manual=false;target.run('at',{x:0.5,y:0.5});toast(t('ccFocused'));render();};row.appendChild(c1);
    const az=document.createElement('label');az.className='check';az.innerHTML='<input type="checkbox"><span></span>';az.querySelector('span').textContent=t('ccAfZoom');const ai=az.querySelector('input');ai.checked=s.afZoom!==false;ai.onchange=()=>{s.afZoom=ai.checked;target.run('afz',ai.checked);};row.appendChild(az);
    box.appendChild(row);
    if(target.tapHint)box.appendChild(el('p','hint',t('ccTapHint')));
  }
  if(k.ec){box.appendChild(el('b','cc-h',t('ccExp')));box.appendChild(slider(k.ec,s.ec||0,v=>{s.ec=v;target.run('ec',v);},v=>(v>0?'+':'')+v.toFixed(1)));}
  if(k.wb){box.appendChild(el('b','cc-h',t('ccWb')));box.appendChild(seg([['auto',t('wb_auto')],['day',t('wb_day')],['cloud',t('wb_cloud')],['warm',t('wb_warm')],['white',t('wb_white')]],s.wb||'auto',v=>{s.wb=v;target.run('wb',v);}));}
  if(k.torch&&target.torch!==false){const tb=el('button','btn'+(s.torch?' on':''),t('ccTorch'));tb.type='button';tb.onclick=()=>{s.torch=!s.torch;tb.classList.toggle('on',s.torch);target.run('torch',s.torch);};box.appendChild(tb);}
  const rs=el('button','btn btn-ghost',t('ccReset'));rs.type='button';rs.onclick=()=>{Object.assign(s,{lock:false,manual:false,ec:0,wb:'auto'});target.run('reset');render();};box.appendChild(rs);
  box.appendChild(el('p','hint',t('ccHidden')));
}
window.ccOpen=function(tg){target=tg;render();openSheet('ccSheet');};

/* tap-to-focus on a <video> shown with object-fit:cover; shows a small focus square */
window.ccTapFocus=function(video,ev,run,mirrored){
  const r=video.getBoundingClientRect(),vw=video.videoWidth||16,vh=video.videoHeight||9;
  const k=Math.max(r.width/vw,r.height/vh),dw=vw*k,dh=vh*k,ox=(r.width-dw)/2,oy=(r.height-dh)/2;
  let x=(ev.clientX-r.left-ox)/dw,y=(ev.clientY-r.top-oy)/dh;if(mirrored)x=1-x;x=Math.max(0,Math.min(1,x));y=Math.max(0,Math.min(1,y));
  run('at',{x,y});
  const f=el('div','cc-ring');f.style.left=(ev.clientX)+'px';f.style.top=(ev.clientY)+'px';document.body.appendChild(f);setTimeout(()=>f.remove(),1100);
};
})();
