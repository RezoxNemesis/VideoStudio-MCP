import { DurableObject } from "cloudflare:workers";
import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";
import APP_HTML from "./app.html";
import STUDIO_RUNTIME_JS from "./studio-runtime.js";
import STUDIO_CINEMATIC_JS from "./studio-cinematic.js";
import STUDIO_NEURAL_JS from "./studio-neural.js";
import STUDIO_TEMPORAL_JS from "./studio-temporal.js";
import { ProjectMetadataMirror } from "./project-metadata-mirror.js";
import { readCommandQueue, mutateCommandQueue, readCommandQueueInTransaction, writeCommandQueueInTransaction, requireCommandQueueAdmission, commandIsPending, terminalCommandUpdate, canonicalCommandResultSha256, readCommandReceipt, readCommandReceiptsInTransaction, mergeCommandReceiptsInTransaction, acknowledgePrunedCommand } from "./command-queue-store.js";

const JH = {"content-type":"application/json; charset=UTF-8","cache-control":"no-store"};
const now = () => new Date().toISOString();
const clean = (v,n=5000) => String(v ?? "").trim().slice(0,n);
const definedFields=value=>Object.fromEntries(Object.entries(value).filter(([,item])=>item!==undefined));
const STUDIO_PROJECT_BYTES=112*1024,STUDIO_APPEND_BYTES=64*1024;
const requireStudioJsonDepth=value=>{const stack=[[value,0]];while(stack.length){const [item,depth]=stack.pop();if(depth>64)throw studioError("Project metadata exceeds the 64-level JSON nesting budget","invalid_project_request");if(item&&typeof item==="object")for(const child of Object.values(item))stack.push([child,depth+1]);}};
const canonicalJson=(value,depth=0)=>{if(depth>64)throw studioError("Project metadata exceeds the 64-level JSON nesting budget","invalid_project_request");return Array.isArray(value)?value.map(item=>canonicalJson(item,depth+1)):value&&typeof value==="object"?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonicalJson(value[key],depth+1)])):value;};
const sameJson=(left,right)=>JSON.stringify(canonicalJson(left))===JSON.stringify(canonicalJson(right));
const studioCommandHint=command=>{
  const hint={id:command.id,action:command.action,status:command.status,createdAt:command.createdAt,completedAt:command.completedAt};
  if(command.runtime)hint.runtime=command.runtime;
  if(command.result!==undefined){
    if(new TextEncoder().encode(JSON.stringify(command.result)).byteLength<=2048)hint.result=command.result;
    else{hint.resultOmitted=true;hint.resultLookupCommandId=command.id;if(command.resultSha256)hint.resultSha256=command.resultSha256;}
  }
  return hint;
};
const studioError=(message,code,status=400,details={})=>Object.assign(new Error(message),{code,status,...details});
const requireStudioBudget=(value,maximum)=>{requireStudioJsonDepth(value);if(new TextEncoder().encode(JSON.stringify(value)).byteLength>maximum)throw studioError("Studio project metadata exceeds its bounded storage budget; existing edits and generated media are retained","project_metadata_budget",413);};
async function readBoundedStudioJson(request,maximum){
  if(!request.body)throw studioError("Project request JSON is required","invalid_project_request");
  const reader=request.body.getReader(),chunks=[];let bytes=0;
  try{while(true){const part=await reader.read();if(part.done)break;bytes+=part.value.byteLength;if(bytes>maximum)throw studioError("Project request exceeds its bounded metadata budget","project_metadata_budget",413);chunks.push(part.value);}}
  catch(error){await reader.cancel().catch(()=>{});throw error;}finally{reader.releaseLock();}
  const combined=new Uint8Array(bytes);let offset=0;for(const chunk of chunks){combined.set(chunk,offset);offset+=chunk.byteLength;}
  try{const body=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(combined));if(!body||typeof body!=="object"||Array.isArray(body))throw new Error();requireStudioJsonDepth(body);return body;}
  catch{throw studioError("Project request must be valid JSON metadata","invalid_project_request");}
}
const PRIVATE_UPLOAD_LIMIT = 250*1024*1024;
const ATTACHMENT_TTL_MS = 20*60*1000;
// Host file parameters are references, not authority to browse a media library.
// file_id alone is deliberately not resolved: only the host's temporary URL or
// an explicit owner-authenticated byte upload gives this Worker access to bytes.
const attachmentFile = (file={}) => {
  if(!file||typeof file!=="object"||Array.isArray(file)) throw new Error("A user-shared attachment file reference is required");
  const download_url=String(file.download_url||file.downloadUrl||file.url||"").trim();
  if(!download_url) throw new Error("The host did not supply an attachment download_url. A file_id or sandbox path alone cannot be downloaded; use a host-supplied temporary HTTPS file URL or the private authenticated upload endpoint.");
  const size=Number(file.size??file.file_size??file.size_bytes??0);
  if(!Number.isSafeInteger(size)||size<0) throw new Error("Invalid attachment byte size");
  const sha256=String(file.sha256||"").trim().toLowerCase();
  if(sha256&&!/^[a-f0-9]{64}$/.test(sha256)) throw new Error("Invalid attachment SHA-256");
  return {download_url:publicAttachmentUrl(download_url),file_id:clean(file.file_id||file.id||"",180),file_name:clean(file.file_name||file.name||"ChatGPT attachment",180),mime_type:clean(file.mime_type||file.mime||"",120),size,sha256};
};
const publicAttachmentUrl = raw => {
  let u; try{ u=new URL(String(raw||"")); }catch{ throw new Error("Invalid attachment HTTPS URL"); }
  if(u.protocol!=="https:"||u.username||u.password||(u.port&&u.port!=="443")) throw new Error("Attachment sources require HTTPS on port 443 without URL credentials");
  const host=u.hostname.toLowerCase().replace(/^\[|\]$/g,"");
  const octets=host.split(".").map(Number);
  const ipv4=octets.length===4&&octets.every(n=>Number.isInteger(n)&&n>=0&&n<=255);
  const blocked4=ipv4&&(octets[0]===0||octets[0]===10||octets[0]===127||octets[0]>=224||
    (octets[0]===169&&octets[1]===254)||(octets[0]===172&&octets[1]>=16&&octets[1]<=31)||
    (octets[0]===192&&octets[1]===168)||(octets[0]===100&&octets[1]>=64&&octets[1]<=127)||
    (octets[0]===198&&(octets[1]===18||octets[1]===19)));
  if(!host||!host.includes(".")||host==="localhost"||/\.(localhost|local|internal|lan|home|test|invalid)$/.test(host)||blocked4||host.includes(":")) throw new Error("Private-network attachment sources are not allowed");
  return u.href;
};
const queueAttachment = async(st,ownerKey,file,projectId="",isV3=true) => {
  const reference=attachmentFile(file);
  // Keep pre-existing Durable Object RPC methods for rolling Worker deploys.
  const handoff=await st.appCreateHandoff(ownerKey,reference.download_url,{name:reference.file_name,mime:reference.mime_type,size:reference.size});
  const parameters={handoffId:handoff.id,name:handoff.name,mime:handoff.mime,size:reference.size,sha256:reference.sha256,fileId:reference.file_id,projectId:clean(projectId,120)};
  const command=isV3?await st.appEnqueueV3(ownerKey,"import_chat_file",parameters):await st.appEnqueue(ownerKey,"import_chat_file",parameters);
  return {...command,attachmentExpiresAt:handoff.expiresAt};
};
const privateHandoffContent = async(request,handoff) => {
  const range=request.headers.get("range")||"";
  if(range&&!/^bytes=\d+-\d*$/.test(range)) return reply({error:"Only one forward byte range is supported"},416);
  let upstream;
  if(handoff.cacheUrl){
    const headers=new Headers();
    if(range) headers.set("range",range);
    if(request.headers.get("if-range")) headers.set("if-range",request.headers.get("if-range"));
    upstream=await caches.default.match(new Request(handoff.cacheUrl,{headers}));
    if(!upstream||!upstream.body) return reply({error:"Private upload expired or unavailable"},404);
  }else{
    let current=publicAttachmentUrl(handoff.sourceUrl);
    for(let redirects=0;redirects<=5;redirects++){
      const headers=new Headers({"accept":"*/*","accept-encoding":"identity","user-agent":"VideoStudio-Private-Handoff/3.0"});
      if(range) headers.set("range",range);
      if(request.headers.get("if-range")) headers.set("if-range",request.headers.get("if-range"));
      upstream=await fetch(current,{headers,redirect:"manual"});
      if([301,302,303,307,308].includes(upstream.status)){
        const location=upstream.headers.get("location");
        if(upstream.body) await upstream.body.cancel();
        if(!location||redirects===5) return reply({error:"Attachment redirect could not be followed safely"},502);
        current=publicAttachmentUrl(new URL(location,current).href);
        continue;
      }
      break;
    }
    if(upstream.status===416) return reply({error:"Attachment byte range is no longer available"},416);
    if(![200,206].includes(upstream.status)||!upstream.body) return reply({error:"Attachment source unavailable",status:upstream.status},502);
  }
  const headers=new Headers({"cache-control":"no-store","x-content-type-options":"nosniff"});
  headers.set("content-type",handoff.mime||upstream.headers.get("content-type")||"application/octet-stream");
  // Preserve resume validators and the upstream status. Relaying a 206 as 200
  // corrupts resumptions; a real 200 tells Android to safely restart its partial.
  for(const name of ["content-length","content-range","accept-ranges","etag","last-modified"]){
    const value=upstream.headers.get(name); if(value) headers.set(name,value);
  }
  headers.set("content-disposition",'attachment; filename="'+handoff.name.replace(/[\r\n"]/g,"_")+'"');
  return new Response(upstream.body,{status:upstream.status,headers});
};
const queueInlineAttachment = async(st,ownerKey,parameters,origin) => {
  const mime=String(parameters.mime||"").toLowerCase();
  if(!["image/png","image/jpeg","image/webp","video/mp4"].includes(mime)) throw new Error("Inline attachment MIME is unsupported");
  let encoded=String(parameters.base64||"").trim();
  if(encoded.startsWith("data:")) encoded=encoded.slice(encoded.indexOf(",")+1);
  if(!encoded||encoded.length>17*1024*1024||encoded.length%4!==0||!/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) throw new Error("Invalid bounded base64 attachment");
  const size=encoded.length/4*3-(encoded.endsWith("==")?2:encoded.endsWith("=")?1:0);
  if(size<=0||size>12*1024*1024) throw new Error("Inline attachment exceeds the 12 MB decoded limit");
  const status=await st.appStatusV3(ownerKey);
  if(!status.registered||status.device.permissionMode==="one_file"||status.device.controlPaused) throw new Error("Inline attachment import is blocked by owner control state");
  const uploadId=crypto.randomUUID(), cacheUrl=origin+"/__videostudio_private_upload/"+uploadId;
  let offset=0;
  const body=new ReadableStream({pull(controller){
    if(offset>=encoded.length){ controller.close(); return; }
    const end=Math.min(encoded.length,offset+64*1024);
    const decoded=atob(encoded.slice(offset,end));
    offset=end;
    const bytes=new Uint8Array(decoded.length);
    for(let i=0;i<decoded.length;i++) bytes[i]=decoded.charCodeAt(i);
    controller.enqueue(bytes);
  }});
  const headers=new Headers({"content-type":mime,"content-length":String(size),"cache-control":"public, max-age=1200","etag":'"'+uploadId+'"'});
  try{
    await caches.default.put(new Request(cacheUrl),new Response(body,{headers}));
    const handoff=await st.appCreateCachedHandoff(ownerKey,cacheUrl,{name:clean(parameters.name||"ChatGPT media",180),mime,size});
    return await st.appEnqueueV3(ownerKey,"import_chat_file",{handoffId:handoff.id,name:handoff.name,mime,size,sha256:parameters.sha256||"",projectId:parameters.projectId||""});
  }catch(error){ await caches.default.delete(new Request(cacheUrl)); throw error; }
};
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
const boundedMetadataJson = async request => {
  const maximum=128*1024;
  if(Number(request.headers.get("content-length")||0)>maximum)throw new Error("Metadata request exceeds 128 KB");
  if(!request.body)throw new Error("Metadata request body is required");
  const reader=request.body.getReader(),chunks=[];
  let size=0;
  try{
    for(;;){
      const {value,done}=await reader.read();if(done)break;
      size+=value.byteLength;
      if(size>maximum){await reader.cancel();throw new Error("Metadata request exceeds 128 KB");}
      chunks.push(value);
    }
  }finally{reader.releaseLock();}
  const bytes=new Uint8Array(size);let offset=0;
  for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength;}
  const body=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes));
  if(!body||typeof body!=="object"||Array.isArray(body))throw new Error("Metadata request must be an object");
  return body;
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
  const a=String(action||"").toLowerCase();

  // Permanent privacy wall. Full autonomy never means Gallery enumeration.
  if(a.includes("gallery")||a.includes("media_library")||a.includes("photo_library")) return false;

  // One-file mode is an explicit user lock, not the normal operating mode.
  if(mode==="one_file"){
    return ["ping","get_state","self_test","job_status","activity_note","apply_tool","preview_project","analyse_media","export_project","cancel_job","cancel_all_jobs","stop_all"].includes(a);
  }

  // "all_tools" remains a backward-compatible alias for Full Autonomous.
  // Every ordinary VideoStudio-native action is available without permission
  // friction; safety remains enforced by device privacy, network and workload
  // boundaries instead of capability gating.
  return true;
};

