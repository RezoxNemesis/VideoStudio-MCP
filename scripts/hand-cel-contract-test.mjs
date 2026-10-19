import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboard from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const legacy={view:'front',handedness:'right',detail:'anatomical',skinColor:'#FFD6A078',nailColor:'#FFE5C1AC',centerX:.5,centerY:.55,size:1,motion:1,wristMotion:.6};
const hand={type:'add_hand_animation',layerId:'layer_1',handId:'hand',settings:{}};
const edit=operations=>({sketchId:'shot',expectedRevision:1,operationId:'style',operations});
const retained=settings=>({settings,poses:[{frame:0},{frame:95,indexCurl:.8}],durationFrames:96,fps:24,renderFrame:47,sourceFrameOffset:0});
const object=settings=>({id:'hand_rig',kind:'hand',hand:retained(settings),bounds:{x:.2,y:.1,width:.6,height:.8}});
const objectOps=settings=>[
 {type:'add_object',layerId:'layer_1',object:object(settings)},
 {type:'update_object',objectId:'hand_rig',patch:{hand:retained(settings)}},
 {type:'add_layer',layer:{id:'hands',objects:[{...object(settings),frame:0,exposure:96,color:'#FFD6A078',width:.0012,fill:true,keys:[],transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1}}]}}
];
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const routes=['sketch_edit','studio_sketch_edit'];

test('hand renderStyle smooth/cel is strict and independent of anatomical/contour detail',()=>{
 for(const action of routes)for(const renderStyle of ['smooth','cel'])for(const detail of ['anatomical','contour']){
  const args=edit([{...hand,settings:{renderStyle,detail}}]);assert.deepEqual(contract.nativeStudioParameters(action,args),args);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,args),/4\.8\.1/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.1'},action,args));
 }
});

test('all retained hand add/layer/update and sampled-cel payload routes preserve optional style and require 4.8.1 when present',()=>{
 for(const action of routes)for(const renderStyle of ['smooth','cel'])for(const operation of objectOps({...legacy,renderStyle})){
  const args=edit([operation]);assert.deepEqual(contract.nativeStudioParameters(action,args),args);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,args),/4\.8\.1/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.1'},action,args));
 }
});

test('omitted styles retain the native 4.8.0 floor and exact legacy fingerprints on every object route',async()=>{
 for(const action of routes)for(const operation of [{...hand,settings:{}},{...hand,settings:legacy},...objectOps(legacy)]){
  const args=edit([operation]),parsed=contract.nativeStudioParameters(action,args);assert.deepEqual(parsed,args);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,args));assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},action,args),/4\.8\.0/);
  assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(args));
 }
});

test('hand styles reject aliases, coercion and misplaced surface controls',()=>{
 for(const action of routes){
  for(const renderStyle of ['flat','Cel',' smooth','',null,0,true,[],{}])for(const operation of [{...hand,settings:{renderStyle}},...objectOps({...legacy,renderStyle})])assert.throws(()=>contract.nativeStudioParameters(action,edit([operation])));
  for(const operation of [{...hand,renderStyle:'cel'},{...hand,settings:{style:'cel'}},{...hand,poses:[{frame:0,renderStyle:'cel'}]},{type:'update_object',objectId:'hand_rig',patch:{renderStyle:'cel'}}])assert.throws(()=>contract.nativeStudioParameters(action,edit([operation])));
 }
});

test('explicit smooth also gates mixed batches and nested native story creation/preview at 4.8.1',()=>{
 for(const renderStyle of ['smooth','cel'])for(const reverse of [false,true]){
  const operations=[hand,{...hand,handId:'styled',settings:{renderStyle}}];if(reverse)operations.reverse();
  const plan={bookId:'book',operationId:'film',name:'Hands',width:1280,height:720,fps:24,shots:[{shotId:'hand',frameCount:96,operations}]};
  for(const [action,args] of [['sketch_edit',edit(operations)],['sketch_story_create',plan],['sketch_story_preview',{plan,shotId:'hand',frame:0}]])for(const name of [action,'studio_'+action]){
   assert.deepEqual(contract.nativeStudioParameters(name,args),args);assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.0'},name,args),/4\.8\.1/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.1'},name,args));
  }
 }
});

