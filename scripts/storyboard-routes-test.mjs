import {nativeCommandSummary,nativeResultPage} from "../src/native-result-pages.js";
import {queueBindings,memoryStorage} from "./native-queue-fixture.mjs";
import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {z} from 'zod';
import * as contract from '../src/storyboard-contract.js';
import {studioToolSchemas,CHARACTER_PERFORMANCE_CAPABILITIES} from '../src/studio-workbench-contract.js';
import {STUDIO_ALIAS_ACTIONS,nativeStudioCanonicalAction} from '../src/studio-workbench-contract.js';
const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
async function fixture(version='3.7.2',mode='everything') {
  const rows=new Map();
  const storage=memoryStorage(rows,{maxValueBytes:128*1024});
  class McpServer { constructor(){this.tools=new Map();} registerTool(name,definition,handler){this.tools.set(name,{definition,handler});} }
  const context={...contract,...queueBindings,studioToolSchemas,CHARACTER_PERFORMANCE_CAPABILITIES,nativeStudioCanonicalAction,nativeCommandSummary,nativeResultPage,z,McpServer,crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,
    DurableObject:class {constructor(ctx){this.ctx=ctx;}},createMcpHandler:()=>()=>new Response(),
    APP_HTML:'',STUDIO_RUNTIME_JS:'',STUDIO_CINEMATIC_JS:'',STUDIO_NEURAL_JS:'',STUDIO_TEMPORAL_JS:'',STUDIO_CHARACTER_JS:'',STUDIO_ACTION_TIMELINE_JS:'',STUDIO_POSE_SEQUENCE_JS:'',LOCAL_VIDEO_DOWNLOAD_HTML:''};
  vm.createContext(context);
  vm.runInContext(source.replace(/^import .*$/gm,'').replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState')
    .replace('export default {','globalThis.worker = {')+'\nglobalThis.nativeTools=serverForApp;globalThis.compatibilityTools=serverFor;',context);
  const relay=new context.VideoStudioState({storage},{}),owner='x'.repeat(43),deviceId='storyboard-test-device';
  await relay.appRegister(deviceId,owner,{appVersion:version,protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:mode});
  const server=context.nativeTools({VIDEO_STATE:{getByName:()=>relay}},owner,3);
  const call=async(name,args)=>{const tool=server.tools.get(name);assert.ok(tool,name);const parsed=z.object(tool.definition.inputSchema).parse(args);return JSON.parse((await tool.handler(parsed)).content[0].text);};
  return {rows,storage,relay,owner,deviceId,call,server,compatibilityServer:()=>context.compatibilityTools({VIDEO_STATE:{getByName:()=>relay}}),writes:()=>storage.writes()};
}
const request={sourceProjectId:'source-project-123',motionIntent:'cinematic_action'};
test('prepared-sketch animation tools reject old native before writes and retain exact new native intent',async()=>{
 const parameters={animationId:'arm_scene',sketchId:'prepared',sourceRevision:1,expectedRevision:0,operationId:'save_1',program:{schema:'videostudio.sketch-animation.v1',frameCount:100,fps:24,rigs:[]}};
 const old=await fixture('4.3.1'),before=old.writes(),blocked=await old.call('app_sketch_animation_save',parameters);
 assert.equal(blocked.queued,false);assert.match(blocked.error,/4\.4\.0/);assert.equal(old.writes(),before);
 const current=await fixture('4.4.0');assert.equal((await current.call('app_sketch_animation_save',parameters)).queued,true);
 const rows=await queueBindings.readCommandQueue(current.storage,'app-v3-cl:'+current.deviceId);assert.deepEqual(rows.at(-1).parameters,parameters);
 const writes=current.writes();await assert.rejects(current.relay.appEnqueueV3(current.owner,'studio_sketch_animation_bake',{animationId:'arm_scene',expectedRevision:0,operationId:'bake_1',outputSketchId:'out'}));assert.equal(current.writes(),writes);
 assert.equal(current.server.tools.get('app_sketch_animation_get').definition.annotations.readOnlyHint,true);
 assert.equal(current.server.tools.get('app_sketch_animation_list').definition.annotations.readOnlyHint,true);
});
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

