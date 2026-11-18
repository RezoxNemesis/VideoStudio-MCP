/**
 * Listening back to a studio take. The model now and then says a word that is not in the line ("बैठो" came out
 * "बैठ हूँ", "ठीक" as "थीक"), skips a sound or adds one. Every take is heard by Whisper and compared, letter by letter,
 * with what it was meant to say; a take that strays is made again with another seed, and the closest of the takes
 * is kept.
 */

/** The text as compared: one Unicode form, no nuktas (Whisper writes them or not at random), no punctuation. */
export function normalizeHeard(s: string): string {
  return s
    .normalize("NFC")
    .toLowerCase()
    .replace(/़/g, "")
    .replace(/[।॥]/g, " ")
    .replace(/[^\p{L}\p{M}\p{N}\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Character error rate of what was heard against what was meant (0 = exact). */
export function heardError(meant: string, heard: string): number {
  const a = [...normalizeHeard(meant)], b = [...normalizeHeard(heard)];
  if (!a.length) return b.length ? 1 : 0;
  // Whisper writes numbers in figures ("11 बज कर"); a line that says them in words is not wrong for it
  if (/\d/.test(heard) && !/\d/.test(meant)) return Math.max(0, editDistance(a.filter((c) => !/\p{N}/u.test(c)), b.filter((c) => !/\p{N}/u.test(c))) / a.length - 0.08);
  return editDistance(a, b) / a.length;
}

function editDistance(a: string[], b: string[]): number {
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j]! + 1, cur[j - 1]! + 1, prev[j - 1]! + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length]!;
}

/** A take this close to the line is kept at once (Whisper itself mishears a letter or two in ten words). */
export const GOOD_TAKE = 0.06;
/** Takes made at most for one sentence. */
export const MAX_TAKES = 3;
