import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {z} from 'zod';

const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const schemaPath=new URL('../protocol/editor-operations.json',import.meta.url);
const context={crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,Date,
  EDITOR_SCHEMA:fs.existsSync(schemaPath)?JSON.parse(fs.readFileSync(schemaPath,'utf8')):{},
  DurableObject:class{constructor(ctx){this.ctx=ctx;}}};
vm.createContext(context);
const end=source.indexOf('\n}\n',source.indexOf('export class VideoStudioState'))+3;
vm.runInContext(source.slice(source.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState=class VideoStudioState'),context);
context.z=z;
context.McpServer=class {
  constructor(){this.tools=new Map();}
  registerTool(name,spec,handler){this.tools.set(name,{spec,handler});}
};
context.state=env=>env.relay;
context.out=value=>({content:[{type:'text',text:JSON.stringify(value)}]});
vm.runInContext(source.slice(source.indexOf('function editorZod('),source.indexOf('async function api(')),context);
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
test('the legacy MCP tool forwards explicit project and revision to its durable native command',async()=>{
  const f=await fixture();await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:4,permissionMode:'everything'});
  const server=context.serverForApp({relay:f.relay},f.key,3),tool=server.tools.get('app_apply_tool');
  const input=z.object(tool.spec.inputSchema).strict().parse({projectId:'project-001',expectedRevision:12,clipIndex:0,tool:'volume',settings:{volume:.4}});
  const response=JSON.parse((await tool.handler(input)).content[0].text);assert.equal(response.queued,true);
  const command=(await f.storage.get('app-v3-cl:editor-device-001'))[0];
  assert.equal(command.parameters.projectId,'project-001');assert.equal(command.parameters.expectedRevision,12);assert.equal(command.parameters.settings.volume,.4);
  assert.throws(()=>z.object(tool.spec.inputSchema).parse({...input,expectedRevision:0}));
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

test('new audio DSP operations require schema 2 while earlier editors retain existing edits',async()=>{
  const f=await fixture();const dsp=edit({operation:'set_audio_effects',args:{clipId:'clip-001',settings:{lowDb:6,thresholdDb:-18,ratio:3}}});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',dsp),/schema version/i);
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:2,permissionMode:'everything'});
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',dsp)).id);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'dsp-invalid-001',operation:'set_audio_effects',args:{clipId:'clip-001',settings:{delayFeedback:1.1}}})),/numeric|value/i);
});

test('composite effects validate colour, shape, unknown settings and older APK compatibility',async()=>{
  const f=await fixture();
  const request=edit({operation:'set_composite_effects',args:{clipId:'clip-001',settings:{chromaKey:true,chromaColor:'#00FF00',mask:'ellipse',maskWidth:0.7}}});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',request),/schema|upgrade|version/i);
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:2,permissionMode:'everything'});
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',request)).id);
  for(const settings of [{chromaColor:'garbage'},{mask:'unimplemented-heart'},{maskWidth:0},{unknownControl:true}])
    await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:crypto.randomUUID(),operation:'set_composite_effects',args:{clipId:'clip-001',settings}})),/argument|colour|pattern|enum|minimum|setting|match/i);
});

test('creator styles require schema 3 and validate the renderer preset names',async()=>{
  const f=await fixture();
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:2,permissionMode:'everything'});
  const request=edit({operation:'set_creator_style',args:{clipId:'clip-001',settings:{colorPreset:'warm_film',motionPreset:'push_in',fontFamily:'monospace',textAnimation:'typewriter'}}});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',request),/schema version/i);
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:3,permissionMode:'everything'});
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',request)).id);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'unknown-style-001',operation:'set_creator_style',args:{clipId:'clip-001',settings:{motionPreset:'invented_motion'}}})),/value|preset/i);
});

