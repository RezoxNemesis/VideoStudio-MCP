import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const actions=['sketch_edit','studio_sketch_edit'];
const legacyDefaults={view:'front',build:'slender',hairStyle:'short',skinColor:'#FFD6A078',hairColor:'#FF302D32',coatColor:'#FF526B78',trouserColor:'#FF343F50',x:.5,floorY:.88,size:1};
const appearanceDefaults={faceStyle:'graphic',shading:'cel',lightAngle:135};
const defaults={...legacyDefaults,...appearanceDefaults};
const presets=['listen','explain','reach','recoil','relief','bow','kneel','walk','run','point','offer','tie','inspect','surprise','anger','remorse','wave','look_up','look_down','stretch','step_back','balance','lift','greet'];
const channels=['rootX','rootY','rootZ','bodyLean','headTilt','gazeX','gazeY','mouth','mouthWide','mouthRound','mouthTeeth','mouthTongue','smile','brow','nearHandX','nearHandY','nearHandZ','farHandX','farHandY','farHandZ','hipX','hipY','hipZ','footLeftX','footLeftY','footLeftZ','footRightX','footRightY','footRightZ','blink','nearWrist','farWrist','handOpen','nearElbowSwivel','farElbowSwivel'];
const limits=Object.fromEntries(channels.map(c=>[c,c==='bodyLean'?[-25,25]:c==='headTilt'?[-20,20]:['gazeX','gazeY','smile','brow','mouthWide'].includes(c)?[-1,1]:['mouth','mouthRound','mouthTeeth','mouthTongue','blink','handOpen'].includes(c)?[0,1]:['nearWrist','farWrist','nearElbowSwivel','farElbowSwivel'].includes(c)?[-180,180]:[-4096,4096]]));
const actor={type:'add_human_animation',layerId:'layer_1',actorId:'human',settings:{}};
const request=(patch={})=>({sketchId:'shot',operationId:'human_1',expectedRevision:1,operations:[{...actor,...patch}]});
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const legacyMotionDefaults={speed:1,travelScale:1,groundSlope:0,direction:1,strength:1,armSwing:1};
const motionDefaults={...legacyMotionDefaults,groundSlopeX:0,phase:0};
const motionLimits={speed:[.5,1.5],travelScale:[0,1.5],groundSlope:[-.5,.5],direction:[-1,1],strength:[0,1],armSwing:[0,1],groundSlopeX:[-.5,.5],phase:[0,1]};
const blinkDefaults={intervalMs:3600,phaseMs:1800,closeMs:70,holdMs:80,openMs:110,strength:1};
const blinkLimits={intervalMs:[1000,30000],phaseMs:[0,30000],closeMs:[20,300],holdMs:[0,300],openMs:[20,400],strength:[0,1]};

test('human motion phase accepts independent cast cycle offsets with ground and unchanged version gates',()=>{
 for(const action of actions)for(const phase of [0,.25,.5,.999,1])for(const ground of [undefined,'bridge']){
  const patch={preset:'walk',motion:{phase,speed:.8,strength:.7},poseOverrides:{gazeX:.3},blinkTrack:false};if(ground!==undefined)patch.ground=ground;
  const r=request(patch);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,r),/4\.8\.0/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
 }
});

test('human motion phase rejects coercion, invalid ranges, explicit poses and misplaced fields',()=>{
 for(const action of actions){
  for(const phase of [-.001,1.001,NaN,Infinity,-Infinity,'0.5',null,true,{},[]])assert.throws(()=>contract.nativeStudioParameters(action,request({motion:{phase}})));
  for(const patch of [{phase:.5},{settings:{phase:.5}},{poseOverrides:{phase:.5}},{motion:{phase:.5},poses:[{frame:0}]},{motion:{phase:.5,footRoll:.2}}])assert.throws(()=>contract.nativeStudioParameters(action,request(patch)));
  for(const op of humanObjectOperations({...programme,phase:.5}))assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
 }
});

test('human motion phase omission and explicit zero preserve exact request fingerprints',async()=>{
 for(const action of actions)for(const motion of [undefined,{},legacyMotionDefaults,{groundSlopeX:0},{phase:0},{phase:.5}]){
  const patch={settings:{...legacyDefaults}};if(motion!==undefined)patch.motion=motion;
  const r=request(patch),parsed=contract.nativeStudioParameters(action,r);assert.deepEqual(parsed,r);
  assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(r));
 }
});

test('human motion phase metadata describes clock offset without shifting root travel origin',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;assert.equal(metadata.motionDefaults.phase,0);assert.deepEqual(metadata.motionLimits.phase,[0,1]);
 assert.match(metadata.motionControls,/phase.*0\.\.1.*native default 0/);assert.match(metadata.motionControls,/clock.*root travel start.*unchanged/i);
 assert.match(metadata.motionControls,/separate.*cast/i);assert.match(worker,/motion.*phase.*0\.\.1/);
});

