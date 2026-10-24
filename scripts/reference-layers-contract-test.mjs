import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {z} from 'zod';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import {nativeCommandSummary,nativeResultPage} from '../src/native-result-pages.js';
import * as storyboard from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const action='sketch_import_reference_layers', previewAction='sketch_reference_layers_preview';
const names=[action,'studio_'+action,previewAction,'studio_'+previewAction];
const triangle=[[0,0],[1,0],[0,1]], rings=[triangle];
const mask={maskId:'head',layerId:'head_layer',objectId:'head_image',name:'Head',rings};
const request={sketchId:'owned',expectedRevision:1,operationId:'separate',sourceObjectId:'guide',expectedReferenceSha256:'a'.repeat(64),expectedSourceSha256:'b'.repeat(64),frame:0,exposure:96,hideSource:true,masks:[mask]};
const provenance={version:1,method:'source_assisted',sourceReferenceSha256:'a'.repeat(64),sourceSha256:'b'.repeat(64),operationId:'separate',maskId:'head',complement:false,rings,maskSha256:'c'.repeat(64)};
const reference={file:'d'.repeat(64)+'.png',sha256:'d'.repeat(64),width:320,height:240,sourceProjectId:'project',sourceAssetId:'asset',sourceWidth:640,sourceHeight:480,sourceOrientation:1,sourceSha256:'b'.repeat(64)};
const image=sourceMask=>({id:'part',kind:'image',bounds:{x:0,y:0,width:1,height:1},reference:{...reference,...(sourceMask===undefined?{}:{sourceMask})}});
const edit=object=>({sketchId:'owned',expectedRevision:1,operationId:'paste',operations:[{type:'add_object',layerId:'parts',object}]});
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');

test('reference layers canonical and alias contracts preserve all explicit arguments without defaults',()=>{
 for(const name of names){
  assert.equal(contract.nativeStudioCanonicalAction(name),name.replace(/^studio_/,''));
  for(const hideSource of [false,true])for(const background of [undefined,{layerId:'background_layer',objectId:'background_image',name:'Background'}]){
   const args={...request,hideSource,...(background?{background}:{})};assert.deepEqual(contract.nativeStudioParameters(name,args),args);
  }
 }
});

test('import and read-only preview share the exact optional integer edge radius contract',()=>{
 for(const name of names){
  assert.deepEqual(contract.nativeStudioParameters(name,request),request);
  for(const edgeSnapRadius of [0,1,16]){const args={...request,edgeSnapRadius};assert.deepEqual(contract.nativeStudioParameters(name,args),args);}
  for(const edgeSnapRadius of [-1,17,.5,'0',true,null,NaN,Infinity])assert.throws(()=>contract.nativeStudioParameters(name,{...request,edgeSnapRadius}));
 }
 assert.deepEqual(Object.keys(contract.studioToolSchemas[action]),Object.keys(contract.studioToolSchemas[previewAction]));
});

test('reference layers reject missing fields, coercion, untrusted data and nested extras',()=>{
 for(const name of names){
  for(const field of Object.keys(request)){const args={...request};delete args[field];assert.throws(()=>contract.nativeStudioParameters(name,args),field);}
  for(const patch of [{hideSource:'false'},{hideSource:0},{hideSource:null},{sourceObjectId:'../guide'},{expectedReferenceSha256:'A'.repeat(64)},{expectedSourceSha256:'bad'},{expectedRevision:0},{frame:1199,exposure:2},{exposure:'96'},{sourceUrl:'https://example.test/image'},{sourcePath:'/private.png'},{semanticAI:true},{masks:[]},{masks:Array(9).fill(mask)},{background:{layerId:'b',objectId:'bi',name:'B',rings}}])assert.throws(()=>contract.nativeStudioParameters(name,{...request,...patch}));
  for(const field of Object.keys(mask)){const part={...mask};delete part[field];assert.throws(()=>contract.nativeStudioParameters(name,{...request,masks:[part]}));}
  for(const part of [{...mask,name:''},{...mask,unknown:true},{...mask,rings:[]},{...mask,rings:Array(9).fill(triangle)},{...mask,rings:[triangle.slice(0,2)]},{...mask,rings:[Array(65).fill([0,0])]},{...mask,rings:[[[0,0],[1,0],[0,Infinity]]]},{...mask,rings:[[[0,0],[1,0],[0,1.01]]]},{...mask,rings:[[[0,0,0],[1,0],[0,1]]]}])assert.throws(()=>contract.nativeStudioParameters(name,{...request,masks:[part]}));
  const getter={...request};Object.defineProperty(getter,'hideSource',{enumerable:true,get(){throw new Error('getter executed');}});assert.throws(()=>contract.nativeStudioParameters(name,getter),/accessors/);
  const polluted=JSON.parse(JSON.stringify(request));polluted.masks[0].__proto__={injected:true};assert.throws(()=>contract.nativeStudioParameters(name,polluted),/prototype/);
 }
});

