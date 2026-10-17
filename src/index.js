import {relayRelease,commandBacklog,commandExecution} from './relay-diagnostics.js';
import { nativeCommandSummary, nativeResultPage } from "./native-result-pages.js";
import {receivePrivateBlob,privateBlobResponse,cleanupPrivateBlobs,deletePrivateBlob} from './private-blob-store.js';
import { readCommandQueue, mutateCommandQueue, requireCommandQueueAdmission, terminalCommandUpdate, commandIsPending, readCommandReceipt, acknowledgePrunedCommand, readCommandQueueInTransaction, writeCommandQueueInTransaction, mergeCommandReceiptsInTransaction, readCommandReceiptsInTransaction, canonicalCommandResultSha256, readNativeDevice, readNativeDeviceInTransaction, writeNativeDevice, writeNativeDeviceInTransaction } from "./native-command-store.js";
import { STORYBOARD_NATIVE_ACTIONS, CINEMATIC_BEATS, requireStoryboardNativeVersion, nativeStoryboardParameters, cinematic3dToolSchemas } from "./storyboard-contract.js";
import { studioToolSchemas, nativeStudioCanonicalAction } from './studio-workbench-contract.js';
import { DurableObject } from "cloudflare:workers";
import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";
import APP_HTML from "./app.html";
import STUDIO_RUNTIME_JS from "./studio-runtime.js";
import STUDIO_CINEMATIC_JS from "./studio-cinematic.js";
import STUDIO_NEURAL_JS from "./studio-neural.js";
import STUDIO_TEMPORAL_JS from "./studio-temporal.js";
import STUDIO_CHARACTER_JS from "./studio-character.js";
import STUDIO_ACTION_TIMELINE_JS from "./studio-action-timeline.js";
import STUDIO_POSE_SEQUENCE_JS from "./studio-pose-sequence.js";
import LOCAL_VIDEO_DOWNLOAD_HTML from "./local-download.js";

const JH = {"content-type":"application/json; charset=UTF-8","cache-control":"no-store"};
const now = () => new Date().toISOString();
const redactCommand = command => {
  if(!command) return command;
  const redact = (input,depth=0,omitPrivate=false) => {
    if(!input || typeof input!=="object") return input;
    if(depth>64) throw new Error("Command view exceeds the structural depth limit");
    if(Array.isArray(input)) return input.map(v=>redact(v,depth+1,omitPrivate));
    const copy=Object.create(null);
    for(const [key,value] of Object.entries(input)){
      if(["base64","sourceurl","downloadurl","signedurl","ownerkey","privateownerkey","authtoken","accesstoken","refreshtoken","authorization","apikey","clientsecret","password","token","bearer"].includes(key.toLowerCase().replace(/[_-]/g,""))){
        if(!omitPrivate)copy[key]="[private command payload redacted]";
      }else copy[key]=value&&typeof value==="object"?redact(value,depth+1,omitPrivate):value;
    }
    return copy;
  };
  // Removing credential fields keeps the full result view within the original
  // result byte budget even when many tiny credential values are present.
  return {...command, parameters:redact(command.parameters),result:redact(command.result,0,true)};
};
const redactedImageResult = command => {
  const result=redactCommand(command).result;
  return result;
};
const nativeImageBlocks = command => {
  const result=command&&command.result;
  if(!result||typeof result!=='object')return [];
  const seen=new Set();
  return ['contactSheet','sourceArtworkImage','rigPreviewImage','sketchPreviewImage'].flatMap(key=>{
    const value=result[key];
    if(!value||typeof value.base64!=='string'||!value.base64||value.base64.length>1500*1024)return [];
    const mime=value.mimeType||'image/jpeg';
    if(!['image/jpeg','image/png','image/webp'].includes(mime))return [];
    const identity=mime+':'+value.base64;
    if(seen.has(identity))return [];
    seen.add(identity);
    return [{type:"image",data:value.base64,mimeType:mime}];
  });
};
const clean = (v,n=5000) => String(v ?? "").trim().slice(0,n);
const reply = (x,s=200) => new Response(JSON.stringify(x),{status:s,headers:JH});
const sha256Hex = async value => {
  const bytes = new TextEncoder().encode(String(value || ""));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map(x=>x.toString(16).padStart(2,"0")).join("");
};
const bearer = request => {
  const h=request.headers.get("authorization")||"";
  return h.toLowerCase().startsWith("bearer ") ? h.slice(7).trim() : "";
};
const studioMcpAuthorized = async (request,env) => {
  const expected=clean(env&&env.VIDEOSTUDIO_STUDIO_MCP_BEARER||"",500);
  // Compatibility lane: existing Web-only plugins keep working until the
  // operator provisions a bearer. Native authority is separately gated by
  // the durable hybrid binding and is never derived from a public device ID.
  if(!expected) return true;
  const supplied=bearer(request);
  if(!supplied) return false;
  const [a,b]=await Promise.all([sha256Hex(supplied),sha256Hex(expected)]);
  let diff=a.length^b.length;
  const n=Math.max(a.length,b.length);
  for(let i=0;i<n;i++) diff|=(a.charCodeAt(i)||0)^(b.charCodeAt(i)||0);
  return diff===0;
};
const appActionAllowed = (mode,action) => {
  const a=nativeStudioCanonicalAction(String(action||"").toLowerCase());

  // Permanent privacy wall. Full autonomy never means Gallery enumeration.
  if(a.includes("gallery")||a.includes("media_library")||a.includes("photo_library")) return false;

  // One-file mode is an explicit user lock, not the normal operating mode.
  if(mode==="one_file"){
    return ["ping","get_state","get_native_result_page","self_test","job_status","activity_note","apply_tool","preview_project","analyse_media","export_project","cancel_job","cancel_all_jobs","stop_all"].includes(a);
  }

  // "all_tools" remains a backward-compatible alias for Full Autonomous.
  // Every ordinary VideoStudio-native action is available without permission
  // friction; safety remains enforced by device privacy, network and workload
  // boundaries instead of capability gating.
  return true;
};

export class VideoStudioState extends DurableObject {
  constructor(ctx,env){ super(ctx,env); }
  async fetch(request){
    const url=new URL(request.url),deviceId=url.searchParams.get('deviceId')||'',ownerKey=bearer(request);
    const device=await this.appAuth(deviceId,ownerKey);
    if(!device)return reply({error:'Native app authorization failed'},401);
    if(url.pathname==='/private-upload'&&request.method==='POST'){
      if(device.permissionMode==='one_file'||device.controlPaused)return reply({error:'Chat-file import is unavailable while device control is locked or paused'},403);
      try{
        const ownerHash=await sha256Hex(ownerKey),projectId=clean(url.searchParams.get('projectId')||'',120);
        const uploaded=await receivePrivateBlob(this.ctx.storage,request.body,{deviceId,ownerHash,
          name:clean(url.searchParams.get('name')||'ChatGPT import',180),
          mime:clean(url.searchParams.get('mime')||request.headers.get('content-type')||'application/octet-stream',120),
          declaredSize:Number(request.headers.get('content-length')||0),expectedSha256:request.headers.get('x-content-sha256')||'',signal:request.signal},async(transaction,manifest)=>{
          const live=await readNativeDeviceInTransaction(transaction,'app-device:'+deviceId);
          if(!live||live.supersededByDeviceId||await transaction.get('app-owner:'+ownerHash)!==deviceId)
            throw new Error('Native identity changed before private upload publication');
          if(live.controlPaused||!appActionAllowed(live.permissionMode,'import_chat_file'))throw new Error('Device control changed before private upload publication');
          const v3=Number(live.protocolMin||live.protocolVersion||0)<=3&&Number(live.protocolMax||live.protocolVersion||0)>=3;
          const key=(v3?'app-v3-cl:':'app-cl:')+deviceId,sequenceKey=(v3?'app-v3-seq:':'app-seq:')+deviceId;
          const rows=await readCommandQueueInTransaction(transaction,key),seq=Math.max(Number(await transaction.get(sequenceKey)||0),...rows.map(row=>row.seq),0)+1;
          const command={id:crypto.randomUUID(),seq,deviceId,...(v3?{protocolVersion:3}:{}),action:'import_chat_file',
            parameters:{handoffId:manifest.id,name:manifest.name,mime:manifest.mime,size:manifest.size,sha256:manifest.sha256,projectId},
            status:'queued',createdAt:now(),completedAt:null,result:null};
          requireCommandQueueAdmission(rows,command);rows.push(command);
          await writeCommandQueueInTransaction(transaction,key,rows);await transaction.put(sequenceKey,seq);
          await transaction.put('app-handoff:'+deviceId+':'+manifest.id,{id:manifest.id,deviceId,blobId:manifest.id,
            name:manifest.name,mime:manifest.mime,size:manifest.size,sha256:manifest.sha256,expiresAt:manifest.expiresAt,createdAt:now()});
          return command;
        });
        const {manifest,result:command}=uploaded;
        return reply({ok:true,queued:true,commandId:command.id,sequence:command.seq,handoffId:manifest.id,expiresAt:manifest.expiresAt,
          name:manifest.name,mime:manifest.mime,size:manifest.size,sha256:manifest.sha256,storage:'durable-object',projectId});
      }catch(error){return reply({error:error.message},error.status||409);}
    }
    const match=url.pathname.match(/^\/private-content\/([^/]+)$/);
    if(match&&request.method==='GET'){
      const response=await privateBlobResponse(this.ctx.storage,match[1],deviceId);
      return response||reply({error:'Handoff missing or expired'},404);
    }
    return reply({error:'Private storage route not found'},404);
  }
  async register(deviceId,meta={}){
    const k="d:"+deviceId, old=(await this.ctx.storage.get(k))||{};
    const d={
      deviceId,
      name:clean(meta.name||old.name||"My device",80),
      platform:clean(meta.platform||old.platform||"web",80),
      appVersion:clean(meta.appVersion||old.appVersion||"1.0.0",30),
      capabilities:Array.isArray(meta.capabilities)?meta.capabilities.slice(0,40):(old.capabilities||[]),
      executionSurface:"studio-web",
      localMedia:true,
      galleryAccess:false,
      // Only a real browser heartbeat confirms it is still available. Project updates must not refresh executor liveness.
      browserVisibility:["visible","hidden"].includes(meta.visibilityState)?meta.visibilityState:(old.browserVisibility||"unknown"),
      activeBrowserProjectId:clean(meta.activeProjectId||old.activeBrowserProjectId||"",80),
      lastExecutorHeartbeatAt:["visible","hidden"].includes(meta.visibilityState)?now():(old.lastExecutorHeartbeatAt||null),
      createdAt:old.createdAt||now(),
      lastSeenAt:now()
    };
    await this.ctx.storage.put(k,d); return d;
  }
  async device(deviceId){ return (await this.ctx.storage.get("d:"+deviceId))||null; }
  async createProject(deviceId,name,instruction=""){
    await this.register(deviceId);
    const id=crypto.randomUUID(), p={id,deviceId,name:clean(name||"Untitled Project",120),instruction:clean(instruction),createdAt:now(),updatedAt:now(),assets:[],timeline:[],settings:{aspect:"9:16",speed:1,mute:false,title:"",quality:"720p",transition:"fade"},drive:{},generation:{},latestRender:null,latestCommand:null};
    await this.ctx.storage.put("p:"+deviceId+":"+id,p);
    const k="pl:"+deviceId, ids=(await this.ctx.storage.get(k))||[]; ids.unshift(id); await this.ctx.storage.put(k,ids.slice(0,100)); return p;
  }
  async projects(deviceId){
    const ids=(await this.ctx.storage.get("pl:"+deviceId))||[], out=[];
    for(const id of ids){ const p=await this.ctx.storage.get("p:"+deviceId+":"+id); if(p) out.push(p); }
    return out;
  }
  async project(deviceId,id){ return (await this.ctx.storage.get("p:"+deviceId+":"+id))||null; }
  async update(deviceId,id,patch={}){
    const p=await this.project(deviceId,id); if(!p) return null;
    for(const k of ["name","instruction","assets","timeline","settings","drive","generation","latestRender","latestCommand"]) if(patch[k]!==undefined) p[k]=patch[k];
    p.updatedAt=now(); await this.ctx.storage.put("p:"+deviceId+":"+id,p); await this.register(deviceId); return p;
  }
  async enqueue(deviceId,projectId,action,parameters={}){
    if(!(await this.project(deviceId,projectId))) throw new Error("Project not found");
    const sk="seq:"+deviceId, seq=((await this.ctx.storage.get(sk))||0)+1; await this.ctx.storage.put(sk,seq);
    const c={id:crypto.randomUUID(),seq,deviceId,projectId,action,parameters,status:"queued",createdAt:now(),completedAt:null,result:null};
    const k="cl:"+deviceId, a=(await this.ctx.storage.get(k))||[]; a.push(c); await this.ctx.storage.put(k,a.slice(-60));
    await this.update(deviceId,projectId,{latestCommand:{id:c.id,action,status:c.status,createdAt:c.createdAt}}); return c;
  }
  async commands(deviceId,after=0){ const a=(await this.ctx.storage.get("cl:"+deviceId))||[]; return a.filter(c=>c.seq>Number(after||0)); }
  async command(deviceId,id){ const a=(await this.ctx.storage.get("cl:"+deviceId))||[]; return a.find(c=>c.id===id)||null; }
  async complete(deviceId,id,result={},status="completed"){
    const k="cl:"+deviceId, a=(await this.ctx.storage.get(k))||[], i=a.findIndex(c=>c.id===id); if(i<0) return null;
    a[i]={...a[i],status:clean(status,40)||"completed",completedAt:now(),result};
    for(let j=0;j<a.length;j++){
      if(j!==i&&a[j]&&a[j].result&&a[j].result.contactSheet&&a[j].result.contactSheet.base64){
        a[j]={...a[j],result:{...a[j].result,contactSheet:{...a[j].result.contactSheet,base64:undefined,expired:true}}};
      }
    }
    await this.ctx.storage.put(k,a.slice(-60));
    const c=a[i]; await this.update(deviceId,c.projectId,{latestCommand:{id:c.id,action:c.action,status:c.status,createdAt:c.createdAt,completedAt:c.completedAt,result:c.result}}); return c;
  }
  async enqueueRuntime(deviceId,projectId,action,parameters={}){
    if(!(await this.project(deviceId,projectId))) throw new Error("Project not found");
    const sk="rseq:"+deviceId, seq=((await this.ctx.storage.get(sk))||0)+1; await this.ctx.storage.put(sk,seq);
    const c={id:crypto.randomUUID(),seq,deviceId,projectId,action,parameters,status:"queued",createdAt:now(),completedAt:null,result:null,runtime:"studio-web"};
    const k="rcl:"+deviceId, a=(await this.ctx.storage.get(k))||[]; a.push(c); await this.ctx.storage.put(k,a.slice(-80));
    await this.update(deviceId,projectId,{latestCommand:{id:c.id,action,status:c.status,createdAt:c.createdAt,runtime:"studio-web"}});
    return c;
  }
  async runtimeCommands(deviceId,after=0){
    const a=(await this.ctx.storage.get("rcl:"+deviceId))||[];
    // Replay unfinished jobs even when an old browser advanced its sequence before acknowledging.
    return a.filter(c=>c.status==="queued"||c.seq>Number(after||0));
  }
  async runtimeCommand(deviceId,id){
    const a=(await this.ctx.storage.get("rcl:"+deviceId))||[];
    return a.find(c=>c.id===id)||null;
  }
  async completeRuntime(deviceId,id,result={},status="completed"){
    const k="rcl:"+deviceId, a=(await this.ctx.storage.get(k))||[], i=a.findIndex(c=>c.id===id);
    if(i<0) return null;
    a[i]={...a[i],status:clean(status,40)||"completed",completedAt:now(),result};
    await this.ctx.storage.put(k,a.slice(-80));
    const c=a[i];
    await this.update(deviceId,c.projectId,{latestCommand:{id:c.id,action:c.action,status:c.status,createdAt:c.createdAt,completedAt:c.completedAt,result:c.result,runtime:"studio-web"}});
    return c;
  }


