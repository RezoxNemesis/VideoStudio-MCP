import test from 'node:test';
import assert from 'node:assert/strict';
import * as contract from '../src/studio-workbench-contract.js';

const edit=operations=>({sketchId:'owned',expectedRevision:2,operationId:'layered_step',operations});
const begin={type:'layered_drawing_begin',referenceObjectId:'guide',prefix:'study',parts:[{id:'front',name:'Front character'},{id:'rear',name:'Rear character'}]};
const pen={type:'precision_contour',layerId:'study_construction',objectId:'jaw_line',points:[[.1,.2,.3],[.2,.3,.8,true]],tolerancePx:.5,tension:.8,width:.001,color:'#FF202020',frame:0,exposure:1};

test('layered drawing operations round trip exactly through canonical and alias edit schemas',()=>{
 const ops=[begin,{type:'layered_drawing_stage',stage:'refinement'},{type:'layered_drawing_review',stage:'refinement',reviewedRevision:2,frameIndex:0,status:'NEEDS_REWORK',note:'Review the actual jaw and hand proportions.'},{type:'layered_drawing_finish'}];
 for(const op of ops)for(const action of ['sketch_edit','studio_sketch_edit'])assert.deepEqual(contract.nativeStudioParameters(action,edit([op])),edit([op]));
});
test('workflow strict arguments reject coercion and ambiguous parts',()=>{
 for(const patch of [{prefix:'x'.repeat(33)},{parts:[]},{parts:[begin.parts[0],begin.parts[0]]},{referenceObjectId:'../guide'},{copiedPixelsAllowed:true},{parts:[{id:'a',name:'A',sourceMask:[]},begin.parts[1]]}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit([{...begin,...patch}])));
 for(const op of [{type:'layered_drawing_stage',stage:'automatically_done'},{type:'layered_drawing_review',stage:'details',reviewedRevision:'2',status:'ACCEPTABLE',note:'review'},{type:'layered_drawing_review',stage:'details',reviewedRevision:2,status:'ACCEPTABLE',note:''},{type:'layered_drawing_review',stage:'details',reviewedRevision:2,status:true,note:'review'},{type:'layered_drawing_finish',qualityCertified:true}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit([op])));
});
test('authored precision pen reaches ordinary MCP without adding defaults or accepting trace shortcuts',()=>{
 assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit([pen])),edit([pen]));
 for(const patch of [{points:[[.1,'0.2'],[.2,.4]]},{points:[[.1,.2,.4,1],[.2,.4]]},{points:[[.1,.2],[Infinity,.4]]},{points:[[.1,.2]]},{closed:true,pressureOutline:true},{sourceImage:'guide'},{tolerancePx:0}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit([{...pen,...patch}])));
});
test('layered preview is source bound, strict, read only and has an explicit native alias',()=>{
 const args={sketchId:'owned',expectedRevision:2,frameIndex:0,view:'comparison',maxDimension:720};
 for(const action of ['sketch_layered_drawing_preview','studio_sketch_layered_drawing_preview']){assert.equal(contract.nativeStudioCanonicalAction(action),'sketch_layered_drawing_preview');assert.deepEqual(contract.nativeStudioParameters(action,args),args);}
 for(const patch of [{expectedRevision:undefined},{view:'cutouts'},{maxDimension:1025},{maxDimension:'720'},{sourcePath:'/private.jpg'},{crop:{x:0,y:0,width:2,height:1}}])assert.throws(()=>contract.nativeStudioParameters('sketch_layered_drawing_preview',{...args,...patch}));
});
test('new workflow and preview require 4.9.2 while precision pen requires the native 4.9.1 baseline',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.1'},action,edit([begin])),/4\.9\.2/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.2'},action,edit([begin])));assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.0'},action,edit([pen])),/4\.9\.1/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.1'},action,edit([pen])));}
 assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.1'},'studio_sketch_layered_drawing_preview',{}),/4\.9\.2/);
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},'sketch_edit',edit([{type:'add_layer',layer:{id:'ordinary'}}])));
 for(const action of ['sketch_component_apply','sketch_restore_archive'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.4'},action,{}));
});
test('capabilities distinguish authored layers from copied pixels and artistic acceptance',()=>{
 const c=contract.LAYERED_DRAWING_CAPABILITIES;assert.equal(c.minimumNativeVersion,'4.9.2');assert.equal(c.referencePixelsInArtwork,false);assert.equal(c.automaticallyDrawsReference,false);assert.equal(c.creativeQualityCertified,false);assert.equal(c.previewAction,'sketch_layered_drawing_preview');assert.deepEqual(c.stages,['construction','refinement','details','clean_line','separation']);
});

