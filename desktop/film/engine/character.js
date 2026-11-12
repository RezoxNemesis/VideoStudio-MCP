// A drawn character: a design sheet (colours, build, clothes, hair) laid over a moving skeleton and painted as cels.
// The skeleton comes from solved motion (see bake.js); everything visible is drawn here, in depth order, every frame.
import { V, clamp, lerp, shade as shadeHex, noise1 } from './core.js';
import { capsule, capsuleOpen, smoothClosed, smoothOpen, poly, cel, capsuleSides, jointArc } from './draw.js';
import { makeHead, drawHead, skull } from './head.js';
import { Cloth, Strand } from './sim.js';
import { qrot } from './core.js';

const SIDES = ['L', 'R'];

export function makeDesign(o) {
  const d = {
    name: o.name ?? 'someone',
    skin: o.skin ?? '#e9b995', skinShade: o.skinShade,
    ink: o.ink ?? '#15121a', inkScale: o.inkScale ?? 1,
    headScale: o.headScale ?? 1.1,
    build: { k: 1, arm: 1, leg: 1, shoulder: 1, hip: 1, waist: 1, chest: 1, neck: 1, female: false, ...(o.build ?? {}) },
    face: { shape: 'male', ...(o.face ?? {}) },
    hair: o.hair === null ? null : { color: '#1c1a22', cap: true, tufts: [], ...(o.hair ?? {}) },
    beard: o.beard ?? null, headgear: o.headgear ?? null,
    top: { type: 'tunic', color: '#3a3f4c', sleeve: 'long', hem: 0.12, collar: 'v', ...(o.top ?? {}) },
    bottom: { type: 'pants', color: '#2c2f38', ...(o.bottom ?? {}) },
    shoes: o.shoes === null ? null : { color: '#2a2422', boot: 0, ...(o.shoes ?? {}) },
    sash: o.sash ?? null, cape: o.cape ?? null, armor: o.armor ?? null,
    sword: { blade: '#dfe6ee', guard: '#b08a3c', grip: '#2a1f24', ...(o.sword ?? {}) },
    spear: o.spear ?? null, staff: o.staff ?? null, shield: o.shield ?? null,
  };
  return d;
}

const J = {
  hip: (s) => `upperleg01.${s}`, knee: (s) => `lowerleg01.${s}`, ank: (s) => `foot.${s}`, ball: (s) => `toe2-1.${s}`, toe: (s) => `toe2-3.${s}:tail`,
  sh: (s) => `upperarm01.${s}`, el: (s) => `lowerarm01.${s}`, wr: (s) => `wrist.${s}`, k2: (s) => `finger2-1.${s}`, k3: (s) => `finger3-1.${s}`, k5: (s) => `finger5-1.${s}`, tip: (s) => `finger3-3.${s}:tail`, thumb: (s) => `finger1-3.${s}:tail`,
};

export class Character {
  constructor(design) {
    this.d = design.build ? design : makeDesign(design);
    this.hd = makeHead(this.d);
    this.front = { armL: null, armR: null, foreL: null, foreR: null, cape: null, tail: null };
    this.cape = null; this.skirt = null; this.strands = null; this.sleeve = { L: null, R: null }; this.lockState = null;
    this.t = 0;
  }

  /** The body's own measures and axes for a pose. */
  frame(pose) {
    const j = pose.j, d = this.d, b = d.build, k = (pose.height / 1.75) * b.k;
    const hipL = j['upperleg01.L'], hipR = j['upperleg01.R'], shL = j['upperarm01.L'], shR = j['upperarm01.R'];
    const pel = V.mid(hipL, hipR), shc = V.mid(shL, shR);
    const up = V.norm(V.sub(j.neck01, pel));
    const sP = V.norm(V.perp(V.sub(hipL, hipR), up)), sC = V.norm(V.perp(V.sub(shL, shR), up));
    const fP = V.cross(sP, up), fC = V.cross(sC, up);
    const hipHalf = V.dist(hipL, hipR) / 2, shHalf = V.dist(shL, shR) / 2;
    // the head: its own axes from the eyes and the crown
    const eL = j['eye.L'], eR = j['eye.R'], hb = j.head, cr = j['head:tail'];
    const hs = V.norm(V.sub(eL, eR));
    const hu = V.norm(V.perp(V.sub(cr, hb), hs)), hf = V.cross(hs, hu);
    const eyeMid = V.mid(eL, eR);
    const R0 = V.dist(eL, eR) * 1.78; // half the head's height, from the eye spacing
    const R = R0 * d.headScale;
    // the skull's centre sits behind the eyes; a bigger drawn head grows up and out from the neck
    const c0 = V.add(V.add(eyeMid, V.mul(hf, -0.78 * R0)), V.mul(hu, 0.05 * R0));
    const hc = V.add(hb, V.mul(V.sub(c0, hb), d.headScale));
    return { k, pel, shc, up, sP, sC, fP, fC, hipHalf, shHalf, head: { c: hc, s: hs, u: hu, f: hf, R }, hipL, hipR, shL, shR };
  }

  /* -------------------------------------------------------------- simulation */

  /** Advance hair and cloth to this pose. Call once per frame, in order. env: { wind(p,t), ground } */
  step(pose, dt, t, env = {}) {
    const d = this.d, F = this.frame(pose), j = pose.j, k = F.k;
    this.t = t;
    const caps = this.bodyCaps(pose, F);
    // ---- cape: threads from shoulder to shoulder across the back of the neck
    if (d.cape) {
      const n = d.cape.cols ?? 7;
      if (!this.cape) this.cape = new Cloth({ cols: n, rows: d.cape.rows ?? 7, length: (d.cape.len ?? 0.62) * pose.height, damp: 0.985, drag: d.cape.drag ?? 0.9, pad: 0.02 });
      const pins = [];
      for (let i = 0; i < n; i++) {
        const u = (i / (n - 1)) * 2 - 1; // −1 = left shoulder … +1 = right shoulder
        const side = V.lerp(j.neck01, u < 0 ? F.shL : F.shR, Math.abs(u) * (d.cape.w ?? 0.92));
        pins.push(V.add(V.add(side, V.mul(F.fC, -(0.1 - 0.035 * Math.abs(u)) * k)), V.mul(F.up, (0.045 - 0.02 * Math.abs(u)) * k)));
      }
      const rest = (c, r) => { const seg = this.cape.len[c] / (this.cape.rows - 1); return V.add(V.add(pins[c], [0, -seg * r, 0]), V.mul(F.fC, -0.06 * r * seg * 4)); };
      this.cape.step(pins, dt, t, { caps, wind: env.wind, ground: env.ground ?? 0, rest, flare: d.cape.flare ?? 0.9 });
    }
    // ---- skirt / robe: a ring of threads from the waist
    if (d.bottom.type === 'skirt' || d.bottom.type === 'robe') {
      const n = 12, rows = 6, len = (d.bottom.len ?? 0.5) * pose.height;
      if (!this.skirt) this.skirt = new Cloth({ cols: n, rows, length: len, closed: true, damp: 0.975, drag: 0.8, stiff: 0.06, pad: 0.03 });
      const c0 = V.add(F.pel, V.mul(F.up, 0.06 * k)), a = (F.hipHalf + 0.085 * k * d.build.hip), bb = 0.125 * k;
      const pins = [], dirs = [];
      for (let i = 0; i < n; i++) { const ph = (i / n) * Math.PI * 2; const dir = V.add(V.mul(F.sP, Math.sin(ph)), V.mul(F.fP, Math.cos(ph))); dirs.push(dir); pins.push(V.add(c0, V.add(V.mul(F.sP, Math.sin(ph) * a), V.mul(F.fP, Math.cos(ph) * bb)))); }
      const fl = d.bottom.flare ?? 0.55;
      const rest = (c, r) => { const u = r / (rows - 1); return V.add(V.add(pins[c], [0, -len * u * 0.97, 0]), V.mul(dirs[c], len * u * fl * 0.42)); };
      this.skirt.step(pins, dt, t, { caps: caps.filter((c) => c[4] === 'leg'), wind: env.wind, ground: env.ground ?? 0, rest, flare: fl * 1.6 });
    }
    const H = F.head, R = H.R;
    const W = (p) => [H.c[0] + (H.s[0] * p[0] + H.u[0] * p[1] + H.f[0] * p[2]) * R, H.c[1] + (H.s[1] * p[0] + H.u[1] * p[1] + H.f[1] * p[2]) * R, H.c[2] + (H.s[2] * p[0] + H.u[2] * p[1] + H.f[2] * p[2]) * R];
    // ---- locks that follow the head: each tip lags behind where it belongs, swings once and settles
    if (d.hair && this.hd.locks.length) {
      if (!this.lockState) this.lockState = this.hd.locks.map(() => null);
      this.hd.locks.forEach((lk, i) => {
        const want = W(lk.pts[lk.pts.length - 1]);
        let st = this.lockState[i];
        if (!st || V.dist(st.p, want) > 0.5) st = this.lockState[i] = { p: want, v: [0, 0, 0], off: [0, 0, 0] };
        const kq = 300 * (lk.stiff ?? 1), cq = 2 * Math.sqrt(kq) * (lk.settle ?? 0.5), wk = (lk.wind ?? 1) * 2.2;
        const wv = env.wind ? env.wind(st.p, t) : [0, 0, 0];
        const sub = 2, h = dt / sub;
        for (let q = 0; q < sub; q++) {
          for (let a = 0; a < 3; a++) { st.v[a] += ((want[a] - st.p[a]) * kq - st.v[a] * cq + wv[a] * wk) * h; st.p[a] += st.v[a] * h; }
        }
        let off = V.sub(st.p, want); const mx = 0.45 * lk.len * R, ol = V.len(off);
        if (ol > mx) { off = V.mul(off, mx / ol); st.p = V.add(want, off); }
        st.off = off;
      });
    }
    // ---- hair that hangs: every strand is a thread that keeps its shape but lags and swings
    if (d.hair && d.hair.tufts.length) {
      if (!this.strands) this.strands = d.hair.tufts.map((tf) => new Strand({ nodes: tf.nodes ?? 4, length: tf.len * R, stiff: tf.stiff ?? 0.55, damp: tf.damp ?? 0.9, drag: tf.drag ?? 0.5, gravity: tf.gravity ?? 0.5 }));
      d.hair.tufts.forEach((tf, i) => {
        const st = this.strands[i]; st.len[0] = tf.len * R;
        const base = tf.at ?? V.mul(skull(tf.az, tf.el, this.hd.sh), 1.04);
        const dir = V.norm(tf.dir), cv = tf.curve ?? [0, 0, 0];
        const restL = (r) => { const u = r / (st.rows - 1); return [base[0] + (dir[0] * u + cv[0] * u * u) * tf.len, base[1] + (dir[1] * u + cv[1] * u * u) * tf.len, base[2] + (dir[2] * u + cv[2] * u * u) * tf.len]; };
        tf._base = base;
        st.step([W(base)], dt, t, { caps: tf.collide ? caps : [], wind: env.wind, ground: env.ground ?? 0, rest: (c, r) => W(restL(r)), sub: 2 });
      });
    }
  }

