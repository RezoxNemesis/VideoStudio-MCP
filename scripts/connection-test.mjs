import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {ProjectMetadataMirror} from '../src/project-metadata-mirror.js';
import * as commandQueueStore from '../src/command-queue-store.js';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
// Run the real relay class with an in-memory Durable Object store; SDK/network are not needed.
const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const end=source.indexOf('\n}\n',source.indexOf('export class VideoStudioState'))+3;
const context={crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date,ProjectMetadataMirror,...commandQueueStore};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
async function fixture(commands=[]) {
 const store=new Map(); const storage={get:async k=>structuredClone(store.get(k)),put:async(k,v)=>store.set(k,structuredClone(v)),delete:async k=>store.delete(k)};
 storage.list=async({prefix,limit})=>new Map([...store].filter(([key])=>key.startsWith(prefix)).slice(0,limit));
 storage.transaction=async callback=>callback(storage);
 const relay=new context.VideoStudioState({storage},{});
 const key='x'.repeat(43),deviceId='test-device-001';
 await relay.appRegister(deviceId,key,{protocolVersion:3,appGeneration:1,permissionMode:'everything'});
 await storage.put('app-v3-cl:'+deviceId,commands);
 return {relay,key,deviceId,storage};
}
const command=(seq,status,leaseUntil=0)=>({id:'cmd-'+seq,seq,protocolVersion:3,action:'ping',parameters:{},status,leaseUntil});
test('out-of-order acknowledgement cannot hide an older expired lease',async()=>{
 const f=await fixture([command(1,'claimed',Date.now()-1),command(2,'completed')]);
 const found=await f.relay.appCommandsV3(f.deviceId,f.key,2,0);
 assert.equal(found.length,1);assert.equal(found[0].id,'cmd-1');assert.equal(found[0].claimCount,1);
});
test('queued work behind a cursor is reconciled, while active leases and terminal results are excluded',async()=>{
 const f=await fixture([command(1,'queued'),command(2,'claimed',Date.now()+60000),command(3,'failed'),command(4,'completed')]);
 const found=await f.relay.appCommandsV3(f.deviceId,f.key,4,0);
 assert.deepEqual(Array.from(found,x=>x.seq),[1]);
});
test('replayed completion acknowledges the same command without requeue',async()=>{
 const f=await fixture([command(1,'claimed')]);
 await f.relay.appCompleteV3(f.deviceId,f.key,'cmd-1',{ok:true},'completed');
 const receipt=await f.relay.appCompleteV3(f.deviceId,f.key,'cmd-1',{ok:true},'completed');
 assert.equal(receipt.id,'cmd-1'); assert.equal(receipt.status,'completed');
 assert.equal((await f.relay.appCommandsV3(f.deviceId,f.key,0,0)).length,0);
});
test('new commands preserve old unfinished work when terminal history is trimmed',async()=>{
 const f=await fixture([command(1,'queued'),...Array.from({length:159},(_,i)=>command(i+2,'completed'))]);
 await f.storage.put('app-v3-seq:'+f.deviceId,160);
 await f.relay.appEnqueueV3(f.key,'ping',{});
 const rows=await commandQueueStore.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
 assert.equal(rows.length,64);assert.ok(rows.some(c=>c.id==='cmd-1'));assert.ok(rows.some(c=>c.seq===161));
 assert.equal(await f.storage.get('app-v3-cl:'+f.deviceId),undefined);
});
test('saturated queue refuses new work rather than discarding existing commands',async()=>{
 const f=await fixture(Array.from({length:160},(_,i)=>command(i+1,'queued')));
 await assert.rejects(f.relay.appEnqueueV3(f.key,'ping',{}),/queue is full/);
 assert.equal((await f.storage.get('app-v3-cl:'+f.deviceId)).length,160);
});
test('wrong owner credential cannot read or complete commands',async()=>{
 const f=await fixture([command(1,'queued')]);
 await assert.rejects(f.relay.appCommandsV3(f.deviceId,'y'.repeat(43),0,0),/authorization failed/);
 await assert.rejects(f.relay.appCompleteV3(f.deviceId,'y'.repeat(43),'cmd-1',{}),/authorization failed/);
});

