import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const actions=['sketch_edit','studio_sketch_edit'];
const defaults={view:'front',handedness:'right',detail:'anatomical',skinColor:'#FFD6A078',nailColor:'#FFE5C1AC',centerX:.5,centerY:.55,size:1,motion:1,wristMotion:.6};
const views=['front','left','right','back','high','low'];
const channels=['indexCurl','middleCurl','ringCurl','littleCurl','thumbCurl','thumbOpposition','spread','wristFlex','wristTurn'];
const settingLimits={centerX:[.2,.8],centerY:[.2,.8],size:[.4,1.2],motion:[0,1],wristMotion:[0,1]};
const channelLimits=Object.fromEntries(channels.map(name=>[name,name.startsWith('wrist')?[-25,25]:[0,1]]));
const hand={type:'add_hand_animation',layerId:'layer_1',handId:'hand',settings:{}};
const request=(patch={})=>({sketchId:'original',operationId:'hand_1',expectedRevision:1,operations:[{...hand,...patch}]});
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');

test('hand operation preserves the native envelope and leaves defaults and sparse channels to native',()=>{
 for(const action of actions)for(const settings of [{},defaults]){
  const r=request({settings,poses:[{frame:0,indexCurl:1,thumbOpposition:.7},{frame:90,spread:.5},{frame:239}]});
  assert.deepEqual(contract.nativeStudioParameters(action,r),r);
 }
 for(const action of actions)assert.deepEqual(contract.nativeStudioParameters(action,request()),request());
});

test('hand requires safe bounded identities, required strict settings and no extra operation fields',()=>{
 for(const action of actions){
  const r=request({handId:'h'.repeat(20),layerId:'l'.repeat(96)});
  assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const patch of [{handId:undefined},{layerId:undefined},{settings:undefined},{settings:null},{settings:[]},
   {handId:''},{handId:'h'.repeat(21)},{handId:'../hand'},{handId:'hand space'},{layerId:'with space'},{layerId:'l'.repeat(97)},
   {settings:{unknown:true}},{settings:{width:.5}},{preset:'curl'},{faceId:'face'},
   {url:'https://example.test/hand.png'},{speech:{}},{audioAssetId:'audio'},{poses:null}])
   assert.throws(()=>contract.nativeStudioParameters(action,request(patch)),JSON.stringify(patch));
 }
});

test('hand settings accept only six projected camera views, handedness and anatomy detail',()=>{
 for(const action of actions)for(const [field,values] of Object.entries({view:views,handedness:['right','left'],detail:['anatomical','contour']})){
  for(const value of values){const r=request({settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of ['unknown','',null,0,true,[],{}])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[field]:value}})));
 }
 for(const view of ['rear','Front',' front'])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',request({settings:{view}})));
});

for(const [field,[min,max]] of Object.entries(settingLimits))test(`hand setting ${field} enforces inclusive finite bounds`,()=>{
 for(const action of actions){
  for(const value of [min,max]){const r=request({settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null,true])
   assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[field]:value}})));
 }
});

test('hand skin and nail colors require eight-digit ARGB',()=>{
 for(const action of actions)for(const field of ['skinColor','nailColor']){
  for(const value of ['#00000000','#FfAa9876']){const r=request({settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of ['red','#FFFFFF','#GG000000',42,null])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[field]:value}})));
 }
});

for(const [field,[min,max]] of Object.entries(channelLimits))test(`hand pose ${field} enforces its native range without filling omitted channels`,()=>{
 for(const action of actions){
  const r=request({poses:[{frame:0,[field]:min},{frame:12,[field]:max},{frame:239}]});
  assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null,true])
   assert.throws(()=>contract.nativeStudioParameters(action,request({poses:[{frame:0,[field]:value}]})));
 }
});

