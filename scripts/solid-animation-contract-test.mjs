import test from 'node:test';
import './character-animation-contract-test.mjs';
import './hand-animation-contract-test.mjs';
import './hand-cel-contract-test.mjs';
import './speech-rebase-contract-test.mjs';
import './human-animation-contract-test.mjs';
import './animation-book-placement-contract-test.mjs';
import './story-stage-prop-contract-test.mjs';
import './grip-link-contract-test.mjs';
import './story-actions-contract-test.mjs';
import './feature-release-contract-test.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';
const program={size:[140,140,140],camera:{focal:900,distance:1000,height:300,horizon:.2},keys:[{frame:0,x:0,elevation:0,depth:0,yaw:0},{frame:60,x:80,elevation:0,depth:150,yaw:360}]};
const request={sketchId:'original',operationId:'solid_1',expectedRevision:1,operations:[{type:'add_solid',layerId:'layer_1',solidId:'crate',program}]};
const mouth={type:'add_mouth_animation',layerId:'layer_1',mouthId:'mouth',settings:{}};
const mouthRequest=(patch={})=>({...request,operationId:'mouth_1',operations:[{...mouth,...patch}]});
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const face={type:'add_face_animation',layerId:'layer_1',faceId:'face',settings:{}};
const faceRequest=(patch={})=>({...request,operationId:'face_1',operations:[{...face,...patch}]});

test('timed speech uses strict native cues through both face and mouth routes',()=>{
 const speech={transitionMs:60,cues:[{startMs:500,endMs:750,shape:'closed'},{startMs:750,endMs:1000,shape:'round',strength:.8}]};
 for(const build of [faceRequest,mouthRequest])for(const action of ['sketch_edit','studio_sketch_edit']){
  const r=build({speech});assert.deepEqual(nativeStudioParameters(action,r),r);
  assert.throws(()=>requireStudioVersion({appVersion:'4.7.6'},action,r),/4\.7\.7/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.7'},action,r));
  for(const bad of [{...speech,cues:[]},{...speech,transitionMs:401},{...speech,cues:[{startMs:500,endMs:500,shape:'rest'}]},
   {...speech,cues:[{startMs:500,endMs:800,shape:'open'},{startMs:700,endMs:900,shape:'wide'}]},
   {...speech,cues:[{startMs:0,endMs:500,shape:'guess'}]}, {...speech,cues:[{startMs:0,endMs:500,shape:'rest',strength:NaN}]},
   {...speech,url:'https://invalid.test/audio.wav'}])assert.throws(()=>nativeStudioParameters(action,build({speech:bad})));
  assert.throws(()=>nativeStudioParameters(action,build({speech,poses:[{frame:0,open:0}]})));
 }
});

test('speech duration predictions retain the actual token and PCM clocks',()=>{
 const segment={phonemes:'mˈA bˈO',startSample:0,sampleCount:24000,modelDurationUnits:[4,4,2,6,4,4,2,10,4],unitsPerSecond:40,forcedAlignmentVerified:false};
 const speech={transitionMs:60,prediction:{startMs:500,segments:[segment]}};
 for(const build of [faceRequest,mouthRequest]){
  const r=build({speech});assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
  for(const patch of [{sampleCount:23999},{modelDurationUnits:[4,4,2]},{forcedAlignmentVerified:true},{unitsPerSecond:50},{phonemes:'m😃A bˈO'}])
   assert.throws(()=>nativeStudioParameters('sketch_edit',build({speech:{...speech,prediction:{...speech.prediction,segments:[{...segment,...patch}]}}})));
  assert.throws(()=>nativeStudioParameters('sketch_edit',build({speech:{...speech,cues:[{startMs:0,endMs:100,shape:'rest'}]}})));
 }
});

test('speech may select an imported generated line by identity without exposing paths',()=>{
 for(const build of [faceRequest,mouthRequest])for(const action of ['sketch_edit','studio_sketch_edit']){
  const speech={transitionMs:60,generated:{requestId:'original_speech',lineIndex:0}},r=build({speech});
  assert.deepEqual(nativeStudioParameters(action,r),r);
  for(const generated of [{requestId:'../speech',lineIndex:0},{requestId:'original',lineIndex:-1},{requestId:'original',lineIndex:32},{requestId:'original',lineIndex:0,path:'/private/audio.wav'}])
   assert.throws(()=>nativeStudioParameters(action,build({speech:{generated}})));
  assert.throws(()=>nativeStudioParameters(action,build({speech:{...speech,cues:[{startMs:0,endMs:10,shape:'rest'}]}})));
 }
});

test('source relay gates speech by APK version and validates before any queue write',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);
 vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of ['sketch_edit','studio_sketch_edit'])for(const build of [faceRequest,mouthRequest]){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='s'.repeat(43),deviceId='speech-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  const speech={generated:{requestId:'original_speech',lineIndex:0}},r=build({speech});
  await register('4.7.6');let before=storage.writes();
  await assert.rejects(relay.appEnqueueV3(key,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.7\.7/);assert.equal(storage.writes(),before);
  await register('4.7.7');before=storage.writes();
  for(const bad of [{...speech,transitionMs:201},{...speech,cues:[{startMs:0,endMs:10,shape:'rest'}]}]){
   await assert.rejects(relay.appEnqueueV3(key,action,build({speech:bad})));assert.equal(storage.writes(),before);
  }
  const admitted=await relay.appEnqueueV3(key,action,r);assert.deepEqual(JSON.parse(JSON.stringify(admitted.parameters)),r);
  assert.equal((await queueBindings.readCommandQueue(storage,'app-v3-cl:'+deviceId)).length,1);
 }
});