test('human ground accepts the four native stage floors with non-slope motion and existing actor controls',()=>{
 for(const action of actions)for(const ground of ['forest_path','bridge','shelter','village'])for(const fit of [undefined,false]){
  const patch={ground,settings:{faceStyle:'angular',shading:'soft'},preset:'walk',motion:{speed:.8,travelScale:.6,direction:-1,strength:.9,armSwing:.2},poseOverrides:{gazeX:.5},blinkTrack:false,speech:{cues:[{startMs:0,endMs:100,shape:'closed'}]}};
  if(fit!==undefined)patch.fit=fit;const r=request(patch);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,r),/4\.8\.0/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
  const sparse=request({ground});assert.deepEqual(contract.nativeStudioParameters(action,sparse),sparse);
 }
});

test('human ground rejects ambiguous fit, explicit poses and either authored slope even when zero',()=>{
 for(const action of actions)for(const ground of ['forest_path','bridge','shelter','village']){
  for(const patch of [{fit:true},{poses:[{frame:0}]},{motion:{groundSlope:0}},{motion:{groundSlopeX:0}},
   {motion:{groundSlope:.5}},{motion:{groundSlopeX:-.5}},{motion:{speed:1,groundSlope:0,groundSlopeX:0}}])
   assert.throws(()=>contract.nativeStudioParameters(action,request({ground,...patch})));
 }
 for(const action of actions){
  for(const ground of ['forest','Bridge','',null,true,false,0,{},[]])assert.throws(()=>contract.nativeStudioParameters(action,request({ground})));
  assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{ground:'bridge'}})));
  for(const op of humanObjectOperations({...programme,ground:'bridge'}))assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
 }
});

test('old human motion and appearance request fingerprints remain unchanged without injected ground defaults',async()=>{
 for(const action of actions)for(const motion of [undefined,{},legacyMotionDefaults,{groundSlope:.25},{groundSlopeX:-.25}]){
  const patch={settings:{...legacyDefaults}};if(motion!==undefined)patch.motion=motion;
  const r=request(patch),parsed=contract.nativeStudioParameters(action,r);assert.deepEqual(parsed,r);
  assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(r));
 }
});

test('human ground metadata documents shared placement, slope axes, conflicts and manual parity',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;
 assert.deepEqual(metadata.grounds,['forest_path','bridge','shelter','village']);assert.deepEqual(metadata.grounds,contract.STORY_STAGE_CAPABILITIES.locations);
 assert.match(metadata.groundControls,/floorY.*groundSlopeX.*groundSlope.*native/i);
 assert.match(metadata.groundControls,/fit true.*explicit poses/);assert.match(metadata.groundControls,/omit.*groundSlope.*groundSlopeX.*zero/);
 assert.match(metadata.groundControls,/actual editable poses.*no.*ground.*payload/i);
 assert.match(metadata.motionControls,/groundSlopeX.*world.Y.*world.X/);
 assert.match(metadata.manualControls,/ground/i);
 assert.match(worker,/ground.*forest_path.*bridge.*shelter.*village/);
});

test('human appearance constructor accepts native face/shading choices and finite light angles at 4.8.0',()=>{
 for(const action of actions)for(const faceStyle of ['graphic','angular','soft'])for(const shading of ['cel','soft'])for(const lightAngle of [-180,0,135.5,180]){
  const r=request({settings:{faceStyle,shading,lightAngle}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,r),/4\.8\.0/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
 }
});

test('human appearance fields reject aliases, nonfinite values and coercion in constructors and all retained routes',()=>{
 for(const action of actions)for(const patch of [
  ...['realistic','Graphic','',null,0,true,{}].map(faceStyle=>({faceStyle})),
  ...['smooth','Cel','',null,0,true,{}].map(shading=>({shading})),
  ...[-180.001,180.001,NaN,Infinity,-Infinity,'135',null,true,{}].map(lightAngle=>({lightAngle})),
  {lighting:135},{material:'soft'}
 ]){
  assert.throws(()=>contract.nativeStudioParameters(action,request({settings:patch})));
  for(const op of humanObjectOperations({...programme,settings:{...legacyDefaults,...patch}}))assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
 }
});

test('legacy retained human settings preserve every appearance omission and exact request fingerprint',async()=>{
 const fields=Object.keys(appearanceDefaults);
 for(const action of actions)for(let mask=0;mask<8;mask++){
  const settings={...legacyDefaults};fields.forEach((field,i)=>{if(mask&(1<<i))settings[field]=appearanceDefaults[field];});
  for(const op of humanObjectOperations({...programme,settings})){
   const r=objectRequest(op),parsed=contract.nativeStudioParameters(action,r);assert.deepEqual(parsed,r);
   assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(r));
  }
 }
 for(const field of Object.keys(legacyDefaults)){
  const settings={...legacyDefaults};delete settings[field];
  assert.throws(()=>contract.nativeStudioParameters('sketch_edit',objectRequest({type:'update_object',objectId:'actor_rig',patch:{human:{...programme,settings}}})));
 }
 for(const action of actions){const r=request();assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
});

test('human appearance metadata matches integrated native/manual fields and states compatibility and gradient scope',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;
 assert.deepEqual(metadata.faceStyles,['graphic','angular','soft']);assert.deepEqual(metadata.shadingStyles,['cel','soft']);
 assert.deepEqual(metadata.settingLimits.lightAngle,[-180,180]);assert.deepEqual(metadata.settings,defaults);
 assert.match(metadata.appearance,/graphic.*default.*cel.*default.*135/);
 assert.match(metadata.appearance,/soft.*gradients.*body.*skin.*clothing.*excludes.*eyes.*mouth.*ink/);
 assert.match(metadata.appearance,/preserve.*geometry/i);assert.match(metadata.appearance,/legacy.*optional.*no.*inject/i);
 assert.match(metadata.manualControls,/Face structure.*Surface shading.*lightAngle/);
 const dialog=native('SketchHumanDialog');for(const field of ['faceStyle','shading','lightAngle'])assert.ok(dialog.includes('"'+field+'"'));
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];assert.match(detail,/faceStyle.*graphic.*angular.*soft.*shading.*cel.*soft.*lightAngle.*-180.*180/);
});

