/**
 * OMEGA M3: independently inspect the full native authored-rig trace after
 * non-destructive contact refinement. This does not score professional acting
 * or prove sword/skin collision; only bounded world-space joint geometry.
 */
const finite = n => typeof n === 'number' && Number.isFinite(n);
const record = n => n !== null && typeof n === 'object' && !Array.isArray(n);
const fail = why => { throw new Error('OMEGA_PHYSICS: ' + why); };
const ensure = (ok, why) => { if (!ok) fail(why); };
const round = n => Math.round(n * 1000) / 1000;

function findJoint(frame, rig, joint) {
  const hits = frame.joints.filter(j => j.rigId === rig && j.jointId === joint);
  ensure(hits.length === 1 && finite(hits[0].x) && finite(hits[0].y),
    `Native joint ${rig}:${joint} missing or invalid at frame ${frame.frame}`);
  return hits[0];
}
function pxDistance(a, b, width, height) {
  return Math.hypot((a.x - b.x) * width, (a.y - b.y) * height);
}

/** Pure audit of a verified source-native solver trace; not a media decoder. */
export function auditContactRecovery(trace, program, stage, skeleton) {
  ensure(record(trace) && trace.schema === 'videostudio.native-rig-tracks.v1' &&
    trace.frameCount === 100 && trace.fps === 24 && trace.width === 1280 && trace.height === 720 &&
    Array.isArray(trace.frames) && trace.frames.length === 100,
  'Expected the genuine 100-frame source-native duel rig trace');
  ensure(record(program) && program.schema === 'videostudio.sketch-animation.v1' &&
    program.frameCount === 100 && Array.isArray(program.rigs) &&
    Array.isArray(program.interactionCues), 'Expected source-native authored rig program');
  ensure(record(skeleton) && skeleton.status === 'MEASURED' &&
    skeleton.qualityDecision === 'MANUAL_REVIEW_REQUIRED' &&
    skeleton.measurements?.frameCount === 100 &&
    skeleton.measurements?.checkedInteractionCues === 1 &&
    skeleton.measurements?.hardFootContactSamples >= 20 &&
    finite(skeleton.measurements?.maxHardFootSlipPx) &&
    skeleton.measurements.maxHardFootSlipPx <= .02,
  'Prior M2.1 native-skeleton or planted-foot evidence failed');
  ensure(record(stage) && record(stage.contactDirector) &&
    stage.contactDirector.schema === 'videostudio.contact-director.v1',
  'Native stage did not record the real contact refinement');
  const author = stage.contactDirector;
  const cue = program.interactionCues.find(c => c.frame === author.frame &&
    c.rigA === author.rigA && c.rigB === author.rigB &&
    c.jointA === author.joint && c.jointB === author.joint);
  ensure(record(cue) && finite(cue.maxGapPx) && cue.maxGapPx >= 1 && cue.maxGapPx <= 8 &&
    author.frame === 16 && author.joint === 'wrist' &&
    author.rigA === 'wide_rig' && author.rigB === 'rook_wide_rig' &&
    finite(author.requestedGapPx) && author.requestedGapPx <= 6 &&
    finite(author.authoredTolerancePx) && author.authoredTolerancePx >= 20,
  'Missing strict authored hand contact cue or baseline tolerance');
  ensure(author.rootOrCameraEdited === false && author.hardFootContactsPreserved === true,
    'Native authoring must not translate roots or skip foot-contact preservation');
  ensure(Array.isArray(author.corrections) && author.corrections.length === 2 &&
    new Set(author.corrections.map(v => v.rig)).size === 2 &&
    author.corrections.every(v => record(v) &&
      finite(v.upperDeltaDeg) && finite(v.foreDeltaDeg) &&
      Math.abs(v.upperDeltaDeg) <= 22.001 && Math.abs(v.foreDeltaDeg) <= 22.001),
  'Contact correction must be bounded to two articulated arms');
  let totalAngularChange = 0;
  for (const correction of author.corrections) totalAngularChange +=
    Math.abs(correction.upperDeltaDeg) + Math.abs(correction.foreDeltaDeg);
  ensure(totalAngularChange >= 1 && totalAngularChange <= 88.01,
    'A meaningful bounded arm correction was not recorded');
  const sampled = trace.frames[16];
  ensure(sampled.frame === 16 && Array.isArray(sampled.joints), 'Impact frame absent');
  const a = findJoint(sampled, author.rigA, author.joint);
  const b = findJoint(sampled, author.rigB, author.joint);
  const currentGap = pxDistance(a,b,trace.width,trace.height);
  ensure(finite(author.baselineWristGapPx) && author.baselineWristGapPx >= 20 &&
    author.baselineWristGapPx <= author.authoredTolerancePx + .01 &&
    finite(author.verifiedWristGapPx) &&
    author.baselineWristGapPx - currentGap >= 15 &&
    Math.abs(currentGap - author.verifiedWristGapPx) < .05 &&
    Math.abs(currentGap - stage.batonWristDistancePx) < .05 &&
    currentGap <= author.requestedGapPx && currentGap <= cue.maxGapPx,
  'Actual native wrist proximity does not match the verified improved scene');
  ensure(Array.isArray(skeleton.interactionCues) && skeleton.interactionCues.length === 1 &&
    Math.abs(skeleton.interactionCues[0].gapPx - currentGap) < .05 &&
    skeleton.interactionCues[0].allowedPx === cue.maxGapPx,
  'Independent M2.1 skeleton contact receipt disagrees with contact director');
  let maxWristStepPx = 0, maxContactErrorPx = 0;
  const steps = [];
  for (let f = 11; f <= 20; f++) {
    const snap = trace.frames[f];
    ensure(snap.frame === f && Array.isArray(snap.joints) && Array.isArray(snap.contactErrors),
      'Missing ordered motion sample at frame ' + f);
    // Existing native IK plants both actors' feet until frame 35. Compare
    // the actual foot errors rather than trusting a preset or authored label.
    const activeContacts = snap.contactErrors.filter(c =>
      (c.rigId === author.rigA || c.rigId === author.rigB) &&
      (c.jointId === 'front_ankle' || c.jointId === 'back_ankle'));
    ensure(activeContacts.length === 4, 'Lost a planted-foot solver sample at frame ' + f);
    for (const contact of activeContacts) {
      ensure(finite(contact.errorPixels) && contact.errorPixels >= 0 && contact.errorPixels < .02,
        'Planted foot slipped during contact refinement at frame ' + f);
      maxContactErrorPx = Math.max(maxContactErrorPx, contact.errorPixels);
    }
    if (f > 11) {
      const prior = trace.frames[f-1];
      for (const rig of [author.rigA, author.rigB]) {
        const current = findJoint(snap,rig,'wrist'), previous = findJoint(prior,rig,'wrist');
        const step = pxDistance(current,previous,trace.width,trace.height);
        ensure(step <= 80, 'Unbounded one-frame wrist movement at frame ' + f);
        maxWristStepPx = Math.max(maxWristStepPx,step);
      }
      steps.push({frame:f,gapPx:round(pxDistance(findJoint(snap,author.rigA,'wrist'),
        findJoint(snap,author.rigB,'wrist'),trace.width,trace.height))});
    }
  }
  const result = {
    schema:'omega.physics-brain.report.v0.3',
    status:'MEASURED',qualityDecision:'MANUAL_REVIEW_REQUIRED',
    contact:{frame:16,rigA:author.rigA,rigB:author.rigB,
      originalGapPx:round(author.baselineWristGapPx),verifiedGapPx:round(currentGap),
      reductionPx:round(author.baselineWristGapPx-currentGap),
      maximumAllowedGapPx:cue.maxGapPx, requestedGapPx:author.requestedGapPx,
      totalJointAngleCorrectionDeg:round(totalAngularChange),
      maxWristStepPx:round(maxWristStepPx),
      nativeFootContactSamplesInWindow:40,
      maxFootContactErrorPx:round(maxContactErrorPx),
      approachRecoveryGapSamples:steps},
    evidenceBasis:'Full native authored joint and hard-foot-contact trace from Robolectric CI, not video pose inference',
    notProven:['Exact baton/weapon collision volumes','Professional acting or 2D anatomy',
      'Natural cloth, facial acting or environment physics','Playback on the owner Android device'],
    physicalPhoneVerified:false, professionalAnimationVerified:false,
  };
  return result;
}
