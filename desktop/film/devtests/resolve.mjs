// Dev tool: solve a baked performance's sword hold again with the Stage engine in this working tree — no app needed.
// A bake holds every bone of the body in every rig frame; this puts a bare skeleton into each of those poses, holds the
// sword again where the sword sequence says (fight.js: swordAt + holdAt), and writes the arm, the hand and the sword
// back into the bake. Everything else in the performance stays as it was solved in the app.
//   node devtests/resolve.mjs <film dir> <bake name> <actor id> '<sword sequence json>' --pos x,z --yaw deg [--t0 s] [--t1 s] [--fade s] [--two]
// The first run keeps the app's own bake beside it as <name>.bin.app; --restore puts it back.
import fs from 'node:fs';
import path from 'node:path';
import * as THREE from 'three';
import { Human } from '../../../src/stage/engine/human.js';
import { Actor } from '../../../src/stage/engine/actors.js';
import { swordAt, holdAt } from '../../../src/stage/engine/fight.js';

const args = process.argv.slice(2), flag = (n, d = null) => { const i = args.indexOf(`--${n}`); return i < 0 ? d : (args[i + 1] ?? true); };
const [dir, name, actorId, seqJson] = args;
const bin = path.join(dir, '.vs/bake', `${name}.bin`), keep = `${bin}.app`, meta = JSON.parse(fs.readFileSync(path.join(dir, '.vs/bake', `${name}.json`), 'utf8'));
if (args.includes('--restore')) { fs.copyFileSync(keep, bin); console.log('restored the app\'s bake'); process.exit(0); }
if (!fs.existsSync(keep)) fs.copyFileSync(bin, keep);
const buf = fs.readFileSync(keep), ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), out = new Uint8Array(ab.slice(0));
const A = meta.actors.find((x) => x.id === actorId); if (!A?.rig) throw new Error(`actor ${actorId} has no body in bake ${name}`);
const seq = JSON.parse(seqJson), t0 = +flag('t0', 0), t1 = +flag('t1', 1e9), fade = +flag('fade', 0), two = args.includes('--two');
const [px, pz] = String(flag('pos', '0,0')).split(',').map(Number), yaw = +flag('yaw', 0) * Math.PI / 180;

const bones = A.body.bones, nb = bones.length, names = bones.map((b) => b.name);
const h = new Human({ name: actorId, height: A.body.height, landmarks: {}, look: {}, morphNames: [], parts: [], bones });
const a = new Actor(h, null, actorId); a.props.sword = a.hold(new THREE.Object3D(), 'R');
a.root.position.set(px, 0, pz); a.root.rotation.set(0, yaw, 0); a.root.scale.setScalar(A.body.scale ?? 1); a.root.updateMatrixWorld(true);
const rig = new Float32Array(ab, A.rig.data[0], A.rig.data[1] / 4), rigOut = new Float32Array(out.buffer, A.rig.data[0], A.rig.data[1] / 4);
const data = new Float32Array(ab, A.data[0], A.data[1] / 4), dataOut = new Float32Array(out.buffer, A.data[0], A.data[1] / 4);
const swordMeta = A.props.find((p) => p.name === 'sword'), swordOut = swordMeta ? new Float32Array(out.buffer, swordMeta.data[0], swordMeta.data[1] / 4) : null;
const every = Math.round(meta.fps / A.rig.fps), S = meta.stride, nj = meta.joints.length;

