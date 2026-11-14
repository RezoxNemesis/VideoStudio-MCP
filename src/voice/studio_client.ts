/**
 * The window's side of VolTune Studio (desktop only): natural, acted speech for any VolTune voice from
 * Chatterbox Multilingual running in a desktop worker (desktop/electron/voice-studio.cjs).
 *
 * A voice and mood become a "speaker" once: VolTune renders a short reference line in that voice and mood
 * (studio_refs.ts) and the worker keeps what the model needs from it. After that every line in that voice
 * and mood is acted by the studio model. When the studio is not installed or switched off, callers fall
 * back to the standard VolTune voice.
 */
import { lookupVoiceRef } from "./engine/plan";
import { mixLang, parseMix, resolveVoice } from "./engine/voicebank";
import { hinglishShare, transliterateHinglish } from "./engine/hinglish";
import { moodTone, type Tone } from "./engine/open";
import { seedFor } from "./engine/studio";
import { referenceLine } from "./engine/studio_refs";
import { devanagariLoanwords } from "./engine/studio_loanwords";
import { loadCustoms } from "./voicelab";
import { openVoice } from "./open_client";

interface StudioBridge {
  status(): Promise<{ available: boolean; installed?: boolean; downloadBytes?: number; totalBytes?: number }>;
  install(): Promise<unknown>;
  say(p: { key: string; text: string; lang: string; mood?: string; moodAmount?: number }): Promise<{ pcm?: Float32Array; needRef?: boolean }>;
  speaker(p: { key: string; pcm: Float32Array }): Promise<boolean>;
  onProgress(fn: (m: { done: number; total?: number; what: string }) => void): () => void;
}

const bridge = (): StudioBridge | null => ((globalThis as { vsd?: { studio?: StudioBridge } }).vsd?.studio ?? null);
const KEY = "vs.voiceEngine";

/** Chatterbox languages that need no extra text tools. */
const LANGS = new Set(["ar", "da", "de", "el", "en", "es", "fi", "fr", "hi", "it", "ms", "nl", "no", "pl", "pt", "sv", "sw", "tr"]);

export type VoiceEngine = "studio" | "standard";
export function voiceEngine(): VoiceEngine {
  try {
    return localStorage.getItem(KEY) === "standard" ? "standard" : "studio";
  } catch {
    return "studio";
  }
}
export function setVoiceEngine(e: VoiceEngine): void {
  try {
    localStorage.setItem(KEY, e);
  } catch {
    /* storage unavailable: the default stays */
  }
}

let installed: boolean | null = null;
export async function studioStatus(): Promise<{ available: boolean; installed: boolean; downloadBytes: number; engine: VoiceEngine }> {
  const b = bridge();
  if (!b) return { available: false, installed: false, downloadBytes: 0, engine: voiceEngine() };
  const s = await b.status();
  installed = !!s.installed;
  return { available: s.available, installed, downloadBytes: s.downloadBytes ?? 0, engine: voiceEngine() };
}

/** Download the studio model (≈1.5 GB, once). */
export async function installStudio(onProgress?: (done: number, what: string) => void): Promise<void> {
  const b = bridge();
  if (!b) throw new Error("Studio voices need the desktop app.");
  const off = b.onProgress((m) => onProgress?.(m.total ? m.done / m.total : 0, m.what));
  try {
    await b.install();
    installed = true;
  } finally {
    off();
  }
}

/**
 * Say one line with the studio engine, or return null when it is not available here (not the desktop app,
 * not installed, switched off, or a language it cannot read) so the caller uses the standard voice.
 */
export async function studioSay(text: string, voice: string, tone?: Tone, lang?: string, onProgress?: (done: number, what: string) => void): Promise<{ pcm: Float32Array; voice: string } | null> {
  const b = bridge();
  if (!b || voiceEngine() !== "studio") return null;
  if (installed === null) await studioStatus().catch(() => (installed = false));
  if (!installed) return null;
  const customs = loadCustoms();
  const mix = resolveVoice(lookupVoiceRef(voice, customs), customs).mix;
  const vlang = mixLang(parseMix(mix));
  let code = (lang ?? "").toLowerCase().split(/[-_]/)[0] ?? "";
  let words = text;
  if (!code) {
    // A Hindi voice reads Devanagari and Hinglish as Hindi; English lines stay English (with its accent).
    const hindi = /[ऀ-ॿ]/.test(text) || (vlang === "h" && hinglishShare(text) >= 0.34);
    code = hindi ? "hi" : "en";
    // one script reads best: Hinglish to Devanagari, common English words too
    if (hindi) words = devanagariLoanwords(transliterateHinglish(text, true));
  }
  if (!LANGS.has(code)) return null;
  const mood = tone?.mood;
  const female = /^[a-z]f_/.test(parseMix(mix)[0]?.[0] ?? mix);
  const key = `v1-${seedFor(mix, mood ?? "neutral").toString(36)}`;
  const off = b.onProgress((m) => onProgress?.(m.total ? m.done / m.total : 0, m.what));
  try {
    let r = await b.say({ key, text: words, lang: code, mood, moodAmount: tone?.moodAmount });
    if (r.needRef) {
      onProgress?.(0, "Preparing the voice");
      const ref = await openVoice.say(referenceLine(vlang, mood, female), mix, mood ? moodTone(mood) : undefined);
      await b.speaker({ key, pcm: ref.pcm });
      r = await b.say({ key, text: words, lang: code, mood, moodAmount: tone?.moodAmount });
    }
    return r.pcm?.length ? { pcm: r.pcm, voice: mix } : null;
  } finally {
    off();
  }
}
