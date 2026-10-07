import { DurableObject } from "cloudflare:workers";
import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";
import APP_HTML from "./app.html";

const JH = {"content-type":"application/json; charset=UTF-8","cache-control":"no-store"};
const now = () => new Date().toISOString();
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
const appActionAllowed = (mode,action) => {
  if(["ping","get_state"].includes(action)) return true;
  if(mode==="everything") return true;
  if(mode==="all_tools") return !["import_url","delete_project"].includes(action);
  if(mode==="one_file") return ["apply_tool","preview_project","cancel_job"].includes(action);
  return false;
};

export class VideoStudioState extends DurableObject {
  constructor(ctx,env){ super(ctx,env); }
  async register(deviceId,meta={}){
    const k="d:"+deviceId, old=(await this.ctx.storage.get(k))||{};
    const d={deviceId,name:clean(meta.name||old.name||"My device",80),platform:clean(meta.platform||old.platform||"web",80),appVersion:"0.6.0",createdAt:old.createdAt||now(),lastSeenAt:now()};
    await this.ctx.storage.put(k,d); return d;
  }
  async device(deviceId){ return (await this.ctx.storage.get("d:"+deviceId))||null; }
  async createProject(deviceId,name,instruction=""){
    await this.register(deviceId);
    const id=crypto.randomUUID(), p={id,deviceId,name:clean(name||"Untitled Project",120),instruction:clean(instruction),createdAt:now(),updatedAt:now(),assets:[],timeline:[],settings:{aspect:"9:16",speed:1,mute:false,title:"",quality:"720p",transition:"fade"},latestRender:null,latestCommand:null};
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
    for(const k of ["name","instruction","assets","timeline","settings","latestRender","latestCommand"]) if(patch[k]!==undefined) p[k]=patch[k];
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
  async status(deviceId){
    const d=await this.device(deviceId), ps=await this.projects(deviceId), a=(await this.ctx.storage.get("cl:"+deviceId))||[];
    return {connected:!!d,device:d,projectCount:ps.length,pendingCommands:a.filter(c=>c.status==="queued").length,lastCommand:a[a.length-1]||null};
  }

  async appRegister(deviceId,ownerKey,meta={}){
    if(!deviceId||String(deviceId).length<8) throw new Error("Invalid native device ID");
    if(!ownerKey||String(ownerKey).length<32) throw new Error("Invalid owner key");
    const hash=await sha256Hex(ownerKey), key="app-owner:"+hash;
    const bound=await this.ctx.storage.get(key);
    if(bound&&bound!==deviceId) throw new Error("Owner key is already bound to another device");
    const dk="app-device:"+deviceId, old=(await this.ctx.storage.get(dk))||{};
    if(old.ownerHash&&old.ownerHash!==hash) throw new Error("This native device is already bound to its owner credential");
    const mode=["one_file","all_tools","everything"].includes(meta.permissionMode)?meta.permissionMode:(old.permissionMode||"all_tools");
    const d={
      deviceId,
      name:clean(meta.name||old.name||"VideoStudio Android",80),
      platform:clean(meta.platform||old.platform||"android-native",80),
      appVersion:clean(meta.appVersion||old.appVersion||"1.0.0",30),
      permissionMode:mode,
      projects:Array.isArray(meta.projects)?meta.projects.slice(0,100):(old.projects||[]),
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
    return (await this.ctx.storage.get("app-device:"+id))||null;
  }
  async appAuth(deviceId,ownerKey){
    const d=await this.appResolve(ownerKey);
    return d&&d.deviceId===deviceId?d:null;
  }
  async appEnqueue(ownerKey,action,parameters={}){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    if(!appActionAllowed(d.permissionMode,action)) throw new Error("Action blocked by device permission mode: "+d.permissionMode);
    const sk="app-seq:"+d.deviceId, seq=((await this.ctx.storage.get(sk))||0)+1;
    await this.ctx.storage.put(sk,seq);
    const c={id:crypto.randomUUID(),seq,deviceId:d.deviceId,action,parameters,status:"queued",createdAt:now(),completedAt:null,result:null};
    const k="app-cl:"+d.deviceId, list=(await this.ctx.storage.get(k))||[];
    list.push(c);
    await this.ctx.storage.put(k,list.slice(-100));
    return c;
  }
  async appCommands(deviceId,ownerKey,after=0,waitMs=0){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const until=Date.now()+Math.max(0,Math.min(20000,Number(waitMs||0))), key="app-cl:"+deviceId;
    while(true){
      const list=(await this.ctx.storage.get(key))||[], nowMs=Date.now();
      const found=[];
      let changed=false;
      for(let i=0;i<list.length;i++){
        const c=list[i];
        if(c.seq<=Number(after||0)) continue;
        const expired=c.status==="claimed"&&Number(c.leaseUntil||0)<=nowMs;
        if(c.status==="queued"||expired){
          list[i]={...c,status:"claimed",claimedAt:now(),leaseUntil:nowMs+45000};
          found.push(list[i]);
          changed=true;
          if(found.length>=4) break;
        }
      }
      if(changed) await this.ctx.storage.put(key,list.slice(-100));
      if(found.length||Date.now()>=until) return found;
      await new Promise(resolve=>setTimeout(resolve,650));
    }
  }
  async appComplete(deviceId,ownerKey,id,result={},status="completed"){
    if(!(await this.appAuth(deviceId,ownerKey))) throw new Error("Native app authorization failed");
    const k="app-cl:"+deviceId, list=(await this.ctx.storage.get(k))||[], i=list.findIndex(c=>c.id===id);
    if(i<0) return null;
    list[i]={...list[i],status:clean(status,30)||"completed",completedAt:now(),result};
    await this.ctx.storage.put(k,list.slice(-100));
    const d=(await this.ctx.storage.get("app-device:"+deviceId))||{};
    d.lastSeenAt=now();
    await this.ctx.storage.put("app-device:"+deviceId,d);
    return list[i];
  }
  async appCommand(ownerKey,id){
    const d=await this.appResolve(ownerKey);
    if(!d) throw new Error("Private App MCP credential rejected");
    const list=(await this.ctx.storage.get("app-cl:"+d.deviceId))||[];
    return list.find(c=>c.id===id)||null;
  }
  async appStatus(ownerKey){
    const d=await this.appResolve(ownerKey);
    if(!d) return {connected:false,error:"Private App MCP credential rejected"};
    const list=(await this.ctx.storage.get("app-cl:"+d.deviceId))||[];
    return {
      connected:true,
      device:((({ownerHash,...safe})=>safe)(d)),
      pendingCommands:list.filter(c=>c.status==="queued"||c.status==="claimed").length,
      lastCommand:list[list.length-1]||null,
      projectCount:Array.isArray(d.projects)?d.projects.length:0
    };
  }
}

const state = env => env.VIDEO_STATE.getByName("primary");
const out = x => ({content:[{type:"text",text:JSON.stringify(x)}]});

function serverFor(env){
  const s=new McpServer({name:"VideoStudio-MCP",version:"0.6.0"}), st=state(env);
  s.registerTool("server_status",{description:"Check VideoStudio MCP status.",inputSchema:{}},async()=>out({ok:true,service:"VideoStudio-MCP",version:"0.6.0",app:"/",capabilities:["Android app shell","device pairing","projects","local media","12-frame visual analysis","scene-change detection","quiet-section detection","multi-cut timeline editing","per-clip speed volume transforms filters and titles","render inspection","batched edit commands","adaptive local MP4/WebM rendering"]}));
  s.registerTool("device_status",{description:"Check a paired VideoStudio app device. Native v1 also accepts the private owner credential as deviceId for compatibility.",inputSchema:{deviceId:z.string().min(8)}},async({deviceId})=>{
    const native=await st.appResolve(deviceId);
    return out(native?await st.appStatus(deviceId):await st.status(deviceId));
  });
  s.registerTool("create_video_project",{description:"Create a VideoStudio project on a paired device.",inputSchema:{deviceId:z.string().min(8),name:z.string().min(1).max(120),instruction:z.string().max(5000).optional()}},async({deviceId,name,instruction})=>{
    if(await st.appResolve(deviceId)){
      const c=await st.appEnqueue(deviceId,"create_project",{name,instruction:instruction||""});
      return out({queued:true,commandId:c.id,sequence:c.seq,nativeApp:true});
    }
    return out(await st.createProject(deviceId,name,instruction||""));
  });
  s.registerTool("list_video_projects",{description:"List projects and synced local-media metadata.",inputSchema:{deviceId:z.string().min(8)}},async({deviceId})=>{
    const native=await st.appResolve(deviceId);
    if(native) return out({nativeApp:true,projects:native.projects||[]});
    return out(await st.projects(deviceId));
  });
  s.registerTool("get_video_project",{description:"Get timeline, assets, settings and latest command result.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}},async({deviceId,projectId})=>out((await st.project(deviceId,projectId))||{error:"Project not found"}));
  s.registerTool("queue_video_edit",{description:"Send one edit action to VideoStudio. Native v1 compatibility can use the private owner credential as deviceId and projectId='active-native'.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),action:z.enum(["set_trim","set_speed","set_mute","set_aspect","set_title","set_quality","set_transition","remove_clip","move_clip","reorder_timeline","replace_timeline","set_clip_speed","set_clip_title","set_clip_effects","analyse_media","inspect_render","render","autonomous_request"]),parameters:z.record(z.string(),z.any()).optional()}},async({deviceId,projectId,action,parameters})=>{
    try{
      const p=parameters||{};
      if(await st.appResolve(deviceId)){
        let nativeAction=p.nativeAction||"";
        let nativeParameters=p.nativeParameters||p;
        if(!nativeAction){
          if(action==="autonomous_request"&&Array.isArray(p.clips)) nativeAction="apply_edit_plan";
          else if(action==="set_clip_effects") nativeAction="apply_tool";
          else if(action==="render"||action==="inspect_render") nativeAction="preview_project";
          else if(action==="autonomous_request"&&String(p.instruction||"").toLowerCase().includes("preview")) nativeAction="preview_project";
          else nativeAction="get_state";
        }
        if(nativeAction==="apply_tool"&&!nativeParameters.tool){
          nativeParameters={clipIndex:Number(p.clipIndex||p.index||0),tool:p.tool||"effect",settings:p.settings||p.effects||p};
        }
        const c=await st.appEnqueue(deviceId,nativeAction,nativeParameters);
        return out({queued:true,commandId:c.id,sequence:c.seq,action:nativeAction,nativeApp:true});
      }
      const c=await st.enqueue(deviceId,projectId,action,p);
      return out({queued:true,commandId:c.id,sequence:c.seq,action,note:action==="render"?"Render runs locally. Keep the app open; a browser may require one tap before playback.":"The open app will apply this automatically."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("get_video_command_result",{description:"Get the result of a queued edit, media-analysis, render or native-app compatibility command.",inputSchema:{deviceId:z.string().min(8),commandId:z.string().min(8)}},async({deviceId,commandId})=>{
    const native=await st.appResolve(deviceId);
    if(native){
      const c=await st.appCommand(deviceId,commandId);
      return out(c||{error:"Command not found"});
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
  s.registerTool("queue_video_edit_batch",{description:"Queue an ordered batch of VideoStudio edits for one project. The app applies them sequentially while open. Put render last when you want an export after the edits.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),edits:z.array(z.object({action:z.enum(["set_trim","set_speed","set_mute","set_aspect","set_title","set_quality","set_transition","remove_clip","move_clip","reorder_timeline","replace_timeline","set_clip_speed","set_clip_title","set_clip_effects","analyse_media","inspect_render","render","autonomous_request"]),parameters:z.record(z.string(),z.any()).optional()})).min(1).max(20)}},async({deviceId,projectId,edits})=>{
    const queued=[];
    try{
      for(const edit of edits){
        const c=await st.enqueue(deviceId,projectId,edit.action,edit.parameters||{});
        queued.push({commandId:c.id,sequence:c.seq,action:c.action});
      }
      return out({queued:true,count:queued.length,commands:queued,note:"VideoStudio will apply these commands in sequence while the paired app is open."});
    }catch(e){ return out({queued:false,error:e.message,commands:queued}); }
  });
  s.registerTool("request_media_analysis",{description:"Run local visual, scene-change and quiet-section analysis. Returns a 12-frame contact sheet plus suggested structural timestamps without uploading the full video.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),assetId:z.string().min(8).optional(),start:z.number().min(0).optional(),end:z.number().positive().optional(),frames:z.number().int().min(6).max(16).optional(),includeAudio:z.boolean().optional()}},async({deviceId,projectId,assetId,start,end,frames,includeAudio})=>{
    try{
      const parameters={}; if(assetId)parameters.assetId=assetId; if(start!=null)parameters.start=start; if(end!=null)parameters.end=end; if(frames!=null)parameters.frames=frames; if(includeAudio!=null)parameters.includeAudio=includeAudio;
      const c=await st.enqueue(deviceId,projectId,"analyse_media",parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,note:"Keep VideoStudio open. The device will return sampled frames, scene changes and quiet sections."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("inspect_video_render",{description:"Inspect the actual latest local render with a contact sheet so ChatGPT can critique the finished edit and iterate.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),frames:z.number().int().min(6).max(16).optional()}},async({deviceId,projectId,frames})=>{
    try{ const c=await st.enqueue(deviceId,projectId,"inspect_render",{frames:frames||12}); return out({queued:true,commandId:c.id,sequence:c.seq,note:"The app will sample the latest render locally and return visual frames."}); }
    catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("queue_autonomous_edit",{description:"Send a structured autonomous edit plan with multi-cuts, per-clip reframing, filters, audio levels, titles, rendering and optional post-render inspection.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),instruction:z.string().min(1),clips:z.array(z.record(z.string(),z.any())).min(1).max(40),aspect:z.enum(["9:16","16:9","1:1","4:5"]).optional(),quality:z.enum(["720p","1080p"]).optional(),transition:z.enum(["none","fade"]).optional(),mute:z.boolean().optional(),render:z.boolean().optional(),inspectAfterRender:z.boolean().optional()}},async(args)=>{
    try{
      const {deviceId,projectId,...parameters}=args;
      const c=await st.enqueue(deviceId,projectId,"autonomous_request",parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,note:"Structured edit plan queued. VideoStudio will execute it locally."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("video_project_plan",{description:"Create a short autonomous editing workflow.",inputSchema:{projectName:z.string().min(1),instruction:z.string().min(1)}},async({projectName,instruction})=>out({projectName,instruction,status:"planned",workflow:["inspect asset metadata","run 12-frame scene and quiet-section analysis","visually inspect sampled frames","design a multi-cut timeline around real structural changes","apply per-clip pacing/reframing/audio only where justified","render locally","inspect the actual rendered contact sheet","iterate before declaring the edit finished"]}));
  return s;
}

function serverForApp(env,ownerKey){
  const s=new McpServer({name:"VideoStudio-App-MCP",version:"1.0.0"}), st=state(env);
  const queue=async(action,parameters={})=>{
    try{
      const c=await st.appEnqueue(ownerKey,action,parameters);
      return out({queued:true,commandId:c.id,sequence:c.seq,action});
    }catch(e){ return out({queued:false,error:e.message}); }
  };
  s.registerTool("app_status",{description:"Check the private native VideoStudio Android app connection, permission mode, project summaries and pending work.",inputSchema:{}},async()=>out(await st.appStatus(ownerKey)));
  s.registerTool("app_capabilities",{description:"Read the native v1 editing/control capabilities exposed to ChatGPT.",inputSchema:{}},async()=>out({
    version:"1.0.0",
    primary:"Android native app",
    capabilities:["native local projects","media import via Android picker","timeline trim/split","slow-motion and speed preview","green-screen parameter model","creator transitions","motion/keyframe preset model","colour/effect/mask model","private device-owned App MCP","HTTPS file import","bounded multitasking","thermal and memory guard","job cancellation"],
    permissionModes:["one_file","all_tools","everything"],
    renderEngine:"native safe export engine is the next v1 milestone; browser rendering is not used by this App MCP"
  }));
  s.registerTool("app_create_project",{description:"Create a project in the native VideoStudio app.",inputSchema:{name:z.string().min(1).max(120)}},async({name})=>queue("create_project",{name}));
  s.registerTool("app_select_project",{description:"Select an existing native VideoStudio project by ID.",inputSchema:{projectId:z.string().min(8)}},async({projectId})=>queue("select_project",{projectId}));
  s.registerTool("app_apply_edit_plan",{description:"Replace the active project's native timeline with an autonomous multi-cut plan. Clips reference already imported local asset IDs.",inputSchema:{clips:z.array(z.record(z.string(),z.any())).min(1).max(80)}},async({clips})=>queue("apply_edit_plan",{clips}));
  s.registerTool("app_apply_tool",{description:"Apply a native editing primitive to one timeline clip. Supported tool names include trim, speed, slow_motion, green_screen, transition, motion, effect, color, reframe, mask, title and volume.",inputSchema:{clipIndex:z.number().int().min(0),tool:z.string().min(1).max(80),settings:z.record(z.string(),z.any()).optional()}},async({clipIndex,tool,settings})=>queue("apply_tool",{clipIndex,tool,settings:settings||{}}));
  s.registerTool("app_preview_project",{description:"Ask the Android app to preview the active timeline locally.",inputSchema:{}},async()=>queue("preview_project",{}));
  s.registerTool("app_import_from_url",{description:"Import an HTTPS media URL directly into the native app without browsing the user's gallery. Requires Allow everything mode.",inputSchema:{url:z.string().url(),name:z.string().max(160).optional()}},async({url,name})=>queue("import_url",{url,name:name||"ChatGPT import"}));
  s.registerTool("app_cancel_job",{description:"Cancel a native VideoStudio background job.",inputSchema:{jobId:z.string().min(8)}},async({jobId})=>queue("cancel_job",{jobId}));
  s.registerTool("app_get_command_result",{description:"Read completion status/result for a native App MCP command.",inputSchema:{commandId:z.string().min(8)}},async({commandId})=>{
    try{ return out((await st.appCommand(ownerKey,commandId))||{error:"Command not found"}); }
    catch(e){ return out({error:e.message}); }
  });
  return s;
}

async function api(request,env){
  const u=new URL(request.url), st=state(env);
  if(request.method==="OPTIONS") return new Response(null,{status:204,headers:{"access-control-allow-origin":"*","access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type, authorization"}});
  try{
    if(u.pathname==="/api/app/register"&&request.method==="POST"){
      const b=await request.json();
      return reply({ok:true,device:await st.appRegister(b.deviceId,b.ownerKey,b.meta||{})});
    }
    if(u.pathname==="/api/app/commands"&&request.method==="GET"){
      const deviceId=u.searchParams.get("deviceId")||"", after=Number(u.searchParams.get("after")||0), wait=Number(u.searchParams.get("wait")||0);
      const token=bearer(request);
      return reply({commands:await st.appCommands(deviceId,token,after,wait)});
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
    if(u.pathname==="/api/commands"&&request.method==="GET") return reply({commands:await st.commands(u.searchParams.get("deviceId")||"",Number(u.searchParams.get("after")||0))});
    const cm=u.pathname.match(/^\/api\/commands\/([^/]+)\/complete$/);
    if(cm&&request.method==="POST"){ const b=await request.json(),c=await st.complete(b.deviceId,cm[1],b.result||{},b.status||"completed"); return c?reply({command:c}):reply({error:"Command not found"},404); }
    return reply({error:"API route not found"},404);
  }catch(e){ return reply({error:e.message||"Request failed"},400); }
}

const MANIFEST=JSON.stringify({name:"VideoStudio MCP",short_name:"VideoStudio",description:"Local-first video editor controlled through ChatGPT MCP.",start_url:"/",display:"standalone",background_color:"#080a0f",theme_color:"#0b0d13",icons:[{src:"/icon.svg",sizes:"any",type:"image/svg+xml",purpose:"any maskable"}]});
const ICON='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><defs><linearGradient id="g"><stop stop-color="#8b5cf6"/><stop offset="1" stop-color="#21d4fd"/></linearGradient></defs><rect width="512" height="512" rx="112" fill="#090b11"/><path d="M211 182v148l124-74-124-74z" fill="url(#g)"/><circle cx="256" cy="256" r="188" fill="none" stroke="url(#g)" stroke-width="18"/></svg>';
const SW='const C="videostudio-v2";self.addEventListener("install",e=>e.waitUntil(caches.open(C).then(c=>c.addAll(["/","/manifest.webmanifest","/icon.svg"]))));self.addEventListener("activate",e=>e.waitUntil(self.clients.claim()));self.addEventListener("fetch",e=>{if(e.request.method!=="GET")return;e.respondWith(fetch(e.request).then(r=>{const x=r.clone();if(!new URL(e.request.url).pathname.startsWith("/api/"))caches.open(C).then(c=>c.put(e.request,x));return r}).catch(()=>caches.match(e.request)))})';

export default {
  async fetch(request,env,ctx){
    const u=new URL(request.url);
    if(u.pathname==="/"&&request.method==="GET") return new Response(APP_HTML,{headers:{"content-type":"text/html; charset=UTF-8","cache-control":"no-store"}});
    if(u.pathname==="/manifest.webmanifest") return new Response(MANIFEST,{headers:{"content-type":"application/manifest+json"}});
    if(u.pathname==="/icon.svg") return new Response(ICON,{headers:{"content-type":"image/svg+xml"}});
    if(u.pathname==="/sw.js") return new Response(SW,{headers:{"content-type":"application/javascript","cache-control":"no-cache"}});
    if(u.pathname.startsWith("/api/")) return api(request,env);
    const appMcp=u.pathname.match(/^\/app-mcp\/([A-Za-z0-9_-]{32,})$/);
    if(appMcp){
      const ownerKey=appMcp[1];
      return createMcpHandler(()=>serverForApp(env,ownerKey),{route:u.pathname,responseMode:"auto"})(request,env,ctx);
    }
    if(u.pathname==="/mcp"||u.pathname.startsWith("/mcp/")) return createMcpHandler(()=>serverFor(env),{route:"/mcp",responseMode:"auto"})(request,env,ctx);
    if(u.pathname==="/mcp-v06"||u.pathname.startsWith("/mcp-v06/")) return createMcpHandler(()=>serverFor(env),{route:"/mcp-v06",responseMode:"auto"})(request,env,ctx);
    return new Response("Not Found",{status:404});
  }
};
