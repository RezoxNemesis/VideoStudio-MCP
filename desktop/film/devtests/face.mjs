// A real head's face has features to draw: node devtests/face.mjs        (exit 1 when a rule is broken)
// The head of a real body is a sculpted mesh painted in two flat tones; seen from the front, a nose leaves no mark at
// all. The engine finds where the nose, the lips and the chin are on any head, and lays drawn tones on the skin there
// (the shadow side of the nose and the shadow under it, the lips, the shadow under the lower lip, the crease of the
// lids), and lets the hair throw its shadow on the brow.
import { CelPainter } from '../engine/body.js';
import { makeCam } from '../engine/camera.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const near = (a, b, tol) => Math.abs(a - b) <= tol;

// ---- a made-up head: a curved face with a nose, lips and a chin of known places (metres; y from the line of the eyes)
const EYE = [0.032, 1.6, 0.137], TRUE = { nasion: -0.004, tip: -0.042, sub: -0.058, lipTop: -0.070, mouth: -0.078, landmark: -0.0824, bend: -2, corner: 0.027, lipLow: -0.090, dip: -0.099, chin: -0.109, alar: 0.016 };
const seamAt = (x) => TRUE.mouth + TRUE.bend * x * x;
const g = (u, s) => Math.exp(-(u / s) * (u / s));
function surface(x, y) {
  const up = (y - TRUE.nasion) / (TRUE.tip - TRUE.nasion), ramp = y > TRUE.nasion ? 0 : y > TRUE.tip ? up * up * (3 - 2 * up) : Math.max(0, 1 - (TRUE.tip - y) / (TRUE.tip - TRUE.sub)) ** 1.5;
  return 0.158 - 2.4 * x * x + 0.004 * g(y - 0.03, 0.012) + 0.021 * ramp * g(x, TRUE.alar * 0.8)
    + 0.0045 * g(y - TRUE.lipTop, 0.005) * g(x, 0.03) + 0.004 * g(y - TRUE.lipLow, 0.005) * g(x, 0.026) - 0.002 * g(y - seamAt(x), 0.0025) * g(x, 0.03) + 0.004 * g(y - TRUE.chin, 0.008) * g(x, 0.03);
}
function madeHead() {
  const xs = [], ys = []; for (let x = -0.07; x <= 0.0701; x += 0.0025) xs.push(x); for (let y = -0.135; y <= 0.0601; y += 0.00125) ys.push(y);
  const pos = new Float32Array(xs.length * ys.length * 3), lower = new Float32Array(xs.length * ys.length), idx = []; let n = 0;
  // the lower lip and the jaw hang from the jaw's bone: everything below the line where the lips meet, between the corners of the mouth, and the chin under them
  for (const y of ys) for (const x of xs) { pos[n * 3] = x; pos[n * 3 + 1] = EYE[1] + y; pos[n * 3 + 2] = surface(x, y); lower[n] = y < seamAt(x) && (Math.abs(x) < TRUE.corner || y < TRUE.dip) ? 1 : 0; n++; }
  for (let j = 0; j < ys.length - 1; j++) for (let i = 0; i < xs.length - 1; i++) { const a = j * xs.length + i; idx.push(a, a + 1, a + xs.length, a + 1, a + xs.length + 1, a + xs.length); }
  return { pos, lower, idx: Uint32Array.from(idx), n, lm: { eyeL: EYE, eyeR: [-EYE[0], EYE[1], EYE[2]], mouth: [0, EYE[1] + TRUE.landmark, surface(0, TRUE.landmark)], eyeRadius: 0.0173 } };
}

const F3 = await import('../engine/face3.js').catch(() => ({}));
const head = madeHead();

// ---- 1. the features are found where they are
const M = typeof F3.faceMarksOf === 'function' ? F3.faceMarksOf(head.pos, head.idx, head.lm, head.lower) : null;
ok(!!M, 'faceMarksOf(pos, triangles, landmarks, jaw) finds the features of a head', M);
if (M) {
  ok(near(M.tip, TRUE.tip, 0.004), 'the tip of the nose is found (within 4 mm)', M.tip);
  ok(near(M.nasion, TRUE.nasion, 0.008), 'the root of the nose between the eyes is found (within 8 mm)', M.nasion);
  ok(near(M.sub, TRUE.sub, 0.005), 'the foot of the nose above the lip is found (within 5 mm)', M.sub);
  ok(near(M.mouth, TRUE.mouth, 0.002), 'the line where the lips meet is found from the lips themselves (within 2 mm), not taken from the body\'s landmark below it', M.mouth);
  ok(M.seamA < -0.4 && M.seamA > -4, 'the line of the mouth bends down towards its corners as the lips do', M.seamA);
  const bare = F3.faceMarksOf(head.pos, head.idx, head.lm);
  ok(near(bare.mouth, TRUE.landmark, 0.001) && bare.seamA === 0, 'a head that does not say which skin is the lower lip: the landmark, a level line', [bare.mouth, bare.seamA]);
  ok(near(M.chin, TRUE.chin, 0.008), 'the chin is found (within 8 mm)', M.chin);
  ok(M.alar > 0.009 && M.alar < 0.024, 'the nose is as wide as a nose (9–24 mm to each side)', M.alar);
  ok(near(M.mouthW, TRUE.corner, 0.004), 'the mouth is as wide as a mouth: its corners lie under the middle of the eyes (within 4 mm)', M.mouthW);
}

