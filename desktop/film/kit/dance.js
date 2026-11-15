// Dancing on the music. A captured dance has its own tempo; the music has another. Find the dancer's steps, then play
// the clip a little faster or slower and start it at the right moment, so the feet come down on the beats.
//   const G = beatGrid(120, 30)            → a beat every 15 frames (pick a tempo whose beats are whole frames)
//   const steps = stepTimes(contact, fps)  → when the feet come down (from a solved performance: steps.mjs prints them)
//   const M = beatMatch(steps, 120)        → { rate, in, per, error }: play the clip from `in` at `rate`
//   countAt(t, 120)                        → { count: 1…8, accent } for poses, cuts and lights that hit the beat

/** The grid of a tempo at a frame rate. whole: a beat is a whole number of frames; if not, `suggest` is the nearest tempo where it is. */
export function beatGrid(bpm, fps = 30) {
  const beat = 60 / bpm, frames = fps * beat, whole = Math.abs(frames - Math.round(frames)) < 1e-6;
  let suggest = bpm;
  if (!whole) {
    const a = (fps * 60) / Math.ceil(frames), b = (fps * 60) / Math.floor(frames);
    suggest = Math.round((Math.abs(a - bpm) <= Math.abs(b - bpm) ? a : b) * 10) / 10;
  }
  return { bpm, beat, frames, whole, bar: beat * 4, eight: beat * 8, suggest };
}
/** Dancers count in eights. Where t is in the count: { beat (from 0), count 1…8, eight (which eight), phase 0…1 into the beat, accent (the 1) }. */
export function countAt(t, bpm, o = {}) {
  const x = (t - (o.start ?? 0)) / (60 / bpm), beat = Math.floor(x + 1e-9), count = ((beat % 8) + 8) % 8 + 1;
  return { beat, count, eight: Math.floor(beat / 8), phase: Math.max(0, x - beat), accent: count === 1 };
}
/** When the feet come down: the moments a foot's contact begins. contact: one byte a frame, bit 1 = left foot down, bit 2 = right. */
export function stepTimes(contact, fps) {
  const out = [];
  for (let i = 1; i < contact.length; i++) { const rise = contact[i] & ~contact[i - 1]; if (rise & 1) out.push(i / fps); if (rise & 2) out.push(i / fps); }
  return out.sort((a, b) => a - b).filter((t, i, a) => i === 0 || t - a[i - 1] > 1e-6);
}
/**
 * Lay a dance on a tempo. steps: the dancer's step times in the clip's own seconds. Returns how to play the clip:
 * from `in` (clip seconds) at `rate`, so that each step falls on a beat (per = 1), a half beat (0.5) or every other
 * beat (2) — whichever needs the least change of speed. error: how far the steps still are from the beats, in seconds.
 * o.rates: the slowest and fastest the clip may be played and still look natural (default [0.8, 1.25]).
 * The dancer's pulse is found the way a period is found in any train of events (every step turns a clock hand; at the
 * right period all the hands point one way), so steps left out, pauses and taps in between do not hide it.
 * sure: 0…1, how much of a pulse there is at all — below about 0.5 the dance has none worth matching.
 */
