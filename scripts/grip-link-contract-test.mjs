import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const actions=['sketch_edit','studio_sketch_edit'];
const link={type:'add_grip_link',layerId:'links',linkId:'rope',from:{actorId:'hero',hand:'near'},to:{actorId:'companion',hand:'far'},settings:{}};
const defaults={sagPx:12,widthPx:2.8,color:'#FF9D784F'};
const request=(patch={})=>({sketchId:'shot',operationId:'grip_1',expectedRevision:1,operations:[{...link,...patch}]});
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');

test('grip links preserve sparse defaults and all endpoint hand combinations, including one actor',()=>{
 for(const action of actions)for(const settings of [{},defaults])for(const fromHand of ['near','far'])for(const toHand of ['near','far']){
  const r=request({settings,from:{actorId:'hero',hand:fromHand},to:{actorId:'hero',hand:toHand}});
  assert.deepEqual(contract.nativeStudioParameters(action,r),r);
 }
});

test('grip link identities, endpoints and settings are required strict bounded objects',()=>{
 for(const action of actions){
  const r=request({linkId:'l'.repeat(20),layerId:'l'.repeat(96),from:{actorId:'a'.repeat(96),hand:'near'}});
  assert.deepEqual(contract.nativeStudioParameters(action,r),r);
  for(const patch of [{linkId:undefined},{linkId:''},{linkId:'l'.repeat(21)},{linkId:'../rope'},{layerId:undefined},{layerId:'l'.repeat(97)},
   {settings:undefined},{settings:null},{settings:[]},{settings:{unknown:1}},{from:undefined},{to:undefined},{from:null},{to:[]},
   {kind:'rope'},{fps:30},{frameCount:240},{poses:[]},{speech:{}}])
   assert.throws(()=>contract.nativeStudioParameters(action,request(patch)),JSON.stringify(patch));
  for(const field of ['from','to'])for(const endpoint of [{},{actorId:'hero'},{hand:'near'},{actorId:'',hand:'near'},
   {actorId:'../hero',hand:'near'},{actorId:'a'.repeat(97),hand:'near'},{actorId:null,hand:'near'},
   {actorId:'hero',hand:'left'},{actorId:'hero',hand:null},{actorId:'hero',hand:0},{actorId:'hero',hand:'near',x:.5}])
   assert.throws(()=>contract.nativeStudioParameters(action,request({[field]:endpoint})),JSON.stringify(endpoint));
 }
});

for(const [field,min,max] of [['sagPx',0,64],['widthPx',.5,12]])test(`grip link ${field} accepts only finite native bounds`,()=>{
 for(const action of actions){
  for(const value of [min,max,(min+max)/2]){const r=request({settings:{[field]:value}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,'1',null,true])
   assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{[field]:value}})));
 }
});

test('grip link color uses eight digit ARGB without coercion or aliases',()=>{
 for(const action of actions){
  for(const color of ['#FF9D784F','#0088aAbB']){const r=request({settings:{color}});assert.deepEqual(contract.nativeStudioParameters(action,r),r);}
  for(const color of ['red','#9D784F','#GG9D784F',0,null,true])assert.throws(()=>contract.nativeStudioParameters(action,request({settings:{color}})));
 }
});

test('grip links require native 4.8.0 for both aliases and all mixed batch positions',()=>{
 const face={type:'add_face_animation',layerId:'actors',faceId:'face',settings:{}};
 for(const action of actions)for(const operations of [[link],[link,face],[face,link]]){
  const r={...request(),operations};
  for(const device of [undefined,{}, {appVersion:'unknown'},{appVersion:'4.7.8'}])
   assert.throws(()=>contract.requireStudioVersion(device,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*grip.*4\.8\.0/i);
  for(const appVersion of ['4.8.0','4.8.1','4.9.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,r));
 }
});

test('grip link relay rejects old APKs and invalid mixed batches before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='g'.repeat(43);
  const register=appVersion=>relay.appRegister('grip-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.8');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request()),/4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();
  const invalid={...link,to:{actorId:'hero',hand:'left'}};
  await assert.rejects(relay.appEnqueueV3(key,action,{...request(),operations:[link,invalid]}));assert.equal(storage.writes(),before);
  assert.deepEqual(JSON.parse(JSON.stringify((await relay.appEnqueueV3(key,action,request())).parameters)),request());
 }
});