test('actual relay rejects unsupported workflow before writes and queues exact native edit on 4.9.2',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const {webcrypto}=await import('node:crypto');
 const {queueBindings,memoryStorage}=await import('./native-queue-fixture.mjs');const storyboard=await import('../src/storyboard-contract.js');
 const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');const end=worker.indexOf('\n}\n',worker.indexOf('export class VideoStudioState'))+3;
 const context={...storyboard,...queueBindings,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,DurableObject:class{constructor(ctx){this.ctx=ctx;}},Date};
 vm.createContext(context);vm.runInContext(worker.slice(worker.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
 const storage=memoryStorage(),relay=new context.VideoStudioState({storage},{}),key='l'.repeat(43),deviceId='layered-contract-native';
 await relay.appRegister(deviceId,key,{appVersion:'4.9.1',protocolVersion:3,appGeneration:1,permissionMode:'everything'});
 const before=storage.writes();await assert.rejects(relay.appEnqueueV3(key,'sketch_edit',edit([begin])),/4\.9\.2/);assert.equal(storage.writes(),before);
 await relay.appRegister(deviceId,key,{appVersion:'4.9.2',protocolVersion:3,appGeneration:1,permissionMode:'everything'});
 const queued=await relay.appEnqueueV3(key,'studio_sketch_edit',edit([begin]));assert.equal(contract.nativeStudioCanonicalAction(queued.action),'sketch_edit');assert.deepEqual(JSON.parse(JSON.stringify(queued.parameters)),edit([begin]));
 const fill={type:'paint_fill',objectId:'base',strokeId:'fill',contourObjectId:'outline',expectedContourSha256:'a'.repeat(64),color:'#FFFFFFFF'};const beforeFill=storage.writes();await assert.rejects(relay.appEnqueueV3(key,'sketch_edit',edit([fill])),/4\.9\.4/);assert.equal(storage.writes(),beforeFill);await relay.appRegister(deviceId,key,{appVersion:'4.9.4',protocolVersion:3,appGeneration:1,permissionMode:'everything'});const filled=await relay.appEnqueueV3(key,'sketch_edit',edit([fill]));assert.deepEqual(JSON.parse(JSON.stringify(filled.parameters)),edit([fill]));
 const preview={sketchId:'owned',expectedRevision:2,view:'comparison',frameIndex:0};const comparison=await relay.appEnqueueV3(key,'studio_sketch_layered_drawing_preview',preview);assert.deepEqual(JSON.parse(JSON.stringify(comparison.parameters)),preview);
});


test('native and Node authored workflow capabilities agree exactly',async()=>{
 const fs=await import('node:fs');const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchLayeredDrawingCapabilities.java',import.meta.url),'utf8');
 const literal=java.match(/new JSONObject\(("(?:\\.|[^"\\])*")\)/)[1];
 assert.deepEqual(JSON.parse(JSON.parse(literal)),contract.LAYERED_DRAWING_CAPABILITIES);
});

test('stage-only preview is a strict opt-in with its own version floor',()=>{
 const legacy={sketchId:'owned',expectedRevision:2,view:'comparison'};
 assert.deepEqual(contract.nativeStudioParameters('sketch_layered_drawing_preview',legacy),legacy);
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.2'},'sketch_layered_drawing_preview',legacy));
 for(const stageOnly of [false,true]){const args={...legacy,stageOnly};assert.deepEqual(contract.nativeStudioParameters('sketch_layered_drawing_preview',args),args);assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.2'},'sketch_layered_drawing_preview',args),/4\.9\.3/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.3'},'sketch_layered_drawing_preview',args));}
 for(const stageOnly of [null,0,'true'])assert.throws(()=>contract.nativeStudioParameters('sketch_layered_drawing_preview',{...legacy,stageOnly}));
});