test('title creation keeps authoring revision and cannot forge owner priority or durable command identity',async()=>{
 const f=await fixture();
 const queued=await f.relay.appEnqueueV3(f.key,'create_title',{
  projectId:'project-title-1',expectedRevision:4,text:'Editable title',startMs:250,durationMs:3000,
  style:{fontFamily:'sans-serif-medium',textSize:.06,textY:.8},
  _ownerInitiated:true,_mcpCommandId:'forged-command',_recoveryPlanId:'forged-plan'
 });
 assert.equal(queued.action,'create_title');assert.equal(queued.parameters.expectedRevision,4);
 assert.equal(queued.parameters.text,'Editable title');assert.equal(queued.parameters.startMs,250);
 assert.equal(Object.keys(queued.parameters).some(key=>key.startsWith('_')),false);
 for(const patch of [{controlPaused:true},{permissionMode:'one_file'}]){
  const locked=await fixture();const device=await locked.storage.get('app-device:'+locked.deviceId);
  await locked.storage.put('app-device:'+locked.deviceId,{...device,...patch});
  await assert.rejects(locked.relay.appEnqueueV3(locked.key,'create_title',{text:'Blocked'}),/paused|permission/);
  assert.equal((await locked.storage.get('app-v3-cl:'+locked.deviceId)).length,0);
 }
});

test('real bounded cel documents cross the old aggregate value limit and retain exact stroke parameters',async()=>{
 const f=await fixture();
 const drawing={version:1,width:512,height:512,background:'#00000000',strokes:[{id:'paint-a',type:'paint',color:'#FF112233',width:.012,
  points:Array.from({length:3000},()=>({x:.5,y:.5,pressure:1}))}]};
 assert.ok(new TextEncoder().encode(JSON.stringify(drawing)).byteLength<128*1024);
 const first=await f.relay.appEnqueueV3(f.key,'cel_create',{projectId:'project-cel-1',expectedRevision:3,drawing,exposure:{startFrame:0,frameCount:2,fpsNumerator:24,fpsDenominator:1},_ownerInitiated:true});
 const second=await f.relay.appEnqueueV3(f.key,'cel_update',{projectId:'project-cel-1',expectedRevision:4,clipId:'cel-clip-1',drawing,exposure:{frameCount:3,fpsNumerator:24,fpsDenominator:1}});
 const rows=await commandQueueStore.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
 assert.ok(new TextEncoder().encode(JSON.stringify(rows)).byteLength>128*1024);
 const claimed=await f.relay.appCommandsV3(f.deviceId,f.key,second.seq,0);
 assert.equal(claimed.length,2);assert.equal(claimed[0].id,first.id);assert.equal(claimed[1].id,second.id);
 assert.deepEqual(claimed[1].parameters.drawing,drawing);
 assert.equal(claimed[1].parameters.exposure.frameCount,3);
 assert.equal(Object.hasOwn(claimed[0].parameters,'_ownerInitiated'),false);
});

test('terminal command receipts cannot be reopened or changed by a delayed acknowledgement',async()=>{
 const f=await fixture([command(1,'claimed')]);
 const result={ok:true,assetId:'actual-cel-asset',pngSha256:'a'.repeat(64)};
 await f.relay.appCompleteV3(f.deviceId,f.key,'cmd-1',result,'completed');
 await assert.rejects(f.relay.appCompleteV3(f.deviceId,f.key,'cmd-1',{ok:false},'completed'),/Conflicting terminal/);
 await assert.rejects(f.relay.appCompleteV3(f.deviceId,f.key,'cmd-1',result,'queued'),/terminal status/);
 assert.deepEqual((await f.relay.appCommandV3(f.key,'cmd-1')).result,result);
 assert.equal((await f.relay.appCommandsV3(f.deviceId,f.key,0,0)).length,0);
});

test('paused owner state rejects private handoff metadata before creating an import bridge',async()=>{
 const f=await fixture();const device=await f.storage.get('app-device:'+f.deviceId);
 await f.storage.put('app-device:'+f.deviceId,{...device,controlPaused:true});
 await assert.rejects(f.relay.appCreateHandoff(f.key,'https://files.example.com/image.png',{}),/paused/);
 await assert.rejects(f.relay.appCreateCachedHandoff(f.key,'https://worker.example.com/__private/upload',{}),/paused/);
 const worker=loadWorkerForRouteTests();
 const result=await worker.fetch(new Request('https://worker.example.com/api/v3/app/private/upload?deviceId='+f.deviceId,{
  method:'POST',headers:{authorization:'Bearer '+f.key},body:'private bytes'}),{VIDEO_STATE:{getByName:()=>f.relay}},{});
 assert.equal(result.status,403);assert.match((await result.json()).error,/paused/);
});

