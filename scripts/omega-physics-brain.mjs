#!/usr/bin/env node
/** Validate only explicitly selected, owned CI-native rig evidence. Read-only. */
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { auditContactRecovery } from '../src/omega/physics-brain.js';

const SHA=/^[0-9a-f]{64}$/i;
const valid=o=>o!==null&&typeof o==='object'&&!Array.isArray(o);
const must=(condition,why)=>{if(!condition)throw new Error('OMEGA_PHYSICS: '+why);};
async function file(file,limit=16*1024*1024){
  const st=await lstat(file);
  must(st.isFile()&&!st.isSymbolicLink()&&st.size>0&&st.size<=limit,
    'Missing or unbounded owned evidence file: '+basename(file));
}
async function json(filePath){await file(filePath);return JSON.parse(await readFile(filePath,'utf8'));}
async function digest(filePath){await file(filePath);const hash=createHash('sha256');
  for await(const bytes of createReadStream(filePath))hash.update(bytes);
  return hash.digest('hex');}
function sameSha(a,b,why){must(typeof a==='string'&&typeof b==='string'&&
  SHA.test(a)&&SHA.test(b)&&a.toLowerCase()===b.toLowerCase(),why);}

export async function auditOwnedPhysics(rootPath){
  const root=resolve(rootPath),output=join(root,'omega-physics-brain.json');
  try{
    const stat=await lstat(root);
    must(stat.isDirectory()&&!stat.isSymbolicLink(),'Explicit proof root must be an ordinary directory');
    const nativePath=join(root,'omega-native-report.json');
    const m1=await json(nativePath);
    must(valid(m1)&&m1.schema==='omega.native-film-proof.v1'&&m1.kind==='duel'&&
      m1.gate==='READY_FOR_VISUAL_REVIEW'&&m1.releaseDecision==='MANUAL_REVIEW_REQUIRED'&&
      m1.frameAudit?.frameEvidence==='PNG_SHA256_VERIFIED', 'Original M1 native evidence failed');
    const m2Path=join(root,'omega-motion-dna.json'),m2=await json(m2Path);
    must(m2.status==='MEASURED'&&m2.kind==='duel'&&m2.qualityDecision==='MANUAL_REVIEW_REQUIRED',
      'Original M2 visual-motion audit absent');
    sameSha(m2.sourceProofSha256,await digest(nativePath),'M2-to-M1 provenance mismatch');
    const m21Path=join(root,'omega-skeleton-dna.json'),m21=await json(m21Path);
    must(m21.status==='MEASURED'&&m21.kind==='duel'&&m21.qualityDecision==='MANUAL_REVIEW_REQUIRED'&&
      m21.physicalPhoneVerified===false,'Original M2.1 native-skeleton audit absent');
    sameSha(m21.provenance?.m1ReceiptSha256,await digest(nativePath),'M2.1-to-M1 provenance mismatch');
    sameSha(m21.provenance?.m2ReceiptSha256,await digest(m2Path),'M2.1-to-M2 provenance mismatch');
    const trackPath=join(root,'native-rig-tracks.json'),programPath=join(root,'editable-duel-program.json');
    sameSha(m21.sourceNativeTraceSha256,await digest(trackPath),'Original native rig track digest disagrees');
    sameSha(m21.provenance?.programSha256,await digest(programPath),'Source pose program changed after native evidence');
    const trace=await json(trackPath),program=await json(programPath);
    sameSha(trace.programSha256,m21.provenance.programSha256,'Rig trace and authored program hashes differ');
    const stage=await json(join(root,'duel-stage-metrics.json'));
    const report=auditContactRecovery(trace,program,stage,m21);
    const outputReport={...report,kind:'duel',
      provenance:{sourceNativeTraceSha256:await digest(trackPath),programSha256:await digest(programPath),
        m21ReceiptSha256:await digest(m21Path),m1ReceiptSha256:await digest(nativePath),
        gitCommitSha:m1.sourceRevision?.gitCommitSha??null},
      ownerPhonePlaybackVerified:false,usableForAutomaticFilmRelease:false};
    await writeFile(output,JSON.stringify(outputReport,null,2)+'\n',{mode:0o600});
    return outputReport;
  }catch(error){
    const blocked={schema:'omega.physics-brain.report.v0.3',kind:'duel',status:'BLOCKED',
      qualityDecision:'BLOCK',reason:String(error?.message??error).slice(0,1500),
      ownerPhonePlaybackVerified:false};
    try{await writeFile(output,JSON.stringify(blocked,null,2)+'\n',{mode:0o600});}catch{}
    throw error;
  }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{
    must(process.argv.length===4&&process.argv[2]==='--root',
      'Usage: node scripts/omega-physics-brain.mjs --root <CI-owned-duel-proof-dir>');
    const result=await auditOwnedPhysics(process.argv[3]);
    console.log(JSON.stringify({status:result.status,qualityDecision:result.qualityDecision,
      originalWristGapPx:result.contact.originalGapPx,
      verifiedWristGapPx:result.contact.verifiedGapPx,
      maxFootContactErrorPx:result.contact.maxFootContactErrorPx,
      artisticReviewStillRequired:true}));
  }catch(error){console.error(String(error?.message??error));process.exitCode=1;}
}
