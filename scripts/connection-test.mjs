import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
// Run the real relay class with an in-memory Durable Object store; SDK/network are not needed.
const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const end=source.indexOf('\n}\n',source.indexOf('export class VideoStudioState'))+3;
const context={crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
async function fixture(commands=[]) {
 const store=new Map(); const storage={get:async k=>structuredClone(store.get(k)),put:async(k,v)=>store.set(k,structuredClone(v)),delete:async k=>store.delete(k)};
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
 const rows=await f.storage.get('app-v3-cl:'+f.deviceId);
 assert.equal(rows.length,160);assert.ok(rows.some(c=>c.id==='cmd-1'));assert.ok(rows.some(c=>c.seq===161));
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

test('filling the last free queue slot retains every pending command',async()=>{
 const pending=Array.from({length:159},(_,i)=>command(i+1,'queued'));
 const f=await fixture([...pending,command(160,'completed')]);
 await f.storage.put('app-v3-seq:'+f.deviceId,160);
 await f.relay.appEnqueueV3(f.key,'ping',{});
 const rows=await f.storage.get('app-v3-cl:'+f.deviceId);
 assert.equal(rows.length,160);assert.ok(rows.some(c=>c.id==='cmd-1'));
 assert.equal(rows.filter(c=>c.status==='queued').length,160);
});


function loadWorkerForRouteTests() {
 const workerContext={
  crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,setTimeout,clearTimeout,Date,
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

 const rows=await storage.get('app-v3-cl:'+primaryDevice);
 assert.ok(rows.some(x=>x.id==='legacy-pending'));
 assert.ok(rows.some(x=>x.id==='legacy-done'&&x.result.marker==='legacy'));

 const oldRecord=await storage.get('app-device:'+legacyDevice);
 assert.equal(oldRecord.supersededByDeviceId,primaryDevice);
 assert.equal(oldRecord.canonicalIdentity,false);
});

test('superseded native device cannot steal a converged legacy endpoint back',async()=>{
 const store=new Map();
 const storage={get:async k=>structuredClone(store.get(k)),put:async(k,v)=>store.set(k,structuredClone(v)),delete:async k=>store.delete(k)};
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
 const sourceUrl='https://files.example.test/private/video.mp4?sig=short-lived';
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

test('native scene operations remain native when the phone is offline',async()=>{
 const f=await fixture([]);
 const resolved=await f.relay.appResolve(f.key);
 assert.ok(resolved);
 resolved.lastSeenAt=new Date(Date.now()-120000).toISOString();
 await f.storage.put('app-device:'+f.deviceId,resolved);
 f.relay.appResolveStudioWebFallback=async()=>({fresh:true,project:{id:'web-project'},binding:{webDeviceId:'web-device'}});
 f.relay.enqueueRuntime=async()=>{throw new Error('Native scene was incorrectly sent to the browser');};
 const queued=await f.relay.appEnqueueV3(f.key,'native_scene',{operation:'render',sceneName:'living_world',revision:1});
 assert.equal(queued.action,'native_scene');assert.equal(queued.parameters.revision,1);
 assert.notEqual(queued.hybridRoute,'studio_web');assert.equal(queued.status,'waiting_native');
});
