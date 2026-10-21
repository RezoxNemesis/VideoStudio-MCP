import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';
const commands=[['M',.1,.3],['C',.15,.2,.25,.2,.3,.3],['L',.3,.7],['L',.1,.7],['Z']];
const curve=[.42,0,1,1];
const edit=operations=>({sketchId:'shape-study',expectedRevision:1,operationId:'shape-edit',operations});
test('shape key operations retain authored curves and require the correct native version',()=>{
 const p=edit([{type:'set_shape_key',objectId:'lid',frame:24,commands,interpolation:'bezier',curve}]);
 assert.deepEqual(nativeStudioParameters('sketch_edit',p),p);
 assert.throws(()=>requireStudioVersion({appVersion:'4.6.0'},'sketch_edit',p),/4\.6\.1/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.6.1'},'sketch_edit',p));
 for(const patch of [{commands:undefined},{curve:undefined},{curve:[-1,0,1,1]},{interpolation:'hold'}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',edit([{...p.operations[0],...patch}])));
});
test('shape-key geometry may only accompany compatible editable path topology',()=>{
 const shapeKeys=[{frame:0,commands,interpolation:'linear'}];
 const object={id:'lid',kind:'path',commands,shapeKeys};
 assert.deepEqual(nativeStudioParameters('sketch_edit',edit([{type:'add_object',layerId:'layer_1',object}])).operations[0].object,object);
 assert.throws(()=>nativeStudioParameters('sketch_edit',edit([{type:'add_object',layerId:'layer_1',object:{...object,shapeKeys:[{...shapeKeys[0],commands:[['M',0,0],['L',1,1]]}]}}])));
 assert.throws(()=>nativeStudioParameters('sketch_edit',edit([{type:'add_object',layerId:'layer_1',object:{id:'raster',kind:'image',shapeKeys}}])));
});
test('sampled shape retrieval keeps the command page boundary and requires an object',()=>{
 const p={sketchId:'shape-study',objectId:'lid',shapeFrame:24,commandOffset:1,commandLimit:2};
 assert.deepEqual(nativeStudioParameters('sketch_get',p),p);
 assert.throws(()=>nativeStudioParameters('sketch_get',{...p,objectId:undefined}));
 assert.throws(()=>requireStudioVersion({appVersion:'4.6.0'},'sketch_get',p),/4\.6\.1/);
});