  // Explicit, one-asset delivery to ChatGPT. Separate upload/download
  // capabilities prevent a browser command reader from obtaining download access.
  async createTemporaryVideoExport(deviceId,projectId,assetId){
    const project=await this.project(deviceId,projectId);
    if(!project) throw new Error("Project not found");
    const asset=(project.assets||[]).find(a=>a.id===assetId&&a.kind==="video");
    if(!asset)throw new Error("Requested video not registered in the selected project");
    const knownSize=Number(asset.size||0);
    if(!Number.isFinite(knownSize)||knownSize<=0||knownSize>12*1024*1024)throw new Error("One-time chat export supports videos up to 12 MiB");
    const newToken=()=>Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b=>b.toString(16).padStart(2,"0")).join("");
    const uploadToken=newToken(),downloadToken=newToken();
    const expiresAt=Date.now()+90*60*1000;
    const safeName=clean(asset.name||"VideoStudio-animation.mp4",120).replace(/[^A-Za-z0-9_.-]/g,"_");
    await this.ctx.storage.put("te:up:"+uploadToken,{
      deviceId,projectId,assetId,safeName,downloadToken,expectedSize:knownSize,expiresAt,status:"pending",
      size:0,parts:0
    });
    await this.ctx.storage.put("te:down:"+downloadToken,uploadToken);
    const previous=await this.ctx.storage.getAlarm();
    if(previous===null||previous>expiresAt) await this.ctx.storage.setAlarm(expiresAt);
    return {uploadToken,downloadToken,expiresAt,safeName};
  }
  async storeTemporaryVideoExport(uploadToken,bytes,mime){
    const key="te:up:"+uploadToken,rec=await this.ctx.storage.get(key);
    if(!rec||Date.now()>rec.expiresAt)throw new Error("This export request expired");
    if(rec.status==="ready")return {ok:true,size:rec.size,reused:true};
    if(!(bytes instanceof Uint8Array))bytes=new Uint8Array(bytes);
    if(!bytes.length||bytes.length>12*1024*1024||bytes.length>rec.expectedSize+8192)throw new Error("Invalid or oversized video payload");
    if(!["video/mp4","video/webm"].includes(String(mime||"").split(";")[0].trim()))throw new Error("Unsupported video MIME type");
    const partSize=64*1024,parts=Math.ceil(bytes.length/partSize);
    const digest=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",bytes))).map(n=>n.toString(16).padStart(2,"0")).join("");
    return this.ctx.storage.transaction(async transaction=>{
      const current=await transaction.get(key);
      if(!current||Date.now()>current.expiresAt)throw new Error("This export request expired");
      if(current.status==="ready")return {ok:true,size:current.size,reused:true};
      for(let i=0;i<parts;i++)await transaction.put("te:part:"+uploadToken+":"+i,bytes.slice(i*partSize,(i+1)*partSize));
      await transaction.put(key,{...current,status:"ready",size:bytes.length,mime:String(mime).split(";")[0].trim(),parts,digest});
      return {ok:true,size:bytes.length,digest};
    });
  }
  async getTemporaryVideoExportInfo(downloadToken){
    const uploadToken=await this.ctx.storage.get("te:down:"+downloadToken);
    if(!uploadToken) return null;
    const rec=await this.ctx.storage.get("te:up:"+uploadToken);
    if(!rec||Date.now()>rec.expiresAt||rec.status!=="ready")return null;
    return {uploadToken,expiresAt:rec.expiresAt,name:rec.safeName,size:rec.size,mime:rec.mime,parts:rec.parts,digest:rec.digest};
  }
  async getTemporaryVideoExportPart(uploadToken,index){
    const rec=await this.ctx.storage.get("te:up:"+uploadToken);
    if(!rec||rec.status!=="ready"||Date.now()>rec.expiresAt||!Number.isInteger(index)||index<0||index>=rec.parts)return null;
    return (await this.ctx.storage.get("te:part:"+uploadToken+":"+index))||null;
  }
  async alarm(){
    const current=Date.now();
    const items=await this.ctx.storage.list({prefix:"te:up:"});
    let next=null;
    for(const [key,rec] of items){
      if(rec.expiresAt>current){next=next===null?rec.expiresAt:Math.min(next,rec.expiresAt);continue;}
      await this.ctx.storage.delete("te:down:"+rec.downloadToken);
      for(let i=0;i<(rec.parts||0);i++)await this.ctx.storage.delete("te:part:"+key.slice(6)+":"+i);
      await this.ctx.storage.delete(key);
    }
    const privateNext=await cleanupPrivateBlobs(this.ctx.storage,current);
    if(privateNext!==null)next=next===null?privateNext:Math.min(next,privateNext);
    if(next!==null)await this.ctx.storage.setAlarm(next);
  }

  async status(deviceId){
    const d=await this.device(deviceId), ps=await this.projects(deviceId), a=(await this.ctx.storage.get("cl:"+deviceId))||[];
    const ra=(await this.ctx.storage.get("rcl:"+deviceId))||[]; return {connected:!!d,device:d,projectCount:ps.length,pendingCommands:a.filter(c=>c.status==="queued").length,pendingRuntimeCommands:ra.filter(c=>c.status==="queued").length,lastCommand:(ra[ra.length-1]||a[a.length-1]||null)};
  }

  async appRegister(deviceId,ownerKey,meta={}){
    if(!deviceId||String(deviceId).length<8) throw new Error("Invalid native device ID");
    if(!ownerKey||String(ownerKey).length<32) throw new Error("Invalid owner key");
    const hash=await sha256Hex(ownerKey), key="app-owner:"+hash;
    return this.ctx.storage.transaction(async transaction=>{
    const bound=await transaction.get(key);
    const dk="app-device:"+deviceId, old=(await readNativeDeviceInTransaction(transaction,dk))||{};
    if(old.supersededByDeviceId) throw new Error("This native identity is superseded by canonical device "+old.supersededByDeviceId);
    if(bound&&bound!==deviceId) throw new Error("Owner key is already bound to canonical device "+bound);
    if(old.ownerHash&&old.ownerHash!==hash) throw new Error("This native device is already bound to its owner credential");
    const clientGeneration=Math.max(0,Number(meta.appGeneration||0));
    const storedGeneration=Math.max(0,Number(old.appGeneration||0));
    if(storedGeneration>clientGeneration){
      const {ownerHash,...safeOld}=old;
      return {
        __staleClient:true,
        expectedGeneration:storedGeneration,
        receivedGeneration:clientGeneration,
        device:safeOld
      };
    }
    const mode=["one_file","all_tools","everything"].includes(meta.permissionMode)?meta.permissionMode:(old.permissionMode||"everything");
    const d={
      deviceId,
      name:clean(meta.name||old.name||"VideoStudio Android",80),
      platform:clean(meta.platform||old.platform||"android-native",80),
      appVersion:clean(meta.appVersion||old.appVersion||"1.0.0",30),
      appGeneration:Math.max(clientGeneration,storedGeneration),
      connectionCoreVersion:Math.max(0,Number(meta.connectionCoreVersion||old.connectionCoreVersion||0)),
      wireSchemaVersion:Math.max(1,Number(meta.wireSchemaVersion||old.wireSchemaVersion||1)),
      featureLevel:Math.max(0,Number(meta.featureLevel||old.featureLevel||0)),
      compatibilityPolicy:clean(meta.compatibilityPolicy||old.compatibilityPolicy||"stable-major-additive-features",80),
      transportDecoupledFromApkVersion:meta.transportDecoupledFromApkVersion!==false,
      protocolVersion:Number(meta.protocolVersion||old.protocolVersion||1),
      protocolMin:Number(meta.protocolMin||meta.protocolVersion||old.protocolMin||old.protocolVersion||1),
      protocolMax:Number(meta.protocolMax||meta.protocolVersion||old.protocolMax||old.protocolVersion||1),
      preferredProtocol:Number(meta.preferredProtocol||meta.protocolVersion||old.preferredProtocol||old.protocolVersion||1),
      protocolFamily:clean(meta.protocolFamily||old.protocolFamily||"videostudio-native",80),
      stableMcpEndpoint:meta.stableMcpEndpoint!==false,
      stableMcpPath:clean(meta.stableMcpPath||old.stableMcpPath||"/app-mcp-v3/",120),
      additiveActionBridge:meta.additiveActionBridge!==false,
      nativeAgent:clean(meta.nativeAgent||old.nativeAgent||"",80),
      directAttachmentIngest:!!meta.directAttachmentIngest,
      localEngineOwnsProjects:meta.localEngineOwnsProjects!==false,
      portraitAnimationEngine:clean(meta.portraitAnimationEngine||old.portraitAnimationEngine||"",80),
      onDevicePortraitAi:!!meta.onDevicePortraitAi,
      permissionMode:mode,
      projects:Array.isArray(meta.projects)?meta.projects:(old.projects||[]),
      controlPaused:!!meta.controlPaused,
      connectionSession:clean(meta.connectionSession||old.connectionSession||"",80),
      galleryAccess:false,
      createdAt:old.createdAt||now(),
      lastSeenAt:now(),
      nativeApp:true,
      ownerHash:hash
    };
    for(const field of ["ownerAliases","canonicalIdentity","identityConvergedAt","identityAliasCount"])
      if(old[field]!==undefined)d[field]=old[field];
    await transaction.put(key,deviceId);
    await writeNativeDeviceInTransaction(transaction,dk,d);
    const {ownerHash,...safe}=d;
    return safe;
    });
  }
  async appResolve(ownerKey){
    if(!ownerKey) return null;
    const hash=await sha256Hex(ownerKey), id=await this.ctx.storage.get("app-owner:"+hash);
    if(!id) return null;
    let device=(await readNativeDevice(this.ctx.storage,"app-device:"+id))||null;
    if(device&&device.supersededByDeviceId){
      const canonical=(await readNativeDevice(this.ctx.storage,"app-device:"+device.supersededByDeviceId))||null;
      if(canonical){
        await this.ctx.storage.put("app-owner:"+hash,canonical.deviceId);
        device=canonical;
      }
    }
    return device;
  }

  async appConvergeOwnerAliases(primaryOwnerKey,legacyOwnerKeys=[],primaryDeviceId=""){
    if(!primaryOwnerKey||String(primaryOwnerKey).length<32) throw new Error("Primary owner key is required");
    const primaryHash=await sha256Hex(primaryOwnerKey);
    let canonicalId=await this.ctx.storage.get("app-owner:"+primaryHash);
    if(!canonicalId&&primaryDeviceId){
      const candidate=await readNativeDevice(this.ctx.storage,"app-device:"+String(primaryDeviceId));
      if(candidate&&candidate.ownerHash===primaryHash){
        canonicalId=candidate.deviceId;
        await this.ctx.storage.put("app-owner:"+primaryHash,canonicalId);
      }
    }
    if(!canonicalId) throw new Error("Primary native identity could not be proven");
    let canonical=await readNativeDevice(this.ctx.storage,"app-device:"+canonicalId);
    if(!canonical) throw new Error("Canonical native device record is missing");
    if(canonical.supersededByDeviceId){
      const next=await readNativeDevice(this.ctx.storage,"app-device:"+canonical.supersededByDeviceId);
      if(!next) throw new Error("Canonical native identity chain is broken");
      canonical=next;
      canonicalId=next.deviceId;
      await this.ctx.storage.put("app-owner:"+primaryHash,canonicalId);
    }

    const aliases=new Set(Array.isArray(canonical.ownerAliases)?canonical.ownerAliases:[]);
    aliases.add(primaryHash);
    const keys=[primaryOwnerKey,...(Array.isArray(legacyOwnerKeys)?legacyOwnerKeys:[])];
    const seen=new Set();
    for(const raw of keys){
      const key=String(raw||"");
      if(key.length<32) continue;
      const hash=await sha256Hex(key);
      if(seen.has(hash)) continue;
      seen.add(hash);
      aliases.add(hash);
      const id=await this.ctx.storage.get("app-owner:"+hash);
      if(!id&&hash!==primaryHash) throw new Error("Legacy owner key could not be proven");
      if(!id||id===canonicalId){
        await this.ctx.storage.put("app-owner:"+hash,canonicalId);
        continue;
      }
      const legacy=await readNativeDevice(this.ctx.storage,"app-device:"+id);
      if(!legacy) throw new Error("Legacy native device record is missing");

      await this.ctx.storage.transaction(async transaction=>{
        const liveCanonical=await readNativeDeviceInTransaction(transaction,"app-device:"+canonicalId),
          liveLegacy=await readNativeDeviceInTransaction(transaction,"app-device:"+id);
        if(!liveCanonical||!liveLegacy||liveCanonical.ownerHash!==canonical.ownerHash||liveLegacy.ownerHash!==legacy.ownerHash
          ||await transaction.get("app-owner:"+hash)!==id||await transaction.get("app-owner:"+primaryHash)!==canonicalId)
          throw new Error("Native ownership changed during alias convergence");
        const targetKey="app-v3-cl:"+canonicalId,sourceKey="app-v3-cl:"+id;
        const currentRows=await readCommandQueueInTransaction(transaction,targetKey),legacyRows=await readCommandQueueInTransaction(transaction,sourceKey);
        const mergedProof=await mergeCommandReceiptsInTransaction(transaction,targetKey,sourceKey);
        const completed=new Map((await readCommandReceiptsInTransaction(transaction,targetKey)).map(row=>[row.id,row]));
        const byId=new Map(currentRows.map(row=>[row.id,row]));
        let seq=Math.max(Number((await transaction.get("app-v3-seq:"+canonicalId))||0),mergedProof.highestSeq,...currentRows.map(row=>row.seq),0);
        for(const row of legacyRows){
          if(byId.has(row.id)){
            const current=byId.get(row.id);
            if(commandIsPending(current)!==commandIsPending(row)||current.action!==row.action
                ||await canonicalCommandResultSha256(current.parameters)!==await canonicalCommandResultSha256(row.parameters))throw new Error("Conflicting accepted alias command identity");
            if(!commandIsPending(current)&&(current.status!==row.status||await canonicalCommandResultSha256(current.result)!==await canonicalCommandResultSha256(row.result)))throw new Error("Conflicting terminal alias result");
            continue;
          }
          if(completed.has(row.id))continue;
          byId.set(row.id,{...row,seq:++seq,deviceId:canonicalId,migratedFromDeviceId:id,migratedAt:now()});
        }
        const merged=[...byId.values()].sort((left,right)=>left.seq-right.seq);
        await writeCommandQueueInTransaction(transaction,targetKey,merged,new Set(merged.filter(row=>!commandIsPending(row)).map(row=>row.id)));
        await transaction.put("app-v3-seq:"+canonicalId,seq);
        liveLegacy.supersededByDeviceId=canonicalId;liveLegacy.supersededAt=now();liveLegacy.canonicalIdentity=false;
        await writeNativeDeviceInTransaction(transaction,"app-device:"+id,liveLegacy);await transaction.put("app-owner:"+hash,canonicalId);
      });
    }

    canonical=await this.ctx.storage.transaction(async transaction=>{
      const live=await readNativeDeviceInTransaction(transaction,"app-device:"+canonicalId);
      if(!live||live.ownerHash!==canonical.ownerHash||await transaction.get("app-owner:"+primaryHash)!==canonicalId)
        throw new Error("Canonical native ownership changed during convergence");
      const combined=new Set(Array.isArray(live.ownerAliases)?live.ownerAliases:[]);for(const alias of aliases)combined.add(alias);
      live.ownerAliases=[...combined];live.canonicalIdentity=true;live.identityConvergedAt=now();live.identityAliasCount=combined.size;
      await writeNativeDeviceInTransaction(transaction,"app-device:"+canonicalId,live);return live;
    });
    return {
      ok:true,
      canonicalDeviceId:canonicalId,
      appVersion:canonical.appVersion||"",
      aliasCount:canonical.ownerAliases.length,
      retainedCommandCount:(await readCommandQueue(this.ctx.storage,"app-v3-cl:"+canonicalId)).length,
      galleryAccess:false
    };
  }
  async appBindStudioWebFallback(ownerKey,webDeviceId){
    const native=await this.appResolve(ownerKey);
    if(!native) throw new Error("Private App MCP credential rejected");
    const id=String(webDeviceId||"").trim();
    if(id.length<8) throw new Error("Invalid Studio Web device ID");
    const web=await this.device(id);
    if(!web) throw new Error("Studio Web device is not registered");
    const ownerHash=await sha256Hex(ownerKey);
    const projects=await this.projects(id);
    const binding={
      id:crypto.randomUUID(),
      nativeDeviceId:native.deviceId,
      webDeviceId:id,
      fallbackWebProjectId:projects[0]&&projects[0].id||"",
      createdAt:Date.now(),
      updatedAt:Date.now(),
      galleryAccess:false
    };
    await this.ctx.storage.put("app-web-fallback:"+ownerHash,binding);
    return {
      bound:true,
      nativeDeviceId:binding.nativeDeviceId,
      webDeviceId:binding.webDeviceId,
      fallbackWebProjectId:binding.fallbackWebProjectId,
      note:"The stable native MCP may route browser-capable work here when Android is sleeping."
    };
  }
  async appResolveStudioWebFallback(ownerKey){
    if(!ownerKey) return null;
    const ownerHash=await sha256Hex(ownerKey);
    const binding=await this.ctx.storage.get("app-web-fallback:"+ownerHash);
    if(!binding) return null;
    const web=await this.device(binding.webDeviceId);
    if(!web) return null;
    const last=Date.parse(String(web.lastSeenAt||""))||0;
    const age=last>0?Math.max(0,Date.now()-last):Number.MAX_SAFE_INTEGER;
    const fresh=age<=45000;
    let projectId=String(binding.fallbackWebProjectId||"");
    let project=projectId?await this.project(binding.webDeviceId,projectId):null;
    if(!project){
      const projects=await this.projects(binding.webDeviceId);
      project=projects[0]||null;
      projectId=project&&project.id||"";
      if(projectId!==binding.fallbackWebProjectId){
        binding.fallbackWebProjectId=projectId;
        binding.updatedAt=Date.now();
        await this.ctx.storage.put("app-web-fallback:"+ownerHash,binding);
      }
    }
    return {binding,web,fresh,ageMs:age===Number.MAX_SAFE_INTEGER?null:age,project};
  }
  async appTryStudioWebFallback(ownerKey,action,parameters={}){
    const fallback=await this.appResolveStudioWebFallback(ownerKey);
    if(!fallback||!fallback.fresh||!fallback.project) return null;
    const webDeviceId=fallback.binding.webDeviceId;
    const projectId=parameters.webProjectId&&await this.project(webDeviceId,String(parameters.webProjectId))
      ? String(parameters.webProjectId)
      : fallback.project.id;
    const p={...parameters};
    let command=null;
    if(action==="prompt_video"||action==="create_prompt_video"){
      command=await this.enqueueRuntime(webDeviceId,projectId,"generate_video",{
        mode:"prompt_scene",
        prompt:clean(p.prompt||"",2000),
        style:clean(p.style||"cinematic",80)||"cinematic",
        duration:Math.max(4,Math.min(60,Number(p.durationSeconds||12))),
        fps:24,
        aspect:["9:16","16:9","1:1","4:5"].includes(p.aspect)?p.aspect:"9:16",
        quality:p.quality==="720p"?"720p":"1080p"
      });
    }else if(action==="animate_images"){
      command=await this.enqueueRuntime(webDeviceId,projectId,"generate_video",{
        mode:"story_video",
        prompt:clean(p.environment||p.style||"cinematic image animation",1200),
        style:clean(p.style||"cinematic",80)||"cinematic",
        duration:Math.max(4,Math.min(60,Number(p.durationSecondsPerImage||4)*4)),
        fps:24,
        aspect:["9:16","16:9","1:1","4:5"].includes(p.aspect)?p.aspect:"9:16",
        quality:p.quality==="720p"?"720p":"1080p"
      });
    }else if(action==="export_project"){
      command=await this.enqueue(webDeviceId,projectId,"render",{});
    }else if(action==="autonomous_edit"){
      command=await this.enqueue(webDeviceId,projectId,"autonomous_request",{
        instruction:p.instruction||"",
        clips:Array.isArray(p.clips)?p.clips:undefined,
        aspect:p.aspect,
        quality:p.quality,
        transition:p.transition,
        title:p.title,
        mute:p.mute,
        render:p.render!==false,
        inspectAfterRender:!!p.inspectAfterRender
      });
    }else if(action==="apply_edit_plan"&&Array.isArray(p.clips)){
      command=await this.enqueue(webDeviceId,projectId,"replace_timeline",{clips:p.clips});
    }else if(action==="analyse_media"){
      command=await this.enqueue(webDeviceId,projectId,"analyse_media",p);
    }else if(action==="apply_tool"){
      const tool=String(p.tool||"").toLowerCase();
      const settings=p.settings||{};
      if(tool==="speed"||tool==="slow_motion"){
        command=await this.enqueue(webDeviceId,projectId,"set_clip_speed",{
          index:Number(p.clipIndex||0),
          speed:Number(settings.speed||settings.value||(tool==="slow_motion" ? .5 : 1))
        });
      }else if(tool==="title"){
        command=await this.enqueue(webDeviceId,projectId,"set_clip_title",{
          index:Number(p.clipIndex||0),
          title:clean(settings.title||settings.value||"",120)
        });
      }else if(tool==="trim"){
        command=await this.enqueue(webDeviceId,projectId,"set_trim",{
          index:Number(p.clipIndex||0),
          start:Number(settings.start??settings.inPoint??0),
          end:Number(settings.end??settings.outPoint??0)
        });
      }else if(["effect","color","motion","blur","reframe","mask","green_screen","audio_duck","volume"].includes(tool)){
        command=await this.enqueue(webDeviceId,projectId,"set_clip_effects",{
          index:Number(p.clipIndex||0),
          effects:settings
        });
      }else if(tool==="transition"){
        command=await this.enqueue(webDeviceId,projectId,"autonomous_request",{
          instruction:"Apply requested transition",
          transition:clean(settings.transition||settings.value||"fade",40),
          render:false
        });
      }
    }else if(action==="creator_preset"){
      command=await this.enqueue(webDeviceId,projectId,"autonomous_request",{
        instruction:"Apply creator preset "+clean(p.preset||"",80),
        transition:p.transition,
        render:false
      });
    }
    if(!command) return null;
    return {
      ...command,
      hybridRoute:"studio_web",
      routedFromNativeAction:action,
      nativeDeferred:false,
      webDeviceId,
      webProjectId:projectId
    };
  }
  async appCreateHybridBinding(ownerKey,webDeviceId){
    const native=await this.appResolve(ownerKey);
    if(!native) throw new Error("Private App MCP credential rejected");
    if(!webDeviceId||String(webDeviceId).length<8) throw new Error("Invalid Studio Web device ID");
    const web=await this.device(String(webDeviceId));
    if(!web) throw new Error("Studio Web device is not registered");
    const ownerHash=await sha256Hex(ownerKey);
    const activeKey="hybrid-challenge-active:"+ownerHash+":"+String(webDeviceId);
    const activeToken=await this.ctx.storage.get(activeKey);
    if(activeToken){
      const existing=await this.ctx.storage.get("hybrid-challenge:"+activeToken);
      if(existing&&!existing.used&&Number(existing.expiresAt||0)>Date.now()){
        return {token:activeToken,expiresAt:existing.expiresAt,webDeviceId:existing.webDeviceId,nativeDeviceId:existing.nativeDeviceId};
      }
    }
    const token=crypto.randomUUID()+"."+crypto.randomUUID();
    const record={
      token,
      ownerHash,
      nativeDeviceId:native.deviceId,
      webDeviceId:String(webDeviceId),
      createdAt:Date.now(),
      expiresAt:Date.now()+15*60*1000,
      used:false
    };
    await this.ctx.storage.put("hybrid-challenge:"+token,record);
    await this.ctx.storage.put(activeKey,token);
    return {token,expiresAt:record.expiresAt,webDeviceId:record.webDeviceId,nativeDeviceId:record.nativeDeviceId};
  }
  async appRedeemHybridBinding(token,webDeviceId){
    if(!token||String(token).length<30) throw new Error("Invalid hybrid binding token");
    if(!webDeviceId||String(webDeviceId).length<8) throw new Error("Invalid Studio Web device ID");
    const key="hybrid-challenge:"+String(token);
    const record=await this.ctx.storage.get(key);
    if(!record||record.used) throw new Error("Hybrid binding token is invalid or already used");
    if(Number(record.expiresAt||0)<Date.now()){
      await this.ctx.storage.delete(key);
      throw new Error("Hybrid binding token expired");
    }
    if(record.webDeviceId!==String(webDeviceId)) throw new Error("Hybrid binding token belongs to another Studio Web device");
    const native=await readNativeDevice(this.ctx.storage,"app-device:"+record.nativeDeviceId);
    if(!native) throw new Error("Native device is no longer registered");
    const hybridKey=(crypto.randomUUID()+crypto.randomUUID()).replace(/-/g,"");
    const hybridHash=await sha256Hex(hybridKey);
    const binding={
      id:crypto.randomUUID(),
      nativeDeviceId:record.nativeDeviceId,
      webDeviceId:record.webDeviceId,
      ownerHash:record.ownerHash,
      createdAt:Date.now(),
      lastUsedAt:Date.now(),
      revoked:false,
      stablePath:"/mcp-v06/"
    };
    await this.ctx.storage.put("hybrid-binding:"+hybridHash,binding);
    await this.ctx.storage.put("hybrid-binding-owner:"+record.ownerHash+":"+record.webDeviceId,hybridHash);
    record.used=true;
    record.usedAt=Date.now();
    record.hybridHash=hybridHash;
    await this.ctx.storage.put(key,record);
    await this.ctx.storage.delete("hybrid-challenge-active:"+record.ownerHash+":"+record.webDeviceId);
    return {
      hybridKey,
      privateMcpPath:"/mcp-v06/"+hybridKey,
      nativeDeviceId:record.nativeDeviceId,
      webDeviceId:record.webDeviceId,
      createdAt:binding.createdAt
    };
  }
  async appResolveHybrid(hybridKey){
    if(!hybridKey||String(hybridKey).length<32) return null;
    const hybridHash=await sha256Hex(hybridKey);
    return this.ctx.storage.transaction(async transaction=>{
      const binding=await transaction.get("hybrid-binding:"+hybridHash);
      if(!binding||binding.revoked) return null;
      const canonicalId=await transaction.get("app-owner:"+binding.ownerHash);
      if(!canonicalId) return null;
      const native=await readNativeDeviceInTransaction(transaction,"app-device:"+canonicalId);
      if(!native||native.supersededByDeviceId) return null;
      binding.nativeDeviceId=canonicalId;binding.lastUsedAt=Date.now();
      await transaction.put("hybrid-binding:"+hybridHash,binding);
      const {ownerHash,...safeBinding}=binding;
      const {ownerHash:nativeOwnerHash,...safeNative}=native;
      return {binding:safeBinding,native:safeNative};
    });
  }
  async appEnqueueHybrid(hybridKey,action,parameters={}){
    const resolved=await this.appResolveHybrid(hybridKey);
    if(!resolved) throw new Error("Private hybrid binding rejected");
    parameters=nativeStoryboardParameters(action,parameters);
    const d=resolved.native;
    requireStoryboardNativeVersion(d,action,parameters);
    const min=Number(d.protocolMin||d.protocolVersion||0), max=Number(d.protocolMax||d.protocolVersion||0);
    if(!(min<=3&&max>=3)) throw new Error("Hybrid native device does not support MCP v3");
    if(d.controlPaused) throw new Error("ChatGPT control is paused on the phone");
    if(!appActionAllowed(d.permissionMode,action)) throw new Error("Action blocked by device permission mode or Gallery privacy boundary: "+d.permissionMode);
    return this.appQueueNativeV3(d,action,parameters,{hybridHash:await sha256Hex(hybridKey),bindingId:resolved.binding.id});
  }
  async appQueueNativeV3(device,action,parameters,identity){
    const key="app-v3-cl:"+device.deviceId,sequenceKey="app-v3-seq:"+device.deviceId;
    return this.ctx.storage.transaction(async transaction=>{
      let ownerHash=identity&&identity.ownerHash;
      if(identity&&identity.hybridHash){
        const binding=await transaction.get("hybrid-binding:"+identity.hybridHash);
        if(!binding||binding.revoked||binding.id!==identity.bindingId||binding.nativeDeviceId!==device.deviceId)
          throw new Error("Private hybrid binding changed before command admission; retry the current binding");
        ownerHash=binding.ownerHash;
      }
      const live=await readNativeDeviceInTransaction(transaction,"app-device:"+device.deviceId);
      if(!ownerHash||!live||live.supersededByDeviceId||!live.ownerHash
          ||device.ownerHash&&device.ownerHash!==live.ownerHash
          ||await transaction.get("app-owner:"+ownerHash)!==device.deviceId)
        throw new Error("Native identity changed before command admission; retry the current binding");
      const min=Number(live.protocolMin||live.protocolVersion||0),max=Number(live.protocolMax||live.protocolVersion||0);
      if(!(min<=3&&max>=3))throw new Error("VideoStudio stable MCP compatibility lane v3 is not registered");
      requireStoryboardNativeVersion(live,action,parameters);
      if(live.controlPaused)throw new Error("ChatGPT control is paused on the phone");
      if(!appActionAllowed(live.permissionMode,action))throw new Error("Action blocked by device permission mode or Gallery privacy boundary: "+live.permissionMode);
      const rows=await readCommandQueueInTransaction(transaction,key);
      const lastSeenMs=Date.parse(String(live.lastSeenAt||""))||0,fresh=lastSeenMs>0&&Date.now()-lastSeenMs<=45000;
      const sequence=Math.max(Number((await transaction.get(sequenceKey))||0),...rows.map(row=>row.seq),0)+1;
      const command={id:crypto.randomUUID(),seq:sequence,protocolVersion:3,deviceId:device.deviceId,action,parameters,
        status:fresh?"queued":"waiting_native",waitingReason:fresh?"":"native_offline",createdAt:now(),completedAt:null,result:null};
      requireCommandQueueAdmission(rows,command);
      await transaction.put(sequenceKey,sequence);
      rows.push(command);await writeCommandQueueInTransaction(transaction,key,rows);
      return command;
    });
  }
  async appCommandHybrid(hybridKey,id){
    const resolved=await this.appResolveHybrid(hybridKey);
    if(!resolved) throw new Error("Private hybrid binding rejected");
    const list=await readCommandQueue(this.ctx.storage,"app-v3-cl:"+resolved.native.deviceId);
    return list.find(c=>c.id===id)||await readCommandReceipt(this.ctx.storage,"app-v3-cl:"+resolved.native.deviceId,id);
  }
  async appStatusHybrid(hybridKey){
    const resolved=await this.appResolveHybrid(hybridKey);
    if(!resolved) return {connected:false,hybrid:true,error:"Private hybrid binding rejected"};
    const d=resolved.native, binding=resolved.binding;
    const list=await readCommandQueue(this.ctx.storage,"app-v3-cl:"+d.deviceId);
    const nativeLast=Date.parse(String(d.lastSeenAt||""))||0;
    const nativeAge=nativeLast>0?Math.max(0,Date.now()-nativeLast):Number.MAX_SAFE_INTEGER;
    const nativeFresh=nativeAge<=45000;
    const web=await this.device(binding.webDeviceId);
    const webLast=Date.parse(String(web&&web.lastSeenAt||""))||0;
    const webAge=webLast>0?Math.max(0,Date.now()-webLast):Number.MAX_SAFE_INTEGER;
    const webFresh=!!web&&webAge<=45000;
    const pending=list.filter(commandIsPending).length;
    return {
      connected:true,
      controlPlaneConnected:true,
      executionAvailable:webFresh||nativeFresh,
      hybrid:true,
      binding,
      web:{connected:webFresh,lastSeenAgeMs:webAge===Number.MAX_SAFE_INTEGER?null:webAge,device:web||null},
      native:{
        connected:nativeFresh,
        lastSeenAgeMs:nativeAge===Number.MAX_SAFE_INTEGER?null:nativeAge,
        appVersion:d.appVersion||"",
        appGeneration:Number(d.appGeneration||0),
        protocolVersion:Number(d.protocolVersion||0),
        connectionCoreVersion:Number(d.connectionCoreVersion||0),
        permissionMode:d.permissionMode||"everything",
        deviceId:d.deviceId
      },
      pendingNativeCommands:pending,
      relay:relayRelease(),
      commandBacklog:commandBacklog(list),
      waitingNative:!nativeFresh&&pending>0,
      lastCommand:nativeCommandSummary(list[list.length-1]||null),
      galleryAccess:false
    };
  }
  async appRevokeHybrid(hybridKey){
    if(!hybridKey||String(hybridKey).length<32) throw new Error("Private hybrid binding rejected");
    const hybridHash=await sha256Hex(hybridKey);
    const binding=await this.ctx.storage.get("hybrid-binding:"+hybridHash);
    if(!binding||binding.revoked) throw new Error("Private hybrid binding rejected");
    binding.revoked=true;
    binding.revokedAt=Date.now();
    await this.ctx.storage.put("hybrid-binding:"+hybridHash,binding);
    return {revoked:true,bindingId:binding.id,nativeDeviceId:binding.nativeDeviceId,webDeviceId:binding.webDeviceId};
  }
  async appCreateRebind(ownerKey){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    const ownerHash=await sha256Hex(ownerKey);
    const activeKey="app-rebind-active:"+ownerHash;
    const activeToken=await this.ctx.storage.get(activeKey);
    if(activeToken){
      const existing=await this.ctx.storage.get("app-rebind:"+activeToken);
      if(existing&&Number(existing.expiresAt||0)>Date.now()&&!existing.used){
        return {
          token:activeToken,
          expiresAt:existing.expiresAt,
          deepLink:"videostudio://mcp-rebind?token="+encodeURIComponent(activeToken)
        };
      }
    }
    const token=crypto.randomUUID()+"."+crypto.randomUUID();
    const record={
      token,
      oldOwnerHash:ownerHash,
      oldDeviceId:d.deviceId,
      createdAt:Date.now(),
      expiresAt:Date.now()+15*60*1000,
      used:false
    };
    await this.ctx.storage.put("app-rebind:"+token,record);
    await this.ctx.storage.put(activeKey,token);
    return {
      token,
      expiresAt:record.expiresAt,
      deepLink:"videostudio://mcp-rebind?token="+encodeURIComponent(token)
    };
  }

  async appRedeemRebind(token,deviceId,newOwnerKey,meta={}){
    if(!token||String(token).length<30) throw new Error("Invalid MCP rebind token");
    if(!deviceId||String(deviceId).length<8) throw new Error("Invalid native device ID");
    if(!newOwnerKey||String(newOwnerKey).length<32) throw new Error("Invalid owner key");
    const key="app-rebind:"+String(token);
    const record=await this.ctx.storage.get(key);
    if(!record||record.used) throw new Error("MCP rebind token is invalid or already used");
    if(Number(record.expiresAt||0)<Date.now()){
      await this.ctx.storage.delete(key);
      throw new Error("MCP rebind token expired");
    }

    const registered=await this.appRegister(deviceId,newOwnerKey,meta);
    if(registered&&registered.__staleClient) throw new Error("New Native Agent generation is stale");
    const newHash=await sha256Hex(newOwnerKey);
    const device=(await readNativeDevice(this.ctx.storage,"app-device:"+deviceId))||{deviceId};
    device.reboundFromDeviceId=record.oldDeviceId||"";
    device.reboundAt=now();
    device.stableEndpointAliases=2;
    await writeNativeDevice(this.ctx.storage,"app-device:"+deviceId,device);

    // Preserve the old ChatGPT connector credential as an alias to the new
    // Native Agent while the newly installed app keeps its own fresh secret.
    await this.ctx.storage.put("app-owner:"+record.oldOwnerHash,deviceId);
    await this.ctx.storage.put("app-owner:"+newHash,deviceId);

    record.used=true;
    record.usedAt=Date.now();
    record.newDeviceId=deviceId;
    await this.ctx.storage.put(key,record);
    await this.ctx.storage.delete("app-rebind-active:"+record.oldOwnerHash);

    const {ownerHash,...safe}=device;
    return safe;
  }


  async appAuth(deviceId,ownerKey){
    const d=await this.appResolve(ownerKey);
    return d&&d.deviceId===deviceId?d:null;
  }
  async appEnqueue(ownerKey,action,parameters={}){
    parameters=nativeStoryboardParameters(action,parameters);
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    const ownerHash=await sha256Hex(ownerKey),key="app-cl:"+d.deviceId,sequenceKey="app-seq:"+d.deviceId;
    return mutateCommandQueue(this.ctx.storage,key,async(rows,transaction)=>{
      const live=await readNativeDeviceInTransaction(transaction,"app-device:"+d.deviceId);
      if(!live||live.supersededByDeviceId||await transaction.get("app-owner:"+ownerHash)!==d.deviceId)throw new Error("Native identity changed before command admission");
      requireStoryboardNativeVersion(live,action,parameters);
      if(live.controlPaused)throw new Error("ChatGPT control is paused on the phone");
      if(!appActionAllowed(live.permissionMode,action))throw new Error("Action blocked by device permission mode or Gallery privacy boundary: "+live.permissionMode);
      const seq=Math.max(Number(await transaction.get(sequenceKey)||0),...rows.map(row=>row.seq),0)+1;
      const command={id:crypto.randomUUID(),seq,deviceId:d.deviceId,action,parameters,status:"queued",createdAt:now(),completedAt:null,result:null};
      requireCommandQueueAdmission(rows,command);rows.push(command);await transaction.put(sequenceKey,seq);
      return {rows,result:command};
    });
  }
  async appCommands(deviceId,ownerKey,after=0,waitMs=0){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const until=Date.now()+Math.max(0,Math.min(20000,Number(waitMs||0))),key="app-cl:"+deviceId;
    while(true){
      const found=await mutateCommandQueue(this.ctx.storage,key,rows=>{
        const claimed=[];let bytes=2;
        for(let i=0;i<rows.length;i++){
          const row=rows[i],expired=row.status==="claimed"&&Number(row.leaseUntil||0)<=Date.now();
          if(row.status==="queued"||row.status==="waiting_native"||expired){
            const next={...row,status:"claimed",claimedAt:now(),leaseUntil:Date.now()+45000,claimCount:Number(row.claimCount||0)+1};
            const length=new TextEncoder().encode(JSON.stringify(next)).byteLength+1;
            if(bytes+length>3*1024*1024)break;
            rows[i]=next;claimed.push(next);bytes+=length;if(claimed.length>=4)break;
          }
        }
        return {rows,result:claimed,changed:claimed.length>0};
      });
      if(found.length||Date.now()>=until)return found;
      await new Promise(resolve=>setTimeout(resolve,650));
    }
  }
  async appComplete(deviceId,ownerKey,id,result={},status="completed"){
    if(!(await this.appAuth(deviceId,ownerKey)))throw new Error("Native app authorization failed");
    const key="app-cl:"+deviceId;
    return mutateCommandQueue(this.ctx.storage,key,async(rows,transaction)=>{
      const index=rows.findIndex(row=>row.id===id);
      if(index<0)return {rows,changed:false,result:await acknowledgePrunedCommand(transaction,key,id,result,status)};
      const old=rows[index],updated=await terminalCommandUpdate(old,result,status);
      rows[index]=updated;
      const device=await readNativeDeviceInTransaction(transaction,"app-device:"+deviceId);
      if(device){device.lastSeenAt=now();await writeNativeDeviceInTransaction(transaction,"app-device:"+deviceId,device);}
      return {rows,result:{...updated,resultReplayed:!commandIsPending(old)}};
    });
  }
  async appCommand(ownerKey,id){
    const d=await this.appResolve(ownerKey);
    if(!d)throw new Error("Private App MCP credential rejected");
    const key="app-cl:"+d.deviceId,rows=await readCommandQueue(this.ctx.storage,key);
    return rows.find(row=>row.id===id)||await readCommandReceipt(this.ctx.storage,key,id);
  }
  async appStatus(ownerKey){
    const d=await this.appResolve(ownerKey);
    if(!d)return {connected:false,error:"Private App MCP credential rejected"};
    const rows=await readCommandQueue(this.ctx.storage,"app-cl:"+d.deviceId);
    return {connected:true,device:((({ownerHash,...safe})=>safe)(d)),pendingCommands:rows.filter(commandIsPending).length,
      lastCommand:nativeCommandSummary(rows[rows.length-1]||null),projectCount:Array.isArray(d.projects)?d.projects.length:0};
  }
  async appV3Device(ownerKey){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("VideoStudio MCP v3 credential rejected");
    const min=Number(d.protocolMin||d.protocolVersion||0);
    const max=Number(d.protocolMax||d.protocolVersion||0);
    if(!(min<=3&&max>=3)) throw new Error("VideoStudio stable MCP compatibility lane v3 is not supported by this app");
    return d;
  }
  async appEnqueueV3(ownerKey,action,parameters={}){
    parameters=nativeStoryboardParameters(action,parameters);
    const d=await this.appV3Device(ownerKey);
    requireStoryboardNativeVersion(d,action,parameters);
    if(d.controlPaused) throw new Error("ChatGPT control is paused on the phone");
    if(!appActionAllowed(d.permissionMode,action)) throw new Error("Action blocked by device permission mode or Gallery privacy boundary: "+d.permissionMode);
    const lastSeenMs=Date.parse(String(d.lastSeenAt||""))||0,fresh=lastSeenMs>0&&Date.now()-lastSeenMs<=45000;
    if(!fresh&&!STORYBOARD_NATIVE_ACTIONS.has(action)){
      const webCommand=await this.appTryStudioWebFallback(ownerKey,action,parameters);
      if(webCommand)return webCommand;
    }
    return this.appQueueNativeV3(d,action,parameters,{ownerHash:await sha256Hex(ownerKey)});
  }
  async appCommandsV3(deviceId,ownerKey,after=0,waitMs=0){
    const d=await this.appAuth(deviceId,ownerKey);
    if(!d) throw new Error("VideoStudio v3 native authorization failed");
    const min=Number(d.protocolMin||d.protocolVersion||0), max=Number(d.protocolMax||d.protocolVersion||0);
    if(!(min<=3&&max>=3)) throw new Error("VideoStudio stable MCP compatibility lane v3 is not registered");
    const until=Date.now()+Math.max(0,Math.min(20000,Number(waitMs||0)));
    const key="app-v3-cl:"+deviceId;
    while(true){
      const found=await mutateCommandQueue(this.ctx.storage,key,(list)=>{
        const nowMs=Date.now(),claimed=[];let replyBytes=2;
        for(let i=0;i<list.length;i++){
          const row=list[i],expired=row.status==="claimed"&&Number(row.leaseUntil||0)<=nowMs;
          if(row.status==="queued"||row.status==="waiting_native"||expired){
            const leased={...row,status:"claimed",waitingReason:"",claimedAt:now(),leaseUntil:nowMs+60000,claimCount:Number(row.claimCount||0)+1};
            const bytes=new TextEncoder().encode(JSON.stringify(leased)).byteLength+1;
            if(replyBytes+bytes>3*1024*1024)break;
            list[i]=leased;replyBytes+=bytes;claimed.push(leased);if(claimed.length>=4)break;
          }
        }
        return {rows:list,result:claimed,changed:claimed.length>0};
      });
      if(found.length||Date.now()>=until) return found;
      await new Promise(resolve=>setTimeout(resolve,500));
    }
  }
  async appCompleteV3(deviceId,ownerKey,id,result={},status="completed"){
    const d=await this.appAuth(deviceId,ownerKey);
    if(!d) throw new Error("VideoStudio v3 native authorization failed");
    const min=Number(d.protocolMin||d.protocolVersion||0), max=Number(d.protocolMax||d.protocolVersion||0);
    if(!(min<=3&&max>=3)) throw new Error("VideoStudio stable MCP compatibility lane v3 is not registered");
    const key="app-v3-cl:"+deviceId;
    const acknowledged=await mutateCommandQueue(this.ctx.storage,key,async(list,transaction)=>{
      const index=list.findIndex(row=>row.id===id);
      if(index<0)return {rows:list,changed:false,result:await acknowledgePrunedCommand(transaction,key,id,result,status)};
      const old=list[index],parameters={...(old.parameters||{})};
      if(parameters.base64)parameters.base64="[completed payload purged]";
      if(parameters.sourceUrl)parameters.sourceUrl="[temporary URL purged]";
      if(parameters.bearer)parameters.bearer="[private runner credential purged]";
      if(parameters.nativeParameters&&typeof parameters.nativeParameters==="object"){
        parameters.nativeParameters={...parameters.nativeParameters};
        if(parameters.nativeParameters.base64)parameters.nativeParameters.base64="[completed payload purged]";
        if(parameters.nativeParameters.sourceUrl)parameters.nativeParameters.sourceUrl="[temporary URL purged]";
      }
      const updated=await terminalCommandUpdate(old,result,status,parameters);
      list[index]=updated;
      const stored=(await readNativeDeviceInTransaction(transaction,"app-device:"+deviceId))||d;
      stored.lastSeenAt=now();await writeNativeDeviceInTransaction(transaction,"app-device:"+deviceId,stored);
      return {rows:list,result:{...updated,result,resultReplayed:!commandIsPending(old)}};
    });
    return acknowledged;
  }
  async appCommandV3(ownerKey,id){
    const d=await this.appV3Device(ownerKey);
    const list=await readCommandQueue(this.ctx.storage,"app-v3-cl:"+d.deviceId);
    const native=list.find(c=>c.id===id);
    if(native){if(native.resultSha256&&native.resultSha256!==await canonicalCommandResultSha256(native.result))throw new Error("Retained result checksum conflicts with original receipt");return native;}
    const compact=await readCommandReceipt(this.ctx.storage,"app-v3-cl:"+d.deviceId,id);
    if(compact)return compact;
    const fallback=await this.appResolveStudioWebFallback(ownerKey);
    if(!fallback) return null;
    const webDeviceId=fallback.binding.webDeviceId;
    const webCommand=await this.command(webDeviceId,id);
    if(webCommand) return {...webCommand,hybridRoute:"studio_web"};
    const runtime=await this.runtimeCommand(webDeviceId,id);
    return runtime?{...runtime,hybridRoute:"studio_web"}:null;
  }
  async appStatusV3(ownerKey){
    const d=await this.appResolve(ownerKey);
    if(!d) return {connected:false,registered:false,protocolVersion:3,error:"VideoStudio stable MCP credential rejected"};
    const min=Number(d.protocolMin||d.protocolVersion||0);
    const max=Number(d.protocolMax||d.protocolVersion||0);
    const compatible=min<=3&&max>=3;
    if(!compatible){
      return {
        connected:false,
        registered:true,
        protocolVersion:3,
        upgradeRequired:true,
        registeredProtocolVersion:Number(d.protocolVersion||0),
        protocolMin:min,
        protocolMax:max,
        appVersion:d.appVersion||"",
        appGeneration:Number(d.appGeneration||0),
        error:"Installed VideoStudio does not expose the stable MCP compatibility lane v3"
      };
    }
    const list=await readCommandQueue(this.ctx.storage,"app-v3-cl:"+d.deviceId);
    const {ownerHash,...safe}=d;
    const lastSeenMs=Date.parse(String(d.lastSeenAt||""))||0;
    const ageMs=lastSeenMs>0?Math.max(0,Date.now()-lastSeenMs):Number.MAX_SAFE_INTEGER;
    const fresh=ageMs<=45000;
    const pending=list.filter(commandIsPending);
    const waitingNative=pending.filter(c=>c.status==="waiting_native").length;
    const fallback=await this.appResolveStudioWebFallback(ownerKey);
    const fallbackConnected=!!fallback&&!!fallback.fresh;
    return {
      // The private MCP endpoint is a Durable Object control plane and remains
      // reachable even when Android is sleeping. Native execution health is
      // reported separately so clients can keep accepting autonomous work.
      connected:true,
      controlPlaneConnected:true,
      nativeConnected:fresh,
      executionAvailable:fresh||fallbackConnected,
      offlineQueueAvailable:true,
      studioWebFallback:fallback?{
        bound:true,
        connected:fallbackConnected,
        deviceId:fallback.binding.webDeviceId,
        projectId:fallback.project&&fallback.project.id||"",
        lastSeenAgeMs:fallback.ageMs
      }:{bound:false,connected:false},
      nativeState:fresh?"online":"sleeping_or_offline",
      registered:true,
      stale:!fresh,
      lastSeenAgeMs:ageMs===Number.MAX_SAFE_INTEGER?null:ageMs,
      protocolVersion:3,
      protocolMin:min,
      protocolMax:max,
      selectedCompatibilityProtocol:3,
      mcpEndpointVersion:"v3-stable",
      stableMcpEndpoint:true,
      stableMcpPath:"/app-mcp-v3/",
      connectionCoreVersion:Number(d.connectionCoreVersion||0),
      wireSchemaVersion:Number(d.wireSchemaVersion||1),
      featureLevel:Number(d.featureLevel||0),
      compatibilityPolicy:d.compatibilityPolicy||"stable-major-additive-features",
      transportDecoupledFromApkVersion:d.transportDecoupledFromApkVersion!==false,
      appGeneration:Number(d.appGeneration||0),
      nativeAgent:"videostudio-v3",
      device:safe,
      relay:relayRelease(),
      commandBacklog:commandBacklog(list),
      pendingCommands:pending.length,
      waitingNativeCommands:waitingNative,
      canAcceptAutonomousWork:true,
      queuedExecutionPolicy:fallbackConnected?"studio-web-when-compatible-otherwise-native-on-reconnect":"native-on-reconnect",
      lastCommand:nativeCommandSummary(list[list.length-1]||null),
      projectCount:Array.isArray(d.projects)?d.projects.length:0,
      galleryAccess:false,
      directAttachmentIngest:true,
      portraitAnimationEngine:d.portraitAnimationEngine||"",
      onDevicePortraitAi:!!d.onDevicePortraitAi,
      rebind:!fresh ? await this.appCreateRebind(ownerKey) : {available:false}
    };
  }

  async appCreateHandoff(ownerKey,sourceUrl,meta={}){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    if(d.permissionMode==="one_file") throw new Error("Chat-file import is unavailable while One File Lock is active");
    let parsed;
    try{ parsed=new URL(String(sourceUrl||"")); }catch{ throw new Error("Invalid chat attachment URL"); }
    if(parsed.protocol!=="https:") throw new Error("Chat attachment handoff requires HTTPS");
    const host=parsed.hostname.toLowerCase();
    if(["localhost","127.0.0.1","0.0.0.0","::1"].includes(host)||host.endsWith(".internal")) throw new Error("Private-network source URLs are not allowed");
    const id=crypto.randomUUID();
    const record={
      id,
      deviceId:d.deviceId,
      sourceUrl:String(sourceUrl),
      name:clean(meta.name||"ChatGPT import",180),
      mime:clean(meta.mime||"",120),
      size:Number(meta.size||0)||0,
      createdAt:now(),
      expiresAt:Date.now()+20*60*1000
    };
    await this.ctx.storage.put("app-handoff:"+d.deviceId+":"+id,record);
    return {id,name:record.name,mime:record.mime,size:record.size,expiresAt:record.expiresAt};
  }
  async appQueueAttachmentHandoff(ownerKey,file={},projectId=""){
    const sourceUrl=String(file.download_url||"");
    const name=clean(file.file_name||"ChatGPT attachment",180);
    const mime=clean(file.mime_type||"",120);
    const handoff=await this.appCreateHandoff(ownerKey,sourceUrl,{name,mime,size:0});
    const command=await this.appEnqueueV3(ownerKey,"import_chat_file",{
      handoffId:handoff.id,
      name:handoff.name,
      mime:handoff.mime,
      size:handoff.size,
      projectId:clean(projectId||"",120)
    });
    return command;
  }

  async appCreateCachedHandoff(ownerKey,cacheUrl,meta={}){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    if(d.permissionMode==="one_file") throw new Error("Chat-file import is unavailable while One File Lock is active");
    if(!cacheUrl||!String(cacheUrl).startsWith("https://")) throw new Error("Invalid private upload cache URL");
    const id=crypto.randomUUID();
    const record={
      id,
      deviceId:d.deviceId,
      cacheUrl:String(cacheUrl),
      sourceUrl:"",
      name:clean(meta.name||"ChatGPT import",180),
      mime:clean(meta.mime||"",120),
      size:Number(meta.size||0)||0,
      createdAt:now(),
      expiresAt:Date.now()+20*60*1000
    };
    await this.ctx.storage.put("app-handoff:"+d.deviceId+":"+id,record);
    return {id,name:record.name,mime:record.mime,size:record.size,expiresAt:record.expiresAt};
  }
  async appHandoff(deviceId,ownerKey,id){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const key="app-handoff:"+deviceId+":"+id, record=await this.ctx.storage.get(key);
    if(!record) return null;
    if(Number(record.expiresAt||0)<=Date.now()){
      if(record.blobId)await deletePrivateBlob(this.ctx.storage,record.blobId);
      else await this.ctx.storage.delete(key);
      return null;
    }
    return record;
  }
  async appDeleteHandoff(deviceId,ownerKey,id){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const key="app-handoff:"+deviceId+":"+id,record=await this.ctx.storage.get(key);
    if(record?.blobId)await deletePrivateBlob(this.ctx.storage,record.blobId);
    else await this.ctx.storage.delete(key);
  }
}

