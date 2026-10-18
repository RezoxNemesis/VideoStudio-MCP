import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {auditCombatStaging} from '../src/omega/combat-staging.js';
import {auditWeaponGeometry,interiorCrossing,segmentGap,weaponCore} from '../src/omega/weapon-geometry.js';
import {auditOwnedCombatProof} from './omega-combat-staging.mjs';
const deep=x=>structuredClone(x);
const frames=[0,8,10,12,14,16,18,20,24,30,35];
const envelope=[0,0,0,.10,.79,1,1,1,0,0,0];
function fixture(){
 // Auditable synthetic sample patterned after the authored public exercise.
 // This fixture is NOT native rendering or captured user footage.
 const w=1280,h=720;
 const setups=[{id:'wide_rig',prefix:'wide_',restX:.5,now:[671.01717,424.44504],nowDeg:39.48257,
    core:[[648.96,314.87976],[712.96,222.71976]],amplitude:-97.0172473},
   {id:'rook_wide_rig',prefix:'rook_wide_',restX:.55,now:[674.4282,424.52021],nowDeg:-38.89308,
    core:[[695.04,314.87976],[631.04,222.71976]],amplitude:96.4967571}];
 const source={width:w,height:h,layers:[{id:'wide',visible:true,objects:[]}]};
 const program={frameCount:100,rigs:[]};
 const native={frameCount:100,frames:Array.from({length:100},(_,frame)=>({frame,joints:[],contactErrors:[]}))};
 for(const cfg of setups){
  const ids=['baton','baton_edge','baton_core','baton_grip'].map(n=>cfg.prefix+n);
  const M=cfg.core[0],L=cfg.core[1],anchor={x:M[0]/w,y:M[1]/h};
  const original={x:0,y:0,scaleX:1,scaleY:1,rotation:0,anchorX:anchor.x,anchorY:anchor.y,opacity:1};
  program.rigs.push({id:cfg.id,joints:[{id:'wrist',x:cfg.restX,y:346.88/h}],bindings:ids.map(objectId=>({objectId,jointId:'wrist'}))});
  for(const id of ids)source.layers[0].objects.push({id,kind:'path',frame:0,exposure:36,
    commands:[['M',M[0]/w,M[1]/h],['L',L[0]/w,L[1]/h]],transform:deep(original),
    keys:frames.map((frame,i)=>({frame,interpolation:'linear',transform:{...original,rotation:cfg.amplitude*envelope[i]}}))});
  for(const f of native.frames){
   f.joints.push({rigId:cfg.id,jointId:'wrist',x:cfg.now[0]/w,y:cfg.now[1]/h,rotation:cfg.nowDeg});
   if(f.frame===16)for(const side of ['left','right'])f.contactErrors.push({rigId:cfg.id,jointId:side,errorPixels:0});
  }
 }
 const trace={schema:'videostudio.native-baton-core-trace.v1',frameCount:36,firstFrame:0,lastFrame:35,
  width:w,height:h,fullSilhouetteVerified:false,frames:[]};
 for(let frame=0;frame<36;frame++){
  const at=native.frames[frame],a=weaponCore(source,program,at,'wide_rig'),b=weaponCore(source,program,at,'rook_wide_rig');
  trace.frames.push({frame,wideCore:a,rookCore:b,centerlineGapPx:segmentGap(a,b),tipGapPx:Math.hypot(a[1][0]-b[1][0],a[1][1]-b[1][1])});
 }
 const a=trace.frames[16].wideCore,b=trace.frames[16].rookCore,cross=interiorCrossing(a,b);
 assert.ok(cross,'Fixture centerlines must cross');
 source.layers[0].objects.push({id:'impact_flash',bounds:{x:cross.point[0]/w-.01,y:cross.point[1]/h-.01,width:.02,height:.02}});
 const oldA=weaponCore(source,program,native.frames[16],'wide_rig',{ignoreWeaponRotation:true});
 const oldB=weaponCore(source,program,native.frames[16],'rook_wide_rig',{ignoreWeaponRotation:true});
 const originalTipSep=Math.hypot(oldA[1][0]-oldB[1][0],oldA[1][1]-oldB[1][1]);
 const baseline={schema:'videostudio.weapon-silhouette-contact.v1',beforeTipGapPx:originalTipSep,
  afterTipGapPx:.0788,afterCenterlineGapPx:0,hardFootContactsPreserved:true,releaseDecision:'MANUAL_REVIEW_REQUIRED',
  impactX:cross.point[0]/w,impactY:(cross.point[1]-65)/h};
 const staged={schema:'videostudio.crossed-baton-staging.v1',frame:16,sourceSpace:'native-canvas-pixels',
  releaseDecision:'MANUAL_REVIEW_REQUIRED',previousTipGapPx:.0788,
  realisticGloveDeformationVerified:false,footContactsPreserved:true,
  shaftCrossAngleDeg:cross.angleDeg,intersectionFractionA:cross.fractionA,intersectionFractionB:cross.fractionB,
  gripSeparationPx:Math.hypot(a[0][0]-b[0][0],a[0][1]-b[0][1]),
  tipSeparationPx:Math.hypot(a[1][0]-b[1][0],a[1][1]-b[1][1]),impactX:cross.point[0]/w,impactY:cross.point[1]/h};
 const stageMetrics={weaponContact:baseline,combatStaging:staged};
 return {source,program,native,weaponTrace:trace,stageMetrics};
}
const audit=f=>auditCombatStaging(f||fixture());

