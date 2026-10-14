import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion,STUDIO_NATIVE_ACTIONS} from '../src/studio-workbench-contract.js';
const edit=op=>({sketchId:'s',operationId:'o',expectedRevision:2,operations:[op]});
const brush={mode:'paint',color:'#FF875533',sizePx:7,hardness:.3,flow:.4,opacity:.8,spacing:.15,pressure:true,texture:'grain',seed:17,angle:20,tip:'flat'};
const stroke={type:'paint_stroke',objectId:'cel',strokeId:'stroke',points:[[.1,.2,.5],[.3,.4,1]],brush};
test('live drawing declares its actual method without retroactively upgrading legacy sessions',()=>{
 const begin={sketchId:'s',operationId:'live',expectedRevision:1};
 assert.deepEqual(nativeStudioParameters('sketch_live_begin',begin),begin);
 for(const drawingMethod of ['original_strokes','source_assisted']){
  const declared={...begin,drawingMethod};assert.deepEqual(nativeStudioParameters('sketch_live_begin',declared),declared);
  assert.throws(()=>requireStudioVersion({appVersion:'4.1.0'},'sketch_live_begin',declared),/4\.1\.1/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.1.1'},'sketch_live_begin',declared));
 }
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.1.0'},'sketch_live_begin',begin));
 for(const drawingMethod of ['photorealistic','mixed',true])assert.throws(()=>nativeStudioParameters('sketch_live_begin',{...begin,drawingMethod}));
});
test('native paint strokes accept real brush controls and reject internal pixels and resource escapes',()=>{
 assert.deepEqual(nativeStudioParameters('sketch_edit',edit(stroke)),edit(stroke));
 for(const patch of [{afterPaint:{}},{beforePaint:{}},{stroke:{}},{cloneSource:{objectId:'ref',sha256:'a'.repeat(64)}}])assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...stroke,...patch})));
 for(const p of [{sizePx:257},{flow:Infinity},{pressure:1},{texture:'photo'},{tip:'stamp'},{command:'shell'}])assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...stroke,brush:{...brush,...p}})));
 assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...stroke,points:Array.from({length:2049},()=>[.1,.2,1])})));
 assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...stroke,brush:{...brush,mode:'erase'},alphaLock:true})));
});
test('clone preserves precise affine mapping and cannot mix ambiguous maps',()=>{
 const op={...stroke,brush:{...brush,mode:'clone'},cloneSource:{objectId:'reference',sha256:'a'.repeat(64),mapping:[1,0,0,1,0,0]}};
 assert.deepEqual(nativeStudioParameters('sketch_edit',edit(op)),edit(op));
 assert.deepEqual(nativeStudioParameters('sketch_edit',edit({...op,frameIndex:5})),edit({...op,frameIndex:5}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...op,frameIndex:1200})));
 for(const source of [undefined,{...op.cloneSource,mapping:[1,2,2,4,0,0]},{...op.cloneSource,scale:2},{...op.cloneSource,mapping:[1,0,0,1,5,0]},{objectId:'reference',sha256:'a'.repeat(64),scale:0}])assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...op,cloneSource:source})));
});
test('native-owned empty cels and read queries preserve revision and source identity',()=>{
 const cel={type:'add_paint_cel',layerId:'layer_1',objectId:'cel',frame:0,exposure:2};assert.deepEqual(nativeStudioParameters('sketch_edit',edit(cel)),edit(cel));
 assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...cel,paint:{file:'unowned.json'}})));
 const journal={sketchId:'s',expectedRevision:2,objectId:'cel',offset:0,limit:128};assert.deepEqual(nativeStudioParameters('sketch_paint_journal',journal),journal);
 assert.throws(()=>nativeStudioParameters('sketch_paint_journal',{...journal,limit:129}));
 const color={sketchId:'s',expectedRevision:2,x:.2,y:.3,source:'paint',objectId:'cel'};assert.deepEqual(nativeStudioParameters('sketch_sample_color',color),color);
 assert.throws(()=>nativeStudioParameters('sketch_sample_color',{...color,objectId:undefined}));
 assert.throws(()=>nativeStudioParameters('sketch_sample_color',{...color,source:'reference'}));
 assert.throws(()=>nativeStudioParameters('sketch_sample_color',{...color,source:'composite'}));
 const ref={...color,source:'reference',sha256:'b'.repeat(64)};assert.deepEqual(nativeStudioParameters('sketch_sample_color',ref),ref);
});
test('paint, archive and larger canvas requests gate incompatible APKs before admission',()=>{
 const args={sketchId:'s',expectedRevision:2,operationId:'archive'};
 assert(STUDIO_NATIVE_ACTIONS.has('sketch_archive'));assert.deepEqual(nativeStudioParameters('sketch_archive',args),args);
 const restore={archiveProjectId:'s',archiveName:'sketch_s.vssketch',expectedSha256:'a'.repeat(64),operationId:'restore'};assert.deepEqual(nativeStudioParameters('sketch_restore_archive',restore),restore);
 assert.throws(()=>nativeStudioParameters('sketch_restore_archive',{...restore,archiveName:'../unsafe.vssketch'}));
 for(const [action,parameters] of [['sketch_edit',edit(stroke)],['sketch_archive',args],['sketch_restore_archive',restore],['sketch_paint_journal',{sketchId:'s',expectedRevision:2,objectId:'cel'}],['sketch_sample_color',{sketchId:'s',expectedRevision:2,source:'composite',x:0,y:0}],['sketch_create',{sketchId:'s',operationId:'create',width:2048,height:2048}]]){
  assert.throws(()=>requireStudioVersion({appVersion:'4.0.1'},action,parameters),/4\.1\.0/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.1.0'},action,parameters));
  assert.deepEqual(nativeStudioParameters(action,parameters),parameters);
 }
 assert.throws(()=>nativeStudioParameters('sketch_create',{sketchId:'s',operationId:'create',width:2050,height:2048}));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.0.0'},'sketch_create',{sketchId:'s',operationId:'create',width:720,height:1280}));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.0.0'},'sketch_edit',edit({type:'set_canvas',width:1280})));
 assert.throws(()=>requireStudioVersion({appVersion:'4.0.1'},'sketch_edit',edit({type:'set_canvas',width:2048})),/4\.1\.0/);
});