test('actual relay source-gates hand style before queue writes and preserves authored settings',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of routes)for(const renderStyle of ['smooth','cel']){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='c'.repeat(43),deviceId='hand-style-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.8.0');let before=storage.writes();const args=edit([{...hand,settings:{renderStyle}}]);
  await assert.rejects(relay.appEnqueueV3(key,action,args),/4\.8\.1/);assert.equal(storage.writes(),before);
  await register('4.8.1');before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,edit([{...hand,settings:{renderStyle:'flat'}}])));assert.equal(storage.writes(),before);
  const queued=await relay.appEnqueueV3(key,action,args);assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),args);
 }
});

test('latest cel motion repair advertises 4.8.2 without raising the existing hand/style input gates',()=>{
 const m=contract.HAND_ANIMATION_CAPABILITIES;
 assert.equal(m.celMotionQualityMinimumNativeVersion,'4.8.2');
 assert.equal(m.minimumNativeVersion,'4.8.0');assert.equal(m.renderStyleMinimumNativeVersion,'4.8.1');
 assert.match(m.agentGuidance,/app_status.*require.*4\.8\.2.*latest cel motion repair.*before authoring/i);
 assert.match(m.agentGuidance,/guidance.*not a new.*gate/i);
 const java=native('SketchCapabilities').match(/public static JSONObject handAnimation\(\) throws Exception \{([\s\S]*?)\n    \}/)[1];
 assert.match(java,/\.put\("celMotionQualityMinimumNativeVersion","4\.8\.2"\)/);
 assert.equal(JSON.parse(java.match(/\.put\("agentGuidance",("[^"]*")\)/)[1]),m.agentGuidance);
 for(const action of routes)for(const [settings,version] of [[{},'4.8.0'],[{renderStyle:'smooth'},'4.8.1'],[{renderStyle:'cel'},'4.8.1']]){
  const args=edit([{...hand,settings}]);assert.deepEqual(contract.nativeStudioParameters(action,args),args);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:version},action,args));
 }
 assert.equal(Object.hasOwn(m.settings,'celMotionQualityMinimumNativeVersion'),false);
});

test('style metadata matches native defaults and advertises bounded native/manual/archive compatibility',()=>{
 const metadata=contract.HAND_ANIMATION_CAPABILITIES;assert.equal(metadata.minimumNativeVersion,'4.8.0');assert.equal(metadata.renderStyleMinimumNativeVersion,'4.8.1');assert.deepEqual(metadata.renderStyles,['smooth','cel']);assert.equal(metadata.settings.renderStyle,'smooth');
 assert.match(metadata.renderStyleControls,/explicit.*smooth.*4\.8\.1/i);assert.match(metadata.renderStyleControls,/omit.*fingerprint/i);assert.match(metadata.renderStyleControls,/independent.*detail/i);
 assert.match(metadata.archiveCompatibility,/archive.*4\.8\.1/i);assert.match(metadata.archiveCompatibility,/opaque.*inspect/i);
 assert.match(metadata.manualControls,/Render style/);assert.match(metadata.agentGuidance,/app_status.*4\.8\.1/);
 assert.match(worker,/renderStyle.*smooth.*cel.*4\.8\.1/);assert.match(native('SketchHandDialog'),/"Render style"/);
 class JSONObject {put(key,value){this[key]=value;return this;}}
 const expression=native('SketchHandAnimation').match(/static JSONObject defaults\(\)\s*throws Exception\s*\{\s*return ([\s\S]*?);\s*\}/)[1];assert.equal(vm.runInNewContext('('+expression+')',{JSONObject}).renderStyle,'smooth');
});

test('style adds no canonical actions and does not raise opaque legacy archive/component floors',()=>{
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,68);assert.equal(contract.STUDIO_ALIAS_ACTIONS.size,79);
 const nativeActions=native('NativeWorkbenchBridge').match(/ACTIONS=[\s\S]*?Arrays\.asList\(([\s\S]*?)\)\)\)/)[1].match(/"[a-z0-9_]+"/g);assert.equal(nativeActions.length,81);
 const archive={archiveProjectId:'archive',archiveName:'hand.vssketch',expectedSha256:'a'.repeat(64),operationId:'restore'};
 for(const action of ['sketch_restore_archive','studio_sketch_restore_archive']){
  assert.deepEqual(contract.nativeStudioParameters(action,archive),archive);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.1.0'},action,archive));assert.throws(()=>contract.nativeStudioParameters(action,{...archive,renderStyle:'cel'}));
 }
});
