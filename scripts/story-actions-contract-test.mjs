import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboard from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';
import {encodePacket,decodePacket} from './native-agent-mailbox.mjs';
import {nativeCommandSummary,nativeResultPage} from '../src/native-result-pages.js';
import {z} from 'zod';

const read=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const shot={shotId:'arrival',frameCount:120,operations:[{type:'add_human_animation',layerId:'layer_1',actorId:'hero',settings:{},preset:'walk',motion:{speed:.8},poseOverrides:{handOpen:.2}}]};
const plan={bookId:'story_v2',operationId:'plan_v2',name:'An original journey',width:1280,height:720,fps:30,shots:[shot]};
const audio={projectId:'voices',sourceProjectUpdatedAt:0,assetId:'master',operationId:'mix_v2',settings:{}};
const requests={sketch_story_create:plan,sketch_story_preview:{plan,shotId:'arrival',frame:119},sketch_story_audio_create:audio,sketch_story_example:{exampleId:'last_lantern'}};
const names=action=>[action,'studio_'+action];

test('story actions have closed Node aliases, strict parameters and native 4.8.0 gates',()=>{
 for(const [action,args] of Object.entries(requests))for(const name of names(action)){
  assert.ok(contract.STUDIO_NATIVE_ACTIONS.has(action));assert.equal(contract.nativeStudioCanonicalAction(name),action);
  assert.deepEqual(contract.nativeStudioParameters(name,args),args);
  assert.ok(storyboard.STORYBOARD_NATIVE_ACTIONS.has(name));
  for(const device of [undefined,{}, {appVersion:'unknown'},{appVersion:'4.7.9'}])assert.throws(()=>storyboard.requireStoryboardNativeVersion(device,name,args),/4\.8\.0/);
  for(const appVersion of ['4.8.0','4.8.1','5.0.0'])assert.doesNotThrow(()=>storyboard.requireStoryboardNativeVersion({appVersion},name,args));
  for(const field of Object.keys(args)){const bad={...args};delete bad[field];assert.throws(()=>contract.nativeStudioParameters(name,bad),field);}
  assert.throws(()=>contract.nativeStudioParameters(name,{...args,url:'file:///foreign'}));
 }
});

test('story plans validate shot identity, counts, camera clock and nested native operations',()=>{
 const check=(patch={})=>contract.nativeStudioParameters('sketch_story_create',{...plan,...patch});
 const r={...plan,bookId:'b'.repeat(48),shots:[{...shot,shotId:'s'.repeat(20),frameCount:1200,label:'l'.repeat(512),camera:[{frame:0,zoom:1},{frame:1199,x:.2,interpolation:'bezier',curve:[.2,0,.8,1]}]}]};
 assert.deepEqual(check(r),r);
 for(const patch of [{bookId:'b'.repeat(49)},{name:''},{width:255},{width:1281},{height:2049},{fps:25},{shots:[]},
  {shots:Array.from({length:65},(_,i)=>({...shot,shotId:'shot_'+i}))},{shots:[shot,shot]}])assert.throws(()=>check(patch));
 for(const patch of [{shotId:'s'.repeat(21)},{shotId:'../shot'},{frameCount:23},{frameCount:1201},{frameCount:'120'},
  {operations:[]},{operations:Array(129).fill(shot.operations[0])},{operations:[{...shot.operations[0],unknown:1}]},
  {camera:[{frame:120}]},{camera:[{frame:0},{frame:0}]},{camera:[{frame:0,zoom:0}]},{camera:[{frame:0,unknown:1}]},{label:'l'.repeat(513)},{unknown:1}])
  assert.throws(()=>check({shots:[{...shot,...patch}]}),JSON.stringify(patch));
});

test('story preview requires an existing shot and an integer frame inside its exact clock',()=>{
 for(const name of names('sketch_story_preview')){
  for(const patch of [{shotId:'unknown'},{frame:-1},{frame:120},{frame:.5},{frame:'0'},{maxDimension:512},{plan:{...plan,replace:true}}])
   assert.throws(()=>contract.nativeStudioParameters(name,{...requests.sketch_story_preview,...patch}));
  const r={...requests.sketch_story_preview,frame:0};assert.deepEqual(contract.nativeStudioParameters(name,r),r);
 }
});

