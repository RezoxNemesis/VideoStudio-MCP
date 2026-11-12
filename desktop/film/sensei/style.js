// Sensei's eye for drawing style: a fingerprint of a picture (values, colour, lines, flat fills vs painted gradients,
// detail) and how far a film's frames are from a reference style — said in plain words with the numbers behind them.
const srgb = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
const LIN = Float32Array.from({ length: 256 }, (_, i) => srgb(i));
export function labOf(r, g, b) {
  const R = LIN[r], G = LIN[g], B = LIN[b];
  const X = (0.4124 * R + 0.3576 * G + 0.1805 * B) / 0.95047, Y = 0.2126 * R + 0.7152 * G + 0.0722 * B, Z = (0.0193 * R + 0.1192 * G + 0.9505 * B) / 1.08883;
  const f = (t) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  return [116 * f(Y) - 16, 500 * (f(X) - f(Y)), 200 * (f(Y) - f(Z))];
}
export const dE = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
/** rgb: Uint8 RGB(A) pixels, w × h, stride 3 or 4. → fingerprint */
export function fingerprint(rgb, w, h, stride = 3) {
  const n = w * h, Y = new Float32Array(n), S = new Float32Array(n);
  let rg = 0, yb = 0, rg2 = 0, yb2 = 0, warm = 0;
  for (let i = 0; i < n; i++) {
    const r = rgb[i * stride], g = rgb[i * stride + 1], b = rgb[i * stride + 2];
    Y[i] = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b); S[i] = mx ? (mx - mn) / mx : 0;
    const a = r - g, c = 0.5 * (r + g) - b; rg += a; yb += c; rg2 += a * a; yb2 += c * c; warm += (r - b) / 255;
  }
  const sorted = Float32Array.from(Y).sort(), pc = (q) => sorted[Math.min(n - 1, Math.floor(q * n))];
  let mean = 0; for (let i = 0; i < n; i++) mean += Y[i]; mean /= n;
  let sd = 0; for (let i = 0; i < n; i++) sd += (Y[i] - mean) ** 2; sd = Math.sqrt(sd / n);
  const hist = new Array(16).fill(0); for (let i = 0; i < n; i++) hist[Math.min(15, Math.floor(Y[i] * 16))]++;
  const sat = Float32Array.from(S).sort();
  const mrg = rg / n, myb = yb / n, colorful = Math.sqrt(Math.max(0, rg2 / n - mrg * mrg) + Math.max(0, yb2 / n - myb * myb)) + 0.3 * Math.hypot(mrg, myb);
  // edges and lines
  let edges = 0, ink = 0, gm = 0, lap = 0;
  for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
    const i = y * w + x;
    const gx = Y[i - w + 1] + 2 * Y[i + 1] + Y[i + w + 1] - Y[i - w - 1] - 2 * Y[i - 1] - Y[i + w - 1];
    const gy = Y[i + w - 1] + 2 * Y[i + w] + Y[i + w + 1] - Y[i - w - 1] - 2 * Y[i - w] - Y[i - w + 1];
    const m = Math.hypot(gx, gy) / 4; gm += m;
    if (m > 0.08) { edges++; if (Y[i] < 0.2) ink++; }
    lap += Math.abs(4 * Y[i] - Y[i - 1] - Y[i + 1] - Y[i - w] - Y[i + w]);
  }
  const inner = (w - 2) * (h - 2);
  // flat fills (no change at all) vs soft painted gradients, in 4×4 blocks
  let flat = 0, soft = 0, blocks = 0;
  for (let by = 0; by + 4 <= h; by += 4) for (let bx = 0; bx + 4 <= w; bx += 4) {
    let s1 = 0, s2 = 0; for (let y = 0; y < 4; y++) for (let x = 0; x < 4; x++) { const v = Y[(by + y) * w + bx + x]; s1 += v; s2 += v * v; }
    const sdv = Math.sqrt(Math.max(0, s2 / 16 - (s1 / 16) ** 2)); blocks++;
    if (sdv < 0.0035) flat++; else if (sdv < 0.03) soft++;
  }
  return {
    w, h, mean: +mean.toFixed(4), sd: +sd.toFixed(4), p5: +pc(0.05).toFixed(4), p95: +pc(0.95).toFixed(4), hist: hist.map((x) => +(x / n).toFixed(4)),
    sat: +(sat.reduce((s, x) => s + x, 0) / n).toFixed(4), sat90: +sat[Math.floor(0.9 * n)].toFixed(4), colorful: +colorful.toFixed(2), warm: +(warm / n).toFixed(4),
    edges: +(edges / inner).toFixed(4), ink: +(ink / inner).toFixed(4), grad: +(gm / inner).toFixed(4), detail: +(lap / inner).toFixed(4),
    flat: +(flat / blocks).toFixed(4), soft: +(soft / blocks).toFixed(4),
  };
}
const KEYS = ['mean', 'sd', 'p5', 'p95', 'sat', 'sat90', 'colorful', 'warm', 'edges', 'ink', 'grad', 'detail', 'flat', 'soft'];
/** the average of several fingerprints */
export function average(fps) {
  const out = {}; for (const k of KEYS) out[k] = +(fps.reduce((s, f) => s + (f[k] ?? 0), 0) / Math.max(1, fps.length)).toFixed(4);
  out.hist = new Array(16).fill(0).map((_, i) => +(fps.reduce((s, f) => s + (f.hist?.[i] ?? 0), 0) / Math.max(1, fps.length)).toFixed(4)); out.n = fps.length; return out;
}
/**
 * How a film's frames (avg fingerprint `me`) differ from a reference style (`ref`). → { score 0…100, gaps: [{ key, me, ref, say, fix }] }
 * Only differences a viewer would see are reported; each comes with what to change.
 */
