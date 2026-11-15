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
 */
export function beatMatch(steps, bpm, o = {}) {
  const [lo, hi] = o.rates ?? [0.8, 1.25], beat = 60 / bpm;
  if (steps.length < 3) return { rate: 1, in: steps[0] ?? 0, per: 1, error: 0, steps: steps.length };
  const gaps = steps.slice(1).map((s, i) => s - steps[i]).sort((a, b) => a - b), iv = gaps[Math.floor(gaps.length / 2)];
  // which note value the steps are nearest to at a natural speed
  let per = 1, best = Infinity;
  for (const k of [0.5, 1, 2]) { const r = iv / (beat * k), d = Math.abs(Math.log(r)); if (d < best) { best = d; per = k; } }
  const target = beat * per;
  // a straight line through (step number, step time): its slope is the clip time of one step, its start the step on beat 0
  const n = [0]; for (let i = 1; i < steps.length; i++) n.push(n[i - 1] + Math.max(1, Math.round((steps[i] - steps[i - 1]) / iv)));
  const N = steps.length, mn = n.reduce((a, b) => a + b, 0) / N, ms = steps.reduce((a, b) => a + b, 0) / N;
  let sxy = 0, sxx = 0; for (let i = 0; i < N; i++) { sxy += (n[i] - mn) * (steps[i] - ms); sxx += (n[i] - mn) ** 2; }
  const slope = Math.min(hi * target, Math.max(lo * target, sxy / sxx)), rate = slope / target;
  const first = ms - slope * mn, start = first - slope * Math.floor(first / slope + 1e-9);
  let err = 0, m = 0;
  for (const s of steps) { const tau = (s - start) / rate; if (tau < -1e-6) continue; err += Math.abs(tau / target - Math.round(tau / target)) * target; m++; }
  return { rate, in: Math.max(0, start), per, error: m ? err / m : 0, steps: N };
}