const sourceInspection={projectId:'source-project-123',assetId:'source-image-123',maxDimension:512,
  expectedSourceSha256:'a'.repeat(64),crop:{left:.1,top:.2,right:.7,bottom:.8}};
const imageData='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aUWQAAAAASUVORK5CYII=';
test('native original inspection rejects 3.7.2 before any command or sequence write',async()=>{
  const f=await fixture('3.7.2'),before=f.writes(),result=await f.call('app_inspect_source_artwork',sourceInspection);
  assert.equal(result.queued,false);assert.match(result.error,/3\.7\.3/);assert.equal(f.writes(),before);
});
test('native original inspection queues exact crop and original byte binding on 3.7.3',async()=>{
  const f=await fixture('3.7.3'),result=await f.call('app_inspect_source_artwork',sourceInspection);
  assert.equal(result.queued,true);const row=(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId))[0];
  assert.equal(row.action,'inspect_source_artwork');assert.deepEqual(row.parameters,sourceInspection);
});
test('original inspection cannot enqueue arbitrary URI or malformed crop through generic routes',async()=>{
  const f=await fixture('3.7.3'),before=f.writes();
  await assert.rejects(f.relay.appEnqueueV3(f.owner,'inspect_source_artwork',{...sourceInspection,uri:'https://unowned.example/original.jpg'}),/field|URI/);
  await assert.rejects(f.relay.appEnqueueV3(f.owner,'inspect_source_artwork',{...sourceInspection,crop:{left:.8,top:0,right:.2,bottom:1}}),/crop/);
  assert.equal(f.writes(),before);
});
test('native original image is delivered as actual MCP image with mapping and no JSON base64 or credentials',async()=>{
  const f=await fixture('3.7.3'),command=await f.relay.appEnqueueV3(f.owner,'inspect_source_artwork',sourceInspection);
  const result={ok:true,sourceSha256:'a'.repeat(64),coordinateMapping:{coordinateSpace:'normalized-preview-uv',uOffset:.1,uScale:.6},
    sourceArtworkImage:{base64:imageData,mimeType:'image/png',width:1,height:1,role:'original_source_inspection'},accessToken:'PRIVATE_TOKEN'};
  await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,result,'completed');
  const response=await f.server.tools.get('app_get_command_result').handler({commandId:command.id});
  assert.equal(response.content[1].type,'image');assert.equal(response.content[1].data,imageData);
  const text=response.content[0].text;assert.doesNotMatch(text,/PRIVATE_TOKEN|iVBOR/);
  assert.equal(JSON.parse(text).result.sourceSha256,result.sourceSha256);
  assert.deepEqual(JSON.parse(text).result.coordinateMapping,result.coordinateMapping);
});
test('native source rig diagnostic image retains diagnostic scope and exact rig proof',async()=>{
  const f=await fixture('3.7.3'),command=await f.relay.appEnqueueV3(f.owner,'preview_source_rig',{projectId:'source-project-123',rigId:'actor-rig-123'});
  await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,{ok:true,diagnosticOnly:true,cinematicAcceptance:false,rigSha256:'b'.repeat(64),
    rigPreviewImage:{base64:imageData,mimeType:'image/png',width:1,height:1,role:'source_rig_pose_diagnostic'}},'completed');
  const response=await f.server.tools.get('app_get_command_result').handler({commandId:command.id});
  assert.equal(response.content[1].type,'image');assert.equal(response.content[1].data,imageData);
  const result=JSON.parse(response.content[0].text).result;assert.equal(result.diagnosticOnly,true);assert.equal(result.cinematicAcceptance,false);assert.equal(result.rigSha256,'b'.repeat(64));
});
test('typed batch rig saves preserve per-profile byte proofs and do not accept nine profiles',async()=>{
  const f=await fixture('3.7.3'),profile={sourceAssetId:'source-image-123',black:true,sourceSha256:'a'.repeat(64),
    joints:Array.from({length:16},(_,i)=>[.2+i*.02,.3+i*.02]),outline:[[.1,.1],[.9,.1],[.9,.9]],swordHilt:[.5,.5],swordTip:[.8,.5],swordOutline:[[.5,.48],[.8,.48],[.8,.52]]};
  const args={projectId:'source-project-123',sourceProjectUpdatedAt:1,batchId:'rig-batch-123',profiles:[profile]};
  assert.equal((await f.call('app_save_source_rig_profiles',args)).queued,true);
  const row=(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId))[0];assert.equal(row.action,'save_source_rig_profiles');assert.equal(row.parameters.profiles[0].sourceSha256,profile.sourceSha256);
  const before=f.writes();await assert.rejects(f.call('app_save_source_rig_profiles',{...args,profiles:Array(9).fill(profile)}));assert.equal(f.writes(),before);
});