  /** Capsules of the body that cloth must stay outside of: [a, ra, b, rb, kind]. */
  bodyCaps(pose, F) {
    const j = pose.j, k = F.k, b = this.d.build, out = [];
    // the back is flatter than a tube: cloth lies on a narrower core, and the shoulders are their own bars
    out.push([V.add(F.pel, V.mul(F.up, -0.02 * k)), 0.115 * k, j.spine02, 0.118 * k, 'torso']);
    out.push([j.spine02, 0.118 * k, V.add(F.shc, V.mul(F.up, -0.07 * k)), 0.112 * k, 'torso']);
    out.push([V.add(F.shL, V.mul(F.up, -0.05 * k)), 0.07 * k, V.add(F.shR, V.mul(F.up, -0.05 * k)), 0.07 * k, 'torso']);
    for (const s of SIDES) {
      out.push([j[J.hip(s)], 0.095 * k * b.leg, j[J.knee(s)], 0.065 * k * b.leg, 'leg']);
      out.push([j[J.knee(s)], 0.065 * k * b.leg, j[J.ank(s)], 0.045 * k * b.leg, 'leg']);
      out.push([j[J.sh(s)], 0.06 * k, j[J.el(s)], 0.05 * k, 'arm']);
    }
    return out;
  }

  /* ------------------------------------------------------------------ drawing */

