// Shot kit: dialogue lip-sync, a one-call "perform" for actors, and small camera/timing helpers shared by every scene.
import { THREE, V3, clamp, lerp, ease, seg, wob, smooth } from './core.js';
import { relax, ik2 } from './actors.js';
export { V3, clamp, lerp, ease, seg, wob, smooth, relax, ik2 };
const _lv = new THREE.Vector3(), _lq = new THREE.Quaternion(), _lx = new THREE.Vector3(1, 0, 0);
const FACE0 = { open: 0, shape: 'M', blink: 0, smile: 0, sad: 0, anger: 0, fear: 0, surprise: 0, squint: 0, browUp: 0, browDown: 0, pain: 0, purse: 0, sneer: 0, wide: 0 };
/** pose, face, gaze and simulate one actor for this frame.
 *  s: { clip, ct, o, pre(a), post(a), relax, face:{}, gaze: V3, head: 0..1, eyes, ground: y|fn, grip:'R'|'L'|'both', fist, noStep } */
export function perform(a, t, s = {}) {
  const h = a.h; h.resetPose(); if (s.relax !== false && !s.clip) relax(h); if (s.pre) s.pre(a, t);
  if (s.clip) { const o = s.o || {}; if (o.inPlace && !o.align && o.face === undefined) o.face = true; a.mo.apply(s.clip, s.ct ?? t, o); if (s.blend) { for (const b of s.blend) a.mo.apply(b.clip, b.ct ?? t, { ...(b.o || {}), w: b.w }); } if (!s.fingersOff) { h.fingers('L', s.curlL ?? 0.3, 0.25); h.fingers('R', s.curlR ?? 0.3, 0.25); } }
  if (s.clip && s.level !== 0) { h.root.updateMatrixWorld(true); const f = _lv.set(0, 0, 1).applyQuaternion(h.B.head.getWorldQuaternion(_lq)); const ang = Math.asin(clamp(f.y, -1, 1)) * (s.level ?? 0.85); h.B.neck01.quaternion.multiply(_lq.setFromAxisAngle(_lx, ang * 0.5)); h.B.head.quaternion.multiply(_lq.setFromAxisAngle(_lx, ang * 0.5)); }
  if (s.grip === 'R' || s.grip === 'both') a.grip('R'); if (s.grip === 'L' || s.grip === 'both') a.grip('L');
  if (s.post) s.post(a, t);
  if (s.ground !== undefined && s.ground !== null) { const gy = typeof s.ground === 'function' ? s.ground(a) : s.ground; a.mo.groundFix(gy, !!s.snap); }
  const mouthOpen = h.face.open, mouthShape = h.face.shape; Object.assign(h.face, FACE0, h.look.rest || {}, s.face || {}); h.face.open = Math.max(mouthOpen, (s.face && s.face.open) || 0); h.face.shape = mouthOpen > 0.02 ? mouthShape : ((s.face && s.face.shape) || 'A');
  // breathing: a little chest motion so nobody is a statue
  if (s.breath !== false) { const b = Math.sin(t * (s.breathRate ?? 1.5) + h.height * 3) * (s.breath ?? 1); h.B.spine02.quaternion.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), -0.012 * b)); h.B.spine04.quaternion.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), 0.006 * b)); }
  h.applyFace(t + (s.blinkShift || 0)); h.root.updateMatrixWorld(true);
  if (s.gaze) h.lookAt(s.gaze, s.head ?? 0.3, s.eyes ?? 1); else if (s.eyeDart !== false) h.eyesRot(wob(t, h.height * 7, 0.7) * 5, wob(t, h.height * 9, 0.6) * 3 - 2);
  if (s.after) s.after(a, t);
  if (!s.noStep) a.step(t);
}
/** place an actor's root group on a straight walk: start (V3), yaw heading, clip loop gives the stride */
export function walk(a, t, s) { const o = { loop: s.loop, align: s.loop, ...(s.o || {}) }; a.place(s.from.x, s.from.y, s.from.z, s.yaw, s.scale ?? 1); perform(a, t, { ...s, clip: s.clip, ct: (s.t0 ?? s.loop[0]) + t * (s.rate ?? 1), o }); }
/** eye point of an actor in world space */
export function eyePos(a, out = new THREE.Vector3()) { const h = a.h; h.root.updateMatrixWorld(true); return out.set(0, h.lm.eyeL[1] - h.rest.head.head.y, h.lm.eyeL[2] - h.rest.head.head.z).applyMatrix4(h.B.head.matrixWorld); }
export function headPos(a, out = new THREE.Vector3()) { return a.h.wpos('head', out); }
export const mix3 = (a, b, k) => new THREE.Vector3().lerpVectors(a, b, k);
/** keyframe helper: kf(t, [[t0, v0], [t1, v1], ...], easeFn) for numbers or Vector3 */
export function kf(t, keys, e = ease.io) { if (t <= keys[0][0]) return keys[0][1].clone ? keys[0][1].clone() : keys[0][1]; for (let i = 0; i < keys.length - 1; i++) { const [t0, v0] = keys[i], [t1, v1] = keys[i + 1]; if (t <= t1) { const k = e(clamp((t - t0) / (t1 - t0))); return v0.clone ? v0.clone().lerp(v1, k) : lerp(v0, v1, k); } } const l = keys[keys.length - 1][1]; return l.clone ? l.clone() : l; }
/** fade in/out in a shot of length dur */
export function fades(st, t, dur, fin = 0, fout = 0, color = null) { let f = 0; if (fin > 0) f = Math.max(f, 1 - clamp(t / fin)); if (fout > 0) f = Math.max(f, clamp((t - (dur - fout)) / fout)); st.L.fade = f; if (color) st.L.fadeColor.setRGB(...color); }
/** impact frame: white flash + invert pulse + shake amount at time ti */
export function impact(st, t, ti, strength = 1) { const d = t - ti; if (d < 0 || d > 0.35) return 0; const k = Math.exp(-d * 14) * strength; st.L.flash = Math.max(st.L.flash, k * 0.5 * (d < 0.05 ? 1.6 : 1)); if (d < 1 / 24 + 1e-3) st.L.invert = Math.max(st.L.invert, 0.85 * strength); st.L.ca = Math.max(st.L.ca, k * 0.06); return k; }
/** sit astride a horse, hands forward on the reins */
export function ridePose(a, lean = 0) { const h = a.h; h.aim('upperleg01.L', V3(0.52, -0.80, 0.30), 'lowerleg01.L').aim('upperleg01.R', V3(-0.52, -0.80, 0.30), 'lowerleg01.R').aim('lowerleg01.L', V3(0.10, -1, -0.28), 'foot.L').aim('lowerleg01.R', V3(-0.10, -1, -0.28), 'foot.R');
  h.aim('upperarm01.L', V3(0.14, -0.9, 0.38), 'lowerarm01.L').aim('lowerarm01.L', V3(-0.25, -0.25, 1), 'wrist.L'); h.fingers('L', 0.85, 0.6);
  if (lean) h.B.spine04.quaternion.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), lean)); }