export class VideoStudioState extends DurableObject {
  constructor(ctx,env){ super(ctx,env); }
  async appMetadataMirror(ownerKey,operation,parameters={}){
    const d=await this.appV3Device(ownerKey);
    const ownerHash=await sha256Hex(ownerKey);
    if(!["get","sync","edit","reconcile","revoke"].includes(operation))throw new Error("Unsupported metadata mirror operation");
    return this.ctx.storage.transaction(async storage=>{
      const current=await storage.get("app-device:"+d.deviceId);
      if(!current||await storage.get("app-owner:"+ownerHash)!==d.deviceId)
        throw new Error("Private native owner identity is no longer registered");
      if(operation!=="get"&&(current.controlPaused||current.permissionMode==="one_file"))
        throw new Error("Metadata graph effects are blocked by the owner's pause or One File Lock");
      if(operation!=="get"&&!appActionAllowed(current.permissionMode,"metadata_mirror_"+operation))
        throw new Error("Metadata mirror operation is outside the owner's scope");
      const mirror=new ProjectMetadataMirror(storage,d.deviceId);
      const result=await(operation==="get"?mirror.get(parameters.projectId):mirror[operation](parameters));
      return{...result,ownerScope:{controlPaused:!!current.controlPaused,permissionMode:current.permissionMode,
        mutationAllowed:!current.controlPaused&&current.permissionMode!=="one_file"
          &&appActionAllowed(current.permissionMode,"metadata_mirror_edit")}};
    });
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
      createdAt:old.createdAt||now(),
      lastSeenAt:now()
    };
    await this.ctx.storage.put(k,d); return d;
  }
  async device(deviceId){ return (await this.ctx.storage.get("d:"+deviceId))||null; }
  async createProject(deviceId,name,instruction=""){
    await this.register(deviceId);
    const id=crypto.randomUUID(), p={id,deviceId,revision:0,name:clean(name||"Untitled Project",120),instruction:clean(instruction),createdAt:now(),updatedAt:now(),assets:[],timeline:[],settings:{aspect:"9:16",speed:1,mute:false,title:"",quality:"720p",transition:"fade"},drive:{},generation:{},latestRender:null,latestCommand:null};
    await this.ctx.storage.put("p:"+deviceId+":"+id,p);
    const k="pl:"+deviceId, ids=(await this.ctx.storage.get(k))||[]; ids.unshift(id); await this.ctx.storage.put(k,ids.slice(0,100)); return p;
  }
  async projects(deviceId){
    const ids=(await this.ctx.storage.get("pl:"+deviceId))||[], out=[];
    for(const id of ids){ const p=await this.ctx.storage.get("p:"+deviceId+":"+id); if(p) out.push(p); }
    return out;
  }
  async project(deviceId,id){ return (await this.ctx.storage.get("p:"+deviceId+":"+id))||null; }
  async update(deviceId,id,patch={},expectedRevision){
    if(!patch||typeof patch!=="object"||Array.isArray(patch))throw studioError("Project patch must be an object","invalid_project_request");
    requireStudioBudget(patch,STUDIO_PROJECT_BYTES);
    const p=await this.ctx.storage.transaction(async transaction=>{
      const key="p:"+deviceId+":"+id,current=await transaction.get(key);if(!current)return null;
      if(current.deviceId!==deviceId)throw studioError("Project device capability does not match","project_owner_conflict",409);
      const revision=current.revision??0;
      if(!Number.isSafeInteger(revision)||revision<0)throw studioError("Stored project revision requires recovery","invalid_project_revision");
      if(expectedRevision!==undefined&&(!Number.isSafeInteger(expectedRevision)||expectedRevision<0))throw studioError("expectedRevision must be a nonnegative safe integer","invalid_project_revision");
      if(expectedRevision!==undefined&&expectedRevision!==revision)throw studioError("Project changed since the accepted edit","revision_conflict",409,{expectedRevision,actualRevision:revision,projectId:id});
      const next=JSON.parse(JSON.stringify(current));let content=false;
      for(const k of ["name","instruction","assets","timeline","settings","drive","generation","latestRender","latestCommand"])if(patch[k]!==undefined){next[k]=JSON.parse(JSON.stringify(patch[k]));if(k!=="latestCommand")content=true;}
      next.revision=revision+(content?1:0);if(!Number.isSafeInteger(next.revision))throw studioError("Project revision exhausted its safe integer bound","invalid_project_revision");
      next.updatedAt=now();requireStudioBudget(next,STUDIO_PROJECT_BYTES);await transaction.put(key,next);return next;
    });
    if(p)await this.register(deviceId);return p;
  }
  async appendGenerated(deviceId,id,input){
    if(!input||typeof input!=="object"||Array.isArray(input))throw studioError("Generated metadata is required","invalid_generated_metadata");
    requireStudioBudget(input,STUDIO_APPEND_BYTES);
    const additions=input.assets??[],clips=input.timeline??[],generation=input.generation??{};
    if(!Array.isArray(additions)||additions.length>64||!Array.isArray(clips)||clips.length>128||(!additions.length&&!clips.length)
      ||!generation||typeof generation!=="object"||Array.isArray(generation))throw studioError("Provide bounded generated assets and timeline additions","invalid_generated_metadata");
    const stable=value=>typeof value==="string"&&/^[A-Za-z0-9][A-Za-z0-9._:-]{0,179}$/.test(value);
    const assetIds=new Set(),clipIds=new Set();
    for(const asset of additions){if(!asset||!stable(asset.id)||assetIds.has(asset.id)||asset.generated!==true)throw studioError("Generated assets need distinct stable IDs and generated:true","invalid_generated_metadata");assetIds.add(asset.id);}
    for(const clip of clips){if(!clip||!stable(clip.id)||clipIds.has(clip.id)||!stable(clip.assetId))throw studioError("Generated clips need distinct stable IDs and an explicit assetId","invalid_generated_metadata");clipIds.add(clip.id);}
    const result=await this.ctx.storage.transaction(async transaction=>{
      const key="p:"+deviceId+":"+id,current=await transaction.get(key);if(!current)return null;
      if(current.deviceId!==deviceId)throw studioError("Project device capability does not match","project_owner_conflict",409);
      if(!Array.isArray(current.assets)||!Array.isArray(current.timeline))throw studioError("Stored project graph requires recovery","invalid_project_graph");
      const next=JSON.parse(JSON.stringify(current)),ownedAssets=new Map(next.assets.map(asset=>[asset.id,asset])),ownedClips=new Map(next.timeline.filter(clip=>clip.id).map(clip=>[clip.id,clip]));let addedAssets=0,addedClips=0;
      for(const asset of additions){const existing=ownedAssets.get(asset.id);if(existing&&!sameJson(existing,asset))throw studioError("Generated asset ID belongs to different metadata","generated_id_conflict",409,{assetId:asset.id});if(!existing){const copy=JSON.parse(JSON.stringify(asset));next.assets.push(copy);ownedAssets.set(copy.id,copy);addedAssets++;}}
      for(const clip of clips){if(!ownedAssets.has(clip.assetId))throw studioError("Generated clip references media outside this project","invalid_generated_metadata");const existing=ownedClips.get(clip.id);if(existing&&!sameJson(existing,clip))throw studioError("Generated clip ID belongs to a different edit","generated_id_conflict",409,{clipId:clip.id});if(!existing){const copy=JSON.parse(JSON.stringify(clip));next.timeline.push(copy);ownedClips.set(copy.id,copy);addedClips++;}}
      if(!addedAssets&&!addedClips)return {project:current,reused:true,addedAssets:0,addedClips:0};
      const revision=current.revision??0;if(!Number.isSafeInteger(revision)||revision<0||!Number.isSafeInteger(revision+1))throw studioError("Stored project revision requires recovery","invalid_project_revision");
      next.generation={...(next.generation||{}),...JSON.parse(JSON.stringify(generation))};next.revision=revision+1;next.updatedAt=now();requireStudioBudget(next,STUDIO_PROJECT_BYTES);
      await transaction.put(key,next);return {project:next,reused:false,addedAssets,addedClips};
    });
    if(result)await this.register(deviceId);return result;
  }
  async enqueue(deviceId,projectId,action,parameters={}){
    parameters=definedFields(parameters);
    if(!(await this.project(deviceId,projectId))) throw new Error("Project not found");
    const c=await this.enqueueStoredCommand("cl:"+deviceId,"seq:"+deviceId,{id:crypto.randomUUID(),deviceId,projectId,action,parameters,status:"queued",createdAt:now(),completedAt:null,result:null});
    const hint=await this.updateCommandHint(deviceId,projectId,c); return {...c,projectMetadataHintUpdated:hint};
  }
  async commands(deviceId,after=0){ const a=await readCommandQueue(this.ctx.storage,"cl:"+deviceId); return a.filter(c=>commandIsPending(c)||c.seq>Number(after||0)); }
  async command(deviceId,id){ const key="cl:"+deviceId,a=await readCommandQueue(this.ctx.storage,key); return a.find(c=>c.id===id)||await readCommandReceipt(this.ctx.storage,key,id); }
  async complete(deviceId,id,result={},status="completed"){
    const c=await mutateCommandQueue(this.ctx.storage,"cl:"+deviceId,async(a,transaction)=>{const i=a.findIndex(c=>c.id===id); if(i<0) return {rows:a,changed:false,result:await acknowledgePrunedCommand(transaction,"cl:"+deviceId,id,result,clean(status,40)||"completed")};
    const replay=!commandIsPending(a[i]);
    a[i]=await terminalCommandUpdate(a[i],result,clean(status,40)||"completed");
    return {rows:a,result:{...a[i],result,resultVerified:true,...(replay?{resultReplayed:true}:{})}};});
    if(!c)return null;
    const hint=await this.updateCommandHint(deviceId,c.projectId,c); return {...c,projectMetadataHintUpdated:hint};
  }
  async enqueueRuntime(deviceId,projectId,action,parameters={}){
    parameters=definedFields(parameters);
    if(!(await this.project(deviceId,projectId))) throw new Error("Project not found");
    const c=await this.enqueueStoredCommand("rcl:"+deviceId,"rseq:"+deviceId,{id:crypto.randomUUID(),deviceId,projectId,action,parameters,status:"queued",createdAt:now(),completedAt:null,result:null,runtime:"studio-web"});
    const hint=await this.updateCommandHint(deviceId,projectId,c);
    return {...c,projectMetadataHintUpdated:hint};
  }
  async runtimeCommands(deviceId,after=0){
    const a=await readCommandQueue(this.ctx.storage,"rcl:"+deviceId);
    return a.filter(c=>commandIsPending(c)||c.seq>Number(after||0));
  }
  async runtimeCommand(deviceId,id){
    const a=await readCommandQueue(this.ctx.storage,"rcl:"+deviceId);
    return a.find(c=>c.id===id)||await readCommandReceipt(this.ctx.storage,"rcl:"+deviceId,id);
  }
  async completeRuntime(deviceId,id,result={},status="completed"){
    const c=await mutateCommandQueue(this.ctx.storage,"rcl:"+deviceId,async(a,transaction)=>{const i=a.findIndex(c=>c.id===id);
    if(i<0) return {rows:a,changed:false,result:await acknowledgePrunedCommand(transaction,"rcl:"+deviceId,id,result,clean(status,40)||"completed")};
    const replay=!commandIsPending(a[i]);
    a[i]=await terminalCommandUpdate(a[i],result,clean(status,40)||"completed");
    return {rows:a,result:{...a[i],result,resultVerified:true,...(replay?{resultReplayed:true}:{})}};});
    if(!c)return null;
    const hint=await this.updateCommandHint(deviceId,c.projectId,c);
    return {...c,projectMetadataHintUpdated:hint};
  }

  async updateCommandHint(deviceId,projectId,command){
    // Queue acceptance/receipt is already durable. A full or deleted project
    // must not turn its acknowledgement into an apparent command failure.
    try{return Boolean(await this.update(deviceId,projectId,{latestCommand:studioCommandHint(command)}));}
    catch{return false;}
  }

  async status(deviceId){
    const d=await this.device(deviceId), ps=await this.projects(deviceId), a=await readCommandQueue(this.ctx.storage,"cl:"+deviceId);
    const ra=await readCommandQueue(this.ctx.storage,"rcl:"+deviceId); return {connected:!!d,device:d,projectCount:ps.length,pendingCommands:a.filter(c=>c.status==="queued").length,pendingRuntimeCommands:ra.filter(c=>c.status==="queued").length,lastCommand:(ra[ra.length-1]||a[a.length-1]||null)};
  }

  async enqueueStoredCommand(key,sequenceKey,command){
    return mutateCommandQueue(this.ctx.storage,key,async(rows,transaction)=>{
      const seq=Math.max(Number((await transaction.get(sequenceKey))||0),...rows.map(row=>row.seq),0)+1;
      if(!Number.isSafeInteger(seq))throw new Error("Command sequence exceeds its safe integer bound");
      const accepted={...command,seq};
      requireCommandQueueAdmission(rows,accepted);
      rows.push(accepted);await transaction.put(sequenceKey,seq);
      return {rows,result:accepted};
    });
  }

  async appRegister(deviceId,ownerKey,meta={}){
    if(!deviceId||String(deviceId).length<8) throw new Error("Invalid native device ID");
    if(!ownerKey||String(ownerKey).length<32) throw new Error("Invalid owner key");
    const hash=await sha256Hex(ownerKey), key="app-owner:"+hash;
    const bound=await this.ctx.storage.get(key);
    const dk="app-device:"+deviceId, old=(await this.ctx.storage.get(dk))||{};
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
      projects:Array.isArray(meta.projects)?meta.projects.slice(0,100):(old.projects||[]),
      controlPaused:!!meta.controlPaused,
      connectionSession:clean(meta.connectionSession||old.connectionSession||"",80),
      galleryAccess:false,
      createdAt:old.createdAt||now(),
      lastSeenAt:now(),
      nativeApp:true,
      ownerHash:hash
    };
    await this.ctx.storage.put(key,deviceId);
    await this.ctx.storage.put(dk,d);
    const {ownerHash,...safe}=d;
    return safe;
  }
  async appResolve(ownerKey){
    if(!ownerKey) return null;
    const hash=await sha256Hex(ownerKey), id=await this.ctx.storage.get("app-owner:"+hash);
    if(!id) return null;
    let device=(await this.ctx.storage.get("app-device:"+id))||null;
    if(device&&device.supersededByDeviceId){
      const canonical=(await this.ctx.storage.get("app-device:"+device.supersededByDeviceId))||null;
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
      const candidate=await this.ctx.storage.get("app-device:"+String(primaryDeviceId));
      if(candidate&&candidate.ownerHash===primaryHash){
        canonicalId=candidate.deviceId;
        await this.ctx.storage.put("app-owner:"+primaryHash,canonicalId);
      }
    }
    if(!canonicalId) throw new Error("Primary native identity could not be proven");
    let canonical=await this.ctx.storage.get("app-device:"+canonicalId);
    if(!canonical) throw new Error("Canonical native device record is missing");
    if(canonical.supersededByDeviceId){
      const next=await this.ctx.storage.get("app-device:"+canonical.supersededByDeviceId);
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
      const legacy=await this.ctx.storage.get("app-device:"+id);
      if(!legacy) throw new Error("Legacy native device record is missing");

      await this.ctx.storage.transaction(async transaction=>{
      const currentRows=await readCommandQueueInTransaction(transaction,"app-v3-cl:"+canonicalId);
      const legacyRows=await readCommandQueueInTransaction(transaction,"app-v3-cl:"+id);
      const receiptMerge=await mergeCommandReceiptsInTransaction(transaction,"app-v3-cl:"+canonicalId,"app-v3-cl:"+id);
      const byId=new Map(currentRows.filter(Boolean).map(row=>[row.id,row]));
      const completedProofs=new Map((await readCommandReceiptsInTransaction(transaction,"app-v3-cl:"+canonicalId)).map(row=>[row.id,row]));
      let seq=Math.max(
        Number((await transaction.get("app-v3-seq:"+canonicalId))||0),
        receiptMerge.highestSeq,
        ...currentRows.map(row=>Number(row&&row.seq||0)),
        0
      );
      for(const row of legacyRows){
        if(!row||!row.id) continue;
        const existing=byId.get(row.id);
        if(existing){
          if(commandIsPending(existing)&&!commandIsPending(row))
            throw new Error("Legacy completion conflicts with an active canonical command; alias queues are preserved for recovery");
          if(!commandIsPending(existing)&&!commandIsPending(row)
              &&(existing.status!==row.status||await canonicalCommandResultSha256(existing.result)!==await canonicalCommandResultSha256(row.result)))
            throw new Error("Conflicting terminal alias command receipts are preserved for recovery");
          if(commandIsPending(existing)&&commandIsPending(row)
              &&(existing.action!==row.action||!sameJson(existing.parameters,row.parameters)))
            throw new Error("Conflicting active alias command identity is preserved for recovery");
          continue;
        }
        const proof=completedProofs.get(row.id);
        if(proof){
          if(!commandIsPending(row)&&(proof.status!==row.status||proof.resultSha256!==await canonicalCommandResultSha256(row.result)))
            throw new Error("Legacy terminal command conflicts with retained canonical proof; alias queues are preserved for recovery");
          continue;
        }
        seq++;
        byId.set(row.id,{
          ...row,
          seq,
          deviceId:canonicalId,
          migratedFromDeviceId:id,
          migratedAt:now()
        });
      }
      const merged=[...byId.values()].sort((a,b)=>Number(a.seq||0)-Number(b.seq||0));
      await writeCommandQueueInTransaction(transaction,"app-v3-cl:"+canonicalId,merged,new Set(merged.filter(row=>!commandIsPending(row)).map(row=>row.id)));
      await transaction.put("app-v3-seq:"+canonicalId,seq);
      });

      legacy.supersededByDeviceId=canonicalId;
      legacy.supersededAt=now();
      legacy.canonicalIdentity=false;
      await this.ctx.storage.put("app-device:"+id,legacy);
      await this.ctx.storage.put("app-owner:"+hash,canonicalId);
    }

    canonical.ownerAliases=[...aliases];
    canonical.canonicalIdentity=true;
    canonical.identityConvergedAt=now();
    canonical.identityAliasCount=canonical.ownerAliases.length;
    await this.ctx.storage.put("app-device:"+canonicalId,canonical);
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
      command=await this.enqueue(webDeviceId,projectId,"autonomous_request",definedFields({
        instruction:p.instruction||"",
        clips:Array.isArray(p.clips)?p.clips:undefined,
        aspect:p.aspect,
        quality:p.quality,
        transition:p.transition,
        title:p.title,
        mute:p.mute,
        render:p.render!==false,
        inspectAfterRender:!!p.inspectAfterRender
      }));
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
    const native=await this.ctx.storage.get("app-device:"+record.nativeDeviceId);
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
    const binding=await this.ctx.storage.get("hybrid-binding:"+hybridHash);
    if(!binding||binding.revoked) return null;
    const native=await this.ctx.storage.get("app-device:"+binding.nativeDeviceId);
    if(!native) return null;
    binding.lastUsedAt=Date.now();
    await this.ctx.storage.put("hybrid-binding:"+hybridHash,binding);
    const {ownerHash,...safeBinding}=binding;
    const {ownerHash:nativeOwnerHash,...safeNative}=native;
    return {binding:safeBinding,native:safeNative};
  }
  async appEnqueueHybrid(hybridKey,action,parameters={}){
    const resolved=await this.appResolveHybrid(hybridKey);
    if(!resolved) throw new Error("Private hybrid binding rejected");
    const d=resolved.native;
    const min=Number(d.protocolMin||d.protocolVersion||0), max=Number(d.protocolMax||d.protocolVersion||0);
    if(!(min<=3&&max>=3)) throw new Error("Hybrid native device does not support MCP v3");
    if(d.controlPaused) throw new Error("ChatGPT control is paused on the phone");
    if(!appActionAllowed(d.permissionMode,action)) throw new Error("Action blocked by device permission mode or Gallery privacy boundary: "+d.permissionMode);
    parameters={...parameters};for(const key of Object.keys(parameters))if(key.startsWith("_")||parameters[key]===undefined)delete parameters[key];
    const lastSeenMs=Date.parse(String(d.lastSeenAt||""))||0;
    const fresh=lastSeenMs>0&&(Date.now()-lastSeenMs)<=45000;
    const command=await this.enqueueStoredCommand("app-v3-cl:"+d.deviceId,"app-v3-seq:"+d.deviceId,{
      id:crypto.randomUUID(),
      protocolVersion:3,
      deviceId:d.deviceId,
      action,
      parameters,
      status:fresh?"queued":"waiting_native",
      waitingReason:fresh?"":"native_offline",
      createdAt:now(),
      completedAt:null,
      result:null
    });
    return command;
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
    const pending=list.filter(c=>c.status==="queued"||c.status==="claimed"||c.status==="waiting_native").length;
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
      waitingNative:!nativeFresh&&pending>0,
      lastCommand:list[list.length-1]||null,
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
    const device=(await this.ctx.storage.get("app-device:"+deviceId))||{};
    device.reboundFromDeviceId=record.oldDeviceId||"";
    device.reboundAt=now();
    device.stableEndpointAliases=2;
    await this.ctx.storage.put("app-device:"+deviceId,device);

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
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    if(d.controlPaused) throw new Error("ChatGPT control is paused on the phone");
    if(!appActionAllowed(d.permissionMode,action)) throw new Error("Action blocked by device permission mode or Gallery privacy boundary: "+d.permissionMode);
    parameters={...parameters};for(const key of Object.keys(parameters))if(key.startsWith("_")||parameters[key]===undefined)delete parameters[key];
    const c=await this.enqueueStoredCommand("app-cl:"+d.deviceId,"app-seq:"+d.deviceId,{id:crypto.randomUUID(),deviceId:d.deviceId,action,parameters,status:"queued",createdAt:now(),completedAt:null,result:null});
    return c;
  }
  async appCommands(deviceId,ownerKey,after=0,waitMs=0){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const until=Date.now()+Math.max(0,Math.min(20000,Number(waitMs||0))), key="app-cl:"+deviceId;
    while(true){
      const found=await mutateCommandQueue(this.ctx.storage,key,list=>{const nowMs=Date.now(),found=[];
      let changed=false;
      for(let i=0;i<list.length;i++){
        const c=list[i];
        // A later ack must never hide earlier unacknowledged commands.
        // Terminal records are filtered by status; queued/expired leases are always eligible.
        const expired=c.status==="claimed"&&Number(c.leaseUntil||0)<=nowMs;
        if(c.status==="queued"||c.status==="waiting_native"||expired){
          list[i]={...c,status:"claimed",claimedAt:now(),leaseUntil:nowMs+45000};
          found.push(list[i]);
          changed=true;
          if(found.length>=4) break;
        }
      }
      return {rows:list,result:found,changed};});
      if(found.length||Date.now()>=until) return found;
      await new Promise(resolve=>setTimeout(resolve,650));
    }
  }
  async appComplete(deviceId,ownerKey,id,result={},status="completed"){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const completed=await mutateCommandQueue(this.ctx.storage,"app-cl:"+deviceId,async(list,transaction)=>{const i=list.findIndex(c=>c.id===id);
    if(i<0) return {rows:list,changed:false,result:await acknowledgePrunedCommand(transaction,"app-cl:"+deviceId,id,result,clean(status,30)||"completed")};
    const replay=!commandIsPending(list[i]);
    list[i]=await terminalCommandUpdate(list[i],result,clean(status,30)||"completed");
    return {rows:list,result:{...list[i],result,resultVerified:true,...(replay?{resultReplayed:true}:{})}};});
    const d=(await this.ctx.storage.get("app-device:"+deviceId))||{};
    d.lastSeenAt=now();
    await this.ctx.storage.put("app-device:"+deviceId,d);
    return completed;
  }
  async appCommand(ownerKey,id){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    const list=await readCommandQueue(this.ctx.storage,"app-cl:"+d.deviceId);
    return list.find(c=>c.id===id)||await readCommandReceipt(this.ctx.storage,"app-cl:"+d.deviceId,id);
  }
  async appStatus(ownerKey){
    const d=await this.appResolve(ownerKey);
    if(!d) return {connected:false,error:"Private App MCP credential rejected"};
    const list=await readCommandQueue(this.ctx.storage,"app-cl:"+d.deviceId);
    return {
      connected:true,
      device:((({ownerHash,...safe})=>safe)(d)),
      pendingCommands:list.filter(c=>c.status==="queued"||c.status==="claimed"||c.status==="waiting_native").length,
      lastCommand:list[list.length-1]||null,
      projectCount:Number.isSafeInteger(d.projectCount)?d.projectCount:Array.isArray(d.projects)?d.projects.length:0,
      projectsTruncated:!!d.projectsTruncated
    };
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
    parameters={...parameters};
    for(const key of Object.keys(parameters)) if(key.startsWith("_")||parameters[key]===undefined) delete parameters[key];
    if(action==="import_attachment"||(action==="import_chat_file"&&!parameters.handoffId)){
      return queueAttachment(this,ownerKey,parameters.file||{download_url:parameters.sourceUrl||parameters.url,file_name:parameters.name,mime_type:parameters.mime,size:parameters.size||0,sha256:parameters.sha256},parameters.projectId||"",true);
    }
    if(action==="import_inline_base64") throw new Error("Inline bytes must be staged by app_import_inline_base64 or app_execute before entering the durable queue");
    const d=await this.appV3Device(ownerKey);
    if(d.controlPaused) throw new Error("ChatGPT control is paused on the phone");
    if(!appActionAllowed(d.permissionMode,action)) throw new Error("Action blocked by device permission mode or Gallery privacy boundary: "+d.permissionMode);
    const lastSeenMs=Date.parse(String(d.lastSeenAt||""))||0;
    const fresh=lastSeenMs>0&&(Date.now()-lastSeenMs)<=45000;
    if(!fresh){
      const webCommand=await this.appTryStudioWebFallback(ownerKey,action,parameters);
      if(webCommand) return webCommand;
    }
    const c=await this.enqueueStoredCommand("app-v3-cl:"+d.deviceId,"app-v3-seq:"+d.deviceId,{
      id:crypto.randomUUID(),
      protocolVersion:3,
      deviceId:d.deviceId,
      action,
      parameters,
      status:fresh?"queued":"waiting_native",
      waitingReason:fresh?"":"native_offline",
      createdAt:now(),
      completedAt:null,
      result:null
    });
    return c;
  }
  async appCommandsV3(deviceId,ownerKey,after=0,waitMs=0){
    const d=await this.appAuth(deviceId,ownerKey);
    if(!d) throw new Error("VideoStudio v3 native authorization failed");
    const min=Number(d.protocolMin||d.protocolVersion||0), max=Number(d.protocolMax||d.protocolVersion||0);
    if(!(min<=3&&max>=3)) throw new Error("VideoStudio stable MCP compatibility lane v3 is not registered");
    const until=Date.now()+Math.max(0,Math.min(20000,Number(waitMs||0)));
    const key="app-v3-cl:"+deviceId;
    while(true){
      const found=await mutateCommandQueue(this.ctx.storage,key,list=>{const nowMs=Date.now(),found=[];
      let changed=false;
      for(let i=0;i<list.length;i++){
        const c=list[i];
        // A later ack must never hide earlier unacknowledged commands.
        // Terminal records are filtered by status; queued/expired leases are always eligible.
        const expired=c.status==="claimed"&&Number(c.leaseUntil||0)<=nowMs;
        if(c.status==="queued"||c.status==="waiting_native"||expired){
          list[i]={...c,status:"claimed",waitingReason:"",claimedAt:now(),leaseUntil:nowMs+60000,claimCount:Number(c.claimCount||0)+1};
          found.push(list[i]);
          changed=true;
          if(found.length>=4) break;
        }
      }
      return {rows:list,result:found,changed};});
      if(found.length||Date.now()>=until) return found;
      await new Promise(resolve=>setTimeout(resolve,500));
    }
  }
  async appCompleteV3(deviceId,ownerKey,id,result={},status="completed"){
    const d=await this.appAuth(deviceId,ownerKey);
    if(!d) throw new Error("VideoStudio v3 native authorization failed");
    const min=Number(d.protocolMin||d.protocolVersion||0), max=Number(d.protocolMax||d.protocolVersion||0);
    if(!(min<=3&&max>=3)) throw new Error("VideoStudio stable MCP compatibility lane v3 is not registered");
    const completed=await mutateCommandQueue(this.ctx.storage,"app-v3-cl:"+deviceId,async(list,transaction)=>{const i=list.findIndex(c=>c.id===id);
    if(i<0) return {rows:list,changed:false,result:await acknowledgePrunedCommand(transaction,"app-v3-cl:"+deviceId,id,result,clean(status,30)||"completed")};
    const completedParameters={...(list[i].parameters||{})};
    if(list[i].action==="import_attachment"&&completedParameters.sourceUrl){
      completedParameters.sourceUrl="[expired temporary file URL removed]";
    }
    const replay=!commandIsPending(list[i]);
    list[i]=await terminalCommandUpdate(list[i],result,clean(status,30)||"completed",completedParameters);
    return {rows:list,result:{...list[i],result,resultVerified:true,...(replay?{resultReplayed:true}:{})}};});
    if(!completed)return null;
    if(completed.action==="import_chat_file"&&status==="completed"&&completed.parameters?.handoffId)
      await this.appDeleteHandoff(deviceId,ownerKey,completed.parameters.handoffId);
    const stored=(await this.ctx.storage.get("app-device:"+deviceId))||d;
    stored.lastSeenAt=now();
    await this.ctx.storage.put("app-device:"+deviceId,stored);
    return completed;
  }
  async appCommandV3(ownerKey,id){
    const d=await this.appV3Device(ownerKey);
    const list=await readCommandQueue(this.ctx.storage,"app-v3-cl:"+d.deviceId);
    const native=list.find(c=>c.id===id)||await readCommandReceipt(this.ctx.storage,"app-v3-cl:"+d.deviceId,id);
    if(native) return native;
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
    const pending=list.filter(c=>c.status==="queued"||c.status==="claimed"||c.status==="waiting_native");
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
      pendingCommands:pending.length,
      waitingNativeCommands:waitingNative,
      canAcceptAutonomousWork:true,
      queuedExecutionPolicy:fallbackConnected?"studio-web-when-compatible-otherwise-native-on-reconnect":"native-on-reconnect",
      lastCommand:list[list.length-1]||null,
      projectCount:Number.isSafeInteger(d.projectCount)?d.projectCount:Array.isArray(d.projects)?d.projects.length:0,
      projectsTruncated:!!d.projectsTruncated,
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
    if(d.controlPaused) throw new Error("ChatGPT control is paused on the phone");
    if(d.permissionMode==="one_file") throw new Error("Chat-file import is unavailable while One File Lock is active");
    const validatedSource=publicAttachmentUrl(sourceUrl);
    const id=crypto.randomUUID();
    const record={
      id,
      deviceId:d.deviceId,
      sourceUrl:validatedSource,
      name:clean(meta.name||"ChatGPT import",180),
      mime:clean(meta.mime||"",120),
      size:Number(meta.size||0)||0,
      createdAt:now(),
      expiresAt:Date.now()+ATTACHMENT_TTL_MS
    };
    await this.ctx.storage.put("app-handoff:"+d.deviceId+":"+id,record);
    return {id,name:record.name,mime:record.mime,size:record.size,expiresAt:record.expiresAt};
  }
  async appQueueAttachmentHandoff(ownerKey,file={},projectId=""){
    return queueAttachment(this,ownerKey,file,projectId,true);
  }

  async appCreateCachedHandoff(ownerKey,cacheUrl,meta={}){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    if(d.controlPaused) throw new Error("ChatGPT control is paused on the phone");
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
      expiresAt:Date.now()+ATTACHMENT_TTL_MS
    };
    await this.ctx.storage.put("app-handoff:"+d.deviceId+":"+id,record);
    return {id,name:record.name,mime:record.mime,size:record.size,expiresAt:record.expiresAt};
  }
  async appHandoff(deviceId,ownerKey,id){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const key="app-handoff:"+deviceId+":"+id, record=await this.ctx.storage.get(key);
    if(!record) return null;
    if(Number(record.expiresAt||0)<Date.now()){
      await this.ctx.storage.delete(key);
      return null;
    }
    return record;
  }
  async appDeleteHandoff(deviceId,ownerKey,id){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    await this.ctx.storage.delete("app-handoff:"+deviceId+":"+id);
  }
}

