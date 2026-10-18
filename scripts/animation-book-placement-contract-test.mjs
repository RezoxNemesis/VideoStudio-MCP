import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import {nativeStudioParameters,requireStudioVersion,nativeStudioCanonicalAction,STUDIO_NATIVE_ACTIONS} from '../src/studio-workbench-contract.js';
const action='sketch_animation_place_in_book',request={animationId:'scene',expectedAnimationRevision:2,bookId:'story',expectedBookRevision:3,sourceFrameStart:0,frameCount:64,destinationPageStart:0,operationId:'place_1'};
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');

test('book placement action and alias preserve the native revision-bound request',()=>{
 assert.ok(STUDIO_NATIVE_ACTIONS.has(action));
 for(const name of [action,'studio_'+action]){assert.equal(nativeStudioCanonicalAction(name),action);assert.deepEqual(nativeStudioParameters(name,request),request);}
});
test('book placement rejects malformed identity, ranges, revisions and unknown fields',()=>{
 for(const patch of [{animationId:'../scene'},{bookId:'b'.repeat(73)},{expectedAnimationRevision:0},{expectedBookRevision:0},
  {sourceFrameStart:-1},{sourceFrameStart:1200},{sourceFrameStart:1199,frameCount:2},{frameCount:0},{frameCount:65},{frameCount:1.5},
  {destinationPageStart:-1},{destinationPageStart:999999,frameCount:2},{operationId:''},{sourceFrameStart:'0'},{replace:true},{fps:30}])
  assert.throws(()=>nativeStudioParameters(action,{...request,...patch}),JSON.stringify(patch));
 const r={...request,bookId:'b'.repeat(72),sourceFrameStart:1199,frameCount:1,destinationPageStart:999999};assert.deepEqual(nativeStudioParameters(action,r),r);
 for(const key of Object.keys(request)){const r={...request};delete r[key];assert.throws(()=>nativeStudioParameters(action,r),key);}
});
test('book placement requires native 4.8.0 on both routes',()=>{
 for(const name of [action,'studio_'+action]){
  for(const device of [undefined,{}, {appVersion:'unknown'},{appVersion:'4.7.8'}])assert.throws(()=>requireStudioVersion(device,name,request),/4\.8\.0/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.8.0'},name,request));
 }
});
test('book placement relay rejects old APKs and malformed ranges before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const name of [action,'studio_'+action]){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='b'.repeat(43);
  const register=appVersion=>relay.appRegister('book-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.8');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,request),/4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,{...request,frameCount:65}));assert.equal(storage.writes(),before);
  assert.deepEqual(JSON.parse(JSON.stringify((await relay.appEnqueueV3(key,name,request)).parameters)),request);
 }
});
test('native alias, mailbox and ControlService admit the real book placement dispatch',()=>{
 const read=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
 for(const name of ['NativeWorkbenchBridge','NativeAgentMailboxActions'])assert.ok(read(name).includes('"'+action+'"'),name);
 const service=read('ControlService');assert.match(service,/case "sketch_animation_place_in_book":[\s\S]{0,180}queueSketchStoryWork\(action,p\)/);
 assert.match(read('SketchAnimationOps'),/"sketch_animation_place_in_book"\.equals\(action\).*SketchAnimationBookPlacement\.place\(context,args,null\)/);
 assert.ok(worker.includes(":action==='sketch_animation_place_in_book'?"));assert.match(read('SketchCapabilities'),/sketch_animation_place_in_book/);
});
test('placement receipts explicitly succeed before ControlService classifies their completion',()=>{
 const service=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java',import.meta.url),'utf8');
 assert.match(service,/state\.setResult\(result\.put\("ok",true\)\)/);
 assert.match(service,/ok\(\)\.put\("queued",true\)\.put\("jobId",job.id\)/);
 assert.match(service,/boolean ok = result\.optBoolean\("ok", false\)/);
});
test('native placement is dispatched off the Android main thread for canonical and aliased commands',()=>{
 const policy=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeCommandExecutionPolicy.java',import.meta.url),'utf8');
 assert.match(policy,/action=NativeWorkbenchBridge\.canonicalAction\(action\)/);
 assert.match(policy,/case "sketch_animation_place_in_book":/);
});
