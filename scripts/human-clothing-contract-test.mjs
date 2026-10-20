import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboard from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const legacy={view:'front',build:'slender',hairStyle:'short',skinColor:'#FFD6A078',hairColor:'#FF302D32',coatColor:'#FF526B78',trouserColor:'#FF343F50',x:.5,floorY:.88,size:1};
const appearance={faceStyle:'graphic',shading:'cel',lightAngle:135};
const actor={type:'add_human_animation',layerId:'layer_1',actorId:'actor',settings:{}};
const edit=operations=>({sketchId:'shot',expectedRevision:1,operationId:'clothing',operations});
const human=settings=>({settings,poses:[{frame:0},{frame:95,nearWrist:30,handOpen:.4}],durationFrames:96,fps:24,renderFrame:47,sourceFrameOffset:-1,parts:'near_hand'});
const object=settings=>({id:'actor_rig',kind:'human',human:human(settings),bounds:{x:.2,y:.1,width:.6,height:.8}});
const transform={x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1};
const objectOps=settings=>[
 {type:'add_object',layerId:'layer_1',object:object(settings)},
 {type:'update_object',objectId:'actor_rig',patch:{human:human(settings)}},
 {type:'add_layer',layer:{id:'actors',objects:[{...object(settings),frame:0,exposure:96,color:'#FFD6A078',width:.001,fill:true,keys:[],transform}]}}
];
const operations=clothingStyle=>[{...actor,settings:{clothingStyle}},...objectOps({...legacy,clothingStyle})];
const story=operations=>({bookId:'book',operationId:'clothing_story',name:'Clothing study',width:1280,height:720,fps:24,shots:[{shotId:'actor',frameCount:96,operations}]});
const envelopes=operations=>[['sketch_edit',edit(operations)],['sketch_story_create',story(operations)],['sketch_story_preview',{plan:story(operations),shotId:'actor',frame:47}]];
const names=action=>[action,'studio_'+action];
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');

test('clothing is a strict optional setting independent of face, shading and build',()=>{
 for(const clothingStyle of ['legacy','tailored'])for(const faceStyle of ['graphic','angular','soft'])for(const shading of ['cel','soft'])for(const build of ['slender','broad'])for(const action of names('sketch_edit')){
  const args=edit([{...actor,settings:{clothingStyle,faceStyle,shading,build,lightAngle:-42.5}}]);assert.deepEqual(contract.nativeStudioParameters(action,args),args);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.1'},action,args),/4\.8\.2/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.2'},action,args));
 }
});

test('retained human partial/full adds and field patches preserve optional clothing with old appearance omission',()=>{
 for(const clothingStyle of ['legacy','tailored'])for(const operation of objectOps({...legacy,clothingStyle}))for(const action of names('sketch_edit')){
  const args=edit([operation]);assert.deepEqual(contract.nativeStudioParameters(action,args),args);
  for(const appVersion of ['4.8.0','4.8.1'])assert.throws(()=>contract.requireStudioVersion({appVersion},action,args),/4\.8\.2/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.2'},action,args));
 }
 for(const clothingStyle of ['legacy','tailored'])for(const field of Object.keys(legacy)){
  const settings={...legacy,clothingStyle};delete settings[field];assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit([objectOps(settings)[1]])));
 }
});

test('unknown/coerced clothing and misplaced style fields fail strictly on every object route',()=>{
 for(const value of ['', 'Tailored',' tailored','smooth',null,0,true,{},[]])for(const operation of operations(value))assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit([operation])));
 for(const operation of [{...actor,clothingStyle:'tailored'},{...actor,settings:{clothing:'tailored'}},{...actor,poses:[{frame:0,clothingStyle:'tailored'}]},{...actor,poseOverrides:{clothingStyle:'tailored'}},{type:'update_object',objectId:'actor_rig',patch:{clothingStyle:'tailored'}},{type:'update_object',objectId:'actor_rig',patch:{human:{...human(legacy),clothingStyle:'tailored'}}}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit([operation])));
});

test('omission preserves old 4.8.0 gates and exact canonical fingerprints including nested story requests',async()=>{
 for(const settings of [{},legacy,{...legacy,...appearance}])for(const operation of [{...actor,settings},...(settings.view?objectOps(settings):[])])for(const [action,args] of envelopes([operation]))for(const name of names(action)){
  const parsed=contract.nativeStudioParameters(name,args);assert.deepEqual(parsed,args);assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(args));
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},name,args));assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},name,args),/4\.8\.0/);
 }
 assert.equal(Object.hasOwn(contract.HUMAN_ANIMATION_CAPABILITIES.settings,'clothingStyle'),false);
});

