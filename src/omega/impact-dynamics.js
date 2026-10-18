/** OMEGA M3.1: independently audit native authored full-body contact response.
 * Real source-space solver joints only. Never infer professional acting, physics
 * simulation or weapon collision from a proximity metric. */
const ok = (value, why) => { if (!value) throw new Error('OMEGA_IMPACT_QA: ' + why); };
const finite = n => typeof n === 'number' && Number.isFinite(n);
const record = n => n !== null && typeof n === 'object' && !Array.isArray(n);
const round = n => Math.round(n * 1000) / 1000;
const ACTORS = ['wide_rig','rook_wide_rig'];
const W = 1280, H = 720;
function joint(frame, rigId, jointId) {
  ok(record(frame) && Array.isArray(frame.joints), 'Missing native solver joints');
  const matches = frame.joints.filter(j => j.rigId === rigId && j.jointId === jointId);
  ok(matches.length === 1 && finite(matches[0].x) && finite(matches[0].y) &&
    finite(matches[0].rotation), 'Invalid native '+rigId+':'+jointId);
  return matches[0];
}
function distance(a,b) { return Math.hypot((a.x-b.x)*W,(a.y-b.y)*H); }
function wristGap(frame) { return distance(joint(frame,ACTORS[0],'wrist'),joint(frame,ACTORS[1],'wrist')); }
function stepPeak(frames) {
  let maximum = 0;
  for (let f=12;f<=20;f++) for (const rig of ACTORS) {
    maximum = Math.max(maximum,distance(joint(frames[f],rig,'wrist'),joint(frames[f-1],rig,'wrist')));
  }
  return maximum;
}

