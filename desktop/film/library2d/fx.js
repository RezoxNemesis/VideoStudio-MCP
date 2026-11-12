// Anime effects: speed lines, impact bursts, sparks, slashes, smoke, dust, fire, explosions, lightning, magic circles,
// auras, splashes, blood, emotion symbols and weather — all drawn by code, transparent backgrounds, ready to overlay.
import { Doc, rng, P, smooth, burst, blob, ellipse, shade, mix, INK, f, open, capsule } from './svg.js';

const A = (id, name, sub, tags, doc, o = {}) => ({ id: `fx_${id}`, name, cat: 'fx', sub, tags, w: doc.w, h: doc.h, svg: doc.svg(), ...o });
export function fx() {
  const out = [];
  // ---- radial speed lines (focus lines): density × colour
  [['white', '#ffffff', 90], ['black', '#141218', 90], ['dense', '#ffffff', 160], ['sparse', '#ffffff', 50], ['blue', '#bfe3ff', 110], ['red', '#ff4a5c', 100], ['gold', '#ffd76a', 110], ['inky', '#1b1824', 140]].forEach(([k, c, n], q) => {
    const d = new Doc(1920, 1080), R = rng(q + 3);
    for (let i = 0; i < n; i++) { const a = R() * Math.PI * 2, w = 0.004 + R() * 0.012, r0 = 300 + R() * 260, r1 = 1300; const p = (rr, aa) => [960 + Math.cos(aa) * rr * 1.25, 540 + Math.sin(aa) * rr]; d.path(P([p(r0, a), p(r1, a - w), p(r1, a + w)]), c, { op: 0.55 + R() * 0.45 }); }
    out.push(A(`focuslines_${k}`, `Focus lines (${k})`, 'speed', ['speed', 'focus', 'impact', 'shock', 'anime', 'lines'], d, { blend: c === '#141218' || k === 'inky' ? 'multiply' : 'screen', fullscreen: true }));
  });
  // ---- parallel speed lines (action background): direction × colour
  [['h_white', 0, '#ffffff'], ['h_blue', 0, '#9fd1ff'], ['diag_white', -0.35, '#ffffff'], ['diag_red', -0.35, '#ff6a6a'], ['v_white', 1.57, '#ffffff'], ['h_dark', 0, '#1d1a26']].forEach(([k, a, c], q) => {
    const d = new Doc(1920, 1080), R = rng(q + 40), ca = Math.cos(a), sa = Math.sin(a);
    for (let i = 0; i < 70; i++) { const off = (R() - 0.5) * 2600, len = 400 + R() * 1400, x0 = 960 + (R() - 0.5) * 2400, th = 1 + R() * 7; const cx = x0 - sa * off * 0.4, cy = 540 + ca * off * 0.4; d.path(P([[cx - ca * len / 2, cy - sa * len / 2], [cx + ca * len / 2 - sa * th, cy + sa * len / 2 + ca * th], [cx + ca * len / 2 + sa * th, cy + sa * len / 2 - ca * th]]), c, { op: 0.35 + R() * 0.5 }); }
    out.push(A(`speedlines_${k}`, `Speed lines (${k.replace('_', ' ')})`, 'speed', ['speed', 'motion', 'action', 'lines', 'anime'], d, { blend: c === '#1d1a26' ? 'multiply' : 'screen', fullscreen: true }));
  });
  // ---- impact bursts
  const burstCols = [['white', '#ffffff', '#ffe8a8'], ['gold', '#fff3b0', '#ffb43a'], ['red', '#ffd0d0', '#ff3b4f'], ['blue', '#e0f4ff', '#3aa6ff'], ['purple', '#f1ddff', '#a35cff'], ['green', '#e8ffd6', '#4fd16a']];
  burstCols.forEach(([k, c1, c2], q) => {
    for (const [sk, n, jit] of [['sharp', 14, 0.5], ['comic', 10, 0.25]]) {
      const d = new Doc(512, 512), g = d.rad([[0, '#ffffff'], [0.35, c1], [1, c2]]);
      d.ink(burst(256, 256, n, 70, 240, jit, q * 7 + n), c2, 5);
      d.path(burst(256, 256, n, 52, 180, jit, q * 7 + n + 1), g);
      d.path(burst(256, 256, Math.max(6, n - 4), 30, 95, jit * 0.6, q * 3 + 2), '#ffffff', { op: 0.9 });
      out.push(A(`impact_${sk}_${k}`, `Impact burst ${sk} (${k})`, 'impact', ['impact', 'hit', 'punch', 'burst', 'pow', 'anime'], d));
    }
  });
  // ---- hit sparks
  for (let q = 0; q < 8; q++) {
    const d = new Doc(512, 512), R = rng(q + 90), col = ['#fff7c2', '#ffd36b', '#ffffff', '#bfe9ff', '#ffb38a', '#ffe08a', '#e9f6ff', '#fff'][q];
    for (let i = 0; i < 16 + q * 2; i++) { const a = R() * Math.PI * 2, r0 = 20 + R() * 30, r1 = r0 + 60 + R() * 170, w = 2 + R() * 5; const x0 = 256 + Math.cos(a) * r0, y0 = 256 + Math.sin(a) * r0, x1 = 256 + Math.cos(a) * r1, y1 = 256 + Math.sin(a) * r1; d.path(P([[x0 - Math.sin(a) * w, y0 + Math.cos(a) * w], [x1, y1], [x0 + Math.sin(a) * w, y0 - Math.cos(a) * w]]), col, { op: 0.7 + R() * 0.3 }); }
    d.path(burst(256, 256, 4, 14, 70, 0.1, q, q * 0.4), '#ffffff'); d.circle(256, 256, 18, '#ffffff');
    out.push(A(`sparks_${q + 1}`, `Hit sparks ${q + 1}`, 'impact', ['sparks', 'clash', 'metal', 'hit', 'sword'], d, { blend: 'screen' }));
  }
  // ---- slash arcs
  const slashCols = [['white', '#ffffff', '#cfe8ff'], ['blue', '#e8f6ff', '#3a8dff'], ['red', '#ffe0e0', '#ff2d48'], ['gold', '#fff6cf', '#ffb020'], ['purple', '#f3e6ff', '#9b4dff'], ['green', '#eaffea', '#2bd16a'], ['black', '#5a5566', '#120f18'], ['fire', '#fff2c4', '#ff6a1a'], ['ice', '#f0fdff', '#7fe0ff'], ['wind', '#f4fff9', '#9ff2d0']];
  slashCols.forEach(([k, c1, c2], q) => {
    const d = new Doc(1024, 512), g = d.lin([[0, c2, 0], [0.55, c2, 0.9], [0.85, c1, 1], [1, '#ffffff', 1]], 0, 0, 1, 0);
    const arc = (r, th) => { const pts = []; for (let i = 0; i <= 40; i++) { const u = i / 40, a = Math.PI * (1.08 + 0.84 * u), w = th * Math.sin(Math.PI * Math.pow(u, 0.75)); pts.push([512 + Math.cos(a) * (r + w), 470 + Math.sin(a) * (r + w) * 0.62]); } for (let i = 40; i >= 0; i--) { const u = i / 40, a = Math.PI * (1.08 + 0.84 * u), w = th * Math.sin(Math.PI * Math.pow(u, 0.75)); pts.push([512 + Math.cos(a) * (r - w * 0.4), 470 + Math.sin(a) * (r - w * 0.4) * 0.62]); } return smooth(pts); };
    d.path(arc(430, 60 + q * 3), g); d.path(arc(430, 20), '#ffffff', { op: 0.85 });
    out.push(A(`slash_${k}`, `Slash arc (${k})`, 'slash', ['slash', 'sword', 'cut', 'swing', 'arc', 'attack'], d, { blend: k === 'black' ? 'normal' : 'screen' }));
  });
  // ---- smoke puffs & dust
  const smokeCols = [['white', '#ffffff', '#c9d0dc'], ['grey', '#c7c9cf', '#7d808a'], ['dark', '#6c6875', '#2e2b36'], ['warm', '#f6eadb', '#b59c86'], ['green', '#d9f2c8', '#7aa66a'], ['purple', '#e6d9f7', '#8a6fb5'], ['sand', '#efe0bd', '#bb9b66'], ['steam', '#ffffff', '#dfe8f2'], ['toxic', '#e6ff9e', '#7fb02c'], ['ash', '#9b9590', '#4a4542']];
  smokeCols.forEach(([k, c1, c2], q) => {
    const d = new Doc(512, 512), R = rng(q + 140);
    const lobes = []; for (let i = 0; i < 11; i++) { const a = R() * Math.PI * 2, rr = Math.sqrt(R()) * 120; lobes.push([256 + Math.cos(a) * rr, 290 + Math.sin(a) * rr * 0.7 - (1 - Math.abs(Math.sin(a))) * 10, 58 + R() * 52]); }
    lobes.sort((a, b) => a[1] - b[1]);
    const all = lobes.map(([x, y, r], i) => blob(x, y, r, r * 0.9, 0.08, q * 10 + i, 18)).join(' ');
    d.path(all, 'none', { stroke: shade(c2, -0.4), sw: 7 }); d.path(all, mix(c1, c2, 0.55));
    for (const [x, y, r] of lobes) d.path(ellipse(x - r * 0.12, y - r * 0.14, r * 0.78, r * 0.68), d.rad([[0, c1], [0.75, mix(c1, c2, 0.25)], [1, mix(c1, c2, 0.55), 0]], 0.42, 0.38, 0.6));
    out.push(A(`smoke_${k}`, `Smoke puff (${k})`, 'smoke', ['smoke', 'puff', 'cloud', 'dust', 'poof', 'vanish'], d));
  });
  for (let q = 0; q < 6; q++) {
    const d = new Doc(768, 384), R = rng(q + 170), c = ['#e8dcc4', '#d8c7a6', '#cfc8bd', '#f0e6d4', '#bfae92', '#e2d9cf'][q];
    const lobes = []; for (let i = 0; i < 16; i++) { const x = 90 + R() * 588, y = 310 - Math.sin((x - 90) / 588 * Math.PI) * (30 + R() * 80), r = 28 + R() * 46; lobes.push([x, y, r]); }
    const all = lobes.map(([x, y, r], i) => blob(x, y, r, r * 0.75, 0.08, q * 30 + i, 16)).join(' ');
    d.path(all, 'none', { stroke: shade(c, -0.4), sw: 5 }); d.path(all, c);
    for (const [x, y, r] of lobes) d.path(ellipse(x - r * 0.1, y - r * 0.15, r * 0.7, r * 0.5), mix(c, '#ffffff', 0.45), { op: 0.7 });
    out.push(A(`dust_${q + 1}`, `Dust cloud ${q + 1}`, 'smoke', ['dust', 'landing', 'run', 'skid', 'ground'], d));
  }
  // ---- flames
  const fire = [['orange', '#ffe27a', '#ff8a1e', '#e53c10'], ['blue', '#e4f7ff', '#4fb6ff', '#1a4dff'], ['green', '#f0ffd0', '#6dff5a', '#14a83a'], ['purple', '#f8e6ff', '#c06bff', '#6a1ad9'], ['white', '#ffffff', '#ffe9b5', '#ffb04a'], ['red', '#ffd27a', '#ff4a1e', '#a8100a'], ['black', '#8a7fa0', '#3c2f52', '#120c1c'], ['gold', '#fffbd6', '#ffd23a', '#ff8a00']];
  fire.forEach(([k, c1, c2, c3], q) => {
    const d = new Doc(512, 640), R = rng(q + 200);
    const flame = (cx, base, w, h, s) => { const pts = []; const n = 7; for (let i = 0; i <= n; i++) { const u = i / n, x = cx - w + u * w * 2, y = base - Math.sin(u * Math.PI) * h * (0.35 + 0.65 * (i % 2 ? 1 : 0.55 + R() * 0.3)); pts.push([x + (R() - 0.5) * w * 0.25, i === 0 || i === n ? base : y]); } pts.push([cx + w * 0.6, base + h * 0.12]); pts.push([cx - w * 0.6, base + h * 0.12]); return smooth(pts.map((p, i) => (i % 2 && i < pts.length - 2 ? [p[0], p[1] - h * 0.25 * s] : p))); };
    d.ink(flame(256, 560, 190, 470, 1), d.lin([[0, c2], [1, c3]]), 4, shade(c3, -0.5));
    d.path(flame(256, 560, 140, 360, 0.8), d.lin([[0, c1], [1, c2]]));
    d.path(flame(256, 565, 80, 220, 0.6), c1, { op: 0.95 });
    out.push(A(`flame_${k}`, `Flame (${k})`, 'fire', ['fire', 'flame', 'burn', 'torch', 'magic'], d));
  });
  // ---- explosions
  for (let q = 0; q < 6; q++) {
    const d = new Doc(768, 768), R = rng(q + 260), pal = [['#fff3b8', '#ffad33', '#e8461a', '#4a2a2a'], ['#ffffff', '#ffd23a', '#ff6b1a', '#3a2a30'], ['#e8f8ff', '#6fc8ff', '#2a5bd9', '#1a1f3a'], ['#fff', '#ff9bd2', '#c2378e', '#2e1a2e'], ['#fffbe0', '#c4ff6a', '#3fae2e', '#1e2a1a'], ['#ffffff', '#ffe08a', '#ff8a3a', '#2a2a2a']][q];
    for (let i = 0; i < 10; i++) { const a = R() * 6.28, r = 120 + R() * 140, x = 384 + Math.cos(a) * r * 0.9, y = 400 + Math.sin(a) * r * 0.75; d.ink(blob(x, y, 90 + R() * 70, 80 + R() * 60, 0.3, q * 50 + i), pal[3], 3, '#120f18', { op: 0.85 }); }
    d.ink(burst(384, 384, 13, 170, 330, 0.45, q + 5), pal[2], 4); d.path(burst(384, 384, 11, 130, 250, 0.4, q + 7), pal[1]); d.path(blob(384, 384, 120, 110, 0.25, q + 9), pal[0]);
    out.push(A(`explosion_${q + 1}`, `Explosion ${q + 1}`, 'explosion', ['explosion', 'blast', 'boom', 'bomb', 'impact'], d));
  }
  // ---- lightning
  for (let q = 0; q < 8; q++) {
    const d = new Doc(512, 1024), R = rng(q + 300), col = ['#ffffff', '#d6f0ff', '#fff4b8', '#e8d6ff', '#ffd6d6', '#d8ffe0', '#ffffff', '#cfe9ff'][q], glow = ['#7fd0ff', '#3f8cff', '#ffcc33', '#a45bff', '#ff4060', '#3fe080', '#ffffff', '#6fb0ff'][q];
    const bolt = () => { const pts = [[256 + (R() - 0.5) * 120, 0]]; let x = pts[0][0]; for (let y = 60; y <= 1024; y += 50 + R() * 50) { x += (R() - 0.5) * 140; pts.push([x, y]); } return pts; };
    const b = bolt(), side = (pts, w) => { const L = pts.map((p, i) => [p[0] - w * (1 - i / pts.length), p[1]]), Rr = pts.map((p, i) => [p[0] + w * (1 - i / pts.length), p[1]]).reverse(); return P([...L, ...Rr]); };
    d.path(side(b, 26), glow, { op: 0.35, filter: d.blur(10) }); d.path(side(b, 11), glow); d.path(side(b, 5), col);
    for (let k = 0; k < 3; k++) { const s = Math.floor(R() * (b.length - 3)) + 1, br = [b[s]]; let x = b[s][0], y = b[s][1]; for (let i = 0; i < 4; i++) { x += (R() - 0.3) * 90; y += 40 + R() * 50; br.push([x, y]); } d.path(side(br, 5), glow); d.path(side(br, 2.5), col); }
    out.push(A(`lightning_${q + 1}`, `Lightning bolt ${q + 1}`, 'energy', ['lightning', 'thunder', 'electric', 'storm', 'power'], d, { blend: 'screen' }));
  }
  // ---- magic circles (original glyphs)
  for (let q = 0; q < 10; q++) {
    const d = new Doc(768, 768), R = rng(q + 340), col = ['#7ff0ff', '#ffd36b', '#ff6bd5', '#8aff9e', '#b48cff', '#ff7a5c', '#ffffff', '#6bb7ff', '#ffef7a', '#ff4d6d'][q], sw = 4;
    const ring = (r, w = sw) => d.path(ellipse(384, 384, r, r), 'none', { stroke: col, sw: w });
    ring(360, 6); ring(330); ring(250, 5); ring(140);
    const nPts = [5, 6, 7, 8, 5, 6, 9, 7, 8, 12][q];
    const star = []; for (let i = 0; i < nPts; i++) { const a = -Math.PI / 2 + (i * 2 * Math.PI * (nPts % 2 ? 2 : 1)) / nPts; star.push([384 + Math.cos(a) * 250, 384 + Math.sin(a) * 250]); }
    d.path(P(star), 'none', { stroke: col, sw: 3.5 });
    for (let i = 0; i < 24; i++) { const a = (i / 24) * Math.PI * 2, x = 384 + Math.cos(a) * 345, y = 384 + Math.sin(a) * 345, g = Math.floor(R() * 5); d.group((dd) => { if (g === 0) dd.path(`M-6 -8 L6 -8 L0 8Z`, 'none', { stroke: col, sw: 2.5 }); else if (g === 1) dd.path(`M-7 0 L7 0 M0 -8 L0 8`, 'none', { stroke: col, sw: 2.5 }); else if (g === 2) dd.path(ellipse(0, 0, 6, 6), 'none', { stroke: col, sw: 2.5 }); else if (g === 3) dd.path(`M-7 -7 L7 7 M-7 7 L7 -7`, 'none', { stroke: col, sw: 2.5 }); else dd.path(`M-6 6 L0 -8 L6 6 M-4 1 L4 1`, 'none', { stroke: col, sw: 2.5 }); }, `translate(${f(x)} ${f(y)}) rotate(${f((a * 180) / Math.PI + 90)})`); }
    for (let i = 0; i < nPts; i++) { const a = -Math.PI / 2 + (i * 2 * Math.PI) / nPts; d.path(ellipse(384 + Math.cos(a) * 195, 384 + Math.sin(a) * 195, 16, 16), 'none', { stroke: col, sw: 3 }); }
    d.path(ellipse(384, 384, 360, 360), d.rad([[0, col, 0.25], [0.7, col, 0.05], [1, col, 0]]));
    out.push(A(`magiccircle_${q + 1}`, `Magic circle ${q + 1}`, 'magic', ['magic', 'spell', 'circle', 'summon', 'glyph', 'fantasy'], d, { blend: 'screen' }));
  }
  // ---- auras
  for (let q = 0; q < 6; q++) {
    const d = new Doc(512, 1024), R = rng(q + 380), col = ['#7fd6ff', '#ffd23a', '#ff4a6a', '#b07bff', '#6dff8a', '#ffffff'][q];
    for (let k = 0; k < 3; k++) { const pts = []; const n = 22; for (let i = 0; i <= n; i++) { const u = i / n, a = Math.PI * u; pts.push([256 + Math.cos(Math.PI - a) * (200 - k * 40) * (0.85 + R() * 0.3), 980 - Math.sin(a) * (820 - k * 120) - (i % 2 ? 60 + R() * 120 : 0)]); } d.path(smooth(pts), col, { op: 0.25 + k * 0.2, filter: k === 0 ? d.blur(12) : undefined }); }
    out.push(A(`aura_${q + 1}`, `Power aura ${q + 1}`, 'energy', ['aura', 'power', 'energy', 'ki', 'charge', 'glow'], d, { blend: 'screen' }));
  }
  // ---- water splashes & blood
  for (let q = 0; q < 6; q++) {
    const d = new Doc(512, 512), R = rng(q + 420), c1 = ['#e6f8ff', '#cfefff', '#f0fbff', '#d6f4ff', '#bfe8ff', '#ffffff'][q], c2 = '#3a9be0';
    d.ink(burst(256, 360, 9, 60, 200, 0.6, q + 1, -Math.PI / 2), d.lin([[0, c1], [1, c2]]), 3.5, '#1f5d8f');
    for (let i = 0; i < 10; i++) { const a = -Math.PI * (0.15 + R() * 0.7), r = 170 + R() * 90; d.ink(ellipse(256 + Math.cos(a) * r, 360 + Math.sin(a) * r, 8 + R() * 10, 12 + R() * 14), c1, 2.4, '#1f5d8f'); }
    out.push(A(`splash_${q + 1}`, `Water splash ${q + 1}`, 'water', ['water', 'splash', 'drop', 'rain', 'sea'], d));
  }
  for (let q = 0; q < 6; q++) {
    const d = new Doc(512, 512), R = rng(q + 450), c = ['#b0101e', '#8f0a17', '#c41a24', '#7a0a14', '#d12a2a', '#5e0a12'][q];
    d.path(blob(256, 256, 110 + q * 8, 90 + q * 6, 0.45, q + 3, 14), c);
    for (let i = 0; i < 14; i++) { const a = R() * 6.28, r = 130 + R() * 110; d.path(ellipse(256 + Math.cos(a) * r, 256 + Math.sin(a) * r, 5 + R() * 14, 5 + R() * 12), c); }
    d.path(blob(236, 230, 40, 26, 0.3, q + 5), shade(c, 0.35), { op: 0.6 });
    out.push(A(`blood_${q + 1}`, `Blood splatter ${q + 1}`, 'blood', ['blood', 'splatter', 'wound', 'fight', 'cut'], d));
  }
  // ---- emotion symbols (manga marks)
  const emo = [];
  emo.push(['anger_vein', 'Anger vein', (d) => { const g = (r) => `M${256 - r} ${256 - r} Q256 ${256 - r * 0.25} ${256 + r} ${256 - r}`; for (let i = 0; i < 4; i++) d.group((dd) => dd.ink(`M150 200 Q240 230 236 150 L276 150 Q272 230 362 200 L362 240 Q272 236 276 316 L236 316 Q240 236 150 240Z`, '#ff3b4f', 6), `rotate(${i * 90} 256 256)`); void g; }]);
  emo.push(['sweat_drop', 'Sweat drop', (d) => { d.ink(`M256 60 C330 200 370 270 370 330 A114 114 0 1 1 142 330 C142 270 182 200 256 60Z`, d.lin([[0, '#e8f7ff'], [1, '#7fc6ff']]), 7); d.path(ellipse(210, 330, 22, 40), '#ffffff', { op: 0.9 }); }]);
  emo.push(['sparkle', 'Sparkle', (d) => { d.ink(burst(256, 256, 4, 40, 220, 0, 1, 0), '#fff7c2', 6); d.path(burst(256, 256, 4, 20, 120, 0, 1, Math.PI / 4), '#ffffff'); }]);
  emo.push(['question', 'Question mark', (d) => d.text(256, 380, '?', { size: 360, fill: '#ffffff', stroke: INK, sw: 14 })]);
  emo.push(['exclaim', 'Exclamation', (d) => d.text(256, 390, '!', { size: 380, fill: '#ff3b4f', stroke: INK, sw: 14 })]);
  emo.push(['heart', 'Heart', (d) => d.ink(`M256 430 C120 330 60 250 60 170 A98 98 0 0 1 256 130 A98 98 0 0 1 452 170 C452 250 392 330 256 430Z`, d.lin([[0, '#ff8aa8'], [1, '#e8204a']]), 8)]);
  emo.push(['hearts', 'Hearts', (d) => { for (const [x, y, s] of [[170, 300, 0.6], [330, 210, 0.75], [250, 380, 0.45]]) d.group((dd) => dd.ink(`M0 140 C-90 70 -130 20 -130 -30 A64 64 0 0 1 0 -60 A64 64 0 0 1 130 -30 C130 20 90 70 0 140Z`, '#ff5c8a', 9), `translate(${x} ${y}) scale(${s})`); }]);
  emo.push(['music', 'Music notes', (d) => { d.ink(`M150 380 A50 38 0 1 1 200 360 L200 130 L360 90 L360 330 A50 38 0 1 1 410 310 L410 160 L250 200 L250 370Z`, '#2a2433', 4, '#2a2433'); }]);
  emo.push(['zzz', 'Sleep Zzz', (d) => { d.text(170, 420, 'Z', { size: 200, fill: '#cfe3ff', stroke: INK, sw: 10 }); d.text(300, 300, 'Z', { size: 150, fill: '#cfe3ff', stroke: INK, sw: 9 }); d.text(400, 200, 'z', { size: 110, fill: '#cfe3ff', stroke: INK, sw: 8 }); }]);
  emo.push(['gloom', 'Gloom lines', (d) => { for (let i = 0; i < 9; i++) { const x = 90 + i * 42; d.path(`M${x} 60 L${x} ${300 + (i % 3) * 50}`, 'none', { stroke: '#4a4a7a', sw: 6 + (i % 2) * 3 }); } }]);
  emo.push(['blush', 'Blush marks', (d) => { for (const cx of [150, 362]) { d.path(ellipse(cx, 256, 90, 46), '#ff8aa0', { op: 0.55 }); for (let i = 0; i < 4; i++) d.path(`M${cx - 50 + i * 30} 230 L${cx - 66 + i * 30} 282`, 'none', { stroke: '#e8506e', sw: 6 }); } }]);
  emo.push(['tears', 'Tear streams', (d) => { for (const cx of [170, 342]) d.ink(`M${cx - 20} 80 C${cx - 30} 260 ${cx - 50} 380 ${cx - 40} 460 L${cx + 40} 460 C${cx + 50} 380 ${cx + 30} 260 ${cx + 20} 80Z`, d.lin([[0, '#dff4ff'], [1, '#7cc4ff']]), 6); }]);
  emo.push(['stareyes', 'Star eyes', (d) => { for (const cx of [160, 352]) d.ink(burst(cx, 256, 5, 40, 95, 0, 1, -Math.PI / 2), '#ffd23a', 7); }]);
  emo.push(['skull', 'Skull (dread)', (d) => { d.ink(`M256 70 C390 70 430 170 410 260 C400 300 370 310 360 340 L360 400 L152 400 L152 340 C142 310 112 300 102 260 C82 170 122 70 256 70Z`, '#f2efe9', 8); d.path(ellipse(200, 250, 42, 46), INK); d.path(ellipse(312, 250, 42, 46), INK); d.path(`M256 300 L236 340 L276 340Z`, INK); }]);
  emo.push(['lightbulb', 'Idea bulb', (d) => { d.ink(`M256 60 A130 130 0 0 1 340 290 C320 310 316 330 316 360 L196 360 C196 330 192 310 172 290 A130 130 0 0 1 256 60Z`, d.rad([[0, '#ffffff'], [1, '#ffe066']]), 8); d.ink(`M196 370 L316 370 L310 420 L202 420Z`, '#b9bcc6', 6); for (let i = 0; i < 7; i++) { const a = -Math.PI * (0.05 + (i / 6) * 0.9); d.path(`M${256 + Math.cos(a) * 170} ${200 + Math.sin(a) * 170} L${256 + Math.cos(a) * 220} ${200 + Math.sin(a) * 220}`, 'none', { stroke: '#ffcc33', sw: 10 }); } }]);
  emo.push(['dizzy', 'Dizzy spiral', (d) => { const pts = []; for (let i = 0; i < 120; i++) { const a = i * 0.16, r = 10 + i * 1.6; pts.push([256 + Math.cos(a) * r, 256 + Math.sin(a) * r * 0.8]); } d.path(open(pts), 'none', { stroke: '#7a5cff', sw: 10 }); }]);
  emo.push(['steam', 'Steam puffs (fuming)', (d) => { for (const [x, y, r] of [[160, 300, 70], [256, 230, 90], [352, 300, 70]]) d.ink(blob(x, y, r, r * 0.8, 0.25, x), '#ffffff', 6); }]);
  emo.push(['shock', 'Shock lines', (d) => { for (let i = 0; i < 12; i++) { const a = (i / 12) * Math.PI * 2; d.path(`M${256 + Math.cos(a) * 110} ${256 + Math.sin(a) * 110} L${256 + Math.cos(a) * 230} ${256 + Math.sin(a) * 230}`, 'none', { stroke: INK, sw: 10 }); } }]);
  emo.push(['flowers', 'Happy flowers', (d) => { for (const [x, y, c] of [[150, 200, '#ff9ec7'], [350, 170, '#ffd36b'], [250, 360, '#9ad7ff']]) { for (let i = 0; i < 5; i++) { const a = (i / 5) * Math.PI * 2; d.ink(ellipse(x + Math.cos(a) * 34, y + Math.sin(a) * 34, 30, 30), c, 5); } d.ink(ellipse(x, y, 24, 24), '#fff3b0', 5); } }]);
  emo.push(['scribble', 'Frustration scribble', (d) => { const R = rng(9), pts = []; for (let i = 0; i < 40; i++) pts.push([140 + R() * 232, 140 + R() * 232]); d.path(smooth(pts, false), 'none', { stroke: INK, sw: 7 }); }]);
  for (const [id, name, fn] of emo) { const d = new Doc(512, 512); fn(d); out.push(A(`emo_${id}`, name, 'emotion', ['emotion', 'manga', 'symbol', 'reaction', ...id.split('_')], d)); }
  // ---- weather and falling things (tileable layers)
  const fall = [['rain_light', 'Rain (light)', 60, '#cfe6ff', 'rain'], ['rain_heavy', 'Rain (heavy)', 200, '#dbeeff', 'rain'], ['rain_storm', 'Rain (storm, slanted)', 260, '#e8f4ff', 'rain'], ['snow_light', 'Snow (light)', 90, '#ffffff', 'snow'], ['snow_heavy', 'Snow (heavy)', 260, '#ffffff', 'snow'], ['snow_blizzard', 'Snow (blizzard)', 420, '#f4f8ff', 'snow'], ['petals_sakura', 'Sakura petals', 70, '#ffc4d8', 'petal'], ['petals_red', 'Red petals', 60, '#ff5a6a', 'petal'], ['petals_white', 'White petals', 60, '#ffffff', 'petal'], ['leaves_autumn', 'Autumn leaves', 50, '#ff8a2a', 'leaf'], ['leaves_green', 'Green leaves', 50, '#6ac25a', 'leaf'], ['embers', 'Embers', 120, '#ffb03a', 'ember']];
  fall.forEach(([id, name, n, col, kind], q) => {
    const d = new Doc(1920, 1080), R = rng(q + 500), slant = id.includes('storm') ? 0.45 : kind === 'rain' ? 0.12 : 0;
    for (let i = 0; i < n; i++) {
      const x = R() * 1920, y = R() * 1080, s = 0.5 + R();
      if (kind === 'rain') { const L = 40 + R() * 70; d.path(`M${f(x)} ${f(y)} L${f(x - L * slant)} ${f(y + L)}`, 'none', { stroke: col, sw: 1.5 + R() * 1.8, op: 0.35 + R() * 0.5 }); }
      else if (kind === 'snow') d.circle(x, y, 2 + R() * 6 * s, col, { op: 0.6 + R() * 0.4 });
      else if (kind === 'petal') d.group((dd) => dd.ink(`M0 -12 C10 -10 14 4 0 14 C-14 4 -10 -10 0 -12Z`, col, 1.5, shade(col, -0.35)), `translate(${f(x)} ${f(y)}) rotate(${f(R() * 360)}) scale(${f(s * 1.3)})`);
      else if (kind === 'leaf') d.group((dd) => dd.ink(`M0 -16 C12 -10 12 8 0 16 C-12 8 -12 -10 0 -16Z M0 -16 L0 16`, mix(col, '#ffd23a', R() * 0.5), 1.8, shade(col, -0.45)), `translate(${f(x)} ${f(y)}) rotate(${f(R() * 360)}) scale(${f(s * 1.4)})`);
      else d.circle(x, y, 1.5 + R() * 3.5, mix(col, '#fff3b0', R()), { op: 0.5 + R() * 0.5 });
    }
    out.push(A(`weather_${id}`, name, 'weather', ['weather', kind, 'falling', 'layer', 'loop'], d, { fullscreen: true, blend: kind === 'ember' ? 'screen' : 'normal', tile: true }));
  });
  return out;
}
