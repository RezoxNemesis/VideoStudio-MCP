#!/usr/bin/env node
/** M3.3 staged core crossings; explicit read-only, previously verified CI files only. */
import {createHash} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {lstat,readFile,writeFile} from 'node:fs/promises';
import {join,resolve,basename} from 'node:path';
import {fileURLToPath} from 'node:url';
import {auditCombatStaging} from '../src/omega/combat-staging.js';
const fail=why=>{throw new Error('OMEGA_COMBAT_PROOF: '+why)};
async function bounded(path,limit=6*1024*1024){
 const s=await lstat(path);if(!s.isFile()||s.isSymbolicLink()||s.size<=0||s.size>limit)fail('Missing or unbounded CI-owned receipt: '+basename(path));
 return JSON.parse(await readFile(path,'utf8'));
}
async function digest(path){const h=createHash('sha256');for await(const c of createReadStream(path))h.update(c);return h.digest('hex');}
export async function auditOwnedCombatProof(folder){
 const root=resolve(folder),out=join(root,'omega-combat-staging.json');
 try{
  const dir=await lstat(root);if(!dir.isDirectory()||dir.isSymbolicLink())fail('Explicit bounded native film directory is required');
  const files=['omega-native-report.json','omega-weapon-contact.json','native-duel-proof.json',
    'editable-duel-source.json','editable-duel-program.json','native-rig-tracks.json',
    'native-weapon-tracks.json','duel-stage-metrics.json'];
  const data=await Promise.all(files.map(name=>bounded(join(root,name))));
  const [m1,previous,manifest,source,program,native,trace,stageMetrics]=data;
  if(m1.gate!=='READY_FOR_VISUAL_REVIEW'||m1.kind!=='duel'||m1.frameAudit?.frameEvidence!=='PNG_SHA256_VERIFIED'||
     previous.status!=='MEASURED'||previous.measured?.contactMode!=='CROSSED_SHAFT_INTERIOR'||
     previous.qualityDecision!=='MANUAL_REVIEW_REQUIRED')fail('M3.2 revised crossing audit did not pass');
  const [sourceHash,programHash,nativeHash,traceHash]=await Promise.all([
   'editable-duel-source.json','editable-duel-program.json','native-rig-tracks.json','native-weapon-tracks.json'].map(x=>digest(join(root,x))));
  if(sourceHash!==m1.native?.sourceSha256||programHash!==m1.native?.programSha256||
     nativeHash!==manifest.rigTracksSha256||traceHash!==manifest.weaponCoreTraceSha256)fail('Native/source content hashes disagree with prior video proof');
  const report=auditCombatStaging({source,program,native,weaponTrace:trace,stageMetrics});
  for(const [key,value] of [['weaponCrossingAngleDeg','crossingAngleDeg'],
    ['gripSeparationPx','gripSeparationPx'],['distalTipSeparationPx','correctedTipSeparationPx']]){
    if(Math.abs(report.measurable[key]-previous.measured[value])>.12)fail('Inconsistent independent M3.3 geometry summary: '+key);
  }
  if(!m1.movie?.filename||m1.movie.filename!==basename(m1.movie.filename)||!m1.movie.filename.endsWith('.mp4'))fail('Unsafe MP4 name');
  const mp4=join(root,m1.movie.filename),stat=await lstat(mp4);
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size>20*1024*1024||stat.size<1000||await digest(mp4)!==m1.movie.sha256)
    fail('Original native encoded MP4 source changed');
  report.provenance={nativeFilmSha256:m1.movie.sha256,originalSourceSha256:sourceHash,
   originalProgramSha256:programHash,nativeFrameTraceSha256:nativeHash,
   nativeWeaponTraceSha256:traceHash,predecessorGeometryReportSha256:await digest(join(root,'omega-weapon-contact.json')),
   originalRender:'Android Canvas in Robolectric/GitHub CI, not a physical device'};
  await writeFile(out,JSON.stringify(report,null,2)+'\n',{mode:0o600});return report;
 }catch(e){
  try{await writeFile(out,JSON.stringify({schema:'omega.combat-staging.report.v0.3.3',status:'BLOCKED',qualityDecision:'BLOCK',reason:String(e?.message||e).slice(0,1100),physicalPhoneVerified:false},null,2)+'\n',{mode:0o600})}catch{}
  throw e;
 }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const a=process.argv.slice(2);
 if(a.length!==2||a[0]!=='--root'){console.error('Usage: node scripts/omega-combat-staging.mjs --root <explicit-CI-project>');process.exitCode=2;}
 else try{let r=await auditOwnedCombatProof(a[1]);console.log(JSON.stringify({gate:r.status,quality:r.qualityDecision,...r.measurable}));}
 catch(e){console.error(e.message);process.exitCode=1;}
}