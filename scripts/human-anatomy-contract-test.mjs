import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboard from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const legacy={view:'front',build:'slender',hairStyle:'short',skinColor:'#FFD6A078',hairColor:'#FF302D32',coatColor:'#FF526B78',trouserColor:'#FF343F50',x:.5,floorY:.88,size:1};
const actor={type:'add_human_animation',layerId:'layer_1',actorId:'actor',settings:{}};
const human=settings=>({settings,poses:[{frame:0},{frame:95,nearWrist:30,handOpen:.4}],durationFrames:96,fps:24,renderFrame:47,parts:'all'});
const object=settings=>({id:'actor_rig',kind:'human',human:human(settings),bounds:{x:.2,y:.1,width:.6,height:.8}});
const transform={x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1};
const operations=settings=>[
 {...actor,settings},
 {type:'add_object',layerId:'layer_1',object:object({...legacy,...settings})},
 {type:'update_object',objectId:'actor_rig',patch:{human:human({...legacy,...settings})}},
 {type:'add_layer',layer:{id:'actors',objects:[{...object({...legacy,...settings}),frame:0,exposure:96,color:'#FFD6A078',width:.001,fill:true,keys:[],transform}]}}
];
const story=ops=>({bookId:'book',operationId:'anatomy_story',name:'Anatomy study',width:1280,height:720,fps:24,shots:[{shotId:'actor',frameCount:96,operations:ops}]});
const envelopes=ops=>[['sketch_edit',{sketchId:'shot',expectedRevision:1,operationId:'anatomy',operations:ops}],['sketch_story_create',story(ops)],['sketch_story_preview',{plan:story(ops),shotId:'actor',frame:47}]];
const names=action=>[action,'studio_'+action];
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const fields=[{figureStyle:'legacy'},{figureStyle:'anatomy_sketch'},{constructionGuides:false},{figureStyle:'anatomy_sketch',constructionGuides:true}];
const oldViews=['front','left','right','back','high','low'];
const newViews=['three_quarter_left','three_quarter_right'];

test('optional anatomy settings round trip strictly without injecting defaults',()=>{
 for(const figureStyle of ['legacy','anatomy_sketch'])for(const constructionGuides of [false,true])for(const view of oldViews)for(const build of ['slender','broad']){
  if(figureStyle==='legacy'&&constructionGuides)continue;
  const settings={figureStyle,constructionGuides,view,build,faceStyle:'angular',shading:'soft'};
  for(const op of operations(settings))for(const [action,args] of envelopes([op]))assert.deepEqual(contract.nativeStudioParameters(action,args),args);
 }
});