  /**
   * Draw the character. face: expression values (see head.js). lit: colour grade (core.makeGrade).
   * o: { light: [x, y] towards the light in the picture, rim: colour or null, shadow: draw a ground shadow (true), ground: y }
   */
  draw(g, cam, pose, face, lit, o = {}) {
    const d = this.d, j = pose.j, F = this.frame(pose), k = F.k, b = d.build;
    const zc = cam.toCam(F.pel)[2];
    if (zc < 0.15) return;
    const ppm = cam.ppm(zc), hpx = pose.height * ppm;
    const lw = clamp(0.0052 * ppm * d.inkScale, 0.9, 4.2), ink = lit(d.ink);
    const light = o.light ?? [-0.6, -0.8];
    const P = (p) => cam.project(p);
    const zOf = (p) => cam.toCam(p)[2];
    const toCam = V.norm(V.sub(cam.pos, F.shc));
    const tiny = hpx < 46;
    const skin = lit(d.skin), skinSh = lit(d.skinShade ?? shadeHex(d.skin, -0.2));
    const C = (c) => lit(c), S = (c, s) => lit(s ?? shadeHex(c, -0.26));
    // far figures are painted flat; nearer ones get their shadow side, and a rim of light when the sun is behind
    const lod = hpx >= 330 ? 0 : hpx >= 120 ? 1 : 2;
    const rim = o.rim && lod < 2 ? lit(o.rim) : null;
    const celOpt = (fill, shd, amt) => ({ fill, shade: lod === 2 ? null : shd, ink, lw, light, amt, rim, rimW: Math.max(1.5, lw * 0.9) });

    // ---- ground shadow
    if (o.shadow !== false) {
      const gy = o.ground ?? 0, fl = j['foot.L'], fr = j['foot.R'];
      const c0 = P([(fl[0] + fr[0]) / 2, gy, (fl[2] + fr[2]) / 2]);
      if (c0[2] > 0.2) {
        const r = (0.22 * k + V.dist(fl, fr) * 0.5) * cam.ppm(c0[2]);
        const squash = clamp(Math.abs(cam.F[1]) * 1.15 + 0.1, 0.12, 1);
        g.save(); g.globalAlpha = clamp(o.shadowAlpha ?? 0.3) * clamp(1.4 - (F.pel[1] - gy) / (pose.height * 0.9)); g.fillStyle = o.shadowColor ?? '#0a0814';
        g.beginPath(); g.ellipse(c0[0], c0[1], r * 1.25, r * squash * 0.8, 0, 0, Math.PI * 2); g.fill(); g.restore();
      }
    }

    const groups = []; // { z: depth (bigger = further), draw() }
    const add = (z, draw) => groups.push({ z, draw });

    // ---- sizes
    const armR = { sh: 0.05 * k * b.arm, el: 0.04 * k * b.arm, wr: 0.029 * k * b.arm };
    const legR = { hip: 0.083 * k * b.leg, knee: 0.056 * k * b.leg, ank: 0.039 * k * b.leg };
    const sleeve = d.top.sleeve, cloth = 0.011 * k;

    // ---- torso sections (bottom → top)
    const mixS = V.norm(V.add(F.sP, F.sC));
    const waistC = V.lerp(j.spine05, j.spine03, 0.5);
    const fem = b.female ? 1 : 0;
    const sec = [
      { c: V.add(F.pel, V.mul(F.up, -0.08 * k)), s: F.sP, a: F.hipHalf * 0.5 + 0.035 * k, b: 0.075 * k },
      { c: V.add(F.pel, V.mul(F.up, -0.005 * k)), s: F.sP, a: F.hipHalf + (0.07 + 0.012 * fem) * k * b.hip, b: 0.108 * k },
      { c: waistC, s: mixS, a: (F.hipHalf + (0.05 - 0.018 * fem) * k) * b.waist, b: 0.092 * k * b.waist },
      { c: j.spine02, s: F.sC, a: F.shHalf * 0.84 + 0.012 * k, b: (0.112 + 0.012 * fem) * k * b.chest },
      { c: V.add(F.shc, V.mul(F.up, -0.03 * k)), s: F.sC, a: (F.shHalf + 0.022 * k) * b.shoulder, b: 0.098 * k * b.chest },
      { c: V.add(j.neck01, V.mul(F.up, 0.012 * k)), s: F.sC, a: 0.066 * k * b.neck, b: 0.062 * k * b.neck },
    ];
    for (const s of sec) s.f = V.cross(s.s, F.up);
    /** the two outline points of a section as the lens sees it; grow = extra thickness (cloth) */
    const ext = (s, grow = 0) => {
      const view = V.norm(V.sub(s.c, cam.pos));
      let m = V.cross(view, F.up); const ml = V.len(m); m = ml < 1e-4 ? cam.R : V.mul(m, 1 / ml);
      const a = s.a + grow, bb = s.b + grow, sm = V.dot(s.s, m), fm = V.dot(s.f, m), n = Math.hypot(a * sm, bb * fm) || 1e-6;
      const off = V.add(V.mul(s.s, (a * a * sm) / n), V.mul(s.f, (bb * bb * fm) / n));
      return [P(V.add(s.c, off)), P(V.sub(s.c, off))];
    };
    /** a point on the garment's surface: section index (may be fractional), angle from the front, growth */
    const onSurf = (si, ph, grow = 0) => {
      const i0 = Math.floor(clamp(si, 0, sec.length - 1.001)), u = si - i0, A = sec[i0], B = sec[i0 + 1];
      const c = V.lerp(A.c, B.c, u), s = V.norm(V.lerp(A.s, B.s, u)), f = V.cross(s, F.up), a = lerp(A.a, B.a, u) + grow, bb = lerp(A.b, B.b, u) + grow;
      const p = V.add(c, V.add(V.mul(s, Math.sin(ph) * a), V.mul(f, Math.cos(ph) * bb)));
      const n = V.norm(V.add(V.mul(s, Math.sin(ph) / a), V.mul(f, Math.cos(ph) / bb)));
      return { p, vis: V.dot(n, V.norm(V.sub(cam.pos, p))) };
    };
    const torsoPath = (grow, from = 0) => {
      const Ls = [], Rs = [];
      for (let i = from; i < sec.length; i++) { const e = ext(sec[i], grow * (i === sec.length - 1 ? 0.5 : 1)); Ls.push(e[0]); Rs.push(e[1]); }
      return Ls.concat(Rs.reverse());
    };

    // ---- legs (and shoes)
    const legGroup = (s) => {
      const H = j[J.hip(s)], K = j[J.knee(s)], A = j[J.ank(s)], Bl = j[J.ball(s)], T = j[J.toe(s)];
      const h = P(H), kk = P(K), a = P(A);
      const pants = d.bottom.type === 'pants' || d.bottom.type === 'shorts';
      const short = d.bottom.type === 'shorts';
      const col = pants ? C(d.bottom.color) : skin, colS = pants ? S(d.bottom.color, d.bottom.shade) : skinSh, gr = pants ? cloth * (d.bottom.loose ?? 1) : 0;
      const sc = (z) => cam.ppm(z);
      // foot: heel → ball → toe as capsules, a little below the ankle joint
      const fdir = V.norm(V.sub(Bl, A)); let dn = V.cross(V.cross(fdir, [0, 1, 0]), fdir); dn = V.len(dn) < 1e-4 ? [0, -1, 0] : V.norm(dn); if (dn[1] > 0) dn = V.mul(dn, -1);
      const heel = P(V.add(V.add(A, V.mul(fdir, -0.045 * k)), V.mul(dn, 0.052 * k))), ballS = P(V.add(Bl, V.mul(dn, 0.018 * k))), tipS = P(V.add(T, V.mul(dn, 0.008 * k)));
      const shoe = d.shoes, bootH = shoe?.boot ?? 0;
      const footPath = (q) => { capsule(q, a[0], a[1], legR.ank * 1.02 * sc(a[2]), heel[0], heel[1], 0.04 * k * sc(heel[2])); capsule(q, heel[0], heel[1], 0.04 * k * sc(heel[2]), ballS[0], ballS[1], 0.038 * k * sc(ballS[2])); capsule(q, ballS[0], ballS[1], 0.038 * k * sc(ballS[2]), tipS[0], tipS[1], 0.027 * k * sc(tipS[2])); };
      const legPath = (q) => { capsule(q, h[0], h[1], (legR.hip + gr) * sc(h[2]), kk[0], kk[1], (legR.knee + gr) * sc(kk[2])); capsule(q, kk[0], kk[1], (legR.knee + gr) * sc(kk[2]), a[0], a[1], (legR.ank + gr * 0.8) * sc(a[2])); };
      const amt = legR.knee * sc(kk[2]) * 0.55;
      // the outline of a leg: its long sides, the outside of the knee, the ankle; no rings where thigh and shin overlap
      const legInk = (q, g2 = gr) => { capsuleSides(q, h[0], h[1], (legR.hip + g2) * sc(h[2]), kk[0], kk[1], (legR.knee + g2) * sc(kk[2])); capsuleOpen(q, kk[0], kk[1], (legR.knee + g2) * sc(kk[2]), a[0], a[1], (legR.ank + g2 * 0.8) * sc(a[2])); jointArc(q, h[0], h[1], kk[0], kk[1], a[0], a[1], (legR.knee + g2) * sc(kk[2])); };
      if (short) {
        cel(g, (q) => { capsule(q, kk[0], kk[1], legR.knee * sc(kk[2]), a[0], a[1], legR.ank * sc(a[2])); capsule(q, h[0], h[1], legR.hip * sc(h[2]), kk[0], kk[1], legR.knee * sc(kk[2])); }, { ...celOpt(skin, skinSh, amt), inkPath: (q) => legInk(q, 0) });
        const m = V.lerp(H, K, 0.62), mp = P(m);
        cel(g, (q) => capsule(q, h[0], h[1], (legR.hip + gr) * sc(h[2]), mp[0], mp[1], (lerp(legR.hip, legR.knee, 0.62) + gr * 1.5) * sc(mp[2])), celOpt(col, colS, amt));
      } else cel(g, legPath, { ...celOpt(col, colS, amt), inkPath: legInk });
      if (shoe) {
        const fc = C(shoe.color), fs = S(shoe.color, shoe.shade);
        if (bootH > 0) { const bt = P(V.lerp(A, K, bootH)); cel(g, (q) => { capsule(q, bt[0], bt[1], (lerp(legR.ank, legR.knee, bootH) + gr + 0.012 * k) * sc(bt[2]), a[0], a[1], (legR.ank + gr + 0.01 * k) * sc(a[2])); footPath(q); }, celOpt(fc, fs, amt)); }
        else cel(g, footPath, celOpt(fc, fs, amt * 0.7));
      } else cel(g, footPath, celOpt(skin, skinSh, amt * 0.7));
      if (d.armor?.greave && !tiny) { const g0 = P(V.lerp(K, A, 0.12)), g1 = P(V.lerp(K, A, 0.86)); cel(g, (q) => capsule(q, g0[0], g0[1], (legR.knee + gr + 0.014 * k) * sc(g0[2]), g1[0], g1[1], (legR.ank + gr + 0.016 * k) * sc(g1[2])), celOpt(C(d.armor.greave), S(d.armor.greave), amt)); }
    };

    // ---- arms (sleeves, hands, what the hand holds)
    const upperArm = (s) => {
      const Sh = j[J.sh(s)], E = j[J.el(s)], a = P(Sh), e = P(E), sc = (z) => cam.ppm(z);
      const bare = sleeve === 'none' || d.top.type === 'bare';
      const gr = bare ? 0 : cloth * (sleeve === 'wide' ? 1.6 : 1);
      const col = bare ? skin : C(d.top.sleeveColor ?? d.top.color), colS = bare ? skinSh : S(d.top.sleeveColor ?? d.top.color, d.top.shade);
      const amt = armR.el * sc(e[2]) * 0.6;
      if (sleeve === 'short' && !bare) {
        cel(g, (q) => capsule(q, a[0], a[1], armR.sh * sc(a[2]), e[0], e[1], armR.el * sc(e[2])), { ...celOpt(skin, skinSh, amt), inkPath: (q) => { capsuleSides(q, a[0], a[1], armR.sh * sc(a[2]), e[0], e[1], armR.el * sc(e[2])); const w = P(j[J.wr(s)]); jointArc(q, a[0], a[1], e[0], e[1], w[0], w[1], armR.el * sc(e[2])); } });
        const m = P(V.lerp(Sh, E, 0.55));
        cel(g, (q) => capsule(q, a[0], a[1], (armR.sh + gr) * sc(a[2]), m[0], m[1], (lerp(armR.sh, armR.el, 0.55) + gr * 1.6) * sc(m[2])), celOpt(col, colS, amt));
      } else {
        // the top of the arm belongs to the shoulder (see torso): start a little way down and leave that end open
        const u0 = 0.2, m = P(V.lerp(Sh, E, u0)), r0 = (lerp(armR.sh, armR.el, u0) + gr) * sc(m[2]), r1 = (armR.el + gr) * sc(e[2]);
        cel(g, (q) => capsule(q, m[0], m[1], r0, e[0], e[1], r1), { ...celOpt(col, colS, amt), inkPath: (q) => { capsuleSides(q, m[0], m[1], r0, e[0], e[1], r1); const w = P(j[J.wr(s)]); jointArc(q, m[0], m[1], e[0], e[1], w[0], w[1], r1); } });
      }
      if (d.armor?.pauldron && !tiny) {
        const c0 = P(V.add(Sh, V.mul(F.up, 0.012 * k))), r = (armR.sh + 0.036 * k) * sc(c0[2]);
        cel(g, (q) => { q.moveTo(c0[0] + r * 1.12, c0[1]); q.ellipse(c0[0], c0[1], r * 1.12, r * 0.92, 0, 0, Math.PI * 2); q.closePath(); }, celOpt(C(d.armor.pauldron), S(d.armor.pauldron), r * 0.35));
      }
    };
    const foreArm = (s) => {
      const E = j[J.el(s)], Wr = j[J.wr(s)], e = P(E), w = P(Wr), sc = (z) => cam.ppm(z);
      const bare = sleeve === 'none' || sleeve === 'short' || d.top.type === 'bare';
      const gr = bare ? 0 : cloth;
      const col = bare ? skin : C(d.top.sleeveColor ?? d.top.color), colS = bare ? skinSh : S(d.top.sleeveColor ?? d.top.color, d.top.shade);
      const amt = armR.el * sc(e[2]) * 0.6;
      // a shield seen from behind lies under the arm that carries it
      const shF = s === 'L' ? this.shieldFacing(cam, pose) : null;
      if (shF === false) drawShield(g, cam, pose.props.shield, this.d, k, lit, { ink, lw, light }, false);
      // what the hand holds goes under the fingers
      this.drawHeld(g, cam, pose, s, lit, { ink, lw, light, pole: o.pole, ground: o.ground });
      if (sleeve === 'wide' && !bare) {
        // a wide sleeve: the cloth hangs from the forearm and swings
        const sl = this.sleeve[s] ?? (this.sleeve[s] = { p: V.add(Wr, [0, -0.2 * k, 0]), v: [0, 0, 0], t: this.t });
        const wd = (d.top.sleeveDrop ?? 0.2) * k;
        const want = V.add(V.lerp(E, Wr, 0.85), [0, -wd, 0]);
        // critically damped follower with gravity: lags, swings, settles
        const dt = clamp(this.t - sl.t, 0, 0.05); sl.t = this.t;
        if (V.dist(sl.p, want) > 0.5) { sl.p = want; sl.v = [0, 0, 0]; }
        const kq = 90, cq = 9;
        sl.v = V.add(sl.v, V.mul(V.sub(V.mul(V.sub(want, sl.p), kq), V.mul(sl.v, cq)), dt)); sl.p = V.add(sl.p, V.mul(sl.v, dt));
        const hang = P(sl.p), et = P(V.add(E, [0, (armR.el + gr) , 0]));
        void et;
        const re = (armR.el + gr * 1.5) * sc(e[2]), rw2 = (armR.wr + gr * 2.2) * sc(w[2]);
        const dx = w[0] - e[0], dy = w[1] - e[1], L = Math.hypot(dx, dy) || 1, nx = -dy / L, ny = dx / L;
        const sgn = (hang[0] - w[0]) * nx + (hang[1] - w[1]) * ny > 0 ? 1 : -1;
        const pts = [[e[0] - nx * sgn * re, e[1] - ny * sgn * re], [w[0] - nx * sgn * rw2, w[1] - ny * sgn * rw2], [w[0] + nx * sgn * rw2 * 0.6 + dx / L * rw2 * 0.4, w[1] + ny * sgn * rw2 * 0.6 + dy / L * rw2 * 0.4], [hang[0] + dx / L * rw2 * 0.9, hang[1] + dy / L * rw2 * 0.9], [hang[0] - dx / L * rw2 * 1.2, hang[1] - dy / L * rw2 * 1.2], [e[0] + nx * sgn * re, e[1] + ny * sgn * re]];
        cel(g, (q) => { smoothClosed(q, pts, 0.55); capsule(q, e[0], e[1], re, e[0], e[1], re); }, celOpt(col, colS, amt));
        // the sleeve's opening
        if (!tiny) { g.beginPath(); g.ellipse((w[0] + hang[0]) / 2 + dx / L * rw2 * 0.5, (w[1] + hang[1]) / 2 + dy / L * rw2 * 0.5, Math.max(2, rw2 * 0.55), Math.hypot(hang[0] - w[0], hang[1] - w[1]) * 0.52, Math.atan2(hang[1] - w[1], hang[0] - w[0]) + Math.PI / 2, 0, 7); g.fillStyle = S(d.top.color, shadeHex(d.top.color, -0.5)); g.fill(); }
      } else { const a = P(j[J.sh(s)]), re = (armR.el + gr) * sc(e[2]); cel(g, (q) => capsule(q, e[0], e[1], re, w[0], w[1], (armR.wr + gr * 0.9) * sc(w[2])), { ...celOpt(col, colS, amt), inkPath: (q) => { capsuleOpen(q, e[0], e[1], re, w[0], w[1], (armR.wr + gr * 0.9) * sc(w[2])); jointArc(q, a[0], a[1], e[0], e[1], w[0], w[1], re); } }); }
      if (d.armor?.bracer && !tiny) { const b0 = P(V.lerp(E, Wr, 0.3)), b1 = P(V.lerp(E, Wr, 0.95)); cel(g, (q) => capsule(q, b0[0], b0[1], (lerp(armR.el, armR.wr, 0.3) + 0.016 * k) * sc(b0[2]), b1[0], b1[1], (armR.wr + 0.016 * k) * sc(b1[2])), celOpt(C(d.armor.bracer), S(d.armor.bracer), amt)); }
      this.drawHand(g, cam, pose, s, F, { skin, skinSh, ink, lw, light, tiny, rim, glove: d.top.glove ? [C(d.top.glove), S(d.top.glove)] : null });
      if (shF === true) drawShield(g, cam, pose.props.shield, this.d, k, lit, { ink, lw, light }, true);
    };

    // ---- torso (bare body or garment), neck, sash, collar
    const torso = () => {
      const bare = d.top.type === 'bare';
      const col = bare ? skin : C(d.top.color), colS = bare ? skinSh : S(d.top.color, d.top.shade);
      const gr = bare ? 0 : cloth * 1.3;
      const wpx = sec[3].a * ppm;
      let pts = torsoPath(gr);
      // a short hem that follows the thighs
      const hem = bare ? 0 : (d.top.hem ?? 0) * pose.height;
      if (hem > 0.01) {
        const tl = V.add(F.hipL, V.mul(V.norm(V.sub(j['lowerleg01.L'], F.hipL)), hem)), tr = V.add(F.hipR, V.mul(V.norm(V.sub(j['lowerleg01.R'], F.hipR)), hem));
        const hc = V.mid(tl, tr), hs0 = V.sub(tl, tr), hl = V.len(V.perp(hs0, F.up));
        const hsec = { c: hc, s: hl > 0.02 ? V.norm(V.perp(hs0, F.up)) : F.sP, a: hl / 2 + legR.hip * 0.95 + 0.03 * k, b: 0.118 * k + 0.02 * k };
        hsec.f = V.cross(hsec.s, F.up);
        const e = ext(hsec, gr), n = pts.length / 2;
        const Ls = [e[0]].concat(pts.slice(1, n)), Rs = pts.slice(n, pts.length - 1).concat([e[1]]);
        pts = Ls.concat(Rs);
      }
      // the shoulders: the top of each arm is part of the body's shape, so there is no seam where it joins
      const stubs = [];
      if (sleeve !== 'short' || bare) for (const s of SIDES) { const Sh = j[J.sh(s)], E = j[J.el(s)], a = P(Sh), m = P(V.lerp(Sh, E, 0.42)); const g2 = (sleeve === 'none' || bare) ? 0 : cloth * (sleeve === 'wide' ? 1.6 : 1); if (bare || sleeve !== 'none') stubs.push([a[0], a[1], (armR.sh + g2) * cam.ppm(a[2]), m[0], m[1], (lerp(armR.sh, armR.el, 0.42) + g2) * cam.ppm(m[2])]); }
      const path = (q) => { smoothClosed(q, pts, 0.72); for (const st of stubs) capsule(q, ...st); };
      cel(g, path, celOpt(col, colS, wpx * 0.34));
      if (tiny) return;
      // armour: a breastplate over the chest
      if (d.armor?.plate) {
        const pp = []; for (let i = 0; i <= 12; i++) { const ph = -1.35 + (2.7 * i) / 12; const a = onSurf(4.25, ph, gr + 0.012 * k), bq = onSurf(1.75, ph, gr + 0.012 * k); if (a.vis > -0.05) pp.push([P(a.p), P(bq.p)]); }
        if (pp.length > 2) { const up = pp.map((x) => x[0]), lo = pp.map((x) => x[1]).reverse(); cel(g, (q) => { smoothOpen(q, up, 1); smoothOpen(q, lo, 1, false); q.closePath(); }, celOpt(C(d.armor.plate), S(d.armor.plate), wpx * 0.3)); }
      }
      // collar: a crossed (kimono) neckline showing the layer under it, or a round one
      if (!bare && d.top.collar === 'v') {
        const lineA = [], lineB = [];
        for (let i = 0; i <= 10; i++) { const u = i / 10, si = 4.95 - u * 2.9; const a = onSurf(si, lerp(0.5, -0.42, Math.pow(u, 0.85)), gr + 0.004); if (a.vis > 0.05) lineA.push(P(a.p)); }
        for (let i = 0; i <= 5; i++) { const u = i / 5, si = 4.95 - u * 1.05; const bq = onSurf(si, lerp(-0.5, 0.2, Math.pow(u, 0.9)), gr + 0.004); if (bq.vis > 0.05) lineB.push(P(bq.p)); }
        if (lineA.length > 4 && lineB.length > 2) {
          g.beginPath(); smoothOpen(g, lineB, 1); smoothOpen(g, lineA.slice(0, 5).reverse(), 1, false); g.closePath();
          g.fillStyle = d.top.inner ? C(d.top.inner) : skin; g.fill();
        }
        // the collar's bands: the wearer's right one first, the left one over it
        const bw = d.top.trim ? (d.top.trimW ?? 0.024) * k * ppm : 0;
        g.lineCap = 'butt'; g.lineJoin = 'round';
        for (const ln of [lineB, lineA]) {
          if (ln.length < 3) continue;
          g.beginPath(); smoothOpen(g, ln, 1);
          if (bw > 1.5) { g.lineWidth = bw + lw * 1.6; g.strokeStyle = ink; g.stroke(); g.lineWidth = bw; g.strokeStyle = C(d.top.trim); g.stroke(); }
          else { g.lineWidth = lw * 0.9; g.strokeStyle = bw > 0 ? C(d.top.trim) : ink; g.stroke(); }
        }
      }
      // sash / belt: a band around the waist
      if (d.sash) {
        const w2 = (d.sash.w ?? 0.045) * pose.height / 1.75, upP = [], loP = [];
        const base = 1.9;
        for (let i = 0; i <= 18; i++) { const ph = -Math.PI + (2 * Math.PI * i) / 18; const a = onSurf(base + w2 / 0.14, ph, gr + 0.008 * k), bq = onSurf(base - w2 / 0.14, ph, gr + 0.008 * k); if (a.vis > -0.12) { upP.push(P(a.p)); loP.push(P(bq.p)); } else if (upP.length) { /* the visible run is contiguous in screen order */ } }
        // order the visible run left → right on screen
        const idx = upP.map((_, i) => i).sort((x, y) => upP[x][0] - upP[y][0]); const U = idx.map((i) => upP[i]), Lo = idx.map((i) => loP[i]);
        if (U.length > 3) { cel(g, (q) => { smoothOpen(q, U, 1); smoothOpen(q, [...Lo].reverse(), 1, false); q.closePath(); }, celOpt(C(d.sash.color), S(d.sash.color), wpx * 0.2)); }
      }
    };
    const neck = () => {
      const a = P(V.add(j.neck01, V.mul(F.up, -0.01 * k))), h = P(V.lerp(j.head, F.head.c, 0.25)), sc = (z) => cam.ppm(z);
      const r = 0.056 * k * b.neck;
      const path = (q) => capsule(q, a[0], a[1], r * 1.08 * sc(a[2]), h[0], h[1], r * sc(h[2]));
      cel(g, path, celOpt(skin, skinSh, r * sc(a[2]) * 0.9));
      // the head's shadow on the neck
      if (!tiny) { const m = [lerp(h[0], a[0], 0.52), lerp(h[1], a[1], 0.52)]; g.save(); g.beginPath(); path(g); g.clip(); g.beginPath(); capsule(g, h[0], h[1], r * 1.6 * sc(h[2]), m[0], m[1], r * 1.6 * sc(h[2])); g.fillStyle = skinSh; g.fill(); g.restore(); }
      // armour: a steel collar round the base of the neck
      if (d.armor?.plate && d.armor.gorget !== false) { const u = d.armor.gorget ?? 0.5, m = [lerp(a[0], h[0], u), lerp(a[1], h[1], u)], lo = [lerp(a[0], h[0], -0.12), lerp(a[1], h[1], -0.12)]; cel(g, (q) => capsule(q, lo[0], lo[1], r * 1.5 * sc(a[2]), m[0], m[1], r * 1.22 * sc(a[2])), celOpt(C(d.armor.plate), S(d.armor.plate), r * sc(a[2]) * 0.8)); }
    };

    // ---- cloth: cape and skirt as panels between threads, far panels first
    const clothPanels = (cl, colOut, colIn, which /* 'far' | 'near' | 'all' */, outSign) => {
      const cols = cl.cols, rows = cl.rows, last = cl.closed ? cols : cols - 1;
      if (lod > 0 && !cl.closed && which === 'all') {
        // from a distance a cape is one shape: its outline, and the side the lens sees
        const edge = [];
        for (let c = 0; c < cols; c++) edge.push(P(cl.p[c][0]));
        for (let r = 1; r < rows; r++) edge.push(P(cl.p[cols - 1][r]));
        for (let c = cols - 2; c >= 0; c--) edge.push(P(cl.p[c][rows - 1]));
        for (let r = rows - 2; r >= 1; r--) edge.push(P(cl.p[0][r]));
        const mc = Math.floor(cols / 2), mr = Math.floor(rows / 2), n = V.cross(V.sub(cl.p[mc + 1][mr], cl.p[mc][mr]), V.sub(cl.p[mc][mr + 1], cl.p[mc][mr]));
        const out = V.dot(V.mul(n, outSign), V.sub(cam.pos, cl.p[mc][mr])) > 0;
        g.beginPath(); smoothClosed(g, edge, 0.5); g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = C(out ? colOut : colIn); g.fill();
        return;
      }
      const quads = [];
      for (let c = 0; c < last; c++) for (let r = 0; r < rows - 1; r++) {
        const c2 = (c + 1) % cols, p00 = cl.p[c][r], p10 = cl.p[c2][r], p01 = cl.p[c][r + 1], p11 = cl.p[c2][r + 1];
        const n = V.norm(V.cross(V.sub(p10, p00), V.sub(p01, p00)));
        const mid = [(p00[0] + p10[0] + p01[0] + p11[0]) / 4, (p00[1] + p10[1] + p01[1] + p11[1]) / 4, (p00[2] + p10[2] + p01[2] + p11[2]) / 4];
        const fc = V.dot(V.mul(n, outSign), V.norm(V.sub(cam.pos, mid)));
        if (which === 'far' && fc > 0) continue;
        if (which === 'near' && fc <= 0) continue;
        quads.push({ s: [P(p00), P(p10), P(p11), P(p01)], z: zOf(mid), out: fc > 0, n: V.mul(n, outSign * (fc > 0 ? 1 : -1)) });
      }
      if (!quads.length) return;
      quads.sort((x, y) => y.z - x.z);
      g.beginPath(); for (const q of quads) poly(g, q.s);
      g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke();
      const lightW = V.norm(V.add(V.add(V.mul(cam.R, light[0]), V.mul(cam.U, -light[1])), V.mul(cam.F, -0.45)));
      for (const q of quads) {
        const base = q.out ? colOut : colIn, lit2 = V.dot(q.n, lightW) > 0.02;
        const col = lit2 ? C(base) : S(base);
        g.beginPath(); poly(g, q.s); g.fillStyle = col; g.fill(); g.lineWidth = 1; g.strokeStyle = col; g.stroke();
      }
    };

    // ---- assemble in depth order
    const zT = zOf(F.shc), zP = zOf(F.pel);
    // legs go under the garment; the far leg first
    const zl = { L: zOf(j['lowerleg01.L']) * 0.5 + zOf(j['foot.L']) * 0.5, R: zOf(j['lowerleg01.R']) * 0.5 + zOf(j['foot.R']) * 0.5 };
    const hasSkirt = !!this.skirt && (d.bottom.type === 'skirt' || d.bottom.type === 'robe');
    if (hasSkirt) add(1e6, () => clothPanels(this.skirt, d.bottom.color, d.bottom.lining ?? shadeHex(d.bottom.color, -0.4), 'far', 1));
    for (const s of SIDES) add(1e5 + zl[s], () => legGroup(s));
    if (hasSkirt) add(1e4, () => clothPanels(this.skirt, d.bottom.color, d.bottom.lining ?? shadeHex(d.bottom.color, -0.4), 'near', 1));
    // which side of the body each arm is on (with memory, so it never flickers)
    const sideOf = (key, m, h = 0.035) => { let v = this.front[key]; if (v === null) v = m > 0; else if (m > h) v = true; else if (m < -h) v = false; this.front[key] = v; return v; };
    const base = 5000;
    add(base, torso);
    add(base + 1, neck);
    for (const s of SIDES) {
      const zs = zOf(j[J.sh(s)]), ze = zOf(j[J.el(s)]), zw = zOf(j[J.wr(s)]);
      const upF = sideOf('arm' + s, (zT - zs) * 1.0 + (zT - ze) * 0.5);
      const foF = sideOf('fore' + s, (zT - ze) * 0.45 + (zT - zw) * 0.75 + (upF ? 0.02 : -0.02));
      add(upF ? base - 20 - (zT - ze) : base + 20 + (ze - zT), () => upperArm(s));
      add(foF ? base - 30 - (zT - zw) : base + 30 + (zw - zT), () => foreArm(s));
    }
    // cape: behind the body when we see the front, over it when we see the back
    if (this.cape && d.cape) {
      const capeFront = sideOf('cape', -V.dot(F.fC, toCam), 0.12);
      add(capeFront ? base - 200 : base + 200, () => clothPanels(this.cape, d.cape.color, d.cape.lining ?? d.cape.color, 'all', -1));
    }
    // scabbard and anything worn at the hip
    this.addWorn(add, g, cam, pose, lit, { ink, lw, light, base, zT, zOf, F });
    // head: after the neck; hair that hangs long goes behind or over the back
    const H = F.head, hz = zOf(H.c);
    const tufts = this.tuftShapes(cam, F);
    const headFront = V.dot(H.f, V.norm(V.sub(cam.pos, H.c)));
    const longBack = tufts.filter((t) => t.body), short = tufts.filter((t) => !t.body);
    if (longBack.length) {
      const tailFront = sideOf('tail', -headFront, 0.15);
      add(tailFront ? base - 150 : base + 150, () => { for (const tf of longBack) drawTuftBody(g, tf, C(d.hair.color), S(d.hair.color, d.hair.shade), ink, lw); });
    }
    add(Math.min(base - 2, base - 2 - (zT - hz) * 0 - 60), () => drawHead(g, cam, H, d, this.hd, face ?? {}, lit, { ink, lw, light, tufts: short, sway: this.lockState ? this.lockState.map((q) => q?.off) : null }));
    // arms raised in front of the face must come after the head: the sort key above already puts near arms last
    groups.sort((x, y) => y.z - x.z);
    for (const gp of groups) gp.draw();
    void zP;
  }

