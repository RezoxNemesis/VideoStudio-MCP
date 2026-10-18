#!/usr/bin/env node
/** OMEGA M3.2: read-only audit of CI-owned native weapon paths and prior film evidence. */
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { auditWeaponGeometry } from '../src/omega/weapon-geometry.js';
const SHA=/^[a-f\d]{64}$/i;
const correct=(v,message)=>{if(!v)throw new Error('OMEGA_WEAPON_PROOF: '+message);};
async function digest(path){const h=createHash('sha256');for await(const chunk of createReadStream(path))h.update(chunk);return h.digest('hex');}
async function checkedJson(path,max=3*1024*1024){
 const info=await lstat(path);
 correct(info.isFile()&&!info.isSymbolicLink()&&info.size>0&&info.size<=max,'untrusted or unbounded evidence file '+basename(path));
 return JSON.parse(await readFile(path,'utf8'));
}
export async function auditOwnedWeaponProof(folder){
 const root=resolve(folder), output=join(root,'omega-weapon-contact.json');
 try{
  const dir=await lstat(root);correct(dir.isDirectory()&&!dir.isSymbolicLink(),'expected explicitly selected CI-owned proof folder');
  const [m1,m2,m21,m3,m31,nativeManifest,source,program,joints,trace,stage]=await Promise.all([
   'omega-native-report.json','omega-motion-dna.json','omega-skeleton-dna.json','omega-physics-brain.json',
   'omega-impact-dynamics.json','native-duel-proof.json','editable-duel-source.json','editable-duel-program.json',
   'native-rig-tracks.json','native-weapon-tracks.json','duel-stage-metrics.json'].map(name=>checkedJson(join(root,name))));
  correct(m1.schema==='omega.native-film-proof.v1'&&m1.kind==='duel'&&m1.gate==='READY_FOR_VISUAL_REVIEW'&&m1.releaseDecision==='MANUAL_REVIEW_REQUIRED'&&m1.frameAudit?.frameEvidence==='PNG_SHA256_VERIFIED','native M1 full-frame integrity missing');
  correct(m2.status==='MEASURED'&&m2.qualityDecision==='MANUAL_REVIEW_REQUIRED'&&m21.status==='MEASURED'&&m21.qualityDecision==='MANUAL_REVIEW_REQUIRED'&&m3.status==='MEASURED'&&m3.qualityDecision==='MANUAL_REVIEW_REQUIRED'&&m31.status==='MEASURED'&&m31.qualityDecision==='MANUAL_REVIEW_REQUIRED','missing previous verified motion/physics evidence');
  correct(nativeManifest.schema==='videostudio.native-duel-proof.v1'&&SHA.test(nativeManifest.weaponCoreTraceSha256??''),'missing native traced weapon SHA');
  const [shaSource,shaProgram,shaJoints,shaTrace]=await Promise.all([
   'editable-duel-source.json','editable-duel-program.json','native-rig-tracks.json','native-weapon-tracks.json'].map(name=>digest(join(root,name))));
  correct(shaSource===m1.native.sourceSha256&&shaProgram===m1.native.programSha256,'M1 original approved vector source/pose program changed');
  correct(shaJoints===nativeManifest.rigTracksSha256&&shaTrace===nativeManifest.weaponCoreTraceSha256,'native solver trace/weapon vector evidence tampered');
  correct(shaSource===joints.sourceSha256&&shaProgram===joints.programSha256,'native rigs do not belong to this editable source');
  const measured=auditWeaponGeometry({source,program,native:joints,weaponTrace:trace,stageMetrics:stage});
  correct(m1.movie?.filename===basename(m1.movie.filename)&&SHA.test(m1.movie.sha256),'invalid original video receipt');
  const video=join(root,m1.movie.filename),videoStat=await lstat(video);
  correct(videoStat.isFile()&&!videoStat.isSymbolicLink()&&videoStat.size>0&&videoStat.size<=20*1024*1024,'MP4 missing or unbounded');
  correct(await digest(video)===m1.movie.sha256,'original rendered MP4 changed after native hash verification');
  const report={...measured,sourceSha256:shaSource,programSha256:shaProgram,
   nativeRigTraceSha256:shaJoints,nativeWeaponTraceSha256:shaTrace,
   videoSha256:m1.movie.sha256,
   provenance:{nativeFramesVerified:m1.frameAudit.stats.verifiedFrameFiles,
     originalCanvas:'Android Canvas / Robolectric CI; not a physical phone',
     priorM31Gate:m31.qualityDecision,reviewedScope:'explicit existing VideoStudio CI project frames only'},
   fullFilmVisualQualityVerified:false};
  await writeFile(output,JSON.stringify(report,null,2)+'\n',{mode:0o600});
  return report;
 }catch(e){
  try{await writeFile(output,JSON.stringify({schema:'omega.weapon-geometry.report.v0.3.2',status:'BLOCKED',qualityDecision:'BLOCK',reason:String(e?.message??e).slice(0,1000),physicalPhoneVerified:false},null,2)+'\n',{mode:0o600});}catch{}
  throw e;
 }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const a=process.argv.slice(2);
 if(a.length!==2||a[0]!=='--root') {console.error('Usage: node scripts/omega-weapon-contact.mjs --root <explicit-CI-owned-duel-proof>');process.exitCode=2;}
 else try{const report=await auditOwnedWeaponProof(a[1]);console.log(JSON.stringify({status:report.status,contactGapPx:report.measured.correctedTipSeparationPx,frames:report.measured.verifiedWideFrames,qualityDecision:report.qualityDecision}));}
 catch(e){console.error(String(e?.message??e));process.exitCode=1;}
}