test('reference layers reject duplicate output identities and source collisions',()=>{
 const other={...mask,maskId:'body',layerId:'body_layer',objectId:'body_image'};
 for(const field of ['maskId','layerId','objectId'])assert.throws(()=>contract.nativeStudioParameters(action,{...request,masks:[mask,{...other,[field]:mask[field]}]}));
 assert.throws(()=>contract.nativeStudioParameters(action,{...request,masks:[{...mask,objectId:request.sourceObjectId}]}));
 for(const field of ['layerId','objectId'])assert.throws(()=>contract.nativeStudioParameters(action,{...request,background:{layerId:'b',objectId:'bi',name:'Background',[field]:mask[field]}}));
});

test('reference layer shape limits admit eight masks with eight rings and 64 points without changing coordinates',()=>{
 const ring=Array.from({length:64},(_,i)=>[.5+.4*Math.cos(i*Math.PI/32),.5+.4*Math.sin(i*Math.PI/32)]);
 const masks=Array.from({length:8},(_,i)=>({...mask,maskId:'mask_'+i,layerId:'layer_'+i,objectId:'image_'+i,rings:Array(8).fill(ring)}));
 const args={...request,frame:1199,exposure:1,masks};assert.deepEqual(contract.nativeStudioParameters(action,args),args);
 // This is transport shape admission: native owns polygon topology and pixel overlap validation.
});

test('sourceMask is strict, binds original source hash and limits part versus complement rings',()=>{
 assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(image(provenance))),edit(image(provenance)));
 for(const field of Object.keys(provenance)){const p={...provenance};delete p[field];assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(image(p))),field);}
 for(const patch of [{version:2},{method:'semantic_ai'},{complement:'false'},{unknown:true},{sourceSha256:'e'.repeat(64)},{sourceReferenceSha256:'bad'},{maskSha256:'bad'},{rings:[]},{rings:Array(9).fill(triangle)}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(image({...provenance,...patch}))));
 const background={...provenance,complement:true,rings:Array(64).fill(triangle)};
 assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(image(background))),edit(image(background)));
 assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(image({...background,rings:Array(65).fill(triangle)}))));
 const missingSource=image(provenance);delete missingSource.reference.sourceSha256;assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(missingSource)));
});

test('all explicit provenance operation paths and mixed batches require native 4.8.5',()=>{
 const full={...image(provenance),frame:0,exposure:1,color:'#FF000000',width:.001,fill:true,keys:[],transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1}};
 const ops=[{type:'add_object',layerId:'parts',object:image(provenance)},{type:'add_layer',layer:{id:'parts',objects:[full]}}];
 const line={type:'add_object',layerId:'parts',object:{id:'line',kind:'line',points:[[0,0,1],[1,1,1]]}};
 for(const op of ops){
  const operations=[line,op];
  const plan={bookId:'book',operationId:'story',name:'Story',width:720,height:1280,fps:24,shots:[{shotId:'shot',frameCount:96,operations}]};
  for(const [name,args] of [['sketch_edit',{...edit(image(provenance)),operations}],['sketch_story_create',plan],['sketch_story_preview',{plan,shotId:'shot',frame:0}]])for(const prefix of ['','studio_']){
   assert.deepEqual(contract.nativeStudioParameters(prefix+name,args),args);
   for(const appVersion of ['4.8.4','4.0.0'])assert.throws(()=>contract.requireStudioVersion({appVersion},prefix+name,args),/4\.8\.5/);
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.5'},prefix+name,args));
  }
 }
 for(const name of names){assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.4'},name,request),/4\.8\.5/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.5'},name,request));}
});

