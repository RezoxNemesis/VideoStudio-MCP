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
  // a gentle face (a child, a fringe): design.face.hairShadow: false keeps the hair's cast shadow off the face and its drawn tones
  const Q = F3.decalPaints ? F3.decalPaints(D, [232, 184, 150], [196, 140, 118], { hairShadow: false }) : null;
  ok(Array.isArray(P) && P.every((p) => p.recvHair === true) && Array.isArray(Q) && Q.every((p) => p.recvHair === false), 'hairShadow: false: the drawn tones of the face take no shadow from the hair (they do by default)', Q && Q.map((p) => p.recvHair));
  if (P) { const skin = lum([232, 184, 150]); ok(P.every((p) => lum(p.fill) < skin - 4), 'every drawn tone is darker than the lit skin', P.map((p) => Math.round(lum(p.fill)))); }
}

// ---- 2b. a beard, a scar and the lines of age are painted on the skin too: they turn with the head and move with the jaw
if (M && typeof F3.faceDecals === 'function') {
  const named = (o) => { const d = F3.faceDecals(M, { shadeSide: 1, ...o }); return { d, at: (x, y) => { const k = d.map(x, y); return k < 0 ? 'skin' : d.names[k]; } }; };
  const plain = named({});
  ok(!plain.d.names.includes('beard') && plain.at(0, M.chin + 0.002) === 'skin', 'a face that asks for no beard has none', plain.at(0, M.chin + 0.002));
  const full = named({ beard: { style: 'full', strands: 0 } }), mid = (M.sub + M.lipTopEdge) / 2; // (strands are looked at on their own below)
  ok(full.d.names.includes('beard') && full.d.names.includes('mustache'), 'beard: { style: "full" } adds the tones beard and mustache', full.d.names);
  ok(full.at(0, M.chin + 0.002) === 'beard' && full.at(0.012, M.chin - 0.01) === 'beard', 'a full beard covers the chin and the skin under it', [full.at(0, M.chin + 0.002), full.at(0.012, M.chin - 0.01)]);
  ok(full.at(0.055, M.mouth - 0.008) === 'beard' && full.at(-0.055, M.mouth - 0.008) === 'beard', 'and the jaw on both sides', [full.at(0.055, M.mouth - 0.008), full.at(-0.055, M.mouth - 0.008)]);
  ok(full.at(0.008, mid) === 'mustache' && full.at(-0.008, mid) === 'mustache', 'between the nose and the upper lip: the moustache', [full.at(0.008, mid), full.at(-0.008, mid)]);
  ok(full.at(M.mouthW + 0.006, M.mouth) === 'beard', 'beside the corner of the mouth the moustache runs down into the beard', full.at(M.mouthW + 0.006, M.mouth));
  ok(full.at(0.004, M.mouth - 0.004) === 'lipLow' && full.at(0.004, M.mouth + 0.004) === 'lipUp' && full.at(0.005, M.mouth) === 'mouthLine', 'the lips stay lips', [full.at(0.004, M.mouth - 0.004), full.at(0.004, M.mouth + 0.004)]);
  ok(full.at(0, M.tip - 0.009) === 'noseUnder' && full.at(M.alar * 0.62, M.tip + 0.006) === 'noseSide', 'the nose keeps its shadows', [full.at(0, M.tip - 0.009), full.at(M.alar * 0.62, M.tip + 0.006)]);
  ok(full.at(0.03, M.tip) === 'skin' && full.at(0.045, -0.03) === 'skin' && full.at(0, 0.05) === 'skin', 'the cheek under the eye and the brow stay bare', [full.at(0.03, M.tip), full.at(0.045, -0.03), full.at(0, 0.05)]);
  ok(full.at(M.mouthW * 0.6, (M.lipEdge + M.dip) / 2 + 0.001) !== 'beard', 'under the lower lip, beside its middle, the skin is bare (a beard grows from the chin)', full.at(M.mouthW * 0.6, (M.lipEdge + M.dip) / 2 + 0.001));
  // the ear: found on a real head where its fine folds begin; a head without one is given a place for it by its size
  ok(near(M.ear, 0.128 * (M.eyeX / 0.0302), 0.002), 'a head with no ear to find: the ear is placed by the head\'s size', M.ear);
  { const fsm = await import('node:fs'), FX = JSON.parse(fsm.readFileSync(new URL('./fixtures/head_mesh.json', import.meta.url), 'utf8'));
    const MR = F3.faceMarksOf(Float32Array.from(FX.skin.pos), FX.skin.idx, FX.landmarks);
    ok(MR.ear > 0.12 && MR.ear < 0.138, 'on a real head the ear is found: it begins about 13 cm round from the middle of the face', MR.ear);
    // the measure round the side of the head runs evenly with the skin: along the cheek's side, a centimetre of skin is about a centimetre of it
    const P = FX.skin.pos, eY = FX.landmarks.eyeL[1], eZ = FX.landmarks.eyeL[2], zF = eZ - (F3.FACE_FRONT ?? 0.045), side = [];
    // (the cheek's side below the cheekbone, as its outline seen from above: the outermost skin at every half centimetre of depth)
    const env = new Map(); for (let v = 0; v < P.length; v += 3) { const y = P[v + 1] - eY, dp = eZ - P[v + 2]; if (y > -0.025 || y < -0.04 || P[v] < 0.03 || dp < 0 || dp > 0.07) continue; const b = Math.floor(dp / 0.005), q = env.get(b); if (!q || P[v] > q[1]) env.set(b, [dp, P[v], Math.abs(F3.unfoldX?.(P[v], P[v + 2], zF) ?? 0)]); }
    for (const b of [...env.keys()].sort((a, b) => a - b)) side.push(env.get(b));
    let skin = 0; const from = side.length > 4 ? 0 : -1, run = from < 0 ? 0 : side[side.length - 1][2] - side[0][2];
    for (let i = 1; i < side.length; i++) skin += Math.hypot(side[i][0] - side[i - 1][0], side[i][1] - side[i - 1][1]);
    ok(from >= 0 && skin > 0.05 && run > skin * 0.8 && run < skin * 1.25, 'round the side of a real head the measure keeps pace with the skin (it does not stand still along the cheek and then leap)', [skin, run]); }
  const sb = M.ear - 0.004; // (just in front of the ear)
  ok(full.at(sb - 0.006, -0.02) === 'beard' && full.at(sb - 0.006, -0.06) === 'beard' && full.at(sb - 0.004, 0.0) === 'beard', 'in front of the ear the beard runs up as a sideburn to meet the hair', [full.at(sb - 0.006, -0.02), full.at(sb - 0.006, -0.06), full.at(sb - 0.004, 0.0)]);
  ok(full.at(M.ear + 0.006, -0.03) === 'skin' && full.at(M.ear + 0.03, -0.03) === 'skin' && full.at(9, -0.08) === 'skin', 'it stops at the ear; the back of the head carries none', [full.at(M.ear + 0.006, -0.03), full.at(9, -0.08)]);
  { const width = (y) => { let n = 0; for (let x = 0.06; x < 0.14; x += 0.0005) if (full.at(x, y) === 'beard') n++; return n * 0.0005; };
    ok(width(0.0) > 0.006 && width(0.0) < 0.014 && width(-0.03) > width(0.0) + 0.002, 'the sideburn is a narrow strip (about a centimetre) that widens downwards into the beard', [width(0.0), width(-0.03)]); }
  ok(full.at(sb - 0.024, -0.012) === 'skin', 'the cheek before the sideburn is bare', full.at(sb - 0.024, -0.012));
  // how high it grows on the cheek
  const low = named({ beard: { style: 'full', cheek: 0, strands: 0 } }), high = named({ beard: { style: 'full', cheek: 1, strands: 0 } });
  ok(low.at(0.06, M.mouth + 0.012) === 'skin' && high.at(0.06, M.mouth + 0.012) === 'beard', 'cheek: 0 keeps it along the jaw, cheek: 1 lets it grow high on the cheek', [low.at(0.06, M.mouth + 0.012), high.at(0.06, M.mouth + 0.012)]);
  // other cuts
  const goat = named({ beard: { style: 'goatee', strands: 0 } });
  ok(goat.at(0, M.chin + 0.002) === 'beard' && goat.at(0.008, mid) === 'mustache' && goat.at(0.06, M.mouth - 0.008) === 'skin' && goat.at(M.ear - 0.008, -0.03) === 'skin', 'a goatee: chin and moustache, bare jaw and cheeks', [goat.at(0, M.chin + 0.002), goat.at(0.008, mid), goat.at(0.06, M.mouth - 0.008)]);
  const must = named({ beard: { style: 'mustache' } });
  ok(must.at(0.008, mid) === 'mustache' && must.at(0, M.chin + 0.002) === 'skin' && must.at(0.055, M.mouth - 0.008) === 'skin', 'a moustache alone', [must.at(0.008, mid), must.at(0, M.chin + 0.002)]);
  const noMust = named({ beard: { style: 'full', mustache: false, strands: 0 } });
  ok(noMust.at(0.008, mid) === 'skin' && noMust.at(0, M.chin + 0.002) === 'beard', 'mustache: false leaves the upper lip bare', noMust.at(0.008, mid));
  // a heavy moustache hangs over the upper lip (the lip is not drawn through it) and is thick from the nose down
  { const hv = named({ beard: { style: 'mustache', mustache: { heavy: true } } }), lt = named({ beard: { style: 'mustache' } });
    ok(hv.at(0.006, M.mouth + 0.004) === 'mustache' && lt.at(0.006, M.mouth + 0.004) === 'lipUp', 'mustache: { heavy: true } covers the upper lip; a light one leaves it', [hv.at(0.006, M.mouth + 0.004), lt.at(0.006, M.mouth + 0.004)]);
    ok(hv.at(0.006, M.mouth - 0.004) === 'lipLow' && hv.at(0.005, M.mouth) === 'mouthLine', 'the lower lip and the line of the mouth stay', [hv.at(0.006, M.mouth - 0.004), hv.at(0.005, M.mouth)]);
    ok(hv.at(M.mouthW + 0.004, M.mouth - 0.008) === 'mustache' && lt.at(M.mouthW + 0.004, M.mouth - 0.008) === 'skin', 'its ends hang down past the corners of the mouth', [hv.at(M.mouthW + 0.004, M.mouth - 0.008), lt.at(M.mouthW + 0.004, M.mouth - 0.008)]); }
  // the edge on the cheek is drawn like hair: it is not one straight cut
  { const edge = (x) => { let y = M.mouth - 0.02; while (y < 0 && full.at(x, y) === 'beard') y += 0.0002; return y; };
    const xs = []; for (let x = 0.05; x < 0.09; x += 0.0005) xs.push(edge(x)); let turns = 0; for (let i = 2; i < xs.length; i++) if ((xs[i] - xs[i - 1]) * (xs[i - 1] - xs[i - 2]) < 0) turns++;
    ok(turns >= 6, 'the beard\'s edge on the cheek is ragged like drawn hair (it rises and falls)', turns); }
  // strands: a beard is hair — darker strokes run through it the way it grows, most of it stays its own tone
  { const share = (o) => { const q = named({ beard: { style: 'full', ...o } }); let all = 0, st = 0; for (let x = 0.002; x < 0.08; x += 0.0007) for (let y = M.chin - 0.012; y < M.mouth + 0.004; y += 0.0007) { const k = q.at(x, y); if (k === 'beard' || k === 'beardStrand') { all++; if (k === 'beardStrand') st++; } } return [st / (all || 1), q.d.names.includes('beardStrand')]; };
    const def = share({}), none = share({ strands: 0 }), many = share({ strands: 1 });
    ok(def[1] && def[0] > 0.04 && def[0] < 0.3, 'a beard is drawn with strands: darker strokes through it, most of it its own tone', def);
    ok(!none[1] && none[0] === 0, 'strands: 0 leaves it one flat tone', none);
    ok(many[0] > def[0] * 1.3, 'strands: 1 draws more of them', [def[0], many[0]]);
    const q = named({ beard: { style: 'full' } }); let runs = 0, inRun = false; for (let y = M.chin - 0.01; y < M.dip; y += 0.0002) { const k = q.at(0.0125, y) === 'beardStrand'; if (k && !inRun) runs++; inRun = k; }
    let across = 0; inRun = false; for (let x = 0.002; x < 0.03; x += 0.0002) { const k = q.at(x, M.chin - 0.002) === 'beardStrand'; if (k && !inRun) across++; inRun = k; }
    ok(across >= 3 && across > runs, 'the strokes run down the chin, the way a beard grows (crossing it finds more of them than going down it)', [across, runs]); }
  // paints: the beard in the hair's colour (or its own), thin for stubble
  const skinC = [232, 184, 150], shadeC = [196, 140, 118], lumOf = (c) => c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11;
  const pOf = (o, name) => { const d = F3.faceDecals(M, { shadeSide: 1, ...o }), P2 = F3.decalPaints(d, skinC, shadeC, o); return P2[d.names.indexOf(name)]; };
  const pFull = pOf({ beard: { style: 'full', color: [58, 56, 62] } }, 'beard'), pStub = pOf({ beard: { style: 'full', color: [58, 56, 62], density: 0.35 } }, 'beard');
  ok(!!pFull && lumOf(pFull.fill) < 70 && lumOf(pFull.shade) < lumOf(pFull.fill), 'a beard is painted in its colour, darker in shadow', pFull && pFull.fill);
  ok(!!pStub && lumOf(pStub.fill) > lumOf(pFull.fill) + 40 && lumOf(pStub.fill) < lumOf(skinC) - 15, 'density below 1 thins it to stubble: between the skin and the beard', pStub && pStub.fill);
  // a scar: a fine line from one place on the face to another
  const sc = named({ scars: [{ from: [0.022, 0.03], to: [0.034, -0.05] }] });
  ok(sc.d.names.includes('scar') && sc.at(0.028, -0.01) === 'scar', 'scars: [{ from, to }] draws a scar along its line', sc.at(0.028, -0.01));
  ok(sc.at(0.031, -0.01) === 'skin' && sc.at(0.028, -0.07) === 'skin' && sc.at(-0.028, -0.01) === 'skin', 'a scar is fine (a millimetre or so) and ends where it ends', [sc.at(0.031, -0.01), sc.at(0.028, -0.07), sc.at(-0.028, -0.01)]);
  // the lines of age
  const old = named({ lines: 1 }), young = named({});
  const foldX = (M.alar + 0.004 + M.mouthW + 0.009) / 2, foldY = (M.sub + 0.004 + M.mouth - 0.004) / 2;
  let hit = 0; for (let dx = -0.004; dx <= 0.004; dx += 0.0002) if (old.at(foldX + dx, foldY) === 'ageLine') hit++;
  let hitL = 0; for (let dx = -0.004; dx <= 0.004; dx += 0.0002) if (old.at(-foldX + dx, foldY) === 'ageLine') hitL++;
  ok(old.d.names.includes('ageLine') && hit >= 2 && hit <= 12 && hitL >= 2, 'lines: 1 draws the fold from each wing of the nose down past the corner of the mouth, as a fine line', [hit, hitL]);
  ok(!young.d.names.includes('ageLine'), 'a face without lines has none', young.d.names);
  let frown = 0; const fr = named({ lines: 0.5, frown: true }); for (let y = 0.008; y < 0.03; y += 0.0005) for (let x = 0.002; x < 0.012; x += 0.0004) if (fr.at(x, y) === 'ageLine') frown++;
  ok(frown >= 4, 'frown: true draws the furrow between the brows', frown);
  // the side of the head unfolds into the face's measure, so a beard can run back along the jaw to the ear
  if (typeof F3.unfoldX === 'function') {
    ok(F3.unfoldX(0.03, 0.14, 0.09) === 0.03 && F3.unfoldX(-0.03, 0.14, 0.09) === -0.03, 'unfoldX: skin in front keeps its place', F3.unfoldX(0.03, 0.14, 0.09));
    ok(near(F3.unfoldX(0.06, 0.06, 0.09), 0.09, 1e-9) && near(F3.unfoldX(-0.06, 0.06, 0.09), -0.09, 1e-9), 'unfoldX: skin behind the face lies further out by as much as it lies behind', F3.unfoldX(0.06, 0.06, 0.09));
  } else ok(false, 'unfoldX(x, z, zFront) unfolds the side of the head', null);
}

