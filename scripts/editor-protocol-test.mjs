import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';

const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const schemaPath=new URL('../protocol/editor-operations.json',import.meta.url);
const context={crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,Date,
  EDITOR_SCHEMA:fs.existsSync(schemaPath)?JSON.parse(fs.readFileSync(schemaPath,'utf8')):{},
  DurableObject:class{constructor(ctx){this.ctx=ctx;}}};
vm.createContext(context);
const end=source.indexOf('\n}\n',source.indexOf('export class VideoStudioState'))+3;
vm.runInContext(source.slice(source.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState=class VideoStudioState'),context);
async function fixture(){
  const rows=new Map();const storage={get:async k=>structuredClone(rows.get(k)),put:async(k,v)=>rows.set(k,structuredClone(v)),delete:async k=>rows.delete(k)};
  storage.transaction=async fn=>fn(storage);
  const relay=new context.VideoStudioState({storage},{}), key='e'.repeat(43);
  await relay.appRegister('editor-device-001',key,{protocolVersion:3,appGeneration:1,permissionMode:'everything',editorSchemaVersion:1});
  return {relay,key,storage};
}
const edit=(patch={})=>({projectId:'project-001',expectedRevision:12,commandId:'edit-command-001',operation:'move_clip',args:{clipId:'clip-001',startMs:2000},...patch});

test('shared editor requests require the owner revision and durable command ID',async()=>{
  const f=await fixture();
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({expectedRevision:-1})),/revision/i);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:''})),/command/i);
});
test('unknown operations and arbitrary source URIs are rejected before queueing',async()=>{
  const f=await fixture();
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({operation:'execute_shell'})),/operation/i);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({operation:'add_clip',args:{assetId:'owned-media',uri:'content://gallery/all'}})),/uri|argument/i);
  assert.equal((await f.storage.get('app-v3-cl:editor-device-001'))?.length||0,0);
});
test('a retried editor request reuses one queue entry and command receipt',async()=>{
  const f=await fixture();const first=await f.relay.appEnqueueV3(f.key,'editor_operation',edit());
  const replay=await f.relay.appEnqueueV3(f.key,'editor_operation',edit());
  assert.equal(replay.id,first.id);assert.equal(replay.seq,first.seq);
  assert.equal((await f.storage.get('app-v3-cl:editor-device-001')).length,1);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({args:{clipId:'different',startMs:2000}})),/command.*different|conflict/i);
});
test('paused control refuses shared editor work without affecting native owner controls',async()=>{
  const f=await fixture();
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,permissionMode:'everything',controlPaused:true});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit()),/paused/);
});
test('legacy v3 queue still accepts its existing commands',async()=>{
  const f=await fixture();const command=await f.relay.appEnqueueV3(f.key,'ping',{});
  assert.equal(command.protocolVersion,3);assert.equal(command.action,'ping');
});

test('prototype names cannot masquerade as editor operations',async()=>{
  const f=await fixture();await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({operation:'constructor'})),/operation/i);
});
test('parallel retried requests still produce one durable native command',async()=>{
  const f=await fixture();const commands=await Promise.all(Array.from({length:12},()=>f.relay.appEnqueueV3(f.key,'editor_operation',edit())));
  assert.equal(new Set(commands.map(c=>c.id)).size,1);assert.equal((await f.storage.get('app-v3-cl:editor-device-001')).length,1);
});
test('completed editor receipt survives queue history pruning',async()=>{
  const f=await fixture();const c=await f.relay.appEnqueueV3(f.key,'editor_operation',edit());
  await f.relay.appCompleteV3('editor-device-001',f.key,c.id,{ok:true,revision:13},'completed');
  await f.storage.put('app-v3-cl:editor-device-001',[]);
  const replay=await f.relay.appEnqueueV3(f.key,'editor_operation',edit());assert.equal(replay.id,c.id);assert.equal(replay.status,'completed');assert.equal(replay.result.revision,13);
});

test('project-only authority is retained and rejects another project',async()=>{
  const f=await fixture();await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:1,permissionMode:'project',allowedProjectId:'project-001'});
  assert.equal((await f.relay.appResolve(f.key)).permissionMode,'project');
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({projectId:'project-private'})),/scope|project|permission/i);
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',edit())).id);
});
test('selected-assets authority rejects unselected analysis and clip edits',async()=>{
  const f=await fixture();await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:1,permissionMode:'selected_assets',allowedProjectId:'project-001',allowedAssetIds:['asset-001'],allowedClipIds:['clip-001']});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'analyse_media',{projectId:'project-001',assetId:'unselected'}),/scope|asset|permission/i);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({operation:'set_property',args:{clipId:'unselected',property:'volume',value:.5}})),/scope|clip|permission/i);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_history',edit({operation:'undo'})),/scope|permission/i);
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',edit({operation:'set_property',args:{clipId:'clip-001',property:'volume',value:.5}}))).id);
});