test('omitted reference provenance preserves old fingerprints and old native floors',()=>{
 const args=edit(image());assert.deepEqual(contract.nativeStudioParameters('sketch_edit',args),args);
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.0.1'},'sketch_edit',args));
 const old={sketchId:'owned',expectedRevision:1,operationId:'import',projectId:'p',sourceProjectUpdatedAt:1,assetId:'a',layerId:'l',objectId:'o'};
 assert.deepEqual(contract.nativeStudioParameters('sketch_import_reference',old),old);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},'sketch_import_reference',old));
 for(const name of ['sketch_component_apply','sketch_restore_archive'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.4'},name));
});

function runtime(){
 class McpServer{constructor(){this.tools=new Map();}registerTool(name,definition,handler){this.tools.set(name,{definition,handler});}}
 const context={...storyboard,...contract,...queueBindings,nativeCommandSummary,nativeResultPage,z,McpServer,crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},createMcpHandler:()=>()=>new Response(),APP_HTML:'',STUDIO_RUNTIME_JS:'',STUDIO_CINEMATIC_JS:'',STUDIO_NEURAL_JS:'',STUDIO_TEMPORAL_JS:'',STUDIO_CHARACTER_JS:'',STUDIO_ACTION_TIMELINE_JS:'',STUDIO_POSE_SEQUENCE_JS:'',LOCAL_VIDEO_DOWNLOAD_HTML:''};
 vm.createContext(context);vm.runInContext(worker.replace(/^import .*$/gm,'').replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState').replace('export default {','globalThis.worker = {')+'\nglobalThis.nativeTools=serverForApp;',context);return context;
}

test('actual relay gates action and provenance before writes and queues exact valid arguments',async()=>{
 const context=runtime();
 for(const [name,args] of [...names.map(name=>[name,request]),['sketch_edit',edit(image(provenance))],['studio_sketch_edit',edit(image(provenance))]]){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),owner='r'.repeat(43);
  const register=appVersion=>relay.appRegister('parts-device',owner,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.8.4');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(owner,name,args),/4\.8\.5/);assert.equal(storage.writes(),before);
  await register('4.8.5');before=storage.writes();await assert.rejects(relay.appEnqueueV3(owner,name,{...args,sourcePath:'/private.png'}));assert.equal(storage.writes(),before);
  const queued=await relay.appEnqueueV3(owner,name,args);assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),args);
 }
});