test('story example accepts only the exact built-in identity and is dispatched through the shared read-only native helper',()=>{
 for(const name of names('sketch_story_example')){
  for(const args of [{},{exampleId:'unknown'},{exampleId:'LAST_LANTERN'},{exampleId:null},{exampleId:1},{exampleId:'last_lantern',save:true}])
   assert.throws(()=>contract.nativeStudioParameters(name,args));
 }
 const native=read('SketchStoryPlanOps'),service=read('ControlService');
 assert.match(service,/case "sketch_story_example":[\s\S]{0,160}SketchStoryPlanOps\.execute\(this,action,p\)\.put\("ok",true\)/);
 assert.match(native,/"sketch_story_example"\.equals\(action\)[\s\S]{0,450}return example\(context\)/);
 const helper=native.match(/public static JSONObject example\(Context context\)throws Exception \{([\s\S]*?)\n    \}/)?.[1];assert.ok(helper);
 for(const pattern of [/UUID\.randomUUID/,/put\("bookId"/,/put\("operationId"/,/put\("plan",plan\)/,/put\("dialogueControls",dialogue\)/,/put\("audioSettings",SketchStoryAudio\.validateSettings/ ,/put\("readOnly",true\)/,/put\("audioEmbedded",false\)/,/put\("dialogueGenerationNeedsConfiguredRunner",true\)/])assert.match(helper,pattern);
 assert.match(read('SketchStoryPlanDialog'),/SketchStoryPlanOps\.example\(activity\)/);
 assert.deepEqual(contract.STORY_PLAN_CAPABILITIES.examples,['last_lantern']);
 assert.match(contract.STORY_PLAN_CAPABILITIES.example,/fresh.*plan.*dialogueControls/);
});

test('MCP retrieves the complete story example plan and dialogue through retained result paging without client filesystem access',async()=>{
 class McpServer {constructor(){this.tools=new Map();}registerTool(name,definition,handler){this.tools.set(name,{definition,handler});}}
 const context={...storyboard,...contract,...queueBindings,nativeCommandSummary,nativeResultPage,z,McpServer,crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,
  DurableObject:class{constructor(ctx){this.ctx=ctx;}},createMcpHandler:()=>()=>new Response(),APP_HTML:'',STUDIO_RUNTIME_JS:'',STUDIO_CINEMATIC_JS:'',STUDIO_NEURAL_JS:'',STUDIO_TEMPORAL_JS:'',STUDIO_CHARACTER_JS:'',STUDIO_ACTION_TIMELINE_JS:'',STUDIO_POSE_SEQUENCE_JS:'',LOCAL_VIDEO_DOWNLOAD_HTML:''};
 vm.createContext(context);vm.runInContext(worker.replace(/^import .*$/gm,'').replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState').replace('export default {','globalThis.worker = {')+'\nglobalThis.nativeTools=serverForApp;',context);
 const storage=memoryStorage(new Map(),{maxValueBytes:128*1024}),relay=new context.VideoStudioState({storage},{}),owner='e'.repeat(43),device='example-device';
 await relay.appRegister(device,owner,{appVersion:'4.8.0',protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything'});
 const server=context.nativeTools({VIDEO_STATE:{getByName:()=>relay}},owner,3);
 const call=async(name,args)=>{const tool=server.tools.get(name);assert.ok(tool,name);return JSON.parse((await tool.handler(z.object(tool.definition.inputSchema).parse(args))).content[0].text);};
 const tool=server.tools.get('app_sketch_story_example');assert.ok(tool);assert.equal(tool.definition.annotations.readOnlyHint,true);
 const queued=await call('app_sketch_story_example',{exampleId:'last_lantern'});assert.equal(queued.queued,true);
 // Representative native receipt; native helper execution/fresh-ID semantics have their own Android tests.
 const plan=JSON.parse(fs.readFileSync(new URL('../android/app/src/main/assets/animation_stories/the-last-lantern.json',import.meta.url),'utf8'));
 plan.bookId='last_lantern_'+'a'.repeat(32);plan.operationId='author_'+'a'.repeat(32);
 const dialogueControls=JSON.parse(fs.readFileSync(new URL('../android/app/src/main/assets/animation_stories/the-last-lantern-dialogue-controls.json',import.meta.url),'utf8'));
 const audioSettings=JSON.parse(fs.readFileSync(new URL('../android/app/src/main/assets/animation_stories/the-last-lantern-audio-settings.json',import.meta.url),'utf8'));
 const receipt=JSON.parse(JSON.stringify({ok:true,exampleId:'last_lantern',plan,dialogueControls,audioSettings,readOnly:true,audioEmbedded:false,dialogueGenerationNeedsConfiguredRunner:true}));
 await relay.appCompleteV3(device,owner,queued.commandId,receipt,'completed');
 const result=await call('app_get_command_result',{commandId:queued.commandId});let recovered;
 if(result.resultPaged){
  const pages=[];for(let pageIndex=0;pageIndex<result.pageCount;pageIndex++)pages.push(await call('app_get_command_result_page',{commandId:queued.commandId,pageIndex}));
  const text=pages.map(page=>page.fragment).join('');assert.ok(pages.every(page=>page.viewSha256===result.viewSha256));
  assert.equal(Buffer.from(await webcrypto.subtle.digest('SHA-256',new TextEncoder().encode(text))).toString('hex'),result.viewSha256);recovered=JSON.parse(text);
 }else recovered=result.result;
 assert.deepEqual(recovered,receipt);assert.equal(recovered.plan.shots.length,16);assert.ok(recovered.dialogueControls.lines.length>0);
 assert.deepEqual(contract.nativeStudioParameters('sketch_story_create',recovered.plan),recovered.plan);
 assert.deepEqual(contract.nativeStudioParameters('open_pipeline_export_request',{projectId:'voices',sourceProjectUpdatedAt:0,requestId:'voices_v2',backend:'kokoro',assetIds:[],controls:recovered.dialogueControls}).controls,recovered.dialogueControls);
 assert.deepEqual(contract.nativeStudioParameters('sketch_story_audio_create',{...audio,settings:recovered.audioSettings}).settings,recovered.audioSettings);
});

test('story audio accepts native sparse defaults and exact strict mix settings',()=>{
 for(const name of names('sketch_story_audio_create')){
  for(const [field,min,max] of [['musicGain',0,1],['ambienceGain',0,1],['dialogueGain',0,2],['tempo',30,180],['seed',0,2147483647]]){
   for(const value of [min,max]){const r={...audio,settings:{[field]:value}};assert.deepEqual(contract.nativeStudioParameters(name,r),r);}
   for(const value of [min-.001,max+.001,NaN,Infinity,-Infinity,null,true,'1',...(field==='seed'?[1.5]:[])])
    assert.throws(()=>contract.nativeStudioParameters(name,{...audio,settings:{[field]:value}}));
  }
  for(const theme of ['warm','calm','tense'])for(const ambience of ['rain','wind','night','none']){
   const r={...audio,settings:{theme,ambience}};assert.deepEqual(contract.nativeStudioParameters(name,r),r);
  }
  for(const settings of [null,[],{theme:'happy'},{ambience:'forest'},{theme:null},{durationMs:120000},{unknown:1}])
   assert.throws(()=>contract.nativeStudioParameters(name,{...audio,settings}));
  for(const patch of [{name:'Mix'},{assetId:'_master'},{operationId:'-mix'},{projectId:'../voices'},{sourceProjectUpdatedAt:-1},{sourceProjectUpdatedAt:.5},{sourceProjectUpdatedAt:'0'}])
   assert.throws(()=>contract.nativeStudioParameters(name,{...audio,...patch}));
 }
});

test('story audio cues preserve input order, reject duplicate IDs and enforce natural tails within the maximum clock',()=>{
 const tails={footstep:180,wood:240,metal:600,cloth:350,chime:1200};
 const check=events=>contract.nativeStudioParameters('sketch_story_audio_create',{...audio,settings:{events}});
 for(const [type,tail] of Object.entries(tails)){
  const event={id:'event',type,atMs:600000-tail,intensity:1};assert.equal(check([event]).settings.events[0].atMs,event.atMs);
  assert.throws(()=>check([{...event,atMs:event.atMs+1}]));
  for(const patch of [{id:'_event'},{type:'speech'},{atMs:-1},{atMs:.5},{atMs:'0'},{intensity:-.001},{intensity:1.001},{intensity:'1'},{intensity:null},{durationMs:tail+1}])assert.throws(()=>check([{...event,...patch}]));
  for(const field of Object.keys(event)){const bad={...event};delete bad[field];assert.throws(()=>check([bad]));}
 }
 const events=[{id:'later',type:'wood',atMs:1000,intensity:.5},{id:'first',type:'cloth',atMs:0,intensity:0}];
 assert.deepEqual(check(events).settings.events,events);assert.deepEqual(check([]).settings.events,[]);
 assert.throws(()=>check([events[0],events[0]]));
 const many=Array.from({length:512},(_,i)=>({...events[0],id:'event_'+i}));assert.equal(check(many).settings.events.length,512);assert.throws(()=>check([...many,{...events[1]}]));
});

test('explicit story audio event duration obeys its native kind bounds through both routes',()=>{
 for(const name of names('sketch_story_audio_create'))for(const [type,max] of Object.entries({footstep:180,wood:240,metal:600,cloth:350,chime:1200})){
  for(const durationMs of [1,max]){
   const event={id:'cue',type,atMs:600000-durationMs,intensity:.5,durationMs},args={...audio,settings:{events:[event]}};
   assert.deepEqual(contract.nativeStudioParameters(name,args),args);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},name,args));
   assert.throws(()=>contract.nativeStudioParameters(name,{...args,settings:{events:[{...event,atMs:event.atMs+1}]}}));
  }
  for(const durationMs of [0,-1,max+1,1.5,'167',null,true,NaN,Infinity,{},[]])assert.throws(()=>contract.nativeStudioParameters(name,{...audio,settings:{events:[{id:'cue',type,atMs:0,intensity:1,durationMs}]}}));
 }
});

test('explicit 167ms final footstep retains the original master clock with no source duration injection',()=>{
 const event={id:'last_step',type:'footstep',atMs:123833,intensity:.7,durationMs:167};assert.equal(event.atMs+event.durationMs,124000);
 for(const name of names('sketch_story_audio_create')){
  const args={...audio,settings:{events:[event]}};assert.deepEqual(contract.nativeStudioParameters(name,args),args);
  assert.throws(()=>contract.nativeStudioParameters(name,{...args,settings:{...args.settings,durationMs:124000}}));
 }
});

test('omitted story audio event durations retain exact legacy payloads and request fingerprints',async()=>{
 for(const name of names('sketch_story_audio_create'))for(const event of [{id:'old',type:'footstep',atMs:0,intensity:.5},{id:'explicit',type:'wood',atMs:1000,intensity:1,durationMs:100}]){
  const args={...audio,settings:{events:[event]}},parsed=contract.nativeStudioParameters(name,args);assert.deepEqual(parsed,args);
  assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(args));
 }
});

test('event duration metadata describes native envelope control without retiming source PCM',()=>{
 const metadata=contract.STORY_AUDIO_CAPABILITIES;assert.match(metadata.eventDurationControls,/optional.*integer.*1.*eventTailMs/i);
 assert.match(metadata.eventDurationControls,/omit.*unchanged.*field/i);assert.match(metadata.eventDurationControls,/envelope.*source PCM.*unchanged/i);
 assert.match(worker,/durationMs.*167.*124000/);assert.match(read('SketchStoryAudio'),/e\.has\("durationMs"\).*integer\(e,"durationMs",1,LENGTHS\[kind\]\)/);
});

test('real relay rejects unsupported story APKs and malformed requests before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const [action,args] of Object.entries(requests))for(const name of names(action)){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='s'.repeat(43);
  const register=appVersion=>relay.appRegister('story-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.9');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,args),/4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,{...args,unknown:true}));assert.equal(storage.writes(),before);
  assert.deepEqual(JSON.parse(JSON.stringify((await relay.appEnqueueV3(key,name,args)).parameters)),args);
 }
});

