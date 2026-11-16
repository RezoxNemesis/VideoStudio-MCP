// The finish over the drawings: node devtests/finish.mjs        (exit 1 when a rule is broken)
// Anime gets its light in the last pass ("photography"): the figure arrives flat-painted, and where it is laid on the
// background (1) the background's light bleeds a little over its edge — light wrap, just short of being noticed —
// (2) a soft gradient goes over it, lighter towards the light and deeper away from it, and (3) the whole picture
// gets a soft diffusion. The Papa film came back for figures that looked cut out and stuck on (16 Nov 2026).
import { createCanvas } from '@napi-rs/canvas';
import { finishFigure, diffuse } from '../engine/finish.js';
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(3) : JSON.stringify(got)}`); };
const W = 320, H = 180, S = 100; // the figure: a disc of radius 40 in a 100 × 100 sprite at (110, 40)
const world = (fill) => { const c = createCanvas(W, H), g = c.getContext('2d'); g.fillStyle = fill; g.fillRect(0, 0, W, H); return g; };
const figure = (fill = '#3a3a44') => { const c = createCanvas(S, S), g = c.getContext('2d'); g.fillStyle = fill; g.beginPath(); g.arc(50, 50, 40, 0, 6.2832); g.fill(); return { canvas: c, x: 110, y: 40, w: S, h: S }; };
const px = (spr, x, y) => [...spr.canvas.getContext('2d').getImageData(x, y, 1, 1).data];
const lum = (p) => 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2];
const all = (spr) => spr.canvas.getContext('2d').getImageData(0, 0, S, S).data;

// ---- 1. light wrap
{
  const g = world('#ffd9a0'), a = figure(), before = { edge: px(a, 50, 13), mid: px(a, 50, 50) }, A0 = all(a);
  finishFigure(g, a, { wrap: 0.55, para: 0 }, { H: 1080 });
  const edge = px(a, 50, 13), mid = px(a, 50, 50), gain = lum(edge) - lum(before.edge), room = lum([255, 217, 160]) - lum(before.edge);
  ok(gain > 6, 'before a bright background the figure\'s edge takes some of its light', gain);
  ok(edge[0] - before.edge[0] > edge[2] - before.edge[2], 'and its colour (a warm background warms the edge)', [edge[0] - before.edge[0], edge[2] - before.edge[2]]);
  ok(gain < room * 0.45, 'just short of being noticed: well under half the way to the background', gain / room);
  ok(Math.abs(lum(mid) - lum(before.mid)) < 2, 'the middle of the figure is left alone', lum(mid) - lum(before.mid));
  const A1 = all(a); let dA = 0; for (let i = 3; i < A0.length; i += 4) dA = Math.max(dA, Math.abs(A1[i] - A0[i]));
  ok(dA <= 2, 'the figure keeps its outline (nothing is added outside it)', dA);
  const d = figure(), g2 = world('#0a1030'), D0 = all(d); finishFigure(g2, d, { wrap: 0.55, para: 0 }, { H: 1080 });
  const D1 = all(d); let up = 0; for (let i = 0; i < D0.length; i += 4) up = Math.max(up, lum([D1[i], D1[i + 1], D1[i + 2]]) - lum([D0[i], D0[i + 1], D0[i + 2]]));
  ok(up < 2, 'a dark background gives nothing: light bleeds, darkness does not', up);
  const untouched = world('#ffd9a0'), u0 = untouched.getImageData(0, 0, W, H).data.slice(); finishFigure(untouched, figure(), { wrap: 0.55, para: 0.2 }, { H: 1080 });
  const u1 = untouched.getImageData(0, 0, W, H).data; let dw = 0; for (let i = 0; i < u0.length; i++) dw = Math.max(dw, Math.abs(u0[i] - u1[i]));
  ok(dw === 0, 'the picture itself is not touched by it', dw);
}
// ---- 2. the gradient over the figure
{
  const g = world('#40404a'), a = figure('#8a7a70'), A0 = all(a);
  finishFigure(g, a, { wrap: 0, para: 0.22 }, { H: 1080, light2: [-0.7, -0.7] });
  const lit = lum(px(a, 30, 30)), far = lum(px(a, 70, 70)), mid = lum(px(a, 50, 50));
  ok(lit > far * 1.04 && lit < far * 1.4, 'the side towards the light is lighter than the side away, gently', lit / far);
  ok(Math.abs(mid - lum([0x8a, 0x7a, 0x70])) < 9, 'the middle keeps its own tone', mid - lum([0x8a, 0x7a, 0x70]));
  const A1 = all(a); let dA = 0; for (let i = 3; i < A0.length; i += 4) dA = Math.max(dA, Math.abs(A1[i] - A0[i]));
  ok(dA <= 2, 'and its outline', dA);
  const b = figure('#8a7a70'); finishFigure(g, b, { wrap: 0, para: 0.22 }, { H: 1080, light2: [0.7, -0.7] });
  ok(lum(px(b, 70, 30)) > lum(px(b, 30, 70)), 'it follows the light (from the other side, the other side is lighter)', [lum(px(b, 70, 30)), lum(px(b, 30, 70))]);
}
// ---- 3. no finish asked: nothing changes
{
  const g = world('#ffd9a0'), a = figure(), A0 = all(a); finishFigure(g, a, null, { H: 1080 }); finishFigure(g, a, { wrap: 0, para: 0 }, { H: 1080 });
  const A1 = all(a); let d = 0; for (let i = 0; i < A0.length; i++) d = Math.max(d, Math.abs(A0[i] - A1[i]));
  ok(d === 0, 'a look without a finish leaves the figure as painted', d);
}
// ---- 4. diffusion over the whole picture
{
  const g = world('#181c2c'); g.fillStyle = '#fff4d0'; g.fillRect(150, 80, 20, 20);
  const at = (x, y) => lum([...g.getImageData(x, y, 1, 1).data]), near0 = at(140, 90), far0 = at(40, 30), spot0 = at(160, 90);
  diffuse(g, W, H, 0.35, g.canvas);
  ok(at(140, 90) - near0 > 3, 'light spreads softly round what is bright', at(140, 90) - near0);
  ok(at(160, 90) >= spot0 - 2, 'the bright thing itself is no dimmer', at(160, 90) - spot0);
  ok(Math.abs(at(40, 30) - far0) < 5, 'far from it the dark stays dark (blacks are not lifted into grey)', at(40, 30) - far0);
  const flat = world('#808080'); diffuse(flat, W, H, 0.35, flat.canvas); const f = lum([...flat.getImageData(100, 100, 1, 1).data]);
  ok(Math.abs(f - 128) < 6, 'an even grey stays that grey', f - 128);
}
// ---- 5. the looks that are painted ask for it
{
  const { resolveLook, postLook } = await import('../engine/look.js');
  for (const name of ['multiplane', 'anime']) { const L = resolveLook(name); ok(L.finish?.wrap > 0 && L.finish?.para > 0, `the ${name} look seats its figures (wrap and gradient)`, L.finish ?? null); ok((L.post ?? []).includes('diffuse') && L.diffuse > 0, `and softens the whole picture`, [L.post, L.diffuse]); }
  ok(!resolveLook('flat').finish && !resolveLook('paper').finish, 'flat and paper looks stay flat', [resolveLook('flat').finish ?? null, resolveLook('paper').finish ?? null]);
  const g = world('#181c2c'); g.fillStyle = '#fff4d0'; g.fillRect(150, 80, 20, 20); const before = lum([...g.getImageData(140, 90, 1, 1).data]);
  postLook({ post: ['diffuse'], diffuse: 0.35 }, g, W, H, { canvas: g.canvas });
  ok(lum([...g.getImageData(140, 90, 1, 1).data]) - before > 3, "post: ['diffuse'] is a pass a look can ask for", lum([...g.getImageData(140, 90, 1, 1).data]) - before);
}
if (fails.length) { console.error(`finish: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`finish: all ${checks} rules hold`);
