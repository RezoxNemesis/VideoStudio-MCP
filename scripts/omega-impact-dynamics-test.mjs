import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {auditImpactDynamics} from '../src/omega/impact-dynamics.js';
import {auditOwnedImpact} from './omega-impact-dynamics.mjs';
const clone=o=>structuredClone(o);
const ids=['wide_rig','rook_wide_rig'];
function fixture(){
 const frames=[];
 for(let frame=0;frame<100;frame++){
   const joints=[];const contactErrors=[];
   for(let n=0;n<2;n++){
     const rigId=ids[n];
     const rootY=.5 + (frame>=20?.014:0);
     const wristX=.5+(n===1?3/1280:frame===12?30/1280:0);
     for (const [jointId,x,y,rotation] of [
        ['root',n?.7:.3,rootY,0],['wrist',wristX,.5,0],['chest',n?.7:.3,.43,0],
        ['scarf',n?.7:.3,.42,frame>=20?30:10]])
       joints.push({rigId,jointId,x,y,rotation});
     if(frame>=5&&frame<=30)for(const jointId of ['front_ankle','back_ankle'])
       contactErrors.push({rigId,jointId,errorPixels:0});
   }
   frames.push({frame,joints,contactErrors});
 }
 // Impact at frame16: gap 3 px, recoil at20: 10.08 px, 100 native frames.
 const trace={schema:'videostudio.native-rig-tracks.v1',frameCount:100,fps:24,width:1280,height:720,frames};
 const program={schema:'videostudio.sketch-animation.v1',frameCount:100,fps:24,rigs:[]};
 const stage={contactDirector:{schema:'videostudio.contact-director.v1'},impactDynamics:{
   schema:'videostudio.impact-dynamics.v1',impactFrame:16,impactPoseUnchanged:true,
   sourceArtChanged:false,cameraChanged:false,humanReviewRequired:true,
   motionKind:'bounded-authored-pose-response-not-general-physics',
   keyframeCorrections:ids.flatMap(rigId=>[5,11,20,25,30].map(frame=>({rigId,frame}))),
   unchangedContactGapPx:3,originalMaxWristStepPx:48,verifiedMaxWristStepPx:30,
   checkedPlantedFootSamples:104,maxHardFootErrorPx:0,
   heroPostImpactSinkPx:10.08,rivalPostImpactSinkPx:10.08,
 }};
 const previous={schema:'omega.physics-brain.report.v0.3',status:'MEASURED',qualityDecision:'MANUAL_REVIEW_REQUIRED',
   contact:{maxFootContactErrorPx:0,verifiedGapPx:3,maxWristStepPx:30}};
 return {trace,program,stage,previous};
}

test('100-frame articulated evidence can pass numerical QA but never automatic artistic approval',()=>{
 const f=fixture(),r=auditImpactDynamics(f.trace,f.program,f.stage,f.previous);
 assert.equal(r.status,'MEASURED');
 assert.equal(r.qualityDecision,'MANUAL_REVIEW_REQUIRED');
 assert.equal(r.measured.contactGapPx,3);
 assert.equal(r.measured.verifiedMaxWristStepPx,30);
 assert.equal(r.measured.actualHardFootSamples,104);
 assert.equal(r.measured.body.length,2);
 assert.equal(r.professionalActingVerified,false);
 assert.equal(r.weaponCollisionVerified,false);
});

test('missing prepared impact response provenance blocks release',()=>{
 const f=fixture();delete f.stage.impactDynamics;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/stage/);
});

test('falsified claimed improvement is rejected against computed frame steps',()=>{
 const f=fixture();f.stage.impactDynamics.verifiedMaxWristStepPx=6;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/trajectory/);
});

test('wrist gap too wide cannot pass even with a forged stage receipt',()=>{
 const f=fixture();const wrist=f.trace.frames[16].joints.find(x=>x.rigId==='rook_wide_rig'&&x.jointId==='wrist');
 wrist.x+=.04;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/wrist accuracy/);
});

test('break any planted sole and the full-body evidence fails',()=>{
 const f=fixture();f.trace.frames[22].contactErrors.pop();
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/planted foot/);
});

test('native foot error over 0.02 pixels is not acceptable',()=>{
 const f=fixture();f.trace.frames[17].contactErrors[0].errorPixels=.04;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/foot slipping/);
});

test('camera/root sideways translation is not disguised as weight response',()=>{
 const f=fixture();const root=f.trace.frames[20].joints.find(x=>x.rigId==='wide_rig'&&x.jointId==='root');
 root.x+=.1;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/translated sideways/);
});

test('weak fake recoil from unchanged roots fails',()=>{
 const f=fixture();for(const frame of f.trace.frames) if(frame.frame>=20)
   for(const j of frame.joints) if(j.jointId==='root') j.y=.5;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/weight response/);
});

test('secondary cloth-scarf motion cannot be faked with identical angles',()=>{
 const f=fixture();const scarf=f.trace.frames[20].joints.find(x=>x.rigId==='wide_rig'&&x.jointId==='scarf');
 scarf.rotation=10;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/scarf lag/);
});

test('duplicate or out-of-order native frame receipts are refused',()=>{
 const f=fixture();f.trace.frames[14].frame=12;
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/unordered/);
});

test('M3 receipt cannot be bypassed by forged success fields',()=>{
 const f=fixture();f.previous.status='BLOCKED';
 assert.throws(()=>auditImpactDynamics(f.trace,f.program,f.stage,f.previous),/M3 native/);
});

test('bounded owned-file CLI leaves a BLOCK receipt when previous M3 audit missing',async()=>{
 const root=await mkdtemp(join(tmpdir(),'omega-m31-'));
 try {
  await writeFile(join(root,'omega-physics-brain.json'),JSON.stringify({kind:'duel',status:'BLOCKED'}));
  await assert.rejects(auditOwnedImpact(root),/M3 proven/);
  const state=JSON.parse(await readFile(join(root,'omega-impact-dynamics.json'),'utf8'));
  assert.equal(state.qualityDecision,'BLOCK');
 }finally{await rm(root,{recursive:true,force:true});}
});