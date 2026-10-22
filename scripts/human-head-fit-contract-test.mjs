import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion,HUMAN_ANIMATION_CAPABILITIES} from '../src/studio-workbench-contract.js';

const profile={jawWidth:.82,chinLength:.94,eyeWidth:.9};
const settings={figureStyle:'anatomy_sketch',headProportions:profile};
const edit=s=>({sketchId:'sketch',expectedRevision:1,operationId:'head_fit',operations:[{type:'add_human_animation',layerId:'layer_1',actorId:'actor',settings:s}]});
const retained=s=>({id:'actor_rig',kind:'human',frame:0,exposure:48,human:{settings:{view:'front',build:'slender',hairStyle:'long',skinColor:'#FFF1EEE8',hairColor:'#FF555150',coatColor:'#FF526B78',trouserColor:'#FF343F50',x:.5,floorY:.88,size:1,...s},poses:[{frame:0}],durationFrames:48,fps:24}});

test('anatomy head proportions are strict finite numeric scales without injected defaults',()=>{
 for(const headProportions of [profile,{jawWidth:1},{chinLength:.75},{eyeWidth:1.25},{jawWidth:1,chinLength:1,eyeWidth:1}]){const request=edit({figureStyle:'anatomy_sketch',headProportions});assert.deepEqual(nativeStudioParameters('sketch_edit',request),request);}
 const omitted=edit({figureStyle:'anatomy_sketch'});assert.deepEqual(nativeStudioParameters('sketch_edit',omitted),omitted);assert.equal(Object.hasOwn(nativeStudioParameters('sketch_edit',omitted).operations[0].settings,'headProportions'),false);
});
test('malformed and incompatible proportional profiles are rejected rather than coerced',()=>{
 // A valid control first prevents the old unknown-field rejection from disguising missing support.
 assert.deepEqual(nativeStudioParameters('sketch_edit',edit(settings)),edit(settings));
 for(const headProportions of [null,true,'1',1,[],{}, {unknown:1}])assert.throws(()=>nativeStudioParameters('sketch_edit',edit({figureStyle:'anatomy_sketch',headProportions})));
 for(const key of ['jawWidth','chinLength','eyeWidth'])for(const value of [null,true,'1',NaN,Infinity,-Infinity,.749,1.251,{},[]])assert.throws(()=>nativeStudioParameters('sketch_edit',edit({figureStyle:'anatomy_sketch',headProportions:{[key]:value}})));
 for(const figureStyle of [undefined,'legacy'])assert.throws(()=>nativeStudioParameters('sketch_edit',edit({...(figureStyle?{figureStyle}:{}),headProportions:profile})));
});
test('explicit profile including identity requires future4.10.13 through every recursive envelope',()=>{
 for(const headProportions of [profile,{jawWidth:1,chinLength:1,eyeWidth:1}]){
  const request=edit({figureStyle:'anatomy_sketch',headProportions});
  const object=retained({figureStyle:'anatomy_sketch',headProportions});
  const envelopes=[['sketch_edit',request],['studio_sketch_edit',request],['sketch_edit',{...request,operations:[{type:'add_object',layerId:'layer_1',object}]}],['sketch_edit',{...request,operations:[{type:'update_object',objectId:'actor_rig',patch:{human:object.human}}]}],['sketch_edit',{...request,operations:[{type:'add_layer',layer:{id:'fitted',objects:[object]}}]}],['sketch_story_create',{shots:[{operations:request.operations}]}],['sketch_story_preview',{shots:[{operations:request.operations}]}],['sketch_correction_propose',{operations:request.operations}],['sketch_component_apply',{component:{layers:[{objects:[object]}]}}]];
  for(const [action,payload] of envelopes){assert.throws(()=>requireStudioVersion({appVersion:'4.10.12'},action,payload),/4\.10\.13/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.13'},action,payload));}
 }
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.8.3'},'sketch_edit',edit({figureStyle:'anatomy_sketch'})));
});
test('ordinary native and ChatGPT capability describes the construction contract and limits',()=>{
 const capability=HUMAN_ANIMATION_CAPABILITIES.headProportions;assert.ok(capability,'Advertise the shared ordinary head fitting capability');assert.equal(capability.minimumNativeVersion,'4.10.13');assert.equal(capability.field,'headProportions');assert.deepEqual(capability.fields,['jawWidth','chinLength','eyeWidth']);assert.equal(capability.figureStyle,'anatomy_sketch');assert.equal(capability.recoversUnseenCharacterViews,false);assert.equal(capability.guaranteesAiIdentity,false);
});