test('face animation accepts strict optional settings and presets through both edit names',()=>{
 const settings={view:'left',shape:'angular',eyeStyle:'almond',mouthStyle:'fang',centerX:.5,centerY:.5,width:.4,height:.65,jawWidth:.7,noseWidth:.15,noseLength:.15,eyeSize:1,mouthSize:1,skinColor:'#FFCC9977',hairColor:'#FF332211',irisColor:'#ff668844',lipColor:'#FFAB4567',ink:'#FF211B24',outlinePx:3,tilt:-10,hair:true};
 for(const action of ['sketch_edit','studio_sketch_edit']){
  assert.deepEqual(nativeStudioParameters(action,faceRequest()),faceRequest());
  for(const preset of ['reaction','playful','chew']){
   const r=faceRequest({settings,preset});
   assert.deepEqual(nativeStudioParameters(action,r),r);
  }
 }
});

test('face animation requires safe identities and rejects unknown fields at every level',()=>{
 for(const patch of [{layerId:undefined},{faceId:undefined},{settings:undefined},{settings:null},{settings:[]},{faceId:''},{faceId:'f'.repeat(29)},{faceId:'../face'},{layerId:'with space'},{layerId:'l'.repeat(97)},{preset:'speech'},{preset:'articulation'},{preset:null},{url:'https://example.test/face.mp4'},{audioAssetId:'audio'},{mouthId:'mouth'},{settings:{unknown:true}},{poses:[{frame:0,phoneme:'aa'}]},{poses:[{frame:0,unknown:true}]}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',faceRequest(patch)),JSON.stringify(patch));
 const r=faceRequest({faceId:'f'.repeat(28),layerId:'l'.repeat(96)});
 assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
});

test('face settings accept only the six authored views and specified anatomy styles',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){
  assert.deepEqual(nativeStudioParameters(action,faceRequest()).operations[0].settings,{});
  for(const [field,values] of [['view',['front','left','right','back','high','low']],['shape',['oval','round','angular']],['eyeStyle',['round','almond','slit']],['mouthStyle',['soft','cartoon','fang']]]){
   for(const value of values){
    const r=faceRequest({settings:{[field]:value}});
    assert.deepEqual(nativeStudioParameters(action,r),r);
   }
   for(const value of ['unknown','',null,0,true,[],{}])
    assert.throws(()=>nativeStudioParameters(action,faceRequest({settings:{[field]:value}})));
  }
  for(const settings of [{view:'rear'},{view:'Front'},{view:' front'},{shape:'soft'},{eyeStyle:'oval'},{mouthStyle:'slit'},{style:'round'}])
   assert.throws(()=>nativeStudioParameters(action,faceRequest({settings})));
 }
});

