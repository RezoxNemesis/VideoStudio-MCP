/**
 * The pauses INSIDE a spoken sentence, for VolTune Studio.
 *
 * The studio model speaks a sentence as one breath. It hears "…", a dash or a comma as a hint at best: "ये… तेरे पापा की
 * पहली तनख़्वाह है।" came out with no pause at all, in voice after voice, and a felt line without its pauses sounds read,
 * not acted (owner, 26 Nov 2026: "voices, dialogues, pacing… weak"). The pauses BETWEEN sentences are set by
 * studio_pace; this sets the ones within: each mark inside the sentence is found in the sound and given its length.
 *
 * Where a mark lies in the sound: the studio model's own attention tells it. As it speaks, the engine follows which
 * letter of the line each 40 ms of sound is spoken from (studio.ts), so it knows the moment the speech comes to the
 * word after a mark, to about a tenth of a second (SayResult.stops). The gap between two words nearest before that
 * moment — a silence, or where the sound dips between them — is the mark. It is opened to the length the mark asks
 * for, and what fills it is the take's own room (its quietest instant, overlapped), never digital silence, so a pause
 * does not click or drop into a hole. A mark with no gap near it (one vowel flowing into the next) is left alone: a
 * pause is never cut through a word. Without the engine's moments (another engine, an old take) the place is
 * estimated from the share of the line's letters before the mark, and then only a clear silence is trusted.
 * It works on the sound and the model's attention alone, so it serves every voice, gender and language the studio speaks.
 */
import type { Pace } from "./studio_pace";

export interface InnerMark {
  /** Share (0–1) of the sentence's letters spoken before the mark. */
  at: number;
  /** Seconds of pause the mark asks for. */
  want: number;
  /** "…", a dash, or a sentence end inside a joined line: a pause the listener must hear. A comma is light. */
  strong: boolean;
  /** Where the word after the mark begins in the (trimmed) text, in string units: the engine asks its alignment for this place. */
  next: number;
}

const LETTER = /[\p{L}\p{M}\p{N}]/u;
const rateOf = (tone: Pace) => Math.max(0.6, Math.min(1.4, tone.rate ?? 1));

/** The marks inside a sentence (not its closing one) and the pause each asks for: longer for a tone that pauses or goes slowly. */
export function innerMarks(text: string, tone: Pace = {}): InnerMark[] {
  const t = text.trim();
  const chars = [...t];
  const total = chars.filter((c) => LETTER.test(c)).length;
  if (total < 4) return [];
  const k = (tone.pauses ?? 1) / rateOf(tone);
  const out: InnerMark[] = [];
  let seen = 0;
  const offs: number[] = [];
  { let o = 0; for (const c of chars) { offs.push(o); o += c.length; } offs.push(o); }
  for (let i = 0; i < chars.length; i++) {
    const c = chars[i]!;
    if (LETTER.test(c)) { seen++; continue; }
    let j = i;
    while (j + 1 < chars.length && !LETTER.test(chars[j + 1]!) ) j++; // the whole run of marks and spaces up to the next letter
    if (j + 1 >= chars.length) break; // the closing mark belongs to the pause after the sentence
    const run = chars.slice(i, j + 1).join("");
    i = j;
    if (seen === 0) continue;
    // a number keeps its point and its comma (3.5, 1,000): no pause inside it
    if (/^[.,]$/.test(run) && /\p{N}/u.test(chars[i - 1] ?? "") && /\p{N}/u.test(chars[i + 1] ?? "")) continue;
    let want = 0, strong = true;
    if (/…|\.\.\./.test(run)) want = 0.5;
    else if (/[—–]|\s-\s/.test(run)) want = 0.34;
    else if (/[?]/.test(run)) want = 0.42;
    else if (/[.!।॥]/.test(run)) want = 0.38;
    else if (/[;:]/.test(run)) { want = 0.26; strong = false; }
    else if (/[,،、]/.test(run)) { want = 0.18; strong = false; }
    if (want > 0) out.push({ at: seen / total, want: want * k, strong, next: offs[i + 1]! });
  }
  return out;
}

interface Valley { from: number; to: number; low: number; depth: number; speechBefore: number }

