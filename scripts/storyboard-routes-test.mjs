import {nativeCommandSummary,nativeResultPage} from "../src/native-result-pages.js";
import {queueBindings,memoryStorage} from "./native-queue-fixture.mjs";
import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {z} from 'zod';
import * as contract from '../src/storyboard-contract.js';
const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
async function fixture(version='3.7.2',mode='everything') {
  const rows=new Map();
  const storage=memoryStorage(rows,{maxValueBytes:128*1024});
  class McpServer { constructor(){this.tools=new Map();} registerTool(name,definition,handler){this.tools.set(name,{definition,handler});} }
  const context={...contract,...queueBindings,nativeCommandSummary,nativeResultPage,z,McpServer,crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,
    DurableObject:class {constructor(ctx){this.ctx=ctx;}},createMcpHandler:()=>()=>new Response(),
    APP_HTML:'',STUDIO_RUNTIME_JS:'',STUDIO_CINEMATIC_JS:'',STUDIO_NEURAL_JS:'',STUDIO_TEMPORAL_JS:'',STUDIO_CHARACTER_JS:'',STUDIO_ACTION_TIMELINE_JS:'',STUDIO_POSE_SEQUENCE_JS:'',LOCAL_VIDEO_DOWNLOAD_HTML:''};
  vm.createContext(context);
  vm.runInContext(source.replace(/^import .*$/gm,'').replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState')
    .replace('export default {','globalThis.worker = {')+'\nglobalThis.nativeTools=serverForApp;',context);
  const relay=new context.VideoStudioState({storage},{}),owner='x'.repeat(43),deviceId='storyboard-test-device';
  await relay.appRegister(deviceId,owner,{appVersion:version,protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:mode});
  const server=context.nativeTools({VIDEO_STATE:{getByName:()=>relay}},owner,3);
  const call=async(name,args)=>{const tool=server.tools.get(name);assert.ok(tool,name);const parsed=z.object(tool.definition.inputSchema).parse(args);return JSON.parse((await tool.handler(parsed)).content[0].text);};
  return {rows,storage,relay,owner,deviceId,call,server,writes:()=>storage.writes()};
}
const request={sourceProjectId:'source-project-123',motionIntent:'cinematic_action'};
test('actual typed handler rejects old native app before queue or sequence writes',async()=>{
  const f=await fixture('3.7.1'),before=f.writes();
  const result=await f.call('app_animate_storyboard_series',request);
  assert.equal(result.queued,false);assert.match(result.error,/NATIVE_APP_UPGRADE_REQUIRED/);
  assert.equal(f.writes(),before);assert.equal(f.rows.has('app-v3-cl:'+f.deviceId),false);
});
test('typed tool queues the existing native action through application v3 without a browser fallback',async()=>{
  const f=await fixture(),result=await f.call('app_storyboard_series_plan',request);
  assert.equal(result.queued,true);assert.equal(result.protocolVersion,3);assert.equal(result.nativeApp,true);
  const rows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
  assert.equal(rows[0].action,'storyboard_series_plan');assert.equal(rows[0].parameters.sourceProjectId,request.sourceProjectId);
});
test('generic action strips caller proof and cannot select an existing output',async()=>{
  const f=await fixture();
  await f.relay.appEnqueueV3(f.owner,'animate_storyboard_series',{...request,_storyboardAdmission:{fake:true},_recoveryPlanId:'forged',_mcpCommandId:'forged'});
  const row=(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId))[0];assert.deepEqual(Object.keys(row.parameters).sort(),Object.keys(request).sort());
  const before=f.writes();await assert.rejects(f.relay.appEnqueueV3(f.owner,'animate_storyboard_series',{...request,outputProjectId:'existing-output'}),/fresh separate/);
  assert.equal(f.writes(),before);
});
test('strict contacts and sixteen joint pairs reject malformed data without queue effects',async()=>{
  const f=await fixture(),before=f.writes();
  await assert.rejects(f.call('app_save_source_rig_profile',{projectId:'source-project',sourceAssetId:'source-asset',sourceProjectUpdatedAt:1,black:true,joints:{root:[.5,.5]},outline:[[0,0],[1,0],[0,1]]}));
  await assert.rejects(f.call('app_animate_storyboard_series',{...request,contactPlans:[{shotIndex:0,fromAssetId:'source-one',toAssetId:'source-two',contactPlan:{black:[{foot:'guessed',start:0,end:1,anchor:[0,0]}],white:[]}}]}));
  assert.equal(f.writes(),before);
});
test('request bytes/depth are bounded before enqueue, one-file remains restrictive',async()=>{
  const f=await fixture(),before=f.writes();await assert.rejects(f.relay.appEnqueueV3(f.owner,'animate_storyboard_series',{...request,note:'x'.repeat(65536)}),/64 KiB/);assert.equal(f.writes(),before);
  let deep={};for(let i=0;i<65;i++)deep={nested:deep};assert.throws(()=>contract.nativeStoryboardParameters('animate_storyboard_series',deep),/structural/);
  const locked=await fixture('3.7.2','one_file'),prior=locked.writes();assert.equal((await locked.call('app_animate_storyboard_series',request)).queued,false);assert.equal(locked.writes(),prior);
});
test('native command and large verified render receipt survive a hard128KiB store and reload',async()=>{
  const f=await fixture(),old=[{id:'accepted-command',seq:9,status:'queued',parameters:{retained:'x'.repeat(118*1024)}}];
  await f.storage.put('app-v3-cl:'+f.deviceId,old);
  const command=await f.relay.appEnqueueV3(f.owner,'storyboard_series_plan',request);
  const output={ok:true,projectId:'owned-render-project',outputUri:'content://media/external/video/media/88',outputName:'native.mp4',
    cinematicAcceptance:false,evidence:{sourceManifestSha256:'a'.repeat(64),renderReceipt:'y'.repeat(192*1024)}};
  const ack=await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,output,'completed');
  assert.deepEqual(ack.result,output);assert.equal(ack.status,'completed');
  assert.equal(f.rows.has('app-v3-cl:'+f.deviceId),false);
  const read=await f.relay.appCommandV3(f.owner,command.id);assert.deepEqual(read.result,output);
  const retry=await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,output,'completed');assert.equal(retry.resultReplayed,true);
  await assert.rejects(f.relay.appCompleteV3(f.deviceId,f.owner,command.id,{...output,outputName:'other.mp4'},'completed'),/Conflicting/);
  assert.deepEqual((await f.relay.appCommandV3(f.owner,command.id)).result,output);
  assert.ok((await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId)).some(row=>row.id==='accepted-command'));
});

