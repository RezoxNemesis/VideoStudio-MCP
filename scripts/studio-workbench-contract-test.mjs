import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {nativeStudioParameters,requireStudioVersion,STUDIO_NATIVE_ACTIONS,studioToolSchemas} from '../src/studio-workbench-contract.js';

test('new native operations require 3.9 before effects, preserve the existing protocol',()=>{
 assert.throws(()=>requireStudioVersion({appVersion:'3.8.1'},'sketch_edit'),/UPGRADE_REQUIRED/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.0'},'sketch_edit'));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.8.0'},'cinematic_3d_save_program',{}));
 assert.throws(()=>requireStudioVersion({appVersion:'3.8.1'},'cinematic_3d_save_program',{timeline:{shots:[{action:'WAVE'}]}}),/UPGRADE_REQUIRED/);
 assert.throws(()=>requireStudioVersion({appVersion:'3.8.1'},'set_studio_layers',{layers:[{type:'image'}]}),/UPGRADE_REQUIRED/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.8.0'},'set_studio_layers',{studioLayers:[{type:'title',text:'Hello'}]}));
 assert.throws(()=>requireStudioVersion({appVersion:'3.8.1'},'apply_tool',{tool:'transform',settings:{opacity:.5}}),/UPGRADE_REQUIRED/);
 assert.throws(()=>requireStudioVersion({appVersion:'3.8.1000'},'sketch_edit'),/UPGRADE_REQUIRED/);
 assert.throws(()=>requireStudioVersion({appVersion:'3.8.1'},'cinematic_3d_install_builtin',{pack:'extended'}),/UPGRADE_REQUIRED/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.8.0'},'cinematic_3d_install_builtin',{pack:'starter'}));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.0'},'cinematic_3d_install_builtin',{pack:'extended'}));
});
test('exact edits reject nonfinite data and unknown fields before admission',()=>{
 const p={projectId:'project_1',expectedUpdatedAt:100,operations:[{clipId:'clip_1',set:{x:.2,scaleX:1.5},keyframes:[{timeMs:0,x:0,easing:'linear'},{timeMs:1000,x:.5,easing:'ease_in'}]}]};
 assert.deepEqual(nativeStudioParameters('precision_edit',p),p);
 assert.throws(()=>nativeStudioParameters('precision_edit',{...p,operations:[{clipId:'clip_1',set:{x:Infinity}}]}));
 assert.throws(()=>nativeStudioParameters('precision_edit',{...p,sourceUrl:'https://foreign.invalid/video'}));
});
test('sketch geometry and paged queries are bounded and precise',()=>{
 const p={sketchId:'sketch_1',operationId:'op_1',expectedRevision:1,operations:[{type:'add_object',layerId:'layer_1',object:{id:'stroke_1',kind:'stroke',points:[[.1,.2,.8],[.2,.3,1]]}}]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',p),p);
 assert.throws(()=>nativeStudioParameters('sketch_get',{sketchId:'sketch_1',pointLimit:513}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{type:'add_object',layerId:'layer_1',object:{id:'bad',kind:'image',sourceUrl:'https://foreign.invalid'}}]}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{type:'delete_object',objectId:'x',_trusted:true}]}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{type:'set_transform',objectId:'o',layerId:'l',transform:{x:.2}}]}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{type:'set_keyframe',objectId:'o',frame:0}]}));
 assert.deepEqual(nativeStudioParameters('sketch_get',{sketchId:'s',expectedRevision:1,layerId:'l',objectOffset:10,objectLimit:8}),{sketchId:'s',expectedRevision:1,layerId:'l',objectOffset:10,objectLimit:8});
});
test('open generation accepts own sources and distinct backend controls',()=>{
 const p={projectId:'project_1',sourceProjectUpdatedAt:1,requestId:'request_1',backend:'cogvideox',controls:{prompt:'Two robots wave',width:64,height:64,numFrames:9,fps:8,seed:17,numInferenceSteps:2,guidanceScale:6},assetIds:[]};
 assert.deepEqual(nativeStudioParameters('open_pipeline_export_request',p),p);
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,backend:'veo'}));
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls:{...p.controls,command:['sh']}}));
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls:{...p.controls,numFrames:10}}));
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,assetIds:['reference_1']}),/REFERENCE_CONDITIONING_UNAVAILABLE/);
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,backend:'frame_sequence'}));
});
test('CogVideoX video-to-video requires one owned-video ID and explicit bounded strength',()=>{
 const p={projectId:'project_1',sourceProjectUpdatedAt:1,requestId:'request_1',backend:'cogvideox',controls:{prompt:'Preserve the source camera and refine the two robots',mode:'video_to_video',strength:.5,width:64,height:64,numFrames:9,fps:8,seed:17,numInferenceSteps:2,guidanceScale:6},assetIds:['owned_mp4']};
 assert.deepEqual(nativeStudioParameters('open_pipeline_export_request',p),p);
 for(const assetIds of [[],['video_1','video_2']])assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,assetIds}));
 for(const strength of [undefined,.01,1.01,Infinity]){const controls={...p.controls};if(strength===undefined)delete controls.strength;else controls.strength=strength;assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls}));}
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls:{...p.controls,numInferenceSteps:1,strength:.05}}),/V2V_ZERO_DENOISING_STEPS/);
 const text={...p,controls:{...p.controls,mode:'text_to_video'},assetIds:[]};assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',text));delete text.controls.strength;
 assert.deepEqual(nativeStudioParameters('open_pipeline_export_request',text),text);
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...text,assetIds:['owned_mp4']}));
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls:{...p.controls,mode:'image_to_video'}}));
});
test('CogVideoX temporal decoder requires 8k+1 frames in both modes',()=>{
 for(const mode of ['text_to_video','video_to_video']){
  const p={projectId:'p',sourceProjectUpdatedAt:1,requestId:'r',backend:'cogvideox',controls:{prompt:'Animate the source',mode,width:64,height:64,numFrames:9,fps:8,seed:1,numInferenceSteps:2,guidanceScale:6},assetIds:mode==='video_to_video'?['owned_mp4']:[]};
  if(mode==='video_to_video')p.controls.strength=.5;
  for(const numFrames of [9,17,49]){const valid={...p,controls:{...p.controls,numFrames}};assert.deepEqual(nativeStudioParameters('open_pipeline_export_request',valid),valid);}
  assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls:{...p.controls,numFrames:13}}));
 }
});
test('bounded acyclic JSON and secret configuration have separate shapes',()=>{
 assert(STUDIO_NATIVE_ACTIONS.has('open_pipeline_configure'));
 assert.throws(()=>nativeStudioParameters('open_pipeline_configure',{endpoint:'https://runner.invalid',bearer:'short'}));
 const p={sketchId:'s',operationId:'o',expectedRevision:1,operations:[]};p.operations.push(p);
 assert.throws(()=>nativeStudioParameters('sketch_edit',p),/acyclic|structural/);
});
test('strict JSON rejects custom prototypes, accessors, sparse arrays and excessive bytes',()=>{
 const base={sketchId:'s',operationId:'o',expectedRevision:1,operations:[{type:'delete_object',objectId:'x'}]};
 assert.throws(()=>nativeStudioParameters('sketch_edit',Object.assign(Object.create({trusted:true}),base)),/prototype/);
 const evil=Object.create(null);evil.constructor=Object;assert.throws(()=>nativeStudioParameters('sketch_edit',Object.assign(Object.create(evil),base)),/prototype/);
 let observed=false;const accessor={...base};Object.defineProperty(accessor,'extra',{enumerable:true,get(){observed=true;return 'x'}});
 assert.throws(()=>nativeStudioParameters('sketch_edit',accessor),/accessor/);assert.equal(observed,false);
 const array={...base,operations:[]};array.operations.length=2;assert.throws(()=>nativeStudioParameters('sketch_edit',array),/dense/);
 const protoArray={...base,operations:[...base.operations]};Object.setPrototypeOf(protoArray.operations,{});assert.throws(()=>nativeStudioParameters('sketch_edit',protoArray),/prototype/);
 assert.throws(()=>nativeStudioParameters('open_pipeline_configure',{endpoint:'https://own.invalid',bearer:'x'.repeat(524289)}),/512 KiB/);
 const crossRealm=vm.runInNewContext("({sketchId:'s',pointLimit:8})");assert.deepEqual(nativeStudioParameters('sketch_get',crossRealm),{sketchId:'s',pointLimit:8});
});
test('advanced clip pixel controls and canonical image layers mirror native geometry bounds',()=>{
 const p={projectId:'p',expectedUpdatedAt:1,operations:[{clipId:'c',set:{opacity:.5,chromaKey:true,chromaColor:'#00FF00',chromaTolerance:.2,chromaFeather:.05,maskShape:'ellipse',maskLeft:.1,maskTop:.2,maskRight:.9,maskBottom:.8,maskFeather:.1,glowStrength:.8,glowRadiusPx:8,glowThreshold:.7},keyframes:[{timeMs:0,opacity:0},{timeMs:1000,opacity:1}]}]};
 assert.deepEqual(nativeStudioParameters('precision_edit',p),p);
 assert.throws(()=>nativeStudioParameters('precision_edit',{...p,operations:[{clipId:'c',set:{maskLeft:.9,maskRight:.1}}]}));
 assert.throws(()=>nativeStudioParameters('precision_edit',{...p,operations:[{clipId:'c',keyframes:[{timeMs:0,x:0},{timeMs:0,x:1}]}]}));
 const layers={projectId:'p',expectedUpdatedAt:1,studioLayers:[{id:'image_1',type:'image',assetId:'owned_png',startMs:0,durationMs:1000,x:.5,y:.5,width:.4,height:.3,rotation:15,opacity:.7}]};
 assert.deepEqual(nativeStudioParameters('set_studio_layers',layers),layers);
 assert.throws(()=>nativeStudioParameters('set_studio_layers',{...layers,studioLayers:[{...layers.studioLayers[0],uri:'content://foreign'}]}));
 assert.throws(()=>nativeStudioParameters('set_studio_layers',{...layers,studioLayers:Array.from({length:9},(_,i)=>({...layers.studioLayers[0],id:'image_'+i}))}));
});
test('Blender and interpolation controls retain exact source order and prefix clock',()=>{
 const timeline={schema:'videostudio.cinematic3d.timeline.v1',name:'Guide',fps:24,motionProvider:'procedural_mannequin',shots:[{id:'wave',action:'WAVE',durationFrames:8,hitStopFrames:0,camera:{mode:'wide',fovDegrees:57,shakeIntensity:0},vfx:{sparkBurst:false,slashTrail:false,rubble:false}}]};
 const p={projectId:'p',sourceProjectUpdatedAt:1,requestId:'r',backend:'blender',controls:{timeline,renderFrames:8,conditioningFrames:[0,1,2]},assetIds:[]};
 assert.deepEqual(nativeStudioParameters('open_pipeline_export_request',p),p);
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls:{...p.controls,conditioningFrames:[0,2]}}));
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...p,controls:{...p.controls,renderFrames:9}}));
 const interpolate={...p,backend:'interpolation',controls:{inputFps:24,outputFps:48,cuts:[2],hitStopRanges:[[0,1],[1,2]]},assetIds:['a','b','c']};
 assert.deepEqual(nativeStudioParameters('open_pipeline_export_request',interpolate),interpolate);
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...interpolate,controls:{...interpolate.controls,cuts:[3]}}));
 assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',{...interpolate,assetIds:['a','a','c']}));
 assert.throws(()=>nativeStudioParameters('open_pipeline_restore_artifact',{requestId:'r',name:'other.vsresult',sha256:'a'.repeat(64)}));
});
test('configuration has no embedded secrets or alternate endpoint schemes',()=>{
 const p={endpoint:'https://own.invalid/runner',bearer:'x'.repeat(32)};assert.deepEqual(nativeStudioParameters('open_pipeline_configure',p),p);
 for(const endpoint of ['http://own.invalid','https://user:pass@own.invalid','https://own.invalid?token=x','https://own.invalid#fragment','https:\\own.invalid','https://own.invalid:0'])assert.throws(()=>nativeStudioParameters('open_pipeline_configure',{...p,endpoint}));
 for(const action of STUDIO_NATIVE_ACTIONS)assert.ok(studioToolSchemas[action],action);
});
