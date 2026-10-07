import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
// Run the real relay class with an in-memory Durable Object store; SDK/network are not needed.
const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const end=source.indexOf('\n}\n',source.indexOf('export class VideoStudioState'))+3;
const context={crypto:webcrypto,TextEncoder,Response,Request,Headers,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
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
