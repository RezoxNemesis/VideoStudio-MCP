import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const actions=['sketch_edit','studio_sketch_edit'];
const defaults={view:'front',build:'slender',material:'wool',detail:'tailored',gesture:'pendant',armLift:.8,headTilt:0,skinColor:'#FFD6A078',hairColor:'#FF252D3D',coatColor:'#FF263847'};
const character={type:'add_character_animation',layerId:'layer_1',characterId:'scholar',settings:{}};
const request=(patch={})=>({sketchId:'original',operationId:'character_1',expectedRevision:1,operations:[{...character,...patch}]});
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');

test('character operation preserves the exact native envelope and leaves defaults to native',()=>{
 for(const action of actions)for(const settings of [{},defaults]){
  const r=request({settings});
  assert.deepEqual(contract.nativeStudioParameters(action,r),r);
 }
});

test('character requires safe identities, required settings and no extra operation fields',()=>{
 for(const action of actions){
  const r=request({characterId:'c'.repeat(20),layerId:'l'.repeat(96)});
  assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const patch of [{characterId:undefined},{layerId:undefined},{settings:undefined},{settings:null},{settings:[]},
   {characterId:''},{characterId:'c'.repeat(21)},{characterId:'../actor'},{layerId:'with space'},{layerId:'l'.repeat(97)},
   {settings:{unknown:true}},{settings:{centerX:.5}},{settings:{tilt:2}},{poses:[{frame:0}]},{preset:'reaction'},
   {faceId:'face'},{url:'https://example.test/actor.png'},{audioAssetId:'audio'},{speech:null}])
   assert.throws(()=>contract.nativeStudioParameters(action,request(patch)),JSON.stringify(patch));
 }
});

test('character settings accept only the specified six views and authored styles',()=>{
 for(const action of actions)for(const [field,values] of Object.entries({view:['front','left','right','back','high','low'],build:['slender','broad'],material:['wool','leather'],detail:['contour','tailored'],gesture:['pendant','explain']})){
  for(const value of values){const r=request({settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of ['unknown','',null,0,true,[],{}])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[field]:value}})));
 }
 for(const view of ['rear','Front',' front'])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',request({settings:{view}})));
});

for(const [field,min,max] of [['armLift',0,1],['headTilt',-12,12]])test(`character ${field} has strict inclusive numeric bounds`,()=>{
 for(const action of actions){
  for(const value of [min,max]){const r=request({settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null,true])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[field]:value}})));
 }
});

test('character colors require eight-digit ARGB',()=>{
 for(const action of actions)for(const field of ['skinColor','hairColor','coatColor']){
  for(const value of ['#00000000','#FfAa9876']){const r=request({settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of ['red','#FFFFFF','#GG000000',42,null])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[field]:value}})));
 }
});

