import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';
import {nativeCinematic3dParameters} from '../src/cinematic3d-contract.js';
import {requireStoryboardNativeVersion} from '../src/storyboard-contract.js';
const gate=(action,p)=>{
 for(const name of [action,'studio_'+action]){
  assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.2.0'},name,p),/4\.3\.0/);
  assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.3.0'},name,p));
 }
};
test('incremental key edits have bounded strict fields and gate both native aliases',()=>{
 const p={projectId:'project',expectedUpdatedAt:1,operations:[{clipId:'clip',keyframeEdits:[{action:'upsert',keyframe:{timeMs:300,x:.2,easing:'ease_in_out'}},{action:'delete',timeMs:200}]}]};
 assert.deepEqual(nativeStudioParameters('precision_edit',p),p);gate('precision_edit',p);
 for(const bad of [{action:'delete',timeMs:-1},{action:'delete',timeMs:1.5},{action:'delete',timeMs:3,other:4},{action:'upsert',keyframe:{timeMs:3,opacity:2}},{action:'upsert',keyframe:{timeMs:3,script:'x'}}])
  assert.throws(()=>nativeStudioParameters('precision_edit',{...p,operations:[{clipId:'clip',keyframeEdits:[bad]}]}));
 assert.throws(()=>nativeStudioParameters('precision_edit',{...p,operations:[{...p.operations[0],clearKeyframes:true}]}));
 assert.throws(()=>nativeStudioParameters('precision_edit',{...p,operations:[{...p.operations[0],keyframes:[]}]}));
});
test('stereo fields preserve explicit zero and false while rejecting coercion',()=>{
 const p={projectId:'project',expectedUpdatedAt:1,operations:[{clipId:'clip',set:{audioPan:0,audioMono:false}}]};
 assert.deepEqual(nativeStudioParameters('precision_edit',p),p);gate('precision_edit',p);
 for(const set of [{audioPan:1.01},{audioPan:-1.01},{audioPan:'0'},{audioPan:Infinity},{audioMono:'false'},{audioMono:0}])
  assert.throws(()=>nativeStudioParameters('precision_edit',{...p,operations:[{clipId:'clip',set}]}));
});
test('symmetry authoring admits bounded authored strokes and cannot smuggle copied source objects',()=>{
 const op={type:'symmetry_stroke',layerId:'layer_1',object:{id:'petal',kind:'stroke',points:[[.5,.5,.8],[.6,.4,1]]},symmetry:{mode:'radial',segments:6,centerX:.5,centerY:.5}};
 const p={sketchId:'sketch',operationId:'operation',expectedRevision:1,operations:[op]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',p),p);gate('sketch_edit',p);
 for(const symmetry of [{mode:'radial'},{mode:'radial',segments:9},{mode:'vertical',segments:2},{mode:'both',centerX:-.1},{mode:'radial',segments:2.5}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{...op,symmetry}]}));
 for(const object of [{...op.object,kind:'image'},{...op.object,reference:{file:'source.png'}},{...op.object,transform:{x:0}},{...op.object,cloneSource:{objectId:'source'}}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{...op,object}]}));
});
test('title styles are strictly title-only, bounded, and versioned without changing old captions',()=>{
 const title={id:'caption',type:'title',text:'Hello',fontSize:48,fontFamily:'serif',fontStyle:'bold_italic',textAlign:'right',backgroundColor:0x88334455};
 const p={projectId:'project',expectedUpdatedAt:1,studioLayers:[title]};
 assert.deepEqual(nativeStudioParameters('set_studio_layers',p),p);gate('set_studio_layers',p);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.2.0'},'set_studio_layers',{...p,studioLayers:[{type:'title',text:'Legacy'}]}));
 for(const bad of [{fontSize:11},{fontSize:96.5},{fontFamily:'custom_script'},{fontStyle:'false'},{textAlign:1},{backgroundColor:.5}])
  assert.throws(()=>nativeStudioParameters('set_studio_layers',{...p,studioLayers:[{...title,...bad}]}));
 assert.throws(()=>nativeStudioParameters('set_studio_layers',{...p,studioLayers:[{type:'audio',assetId:'sound',fontSize:32}]}));
});
test('camera edits bind exact program SHA and source revision before native dispatch',()=>{
 const p={projectId:'project',programId:'program',sourceProjectUpdatedAt:1,expectedProgramSha256:'a'.repeat(64),operations:[{type:'upsert_camera_key',shotId:'shot',phase:.5,position:[3,2,5],target:[0,1,0],up:[0,1,0]}]};
 assert.deepEqual(nativeCinematic3dParameters('cinematic_3d_edit_program',p),p);gate('cinematic_3d_edit_program',p);
 for(const patch of [{expectedProgramSha256:''},{sourceProjectUpdatedAt:.5},{operations:[]},{uri:'https://unowned.invalid'}])assert.throws(()=>nativeCinematic3dParameters('cinematic_3d_edit_program',{...p,...patch}));
 for(const op of [{type:'remove_camera_key',shotId:'shot',phase:1.1},{type:'upsert_camera_key',shotId:'shot',phase:0,position:[0,0,0],target:[0,0,1],up:[0,0,0]},{type:'clear_camera_keys',shotId:'shot',arbitrary:1}])
  assert.throws(()=>nativeCinematic3dParameters('cinematic_3d_edit_program',{...p,operations:[op]}));
});
