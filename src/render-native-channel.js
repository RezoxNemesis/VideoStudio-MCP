/* VideoStudio-MCP v3 shared transport: isolated Render Android device lane.
 * No cloud Blender computation, no Gallery access, no second relay.
 * Existing VideoStudio keys/queues/projects are neither replaced nor modified.
 */
import {persistNativeCommandQueue,hydrateNativeCommand} from "./native-payload-storage.js";
import {assertRenderAction,assertRenderRequestId} from "./render-v3-actions.js";
const now = () => new Date().toISOString();
const sha256 = async value => {
  const bytes=new TextEncoder().encode(value);
  const data=await crypto.subtle.digest("SHA-256",bytes);
  return [...new Uint8Array(data)].map(b=>b.toString(16).padStart(2,"0")).join("");
};
const pending=c=>["queued","waiting_native","claimed","cancel_requested"].includes(c.status);
const states=new Set(["succeeded","failed","cancelled","recovery_required"]);
async function resolve(st,videoKey){
  const video=await st.appV3Device(videoKey);
  const link=await st.ctx.storage.get("render-link:"+video.deviceId);
  if(!link)return null;
  const d=await st.ctx.storage.get("app-device:"+link.deviceId);
  if(!d||d.ownerHash!==link.ownerHash||d.protocolFamily!=="render-native"||d.renderRevoked)return null;
  return {device:d,link};
}
export async function link(st,videoKey,renderId,renderKey,meta={}){
  const video=await st.appV3Device(videoKey);
  if(video.protocolFamily!=="videostudio-native" && video.nativeAgent!=="videostudio-v3")
    throw Error("VIDEOSTUDIO_OWNER_REQUIRED");
  if(typeof renderId!=="string"||!/^[a-f0-9]{8}-[a-f0-9-]{27}$/.test(renderId) || renderId===video.deviceId)
    throw Error("RENDER_DEVICE_ID_INVALID");
  if(typeof renderKey!=="string"||!/^[A-Za-z0-9_-]{43,128}$/.test(renderKey)||renderKey===videoKey)
    throw Error("RENDER_DEVICE_KEY_INVALID");
  const hash=await sha256(renderKey),key="render-link:"+video.deviceId;
  const old=await st.ctx.storage.get(key);
  if(old&&(old.deviceId!==renderId||old.ownerHash!==hash))
    throw Error("RENDER_ALREADY_LINKED");
  const result=await st.appRegister(renderId,renderKey,{
    ...meta,name:"Render Android",platform:"android-native",
    appVersion:String(meta.appVersion||"0.12").slice(0,24),
    nativeAgent:"render-native-v1",protocolFamily:"render-native",
    protocolVersion:3,protocolMin:3,protocolMax:3,appGeneration:1,
    permissionMode:"everything",controlPaused:true,projects:[],galleryAccess:false,
    localEngineOwnsProjects:true,directAttachmentIngest:false,connectionCoreVersion:3
  });
  if(result.__staleClient)throw Error("RENDER_STALE_CLIENT");
  await st.ctx.storage.put(key,{deviceId:renderId,ownerHash:hash,parent:video.deviceId,linkedAt:old?.linkedAt||now()});
  await st.ctx.storage.put("render-parent:"+renderId,video.deviceId);
  return {linked:true,deviceId:renderId,transport:"existing-videostudio-mcp-v3",galleryAccess:false,
    editorRuns:"render-android-native",separateMcpConnectionRequired:false};
}
export async function status(st,videoKey){
  const rec=await resolve(st,videoKey);
  if(!rec)return {linked:false,deviceOnline:false,executionAvailable:false,
    reason:"Pair Render Android using the existing VideoStudio MCP URL."};
  const d=rec.device,last=Date.parse(d.lastSeenAt||"")||0;
  const online=last>0&&Date.now()-last<45000;
  const q=(await st.ctx.storage.get("app-v3-cl:"+d.deviceId))||[];
  return {linked:true,deviceId:d.deviceId,deviceOnline:online,
    executionAvailable:online&&!d.controlPaused,controlPaused:!!d.controlPaused,
    appVersion:d.appVersion,lastSeenAt:d.lastSeenAt||null,
    pendingCommands:q.filter(pending).length,transport:"videostudio-mcp-v3",
    galleryAccess:false,executor:"render-android-only"};
}
export async function enqueue(st,videoKey,action,args={},requestId=null){
  const rec=await resolve(st,videoKey);
  if(!rec)throw Error("RENDER_NOT_LINKED");
  if(rec.device.controlPaused)throw Error("RENDER_EXECUTOR_STOPPED");
  assertRenderAction(action,args);
  const rid=assertRenderRequestId(requestId||crypto.randomUUID().replaceAll("-",""));
  const payload=JSON.stringify({device_id:rec.device.deviceId,request_id:rid,api_version:1,action,args});
  const digest=await sha256(payload);
  const storage=st.ctx.storage,id=rec.device.deviceId,k="app-v3-cl:"+id;
  const list=(await storage.get(k))||[];
  const previous=list.find(c=>c.renderRequestId===rid);
  if(previous){
    if(previous.parameters?.digest!==digest)throw Error("RENDER_REQUEST_ID_CONFLICT");
    return {...previous,duplicate:true};
  }
  if(list.filter(pending).length>=140)throw Error("RENDER_QUEUE_FULL");
  const last=Date.parse(rec.device.lastSeenAt||"")||0;
  const fresh=last>0&&Date.now()-last<45000;
  const sk="app-v3-seq:"+id,seq=((await storage.get(sk))||0)+1;
  await storage.put(sk,seq);
  const cmd={id:crypto.randomUUID().replaceAll("-",""),seq,
    protocolVersion:3,deviceId:id,action:"render.execute",
    renderAction:action,renderRequestId:rid,
    parameters:{payload,digest},status:fresh?"queued":"waiting_native",
    waitingReason:fresh?"":"render_offline",createdAt:now(),result:null,completedAt:null};
  const open=list.filter(pending);
  const closed=list.filter(c=>!pending(c));
  const retained=[...closed.slice(-Math.max(0,160-open.length-1)),...open,cmd]
    .sort((a,b)=>a.seq-b.seq).slice(-160);
  await persistNativeCommandQueue(storage,id,retained);
  return cmd;
}
export async function command(st,videoKey,commandId){
  const rec=await resolve(st,videoKey);if(!rec)return null;
  const list=(await st.ctx.storage.get("app-v3-cl:"+rec.device.deviceId))||[];
  const record=list.find(c=>c.action==="render.execute"&&c.id===commandId);
  return record?await hydrateNativeCommand(st.ctx.storage,record):null;
}
export async function cancel(st,videoKey,commandId){
  const rec=await resolve(st,videoKey);if(!rec)throw Error("RENDER_NOT_LINKED");
  const k="app-v3-cl:"+rec.device.deviceId,q=(await st.ctx.storage.get(k))||[];
  const c=q.find(x=>x.id===commandId && x.action==="render.execute");
  if(!c)throw Error("RENDER_COMMAND_NOT_FOUND");
  if(["queued","waiting_native"].includes(c.status)){
    c.status="cancelled";c.completedAt=now();c.result={executed:false};
  }else if(c.status==="claimed")c.status="cancel_requested";
  await persistNativeCommandQueue(st.ctx.storage,rec.device.deviceId,q);
  return {commandId,status:c.status};
}
async function child(st,deviceId,ownerKey){
  const d=await st.appAuth(deviceId,ownerKey);
  const p=await st.ctx.storage.get("render-parent:"+deviceId);
  if(!d||!p||d.renderRevoked||d.protocolFamily!=="render-native")throw Error("RENDER_DEVICE_AUTH_REQUIRED");
  const bound=await st.ctx.storage.get("render-link:"+p);
  if(bound?.deviceId!==deviceId || d.ownerHash!==bound.ownerHash)throw Error("RENDER_DEVICE_NOT_LINKED");
  return d;
}
export async function poll(st,deviceId,ownerKey,enabled,activeJobId=null,capabilities=[]){
  if(typeof enabled!=="boolean")throw Error("RENDER_ENABLED_FLAG_REQUIRED");
  const d=await child(st,deviceId,ownerKey),storage=st.ctx.storage;
  const nextDevice={...d,lastSeenAt:now(),controlPaused:!enabled,
    renderCapabilities:Array.isArray(capabilities)?capabilities.slice(0,128):[]};
  await storage.put("app-device:"+deviceId,nextDevice);
  const k="app-v3-cl:"+deviceId,q=(await storage.get(k))||[];
  if(!enabled){
    for(const c of q){
      if(["queued","waiting_native"].includes(c.status)){
        c.status="cancelled";c.completedAt=now();c.result={executed:false};
      }else if(c.status==="claimed")c.status="cancel_requested";
    }
    await persistNativeCommandQueue(storage,deviceId,q);
  }
  const cancelled=q.filter(c=>c.status==="cancel_requested").map(c=>c.id);
  let job=null;
  if(enabled && !activeJobId){
    const c=q.find(c=>c.action==="render.execute" &&
      (["queued","waiting_native"].includes(c.status) ||
       (c.status==="claimed"&&Number(c.leaseUntil||0)<=Date.now())));
    if(c){
      c.status="claimed";c.claimCount=Number(c.claimCount||0)+1;
      c.leaseUntil=Date.now()+60000;c.waitingReason="";
      await persistNativeCommandQueue(storage,deviceId,q);
      job={job_id:c.id,digest:c.parameters.digest,payload:c.parameters.payload};
    }
  }
  return {job,cancel_job_ids:cancelled,device_id:deviceId,protocolVersion:3};
}
export async function complete(st,deviceId,ownerKey,jobId,digest,state,result){
  await child(st,deviceId,ownerKey);
  if(!states.has(state) || !result || typeof result!=="object" || Array.isArray(result)
     || JSON.stringify(result).length>120000)throw Error("RENDER_INVALID_RESULT");
  const k="app-v3-cl:"+deviceId,q=(await st.ctx.storage.get(k))||[];
  const c=q.find(x=>x.id===jobId&&x.action==="render.execute");
  if(!c)throw Error("RENDER_COMMAND_NOT_FOUND");
  if(c.parameters.digest!==digest)throw Error("RENDER_COMMAND_DIGEST_MISMATCH");
  const terminal=state==="succeeded"?"completed":state;
  const payload={ok:state==="succeeded",native_result:result,
    nativeExecutor:"Render Android",state};
  if(["completed","failed","cancelled","recovery_required"].includes(c.status)){
    if(c.status!==terminal || JSON.stringify(c.result)!==JSON.stringify(payload))
      throw Error("RENDER_TERMINAL_CONFLICT");
    return {accepted:true,duplicate:true};
  }
  if(!["claimed","cancel_requested"].includes(c.status))throw Error("RENDER_NOT_CLAIMED");
  c.status=terminal;c.completedAt=now();c.leaseUntil=0;c.result=payload;
  await persistNativeCommandQueue(st.ctx.storage,deviceId,q);
  return {accepted:true,duplicate:false};
}
export async function unlink(st,deviceId,ownerKey){
  const d=await child(st,deviceId,ownerKey),storage=st.ctx.storage;
  const parent=await storage.get("render-parent:"+deviceId);
  await storage.delete("render-link:"+parent);
  await storage.delete("render-parent:"+deviceId);
  const k="app-v3-cl:"+deviceId,q=(await storage.get(k))||[];
  for(const c of q)if(pending(c)){
    c.status=c.status==="claimed"?"recovery_required":"cancelled";
    c.result={error:"render_unlinked"};c.completedAt=now();
  }
  await persistNativeCommandQueue(storage,deviceId,q);
  await storage.put("app-device:"+deviceId,{...d,renderRevoked:true,controlPaused:true,lastSeenAt:now()});
  return {unlinked:true,deviceId};
}