/** The quiet stretches inside a take (not its edges): where each lies, its lowest point, how deep it is, and how much speech came before it. */
export function valleys(pcm: Float32Array, sr = 24000): { list: Valley[]; speech: number; level: number; env?: Float32Array; hop?: number; first?: number; last?: number; thr?: number } {
  const hop = Math.max(1, Math.round(sr * 0.005)), win = hop * 2, n = Math.max(0, Math.floor((pcm.length - win) / hop) + 1);
  if (n < 12) return { list: [], speech: pcm.length / sr, level: 0 };
  const e = new Float32Array(n);
  for (let f = 0; f < n; f++) { let s = 0; for (let i = f * hop; i < f * hop + win; i++) s += pcm[i]! * pcm[i]!; e[f] = Math.sqrt(s / win); }
  const sorted = Float32Array.from(e).sort(), level = sorted[Math.floor(n * 0.9)]!;
  if (!(level > 1e-6)) return { list: [], speech: 0, level };
  const thr = level * 0.09, quiet = (f: number) => e[f]! < thr;
  let a = 0, b = n - 1;
  while (a < b && quiet(a)) a++;
  while (b > a && quiet(b)) b--;
  const list: Valley[] = [];
  let speech = 0;
  for (let f = a; f <= b; ) {
    if (!quiet(f)) { speech++; f++; continue; }
    let g = f, lowF = f;
    while (g + 1 <= b && quiet(g + 1)) { g++; if (e[g]! < e[lowF]!) lowF = g; }
    // the lowest point is taken as the middle of the quietest stretch, not a single dip
    let m0 = lowF, m1 = lowF;
    while (m0 > f && e[m0 - 1]! <= e[lowF]! * 1.6 + 1e-7) m0--;
    while (m1 < g && e[m1 + 1]! <= e[lowF]! * 1.6 + 1e-7) m1++;
    list.push({ from: f * hop, to: Math.min(pcm.length, (g + 1) * hop + hop), low: Math.round(((m0 + m1) / 2) * hop + hop), depth: e[lowF]! / level, speechBefore: (speech * hop) / sr });
    f = g + 1;
  }
  return { list, speech: (speech * hop) / sr, level, env: e, hop, first: a, last: b, thr };
}

/**
 * The take's own room, to fill an opened pause with: a soft noise at the level of the take's quietest instant (30 ms:
 * the gaps of a run-together line are no longer than that, and a longer stretch would bring the edges of words into the
 * pause). Never louder than a fortieth of the speech; made the same for the same take.
 */
function roomTone(pcm: Float32Array, sr: number, length: number, level: number): Float32Array {
  const out = new Float32Array(length), w = Math.min(pcm.length, Math.round(sr * 0.03));
  if (w < 16 || length <= 0) return out;
  const step = Math.max(1, Math.round(sr * 0.005));
  let quietest = Infinity;
  for (let i = 0; i + w <= pcm.length; i += step) { let e = 0; for (let j = i; j < i + w; j++) e += pcm[j]! * pcm[j]!; if (e < quietest) quietest = e; }
  const rms = Math.min(Math.sqrt(quietest / w), level * 0.025);
  if (!(rms > 1e-7)) return out;
  let seed = (length * 2654435761) >>> 0, lp = 0, sum = 0;
  for (let i = 0; i < length; i++) { seed = (seed * 1664525 + 1013904223) >>> 0; lp = lp * 0.82 + (seed / 4294967296 - 0.5); out[i] = lp; sum += lp * lp; }
  const g = rms / Math.sqrt(sum / length || 1), edge = Math.min(length >> 1, Math.round(sr * 0.01));
  for (let i = 0; i < length; i++) out[i]! *= g * Math.min(1, (i + 1) / edge, (length - i) / edge);
  return out;
}

/** A silence between words is at least this long (a stop's closure inside a word is as quiet, but the alignment tells them apart). */
const MIN_GAP = 0.03;

/**
 * A spoken sentence with the pauses its inner marks ask for.
 *   stops: for each inner mark (in order), the second at which the speech comes to the word after it, from the
 *          engine's alignment (SayResult.stops; -1 or absent: not known).
 * → the sound, and what was done: for each mark placed, the second it falls at in the take, the pause it had and has now.
 */
