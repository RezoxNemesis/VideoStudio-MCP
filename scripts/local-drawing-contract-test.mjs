import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import {nativeStudioParameters,requireStudioVersion,LOCAL_DRAWING_CAPABILITIES} from '../src/studio-workbench-contract.js';
const request={requestId:'study_1',sketchId:'source_1',expectedRevision:2,objectId:'guide',sourceSha256:'a'.repeat(64),sourceRect:{x:.2,y:.3,width:.5,height:.4},settings:{prompt:'A precise pencil study',negativePrompt:'blurry',seed:42,steps:20,strength:.6,guidance:7}};
test('local drawing native and aliases retain exact source-bound requests and gate older apps',()=>{
 for(const action of ['local_drawing_generate','studio_local_drawing_generate']){
  assert.deepEqual(nativeStudioParameters(action,request),request);
  assert.throws(()=>requireStudioVersion({appVersion:'4.10.1'},action,request),/4\.10\.2/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.2'},action,request));
 }
});
test('local drawing rejects coercion, unknown properties and invalid source/settings',()=>{
 const mutations=[r=>r.expectedRevision='2',r=>r.sourceSha256='x',r=>r.path='/tmp/source.png',r=>r.settings.seed='42',r=>r.settings.steps=3,r=>r.settings.strength=NaN,r=>r.settings.guidance=13,r=>r.settings.prompt=' ',r=>r.settings.negativePrompt=null,r=>r.settings.model='pretend',r=>r.sourceRect.x=.8,r=>r.sourceRect.width=0,r=>r.sourceRect.extra=true];
 for(const mutate of mutations){const r=structuredClone(request);mutate(r);assert.throws(()=>nativeStudioParameters('local_drawing_generate',r));}
 assert.throws(()=>nativeStudioParameters('local_drawing_install',{packId:'anything'}));
 assert.throws(()=>nativeStudioParameters('local_drawing_get',{requestId:'a',includePreview:'true'}));
 assert.throws(()=>nativeStudioParameters('local_drawing_models',{sourceUrl:'https://example.com'}));
});
test('neural inference capabilities agree with native and do not advertise artist layers or hosted execution',()=>{
 const source=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/LocalDrawingCapabilities.java',import.meta.url),'utf8');
 const native=JSON.parse(JSON.parse('"'+source.match(/new JSONObject\("(.*)"\)/)[1]+'"'));
 assert.deepEqual(native,LOCAL_DRAWING_CAPABILITIES);assert.equal(native.hostedInference,false);assert.equal(native.inferenceRequiresNetwork,false);assert.equal(native.recoversArtistLayers,false);assert.equal(native.creativeQualityCertified,false);
 const index=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');assert.match(index,/localNeuralDrawing:isV3&&typeof LOCAL_DRAWING_CAPABILITIES/);
});
test('legacy precise drawing requests retain their earlier native floor',()=>{
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.9.1'},'sketch_create',{sketchId:'a',operationId:'b'}));
 assert.deepEqual(nativeStudioParameters('local_drawing_get',{requestId:'a'}),{requestId:'a'});
});

test('actual relay fences old apps and malformed neural requests before queue writes',async()=>{
 const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of ['local_drawing_generate','studio_local_drawing_generate']){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='l'.repeat(43),device='local-drawing-device';
  await relay.appRegister(device,key,{appVersion:'4.10.1',protocolVersion:3,appGeneration:1,permissionMode:'everything'});let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request),/4\.10\.2/);assert.equal(storage.writes(),before);
  await relay.appRegister(device,key,{appVersion:'4.10.2',protocolVersion:3,appGeneration:1,permissionMode:'everything'});before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,{...request,path:'/outside.png'}));assert.equal(storage.writes(),before);
  const queued=await relay.appEnqueueV3(key,action,request);assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),request);
 }
});

test('existing MCP image channel preserves a native PNG MIME type and payload',()=>{
 const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');const start=worker.indexOf('const nativeImageBlocks ='),end=worker.indexOf('\nconst clean =',start);assert(start>=0&&end>start);
 const context={};vm.createContext(context);vm.runInContext(worker.slice(start,end).replace('const nativeImageBlocks =','globalThis.nativeImageBlocks ='),context);
 const payload='aA==';const blocks=JSON.parse(JSON.stringify(context.nativeImageBlocks({result:{contactSheet:{mimeType:'image/png',base64:payload}}})));assert.deepEqual(blocks,[{type:'image',data:payload,mimeType:'image/png'}]);
});