test('human fit is a strict optional constructor boolean requiring native 4.8.0 even when false',()=>{
 for(const action of actions){
  for(const fit of [false,true])for(const patch of [{},{poses:[{frame:0}]},{preset:'walk',motion:{speed:.8},poseOverrides:{gazeX:.5}}]){
   const r=request({...patch,fit});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,r),/4\.8\.0/);
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
  }
  for(const fit of [null,0,1,'true',{},[]])assert.throws(()=>contract.nativeStudioParameters(action,request({fit})));
  assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{fit:true}})));
  for(const op of humanObjectOperations({...programme,fit:true}))assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,request()));
 }
});

for(const [field,[min,max]] of Object.entries(blinkLimits))test(`human blinkTrack ${field} follows the independent native track range`,()=>{
 for(const action of actions){
  for(const value of [min,max]){
   const r=request({blinkTrack:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   for(const op of humanObjectOperations({...programme,blinkTrack:{...blinkDefaults,[field]:value}})){
    const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   }
  }
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0',null,true,...(field==='strength'?[]:[min+.5])]){
   assert.throws(()=>contract.nativeStudioParameters(action,request({blinkTrack:{[field]:value}})));
   for(const op of humanObjectOperations({...programme,blinkTrack:{...blinkDefaults,[field]:value}}))assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
  }
 }
});

test('human blinkTrack constructor preserves disabled/sparse options with explicit poses, motion, overrides and speech',()=>{
 for(const action of actions)for(const blinkTrack of [false,{},blinkDefaults,{intervalMs:1000,closeMs:300,holdMs:300,openMs:400}])
  for(const patch of [{},{preset:'walk',motion:{armSwing:.2},poseOverrides:{handOpen:.1}},{poses:[{frame:0,blink:.5},{frame:23,blink:0}]},{speech:{cues:[{startMs:0,endMs:100,shape:'closed'}]}}]){
   const r=request({...patch,fit:false,blinkTrack});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,r),/4\.8\.0/);
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
  }
 for(const action of actions){
  for(const blinkTrack of [false,{},blinkDefaults,{strength:.5}]){
   const r=request({blinkTrack});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,r),/4\.8\.0/);
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
  }
  for(const blinkTrack of [true,null,0,'false',[],{unknown:1},{enabled:true},{frame:0},{closeMs:300,holdMs:300,openMs:401,intervalMs:1000}])
   assert.throws(()=>contract.nativeStudioParameters(action,request({blinkTrack})));
  assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{blinkTrack:{}}})));
  for(const poses of [undefined,[{frame:0}]]){
   const patch={};if(poses!==undefined)patch.poses=poses;const r=request(patch);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
 }
});

test('retained human blinkTrack requires normalized full settings and gates every object route at 4.8.0',()=>{
 for(const action of actions){
  for(const op of humanObjectOperations({...programme,renderFrame:0,sourceFrameOffset:-10,blinkTrack:blinkDefaults})){
   const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   const face={type:'add_face_animation',layerId:'layer_1',faceId:'face',settings:{}};
   for(const operations of [[op],[face,op],[op,face]]){
    const mixed={...r,operations};assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,mixed),/4\.8\.0/);
    assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,mixed));
   }
  }
  for(const blinkTrack of [false,true,null,{},[],{...blinkDefaults,unknown:1},...Object.keys(blinkDefaults).map(field=>{const v={...blinkDefaults};delete v[field];return v;})])
   for(const op of humanObjectOperations({...programme,blinkTrack}))assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
 }
});

