// The cast at the drawn standard: node devtests/cast.mjs        (exit 1 when a rule is broken)
// A person of the cast is made of library parts — a bare Stage body, clothes cut by the engine (design.wear), a head of
// hair that is a mass with designed locks, a designed beard — so every one comes out at the same standard and a part
// made better later makes them all better. The first is the father of the Papa film (the owner's standard, 16 Nov 2026).
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import fs from 'node:fs';
import { father, layeredHair, OUTFITS, TROUPE } from '../kit/cast.js';
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
ok(TROUPE.father === father && JSON.stringify(father().design.wear) === JSON.stringify(father().design.wear), 'the cast lists him; he is the same every time', 0);
ok(father({ young: true }).design.face.beard == null && father({ young: true }).design.wear.shirt.collar.open === false, 'the young father (a memory) has no beard and a closed collar', 0);
// ---- a person made of parts: person({ … }) — any man of the cast is a recipe
{ const { man: person, BUILDS, HAIR_STYLES, BEARDS } = await import('../kit/cast.js');
  ok(Object.keys(BUILDS).length >= 5 && Object.keys(HAIR_STYLES).length >= 5 && Object.keys(BEARDS).length >= 5 && Object.keys(OUTFITS).length >= 3, 'the parts: five or more builds, hair styles and beards, three or more outfits', [Object.keys(BUILDS), Object.keys(HAIR_STYLES), Object.keys(BEARDS), Object.keys(OUTFITS)]);
  const a = person({ name: 'A', age: 24, build: 'slim', hair: 'short', beard: 'none', outfit: 'tee' }), b = person({ name: 'B', age: 52, build: 'heavy', hair: 'neat', beard: 'moustache', outfit: 'office', hairColor: 'grey' });
  ok(a.body.garments.length === 0 && b.body.garments.length === 0 && a.body.age === 24 && b.body.age === 52 && b.body.weight > a.body.weight + 0.3, 'a recipe sets the body: age and build (always bare)', [a.body.weight, b.body.weight]);
  ok(a.design.wear.shirt.kind === 'tee' && !a.design.wear.tie && b.design.wear.tie && !a.design.face.beard && b.design.face.beard.style === 'mustache', 'the outfit and the beard', [a.design.wear.shirt.kind, b.design.face.beard?.style]);
  ok(a.design.hair3.mass.vol < father().design.hair3.mass.vol && a.design.hair.color !== b.design.hair.color, 'the hair: its style and its colour', [a.design.hair3.mass.vol, a.design.hair.color, b.design.hair.color]);
  ok(JSON.stringify(person({ name: 'A', age: 24, build: 'slim', hair: 'short', beard: 'none', outfit: 'tee' }).design.wear) === JSON.stringify(a.design.wear), 'the same recipe gives the same person', 0);
  let threw = ''; try { person({ build: 'giant' }); } catch (e) { threw = e.message; }
  ok(/giant/.test(threw) && /slim/.test(threw), 'a part the library does not have is named, with the ones it has', threw);
  for (const st of Object.keys(HAIR_STYLES)) { const h = HAIR_STYLES[st](); ok(h.n === 0 && h.mass && Array.isArray(h.locks), `hair style "${st}" is a mass with designed locks`, st); } }
