// Land and sky painted pixel by pixel: node devtests/paintland.mjs        (exit 1 when a rule is broken)
// A sky whose light comes from where the sun stands, with clouds that have a lit and a shaded side; ground whose
// colour varies like grown land and holds to the world (it does not swim when the camera moves, nor boil when it
// stands); far ridges that pale into the air.
import { createCanvas } from '@napi-rs/canvas';
import { makeCam } from '../engine/camera.js';
import { Scene } from '../engine/scene.js';
import { setLook } from '../engine/look.js';
import { skyPaint, landPaint, ridgePaint, mistBand, grassPaint } from '../engine/paintland.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(3) : JSON.stringify(got)}`); };
setLook('anime');
const W = 480, H = 270, lum = (c) => c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11, dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const SUN = [0, 0.14, -0.99]; // low over the −z horizon
const scene = () => new Scene({ sun: SUN, fog: { color: '#d9c9c4', dist: 220, max: 0.92 } });
/** draw one item for a camera, return a reader of its pixels */
function shot(item, camO, t = 0, sc = scene()) {
  const cam = makeCam(camO, W, H), cv = createCanvas(W, H), g = cv.getContext('2d');
  item.draw(g, cam, sc, t);
  const d = g.getImageData(0, 0, W, H).data;
  const px = (x, y) => { const i = (Math.round(y) * W + Math.round(x)) * 4; return [d[i], d[i + 1], d[i + 2], d[i + 3]]; };
  const mean = (x0, y0, x1, y1) => { const s = [0, 0, 0]; let n = 0; for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) { const c = px(x, y); s[0] += c[0]; s[1] += c[1]; s[2] += c[2]; n++; } return s.map((v) => v / n); };
  const sd = (x0, y0, x1, y1) => { const L = []; for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) L.push(lum(px(x, y))); const m = L.reduce((a, b) => a + b, 0) / L.length; return Math.sqrt(L.reduce((a, b) => a + (b - m) ** 2, 0) / L.length); };
  return { cam, d, px, mean, sd };
}
const diff = (a, b) => { let s = 0; for (let i = 0; i < a.d.length; i += 4) s += Math.abs(a.d[i] - b.d[i]) + Math.abs(a.d[i + 1] - b.d[i + 1]) + Math.abs(a.d[i + 2] - b.d[i + 2]); return s / (a.d.length / 4) / 3; };

// ---- 1. the sky
{
  const SKY = { zenith: '#232a4a', mid: '#7a5a7c', horizon: '#f0a868', away: '#8f8aa6', sun: { dir: SUN, color: '#fff0c8', glow: '#ffb060' } };
  const toSun = shot(skyPaint(SKY), { pos: [0, 1.6, 0], target: [0, 6, -30], fov: 40 }), away = shot(skyPaint(SKY), { pos: [0, 1.6, 0], target: [30, 6, 0], fov: 40 });
  ok(toSun.px(W / 2, H / 2)[3] === 255, 'the sky fills the picture', toSun.px(W / 2, H / 2));
  ok(lum(toSun.mean(200, 150, 280, 200)) > lum(away.mean(200, 150, 280, 200)) + 25, 'the sky is brighter towards the sun than at the same height a quarter turn away', [lum(toSun.mean(200, 150, 280, 200)), lum(away.mean(200, 150, 280, 200))]);
  const up = shot(skyPaint(SKY), { pos: [0, 1.6, 0], target: [30, 40, 0], fov: 40 });
  ok(lum(up.mean(200, 20, 280, 60)) < lum(away.mean(200, 200, 280, 240)) - 20, 'high up the sky is darker than at the horizon (dawn)', [lum(up.mean(200, 20, 280, 60)), lum(away.mean(200, 200, 280, 240))]);
  const m = away.mean(200, 180, 280, 230);
  ok(m[2] > m[0] - 30, 'away from the sun the horizon keeps the cool of the night', m);
  // clouds: a lit side and a shaded side, drifting without flicker
  const CL = { ...SKY, clouds: { cover: 0.5, scale: 900, height: 1500, color: '#ffd9b0', shade: '#5a4a6a', wind: [6, 2], seed: 3 } };
  const clear = shot(skyPaint(SKY), { pos: [0, 1.6, 0], target: [30, 14, -10], fov: 50 }), cloudy = shot(skyPaint(CL), { pos: [0, 1.6, 0], target: [30, 14, -10], fov: 50 });
  ok(cloudy.sd(40, 20, 440, 200) > clear.sd(40, 20, 440, 200) + 6, 'clouds break up the sky', [clear.sd(40, 20, 440, 200), cloudy.sd(40, 20, 440, 200)]);
  const again = shot(skyPaint(CL), { pos: [0, 1.6, 0], target: [30, 14, -10], fov: 50 }), next = shot(skyPaint(CL), { pos: [0, 1.6, 0], target: [30, 14, -10], fov: 50 }, 1 / 24), later = shot(skyPaint(CL), { pos: [0, 1.6, 0], target: [30, 14, -10], fov: 50 }, 20);
  ok(diff(cloudy, again) === 0, 'the same moment is painted the same', diff(cloudy, again));
  ok(diff(cloudy, next) < 0.6, 'from one frame to the next the clouds barely move (no flicker)', diff(cloudy, next));
  ok(diff(cloudy, later) > 1.5, 'over twenty seconds they have drifted', diff(cloudy, later));
}

