/* ProMultiCam — fast colour looks.
   Canvas "filter" (contrast, saturate, sepia...) is slow on phones because it runs on every pixel on the CPU side.
   All the looks we use are simple colour maths, so one small WebGL pass does the same job on the graphics chip.
   PMCFX.src(video, 'contrast(1.1) saturate(0.8)') -> {img, k}: a picture of the frame with the look applied,
   k = its size compared with the video (source rectangles are multiplied by k). Returns null when it cannot help
   (no WebGL, or a filter like blur), and the caller then falls back to the normal canvas filter. */
(function(){
  const ID=()=>[1,0,0,0, 0,1,0,0, 0,0,1,0];
  function mul(A,B){ /* A after B; 3x4 affine, row-major [r0c0 r0c1 r0c2 r0off, ...] */
    const R=new Array(12);
    for(let i=0;i<3;i++){for(let j=0;j<3;j++){R[i*4+j]=A[i*4]*B[j]+A[i*4+1]*B[4+j]+A[i*4+2]*B[8+j];}
      R[i*4+3]=A[i*4]*B[3]+A[i*4+1]*B[7]+A[i*4+2]*B[11]+A[i*4+3];}
    return R;
  }
  function op(name,a){
    switch(name){
      case 'brightness':return [a,0,0,0, 0,a,0,0, 0,0,a,0];
      case 'contrast':{const o=0.5-0.5*a;return [a,0,0,o, 0,a,0,o, 0,0,a,o];}
      case 'saturate':return [0.213+0.787*a,0.715-0.715*a,0.072-0.072*a,0, 0.213-0.213*a,0.715+0.285*a,0.072-0.072*a,0, 0.213-0.213*a,0.715-0.715*a,0.072+0.928*a,0];
      case 'grayscale':{const s=1-Math.min(1,a);return [0.2126+0.7874*s,0.7152-0.7152*s,0.0722-0.0722*s,0, 0.2126-0.2126*s,0.7152+0.2848*s,0.0722-0.0722*s,0, 0.2126-0.2126*s,0.7152-0.7152*s,0.0722+0.9278*s,0];}
      case 'sepia':{const s=1-Math.min(1,a);return [0.393+0.607*s,0.769-0.769*s,0.189-0.189*s,0, 0.349-0.349*s,0.686+0.314*s,0.168-0.168*s,0, 0.272-0.272*s,0.534-0.534*s,0.131+0.869*s,0];}
      case 'hue-rotate':{const r=a*Math.PI/180,c=Math.cos(r),s=Math.sin(r);return [
        0.213+c*0.787-s*0.213,0.715-c*0.715-s*0.715,0.072-c*0.072+s*0.928,0,
        0.213-c*0.213+s*0.143,0.715+c*0.285+s*0.140,0.072-c*0.072-s*0.283,0,
        0.213-c*0.213-s*0.787,0.715-c*0.715+s*0.715,0.072+c*0.928+s*0.072,0];}
    }
    return null;
  }
  const cache={};
  function matrixOf(f){
    if(f in cache)return cache[f];
    let M=ID(),ok=true;const steps=[];const re=/([a-z-]+)\(\s*(-?[\d.]+)(deg|%)?\s*\)/g;let m,n=0;
    const rest=f.replace(re,'').trim();if(rest)ok=false;
    while(ok&&(m=re.exec(f))){let a=parseFloat(m[2]);if(m[3]==='%')a/=100;const o=op(m[1],a);if(!o){ok=false;break;}M=mul(o,M);steps.push(o);n++;}
    return cache[f]=(ok&&n&&n<=4)?steps:null;
  }
  let gl=null,cv=null,prog=null,tex=null,uM=null,uO=null,uN=null,bad=false;
  function init(){
    if(gl||bad)return !!gl;
    try{cv=document.createElement('canvas');gl=cv.getContext('webgl',{premultipliedAlpha:false,alpha:false,antialias:false,depth:false,preserveDrawingBuffer:true});
      if(!gl)throw 0;
      /* only worth it on a real graphics chip; a software stand-in is slower than the canvas filter */
      try{const di=gl.getExtension('WEBGL_debug_renderer_info'),rn=di?gl.getParameter(di.UNMASKED_RENDERER_WEBGL):'';if(!window.PMCFX_FORCE&&/swiftshader|llvmpipe|software|basic render/i.test(rn))throw 0;}catch(e){if(e===0)throw 0;}
      const vs='attribute vec2 p;varying vec2 u;void main(){u=vec2(p.x*.5+.5,.5-p.y*.5);gl_Position=vec4(p,0.,1.);}';
      const fs='precision mediump float;varying vec2 u;uniform sampler2D t;uniform mat3 M[4];uniform vec3 O[4];uniform int N;void main(){vec3 c=texture2D(t,u).rgb;for(int i=0;i<4;i++){if(i<N)c=clamp(M[i]*c+O[i],0.,1.);}gl_FragColor=vec4(c,1.);}';
      const sh=(ty,src)=>{const s=gl.createShader(ty);gl.shaderSource(s,src);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw 0;return s;};
      prog=gl.createProgram();gl.attachShader(prog,sh(gl.VERTEX_SHADER,vs));gl.attachShader(prog,sh(gl.FRAGMENT_SHADER,fs));gl.linkProgram(prog);if(!gl.getProgramParameter(prog,gl.LINK_STATUS))throw 0;gl.useProgram(prog);
      const b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
      const loc=gl.getAttribLocation(prog,'p');gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);
      tex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tex);
      [gl.TEXTURE_WRAP_S,gl.TEXTURE_WRAP_T].forEach(k=>gl.texParameteri(gl.TEXTURE_2D,k,gl.CLAMP_TO_EDGE));
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
      uM=gl.getUniformLocation(prog,'M');uO=gl.getUniformLocation(prog,'O');uN=gl.getUniformLocation(prog,'N');
      cv.addEventListener('webglcontextlost',e=>{e.preventDefault();gl=null;bad=true;});
      return true;
    }catch(e){gl=null;bad=true;return false;}
  }
  let tSum=0,tN=0;
  function src(v,f,maxSide){
    if(!f||f==='none'||bad)return null;const M=matrixOf(f);if(!M||!init())return null;
    const vw=v.videoWidth||v.width,vh=v.videoHeight||v.height;if(!vw||!vh)return null;
    const k=Math.min(1,(maxSide||1920)/Math.max(vw,vh)),w=Math.max(2,Math.round(vw*k)),h=Math.max(2,Math.round(vh*k));
    const t0=performance.now();
    try{
      if(cv.width!==w||cv.height!==h){cv.width=w;cv.height=h;}gl.viewport(0,0,w,h);
      gl.bindTexture(gl.TEXTURE_2D,tex);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,v);
      /* GLSL mat3 is column-major */
      const mm=[],oo=[];M.forEach(A=>{mm.push(A[0],A[4],A[8],A[1],A[5],A[9],A[2],A[6],A[10]);oo.push(A[3],A[7],A[11]);});while(mm.length<36){mm.push(1,0,0,0,1,0,0,0,1);oo.push(0,0,0);}
      gl.uniformMatrix3fv(uM,false,mm);gl.uniform3fv(uO,oo);gl.uniform1i(uN,M.length);
      gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
      /* safety: if this phone is slow at it, go back to the normal filter for good */
      if(tN<30){tSum+=performance.now()-t0;tN++;if(tN===30&&tSum/tN>12){bad=true;}}
      return {img:cv,k:w/vw};   /* caller draws it straight away, before the next call */
    }catch(e){return null;}
  }
  window.PMCFX={src,matrixOf,ok:()=>init()};
})();