// ---- 2c. a design asks for them on its sheet: face: { beard, scars, lines, frown }
{
  const BD = await import('../engine/bodydraw.js').catch((e) => ({ err: e }));
  const fo = typeof BD.faceMarkOptions === 'function' ? BD.faceMarkOptions : null;
  ok(!!fo, 'faceMarkOptions(design) reads what a design asks to have painted on its face', BD.err ? String(BD.err) : Object.keys(BD).slice(0, 4));
  if (fo) {
    const a = fo({ hair: { color: '#3a383e', shade: '#242328' }, face: { beard: { style: 'full', cheek: 0.4 }, scars: [{ from: [0.02, 0.03], to: [0.03, -0.05] }], lines: true, frown: true } });
    ok(a.beard && a.beard.style === 'full' && a.beard.cheek === 0.4 && a.beard.color.join() === '58,56,62' && a.beard.shade.join() === '36,35,40', 'a beard takes the hair\'s colour and shade', a.beard);
    ok(a.scars.length === 1 && a.lines === true && a.frown === true, 'scars, lines and frown are handed on', [a.scars, a.lines, a.frown]);
    const b = fo({ hair: { color: '#3a383e' }, face: { beard: { style: 'goatee', color: '#e8e6e2' } } });
    ok(b.beard.color.join() === '232,230,226' && b.beard.shade.every((v, i) => v < b.beard.color[i]), 'or its own colour (a white beard under dark hair), with a shade made from it', b.beard);
    const c = fo({ hair: { color: '#3a383e' }, face: {} });
    ok(!c.beard && !c.scars && !c.lines, 'a design that asks for nothing gets nothing', c);
    ok(BD.paintedBeard?.({ face: { beard: { style: 'full' } } }) === true && BD.paintedBeard?.({ face: {}, beard: { length: 0.2 } }) === false, 'paintedBeard(design): a painted beard replaces the body model\'s own', [BD.paintedBeard?.({ face: { beard: { style: 'full' } } }), BD.paintedBeard?.({ face: {} })]);
    ok(BD.bodyBeard?.({ face: { beard: false } }) === false && BD.bodyBeard?.({ face: {} }) === true && BD.bodyBeard?.({ face: { beard: { style: 'full' } } }) === false, 'bodyBeard(design): face.beard: false leaves the body model\'s own beard out altogether (a woman, a child: a stray piece of it shows as a dark mark on the chin); a painted beard replaces it; otherwise it is drawn', [BD.bodyBeard?.({ face: { beard: false } }), BD.bodyBeard?.({ face: {} })]);
  }
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
