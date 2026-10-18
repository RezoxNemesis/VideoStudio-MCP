#!/usr/bin/env node
/** M3.1 reads only CI-owned previously verified native evidence; no Android/Gallery access. */
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { auditImpactDynamics } from '../src/omega/impact-dynamics.js';
const valid = x => x !== null && typeof x === 'object' && !Array.isArray(x);
const must = (ok,why) => { if (!ok) throw new Error('OMEGA_IMPACT_QA: '+why); };
async function bounded(path) {
  const s=await lstat(path); must(s.isFile() && !s.isSymbolicLink() && s.size>0 && s.size<=16*1024*1024,
    'Missing or unbounded local evidence file '+basename(path));
}
async function json(path) { await bounded(path);return JSON.parse(await readFile(path,'utf8')); }
async function sha(path) { await bounded(path);const h=createHash('sha256');for await(const c of createReadStream(path))h.update(c);return h.digest('hex'); }
function digestEq(x,y,label){must(typeof x==='string'&& /^[a-f0-9]{64}$/i.test(x) && x.toLowerCase()===y.toLowerCase(),label);}
export async function auditOwnedImpact(rootPath) {
  const root=resolve(rootPath),output=join(root,'omega-impact-dynamics.json');
  try {
    const stat=await lstat(root);must(stat.isDirectory()&&!stat.isSymbolicLink(),'Explicit owned proof folder required');
    const m3Path=join(root,'omega-physics-brain.json'),physics=await json(m3Path);
    must(valid(physics) && physics.status==='MEASURED' && physics.kind==='duel' &&
      physics.qualityDecision==='MANUAL_REVIEW_REQUIRED' &&
      physics.ownerPhonePlaybackVerified===false,'M3 proven native geometry is required');
    const nativePath=join(root,'omega-native-report.json'), m1=await json(nativePath);
    must(m1.kind==='duel'&&m1.frameAudit?.frameEvidence==='PNG_SHA256_VERIFIED'&&
      m1.gate==='READY_FOR_VISUAL_REVIEW','M1 source MP4 evidence not proven');
    const tracePath=join(root,'native-rig-tracks.json'),programPath=join(root,'editable-duel-program.json');
    digestEq(physics.provenance?.sourceNativeTraceSha256,await sha(tracePath),'M3 native skeleton hash mismatch');
    digestEq(physics.provenance?.programSha256,await sha(programPath),'M3 editable pose hash mismatch');
    digestEq(physics.provenance?.m1ReceiptSha256,await sha(nativePath),'M3 native-MP4 receipt mismatch');
    const native=await json(join(root,'native-duel-proof.json'));
    digestEq(native.rigTracksSha256,await sha(tracePath),'Original Android trace hash mismatch');
    const trace=await json(tracePath),program=await json(programPath),stage=await json(join(root,'duel-stage-metrics.json'));
    const check=auditImpactDynamics(trace,program,stage,physics);
    const report={...check,kind:'duel',provenance:{nativeTraceSha256:await sha(tracePath),
      programSha256:await sha(programPath),m3ReceiptSha256:await sha(m3Path),gitCommitSha:m1.sourceRevision?.gitCommitSha??null}};
    await writeFile(output,JSON.stringify(report,null,2)+'\n',{mode:0o600});
    return report;
  }catch(e){
    const blocked={schema:'omega.impact-dynamics.report.v0.31',kind:'duel',status:'BLOCKED',
      qualityDecision:'BLOCK',reason:String(e?.message??e).slice(0,1500),physicalPhoneVerified:false};
    try{await writeFile(output,JSON.stringify(blocked,null,2)+'\n',{mode:0o600});}catch{}
    throw e;
  }
}
if (process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    must(process.argv.length===4 && process.argv[2]==='--root',
      'Usage: node scripts/omega-impact-dynamics.mjs --root <CI-owned-duel-proof-folder>');
    const r=await auditOwnedImpact(process.argv[3]);
    console.log(JSON.stringify({status:r.status,peakBefore:r.measured.originalMaxWristStepPx,
      peakAfter:r.measured.verifiedMaxWristStepPx,footSamples:r.measured.actualHardFootSamples,
      qualityDecision:r.qualityDecision}));
  }catch(e){console.error(String(e?.message??e));process.exitCode=1;}
}