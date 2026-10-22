import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as contract from '../src/studio-workbench-contract.js';
const settings={view:'front',handedness:'right',detail:'anatomical',skinColor:'#FFD6A078',nailColor:'#FFE5C1AC',centerX:.5,centerY:.55,size:.55,motion:0,wristMotion:0};
const operation=angles=>({type:'add_hand_animation',layerId:'hand_layer',handId:'grip',settings:{view:'front'},poses:[{frame:0,indexCurl:.82,...(angles===undefined?{}:{fingerJointAngles:angles})}]});
const edit=operation=>({sketchId:'study',operationId:'pose',expectedRevision:1,operations:[operation]});
const retained=angles=>({id:'hand',kind:'hand',frame:0,exposure:96,color:'#FF000000',fill:false,keys:[],transform:{x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:0,anchorY:0,opacity:1},width:.0012,bounds:{x:.2,y:.2,width:.6,height:.6},hand:{settings:{...settings},poses:operation(angles).poses,durationFrames:96,fps:24}});
test('strict independent triples preserve explicit and omitted constructor/retained input',()=>{
 for(const angles of [{index:[90,80,35]},{index:[0,0,0],middle:[110,120,90],ring:[85,95,40],little:[90,80,35]}]){
  const request=edit(operation(angles));assert.deepEqual(contract.nativeStudioParameters('sketch_edit',request),request);
  for(const op of [{type:'add_object',layerId:'hand_layer',object:retained(angles)},{type:'update_object',objectId:'hand',patch:{hand:retained(angles).hand}},{type:'add_layer',layer:{id:'new_layer',objects:[retained(angles)]}}])assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(op)),edit(op));
 }
 const old=edit(operation());assert.deepEqual(contract.nativeStudioParameters('sketch_edit',old),old);assert.equal(Object.hasOwn(contract.nativeStudioParameters('sketch_edit',old).operations[0].poses[0],'fingerJointAngles'),false);
});
test('map, names, tuple numbers and all degree ranges are strict without accepting field under settings',()=>{
 const invalid=[null,true,false,1,'90,80,35',[],{}, {thumb:[90,80,35]},{Index:[90,80,35]}];
 for(const value of [null,true,'90',{},[],[90,80],[90,80,35,0],['90',80,35],[true,80,35],[90,null,35],[NaN,80,35],[90,Infinity,35],[90,80,-Infinity],[-.001,80,35],[110.001,80,35],[90,-.001,35],[90,120.001,35],[90,80,-.001],[90,80,90.001]])invalid.push({index:value});
 for(const angles of invalid){assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(operation(angles))));assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({type:'add_object',layerId:'hand_layer',object:retained(angles)})));}
 const wrong=operation();wrong.settings.fingerJointAngles={index:[90,80,35]};assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(wrong)));
});
test('explicit degrees gate recursively at4.10.16 while omitted old hand floor remains4.8.0',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit','sketch_live_apply','sketch_correction_propose','sketch_story_create','sketch_story_preview'])for(const parameters of [edit(operation({index:[90,80,35]})),{nested:{object:retained({index:[90,80,35]})}},{nested:{patch:{hand:retained({index:[90,80,35]}).hand}}}]){assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.15'},action,parameters),/4\.10\.16/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.16'},action,parameters));}
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.0'},'sketch_edit',edit(operation())));
});
test('native and Node capabilities share exact finite-degree contract without contact claim',()=>{
 const expected={minimumNativeVersion:'4.10.16',field:'poses[].fingerJointAngles',fingers:['index','middle','ring','little'],jointOrder:['MCP','PIP','DIP'],degreeLimits:[[0,110],[0,120],[0,90]],interpolation:'resolved_endpoint_angles',automaticContact:false};assert.deepEqual(contract.HAND_ANIMATION_CAPABILITIES.fingerJointAngles,expected);
 const source=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchFingerJointAngles.java',import.meta.url),'utf8');const match=source.match(/CAPABILITY_JSON\s*=\s*("(?:[^"\\]|\\.)*")/);assert.ok(match);assert.deepEqual(JSON.parse(JSON.parse(match[1])),expected);
});