export function innerPauses(pcm: Float32Array, text: string, tone: Pace = {}, sr = 24000, stops?: number[]): { pcm: Float32Array; made: { at: number; had: number; now: number }[]; missed?: number } {
  const marks = innerMarks(text, tone);
  if (!marks.length || pcm.length < sr * 0.4) return { pcm, made: [] };
  const v = valleys(pcm, sr);
  if (!(v.speech > 0) || !v.env || !v.hop) return { pcm, made: [] };
  const e = v.env, hop = v.hop, n = e.length, first = v.first!, last = v.last!;
  // every gap between sounds: the silences, and the dips where the sound falls under 45 % of the speech around it
  interface Gap { t: number; low: number; had: number; quiet: boolean; a: number; b: number }
  const gaps: Gap[] = v.list.filter((x) => (x.to - x.from) / sr >= MIN_GAP).map((x) => ({ t: x.low / sr, low: x.low, had: (x.to - x.from) / sr, quiet: true, a: x.from / sr, b: x.to / sr }));
  const sm = new Float32Array(n);
  for (let f = 0; f < n; f++) { let t = 0, c = 0; for (let g = Math.max(0, f - 2); g <= Math.min(n - 1, f + 2); g++) { t += e[g]!; c++; } sm[f] = t / c; }
  const near = Math.round((sr * 0.15) / hop);
  for (let f = first + near; f <= last - near; f++) {
    if (!(sm[f]! < sm[f - 1]! && sm[f]! <= sm[f + 1]!)) continue; // a local minimum
    if (gaps.some((g) => g.quiet && Math.abs(g.low - f * hop) < sr * 0.06)) continue; // it is one of the silences
    const around = Array.from(e.subarray(Math.max(0, f - near), Math.min(n, f + near + 1))).sort((x, y) => x - y), ref = around[Math.floor(around.length * 0.7)]!;
    if (sm[f]! <= ref * 0.45) gaps.push({ t: (f * hop + hop) / sr, low: f * hop + hop, had: 0, quiet: false, a: (f * hop + hop) / sr, b: (f * hop + hop) / sr });
  }
  gaps.sort((x, y) => x.low - y.low);
  // without the engine's moments: where the share of letters before the mark falls in the speech (silences left out)
  const cum = new Float32Array(n); { let run = 0; for (let f = 0; f < n; f++) { if (f >= first && f <= last && e[f]! >= v.thr!) run++; cum[f] = (run * hop) / sr; } }
  const guess = (at: number) => { let f = first; while (f < last && cum[f]! < at * v.speech) f++; return (f * hop) / sr; };
  const tol = Math.max(0.2, v.speech * 0.13);
  const cuts: { low: number; add: number; had: number; now: number }[] = [];
  let from = 0, missed = 0;
  marks.forEach((m, i) => {
    const stop = stops?.[i], known = stop !== undefined && stop >= 0;
    // The word after the mark begins at `stop`, so the mark's own gap ENDS about there: measured on real takes the
    // attention reaches the next word anywhere from the gap's start to 0.15 s after its end. So a gap is judged by how
    // far `stop` is from the whole gap (nothing, if it falls inside it), and a longer silence wins over a short one
    // nearby: the model already paused there, which is the best sign of where the mark is.
    // A gap that ENDS well before `stop` belongs to an earlier word (measured: "Nobody told | you — not" took the gap after
    // "told", 0.28 s before): only 0.14 s is allowed that way. Where the voice ran on through a mark, no pause is made
    // (a cut inside running speech clips a word) and the mark is counted in `missed`, so that another take can be tried.
    const aim = known ? stop : guess(m.at), before = known ? 0.14 : tol, after = known ? 0.2 : tol;
    let best: Gap | null = null, bestCost = Infinity;
    for (const g of gaps) {
      if (g.low <= from) continue;
      if (!g.quiet && (!known || !m.strong)) continue; // a dip is only trusted where the alignment points at it, and only for a pause that must be heard
      if (!known && g.had < (m.strong ? 0.035 : 0.045)) continue;
      let cost: number;
      if (known) {
        const off = aim < g.a - 0.02 ? g.a - 0.02 - aim : aim > g.b + 0.08 ? aim - g.b - 0.08 : 0;
        if (aim < g.a ? g.a - aim > after : aim - g.b > before) continue;
        cost = off - (g.quiet ? 0.03 + Math.min(0.12, g.had * 0.6) : 0);
      } else {
        if (g.t < aim - before || g.t > aim + after) continue;
        cost = Math.abs(g.t - aim) - (g.quiet ? 0.05 + Math.min(0.06, g.had * 0.5) : 0);
      }
      if (cost < bestCost) { bestCost = cost; best = g; }
    }
    if (!best) { if (m.strong) missed++; return; }
    from = best.low + Math.round(sr * 0.08);
    const add = best.had >= m.want - 0.03 ? 0 : Math.round((m.want - best.had) * sr);
    cuts.push({ low: best.low, add, had: best.had, now: add ? m.want : best.had });
  });
  const extra = cuts.reduce((k, c) => k + c.add, 0);
  const made = cuts.map((c) => ({ at: Math.round((c.low / sr) * 1000) / 1000, had: Math.round(c.had * 1000) / 1000, now: Math.round(c.now * 1000) / 1000 }));
  if (!extra) return { pcm, made, missed };
  const out = new Float32Array(pcm.length + extra), fade = Math.round(sr * 0.012);
  let src = 0, dst = 0, afterGap = false;
  // lay a stretch of the take down; after a pause it comes up from nothing, before one it dies away
  const lay = (to: number, beforeGap: boolean) => {
    const len = to - src;
    out.set(pcm.subarray(src, to), dst);
    const f = Math.min(fade, len >> 1);
    if (afterGap) for (let k = 0; k < f; k++) out[dst + k]! *= k / f;
    if (beforeGap) for (let k = 0; k < f; k++) out[dst + len - 1 - k]! *= k / f;
    dst += len; src = to;
  };
  for (const c of cuts) {
    if (!c.add) continue;
    lay(Math.max(src, Math.min(pcm.length, c.low)), true);
    out.set(roomTone(pcm, sr, c.add, v.level), dst);
    dst += c.add; afterGap = true;
  }
  lay(pcm.length, false);
  return { pcm: out, made, missed };
}