test('effect presets require schema 4, retain older styles and restrict selected clip authority',async()=>{
  const f=await fixture();
  const request=edit({operation:'set_effect_preset',args:{clipId:'clip-001',preset:'gaussian_blur'}});
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:3,permissionMode:'everything'});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',request),/schema version/i);
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'older-style-001',operation:'set_creator_style',args:{clipId:'clip-001',settings:{colorPreset:'none'}}}))).id);
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:4,permissionMode:'selected_assets',allowedProjectId:'project-001',allowedAssetIds:['asset-001'],allowedClipIds:['clip-001']});
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',request)).id);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'wrong-clip-fx-001',operation:'set_effect_preset',args:{clipId:'private-clip',preset:'none'}})),/scope|permission/i);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'wrong-effect-001',operation:'set_effect_preset',args:{clipId:'clip-001',preset:'unimplemented-bloom'}})),/value|preset/i);
});

test('bulk legacy MCP tools forward explicit projects and revisions',async()=>{
  const f=await fixture();await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:5,permissionMode:'everything'});
  const server=context.serverForApp({relay:f.relay},f.key,3);
  for(const [name,extras] of [['app_apply_edit_plan',{clips:[{assetId:'asset-001',outMs:1000}]}],['app_creator_preset',{preset:'noir'}],['app_autonomous_edit',{preset:'noir',render:false}]]){
    const tool=server.tools.get(name),input=z.object(tool.spec.inputSchema).strict().parse({projectId:'project-001',expectedRevision:12,...extras});
    await tool.handler(input);const entries=await f.storage.get('app-v3-cl:editor-device-001');
    assert.equal(entries.at(-1).parameters.projectId,'project-001');assert.equal(entries.at(-1).parameters.expectedRevision,12);
  }
});

test('shared timeline and bulk preset operations are typed and require schema 5',async()=>{
  const f=await fixture();
  const plan=edit({operation:'replace_timeline',args:{clips:[{assetId:'asset-001',outMs:1000,effects:{effectPreset:'gaussian_blur'}}]}});
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:4,permissionMode:'everything'});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',plan),/schema version/i);
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:5,permissionMode:'everything'});
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',plan)).id);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'bad-effect-plan-001',operation:'replace_timeline',args:{clips:[{assetId:'asset-001',outMs:1000,effects:{fakeEffect:true}}]}})),/argument|effect/i);
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'bulk-style-command-001',operation:'apply_creator_preset',args:{clipIds:['clip-001'],preset:'noir'}}))).id);
});

test('legacy revision guarantees are refused by APKs that do not implement them',async()=>{
  const f=await fixture();await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:3,permissionMode:'everything'});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'apply_tool',{projectId:'project-001',expectedRevision:12,tool:'volume',settings:{volume:.4}}),/schema version|revision.*upgrade/i);
  await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:4,permissionMode:'everything'});
  await assert.rejects(f.relay.appEnqueueV3(f.key,'creator_preset',{projectId:'project-001',expectedRevision:12,preset:'noir'}),/schema version|revision.*upgrade/i);
});

test('selected clip authority permits bounded preset edits and refuses timeline replacement',async()=>{
  const f=await fixture();await f.relay.appRegister('editor-device-001',f.key,{protocolVersion:3,appGeneration:1,editorSchemaVersion:5,permissionMode:'selected_assets',allowedProjectId:'project-001',allowedAssetIds:['asset-001'],allowedClipIds:['clip-001']});
  const preset=edit({operation:'apply_creator_preset',args:{clipIds:['clip-001'],preset:'noir'}});
  assert.ok((await f.relay.appEnqueueV3(f.key,'editor_operation',preset)).id);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'private-bulk-command-001',operation:'apply_creator_preset',args:{clipIds:['clip-001','private-clip'],preset:'noir'}})),/scope|permission/i);
  await assert.rejects(f.relay.appEnqueueV3(f.key,'editor_operation',edit({commandId:'replace-bulk-command-001',operation:'replace_timeline',args:{clips:[{assetId:'asset-001',outMs:1000}]}})),/scope|permission/i);
});
