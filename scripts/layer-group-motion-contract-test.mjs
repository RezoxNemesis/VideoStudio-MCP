import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import * as contract from '../src/studio-workbench-contract.js';

const pose=(frame,x=0,y=0,rotation=0,interpolation='smooth')=>({frame,x,y,rotation,interpolation});
const operation=()=>({type:'set_layer_group_motion',layerIds:['rear_hand','banana','digits'],pivot:[.52,.55],poses:[pose(0),pose(36,.012,-.043,-3),pose(60,.012,-.043,-3),pose(95)]});
const edit=op=>({sketchId:'held',operationId:'group_lift',expectedRevision:1,operations:[op]});
const plan=op=>({bookId:'held_book',operationId:'held_story',name:'One bounded held lift',width:512,height:512,fps:24,shots:[{shotId:'held',frameCount:96,operations:[op]}]});
const correction=op=>({sketchId:'held',reviewId:'review',expectedReviewRevision:1,proposalId:'group_fix',summary:'Move already held layers together',noteIds:['attachment'],operations:[op]});

test('shared layer motion uses the existing native edit and alias without injected pose defaults',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){
  const request=edit(operation());assert.deepEqual(contract.nativeStudioParameters(action,request),request);
 }
 for(const interpolation of ['linear','hold','smooth','ease_in','ease_out','ease_in_out','bezier']){
  const op=operation();op.poses[0].interpolation=interpolation;if(interpolation==='bezier')op.poses[0].curve=[.12,.1,.8,.95];
  assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(op)),edit(op));
 }
});

test('group identities, pivot, pose scalars, ordering and unknown controls are strict',()=>{
 assert.doesNotThrow(()=>contract.nativeStudioParameters('sketch_edit',edit(operation())));
 const badOperations=[{unknown:true},{frameCount:96},{fps:24},{camera:true},{preservePhysics:true},{layerIds:null},{layerIds:[]},{layerIds:['rear_hand','rear_hand']},{layerIds:['../rear','banana']},{layerIds:[1,'banana']},{pivot:null},{pivot:[.5]},{pivot:[.5,.5,0]},{pivot:['.5',.5]},{pivot:[NaN,.5]},{pivot:[.5,Infinity]},{pivot:[-4.01,.5]},{pivot:[.5,4.01]},{poses:null},{poses:[]}];
 for(const patch of badOperations)assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({...operation(),...patch})));
 for(const patch of [{frame:'0'},{frame:null},{frame:.5},{frame:-1},{frame:240},{x:'0'},{x:null},{x:NaN},{x:-4.001},{x:4.001},{y:Infinity},{y:true},{rotation:'0'},{rotation:null},{rotation:Infinity},{interpolation:'Smooth'},{interpolation:null},{curve:[.1,.2,.3,.4]},{interpolation:'bezier'},{interpolation:'bezier',curve:[0,0,1]},{interpolation:'bezier',curve:[0,0,1,1,1]},{interpolation:'bezier',curve:[0,0,1,1.001]},{interpolation:'bezier',curve:['0',0,1,1]},{unknown:true}]){
  const op=operation();op.poses[0]={...op.poses[0],...patch};assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(op)));
 }
 for(const key of ['frame','x','y','rotation']){const op=operation();delete op.poses[0][key];assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(op)));}
 for(const frames of [[1,95],[0,0,95],[0,60,36,95]]){const op=operation();op.poses=frames.map(f=>pose(f));assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(op)));}
});

test('the same strict operation reaches story create/preview while correction stays explicitly unsupported',()=>{
 const op=operation(),p=plan(op);
 for(const [action,args] of [['sketch_story_create',p],['studio_sketch_story_create',p],['sketch_story_preview',{plan:p,shotId:'held',frame:24}],['studio_sketch_story_preview',{plan:p,shotId:'held',frame:24}]]){
  assert.deepEqual(contract.nativeStudioParameters(action,args),args);
 }
 assert.throws(()=>contract.nativeStudioParameters('sketch_correction_propose',correction(op)));
 for(const action of ['sketch_set_layer_group_motion','sketch_layer_group_motion'])assert.equal(contract.STUDIO_NATIVE_ACTIONS.has(action),false);
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,76,'Pinned actual521 canonical action count stays unchanged');
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);
});

