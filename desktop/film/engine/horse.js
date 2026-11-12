// A horse that walks, trots and gallops: hooves planted by the ground it covers (no sliding), legs solved from hoof to
// shoulder, a body that rises, falls and pitches with the gait, mane and tail that follow. Drawn as cels like the people.
import { inView } from './camera.js';
import { V, clamp, lerp, hash, noise1, shade as shadeHex, smooth } from './core.js';
import { capsule, smoothClosed, smoothOpen, hull } from './draw.js';

// stride (m), how long each hoof is down (share of the cycle), when each hoof lands (LH, RH, LF, RF), rise, pitch
const GAITS = {
  stand: { stride: 1, duty: 1, ph: [0, 0.5, 0.25, 0.75], bob: 0, pitch: 0, lift: 0, speed: 0 },
  walk: { stride: 1.75, duty: 0.6, ph: [0, 0.5, 0.25, 0.75], bob: 0.015, pitch: 0.01, lift: 0.11, speed: 1.6 },
  trot: { stride: 2.6, duty: 0.4, ph: [0, 0.5, 0.5, 0], bob: 0.045, pitch: 0.012, lift: 0.2, speed: 3.6 },
  gallop: { stride: 5.4, duty: 0.2, ph: [0.0, 0.11, 0.47, 0.58], bob: 0.08, pitch: 0.075, lift: 0.34, speed: 10.5 },
};
export const horseSpeed = (gait) => GAITS[gait].speed;

/** Two-bone leg: from the top joint to the hoof, the middle joint bends towards `bend` (+1 forwards, −1 backwards). */
function knee(top, hoof, l1, l2, fwd, bend) {
  const d = V.sub(hoof, top), dl = Math.min(V.len(d), (l1 + l2) * 0.999), u = V.mul(d, 1 / (V.len(d) || 1));
  const a = (l1 * l1 - l2 * l2 + dl * dl) / (2 * dl), h = Math.sqrt(Math.max(0, l1 * l1 - a * a));
  let side = V.sub(fwd, V.mul(u, V.dot(fwd, u))); side = V.norm(side);
  return V.add(V.add(top, V.mul(u, a)), V.mul(side, h * bend));
}