test('human fit/blink metadata states source floors, unchanged clock/size, full-frame sampling and view conventions',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;
 assert.equal(metadata.fitMinimumNativeVersion,'4.8.0');assert.equal(metadata.fitDefault,false);
 assert.match(metadata.fitControls,/x.*floorY.*anchor/);assert.match(metadata.fitControls,/never.*size.*width.*height.*FPS.*frames/);assert.match(metadata.fitControls,/cannot.*fit.*reject/i);
 assert.equal(metadata.blinkTrackMinimumNativeVersion,'4.8.0');assert.deepEqual(metadata.blinkTrackDefaults,blinkDefaults);assert.deepEqual(metadata.blinkTrackLimits,blinkLimits);
 assert.match(metadata.blinkTrackControls,/preset.*default.*explicit poses.*unless/i);assert.match(metadata.blinkTrackControls,/max\(pose.blink/);
 assert.match(metadata.blinkTrackControls,/localFrame.*1000.*fps/);assert.match(metadata.blinkTrackControls,/sampled.*remove.*track/i);
 assert.match(metadata.manualControls,/fit.*checkbox.*blink.*JSON/i);
 assert.match(metadata.poseConvention,/left.*bodyLean.*headTilt.*before.*poseOverrides.*custom poses.*clockwise/i);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];assert.match(detail,/fit.*blinkTrack.*4\.8\.0/);
 class JSONObject {put(key,value){this[key]=value;return this;}}
 const source=native('SketchHumanBlink'),expression=source.match(/static JSONObject defaults\(\)throws Exception\s*\{\s*return ([\s\S]*?);\s*\}/)[1];
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${expression})`,{JSONObject}))),blinkDefaults);
 for(const [field,[min,max]] of Object.entries(blinkLimits))assert.ok(source.includes(`SketchDocument.${field==='strength'?'number':'integer'}(value,"${field}",${min},${max})`));
 assert.match(metadata.blinkTrackControls,/integer milliseconds/);
});

test('fit and blink relay gates reject old apps and invalid settings before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='b'.repeat(43);
  const register=appVersion=>relay.appRegister('human-blink-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.9');let before=storage.writes();
  for(const r of [request({fit:false}),request({fit:true}),request({blinkTrack:false}),request({blinkTrack:{}}),objectRequest({type:'update_object',objectId:'actor_rig',patch:{human:{...programme,blinkTrack:blinkDefaults}}})]){
   await assert.rejects(relay.appEnqueueV3(key,action,r),/4\.8\.0/);assert.equal(storage.writes(),before);
  }
  await register('4.8.0');before=storage.writes();
  for(const patch of [{fit:1},{blinkTrack:true},{blinkTrack:{closeMs:70.5}},{blinkTrack:{unknown:1}}]){
   await assert.rejects(relay.appEnqueueV3(key,action,request(patch)));assert.equal(storage.writes(),before);
  }
  const r=request({fit:false,blinkTrack:{strength:.5},motion:{speed:.8},poseOverrides:{gazeX:.2}});
  assert.deepEqual(JSON.parse(JSON.stringify((await relay.appEnqueueV3(key,action,r)).parameters)),r);
 }
});

for(const [field,[min,max]] of Object.entries(motionLimits))test(`human preset motion ${field} follows the native strict control range`,()=>{
 for(const action of actions){
  for(const value of [min,max]){const r=request({preset:'walk',motion:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'1',null,true,...(field==='direction'?[0,.5,-.5]:[])])
   assert.throws(()=>contract.nativeStudioParameters(action,request({preset:'walk',motion:{[field]:value}})));
 }
});

test('human preset motion is strict, optional and exclusive with explicit poses, never retained as payload',()=>{
 for(const action of actions){
  for(const motion of [{},motionDefaults])for(const preset of [undefined,'walk','run','listen']){
   const patch={motion,speech:{cues:[{startMs:0,endMs:100,shape:'rest'}]}};if(preset!==undefined)patch.preset=preset;
   const r=request(patch);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.8'},action,r),/4\.8\.0/);
  }
  for(const patch of [{motion:null},{motion:[]},{motion:{unknown:true}},{motion:{speed:1},poses:[{frame:0}]},{motion:{},poses:[{frame:0}]}])
   assert.throws(()=>contract.nativeStudioParameters(action,request(patch)));
  const retained={...programme,motion:motionDefaults};
  assert.throws(()=>contract.nativeStudioParameters(action,objectRequest({type:'update_object',objectId:'actor_rig',patch:{human:retained}})));
 }
});

test('human motion metadata matches native defaults and explains slope units and retained exact poses',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;assert.deepEqual(metadata.motionDefaults,motionDefaults);assert.deepEqual(metadata.motionLimits,motionLimits);
 class JSONObject {put(key,value){this[key]=value;return this;}}
 const source=native('SketchHumanPresets'),expression=source.match(/static JSONObject motionDefaults\(\)throws Exception\s*\{\s*return ([\s\S]*?);\s*\}/)[1];
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${expression})`,{JSONObject}))),motionDefaults);
 assert.match(metadata.motionControls,/groundSlope.*world.Y.*world.Z/);assert.match(metadata.motionControls,/cannot.*explicit poses/);
 assert.match(metadata.motionControls,/editable poses.*no.*motion.*payload/);
 assert.match(worker,/motion.*travelScale.*groundSlope.*armSwing/);
});