test('large programme result is fully retrievable through bounded authenticated MCP pages',async()=>{
  const f=await fixture(),command=await f.relay.appEnqueueV3(f.owner,'get_state',{});
  const definition={projectId:'source-project-123',sha256:'b'.repeat(64),choreography:[{beat:'CLASH',note:'π🗡'.repeat(30000)}],sourceHashes:['c'.repeat(64)],cinematicAcceptance:false};
  await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,definition,'completed');
  const first=await f.call('app_get_command_result',{commandId:command.id});assert.equal(first.resultPaged,true);assert.ok(first.pageCount>1);
  let text='';for(let pageIndex=0;pageIndex<first.pageCount;pageIndex++){
    const page=await f.call('app_get_command_result_page',{commandId:command.id,pageIndex});assert.equal(page.viewSha256,first.viewSha256);
    assert.ok(new TextEncoder().encode(page.fragment).byteLength<=32*1024);text+=page.fragment;
  }
  assert.deepEqual(JSON.parse(text),definition);
  const sha=Buffer.from(await webcrypto.subtle.digest('SHA-256',new TextEncoder().encode(text))).toString('hex');assert.equal(sha,first.viewSha256);
});
test('native polling applies aggregate response bound before leases and later drains remaining commands',async()=>{
  const f=await fixture(),ids=[];for(let i=0;i<5;i++)ids.push((await f.relay.appEnqueueV3(f.owner,'get_state',{fixture:'x'.repeat(1100*1024)})).id);
  const first=await f.relay.appCommandsV3(f.deviceId,f.owner,0,0);assert.equal(first.length,2);
  assert.ok(new TextEncoder().encode(JSON.stringify(first)).byteLength<=3*1024*1024);
  const stillQueued=(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId)).filter(row=>row.status==='queued');assert.equal(stillQueued.length,3);
  for(const row of first)await f.relay.appCompleteV3(f.deviceId,f.owner,row.id,{ok:true},'completed');
  const second=await f.relay.appCommandsV3(f.deviceId,f.owner,first.at(-1).seq,0);assert.equal(second.length,2);
  const all=[...first,...second].map(row=>row.id);assert.equal(new Set(all).size,4);assert.ok(ids.every(id=>all.includes(id)||stillQueued.some(row=>row.id===id)));
});
test('edit-only saved-program inspection preserves its explicit flag on the owned typed route',async()=>{
  const f=await fixture();
  const result=await f.call('app_storyboard_program_get',{projectId:'source-project-123',programId:'program-123456',editOnly:true});
  assert.equal(result.queued,true);
  const rows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
  assert.equal(rows[0].action,'get_storyboard_program');assert.equal(rows[0].parameters.editOnly,true);
  await f.call('app_storyboard_program_get',{projectId:'source-project-123',programId:'program-123456'});
  const defaults=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
  assert.equal(defaults[1].parameters.editOnly,undefined);
});
test('nested credentials are removed from the complete paged view while the original receipt proof is preserved',async()=>{
  const f=await fixture(),command=await f.relay.appEnqueueV3(f.owner,'get_state',{});
  const result={ok:true,code:'c'.repeat(2*1024*1024-2048),outputName:'owned.mp4',nested:{accessToken:'SECRET_ACCESS',owner_key:'SECRET_OWNER',
    source_url:'https://private.example/SECRET_SOURCE',children:[{Authorization:'SECRET_AUTH',downloadUrl:'https://private.example/SECRET_DOWNLOAD',visible:'kept'}]}};
  await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,result,'completed');
  const original=await f.relay.appCommandV3(f.owner,command.id),status=await f.relay.appStatusV3(f.owner);
  assert.ok(new TextEncoder().encode(JSON.stringify(status.lastCommand)).byteLength<8*1024);
  assert.equal(status.lastCommand.code,undefined);assert.equal(status.lastCommand.summaryPartial,true);
  assert.ok(status.lastCommand.omittedSummaryFields.includes('code'));
  const first=await f.call('app_get_command_result',{commandId:command.id});
  assert.equal(first.credentialRedactedView,true);assert.equal(first.canonicalResultSha256,original.resultSha256);
  let text='';for(let pageIndex=0;pageIndex<first.pageCount;pageIndex++){
    const page=await f.call('app_get_command_result_page',{commandId:command.id,pageIndex});
    assert.equal(page.viewSha256,first.viewSha256);assert.equal(page.canonicalResultSha256,original.resultSha256);
    assert.ok(new TextEncoder().encode(JSON.stringify(page)).byteLength<48*1024);text+=page.fragment;
  }
  assert.doesNotMatch(text,/SECRET_|private\.example/);
  const view=JSON.parse(text);assert.equal(view.code,result.code);assert.deepEqual(view.nested,{children:[{visible:'kept'}]});
  assert.equal(Buffer.from(await webcrypto.subtle.digest('SHA-256',new TextEncoder().encode(text))).toString('hex'),first.viewSha256);
  assert.deepEqual((await f.relay.appCommandV3(f.owner,command.id)).result,result);
});
test('small native image results redact nested credentials in text without losing the image delivery',async()=>{
  const f=await fixture(),command=await f.relay.appEnqueueV3(f.owner,'get_state',{});
  await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,{contactSheet:{base64:'aGVsbG8=',mimeType:'image/jpeg',accessToken:'SECRET_IMAGE'},nested:{ownerKey:'SECRET_OWNER',visible:true}},'completed');
  const tool=f.server.tools.get('app_get_command_result'),response=await tool.handler({commandId:command.id});
  assert.equal(response.content[1].type,'image');assert.equal(response.content[1].data,'aGVsbG8=');
  assert.doesNotMatch(response.content[0].text,/SECRET_|aGVsbG8=/);
  assert.equal(JSON.parse(response.content[0].text).result.nested.visible,true);
});
test('alias convergence between credential resolution and enqueue cannot strand a newly accepted command',async()=>{
  const f=await fixture(),legacyOwner='l'.repeat(43),legacyId='legacy-storyboard-device';
  await f.relay.appRegister(legacyId,legacyOwner,{appVersion:'3.7.2',protocolVersion:3,protocolMin:3,protocolMax:3});
  const accepted=await f.relay.appEnqueueV3(legacyOwner,'storyboard_series_plan',request),enqueue=f.relay.appQueueNativeV3.bind(f.relay);
  f.relay.appQueueNativeV3=async(...args)=>{
    f.relay.appQueueNativeV3=enqueue;
    await f.relay.appConvergeOwnerAliases(f.owner,[legacyOwner],f.deviceId);
    return enqueue(...args);
  };
  await assert.rejects(f.relay.appEnqueueV3(legacyOwner,'storyboard_series_plan',request),/identity changed before command admission/);
  const oldRows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+legacyId),canonical=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
  assert.deepEqual(oldRows.map(row=>row.id),[accepted.id]);assert.deepEqual(canonical.map(row=>row.id),[accepted.id]);
  assert.equal(await f.storage.get('app-v3-seq:'+legacyId),accepted.seq);
  const retry=await f.relay.appEnqueueV3(legacyOwner,'storyboard_series_plan',request);
  assert.equal(retry.deviceId,f.deviceId);assert.equal(retry.seq,canonical[0].seq+1);
  const claimed=await f.relay.appCommandsV3(f.deviceId,f.owner,0,0);assert.ok(claimed.some(row=>row.id===retry.id));
});
test('live phone controls are revalidated in the enqueue transaction before queue or sequence admission',async()=>{
  for(const [change,message] of [[{controlPaused:true},/paused/],[{permissionMode:'one_file'},/permission mode/],
      [{protocolVersion:2,protocolMin:2,protocolMax:2},/v3/],[{appVersion:'3.7.1'},/NATIVE_APP_UPGRADE_REQUIRED/]]){
    const f=await fixture(),enqueue=f.relay.appQueueNativeV3.bind(f.relay);
    f.relay.appQueueNativeV3=async(...args)=>{
      f.relay.appQueueNativeV3=enqueue;
      await f.relay.appRegister(f.deviceId,f.owner,{appVersion:'3.7.2',protocolVersion:3,protocolMin:3,protocolMax:3,...change});
      return enqueue(...args);
    };
    await assert.rejects(f.relay.appEnqueueV3(f.owner,'animate_storyboard_series',request),message);
    assert.deepEqual(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId),[]);
    assert.equal(await f.storage.get('app-v3-seq:'+f.deviceId),undefined);
  }
});
test('hybrid revocation between resolution and enqueue rejects before native command acceptance',async()=>{
  const f=await fixture(),webId='hybrid-storyboard-web';await f.relay.register(webId);
  const challenge=await f.relay.appCreateHybridBinding(f.owner,webId),binding=await f.relay.appRedeemHybridBinding(challenge.token,webId);
  const enqueue=f.relay.appQueueNativeV3.bind(f.relay);
  f.relay.appQueueNativeV3=async(...args)=>{
    f.relay.appQueueNativeV3=enqueue;await f.relay.appRevokeHybrid(binding.hybridKey);return enqueue(...args);
  };
  await assert.rejects(f.relay.appEnqueueHybrid(binding.hybridKey,'storyboard_series_plan',request),/hybrid binding changed before command admission/);
  assert.deepEqual(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId),[]);
  assert.equal(await f.storage.get('app-v3-seq:'+f.deviceId),undefined);assert.equal(await f.relay.appResolveHybrid(binding.hybridKey),null);
});