const metadataSync=()=>({projectId:'project-mirror-1',sourceRevision:1,expectedMirrorRevision:0,enabled:true,
 projectGraph:{id:'project-mirror-1',revision:1,name:'Private graph',
  assets:[{id:'asset-mirror-1',mime:'video/mp4',durationMs:5000}],
  tracks:[{id:'track-mirror-1',type:'video',order:0}],
  clips:[{id:'clip-mirror-1',assetId:'asset-mirror-1',trackId:'track-mirror-1',startMs:0,inMs:0,outMs:5000}]}});

test('metadata mirror owner scopes guard every graph effect before storage changes',async()=>{
 for(const patch of [{controlPaused:true},{permissionMode:'one_file'}]){
  const f=await fixture();
  const device=await f.storage.get('app-device:'+f.deviceId);
  await f.storage.put('app-device:'+f.deviceId,{...device,...patch});
  for(const operation of ['sync','edit','reconcile','revoke'])
   await assert.rejects(f.relay.appMetadataMirror(f.key,operation,metadataSync()),/pause or One File Lock/);
  const read=await f.relay.appMetadataMirror(f.key,'get',{projectId:'project-mirror-1'});
  assert.equal(read.enabled,false);assert.equal(read.ownerScope.mutationAllowed,false);
  assert.equal(await f.storage.get('app-metadata-mirror:'+f.deviceId+':project-mirror-1'),undefined);
 }
});

test('metadata mirror rejects wrong owner and rechecks owner binding inside transaction',async()=>{
 const f=await fixture();
 await assert.rejects(f.relay.appMetadataMirror('y'.repeat(43),'sync',metadataSync()),/owner|native/i);
 const device=await f.storage.get('app-device:'+f.deviceId);
 f.storage.transaction=async callback=>{
  await f.storage.delete('app-owner:'+device.ownerHash);
  return callback(f.storage);
 };
 await assert.rejects(f.relay.appMetadataMirror(f.key,'sync',metadataSync()),/owner identity/);
 assert.equal(await f.storage.get('app-metadata-mirror:'+f.deviceId+':project-mirror-1'),undefined);
});

test('filling the last free queue slot retains every pending command',async()=>{
 const pending=Array.from({length:63},(_,i)=>command(i+1,'queued'));
 const f=await fixture([...pending,command(64,'completed')]);
 await f.storage.put('app-v3-seq:'+f.deviceId,64);
 await f.relay.appEnqueueV3(f.key,'ping',{});
 const rows=await commandQueueStore.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
 assert.equal(rows.length,64);assert.ok(rows.some(c=>c.id==='cmd-1'));
 assert.equal(rows.filter(c=>c.status==='queued').length,64);
});


function loadWorkerForRouteTests() {
 const workerContext={
  crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,setTimeout,clearTimeout,Date,ProjectMetadataMirror,...commandQueueStore,
  DurableObject:class{constructor(ctx){this.ctx=ctx;}},
  McpServer:class{},
  createMcpHandler:()=>()=>new Response(null,{status:204}),
  z:{},
  APP_HTML:"<html><body>studio</body></html>",
  STUDIO_RUNTIME_JS:"",STUDIO_CINEMATIC_JS:"",STUDIO_NEURAL_JS:"",STUDIO_TEMPORAL_JS:""
 };
 vm.createContext(workerContext);
 const transformed=source
  .replace(/^import .*$/gm,"")
  .replace("export class VideoStudioState","class VideoStudioState")
  .replace("export default {","globalThis.worker = {");
 vm.runInContext(transformed,workerContext);
 return workerContext.worker;
}