test('explicit group motion gates recursively at native4.10.19 on all operation-bearing routes',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit','sketch_story_create','studio_sketch_story_create','sketch_story_preview','studio_sketch_story_preview']){
  const request={nested:{operations:[operation()]}};
  for(const appVersion of [undefined,'garbage','4.9.99','4.10.18'])assert.throws(()=>contract.requireStudioVersion({appVersion},action,request),/4\.10\.19/);
  for(const appVersion of ['4.10.19','4.10.20','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,request));
 }
});

test('old normalized keyframe bytes, version floors and opaque archive/component floors are exact',()=>{
 const old={sketchId:'held',operationId:'motion',expectedRevision:1,operations:[{type:'set_keyframe',layerId:'palm',frame:0,transform:{x:0,y:0,rotation:0},interpolation:'hold'}]};
 for(const action of ['sketch_edit','studio_sketch_edit']){
  const normalized=contract.nativeStudioParameters(action,old);assert.deepEqual(normalized,old);
  assert.equal(createHash('sha256').update(JSON.stringify(normalized)).digest('hex'),'0276fa19631a891267516a13294b33f799a0093dd6a9376e56769497bcddf4d4');
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},action,old));
 }
 for(const action of ['sketch_restore_archive','sketch_component_apply'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.4'},action,{}));
});

test('native and Node expose exactly the same shared-layer capability without physical-quality claims',()=>{
 const cap=contract.SHARED_LAYER_MOTION_CAPABILITIES;assert.ok(cap,'New native capability has a matching Node export');assert.equal(cap.minimumNativeVersion,'4.10.19');assert.equal(cap.operation,'set_layer_group_motion');
 const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchLayerGroupMotion.java',import.meta.url),'utf8');
 const literal=java.match(/CAPABILITY_JSON\s*=\s*("(?:\\.|[^"\\])*")/);assert.ok(literal,'Native capability has one canonical JSON literal');
 assert.deepEqual(JSON.parse(JSON.parse(literal[1])),cap);
 const nativeCaps=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchCapabilities.java',import.meta.url),'utf8');assert.match(nativeCaps,/\.put\("sharedLayerMotion",SketchLayerGroupMotion\.capabilities\(\)\)/);
 assert.equal(contract.CORRECTION_SUPPORTED_OPERATIONS.includes('set_layer_group_motion'),false);
});

test('ordinary ChatGPT guidance describes native shared keys and preserves honest capability boundaries',()=>{
 const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
 assert.ok(/sharedLayerMotion\s*:\s*isV3[^\n]*SHARED_LAYER_MOTION_CAPABILITIES/.test(source),'MCP status registers the shared-layer capability');
 assert.ok(/Native4\.10\.19 adds set_layer_group_motion/.test(source),'Ordinary edit guidance names the native upgrade');assert.ok(/sketch_capabilities\.sharedLayerMotion/.test(source));
 assert.ok(/ordinary layer HOLD keys/.test(source));assert.ok(/not.*(?:contact IK|grip forces|physical forces)/.test(source));
});

test('resolved native bounds preserve2..32layers,1..32poses,pivot0..1 and unwrapped rotation±3600',()=>{
 for(const layerCount of [2,32]){
  const op=operation();op.layerIds=Array.from({length:layerCount},(_,i)=>'part_'+i);op.pivot=[0,1];op.poses=[pose(0,-4,4,-3600),pose(239,4,-4,3600)];
  assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(op)),edit(op));
 }
 const maximum=operation();maximum.poses=Array.from({length:32},(_,i)=>pose(i));assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(maximum)),edit(maximum));
 const single=operation();single.poses=[pose(0)];delete single.poses[0].interpolation;assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(single)),edit(single),'Native alone checks that the current document has exactly one frame; relay preserves omitted linear timing');
 for(const patch of [{layerIds:['part']},{layerIds:Array.from({length:33},(_,i)=>'part_'+i)},{pivot:[-.001,.5]},{pivot:[.5,1.001]},{poses:Array.from({length:33},(_,i)=>pose(i))}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({...operation(),...patch})));
 for(const rotation of [-3600.001,3600.001]){const op=operation();op.poses[1].rotation=rotation;assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(op)));}
});
