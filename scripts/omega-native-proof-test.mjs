import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { deflateSync } from 'node:zlib';
import { auditNativeFilmProof } from './omega-native-proof.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const b of bytes) {
    crc ^= b;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(name, bytes) {
  const label = Buffer.from(name, 'ascii');
  const header = Buffer.alloc(4); header.writeUInt32BE(bytes.length);
  const payload = Buffer.concat([label, bytes]);
  const checksum = Buffer.alloc(4); checksum.writeUInt32BE(crc32(payload));
  return Buffer.concat([header, payload, checksum]);
}
function png(gray) {
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(1, 0); ihdr.writeUInt32BE(1, 4); ihdr[8] = 8;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(Buffer.from([0, gray]))), chunk('IEND', Buffer.alloc(0))]);
}
async function fixture(kind) {
  const root = await mkdtemp(join(tmpdir(), 'omega-native-ci-'));
  await mkdir(join(root, 'frames'));
  const frames = [png(9), png(17), png(30)];
  for (let i = 0; i < frames.length; i++)
    await writeFile(join(root, `frames/frame-${String(i).padStart(4, '0')}.png`), frames[i]);
  const source = Buffer.from('Synthetic sample only; not an actual Android drawing.');
  const sourceFile = kind === 'story' ? 'editable-original-source.json' : 'editable-duel-source.json';
  await writeFile(join(root, sourceFile), source);
  const program = Buffer.from('Synthetic original pose program');
  if (kind === 'duel') {
    await writeFile(join(root, 'editable-duel-program.json'), program);
    await writeFile(join(root, 'duel-stage-metrics.json'), JSON.stringify({
      actors: ['Vale','Rook'], preparedViews: ['wide','POV','recovery'], batonWristDistancePx: 12,
    }));
  }
  const hashes = frames.map(sha);
  const native = kind === 'story' ? {
    frameCount: 3, width: 1, height: 1, fps: 24,
    sourceSha256: sha(source), changedAdjacentFrames: 1, // Sampled-pixel basis, NOT byte hashes.
    samples: [{frame:0,pngSha256:hashes[0]},{frame:2,pngSha256:hashes[2]}],
  } : {
    schema:'videostudio.native-duel-proof.v1',
    frameCount: 3, width: 1, height: 1, fps: 24,
    changedAdjacentFrames: 1, frames: hashes.map((sha256,frame)=>({frame,sha256})),
  };
  await writeFile(join(root, kind === 'story' ? 'native-frame-proof.json' : 'native-duel-proof.json'), JSON.stringify(native));
  const movie = Buffer.from('SYNTHETIC MOCK VIDEO — not a valid MP4');
  await writeFile(join(root, 'fixture.mp4'), movie);
  const probe = { streams: [{ width:1,height:1,nb_read_frames:'3',r_frame_rate:'24/1' }],format:{duration:'0.125',size:String(movie.length)} };
  const encoded = kind === 'story' ? {
    schema: 'videostudio.story-proof.v1', video:'fixture.mp4',sha256:sha(movie),bytes:movie.length,
    frameCount:3,fps:24,durationSeconds:0.125,sourcePngsVerified:3,
    sourceDocumentSha256:sha(source),videoProbe:probe,
  } : {
    schema: 'videostudio.two-actor-visual-proof.v1', video:'fixture.mp4',sha256:sha(movie),fileBytes:movie.length,
    nativeFrameCount:3,fps:24,durationSeconds:0.125,nativeFramesVerified:3,
    inputSourceSha256:sha(source),inputProgramSha256:sha(program),videoProbe:probe,
  };
  await writeFile(join(root, 'mp4-verified.json'), JSON.stringify(encoded));
  return {root,native,encoded,hashes};
}
async function update(root, name, value) { await writeFile(join(root,name), JSON.stringify(value)); }
async function assertBlocked(root, kind) {
  await assert.rejects(auditNativeFilmProof(root,kind), /OMEGA_NATIVE_PROOF/);
  const failure = JSON.parse(await readFile(join(root,'omega-native-report.json'),'utf8'));
  assert.equal(failure.gate, 'BLOCK');
  assert.equal(failure.releaseDecision, 'BLOCK');
}

