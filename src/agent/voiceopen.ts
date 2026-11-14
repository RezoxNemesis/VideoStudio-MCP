/**
 * Voice commands on the open-prosody engine: a line said with a tone (mood, emphasis, a question's rise), Hindi typed
 * in Latin letters, English with Indian sounds; and a helper the Stage uses so characters can speak that way.
 */
import { langOf } from "../voice/engine/world";
import type { Studio } from "../app/studio";
import type { Asset } from "../core/model";
import * as ops from "../core/ops";
import { registerCommand } from "./api";
import { SAMPLE_RATE, mouthEnvelope } from "../voice/engine/audio";
import { TONES, type Tone, knowsTone, moodTone } from "../voice/engine/open";
import { readInstruction } from "../voice/engine/instruct";
import { installStudio, setVoiceEngine, studioSay, studioStatus } from "../voice/studio_client";
import { hinglishShare, transliterateHinglish } from "../voice/engine/hinglish";
import { openVoice } from "../voice/open_client";
import { isMood } from "../voice/engine/learned";

function wavBlob(pcm: Float32Array): Blob {
  const n = pcm.length;
  const out = new Uint8Array(44 + n * 2);
  const dv = new DataView(out.buffer);
  const wr = (o: number, s: string) => { for (let i = 0; i < s.length; i++) out[o + i] = s.charCodeAt(i); };
  wr(0, "RIFF");
  dv.setUint32(4, 36 + n * 2, true);
  wr(8, "WAVEfmt ");
  dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true);
  dv.setUint16(22, 1, true);
  dv.setUint32(24, SAMPLE_RATE, true);
  dv.setUint32(28, SAMPLE_RATE * 2, true);
  dv.setUint16(32, 2, true);
  dv.setUint16(34, 16, true);
  wr(36, "data");
  dv.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) dv.setInt16(44 + i * 2, Math.max(-32768, Math.min(32767, Math.round(pcm[i]! * 32767))), true);
  return new Blob([out], { type: "audio/wav" });
}

/** A tone given by name, as an object, or both (name plus overrides). */
export function readTone(v: unknown): Tone | undefined {
  if (v === undefined || v === null || v === "") return undefined;
  if (typeof v === "string") {
    if (!knowsTone(v)) throw new ops.OpError(`Unknown tone "${v}". Tones: ${Object.keys(TONES).join(", ")}; plain words such as "emotional hoke" or "bahut udaas" also work; or give {pitch, range, rate, energy, ending, even, shake, pauses, mood}.`);
    // tones that are emotions are acted by the model itself (learned moods), the curve shaping adds to it
    return moodTone(v);
  }
  if (typeof v === "object") {
    const o = v as Record<string, unknown>;
    const base = typeof o.like === "string" ? moodTone(o.like) : {};
    const t: Tone = { ...base };
    if (typeof o.mood === "string" && isMood(o.mood)) {
      t.mood = o.mood;
      t.moodAmount = typeof o.moodAmount === "number" ? Math.max(0, Math.min(2, o.moodAmount)) : 1;
    }
    for (const k of ["pitch", "range", "rate", "energy", "even", "shake", "pauses", "natural", "breath", "drift"] as const) if (typeof o[k] === "number") t[k] = o[k] as number;
    if (o.ending === "rise" || o.ending === "fall" || o.ending === "level") t.ending = o.ending;
    return t;
  }
  return undefined;
}

/**
 * A line written with a plain-language instruction ("is dialogue ko emotional hoke bolna hai: Kaash …")
 * becomes the words to say plus the tone it asks for; a tone given separately wins.
 */
export function directLine(text: string, tone?: Tone): { text: string; tone?: Tone } {
  const ins = readInstruction(text);
  if (!ins || !ins.spoken) return { text, tone };
  return { text: ins.spoken, tone: tone ?? moodTone(ins.mark) };
}

