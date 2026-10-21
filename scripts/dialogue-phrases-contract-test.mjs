import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';
const {nativeStudioParameters,requireStudioVersion}=contract;
const actions=['open_pipeline_export_request','studio_open_pipeline_export_request'];
const base={lineId:'line',characterId:'keeper',voice:'am_adam',text:'Wait. We can do this together.',startMs:1000,slotMs:5000,speed:1};
const phrases=[{text:'Wait.',speed:.75,pauseAfterMs:1500},{text:'We can do this together.',speed:1.25,pauseAfterMs:0}];
const request=(line={...base,phrases})=>({projectId:'story',sourceProjectUpdatedAt:1,requestId:'paced_v2',backend:'kokoro',assetIds:[],controls:{language:'en-US',durationMs:120000,lines:[line]}});

test('dialogue phrases preserve explicit pacing without adding defaults or changing original text',()=>{
 for(const action of actions)for(const line of [base,{...base,phrases:[{text:base.text}]},{...base,phrases},
  {...base,text:'a b c d e f g h',phrases:'abcdefgh'.split('').map(text=>({text}))}]){
  const r=request(line);assert.deepEqual(nativeStudioParameters(action,r),r);
 }
});

test('phrases are a strict nonempty 1..8 list with bounded text, speed and integer pauses',()=>{
 for(const action of actions){
  for(const invalid of [[],null,{},Array.from({length:9},()=>({text:'a'})),[{text:''}],[{text:' '}],[{text:'x'.repeat(241)}],
   [{text:base.text,speed:.749}],[{text:base.text,speed:1.251}],[{text:base.text,speed:'1'}],[{text:base.text,speed:null}],
   [{text:base.text,speed:NaN}],[{text:base.text,speed:Infinity}],
   [{text:base.text,pauseAfterMs:-1}],[{text:base.text,pauseAfterMs:1501}],[{text:base.text,pauseAfterMs:.5}],
   [{text:base.text,pauseAfterMs:'0'}],[{text:base.text,pauseAfterMs:null}],[{text:base.text,pauseAfterMs:true}],
   [{text:base.text,pauseAfterMs:NaN}],[{text:base.text,pauseAfterMs:Infinity}],[{text:base.text,emphasis:'strong'}],
   [{text:base.text,audioUrl:'https://invalid.test/line.wav'}]])
   assert.throws(()=>nativeStudioParameters(action,request({...base,phrases:invalid})),JSON.stringify(invalid));
 }
});

test('single-space phrase join must exactly equal line.text without trimming or Unicode normalization',()=>{
 for(const action of actions){
  for(const [text,parts] of [['Wait. We can do this together.',['Wait.','We can do this together.']],
   ['पहले रुकिए। साथ चलेंगे।',['पहले रुकिए।','साथ चलेंगे।']],[' café',[' café']]]){
   const r=request({...base,text,phrases:parts.map(text=>({text}))});assert.deepEqual(nativeStudioParameters(action,r),r);
  }
  for(const line of [{...base,phrases:[{text:'Wait. '},{text:'We can do this together.'}]},
   {...base,text:'Wait.We can do this together.',phrases},
   {...base,text:'Wait.  We can do this together.',phrases},
   {...base,text:'wait. We can do this together.',phrases},
   {...base,text:'café',phrases:[{text:'cafe\u0301'}]}])assert.throws(()=>nativeStudioParameters(action,request(line)));
 }
});

test('presence of phrases gates native 4.8.0 on either export route and any dialogue line',()=>{
 for(const action of actions){
  for(const r of [request(),request({...base,phrases:[{text:base.text}]}),{...request(base),controls:{...request(base).controls,lines:[base,{...base,lineId:'second',startMs:7000,phrases}]}}]){
   for(const device of [undefined,{}, {appVersion:'unknown'},{appVersion:'4.7.0'},{appVersion:'4.7.8'}])assert.throws(()=>requireStudioVersion(device,action,r),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.0/);
   for(const appVersion of ['4.8.0','4.8.1','4.9.0'])assert.doesNotThrow(()=>requireStudioVersion({appVersion},action,r));
  }
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.0'},action,request(base)));
 }
});

test('actual export relay rejects old APKs and malformed phrase text before any queue write',async()=>{
 const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8'),end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='v'.repeat(43);
  const register=appVersion=>relay.appRegister('voice-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.8');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request()),/4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request({...base,phrases:[{text:'Changed source text.'}]})));assert.equal(storage.writes(),before);
  const r=request();assert.deepEqual(JSON.parse(JSON.stringify((await relay.appEnqueueV3(key,action,r)).parameters)),r);
 }
});

test('phrase capability describes existing Kokoro pacing, inherited speed, final pauses and real manual controls',async()=>{
 const metadata=contract.DIALOGUE_PHRASE_CAPABILITIES;assert.ok(metadata);assert.equal(metadata.minimumNativeVersion,'4.8.0');assert.equal(metadata.action,'open_pipeline_export_request');
 assert.equal(metadata.backend,'kokoro');assert.equal(metadata.maximumPhrases,8);assert.equal(metadata.maximumTextCharacters,240);
 assert.deepEqual(metadata.speedRange,[.75,1.25]);assert.deepEqual(metadata.pauseAfterMsRange,[0,1500]);
 assert.match(metadata.engine,/existing Kokoro model.*per-phrase pacing/i);assert.equal(metadata.newModel,false);assert.equal(metadata.emotionalActingCertified,false);
 assert.match(metadata.semantics,/inherits.*line speed/);assert.match(metadata.semantics,/final.*pause/);assert.match(metadata.semantics,/exact.*original line text/);
 assert.match(metadata.manualControls,/Add phrase.*Use whole line pacing/);
 const read=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
 for(const label of ['Add phrase','Use whole line pacing'])assert.ok(read('DialogueControlsView').includes(label));
 class JSONObject {put(key,value){this[key]=value;return this;}}
 class JSONArray extends Array {constructor(values){super();this.push(...values);}}
 const source=read('SketchCapabilities'),body=source.match(/public static JSONObject dialoguePhrases\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)?.[1];assert.ok(body);
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext(`(${body.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]')})`,{JSONObject,JSONArray}))),metadata);
 const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
 assert.match(worker,/dialoguePhrases:isV3/);assert.match(worker,/per-phrase pacing/);assert.match(worker,/Use whole line pacing/);
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);let handler;
 vm.runInNewContext(worker.slice(start,end),{...contract,s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3:true,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[]});
 assert.deepEqual((await handler()).dialoguePhrases,metadata);
});
