/* ProMultiCam text styles: one renderer shared by the editor text, subtitles and previews.
   Our own effects drawn on canvas with open-licence (OFL) Google fonts. */
(function(){
const MORE_FONTS={
  luckiest:{fam:'"Luckiest Guy"',w:400,fun:true},bangers:{fam:'Bangers',w:400},fredoka:{fam:'Fredoka',w:700},baloo:{fam:'"Baloo 2"',w:800},
  righteous:{fam:'Righteous',w:400},kanit:{fam:'Kanit',w:800},oswald:{fam:'Oswald',w:700},archivo:{fam:'"Archivo Black"',w:400},
  caveat:{fam:'Caveat',w:700,script:true},satisfy:{fam:'Satisfy',w:400,script:true},lalezar:{fam:'Lalezar',w:400},amiri:{fam:'Amiri',w:700}};
Object.assign(OV_FONTS,MORE_FONTS);
window.TX_STYLES=['outline','plain','shadow','glow','neon','gradient','d3','bubble','darkbox','highlight','karaoke','wordpop','shake','retro','write'];
window.TX_COLS={white:'#ffffff',yellow:'#ffe14d',gold:'#d9b44a',orange:'#ff9a3c',red:'#ff4d4d',pink:'#ff6fb5',purple:'#a66bff',blue:'#4d8bff',cyan:'#4dd9ff',green:'#5be08a',black:'#111111'};
const PARTNER={white:'#ff4d8d',yellow:'#ff4d4d',gold:'#7a3cff',orange:'#2f6bff',red:'#ffe14d',pink:'#4dd9ff',purple:'#ffe14d',blue:'#ff6fb5',cyan:'#ff4d8d',green:'#2f6bff',black:'#ffe14d'};
window.txColor=k=>TX_COLS[k]||(window.OV_COLORS&&OV_COLORS[k]&&OV_COLORS[k].c)||'#ffffff';
function inkFor(hex){const n=parseInt(hex.slice(1),16),r=n>>16,g=n>>8&255,b=n&255;return (r*299+g*587+b*114)/1000>150?'#111111':'#ffffff';}
function lighten(hex,k){const n=parseInt(hex.slice(1),16);const m=x=>Math.round(x+(255-x)*k);return `rgb(${m(n>>16)},${m(n>>8&255)},${m(n&255)})`;}
function darken(hex,k){const n=parseInt(hex.slice(1),16);const m=x=>Math.round(x*(1-k));return `rgb(${m(n>>16)},${m(n>>8&255)},${m(n&255)})`;}
/* case: as typed / Each Word (first letter up, rest kept) / UPPER / lower */
window.txCase=function(s,mode){s=String(s||'');
  if(mode==='upper')return s.toLocaleUpperCase();if(mode==='lower')return s.toLocaleLowerCase();
  if(mode==='title')return s.replace(/(^|[\s\-("“‘\/]|(?<=^|\s)')(\p{L})/gu,(m,a,b)=>a+b.toLocaleUpperCase());return s;};
/* old projects: box on/off becomes a style */
window.txStyleOf=function(o,boxDef){if(o.style&&TX_STYLES.includes(o.style))return o.style;return o.box?boxDef:'outline';};
function wrap(c,txt,maxW){const out=[];String(txt).split('\n').forEach(par=>{let cur='';par.split(/\s+/).filter(Boolean).forEach(w=>{const tt=cur?cur+' '+w:w;if(c.measureText(tt).width>maxW&&cur){out.push(cur);cur=w;}else cur=tt;});if(cur)out.push(cur);});return out;}
function rrect(c,x,y,w,h,r){c.beginPath();if(c.roundRect)c.roundRect(x,y,w,h,r);else c.rect(x,y,w,h);}
/* layout: returns {lines,fs,lh,font} after setting c.font */
window.txLayout=function(c,o,W,H){
  const f=OV_FONTS[o.font]||OV_FONTS.montserrat,S=Math.min(W,H),fs=Math.max(8,Math.round(S*(o.size||7)/100));
  let txt=txCase(o.txt,o.cs||'asis');if(f.caps)txt=txt.toUpperCase();
  const font=`${f.w} ${fs}px ${f.fam},"Bricolage Grotesque",sans-serif`;c.font=font;
  const lines=wrap(c,txt,W*(o.maxW||0.86));return{lines,fs,lh:fs*(f.script?1.4:1.28),font,f};
};
/* paint wrapped lines centred on cx, first line top at y0. age/dur in seconds drive the moving styles. */
window.txPaint=function(c,L,cx,y0,o,age,dur){
  const st=o.style||'outline',col=txColor(o.col),fs=L.fs,lh=L.lh,base=fs*0.98;age=age||0;dur=Math.max(0.3,dur||3);
  c.save();c.font=L.font;c.lineJoin='round';c.miterLimit=2;c.textBaseline='alphabetic';
  const words=[];L.lines.forEach((l,i)=>l.split(' ').forEach(w=>words.push({w,i})));const nW=words.length||1;
  let wi=0;
  L.lines.forEach((line,li)=>{
    const y=y0+li*lh+base,w=c.measureText(line).width,x0=cx-w/2;
    if(st==='bubble'||st==='darkbox'||st==='highlight'){
      const pad=fs*0.4;c.fillStyle=st==='bubble'?col:st==='darkbox'?'rgba(0,0,0,.62)':col;
      if(st==='highlight'){c.save();c.translate(cx,y-fs*0.33);c.rotate(-0.02);rrect(c,-w/2-pad*0.6,-fs*0.52,w+pad*1.2,fs*0.95,fs*0.08);c.fill();c.restore();}
      else{rrect(c,x0-pad,y-base,w+pad*2,lh,fs*0.3);c.fill();}
    }
    const fillCol=st==='bubble'||st==='highlight'?inkFor(col):st==='neon'?'#ffffff':col;
    const parts=line.split(' '),sp=c.measureText(' ').width;let x=x0;
    const perWord=st==='karaoke'||st==='wordpop'||st==='shake';
    const drawRun=(txt,px,py,k)=>{
      c.textAlign='left';
      if(st==='outline'||st==='karaoke'||st==='wordpop'||st==='shake'||st==='write'){c.lineWidth=Math.max(2,fs*0.17);c.strokeStyle='rgba(0,0,0,.9)';c.strokeText(txt,px,py);c.fillStyle=k||fillCol;c.fillText(txt,px,py);}
      else if(st==='plain'){c.shadowColor='rgba(0,0,0,.55)';c.shadowBlur=fs*0.12;c.shadowOffsetY=fs*0.03;c.fillStyle=fillCol;c.fillText(txt,px,py);c.shadowColor='transparent';}
      else if(st==='shadow'){c.fillStyle='rgba(0,0,0,.85)';c.fillText(txt,px+fs*0.07,py+fs*0.07);c.fillStyle=fillCol;c.fillText(txt,px,py);}
      else if(st==='glow'){c.shadowColor=col;c.shadowBlur=fs*0.45;c.fillStyle=col;c.fillText(txt,px,py);c.shadowBlur=fs*0.15;c.fillStyle=lighten(col,0.35);c.fillText(txt,px,py);c.shadowColor='transparent';}
      else if(st==='neon'){const fl=0.85+0.15*Math.sin(age*17)*Math.sin(age*5.3);c.globalAlpha*=fl;c.lineWidth=Math.max(2,fs*0.09);c.strokeStyle=col;c.shadowColor=col;c.shadowBlur=fs*0.6;c.strokeText(txt,px,py);c.shadowBlur=fs*0.25;c.strokeText(txt,px,py);c.shadowColor='transparent';c.fillStyle='#ffffff';c.fillText(txt,px,py);c.globalAlpha/=fl;}
      else if(st==='gradient'){const g=c.createLinearGradient(0,py-fs*0.8,0,py+fs*0.1);g.addColorStop(0,lighten(col,0.55));g.addColorStop(0.55,col);g.addColorStop(1,PARTNER[o.col]||darken(col,0.4));c.lineWidth=Math.max(2,fs*0.14);c.strokeStyle='rgba(0,0,0,.85)';c.strokeText(txt,px,py);c.fillStyle=g;c.fillText(txt,px,py);}
      else if(st==='d3'){const dk=darken(col,0.55),n=Math.max(3,Math.round(fs*0.09));c.fillStyle=dk;for(let i=n;i>0;i--)c.fillText(txt,px+i,py+i);c.lineWidth=Math.max(1.5,fs*0.06);c.strokeStyle=dk;c.strokeText(txt,px,py);c.fillStyle=col;c.fillText(txt,px,py);}
      else if(st==='retro'){const p2=PARTNER[o.col]||'#ff4d8d';c.lineWidth=Math.max(2,fs*0.12);c.strokeStyle='#111';c.strokeText(txt,px+fs*0.09,py+fs*0.09);c.fillStyle=p2;c.fillText(txt,px+fs*0.09,py+fs*0.09);c.strokeText(txt,px,py);c.fillStyle=col;c.fillText(txt,px,py);}
      else{c.fillStyle=fillCol;c.fillText(txt,px,py);}
    };
    if(st==='write'){ /* hand-writing reveal: each line is uncovered left to right */
      const tot=Math.min(dur*0.6,0.35+L.lines.length*0.6),p=Math.max(0,Math.min(1,age/tot*L.lines.length-li));if(p<=0){wi+=parts.length;return;}
      c.save();c.beginPath();c.rect(x0-fs,y-base-fs*0.3,(w+fs*2)*p,lh+fs*0.6);c.clip();drawRun(line,x0,y);c.restore();wi+=parts.length;return;}
    if(!perWord){drawRun(line,x0,y);wi+=parts.length;return;}
    parts.forEach(pw=>{const ww=c.measureText(pw).width,k=wi/nW;
      if(st==='karaoke'){const on=age>=k*dur*0.9;drawRun(pw,x,y,on?col:'#ffffff');}
      else if(st==='wordpop'){const t0=k*Math.min(dur*0.7,nW*0.32),p=Math.max(0,Math.min(1,(age-t0)/0.22));if(p>0){const s=0.4+0.6*(1-Math.pow(1-p,3))+(p<1?Math.sin(p*Math.PI)*0.15:0);c.save();c.translate(x+ww/2,y-fs*0.35);c.scale(s,s);c.globalAlpha*=p;drawRun(pw,-ww/2,fs*0.35);c.restore();}}
      else{const j=fs*0.035;const dx=Math.sin(age*31+wi*1.7)*j,dy=Math.cos(age*27+wi*2.3)*j;c.save();c.translate(x+ww/2,y);c.rotate(Math.sin(age*23+wi)*0.03);drawRun(pw,-ww/2+dx,dy);c.restore();}
      x+=ww+sp;wi++;});
  });
  c.restore();
};
/* one call: lay out + paint at a position (t / m / b) */
window.txDraw=function(c,W,H,o,age,dur){const L=txLayout(c,o,W,H);if(!L.lines.length)return null;const tot=L.lines.length*L.lh;
  const pos=o.pos||'b',y=pos==='t'?H*0.09:pos==='m'?H/2-tot/2:H*(o.bottom||0.9)-tot;txPaint(c,L,W/2,y,o,age,dur);return L;};
/* preview chips: a strip of buttons each showing "Aa" in a style */
window.txStylePicker=function(cur,font,col,onPick){
  const box=document.createElement('div');box.className='tx-styles';
  TX_STYLES.forEach(k=>{const b=document.createElement('button');b.type='button';b.className='tx-st'+(k===cur?' on':'');b.title=t('txs_'+k);
    const cv=document.createElement('canvas');cv.width=120;cv.height=64;b.appendChild(cv);const s=document.createElement('span');s.textContent=t('txs_'+k);b.appendChild(s);
    const paint=()=>{const c=cv.getContext('2d');c.clearRect(0,0,120,64);const gr=c.createLinearGradient(0,0,120,64);gr.addColorStop(0,'#3a4152');gr.addColorStop(1,'#1d2230');c.fillStyle=gr;c.fillRect(0,0,120,64);
      const o={txt:'Aa Bb',font:font||'montserrat',col:col||'yellow',size:30,style:k,maxW:0.95};const L=txLayout(c,o,120,64);txPaint(c,L,60,32-L.lh/2,o,k==='karaoke'?1.2:k==='write'?5:5,2);};
    paint();try{const f=OV_FONTS[font];if(f)document.fonts.load(`${f.w} 20px ${f.fam}`).then(paint);}catch(e){}
    b.onclick=()=>{[...box.children].forEach(x=>x.classList.toggle('on',x===b));onPick(k);};box.appendChild(b);});
  return box;
};
window.txSwatches=function(cur,onPick){const box=document.createElement('div');box.className='swatches tx-sw';
  Object.entries(TX_COLS).forEach(([k,c])=>{const b=document.createElement('button');b.type='button';b.style.background=c;b.dataset.k=k;b.setAttribute('aria-label',k);if(k===cur)b.classList.add('on');
    b.onclick=()=>{[...box.children].forEach(x=>x.classList.toggle('on',x===b));onPick(k);};box.appendChild(b);});return box;};
window.txCaseSel=function(cur,onPick){const s=document.createElement('select');s.className='input';[['asis','txc_asis'],['title','txc_title'],['upper','txc_upper'],['lower','txc_lower']].forEach(([v,k])=>{const o=document.createElement('option');o.value=v;o.textContent=t(k);s.appendChild(o);});s.value=cur||'asis';s.onchange=()=>onPick(s.value);return s;};
window.txFontSel=function(cur,onPick){const s=document.createElement('select');s.className='input';Object.keys(OV_FONTS).forEach(k=>{const o=document.createElement('option');o.value=k;o.textContent=t('font_'+k);s.appendChild(o);});s.value=cur;
  s.onchange=()=>{const f=OV_FONTS[s.value];onPick(s.value);try{document.fonts.load(`${f.w} 30px ${f.fam}`).then(()=>onPick(s.value));}catch(e){}};return s;};
})();
