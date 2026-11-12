// 2.5D backgrounds: 16 places × day / dusk / night, each a stack of painted layers (sky, far, middle, near, foreground)
// that slide at different speeds when the camera moves — the multiplane camera of hand-made anime, drawn by code.
import { Doc, rng, P, smooth, blob, ellipse, rrect, shade, mix, f } from './svg.js';

const W = 2400, H = 1080;
const TIMES = {
  day: { top: '#5aa6f2', mid: '#a9d6ff', hor: '#e8f6ff', sun: '#fff8e0', glow: '#ffffff', cloud: '#ffffff', cshade: '#c9d8ee', haze: '#dceeff', light: 0.0, ink: '#3a4a5a' },
  dusk: { top: '#3d3a86', mid: '#e8788a', hor: '#ffcf8f', sun: '#fff0c0', glow: '#ffb07a', cloud: '#ffd2b0', cshade: '#b0708e', haze: '#ffc8a0', light: -0.12, ink: '#3a2a3a' },
  night: { top: '#070b22', mid: '#16204a', hor: '#2c3c72', sun: '#f4f1e0', glow: '#9fb4ff', cloud: '#3a4a7a', cshade: '#1c2448', haze: '#2a3866', light: -0.45, ink: '#0c1020' },
};
/** a colour seen through the air: far things take the sky's haze */
const air = (c, T, k) => mix(shade(c, T.light), T.haze, k);
function sky(T, time, seed, o = {}) {
  const d = new Doc(W, H), R = rng(seed);
  d.path(`M0 0H${W}V${H}H0Z`, d.lin([[0, T.top], [0.55, T.mid], [1, T.hor]]));
  if (time === 'night') { for (let i = 0; i < 260; i++) d.circle(R() * W, R() * H * 0.7, 0.8 + R() * 2.2, '#ffffff', { op: 0.3 + R() * 0.7 }); }
  const sx = o.sunX ?? W * (0.62 + R() * 0.2), sy = time === 'day' ? H * 0.2 : time === 'dusk' ? H * 0.58 : H * 0.22;
  d.path(ellipse(sx, sy, 260, 260), d.rad([[0, T.glow, 0.75], [1, T.glow, 0]]));
  d.circle(sx, sy, time === 'night' ? 46 : 58, T.sun);
  if (time === 'night') d.circle(sx + 18, sy - 8, 40, T.top, { op: 0.0 });
  const nc = o.clouds ?? 7;
  for (let i = 0; i < nc; i++) {
    // a cumulus: a flat base and round lobes on top, painted in two values — the shade, then the sunlit part pushed up
    // and to the left so a rim of shade stays under and to the right, then a glint on the top lobes
    const cx = R() * W, cy = H * (0.12 + R() * 0.36), w = 170 + R() * 280, h = 46 + R() * 52, n = 4 + Math.floor(R() * 4), lobes = [];
    for (let k = 0; k < n; k++) { const u = n === 1 ? 0.5 : k / (n - 1), r = h * (0.55 + 0.55 * Math.sin(u * Math.PI)) * (0.8 + R() * 0.4); lobes.push([cx + (u - 0.5) * w * 0.86, cy - r * 0.45, r]); }
    const op = time === 'night' ? 0.6 : 0.96, base = [cx, cy + h * 0.1, w * 0.58, h * 0.42];
    d.path(ellipse(base[0], base[1], base[2], base[3]), T.cshade, { op });
    for (const [x, y, r] of lobes) d.circle(x, y, r, T.cshade, { op });
    const lit = mix(T.cloud, T.cshade, 0.08);
    d.path(ellipse(base[0] - w * 0.04, base[1] - h * 0.1, base[2] * 0.92, base[3] * 0.7), lit, { op });
    for (const [x, y, r] of lobes) d.circle(x - r * 0.14, y - r * 0.16, r * 0.84, lit, { op });
    for (const [x, y, r] of lobes.slice(0, Math.ceil(n / 2))) d.circle(x - r * 0.3, y - r * 0.36, r * 0.36, T.cloud, { op: op * 0.9 });
    if (time === 'dusk') d.path(ellipse(base[0] + w * 0.06, base[1] + h * 0.22, base[2] * 0.8, base[3] * 0.25), mix(T.glow, '#ffffff', 0.3), { op: 0.35 }); // the low sun warms their undersides
  }
  return d;
}
function ridge(n, base, amp, seed, rough = 0.5, peaks = 4) {
  const R = rng(seed), pts = [[0, H]], ph = R() * 10;
  for (let i = 0; i <= n; i++) { const x = (i / n) * W, u = i / n; const y = base - amp * (0.55 + 0.45 * Math.sin(u * peaks * Math.PI + ph)) * (0.7 + 0.3 * Math.sin(u * 13 + ph * 2)) - (R() - 0.5) * amp * 0.12 * rough; pts.push([x, y]); }
  pts.push([W, H]); return pts;
}
function hills(d, T, base, amp, col, seed, haze, o = {}) {
  const pts = ridge(o.n ?? 60, base, amp, seed, o.rough ?? 0.6, o.peaks ?? 3);
  const c = air(col, T, haze);
  d.path(smooth(pts, true, 0.6), d.lin([[0, shade(c, 0.06)], [1, mix(c, T.haze, 0.25)]]));
  // light on the slopes facing the sun, a few strokes of strata
  const R = rng(seed + 5);
  for (let i = 0; i < (o.strokes ?? 14); i++) { const k = 1 + Math.floor(R() * (pts.length - 3)), p = pts[k]; if (p[1] > H - 10) continue; const L = 40 + R() * 90; d.path(`M${f(p[0])} ${f(p[1] + 8)} l${f(-L * 0.5)} ${f(L)}`, 'none', { stroke: shade(c, -0.12), sw: 3 + R() * 4, op: 0.35 }); }
  return pts;
}
function trees(d, T, n, y0, y1, col, seed, haze, kind = 'pine', scale = 1) {
  const R = rng(seed), c = air(col, T, haze), cs = shade(c, -0.2);
  const list = []; for (let i = 0; i < n; i++) list.push([R() * W, y0 + R() * (y1 - y0), (0.6 + R() * 0.8) * scale]);
  list.sort((a, b) => a[1] - b[1]);
  for (const [x, y, s] of list) {
    if (kind === 'pine') { const h = 150 * s, w = 46 * s; for (let t = 0; t < 4; t++) { const ty = y - t * h * 0.22; d.path(P([[x - w * (1 - t * 0.2), ty], [x, ty - h * 0.42], [x + w * (1 - t * 0.2), ty]]), t % 2 ? c : shade(c, 0.05)); d.path(P([[x, ty - h * 0.42], [x + w * (1 - t * 0.2), ty], [x + w * 0.2, ty]]), cs, { op: 0.6 }); } d.path(rrect(x - 4 * s, y, 8 * s, 18 * s, 2), shade(c, -0.4)); }
    else if (kind === 'round') { d.path(rrect(x - 6 * s, y - 40 * s, 12 * s, 60 * s, 3), shade(air('#5a4636', T, haze), -0.1)); d.path(blob(x, y - 80 * s, 70 * s, 62 * s, 0.12, x | 0, 14), d.lin([[0, shade(c, 0.12)], [1, cs]])); for (let k = 0; k < 6; k++) d.path(ellipse(x - 30 * s + R() * 50 * s, y - 110 * s + R() * 50 * s, 14 * s, 8 * s), shade(c, 0.18), { op: 0.6 }); }
    else if (kind === 'bamboo') { for (let k = 0; k < 3; k++) { const bx = x + k * 14 * s; d.path(rrect(bx, y - 700 * s, 10 * s, 700 * s, 4), mix(c, '#cfe6a0', 0.2)); for (let j = 1; j < 8; j++) d.path(`M${f(bx)} ${f(y - j * 90 * s)} h${f(10 * s)}`, 'none', { stroke: cs, sw: 3 }); } d.path(blob(x + 20 * s, y - 680 * s, 60 * s, 30 * s, 0.4, x | 0, 10), c, { op: 0.8 }); }
    else if (kind === 'sakura') { d.path(rrect(x - 7 * s, y - 50 * s, 14 * s, 70 * s, 3), air('#4a3030', T, haze)); d.path(blob(x, y - 95 * s, 80 * s, 60 * s, 0.14, x | 0, 14), d.lin([[0, air('#ffd6e6', T, haze)], [1, air('#f29ab8', T, haze)]])); }
    else if (kind === 'palm') { d.path(`M${f(x)} ${f(y)} Q${f(x + 20 * s)} ${f(y - 120 * s)} ${f(x + 6 * s)} ${f(y - 240 * s)}`, 'none', { stroke: air('#7a5a3a', T, haze), sw: 12 * s }); for (let k = 0; k < 6; k++) { const a = Math.PI * (1.1 + k * 0.16); d.path(`M${f(x + 6 * s)} ${f(y - 240 * s)} Q${f(x + Math.cos(a) * 80 * s)} ${f(y - 280 * s + Math.sin(a) * 40 * s)} ${f(x + Math.cos(a) * 150 * s)} ${f(y - 220 * s - Math.sin(a) * 20 * s)}`, 'none', { stroke: c, sw: 14 * s }); } }
    else if (kind === 'cactus') { d.path(rrect(x - 16 * s, y - 160 * s, 32 * s, 160 * s, 16 * s), c); d.path(rrect(x - 50 * s, y - 120 * s, 22 * s, 60 * s, 11 * s), c); d.path(rrect(x + 26 * s, y - 140 * s, 22 * s, 70 * s, 11 * s), c); }
  }
}
function buildings(d, T, n, base, col, seed, haze, o = {}) {
  const R = rng(seed), c = air(col, T, haze);
  let x = -40;
  while (x < W + 40) {
    const w = (o.w ?? 120) * (0.6 + R() * 0.9), h = (o.h ?? 260) * (0.4 + R() * 0.9), wallC = mix(c, '#ffffff', R() * 0.08);
    if (o.style === 'city') {
      d.path(rrect(x, base - h, w, h + 10, 2), d.lin([[0, shade(wallC, 0.06)], [1, shade(wallC, -0.12)]]));
      const lit = o.lights ?? 0; for (let yy = base - h + 16; yy < base - 20; yy += 26) for (let xx = x + 10; xx < x + w - 16; xx += 22) if (R() < 0.55) d.path(rrect(xx, yy, 12, 14, 1), lit && R() < lit ? '#ffd27a' : shade(wallC, -0.18), { op: 0.9 });
    } else {
      // village / temple roofs: walls, a roof with eaves
      const wh = h * 0.45; d.path(rrect(x, base - wh, w, wh + 10, 3), d.lin([[0, air('#efe6d4', T, haze)], [1, air('#c9bea8', T, haze)]]));
      d.path(P([[x - 16, base - wh], [x + w / 2, base - wh - h * 0.42], [x + w + 16, base - wh]]), air(o.roof ?? '#7a4a3a', T, haze));
      if (o.lights && R() < 0.7) d.path(rrect(x + w * 0.3, base - wh * 0.7, w * 0.22, wh * 0.35, 2), '#ffd27a', { op: 0.95 });
      else d.path(rrect(x + w * 0.3, base - wh * 0.7, w * 0.22, wh * 0.35, 2), air('#5a4a3a', T, haze));
    }
    x += w + (o.gap ?? 10) * (0.5 + R());
  }
}
function ground(d, T, y, col, seed, o = {}) {
  const c = shade(col, T.light * 0.7);
  d.path(smooth([[0, y + 30], [W * 0.3, y - 10], [W * 0.6, y + 20], [W, y - 5], [W, H], [0, H]], true, 0.4), d.lin([[0, mix(c, T.haze, 0.2)], [1, shade(c, -0.18)]]));
  const R = rng(seed);
  for (let i = 0; i < (o.patches ?? 40); i++) d.path(ellipse(R() * W, y + 40 + R() * (H - y - 40), 40 + R() * 140, 6 + R() * 14), shade(c, (R() - 0.5) * 0.18), { op: 0.5 });
}
function fore(d, T, kind, seed) {
  const R = rng(seed), dark = shade(T === TIMES.night ? '#0a1020' : '#1e2a22', T.light * 0.4);
  if (kind === 'grass') { for (let i = 0; i < 160; i++) { const x = R() * W, h = 60 + R() * 160; d.path(P([[x - 7, H], [x + (R() - 0.5) * 60, H - h], [x + 7, H]]), mix(dark, '#3a5a3a', R() * 0.5)); } }
  else if (kind === 'branch') { d.path(`M${W} 60 Q${W - 500} 120 ${W - 900} 30`, 'none', { stroke: dark, sw: 26 }); for (let i = 0; i < 40; i++) { const x = W - 100 - R() * 800, y = 40 + R() * 160; d.path(blob(x, y, 40 + R() * 30, 18 + R() * 12, 0.2, i + seed, 10), mix(dark, '#2a3a2a', R() * 0.6)); } }
  else if (kind === 'petals') { for (let i = 0; i < 60; i++) d.path(ellipse(R() * W, R() * H, 10 + R() * 8, 5 + R() * 4), '#ffc4d8', { op: 0.85 }); }
  else if (kind === 'rocks') { for (let i = 0; i < 6; i++) d.path(blob(R() * W, H - 20, 120 + R() * 160, 60 + R() * 70, 0.2, seed + i, 10), dark); }
  else if (kind === 'reeds') { for (let i = 0; i < 90; i++) { const x = R() * W, h = 140 + R() * 220; d.path(`M${f(x)} ${H} Q${f(x + (R() - 0.5) * 50)} ${f(H - h / 2)} ${f(x + (R() - 0.5) * 70)} ${f(H - h)}`, 'none', { stroke: mix(dark, '#4a5a3a', R() * 0.5), sw: 4 + R() * 4 }); } }
  else if (kind === 'bubbles') { for (let i = 0; i < 50; i++) d.path(ellipse(R() * W, R() * H, 6 + R() * 20, 6 + R() * 20), 'none', { stroke: '#cfeeff', sw: 2.5, op: 0.6 }); }
  else if (kind === 'stalactites') { for (let i = 0; i < 18; i++) { const x = R() * W, h = 80 + R() * 220; d.path(P([[x - 40, 0], [x, h], [x + 40, 0]]), dark); } }
}
const PLACES = {
  mountains: (L, T, s) => { L.far((d) => hills(d, T, 640, 300, '#6a7aa0', s, 0.6, { peaks: 5 })); L.mid((d) => { hills(d, T, 760, 200, '#4f7a5a', s + 1, 0.35); trees(d, T, 40, 700, 820, '#2f5a45', s + 2, 0.35); }); L.near((d) => ground(d, T, 860, '#6a9a4a', s + 3)); L.fore((d) => fore(d, T, 'grass', s + 4)); },
  forest: (L, T, s) => { L.far((d) => { hills(d, T, 660, 160, '#5a7a6a', s, 0.6); trees(d, T, 70, 560, 680, '#3a6a52', s + 1, 0.55, 'pine', 0.8); }); L.mid((d) => trees(d, T, 50, 760, 860, '#2f5a45', s + 2, 0.25, 'round', 1.6)); L.near((d) => ground(d, T, 880, '#5a7a3a', s + 3)); L.fore((d) => fore(d, T, 'branch', s + 4)); },
  desert: (L, T, s) => { L.far((d) => hills(d, T, 700, 140, '#d9a86a', s, 0.55, { peaks: 2, strokes: 4 })); L.mid((d) => { hills(d, T, 820, 120, '#e8b878', s + 1, 0.2, { peaks: 3, strokes: 4 }); trees(d, T, 6, 800, 860, '#5a8a4a', s + 2, 0.2, 'cactus', 1.2); }); L.near((d) => ground(d, T, 900, '#f0c890', s + 3)); L.fore((d) => fore(d, T, 'rocks', s + 4)); },
  coast: (L, T, s) => { L.far((d) => { d.path(`M0 640H${W}V${H}H0Z`, d.lin([[0, air('#3a8ac0', T, 0.35)], [1, air('#1f5a8a', T, 0.1)]])); for (let i = 0; i < 40; i++) d.path(`M${f(rng(s + i)() * W)} ${f(660 + i * 9)} h${f(60 + i * 3)}`, 'none', { stroke: '#ffffff', sw: 2, op: 0.4 }); }); L.mid((d) => { hills(d, T, 760, 120, '#b9a07a', s + 1, 0.3, { peaks: 1 }); trees(d, T, 8, 720, 760, '#3f7a4a', s + 2, 0.3, 'palm', 1.2); }); L.near((d) => ground(d, T, 900, '#e8d4a6', s + 3)); L.fore((d) => fore(d, T, 'rocks', s + 4)); },
  village: (L, T, s, time) => { L.far((d) => hills(d, T, 640, 220, '#6a8aa0', s, 0.6)); L.mid((d) => { trees(d, T, 18, 640, 700, '#3f6a4a', s + 1, 0.4, 'round', 0.9); buildings(d, T, 0, 760, '#d9cbb0', s + 2, 0.25, { w: 160, h: 220, gap: 40, lights: time !== 'day', roof: '#8a4a3a' }); }); L.near((d) => ground(d, T, 820, '#c2a878', s + 3, { patches: 60 })); L.fore((d) => fore(d, T, 'grass', s + 4)); },
  city: (L, T, s, time) => { L.far((d) => buildings(d, T, 0, 760, '#7a86a8', s, 0.55, { style: 'city', w: 110, h: 520, gap: 4, lights: time === 'day' ? 0 : 0.3 })); L.mid((d) => buildings(d, T, 0, 880, '#4a5470', s + 1, 0.25, { style: 'city', w: 160, h: 420, gap: 6, lights: time === 'day' ? 0 : 0.55 })); L.near((d) => { d.path(`M0 900H${W}V${H}H0Z`, shade('#3a3c44', T.light)); for (let x = 0; x < W; x += 160) d.path(rrect(x, 980, 90, 10, 2), '#e8e2c0', { op: 0.6 }); }); L.fore((d) => { for (let x = 120; x < W; x += 600) { d.path(rrect(x, 420, 14, 660, 4), '#20232a'); d.path(ellipse(x + 7, 420, 40, 14), time === 'day' ? '#2a2d36' : '#ffe9a8'); } }); },
  temple: (L, T, s, time) => { L.far((d) => hills(d, T, 620, 260, '#5a7090', s, 0.55)); L.mid((d) => { trees(d, T, 30, 640, 720, '#3a6050', s + 1, 0.4, 'pine', 1.1); d.path(rrect(W / 2 - 300, 560, 600, 260, 6), air('#c9443a', T, 0.15)); for (let k = 0; k < 3; k++) d.path(P([[W / 2 - 380 + k * 40, 560 - k * 120], [W / 2, 440 - k * 120], [W / 2 + 380 - k * 40, 560 - k * 120]]), air('#2a2a36', T, 0.15)); if (time !== 'day') d.path(rrect(W / 2 - 60, 660, 120, 140, 4), '#ffd27a'); }); L.near((d) => { ground(d, T, 840, '#b8b0a0', s + 3); for (let k = 0; k < 6; k++) d.path(rrect(W / 2 - 260 + k * 8, 820 + k * 34, 520 - k * 16, 30, 3), shade('#a8a090', -k * 0.03)); }); L.fore((d) => fore(d, T, 'branch', s + 4)); },
  bamboo: (L, T, s) => { L.far((d) => trees(d, T, 60, 900, 960, '#7aa66a', s, 0.55, 'bamboo', 1)); L.mid((d) => trees(d, T, 28, 980, 1020, '#5a8a4a', s + 1, 0.25, 'bamboo', 1.3)); L.near((d) => ground(d, T, 960, '#6a7a4a', s + 3)); L.fore((d) => trees(d, T, 6, 1080, 1080, '#3a5a2a', s + 2, 0.0, 'bamboo', 1.8)); },
  snow: (L, T, s) => { L.far((d) => { const pts = hills(d, T, 600, 360, '#8a9ac0', s, 0.45, { peaks: 4 }); d.path(smooth(pts.map(([x, y]) => [x, Math.min(H, y + (y < 600 ? 60 : 900))]), true, 0.6), '#ffffff', { op: 0.0 }); }); L.mid((d) => { hills(d, T, 780, 160, '#e8eef8', s + 1, 0.15); trees(d, T, 30, 720, 800, '#2f4a45', s + 2, 0.3, 'pine', 1); }); L.near((d) => ground(d, T, 860, '#f4f8ff', s + 3)); L.fore((d) => fore(d, T, 'rocks', s + 4)); },
  lake: (L, T, s) => { L.far((d) => hills(d, T, 600, 220, '#5a7aa0', s, 0.55)); L.mid((d) => { d.path(`M0 640H${W}V${H}H0Z`, d.lin([[0, air('#6aa0d0', T, 0.3)], [1, air('#2a5a8a', T, 0.05)]])); for (let i = 0; i < 30; i++) { const R = rng(s + i); d.path(`M${f(R() * W)} ${f(660 + R() * 300)} h${f(40 + R() * 160)}`, 'none', { stroke: '#ffffff', sw: 2, op: 0.35 }); } }); L.near((d) => ground(d, T, 920, '#6a8a4a', s + 3)); L.fore((d) => fore(d, T, 'reeds', s + 4)); },
  sakura: (L, T, s) => { L.far((d) => hills(d, T, 640, 200, '#7a8ab0', s, 0.6)); L.mid((d) => trees(d, T, 26, 700, 820, '#f29ab8', s + 1, 0.2, 'sakura', 1.4)); L.near((d) => ground(d, T, 860, '#8ab060', s + 3)); L.fore((d) => fore(d, T, 'petals', s + 4)); },
  ricefields: (L, T, s) => { L.far((d) => hills(d, T, 620, 200, '#5a8070', s, 0.55)); L.mid((d) => { for (let k = 0; k < 7; k++) d.path(`M0 ${700 + k * 50} Q${W / 2} ${680 + k * 56} ${W} ${700 + k * 50} V${750 + k * 50} Q${W / 2} ${730 + k * 56} 0 ${750 + k * 50}Z`, air(k % 2 ? '#7ab060' : '#8cc070', T, 0.3 - k * 0.04)); }); L.near((d) => ground(d, T, 1000, '#6a9a4a', s + 3, { patches: 20 })); L.fore((d) => fore(d, T, 'grass', s + 4)); },
  castle: (L, T, s, time) => { L.far((d) => hills(d, T, 640, 240, '#6a7a9a', s, 0.55)); L.mid((d) => { const cx = W * 0.55; d.path(rrect(cx - 360, 560, 720, 300, 4), air('#8a8a92', T, 0.25)); for (const x of [-360, -120, 120, 300]) { d.path(rrect(cx + x, 380, 80, 480, 4), air('#9a9aa2', T, 0.2)); d.path(P([[cx + x - 12, 380], [cx + x + 40, 280], [cx + x + 92, 380]]), air('#3a4a7a', T, 0.2)); } for (let x = cx - 360; x < cx + 360; x += 40) d.path(rrect(x, 540, 24, 22, 2), air('#8a8a92', T, 0.25)); if (time !== 'day') for (let k = 0; k < 8; k++) d.path(rrect(cx - 300 + k * 80, 640, 18, 30, 4), '#ffd27a'); }); L.near((d) => ground(d, T, 860, '#7a9a5a', s + 3)); L.fore((d) => fore(d, T, 'grass', s + 4)); },
  space: (L, T, s) => { L.far((d) => { const R = rng(s); for (let i = 0; i < 400; i++) d.circle(R() * W, R() * H, 0.6 + R() * 2.4, '#ffffff', { op: 0.3 + R() * 0.7 }); d.path(ellipse(W * 0.3, H * 0.4, 600, 220), d.rad([[0, '#b07bff', 0.35], [1, '#b07bff', 0]])); }); L.mid((d) => { d.path(ellipse(W * 0.7, H * 0.5, 260, 260), d.lin([[0, '#ffb07a'], [1, '#a03a5a']])); d.path(ellipse(W * 0.7, H * 0.5, 420, 60), 'none', { stroke: '#ffe0b0', sw: 10, op: 0.8 }); }); L.near((d) => { d.path(ellipse(W * 0.15, H * 0.8, 140, 140), d.lin([[0, '#9ad7ff'], [1, '#2a5aa0']])); }); L.fore((d) => { const R = rng(s + 9); for (let i = 0; i < 12; i++) d.path(blob(R() * W, R() * H, 30 + R() * 50, 20 + R() * 30, 0.4, i, 9), '#5a5060'); }); },
  underwater: (L, T, s) => { L.far((d) => { for (let i = 0; i < 8; i++) d.path(P([[W * (0.1 + i * 0.12), 0], [W * (0.14 + i * 0.12), 0], [W * (0.3 + i * 0.1), H], [W * (0.22 + i * 0.1), H]]), '#ffffff', { op: 0.07 }); }); L.mid((d) => { hills(d, T, 880, 120, '#2a7a8a', s + 1, 0.3, { strokes: 2 }); for (let i = 0; i < 18; i++) { const R = rng(s + i), x = R() * W; d.path(`M${f(x)} ${H} Q${f(x + 40)} 860 ${f(x)} ${f(700 + R() * 100)}`, 'none', { stroke: '#3a9a6a', sw: 14 }); } }); L.near((d) => ground(d, T, 980, '#d9c49a', s + 3)); L.fore((d) => fore(d, T, 'bubbles', s + 4)); },
  cave: (L, T, s) => { L.far((d) => { d.path(ellipse(W / 2, H * 0.6, 520, 300), d.rad([[0, mix(T.hor, '#ffffff', 0.3)], [1, T.mid, 0]])); }); L.mid((d) => { d.path(`M0 0 H${W} V${H} H0Z M${W / 2 - 600} ${H} Q${W / 2 - 560} 300 ${W / 2} 280 Q${W / 2 + 560} 300 ${W / 2 + 600} ${H}Z`, '#3a3440', { rule: 'evenodd' }); }); L.near((d) => ground(d, T, 950, '#4a4450', s + 3, { patches: 30 })); L.fore((d) => fore(d, T, 'stalactites', s + 4)); },
};
export function backgrounds() {
  const out = [];
  let seed = 1;
  for (const [place, build] of Object.entries(PLACES)) for (const time of ['day', 'dusk', 'night']) {
    const T = TIMES[time], layers = [], s = seed++ * 17;
    const mk = (name, depth, speed) => (fn) => { const d = new Doc(W, H, { seed: s + layers.length }); fn(d); layers.push({ name, depth, speed, svg: d.svg(), d }); };
    layers.push({ name: 'sky', depth: 1000, speed: 0.02, svg: (() => { const d = sky(T, time, s, { clouds: place === 'space' || place === 'underwater' || place === 'cave' ? 0 : 7 }); if (place === 'space') { d.body = []; d.defs = []; d.path(`M0 0H${W}V${H}H0Z`, d.lin([[0, '#05040f'], [1, '#140a2a']])); } if (place === 'underwater') { d.body = []; d.defs = []; d.path(`M0 0H${W}V${H}H0Z`, d.lin([[0, time === 'night' ? '#0a2a4a' : '#3ac0e0'], [1, time === 'night' ? '#02101e' : '#0a4a7a']])); } if (place === 'cave') { d.body = []; d.defs = []; d.path(`M0 0H${W}V${H}H0Z`, '#1a1620'); } return d; })().svg() });
    build({ far: mk('far', 300, 0.15), mid: mk('middle', 60, 0.4), near: mk('near', 15, 0.8), fore: mk('foreground', 3, 1.4) }, T, s, time);
    // the whole picture in one file (each layer's drawing nested)
    const inner = layers.map((L) => L.svg.replace(/^<svg[^>]*>/, '').replace(/<\/svg>$/, '')).join('');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">${inner}</svg>`;
    out.push({ id: `bg_${place}_${time}`, name: `${place[0].toUpperCase() + place.slice(1)} — ${time}`, cat: 'backgrounds', sub: place, tags: ['background', '2.5d', 'parallax', 'multiplane', place, time], w: W, h: H, svg, layers: layers.map(({ name, depth, speed, svg: sv }) => ({ name, depth, speed, svg: sv })), fullscreen: true });
  }
  return out;
}
