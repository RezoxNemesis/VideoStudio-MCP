// TINKU — a small home robot, about a metre tall: a boxy head with an LED face behind dark glass, a painted-metal
// body with a round chest light (it warms from off to a soft red as he learns to love), two-part arms that move at
// shoulder and elbow, and a wheeled base. Built as a 3D mesh in the film's painted look, like Chippu.
// robot3d(at, o) → [Mesh] for a sequence's `dyn`.
import { Mesh } from '../engine/scene.js';
import { onFace } from './pictures.js';
import { ledFace } from './ledface.js';
const rr = (g, x, y, w, h, r) => { g.beginPath(); g.roundRect(x, y, w, h, r); };

const TEX = new Map(); const canvasOf = (g, w, h) => new (g.canvas.constructor)(w, h);
function faceTex(g, o) {
  const key = `${o.mood}|${Math.round((o.talk ?? 0) * 3)}|${(o.look ?? [0, 0]).map((v) => Math.round(v * 2)).join(',')}|${o.blink ? 1 : 0}`;
  let c = TEX.get(key); if (c) return c; c = canvasOf(g, 512, 400); const q = c.getContext('2d');
  q.fillStyle = '#e9e4d8'; q.fillRect(0, 0, 512, 400);
  q.fillStyle = '#05070b'; rr(q, 44, 52, 424, 296, 64); q.fill();
  const gl = q.createLinearGradient(44, 52, 300, 348); gl.addColorStop(0, 'rgba(255,255,255,0.16)'); gl.addColorStop(0.45, 'rgba(255,255,255,0)'); q.fillStyle = gl; rr(q, 44, 52, 424, 296, 64); q.fill();
  ledFace(q, 256, 200, 230, { mood: o.mood ?? 'happy', talk: o.talk ?? 0, look: o.look, blink: o.blink ? 0.1 : 1 }, 0);
  if (TEX.size > 300) TEX.clear(); TEX.set(key, c); return c;
}
function chestTex(g, love) {
  const key = `c${Math.round(love * 20)}`; let c = TEX.get(key); if (c) return c; c = canvasOf(g, 256, 256); const q = c.getContext('2d');
  q.fillStyle = '#e3ddd0'; q.fillRect(0, 0, 256, 256); const sh = q.createLinearGradient(0, 0, 256, 256); sh.addColorStop(0, 'rgba(255,255,255,0.25)'); sh.addColorStop(1, 'rgba(0,0,0,0.12)'); q.fillStyle = sh; q.fillRect(0, 0, 256, 256);
  if (love > 0.05) { const halo = q.createRadialGradient(128, 120, 30, 128, 120, 120); halo.addColorStop(0, `rgba(255,90,110,${0.5 * love})`); halo.addColorStop(1, 'rgba(255,90,110,0)'); q.fillStyle = halo; q.fillRect(0, 0, 256, 256); }
  q.fillStyle = '#3a3f48'; q.beginPath(); q.arc(128, 120, 60, 0, 7); q.fill();
  const r = Math.round(60 + love * 195), gg = Math.round(70 + love * 20), b = Math.round(80 - love * 40);
  const rg = q.createRadialGradient(128, 120, 4, 128, 120, 50); rg.addColorStop(0, `rgba(${Math.min(255, r + 40)},${gg + 60},${b + 50},1)`); rg.addColorStop(1, `rgba(${r},${gg},${b},1)`);
  q.fillStyle = rg; q.beginPath(); q.arc(128, 120, 48, 0, 7); q.fill(); q.fillStyle = 'rgba(255,255,255,0.35)'; q.beginPath(); q.ellipse(112, 104, 16, 9, -0.6, 0, 7); q.fill();
  q.strokeStyle = 'rgba(0,0,0,0.35)'; q.lineWidth = 3; for (let i = 0; i < 4; i++) { q.beginPath(); q.moveTo(70, 200 + i * 12); q.lineTo(186, 200 + i * 12); q.stroke(); }
  TEX.set(key, c); return c;
}
const rad = (d) => (d * Math.PI) / 180;
const mul = (A, B) => A.map((r) => [0, 1, 2].map((j) => r[0] * B[0][j] + r[1] * B[1][j] + r[2] * B[2][j]));
const Rx = (d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [[1, 0, 0], [0, c, -s], [0, s, c]]; };
const Ry = (d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [[c, 0, s], [0, 1, 0], [-s, 0, c]]; };
const Rz = (d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [[c, -s, 0], [s, c, 0], [0, 0, 1]]; };
const ap = (R, p) => [R[0][0] * p[0] + R[0][1] * p[1] + R[0][2] * p[2], R[1][0] * p[0] + R[1][1] * p[1] + R[1][2] * p[2], R[2][0] * p[0] + R[2][1] * p[1] + R[2][2] * p[2]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
function slab(c, w, h, d, cols, det = {}) {
  const [cx, cy, cz] = c, x0 = cx - w / 2, x1 = cx + w / 2, y0 = cy - h / 2, y1 = cy + h / 2, z0 = cz - d / 2, z1 = cz + d / 2, k = (n) => cols[n] ?? cols.side;
  return [
    { p: [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], c: k('front'), detail: det.front, tag: 'front' },
    { p: [[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]], c: k('back'), tag: 'back' },
    { p: [[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]], c: k('side'), tag: 'right' },
    { p: [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], c: k('side'), tag: 'left' },
    { p: [[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]], c: k('top'), tag: 'top' },
    { p: [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], c: k('bottom') ?? k('side'), tag: 'bottom' },
  ];
}
/** move a part's faces: rotate by R about `pivot` (all in the robot's own frame) */
const turn = (faces, R, pivot) => faces.map((f) => ({ ...f, p: f.p.map((q) => add(ap(R, [q[0] - pivot[0], q[1] - pivot[1], q[2] - pivot[2]]), pivot)) }));
const SHELL = { side: '#e3ddd0', top: '#f1ece2', bottom: '#b9b2a4' }, DARK = { side: '#3a3f48', top: '#4a505a' }, ACC = { side: '#e8623a', top: '#f07a52' };
/**
 * Tinku in the world. at: [x, 0, z] (the floor under him); o: { yaw (deg; 0 faces +z), tilt (head, deg), nod, lean (body, deg),
 * armL / armR: [lift (deg, 0 = hanging, 90 = straight ahead, 180 = up), out (deg, away from the body; negative = inwards),
 * elbow (deg)], mood, talk (0–1), look, blink, love (0–1: the chest light), bob (m), bias }
 */
export function robot3d(at, o = {}) {
  const parts = [];
  // base: a dark wheel housing and two orange wheels
  parts.push(...slab([0, 0.07, 0], 0.4, 0.14, 0.34, DARK));
  for (const sx of [-1, 1]) parts.push(...slab([sx * 0.215, 0.07, 0], 0.04, 0.13, 0.13, ACC));
  const lean = Rx(o.lean ?? 0), hip = [0, 0.15, 0], up = (f) => turn(f, lean, hip), bob = o.bob ?? 0;
  // body: neck post, torso with the chest light, shoulder caps
  const body = [...slab([0, 0.22 + bob, 0], 0.12, 0.1, 0.12, DARK),
    ...slab([0, 0.48 + bob, 0], 0.44, 0.44, 0.3, SHELL, { front: (g, F) => onFace(g, F, chestTex(g, o.love ?? 0), 0, 0, 1, 1, { fit: 'fill', lit: true }) }),
    ...slab([0, 0.735 + bob, 0], 0.1, 0.07, 0.1, DARK)];
  parts.push(...up(body));
  // arms: upper (0.24) and fore (0.22), turned at shoulder then elbow
  for (const [sx, key] of [[-1, 'armR'], [1, 'armL']]) {
    const [lift, out, elbow] = o[key] ?? [8, 4, 10], sh = [sx * 0.265, 0.66 + bob, 0];
    const Rs = mul(Rz(sx * (out ?? 0)), Rx(-(lift ?? 0))), el = add(sh, ap(Rs, [0, -0.24, 0])), Re = mul(Rs, Rx(-(elbow ?? 0)));
    const upper = slab([0, -0.12, 0], 0.08, 0.24, 0.08, SHELL), fore = slab([0, -0.11, 0], 0.07, 0.22, 0.07, SHELL), hand = slab([0, -0.25, 0], 0.09, 0.07, 0.09, ACC), cap = slab([0, 0, 0], 0.11, 0.1, 0.11, DARK);
    const place = (fs, R, p) => fs.map((f) => ({ ...f, p: f.p.map((q) => add(ap(R, q), p)) }));
    parts.push(...up([...place(cap, Rs, sh), ...place(upper, Rs, sh), ...place([...fore, ...hand], Re, el)]));
  }
  // head: a rounded box, the face in front, two little ear lights and an antenna
  const Rh = mul(Rx(o.nod ?? 0), Rz(o.tilt ?? 0)), neck = [0, 0.77 + bob, 0];
  const head = [...slab([0, 0.95 + bob, 0], 0.42, 0.34, 0.34, SHELL, { front: (g, F) => onFace(g, F, faceTex(g, o), 0, 0, 1, 1, { fit: 'fill', lit: false }) }),
    ...slab([-0.225, 0.95 + bob, 0], 0.03, 0.1, 0.1, ACC), ...slab([0.225, 0.95 + bob, 0], 0.03, 0.1, 0.1, ACC),
    ...slab([0, 1.16 + bob, 0], 0.015, 0.09, 0.015, DARK), ...slab([0, 1.215 + bob, 0], 0.04, 0.04, 0.04, { side: o.love > 0.5 ? '#ff6a7a' : '#7affd8' })];
  parts.push(...up(turn(head, Rh, neck)));
  const Rw = Ry(o.yaw ?? 0), faces = parts.map((f) => ({ ...f, p: f.p.map((q) => add(ap(Rw, q), at)) }));
  return [new Mesh(faces, { p: [at[0], at[1] + 0.6, at[2]], bias: o.bias ?? 0 })];
}