test('owned contour fills and source inspection are strict and independently gated',()=>{
 const fill={type:'paint_fill',objectId:'base',strokeId:'fill_1',contourObjectId:'closed_pen',expectedContourSha256:'a'.repeat(64),color:'#FFFFFFFF'};
 for(const action of ['sketch_edit','studio_sketch_edit']){assert.deepEqual(contract.nativeStudioParameters(action,edit([fill])),edit([fill]));assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.3'},action,edit([fill])),/4\.9\.4/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.4'},action,edit([fill])));}
 for(const patch of [{color:1},{color:'#00FFFFFF'},{color:null},{expectedContourSha256:'bad'},{sourceImage:'guide'},{contour:{commands:[]}},{frameIndex:'0'}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit([{...fill,...patch}])));
 const inspect={sketchId:'owned',expectedRevision:2,contourObjectIds:['closed_pen']};assert.deepEqual(contract.nativeStudioParameters('sketch_inspect',inspect),inspect);assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.3'},'sketch_inspect',inspect),/4\.9\.4/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.4'},'sketch_inspect',inspect));
 for(const patch of [{expectedRevision:undefined},{contourObjectIds:[]},{contourObjectIds:['same','same']},{contourObjectIds:[null]}])assert.throws(()=>contract.nativeStudioParameters('sketch_inspect',{...inspect,...patch}));
 assert.throws(()=>contract.requireStudioVersion({appVersion:'3.9.5'},'sketch_inspect',{sketchId:'owned'}),/3\.9\.6/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.6'},'sketch_inspect',{sketchId:'owned'}));
 assert.throws(()=>contract.nativeStudioParameters('sketch_correction_propose',{reviewId:'review',expectedReviewRevision:1,operationId:'proposal',noteIds:['note'],operations:[fill]}));
});

test('ordinary animation schemas preserve native hidden artwork, contact cues and sampling tolerance',()=>{
 const program={schema:'videostudio.sketch-animation.v1',frameCount:96,fps:24,rigs:[{id:'actor',joints:[{id:'hand',x:.3,y:.4}],bindings:[],poses:[{frame:0}]},{id:'prop',joints:[{id:'grip',x:.3,y:.4}],bindings:[],poses:[{frame:0}]}],hiddenArtwork:['annotations'],interactionCues:[{frame:95,rigA:'actor',jointA:'hand',rigB:'prop',jointB:'grip',maxGapPx:.1}],keyframeTolerancePixels:.25};
 const request=p=>({animationId:'study',sketchId:'owned',sourceRevision:2,expectedRevision:0,operationId:'save',program:p});
 for(const action of ['sketch_animation_save','studio_sketch_animation_save'])assert.deepEqual(contract.nativeStudioParameters(action,request(program)),request(program));
 for(const patch of [{hiddenArtwork:['same','same']},{hiddenArtwork:null},{keyframeTolerancePixels:.24},{keyframeTolerancePixels:'0.25'},{interactionCues:[{...program.interactionCues[0],frame:96}]},{interactionCues:[{...program.interactionCues[0],jointA:'missing'}]},{interactionCues:[{...program.interactionCues[0],rigB:'actor'}]}])assert.throws(()=>contract.nativeStudioParameters('sketch_animation_save',request({...program,...patch})));
 const bare={schema:program.schema,frameCount:96,rigs:[]};assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.4.0'},'sketch_animation_save',request(bare)));
 for(const [field,minimum,previous] of [['hiddenArtwork','4.5.0','4.4.9'],['interactionCues','4.5.2','4.5.1'],['keyframeTolerancePixels','4.5.3','4.5.2']]){const p={...bare,[field]:field==='keyframeTolerancePixels'?.25:[]};assert.throws(()=>contract.requireStudioVersion({appVersion:previous},'sketch_animation_save',request(p)),new RegExp(minimum.replaceAll('.','\\.')));assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:minimum},'sketch_animation_save',request(p)));}
});
