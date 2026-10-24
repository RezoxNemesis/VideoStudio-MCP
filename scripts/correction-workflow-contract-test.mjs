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

const actions=['start','get','note','propose','preview','apply','options','list','close'].map(s=>'sketch_correction_'+s);
const names=action=>[action,'studio_'+action];
const identity={sketchId:'original',reviewId:'review_1'};
const line={id:'correction_ink',kind:'line',points:[[.2,.2,1],[.7,.7,1]]};
const proposal={...identity,expectedReviewRevision:2,proposalId:'candidate_1',summary:'Correct wrist contour',noteIds:['wrist_note'],operations:[{type:'add_object',layerId:'layer_1',object:line}]};
const fixtures={
 sketch_correction_start:{...identity,expectedRevision:3,target:'Inspect wrist and shoulder',frameOffset:0,frameLimit:24,frameStep:2,motionRequired:false},
 sketch_correction_get:{...identity},
 sketch_correction_note:{...identity,expectedReviewRevision:1,noteId:'wrist_note',outcome:'NEEDS_REWORK',note:'Inspect wrist contact',frameStart:0,frameEnd:95,objectId:'actor',bounds:{x:.1,y:.2,width:.3,height:.4}},
 sketch_correction_propose:proposal,
 sketch_correction_preview:{...identity,side:'after',proposalId:'candidate_1',frameIndex:47,crop:{x:.1,y:.2,width:.3,height:.4}},
 sketch_correction_apply:{...identity,expectedReviewRevision:3,proposalId:'candidate_1',expectedRevision:3,operationId:'apply_1'},
 sketch_correction_options:{...identity,objectId:'actor'},
 sketch_correction_list:{sketchId:'original',offset:0,limit:16},
 sketch_correction_close:{...identity,expectedReviewRevision:3}
};
const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const native=name=>fs.readFileSync(new URL(`../android/app/src/main/java/com/rezoxnemesis/videostudio/${name}.java`,import.meta.url),'utf8');
const parse=(action,args)=>contract.nativeStudioParameters(action,args);
const propose=operations=>({...proposal,operations});
const retainedSettings={view:'front',build:'slender',hairStyle:'short',skinColor:'#FFD6A078',hairColor:'#FF302D32',coatColor:'#FF526B78',trouserColor:'#FF343F50',x:.5,floorY:.88,size:1};
const human={settings:retainedSettings,poses:[{frame:0},{frame:95,nearWrist:-6}],durationFrames:96,fps:24};
const image={id:'image',kind:'image',bounds:{x:0,y:0,width:.4,height:.4},reference:{file:'a'.repeat(64)+'.png',sha256:'a'.repeat(64),width:128,height:128,sourceProjectId:'p',sourceAssetId:'a'}};

