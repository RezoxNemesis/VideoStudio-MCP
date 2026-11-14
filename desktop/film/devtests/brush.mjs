// The brush of a painted figure: node devtests/brush.mjs        (exit 1 when a rule is broken)
// The painted look can turn from light to shadow softly, the way a figure painted with a brush does (skin and cloth
// as rounded forms), while hair, metal and anything a paint marks stays crisp; and the drawn tones of a face can be
// laid in lightly.
import { CelPainter } from '../engine/body.js';
import { makeCam } from '../engine/camera.js';
import { setLook, resolveLook } from '../engine/look.js';
import { faceMarksOf, faceDecals, decalPaints } from '../engine/face3.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
setLook('anime');

/** a ball, a metre in front of the lens */
function ball(r = 0.1, c = [0, 1.6, 0]) {
  const N = 40, M = 64, pos = [], idx = [];
  for (let i = 0; i <= N; i++) for (let k = 0; k < M; k++) { const el = (i / N) * Math.PI, az = (k / M) * Math.PI * 2; pos.push(c[0] + Math.sin(el) * Math.sin(az) * r, c[1] + Math.cos(el) * r, c[2] + Math.sin(el) * Math.cos(az) * r); }
  for (let i = 0; i < N; i++) for (let k = 0; k < M; k++) { const a = i * M + k, b = i * M + (k + 1) % M; idx.push(a, a + M, b, b, a + M, b + M); }
  return { pos: Float32Array.from(pos), idx: Uint32Array.from(idx) };
}
const cam = makeCam({ pos: [0, 1.6, 1.2], target: [0, 1.6, 0], fov: 14 }, 320, 320);
/** the tones along the ball's waist, from its lit side to its dark side: [[r, g, b] per pixel] */
function waist(paint, o = {}) {
  const b = ball(), cp = new CelPainter(cam, { ss: 2, light: [-0.94, 0.1, 0.33], lines: false, shadows: false, ...o }); cp.add(b.pos, b.idx, paint);
  const r = cp.finish(), g = r.canvas.getContext('2d'), y = Math.round(cam.project([0, 1.6, 0])[1] - r.y), row = g.getImageData(0, y, r.w, 1).data, out = [];
  for (let x = 0; x < r.w; x++) if (row[x * 4 + 3] > 250) out.push([row[x * 4], row[x * 4 + 1], row[x * 4 + 2]]);
  return out;
}
const lum = (c) => c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11;
/** how much of the waist lies between light and shadow (the turn), as a part of its whole width */
const turn = (row) => { const L = row.map(lum), hi = Math.max(...L.slice(4, 30)), lo = Math.min(...L.slice(-30, -4)), a = hi - (hi - lo) * 0.15, b = lo + (hi - lo) * 0.15; return L.filter((v) => v < a && v > b).length / L.length; };

// ---- 1. a soft turn of the light
{
  const skin = { fill: [228, 176, 138], shade: [150, 100, 82], thr: 0.02, skin: true }, cloth = { fill: [236, 230, 218], shade: [150, 146, 150], thr: 0.05 };
  const crisp = turn(waist(skin)), soft = turn(waist(skin, { soft: 1 })), half = turn(waist(skin, { soft: 0.5 }));
  ok(crisp < 0.07, 'the painted look turns from light to shadow in a crisp edge', crisp);
  ok(soft > 0.22, 'with soft: 1 the light turns to shadow gradually, over a good part of a rounded form', soft);
  ok(half > crisp + 0.05 && half < soft - 0.03, 'soft: 0.5 lies between', [crisp, half, soft]);
  ok(turn(waist(cloth, { soft: 1 })) > 0.2, 'cloth turns softly too', turn(waist(cloth, { soft: 1 })));
  const hair = { fill: [60, 44, 36], shade: [24, 16, 14], thr: 0.1, hairy: true, soft: 0 };
  ok(turn(waist(hair, { soft: 1 })) < 0.07, 'a paint that says soft: 0 (hair: drawn in strands) keeps its crisp edge', turn(waist(hair, { soft: 1 })));
  // the two ends stay what they were: full light is the lit tone, full shadow the shadow tone
  const a = waist(skin), b = waist(skin, { soft: 1 });
  ok(Math.abs(lum(a[a.length - 6]) - lum(b[b.length - 6])) < 4, 'the shadow side is as dark as before', [lum(a[a.length - 6]), lum(b[b.length - 6])]);
  // no banding: a soft turn has many steps, none of them a jump
  const L = b.map(lum); let jump = 0; for (let i = 5; i < L.length - 5; i++) jump = Math.max(jump, Math.abs(L[i] - L[i - 1]));
  ok(jump < 6, 'and it has no steps in it', jump);
  setLook('classic');
  ok(turn(waist(skin, { soft: 1 })) < 0.07, 'the classic cel look stays two flat tones whatever is asked', turn(waist(skin, { soft: 1 })));
  setLook(resolveLook({ style: 'anime', paint: { soft: 0.8 } }));
  ok(turn(waist(skin)) > 0.18, 'a film sets it for every figure in its look: { style: \'anime\', paint: { soft: 0.8 } }', turn(waist(skin)));
  setLook('anime');
}