test('asynchronous story writes retain jobResult receipts and never present queued admission as completed work',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),owner='j'.repeat(43),device='story-jobs-device';
 await relay.appRegister(device,owner,{appVersion:'4.8.0',protocolVersion:3,appGeneration:1,permissionMode:'everything'});
 const placement={animationId:'scene',expectedAnimationRevision:1,bookId:'story',expectedBookRevision:1,sourceFrameStart:0,frameCount:2,destinationPageStart:0,operationId:'place'};
 for(const [action,parameters,jobResult] of [
  ['sketch_story_create',plan,{ok:true,bookId:plan.bookId,shots:[{shotId:'arrival'}],editable:true}],
  ['sketch_story_audio_create',audio,{ok:true,projectId:'mix',assetId:'audio',sha256:'a'.repeat(64),audioOnly:true}],
  ['sketch_animation_place_in_book',placement,{ok:true,bookId:'story',pages:[{page:0,sketchId:'story_p0'},{page:1,sketchId:'story_p1'}],editable:true}]
 ]){
  const command=await relay.appEnqueueV3(owner,action,parameters),jobId=webcrypto.randomUUID();
  const admission={ok:true,queued:true,jobId,durableRecovery:true};
  assert.equal(queueBindings.commandExecution({status:'completed',result:admission}).workState,'awaiting_job');
  const result={...admission,queued:false,completed:true,jobState:'completed',jobResult};
  await relay.appCompleteV3(device,owner,command.id,result,'completed');
  const receipt=await relay.appCommandV3(owner,command.id);assert.deepEqual(receipt.result.jobResult,jobResult);
  assert.equal(queueBindings.commandExecution(receipt).workState,'completed');assert.equal(receipt.result.queued,false);
 }
});

