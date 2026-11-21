// STAGE — what a director needs on the floor: a lens that frames a subject in a vertical picture, people who sit and
// walk and blink, a mouth that follows a voice, hands that hold a paper (the thing is placed, the hands follow it),
// a camera that rides the car's tremble, words laid over the picture.
import { THREE, V3, lerp, clamp, ease, easeIO, hash } from './clay.js';
import { CAR } from './coach.js';
export { track } from './puppet.js';
export const D = Math.PI / 180;
export const fade = (t, a, b, f = 0.25) => clamp((t - a) / f) * clamp((b - t) / f);
let ASPECT = 9 / 16;
/** the picture's shape (width / height): 9/16 for a vertical film (the default), 16/9 for a wide one */
export const setAspect = (a) => { ASPECT = a; };
/** a lens on a subject: target, the width of the frame there (m), fov, azimuth (0 = from +z, + towards +x), elevation */
export const shoot = (target, o = {}) => { const fov = o.fov ?? 30, d = (o.w ?? 0.6) / (2 * Math.tan(fov * D / 2) * ASPECT), az = (o.az ?? 0) * D, el = (o.el ?? 0) * D;
  return { pos: [target[0] + Math.sin(az) * Math.cos(el) * d, target[1] + Math.sin(el) * d, target[2] + Math.cos(az) * Math.cos(el) * d], target, fov, focus: o.focus ?? target, ap: o.ap ?? 24, roll: o.roll ?? 0 }; };
/** a seated pose at (x, z), facing +z: feet on the floor, hands resting on the thighs */
export const sit = (x, z, o = {}, h = 1) => ({ at: [x, CAR.SEAT + 0.092 * h, z], yaw: 0, lean: [6, 0, 0], head: [0, 8, 0], gaze: [0, -4],
  footL: { w: [x + 0.09 * h, 0.053 * h, z + 0.33 * h], toe: 8 }, footR: { w: [x - 0.085 * h, 0.053 * h, z + 0.31 * h], toe: -6 },
  handL: { p: [0.1 * h, 0.0, 0.15 * h], f: [0, -0.32, 1], n: [0, -1, -0.2], pole: [1, -0.5, -0.5], curl: 0.3, thumb: 0.35 }, handR: { p: [-0.1 * h, 0.0, 0.15 * h], f: [0, -0.32, 1], n: [0, -1, -0.2], pole: [-1, -0.5, -0.5], curl: 0.3, thumb: 0.35 }, ...o });
/** a blink: the lid comes down fast and lifts a little slower */
export const blink = (t, at, open) => { for (const b of at) { const u = (t - b) / 0.22; if (u >= 0 && u < 1) return open * (u < 0.35 ? 1 - ease(u / 0.35) : ease((u - 0.35) / 0.65)); } return open; };
/** the mouth a voice makes at film time T: [curve, open, width, skew, tongue]. L: the line's lip track ({ fps, v } from lips.py), start: when the line begins, base: the mouth at rest */
export const lipAt = (L, start, T, base = [-0.1, 0, 1, 0]) => { if (!L) return base; const f = (T - start) * L.fps, i = Math.floor(f); if (i < 0 || i >= L.v.length - 1) return base; const a = L.v[i], b = L.v[i + 1], u = f - i, o = lerp(a[0], b[0], u), w = lerp(a[1], b[1], u), tg = lerp(a[2], b[2], u), k = clamp(o * 3 + Math.abs(w - 1) * 3);
  return [lerp(base[0], base[0] * 0.3, k), o, lerp(base[2], w, k), 0, tg]; };
export const pose = (p, f, t) => p.set(typeof f === 'function' ? f(t) : f);
/** hands given in the body's frame, turned into places in the world (for someone who sits square, yaw 0): keys of one track must all be of one kind */
export const worldHands = (p) => { for (const k of ['handL', 'handR']) if (p[k] && p[k].p) { const { p: q, ...r } = p[k]; p[k] = { ...r, w: [p.at[0] + q[0], p.at[1] + q[1], p.at[2] + q[2]] }; } return p; };
/** how a sheet of paper (w × h) sits in a hand, in the hand's own frame: it lies on the flat fingers, the thumb on top → { R, L } */
export const holdOf = (w, h) => ({ R: new THREE.Matrix4().makeBasis(V3(0, 1, 0), V3(0, 0, 1), V3(1, 0, 0)).setPosition(0.017, -0.075 - (w / 2 - 0.035), h / 2 - 0.035), L: new THREE.Matrix4().makeBasis(V3(0, -1, 0), V3(0, 0, 1), V3(-1, 0, 0)).setPosition(-0.017, -0.075 - (w / 2 - 0.035), h / 2 - 0.035) });
/** give a thing its place in the world as a matrix (it no longer follows position / rotation) */
export const setM = (o, m) => { o.matrixAutoUpdate = false; o.matrix.copy(m); o.matrixWorldNeedsUpdate = true; };
/** a paper held in the air: centre, the way the writing runs, the way its top points → matrix */
export const paperM = (c, x, y) => { const X = V3(...x).normalize(), Y = V3(...y); Y.addScaledVector(X, -Y.dot(X)).normalize(); return new THREE.Matrix4().makeBasis(X, Y, V3().crossVectors(X, Y)).setPosition(...c); };
/** the hand that holds that paper: wrist, fingers, palm for puppet.set (the paper is placed first; the hand follows it). hold: holdOf(w, h) */
export const handOn = (PM, k, hold, o = {}) => { const H = PM.clone().multiply(hold[k].clone().invert()), e = H.elements, s = k === 'R' ? 1 : -1; return { w: [e[12], e[13], e[14]], f: [-e[4], -e[5], -e[6]], n: [e[0] * s, e[1] * s, e[2] * s], curl: -0.1, thumb: 0.95, ...o }; };


