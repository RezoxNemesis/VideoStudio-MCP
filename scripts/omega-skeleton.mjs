#!/usr/bin/env node
/** M2.1: audited, user-owned/CI-owned native authored rig traces, no Gallery I/O. */
import {createHash} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {lstat,readFile,writeFile} from 'node:fs/promises';
import {basename,join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {analyzeNativeSkeleton} from '../src/omega/skeleton-dna.js';

const planPath=fileURLToPath(new URL('./omega-skeleton-shot-plan.json',import.meta.url));
const shaRx=/^[a-f0-9]{64}$/i;
const expected={
  source:'editable-duel-source.json',program:'editable-duel-program.json',
  native:'native-duel-proof.json',tracks:'native-rig-tracks.json',
};
const fail=msg=>{throw new Error('OMEGA_SKELETON: '+msg);};
const valid=o=>o!==null&&typeof o==='object'&&!Array.isArray(o);
async function owned(path,max=16*1024*1024){
  const stat=await lstat(path);
  if(!stat.isFile()||stat.isSymbolicLink()||stat.size<=0||stat.size>max)fail('Missing/oversized/symlink local evidence: '+basename(path));
  return stat;
}
async function json(path){await owned(path);return JSON.parse(await readFile(path,'utf8'));}
async function digest(path){await owned(path);const hash=createHash('sha256');
  for await(const bytes of createReadStream(path)) hash.update(bytes);
  return hash.digest('hex');}
function eqSha(a,b,msg){if(!shaRx.test(a??'')||!shaRx.test(b??'')||a.toLowerCase()!==b.toLowerCase())fail(msg);}

export async function auditOwnedSkeleton(rootPath,{planOverride=null}={}){
  const root=resolve(rootPath),out=join(root,'omega-skeleton-dna.json');
  try{
    const dir=await lstat(root);if(!dir.isDirectory()||dir.isSymbolicLink())fail('Expected explicit real proof directory');
    const m1Path=join(root,'omega-native-report.json');
    const m1=await json(m1Path);
    if(!valid(m1)||m1.schema!=='omega.native-film-proof.v1'||m1.kind!=='duel'||
       m1.gate!=='READY_FOR_VISUAL_REVIEW'||m1.releaseDecision!=='MANUAL_REVIEW_REQUIRED'||
       m1.frameAudit?.frameEvidence!=='PNG_SHA256_VERIFIED')fail('M1 full native gate missing');
    const m2=await json(join(root,'omega-motion-dna.json'));
    if(!valid(m2)||m2.schema!=='omega.motion-dna.report.v0.2'||m2.kind!=='duel'||
      m2.status!=='MEASURED'||m2.qualityDecision!=='MANUAL_REVIEW_REQUIRED'||
      m2.measured?.originalFrames!==m1.native?.frameCount ||
      m2.ownerPhonePlaybackVerified!==false)fail('M2 measured movie evidence missing');
    eqSha(m2.sourceProofSha256,await digest(m1Path),'M2 references another M1 evidence file');
    eqSha(m2.sourceVideoSha256,m1.movie?.sha256,'M2 movie revision mismatch');
    const filename=m1.movie?.filename;
    if(typeof filename!=='string'||filename!==basename(filename)||filename.startsWith('.')||
      !/^[\w.-]+\.mp4$/.test(filename))fail('Unsafe movie name');
    eqSha(await digest(join(root,filename)),m1.movie.sha256,'Owned movie has changed');
    const src=await json(join(root,expected.source)),program=await json(join(root,expected.program));
    eqSha(await digest(join(root,expected.source)),m1.native?.sourceSha256,'Authored artwork changed');
    eqSha(await digest(join(root,expected.program)),m1.native?.programSha256,'Authored rig program changed');
    const native=await json(join(root,expected.native));
    const trackPath=join(root,expected.tracks),trace=await json(trackPath);
    eqSha(await digest(trackPath),native.rigTracksSha256,'Native trace differs from original Android receipt');
    eqSha(trace.sourceSha256,m1.native.sourceSha256,'Native trace artwork provenance mismatch');
    eqSha(trace.programSha256,m1.native.programSha256,'Native trace program provenance mismatch');
    if(trace.frameCount!==m1.native.frameCount||trace.width!==m1.native.width||
      trace.height!==m1.native.height||trace.fps!==m1.native.fps ||
      src.width!==trace.width||src.height!==trace.height)fail('Native timeline/dimensions diverged');
    const plan=planOverride??await json(planPath);
    const report=analyzeNativeSkeleton(trace,program,plan);
    const output={...report,kind:'duel',sourceNativeTraceSha256:await digest(trackPath),
      sourceVideoSha256:m1.movie.sha256,provenance:{m1ReceiptSha256:await digest(m1Path),
        m2ReceiptSha256:await digest(join(root,'omega-motion-dna.json')),
        nativeReceiptSha256:await digest(join(root,expected.native)),
        sourceSha256:m1.native.sourceSha256,programSha256:m1.native.programSha256,
        sourceRevision:m1.sourceRevision,reportBasis:'Native CI-authored rig solver trace, not computer vision'},
      physicalPhoneVerified:false,usableForAutomaticFilmRelease:false};
    await writeFile(out,JSON.stringify(output,null,2)+'\n',{mode:0o600});
    return output;
  }catch(e){
    try{await writeFile(out,JSON.stringify({schema:'omega.skeleton-dna.report.v0.1',kind:'duel',
      status:'BLOCKED',qualityDecision:'BLOCK',reason:String(e?.message??e).slice(0,1300),
      physicalPhoneVerified:false},null,2)+'\n',{mode:0o600});}catch{}
    throw e;
  }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{
    if(process.argv.length!==4||process.argv[2]!=='--root')fail('Usage: node scripts/omega-skeleton.mjs --root <reviewed-native-duel-proof-dir>');
    const report=await auditOwnedSkeleton(process.argv[3]);
    console.log(JSON.stringify({status:report.status,qualityDecision:report.qualityDecision,
      frameCount:report.measurements.frameCount,activeShots:report.measurements.activeShotCount,
      authoredRigCount:report.measurements.authoredRigCount,
      articulationPairs:report.measurements.rootRelativeArticulationPairs,
      hardFootContactSamples:report.measurements.hardFootContactSamples,
      contactCues:report.measurements.checkedInteractionCues}));
  }catch(e){console.error(String(e?.message??e));process.exitCode=1;}
}
