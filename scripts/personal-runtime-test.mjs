/** Real workerd/SQLite integration checks. Run only against the local QA origin. */
import assert from 'node:assert/strict';
import {writeFile,mkdir} from 'node:fs/promises';
const origin=process.env.PERSONAL_QA_ORIGIN||'http://127.0.0.1:8790';
if(!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(origin))throw new Error('QA seeding is restricted to loopback.');
const key='test_owner_alpha_'.padEnd(48,'a'),otherKey='test_owner_beta_'.padEnd(48,'b');
const device='d1867b43-bbcb-4a8e-852f-6c498f904aea',otherDevice='e3167b43-bbcb-4a8e-852f-6c498f904aeb';
let cookie='',sessionHeader='',protocol='2025-03-26',seq=0;
const results=[];
const ok=name=>{results.push({test:name,status:'passed'});console.log('PASS:',name);};
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function request(path,body,headers={}) {const response=await fetch(origin+path,{method:body===undefined?'GET':'POST',headers:{...(body===undefined?{}:{origin,'content-type':'application/json'}),...(cookie?{cookie}:{}),...headers},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)});return response;}
async function json(path,body,headers={}){const r=await request(path,body,headers);const data=await r.json();assert.equal(r.status,200,path+': '+JSON.stringify(data));return data;}
async function register(owner=key,id=device,permissionMode='everything') {return json('/api/v3/app/register',{deviceId:id,ownerKey:owner,meta:{name:'Synthetic CI phone',appVersion:'3.4.11',protocolVersion:3,protocolMin:3,protocolMax:3,appGeneration:1,permissionMode,controlPaused:false,projects:[]}});}
async function login(owner=key){const r=await request('/api/personal/session',{ownerKey:owner});assert.equal(r.status,200);const set=r.headers.get('set-cookie');assert.match(set,/HttpOnly/);assert.match(set,/Secure/);cookie=set.split(';')[0];return cookie;}
async function mcp(method,params={}) {
  const r=await fetch(origin+'/app-mcp-v3/'+key,{method:'POST',headers:{'content-type':'application/json',accept:'application/json, text/event-stream','mcp-protocol-version':protocol,...(sessionHeader?{'mcp-session-id':sessionHeader}:{})},body:JSON.stringify({jsonrpc:'2.0',id:++seq,method,params}),signal:AbortSignal.timeout(20000)});
  if(r.headers.get('mcp-session-id'))sessionHeader=r.headers.get('mcp-session-id');
  const text=await r.text();assert.equal(r.status,200,'MCP transport: '+text.slice(0,300));
  const frames=text.startsWith('data:')||text.startsWith('event:')?text.split(/\r?\n/).filter(line=>line.startsWith('data:')).map(line=>JSON.parse(line.slice(5).trim())):[JSON.parse(text)];
  const envelope=frames.find(item=>item.id===seq);assert.ok(envelope,'MCP response ID must match.');assert.ok(!envelope.error,JSON.stringify(envelope.error));return envelope.result;
}
async function tool(name,args={}){const result=await mcp('tools/call',{name,arguments:args});assert.ok(!result.isError,JSON.stringify(result));if(result.structuredContent)return result.structuredContent;for(const item of result.content||[]){if(item.type==='text'){try{return JSON.parse(item.text);}catch{}}}throw new Error('MCP tool did not return structured JSON.');}
function find(value,predicate,depth=0){if(!value||typeof value!=='object'||depth>6)return null;if(predicate(value))return value;for(const child of Object.values(value)){const found=find(child,predicate,depth+1);if(found)return found;}return null;}