test('stable Studio Web MCP requires the configured bearer while the editor page stays public',async()=>{
 const worker=loadWorkerForRouteTests();
 const env={VIDEOSTUDIO_STUDIO_MCP_BEARER:"studio-secret"};

 const missing=await worker.fetch(new Request("https://example.test/mcp-v06",{method:"POST"}),env,{});
 assert.equal(missing.status,401);

 const wrong=await worker.fetch(new Request("https://example.test/mcp-v06",{method:"POST",headers:{authorization:"Bearer wrong"}}),env,{});
 assert.equal(wrong.status,401);

 const good=await worker.fetch(new Request("https://example.test/mcp-v06",{method:"POST",headers:{authorization:"Bearer studio-secret"}}),env,{});
 assert.equal(good.status,204);

 const nested=await worker.fetch(new Request("https://example.test/mcp-v06/messages",{method:"POST",headers:{authorization:"Bearer studio-secret"}}),env,{});
 assert.equal(nested.status,204);

 const root=await worker.fetch(new Request("https://example.test/",{method:"GET"}),env,{});
 assert.equal(root.status,200);
});

test('native metadata REST uses private owner/device/generation authentication and actual stored graph',async()=>{
 const f=await fixture();const worker=loadWorkerForRouteTests();
 const env={VIDEO_STATE:{getByName:()=>f.relay}};
 const request=(body,key=f.key)=>new Request('https://example.test/api/v3/app/metadata-mirror',{
  method:'POST',headers:{authorization:'Bearer '+key,'content-type':'application/json'},body:JSON.stringify(body)});
 const body={deviceId:f.deviceId,appGeneration:1,operation:'sync',parameters:metadataSync()};
 assert.equal((await worker.fetch(request(body,'y'.repeat(43)),env,{})).status,401);
 assert.equal((await worker.fetch(request({...body,deviceId:'another-device'}),env,{})).status,401);
 assert.equal((await worker.fetch(request({...body,appGeneration:0}),env,{})).status,409);
 const synced=await worker.fetch(request(body),env,{});
 assert.equal(synced.status,200);
 const result=await synced.json();
 assert.equal(result.graph.clips[0].id,'clip-mirror-1');
 assert.equal(result.mediaUploaded,false);assert.equal(result.executorAvailable,false);
 const read=await worker.fetch(request({...body,operation:'get',parameters:{projectId:'project-mirror-1'}}),env,{});
 assert.equal((await read.json()).mirrorRevision,1);
});

test('native metadata REST bounds chunked actual bytes before any graph effect',async()=>{
 const f=await fixture();const worker=loadWorkerForRouteTests();
 const env={VIDEO_STATE:{getByName:()=>f.relay}};
 const stream=new ReadableStream({start(controller){
  controller.enqueue(new TextEncoder().encode('x'.repeat(128*1024+1)));controller.close();
 }});
 const request=new Request('https://example.test/api/v3/app/metadata-mirror',{
  method:'POST',headers:{authorization:'Bearer '+f.key},body:stream,duplex:'half'});
 const result=await worker.fetch(request,env,{});
 assert.equal(result.status,400);assert.match((await result.json()).error,/128 KB/);
 assert.equal(await f.storage.get('app-metadata-mirror:'+f.deviceId+':project-mirror-1'),undefined);
});


test('one-time native hybrid challenge creates a private stable binding and rejects replay',async()=>{
 const f=await fixture();
 const webDeviceId='studio-web-device-001';
 await f.relay.register(webDeviceId,{name:'Studio Web'});

 assert.equal(await f.relay.appResolveHybrid(webDeviceId),null);

 const challenge=await f.relay.appCreateHybridBinding(f.key,webDeviceId);
 assert.ok(challenge.token.length>=30);
 assert.equal(challenge.webDeviceId,webDeviceId);

 await assert.rejects(
  f.relay.appRedeemHybridBinding(challenge.token,'another-web-device'),
  /device/i
 );

 const bound=await f.relay.appRedeemHybridBinding(challenge.token,webDeviceId);
 assert.ok(bound.hybridKey.length>=32);
 assert.match(bound.privateMcpPath,/^\/mcp-v06\/[A-Za-z0-9_-]{32,}$/);

 const resolved=await f.relay.appResolveHybrid(bound.hybridKey);
 assert.equal(resolved.native.deviceId,f.deviceId);
 assert.equal(resolved.binding.webDeviceId,webDeviceId);

 await assert.rejects(
  f.relay.appRedeemHybridBinding(challenge.token,webDeviceId),
  /already used|invalid/i
 );
});

