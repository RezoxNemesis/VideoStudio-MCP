// Hairstyles drawn the animator's way (kit/hairstyles.js): node devtests/hairdesign.mjs     (exit 1 when a rule is broken)
// Eight styles on one head must be told apart at a glance — by their outline — and each must be what its name says:
// spikes stand off, long hair reaches the shoulders, a tail is tied, a parting is a parting. Clumps grow from the crown
// and are solid to the head (wedges, not ribbons), and come to a point.
import fs from 'node:fs';
import { makeHair, hairMeshes } from '../engine/hair.js';
import { HAIR_DESIGNS } from '../kit/hairstyles.js';
import { HAIR_STYLES, man } from '../kit/cast.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const FX = JSON.parse(fs.readFileSync(new URL('./fixtures/head_mesh.json', import.meta.url), 'utf8'));
const sp = Float32Array.from(FX.skin.pos), cp = Float32Array.from(FX.cap.pos);
const B = { parts: [{ name: 'haircap', material: 'hair', pos: cp, rest0: cp, idx: Uint32Array.from(FX.cap.idx), n: cp.length / 3 }, { name: 'skin', material: 'skin', pos: sp, rest0: sp, idxHead: Uint32Array.from(FX.skin.idx), n: sp.length / 3 }],
  landmarks: FX.landmarks, bi: { head: 0 }, R: Float32Array.from([1, 0, 0, 0, 1, 0, 0, 0, 1]), T: new Float32Array(3) };
const L = FX.landmarks;

const NAMES = ['messy', 'neat', 'spiky', 'wavy', 'middlePart', 'sidePart', 'longTied', 'longLoose'];
ok(NAMES.every((n) => typeof HAIR_DESIGNS[n] === 'function'), 'the eight styles of the guide are there', Object.keys(HAIR_DESIGNS));

// the outline of a style, as seen from the front and from the side: per band of height, how far out the hair reaches
const outline = (pts) => { const fr = [], sd = []; for (let i = 0; i < 24; i++) { fr.push(0); sd.push(0); }
  const y0 = L.nape[1] - 0.2; for (let k = 0; k < pts.length; k += 3) { const b = Math.floor((pts[k + 1] - y0) / 0.0125); if (b < 0 || b >= 24) continue; fr[b] = Math.max(fr[b], Math.abs(pts[k])); sd[b] = Math.max(sd[b], Math.abs(pts[k + 2] - 0.02)); } return [...fr, ...sd]; };