const _p = new THREE.Vector3(), _q = new THREE.Quaternion(), _pq = new THREE.Quaternion(), _pp = new THREE.Vector3(), _m = new THREE.Matrix4(), _s = new THREE.Vector3();
/** the bake's pose of every bone at 60-a-second frame i (between two rig frames: halfway) */
function worldAt(i) {
  const f = i / every, i0 = Math.min(A.rig.frames - 1, Math.floor(f)), i1 = Math.min(A.rig.frames - 1, i0 + 1), u = f - i0, W = new Float32Array(nb * 7);
  for (let b = 0; b < nb; b++) { const o0 = (i0 * nb + b) * 7, o1 = (i1 * nb + b) * 7;
    for (let c = 0; c < 3; c++) W[b * 7 + c] = rig[o0 + c] + (rig[o1 + c] - rig[o0 + c]) * u;
    const q0 = _q.set(rig[o0 + 3], rig[o0 + 4], rig[o0 + 5], rig[o0 + 6]), q1 = _pq.set(rig[o1 + 3], rig[o1 + 4], rig[o1 + 5], rig[o1 + 6]); q0.slerp(q1, u); W[b * 7 + 3] = q0.x; W[b * 7 + 4] = q0.y; W[b * 7 + 5] = q0.z; W[b * 7 + 6] = q0.w; }
  return W;
}
/** put the skeleton into a pose given as every bone's place in the world */
function pose(W) {
  a.root.updateMatrixWorld(true);
  for (let b = 0; b < nb; b++) { const bone = h.B[names[b]], par = bones[b].parent;
    _p.set(W[b * 7], W[b * 7 + 1], W[b * 7 + 2]); _q.set(W[b * 7 + 3], W[b * 7 + 4], W[b * 7 + 5], W[b * 7 + 6]);
    bone.parent.updateWorldMatrix(false, false); bone.parent.matrixWorld.decompose(_pp, _pq, _s);
    bone.position.copy(_p).applyMatrix4(_m.copy(bone.parent.matrixWorld).invert()); bone.quaternion.copy(_pq.invert().multiply(_q)); bone.updateMatrix(); bone.matrixWorld.multiplyMatrices(bone.parent.matrixWorld, bone.matrix); }
  h.root.updateMatrixWorld(true);
}
const wp = (n, v = new THREE.Vector3()) => { const tail = n.endsWith(':tail'), bn = tail ? n.slice(0, -5) : n, b = h.B[bn]; if (tail) { const r = h.rest[bn]; return b.localToWorld(v.copy(r.tail).sub(r.head)); } return v.setFromMatrixPosition(b.matrixWorld); };

// a check before anything is changed: put back into its own pose, the skeleton lies where the bake says
{ const W = worldAt(0); pose(W); let worst = 0; for (let b = 0; b < nb; b++) worst = Math.max(worst, wp(names[b]).distanceTo(_p.set(W[b * 7], W[b * 7 + 1], W[b * 7 + 2]))); if (worst > 1e-3) throw new Error(`the skeleton does not fit the bake (${worst.toFixed(4)} m off): wrong --pos / --yaw?`); }

const k = h.height / 1.8 * a.root.scale.y, arm = (s) => new Set(names.filter((n) => n.endsWith(`.${s}`) && /^(upperarm|lowerarm|wrist|finger|metacarpal)/.test(n)));
const moved = new Set([...arm('R'), ...(two ? arm('L') : [])]);
let solved = 0, worstGrip = 0;
for (let i = 0; i < meta.frames; i++) {
  const t = i / meta.fps, w = Math.min(fade > 0 ? (t - t0) / fade : (t >= t0 ? 1 : 0), fade > 0 ? (t1 - t) / fade : (t <= t1 ? 1 : 0));
  if (w < 1) continue;
  pose(worldAt(i));
  const P = swordAt(t - t0, seq), q = a.root.quaternion, hand = P.h.clone().multiplyScalar(k).applyQuaternion(q).add(a.root.position), d = P.d.clone().applyQuaternion(q).normalize(), e = P.e.clone().applyQuaternion(q);
  holdAt(a, 'R', hand.clone().addScaledVector(d, -0.02 * k), d, e);
  if (two) holdAt(a, 'L', hand.clone().addScaledVector(d, -0.12 * k), d, e, null, null);
  h.root.updateMatrixWorld(true); solved++;
  // the 60-a-second tracks: joints, the few orientations, the sword
  for (let n = 0; n < nj; n++) { const j = meta.joints[n]; if (!moved.has(j.replace(':tail', ''))) continue; wp(j, _p); dataOut.set([_p.x, _p.y, _p.z], i * S + n * 3); }
  meta.quats.forEach((n, x) => { if (!moved.has(n)) return; h.B[n].getWorldQuaternion(_q); dataOut.set([_q.x, _q.y, _q.z, _q.w], i * S + nj * 3 + x * 4); });
  if (swordOut) { const sw = a.props.sword; sw.updateWorldMatrix(true, false); sw.getWorldPosition(_p); sw.getWorldQuaternion(_q); swordOut.set([_p.x, _p.y, _p.z, _q.x, _q.y, _q.z, _q.w], i * 8); }
  // the rig frames: every bone of the arm
  if (i % every === 0) { const o = (i / every) * nb * 7; for (let b = 0; b < nb; b++) { if (!moved.has(names[b])) continue; wp(names[b], _p); h.B[names[b]].getWorldQuaternion(_q); rigOut.set([_p.x, _p.y, _p.z, _q.x, _q.y, _q.z, _q.w], o + b * 7); } }
}
fs.writeFileSync(bin, out);
console.log(`${name}/${actorId}: sword held again in ${solved} of ${meta.frames} frames`);
