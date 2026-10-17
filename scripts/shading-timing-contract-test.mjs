import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion,sketchAnimationProgramSchema} from '../src/studio-workbench-contract.js';
const curve=[.42,0,1,1];
const gradient={type:'radial',cx:.5,cy:.5,radius:.4,colors:['#FFFFFFFF','#FF20A0DF','#FF050A20'],stops:[0,.5,1]};
const edit=operations=>({sketchId:'quality-study',operationId:'edit-shading',expectedRevision:1,operations});
test('radial shading reaches native edits without losing material colors or stops',()=>{
 const p=edit([{type:'update_object',objectId:'iris',patch:{gradient}}]);
 assert.deepEqual(nativeStudioParameters('sketch_edit',p),p);
 for(const g of [{...gradient,radius:0},{...gradient,stops:[0,.5]},{...gradient,stops:[0,0,1]},{...gradient,stops:[.1,.5,1]},{...gradient,colors:['#FFFFFFFF']}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',edit([{type:'update_object',objectId:'iris',patch:{gradient:g}}])));
 assert.throws(()=>requireStudioVersion({appVersion:'4.5.12'},'sketch_edit',p),/4\.6\.0/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.6.0'},'sketch_edit',p));
});
test('manual/MCP key edits carry an explicit bounded Bezier timing curve',()=>{
 const key={type:'set_keyframe',objectId:'iris',frame:0,transform:{x:0},interpolation:'bezier',curve};
 const p=edit([key]);assert.deepEqual(nativeStudioParameters('sketch_edit',p),p);
 for(const patch of [{curve:[0,1]},{curve:[-.1,0,1,1]},{interpolation:'linear'},{curve:undefined}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',edit([{...key,...patch}])));
 assert.throws(()=>requireStudioVersion({appVersion:'4.5.12'},'sketch_edit',p),/4\.6\.0/);
});
test('prepared rig/camera programs retain the same curve contract',()=>{
 const program={schema:'videostudio.sketch-animation.v1',frameCount:61,fps:24,rigs:[],cameraLayerIds:['background'],camera:[{frame:0,x:0,interpolation:'bezier',curve},{frame:60,x:1}]};
 assert.deepEqual(sketchAnimationProgramSchema.parse(program),program);
 assert.throws(()=>requireStudioVersion({appVersion:'4.5.12'},'sketch_animation_save',{program}),/4\.6\.0/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.6.0'},'sketch_animation_save',{program}));
});
