// Face parts for drawn and cut-out animation: eye sets, brows, mouths (lip-sync and feelings), noses, ears, blush, tears.
import { Doc, smooth, ellipse, shade, mix, INK, f } from './svg.js';

export function faces() {
  const out = [], A = (id, name, sub, d, tags = []) => out.push({ id: `face_${id}`, name, cat: 'faces', sub, tags: ['face', sub, ...tags], w: d.w, h: d.h, svg: d.svg() });
  // ---- eyes: 10 styles × the open set
  const styles = [['shoujo', '#7a4ad9', 1.25, 3], ['shonen', '#d98a1a', 1.0, 2], ['calm', '#3a7a5a', 0.85, 2], ['sharp', '#c0303a', 0.7, 1], ['round', '#3a7ad9', 1.15, 2], ['tired', '#7a8290', 0.6, 1], ['sparkly', '#ff6aa8', 1.3, 4], ['villain', '#e3b23c', 0.65, 1], ['cute', '#4ac0c0', 1.2, 3], ['elder', '#6a4a2a', 0.7, 1]];
  for (const [k, ec, tall, glints] of styles) {
    const d = new Doc(512, 256);
    for (const s of [-1, 1]) {
      const x = 256 + s * 110, y = 140, ew = 62, eh = 52 * tall;
      d.ink(smooth([[x - ew, y], [x - ew * 0.7, y - eh], [x + ew * 0.7, y - eh], [x + ew, y], [x + ew * 0.6, y + eh * 0.55], [x - ew * 0.6, y + eh * 0.55]], true, 0.8), '#ffffff', 3);
      d.path(ellipse(x + s * 4, y - eh * 0.2, ew * 0.55, eh * 0.75), d.lin([[0, shade(ec, -0.5)], [0.6, ec], [1, shade(ec, 0.4)]]));
      d.path(ellipse(x + s * 4, y - eh * 0.2, ew * 0.22, eh * 0.32), INK);
      for (let g = 0; g < glints; g++) d.circle(x - ew * 0.2 + g * 10, y - eh * 0.5 + g * 16, 13 - g * 3, '#ffffff', { op: 1 - g * 0.15 });
      d.path(`M${x - ew * 1.12} ${y - eh * 0.08} Q${x - ew * 0.2} ${y - eh * 1.2} ${x + ew * 1.15} ${y - eh * 0.45}`, 'none', { stroke: INK, sw: k === 'sharp' || k === 'villain' ? 11 : 9 });
      d.path(`M${x - ew} ${y - eh * 1.55} Q${x} ${y - eh * (k === 'villain' ? 1.4 : 1.9)} ${x + ew * 1.05} ${y - eh * (k === 'villain' ? 1.95 : 1.6)}`, 'none', { stroke: INK, sw: 7 });
    }
    A(`eyes_${k}`, `Eyes — ${k}`, 'eyes', d, [k]);
  }
  // ---- mouths: lip-sync set + feelings
  const M = { rest: (d) => d.path('M200 130 Q256 140 312 130', 'none', { stroke: INK, sw: 7 }), A: (d) => open(d, 70, 90), E: (d) => { open(d, 90, 44); d.path('M190 120 h132 v14 h-132Z', '#ffffff'); }, I: (d) => { open(d, 80, 30); d.path('M196 122 h120 v10 h-120Z', '#ffffff'); }, O: (d) => open(d, 46, 66, 0.3), U: (d) => open(d, 30, 34, 0.2), M: (d) => d.path('M206 128 L306 128', 'none', { stroke: INK, sw: 9 }), F: (d) => { d.path('M200 130 Q256 118 312 130', 'none', { stroke: INK, sw: 7 }); d.path('M224 130 h64 v16 h-64Z', '#ffffff', { stroke: INK, sw: 3 }); }, smile: (d) => d.path('M180 110 Q256 180 332 110', 'none', { stroke: INK, sw: 8 }), grin: (d) => { open(d, 90, 50, 0.85); d.path('M178 118 h156 v18 h-156Z', '#ffffff'); }, frown: (d) => d.path('M190 150 Q256 96 322 150', 'none', { stroke: INK, sw: 8 }), shout: (d) => { open(d, 96, 120, 0.6); d.path('M176 110 h160 v18 h-160Z', '#ffffff'); }, cat: (d) => d.path('M190 120 Q222 150 256 122 Q290 150 322 120', 'none', { stroke: INK, sw: 7 }), smirk: (d) => d.path('M200 132 Q270 140 320 108', 'none', { stroke: INK, sw: 7 }), pout: (d) => d.ink(smooth([[220, 128], [256, 112], [292, 128], [256, 146]], true), '#e8707e', 4), teeth: (d) => { open(d, 92, 40, 0.9); d.path('M176 112 h160 v40 h-160Z', '#ffffff'); d.path('M176 132 h160', 'none', { stroke: '#cfc8c0', sw: 3 }); } };
  function open(d, ow, oh, round = 0.5) { const cx = 256, y = 116; d.ink(smooth([[cx - ow, y], [cx - ow * round, y - oh * 0.3], [cx + ow * round, y - oh * 0.3], [cx + ow, y], [cx + ow * round, y + oh], [cx - ow * round, y + oh]], true, 0.8), '#7a2a34', 5); d.path(ellipse(cx, y + oh * 0.68, ow * 0.55, oh * 0.24), '#e86a7a'); }
  for (const [k, fn] of Object.entries(M)) { const d = new Doc(512, 256); fn(d); A(`mouth_${k}`, `Mouth — ${k}`, 'mouths', d, ['lip-sync', 'viseme', k]); }
  // ---- noses, ears, brows, extras
  const noses = [['dot', (d) => d.circle(256, 130, 6, shade('#e8b090', -0.4))], ['line', (d) => d.path('M260 80 L246 150 L262 154', 'none', { stroke: '#9a6a54', sw: 5 })], ['hook', (d) => d.path('M262 70 Q246 130 236 152 Q256 164 270 152', 'none', { stroke: '#9a6a54', sw: 5 })], ['button', (d) => d.path(ellipse(256, 132, 22, 16), '#f2b8a0', { stroke: '#9a6a54', sw: 4 })], ['shadow', (d) => d.path('M262 80 L246 150 L270 150Z', '#d9a080', { op: 0.6 })], ['nostrils', (d) => { d.circle(240, 140, 6, '#9a6a54'); d.circle(272, 140, 6, '#9a6a54'); }]];
  for (const [k, fn] of noses) { const d = new Doc(512, 256); fn(d); A(`nose_${k}`, `Nose — ${k}`, 'noses', d); }
  for (const s of ['left', 'right']) { const d = new Doc(256, 256), m = s === 'left' ? 1 : -1; d.ink(`M${128 + m * -30} 40 Q${128 + m * 70} 40 ${128 + m * 60} 128 Q${128 + m * 50} 210 ${128 + m * -20} 210`, '#f2cba8', 6); d.path(`M${128 + m * -10} 80 Q${128 + m * 40} 90 ${128 + m * 30} 140`, 'none', { stroke: '#c48a6a', sw: 5 }); A(`ear_${s}`, `Ear — ${s}`, 'ears', d); }
  const brows = [['neutral', 'M150 150 Q256 110 362 150'], ['angry', 'M150 110 L362 170'], ['sad', 'M150 170 L362 110'], ['raised', 'M150 120 Q256 60 362 120'], ['thick', 'M150 150 Q256 100 362 150'], ['worried', 'M150 160 Q256 120 362 110']];
  for (const [k, pth] of brows) { const d = new Doc(512, 256); d.path(pth, 'none', { stroke: INK, sw: k === 'thick' ? 22 : 12 }); A(`brow_${k}`, `Brow — ${k}`, 'brows', d); }
  { const d = new Doc(512, 256); for (const x of [150, 362]) { d.path(ellipse(x, 128, 80, 40), '#ff8aa0', { op: 0.5 }); for (let i = 0; i < 4; i++) d.path(`M${x - 44 + i * 28} 106 l-14 46`, 'none', { stroke: '#e8506e', sw: 6 }); } A('blush', 'Blush', 'extras', d); }
  { const d = new Doc(512, 256); for (const x of [180, 332]) d.ink(`M${x} 30 C${x - 26} 120 ${x - 30} 190 ${x} 230 C${x + 30} 190 ${x + 26} 120 ${x} 30Z`, d.lin([[0, '#e8f7ff'], [1, '#7cc4ff']]), 5); A('tears', 'Tears', 'extras', d); }
  { const d = new Doc(512, 256); d.path('M140 60 L372 60 L372 200 L140 200Z', 'none'); for (let i = 0; i < 7; i++) d.path(`M${170 + i * 30} 60 L${170 + i * 30} ${150 + (i % 2) * 30}`, 'none', { stroke: '#4a4a7a', sw: 8 }); A('gloom', 'Gloom (forehead lines)', 'extras', d); }
  { const d = new Doc(512, 256); d.ink('M256 40 C300 110 320 150 320 180 A64 64 0 1 1 192 180 C192 150 212 110 256 40Z', d.lin([[0, '#e8f7ff'], [1, '#7fc6ff']]), 6); A('sweat', 'Sweat drop (face)', 'extras', d); }
  return out;
}