test('expired native hybrid challenge cannot be redeemed',async()=>{
 const f=await fixture();
 const webDeviceId='studio-web-device-expired';
 await f.relay.register(webDeviceId,{name:'Studio Web'});
 const challenge=await f.relay.appCreateHybridBinding(f.key,webDeviceId);
 const record=await f.storage.get('hybrid-challenge:'+challenge.token);
 record.expiresAt=Date.now()-1;
 await f.storage.put('hybrid-challenge:'+challenge.token,record);
 await assert.rejects(
  f.relay.appRedeemHybridBinding(challenge.token,webDeviceId),
  /expired/i
 );
});


async function hybridFixture(){
 const f=await fixture();
 const webDeviceId='studio-web-hybrid-001';
 await f.relay.register(webDeviceId,{name:'Studio Web'});
 const challenge=await f.relay.appCreateHybridBinding(f.key,webDeviceId);
 const bound=await f.relay.appRedeemHybridBinding(challenge.token,webDeviceId);
 return {...f,webDeviceId,hybridKey:bound.hybridKey};
}

test('hybrid command waits for native when Android is stale and becomes claimable on reconnect',async()=>{
 const f=await hybridFixture();
 const native=await f.storage.get('app-device:'+f.deviceId);
 native.lastSeenAt=new Date(Date.now()-120000).toISOString();
 await f.storage.put('app-device:'+f.deviceId,native);

 const queued=await f.relay.appEnqueueHybrid(f.hybridKey,'ping',{source:'hybrid'});
 assert.equal(queued.status,'waiting_native');

 const status=await f.relay.appStatusHybrid(f.hybridKey);
 assert.equal(status.hybrid,true);
 assert.equal(status.web.connected,true);
 assert.equal(status.native.connected,false);
 assert.equal(status.waitingNative,true);
 assert.equal(status.pendingNativeCommands,1);

 await f.relay.appRegister(f.deviceId,f.key,{protocolVersion:3,appGeneration:1,permissionMode:'everything'});
 const claimed=await f.relay.appCommandsV3(f.deviceId,f.key,0,0);
 assert.equal(claimed.length,1);
 assert.equal(claimed[0].id,queued.id);
 assert.equal(claimed[0].status,'claimed');
});

test('public Web device ID cannot route native commands and revoked hybrid binding stops routing',async()=>{
 const f=await hybridFixture();
 await assert.rejects(
  f.relay.appEnqueueHybrid(f.webDeviceId,'ping',{}),
  /hybrid.*rejected|binding/i
 );

 const revoked=await f.relay.appRevokeHybrid(f.hybridKey);
 assert.equal(revoked.revoked,true);
 assert.equal(await f.relay.appResolveHybrid(f.hybridKey),null);
 await assert.rejects(
  f.relay.appEnqueueHybrid(f.hybridKey,'ping',{}),
  /hybrid.*rejected|binding/i
 );
 assert.ok(await f.storage.get('app-device:'+f.deviceId));
});


test('stable native MCP control plane remains available while Android sleeps',async()=>{
 const f=await fixture();
 const native=await f.storage.get('app-device:'+f.deviceId);
 native.lastSeenAt=new Date(Date.now()-120000).toISOString();
 await f.storage.put('app-device:'+f.deviceId,native);

 const status=await f.relay.appStatusV3(f.key);
 assert.equal(status.connected,true);
 assert.equal(status.controlPlaneConnected,true);
 assert.equal(status.nativeConnected,false);
 assert.equal(status.executionAvailable,false);
 assert.equal(status.offlineQueueAvailable,true);
 assert.equal(status.nativeState,'sleeping_or_offline');
});

test('direct native commands become durable waiting_native work while Android sleeps',async()=>{
 const f=await fixture();
 const native=await f.storage.get('app-device:'+f.deviceId);
 native.lastSeenAt=new Date(Date.now()-120000).toISOString();
 await f.storage.put('app-device:'+f.deviceId,native);

 const queued=await f.relay.appEnqueueV3(f.key,'apply_tool',{clipIndex:0,tool:'transition'});
 assert.equal(queued.status,'waiting_native');
 assert.equal(queued.waitingReason,'native_offline');

 const status=await f.relay.appStatusV3(f.key);
 assert.equal(status.pendingCommands,1);
 assert.equal(status.waitingNativeCommands,1);

 const claimed=await f.relay.appCommandsV3(f.deviceId,f.key,0,0);
 assert.equal(claimed.length,1);
 assert.equal(claimed[0].id,queued.id);
 assert.equal(claimed[0].status,'claimed');
});