  /** Hair locks as screen shapes. Each: { pts, w0, front, body } */
  tuftShapes(cam, F) {
    const d = this.d, out = [];
    if (!d.hair || !this.strands) return out;
    const H = F.head, view = V.norm(V.sub(cam.pos, H.c)), vl = [V.dot(view, H.s), V.dot(view, H.u), V.dot(view, H.f)];
    const ppm = cam.ppm(cam.toCam(H.c)[2]);
    d.hair.tufts.forEach((tf, i) => {
      const st = this.strands[i], base = tf._base ?? [0, 1, 0];
      const n = V.norm([base[0], base[1] - 0.1, base[2]]);
      const vis = n[0] * vl[0] + n[1] * vl[1] + n[2] * vl[2];
      const pts = st.p[0].map((p) => cam.project(p));
      out.push({ pts, w0: tf.w * H.R * ppm, front: tf.layer === 'front' ? vis > -0.2 : tf.layer === 'back' ? vis > 0.35 : vis > 0.05, body: !!tf.body, pow: tf.pow, flat: tf.flat, bulge: tf.bulge, col: tf.color ? null : null, plain: tf.plain });
    });
    return out;
  }

  /** The hand as a mitten with a thumb, from the wrist, knuckles and fingertips. */
  drawHand(g, cam, pose, s, F, o) {
    const j = pose.j, P = (p) => cam.project(p);
    const W = j[J.wr(s)], I = j[J.k2(s)], M = j[J.k3(s)], Pk = j[J.k5(s)], T = j[J.tip(s)], Th = j[J.thumb(s)];
    const w = P(W), ppm = cam.ppm(w[2]), k = F.k;
    const fill = o.glove ? o.glove[0] : o.skin, shd = o.glove ? o.glove[1] : o.skinSh;
    if (o.tiny || 0.09 * k * ppm < 9) { const m = P(V.lerp(M, T, 0.5)); g.beginPath(); capsule(g, w[0], w[1], 0.03 * k * ppm, m[0], m[1], 0.036 * k * ppm); if (!o.tiny) { g.lineWidth = o.lw * 1.6; g.strokeStyle = o.ink; g.stroke(); } g.fillStyle = fill; g.fill(); return; }
    // widen the knuckle line a little and build finger ends from the middle finger
    const out = V.norm(V.sub(I, Pk)), fd = V.sub(T, M);
    const I2 = V.add(I, V.mul(out, 0.012 * k)), P2 = V.add(Pk, V.mul(out, -0.012 * k));
    const pts = [P(V.add(W, V.mul(out, 0.024 * k))), P(I2), P(V.add(I2, V.mul(fd, 0.93))), P(V.add(T, V.mul(out, 0.006 * k))), P(V.add(V.lerp(M, Pk, 0.55), V.mul(fd, 0.9))), P(V.add(P2, V.mul(fd, 0.72))), P(P2), P(V.add(W, V.mul(out, -0.024 * k)))];
    const tb = V.lerp(W, I, 0.5), thumb = [P(V.add(tb, V.mul(out, 0.012 * k))), P(V.add(V.lerp(tb, Th, 0.6), V.mul(out, 0.014 * k))), P(Th), P(V.lerp(W, I, 0.75))];
    const amt = 0.03 * k * ppm * 0.5;
    cel(g, (q) => { smoothClosed(q, pts, 0.6); smoothClosed(q, thumb, 0.7); }, { fill, shade: shd, ink: o.ink, lw: o.lw * 0.9, light: o.light, amt, rim: o.rim, rimW: 1.5 });
    if (0.09 * k * ppm > 26) {
      // finger lines
      g.lineWidth = o.lw * 0.5; g.strokeStyle = o.ink; g.globalAlpha = 0.6; g.beginPath();
      for (const u of [0.3, 0.55, 0.78]) { const a = P(V.add(V.lerp(I, Pk, u), V.mul(fd, 0.12))), b = P(V.add(V.lerp(I, Pk, u), V.mul(fd, 0.86 - 0.12 * Math.abs(u - 0.4)))); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); }
      g.stroke(); g.globalAlpha = 1;
    }
  }

  /** What a hand holds (sword, spear, staff, bucket): drawn just before the hand so the fingers close over the grip. */
  drawHeld(g, cam, pose, s, lit, o) {
    const pr = pose.props ?? {}, sw = pr.sword, k = pose.height / 1.8, wr = pose.j[J.wr(s)], other = pose.j[J.wr(s === 'L' ? 'R' : 'L')];
    if (sw && swordHand(pose) === s) drawSword(g, cam, sw, this.d, pose.height, lit, o);
    for (const name of ['spear', 'staff']) {
      const q = pr[name]; if (!q || !q.visible) continue;
      if (V.dist(q.pos, wr) > V.dist(q.pos, other)) continue;
      const L = name === 'spear' ? (this.d.spear?.len ?? 2.4) : (this.d.staff?.len ?? 1.7), pm = o.pole ?? this.d[name]?.hold;
      const mix = pm && typeof pm === 'object' ? clamp(pm.planted ?? 1) : 1, mode = pm && typeof pm === 'object' ? (mix > 0.001 ? 'planted' : null) : pm;
      const grip = V.lerp(wr, pose.j[J.k3(s)], 0.6);
      if (mode === 'planted') {
        // the butt rests on the ground and stays there; when the hand has gone too far from it, it is lifted and set down ahead
        const gy = o.ground ?? 0, st = this.pole ?? (this.pole = { p: [grip[0], gy, grip[2]], from: null, t0: -9 });
        const dx = grip[0] - st.p[0], dz = grip[2] - st.p[2], dd = Math.hypot(dx, dz);
        if (dd > 2.5) { st.p = [grip[0], gy, grip[2]]; st.from = null; }
        else if (dd > 0.42 * k && this.t - st.t0 > 0.3) { st.from = [...st.p]; st.p = [grip[0] + dx / dd * 0.36 * k, gy, grip[2] + dz / dd * 0.36 * k]; st.t0 = this.t; }
        const u = clamp((this.t - st.t0) / 0.26), e = u * u * (3 - 2 * u);
        const butt = st.from && u < 1 ? [lerp(st.from[0], st.p[0], e), gy + Math.sin(Math.PI * u) * 0.07 * k, lerp(st.from[2], st.p[2], e)] : st.p;
        const ax = V.norm(V.sub(grip, butt));
        let A = butt, B = V.add(butt, V.mul(ax, L));
        if (mix < 1) { const bx = qrot(q.q, [0, 1, 0]), e2 = mix * mix * (3 - 2 * mix); A = V.lerp(V.add(q.pos, V.mul(bx, -L / 2)), A, e2); B = V.add(A, V.mul(V.norm(V.sub(V.lerp(V.add(q.pos, V.mul(bx, L / 2)), B, e2), A)), L)); }
        drawPole(g, cam, A, B, name, this.d, k, lit, o, [-ax[1], ax[0], 0]);
      } else if (mode === 'carry') {
        // carried upright in the fist: a little sway, the butt clear of the ground
        const ax = V.norm([noise1(this.t * 0.7, 5 + k) * 0.05, 1, noise1(this.t * 0.6, 9 + k) * 0.05]), butt = V.add(grip, V.mul(ax, -1.0 * k));
        drawPole(g, cam, butt, V.add(butt, V.mul(ax, L)), name, this.d, k, lit, o, [1, 0, 0]);
      } else {
        const ax = qrot(q.q, [0, 1, 0]);
        drawPole(g, cam, V.add(q.pos, V.mul(ax, -L / 2)), V.add(q.pos, V.mul(ax, L / 2)), name, this.d, k, lit, o, qrot(q.q, [0, 0, 1]));
      }
    }
    const bk = pr.bucket;
    if (bk && bk.visible && V.dist(bk.pos, wr) <= V.dist(bk.pos, other)) drawBucket(g, cam, wr, this, k, lit, o);
  }
  /** The shield on the left forearm: its face or its back, whichever the lens sees. Returns true when it faces the lens. */
  shieldFacing(cam, pose) { const sh = pose.props?.shield; if (!sh || !sh.visible) return null; return V.dot(qrot(sh.q, [0, 0, 1]), V.sub(cam.pos, sh.pos)) > 0; }

  addWorn(add, g, cam, pose, lit, o) {
    const sc = pose.props?.scabbard, sw0 = pose.props?.sword;
    // a sword that is out but in no hand (planted in the ground, flying) is drawn where it is
    if (sw0 && swordInHand(pose) && !swordHand(pose)) add(o.zOf(sw0.pos) < o.zT ? o.base - 95 : o.base + 95, () => drawSword(g, cam, sw0, this.d, pose.height, lit, o));
    if (sc && sc.visible) {
      const a = sc.pos, b = V.add(sc.pos, V.mul(qrot(sc.q, [0, 1, 0]), 0.78 * pose.height / 1.8));
      const zm = (o.zOf(a) + o.zOf(b)) / 2;
      add(zm < o.zT ? o.base - 90 : o.base + 90, () => {
        const pa = cam.project(a), pb = cam.project(b), r = 0.021 * pose.height / 1.8;
        cel(g, (q) => capsule(q, pa[0], pa[1], r * cam.ppm(pa[2]), pb[0], pb[1], r * 0.9 * cam.ppm(pb[2])), { fill: lit(this.d.sword.scabbard ?? '#201a22'), shade: lit('#0e0b10'), ink: o.ink, lw: o.lw, light: o.light, amt: r * cam.ppm(pa[2]) * 0.6 });
        // the sword's grip shows above the scabbard while it is sheathed
        const sw = pose.props?.sword;
        if (sw && (!sw.visible || !swordInHand(pose))) { const ga = cam.project(V.add(a, V.mul(qrot(sc.q, [0, -1, 0]), 0.2 * pose.height / 1.8))); g.beginPath(); capsule(g, pa[0], pa[1], r * 0.8 * cam.ppm(pa[2]), ga[0], ga[1], r * 0.75 * cam.ppm(ga[2])); g.lineWidth = o.lw * 2; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = lit(this.d.sword.grip); g.fill(); }
      });
    }
  }
}

