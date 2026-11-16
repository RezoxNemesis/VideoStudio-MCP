// Clothes that are cut, not painted on: node devtests/tailor.mjs        (exit 1 when a rule is broken)
// The body's own trousers follow every curve of the leg — thigh, knee, calf — like leggings. Trousers hang: from the
// widest of the thigh the cloth falls nearly straight to a hem far wider than the ankle. `design.bottom.fit` re-cuts
// them ('straight' | 'slim' | 'loose'); without it they stay as they are. (The father of the Papa film, 16 Nov 2026.)
import { tailorBody } from '../engine/tailor.js';
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
// a body's legs in their trousers: rings of 12 round each leg from hip to ankle, hugging the leg
const SHAPE = [[0, 0.095], [0.12, 0.088], [0.3, 0.075], [0.5, 0.055], [0.62, 0.06], [0.8, 0.05], [0.97, 0.04]];
const radAt = (u) => { for (let i = 0; i < SHAPE.length - 1; i++) if (u <= SHAPE[i + 1][0]) { const [u0, r0] = SHAPE[i], [u1, r1] = SHAPE[i + 1]; return r0 + (r1 - r0) * (u - u0) / (u1 - u0); } return 0.04; };
function body(k = 1) {
  const hipL = [0.1 * k, 0.95 * k, 0], ankL = [0.11 * k, 0.08 * k, 0.01 * k], pos = [], tag = []; // (the legs of a real body's trousers meet between the legs, they do not run through each other)
  for (const sx of [1, -1]) for (let i = 0; i <= 14; i++) { const u = (i / 14) * 0.97, c = [sx * (hipL[0] + (ankL[0] - hipL[0]) * u), hipL[1] + (ankL[1] - hipL[1]) * u, hipL[2] + (ankL[2] - hipL[2]) * u]; for (let a = 0; a < 12; a++) { const th = (a / 12) * Math.PI * 2, r = radAt(u) * k; pos.push(c[0] + Math.cos(th) * r, c[1], c[2] + Math.sin(th) * r); tag.push({ sx, u, th }); } }
  for (let a = 0; a < 12; a++) { const th = (a / 12) * Math.PI * 2; pos.push(Math.cos(th) * 0.17 * k, 1.05 * k, Math.sin(th) * 0.11 * k); tag.push({ waist: true }); } // the waistband
  const head = new Float32Array(7 * 3); const set = (b, p) => head.set(p, b * 3);
  set(0, [0, 0.98 * k, 0]); set(1, hipL); set(2, [0.105 * k, 0.52 * k, 0.005 * k]); set(3, ankL); set(4, [-hipL[0], hipL[1], hipL[2]]); set(5, [-0.105 * k, 0.52 * k, 0.005 * k]); set(6, [-ankL[0], ankL[1], ankL[2]]);
  return { B: { height: 1.8 * k, head, bi: { root: 0, 'upperleg01.L': 1, 'lowerleg01.L': 2, 'foot.L': 3, 'upperleg01.R': 4, 'lowerleg01.R': 5, 'foot.R': 6 }, parts: [{ name: 'trousers', material: 'cloth', n: pos.length / 3, pos: Float32Array.from(pos) }, { name: 'skin', material: 'skin', n: 1, pos: Float32Array.from([0, 1, 0]) }] }, tag, hipL, ankL };
}
const radius = (T, i, k = 1) => { const { tag, hipL, ankL, B } = T, t = tag[i], P = B.parts[0].pos, u = t.u, c = [t.sx * (hipL[0] + (ankL[0] - hipL[0]) * u), 0, hipL[2] + (ankL[2] - hipL[2]) * u]; return Math.hypot(P[i * 3] - c[0], P[i * 3 + 2] - c[2]) / k; };
const ring = (T, u, f) => T.tag.map((t, i) => (!t.waist && Math.abs(t.u - u) < 0.036 ? f(i) : null)).filter((v) => v !== null);

