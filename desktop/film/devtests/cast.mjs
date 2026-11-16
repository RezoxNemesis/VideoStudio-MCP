// The cast at the drawn standard: node devtests/cast.mjs        (exit 1 when a rule is broken)
// A person of the cast is made of library parts — a bare Stage body, clothes cut by the engine (design.wear), a head of
// hair that is a mass with designed locks, a designed beard — so every one comes out at the same standard and a part
// made better later makes them all better. The first is the father of the Papa film (the owner's standard, 16 Nov 2026).
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import fs from 'node:fs';
import { father, layeredHair, OUTFITS, CAST } from '../kit/cast.js';
import { dressBody } from '../engine/wardrobe.js';
import { makeHair } from '../engine/hair.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const P = father();
ok(P.body && Array.isArray(P.body.garments) && P.body.garments.length === 0 && !P.body.outfit, 'a person of the cast has a BARE Stage body (garments: []): the engine cuts the clothes', P.body?.garments);
ok(P.body.body === 'man' && P.body.height > 1.6 && P.body.height < 1.9 && P.body.age > 30, 'the father is a grown man', [P.body.body, P.body.height, P.body.age]);
const d = P.design, w = d.wear;
ok(!!w && ['shirt', 'tie', 'trousers', 'belt', 'shoes'].every((k) => w[k]), 'he wears shirt, tie, trousers, belt and shoes cut by the engine', Object.keys(w ?? {}));
const lum = (h) => { const n = parseInt(h.slice(1), 16); return 0.3 * (n >> 16 & 255) + 0.59 * (n >> 8 & 255) + 0.11 * (n & 255); };
ok(lum(w.shoes.color) > 36 && lum(w.belt.color) > 30, '"black" leather is a dark grey (or its form and its lines are lost)', [lum(w.shoes.color), lum(w.belt.color)]);
ok(d.hair3 && d.hair3.n === 0 && d.hair3.mass && d.hair3.locks.length >= 16 && d.hair3.locks.length <= 40, 'his hair is a mass with 16 to 40 designed locks', d.hair3?.locks?.length);
ok(d.hair3.locks.filter((l) => l.broad > 0.5).length >= 14, 'most of them broad (drawn locks, not whips)', d.hair3.locks.filter((l) => l.broad > 0.5).length);
ok(d.face?.beard && d.face.beard.style === 'full' && d.face.beard.density >= 0.85 && d.face.beard.rough <= 0.3, 'his beard is a dark mass with a fine edge', d.face?.beard);
ok(d.inkScale <= 0.7, 'drawn with a fine line', d.inkScale);
// the parts are real: the clothes can be cut for a real body, the hair grown on a real head
{ const J = JSON.parse(gunzipSync(readFileSync(new URL('./fixtures/body_man.json.gz', import.meta.url)))), buf = (s) => { const b = Buffer.from(s, 'base64'); return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength); }, sk = J.skin, idx = new Uint32Array(buf(sk.idx)), bi = {}; J.names.forEach((n, i) => { bi[n] = i; });
  const B = { height: J.height, names: J.names, bi, nb: J.names.length, parent: Int16Array.from(J.parent), head: new Float32Array(buf(J.head)), tail: new Float32Array(buf(J.tail)), landmarks: J.landmarks, parts: [{ name: 'skin', material: 'skin', n: sk.n, pos: new Float32Array(buf(sk.pos)), idx, si: new Uint16Array(buf(sk.si)), sw: new Float32Array(buf(sk.sw)), idxCore: idx, idxLimb: new Uint32Array(0) }] };
  const W = dressBody(B, d); ok(!!W && ['trousers', 'shirt', 'collar', 'tie', 'knot', 'belt', 'buckle', 'shoe', 'sole'].every((n) => W.parts.some((p) => p.name === n)), 'his clothes are cut for a real body', W?.parts.map((p) => p.name)); }
{ const FX = JSON.parse(fs.readFileSync(new URL('./fixtures/head_mesh.json', import.meta.url), 'utf8')), sp = Float32Array.from(FX.skin.pos), cp = Float32Array.from(FX.cap.pos);
  const B = { parts: [{ name: 'haircap', material: 'hair', pos: cp, rest0: cp, idx: Uint32Array.from(FX.cap.idx), n: cp.length / 3 }, { name: 'skin', material: 'skin', pos: sp, rest0: sp, idxHead: Uint32Array.from(FX.skin.idx), n: sp.length / 3 }], landmarks: FX.landmarks, bi: { head: 0 }, R: Float32Array.from([1, 0, 0, 0, 1, 0, 0, 0, 1]), T: new Float32Array(3) };
  const H = makeHair(B, d.hair3, 3); ok(!!H?.mass && H.clumps.length === d.hair3.locks.length && H.clumps.every((c) => c.lock), 'his hair grows on a real head: the mass and every designed lock', H?.clumps?.length);
  const short = layeredHair({ fringe: false, cols: [30, 75, 120, 165], vol: 0.012 }), long = layeredHair({ vol: 0.03 });
  ok(short.locks.length < long.locks.length && short.mass.vol < long.mass.vol, 'layeredHair makes other heads of hair from the same rule (fewer columns, less body, no fringe)', [short.locks.length, long.locks.length]);
  const side = (l) => Math.sign(l.to[0]); ok(Math.abs(long.locks.filter((l) => side(l) > 0).length - long.locks.filter((l) => side(l) < 0).length) <= 3, 'with locks on both sides of the head', 0); }
// outfits are made of the same pieces: other colours, with or without a tie
{ const a = OUTFITS.office(), b = OUTFITS.office({ shirt: '#f2efe6', tie: null, trousers: '#3a3a3e', fit: 'slim' });
  ok(a.tie && !b.tie && b.shirt.color === '#f2efe6' && b.trousers.fit === 'slim' && b.shirt.collar.open === true, 'the office outfit in other colours, without a tie, in a slim cut', [!!a.tie, !!b.tie, b.trousers.fit]); }
ok(CAST.father === father && JSON.stringify(father().design.wear) === JSON.stringify(father().design.wear), 'the cast lists him; he is the same every time', 0);
ok(father({ young: true }).design.face.beard == null && father({ young: true }).design.wear.shirt.collar.open === false, 'the young father (a memory) has no beard and a closed collar', 0);
console.log(fails.length ? `CAST: ${fails.length} of ${checks} rules broken\n  ✗ ${fails.join('\n  ✗ ')}` : `CAST: all ${checks} rules hold`);
process.exit(fails.length ? 1 : 0);