test('registered MCP tool reports source writes, bounded source-assisted capability and old-app rejection',async()=>{
 const context=runtime(),storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),owner='s'.repeat(43);
 await relay.appRegister('parts-mcp',owner,{appVersion:'4.8.4',protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything'});
 const server=context.nativeTools({VIDEO_STATE:{getByName:()=>relay}},owner,3),tool=server.tools.get('app_'+action);assert.ok(tool);
 assert.equal(tool.definition.annotations.readOnlyHint,false);assert.equal(tool.definition.annotations.destructiveHint,true);
 assert.match(tool.definition.description,/4\.8\.5/);assert.match(tool.definition.description,/source.assisted/i);assert.match(tool.definition.description,/SketchEditOps/);
 const before=storage.writes(),result=JSON.parse((await tool.handler(request)).content[0].text);assert.equal(result.queued,false);assert.match(result.error,/4\.8\.5/);assert.equal(storage.writes(),before);
 const caps=JSON.parse((await server.tools.get('app_capabilities').handler({})).content[0].text);assert.deepEqual(caps.referenceLayers,contract.REFERENCE_LAYERS_CAPABILITIES);
 assert.equal(caps.referenceLayers.minimumNativeVersion,'4.8.5');assert.equal(caps.referenceLayers.automaticSemanticAnalysis,false);
 assert.equal(caps.referenceLayers.expandedImageBudgetMinimumNativeVersion,'4.8.6');assert.equal(caps.referenceLayers.limits.maximumImageObjects,32);assert.equal(caps.referenceLayers.limits.legacyMaximumImageObjects,16);assert.equal(caps.referenceLayers.limits.maximumPreparedSelectionBytes,8388608);
});

test('read-only MCP mask preview gates old apps before queue writes and forwards accepted masks and native image',async()=>{
 const context=runtime(),storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),owner='p'.repeat(43),device='parts-preview-mcp';
 const register=appVersion=>relay.appRegister(device,owner,{appVersion,protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything'});
 await register('4.8.4');const server=context.nativeTools({VIDEO_STATE:{getByName:()=>relay}},owner,3),tool=server.tools.get('app_'+previewAction);assert.ok(tool);
 assert.equal(tool.definition.annotations.readOnlyHint,true);assert.equal(tool.definition.annotations.destructiveHint,false);
 for(const pattern of [/4\.8\.5/,/read.only/i,/acceptedMasks/,/edgeSnapRadius/,/no files/i])assert.match(tool.definition.description,pattern);
 let before=storage.writes();const rejected=JSON.parse((await tool.handler(request)).content[0].text);assert.equal(rejected.queued,false);assert.match(rejected.error,/4\.8\.5/);assert.equal(storage.writes(),before);
 await register('4.8.5');before=storage.writes();const invalid=JSON.parse((await tool.handler({...request,edgeSnapRadius:17})).content[0].text);assert.equal(invalid.queued,false);assert.equal(storage.writes(),before);
 const args={...request,edgeSnapRadius:4};const queued=JSON.parse((await tool.handler(args)).content[0].text);assert.equal(queued.queued,true);
 // Synthetic receipt checks transport only; native core tests own overlay pixels and no-publication behavior.
 const base64=Buffer.from('synthetic native selection-overlay channel').toString('base64');
 const receipt={ok:true,readOnly:true,sourceModified:false,filesPublished:false,acceptedMasks:request.masks,sourceReferenceSha256:request.expectedReferenceSha256,sourceSha256:request.expectedSourceSha256,sketchPreviewImage:{mimeType:'image/png',base64}};
 await relay.appCompleteV3(device,owner,queued.commandId,receipt,'completed');const returned=await server.tools.get('app_get_command_result').handler({commandId:queued.commandId});
 assert.deepEqual(JSON.parse(JSON.stringify(returned.content.filter(block=>block.type==='image'))),[{type:'image',data:base64,mimeType:'image/png'}]);
 const metadata=JSON.parse(returned.content.find(block=>block.type==='text').text).result;
 assert.deepEqual(metadata.acceptedMasks,request.masks);assert.equal(metadata.sourceReferenceSha256,request.expectedReferenceSha256);assert.equal(metadata.sourceSha256,request.expectedSourceSha256);assert.equal(metadata.sourceModified,false);assert.equal(metadata.filesPublished,false);
 const caps=JSON.parse((await server.tools.get('app_capabilities').handler({})).content[0].text).referenceLayers;
 assert.equal(caps.previewAction,previewAction);assert.equal(caps.previewReadOnly,true);assert.equal(caps.localEdgeAssistance,true);assert.equal(caps.fullPrecisionHardMasks,true);assert.deepEqual(caps.edgeSnapRadius,[0,16]);
});

test('native admission and source-level dispatch route reference layers through shared background edit core',()=>{
 for(const name of ['NativeWorkbenchBridge','NativeAgentMailboxActions','NativeCommandExecutionPolicy'])for(const route of [action,previewAction])assert.ok(native(name).includes('"'+route+'"'),name+': '+route);
 assert.match(native('ControlService'),/case "sketch_import_reference_layers":[\s\S]*?SketchEditOps\.execute\(this,action,p\)/);
 assert.match(native('ControlService'),/case "sketch_reference_layers_preview":\s*case "sketch_layered_drawing_preview":\s*case "sketch_preview":\{\s*JSONObject preview=SketchEditOps\.execute\(this,action,p\)/);
 assert.match(native('SketchCapabilities'),/\.put\("referenceLayers",referenceLayers\(\)\)/);
 assert.match(native('AppProtocol'),/APP_VERSION = "4\.10\.27"/);
 const build=fs.readFileSync(new URL('../android/app/build.gradle.kts',import.meta.url),'utf8');assert.match(build,/versionCode = 530/);assert.match(build,/versionName = "4\.10\.27"/);
});

test('native and MCP referenceLayers capability metadata match exactly',()=>{
 const expression=native('SketchCapabilities').match(/public static JSONObject referenceLayers\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)[1];
 class JSONObject{put(key,value){this[key]=value;return this;}}
 class JSONArray{constructor(values){return values;}}
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext('('+expression.replace(/new int\[\]\{([^}]*)\}/g,'[$1]')+')',{JSONObject,JSONArray}))),contract.REFERENCE_LAYERS_CAPABILITIES);
});

