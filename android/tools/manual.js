// Build the app's manual (assets/manual) from the web manual, so both stay the same.
const fs=require('fs'),path=require('path');
const src=fs.readFileSync(process.argv[2],'utf8');const out=process.argv[3];
const ms=src.indexOf('const MS='),cut=src.indexOf('const st=');
const body=src.slice(ms,cut>0?cut:undefined);
const f=new Function(body.replace(/^/,'')+';return {MS,EN};');const {MS,EN}=f();
const pm=src.match(/PICS=(\{[\s\S]*?\});/);const PICS=pm?JSON.parse(pm[1]):{};
fs.mkdirSync(out,{recursive:true});
fs.writeFileSync(path.join(out,'manual.json'),JSON.stringify({ms:MS,en:EN,pics:PICS}));
console.log('manual pages',MS.length,EN.length);