export class Horse {
  /** o: { coat, mane, sock, scale (1 = 1.6 m at the withers), cloth (barding colour), tack, seed } */
  constructor(o = {}) { this.o = { coat: '#4a3528', mane: '#1c1614', scale: 1, ...o }; this.s = 0; this.tail = null; this.t = 0; this.j = null; }
  /**
   * Put the horse somewhere: pos [x, z] on the ground (y = ground), yaw (radians, 0 = facing +Z like the people),
   * dist = metres travelled so far (drives the legs), gait: 'stand' | 'walk' | 'trot' | 'gallop', mix: [gaitB, weight] to blend.
   */
  place(pos, yaw, dist, gait, t, o = {}) {
    const k = this.o.scale, G = GAITS[gait], f = [Math.sin(yaw), 0, Math.cos(yaw)], sd = [Math.cos(yaw), 0, -Math.sin(yaw)], gy = o.ground ?? 0;
    const cyc = dist / (G.stride * k), seed = this.o.seed ?? 1;
    // the body: rises twice a stride at the trot, once at the gallop; pitches at the gallop
    const bobPh = gait === 'gallop' ? cyc * Math.PI * 2 : cyc * Math.PI * 4;
    const bob = G.bob * k * Math.sin(bobPh + 0.6) + (gait === 'stand' ? Math.sin(t * 1.3 + seed) * 0.004 : 0), pitch = G.pitch * Math.sin(cyc * Math.PI * 2 + 2.2) + (o.rear ?? 0);
    const W = (x, y, z) => { const c = Math.cos(pitch), s = Math.sin(pitch), yy = y - 1.25 * k, x2 = x * c - yy * s, y2 = x * s + yy * c + 1.25 * k + bob; return [pos[0] + f[0] * x2 + sd[0] * z, gy + y2, pos[1] + f[2] * x2 + sd[2] * z]; };
    const j = {};
    j.chest = W(0.58 * k, 1.22 * k, 0); j.belly = W(0, 1.08 * k, 0); j.croup = W(-0.62 * k, 1.32 * k, 0); j.withers = W(0.42 * k, 1.56 * k, 0); j.back = W(-0.1 * k, 1.47 * k, 0); j.dock = W(-0.78 * k, 1.36 * k, 0);
    // neck and head: nod with the gait, lower at the gallop, turn a little to where it looks
    const nod = (gait === 'walk' ? 0.06 * Math.sin(cyc * Math.PI * 4) : gait === 'gallop' ? 0.1 * Math.sin(cyc * Math.PI * 2 + 0.8) - 0.22 : 0.02 * Math.sin(t * 0.7 + seed)) + (o.head ?? 0);
    const na = 0.92 + nod, nl = 0.78 * k;
    j.poll = W(0.42 * k + Math.cos(na) * nl + 0.12 * k, 1.5 * k + Math.sin(na) * nl, (o.turn ?? 0) * 0.25 * k);
    const ha = na - 1.75 - nod * 0.5, hl = 0.56 * k;
    j.muzzle = V.add(j.poll, V.add(V.mul(f, Math.cos(ha) * hl * Math.cos(pitch)), V.add([0, Math.sin(ha) * hl, 0], V.mul(sd, (o.turn ?? 0) * 0.2 * k))));
    // legs
    const legs = [['LH', -0.56, 0.17, 0, -1], ['RH', -0.56, -0.17, 1, -1], ['LF', 0.5, 0.15, 2, 1], ['RF', 0.5, -0.15, 3, 1]];
    for (const [name, lx, lz, pi, bend] of legs) {
      const top = W(lx * k, (bend > 0 ? 1.12 : 1.18) * k, lz * k);
      let u = ((cyc + G.ph[pi]) % 1 + 1) % 1, hx, hy = 0;
      // while a hoof is down the body passes over it (it goes back, relative to the body, by the ground covered);
      // then it is lifted and swung forward to its next print
      const span = G.stride * k * G.duty;
      if (gait === 'stand') { hx = (pi % 2 ? 0.03 : -0.03) * k; }
      else if (u < G.duty) { hx = span * (0.5 - u / G.duty); }
      else { const v = (u - G.duty) / (1 - G.duty), e = v * v * (3 - 2 * v); hx = span * (-0.5 + e); hy = Math.sin(Math.PI * v) * G.lift * k * (bend > 0 ? 1.15 : 0.85); }
      const hoof = [pos[0] + f[0] * (lx * k + hx + (bend > 0 ? 0.06 : -0.02) * k) + sd[0] * lz * k, gy + hy + 0.045 * k, pos[1] + f[2] * (lx * k + hx + (bend > 0 ? 0.06 : -0.02) * k) + sd[2] * lz * k];
      const l1 = (bend > 0 ? 0.5 : 0.56) * k, l2 = (bend > 0 ? 0.68 : 0.76) * k;
      // front legs fold forwards at the knee when lifted; hind legs keep the hock behind
      const mid = knee(top, hoof, l1, l2, f, bend > 0 ? (hy > 0.02 ? 1 : 0.35) : -1);
      j[name] = { top, mid, hoof, fet: V.lerp(mid, hoof, 0.78) };
    }
    this.j = j; this.f = f; this.sd = sd; this.k = k; this.gait = gait; this.t = t; this.pitch = pitch; this.yaw = yaw;
    // the tail: a few points that trail the dock and swing
    const dt = clamp(t - (this.tt ?? t), 0, 0.05); this.tt = t;
    const n = 5; if (!this.tail || V.dist(this.tail[0], j.dock) > 2) this.tail = Array.from({ length: n }, (_, i) => V.add(j.dock, [-f[0] * 0.12 * i * k, -0.2 * i * k, -f[2] * 0.12 * i * k]));
    this.tail[0] = j.dock;
    for (let i = 1; i < n; i++) {
      const want = V.add(this.tail[i - 1], V.add(V.mul(f, -(gait === 'gallop' ? 0.2 : 0.07) * k), [noise1(t * 2 + i, seed) * 0.02, -(gait === 'gallop' ? 0.1 : 0.2) * k, 0]));
      this.tail[i] = V.lerp(this.tail[i], want, clamp(dt * 14));
      const d = V.sub(this.tail[i], this.tail[i - 1]), dl = V.len(d) || 1; this.tail[i] = V.add(this.tail[i - 1], V.mul(d, 0.22 * k / dl));
    }
    return this;
  }
  /** Where a rider sits: the saddle's place, the way the horse faces (radians) and how far it tips. */
  saddle() { const j = this.j; return { p: V.add(V.lerp(j.withers, j.back, 0.6), [0, 0.06 * this.k, 0]), yaw: this.yaw, pitch: this.pitch, f: this.f, sd: this.sd }; }