for(const [field,min,max] of [['centerX',.15,.85],['centerY',.2,.75],['width',.28,.5],['height',.45,.85],['jawWidth',.45,.9],['noseWidth',.08,.25],['noseLength',.08,.22],['eyeSize',.8,1.2],['mouthSize',.8,1.2],['outlinePx',1,6],['tilt',-20,20]]){
 test(`face setting ${field} accepts inclusive bounds and rejects invalid numbers`,()=>{
  for(const value of [min,max]){
   const r=faceRequest({settings:{[field]:value}});
   assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
  }
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null])
   assert.throws(()=>nativeStudioParameters('sketch_edit',faceRequest({settings:{[field]:value}})),`${field}: ${value}`);
 });
}

test('face colors follow native color bounds and hair is strictly boolean',()=>{
 for(const field of ['skinColor','hairColor','irisColor','lipColor','ink']){
  for(const value of ['#00000000','#FfAa9876']){
   const r=faceRequest({settings:{[field]:value}});
   assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
  }
  for(const value of ['red','#FFFFFF','#GG000000',42,null])
   assert.throws(()=>nativeStudioParameters('sketch_edit',faceRequest({settings:{[field]:value}})));
 }
 for(const hair of [true,false]){
  const r=faceRequest({settings:{hair}});
  assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 }
 for(const hair of [0,1,'true',null,{}])assert.throws(()=>nativeStudioParameters('sketch_edit',faceRequest({settings:{hair}})));
});

for(const [field,min,max] of [['gazeX',-1,1],['gazeY',-1,1],['leftOpen',0,1],['rightOpen',0,1],['pupilScale',.5,1.5],['vergence',0,.5],['open',0,1],['wide',.5,1.5],['smile',-1,1],['jawX',-1,1],['tongue',0,1],['upperTeeth',0,1],['lowerTeeth',0,1],['roundness',0,1],['browRaise',-1,1],['browTilt',-1,1]]){
 test(`face pose channel ${field} is optional and strictly bounded`,()=>{
  const r=faceRequest({poses:[{frame:0,[field]:min},{frame:12,[field]:max},{frame:239}]});
  assert.deepEqual(nativeStudioParameters('studio_sketch_edit',r),r);
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null])
   assert.throws(()=>nativeStudioParameters('sketch_edit',faceRequest({poses:[{frame:0,[field]:value}]})),`${field}: ${value}`);
 });
}

test('face pose lists contain 1..240 integer frames starting at zero and strictly increasing',()=>{
 for(const poses of [[{frame:0}],Array.from({length:240},(_,frame)=>({frame}))]){
  const r=faceRequest({poses});
  assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 }
 for(const poses of [[],null,[{}],[{frame:1}],[{frame:-1}],[{frame:.5}],[{frame:'0'}],[{frame:0},{frame:240}],[{frame:0},{frame:0}],[{frame:0},{frame:20},{frame:10}],Array.from({length:241},(_,frame)=>({frame}))])
  assert.throws(()=>nativeStudioParameters('sketch_edit',faceRequest({poses})),JSON.stringify(poses));
});

