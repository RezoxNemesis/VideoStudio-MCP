import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const actions=['sketch_edit','studio_sketch_edit'];
const stage={type:'add_story_stage',layerId:'background',sceneId:'scene',settings:{}};
const prop={type:'add_actor_prop',layerId:'actors',propId:'held',actorId:'hero',settings:{}};
const stageDefaults={location:'forest_path',time:'dusk',weather:'clear',intensity:.6};
const propDefaults={kind:'lantern',attachment:'near',scale:1,x:.5,y:.8};
const request=operation=>({sketchId:'shot',operationId:'story_1',expectedRevision:1,operations:[operation]});
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');

test('story stage accepts native defaults, all locations/times/weather and separate foreground identity',()=>{
 for(const action of actions){
  for(const settings of [{},stageDefaults]){const r=request({...stage,settings});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const location of ['forest_path','bridge','shelter','village'])for(const time of ['dusk','night','dawn'])for(const weather of ['clear','wind','rain']){
   const r=request({...stage,foregroundLayerId:'foreground',settings:{location,time,weather,intensity:.7}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
 }
});

test('story and prop identities/settings are strict and bounded without applying native defaults in Node',()=>{
 for(const action of actions)for(const [op,idField] of [[stage,'sceneId'],[prop,'propId']]){
  const r=request({...op,[idField]:'s'.repeat(20),layerId:'l'.repeat(96)});assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const patch of [{[idField]:undefined},{[idField]:''},{[idField]:'s'.repeat(21)},{[idField]:'../scene'},{layerId:undefined},{layerId:'with space'},
   {settings:undefined},{settings:null},{settings:[]},{settings:{unknown:true}},{url:'https://invalid.test/scene'},{poses:[{frame:0}]},{speech:{}},{frameCount:180}])
   assert.throws(()=>contract.nativeStudioParameters(action,request({...op,...patch})),JSON.stringify(patch));
 }
 for(const foregroundLayerId of ['',null,'../foreground','f'.repeat(97)])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',request({...stage,foregroundLayerId})));
 assert.throws(()=>contract.nativeStudioParameters('sketch_edit',request({...stage,foregroundLayerId:stage.layerId})));
});

test('stage enum choices reject aliases and numeric intensity accepts finite inclusive 0..1 only',()=>{
 for(const action of actions){
  for(const settings of [{location:'forest'},{time:'sunset'},{weather:'storm'},{location:null},{time:0},{weather:true}])assert.throws(()=>contract.nativeStudioParameters(action,request({...stage,settings})));
  for(const intensity of [0,1]){const r=request({...stage,settings:{intensity}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const intensity of [-.001,1.001,NaN,Infinity,-Infinity,'0.6',null,true])assert.throws(()=>contract.nativeStudioParameters(action,request({...stage,settings:{intensity}})));
 }
});

test('actor props preserve native kinds, attachment choices and explicit floor coordinates',()=>{
 for(const action of actions){
  for(const kind of ['lantern','parcel','staff'])for(const attachment of ['near','far','floor']){
   const op={...prop,settings:{kind,attachment,scale:.8,x:.4,y:.6}};if(attachment==='floor')delete op.actorId;
   const r=request(op);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  }
  for(const [field,min,max] of [['scale',.4,1.5],['x',0,1],['y',0,1]]){
   for(const value of [min,max]){const r=request({...prop,settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
   for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'0.5',null,true])assert.throws(()=>contract.nativeStudioParameters(action,request({...prop,settings:{[field]:value}})));
  }
  for(const settings of [{kind:'lamp'},{attachment:'left'},{kind:null},{attachment:0}])assert.throws(()=>contract.nativeStudioParameters(action,request({...prop,settings})));
 }
});

test('held actor props require actorId including default near; floor permits omission but validates supplied identity',()=>{
 for(const action of actions){
  for(const settings of [{},{attachment:'near'},{attachment:'far'}]){
   const op={...prop,settings};delete op.actorId;assert.throws(()=>contract.nativeStudioParameters(action,request(op)),JSON.stringify(settings));
  }
  const floor={...prop,settings:{attachment:'floor'}};delete floor.actorId;
  const r=request(floor);assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const actorId of ['',null,'../actor','a'.repeat(97)])assert.throws(()=>contract.nativeStudioParameters(action,request({...floor,actorId})));
 }
});

test('stage and props require native 4.8.0 in both aliases and either mixed-batch order',()=>{
 const face={type:'add_face_animation',layerId:'actors',faceId:'face',settings:{}};
 for(const action of actions)for(const op of [stage,prop])for(const operations of [[op],[op,face],[face,op]]){
  const r={...request(op),operations};
  for(const device of [undefined,{}, {appVersion:'unknown'},{appVersion:'4.7.8'}])assert.throws(()=>contract.requireStudioVersion(device,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.0/);
  for(const appVersion of ['4.8.0','4.8.1','4.9.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,r));
 }
});

test('real relay rejects old APKs, malformed settings and missing held actor before any queue write',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions)for(const op of [stage,prop]){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='p'.repeat(43);
  const register=appVersion=>relay.appRegister('stage-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.8');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request(op)),/4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();
  for(const invalid of [{...op,settings:{unknown:true}},{...prop,actorId:undefined}]){
   await assert.rejects(relay.appEnqueueV3(key,action,{...request(op),operations:[op,invalid]}));assert.equal(storage.writes(),before);
  }
  const r=request(op);assert.deepEqual(JSON.parse(JSON.stringify((await relay.appEnqueueV3(key,action,r)).parameters)),r);
 }
});

test('stage/prop Java and Node capabilities match native defaults and retain unchanged budgets',()=>{
 class JSONObject {put(key,value){this[key]=value;return this;}}
 class JSONArray extends Array {constructor(values){super();this.push(...values);}}
 const capabilities=native('SketchCapabilities');
 for(const [exportName,method,className,defaults,operation] of [['STORY_STAGE_CAPABILITIES','storyStage','SketchStoryStage',stageDefaults,stage.type],['ACTOR_PROP_CAPABILITIES','actorProp','SketchActorProp',propDefaults,prop.type]]){
  const metadata=contract[exportName];assert.ok(metadata,exportName);assert.equal(metadata.minimumNativeVersion,'4.8.0');assert.equal(metadata.operation,operation);assert.equal(metadata.action,'sketch_edit');
  assert.deepEqual(metadata.settings,defaults);assert.deepEqual(metadata.budgets,{maximumBytes:4194304,maximumObjects:4096,maximumPoints:131072,maximumKeys:4096});
  for(const flag of ['genuine3D','creativeQualityCertified','phoneExecutionVerified'])assert.equal(metadata[flag],false);
  const expression=native(className).match(/static JSONObject defaults\(\)throws Exception\s*\{\s*return ([\s\S]*?);\s*\}/)[1];
  assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${expression})`,{JSONObject}))),defaults);
  const methodExpression=capabilities.match(new RegExp(`public static JSONObject ${method}\\(\\) throws Exception \\{\\s*return ([\\s\\S]*?);\\s*\\}`))?.[1];assert.ok(methodExpression,method);
  assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${methodExpression.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]')})`,{JSONObject,JSONArray}))),metadata);
  assert.ok(capabilities.includes(`.put("${method}",${method}())`));assert.match(metadata.output,/ordinary editable native paths/i);assert.ok(metadata.manualSource.includes(className));
 }
 assert.match(contract.ACTOR_PROP_CAPABILITIES.attachment,/actorId.*same actor layer.*actual.*grip/);
 assert.match(contract.ACTOR_PROP_CAPABILITIES.motion,/every actual output frame.*no resampl/);
 assert.match(contract.ACTOR_PROP_CAPABILITIES.editing,/independent.*rebake/);
 assert.match(contract.STORY_STAGE_CAPABILITIES.motion,/compact periodic.*rain.*wind/);
});

