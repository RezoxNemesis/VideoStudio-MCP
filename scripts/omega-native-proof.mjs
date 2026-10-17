#!/usr/bin/env node
/**
 * OMEGA M1: bind Android Canvas CI-generated PNGs to the encoded film evidence.
 * Reads only an explicitly supplied proof folder; never connects to phone/Gallery.
 * An opt-in CineBench review is NOT cinematic-quality certification.
 */
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { auditNativeFrameManifest, verifyFramePngs } from '../src/omega/cinemabench.js';

const TYPES = Object.freeze({
  story: { proof: 'native-frame-proof.json', source: 'editable-original-source.json', schema: null },
  duel: { proof: 'native-duel-proof.json', source: 'editable-duel-source.json', program: 'editable-duel-program.json', schema: 'videostudio.native-duel-proof.v1' },
});
const SHA256 = /^[0-9a-f]{64}$/i;
const MAX_JSON = 32 * 1024 * 1024;
const MAX_PNG = 64 * 1024 * 1024;
function requireEvidence(ok, description) { if (!ok) throw new Error('OMEGA_NATIVE_PROOF: ' + description); }
function isRecord(o) { return o !== null && typeof o === 'object' && !Array.isArray(o); }
function asCount(value) { return Number.isSafeInteger(value) && value >= 2 && value <= 7200; }
async function regularFile(file, max = MAX_JSON) {
  const st = await lstat(file);
  requireEvidence(st.isFile() && !st.isSymbolicLink() && st.size > 0 && st.size <= max,
    'Missing/unbounded/nonregular file: ' + basename(file));
  return st;
}
async function json(file) { await regularFile(file); return JSON.parse(await readFile(file, 'utf8')); }
async function digest(file, max) {
  await regularFile(file, max);
  const hasher = createHash('sha256');
  for await (const part of createReadStream(file)) hasher.update(part);
  return hasher.digest('hex');
}
function safeMediaName(name) {
  return typeof name === 'string' && name.length < 200 && name === basename(name) &&
    !name.startsWith('.') && /^[\w.-]+\.mp4$/i.test(name);
}
function validHash(hash) { return typeof hash === 'string' && SHA256.test(hash); }
function exactSha(actual, expected, description) {
  requireEvidence(validHash(expected) && actual.toLowerCase() === expected.toLowerCase(), description);
}
function intEqual(actual, expected, label) {
  requireEvidence(Number(actual) === Number(expected) && Number.isSafeInteger(Number(actual)), label);
}

/**
 * Run after each existing encoder writes mp4-verified.json and BEFORE it deletes
 * Android Canvas PNGs. Re-hash every original frame, retain a canonical full
 * ordered manifest (including story frames whose original proof has only samples).
 */
