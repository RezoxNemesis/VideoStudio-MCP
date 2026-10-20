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
 const preview={sketchId:'owned',expectedRevision:2,view:'comparison',frameIndex:0};const comparison=await relay.appEnqueueV3(key,'studio_sketch_layered_drawing_preview',preview);assert.deepEqual(JSON.parse(JSON.stringify(comparison.parameters)),preview);
});


test('native and Node authored workflow capabilities agree exactly',async()=>{
 const fs=await import('node:fs');const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchLayeredDrawingCapabilities.java',import.meta.url),'utf8');
 const literal=java.match(/new JSONObject\(("(?:\\.|[^"\\])*")\)/)[1];
 assert.deepEqual(JSON.parse(JSON.parse(literal)),contract.LAYERED_DRAWING_CAPABILITIES);
});
