const STUDIO_CINEMATIC_JS = String.raw`
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const clamp = (v,min,max) => Math.max(min,Math.min(max,Number(v)));
  const sleep = (ms) => new Promise(r=>setTimeout(r,ms));
  const state = {
    busy:false,
    baseAssetId:"",
    worldAssetIds:[],
    startQuad:[[.43,.08],[.97,.08],[.97,.72],[.43,.72]],
    endQuad:[[.43,.08],[.97,.08],[.97,.72],[.43,.72]],
    calibrationTarget:"start",
    clicks:[],
    lastFrame:null,
    trackOffset:[0,0],
    personSegmenter:null,
    personMaskCanvas:null,
    personMaskFrame:0,
    personMaskReady:false
  };

  const deviceId = () => localStorage.getItem("vs-device-id") || "";
  const projectId = () => localStorage.getItem("vs-project-id") || "";

  async function api(path, options={}) {
    const response = await fetch(path,{
      ...options,
      headers:{"content-type":"application/json",...(options.headers||{})}
    });
    const data = await response.json().catch(()=>({}));
    if(!response.ok) throw new Error(data.error||"Request failed");
    return data;
  }

  function toast(message) {
    const n=$("toast");
    if(n){n.textContent=message;n.classList.add("show");clearTimeout(toast.t);toast.t=setTimeout(()=>n.classList.remove("show"),2600);}
    else console.log("[VideoStudio Cinematic]",message);
  }

  function log(title,detail){
    const host=$("commandLog"); if(!host)return;
    const item=document.createElement("div");item.className="log";
    const a=document.createElement("strong");a.textContent=title;
    const b=document.createElement("span");b.textContent=detail||"";
    item.append(a,b);host.prepend(item);
  }

  function openDb(){
    return new Promise((resolve,reject)=>{
      const req=indexedDB.open("videostudio-local",3);
      req.onupgradeneeded=()=>{
        const db=req.result;
        if(!db.objectStoreNames.contains("assets"))db.createObjectStore("assets",{keyPath:"key"});
        if(!db.objectStoreNames.contains("renders"))db.createObjectStore("renders",{keyPath:"key"});
        if(!db.objectStoreNames.contains("thumbs"))db.createObjectStore("thumbs",{keyPath:"key"});
      };
      req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
    });
  }

  async function idbGet(store,key){
    const db=await openDb();
    return new Promise((resolve,reject)=>{
      const tx=db.transaction(store,"readonly"),req=tx.objectStore(store).get(key);
      req.onsuccess=()=>{db.close();resolve(req.result||null);};
      req.onerror=()=>{db.close();reject(req.error);};
    });
  }

  async function idbPut(store,value){
    const db=await openDb();
    return new Promise((resolve,reject)=>{
      const tx=db.transaction(store,"readwrite");tx.objectStore(store).put(value);
      tx.oncomplete=()=>{db.close();resolve();};tx.onerror=()=>{db.close();reject(tx.error);};
    });
  }

  const assetKey=(pid,aid)=>pid+":"+aid;

  async function getProject(){
    const pid=projectId();if(!pid)throw new Error("Select a project first");
    const data=await api("/api/projects?deviceId="+encodeURIComponent(deviceId()));
    const p=(data.projects||[]).find(x=>x.id===pid);
    if(!p)throw new Error("Project not found");
    p.assets=p.assets||[];p.timeline=p.timeline||[];p.settings=p.settings||{};
    return p;
  }

  async function patchProject(project,patch){
    return (await api("/api/projects/"+encodeURIComponent(project.id),{
      method:"POST",body:JSON.stringify({deviceId:deviceId(),patch})
    })).project;
  }

  function dimensions(aspect,quality){
    const base=quality==="1080p"?1080:720;
    if(aspect==="16:9")return[Math.round(base*16/9),base];
    if(aspect==="1:1")return[base,base];
    if(aspect==="4:5")return[base,Math.round(base*5/4)];
    return[base,Math.round(base*16/9)];
  }

  function bestRecorder(stream,quality){
    const bitrate=quality==="1080p"?10000000:4800000;
    const types=["video/mp4;codecs=avc1.42E01E,mp4a.40.2","video/mp4","video/webm;codecs=vp9,opus","video/webm;codecs=vp8,opus","video/webm"];
    for(const type of types){
      if(window.MediaRecorder&&MediaRecorder.isTypeSupported(type)){
        try{return new MediaRecorder(stream,{mimeType:type,videoBitsPerSecond:bitrate});}catch{}
      }
    }
    return new MediaRecorder(stream,{videoBitsPerSecond:bitrate});
  }

  async function getAssetBlob(project,asset){
    let record=await idbGet("assets",assetKey(project.id,asset.id));
    if(record&&record.blob)return record.blob;
    if(window.VideoStudioCloud&&typeof window.VideoStudioCloud.restoreAssetBlob==="function"){
      record=await window.VideoStudioCloud.restoreAssetBlob(project.id,asset.id);
      if(record&&record.blob)return record.blob;
    }
    throw new Error("Media not available locally: "+asset.name+". Restore it from Drive first.");
  }

  async function loadVideo(project,asset,withAudio=false){
    const blob=await getAssetBlob(project,asset),url=URL.createObjectURL(blob);
    const video=document.createElement("video");
    video.src=url;video.preload="auto";video.playsInline=true;video.loop=false;video.muted=!withAudio;
    await new Promise((resolve,reject)=>{video.onloadedmetadata=resolve;video.onerror=()=>reject(new Error("Could not decode "+asset.name));});
    return{asset,blob,url,video};
  }

  async function loadImage(project,asset){
    const blob=await getAssetBlob(project,asset),url=URL.createObjectURL(blob),image=new Image();
    await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=()=>reject(new Error("Could not decode "+asset.name));image.src=url;});
    return{asset,blob,url,image};
  }

  function drawCover(ctx,source,w,h,scale=1){
    const sw=source.videoWidth||source.naturalWidth||source.width||w,sh=source.videoHeight||source.naturalHeight||source.height||h;
    const fit=Math.max(w/sw,h/sh)*scale,dw=sw*fit,dh=sh*fit;
    ctx.drawImage(source,(w-dw)/2,(h-dh)/2,dw,dh);
  }

  function lerp(a,b,t){return a+(b-a)*t;}
  function quadAt(t){
    const q=state.startQuad.map((p,i)=>[lerp(p[0],state.endQuad[i][0],t),lerp(p[1],state.endQuad[i][1],t)]);
    return q.map(p=>[p[0]+state.trackOffset[0],p[1]+state.trackOffset[1]]);
  }

  function affineFromTriangles(s0,s1,s2,d0,d1,d2){
    const x0=s0[0],y0=s0[1],x1=s1[0],y1=s1[1],x2=s2[0],y2=s2[1];
    const X0=d0[0],Y0=d0[1],X1=d1[0],Y1=d1[1],X2=d2[0],Y2=d2[1];
    const det=x0*(y1-y2)+x1*(y2-y0)+x2*(y0-y1);
    if(Math.abs(det)<1e-6)return null;
    const a=(X0*(y1-y2)+X1*(y2-y0)+X2*(y0-y1))/det;
    const c=(X0*(x2-x1)+X1*(x0-x2)+X2*(x1-x0))/det;
    const e=(X0*(x1*y2-x2*y1)+X1*(x2*y0-x0*y2)+X2*(x0*y1-x1*y0))/det;
    const b=(Y0*(y1-y2)+Y1*(y2-y0)+Y2*(y0-y1))/det;
    const d=(Y0*(x2-x1)+Y1*(x0-x2)+Y2*(x1-x0))/det;
    const f=(Y0*(x1*y2-x2*y1)+Y1*(x2*y0-x0*y2)+Y2*(x0*y1-x1*y0))/det;
    return[a,b,c,d,e,f];
  }

  function drawTriangle(ctx,source,s0,s1,s2,d0,d1,d2){
    const m=affineFromTriangles(s0,s1,s2,d0,d1,d2);if(!m)return;
    ctx.save();
    ctx.beginPath();ctx.moveTo(d0[0],d0[1]);ctx.lineTo(d1[0],d1[1]);ctx.lineTo(d2[0],d2[1]);ctx.closePath();ctx.clip();
    ctx.transform(...m);ctx.drawImage(source,0,0);ctx.restore();
  }

  function bilerp(q,u,v){
    const top=[lerp(q[0][0],q[1][0],u),lerp(q[0][1],q[1][1],u)];
    const bottom=[lerp(q[3][0],q[2][0],u),lerp(q[3][1],q[2][1],u)];
    return[lerp(top[0],bottom[0],v),lerp(top[1],bottom[1],v)];
  }

  function drawWarped(ctx,source,quadPx,subdiv=8){
    const sw=source.videoWidth||source.naturalWidth||source.width||1,sh=source.videoHeight||source.naturalHeight||source.height||1;
    for(let y=0;y<subdiv;y++){
      const v0=y/subdiv,v1=(y+1)/subdiv;
      for(let x=0;x<subdiv;x++){
        const u0=x/subdiv,u1=(x+1)/subdiv;
        const s00=[u0*sw,v0*sh],s10=[u1*sw,v0*sh],s11=[u1*sw,v1*sh],s01=[u0*sw,v1*sh];
        const d00=bilerp(quadPx,u0,v0),d10=bilerp(quadPx,u1,v0),d11=bilerp(quadPx,u1,v1),d01=bilerp(quadPx,u0,v1);
        drawTriangle(ctx,source,s00,s10,s11,d00,d10,d11);
        drawTriangle(ctx,source,s00,s11,s01,d00,d11,d01);
      }
    }
  }

  function pathQuad(ctx,q){
    ctx.beginPath();ctx.moveTo(q[0][0],q[0][1]);ctx.lineTo(q[1][0],q[1][1]);ctx.lineTo(q[2][0],q[2][1]);ctx.lineTo(q[3][0],q[3][1]);ctx.closePath();
  }

  function epicWorld(ctx,w,h,elapsed,prompt,index){
    const lower=String(prompt||"").toLowerCase();
    const themes=[
      ["#171020","#793d38","#f1b06b"],
      ["#071621","#176078","#c8f1e8"],
      ["#19080a","#8d1717","#ffb252"],
      ["#07120d","#24643f","#d5e6a4"],
      ["#0b0b1c","#423781","#f2d7ff"]
    ];
    const c=themes[index%themes.length];
    const g=ctx.createLinearGradient(0,0,0,h);g.addColorStop(0,c[0]);g.addColorStop(.58,c[1]);g.addColorStop(1,c[2]);ctx.fillStyle=g;ctx.fillRect(0,0,w,h);
    const sunX=w*(.72+.06*Math.sin(elapsed*.11)),sunY=h*.28,sunR=Math.min(w,h)*.08;
    const rg=ctx.createRadialGradient(sunX,sunY,0,sunX,sunY,sunR*4);rg.addColorStop(0,"rgba(255,244,190,.94)");rg.addColorStop(.18,"rgba(255,176,92,.5)");rg.addColorStop(1,"rgba(255,130,60,0)");ctx.fillStyle=rg;ctx.fillRect(0,0,w,h);
    for(let layer=0;layer<4;layer++){
      const base=h*(.58+layer*.09);
      ctx.fillStyle="rgba(5,8,12,"+(.20+layer*.15)+")";
      ctx.beginPath();ctx.moveTo(0,h);
      for(let x=0;x<=w;x+=w/10){
        const yy=base-Math.sin(x/w*8+layer*1.7)*h*(.05+.025*layer)-Math.sin(x/w*19+layer)*h*.018;
        ctx.lineTo(x,yy);
      }
      ctx.lineTo(w,h);ctx.closePath();ctx.fill();
    }
    const cx=w*(.54+.05*Math.sin(elapsed*.17+index)),cy=h*.55,scale=Math.min(w,h)*(.18+.015*Math.sin(elapsed*.3));
    ctx.save();ctx.translate(cx,cy);ctx.globalAlpha=.82;
    ctx.fillStyle=lower.includes("gold")?"#5a2f12":"#11131a";
    ctx.beginPath();ctx.arc(0,-scale*.72,scale*.16,0,Math.PI*2);ctx.fill();
    ctx.fillRect(-scale*.14,-scale*.56,scale*.28,scale*.72);
    ctx.beginPath();ctx.moveTo(-scale*.15,-scale*.42);ctx.lineTo(-scale*.62,-scale*.02);ctx.lineTo(-scale*.49,scale*.07);ctx.lineTo(-scale*.05,-scale*.22);ctx.fill();
    ctx.beginPath();ctx.moveTo(scale*.15,-scale*.42);ctx.lineTo(scale*.62,-scale*.08);ctx.lineTo(scale*.5,scale*.04);ctx.lineTo(scale*.05,-scale*.22);ctx.fill();
    ctx.globalAlpha=1;ctx.restore();
    for(let i=0;i<34;i++){
      const x=(i*71+elapsed*(12+i%4))%w,y=(i*113+Math.sin(elapsed*.5+i)*h*.05)%h;
      ctx.fillStyle=i%4===0?"rgba(255,220,150,.65)":"rgba(255,255,255,.22)";ctx.beginPath();ctx.arc(x,y,1+i%3,0,Math.PI*2);ctx.fill();
    }
  }

  function sampleGray(ctx,w,h){
    const dw=96,dh=Math.max(54,Math.round(dw*h/w)),c=sampleGray.canvas||(sampleGray.canvas=document.createElement("canvas"));
    c.width=dw;c.height=dh;const x=c.getContext("2d",{willReadFrequently:true});x.drawImage(ctx.canvas,0,0,dw,dh);
    const d=x.getImageData(0,0,dw,dh).data,g=new Uint8Array(dw*dh);
    for(let i=0,j=0;i<d.length;i+=4,j++)g[j]=(d[i]*.299+d[i+1]*.587+d[i+2]*.114)|0;
    return{g,w:dw,h:dh};
  }

  function trackTranslation(current){
    if(!state.lastFrame){state.lastFrame=current;return;}
    const prev=state.lastFrame;if(prev.w!==current.w||prev.h!==current.h){state.lastFrame=current;return;}
    let best=[0,0],score=Infinity;
    for(let dy=-2;dy<=2;dy++)for(let dx=-2;dx<=2;dx++){
      let s=0,n=0;
      for(let y=5;y<current.h-5;y+=3)for(let x=5;x<current.w-5;x+=3){
        const px=x+dx,py=y+dy;if(px<0||py<0||px>=prev.w||py>=prev.h)continue;
        s+=Math.abs(current.g[y*current.w+x]-prev.g[py*prev.w+px]);n++;
      }
      s/=Math.max(1,n);if(s<score){score=s;best=[dx,dy];}
    }
    if(score<24){
      state.trackOffset[0]=clamp(state.trackOffset[0]-best[0]/current.w,-.06,.06);
      state.trackOffset[1]=clamp(state.trackOffset[1]-best[1]/current.h,-.06,.06);
    }
    state.lastFrame=current;
  }

  async function ensurePersonSegmenter(){
    if(state.personSegmenter)return state.personSegmenter;
    const mod=await import("https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/vision_bundle.mjs");
    const vision=await mod.FilesetResolver.forVisionTasks("https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/wasm");
    state.personSegmenter=await mod.ImageSegmenter.createFromOptions(vision,{
      baseOptions:{modelAssetPath:"https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter_landscape/float16/latest/selfie_segmenter_landscape.tflite"},
      outputCategoryMask:true,
      outputConfidenceMasks:false,
      runningMode:"VIDEO"
    });
    return state.personSegmenter;
  }

  function updatePersonMask(video,elapsed){
    if(!state.personSegmenter)return;
    state.personMaskFrame++;
    if(state.personMaskFrame%4!==1&&state.personMaskReady)return;
    try{
      state.personSegmenter.segmentForVideo(video,elapsed*1000,result=>{
        try{
          const mask=result&&result.categoryMask;if(!mask)return;
          const mw=mask.width,mh=mask.height,data=mask.getAsUint8Array();
          const canvas=state.personMaskCanvas||(state.personMaskCanvas=document.createElement("canvas"));canvas.width=mw;canvas.height=mh;
          const x=canvas.getContext("2d",{willReadFrequently:true}),img=x.createImageData(mw,mh);
          for(let i=0,j=0;i<data.length;i++,j+=4){
            const a=data[i]===1?255:0;
            img.data[j]=255;img.data[j+1]=255;img.data[j+2]=255;img.data[j+3]=a;
          }
          x.putImageData(img,0,0);state.personMaskReady=true;
          if(typeof mask.close==="function")mask.close();
        }catch(error){console.warn("Person mask",error);}
      });
    }catch(error){console.warn("Person segmentation frame",error);}
  }

  function compositePersonOcclusion(ctx,video,w,h){
    if(!state.personMaskReady||!state.personMaskCanvas)return;
    const canvas=compositePersonOcclusion.canvas||(compositePersonOcclusion.canvas=document.createElement("canvas"));
    if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
    const x=canvas.getContext("2d",{alpha:true});x.clearRect(0,0,w,h);drawCover(x,video,w,h,1);
    x.globalCompositeOperation="destination-in";x.drawImage(state.personMaskCanvas,0,0,w,h);x.globalCompositeOperation="source-over";
    ctx.drawImage(canvas,0,0);
  }

  function portalLightSpill(ctx,q,w,h,index,strength){
    if(strength<=0)return;
    const colors=["255,176,92","92,214,255","255,76,89","108,235,154","188,132,255"];
    const color=colors[index%colors.length],cx=(q[0][0]+q[1][0]+q[2][0]+q[3][0])/4,cy=(q[0][1]+q[1][1]+q[2][1]+q[3][1])/4;
    const radius=Math.max(w,h)*.55,g=ctx.createRadialGradient(cx,cy,0,cx,cy,radius);
    g.addColorStop(0,"rgba("+color+","+Math.min(.28,strength)+")");
    g.addColorStop(.45,"rgba("+color+","+(strength*.45)+")");
    g.addColorStop(1,"rgba("+color+",0)");
    ctx.save();ctx.globalCompositeOperation="screen";ctx.fillStyle=g;ctx.fillRect(0,0,w,h);ctx.restore();
  }

  function drawSceneLabel(ctx,w,h,label,alpha){
    if(!label)return;
    ctx.save();ctx.globalAlpha=clamp(alpha,0,1);ctx.textAlign="center";ctx.textBaseline="middle";
    const font=Math.max(22,Math.round(w*.047));ctx.font="800 "+font+"px system-ui,sans-serif";
    ctx.shadowColor="rgba(0,0,0,.85)";ctx.shadowBlur=16;ctx.fillStyle="rgba(255,250,240,.95)";
    ctx.fillText(String(label).slice(0,52),w/2,h*.82);ctx.restore();
  }

  async function registerGenerated(project,blob,duration,name,metadata){
    const id=crypto.randomUUID(),ext=blob.type.includes("mp4")?".mp4":".webm";
    const asset={id,name:(name||"Cinematic-Portal")+ext,type:blob.type||"video/webm",size:blob.size,duration,kind:"video",generated:true,importedAt:new Date().toISOString(),cinematic:metadata};
    await idbPut("assets",{key:assetKey(project.id,id),blob,meta:asset});
    project.assets.push(asset);project.timeline.push({assetId:id,inPoint:0,outPoint:duration,speed:1,title:"Cinematic Portal"});
    project.generation={...(project.generation||{}),lastAssetId:id,lastMode:"cinematic_portal",generatedAt:new Date().toISOString()};
    await patchProject(project,{assets:project.assets,timeline:project.timeline,generation:project.generation});
    return asset;
  }

  async function renderPortal(options={}){
    if(state.busy)throw new Error("Cinematic renderer is already running");
    state.busy=true;state.lastFrame=null;state.trackOffset=[0,0];state.personMaskFrame=0;state.personMaskReady=false;
    if(Array.isArray(options.startQuad)&&options.startQuad.length===4) state.startQuad=options.startQuad.map(p=>[clamp(p[0],0,1),clamp(p[1],0,1)]);
    if(Array.isArray(options.endQuad)&&options.endQuad.length===4) state.endQuad=options.endQuad.map(p=>[clamp(p[0],0,1),clamp(p[1],0,1)]);
    const project=await getProject();
    const base=project.assets.find(a=>a.id===(options.baseAssetId||state.baseAssetId)&&a.kind==="video")||project.assets.find(a=>a.kind==="video"&&!a.generated);
    if(!base)throw new Error("Import a live-action base video first");
    const worldIds=(options.worldAssetIds&&options.worldAssetIds.length?options.worldAssetIds:state.worldAssetIds);
    const worldAssets=worldIds.map(id=>project.assets.find(a=>a.id===id)).filter(Boolean).filter(a=>a.kind==="video"||a.kind==="image");
    const baseLoaded=await loadVideo(project,base,true);
    const worldLoaded=[];
    try{
      for(const a of worldAssets){
        if(a.kind==="video")worldLoaded.push({...await loadVideo(project,a,false),kind:"video"});
        else worldLoaded.push({...await loadImage(project,a),kind:"image"});
      }
      const duration=clamp(options.duration||baseLoaded.video.duration||30,1,180);
      const fps=clamp(options.fps||30,12,60),aspect=options.aspect||project.settings.aspect||"9:16",quality=options.quality||project.settings.quality||"720p";
      const personOcclusion=(options.personOcclusion||($("vsPortalOcclusion")&&$("vsPortalOcclusion").value)||"auto")==="auto";
      if(personOcclusion){try{if($("vsPortalStatus"))$("vsPortalStatus").textContent="Loading local person segmentation…";await ensurePersonSegmenter();}catch(error){console.warn("Person segmentation unavailable",error);}}
      const [w,h]=dimensions(aspect,quality),canvas=document.createElement("canvas");canvas.width=w;canvas.height=h;const ctx=canvas.getContext("2d",{alpha:false});
      if(!canvas.captureStream||!window.MediaRecorder)throw new Error("This browser cannot record the cinematic composite");
      const stream=canvas.captureStream(fps);

      let audioCtx=null,audioSource=null,audioDest=null;
      try{
        audioCtx=new (window.AudioContext||window.webkitAudioContext)();await audioCtx.resume();
        audioSource=audioCtx.createMediaElementSource(baseLoaded.video);audioDest=audioCtx.createMediaStreamDestination();audioSource.connect(audioDest);
        audioDest.stream.getAudioTracks().forEach(t=>stream.addTrack(t));
      }catch(error){console.warn("Base audio capture unavailable",error);}

      const recorder=bestRecorder(stream,quality),chunks=[];
      recorder.ondataavailable=e=>{if(e.data&&e.data.size)chunks.push(e.data);};
      const stopped=new Promise(r=>recorder.onstop=r);
      const status=$("vsPortalStatus"),bar=$("vsPortalProgress");
      recorder.start(500);
      baseLoaded.video.currentTime=0;baseLoaded.video.muted=!audioSource;await baseLoaded.video.play();

      for(const world of worldLoaded)if(world.kind==="video"){world.video.currentTime=0;world.video.muted=true;world.video.loop=true;world.video.play().catch(()=>{});}

      const start=performance.now(),tracking=(options.tracking||$("vsPortalTracking")&&$("vsPortalTracking").value||"static");
      await new Promise((resolve,reject)=>{
        function frame(now){
          try{
            const elapsed=(now-start)/1000,t=clamp(elapsed/duration,0,1);
            ctx.fillStyle="#000";ctx.fillRect(0,0,w,h);drawCover(ctx,baseLoaded.video,w,h,1);
            if(tracking==="translation")trackTranslation(sampleGray(ctx,w,h));
            const q=quadAt(t).map(p=>[p[0]*w,p[1]*h]);

            let primary=null,secondary=null,mix=0;
            if(worldLoaded.length){
              const pos=(elapsed/duration)*worldLoaded.length,idx=Math.min(worldLoaded.length-1,Math.floor(pos)),local=pos-idx;
              primary=worldLoaded[idx];secondary=worldLoaded[Math.min(worldLoaded.length-1,idx+1)];
              mix=local>.78?(local-.78)/.22:0;
            }
            const drawWorld=(world,alpha,index)=>{
              ctx.save();pathQuad(ctx,q);ctx.clip();ctx.globalAlpha=alpha;
              ctx.filter="saturate(1.08) contrast(1.06) brightness(.96)";
              if(world){
                if(world.kind==="video"){
                  drawWarped(ctx,world.video,q,10);
                }else{
                  const off=drawWorld.imageCanvas||(drawWorld.imageCanvas=document.createElement("canvas"));off.width=640;off.height=360;
                  const ox=off.getContext("2d",{alpha:false});ox.fillStyle="#000";ox.fillRect(0,0,off.width,off.height);
                  const drift=Math.sin(elapsed*.22+index)*.018,zoom=1.06+.035*Math.sin(elapsed*.17+index*.7);
                  ox.save();ox.translate(drift*off.width,Math.cos(elapsed*.19+index)*off.height*.008);drawCover(ox,world.image,off.width,off.height,zoom);ox.restore();
                  drawWarped(ctx,off,q,10);
                }
              }else{
                const off=drawWorld.canvas||(drawWorld.canvas=document.createElement("canvas"));off.width=640;off.height=360;
                epicWorld(off.getContext("2d",{alpha:false}),off.width,off.height,elapsed,options.prompt||$("vsPortalPrompt")&&$("vsPortalPrompt").value||"epic mythological world",index||0);
                drawWarped(ctx,off,q,10);
              }
              ctx.restore();
            };
            const sceneIndex=Math.floor((elapsed/duration)*Math.max(1,worldLoaded.length||9));
            drawWorld(primary,1-mix,sceneIndex);if(secondary&&secondary!==primary&&mix>0)drawWorld(secondary,mix,sceneIndex+1);

            ctx.save();pathQuad(ctx,q);ctx.clip();ctx.globalCompositeOperation="screen";ctx.globalAlpha=clamp(options.reflection==null?.11:options.reflection,0,.35);ctx.filter="brightness(1.08) contrast(.92)";drawCover(ctx,baseLoaded.video,w,h,1);ctx.restore();
            ctx.globalCompositeOperation="source-over";ctx.globalAlpha=1;ctx.filter="none";

            const lightSpill=clamp(options.lightSpill==null?.07:options.lightSpill,0,.25);
            portalLightSpill(ctx,q,w,h,sceneIndex,lightSpill);

            if(personOcclusion&&state.personSegmenter){
              updatePersonMask(baseLoaded.video,elapsed);
              compositePersonOcclusion(ctx,baseLoaded.video,w,h);
            }

            ctx.save();pathQuad(ctx,q);ctx.strokeStyle="rgba(255,255,255,.18)";ctx.lineWidth=Math.max(1,w*.002);ctx.shadowColor="rgba(255,185,105,.18)";ctx.shadowBlur=12;ctx.stroke();ctx.restore();

            const labels=Array.isArray(options.sceneLabels)?options.sceneLabels:[];
            if(labels.length){
              const labelIndex=Math.min(labels.length-1,Math.floor((elapsed/duration)*labels.length));
              const localLabel=(elapsed/duration)*labels.length-labelIndex;
              const labelAlpha=Math.min(1,localLabel*4,(1-localLabel)*4);
              drawSceneLabel(ctx,w,h,labels[labelIndex],labelAlpha);
            }

            const pct=Math.round(t*100);if(bar)bar.style.width=pct+"%";if(status)status.textContent="Rendering cinematic portal • "+pct+"%";
            if(elapsed>=duration||baseLoaded.video.ended)resolve();else requestAnimationFrame(frame);
          }catch(error){reject(error);}
        }
        requestAnimationFrame(frame);
      });

      baseLoaded.video.pause();worldLoaded.forEach(x=>x.kind==="video"&&x.video.pause());
      if(recorder.state!=="inactive")recorder.stop();await stopped;stream.getTracks().forEach(t=>t.stop());
      try{audioSource&&audioSource.disconnect();}catch{};try{audioCtx&&await audioCtx.close();}catch{}
      const blob=new Blob(chunks,{type:recorder.mimeType||"video/webm"});
      const asset=await registerGenerated(project,blob,duration,"VideoStudio-Cinematic-Portal",{
        engine:"studio-web-portal-compositor-v1",baseAssetId:base.id,worldAssetIds:worldAssets.map(a=>a.id),prompt:String(options.prompt||"").slice(0,1000),tracking,startQuad:state.startQuad,endQuad:state.endQuad,reflection:options.reflection==null?.11:options.reflection,lightSpill:options.lightSpill==null?.07:options.lightSpill,sceneLabels:Array.isArray(options.sceneLabels)?options.sceneLabels.slice(0,24):[],personOcclusion:personOcclusion&&!!state.personSegmenter
      });
      if(status)status.textContent="Complete • "+asset.name+" • "+Math.round(blob.size/1048576)+" MB";
      if(bar)bar.style.width="100%";log("Cinematic portal complete",asset.name);toast("Cinematic world video added to project");
      if(localStorage.getItem("vs-drive-auto-upload")==="1"&&window.VideoStudioCloud){try{await window.VideoStudioCloud.uploadSingleAsset(project,asset);}catch{}}
      return{ok:true,realVideo:true,asset,engine:"studio-web-portal-compositor-v1",duration,worldCount:worldAssets.length||"procedural",tracking,note:"Base live-action video is preserved while generated/imported worlds are composited through a tracked perspective portal."};
    } finally {
      state.busy=false;URL.revokeObjectURL(baseLoaded.url);worldLoaded.forEach(x=>URL.revokeObjectURL(x.url));
    }
  }

  async function calibrate(){
    const project=await getProject(),base=project.assets.find(a=>a.id===state.baseAssetId&&a.kind==="video")||project.assets.find(a=>a.kind==="video"&&!a.generated);
    if(!base)return toast("Select a base video first");
    const loaded=await loadVideo(project,base,false),canvas=$("vsPortalCanvas"),ctx=canvas.getContext("2d");
    try{
      canvas.width=540;canvas.height=304;loaded.video.currentTime=state.calibrationTarget==="end"?Math.max(0,(loaded.video.duration||1)-.15):0;
      await new Promise(r=>{loaded.video.onseeked=r;setTimeout(r,700);});
      ctx.fillStyle="#000";ctx.fillRect(0,0,canvas.width,canvas.height);drawCover(ctx,loaded.video,canvas.width,canvas.height,1);
      const q=(state.calibrationTarget==="end"?state.endQuad:state.startQuad).map(p=>[p[0]*canvas.width,p[1]*canvas.height]);
      ctx.beginPath();q.forEach((p,i)=>i?ctx.lineTo(...p):ctx.moveTo(...p));ctx.closePath();ctx.strokeStyle="#48e5ff";ctx.lineWidth=3;ctx.stroke();
      q.forEach((p,i)=>{ctx.fillStyle="#ff4ff1";ctx.beginPath();ctx.arc(p[0],p[1],7,0,Math.PI*2);ctx.fill();ctx.fillStyle="#fff";ctx.font="11px system-ui";ctx.fillText(String(i+1),p[0]+9,p[1]-9);});
      state.clicks=[];$("vsPortalCalibrateHint").textContent="Tap 4 corners clockwise: top-left, top-right, bottom-right, bottom-left.";
    } finally {URL.revokeObjectURL(loaded.url);}
  }

  function canvasClick(event){
    const canvas=event.currentTarget,r=canvas.getBoundingClientRect(),x=(event.clientX-r.left)/r.width,y=(event.clientY-r.top)/r.height;
    state.clicks.push([clamp(x,0,1),clamp(y,0,1)]);
    if(state.clicks.length===4){
      if(state.calibrationTarget==="end")state.endQuad=state.clicks.slice();else state.startQuad=state.clicks.slice();
      $("vsPortalCalibrateHint").textContent=(state.calibrationTarget==="end"?"End":"Start")+" quad saved.";
      state.clicks=[];
      calibrate().catch(()=>{});
    }
  }

  async function refreshSelectors(){
    if(!$("vsPortalBase"))return;
    try{
      const p=await getProject(),baseSel=$("vsPortalBase"),world=$("vsPortalWorld");
      const oldBase=baseSel.value,oldWorld=[...world.selectedOptions].map(o=>o.value);
      baseSel.innerHTML="";world.innerHTML="";
      p.assets.filter(a=>a.kind==="video").forEach(a=>{const o=document.createElement("option");o.value=a.id;o.textContent=(a.generated?"✦ ":"")+a.name;baseSel.appendChild(o);});
      p.assets.filter(a=>a.kind==="image"||a.kind==="video").forEach(a=>{const o=document.createElement("option");o.value=a.id;o.textContent=(a.generated?"✦ ":"")+a.name;world.appendChild(o);});
      if(oldBase&&[...baseSel.options].some(o=>o.value===oldBase))baseSel.value=oldBase;
      state.baseAssetId=baseSel.value||state.baseAssetId;
      if(oldWorld.length)for(const o of world.options)o.selected=oldWorld.includes(o.value);
      state.worldAssetIds=[...world.selectedOptions].map(o=>o.value);
    }catch{}
  }

  function inject(){
    if($("vsCinematicWorlds"))return;
    const host=$("vsRuntimeRoot")||$("aiSection");if(!host)return;
    const style=document.createElement("style");style.textContent=
      ".vsCinema{margin-top:12px;border:1px solid rgba(245,158,11,.25);background:linear-gradient(145deg,rgba(47,27,9,.22),rgba(9,12,20,.98));border-radius:16px;padding:13px}.vsCinema h4{margin:0}.vsCinemaGrid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.vsCinema select,.vsCinema input,.vsCinema textarea{width:100%;box-sizing:border-box;border:1px solid #333d55;background:#080c14;color:#eef4ff;border-radius:10px;padding:8px}.vsCinema textarea{min-height:62px}.vsCinema label{display:block;margin-top:9px;font-size:9px;color:#9aa6bc;font-weight:800;letter-spacing:.06em}.vsCinema .row{display:flex;gap:7px;flex-wrap:wrap;margin-top:9px}.vsCinema button{border:1px solid #39445d;background:#131a29;color:#f2f6ff;border-radius:10px;padding:8px 10px;font-size:10px;font-weight:800}.vsCinema button.primary{background:linear-gradient(135deg,#f59e0b,#e11d48);border-color:transparent}.vsCinema canvas{width:100%;border-radius:12px;background:#000;margin-top:8px;touch-action:none}.vsCinema .progress{height:6px;background:#1b2433;border-radius:999px;overflow:hidden;margin-top:9px}.vsCinema .progress i{display:block;height:100%;width:0;background:linear-gradient(90deg,#f59e0b,#fb3fbf)}.vsCinema .muted{font-size:10px;color:#97a3b8;margin-top:5px}@media(max-width:820px){.vsCinemaGrid{grid-template-columns:1fr}}";
    document.head.appendChild(style);
    const card=document.createElement("div");card.id="vsCinematicWorlds";card.className="vsCinema";
    card.innerHTML='<h4>Cinematic Worlds <span class="vsBadge">TARGET EFFECT ENGINE</span></h4><div class="muted">Built for videos where a real person/room/train stays intact while impossible cinematic worlds move outside a window, screen, doorway or other portal. Uses real perspective compositing, scene sequencing, reflections and optional translation tracking.</div>'+
      '<div class="vsCinemaGrid"><div><label>BASE LIVE-ACTION VIDEO</label><select id="vsPortalBase"></select><label>WORLD ASSETS · multi-select</label><select id="vsPortalWorld" multiple size="5"></select><label>WORLD PROMPT · used when no world assets are selected</label><textarea id="vsPortalPrompt" placeholder="Epic mythological mountain realm, colossal divine figure, cinematic sunset, clouds, waterfalls"></textarea><label>SCENE LABELS · comma separated, optional</label><input id="vsPortalLabels" placeholder="Shailputri, Brahmacharini, Chandraghanta, Kushmanda"></div>'+
      '<div><label>PORTAL TRACKING</label><select id="vsPortalTracking"><option value="static">Static / keyframed quad</option><option value="translation">Auto translation tracking + keyframes</option></select><label>FOREGROUND OCCLUSION</label><select id="vsPortalOcclusion"><option value="auto">Auto person segmentation · local AI</option><option value="off">Off</option></select><label>DURATION SECONDS</label><input id="vsPortalDuration" type="number" min="1" max="180" value="30"><label>WINDOW REFLECTION</label><input id="vsPortalReflection" type="range" min="0" max=".35" step=".01" value=".11"><label>LIGHT SPILL INTO REAL SCENE</label><input id="vsPortalLightSpill" type="range" min="0" max=".25" step=".01" value=".07"><div class="row"><button id="vsPortalPreset">Right-window preset</button><button id="vsPortalStart">Set start corners</button><button id="vsPortalEnd">Set end corners</button></div></div></div>'+
      '<canvas id="vsPortalCanvas" width="540" height="304"></canvas><div id="vsPortalCalibrateHint" class="muted">Select a base clip, then use the preset or calibrate four corners.</div>'+
      '<div class="row"><button id="vsPortalRender" class="primary">Render cinematic world video</button><button id="vsPortalRefresh">Refresh media</button></div><div class="progress"><i id="vsPortalProgress"></i></div><div id="vsPortalStatus" class="muted">Ready. Use imported/generated image or video worlds for the highest realism. The compositor preserves the real foreground instead of regenerating it.</div>';
    host.appendChild(card);

    $("vsPortalBase").onchange=()=>{state.baseAssetId=$("vsPortalBase").value;calibrate().catch(e=>toast(e.message));};
    $("vsPortalWorld").onchange=()=>state.worldAssetIds=[...$("vsPortalWorld").selectedOptions].map(o=>o.value);
    $("vsPortalCanvas").addEventListener("pointerdown",canvasClick);
    $("vsPortalPreset").onclick=()=>{state.startQuad=[[.43,.08],[.97,.08],[.97,.72],[.43,.72]];state.endQuad=state.startQuad.map(p=>p.slice());calibrate().catch(()=>{});};
    $("vsPortalStart").onclick=()=>{state.calibrationTarget="start";calibrate().catch(e=>toast(e.message));};
    $("vsPortalEnd").onclick=()=>{state.calibrationTarget="end";calibrate().catch(e=>toast(e.message));};
    $("vsPortalRefresh").onclick=()=>refreshSelectors();
    $("vsPortalRender").onclick=async()=>{
      try{
        state.baseAssetId=$("vsPortalBase").value;state.worldAssetIds=[...$("vsPortalWorld").selectedOptions].map(o=>o.value);
        const p=await getProject();
        await renderPortal({baseAssetId:state.baseAssetId,worldAssetIds:state.worldAssetIds,prompt:$("vsPortalPrompt").value,sceneLabels:$("vsPortalLabels").value.split(",").map(x=>x.trim()).filter(Boolean),duration:Number($("vsPortalDuration").value||30),tracking:$("vsPortalTracking").value,personOcclusion:$("vsPortalOcclusion").value,reflection:Number($("vsPortalReflection").value),lightSpill:Number($("vsPortalLightSpill").value),aspect:p.settings.aspect||"9:16",quality:p.settings.quality||"720p"});
        setTimeout(()=>location.reload(),900);
      }catch(e){toast(e.message);$("vsPortalStatus").textContent=e.message;}
    };
    refreshSelectors().then(()=>{if(state.baseAssetId)calibrate().catch(()=>{});});
    setInterval(refreshSelectors,15000);
  }

  window.VideoStudioCinematic={renderPortal,state,refreshSelectors};
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",inject);
  else inject();
})();
`;

export default STUDIO_CINEMATIC_JS;
