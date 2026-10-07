const STUDIO_RUNTIME_JS = String.raw`
(() => {
  "use strict";

  const root = document;
  const byId = (id) => document.getElementById(id);
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const clamp = (v, min, max) => Math.max(min, Math.min(max, Number(v)));
  const runtimeState = {
    driveToken: null,
    driveTokenExpiresAt: 0,
    driveClientId: localStorage.getItem("vs-drive-client-id") || "",
    driveRootId: null,
    busy: false,
    lastRuntimeSeq: Number(localStorage.getItem("vs-runtime-last-seq") || 0),
    googleLoaded: false
  };

  function currentProjectId() {
    return localStorage.getItem("vs-project-id") || "";
  }

  function deviceId() {
    return localStorage.getItem("vs-device-id") || "";
  }

  async function api(path, options) {
    const response = await fetch(path, {
      headers: { "content-type": "application/json", ...((options && options.headers) || {}) },
      ...(options || {})
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || "Request failed");
    return data;
  }

  function openDb() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open("videostudio-local", 3);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains("assets")) db.createObjectStore("assets", { keyPath: "key" });
        if (!db.objectStoreNames.contains("renders")) db.createObjectStore("renders", { keyPath: "key" });
        if (!db.objectStoreNames.contains("thumbs")) db.createObjectStore("thumbs", { keyPath: "key" });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  async function idbGet(store, key) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readonly");
      const req = tx.objectStore(store).get(key);
      req.onsuccess = () => { db.close(); resolve(req.result || null); };
      req.onerror = () => { db.close(); reject(req.error); };
    });
  }

  async function idbPut(store, value) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readwrite");
      tx.objectStore(store).put(value);
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    });
  }

  async function idbDelete(store, key) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readwrite");
      tx.objectStore(store).delete(key);
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    });
  }

  function assetKey(projectId, assetId) {
    return projectId + ":" + assetId;
  }

  async function getProject() {
    const pid = currentProjectId();
    if (!pid) throw new Error("Create or select a project first");
    const projects = await api("/api/projects?deviceId=" + encodeURIComponent(deviceId()));
    const project = (projects.projects || []).find((p) => p.id === pid);
    if (!project) throw new Error("Selected project is unavailable");
    project.assets = project.assets || [];
    project.timeline = project.timeline || [];
    project.settings = {
      aspect: "9:16",
      quality: "720p",
      transition: "fade",
      speed: 1,
      mute: false,
      title: "",
      ...(project.settings || {})
    };
    project.drive = project.drive || {};
    project.generation = project.generation || {};
    return project;
  }

  async function patchProject(project, patch) {
    const result = await api("/api/projects/" + encodeURIComponent(project.id), {
      method: "POST",
      body: JSON.stringify({ deviceId: deviceId(), patch })
    });
    return result.project;
  }

  function toast(message) {
    const existing = byId("toast");
    if (existing) {
      existing.textContent = message;
      existing.classList.add("show");
      clearTimeout(toast._timer);
      toast._timer = setTimeout(() => existing.classList.remove("show"), 2600);
      return;
    }
    console.log("[VideoStudio]", message);
  }

  function addLog(title, detail) {
    const log = byId("commandLog");
    if (!log) return;
    const item = document.createElement("div");
    item.className = "log";
    const strong = document.createElement("strong");
    strong.textContent = title;
    const span = document.createElement("span");
    span.textContent = detail || "";
    item.append(strong, span);
    log.prepend(item);
  }

  function humanBytes(bytes) {
    const n = Number(bytes || 0);
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
    if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
    return (n / 1073741824).toFixed(2) + " GB";
  }

  function dimensions(aspect, quality) {
    const base = quality === "1080p" ? 1080 : 720;
    if (aspect === "16:9") return [Math.round(base * 16 / 9), base];
    if (aspect === "1:1") return [base, base];
    if (aspect === "4:5") return [base, Math.round(base * 5 / 4)];
    return [base, Math.round(base * 16 / 9)];
  }

  function bestRecorder(stream, bitrate) {
    const types = [
      "video/mp4;codecs=avc1.42E01E,mp4a.40.2",
      "video/mp4",
      "video/webm;codecs=vp9,opus",
      "video/webm;codecs=vp8,opus",
      "video/webm"
    ];
    for (const type of types) {
      if (window.MediaRecorder && MediaRecorder.isTypeSupported(type)) {
        try { return new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: bitrate }); }
        catch {}
      }
    }
    return new MediaRecorder(stream, { videoBitsPerSecond: bitrate });
  }

  function hashSeed(text) {
    let h = 2166136261 >>> 0;
    const value = String(text || "VideoStudio");
    for (let i = 0; i < value.length; i++) {
      h ^= value.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return h >>> 0;
  }

  function randomFactory(seed) {
    let x = seed || 1;
    return () => {
      x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
      return (x >>> 0) / 4294967296;
    };
  }

  function paletteFor(prompt, style) {
    const p = String(prompt || "").toLowerCase();
    if (p.includes("ocean") || p.includes("water") || p.includes("river")) return ["#061a2b","#0e5c74","#52d8d8","#d8fbff"];
    if (p.includes("forest") || p.includes("nature")) return ["#06170f","#125936","#66c46f","#d9ffd3"];
    if (p.includes("fire") || p.includes("sunset")) return ["#1a0806","#7d1e0b","#f56d26","#ffd27a"];
    if (p.includes("space") || p.includes("galaxy")) return ["#050510","#1e164d","#6d4df2","#d8d0ff"];
    if (p.includes("city") || p.includes("neon") || style === "neon") return ["#070912","#28124c","#00d9ff","#f04cff"];
    if (style === "mono") return ["#050607","#20242a","#8c96a3","#f4f7fb"];
    return ["#070b14","#1b2754","#7452f4","#4fe3dc"];
  }

  function drawCover(ctx, source, width, height, scale) {
    const sw = source.videoWidth || source.naturalWidth || source.width || width;
    const sh = source.videoHeight || source.naturalHeight || source.height || height;
    const fit = Math.max(width / sw, height / sh) * (scale || 1);
    const dw = sw * fit, dh = sh * fit;
    ctx.drawImage(source, (width - dw) / 2, (height - dh) / 2, dw, dh);
  }

  async function recordCanvas(options) {
    if (!HTMLCanvasElement.prototype.captureStream || !window.MediaRecorder) {
      throw new Error("This browser does not support local video generation");
    }
    if (document.hidden) throw new Error("Keep Studio Web visible while browser-local generation is running");

    const width = options.width, height = options.height;
    const fps = clamp(options.fps || 30, 12, 60);
    const duration = clamp(options.duration || 6, 1, 60);
    const canvas = document.createElement("canvas");
    canvas.width = width; canvas.height = height;
    const ctx = canvas.getContext("2d", { alpha: false, willReadFrequently: false });
    const stream = canvas.captureStream(fps);
    (options.audioTracks || []).forEach((track) => stream.addTrack(track));

    const recorder = bestRecorder(stream, options.quality === "1080p" ? 8500000 : 4200000);
    const chunks = [];
    recorder.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    const stopped = new Promise((resolve) => { recorder.onstop = resolve; });
    recorder.start(500);

    const start = performance.now();
    let lastUi = 0;
    await new Promise((resolve, reject) => {
      function frame(now) {
        try {
          const elapsed = Math.max(0, (now - start) / 1000);
          const t = Math.min(1, elapsed / duration);
          options.draw(ctx, width, height, t, elapsed, duration);
          if (options.onProgress && now - lastUi > 180) {
            lastUi = now;
            options.onProgress(Math.round(t * 100));
          }
          if (elapsed >= duration) resolve();
          else requestAnimationFrame(frame);
        } catch (error) {
          reject(error);
        }
      }
      requestAnimationFrame(frame);
    });

    if (recorder.state !== "inactive") recorder.stop();
    await stopped;
    stream.getTracks().forEach((track) => track.stop());
    return new Blob(chunks, { type: recorder.mimeType || "video/webm" });
  }

  async function loadImageAsset(project, asset) {
    const stored = await idbGet("assets", assetKey(project.id, asset.id));
    if (!stored || !stored.blob) throw new Error("Selected image is not stored locally. Restore it from Drive first.");
    const url = URL.createObjectURL(stored.blob);
    const image = new Image();
    await new Promise((resolve, reject) => { image.onload = resolve; image.onerror = reject; image.src = url; });
    return { image, url, blob: stored.blob };
  }

  async function loadVideoAsset(project, asset) {
    const stored = await idbGet("assets", assetKey(project.id, asset.id));
    if (!stored || !stored.blob) throw new Error("Selected video is not stored locally. Restore it from Drive first.");
    const url = URL.createObjectURL(stored.blob);
    const video = document.createElement("video");
    video.playsInline = true;
    video.preload = "auto";
    video.src = url;
    await new Promise((resolve, reject) => { video.onloadedmetadata = resolve; video.onerror = reject; });
    return { video, url, blob: stored.blob };
  }

  async function createVideoThumbnail(blob) {
    const url = URL.createObjectURL(blob);
    const video = document.createElement("video");
    video.muted = true; video.playsInline = true; video.preload = "metadata"; video.src = url;
    try {
      await new Promise((resolve, reject) => { video.onloadedmetadata = resolve; video.onerror = reject; });
      video.currentTime = Math.min(Math.max(.05, Number(video.duration || 0) * .12), Math.max(.05, Number(video.duration || 0) - .05));
      await new Promise((resolve) => { video.onseeked = resolve; setTimeout(resolve, 900); });
      const canvas = document.createElement("canvas"); canvas.width = 320; canvas.height = 180;
      const ctx = canvas.getContext("2d", { alpha:false }); ctx.fillStyle = "#05070b"; ctx.fillRect(0,0,320,180);
      drawCover(ctx, video, 320, 180, 1);
      return canvas.toDataURL("image/jpeg", .72);
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  async function registerGeneratedVideo(project, blob, options) {
    const id = crypto.randomUUID();
    const ext = blob.type.includes("mp4") ? ".mp4" : ".webm";
    const base = String(options.name || options.mode || "generated-video").replace(/[^a-z0-9_-]+/gi, "-").replace(/^-|-$/g, "").slice(0,80) || "generated-video";
    const asset = {
      id,
      name: base + ext,
      type: blob.type || "video/webm",
      size: blob.size,
      duration: Number(options.duration || 0),
      kind: "video",
      generated: true,
      importedAt: new Date().toISOString(),
      generation: {
        engine: options.engine || "studio-web-local",
        mode: options.mode || "prompt_scene",
        prompt: String(options.prompt || "").slice(0,1000),
        style: options.style || "cinematic",
        seed: options.seed || 0
      }
    };
    await idbPut("assets", { key: assetKey(project.id, id), blob, meta: asset });
    try {
      const dataUrl = await createVideoThumbnail(blob);
      await idbPut("thumbs", { key: assetKey(project.id, id), dataUrl, updatedAt: Date.now() });
    } catch {}

    project.assets.push(asset);
    project.timeline.push({ assetId:id, inPoint:0, outPoint:Math.max(.1, Number(options.duration || 0)), speed:1 });
    project.generation = {
      lastAssetId:id,
      lastMode:options.mode,
      lastPrompt:String(options.prompt || "").slice(0,1000),
      generatedAt:new Date().toISOString()
    };
    await patchProject(project, { assets:project.assets, timeline:project.timeline, generation:project.generation });
    return asset;
  }

  function drawPromptScene(ctx, width, height, t, elapsed, prompt, style, seed) {
    const colors = paletteFor(prompt, style);
    const rng = randomFactory(seed);
    const g = ctx.createLinearGradient(0,0,width,height);
    g.addColorStop(0, colors[0]); g.addColorStop(.45, colors[1]); g.addColorStop(1, colors[2]);
    ctx.fillStyle = g; ctx.fillRect(0,0,width,height);

    const p = String(prompt || "").toLowerCase();
    const horizon = height * (.55 + Math.sin(elapsed * .18) * .025);
    if (p.includes("ocean") || p.includes("water") || p.includes("river")) {
      ctx.fillStyle = "rgba(120,235,255,.10)";
      for (let i=0;i<24;i++) {
        const y = horizon + i * height/50;
        const amp = 12 + i * 1.8;
        ctx.beginPath();
        for(let x=0;x<=width;x+=24){
          const yy = y + Math.sin(x*.012 + elapsed*(1.1+i*.025))*amp;
          if(x===0)ctx.moveTo(x,yy); else ctx.lineTo(x,yy);
        }
        ctx.strokeStyle = "rgba(145,238,255," + (0.22 - i*.004) + ")";
        ctx.lineWidth = 2; ctx.stroke();
      }
    } else if (p.includes("city")) {
      const baseY = height*.78;
      for(let i=0;i<34;i++){
        const x=i*width/34, h=(.10+rng()*.38)*height, w=width/38;
        ctx.fillStyle=i%3===0?"rgba(20,240,255,.22)":"rgba(255,255,255,.07)";
        ctx.fillRect(x,baseY-h,w,h);
        ctx.fillStyle="rgba(255,230,120,.45)";
        for(let y=baseY-h+12;y<baseY-8;y+=20) if((i+y)%3) ctx.fillRect(x+5,y,3,5);
      }
    } else if (p.includes("forest") || p.includes("nature")) {
      for(let layer=0;layer<4;layer++){
        const y=height*(.56+layer*.12);
        ctx.fillStyle="rgba(3,25,13,"+(.35+layer*.13)+")";
        for(let x=-40;x<width+40;x+=42-layer*5){
          const h=height*(.12+layer*.04);
          ctx.beginPath();ctx.moveTo(x,y+h);ctx.lineTo(x+18,y);ctx.lineTo(x+36,y+h);ctx.fill();
        }
      }
    } else {
      for(let i=0;i<75;i++){
        const a=rng()*Math.PI*2, radius=(.12+rng()*.52)*Math.min(width,height);
        const x=width/2+Math.cos(a+elapsed*(.08+i%4*.025))*radius;
        const y=height/2+Math.sin(a+elapsed*(.10+i%5*.02))*radius*.72;
        const size=1+rng()*5;
        ctx.fillStyle=i%3===0?colors[3]:"rgba(255,255,255,.35)";
        ctx.globalAlpha=.18+.55*rng();ctx.beginPath();ctx.arc(x,y,size,0,Math.PI*2);ctx.fill();
      }
      ctx.globalAlpha=1;
    }

    const words = String(prompt || "Create without limits").trim().split(/\s+/).slice(0,7);
    if (words.length) {
      ctx.save();
      ctx.textAlign="center";ctx.textBaseline="middle";
      const pulse=.97+Math.sin(elapsed*1.7)*.03;
      ctx.translate(width/2,height*.26);ctx.scale(pulse,pulse);
      ctx.font="800 "+Math.round(Math.max(28,width*.055))+"px system-ui,sans-serif";
      ctx.shadowColor="rgba(0,0,0,.55)";ctx.shadowBlur=28;
      ctx.fillStyle="rgba(248,252,255,.94)";
      ctx.fillText(words.join(" ").slice(0,62),0,0);
      ctx.restore();
    }

    ctx.fillStyle="rgba(0,0,0,"+(0.05+Math.sin(t*Math.PI)*.05)+")";ctx.fillRect(0,0,width,height);
  }

  function drawMotionGraphics(ctx,width,height,t,elapsed,prompt,style,seed){
    const colors=paletteFor(prompt,style), rng=randomFactory(seed);
    ctx.fillStyle=colors[0];ctx.fillRect(0,0,width,height);
    const words=String(prompt||"VIDEO STUDIO MOTION").toUpperCase().split(/\s+/).slice(0,8);
    for(let i=0;i<18;i++){
      const phase=(i/18+t*1.6)%1;
      const w=width*(.08+.32*rng()), h=8+height*.018*rng();
      const x=(phase*1.35-.15)*width;
      const y=height*(.08+i/20);
      ctx.globalAlpha=.18+.28*(i%3);
      ctx.fillStyle=colors[(i%3)+1]||colors[2];
      ctx.save();ctx.translate(x,y);ctx.rotate((i%2?1:-1)*.08);ctx.fillRect(-w/2,-h/2,w,h);ctx.restore();
    }
    ctx.globalAlpha=1;
    words.forEach((word,i)=>{
      const local=(t*words.length-i);
      const enter=clamp(local,0,1), exit=clamp(words.length+1-t*words.length-i,0,1);
      const alpha=Math.min(enter*1.6,exit,1);
      const y=height*(.28+(i%4)*.15);
      const x=width/2+(1-enter)*width*(i%2?-.7:.7);
      ctx.save();ctx.globalAlpha=alpha;ctx.textAlign="center";ctx.textBaseline="middle";
      ctx.font="900 "+Math.round(Math.min(width*.14,height*.11))+"px system-ui,sans-serif";
      ctx.fillStyle=i%2?colors[3]:"#ffffff";ctx.translate(x,y);ctx.rotate((i%2?1:-1)*(.08-.08*enter));
      ctx.fillText(word.slice(0,18),0,0);ctx.restore();
    });
    ctx.globalAlpha=1;
  }

  function drawAbstract(ctx,width,height,t,elapsed,prompt,style,seed){
    const colors=paletteFor(prompt,style), rng=randomFactory(seed);
    const g=ctx.createRadialGradient(width*(.35+.2*Math.sin(elapsed*.2)),height*.4,10,width*.5,height*.5,Math.max(width,height));
    g.addColorStop(0,colors[2]);g.addColorStop(.5,colors[1]);g.addColorStop(1,colors[0]);
    ctx.fillStyle=g;ctx.fillRect(0,0,width,height);
    ctx.globalCompositeOperation="screen";
    for(let i=0;i<42;i++){
      const a=i*.83+elapsed*(.1+(i%7)*.018), r=(.08+(i%11)/15)*Math.min(width,height);
      const x=width/2+Math.cos(a)*r, y=height/2+Math.sin(a*1.2)*r*.7;
      const size=(18+rng()*90)*(1+.35*Math.sin(elapsed+i));
      ctx.fillStyle=i%3===0?colors[3]:i%3===1?colors[2]:colors[1];
      ctx.globalAlpha=.05+.12*(i%5);ctx.beginPath();ctx.arc(x,y,size,0,Math.PI*2);ctx.fill();
    }
    ctx.globalCompositeOperation="source-over";ctx.globalAlpha=1;
  }

  function rotatePoint(p,ax,ay,az){
    let x=p[0],y=p[1],z=p[2];
    let c=Math.cos(ax),s=Math.sin(ax);let y1=y*c-z*s,z1=y*s+z*c;y=y1;z=z1;
    c=Math.cos(ay);s=Math.sin(ay);let x1=x*c+z*s,z2=-x*s+z*c;x=x1;z=z2;
    c=Math.cos(az);s=Math.sin(az);x1=x*c-y*s;y=x*s+y*c;x=x1;
    return [x,y,z];
  }

  function drawProcedural3d(ctx,width,height,t,elapsed,prompt,style,seed){
    const colors=paletteFor(prompt,style), rng=randomFactory(seed);
    const bg=ctx.createLinearGradient(0,0,0,height);bg.addColorStop(0,colors[0]);bg.addColorStop(1,"#020306");
    ctx.fillStyle=bg;ctx.fillRect(0,0,width,height);
    for(let i=0;i<80;i++){
      const x=(rng()*width+elapsed*(10+i%9))%width,y=rng()*height;
      ctx.fillStyle="rgba(255,255,255,"+(.15+.5*rng())+")";ctx.fillRect(x,y,1+rng()*2,1+rng()*2);
    }
    const verts=[[-1,-1,-1],[1,-1,-1],[1,1,-1],[-1,1,-1],[-1,-1,1],[1,-1,1],[1,1,1],[-1,1,1]];
    const edges=[[0,1],[1,2],[2,3],[3,0],[4,5],[5,6],[6,7],[7,4],[0,4],[1,5],[2,6],[3,7]];
    const ax=elapsed*.45,ay=elapsed*.63,az=elapsed*.21;
    const size=Math.min(width,height)*.22;
    const projected=verts.map(v=>{
      const p=rotatePoint(v,ax,ay,az), depth=4.5+p[2], scale=2.3/depth;
      return [width/2+p[0]*size*scale,height/2+p[1]*size*scale,p[2]];
    });
    ctx.lineWidth=Math.max(2,width*.004);ctx.strokeStyle=colors[2];ctx.shadowColor=colors[2];ctx.shadowBlur=18;
    edges.forEach(e=>{ctx.beginPath();ctx.moveTo(projected[e[0]][0],projected[e[0]][1]);ctx.lineTo(projected[e[1]][0],projected[e[1]][1]);ctx.stroke();});
    ctx.shadowBlur=0;
    ctx.textAlign="center";ctx.font="800 "+Math.round(width*.048)+"px system-ui,sans-serif";ctx.fillStyle="rgba(255,255,255,.88)";
    ctx.fillText(String(prompt||"3D SCENE").slice(0,45),width/2,height*.82);
  }

  async function generateImageMotion(project, options, progress) {
    const asset = project.assets.find(a=>a.id===options.assetId&&a.kind==="image") || project.assets.find(a=>a.kind==="image");
    if(!asset) throw new Error("Image-to-video needs at least one image asset");
    const loaded=await loadImageAsset(project,asset);
    const [width,height]=dimensions(options.aspect||project.settings.aspect,options.quality||project.settings.quality);
    try {
      const seed=Number(options.seed||hashSeed(options.prompt||asset.name));
      const blob=await recordCanvas({
        width,height,quality:options.quality||project.settings.quality,duration:options.duration,fps:options.fps,
        onProgress:progress,
        draw:(ctx,w,h,t,elapsed)=>{
          ctx.fillStyle="#020305";ctx.fillRect(0,0,w,h);
          const bands=22;
          for(let i=0;i<bands;i++){
            const y0=i*h/bands,y1=(i+1)*h/bands;
            const depth=(i/(bands-1)-.5);
            const zoom=1.05+.11*t+.018*Math.sin(elapsed*.8+depth*4);
            const drift=Math.sin(elapsed*.55+depth*2.5)*w*.014*depth;
            ctx.save();ctx.beginPath();ctx.rect(0,y0,w,y1-y0+1);ctx.clip();ctx.translate(drift,depth*h*.018*Math.sin(elapsed*.7));
            drawCover(ctx,loaded.image,w,h,zoom);ctx.restore();
          }
          const colors=paletteFor(options.prompt,options.style);
          ctx.fillStyle=colors[2];ctx.globalAlpha=.06;
          for(let i=0;i<28;i++){const x=(i*97+elapsed*(18+i%4))%w,y=(i*173+Math.sin(elapsed+i)*h*.08)%h;ctx.beginPath();ctx.arc(x,y,2+(i%4),0,Math.PI*2);ctx.fill();}
          ctx.globalAlpha=1;
          const vign=ctx.createRadialGradient(w/2,h/2,Math.min(w,h)*.2,w/2,h/2,Math.max(w,h)*.72);vign.addColorStop(.55,"rgba(0,0,0,0)");vign.addColorStop(1,"rgba(0,0,0,.42)");ctx.fillStyle=vign;ctx.fillRect(0,0,w,h);
        }
      });
      return {blob,engine:"studio-web-image-motion-v1"};
    } finally { URL.revokeObjectURL(loaded.url); }
  }

  async function generateStory(project, options, progress) {
    const assets=project.assets.filter(a=>a.kind==="image").slice(0,24);
    if(!assets.length) throw new Error("Story video needs image assets");
    const loaded=[];
    try {
      for(const asset of assets) loaded.push(await loadImageAsset(project,asset));
      const [width,height]=dimensions(options.aspect||project.settings.aspect,options.quality||project.settings.quality);
      const duration=Math.max(Number(options.duration||8),assets.length*1.4);
      const blob=await recordCanvas({
        width,height,quality:options.quality||project.settings.quality,duration,fps:options.fps,onProgress:progress,
        draw:(ctx,w,h,t,elapsed)=>{
          const pos=(elapsed/duration)*assets.length;
          const index=Math.min(assets.length-1,Math.floor(pos));
          const local=pos-index;
          const next=Math.min(assets.length-1,index+1);
          ctx.fillStyle="#000";ctx.fillRect(0,0,w,h);
          ctx.save();ctx.globalAlpha=1;drawCover(ctx,loaded[index].image,w,h,1.03+local*.08);ctx.restore();
          if(next!==index && local>.76){ctx.save();ctx.globalAlpha=(local-.76)/.24;drawCover(ctx,loaded[next].image,w,h,1.01);ctx.restore();}
          ctx.globalAlpha=1;
          const vign=ctx.createLinearGradient(0,0,0,h);vign.addColorStop(0,"rgba(0,0,0,.18)");vign.addColorStop(.55,"rgba(0,0,0,0)");vign.addColorStop(1,"rgba(0,0,0,.32)");ctx.fillStyle=vign;ctx.fillRect(0,0,w,h);
        }
      });
      options.duration=duration;
      return {blob,engine:"studio-web-story-v1"};
    } finally { loaded.forEach(x=>URL.revokeObjectURL(x.url)); }
  }

  async function generateVideoRestyle(project, options, progress) {
    const asset=project.assets.find(a=>a.id===options.assetId&&a.kind==="video")||project.assets.find(a=>a.kind==="video");
    if(!asset) throw new Error("Video-to-video restyle needs a video asset");
    const loaded=await loadVideoAsset(project,asset);
    const video=loaded.video;
    const duration=Math.min(Number(options.duration||video.duration||8),Number(video.duration||8),60);
    const [width,height]=dimensions(options.aspect||project.settings.aspect,options.quality||project.settings.quality);
    let audioCtx=null,dest=null,source=null;
    const tracks=[];
    try {
      try {
        audioCtx=new (window.AudioContext||window.webkitAudioContext)();await audioCtx.resume();
        dest=audioCtx.createMediaStreamDestination();source=audioCtx.createMediaElementSource(video);source.connect(dest);tracks.push(...dest.stream.getAudioTracks());
      } catch {}
      video.currentTime=0;video.muted=!source;
      await video.play();
      const style=options.style||"cinematic";
      const blob=await recordCanvas({
        width,height,quality:options.quality||project.settings.quality,duration,fps:options.fps,audioTracks:tracks,onProgress:progress,
        draw:(ctx,w,h,t)=>{
          ctx.save();
          if(style==="mono")ctx.filter="grayscale(1) contrast(1.2)";
          else if(style==="neon")ctx.filter="saturate(1.55) contrast(1.12) hue-rotate(12deg)";
          else if(style==="dreamy")ctx.filter="saturate(.86) brightness(1.08) contrast(.92)";
          else ctx.filter="saturate(.93) contrast(1.08) brightness(.98)";
          drawCover(ctx,video,w,h,1.02+.025*Math.sin(t*Math.PI));
          ctx.restore();
          if(style==="neon"){ctx.globalCompositeOperation="screen";ctx.globalAlpha=.06;ctx.fillStyle="#2ee9ff";ctx.fillRect(3,0,w,h);ctx.globalAlpha=1;ctx.globalCompositeOperation="source-over";}
          if(style==="film"){ctx.globalAlpha=.08;ctx.fillStyle="#f7c58a";ctx.fillRect(0,0,w,h);ctx.globalAlpha=1;}
        }
      });
      video.pause();
      options.duration=duration;
      return {blob,engine:"studio-web-video-restyle-v1"};
    } finally {
      try{video.pause();}catch{};try{source&&source.disconnect();}catch{};try{audioCtx&&await audioCtx.close();}catch{};URL.revokeObjectURL(loaded.url);
    }
  }

  async function generateAudioVisualizer(project, options, progress) {
    const asset=project.assets.find(a=>a.id===options.assetId&&(a.kind==="audio"||a.kind==="video"))||project.assets.find(a=>a.kind==="audio");
    if(!asset) throw new Error("Audio visualizer needs an audio asset");
    const stored=await idbGet("assets",assetKey(project.id,asset.id));
    if(!stored||!stored.blob)throw new Error("Audio is not stored locally");
    const audioCtx=new (window.AudioContext||window.webkitAudioContext)();
    const buffer=await audioCtx.decodeAudioData(await stored.blob.arrayBuffer());
    const duration=Math.min(Number(options.duration||buffer.duration||8),buffer.duration,60);
    const source=audioCtx.createBufferSource();source.buffer=buffer;
    const dest=audioCtx.createMediaStreamDestination();source.connect(dest);
    const [width,height]=dimensions(options.aspect||project.settings.aspect,options.quality||project.settings.quality);
    const data=buffer.getChannelData(0),rate=buffer.sampleRate;
    source.start(0,0,duration);
    try{
      const blob=await recordCanvas({
        width,height,quality:options.quality||project.settings.quality,duration,fps:options.fps,audioTracks:dest.stream.getAudioTracks(),onProgress:progress,
        draw:(ctx,w,h,t,elapsed)=>{
          const colors=paletteFor(options.prompt||asset.name,options.style);
          const g=ctx.createLinearGradient(0,0,0,h);g.addColorStop(0,colors[0]);g.addColorStop(1,colors[1]);ctx.fillStyle=g;ctx.fillRect(0,0,w,h);
          const bars=56, center=Math.floor(elapsed*rate),windowSize=Math.floor(rate*.06);
          for(let i=0;i<bars;i++){
            const start=center+Math.floor((i/bars-.5)*windowSize);
            let sum=0,count=0;
            for(let j=0;j<80;j++){const idx=Math.max(0,Math.min(data.length-1,start+j*Math.max(1,Math.floor(windowSize/(bars*80)))));sum+=Math.abs(data[idx]||0);count++;}
            const amp=Math.min(1,(sum/Math.max(1,count))*3.4);
            const bw=w/bars*.72,bh=Math.max(3,amp*h*.46),x=(i+.5)*w/bars-bw/2;
            ctx.fillStyle=i%2?colors[2]:colors[3];ctx.globalAlpha=.72;ctx.fillRect(x,h*.72-bh,bw,bh);
          }
          ctx.globalAlpha=1;ctx.textAlign="center";ctx.fillStyle="rgba(255,255,255,.9)";ctx.font="700 "+Math.round(w*.042)+"px system-ui,sans-serif";ctx.fillText(String(options.prompt||asset.name).slice(0,52),w/2,h*.84);
        }
      });
      options.duration=duration;
      return {blob,engine:"studio-web-audio-visualizer-v1"};
    } finally {try{source.stop();}catch{};await audioCtx.close().catch(()=>{});}
  }

  async function generate(options) {
    if(runtimeState.busy) throw new Error("Another Studio Web generation is already running");
    runtimeState.busy=true;
    const project=await getProject();
    const mode=String(options.mode||byId("vsGenMode")&&byId("vsGenMode").value||"prompt_scene");
    const prompt=String(options.prompt!=null?options.prompt:(byId("vsGenPrompt")&&byId("vsGenPrompt").value)||"").trim();
    const style=String(options.style||byId("vsGenStyle")&&byId("vsGenStyle").value||"cinematic");
    const duration=clamp(options.duration||byId("vsGenDuration")&&byId("vsGenDuration").value||8,1,60);
    const fps=clamp(options.fps||30,12,60);
    const quality=options.quality||project.settings.quality||"720p";
    const aspect=options.aspect||project.settings.aspect||"9:16";
    const seed=Number.isFinite(Number(options.seed))?Number(options.seed):hashSeed(prompt+"|"+mode);
    const progress=(pct)=>{const bar=byId("vsGenProgress");if(bar)bar.style.width=pct+"%";const text=byId("vsGenStatus");if(text)text.textContent="Generating "+mode.replaceAll("_"," ")+" • "+pct+"%";};
    try{
      progress(1);
      let output;
      if(mode==="image_motion") output=await generateImageMotion(project,{...options,prompt,style,duration,fps,quality,aspect,seed},progress);
      else if(mode==="story_video") output=await generateStory(project,{...options,prompt,style,duration,fps,quality,aspect,seed},progress);
      else if(mode==="video_restyle") output=await generateVideoRestyle(project,{...options,prompt,style,duration,fps,quality,aspect,seed},progress);
      else if(mode==="audio_visualizer") output=await generateAudioVisualizer(project,{...options,prompt,style,duration,fps,quality,aspect,seed},progress);
      else{
        const [width,height]=dimensions(aspect,quality);
        const draw=mode==="motion_graphics"
          ?(ctx,w,h,t,e)=>drawMotionGraphics(ctx,w,h,t,e,prompt,style,seed)
          :mode==="procedural_3d"
            ?(ctx,w,h,t,e)=>drawProcedural3d(ctx,w,h,t,e,prompt,style,seed)
            :mode==="abstract_vfx"
              ?(ctx,w,h,t,e)=>drawAbstract(ctx,w,h,t,e,prompt,style,seed)
              :(ctx,w,h,t,e)=>drawPromptScene(ctx,w,h,t,e,prompt,style,seed);
        const blob=await recordCanvas({width,height,quality,duration,fps,onProgress:progress,draw});
        output={blob,engine:mode==="procedural_3d"?"studio-web-3d-projection-v1":"studio-web-procedural-v1"};
      }
      const asset=await registerGeneratedVideo(project,output.blob,{mode,prompt,style,duration:options.duration||duration,seed,engine:output.engine,name:"VideoStudio-"+mode});
      progress(100);
      if(byId("vsGenStatus"))byId("vsGenStatus").textContent="Generated "+asset.name+" • "+humanBytes(output.blob.size);
      addLog("Generation complete",asset.name+" • "+output.engine);
      toast("Generated video added to project");
      if(localStorage.getItem("vs-drive-auto-upload")==="1"&&runtimeState.driveToken){
        try{await uploadSingleAsset(project,asset);}catch(error){console.warn("Auto Drive upload",error);}
      }
      return {ok:true,asset,engine:output.engine,mode,prompt,duration:asset.duration,realVideo:true,neural:false,note:"Rendered as a real browser-local video. This engine does not claim neural photoreal synthesis."};
    } finally {runtimeState.busy=false;}
  }

  function loadGoogleIdentity() {
    if(window.google&&google.accounts&&google.accounts.oauth2)return Promise.resolve();
    return new Promise((resolve,reject)=>{
      const existing=document.querySelector('script[data-vs-google-identity="1"]');
      if(existing){existing.addEventListener("load",resolve,{once:true});existing.addEventListener("error",reject,{once:true});return;}
      const script=document.createElement("script");script.src="https://accounts.google.com/gsi/client";script.async=true;script.defer=true;script.dataset.vsGoogleIdentity="1";script.onload=resolve;script.onerror=()=>reject(new Error("Could not load Google Identity Services"));document.head.appendChild(script);
    });
  }

  async function resolveDriveClientId(){
    if(runtimeState.driveClientId)return runtimeState.driveClientId;
    try{
      const config=await api("/api/web/config");
      if(config.googleDriveClientId){runtimeState.driveClientId=config.googleDriveClientId;localStorage.setItem("vs-drive-client-id",runtimeState.driveClientId);}
    }catch{}
    const field=byId("vsDriveClientId");
    if(!runtimeState.driveClientId&&field&&field.value.trim()){runtimeState.driveClientId=field.value.trim();localStorage.setItem("vs-drive-client-id",runtimeState.driveClientId);}
    if(!runtimeState.driveClientId)throw new Error("Google Drive needs an OAuth Web Client ID. Add it once in Drive Storage settings; no paid storage is required.");
    return runtimeState.driveClientId;
  }

  async function connectDrive(interactive=true){
    if(runtimeState.driveToken&&Date.now()<runtimeState.driveTokenExpiresAt-60000)return runtimeState.driveToken;
    const clientId=await resolveDriveClientId();await loadGoogleIdentity();
    const token=await new Promise((resolve,reject)=>{
      const client=google.accounts.oauth2.initTokenClient({
        client_id:clientId,
        scope:"https://www.googleapis.com/auth/drive.file",
        callback:(response)=>response&&response.access_token?resolve(response):reject(new Error(response&&response.error||"Drive authorization failed"))
      });
      client.requestAccessToken({prompt:interactive?"consent":""});
    });
    runtimeState.driveToken=token.access_token;runtimeState.driveTokenExpiresAt=Date.now()+Number(token.expires_in||3300)*1000;
    if(byId("vsDriveStatus"))byId("vsDriveStatus").textContent="Connected • drive.file scope";
    toast("Google Drive connected");
    return runtimeState.driveToken;
  }

  async function driveFetch(url,options={}){
    const token=await connectDrive(Boolean(options.interactive));
    const headers={...(options.headers||{}),Authorization:"Bearer "+token};
    const response=await fetch(url,{...options,headers});
    if(response.status===401){runtimeState.driveToken=null;runtimeState.driveTokenExpiresAt=0;if(!options._retry)return driveFetch(url,{...options,_retry:true,interactive:true});}
    if(!response.ok&&response.status!==308){const data=await response.json().catch(()=>({}));throw new Error(data.error&&data.error.message||"Google Drive request failed");}
    return response;
  }

  function driveQuery(q,fields){
    return driveFetch("https://www.googleapis.com/drive/v3/files?q="+encodeURIComponent(q)+"&spaces=drive&fields="+encodeURIComponent(fields||"files(id,name,mimeType,size,modifiedTime,parents)")+"&pageSize=1000",{method:"GET",interactive:false});
  }

  async function findChild(name,parentId,mimeType){
    const safe=String(name).replaceAll("'","\\'");
    let q="name='"+safe+"' and trashed=false and '"+(parentId||"root")+"' in parents";
    if(mimeType)q+=" and mimeType='"+mimeType+"'";
    const response=await driveQuery(q);
    const data=await response.json();
    return (data.files||[])[0]||null;
  }

  async function ensureFolder(name,parentId){
    const mime="application/vnd.google-apps.folder";
    const existing=await findChild(name,parentId,mime);if(existing)return existing.id;
    const response=await driveFetch("https://www.googleapis.com/drive/v3/files?fields=id,name",{method:"POST",interactive:false,headers:{"content-type":"application/json"},body:JSON.stringify({name,mimeType:mime,parents:[parentId||"root"]})});
    return (await response.json()).id;
  }

  async function uploadBlobResumable(blob,name,mime,parentId,existingId,onProgress){
    const url=existingId
      ?"https://www.googleapis.com/upload/drive/v3/files/"+encodeURIComponent(existingId)+"?uploadType=resumable&fields=id,name,mimeType,size,parents"
      :"https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,mimeType,size,parents";
    const init=await driveFetch(url,{method:existingId?"PATCH":"POST",interactive:false,headers:{"content-type":"application/json","X-Upload-Content-Type":mime||"application/octet-stream"},body:JSON.stringify(existingId?{name}:{name,parents:[parentId]})});
    const session=init.headers.get("location");if(!session)throw new Error("Drive resumable upload session was not created");
    const chunkSize=8*1024*1024;let offset=0,last=null;
    while(offset<blob.size){
      const end=Math.min(blob.size,offset+chunkSize),chunk=blob.slice(offset,end);
      const response=await driveFetch(session,{method:"PUT",interactive:false,headers:{"Content-Range":"bytes "+offset+"-"+(end-1)+"/"+blob.size,"content-type":mime||"application/octet-stream"},body:chunk});
      if(response.status!==308)last=await response.json().catch(()=>null);
      offset=end;if(onProgress)onProgress(Math.round(offset/Math.max(1,blob.size)*100));
    }
    if(last)return last;
    if(existingId)return {id:existingId,name};
    throw new Error("Drive upload did not return file metadata");
  }

  async function ensureProjectFolders(project){
    const rootId=runtimeState.driveRootId||await ensureFolder("VideoStudio Studio Web","root");runtimeState.driveRootId=rootId;
    const projectsId=await ensureFolder("Projects",rootId);
    const projectId=await ensureFolder(project.id,projectsId);
    const assetsId=await ensureFolder("assets",projectId);
    const rendersId=await ensureFolder("renders",projectId);
    return {rootId,projectsId,projectId,assetsId,rendersId};
  }

  async function uploadSingleAsset(project,asset,folders){
    folders=folders||await ensureProjectFolders(project);
    const record=await idbGet("assets",assetKey(project.id,asset.id));
    if(!record||!record.blob){if(asset.driveFileId)return asset;throw new Error("Local asset is missing: "+asset.name);}
    const remote=await uploadBlobResumable(record.blob,asset.id+"__"+asset.name,asset.type||record.blob.type||"application/octet-stream",folders.assetsId,asset.driveFileId);
    asset.driveFileId=remote.id;asset.driveSyncedAt=new Date().toISOString();return asset;
  }

  async function syncProject(options={}){
    const project=await getProject();await connectDrive(options.interactive!==false);
    const folders=await ensureProjectFolders(project);
    const status=byId("vsDriveStatus");if(status)status.textContent="Syncing project to Drive…";
    let uploaded=0,bytes=0;
    for(const asset of project.assets){
      const before=asset.driveFileId;await uploadSingleAsset(project,asset,folders);if(!before)uploaded++;
      const record=await idbGet("assets",assetKey(project.id,asset.id));if(record&&record.blob)bytes+=record.blob.size;
    }
    if(project.latestRender&&project.latestRender.id){
      const render=await idbGet("renders",project.id+":"+project.latestRender.id);
      if(render&&render.blob){
        const remote=await uploadBlobResumable(render.blob,"latest-render__"+project.latestRender.id+(render.blob.type.includes("mp4")?".mp4":".webm"),render.blob.type,folders.rendersId,project.latestRender.driveFileId);
        project.latestRender.driveFileId=remote.id;
      }
    }
    project.drive={...(project.drive||{}),rootId:folders.rootId,projectFolderId:folders.projectId,assetsFolderId:folders.assetsId,rendersFolderId:folders.rendersId,lastSyncedAt:new Date().toISOString(),scope:"drive.file"};
    const manifest={version:1,project:{...project,drive:project.drive},syncedAt:new Date().toISOString()};
    const manifestBlob=new Blob([JSON.stringify(manifest,null,2)],{type:"application/json"});
    const remoteManifest=await uploadBlobResumable(manifestBlob,"project.json","application/json",folders.projectId,project.drive.manifestFileId);
    project.drive.manifestFileId=remoteManifest.id;
    await patchProject(project,{assets:project.assets,latestRender:project.latestRender,drive:project.drive});
    if(status)status.textContent="Drive synced • "+project.assets.length+" assets";
    toast("Project synced to your Google Drive");
    return {ok:true,provider:"google-drive",scope:"drive.file",uploadedAssets:uploaded,localBytesRepresented:bytes,projectFolderId:folders.projectId,manifestFileId:project.drive.manifestFileId};
  }

  async function downloadDriveFile(fileId){
    const response=await driveFetch("https://www.googleapis.com/drive/v3/files/"+encodeURIComponent(fileId)+"?alt=media",{method:"GET",interactive:false});
    return response.blob();
  }

  async function restoreAssetBlob(projectId,assetId){
    const projects=await api("/api/projects?deviceId="+encodeURIComponent(deviceId()));
    const project=(projects.projects||[]).find(p=>p.id===projectId);if(!project)return null;
    const asset=(project.assets||[]).find(a=>a.id===assetId);if(!asset||!asset.driveFileId)return null;
    if(!runtimeState.driveToken||Date.now()>=runtimeState.driveTokenExpiresAt-60000)return null;
    const blob=await downloadDriveFile(asset.driveFileId);
    const record={key:assetKey(projectId,assetId),blob,meta:asset};await idbPut("assets",record);return record;
  }

  async function restoreProject(options={}){
    let project=await getProject();await connectDrive(options.interactive!==false);
    const folders=await ensureProjectFolders(project);
    let manifestId=project.drive&&project.drive.manifestFileId;
    if(!manifestId){const found=await findChild("project.json",folders.projectId,"application/json");manifestId=found&&found.id;}
    if(!manifestId)throw new Error("No Drive project manifest was found");
    const manifest=JSON.parse(await (await downloadDriveFile(manifestId)).text());
    const remoteProject=manifest.project||{};
    const assets=remoteProject.assets||project.assets||[];
    let restored=0,bytes=0;
    for(const asset of assets){
      if(!asset.driveFileId)continue;
      const local=await idbGet("assets",assetKey(project.id,asset.id));if(local&&local.blob)continue;
      const blob=await downloadDriveFile(asset.driveFileId);
      await idbPut("assets",{key:assetKey(project.id,asset.id),blob,meta:asset});restored++;bytes+=blob.size;
    }
    project={...project,...remoteProject,id:project.id,deviceId:project.deviceId};
    await patchProject(project,{name:project.name,instruction:project.instruction,assets, timeline:project.timeline||[],settings:project.settings||{},latestRender:project.latestRender||null,drive:{...(project.drive||{}),manifestFileId}});
    const status=byId("vsDriveStatus");if(status)status.textContent="Restored "+restored+" asset(s) • "+humanBytes(bytes);
    toast("Drive restore complete");
    setTimeout(()=>location.reload(),700);
    return {ok:true,restoredAssets:restored,bytesRestored:bytes,provider:"google-drive"};
  }

  async function offloadProject(options={}){
    const project=await getProject();const synced=await syncProject({interactive:options.interactive!==false});
    let deleted=0,bytes=0;
    for(const asset of project.assets){
      if(!asset.driveFileId)continue;
      const local=await idbGet("assets",assetKey(project.id,asset.id));
      if(local&&local.blob){bytes+=local.blob.size;await idbDelete("assets",assetKey(project.id,asset.id));deleted++;}
    }
    if(project.latestRender&&project.latestRender.id&&project.latestRender.driveFileId){
      const key=project.id+":"+project.latestRender.id;const local=await idbGet("renders",key);if(local&&local.blob){bytes+=local.blob.size;await idbDelete("renders",key);}
    }
    project.drive={...(project.drive||{}),offloadedAt:new Date().toISOString(),offloadedBytes:bytes};
    await patchProject(project,{drive:project.drive});
    toast("Freed "+humanBytes(bytes)+" locally after Drive verification");
    setTimeout(()=>location.reload(),700);
    return {ok:true,synced,localAssetsEvicted:deleted,bytesFreed:bytes,restoreRequired:true};
  }

  async function driveStatus(){
    const projectId=currentProjectId();
    return {ok:true,connected:!!runtimeState.driveToken&&Date.now()<runtimeState.driveTokenExpiresAt-60000,clientConfigured:!!runtimeState.driveClientId,scope:"drive.file",projectId,storageOwner:"user-google-drive",paidWebsiteStorageRequired:false};
  }

  function injectStyles(){
    const style=document.createElement("style");style.textContent=
      ".vsRuntimeGrid{display:grid;grid-template-columns:1.15fr .85fr;gap:12px;margin-top:12px}.vsRuntimeCard{border:1px solid #25304a;background:linear-gradient(145deg,rgba(20,28,48,.92),rgba(8,12,20,.98));border-radius:16px;padding:13px}.vsRuntimeCard h4{margin:0 0 4px;font-size:14px}.vsRuntimeCard .vsMuted{font-size:10px;color:#8d98ad}.vsField{display:grid;gap:5px;margin-top:10px}.vsField label{font-size:9px;color:#8d98ad;font-weight:800;letter-spacing:.07em}.vsField input,.vsField select,.vsField textarea{width:100%;box-sizing:border-box;border:1px solid #2b354d;background:#080c14;color:#eef4ff;border-radius:10px;padding:9px;font:inherit}.vsField textarea{min-height:76px;resize:vertical}.vsRow{display:flex;gap:7px;flex-wrap:wrap;margin-top:10px}.vsRuntimeBtn{border:1px solid #303b55;background:#111827;color:#eef4ff;border-radius:10px;padding:8px 10px;font-weight:750;font-size:10px}.vsRuntimeBtn.primary{background:linear-gradient(135deg,#7655f4,#2bc6e5);border-color:transparent}.vsRuntimeBtn.good{border-color:rgba(74,222,128,.35);color:#a9f6cd}.vsRuntimeBtn.warn{border-color:rgba(251,191,36,.35);color:#ffe09a}.vsProgress{height:6px;background:#1c2434;border-radius:999px;overflow:hidden;margin-top:10px}.vsProgress i{display:block;width:0;height:100%;background:linear-gradient(90deg,#805cf5,#32d9df)}.vsStatus{font-size:10px;color:#aab5ca;margin-top:7px}.vsBadge{display:inline-block;padding:3px 7px;border-radius:999px;border:1px solid rgba(80,220,160,.24);color:#99f6c8;font-size:9px;background:rgba(80,220,160,.04)}@media(max-width:820px){.vsRuntimeGrid{grid-template-columns:1fr}}";
    document.head.appendChild(style);
  }

  function injectUi(){
    if(byId("vsRuntimeRoot"))return;
    const host=byId("aiSection");if(!host)return;
    injectStyles();
    const section=document.createElement("div");section.id="vsRuntimeRoot";section.className="sectionBody";section.style.borderTop="1px solid #20283a";
    section.innerHTML=
      '<div class="vsRuntimeGrid">'+
      '<div class="vsRuntimeCard"><h4>Generation Studio <span class="vsBadge">REAL LOCAL RENDER</span></h4><div class="vsMuted">Creates actual MP4/WebM video in your browser. No fake progress cards, no paid generation API.</div>'+
      '<div class="vsField"><label>GENERATION TYPE</label><select id="vsGenMode"><option value="prompt_scene">Text → Video · procedural cinematic</option><option value="image_motion">Image → Video · depth motion</option><option value="story_video">Images → Story Video</option><option value="video_restyle">Video → Video · restyle</option><option value="motion_graphics">2D Motion Graphics</option><option value="procedural_3d">3D Procedural Scene</option><option value="audio_visualizer">Audio → Visualizer Video</option><option value="abstract_vfx">Abstract / VFX Generator</option></select></div>'+
      '<div class="vsField"><label>PROMPT / DIRECTION</label><textarea id="vsGenPrompt" placeholder="A neon monsoon city at night, slow cinematic movement, atmospheric reflections"></textarea></div>'+
      '<div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:7px"><div class="vsField"><label>DURATION</label><input id="vsGenDuration" type="number" min="1" max="60" value="8"></div><div class="vsField"><label>STYLE</label><select id="vsGenStyle"><option value="cinematic">Cinematic</option><option value="dreamy">Dreamy</option><option value="neon">Neon</option><option value="film">Film</option><option value="mono">Monochrome</option></select></div><div class="vsField"><label>FPS</label><select id="vsGenFps"><option>30</option><option>24</option><option>60</option></select></div></div>'+
      '<div class="vsRow"><button id="vsGenerateBtn" class="vsRuntimeBtn primary">Generate real video</button><button id="vsGenUseSelected" class="vsRuntimeBtn">Use selected media</button></div><div class="vsProgress"><i id="vsGenProgress"></i></div><div id="vsGenStatus" class="vsStatus">Ready. Neural photoreal synthesis is not falsely claimed; these are executable browser renderers.</div></div>'+
      '<div class="vsRuntimeCard"><h4>Google Drive Storage <span class="vsBadge">USER OWNED</span></h4><div class="vsMuted">Uses Google Drive <b>drive.file</b> permission. VideoStudio can access only files/folders it creates or the user opens with it, not the whole Drive.</div>'+
      '<div class="vsField"><label>GOOGLE OAUTH WEB CLIENT ID</label><input id="vsDriveClientId" placeholder="Configured by server, or paste once here"></div>'+
      '<div class="vsRow"><button id="vsDriveConnect" class="vsRuntimeBtn primary">Connect Drive</button><button id="vsDriveSync" class="vsRuntimeBtn good">Sync project</button><button id="vsDriveRestore" class="vsRuntimeBtn">Restore project</button><button id="vsDriveOffload" class="vsRuntimeBtn warn">Sync + free local</button></div>'+
      '<div id="vsDriveStatus" class="vsStatus">Not connected. Google Drive API itself has no storage charge from VideoStudio; your own Drive quota is used.</div><div class="vsStatus">If the site owner configures the OAuth Client ID on the worker, users only press Connect. Otherwise paste a free Google OAuth Web Client ID once.</div></div>'+
      '</div>';
    host.appendChild(section);
    const client=byId("vsDriveClientId");if(client)client.value=runtimeState.driveClientId;
    byId("vsDriveConnect").onclick=async()=>{try{runtimeState.driveClientId=client.value.trim()||runtimeState.driveClientId;if(runtimeState.driveClientId)localStorage.setItem("vs-drive-client-id",runtimeState.driveClientId);await connectDrive(true);}catch(error){toast(error.message);if(byId("vsDriveStatus"))byId("vsDriveStatus").textContent=error.message;}};
    byId("vsDriveSync").onclick=async()=>{try{await syncProject({interactive:true});}catch(error){toast(error.message);}};
    byId("vsDriveRestore").onclick=async()=>{try{await restoreProject({interactive:true});}catch(error){toast(error.message);}};
    byId("vsDriveOffload").onclick=async()=>{try{if(confirm("Sync all project media to your Drive, verify the upload, then remove local browser copies?"))await offloadProject({interactive:true});}catch(error){toast(error.message);}};
    byId("vsGenerateBtn").onclick=async()=>{try{const project=await getProject();const selected=localStorage.getItem("vs-runtime-selected-asset")||"";await generate({mode:byId("vsGenMode").value,prompt:byId("vsGenPrompt").value,duration:Number(byId("vsGenDuration").value||8),style:byId("vsGenStyle").value,fps:Number(byId("vsGenFps").value||30),quality:project.settings.quality,aspect:project.settings.aspect,assetId:selected});setTimeout(()=>location.reload(),850);}catch(error){toast(error.message);if(byId("vsGenStatus"))byId("vsGenStatus").textContent=error.message;}};
    byId("vsGenUseSelected").onclick=()=>{const cards=[...document.querySelectorAll(".mediaCard.active,.clip.active")];if(!cards.length){toast("Select media in the Media Bin or timeline first");return;}const projectId=currentProjectId();getProject().then(project=>{const activeIndex=[...document.querySelectorAll(".mediaCard")].findIndex(x=>x.classList.contains("active"));const asset=activeIndex>=0?project.assets[activeIndex]:null;if(asset){localStorage.setItem("vs-runtime-selected-asset",asset.id);toast("Selected "+asset.name+" for generation");}});};
    resolveDriveClientId().then(id=>{if(client&&!client.value)client.value=id;}).catch(()=>{});
  }

  async function completeRuntimeCommand(command,result,status){
    await api("/api/runtime/commands/"+encodeURIComponent(command.id)+"/complete",{method:"POST",body:JSON.stringify({deviceId:deviceId(),result,status:status||"completed"})});
  }

  async function handleRuntimeCommand(command){
    const p=command.parameters||{};let result;
    try{
      if(command.action==="generate_video")result=await generate({...p,remote:true});
      else if(command.action==="render_portal_video"){
        if(!window.VideoStudioCinematic||typeof window.VideoStudioCinematic.renderPortal!=="function") throw new Error("Cinematic Worlds runtime is not ready");
        result=await window.VideoStudioCinematic.renderPortal({...p,remote:true});
      }
      else if(command.action==="drive_status")result=await driveStatus();
      else if(command.action==="drive_sync")result=await syncProject({interactive:false});
      else if(command.action==="drive_restore")result=await restoreProject({interactive:false});
      else if(command.action==="drive_offload")result=await offloadProject({interactive:false});
      else throw new Error("Unsupported Studio Runtime action: "+command.action);
      await completeRuntimeCommand(command,result,"completed");addLog("Studio Runtime: "+command.action,"Completed");
    }catch(error){
      result={ok:false,error:error.message};await completeRuntimeCommand(command,result,"failed").catch(()=>{});addLog("Studio Runtime: "+command.action,error.message);
    }
  }

  async function runtimeCommandLoop(){
    while(true){
      try{
        const data=await api("/api/runtime/commands?deviceId="+encodeURIComponent(deviceId())+"&after="+runtimeState.lastRuntimeSeq);
        for(const command of data.commands||[]){
          runtimeState.lastRuntimeSeq=Math.max(runtimeState.lastRuntimeSeq,Number(command.seq||0));
          localStorage.setItem("vs-runtime-last-seq",String(runtimeState.lastRuntimeSeq));
          if(command.status==="queued")await handleRuntimeCommand(command);
        }
      }catch(error){console.warn("Studio Runtime command loop",error);}
      await sleep(document.hidden?3500:900);
    }
  }

  window.VideoStudioGeneration={generate};
  window.VideoStudioCloud={status:driveStatus,connect:connectDrive,syncProject,restoreProject,offloadProject,restoreAssetBlob,uploadSingleAsset};

  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",()=>{injectUi();runtimeCommandLoop();});
  else{injectUi();runtimeCommandLoop();}
})();
`;

export default STUDIO_RUNTIME_JS;