test('explicit legacy and false independently require4.8.3 on every human and nested story route, including aliases',()=>{
 for(const settings of fields)for(const op of operations(settings))for(const [action,args] of envelopes([op]))for(const name of names(action)){
  for(const appVersion of ['4.7.9','4.8.0','4.8.1','4.8.2'])assert.throws(()=>contract.requireStudioVersion({appVersion},name,args),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.3/);
  for(const appVersion of ['4.8.3','4.8.4','4.9.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},name,args));
 }
});

test('three-quarter views require anatomy style and4.8.3 through direct and retained routes',()=>{
 for(const view of newViews){
  for(const figureStyle of [undefined,'legacy'])for(const op of operations({view,...(figureStyle?{figureStyle}:{})}))for(const [action,args] of envelopes([op]))assert.throws(()=>contract.nativeStudioParameters(action,args),/anatomy_sketch/);
  for(const op of operations({view,figureStyle:'anatomy_sketch'}))for(const [action,args] of envelopes([op]))for(const name of names(action)){
   assert.deepEqual(contract.nativeStudioParameters(name,args),args);
   assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.2'},name,args),/4\.8\.3/);
  }
  // The gate also protects callers that have not yet parsed their request.
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.2'},'sketch_edit',envelopes([{...actor,settings:{view}}])[0][1]),/4\.8\.3/);
 }
});

test('anatomy and tailored cannot combine on any human route or nested envelope',()=>{
 for(const op of operations({figureStyle:'anatomy_sketch',clothingStyle:'tailored'}))for(const [action,args] of envelopes([op]))assert.throws(()=>contract.nativeStudioParameters(action,args),/tailored/);
 for(const clothingStyle of [undefined,'legacy'])for(const op of operations({figureStyle:'anatomy_sketch',...(clothingStyle?{clothingStyle}:{})}))assert.doesNotThrow(()=>contract.nativeStudioParameters('sketch_edit',envelopes([op])[0][1]));
});

test('enabled guides require anatomy while explicit false stays valid in legacy settings',()=>{
 for(const figureStyle of [undefined,'legacy'])for(const op of operations({constructionGuides:true,...(figureStyle?{figureStyle}:{})}))for(const [action,args] of envelopes([op]))assert.throws(()=>contract.nativeStudioParameters(action,args),/constructionGuides.*anatomy_sketch/);
 for(const op of operations({constructionGuides:false}))for(const [action,args] of envelopes([op]))assert.deepEqual(contract.nativeStudioParameters(action,args),args);
});

test('malformed or misplaced anatomy values fail rather than coerce on all human routes',()=>{
 for(const [field,invalid] of Object.entries({figureStyle:['','Legacy',' anatomy_sketch','anatomy',null,false,1,{},[]],constructionGuides:['false','true',0,1,null,{},[]]}))for(const value of invalid)for(const op of operations({[field]:value}))for(const [action,args] of envelopes([op]))assert.throws(()=>contract.nativeStudioParameters(action,args));
 for(const field of ['figureStyle','constructionGuides']){
  const value=field==='figureStyle'?'anatomy_sketch':true;
  for(const op of [{...actor,[field]:value},{...actor,poses:[{frame:0,[field]:value}]},{...actor,poseOverrides:{[field]:value}},{type:'update_object',objectId:'actor_rig',patch:{[field]:value}},{type:'update_object',objectId:'actor_rig',patch:{human:{...human(legacy),[field]:value}}}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',envelopes([op])[0][1]));
 }
 for(const field of Object.keys(legacy)){
  const settings={...legacy,figureStyle:'anatomy_sketch',constructionGuides:false};delete settings[field];
  for(const op of [{type:'add_object',layerId:'layer_1',object:object(settings)},{type:'update_object',objectId:'actor_rig',patch:{human:human(settings)}}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',envelopes([op])[0][1]));
 }
});

test('omission keeps canonical bytes and old4.8.0/4.8.1/4.8.2 feature floors',async()=>{
 for(const view of oldViews)for(const op of operations({view}))for(const [action,args] of envelopes([op]))for(const name of names(action)){
  const parsed=contract.nativeStudioParameters(name,args);assert.deepEqual(parsed,args);assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(args));
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},name,args));
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},name,args),/4\.8\.0/);
 }
 for(const settings of [{clothingStyle:'legacy'},{clothingStyle:'tailored'}])for(const op of operations(settings))for(const [action,args] of envelopes([op])){
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.1'},action,args),/4\.8\.2/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.2'},action,args));
 }
 const hand={...actor,type:'add_hand_animation',handId:'hand',actorId:undefined,settings:{renderStyle:'cel'}};delete hand.actorId;
 assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.0'},'sketch_edit',envelopes([hand])[0][1]),/4\.8\.1/);
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.1'},'sketch_edit',envelopes([hand])[0][1]));
 for(const field of ['figureStyle','constructionGuides'])assert.equal(Object.hasOwn(contract.HUMAN_ANIMATION_CAPABILITIES.settings,field),false);
});

test('higher anatomy gate wins mixed batch order and checks unselected preview shots',()=>{
 const hand={type:'add_hand_animation',layerId:'layer_1',handId:'hand',settings:{renderStyle:'cel'}};
 const clothing={...actor,settings:{clothingStyle:'tailored'}};
 for(const settings of fields)for(const anatomy of operations(settings))for(const ops of [[hand,clothing,anatomy],[anatomy,clothing,hand]])for(const [action,args] of envelopes(ops))assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.0'},action,args),/4\.8\.3/);
 const plan=story([actor]);plan.shots.push({shotId:'other',frameCount:96,operations:[{...actor,settings:{constructionGuides:false}}]});
 for(const action of names('sketch_story_preview'))assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.2'},action,{plan,shotId:'actor',frame:0}),/4\.8\.3/);
});

test('real relay rejects old clients and malformed anatomy before queue persistence',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const settings of fields)for(const op of operations(settings))for(const [action,args] of envelopes([op]))for(const name of names(action)){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='a'.repeat(43);
  const register=appVersion=>relay.appRegister('anatomy-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.8.2');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,args),/4\.8\.3/);assert.equal(storage.writes(),before);
  await register('4.8.3');before=storage.writes();
  const invalid=envelopes([{...actor,settings:{constructionGuides:'false'}}]).find(([a])=>a===action)[1];await assert.rejects(relay.appEnqueueV3(key,name,invalid));assert.equal(storage.writes(),before);
  const queued=await relay.appEnqueueV3(key,name,args);assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),args);
 }
});

test('opaque archive references preserve old floors and cannot bypass strict anatomy schemas',()=>{
 const args={archiveProjectId:'archive',archiveName:'actor.vssketch',expectedSha256:'a'.repeat(64),operationId:'restore'};
 for(const action of names('sketch_restore_archive')){
  assert.deepEqual(contract.nativeStudioParameters(action,args),args);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.1.0'},action,args));
  for(const fields of [{figureStyle:'legacy'},{constructionGuides:false},{document:{layers:[]}}])assert.throws(()=>contract.nativeStudioParameters(action,{...args,...fields}));
 }
 assert.match(contract.HUMAN_ANIMATION_CAPABILITIES.figureArchiveCompatibility,/4\.8\.3/);
 assert.match(contract.HUMAN_ANIMATION_CAPABILITIES.figureArchiveCompatibility,/opaque.*cannot inspect/i);
 assert.match(contract.HUMAN_ANIMATION_CAPABILITIES.figureArchiveCompatibility,/floor.*unchanged/i);
});