export async function auditNativeFilmProof(folder, kind, { sourceCommit = process.env.GITHUB_SHA ?? null } = {}) {
  requireEvidence(Object.hasOwn(TYPES, kind), 'Film kind must be story or duel');
  const root = resolve(folder);
  const config = TYPES[kind];
  const output = join(root, 'omega-native-report.json');
  let report = null;
  try {
    const native = await json(join(root, config.proof));
    const encoded = await json(join(root, 'mp4-verified.json'));
    requireEvidence(isRecord(native) && isRecord(encoded), 'Native/encoded proof must be JSON objects');
    requireEvidence(encoded.schema === (kind === 'story' ? 'videostudio.story-proof.v1' : 'videostudio.two-actor-visual-proof.v1'), 'Unexpected encoder receipt schema');
    requireEvidence(!config.schema || native.schema === config.schema, 'Unexpected native proof schema');
    requireEvidence(asCount(native.frameCount) && Number.isInteger(native.width) && native.width >= 1 && native.width <= 8192 &&
      Number.isInteger(native.height) && native.height >= 1 && native.height <= 8192 &&
      Number.isInteger(native.fps) && native.fps >= 1 && native.fps <= 120,
      'Bad frame count, FPS or frame dimensions');
    const count = native.frameCount;
    const frames = [];
    let previousHash = null, byteChanges = 0;
    for (let i = 0; i < count; i++) {
      const name = `frame-${String(i).padStart(4, '0')}.png`;
      const file = join(root, 'frames', name);
      const actual = await digest(file, MAX_PNG);
      if (previousHash && actual !== previousHash) byteChanges++;
      previousHash = actual;
      if (kind === 'duel') {
        requireEvidence(Array.isArray(native.frames) && native.frames.length === count,
          'Duel must supply exact full original frame hash manifest');
        requireEvidence(native.frames[i]?.frame === i, 'Duel frame index mismatch at ' + i);
        exactSha(actual, native.frames[i].sha256, 'Duel original native hash mismatch at ' + i);
      }
      frames.push({ frame: i, sha256: actual });
    }
    if (kind === 'story') {
      requireEvidence(Array.isArray(native.samples) && native.samples.length > 0,
        'Story must contain independently recorded native sample hashes');
      let last = -1;
      for (const sample of native.samples) {
        requireEvidence(Number.isInteger(sample?.frame) && sample.frame > last && sample.frame < count,
          'Story sample index missing, repeated or unsorted');
        exactSha(frames[sample.frame].sha256, sample.pngSha256,
          'Story original sampled frame hash mismatch at ' + sample.frame);
        last = sample.frame;
      }
    }
    const canonical = {
      schema: 'omega.native-canvas-frame-manifest.v1',
      frameCount: count, fps: native.fps, width: native.width, height: native.height,
      changedAdjacentFrames: byteChanges, frames,
    };
    const verification = await verifyFramePngs(canonical, join(root, 'frames'));
    const stage = kind === 'duel' ? await json(join(root, 'duel-stage-metrics.json')) : null;
    const audit = auditNativeFrameManifest(canonical, {
      frameVerification: verification, stageMetrics: stage,
      maxContactGapPx: kind === 'duel' ? 80 : null,
    });
    const sourceHash = await digest(join(root, config.source), MAX_JSON);
    if (kind === 'story') exactSha(sourceHash, native.sourceSha256, 'Native story source SHA mismatch');
    const programHash = config.program ? await digest(join(root, config.program), MAX_JSON) : null;
    requireEvidence(safeMediaName(encoded.video), 'Encoded MP4 name must be a local basename');
    const videoFile = join(root, encoded.video);
    const videoStat = await regularFile(videoFile, 20 * 1024 * 1024);
    const movieHash = await digest(videoFile, 20 * 1024 * 1024);
    exactSha(movieHash, encoded.sha256, 'Encoded movie SHA differs from verification receipt');
    const recordedBytes = encoded.bytes ?? encoded.fileBytes;
    intEqual(recordedBytes, videoStat.size, 'Encoded video byte size mismatches actual file');
    const expectedCount = encoded.frameCount ?? encoded.nativeFrameCount;
    intEqual(expectedCount, count, 'Encoded frame count mismatches native frame count');
    intEqual(encoded.fps, native.fps, 'Encoded FPS mismatches native frames');
    requireEvidence(Number.isFinite(encoded.durationSeconds) &&
      Math.abs(encoded.durationSeconds - count / native.fps) < .02, 'Encoded duration differs from native frame count');
    requireEvidence(isRecord(encoded.videoProbe) && Array.isArray(encoded.videoProbe.streams) &&
      isRecord(encoded.videoProbe.streams[0]) && isRecord(encoded.videoProbe.format), 'Missing FFprobe result');
    const stream = encoded.videoProbe.streams[0];
    intEqual(stream.nb_read_frames, count, 'FFprobe decoded frame count mismatch');
    intEqual(stream.width, native.width, 'FFprobe width mismatch');
    intEqual(stream.height, native.height, 'FFprobe height mismatch');
    requireEvidence(stream.r_frame_rate === `${native.fps}/1` ||
      (native.fps === 24 && stream.r_frame_rate === '24/1'), 'FFprobe FPS mismatch');
    requireEvidence(Number.isFinite(Number(encoded.videoProbe.format.duration)) &&
      Math.abs(Number(encoded.videoProbe.format.duration) - count / native.fps) < .02, 'FFprobe duration mismatch');
    if (kind === 'story') {
      intEqual(encoded.sourcePngsVerified, count, 'Story encoder did not verify complete frame count');
      exactSha(sourceHash, encoded.sourceDocumentSha256, 'Story encoder source SHA mismatch');
    } else {
      intEqual(encoded.nativeFramesVerified, count, 'Duel encoder did not verify complete frame count');
      exactSha(sourceHash, encoded.inputSourceSha256, 'Duel encoder source SHA mismatch');
      exactSha(programHash, encoded.inputProgramSha256, 'Duel encoder pose program SHA mismatch');
    }
    const commit = typeof sourceCommit === 'string' && /^[a-f0-9]{40}$/i.test(sourceCommit) ? sourceCommit : null;
    report = {
      schema: 'omega.native-film-proof.v1',
      kind,
      gate: audit.gate,
      releaseDecision: audit.releaseDecision,
      sourceRevision: { gitCommitSha: commit, ownerDeviceProjectRevision: null },
      native: {
        renderer: 'Android Canvas / Robolectric CI', physicalPhoneVerified: false,
        frameCount: count, width: native.width, height: native.height, fps: native.fps,
        originalEvidenceFile: config.proof,
        originalNativeSampledPixelChanges: native.changedAdjacentFrames ?? null,
        originalSampledHashesChecked: kind === 'story' ? native.samples.length : count,
        canonicalByteChangedAdjacentFrames: byteChanges,
        canonicalManifest: 'omega-frame-manifest.json',
        sourceDocument: config.source, sourceSha256: sourceHash,
        ...(programHash ? { programDocument: config.program, programSha256: programHash } : {}),
      },
      movie: {
        filename: encoded.video, sha256: movieHash, bytes: videoStat.size,
        decodedFrames: count, durationSeconds: count / native.fps,
        ffprobeVerifiedInEncoder: true,
        originalEncoderReceipt: 'mp4-verified.json',
      },
      frameAudit: audit,
      notEvaluated: [
        'Real-phone APK playback, temperature and performance',
        'Visual artistry, anatomy, acting, camera-only motion and character continuity',
        'Speech and audio synchronization',
      ],
      provenanceNote: 'Hashes and FFprobe receipt verify the CI-owned build pipeline, not human cinematic quality or on-phone export.',
    };
    await writeFile(join(root, 'omega-frame-manifest.json'), JSON.stringify(canonical, null, 2) + '\n');
    await writeFile(output, JSON.stringify(report, null, 2) + '\n');
    requireEvidence(verification.verified && audit.gate === 'READY_FOR_VISUAL_REVIEW' &&
      audit.frameEvidence === 'PNG_SHA256_VERIFIED', 'CinemaBench frame gate blocked film: ' + JSON.stringify(audit.findings));
    return report;
  } catch (error) {
    // Preserve a fail-closed receipt for a broken proof; Python will retain PNGs.
    const failed = {
      schema: 'omega.native-film-proof.v1', kind, gate: 'BLOCK', releaseDecision: 'BLOCK',
      reason: String(error?.message ?? error).slice(0, 1500),
      ...(report ? { frameAudit: report.frameAudit } : {}),
      physicalPhoneVerified: false,
    };
    try { await writeFile(output, JSON.stringify(failed, null, 2) + '\n'); } catch {}
    throw new Error(String(error?.message ?? error).startsWith('OMEGA_NATIVE_PROOF:') ? String(error.message) :
      'OMEGA_NATIVE_PROOF: ' + String(error?.message ?? error), { cause: error });
  }
}

function parse(args) {
  const found = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i], value = args[i + 1];
    if (!['--kind','--root'].includes(key) || !value || value.startsWith('--') ||
      Object.hasOwn(found, key)) throw new Error('Usage: node scripts/omega-native-proof.mjs --kind story|duel --root <CI-owned-folder>');
    found[key] = value;
  }
  if (!found['--kind'] || !found['--root']) throw new Error('Missing --kind or --root');
  return found;
}
const entryFile = process.argv[1] ? resolve(process.argv[1]) : null;
if (entryFile === fileURLToPath(import.meta.url)) {
  try {
    const args = parse(process.argv.slice(2));
    const result = await auditNativeFilmProof(args['--root'], args['--kind']);
    console.log(JSON.stringify({ gate: result.gate, kind: result.kind,
      verifiedFrames: result.frameAudit.stats.verifiedFrameFiles,
      mp4Sha256: result.movie.sha256, humanReviewRequired: true }));
  } catch (error) {
    console.error(String(error?.message ?? error));
    process.exitCode = 1;
  }
}
