// Costume that moves: node devtests/costume.mjs        (exit 1 when a rule is broken)
// A robe's skirts hang from the waist of a real body, open in front, and trail behind a moving figure; hair tied
// high hangs down from its tie (it does not stick out like a rod) and the tie is a cord of its own colour.
import fs from 'node:fs';
import { Character, makeDesign } from '../engine/character.js';
import { makeHair, hairMeshes } from '../engine/hair.js';
import { CelPainter } from '../engine/body.js';
import { makeCam } from '../engine/camera.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(3) : JSON.stringify(got)}`); };
const POSE = JSON.parse(fs.readFileSync(new URL('./fixtures/pose_standing.json', import.meta.url), 'utf8'));
const poseAt = (dx = 0) => ({ height: POSE.height, id: 'x', props: {}, contact: 0, j: Object.fromEntries(Object.entries(POSE.j).map(([k, v]) => [k, [v[0] + dx, v[1], v[2]]])) });
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

// ---- 1. a robe
{
  const plain = new Character(makeDesign({ name: 'plain' })); plain.step(poseAt(), 1 / 24, 0, {});
  ok(!plain.robe, 'a design without a robe has none', !!plain.robe);
  const ch = new Character(makeDesign({ name: 'robed', robe: { color: '#1c1b21', lining: '#8f1d23', len: 0.46, open: 0.18, trim: '#c9a24a' } }));
  for (let f = 0; f < 60; f++) ch.step(poseAt(), 1 / 24, f / 24, {});
  const R = ch.robe; ok(!!R && R.cols >= 9 && R.rows >= 5, 'a design with a robe gets its skirts: a sheet of cloth', R && [R.cols, R.rows]);
  if (R) {
    const F = ch.frame(poseAt()), top = R.p.map((c) => c[0]), hem = R.p.map((c) => c[R.rows - 1]), len = 0.46 * POSE.height;
    const round = top.map((p) => { const d = sub(p, F.pel); return Math.atan2(dot(d, F.sP), dot(d, F.fP)) * 180 / Math.PI; }); // 0 = straight in front, ±180 = behind
    ok(top.every((p) => Math.abs(dot(sub(p, F.pel), F.up)) < 0.16 && Math.hypot(dot(sub(p, F.pel), F.sP), dot(sub(p, F.pel), F.fP)) < 0.3), 'it hangs from the waist', top.map((p) => +dot(sub(p, F.pel), F.up).toFixed(2)));
    ok(round.every((a) => Math.abs(a) > 0.18 * 180 - 4), 'open in front: no thread within the opening', round.map(Math.round));
    ok(round.some((a) => Math.abs(a) > 170) && round.some((a) => a > 60 && a < 120) && round.some((a) => a < -60 && a > -120), 'and goes round both sides and the back', round.map(Math.round));
    ok(hem.every((p, c) => top[c][1] - p[1] > len * 0.75 && top[c][1] - p[1] < len * 1.05), 'it hangs its full length', hem.map((p, c) => +(top[c][1] - p[1]).toFixed(2)));
    ok(hem.every((p) => p[1] > 0.005), 'and stays above the ground', Math.min(...hem.map((p) => p[1])));
    // moving through still air: the skirts stream behind — every hem further back than it hung at rest
    const still = R.p.map((c) => c[0][0] - c[R.rows - 1][0]), air = { wind: () => [0, 0, 0] };
    for (let f = 0; f < 36; f++) ch.step(poseAt(f * 0.08), 1 / 24, 2.5 + f / 24, air);
    const lag = R.p.reduce((s, c, i) => s + (c[0][0] - c[R.rows - 1][0] - still[i]) / R.cols, 0);
    ok(lag > 0.03, 'a figure that moves through the air trails its skirts behind it (the hems lag by more than 3 cm)', lag);
    const { robeMesh } = await import('../engine/bodydraw.js');
    const m = typeof robeMesh === 'function' ? robeMesh(ch) : null;
    ok(m && m.pos.length > 0 && m.u && m.u.length === m.pos.length / 3 && Math.min(...m.u) === 0 && Math.max(...m.u) === 1, 'robeMesh(character) gives the skirts as a mesh, each point with its place between waist (0) and hem (1)', m && [m.pos.length / 3, m.u?.length]);
    if (m) {
      // seen from behind, the skirts show their outside (the robe's own colour), not the lining
      const F2 = ch.frame(poseAt(35 * 0.08)), mid = R.p[Math.floor(R.cols / 2)][Math.floor(R.rows / 2)], cam = makeCam({ pos: [mid[0] - F2.fP[0] * 3, mid[1] + 0.2, mid[2] - F2.fP[2] * 3], target: mid, fov: 30 }, 240, 240);
      const cp = new CelPainter(cam, { ss: 2, light: [0, 0.7, 0.7], lines: false, shadows: false }); cp.add(m.pos, m.idx, m.paint, null, m.u);
      const r = cp.finish(), q = cam.project(mid), px = r.canvas.getContext('2d').getImageData(Math.round(q[0] - r.x), Math.round(q[1] - r.y), 1, 1).data;
      ok(px[3] > 200 && px[0] < 90 && Math.abs(px[0] - px[1]) < 30, 'seen from behind the skirts show their outside: the robe\'s own dark colour, not its red lining', [px[0], px[1], px[2]]);
    }
    ok(m && m.paint && Array.isArray(m.paint.bands) && m.paint.bands.length === 1 && m.paint.bands[0][0] > 0.85 && m.paint.back, 'with the robe\'s paint: its lining inside and its trim along the hem', m && m.paint && [m.paint.bands?.length, !!m.paint.back]);
  }
}

