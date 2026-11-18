import { cleanPass, tailOver } from "./studio_pass";

/**
 * A sentence for the studio voice to spoil, so the line is not.
 *
 * For the first third of a second after its reference clip the model is not yet itself: it hurries, swallows the first
 * sound or says another one. "Make a world" came out "Take a world" in eight takes of eight, "He is not a man" lost its
 * "He", Hindi lines began with a sound that is not in them. (Chatterbox is known for it; a first word that is hard
 * suffers most.) Cutting a fixed stretch off the start does not help: a clean first word is cut as well.
 *
 * So the line is given a short sentence before it ("Listen to me."), which takes what would have gone wrong, and the pause
 * after that sentence is found and the sentence cut away. Where the pause lies is read from the model's attention:
 * the frame at which it comes to the line's first word is, within a tenth of a second, where the lead ends and the
 * line begins; the quiet around that moment is the pause. A stop inside the lead or the line (the closed "k" of
 * "make") is quiet too, but it does not lie there.
 */

const DEVANAGARI = /[\u0900-\u097F]/;

/** The sentence said before a line and cut away (with the space that parts it from the line), "" where none is used. */
export function leadFor(text: string, lang: string): string {
  if (!text.trim()) return "";
  // (a whole sentence: after a single word, "Listen." or "सुनो।", the model often runs on into the line)
  // (never a lead that shares a word with the line: "मेरी बात सुनो।" before "प्लीज़, मेरी बात सुन लो" let the model
  //  jump from the lead's "मेरी बात" to the line's, and the cut took the line's first word with the lead)
  const words = new Set(text.toLowerCase().split(/[\s,.!?।…"'-]+/u).filter(Boolean));
  const pick = (c: string[]) => c.find((l) => !l.toLowerCase().split(/[\s,.!?।…]+/u).filter((w) => [...w].length > 2).some((w) => words.has(w))) ?? c[0]!;
  if (lang === "en") return pick(["Listen to me. ", "Now, hear this. ", "So, here it is. "]);
  if (lang === "hi" && DEVANAGARI.test(text)) return pick(["मेरी बात सुनो। ", "अच्छा, तो सुनिए। ", "देखिए, एक चीज़ है। "]);
  return "";
}

/**
 * The frame at which the speech comes to the line: free = the text position the model attends at each frame (25 a
 * second, before the mask that holds it to one more word a frame), leadEnd = the position of the line's first token.
 * It is the frame after the last one spent on the lead (looked for from a frame on). The attention looks ahead at the
 * line for a few frames and comes back, glances at the very end of the text, and often goes back to the lead's full
 * stop or to the start for the pause: none of that is the line yet. Once it has been off the lead for a third of a
 * second the line is under way, and a glance back is no new beginning. −1: it never left the lead, or was never on it.
 */
export function lineStart(free: readonly number[], leadEnd: number, from = 0): number {
  let last = -1;
  for (let f = Math.max(0, from); f < free.length; f++) {
    if (free[f]! < leadEnd) last = f;
    else if (last >= 0 && f - last > 8) break;
  }
  return last < 0 || last + 1 >= free.length ? -1 : last + 1;
}

/**
 * A take as the line alone would have it: every position counted from the line's first token (which stands where a
 * text's first token stands, after the start mark and the language tag), the lead folded onto the start. What the
 * attention does over the lead (it wanders there, and goes back to it for the pause) is then no movement at all.
 */
function asLine(free: readonly number[], S: number, leadEnd: number): { cur: number[]; S: number } {
  const base = leadEnd - 2;
  return { cur: free.map((c) => Math.max(0, c - base)), S: S - base };
}

/**
 * The one clean pass through the line (studio_pass.ts) in a take made with a lead, judged by the line alone.
 * from = the frame at which the run-up to it begins (in the lead, or in what the model murmured before it),
 * start = the frame at which the line itself begins (lineStart; where the run-up was never on the lead, the line
 * was begun with no lead before it, after babble, and begins with its pass), end and dirt as cleanPass gives them.
 * null: the line was never said through.
 */
export function linePass(free: readonly number[], S: number, leadEnd: number): { from: number; start: number; end: number; dirt: number } | null {
  const l = asLine(free, S, leadEnd), p = cleanPass(l.cur, l.S);
  if (!p) return null;
  const start = lineStart(free, leadEnd, p.from);
  return { ...p, start: start < 0 ? p.from : start };
}

/** Whether making the take can stop (studio_pass.ts tailOver), judged by the line alone. */
export function lineOver(free: readonly number[], S: number, leadEnd: number): boolean {
  const l = asLine(free, S, leadEnd);
  return tailOver(l.cur, l.S);
}

/** Loudness in 10 ms windows every 5 ms, and the loudest of them. */
function loudness(pcm: Float32Array, sr: number): { e: Float32Array; hop: number; peak: number } {
  const hop = Math.max(1, Math.round(sr * 0.005)), win = hop * 2;
  const n = Math.max(0, Math.floor((pcm.length - win) / hop) + 1), e = new Float32Array(n);
  let peak = 0;
  for (let f = 0; f < n; f++) {
    let q = 0;
    for (let i = f * hop; i < f * hop + win; i++) q += pcm[i]! * pcm[i]!;
    peak = Math.max(peak, (e[f] = Math.sqrt(q / win)));
  }
  return { e, hop, peak };
}

/**
 * The pause in which a line begins: the stretch of quiet (30 ms or more, under 6 % of the loudest) that lies where
 * the attention came to the line (at, in samples). The attention often comes to the line while the lead is still
 * sounding, so the quiet may begin up to 0.14 s after it; but it does not come after the pause is over (in two dozen
 * takes the pause never ended more than 0.01 s before it), so quiet that ended 0.05 s before it is not the pause:
 * what sounds between the two is something the model said that is not the line. And a pause comes after a lead: a
 * tenth of a second of sound at the least (the silence a take begins with comes after nothing: when the lead was
 * skipped the line begins at once, and there is no pause). null: there is no pause there.
 */
export function pauseNear(pcm: Float32Array, at: number, sr: number): { from: number; to: number } | null {
  const { e, hop, peak } = loudness(pcm, sr);
  if (!e.length || peak <= 0) return null;
  const quiet = peak * 0.06, least = Math.round(0.03 * sr / hop), late = Math.round(0.14 * sr), early = Math.round(0.05 * sr);
  const lead = Math.round(0.1 * sr / hop);
  let sound = 0; // (windows of sound so far)
  for (let f = 0; f < e.length && f * hop <= at + late; f++) {
    if (e[f]! > quiet) { sound++; continue; }
    let g = f;
    while (g < e.length && e[g]! <= quiet) g++;
    if (sound >= lead && g - f >= least && g * hop >= at - early) return { from: f * hop, to: Math.min(pcm.length, g * hop) };
    f = g - 1;
  }
  return null;
}

/**
 * A take without its lead: cut in the middle of the pause where the line begins (at = the sample at which the
 * attention came to the line, −1 when it never did). clean: false when there was no pause to cut in: then it is cut at
 * the least loud moment about there, and the take is best made again.
 */
export function withoutLead(pcm: Float32Array, at: number, sr: number): { pcm: Float32Array; clean: boolean } {
  if (at < 0 || at >= pcm.length) return { pcm, clean: false };
  const p = pauseNear(pcm, at, sr);
  let cut: number;
  if (p) cut = (p.from + p.to) >> 1;
  else {
    const { e, hop } = loudness(pcm, sr);
    const a = Math.max(0, Math.floor((at - 0.06 * sr) / hop)), b = Math.min(e.length - 1, Math.ceil((at + 0.14 * sr) / hop));
    let k = a;
    for (let f = a; f <= b; f++) if (e[f]! < e[k]!) k = f;
    cut = Math.min(pcm.length, k * hop + hop);
  }
  const out = pcm.slice(cut);
  const fade = Math.min(out.length, Math.round(sr * 0.008));
  for (let i = 0; i < fade; i++) out[i] = out[i]! * (i / fade);
  return { pcm: out, clean: !!p };
}
