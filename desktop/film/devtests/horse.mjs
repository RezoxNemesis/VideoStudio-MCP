// A horse with a body: node devtests/horse.mjs        (exit 1 when a rule is broken)
// The horse used to be a flat drawing (a few outlines filled in), which beside painted people read as a paper cut-out,
// and nobody could sit on it. It is a real, rounded body now, made from the same skeleton that walks, trots and
// gallops: trunk, neck, head, four legs with their joints and hooves, mane and tail, painted like the people.
// The measures are a riding horse's (withers 1.56 m at scale 1): as long as it is tall, the belly well above the
// knees, legs that are thick where the muscle is and thin below the knee and the hock.
import { Horse } from '../engine/horse.js';
import { horseMeshes, trunkHalfWidth } from '../engine/horsemesh.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const H = 1.56;
const verts = (m) => { const out = []; for (let i = 0; i < m.pos.length; i += 3) out.push([m.pos[i], m.pos[i + 1], m.pos[i + 2]]); return out; };
const part = (ms, name) => ms.find((m) => m.name === name);
const span = (vs, ax) => [Math.min(...vs.map((v) => v[ax])), Math.max(...vs.map((v) => v[ax]))];
/** the volume a closed mesh holds (positive when its triangles face outwards) */
function volume(m) {
  let v = 0;
  for (let t = 0; t < m.idx.length; t += 3) {
    const a = m.idx[t] * 3, b = m.idx[t + 1] * 3, c = m.idx[t + 2] * 3, p = m.pos;
    v += (p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])) / 6;
  }
  return v;
}