const OUT = {}, H = {};
for (const n of NAMES) {
  const spec = HAIR_DESIGNS[n](), h = makeHair(B, spec, 7); H[n] = { spec, h };
  ok(!!h && h.clumps.length === spec.locks.length && !!h.mass, `"${n}" builds on the head: a mass and its clumps`, h && h.clumps.length);
  if (!h) continue;
  const ms = hairMeshes(h, B, 1 / 60, 0), all = []; for (const m of ms) if (!m.tie) for (let k = 0; k < m.pos.length; k++) all.push(m.pos[k]);
  OUT[n] = { pts: all, ol: outline(all), low: Math.min(...all.filter((_, k) => k % 3 === 1)) };
  // every clump is solid to the head and comes to a point
  ok(spec.locks.every((l) => l.th >= 0.012 && l.thTip <= 0.1), `"${n}": every clump is a solid wedge that comes to a point (th ≥ lift, thin at its tip)`, spec.locks.map((l) => [l.th, l.thTip]).slice(0, 3));
  // big clumps, not strands: most of them are broad
  const big = spec.locks.filter((l) => l.w >= 0.045).length / spec.locks.length;
  ok(big >= 0.75, `"${n}": hair is drawn in LARGE clumps (most ≥ 4.5 cm wide)`, big);
}
// told apart at a glance: every two outlines differ
const dist = (a, b) => Math.sqrt(a.reduce((s, v, i) => s + (v - b[i]) ** 2, 0));
for (let i = 0; i < NAMES.length; i++) for (let j = i + 1; j < NAMES.length; j++) { const a = OUT[NAMES[i]], b = OUT[NAMES[j]]; if (a && b) ok(dist(a.ol, b.ol) > 0.025, `"${NAMES[i]}" and "${NAMES[j]}" have different outlines`, dist(a.ol, b.ol)); }
// each is what its name says
{ const top = (n) => Math.max(...OUT[n].pts.filter((_, k) => k % 3 === 1));
  ok(top('spiky') > top('messy') + 0.012 && top('spiky') > top('neat') + 0.02, 'spiky: its points stand off over the crown, higher than any other cut', [top('spiky'), top('messy'), top('neat')]);
  ok(top('neat') < top('messy') - 0.005, 'neat: close to the head, lower on top than the messy cut', [top('neat'), top('messy')]);
  ok(OUT.longLoose.low < L.nape[1] - 0.12, 'long, loose: the hair reaches the shoulders (12 cm or more below the nape)', OUT.longLoose.low - L.nape[1]);
  ok(OUT.messy.low > L.nape[1] - 0.06 && OUT.neat.low > L.nape[1] - 0.03, 'the short cuts end at the nape', [OUT.messy.low - L.nape[1], OUT.neat.low - L.nape[1]]);
  ok(!!H.longTied.h.tail && H.longTied.h.tail.tie, 'long, tied: a tail bound with a cord', !!H.longTied.h.tail);
  ok(H.longTied.h.tail && H.longTied.h.tail.dir[1] < -0.6, 'the tail hangs down from its tie (not out behind the head)', H.longTied.h.tail?.dir);
  // a parting: no clump crosses its line on top of the head
  const crosses = (spec, az) => spec.locks.filter((l) => l.from[1] > 45 && l.to[1] > 45 && Math.sign(l.from[0] - az) !== Math.sign(l.to[0] - az) && Math.abs(l.from[0] - az) > 2 && Math.abs(l.to[0] - az) > 2).length;
  ok(crosses(H.middlePart.spec, 0) === 0, 'middle part: no clump on top crosses the middle', crosses(H.middlePart.spec, 0));
  const mid = H.middlePart.spec.locks.filter((l) => l.to[1] < 20 && Math.abs(l.to[0]) < 45);
  ok(mid.length >= 2 && mid.every((l) => Math.abs(l.to[0]) >= 15), 'middle part: the curtains open off the middle of the forehead', mid.map((l) => l.to[0]));
  ok(H.sidePart.spec.locks.filter((l) => l.from[0] > 30 && l.from[0] < 60 && l.from[1] > 70).length >= 6, 'side part: the clumps start along a parting over one eye', H.sidePart.spec.locks.length);
  const bangs = (n) => H[n].spec.locks.filter((l) => l.to[1] < 26 && Math.abs(l.to[0]) <= 36 && l.from[1] > 55).length;
  for (const n of ['messy', 'spiky', 'wavy', 'longLoose']) ok(bangs(n) >= 3 && bangs(n) <= 5, `"${n}": three to five big bangs over the forehead`, bangs(n));
  ok(H.wavy.spec.locks.filter((l) => l.wave).length >= 8, 'wavy: its clumps run in waves', H.wavy.spec.locks.filter((l) => l.wave).length);
  ok(H.messy.spec.locks.some((l) => l.hang && l.flick >= 0.6), 'messy: the ends at ear and nape turn out', null);
}
// every style is a part of the cast, painted clean
for (const n of NAMES) ok(typeof HAIR_STYLES[n] === 'function', `the cast offers "${n}"`, Object.keys(HAIR_STYLES));
{ const p = man({ hair: 'spiky' }); ok(p.design.hair.paint === 'clean' && p.design.hair3.locks.length > 10, 'a man of the cast with a drawn style is painted clean', p.design.hair.paint); }

console.log(`${checks - fails.length}/${checks} hair design rules hold`);
if (fails.length) { for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