// ---- 2. the land
{
  const LAND = { colors: ['#4a5230', '#5d6238', '#3c4630', '#6e6a40'], earth: '#5a4634', sun: { dir: SUN, color: '#ffc890' } };
  const camA = { pos: [0, 1.6, 0], target: [2, 0, -10], fov: 40 };
  const a = shot(landPaint(LAND), camA);
  ok(a.sd(40, 190, 440, 265) > 4, 'near ground is not one flat tone: it varies like grown land', a.sd(40, 190, 440, 265));
  const hz = Math.round(a.cam.project([0, 0, -4000])[1]);
  ok(dist(a.mean(100, hz + 1, 380, hz + 2), [0xd9, 0xc9, 0xc4]) < 50, 'towards the horizon the ground takes the colour of the air', a.mean(100, hz + 1, 380, hz + 2));
  const misty = shot(landPaint({ ...LAND, mist: { color: '#d9c9c4', dist: 120, amt: 1 } }), camA);
  ok(dist(misty.mean(100, hz + 1, 380, hz + 2), [0xd9, 0xc9, 0xc4]) < 12, 'with mist lying on it, far land is lost in the mist', misty.mean(100, hz + 1, 380, hz + 2));
  ok(dist(misty.mean(100, 250, 380, 265), a.mean(100, 250, 380, 265)) < 14, 'and the ground at the feet stays clear', [misty.mean(100, 250, 380, 265), a.mean(100, 250, 380, 265)]);
  ok(dist(a.mean(100, hz + 1, 380, hz + 2), [0xd9, 0xc9, 0xc4]) < dist(a.mean(100, 240, 380, 265), [0xd9, 0xc9, 0xc4]) - 40, 'and near the lens it has its own', [a.mean(100, hz + 1, 380, hz + 2), a.mean(100, 240, 380, 265)]);
  ok(a.px(W / 2, hz - 12)[3] === 0, 'above the horizon the land paints nothing', a.px(W / 2, hz - 12));
  // the paint holds to the world: the same spot of ground has the same colour from another place
  const P = [2, 0, -10], Q = [-3, 0, -14];
  const b = shot(landPaint(LAND), { pos: [5, 1.6, -1], target: P, fov: 40 });
  const at = (s, p) => { const q = s.cam.project(p); return s.mean(Math.round(q[0]) - 2, Math.round(q[1]) - 1, Math.round(q[0]) + 3, Math.round(q[1]) + 2); };
  ok(dist(at(a, P), at(b, P)) < 16, 'a spot of ground keeps its colour when the camera stands elsewhere (the paint does not swim)', [at(a, P), at(b, P)]);
  ok(dist(at(a, P), at(a, Q)) > 2 || dist(at(b, P), at(b, Q)) > 2, 'and another spot has its own', [at(a, P), at(a, Q)]);
  const a2 = shot(landPaint(LAND), camA, 7);
  ok(diff(a, a2) === 0, 'under a still camera the ground does not boil', diff(a, a2));
  // low light lies on the land towards the sun
  // (the same stretch of ground, seen from either side)
  const sunward = shot(landPaint(LAND), { pos: [0, 1.6, 12], target: [0, 0, 0], fov: 40 }), back = shot(landPaint(LAND), { pos: [0, 1.6, -12], target: [0, 0, 0], fov: 40 });
  ok(lum(sunward.mean(200, 120, 280, 150)) > lum(back.mean(200, 120, 280, 150)) + 5, 'seen towards a low sun the land shines with its light', [lum(sunward.mean(200, 120, 280, 150)), lum(back.mean(200, 120, 280, 150))]);
}