test('all nine correction actions and aliases preserve exact requests and require4.8.4',async()=>{
 for(const action of actions)for(const name of names(action)){
  assert.ok(contract.STUDIO_NATIVE_ACTIONS.has(action));assert.ok(contract.STUDIO_ALIAS_ACTIONS.has('studio_'+action));
  const args=fixtures[action],parsed=parse(name,args);assert.deepEqual(parsed,args);assert.equal(await queueBindings.canonicalCommandResultSha256(parsed),await queueBindings.canonicalCommandResultSha256(args));
  for(const appVersion of ['3.9.0','4.8.0','4.8.2','4.8.3'])assert.throws(()=>contract.requireStudioVersion({appVersion},name,args),/NATIVE_APP_UPGRADE_REQUIRED:.*4\.8\.4/);
  for(const appVersion of ['4.8.4','4.8.5','4.9.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},name,args));
 }
});

test('strict fields, identifiers, revisions and scalar types reject before admission',()=>{
 for(const [action,args] of Object.entries(fixtures)){
  assert.throws(()=>parse(action,{...args,sourceDocument:{}}));assert.throws(()=>parse(action,{...args,accepted:true}));
  for(const key of ['sketchId','reviewId'])for(const value of ['','../owner','bad id','a'.repeat(97),null,1,true,{}])assert.throws(()=>parse(action,{...args,[key]:value}));
  const required=Object.keys(args).filter(key=>!['frameOffset','frameLimit','frameStep','motionRequired','bounds','objectId','crop','offset','limit'].includes(key));
  if(action==='sketch_correction_options')required.push('objectId');
  for(const key of required){const missing={...args};delete missing[key];assert.throws(()=>parse(action,missing),action+': '+key);}
  for(const key of ['expectedRevision','expectedReviewRevision'])if(key in args)for(const value of [0,-1,1.5,'2',null,true,Infinity])assert.throws(()=>parse(action,{...args,[key]:value}));
 }
 for(const field of ['motionRequired'])for(const value of ['false',0,1,null])assert.throws(()=>parse('sketch_correction_start',{...fixtures.sketch_correction_start,[field]:value}));
});

test('list resumes app-owned reviews with optional bounded pagination and no reviewId',()=>{
 const action='sketch_correction_list',minimal={sketchId:'original'};assert.deepEqual(parse(action,minimal),minimal);
 assert.doesNotThrow(()=>parse(action,{...minimal,offset:1000000,limit:1}));
 for(const [field,values] of Object.entries({offset:[-1,1000001,1.5,'0',null],limit:[0,17,1.5,'16',null]}))for(const value of values)assert.throws(()=>parse(action,{...minimal,[field]:value}));
 assert.throws(()=>parse(action,{...minimal,reviewId:'review_1'}));
});

test('close preserves exact review CAS and rejects source mutations or caller-owned replay metadata',()=>{
 const args=fixtures.sketch_correction_close;
 for(const action of names('sketch_correction_close')){
  assert.deepEqual(parse(action,args),args);
  for(const field of ['operationId','expectedRevision','closeRequestHash','status','sourceModified','discardSnapshots','delete'])assert.throws(()=>parse(action,{...args,[field]:field==='status'?'CLOSED':true}));
 }
 assert.match(contract.CORRECTION_WORKFLOW_CAPABILITIES.close,/OPEN.*CLOSED/);
 assert.match(contract.CORRECTION_WORKFLOW_CAPABILITIES.close,/APPLIED/);
 assert.match(contract.CORRECTION_WORKFLOW_CAPABILITIES.close,/identical.*replay/i);
 assert.match(contract.CORRECTION_WORKFLOW_CAPABILITIES.close,/retain/i);
});

test('start inherits bounded audit controls without inserting defaults',()=>{
 const minimal={...identity,expectedRevision:1,target:'Review figure'};assert.deepEqual(parse('sketch_correction_start',minimal),minimal);
 for(const [key,values] of Object.entries({target:['',' '.repeat(4),'x'.repeat(501),'bad\0text'],frameOffset:[-1,1200,1.5,'0'],frameLimit:[0,25,1.5,'24'],frameStep:[0,17,1.5,'1']}))for(const value of values)assert.throws(()=>parse('sketch_correction_start',{...minimal,[key]:value}));
 assert.doesNotThrow(()=>parse('sketch_correction_start',{...minimal,frameOffset:1199,frameLimit:24,frameStep:16,motionRequired:true}));
});

test('notes have strict outcomes, frame ranges and normalized bounds with optional candidate binding',()=>{
 const base=fixtures.sketch_correction_note;
 for(const outcome of ['ACCEPTABLE','NEEDS_REWORK','UNREVIEWED'])assert.deepEqual(parse('sketch_correction_note',{...base,outcome,proposalId:'candidate_1'}),{...base,outcome,proposalId:'candidate_1'});
 for(const [key,values] of Object.entries({outcome:['PASS','acceptable',false],note:['','x'.repeat(1001),'bad\0note'],frameStart:[-1,1200,1.5,'1'],frameEnd:[-1,1200,1.5,'1'],proposalId:['../candidate',null]}))for(const value of values)assert.throws(()=>parse('sketch_correction_note',{...base,[key]:value}));
 assert.throws(()=>parse('sketch_correction_note',{...base,frameStart:20,frameEnd:19}));
 for(const bounds of [{x:.8,y:0,width:.3,height:.2},{x:0,y:-.1,width:.1,height:.1},{x:0,y:0,width:0,height:.2},{x:0,y:0,width:.2,height:2},{x:0,y:0,width:.2,height:.2,extra:true}])assert.throws(()=>parse('sketch_correction_note',{...base,bounds}));
});

test('proposals choose exactly operations OR recipeId and objectId and preserve note order',()=>{
 const {operations,...rest}=proposal,recipe={...rest,recipeId:'neutral_arms',objectId:'actor'};
 assert.deepEqual(parse('sketch_correction_propose',recipe),recipe);
 assert.deepEqual(parse('sketch_correction_propose',{...proposal,noteIds:['second','first']}),{...proposal,noteIds:['second','first']});
 for(const patch of [{},{recipeId:'neutral_arms'},{objectId:'actor'},{operations,recipeId:'neutral_arms',objectId:'actor'},{operations,recipeId:'neutral_arms'},{operations,objectId:'actor'}])assert.throws(()=>parse('sketch_correction_propose',{...rest,...patch}));
 for(const patch of [{operations:[]},{operations:Array(65).fill(operations[0])},{noteIds:[]},{noteIds:Array(17).fill('note')},{noteIds:['same','same']},{summary:''},{summary:'x'.repeat(501)},{recipeId:'eval()',objectId:'actor',operations:undefined}])assert.throws(()=>parse('sketch_correction_propose',{...proposal,...patch}));
});

test('correction operations reuse strict native validation for vector, pose and key edits',()=>{
 const valid=[
  {type:'add_layer',layer:{id:'fixes',name:'Correction'}},{type:'update_layer',layerId:'layer_1',patch:{opacity:.8}},
  {type:'delete_layer',layerId:'extra'},{type:'reorder_layer',layerId:'fixes',index:0},
  {type:'add_object',layerId:'layer_1',object:line},{type:'update_object',objectId:'actor',patch:{human}},
  {type:'delete_object',objectId:'extra'},{type:'append_points',objectId:'stroke',points:[[.3,.3,1]]},
  {type:'set_transform',objectId:'actor',transform:{rotation:2}},
  {type:'set_keyframe',objectId:'actor',frame:47,transform:{rotation:3},interpolation:'linear'},
  {type:'set_shape_key',objectId:'contour',frame:0,commands:[['M',.1,.1],['L',.5,.5]]},
  {type:'set_exposure',objectId:'actor',frame:0,exposure:96},
  {type:'add_human_animation',layerId:'layer_1',actorId:'actor',settings:{figureStyle:'anatomy_sketch'},poseOverrides:{nearElbowSwivel:90,farElbowSwivel:-90}},
  {type:'add_hand_animation',layerId:'layer_1',handId:'hand',settings:{renderStyle:'cel'}}
 ];
 for(const op of valid)assert.deepEqual(parse('sketch_correction_propose',propose([op])),propose([op]));
 for(const op of [{type:'set_transform',layerId:'a',objectId:'b',transform:{rotation:3}},{type:'set_keyframe',objectId:'actor',frame:4},{type:'set_shape_key',objectId:'actor',frame:0},{type:'set_exposure',objectId:'actor',frame:1199,exposure:2},{type:'update_object',objectId:'actor',patch:{human:{...human,settings:{...retainedSettings,constructionGuides:'false'}}}},{...valid.at(-2),settings:{view:'three_quarter_left'}}])assert.throws(()=>parse('sketch_correction_propose',propose([op])));
});

test('correction whitelist blocks paint, dependencies, references, imports, clocks and live/remote paths',()=>{
 const forbidden=[{type:'add_paint_cel',layerId:'layer_1',objectId:'paint'},{type:'paint_stroke',objectId:'paint',strokeId:'s',frameIndex:0,points:[[.2,.2,1]],brush:{mode:'draw',color:'#FF111111',size:.01,opacity:1,flow:1,hardness:1,spacing:.1,pressure:true,grain:0,tip:'round',angle:0}},
  {type:'set_timing',fps:30},{type:'set_canvas',width:512},{type:'insert_frame',frame:0},{type:'delete_frame',frame:1},
  {type:'duplicate_cel',layerId:'layer_1',fromFrame:0,toFrame:1,idPrefix:'copy'},
  {type:'trace_reference',objectId:'ref',layerId:'layer_1',frame:0,idPrefix:'trace'},
  {type:'set_reference_visibility',objectId:'ref',visible:true},{type:'sketch_live_begin'},{type:'import_reference'},
  {type:'add_object',layerId:'layer_1',object:image},
  {type:'add_layer',layer:{id:'refs',objects:[{...image,frame:0,exposure:1,color:'#FF111111',width:.001,fill:true,keys:[],transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1}}]}},
  {type:'update_object',objectId:'ref',patch:{referenceCrop:{x:0,y:0,width:.5,height:.5}}},
  {type:'update_object',objectId:'paint',patch:{paint:{file:'private.png'}}}];
 for(const op of forbidden)assert.throws(()=>parse('sketch_correction_propose',propose([op])),JSON.stringify(op));
 // A placement edit of an existing reference remains within the operation scope;
 // native document/reference policy still decides whether the source is allowed.
 const placement={type:'set_transform',objectId:'existing_reference',transform:{x:.2,rotation:3}};
 assert.deepEqual(parse('sketch_correction_propose',propose([placement])),propose([placement]));
 // The ordinary authoring routes retain their old capabilities.
 assert.doesNotThrow(()=>parse('sketch_edit',{sketchId:'original',expectedRevision:1,operationId:'timing',operations:[{type:'set_timing',fps:30}]}));
});

test('preview preserves the existing image contract and enforces after proposal binding and frame/crop bounds',()=>{
 const before={...identity,side:'before',frameIndex:0};assert.deepEqual(parse('sketch_correction_preview',before),before);
 for(const args of [{...before,side:'after'},{...before,side:'AFTER'},{...before,frameIndex:1200},{...before,frameIndex:-1},{...before,frameIndex:'0'},{...before,crop:{x:.9,y:0,width:.2,height:.2}},{...before,detail:'thumbnail'},{...before,maxDimension:32}])assert.throws(()=>parse('sketch_correction_preview',args));
 const imageReader=vm.runInNewContext('('+worker.match(/const nativeImageBlocks = (command => \{[\s\S]*?\n\});/)[1]+')');
 const priorKeys=['contactSheet','sourceArtworkImage','rigPreviewImage','sketchPreviewImage'];
 const encoded=Object.fromEntries(priorKeys.map(key=>[key,{base64:Buffer.from(key).toString('base64'),mimeType:'image/png'}]));
 assert.deepEqual(JSON.parse(JSON.stringify(imageReader({result:encoded}))),priorKeys.map(key=>({type:'image',data:encoded[key].base64,mimeType:'image/png'})));
 const service=native('ControlService');for(const action of actions)assert.ok(service.includes('case "'+action+'"'),action);
 assert.match(service,/SketchCorrectionOps\.execute\(this,action,p\)/);
});

test('real relay rejects old clients and malformed correction data before queue writes',async()=>{
 const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 for(const action of actions)for(const name of names(action)){
  const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='k'.repeat(43);
  const register=appVersion=>relay.appRegister('correction-device',key,{appVersion,protocolVersion:3,appGeneration:1,permissionMode:'everything'});
  await register('4.8.3');let before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,fixtures[action]),/4\.8\.4/);assert.equal(storage.writes(),before);
  await register('4.8.4');before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,name,{...fixtures[action],unknown:true}));assert.equal(storage.writes(),before);
  if(action==='sketch_correction_propose'){
   for(const op of [{type:'set_timing',fps:30},{type:'add_object',layerId:'layer_1',object:image},{type:'add_layer',layer:{id:'refs',objects:[{...image,frame:0,exposure:1,color:'#FF111111',width:.001,fill:true,keys:[],transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1}}]}}]){
    await assert.rejects(relay.appEnqueueV3(key,name,propose([op])));assert.equal(storage.writes(),before);
   }
  }
  const queued=await relay.appEnqueueV3(key,name,fixtures[action]);assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),fixtures[action]);
 }
});