  draw(g, cam, lit, o = {}) {
    const j = this.j, k = this.k, P = (p) => cam.project(p), zc = cam.toCam(j.belly)[2];
    if (zc < 0.4) return;
    // a horse that reaches the lens plane (head, a hoof) projects as a band across the picture: leave it out, as one wholly outside the picture
    { let zmin = 1e9; for (const n in j) { const z = cam.toCam(j[n])[2]; if (z < zmin) zmin = z; }
      if (zmin < 0.3 || !inView(cam, j, 1.2)) { if (globalThis.__cullLog && (zmin < 0.3 || zc < 1.2)) globalThis.__cullLog('horse', [0, zmin * 100, zc * 100], cam, null, 0); return; } }
    const ppm = cam.f / zc, lw = clamp(0.0055 * ppm, 0.8, 4), ink = lit(o.ink ?? '#15121a'), L = o.light ?? [-0.5, -0.8];
    const coat = lit(this.o.coat), coatS = lit(shadeHex(this.o.coat, -0.28)), mane = lit(this.o.mane), far = 1.6 * k * ppm < 90;
    const sc = (p) => cam.f / Math.max(0.05, p[2]);
    // ground shadow
    if (o.shadow !== false) { const c = P([j.belly[0], o.ground ?? 0, j.belly[2]]); if (c[2] > 0.3) { const r = 0.95 * k * sc(c); g.save(); g.globalAlpha = o.shadowAlpha ?? 0.28; g.fillStyle = o.shadowColor ?? '#0a0814'; g.beginPath(); g.ellipse(c[0], c[1], r * (0.6 + 0.4 * Math.abs(V.dot(this.f, cam.R))) * 1.15, r * clamp(Math.abs(cam.F[1]) * 1.15 + 0.1, 0.12, 1) * 0.5, 0, 0, 7); g.fill(); g.restore(); } }
    const part = (path, fill, shade) => {
      g.beginPath(); path(); g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = fill; g.fill();
      if (shade && !far) { g.save(); g.beginPath(); path(); g.clip(); g.fillStyle = shade; g.beginPath(); path(); g.fill(); g.translate(L[0] * ppm * 0.07 * k, L[1] * ppm * 0.09 * k); g.fillStyle = fill; g.beginPath(); path(); g.fill(); g.restore(); }
    };
    const leg = (q, back) => {
      const a = P(q.top), b = P(q.mid), c = P(q.fet), d = P(q.hoof);
      part(() => { capsule(g, a[0], a[1], 0.115 * k * sc(a), b[0], b[1], 0.058 * k * sc(b)); capsule(g, b[0], b[1], 0.055 * k * sc(b), c[0], c[1], 0.04 * k * sc(c)); capsule(g, c[0], c[1], 0.04 * k * sc(c), d[0], d[1], 0.047 * k * sc(d)); }, back ? coatS : coat, back ? null : coatS);
      g.beginPath(); capsule(g, lerp(c[0], d[0], 0.55), lerp(c[1], d[1], 0.55), 0.05 * k * sc(d), d[0], d[1], 0.058 * k * sc(d)); g.fillStyle = lit(this.o.hoof ?? '#1a1616'); g.fill();
      if (this.o.sock && !back) { g.beginPath(); capsule(g, b[0] * 0.3 + c[0] * 0.7, b[1] * 0.3 + c[1] * 0.7, 0.045 * k * sc(c), lerp(c[0], d[0], 0.5), lerp(c[1], d[1], 0.5), 0.047 * k * sc(d)); g.fillStyle = lit(this.o.sock); g.fill(); }
    };
    // which side of the horse the lens sees: the far legs go first
    const left = V.dot(this.sd, V.sub(cam.pos, j.belly)) > 0;
    const farL = left ? ['RH', 'RF'] : ['LH', 'LF'], nearL = left ? ['LH', 'LF'] : ['RH', 'RF'];
    // tail (behind the body when the horse comes towards us)
    const tailDraw = () => { const tp = this.tail.map(P), n = tp.length, lf = [], rt = []; for (let i = 0; i < n; i++) { const a = tp[Math.max(0, i - 1)], b = tp[Math.min(n - 1, i + 1)]; let dx = b[0] - a[0], dy = b[1] - a[1]; const dl = Math.hypot(dx, dy) || 1; dx /= dl; dy /= dl; const w = (0.03 + 0.07 * Math.sin(Math.PI * Math.min(1, i / (n - 1) * 1.1))) * k * sc(tp[i]); lf.push([tp[i][0] - dy * w, tp[i][1] + dx * w]); rt.push([tp[i][0] + dy * w, tp[i][1] - dx * w]); } part(() => { smoothOpen(g, lf, 1); smoothOpen(g, rt.reverse(), 1, false); g.closePath(); }, mane, null); };
    const towards = V.dot(this.f, V.sub(cam.pos, j.belly)) > 0;
    if (towards) tailDraw();
    for (const n of farL) leg(j[n], true);
    // body: sections along the spine, outlined as one shape
    const up = [0, 1, 0], secs = [[j.dock, 0.17, 0.2], [j.croup, 0.3, 0.33], [V.lerp(j.croup, j.belly, 0.5), 0.33, 0.36], [j.belly, 0.31, 0.38], [V.lerp(j.belly, j.chest, 0.6), 0.31, 0.4], [j.chest, 0.27, 0.36], [V.add(j.chest, V.mul(this.f, 0.14 * k)), 0.16, 0.24]];
    secs[1][0] = V.add(j.croup, [0, -0.3 * k, 0]); secs[2][0] = V.add(secs[2][0], [0, 0.08 * k, 0]); secs[3][0] = V.add(j.belly, [0, 0.12 * k, 0]); secs[4][0] = V.add(secs[4][0], [0, 0.1 * k, 0]); secs[5][0] = V.add(j.chest, [0, 0.02 * k, 0]); secs[0][0] = V.add(j.dock, [0, -0.2 * k, 0]);
    const pts = [];
    for (const [c, rx, ry] of secs) for (let i = 0; i < 12; i++) { const a = (i / 12) * Math.PI * 2; pts.push(P(V.add(c, V.add(V.mul(this.sd, Math.cos(a) * rx * k), V.mul(up, Math.sin(a) * ry * k))))); }
    const body = hull(pts);
    part(() => smoothClosed(g, body, 0.8), coat, coatS);
    // cloth over the back (a saddle blanket or a war horse's cover)
    if (this.o.cloth && !far) { const cp = []; for (const u of [0.15, 0.5, 0.85]) { const c = V.lerp(j.withers, j.croup, u); for (let i = 0; i <= 8; i++) { const a = -0.25 + (i / 8) * (Math.PI + 0.5); cp.push(P(V.add(V.add(c, [0, -0.3 * k, 0]), V.add(V.mul(this.sd, Math.cos(a) * 0.335 * k), V.mul(up, Math.sin(a) * 0.36 * k))))); } } part(() => smoothClosed(g, hull(cp), 0.7), lit(this.o.cloth), lit(shadeHex(this.o.cloth, -0.25))); }
    // neck and head
    const w0 = P(V.lerp(j.withers, j.chest, 0.45)), pl = P(j.poll), mz = P(j.muzzle);
    part(() => capsule(g, w0[0], w0[1], 0.25 * k * sc(w0), pl[0], pl[1], 0.125 * k * sc(pl)), coat, coatS);
    const jaw = P(V.add(V.lerp(j.poll, j.muzzle, 0.3), [0, -0.16 * k, 0]));
    part(() => { capsule(g, pl[0], pl[1], 0.13 * k * sc(pl), mz[0], mz[1], 0.075 * k * sc(mz)); capsule(g, jaw[0], jaw[1], 0.1 * k * sc(jaw), mz[0], mz[1], 0.07 * k * sc(mz)); }, coat, coatS);
    if (!far) {
      // ears, eye, nostril, the blaze if it has one
      for (const s of [1, -1]) { const e0 = P(V.add(j.poll, V.add(V.mul(this.sd, s * 0.06 * k), [0, 0.08 * k, 0]))), e1 = P(V.add(j.poll, V.add(V.mul(this.sd, s * 0.09 * k), V.add([0, 0.24 * k, 0], V.mul(this.f, -0.03 * k))))); g.beginPath(); capsule(g, e0[0], e0[1], 0.035 * k * sc(e0), e1[0], e1[1], 0.008 * k * sc(e1)); g.lineWidth = lw * 1.6; g.strokeStyle = ink; g.stroke(); g.fillStyle = coat; g.fill(); }
      const eye = P(V.add(V.lerp(j.poll, j.muzzle, 0.3), V.add(V.mul(this.sd, (left ? 1 : -1) * 0.1 * k), [0, 0.02 * k, 0])));
      g.beginPath(); g.ellipse(eye[0], eye[1], 0.028 * k * sc(eye), 0.022 * k * sc(eye), 0, 0, 7); g.fillStyle = ink; g.fill();
      if (o.eye) { g.beginPath(); g.arc(eye[0], eye[1], 0.012 * k * sc(eye), 0, 7); g.fillStyle = o.eye; g.fill(); }
      const no = P(V.add(V.lerp(j.poll, j.muzzle, 0.95), V.mul(this.sd, (left ? 1 : -1) * 0.05 * k)));
      g.beginPath(); g.ellipse(no[0], no[1], 0.014 * k * sc(no), 0.008 * k * sc(no), 0.5, 0, 7); g.fillStyle = ink; g.fill();
      if (this.o.tack) { const a = P(V.lerp(j.poll, j.muzzle, 0.62)), b = P(V.add(V.lerp(j.poll, j.muzzle, 0.62), [0, -0.2 * k, 0])), r0 = P(V.add(j.withers, [0, -0.02 * k, 0])); g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.moveTo(b[0], b[1]); g.quadraticCurveTo((b[0] + r0[0]) / 2, Math.max(b[1], r0[1]) + 0.08 * k * ppm, r0[0], r0[1]); g.lineWidth = Math.max(1, 0.014 * k * ppm); g.strokeStyle = lit(this.o.tack); g.stroke(); }
    }
    // mane: tufts along the crest of the neck, blown back
    const mp = [], blow = this.gait === 'gallop' ? 0.2 : this.gait === 'trot' ? 0.1 : 0.03;
    for (let i = 0; i <= 6; i++) { const u = i / 6, c = V.lerp(j.withers, j.poll, 0.1 + u * 0.92), tip = V.add(c, V.add(V.mul(this.f, -(0.1 + blow + 0.03 * Math.sin(this.t * 6 + i)) * k), [0, (0.1 - blow * 0.3) * k, 0])); mp.push([P(V.add(c, [0, 0.07 * k, 0])), P(tip)]); }
    part(() => { g.moveTo(mp[0][0][0], mp[0][0][1]); for (let i = 0; i < mp.length; i++) { g.lineTo(mp[i][1][0], mp[i][1][1]); if (i < mp.length - 1) { const m = [(mp[i][0][0] + mp[i + 1][0][0]) / 2, (mp[i][0][1] + mp[i + 1][0][1]) / 2]; g.lineTo(m[0], m[1]); } } g.lineTo(mp[mp.length - 1][0][0], mp[mp.length - 1][0][1]); for (let i = mp.length - 1; i >= 0; i--) g.lineTo(mp[i][0][0], mp[i][0][1]); g.closePath(); }, mane, null);
    for (const n of nearL) leg(j[n], false);
    if (!towards) tailDraw();
  }
}

