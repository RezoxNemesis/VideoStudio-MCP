/**
 * Project OMEGA M2.1: bounded, read-only kinematic analysis of the existing
 * Android SketchAnimationCompiler's authored WORLD-space joint snapshots.
 * Not neural pose detection, silhouette collision, or artistic certification.
 */
const MAX_FRAMES = 360;
const MAX_JOINTS = 1024;
const MAX_RIGS = 16;
const MAX_POINTS_ABS = 8;
const MAX_WARNS = 80;
const SHA256 = /^[a-f0-9]{64}$/i;
const finite = n => typeof n === 'number' && Number.isFinite(n);
const record = n => n !== null && typeof n === 'object' && !Array.isArray(n);
const validId = n => typeof n === 'string' && n.length > 0 && n.length <= 100 && /^[\w.-]+$/.test(n);
const pxDist = (a,b,width,height) => Math.hypot((a.x-b.x)*width,(a.y-b.y)*height);
const round = x => Math.round(x*1000)/1000;
function requireTrack(test, reason) { if (!test) throw new Error('OMEGA_SKELETON: ' + reason); }
function warning(type, details) { return {code:type,...details}; }

function validateShotPlan(shotPlan, count, knownRigs) {
  requireTrack(record(shotPlan) && shotPlan.schema === 'omega.skeleton-shot-plan.v1' &&
    Array.isArray(shotPlan.shots) && shotPlan.shots.length>=1 && shotPlan.shots.length<=24,
  'Explicit, bounded rig visibility shot plan required');
  let next = 0;
  for(const shot of shotPlan.shots){
    requireTrack(record(shot) && validId(shot.id) && Number.isSafeInteger(shot.start) &&
      Number.isSafeInteger(shot.end) && shot.start === next && shot.end >= shot.start &&
      shot.end < count && Array.isArray(shot.activeRigs) && shot.activeRigs.length>0 &&
      shot.activeRigs.length<=MAX_RIGS && new Set(shot.activeRigs).size===shot.activeRigs.length &&
      shot.activeRigs.every(id=>knownRigs.has(id)), 'Invalid/overlapping shot or unknown rig');
    next=shot.end+1;
  }
  requireTrack(next===count, 'Shot plan must cover the entire native frame timeline exactly');
}

