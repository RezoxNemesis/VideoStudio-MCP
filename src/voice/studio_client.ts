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
import { moodTone, TONES, type Tone } from "./engine/open";
import { peakFor, shapeStudio, swellOf } from "./engine/studio_shape";
import { GOOD_TAKE, MAX_TAKES, heardError } from "./engine/studio_check";
import { transcribe } from "../media/asr";
import { referenceLine } from "./engine/studio_refs";
import { devanagariLoanwords } from "./engine/studio_loanwords";
import { NATURAL_PACE, NATURAL_PACE_HI, phrases, pacing } from "./engine/studio_pace";
import { cutTail, innerPauses, tailPiece } from "./engine/studio_pause";
import { studioSpeaker } from "./engine/studio_speaker";
import { bankKey, bankVoice } from "./engine/studio_bank";
import { loadCustoms } from "./voicelab";
import { openVoice } from "./open_client";

interface StudioBridge {
  status(): Promise<{ available: boolean; installed?: boolean; downloadBytes?: number; totalBytes?: number }>;
  install(): Promise<unknown>;
  say(p: { key: string; text: string; lang: string; mood?: string; moodAmount?: number; cfg?: number; take?: number; bank?: string }): Promise<{ pcm?: Float32Array; stops?: number[]; needRef?: boolean }>;
  speaker(p: { key: string; pcm: Float32Array }): Promise<boolean>;
  revoice?(p: { key: string; pcm: Float32Array; bank?: string }): Promise<{ pcm?: Float32Array; needRef?: boolean }>;
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
/**
 * A recorded performance said in a VolTune voice (engine/studio.ts revoice): the timing, the stresses and the pauses stay the
 * performer's, the voice becomes the character's. `pcm`: mono, 24 kHz. The answer is as long as the recording, to the sample.
 * null when the studio voice is not installed or this build has no re-voicing.
 */
export async function studioRevoice(pcm: Float32Array, voice: string, tone?: Tone, onProgress?: (done: number, what: string) => void): Promise<{ pcm: Float32Array; voice: string } | null> {
  const b = bridge();
  if (!b || !b.revoice || voiceEngine() !== "studio") return null;
  if (installed === null) await studioStatus().catch(() => (installed = false));
  if (!installed) return null;
  const customs = loadCustoms();
  const own = resolveVoice(lookupVoiceRef(voice, customs), customs);
  const mix = own.mix, vlang = mixLang(parseMix(mix));
  const female = own.gender ? own.gender === "Female" : /^[a-z]f_/.test(parseMix(mix)[0]?.[0] ?? mix);
  const spk = studioSpeaker({ mix, pitch: own.pitch }, tone?.mood, tone);
  const real = bankVoice(vlang, female, spk.pitch, mix);
  const key = real ? `v3-${bankKey(real)}` : spk.key, bank = real?.file;
  const off = b.onProgress((m) => onProgress?.(m.total ? m.done / m.total : 0, m.what));
  try {
    let r = await b.revoice({ key, pcm, ...(bank ? { bank } : {}) });
    if (r.needRef) {
      onProgress?.(0, "Preparing the voice");
      const ref = await openVoice.say(referenceLine(vlang, tone?.mood, female), voice, Object.keys(spk.tone).length ? spk.tone : undefined);
      await b.speaker({ key, pcm: ref.pcm });
      r = await b.revoice({ key, pcm, ...(bank ? { bank } : {}) });
    }
    return r.pcm?.length ? { pcm: r.pcm, voice } : null;
  } finally { off(); }
}

/**
 * The same, in the voice of a SAMPLE: a few seconds of the voice itself (a character's earlier line, a clip from a finished
 * film, a recording), 24 kHz mono. `key` names the voice, so the same sample is prepared once.
 * Null when VolTune Studio is not installed or not the engine in use.
 */
export async function studioRevoiceLike(pcm: Float32Array, sample: Float32Array, key: string, onProgress?: (done: number, what: string) => void): Promise<{ pcm: Float32Array; voice: string } | null> {
  const b = bridge();
  if (!b || !b.revoice || voiceEngine() !== "studio") return null;
  if (installed === null) await studioStatus().catch(() => (installed = false));
  if (!installed) return null;
  const off = b.onProgress((m) => onProgress?.(m.total ? m.done / m.total : 0, m.what));
  try {
    let r = await b.revoice({ key, pcm });
    if (r.needRef) {
      onProgress?.(0, "Preparing the voice");
      await b.speaker({ key, pcm: sample });
      r = await b.revoice({ key, pcm });
    }
    return r.pcm?.length ? { pcm: r.pcm, voice: key } : null;
  } finally { off(); }
}

export async function studioSay(text: string, voice: string, tone?: Tone, lang?: string, onProgress?: (done: number, what: string) => void): Promise<{ pcm: Float32Array; voice: string; pace: StudioPace; peak: number } | null> {
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
    const pace = { rate: (tone?.rate ?? 1) * (own.speed || 1), pauses: tone?.pauses, natural: code === "hi" ? NATURAL_PACE_HI : NATURAL_PACE };
    const list = phrases(words);
    // (A slow line is not asked for with a weaker hold on its words, as Chatterbox advises for a slower delivery:
    //  measured, the model's pace does not follow it, and with less of it the model murmurs for seconds, skips words
    //  and runs sentences together. Of eight Hindi sentences five were clean at the first attempt, against seven.)
    const sayOne = (t: string, take = 0) => b.say({ key, text: t, lang: code, mood, moodAmount: tone?.moodAmount, ...((tone?.take ?? 0) + take ? { take: (tone?.take ?? 0) + take } : {}), ...(bank ? { bank } : {}) });
    let r = await sayOne(list[0] ?? words);
    if (r.needRef) {
      onProgress?.(0, "Preparing the voice");
      // the reference is spoken by the voice itself (a character voice brings its own pitch and pace)
      const ref = await openVoice.say(referenceLine(vlang, mood, female), voice, Object.keys(spk.tone).length ? spk.tone : undefined);
      await b.speaker({ key, pcm: ref.pcm });
      r = await sayOne(list[0] ?? words);
    }
    if (!r.pcm?.length) return null;
    // every sentence is listened back to (studio_check.ts): a take that strays from its words is made again
    // (the take that is kept brings where its inner marks are in its sound, for the pauses inside the sentence: studio_pause.ts)
    type Take = { pcm: Float32Array; stops?: number[] };
    const checked = async (t: string, first: Take): Promise<Take> => {
      let best = first, err = await heard(first.pcm, t, code), k = 1;
      for (; k < MAX_TAKES && err > GOOD_TAKE; k++) {
        onProgress?.(0, `Saying it again (take ${k + 1})`);
        const x = await sayOne(t, k);
        if (!x.pcm?.length) continue;
        const e = await heard(x.pcm, t, code);
        if (e < err) { best = { pcm: x.pcm, stops: x.stops }; err = e; }
      }
      // A good take can still run on through a pause the line asks for ("you — not" said as one breath): the pause cannot
      // be cut into running speech, so with takes left another is made, and kept if it is as true to its words and
      // holds more of the line's pauses.
      const runsOn = (x: Take) => innerPauses(x.pcm, t, pace, 24000, x.stops).missed ?? 0;
      let miss = err <= GOOD_TAKE ? runsOn(best) : 0;
      for (; miss > 0 && k < MAX_TAKES; k++) {
        onProgress?.(0, `Saying it again for its pauses (take ${k + 1})`);
        const x = await sayOne(t, k);
        if (!x.pcm?.length) continue;
        const e = await heard(x.pcm, t, code);
        if (e > GOOD_TAKE) continue;
        const m = runsOn({ pcm: x.pcm, stops: x.stops });
        if (m < miss) { best = { pcm: x.pcm, stops: x.stops }; err = e; miss = m; }
      }
      // A short piece of sound alone at the end, after a silence, may be the last word said twice (Whisper writes such an
      // echo once, so the letters pass). The take is heard again without the piece: if every word is still there, the
      // piece was an echo and is cut; if the last word is now missing, it was the word itself and stays.
      const tail = listening === true ? tailPiece(best.pcm, 24000) : null;
      if (tail) {
        const without = cutTail(best.pcm, tail.cut, 24000), e = await heard(without, t, code);
        if (e <= err + 1e-6) { best = { pcm: without, stops: best.stops }; err = e; }
      }
      return best;
    };
    const takes = [await checked(list[0] ?? words, { pcm: r.pcm, stops: r.stops })];
    for (const t of list.slice(1)) {
      const x = await sayOne(t);
      if (!x.pcm?.length) return null;
      takes.push(await checked(t, { pcm: x.pcm, stops: x.stops }));
    }
    const { pcm, ...how } = pacing(takes.map((t) => t.pcm), list, pace, 24000, takes.map((t) => t.stops));
    // the rest of the tone, worked onto the acted speech: what a line asks beyond its mood (higher, louder, a rising
    // end, a tremble); a mood's own curves are the model's to act (studio_shape.ts)
    const own0 = mood ? TONES[mood] ?? {} : {};
    const moodPitch = mood ? moodTone(mood).pitch ?? 0 : 0;
    const extra = (k: "ending" | "shake") => (tone?.[k] !== undefined && tone[k] !== own0[k] ? tone[k] : undefined);
    const shaped = shapeStudio(pcm, {
      pitch: tone?.pitch !== undefined ? tone.pitch - moodPitch : 0,
      energy: tone?.energy,
      ending: extra("ending") as "rise" | "fall" | "level" | undefined ?? (/\?\s*$/.test(words) && !mood ? "rise" : undefined),
      shake: (extra("shake") as number | undefined) ?? (tone?.shake ? tone.shake * 0.5 : 0),
      swell: swellOf(words),
    });
    return { pcm: shaped, voice: mix, pace: how, peak: peakFor(tone?.energy) };
  } finally {
    off();
  }
}

/** Can takes be listened back to here (the desktop app has Whisper on the graphics card)? Off: every take is kept. */
let listening: boolean | null = null;
/** How far a take strays from its words (0 exact; 0 when it cannot be heard here, so the take is kept). */
async function heard(pcm: Float32Array, meant: string, lang: string): Promise<number> {
  if (listening === false) return 0;
  try {
    const n = Math.floor((pcm.length * 2) / 3), x = new Float32Array(n); // 24 kHz → 16 kHz
    for (let i = 0; i < n; i++) { const p = i * 1.5, j = Math.floor(p), f = p - j; x[i] = pcm[j]! * (1 - f) + (pcm[j + 1] ?? pcm[j]!) * f; }
    const r = await transcribe(x, { model: "turbo", device: "webgpu", language: lang === "hi" ? "hindi" : "english" });
    listening = true;
    return heardError(meant, r.text);
  } catch {
    // no graphics card for Whisper (or it failed): the takes are not checked from here on
    listening = false;
    return 0;
  }
}