/**
 * Seat a rider on a horse: bends a sitting pose so the legs go down the horse's sides, and carries the whole body with
 * the saddle. pose: a character pose (sitting or standing); returns the same pose, changed.
 */
export function mount(pose, horse, o = {}) {
  const sdl = horse.saddle(), j = pose.j, k = pose.height / 1.8, root = j.root;
  // the rider's own axes from their hips
  const hipL = j['upperleg01.L'], hipR = j['upperleg01.R'], side = V.norm(V.sub(hipL, hipR)), up = [0, 1, 0], fw = V.cross(side, up);
  const yaw0 = Math.atan2(fw[0], fw[2]), dy = sdl.yaw - yaw0, c = Math.cos(dy), s = Math.sin(dy);
  const seat = V.add(sdl.p, [0, (o.lift ?? 0.02) * k, 0]), pel = V.mid(hipL, hipR);
  const turn = (p) => { const x = p[0] - pel[0], y = p[1] - pel[1], z = p[2] - pel[2]; return [seat[0] + x * c + z * s, seat[1] + y + (o.lean ?? 0) * 0, seat[2] - x * s + z * c]; };
  for (const n in j) j[n] = turn(j[n]);
  for (const n in pose.props) pose.props[n].pos = turn(pose.props[n].pos);
  // legs: the thighs grip the barrel, the lower legs hang
  for (const sgn of ['L', 'R']) {
    const sg = sgn === 'L' ? 1 : -1, hip = j[`upperleg01.${sgn}`], out = V.mul(sdl.sd, sg), thigh = 0.44 * k, shin = 0.46 * k;
    const kn = V.add(hip, V.add(V.add(V.mul(sdl.f, 0.26 * k), V.mul(out, 0.2 * k)), [0, -0.3 * k, 0]));
    const knee2 = V.add(hip, V.mul(V.norm(V.sub(kn, hip)), thigh));
    const an = V.add(knee2, V.add(V.add(V.mul(sdl.f, -0.1 * k), V.mul(out, 0.02 * k)), [0, -shin, 0]));
    j[`lowerleg01.${sgn}`] = knee2; j[`foot.${sgn}`] = an;
    j[`toe2-1.${sgn}`] = V.add(an, V.add(V.mul(sdl.f, 0.13 * k), [0, -0.05 * k, 0])); j[`toe2-3.${sgn}:tail`] = V.add(an, V.add(V.mul(sdl.f, 0.22 * k), [0, -0.06 * k, 0]));
  }
  // the leg on the far side of the horse is hidden by it (unless the horse is seen from in front or behind)
  if (o.cam) { const vd = V.norm(V.sub(o.cam.pos, seat)), sdn = V.dot(sdl.sd, vd); if (Math.abs(sdn) > 0.3) { const far = sdn > 0 ? 'R' : 'L', hip = j[`upperleg01.${far}`], tuck = V.add(hip, V.mul(sdl.sd, sdn > 0 ? 0.06 * k : -0.06 * k)); for (const n of [`lowerleg01.${far}`, `foot.${far}`, `toe2-1.${far}`, `toe2-3.${far}:tail`]) j[n] = V.add(tuck, [0, -0.02 * k, 0]); } }
  pose.contact = 0;
  return pose;
}
