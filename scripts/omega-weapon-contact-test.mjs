import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { auditWeaponGeometry, segmentGap, weaponCore } from '../src/omega/weapon-geometry.js';
import { auditOwnedWeaponProof } from './omega-weapon-contact.mjs';
const digest=b=>createHash('sha256').update(b).digest('hex');
const deep=x=>structuredClone(x);
const transform=(wrist,rotation=0)=>({x:0,y:0,scaleX:1,scaleY:1,rotation,anchorX:wrist,anchorY:.55,opacity:1});
function fixture(){
 const w=1280,h=720,rot=41.6335393365702;
 const joints=[{rigId:'wide_rig',jointId:'wrist',x:.5,y:.55,rotation:0},
               {rigId:'rook_wide_rig',jointId:'wrist',x:.502,y:.55,rotation:0}];
 const program={frameCount:100,rigs:[]},art=[];
 for(const [id,prefix,x,baseRot] of [['wide_rig','wide_',.5,-rot],['rook_wide_rig','rook_wide_',.502,rot]]){
   const ids=['baton','baton_edge','baton_core','baton_grip'].map(s=>prefix+s);
   program.rigs.push({id,joints:[{id:'wrist',x,y:.55}],bindings:ids.map(objectId=>({objectId,jointId:'wrist'}))});
   for(const name of ids){
    const profile=[[0,0],[8,0],[10,.2],[12,.45],[14,.75],[16,1],[18,.70],[20,.35],[24,0],[30,0],[35,0]];
    const keys=profile.map(([frame,m])=>({frame,interpolation:'linear',transform:transform(x,baseRot*m)}));
    const points=id==='wide_rig'?[[x,.54],[x+.1,.35]]:[[x,.54],[x-.1,.35]];
    art.push({id:name,kind:'path',frame:0,exposure:36,transform:transform(x),keys,
      commands:[['M',...points[0]],['L',...points[1]]]});
   }
 }
 const source={width:w,height:h,layers:[{id:'people',visible:true,objects:art}]};
 const native={frameCount:100,frames:Array.from({length:100},(_,frame)=>({frame,joints:deep(joints),
      contactErrors:frame===16?['wide_rig','rook_wide_rig'].flatMap(rigId=>['left','right'].map(jointId=>({rigId,jointId,errorPixels:0}))):[]}))};
 const frames=Array.from({length:36},(_,frame)=>{
  const a=weaponCore(source,program,native.frames[frame],'wide_rig'),b=weaponCore(source,program,native.frames[frame],'rook_wide_rig');
  return {frame,wideCore:a,rookCore:b,centerlineGapPx:segmentGap(a,b),tipGapPx:Math.hypot(a[1][0]-b[1][0],a[1][1]-b[1][1])};
 });
 const trace={schema:'videostudio.native-baton-core-trace.v1',frameCount:36,firstFrame:0,lastFrame:35,
  width:w,height:h,fullSilhouetteVerified:false,frames};
 const oldA=weaponCore(source,program,native.frames[16],'wide_rig',{ignoreWeaponRotation:true}),
 oldB=weaponCore(source,program,native.frames[16],'rook_wide_rig',{ignoreWeaponRotation:true});
 const before=Math.hypot(oldA[1][0]-oldB[1][0],oldA[1][1]-oldB[1][1]);
 const contact=frames[16];
 const midpoint=[(contact.wideCore[1][0]+contact.rookCore[1][0])/2/w,(contact.wideCore[1][1]+contact.rookCore[1][1])/2/h];
 source.layers[0].objects.push({id:'impact_flash',bounds:{x:midpoint[0]-.01,y:midpoint[1]-.01,width:.02,height:.02}});
 const stage={weaponContact:{schema:'videostudio.weapon-silhouette-contact.v1',beforeTipGapPx:before,
  afterTipGapPx:contact.tipGapPx,afterCenterlineGapPx:contact.centerlineGapPx,
  hardFootContactsPreserved:true,releaseDecision:'MANUAL_REVIEW_REQUIRED'}};
 return {source,program,native,weaponTrace:trace,stageMetrics:stage};
}
const audited=()=>auditWeaponGeometry(fixture());

