/**
 * OMEGA M3.3: independent, read-only crossed-weapon staging gate.
 * Measures source-canvas geometry from 100 genuine native solver snapshots.
 * A crossing on an editable vector drawing is not automatically professional
 * sword choreography, physical collision or a correctly played Android video.
 */
import { auditWeaponGeometry, interiorCrossing, weaponCore } from './weapon-geometry.js';
const ensure=(yes,why)=>{if(!yes)throw new Error('OMEGA_COMBAT_QA: '+why);};
const finite=n=>typeof n==='number'&&Number.isFinite(n);
const distance=(a,b)=>Math.hypot(a[0]-b[0],a[1]-b[1]);
const round=n=>Number(n.toFixed(4));
export function auditCombatStaging({source,program,native,weaponTrace,stageMetrics}){
  const prior=auditWeaponGeometry({source,program,native,weaponTrace,stageMetrics});
  ensure(prior.schema==='omega.weapon-geometry.report.v0.3.3'&&
    prior.measured.contactMode==='CROSSED_SHAFT_INTERIOR'&&
    prior.qualityDecision==='MANUAL_REVIEW_REQUIRED',
    'M3.3 requires a separately verified native crossed-weapon stage');
  const stage=stageMetrics.combatStaging;
  ensure(stage?.schema==='videostudio.crossed-baton-staging.v1'&&
    stage.frame===16&&stage.realisticGloveDeformationVerified===false&&
    stage.footContactsPreserved===true&&stage.releaseDecision==='MANUAL_REVIEW_REQUIRED',
    'Missing honest native staging provenance');
  const p=native.frames[16];
  const wide=weaponCore(source,program,p,'wide_rig');
  const rook=weaponCore(source,program,p,'rook_wide_rig');
  const actual=interiorCrossing(wide,rook);
  ensure(actual!==null,'Native authored shafts do not cross');
  const bounds=[source.width,source.height];
  ensure(actual.angleDeg>=35&&actual.angleDeg<=90,'Clash remains near-parallel or inverted');
  ensure(actual.fractionA>.35&&actual.fractionA<.75&&actual.fractionB>.35&&actual.fractionB<.75,
    'Weapon tips or handles, rather than interior edges, carry the impact');
  ensure(distance(wide[0],rook[0])>=40,'Drawn hand grips remain crowded');
  ensure(distance(wide[1],rook[1])>=25,'Weapon tips still meet like an inverted V');
  const earlier=stageMetrics.weaponContact;
  const legacy=[earlier.impactX*bounds[0],earlier.impactY*bounds[1]];
  const displacement=distance(legacy,actual.point);
  ensure(displacement>=35,'Impact effect was not moved from old tip-collision location');
  ensure(prior.measured.maximumWideShotTipStepPx<85,'Unbounded staged weapon snap');
  ensure(p.contactErrors?.length===4&&p.contactErrors.every(c=>finite(c.errorPixels)&&c.errorPixels<.02),
    'Native planted-foot solver evidence is not preserved at the clash');
  ensure(prior.measured.effectToTipContactOffsetPx<=1,'Impact flash is not centered at shaft crossing');
  return {
    schema:'omega.combat-staging.report.v0.3.3',status:'MEASURED',
    qualityDecision:'MANUAL_REVIEW_REQUIRED',
    measurable:{nativeFrameCount:native.frameCount,wideFrames:weaponTrace.frameCount,
      weaponCrossingAngleDeg:round(actual.angleDeg),
      gripSeparationPx:round(distance(wide[0],rook[0])),
      distalTipSeparationPx:round(distance(wide[1],rook[1])),
      contactFractions:[round(actual.fractionA),round(actual.fractionB)],
      flashAlignmentErrorPx:prior.measured.effectToTipContactOffsetPx,
      oldToNewImpactDisplacementPx:round(displacement),
      maximumWideShotTipStepPx:prior.measured.maximumWideShotTipStepPx,
      plantedFeetAtImpact:p.contactErrors.length},
    contactLocation:{xPx:round(actual.point[0]),yPx:round(actual.point[1])},
    notVerified:[
      'Actual human-finger regrip anatomy; grip follows the prepared vector hand, not individual fingers',
      'Full-width 2D weapon-silhouette intersection and weapon thickness',
      'Real 3D rigid-body forces, pain, emotion and film-quality acting',
      'Self-consistent native CI evidence does not verify the owner Android phone playback',
      'Original spoken dialogue, recorded audio or lip synchronization',
    ],
    professionalAnimationVerified:false,physicalPhoneVerified:false,automaticFilmReleaseApproved:false,
  };
}