/** Is the sword out (in a hand, or loose), or riding in its scabbard? */
export function swordInHand(pose) {
  const sw = pose.props?.sword, sc = pose.props?.scabbard;
  if (!sw || !sw.visible) return false;
  if (sc && sc.visible && V.dist(sw.pos, sc.pos) < 0.09 * pose.height / 1.8 && V.dot(qrot(sw.q, [0, 1, 0]), qrot(sc.q, [0, 1, 0])) > 0.96) return false;
  return true;
}
/** Which hand holds the sword: 'L', 'R', or null when it is in neither (thrown, dropped, planted). */
export function swordHand(pose) {
  if (!swordInHand(pose)) return null;
  const sw = pose.props.sword, k = pose.height / 1.8, dl = V.dist(sw.pos, pose.j['wrist.L']), dr = V.dist(sw.pos, pose.j['wrist.R']);
  if (Math.min(dl, dr) > 0.3 * k) return null;
  return dl < dr ? 'L' : 'R';
}

/** A sword from its solved place: grip, guard and a blade that catches the light. */
export function drawSword(g, cam, sw, d, height, lit, o) {
  const k = height / 1.8, ax = qrot(sw.q, [0, 1, 0]), wd = qrot(sw.q, [0, 0, 1]);
  const L = (d.sword.len ?? 0.78) * (d.sword.scale ?? 1);
  const p0 = sw.pos, gd = V.add(p0, V.mul(ax, 0.14)), tip = V.add(p0, V.mul(ax, 0.135 + L)), pm = V.add(p0, V.mul(ax, -0.045));
  const P = (p) => cam.project(p);
  const a = P(pm), b = P(gd), t = P(tip);
  if (a[2] < 0.1 || t[2] < 0.1) return;
  const hw = (d.sword.width ?? 0.02) * k;
  // blade: back edge straight, cutting edge tapering to the point
  const e0 = P(V.add(gd, V.mul(wd, hw))), e1 = P(V.add(gd, V.mul(wd, -hw))), m0 = P(V.add(V.lerp(gd, tip, 0.86), V.mul(wd, hw * 0.92))), m1 = P(V.add(V.lerp(gd, tip, 0.9), V.mul(wd, -hw * 0.85)));
  // seen edge-on a blade is a line: keep a minimum width so it never vanishes
  const minw = Math.max(1.2, 0.006 * k * cam.ppm(b[2]));
  const fat = (q0, q1) => { const dx = q1[0] - q0[0], dy = q1[1] - q0[1], l = Math.hypot(dx, dy); if (l >= minw * 2) return [q0, q1]; const cx = (q0[0] + q1[0]) / 2, cy = (q0[1] + q1[1]) / 2; const bx = t[0] - b[0], by = t[1] - b[1], bl = Math.hypot(bx, by) || 1; return [[cx - (-by / bl) * minw, cy - (bx / bl) * minw], [cx + (-by / bl) * minw, cy + (bx / bl) * minw]]; };
  const [E0, E1] = fat(e0, e1), [M0, M1] = fat(m0, m1);
  g.beginPath(); g.moveTo(E0[0], E0[1]); g.lineTo(M0[0], M0[1]); g.lineTo(t[0], t[1]); g.lineTo(M1[0], M1[1]); g.lineTo(E1[0], E1[1]); g.closePath();
  g.lineJoin = 'round'; g.lineWidth = o.lw * 1.6; g.strokeStyle = o.ink; g.stroke();
  g.fillStyle = lit(d.sword.blade); g.fill();
  // the bevel: one side of the blade a tone darker, and a glint line
  g.beginPath(); g.moveTo((E0[0] + E1[0]) / 2, (E0[1] + E1[1]) / 2); g.lineTo(t[0], t[1]); g.lineTo(M1[0], M1[1]); g.lineTo(E1[0], E1[1]); g.closePath(); g.fillStyle = lit(shadeHex(d.sword.blade, -0.22)); g.fill();
  g.beginPath(); g.moveTo((E0[0] * 0.72 + E1[0] * 0.28), (E0[1] * 0.72 + E1[1] * 0.28)); g.lineTo((M0[0] * 0.7 + M1[0] * 0.3), (M0[1] * 0.7 + M1[1] * 0.3)); g.lineWidth = Math.max(0.8, o.lw * 0.45); g.strokeStyle = '#ffffff'; g.globalAlpha = 0.85; g.stroke(); g.globalAlpha = 1;
  // guard and grip
  const gr = 0.017 * k * cam.ppm(a[2]);
  g.beginPath(); capsule(g, a[0], a[1], gr, b[0], b[1], gr); g.lineWidth = o.lw * 1.6; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = lit(d.sword.grip); g.fill();
  const g0 = P(V.add(gd, V.mul(wd, hw * 2.1))), g1 = P(V.add(gd, V.mul(wd, -hw * 2.1)));
  g.beginPath(); capsule(g, g0[0], g0[1], gr * 0.8, g1[0], g1[1], gr * 0.8); g.lineWidth = o.lw * 1.6; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = lit(d.sword.guard); g.fill();
}

