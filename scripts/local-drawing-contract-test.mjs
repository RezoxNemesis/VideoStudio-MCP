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
