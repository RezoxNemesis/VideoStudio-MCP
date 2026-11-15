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

// ---- 3. armour of small plates
{
  const { platePaint } = await import('../engine/bodydraw.js');
  const rgbOf = (c) => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)], base = { fill: [60, 62, 70], shade: [30, 31, 36], metal: true, bias: 0.002 };
  const P = platePaint(base, { h: 0.05, w: 0.04, color: '#3c3e46', lace: '#8f1d23' }, rgbOf);
  ok(!!P && typeof P.map === 'function' && Array.isArray(P.maps), 'platePaint(base, plates): armour is painted by a map of its plates', !!P);
  if (P?.map) {
    const tone = (x, y) => { const k = P.map(x, y); return k < 0 ? 'plate' : P.maps[k].name; };
    const y0 = 1.2; // the lower edge of a row
    ok(tone(0.02, y0 + 0.025) === 'plate', 'the middle of a plate is the plate itself', tone(0.02, y0 + 0.025));
    ok(tone(0.02, y0 + 0.004) === 'lip', 'its lower edge catches the light', tone(0.02, y0 + 0.004));
    ok(tone(0.02, y0 + 0.046) === 'under', 'its top lies in the shadow of the row above (the rows lap over one another)', tone(0.02, y0 + 0.046));
    const gaps = (y) => { const out = []; let was = false; for (let x = -0.1; x < 0.1; x += 0.0005) { const g = tone(x, y) === 'gap'; if (g && !was) out.push(x); was = g; } return out; };
    const g0 = gaps(y0 + 0.025), g1 = gaps(y0 + 0.075);
    ok(g0.length >= 4 && g0.length <= 6 && Math.abs((g0[1] - g0[0]) - 0.04) < 0.002, 'the plates of a row are as wide as asked, with a line between them', g0.map((v) => +v.toFixed(3)));
    ok(g1.length >= 4 && Math.abs(Math.abs(((g1[0] - g0[0]) % 0.04 + 0.04) % 0.04) - 0.02) < 0.003, 'the next row is set half a plate over, like bricks', [g0[0], g1[0]]);
    let plate = 0, lace = 0, n = 0; for (let y = 1.0; y < 1.4; y += 0.0017) for (let x = -0.15; x < 0.15; x += 0.0013) { const t = tone(x, y); n++; if (t === 'plate') plate++; if (t === 'lace') lace++; }
    ok(plate / n > 0.5, 'most of it is plate', plate / n);
    ok(lace / n > 0.01 && lace / n < 0.12, 'every plate is laced with a little of the cord\'s colour', lace / n);
    ok(P.maps.find((m) => m.name === 'lace').fill[0] > 120 && P.maps.find((m) => m.name === 'lace').fill[2] < 60, 'in the colour asked for', P.maps.find((m) => m.name === 'lace').fill);
    const lum = (c) => c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11, M = (nm) => P.maps.find((m) => m.name === nm);
    ok(lum(M('lip').fill) > lum(base.fill) + 15 && lum(M('under').fill) < lum(base.fill) - 12 && lum(M('gap').fill) < lum(base.fill) - 12, 'the lip is lighter than the plate, the lap and the lines between plates darker', [lum(M('lip').fill), lum(base.fill), lum(M('under').fill)]);
    ok(tone(0.02, y0 + 0.025 + 10) === 'plate' && tone(0.02, y0 + 0.004 + 10) === 'lip', 'the plates go round the back', [tone(0.02, y0 + 0.025 + 10)]);
    const part = platePaint(base, { h: 0.05, w: 0.04, from: 1.1, to: 1.3 }, rgbOf);
    const pt = (x, y) => { const k = part.map(x, y); return k < 0 ? 'plate' : part.maps[k].name; };
    let outside = 0; for (let y = 0.8; y < 1.09; y += 0.003) for (let x = -0.1; x < 0.1; x += 0.007) if (pt(x, y) !== 'plate') outside++;
    for (let y = 1.31; y < 1.6; y += 0.003) for (let x = -0.1; x < 0.1; x += 0.007) if (pt(x, y) !== 'plate') outside++;
    ok(outside === 0 && pt(0.02, 1.2 + 0.004) === 'lip', 'plates can be kept to a part of a garment\'s height (a skirt of plates under plain cloth)', outside);
    ok(!part.maps.some((m) => m.name === 'lace') || (() => { let l = 0; for (let y = 1.1; y < 1.3; y += 0.002) for (let x = -0.1; x < 0.1; x += 0.002) if (pt(x, y) === 'lace') l++; return l === 0; })(), 'no cord shows where none is asked for', part.maps.map((m) => m.name));
  }
}