const state = env => env.VIDEO_STATE.getByName("primary");
const out = x => ({content:[{type:"text",text:JSON.stringify(x)}]});
const isNativeV3 = d => !!d && Number(d.protocolVersion||0)===3;
const enqueueNative = (st,d,ownerKey,action,parameters={}) => {
  if(action==="import_attachment"||(action==="import_chat_file"&&!parameters.handoffId)){
    return queueAttachment(st,ownerKey,parameters.file||{download_url:parameters.sourceUrl||parameters.url,file_name:parameters.name,mime_type:parameters.mime,size:parameters.size||0,sha256:parameters.sha256},parameters.projectId||"",isNativeV3(d));
  }
  return isNativeV3(d)?st.appEnqueueV3(ownerKey,action,parameters):st.appEnqueue(ownerKey,action,parameters);
};
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
      mode:z.enum(["prompt_scene","image_motion","story_video","video_restyle","motion_graphics","procedural_3d","audio_visualizer","abstract_vfx"]),
      prompt:z.string().max(2000).optional(),
      style:z.enum(["cinematic","dreamy","neon","film","mono"]).optional(),
      duration:z.number().min(1).max(60).optional(),
      fps:z.number().int().min(12).max(60).optional(),
      aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),
      quality:z.enum(["720p","1080p"]).optional(),
      assetId:z.string().min(8).optional(),
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
      const sheet=c.result&&c.result.contactSheet;
      if(sheet&&sheet.base64){
        const safeResult={...c.result,contactSheet:{...sheet,base64:undefined}};
        return {content:[
          {type:"text",text:JSON.stringify({...c,result:safeResult})},
          {type:"image",data:sheet.base64,mimeType:sheet.mimeType||"image/jpeg"}
        ]};
      }
      return out(c);
    }
    const c=await st.command(deviceId,commandId);
    if(!c) return out({error:"Command not found"});
    const sheet=c.result&&c.result.contactSheet;
    if(sheet&&sheet.base64){
      const safeResult={...c.result,contactSheet:{...sheet,base64:undefined}};
      const safeCommand={...c,result:safeResult};
      return {content:[
        {type:"text",text:JSON.stringify(safeCommand)},
        {type:"image",data:sheet.base64,mimeType:sheet.mimeType||"image/jpeg"}
      ]};
    }
    return out(c);
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
      const c=await queueAttachment(st,deviceId,{download_url:sourceUrl,file_name:name,mime_type:mime,size:size||0},projectId||"",isNativeV3(native));
      return out({queued:true,commandId:c.id,sequence:c.seq,nativeApp:true,protocolVersion:isNativeV3(native)?3:1,transport:"private-worker-handoff",expiresAt:c.attachmentExpiresAt});
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
        const sheet=command.result&&command.result.contactSheet;
        if(sheet&&sheet.base64){
          const safeResult={...command.result,contactSheet:{...sheet,base64:undefined}};
          return {content:[
            {type:"text",text:JSON.stringify({...command,result:safeResult,hybrid:true})},
            {type:"image",data:sheet.base64,mimeType:sheet.mimeType||"image/jpeg"}
          ]};
        }
        return out({...command,hybrid:true});
      }catch(e){ return out({error:e.message,hybrid:true}); }
    });

    s.registerTool("hybrid_revoke_native_binding",{
      description:"Revoke this permanent Studio Web to Android native-control binding. This does not delete VideoStudio projects or media.",
      inputSchema:{confirm:z.literal(true)}
    },async()=>out(await st.appRevokeHybrid(hybridKey)));
  }

  s.registerTool("video_project_plan",{description:"Create a short autonomous editing workflow.",inputSchema:{projectName:z.string().min(1),instruction:z.string().min(1)}},async({projectName,instruction})=>out({projectName,instruction,status:"planned",workflow:["inspect asset metadata","run 12-frame scene and quiet-section analysis","visually inspect sampled frames","design a multi-cut timeline around real structural changes","apply per-clip pacing/reframing/audio only where justified","render locally","inspect the actual rendered contact sheet","iterate before declaring the edit finished"]}));
  return s;
}

