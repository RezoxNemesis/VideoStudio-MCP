import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto,createHash} from 'node:crypto';
import {z} from 'zod';
import * as contract from '../src/storyboard-contract.js';
import * as studio from '../src/studio-workbench-contract.js';
import {nativeCommandSummary,nativeResultPage} from '../src/native-result-pages.js';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const field='maxOutputSampleDelayMs';
const legacy=()=>({aspect:'16:9',quality:'720p',fileName:'unchanged.mp4',projectId:'project-1'});
const request=(value=60000)=>({...legacy(),[field]:value});
const sha=value=>createHash('sha256').update(typeof value==='string'?value:JSON.stringify(value)).digest('hex');
async function fixture(version='4.10.25'){
 const rows=new Map(),storage=memoryStorage(rows,{maxValueBytes:128*1024});
 class McpServer{constructor(){this.tools=new Map();}registerTool(name,definition,handler){this.tools.set(name,{definition,handler});}}
 const context={...contract,...studio,...queueBindings,nativeCommandSummary,nativeResultPage,z,McpServer,crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,
  DurableObject:class{constructor(ctx){this.ctx=ctx;}},createMcpHandler:()=>()=>new Response(),APP_HTML:'',STUDIO_RUNTIME_JS:'',STUDIO_CINEMATIC_JS:'',STUDIO_NEURAL_JS:'',STUDIO_TEMPORAL_JS:'',STUDIO_CHARACTER_JS:'',STUDIO_ACTION_TIMELINE_JS:'',STUDIO_POSE_SEQUENCE_JS:'',LOCAL_VIDEO_DOWNLOAD_HTML:''};
 vm.createContext(context);vm.runInContext(source.replace(/^import .*$/gm,'').replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState').replace('export default {','globalThis.worker = {')+'\nglobalThis.nativeTools=serverForApp;globalThis.compatibilityTools=serverFor;',context);
 const relay=new context.VideoStudioState({storage},{}),owner='w'.repeat(43),deviceId='output-wait-native-device';
 await relay.appRegister(deviceId,owner,{appVersion:version,protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything'});
 const env={VIDEO_STATE:{getByName:()=>relay}},server=context.nativeTools(env,owner,3),compatibility=context.compatibilityTools(env);
 const call=async(name,args)=>{const tool=(name.startsWith('app_')?server:compatibility).tools.get(name);assert.ok(tool,name);const schema=typeof tool.definition.inputSchema.parse==='function'?tool.definition.inputSchema:z.object(tool.definition.inputSchema);return JSON.parse((await tool.handler(schema.parse(args))).content[0].text);};
 return{rows,storage,relay,owner,deviceId,server,call,commands:()=>queueBindings.readCommandQueue(storage,'app-v3-cl:'+deviceId)};
}

test('existing export_project validates only explicit bounded integer wait and retains unrelated legacy bytes',()=>{
 for(const value of [25000,25001,60000,119999,120000])assert.deepEqual(contract.nativeStoryboardParameters('export_project',request(value)),request(value));
 for(const value of [0,-1,24999,120001,25000.5,'60000',true,false,null,undefined,NaN,Infinity]){
  const input=request();input[field]=value;assert.throws(()=>contract.nativeStoryboardParameters('export_project',input));
 }
 const old={...legacy(),legacyFlag:{kept:true}};assert.deepEqual(contract.nativeStoryboardParameters('export_project',old),old);
 assert.deepEqual(contract.nativeStoryboardParameters('export_project',{...old,[field]:60000}),{...old,[field]:60000});
 for(const action of ['autonomous_edit','prompt_video','get_state','sketch_render'])assert.throws(()=>contract.nativeStoryboardParameters(action,{[field]:60000}));
});

test('every explicit wait including25000 requires4.10.25 without raising omitted export floors',()=>{
 for(const value of [25000,60000,120000])for(const appVersion of [undefined,'invalid','3.7.0','4.10.24'])
  assert.throws(()=>studio.requireStudioVersion({appVersion},'export_project',request(value)),/4\.10\.25/);
 for(const appVersion of ['4.10.25','4.10.26','5.0.0'])assert.doesNotThrow(()=>studio.requireStudioVersion({appVersion},'export_project',request()));
 assert.doesNotThrow(()=>studio.requireStudioVersion({appVersion:'3.7.0'},'export_project',legacy()));
 assert.doesNotThrow(()=>studio.requireStudioVersion({appVersion:'4.8.0'},'sketch_restore_archive',{opaque:{[field]:60000}}));
});

test('native typed tool direct queues and compatibility render/batch preserve the exact chosen wait',async()=>{
 const f=await fixture();
 await f.relay.appEnqueueV3(f.owner,'export_project',request());await f.relay.appEnqueue(f.owner,'export_project',request());
 assert.equal((await f.call('app_export_project',{aspect:'16:9',quality:'720p',fileName:'unchanged.mp4',[field]:60000})).queued,true);
 for(const[name,args]of[
  ['queue_video_edit',{deviceId:f.owner,projectId:'active-native',action:'render',parameters:request()}],
  ['queue_video_edit_batch',{deviceId:f.owner,projectId:'active-native',edits:[{action:'render',parameters:request()}]}]
 ])assert.equal((await f.call(name,args)).queued,true,name);
 const all=[...await f.commands(),...await queueBindings.readCommandQueue(f.storage,'app-cl:'+f.deviceId)];assert.equal(all.length,5);
 for(const command of all){assert.equal(command.action,'export_project');assert.equal(command.parameters[field],60000);}
 const typed=f.server.tools.get('app_export_project').definition.inputSchema;
 const json=z.toJSONSchema(typeof typed.parse==='function'?typed:z.object(typed),{io:'input'});
 assert.equal(json.properties[field].type,'integer');assert.equal(json.properties[field].minimum,25000);assert.equal(json.properties[field].maximum,120000);
});

test('all actual old-native export routes reject before queue or sequence writes',async()=>{
 const f=await fixture('4.10.24'),before=f.storage.writes();
 await assert.rejects(f.relay.appEnqueueV3(f.owner,'export_project',request()),/4\.10\.25/);
 await assert.rejects(f.relay.appEnqueue(f.owner,'export_project',request()),/4\.10\.25/);
 for(const[name,args]of[
  ['app_export_project',{[field]:60000}],
  ['queue_video_edit',{deviceId:f.owner,projectId:'active-native',action:'render',parameters:request()}],
  ['queue_video_edit_batch',{deviceId:f.owner,projectId:'active-native',edits:[{action:'render',parameters:request()}]}]
 ]){const value=await f.call(name,args);assert.equal(value.queued,false,name);assert.match(value.error,/4\.10\.25/);}
 assert.equal(f.storage.writes(),before);assert.deepEqual(await f.commands(),[]);
});

test('compatibility enqueueNative guards explicit export options even before invoking a stale queue adapter',async()=>{
 const f=await fixture('4.10.24');let delegated=0;
 f.relay.appEnqueueV3=async()=>{delegated++;return{id:'adapter',seq:1,action:'export_project'};};
 for(const[name,args]of[
  ['queue_video_edit',{deviceId:f.owner,projectId:'active-native',action:'render',parameters:request()}],
  ['queue_video_edit_batch',{deviceId:f.owner,projectId:'active-native',edits:[{action:'render',parameters:request()}]}]
 ]){const value=await f.call(name,args);assert.equal(value.queued,false,name);assert.match(value.error,/4\.10\.25/);}
 assert.equal(delegated,0);
});

test('offline explicit export remains durable native without resolving or silently using a browser render',async()=>{
 const f=await fixture(),device=await queueBindings.readNativeDevice(f.storage,'app-device:'+f.deviceId);let fallbacks=0;
 await queueBindings.writeNativeDevice(f.storage,'app-device:'+f.deviceId,{...device,lastSeenAt:'2020-01-01T00:00:00Z'});
 f.relay.appTryStudioWebFallback=async()=>{fallbacks++;throw new Error('Explicit native wait must not resolve a web render');};
 const queued=await f.relay.appEnqueueV3(f.owner,'export_project',request());
 assert.equal(queued.status,'waiting_native');assert.equal(fallbacks,0);assert.deepEqual((await f.commands())[0].parameters,request());
});

test('direct web fallback and browser render reject native-only wait before accessing a web project',async()=>{
 const f=await fixture(),before=f.storage.writes();let lookups=0;
 f.relay.appResolveStudioWebFallback=async()=>{lookups++;throw new Error('No fallback lookup permitted');};
 assert.equal(await f.relay.appTryStudioWebFallback(f.owner,'export_project',request()),null);
 f.relay.project=async()=>{lookups++;throw new Error('No browser lookup permitted');};
 await assert.rejects(f.relay.enqueue('browser-id','project-id','render',request()),/native VideoStudio/);
 assert.equal(lookups,0);assert.equal(f.storage.writes(),before);
});

test('omitted wait keeps legacy normalization typed defaults action inventory and offline fallback',async()=>{
 assert.equal(sha(contract.nativeStoryboardParameters('export_project',legacy())),'2aa5df1fc1e45bbc4a705e3078ef4d189513a673308170707c5fa3c66743544e');
 const cap={...studio.EDITOR_EDITING_CAPABILITIES};delete cap.exportOutputWait;assert.equal(sha(cap),'34b4c480aea6e59d506cc534b8caebd5acf799a3505d89f8183328e3b18847fa');
 assert.equal(sha([...studio.STUDIO_NATIVE_ACTIONS].sort().join('\n')),'42e3cf3dac926f3c60760d13a15094297c0689b6073c6c837d76dff68b2f9f7f');assert.equal(studio.STUDIO_NATIVE_ACTIONS.has('export_project'),false);
 const f=await fixture('3.7.0');assert.equal((await f.call('app_export_project',{aspect:'16:9',quality:'720p',fileName:'unchanged.mp4'})).queued,true);
 assert.deepEqual((await f.commands())[0].parameters,{aspect:'16:9',quality:'720p',fileName:'unchanged.mp4'});
 const device=await queueBindings.readNativeDevice(f.storage,'app-device:'+f.deviceId);await queueBindings.writeNativeDevice(f.storage,'app-device:'+f.deviceId,{...device,lastSeenAt:'2020-01-01T00:00:00Z'});let count=0;
 f.relay.appTryStudioWebFallback=async()=>{count++;return{id:'legacy-browser',seq:1,hybridRoute:'studio_web'};};
 const old=await f.relay.appEnqueueV3(f.owner,'export_project',legacy());assert.equal(old.hybridRoute,'studio_web');assert.equal(count,1);
});

test('native and Node capability metadata expose the same bounded explicit opt-in and no disable/success guarantee',async()=>{
 const f=await fixture(),capabilities=await f.call('app_capabilities',{}),cap=studio.EDITOR_EDITING_CAPABILITIES.exportOutputWait;
 assert.ok(cap);assert.equal(cap.minimumNativeVersion,'4.10.25');assert.equal(cap.action,'export_project');assert.equal(cap.field,field);
 assert.equal(cap.minimumMs,25000);assert.equal(cap.maximumMs,120000);assert.equal(cap.integerOnly,true);assert.equal(cap.explicitOnly,true);
 assert.equal(cap.defaultUnchanged,true);assert.equal(cap.timeoutCanBeDisabled,false);assert.deepEqual(cap.manualChoicesMs,[60000,120000]);
 assert.equal(cap.sourceUnchanged,true);assert.equal(cap.previewUnchanged,true);assert.match(cap.limitations,/no.*(?:success|stall)/i);
 assert.deepEqual(capabilities.studioEditing.exportOutputWait,cap);
 const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/StudioEditingCapabilities.java',import.meta.url),'utf8');
 const literal=java.match(/CAPABILITY_JSON\s*=\s*("(?:\\.|[^"\\])*")/);assert.ok(literal);assert.deepEqual(JSON.parse(JSON.parse(literal[1])),studio.EDITOR_EDITING_CAPABILITIES);
 const description=f.server.tools.get('app_export_project').definition.description;assert.match(description,/4\.10\.25/);assert.match(description,/25000/);assert.match(description,/120000/);assert.match(description,/default/i);
});

test('hybrid native export binding applies identical strict wait and version floor',async()=>{
 for(const version of ['4.10.24','4.10.25']){
  const f=await fixture(version),browser='export-wait-browser';await f.relay.register(browser);const challenge=await f.relay.appCreateHybridBinding(f.owner,browser);const binding=await f.relay.appRedeemHybridBinding(challenge.token,browser);const before=f.storage.writes();
  await assert.rejects(f.relay.appEnqueueHybrid(binding.hybridKey,'export_project',request('60000')));assert.equal(f.storage.writes(),before);
  if(version==='4.10.24'){await assert.rejects(f.relay.appEnqueueHybrid(binding.hybridKey,'export_project',request()),/4\.10\.25/);assert.equal(f.storage.writes(),before);}
  else{const result=await f.relay.appEnqueueHybrid(binding.hybridKey,'export_project',request());assert.equal(result.action,'export_project');assert.deepEqual(result.parameters,request());}
 }
});