test('bundled 124-second native story crosses strict schemas, chunked queue storage and encrypted mailbox intact',async()=>{
 const plan=JSON.parse(fs.readFileSync(new URL('../android/app/src/main/assets/animation_stories/the-last-lantern.json',import.meta.url),'utf8'));
 assert.equal(plan.shots.length,16);assert.equal(plan.shots.reduce((n,shot)=>n+shot.frameCount,0)*1000/plan.fps,124000);
 const preview={plan,shotId:plan.shots[0].shotId,frame:0};
 assert.deepEqual(contract.nativeStudioParameters('sketch_story_create',plan),plan);
 assert.deepEqual(contract.nativeStudioParameters('sketch_story_preview',preview),preview);
 const limits=contract.STORY_PLAN_CAPABILITIES.transport;assert.ok(limits);assert.equal(limits.maximumParametersBytes,512*1024);assert.equal(limits.nativePlanMaximumBytes,2*1024*1024);
 assert.match(limits.largePlans,/no.*archive.*reference/i);
 assert.ok(Buffer.byteLength(JSON.stringify(preview))<limits.maximumParametersBytes);
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 const storage=memoryStorage(new Map(),{maxValueBytes:128*1024}),relay=new context.VideoStudioState({storage},{}),owner='t'.repeat(43);
 await relay.appRegister('story-asset-device',owner,{appVersion:'4.8.0',protocolVersion:3,appGeneration:1,permissionMode:'everything'});
 for(const [action,parameters] of [['sketch_story_create',plan],['sketch_story_preview',preview]]){
  const queued=await relay.appEnqueueV3(owner,action,parameters),rows=await queueBindings.readCommandQueue(storage,'app-v3-cl:story-asset-device');
  // JSON serializes authored -0 camera coordinates as 0 at both transport boundaries.
  const wireParameters=JSON.parse(JSON.stringify(parameters));assert.deepEqual(rows.at(-1).parameters,wireParameters);
  const now=Date.now(),packet={schema:'videostudio.agent.commands.v1',packetId:queued.id,issuedAtMs:now,expiresAtMs:now+60000,commands:[{id:queued.id,action,parameters}]};
  assert.deepEqual(decodePacket(owner,encodePacket(owner,packet,{now}),{now}).commands[0].parameters,wireParameters);
 }
 const before=storage.writes();await assert.rejects(relay.appEnqueueV3(owner,'sketch_story_create',{...plan,name:'x'.repeat(512*1024)}),/512 KiB/);assert.equal(storage.writes(),before);
});