// ---- 2. the drawn tones of a face, laid in lightly
{
  const skin = [228, 176, 138], shade = [150, 100, 82], M = { nasion: -0.004, tip: -0.042, sub: -0.058, lipTop: -0.07, lipTopEdge: -0.066, mouth: -0.078, lipLow: -0.09, lipEdge: -0.093, dip: -0.099, chin: -0.109, alar: 0.016, bridge: 0.008, mouthW: 0.027, seamA: 0, eyeX: 0.032, eyeY: 1.6, eyeZ: 0.137, m: 0.08 };
  const D = faceDecals(M, { shadeSide: 1 }), full = decalPaints(D, skin, shade, {}), light = decalPaints(D, skin, shade, { nose: 0.3 });
  const far = (P, n) => { const p = P[D.names.indexOf(n)]; return Math.abs(lum(p.fill) - lum(skin)); };
  ok(far(light, 'noseSide') < 0.45 * far(full, 'noseSide') && far(light, 'noseUnder') < 0.45 * far(full, 'noseUnder'), 'face.nose: 0.3 lays the nose\'s shadows in lightly (less than half as far from the skin\'s own tone)', [far(light, 'noseSide') / far(full, 'noseSide'), far(light, 'noseUnder') / far(full, 'noseUnder')]);
  ok(far(light, 'lipUp') === far(full, 'lipUp') && far(light, 'mouthLine') === far(full, 'mouthLine'), 'the lips and the line of the mouth stay as they were', [far(light, 'lipUp'), far(full, 'lipUp')]);
  void faceMarksOf;
}

// ---- 3. on a softly painted face, hair's shadow is soft too — and lighter than a form's own shadow
{
  const quad = (x0, x1, y0, y1, z) => ({ pos: Float32Array.from([x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z]), idx: Uint32Array.from([0, 1, 2, 0, 2, 3]) });
  const down = (soft) => {
    const cam2 = makeCam({ pos: [0, 1.6, 1], target: [0, 1.6, 0], fov: 20 }, 400, 400), cp = new CelPainter(cam2, { ss: 2, light: [0, 0.8, 0.6], lines: false, soft });
    const brow = quad(-0.08, 0.08, 1.52, 1.68, 0), h = quad(-0.08, 0.08, 1.64, 1.70, 0.015);
    cp.add(brow.pos, brow.idx, { fill: [230, 180, 150], shade: [190, 130, 110], thr: -0.3, group: 'skin', skin: true, recv: false, recvHair: true, two: true });
    cp.add(h.pos, h.idx, { fill: [20, 20, 30], shade: [10, 10, 20], group: 'hair', two: true, castHair: true, recv: false, soft: 0 });
    const r = cp.finish(), g = r.canvas.getContext('2d'), col = [];
    for (let y = 1.638; y > 1.56; y -= 0.0005) { const q = cam2.project([0.05, y, 0]); col.push(g.getImageData(Math.round(q[0] - r.x), Math.round(q[1] - r.y), 1, 1).data[0]); }
    return col;
  };
  const crisp = down(0), soft = down(1), lit = soft[soft.length - 1], under = soft[4];
  ok(crisp[4] <= 196, 'crisp: under a fringe the brow takes the skin\'s full shadow tone', crisp[4]);
  ok(under < lit - 12 && under > 190 + 8, 'soft: the hair\'s shadow is there, but lighter than a form\'s own shadow', [under, lit]);
  const between = (col) => new Set(col.filter((v) => v > col[4] + 3 && v < lit - 3)).size;
  ok(between(soft) >= 3 && between(soft) > between(crisp), 'and its edge is soft: the tone steps down through several values, not one', [between(crisp), between(soft)]);
}

