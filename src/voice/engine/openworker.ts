/**
 * Worker for the open-prosody voice: toned speech and singing. It loads the three-part model
 * (tools/voice/split_kokoro.py) from the app's own files and keeps the heavy work off the page.
 */
import * as ort from "onnxruntime-web";
import { phonemize } from "phonemizer";
import { polish } from "./audio";
import { OpenModel, TONES, emphasisRanges, sayOpen, type Tone } from "./open";
import { voiceForText } from "./plan";
import { lineSyllables, sing, type SingOptions, type SungNote } from "./sing";
import { VOICES_URL, phonemesFor } from "./synth";
import { VoiceTables } from "./voicebank";

export type OpenRequest =
  | { id: number; type: "syllables"; lines: string[]; voice: string }
  | { id: number; type: "say"; text: string; voice: string; tone?: Tone | string; emphasis?: string[] }
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
const speak = (t: string, lang: string) => phonemize(t, lang);

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
      const voice = voiceForText(msg.voice, msg.text);
      const ph = await phonemesFor(msg.text, voice, speak as never);
      const base: Tone = typeof msg.tone === "string" ? (TONES[msg.tone] ?? {}) : (msg.tone ?? {});
      const tone: Tone = { ...base };
      if (msg.emphasis?.length) {
        // words to lean on, found by their place in the line
        const words = msg.text.split(/\s+/).map((w) => w.toLowerCase().replace(/[^\p{L}\p{N}']/gu, ""));
        const idx = msg.emphasis.map((e) => words.indexOf(e.toLowerCase())).filter((i) => i >= 0);
        tone.stress = [...(tone.stress ?? []), ...emphasisRanges(ph, idx)];
      }
      const pcm = polish(await sayOpen(m, await tables.table(voice), ph, tone));
      post({ id: msg.id, ok: true, pcm, voice }, [pcm.buffer]);
    } else if (msg.type === "sing") {
      const pcm = await sing(m, await tables.table(msg.voice), msg.notes, { ...msg.options, onProgress: (d) => post({ id: 0, ok: true, progress: d, what: "Singing" }) });
      post({ id: msg.id, ok: true, pcm }, [pcm.buffer]);
    }
  } catch (e) {
    post({ id: msg.id, ok: false, error: (e as Error).message });
  }
};