test('human constructor accepts all native presets, sparse poses and optional existing speech',()=>{
 const sources=[undefined,{cues:[{startMs:0,endMs:500,shape:'closed'}]},{generated:{requestId:'master',lineIndex:0,shotStartMs:65000}},
  {prediction:{startMs:500,segments:[{phonemes:'m',startSample:0,sampleCount:1800,modelDurationUnits:[1,1,1],unitsPerSecond:40,forcedAlignmentVerified:false}]}}];
 for(const action of actions)for(const preset of [undefined,...presets])for(const speech of sources){
  const patch={settings:defaults,poses:[{frame:0,nearHandX:58},{frame:1199}]};if(preset!==undefined)patch.preset=preset;if(speech!==undefined)patch.speech=speech;
  const r=request(patch);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
 }
 for(const action of actions)assert.deepEqual(contract.nativeStudioParameters(action,request()),request());
});

test('human identities, settings, preset and pose envelope reject malformed or unknown fields',()=>{
 for(const action of actions){
  const r=request({actorId:'a'.repeat(20),layerId:'l'.repeat(96)});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const patch of [{actorId:undefined},{layerId:undefined},{settings:undefined},{actorId:''},{actorId:'a'.repeat(21)},{actorId:'../actor'},
   {settings:null},{settings:[]},{settings:{unknown:true}},{preset:'dance'},{preset:null},{handId:'hand'},{url:'https://invalid.test/actor'},
   {poses:[]},{poses:null},{poses:[{frame:1}]},{poses:[{frame:0},{frame:1200}]},{poses:[{frame:0},{frame:0}]},
   {poses:[{frame:0},{frame:30},{frame:20}]},{poses:[{frame:.5}]},{poses:[{frame:0,unknown:0}]},
   {poses:Array.from({length:241},(_,frame)=>({frame}))},{speech:{generated:{requestId:'master',lineIndex:0,shotStartMs:600001}}}])
   assert.throws(()=>contract.nativeStudioParameters(action,request(patch)),JSON.stringify(patch));
  const poses=Array.from({length:240},(_,frame)=>({frame}));assert.deepEqual(contract.nativeStudioParameters(action,request({poses})),request({poses}));
 }
});

test('human settings match native views, anatomy choices, colors and numeric bounds',()=>{
 for(const action of actions){
  for(const [key,values] of Object.entries({view:['front','left','right','back','high','low'],build:['slender','broad'],hairStyle:['short','long']})){
   for(const value of values){const r=request({settings:{[key]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
   for(const value of ['unknown','',null,0,true])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[key]:value}})));
  }
  for(const key of ['skinColor','hairColor','coatColor','trouserColor']){
   for(const value of ['#00000000','#FfAa9876']){const r=request({settings:{[key]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
   for(const value of ['red','#FFFFFF',42,null])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[key]:value}})));
  }
  for(const [key,min,max] of [['x',0,1],['floorY',0,1],['size',.1,2]]){
   for(const value of [min,max]){const r=request({settings:{[key]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
   for(const value of [min-.001,max+.001,NaN,Infinity,'0.5',null,true])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[key]:value}})));
  }
 }
});

for(const [channel,[min,max]] of Object.entries(limits))test(`human pose ${channel} follows Figure.validatePose bounds`,()=>{
 for(const action of actions){
  const r=request({poses:[{frame:0,[channel]:min},{frame:1199,[channel]:max}]});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0',null,true])assert.throws(()=>contract.nativeStudioParameters(action,request({poses:[{frame:0,[channel]:value}]})));
 }
});

for(const [channel,[min,max]] of Object.entries(limits))test(`human poseOverrides ${channel} follows Figure.validatePose bounds`,()=>{
 for(const action of actions){
  for(const value of [min,max]){
   const r=request({poseOverrides:{[channel]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0',null,true])
   assert.throws(()=>contract.nativeStudioParameters(action,request({poseOverrides:{[channel]:value}})));
 }
});

test('human poseOverrides combine with preset motion and speech, exclude explicit poses and gate 4.8.0',()=>{
 for(const action of actions)for(const poseOverrides of [{},{nearHandX:58,nearHandY:-130,nearWrist:20,handOpen:.2,gazeX:.5,brow:-.2}]){
  for(const motion of [undefined,{},motionDefaults])for(const speech of [undefined,{cues:[{startMs:0,endMs:100,shape:'closed'}]},{generated:{requestId:'master',lineIndex:0,shotStartMs:65000}}]){
   const patch={preset:'walk',poseOverrides};if(motion!==undefined)patch.motion=motion;if(speech!==undefined)patch.speech=speech;
   const r=request(patch);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.8'},action,r),/4\.8\.0/);
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
  }
  for(const patch of [{poses:[{frame:0}],poseOverrides},{poses:[{frame:0}],motion:{},poseOverrides}])
   assert.throws(()=>contract.nativeStudioParameters(action,request(patch)));
 }
});

test('human poseOverrides accept only a channel subset with no pose timing or retained author data',()=>{
 for(const action of actions){
  const allChannels=Object.fromEntries(Object.entries(limits).map(([channel,[min]])=>[channel,min]));
  const r=request({poseOverrides:allChannels});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const poseOverrides of [null,[],true,0,'{}',{frame:0},{interpolation:'linear'},{curve:[0,0,1,1]},{unknown:0},{nearHandX:0,frame:0}])
   assert.throws(()=>contract.nativeStudioParameters(action,request({poseOverrides})),JSON.stringify(poseOverrides));
  for(const op of humanObjectOperations({...programme,poseOverrides:{handOpen:.2}}))
   assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
  assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{poseOverrides:{}}})));
 }
});

