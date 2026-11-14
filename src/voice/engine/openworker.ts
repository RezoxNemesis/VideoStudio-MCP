/**
 * Worker for the open-prosody voice: toned speech and singing. It loads the three-part model
 * (tools/voice/split_kokoro.py) from the app's own files and keeps the heavy work off the page.
 */
import * as ort from "onnxruntime-web";
import { phonemize } from "phonemizer";
import espeakWasm from "espeak-ng/dist/espeak-ng.wasm?url";
import { espeakIpa, setEspeakWasm } from "./espeak";
import { pitchShift, polish } from "./audio";
import { OpenModel, emphasisRanges, moodTone, sayOpen, type Tone } from "./open";
import { voiceForText } from "./plan";
import { lineSyllables, sing, type SingOptions, type SungNote } from "./sing";
import { VOICES_URL, phonemesFor, worldLang } from "./synth";
import { joinReading, reading } from "./natural";
import { langOfVoice } from "./world";
import { VoiceTables } from "./voicebank";

export type OpenRequest =
  | { id: number; type: "syllables"; lines: string[]; voice: string }
  | { id: number; type: "say"; text: string; voice: string; tone?: Tone | string; emphasis?: string[]; lang?: string; /** A character voice's own pitch (semitones) and pace. */ shift?: number; pace?: number }
  | { id: number; type: "sing"; notes: SungNote[]; voice: string; options?: SingOptions };
export type OpenReply = { id: number; ok: true; syllables?: string[][]; pcm?: Float32Array; voice?: string } | { id: number; ok: false; error: string } | { id: 0; ok: true; progress: number; what: string };

const BASE = `${self.location.origin}/stage-assets/voice`;
ort.env.wasm.wasmPaths = `${self.location.origin}/ort/`;
ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.max(1, Math.min(6, (self.navigator.hardwareConcurrency ?? 4) - 1)) : 1;
const post = (m: OpenReply, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(m, transfer);

let model: Promise<OpenModel> | null = null;
function load(): Promise<OpenModel> {
  model ??= (async () => {
    const part = async (name: string, k: number) => {
      const res = await fetch(`${BASE}/${name}.onnx`);
      if (!res.ok) throw new Error(`The open voice model is not installed (${name}.onnx, ${res.status}).`);
      const buf = await res.arrayBuffer();
      post({ id: 0, ok: true, progress: (k + 1) / 3, what: "Loading the singing voice" });
      return ort.InferenceSession.create(new Uint8Array(buf), { executionProviders: ["wasm"] });
    };
    const m = new OpenModel(ort as never, { dur: (await part("dur", 0)) as never, pros: (await part("pros", 1)) as never, voc: (await part("voc", 2)) as never });
    return m;
  })();
  model.catch(() => (model = null));
  return model;
}

/** Voice tables: the ones shipped with the app first, the public copies otherwise. */
const tables = new VoiceTables(async (id) => {
  for (const url of [`${BASE}/voices/${id}.bin`, `${VOICES_URL}/${id}.bin`]) {
    try {
      const res = await fetch(url);
      if (res.ok) return new Float32Array(await res.arrayBuffer());
    } catch {
      /* try the next place */
    }
  }
  throw new Error(`Voice ${id} could not be loaded.`);
});
setEspeakWasm(espeakWasm);
/** Sounds of a run of words: English by the small built-in dictionary, every other language by eSpeak NG. */
const speak = (t: string, lang: string) => (lang === "en-us" || lang === "en" ? phonemize(t, lang) : espeakIpa(t, lang));

self.onmessage = async (ev: MessageEvent<OpenRequest>) => {
  const msg = ev.data;
  try {
    if (msg.type === "syllables") {
      const out: string[][] = [];
      for (const line of msg.lines) out.push(lineSyllables(await phonemesFor(line, voiceForText(msg.voice, line), speak as never)));
      post({ id: msg.id, ok: true, syllables: out });
      return;
    }
    const m = await load();
    if (msg.type === "say") {
      const voice = msg.lang && !/^(en|hi)\b/i.test(msg.lang) ? msg.voice : voiceForText(msg.voice, msg.text);
      // a named tone that is also a learned emotion is acted by the model itself (moodTone)
      const base: Tone = moodTone(msg.tone);
      if (msg.pace && msg.pace !== 1) base.rate = (base.rate ?? 1) * msg.pace;
      const world = worldLang(msg.text, voice, msg.lang);
      // a voice that never learnt an evenly timed language keeps its own stress rhythm unless it is evened out
      if (world?.even && langOfVoice(voice)?.code !== world.code && base.even === undefined) base.even = 0.35;
      const table = await tables.table(voice, base.mood, base.moodAmount ?? 1);
      const emphasis = (msg.emphasis ?? []).map((e) => e.toLowerCase());
      const one = async (text: string, tone: Tone): Promise<Float32Array> => {
        const ph = await phonemesFor(text, voice, speak as never, msg.lang);
        const t: Tone = { ...tone };
        if (emphasis.length) {
          // words to lean on, found by their place in the sentence
          const words = text.split(/\s+/).map((w) => w.toLowerCase().replace(/[^\p{L}\p{N}']/gu, ""));
          const idx = emphasis.map((e) => words.indexOf(e)).filter((i) => i >= 0);
          if (idx.length) t.stress = [...(t.stress ?? []), ...emphasisRanges(ph, idx)];
        }
        return polish(await sayOpen(m, table, ph, t));
      };
      let pcm: Float32Array;
      const plan = (base.natural ?? 1) > 0 ? reading(msg.text, base, `${voice}|${msg.text}`, { world: !!world, breath: base.breath }) : [];
      if (plan.length) {
        const parts: { pcm: Float32Array; pauseMs: number; gain: number; breath?: boolean }[] = [];
        for (const r of plan) {
          const p = await one(r.text, r.tone);
          if (p.length) parts.push({ pcm: p, pauseMs: r.pauseMs, gain: r.gain, breath: r.breath });
        }
        if (parts.length) parts[parts.length - 1]!.pauseMs = 0;
        pcm = joinReading(parts, { breath: base.breath, seedText: msg.text });
      } else pcm = await one(msg.text, base);
      if (msg.shift) pcm = pitchShift(pcm, msg.shift);
      post({ id: msg.id, ok: true, pcm, voice }, [pcm.buffer]);
    } else if (msg.type === "sing") {
      const pcm = await sing(m, await tables.table(msg.voice), msg.notes, { ...msg.options, onProgress: (d) => post({ id: 0, ok: true, progress: d, what: "Singing" }) });
      post({ id: msg.id, ok: true, pcm }, [pcm.buffer]);
    }
  } catch (e) {
    post({ id: msg.id, ok: false, error: (e as Error).message });
  }
};
