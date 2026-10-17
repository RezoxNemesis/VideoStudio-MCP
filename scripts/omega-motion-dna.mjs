#!/usr/bin/env node
/** M2 read-only FFmpeg luminance sampling on explicit M1-verified CI films. */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyzeSequence } from '../src/omega/motion-dna.js';

const W = 160, H = 90, SAMPLE_FPS = 6, MAX_VIDEO_BYTES = 20 * 1024 * 1024;
const PLAN_FILE = fileURLToPath(new URL('./omega-motion-shot-plan.json', import.meta.url));
function valid(o) { return o !== null && typeof o === 'object' && !Array.isArray(o); }
function must(v, why) { if (!v) throw new Error('OMEGA_MOTION: ' + why); }
async function fileDigest(path) {
  const hash = createHash('sha256');
  for await (const data of createReadStream(path)) hash.update(data);
  return hash.digest('hex');
}

export async function auditOwnedMotion(rootPath, kind, { decode = null } = {}) {
  must(['story','duel'].includes(kind), 'Only story or duel owned proof kinds are allowed');
  const root = resolve(rootPath), output = join(root, 'omega-motion-dna.json');
  try {
    const directory = await lstat(root);
    must(directory.isDirectory() && !directory.isSymbolicLink(), 'Explicit root must be a regular directory');
    const receiptPath = join(root, 'omega-native-report.json');
    const receiptStat = await lstat(receiptPath);
    must(receiptStat.isFile() && !receiptStat.isSymbolicLink() && receiptStat.size < 1024 * 1024,
      'Missing/unbounded original M1 report');
    const prior = JSON.parse(await readFile(receiptPath, 'utf8'));
    must(valid(prior) && prior.schema === 'omega.native-film-proof.v1' && prior.kind === kind &&
      prior.gate === 'READY_FOR_VISUAL_REVIEW' && prior.releaseDecision === 'MANUAL_REVIEW_REQUIRED' &&
      prior.frameAudit?.frameEvidence === 'PNG_SHA256_VERIFIED', 'Cannot run without successful, original full M1 audit');
    const videoName = prior.movie?.filename;
    must(typeof videoName === 'string' && videoName.length < 200 && videoName === basename(videoName) &&
      /^[\w.-]+\.mp4$/.test(videoName) && !videoName.startsWith('.'), 'MP4 name must be a safe basename');
    const path = join(root, videoName), videoStat = await lstat(path);
    must(videoStat.isFile() && !videoStat.isSymbolicLink() && videoStat.size > 0 &&
      videoStat.size <= MAX_VIDEO_BYTES, 'Owned MP4 invalid or exceeds 20 MiB');
    must(await fileDigest(path) === prior.movie?.sha256, 'Encoded MP4 has changed since M1 verification');
    const {frameCount, fps} = {frameCount:prior.native?.frameCount, fps:prior.native?.fps};
    must(Number.isSafeInteger(frameCount) && frameCount >= 3 && frameCount <= 7200 &&
      fps === 24, 'M2 currently supports reviewed 24 FPS native films only');
    must(frameCount <= 288 && Math.ceil(frameCount / 4) <= 72,
      'M2 sampling is limited to 72 samples; increase in a separately reviewed change');
    const maxBytes = W * H * 72;
    const raw = decode ? await decode({ path, width:W, height:H, fps:SAMPLE_FPS }) : execFileSync('ffmpeg', [
      '-nostdin','-v','error','-i',path,'-vf',`fps=${SAMPLE_FPS},scale=${W}:${H}:flags=area,format=gray`,
      '-frames:v','72','-f','rawvideo','-pix_fmt','gray','pipe:1',
    ], { timeout: 30000, maxBuffer: maxBytes + W * H });
    must(Buffer.isBuffer(raw) && raw.length % (W * H) === 0 && raw.length <= maxBytes,
      'FFmpeg returned malformed or oversized grayscale frames');
    const count = raw.length / (W * H), frames = [];
    for (let i = 0; i < count; i++) frames.push(raw.subarray(i * W * H, (i + 1) * W * H));
    const plans = JSON.parse(await readFile(PLAN_FILE, 'utf8'));
    const analysis = analyzeSequence(frames, {
      width: W, height: H, originalFps:fps, originalFrameCount:frameCount, sampleFps:SAMPLE_FPS,
      shotPlan:plans[kind], maxShift:6,
    });
    const report = {
      ...analysis, kind, sourceVideo:videoName, sourceVideoSha256:prior.movie.sha256,
      sourceProofSha256:await fileDigest(receiptPath),
      provenance:{ m1Gate:prior.gate, ownerProjectRevision:prior.sourceRevision?.ownerDeviceProjectRevision ?? null,
        gitCommitSha:prior.sourceRevision?.gitCommitSha ?? null, decodedFrom:'FFmpeg local reviewed MP4',
        authorizedMedia:'only explicitly supplied CI-owned proof directory',
        shotRegionNote:plans[kind].note },
      ownerPhonePlaybackVerified:false, semanticActingQualityVerified:false,
    };
    await writeFile(output, JSON.stringify(report,null,2) + '\n', {mode:0o600});
    return report;
  } catch(error) {
    const blocked={schema:'omega.motion-dna.report.v0.2',kind,status:'BLOCKED',qualityDecision:'BLOCK',
      reason:String(error?.message??error).slice(0,1200),ownerPhonePlaybackVerified:false};
    try { await writeFile(output,JSON.stringify(blocked,null,2)+'\n',{mode:0o600}); } catch {}
    throw error;
  }
}

function argsParse(a) {
  const args={};
  for(let i=0;i<a.length;i+=2) {
    const k=a[i], v=a[i+1];
    if(!['--kind','--root'].includes(k)||!v||v.startsWith('--')||Object.hasOwn(args,k))
      throw new Error('Usage: node scripts/omega-motion-dna.mjs --kind story|duel --root <owned-proof-dir>');
    args[k]=v;
  }
  must(args['--kind'] && args['--root'],'--kind and --root are mandatory');
  return args;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const a=argsParse(process.argv.slice(2));
    const report=await auditOwnedMotion(a['--root'],a['--kind']);
    console.log(JSON.stringify({kind:report.kind,status:report.status,frames:report.measured.decodedSampleCount,
      pairs:report.measured.evaluatedPairs,localizedCandidates:report.measured.localizedMotionCandidatePairs,
      backgroundOnlyCandidates:report.measured.backgroundOnlyCandidatePairs,
      warnings:report.warnings,qualityDecision:report.qualityDecision}));
  }catch(e){console.error(String(e?.message??e));process.exitCode=1;}
}