/** Long hair that hangs over the back or shoulders (drawn at body level). */
function drawTuftBody(g, tf, col, sh, ink, lw) {
  const p = tf.pts, n = p.length; if (n < 2) return;
  const left = [], right = [];
  for (let i = 0; i < n; i++) {
    const a = p[Math.max(0, i - 1)], b = p[Math.min(n - 1, i + 1)];
    let dx = b[0] - a[0], dy = b[1] - a[1]; const l = Math.hypot(dx, dy) || 1; dx /= l; dy /= l;
    const t = i / (n - 1), w = tf.w0 * (tf.flat ? 1 - 0.3 * t : Math.pow(1 - t, tf.pow ?? 0.6)) * (tf.bulge ? 1 + tf.bulge * Math.sin(Math.PI * Math.min(1, t * 1.3)) : 1);
    left.push([p[i][0] - dy * w, p[i][1] + dx * w]); right.push([p[i][0] + dy * w, p[i][1] - dx * w]);
  }
  const path = () => { g.beginPath(); smoothOpen(g, left, 1); smoothOpen(g, [...right].reverse(), 1, false); g.closePath(); };
  path(); g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = col; g.fill();
  g.save(); path(); g.clip(); g.beginPath(); smoothOpen(g, left.map((q, i) => [q[0] * 0.3 + right[i][0] * 0.7, q[1] * 0.3 + right[i][1] * 0.7]), 1); g.lineWidth = tf.w0 * 0.5; g.strokeStyle = sh; g.stroke(); g.restore();
}
void noise1;