// ---- 1b. sleeves of their own colour (a dark vest over a white robe)
{
  const { limbPaint } = await import('../engine/bodydraw.js');
  const base = { fill: [28, 27, 33], shade: [14, 14, 18], group: 'top' }, rgb = (c) => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  const with_ = typeof limbPaint === 'function' ? limbPaint(base, 'tunic', { body: { sleeves: '#ece6da' } }, rgb) : null;
  ok(with_ && with_.fill[0] > 200 && with_.shade[0] < with_.fill[0] && with_.group !== 'top', 'design.body.sleeves: the arms of the top take that colour, with a line where they meet the body of it', with_ && [with_.fill, with_.group]);
  const without = typeof limbPaint === 'function' ? limbPaint(base, 'tunic', { body: {} }, rgb) : null;
  ok(without && without.fill === base.fill, 'without it the sleeves are the garment\'s own colour', without && without.fill);
  const other = typeof limbPaint === 'function' ? limbPaint(base, 'trousers', { body: { sleeves: '#ece6da' } }, rgb) : null;
  ok(other && other.fill === base.fill, 'and only the top has sleeves', other && other.fill);
}

// ---- 1c. a top cut like a robe: lapels that cross at the chest over an under-robe, sleeves set in at a clean seam
{
  const BD = await import('../engine/bodydraw.js'), rgb = (c) => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  const base = { fill: [28, 27, 33], shade: [14, 14, 18], group: 'top' };
  // the measures of a body's top (metres, rest pose): the neck, where the tunic ends beside it and behind it, the sash, the shoulder joint and the way the arm leaves it
  const G = { neckY: 1.53, top: 1.6, backTop: 1.61, sashY: 1.1, shoulder: [0.23, 1.42], arm: [0.66, -0.75] };
  const P = typeof BD.topPaint === 'function' ? BD.topPaint(base, { body: { sleeves: '#ece6da', collar: { style: 'cross', depth: 0.12, w: 0.03, inner: '#ece6da', trim: '#b08a3c' } } }, G, rgb) : null;
  ok(!!P && typeof P.map === 'function' && Array.isArray(P.maps), 'topPaint(base, design, measures): a top with a collar or sleeves of its own is painted by a map of its cut', P && Object.keys(P));
  if (P?.map) {
    const tone = (x, y) => { const k = P.map(x, y); return k < 0 ? 'top' : P.maps[k].name; }, B10 = 10;
    ok(tone(0.09, 1.3) === 'top' && tone(-0.12, 1.25) === 'top', 'the chest and the ribs are the top\'s own colour', [tone(0.09, 1.3), tone(-0.12, 1.25)]);
    ok(tone(0, 1.5) === 'inner' && tone(0.01, 1.46) === 'inner', 'in the opening under the throat the under-robe shows', [tone(0, 1.5), tone(0.01, 1.46)]);
    ok(P.maps.find((m) => m.name === 'inner').fill[0] > 200 && P.maps.find((m) => m.name === 'trim').fill[0] > 150 && P.maps.find((m) => m.name === 'trim').fill[2] < 90, 'in the colours of the design: a pale under-robe, a gold band', P.maps.map((m) => m.fill));
    // the lapel from the wearer's left lies over the other: its edge runs from the left of the neck down across the chest to the right of the waist
    const edge1 = (y) => 0.062 - (0.062 / (1.6 - (1.53 - 0.12))) * (1.6 - y);
    ok([1.55, 1.45, 1.35, 1.25, 1.15].every((y) => tone(edge1(y) + 0.012, y) === 'trim'), 'a band runs along the edge of the upper lapel, from beside the neck down across the chest to the waist', [1.55, 1.45, 1.35, 1.25, 1.15].map((y) => tone(edge1(y) + 0.012, y)));
    ok(tone(-edge1(1.5) - 0.012, 1.5) === 'trim' && tone(-edge1(1.3) - 0.012, 1.3) === 'top', 'the under lapel shows its band only above where they cross', [tone(-edge1(1.5) - 0.012, 1.5), tone(-edge1(1.3) - 0.012, 1.3)]);
    ok(tone(-0.05, 1.05) === 'top' && tone(0.0, 1.0) === 'top', 'below the sash there is no collar', [tone(-0.05, 1.05), tone(0, 1)]);
    ok(tone(0.4, 1.25) === 'sleeve' && tone(-0.4, 1.25) === 'sleeve' && tone(0.3, 1.38) === 'sleeve', 'out along the arm is sleeve, both sides', [tone(0.4, 1.25), tone(-0.4, 1.25), tone(0.3, 1.38)]);
    ok(tone(0.17, 1.44) !== 'sleeve' && tone(0.15, 1.3) !== 'sleeve', 'the shoulder and the side of the chest are not', [tone(0.17, 1.44), tone(0.15, 1.3)]);
    // the seam is one straight cut across the arm at the shoulder: walking out along the arm the tone changes once
    const walk = []; for (let t = -0.1; t <= 0.12; t += 0.005) walk.push(tone(G.shoulder[0] + G.arm[0] * t - G.arm[1] * 0.03, G.shoulder[1] + G.arm[1] * t + G.arm[0] * 0.03) === 'sleeve');
    ok(walk.filter((v, i) => i && v !== walk[i - 1]).length === 1 && !walk[0] && walk[walk.length - 1], 'the sleeve is set in at one clean seam across the arm', walk.map((v) => (v ? 1 : 0)).join(''));
    // the back: points of the back are given 10 m up (a top's back has no opening)
    ok(tone(0, 1.6 + B10) === 'trim' && tone(0, 1.5 + B10) === 'top' && tone(0.02, 1.3 + B10) === 'top', 'behind, the collar is a band round the neck and the back is whole', [tone(0, 1.6 + B10), tone(0, 1.5 + B10), tone(0.02, 1.3 + B10)]);
    ok(tone(0.4, 1.25 + B10) === 'sleeve', 'and the sleeves are sleeves from behind too', tone(0.4, 1.25 + B10));
    ok(P.maps.find((m) => m.name === 'sleeve').group !== 'top' && P.maps.find((m) => m.name === 'trim').group !== 'top', 'sleeve and band are forms of their own: a line is drawn where they meet the top', P.maps.map((m) => m.group));
  }
  const V = typeof BD.topPaint === 'function' ? BD.topPaint(base, { body: { collar: { style: 'v', depth: 0.1, inner: '#ece6da' } } }, G, rgb) : null;
  if (V?.map) {
    const tone = (x, y) => { const k = V.map(x, y); return k < 0 ? 'top' : V.maps[k].name; };
    ok(tone(0, 1.5) === 'inner' && [[0.05, 1.3], [0.03, 1.2], [0.08, 1.4]].every(([x, y]) => tone(x, y) === tone(-x, y)) && tone(0.02, 1.3) === 'top', 'style: \'v\' is a neck cut to a point: the same both sides, closed below the point', [tone(0, 1.5), tone(0.02, 1.3)]);
    ok(tone(0.4, 1.25) === 'top', 'without sleeves of their own the arms are the top\'s colour', tone(0.4, 1.25));
  } else ok(false, 'a v collar is made', !!V);
  const none = typeof BD.topPaint === 'function' ? BD.topPaint(base, { body: {} }, G, rgb) : null;
  ok(none === base, 'a design with neither keeps the top\'s plain paint', none === base);
}