// standing square on all four legs (alive: 0 — the build of the horse is measured, not what it does while it waits),
// facing +z (its left is +x), at the origin
const stand = new Horse({ coat: '#2a2226', alive: 0 }).place([0, 0], 0, 0, 'stand', 0);
const ms = horseMeshes(stand, (c) => c);
{
  const names = ms.map((m) => m.name);
  for (const n of ['trunk', 'neck', 'head', 'legLF', 'legRF', 'legLH', 'legRH', 'hoofLF', 'hoofRF', 'hoofLH', 'hoofRH', 'mane', 'tail', 'earL', 'earR'])
    ok(names.includes(n), `a horse has a ${n}`, names);
  ok(names.includes('crest'), 'the mane stands along the crest too (it falls to one side: from the other there was no mane at all)', names);
  let bad = 0, thin = 0;
  for (const m of ms) {
    for (let i = 0; i < m.pos.length; i++) if (!Number.isFinite(m.pos[i])) bad++;
    for (let i = 0; i < m.idx.length; i++) if (m.idx[i] >= m.pos.length / 3) bad++;
    if (m.idx.length < 24 * 3 && !/^(eye|nostril)/.test(m.name)) thin++;
    if (!m.paint || !Array.isArray(m.paint.fill) || !Array.isArray(m.paint.shade)) bad++;
  }
  ok(bad === 0, 'every part is a sound mesh with a paint', bad);
  // (light falls on a part by the way its faces look: a part turned inside out is lit from the wrong side)
  const inside = ms.filter((m) => !/^(mane)$/.test(m.name) && volume(m) <= 0).map((m) => m.name);
  ok(inside.length === 0, 'every closed part faces outwards', inside);
  ok(thin === 0, 'and no part is a sliver', thin);
}
{
  const t = verts(part(ms, 'trunk'));
  const top = Math.max(...t.filter((v) => v[2] > 0.3 && v[2] < 0.5).map((v) => v[1]));
  ok(Math.abs(top - H) < 0.05, 'the withers stand 1.56 m high', top);
  const [z0, z1] = span(t, 2);
  ok((z1 - z0) / H > 0.94 && (z1 - z0) / H < 1.12, 'the body is about as long as the horse is tall (chest to buttock)', (z1 - z0) / H);
  const belly = Math.min(...t.filter((v) => v[2] > 0.05 && v[2] < 0.3).map((v) => v[1]));
  ok(belly / H > 0.48 && belly / H < 0.56, 'the chest is about as deep as the legs below it are long (the first horse was a greyhound on stilts: its belly at 0.58)', belly / H);
  const half = Math.max(...t.filter((v) => Math.abs(v[2]) < 0.1).map((v) => Math.abs(v[0])));
  ok(half > 0.25 && half < 0.33, 'the barrel is 50 to 65 cm wide', half);
  const [x0, x1] = span(t, 0);
  ok(Math.abs(x0 + x1) < 0.01, 'left and right are alike', x0 + x1);
  const croup = Math.max(...t.filter((v) => v[2] < -0.45 && v[2] > -0.65).map((v) => v[1]));
  ok(croup > H - 0.1 && croup < H + 0.02, 'the croup is nearly as high as the withers', croup);
  const back = Math.max(...t.filter((v) => v[2] > -0.15 && v[2] < 0.0).map((v) => v[1]));
  ok(back < top - 0.04 && back < croup, 'and the back dips between them (where the saddle lies)', back);
  ok(volume(part(ms, 'trunk')) > 0.25 && volume(part(ms, 'trunk')) < 0.6, 'the trunk is closed and faces outwards (about 0.4 cubic metres)', volume(part(ms, 'trunk')));
}
{
  const hd = verts(part(ms, 'head')), j = stand.j;
  const ax = [j.muzzle[0] - j.poll[0], j.muzzle[1] - j.poll[1], j.muzzle[2] - j.poll[2]], al = Math.hypot(...ax);
  const along = hd.map((v) => ((v[0] - j.poll[0]) * ax[0] + (v[1] - j.poll[1]) * ax[1] + (v[2] - j.poll[2]) * ax[2]) / al);
  const len = Math.max(...along) - Math.min(...along);
  ok(len / H > 0.36 && len / H < 0.46, 'the head is two fifths of the height long', len / H);
  // deep at the jaw, fine at the muzzle
  const depthAt = (a, b) => { const vs = hd.filter((_, i) => along[i] > a * len && along[i] < b * len); const [y0, y1] = span(vs, 1), [z0, z1] = span(vs, 2); return Math.hypot(y1 - y0, z1 - z0); };
  ok(depthAt(0.15, 0.4) > depthAt(0.75, 0.95) * 1.25, 'deep at the jaw, fine at the muzzle', [depthAt(0.15, 0.4), depthAt(0.75, 0.95)]);
  // the mane lies on the neck from the crest down its right side: outside the neck's skin everywhere (it lay half
  // inside it and showed as ragged patches)
  const mn = verts(part(ms, 'mane')), nkv = verts(part(ms, 'neck'));
  const side = mn.filter((v) => v[0] < -0.02).length / mn.length;
  ok(side > 0.6, 'the mane falls to the right of the neck', side);
  const nearest = mn.map((v) => Math.min(...nkv.map((n) => Math.hypot(v[0] - n[0], v[1] - n[1], v[2] - n[2]))));
  ok(Math.max(...nearest) < 0.2, 'and lies on it', Math.max(...nearest));
  const nk = verts(part(ms, 'neck'));
  ok(Math.max(...nk.map((v) => v[1])) > H + 0.25, 'the neck carries the head above the withers', Math.max(...nk.map((v) => v[1])));
  ok(Math.max(...verts(part(ms, 'earL')).map((v) => v[1])) > Math.max(...hd.map((v) => v[1])) - 0.02, 'the ears stand on top of the head', 0);
}
for (const leg of ['LF', 'RF', 'LH', 'RH']) {
  const hoof = verts(part(ms, `hoof${leg}`)), lg = verts(part(ms, `leg${leg}`)), front = leg[1] === 'F';
  const low = Math.min(...hoof.map((v) => v[1]));
  ok(low > -0.005 && low < 0.02, `the ${leg} hoof stands on the ground`, low);
  const joint = stand.j[leg].mid[1] / H;
  ok(front ? joint > 0.27 && joint < 0.36 : joint > 0.37 && joint < 0.46, front ? `the ${leg} knee is a third of the way up` : `the ${leg} hock is two fifths of the way up`, joint);
  // thick where the muscle is, thin below the knee and the hock
  const girth = (y0, y1) => { const vs = lg.filter((v) => v[1] > y0 && v[1] < y1); if (!vs.length) return 0; const [a, b] = span(vs, 0), [c, d] = span(vs, 2); return Math.max(b - a, d - c); };
  const cannon = girth(0.22, 0.32), upper = girth(front ? 0.7 : 0.8, front ? 0.85 : 0.95);
  ok(cannon > 0.085 && cannon < 0.13, `the ${leg} cannon is a slim bone, but a horse's (8 cm of bone and tendon read as a stilt)`, cannon);
  ok(upper > cannon * 1.6, `and the ${leg} is thick above it`, [upper, cannon]);
  const side = lg.reduce((s, v) => s + v[0], 0) / lg.length;
  ok(leg[0] === 'L' ? side > 0.08 : side < -0.08, `the ${leg} leg is on its own side`, side);
  ok(Math.max(...lg.map((v) => v[1])) > 0.95, `the ${leg} leg goes up into the body`, Math.max(...lg.map((v) => v[1])));
}
{
  // a hind leg is three bones: thigh, gaskin and cannon. Standing, the cannon is nearly upright under the hock and the
  // stifle is well in front of it. (With two bones the hock stood a third of a metre behind the hoof: a sickle.)
  const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
  for (const leg of ['LH', 'RH']) {
    const L = stand.j[leg];
    ok(!!L.stifle, `the ${leg} leg has a stifle`, Object.keys(L));
    if (!L.stifle) continue;
    ok(L.hoof[2] - L.mid[2] > -0.02 && L.hoof[2] - L.mid[2] < 0.12, `standing, the ${leg} cannon is nearly upright (the hock over the hoof)`, L.hoof[2] - L.mid[2]);
    ok(L.stifle[2] - L.mid[2] > 0.25, `and the ${leg} stifle is well in front of the hock`, L.stifle[2] - L.mid[2]);
    ok(L.top[1] > 1.2 && L.stifle[1] < L.top[1] - 0.15, `the ${leg} thigh goes down from the hip to the stifle`, [L.top[1], L.stifle[1]]);
  }
  // bones do not stretch: over a whole stride at a walk and at a gallop every bone keeps its length
  for (const gait of ['walk', 'gallop']) {
    const hb = new Horse({}), len = {}; let worst = 0, which = '';
    for (let i = 0; i <= 60; i++) {
      const d = i * (gait === 'walk' ? 0.06 : 0.18); hb.place([0, d], 0, d, gait, d / (gait === 'walk' ? 1.6 : 10.5));
      for (const leg of ['LF', 'RF', 'LH', 'RH']) {
        const L = hb.j[leg], bones = L.stifle ? [['thigh', L.top, L.stifle], ['gaskin', L.stifle, L.mid], ['cannon', L.mid, L.hoof]] : [['forearm', L.top, L.mid], ['cannon', L.mid, L.hoof]];
        for (const [nm, a, b] of bones) { const key = leg + nm, l = dist(a, b); len[key] ??= [l, l]; len[key][0] = Math.min(len[key][0], l); len[key][1] = Math.max(len[key][1], l); }
      }
    }
    for (const key in len) { const st = len[key][1] / len[key][0] - 1; if (st > worst) { worst = st; which = key; } }
    ok(worst < 0.03, `no bone stretches at a ${gait}`, [which, worst]);
  }
  // the tail hangs: it falls from the dock to about the hocks, close behind the quarters
  const tl = verts(part(ms, 'tail')), tr = verts(part(ms, 'trunk'));
  ok(Math.min(...tl.map((v) => v[1])) < 0.75 && Math.min(...tl.map((v) => v[1])) > 0.3, 'the tail falls to about the hocks', Math.min(...tl.map((v) => v[1])));
  ok(Math.min(...tr.map((v) => v[2])) - Math.min(...tl.map((v) => v[2])) < 0.3, 'close behind the quarters (it does not stick out)', Math.min(...tr.map((v) => v[2])) - Math.min(...tl.map((v) => v[2])));
}
{
  // walking: at a walk a horse always has two hooves on the ground at the least, a hoof that is down stays where it
  // is while the body passes over it, and nothing leaps from one frame to the next
  const hw = new Horse({ coat: '#2a2226' });
  let prev = null, least = 4, slide = 0, leap = 0;
  for (let i = 0; i <= 50; i++) {
    const d = i * 0.07, t = d / 1.6;
    hw.place([0, d], 0, d, 'walk', t);
    const m = horseMeshes(hw, (c) => c), down = {};
    for (const leg of ['LF', 'RF', 'LH', 'RH']) { const hv = verts(part(m, `hoof${leg}`)), low = Math.min(...hv.map((v) => v[1])); if (low < 0.003) down[leg] = hv.reduce((s, v) => [s[0] + v[0] / hv.length, s[1] + v[2] / hv.length], [0, 0]); }
    least = Math.min(least, Object.keys(down).length);
    if (prev) {
      for (const leg in down) if (prev.down[leg]) slide = Math.max(slide, Math.hypot(down[leg][0] - prev.down[leg][0], down[leg][1] - prev.down[leg][1]));
      for (const a of m) { const b = part(prev.m, a.name); if (!b || b.pos.length !== a.pos.length) { leap = 9; continue; } for (let k = 0; k < a.pos.length; k += 3) leap = Math.max(leap, Math.hypot(a.pos[k] - b.pos[k], a.pos[k + 1] - b.pos[k + 1], a.pos[k + 2] - b.pos[k + 2] - 0.07)); }
    }
    prev = { m, down };
  }
  ok(least >= 2, 'at a walk two hooves or more are always down', least);
  ok(slide < 0.012, 'a hoof that is down does not slide', slide);
  ok(leap < 0.3, 'nothing leaps between two frames', leap);
}