test('separated grips and genuine X-shaped interior batons pass technical review, not artistic release',()=>{
 const r=audit();assert.equal(r.status,'MEASURED');assert.equal(r.qualityDecision,'MANUAL_REVIEW_REQUIRED');
 assert.ok(r.measurable.weaponCrossingAngleDeg>35);
 assert.ok(r.measurable.gripSeparationPx>40);
 assert.ok(r.measurable.distalTipSeparationPx>25);
 assert.ok(r.measurable.contactFractions.every(x=>x>.35&&x<.75));
 assert.equal(r.measurable.plantedFeetAtImpact,4);
 assert.equal(r.professionalAnimationVerified,false);
});
test('geometric intersection excludes parallel, tip-only and disconnected lines',()=>{
 assert.equal(interiorCrossing([[0,0],[10,0]],[[0,4],[10,4]]),null);
 const t=interiorCrossing([[0,0],[10,10]],[[0,10],[10,0]]);
 assert.equal(t.angleDeg,90);assert.equal(t.fractionA,.5);
 assert.equal(interiorCrossing([[0,0],[10,0]],[[15,-5],[15,5]]),null);
});
test('parallel shafts with identical tips are never mistaken for an X',()=>{
 const f=fixture();
 const a=f.source.layers[0].objects.find(x=>x.id==='wide_baton_core');
 for(const id of ['wide_baton','wide_baton_edge','wide_baton_core','wide_baton_grip']){
  const obj=f.source.layers[0].objects.find(x=>x.id===id);
  obj.keys[5].transform.rotation=0;
 }
 assert.throws(()=>audit(f),/forged hero baton|rotate together|CROSSED_SHAFT|provenance|cross/);
});
test('reject forged interior-cross angle, fractions, or effect location',()=>{
 for(const name of ['shaftCrossAngleDeg','intersectionFractionA','impactX']){
  const f=fixture();f.stageMetrics.combatStaging[name]+=1;
  assert.throws(()=>audit(f),/disagrees|aligned/);
 }
});
test('reject fake editor anchors that detach the baton from the hand grip',()=>{
 const f=fixture();f.source.layers[0].objects.find(x=>x.id==='rook_wide_baton_core').keys[5].transform.anchorX+=.01;
 assert.throws(()=>audit(f),/rotate together|drawn hand grip/);
});
test('authored source cannot be accepted without 36 continuous native frames',()=>{
 const f=fixture();f.weaponTrace.frames[16].rookCore[1][0]+=5;
 assert.throws(()=>audit(f),/forged rival baton/);
 const g=fixture();g.weaponTrace.frames.splice(15,1);
 assert.throws(()=>audit(g),/missing native weapon frames/);
});
test('protect authored planted feet, original geometry and artistic review boundary',()=>{
 let f=fixture();f.native.frames[16].contactErrors=[];
 assert.throws(()=>audit(f),/planted feet/);
 f=fixture();f.stageMetrics.combatStaging.releaseDecision='AUTO_APPROVED';
 assert.throws(()=>audit(f),/staging falsely asserts/);
 f=fixture();f.stageMetrics.combatStaging.realisticGloveDeformationVerified=true;
 assert.throws(()=>audit(f),/staging falsely asserts/);
});
test('M3.3 CI adapter fails closed on nonexistent proof directory',async()=>{
 const root=await mkdtemp(join(tmpdir(),'omega-m33-'));
 try{await assert.rejects(auditOwnedCombatProof(root),/ENOENT|Missing or unbounded/);
 const result=JSON.parse(await readFile(join(root,'omega-combat-staging.json'),'utf8'));
 assert.equal(result.status,'BLOCKED');
 }finally{await rm(root,{recursive:true,force:true});}
});