// ---- faces are made of parts too: a shape, eyes, brows, a nose, a mouth
{ const C = await import('../kit/cast.js'), { man, FACES, EYES, BROWS, NOSES, MOUTHS, MEN } = C;
  ok(Object.keys(FACES ?? {}).length >= 7 && Object.keys(EYES ?? {}).length >= 6 && Object.keys(BROWS ?? {}).length >= 5 && Object.keys(NOSES ?? {}).length >= 6 && Object.keys(MOUTHS ?? {}).length >= 5, 'the parts of a face: seven shapes, six kinds of eyes, five of brows, six noses, five mouths (or more)', [Object.keys(FACES ?? {}), Object.keys(EYES ?? {}), Object.keys(BROWS ?? {}), Object.keys(NOSES ?? {}), Object.keys(MOUTHS ?? {})]);
  const sq = man({ face: 'square', eyes: 'round', brows: 'heavy', nose: 'hooked', mouth: 'thin' }), ov = man({ face: 'oval', eyes: 'narrow', brows: 'thin', nose: 'snub', mouth: 'full' });
  ok(sq.body.detail['head/head-square'] > 0.3 && !(ov.body.detail['head/head-square'] > 0) && ov.body.detail['head/head-oval'] > 0.3, 'the shape of the face sculpts the head (the Stage\'s own shapes)', [sq.body.detail['head/head-square'], ov.body.detail['head/head-oval']]);
  ok(sq.body.detail['nose/nose-hump-incr'] > 0.3 && ov.body.detail['nose/nose-point-up'] > 0.3, 'the nose too', [sq.body.detail['nose/nose-hump-incr'], ov.body.detail['nose/nose-point-up']]);
  ok(sq.design.face.eye.h > ov.design.face.eye.h + 0.02 && sq.design.face.brow.thick > ov.design.face.brow.thick + 0.15 && ov.design.face.lips > sq.design.face.lips + 0.15, 'eyes, brows and lips are drawn by their kind', [sq.design.face.eye.h, ov.design.face.eye.h, sq.design.face.brow.thick, ov.design.face.brow.thick]);
  ok(man({ eyeColor: '#2e4a3a' }).design.face.eye.color === '#2e4a3a', 'with the colour of the eyes', 0);
  let threw = ''; try { man({ nose: 'trunk' }); } catch (e) { threw = e.message; } ok(/trunk/.test(threw) && /straight/.test(threw), 'a nose the library does not have is named, with the ones it has', threw);
  const sig = Object.values(MEN).map((r) => [r.face, r.eyes, r.brows, r.nose, r.mouth].join('/'));
  ok(sig.every((q) => !/undefined/.test(q)) && new Set(sig).size === sig.length && new Set(Object.values(MEN).map((r) => r.face)).size >= 6, 'every man of the library has a face of his own (no two the same; six or more shapes among them)', sig); }
{ const names = Object.keys(TROUPE), made = names.map((n) => TROUPE[n]());
  ok(names.length >= 8 && made.every((q) => q.body.garments.length === 0 && q.design.wear?.shirt && q.design.wear?.trousers && q.design.hair3?.mass), 'the cast has eight or more people, every one a bare body in engine-cut clothes with a head of hair', names);
  ok(new Set(made.map((q) => JSON.stringify([q.body.age, q.body.weight, q.design.hair.color, q.design.wear.shirt.color, q.design.face.beard?.style ?? '-']))).size === names.length, 'no two alike', 0);
  ok(TROUPE.ravi({ outfit: 'tee' }).design.wear.shirt.kind === 'tee' && TROUPE.ravi().design.wear.shirt.kind !== 'tee', 'one of the cast in other clothes: TROUPE.ravi({ outfit: "tee" })', 0); }
// ---- the kit is one namespace (a film imports everything from vs.js): no two of its modules may export the same name
{ const dir = new URL('../kit/', import.meta.url), idx = fs.readFileSync(new URL('index.js', dir), 'utf8'), mods = [...idx.matchAll(/export \* from '\.\/([a-z0-9]+)\.js'/g)].map((m) => m[1]), names = {};
  for (const m of mods) { const M = await import(new URL(m + '.js', dir)); for (const k of Object.keys(M)) (names[k] ??= []).push(m); }
  const twice = Object.entries(names).filter(([, v]) => v.length > 1).map(([k, v]) => `${k}: ${v.join(', ')}`);
  ok(twice.length === 0, 'no name is exported by two modules of the kit (it would vanish from vs.js)', twice);
  const K = await import(new URL('index.js', dir)); ok(typeof K.man === 'function' && typeof K.father === 'function' && typeof K.TROUPE === 'object' && typeof K.layeredHair === 'function', 'a film can import man, father, TROUPE and layeredHair from the kit', [typeof K.man, typeof K.TROUPE]); }
console.log(fails.length ? `CAST: ${fails.length} of ${checks} rules broken\n  ✗ ${fails.join('\n  ✗ ')}` : `CAST: all ${checks} rules hold`);
process.exit(fails.length ? 1 : 0);
