import test from 'node:test';
import assert from 'node:assert/strict';
import * as contract from '../src/studio-workbench-contract.js';

const settings={renderStyle:'cel',view:'front',handedness:'right',detail:'anatomical',skinColor:'#FFE2DED3',nailColor:'#FFF0EADF',centerX:.5,centerY:.55,size:.55,motion:0,wristMotion:0};
const operation=parts=>({type:'add_hand_animation',layerId:'hand_layer',handId:'grip',settings:{...settings},poses:[{frame:0,indexCurl:.82,middleCurl:.88,ringCurl:.93,littleCurl:.96,thumbCurl:.68,thumbOpposition:.7}],...(parts===undefined?{}:{parts})});
const edit=op=>({sketchId:'study',operationId:'grip',expectedRevision:1,operations:[op]});
const retained=parts=>({id:'retained',kind:'hand',frame:0,exposure:96,color:'#FF000000',width:.0012,fill:false,transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:0,anchorY:0,opacity:1},keys:[],bounds:{x:.2,y:.2,width:.6,height:.6},hand:{settings:{...settings},poses:[{frame:0,indexCurl:.8}],durationFrames:96,fps:24,...(parts===undefined?{}:{parts})}});
test('strict hand pass enum is admitted on ordinary constructor and retained routes without injecting all',()=>{
 for(const part of ['all','palm_forearm','digits']){
  const request=edit(operation(part));assert.deepEqual(contract.nativeStudioParameters('sketch_edit',request),request);
  for(const op of [{type:'add_object',layerId:'hand_layer',object:retained(part)},{type:'update_object',objectId:'old',patch:{hand:retained(part).hand}},{type:'add_layer',layer:{id:'hand_layer',objects:[retained(part)]}}])assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(op)),edit(op));
 }
 const old=edit(operation());assert.deepEqual(contract.nativeStudioParameters('sketch_edit',old),old);assert.equal(Object.hasOwn(contract.nativeStudioParameters('sketch_edit',old).operations[0],'parts'),false);
});
test('partial passes require cel and cannot be inserted into unrelated settings or mistyped',()=>{
 for(const bad of [null,true,false,0,1,{},[],'All','palm','fingers',''])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(operation(bad))));
 for(const part of ['palm_forearm','digits'])for(const style of [undefined,'smooth']){const op=operation(part);if(style===undefined)delete op.settings.renderStyle;else op.settings.renderStyle=style;assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(op)));const o=retained(part);if(style===undefined)delete o.hand.settings.renderStyle;else o.hand.settings.renderStyle=style;assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({type:'add_object',layerId:'hand_layer',object:o})));}
 const wrong=operation();wrong.settings.parts='digits';assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(wrong)));
});
test('all explicit hand passes gate recursively while legacy hands and human parts retain old floors',()=>{
 for(const parts of ['all','palm_forearm','digits'])for(const action of ['sketch_edit','studio_sketch_edit','sketch_live_apply','sketch_correction_propose','sketch_story_create','sketch_story_preview'])for(const parameters of [edit(operation(parts)),{nested:{object:retained(parts)}},{nested:{patch:{hand:retained(parts).hand}}}]){assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.14'},action,parameters),/4\.10\.15/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.15'},action,parameters));}
 const old=operation();delete old.settings.renderStyle;assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},'sketch_edit',edit(old)));assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.14'},'sketch_edit',{nested:{human:{parts:'near_hand'}}}));
});
test('capabilities describe post-visibility cel owner passes without claiming automatic contact',()=>{
 const cap=contract.HAND_ANIMATION_CAPABILITIES.partPasses;assert.ok(cap);assert.equal(cap.minimumNativeVersion,'4.10.15');assert.equal(cap.field,'parts');assert.deepEqual(cap.choices,['all','palm_forearm','digits']);assert.equal(cap.partialRenderStyle,'cel');assert.equal(cap.requiresOwnLayer,true);assert.equal(cap.visibility,'full_hand_before_part_selection');assert.equal(cap.automaticContact,false);assert.equal(cap.recoversUnseenAnatomy,false);
});

test('a retained partial pass rejects a shared layer while complete and all preserve legacy mixed layers',()=>{
 const rect={id:'underpaint',kind:'rect',frame:0,exposure:96,color:'#FF35516B',width:.0012,fill:true,transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:0,anchorY:0,opacity:1},keys:[],bounds:{x:.02,y:.02,width:.96,height:.96}};
 for(const part of ['palm_forearm','digits'])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({type:'add_layer',layer:{id:'mixed',objects:[rect,retained(part)]}})),/own layer/);
 for(const part of [undefined,'all'])assert.doesNotThrow(()=>contract.nativeStudioParameters('sketch_edit',edit({type:'add_layer',layer:{id:'mixed',objects:[rect,retained(part)]}})));
});