{
  // tack: a saddle on the back with its cloth and girth, a bridle on the head, reins from the mouth to the rider's
  // hands (to the saddle's front when nobody holds them), stirrups at the rider's feet
  const TACK = ['saddle', 'saddlecloth', 'girth', 'bridle', 'reins'];
  ok(!ms.some((m) => TACK.includes(m.name)), 'a bare horse wears nothing', ms.map((m) => m.name).filter((n) => TACK.includes(n)));
  const th = new Horse({ coat: '#2a2226', tack: true, cloth: '#5a1a1e' }).place([0, 0], 0, 0, 'stand', 0);
  const hands = [[0.12, 1.86, 0.42], [-0.12, 1.86, 0.42]], feet = [[0.34, 0.86, 0.3], [-0.34, 0.86, 0.3]];
  const tm = horseMeshes(th, (c) => c, { hands, feet }), names = tm.map((m) => m.name);
  for (const n of [...TACK, 'stirrupL', 'stirrupR']) ok(names.includes(n), `a saddled horse has ${n}`, names);
  if (TACK.every((n) => names.includes(n)) && names.includes('stirrupL')) {
    const sdl = verts(part(tm, 'saddle')), cl = verts(part(tm, 'saddlecloth')), tr = verts(part(tm, 'trunk'));
    const backTop = Math.max(...tr.filter((v) => Math.abs(v[2]) < 0.15).map((v) => v[1]));
    const [sz0, sz1] = span(sdl, 2), [sy0, sy1] = span(sdl, 1);
    ok(sz0 > -0.35 && sz1 < 0.42 && sy1 > backTop && sy1 < backTop + 0.16 && sy0 > backTop - 0.4, 'the saddle lies on the back behind the withers', [sz0, sz1, sy0, sy1, backTop]);
    ok(Math.min(...cl.map((v) => v[1])) < sy0 - 0.05 && Math.max(...cl.map((v) => Math.abs(v[0]))) > 0.2, 'its cloth hangs down both sides below it', [Math.min(...cl.map((v) => v[1])), sy0]);
    const inside = cl.filter((v) => Math.abs(v[0]) < trunkHalfWidth(v[2], v[1]) - 0.002).length;
    ok(inside === 0, 'the cloth lies on the horse, not in it', inside);
    const g = verts(part(tm, 'girth')), [gy0, gy1] = span(g, 1);
    ok(gy0 < 0.85 && gy1 > 1.3, 'the girth goes round under the belly', [gy0, gy1]);
    const rn = verts(part(tm, 'reins')), near = (p, q) => Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]);
    ok(Math.min(...rn.map((v) => near(v, th.j.muzzle))) < 0.2, 'the reins come from the mouth', Math.min(...rn.map((v) => near(v, th.j.muzzle))));
    ok(hands.every((h) => Math.min(...rn.map((v) => near(v, h))) < 0.06), 'to the hands that hold them', hands.map((h) => Math.min(...rn.map((v) => near(v, h)))));
    const st = verts(part(tm, 'stirrupL'));
    ok(Math.min(...st.map((v) => near(v, feet[0]))) < 0.12 && Math.max(...st.map((v) => v[1])) > 1.2, 'a stirrup hangs from the saddle to the foot', [Math.min(...st.map((v) => near(v, feet[0]))), Math.max(...st.map((v) => v[1]))]);
    // nobody riding: the reins lie on the neck to the saddle's front, the stirrups hang at their own length
    const lone = horseMeshes(th, (c) => c), lr = verts(part(lone, 'reins'));
    ok(Math.max(...lr.map((v) => v[1])) < 2.2 && Math.min(...lr.map((v) => near(v, [0, backTop + 0.05, 0.3]))) < 0.2, 'with nobody riding, the reins end at the saddle', Math.min(...lr.map((v) => near(v, [0, backTop + 0.05, 0.3]))));
    ok(lone.some((m) => m.name === 'stirrupL') && Math.min(...verts(part(lone, 'stirrupL')).map((v) => v[1])) > 0.7, 'and the stirrups hang beside the belly', 0);
    let bad = 0; for (const m of tm) for (let i = 0; i < m.pos.length; i++) if (!Number.isFinite(m.pos[i])) bad++;
    ok(bad === 0, 'all of it sound', bad);
  }
}

