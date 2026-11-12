// The ranks of an army seen from afar: every soldier a small drawn figure with its own stance, sway and step.
// (Soldiers near the lens are full actors with captured performances; these stand behind them.)
import { V, clamp, lerp, hash, noise1, shade as shadeHex } from './core.js';
import { capsule } from './draw.js';

/**
 * o: { rows: [{ x, z: [z…] }…] | grid: { x0, dx, n, z0, dz, m }, yaw (deg, which way they face), seed,
 *      state(t) → { advance (m moved along their facing), spear (0 upright … 1 levelled), fear (0..1 backing/looking about), speed },
 *      skip(row, col) → true to leave a place empty (an actor stands there), colors: { cloth, steel, dark, skin, shield },
 *      banners: every nth soldier of chosen rows carries one }
 * Returns world items (one per row) for Scene.draw.
 */
export function army(o) {
  const yaw = (o.yaw ?? -90) * Math.PI / 180, fwd = [Math.sin(yaw), 0, Math.cos(yaw)], side = [Math.cos(yaw), 0, -Math.sin(yaw)];
  const C = { cloth: '#7c1f27', steel: '#7b8594', dark: '#2e2a30', skin: '#d9a680', shield: '#7c1f27', wood: '#6b5340', ...(o.colors ?? {}) };
  const rows = o.rows ?? Array.from({ length: o.grid.n }, (_, i) => ({ x: o.grid.x0 + i * o.grid.dx, z: Array.from({ length: o.grid.m }, (_, j) => o.grid.z0 + j * o.grid.dz) }));
  const seed = o.seed ?? 1, stride = 0.72;
  const items = [];
  rows.forEach((row, ri) => {
    const men = [];
    row.z.forEach((z, ci) => {
      if (o.skip && o.skip(ri, ci)) return;
      const h1 = hash(ri * 13.7 + ci * 3.1 + seed), h2 = hash(ri * 5.3 + ci * 9.7 + seed * 2);
      men.push({ x: row.x + (h1 - 0.5) * 0.3, z: z + (h2 - 0.5) * 0.3, k: 0.95 + 0.1 * hash(ri * 2.1 + ci * 7.7 + seed), ph: h1 * 6.28, leg: h2, banner: o.banners && o.banners(ri, ci), tone: hash(ri * 1.3 + ci * 2.9) });
    });
    if (!men.length) return;
    const item = {
      p: (t) => { const st = o.state ? o.state(t) : {}; const a = st.advance ?? 0; return [row.x + fwd[0] * a, 0, (row.z[0] + row.z[row.z.length - 1]) / 2 + fwd[2] * a]; },
      r: 12,
      draw(g, cam, sc, t) {
        const st = o.state ? o.state(t) : {}, adv = st.advance ?? 0, sp = st.spear ?? 0, fear = st.fear ?? 0, L = sc.light2(cam);
        const list = men.map((m) => { const base = [m.x + fwd[0] * adv, 0, m.z + fwd[2] * adv]; return [cam.toCam(base)[2], m, base]; }).filter((q) => q[0] > 0.6).sort((a, b) => b[0] - a[0]);
        for (const [zc, m, base] of list) drawMan(g, cam, sc, t, m, base, zc, { fwd, side, C, adv, sp, fear, L, stride, moving: (st.speed ?? 0) > 0.05 });
      },
    };
    items.push(item);
  });
  return items;
}