// ---- 3. far ridges
{
  const R = { layers: [{ dist: 900, h: 160, color: '#3a3f55', seed: 2 }, { dist: 4000, h: 900, color: '#3a3f55', seed: 5 }] }, HAZE = [0xd9, 0xc9, 0xc4];
  const near = shot(ridgePaint({ layers: [R.layers[0]] }), { pos: [0, 1.6, 0], target: [40, 8, 0], fov: 50 }), far = shot(ridgePaint({ layers: [R.layers[1]] }), { pos: [0, 1.6, 0], target: [40, 8, 0], fov: 50 });
  const body = (s) => { const out = [0, 0, 0]; let n = 0; for (let y = 0; y < H; y++) for (let x = 0; x < W; x += 3) { const c = s.px(x, y); if (c[3] > 250) { out[0] += c[0]; out[1] += c[1]; out[2] += c[2]; n++; } } return n ? out.map((v) => v / n) : null; };
  ok(!!body(near) && !!body(far), 'ridges stand on the horizon', [body(near), body(far)]);
  ok(body(near) && body(far) && dist(body(far), HAZE) < dist(body(near), HAZE) - 10, 'the farther ridge is paler: more air lies before it', [body(near), body(far)]);
  ok(near.px(W / 2, 4)[3] === 0, 'above the ridges the sky is left as it is', near.px(W / 2, 4));
  const cols = []; for (let x = 0; x < W; x += 4) { let y = 0; while (y < H && near.px(x, y)[3] < 128) y++; cols.push(y); }
  ok(Math.max(...cols) - Math.min(...cols) > 10, 'a ridge has a line of its own: crests and saddles', Math.max(...cols) - Math.min(...cols));
}

// ---- 4. a ridge is a body: crest above crest, mist in its valleys, a gap where a gorge cuts it
{
  const HAZE = [0xd9, 0xc9, 0xc4], camO = { pos: [0, 1.6, 0], target: [40, 6, 0], fov: 50 };
  const one = shot(ridgePaint({ layers: [{ dist: 1500, h: 420, color: '#30364a', seed: 4 }] }), camO);
  // down one column of the picture: from its crest to its foot
  const column = (s, x) => { let y = 0; while (y < H && s.px(x, y)[3] < 250) y++; const hz = Math.round(s.cam.project([4000, 0, 0])[1]); return { top: y, hz }; };
  let paler = 0, n = 0;
  for (let x = 40; x < W - 40; x += 20) { const c = column(one, x); if (c.hz - c.top < 30) continue; n++; if (dist(one.px(x, c.hz - 4), HAZE) < dist(one.px(x, c.top + 5), HAZE) - 12) paler++; }
  ok(n > 5 && paler / n > 0.8, 'a ridge stands in mist: its foot is paler than its crest', [paler, n]);
  let tones = 0;
  for (let x = 60; x < W - 60; x += 40) { const c = column(one, x), L = []; for (let y = c.top + 3; y < c.hz - 3; y++) L.push(lum(one.px(x, y))); let steps = 0; for (let i = 1; i < L.length; i++) if (L[i] < L[i - 1] - 4) steps++; if (steps >= 1) tones++; }
  ok(tones >= 3, 'crest stands behind crest inside it (a nearer, darker fold begins below a paler one)', tones);
  const cut = shot(ridgePaint({ layers: [{ dist: 1500, h: 420, color: '#30364a', seed: 4, gap: { az: 90, w: 5, depth: 0.95 } }] }), camO);
  const mid = column(cut, W / 2).top, side = Math.min(column(cut, 60).top, column(cut, W - 60).top);
  ok(mid > side + 25 && mid > column(one, W / 2).top + 20, 'a gap cuts the ridge down where it is asked for', [mid, side, column(one, W / 2).top]);
}

