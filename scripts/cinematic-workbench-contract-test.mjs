import {test} from 'node:test';
import assert from 'node:assert/strict';
import {CINEMATIC_3D_BEATS,cinematic3dTimelineSchema,nativeCinematic3dParameters} from '../src/cinematic3d-contract.js';

function timeline(action='WAVE') {
 return {schema:'videostudio.cinematic3d.timeline.v1',name:'Workbench',fps:30,motionProvider:'procedural_mannequin',
  actors:{black:{preset:'chibi',headScale:1.1},white:{preset:'robot',material:{color:[.2,.5,.7],metallic:.5,roughness:.4}}},
  shots:[{id:'wave',action,durationFrames:30,hitStopFrames:0,camera:{mode:'wide',fovDegrees:57,shakeIntensity:0},
   vfx:{sparkBurst:false,slashTrail:false,rubble:false},motionStyle:{preset:'anime',intensity:.8}}]};
}
test('all expanded actions and exact model/style inputs survive typed native save',()=>{
 assert.equal(CINEMATIC_3D_BEATS.length,21);
 for(const action of ['IDLE','WALK','RUN','JUMP','DODGE','DUCK','PUNCH','KICK','DANCE','WAVE']) {
  const value=timeline(action),parameters={projectId:'project_1',sourceProjectUpdatedAt:1,bundleId:'bundle_1',timeline:value};
  assert.deepEqual(nativeCinematic3dParameters('cinematic_3d_save_program',parameters).timeline,value);
 }
 const legacy=timeline();delete legacy.actors;delete legacy.shots[0].motionStyle;
 assert.ok(!Object.hasOwn(cinematic3dTimelineSchema.parse(legacy),'actors'));
});
test('unbounded geometry and executable or unknown style/model fields cannot enter a request',()=>{
 for(const change of [v=>{v.actors.black.heightScale=2},v=>{v.actors.black.command='blender'},
  v=>{v.actors.black.preset='quadruped'},v=>{v.shots[0].motionStyle.preset='neural_inference'},
  v=>{v.shots[0].motionStyle.intensity=Infinity},v=>{delete v.actors.white},v=>{v.shots[0].hitStopFrames=3}]) {
  const value=timeline();change(value);assert.equal(cinematic3dTimelineSchema.safeParse(value).success,false);
 }
});
