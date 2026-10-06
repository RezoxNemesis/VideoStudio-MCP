import { DurableObject } from "cloudflare:workers";
import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";
import APP_HTML from "./app.html";

const JH = {"content-type":"application/json; charset=UTF-8","cache-control":"no-store"};
const now = () => new Date().toISOString();
const clean = (v,n=5000) => String(v ?? "").trim().slice(0,n);
const reply = (x,s=200) => new Response(JSON.stringify(x),{status:s,headers:JH});

export class VideoStudioState extends DurableObject {
  constructor(ctx,env){ super(ctx,env); }
  async register(deviceId,meta={}){
    const k="d:"+deviceId, old=(await this.ctx.storage.get(k))||{};
    const d={deviceId,name:clean(meta.name||old.name||"My device",80),platform:clean(meta.platform||old.platform||"web",80),appVersion:"0.5.0",createdAt:old.createdAt||now(),lastSeenAt:now()};
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
}

const state = env => env.VIDEO_STATE.getByName("primary");
const out = x => ({content:[{type:"text",text:JSON.stringify(x)}]});

function serverFor(env){
  const s=new McpServer({name:"VideoStudio-MCP",version:"0.5.0"}), st=state(env);
  s.registerTool("server_status",{description:"Check VideoStudio MCP status.",inputSchema:{}},async()=>out({ok:true,service:"VideoStudio-MCP",version:"0.5.0",app:"/",capabilities:["device pairing","projects","local media","visual contact-sheet analysis","multi-cut timeline editing","per-clip speed and titles","batched edit commands","adaptive local MP4/WebM rendering"]}));
  s.registerTool("device_status",{description:"Check a paired VideoStudio app device.",inputSchema:{deviceId:z.string().min(8)}},async({deviceId})=>out(await st.status(deviceId)));
  s.registerTool("create_video_project",{description:"Create a VideoStudio project on a paired device.",inputSchema:{deviceId:z.string().min(8),name:z.string().min(1).max(120),instruction:z.string().max(5000).optional()}},async({deviceId,name,instruction})=>out(await st.createProject(deviceId,name,instruction||"")));
  s.registerTool("list_video_projects",{description:"List projects and synced local-media metadata.",inputSchema:{deviceId:z.string().min(8)}},async({deviceId})=>out(await st.projects(deviceId)));
  s.registerTool("get_video_project",{description:"Get timeline, assets, settings and latest command result.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8)}},async({deviceId,projectId})=>out((await st.project(deviceId,projectId))||{error:"Project not found"}));
  s.registerTool("queue_video_edit",{description:"Send one edit action to the open VideoStudio app. Supports trimming, project settings, clip removal/movement, full multi-cut timeline replacement, per-clip speed/title, media analysis, rendering and autonomous requests. Media stays local on the device.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),action:z.enum(["set_trim","set_speed","set_mute","set_aspect","set_title","set_quality","set_transition","remove_clip","move_clip","reorder_timeline","replace_timeline","set_clip_speed","set_clip_title","analyse_media","render","autonomous_request"]),parameters:z.record(z.string(),z.any()).optional()}},async({deviceId,projectId,action,parameters})=>{
    try{ const c=await st.enqueue(deviceId,projectId,action,parameters||{}); return out({queued:true,commandId:c.id,sequence:c.seq,action,note:action==="render"?"Render runs locally. Keep the app open; a browser may require one tap before playback.":"The open app will apply this automatically."}); }
    catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("get_video_command_result",{description:"Get the result of a queued edit, media-analysis, or render command. Visual analysis results include an image content block that ChatGPT can inspect.",inputSchema:{deviceId:z.string().min(8),commandId:z.string().min(8)}},async({deviceId,commandId})=>{
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
  s.registerTool("queue_video_edit_batch",{description:"Queue an ordered batch of VideoStudio edits for one project. The app applies them sequentially while open. Put render last when you want an export after the edits.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),edits:z.array(z.object({action:z.enum(["set_trim","set_speed","set_mute","set_aspect","set_title","set_quality","set_transition","remove_clip","move_clip","reorder_timeline","replace_timeline","set_clip_speed","set_clip_title","analyse_media","render","autonomous_request"]),parameters:z.record(z.string(),z.any()).optional()})).min(1).max(20)}},async({deviceId,projectId,edits})=>{
    const queued=[];
    try{
      for(const edit of edits){
        const c=await st.enqueue(deviceId,projectId,edit.action,edit.parameters||{});
        queued.push({commandId:c.id,sequence:c.seq,action:c.action});
      }
      return out({queued:true,count:queued.length,commands:queued,note:"VideoStudio will apply these commands in sequence while the paired app is open."});
    }catch(e){ return out({queued:false,error:e.message,commands:queued}); }
  });
  s.registerTool("request_media_analysis",{description:"Ask the open VideoStudio app to sample a local video or image and return a compact contact sheet for visual inspection. The full media file stays on the user device.",inputSchema:{deviceId:z.string().min(8),projectId:z.string().min(8),assetId:z.string().min(8).optional()}},async({deviceId,projectId,assetId})=>{
    try{
      const c=await st.enqueue(deviceId,projectId,"analyse_media",assetId?{assetId}:{});
      return out({queued:true,commandId:c.id,sequence:c.seq,note:"Keep VideoStudio open. Call get_video_command_result after the device completes analysis."});
    }catch(e){ return out({queued:false,error:e.message}); }
  });
  s.registerTool("video_project_plan",{description:"Create a short autonomous editing workflow.",inputSchema:{projectName:z.string().min(1),instruction:z.string().min(1)}},async({projectName,instruction})=>out({projectName,instruction,status:"planned",workflow:["inspect asset metadata","request local contact-sheet analysis","visually inspect sampled frames","design a multi-cut timeline","apply per-clip pacing and titles","queue edit commands","request local render","read render result","iterate"]}));
  return s;
}

async function api(request,env){
  const u=new URL(request.url), st=state(env);
  if(request.method==="OPTIONS") return new Response(null,{status:204,headers:{"access-control-allow-origin":"*","access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type"}});
  try{
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
    if(u.pathname==="/mcp"||u.pathname.startsWith("/mcp/")) return createMcpHandler(()=>serverFor(env),{route:"/mcp",responseMode:"auto"})(request,env,ctx);
    return new Response("Not Found",{status:404});
  }
};
