import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {ProjectMetadataMirror,normalizeMetadataGraph} from '../src/project-metadata-mirror.js';

if(!globalThis.crypto)globalThis.crypto=webcrypto;
function fixture(){
  const values=new Map();
  const storage={async get(key){return structuredClone(values.get(key));},
    async put(key,value){values.set(key,structuredClone(value));},async delete(key){values.delete(key);}};
  return{mirror:new ProjectMetadataMirror(storage,'owner-device'),values};
}
function graph(){return{id:'project-123',revision:1,name:'Project',assets:[{id:'asset-123',name:'video.mp4',
  mime:'video/mp4',durationMs:10000,sizeBytes:500,uri:'file:///private/video.mp4'}],
  tracks:[{id:'track-video',name:'Video',type:'video',order:0}],
  clips:[{id:'clip-123',assetId:'asset-123',trackId:'track-video',startMs:0,inMs:0,outMs:5000,speed:1,volume:1,
    effects:{uri:'file:///private/layer.png',foregroundUri:'content://private/layer',
      imageUrl:'https://files.example.com/signed?token=private',apiKey:'private',scale:1}}]};}
const sync=()=>({projectId:'project-123',projectGraph:graph(),sourceRevision:1,expectedMirrorRevision:0,enabled:true});

test('metadata sync explicitly opts in and strips local media locators without claiming an executor',async()=>{
  const {mirror}=fixture();
  await assert.rejects(mirror.sync({...sync(),enabled:false}),/opt-in/);
  const result=await mirror.sync(sync());
  assert.equal(result.enabled,true);
  assert.equal(result.mirrorRevision,1);
  assert.equal(result.executorAvailable,false);
  assert.equal(result.mediaUploaded,false);
  assert.equal(result.graph.assets[0].mediaAvailability,'metadata_only');
  assert.equal(Object.hasOwn(result.graph.assets[0],'uri'),false);
  assert.equal(Object.hasOwn(result.graph.clips[0].effects,'uri'),false);
  assert.deepEqual(result.graph.clips[0].effects,{scale:1});
});

test('remote metadata cannot manufacture native rig, path or cel provenance',()=>{
 for(const key of ['rig2d','motionPath','celExposure']){
  const authored=graph();authored.clips[0].effects[key]={version:1};
  assert.throws(()=>normalizeMetadataGraph('project-123',authored,1),/shared native animation tools/);
 }
});

test('offline metadata edit returns its changed graph and monotonic revision with audit',async()=>{
  const {mirror}=fixture();await mirror.sync(sync());
  const result=await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'patch_clip',
    parameters:{clipId:'clip-123',title:'Changed while native offline'}});
  assert.equal(result.mirrorRevision,2);
  assert.equal(result.sourceRevision,1);
  assert.equal(result.dirty,true);
  assert.equal(result.graph.clips[0].title,'Changed while native offline');
  assert.equal(result.audit[0].operation,'patch_clip');
  assert.equal(result.nativeAutoReconciliation,false);
});

test('stale mirror edit retains a conflict and preserves current graph',async()=>{
  const {mirror}=fixture();await mirror.sync(sync());
  await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'patch_clip',parameters:{clipId:'clip-123',title:'First'}});
  const conflict=await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'delete_clip',parameters:{clipId:'clip-123'}});
  assert.equal(conflict.conflict,true);
  assert.equal(conflict.graph.clips[0].title,'First');
  assert.equal((await mirror.get('project-123')).conflicts.length,1);
});

test('unsynced asset and overlapping insertion cannot mutate mirror',async()=>{
  const {mirror}=fixture();await mirror.sync(sync());
  await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'insert_clip',
    parameters:{assetId:'not-synced',trackId:'track-video',startMs:5000,outMs:5000}}),/explicitly synced/);
  await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'insert_clip',
    parameters:{assetId:'asset-123',trackId:'track-video',startMs:2500,outMs:5000}}),/overlap/);
  assert.equal((await mirror.get('project-123')).mirrorRevision,1);
});