export function auditImpactDynamics(trace, program, stage, previousPhysics) {
  ok(record(trace) && trace.schema === 'videostudio.native-rig-tracks.v1' &&
    trace.frameCount === 100 && trace.fps === 24 && trace.width === W && trace.height === H &&
    Array.isArray(trace.frames) && trace.frames.length === 100 &&
    trace.frames.every((s,i) => s.frame === i), 'Invalid or unordered native full skeleton trace');
  ok(record(program) && program.schema === 'videostudio.sketch-animation.v1' &&
    program.frameCount === 100 && program.fps === 24 && Array.isArray(program.rigs),
    'Expected exact reviewed editable 100-frame pose program');
  ok(record(previousPhysics) && previousPhysics.schema === 'omega.physics-brain.report.v0.3' &&
    previousPhysics.status === 'MEASURED' && previousPhysics.qualityDecision === 'MANUAL_REVIEW_REQUIRED' &&
    previousPhysics.contact?.maxFootContactErrorPx <= .02 &&
    previousPhysics.contact?.verifiedGapPx <= 6, 'M3 native contact receipt missing');
  ok(record(stage) && record(stage.contactDirector) && record(stage.impactDynamics) &&
    stage.impactDynamics.schema === 'videostudio.impact-dynamics.v1',
    'Original native stage does not include independently authored acting pass');
  const claim = stage.impactDynamics;
  ok(claim.impactPoseUnchanged === true && claim.sourceArtChanged === false &&
    claim.cameraChanged === false && claim.humanReviewRequired === true &&
    claim.motionKind === 'bounded-authored-pose-response-not-general-physics' &&
    claim.impactFrame === 16, 'Native acting provenance or scope was misrepresented');
  ok(Array.isArray(claim.keyframeCorrections) && claim.keyframeCorrections.length === 10 &&
    ACTORS.every(rig => [5,11,20,25,30].every(frame =>
      claim.keyframeCorrections.filter(x => x.rigId===rig && x.frame===frame).length===1)),
    'Unexpected or missing authorized native secondary acting keys');
  const gap = wristGap(trace.frames[16]);
  ok(gap <= 6.001 && Math.abs(gap-claim.unchangedContactGapPx) < .05 &&
    Math.abs(gap-previousPhysics.contact.verifiedGapPx) < .05,
    'M3 impact wrist accuracy was lost or native receipts disagree');
  let footSamples=0,maxFootError=0;
  for (let f=5;f<=30;f++) {
    const contacts = trace.frames[f].contactErrors;
    ok(Array.isArray(contacts), 'Missing native planted-foot solver evidence');
    const active=contacts.filter(c => ACTORS.includes(c.rigId) &&
      ['front_ankle','back_ankle'].includes(c.jointId));
    ok(active.length===4 && new Set(active.map(c => c.rigId+':'+c.jointId)).size===4,
      'A hard-planted foot was lost at frame '+f);
    for (const c of active) {
      ok(finite(c.errorPixels) && c.errorPixels >= 0 && c.errorPixels <= .02,
        'Measured foot slipping at frame '+f);
      maxFootError=Math.max(maxFootError,c.errorPixels);footSamples++;
    }
  }
  const peak=stepPeak(trace.frames);
  ok(finite(claim.originalMaxWristStepPx) && finite(claim.verifiedMaxWristStepPx) &&
    claim.originalMaxWristStepPx >= peak+5 && claim.originalMaxWristStepPx < 80 &&
    peak <=55 && Math.abs(peak-claim.verifiedMaxWristStepPx)<.05 &&
    Math.abs(peak-previousPhysics.contact.maxWristStepPx)<.05,
    'Impact trajectory did not reduce sharp wrist-frame movement');
  ok(footSamples===104 && claim.checkedPlantedFootSamples===104 &&
    Math.abs(maxFootError-claim.maxHardFootErrorPx) < .05,
    'Native foot-contact receipt disagrees with sampled solver');
  const body=[];
  for (const rig of ACTORS) {
    const rootImpact=joint(trace.frames[16],rig,'root'),rootRecoil=joint(trace.frames[20],rig,'root');
    const sink=(rootRecoil.y-rootImpact.y)*H;
    ok(Math.abs(rootRecoil.x-rootImpact.x)*W < .01 && sink >= 5 && sink <= 20,
      'Post-contact weight response missing or translated sideways');
    const scarfAtContact=joint(trace.frames[16],rig,'scarf');
    const scarfAtRecoil=joint(trace.frames[20],rig,'scarf');
    const chestAtContact=joint(trace.frames[16],rig,'chest');
    const chestAtRecoil=joint(trace.frames[20],rig,'chest');
    const scarfDelta=Math.abs((scarfAtRecoil.rotation-chestAtRecoil.rotation)-
      (scarfAtContact.rotation-chestAtContact.rotation));
    ok(scarfDelta>=8, 'Secondary scarf lag is missing or too small');
    body.push({rigId:rig,downwardRecoilPx:round(sink),scarfRelativeRotationChangeDeg:round(scarfDelta)});
  }
  ok(Math.abs(body[0].downwardRecoilPx-claim.heroPostImpactSinkPx)<.05 &&
    Math.abs(body[1].downwardRecoilPx-claim.rivalPostImpactSinkPx)<.05,
    'Native torso recoil disagrees with authored stage receipt');
  return {
    schema:'omega.impact-dynamics.report.v0.31',status:'MEASURED',qualityDecision:'MANUAL_REVIEW_REQUIRED',
    measured:{impactFrame:16,contactGapPx:round(gap),
      originalMaxWristStepPx:round(claim.originalMaxWristStepPx),
      verifiedMaxWristStepPx:round(peak),
      reducedMaxWristStepPx:round(claim.originalMaxWristStepPx-peak),
      actualHardFootSamples:footSamples,maxFootErrorPx:round(maxFootError),
      editedExistingKeys:10,body},
    method:'Native authored rig solver plus explicit prepared-key response; NOT full physical dynamics',
    professionalActingVerified:false,physicalPhoneVerified:false,weaponCollisionVerified:false,
    automaticFilmReleaseApproved:false,
    notProven:['Anatomically convincing human weight shift','Exact weapon mesh or blade collision',
      'True simulated cloth/hair dynamics','Native physical Android device playback and sound'],
  };
}