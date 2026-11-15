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