export function reinR(a) { const h = a.h; h.aim('upperarm01.R', V3(-0.14, -0.9, 0.38), 'lowerarm01.R').aim('lowerarm01.R', V3(0.25, -0.25, 1), 'wrist.R'); h.fingers('R', 0.85, 0.6); }
/** put a rider on a horse's saddle (call after the horse is posed) */
export function seat(a, horse, lift = 0.1) { horse.updateMatrixWorld(true); const p = horse.localToWorld(horse.userData.seat.clone()); a.place(p.x, p.y - a.h.rest.root.head.y * a.root.scale.y + lift, p.z, horse.rotation.y, a.root.scale.y); }
/** reach a hand to a world point */
export function reach(a, side, target, pole = null) { ik2(a.h, `upperarm01.${side}`, `lowerarm01.${side}`, `wrist.${side}`, target, pole || V3(side === 'L' ? 0.4 : -0.4, -1, -0.3).applyQuaternion(a.root.quaternion)); }
/** hand position (world) of an actor */
export function handPos(a, side, out = new THREE.Vector3()) { return a.h.wpos(`finger3-1.${side}`, out); }
const _qx = new THREE.Quaternion(), X1 = new THREE.Vector3(1, 0, 0), Y1 = new THREE.Vector3(0, 1, 0);
export const pitch = (a, bone, ang) => { a.h.B[bone].quaternion.multiply(_qx.setFromAxisAngle(X1, ang)); return a; };
export const twist = (a, bone, ang) => { a.h.B[bone].quaternion.multiply(_qx.setFromAxisAngle(Y1, ang)); return a; };
/** sit on the ground, legs out in front, torso leaning back by `lean` (rad). call inside perform's post */
export function sitPose(a, o = {}) { const h = a.h, k = h.height / 1.8, lean = o.lean ?? 0.45, kn = o.knee ?? 0.2, R = h.rest.root.head;
  h.B.root.quaternion.multiply(_qx.setFromAxisAngle(X1, -lean)); h.B.root.position.set(R.x, (0.115 + (o.lift ?? 0)) * k, R.z);
  for (const [s, sx] of [['L', 1], ['R', -1]]) { const kk = kn * (s === 'R' ? (o.kneeR ?? 1) : 1); h.aim(`upperleg01.${s}`, V3(sx * 0.1, kk * 0.9, 1), `lowerleg01.${s}`).aim(`lowerleg01.${s}`, V3(sx * 0.04, -kk * 1.5 - 0.02, 1), `foot.${s}`).aim(`foot.${s}`, V3(sx * 0.25, 0.75, 0.6), `toe2-1.${s}`);
    if (o.arms !== false) { h.aim(`upperarm01.${s}`, V3(sx * 0.22, -1, 0.3), `lowerarm01.${s}`).aim(`lowerarm01.${s}`, V3(-sx * 0.3, -0.45, 0.85), `wrist.${s}`); h.fingers(s, 0.35, 0.3); } }
  pitch(a, 'spine03', lean * 0.3); pitch(a, 'neck01', lean * 0.35); pitch(a, 'head', lean * 0.2); }