test('face poses preserve combined sparse eye mouth and brow channels with shared easing',()=>{
 for(const interpolation of [undefined,'linear','hold','smooth','ease_in','ease_out','ease_in_out','bezier']){
  const pose={frame:0,gazeX:.2,gazeY:-.1,leftOpen:.9,rightOpen:.4,pupilScale:1.1,vergence:.2,open:.3,wide:1.2,smile:-.2,jawX:.1,tongue:.4,upperTeeth:.5,lowerTeeth:.2,roundness:.7,browRaise:.5,browTilt:-.4};
  if(interpolation!==undefined)pose.interpolation=interpolation;
  if(interpolation==='bezier')pose.curve=[.42,0,.58,1];
  const r=faceRequest({preset:'reaction',poses:[pose,{frame:20,browRaise:-1,tongue:1},{frame:239}]});
  assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 }
 for(const patch of [{interpolation:'bezier'},{curve:[0,0,1,1]},{interpolation:'hold',curve:[0,0,1,1]},{interpolation:'ease'},{interpolation:'bezier',curve:[0,0,1]},{interpolation:'bezier',curve:[-1,0,1,1]},{interpolation:'bezier',curve:[0,0,1,1.01]},{easing:'linear'}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',faceRequest({poses:[{frame:0,...patch}]})));
});

test('face animation requires native 4.7.6 on canonical alias and mixed edit routes',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){
  for(const device of [undefined,{}, {appVersion:''},{appVersion:'unknown'},{appVersion:'4.7.5'},{appVersion:'4.7.4'}])
   assert.throws(()=>requireStudioVersion(device,action,faceRequest()),/NATIVE_APP_UPGRADE_REQUIRED:.*face.*4\.7\.6/);
  for(const appVersion of ['4.7.6','4.7.6-beta','4.7.10','4.8.0','5.0.0'])
   assert.doesNotThrow(()=>requireStudioVersion({appVersion},action,faceRequest()));
  const eyes={type:'add_eye_animation',layerId:'layer_1',eyeId:'eyes',settings:{}};
  for(const operations of [[eyes,face],[face,eyes],[mouth,face],[face,mouth]]){
   const r={...request,operations};
   assert.throws(()=>requireStudioVersion({appVersion:'4.7.5'},action,r),/4\.7\.6/);
   assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.6'},action,r));
  }
 }
});

test('real native relay rejects older APKs and malformed face operations before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);
 vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of ['sketch_edit','studio_sketch_edit']){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='f'.repeat(43),deviceId='face-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.5');
  let before=storage.writes();
  await assert.rejects(relay.appEnqueueV3(key,action,faceRequest()),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.7\.6/);
  assert.equal(storage.writes(),before);
  await register('4.7.6');
  before=storage.writes();
  for(const patch of [{settings:{view:'rear'}},{settings:{jawWidth:1}},{poses:[{frame:0,browRaise:2}]},{settings:{unknown:true}},{poses:[{frame:1}]}]){
   await assert.rejects(relay.appEnqueueV3(key,action,faceRequest(patch)));
   assert.equal(storage.writes(),before);
  }
  const r=faceRequest({settings:{view:'back'},preset:'playful',poses:[{frame:0,open:.5,browTilt:-1}]});
  const admitted=await relay.appEnqueueV3(key,action,r);
  assert.deepEqual(JSON.parse(JSON.stringify(admitted.parameters)),r);
  assert.equal(admitted.seq,1);
  assert.equal((await queueBindings.readCommandQueue(storage,'app-v3-cl:'+deviceId)).length,1);
 }
});

test('MCP face description advertises authored 2D anatomy occlusion and bounded evidence',()=>{
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 assert.match(detail,/Native 4\.7\.6 adds add_face_animation/);
 assert.match(detail,/sketch_capabilities\.faceAnimation/);
 assert.match(detail,/reaction\/playful\/chew/);
 assert.match(detail,/original authored 2D view-specific anatomy and occlusion/);
 assert.match(detail,/not true 3D or proof of audio speaking/);
});

test('mouth animation accepts strict settings and optional articulated presets through both edit names',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){
  const minimal=mouthRequest();
  assert.deepEqual(nativeStudioParameters(action,minimal),minimal);
  for(const style of ['soft','cartoon','fang'])for(const preset of ['articulation','tongue','chew']){
   const r=mouthRequest({settings:{style,centerX:.5,centerY:.55,width:.35,height:.25,outlinePx:3,tilt:-10,lipColor:'#FFAB4567',cavityColor:'#FF220011',tongueColor:'#ffed7189',toothColor:'#FFFFFFFF',ink:'#FF211B24'},preset});
   assert.deepEqual(nativeStudioParameters(action,r),r);
  }
 }
});