test('human poseOverrides metadata explains constant merge, compatibility and shared manual controls',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;
 assert.match(metadata.poseOverrideControls,/optional.*subset.*35.*channels/i);
 assert.match(metadata.poseOverrideControls,/constant.*over.*generated.*preset.*poses/i);
 assert.match(metadata.poseOverrideControls,/cannot combine.*explicit poses/);
 assert.match(metadata.poseOverrideControls,/motion.*speech/);
 assert.match(metadata.poseOverrideControls,/frame.*interpolation.*curve/);
 assert.match(metadata.poseOverrideControls,/retained.*actual.*poses/);
 assert.match(metadata.manualControls,/Pose overrides.*JSON/i);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 assert.match(detail,/poseOverrides.*35.*channels/);assert.match(detail,/poseOverrides.*4\.8\.0/);
});

test('human poses preserve standard easing and require Bezier controls only with bezier',()=>{
 for(const interpolation of [undefined,'linear','hold','smooth','ease_in','ease_out','ease_in_out','bezier']){
  const pose={frame:0};if(interpolation!==undefined)pose.interpolation=interpolation;if(interpolation==='bezier')pose.curve=[.42,0,.58,1];
  const r=request({poses:[pose]});assert.deepEqual(contract.nativeStudioParameters('sketch_edit',r),r);
 }
 for(const patch of [{interpolation:'bezier'},{curve:[0,0,1,1]},{interpolation:'hold',curve:[0,0,1,1]},
  {interpolation:'ease'},{interpolation:'bezier',curve:[0,0,1]},{interpolation:'bezier',curve:[-1,0,1,1]}])
  assert.throws(()=>contract.nativeStudioParameters('sketch_edit',request({poses:[{frame:0,...patch}]})));
});

test('human speech uses the full 40-second native clock while older mouth routes keep their bounds',()=>{
 for(const action of actions)for(const speech of [
  {cues:[{startMs:39000,endMs:40000,shape:'closed'}]},
  {prediction:{startMs:39000,segments:[{phonemes:'m',startSample:0,sampleCount:1800,modelDurationUnits:[1,1,1],unitsPerSecond:40,forcedAlignmentVerified:false}]}}
 ]){
  const r=request({speech});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  const mouth={...r,operations:[{type:'add_mouth_animation',layerId:'layer_1',mouthId:'mouth',settings:{},speech}]};
  assert.throws(()=>contract.nativeStudioParameters(action,mouth));
 }
 for(const speech of [{cues:[{startMs:39999,endMs:40001,shape:'open'}]},
  {prediction:{startMs:40001,segments:[{phonemes:'m',startSample:0,sampleCount:1800,modelDurationUnits:[1,1,1],unitsPerSecond:40,forcedAlignmentVerified:false}]}},
  {cues:[{startMs:0,endMs:100,shape:'rest'}],generated:{requestId:'master',lineIndex:0}}])
  assert.throws(()=>contract.nativeStudioParameters('sketch_edit',request({speech})));
});

const programme={settings:legacyDefaults,poses:[{frame:0},{frame:959,bodyLean:5}],durationFrames:960,fps:24};
const object={id:'actor_rig',kind:'human',bounds:{x:.2,y:.1,width:.6,height:.8},human:programme};
const transform={x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1};
const complete={...object,frame:0,exposure:960,color:'#FFD6A078',width:.00065,fill:true,transform,keys:[]};
const objectRequest=op=>({...request(),operations:[op]});
const humanObjectOperations=human=>[
 {type:'add_object',layerId:'layer_1',object:{...object,human}},
 {type:'add_layer',layer:{id:'actors',objects:[{...complete,human}]}},
 {type:'update_object',objectId:'actor_rig',patch:{human}}
];
const humanParts=['all','without_near_hand','without_far_hand','near_hand','far_hand'];

test('retained human sourceFrameOffset round-trips through all object routes without altering renderFrame or native gates',()=>{
 for(const action of actions)for(const sourceFrameOffset of [-1200,-1,0,1,1200])for(const renderFrame of [undefined,0,959]){
  const human={...programme,sourceFrameOffset};if(renderFrame!==undefined)human.renderFrame=renderFrame;
  for(const op of humanObjectOperations(human)){
   const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.8'},action,r),/4\.8\.0/);
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
  }
 }
});