test('AI key placement is explicit, strict and gated while ordinary imports retain their floor',()=>{
 const p={sketchId:'animation',operationId:'place_key',expectedRevision:1,projectId:'draft',sourceProjectUpdatedAt:1,assetId:'asset',layerId:'layer_1',objectId:'key',frame:8,exposure:4,localDrawing:{requestId:'study',outputSha256:'a'.repeat(64)}};
 for(const action of ['sketch_import_reference','studio_sketch_import_reference']){
  assert.deepEqual(nativeStudioParameters(action,p),p);
  assert.throws(()=>requireStudioVersion({appVersion:'4.10.4'},action,p),/4\.10\.5/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.5'},action,p));
 }
 for(const patch of [{frame:undefined},{exposure:undefined},{localDrawing:null},{localDrawing:{...p.localDrawing,accepted:true}}])assert.throws(()=>nativeStudioParameters('sketch_import_reference',{...p,...patch}));
 const ordinary={...p};delete ordinary.localDrawing;assert.deepEqual(nativeStudioParameters('sketch_import_reference',ordinary),ordinary);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.2'},'sketch_import_reference',ordinary));
});

test('portable AI provenance is strict and carries its version floor through inline edits',()=>{
 const provenance={version:1,kind:'local_ai_raster',request,requestSha256:'b'.repeat(64),modelIdentity:'c'.repeat(64),outputSha256:'d'.repeat(64)};
 const ref={file:'e'.repeat(64)+'.png',sha256:'e'.repeat(64),width:512,height:512,sourceProjectId:'draft',sourceAssetId:'asset',sourceSha256:'d'.repeat(64),localDrawing:provenance};
 const patch={sketchId:'animation',operationId:'restore_key',expectedRevision:2,operations:[{type:'add_object',layerId:'layer_1',object:{id:'key',kind:'image',bounds:{x:0,y:0,width:1,height:1},reference:ref}}]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',patch),patch);
 assert.throws(()=>requireStudioVersion({appVersion:'4.10.4'},'sketch_edit',patch),/4\.10\.5/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.5'},'sketch_edit',patch));
 for(const mutate of [p=>p.operations[0].object.reference.localDrawing=null,p=>p.operations[0].object.reference.localDrawing.version='1',p=>p.operations[0].object.reference.localDrawing.request.settings.steps='4',p=>p.operations[0].object.reference.localDrawing.claim='original artist layers',p=>p.operations[0].object.reference.sourceSha256='a'.repeat(64)]){const p=structuredClone(patch);mutate(p);assert.throws(()=>nativeStudioParameters('sketch_edit',p));}
});

test('native sketch frame input is strict, explicit and retains the legacy image request',()=>{
 const p={requestId:'posed',sketchId:'sketch',expectedRevision:4,frameSource:{frameIndex:6,documentSha256:'a'.repeat(64),layerIds:['body','face'],crop:{x:.2,y:.1,width:.5,height:.6}},settings:request.settings};
 for(const action of ['local_drawing_generate','studio_local_drawing_generate']){assert.deepEqual(nativeStudioParameters(action,p),p);assert.throws(()=>requireStudioVersion({appVersion:'4.10.5'},action,p),/4\.10\.6/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.6'},action,p));}
 for(const mutate of [v=>v.objectId='guide',v=>v.sourceSha256='b'.repeat(64),v=>v.sourceRect={x:0,y:0,width:1,height:1},v=>v.frameSource=null,v=>v.frameSource.frameIndex='6',v=>v.frameSource.layerIds=[],v=>v.frameSource.layerIds=['body','body'],v=>v.frameSource.extra=true,v=>v.frameSource.documentSha256='bad']){const v=structuredClone(p);mutate(v);assert.throws(()=>nativeStudioParameters('local_drawing_generate',v));}
 assert.deepEqual(nativeStudioParameters('local_drawing_generate',request),request);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.2'},'local_drawing_generate',request));
});

test('prepared native source preview is explicit and gated without changing old request parsing',()=>{
 const p={...request,previewOnly:true};assert.deepEqual(nativeStudioParameters('local_drawing_generate',p),p);assert.throws(()=>requireStudioVersion({appVersion:'4.10.5'},'local_drawing_generate',p),/4\.10\.6/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.6'},'local_drawing_generate',p));for(const value of [null,'true',1])assert.throws(()=>nativeStudioParameters('local_drawing_generate',{...request,previewOnly:value}));
});