test('hand poses contain 1..32 ordered integer keys starting at zero inside 240 frames',()=>{
 for(const action of actions){
  for(const poses of [[{frame:0}],Array.from({length:32},(_,i)=>({frame:i===31?239:i}))]){
   const r=request({poses});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
  for(const poses of [[],null,[{}],[{frame:1}],[{frame:-1}],[{frame:.5}],[{frame:'0'}],
   [{frame:0},{frame:240}],[{frame:0},{frame:0}],[{frame:0},{frame:20},{frame:10}],
   Array.from({length:33},(_,frame)=>({frame})),[{frame:0,unknown:true}],[{frame:0,IndexCurl:.5}]])
   assert.throws(()=>contract.nativeStudioParameters(action,request({poses})),JSON.stringify(poses));
 }
});

test('hand poses preserve standard interpolation and require Bezier controls exclusively for bezier',()=>{
 for(const action of actions){
  for(const interpolation of [undefined,'linear','hold','smooth','ease_in','ease_out','ease_in_out','bezier']){
   const pose={frame:0,...Object.fromEntries(channels.map(c=>[c,c.startsWith('wrist')?10:.5]))};
   if(interpolation!==undefined)pose.interpolation=interpolation;
   if(interpolation==='bezier')pose.curve=[.42,0,.58,1];
   const r=request({poses:[pose,{frame:239}]});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
  for(const patch of [{interpolation:'bezier'},{curve:[0,0,1,1]},{interpolation:'hold',curve:[0,0,1,1]},
   {interpolation:'ease'},{interpolation:null},{interpolation:'bezier',curve:[0,0,1]},
   {interpolation:'bezier',curve:[-1,0,1,1]},{interpolation:'bezier',curve:[0,0,1,1.01]},
   {interpolation:'bezier',curve:[0,0,1,NaN]},{interpolation:'bezier',curve:null},{easing:'linear'}])
   assert.throws(()=>contract.nativeStudioParameters(action,request({poses:[{frame:0,...patch}]})));
 }
});

test('hand gate requires native 4.8.0 on both edit aliases and either mixed-batch order',()=>{
 const character={type:'add_character_animation',layerId:'layer_1',characterId:'actor',settings:{}};
 for(const action of actions)for(const operations of [[hand],[hand,character],[character,hand]]){
  const r={...request(),operations};
  for(const device of [undefined,{}, {appVersion:''},{appVersion:'unknown'},{appVersion:'4.7.7'},{appVersion:'4.7.8'}])
   assert.throws(()=>contract.requireStudioVersion(device,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*hand.*4\.8\.0/);
  for(const appVersion of ['4.8.0','4.8.0-beta','4.8.1','4.9.0','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,r));
 }
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.7.8'},'sketch_edit',{...request(),operations:[character]}));
});

test('real relay rejects old native and malformed hand batches before any queue write',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);
 vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='h'.repeat(43),deviceId='hand-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.8');let before=storage.writes();
  await assert.rejects(relay.appEnqueueV3(key,action,request()),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.0/);
  assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();
  for(const patch of [{settings:{size:2}},{handId:'h'.repeat(21)},{poses:[{frame:1}]},{poses:[{frame:0,wristFlex:26}]}]){
   const r={...request(),operations:[hand,{...hand,handId:'other',...patch}]};
   await assert.rejects(relay.appEnqueueV3(key,action,r));assert.equal(storage.writes(),before);
  }
  const r=request({settings:defaults,poses:[{frame:0,indexCurl:1,interpolation:'bezier',curve:[.42,0,.58,1]},{frame:179}]});
  const admitted=await relay.appEnqueueV3(key,action,r);
  assert.deepEqual(JSON.parse(JSON.stringify(admitted.parameters)),r);
  assert.equal(admitted.seq,1);
  assert.equal((await queueBindings.readCommandQueue(storage,'app-v3-cl:'+deviceId)).length,1);
 }
});

test('hand metadata exposes native defaults, precise budgets and bounded projected2D claims',()=>{
 const metadata=contract.HAND_ANIMATION_CAPABILITIES;
 assert.ok(metadata,'hand metadata exported for reuse');
 assert.equal(metadata.minimumNativeVersion,'4.8.0');assert.equal(metadata.operation,hand.type);assert.equal(metadata.action,'sketch_edit');
 assert.deepEqual(metadata.settings,{...defaults,renderStyle:'smooth'});assert.deepEqual(metadata.views,views);
 assert.deepEqual(metadata.handedness,['right','left']);assert.deepEqual(metadata.details,['anatomical','contour']);
 assert.deepEqual(metadata.channels,channels);assert.deepEqual(metadata.channelLimits,channelLimits);
 assert.deepEqual(metadata.settingLimits,{...settingLimits,colors:'Eight-digit #AARRGGBB'});
 assert.deepEqual(metadata.budgets,{maximumBytes:4194304,maximumObjects:4096,maximumPoints:131072,maximumKeys:4096});
 for(const flag of ['genuine3D','photorealistic','phoneExecutionVerified','speechVerified','creativeQualityCertified'])assert.equal(metadata[flag],false,flag);
 assert.match(metadata.limits,/4\.\.8 seconds.*24\.\.240 frames.*24\/30\/60 FPS/);
 assert.match(metadata.limits,/1\.\.32 poses.*handId up to 20/);
 assert.match(metadata.poseControls,/neutral defaults.*not the preceding pose/);
 assert.match(metadata.poseControls,/-25\.\.25 degrees/);
 assert.match(metadata.geometry,/projected2D.*occlusion/);
 assert.match(metadata.semantics,/exposure.*unique drawings/);
 assert.match(metadata.manualControls,/Hand animation/);
 assert.match(metadata.agentGuidance,/ordinary ChatGPT.*app_status.*4\.8\.0/);
 const review=metadata.reviewChecks;
 assert.ok(review,'standard 2D review dimensions are available to ordinary capability consumers');
 assert.deepEqual(review.requiredViews,views);assert.deepEqual(review.handedness,['right','left']);
 assert.match(review.palmAndWrist,/coherent palm silhouette.*wrist join/i);
 assert.match(review.fingertipCoverage,/every fingertip.*both hands.*six views/i);
 assert.match(review.posesAndMotion,/readable poses.*motion timing.*volume/i);
 assert.match(review.surfaceOcclusion,/nails.*creases.*occluded.*no floating marks/i);
 assert.match(review.contact,/when applicable.*exact contact.*no hand\/prop penetration/i);
 assert.match(review.motionCoverage,/full motion.*all six views.*still frames.*insufficient/i);
 assert.match(review.technicalResult,/technical PASS.*does not.*visual/i);
 assert.deepEqual(review.visualOutcomes,['ACCEPTABLE','NEEDS_REWORK','UNREVIEWED']);
 assert.equal(review.automaticQualityCertification,false);
 assert.match(metadata.agentGuidance,/reviewChecks.*full motion.*all six views.*no automatic quality certification/i);
});

test('hand study discovery exposes four product recipes without adding operation fields or actions',()=>{
 const metadata=contract.HAND_ANIMATION_CAPABILITIES;
 assert.deepEqual(metadata.studyPatterns,['open_close','grasp','finger_cascade','thumb_opposition']);
 assert.match(metadata.studyExampleGuidance,/sketch_capabilities\.handAnimation\.studyExamples/);
 assert.match(metadata.studyExampleGuidance,/1280.*720.*96.*24/);
 assert.match(metadata.studyExampleGuidance,/fresh.*sketchId.*operationId.*handId/i);
 assert.match(metadata.studyExampleGuidance,/Load study.*current.*clock.*settings/i);
 for(const action of actions)assert.throws(()=>contract.nativeStudioParameters(action,request({pattern:'grasp'})));
 const source=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchCapabilities.java',import.meta.url),'utf8');
 assert.match(source,/\.put\("studyExamples",SketchHandStudies\.examples\(\)\)/);
});

test('actual MCP app capabilities expose hand metadata only for the v3 agent',async()=>{
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);
 for(const isV3 of [true,false]){
  let handler;
  vm.runInNewContext(worker.slice(start,end),{s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[],CHARACTER_PERFORMANCE_CAPABILITIES:contract.CHARACTER_PERFORMANCE_CAPABILITIES,HAND_ANIMATION_CAPABILITIES:contract.HAND_ANIMATION_CAPABILITIES});
  const result=await handler();
  if(isV3){assert.ok(result.handAnimation);assert.deepEqual(result.handAnimation,contract.HAND_ANIMATION_CAPABILITIES);}
  else assert.equal(result.handAnimation,undefined);
 }
 assert.match(worker,/import \{[^}]*HAND_ANIMATION_CAPABILITIES[^}]*\} from '\.\/studio-workbench-contract\.js'/);
});

