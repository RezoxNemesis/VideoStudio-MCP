import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {analyzeNativeSkeleton} from '../src/omega/skeleton-dna.js';
import {auditOwnedSkeleton} from './omega-skeleton.mjs';
const sha=data=>createHash('sha256').update(data).digest('hex');
const clone=x=>structuredClone(x);
const plan=()=>({schema:'omega.skeleton-shot-plan.v1',shots:[{id:'single',start:0,end:3,activeRigs:['actorA','actorB']}]});
function makeFixture({moving=false,drift=false,planted=false,contact=true}={}) {
  const rigs=['actorA','actorB'];
  const coords={actorA:.25,actorB:.65};
  const program={schema:'videostudio.sketch-animation.v1',fps:24,frameCount:4,rigs:rigs.map(id=>({id,
    joints:[{id:'root',x:coords[id],y:.5},{id:'wrist',parent:'root',x:coords[id]+(id==='actorA'?.05:-.05),y:.5},
      {id:'foot',parent:'root',x:coords[id],y:.8}],
    ik:planted?[{hip:'root',knee:'wrist',ankle:'foot',start:1,end:2,x:coords[id],y:.8}]:[],
  }))};
  if(contact)program.interactionCues=[{frame:2,rigA:'actorA',jointA:'wrist',rigB:'actorB',jointB:'wrist',maxGapPx:110}];
  const frames=[];
  for(let f=0;f<4;f++){
    const joints=[],contactErrors=[];
    for(const id of rigs){
      const dx=drift?f*.01:0,base=coords[id]+dx;
      for(const entry of program.rigs.find(r=>r.id===id).joints){
        const shift=(entry.id==='wrist'&&id==='actorA'&&moving)?(f%2)*.03:0;
        const x=entry.id==='wrist'?base+(id==='actorA'?.05:-.05)+shift:
          entry.id==='foot'&&planted&&(f===1||f===2)?coords[id]:base;
        const y=entry.id==='foot'?.8:.5;
        joints.push({rigId:id,jointId:entry.id,parent:entry.parent??null,x,y,rotation:0,
          cameraApplied:false,displayX:x,displayY:y});
      }
      if(planted&&(f===1||f===2))contactErrors.push({rigId:id,jointId:'foot',targetX:coords[id],targetY:.8,errorPixels:0});
    }
    frames.push({frame:f,joints,contactErrors});
  }
  const trace={schema:'videostudio.native-rig-tracks.v1',fps:24,frameCount:4,width:320,height:200,
    sourceSha256:sha('src'),programSha256:sha('prog'),frames};
  return {trace,program,shotPlan:plan()};
}

test('authored rig tracks measure moving wrist independently of the root and camera',()=>{
  const a=makeFixture({moving:true});const out=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.equal(out.status,'MEASURED');assert.equal(out.qualityDecision,'MANUAL_REVIEW_REQUIRED');
  assert.ok(out.measurements.rootRelativeArticulationPairs>0);
  assert.equal(out.measurements.checkedInteractionCues,1);
  assert.equal(out.ownerPhonePlaybackVerified,false);
});

test('global translation alone is root travel, not articulation',()=>{
  const a=makeFixture({drift:true,contact:false});const out=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.equal(out.measurements.rootRelativeArticulationPairs,0);
  assert.ok(out.shots[0].activeRigs[0].rootTravelPx>0);
  assert.ok(out.findings.some(v=>v.code==='NO_ROOT_RELATIVE_ARTICULATION')===false); // short 4-frame fixture
});

test('planted feet checked only when explicitly authored as hard contacts',()=>{
  const a=makeFixture({planted:true,contact:false});const out=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.equal(out.measurements.hardFootContactSamples,4);
  assert.equal(out.measurements.maxHardFootSlipPx,0);
  assert.equal(out.qualityDecision,'MANUAL_REVIEW_REQUIRED');
});