// ---- 4. shoulder plates on a real body: lames that lie over the shoulder and lap down the upper arm
{
  const AR = await import('../engine/armour.js').catch(() => ({}));
  const k = POSE.height / 1.8, len = (a) => Math.hypot(a[0], a[1], a[2]), nrm = (a) => { const l = len(a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; }, add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], mul = (a, q) => [a[0] * q, a[1] * q, a[2] * q];
  const make = (j) => (typeof AR.pauldronMeshes === 'function' ? AR.pauldronMeshes(j, k, {}) : null);
  const M = make(POSE.j);
  ok(Array.isArray(M) && M.filter((m) => m.side === 'L').length >= 3 && M.filter((m) => m.side === 'R').length >= 3, 'pauldronMeshes(joints, k) gives each shoulder its lames (three or more)', M && M.map((m) => m.name));
  if (Array.isArray(M) && M.length) {
    const pts = (m) => { const out = []; for (let v = 0; v < m.pos.length; v += 3) out.push([m.pos[v], m.pos[v + 1], m.pos[v + 2]]); return out; };
    for (const sd of ['L', 'R']) {
      const S = POSE.j[`upperarm01.${sd}`], E = POSE.j[`lowerarm01.${sd}`], a = nrm(sub(E, S)), armLen = len(sub(E, S)), mid = [(POSE.j['upperarm01.L'][0] + POSE.j['upperarm01.R'][0]) / 2, S[1], (POSE.j['upperarm01.L'][2] + POSE.j['upperarm01.R'][2]) / 2], out = nrm(sub(S, mid));
      const L = M.filter((m) => m.side === sd), all = L.flatMap(pts), radial = (p) => { const d = sub(p, S), s = dot(d, a); return [s, len(sub(d, mul(a, s)))]; };
      const top = Math.max(...all.map((p) => p[1])), low = Math.max(...all.map((p) => radial(p)[0]));
      ok(top > S[1] + 0.02 * k && top < S[1] + 0.1 * k, `${sd}: the plates rise over the top of the shoulder (2–10 cm above the joint)`, top - S[1]);
      ok(low > 0.1 * k && low < armLen * 0.8, `${sd}: and lap down the upper arm (10 cm or more, well short of the elbow)`, low);
      const inner = Math.min(...all.filter((p) => radial(p)[0] > 0.03 * k).map((p) => radial(p)[1]));
      ok(inner > 0.05 * k, `${sd}: below the shoulder every plate stands clear of the arm (5 cm or more from its middle)`, inner);
      ok(Math.min(...all.map((p) => dot(sub(p, S), out))) > -0.085 * k, `${sd}: they stay on the shoulder (nothing reaches in to the neck)`, Math.min(...all.map((p) => dot(sub(p, S), out))));
      // each lame laps over the next: no gap between them down the arm
      const span = L.map((m) => { const ss = pts(m).map((p) => radial(p)[0]); return [Math.min(...ss), Math.max(...ss)]; }).sort((p, q) => p[1] - q[1]);
      ok(span.every((sp, i) => i === 0 || sp[0] < span[i - 1][1] - 0.008 * k), `${sd}: each plate begins under the one above (they overlap)`, span.map((sp) => sp.map((v) => +v.toFixed(3))));
      // the plates face outwards: a painter lights them from outside
      let outw = 0, tot = 0; for (const m of L) for (let t = 0; t < m.idx.length; t += 3) { const A = [m.pos[m.idx[t] * 3], m.pos[m.idx[t] * 3 + 1], m.pos[m.idx[t] * 3 + 2]], B2 = [m.pos[m.idx[t + 1] * 3], m.pos[m.idx[t + 1] * 3 + 1], m.pos[m.idx[t + 1] * 3 + 2]], C = [m.pos[m.idx[t + 2] * 3], m.pos[m.idx[t + 2] * 3 + 1], m.pos[m.idx[t + 2] * 3 + 2]], u = sub(B2, A), v = sub(C, A), n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]], c = [(A[0] + B2[0] + C[0]) / 3, (A[1] + B2[1] + C[1]) / 3, (A[2] + B2[2] + C[2]) / 3], d = sub(c, add(S, mul(a, radial(c)[0]))); tot++; if (dot(n, d) > 0) outw++; }
      ok(outw / tot > 0.9, `${sd}: the plates' faces look outwards`, outw / tot);
    }
    ok(M.every((m) => m.paint && m.paint.metal && Array.isArray(m.paint.fill)), 'each comes with its paint: metal', M[0].paint);
    // an arm lifted out to the side: the lower plates go with the arm, the top one stays on the shoulder
    const J2 = JSON.parse(JSON.stringify(POSE.j)), S = J2['upperarm01.L'], armLen = len(sub(J2['lowerarm01.L'], S)); J2['lowerarm01.L'] = [S[0] + armLen * 0.96, S[1] + armLen * 0.25, S[2]];
    const M2 = make(J2).filter((m) => m.side === 'L'), a2 = nrm(sub(J2['lowerarm01.L'], S));
    const lowest = M2[M2.length - 1], c2 = pts(lowest).reduce((p, q) => add(p, mul(q, 1 / (lowest.pos.length / 3))), [0, 0, 0]);
    ok(dot(sub(c2, S), a2) > 0.06 * k, 'an arm lifted to the side carries its lower plates with it', dot(sub(c2, S), a2));
    const clear = Math.min(...M2.flatMap(pts).map((p) => { const d = sub(p, S), s = dot(d, a2); return s > 0.03 * k ? len(sub(d, mul(a2, s))) : 1; }));
    ok(clear > 0.045 * k, 'and they still stand clear of the arm', clear);
    const o = AR.pauldronMeshes(POSE.j, k, { size: 1.3 }), big = Math.max(...o.filter((m) => m.side === 'L').flatMap(pts).map((p) => p[1])), std = Math.max(...M.filter((m) => m.side === 'L').flatMap(pts).map((p) => p[1]));
    ok(big > std + 0.004, 'size: 1.3 makes them larger', [std, big]);
  }
}