export function beatMatch(steps, bpm, o = {}) {
  const [lo, hi] = o.rates ?? [0.8, 1.25], beat = 60 / bpm, N = steps.length;
  if (N < 3) return { rate: 1, in: steps[0] ?? 0, per: 1, error: 0, sure: 0, steps: N };
  // how well the steps keep to a pulse of period P, and where in the period they fall
  const pulse = (P) => { let c = 0, q = 0; for (const t of steps) { const a = (t / P) * 2 * Math.PI; c += Math.cos(a); q += Math.sin(a); } return { r: Math.hypot(c, q) / N, ph: Math.atan2(q, c) }; };
  let best = 0; const cand = [];
  for (let P = 0.18; P <= 0.9001; P += 0.002) { const r = pulse(P).r; cand.push([P, r]); if (r > best) best = r; }
  // half a true period fits as well as the period itself: of the near-best, the longest is the dancer's step
  let Pb = cand.filter(([, r]) => r >= best * 0.92).pop()[0], rb = pulse(Pb).r;
  // (that is somewhere on the side of its peak: climb to the top in fine steps)
  for (const dir of [-1, 1]) for (let k = 0; k < 400; k++) { const P = Pb + dir * 0.0001, r = pulse(P).r; if (r <= rb) break; rb = r; Pb = P; }
  let per = 1, d0 = Infinity;
  for (const k of [0.5, 1, 2]) { const d = Math.abs(Math.log(Pb / (beat * k))); if (d < d0) { d0 = d; per = k; } }
  const target = beat * per, slope = Math.min(hi * target, Math.max(lo * target, Pb)), rate = slope / target;
  const ph = pulse(slope).ph, start = ((((ph / (2 * Math.PI)) * slope) % slope) + slope) % slope;
  let err = 0, m = 0;
  for (const s of steps) { const tau = (s - start) / rate; if (tau < -1e-6) continue; err += Math.abs(tau / target - Math.round(tau / target)) * target; m++; }
  return { rate, in: start, per, error: m ? err / m : 0, sure: rb, steps: N };
}
/**
 * The pulse of a dancer who grooves on the spot is in the bounce: the hips come down on every beat. The moments the
 * hips are lowest. ys: the height of the hips at every frame (m); a dip counts when it is at least `deep` (0.012 m)
 * below the heights around it. Give the result to beatMatch like steps.
 */
export function bounceTimes(ys, fps, o = {}) {
  const deep = o.deep ?? 0.012, w = Math.max(2, Math.round(fps * (o.win ?? 0.18))), N = ys.length, out = [];
  // smooth a little (three frames) so a tremble does not make dips
  const y = ys.map((_, i) => (ys[Math.max(0, i - 1)] + ys[i] + ys[Math.min(N - 1, i + 1)]) / 3);
  for (let i = w; i < N - w; i++) {
    let low = true, hi = -Infinity;
    for (let k = i - w; k <= i + w; k++) { if (y[k] < y[i] - 1e-9 || (y[k] === y[i] && k < i)) { low = false; break; } hi = Math.max(hi, y[k]); }
    if (low && hi - y[i] >= deep) { out.push(i / fps); i += w; }
  }
  return out;
}
/**
 * Lock a captured dance to the music. A person is not a metronome: a captured dancer's pulse drifts and stumbles, so
 * every mark (a dip of the hips from bounceTimes, or a step) is moved onto the music's grid by playing the stretch
 * before it a little faster or slower — never more than `max` (15 %), so it does not show; a mark that would need
 * more is skipped and the next one carries on.
 *   marks: CLIP seconds of the dips, in order.   o: { bpm, in, out (clip seconds played), t (film seconds the action
 *   starts at; the music starts at 0), per: 0.5 (beats between two marks: 0.5 = a bounce on every half beat), max: 0.15 }
 * → { warp: [[seconds since the action began, clip seconds], …] for the clip action, dur, locked, skipped, rates: [slowest, fastest] }
 */
export function beatLock(marks, o = {}) {
  const step = (60 / o.bpm) * (o.per ?? 0.5), cin = o.in, cout = o.out, t = o.t ?? 0, max = o.max ?? 0.15;
  const r6 = (x) => Math.round(x * 1e6) / 1e6, warp = [[0, cin]];
  let a0 = 0, c0 = cin, locked = 0, skipped = 0, lo = 1, hi = 1;
  for (const m of [...marks].filter((x) => x > cin + 1e-6 && x < cout - 1e-6).sort((x, y) => x - y)) {
    const gap = m - c0;
    // the first mark goes to the grid moment nearest to where it falls by itself; the others a whole number of grid steps on
    const a = locked === 0 ? Math.round((t + a0 + gap) / step) * step - t : a0 + Math.max(1, Math.round(gap / step)) * step;
    const rate = a - a0 > 1e-6 ? gap / (a - a0) : Infinity;
    if (Math.abs(rate - 1) > max) { skipped++; continue; }
    warp.push([r6(a), m]); a0 = a; c0 = m; locked++; lo = Math.min(lo, rate); hi = Math.max(hi, rate);
  }
  warp.push([r6(a0 + (cout - c0)), cout]);
  return { warp, dur: warp[warp.length - 1][0], locked, skipped, rates: [lo, hi] };
}
