/**
 * The one clean saying of a line inside what the studio voice made.
 *
 * While the model speaks, its attention moves through the words of the line: frame by frame (25 a second) one can
 * read which word it is on. A good take goes through the words once, from the first to the last. A bad one babbles
 * before it begins (the attention hangs on the first word, or rushes to the end and comes back), or goes on after
 * the last word until it is stopped. Hindi lines do both far more often than English ones, and the checks that
 * come with the model (which weigh how strongly the last words are attended) do not catch them. So the path of the
 * attention itself is read: a pass through the words begins near the first word and moves on in small steps to the
 * last; the cleanest whole pass is the line, and what lies before and after it is left out.
 */

export interface Pass {
  /** The frame the pass begins at. */
  from: number;
  /** The frame at which it has come to the last words (−1: it never did). */
  end: number;
  /** Frames in which the attention sprang away from where the speech was (0 for a clean pass). */
  dirt: number;
  /** The word it has come to, and the frame it last moved on. */
  pos: number;
  last: number;
}

/** A middle value over five frames: a frame or two spent elsewhere is no move. */
function settle(cur: readonly number[]): number[] {
  const out: number[] = [];
  for (let i = 0; i < cur.length; i++) {
    const w = cur.slice(Math.max(0, i - 2), i + 3).sort((a, b) => a - b);
    out.push(w[(w.length - 1) >> 1]!);
  }
  return out;
}

/** Every pass through the words in a take: cur = the text position attended at each frame, S = text positions. */
export function passes(cur: readonly number[], S: number): Pass[] {
  if (S < 6 || !cur.length) return [];
  const low = Math.max(3, Math.round(S * 0.12)), sm = settle(cur), out: Pass[] = [];
  let p: Pass | null = null;
  let off = 0; // frames in a row spent away from where the speech was
  for (let f = 0; f < sm.length; f++) {
    const c = sm[f]!;
    if (!p) {
      if (c <= low) { p = { from: f, end: -1, dirt: 0, pos: c, last: f }; off = 0; }
      continue;
    }
    const d = c - p.pos;
    if (d > -4 && d < 7) {
      // the speech moves on (or stays, or steps back a word)
      if (c > p.pos) p.last = f;
      p.pos = c;
      off = 0;
      if (p.end < 0 && p.pos >= S - 2) p.end = f;
      continue;
    }
    if (c <= low && p.pos > low + 3) {
      // back at the first words: this pass is over (said, or given up), another begins
      out.push(p);
      p = { from: f, end: -1, dirt: 0, pos: c, last: f };
      off = 0;
      continue;
    }
    // (once the line has been said to its end, where the attention wanders is the tail's affair, not the line's)
    if (p.end >= 0) continue;
    // sprung away: words were skipped, or the attention is somewhere it has no business
    p.dirt++;
    if (++off >= 3) {
      p.dirt += 5;
      p.pos = c;
      p.last = f;
      off = 0;
      if (p.end < 0 && p.pos >= S - 2) p.end = f;
    }
  }
  if (p) out.push(p);
  return out;
}

/**
 * The line itself: the cleanest pass that went through to the last words (the longest of equals), or null.
 * dirt: 0 for a pass that took every word in turn; more when it leapt over words (the take should be made again).
 */
export function cleanPass(cur: readonly number[], S: number): { from: number; end: number; dirt: number } | null {
  let best: Pass | null = null;
  for (const p of passes(cur, S)) {
    if (p.end < 0) continue;
    if (!best || p.dirt < best.dirt || (p.dirt === best.dirt && p.end - p.from > best.end - best.from)) best = p;
  }
  return best ? { from: best.from, end: best.end, dirt: best.dirt } : null;
}

/**
 * Whether making the line can stop: it was said through cleanly, that was a good half second ago, and it is not being
 * said again (a pass begun since then that has moved on within the last half second is still speech).
 */
export function tailOver(cur: readonly number[], S: number, wait = 16): boolean {
  const all = passes(cur, S), low = Math.max(3, Math.round(S * 0.12));
  let best: Pass | null = null;
  for (const p of all) if (p.end >= 0 && p.dirt === 0 && (!best || p.end - p.from > best.end - best.from)) best = p;
  if (!best || cur.length - best.end <= wait) return false;
  const lastP = all[all.length - 1]!;
  if (lastP !== best && lastP.from > best.end && lastP.end < 0 && lastP.pos > low + 3 && cur.length - lastP.last <= 12) return false;
  return true;
}