test('mouth animation requires safe bounded identities and a strict settings object',()=>{
 for(const patch of [{layerId:undefined},{mouthId:undefined},{settings:undefined},{settings:null},{settings:[]},{mouthId:''},{mouthId:'a'.repeat(37)},{mouthId:'../mouth'},{layerId:'with space'},{preset:'speech'},{preset:'phoneme'},{preset:null},{url:'https://example.test/mouth.mp4'},{audioAssetId:'audio'},{settings:{unknown:true}},{settings:{style:'slit'}},{poses:[{frame:0,phoneme:'aa'}]}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',mouthRequest(patch)),JSON.stringify(patch));
 const r=mouthRequest({mouthId:'a'.repeat(36),layerId:'l'.repeat(96)});
 assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 assert.throws(()=>nativeStudioParameters('sketch_edit',mouthRequest({layerId:'l'.repeat(97)})));
});

test('mouth settings accept six strict views and preserve omission for the native front default',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){
  const omitted=mouthRequest();
  assert.deepEqual(nativeStudioParameters(action,omitted),omitted);
  assert.equal(Object.hasOwn(nativeStudioParameters(action,omitted).operations[0].settings,'view'),false);
  for(const view of ['front','left','right','back','high','low']){
   const r=mouthRequest({settings:{view}});
   assert.deepEqual(nativeStudioParameters(action,r),r);
   assert.throws(()=>requireStudioVersion({appVersion:'4.7.5'},action,r),/4\.7\.6/);
   assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.6'},action,r));
  }
  for(const view of ['rear','profile','Front',' front','front ','',null,0,true,[],{}])
   assert.throws(()=>nativeStudioParameters(action,mouthRequest({settings:{view}})),JSON.stringify(view));
  for(const patch of [{view:'front'},{settings:{view:'front',unknown:true}},{poses:[{frame:0,view:'front'}]}])
   assert.throws(()=>nativeStudioParameters(action,mouthRequest(patch)));
 }
});

test('MCP mouth description documents six settings views and the native front default',()=>{
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 assert.match(detail,/settings\.view.*front\/left\/right\/back\/high\/low/);
 assert.match(detail,/defaults to front in native/);
});

for(const [field,min,max] of [['centerX',.05,.95],['centerY',.05,.95],['width',.08,.65],['height',.05,.6],['outlinePx',1,8],['tilt',-35,35]]){
 test(`mouth setting ${field} accepts inclusive bounds and rejects invalid numbers`,()=>{
  for(const value of [min,max]){
   const r=mouthRequest({settings:{[field]:value}});
   assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
  }
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null])
   assert.throws(()=>nativeStudioParameters('sketch_edit',mouthRequest({settings:{[field]:value}})),`${field}: ${value}`);
 });
}

test('mouth colors use the normal eight-digit native color contract',()=>{
 for(const field of ['lipColor','cavityColor','tongueColor','toothColor','ink']){
  for(const value of ['#00000000','#FfAa9876']){
   const r=mouthRequest({settings:{[field]:value}});
   assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
  }
  for(const value of ['red','#FFFFFF','#GG000000',42,null])
   assert.throws(()=>nativeStudioParameters('sketch_edit',mouthRequest({settings:{[field]:value}})));
 }
});

for(const [field,min,max] of [['open',0,1],['wide',.5,1.5],['smile',-1,1],['jawX',-1,1],['tongue',0,1],['upperTeeth',0,1],['lowerTeeth',0,1],['roundness',0,1]]){
 test(`mouth pose channel ${field} is optional and strictly bounded`,()=>{
  const poses=[{frame:0,[field]:min},{frame:12,[field]:max},{frame:239}];
  const r=mouthRequest({poses});
  assert.deepEqual(nativeStudioParameters('studio_sketch_edit',r),r);
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null])
   assert.throws(()=>nativeStudioParameters('sketch_edit',mouthRequest({poses:[{frame:0,[field]:value}]})),`${field}: ${value}`);
 });
}

test('mouth pose lists contain 1..240 integer frames starting at zero and strictly increasing',()=>{
 for(const poses of [[{frame:0}],Array.from({length:240},(_,frame)=>({frame}))]){
  const r=mouthRequest({poses});
  assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 }
 for(const poses of [[],null,[{}],[{frame:1}],[{frame:-1}],[{frame:.5}],[{frame:'0'}],[{frame:0},{frame:240}],[{frame:0},{frame:0}],[{frame:0},{frame:20},{frame:10}],Array.from({length:241},(_,frame)=>({frame}))])
  assert.throws(()=>nativeStudioParameters('sketch_edit',mouthRequest({poses})),JSON.stringify(poses));
});

