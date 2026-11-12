/**
 * Voice commands on the open-prosody engine: a line said with a tone (mood, emphasis, a question's rise), Hindi typed
 * in Latin letters, English with Indian sounds; and a helper the Stage uses so characters can speak that way.
 */
import type { Studio } from "../app/studio";
import type { Asset } from "../core/model";
import * as ops from "../core/ops";
import { registerCommand } from "./api";
import { SAMPLE_RATE, mouthEnvelope } from "../voice/engine/audio";
import { TONES, type Tone } from "../voice/engine/open";
import { hinglishShare, transliterateHinglish } from "../voice/engine/hinglish";
import { openVoice } from "../voice/open_client";

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
    const t = TONES[v.toLowerCase()];
    if (!t) throw new ops.OpError(`Unknown tone "${v}". Tones: ${Object.keys(TONES).join(", ")}; or give {pitch, range, rate, energy, ending, even, shake, pauses}.`);
    return t;
  }
  if (typeof v === "object") {
    const o = v as Record<string, unknown>;
    const base = typeof o.like === "string" ? (TONES[o.like.toLowerCase()] ?? {}) : {};
    const t: Tone = { ...base };
    for (const k of ["pitch", "range", "rate", "energy", "even", "shake", "pauses"] as const) if (typeof o[k] === "number") t[k] = o[k] as number;
    if (o.ending === "rise" || o.ending === "fall" || o.ending === "level") t.ending = o.ending;
    return t;
  }
  return undefined;
}

/** Say one line with the open voice and add it to the media pool (with its mouth movement for lip sync). */
export async function sayToAsset(s: Studio, text: string, voice: string, tone?: Tone, emphasis?: string[]): Promise<{ asset: Asset; seconds: number; voice: string }> {
  s.setStatus("Speaking…");
  openVoice.onProgress = (d, what) => s.setStatus(`${what}… ${Math.round(d * 100)}%`);
  try {
    const { pcm, voice: used } = await openVoice.say(text, voice, tone, emphasis);
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
    fields: { pitch: "semitones up/down", range: "how wide the melody swings (1 natural)", rate: "speed (1 natural)", energy: "-1 hushed … 1 forceful", ending: "rise | fall | level", even: "0…1 even syllables (Hindi / Indian English rhythm)", shake: "0…1 trembling", pauses: "longer pauses at commas and stops" },
  }));

  registerCommand(
    "voice.say",
    `{text, voice?="hf_alpha" (Hindi voices: hf_alpha, hf_beta, hm_omega, hm_psi; any other voice works too), tone?: ${Object.keys(TONES).join("|")} or {like?: <tone>, pitch?, range?, rate?, energy?, ending?: rise|fall|level, even?, shake?, pauses?}, emphasis?: ["word", …], toTimeline?=true, start?, volume?} Say one line with control over how it is said: mood, a question's rise, words to lean on. Reads Hindi (Devanagari), Hinglish (Hindi typed in English letters, mixed with English) and English; a Hindi voice gives English words Indian sounds. Returns {assetId, clipId, seconds, voice, readAs}.`,
    async (s, a) => {
      const text = a.str("text").trim();
      if (!text) throw new ops.OpError("There is no text to say.");
      const em = a.any("emphasis");
      const { asset, seconds, voice } = await sayToAsset(s, text, a.str("voice", "hf_alpha"), readTone(a.any("tone")), Array.isArray(em) ? em.map(String) : undefined);
      let clipId: string | undefined;
      if (a.bool("toTimeline", true))
        clipId = s.mutate("place voice", (p) => {
          const c = ops.addClip(p, { assetId: asset.id, start: a.num("start", s.time) });
          if (a.optNum("volume") !== undefined) ops.patchClip(p, c.id, { volume: a.num("volume") });
          return c.id;
        });
      return { assetId: asset.id, clipId, seconds: Math.round(seconds * 100) / 100, voice, readAs: hinglishShare(text) >= 0.12 ? transliterateHinglish(text) : undefined };
    },
  );

  registerCommand("voice.hinglish", "{text} How a line typed in English letters will be read: the Hindi words in Devanagari, the English ones left as they are, and how Hindi the line is (0…1).", (_s, a) => {
    const text = a.str("text");
    return { hindi: Math.round(hinglishShare(text) * 100) / 100, readAs: transliterateHinglish(text) };
  });
}
