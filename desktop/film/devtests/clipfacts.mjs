// What a captured clip DOES, in numbers, read straight from the motion library (no solve, no app):
//   node devtests/clipfacts.mjs <clip>[:in[:out]] …            the facts of a stretch (and, with --line, its course every quarter second)
//   node devtests/clipfacts.mjs --pair <clipA>[:in] <clipB>[:in]   where B stands and faces, seen from A, when both begin
//   node devtests/clipfacts.mjs --write                         measure every movement of kit/choreo.js into kit/movefacts.js
// The Stage starts a clip with the hips' facing at `in` turned to the body's own facing, carries the body along the
// clip's root path (scaled by leg length) and leaves it facing where the hips face at the end. The same is done here,
// so a film can know where a body will be after any chain of movements BEFORE anything is solved.
//   fwd, side: metres travelled for a leg of 1 m (× the body's leg: about 0.88 for 1.78 m), ahead and to the body's LEFT
//   turn: degrees the body has turned by the end (positive: to its left)      head: the way it travelled, from its facing
//   up / down: the hips' highest and lowest, in legs, against standing (up > 0.15: in the air; down < −0.45: on the ground)
//   v0, v1: speed at the start and the end (legs a second)    feet0, feet1: planted feet (0 none, 1 left, 2 right, 3 both)
import fs from 'node:fs';
const A = [process.env.VS_STAGE_ASSETS_DIR, new URL('../stage-assets', import.meta.url).pathname, '/home/claude/ws/squashfs-root/resources/stage-assets'].find((d) => d && fs.existsSync(`${d}/motion/index.json`));
const { Clip, fixTimes } = await import('../../../src/stage/engine/anim.js');
const { THREE } = await import('../../../src/stage/engine/core.js');
const TYPES = { f32: Float32Array, u16: Uint16Array, u32: Uint32Array, i16: Int16Array, u8: Uint8Array, i8: Int8Array, i32: Int32Array };
class Pack {
  constructor(buf) { const dv = new DataView(buf), n = dv.getUint32(8, true), head = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, 16, n))); this.buf = buf; this.index = head.arrays; this.base = 16 + n; }
  has(k) { return k in this.index; }
  view(k) { const e = this.index[k]; return new TYPES[e[2]](this.buf, this.base + e[0], e[1]); }
  f32(k) { return this.view(k); } i16(k) { return this.view(k); } u8(k) { return this.view(k); }
}
let IDX = null; const PACKS = new Map(), CLIPS = new Map();
export const library = () => { if (!A) return null; IDX ??= fixTimes(JSON.parse(fs.readFileSync(`${A}/motion/index.json`, 'utf8'))); return IDX; };
export function clipOf(name) {
  if (CLIPS.has(name)) return CLIPS.get(name);
  const idx = library(), m = idx?.clips[name]; if (!m) return null;
  if (!PACKS.has(m.s)) { const b = fs.readFileSync(`${A}/motion/s${String(m.s).padStart(3, '0')}.pack`); PACKS.set(m.s, new Pack(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength))); }
  const c = new Clip(name, m, idx, PACKS.get(m.s)); CLIPS.set(name, c); return c;
}
const HIPS = 0, q = new THREE.Quaternion(), v = new THREE.Vector3(), R2D = 180 / Math.PI, r3 = (x) => Math.round(x * 1000) / 1000;
/** the hips' facing at a moment (radians about the vertical; null when the body lies: its "front" points up or down) */
const facing = (c, t, mir) => { const f = v.set(0, 0, 1).applyQuaternion(c.quatAt(t, HIPS, q, mir)); return Math.abs(f.y) < 0.8 ? Math.atan2(f.x, f.z) : null; };
/** the root at a moment in the frame the Stage plays the clip in: [left, ahead] in legs from where it began, and the hips' height in legs against standing */
function local(c, t, t0, yawFix, mir) {
  const r = c.rootAt(t, new THREE.Vector3(), mir), r0 = c.rootAt(t0, new THREE.Vector3(), mir), dx = r.x - r0.x, dz = r.z - r0.z, co = Math.cos(yawFix), s = Math.sin(yawFix);
  return [(dx * co + dz * s) / c.m.leg, (-dx * s + dz * co) / c.m.leg, (r.y - c.m.ground - c.m.stand) / c.m.leg];
}
/** The facts of a clip from `in` to `out` (seconds in the clip, true time). o: { mirror, step: seconds between the points of its course } */
export function factsOf(name, tin = 0.05, tout, o = {}) {
  const c = clipOf(name); if (!c) return null;
  const mir = !!o.mirror, t0 = Math.max(0, tin), t1 = Math.min(tout ?? c.dur, c.dur), f0 = facing(c, t0, mir) ?? 0, yawFix = -f0, step = o.step ?? 0.25;
  const P = (t) => local(c, t, t0, yawFix, mir), end = P(t1);
  // the facing the Stage leaves the body with: the hips' over the last fifth of a second
  let fx = 0, fz = 0; for (const back of [0.2, 0.1, 0]) { const f = facing(c, Math.max(t0, t1 - back), mir); if (f !== null) { fx += Math.sin(f + yawFix); fz += Math.cos(f + yawFix); } }
  const turn = fx === 0 && fz === 0 ? 0 : Math.atan2(fx, fz) * R2D;
  let up = -9, down = 9, far = 0; const course = [];
  for (let t = t0; t < t1 + 1e-6; t += 1 / 30) { const p = P(t); up = Math.max(up, p[2]); down = Math.min(down, p[2]); far = Math.max(far, Math.hypot(p[0], p[1])); }
  for (let t = t0; t < t1 + step - 1e-6; t += step) { const p = P(Math.min(t, t1)); course.push([r3(p[0]), r3(p[1])]); }
  const speed = (t) => { const a = P(Math.max(t0, t - 0.1)), b = P(Math.min(t1, t + 0.1)); return Math.hypot(b[0] - a[0], b[1] - a[1]) / Math.max(1e-6, Math.min(t1, t + 0.1) - Math.max(t0, t - 0.1)); };
  const D = Math.hypot(end[0], end[1]);
  return { clip: name, in: r3(t0), out: r3(t1), dur: r3(t1 - t0), side: r3(end[0]), fwd: r3(end[1]), turn: Math.round(turn * 10) / 10, head: D > 0.25 ? Math.round(Math.atan2(end[0], end[1]) * R2D) : 0, far: r3(far), up: r3(up), down: r3(down), v0: r3(speed(t0)), v1: r3(speed(t1)), feet0: c.contactAt(t0, mir), feet1: c.contactAt(t1, mir), step, course };
}
/** Where B stands and faces when both begin, seen from A (two performers captured together share one floor): { side, fwd } legs of A, yaw degrees to A's left */
export function pairOf(a, ta, b, tb) {
  const ca = clipOf(a), cb = clipOf(b); if (!ca || !cb) return null;
  const fa = facing(ca, ta) ?? 0, fb = facing(cb, tb) ?? 0, ra = ca.rootAt(ta, new THREE.Vector3()), rb = cb.rootAt(tb, new THREE.Vector3()), dx = rb.x - ra.x, dz = rb.z - ra.z, co = Math.cos(-fa), s = Math.sin(-fa);
  let yaw = (fb - fa) * R2D; yaw = ((yaw + 540) % 360) - 180;
  return { side: r3((dx * co + dz * s) / ca.m.leg), fwd: r3((-dx * s + dz * co) / ca.m.leg), yaw: Math.round(yaw * 10) / 10, durA: r3(ca.dur), durB: r3(cb.dur) };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const a = process.argv.slice(2), flag = (n) => { const i = a.indexOf('--' + n); if (i < 0) return false; a.splice(i, 1); return true; };
  if (!A) { console.error('no motion library here (set VS_STAGE_ASSETS_DIR)'); process.exit(1); }
  const line = flag('line'), pair = flag('pair'), write = flag('write');
  const parse = (s) => { const [n, i, o] = s.split(':'); return [n, i === undefined || i === '' ? 0.05 : +i, o === undefined || o === '' ? undefined : +o]; };
  if (write) {
    const { MOVEMENTS } = await import('../kit/choreo.js'); const out = {};
    for (const [id, m] of Object.entries(MOVEMENTS)) {
      const f = factsOf(m.clip, m.in ?? 0.05, m.out, { mirror: m.mirror }); if (!f) { console.error(`${id}: no clip ${m.clip}`); process.exit(1); }
      const { clip, in: _i, out: _o, ...rest } = f; out[id] = rest;
      if (m.with) { const w = MOVEMENTS[m.with]; out[id].pair = pairOf(m.clip, m.in ?? 0.05, w.clip, w.in ?? 0.05); }
    }
    const text = `// MEASURED by devtests/clipfacts.mjs --write from the motion library: what each movement of kit/choreo.js does (legs, degrees,\n// seconds). Never edited by hand: change the movement, then measure again.\nexport const FACTS = {\n${Object.entries(out).map(([k, f]) => `  '${k}': ${JSON.stringify(f)},`).join('\n')}\n};\n`;
    fs.writeFileSync(new URL('../kit/movefacts.js', import.meta.url), text); console.log(`kit/movefacts.js: ${Object.keys(out).length} movements measured`);
  } else if (pair) {
    const [x, y] = a.map(parse); console.log(JSON.stringify(pairOf(x[0], x[1], y[0], y[1])));
  } else for (const s of a) {
    const [n, i, o] = parse(s), c = clipOf(n); if (!c) { console.log(`${n}: not in the library`); continue; }
    const f = factsOf(n, i, o), { course, ...head } = f;
    console.log(`${n}  "${c.m.desc}"  ${c.dur.toFixed(2)} s\n  ${JSON.stringify(head)}`);
    if (line) for (let t = f.in; t < f.out + 1e-6; t += 0.25) { const g = factsOf(n, f.in, Math.max(f.in + 0.05, t)); console.log(`  ${t.toFixed(2)}  ahead ${g.fwd.toFixed(2).padStart(6)}  left ${g.side.toFixed(2).padStart(6)}  hips ${(local(c, t, f.in, 0, false)[2]).toFixed(2).padStart(6)}  feet ${c.contactAt(t)}  turned ${String(Math.round(g.turn)).padStart(4)}°`); }
  }
}