test('mouth poses preserve sparse inherited channels and shared easing semantics',()=>{
 for(const interpolation of [undefined,'linear','hold','smooth','ease_in','ease_out','ease_in_out','bezier']){
  const pose={frame:0,open:.3,wide:1.2,smile:-.2,jawX:.1,tongue:.4,upperTeeth:.5,lowerTeeth:.2,roundness:.7};
  if(interpolation!==undefined)pose.interpolation=interpolation;
  if(interpolation==='bezier')pose.curve=[.42,0,.58,1];
  const r=mouthRequest({preset:'chew',poses:[pose,{frame:20,tongue:1},{frame:239}]});
  assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 }
 for(const patch of [{interpolation:'bezier'},{curve:[0,0,1,1]},{interpolation:'hold',curve:[0,0,1,1]},{interpolation:'ease'},{interpolation:'bezier',curve:[0,0,1]},{interpolation:'bezier',curve:[-1,0,1,1]},{interpolation:'bezier',curve:[0,0,1,1.01]},{easing:'linear'},{extra:true}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',mouthRequest({poses:[{frame:0,...patch}]})));
});

test('mouth animation requires native 4.7.6 on canonical and alias routes without raising the eye minimum',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){
  for(const device of [undefined,{}, {appVersion:''},{appVersion:'unknown'},{appVersion:'4.7.5'},{appVersion:'4.7.4'}])
   assert.throws(()=>requireStudioVersion(device,action,mouthRequest()),/NATIVE_APP_UPGRADE_REQUIRED:.*mouth.*4\.7\.6/);
  for(const appVersion of ['4.7.6','4.7.6-beta','4.7.10','4.8.0','5.0.0'])
   assert.doesNotThrow(()=>requireStudioVersion({appVersion},action,mouthRequest()));
  const eyes={type:'add_eye_animation',layerId:'layer_1',eyeId:'eyes',settings:{}};
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.5'},action,{...request,operations:[eyes]}));
  for(const operations of [[eyes,mouth],[mouth,eyes]])
   assert.throws(()=>requireStudioVersion({appVersion:'4.7.5'},action,{...request,operations}),/4\.7\.6/);
 }
});

test('real native relay rejects older APKs and malformed mouth operations before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);
 vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of ['sketch_edit','studio_sketch_edit']){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='m'.repeat(43),deviceId='mouth-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.5');
  let before=storage.writes();
  await assert.rejects(relay.appEnqueueV3(key,action,mouthRequest()),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.7\.6/);
  assert.equal(storage.writes(),before);
  await register('4.7.6');
  before=storage.writes();
  await assert.rejects(relay.appEnqueueV3(key,action,mouthRequest({poses:[{frame:0,open:2}]})));
  assert.equal(storage.writes(),before);
  const admitted=await relay.appEnqueueV3(key,action,mouthRequest());
  assert.deepEqual(JSON.parse(JSON.stringify(admitted.parameters)),mouthRequest());
  assert.equal(admitted.seq,1);
  assert.equal((await queueBindings.readCommandQueue(storage,'app-v3-cl:'+deviceId)).length,1);
 }
});

test('MCP edit description advertises native mouth capability with no speech recognition claim',()=>{
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 assert.match(detail,/Native 4\.7\.6 adds add_mouth_animation/);
 assert.match(detail,/sketch_capabilities\.mouthAnimation/);
 assert.match(detail,/articulation.*tongue.*chew/);
 assert.match(detail,/not proof of audio speaking/);
});