test('contact slip is measured, not waved away with invented performance certification',()=>{
  const a=makeFixture({planted:true,contact:false});
  const joint=a.trace.frames[1].joints.find(x=>x.rigId==='actorA'&&x.jointId==='foot');
  joint.x+=.011;
  a.trace.frames[1].contactErrors.find(x=>x.rigId==='actorA').errorPixels=.011*320;
  const report=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.ok(report.measurements.maxHardFootSlipPx>3);
  assert.ok(report.findings.some(x=>x.code==='PLANTED_FOOT_SLIP'));
});

test('tampered contact receipt differs from measured physical joint location and blocks',()=>{
  const a=makeFixture({planted:true});a.trace.frames[1].contactErrors[0].errorPixels=50;
  assert.throws(()=>analyzeNativeSkeleton(a.trace,a.program,a.shotPlan),/diagnostic disagrees/);
});

test('unknown rig, gapped shot, duplicate joints, invalid index and cyclic bone parents reject',()=>{
  const a=makeFixture();
  const b=clone(a.shotPlan);b.shots[0].activeRigs=['actorZ'];
  assert.throws(()=>analyzeNativeSkeleton(a.trace,a.program,b),/unknown rig/);
  const c=clone(a.shotPlan);c.shots[0].end=2;
  assert.throws(()=>analyzeNativeSkeleton(a.trace,a.program,c),/cover/);
  const d=clone(a.trace);d.frames[0].joints[0]=clone(d.frames[0].joints[1]);
  assert.throws(()=>analyzeNativeSkeleton(d,a.program,a.shotPlan),/Duplicate joint/);
  const e=clone(a.trace);e.frames[2].frame=99;
  assert.throws(()=>analyzeNativeSkeleton(e,a.program,a.shotPlan),/Frame index/);
  const f=clone(a.program);f.rigs[0].joints[0].parent='wrist';
  assert.throws(()=>analyzeNativeSkeleton(a.trace,f,a.shotPlan),/root|Cyclic/);
});

test('interaction proximity measures exact source pixels and warns about loose wide gaps',()=>{
  const a=makeFixture();const r=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.ok(r.interactionCues[0].gapPx>25);
  assert.equal(r.interactionCues[0].withinTolerance,true);
  assert.ok(r.findings.some(x=>x.code==='LOOSE_CONTACT_VISUAL_REVIEW'));
  a.program.interactionCues[0].maxGapPx=2;
  const fail=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.equal(fail.interactionCues[0].withinTolerance,false);
  assert.ok(fail.findings.some(x=>x.code==='INTERACTION_PROXIMITY_MISS'));
});

test('shot boundaries do not create impossible single-frame velocities',()=>{
  const a=makeFixture({moving:true});
  a.shotPlan.shots=[{id:'first',start:0,end:1,activeRigs:['actorA']},{id:'second',start:2,end:3,activeRigs:['actorB']}];
  const r=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.equal(r.measurements.totalRigPairs,2);
});

test('foot-slip metrics are absent, not guessed, when no IK is authored',()=>{
  const a=makeFixture({drift:true});const r=analyzeNativeSkeleton(a.trace,a.program,a.shotPlan);
  assert.equal(r.measurements.hardFootContactSamples,0);
  assert.equal(r.measurements.maxHardFootSlipPx,0);
});