test('changed native revision cannot silently replace unreconciled offline graph',async()=>{
  const {mirror}=fixture();await mirror.sync(sync());
  await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'patch_clip',parameters:{clipId:'clip-123',title:'Offline'}});
  const native=graph();native.revision=2;native.clips[0].title='Native';
  const result=await mirror.sync({...sync(),sourceRevision:2,expectedMirrorRevision:2,projectGraph:native});
  assert.equal(result.conflict,true);
  assert.equal(result.graph.clips[0].title,'Offline');
  const snapshots=(await mirror.get('project-123')).conflicts;
  assert.equal(snapshots[0].currentGraph.clips[0].title,'Offline');
  assert.equal(snapshots[0].incoming.graph.clips[0].title,'Native');
  const kept=await mirror.reconcile({projectId:'project-123',projectGraph:native,sourceRevision:2,
    expectedMirrorRevision:2,baseSourceRevision:1,resolution:'keep_native'});
  assert.equal(kept.graph.clips[0].title,'Native');
  assert.equal(kept.conflicts[1].currentGraph.clips[0].title,'Offline');
});

test('acknowledging mirror requires exact native readback at a newer native revision',async()=>{
  const {mirror}=fixture();await mirror.sync(sync());
  const edited=await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'patch_clip',parameters:{clipId:'clip-123',title:'Offline'}});
  const wrong=graph();wrong.revision=2;
  const conflict=await mirror.reconcile({projectId:'project-123',expectedMirrorRevision:2,baseSourceRevision:1,
    sourceRevision:2,projectGraph:wrong,resolution:'acknowledge_mirror'});
  assert.equal(conflict.conflict,true);
  const correct={...edited.graph,revision:2};
  const result=await mirror.reconcile({projectId:'project-123',expectedMirrorRevision:2,baseSourceRevision:1,
    sourceRevision:2,projectGraph:correct,resolution:'acknowledge_mirror'});
  assert.equal(result.dirty,false);
  assert.equal(result.sourceRevision,2);
  assert.equal(result.mirrorRevision,3);
});

test('authoring limits reject text truncation and canonical equality ignores object key order',async()=>{
 const {mirror}=fixture();const initial=sync();
 initial.projectGraph.clips[0].title='  exact title\n';
 initial.projectGraph.clips[0].effects={brightness:.2,transform:{x:0,scale:1}};
 await mirror.sync(initial);
 const same=structuredClone(initial);
 same.expectedMirrorRevision=1;
 same.projectGraph.clips[0].effects={transform:{scale:1,x:0},brightness:.2};
 assert.equal((await mirror.sync(same)).mirrorRevision,1);
 const noOp=await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'patch_clip',
  parameters:{clipId:'clip-123',title:'  exact title\n'}});
 assert.equal(noOp.mirrorRevision,1);assert.equal(noOp.dirty,false);
 const invalid=graph();invalid.clips[0].title='x'.repeat(1001);
 assert.throws(()=>normalizeMetadataGraph('project-123',invalid),/Clip title/);
});

test('edit then revert can acknowledge exact native no-op without inventing a revision',async()=>{
 const {mirror}=fixture();await mirror.sync(sync());
 await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'patch_clip',parameters:{clipId:'clip-123',title:'Changed'}});
 await mirror.edit({projectId:'project-123',expectedMirrorRevision:2,operation:'patch_clip',parameters:{clipId:'clip-123',title:''}});
 const result=await mirror.reconcile({projectId:'project-123',expectedMirrorRevision:3,baseSourceRevision:1,
  sourceRevision:1,projectGraph:graph(),resolution:'acknowledge_mirror'});
 assert.equal(result.dirty,false);assert.equal(result.sourceRevision,1);
 assert.equal(result.audit[0].noOpAcknowledgement,true);
});

test('graph and history storage are bounded independently',async()=>{
  const {mirror,values}=fixture();await mirror.sync(sync());
  for(let i=0;i<45;i++)await mirror.edit({projectId:'project-123',expectedMirrorRevision:i+1,
    operation:'patch_clip',parameters:{clipId:'clip-123',title:'title '+i}});
  const result=await mirror.get('project-123');
  assert.equal(result.audit.length,40);
  assert.equal(Array.from(values.keys()).filter(key=>key.includes(':audit:')&&!key.endsWith(':index')).length,40);
  const huge=graph();huge.clips[0].effects={items:Array.from({length:128},()=> 'x'.repeat(2048))};
  assert.throws(()=>normalizeMetadataGraph('project-123',huge),/64 KB/);
});

