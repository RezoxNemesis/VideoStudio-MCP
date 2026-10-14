import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {nativeStudioParameters,requireStudioVersion,STUDIO_NATIVE_ACTIONS,STUDIO_ALIAS_ACTIONS,nativeStudioCanonicalAction,studioToolSchemas} from '../src/studio-workbench-contract.js';
import {nativeStoryboardParameters,requireStoryboardNativeVersion,STORYBOARD_NATIVE_ACTIONS} from '../src/storyboard-contract.js';

test('prepared-art animation validates real programs, revision contracts and version gates',()=>{
 const program={schema:'videostudio.sketch-animation.v1',frameCount:100,fps:24,rigs:[{id:'arm',joints:[{id:'shoulder',x:.4,y:.3},{id:'elbow',parent:'shoulder',x:.5,y:.4}],bindings:[{objectId:'sleeve',jointId:'shoulder'}],poses:[{frame:0,rotations:{elbow:0},interpolation:'smooth'},{frame:99,rotations:{elbow:80}}]}]};
 const save={animationId:'scene',sketchId:'prepared',sourceRevision:1,expectedRevision:0,operationId:'save_1',program};
 for(const action of ['sketch_animation_save','studio_sketch_animation_save'])assert.deepEqual(nativeStudioParameters(action,save),save);
 assert.deepEqual(nativeStudioParameters('sketch_animation_bake',{animationId:'scene',expectedRevision:1,operationId:'bake_1',outputSketchId:'new_output'}),{animationId:'scene',expectedRevision:1,operationId:'bake_1',outputSketchId:'new_output'});
 for(const action of ['sketch_animation_save','sketch_animation_get','sketch_animation_list','sketch_animation_bake']){assert.throws(()=>requireStudioVersion({appVersion:'4.3.1'},action),/4\.4\.0/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.4.0'},action));}
 for(const patch of [{frameCount:1201},{unknown:true},{camera:[{frame:0}]},{dialogues:[{start:0,end:100,text:'Late',layerId:'letters',bounds:{x:0,y:0,width:1,height:.1}}]},{rigs:[{...program.rigs[0],poses:[{frame:3},{frame:2}]}]},{rigs:[{...program.rigs[0],bindings:[{objectId:'sleeve',jointId:'shoulder',weights:[{shoulder:.2,elbow:.2}]}]}]}])assert.throws(()=>nativeStudioParameters('sketch_animation_save',{...save,program:{...program,...patch}}));
 assert.throws(()=>nativeStudioParameters('sketch_animation_save',{...save,expectedRevision:-1}));
 assert.throws(()=>nativeStudioParameters('sketch_animation_save',{...save,program:{...program,rigs:[{...program.rigs[0],poses:[{frame:0,rotations:{elbow:Infinity}}]}]}}));
});

test('original native action-film program passes the production relay schema',()=>{
 const program=JSON.parse(fs.readFileSync(new URL('../android/app/src/test/resources/sketch-animation/rooftop-relay-program.json',import.meta.url),'utf8'));
 assert.deepEqual(nativeStudioParameters('sketch_animation_save',{animationId:'film',sketchId:'courier_source',sourceRevision:1,expectedRevision:0,operationId:'save_1',program}).program,program);
});

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
test('reference visibility is shared, strictly boolean and gated before older native execution',()=>{
 const p={sketchId:'sketch_1',operationId:'guide_visibility',expectedRevision:1,operations:[{type:'set_reference_visibility',objectId:'guide',visible:false}]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',p),p);
 assert.throws(()=>requireStudioVersion({appVersion:'4.0.0'},'sketch_edit',p),/UPGRADE_REQUIRED/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.0.1'},'sketch_edit',p));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{...p.operations[0],visible:'false'}]}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...p,operations:[{...p.operations[0],layerId:'layer_1'}]}));
 assert.deepEqual(nativeStudioParameters('sketch_edit',{...p,operations:[{...p.operations[0],visible:true}]}).operations[0].visible,true);
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
test('closed studio relay aliases enforce each native minimum and validate their exact canonical contracts',()=>{
 for(const alias of STUDIO_ALIAS_ACTIONS){
  assert.equal(nativeStudioCanonicalAction(alias),alias.slice('studio_'.length));
  assert(STORYBOARD_NATIVE_ACTIONS.has(alias),'Every alias must remain native-only');
  const paint=['studio_sketch_paint_journal','studio_sketch_sample_color','studio_sketch_archive','studio_sketch_restore_archive'].includes(alias);
  const media=alias==='studio_import_mailbox_media';
  const cameraEdit=alias==='studio_cinematic_3d_edit_program';
  const animation=alias.startsWith('studio_sketch_animation_');
  const repair=['studio_sketch_import_drawing_asset','studio_get_native_result_page','studio_get_native_delivery_status','studio_compact_native_delivery'].includes(alias);
  assert.throws(()=>requireStoryboardNativeVersion({appVersion:'3.8.1'},alias),animation?/4\.4\.0/:repair?/4\.3\.1/:cameraEdit?/4\.3\.0/:media?/4\.2\.0/:paint?/4\.1\.0/:/3\.9\.[01456]/);
  if(animation){
   assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.3.1'},alias),/4\.4\.0/);
   assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.4.0'},alias));
   continue;
  }
  if(repair){
   assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.3.0'},alias),/4\.3\.1/);
   assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.3.1'},alias));
   continue;
  }
  if(cameraEdit){
   assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.2.0'},alias),/4\.3\.0/);
   assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.3.0'},alias));
   continue;
  }
  if(media){
   assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.1.1'},alias),/4\.2\.0/);
   assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.2.0'},alias));
   continue;
  }
  if(paint)assert.throws(()=>requireStoryboardNativeVersion({appVersion:'3.9.6'},alias),/4\.1\.0/);
  else assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'3.9.6'},alias));
  assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.1.0'},alias));
 }
 const p={projectId:'p',expectedUpdatedAt:1,operations:[{clipId:'c',set:{x:.2}}]};
 assert.deepEqual(nativeStoryboardParameters('studio_precision_edit',p),p);
 assert.throws(()=>nativeStoryboardParameters('studio_sketch_get',{sketchId:'s',pointLimit:513}));
 assert.throws(()=>nativeStoryboardParameters('studio_cinematic_3d_render',{projectId:'p',programId:'r',uri:'https://unowned.invalid'}));
 assert.throws(()=>nativeStoryboardParameters('studio_apply_tool',{tool:'transform',settings:{extra:'x'.repeat(65536)}}),/64 KiB/);
 for(const unknown of ['studio_delete_project','studio_execute','studio_studio_sketch_get']){
  assert.equal(nativeStudioCanonicalAction(unknown),unknown);
  assert(!STUDIO_ALIAS_ACTIONS.has(unknown));assert(!STORYBOARD_NATIVE_ACTIONS.has(unknown));
 }
});

test('professional paths brushes and live drawing steps pass strict native contracts',()=>{
 const parsed=nativeStudioParameters('sketch_edit',{sketchId:'s',operationId:'live',expectedRevision:1,operations:[
  {type:'add_object',layerId:'l',object:{id:'portrait',kind:'path',commands:[['M',.1,.5],['C',.2,.1,.8,.1,.9,.5]],gradient:{startColor:'#FFEECC99',endColor:'#FF886655',x0:0,y0:0,x1:1,y1:1}}},
  {type:'append_points',objectId:'ink',points:[[.4,.5,1]]},
  {type:'duplicate_cel',layerId:'l',fromFrame:0,toFrame:1,idPrefix:'cel'},
  {type:'trace_reference',layerId:'l',objectId:'reference',frame:0,idPrefix:'trace',threshold:.1}
 ]});assert.equal(parsed.operations.length,4);
 assert.throws(()=>nativeStudioParameters('sketch_edit',{sketchId:'s',operationId:'bad',expectedRevision:1,operations:[{type:'add_object',layerId:'l',object:{id:'p',kind:'path',commands:[['M',0,0],['C',0,0,1,1,2,2]]}}]}));
});

test('photo reference contract preserves bounded native dimensions and original-source provenance',()=>{
 const reference={file:'a'.repeat(64)+'.png',sha256:'a'.repeat(64),width:941,height:1672,
  sourceProjectId:'p',sourceAssetId:'a',sourceWidth:941,sourceHeight:1672,sourceOrientation:1,sourceSha256:'b'.repeat(64)};
 const payload=ref=>({sketchId:'s',operationId:'reference',expectedRevision:1,operations:[{type:'add_object',layerId:'l',object:{id:'guide',kind:'image',bounds:{x:0,y:0,width:1,height:1},reference:ref}}]});
 assert.deepEqual(nativeStudioParameters('sketch_edit',payload(reference)),payload(reference));
 assert.throws(()=>requireStudioVersion({appVersion:'4.0.0'},'sketch_edit',payload(reference)),/4\.0\.1/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.0.1'},'sketch_edit',payload(reference)));
 for(const ref of [{...reference,width:2049},{...reference,sourceWidth:8001,sourceHeight:8000},{...reference,sourceOrientation:9},{...reference,sourceSha256:'bad'}])
  assert.throws(()=>nativeStudioParameters('sketch_edit',payload(ref)));
 const oneDimension={...reference};delete oneDimension.sourceHeight;
 assert.throws(()=>nativeStudioParameters('sketch_edit',payload(oneDimension)));
 const legacy={file:'a'.repeat(64)+'.png',sha256:'a'.repeat(64),width:640,height:1280,sourceProjectId:'p',sourceAssetId:'a'};
 assert.deepEqual(nativeStudioParameters('sketch_edit',payload(legacy)),payload(legacy));
 const hiddenImport={sketchId:'s',operationId:'import',expectedRevision:1,projectId:'p',sourceProjectUpdatedAt:1,assetId:'a',layerId:'l',objectId:'guide',guideVisible:false};
 assert.deepEqual(nativeStudioParameters('sketch_import_reference',hiddenImport),hiddenImport);
 assert.throws(()=>requireStudioVersion({appVersion:'4.0.0'},'sketch_import_reference',hiddenImport),/4\.0\.1/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.0.1'},'sketch_import_reference',hiddenImport));
 assert.throws(()=>nativeStudioParameters('sketch_import_reference',{...hiddenImport,guideVisible:'false'}));
});

test('advanced sketch authoring requires 3.9.1 while basic 3.9 tools remain compatible',()=>{
 for(const action of ['sketch_book_create','sketch_live_begin','sketch_capabilities']){assert.throws(()=>requireStudioVersion({appVersion:'3.9.0'},action),/3\.9\.1/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.1'},action));}
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.0'},'sketch_get'));
 assert.throws(()=>requireStudioVersion({appVersion:'3.9.0'},'sketch_edit',{operations:[{type:'add_object',object:{kind:'path'}}]}),/3\.9\.1/);
});

test('precision preview validates bounded crops and requires the native crop implementation',()=>{
 const p={sketchId:'owned-sketch',expectedRevision:2,crop:{x:.5,y:0,width:.5,height:1}};
 assert.deepEqual(nativeStudioParameters('sketch_preview',p),p);
 assert.throws(()=>nativeStudioParameters('sketch_preview',{...p,crop:{x:.9,y:0,width:.2,height:1}}));
 assert.throws(()=>requireStudioVersion({appVersion:'3.9.2'},'studio_sketch_preview',p),/3\.9\.3/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.3'},'studio_sketch_preview',p));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.0'},'studio_sketch_preview',{sketchId:'owned-sketch'}));
});

test('native colour tracing and source-linked bulk drawings expose strict versioned contracts',()=>{
 const edit={sketchId:'owned',operationId:'colours',expectedRevision:2,operations:[{type:'trace_reference',objectId:'reference',layerId:'colours',frame:0,idPrefix:'trace',mode:'colour_regions',maxDimension:192,colourLevels:8}]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',edit),edit);
 assert.throws(()=>requireStudioVersion({appVersion:'3.9.4'},'sketch_edit',edit),/3\.9\.5/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.5'},'sketch_edit',edit));
 const p={sketchId:'drawing',manifestName:'portrait.jsonl',sourceName:'portrait.jpg',expectedSourceSha256:'a'.repeat(64),expectedFrameCount:1};
 assert.deepEqual(nativeStudioParameters('studio_sketch_import_drawing_manifest',p),p);
 assert.throws(()=>nativeStudioParameters('sketch_import_drawing_manifest',{...p,sourceName:'../portrait.jpg'}));
 assert.throws(()=>requireStudioVersion({appVersion:'3.9.4'},'studio_sketch_import_drawing_manifest',p),/3\.9\.5/);
});

test('dense live streams and curve queries have bounded revision-aware geometry cursors',()=>{
 const live={sketchId:'owned',afterSequence:2,operationOffset:4096,operationLimit:32,eventSequence:3};assert.deepEqual(nativeStudioParameters('studio_sketch_live_events',live),live);assert.throws(()=>requireStudioVersion({appVersion:'3.9.4'},'sketch_live_events',live),/3\.9\.5/);
 const curve={sketchId:'owned',expectedRevision:3,objectId:'eye',commandOffset:512,commandLimit:128};assert.deepEqual(nativeStudioParameters('sketch_get',curve),curve);assert.throws(()=>nativeStudioParameters('sketch_get',{...curve,commandLimit:513}));assert.throws(()=>requireStudioVersion({appVersion:'3.9.4'},'sketch_get',curve),/3\.9\.5/);
});


test('frame manifest aliases require395 but394 raw actions remain compatible',()=>{
 assert.throws(()=>requireStudioVersion({appVersion:'3.9.4'},'studio_sketch_import_frame_manifest'),/3\.9\.5/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.4'},'sketch_import_frame_manifest'));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.5'},'studio_sketch_import_frame_manifest'));
});
test('editable lettering source crop and frame inspection require their native396 implementation',()=>{
 const text={sketchId:'owned',operationId:'letters',expectedRevision:2,operations:[{type:'add_object',layerId:'layer_1',object:{id:'caption',kind:'text',text:'MIGHT PROVE USEFUL',bounds:{x:.1,y:.2,width:.8,height:.2},fontSize:.04,fontStyle:'italic',textAlign:'center'}}]};assert.deepEqual(nativeStudioParameters('sketch_edit',text),text);assert.throws(()=>requireStudioVersion({appVersion:'3.9.5'},'sketch_edit',text),/3\.9\.6/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.6'},'sketch_edit',text));
 const crop={projectId:'project',expectedUpdatedAt:3,operations:[{clipId:'clip',set:{crop:{x:.25,y:.25,width:.5,height:.5}}}]};assert.deepEqual(nativeStudioParameters('precision_edit',crop),crop);assert.throws(()=>requireStudioVersion({appVersion:'3.9.5'},'precision_edit',crop),/3\.9\.6/);
 const inspect={sketchId:'owned',expectedRevision:3,frameOffset:64,frameLimit:32,characterLayerIds:['black','white']};assert.deepEqual(nativeStudioParameters('sketch_inspect',inspect),inspect);assert.throws(()=>requireStudioVersion({appVersion:'3.9.5'},'sketch_inspect',inspect),/3\.9\.6/);
 const packet={sketchId:'owned',manifestName:'frames.jsonl.gz',sourceName:'source.png',expectedSourceSha256:'a'.repeat(64),expectedFrameCount:200};assert.deepEqual(nativeStudioParameters('sketch_import_drawing_manifest',packet),packet);assert.throws(()=>requireStudioVersion({appVersion:'3.9.5'},'sketch_import_drawing_manifest',packet),/3\.9\.6/);
});
test('native text nested in an added layer requires its renderer version before admission',()=>{
 const request={operations:[{type:'add_layer',layer:{id:'lettering_layer',objects:[{id:'caption',kind:'text',text:'Hello',frame:0,exposure:1,color:'#FF000000',width:.01,fill:true,keys:[],transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1},bounds:{x:0,y:0,width:1,height:1}}]}}]};
 assert.throws(()=>requireStudioVersion({appVersion:'3.9.5'},'sketch_edit',request),/3\.9\.6/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.6'},'sketch_edit',request));
});

test('431 owned drawing import and receipt repair contracts are strict and native-only',()=>{
 const packet={projectId:'owned-project',sourceProjectUpdatedAt:1234,sketchId:'new-drawing',manifestAssetId:'packet-asset',expectedManifestSha256:'a'.repeat(64),sourceAssetId:'source-asset',expectedSourceSha256:'b'.repeat(64),expectedFrameCount:2,drawingMethod:'original_strokes'};
 assert.deepEqual(nativeStudioParameters('studio_sketch_import_drawing_asset',packet),packet);
 for(const patch of [{drawingMethod:undefined},{drawingMethod:'ai_generated'},{sourceProjectUpdatedAt:0},{expectedManifestSha256:'invalid'},{sourceUrl:'https://unowned.invalid'}])assert.throws(()=>nativeStudioParameters('sketch_import_drawing_asset',{...packet,...patch}));
 assert.deepEqual(nativeStudioParameters('get_native_delivery_status',{limit:4}),{limit:4});
 assert.throws(()=>nativeStudioParameters('get_native_delivery_status',{limit:5}));
 assert.deepEqual(nativeStudioParameters('compact_native_delivery',{commandId:'terminal-command',expectedResultSha256:'c'.repeat(64)}),{commandId:'terminal-command',expectedResultSha256:'c'.repeat(64)});
 assert.throws(()=>nativeStudioParameters('compact_native_delivery',{commandId:'terminal-command',expectedResultSha256:'x'}));
 for(const action of ['sketch_import_drawing_asset','get_native_result_page','get_native_delivery_status','compact_native_delivery']){
  assert(STORYBOARD_NATIVE_ACTIONS.has(action));
  assert.throws(()=>requireStudioVersion({appVersion:'4.3.0'},action),/4\.3\.1/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.3.1'},action));
 }
});