function validateProgramAndTracks(trace, program, shots) {
  requireTrack(record(trace) && trace.schema==='videostudio.native-rig-tracks.v1' &&
    record(program) && program.schema==='videostudio.sketch-animation.v1', 'Native rig/program schema required');
  const {frameCount:count, fps, width, height, frames} = trace;
  requireTrack(Number.isSafeInteger(count) && count>=2 && count<=MAX_FRAMES &&
    fps===24 && Number.isSafeInteger(width) && width>=1 && width<=8192 &&
    Number.isSafeInteger(height) && height>=1 && height<=8192 &&
    Array.isArray(frames) && frames.length===count &&
    program.frameCount===count && program.fps===fps &&
    SHA256.test(trace.sourceSha256??'') && SHA256.test(trace.programSha256??''),
  'Invalid frame count/rate/dimensions, program timeline or source hashes');
  requireTrack(Array.isArray(program.rigs) && program.rigs.length>=1 && program.rigs.length<=MAX_RIGS,
    'Program rigs must be bounded');
  const rigJoints = new Map();
  const rigIk = new Map();
  for(const rig of program.rigs){
    requireTrack(record(rig) && validId(rig.id) && !rigJoints.has(rig.id) &&
      Array.isArray(rig.joints) && rig.joints.length>0 && rig.joints.length<=64,
      'Invalid or duplicated authored rig');
    const joints=new Map();
    for(const item of rig.joints){
      requireTrack(record(item) && validId(item.id) && !joints.has(item.id) &&
        (item.parent === undefined || validId(item.parent)), 'Invalid authored joint');
      joints.set(item.id,item.parent??null);
    }
    requireTrack([...joints.values()].filter(v=>v===null).length===1, 'Exactly one root required per rig');
    for(const [id,parent] of joints){
      if(parent!==null){
        requireTrack(joints.has(parent), 'Missing authored parent');
        const chain=new Set([id]); let at=parent;
        while(at!==null){requireTrack(!chain.has(at),'Cyclic authored joints');chain.add(at);at=joints.get(at);}
      }
    }
    rigJoints.set(rig.id,joints);
    const contacts=Array.isArray(rig.ik)?rig.ik:[];
    requireTrack(contacts.length<=64,'Too many authored contacts');
    for(const c of contacts){
      requireTrack(record(c) && validId(c.ankle) && joints.has(c.ankle) &&
        Number.isSafeInteger(c.start) && Number.isSafeInteger(c.end) && c.start>=0 && c.end<count && c.end>=c.start &&
        finite(c.x) && finite(c.y) && Math.abs(c.x)<MAX_POINTS_ABS && Math.abs(c.y)<MAX_POINTS_ABS,
        'Malformed hard foot contact');
    }
    rigIk.set(rig.id,contacts);
  }
  validateShotPlan(shots,count,rigJoints);
  requireTrack(count * [...rigJoints.values()].reduce((sum, j)=>sum+j.size,0)<=MAX_FRAMES*MAX_JOINTS,
    'Too many rig samples');
  const normalized=[];
  for(let fi=0;fi<count;fi++){
    const f=frames[fi];
    requireTrack(record(f) && f.frame===fi && Array.isArray(f.joints) &&
      f.joints.length===[...rigJoints.values()].reduce((sum,m)=>sum+m.size,0) &&
      f.joints.length<=MAX_JOINTS && Array.isArray(f.contactErrors) && f.contactErrors.length<=64,
      'Frame index/joints/contact errors invalid at frame '+fi);
    const joints=new Map();
    for(const j of f.joints){
      requireTrack(record(j) && validId(j.rigId) && rigJoints.has(j.rigId) &&
        validId(j.jointId) && rigJoints.get(j.rigId).has(j.jointId) &&
        j.parent===rigJoints.get(j.rigId).get(j.jointId) &&
        finite(j.x) && finite(j.y) && finite(j.rotation) &&
        Math.abs(j.x)<=MAX_POINTS_ABS && Math.abs(j.y)<=MAX_POINTS_ABS && Math.abs(j.rotation)<36000 &&
        typeof j.cameraApplied==='boolean' && finite(j.displayX) && finite(j.displayY) &&
        Math.abs(j.displayX)<50 && Math.abs(j.displayY)<50,
        'Invalid native joint position/parent at frame '+fi);
      const id=j.rigId+':'+j.jointId;
      requireTrack(!joints.has(id),'Duplicate joint at frame '+fi);
      joints.set(id,j);
    }
    const contacts=new Map();
    for(const c of f.contactErrors){
      requireTrack(record(c) && validId(c.rigId) && validId(c.jointId) &&
        joints.has(c.rigId+':'+c.jointId) && finite(c.targetX) && finite(c.targetY) &&
        finite(c.errorPixels) && c.errorPixels>=0 && c.errorPixels<100000 &&
        (!Object.hasOwn(c,'rotationErrorDegrees')||
        (finite(c.rotationErrorDegrees)&&c.rotationErrorDegrees>=0)),
      'Malformed solver contact at frame '+fi);
      const key=c.rigId+':'+c.jointId;
      requireTrack(!contacts.has(key), 'Duplicate native contact diagnostic');
      contacts.set(key,c);
    }
    normalized.push({joints,contacts});
  }
  return {normalized,rigJoints,rigIk,width,height,count,fps};
}

/**
 * Returned motion/foot-slip warnings are advisory; corrupt traces BLOCK.
 * Only original COMPILER joint positions in prepared source-canvas coordinates.
 */