async function fileFixture(){
  const {trace,program,shotPlan}=makeFixture({moving:true,planted:true});
  const root=await mkdtemp(join(tmpdir(),'omega-skeleton-'));
  const source=JSON.stringify({schema:'videostudio.sketch.v1',width:320,height:200});
  const programBytes=JSON.stringify(program),traceBytes=JSON.stringify({...trace,sourceSha256:sha(source),programSha256:sha(programBytes)});
  const movie=Buffer.from('test-only synthetic MOVIE, NOT native encoded MP4');
  const put=async (n,content)=>writeFile(join(root,n),content);
  await put('editable-duel-source.json',source);
  await put('editable-duel-program.json',programBytes);
  await put('native-rig-tracks.json',traceBytes);
  await put('duel.mp4',movie);
  const native={schema:'videostudio.native-duel-proof.v1',rigTracksSha256:sha(traceBytes)};
  await put('native-duel-proof.json',JSON.stringify(native));
  const m1={schema:'omega.native-film-proof.v1',kind:'duel',gate:'READY_FOR_VISUAL_REVIEW',
    releaseDecision:'MANUAL_REVIEW_REQUIRED',frameAudit:{frameEvidence:'PNG_SHA256_VERIFIED'},
    native:{frameCount:4,fps:24,width:320,height:200,sourceSha256:sha(source),programSha256:sha(programBytes)},
    movie:{filename:'duel.mp4',sha256:sha(movie)},sourceRevision:{gitCommitSha:'a'.repeat(40),ownerDeviceProjectRevision:null}};
  const m1Bytes=JSON.stringify(m1);await put('omega-native-report.json',m1Bytes);
  const m2={schema:'omega.motion-dna.report.v0.2',kind:'duel',status:'MEASURED',
    qualityDecision:'MANUAL_REVIEW_REQUIRED',sourceProofSha256:sha(m1Bytes),sourceVideoSha256:sha(movie),
    measured:{originalFrames:4},ownerPhonePlaybackVerified:false};
  await put('omega-motion-dna.json',JSON.stringify(m2));
  return{root,trace,program,shotPlan,native,m1,m2,put};
}

test('owned CI proof ties native traces, actual files, M1/M2 receipts and authoring hashes',async()=>{
  const f=await fileFixture();try{
    const out=await auditOwnedSkeleton(f.root,{planOverride:f.shotPlan});
    assert.equal(out.status,'MEASURED');assert.equal(out.kind,'duel');
    assert.equal(out.provenance.sourceRevision.ownerDeviceProjectRevision,null);
    assert.equal(JSON.parse(await readFile(join(f.root,'omega-skeleton-dna.json'),'utf8')).status,'MEASURED');
  }finally{await rm(f.root,{recursive:true,force:true});}
});

test('trace modified after native capture fails closed with BLOCK receipt',async()=>{
  const f=await fileFixture();try{
    await f.put('native-rig-tracks.json',JSON.stringify({...f.trace,sourceSha256:'0'.repeat(64)}));
    await assert.rejects(auditOwnedSkeleton(f.root,{planOverride:f.shotPlan}),/trace differs/);
    const out=JSON.parse(await readFile(join(f.root,'omega-skeleton-dna.json'),'utf8'));
    assert.equal(out.qualityDecision,'BLOCK');
  }finally{await rm(f.root,{recursive:true,force:true});}
});

test('M2 proof forged revision and modified movie are rejected',async()=>{
  const f=await fileFixture();try{
    f.m2.sourceProofSha256='0'.repeat(64);await f.put('omega-motion-dna.json',JSON.stringify(f.m2));
    await assert.rejects(auditOwnedSkeleton(f.root,{planOverride:f.shotPlan}),/M2 references/);
    f.m2.sourceProofSha256=sha(JSON.stringify(f.m1));await f.put('omega-motion-dna.json',JSON.stringify(f.m2));
    await f.put('duel.mp4',Buffer.from('ALTERED'));
    await assert.rejects(auditOwnedSkeleton(f.root,{planOverride:f.shotPlan}),/movie has changed/);
  }finally{await rm(f.root,{recursive:true,force:true});}
});

test('story film is not silently relabelled as tracked skeleton',async()=>{
  const f=await fileFixture();try{
    f.m1.kind='story';await f.put('omega-native-report.json',JSON.stringify(f.m1));
    await assert.rejects(auditOwnedSkeleton(f.root,{planOverride:f.shotPlan}),/M1 full native gate/);
  }finally{await rm(f.root,{recursive:true,force:true});}
});