test('native admission aliases and background policy include all correction calls',()=>{
 for(const action of actions){for(const name of ['NativeWorkbenchBridge','NativeAgentMailboxActions','NativeCommandExecutionPolicy'])assert.ok(native(name).includes('"'+action+'"'),name+': '+action);}
});

test('MCP registration exposes nine accurate effect hints and preserves the preview image channel',async()=>{
 class McpServer{constructor(){this.tools=new Map();}registerTool(name,definition,handler){this.tools.set(name,{definition,handler});}}
 const context={...storyboard,...contract,...queueBindings,nativeCommandSummary,nativeResultPage,z,McpServer,crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,Date,setTimeout,clearTimeout,
  DurableObject:class{constructor(ctx){this.ctx=ctx;}},createMcpHandler:()=>()=>new Response(),APP_HTML:'',STUDIO_RUNTIME_JS:'',STUDIO_CINEMATIC_JS:'',STUDIO_NEURAL_JS:'',STUDIO_TEMPORAL_JS:'',STUDIO_CHARACTER_JS:'',STUDIO_ACTION_TIMELINE_JS:'',STUDIO_POSE_SEQUENCE_JS:'',LOCAL_VIDEO_DOWNLOAD_HTML:''};
 vm.createContext(context);vm.runInContext(worker.replace(/^import .*$/gm,'').replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState').replace('export default {','globalThis.worker = {')+'\nglobalThis.nativeTools=serverForApp;',context);
 const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),owner='m'.repeat(43),device='correction-mcp-device';
 const register=appVersion=>relay.appRegister(device,owner,{appVersion,protocolVersion:3,protocolMin:3,protocolMax:3,permissionMode:'everything'});
 await register('4.8.3');const server=context.nativeTools({VIDEO_STATE:{getByName:()=>relay}},owner,3);
 const readonly=new Set(['sketch_correction_get','sketch_correction_preview','sketch_correction_options','sketch_correction_list']);
 for(const action of actions){const tool=server.tools.get('app_'+action);assert.ok(tool);assert.equal(tool.definition.annotations.readOnlyHint,readonly.has(action));assert.match(tool.definition.description,/4\.8\.4/);const before=storage.writes();const rejected=JSON.parse((await tool.handler(fixtures[action])).content[0].text);assert.equal(rejected.queued,false);assert.match(rejected.error,/4\.8\.4/);assert.equal(storage.writes(),before);}
 const caps=JSON.parse((await server.tools.get('app_capabilities').handler({})).content[0].text);assert.deepEqual(caps.correctionWorkflow,JSON.parse(JSON.stringify(contract.CORRECTION_WORKFLOW_CAPABILITIES)));
 await register('4.8.4');const queued=JSON.parse((await server.tools.get('app_sketch_correction_preview').handler(fixtures.sketch_correction_preview)).content[0].text);assert.equal(queued.queued,true);
 // Synthetic transport receipt only: this tests image-channel forwarding, not native rendering.
 const imageBytes=Buffer.from('synthetic image-channel receipt').toString('base64');
 const result={ok:true,sourceModified:false,side:'after',reviewId:'review_1',proposalId:'candidate_1',snapshotDocumentSha256:'a'.repeat(64),sketchPreviewImage:{mimeType:'image/png',base64:imageBytes}};
 await relay.appCompleteV3(device,owner,queued.commandId,result,'completed');
 const returned=await server.tools.get('app_get_command_result').handler({commandId:queued.commandId});
 assert.deepEqual(JSON.parse(JSON.stringify(returned.content.filter(block=>block.type==='image'))),[{type:'image',data:imageBytes,mimeType:'image/png'}]);
 const metadata=JSON.parse(returned.content.find(block=>block.type==='text').text);assert.equal(metadata.result.sourceModified,false);assert.equal(metadata.result.side,'after');assert.equal(metadata.result.snapshotDocumentSha256,'a'.repeat(64));
});

test('Node operation scope matches the parent core and fixed recipe identifiers match the bundled catalogue',()=>{
 const nativeOps=JSON.parse('['+native('SketchCorrectionOps').match(/SUPPORTED=Set\.of\(([^)]*)\)/)[1]+']');
 assert.deepEqual([...contract.CORRECTION_SUPPORTED_OPERATIONS].sort(),nativeOps.sort());
 const library=JSON.parse(fs.readFileSync(new URL('../android/app/src/main/assets/animation_recipes/corrections-v1.json',import.meta.url),'utf8'));
 assert.equal(library.libraryVersion,1);assert.ok(library.recipes.length<=32);
 assert.deepEqual(library.recipes.map(recipe=>recipe.id).sort(),[...contract.CORRECTION_WORKFLOW_CAPABILITIES.initialRecipes].sort());
 assert.ok(library.recipes.every(recipe=>typeof recipe.expectedChange==='string'&&Array.isArray(recipe.checks)&&recipe.checks.length>0));
});

test('capability literals expose fixed recipe checks and human judgment without training or automatic acceptance claims',()=>{
 const m=contract.CORRECTION_WORKFLOW_CAPABILITIES;assert.ok(m);assert.equal(m.minimumNativeVersion,'4.8.4');assert.deepEqual(m.actions,actions);
 assert.deepEqual(m.limits,{maximumOpenReviews:16,maximumStoredBytes:134217728,maximumProposals:4,maximumNotes:32,maximumNoteReferences:16,maximumOperations:64,maximumRecipes:32,maximumAuditFrames:24,maximumDocumentBytes:4194304});
 assert.match(m.recipeLibrary,/corrections-v1\.json/);assert.match(m.recipes,/fixed.*not.*training/i);assert.match(m.preview,/high.*sketchPreviewImage/);assert.match(m.apply,/revision.*hash.*undo/i);assert.match(m.recovery,/separate.*transaction/i);
 for(const key of ['automaticAnatomyEvaluation','modelTraining','creativeQualityCertified','phoneExecutionVerified'])assert.equal(m[key],false);
 class JSONObject{put(key,value){this[key]=value;return this;}}class JSONArray extends Array{constructor(values){super();this.push(...values);}}
 const body=native('SketchCapabilities').match(/public static JSONObject correctionWorkflow\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/)?.[1];assert.ok(body);
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInNewContext('('+body.replace(/new (?:String|double|int)\[\]\{([^}]*)\}/g,'[$1]')+')',{JSONObject,JSONArray}))),m);
 assert.match(native('SketchCapabilities'),/\.put\("correctionWorkflow",correctionWorkflow\(\)\)/);
 assert.match(worker,/correctionWorkflow:isV3.*CORRECTION_WORKFLOW_CAPABILITIES/);
});

test('release is4.10.23/code526 and earlier anatomy/style/action floors are unchanged',()=>{
 assert.match(native('AppProtocol'),/APP_VERSION = "4\.10\.23"/);const build=fs.readFileSync(new URL('../android/app/build.gradle.kts',import.meta.url),'utf8');assert.match(build,/versionCode = 526/);assert.match(build,/versionName = "4\.10\.23"/);
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);assert(contract.STUDIO_ALIAS_ACTIONS.has("studio_sketch_layered_drawing_preview"));for(const action of contract.STUDIO_NATIVE_ACTIONS)assert(contract.STUDIO_ALIAS_ACTIONS.has("studio_"+action));
 const anatomy={sketchId:'original',expectedRevision:1,operationId:'anatomy',operations:[{type:'add_human_animation',layerId:'layer_1',actorId:'actor',settings:{figureStyle:'legacy',constructionGuides:false}}]};
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.3'},'sketch_edit',anatomy));assert.throws(()=>contract.requireStudioVersion({appVersion:'4.8.2'},'sketch_edit',anatomy),/4\.8\.3/);
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.1.0'},'sketch_restore_archive',{archiveProjectId:'p',archiveName:'a.vssketch',expectedSha256:'a'.repeat(64),operationId:'restore'}));
});
