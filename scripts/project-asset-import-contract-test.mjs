import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import * as contract from '../src/studio-workbench-contract.js';

const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const info={projectId:'picture_project',expectedUpdatedAt:100,assetId:'sketch_encoded_output'};
const request={projectId:'audio_project',expectedUpdatedAt:200,sourceProjectId:'picture_project',sourceProjectUpdatedAt:100,assetId:'sketch_encoded_output',expectedSha256:'a'.repeat(64),operationId:'copy_picture'};

test('project asset info and import accept strict explicit references with unchanged optional append semantics',()=>{
 for(const prefix of ['', 'studio_']){
  assert.deepEqual(contract.nativeStudioParameters(prefix+'project_asset_info',info),info);
  for(const appendToTimeline of [undefined,false,true]){
   const args={...request};if(appendToTimeline!==undefined)args.appendToTimeline=appendToTimeline;
   assert.deepEqual(contract.nativeStudioParameters(prefix+'project_import_asset',args),args);
  }
 }
});

test('project import rejects ambiguous identity, revisions, hashes, coercion and caller paths before admission',()=>{
 for(const prefix of ['','studio_']){
  for(const patch of [{projectId:request.sourceProjectId},{sourceProjectId:''},{assetId:'../video'},{operationId:'o'.repeat(97)},
   {expectedUpdatedAt:-1},{sourceProjectUpdatedAt:.5},{expectedUpdatedAt:'200'},{expectedSha256:'A'.repeat(64)},
   {expectedSha256:'abc'},{appendToTimeline:null},{appendToTimeline:1},{appendToTimeline:'true'},{uri:'file:///private.mp4'},
   {sourcePath:'/private.mp4'},{destinationAssetId:'overwrite'},{replace:true}])assert.throws(()=>contract.nativeStudioParameters(prefix+'project_import_asset',{...request,...patch}));
  for(const field of Object.keys(request)){const args={...request};delete args[field];assert.throws(()=>contract.nativeStudioParameters(prefix+'project_import_asset',args));}
  assert.throws(()=>contract.nativeStudioParameters(prefix+'project_asset_info',{...info,uri:'content://media/video/1'}));
 }
});

test('project asset operations require first shipping native 4.8.0 through canonical and alias routes',()=>{
 for(const [action,args] of [['project_asset_info',info],['project_import_asset',request]])for(const prefix of ['','studio_']){
  for(const device of [undefined,{}, {appVersion:'4.7.9'}])assert.throws(()=>contract.requireStudioVersion(device,prefix+action,args),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.0/);
  for(const appVersion of ['4.8.0','4.8.1','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},prefix+action,args));
 }
});

test('actual relay refuses malformed and old-app imports without a queue write then preserves the admitted request',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboardContract,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of ['project_import_asset','studio_project_import_asset']){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='i'.repeat(43),deviceId='project-import-device';
  const register=appVersion=>relay.appRegister(deviceId,key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.7.9');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,request),/4\.8\.0/);assert.equal(storage.writes(),before);
  await register('4.8.0');before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,action,{...request,uri:'file:///video.mp4'}));assert.equal(storage.writes(),before);
  const queued=await relay.appEnqueueV3(key,action,{...request,appendToTimeline:true});assert.equal(contract.nativeStudioCanonicalAction(queued.action),'project_import_asset');assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),{...request,appendToTimeline:true});
 }
});

test('project copy metadata and all native admission paths expose real owned independent copy jobs',()=>{
 const metadata=contract.PROJECT_ASSET_IMPORT_CAPABILITIES;assert.equal(metadata.minimumNativeVersion,'4.8.0');assert.equal(metadata.maximumBytes,10737418240);assert.equal(metadata.maximumImageBytes,268435456);
 for(const pattern of [/independent/i,/SHA-256/,/same operation/i,/audio lane/i])assert.match(JSON.stringify(metadata),pattern);
 for(const name of ['NativeWorkbenchBridge','NativeAgentMailboxActions','NativeCommandExecutionPolicy'])for(const action of ['project_asset_info','project_import_asset'])assert.match(native(name),new RegExp('"'+action+'"'));
 const service=native('ControlService');assert.match(service,/case "project_import_asset":.*queueProjectAssetImport/);assert.match(service,/submitRecoverableTransfer\("project_import_asset"/);
 assert.match(worker,/project_asset_info/);assert.match(worker,/project_import_asset/);assert.match(native('MainActivity'),/Import from project/);
});

test('Java and actual MCP capability registration expose the same bounded copy metadata',async()=>{
 const expression=native('ProjectAssetImport').match(/public static JSONObject capabilities\(\)throws Exception \{\s*return ([\s\S]*?);\s*\}/)[1];
 class JSONObject{put(key,value){this[key]=value;return this;}}
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext('('+expression+')',{JSONObject,MAX_BYTES:10737418240,MAX_IMAGE_BYTES:268435456,STORAGE_RESERVE_BYTES:536870912}))),contract.PROJECT_ASSET_IMPORT_CAPABILITIES);
 const start=worker.indexOf('  s.registerTool("app_capabilities"'),end=worker.indexOf('  s.registerTool("app_catalog"',start);let handler;
 vm.runInNewContext(worker.slice(start,end),{s:{registerTool:(_name,_options,fn)=>{handler=fn;}},isV3:true,out:v=>v,relayRelease:()=>({}),CINEMATIC_BEATS:[],CHARACTER_PERFORMANCE_CAPABILITIES:contract.CHARACTER_PERFORMANCE_CAPABILITIES,PROJECT_ASSET_IMPORT_CAPABILITIES:contract.PROJECT_ASSET_IMPORT_CAPABILITIES});
 assert.deepEqual((await handler()).projectAssetImport,contract.PROJECT_ASSET_IMPORT_CAPABILITIES);
});