/**
 * A short piece of sound standing alone at the very end of a take, after a silence: either the line's real last word
 * said after a comma ("…नहीं, माँ?") or a stray echo of it that the model added ("…नहीं, माँ? माँ": the owner heard "maa"
 * twice, and Whisper wrote it once, so the letter count passed). The sound alone cannot tell the two apart; the caller
 * listens to the take WITHOUT the piece, and cuts it when the words are all still there (studio_client.ts).
 * → where to cut (the middle of the silence before the piece), or null when the take does not end that way.
 */
export function tailPiece(pcm: Float32Array, sr = 24000): { cut: number; piece: number; gap: number } | null {
  const v = valleys(pcm, sr);
  if (!v.list.length || v.last === undefined || v.first === undefined || !v.hop) return null;
  const end = (v.last + 1) * v.hop, g = [...v.list].reverse().find((x) => x.to < end && (x.to - x.from) / sr >= 0.08);
  if (!g) return null;
  const piece = (end - g.to) / sr, before = (g.from - v.first * v.hop) / sr;
  if (piece < 0.03 || piece > 0.38 || before < 0.5) return null;
  // nothing but this one piece may follow the silence
  if (v.list.some((x) => x.from > g.to && (x.to - x.from) / sr >= 0.08 && x.to < end)) return null;
  return { cut: Math.round((g.from + g.to) / 2), piece: Math.round(piece * 1000) / 1000, gap: Math.round(((g.to - g.from) / sr) * 1000) / 1000 };
}
/** The take up to `cut`, dying away over its last 30 ms. */
export function cutTail(pcm: Float32Array, cut: number, sr = 24000): Float32Array {
  const out = pcm.slice(0, Math.max(0, Math.min(pcm.length, cut))), f = Math.min(out.length, Math.round(sr * 0.03));
  for (let i = 0; i < f; i++) out[out.length - 1 - i]! *= i / f;
  return out;
}
