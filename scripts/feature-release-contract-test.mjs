import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as contract from '../src/studio-workbench-contract.js';

const edit=op=>({sketchId:'shot',operationId:'release_check',expectedRevision:1,operations:[op]});
const {renderStyle:omittedNewHandStyle,...legacyHandSettings}=contract.HAND_ANIMATION_CAPABILITIES.settings;
const hand={id:'hand_rig',kind:'hand',bounds:{x:.2,y:.1,width:.6,height:.8},hand:{settings:legacyHandSettings,poses:[{frame:0}],durationFrames:120,fps:30,sourceFrameOffset:1}};
const human={id:'human_rig',kind:'human',bounds:{x:.2,y:.1,width:.6,height:.8},human:{settings:contract.HUMAN_ANIMATION_CAPABILITIES.settings,poses:[{frame:0}],durationFrames:120,fps:30,sourceFrameOffset:-1}};
const transform={x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:.5,anchorY:.5,opacity:1};
const newOperations=[
 {type:'add_hand_animation',layerId:'layer_1',handId:'hand',settings:{}},
 {type:'add_human_animation',layerId:'layer_1',actorId:'actor',settings:{}},
 {type:'add_human_animation',layerId:'layer_1',actorId:'actor',settings:{},fit:false,blinkTrack:false},
 {type:'add_story_stage',layerId:'layer_1',sceneId:'stage',settings:{}},
 {type:'add_actor_prop',layerId:'layer_1',propId:'prop',actorId:'actor',settings:{}},
 {type:'add_grip_link',layerId:'layer_1',linkId:'link',from:{actorId:'actor',hand:'near'},to:{actorId:'actor',hand:'far'},settings:{}},
 ...[hand,human].flatMap(object=>[
  {type:'add_object',layerId:'layer_1',object},
  {type:'update_object',objectId:object.id,patch:{[object.kind]:object[object.kind]}},
  {type:'add_layer',layer:{id:'actors',objects:[{...object,frame:0,exposure:120,color:'#FFD6A078',width:.001,fill:true,transform,keys:[]}]}}
 ]),
 ...['mouth','face','character'].map(kind=>({type:`add_${kind}_animation`,layerId:'layer_1',[kind+'Id']:'speaker',settings:{},speech:{generated:{requestId:'master',lineIndex:0,shotStartMs:0}}}))
];

test('every first-shipped 4.8 feature rejects all 4.7 app versions through both Node routes',()=>{
 const dialogue=JSON.parse(fs.readFileSync(new URL('../android/app/src/main/assets/animation_stories/the-last-lantern-dialogue-controls.json',import.meta.url),'utf8'));
 const requests=[...newOperations.map(op=>['sketch_edit',edit(op)]),
  ['sketch_animation_place_in_book',{animationId:'scene',expectedAnimationRevision:1,bookId:'book',expectedBookRevision:1,sourceFrameStart:0,frameCount:1,destinationPageStart:0,operationId:'place'}],
  ['open_pipeline_export_request',{projectId:'voices',sourceProjectUpdatedAt:0,requestId:'phrases',backend:'kokoro',assetIds:[],controls:dialogue}]
 ];
 for(const [action,args] of requests)for(const name of [action,'studio_'+action]){
  assert.deepEqual(contract.nativeStudioParameters(name,args),args);
  for(const appVersion of ['4.7.8','4.7.9','4.7.10','4.7.99'])assert.throws(()=>contract.requireStudioVersion({appVersion},name,args),/4\.8\.0/,`${appVersion} ${name} ${args.operations?.[0].type||''}`);
  for(const appVersion of ['4.8.0','4.8.1','4.9.0','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},name,args));
 }
});

test('new capability metadata names the first shipping version while existing 4.7.8 and older contracts retain their floors',()=>{
 for(const name of ['HAND_ANIMATION','HUMAN_ANIMATION','STORY_STAGE','ACTOR_PROP','GRIP_LINK','DIALOGUE_PHRASE','STORY_PLAN','STORY_AUDIO'])
  assert.equal(contract[name+'_CAPABILITIES'].minimumNativeVersion,'4.8.0',name);
 assert.equal(contract.CHARACTER_PERFORMANCE_CAPABILITIES.minimumNativeVersion,'4.7.8');
 for(const [type,field,minimum,extra={}] of [
  ['add_character_animation','characterId','4.7.8'],['add_face_animation','faceId','4.7.6'],['add_mouth_animation','mouthId','4.7.6'],
  ['add_mouth_animation','mouthId','4.7.7',{speech:{generated:{requestId:'master',lineIndex:0}}}]
 ])for(const action of ['sketch_edit','studio_sketch_edit']){
  const args=edit({type,layerId:'layer_1',[field]:'actor',settings:{},...extra});
  assert.deepEqual(contract.nativeStudioParameters(action,args),args);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:minimum},action,args));
 }
});
