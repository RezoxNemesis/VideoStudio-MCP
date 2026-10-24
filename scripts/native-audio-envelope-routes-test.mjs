import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {z} from 'zod';
import * as contract from '../src/storyboard-contract.js';
import * as studioContract from '../src/studio-workbench-contract.js';
import {nativeCommandSummary,nativeResultPage} from '../src/native-result-pages.js';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';

const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const request=()=>({mode:'audio_envelope',projectId:'p',expectedUpdatedAt:123,assetId:'a',expectedSha256:'a'.repeat(64),sourceMs:0,durationMs:24000,buckets:576});
async function fixture(version='4.10.25'){
  const rows=new Map(),storage=memoryStorage(rows,{maxValueBytes:128*1024});
  class McpServer {constructor(){this.tools=new Map();}registerTool(name,definition,handler){this.tools.set(name,{definition,handler});}}
  const context={...contract,...studioContract,...queueBindings,nativeCommandSummary,nativeResultPage,z,McpServer,crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,
    DurableObject:class {constructor(ctx){this.ctx=ctx;}},createMcpHandler:()=>()=>new Response(),
    APP_HTML:'',STUDIO_RUNTIME_JS:'',STUDIO_CINEMATIC_JS:'',STUDIO_NEURAL_JS:'',STUDIO_TEMPORAL_JS:'',STUDIO_CHARACTER_JS:'',STUDIO_ACTION_TIMELINE_JS:'',STUDIO_POSE_SEQUENCE_JS:'',LOCAL_VIDEO_DOWNLOAD_HTML:''};
  vm.createContext(context);
  vm.runInContext(source.replace(/^import .*$/gm,'').replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState')
    .replace('export default {','globalThis.worker = {')+'\nglobalThis.nativeTools=serverForApp;globalThis.compatibilityTools=serverFor;',context);
  const relay=new context.VideoStudioState({storage},{}),owner='e'.repeat(43),deviceId='audio-envelope-device';
  await relay.appRegister(deviceId,owner,{appVersion:version,protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything'});
  const env={VIDEO_STATE:{getByName:()=>relay}},server=context.nativeTools(env,owner,3),compatibility=context.compatibilityTools(env);
  const call=async(name,args)=>{
    const tool=(name.startsWith('app_')?server:compatibility).tools.get(name);assert.ok(tool,name);
    const schema=typeof tool.definition.inputSchema.parse==='function'?tool.definition.inputSchema:z.object(tool.definition.inputSchema);
    return JSON.parse((await tool.handler(schema.parse(args))).content[0].text);
  };
  return {rows,storage,relay,owner,deviceId,server,call,commands:()=>queueBindings.readCommandQueue(storage,'app-v3-cl:'+deviceId)};
}

test('strict audio identity and bounds accept endpoints and reject non-JSON/coercive values',()=>{
  for(const parameters of [request(),{...request(),projectId:'Z_-'.repeat(32),assetId:'x'.repeat(96),expectedUpdatedAt:Number.MAX_SAFE_INTEGER,sourceMs:86400000,durationMs:30000,buckets:720},{...request(),expectedUpdatedAt:0,durationMs:100,buckets:4}])
    assert.deepEqual(contract.nativeStoryboardParameters('analyse_media',parameters),parameters);
  for(const key of ['projectId','assetId'])for(const value of ['',null,1,true,'has space','../owned','x'.repeat(97)])assert.throws(()=>contract.nativeStoryboardParameters('analyse_media',{...request(),[key]:value}),/AUDIO_ENVELOPE_INPUT/);
  for(const value of [null,true,'123',NaN,Infinity,-1,.5,Number.MAX_SAFE_INTEGER+1])assert.throws(()=>contract.nativeStoryboardParameters('analyse_media',{...request(),expectedUpdatedAt:value}),/AUDIO_ENVELOPE_INPUT/);
  for(const value of ['A'.repeat(64),'a'.repeat(63),'a'.repeat(65),null,false,7])assert.throws(()=>contract.nativeStoryboardParameters('analyse_media',{...request(),expectedSha256:value}),/AUDIO_ENVELOPE_INPUT/);
  for(const key of ['_mcpCommandId','_audioAdmission','operationId','frames','includeAudio','uri','unexpected'])assert.throws(()=>contract.nativeStoryboardParameters('analyse_media',{...request(),[key]:undefined}),/AUDIO_ENVELOPE_INPUT/);
});

test('every existing native/compatibility route preserves audio request without visual defaults',async()=>{
  const f=await fixture(),parameters=request();
  await f.relay.appEnqueueV3(f.owner,'analyse_media',parameters);
  await f.relay.appEnqueue(f.owner,'analyse_media',parameters);
  for(const [name,args] of [
    ['app_analyse_media',parameters],
    ['queue_video_edit',{deviceId:f.owner,projectId:'active-native',action:'analyse_media',parameters}],
    ['queue_video_edit_batch',{deviceId:f.owner,projectId:'active-native',edits:[{action:'analyse_media',parameters}]}],
    ['request_media_analysis',{deviceId:f.owner,...parameters}]
  ])assert.equal((await f.call(name,args)).queued,true,name);
  const commands=[...await f.commands(),...await queueBindings.readCommandQueue(f.storage,'app-cl:'+f.deviceId)];
  assert.equal(commands.length,6);
  for(const command of commands){assert.equal(command.action,'analyse_media');assert.deepEqual(command.parameters,parameters);assert.equal(Object.hasOwn(command.parameters,'frames'),false);}
});

test('all audio routes reject older native versions without queue or sequence writes',async()=>{
  const f=await fixture('4.10.24'),before=f.storage.writes(),parameters=request();
  await assert.rejects(f.relay.appEnqueueV3(f.owner,'analyse_media',parameters),/4\.10\.25/);
  await assert.rejects(f.relay.appEnqueue(f.owner,'analyse_media',parameters),/4\.10\.25/);
  for(const [name,args] of [
    ['app_analyse_media',parameters],
    ['queue_video_edit',{deviceId:f.owner,projectId:'active-native',action:'analyse_media',parameters}],
    ['queue_video_edit_batch',{deviceId:f.owner,projectId:'active-native',edits:[{action:'analyse_media',parameters}]}],
    ['request_media_analysis',{deviceId:f.owner,...parameters}]
  ]){const result=await f.call(name,args);assert.equal(result.queued,false,name);assert.match(result.error,/4\.10\.25/);}
  assert.equal(f.storage.writes(),before);assert.deepEqual(await f.commands(),[]);
});

test('actual typed schemas reject unknown/mixed audio keys before handlers can discard them',async()=>{
  const f=await fixture(),before=f.storage.writes();
  for(const patch of [{frames:12},{start:0},{end:1},{startMs:0},{endMs:100},{includeAudio:false},{_mcpCommandId:'forged'},{unknown:true},{mode:'visual'},{mode:null}])
    for(const name of ['app_analyse_media','request_media_analysis'])await assert.rejects(f.call(name,{...(name==='request_media_analysis'?{deviceId:f.owner}:{}),...request(),...patch}));
  assert.equal(f.storage.writes(),before);
});

test('raw and batch audio reject forged metadata/overrides and missing CAS before writes',async()=>{
  const f=await fixture(),before=f.storage.writes();
  for(const patch of [{_mcpCommandId:'forged'},{nativeAction:'get_state'},{nativeParameters:{}},{frames:12},{expectedSha256:undefined},{assetId:undefined}]){
    const parameters={...request(),...patch};
    await assert.rejects(f.relay.appEnqueueV3(f.owner,'analyse_media',parameters),/AUDIO_ENVELOPE_INPUT/);
    for(const [name,args] of [
      ['queue_video_edit',{deviceId:f.owner,projectId:'active-native',action:'analyse_media',parameters}],
      ['queue_video_edit_batch',{deviceId:f.owner,projectId:'active-native',edits:[{action:'analyse_media',parameters}]}]
    ])assert.equal((await f.call(name,args)).queued,false,name);
  }
  assert.equal(f.storage.writes(),before);
});

test('offline explicit audio stays durable native and never resolves a browser fallback project',async()=>{
  const f=await fixture();let fallbackCalls=0;
  const device=await queueBindings.readNativeDevice(f.storage,'app-device:'+f.deviceId);
  await queueBindings.writeNativeDevice(f.storage,'app-device:'+f.deviceId,{...device,lastSeenAt:'2020-01-01T00:00:00Z'});
  f.relay.appTryStudioWebFallback=async()=>{fallbackCalls++;throw new Error('must not resolve browser fallback');};
  const command=await f.relay.appEnqueueV3(f.owner,'analyse_media',request());
  assert.equal(command.status,'waiting_native');assert.equal(fallbackCalls,0);assert.deepEqual((await f.commands())[0].parameters,request());
});

test('direct browser audio fails before project access/writes and fallback direct call does not resolve',async()=>{
  const f=await fixture(),before=f.storage.writes();let lookups=0;
  f.relay.project=async()=>{lookups++;throw new Error('must not lookup browser project');};
  await assert.rejects(f.relay.enqueue('browser-id','project-id','analyse_media',request()),/native VideoStudio/);
  f.relay.appResolveStudioWebFallback=async()=>{lookups++;throw new Error('must not resolve fallback');};
  assert.equal(await f.relay.appTryStudioWebFallback(f.owner,'analyse_media',request()),null);
  assert.equal(lookups,0);assert.equal(f.storage.writes(),before);
});

test('omitted mode retains app visual default12 and compatibility visual bytes without audio fields',async()=>{
  const f=await fixture('3.7.0');
  assert.equal((await f.call('app_analyse_media',{})).queued,true);
  assert.equal((await f.call('app_analyse_media',{assetId:'old-video',start:0,end:12.5})).queued,true);
  assert.equal((await f.call('request_media_analysis',{deviceId:f.owner,projectId:'active-native',assetId:'old-video',start:0,end:12.5,frames:8,includeAudio:false})).queued,true);
  assert.equal((await f.call('request_media_analysis',{deviceId:f.owner,projectId:'active-native'})).queued,true);
  assert.deepEqual((await f.commands()).map(command=>command.parameters),[
    {frames:12},{frames:12,assetId:'old-video',start:0,end:12.5},{assetId:'old-video',start:0,end:12.5,frames:8,includeAudio:false},{}
  ]);
  await assert.rejects(f.call('app_analyse_media',{assetId:'short'}));
  assert.equal((await f.call('app_analyse_media',{assetId:'x'.repeat(128)})).queued,true);
});

test('ordinary native capability discovery reports the approved bounded measurement contract',async()=>{
  const f=await fixture(),capabilities=await f.call('app_capabilities',{});
  assert.deepEqual(capabilities.audioAnalysis,contract.AUDIO_ENVELOPE_CAPABILITIES);
  assert.equal(capabilities.audioAnalysis.minimumNativeVersion,'4.10.25');
  assert.equal(capabilities.audioAnalysis.pcm.samplePeakIsTruePeak,false);
  assert.equal(capabilities.audioAnalysis.coverage.missingMetricsAreNull,true);
  assert.equal(capabilities.audioAnalysis.source.linkedContentSupported,false);
  assert.equal(Object.isFrozen(contract.AUDIO_ENVELOPE_CAPABILITIES),true);
  const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeAudioEnvelope.java',import.meta.url),'utf8');
  const literal=java.match(/CAPABILITY_JSON\s*=\s*("(?:\\.|[^"\\])*")/);assert.ok(literal);
  assert.deepEqual(JSON.parse(JSON.parse(literal[1])),contract.AUDIO_ENVELOPE_CAPABILITIES);
  const schema=z.toJSONSchema(f.server.tools.get('app_analyse_media').definition.inputSchema,{io:'input'});
  assert.equal(schema.type,'object');assert.equal(schema.properties.mode.const,'audio_envelope');
});

test('hybrid binding uses the same strict audio contract and native floor',async()=>{
  for(const version of ['4.10.24','4.10.25']){
    const f=await fixture(version),browserId='audio-envelope-browser';
    await f.relay.register(browserId);
    const challenge=await f.relay.appCreateHybridBinding(f.owner,browserId);
    const binding=await f.relay.appRedeemHybridBinding(challenge.token,browserId);
    const before=f.storage.writes();
    await assert.rejects(f.relay.appEnqueueHybrid(binding.hybridKey,'analyse_media',{...request(),_mcpCommandId:'forged'}),/AUDIO_ENVELOPE_INPUT/);
    assert.equal(f.storage.writes(),before);
    if(version==='4.10.24'){
      await assert.rejects(f.relay.appEnqueueHybrid(binding.hybridKey,'analyse_media',request()),/4\.10\.25/);
      assert.deepEqual(await f.commands(),[]);assert.equal(f.rows.has('app-v3-seq:'+f.deviceId),false);
    }else{
      const command=await f.relay.appEnqueueHybrid(binding.hybridKey,'analyse_media',request());
      assert.equal(command.action,'analyse_media');assert.deepEqual(command.parameters,request());
    }
  }
});