function serverForApp(env,ownerKey,protocolVersion=1,requestOrigin="https://wispy-queen-f9b5.prakasharuntandon634.workers.dev"){
  const isV3=Number(protocolVersion)===3;
  const s=new McpServer({
    name:isV3?"VideoStudio-App-MCP-v3":"VideoStudio-App-MCP",
    version:isV3?"3.4.7":"1.1.2"
  }), st=state(env);
  const enqueueCommand=async(action,parameters={})=>{
    if(isV3&&action==="import_inline_base64") return queueInlineAttachment(st,ownerKey,parameters,requestOrigin);
    if(action==="import_attachment"||(action==="import_chat_file"&&!parameters.handoffId)){
      return queueAttachment(st,ownerKey,parameters.file||{
        download_url:parameters.sourceUrl||parameters.url,
        file_name:parameters.name,mime_type:parameters.mime,
        size:parameters.size,sha256:parameters.sha256
      },parameters.projectId||"",isV3);
    }
    return isV3?st.appEnqueueV3(ownerKey,action,parameters):st.appEnqueue(ownerKey,action,parameters);
  };
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
        action:c.action,
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
      const sheet=c.result&&c.result.contactSheet;
      if(sheet&&sheet.base64){
        const safeResult={...c.result,contactSheet:{...sheet,base64:undefined}};
        return {content:[
          {type:"text",text:JSON.stringify({...c,result:safeResult})},
          {type:"image",data:sheet.base64,mimeType:sheet.mimeType||"image/jpeg"}
        ]};
      }
      return out(c);
    }catch(e){ return out({error:e.message}); }
  };

  s.registerTool("app_status",{description:isV3?"Check the VideoStudio v3 Native Agent connection, protocol version, permission mode, projects and pending native work. Gallery access is always false.":"Check the private native VideoStudio Android connection, permission mode, projects, control-pause state and pending work. Gallery access is always false.",inputSchema:{}},async()=>out(await readStatus()));
  if(isV3){
    const mirrorCall=async(operation,args)=>{
      try{return out(await st.appMetadataMirror(ownerKey,operation,args));}
      catch(error){return out({ok:false,error:error.message,executorAvailable:false,nativeAutoReconciliation:false});}
    };
    s.registerTool("app_metadata_mirror_sync",{
      description:"Explicitly opt one project into an owner-private metadata mirror. Supply the native project graph and its actual revision. Assets are metadata only: this does not upload source media, render, generate, or automatically update Android. Existing mirrors require expectedMirrorRevision; conflicts retain snapshots and current graph.",
      inputSchema:{projectId:z.string().min(8).max(180),projectGraph:z.record(z.string(),z.any()),sourceRevision:z.number().int().positive(),expectedMirrorRevision:z.number().int().nonnegative().optional(),enabled:z.literal(true)}
    },async args=>mirrorCall("sync",args));
    s.registerTool("app_metadata_mirror_get",{
      description:"Retrieve the actual mirrored project graph, source and mirror revisions, pending reconciliation flag, bounded audit and conflict snapshots. This is owner-authenticated metadata retrieval; no cloud executor or uploaded source media is claimed.",
      inputSchema:{projectId:z.string().min(8).max(180)}
    },async args=>mirrorCall("get",args));
    s.registerTool("app_metadata_mirror_edit",{
      description:"Edit an explicitly synced metadata graph while Android is offline. Only patch/insert/delete clips referencing synced assets and track flags are implemented. Requires expectedMirrorRevision and records an audit. Changed graph/revision is returned for deliberate Android reconciliation; no media execution occurs.",
      inputSchema:{projectId:z.string().min(8).max(180),expectedMirrorRevision:z.number().int().positive(),operation:z.enum(["patch_clip","insert_clip","delete_clip","track_flags"]),parameters:z.record(z.string(),z.any())}
    },async args=>mirrorCall("edit",args));
    s.registerTool("app_metadata_mirror_reconcile",{
      description:"Explicitly reconcile a native project readback with its metadata mirror. Supply the actual native revision, the mirror's prior source revision and current mirror revision. keep_native retains any displaced offline graph as a conflict snapshot and can keep an unchanged native revision while resolving a dirty mirror; acknowledge_mirror requires matching native graph readback. Android automatic apply/pull is not implemented.",
      inputSchema:{projectId:z.string().min(8).max(180),expectedMirrorRevision:z.number().int().positive(),baseSourceRevision:z.number().int().positive(),sourceRevision:z.number().int().positive(),projectGraph:z.record(z.string(),z.any()),resolution:z.enum(["keep_native","acknowledge_mirror"])}
    },async args=>mirrorCall("reconcile",args));
    s.registerTool("app_metadata_mirror_revoke",{
      description:"Disable future edits to one opted-in metadata mirror while retaining its graph and bounded audit for owner review. Native projects and media are unaffected.",
      inputSchema:{projectId:z.string().min(8).max(180),expectedMirrorRevision:z.number().int().positive()}
    },async args=>mirrorCall("revoke",args));
    s.registerTool("app_metadata_mirror_sync_native",{
      description:"Explicitly opt one Android project into the private metadata mirror using its actual current native graph and revision. Android must be connected. This transfers metadata only; source bytes and render/generation are not provided. Existing mirrors require expectedMirrorRevision.",
      inputSchema:{projectId:z.string().min(8).max(180),enabled:z.literal(true),expectedMirrorRevision:z.number().int().nonnegative().optional()}
    },async args=>queue("metadata_mirror_sync",args));
    s.registerTool("app_metadata_mirror_apply_native",{
      description:"Apply an offline mirrored metadata edit to the connected Android project through its revision-checked transaction. Requires exact mirror and native revisions, existing synced assets and supported edits. Native media remains local. A pending cloud acknowledgement is durably retained if reconciliation fails; this is not an atomic cloud commit.",
      inputSchema:{projectId:z.string().min(8).max(180),expectedMirrorRevision:z.number().int().positive(),expectedNativeRevision:z.number().int().positive()}
    },async args=>queue("metadata_mirror_apply",args));
    s.registerTool("app_metadata_mirror_retry_native",{
      description:"Explicitly retry the exact readback acknowledgement of a previously applied mirrored edit. Android retains pending revision/fingerprint evidence across restarts. Changed native or mirror state fails and preserves the conflict; this never reapplies a committed edit or discards a conflict.",
      inputSchema:{projectId:z.string().min(8).max(180)}
    },async args=>queue("metadata_mirror_retry",args));
    s.registerTool("app_metadata_mirror_status_native",{
      description:"Read Android's local metadata mirror reconciliation marker and current native revision, including an interrupted or pending acknowledgement. Source bytes remain local and no background reconciliation is implied.",
      inputSchema:{projectId:z.string().min(8).max(180)}
    },async args=>queue("metadata_mirror_status",args));
    s.registerTool("app_metadata_mirror_revoke_native",{
      description:"Explicitly disable one Android project's metadata mirror at its expected mirror revision. Pending native acknowledgements must be resolved first; project media and history are preserved.",
      inputSchema:{projectId:z.string().min(8).max(180),expectedMirrorRevision:z.number().int().positive()}
    },async args=>queue("metadata_mirror_revoke",args));
    s.registerTool("app_metadata_mirror_keep_native",{
      description:"Explicitly resolve Android's pending metadata conflict by keeping the actual native project. Requires current native/mirror revisions and the exact pendingMarker identity from status_native. The displaced offline graph and local resolution evidence are retained before clearing the marker. A failed cloud reconciliation or changed native graph preserves recovery evidence; this does not discard a conflict silently.",
      inputSchema:{projectId:z.string().min(8).max(180),expectedNativeRevision:z.number().int().positive().max(Number.MAX_SAFE_INTEGER),expectedMirrorRevision:z.number().int().positive().max(Number.MAX_SAFE_INTEGER),expectedPendingMirrorRevision:z.number().int().positive().max(Number.MAX_SAFE_INTEGER),expectedPendingFingerprint:z.string().regex(/^[a-f0-9]{64}$/),expectedPendingId:z.string().min(1).max(180)}
    },async args=>queue("metadata_mirror_keep_native",args));
  }

  s.registerTool("app_capabilities",{description:isV3?"Read VideoStudio v3 Native Agent capabilities and architecture guarantees.":"Read the native v1.1 editing, AI, render and privacy capabilities available to ChatGPT.",inputSchema:{}},async()=>out({
    version:isV3?"3.4.2":"1.1.2",
    protocolVersion:isV3?3:1,
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
      ?["ChatGPT attachment ingest when the host supplies a temporary HTTPS locator","owner-authenticated inline still-frame fallback","VideoStudio-owned media","explicit HTTPS import","manual Android picker","one registered original-source archive/restore with pinned folder grant and streamed chunks","no Gallery enumeration","legacy short-lived relay fallback"]
      :["VideoStudio-owned media","explicit HTTPS import","manual Android picker","private handoff"],
    editing:["trim","split","0.1x-16x native speed","slow motion","volume and supported native audio DSP","titles","fonts","text animations","scale","rotate","blur","colour/HSL","supported motion and transition presets","geometric masks","native chroma key","unsupported effects return diagnostics"],
    ai:["native visual analysis","scene-change sampling","bundled person segmentation","bundled face mesh","subject-aware image animation","2.5D parallax","autonomous edit plans","creator presets","prompt-to-video","multi-variant planning","short-form recut planning","render/export orchestration"],
    animation:isV3?["AI subject/background layer extraction","feathered head/hair torso and lower-drape layers","face-aware camera anchoring","multi-keyframe easing","head drift/nod","torso breathing","lower-drape sway","independent depth motion","story-shot reordering","procedural atmosphere","layered Media3 composition"]:[],
    export:["Media3 native MP4","H.264","AAC","720p","1080p","9:16","16:9","1:1","4:5","accepted graph/revision pinned through recovery","durable exact MediaStore row and full SHA-256 readback","Movies/VideoStudio"],
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

  s.registerTool("app_state",{description:"Read bounded native app diagnostics, project/asset summary pages, jobs and recent activity. Job/recovery/journal entries are compact summaries; use app_project_state for complete owned asset/clip/track/marker pages and rig/cel describe tools for their actual definitions.",inputSchema:{projectOffset:z.number().int().nonnegative().max(2147483647).optional(),projectLimit:z.number().int().min(1).max(100).optional(),assetOffset:z.number().int().nonnegative().max(2147483647).optional(),assetLimit:z.number().int().min(1).max(100).optional()}},async args=>queue("get_state",args));
  if(isV3)s.registerTool("app_project_state",{description:"Read complete registered native project entries as bounded pages: assets, clips (including actual effects), tracks or markers. Pages include project revision, exact duration, cadence and rehearsal-range status. Reuse expectedRevision across pages to reject concurrent edits. Owned media URIs are project references; Gallery enumeration is unavailable.",inputSchema:{projectId:z.string().min(8).max(180).optional(),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),section:z.enum(["assets","clips","tracks","markers"]).optional(),offset:z.number().int().nonnegative().max(2147483647).optional(),limit:z.number().int().min(1).max(50).optional()}},async args=>queue("project_state",args));
  if(isV3) s.registerTool("app_generate_image",{description:"Generate an original local procedural image from a sceneGraph or supported scene prompt. Not photorealistic diffusion. Registers output in the app Media Bin.",inputSchema:{prompt:z.string().max(10000).optional(),sceneGraph:z.record(z.string(),z.any()).optional(),projectId:z.string().min(8).optional(),width:z.number().int().min(128).max(1920).optional(),height:z.number().int().min(128).max(1920).optional(),appendToTimeline:z.boolean().optional()}},async args=>queue("generate_image",args));
  if(isV3) s.registerTool("app_self_test",{description:"Run VideoStudio v3's on-device native self-test before autonomous work. Verifies protocol v3, app-private storage, local project state, job/render/analysis engines, direct attachment ingest and the no-Gallery boundary.",inputSchema:{}},async()=>queue("self_test",{}));
  s.registerTool("app_activity_note",{description:"Post a live progress message into VideoStudio's ChatGPT Activity screen. Use this to mirror autonomous-work updates such as planning, analysing, applying edits, rendering or retrying.",inputSchema:{title:z.string().min(1).max(120),message:z.string().min(1).max(500),status:z.enum(["info","queued","running","success","failed"]).optional(),progress:z.number().int().min(0).max(100).optional(),projectId:z.string().min(8).optional()}},async args=>queue("activity_note",args));
  s.registerTool("app_create_project",{description:"Create a native VideoStudio project.",inputSchema:{name:z.string().min(1).max(120)}},async({name})=>queue("create_project",{name}));
  if(isV3) s.registerTool("app_create_title",{
    description:"Create a real editable title on an owned transparent canvas using the same native text renderer and project transaction as the human editor. The existing active project is used when projectId is omitted; expectedRevision rejects stale edits. Duration is 100 to 3600000 ms. Style settings use supported native fonts, animations, transforms, keyframes and effects, bounded to 32 KB. Replaying the same native command recovers its committed title. Metadata mirrors separately limit clip titles to 1000 characters.",
    inputSchema:{projectId:z.string().min(8).max(180).optional(),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),text:z.string().min(1).max(2000),startMs:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),durationMs:z.number().int().min(100).max(3600000).optional(),style:z.object({fontFamily:z.string().max(80).optional(),textAnimation:z.string().max(80).optional(),textColor:z.string().max(40).optional(),textSize:z.number().min(.02).max(.15).optional(),textY:z.number().min(.05).max(.95).optional()}).passthrough().optional()}
  },async args=>queue("create_title",args));
  s.registerTool("app_select_project",{description:"Select an existing native VideoStudio project by ID.",inputSchema:{projectId:z.string().min(8)}},async({projectId})=>queue("select_project",{projectId}));
  s.registerTool("app_delete_project",{description:"Delete a VideoStudio-owned project. Available in Full Autonomous mode; Gallery enumeration remains blocked.",inputSchema:{projectId:z.string().min(8)}},async({projectId})=>queue("delete_project",{projectId}));

  s.registerTool("app_analyse_media",{description:"Sample a local imported video on-device and return a contact sheet plus scene-change candidates to ChatGPT. Full video stays on the phone.",inputSchema:{assetId:z.string().min(8).optional(),frames:z.number().int().min(6).max(16).optional(),start:z.number().min(0).optional(),end:z.number().positive().optional()}},async({assetId,frames,start,end})=>{
    const p={frames:frames||12}; if(assetId)p.assetId=assetId; if(start!=null)p.start=start; if(end!=null)p.end=end;
    return queue("analyse_media",p);
  });

  s.registerTool("app_apply_edit_plan",{description:"Replace the active project's timeline with a structured multi-cut plan referencing already imported local asset IDs.",inputSchema:{clips:z.array(z.record(z.string(),z.any())).min(1).max(80)}},async({clips})=>queue("apply_edit_plan",{clips}));

  s.registerTool("app_apply_tool",{description:"Apply a precise native edit primitive to one clip. Tool names include trim, speed, slow_motion, green_screen, transition, motion, effect, color, reframe, mask, font, text_animation, blur, transform, audio_duck, title and volume.",inputSchema:{clipIndex:z.number().int().min(0),tool:z.string().min(1).max(80),settings:z.record(z.string(),z.any()).optional()}},async({clipIndex,tool,settings})=>queue("apply_tool",{clipIndex,tool,settings:settings||{}}));

  s.registerTool("app_creator_preset",{description:"Apply a creator look plus optional motion, transition and font to one clip or the full active timeline.",inputSchema:{preset:z.string().min(1).max(80),motion:z.string().max(80).optional(),transition:z.string().max(80).optional(),font:z.string().max(80).optional(),allClips:z.boolean().optional(),clipIndex:z.number().int().min(0).optional()}},async args=>queue("creator_preset",args));

  s.registerTool("app_autonomous_edit",{description:"Execute a structured autonomous native edit. ChatGPT may replace the timeline, apply a creator preset and optionally launch a safe native export in one request.",inputSchema:{instruction:z.string().max(5000).optional(),clips:z.array(z.record(z.string(),z.any())).max(80).optional(),preset:z.string().max(80).optional(),motion:z.string().max(80).optional(),transition:z.string().max(80).optional(),font:z.string().max(80).optional(),aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),render:z.boolean().optional(),fileName:z.string().max(180).optional()}},async args=>queue("autonomous_edit",args));

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

  if(isV3){
    const sourceScope={projectId:z.string().min(8).max(180),assetId:z.string().min(1).max(180),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),storageTreeUri:z.string().max(12000).optional()};
    s.registerTool("app_archive_source_media",{
      description:"Archive one explicitly registered original video, image or audio source into the owner's selected Android document-tree folder. Native transfers stream 256 MiB chunks with hashes, readback verification and durable resume journals; the accepted source snapshot remains pinned through recovery. Source originals stay intact. Provider quota is unknown; no Gallery scan, source deletion or cloud rendering is implied. Read the command result for the committed archive receipt.",
      inputSchema:sourceScope
    },async args=>queue("archive_source_media",args));
    s.registerTool("app_restore_source_media",{
      description:"Restore one exact committed original-source archive generation to a verified immutable app-private copy. Native revision and protection checks govern relinking the registered source. If relink is blocked, the real restored copy is retained in the owned Media Bin and the receipt reports the conflict. The original source is preserved; no broad storage or Gallery enumeration occurs.",
      inputSchema:{...sourceScope,generationId:z.string().uuid()}
    },async args=>queue("restore_source_media",args));
    s.registerTool("app_source_media_status",{
      description:"Read the local archive catalog and upload journals for one registered source asset. This performs no remote folder scan; available provider quota remains unknown. Saved receipts contain exact generation IDs and pinned folder capabilities for explicit restoration.",
      inputSchema:{projectId:sourceScope.projectId,assetId:sourceScope.assetId}
    },async args=>queue("source_media_status",args));
  }

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

  s.registerTool("app_export_project",{description:"Capture the accepted native project graph and revision, render that immutable graph to MP4, and publish it to Movies/VideoStudio. Recovery keeps the accepted graph even after later editor changes. Optional frameRate must match the project's actual12/24/30/60fps cadence; encoded sample timestamps are checked by the native renderer. Source media must remain readable. Editor rehearsal ranges do not limit this full-program export.",inputSchema:{projectId:z.string().min(8).max(180).optional(),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),frameRate:z.union([z.literal(12),z.literal(24),z.literal(30),z.literal(60)]).optional(),aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),fileName:z.string().max(180).optional()}},async({projectId,expectedRevision,frameRate,aspect,quality,fileName})=>queue("export_project",{projectId,expectedRevision,frameRate,aspect:aspect||"9:16",quality:quality||"1080p",fileName:fileName||("VideoStudio_"+Date.now()+".mp4")}));
  if(isV3)s.registerTool("app_export_range",{
    description:"Export one explicit accepted program In/Out range as native MP4. The original graph/revision is pinned durably, selected clips preserve their authored animation clock, and output duration includes trailing gaps. In/Out are never inferred from editor rehearsal selection. All-gap ranges are unavailable; stateful audio DSP starts at the cut without earlier PCM pre-roll. Recovery and publication proof retain the exact range and output profile.",
    inputSchema:{projectId:z.string().min(8).max(180),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),inMs:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),outMs:z.number().int().positive().max(Number.MAX_SAFE_INTEGER),frameRate:z.union([z.literal(12),z.literal(24),z.literal(30),z.literal(60)]).optional(),aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),fileName:z.string().max(180).optional()}
  },async args=>queue("export_range",{...args,exportMode:"range",aspect:args.aspect||"9:16",quality:args.quality||"1080p",fileName:args.fileName||("VideoStudio_Range_"+Date.now()+".mp4")}));

  s.registerTool("app_preview_project",{description:"Preview the active timeline locally on the Android device.",inputSchema:{}},async()=>queue("preview_project",{}));

  if(isV3) s.registerTool("app_timeline_edit",{
    description:"Edit the same persistent timeline the owner edits: split, trim, move, duplicate, delete, ripple delete, insert or relink owned assets, extract audio, link/unlink clips, add track, track flags, effects and clip properties. Linked A/V edits use shared atomic peer timing, locks, validation and undo history. Supply expectedRevision from project state to reject a stale edit.",
    inputSchema:{projectId:z.string().min(8).optional(),expectedRevision:z.number().int().nonnegative().optional(),operation:z.enum(["split","trim","move","roll","slip","slide","duplicate","delete","ripple_delete","insert_asset","relink_asset","extract_audio","link","unlink","add_track","track_flags","effects","properties"]),settings:z.record(z.string(),z.any())}
  },async args=>queue("timeline_edit",args));
  if(isV3){
    const animationScope={projectId:z.string().min(8).max(180).optional(),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),clipId:z.string().min(1).max(180)};
    const stableId=z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
    const rigId=z.string().min(1).max(64).regex(/^[A-Za-z0-9_.:-]+$/);
    const object=z.record(z.string(),z.any());
    const atMs=z.number().int().nonnegative().max(604800000);
    s.registerTool("app_rig_curve",{description:"Read actual compiled FK or IK scalar curve samples from the same evaluator as the owner's value graph. Supply an explicit clipId, kind bone|ik, stable id, channel and startMs/endMs (end must be later). Bone channels are rotation/x/y/scaleX/scaleY before IK; IK channels are targetX/targetY/mix before reach clamping. timeDomain defaults output_local and uses the shared signed held clip clock; authored samples the rig's authored clock directly. Samples default 128 and are bounded to 2..256, with fewer distinct integer times for short intervals. Optional expectedRevision rejects a changed project. Returns sampled values, both clocks, declared/sample bounds and evaluator identity; no geometry, inference, project edit or render job occurs.",annotations:{readOnlyHint:true,destructiveHint:false,idempotentHint:true,openWorldHint:false},inputSchema:{...animationScope,kind:z.enum(["bone","ik"]),id:rigId,channel:z.enum(["rotation","x","y","scaleX","scaleY","targetX","targetY","mix"]),startMs:atMs,endMs:atMs,samples:z.number().int().min(2).max(256).optional(),timeDomain:z.enum(["authored","output_local"]).optional()}},async args=>{
      if(args.endMs<=args.startMs)return out({queued:false,error:"Rig curve endMs must be later than startMs"});
      const channels=args.kind==="bone"?["rotation","x","y","scaleX","scaleY"]:["targetX","targetY","mix"];
      if(!channels.includes(args.channel))return out({queued:false,error:"Rig curve channel does not belong to the selected kind"});
      return queue("rig_curve",args);
    });
    const rigTools=[
      ["describe",{atMs:atMs.optional(),includeDefinition:z.boolean().optional()},"Read the validated full native 2D rig definition, including every bone, weight, keyframe and saved pose, separately from sampled state. atMs is output-local time. authoredPose contains sampled local FK values before IK; rig.bones/ik contain solved state. includeDefinition defaults true; false requests compact diagnostics. Definition is bounded to 256 KiB; no inference or source upload occurs."],
      ["create",{bones:z.array(object).min(1).max(24).optional(),replace:z.boolean().optional()},"Create a real native textured 2D bone/mesh rig on an owned still-image clip through the owner's shared transaction. Optional bones use the native schema; omitted bones use the explicit default body rig. Existing rigs require replace:true. Limits: 24 bones, 512 mesh vertices, 1024 triangles, 2048 keys and 256 KiB rig JSON. No inferred anatomy or 3D rig is claimed."],
      ["apply",{rig:object},"Apply a validated complete version-1 native 2D rig with normalized bind coordinates, bones, pose/keyframes, weighted mesh and optional analytic two-link IK. Rig JSON contains no source URI. Native limits, clip ownership and track locks are enforced."],
      ["clear",{},"Remove the selected clip's authored native 2D rig through shared undo history. Owned media is preserved."],
      ["set_enabled",{enabled:z.boolean()},"Enable or disable the existing native 2D rig while preserving its mesh, weights, poses and animation keys."],
      ["set_bone",{bone:object,autoWeights:z.boolean().optional(),preserveConnections:z.boolean().optional()},"Add or update one stable native bone using id/parentId and normalized bind x/y/endX/endY coordinates in 0..1. Native hierarchy, geometry and actual mesh constraints are validated. autoWeights defaults true. Explicit preserveConnections moves already connected neighboring joints atomically while retaining authored mesh weights when autoWeights:false."],
      ["remove_bone",{boneId:rigId},"Remove one bone through shared native rig validation; protected references must be resolved explicitly."],
      ["auto_weights",{},"Compute actual bounded native mesh skin weights for the current explicit 2D bones. This is geometric weighting, with no anatomy inference or external executor."],
      ["set_weights",{vertexIndex:z.number().int().min(0).max(511).optional(),vertexIndices:z.array(z.number().int().min(0).max(511)).min(1).max(512).optional(),influences:z.array(z.object({boneId:rigId,weight:z.number().min(0).max(1)})).min(1).max(4)},"Set real native skin-weight influences for one existing vertexIndex or an explicit bounded vertexIndices batch. Distinct bone IDs and 1..4 influences are required; native painting drops zero weights and normalizes the positive sum."],
      ["paint_weights",{boneId:rigId,radius:z.number().min(.001).max(2),strength:z.number().min(-1).max(1),falloff:z.enum(["linear","smooth","hard"]).optional(),u:z.number().min(0).max(1).optional(),v:z.number().min(0).max(1).optional(),points:z.array(z.object({u:z.number().min(0).max(1),v:z.number().min(0).max(1)}).strict()).min(1).max(256).optional()},"Paint or erase real weights for one existing bone in a single atomic stroke. Supply normalized u/v or points (never both); radius is measured in source-image height units, signed strength adds/erases, and falloff defaults smooth. The receipt reports actual changed vertex indices. A no-change stroke is rejected; vertices remain bound."],
      ["set_keyframe",{keyframe:object,replaceKeyframe:z.boolean().optional()},"Set one native bone keyframe {boneId,atMs,rotation?,x?,y?,scaleX?,scaleY?,ease?} in the clip's authored output clock. replaceKeyframe:true replaces the entire sparse row, removing omitted channels; default merges. Actual sampling uses the same keys in preview and export."],
      ["remove_keyframe",{boneId:rigId,atMs},"Delete one exact authored bone key at its stable bone ID and output clock."],
      ["move_keyframe",{boneId:rigId,fromAtMs:atMs,toAtMs:atMs},"Move one existing sparse bone key row between exact authored times in one native transaction. The complete channels and easing/Bézier controls are preserved; any occupied destination row is rejected."],
      ["set_pose",{pose:object},"Set the current explicit native bone pose map {boneId:{rotation?,x?,y?,scaleX?,scaleY?}} through the revision-checked transaction."],
      ["capture_pose",{poseId:rigId,name:z.string().min(1).max(128).optional(),atMs},"Capture the actually sampled native 2D bone pose at one authored-clock time as a named reusable pose."],
      ["apply_pose",{poseId:rigId,atMs:atMs.optional(),ease:z.string().max(40).optional(),bezier:z.tuple([z.number().min(0).max(1),z.number().min(-4).max(4),z.number().min(0).max(1),z.number().min(-4).max(4)]).optional()},"Apply one saved native pose. Optional atMs writes sampled FK keys and disables IK mix at that authored time; omitted atMs applies the baseline pose and base IK mix zero. Cubic easing requires exact four-number controls."],
      ["remove_pose",{poseId:rigId},"Remove one saved native pose; owned source media and other animation keys remain."],
      ["set_ik",{ik:object},"Set a real analytic two-link IK constraint {id,rootBoneId,childBoneId,targetX,targetY,bend,mix,keyframes?}. Native validation requires a connected unit-scale two-link chain and bounded normalized targets; arbitrary skeletal IK is unavailable."],
      ["remove_ik",{ikId:rigId},"Remove one stable native analytic two-link IK constraint."],
      ["set_ik_keyframe",{ikId:rigId,keyframe:object,replaceKeyframe:z.boolean().optional()},"Set one actual analytic two-link IK target/mix key at {atMs,targetX?,targetY?,bend?,mix?,ease?}. replaceKeyframe:true replaces the complete sparse row so omitted channels are removed; default merges. Native key and chain limits are enforced."],
      ["remove_ik_keyframe",{ikId:rigId,atMs},"Delete one exact native IK target key without inventing a procedural motion result."],
      ["move_ik_keyframe",{ikId:rigId,fromAtMs:atMs,toAtMs:atMs},"Move one existing sparse analytic two-link IK target key row between exact authored times atomically. Original target/mix channels and easing controls are preserved, and any occupied destination row rejects the edit."]
    ];
    for(const [operation,fields,description] of rigTools)s.registerTool("app_rig_"+operation,{description,inputSchema:{...animationScope,...fields}},async args=>queue("rig_"+operation,args));
    const coordinate=z.number().min(-10).max(10);
    const path=z.object({version:z.literal(1),mode:z.enum(["add","replace"]),orientToPath:z.boolean(),rotationOffsetDeg:z.number().min(-3600).max(3600).optional(),points:z.array(z.object({id:stableId,t:z.number().min(0).max(1),x:coordinate,y:coordinate,inX:coordinate.optional(),inY:coordinate.optional(),outX:coordinate.optional(),outY:coordinate.optional()}).strict()).min(2).max(128)}).strict();
    s.registerTool("app_set_motion_path",{description:"Author a real cubic spatial path on an owned visual clip. Points have unique stable IDs and strictly increasing normalized t; absolute in/out handles are paired X/Y. GL coordinates use X=2 for one canvas width and positive Y upwards. Mode add composes with existing transforms; replace substitutes position. Optional orientation follows the analytic tangent. Native preview and export use the same bounded path sampler.",inputSchema:{...animationScope,path}},async args=>queue("set_motion_path",args));
    s.registerTool("app_clear_motion_path",{description:"Clear the selected clip's authored spatial motion path through the owner's shared revision-checked transaction; native source media and other effects remain.",inputSchema:animationScope},async args=>queue("clear_motion_path",args));
    s.registerTool("app_set_animation_easing",{description:"Set actual native visual, audio or camera easing through the same owner edit helper. cubic_bezier requires [x1,y1,x2,y2], solves X before sampling Y, and preserves the current camera specification. Named easing removes custom controls. Individual keyframes can carry their own native curves.",inputSchema:{...animationScope,scope:z.enum(["visual","audio","camera"]),easing:z.enum(["linear","smooth","ease_in","ease_out","easeIn","easeOut","ease_in_out","easeInOut","cinematic","hold","step","cubic_bezier"]),bezier:z.tuple([z.number().min(0).max(1),z.number().min(-4).max(4),z.number().min(0).max(1),z.number().min(-4).max(4)]).optional()}},async args=>queue("set_animation_easing",args));
  }
  if(isV3){
    const scope={projectId:z.string().min(8).max(180).optional(),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional()};
    const clipId=z.string().min(1).max(256),frame=z.number().int().min(0).max(10000000),frames=z.number().int().min(1).max(10000000);
    const color=z.string().regex(/^#[A-Fa-f0-9]{6}([A-Fa-f0-9]{2})?$/);
    const stroke=z.object({id:z.string().min(1).max(256).regex(/^\S+$/),type:z.enum(["paint","erase"]),color:color.optional(),width:z.number().min(.001).max(.2).optional(),points:z.array(z.object({x:z.number().min(0).max(1),y:z.number().min(0).max(1),pressure:z.number().min(.1).max(1.5).optional()}).strict()).min(1).max(8192)}).strict();
    const drawing=z.object({version:z.literal(1),width:z.number().int().min(16).max(2048).optional(),height:z.number().int().min(16).max(2048).optional(),background:color.optional(),strokes:z.array(stroke).max(512).optional()}).strict().refine(value=>(value.strokes||[]).reduce((sum,item)=>sum+item.points.length,0)<=8192,{message:"Drawing exceeds8192totalpoints"});
    const strokeId=stroke.shape.id.regex(/^[^\s\u0000-\u001f\u007f-\u009f]+$/),pointIndex=z.number().int().min(0).max(8191),coordinate=z.number().min(0).max(1),pressure=z.number().min(.1).max(1.5);
    const vectorAction=z.discriminatedUnion("op",[
      z.object({op:z.literal("set_stroke"),strokeId,color:color.optional(),width:z.number().min(.001).max(.2).optional()}).strict().refine(value=>value.color!==undefined||value.width!==undefined,{message:"set_stroke requires color or width"}),
      z.object({op:z.literal("move_point"),strokeId,pointIndex,x:coordinate,y:coordinate,pressure:pressure.optional()}).strict(),
      z.object({op:z.literal("translate_stroke"),strokeId,dx:z.number().min(-1).max(1),dy:z.number().min(-1).max(1)}).strict(),
      z.object({op:z.literal("delete_stroke"),strokeId}).strict(),
      z.object({op:z.literal("insert_point"),strokeId,pointIndex:z.number().int().min(0).max(8192),x:coordinate,y:coordinate,pressure:pressure.optional()}).strict(),
      z.object({op:z.literal("remove_point"),strokeId,pointIndex}).strict()
    ]);
    const vectorActions=z.array(vectorAction).min(1).max(64).refine(value=>new TextEncoder().encode(JSON.stringify(value)).byteLength<=32*1024,{message:"Cel vector edit batch exceeds 32 KiB"});
    const exposure=z.object({trackId:z.string().min(1).max(180).optional(),startFrame:frame.optional(),frameCount:frames.optional(),fpsNumerator:z.union([z.literal(12),z.literal(24),z.literal(30),z.literal(60)]).optional(),fpsDenominator:z.literal(1).optional(),originMs:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),ripple:z.boolean().optional(),name:z.string().min(1).max(120).optional()}).strict();
    s.registerTool("app_cel_create",{description:"Draw a real immutable native PNG cel from explicit normalized paint/eraser strokes, then atomically register its owned asset and exposure at the accepted revision. Uses the same factory as the owner's drawing canvas. Limits:128KiB drawing JSON,512strokes,8192points,16..2048pixel dimensions. Pressure scales brush width; erase clears foreground while preserving the chosen background. Output cadence supports integer12/24/30/60fps only; fractional rates and inferred drawings are unavailable. The full bounded document is durably queued in checksummed chunks, never as a source URI.",inputSchema:{...scope,drawing,exposure:exposure.optional()}},async args=>queue("cel_create",args));
    s.registerTool("app_cel_update",{description:"Redraw one existing native animation cel exposure as a new immutable PNG generation at expectedRevision. Optional exposure settings change frameCount in the same exact-revision transaction as the owner's canvas save; cadence must match the current exposure. Other exposures and undo snapshots keep the prior image. Replaying this command recovers its actual PNG; it never recreates a deleted or subsequently redrawn exposure.",inputSchema:{...scope,clipId,drawing,exposure:z.object({frameCount:frames.optional(),fpsNumerator:z.union([z.literal(12),z.literal(24),z.literal(30),z.literal(60)]).optional(),fpsDenominator:z.literal(1).optional(),ripple:z.boolean().optional()}).strict().optional()}},async args=>queue("cel_update",args));
    s.registerTool("app_cel_edit_strokes",{description:"Edit an existing owned cel's real vector document in one strict sequential batch, then render a new immutable PNG through the owner's shared factory and undo transaction. Read stroke IDs/points with app_cel_describe and supply its exact revision. Supports set_stroke color/width, move_point, translate_stroke, delete_stroke, insert_point and remove_point. Indices refer to the document after earlier actions; points must remain in 0..1, translations never clamp, and removing the last point is refused. Missing move pressure preserves it; missing inserted pressure uses 1. Limits: 64 actions/32 KiB, with a final drawing of 128 KiB/512 strokes/8192 points. Committed retries reuse verified output before applying edits; stale or owner-deleted/redrawn exposures are not recreated. No caller drawing/media locator or inferred geometry is accepted.",inputSchema:{...scope,expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),clipId,actions:vectorActions}},async args=>queue("cel_edit_strokes",args));
    s.registerTool("app_cel_describe",{description:"Read the owned cel's actual editable vector-stroke document and native exposure/cadence metadata. No Gallery, external source locator or inferred drawing is returned.",inputSchema:{projectId:scope.projectId,clipId}},async args=>queue("cel_describe",args));
    s.registerTool("app_cel_exposure_add",{description:"Place an existing owned animation-cel asset at explicit cumulative frame boundaries through shared native revision checks and undo. Supported integer cadence12/24/30/60must match the chosen project cadence; fpsDenominator must be1.",inputSchema:{...scope,assetId:z.string().min(1).max(256),...exposure.shape}},async args=>queue("cel_exposure_add",args));
    s.registerTool("app_cel_exposure_hold",{description:"Set one frame-aligned cel exposure's hold length to an explicit frameCount through the shared native transaction. Generic timing changes that leave it off the frame grid require explicit rebasing before this operation.",inputSchema:{...scope,clipId,frameCount:frames,ripple:z.boolean().optional()}},async args=>queue("cel_exposure_hold",args));
    s.registerTool("app_cel_exposure_extend",{description:"Extend one frame-aligned native cel exposure by actual addFrames, respecting locks and optional ripple placement.",inputSchema:{...scope,clipId,addFrames:frames,ripple:z.boolean().optional()}},async args=>queue("cel_exposure_extend",args));
    s.registerTool("app_cel_exposure_duplicate",{description:"Duplicate one real frame-aligned cel exposure using the same immutable PNG. Optional startFrame/trackId places it explicitly; the shared helper preserves exact cumulative frame timing.",inputSchema:{...scope,clipId,startFrame:frame.optional(),trackId:z.string().min(1).max(180).optional(),ripple:z.boolean().optional()}},async args=>queue("cel_exposure_duplicate",args));
    s.registerTool("app_cel_exposure_delete",{description:"Delete one native cel exposure through shared undo history, with optional ripple. Its owned immutable source image is retained for other exposures/history.",inputSchema:{...scope,clipId,ripple:z.boolean().optional()}},async args=>queue("cel_exposure_delete",args));
    s.registerTool("app_animation_frame_rate",{description:"Choose the project's actual integer animation export cadence at the expected native revision. Supports12/24/30/60fps; fractional cadence is refused. This changes future export sampling, not authored cel hold counts.",inputSchema:{...scope,frameRate:z.union([z.literal(12),z.literal(24),z.literal(30),z.literal(60)])}},async args=>queue("animation_frame_rate",args));
  }
  for(const operation of ["roll","slip","slide"])if(isV3)s.registerTool("app_"+operation+"_clip",{
    description:operation==="roll"?"Move a touching edit boundary by signed program milliseconds while preserving total program duration. Shared native validation enforces neighbor handles, locks and linked A/V timing. Positive delta moves the boundary later.":operation==="slip"?"Change the sampled source range by signed program milliseconds while keeping this clip's program placement and duration. Positive delta samples later footage at each linked peer's speed. Requires known timed source media.":"Move a clip by signed program milliseconds while trimming its touching neighbors and preserving whole-program duration. Positive delta moves it later. Shared native validation enforces source handles, tracks and linked A/V peers.",
    inputSchema:{projectId:z.string().min(8).max(180).optional(),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional(),clipId:z.string().min(1).max(180),deltaMs:z.number().int().min(-Number.MAX_SAFE_INTEGER).max(Number.MAX_SAFE_INTEGER),...(operation==="roll"?{edge:z.enum(["start","end"]).optional()}:{})}
  },async({projectId,expectedRevision,clipId,deltaMs,edge})=>queue("timeline_edit",{projectId,expectedRevision,operation,settings:{clipId,deltaMs,...(edge?{edge}:{})}}));
  if(isV3){
    const projectScope={projectId:z.string().min(8).max(180).optional(),expectedRevision:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).optional()};
    const markerFields={atMs:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),endMs:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).nullable().optional(),name:z.string().min(1).max(120).optional(),color:z.string().regex(/^#[a-fA-F0-9]{6}$/).optional(),note:z.string().max(2048).optional()};
    s.registerTool("app_marker_add",{description:"Add a durable program marker or marker range using the owner's shared project transaction. Positions must lie inside the current program; marker ranges do not limit export.",inputSchema:{...projectScope,markerId:z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/).optional(),...markerFields}},async args=>queue("marker_add",args));
    s.registerTool("app_marker_update",{description:"Update a durable marker at expectedRevision. Set endMs to null to clear its range.",inputSchema:{...projectScope,markerId:z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/),...markerFields,atMs:markerFields.atMs.optional()}},async args=>queue("marker_update",args));
    s.registerTool("app_marker_delete",{description:"Delete one durable program marker through the shared native editor transaction.",inputSchema:{...projectScope,markerId:z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)}},async args=>queue("marker_delete",args));
    s.registerTool("app_marker_list",{description:"Read saved markers with their derived validity against the current native program.",inputSchema:{projectId:projectScope.projectId}},async args=>queue("marker_list",args));
    s.registerTool("app_editor_range_set",{description:"Set the owner's durable rehearsal range inside the current program. This controls editor rehearsal only; native export still renders the entire accepted program.",inputSchema:{...projectScope,inMs:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),outMs:z.number().int().positive().max(Number.MAX_SAFE_INTEGER),enabled:z.boolean().optional()}},async args=>queue("editor_range_set",args));
    s.registerTool("app_editor_range_clear",{description:"Clear the durable editor rehearsal range using the shared project transaction.",inputSchema:projectScope},async args=>queue("editor_range_clear",args));
    s.registerTool("app_editor_range_status",{description:"Read the native editor rehearsal range and whether it remains valid for the current program. No range rendering is implied.",inputSchema:{projectId:projectScope.projectId}},async args=>queue("editor_range_status",args));
  }
  if(isV3) s.registerTool("app_extract_audio",{
    description:"Extract a video's real embedded audio into an audio-track peer sharing the owned source and exact timing. The original embedded audio is disabled and the pair is linked for shared timeline edits. Requires a source with an audio stream; no source upload or invented audio occurs.",
    inputSchema:{projectId:z.string().min(8).optional(),expectedRevision:z.number().int().nonnegative().optional(),clipId:z.string().min(8),trackId:z.string().min(8).optional()}
  },async({projectId,expectedRevision,clipId,trackId})=>queue("timeline_edit",{projectId,expectedRevision,operation:"extract_audio",settings:{clipId,trackId}}));
  if(isV3) s.registerTool("app_link_clips",{
    description:"Link existing native clips that have identical program start/end so future move, trim, split and delete operations edit their peers atomically. Uses the owner's shared project model and undo history.",
    inputSchema:{projectId:z.string().min(8).optional(),expectedRevision:z.number().int().nonnegative().optional(),clipIds:z.array(z.string().min(8)).min(2).max(120)}
  },async({projectId,expectedRevision,clipIds})=>queue("timeline_edit",{projectId,expectedRevision,operation:"link",settings:{clipIds}}));
  if(isV3) s.registerTool("app_unlink_clips",{
    description:"Explicitly unlink an existing native clip group through the shared revision-checked project transaction; owned media and detached-audio state are retained.",
    inputSchema:{projectId:z.string().min(8).optional(),expectedRevision:z.number().int().nonnegative().optional(),clipId:z.string().min(8)}
  },async({projectId,expectedRevision,clipId})=>queue("timeline_edit",{projectId,expectedRevision,operation:"unlink",settings:{clipId}}));
  if(isV3) s.registerTool("app_relink_asset",{
    description:"Relink one original source asset to replacement media already imported into the same owned native project. Shared validation checks source type, referenced clip ranges and track locks at expectedRevision. Generated assets, raw Gallery enumeration and arbitrary locator overwrites are rejected.",
    inputSchema:{projectId:z.string().min(8).optional(),expectedRevision:z.number().int().nonnegative().optional(),assetId:z.string().min(8),replacementAssetId:z.string().min(8),preserveName:z.boolean().optional()}
  },async({projectId,expectedRevision,assetId,replacementAssetId,preserveName})=>queue("timeline_edit",{projectId,expectedRevision,operation:"relink_asset",settings:{assetId,replacementAssetId,preserveName}}));
  if(isV3) for(const action of ["undo","redo"]) s.registerTool("app_"+action,{
    description:action+" the latest shared project edit, including owner and ChatGPT mutations.",
    inputSchema:{projectId:z.string().min(8).optional(),expectedRevision:z.number().int().nonnegative().optional()}
  },async args=>queue(action,args));

  s.registerTool("app_import_from_url",{description:"Import an explicit HTTPS media URL into VideoStudio without browsing Gallery. Available in Full Autonomous mode; Gallery enumeration remains blocked.",inputSchema:{url:z.string().url(),name:z.string().max(160).optional(),projectId:z.string().min(8).optional()}},async({url,name,projectId})=>queue("import_url",{url,name:name||"ChatGPT import",projectId:projectId||""}));

  if(isV3) s.registerTool("app_import_attachment",{
    description:"Import a user-shared video, image or audio attachment when the host supplies its temporary HTTPS download_url. The authorised Android app receives an authenticated private relay, persists validated asset metadata, and inserts media into the shared project. A file_id or sandbox path alone is not downloadable. Read app_get_command_result for actual completion.",
    inputSchema:{
      file:z.object({
        download_url:z.string().max(12000).optional(),
        file_id:z.string().max(180).optional(),
        mime_type:z.string().max(120).optional(),
        file_name:z.string().max(180).optional(),
        size:z.number().int().nonnegative().optional(),
        sha256:z.string().regex(/^[a-fA-F0-9]{64}$/).optional()
      }).passthrough(),
      projectId:z.string().min(8).optional()
    },
    _meta:{"openai/fileParams":["file"]}
  },async({file,projectId})=>{
    try{
      const c=await queueAttachment(st,ownerKey,file,projectId||"",true);
      return out({
        queued:true,
        commandId:c.id,
        sequence:c.seq,
        action:"import_chat_file",
        nativeApp:true,
        protocolVersion:3,
        route:"private-worker-handoff",
        waitingNative:c.status==="waiting_native",
        attachmentExpiresAt:c.attachmentExpiresAt,
        completion:"Read app_get_command_result; queued is not an imported asset",
        hostFileReferenceAccepted:true
      });
    }catch(e){ return out({queued:false,error:e.message}); }
  });

  if(isV3) s.registerTool("app_import_inline_base64",{
    description:"Private compatibility fallback when the caller can supply actual small-media bytes and the host cannot supply a temporary HTTPS URL. Up to 12 MB of PNG, JPEG, WebP or MP4 bytes are streamed into a short-lived private cache, then the authorised app downloads a resumable handoff. No media bytes enter the durable command queue. A file_id alone cannot supply bytes.",
    inputSchema:{
      name:z.string().min(1).max(180),
      mime:z.enum(["image/png","image/jpeg","image/webp","video/mp4"]),
      base64:z.string().min(1).max(17*1024*1024),
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

  if(isV3) s.registerTool("app_execute",{description:"Stable VideoStudio action bridge. For import_attachment, pass the user-shared host file parameter or parameters containing an explicit HTTPS sourceUrl; both use the authenticated private relay. A file_id or sandbox path alone is not downloadable. Editing actions share the owner's transactional project model; expectedRevision can reject a stale edit. Gallery enumeration is always blocked.",inputSchema:{action:z.string().min(1).max(80),parameters:z.record(z.string(),z.any()).optional(),file:z.object({download_url:z.string().max(12000).optional(),file_id:z.string().max(180).optional(),file_name:z.string().max(180).optional(),mime_type:z.string().max(120).optional(),size:z.number().int().nonnegative().optional(),sha256:z.string().regex(/^[a-fA-F0-9]{64}$/).optional()}).passthrough().optional()},_meta:{"openai/fileParams":["file"]}},async({action,parameters,file})=>{
    if(file&&action!=="import_attachment"&&action!=="import_chat_file") return out({queued:false,error:"The file parameter is only valid for attachment import actions"});
    const p={...(parameters||{}),...(file?{file}:{})};
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

  s.registerTool("app_cancel_job",{description:"Cancel one ChatGPT-initiated native VideoStudio job. Owner-started exports and other manual work remain under the owner's local controls.",inputSchema:{jobId:z.string().min(8)}},async({jobId})=>queue("cancel_job",{jobId}));
  s.registerTool("app_cancel_all_jobs",{description:"Cancel active ChatGPT-initiated VideoStudio jobs and autonomous render. Owner-started jobs remain under the owner's local controls.",inputSchema:{}},async()=>queue("cancel_all_jobs",{}));
  s.registerTool("app_get_command_result",{description:"Read completion status/result for a native command. Analysis results render their contact sheet directly for ChatGPT to inspect.",inputSchema:{commandId:z.string().min(8)}},async({commandId})=>commandResult(commandId));
  return s;
}

async function api(request,env){
  const u=new URL(request.url), st=state(env);
  if(request.method==="OPTIONS") return new Response(null,{status:204,headers:{"access-control-allow-origin":"*","access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type, authorization"}});
  try{
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
    if(u.pathname==="/api/v3/app/metadata-mirror"&&request.method==="POST"){
      const token=bearer(request),owner=await st.appResolve(token);
      if(!owner||!owner.nativeApp)return reply({ok:false,error:"Private native owner authorization failed"},401);
      const body=await boundedMetadataJson(request);
      if(!(await st.appAuth(body.deviceId,token)))return reply({ok:false,error:"Private native owner authorization failed"},401);
      const expected=Math.max(0,Number(owner.appGeneration||0));
      if(expected>0&&Number(body.appGeneration||0)!==expected)
        return reply({ok:false,error:"Stale Native Agent generation",expectedGeneration:expected},409);
      return reply(await st.appMetadataMirror(token,body.operation,body.parameters||{}));
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
      return privateHandoffContent(request,handoff);
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
      const status=await st.appStatus(token);
      if(!status.connected) return reply(status,401);
      return reply(status);
    }
    const pcm=u.pathname.match(/^\/api\/app\/private\/commands\/([^/]+)$/);
    if(pcm&&request.method==="GET"){
      const token=bearer(request);
      const command=await st.appCommand(token,pcm[1]);
      return command?reply({command}):reply({error:"Command not found"},404);
    }
    if(["/api/app/private/upload","/api/v3/app/private/upload"].includes(u.pathname)&&request.method==="POST"){
      const token=bearer(request), deviceId=u.searchParams.get("deviceId")||"";
      if(!(await st.appAuth(deviceId,token))) return reply({error:"Native app authorization failed"},401);
      const status=await st.appStatus(token);
      if(!status.connected||status.device.permissionMode==="one_file") return reply({error:"Full Autonomous mode is required while One File Lock is active"},403);
      if(status.device.controlPaused) return reply({error:"ChatGPT control is paused on the phone"},403);

      const declared=Number(request.headers.get("content-length")||0);
      const max=PRIVATE_UPLOAD_LIMIT;
      if(!request.body||!Number.isSafeInteger(declared)||declared<0) return reply({error:"Invalid private upload body or length"},400);
      if(declared>max) return reply({error:"Upload exceeds 250 MB private relay limit"},413);
      const name=clean(u.searchParams.get("name")||"ChatGPT import",180);
      const mime=clean(u.searchParams.get("mime")||request.headers.get("content-type")||"application/octet-stream",120);
      const projectId=clean(u.searchParams.get("projectId")||"",120);
      const uploadId=crypto.randomUUID();
      const cacheUrl=u.origin+"/__videostudio_private_upload/"+uploadId;
      const cacheHeaders=new Headers();
      cacheHeaders.set("content-type",mime);
      if(declared>0) cacheHeaders.set("content-length",String(declared));
      cacheHeaders.set("cache-control","public, max-age=1200");
      cacheHeaders.set("x-content-type-options","nosniff");
      cacheHeaders.set("etag",'"'+uploadId+'"');
      let received=0;
      const bounded=request.body.pipeThrough(new TransformStream({transform(chunk,controller){
        received+=chunk.byteLength;
        if(received>max) throw new Error("Upload exceeds 250 MB private relay limit");
        controller.enqueue(chunk);
      }}));
      try{
        await caches.default.put(new Request(cacheUrl),new Response(bounded,{status:200,headers:cacheHeaders}));
        if(received===0||(declared>0&&received!==declared)) throw new Error("Private upload length mismatch");
        const handoff=await st.appCreateCachedHandoff(token,cacheUrl,{name,mime,size:received});
        const parameters={handoffId:handoff.id,name:handoff.name,mime:handoff.mime,size:received,projectId};
        const d=status.device;
        const isV3=Number(d.protocolMin||d.protocolVersion||0)<=3&&Number(d.protocolMax||d.protocolVersion||0)>=3;
        const c=isV3?await st.appEnqueueV3(token,"import_chat_file",parameters):await st.appEnqueue(token,"import_chat_file",parameters);
        return reply({ok:true,queued:true,commandId:c.id,sequence:c.seq,protocolVersion:isV3?3:1,handoffId:handoff.id,expiresAt:handoff.expiresAt,name,mime,size:received,projectId,waitingNative:c.status==="waiting_native"});
      }catch(error){
        await caches.default.delete(new Request(cacheUrl));
        return reply({error:error.message||"Private upload could not be queued"},received>max?413:400);
      }
    }
    if(u.pathname==="/api/app/commands"&&request.method==="GET"){
      const deviceId=u.searchParams.get("deviceId")||"", after=Number(u.searchParams.get("after")||0), wait=Number(u.searchParams.get("wait")||0);
      const token=bearer(request);
      return reply({commands:await st.appCommands(deviceId,token,after,wait)});
    }
    const hm=u.pathname.match(/^\/api\/app\/handoffs\/([^/]+)\/content$/);
    if(hm&&request.method==="GET"){
      const deviceId=u.searchParams.get("deviceId")||"", token=bearer(request);
      const handoff=await st.appHandoff(deviceId,token,hm[1]);
      if(!handoff) return reply({error:"Handoff missing or expired"},404);
      return privateHandoffContent(request,handoff);
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
    const append=u.pathname.match(/^\/api\/projects\/([^/]+)\/append-generated$/);
    if(append&&request.method==="POST"){
      try{const b=await readBoundedStudioJson(request,STUDIO_APPEND_BYTES),result=await st.appendGenerated(b.deviceId,append[1],b);return result?reply({ok:true,...result}):reply({error:"Project not found"},404);}
      catch(error){return reply({error:error.message,code:error.code||"invalid_generated_metadata",...(error.assetId?{assetId:error.assetId}:{}),...(error.clipId?{clipId:error.clipId}:{})},error.status||400);}
    }
    const pm=u.pathname.match(/^\/api\/projects\/([^/]+)$/);
    if(pm&&request.method==="GET"){ const p=await st.project(u.searchParams.get("deviceId")||"",pm[1]); return p?reply({project:p}):reply({error:"Project not found"},404); }
    if(pm&&request.method==="POST"){
      try{const b=await readBoundedStudioJson(request,STUDIO_PROJECT_BYTES),p=await st.update(b.deviceId,pm[1],b.patch||{},b.expectedRevision);return p?reply({project:p}):reply({error:"Project not found"},404);}
      catch(error){return reply({error:error.message,code:error.code||"invalid_project_request",...(error.actualRevision!==undefined?{actualRevision:error.actualRevision,expectedRevision:error.expectedRevision}:{}),projectId:pm[1]},error.status||400);}
    }
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
    if(u.pathname==="/"&&request.method==="GET"){
      let html=APP_HTML.includes("/studio-runtime.js")?APP_HTML:APP_HTML.replace("</body>",'<script defer src="/studio-runtime.js"></script></body>');
      if(!html.includes("/studio-cinematic.js")) html=html.replace("</body>",'<script defer src="/studio-cinematic.js"></script></body>');
      if(!html.includes("/studio-neural.js")) html=html.replace("</body>",'<script defer src="/studio-neural.js"></script></body>');
      if(!html.includes("/studio-temporal.js")) html=html.replace("</body>",'<script defer src="/studio-temporal.js"></script></body>');
      return new Response(html,{headers:{"content-type":"text/html; charset=UTF-8","cache-control":"no-store"}});
    }
    if(u.pathname==="/studio-runtime.js"&&request.method==="GET") return new Response(STUDIO_RUNTIME_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-cinematic.js"&&request.method==="GET") return new Response(STUDIO_CINEMATIC_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-neural.js"&&request.method==="GET") return new Response(STUDIO_NEURAL_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/studio-temporal.js"&&request.method==="GET") return new Response(STUDIO_TEMPORAL_JS,{headers:{"content-type":"application/javascript; charset=UTF-8","cache-control":"no-cache"}});
    if(u.pathname==="/api/web/config"&&request.method==="GET") return reply({googleDriveClientId:clean(env.GOOGLE_DRIVE_CLIENT_ID||"",300),driveScope:"https://www.googleapis.com/auth/drive.file",storageMode:"user-owned-google-drive"});
    if(u.pathname==="/manifest.webmanifest") return new Response(MANIFEST,{headers:{"content-type":"application/manifest+json"}});
    if(u.pathname==="/icon.svg") return new Response(ICON,{headers:{"content-type":"image/svg+xml"}});
    if(u.pathname==="/sw.js") return new Response(SW,{headers:{"content-type":"application/javascript","cache-control":"no-cache"}});
    if(u.pathname.startsWith("/api/")) return api(request,env);
    // Permanent compatibility endpoint. Do not rename this route for APK releases.
    // Future app versions evolve behind protocol-v3 additive actions/app_execute.
    const appMcpV3=u.pathname.match(/^\/app-mcp-v3\/([A-Za-z0-9_-]{32,})$/);
    if(appMcpV3){
      const ownerKey=appMcpV3[1];
      return createMcpHandler(()=>serverForApp(env,ownerKey,3,u.origin),{route:u.pathname,responseMode:"auto"})(request,env,ctx);
    }
    const appMcp=u.pathname.match(/^\/app-mcp\/([A-Za-z0-9_-]{32,})$/);
    if(appMcp){
      const ownerKey=appMcp[1];
      return createMcpHandler(()=>serverForApp(env,ownerKey,1,u.origin),{route:u.pathname,responseMode:"auto"})(request,env,ctx);
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