test('conflict snapshots prune both separately stored graph candidates and reject ambiguous IDs',async()=>{
  const {mirror,values}=fixture();await mirror.sync(sync());
  for(let i=0;i<12;i++){
    const native=graph();native.clips[0].title='conflicting '+i;
    await mirror.sync({...sync(),expectedMirrorRevision:0,projectGraph:native});
  }
  assert.equal((await mirror.get('project-123')).conflicts.length,8);
  assert.equal(Array.from(values.keys()).filter(key=>key.endsWith(':current-graph')).length,8);
  assert.equal(Array.from(values.keys()).filter(key=>key.endsWith(':incoming-graph')).length,8);
  for(const value of values.values())assert.ok(Buffer.byteLength(JSON.stringify(value))<128*1024);
  const invalid=graph();invalid.assets[0].id='x'.repeat(181);
  assert.throws(()=>normalizeMetadataGraph('project-123',invalid),/Stable object ID/);
});

test('unsupported cloud execution and revoked projects cannot receive metadata edits',async()=>{
  const {mirror}=fixture();await mirror.sync(sync());
  await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'render',parameters:{}}),/no executor/);
  await mirror.revoke({projectId:'project-123',expectedMirrorRevision:1});
  await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:2,operation:'delete_clip',parameters:{clipId:'clip-123'}}),/not opted in/);
});

test('explicit opt-in can re-enable a clean revoked mirror without inventing a native revision',async()=>{
  const {mirror}=fixture();await mirror.sync(sync());
  await mirror.revoke({projectId:'project-123',expectedMirrorRevision:1});
  const result=await mirror.sync({...sync(),expectedMirrorRevision:2});
  assert.equal(result.enabled,true);assert.equal(result.mirrorRevision,3);
  assert.equal(result.sourceRevision,1);assert.equal(result.dirty,false);
  assert.equal(result.audit[0].operation,'sync');
});

test('native track families and full speed range round-trip without changing existing track types',()=>{
  for(const type of ['audio_music','audio_dialogue','audio_sfx','music','dialogue','sfx','voiceover','voice_over']){
    const input=graph();input.tracks[0].type=type;input.assets[0].hasAudio=true;
    input.clips[0].speed=.1;input.clips[0].outMs=1000;
    const normalized=normalizeMetadataGraph('project-123',input);
    assert.equal(normalized.tracks[0].type,type);
    assert.equal(normalized.clips[0].speed,Math.fround(.1));
    input.assets[0].hasAudio=false;
    assert.throws(()=>normalizeMetadataGraph('project-123',input),/compatible tracks/);
  }
  for(const type of ['title','subtitle','graphics','adjustment']){
    const input=graph();input.tracks[0].type=type;input.clips[0].speed=16;
    const normalized=normalizeMetadataGraph('project-123',input);
    assert.equal(normalized.tracks[0].type,type);assert.equal(normalized.clips[0].speed,16);
  }
});

test('metadata IDs and effect numbers retain precise identity across native exchange',()=>{
 const input=graph();input.assets[0].id=' asset-123 ';
 assert.throws(()=>normalizeMetadataGraph('project-123',input),/Stable object ID/);
 const imprecise=graph();imprecise.clips[0].effects={unknownCounter:Number.MAX_SAFE_INTEGER+1};
 assert.throws(()=>normalizeMetadataGraph('project-123',imprecise),/safe precision/);
});