test('human sourceFrameOffset is a strict optional retained integer, never a constructor argument',()=>{
 for(const action of actions){
  for(const sourceFrameOffset of [-1201,1201,.5,-.5,NaN,Infinity,-Infinity,'0',null,true])for(const op of humanObjectOperations({...programme,sourceFrameOffset}))
   assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
  for(const patch of [{sourceFrameOffset:0},{settings:{sourceFrameOffset:0}}])assert.throws(()=>contract.nativeStudioParameters(action,request(patch)));
  for(const op of humanObjectOperations(programme)){const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
 }
 const source=native('SketchHumanAnimation');assert.match(source,/integer\(h,"sourceFrameOffset",-1200,1200\)/);
 assert.match(source,/has\("renderFrame"\)\?h\.getInt\("renderFrame"\):.*optInt\("sourceFrameOffset",0\)/);
 assert.match(contract.HUMAN_ANIMATION_CAPABILITIES.sourceFrameOffset,/retained.*-1200.*1200.*renderFrame.*precedence/i);
});

for(const parts of humanParts)test(`retained human parts ${parts} survives adds, layer imports and replacements with native gating`,()=>{
 for(const action of actions)for(const op of humanObjectOperations({...programme,parts})){
  const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.8'},action,r),/4\.8\.0/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,r));
 }
});

test('human parts remain optional and strict inside retained payloads only; constructor author data stays rejected',()=>{
 for(const action of actions){
  for(const op of humanObjectOperations(programme)){
   const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
  for(const parts of ['ALL','near','without_both_hands','',null,0,true,[],{}])for(const op of humanObjectOperations({...programme,parts}))
   assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)),JSON.stringify(parts));
  for(const patch of [{parts:'near_hand'},{settings:{parts:'all'}},{poseOverrides:[]}])
   assert.throws(()=>contract.nativeStudioParameters(action,request(patch)));
  for(const op of humanObjectOperations({...programme,poseOverrides:{}}))
   assert.throws(()=>contract.nativeStudioParameters(action,objectRequest(op)));
  assert.throws(()=>contract.nativeStudioParameters(action,objectRequest({type:'add_object',layerId:'layer_1',object:{...object,parts:'all'}})));
 }
});

test('human parts metadata matches native rendering choices and preserves the shared sixteen actor cap',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;
 assert.deepEqual(metadata.parts,humanParts);assert.equal(metadata.defaultParts,'all');assert.equal(metadata.maximumRetainedActors,16);
 const source=native('SketchHumanAnimation');
 assert.ok(source.includes('Set.of('+humanParts.map(p=>JSON.stringify(p)).join(',')+')'));
 assert.match(source,/optString\("parts","all"\)/);
 assert.match(native('SketchDocument'),/proceduralActors>16/);
 assert.match(metadata.retainedObject,/optional parts/);assert.match(metadata.limits,/16.*clones/);
});

test('retained human adds, complete layer objects and human patches preserve clocks and resolved speech',()=>{
 for(const action of actions)for(const op of [
  {type:'add_object',layerId:'layer_1',object},
  {type:'add_layer',layer:{id:'actors',objects:[complete]}},
  {type:'update_object',objectId:'actor_rig',patch:{human:{...programme,speech:{transitionMs:60,cues:[{startMs:39000,endMs:40000,shape:'open',strength:1}]}}}}
 ]){const r=objectRequest(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
 for(const [durationFrames,fps] of [[24,24],[960,24],[1200,30],[1200,60]]){
  const r=objectRequest({type:'add_object',layerId:'layer_1',object:{...object,human:{...programme,durationFrames,fps,poses:[{frame:0}],renderFrame:durationFrames-1}}});
  assert.deepEqual(contract.nativeStudioParameters('sketch_edit',r),r);
 }
 const dense={...programme,durationFrames:240,fps:60,poses:Array.from({length:240},(_,frame)=>({frame}))};
 const r=objectRequest({type:'update_object',objectId:'actor_rig',patch:{human:dense}});assert.deepEqual(contract.nativeStudioParameters('sketch_edit',r),r);
});

test('retained human payload rejects invalid clocks, incomplete settings and unresolved speech',()=>{
 for(const patch of [{durationFrames:23},{durationFrames:1201},{durationFrames:961,fps:24},{fps:25},{renderFrame:960},
  {poses:[{frame:0},{frame:960}]},{settings:{}},{speech:{generated:{requestId:'master',lineIndex:0}}},
  {speech:{prediction:{startMs:0,segments:[]}}},{speech:{cues:[{startMs:39999,endMs:40001,shape:'open'}]}},
  {speech:{cues:[{startMs:10,endMs:20,shape:'open'},{startMs:19,endMs:30,shape:'rest'}]}},{unknown:true}])
  assert.throws(()=>contract.nativeStudioParameters('sketch_edit',objectRequest({type:'update_object',objectId:'actor_rig',patch:{human:{...programme,...patch}}})),JSON.stringify(patch));
 const bad={...complete};delete bad.transform;
 assert.throws(()=>contract.nativeStudioParameters('sketch_edit',objectRequest({type:'add_layer',layer:{id:'actors',objects:[bad]}})));
});

test('human constructors, retained adds, patches and layer imports gate native 4.8.0 in mixed batches',()=>{
 const face={type:'add_face_animation',layerId:'layer_1',faceId:'face',settings:{}};
 for(const action of actions)for(const op of [actor,{type:'add_object',layerId:'layer_1',object},
  {type:'update_object',objectId:'actor_rig',patch:{human:programme}},{type:'add_layer',layer:{id:'actors',objects:[complete]}}])
  for(const operations of [[op],[op,face],[face,op]]){
   const r={...request(),operations};
   for(const device of [undefined,{}, {appVersion:'unknown'},{appVersion:'4.7.8'}])assert.throws(()=>contract.requireStudioVersion(device,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*human.*4\.8\.0/);
   for(const appVersion of ['4.8.0','4.8.1','4.9.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,r));
  }
});

test('human relay rejects invalid inputs and older APKs before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='u'.repeat(43);
  const register=appVersion=>relay.appRegister('human-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.8');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request()),/4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request({poses:[{frame:0,mouthWide:1.1}]})));assert.equal(storage.writes(),before);
  for(const patch of [{poseOverrides:{handOpen:1.1}},{poseOverrides:{frame:0}},{poseOverrides:{},poses:[{frame:0}]}]){
   await assert.rejects(relay.appEnqueueV3(key,action,request(patch)));assert.equal(storage.writes(),before);
  }
  const r=request({preset:'explain',motion:{strength:.8},poseOverrides:{nearWrist:25,handOpen:.2,gazeX:.5},speech:{generated:{requestId:'master',lineIndex:0,shotStartMs:65000}}});
  assert.deepEqual(JSON.parse(JSON.stringify((await relay.appEnqueueV3(key,action,r)).parameters)),r);
 }
});