// ---- 4b. the light on a ridge has no seam where the ridge stands straight opposite the sun
{
  // (SUN is over −z: straight away from it is +z, the middle column of this picture)
  const s2 = shot(ridgePaint({ sun: { dir: SUN, color: '#ffd0a0' }, layers: [{ dist: 2500, h: 900, color: '#30364a', seed: 9, peaks: 7, folds: 3 }] }), { pos: [0, 1.6, 0], target: [0, 40, 600], fov: 30 });
  const jump = (x) => { let sum = 0, n = 0; for (let y = 40; y < H - 20; y += 2) { const a = s2.px(x, y), b = s2.px(x + 1, y); if (a[3] > 250 && b[3] > 250) { sum += Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]); n++; } } return n > 10 ? sum / n : 0; };
  const all = []; for (let x = 4; x < W - 5; x++) all.push(jump(x));
  const usual = [...all].sort((a, b) => a - b)[Math.floor(all.length * 0.9)], mid = Math.max(jump(W / 2 - 2), jump(W / 2 - 1), jump(W / 2), jump(W / 2 + 1));
  ok(mid <= usual * 1.6 + 2, 'the light goes round a ridge without a jump (no line down it opposite the sun)', [mid, usual]);
}

// ---- 5. mist lying along the horizon
{
  const B = { color: '#d9c9c4', h: 1.6, amt: 0.9 }, camO = { pos: [0, 1.6, 0], target: [40, 5, 0], fov: 50 };
  const a = shot(mistBand(B), camO), hz = Math.round(a.cam.project([4000, 0, 0])[1]);
  ok(a.mean(40, hz - 3, 440, hz - 1).every((v, i) => Math.abs(v - [0xd9, 0xc9, 0xc4][i]) < 10) && a.px(W / 2, hz - 2)[3] > 170, 'mist lies on the horizon', [a.mean(40, hz - 3, 440, hz - 1), a.px(W / 2, hz - 2)]);
  ok(a.px(W / 2, hz - 90)[3] < 12, 'high above it the air is clear', a.px(W / 2, hz - 90));
  const tops = []; for (let x = 10; x < W; x += 6) { let y = hz; while (y > 0 && a.px(x, y)[3] > 60) y--; tops.push(hz - y); }
  ok(Math.max(...tops) - Math.min(...tops) >= 4, 'its upper edge is uneven: banks and wisps', [Math.min(...tops), Math.max(...tops)]);
  const next = shot(mistBand(B), camO, 1 / 24), later = shot(mistBand(B), camO, 30);
  const adiff = (p, q) => { let s2 = 0; for (let i = 3; i < p.d.length; i += 4) s2 += Math.abs(p.d[i] - q.d[i]); return s2 / (p.d.length / 4); };
  ok(adiff(a, next) < 0.2, 'it does not flicker from frame to frame', adiff(a, next));
  ok(adiff(a, later) > 0.5, 'and drifts slowly', adiff(a, later));
}

// ---- 6. a crest can be soft or jagged
{
  const camO = { pos: [0, 1.6, 0], target: [40, 6, 0], fov: 50 };
  const line = (sharp) => { const s2 = shot(ridgePaint({ layers: [{ dist: 3000, h: 900, color: '#30364a', seed: 6, folds: 1, sharp }] }), camO); const ys = []; for (let x = 0; x < W; x += 2) { let y = 0; while (y < H && s2.px(x, y)[3] < 128) y++; ys.push(y); } let v = 0; for (let i = 1; i < ys.length; i++) v += Math.abs(ys[i] - ys[i - 1]); return v; };
  ok(line(1) > line(0) * 1.4, 'sharp: 1 gives a ridge teeth, sharp: 0 a rolling line', [line(0), line(1)]);
}

