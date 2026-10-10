const STUDIO_POSE_SEQUENCE_JS = String.raw`(() => {
  "use strict";

  /*
   * Pose-to-Pose Frame Synthesizer
   * Inputs must be distinct, explicitly imported sequential drawings.
   * This is not image-to-video generation from a single still.
   * A local sparse brightness flow grid animates details between actual poses.
   * WebGL performs full-resolution two-anchor flow warping and occlusion blending.
   * One consistent frame is recorded for every time sample (not slideshow zoom).
   */
  const $=id=>document.getElementById(id);
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,Number(n)||0));
  const ease=t=>{let p=clamp(t,0,1);return p*p*(3-2*p)};
  const W=96,H=160,GRID_X=12,GRID_Y=20,SEARCH=9;
  const maxAnchors=12;

  function makeLuminance(image){
    const canvas=document.createElement("canvas");canvas.width=W;canvas.height=H;
    const ctx=canvas.getContext("2d",{willReadFrequently:true});
    ctx.fillStyle="#07090d";ctx.fillRect(0,0,W,H);
    const sw=image.naturalWidth||image.width,sh=image.naturalHeight||image.height;
    const scale=Math.max(W/sw,H/sh);
    ctx.drawImage(image,(W-sw*scale)/2,(H-sh*scale)/2,sw*scale,sh*scale);
    const d=ctx.getImageData(0,0,W,H).data;
    const lum=new Uint8Array(W*H);
    for(let i=0;i<lum.length;i++)lum[i]=(77*d[i*4]+150*d[i*4+1]+29*d[i*4+2])>>8;
    return lum;
  }

  function difference(a,b){
    let accum=0;for(let i=0;i<a.length;i+=3){let d=a[i]-b[i];accum+=d*d;}
    return accum/Math.ceil(a.length/3);
  }

  function estimateFlow(a,b){
    const packed=new Uint8Array(GRID_X*GRID_Y*4);
    const patches=[];
    let meaningful=0,total=0;
    for(let gy=0;gy<GRID_Y;gy++)for(let gx=0;gx<GRID_X;gx++){
      const x=Math.round((gx+.5)*W/GRID_X),y=Math.round((gy+.5)*H/GRID_Y);
      let bestScore=1e9,bestDx=0,bestDy=0;
      const patchPoints=[];
      for(let dy=-2;dy<=2;dy++)for(let dx=-2;dx<=2;dx++) {
        if(x+dx>=2&&x+dx<W-2&&y+dy>=2&&y+dy<H-2)
          patchPoints.push([dx,dy]);
      }
      let localDetail=0;
      for(const [dx,dy] of patchPoints) localDetail+=Math.abs(a[(y+dy)*W+x+dx]-a[y*W+x]);
      for(let sy=-SEARCH;sy<=SEARCH;sy+=2){
        for(let sx=-SEARCH;sx<=SEARCH;sx+=2){
          let score=0,count=0;
          for(const [dx,dy] of patchPoints){
            const tx=x+dx+sx,ty=y+dy+sy;
            if(tx<0||ty<0||tx>=W||ty>=H)continue;
            score+=Math.abs(a[(y+dy)*W+x+dx]-b[ty*W+tx]);count++;
          }
          if(count){
            score/=count;
            // Penalise false motion in flat regions; retain real pose changes.
            score+=.16*(Math.abs(sx)+Math.abs(sy));
            if(score<bestScore){bestScore=score;bestDx=sx;bestDy=sy;}
          }
        }
      }
      if(localDetail<45||bestScore>65){bestDx=0;bestDy=0;}
      const i=(gy*GRID_X+gx)*4;
      packed[i]=Math.round(clamp((bestDx/SEARCH+1)*127.5,0,255));
      packed[i+1]=Math.round(clamp((-bestDy/SEARCH+1)*127.5,0,255));
      packed[i+2]=Math.round(clamp(255-bestScore*3.2,0,255));
      packed[i+3]=255;
      if(Math.abs(bestDx)+Math.abs(bestDy)>2)meaningful++;
      total++;
    }
    return {packed,meaningful,total,ratio:meaningful/total};
  }

  function verifyAnchors(assetIds,project){
    if(!Array.isArray(assetIds)||assetIds.length<2||assetIds.length>maxAnchors)
      throw new Error("Pose animation needs 2–12 distinct illustrated keyframe image IDs.");
    const ids=assetIds.map(String);
    if(new Set(ids).size!==ids.length)throw new Error("Repeated images do not create distinct poses");
    const images=ids.map(id=>(project.assets||[]).find(a=>a.id===id&&a.kind==="image"));
    if(images.some(x=>!x))throw new Error("Every keyframe must be an imported image in this exact project");
    return images;
  }

  function validateTimes(times,n){
    if(times===undefined)return Array.from({length:n},(_,i)=>i/(n-1));
    if(!Array.isArray(times)||times.length!==n)throw new Error("poseTimes must match the number of anchors");
    const values=times.map(Number);
    if(values[0]!==0||values[n-1]!==1||values.some(x=>!Number.isFinite(x)||x<0||x>1))
      throw new Error("Pose timeline must begin at 0, end at 1, and remain normalized");
    for(let i=1;i<n;i++)if(values[i]-values[i-1]<.025)throw new Error("Pose times must be strictly increasing with >=0.025 spacing");
    return values;
  }

  const VERT=[
    "attribute vec2 aPos;","attribute vec2 aTex;","varying vec2 vUV;",
    "void main(){vUV=aTex;gl_Position=vec4(aPos,0.,1.);}"
  ].join("\n");
  const FRAG=[
    "precision mediump float;",
    "varying vec2 vUV;",
    "uniform sampler2D uA;uniform sampler2D uB;uniform sampler2D uFlow;",
    "uniform vec2 uMax;uniform float uTime;",
    "void main(){",
    "  vec4 flow=texture2D(uFlow,vUV);",
    "  vec2 disp=(flow.rg*2.-1.)*uMax*clamp(flow.b*1.7,.25,1.);",
    "  vec2 srcA=clamp(vUV-disp*uTime,vec2(.0001),vec2(.9999));",
    "  vec2 srcB=clamp(vUV+disp*(1.-uTime),vec2(.0001),vec2(.9999));",
    "  vec3 a=texture2D(uA,srcA).rgb;",
    "  vec3 b=texture2D(uB,srcB).rgb;",
    "  float blend=uTime*uTime*(3.-2.*uTime);",
    "  gl_FragColor=vec4(mix(a,b,blend),1.);",
    "}"
  ].join("\n");
  function shader(gl,type,source){
    const sh=gl.createShader(type);gl.shaderSource(sh,source);gl.compileShader(sh);
    if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS))throw new Error("Pose shader failed: "+gl.getShaderInfoLog(sh));
    return sh;
  }

  function gpuRenderer(width,height,images,flows,drawCover){
    const canvas=document.createElement("canvas");canvas.width=width;canvas.height=height;
    const gl=canvas.getContext("webgl",{alpha:false,preserveDrawingBuffer:true,powerPreference:"high-performance"});
    if(!gl)throw new Error("WebGL is required for local pose-to-pose frame synthesis");
    const program=gl.createProgram(),vs=shader(gl,gl.VERTEX_SHADER,VERT),fs=shader(gl,gl.FRAGMENT_SHADER,FRAG);
    gl.attachShader(program,vs);gl.attachShader(program,fs);gl.linkProgram(program);
    if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error("Pose compositor could not link");
    gl.useProgram(program);gl.viewport(0,0,width,height);
    const vertices=new Float32Array([-1,-1,0,0,1,-1,1,0,-1,1,0,1,-1,1,0,1,1,-1,1,0,1,1,1,1]);
    const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.bufferData(gl.ARRAY_BUFFER,vertices,gl.STATIC_DRAW);
    for(const [name,offset] of [["aPos",0],["aTex",8]]){
      const loc=gl.getAttribLocation(program,name);gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,2,gl.FLOAT,false,16,offset);
    }
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
    const imagesTex=[];
    function tex(source,isFlow){
      const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
      if(isFlow)gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,GRID_X,GRID_Y,0,gl.RGBA,gl.UNSIGNED_BYTE,source);
      else gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,source);
      if(gl.getError()!==gl.NO_ERROR)throw new Error("Browser GPU memory insufficient to allocate pose textures");
      return t;
    }
    for(const img of images){
      const off=document.createElement("canvas");off.width=width;off.height=height;
      const cx=off.getContext("2d",{alpha:false});cx.fillStyle="#07090d";cx.fillRect(0,0,width,height);
      drawCover(cx,img,width,height,1);
      imagesTex.push(tex(off,false));
    }
    const flowsTex=flows.map(x=>tex(x.packed,true));
    const uniforms={
      a:gl.getUniformLocation(program,"uA"),b:gl.getUniformLocation(program,"uB"),
      flow:gl.getUniformLocation(program,"uFlow"),
      time:gl.getUniformLocation(program,"uTime"),max:gl.getUniformLocation(program,"uMax")
    };
    gl.uniform1i(uniforms.a,0);gl.uniform1i(uniforms.b,1);gl.uniform1i(uniforms.flow,2);
    gl.uniform2f(uniforms.max,SEARCH/W,SEARCH/H);
    return {
      canvas,
      draw(pair,t) {
        gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,imagesTex[pair]);
        gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,imagesTex[pair+1]);
        gl.activeTexture(gl.TEXTURE2);gl.bindTexture(gl.TEXTURE_2D,flowsTex[pair]);
        gl.uniform1f(uniforms.time,t);
        gl.drawArrays(gl.TRIANGLES,0,6);
        if(gl.isContextLost())throw new Error("GPU context lost during pose frame synthesis");
      },
      close() {
        gl.deleteBuffer(buffer);imagesTex.concat(flowsTex).forEach(x=>gl.deleteTexture(x));
        gl.deleteShader(vs);gl.deleteShader(fs);gl.deleteProgram(program);
      }
    };
  }

  async function render(project,options,services){
    const assets=verifyAnchors(options.anchorAssetIds,project);
    const times=validateTimes(options.poseTimes,assets.length);
    const loaded=[];
    let gpu=null;
    try {
      for(const asset of assets)loaded.push(await services.loadImageAsset(project,asset));
      const low=loaded.map(x=>makeLuminance(x.image));
      const visualDelta=[],flows=[];
      for(let i=0;i<low.length-1;i++){
        const diff=difference(low[i],low[i+1]);
        if(diff<12)throw new Error("Two adjacent drawings are too similar; pose-to-pose video needs newly illustrated poses, not duplicated/sliding stills");
        visualDelta.push(diff);
        flows.push(estimateFlow(low[i],low[i+1]));
      }
      const [width,height]=services.dimensions(options.aspect||project.settings.aspect,options.quality||project.settings.quality);
      gpu=gpuRenderer(width,height,loaded.map(x=>x.image),flows,services.drawCover);
      const blob=await services.recordCanvas({
        width,height,quality:options.quality||project.settings.quality,
        duration:clamp(options.duration||8,2,60),fps:clamp(options.fps||24,12,30),
        onProgress:services.progress,
        draw:(ctx,w,h,t)=>{
          const frameT=clamp(t,0,1);
          let idx=times.findIndex((v,i)=>i<times.length-1&&frameT>=v&&frameT<=times[i+1]);
          if(idx<0)idx=times.length-2;
          const local=clamp((frameT-times[idx])/(times[idx+1]-times[idx]),0,1);
          gpu.draw(idx,local);
          ctx.drawImage(gpu.canvas,0,0,w,h);
        }
      });
      if(!blob.size)throw new Error("Pose animation generated no video bytes");
      return {
        blob,engine:"studio-web-multiframe-pose-flow-v1",poseAnchorIds:assets.map(x=>x.id),
        distinctPoseDeltas:visualDelta,flowMotionRatios:flows.map(x=>x.ratio),
        newlyGeneratedPoses:false,neural:false,distinctPoseFrames:assets.length
      };
    } finally {
      if(gpu)gpu.close();
      loaded.forEach(x=>URL.revokeObjectURL(x.url));
    }
  }

  window.VideoStudioPoseSequence=Object.freeze({
    version:"1.0.0",render,verifyAnchors,validateTimes,estimateFlow,difference,makeLuminance,
    maxAnchors,model:"non-neural sparse matching between genuinely distinct pose drawings"
  });
})();`;

export default STUDIO_POSE_SEQUENCE_JS;