test('character reuses authored, generated and prediction speech with exclusive strict sources',()=>{
 const cue={startMs:500,endMs:750,shape:'closed'};
 const segment={phonemes:'m',startSample:0,sampleCount:1800,modelDurationUnits:[1,1,1],unitsPerSecond:40,forcedAlignmentVerified:false};
 const sources=[{cues:[cue]},{generated:{requestId:'original_speech',lineIndex:0}},{prediction:{startMs:500,segments:[segment]}}];
 for(const action of actions){
  for(const source of sources){const r=request({speech:{...source,transitionMs:60}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const speech of [{},{...sources[0],...sources[1]},{...sources[1],...sources[2]},
   {cues:[]},{cues:[{...cue,endMs:500}]},{cues:[cue,{...cue,startMs:600,endMs:800}]},
   {cues:[{...cue,shape:'guess'}]},{cues:[{...cue,strength:NaN}]},{cues:[{...cue,unknown:true}]},
   {...sources[0],transitionMs:201},{...sources[0],path:'/private/audio.wav'},
   {generated:{requestId:'../speech',lineIndex:0}},{generated:{requestId:'speech',lineIndex:32}},
   {generated:{requestId:'speech',lineIndex:0,path:'/private/audio.wav'}},
   {prediction:{startMs:500,segments:[{...segment,sampleCount:1799}]}},
   {prediction:{startMs:500,segments:[{...segment,forcedAlignmentVerified:true}]}}])
   assert.throws(()=>contract.nativeStudioParameters(action,request({speech})));
 }
});

test('character requires native 4.7.8 for aliases, speech and either mixed-batch order',()=>{
 const face={type:'add_face_animation',layerId:'layer_1',faceId:'face',settings:{}};
 for(const action of actions)for(const operations of [[character],[character,face],[face,character],[{...character,speech:{generated:{requestId:'speech',lineIndex:0}}}]]){
  const r={...request(),operations};
  for(const device of [undefined,{}, {appVersion:''},{appVersion:'unknown'},{appVersion:'4.7.6'},{appVersion:'4.7.7'}])
   assert.throws(()=>contract.requireStudioVersion(device,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*character.*4\.7\.8/);
  for(const appVersion of ['4.7.8','4.7.8-beta','4.7.10','4.8.0','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,r));
 }
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.7.6'},'sketch_edit',{...request(),operations:[face]}));
});

test('relay rejects old native and malformed characters before any queue write',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);
 vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='c'.repeat(43),deviceId='character-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.7');let before=storage.writes();
  await assert.rejects(relay.appEnqueueV3(key,action,request()),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.7\.8/);
  assert.equal(storage.writes(),before);
  await register('4.7.8');before=storage.writes();
  for(const patch of [{settings:{armLift:2}},{characterId:'c'.repeat(21)},{poses:[{frame:0}]},{speech:{cues:[]}}]){
   await assert.rejects(relay.appEnqueueV3(key,action,request(patch)));assert.equal(storage.writes(),before);
  }
  const r=request({settings:defaults,speech:{generated:{requestId:'speech',lineIndex:0}}});
  const admitted=await relay.appEnqueueV3(key,action,r);
  assert.deepEqual(JSON.parse(JSON.stringify(admitted.parameters)),r);
  assert.equal((await queueBindings.readCommandQueue(storage,'app-v3-cl:'+deviceId)).length,1);
 }
});

test('reusable character metadata is exposed by actual MCP app capabilities',async()=>{
 const metadata=contract.CHARACTER_PERFORMANCE_CAPABILITIES;
 assert.ok(metadata,'character metadata exported for reuse');
 assert.equal(metadata.minimumNativeVersion,'4.7.8');assert.equal(metadata.operation,character.type);
 assert.equal(metadata.action,'sketch_edit');assert.deepEqual(metadata.settings,defaults);
 assert.deepEqual(metadata.views,['front','left','right','back','high','low']);
 assert.equal(metadata.genuine3D,false);assert.equal(metadata.phoneExecutionVerified,false);
 assert.match(metadata.manualControls,/Character performance/);
 assert.match(metadata.limits,/4\.\.8 seconds.*24\.\.240 frames.*24\/30\/60 FPS/);
 assert.match(metadata.sourceLimits,/original.*benchmark.*reconstruction/i);
 assert.match(metadata.audioWorkflow,/silent/);
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);
 let handler;
 vm.runInNewContext(worker.slice(start,end),{s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3:true,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[],CHARACTER_PERFORMANCE_CAPABILITIES:metadata});
 assert.deepEqual((await handler()).characterPerformance,metadata);
});

test('MCP edit guidance and native capabilities expose the dedicated performance contract',()=>{
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 for(const pattern of [/4\.7\.8/,/add_character_animation/,/sketch_capabilities\.characterPerformance/,/front\/left\/right\/back\/high\/low/,/Character performance/,/authored 2D/,/4\.\.8 seconds/,/silent/,/benchmark/])assert.match(detail,pattern);
 const native=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchCapabilities.java',import.meta.url),'utf8');
 assert.match(native,/\.put\("characterPerformance",characterPerformance\(\)\)/);
 assert.match(native,/public static JSONObject characterPerformance\(\)/);
 for(const text of ['add_character_animation','4.7.8','Character performance','sourceLimits','genuine3D'])assert.ok(native.includes(text),text);
 assert.match(native,/\.put\("characterAnimation",new JSONObject\(\)\.put\("minimumNativeVersion","4\.4\.0"\)/);
});

 test('generated reduced schwa is accepted without removing duration tokens',()=>{
 const speech={prediction:{startMs:100,segments:[{phonemes:'ᵊ',startSample:0,sampleCount:1800,modelDurationUnits:[1,1,1],unitsPerSecond:40,forcedAlignmentVerified:false}]}};
 assert.doesNotThrow(()=>contract.nativeStudioParameters('sketch_edit',request({speech})));
 });