export function compare(me, ref) {
  const gaps = [];
  const rel = (k) => (me[k] - ref[k]) / Math.max(1e-3, Math.abs(ref[k]));
  const add = (key, cond, sev, say, fix) => { if (cond) gaps.push({ key, me: me[key], ref: ref[key], sev, say, fix }); };
  const pc = (x) => `${Math.round(x * 100)}%`;
  add('flat', me.flat - ref.flat > 0.12, me.flat - ref.flat > 0.25 ? 'should' : 'nice', `${pc(me.flat)} of the picture is perfectly flat colour (the reference: ${pc(ref.flat)}) — it reads as vector art, not painting.`, 'Paint the large areas: sky gradients, soft light falloff on walls and ground, texture on cloth/stone/wood, a second shadow tone and highlights on bodies.');
  add('soft', ref.soft - me.soft > 0.12, 'nice', `Soft painted gradients cover ${pc(me.soft)} of the frame (reference ${pc(ref.soft)}).`, 'Add gradient fills (light to dark across a wall, warm-to-cool sky), soft shadows and glow.');
  add('detail', rel('detail') < -0.35, rel('detail') < -0.55 ? 'should' : 'nice', `Fine detail is ${pc(-rel('detail'))} lower than the reference (detail ${me.detail} vs ${ref.detail}).`, 'Add secondary detail where the eye looks: folds and seams on clothes, trims and patterns, strands in hair, cracks/planks/bricks in the set, small props.');
  add('edges', rel('edges') < -0.35, 'nice', `Fewer drawn edges than the reference (${pc(me.edges)} vs ${pc(ref.edges)} of the picture).`, 'Draw interior lines: cloth folds, muscle and joint lines, hair strand lines, architectural detail.');
  add('ink', rel('ink') < -0.4, 'nice', `Ink lines are lighter or thinner than the reference (dark line pixels ${pc(me.ink)} vs ${pc(ref.ink)}).`, 'Use darker, weight-varied outlines (thicker on the silhouette, thinner inside), tinted by the colour they enclose.');
  add('ink', rel('ink') > 0.8, 'nice', `Much heavier ink than the reference (${pc(me.ink)} vs ${pc(ref.ink)}).`, 'Thin the inner lines and keep the heavy weight for silhouettes only.');
  const range = me.p95 - me.p5, rr = ref.p95 - ref.p5;
  add('p5', rr - range > 0.15, rr - range > 0.28 ? 'should' : 'nice', `The value range is narrow (${me.p5.toFixed(2)}–${me.p95.toFixed(2)}; reference ${ref.p5.toFixed(2)}–${ref.p95.toFixed(2)}): shadows not deep enough or lights not bright enough.`, 'Push darker shadow tones on forms and in the set, and brighter key-light highlights; keep the sky lighter than the ground.');
  add('colorful', rel('colorful') < -0.3, 'nice', `Less colourful than the reference (${me.colorful} vs ${ref.colorful}).`, 'Saturate the accents (cloth, sky, lights) and add a colour key per scene (warm dusk, cool night).');
  add('colorful', rel('colorful') > 0.6, 'nice', `More garish than the reference (${me.colorful} vs ${ref.colorful}).`, 'Lower saturation of large areas and keep strong colour for accents.');
  add('warm', Math.abs(me.warm - ref.warm) > 0.12, 'nice', `The colour temperature differs (${me.warm > ref.warm ? 'warmer' : 'cooler'} than the reference).`, 'Grade the scene toward the reference temperature (a colour wash or the set\'s time of day).');
  add('mean', Math.abs(me.mean - ref.mean) > 0.15, 'nice', `Overall ${me.mean > ref.mean ? 'brighter' : 'darker'} than the reference (mean value ${me.mean} vs ${ref.mean}).`, me.mean > ref.mean ? 'Lower the ambient light / add more shadow area.' : 'Lift the ambient light or the exposure.');
  // a score from the distance over the main measures
  const keys = ['flat', 'soft', 'detail', 'edges', 'ink', 'colorful', 'sd', 'mean', 'sat'];
  let d = 0; for (const k of keys) d += Math.min(1, Math.abs(rel(k))) ** 2; d = Math.sqrt(d / keys.length);
  return { score: Math.round(100 * Math.max(0, 1 - d)), gaps };
}