if(process.argv[2]==='seed') {
  for(let i=0;i<60;i++){try{const r=await fetch(origin+'/personal/',{signal:AbortSignal.timeout(2000)});if(r.status===200)break;}catch{}if(i===59)throw new Error('Local Worker did not become ready.');await delay(1000);}
  assert.equal((await request('/api/personal/snapshot')).status,401);
  await register();await register(otherKey,otherDevice);await login();
  const cloud=await json('/api/personal/snapshot');assert.equal(cloud.cloud.available,true);
  const projects=[];
  for(const [name,brief,count] of [['Afterglow','A city unwinds. A story begins.',4],['Quiet water','A slower kind of story, told by the shore.',3],['The small things','Finding a little wonder in the everyday.',5]]) {
    const result=await json('/api/personal/execute',{operation:'project.create',data:{name,brief,shots:Array.from({length:count},(_,i)=>({title:'Sequence '+(i+1),description:'A planned moment in the story.',durationSeconds:3}))},requestId:'seed-'+name.replaceAll(' ','-')});
    assert.equal(result.executionSurface,'cloud');assert.equal(result.status,'completed');projects.push(result.result.project);
  }
  ok('Real Durable Object inheritance, SQLite persistence and same-origin login');
  await login(otherKey);assert.equal((await request('/api/personal/projects/'+projects[0].id)).status,404);await login();
  const stale=await request('/api/personal/execute',{operation:'project.update',data:{id:projects[0].id,expectedRevision:999,patch:{notes:'Stale attempt'}},requestId:'runtime-stale'});assert.equal(stale.status,409);
  const csrf=await request('/api/personal/execute',{operation:'project.create',data:{name:'Injected'},requestId:'runtime-csrf'},{origin:'https://invalid.example'});assert.equal(csrf.status,403);
  ok('Real RPC preserves authorization, ownership, conflict and CSRF errors');
  await register(key,device,'one_file');const paused=await request('/api/personal/execute',{operation:'project.create',data:{name:'Forbidden'},requestId:'runtime-scope'});assert.equal(paused.status,423);await register();
  ok('Native One File Lock still blocks new cloud writes');
  await mkdir('.work',{recursive:true});await writeFile('.work/runtime-seed-results.json',JSON.stringify(results,null,2));
} else {
  await login();
  for(let i=0;i<55;i++) {const state=await json('/api/v3/app/status',undefined,{authorization:'Bearer '+key});assert.equal(state.personalCloud.available,true);if(!state.nativeConnected)break;if(i===54)throw new Error('Synthetic native heartbeat did not expire.');await delay(1000);}
  const init=await mcp('initialize',{protocolVersion:protocol,capabilities:{},clientInfo:{name:'videostudio-personal-qa',version:'1.0.0'}});protocol=init.protocolVersion;
  const s=await tool('app_status');assert.equal(s.nativeConnected,false);assert.equal(s.personalCloud.available,true);
  const queued=await tool('app_execute',{action:'personal_cloud',parameters:{operation:'project.create',data:{name:'MCP cloud verification'},requestId:'runtime-mcp-create'}});
  const holder=find(queued,v=>typeof v.commandId==='string'&&v.commandId.startsWith('pc_'));assert.ok(holder,'MCP must return a cloud command ID.');
  const read=await tool('app_get_command_result',{commandId:holder.commandId});const command=find(read,v=>v.id===holder.commandId&&v.status==='completed');assert.ok(command,'MCP must return completed cloud receipt.');assert.equal(command.executionSurface,'cloud');assert.equal(command.result.project.name,'MCP cloud verification');
  const persisted=await json('/api/personal/projects/'+command.result.project.id);assert.equal(persisted.project.name,'MCP cloud verification');
  await json('/api/personal/execute',{operation:'project.delete',data:{id:persisted.project.id,expectedRevision:persisted.project.revision},requestId:'runtime-mcp-cleanup'});
  ok('Real MCP create/result and website read succeed with no native executor or browser');
  const native=await tool('app_execute',{action:'get_state',parameters:{}});const nativeHolder=find(native,v=>typeof v.commandId==='string');assert.ok(nativeHolder);
  const nativeRead=await tool('app_get_command_result',{commandId:nativeHolder.commandId});const nativeCommand=find(nativeRead,v=>v.id===nativeHolder.commandId);assert.ok(nativeCommand);assert.equal(nativeCommand.status,'waiting_native');
  ok('Legacy native queue remains distinct and truthfully waits for the phone');
  await writeFile('.work/runtime-mcp-results.json',JSON.stringify(results,null,2));
}
