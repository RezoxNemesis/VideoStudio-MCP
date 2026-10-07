const STUDIO_NEURAL_JS = String.raw`
/*
 * VideoStudio Neural Keyframe Lab
 *
 * The WebGPU SD-Turbo execution flow is independently adapted from Microsoft's
 * ONNX Runtime inference example (MIT licensed):
 * https://github.com/microsoft/onnxruntime-inference-examples/tree/main/js/sd-turbo
 *
 * Model weights are not bundled with VideoStudio. The default model source is
 * schmuell/sd-turbo-ort-web, derived from Stability AI SD-Turbo. Users are
 * responsible for the model's license/acceptable-use terms.
 */
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const sleep = (ms) => new Promise(r=>setTimeout(r,ms));
  const MODEL_BASE_DEFAULT = "https://huggingface.co/schmuell/sd-turbo-ort-web/resolve/main";
  const ORT_VERSION = "1.17.1";
  const ORT_SCRIPT = "https://cdn.jsdelivr.net/npm/onnxruntime-web@"+ORT_VERSION+"/dist/ort.webgpu.min.js";
  const ORT_DIST = "https://cdn.jsdelivr.net/npm/onnxruntime-web@"+ORT_VERSION+"/dist/";
  const TOKENIZER_MODULE = "https://cdn.jsdelivr.net/npm/@xenova/transformers/dist/transformers.js";
  const modelSpec = {
    text_encoder:{path:"text_encoder/model.onnx",estimatedMb:1700,overrides:{batch_size:1}},
    unet:{path:"unet/model.onnx",estimatedMb:640,overrides:{batch_size:1,num_channels:4,height:64,width:64,sequence_length:77}},
    vae_decoder:{path:"vae_decoder/model.onnx",estimatedMb:95,overrides:{batch_size:1,num_channels_latent:4,height_latent:64,width_latent:64}}
  };

  const state={
    ort:null,
    tokenizer:null,
    busy:false,
    modelBase:localStorage.getItem("vs-neural-model-base")||MODEL_BASE_DEFAULT,
    cacheName:"videostudio-neural-models-v1",
    lastProbe:null
  };

  const deviceId=()=>localStorage.getItem("vs-device-id")||"";
  const projectId=()=>localStorage.getItem("vs-project-id")||"";

  async function api(path,options={}){
    const r=await fetch(path,{...options,headers:{"content-type":"application/json",...(options.headers||{})}});
    const data=await r.json().catch(()=>({}));
    if(!r.ok)throw new Error(data.error||"Request failed");
    return data;
  }

  function toast(message){
    const n=$("toast");if(n){n.textContent=message;n.classList.add("show");clearTimeout(toast.t);toast.t=setTimeout(()=>n.classList.remove("show"),2800);}else console.log("[VideoStudio Neural]",message);
  }

  function log(title,detail){
    const host=$("commandLog");if(!host)return;
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
    p.assets=p.assets||[];p.timeline=p.timeline||[];p.generation=p.generation||{};
    return p;
  }

  async function patchProject(project,patch){
    return (await api("/api/projects/"+encodeURIComponent(project.id),{method:"POST",body:JSON.stringify({deviceId:deviceId(),patch})})).project;
  }

  function setStatus(text,percent){
    const n=$("vsNeuralStatus"),bar=$("vsNeuralProgress");
    if(n)n.textContent=text;
    if(bar&&percent!=null)bar.style.width=Math.max(0,Math.min(100,percent))+"%";
  }

  async function loadOrt(){
    if(state.ort)return state.ort;
    if(!window.ort){
      await new Promise((resolve,reject)=>{
        const old=document.querySelector('script[data-vs-ort="1"]');
        if(old){old.addEventListener("load",resolve,{once:true});old.addEventListener("error",reject,{once:true});return;}
        const s=document.createElement("script");s.src=ORT_SCRIPT;s.async=true;s.dataset.vsOrt="1";s.onload=resolve;s.onerror=()=>reject(new Error("Could not load ONNX Runtime Web"));document.head.appendChild(s);
      });
    }
    if(!window.ort)throw new Error("ONNX Runtime Web did not initialize");
    state.ort=window.ort;
    state.ort.env.wasm.wasmPaths=ORT_DIST;
    state.ort.env.wasm.numThreads=1;
    state.ort.env.wasm.simd=true;
    return state.ort;
  }

  async function loadTokenizer(){
    if(state.tokenizer)return state.tokenizer;
    const mod=await import(TOKENIZER_MODULE);
    state.tokenizer=await mod.AutoTokenizer.from_pretrained("Xenova/clip-vit-base-patch16");
    state.tokenizer.pad_token_id=0;
    return state.tokenizer;
  }

  async function probe(){
    const result={
      webgpu:!!navigator.gpu,
      deviceMemoryGb:Number(navigator.deviceMemory||0),
      crossOriginIsolated:!!crossOriginIsolated,
      shaderF16:false,
      recommended:false,
      reason:""
    };
    if(!navigator.gpu){result.reason="WebGPU is unavailable in this browser";state.lastProbe=result;return result;}
    try{
      const adapter=await navigator.gpu.requestAdapter({powerPreference:"high-performance"});
      if(!adapter){result.reason="No WebGPU adapter was returned";state.lastProbe=result;return result;}
      result.shaderF16=adapter.features.has("shader-f16");
      result.recommended=result.shaderF16&&(result.deviceMemoryGb===0||result.deviceMemoryGb>=8);
      if(!result.shaderF16)result.reason="GPU lacks shader-f16 required by this SD-Turbo WebGPU pack";
      else if(result.deviceMemoryGb&&result.deviceMemoryGb<8)result.reason="WebGPU works, but reported device memory is below the recommended 8 GB";
      else result.reason="WebGPU + shader-f16 available";
    }catch(e){result.reason=e.message;}
    state.lastProbe=result;return result;
  }

  async function cachedModel(path,onProgress){
    const url=state.modelBase.replace(/\/$/,"")+"/"+path;
    const cache=await caches.open(state.cacheName);
    let response=await cache.match(url);
    if(response){
      onProgress&&onProgress("cache",100);
      return response.arrayBuffer();
    }
    const net=await fetch(url);
    if(!net.ok)throw new Error("Model download failed: "+path+" ("+net.status+")");
    const total=Number(net.headers.get("content-length")||0);
    if(!net.body||!total){
      const clone=net.clone();await cache.put(url,clone);return net.arrayBuffer();
    }
    const reader=net.body.getReader(),parts=[];let loaded=0;
    while(true){
      const {done,value}=await reader.read();if(done)break;parts.push(value);loaded+=value.byteLength;
      onProgress&&onProgress("network",Math.round(loaded/total*100));
    }
    const blob=new Blob(parts,{type:"application/octet-stream"});
    try{await cache.put(url,new Response(blob,{headers:{"content-type":"application/octet-stream","content-length":String(blob.size)}}));}catch(e){console.warn("Model cache put",e);}
    return blob.arrayBuffer();
  }

  async function clearModelCache(){
    await caches.delete(state.cacheName);toast("Neural model cache cleared");return{ok:true};
  }

  function gaussian(size,sigma){
    const out=new Float32Array(size);
    for(let i=0;i<size;i++){
      let u=Math.random(),v=Math.random();if(u<1e-7)u=1e-7;
      out[i]=Math.sqrt(-2*Math.log(u))*Math.cos(2*Math.PI*v)*sigma;
    }
    return out;
  }

  function scaleInputs(ort,tensor,sigma){
    const data=tensor.data,out=new Float32Array(data.length),div=Math.sqrt(sigma*sigma+1);
    for(let i=0;i<data.length;i++)out[i]=data[i]/div;
    return new ort.Tensor("float32",out,tensor.dims);
  }

  function schedulerStep(ort,modelOutput,sample){
    const sigma=14.6146,vaeScale=.18215,out=new Float32Array(modelOutput.data.length);
    for(let i=0;i<modelOutput.data.length;i++){
      const pred=sample.data[i]-sigma*modelOutput.data[i];
      const deriv=(sample.data[i]-pred)/sigma;
      out[i]=(sample.data[i]+deriv*(0-sigma))/vaeScale;
    }
    return new ort.Tensor("float32",out,modelOutput.dims);
  }

  function tensorToCanvas(tensor){
    const dims=tensor.dims,w=Number(dims[dims.length-1]||512),h=Number(dims[dims.length-2]||512),data=tensor.data;
    const canvas=document.createElement("canvas");canvas.width=w;canvas.height=h;
    const ctx=canvas.getContext("2d"),img=ctx.createImageData(w,h),plane=w*h;
    for(let i=0;i<plane;i++){
      const r=Math.round(Math.max(0,Math.min(1,data[i]/2+.5))*255);
      const g=Math.round(Math.max(0,Math.min(1,data[plane+i]/2+.5))*255);
      const b=Math.round(Math.max(0,Math.min(1,data[plane*2+i]/2+.5))*255);
      const j=i*4;img.data[j]=r;img.data[j+1]=g;img.data[j+2]=b;img.data[j+3]=255;
    }
    ctx.putImageData(img,0,0);return canvas;
  }

  async function sessionFor(key,percentBase,percentSpan){
    const ort=await loadOrt(),spec=modelSpec[key];
    setStatus("Loading "+key+"…",percentBase);
    const bytes=await cachedModel(spec.path,(source,pct)=>setStatus((source==="cache"?"Loading cached ":"Downloading ")+key+" • "+pct+"%",percentBase+percentSpan*.45*pct/100));
    const options={
      executionProviders:["webgpu"],
      enableMemPattern:false,
      enableCpuMemArena:false,
      freeDimensionOverrides:spec.overrides,
      extra:{session:{disable_prepacking:"1",use_device_allocator_for_initializers:"1",use_ort_model_bytes_directly:"1",use_ort_model_bytes_for_initializers:"1"}}
    };
    setStatus("Creating "+key+" WebGPU session…",percentBase+percentSpan*.5);
    const session=await ort.InferenceSession.create(bytes,options);
    return session;
  }

  async function generateOne(prompt,index,total){
    const ort=await loadOrt(),tokenizer=await loadTokenizer();
    const p0=(index/total)*100,p1=((index+1)/total)*100,span=p1-p0;
    const tokenized=await tokenizer(prompt,{padding:true,max_length:77,truncation:true,return_tensor:false});
    let ids=tokenized.input_ids;if(Array.isArray(ids)&&Array.isArray(ids[0]))ids=ids[0];
    const idData=Int32Array.from(Array.from(ids).slice(0,77));
    let textSession,unetSession,vaeSession,hidden,outSample,sample;
    try{
      textSession=await sessionFor("text_encoder",p0,p1-p0);
      setStatus("Encoding prompt "+(index+1)+"/"+total+"…",p0+span*.22);
      ({last_hidden_state:hidden}=await textSession.run({input_ids:new ort.Tensor("int32",idData,[1,idData.length])}));
      if(textSession.release)await textSession.release();textSession=null;

      const shape=[1,4,64,64],latent=new ort.Tensor("float32",gaussian(1*4*64*64,14.6146),shape);
      const input=scaleInputs(ort,latent,14.6146);
      unetSession=await sessionFor("unet",p0+span*.28,span*.42);
      setStatus("Diffusing keyframe "+(index+1)+"/"+total+"…",p0+span*.58);
      ({out_sample:outSample}=await unetSession.run({
        sample:input,
        timestep:new ort.Tensor("int64",[999n],[1]),
        encoder_hidden_states:hidden
      }));
      if(unetSession.release)await unetSession.release();unetSession=null;
      const latents=schedulerStep(ort,outSample,latent);

      vaeSession=await sessionFor("vae_decoder",p0+span*.72,span*.24);
      setStatus("Decoding keyframe "+(index+1)+"/"+total+"…",p0+span*.88);
      ({sample}=await vaeSession.run({latent_sample:latents}));
      const canvas=tensorToCanvas(sample);
      const blob=await new Promise((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(new Error("PNG encoding failed")),"image/png"));
      return{blob,canvas};
    } finally {
      for(const session of [textSession,unetSession,vaeSession]){try{if(session&&session.release)await session.release();}catch{}}
      for(const tensor of [hidden,outSample,sample]){try{tensor&&tensor.dispose&&tensor.dispose();}catch{}}
    }
  }

  async function saveImageAsset(project,blob,prompt,index){
    const id=crypto.randomUUID(),name="Neural-World-"+String(index+1).padStart(2,"0")+".png";
    const asset={id,name,type:"image/png",size:blob.size,duration:4,kind:"image",generated:true,role:"neural_world_keyframe",importedAt:new Date().toISOString(),generation:{engine:"sd-turbo-webgpu-onnx",prompt,modelBase:state.modelBase,resolution:"512x512",neural:true}};
    await idbPut("assets",{key:assetKey(project.id,id),blob,meta:asset});
    try{const url=URL.createObjectURL(blob),img=new Image();await new Promise((r,j)=>{img.onload=r;img.onerror=j;img.src=url;});const c=document.createElement("canvas");c.width=320;c.height=180;const x=c.getContext("2d");const fit=Math.max(320/img.naturalWidth,180/img.naturalHeight);x.drawImage(img,(320-img.naturalWidth*fit)/2,(180-img.naturalHeight*fit)/2,img.naturalWidth*fit,img.naturalHeight*fit);await idbPut("thumbs",{key:assetKey(project.id,id),dataUrl:c.toDataURL("image/jpeg",.78),updatedAt:Date.now()});URL.revokeObjectURL(url);}catch{}
    project.assets.push(asset);return asset;
  }

  async function generateKeyframes(options={}){
    if(state.busy)throw new Error("Neural generation is already running");
    state.busy=true;
    try{
      const hardware=await probe();
      if(!hardware.webgpu||!hardware.shaderF16)throw new Error(hardware.reason||"Compatible WebGPU is required");
      if(options.modelBase){state.modelBase=String(options.modelBase);localStorage.setItem("vs-neural-model-base",state.modelBase);}
      const raw=Array.isArray(options.prompts)?options.prompts.join("\n"):String(options.prompts||options.prompt||"");
      const prompts=raw.split(/\n+/).map(x=>x.trim()).filter(Boolean).slice(0,9);
      if(!prompts.length)throw new Error("Enter at least one world prompt");
      const project=await getProject(),assets=[];
      for(let i=0;i<prompts.length;i++){
        const generated=await generateOne(prompts[i],i,prompts.length);
        const asset=await saveImageAsset(project,generated.blob,prompts[i],i);assets.push(asset);
        if(window.VideoStudioCloud&&localStorage.getItem("vs-drive-auto-upload")==="1"){try{await window.VideoStudioCloud.uploadSingleAsset(project,asset);}catch{}}
      }
      project.generation={...(project.generation||{}),lastNeuralWorldIds:assets.map(a=>a.id),lastNeuralEngine:"sd-turbo-webgpu-onnx",generatedAt:new Date().toISOString()};
      await patchProject(project,{assets:project.assets,generation:project.generation});
      setStatus("Generated "+assets.length+" neural world keyframe"+(assets.length===1?"":"s")+" • ready for Cinematic Worlds",100);
      if(window.VideoStudioCinematic&&window.VideoStudioCinematic.refreshSelectors)window.VideoStudioCinematic.refreshSelectors();
      log("Neural worlds generated",assets.length+" SD-Turbo WebGPU keyframe(s)");
      toast("Neural world keyframes added to Media Bin");
      return{ok:true,neural:true,engine:"sd-turbo-webgpu-onnx",assets,hardware,modelBase:state.modelBase,note:"Real client-side SD-Turbo inference through ONNX Runtime WebGPU."};
    } finally {state.busy=false;}
  }

  function inject(){
    if($("vsNeuralLab"))return;
    const host=$("vsCinematicWorlds")||$("vsRuntimeRoot")||$("aiSection");if(!host)return;
    const style=document.createElement("style");style.textContent=".vsNeural{margin-top:12px;border:1px solid rgba(73,182,255,.28);background:linear-gradient(145deg,rgba(11,34,58,.30),rgba(8,12,20,.98));border-radius:16px;padding:13px}.vsNeural h4{margin:0}.vsNeural textarea,.vsNeural input{width:100%;box-sizing:border-box;border:1px solid #33405b;background:#080c14;color:#eef4ff;border-radius:10px;padding:9px}.vsNeural textarea{min-height:110px}.vsNeural label{display:block;margin-top:9px;font-size:9px;color:#9aa6bc;font-weight:800;letter-spacing:.06em}.vsNeural .row{display:flex;gap:7px;flex-wrap:wrap;margin-top:9px}.vsNeural button{border:1px solid #39465e;background:#111927;color:#eef4ff;border-radius:10px;padding:8px 10px;font-size:10px;font-weight:800}.vsNeural button.primary{background:linear-gradient(135deg,#3578ff,#3bd9c6);border-color:transparent}.vsNeural .progress{height:6px;background:#1b2433;border-radius:999px;overflow:hidden;margin-top:9px}.vsNeural .progress i{display:block;width:0;height:100%;background:linear-gradient(90deg,#3578ff,#3bd9c6)}.vsNeural .muted{font-size:10px;color:#97a3b8;margin-top:5px}";
    document.head.appendChild(style);
    const card=document.createElement("div");card.id="vsNeuralLab";card.className="vsNeural";
    card.innerHTML='<h4>Neural World Keyframes <span class="vsBadge">WEBGPU · REAL MODEL</span></h4><div class="muted">Optional heavyweight path for the fantasy worlds used by Cinematic Worlds. Runs SD-Turbo locally in your browser through ONNX Runtime WebGPU, one model phase at a time to reduce peak memory. No paid inference API. First use downloads about 2.4 GB of model weights and caches them locally. Model license is non-commercial/research-oriented, so check terms for your use.</div>'+
      '<label>WORLD PROMPTS · one per line, up to 9</label><textarea id="vsNeuralPrompts" placeholder="Epic Himalayan divine realm at sunrise, colossal sacred figure, cinematic clouds, photoreal\nStorm-lit warrior goddess riding through a volcanic battlefield, cinematic realism\nWhite-robed divine figure beside immense waterfalls and mist, photoreal"></textarea>'+
      '<label>MODEL BASE URL</label><input id="vsNeuralModelBase" value="'+state.modelBase.replaceAll('"',"&quot;")+'">'+
      '<div class="row"><button id="vsNeuralProbe">Probe GPU</button><button id="vsNeuralGenerate" class="primary">Generate neural world keyframes</button><button id="vsNeuralClear">Clear model cache</button></div>'+
      '<div class="progress"><i id="vsNeuralProgress"></i></div><div id="vsNeuralStatus" class="muted">Not probed yet. If this phone cannot hold the model, VideoStudio keeps the lighter procedural/asset compositor available instead of crashing the page.</div>';
    host.appendChild(card);
    $("vsNeuralProbe").onclick=async()=>{const r=await probe();$("vsNeuralStatus").textContent=(r.recommended?"Recommended":"Experimental")+" • "+r.reason+" • deviceMemory "+(r.deviceMemoryGb||"unknown")+" GB";};
    $("vsNeuralClear").onclick=()=>clearModelCache().catch(e=>toast(e.message));
    $("vsNeuralGenerate").onclick=async()=>{try{state.modelBase=$("vsNeuralModelBase").value.trim()||MODEL_BASE_DEFAULT;localStorage.setItem("vs-neural-model-base",state.modelBase);await generateKeyframes({prompts:$("vsNeuralPrompts").value,modelBase:state.modelBase});}catch(e){setStatus(e.message,null);toast(e.message);}};
  }

  window.VideoStudioNeural={probe,generateKeyframes,clearModelCache,state};
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",inject);else inject();
})();
`;

export default STUDIO_NEURAL_JS;