test('story adapter normalizes sparse native hashes and preserves actual full PNG evidence', async () => {
  const {root} = await fixture('story');
  try {
    const result=await auditNativeFilmProof(root,'story',{sourceCommit:'a'.repeat(40)});
    assert.equal(result.gate,'READY_FOR_VISUAL_REVIEW');
    assert.equal(result.releaseDecision,'MANUAL_REVIEW_REQUIRED');
    assert.equal(result.frameAudit.frameEvidence,'PNG_SHA256_VERIFIED');
    assert.equal(result.native.canonicalByteChangedAdjacentFrames,2);
    assert.equal(result.native.originalNativeSampledPixelChanges,1);
    assert.equal(result.native.originalSampledHashesChecked,2);
    assert.equal(result.sourceRevision.gitCommitSha,'a'.repeat(40));
    assert.equal(result.sourceRevision.ownerDeviceProjectRevision,null);
    const normalized=JSON.parse(await readFile(join(root,'omega-frame-manifest.json'),'utf8'));
    assert.equal(normalized.frames.length,3);
    assert.equal(normalized.changedAdjacentFrames,2);
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('duel adapter independently checks native full manifest, 2 actors and bounded contact gap', async () => {
  const {root}=await fixture('duel');
  try {
    const result=await auditNativeFilmProof(root,'duel');
    assert.equal(result.gate,'READY_FOR_VISUAL_REVIEW');
    assert.equal(result.native.originalSampledHashesChecked,3);
    assert.equal(result.frameAudit.reportedStageMetrics.wristGapPx,12);
    assert.equal(result.frameAudit.frameEvidence,'PNG_SHA256_VERIFIED');
    assert.ok(result.notEvaluated.some(value=>value.includes('artistry')));
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('native story original sample-hash tampering fails closed and keeps frames', async () => {
  const {root,native}=await fixture('story');
  try {
    native.samples[0].pngSha256='b'.repeat(64);
    await update(root,'native-frame-proof.json',native);
    await assertBlocked(root,'story');
    assert.ok((await readFile(join(root,'frames/frame-0000.png'))).length>40);
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('duel original frame manifest tampering fails closed',async()=>{
  const {root,native}=await fixture('duel');
  try {
    native.frames[1].sha256='0'.repeat(64);
    await update(root,'native-duel-proof.json',native);
    await assertBlocked(root,'duel');
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('MP4 receipt cannot claim another decoded frame count',async()=>{
  const {root,encoded}=await fixture('story');
  try {
    encoded.videoProbe.streams[0].nb_read_frames='12';
    await update(root,'mp4-verified.json',encoded);
    await assertBlocked(root,'story');
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('source sketch digest mismatch blocks provenance',async()=>{
  const {root}=await fixture('story');
  try {
    await writeFile(join(root,'editable-original-source.json'),'Tampered source');
    await assertBlocked(root,'story');
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('film receipt blocks incorrect movie hash and path traversal',async()=>{
  const {root,encoded}=await fixture('duel');
  try {
    encoded.sha256='f'.repeat(64);
    await update(root,'mp4-verified.json',encoded);
    await assertBlocked(root,'duel');
    encoded.sha256=sha(Buffer.from('SYNTHETIC MOCK VIDEO — not a valid MP4'));
    encoded.video='../fixture.mp4';
    await update(root,'mp4-verified.json',encoded);
    await assertBlocked(root,'duel');
  } finally { await rm(root,{recursive:true,force:true}); }
});

test('missing rendered PNG blocks without calling it a completed film',async()=>{
  const {root}=await fixture('story');
  try {
    await rm(join(root,'frames/frame-0002.png'));
    await assertBlocked(root,'story');
  } finally { await rm(root,{recursive:true,force:true}); }
});