// ---- 2. drawn tones lie on the skin where the features are
const D = M && typeof F3.faceDecals === 'function' ? F3.faceDecals(M, { shadeSide: 1 }) : null;
ok(!!D && typeof D.map === 'function' && Array.isArray(D.names), 'faceDecals(marks, { shadeSide }) gives a map of drawn tones over the face', D && D.names);
if (D) {
  const at = (x, y) => { const k = D.map(x, y); return k < 0 ? 'skin' : D.names[k]; };
  ok(at(0, M.tip - 0.009) === 'noseUnder', 'under the tip of the nose: its shadow', at(0, M.tip - 0.009));
  ok(at(M.alar * 0.62, M.tip + 0.006) === 'noseSide', 'beside the nose, away from the light: the shadow side of the nose', at(M.alar * 0.62, M.tip + 0.006));
  ok(at(-M.alar * 0.62, M.tip + 0.006) === 'skin', 'beside the nose, towards the light: bare skin', at(-M.alar * 0.62, M.tip + 0.006));
  ok(at(0.004, M.mouth + 0.004) === 'lipUp', 'above the line of the mouth: the upper lip', at(0.004, M.mouth + 0.004));
  ok(at(0.004, M.mouth - 0.004) === 'lipLow', 'below the line of the mouth: the lower lip', at(0.004, M.mouth - 0.004));
  ok(at(0.005, M.mouth) === 'mouthLine' && at(M.mouthW * 0.8, M.mouth + M.seamA * (M.mouthW * 0.8) ** 2) === 'mouthLine', 'where the lips meet: the line of the mouth, out towards its corners', [at(0.005, M.mouth), at(M.mouthW * 0.8, M.mouth)]);
  ok(at(M.mouthW * 1.25, M.mouth + 0.003) === 'skin', 'beyond the corner of the mouth: bare skin', at(M.mouthW * 1.25, M.mouth + 0.003));
  ok(at(0, (M.lipEdge + M.dip) / 2) === 'lipUnder', 'under the lower lip: its shadow', at(0, (M.lipEdge + M.dip) / 2));
  ok(at(0.045, -0.03) === 'skin' && at(0, 0.05) === 'skin' && at(-0.05, -0.08) === 'skin', 'cheeks and brow: bare skin', [at(0.045, -0.03), at(0, 0.05), at(-0.05, -0.08)]);
  ok(at(9, 0) === 'skin', 'the back of the head carries no drawn tone', at(9, 0));
  const lid = at(head.lm.eyeL[0], D.lidY);
  ok(lid === 'lid', 'over each eye: the crease of the lid', lid);
  // the light from the other side: the shadow side of the nose changes over
  const D2 = F3.faceDecals(M, { shadeSide: -1 }), at2 = (x, y) => { const k = D2.map(x, y); return k < 0 ? 'skin' : D2.names[k]; };
  ok(at2(-M.alar * 0.62, M.tip + 0.006) === 'noseSide' && at2(M.alar * 0.62, M.tip + 0.006) === 'skin', 'light from the other side: the nose\'s shadow changes sides', [at2(-M.alar * 0.62, M.tip + 0.006), at2(M.alar * 0.62, M.tip + 0.006)]);
  // a head turned from the lens shows the line of its nose: down the far side of the ridge where the nose turns away
  // from the eye — out on its slope when the head is a little turned, up on the ridge when it is turned far
  const line = (turn, far) => (typeof F3.noseLine === 'function' ? F3.noseLine(M, turn, far) : null);
  ok(Array.isArray(line(0.05, 1)) && line(0.05, 1).length === 0, 'facing the lens: no line down the nose', line(0.05, 1));
  const full = line(0.9, -1) ?? [];
  ok(full.length >= 6 && full.every((q) => q.length === 3 && q[0] <= 1e-9 && q[0] > -M.alar * 1.2), 'turned from the lens: a line of points down the far side of the nose', [full.length, full.slice(0, 3)]);
  if (full.length) {
    const ys = full.map((q) => q[1] - M.eyeY), iTip = ys.findIndex((y) => y <= M.tip);
    ok(near(ys[0], M.nasion, 0.012), 'it starts near the root of the nose (within 12 mm)', ys[0]);
    ok(ys[ys.length - 1] < M.tip - 0.002 && ys[ys.length - 1] > M.sub - 0.002, 'and turns in under the tip, ending above the lip', ys[ys.length - 1]);
    ok(iTip > 2 && full.every((q) => near(q[2], surface(q[0], q[1] - M.eyeY), 0.003)), 'every point lies on the skin (within 3 mm)', full.map((q) => +(q[2] - surface(q[0], q[1] - M.eyeY)).toFixed(4)).slice(0, 6));
    const off = (pts) => pts.filter((q) => q[1] - M.eyeY > M.tip + 0.004).reduce((sum, q, i, a) => sum + Math.abs(q[0]) / a.length, 0);
    const little = line(0.35, -1) ?? [], far = line(1.2, -1) ?? [];
    ok(little.length >= 3 && far.length >= 3 && off(little) > off(far) + 0.0015, 'a little turned the line lies out on the slope of the nose, turned far it lies up on the ridge', [off(little), off(far)]);
    const other = line(0.9, 1) ?? []; ok(other.length === full.length && other.every((q, i) => near(q[0], -full[i][0], 1e-9)), 'turned the other way: the same line on the other side', other.length);
    const wob = Math.max(...full.slice(1).map((q, i) => Math.abs(q[0] - full[i][0]))); ok(wob < 0.0022, 'the line runs smoothly (no step of more than 2.2 mm between neighbours)', wob);
  }
  const P = F3.decalPaints ? F3.decalPaints(D, [232, 184, 150], [196, 140, 118]) : null;
  const lum = (c) => c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11;
  ok(Array.isArray(P) && P.length === D.names.length && P.every((p) => p.fill.length === 3 && p.shade.length === 3 && lum(p.shade) <= lum(p.fill)), 'decalPaints gives a paint (lit tone and shadow tone) for every drawn tone', P && P.length);
  if (P) { const skin = lum([232, 184, 150]); ok(P.every((p) => lum(p.fill) < skin - 4), 'every drawn tone is darker than the lit skin', P.map((p) => Math.round(lum(p.fill)))); }
}