{ // ---- 'straight'
  const T = body(), before = Float32Array.from(T.B.parts[0].pos);
  tailorBody(T.B, { bottom: { fit: 'straight' } });
  const P = T.B.parts[0].pos, knee = ring(T, 0.5, (i) => radius(T, i)), hem = ring(T, 0.97, (i) => radius(T, i)), hip = ring(T, 0, (i) => Math.hypot(P[i * 3] - before[i * 3], P[i * 3 + 1] - before[i * 3 + 1], P[i * 3 + 2] - before[i * 3 + 2]));
  ok(Math.min(...knee) > 0.07, 'at the knee the cloth stands well off the leg (5.5 cm): it hangs from the thigh, it does not follow the knee in', Math.min(...knee));
  ok(Math.min(...hem) > 0.066 && Math.max(...hem) < 0.09, 'the hem is far wider than the ankle (4 cm), as a trouser leg is', [Math.min(...hem), Math.max(...hem)]);
  ok(Math.max(...hip) < 0.004, 'at the hip nothing is moved (the seat keeps its cut)', Math.max(...hip));
  let waist = 0; T.tag.forEach((t, i) => { if (t.waist) waist = Math.max(waist, Math.abs(P[i * 3] - before[i * 3]) + Math.abs(P[i * 3 + 2] - before[i * 3 + 2])); });
  ok(waist === 0, 'nor at the waistband', waist);
  // from the widest of the thigh to the hem the outer line is nearly straight: no knee, no calf
  const outer = (u) => Math.max(...ring(T, u, (i) => radius(T, i))), r0 = outer(0.3), r1 = outer(0.97); let bend = 0; for (const u of [0.42, 0.5, 0.62, 0.7, 0.8]) bend = Math.max(bend, Math.abs(outer(u) - (r0 + (r1 - r0) * (u - 0.3) / 0.67)));
  ok(bend < 0.012, 'the leg of the trousers falls in a nearly straight line from thigh to hem', bend);
  let cross = 1; T.tag.forEach((t, i) => { if (!t.waist) cross = Math.min(cross, t.sx * P[i * 3]); });
  ok(cross > 0.004, 'the two legs never run into each other between the legs', cross);
  let ys = 0; for (let i = 0; i < P.length / 3; i++) ys = Math.max(ys, Math.abs(P[i * 3 + 1] - before[i * 3 + 1]));
  ok(ys < 0.012, 'cloth is moved outwards, not up or down the leg', ys);
  const once = Float32Array.from(P); tailorBody(T.B, { bottom: { fit: 'straight' } }); let d = 0; for (let i = 0; i < P.length; i++) d = Math.max(d, Math.abs(P[i] - once[i]));
  ok(d === 0, 'cutting them again changes nothing', d);
  tailorBody(T.B, { bottom: { fit: 'loose' } }); const loose = Math.max(...ring(T, 0.5, (i) => radius(T, i)));
  ok(loose > Math.max(...knee) + 0.008, 'loose trousers are wider than straight ones', [loose, Math.max(...knee)]);
  tailorBody(T.B, { bottom: { fit: 'slim' } }); const slim = Math.min(...ring(T, 0.97, (i) => radius(T, i)));
  ok(slim < Math.min(...hem) - 0.008 && slim > 0.05, 'slim ones narrower, and still not leggings', [slim, Math.min(...hem)]);
  tailorBody(T.B, {}); let back = 0; for (let i = 0; i < P.length; i++) back = Math.max(back, Math.abs(P[i] - before[i]));
  ok(back === 0, 'without a fit they are the body\'s own again, exactly', back);
  ok(T.B.parts[1].pos[1] === 1, 'skin is never touched', T.B.parts[1].pos[1]);
}
{ // ---- a child's trousers are the same cut at a child's size
  const T = body(0.5); tailorBody(T.B, { bottom: { fit: 'straight' } }); const A = body(); tailorBody(A.B, { bottom: { fit: 'straight' } });
  const a = Math.min(...ring(A, 0.5, (i) => radius(A, i))), c = Math.min(...ring(T, 0.5, (i) => radius(T, i, 0.5)));
  ok(Math.abs(a - c) < 0.002, 'half the body, half the trousers', [a, c]);
}
if (fails.length) { console.error(`tailor: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`tailor: all ${checks} rules hold`);