test('same private native MCP routes prompt generation to bound Studio Web while Android sleeps',async()=>{
 const f=await fixture();
 const webDeviceId='studio-web-fallback-001';
 await f.relay.register(webDeviceId,{name:'Studio Web fallback'});
 const project=await f.relay.createProject(webDeviceId,'Fallback Project','');
 const binding=await f.relay.appBindStudioWebFallback(f.key,webDeviceId);
 assert.equal(binding.bound,true);
 assert.equal(binding.fallbackWebProjectId,project.id);

 const native=await f.storage.get('app-device:'+f.deviceId);
 native.lastSeenAt=new Date(Date.now()-120000).toISOString();
 await f.storage.put('app-device:'+f.deviceId,native);

 const queued=await f.relay.appEnqueueV3(f.key,'prompt_video',{
  prompt:'cinematic test world',
  durationSeconds:8,
  aspect:'9:16',
  quality:'1080p'
 });
 assert.equal(queued.hybridRoute,'studio_web');
 assert.equal(queued.action,'generate_video');
 assert.equal(queued.runtime,'studio-web');
 assert.equal(queued.webProjectId,project.id);

 const read=await f.relay.appCommandV3(f.key,queued.id);
 assert.equal(read.hybridRoute,'studio_web');
 assert.equal(read.id,queued.id);

 const status=await f.relay.appStatusV3(f.key);
 assert.equal(status.nativeConnected,false);
 assert.equal(status.executionAvailable,true);
 assert.equal(status.studioWebFallback.bound,true);
 assert.equal(status.studioWebFallback.connected,true);
 assert.equal(status.studioWebFallback.projectId,project.id);
});

test('offline native MCP still queues native-only work when Web fallback cannot execute it',async()=>{
 const f=await fixture();
 const webDeviceId='studio-web-fallback-native-only';
 await f.relay.register(webDeviceId,{name:'Studio Web fallback'});
 await f.relay.createProject(webDeviceId,'Fallback Project','');
 await f.relay.appBindStudioWebFallback(f.key,webDeviceId);
 const native=await f.storage.get('app-device:'+f.deviceId);
 native.lastSeenAt=new Date(Date.now()-120000).toISOString();
 await f.storage.put('app-device:'+f.deviceId,native);

 const queued=await f.relay.appEnqueueV3(f.key,'install_model_pack',{assetId:'asset-12345678'});
 assert.equal(queued.status,'waiting_native');
 assert.equal(queued.waitingReason,'native_offline');
 assert.equal(queued.hybridRoute,undefined);
});


test('canonical identity convergence aliases legacy owner keys to the current device',async()=>{
 const store=new Map();
 const storage={get:async k=>structuredClone(store.get(k)),put:async(k,v)=>store.set(k,structuredClone(v)),delete:async k=>store.delete(k)};
 storage.list=async({prefix,limit})=>new Map([...store].filter(([key])=>key.startsWith(prefix)).slice(0,limit));
 storage.transaction=async callback=>callback(storage);
 const relay=new context.VideoStudioState({storage},{});
 const primaryKey='p'.repeat(43), primaryDevice='current-device-343';
 const legacyKey='l'.repeat(43), legacyDevice='legacy-device-341';

 await relay.appRegister(primaryDevice,primaryKey,{protocolVersion:3,appGeneration:3,appVersion:'3.4.3',permissionMode:'everything'});
 await relay.appRegister(legacyDevice,legacyKey,{protocolVersion:3,appGeneration:1,appVersion:'3.4.1',permissionMode:'everything'});
 await storage.put('app-v3-cl:'+legacyDevice,[
  {id:'legacy-pending',seq:1,protocolVersion:3,deviceId:legacyDevice,action:'apply_tool',parameters:{},status:'queued',createdAt:new Date().toISOString(),completedAt:null,result:null},
  {id:'legacy-done',seq:2,protocolVersion:3,deviceId:legacyDevice,action:'get_state',parameters:{},status:'completed',createdAt:new Date().toISOString(),completedAt:new Date().toISOString(),result:{ok:true,marker:'legacy'}}
 ]);

 const result=await relay.appConvergeOwnerAliases(primaryKey,[legacyKey],primaryDevice);
 assert.equal(result.canonicalDeviceId,primaryDevice);
 assert.equal(result.aliasCount,2);

 const primary=await relay.appResolve(primaryKey);
 const legacy=await relay.appResolve(legacyKey);
 assert.equal(primary.deviceId,primaryDevice);
 assert.equal(legacy.deviceId,primaryDevice);
 assert.equal(legacy.appVersion,'3.4.3');

 const rows=await commandQueueStore.readCommandQueue(storage,'app-v3-cl:'+primaryDevice);
 assert.ok(rows.some(x=>x.id==='legacy-pending'));
 assert.ok(rows.some(x=>x.id==='legacy-done'&&x.result.marker==='legacy'));

 const oldRecord=await storage.get('app-device:'+legacyDevice);
 assert.equal(oldRecord.supersededByDeviceId,primaryDevice);
 assert.equal(oldRecord.canonicalIdentity,false);
});