test('grip link Java/Node metadata matches native defaults and documents exact path output and placement',()=>{
 const metadata=contract.GRIP_LINK_CAPABILITIES;assert.ok(metadata);assert.equal(metadata.minimumNativeVersion,'4.8.0');
 assert.equal(metadata.action,'sketch_edit');assert.equal(metadata.operation,'add_grip_link');assert.deepEqual(metadata.settings,defaults);
 assert.deepEqual(metadata.hands,['near','far']);assert.equal(metadata.maximumFrames,240);
 assert.deepEqual(metadata.settingLimits,{sagPx:[0,64],widthPx:[.5,12],color:'Eight-digit #AARRGGBB'});
 assert.deepEqual(metadata.budgets,{maximumBytes:4194304,maximumObjects:4096,maximumPoints:131072,maximumKeys:4096});
 assert.match(metadata.placement,/behind both actor layers/);assert.match(metadata.endpoints,/world.*layer.*actor.*transform/i);
 assert.match(metadata.motion,/every actual output frame.*no resampl/i);assert.match(metadata.output,/ordinary.*native paths.*shapeKeys/i);
 assert.match(metadata.editing,/rebake.*actor.*layer.*edits/i);assert.match(metadata.manualControls,/Human acting.*rope/i);
 assert.equal(metadata.clothPhysics,false);assert.equal(metadata.genuine3D,false);assert.equal(metadata.phoneExecutionVerified,false);
 class JSONObject {put(key,value){this[key]=value;return this;}}
 class JSONArray extends Array {constructor(values){super();this.push(...values);}}
 const source=native('SketchGripLink'),expression=source.match(/static JSONObject defaults\(\)throws Exception\s*\{\s*return ([\s\S]*?);\s*\}/)[1];
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${expression})`,{JSONObject}))),defaults);
 const java=native('SketchCapabilities'),body=java.match(/public static JSONObject gripLink\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)?.[1];assert.ok(body);
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${body.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]')})`,{JSONObject,JSONArray}))),metadata);
 assert.ok(java.includes('.put("gripLink",gripLink())'));
});

test('ordinary ChatGPT discovers grip link metadata, native gates and the unchanged operation path',async()=>{
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);let handler;
 const context={s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3:true,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[],CHARACTER_PERFORMANCE_CAPABILITIES:contract.CHARACTER_PERFORMANCE_CAPABILITIES,GRIP_LINK_CAPABILITIES:contract.GRIP_LINK_CAPABILITIES};
 vm.runInNewContext(worker.slice(start,end),context);const result=await handler();assert.ok(result.gripLink);assert.deepEqual(result.gripLink,contract.GRIP_LINK_CAPABILITIES);
 const detail=worker.match(/:action==='sketch_edit'\?'([^']+)'/)[1];
 for(const pattern of [/add_grip_link/,/sketch_capabilities\.gripLink/,/ordinary ChatGPT.*app_status/,/4\.8\.0/,/behind both actor layers/,/240/,/shapeKeys/])assert.match(detail,pattern);
 assert.match(native('SketchEditOps'),/case "add_grip_link":[\s\S]{0,100}SketchGripLink\.apply\(doc,op\)/);
 assert.match(native('SketchHumanDialog'),/put\("type","add_grip_link"\)/);
 for(const kind of ['rope','grip_link'])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',{
  ...request(),operations:[{type:'add_object',layerId:'links',object:{id:'rope',kind,bounds:{x:.1,y:.1,width:.3,height:.2}}}]
 }));
});