test('human metadata matches native defaults, channel order, presets and source capability literals',()=>{
 const metadata=contract.HUMAN_ANIMATION_CAPABILITIES;assert.ok(metadata);
 assert.equal(metadata.minimumNativeVersion,'4.8.0');assert.equal(metadata.operation,actor.type);assert.deepEqual(metadata.settings,defaults);
 assert.deepEqual(metadata.channels,channels);assert.deepEqual(metadata.channelLimits,limits);assert.deepEqual(metadata.presets,presets);
 const figure=native('SketchHumanFigure'),presetSource=native('SketchHumanPresets');
 assert.deepEqual(JSON.parse('['+figure.match(/POSE_CHANNELS=\{([^}]+)\}/)[1]+']'),channels);
 assert.deepEqual(JSON.parse('['+presetSource.match(/NAMES=\{([^}]+)\}/)[1]+']'),presets);
 class JSONObject {put(key,value){this[key]=value;return this;}}
 class JSONArray extends Array {constructor(values){super();this.push(...values);}}
 const defaultsExpression=figure.match(/static JSONObject defaults\(\)throws Exception \{\s*return ([\s\S]*?);\s*\}/)[1];
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${defaultsExpression})`,{JSONObject}))),defaults);
 const cameraLiteral=native('SketchHumanCameraGuide').match(/return new JSONObject\(("(?:\\.|[^"\\])*")\);/)[1];
 const SketchHumanCameraGuide={capabilities:()=>JSON.parse(JSON.parse(cameraLiteral))};
 const java=native('SketchCapabilities');assert.match(java,/\.put\("humanAnimation",humanAnimation\(\)\)/);
 const body=java.match(/public static JSONObject humanAnimation\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)?.[1];assert.ok(body);
 const expression=body.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]');
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${expression})`,{JSONObject,JSONArray,SketchHumanCameraGuide}))),metadata);
 assert.equal(metadata.genuine3D,false);assert.equal(metadata.creativeQualityCertified,false);assert.equal(metadata.phoneExecutionVerified,false);
 assert.match(metadata.limits,/24\.\.1200 frames.*40 seconds/);assert.match(metadata.geometry,/projected2D/);
 assert.match(metadata.bounds,/camera.*world.*crop.*base artwork.*canvas/);
 assert.equal(metadata.maximumPoses,240);assert.match(metadata.poseControls,/1\.\.240/);
 assert.match(metadata.locomotion,/walk\/run.*every actual output frame.*240.*reject.*resampl/);
 assert.match(metadata.manualControls,/Human acting.*all 35 pose channels.*native preview.*generated speech/);
});

test('ordinary ChatGPT discovers human metadata and explicit app_status version guidance',async()=>{
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);let handler;
 vm.runInNewContext(worker.slice(start,end),{s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3:true,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[],
  CHARACTER_PERFORMANCE_CAPABILITIES:contract.CHARACTER_PERFORMANCE_CAPABILITIES,HAND_ANIMATION_CAPABILITIES:contract.HAND_ANIMATION_CAPABILITIES,HUMAN_ANIMATION_CAPABILITIES:contract.HUMAN_ANIMATION_CAPABILITIES});
 assert.ok((await handler()).humanAnimation);assert.deepEqual((await handler()).humanAnimation,contract.HUMAN_ANIMATION_CAPABILITIES);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];for(const pattern of [/add_human_animation/,/sketch_capabilities\.humanAnimation/,/ordinary ChatGPT.*app_status/,/4\.8\.0/])assert.match(detail,pattern);
});