const cinematicTimeline={schema:'videostudio.cinematic3d.timeline.v1',name:'Full scene',fps:60,motionProvider:'procedural_mannequin',shots:[
 {id:'shot_001',action:'CLASH',durationFrames:120,hitStopFrames:4,camera:{mode:'macro',fovDegrees:85,shakeIntensity:.4},vfx:{sparkBurst:true,slashTrail:true,rubble:true},
 sourceAnchors:[{assetId:'source-anchor-one',sha256:'a'.repeat(64)},{assetId:'source-anchor-two',sha256:'b'.repeat(64)},{assetId:'source-anchor-one',sha256:'a'.repeat(64)}]}]};
test('new 3D typed render rejects 3.7.3 without queue or sequence effects',async()=>{
 const f=await fixture('3.7.3'),before=f.writes(),r=await f.call('app_cinematic_3d_render',{projectId:'source-project-123',programId:'program-123456'});
 assert.equal(r.queued,false);assert.match(r.error,/3\.8\.0/);assert.equal(f.writes(),before);
});
test('new 3D builtin installation queues native owned work on 3.8.0',async()=>{
 const f=await fixture('3.8.0'),r=await f.call('app_cinematic_3d_install_builtin',{projectId:'source-project-123',sourceProjectUpdatedAt:1});
 assert.equal(r.queued,true);assert.equal(r.nativeApp,true);const row=(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId))[0];
 assert.equal(row.action,'cinematic_3d_install_builtin');assert.deepEqual(row.parameters,{projectId:'source-project-123',sourceProjectUpdatedAt:1});
});
test('new 3D timeline save preserves sixty fps exact holds and source occurrences',async()=>{
 const f=await fixture('3.8.0'),args={projectId:'source-project-123',sourceProjectUpdatedAt:1,bundleId:'bundle-123456',timeline:cinematicTimeline};
 assert.equal((await f.call('app_cinematic_3d_save_program',args)).queued,true);
 const row=(await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId))[0];assert.equal(row.action,'cinematic_3d_save_program');assert.deepEqual(row.parameters.timeline,cinematicTimeline);
});
test('generic 3D actions reject old native before any queue effects',async()=>{
 const f=await fixture('3.7.3'),before=f.writes();await assert.rejects(f.relay.appEnqueueV3(f.owner,'cinematic_3d_render',{projectId:'source-project-123',programId:'program-123456'}),/3\.8\.0/);assert.equal(f.writes(),before);
});
test('3D bundle import is registered-asset only and callers cannot select output projects',async()=>{
 const f=await fixture('3.8.0'),before=f.writes();
 await assert.rejects(f.relay.appEnqueueV3(f.owner,'cinematic_3d_import_bundle',{projectId:'source-project-123',sourceProjectUpdatedAt:1,bundleAssetId:'bundle-asset-123',uri:'https://unowned.invalid/bundle.zip'}),/field|URI/);
 await assert.rejects(f.relay.appEnqueueV3(f.owner,'cinematic_3d_render',{projectId:'source-project-123',programId:'program-123456',outputProjectId:'existing-output'}),/fresh|output/);
 assert.equal(f.writes(),before);
});
test('generic 3D timeline validation rejects unsafe intervals unknown fields and metadata overflow',async()=>{
 const f=await fixture('3.8.0'),before=f.writes(),args={projectId:'source-project-123',sourceProjectUpdatedAt:1,bundleId:'bundle-123456',timeline:cinematicTimeline};
 for(const mutate of [t=>t.shots[0].hitStopFrames=7,t=>t.shots[0].camera.fovDegrees=180,t=>t.shots[0].vfx.python='run.py',t=>t.shots[0].durationFrames=3]){
   const timeline=structuredClone(cinematicTimeline);mutate(timeline);await assert.rejects(f.relay.appEnqueueV3(f.owner,'cinematic_3d_save_program',{...args,timeline}));
 }
 assert.equal(f.writes(),before);
});
test('3D program retrieval and authoring export preserve exact program sha proof',async()=>{
 const f=await fixture('3.8.0'),args={projectId:'source-project-123',programId:'program-123456',expectedProgramSha256:'c'.repeat(64)};
 for(const [tool,action] of [['app_cinematic_3d_get_program','cinematic_3d_get_program'],['app_cinematic_3d_export_request','cinematic_3d_export_request']]){
  assert.equal((await f.call(tool,args)).queued,true);const rows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);assert.equal(rows.at(-1).action,action);assert.deepEqual(rows.at(-1).parameters,args);
 }
});
test('3D capabilities distinguish available source contracts from installed neural providers',async()=>{
 const f=await fixture('3.8.0'),result=await f.call('app_capabilities',{});
 assert.equal(result.cinematic3d.minimumNativeAppVersion,'3.8.0');assert.equal(result.cinematic3d.cinematicAcceptance,false);assert.equal(result.cinematic3d.neuralProviderInstalled,false);
 assert.equal((await f.call('app_cinematic_3d_capabilities',{})).queued,true);
});
test('edit-only 3D saved-program reads preserve the explicit non-executable mode',async()=>{
 const f=await fixture('3.8.0'),args={projectId:'source-project-123',programId:'program-123456',expectedProgramSha256:'c'.repeat(64),editOnly:true};
 assert.equal((await f.call('app_cinematic_3d_get_program',args)).queued,true);
 const rows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);assert.deepEqual(rows.at(-1).parameters,args);
});
test('solved cinematic camera keys reach the exact native timeline',async()=>{
 const f=await fixture('3.8.0'),timeline=structuredClone(cinematicTimeline);timeline.shots[0].camera.keyframes=[{phase:0,position:[0,1.65,5.5],target:[0,1.1,0],up:[0,1,0]},{phase:1,position:[.5,1.7,5],target:[0,1.1,0],up:[0,1,0]}];
 const args={projectId:'source-project-123',sourceProjectUpdatedAt:1,bundleId:'bundle-123456',timeline};assert.equal((await f.call('app_cinematic_3d_save_program',args)).queued,true);
 const rows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);assert.deepEqual(rows.at(-1).parameters.timeline.shots[0].camera.keyframes,timeline.shots[0].camera.keyframes);
});
test('studio relay alias generic execution keeps queued alias native-only when the phone is stale',async()=>{
 const f=await fixture('3.9.0');
 const device=await queueBindings.readNativeDevice(f.storage,'app-device:'+f.deviceId);device.lastSeenAt='2020-01-01T00:00:00.000Z';
 await queueBindings.writeNativeDevice(f.storage,'app-device:'+f.deviceId,device);
 let browserFallback=false;f.relay.appTryStudioWebFallback=async()=>{browserFallback=true;return {queued:true,nativeApp:false}};
 const parameters={sketchId:'sketch_1',pointLimit:16},result=await f.call('app_execute',{action:'studio_sketch_get',parameters});
 assert.equal(result.queued,true);assert.equal(result.nativeApp,true);assert.equal(browserFallback,false);
 const rows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);
 assert.equal(rows.at(-1).action,'studio_sketch_get');assert.deepEqual(rows.at(-1).parameters,parameters);
 assert.equal(nativeStudioCanonicalAction('studio_sketch_get'),'sketch_get');
});
test('studio relay alias version and canonical parameter failures happen before queue writes',async()=>{
 const old=await fixture('3.8.1'),before=old.writes();
 for(const alias of STUDIO_ALIAS_ACTIONS){const paint=['studio_sketch_paint_journal','studio_sketch_sample_color','studio_sketch_archive','studio_sketch_restore_archive'].includes(alias);const repair=['studio_sketch_import_drawing_asset','studio_get_native_result_page','studio_get_native_delivery_status','studio_compact_native_delivery'].includes(alias);assert.throws(()=>contract.requireStoryboardNativeVersion({appVersion:'3.8.1'},alias),alias==='studio_sketch_layered_drawing_preview'?/4\.9\.2/:['studio_sketch_import_reference_layers','studio_sketch_reference_layers_preview'].includes(alias)?/4\.8\.5/:alias.startsWith('studio_sketch_correction_')?/4\.8\.4/:(alias.startsWith('studio_sketch_story_')||['studio_project_asset_info','studio_project_import_asset'].includes(alias))?/4\.8\.0/:alias==='studio_sketch_animation_place_in_book'?/4\.8\.0/:alias==='studio_sketch_book_timeline'||alias==='studio_sketch_import_animation'||alias.startsWith('studio_sketch_component_')?/4\.7\.0/:alias.startsWith('studio_sketch_animation_')?/4\.4\.0/:repair?/4\.3\.1/:alias==='studio_cinematic_3d_edit_program'?/4\.3\.0/:alias==='studio_import_mailbox_media'?/4\.2\.0/:paint?/4\.1\.0/:/3\.9\.[0156]/);}
 await assert.rejects(old.relay.appEnqueueV3(old.owner,'studio_sketch_get',{sketchId:'s'}),/3\.9\.0/);assert.equal(old.writes(),before);
 const current=await fixture('3.9.0'),currentBefore=current.writes();
 await assert.rejects(current.relay.appEnqueueV3(current.owner,'studio_sketch_get',{sketchId:'s',uri:'content://foreign'}));
 await assert.rejects(current.relay.appEnqueueV3(current.owner,'studio_cinematic_3d_render',{projectId:'p',programId:'r',outputProjectId:'existing'}));
 assert.equal(current.writes(),currentBefore);
});
test('native painting requires a compatible phone before any relay command is stored',async()=>{
 const older=await fixture('4.0.1'),before=older.writes();
 await assert.rejects(older.relay.appEnqueueV3(older.owner,'studio_sketch_edit',{sketchId:'s',operationId:'op',expectedRevision:1,operations:[{type:'add_paint_cel',layerId:'layer_1',objectId:'cel'}]}),/4\.1\.0/);
 await assert.rejects(older.relay.appEnqueueV3(older.owner,'studio_sketch_sample_color',{sketchId:'s',expectedRevision:1,source:'composite',x:.5,y:.5}),/4\.1\.0/);
 assert.equal(older.writes(),before);
 const current=await fixture('4.1.0');await current.relay.appEnqueueV3(current.owner,'studio_sketch_edit',{sketchId:'s',operationId:'op',expectedRevision:1,operations:[{type:'add_paint_cel',layerId:'layer_1',objectId:'cel'}]});
 const rows=await queueBindings.readCommandQueue(current.storage,'app-v3-cl:'+current.deviceId);assert.equal(rows.at(-1).action,'studio_sketch_edit');assert.equal(rows.at(-1).parameters.operations[0].type,'add_paint_cel');
});
test('studio relay alias permission checks use canonical one-file action and retain the Gallery wall',async()=>{
 const f=await fixture('3.9.0','one_file');
 assert.equal((await f.call('app_execute',{action:'studio_apply_tool',parameters:{clipIndex:0,tool:'opacity',settings:{opacity:.5}}})).queued,true);
 const before=f.writes();
 await assert.rejects(f.relay.appEnqueueV3(f.owner,'studio_precision_edit',{projectId:'p',expectedUpdatedAt:1,operations:[{clipId:'c',set:{x:.2}}]}),/permission mode|privacy boundary/);
 await assert.rejects(f.relay.appEnqueueV3(f.owner,'studio_gallery_list',{}),/permission mode|privacy boundary/);
 assert.equal(f.writes(),before);
 const rows=await queueBindings.readCommandQueue(f.storage,'app-v3-cl:'+f.deviceId);assert.equal(rows.at(-1).action,'studio_apply_tool');
});
test('live app status exposes relay and distinct running/backlog states without claiming job completion',async()=>{
 const f=await fixture('3.9.5');const c=await f.relay.appEnqueueV3(f.owner,'get_state',{});
 await f.relay.appCompleteV3(f.deviceId,f.owner,c.id,{ok:true,queued:true,background:true,jobId:'native-job-owned'},'completed');
 const status=await f.call('app_status',{});assert.equal(status.relay.commandStorage.version,1);assert.equal(status.commandBacklog.pending,0);assert.equal(status.lastCommand.execution.workState,'awaiting_job');
 const receipt=await f.call('app_get_command_result',{commandId:c.id});assert.equal(receipt.status,'completed');assert.equal(receipt.execution.workState,'awaiting_job');assert.equal(receipt.execution.outputVerified,false);
});

