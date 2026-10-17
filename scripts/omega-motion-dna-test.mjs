import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { analyzePair, analyzeSequence, validateShotPlan } from '../src/omega/motion-dna.js';
import { auditOwnedMotion } from './omega-motion-dna.mjs';

const W=160, H=90;
const ROI=[[0.32,0.25,0.34,0.62]];
const bg=(x,y)=>((x*23 ^ y*97 ^ ((x*y*17)>>3)) & 255);
function image({panX=0,panY=0,actorX=66,actorY=44,visible=true,light=0}={}) {
  const frame=new Uint8Array(W*H);
  for(let y=0;y<H;y++)for(let x=0;x<W;x++) {
    let val=bg(x-panX,y-panY);
    if(visible && x>=actorX+panX && x<actorX+panX+17 &&
       y>=actorY+panY && y<actorY+panY+20) val=226;
    frame[y*W+x]=Math.min(255,Math.max(0,val+light));
  }
  return frame;
}
const shot=(from,to,id='scene')=>({id,start:from,end:to,regions:ROI});
const plan=(...shots)=>({schema:'omega.motion-shot-plan.v1',shots});
function label(a,b) {return analyzePair(a,b,W,H,ROI).label;}
function countOf(report,kind) {return report.shots.flatMap(s=>s.pairs).filter(p=>p.label===kind).length;}

test('static shot cannot be mislabeled as character animation',()=>{
  const before=image();
  const result=analyzePair(before,before,W,H,ROI);
  assert.equal(result.label,'NEAR_STATIC');
  assert.equal(result.globalTranslation.distancePx,0);
});

test('changing character rectangle against fixed textured scene is localized motion candidate',()=>{
  const result=analyzePair(image(),image({actorX:73}),W,H,ROI);
  assert.equal(result.label,'LOCAL_MOTION_CANDIDATE');
  assert.equal(result.globalTranslation.distancePx,0);
  assert.ok(result.normalizedMetrics.localizedChangeExcess>=.045);
});

test('whole scene camera pan is identified as global shift, not acting',()=>{
  const result=analyzePair(image(),image({panX:3,panY:2}),W,H,ROI);
  assert.equal(result.globalTranslation.dx,3);
  assert.equal(result.globalTranslation.dy,2);
  assert.equal(result.label,'GLOBAL_TRANSLATION_CANDIDATE');
});

test('independent character movement over panning background is not camera-only',()=>{
  const result=analyzePair(image(),image({panX:3,panY:1,actorX:75}),W,H,ROI);
  assert.equal(result.label,'LOCAL_MOTION_OVER_CAMERA');
});

test('full-frame lighting pulse must not certify localized character acting',()=>{
  const result=analyzePair(image(),image({light:45}),W,H,ROI);
  assert.notEqual(result.label,'LOCAL_MOTION_CANDIDATE');
  assert.notEqual(result.label,'LOCAL_MOTION_OVER_CAMERA');
});

test('shot boundary crossing is excluded, not counted as contact/actor motion',()=>{
  const samples=[image(),image({actorX:72}),image({actorX:95}),image({actorX:102})];
  const result=analyzeSequence(samples,{width:W,height:H,originalFrameCount:16,
    shotPlan:plan(shot(0,7,'A'),shot(8,15,'B'))});
  assert.equal(result.measured.excludedShotBoundaries,1);
  assert.equal(result.measured.evaluatedPairs,2);
  assert.equal(result.shots.length,2);
  assert.deepEqual(result.shots[1].pairs[0].sourceFrames,[8,12]);
});

test('static sequence reports lack of localized motion and requires review',()=>{
  const samples=Array.from({length:5},()=>image());
  const result=analyzeSequence(samples,{width:W,height:H,originalFrameCount:20,shotPlan:plan(shot(0,19))});
  assert.equal(result.measured.localizedMotionCandidatePairs,0);
  assert.equal(result.measured.nearStaticPairs,4);
  assert.equal(result.qualityDecision,'MANUAL_REVIEW_REQUIRED');
  assert.ok(result.warnings.some(w=>w.includes('NO_LOCALIZED')));
});