/** a walk along +z: { x, z0, v (m/s), step (m), h (the puppet's size) } → the pose at t. The planted foot stays where it is; the body rises over it */
export function walk(t, o) { const h = o.h, T = o.step / o.v, ph = t / T, n = Math.floor(ph), u = ph - n, sw = n % 2 === 0 ? 'L' : 'R', P = (m) => o.z0 + m * o.step, sgn = sw === 'L' ? 1 : -1;
  const foot = (k) => (k === sw ? { w: [o.x + (k === 'L' ? 1 : -1) * 0.085 * h, 0.053 * h + 0.055 * Math.sin(Math.PI * u), lerp(P(n - 1), P(n + 1), ease(u))], toe: k === 'L' ? 9 : -9, pitch: 22 * Math.sin(Math.PI * u) * (u < 0.6 ? 1 : -0.4) } : { w: [o.x + (k === 'L' ? 1 : -1) * 0.085 * h, 0.053 * h, P(n)], toe: k === 'L' ? 9 : -9 });
  const a = 0.085 * Math.cos(Math.PI * u) * sgn; return { at: [o.x - sgn * 0.016 * Math.sin(Math.PI * u), 0.64 * h - 0.024 - 0.012 * Math.cos(2 * Math.PI * u), o.z0 + (ph - 0.5) * o.step], yaw: 0, lean: [5, sgn * 3.2 * Math.sin(Math.PI * u), sgn * -6 * Math.cos(Math.PI * u)], head: [0, 2, sgn * 1.5 * Math.sin(Math.PI * u)],
    footL: foot('L'), footR: foot('R'), handL: { p: [0.215 * h, -0.02, 0.04 + a], f: [0, -1, 0.25], n: [-1, 0, 0.1], curl: 0.4 }, handR: { p: [-0.215 * h, -0.02, 0.04 - a], f: [0, -1, 0.25], n: [1, 0, 0.1], curl: 0.4 } }; }

/** the car is never still: the camera rides its tremble (a few millimetres) and its slow roll */
export const ride = (cam, T, k = 1) => { const a = 0.0016 * k, n = (f, p) => Math.sin(T * f + p) * Math.sin(T * f * 0.37 + p * 2.1); const dx = a * n(31, 0.3), dy = a * 1.4 * n(37, 1.7) + a * 2.2 * Math.sin(T * 3.9) * Math.max(0, Math.sin(T * 0.62)) ** 6, dz = a * n(23, 4.1); return { ...cam, pos: [cam.pos[0] + dx, cam.pos[1] + dy, cam.pos[2] + dz], target: [cam.target[0] + dx * 0.4, cam.target[1] + dy * 0.5, cam.target[2] + dz * 0.4], roll: (cam.roll ?? 0) + 0.1 * k * Math.sin(T * 1.9) + 0.05 * k * n(17, 2.2) }; };

/** words on the overlay: centred lines at height y (0…1), px their size on a 1080-wide frame; o: { w (weight), font, c, a, edge, stroke, x } */
export function words(g, W, H, txt, y, px, o = {}) { const k = W / 1080; g.save(); g.font = `${o.w ?? 700} ${px * k}px ${o.font ?? 'Poppins'}`; g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = o.a ?? 1;
  txt.split('\n').forEach((ln, i, all) => { const yy = y * H + (i - (all.length - 1) / 2) * px * k * 1.28; g.lineJoin = 'round'; g.lineWidth = (o.edge ?? 9) * k; g.strokeStyle = o.stroke ?? 'rgba(10,10,16,0.82)'; g.strokeText(ln, W / 2 + (o.x ?? 0) * W, yy); g.fillStyle = o.c ?? '#fffaf0'; g.fillText(ln, W / 2 + (o.x ?? 0) * W, yy); }); g.restore(); }