/** Say one line with the open voice and add it to the media pool (with its mouth movement for lip sync). */
export async function sayToAsset(s: Studio, line: string, voice: string, toneIn?: Tone, emphasis?: string[], lang?: string): Promise<{ asset: Asset; seconds: number; voice: string }> {
  const { text, tone } = directLine(line, toneIn);
  s.setStatus("Speaking…");
  openVoice.onProgress = (d, what) => s.setStatus(`${what}… ${Math.round(d * 100)}%`);
  try {
    // VolTune Studio (desktop): natural acting; the standard voice when it is not installed or switched off.
    const studio = await studioSay(text, voice, tone, lang, (d, what) => s.setStatus(`${what}… ${Math.round(d * 100)}%`));
    const { pcm, voice: used } = studio ?? (await openVoice.say(text, voice, tone, emphasis, lang));
    if (!pcm.length) throw new ops.OpError("The voice returned no sound for this line.");
    let peak = 0;
    for (let i = 0; i < pcm.length; i++) peak = Math.max(peak, Math.abs(pcm[i]!));
    if (peak > 0) for (let i = 0; i < pcm.length; i++) pcm[i] = pcm[i]! * Math.min(3, 0.85 / peak);
    const label = text.trim().split(/\s+/).slice(0, 5).join(" ");
    const asset = await s.addBlobAsset(wavBlob(pcm), `Voice – ${label}.wav`, "voice", { mouth: mouthEnvelope(pcm, 30) });
    return { asset, seconds: pcm.length / SAMPLE_RATE, voice: used };
  } finally {
    s.setStatus("");
  }
}

export function registerOpenVoiceCommands(): void {
  registerCommand("voice.tones", "The tones a line can be said in, with what each changes.", () => ({
    tones: TONES,
    fields: { pitch: "semitones up/down", range: "how wide the melody swings (1 natural)", rate: "speed (1 natural)", energy: "-1 hushed … 1 forceful", ending: "rise | fall | level", even: "0…1 even syllables (Hindi / Indian English rhythm)", shake: "0…1 trembling", pauses: "longer pauses at commas and stops", natural: "1 (default) natural reading: sentence by sentence, each a little different, the writing shapes the delivery; 0 exactly as the model says it", breath: "0…1 breaths before longer sentences (0 = none)", drift: "slow wandering of the pitch (1 natural, 0 none)" },
  }));

  registerCommand(
    "voice.say",
    `{text, voice?="hf_alpha" (Hindi voices: hf_alpha, hf_beta, hm_omega, hm_psi; any other voice works too), lang?: de|ru|es|fr|it|pt|… (voice.languages; empty = from the voice and the letters), tone?: ${Object.keys(TONES).join("|")} or {like?: <tone>, pitch?, range?, rate?, energy?, ending?: rise|fall|level, even?, shake?, pauses?}, emphasis?: ["word", …], toTimeline?=true, start?, volume?} Say one line with control over how it is said: mood, a question's rise, words to lean on. Reads Hindi (Devanagari), Hinglish (Hindi typed in English letters, mixed with English) and English; a Hindi voice gives English words Indian sounds. Returns {assetId, clipId, seconds, voice, readAs}.`,
    async (s, a) => {
      const text = a.str("text").trim();
      if (!text) throw new ops.OpError("There is no text to say.");
      const em = a.any("emphasis");
      const { asset, seconds, voice } = await sayToAsset(s, text, a.str("voice", a.optStr("lang") ? (langOf(a.optStr("lang"))?.voice ?? "hf_alpha") : "hf_alpha"), readTone(a.any("tone")), Array.isArray(em) ? em.map(String) : undefined, a.optStr("lang"));
      let clipId: string | undefined;
      if (a.bool("toTimeline", true))
        clipId = s.mutate("place voice", (p) => {
          const c = ops.addClip(p, { assetId: asset.id, start: a.num("start", s.time) });
          if (a.optNum("volume") !== undefined) ops.patchClip(p, c.id, { volume: a.num("volume") });
          return c.id;
        });
      return { assetId: asset.id, clipId, seconds: Math.round(seconds * 100) / 100, voice, readAs: !langOf(a.optStr("lang")) && hinglishShare(text) >= 0.12 ? transliterateHinglish(text) : undefined };
    },
  );

  registerCommand(
    "voice.studio",
    `{install?: true, engine?: "studio"|"standard"} VolTune Studio voices (desktop app): natural, acted delivery for every VolTune voice (Chatterbox Multilingual, run on this PC). The model is downloaded once (about 1.5 GB) with install:true. engine picks what voice.say, the Stage and films use; "studio" falls back to the standard voice until it is installed. Returns {available, installed, downloadBytes, engine}.`,
    async (s, a) => {
      if (a.optStr("engine")) setVoiceEngine(a.str("engine") === "standard" ? "standard" : "studio");
      if (a.bool("install", false)) {
        await installStudio((d, what) => s.setStatus(`${what}… ${Math.round(d * 100)}%`));
        s.setStatus("");
      }
      return studioStatus();
    },
  );

  registerCommand("voice.hinglish", "{text} How a line typed in English letters will be read: the Hindi words in Devanagari, the English ones left as they are, and how Hindi the line is (0…1).", (_s, a) => {
    const text = a.str("text");
    return { hindi: Math.round(hinglishShare(text) * 100) / 100, readAs: transliterateHinglish(text) };
  });
}