test('all story actions are admitted by the native alias/mailbox and dispatched off the main thread with successful envelopes',()=>{
 const bridge=read('NativeWorkbenchBridge'),mailbox=read('NativeAgentMailboxActions'),policy=read('NativeCommandExecutionPolicy'),service=read('ControlService');
 for(const action of Object.keys(requests)){
  assert.ok(bridge.includes('"'+action+'"'),action+' bridge');assert.ok(mailbox.includes('"'+action+'"'),action+' mailbox');
  assert.ok(policy.includes('case "'+action+'":'),action+' background');assert.ok(service.includes('case "'+action+'":'),action+' dispatch');
 }
 for(const action of ['sketch_story_create','sketch_story_audio_create','sketch_animation_place_in_book'])
  assert.match(service,new RegExp(`case "${action}":[\\s\\S]{0,180}complete\\(command,queueSketchStoryWork\\(action,p\\)\\)`));
 assert.match(service,/case "sketch_story_preview":[\s\S]{0,100}previewSketchStory\(p\)/);
 const preview=service.match(/private JSONObject previewSketchStory\(JSONObject args\) throws Exception \{([\s\S]*?)\n    \}/)?.[1];assert.ok(preview);
 assert.match(preview,/SketchStoryPlanOps\.execute\(this,"sketch_story_preview",args\)/);assert.match(preview,/remove\("document"\)/);
 assert.match(preview,/SketchFrameRenderer/);assert.match(preview,/renderer\.render\(0,true,/);assert.match(preview,/1024\s*\*\s*1024/);
 assert.match(preview,/put\("contactSheet"/);assert.match(preview,/put\("ok",true\)/);assert.match(preview,/finally[\s\S]*recycle/);
 assert.match(bridge,/put\("storyPlanMinimumNativeVersion","4\.8\.0"\)/);assert.match(bridge,/put\("storyAudioMinimumNativeVersion","4\.8\.0"\)/);
 const adapter=service.match(/private JSONObject queueSketchStoryWork\(String action,JSONObject input\) throws Exception \{([\s\S]*?)\n    \}/)?.[1];assert.ok(adapter);
 assert.match(adapter,/submitRecoverableHeavy/);assert.match(adapter,/state\.setResult\(result\.put\("ok",true\)\)/);
 assert.match(adapter,/put\("queued",true\).*put\("jobId",job.id\)/);
 assert.match(contract.STORY_PLAN_CAPABILITIES.execution,/cancellable.*jobResult/i);assert.match(contract.STORY_AUDIO_CAPABILITIES.execution,/cancellable.*jobResult/i);
});

test('story capability metadata matches Java literals and is discoverable through the live app capability handler',async()=>{
 class JSONObject {put(key,value){this[key]=value;return this;}}
 class JSONArray extends Array {constructor(values=[]){super();this.push(...values);}}
 const java=read('SketchCapabilities');
 for(const [exportName,method] of [['STORY_PLAN_CAPABILITIES','storyPlan'],['STORY_AUDIO_CAPABILITIES','storyAudio']]){
  const metadata=contract[exportName];assert.ok(metadata);assert.equal(metadata.minimumNativeVersion,'4.8.0');
  assert.equal(metadata.phoneExecutionVerified,false);assert.equal(metadata.creativeQualityCertified,false);
  const body=java.match(new RegExp(`public static JSONObject ${method}\\(\\) throws Exception \\{\\s*return ([\\s\\S]*?);\\s*\\}`))?.[1];assert.ok(body);
  assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${body.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]')})`,{JSONObject,JSONArray}))),metadata);
  assert.ok(java.includes(`.put("${method}",${method}())`));
 }
 const source=read('SketchStoryAudio'),expression=source.match(/public static JSONObject defaults\(\)throws Exception\s*\{\s*return ([\s\S]*?);\s*\}/)[1];
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${expression})`,{JSONObject,JSONArray}))),contract.STORY_AUDIO_CAPABILITIES.settings);
 assert.match(contract.STORY_AUDIO_CAPABILITIES.source,/owned.*24.*mono.*PCM16/i);assert.match(contract.STORY_AUDIO_CAPABILITIES.clock,/unchanged.*sample/i);
 assert.match(contract.STORY_PLAN_CAPABILITIES.publication,/recoverable.*partial.*identical/i);
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);let handler;
 vm.runInNewContext(worker.slice(start,end),{s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3:true,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[],CHARACTER_PERFORMANCE_CAPABILITIES:contract.CHARACTER_PERFORMANCE_CAPABILITIES,STORY_PLAN_CAPABILITIES:contract.STORY_PLAN_CAPABILITIES,STORY_AUDIO_CAPABILITIES:contract.STORY_AUDIO_CAPABILITIES});
 const result=await handler();assert.deepEqual(result.storyPlan,contract.STORY_PLAN_CAPABILITIES);assert.deepEqual(result.storyAudio,contract.STORY_AUDIO_CAPABILITIES);
 for(const action of Object.keys(requests))assert.ok(worker.includes(`:action==='${action}'?'`));
});