// ---- 2. hair tied high
{
  // a scalp: the top of a ball, with the landmarks a head has
  const pos = [], idx = [], N = 10, M = 16, c = [0, 1.66, 0.03];
  for (let i = 0; i <= N; i++) for (let k = 0; k < M; k++) { const el = (i / N) * 1.25, az = (k / M) * Math.PI * 2; pos.push(c[0] + Math.sin(el) * Math.sin(az) * 0.085, c[1] + Math.cos(el) * 0.115, c[2] + Math.sin(el) * Math.cos(az) * 0.1); }
  for (let i = 0; i < N; i++) for (let k = 0; k < M; k++) { const a = i * M + k, b = i * M + (k + 1) % M; idx.push(a, a + M, b, b, a + M, b + M); }
  const P = Float32Array.from(pos), B = { parts: [{ name: 'haircap', pos: P, rest0: P, idx: Uint32Array.from(idx), n: P.length / 3 }], landmarks: { crown: [0, 1.775, -0.01], nape: [0, 1.6, -0.07], eyeL: [0.032, 1.66, 0.13], neckY: 1.52 }, bi: { head: 0 }, R: Float32Array.from([1, 0, 0, 0, 1, 0, 0, 0, 1]), T: new Float32Array(3) };
  const H = makeHair(B, { cut: 'tied', tail: { len: 0.6, w: 0.07, at: 'high', tie: true } }, 3);
  const ms = hairMeshes(H, B, 1 / 24, 0, null), tail = ms[1], tie = ms.find((m) => m.tie);
  ok(ms.length >= 2 && tail && tail.pos.length > 0, 'tied hair has its tail', ms.length);
  if (tail) {
    let top = -1e9, bot = 1e9; for (let i = 1; i < tail.pos.length; i += 3) { top = Math.max(top, tail.pos[i]); bot = Math.min(bot, tail.pos[i]); }
    ok(top - bot > 0.6 * 0.55, 'from the first frame the tail hangs down from its tie (more than half its length below it), not out like a rod', top - bot);
  }
  ok(!!tie && tie.pos.length >= 18 && tie.idx.length >= 18, 'the tie is a band of its own, for its own colour', tie && tie.pos.length / 3);
  const plainTail = hairMeshes(makeHair(B, 'tied', 3), B, 1 / 24, 0, null);
  ok(!plainTail.some((m) => m.tie), 'hair tied without a cord shows none', plainTail.length);
}

if (fails.length) { console.error(`costume: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`costume: ${checks} rules hold`);
