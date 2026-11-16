// Clothes made by the engine: node devtests/wardrobe.mjs        (exit 1 when a rule is broken)
// The Stage's garments are the body's own skin let out a little: they follow every muscle, end in torn edges and have
// no collar, placket, pocket, belt or shoe. `design.wear` asks for cut clothes instead: smooth shells that hang off the
// body (engine/wardrobe.js), with the pieces a drawing of clothes is made of. Tested on a real body (the father of the
// Papa film, 16 Nov 2026: fixtures/body_man.json.gz) in its rest shape.
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { dressBody } from '../engine/wardrobe.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
function body() {
  const J = JSON.parse(gunzipSync(readFileSync(new URL('./fixtures/body_man.json.gz', import.meta.url))));
  const buf = (s) => { const b = Buffer.from(s, 'base64'); return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength); };
  const sk = J.skin, pos = new Float32Array(buf(sk.pos)), idx = new Uint32Array(buf(sk.idx)), si = new Uint16Array(buf(sk.si)), sw = new Float32Array(buf(sk.sw));
  const bi = {}; J.names.forEach((n, i) => { bi[n] = i; });
  const skin = { name: 'skin', material: 'skin', n: sk.n, pos, rest0: pos, idx, si, sw, idxCore: idx, idxLimb: new Uint32Array(0), idxHead: new Uint32Array(0), out: new Float32Array(sk.n * 3), nrm: new Float32Array(sk.n * 3) };
  return { height: J.height, names: J.names, bi, nb: J.names.length, parent: Int16Array.from(J.parent), head: new Float32Array(buf(J.head)), tail: new Float32Array(buf(J.tail)), landmarks: J.landmarks,
    parts: [{ name: 'tunic', material: 'cloth', n: 0 }, { name: 'trousers', material: 'cloth', n: 0 }, { name: 'shoes', material: 'leather', n: 0 }, skin] };
}
const WEAR = { shirt: { color: '#c9cde6', sleeve: 'short', pocket: true, collar: { open: true } }, tie: { color: '#7a2030', stripe: '#d9c7c9' }, trousers: { color: '#1e2236', fit: 'straight' }, belt: { color: '#141416', buckle: '#b9bdc6' }, shoes: { kind: 'derby', color: '#121214' } };
const B = body(), k = B.height / 1.8, W = dressBody(B, { wear: WEAR });
const part = (name) => W?.parts.find((p) => p.name === name);
const box = (p, f = () => true) => { const lo = [1e9, 1e9, 1e9], hi = [-1e9, -1e9, -1e9]; for (let v = 0; v < p.n; v++) { const q = [p.pos[v * 3], p.pos[v * 3 + 1], p.pos[v * 3 + 2]]; if (!f(q)) continue; for (let c = 0; c < 3; c++) { if (q[c] < lo[c]) lo[c] = q[c]; if (q[c] > hi[c]) hi[c] = q[c]; } } return { lo, hi }; };
const skin = B.parts.find((p) => p.material === 'skin');
const armW = (v) => { let w = 0; for (let q = 0; q < 4; q++) if (/^(upperarm|lowerarm|wrist|finger|metacarpal)/.test(B.names[skin.si[v * 4 + q]])) w += skin.sw[v * 4 + q]; return w; };
const headW = (v) => { let w = 0; for (let q = 0; q < 4; q++) { let b = skin.si[v * 4 + q], h = false; for (; b >= 0; b = B.parent[b]) if (B.names[b] === 'neck02') { h = true; break; } if (h) w += skin.sw[v * 4 + q]; } return w; };
// the radius of a shell round a middle [cx, cz] at a height, in a direction: from the shell's own vertices near that height
const radiusAt = (p, y, th, c, half = 0.02, f = () => true) => { let best = 0, bd = 0.2; for (let v = 0; v < p.n; v++) { const py = p.pos[v * 3 + 1]; if (Math.abs(py - y) > half) continue; const q = [p.pos[v * 3], py, p.pos[v * 3 + 2]]; if (!f(q)) continue; const dx = q[0] - c[0], dz = q[2] - c[1]; let da = Math.abs(Math.atan2(dz, dx) - th); if (da > Math.PI) da = 2 * Math.PI - da; if (da < bd) { bd = da; best = Math.hypot(dx, dz); } } return best; };