test('invalid shot plans, ROI and sample counts reject rather than invent metrics',()=>{
  assert.throws(()=>validateShotPlan(plan(shot(0,8),shot(8,19)),20),/contiguously/);
  assert.throws(()=>validateShotPlan(plan({...shot(0,19),regions:[[0.9,0.2,0.5,0.2]]}),20),/fit/);
  assert.throws(()=>analyzeSequence([image(),image(),image()],{width:W,height:H,originalFrameCount:20,
    shotPlan:plan(shot(0,19))}),/sample count/);
  assert.throws(()=>analyzePair(new Uint8Array(5),image(),W,H,ROI),/length/);
});

test('M2 file gate validates verified M1 movie hash before accepting an injected decoder',async()=>{
  const root=await mkdtemp(join(tmpdir(),'omega-motion-'));
  const movie=Buffer.from('fixture bytes, not a real movie: deliberately supplied to injected decoder');
  const sha=createHash('sha256').update(movie).digest('hex');
  try {
    await writeFile(join(root,'fixture.mp4'),movie);
    const receipt={ schema:'omega.native-film-proof.v1',kind:'duel',gate:'READY_FOR_VISUAL_REVIEW',
      releaseDecision:'MANUAL_REVIEW_REQUIRED',frameAudit:{frameEvidence:'PNG_SHA256_VERIFIED'},
      movie:{filename:'fixture.mp4',sha256:sha},native:{frameCount:100,fps:24},sourceRevision:{gitCommitSha:null}};
    await writeFile(join(root,'omega-native-report.json'),JSON.stringify(receipt));
    const raw=Buffer.concat(Array.from({length:25},(_,i)=>Buffer.from(image({actorX:66+i%6}))));
    const validated=await auditOwnedMotion(root,'duel',{decode:async()=>raw});
    assert.equal(validated.measured.decodedSampleCount,25);
    assert.equal(validated.qualityDecision,'MANUAL_REVIEW_REQUIRED');
    assert.equal(validated.ownerPhonePlaybackVerified,false);
    assert.equal(validated.sourceVideoSha256,sha);
    await writeFile(join(root,'fixture.mp4'),'tampered');
    await assert.rejects(auditOwnedMotion(root,'duel',{decode:async()=>raw}),/changed since M1/);
    assert.equal(JSON.parse(await readFile(join(root,'omega-motion-dna.json'),'utf8')).status,'BLOCKED');
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('M2 refuses receipt bypass and unsafe film names without running decoder',async()=>{
  const root=await mkdtemp(join(tmpdir(),'omega-motion-'));
  let decoderCalled=false;
  const decode=async()=>{decoderCalled=true;return Buffer.alloc(14400*25)};
  try {
    await writeFile(join(root,'omega-native-report.json'),JSON.stringify({gate:'BLOCK',kind:'story'}));
    await assert.rejects(auditOwnedMotion(root,'story',{decode}),/M1 audit/);
    assert.equal(decoderCalled,false);
    const receipt={schema:'omega.native-film-proof.v1',kind:'story',gate:'READY_FOR_VISUAL_REVIEW',
      releaseDecision:'MANUAL_REVIEW_REQUIRED',frameAudit:{frameEvidence:'PNG_SHA256_VERIFIED'},
      movie:{filename:'../evil.mp4',sha256:'a'.repeat(64)},native:{frameCount:240,fps:24}};
    await writeFile(join(root,'omega-native-report.json'),JSON.stringify(receipt));
    await assert.rejects(auditOwnedMotion(root,'story',{decode}),/safe basename/);
    assert.equal(decoderCalled,false);
  } finally { await rm(root,{recursive:true,force:true}); }
});