test('superseded native device cannot steal a converged legacy endpoint back',async()=>{
 const store=new Map();
 const storage={get:async k=>structuredClone(store.get(k)),put:async(k,v)=>store.set(k,structuredClone(v)),delete:async k=>store.delete(k)};
 storage.list=async({prefix,limit})=>new Map([...store].filter(([key])=>key.startsWith(prefix)).slice(0,limit));
 storage.transaction=async callback=>callback(storage);
 const relay=new context.VideoStudioState({storage},{});
 const primaryKey='q'.repeat(43), primaryDevice='current-device-lock';
 const legacyKey='r'.repeat(43), legacyDevice='legacy-device-lock';

 await relay.appRegister(primaryDevice,primaryKey,{protocolVersion:3,appGeneration:4,appVersion:'3.4.3'});
 await relay.appRegister(legacyDevice,legacyKey,{protocolVersion:3,appGeneration:1,appVersion:'3.4.1'});
 await relay.appConvergeOwnerAliases(primaryKey,[legacyKey],primaryDevice);

 await assert.rejects(
  relay.appRegister(legacyDevice,legacyKey,{protocolVersion:3,appGeneration:99,appVersion:'9.9.9'}),
  /superseded|canonical/i
 );
 const legacy=await relay.appResolve(legacyKey);
 assert.equal(legacy.deviceId,primaryDevice);
});

test('convergence can repair a missing primary owner index from the canonical device record',async()=>{
 const f=await fixture();
 const ownerHash=await (async()=>{
  const data=new TextEncoder().encode(f.key);
  const digest=await webcrypto.subtle.digest('SHA-256',data);
  return Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
 })();
 await f.storage.delete('app-owner:'+ownerHash);

 const repaired=await f.relay.appConvergeOwnerAliases(f.key,[],f.deviceId);
 assert.equal(repaired.canonicalDeviceId,f.deviceId);
 assert.equal((await f.relay.appResolve(f.key)).deviceId,f.deviceId);
});


test('ChatGPT attachment URL is relayed through a private handoff instead of exposed to Android',async()=>{
 const f=await fixture();
 const sourceUrl='https://files.example.com/private/video.mp4?sig=short-lived';
 const queued=await f.relay.appQueueAttachmentHandoff(f.key,{
  download_url:sourceUrl,
  file_id:'file-chatgpt-video',
  mime_type:'video/mp4',
  file_name:'chatgpt-source.mp4'
 },'project-12345678');

 assert.equal(queued.action,'import_chat_file');
 assert.ok(queued.parameters.handoffId);
 assert.equal(queued.parameters.sourceUrl,undefined);
 assert.equal(queued.parameters.name,'chatgpt-source.mp4');
 assert.equal(queued.parameters.mime,'video/mp4');
 assert.equal(queued.parameters.projectId,'project-12345678');

 const handoff=await f.relay.appHandoff(f.deviceId,f.key,queued.parameters.handoffId);
 assert.ok(handoff);
 assert.equal(handoff.sourceUrl,sourceUrl);
 assert.equal(handoff.name,'chatgpt-source.mp4');
 assert.equal(handoff.mime,'video/mp4');
});