{ // ---- what a dressed body wears
  ok(!!W, 'a design with `wear` dresses the body', !!W);
  for (const n of ['trousers', 'belt', 'buckle', 'shirt', 'cuff', 'collar', 'placket', 'pocket', 'tie', 'shoe', 'sole']) ok(!!part(n) && part(n).n > 3 && part(n).idx.length >= 3, `it wears a ${n}`, part(n)?.n ?? 0);
  for (const n of ['tunic', 'trousers', 'shoes']) ok(W?.hide.has(n), `the Stage's own ${n} is not drawn under it`, [...(W?.hide ?? [])]);
  let bad = 0, nan = 0; for (const p of W?.parts ?? []) for (let v = 0; v < p.n; v++) { let s = 0; for (let q = 0; q < 4; q++) { s += p.sw[v * 4 + q]; if (!(p.si[v * 4 + q] < B.nb)) bad++; } if (Math.abs(s - 1) > 1e-3) bad++; for (let c = 0; c < 3; c++) if (!Number.isFinite(p.pos[v * 3 + c])) nan++; }
  ok(bad === 0 && nan === 0, 'every bit of cloth hangs on the body\'s bones (weights that sum to one) and is somewhere', [bad, nan]);
  ok(dressBody(B, { wear: WEAR }) === W, 'dressing it again in the same clothes makes nothing new', 0);
}
{ // ---- trousers: a straight leg
  const T = part('trousers'), left = (q) => q[0] > 0, b = box(T, left), hip = [...B.head.subarray(B.bi['upperleg01.L'] * 3, B.bi['upperleg01.L'] * 3 + 3)], knee = [...B.head.subarray(B.bi['lowerleg01.L'] * 3, B.bi['lowerleg01.L'] * 3 + 3)], ank = [...B.head.subarray(B.bi['foot.L'] * 3, B.bi['foot.L'] * 3 + 3)];
  ok(b.lo[1] > 0.06 * k && b.lo[1] < 0.1 * k, 'the hem ends at the ankle: the shoe is seen whole under it', b.lo[1]);
  const axis = (y) => { const [a, c] = y > knee[1] ? [hip, knee] : [knee, ank], u = (a[1] - y) / (a[1] - c[1]); return [a[0] + (c[0] - a[0]) * u, a[2] + (c[2] - a[2]) * u]; };
  // seen from the front, the outer line of the leg falls nearly straight from the thigh to the hem; no knee, no calf, no balloon
  const outer = (y) => radiusAt(T, y, 0, axis(y), 0.02, left), y0 = hip[1] - 0.2 * k, y1 = b.lo[1] + 0.05, r0 = outer(y0), r1 = outer(y1); let bend = 0;
  for (let i = 1; i < 8; i++) { const y = y0 + (y1 - y0) * i / 8; bend = Math.max(bend, Math.abs(outer(y) - (r0 + (r1 - r0) * i / 8))); }
  ok(bend < 0.009, 'the outer line of the leg falls nearly straight from thigh to hem', bend);
  ok(r1 > 0.058 * k && r1 < 0.08 * k, 'seen from the front the hem is about 13 cm wide: far wider than the ankle', r1);
  const side = radiusAt(T, y1, Math.PI / 2, axis(y1), 0.02, left);
  ok(side > r1 * 1.15, 'a pressed leg is wider seen from the side than from the front (the crease runs down its front)', side / r1);
  // no skin comes through: at every height the leg lies inside its trouser leg
  let poke = 0, n = 0; for (let v = 0; v < skin.n; v++) { const q = [skin.pos[v * 3], skin.pos[v * 3 + 1], skin.pos[v * 3 + 2]]; if (!(q[0] > 0.02 && q[1] > b.lo[1] + 0.04 && q[1] < hip[1] - 0.2 && armW(v) < 0.3)) continue; const c = axis(q[1]), th = Math.atan2(q[2] - c[1], q[0] - c[0]), r = radiusAt(T, q[1], th, c, 0.025, left); n++; if (Math.hypot(q[0] - c[0], q[2] - c[1]) > r - 0.003) poke++; }
  ok(n > 200 && poke === 0, 'the leg lies inside its trouser leg everywhere, with room', [n, poke]);
  // where the legs leave the seat there is no ledge: just under the crotch a leg is as wide as the seat above it
  { const yc = Math.min(...Array.from({ length: T.n }, (_, v) => (Math.abs(T.pos[v * 3]) < 1e-5 ? T.pos[v * 3 + 1] : 9))); /* (the seat's lowest ring: the only cloth on the body's middle line) */ const wAt = (y) => box(T, (q) => q[0] > 0 && Math.abs(q[1] - y) < 0.012).hi[0]; ok(yc > 0.6 && Math.abs(wAt(yc + 0.02) - wAt(yc - 0.02)) < 0.006, 'no ledge where the legs leave the seat', [yc, wAt(yc + 0.02), wAt(yc - 0.02)]); }
  let cross = 1; for (let v = 0; v < T.n; v++) if (T.pos[v * 3 + 1] < hip[1] - 0.22 && T.pos[v * 3] > 0) cross = Math.min(cross, T.pos[v * 3]);
  ok(cross > 0.002, 'below the crotch the two legs never run into each other', cross);
  const top = box(T).hi[1], belt = box(part('belt'));
  ok(top > B.landmarks.hipY + 0.07 * k && top < B.landmarks.hipY + 0.14 * k, 'they are worn at the waist, a hand above the hip joints', top - B.landmarks.hipY);
  ok(belt.lo[1] > top - 0.05 && belt.hi[1] < top + 0.012 && belt.hi[1] - belt.lo[1] > 0.024 * k && belt.hi[1] - belt.lo[1] < 0.042 * k, 'the belt lies round their waistband, about 3 cm high', [belt.lo[1] - top, belt.hi[1] - top]);
  const bk = box(part('buckle'));
  ok(Math.abs(bk.lo[0] + bk.hi[0]) < 0.004 && bk.hi[2] > belt.hi[2] - 0.004 && bk.lo[1] > belt.lo[1] - 0.012 && bk.hi[1] < belt.hi[1] + 0.012, 'its buckle sits in the middle of the front', [bk.lo, bk.hi]);
  ok(part('belt').group !== T.group && part('buckle').group !== part('belt').group, 'belt and buckle are drawn as things of their own (a line round each)', [part('belt').group, part('buckle').group, T.group]);
}
{ // ---- shirt: a shell with ease, sleeves that stand off the arm
  const S = part('shirt'), belt = box(part('belt')), sh = [...B.head.subarray(B.bi['upperarm01.L'] * 3, B.bi['upperarm01.L'] * 3 + 3)], el = [...B.head.subarray(B.bi['lowerarm01.L'] * 3, B.bi['lowerarm01.L'] * 3 + 3)], yA = sh[1] - 0.1 * k;
  // the trunk lies inside the shirt, with ease
  let poke = 0, n = 0, ease = 0; for (let v = 0; v < skin.n; v++) { const q = [skin.pos[v * 3], skin.pos[v * 3 + 1], skin.pos[v * 3 + 2]]; if (!(q[1] > belt.hi[1] + 0.03 && q[1] < yA && armW(v) < 0.2 && headW(v) < 0.2)) continue; const c = [0, -0.02], th = Math.atan2(q[2] - c[1], q[0] - c[0]), r = radiusAt(S, q[1], th, c, 0.025, (p) => Math.abs(p[0]) < 0.24), d = r - Math.hypot(q[0] - c[0], q[2] - c[1]); n++; ease += d; if (d < 0.004) poke++; }
  ok(n > 300 && poke === 0, 'the trunk lies inside the shirt everywhere', [n, poke]);
  ok(ease / n > 0.012 && ease / n < 0.06, 'the shirt has ease: it stands a centimetre or more off the body, it is not a second skin', ease / n);
  const chest = radiusAt(S, yA - 0.04, Math.PI / 2, [0, -0.02], 0.02, (p) => Math.abs(p[0]) < 0.1), waist = radiusAt(S, belt.hi[1] + 0.012, Math.PI / 2, [0, -0.02], 0.012, (p) => Math.abs(p[0]) < 0.1), over = radiusAt(S, belt.hi[1] + 0.05, Math.PI / 2, [0, -0.02], 0.012, (p) => Math.abs(p[0]) < 0.1);
  ok(over > waist + 0.006, 'tucked in: a little cloth stands out over the belt', over - waist);
  ok(box(S).lo[1] < belt.hi[1], 'and it goes down into the trousers', box(S).lo[1] - belt.hi[1]);
  // short sleeves: to between the middle of the upper arm and just above the elbow, standing off the arm
  const C = part('cuff'), along = (q) => ((q[0] - sh[0]) * (el[0] - sh[0]) + (q[1] - sh[1]) * (el[1] - sh[1]) + (q[2] - sh[2]) * (el[2] - sh[2])) / ((el[0] - sh[0]) ** 2 + (el[1] - sh[1]) ** 2 + (el[2] - sh[2]) ** 2);
  let end = 0, cr = 0, cn = 0; for (let v = 0; v < C.n; v++) { const q = [C.pos[v * 3], C.pos[v * 3 + 1], C.pos[v * 3 + 2]]; if (q[0] < 0) continue; const u = along(q); end = Math.max(end, u); const ax = [sh[0] + (el[0] - sh[0]) * u, sh[1] + (el[1] - sh[1]) * u, sh[2] + (el[2] - sh[2]) * u]; cr += Math.hypot(q[0] - ax[0], q[1] - ax[1], q[2] - ax[2]); cn++; }
  ok(end > 0.45 && end < 0.8, 'a short sleeve ends between the middle of the upper arm and the elbow', end);
  let ar = 0, an = 0; for (let v = 0; v < skin.n; v++) { const q = [skin.pos[v * 3], skin.pos[v * 3 + 1], skin.pos[v * 3 + 2]]; if (q[0] < 0 || armW(v) < 0.6) continue; const u = along(q); if (Math.abs(u - end) > 0.06) continue; const ax = [sh[0] + (el[0] - sh[0]) * u, sh[1] + (el[1] - sh[1]) * u, sh[2] + (el[2] - sh[2]) * u]; ar += Math.hypot(q[0] - ax[0], q[1] - ax[1], q[2] - ax[2]); an++; }
  ok(cn > 8 && an > 8 && cr / cn > ar / an + 0.014, 'its hem stands off the arm (a centimetre and a half or more all round)', cr / cn - ar / an);
  ok(C.group !== S.group, 'the hem of the sleeve is a band of its own (a drawn line above it)', [C.group, S.group]);
  // seen from outside: the shell faces out
  let outw = 0, tn = 0; for (let t = 0; t < S.idx.length; t += 3) { const [a, b2, c] = [S.idx[t], S.idx[t + 1], S.idx[t + 2]].map((v) => [S.pos[v * 3], S.pos[v * 3 + 1], S.pos[v * 3 + 2]]); const m = [(a[0] + b2[0] + c[0]) / 3, (a[1] + b2[1] + c[1]) / 3, (a[2] + b2[2] + c[2]) / 3]; if (Math.abs(m[0]) > 0.15 || m[1] > yA || m[1] < belt.hi[1] + 0.03) continue; const e1 = [b2[0] - a[0], b2[1] - a[1], b2[2] - a[2]], e2 = [c[0] - a[0], c[1] - a[1], c[2] - a[2]], nx = e1[1] * e2[2] - e1[2] * e2[1], nz = e1[0] * e2[1] - e1[1] * e2[0]; tn++; if (nx * m[0] + nz * (m[2] + 0.02) > 0) outw++; }
  ok(tn > 100 && outw / tn > 0.97, 'the shell is made facing outwards (so light falls on it the right way)', outw / Math.max(1, tn));
}
{ // ---- the pieces a shirt is drawn with
  const S = part('shirt'), Co = part('collar'), bx = box(Co), neckY = B.landmarks.neckY;
  const tip = (sx) => { let best = null; for (let v = 0; v < Co.n; v++) { const q = [Co.pos[v * 3], Co.pos[v * 3 + 1], Co.pos[v * 3 + 2]]; if (q[0] * sx <= 0 || q[2] < 0.03) continue; if (!best || q[1] < best[1]) best = q; } return best; };
  const tl = tip(1), tr = tip(-1);
  ok(!!tl && !!tr && Math.abs(tl[0] + tr[0]) < 0.004 && Math.abs(tl[1] - tr[1]) < 0.004, 'the collar has two points, one each side of the middle, alike', [tl, tr]);
  ok(tl && bx.hi[1] - tl[1] > 0.06 * k && bx.hi[1] - tl[1] < 0.12 * k, 'each point hangs well below the collar\'s top (a pointed collar, not a ring)', tl ? bx.hi[1] - tl[1] : 0);
  ok(tl && tl[0] > 0.025 * k && tl[0] < 0.085 * k, 'the points lie a few centimetres either side of the middle', tl?.[0]);
  ok(bx.hi[1] > neckY - 0.005 && bx.hi[1] < neckY + 0.045, 'the collar stands on a band round the base of the neck', bx.hi[1] - neckY);
  ok(Co.group !== S.group, 'the collar is a piece of its own (its edge is a drawn line)', Co.group);
  const P = part('pocket'), pb = box(P), sh = B.head[B.bi['upperarm01.L'] * 3 + 1];
  ok(pb.lo[0] > 0.03 * k && pb.hi[0] < 0.17 * k, 'the pocket is on the wearer\'s left chest', [pb.lo[0], pb.hi[0]]);
  ok(pb.hi[0] - pb.lo[0] > 0.075 * k && pb.hi[0] - pb.lo[0] < 0.115 * k && pb.hi[1] - pb.lo[1] > 0.085 * k && pb.hi[1] - pb.lo[1] < 0.135 * k, 'about 9 cm wide and 11 high', [pb.hi[0] - pb.lo[0], pb.hi[1] - pb.lo[1]]);
  ok(pb.hi[1] < sh - 0.07 * k && pb.lo[1] > sh - 0.3 * k, 'below the shoulder, on the chest', [sh - pb.hi[1], sh - pb.lo[1]]);
  ok(P.group !== S.group, 'drawn with a line round it', P.group);
  const Pl = part('placket'), plb = box(Pl), belt = box(part('belt'));
  ok(Math.abs(plb.lo[0] + plb.hi[0]) < 0.003 && plb.hi[0] - plb.lo[0] > 0.022 * k && plb.hi[0] - plb.lo[0] < 0.042 * k, 'the placket runs down the middle of the front, about 3 cm wide', [plb.lo[0], plb.hi[0]]);
  ok(plb.lo[1] < belt.hi[1] + 0.02 && plb.hi[1] > neckY - 0.09, 'from the collar to the belt', [plb.lo[1] - belt.hi[1], plb.hi[1] - neckY]);
  const Ti = part('tie'), tb = box(Ti);
  ok(Math.abs(tb.lo[0] + tb.hi[0]) < 0.004 && tb.hi[0] - tb.lo[0] > 0.055 * k && tb.hi[0] - tb.lo[0] < 0.095 * k, 'the tie hangs in the middle, 6 to 9 cm at its widest', tb.hi[0] - tb.lo[0]);
  ok(tb.lo[1] > belt.lo[1] - 0.02 && tb.lo[1] < belt.hi[1] + 0.05, 'its tip reaches the belt', tb.lo[1] - belt.hi[1]);
  const kb = box(part('knot') ?? { n: 0 });
  ok(!!part('knot') && kb.hi[1] > neckY - 0.09 && kb.hi[1] < neckY + 0.01 && Math.abs(kb.lo[1] - tb.hi[1]) < 0.02 && part('knot').group !== Ti.group, 'its knot sits under the collar, a thing of its own above the blade', [kb.hi[1] - neckY, kb.lo[1] - tb.hi[1]]);
  // the head of a sleeve is round the shoulder joint: nothing of it stands above the shoulder like a box
  { const Sl = part('sleeve'), sj = [...B.head.subarray(B.bi['upperarm01.L'] * 3, B.bi['upperarm01.L'] * 3 + 3)]; let far = 0; for (let v = 0; v < Sl.n; v++) { const q = [Sl.pos[v * 3], Sl.pos[v * 3 + 1], Sl.pos[v * 3 + 2]]; if (q[0] < 0 || q[1] < sj[1]) continue; far = Math.max(far, Math.hypot(q[0] - sj[0], q[1] - sj[1], q[2] - sj[2])); }
    ok(far > 0.03 && far < 0.085 * k, 'the head of a sleeve is a dome round the shoulder joint (nothing stands out of the shoulder however the arm turns)', far); }
  ok(!!Ti.bands && Ti.bands.length >= 6, 'with stripes across it', Ti.bands?.length ?? 0);
  // a piece lies ON the shirt, not in it and not floating: a few millimetres proud of the shell
  const proud = (p, f = () => true) => { let lo = 1, hi = -1; for (let v = 0; v < p.n; v++) { const q = [p.pos[v * 3], p.pos[v * 3 + 1], p.pos[v * 3 + 2]]; if (!f(q)) continue; const c = [0, -0.02], th = Math.atan2(q[2] - c[1], q[0] - c[0]), d = Math.hypot(q[0] - c[0], q[2] - c[1]) - radiusAt(S, q[1], th, c, 0.02, (s) => Math.abs(s[0]) < 0.24); lo = Math.min(lo, d); hi = Math.max(hi, d); } return [lo, hi]; };
  const pp = proud(P), pl = proud(Pl, (q) => q[1] < neckY - 0.12), pt = proud(Ti, (q) => q[1] < neckY - 0.14);
  ok(pp[0] > -0.004 && pp[1] < 0.012, 'the pocket lies on the shirt', pp);
  ok(pl[0] > -0.004 && pl[1] < 0.012, 'the placket lies on the shirt', pl);
  ok(pt[0] > -0.002 && pt[1] < 0.03, 'the tie lies on the shirt', pt);
}
{ // ---- shoes
  for (const sx of [1, -1]) {
    const side = (q) => q[0] * sx > 0, So = box(part('sole'), side), U = box(part('shoe'), side), ank = B.head[B.bi['foot.L'] * 3 + 1];
    ok(So.lo[1] > -0.006 && So.lo[1] < 0.008, 'the sole stands on the ground', So.lo[1]);
    ok(So.hi[2] - So.lo[2] > 0.25 * k && So.hi[2] - So.lo[2] < 0.33 * k, 'a shoe is 26 to 32 cm long', So.hi[2] - So.lo[2]);
    ok(So.hi[0] - So.lo[0] > 0.085 * k && So.hi[0] - So.lo[0] < 0.125 * k, 'and about 10 cm wide', So.hi[0] - So.lo[0]);
    ok(U.hi[1] > ank && U.hi[1] < ank + 0.06, 'its upper comes up to the ankle', U.hi[1] - ank);
    ok(U.lo[1] >= So.lo[1] && So.hi[0] - So.lo[0] >= U.hi[0] - U.lo[0] - 0.002, 'the upper stands on its sole (the sole shows as a line all round)', [U.lo[1] - So.lo[1], (So.hi[0] - So.lo[0]) - (U.hi[0] - U.lo[0])]);
  }
  ok(part('sole').group !== part('shoe').group, 'sole and upper are drawn apart', part('sole').group);
}
{ // ---- the skin under the clothes is not drawn (so nothing can come through them when the body moves)
  const sk = W?.skin; let under = 0, kept = 0, hands = 0, neck = 0;
  const idx = sk?.idxCore ?? skin.idx;
  for (let t = 0; t < idx.length; t += 3) { const vs = [idx[t], idx[t + 1], idx[t + 2]], ys = vs.map((v) => skin.pos[v * 3 + 1]), aw = Math.max(...vs.map(armW)), hw = Math.max(...vs.map(headW)); if (aw < 0.1 && hw < 0.1 && Math.max(...ys) < 1.3 * k && Math.min(...ys) > 0.2 * k) under++; else kept++; if (Math.min(...vs.map(armW)) > 0.9 && Math.max(...ys) < 1.15 * k) hands++; if (Math.min(...vs.map(headW)) > 0.5) neck++; }
  ok(!!sk && under === 0, 'skin of the trunk and the legs under the clothes is left out', under);
  ok(hands > 500 && neck > 100, 'hands, forearms, neck and head stay', [hands, neck]);
}
{ // ---- other clothes, other cuts
  const B2 = body(), W2 = dressBody(B2, { wear: { ...WEAR, trousers: { ...WEAR.trousers, fit: 'slim' } } }), T2 = W2.parts.find((p) => p.name === 'trousers'), T = part('trousers');
  const hem = (p) => { const b = box(p, (q) => q[0] > 0 && q[1] < 0.12); return b.hi[2] - b.lo[2]; };
  ok(hem(T2) < hem(T) - 0.015, 'a slim leg is narrower at the hem than a straight one', [hem(T2), hem(T)]);
  const B3 = body(); ok(dressBody(B3, {}) === null && !B3.wear, 'a design without `wear` keeps the Stage\'s clothes', !!B3.wear);
  // a T-shirt: no collar, placket, pocket or buttons — a ribbed band round a wider neck — and it hangs OVER the trousers
  const B4 = body(), W4 = dressBody(B4, { wear: { shirt: { kind: 'tee', color: '#7a2a2a', sleeve: 'short' }, trousers: WEAR.trousers, shoes: WEAR.shoes } }), p4 = (n) => W4.parts.find((q) => q.name === n);
  ok(['collar', 'placket', 'pocket', 'buttons', 'tie', 'knot', 'throat'].every((n) => !p4(n)) && !!p4('rib') && p4('rib').group !== p4('shirt').group, 'a T-shirt has no collar, placket, pocket or buttons: a ribbed band round the neck instead', W4.parts.map((q) => q.name));
  const s4 = box(p4('shirt')), t4 = box(p4('trousers')), neckY4 = B4.landmarks.neckY;
  ok(s4.lo[1] < t4.hi[1] - 0.06 * k && s4.lo[1] > t4.hi[1] - 0.2 * k, 'it is worn out: its hem hangs a hand or more below the waistband', t4.hi[1] - s4.lo[1]);
  { const y = t4.hi[1] - 0.04 * k, c = [0, -0.02]; let under = 0; for (let j = 0; j < 24; j++) { const th = j * Math.PI / 12; if (radiusAt(p4('shirt'), y, th, c, 0.02, (q) => Math.abs(q[0]) < 0.24) < radiusAt(p4('trousers'), y, th, c, 0.02) + 0.002) under++; } ok(under === 0, 'and lies over the trousers all round (never inside them)', under); }
  { const rb = box(p4('rib')), cb = box(part('collar')); ok(rb.hi[1] < neckY4 + 0.012 && rb.hi[0] - rb.lo[0] > (cb.hi[0] - cb.lo[0]) * 0 + 0.13 * k && rb.hi[1] - rb.lo[1] < 0.07 * k, 'its neck is wider and lower than a shirt collar\'s band', [rb.hi[1] - neckY4, rb.hi[0] - rb.lo[0]]); }
}
{ // ---- the lines a drawing of trousers is made with (ink on the cloth, moving with it)
  const L = W?.lines ?? [], cr = L.filter((l) => /^crease/.test(l.name)), hipY = B.landmarks.hipY;
  ok(cr.length === 2, 'a crease runs down the front of each leg', cr.length);
  for (const l of cr) { let y0 = 1e9, y1 = -1e9, back = 0; for (let v = 0; v < l.n; v++) { const y = l.pos[v * 3 + 1]; y0 = Math.min(y0, y); y1 = Math.max(y1, y); if (l.pos[v * 3 + 2] < 0.02) back++; }
    ok(l.n >= 8 && y1 > hipY - 0.32 * k && y0 < 0.12 * k && back === 0, 'from the thigh to the hem, on the front of the leg', [l.n, y1, y0, back]); ok(l.on === 'trousers' && l.sw.length === l.n * 4, 'it belongs to the trousers and hangs on the leg\'s bones', l.on); }
  ok(L.some((l) => l.name === 'fly') && L.filter((l) => /^pocket/.test(l.name)).length === 2, 'the fly and the two side pockets are drawn lines', L.map((l) => l.name));
  // cloth bends where the body does: a few short fold lines at the armpits and over the belt, a break over each shoe
  const folds = L.filter((l) => /^fold/.test(l.name) && l.on === 'shirt'), S = part('shirt'), belt = box(part('belt')), shY = B.head[B.bi['upperarm01.L'] * 3 + 1];
  const yOf = (l) => Array.from({ length: l.n }, (_, v) => l.pos[v * 3 + 1]), lenOf = (l) => { let d = 0; for (let v = 1; v < l.n; v++) d += Math.hypot(l.pos[v * 3] - l.pos[v * 3 - 3], l.pos[v * 3 + 1] - l.pos[v * 3 - 2], l.pos[v * 3 + 2] - l.pos[v * 3 - 1]); return d; };
  const pit = folds.filter((l) => Math.min(...yOf(l)) > shY - 0.26 * k && Math.abs(l.pos[0]) > 0.08 * k), over = folds.filter((l) => Math.max(...yOf(l)) < belt.hi[1] + 0.09 * k);
  ok(pit.length >= 4 && pit.filter((l) => l.pos[0] > 0).length === pit.filter((l) => l.pos[0] < 0).length, 'two or more fold lines run from each armpit towards the chest', pit.length);
  ok(over.length >= 3 && over.every((l) => Math.min(...yOf(l)) > belt.hi[1] - 0.002), 'three or more short ones stand over the belt, where the shirt is gathered', over.length);
  ok(folds.every((l) => lenOf(l) > 0.025 * k && lenOf(l) < 0.12 * k), 'fold lines are short (3 to 12 cm): a few marks, not a net', folds.map((l) => +lenOf(l).toFixed(3)));
  { let off = 0; for (const l of folds) for (let v = 0; v < l.n; v++) { const q = [l.pos[v * 3], l.pos[v * 3 + 1], l.pos[v * 3 + 2]], c = [0, -0.02], th = Math.atan2(q[2] - c[1], q[0] - c[0]); off = Math.max(off, Math.abs(Math.hypot(q[0] - c[0], q[2] - c[1]) - radiusAt(S, q[1], th, c, 0.02, (p) => Math.abs(p[0]) < 0.24))); } ok(off < 0.012, 'they lie on the cloth', off); }
  const br = L.filter((l) => /^break/.test(l.name));
  ok(br.length === 2 && br.every((l) => l.on === 'trousers' && Math.max(...yOf(l)) < 0.2 * k && Array.from({ length: l.n }, (_, v) => l.pos[v * 3 + 2]).every((z) => z > 0)), 'a break: one fold across the front of each leg just above the hem', br.map((l) => l.name));
  // a leather shoe is drawn with its toe cap and its laces
  const sh = L.filter((l) => l.on === 'shoe');
  ok(sh.filter((l) => /^cap/.test(l.name)).length === 2 && sh.filter((l) => /^lace/.test(l.name)).length >= 6, 'each shoe has a line across the toe cap and three laces', sh.map((l) => l.name));
  ok(sh.every((l) => { for (let v = 0; v < l.n; v++) if (l.sw[v * 4] !== 1 || l.si[v * 4] !== B.bi[l.pos[v * 3] > 0 ? 'foot.L' : 'foot.R']) return false; return true; }), 'they ride on the foot, as the shoe does', 0);
}
console.log(fails.length ? `WARDROBE: ${fails.length} of ${checks} rules broken\n  ✗ ${fails.join('\n  ✗ ')}` : `WARDROBE: all ${checks} rules hold`);
process.exit(fails.length ? 1 : 0);