test('eye animation has strict native pose/control schemas and rejects older APKs before admission',()=>{
 const op={type:'add_eye_animation',layerId:'layer_1',eyeId:'eyes',settings:{style:'almond',irisSize:.95},preset:'look_blink'};
 const r={...request,operations:[op]};assert.deepEqual(nativeStudioParameters('studio_sketch_edit',r),r);
 assert.throws(()=>requireStudioVersion({appVersion:'4.7.4'},'sketch_edit',r),/4\.7\.5/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.5'},'sketch_edit',r));
 for(const patch of [{settings:{url:'https://example.test/reference'}},{settings:{style:'video'}},{poses:[{frame:0,gazeX:2}]},{poses:[{frame:10},{frame:0}]}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...op,...patch}]}));
});
test('original cartoon and character native studies have strict requests and APK gating',()=>{
 for(const study of ['cartoon_jump','walk_to_run']){
  const r={...request,operations:[{type:'add_motion_study',layerId:'layer_1',sceneId:'new_scene',study,settings:{travel:.18,lift:.24}}]};
  assert.deepEqual(nativeStudioParameters('studio_sketch_edit',r),r);
  assert.throws(()=>requireStudioVersion({appVersion:'4.7.3'},'sketch_edit',r),/4\.7\.4/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.4'},'sketch_edit',r));
  for(const patch of [{study:'trace_reference'},{settings:{url:'https://example.test/reference.mp4'}},{settings:{travel:1}},{settings:{lift:0}}])
   assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],...patch}]}));
 }
});
test('MCP accepts native solid construction with bounded authored poses',()=>{
 assert.deepEqual(nativeStudioParameters('studio_sketch_edit',request),request);
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...request,operations:[{...request.operations[0],program:{...program,url:'https://example.test/reference.mp4'}}]}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...request,operations:[{...request.operations[0],program:{...program,keys:[program.keys[1],program.keys[0]]}}]}));
});
test('old APKs are rejected before receiving the new native construction operation',()=>{
 assert.throws(()=>requireStudioVersion({appVersion:'4.7.0'},'sketch_edit',request),/4\.7\.1/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',request));
});
test('depth timing can be authored independently of rotation timing',()=>{
 const p={...program,keys:[{...program.keys[0],depthInterpolation:'smooth'},program.keys[1]]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',{...request,operations:[{...request.operations[0],program:p}]}).operations[0].program,p);
});

test('native flight and impact controls are accepted only by compatible APKs',()=>{
 const p={...program,preserveVolume:true,keys:[{...program.keys[0],flightHeight:200,scaleInterpolation:'smooth'},program.keys[1]]};const r={...request,operations:[{...request.operations[0],program:p}]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',r),/4\.7\.2/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.2'},'sketch_edit',r));
});
test('native sphere and distance-driven rolling are strictly validated and version gated',()=>{
 const p={...program,shape:'sphere',rollingRadius:80};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',r),/4\.7\.2/);
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,shape:'external_mesh'}}]}));assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,rollingRadius:0}}]}));
});

test('ground contact accepts a native cylinder and rejects conflicting flight',()=>{
 const p={...program,shape:'cylinder',groundLock:true,rollingRadius:70};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,keys:[{...p.keys[0],flightHeight:10},p.keys[1]]}}]}));
});

test('cone and native world lighting are exposed with the same bounds',()=>{
 const p={...program,shape:'cone',lighting:true};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',r),/4\.7\.2/);
});

test('rocking constraints and held-cel optimization match native policy',()=>{
 const p={...program,shape:'pyramid',groundLock:true,rockingPivot:true,holdLast:true,mergeHolds:true};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);for(const patch of [{groundLock:false},{rollingRadius:70},{shape:'sphere'}])assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,...patch}}]}));
});

test('two-body native scenes use strict app settings and a 4.7.3 gate',()=>{
 const r={...request,operations:[{type:'add_solid_collision',layerId:'layer_1',sceneId:'impact',settings:{ballMass:1,boxMass:3,restitution:.7,friction:100}}]};
 assert.deepEqual(nativeStudioParameters('studio_sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.2'},'sketch_edit',r),/4\.7\.3/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.3'},'sketch_edit',r));
 for(const settings of [{url:'https://example.test/reference.mp4'},{boxMass:0},{restitution:1.1},{boxSize:[10,20,30]},{speed:0}])assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],settings}]}));
});

test('fine mesh quality is bounded and rejects APKs without its native implementation',()=>{
 const r={...request,operations:[{...request.operations[0],program:{...program,shape:'sphere',meshDetail:'fine'}}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.2'},'sketch_edit',r),/4\.7\.3/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.3'},'sketch_edit',r));assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...r.operations[0].program,meshDetail:'unbounded'}}]}));
});