const state = env => env.VIDEO_STATE.getByName("primary");
const out = x => ({content:[{type:"text",text:JSON.stringify(x)}]});
const isNativeV3 = d => !!d && Number(d.protocolVersion||0)===3;
const enqueueNative = (st,d,ownerKey,action,parameters={}) => isNativeV3(d)
  ? st.appEnqueueV3(ownerKey,action,parameters)
  : st.appEnqueue(ownerKey,action,parameters);
const commandNative = (st,d,ownerKey,id) => isNativeV3(d)
  ? st.appCommandV3(ownerKey,id)
  : st.appCommand(ownerKey,id);
const statusNative = (st,d,ownerKey) => isNativeV3(d)
  ? st.appStatusV3(ownerKey)
  : st.appStatus(ownerKey);

function serverFor(env,hybridKey=""){
  const s=new McpServer({name:"VideoStudio-Studio-Web",version:"1.0.0"}), st=state(env);
  s.registerTool("server_status",{description:"Check VideoStudio Studio Web status and fallback editing capabilities.",inputSchema:{}},async()=>out({
    ok:true,
    service:"VideoStudio Studio Web",
    version:"1.0.0",
    app:"/",
    executionSurface:"browser-local",
    mediaPolicy:"Media blobs stay in browser IndexedDB; only metadata and commands use MCP.",
    galleryAccess:false,
    capabilities:[
      "visual Media Bin thumbnails",
      "thumbnail timeline",
      "instant image/video preview",
      "persistent browser storage request",
      "projects and lightweight metadata sync",
      "12-frame visual analysis",
      "scene-change detection",
      "quiet-section detection",
      "multi-cut timeline editing",
      "per-clip speed volume transforms filters titles and camera motion",
      "creator quick tools",
      "autonomous director goal",
      "render inspection",
      "batched edit commands",
      "adaptive local MP4/WebM rendering",
      "mobile bottom navigation",
      "browser fallback execution when Android app is unavailable",
      "Google Drive drive.file project storage",
      "real browser-local text-to-video procedural rendering",
      "image-to-video depth motion",
      "single-still WebGL localized character motion (not full generative new-pose synthesis)",
      "multi-pose anchor frame synthesis: distinct drawings, sparse optical flow, proper inbetweens",
      "eased pose keyframes, independent articulated regions and secondary cape physics",
      "multi-image story video generation",
      "video-to-video restyling",
      "2D motion graphics generation",
      "procedural 3D video generation",
      "audio visualizer video generation",
      "abstract VFX generation",
      "cinematic perspective portal/world replacement",
      "multi-world sequencing through tracked windows/screens/doorways",
      "foreground-preserving compositing with reflections",
      "optional real SD-Turbo neural keyframe generation through ONNX Runtime WebGPU",
      "neural temporal motion using bidirectional RAFT ONNX optical flow on WebGPU",
      "sequential neural model phases to reduce peak browser memory"
    ]
  }));
  s.registerTool("device_status",{description:"Check a paired VideoStudio app device. Native v3/v1 also accepts the private owner credential as deviceId for compatibility.",inputSchema:{deviceId:z.string().min(8)}},async({deviceId})=>{
    const native=await st.appResolve(deviceId);
    return out(native?await statusNative(st,native,deviceId):await st.status(deviceId));
  });
  s.registerTool("create_video_project",{description:"Create a VideoStudio project on a paired device.",inputSchema:{deviceId:z.string().min(8),name:z.string().min(1).max(120),instruction:z.string().max(5000).optional()}},async({deviceId,name,instruction})=>{
    const native=await st.appResolve(deviceId);
    if(native){
      const c=await enqueueNative(st,native,deviceId,"create_project",{name,instruction:instruction||""});
      return out({queued:true,commandId:c.id,sequence:c.seq,nativeApp:true});
    }
    return out(await st.createProject(deviceId,name,instruction||""));
  });
  s.registerTool("list_video_projects",{description:"List projects and synced local-media metadata.",inputSchema:{deviceId:z.string().min(8)}},async({deviceId})=>{
    const native=await st.appResolve(deviceId);
    if(native) return out({nativeApp:true,projects:native.projects||[]});
    return out(await st.projects(deviceId));
  });
  s.registerTool("get_video_project",{description:"Get project metadata. For the native app this returns the latest registered local summary; full native state is available through get_state/app_state.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}},async({deviceId,projectId})=>{
    const native=await st.appResolve(deviceId);
    if(native){
      const project=(native.projects||[]).find(p=>p&&p.id===projectId);
      return out(project?{nativeApp:true,protocolVersion:isNativeV3(native)?3:1,project}:{error:"Native project not found"});
    }
    return out((await st.project(deviceId,projectId))||{error:"Project not found"});
  });
  s.registerTool("generate_studio_video",{
    description:"Generate a real video inside VideoStudio Studio Web. The browser executes and records the selected generator locally; this tool does not pretend procedural output is neural photoreal synthesis.",
    inputSchema:{
      deviceId:z.string().min(8),
      projectId:z.string().min(8),
      mode:z.enum(["prompt_scene","image_motion","character_action","pose_sequence","story_video","video_restyle","motion_graphics","procedural_3d","audio_visualizer","abstract_vfx"]),
      prompt:z.string().max(2000).optional(),
      style:z.enum(["cinematic","dreamy","neon","film","mono"]).optional(),
      duration:z.number().min(1).max(60).optional(),
      fps:z.number().int().min(12).max(60).optional(),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),
      quality:z.enum(["720p","1080p"]).optional(),
      assetId:z.string().min(8).optional(),
      anchorAssetIds:z.array(z.string().min(8)).min(2).max(12).optional(),
      poseTimes:z.array(z.number().min(0).max(1)).min(2).max(12).optional(),
      motionPreset:z.enum(["duel","portrait","custom"]).optional(),
      intensity:z.number().min(.2).max(1.6).optional(),
      poseTracks:z.array(z.array(z.array(z.number()).min(4).max(5)).min(2).max(16)).min(1).max(8).optional(),
      regions:z.array(z.object({
        x:z.number().min(0).max(1),y:z.number().min(0).max(1),
        rx:z.number().min(.025).max(.5),ry:z.number().min(.025).max(.5),
        dx:z.number().min(-.1).max(.1),dy:z.number().min(-.1).max(.1),
        frequency:z.number().min(.5).max(10).optional(),
        phase:z.number().min(-6.28).max(6.28).optional()
      })).min(1).max(8).optional(),
      seed:z.number().int().optional()
    }
  },async({deviceId,projectId,...parameters})=>{
    try{
      const native=await st.appResolve(deviceId);
      if(native) return out({queued:false,error:"This tool targets Studio Web. Use native app generation tools for an Android Native Agent device."});
      const c=await st.enqueueRuntime(deviceId,projectId,"generate_video",parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web",note:"Keep Studio Web visible while browser-local generation records the video."});
    }catch(e){return out({queued:false,error:e.message});}
  });

  s.registerTool("animate_still_character",{
    description:"Render independently keyframed 2D puppet motion from a specific still image in Studio Web. This is not generative reconstruction of unseen poses. The browser storing the image must be foregrounded.",
    inputSchema:{
      deviceId:z.string().min(8),projectId:z.string().min(8),assetId:z.string().min(8),
      prompt:z.string().max(2000).optional(),
      motionPreset:z.enum(["duel","portrait"]).default("duel"),
      duration:z.number().min(2).max(30).default(6),
      fps:z.number().int().min(12).max(30).default(24),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).default("9:16"),
      quality:z.enum(["720p","1080p"]).default("720p"),
      intensity:z.number().min(.2).max(1.6).default(1),
      poseTracks:z.array(z.array(z.array(z.number()).min(4).max(5)).min(2).max(16)).min(1).max(8).optional()
    }
  },async({deviceId,projectId,...options})=>{
    try{
      const native=await st.appResolve(deviceId);
      if(native)return out({queued:false,error:"Studio Web's explicit still-asset renderer requires a Studio Web device ID."});
      const project=await st.project(deviceId,projectId);
      if(!project)return out({queued:false,error:"Project not found"});
      if(!(project.assets||[]).some(a=>a.id===options.assetId&&a.kind==="image"))
        return out({queued:false,error:"Requested image asset not registered in the specified project; refusing another image."});
      const c=await st.enqueueRuntime(deviceId,projectId,"generate_video",{...options,mode:"character_action"});
      return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web",requestedAssetId:options.assetId,note:"Render executes only while the exact browser with this local image is open. Queued does not mean finished."});
    }catch(error){return out({queued:false,error:error.message});}
  });

  s.registerTool("studio_executor_status",{
    description:"Check whether Studio Web browser has a recent heartbeat and can receive queued render commands. Reports cloud queue vs real foreground executor honestly.",
    inputSchema:{deviceId:z.string().min(8)}
  },async({deviceId})=>{
    try{
      const status=await st.status(deviceId);
      const ageMs=status.device?.lastExecutorHeartbeatAt?Math.max(0,Date.now()-Date.parse(status.device.lastExecutorHeartbeatAt)):null;
      return out({deviceId,registered:!!status.device,heartbeatAgeMs:ageMs,
        recentlyConnected:ageMs!==null&&ageMs<60000,
        browserForegroundConfirmed:ageMs!==null&&ageMs<60000&&status.device.browserVisibility==="visible",
        lastObservedBrowserVisibility:status.device?.browserVisibility||"unknown",
        pendingRuntimeCommands:status.pendingRuntimeCommands,
        executionPolicy:"Browser-local GPU and IndexedDB. MCP queue persists in Worker; final render cannot run with browser closed.",
        actionRequired:ageMs!==null&&ageMs<60000&&status.device.browserVisibility==="visible"?"Browser visible; wait for verified command completion.":"Open the exact browser profile with the imported image and keep tab visible while rendering."});
    }catch(error){return out({error:error.message});}
  });

  s.registerTool("animate_pose_sequence",{
    description:"Animate a real sequence of 2-12 distinct artist-provided pose drawings, interpolating with local sparse flow. This is not new-pose generation from one still. Requires the original browser containing all image assets to be visible.",
    inputSchema:{
      deviceId:z.string().min(8),projectId:z.string().min(8),
      anchorAssetIds:z.array(z.string().min(8)).min(2).max(12),
      poseTimes:z.array(z.number().min(0).max(1)).min(2).max(12).optional(),
      duration:z.number().min(2).max(60).default(6),
      fps:z.number().int().min(12).max(30).default(24),
      quality:z.enum(["720p","1080p"]).default("720p"),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).default("9:16")
    }
  },async({deviceId,projectId,anchorAssetIds,poseTimes,duration,fps,quality,aspect})=>{
    try{
      if(await st.appResolve(deviceId))return out({queued:false,error:"This is Studio Web browser-local pose animation."});
      const p=await st.project(deviceId,projectId);
      if(!p)return out({queued:false,error:"Project not found"});
      if(new Set(anchorAssetIds).size!==anchorAssetIds.length)
        return out({queued:false,error:"Repeated still images cannot provide different poses"});
      if(anchorAssetIds.some(id=>!(p.assets||[]).some(a=>a.id===id&&a.kind==="image")))
        return out({queued:false,error:"Pose anchor is missing from this project's registered images"});
      const c=await st.enqueueRuntime(deviceId,projectId,"generate_video",{
        mode:"pose_sequence",anchorAssetIds,poseTimes,duration,fps,quality,aspect
      });
      return out({queued:true,commandId:c.id,sequence:c.seq,executionSurface:"studio-web",
        requiredOriginalPoseCount:anchorAssetIds.length,
        note:"Browser must remain open. Each anchor must depict a genuinely different pose. The engine synthesizes inbetweens but does not generate missing artwork."});
    }catch(e){return out({queued:false,error:e.message});}
  });

  s.registerTool("probe_studio_neural_gpu",{
    description:"Probe whether the open Studio Web browser has WebGPU and shader-f16 support suitable for the optional real SD-Turbo ONNX keyframe generator.",
    inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}
  },async({deviceId,projectId})=>{
    try{
      const native=await st.appResolve(deviceId);
      if(native)return out({queued:false,error:"This probe targets Studio Web, not the Android Native Agent."});
      const c=await st.enqueueRuntime(deviceId,projectId,"neural_probe",{});
      return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web"});
    }catch(e){return out({queued:false,error:e.message});}
  });

  s.registerTool("generate_neural_world_keyframes",{
    description:"Generate up to nine real neural 512x512 world keyframes locally in Studio Web using SD-Turbo through ONNX Runtime WebGPU. First use downloads a large open model pack; no paid inference API is used. Intended to feed the Cinematic Worlds compositor.",
    inputSchema:{
      deviceId:z.string().min(8),
      projectId:z.string().min(8),
      prompts:z.array(z.string().min(1).max(1200)).min(1).max(9),
      modelBase:z.string().url().optional()
    }
  },async({deviceId,projectId,prompts,modelBase})=>{
    try{
      const native=await st.appResolve(deviceId);
      if(native)return out({queued:false,error:"This neural browser provider targets Studio Web. Use the website device ID."});
      const c=await st.enqueueRuntime(deviceId,projectId,"generate_neural_keyframes",{prompts,...(modelBase?{modelBase}:{})});
      return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web",engine:"sd-turbo-webgpu-onnx",note:"Keep Studio Web visible during model download/inference. Model terms apply."});
    }catch(e){return out({queued:false,error:e.message});}
  });


  s.registerTool("render_neural_temporal_video",{
    description:"Create a real moving world video from 2–9 neural/imported image anchors in Studio Web. VideoStudio runs the RAFT ONNX optical-flow network locally with WebGPU, estimates bidirectional dense motion between anchor frames, flow-warps/interpolates them into continuous motion, records a real video asset, and makes that asset available to Cinematic Worlds. No paid inference API is required.",
    inputSchema:{
      deviceId:z.string().min(8),
      projectId:z.string().min(8),
      anchorAssetIds:z.array(z.string().min(8)).min(2).max(9).optional(),
      prompts:z.array(z.string().min(1).max(1200)).min(2).max(9).optional(),
      duration:z.number().min(2).max(60).optional(),
      fps:z.number().int().min(12).max(30).optional(),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),
      quality:z.enum(["720p","1080p"]).optional(),
      motionStrength:z.number().min(.2).max(1.6).optional(),
      modelBase:z.string().url().optional()
    }
  },async({deviceId,projectId,...parameters})=>{
    try{
      const native=await st.appResolve(deviceId);
      if(native)return out({queued:false,error:"Neural Temporal Motion currently targets Studio Web. Use the website device ID."});
      const c=await st.enqueueRuntime(deviceId,projectId,"generate_temporal_motion",parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web",engine:"raft-onnx-webgpu-flow-mesh-v1",note:"Keep Studio Web visible during local neural motion analysis and video recording. Two or more progressive anchors are required."});
    }catch(e){return out({queued:false,error:e.message});}
  });

  s.registerTool("render_cinematic_world_video",{
    description:"Create the window/portal-world effect shown in cinematic social videos: preserve the real base video and person, perspective-warp one or more generated/imported worlds into a window/screen/doorway quad, sequence worlds over time, retain subtle reflections, and optionally apply translation tracking. Rendering happens locally in Studio Web as a real video.",
    inputSchema:{
      deviceId:z.string().min(8),
      projectId:z.string().min(8),
      baseAssetId:z.string().min(8).optional(),
      worldAssetIds:z.array(z.string().min(8)).max(24).optional(),
      prompt:z.string().max(2000).optional(),
      duration:z.number().min(1).max(180).optional(),
      fps:z.number().int().min(12).max(60).optional(),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),
      quality:z.enum(["720p","1080p"]).optional(),
      tracking:z.enum(["static","translation"]).optional(),
      personOcclusion:z.enum(["auto","off"]).optional(),
      reflection:z.number().min(0).max(.35).optional(),
      lightSpill:z.number().min(0).max(.25).optional(),
      travelMotion:z.number().min(0).max(.45).optional(),
      sceneLabels:z.array(z.string().max(80)).max(24).optional(),
      startQuad:z.array(z.tuple([z.number().min(0).max(1),z.number().min(0).max(1)])).length(4).optional(),
      endQuad:z.array(z.tuple([z.number().min(0).max(1),z.number().min(0).max(1)])).length(4).optional()
    }
  },async({deviceId,projectId,...parameters})=>{
    try{
      const native=await st.appResolve(deviceId);
      if(native) return out({queued:false,error:"This cinematic compositor currently targets Studio Web. Use the website device ID."});
      const c=await st.enqueueRuntime(deviceId,projectId,"render_portal_video",parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web",engine:"studio-web-portal-compositor-v1",note:"Keep Studio Web visible during the real-time local render."});
    }catch(e){return out({queued:false,error:e.message});}
  });


  s.registerTool("deliver_studio_video_to_chat",{
    description:"On explicit owner request, queue one private browser-local video asset for one-time HTTPS transfer to ChatGPT. Only the selected project's video is transferred, never Gallery media. Temporary export automatically expires after 90 minutes. This upload is user-authorized and is not normal background media sync.",
    inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),assetId:z.string().min(8)}
  },async({deviceId,projectId,assetId})=>{
    try{
      if(await st.appResolve(deviceId))return out({queued:false,error:"Studio Web device ID required"});
      const transfer=await st.createTemporaryVideoExport(deviceId,projectId,assetId);
      const command=await st.enqueueRuntime(deviceId,projectId,"temporary_video_export",{assetId,uploadToken:transfer.uploadToken});
      return out({queued:true,commandId:command.id,sequence:command.seq,
        downloadPath:"/api/studio-transfer/download/"+transfer.downloadToken,
        expiresAt:new Date(transfer.expiresAt).toISOString(),assetId,
        note:"The exact selected MP4 will be available at downloadPath after the original browser uploads it. This is an explicit temporary export, not blanket Gallery access. Queued is not completed."});
    }catch(e){return out({queued:false,error:e.message});}
  });
  s.registerTool("studio_drive_status",{description:"Read Studio Web Google Drive storage status. The website uses user-owned Drive through the narrow drive.file OAuth scope; it does not store video files in Cloudflare.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}},async({deviceId,projectId})=>{
    try{const c=await st.enqueueRuntime(deviceId,projectId,"drive_status",{});return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web"});}catch(e){return out({queued:false,error:e.message});}
  });
  s.registerTool("studio_drive_sync",{description:"Sync the current Studio Web project media and manifest to the user's own Google Drive. Drive must already be authorized in the open website.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}},async({deviceId,projectId})=>{
    try{const c=await st.enqueueRuntime(deviceId,projectId,"drive_sync",{});return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web"});}catch(e){return out({queued:false,error:e.message});}
  });
  s.registerTool("studio_drive_restore",{description:"Restore locally missing Studio Web project media from the user's own Google Drive archive.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}},async({deviceId,projectId})=>{
    try{const c=await st.enqueueRuntime(deviceId,projectId,"drive_restore",{});return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web"});}catch(e){return out({queued:false,error:e.message});}
  });
  s.registerTool("studio_drive_offload",{description:"First verify a Studio Web project sync to the user's Drive, then remove local browser media blobs to free browser/device storage. Project metadata and Drive IDs remain.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}},async({deviceId,projectId})=>{
    try{const c=await st.enqueueRuntime(deviceId,projectId,"drive_offload",{});return out({queued:true,commandId:c.id,sequence:c.seq,runtime:"studio-web"});}catch(e){return out({queued:false,error:e.message});}
  });
  s.registerTool("get_studio_runtime_result",{description:"Read the result of a Studio Web generation or Drive runtime command.",inputSchema:{deviceId:z.string().min(8),commandId:z.string().min(8)}},async({deviceId,commandId})=>{
    const c=await st.runtimeCommand(deviceId,commandId);return out(c||{error:"Studio Runtime command not found"});
  });

  s.registerTool("queue_video_edit",{description:"Send one edit action to VideoStudio. Native v3/v1 compatibility can use the private owner credential as deviceId and projectId='active-native'.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),action:z.enum(["set_trim","set_speed","set_mute","set_aspect","set_title","set_quality","set_transition","remove_clip","move_clip","reorder_timeline","replace_timeline","set_clip_speed","set_clip_title","set_clip_effects","analyse_media","inspect_render","render","autonomous_request"]),parameters:z.record(z.string(),z.any()).optional()}},async({deviceId,projectId,action,parameters})=>{
    try{
      const p=parameters||{};
      // Backwards compatible for installed MCP connectors that have not yet
      // refreshed the dedicated deliver_studio_video_to_chat tool schema.
      if(action==="autonomous_request"&&Array.isArray(p.poseSequenceAnchorIds)&&!p.nativeAction){
        if(await st.appResolve(deviceId))return out({queued:false,error:"Pose sequence browser IDs required; native app supports separate animation tools"});
        const ids=p.poseSequenceAnchorIds.map(String);
        if(ids.length<2||ids.length>12||new Set(ids).size!==ids.length)
          return out({queued:false,error:"Pose animation requires 2-12 distinct imported pose images"});
        const project=await st.project(deviceId,projectId);
        if(!project)return out({queued:false,error:"VideoStudio project not found"});
        if(ids.some(id=>!(project.assets||[]).some(a=>a.id===id&&a.kind==="image")))
          return out({queued:false,error:"Pose animation anchor missing from this project's images"});
        const poseTimes=p.poseTimes;
        if(poseTimes!==undefined){
          if(!Array.isArray(poseTimes)||poseTimes.length!==ids.length||poseTimes[0]!==0||poseTimes[poseTimes.length-1]!==1||
             poseTimes.some(v=>!Number.isFinite(Number(v))||Number(v)<0||Number(v)>1)||
             poseTimes.some((v,i)=>i>0&&Number(v)-Number(poseTimes[i-1])<.025))
            return out({queued:false,error:"Pose times must be ordered normalized values beginning at 0 and ending at 1"});
        }
        const command=await st.enqueueRuntime(deviceId,projectId,"generate_video",{
          mode:"pose_sequence",anchorAssetIds:ids,poseTimes,
          duration:Math.max(2,Math.min(60,Number(p.duration||6))),
          fps:Math.max(12,Math.min(30,Number(p.fps||24))),
          aspect:["9:16","16:9","1:1","4:5"].includes(p.aspect)?p.aspect:"9:16",
          quality:p.quality==="1080p"?"1080p":"720p"
        });
        return out({queued:true,commandId:command.id,sequence:command.seq,
          mode:"pose_sequence",poseCount:ids.length,
          note:"Distinct drawn pose interpolation queued for the original foreground browser. No new image poses synthesized."});
      }
      if(action==="autonomous_request"&&typeof p.deliverToChatAssetId==="string"&&!p.nativeAction){
        if(await st.appResolve(deviceId))return out({queued:false,error:"Chat video export requires a Studio Web device ID"});
        const assetId=p.deliverToChatAssetId;
        const transfer=await st.createTemporaryVideoExport(deviceId,projectId,assetId);
        const command=await st.enqueueRuntime(deviceId,projectId,"temporary_video_export",{assetId,uploadToken:transfer.uploadToken});
        return out({queued:true,commandId:command.id,sequence:command.seq,
          downloadPath:"/api/studio-transfer/download/"+transfer.downloadToken,
          expiresAt:new Date(transfer.expiresAt).toISOString(),assetId,
          note:"One explicit browser-local video is queued for an expiring private HTTPS transfer. Browser must be foreground; queued does not mean ready."});
      }
      if(action==="autonomous_request"&&p.nativeAction==="converge_identity"){
        const primary=String(p.primaryOwnerKey||deviceId||"");
        const legacy=Array.isArray(p.legacyOwnerKeys)?p.legacyOwnerKeys:[];
        return out(await st.appConvergeOwnerAliases(primary,legacy,String(p.primaryDeviceId||"")));
      }
      const native=await st.appResolve(deviceId);
      if(native){
        let nativeAction=p.nativeAction||"";
        let nativeParameters=p.nativeParameters||p;
        if(!nativeAction){
          if(action==="autonomous_request"&&p.prompt) nativeAction="prompt_video";
          else if(action==="autonomous_request"&&(Array.isArray(p.clips)||p.preset||p.render)) nativeAction="autonomous_edit";
          else if(action==="set_clip_effects") nativeAction="apply_tool";
          else if(action==="analyse_media") nativeAction="analyse_media";
          else if(action==="render") nativeAction="export_project";
          else if(action==="inspect_render") nativeAction="get_state";
          else if(action==="autonomous_request"&&String(p.instruction||"").toLowerCase().includes("preview")) nativeAction="preview_project";
          else nativeAction="get_state";
        }
        if(nativeAction==="apply_tool"&&!nativeParameters.tool){
          nativeParameters={clipIndex:Number(p.clipIndex||p.index||0),tool:p.tool||"effect",settings:p.settings||p.effects||p};
        }
        if(nativeAction==="import_chat_file"){
          const sourceUrl=nativeParameters.sourceUrl||nativeParameters.url||"";
          const handoff=await st.appCreateHandoff(deviceId,sourceUrl,{name:nativeParameters.name,mime:nativeParameters.mime,size:nativeParameters.size});
          nativeParameters={handoffId:handoff.id,name:handoff.name,mime:handoff.mime,size:handoff.size,projectId:nativeParameters.projectId||""};
        }
        if(isNativeV3(native)&&nativeAction==="import_chat_file"&&nativeParameters.sourceUrl){
          nativeAction="import_attachment";
          nativeParameters={
            sourceUrl:nativeParameters.sourceUrl,
            name:nativeParameters.name||"ChatGPT attachment",
            mime:nativeParameters.mime||"",
            size:Number(nativeParameters.size||0),
            projectId:nativeParameters.projectId||""
          };
        }
        const c=await enqueueNative(st,native,deviceId,nativeAction,nativeParameters);
        return out({queued:true,commandId:c.id,sequence:c.seq,action:nativeAction,nativeApp:true,protocolVersion:isNativeV3(native)?3:1});
      }
      const c=await st.enqueue(deviceId,projectId,action,p);
      return out({queued:true,commandId:c.id,sequence:c.seq,action,note:action==="render"?"Render runs locally. Keep the app open; a browser may require one tap before playback.":"The open app will apply this automatically."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("get_video_command_result",{description:"Get the result of a queued edit, media-analysis, render or native-app compatibility command.",inputSchema:{deviceId:z.string().min(8),commandId:z.string().min(8)}},async({deviceId,commandId})=>{
    const native=await st.appResolve(deviceId);
    if(native){
      const c=await commandNative(st,native,deviceId,commandId);
      if(!c) return out({error:"Command not found"});
      const view={...redactCommand(c),execution:commandExecution(c)};
      const images=nativeImageBlocks(c);
      if(images.length){
        const safeResult=redactedImageResult(c);
        return {content:[
          {type:"text",text:JSON.stringify({...view,result:safeResult})},
          ...images
        ]};
      }
      return out(view);
    }
    const c=await st.command(deviceId,commandId);
    if(!c) return out({error:"Command not found"});
    const images=nativeImageBlocks(c);
    if(images.length){
      const safeResult=redactedImageResult(c);
      const safeCommand={...redactCommand(c),result:safeResult};
      return {content:[
        {type:"text",text:JSON.stringify(safeCommand)},
        ...images
      ]};
    }
    return out(redactCommand(c));
  });
  s.registerTool("queue_video_edit_batch",{description:"Queue an ordered batch of VideoStudio edits. Native v3 compatibility routes the batch into the isolated MCP v3 queue.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),edits:z.array(z.object({action:z.enum(["set_trim","set_speed","set_mute","set_aspect","set_title","set_quality","set_transition","remove_clip","move_clip","reorder_timeline","replace_timeline","set_clip_speed","set_clip_title","set_clip_effects","analyse_media","inspect_render","render","autonomous_request"]),parameters:z.record(z.string(),z.any()).optional()})).min(1).max(20)}},async({deviceId,projectId,edits})=>{
    const queued=[];
    try{
      const native=await st.appResolve(deviceId);
      for(const edit of edits){
        const p=edit.parameters||{};
        if(native){
          let nativeAction=p.nativeAction||"";
          let nativeParameters=p.nativeParameters||p;
          if(!nativeAction){
            if(edit.action==="autonomous_request"&&p.prompt) nativeAction="prompt_video";
            else if(edit.action==="autonomous_request") nativeAction="autonomous_edit";
            else if(edit.action==="set_clip_effects") nativeAction="apply_tool";
            else if(edit.action==="analyse_media") nativeAction="analyse_media";
            else if(edit.action==="render") nativeAction="export_project";
            else nativeAction="get_state";
          }
          const c=await enqueueNative(st,native,deviceId,nativeAction,nativeParameters);
          queued.push({commandId:c.id,sequence:c.seq,action:c.action,protocolVersion:isNativeV3(native)?3:1});
        }else{
          const c=await st.enqueue(deviceId,projectId,edit.action,p);
          queued.push({commandId:c.id,sequence:c.seq,action:c.action,route:c.hybridRoute||"native",waitingNative:c.status==="waiting_native"});
        }
      }
      return out({queued:true,count:queued.length,commands:queued,nativeApp:!!native,protocolVersion:native?(isNativeV3(native)?3:1):0});
    }catch(e){ return out({queued:false,error:e.message,commands:queued}); }
  });
  s.registerTool("request_media_analysis",{description:"Run local visual analysis on VideoStudio media. Native v3/v1.1 returns sampled frames and scene-change candidates without uploading the full video.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),assetId:z.string().min(8).optional(),start:z.number().min(0).optional(),end:z.number().positive().optional(),frames:z.number().int().min(6).max(16).optional(),includeAudio:z.boolean().optional()}},async({deviceId,projectId,assetId,start,end,frames,includeAudio})=>{
    try{
      const parameters={}; if(assetId)parameters.assetId=assetId; if(start!=null)parameters.start=start; if(end!=null)parameters.end=end; if(frames!=null)parameters.frames=frames; if(includeAudio!=null)parameters.includeAudio=includeAudio;
      const native=await st.appResolve(deviceId);
      const c=native?await enqueueNative(st,native,deviceId,"analyse_media",parameters):await st.enqueue(deviceId,projectId,"analyse_media",parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,nativeApp:!!native,note:"VideoStudio will analyse frames locally and return a contact sheet."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("inspect_video_render",{description:"Inspect the actual latest local render with a contact sheet so ChatGPT can critique the finished edit and iterate.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),frames:z.number().int().min(6).max(16).optional()}},async({deviceId,projectId,frames})=>{
    try{ const c=await st.enqueue(deviceId,projectId,"inspect_render",{frames:frames||12}); return out({queued:true,commandId:c.id,sequence:c.seq,note:"The app will sample the latest render locally and return visual frames."}); }
    catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("queue_autonomous_edit",{description:"Send a structured autonomous edit plan. Native v3/v1.1 can apply the timeline, creator preset and optionally export locally.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),instruction:z.string().min(1),clips:z.array(z.record(z.string(),z.any())).min(1).max(80),aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),transition:z.string().optional(),mute:z.boolean().optional(),render:z.boolean().optional(),inspectAfterRender:z.boolean().optional()}},async(args)=>{
    try{
      const {deviceId,projectId,...parameters}=args;
      const native=await st.appResolve(deviceId);
      const c=native?await enqueueNative(st,native,deviceId,"autonomous_edit",parameters):await st.enqueue(deviceId,projectId,"autonomous_request",parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,nativeApp:!!native,note:"Structured edit plan queued for local execution."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("import_chat_file",{description:"Securely stream a ChatGPT conversation attachment into the private native VideoStudio Android app. Pass the native private owner credential as deviceId. Available in Full Autonomous mode.",inputSchema:{deviceId:z.string().min(32),sourceUrl:z.string().url(),name:z.string().min(1).max(180),mime:z.string().max(120).optional(),size:z.number().nonnegative().optional(),projectId:z.string().min(8).optional()}},async({deviceId,sourceUrl,name,mime,size,projectId})=>{
    try{
      const native=await st.appResolve(deviceId);
      if(!native) throw new Error("Native VideoStudio app not connected");
      if(isNativeV3(native)){
        const c=await st.appEnqueueV3(deviceId,"import_attachment",{sourceUrl,name,mime:mime||"",size:Number(size||0),projectId:projectId||""});
        return out({queued:true,commandId:c.id,sequence:c.seq,nativeApp:true,protocolVersion:3,transport:"direct-app-ingest"});
      }
      const handoff=await st.appCreateHandoff(deviceId,sourceUrl,{name,mime,size});
      const c=await st.appEnqueue(deviceId,"import_chat_file",{handoffId:handoff.id,name:handoff.name,mime:handoff.mime,size:handoff.size,projectId:projectId||""});
      return out({queued:true,commandId:c.id,sequence:c.seq,nativeApp:true,protocolVersion:1,handoffId:handoff.id,expiresAt:handoff.expiresAt});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  if(hybridKey){
    s.registerTool("hybrid_status",{
      description:"Read the permanent VideoStudio hybrid connection: Studio Web health, Android Native Agent health, app generation/protocol, pending native work, waiting-native state and privacy boundary.",
      inputSchema:{}
    },async()=>out(await st.appStatusHybrid(hybridKey)));

    s.registerTool("hybrid_execute",{
      description:"Execute any VideoStudio-native action through the permanent hybrid binding. If Android is offline, the command is preserved as waiting_native and becomes claimable when the Native Agent reconnects. Gallery/media-library enumeration remains blocked.",
      inputSchema:{action:z.string().min(1).max(80),parameters:z.record(z.string(),z.any()).optional()}
    },async({action,parameters})=>{
      try{
        const command=await st.appEnqueueHybrid(hybridKey,action,parameters||{});
        return out({queued:true,commandId:command.id,sequence:command.seq,status:command.status,waitingNative:command.status==="waiting_native",action:command.action,nativeApp:true,hybrid:true});
      }catch(e){ return out({queued:false,hybrid:true,error:e.message}); }
    });

    s.registerTool("hybrid_get_command_result",{
      description:"Read one native command queued through the permanent hybrid binding. Native visual-analysis contact sheets are returned as images when available.",
      inputSchema:{commandId:z.string().min(8)}
    },async({commandId})=>{
      try{
        const command=await st.appCommandHybrid(hybridKey,commandId);
        if(!command) return out({error:"Command not found",hybrid:true});
        const view=redactCommand(command);
        if(command.result&&new TextEncoder().encode(JSON.stringify(view.result)).byteLength>48*1024){
          const page=await nativeResultPage(command,view.result,0);page.retrievalTool="hybrid_get_command_result_page";
          return {content:[{type:"text",text:JSON.stringify({...page,hybrid:true})},...nativeImageBlocks(command)]};
        }
        const images=nativeImageBlocks(command);
        if(images.length){
          const safeResult=redactedImageResult(command);
          return {content:[
            {type:"text",text:JSON.stringify({...redactCommand(command),result:safeResult,hybrid:true})},
            ...images
          ]};
        }
        return out({...redactCommand(command),hybrid:true});
      }catch(e){ return out({error:e.message,hybrid:true}); }
    });

    s.registerTool("hybrid_get_command_result_page",{description:"Read a bounded JSON fragment of the complete retained native result through this exact private hybrid binding; concatenate pages and verify viewSha256. No result is silently truncated.",inputSchema:{commandId:z.string().min(8),pageIndex:z.number().int().min(0).max(255).optional()},annotations:{readOnlyHint:true}},async({commandId,pageIndex})=>{
      try{const command=await st.appCommandHybrid(hybridKey,commandId);if(!command)return out({error:"Command not found",hybrid:true});
        const page=await nativeResultPage(command,redactCommand(command).result,pageIndex||0);page.retrievalTool="hybrid_get_command_result_page";return out({...page,hybrid:true});}
      catch(error){return out({error:error.message,hybrid:true});}
    });

    s.registerTool("hybrid_revoke_native_binding",{
      description:"Revoke this permanent Studio Web to Android native-control binding. This does not delete VideoStudio projects or media.",
      inputSchema:{confirm:z.literal(true)}
    },async()=>out(await st.appRevokeHybrid(hybridKey)));
  }

  s.registerTool("video_project_plan",{description:"Create a short autonomous editing workflow.",inputSchema:{projectName:z.string().min(1),instruction:z.string().min(1)}},async({projectName,instruction})=>out({projectName,instruction,status:"planned",workflow:["inspect asset metadata","run 12-frame scene and quiet-section analysis","visually inspect sampled frames","design a multi-cut timeline around real structural changes","apply per-clip pacing/reframing/audio only where justified","render locally","inspect the actual rendered contact sheet","iterate before declaring the edit finished"]}));
  return s;
}

function serverForApp(env,ownerKey,protocolVersion=1){
  const isV3=Number(protocolVersion)===3;
  const s=new McpServer({
    name:isV3?"VideoStudio-App-MCP-v3":"VideoStudio-App-MCP",
    version:isV3?"3.4.7":"1.1.2"
  }), st=state(env);
  const enqueueCommand=(action,parameters={})=>isV3
    ? st.appEnqueueV3(ownerKey,action,parameters)
    : st.appEnqueue(ownerKey,action,parameters);
  const readCommand=commandId=>isV3
    ? st.appCommandV3(ownerKey,commandId)
    : st.appCommand(ownerKey,commandId);
  const readStatus=()=>isV3
    ? st.appStatusV3(ownerKey)
    : st.appStatus(ownerKey);
  const queue=async(action,parameters={})=>{
    try{
      const c=await enqueueCommand(action,parameters);
      return out({
        queued:true,
        commandId:c.id,
        sequence:c.seq,
        action,
        nativeApp:c.hybridRoute!=="studio_web",
        protocolVersion:isV3?3:1,
        route:c.hybridRoute||"native",
        waitingNative:c.status==="waiting_native",
        webProjectId:c.webProjectId||undefined
      });
    }catch(e){ return out({queued:false,error:e.message,protocolVersion:isV3?3:1}); }
  };
  const commandResult=async commandId=>{
    try{
      const c=await readCommand(commandId);
      if(!c) return out({error:"Command not found"});
      const view={...redactCommand(c),execution:commandExecution(c)};
      if(c.resultExpired&&!c.result)return out({command:nativeCommandSummary(c),resultExpired:true,resultRetained:false});
      if(c.result&&new TextEncoder().encode(JSON.stringify(view.result)).byteLength>48*1024){
        const page=await nativeResultPage(c,view.result,0);
        return {content:[{type:"text",text:JSON.stringify(page)},...nativeImageBlocks(c)]};
      }
      const images=nativeImageBlocks(c);
      if(images.length){
        const safeResult=redactedImageResult(c);
        return {content:[
          {type:"text",text:JSON.stringify({...view,result:safeResult})},
          ...images
        ]};
      }
      return out(view);
    }catch(e){ return out({error:e.message}); }
  };

  s.registerTool("app_status",{description:isV3?"Check the VideoStudio v3 Native Agent connection, protocol version, permission mode, projects and pending native work. Gallery access is always false.":"Check the private native VideoStudio Android connection, permission mode, projects, control-pause state and pending work. Gallery access is always false.",inputSchema:{}},async()=>out(await readStatus()));

  s.registerTool("app_capabilities",{description:isV3?"Read VideoStudio v3 Native Agent capabilities and architecture guarantees.":"Read the native v1.1 editing, AI, render and privacy capabilities available to ChatGPT.",inputSchema:{}},async()=>out({
    version:isV3?"3.9.6":"1.1.2",
    relay:relayRelease(),
    protocolVersion:isV3?3:1,
    storyboard:{minimumNativeAppVersion:"3.7.2",defaultMotionIntent:"cinematic_action",frozenSourceHashes:true,explicitOriginalRigAndPropAnnotations:true,explicitContactPlans:true,typedChoreography:CINEMATIC_BEATS,cinematicAcceptance:"requires decoded physical QA; eligibility/pixel checks do not certify",neuralMotionProviderInstalled:false},
    sourceRigWorkbench:isV3?{minimumNativeAppVersion:"3.7.3",originalImageInspection:true,diagnosticPosePreview:true,batchProfileLimit:8,orderedPreparationStatus:true,anatomicalInferenceAvailable:false,cinematicAcceptance:false}:undefined,
    cinematic3d:isV3?{minimumNativeAppVersion:"3.8.0",engine:"Blender/Rigify full-scene GLB",offlineActionLibrary:true,exactOutputFrameClock:true,neuralProviderInstalled:false,cinematicAcceptance:false,nativeExecutionVerified:false}:undefined,
    studioWorkbench:isV3?{minimumNativeAppVersion:"3.9.0",sharedHumanAndMCP:true,sketchGeometryPaging:true,sketchDiagnosticImages:true,preciseClipKeyframes:true,editedPreviewAndExportShared:true,timedImageOverlays:true,openSourceRunner:true,commercialGenerationAPIs:false,driveArchiveReadbackRequired:true,phoneGpuVerified:false}:undefined,
    stableEndpoint:isV3,
    stableEndpointPath:isV3?"/app-mcp-v3/":"",
    primary:"Android native app",
    architecture:isV3?"permanent hybrid control plane; native-first execution with bound Studio Web fallback and durable native queue":"native app with private MCP relay",
    privacy:{galleryAccess:false,boundary:"No MCP tool may list, browse or enumerate Gallery/media-library items. Only user-selected Android picker files, VideoStudio-owned files and explicit ChatGPT attachments are usable."},
    permissions:["everything","one_file"],
    permissionModel:{default:"everything",legacyAlias:"all_tools",note:"Full Autonomous grants every VideoStudio-native action. One File Lock is the only restrictive mode. Gallery enumeration is always blocked."},
    connection:isV3
      ?["always-available stable MCP v3 control plane across APK updates","Android Keystore owner key","device binding","optional Studio Web fallback binding","persistent app-generation fencing","adaptive connection profile negotiation","isolated v3 command queue","waiting_native durable work","leased commands","durable command idempotency journal","persistent foreground Native Agent","self-rearm watchdog","secure reconnect backoff","live ChatGPT activity feed","STOP CHATGPT CONTROL"]
      :["Android Keystore owner key","device binding","persistent foreground control service","leased commands","crash-safe completion checkpoints","secure reconnect backoff","notification pause/cancel controls","live ChatGPT activity feed","STOP CHATGPT CONTROL"],
    media:isV3
      ?["direct ChatGPT attachment ingest to app-private storage","owner-authenticated inline still-frame fallback","VideoStudio-owned media","explicit HTTPS import","manual Android picker","no Gallery enumeration","legacy short-lived relay fallback"]
      :["VideoStudio-owned media","explicit HTTPS import","manual Android picker","private handoff"],
    editing:["trim","split","0.25x-4x speed","slow motion","volume","static titles","scale","rotate","blur","colour/HSL","motion presets","precise transform/opacity keyframes","chroma key","feathered masks","glow","timed owned image overlays"],
    ai:["native visual analysis","scene-change sampling","bundled person segmentation","bundled face mesh","subject-aware image animation","2.5D parallax","autonomous edit plans","creator presets","prompt-to-video","multi-variant planning","short-form recut planning","render/export orchestration"],
    animation:isV3?["AI subject/background layer extraction","feathered head/hair torso and lower-drape layers","face-aware camera anchoring","multi-keyframe easing","head drift/nod","torso breathing","lower-drape sway","independent depth motion","story-shot reordering","procedural atmosphere","layered Media3 composition"]:[],
    export:["Media3 native MP4","H.264","AAC","720p","1080p","9:16","16:9","1:1","4:5","Movies/VideoStudio"],
    stability:isV3
      ?["MCP control plane remains reachable while Android sleeps","browser-capable work can route to bound Studio Web","native-only work waits durably for reconnect","Native Agent task/process self-rearm watchdog","local projects survive signalling outages","bounded light/heavy lanes","one process-wide heavy export at a time","RAM guard","thermal guard","persistent job checkpoints","duplicate-command prevention","cancel single/all jobs"]
      :["persistent background MCP controller","bounded light/heavy job lanes","one process-wide heavy export at a time","RAM guard","thermal guard","persistent job checkpoints","cancel single/all jobs"]
  }));

  s.registerTool("app_catalog",{description:isV3?"List creator effects, motions, transitions, text animations, fonts and AI operations understood by VideoStudio v3.":"List creator effects, motions, transitions, text animations, fonts and AI editing operations understood by VideoStudio v1.1.",inputSchema:{}},async()=>out({
    transitions:["none","cut","fade","dip_black","dip_white","slide_left","slide_right","slide_up","slide_down","push_left","push_right","zoom_in","zoom_out","whip_left","whip_right","spin","blur","flash","glitch","rgb_split","light_leak","film_burn","luma_wipe","mask_wipe","camera_shutter"],
    motions:["none","push_in","pull_out","pan_left","pan_right","pan_up","pan_down","drift","orbit","handheld","micro_shake","impact_shake","bounce","elastic_pop","float","parallax","ken_burns","snap_zoom","zoom_punch","rack_focus_sim","tilt","roll","hero_reveal"],
    effects:["none","cinematic","film_grain","soft_glow","bloom","dream","vignette","sharpen","clarity","motion_blur","radial_blur","gaussian_blur","chromatic_aberration","rgb_split","glitch","scanlines","vhs","retro_cam","super8","film_burn","light_leak","halation","neon","cyberpunk","noir","bleach_bypass","teal_orange","warm_film","cool_night","golden_hour","matte","high_contrast","soft_portrait","crush_black","fade_black","duotone","posterize","pixelate","fisheye","shake","strobe","flash","edge_glow"],
    textAnimations:["none","fade","fade_up","fade_down","slide_left","slide_right","scale_in","pop","bounce","typewriter","word_reveal","line_reveal","blur_in","tracking_in","tracking_out","glitch","neon_flicker","kinetic","mask_reveal","cinematic_title","caption_pop"],
    fonts:["sans-serif","sans-serif-medium","sans-serif-condensed","sans-serif-light","sans-serif-black","serif","serif-monospace","monospace","cursive","casual","elegant","poster","tech","editorial"],
    aiTools:["auto_cut","scene_detect","silence_trim","highlight_extract","smart_reframe","caption_plan","hook_builder","beat_sync","b_roll_plan","pace_rewrite","shorts_recut","story_recut","colour_match","audio_ducking","title_writer","thumbnail_frame_pick","render_critique","prompt_video","animate_images","portrait_parallax","motion_script_compile","motion_script_run","creative_graph_plan","creative_graph_execute","creative_graph_targeted_regeneration","creative_workspace","generated_media_bin","capability_registry","model_pack_install","cloud_workspace_archive","stable_connection_health","stable_connection_reconnect","multi_variant_edit","platform_adapt","continuity_check"]
  }));

  s.registerTool("app_state",{description:"Request full current native app/project state including active asset metadata, jobs, creator capabilities and recent on-device ChatGPT activity.",inputSchema:{}},async()=>queue("get_state",{}));
  if(isV3) {
    for(const [action,inputSchema] of Object.entries(studioToolSchemas)){
      const readOnly=['get_native_delivery_status','get_native_result_page','sketch_list','sketch_get','sketch_inspect','sketch_preview','sketch_paint_journal','sketch_sample_color','sketch_book_list','sketch_book_get','sketch_live_events','sketch_capabilities','sketch_animation_get','sketch_animation_list','open_pipeline_capabilities','open_pipeline_list','open_pipeline_status'].includes(action);
      const detail=action==='sketch_get'?' Geometry is paged by layer/object IDs; pointLimit<=512. Keep returned revision for atomic edits.'
        :action==='sketch_preview'?' Returns the actual shared Canvas frame as an MCP image. Native 3.9.3 adds crop bounds for close inspection of eyes, hair and other details; sourcePixelBounds maps the preview back to exact full-canvas coordinates.'
        :action==='sketch_animation_save'?' Native 4.4.0: freeze prepared sketch artwork and save a validated articulated animation with parent joints, pose timing, two-bone IK contacts, vector weights, camera keys, dialogue and mouth/cel visibility. Use sourceRevision, expectedRevision (0 for new) and a stable operationId. No automatic anatomy or alternate-view inference.'
        :action==='sketch_animation_get'||action==='sketch_animation_list'?' Native 4.4.0: inspect saved prepared-art animation metadata and programs. Full source geometry stays app-owned. Retrieve large results through verified result pages.'
        :action==='sketch_animation_bake'?' Native 4.4.0: compile the saved revision into a NEW editable native sketch. Source preserved; outputSketchId must be unused. Keep operationId stable on retries. Inspect actual sketch_preview frames and render with sketch_render; constraint metrics alone do not establish artistic quality.'
        :action==='sketch_import_drawing_asset'?' Native 4.3.1: reads a hash-bound drawing packet and source already imported as private assets. No manual Drive handoff. Requires source-project revision, both file hashes, and an explicit original_strokes or source_assisted declaration. Full preflight precedes every mark; resume preserves human edits. Valid geometry does not certify likeness.'
        :action==='get_native_result_page'?' Native 4.3.1: retrieves a bounded base64 page of an archived full native result. Concatenate pages, verify resultBytes and resultSha256, then decode the original JSON; terminal status is preserved.'
        :action==='get_native_delivery_status'?' Native 4.3.1: inspect pending legacy result deliveries, their original checksums and reconciliation state in bounded pages. Does not report transport publication as native execution.'
        :action==='compact_native_delivery'?' Native 4.3.1: explicitly prepare a smaller legacy terminal receipt after matching its original result checksum. Full result and original wire receipt remain private on the phone. Local preparation does not mean the server acknowledged it; verify delivery status.'
        :action==='sketch_import_drawing_manifest'||action==='sketch_import_frame_manifest'?' Reads a hash-bound JSONL drawing packet and original source from the app’s already linked folder, without placing geometry in the relay. Full preflight precedes any drawing; replay preserves human edits. Read the returned native job through completion. Valid objects do not certify visual likeness.'
        :action==='sketch_capabilities'?' Read this before drawing. Original portraits, landscapes and animation may start from a text prompt and an empty canvas; reference images are optional.'
        :action==='sketch_live_begin'?' Call before the first stroke. The open phone canvas presents every accepted drawing operation and stroke progress; it preserves rapid batches rather than skipping intermediate steps.'
        :action==='sketch_live_end'?' Finish a drawing page using its final revision. This closes its ordered live stream so the phone can show the next page without dropping drawings.'
        :action==='sketch_edit'?' Native 4.1.0 adds add_paint_cel and paint_stroke with real pressure, flow, hardness, grain, flat tips, alpha lock and owned-reference clone mapping. Keep strokeId stable and use expectedRevision. Start sketch_live_begin before drawing to show actual ordered brush prefixes. Prompt-only original drawings also use these controls. Reference tracing does not recover the original artist stroke order.'
        :action==='sketch_paint_journal'?' Native 4.1.0: read revision-bound, newest-first stroke records in pages of at most 128. Pixels and source pins remain app-owned.'
        :action==='sketch_sample_color'?' Native 4.1.0: sample the selected paint cel, owned hash-bound reference or current composite. Paint x/y are object-local; reference/composite x/y are full-canvas normalized.'
        :action==='sketch_archive'?' Native 4.1.0: archive a revision-bound editable sketch and its verified paint/reference dependencies to the app-linked Drive Results folder. Completion requires actual upload evidence.'
        :action==='sketch_restore_archive'?' Native 4.1.0: restore a hash-bound scoped Drive archive as a new sketch. Keep operationId stable to avoid duplicate restores; existing projects are preserved.'
        :action==='sketch_book_create'?' Create a lazy animation book with up to 10000 pages, without loading thousands of drawings into phone RAM.'
        :action==='sketch_book_render'?' Render 1..1200 consecutive book pages through the native Canvas and Media3 pipeline; originals are preserved.'
        :action.startsWith('open_pipeline_')?' Uses your own configured open-source runner or a hash-verified Drive artifact exchange. Submission is separate from inference completion. No commercial generation API.'
        :action==='precision_edit'?' Atomic stable-clip edits require the exact project revision. Flat keys, opacity, mask/chroma/glow share actual native preview/export.'
        :' Touch controls use the same native stored operations, revision checks and renderer.';
      s.registerTool('app_'+action,{description:'VideoStudio authoring: '+action.replaceAll('_',' ')+'. New features require native3.9.0+.'+detail,inputSchema,annotations:{readOnlyHint:readOnly}},async args=>queue(action,args));
    }
    for(const [action,inputSchema] of Object.entries(cinematic3dToolSchemas)) {
      const readOnly=["cinematic_3d_capabilities","cinematic_3d_get_program","cinematic_3d_list_programs","cinematic_3d_plan","cinematic_3d_export_request"].includes(action);
      s.registerTool("app_"+action,{description:"Cinematic 3D: "+action.replace("cinematic_3d_","").replaceAll("_"," ")+". Requires native 3.8.0+. Uses owned full-scene GLB data, original source hashes and exact 24/30/60 FPS output timing. Procedural mannequins are explicitly identified; installed neural providers and native render acceptance require separate evidence.",inputSchema,annotations:{readOnlyHint:readOnly}},async args=>queue(action,args));
    }
    const stableId=z.string().regex(/^[A-Za-z0-9_-]{8,128}$/), hash=z.string().regex(/^[a-f0-9]{64}$/);
    const xy=z.tuple([z.number().min(0).max(1),z.number().min(0).max(1)]);
    const crop=z.object({left:z.number().min(0).max(1),top:z.number().min(0).max(1),right:z.number().min(0).max(1),bottom:z.number().min(0).max(1)}).strict().refine(value=>value.left<value.right&&value.top<value.bottom,{message:"Invalid original-oriented crop"});
    const batchProfile=z.object({sourceAssetId:stableId,rigId:stableId.optional(),black:z.boolean(),transparentSource:z.boolean().optional(),
      joints:z.array(xy).length(16),outline:z.array(xy).min(3).max(64),swordTip:xy.optional(),swordHilt:xy.optional(),swordOutline:z.array(xy).min(3).max(64).optional(),
      sourceSha256:hash,expectedRigSha256:hash.optional()}).strict();
    const authoringNote=" Requires native VideoStudio 3.7.3+. Authoring and pose diagnostics do not certify anatomy, motion synthesis or cinematic acceptance.";
    s.registerTool("app_inspect_source_artwork",{description:"Inspect one exact owned original image, optionally cropped in EXIF-oriented normalized coordinates. The native result returns an MCP image, original byte SHA, dimensions and a reversible preview coordinate mapping. Use that image to author joints and body/weapon polygons; no arbitrary URI or Gallery access."+authoringNote,inputSchema:{projectId:stableId,assetId:stableId,expectedSourceSha256:hash.optional(),crop:crop.optional(),maxDimension:z.number().int().min(128).max(1024).optional(),rigIds:z.array(stableId).max(8).optional()},annotations:{readOnlyHint:true}},async args=>queue("inspect_source_artwork",args));
    s.registerTool("app_preview_source_rig",{description:"Render a saved source-bound actor and original sword on a diagnostic stage. Optional targetJoints16 changes only this preview; targetJoints is a compatibility alias. Inspect actual source material deformation before saving animation. Rest/profile/source bytes remain unchanged."+authoringNote,inputSchema:{projectId:stableId,rigId:stableId,expectedRigSha256:hash.optional(),expectedSourceSha256:hash.optional(),targetJoints16:z.array(xy).length(16).optional(),targetJoints:z.array(xy).length(16).optional(),swordTip:xy.optional(),maxDimension:z.number().int().min(128).max(720).optional()},annotations:{readOnlyHint:true}},async args=>queue("preview_source_rig",args));
    s.registerTool("app_save_source_rig_profiles",{description:"Save 1..8 original-byte-bound actor definitions with source revision and stable batch identity. Prevalidates the complete request; individual profiles publish atomically. Explicit partial receipts are retained after interruption; retry the same batch unchanged or inspect status. This is not an all-or-nothing transaction."+authoringNote,inputSchema:{projectId:stableId,sourceProjectUpdatedAt:z.number().int().nonnegative(),batchId:stableId,profiles:z.array(batchProfile).min(1).max(8)},annotations:{idempotentHint:true}},async args=>queue("save_source_rig_profiles",args));
    s.registerTool("app_source_rig_status",{description:"Read preparation status for exact ordered asset occurrences, a saved program, or project import order. Reports missing/ambiguous black and white actor profiles without implying verified anatomy or render readiness. Structural status does not rehash source bytes."+authoringNote,inputSchema:{projectId:stableId,assetIds:z.array(stableId).min(1).max(160).optional(),programId:stableId.optional(),expectedProgramSha256:hash.optional()},annotations:{readOnlyHint:true}},async args=>queue("source_rig_status",args));
    const contact=z.object({foot:z.enum(["leadingFoot","trailingFoot"]),start:z.number().min(0).max(1),end:z.number().min(0).max(1),anchor:xy}).strict();
    const contacts=z.object({black:z.array(contact).max(32),white:z.array(contact).max(32)}).strict();
    const binding=z.object({assetId:stableId,blackRigId:stableId,whiteRigId:stableId,blackRigSha256:hash.optional(),whiteRigSha256:hash.optional()}).strict();
    const contactRow=z.object({shotIndex:z.number().int().min(0).max(158),fromAssetId:stableId,toAssetId:stableId,
      sourceWidth:z.number().int().min(32).max(8192).optional(),sourceHeight:z.number().int().min(32).max(8192).optional(),contactPlan:contacts}).strict();
    const beatRow=z.object({shotIndex:z.number().int().min(0).max(158),fromAssetId:stableId,toAssetId:stableId,beat:z.enum(CINEMATIC_BEATS)}).strict();
    const series={sourceProjectId:stableId,assetIds:z.array(stableId).min(2).max(160).optional(),
      anchorGroups:z.array(z.object({representativeAssetId:stableId,assetIds:z.array(stableId).min(1).max(160)}).strict()).min(2).max(160).optional(),
      programId:stableId.optional(),expectedProgramSha256:hash.optional(),
      motionIntent:z.enum(["cinematic_action","source_deformation","environmental"]).optional(),
      backend:z.enum(["AUTO_LAYERS","SOURCE_RIG","MANUAL_MASKS","CAMERA_PARALLAX","PERSON_SEGMENTATION","NEURAL_IF_AVAILABLE"]).optional(),
      genre:z.string().max(80).optional(),direction:z.string().max(80).optional(),fps:z.union([z.literal(24),z.literal(30),z.literal(60)]).optional(),
      batchIndex:z.number().int().min(-1).max(158).optional(),framesPerPair:z.number().int().min(4).max(24).optional(),
      maxFramesPerBatch:z.number().int().min(2).max(20).optional(),maxGeneratedFrames:z.number().int().min(24).max(480).optional(),
      sourceRigBindings:z.array(binding).max(160).optional(),contactPlans:z.array(contactRow).max(159).optional(),choreography:z.array(beatRow).max(159).optional(),
      width:z.number().int().min(256).max(1280).optional(),height:z.number().int().min(256).max(1280).optional(),
      durationSeconds:z.number().positive().max(12.5).optional(),cameraEnabled:z.boolean().optional(),foley:z.boolean().optional(),foleyGain:z.number().min(.05).max(1).optional(),
      render:z.boolean().optional(),quality:z.enum(["720p","1080p"]).optional(),fileName:z.string().max(180).optional()};
    const note=" Requires native VideoStudio 3.7.2+. Default cinematic_action needs original-byte-bound black/white actor and weapon annotations at both anchors, exact ordered contacts and one of twelve explicit choreography beats. Queue acknowledgement does not certify rendered motion or cinematic acceptance.";
    s.registerTool("app_storyboard_series_plan",{description:"Plan exact owned source-frame order and report missing motion annotations without creating an output or render job."+note,inputSchema:series,annotations:{readOnlyHint:true}},async args=>queue("storyboard_series_plan",args));
    s.registerTool("app_storyboard_series_audit",{description:"Admit source-bound annotations before queueing an on-device pixel/scene audit. Missing authentic motion providers reject before a heavy job."+note,inputSchema:series},async args=>queue("storyboard_series_audit",args));
    s.registerTool("app_animate_storyboard_series",{description:"Create a fresh separate native source-artwork animation from frozen image hashes, exact rig/contact bindings and typed choreography. Explicit source_deformation/environmental modes have limited scope; no neural hidden-limb provider is installed."+note,inputSchema:series},async args=>queue("animate_storyboard_series",args));
    s.registerTool("app_storyboard_artwork_preflight",{description:"Read source annotations and exact image hashes for authoring; no output or heavy job. Provider eligibility is separate from cinematic QA. Requires native 3.7.2+.",inputSchema:series,annotations:{readOnlyHint:true}},async args=>queue("storyboard_artwork_preflight",args));
    s.registerTool("app_save_source_rig_profile",{description:"Save authentic original-image actor geometry with a source revision and optional profile CAS digest. SourceSha256 must identify the displayed source bytes for cinematic eligibility. Joint array order: pelvis,chest,neck,head,swordShoulder,swordElbow,swordHand,freeShoulder,freeElbow,freeHand,leadingHip,leadingKnee,leadingFoot,trailingHip,trailingKnee,trailingFoot. No inference, Gallery, URI or substitute sword.",inputSchema:{projectId:stableId,sourceAssetId:stableId,sourceProjectUpdatedAt:z.number().int().nonnegative(),rigId:stableId.optional(),black:z.boolean(),transparentSource:z.boolean().optional(),joints:z.array(xy).length(16),outline:z.array(xy).min(3).max(64),swordTip:xy.optional(),swordHilt:xy.optional(),swordOutline:z.array(xy).min(3).max(64).optional(),sourceSha256:hash.optional(),expectedRigSha256:hash.optional()}},async args=>queue("save_source_rig_profile",args));
    s.registerTool("app_list_source_rig_profiles",{description:"Read exact source actor/weapon definitions and SHA digests from one owned project. Requires native 3.7.2+.",inputSchema:{projectId:stableId,offset:z.number().int().min(0).max(100).optional(),limit:z.number().int().min(1).max(8).optional()},annotations:{readOnlyHint:true}},async args=>queue("list_source_rig_profiles",args));
    const program=z.object({programId:stableId.optional(),name:z.string().max(120).optional(),sourceAssetIds:z.array(stableId).min(2).max(160),
      sourceRigBindings:z.record(z.string(),z.object({blackRigId:stableId,whiteRigId:stableId,blackRigSha256:hash,whiteRigSha256:hash}).strict()),
      contactPlans:z.array(contactRow).min(1).max(159),choreography:z.array(beatRow).min(1).max(159)}).strict();
    s.registerTool("app_storyboard_program_save",{description:"Persist one ordered source/contact/choreography program using source revision and program SHA guards; the phone editor uses the same program store. This does not create a film or certify QA. Requires native 3.7.2+.",inputSchema:{projectId:stableId,sourceProjectUpdatedAt:z.number().int().nonnegative(),expectedProgramSha256:hash.optional(),program}},async args=>queue("save_storyboard_program",args));
    s.registerTool("app_storyboard_program_get",{description:"Read and revalidate one exact saved native storyboard program, including ordered beats, contact intervals and original image/profile hashes. Explicit editOnly:true reads stale draft metadata with renderReady:false; default is strict byte/profile revalidation. Requires native 3.7.2+.",inputSchema:{projectId:stableId,programId:stableId,editOnly:z.boolean().optional()},annotations:{readOnlyHint:true}},async args=>queue("get_storyboard_program",args));
  }
  if(isV3) s.registerTool("app_generate_image",{description:"Generate an original local procedural image from a sceneGraph or supported scene prompt. Not photorealistic diffusion. Registers output in the app Media Bin.",inputSchema:{prompt:z.string().max(10000).optional(),sceneGraph:z.record(z.string(),z.any()).optional(),projectId:z.string().min(8).optional(),width:z.number().int().min(128).max(1920).optional(),height:z.number().int().min(128).max(1920).optional(),appendToTimeline:z.boolean().optional()}},async args=>queue("generate_image",args));
  if(isV3) s.registerTool("app_self_test",{description:"Run VideoStudio v3's on-device native self-test before autonomous work. Verifies protocol v3, app-private storage, local project state, job/render/analysis engines, direct attachment ingest and the no-Gallery boundary.",inputSchema:{}},async()=>queue("self_test",{}));
  s.registerTool("app_activity_note",{description:"Post a live progress message into VideoStudio's ChatGPT Activity screen. Use this to mirror autonomous-work updates such as planning, analysing, applying edits, rendering or retrying.",inputSchema:{title:z.string().min(1).max(120),message:z.string().min(1).max(500),status:z.enum(["info","queued","running","success","failed"]).optional(),progress:z.number().int().min(0).max(100).optional(),projectId:z.string().min(8).optional()}},async args=>queue("activity_note",args));
  s.registerTool("app_create_project",{description:"Create a native VideoStudio project.",inputSchema:{name:z.string().min(1).max(120)}},async({name})=>queue("create_project",{name}));
  s.registerTool("app_select_project",{description:"Select an existing native VideoStudio project by ID.",inputSchema:{projectId:z.string().min(8)}},async({projectId})=>queue("select_project",{projectId}));
  s.registerTool("app_delete_project",{description:"Delete a VideoStudio-owned project. Available in Full Autonomous mode; Gallery enumeration remains blocked.",inputSchema:{projectId:z.string().min(8)}},async({projectId})=>queue("delete_project",{projectId}));

  s.registerTool("app_analyse_media",{description:"Sample a local imported video on-device and return a contact sheet plus scene-change candidates to ChatGPT. Full video stays on the phone.",inputSchema:{assetId:z.string().min(8).optional(),frames:z.number().int().min(6).max(16).optional(),start:z.number().min(0).optional(),end:z.number().positive().optional()}},async({assetId,frames,start,end})=>{
    const p={frames:frames||12}; if(assetId)p.assetId=assetId; if(start!=null)p.start=start; if(end!=null)p.end=end;
    return queue("analyse_media",p);
  });

  s.registerTool("app_apply_edit_plan",{description:"Replace a native timeline with imported local asset IDs. Native 4.5.12 preserves explicit clipId/effects and rejects invalid plans atomically. Supply projectId and expectedUpdatedAt from current native state to protect manual edits.",inputSchema:{projectId:z.string().min(1).max(96).optional(),expectedUpdatedAt:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),clips:z.array(z.record(z.string(),z.any())).min(1).max(80)}},async args=>queue("apply_edit_plan",args));

  s.registerTool("app_apply_tool",{description:"Apply a native rendered edit to a named stable clip: trim, speed, slow_motion, motion, effect, color, blur, transform, keyframes, green_screen, mask, glow, title or volume. Prefer projectId, clipId and expectedUpdatedAt for collaboration; app_precision_edit applies strict atomic batches. Metadata-only unsupported effects fail clearly.",inputSchema:{projectId:z.string().min(1).max(96).optional(),clipId:z.string().min(1).max(128).optional(),expectedUpdatedAt:z.number().int().nonnegative().optional(),clipIndex:z.number().int().min(0).optional(),tool:z.string().min(1).max(80),settings:z.record(z.string(),z.any()).optional()}},async args=>queue("apply_tool",{...args,settings:args.settings||{}}));

  s.registerTool("app_creator_preset",{description:"Apply a creator look plus optional motion, transition and font to one clip or the full active timeline.",inputSchema:{preset:z.string().min(1).max(80),motion:z.string().max(80).optional(),transition:z.string().max(80).optional(),font:z.string().max(80).optional(),allClips:z.boolean().optional(),clipIndex:z.number().int().min(0).optional()}},async args=>queue("creator_preset",args));

  s.registerTool("app_autonomous_edit",{description:"Execute a structured autonomous native edit, optionally followed by a creator preset and export. Native 4.5.12: supply projectId and expectedUpdatedAt for revision-bound timeline replacement with preserved clipId/effects.",inputSchema:{projectId:z.string().min(1).max(96).optional(),expectedUpdatedAt:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),instruction:z.string().max(5000).optional(),clips:z.array(z.record(z.string(),z.any())).max(80).optional(),preset:z.string().max(80).optional(),motion:z.string().max(80).optional(),transition:z.string().max(80).optional(),font:z.string().max(80).optional(),aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),render:z.boolean().optional(),fileName:z.string().max(180).optional()}},async args=>queue("autonomous_edit",args));

  s.registerTool("app_create_prompt_video",{description:"Create and export a real local MP4 from a prompt. ChatGPT can provide a detailed scene plan with original titles, text, motion, transitions, effects and font choices; VideoStudio generates the scene visuals locally and renders them with its native engine.",inputSchema:{prompt:z.string().min(1).max(10000),durationSeconds:z.number().int().min(4).max(120).optional(),aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),style:z.string().max(100).optional(),font:z.string().max(80).optional(),scenes:z.array(z.record(z.string(),z.any())).max(20).optional()}},async args=>queue("prompt_video",args));

  if(isV3) s.registerTool("app_compile_scene",{
    description:"Compile safe VideoStudio MotionScript into serializable CreativeIR and persist both in the app-private creative workspace. This is a creative scene language, not arbitrary shell/code execution.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      script:z.string().min(1).max(20000)
    }
  },async({projectId,script})=>queue("compile_scene",{projectId:projectId||"",script}));

  if(isV3) s.registerTool("app_run_motion_script",{
    description:"Compile and apply MotionScript to the active native timeline, persisting CreativeIR and optionally launching a protected VideoStudio render.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      script:z.string().min(1).max(20000),
      render:z.boolean().optional(),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),
      quality:z.enum(["720p","1080p"]).optional(),
      fileName:z.string().max(180).optional(),
      strictProviders:z.boolean().optional()
    }
  },async args=>queue("run_motion_script",args));

  if(isV3) s.registerTool("app_plan_creative_graph",{
    description:"Compile or inspect a VideoStudio CreativeIR plan as a provider-agnostic execution DAG. The result exposes node dependencies, provider resolution, cache/checkpoint keys, compute plans and unresolved capabilities before expensive execution.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      script:z.string().min(1).max(20000).optional(),
      ir:z.record(z.string(),z.any()).optional()
    }
  },async args=>queue("plan_creative_graph",args));

  if(isV3) s.registerTool("app_run_creative_graph",{
    description:"Execute the prepared CreativeIR DAG inside VideoStudio using only installed/resolved providers. Built-in portrait analysis, layered depth/rigging, native composition, Media3 rendering and local technical critique run on-device; unresolved capabilities remain explicit rather than being faked.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      script:z.string().min(1).max(20000).optional(),
      render:z.boolean().optional(),
      critique:z.boolean().optional(),
      strictProviders:z.boolean().optional(),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),
      quality:z.enum(["720p","1080p"]).optional(),
      fileName:z.string().max(180).optional()
    }
  },async args=>queue("run_creative_graph",args));

  if(isV3) s.registerTool("app_creative_graph_status",{
    description:"Read durable CreativeIR node checkpoints for a VideoStudio project, including currently dependency-ready nodes.",
    inputSchema:{projectId:z.string().min(8).optional()}
  },async({projectId})=>queue("creative_graph_status",{projectId:projectId||""}));

  if(isV3) s.registerTool("app_invalidate_creative_node",{
    description:"Invalidate one CreativeIR execution node and optionally every downstream dependent node so VideoStudio can regenerate only the affected part of a scene.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      nodeId:z.string().min(1).max(160),
      downstream:z.boolean().optional()
    }
  },async({projectId,nodeId,downstream})=>queue("invalidate_creative_node",{projectId:projectId||"",nodeId,downstream:downstream!==false}));

  if(isV3) s.registerTool("app_workspace_status",{
    description:"Read VideoStudio's app-private creative workspace usage for models, caches, generated artifacts and the active project without browsing Gallery.",
    inputSchema:{projectId:z.string().min(8).optional()}
  },async({projectId})=>queue("workspace_status",{projectId:projectId||""}));

  if(isV3) s.registerTool("app_cleanup_workspace",{
    description:"Delete only regenerable VideoStudio creative workspace caches for one project or global caches while preserving project state, final exports and installed model packs.",
    inputSchema:{projectId:z.string().min(8).optional()}
  },async({projectId})=>queue("cleanup_workspace",{projectId:projectId||""}));

  if(isV3) s.registerTool("app_insert_asset_timeline",{
    description:"Insert an existing VideoStudio project media-bin asset into the active timeline without browsing Gallery.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      assetId:z.string().min(8)
    }
  },async({projectId,assetId})=>queue("insert_asset_timeline",{projectId:projectId||"",assetId}));

  if(isV3) s.registerTool("app_capability_registry",{
    description:"Read VideoStudio's capability-first provider registry, including built-in engines and optional installed model providers.",
    inputSchema:{}
  },async()=>queue("capability_registry",{}));

  if(isV3) s.registerTool("app_resolve_capability",{
    description:"Ask VideoStudio to resolve the best currently installed provider for one creative capability such as depth, pose, rendering, scene compilation or future generative modules.",
    inputSchema:{
      capability:z.string().min(1).max(120),
      quality:z.string().max(40).optional()
    }
  },async({capability,quality})=>queue("resolve_capability",{capability,quality:quality||""}));

  if(isV3) s.registerTool("app_model_pack_status",{
    description:"Read optional local VideoStudio model-pack status and installed provider manifests. This does not browse Gallery or install anything.",
    inputSchema:{}
  },async()=>queue("model_pack_status",{}));

  if(isV3) s.registerTool("app_install_model_pack",{
    description:"Install a VideoStudio optional model/capability pack transactionally from an explicitly imported project asset. The pack is staged, checksum-checked when supplied, zip-path guarded, manifest-validated and atomically activated in app-private storage. Gallery is never browsed.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      assetId:z.string().min(8),
      sha256:z.string().regex(/^[a-fA-F0-9]{64}$/).optional()
    }
  },async({projectId,assetId,sha256})=>queue("install_model_pack",{projectId:projectId||"",assetId,sha256:sha256||""}));

  if(isV3) s.registerTool("app_uninstall_model_pack",{
    description:"Remove one optional VideoStudio model pack by its manifest id without touching project media, exports or Gallery.",
    inputSchema:{id:z.string().min(3).max(120)}
  },async({id})=>queue("uninstall_model_pack",{id}));

  if(isV3) s.registerTool("app_compute_profile",{
    description:"Read VideoStudio's current Android compute profile and bounded working-set recommendations for local AI/render workloads.",
    inputSchema:{}
  },async()=>queue("compute_profile",{}));

  if(isV3) s.registerTool("app_plan_compute",{
    description:"Plan a bounded local inference working set using the phone's current RAM, storage and thermal state. Returns model swapping, tile, overlap, temporal-window and checkpoint recommendations.",
    inputSchema:{
      estimatedModelMb:z.number().int().min(0).max(1048576).optional(),
      width:z.number().int().min(1).max(16384).optional(),
      height:z.number().int().min(1).max(16384).optional(),
      quality:z.enum(["draft","balanced","final"]).optional()
    }
  },async args=>queue("plan_compute",args));

  if(isV3) s.registerTool("app_drive_workspace_status",{
    description:"Read VideoStudio's folder-scoped cloud workspace status. This uses a single Android document-tree capability selected by the user and does not request broad Google Drive access or browse Gallery.",
    inputSchema:{}
  },async()=>queue("drive_workspace_status",{}));

  if(isV3) s.registerTool("app_drive_workspace_inventory",{
    description:"Scan only the folder-scoped VideoStudio cloud workspace selected by the user and report visible Projects/ModelPacks counts and bytes. It never enumerates unrelated Drive content or Gallery.",
    inputSchema:{}
  },async()=>queue("drive_workspace_inventory",{}));

  if(isV3) s.registerTool("app_sync_project_to_drive",{
    description:"Archive one VideoStudio project's Creative Runtime workspace and project manifest to the linked folder-scoped cloud workspace. The Android app performs the transfer natively and only within the user-selected folder.",
    inputSchema:{
      projectId:z.string().min(8).optional()
    }
  },async({projectId})=>queue("sync_project_to_drive",{projectId:projectId||""}));

  if(isV3) s.registerTool("app_offload_project_to_drive",{
    description:"Archive the active VideoStudio Creative Runtime workspace to the linked folder-scoped cloud tier, verify the archive, then evict only cloud-backed intermediates locally. Project SQLite state, MotionScript/checkpoints, source media and final exports stay local. Rendering can rehydrate evicted layers automatically.",
    inputSchema:{projectId:z.string().min(8).optional()}
  },async({projectId})=>queue("offload_project_to_drive",{projectId:projectId||""}));

  if(isV3) s.registerTool("app_restore_project_from_drive",{
    description:"Restore one project's Creative Runtime workspace from the linked folder-scoped cloud archive into VideoStudio app-private storage. SQLite project state and Gallery are not overwritten or browsed.",
    inputSchema:{projectId:z.string().min(8).optional()}
  },async({projectId})=>queue("restore_project_from_drive",{projectId:projectId||""}));

  if(isV3) s.registerTool("app_archive_model_pack_to_drive",{
    description:"Archive one already-installed VideoStudio model pack to the linked folder-scoped cloud workspace so large packs can live in cold storage without broad Drive permission.",
    inputSchema:{id:z.string().min(3).max(120)}
  },async({id})=>queue("archive_model_pack_to_drive",{id}));

  if(isV3) s.registerTool("app_restore_model_pack_from_drive",{
    description:"Restore a previously archived VideoStudio model pack from the linked folder-scoped cloud workspace into protected staging, revalidate its manifest/checksums, and atomically activate it locally.",
    inputSchema:{id:z.string().min(3).max(120)}
  },async({id})=>queue("restore_model_pack_from_drive",{id}));

  if(isV3) s.registerTool("app_animate_images",{
    description:"Turn imported still images into a real native animated video. VideoStudio runs bundled on-device person segmentation and face-aware analysis, builds foreground/background layers, directs varied cinematic keyframes and 2.5D parallax, optionally reorders shots for story rhythm, then renders a local MP4 through Media3.",
    inputSchema:{
      projectId:z.string().min(8).optional(),
      style:z.enum(["cinematic","dreamy","dramatic","epic","warm","romantic"]).optional(),
      environment:z.string().max(80).optional(),
      intensity:z.number().min(.15).max(1).optional(),
      durationSecondsPerImage:z.number().min(1.8).max(8).optional(),
      reorderForStory:z.boolean().optional(),
      render:z.boolean().optional(),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),
      quality:z.enum(["720p","1080p"]).optional(),
      fileName:z.string().max(180).optional()
    }
  },async args=>queue("animate_images",args));

  if(isV3) s.registerTool("app_job_status",{
    description:"Read one native background job's current state and progress, including long image-animation, analysis and render jobs.",
    inputSchema:{jobId:z.string().min(8)}
  },async({jobId})=>queue("job_status",{jobId}));

  s.registerTool("app_export_project",{description:"Render the active timeline to a native MP4 and publish it to Movies/VideoStudio.",inputSchema:{aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),fileName:z.string().max(180).optional()}},async({aspect,quality,fileName})=>queue("export_project",{aspect:aspect||"9:16",quality:quality||"1080p",fileName:fileName||("VideoStudio_"+Date.now()+".mp4")}));

  s.registerTool("app_preview_project",{description:"Preview the active timeline locally on the Android device.",inputSchema:{}},async()=>queue("preview_project",{}));

  s.registerTool("app_import_from_url",{description:"Import an explicit HTTPS media URL into VideoStudio without browsing Gallery. Available in Full Autonomous mode; Gallery enumeration remains blocked.",inputSchema:{url:z.string().url(),name:z.string().max(160).optional(),projectId:z.string().min(8).optional()}},async({url,name,projectId})=>queue("import_url",{url,name:name||"ChatGPT import",projectId:projectId||""}));

  if(isV3) s.registerTool("app_import_attachment",{
    description:"Primary VideoStudio v3 ChatGPT attachment path. Pass a file explicitly attached/shared by the user. The Worker holds only short-lived handoff metadata and privately streams the temporary source to the authorised Android app; Gallery enumeration is never used.",
    inputSchema:{
      file:z.object({
        download_url:z.string().url(),
        file_id:z.string().min(1),
        mime_type:z.string().max(120).optional(),
        file_name:z.string().max(180).optional()
      }).strict(),
      projectId:z.string().min(8).optional()
    },
    _meta:{"openai/fileParams":["file"]}
  },async({file,projectId})=>{
    try{
      // Use RPC methods that already existed before the private-relay rollout.
      // This keeps active Durable Object instances compatible during a rolling deploy.
      const handoff=await st.appCreateHandoff(ownerKey,file.download_url,{
        name:file.file_name||"ChatGPT attachment",
        mime:file.mime_type||"",
        size:0
      });
      const c=await st.appEnqueueV3(ownerKey,"import_chat_file",{
        handoffId:handoff.id,
        name:handoff.name,
        mime:handoff.mime,
        size:handoff.size,
        projectId:projectId||""
      });
      return out({
        queued:true,
        commandId:c.id,
        sequence:c.seq,
        action:"import_chat_file",
        nativeApp:true,
        protocolVersion:3,
        route:"private-worker-handoff",
        waitingNative:c.status==="waiting_native"
      });
    }catch(e){ return out({queued:false,error:e.message}); }
  });

  if(isV3) s.registerTool("app_import_inline_base64",{
    description:"Private compatibility fallback for small ChatGPT media when temporary HTTPS handoff is unavailable. Bytes stay inside the owner-authenticated MCP command and are written directly to VideoStudio app-private storage. PNG, JPEG, WebP and MP4 are accepted up to 1 MiB decoded; larger explicit files use private direct HTTPS/attachment handoff.",
    inputSchema:{
      name:z.string().min(1).max(180),
      mime:z.enum(["image/png","image/jpeg","image/webp","video/mp4"]),
      base64:z.string().min(1).max(4*Math.ceil(1024*1024/3)),
      sha256:z.string().regex(/^[a-fA-F0-9]{64}$/).optional(),
      projectId:z.string().min(8).optional()
    }
  },async({name,mime,base64,sha256,projectId})=>queue("import_inline_base64",{
    name,mime,base64,sha256:sha256||"",projectId:projectId||""
  }));

  s.registerTool("app_import_chat_file",{description:"Securely stream a file attached in this ChatGPT conversation into VideoStudio. Short-lived relay metadata only; media is not permanently stored by the Worker. Available in Full Autonomous mode; Gallery enumeration remains blocked.",inputSchema:{sourceUrl:z.string().url(),name:z.string().min(1).max(180),mime:z.string().max(120).optional(),size:z.number().nonnegative().optional(),projectId:z.string().min(8).optional()}},async({sourceUrl,name,mime,size,projectId})=>{
    try{
      const handoff=await st.appCreateHandoff(ownerKey,sourceUrl,{name,mime,size});
      const c=await enqueueCommand("import_chat_file",{handoffId:handoff.id,name:handoff.name,mime:handoff.mime,size:handoff.size,projectId:projectId||""});
      return out({queued:true,commandId:c.id,sequence:c.seq,action:"import_chat_file",handoff:{id:handoff.id,expiresAt:handoff.expiresAt},note:"Bytes stream privately to the phone; the source URL is not sent in the device command."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });

  if(isV3) s.registerTool("app_bind_studio_web",{
    description:"Bind one registered Studio Web device as the browser fallback for this same private native MCP. The owner credential stays private. When Android sleeps, compatible edit/generation/render work can route to the bound Web project; native-only work remains durable until Android reconnects.",
    inputSchema:{webDeviceId:z.string().min(8).max(160)}
  },async({webDeviceId})=>{
    try { return out(await st.appBindStudioWebFallback(ownerKey,webDeviceId)); }
    catch(e){ return out({bound:false,error:e.message}); }
  });

  if(isV3) s.registerTool("app_create_hybrid_binding",{
    description:"Ask the Android Native Agent to mint a short-lived one-time challenge that binds a Studio Web device to this existing permanent native identity. The resulting private hybrid MCP key survives compatible APK upgrades.",
    inputSchema:{webDeviceId:z.string().min(8).max(160)}
  },async({webDeviceId})=>queue("create_hybrid_binding",{webDeviceId}));

  if(isV3) s.registerTool("app_connection_health",{
    description:"Read the Android Native Agent's stable MCP Connection Core health, app generation and persisted service heartbeat without changing identity or browsing Gallery.",
    inputSchema:{}
  },async()=>queue("connection_health",{}));

  if(isV3) s.registerTool("app_reconnect_mcp",{
    description:"Ask VideoStudio to reset only cached MCP negotiation and re-register through the permanent compatibility endpoint. Device ID, owner credential, projects, command journal and Gallery privacy boundary are preserved.",
    inputSchema:{}
  },async()=>queue("reconnect_mcp",{}));

  if(isV3) s.registerTool("app_execute",{description:"Stable future-compatible VideoStudio v3 action bridge. Use this for native actions introduced by future app versions without requiring the ChatGPT connector to be recreated. Gallery/media-library enumeration remains permanently blocked by the server regardless of the requested action.",inputSchema:{action:z.string().min(1).max(80),parameters:z.record(z.string(),z.any()).optional()}},async({action,parameters})=>{
    const p=parameters||{};
    if(action==="converge_identity_to"){
      try{
        const primaryOwnerKey=String(p.primaryOwnerKey||"");
        const primaryDeviceId=String(p.primaryDeviceId||"");
        const legacy=[ownerKey,...(Array.isArray(p.legacyOwnerKeys)?p.legacyOwnerKeys:[])];
        return out(await st.appConvergeOwnerAliases(primaryOwnerKey,legacy,primaryDeviceId));
      }catch(e){ return out({ok:false,error:e.message}); }
    }
    return queue(action,p);
  });

  s.registerTool("app_batch",{description:"Queue up to 20 native VideoStudio actions quickly in order. This v3-compatible batch surface accepts future native action names so app upgrades do not require reconnecting the ChatGPT connector. Gallery/library enumeration is blocked regardless of permission mode.",inputSchema:{actions:z.array(z.object({action:z.string().min(1).max(80),parameters:z.record(z.string(),z.any()).optional()})).min(1).max(20)}},async({actions})=>{
    const queued=[];
    try{
      for(const item of actions){
        const c=await enqueueCommand(item.action,item.parameters||{});
        queued.push({commandId:c.id,sequence:c.seq,action:c.action});
      }
      return out({queued:true,count:queued.length,commands:queued});
    }catch(e){ return out({queued:false,error:e.message,commands:queued}); }
  });

  s.registerTool("app_cancel_job",{description:"Cancel one native VideoStudio background job.",inputSchema:{jobId:z.string().min(8)}},async({jobId})=>queue("cancel_job",{jobId}));
  s.registerTool("app_cancel_all_jobs",{description:"Cancel every active VideoStudio job and current export.",inputSchema:{}},async()=>queue("cancel_all_jobs",{}));
  if(isV3)s.registerTool("app_get_command_result_page",{description:"Retrieve one bounded JSON text fragment of a large retained native result, including full source programme/plan/effect definitions. Concatenate ordered fragments and verify viewSha256; view is credential-redacted, never silently truncated. Result may explicitly expire under bounded terminal retention.",inputSchema:{commandId:z.string().min(8),pageIndex:z.number().int().min(0).max(255).optional()},annotations:{readOnlyHint:true}},async({commandId,pageIndex})=>{
    try{const command=await readCommand(commandId);if(!command)return out({error:"Command not found"});return out(await nativeResultPage(command,redactCommand(command).result,pageIndex||0));}
    catch(error){return out({error:error.message});}
  });
  s.registerTool("app_get_command_result",{description:"Read completion status/result for a native command. Analysis results render their contact sheet directly for ChatGPT to inspect.",inputSchema:{commandId:z.string().min(8)}},async({commandId})=>commandResult(commandId));
  return s;
}

async function api(request,env){
  const u=new URL(request.url), st=state(env);
  if(request.method==="OPTIONS") return new Response(null,{status:204,headers:{"access-control-allow-origin":"*","access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type, authorization"}});
  try{

    const exportUpload=u.pathname.match(/^\/api\/studio-transfer\/upload\/([a-f0-9]{64})$/);
    if(exportUpload){
      if(request.method!=="POST")return reply({error:"Method not allowed"},405);
      const claimed=Number(request.headers.get("content-length")||0);
      if(claimed>12*1024*1024)return reply({error:"Video exceeds 12 MiB export limit"},413);
      const bytes=new Uint8Array(await request.arrayBuffer());
      if(bytes.length>12*1024*1024)return reply({error:"Video exceeds 12 MiB export limit"},413);
      return reply(await st.storeTemporaryVideoExport(exportUpload[1],bytes,request.headers.get("content-type")||""));
    }
    const exportDownload=u.pathname.match(/^\/api\/studio-transfer\/download\/([a-f0-9]{64})$/);
    if(exportDownload){
      if(request.method!=="GET")return reply({error:"Method not allowed"},405);
      const meta=await st.getTemporaryVideoExportInfo(exportDownload[1]);
      if(!meta)return reply({error:"Video is not ready or the private download has expired"},404);
      const parts=[];
      for(let i=0;i<meta.parts;i++){
        const chunk=await st.getTemporaryVideoExportPart(meta.uploadToken,i);
        if(!chunk) return reply({error:"Missing transfer part; cannot serve incomplete MP4"},409);
        parts.push(chunk);
      }
      const blob=new Blob(parts,{type:meta.mime});
      if(blob.size!==meta.size)return reply({error:"Export size does not match"},409);
      return new Response(blob,{
        status:200,
        headers:{
          "content-type":meta.mime,"content-length":String(meta.size),
          "content-disposition":'attachment; filename="'+meta.name+'"',
          "cache-control":"private, no-store","referrer-policy":"no-referrer",
          "x-content-type-options":"nosniff",
          "x-videostudio-sha256":meta.digest
        }
      });
    }
    if(u.pathname==="/api/v3/app/register"&&request.method==="POST"){
      const b=await request.json(), meta=b.meta||{};
      const min=Number(meta.protocolMin||meta.protocolVersion||0);
      const max=Number(meta.protocolMax||meta.protocolVersion||0);
      if(!(min<=3&&max>=3)) return reply({
        ok:false,
        protocolVersion:3,
        error:"Stable MCP compatibility lane v3 is not supported by this app"
      },409);
      const registration=await st.appRegister(b.deviceId,b.ownerKey,meta);
      if(registration&&registration.__staleClient){
        return reply({
          ok:false,
          staleClient:true,
          protocolVersion:3,
          expectedGeneration:registration.expectedGeneration,
          receivedGeneration:registration.receivedGeneration,
          error:"Older VideoStudio Native Agent generation rejected"
        });
      }
      return reply({
        ok:true,
        protocolVersion:3,
        mcpEndpointVersion:"v3-stable",
        stableMcpEndpoint:true,
        device:registration,
        connection:{
          selectedProtocol:3,
          apiPrefix:"/api/v3/app",
          stableMcpPath:"/app-mcp-v3/",
          heartbeatMs:12000,
          commandWaitMs:18000,
          requestTimeoutMs:30000,
          leaseMs:60000,
          endpointMode:"stable-compatibility",
          compatibilityPolicy:"stable-major-additive-features",
          wireSchemaVersion:1,
          transportDecoupledFromApkVersion:true,
          serverEpoch:"stable-core-2",
          acceptedAppGeneration:Number(registration.appGeneration||0)
        }
      });
    }
    if(u.pathname==="/api/v3/app/rebind"&&request.method==="POST"){
      const b=await request.json(), meta=b.meta||{};
      const min=Number(meta.protocolMin||meta.protocolVersion||0);
      const max=Number(meta.protocolMax||meta.protocolVersion||0);
      if(!(min<=3&&max>=3)) return reply({ok:false,error:"Stable MCP compatibility lane v3 is required"},409);
      const registration=await st.appRedeemRebind(b.token,b.deviceId,b.ownerKey,meta);
      return reply({
        ok:true,
        rebound:true,
        protocolVersion:3,
        mcpEndpointVersion:"v3-stable",
        stableMcpEndpoint:true,
        device:registration,
        connection:{
          selectedProtocol:3,
          apiPrefix:"/api/v3/app",
          stableMcpPath:"/app-mcp-v3/",
          heartbeatMs:12000,
          commandWaitMs:18000,
          requestTimeoutMs:30000,
          leaseMs:60000,
          endpointMode:"stable-compatibility",
          compatibilityPolicy:"stable-major-additive-features",
          wireSchemaVersion:1,
          transportDecoupledFromApkVersion:true,
          serverEpoch:"stable-core-2",
          acceptedAppGeneration:Number(registration.appGeneration||0)
        }
      });
    }
    if(u.pathname==="/api/v3/app/hybrid/challenge"&&request.method==="POST"){
      const b=await request.json(), token=bearer(request);
      const registered=await st.appAuth(b.deviceId,token);
      if(!registered) return reply({error:"VideoStudio stable MCP authorization failed"},401);
      const expected=Math.max(0,Number(registered.appGeneration||0));
      const received=Math.max(0,Number(b.appGeneration||0));
      if(expected>0&&received!==expected) return reply({
        error:"Stale Native Agent generation",
        staleClient:true,
        expectedGeneration:expected,
        receivedGeneration:received
      },409);
      const challenge=await st.appCreateHybridBinding(token,b.webDeviceId);
      return reply({ok:true,protocolVersion:3,challenge});
    }
    if(u.pathname==="/api/v3/app/status"&&request.method==="GET"){
      const status=await st.appStatusV3(bearer(request));
      return reply(status,status.connected?200:409);
    }
    if(u.pathname==="/api/v3/app/commands"&&request.method==="GET"){
      const deviceId=u.searchParams.get("deviceId")||"";
      const after=Number(u.searchParams.get("after")||0);
      const wait=Number(u.searchParams.get("wait")||0);
      const generation=Math.max(0,Number(u.searchParams.get("appGeneration")||0));
      const token=bearer(request);
      const registered=await st.appAuth(deviceId,token);
      if(!registered) return reply({error:"VideoStudio stable MCP authorization failed"},401);
      const expected=Math.max(0,Number(registered.appGeneration||0));
      if(expected>0&&generation!==expected) return reply({
        error:"Stale Native Agent generation",
        staleClient:true,
        expectedGeneration:expected,
        receivedGeneration:generation
      },409);
      return reply({protocolVersion:3,appGeneration:expected,commands:await st.appCommandsV3(deviceId,token,after,wait)});
    }
    const v3hm=u.pathname.match(/^\/api\/v3\/app\/handoffs\/([^/]+)\/content$/);
    if(v3hm&&request.method==="GET"){
      const deviceId=u.searchParams.get("deviceId")||"", token=bearer(request);
      const generation=Math.max(0,Number(u.searchParams.get("appGeneration")||0));
      const registered=await st.appAuth(deviceId,token);
      if(!registered) return reply({error:"VideoStudio stable MCP authorization failed"},401);
      const expected=Math.max(0,Number(registered.appGeneration||0));
      if(expected>0&&generation!==expected) return reply({
        error:"Stale Native Agent generation",
        staleClient:true,
        expectedGeneration:expected,
        receivedGeneration:generation
      },409);
      const handoff=await st.appHandoff(deviceId,token,v3hm[1]);
      if(!handoff) return reply({error:"Handoff missing or expired"},404);
      if(handoff.blobId){
        const durableUrl=new URL(request.url);durableUrl.pathname='/private-content/'+handoff.blobId;
        return st.fetch(new Request(durableUrl,request));
      }
      let upstream;
      if(handoff.cacheUrl){
        upstream=await caches.default.match(new Request(handoff.cacheUrl));
        if(!upstream||!upstream.body) return reply({error:"Private upload expired or unavailable"},404);
      }else{
        upstream=await fetch(handoff.sourceUrl,{headers:{"accept":"*/*","user-agent":"VideoStudio-MCPv3-Fallback/3.0"}});
        if(!upstream.ok||!upstream.body) return reply({error:"Attachment source unavailable",status:upstream.status},502);
      }
      const headers=new Headers();
      headers.set("content-type",handoff.mime||upstream.headers.get("content-type")||"application/octet-stream");
      const length=upstream.headers.get("content-length");
      if(length) headers.set("content-length",length);
      headers.set("cache-control","no-store");
      headers.set("x-content-type-options","nosniff");
      headers.set("content-disposition",'attachment; filename="'+handoff.name.replace(/[\r\n"]/g,"_")+'"');
      return new Response(upstream.body,{status:200,headers});
    }
    const v3cm=u.pathname.match(/^\/api\/v3\/app\/commands\/([^/]+)\/complete$/);
    if(v3cm&&request.method==="POST"){
      const b=await request.json(), token=bearer(request);
      if(Number(b.protocolVersion||0)!==3) return reply({error:"Protocol version mismatch"},409);
      const registered=await st.appAuth(b.deviceId,token);
      if(!registered) return reply({error:"VideoStudio stable MCP authorization failed"},401);
      const expected=Math.max(0,Number(registered.appGeneration||0));
      const generation=Math.max(0,Number(b.appGeneration||0));
      if(expected>0&&generation!==expected) return reply({
        error:"Stale Native Agent generation",
        staleClient:true,
        expectedGeneration:expected,
        receivedGeneration:generation
      },409);
      const c=await st.appCompleteV3(b.deviceId,token,v3cm[1],b.result||{},b.status||"completed");
      return c?reply({protocolVersion:3,appGeneration:expected,command:c}):reply({error:"Command not found"},404);
    }

    if(u.pathname==="/api/app/register"&&request.method==="POST"){
      const b=await request.json();
      return reply({ok:true,device:await st.appRegister(b.deviceId,b.ownerKey,b.meta||{})});
    }
    if(u.pathname==="/api/app/private/status"&&request.method==="GET"){
      const token=bearer(request);
      const device=await st.appResolve(token);
      if(!device)return reply({error:"Native app authorization failed"},401);
      const v3=Number(device.protocolMin||device.protocolVersion||0)<=3&&Number(device.protocolMax||device.protocolVersion||0)>=3;
      const status=v3?await st.appStatusV3(token):await st.appStatus(token);
      if(!status.connected) return reply(status,401);
      return reply(status);
    }
    const pcm=u.pathname.match(/^\/api\/app\/private\/commands\/([^/]+)$/);
    if(pcm&&request.method==="GET"){
      const token=bearer(request);
      const device=await st.appResolve(token);
      if(!device)return reply({error:"Native app authorization failed"},401);
      const v3=Number(device.protocolMin||device.protocolVersion||0)<=3&&Number(device.protocolMax||device.protocolVersion||0)>=3;
      const command=(v3?await st.appCommandV3(token,pcm[1]):null)||await st.appCommand(token,pcm[1]);
      return command?reply({command}):reply({error:"Command not found"},404);
    }
    if(u.pathname==="/api/app/private/upload"&&request.method==="POST"){
      const token=bearer(request), deviceId=u.searchParams.get("deviceId")||"";
      const device=await st.appAuth(deviceId,token);
      if(!device) return reply({error:"Native app authorization failed"},401);
      if(device.permissionMode==="one_file") return reply({error:"Full Autonomous mode is required while One File Lock is active"},403);

      const durableUrl=new URL(request.url);durableUrl.pathname='/private-upload';
      return st.fetch(new Request(durableUrl,request));
    }
    if(u.pathname==="/api/app/commands"&&request.method==="GET"){
      const deviceId=u.searchParams.get("deviceId")||"", after=Number(u.searchParams.get("after")||0), wait=Number(u.searchParams.get("wait")||0);
      const token=bearer(request);
      return reply({commands:await st.appCommands(deviceId,token,after,wait)});
    }
    const hm=u.pathname.match(/^\/api\/app\/handoffs\/([^/]+)\/content$/);
    if(hm&&request.method==="GET"){
      const deviceId=u.searchParams.get("deviceId")||"", token=bearer(request);
      if(!(await st.appAuth(deviceId,token)))return reply({error:"Native app authorization failed"},401);
      const handoff=await st.appHandoff(deviceId,token,hm[1]);
      if(!handoff) return reply({error:"Handoff missing or expired"},404);
      if(handoff.blobId){
        const durableUrl=new URL(request.url);durableUrl.pathname='/private-content/'+handoff.blobId;
        return st.fetch(new Request(durableUrl,request));
      }
      let upstream;
      if(handoff.cacheUrl){
        upstream=await caches.default.match(new Request(handoff.cacheUrl));
        if(!upstream||!upstream.body) return reply({error:"Private upload expired or unavailable"},404);
      }else{
        upstream=await fetch(handoff.sourceUrl,{headers:{"accept":"*/*","user-agent":"VideoStudio-Private-Handoff/1.0"}});
        if(!upstream.ok||!upstream.body) return reply({error:"Attachment source unavailable",status:upstream.status},502);
      }
      const headers=new Headers();
      headers.set("content-type",handoff.mime||upstream.headers.get("content-type")||"application/octet-stream");
      const length=upstream.headers.get("content-length");
      if(length) headers.set("content-length",length);
      headers.set("cache-control","no-store");
      headers.set("x-content-type-options","nosniff");
      headers.set("content-disposition",'attachment; filename="'+handoff.name.replace(/[\r\n"]/g,"_")+'"');
      return new Response(upstream.body,{status:200,headers});
    }
    const acm=u.pathname.match(/^\/api\/app\/commands\/([^/]+)\/complete$/);
    if(acm&&request.method==="POST"){
      const b=await request.json(),token=bearer(request);
      const c=await st.appComplete(b.deviceId,token,acm[1],b.result||{},b.status||"completed");
      return c?reply({command:c}):reply({error:"Command not found"},404);
    }
    if(u.pathname==="/api/device/register"&&request.method==="POST"){ const b=await request.json(); return reply({ok:true,device:await st.register(b.deviceId,b.meta||{})}); }
    if(u.pathname==="/api/device/status"&&request.method==="GET") return reply(await st.status(u.searchParams.get("deviceId")||""));
    if(u.pathname==="/api/projects"&&request.method==="GET") return reply({projects:await st.projects(u.searchParams.get("deviceId")||"")});
    if(u.pathname==="/api/projects"&&request.method==="POST"){ const b=await request.json(); return reply({project:await st.createProject(b.deviceId,b.name,b.instruction||"")}); }
    const pm=u.pathname.match(/^\/api\/projects\/([^/]+)$/);
    if(pm&&request.method==="GET"){ const p=await st.project(u.searchParams.get("deviceId")||"",pm[1]); return p?reply({project:p}):reply({error:"Project not found"},404); }
    if(pm&&request.method==="POST"){ const b=await request.json(),p=await st.update(b.deviceId,pm[1],b.patch||{}); return p?reply({project:p}):reply({error:"Project not found"},404); }
    if(u.pathname==="/api/runtime/commands"&&request.method==="GET") return reply({commands:await st.runtimeCommands(u.searchParams.get("deviceId")||"",Number(u.searchParams.get("after")||0))});
    const rcm=u.pathname.match(/^\/api\/runtime\/commands\/([^/]+)\/complete$/);
    if(rcm&&request.method==="POST"){ const b=await request.json(),c=await st.completeRuntime(b.deviceId,rcm[1],b.result||{},b.status||"completed"); return c?reply({command:c}):reply({error:"Runtime command not found"},404); }
        if(u.pathname==="/api/commands"&&request.method==="GET") return reply({commands:await st.commands(u.searchParams.get("deviceId")||"",Number(u.searchParams.get("after")||0))});
    const cm=u.pathname.match(/^\/api\/commands\/([^/]+)\/complete$/);
    if(cm&&request.method==="POST"){ const b=await request.json(),c=await st.complete(b.deviceId,cm[1],b.result||{},b.status||"completed"); return c?reply({command:c}):reply({error:"Command not found"},404); }
    return reply({error:"API route not found"},404);
  }catch(e){ return reply({error:e.message||"Request failed"},400); }
}

const MANIFEST=JSON.stringify({
  name:"VideoStudio Studio Web",
  short_name:"VideoStudio",
  description:"Visual local-first video editor and autonomous ChatGPT execution surface.",
  start_url:"/",
  display:"standalone",
  background_color:"#07090d",
  theme_color:"#0b0d13",
  categories:["video","productivity","photo"],
  icons:[{src:"/icon.svg",sizes:"any",type:"image/svg+xml",purpose:"any maskable"}]
});
const ICON='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><defs><linearGradient id="g"><stop stop-color="#8b5cf6"/><stop offset="1" stop-color="#21d4fd"/></linearGradient></defs><rect width="512" height="512" rx="112" fill="#090b11"/><path d="M211 182v148l124-74-124-74z" fill="url(#g)"/><circle cx="256" cy="256" r="188" fill="none" stroke="url(#g)" stroke-width="18"/></svg>';
const SW='const C="videostudio-studio-web-v3";self.addEventListener("install",e=>e.waitUntil(caches.open(C).then(c=>c.addAll(["/","/manifest.webmanifest","/icon.svg"]))));self.addEventListener("activate",e=>e.waitUntil(Promise.all([self.clients.claim(),caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k))))])));self.addEventListener("fetch",e=>{if(e.request.method!=="GET")return;const u=new URL(e.request.url);if(u.pathname.startsWith("/api/")||u.pathname.startsWith("/mcp"))return;e.respondWith(fetch(e.request).then(r=>{const x=r.clone();caches.open(C).then(c=>c.put(e.request,x));return r}).catch(()=>caches.match(e.request)))})';

export default {
  async fetch(request,env,ctx){
    const u=new URL(request.url);
    if((u.pathname==="/download-local"||u.pathname==="/download-local/")&&request.method==="GET"){
      return new Response(LOCAL_VIDEO_DOWNLOAD_HTML,{status:200,headers:{
        "content-type":"text/html; charset=UTF-8",
        "cache-control":"private, no-store",
        "referrer-policy":"no-referrer",
        "x-content-type-options":"nosniff",
        "content-security-policy":"default-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; frame-ancestors 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'"
      }});
    }
    if(u.pathname==="/"&&request.method==="GET"){
      let html=APP_HTML.includes("/studio-runtime.js")?APP_HTML:APP_HTML.replace("</body>",'<script defer src="/studio-runtime.js"></script></body>');
      if(!html.includes("/studio-cinematic.js")) html=html.replace("</body>",'<script defer src="/studio-cinematic.js"></script></body>');
      if(!html.includes("/studio-neural.js")) html=html.replace("</body>",'<script defer src="/studio-neural.js"></script></body>');
      if(!html.includes("/studio-temporal.js")) html=html.replace("</body>",'<script defer src="/studio-temporal.js"></script></body>');
      if(!html.includes("/studio-character.js")) html=html.replace("</body>",'<script defer src="/studio-character.js"></script></body>');
      if(!html.includes("/studio-action-timeline.js")) html=html.replace("</body>",'<script defer src="/studio-action-timeline.js"></script></body>');
      if(!html.includes("/studio-pose-sequence.js")) html=html.replace("</body>",'<script defer src="/studio-pose-sequence.js"></script></body>');
      return new Response(html,{headers:{"content-type":"text/html; charset=UTF-8","cache-control":"no-store"}});
    }
    if(u.pathname==="/studio-runtime.js"&&request.method==="GET") return new Response(STUDIO_RUNTIME_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-cinematic.js"&&request.method==="GET") return new Response(STUDIO_CINEMATIC_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-neural.js"&&request.method==="GET") return new Response(STUDIO_NEURAL_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-temporal.js"&&request.method==="GET") return new Response(STUDIO_TEMPORAL_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-character.js"&&request.method==="GET") return new Response(STUDIO_CHARACTER_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-action-timeline.js"&&request.method==="GET") return new Response(STUDIO_ACTION_TIMELINE_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-pose-sequence.js"&&request.method==="GET") return new Response(STUDIO_POSE_SEQUENCE_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/api/web/config"&&request.method==="GET") return reply({googleDriveClientId:clean(env.GOOGLE_DRIVE_CLIENT_ID||"",300),driveScope:"https://www.googleapis.com/auth/drive.file",storageMode:"user-owned-google-drive"});
    if(u.pathname==="/manifest.webmanifest") return new Response(MANIFEST,{headers:{"content-type":"application/manifest+json"}});
    if(u.pathname==="/icon.svg") return new Response(ICON,{headers:{"content-type":"image/svg+xml"}});
    if(u.pathname==="/sw.js") return new Response(SW,{headers:{"content-type":"application/javascript","cache-control":"no-cache"}});
    if(u.pathname==="/api/relay/release"&&request.method==="GET")return reply(relayRelease());
    if(u.pathname.startsWith("/api/")) return api(request,env);
    // Permanent compatibility endpoint. Do not rename this route for APK releases.
    // Future app versions evolve behind protocol-v3 additive actions/app_execute.
    const appMcpV3=u.pathname.match(/^\/app-mcp-v3\/([A-Za-z0-9_-]{32,})$/);
    if(appMcpV3){
      const ownerKey=appMcpV3[1];
      return createMcpHandler(()=>serverForApp(env,ownerKey,3),{route:u.pathname,responseMode:"auto"})(request,env,ctx);
    }
    const appMcp=u.pathname.match(/^\/app-mcp\/([A-Za-z0-9_-]{32,})$/);
    if(appMcp){
      const ownerKey=appMcp[1];
      return createMcpHandler(()=>serverForApp(env,ownerKey),{route:u.pathname,responseMode:"auto"})(request,env,ctx);
    }
    if(u.pathname==="/mcp"||u.pathname.startsWith("/mcp/")) return createMcpHandler(()=>serverFor(env),{route:"/mcp",responseMode:"auto"})(request,env,ctx);
    const hybridMcp=u.pathname.match(/^\/mcp-v06\/([A-Za-z0-9_-]{32,})(?:\/.*)?$/);
    if(hybridMcp){
      if(!(await studioMcpAuthorized(request,env))) return reply({error:"Studio Web MCP authorization required"},401);
      const hybridKey=hybridMcp[1];
      const bound=await state(env).appResolveHybrid(hybridKey);
      if(!bound) return reply({error:"Private hybrid MCP binding rejected"},401);
      const route="/mcp-v06/"+hybridKey;
      return createMcpHandler(()=>serverFor(env,hybridKey),{route,responseMode:"auto"})(request,env,ctx);
    }
    if(u.pathname==="/mcp-v06"||u.pathname.startsWith("/mcp-v06/")){
      if(!(await studioMcpAuthorized(request,env))) return reply({error:"Studio Web MCP authorization required"},401);
      return createMcpHandler(()=>serverFor(env),{route:"/mcp-v06",responseMode:"auto"})(request,env,ctx);
    }
    return new Response("Not Found",{status:404});
  }
};