// ---- 5. a sword in its scabbard: home, on its way out along the scabbard, and out
{
  const CH = await import('../engine/character.js'), BD = await import('../engine/bodydraw.js');
  const k = POSE.height / 1.8, ax = [0.239, -0.5, -0.832], n = Math.hypot(...ax), A = ax.map((v) => v / n);
  // (a rotation taking +Y to the scabbard's line: the Stage hangs both along it)
  const q = (() => { const c = [A[2], 0, -A[0]], s = Math.hypot(...c), w = 1 + A[1], l = Math.hypot(c[0], c[1], c[2], w); void s; return [c[0] / l, c[1] / l, c[2] / l, w / l]; })();
  const mouth = [0.2, 1.04, 0.14], at = (d) => [mouth[0] - A[0] * d, mouth[1] - A[1] * d, mouth[2] - A[2] * d];
  const pose = (swordPos, swordQ = q) => ({ ...poseAt(), props: { scabbard: { pos: mouth, q, visible: true }, sword: { pos: swordPos, q: swordQ, visible: true } } });
  ok(CH.swordInHand(pose(at(0.124 * k))) === false, 'a sword home in its scabbard (its guard on the mouth) is not out', CH.swordInHand(pose(at(0.124 * k))));
  ok(CH.swordInHand(pose(at(0.45 * k))) === false, 'half drawn, still in the scabbard\'s line, it still rides the scabbard', CH.swordInHand(pose(at(0.45 * k))));
  ok(CH.swordInHand(pose([-0.3, 1.2, 0.45])) === true, 'in a hand away from the scabbard it is out', CH.swordInHand(pose([-0.3, 1.2, 0.45])));
  ok(CH.swordInHand(pose(at(0.3 * k), [0, 0, 0, 1])) === true, 'crossing the scabbard\'s line (not along it) it is out', CH.swordInHand(pose(at(0.3 * k), [0, 0, 0, 1])));
  // what is drawn: the scabbard takes the whole blade, whatever the design's sword measures
  ok(typeof BD.carriedMeshes === 'function', 'bodydraw exports carriedMeshes(character, pose): what a body carries, as meshes', typeof BD.carriedMeshes);
  if (typeof BD.carriedMeshes === 'function') {
    for (const sword of [{}, { len: 0.62 }, { len: 0.95, width: 0.03 }, { len: 0.8, scale: 1.25 }]) {
      const ch = new Character(makeDesign({ name: 'armed', sword })), M = BD.carriedMeshes(ch, pose(at(0.124 * k)));
      const far = (m) => { let best = -Infinity; for (let v = 0; v < m.pos.length; v += 3) best = Math.max(best, (m.pos[v] - mouth[0]) * A[0] + (m.pos[v + 1] - mouth[1]) * A[1] + (m.pos[v + 2] - mouth[2]) * A[2]); return best; };
      const near = (m) => { let best = Infinity; for (let v = 0; v < m.pos.length; v += 3) best = Math.min(best, (m.pos[v] - mouth[0]) * A[0] + (m.pos[v + 1] - mouth[1]) * A[1] + (m.pos[v + 2] - mouth[2]) * A[2]); return best; };
      const blade = M.find((m) => m.paint?.group === 'blade'), sc = M.find((m) => m.name === 'scabbard');
      ok(!!blade && !!sc, `${JSON.stringify(sword)}: a blade and a scabbard are drawn`, M.map((m) => m.name ?? m.paint?.group));
      if (blade && sc) {
        ok(far(sc) > far(blade) + 0.01 * k, `${JSON.stringify(sword)}: the scabbard is longer than the blade in it (the point does not stand out of its end)`, far(sc) - far(blade));
        ok(near(blade) > -0.012 * k && near(blade) < 0.03 * k, `${JSON.stringify(sword)}: the blade begins at the scabbard's mouth (the guard sits on it)`, near(blade));
      }
    }
  }
}

if (fails.length) { console.error(`costume: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`costume: ${checks} rules hold`);