test('capabilities match native and advertise style-specific views, manual controls and bounded quality honestly',()=>{
 const m=contract.HUMAN_ANIMATION_CAPABILITIES;
 assert.deepEqual(m.figureStyles,['legacy','anatomy_sketch']);assert.equal(m.defaultFigureStyle,'legacy');assert.equal(m.figureStyleMinimumNativeVersion,'4.8.3');assert.equal(m.constructionGuidesDefault,false);assert.equal(m.constructionGuidesMinimumNativeVersion,'4.8.3');assert.equal(m.threeQuarterViewMinimumNativeVersion,'4.8.3');
 assert.deepEqual(m.views,[...oldViews,...newViews]);assert.deepEqual(m.viewsByFigureStyle,{legacy:oldViews,anatomy_sketch:[...oldViews,...newViews]});
 assert.match(m.figureControls,/explicit.*legacy.*false.*4\.8\.3/);assert.match(m.figureControls,/tailored/);assert.match(m.manualControls,/Figure style.*Construction guides/);assert.match(m.agentGuidance,/app_status.*figureStyle.*constructionGuides.*4\.8\.3/);
 assert.match(m.figureControls,/faceStyle, shading and lightAngle.*legacy.*do not.*anatomy/);assert.match(m.manualControls,/Face structure.*legacy only.*anatomy disables/);
 for(const field of ['figureStyle','constructionGuides'])assert.match(m.retainedObject,new RegExp(field));
 for(const key of ['genuine3D','creativeQualityCertified','phoneExecutionVerified','speechVerified'])assert.equal(m[key],false);
 class JSONObject{put(key,value){this[key]=value;return this;}}class JSONArray extends Array{constructor(values){super();this.push(...values);}}
 const cameraLiteral=native('SketchHumanCameraGuide').match(/return new JSONObject\(("(?:\\.|[^"\\])*")\);/)[1];
 const SketchHumanCameraGuide={capabilities:()=>JSON.parse(JSON.parse(cameraLiteral))};
 const fringeLiteral=native('SketchHumanFringeCapabilities').match(/new JSONObject\(("(?:\\.|[^"\\])*")\)/)[1];
 const SketchHumanFringeCapabilities={get:()=>JSON.parse(JSON.parse(fringeLiteral))};
 const headLiteral=native('SketchHumanHeadProportions').match(/new JSONObject\(("(?:\\.|[^"\\])*")\)/)[1];
 const SketchHumanHeadProportions={capabilities:()=>JSON.parse(JSON.parse(headLiteral))};
 const body=native('SketchCapabilities').match(/public static JSONObject humanAnimation\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)[1];
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext('('+body.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]')+')',{JSONObject,JSONArray,SketchHumanCameraGuide,SketchHumanFringeCapabilities,SketchHumanHeadProportions}))),m);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];assert.match(detail,/figureStyle.*constructionGuides.*4\.8\.3/);assert.match(detail,/three_quarter_left.*three_quarter_right/);
});

test('native release constants agree at4.10.27/code530 while earlier anatomy floors stay unchanged',()=>{
 assert.match(native('AppProtocol'),/APP_VERSION = "4\.10\.27"/);
 const build=fs.readFileSync(new URL('../android/app/build.gradle.kts',import.meta.url),'utf8');assert.match(build,/versionCode = 530/);assert.match(build,/versionName = "4\.10\.27"/);
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);assert(contract.STUDIO_ALIAS_ACTIONS.has("studio_sketch_layered_drawing_preview"));for(const action of contract.STUDIO_NATIVE_ACTIONS)assert(contract.STUDIO_ALIAS_ACTIONS.has("studio_"+action));assert.equal(contract.HUMAN_ANIMATION_CAPABILITIES.minimumNativeVersion,'4.8.0');
});

test('numeric construction views are strict anatomy-only controls and preserve omitted legacy requests',()=>{
 const p={sketchId:'guide',operationId:'degrees',expectedRevision:1,operations:[{type:'add_human_animation',layerId:'layer_1',actorId:'actor',preset:'listen',settings:{figureStyle:'anatomy_sketch',viewYawDegrees:25,viewPitchDegrees:-30}}]};
 assert.deepEqual(contract.nativeStudioParameters('sketch_edit',p),p);
 assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.5'},'sketch_edit',p),/4\.10\.6/);
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.6'},'sketch_edit',p));
 for(const bad of [{viewYawDegrees:'25'},{viewYawDegrees:null},{viewPitchDegrees:76},{viewYawDegrees:361},{figureStyle:'legacy'}]){const q=structuredClone(p);Object.assign(q.operations[0].settings,bad);assert.throws(()=>contract.nativeStudioParameters('sketch_edit',q));}
 const old=structuredClone(p);delete old.operations[0].settings.viewYawDegrees;delete old.operations[0].settings.viewPitchDegrees;assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.3'},'sketch_edit',old));assert.deepEqual(contract.nativeStudioParameters('sketch_edit',old),old);
});