export function analyzeNativeSkeleton(trace, program, shotPlan) {
  const {normalized,rigJoints,rigIk,width,height,count,fps}=validateProgramAndTracks(trace,program,shotPlan);
  const findings=[], shots=[], cueMetrics=[];
  let totalRootRelativePairs=0, totalPairs=0, plantedSamples=0;
  let overallMaxFootSlip=0, overallMaxBoneDrift=0, overallMaxVelocity=0;
  const warn=(code,info)=>{if(findings.length<MAX_WARNS)findings.push(warning(code,info));};

  for(const shot of shotPlan.shots){
    const shotResult={id:shot.id,start:shot.start,end:shot.end,activeRigs:[]};
    for(const rigId of shot.activeRigs){
      const spec=rigJoints.get(rigId);
      const rootId=[...spec].find(([,p])=>p===null)[0];
      const boneRest=new Map();
      const ik=rigIk.get(rigId);
      const individual={rigId, frames:shot.end-shot.start+1, joints:spec.size,
        rootTravelPx:0,worldJointTravelPx:0,rootRelativeJointTravelPx:0,
        articulatedPairs:0,totalPairs:0,maximumJointStepPx:0,maximumJointAccelerationPxFrame2:0,
        maxBoneLengthDriftPx:0,hardFootSamples:0,maxHardFootSlipPx:0};
      let prev=null,prevVelocity=new Map();
      for(let f=shot.start;f<=shot.end;f++){
        const set=normalized[f].joints;
        const root=set.get(rigId+':'+rootId);
        requireTrack(root!==undefined, 'Absent active root');
        let anyActing=false, currentVelocity=new Map();
        for(const [jointId,parent] of spec){
          const joint=set.get(rigId+':'+jointId);
          requireTrack(joint!==undefined,'Missing active joint');
          if(parent!==null){
            const p=set.get(rigId+':'+parent);
            const bone=pxDist(joint,p,width,height);
            if(!boneRest.has(jointId))boneRest.set(jointId,bone);
            individual.maxBoneLengthDriftPx=Math.max(individual.maxBoneLengthDriftPx,Math.abs(bone-boneRest.get(jointId)));
          }
          if(prev){
            const old=prev.joints.get(rigId+':'+jointId);
            const step=pxDist(joint,old,width,height);
            individual.worldJointTravelPx+=step;
            individual.maximumJointStepPx=Math.max(individual.maximumJointStepPx,step);
            const vx=(joint.x-old.x)*width,vy=(joint.y-old.y)*height;
            currentVelocity.set(jointId,{x:vx,y:vy});
            if(prevVelocity.has(jointId)){
              const last=prevVelocity.get(jointId);
              individual.maximumJointAccelerationPxFrame2=Math.max(individual.maximumJointAccelerationPxFrame2,Math.hypot(vx-last.x,vy-last.y));
            }
            if(parent!==null){
              const oldRoot=prev.joints.get(rigId+':'+rootId);
              const dx=((joint.x-root.x)-(old.x-oldRoot.x))*width;
              const dy=((joint.y-root.y)-(old.y-oldRoot.y))*height;
              const relative=Math.hypot(dx,dy);
              individual.rootRelativeJointTravelPx+=relative;
              if(relative>.25)anyActing=true;
            }
          }
        }
        if(prev){
          const previousRoot=prev.joints.get(rigId+':'+rootId);
          individual.rootTravelPx+=pxDist(root,previousRoot,width,height);
          individual.totalPairs++;if(anyActing)individual.articulatedPairs++;
        }
        prev=normalized[f];prevVelocity=currentVelocity;
        for(const c of ik){
          if(f<c.start||f>c.end)continue;
          const targetJoint=set.get(rigId+':'+c.ankle);
          const native=normalized[f].contacts.get(rigId+':'+c.ankle);
          requireTrack(native!==undefined, 'Native hard-contact receipt missing at frame '+f);
          requireTrack(Math.abs(native.targetX-c.x)<1e-6 && Math.abs(native.targetY-c.y)<1e-6,
            'Authored and native contact targets disagree');
          const slip=Math.hypot((targetJoint.x-c.x)*width,(targetJoint.y-c.y)*height);
          requireTrack(Math.abs(slip-native.errorPixels)<.05,
            'Native contact diagnostic disagrees with sampled joint');
          individual.hardFootSamples++;
          individual.maxHardFootSlipPx=Math.max(individual.maxHardFootSlipPx,slip);
        }
      }
      overallMaxFootSlip=Math.max(overallMaxFootSlip,individual.maxHardFootSlipPx);
      overallMaxBoneDrift=Math.max(overallMaxBoneDrift,individual.maxBoneLengthDriftPx);
      overallMaxVelocity=Math.max(overallMaxVelocity,individual.maximumJointStepPx);
      totalRootRelativePairs+=individual.articulatedPairs;totalPairs+=individual.totalPairs;
      if(individual.maxHardFootSlipPx>1)warn('PLANTED_FOOT_SLIP',{shot:shot.id,rigId,px:round(individual.maxHardFootSlipPx)});
      if(individual.maxBoneLengthDriftPx>2)warn('BONE_LENGTH_VARIATION',{shot:shot.id,rigId,px:round(individual.maxBoneLengthDriftPx)});
      if(individual.maximumJointStepPx>100)warn('FAST_JOINT_DISPLACEMENT',{shot:shot.id,rigId,pxPerFrame:round(individual.maximumJointStepPx)});
      if(individual.frames>=8 && individual.joints>1 && individual.articulatedPairs===0)
        warn('NO_ROOT_RELATIVE_ARTICULATION',{shot:shot.id,rigId});
      plantedSamples+=individual.hardFootSamples;
      shotResult.activeRigs.push(Object.fromEntries(Object.entries(individual).map(([k,v])=>[k,typeof v==='number'?round(v):v])));
    }
    shots.push(shotResult);
  }
  const cues=Array.isArray(program.interactionCues)?program.interactionCues:[];
  requireTrack(cues.length<=64,'Too many authored inter-character cues');
  for(const cue of cues){
    requireTrack(record(cue) && Number.isSafeInteger(cue.frame) && cue.frame>=0 && cue.frame<count &&
      validId(cue.rigA)&&validId(cue.rigB)&&cue.rigA!==cue.rigB &&
      validId(cue.jointA)&&validId(cue.jointB) && finite(cue.maxGapPx) && cue.maxGapPx>0,
    'Malformed authored interaction');
    const shot=shotPlan.shots.find(x=>cue.frame>=x.start&&cue.frame<=x.end);
    if(!shot.activeRigs.includes(cue.rigA)||!shot.activeRigs.includes(cue.rigB))continue;
    const j1=normalized[cue.frame].joints.get(cue.rigA+':'+cue.jointA);
    const j2=normalized[cue.frame].joints.get(cue.rigB+':'+cue.jointB);
    requireTrack(j1&&j2,'Authored contact refers to missing native joints');
    const measured=pxDist(j1,j2,width,height);
    const passed=measured<=cue.maxGapPx+.0001;
    cueMetrics.push({frame:cue.frame,shot:shot.id,from:cue.rigA+':'+cue.jointA,
      to:cue.rigB+':'+cue.jointB,gapPx:round(measured),allowedPx:cue.maxGapPx,withinTolerance:passed});
    if(!passed)warn('INTERACTION_PROXIMITY_MISS',{frame:cue.frame,gapPx:round(measured),allowedPx:cue.maxGapPx});
    if(passed && measured>25)warn('LOOSE_CONTACT_VISUAL_REVIEW',{frame:cue.frame,gapPx:round(measured)});
  }
  return {
    schema:'omega.skeleton-dna.report.v0.1',status:'MEASURED',qualityDecision:'MANUAL_REVIEW_REQUIRED',
    source:'Existing SketchAnimationCompiler.sampleFrames (original native authored rigs)',
    measurements:{frameCount:count,fps,width,height,authoredRigCount:rigJoints.size,
      activeShotCount:shots.length,rootRelativeArticulationPairs:totalRootRelativePairs,
      totalRigPairs:totalPairs,hardFootContactSamples:plantedSamples,
      maxHardFootSlipPx:round(overallMaxFootSlip),maxBoneLengthDriftPx:round(overallMaxBoneDrift),
      maxJointStepPxPerFrame:round(overallMaxVelocity),checkedInteractionCues:cueMetrics.length},
    shots,interactionCues:cueMetrics,findings,
    notProven:['full-resolution rendered artwork alignment','correct character anatomy or acting',
      'true mesh/weapon collision','pose extraction from ordinary videos','phone playback or performance',
      'professional cinematic quality'],
    ownerPhonePlaybackVerified:false,professionalAnimationVerified:false,
  };
}