function drawMan(g, cam, sc, t, m, base, zc, e) {
  const { fwd, side, C, L } = e, k = m.k, ppm = cam.f / zc, hpx = 1.8 * k * ppm;
  if (hpx < 3 || zc < 2.4) return;
  const d = zc, col = (c) => sc.col(c, d);
  const sway = noise1(t * 0.35 + m.ph * 3, 3) * 0.035 + e.fear * noise1(t * 2.2 + m.ph, 5) * 0.06, bob = e.moving ? Math.abs(Math.sin((e.adv / e.stride) * Math.PI + m.leg * 6.28)) * 0.03 : noise1(t * 0.5 + m.ph, 7) * 0.008;
  const at = (s, y, f) => cam.project([base[0] + side[0] * s + fwd[0] * f, y * k, base[2] + side[2] * s + fwd[2] * f]);
  // feet: planted, or stepping in time with the ground they cover
  const foot = (sgn) => {
    if (!e.moving) return [at(sgn * 0.1, 0.04, sgn * 0.03 - 0.02), 0];
    const u = ((e.adv / (2 * e.stride) + m.leg + (sgn > 0 ? 0 : 0.5)) % 1 + 1) % 1;
    const off = u < 0.5 ? e.stride * (0.5 - u * 2) * 0.5 : e.stride * ((u - 0.5) * 2 - 0.5) * 0.5, lift = u < 0.5 ? 0 : Math.sin((u - 0.5) * 2 * Math.PI) * 0.1;
    return [at(sgn * 0.1, 0.04 + lift, off), lift];
  };
  const [fl] = foot(1), [fr] = foot(-1);
  const hip = at(sway * 0.4, 0.95 + bob, 0), chest = at(sway, 1.38 + bob, 0.02), neck = at(sway * 1.1, 1.52 + bob, 0.02), head = at(sway * 1.2, 1.66 + bob, 0.03);
  const handR = at(-0.26 + sway, 1.02 + bob + e.sp * 0.15, 0.14 + e.sp * 0.2), handL = at(0.24 + sway, 1.12 + bob, 0.22);
  const big = hpx > 60, lw = clamp(hpx * 0.012, 0.6, 2), ink = col('#1a1722');
  const part = (path, fill) => { g.beginPath(); path(); if (big) { g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); } g.fillStyle = fill; g.fill(); };
  const r = (m2) => m2 * k * ppm;
  // which way the man faces relative to the lens: his shield and spear go in front or behind accordingly
  const front = V.dot(fwd, V.sub(cam.pos, base)) > 0;
  const steel = col(m.tone > 0.5 ? C.steel : shadeHex(C.steel, -0.1)), cloth = col(m.tone > 0.3 ? C.cloth : shadeHex(C.cloth, -0.15));
  const spear = () => {
    const ang = e.sp * 1.25 + noise1(t * 0.4 + m.ph * 2, 9) * 0.04, len = 2.4, low = 1.02;
    const dirV = [fwd[0] * Math.sin(ang), Math.cos(ang), fwd[2] * Math.sin(ang)];
    const hx = base[0] + side[0] * (-0.26 + sway) + fwd[0] * (0.14 + e.sp * 0.2), hy = (1.02 + bob + e.sp * 0.15) * k, hz = base[2] + side[2] * (-0.26 + sway) + fwd[2] * (0.14 + e.sp * 0.2);
    const a = cam.project([hx - dirV[0] * low, Math.max(0.02, hy - dirV[1] * low), hz - dirV[2] * low]), b = cam.project([hx + dirV[0] * (len - low), hy + dirV[1] * (len - low), hz + dirV[2] * (len - low)]), tip = cam.project([hx + dirV[0] * (len - low + 0.32), hy + dirV[1] * (len - low + 0.32), hz + dirV[2] * (len - low + 0.32)]);
    if (m.banner) return;
    g.strokeStyle = col(C.wood); g.lineWidth = Math.max(0.8, r(0.03)); g.lineCap = 'round'; g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke();
    g.strokeStyle = col('#cfd6de'); g.lineWidth = Math.max(0.9, r(0.045)); g.beginPath(); g.moveTo(b[0], b[1]); g.lineTo(tip[0], tip[1]); g.stroke();
  };
  const shield = () => {
    const c = at(0.3 + sway, 1.14 + bob, 0.26), rr = r(0.38), sq = clamp(Math.abs(V.dot(fwd, V.norm(V.sub(cam.pos, base)))), 0.28, 1);
    part(() => g.ellipse(c[0], c[1], rr * sq, rr, 0, 0, 7), front ? col('#8a94a3') : col('#5a4638'));
    if (front) { g.beginPath(); g.ellipse(c[0], c[1], rr * sq * 0.82, rr * 0.82, 0, 0, 7); g.fillStyle = col(C.shield); g.fill(); if (hpx > 40) { g.beginPath(); g.arc(c[0], c[1], rr * 0.2, 0, 7); g.fillStyle = col('#c9ced6'); g.fill(); } }
  };
  if (!front) { spear(); shield(); }
  // legs, body, arms, head
  part(() => { capsule(g, hip[0] - r(0.07), hip[1], r(0.085), fl[0], fl[1], r(0.05)); capsule(g, hip[0] + r(0.07), hip[1], r(0.085), fr[0], fr[1], r(0.05)); }, col(C.dark));
  if (hpx > 30) { g.beginPath(); capsule(g, lerp(hip[0] - r(0.07), fl[0], 0.55), lerp(hip[1], fl[1], 0.55), r(0.065), fl[0], fl[1], r(0.055)); capsule(g, lerp(hip[0] + r(0.07), fr[0], 0.55), lerp(hip[1], fr[1], 0.55), r(0.065), fr[0], fr[1], r(0.055)); g.fillStyle = steel; g.fill(); }
  part(() => capsule(g, hip[0], hip[1] + r(0.06), r(0.17), chest[0], chest[1], r(0.2)), cloth);
  if (hpx > 24) { g.beginPath(); capsule(g, lerp(hip[0], chest[0], 0.45), lerp(hip[1], chest[1], 0.45), r(0.16), chest[0], chest[1], r(0.185)); g.fillStyle = steel; g.fill(); if (big) { g.save(); g.clip(); g.fillStyle = col(shadeHex(C.steel, -0.28)); g.fillRect(chest[0] + (L[0] < 0 ? 0.02 : -0.3) * k * ppm, chest[1] - r(0.3), r(0.28), r(0.7)); g.restore(); } }
  part(() => { capsule(g, chest[0] - r(0.2), chest[1] - r(0.06), r(0.06), handR[0], handR[1], r(0.045)); capsule(g, chest[0] + r(0.2), chest[1] - r(0.06), r(0.06), handL[0], handL[1], r(0.045)); }, cloth);
  if (hpx > 24) { g.beginPath(); g.arc(chest[0] - r(0.2), chest[1] - r(0.08), r(0.09), 0, 7); g.arc(chest[0] + r(0.2), chest[1] - r(0.08), r(0.09), 0, 7); g.fillStyle = steel; g.fill(); }
  part(() => capsule(g, neck[0], neck[1], r(0.05), head[0], head[1], r(0.1)), col(C.skin));
  // helmet: a cap over the top of the head
  g.beginPath(); g.arc(head[0], head[1] - r(0.015), r(0.118), Math.PI * 0.97, Math.PI * 2.03); g.closePath(); if (big) { g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); } g.fillStyle = steel; g.fill();
  if (front) { spear(); shield(); }
  if (m.banner) {
    const a = at(-0.26, 0.02, 0.14), b = at(-0.26 + sway * 2, 3.5, 0.14), wv = Math.sin(t * 4 + m.ph) * r(0.12);
    g.strokeStyle = col('#3c3028'); g.lineWidth = Math.max(1, r(0.04)); g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke();
    const w = r(1.1), hh = r(1.5), dir = cam.project([base[0] + (sc.wind ? -1 : 1), 3.5 * k, base[2]])[0] > b[0] ? 1 : -1;
    g.beginPath(); g.moveTo(b[0], b[1]); g.quadraticCurveTo(b[0] + dir * w * 0.5, b[1] + wv, b[0] + dir * w, b[1] + wv * 0.6); g.lineTo(b[0] + dir * w * 0.9, b[1] + hh + wv); g.quadraticCurveTo(b[0] + dir * w * 0.45, b[1] + hh - wv * 0.5, b[0], b[1] + hh); g.closePath();
    g.fillStyle = col(m.banner.color ?? C.cloth); g.fill(); if (big) { g.lineWidth = lw; g.strokeStyle = ink; g.stroke(); }
    g.beginPath(); g.moveTo(b[0] + dir * w * 0.15, b[1] + hh * 0.9); g.lineTo(b[0] + dir * w * 0.9, b[1] + hh * 0.93 + wv); g.lineWidth = Math.max(1, r(0.1)); g.strokeStyle = col(m.banner.trim ?? '#d9b25a'); g.stroke();
  }
}