test('linked A/V metadata round-trips but shared timing, membership and audio detach edits stay native',async()=>{
 const {mirror}=fixture();const input=sync();
 input.projectGraph.assets[0].hasAudio=true;
 input.projectGraph.tracks.push({id:'track-audio',name:'Audio',type:'audio_dialogue',order:1});
 input.projectGraph.clips[0].linkGroupId='link-av';
 input.projectGraph.clips[0].effects={audioDetached:true,audioExtractionDetached:true};
 input.projectGraph.clips.push({...input.projectGraph.clips[0],id:'clip-audio',trackId:'track-audio',volume:1,effects:{}});
 const synced=await mirror.sync(input);
 assert.equal(synced.graph.clips[0].linkGroupId,'link-av');
 assert.equal(synced.graph.clips[0].effects.audioDetached,true);
 assert.equal(synced.graph.clips[0].effects.audioExtractionDetached,true);
 for(const parameters of [{startMs:10},{linkGroupId:''},{effects:{}},{speed:2},
  {effects:{audioDetached:true}},{effects:{audioDetached:true,audioExtractionDetached:false}}])
  await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:1,
   operation:'patch_clip',parameters:{clipId:'clip-123',...parameters}}),/linked|linkage|detached|timing/i);
 await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:1,
  operation:'delete_clip',parameters:{clipId:'clip-audio'}}),/Linked clip deletion/);
 const titled=await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,
  operation:'patch_clip',parameters:{clipId:'clip-123',title:'New caption'}});
 assert.equal(titled.graph.clips[0].linkGroupId,'link-av');
 assert.equal(titled.graph.clips[0].effects.audioDetached,true);
 assert.equal(titled.graph.clips[0].effects.audioExtractionDetached,true);
});

test('metadata cannot manufacture, remove or coerce unlinked extraction provenance',async()=>{
 const {mirror}=fixture();const input=sync();
 input.projectGraph.clips[0].effects={audioDetached:true,audioExtractionDetached:true};
 await mirror.sync(input);
 await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:1,
  operation:'delete_clip',parameters:{clipId:'clip-123'}}),/provenance deletion/);
 await assert.rejects(mirror.edit({projectId:'project-123',expectedMirrorRevision:1,
  operation:'insert_clip',parameters:{clipId:'clip-new',assetId:'asset-123',trackId:'track-video',startMs:5000,
   inMs:0,outMs:1000,effects:{audioExtractionDetached:false}}}),/cannot manufacture/);
 for(const key of ['audioDetached','audioExtractionDetached'])for(const value of ['true',0,null]){
  const invalid=graph();invalid.clips[0].effects={[key]:value};
  assert.throws(()=>normalizeMetadataGraph('project-123',invalid),/boolean metadata/);
 }
 const inconsistent=graph();inconsistent.clips[0].effects={audioDetached:false,audioExtractionDetached:true};
 assert.throws(()=>normalizeMetadataGraph('project-123',inconsistent),/requires detached audio/);
});

test('explicit keep-native can resolve a dirty mirror at unchanged actual native revision with conflict evidence',async()=>{
 const {mirror}=fixture();await mirror.sync(sync());
 await mirror.edit({projectId:'project-123',expectedMirrorRevision:1,operation:'patch_clip',
  parameters:{clipId:'clip-123',title:'Offline displaced edit'}});
 const result=await mirror.reconcile({projectId:'project-123',expectedMirrorRevision:2,
  baseSourceRevision:1,sourceRevision:1,projectGraph:graph(),resolution:'keep_native'});
 assert.equal(result.sourceRevision,1);assert.equal(result.mirrorRevision,3);
 assert.equal(result.dirty,false);assert.equal(result.graph.clips[0].title,'');
 assert.equal(result.conflicts[0].currentGraph.clips[0].title,'Offline displaced edit');
 assert.equal(result.audit[0].resolution,'keep_native');
});

test('explicit equal-source keep-native permits identical clean readback and rejects unrevisioned changes',async()=>{
 const {mirror}=fixture();await mirror.sync(sync());
 const settled=await mirror.reconcile({projectId:'project-123',expectedMirrorRevision:1,
  baseSourceRevision:1,sourceRevision:1,projectGraph:graph(),resolution:'keep_native'});
 assert.equal(settled.sourceRevision,1);assert.equal(settled.mirrorRevision,2);
 assert.equal(settled.audit[0].resolution,'keep_native');
 const changed=graph();changed.clips[0].title='Changed without native revision';
 const refused=await mirror.reconcile({projectId:'project-123',expectedMirrorRevision:2,
  baseSourceRevision:1,sourceRevision:1,projectGraph:changed,resolution:'keep_native'});
 assert.equal(refused.conflict,true);assert.equal(refused.mirrorRevision,2);
 assert.equal(refused.graph.clips[0].title,'');
});