/** A spear or a staff between two world points (butt A, top of the shaft B); wd = the way the spear's blade is broad. */
export function drawPole(g, cam, A, B, kind, d, k, lit, o, wd) {
  const ax = V.norm(V.sub(B, A)), a = cam.project(A), b = cam.project(B);
  if (a[2] < 0.1 || b[2] < 0.1) return;
  const col = lit(kind === 'spear' ? (d.spear?.shaft ?? '#6b5340') : (d.staff?.color ?? '#7a6248'));
  const ra = Math.max(0.9, 0.016 * k * cam.ppm(a[2])), rb = Math.max(0.9, 0.015 * k * cam.ppm(b[2]));
  g.beginPath(); capsule(g, a[0], a[1], ra, b[0], b[1], rb); g.lineJoin = 'round'; g.lineWidth = o.lw * 1.6; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = col; g.fill();
  if (kind === 'spear') {
    const tip = cam.project(V.add(B, V.mul(ax, 0.34))), w0 = cam.project(V.add(V.add(B, V.mul(ax, 0.1)), V.mul(wd, 0.034))), w1 = cam.project(V.add(V.add(B, V.mul(ax, 0.1)), V.mul(wd, -0.034)));
    // seen edge-on the head is still a sliver
    const dx = tip[0] - b[0], dy = tip[1] - b[1], dl = Math.hypot(dx, dy) || 1, mw = Math.max(1.3, 0.012 * k * cam.ppm(b[2]));
    let P0 = w0, P1 = w1; if (Math.hypot(w0[0] - w1[0], w0[1] - w1[1]) < mw * 2) { const mx = (w0[0] + w1[0]) / 2, my = (w0[1] + w1[1]) / 2; P0 = [mx - dy / dl * mw, my + dx / dl * mw]; P1 = [mx + dy / dl * mw, my - dx / dl * mw]; }
    g.beginPath(); g.moveTo(b[0], b[1]); g.lineTo(P0[0], P0[1]); g.lineTo(tip[0], tip[1]); g.lineTo(P1[0], P1[1]); g.closePath(); g.lineWidth = o.lw * 1.5; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = lit(d.spear?.head ?? '#cfd6de'); g.fill();
    g.beginPath(); g.moveTo(b[0], b[1]); g.lineTo(P1[0], P1[1]); g.lineTo(tip[0], tip[1]); g.closePath(); g.fillStyle = lit(shadeHex(d.spear?.head ?? '#cfd6de', -0.25)); g.fill();
  } else if (d.staff?.knob !== false) {
    g.beginPath(); g.arc(b[0], b[1], rb * 1.9, 0, 7); g.lineWidth = o.lw * 1.5; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = col; g.fill();
  }
}
/** A round shield: its face (boss, rim, a device) or its back (boards and the grip strap). */
export function drawShield(g, cam, pr, d, k, lit, o, face) {
  const R = (d.shield?.r ?? 0.4) * k, up = qrot(pr.q, [0, 1, 0]), sd = qrot(pr.q, [1, 0, 0]), nz = qrot(pr.q, [0, 0, 1]);
  const c0 = V.add(pr.pos, V.mul(nz, face ? 0.02 : 0)), rim = [], inner = [];
  for (let i = 0; i < 20; i++) { const a = (i / 20) * Math.PI * 2, v = V.add(V.mul(sd, Math.cos(a)), V.mul(up, Math.sin(a))); rim.push(cam.project(V.add(c0, V.mul(v, R)))); inner.push(cam.project(V.add(V.add(c0, V.mul(nz, 0.03)), V.mul(v, R * 0.82)))); }
  if (rim.some((q) => q[2] < 0.1)) return;
  const col = d.shield?.color ?? '#7c2a2a', rimC = d.shield?.rim ?? '#9aa2ad', sc = cam.project(V.add(c0, V.mul(nz, 0.07)));
  g.beginPath(); smoothClosed(g, rim, 1); g.lineJoin = 'round'; g.lineWidth = o.lw * 2; g.strokeStyle = o.ink; g.stroke();
  if (face) {
    g.fillStyle = lit(rimC); g.fill();
    g.beginPath(); smoothClosed(g, inner, 1); g.fillStyle = lit(col); g.fill();
    g.save(); g.clip(); g.translate(-o.light[0] * R * cam.ppm(sc[2]) * 0.5, -o.light[1] * R * cam.ppm(sc[2]) * 0.5); g.beginPath(); smoothClosed(g, inner, 1); g.fillStyle = lit(shadeHex(col, -0.25)); g.globalAlpha = 0.55; g.fill(); g.globalAlpha = 1; g.restore();
    g.beginPath(); smoothClosed(g, inner, 1); g.lineWidth = o.lw * 0.8; g.strokeStyle = o.ink; g.stroke();
    const br = Math.max(1.5, R * 0.2 * cam.ppm(sc[2]));
    g.beginPath(); g.arc(sc[0], sc[1], br, 0, 7); g.lineWidth = o.lw * 1.4; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = lit(d.shield?.boss ?? '#c9ced6'); g.fill();
  } else {
    g.fillStyle = lit(shadeHex(d.shield?.back ?? '#6a5240', 0)); g.fill();
    g.save(); g.clip(); g.beginPath(); for (let i = -2; i <= 2; i++) { const a = cam.project(V.add(V.add(c0, V.mul(sd, i * R * 0.36)), V.mul(up, R))), b = cam.project(V.add(V.add(c0, V.mul(sd, i * R * 0.36)), V.mul(up, -R))); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); } g.lineWidth = o.lw * 0.7; g.strokeStyle = lit(shadeHex(d.shield?.back ?? '#6a5240', -0.3)); g.stroke(); g.restore();
  }
}
/** A bucket hanging from a hand by its handle. */
function drawBucket(g, cam, wr, ch, k, lit, o) {
  const st = ch.bucket ?? (ch.bucket = { p: V.add(wr, [0, -0.3 * k, 0]), v: [0, 0, 0], t: ch.t });
  const dt = clamp(ch.t - st.t, 0, 0.05); st.t = ch.t;
  const want = V.add(wr, [0, -0.3 * k, 0]);
  if (V.dist(st.p, want) > 0.6) { st.p = want; st.v = [0, 0, 0]; }
  for (let a = 0; a < 3; a++) { st.v[a] += ((want[a] - st.p[a]) * 140 - st.v[a] * 7) * dt; st.p[a] += st.v[a] * dt; }
  // keep the handle's length
  const dv = V.sub(st.p, wr), dl = V.len(dv) || 1; st.p = V.add(wr, V.mul(dv, 0.3 * k / dl));
  const top = cam.project(st.p), w = cam.project(wr), bot = cam.project(V.add(st.p, V.mul(V.norm(V.sub(st.p, wr)), 0.26 * k)));
  if (top[2] < 0.1) return;
  const rt = 0.15 * k * cam.ppm(top[2]), rb = 0.12 * k * cam.ppm(bot[2]), dx = bot[0] - top[0], dy = bot[1] - top[1], dl2 = Math.hypot(dx, dy) || 1, nx = -dy / dl2, ny = dx / dl2;
  g.beginPath(); g.moveTo(top[0] + nx * rt, top[1] + ny * rt); g.quadraticCurveTo(w[0], w[1] - rt * 0.2, top[0] - nx * rt, top[1] - ny * rt); g.lineWidth = o.lw * 1.2; g.strokeStyle = o.ink; g.stroke();
  g.beginPath(); g.moveTo(top[0] + nx * rt, top[1] + ny * rt); g.lineTo(bot[0] + nx * rb, bot[1] + ny * rb); g.lineTo(bot[0] - nx * rb, bot[1] - ny * rb); g.lineTo(top[0] - nx * rt, top[1] - ny * rt); g.closePath();
  g.lineJoin = 'round'; g.lineWidth = o.lw * 2; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = lit('#8a6a4a'); g.fill();
  g.save(); g.clip(); g.fillStyle = lit('#5f4632'); g.fillRect(Math.min(top[0], bot[0]) + (o.light[0] < 0 ? 0 : -rt), Math.min(top[1], bot[1]) - rt, rt * 1.1, dl2 + rt * 2); g.restore();
  g.beginPath(); g.ellipse(top[0], top[1], rt, rt * 0.3, Math.atan2(ny, nx), 0, 7); g.lineWidth = o.lw; g.strokeStyle = o.ink; g.stroke(); g.fillStyle = lit('#3d6a7c'); g.fill();
}