test('shared Java/Node hand metadata matches and native templates use the product helper without Gradle',()=>{
 const native=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchCapabilities.java',import.meta.url),'utf8');
 assert.match(native,/\.put\("handAnimation",handAnimation\(\)\)/);
 const body=native.match(/public static JSONObject handAnimation\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)?.[1];
 assert.ok(body,'independent reusable native metadata method');
 // Evaluate the literal JSON builder, including nested arrays/objects; this is a
 // source parity check, not evidence of Java compilation or native rendering.
 class JSONObject {put(key,value){this[key]=value;return this;}}
 class JSONArray extends Array {constructor(values){super();this.push(...values);}}
 const studies=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchHandStudies.java',import.meta.url),'utf8');
 const patterns=JSON.parse('['+studies.match(/PATTERNS=\{([^}]*)\}/)[1]+']');
 // Native dynamic examples are validated against the unchanged matrix and actual
 // constructor by SketchHandStudiesTest; Node advertises where to retrieve them.
 assert.match(body,/\.put\("studyExamples",SketchHandStudies\.examples\(\)\)/);
 const expression=body.replace('.put("studyExamples",SketchHandStudies.examples())','').replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]');
 const handSource=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchHandAnimation.java',import.meta.url),'utf8');
 const partBody=handSource.match(/static JSONObject partCapabilities\(\)throws Exception \{\s*return ([\s\S]*?);\s*\}/)?.[1];
 assert.ok(partBody,'native hand passes have inspectable shared metadata');
 const partExpression=partBody.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]');
 const SketchHandAnimation={partCapabilities:()=>vm.runInNewContext(`(${partExpression})`,{JSONObject,JSONArray})};
 const fingerSource=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchFingerJointAngles.java',import.meta.url),'utf8');
 const fingerJson=fingerSource.match(/CAPABILITY_JSON\s*=\s*("(?:[^"\\]|\\.)*")/)?.[1];assert.ok(fingerJson,'native finger angles have inspectable shared metadata');
 const SketchFingerJointAngles={capabilities:()=>JSON.parse(JSON.parse(fingerJson))};
 const nativeMetadata=vm.runInNewContext(`(${expression})`,{JSONObject,JSONArray,SketchHandStudies:{PATTERNS:patterns},SketchHandAnimation,SketchFingerJointAngles});
 assert.deepEqual(JSON.parse(JSON.stringify(nativeMetadata)),contract.HAND_ANIMATION_CAPABILITIES);
});