// ---- a standing horse is never a statue: it breathes, carries its head about, turns its ears, swishes its tail, rests a leg
{
  const HO = ['LH', 'RH', 'LF', 'RF'], rng = (a) => Math.max(...a) - Math.min(...a);
  const run = (seed, o = {}) => { const h = new Horse({ coat: '#2a2226', seed, ...o }), S = []; for (let t = 0; t <= 30; t += 1 / 24) { h.place([0, 0], 0, 0, 'stand', t); S.push({ t, poll: [...h.j.poll], muzzle: [...h.j.muzzle], back: [...h.j.back], croup: [...h.j.croup], breath: h.breath, eL: h.ears.L, eR: h.ears.R, tail: [...h.tail[4]], hoof: HO.map((n) => [...h.j[n].hoof]), hock: ['LH', 'RH'].map((n) => [...h.j[n].mid]) }); } return S; };
  const S = run(3);
  ok(rng(S.map((s) => s.poll[1])) > 0.03 && rng(S.map((s) => s.muzzle[1])) > 0.05, 'a standing horse carries its head about: it nods and lifts it (the poll by 3 cm and more, the muzzle by 5)', [rng(S.map((s) => s.poll[1])), rng(S.map((s) => s.muzzle[1]))]);
  ok(rng(S.map((s) => s.muzzle[0])) > 0.05, 'and turns it to look to one side and the other', rng(S.map((s) => s.muzzle[0])));
  const br = S.map((s) => s.breath); let cross = 0; for (let i = 1; i < br.length; i++) if (br[i - 1] < 0 && br[i] >= 0) cross++;
  ok(Math.min(...br) < -0.9 && Math.max(...br) > 0.9 && cross >= 4 && cross <= 9, 'it breathes: 8 to 18 breaths a minute, from empty to full', [Math.min(...br), Math.max(...br), cross]);
  ok(rng(S.map((s) => s.back[1])) > 0.003 && rng(S.map((s) => s.back[1])) < 0.03, 'the back rises and sinks a little with the breath', rng(S.map((s) => s.back[1])));
  ok(rng(S.map((s) => s.eL)) > 0.5 && rng(S.map((s) => s.eR)) > 0.5 && Math.max(...S.map((s) => Math.abs(s.eL - s.eR))) > 0.3, 'its ears turn, each by itself', [rng(S.map((s) => s.eL)), rng(S.map((s) => s.eR)), Math.max(...S.map((s) => Math.abs(s.eL - s.eR)))]);
  ok(rng(S.map((s) => s.tail[0])) > 0.1, 'the tail swishes', rng(S.map((s) => s.tail[0])));
  ok(rng(S.map((s) => s.croup[0])) > 0.012 && rng(S.map((s) => s.croup[0])) < 0.08, 'its weight goes from one hind leg to the other (the croup sways by centimetres)', rng(S.map((s) => s.croup[0])));
  // hooves: where a hoof stands it stays; a leg that rests is lifted a little, never slid
  let slide = 0, lift = 0;
  for (let k = 0; k < 4; k++) { slide = Math.max(slide, rng(S.map((s) => s.hoof[k][0])), rng(S.map((s) => s.hoof[k][2]))); lift = Math.max(lift, rng(S.map((s) => s.hoof[k][1]))); }
  ok(slide < 1e-6 && lift < 1e-6, 'no hoof ever slides over the ground or leaves it', [slide, lift]);
  const give = [0, 1].map((q) => rng(S.map((s) => s.hock[q][2])));
  ok(give[0] > 0.05 && give[1] > 0.05 && give[0] < 0.3, 'a hind leg is rested in turn: its hock gives forward (5 cm and more) while the toe stays down', give);
  const base = [0, 1].map((q) => Math.min(...S.map((s) => s.hock[q][2]))); let both = 0; for (const s of S) if (s.hock[0][2] > base[0] + 0.03 && s.hock[1][2] > base[1] + 0.03) both++;
  ok(both === 0, 'never both hind legs at once', both);
  // nothing jumps
  let step = 0, estep = 0; for (let i = 1; i < S.length; i++) { for (const n of ['poll', 'muzzle', 'back', 'croup']) step = Math.max(step, Math.hypot(S[i][n][0] - S[i - 1][n][0], S[i][n][1] - S[i - 1][n][1], S[i][n][2] - S[i - 1][n][2])); estep = Math.max(estep, Math.abs(S[i].eL - S[i - 1].eL), Math.abs(S[i].eR - S[i - 1].eR)); }
  ok(step < 0.02 && estep < 0.35, 'nothing jumps from one frame to the next (the head under 2 cm, an ear under a fifth of its turn)', [step, estep]);
  // the same moment is the same; another horse is another horse; a horse can be told to hold still
  const A = run(3), B = run(8);
  ok(A.every((s, i) => Math.abs(s.poll[1] - S[i].poll[1]) < 1e-9 && s.eL === S[i].eL && s.breath === S[i].breath), 'the same horse at the same moment is the same (frames can be drawn in any order)', 0);
  let diff = 0; for (let i = 0; i < S.length; i++) diff += Math.abs(B[i].poll[1] - S[i].poll[1]) + Math.abs(B[i].breath - S[i].breath) * 0.01; 
  ok(diff / S.length > 0.004, 'two horses are not in step', diff / S.length);
  const D = run(3, { alive: 0 });
  ok(rng(D.map((s) => s.poll[1])) < 0.025 && rng(D.map((s) => s.eL)) === 0 && rng(D.map((s) => s.hock[0][2])) < 1e-9, 'alive: 0 leaves a horse as still as it used to be', [rng(D.map((s) => s.poll[1])), rng(D.map((s) => s.eL))]);
  // a horse that walks is not disturbed by any of it
  const w = new Horse({ coat: '#2a2226', seed: 3 }); w.place([0, 0], 0, 3.3, 'walk', 5);
  ok(w.breath !== undefined && w.ears && Math.abs(w.ears.L) < 0.5 && Math.abs(w.ears.R) < 0.5, 'a walking horse keeps its ears forward and still has breath', [w.breath, w.ears]);
  // the body shows it: the barrel fills, the ears turn
  const h1 = new Horse({ coat: '#2a2226', seed: 3 }).place([0, 0], 0, 0, 'stand', 0); h1.breath = -1; h1.ears = { L: 0, R: 0 };
  const m0 = horseMeshes(h1, (c) => c); h1.breath = 1; h1.ears = { L: 1.2, R: 0 }; const m1 = horseMeshes(h1, (c) => c);
  const wd = (ms2) => { const s2 = span(verts(part(ms2, 'trunk')), 0); return s2[1] - s2[0]; };
  ok(wd(m1) > wd(m0) * 1.01 && wd(m1) < wd(m0) * 1.06, 'the barrel is fuller at the top of a breath (1 to 6 percent wider)', [wd(m0), wd(m1)]);
  const tip = (ms2, nm) => { const vs = verts(part(ms2, nm)); return vs.reduce((a, b) => (b[1] > a[1] ? b : a)); };
  ok(Math.hypot(...tip(m1, 'earL').map((v, i) => v - tip(m0, 'earL')[i])) > 0.04 && Math.hypot(...tip(m1, 'earR').map((v, i) => v - tip(m0, 'earR')[i])) < 1e-6, 'an ear that turns back is drawn turned back; the other stays', [tip(m0, 'earL'), tip(m1, 'earL')]);
}

if (fails.length) { console.error(`horse: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`horse: ${checks} rules hold`);
