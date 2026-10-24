import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {createHash,webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const info={projectId:'source',expectedUpdatedAt:11,assetId:'linked-video'};
const request={projectId:'destination',expectedUpdatedAt:22,sourceProjectId:'source',sourceProjectUpdatedAt:11,assetId:'linked-video',expectedSha256:'a'.repeat(64),operationId:'copy'};
const selectedInfo=()=>({...info,selectedDocument:true});
const selectedCopy=()=>({...request,selectedDocument:true});
const sha=value=>createHash('sha256').update(typeof value==='string'?value:JSON.stringify(value)).digest('hex');

test('existing info/import actions and aliases retain explicit literal selectedDocument without injecting defaults',()=>{
 for(const prefix of ['','studio_']){
  assert.deepEqual(contract.nativeStudioParameters(prefix+'project_asset_info',selectedInfo()),selectedInfo());
  for(const appendToTimeline of [undefined,false]){
   const input=selectedCopy();if(appendToTimeline!==undefined)input.appendToTimeline=appendToTimeline;
   const parsed=contract.nativeStudioParameters(prefix+'project_import_asset',input);assert.deepEqual(parsed,input);
   assert.equal(Object.hasOwn(parsed,'appendToTimeline'),appendToTimeline!==undefined);
  }
 }
});

test('selected document mode rejects false null coercion arbitrary provider paths and unsupported media directives',()=>{
 for(const prefix of ['','studio_'])for(const [action,input] of [['project_asset_info',selectedInfo()],['project_import_asset',selectedCopy()]]){
  assert.doesNotThrow(()=>contract.nativeStudioParameters(prefix+action,input));
  for(const value of [false,null,0,1,'true','false',{},[]])assert.throws(()=>contract.nativeStudioParameters(prefix+action,{...input,selectedDocument:value}));
  for(const patch of [{uri:'content://provider/secret'},{sourceUri:'content://provider/secret'},{path:'/private/video'},{mime:'audio/mpeg'},{mediaType:'image'},{grant:true},{providerTree:true}])
   assert.throws(()=>contract.nativeStudioParameters(prefix+action,{...input,...patch}));
 }
});

test('same-project selected VIDEO copy requires one exact source and destination revision while legacy stays cross-project',()=>{
 for(const prefix of ['','studio_']){
  const same={...selectedCopy(),projectId:'source',expectedUpdatedAt:11};
  assert.deepEqual(contract.nativeStudioParameters(prefix+'project_import_asset',same),same);
  for(const sourceProjectUpdatedAt of [0,10,12])assert.throws(()=>contract.nativeStudioParameters(prefix+'project_import_asset',{...same,sourceProjectUpdatedAt}));
  assert.throws(()=>contract.nativeStudioParameters(prefix+'project_import_asset',{...request,projectId:'source'}));
  const cross=selectedCopy();assert.deepEqual(contract.nativeStudioParameters(prefix+'project_import_asset',cross),cross);
  for(const field of ['expectedUpdatedAt','sourceProjectUpdatedAt'])for(const value of [-1,1.5,'11',null,Number.MAX_SAFE_INTEGER+1])
   assert.throws(()=>contract.nativeStudioParameters(prefix+'project_import_asset',{...same,[field]:value}));
 }
});

test('selected VIDEO copies never append a clip or accept audio controls while omitted legacy append remains usable',()=>{
 for(const prefix of ['','studio_']){
  assert.doesNotThrow(()=>contract.nativeStudioParameters(prefix+'project_import_asset',selectedCopy()));
  assert.doesNotThrow(()=>contract.nativeStudioParameters(prefix+'project_import_asset',{...selectedCopy(),appendToTimeline:false}));
  for(const appendToTimeline of [true,null,'false',0])assert.throws(()=>contract.nativeStudioParameters(prefix+'project_import_asset',{...selectedCopy(),appendToTimeline}));
  for(const patch of [{audioMode:'source'},{gain:.5},{videoOnly:true},{appendAudio:true}])assert.throws(()=>contract.nativeStudioParameters(prefix+'project_import_asset',{...selectedCopy(),...patch}));
  assert.deepEqual(contract.nativeStudioParameters(prefix+'project_import_asset',{...request,appendToTimeline:true}),{...request,appendToTimeline:true});
 }
});

test('any explicit selectedDocument requires4.10.25 on both aliases while omission retains4.8.0',()=>{
 for(const prefix of ['','studio_'])for(const [action,input] of [['project_asset_info',info],['project_import_asset',request]]){
  for(const selectedDocument of [true,false,null])for(const appVersion of [undefined,'garbage','4.7.9','4.8.0','4.10.24'])
   assert.throws(()=>contract.requireStudioVersion({appVersion},prefix+action,{...input,selectedDocument}),/4\.10\.25/);
  for(const appVersion of ['4.10.25','4.10.26','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},prefix+action,{...input,selectedDocument:true}));
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},prefix+action,input));
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.7.9'},prefix+action,input),/4\.8\.0/);
 }
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},'sketch_restore_archive',{opaque:{selectedDocument:true}}));
});

