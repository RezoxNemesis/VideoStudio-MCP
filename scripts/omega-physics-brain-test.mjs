import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { auditContactRecovery } from '../src/omega/physics-brain.js';
import { auditOwnedPhysics } from './omega-physics-brain.mjs';

function fixture() {
  const frames = Array.from({length:100},(_,frame)=>({
    frame,
    joints:[
      {rigId:'wide_rig',jointId:'wrist',x:(670+(frame-16)*.35)/1280,y:433/720},
      {rigId:'rook_wide_rig',jointId:'wrist',x:(673.5-(frame-16)*.35)/1280,y:433/720},
    ],
    contactErrors: Array.from({length:4},(_,i)=>({
      rigId:i<2?'wide_rig':'rook_wide_rig',
      jointId:i%2?'back_ankle':'front_ankle',errorPixels:0,targetX:.5,targetY:.8,
    })),
  }));
  return {
    trace:{schema:'videostudio.native-rig-tracks.v1',frameCount:100,fps:24,
      width:1280,height:720,frames},
    program:{schema:'videostudio.sketch-animation.v1',frameCount:100,
      rigs:[{id:'wide_rig'},{id:'rook_wide_rig'}],
      interactionCues:[{frame:16,rigA:'wide_rig',rigB:'rook_wide_rig',
        jointA:'wrist',jointB:'wrist',maxGapPx:8}]},
    stage:{batonWristDistancePx:3.5,contactDirector:{
      schema:'videostudio.contact-director.v1',frame:16,joint:'wrist',
      rigA:'wide_rig',rigB:'rook_wide_rig',requestedGapPx:6,
      authoredTolerancePx:80,baselineWristGapPx:32.67,verifiedWristGapPx:3.5,
      rootOrCameraEdited:false,hardFootContactsPreserved:true,
      corrections:[
        {rig:'wide_rig',upperDeltaDeg:-14.64,foreDeltaDeg:3.12},
        {rig:'rook_wide_rig',upperDeltaDeg:16.12,foreDeltaDeg:2.74},
      ]}},
    skeleton:{status:'MEASURED',qualityDecision:'MANUAL_REVIEW_REQUIRED',
      measurements:{frameCount:100,checkedInteractionCues:1,
        hardFootContactSamples:144,maxHardFootSlipPx:0},
      interactionCues:[{frame:16,gapPx:3.5,allowedPx:8}]},
  };
}
function rejectWith(mutator,match=/OMEGA_PHYSICS/) {
  const f=fixture();mutator(f);
  assert.throws(()=>auditContactRecovery(f.trace,f.program,f.stage,f.skeleton),match);
}

test('genuine native wrist proximity is measured, never sold as artistic acceptance',()=>{
  const f=fixture(),r=auditContactRecovery(f.trace,f.program,f.stage,f.skeleton);
  assert.equal(r.status,'MEASURED');
  assert.equal(r.qualityDecision,'MANUAL_REVIEW_REQUIRED');
  assert.equal(r.contact.verifiedGapPx,3.5);
  assert.equal(r.contact.reductionPx,29.17);
  assert.equal(r.contact.nativeFootContactSamplesInWindow,40);
  assert.equal(r.professionalAnimationVerified,false);
  assert.equal(r.physicalPhoneVerified,false);
});
test('unaltered 32.67px hands cannot pass the new strict 8px cue',()=>{
  rejectWith(f=>{const w=f.trace.frames[16].joints[1];w.x=(670+32.67)/1280;},/Actual native wrist proximity/);
});
test('forged native contactDirector receipt cannot override measured wrist geometry',()=>{
  rejectWith(f=>{f.stage.contactDirector.verifiedWristGapPx=1;},/Actual native wrist proximity/);
});
test('M2.1 independent skeleton receipt cannot disagree with new native pose',()=>{
  rejectWith(f=>{f.skeleton.interactionCues[0].gapPx=17;},/Independent M2.1/);
});
test('missing planted-foot evidence fails closed',()=>{
  rejectWith(f=>{f.trace.frames[16].contactErrors.pop();},/Lost a planted-foot/);
});
test('planted foot slip cannot be hidden by a visually closer wrist',()=>{
  rejectWith(f=>{f.trace.frames[16].contactErrors[0].errorPixels=.5;},/Planted foot slipped/);
});
test('report cannot claim it moved character roots or camera',()=>{
  rejectWith(f=>{f.stage.contactDirector.rootOrCameraEdited=true;},/must not translate roots/);
});
test('angle changes above native arm budget are rejected',()=>{
  rejectWith(f=>{f.stage.contactDirector.corrections[1].upperDeltaDeg=39;},/bounded to two articulated arms/);
});
test('unsupported older loose contact cue must not silently pass',()=>{
  rejectWith(f=>{f.program.interactionCues[0].maxGapPx=80;},/strict authored hand contact/);
});
test('impossible one-frame wrist teleport is detected',()=>{
  rejectWith(f=>{f.trace.frames[18].joints[1].x=.95;},/Unbounded one-frame wrist movement/);
});
test('missing rig sample at the impact frame is not counted as good animation',()=>{
  rejectWith(f=>{f.trace.frames[16].joints.pop();},/Native joint/);
});
test('no fake M3 success without original M1 / M2 / M2.1 evidence',async()=>{
  const root=await mkdtemp(join(tmpdir(),'omega-physics-'));
  try{
    await assert.rejects(auditOwnedPhysics(root),/ENOENT|OMEGA_PHYSICS/);
    const failure=JSON.parse(await readFile(join(root,'omega-physics-brain.json'),'utf8'));
    assert.equal(failure.status,'BLOCKED');
    assert.equal(failure.qualityDecision,'BLOCK');
  }finally{await rm(root,{recursive:true,force:true});}
});
