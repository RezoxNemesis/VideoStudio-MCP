/**
 * OMEGA M3.2: independent auditor for EDITABLE weapon vector centre-lines.
 * Uses the exact authored 2D wrist-space transform contract, not hand proximity.
 * The native frame raster/MP4 has separate hash and FFprobe provenance; this
 * module does not certify full silhouette collision, expressive acting or physics.
 */
const bad = why => { throw new Error('OMEGA_WEAPON_QA: ' + why); };
const required = (ok,why) => { if(!ok) bad(why); };
const finite = n => typeof n === 'number' && Number.isFinite(n);
const sha = /^[a-f\d]{64}$/i;
const groups = {
  wide_rig: ['wide_baton','wide_baton_edge','wide_baton_core','wide_baton_grip'],
  rook_wide_rig: ['rook_wide_baton','rook_wide_baton_edge','rook_wide_baton_core','rook_wide_baton_grip'],
};
const cap=(n,places=4)=>Number(n.toFixed(places));
function dict(v){return v&&typeof v==='object'&&!Array.isArray(v);}
function dist(p,q){return Math.hypot(p[0]-q[0],p[1]-q[1]);}
function object(source,id){
  const found=source.layers.flatMap(layer=>layer.objects||[]).filter(o=>o.id===id);
  required(found.length===1,`missing/duplicate editable weapon path ${id}`);
  return found[0];
}
function rig(program,id){const matches=program.rigs.filter(r=>r.id===id); required(matches.length===1,'invalid rig '+id);return matches[0];}
function wrist(program,id){const joints=rig(program,id).joints.filter(j=>j.id==='wrist');required(joints.length===1,'missing wrist '+id);return joints[0];}
function normalizeAngles(degrees){return (degrees+540)%360-180;}
function lerpKey(o,frame){
  const keys=o.keys||[];let left=null,right=null;
  for(const k of keys){if(k.frame<=frame)left=k;else {right=k;break;}}
  if(!left)return o.transform;
  if(!right||left.interpolation==='hold')return left.transform;
  const t=(frame-left.frame)/(right.frame-left.frame);
  const a=left.transform,b=right.transform;const v={};
  for(const f of ['x','y','scaleX','scaleY','rotation','anchorX','anchorY','opacity']) v[f]=a[f]+(b[f]-a[f])*t;
  return v;
}
function currentJoint(nativeFrame,id){
  const found=nativeFrame.joints.filter(j=>j.rigId===id&&j.jointId==='wrist');
  required(found.length===1,`missing native solver joint for ${id}`);
  required(['x','y','rotation'].every(k=>finite(found[0][k])),'invalid native wrist sample');
  return found[0];
}
/** Derive the exact 2D path centre-line from owner-authored source and native FK wrist pose. */
export function weaponCore(source,program,nativeFrame,id,{ignoreWeaponRotation=false}={}){
  const w=source.width,h=source.height;
  required(Number.isInteger(w)&&w>=256&&w<=2048&&Number.isInteger(h)&&h>=256&&h<=2048,'unsupported vector canvas');
  const idx=id==='wide_rig'?2:id==='rook_wide_rig'?2:-1;
  required(idx>=0,'invalid weapon rig');
  const ids=groups[id],obj=object(source,ids[idx]);
  required(obj.kind==='path'&&Array.isArray(obj.commands)&&obj.commands.length===2&&obj.commands[0][0]==='M'&&obj.commands[1][0]==='L','invalid native M/L weapon core');
  required(obj.frame===0&&obj.exposure>=36,'weapon not exposed through wide shot');
  const rest=wrist(program,id);const now=currentJoint(nativeFrame,id);
  const transform=lerpKey(obj,nativeFrame.frame);
  required(dict(transform)&&['x','y','rotation','anchorX','anchorY','scaleX','scaleY','opacity'].every(f=>finite(transform[f])),'missing vector affine transform');
  required(Math.abs(transform.x)<1e-8&&Math.abs(transform.y)<1e-8&&Math.abs(transform.scaleX-1)<1e-8&&Math.abs(transform.scaleY-1)<1e-8&&transform.opacity>0.99,'unexpected edited vector scale/translation/opacity');
  const theta=(now.rotation+(ignoreWeaponRotation?0:transform.rotation))*Math.PI/180;
  const c=Math.cos(theta),s=Math.sin(theta);
  const point=cmd=>{
    required(cmd.length===3&&finite(cmd[1])&&finite(cmd[2]),'invalid authored M/L point');
    const dx=(cmd[1]-rest.x)*w,dy=(cmd[2]-rest.y)*h;
    return [now.x*w+c*dx-s*dy,now.y*h+s*dx+c*dy];
  };
  return [point(obj.commands[0]),point(obj.commands[1])];
}
function pointLine(p,a,b){
  const dx=b[0]-a[0],dy=b[1]-a[1],sq=dx*dx+dy*dy;
  if(sq<=1e-9)return dist(p,a);
  const t=Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/sq));
  return Math.hypot(p[0]-(a[0]+dx*t),p[1]-(a[1]+dy*t));
}
const cross=(a,b,c)=>(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
export function segmentGap([a,b],[c,d]){
  required([a,b,c,d].every(p=>Array.isArray(p)&&p.length===2&&p.every(finite)),'bad centre-line segment');
  const o1=cross(a,b,c),o2=cross(a,b,d),o3=cross(c,d,a),o4=cross(c,d,b);
  if(o1*o2<0&&o3*o4<0)return 0;
  return Math.min(pointLine(a,c,d),pointLine(b,c,d),pointLine(c,a,b),pointLine(d,a,b));
}
function evidenceMatch(a,b,why){required(dist(a,b)<=.02,why);}
/** Fails closed if any native weapon trace is inconsistent with source and 100 authored solver frames. */
export function auditWeaponGeometry({source,program,native,weaponTrace,stageMetrics}){
  required(dict(source)&&dict(program)&&dict(native)&&dict(weaponTrace)&&dict(stageMetrics),'source/program/native evidence required');
  required(source.width===1280&&source.height===720&&program.frameCount===100&&native.frameCount===100,'unexpected native reviewed duel size');
  required(Array.isArray(native.frames)&&native.frames.length===100,'need all native rig frames');
  required(weaponTrace.schema==='videostudio.native-baton-core-trace.v1'&&weaponTrace.frameCount===36&&weaponTrace.firstFrame===0&&weaponTrace.lastFrame===35&&weaponTrace.width===source.width&&weaponTrace.height===source.height,'unexpected native baton trace');
  required(weaponTrace.fullSilhouetteVerified===false,'prohibited film-grade silhouette certification');
  required(Array.isArray(weaponTrace.frames)&&weaponTrace.frames.length===36,'missing native weapon frames');
  required(dict(stageMetrics.weaponContact)&&stageMetrics.weaponContact.schema==='videostudio.weapon-silhouette-contact.v1','missing original native geometric correction');
  for(const [id,ids] of Object.entries(groups)){
    const referenceRig=rig(program,id);const rest=wrist(program,id);
    const seen=new Set();
    for(const name of ids){
      const sourcePath=object(source,name);
      required(sourcePath.kind==='path'&&sourcePath.frame===0&&sourcePath.exposure>=36,'weapon is not a path throughout wide shot');
      const bindings=referenceRig.bindings.filter(b=>b.objectId===name);
      required(bindings.length===1&&bindings[0].jointId==='wrist'&&!bindings[0].weights,'not rigidly bound to authored wrist: '+name);
      required(Array.isArray(sourcePath.keys)&&sourcePath.keys.length>=5,'weapon has no original editable pivot keys');
      required(sourcePath.keys.some(k=>k.frame===16),'impact lacks authored weapon key');
      const mid=lerpKey(sourcePath,16);
      required(Math.abs(mid.anchorX-rest.x)<1e-6&&Math.abs(mid.anchorY-rest.y)<1e-6,'weapon rotation not pivoted on wrist');
      const signature=sourcePath.keys.map(k=>`${k.frame}:${cap(k.transform.rotation,6)}`).join('/');
      seen.add(signature);
    }
    required(seen.size===1,'weapon shell, edge, core and grip must rotate together');
  }
  let maximumTipStep=0, prev=null;
  const verifiedFrames=[];
  for(let i=0;i<36;i++){
    const nativeRow=native.frames[i],t=weaponTrace.frames[i];
    required(nativeRow.frame===i&&t.frame===i,'native trace frame coverage mismatch');
    const actualA=weaponCore(source,program,nativeRow,'wide_rig');
    const actualB=weaponCore(source,program,nativeRow,'rook_wide_rig');
    const suppliedA=t.wideCore,suppliedB=t.rookCore;
    required(Array.isArray(suppliedA)&&suppliedA.length===2&&Array.isArray(suppliedB)&&suppliedB.length===2,'missing original native frame geometry');
    evidenceMatch(actualA[0],suppliedA[0],'forged hero baton grip at '+i);
    evidenceMatch(actualA[1],suppliedA[1],'forged hero baton tip at '+i);
    evidenceMatch(actualB[0],suppliedB[0],'forged rival baton grip at '+i);
    evidenceMatch(actualB[1],suppliedB[1],'forged rival baton tip at '+i);
    const gap=segmentGap(actualA,actualB), tipGap=dist(actualA[1],actualB[1]);
    required(finite(t.centerlineGapPx)&&Math.abs(t.centerlineGapPx-gap)<=.02,'forged centre-line gap at '+i);
    required(finite(t.tipGapPx)&&Math.abs(t.tipGapPx-tipGap)<=.02,'forged actual tip gap at '+i);
    for(const xy of [...actualA,...actualB])required(xy[0]>=0&&xy[0]<=source.width&&xy[1]>=0&&xy[1]<=source.height,'drawn weapon leaves viewport at '+i);
    if(prev&&i>=8&&i<=24)maximumTipStep=Math.max(maximumTipStep,dist(actualA[1],prev[0]),dist(actualB[1],prev[1]));
    if(i===0||i===8||i===12||i===14||i===16||i===18||i===20||i===24||i===35)verifiedFrames.push({frame:i,tipGapPx:cap(tipGap),centerlineGapPx:cap(gap)});
    prev=[actualA[1],actualB[1]];
  }
  const at=native.frames[16];
  const a=weaponCore(source,program,at,'wide_rig'), b=weaponCore(source,program,at,'rook_wide_rig');
  const oldA=weaponCore(source,program,at,'wide_rig',{ignoreWeaponRotation:true});
  const oldB=weaponCore(source,program,at,'rook_wide_rig',{ignoreWeaponRotation:true});
  const originalTip=dist(oldA[1],oldB[1]),correctedTip=dist(a[1],b[1]),correctedCenter=segmentGap(a,b);
  const point=[(a[1][0]+b[1][0])*.5,(a[1][1]+b[1][1])*.5];
  const visualFlash=object(source,'impact_flash');
  required(dict(visualFlash.bounds),'missing authored visible impact effect');
  const flashCenter=[(visualFlash.bounds.x+visualFlash.bounds.width/2)*source.width,(visualFlash.bounds.y+visualFlash.bounds.height/2)*source.height];
  const flashError=dist(point,flashCenter);
  required(originalTip>=100&&correctedTip<=6&&correctedCenter<=6,'weapon shaft/tip contact has not been corrected');
  required(flashError<=1,'impact flash is not aligned with actual drawn tip collision');
  required(maximumTipStep<85,'unbounded one-frame weapon tip snap');
  const metrics=stageMetrics.weaponContact;
  required(Math.abs(metrics.beforeTipGapPx-originalTip)<.1&&Math.abs(metrics.afterTipGapPx-correctedTip)<.1,'native tip-correction provenance differs from independently computed geometry');
  required(Math.abs(metrics.afterCenterlineGapPx-correctedCenter)<.1,'native centre-line measurement inconsistent');
  required(metrics.hardFootContactsPreserved===true&&metrics.releaseDecision==='MANUAL_REVIEW_REQUIRED','weapon correction overstated acceptance');
  const planted=at.contactErrors||[];
  required(planted.length===4&&planted.every(f=>finite(f.errorPixels)&&f.errorPixels<.02),'real native planted feet missing/unstable');
  return {
    schema:'omega.weapon-geometry.report.v0.3.2',status:'MEASURED',qualityDecision:'MANUAL_REVIEW_REQUIRED',
    measured:{nativeFrameCount:100,verifiedWideFrames:36,
      originalTipSeparationPx:cap(originalTip),correctedTipSeparationPx:cap(correctedTip),
      correctedCenterlineGapPx:cap(correctedCenter),tipGapImprovementPx:cap(originalTip-correctedTip),
      effectToTipContactOffsetPx:cap(flashError),maximumWideShotTipStepPx:cap(maximumTipStep),
      nativePlantedFeetAtImpact:planted.length},
    impactPoint:{x:cap(point[0]),y:cap(point[1])},observedWideFrames:verifiedFrames,
    notVerified:['Complete weapon silhouette overlap, contact thickness and collision force',
      '3D swept weapon collision or detailed hand finger grip',
      'Automatic realistic anatomy, physically simulated cloth/hair or facial acting',
      'Pixel-perfect screenshots or professional cinematic review','Real Android owner-device playback or audio'],
    professionalAnimationVerified:false,physicalPhoneVerified:false,automaticFilmReleaseApproved:false,
  };
}