test('MCP edit guidance tells ordinary ChatGPT to check app_status and gate articulated hands',()=>{
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 for(const pattern of [/Native 4\.8\.0 adds add_hand_animation/,/sketch_capabilities\.handAnimation/,/ordinary ChatGPT.*app_status/,/1\.\.32 poses/,/neutral defaults/,/projected2D/,/4 MiB/,/Hand animation/])assert.match(detail,pattern);
});

test('advertised defaults and channels match the shared native constructor source',()=>{
 const native=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchHandAnimation.java',import.meta.url),'utf8');
 const body=native.match(/static JSONObject defaults\(\)\s*throws Exception\s*\{\s*return ([\s\S]*?);\s*\}/)?.[1];
 assert.ok(body,'shared native defaults are inspectable');
 class JSONObject {put(key,value){this[key]=value;return this;}}
 const settings=vm.runInNewContext(`(${body})`,{JSONObject});
 assert.deepEqual(JSON.parse(JSON.stringify(settings)),{...defaults,renderStyle:'smooth'});
 assert.deepEqual(contract.HAND_ANIMATION_CAPABILITIES.settings,{...defaults,renderStyle:'smooth'});
 const nativeChannels=JSON.parse('['+native.match(/String\[\] CHANNELS=\{([^}]+)\}/)[1]+']');
 assert.deepEqual(contract.HAND_ANIMATION_CAPABILITIES.channels,nativeChannels);
 const interpolation=native.match(/left\.optString\("interpolation","([^"]+)"\)/)[1];
 assert.ok(contract.HAND_ANIMATION_CAPABILITIES.poseControls.includes(`Default interpolation is ${interpolation}.`));
});

const handProgram={settings:defaults,poses:[{frame:0,indexCurl:.25},{frame:179,indexCurl:1}],durationFrames:180,fps:30};
const handObject={id:'retained_hand',kind:'hand',bounds:{x:.2,y:.1,width:.6,height:.8},hand:handProgram};
const transform={x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1};
const objectRequest=op=>({...request(),operations:[op]});
const handObjectOperations=hand=>[
 {type:'add_object',layerId:'layer_1',object:{...handObject,hand}},
 {type:'add_layer',layer:{id:'hands',objects:[{...handObject,hand,frame:0,exposure:180,color:'#FFD6A078',width:.00065,fill:true,transform,keys:[]}]}},
 {type:'update_object',objectId:'retained_hand',patch:{hand}}
];

test('retained hand sourceFrameOffset round-trips through all object routes without altering renderFrame or native gates',()=>{
 for(const action of actions)for(const sourceFrameOffset of [-1200,-1,0,1,1200])for(const renderFrame of [undefined,0,179]){
  const hand={...handProgram,sourceFrameOffset};if(renderFrame!==undefined)hand.renderFrame=renderFrame;
  for(const op of handObjectOperations(hand)){
   const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.8'},action,r),/4\.8\.0/);
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
  }
 }
});