// ---- 6b. the highest crests are peaks, not tables: a ridge does not stand at its full height along a stretch of sky
{
  const camO = { pos: [0, 1.6, 0], target: [40, 6, 49], fov: 60 };
  const flat = (peaky) => { const s2 = shot(ridgePaint({ layers: [{ dist: 12000, h: 1750, color: '#30364a', seed: 3, folds: 3, peaks: 7, sharp: 0.78, base: 0.2, peaky }] }), camO); const ys = []; for (let x = 0; x < W; x++) { let y = 0; while (y < H && s2.px(x, y)[3] < 128) y++; ys.push(y); }
    // (the longest stretch of skyline, among its high parts, that keeps to one level: within a thirtieth of the ridge's relief)
    const top = Math.min(...ys), low = Math.max(...ys), tol = (low - top) / 30; let best = 0;
    for (let i = 0; i < ys.length; i++) { if (low - ys[i] < 0.6 * (low - top)) continue; let lo = ys[i], hi = ys[i], k = i; while (k + 1 < ys.length && Math.max(hi, ys[k + 1]) - Math.min(lo, ys[k + 1]) <= tol) { k++; lo = Math.min(lo, ys[k]); hi = Math.max(hi, ys[k]); } best = Math.max(best, k - i + 1); }
    return best / ys.length; };
  const table = flat(0), peaks = flat(1);
  ok(table > 0.04 && peaks < table * 0.55, 'peaky: 1 rounds the highest crests into peaks (no long stretch of the skyline keeps to one level)', [table, peaks]);
}

// ---- 7. grass standing on the land
{
  const LAND = landPaint({ colors: ['#4a5230', '#5d6238', '#3c4630', '#6e6a40'], earth: '#5a4634', earthAmt: 0, sun: { dir: SUN, color: '#ffc890' } });
  const G = { land: LAND, max: 20, h: [0.18, 0.42] }, camO = { pos: [0, 1.5, 0], target: [0, 0.6, -12], fov: 40 };
  const a = shot(grassPaint(G), camO), hz = Math.round(a.cam.project([0, 0, -4000])[1]);
  const count = (s2, y0, y1) => { let n = 0; for (let y = y0; y < y1; y++) for (let x = 0; x < W; x++) if (s2.px(x, y)[3] > 100) n++; return n / ((y1 - y0) * W); };
  ok(count(a, 200, 268) > 0.25, 'grass stands thick at the feet', count(a, 200, 268));
  ok(count(a, 0, hz - 2) === 0, 'none of it reaches above the horizon', count(a, 0, hz - 2));
  const far = Math.round(a.cam.project([0, 0, -60])[1]);
  ok(count(a, hz + 1, far) < 0.01, 'beyond its reach the painted land takes over', count(a, hz + 1, far));
  // blades are upright strokes: narrow runs across, taller than wide
  let runs = 0, wide = 0; for (let y = 205; y < 260; y += 5) { let run = 0; for (let x = 0; x < W; x++) { if (a.px(x, y)[3] > 100) run++; else { if (run) { runs++; if (run > 14) wide++; } run = 0; } } }
  ok(runs > 80 && wide / runs < 0.2, 'it is made of blades, not of patches', [runs, wide]);
  const again = shot(grassPaint(G), camO), next = shot(grassPaint(G), camO, 1 / 24), later = shot(grassPaint(G), camO, 2.5), moved = shot(grassPaint(G), { ...camO, pos: [1, 1.5, 0], target: [1, 0.6, -12] });
  ok(diff(a, again) === 0, 'the same moment is drawn the same', diff(a, again));
  ok(diff(a, next) < diff(a, later) * 0.5 && diff(a, later) > 0.3, 'it stirs in the wind, slowly', [diff(a, next), diff(a, later)]);
  ok(diff(a, moved) > 3, 'each tuft has its place on the land (a step to the side shows others)', diff(a, moved));
  // on bare earth nothing grows
  const bare = shot(grassPaint({ ...G, land: landPaint({ colors: ['#4a5230'], earth: '#5a4634', earthAmt: 1, earthCover: 1, sun: { dir: SUN } }) }), camO);
  ok(count(bare, 200, 268) < count(a, 200, 268) * 0.5, 'where the land is bare there is less of it', [count(bare, 200, 268), count(a, 200, 268)]);
}

if (fails.length) { console.log(`paintland: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`paintland: ${checks} rules hold`);
