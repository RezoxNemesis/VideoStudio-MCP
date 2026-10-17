/**
 * OMEGA Motion DNA M2: read-only luminance motion evidence on explicitly selected frames.
 * Uses background global-translation compensation and artist-declared coarse interest
 * regions. It does NOT infer bones, segmentation, real optical flow, or film quality.
 * Pure JavaScript; no phone, Gallery, network, inference or paid dependencies.
 */
const LABELS = Object.freeze({
  static: 'NEAR_STATIC',
  camera: 'GLOBAL_TRANSLATION_CANDIDATE',
  local: 'LOCAL_MOTION_CANDIDATE',
  mixed: 'LOCAL_MOTION_OVER_CAMERA',
  background: 'BACKGROUND_ONLY_MOTION_CANDIDATE',
  cut: 'CUT_OR_MAJOR_TRANSITION',
  unknown: 'INDETERMINATE',
});
function ensure(ok, text) { if (!ok) throw new Error('OMEGA_MOTION: ' + text); }
function finite(v) { return typeof v === 'number' && Number.isFinite(v); }
function clamp01(v) { return Math.max(0, Math.min(1, v)); }
function rounded(v) { return Math.round(v * 10000) / 10000; }
function validateRegion(region) {
  ensure(Array.isArray(region) && region.length === 4 && region.every(finite), 'ROI requires 4 finite normalized coordinates');
  const [x, y, width, height] = region;
  ensure(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.000001 && y + height <= 1.000001,
    'ROI must fit normalized frame');
}
export function validateShotPlan(plan, originalFrames) {
  ensure(plan !== null && typeof plan === 'object' && !Array.isArray(plan), 'Shot plan missing');
  ensure(plan.schema === 'omega.motion-shot-plan.v1', 'Unsupported shot plan schema');
  ensure(Number.isSafeInteger(originalFrames) && originalFrames >= 3 && originalFrames <= 7200, 'Frame count unsupported');
  ensure(Array.isArray(plan.shots) && plan.shots.length >= 1 && plan.shots.length <= 24, 'Need 1-24 named authored shots');
  let next = 0;
  for (const shot of plan.shots) {
    ensure(typeof shot.id === 'string' && /^[a-z0-9_-]{1,40}$/i.test(shot.id), 'Invalid shot label');
    ensure(Number.isSafeInteger(shot.start) && shot.start === next && Number.isSafeInteger(shot.end) &&
      shot.end >= shot.start && shot.end < originalFrames, 'Shots must cover original frames contiguously in order');
    ensure(Array.isArray(shot.regions) && shot.regions.length >= 1 && shot.regions.length <= 6,
      'Each shot needs 1-6 artist-declared foreground interest regions');
    shot.regions.forEach(validateRegion);
    next = shot.end + 1;
  }
  ensure(next === originalFrames, 'Shot ranges do not cover the complete film');
  return plan;
}
function insideAnyRegion(x, y, width, height, regions) {
  const nx = x / width, ny = y / height;
  for (const [rx, ry, rw, rh] of regions) {
    if (nx >= rx && nx < rx + rw && ny >= ry && ny < ry + rh) return true;
  }
  return false;
}
/** Analyze one pair of 8-bit grayscale downscaled frames. */
export function analyzePair(before, after, width, height, regions, { maxShift = 6 } = {}) {
  ensure(Number.isSafeInteger(width) && width >= 48 && width <= 320 &&
    Number.isSafeInteger(height) && height >= 36 && height <= 180, 'Frame dimensions out of bounds');
  ensure(Number.isSafeInteger(maxShift) && maxShift >= 0 && maxShift <= 12 &&
    Math.min(width, height) > (maxShift + 2) * 4, 'Global shift bound invalid');
  const count = width * height;
  ensure(before instanceof Uint8Array && before.length === count &&
    after instanceof Uint8Array && after.length === count, 'Grayscale frame length mismatch');
  ensure(Array.isArray(regions) && regions.length && regions.length <= 6, 'Foreground regions required');
  regions.forEach(validateRegion);
  const margin = maxShift + 2;
  const samples = [];
  let backgroundCount = 0, subjectCount = 0;
  // Use SAME internal coordinates for every translation candidate: no crop bias.
  for (let y = margin; y < height - margin; y += 3) {
    for (let x = margin; x < width - margin; x += 3) {
      const focus = insideAnyRegion(x, y, width, height, regions);
      if (focus) subjectCount++; else backgroundCount++;
      samples.push({ index: y * width + x, focus });
    }
  }
  ensure(backgroundCount >= 70 && subjectCount >= 25, 'Foreground ROI leaves insufficient foreground/background coverage');
  let best = { dx: 0, dy: 0, mean: Infinity };
  for (let dy = -maxShift; dy <= maxShift; dy++) {
    for (let dx = -maxShift; dx <= maxShift; dx++) {
      let sum = 0;
      for (const p of samples) {
        if (p.focus) continue;
        sum += Math.abs(before[p.index] - after[p.index + dy * width + dx]);
      }
      const error = sum / backgroundCount;
      if (error < best.mean - 0.001 ||
        (Math.abs(error - best.mean) <= 0.001 && dx * dx + dy * dy < best.dx * best.dx + best.dy * best.dy)) {
        best = { dx, dy, mean: error };
      }
    }
  }
  let rawBg = 0, alignedBg = 0, alignedSubject = 0;
  let bgChanged = 0, subjectChanged = 0;
  for (const p of samples) {
    const aligned = Math.abs(before[p.index] - after[p.index + best.dy * width + best.dx]);
    if (p.focus) {
      alignedSubject += aligned;
      if (aligned >= 18) subjectChanged++;
    } else {
      rawBg += Math.abs(before[p.index] - after[p.index]);
      alignedBg += aligned;
      if (aligned >= 18) bgChanged++;
    }
  }
  const rawBgMean = rawBg / backgroundCount;
  const alignedBgMean = alignedBg / backgroundCount;
  const alignedSubjectMean = alignedSubject / subjectCount;
  const bgChangedFraction = bgChanged / backgroundCount;
  const subjectChangedFraction = subjectChanged / subjectCount;
  const shift = Math.hypot(best.dx, best.dy);
  const alignmentImprovement = rawBgMean > 1 ? clamp01((rawBgMean - alignedBgMean) / rawBgMean) : 0;
  const camera = shift >= 1 && alignmentImprovement >= 0.25 && rawBgMean >= 5;
  const local = subjectChangedFraction >= 0.06 &&
    (subjectChangedFraction - bgChangedFraction >= 0.045) &&
    (alignedSubjectMean >= alignedBgMean * 1.15 + 2);
  let label;
  if (bgChangedFraction >= 0.60 && alignedBgMean >= 27 && subjectChangedFraction >= 0.4) {
    label = LABELS.cut;
  } else if (local) {
    label = camera ? LABELS.mixed : LABELS.local;
  } else if (camera && subjectChangedFraction < 0.1 && bgChangedFraction < 0.1) {
    label = LABELS.camera;
  } else if (subjectChangedFraction < 0.02 && bgChangedFraction < 0.02 && rawBgMean < 6) {
    label = LABELS.static;
  } else if (subjectChangedFraction < 0.045 && bgChangedFraction >= 0.06) {
    label = LABELS.background;
  } else if (camera && bgChangedFraction < 0.15) {
    label = LABELS.camera;
  } else {
    label = LABELS.unknown;
  }
  return {
    label, globalTranslation: { dx: best.dx, dy: best.dy, distancePx: rounded(shift),
      backgroundAlignmentImprovement: rounded(alignmentImprovement) },
    normalizedMetrics: {
      backgroundRawMeanAbsoluteDifference: rounded(rawBgMean / 255),
      backgroundAlignedMeanAbsoluteDifference: rounded(alignedBgMean / 255),
      interestAlignedMeanAbsoluteDifference: rounded(alignedSubjectMean / 255),
      backgroundChangedFraction: rounded(bgChangedFraction),
      interestChangedFraction: rounded(subjectChangedFraction),
      localizedChangeExcess: rounded(subjectChangedFraction - bgChangedFraction),
    },
  };
}
/** Audit all pairs strictly within named shots, excluding cross-cut pairs. */
export function analyzeSequence(samples, {
  width = 160, height = 90, originalFps = 24, originalFrameCount, sampleFps = 6,
  shotPlan, maxShift = 6,
} = {}) {
  ensure(Array.isArray(samples) && samples.length >= 3 && samples.length <= 120,
    'Need 3-120 decoded grayscale frames');
  ensure(Number.isSafeInteger(originalFps) && originalFps >= 1 && originalFps <= 120 &&
    Number.isSafeInteger(sampleFps) && sampleFps >= 1 && sampleFps <= originalFps &&
    originalFps % sampleFps === 0, 'Sampling must be an integer divisor of source FPS');
  validateShotPlan(shotPlan, originalFrameCount);
  const stride = originalFps / sampleFps;
  const expected = Math.ceil(originalFrameCount / stride);
  ensure(samples.length === expected, `Decoded sample count mismatch: expected ${expected}, got ${samples.length}`);
  const shots = shotPlan.shots.map(shot => ({
    id: shot.id, originalFrames: [shot.start, shot.end],
    regionSource: 'human-authored-coarse-rectangles', regions: shot.regions,
    pairs: [], labelCounts: {},
  }));
  let ignoredCuts = 0;
  for (let i = 1; i < samples.length; i++) {
    const previousOriginal = (i - 1) * stride, currentOriginal = i * stride;
    const shotIndex = shotPlan.shots.findIndex(s => s.start <= currentOriginal && s.end >= currentOriginal);
    const previousIndex = shotPlan.shots.findIndex(s => s.start <= previousOriginal && s.end >= previousOriginal);
    ensure(shotIndex >= 0 && previousIndex >= 0, 'Decoded frame outside authored shots');
    if (shotIndex !== previousIndex) { ignoredCuts++; continue; }
    const match = analyzePair(samples[i - 1], samples[i], width, height, shotPlan.shots[shotIndex].regions, { maxShift });
    const shot = shots[shotIndex];
    const pair = { sourceFrames: [previousOriginal, currentOriginal], ...match };
    shot.pairs.push(pair);
    shot.labelCounts[match.label] = (shot.labelCounts[match.label] ?? 0) + 1;
  }
  const allPairs = shots.flatMap(s => s.pairs);
  const cameraOnly = allPairs.filter(p => p.label === LABELS.camera).length;
  const local = allPairs.filter(p => [LABELS.local, LABELS.mixed].includes(p.label)).length;
  const staticCount = allPairs.filter(p => p.label === LABELS.static).length;
  const background = allPairs.filter(p => p.label === LABELS.background).length;
  const warnings = [];
  if (local === 0) warnings.push('NO_LOCALIZED_MOTION_EVIDENCE: No decoded pair demonstrates localized pixel change above background in the authored interest regions.');
  if (cameraOnly + background >= allPairs.length * 0.65)
    warnings.push('CAMERA_OR_BACKGROUND_DOMINANT: Most measured pairs lack localized motion evidence.');
  if (staticCount >= allPairs.length * 0.5) warnings.push('FREQUENT_STATIC_FRAMES: At least half the sampled pairs have negligible pixel motion.');
  return {
    schema: 'omega.motion-dna.report.v0.2',
    analysisKind: 'low-resolution-grayscale-global-translation-and-region-residual',
    status: 'MEASURED', qualityDecision: 'MANUAL_REVIEW_REQUIRED',
    measured: { width, height, originalFps, sampleFps, originalFrames: originalFrameCount,
      decodedSampleCount: samples.length, evaluatedPairs: allPairs.length, excludedShotBoundaries: ignoredCuts,
      localizedMotionCandidatePairs: local, globalTranslationOnlyCandidatePairs: cameraOnly,
      backgroundOnlyCandidatePairs: background, nearStaticPairs: staticCount,
    }, warnings, shots,
    notProven: [
      'Pose, skeleton, joint tracking or anatomical correctness',
      'True object segmentation: interest rectangles were explicitly hand authored',
      'Actual camera transform or optical flow: only bounded global integer translation estimated',
      'Original full-resolution motion fidelity or all unsampled frames',
      'Actor identity, expressive acting, production-grade visual quality',
      'Real Android phone playback, audio and lip synchronization',
    ],
  };
}