test('explicit legacy and tailored gate all direct/object/layer/patch routes in mixed and nested story batches',()=>{
 for(const style of ['legacy','tailored'])for(const operation of operations(style))for(const reverse of [false,true]){
  const batch=[actor,operation];if(reverse)batch.reverse();
  for(const [action,args] of envelopes(batch))for(const name of names(action)){
   assert.deepEqual(contract.nativeStudioParameters(name,args),args);assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.1'},name,args),/NATIVE_APP_UPGRADE_REQUIRED:.*clothingStyle.*4\.8\.2/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.2'},name,args));
  }
 }
});

test('mixed hand and clothing features report the higher required native version',()=>{
 const hand={type:'add_hand_animation',layerId:'layer_1',handId:'hand',settings:{renderStyle:'cel'}};
 for(const style of ['legacy','tailored'])for(const operations of [[hand,...operationsForStyle(style)],[...operationsForStyle(style),hand]])for(const [action,args] of envelopes(operations)){
  for(const appVersion of ['4.8.0','4.8.1'])assert.throws(()=>contract.requireStudioVersion({appVersion},action,args),/clothingStyle.*4\.8\.2/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.2'},action,args));
 }
 function operationsForStyle(style){return [{...actor,settings:{clothingStyle:style}}];}
});

test('relay enforces clothing source gates and strict settings before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const style of ['legacy','tailored'])for(const [action,args] of envelopes(operations(style)))for(const name of names(action)){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='c'.repeat(43);
  const register=appVersion=>relay.appRegister('clothing-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.8.1');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,args),/4\.8\.2/);assert.equal(storage.writes(),before);
  await register('4.8.2');before=storage.writes();const invalid=envelopes(operations('Tailored')).find(([a])=>a===action)[1];await assert.rejects(relay.appEnqueueV3(key,name,invalid));assert.equal(storage.writes(),before);
  const queued=await relay.appEnqueueV3(key,name,args);assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),args);
 }
});

test('human clothing metadata matches native literals and gives ordinary ChatGPT honest manual/archive guidance',()=>{
 const m=contract.HUMAN_ANIMATION_CAPABILITIES;assert.deepEqual(m.clothingStyles,['legacy','tailored']);assert.equal(m.defaultClothingStyle,'legacy');assert.equal(m.clothingStyleMinimumNativeVersion,'4.8.2');assert.equal(m.minimumNativeVersion,'4.8.0');
 assert.match(m.clothingControls,/explicit.*legacy.*4\.8\.2/);assert.match(m.clothingControls,/omit.*fingerprint/i);assert.match(m.clothingControls,/independent.*faceStyle.*shading/);assert.match(m.clothingControls,/fixed.*grip.*sole/i);assert.match(m.clothingControls,/not cloth simulation/);
 assert.match(m.clothingArchiveCompatibility,/opaque.*cannot inspect/i);assert.match(m.clothingArchiveCompatibility,/floor.*unchanged/i);assert.match(m.manualControls,/Clothing.*dropdown/);assert.match(m.agentGuidance,/app_status.*clothingStyle.*4\.8\.2/);assert.match(m.agentGuidance,/visual.*review/i);
 for(const key of ['genuine3D','creativeQualityCertified','phoneExecutionVerified','speechVerified'])assert.equal(m[key],false);
 assert.deepEqual(m.budgets,{maximumBytes:4194304,maximumObjects:4096,maximumPoints:131072,maximumKeys:4096});assert.equal(m.channels.length,35);assert.equal(m.maximumRetainedActors,16);
 class JSONObject{put(key,value){this[key]=value;return this;}}class JSONArray extends Array{constructor(values){super();this.push(...values);}}
 const body=native('SketchCapabilities').match(/public static JSONObject humanAnimation\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)[1];const expression=body.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]');assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext('('+expression+')',{JSONObject,JSONArray}))),m);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];assert.match(detail,/clothingStyle.*legacy.*tailored.*4\.8\.2/);assert.match(detail,/Clothing.*dropdown/);
});

test('release is coherent at 4.9.2/code495 and existing hand/archive/action floors stay intact',()=>{
 assert.match(native('AppProtocol'),/APP_VERSION = "4\.9\.4"/);const build=fs.readFileSync(new URL('../android/app/build.gradle.kts',import.meta.url),'utf8');assert.match(build,/versionCode = 497/);assert.match(build,/versionName = "4\.9\.4"/);
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);assert(contract.STUDIO_ALIAS_ACTIONS.has("studio_sketch_layered_drawing_preview"));for(const action of contract.STUDIO_NATIVE_ACTIONS)assert(contract.STUDIO_ALIAS_ACTIONS.has("studio_"+action));assert.equal(contract.HAND_ANIMATION_CAPABILITIES.renderStyleMinimumNativeVersion,'4.8.1');
 const archive={archiveProjectId:'archive',archiveName:'actor.vssketch',expectedSha256:'a'.repeat(64),operationId:'restore'};for(const action of names('sketch_restore_archive')){assert.deepEqual(contract.nativeStudioParameters(action,archive),archive);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.1.0'},action,archive));assert.throws(()=>contract.nativeStudioParameters(action,{...archive,clothingStyle:'tailored'}));}
});
