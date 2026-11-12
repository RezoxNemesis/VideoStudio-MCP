/**
 * Reading a line the way a person does. A voice model says every sentence "correctly", and that is what makes a
 * long passage sound read out: the same pace, the same height, the same gap, sentence after sentence. A person
 * varies all three, leans into an exclamation, lets a trailing thought slow down and takes a little air before a
 * long sentence. This plans those differences, for any language; the model still speaks every word.
 *
 * Everything is repeatable: the same text and voice are always read the same way.
 */
import type { Tone } from "./open";
import { toChunks } from "./text";

/** Small repeatable random numbers (0…1) from a string. */
export function seeded(text: string): () => number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  let a = h >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface Reading {
  text: string;
  /** Silence after this sentence, in milliseconds. */
  pauseMs: number;
  tone: Tone;
  /** Loudness against the other sentences of the line (1 = the same). */
  gain: number;
  /** A breath is taken before this sentence (only when breaths are asked for). */
  breath?: boolean;
}

const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v));

/** The way one sentence is said, from how it is written: its ending, its place in the passage, its length. */
export function performed(sentence: string, base: Tone, k: number, n: number, rnd: () => number): { tone: Tone; gain: number } {
  const t: Tone = { ...base };
  const s = sentence.trim();
  const end = s.match(/([.!?…]+)["')\]»”]*$/)?.[1] ?? "";
  const words = (s.match(/[\p{L}\p{N}]+/gu) ?? []).length;
  const mul = (key: "range" | "rate" | "pauses", f: number) => (t[key] = (t[key] ?? 1) * f);
  const add = (key: "pitch" | "energy", d: number) => (t[key] = (t[key] ?? 0) + d);
  let gain = 1;
  if (end.includes("!") && end.includes("?")) {
    // disbelief: "What?!"
    mul("range", 1.25);
    add("energy", 0.25);
    t.ending ??= "rise";
  } else if (/!!/.test(end)) {
    mul("range", 1.3);
    add("energy", 0.45);
    add("pitch", 0.6);
    gain = 1.08;
  } else if (end.includes("!")) {
    mul("range", 1.15);
    add("energy", 0.25);
    add("pitch", 0.7);
    gain = 1.04;
  } else if (/…|\.\.\./.test(end)) {
    // a thought trailing off
    mul("rate", 0.95);
    mul("pauses", 1.25);
    t.ending ??= "level";
    gain = 0.95;
  } else if (end.includes("?")) {
    mul("range", 1.08);
  }
  // no two sentences at exactly the same pace and height
  mul("rate", 1 + (rnd() - 0.5) * 0.05);
  // a passage starts a little higher and settles as it goes on
  add("pitch", (rnd() - 0.5) * 0.5 + (n > 1 ? 0.25 - Math.min(0.75, k * 0.18) : 0));
  // a word or two on their own ("No.", "Wait!") are not hurried
  if (words <= 2) mul("rate", 0.94);
  if (t.energy !== undefined) t.energy = clamp(t.energy, -1, 1);
  t.drift = (base.drift ?? 1) * (0.6 + rnd() * 0.8);
  t.seed = Math.floor(rnd() * 1e6);
  return { tone: t, gain };
}

/**
 * A line as the sentences it is read in. `world` keeps the text as written (numbers and abbreviations are read by
 * that language's own rules); otherwise it gets the English / Hindi treatment.
 */
export function reading(text: string, base: Tone, seedText: string, o: { world?: boolean; breath?: number } = {}): Reading[] {
  const chunks = toChunks(text, undefined, o.world);
  if (!chunks.length) return [];
  const rnd = seeded(seedText);
  const out: Reading[] = chunks.map((c, k) => {
    const p = performed(c.text, base, k, chunks.length, rnd);
    const last = k === chunks.length - 1;
    return { text: c.text, pauseMs: last ? 0 : Math.round(c.pauseMs * (p.tone.pauses ?? 1) * (0.88 + rnd() * 0.3)), tone: p.tone, gain: p.gain };
  });
  if ((o.breath ?? 0) > 0)
    for (let k = 1; k < out.length; k++) {
      // air is taken before a sentence of some length, when the one before it left room
      const long = (out[k]!.text.match(/[\p{L}\p{N}]+/gu) ?? []).length >= 7;
      if (long && out[k - 1]!.pauseMs >= 300) {
        out[k]!.breath = true;
        out[k - 1]!.pauseMs = Math.max(out[k - 1]!.pauseMs, 430);
      }
    }
  return out;
}

/**
 * A quiet breath in (24 kHz mono): noise shaped like air through an open mouth, rising and cut short as the
 * first word starts. `level` is its peak against a voice at full scale (about 0.02…0.05).
 */
export function breathIn(seconds: number, level: number, rnd: () => number, sampleRate = 24000): Float32Array {
  const n = Math.round(seconds * sampleRate);
  const out = new Float32Array(n);
  // two soft resonances and a little air above them
  const bands = [
    { f: 950 + rnd() * 200, q: 1.6, g: 1 },
    { f: 2300 + rnd() * 400, q: 2.2, g: 0.7 },
    { f: 4800, q: 1.2, g: 0.28 },
  ].map((b) => {
    const w = (2 * Math.PI * b.f) / sampleRate, alpha = Math.sin(w) / (2 * b.q);
    return { b0: alpha / (1 + alpha), a1: (-2 * Math.cos(w)) / (1 + alpha), a2: (1 - alpha) / (1 + alpha), g: b.g, x1: 0, x2: 0, y1: 0, y2: 0 };
  });
  for (let i = 0; i < n; i++) {
    const u = i / n;
    // slow swell, quick close
    const env = Math.pow(Math.sin(Math.PI * Math.min(1, u / 0.72) * 0.5), 1.6) * (u > 0.72 ? Math.pow(1 - (u - 0.72) / 0.28, 1.5) : 1);
    const x = rnd() * 2 - 1;
    let y = 0;
    for (const b of bands) {
      const v = b.b0 * x - b.b0 * b.x2 - b.a1 * b.y1 - b.a2 * b.y2;
      b.x2 = b.x1;
      b.x1 = x;
      b.y2 = b.y1;
      b.y1 = v;
      y += v * b.g;
    }
    out[i] = y * env;
  }
  let peak = 0;
  for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(out[i]!));
  if (peak > 0) for (let i = 0; i < n; i++) out[i] = (out[i]! / peak) * level;
  return out;
}

/** Join the spoken sentences with their pauses (and breaths) into one take. */
export function joinReading(parts: { pcm: Float32Array; pauseMs: number; gain: number; breath?: boolean }[], o: { breath?: number; seedText?: string } = {}, sampleRate = 24000): Float32Array {
  const rnd = seeded(`${o.seedText ?? ""}#air`);
  let total = 0;
  for (const p of parts) total += p.pcm.length + Math.round((p.pauseMs / 1000) * sampleRate);
  const out = new Float32Array(total);
  let at = 0;
  for (const p of parts) {
    if (p.breath && (o.breath ?? 0) > 0 && at > 0) {
      const air = breathIn(0.24 + rnd() * 0.14, 0.035 * clamp(o.breath!, 0, 1.5), rnd, sampleRate);
      const start = Math.max(0, at - air.length - Math.round(0.03 * sampleRate));
      for (let i = 0; i < air.length && start + i < at; i++) out[start + i] = out[start + i]! + air[i]!;
    }
    for (let i = 0; i < p.pcm.length; i++) out[at + i] = p.pcm[i]! * p.gain;
    at += p.pcm.length + Math.round((p.pauseMs / 1000) * sampleRate);
  }
  return out;
}