test('explicit source-coordinate cutout pivots are strict and keep older omitted floors',()=>{
 const placed={...request,masks:[{...mask,pivot:[.25,.5]}]};
 for(const name of names){
  assert.deepEqual(contract.nativeStudioParameters(name,placed),placed);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.5'},name,placed),/4\.10\.6/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.6'},name,placed));
  assert.deepEqual(contract.nativeStudioParameters(name,request),request);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.5'},name,request));
 }
 for(const pivot of [null,[.5],['.5',.5],[-.01,.5],[1.01,.5],[.5,.5,0]])assert.throws(()=>contract.nativeStudioParameters(action,{...request,masks:[{...mask,pivot}]}));
});


test('explicit shared joint pixels preserve strict booleans and upgrade every inline provenance path',()=>{
 for(const allowPartOverlap of [false,true]){
  for(const name of names){const args={...request,allowPartOverlap};assert.deepEqual(contract.nativeStudioParameters(name,args),args);assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.5'},name,args),/4\.10\.6/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.6'},name,args));}
  const full={...image({...provenance,allowPartOverlap}),frame:0,exposure:1,color:'#FF000000',width:.001,fill:true,keys:[],transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1}};
  for(const operations of [[{type:'add_object',layerId:'parts',object:image({...provenance,allowPartOverlap})}],[{type:'add_layer',layer:{id:'parts',objects:[full]}}]]){
   const plan={bookId:'book',operationId:'story',name:'Story',width:720,height:1280,fps:24,shots:[{shotId:'shot',frameCount:96,operations}]};
   for(const [name,args] of [['sketch_edit',{...edit(image(provenance)),operations}],['sketch_story_create',plan],['sketch_story_preview',{plan,shotId:'shot',frame:0}]])for(const prefix of ['','studio_']){assert.deepEqual(contract.nativeStudioParameters(prefix+name,args),args);assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.5'},prefix+name,args),/4\.10\.6/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.6'},prefix+name,args));}
  }
 }
 for(const value of [null,0,1,'true',{},[]]){assert.throws(()=>contract.nativeStudioParameters(action,{...request,allowPartOverlap:value}));assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(image({...provenance,allowPartOverlap:value}))));}
});


test('MCP forwards bounded retained native previews through the image channel',async()=>{
 const context=runtime(),storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),owner='q'.repeat(43),device='parts-retained-preview';await relay.appRegister(device,owner,{appVersion:'4.10.6',protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything'});const server=context.nativeTools({VIDEO_STATE:{getByName:()=>relay}},owner,3),tool=server.tools.get('app_'+previewAction);assert.match(tool.definition.description,/retainedPreviewImage/);const args={...request,masks:[{...mask,pivot:[.25,.5]}],allowPartOverlap:true};const queued=JSON.parse((await tool.handler(args)).content[0].text);assert.equal(queued.queued,true);
 const overlay=Buffer.from('synthetic overlay transport').toString('base64'),retained=Buffer.from('synthetic retained transparency transport').toString('base64');await relay.appCompleteV3(device,owner,queued.commandId,{ok:true,sourceModified:false,filesPublished:false,sourceRevision:1,acceptedMasks:args.masks,sharedSourcePixelCount:42,sketchPreviewImage:{mimeType:'image/png',base64:overlay},retainedPreviewImage:{mimeType:'image/png',base64:retained}},'completed');const result=await server.tools.get('app_get_command_result').handler({commandId:queued.commandId});assert.deepEqual(JSON.parse(JSON.stringify(result.content.filter(block=>block.type==='image'))),[{type:'image',data:overlay,mimeType:'image/png'},{type:'image',data:retained,mimeType:'image/png'}]);
});