/** kneel on both knees. sit 0 = upright on the knees, 1 = sitting back on the heels; lean = torso forward (rad) */
export function kneelPose(a, o = {}) { const h = a.h, sit = o.sit ?? 0.6, al = sit * 1.2;
  for (const [s, sx] of [['L', 1], ['R', -1]]) { h.aim(`upperleg01.${s}`, V3(sx * 0.12, -Math.cos(al), Math.sin(al)), `lowerleg01.${s}`).aim(`lowerleg01.${s}`, V3(sx * 0.02, -0.06, -1), `foot.${s}`).aim(`foot.${s}`, V3(0, -0.25, -1), `toe2-1.${s}`);
    if (o.arms !== false) { h.aim(`upperarm01.${s}`, V3(sx * 0.16, -1, 0.22), `lowerarm01.${s}`).aim(`lowerarm01.${s}`, V3(-sx * 0.12, -0.6, 0.8), `wrist.${s}`); h.fingers(s, 0.4, 0.3); } }
  if (o.lean) { pitch(a, 'spine05', o.lean * 0.5); pitch(a, 'spine03', o.lean * 0.5); } }
/** drop / lift the body so the lowest of the given joints (bone, radius) rests on world height y */
export function floorFix(a, y, pts = [['lowerleg01.L', 0.055], ['lowerleg01.R', 0.055], ['foot.L', 0.04], ['foot.R', 0.04], ['toe2-1.L', 0.02], ['toe2-1.R', 0.02]]) { const h = a.h, s = h.root.scale.y; h.root.updateMatrixWorld(true); let low = 1e9; for (const [b, r] of pts) low = Math.min(low, h.wpos(b).y - r * s * h.height / 1.8); h.B.root.position.y += (y - low) / s; h.root.updateMatrixWorld(true); }
/** single close-up: camera `dist` metres from the actor's eyes, on the line towards `toward` swung by `yaw` (rad). o: dist, yaw, lift, dy, side, fov, range, max, shake */
export function cu(st, a, toward, o = {}) { const e = eyePos(a); const d = toward.clone().sub(e); d.y = 0; if (d.lengthSq() < 1e-6) d.set(0, 0, 1); d.normalize().applyAxisAngle(Y1, o.yaw ?? 0.45); const p = e.clone().addScaledVector(d, o.dist ?? 1.0); p.y += o.lift ?? -0.04;
  const right = new THREE.Vector3().crossVectors(d, Y1); const tg = e.clone().add(V3(0, o.dy ?? -0.02, 0)).addScaledVector(right, o.side ?? 0); st.cam(p, tg, o.fov ?? 30, o.roll ?? 0, o.shake || null); if (o.dof !== false) st.dof(e, o.range ?? 0.22, o.max ?? 14); return p; }
/** debug staging view: ?view=dx,dy,dz[,fov] looks at `focus` from an offset, without depth of field */
export function stagingView(st, q, focus, arg) { const vs = (arg && arg.view) || q.get('view'); if (!vs) return false; const v = vs.split(',').map(Number); st.cam(focus.clone().add(V3(v[0], v[1], v[2])), focus, v[3] || 35); st.dof(null); st.L.fade = 0; return true; }