// ---- 3. hair throws its shadow on the face (and only hair does): a strip of hair over a brow, light from above
{
  const quad = (x0, x1, y0, y1, z) => ({ pos: Float32Array.from([x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z]), idx: Uint32Array.from([0, 1, 2, 0, 2, 3]) });
  const paint = (fringeOver) => {
    const cam = makeCam({ pos: [0, 1.6, 1], target: [0, 1.6, 0], fov: 20 }, 200, 200);
    const cp = new CelPainter(cam, { ss: 2, light: [0, 0.8, 0.6], lw: 1, lwIn: 1, lines: false });
    const brow = quad(-0.08, 0.08, 1.52, 1.68, 0);
    cp.add(brow.pos, brow.idx, { fill: [230, 180, 150], shade: [190, 130, 110], thr: -0.3, group: 'skin', recv: false, recvHair: true, two: true });
    if (fringeOver) { const h = quad(-0.08, 0.08, 1.64, 1.70, 0.015); cp.add(h.pos, h.idx, { fill: [20, 20, 30], shade: [10, 10, 20], group: 'hair', two: true, castHair: true, recv: false }); }
    const nose = quad(-0.01, 0.01, 1.55, 1.60, 0.03); cp.add(nose.pos, nose.idx, { fill: [230, 180, 150], shade: [190, 130, 110], thr: -0.3, group: 'skin', recv: false, two: true }); // skin never shades the face this way
    const r = cp.finish(), px = (wx, wy) => { const q = cam.project([wx, wy, 0]), d = r.canvas.getContext('2d').getImageData(Math.round(q[0] - r.x), Math.round(q[1] - r.y), 1, 1).data; return [d[0], d[1], d[2]]; };
    return { justUnder: px(0.05, 1.63), farBelow: px(0.05, 1.56), underNose: px(0.03, 1.54) };
  };
  const dark = (c) => c[0] < 210, withHair = paint(true), bare = paint(false);
  ok(dark(withHair.justUnder), 'just under a fringe the brow is in the hair\'s shadow', withHair.justUnder);
  ok(!dark(withHair.farBelow), 'well below the fringe the face is lit', withHair.farBelow);
  ok(!dark(bare.justUnder), 'without hair the brow is lit', bare.justUnder);
  ok(!dark(withHair.underNose), 'skin in front of the face throws no such shadow on it', withHair.underNose);
}

if (fails.length) { console.error(`face: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`face: ${checks} rules hold`);