// ---- 3b. so is the shadow one form throws on another (an arm on the chest, a head on a shoulder)
{
  const quad = (x0, x1, y0, y1, z) => ({ pos: Float32Array.from([x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z]), idx: Uint32Array.from([0, 1, 2, 0, 2, 3]) });
  const down = (soft) => {
    const cam2 = makeCam({ pos: [0, 1.3, 2.4], target: [0, 1.3, 0], fov: 20 }, 400, 400), cp = new CelPainter(cam2, { ss: 2, light: [0, 0.8, 0.6], lines: false, soft });
    const chest = quad(-0.3, 0.3, 1.0, 1.6, 0), arm = quad(-0.3, 0.3, 1.4, 1.5, 0.12);
    cp.add(chest.pos, chest.idx, { fill: [236, 230, 218], shade: [150, 146, 150], thr: 0.05, group: 'a', two: true });
    cp.add(arm.pos, arm.idx, { fill: [40, 40, 50], shade: [20, 20, 30], group: 'b', two: true });
    const r = cp.finish(), g = r.canvas.getContext('2d'), col = [];
    for (let y = 1.39; y > 1.05; y -= 0.002) { const q = cam2.project([0.1, y, 0]); col.push(g.getImageData(Math.round(q[0] - r.x), Math.round(q[1] - r.y), 1, 1).data[1]); }
    return col;
  };
  const crisp = down(0), soft = down(1), lit = soft[soft.length - 1], dark = Math.min(...soft);
  const between = (col) => new Set(col.filter((v) => v > Math.min(...col) + 4 && v < col[col.length - 1] - 4)).size;
  ok(Math.min(...crisp) < 160 && lit > 215, 'an arm held before the chest throws its shadow on it', [Math.min(...crisp), lit]);
  ok(dark < 165, 'softly painted, the shadow is as deep', dark);
  ok(between(soft) >= 4 && between(soft) > between(crisp) + 1, 'but its edge is soft', [between(crisp), between(soft)]);
}

// ---- 4. softly painted, a tuck in the form is not a blot
{
  const wall = { pos: Float32Array.from([-0.2, 1.4, 0, 0.2, 1.4, 0, 0.2, 1.8, 0, -0.2, 1.8, 0]), idx: Uint32Array.from([0, 1, 2, 0, 2, 3]) }, b = ball(0.05, [0, 1.6, 0.045]);
  const darkest = (soft) => {
    const cam2 = makeCam({ pos: [0.5, 1.6, 1.1], target: [0, 1.6, 0], fov: 16 }, 320, 320), cp = new CelPainter(cam2, { ss: 2, light: [0.9, 0.3, -0.31], lines: false, shadows: false, soft }); // (the wall is turned from the light: all of it in shadow)
    const skin = { fill: [228, 176, 138], shade: [150, 100, 82], thr: 0.02, skin: true, group: 'skin' };
    cp.add(wall.pos, wall.idx, { ...skin, two: true }); cp.add(b.pos, b.idx, skin);
    const r = cp.finish(), d = r.canvas.getContext('2d').getImageData(0, 0, r.w, r.h).data; let m = 255;
    for (let i = 0; i < d.length; i += 4) if (d[i + 3] > 250 && d[i] < m) m = d[i];
    return m;
  };
  ok(darkest(0) < 150 * 0.85, 'crisp: where a form tucks in behind another the shadow goes deeper than the shadow tone', darkest(0));
  ok(darkest(1) > 150 * 0.9, 'soft: it stays close to the shadow tone (no dark blots where the jaw meets the neck)', darkest(1));
}

if (fails.length) { console.error(`brush: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`brush: ${checks} rules hold`);
