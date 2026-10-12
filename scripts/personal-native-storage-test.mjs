/** Actual native relay + personal HTTP inheritance, with real chunk storage. */
import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import {nativeCommandSummary,nativeResultPage} from '../src/native-result-pages.js';
import {withPersonalStudio,hash} from '../src/personal-state.js';
import {handlePersonal} from '../src/personal-http.js';

const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const end=source.indexOf('\n}\n',source.indexOf('export class VideoStudioState'))+3;
const context={...storyboardContract,...queueBindings,nativeCommandSummary,nativeResultPage,
  crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,
  DurableObject:class{constructor(ctx){this.ctx=ctx;}}};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
const PersonalState=withPersonalStudio(context.VideoStudioState),origin='https://personal.test';
const owner='test_chunked_owner_'.padEnd(48,'a'),otherOwner='test_chunked_other_'.padEnd(48,'b');
const deviceId='personal-chunked-native',otherId='personal-other-native';
const meta={appVersion:'3.7.2',protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything',controlPaused:false};
async function fixture(){
  const rows=new Map(),storage=memoryStorage(rows,{maxValueBytes:128*1024}),state=new PersonalState({storage},{});
  await state.appRegister(deviceId,owner,{...meta,projects:[{id:'owned-native-project',name:'Native original',sourcePrompt:'p'.repeat(230*1024),clipCount:2,durationMs:1000}]});
  await state.appRegister(otherId,otherOwner,meta);
  return {rows,storage,state};
}
function send(state,path,{body,cookie}={}){
  return handlePersonal(new Request(origin+path,{method:body===undefined?'GET':'POST',headers:{...(cookie?{cookie}:{}),
    ...(body===undefined?{}:{origin,'content-type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})}),{},state);
}
async function login(state,key=owner){
  const response=await send(state,'/api/personal/session',{body:{ownerKey:key}}),body=await response.json();
  assert.equal(response.status,200,JSON.stringify(body));assert.equal(body.ok,true);
  assert.ok(!JSON.stringify(body).includes(key));assert.ok(!JSON.stringify(body).includes('ownerHash'));
  const cookie=response.headers.get('set-cookie');assert.match(cookie,/HttpOnly/);assert.match(cookie,/Secure/);
  return cookie.split(';')[0];
}
test('real chunked native registration authenticates personal login, cookie restore and cloud writes',async()=>{
  const f=await fixture();assert.equal(f.rows.has('app-device:'+deviceId),false);
  assert.ok([...f.rows.keys()].filter(key=>key.startsWith('app-device:'+deviceId+':device-json:chunks:v1:')).length>=4);
  const cookie=await login(f.state),snapshot=await send(f.state,'/api/personal/snapshot',{cookie});
  assert.equal(snapshot.status,200);const view=await snapshot.json();assert.equal(view.native.projects[0].id,'owned-native-project');
  assert.ok(!JSON.stringify(view).includes(owner));assert.ok(!JSON.stringify(view).includes('ownerHash'));
  const result=await send(f.state,'/api/personal/execute',{cookie,body:{operation:'project.create',data:{name:'Chunked login film',nativeProjectId:'owned-native-project'},requestId:'chunked-http-create'}});
  assert.equal(result.status,200);const command=await result.json();assert.equal(command.executionSurface,'cloud');
  const restarted=new PersonalState({storage:f.storage},{}),read=await send(restarted,'/api/personal/projects/'+command.result.project.id,{cookie});
  assert.equal(read.status,200);assert.equal((await read.json()).project.name,'Chunked login film');
  const otherCookie=await login(restarted,otherOwner);
  assert.equal((await send(restarted,'/api/personal/projects/'+command.result.project.id,{cookie:otherCookie})).status,404);
});
test('personal activity reads the actual chunked native queue without exposing command payloads',async()=>{
  const f=await fixture(),cookie=await login(f.state);
  const command=await f.state.appEnqueueV3(owner,'get_state',{note:'n'.repeat(180*1024),sourceUrl:'https://private.example/source',ownerKey:'private-command-key'});
  assert.equal(f.rows.has('app-v3-cl:'+deviceId),false);
  const response=await send(f.state,'/api/personal/snapshot',{cookie});assert.equal(response.status,200);
  const view=await response.json(),job=view.native.jobs.find(row=>row.id===command.id);assert.ok(job);assert.equal(job.status,command.status);
  assert.ok(!JSON.stringify(view).includes('private-command-key'));assert.ok(!JSON.stringify(view).includes('private.example'));
});
test('legacy inline registration and its existing session remain valid after native chunk migration',async()=>{
  const rows=new Map(),storage=memoryStorage(rows,{maxValueBytes:128*1024}),state=new PersonalState({storage},{});
  const ownerHash=await hash(owner);await storage.put('app-owner:'+ownerHash,deviceId);
  await storage.put('app-device:'+deviceId,{deviceId,ownerHash,...meta,projects:[],lastSeenAt:new Date().toISOString()});
  const cookie=await login(state);await state.appRegister(deviceId,owner,meta);
  assert.equal(rows.has('app-device:'+deviceId),false);
  assert.equal((await send(state,'/api/personal/snapshot',{cookie})).status,200);
});
test('missing native metadata chunks fail closed without replacing the accepted owner or issuing a session',async()=>{
  const f=await fixture(),cookie=await login(f.state);
  const chunk=[...f.rows.keys()].find(key=>key.startsWith('app-device:'+deviceId+':device-json:chunks:v1:'));assert.ok(chunk);await f.storage.delete(chunk);
  const before=f.storage.writes(),ownerIndex=await f.storage.get('app-owner:'+await hash(owner));
  const loginResponse=await send(f.state,'/api/personal/session',{body:{ownerKey:owner}});assert.equal(loginResponse.status,500);
  assert.equal(loginResponse.headers.get('set-cookie'),null);
  assert.equal((await send(f.state,'/api/personal/snapshot',{cookie})).status,500);
  assert.equal(f.storage.writes(),before);assert.equal(await f.storage.get('app-owner:'+await hash(owner)),ownerIndex);
});
test('chunk-backed native pause and narrow scope still prohibit personal cloud writes',async()=>{
  const f=await fixture(),cookie=await login(f.state);
  for(const change of [{controlPaused:true},{permissionMode:'one_file'}]){
    await f.state.appRegister(deviceId,owner,{...meta,...change});
    const response=await send(f.state,'/api/personal/execute',{cookie,body:{operation:'project.create',data:{name:'Blocked'},requestId:'blocked-'+Object.keys(change)[0]}});
    assert.equal(response.status,423);
  }
  const snapshot=await send(f.state,'/api/personal/snapshot',{cookie});assert.equal((await snapshot.json()).cloud.scopeRestricted,true);
});
test('superseded device cookies cannot transfer authority while the proven owner key follows the canonical binding',async()=>{
  const f=await fixture(),cookie=await login(f.state);
  await f.state.appConvergeOwnerAliases(otherOwner,[owner],otherId);
  assert.equal((await send(f.state,'/api/personal/snapshot',{cookie})).status,401);
  const currentCookie=await login(f.state),snapshot=await send(f.state,'/api/personal/snapshot',{cookie:currentCookie});
  assert.equal(snapshot.status,200);assert.deepEqual((await snapshot.json()).native.projects,[]);
});