test('hand sourceFrameOffset is a strict optional retained integer, never a constructor argument',()=>{
 for(const action of actions){
  for(const sourceFrameOffset of [-1201,1201,.5,-.5,NaN,Infinity,-Infinity,'0',null,true])for(const op of handObjectOperations({...handProgram,sourceFrameOffset}))
   assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
  for(const patch of [{sourceFrameOffset:0},{settings:{sourceFrameOffset:0}}])assert.throws(()=>contract.nativeStudioParameters(action,request(patch)));
  for(const op of handObjectOperations(handProgram)){const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
 }
 const source=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchHandAnimation.java',import.meta.url),'utf8');
 assert.match(source,/integer\(hand,"sourceFrameOffset",-1200,1200\)/);
 assert.match(source,/has\("renderFrame"\)\?hand\.getInt\("renderFrame"\):.*optInt\("sourceFrameOffset",0\)/);
 assert.match(contract.HAND_ANIMATION_CAPABILITIES.sourceFrameOffset,/retained.*-1200.*1200.*renderFrame.*precedence/i);
});

test('retained hand objects support partial adds, complete layer objects and full programme replacement',()=>{
 const complete={...handObject,frame:0,exposure:180,color:'#FFD6A078',width:.00065,fill:true,transform,keys:[]};
 for(const action of actions)for(const op of [
  {type:'add_object',layerId:'layer_1',object:handObject},
  {type:'add_layer',layer:{id:'hands',objects:[complete]}},
  {type:'update_object',objectId:'retained_hand',patch:{hand:{...handProgram,settings:{...defaults,motion:.5}},bounds:handObject.bounds}}
 ]){const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
 const incomplete={...complete};delete incomplete.transform;
 assert.throws(()=>contract.nativeStudioParameters('sketch_edit',objectRequest({type:'add_layer',layer:{id:'hands',objects:[incomplete]}})));
});

test('retained hand payload is strict, requires full settings, and bounds its original physical clock',()=>{
 for(const action of actions){
  for(const patch of [{settings:{}},{settings:{...defaults,unknown:true}},{poses:[]},{poses:[{frame:1}]},
   {poses:Array.from({length:33},(_,frame)=>({frame}))},{poses:[{frame:0,wristTurn:26}]},
   {durationFrames:23},{durationFrames:241},{durationFrames:180.5},{fps:25},{fps:'30'},
   {renderFrame:-1},{renderFrame:180},{renderFrame:.5},{renderFrame:null},{unknown:true},
   {poses:[{frame:0},{frame:180}]},{poses:[{frame:0,interpolation:'bezier'}]}]){
   for(const op of [{type:'add_object',layerId:'layer_1',object:{...handObject,hand:{...handProgram,...patch}}},
    {type:'update_object',objectId:'retained_hand',patch:{hand:{...handProgram,...patch}}}])
    assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)),JSON.stringify(patch));
  }
  for(const [durationFrames,fps] of [[96,24],[192,24],[120,30],[240,30],[240,60]]){
   const hand={...handProgram,durationFrames,fps,poses:[{frame:0},{frame:durationFrames-1}],renderFrame:durationFrames-1};
   const r=objectRequest({type:'add_object',layerId:'layer_1',object:{...handObject,hand}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
  for(const object of [{...handObject,hand:undefined},{...handObject,bounds:undefined},{...handObject,commands:[['M',0,0],['L',1,1]]}])
   assert.throws(()=>contract.nativeStudioParameters(action,objectRequest({type:'add_object',layerId:'layer_1',object})));
 }
});

test('retained hand adds, hand patches and full layer imports all require native 4.8.0',()=>{
 const complete={...handObject,frame:0,exposure:180,color:'#FFD6A078',width:.00065,fill:true,transform,keys:[]};
 const character={type:'add_character_animation',layerId:'layer_1',characterId:'actor',settings:{}};
 for(const action of actions)for(const op of [
  {type:'add_object',layerId:'layer_1',object:handObject},
  {type:'update_object',objectId:'retained_hand',patch:{hand:handProgram}},
  {type:'add_layer',layer:{id:'hands',objects:[complete]}}
 ])for(const operations of [[op],[character,op],[op,character]]){
  const r={...request(),operations};
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.8'},action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*hand.*4\.8\.0/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
 }
});

test('hand capability output describes retained book sampling without claiming public vector conversion',()=>{
 const output='Editable retained native hand settings/pose programme; sampled book pages retain native hand state. Per-stroke vector conversion is internal and is not exposed by this feature.';
 assert.equal(contract.HAND_ANIMATION_CAPABILITIES.output,output);
 assert.ok(worker.includes(output));
});

test('retained programmes preserve the same native 4..8 second clock',()=>{
 for(const [durationFrames,fps] of [[24,24],[95,24],[193,24],[119,30],[239,60]]){
  const hand={...handProgram,durationFrames,fps,poses:[{frame:0}]};
  assert.throws(()=>contract.nativeStudioParameters('sketch_edit',objectRequest({type:'add_object',layerId:'layer_1',object:{...handObject,hand}})),`${durationFrames}/${fps}`);
 }
});