test('legacy normalized request hashes outer copy metadata and canonical actions remain byte-exact',()=>{
 assert.equal(sha(contract.nativeStudioParameters('project_asset_info',info)),'f119849542e67f1a35be573f0745e6db69eb122ce723187dc10c09a4488f52eb');
 assert.equal(sha(contract.nativeStudioParameters('project_import_asset',request)),'37bfebdfdc2916351afa60f037025b69d0367bab3df03084128242cfd4489115');
 const old={...contract.PROJECT_ASSET_IMPORT_CAPABILITIES};delete old.selectedVideoDocumentCopy;
 assert.equal(sha(old),'0af1732cba34d4ab50e2cf3372c60771b98e2c0b7958590148d2ee677ceb889a');
 assert.equal(sha([...contract.STUDIO_NATIVE_ACTIONS].sort().join('\n')),'42e3cf3dac926f3c60760d13a15094297c0689b6073c6c837d76dff68b2f9f7f');
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,76);
 for(const action of ['selected_video_import','copy_document_uri','import_selected_video'])assert.equal(contract.STUDIO_NATIVE_ACTIONS.has(action),false);
});

test('actual relay rejects old native or malformed selected document requests before writes and preserves valid copy identity',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const prefix of ['','studio_'])for(const [action,input] of [['project_asset_info',selectedInfo()],['project_import_asset',{...selectedCopy(),projectId:'source',expectedUpdatedAt:11}]]){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='s'.repeat(43);
  const register=appVersion=>relay.appRegister('selected-video-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.10.24');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,prefix+action,input),/4\.10\.25/);assert.equal(storage.writes(),before);
  await register('4.10.25');
  const bad=[{...input,selectedDocument:false},{...input,uri:'content://other/not-selected'}];if(action==='project_import_asset')bad.push({...input,appendToTimeline:true},{...input,sourceProjectUpdatedAt:12});
  for(const invalid of bad){before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,prefix+action,invalid));assert.equal(storage.writes(),before);}
  const queued=await relay.appEnqueueV3(key,prefix+action,input);assert.equal(contract.nativeStudioCanonicalAction(queued.action),action);
  assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),input);
 }
});

test('native and Node capabilities share truthful selected VIDEO ownership limits without broadening legacy source access',()=>{
 const cap=contract.PROJECT_ASSET_IMPORT_CAPABILITIES.selectedVideoDocumentCopy;assert.ok(cap,'Explicit selected VIDEO document copy has an additive capability');
 assert.equal(cap.minimumNativeVersion,'4.10.25');assert.equal(cap.selectedDocument,true);assert.equal(cap.field,'selectedDocument');
 assert.equal(cap.registeredVideoOnly,true);assert.equal(cap.exactPersistedReadGrant,true);assert.equal(cap.readOnlyInspection,true);assert.equal(cap.inspectionAppOwned,false);
 assert.equal(cap.maximumBytes,10737418240);assert.equal(cap.storageReserveBytes,536870912);assert.equal(cap.sameProject,true);assert.equal(cap.appendAllowed,false);assert.equal(cap.independentOwnedCopy,true);assert.equal(cap.receiptFirstReplay,true);assert.equal(cap.newCanonicalAction,false);
 assert.match(cap.manualControls,/Choose video file/);assert.match(cap.manualControls,/Retry saved video copy/);assert.match(cap.source,/registered VIDEO/);assert.match(cap.source,/no public URI/);
 const expression=native('ProjectAssetImport').match(/public static JSONObject capabilities\(\)throws Exception \{\s*return ([\s\S]*?);\s*\}/)[1];
 class JSONObject{put(key,value){this[key]=value;return this;}}
 const metadata=JSON.parse(JSON.stringify(vm.runInNewContext('('+expression+')',{JSONObject,MAX_BYTES:10737418240,MAX_IMAGE_BYTES:268435456,STORAGE_RESERVE_BYTES:536870912})));
 assert.deepEqual(metadata,contract.PROJECT_ASSET_IMPORT_CAPABILITIES);
});

test('ordinary ChatGPT existing tool guidance states new floor grant-owned mode no append and independent publication',()=>{
 const infoStart=worker.indexOf(":action==='project_asset_info'"),copyStart=worker.indexOf(":action==='project_import_asset'",infoStart);
 const infoText=worker.slice(infoStart,copyStart),copyText=worker.slice(copyStart,worker.indexOf(":action===",copyStart+5));
 for(const text of [infoText,copyText]){assert.match(text,/4\.10\.25/);assert.match(text,/selectedDocument/);assert.match(text,/VIDEO/);assert.match(text,/persisted READ/);}
 assert.match(infoText,/read.only/i);assert.match(copyText,/same.project/i);assert.match(copyText,/append.*false|no.*append/i);assert.match(copyText,/independent/i);
});