test('MCP capabilities and ordinary ChatGPT guidance expose the source-bound scenery/prop contracts',async()=>{
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);let handler;
 vm.runInNewContext(worker.slice(start,end),{s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3:true,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[],
  CHARACTER_PERFORMANCE_CAPABILITIES:contract.CHARACTER_PERFORMANCE_CAPABILITIES,HAND_ANIMATION_CAPABILITIES:contract.HAND_ANIMATION_CAPABILITIES,HUMAN_ANIMATION_CAPABILITIES:contract.HUMAN_ANIMATION_CAPABILITIES,
  STORY_STAGE_CAPABILITIES:contract.STORY_STAGE_CAPABILITIES,ACTOR_PROP_CAPABILITIES:contract.ACTOR_PROP_CAPABILITIES});
 const result=await handler();assert.ok(result.storyStage);assert.ok(result.actorProp);assert.deepEqual(result.storyStage,contract.STORY_STAGE_CAPABILITIES);assert.deepEqual(result.actorProp,contract.ACTOR_PROP_CAPABILITIES);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 for(const pattern of [/add_story_stage/,/add_actor_prop/,/sketch_capabilities\.storyStage/,/sketch_capabilities\.actorProp/,/ordinary ChatGPT.*app_status/,/same actor layer/,/actual.*grip/,/4\.8\.0/])assert.match(detail,pattern);
});

test('held prop metadata explains retained hand overlays, occlusion, shared actor capacity and rebaking',()=>{
 const metadata=contract.ACTOR_PROP_CAPABILITIES;
 assert.match(metadata.output,/may retain.*separate.*hand.*overlay/i);
 assert.match(metadata.occlusion,/fingers.*after.*handle/i);
 assert.equal(metadata.maximumRetainedActors,16);assert.match(metadata.limits,/16.*clones/i);
 assert.match(metadata.editing,/rebake.*actor edits.*overlay/i);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 assert.match(detail,/separate hand overlay/);assert.match(detail,/16.*clones/);
 assert.match(detail,/optional parts.*without_near_hand.*without_far_hand.*near_hand.*far_hand/);
});

test('stage foreground and prop contract reach their actual native helpers through parent dispatch',()=>{
 const stageSource=native('SketchStoryStage'),propSource=native('SketchActorProp'),dispatch=native('SketchEditOps');
 assert.match(stageSource,/fields\(op,"type,layerId,foregroundLayerId,sceneId,settings"\)/);
 assert.match(stageSource,/foreground\.equals\(layerId\)/);assert.match(stageSource,/appendSplit\(doc,layerId,foreground,id,objects\)/);
 assert.match(propSource,/SAME_ACTOR_LAYER/);assert.match(propSource,/SketchHumanAnimation\.pose\(actor,frame\)/);
 assert.match(propSource,/objectTransform\.mapPoints\(point\)/);
 assert.match(dispatch,/case "add_story_stage":[\s\S]{0,100}SketchStoryStage\.apply\(doc,op\)/);
 assert.match(dispatch,/case "add_actor_prop":[\s\S]{0,100}SketchActorProp\.apply\(doc,op\)/);
});
