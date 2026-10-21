import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';

const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const source=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const actions=['sketch_edit','studio_sketch_edit'];
const operations=['mouth','face','character'].map(name=>({type:`add_${name}_animation`,layerId:'layer_1',[`${name}Id`]:'actor',settings:{}}));
const request=(op,generated)=>({sketchId:'shot',operationId:'speech_shot',expectedRevision:1,operations:[{...op,speech:{generated}}]});
const ref={requestId:'long_master',lineIndex:0};

test('generated speech preserves optional explicit shot offsets and legacy references on all routes',()=>{
 for(const action of actions)for(const op of operations)for(const generated of [ref,{...ref,shotStartMs:0},{...ref,shotStartMs:65000},{...ref,shotStartMs:600000}]){
  const r=request(op,generated);assert.deepEqual(nativeStudioParameters(action,r),r);
 }
});

test('shotStartMs is a strict bounded integer within the generated reference only',()=>{
 for(const action of actions)for(const op of operations){
  for(const shotStartMs of [-1,600001,.5,'65000',null,true,NaN,Infinity,-Infinity,[],{}])
   assert.throws(()=>nativeStudioParameters(action,request(op,{...ref,shotStartMs})));
  assert.throws(()=>nativeStudioParameters(action,request(op,{...ref,shotStartMs:65000,clamp:true})));
  const r=request(op,ref);r.operations[0].speech.shotStartMs=65000;assert.throws(()=>nativeStudioParameters(action,r));
 }
});

test('even explicit zero rebasing gates older mouth and face APKs at native 4.8.0',()=>{
 for(const action of actions)for(const op of operations)for(const shotStartMs of [0,65000]){
  const r=request(op,{...ref,shotStartMs});
  for(const device of [undefined,{}, {appVersion:'unknown'},{appVersion:'4.7.7'},{appVersion:'4.7.8'}])
   assert.throws(()=>requireStudioVersion(device,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.0/);
  for(const appVersion of ['4.8.0','4.8.1','4.9.0'])assert.doesNotThrow(()=>requireStudioVersion({appVersion},action,r));
 }
 for(const action of actions)for(const op of operations)assert.doesNotThrow(()=>requireStudioVersion({appVersion:op.type==='add_character_animation'?'4.7.8':'4.7.7'},action,request(op,ref)));
});

test('rebasing gate wins in either mixed-batch order',()=>{
 for(const action of actions)for(const op of operations)for(const reverse of [false,true]){
  const r=request(op,{...ref,shotStartMs:65000});r.operations.push({...operations[2]});if(reverse)r.operations.reverse();
  assert.throws(()=>requireStudioVersion({appVersion:'4.7.8'},action,r),/4\.8\.0/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.8.0'},action,r));
 }
});

test('actual relay validates and version-gates rebase refs before any queue write',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions)for(const op of operations){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='r'.repeat(43),deviceId='rebase-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.8');let before=storage.writes();const r=request(op,{...ref,shotStartMs:65000});
  await assert.rejects(relay.appEnqueueV3(key,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();
  await assert.rejects(relay.appEnqueueV3(key,action,request(op,{...ref,shotStartMs:65000.5})));assert.equal(storage.writes(),before);
  const admitted=await relay.appEnqueueV3(key,action,r);assert.deepEqual(JSON.parse(JSON.stringify(admitted.parameters)),r);assert.equal(admitted.seq,1);
 }
});

test('native loader returns placement metadata and both manual loaders pass explicit offsets',()=>{
 const assets=source('SketchSpeechAssets');
 assert.match(assets,/"requestId,lineIndex,shotStartMs"/);
 assert.match(assets,/"audioTimelineOffsetMs"/);assert.match(assets,/"sourceLineTiming"/);
 for(const name of ['SketchSpeechDialog','SketchCharacterDialog']){
  const dialog=source(name);assert.match(dialog,/"Shot start in master \(ms\)","0",false/);
  assert.match(dialog,/SketchSpeechAssets\.load\([^;]+,shotStartMs\)/);
  assert.match(dialog,/loaded\.getLong\("audioTimelineOffsetMs"\)/);
 }
});

test('native and MCP guidance describe explicit no-clipping master rebasing and the 4.8.0 gate',()=>{
 const capabilities=source('SketchCapabilities');
 for(const text of [capabilities,worker])for(const pattern of [/shotStartMs/,/audioTimelineOffsetMs/,/sourceLineTiming/,/4\.8\.0/,/unchanged master/,/no clipping or timewarp/])assert.match(text,pattern);
});