test('independent weapon auditor finds drawn shaft tip contact, not just wrists',()=>{
 const result=audited();
 assert.equal(result.status,'MEASURED');
 assert.equal(result.qualityDecision,'MANUAL_REVIEW_REQUIRED');
 assert.ok(result.measured.originalTipSeparationPx>200);
 assert.ok(result.measured.correctedTipSeparationPx<6);
 assert.equal(result.measured.verifiedWideFrames,36);
 assert.equal(result.measured.nativePlantedFeetAtImpact,4);
 assert.equal(result.professionalAnimationVerified,false);
});
test('clearance geometry handles crossing and physically separated shafts',()=>{
 assert.equal(segmentGap([[0,0],[10,10]],[[0,10],[10,0]]),0);
 assert.equal(segmentGap([[0,0],[10,0]],[[0,5],[10,5]]),5);
 assert.equal(segmentGap([[0,0],[10,0]],[[15,0],[20,0]]),5);
});
test('a fraudulent 2D weapon trace or skipped native frame is rejected',()=>{
 const f=fixture();f.weaponTrace.frames[16].wideCore[1][0]+=5;
 assert.throws(()=>auditWeaponGeometry(f),/forged hero baton tip/);
 const g=fixture();g.native.frames[14].frame=13;
 assert.throws(()=>auditWeaponGeometry(g),/coverage mismatch/);
});
test('the original diverging weapon shafts cannot pass without independently authored correction',()=>{
 const f=fixture();
 for(const l of f.source.layers)for(const obj of l.objects)if(obj.id.includes('baton'))for(const k of obj.keys)k.transform.rotation=0;
 assert.throws(()=>auditWeaponGeometry(f),/forged hero baton|weapon shaft\/tip contact/);
});
test('missing wrist binding and mismatched shell/core keys are both rejected',()=>{
 const f=fixture();f.program.rigs[0].bindings[2].jointId='head';
 assert.throws(()=>auditWeaponGeometry(f),/rigidly bound/);
 const g=fixture();g.source.layers[0].objects[0].keys[5].transform.rotation=0;
 assert.throws(()=>auditWeaponGeometry(g),/rotate together/);
});
test('impact flash must follow actual tip intersection, not the wrist midpoint',()=>{
 const f=fixture();f.source.layers[0].objects.at(-1).bounds.y+=.3;
 assert.throws(()=>auditWeaponGeometry(f),/impact flash/);
});
test('missing planted foot evidence blocks geometry film acceptance',()=>{
 const f=fixture();f.native.frames[16].contactErrors=[];
 assert.throws(()=>auditWeaponGeometry(f),/planted feet/);
});
test('original full silhouette/physical phone certification cannot be fabricated',()=>{
 const f=fixture();f.weaponTrace.fullSilhouetteVerified=true;
 assert.throws(()=>auditWeaponGeometry(f),/prohibited film-grade/);
});
test('missing editable impact frame keys cannot pass rigid weapon articulation review',()=>{
 const f=fixture();f.source.layers[0].objects[2].keys=f.source.layers[0].objects[2].keys.filter(k=>k.frame!==16);
 assert.throws(()=>auditWeaponGeometry(f),/impact lacks authored weapon key/);
});
test('metric forgery and discontinuous key values are rejected',()=>{
 const f=fixture();f.stageMetrics.weaponContact.afterTipGapPx=0;
 assert.throws(()=>auditWeaponGeometry(f),/provenance differs/);
 const g=fixture();g.source.layers[0].objects[2].keys[5].transform.rotation=-180;
 assert.throws(()=>auditWeaponGeometry(g),/rotate together/);
});
async function storedFixture(){
 const root=await mkdtemp(join(tmpdir(),'omega-weapon-m32-'));
 const f=fixture(),files={
  'editable-duel-source.json':f.source,
  'editable-duel-program.json':f.program,
  'native-rig-tracks.json':f.native,
  'native-weapon-tracks.json':f.weaponTrace,
  'duel-stage-metrics.json':f.stageMetrics,
 };
 for(const [name,value] of Object.entries(files))await writeFile(join(root,name),JSON.stringify(value));
 const sourceSha=digest(await readFile(join(root,'editable-duel-source.json')));
 const programSha=digest(await readFile(join(root,'editable-duel-program.json')));
 files['native-rig-tracks.json'].sourceSha256=sourceSha;
 files['native-rig-tracks.json'].programSha256=programSha;
 await writeFile(join(root,'native-rig-tracks.json'),JSON.stringify(files['native-rig-tracks.json']));
 const hashes={};for(const name of Object.keys(files))hashes[name]=digest(await readFile(join(root,name)));
 const video=Buffer.from('EXPLICITLY SYNTHETIC UNIT TEST FIXTURE; NOT A PLAYABLE MP4');
 await writeFile(join(root,'synthetic.mp4'),video);
 const prior={schema:'omega.native-film-proof.v1',kind:'duel',gate:'READY_FOR_VISUAL_REVIEW',releaseDecision:'MANUAL_REVIEW_REQUIRED',
  frameAudit:{frameEvidence:'PNG_SHA256_VERIFIED',stats:{verifiedFrameFiles:100}},
  native:{sourceSha256:hashes['editable-duel-source.json'],programSha256:hashes['editable-duel-program.json']},
  movie:{filename:'synthetic.mp4',sha256:digest(video)}};
 files['native-duel-proof.json']={schema:'videostudio.native-duel-proof.v1',rigTracksSha256:hashes['native-rig-tracks.json'],weaponCoreTraceSha256:hashes['native-weapon-tracks.json']};
 files['omega-native-report.json']=prior;
 for(const key of ['omega-motion-dna','omega-skeleton-dna','omega-physics-brain','omega-impact-dynamics'])files[key+'.json']={status:'MEASURED',qualityDecision:'MANUAL_REVIEW_REQUIRED'};
 for(const [name,value] of Object.entries(files))await writeFile(join(root,name),JSON.stringify(value));
 return {root};
}
test('M3.2 CI adapter verifies linked independent source/rig/weapon hashes',async()=>{
 const {root}=await storedFixture();
 try{
   const r=await auditOwnedWeaponProof(root);
   assert.equal(r.qualityDecision,'MANUAL_REVIEW_REQUIRED');
   assert.equal(r.provenance.nativeFramesVerified,100);
   assert.equal(JSON.parse(await readFile(join(root,'omega-weapon-contact.json'),'utf8')).status,'MEASURED');
 }finally{await rm(root,{recursive:true,force:true});}
});
test('M3.2 CI adapter fails closed on altered native weapon source or altered MP4',async()=>{
 const {root}=await storedFixture();
 try{
  await writeFile(join(root,'native-weapon-tracks.json'),'{}');
  await assert.rejects(auditOwnedWeaponProof(root),/tampered/);
  assert.equal(JSON.parse(await readFile(join(root,'omega-weapon-contact.json'),'utf8')).status,'BLOCKED');
 }finally{await rm(root,{recursive:true,force:true});}
 const g=await storedFixture();
 try{
  await writeFile(join(g.root,'synthetic.mp4'),'tampered media');
  await assert.rejects(auditOwnedWeaponProof(g.root),/MP4 changed/);
 }finally{await rm(g.root,{recursive:true,force:true});}
});