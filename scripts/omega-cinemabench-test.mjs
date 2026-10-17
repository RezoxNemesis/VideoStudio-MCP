import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { deflateSync } from 'node:zlib';
import { auditNativeFrameManifest, verifyFramePngs } from '../src/omega/cinemabench.js';

function manifest(hashes, options = {}) {
  const changedAdjacentFrames = hashes.slice(1).filter((s, i) => s !== hashes[i]).length;
  return {
    schema: 'videostudio.native-duel-proof.v1', width: 1, height: 1, fps: 24,
    frameCount: hashes.length, changedAdjacentFrames,
    frames: hashes.map((sha256, frame) => ({ frame, sha256 })), ...options,
  };
}
function hash(data) { return createHash('sha256').update(data).digest('hex'); }
const fakeHashes = Array.from({ length: 100 }, (_, i) => hash('native PNG ' + i));

// Build valid PNG bytes from pixel data, not arbitrary fake PNG headers.
function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, content) {
  const kind = Buffer.from(type, 'ascii');
  const length = Buffer.alloc(4); length.writeUInt32BE(content.length);
  const body = Buffer.concat([kind, content]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}
function png(gray) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(1, 0); ihdr.writeUInt32BE(1, 4); ihdr[8] = 8; ihdr[9] = 0;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(Buffer.from([0, gray]))), chunk('IEND', Buffer.alloc(0)),
  ]);
}

test('100 distinct native files get technical clearance but never automatic artistic acceptance', () => {
  const report = auditNativeFrameManifest(manifest(fakeHashes));
  assert.equal(report.gate, 'READY_FOR_VISUAL_REVIEW');
  assert.equal(report.releaseDecision, 'MANUAL_REVIEW_REQUIRED');
  assert.equal(report.stats.byteDistinctAdjacentFrames, 99);
  assert.equal(report.stats.longestIdenticalPngRunFrames, 1);
  assert.equal(report.frameEvidence, 'MANIFEST_ONLY');
  assert.ok(report.notEvaluated.some(s => s.includes('camera-only')));
});

test('unmoving frame hashes are blocked, not accepted as cinematic animation', () => {
  const report = auditNativeFrameManifest(manifest(Array(50).fill(fakeHashes[0])));
  assert.equal(report.gate, 'BLOCK');
  assert.ok(report.findings.some(f => f.code === 'NEAR_STATIC'));
});

test('abnormally long exact holds are surfaced for review', () => {
  const hashes = [...fakeHashes.slice(0, 35), ...Array(24).fill(fakeHashes[34]), ...fakeHashes.slice(35, 50)];
  const report = auditNativeFrameManifest(manifest(hashes));
  assert.equal(report.gate, 'READY_FOR_VISUAL_REVIEW');
  assert.ok(report.findings.some(f => f.code === 'LONG_IDENTICAL_HOLD'));
  assert.ok(report.stats.longIdenticalRuns.some(r => r.frameCount >= 24));
});

test('missing/out-of-order frame evidence and dishonest declared adjacency are blocked', () => {
  const wrong = manifest(fakeHashes.slice(0, 4));
  wrong.frames[1].frame = 3;
  assert.equal(auditNativeFrameManifest(wrong).gate, 'BLOCK');
  const wrongClaim = manifest(fakeHashes.slice(0, 4), { changedAdjacentFrames: 1 });
  assert.ok(auditNativeFrameManifest(wrongClaim).findings.some(f => f.code === 'ADJACENCY_MISMATCH'));
  const tooLong = manifest(fakeHashes.slice(0, 4), { frameCount: 10000000 });
  assert.equal(auditNativeFrameManifest(tooLong).gate, 'BLOCK');
});

test('optional wrist proximity rejects a measurable gap but does not claim collision', () => {
  const m = manifest(fakeHashes.slice(0, 4));
  const stageMetrics = { actors: ['Vale', 'Rook'], preparedViews: ['wide', 'POV', 'recovery'], batonWristDistancePx: 83 };
  const fail = auditNativeFrameManifest(m, { stageMetrics, maxContactGapPx: 80 });
  assert.ok(fail.findings.some(f => f.code === 'CONTACT_GAP'));
  const ready = auditNativeFrameManifest(m, { stageMetrics: { ...stageMetrics, batonWristDistancePx: 30 }, maxContactGapPx: 80 });
  assert.equal(ready.gate, 'READY_FOR_VISUAL_REVIEW');
  assert.ok(ready.findings.some(f => f.code === 'CONTACT_IS_NOT_COLLISION'));
});

test('owned PNG proof hashes, header and dimensions are checked; changed file is rejected', async () => {
  const root = await mkdtemp(join(tmpdir(), 'omega-cinemabench-'));
  const framesDir = join(root, 'frames');
  await mkdir(framesDir);
  try {
    const raw = [png(0), png(127), png(255)];
    const expected = manifest(raw.map(hash));
    for (let i = 0; i < raw.length; i++) await writeFile(join(framesDir, 'frame-' + String(i).padStart(4, '0') + '.png'), raw[i]);
    const verified = await verifyFramePngs(expected, framesDir);
    assert.deepEqual(verified, { verified: true, checkedFrames: 3, problem: null });
    assert.equal(auditNativeFrameManifest(expected, { frameVerification: verified }).frameEvidence, 'PNG_SHA256_VERIFIED');
    await writeFile(join(framesDir, 'frame-0001.png'), png(128));
    const failed = await verifyFramePngs(expected, framesDir);
    assert.equal(failed.verified, false);
    assert.equal(auditNativeFrameManifest(expected, { frameVerification: failed }).gate, 'BLOCK');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('forged frame dimensions and unexpected numbered files are rejected', async () => {
  const root = await mkdtemp(join(tmpdir(), 'omega-cinemabench-'));
  try {
    const raw = [png(1), png(2)];
    const valid = manifest(raw.map(hash), { width: 2 });
    for (let i = 0; i < 2; i++) await writeFile(join(root, 'frame-' + String(i).padStart(4, '0') + '.png'), raw[i]);
    assert.match((await verifyFramePngs(valid, root)).problem, /dimensions/);
    await writeFile(join(root, 'frame-0002.png'), png(3));
    assert.match((await verifyFramePngs(manifest(raw.map(hash)), root)).problem, /unexpected/);
  } finally { await rm(root, { recursive: true, force: true }); }
});