test('legacy native result compatibility returns delivery and job evidence with or without images',async()=>{
 const f=await fixture();const legacy=f.compatibilityServer().tools.get('get_video_command_result');
 for(const image of [false,true]){
  const command=await f.relay.appEnqueueV3(f.owner,'get_state',{});
  const result={ok:true,queued:true,jobId:'legacy-job',...(image?{contactSheet:{base64:'aGVsbG8=',mime:'image/png'}}:{})};
  await f.relay.appCompleteV3(f.deviceId,f.owner,command.id,result,'completed');
  const response=await legacy.handler({deviceId:f.owner,commandId:command.id});const view=JSON.parse(response.content[0].text);
  assert.equal(view.execution.workState,'awaiting_job');assert.equal(view.execution.outputVerified,false);
  assert.equal(response.content.filter(item=>item.type==='image').length,image?1:0);
 }
});

test('native archived result pages retain exact encoded bytes through the real MCP result handler',async()=>{
 const f=await fixture('4.3.1'),original=Buffer.from(JSON.stringify({ok:true,details:'日本語 drawing receipt'}));
 const request=await f.call('app_get_native_result_page',{commandId:'original-command',pageIndex:0});
 assert.equal(request.queued,true);
 const digest=Buffer.from(await webcrypto.subtle.digest('SHA-256',original)).toString('hex');
 const page={ok:true,schema:'videostudio.native-result-page.v1',commandId:'original-command',status:'completed',resultBytes:original.length,resultSha256:digest,encoding:'base64',pageIndex:0,pageCount:1,encodedChunk:original.toString('base64')};
 await f.relay.appCompleteV3(f.deviceId,f.owner,request.commandId,page,'completed');
 const returned=await f.call('app_get_command_result',{commandId:request.commandId});
 assert.equal(returned.result.encodedChunk,page.encodedChunk);
 assert.deepEqual(Buffer.from(returned.result.encodedChunk,'base64'),original);
 assert.equal(returned.result.resultSha256,digest);
});
test('one-file mode admits receipt paging while retaining drawing-import and history-repair restrictions',async()=>{
 const f=await fixture('4.3.1','one_file');
 const result=await f.call('app_get_native_result_page',{commandId:'selected-file-original-result',pageIndex:0});
 assert.equal(result.queued,true);
 assert.equal((await f.call('app_get_native_delivery_status',{})).queued,false);
 assert.equal((await f.call('app_compact_native_delivery',{commandId:'original-command',expectedResultSha256:'a'.repeat(64)})).queued,false);
});
