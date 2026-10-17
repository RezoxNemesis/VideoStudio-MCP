/**
 * Project OMEGA / CinemaBench: independent, read-only native frame-evidence audit.
 * This module measures file-level integrity and obvious repeated frames only.
 * It cannot certify pixels differ perceptually, realistic articulation or art quality.
 * No network, Android, Worker or third-party dependencies.
 */
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, open, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const SHA256 = /^[a-f0-9]{64}$/i;
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const MAX_FRAMES = 7200;
const MAX_PNG_BYTES = 64 * 1024 * 1024;

function validInt(value, min, max) {
  return Number.isSafeInteger(value) && value >= min && value <= max;
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function finding(level, code, message, details = undefined) {
  return details ? { level, code, message, details } : { level, code, message };
}

/** Verify only an explicitly specified, ordered project/CI-owned PNG directory. */
export async function verifyFramePngs(manifest, frameDirectory) {
  if (!isRecord(manifest) || !validInt(manifest.frameCount, 1, MAX_FRAMES) ||
      !Array.isArray(manifest.frames) || manifest.frames.length !== manifest.frameCount ||
      !validInt(manifest.width, 1, 8192) || !validInt(manifest.height, 1, 8192)) {
    return { verified: false, checkedFrames: 0, problem: 'Invalid manifest for PNG verification' };
  }

  let checkedFrames = 0;
  try {
    const dir = await lstat(frameDirectory);
    if (!dir.isDirectory() || dir.isSymbolicLink()) {
      throw new Error('Frame directory must be a real directory, not a symlink');
    }
    const names = (await readdir(frameDirectory)).filter(name => /^frame-\d+\.png$/.test(name));
    const expectedNames = Array.from({ length: manifest.frameCount }, (_, i) => 'frame-' + String(i).padStart(4, '0') + '.png');
    if (names.length !== expectedNames.length ||
        expectedNames.some(name => !names.includes(name))) {
      throw new Error('Frame PNG collection has missing or unexpected numbered frames');
    }

    for (let i = 0; i < manifest.frameCount; i++) {
      const entry = manifest.frames[i];
      if (!isRecord(entry) || entry.frame !== i || !SHA256.test(entry.sha256 ?? '')) {
        throw new Error('Missing or malformed frame manifest entry at ' + i);
      }
      const file = join(frameDirectory, expectedNames[i]);
      const stat = await lstat(file);
      if (!stat.isFile() || stat.isSymbolicLink() || stat.size < 45 || stat.size > MAX_PNG_BYTES) {
        throw new Error('Frame ' + i + ' is not a bounded regular PNG file');
      }

      const handle = await open(file, 'r');
      const header = Buffer.alloc(24);
      try {
        const { bytesRead } = await handle.read(header, 0, header.length, 0);
        if (bytesRead !== 24 || !header.subarray(0, 8).equals(PNG_SIGNATURE) ||
            header.toString('ascii', 12, 16) !== 'IHDR' || header.readUInt32BE(8) !== 13 ||
            header.readUInt32BE(16) !== manifest.width || header.readUInt32BE(20) !== manifest.height) {
          throw new Error('Frame ' + i + ' PNG signature/dimensions disagree with manifest');
        }
      } finally {
        await handle.close();
      }
      const hash = createHash('sha256');
      for await (const chunk of createReadStream(file)) hash.update(chunk);
      if (hash.digest('hex').toLowerCase() !== entry.sha256.toLowerCase()) {
        throw new Error('Frame ' + i + ' SHA-256 differs from recorded native output');
      }
      checkedFrames++;
    }
    return { verified: true, checkedFrames, problem: null };
  } catch (error) {
    return { verified: false, checkedFrames, problem: String(error?.message ?? error) };
  }
}

/**
 * Audit a native frame manifest without pretending frame hashes establish visual animation.
 * Optional verification comes from verifyFramePngs and is not invented in this function.
 */
export function auditNativeFrameManifest(manifest, { frameVerification = null, stageMetrics = null, maxContactGapPx = null } = {}) {
  const findings = [];
  const block = (code, message, details) => findings.push(finding('blocker', code, message, details));
  const warn = (code, message, details) => findings.push(finding('warning', code, message, details));
  const info = (code, message, details) => findings.push(finding('info', code, message, details));
  const report = {
    schema: 'omega.cinemabench.report.v0.1',
    sourceSchema: isRecord(manifest) && typeof manifest.schema === 'string' ? manifest.schema : 'unspecified',
    gate: 'BLOCK',
    releaseDecision: 'BLOCK',
    frameEvidence: frameVerification === null ? 'MANIFEST_ONLY' : (frameVerification.verified ? 'PNG_SHA256_VERIFIED' : 'PNG_VERIFICATION_FAILED'),
    stats: null,
    reportedStageMetrics: null,
    notEvaluated: [
      'actual perceptual pixel motion and camera-only movement',
      'anatomy, hand quality, acting and temporal identity',
      'audio synchronization and intelligibility',
      'APK installation, playback and performance on the owner phone',
    ],
    findings,
  };

  if (!isRecord(manifest)) {
    block('INVALID_MANIFEST', 'Expected a JSON object');
    return report;
  }
  const { frameCount, fps, width, height, frames } = manifest;
  if (!validInt(frameCount, 2, MAX_FRAMES)) block('FRAME_COUNT', 'Frame count must be an integer between 2 and ' + MAX_FRAMES);
  if (!Number.isFinite(fps) || fps < 1 || fps > 120) block('FPS', 'Frame rate must be between 1 and 120');
  if (!validInt(width, 1, 8192) || !validInt(height, 1, 8192)) block('DIMENSIONS', 'Frame dimensions must be 1–8192 pixels');
  if (!Array.isArray(frames) || !validInt(frameCount, 2, MAX_FRAMES) || frames.length !== frameCount) {
    block('FRAME_SEQUENCE', 'frames must exactly match frameCount');
  }

  let changed = 0;
  let longestRun = 0;
  let consecutiveRun = 0;
  let runStart = 0;
  const holds = [];
  let allValid = Array.isArray(frames) && validInt(frameCount, 2, MAX_FRAMES) && frames.length === frameCount;
  if (allValid) {
    for (let i = 0; i < frameCount; i++) {
      const entry = frames[i];
      if (!isRecord(entry) || entry.frame !== i || typeof entry.sha256 !== 'string' || !SHA256.test(entry.sha256)) {
        allValid = false;
        block('FRAME_ENTRY', 'Frame ' + i + ' must have matching zero-based frame index and SHA-256');
        break;
      }
      const now = entry.sha256.toLowerCase();
      const previous = i ? frames[i - 1].sha256.toLowerCase() : null;
      if (now === previous) {
        consecutiveRun++;
      } else {
        if (i) changed++;
        if (consecutiveRun > 0) holds.push({ fromFrame: runStart, toFrame: i - 1, frameCount: consecutiveRun });
        consecutiveRun = 1;
        runStart = i;
      }
      longestRun = Math.max(longestRun, consecutiveRun);
    }
    if (allValid && consecutiveRun > 0) {
      holds.push({ fromFrame: runStart, toFrame: frameCount - 1, frameCount: consecutiveRun });
    }
  }

  if (allValid) {
    const ratio = changed / (frameCount - 1);
    const meaningfulHolds = Number.isFinite(fps)
      ? holds.filter(h => h.frameCount >= Math.max(2, Math.ceil(fps * 0.75)))
      : [];
    report.stats = {
      frameCount, fps, width, height,
      durationSeconds: frameCount / fps,
      byteDistinctAdjacentFrames: changed,
      byteDistinctAdjacentRatio: Math.round(ratio * 10000) / 10000,
      longestIdenticalPngRunFrames: longestRun,
      longIdenticalRuns: meaningfulHolds.map(h => ({ ...h, durationSeconds: h.frameCount / fps })),
      verifiedFrameFiles: frameVerification?.verified ? frameVerification.checkedFrames : 0,
    };
    if (manifest.changedAdjacentFrames != null && manifest.changedAdjacentFrames !== changed) {
      block('ADJACENCY_MISMATCH', 'Recorded changedAdjacentFrames does not match the supplied frame hashes', {
        recorded: manifest.changedAdjacentFrames, recomputed: changed,
      });
    }
    if (changed === 0 || ratio < 0.1) {
      block('NEAR_STATIC', 'Nearly all adjacent PNGs have identical bytes; meaningful motion evidence is absent', {
        distinctRatio: report.stats.byteDistinctAdjacentRatio,
      });
    } else if (meaningfulHolds.length > 0) {
      warn('LONG_IDENTICAL_HOLD', 'Found extended identical-PNG runs; review whether these holds are intentional', {
        ranges: report.stats.longIdenticalRuns,
      });
    }
    info('HASHES_ARE_NOT_MOTION', 'Distinct PNG hashes do not prove moving characters, visual differences or cinematic quality');
  }

  if (frameVerification !== null && !frameVerification.verified) {
    block('PNG_INTEGRITY_FAILED', 'Native PNG verification failed', {
      checked: frameVerification.checkedFrames, reason: frameVerification.problem,
    });
  } else if (frameVerification === null) {
    warn('UNVERIFIED_FILES', 'Manifest hashes were not checked against actual numbered PNG files');
  }

  if (maxContactGapPx !== null) {
    if (!Number.isFinite(maxContactGapPx) || maxContactGapPx <= 0 || maxContactGapPx > 100000) {
      block('CONTACT_THRESHOLD', 'Contact-gap threshold must be a positive finite pixel distance');
    } else if (!isRecord(stageMetrics) || !Number.isFinite(stageMetrics.batonWristDistancePx) ||
               stageMetrics.batonWristDistancePx < 0) {
      block('MISSING_CONTACT_EVIDENCE', 'A finite nonnegative batonWristDistancePx is required for this optional contact gate');
    } else if (stageMetrics.batonWristDistancePx > maxContactGapPx) {
      block('CONTACT_GAP', 'Measured wrist separation exceeds the selected authored tolerance', {
        measuredPx: stageMetrics.batonWristDistancePx, tolerancePx: maxContactGapPx,
      });
    }
  }
  if (isRecord(stageMetrics)) {
    report.reportedStageMetrics = {
      actorLabels: Array.isArray(stageMetrics.actors) ? stageMetrics.actors.filter(v => typeof v === 'string').slice(0, 10) : [],
      preparedViewCount: Array.isArray(stageMetrics.preparedViews) ? stageMetrics.preparedViews.length : null,
      wristGapPx: Number.isFinite(stageMetrics.batonWristDistancePx) ? stageMetrics.batonWristDistancePx : null,
    };
    info('CONTACT_IS_NOT_COLLISION', 'Wrist distance and reported view/actor labels cannot prove sword contact or independent character animation');
  }

  report.gate = findings.some(f => f.level === 'blocker') ? 'BLOCK' : 'READY_FOR_VISUAL_REVIEW';
  report.releaseDecision = report.gate === 'BLOCK' ? 'BLOCK' : 'MANUAL_REVIEW_REQUIRED';
  return report;
}
