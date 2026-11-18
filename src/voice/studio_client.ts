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
import type { Tone } from "./engine/open";
import { referenceLine } from "./engine/studio_refs";
import { devanagariLoanwords } from "./engine/studio_loanwords";
import { phrases, pacing } from "./engine/studio_pace";
import { studioSpeaker } from "./engine/studio_speaker";
import { bankKey, bankVoice } from "./engine/studio_bank";
import { loadCustoms } from "./voicelab";
import { openVoice } from "./open_client";

interface StudioBridge {
  status(): Promise<{ available: boolean; installed?: boolean; downloadBytes?: number; totalBytes?: number }>;
  install(): Promise<unknown>;
  say(p: { key: string; text: string; lang: string; mood?: string; moodAmount?: number; cfg?: number; take?: number; bank?: string }): Promise<{ pcm?: Float32Array; needRef?: boolean }>;
  speaker(p: { key: string; pcm: Float32Array }): Promise<boolean>;
  onProgress(fn: (m: { done: number; total?: number; what: string }) => void): () => void;
}

/** How a studio line was spoken: its words, the seconds of speech the model gave, the stretch its pace asked for and the one it got. */
export interface StudioPace { words: number; spoken: number; asked: number; factor: number }

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
export async function studioSay(text: string, voice: string, tone?: Tone, lang?: string, onProgress?: (done: number, what: string) => void): Promise<{ pcm: Float32Array; voice: string; pace: StudioPace } | null> {
  const b = bridge();
  if (!b || voiceEngine() !== "studio") return null;
  if (installed === null) await studioStatus().catch(() => (installed = false));
  if (!installed) return null;
  const customs = loadCustoms();
  const own = resolveVoice(lookupVoiceRef(voice, customs), customs);
  const mix = own.mix;
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
  const female = own.gender ? own.gender === "Female" : /^[a-z]f_/.test(parseMix(mix)[0]?.[0] ?? mix);
  // a character voice has a pitch of its own, a line may add to it: each is a speaker of its own (studio_speaker.ts)
  const spk = studioSpeaker({ mix, pitch: own.pitch }, mood, tone);
  // A real reader of the voice's language, gender and register speaks it (studio_bank.ts); the mood is acted by the
  // model (exaggeration), so one speaker serves every mood. A voice no reader fits (a child) keeps its VolTune clip.
  const real = bankVoice(vlang, female, spk.pitch, mix);
  const key = real ? `v3-${bankKey(real)}` : spk.key;
  const bank = real?.file;
  const off = b.onProgress((m) => onProgress?.(m.total ? m.done / m.total : 0, m.what));
  try {
    // The line keeps its pace: it is spoken a sentence at a time (the model runs short sentences together and
    // hurries a long line), joined with the silences its marks ask for, and brought to the tone's rate.
    const pace = { rate: (tone?.rate ?? 1) * (own.speed || 1), pauses: tone?.pauses };
    const list = phrases(words);
    // (A slow line is not asked for with a weaker hold on its words, as Chatterbox advises for a slower delivery:
    //  measured, the model's pace does not follow it, and with less of it the model murmurs for seconds, skips words
    //  and runs sentences together. Of eight Hindi sentences five were clean at the first attempt, against seven.)
    const sayOne = (t: string) => b.say({ key, text: t, lang: code, mood, moodAmount: tone?.moodAmount, ...(tone?.take ? { take: tone.take } : {}), ...(bank ? { bank } : {}) });
    let r = await sayOne(list[0] ?? words);
    if (r.needRef) {
      onProgress?.(0, "Preparing the voice");
      // the reference is spoken by the voice itself (a character voice brings its own pitch and pace)
      const ref = await openVoice.say(referenceLine(vlang, mood, female), voice, Object.keys(spk.tone).length ? spk.tone : undefined);
      await b.speaker({ key, pcm: ref.pcm });
      r = await sayOne(list[0] ?? words);
    }
    if (!r.pcm?.length) return null;
    const parts = [r.pcm];
    for (const t of list.slice(1)) {
      const x = await sayOne(t);
      if (!x.pcm?.length) return null;
      parts.push(x.pcm);
    }
    const { pcm, ...how } = pacing(parts, list, pace);
    return { pcm, voice: mix, pace: how };
  } finally {
    off();
  }
}
