/**
 * The studio voice worker (a Node process started by electron/voice-studio.cjs with ELECTRON_RUN_AS_NODE):
 * Chatterbox Multilingual through onnxruntime-node, so the heavy model (≈2 GB in memory) never sits in the
 * editor window or the main process. It downloads the model once into the app's data folder, keeps one
 * small speaker file per voice and mood, and speaks lines. Messages: {id, method, params} → {id, ok, value}.
 */
import { createWriteStream, existsSync, mkdirSync, openSync, readSync, closeSync, renameSync, statSync } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import { availableParallelism } from "node:os";
import path from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import * as ort from "onnxruntime-node";
import {
  acting,
  exposeAlignment,
  makeSpeaker,
  packSpeaker,
  say,
  seedFor,
  STUDIO_FILES,
  STUDIO_REPO,
  STUDIO_REVISION,
  StudioTokenizer,
  TEXT_POS,
  unpackSpeaker,
  type OrtSession,
  type Speaker,
  type StudioModels,
} from "../../../src/voice/engine/studio";

type Msg = { id: string; method: string; params: Record<string, unknown> };
const send = (m: unknown) => process.send?.(m);
let dataDir = "";
const modelDir = () => path.join(dataDir, "models", "chatterbox");
const ALL_FILES = [...STUDIO_FILES, "onnx/language_model_q4.onnx"];
/** Download sizes at the pinned revision, for progress. */
const SIZES: Record<string, number> = {
  "tokenizer.json": 71798,
  "onnx/speech_encoder.onnx": 1184608,
  "onnx/speech_encoder.onnx_data": 591274880,
  "onnx/embed_tokens.onnx": 13286,
  "onnx/embed_tokens.onnx_data": 68390912,
  "onnx/language_model_q4.onnx": 227911,
  "onnx/language_model_q4.onnx_data": 353621248,
  "onnx/conditional_decoder.onnx": 6350448,
  "onnx/conditional_decoder.onnx_data": 533970816,
};
const TOTAL = Object.values(SIZES).reduce((a, b) => a + b, 0);

function status() {
  const missing = ALL_FILES.filter((f) => !existsSync(path.join(modelDir(), f)));
  return { installed: missing.length === 0, missing, downloadBytes: missing.reduce((n, f) => n + (SIZES[f] ?? 0), 0), totalBytes: TOTAL, dir: modelDir() };
}

async function install(id: string) {
  let done = ALL_FILES.filter((f) => existsSync(path.join(modelDir(), f))).reduce((n, f) => n + (SIZES[f] ?? 0), 0);
  for (const f of status().missing) {
    const to = path.join(modelDir(), f);
    mkdirSync(path.dirname(to), { recursive: true });
    const res = await fetch(`https://huggingface.co/${STUDIO_REPO}/resolve/${STUDIO_REVISION}/${f}`);
    if (!res.ok || !res.body) throw new Error(`Download failed (${res.status}) for ${f}.`);
    let last = 0;
    const count = new Transform({
      transform(chunk: Buffer, _e, cb) {
        done += chunk.length;
        const now = Date.now();
        if (now - last > 400) {
          last = now;
          send({ id, event: "progress", done, total: TOTAL, what: "Downloading the studio voice model" });
        }
        cb(null, chunk);
      },
    });
    await pipeline(Readable.fromWeb(res.body as never), count, createWriteStream(`${to}.part`));
    if (SIZES[f] && statSync(`${to}.part`).size !== SIZES[f]) throw new Error(`${f} did not download completely; try again.`);
    renameSync(`${to}.part`, to);
  }
  return status();
}

let models: Promise<StudioModels> | null = null;
function load(): Promise<StudioModels> {
  models ??= (async () => {
    if (!status().installed) throw new Error("The studio voice model is not installed yet.");
    const dir = path.join(modelDir(), "onnx");
    const aligned = path.join(dir, "language_model_q4_align.onnx");
    if (!existsSync(aligned)) await writeFile(aligned, exposeAlignment(new Uint8Array(await readFile(path.join(dir, "language_model_q4.onnx")))));
    const opts: ort.InferenceSession.SessionOptions = { graphOptimizationLevel: "all", intraOpNumThreads: availableParallelism() };
    const open = (f: string, o = opts) => ort.InferenceSession.create(path.join(dir, f), o) as unknown as Promise<OrtSession>;
    // The decoder's graph is huge: onnxruntime 1.31's sequential planner takes minutes on it, the parallel one seconds.
    const [embed, lm, decoder] = await Promise.all([open("embed_tokens.onnx"), open("language_model_q4_align.onnx"), open("conditional_decoder.onnx", { ...opts, executionMode: "parallel", interOpNumThreads: 1 })]);
    let enc: Promise<OrtSession> | null = null;
    const fd = openSync(path.join(dir, "embed_tokens.onnx_data"), "r");
    const buf = new Uint8Array(TEXT_POS.rows * TEXT_POS.dim * 4);
    readSync(fd, buf, 0, buf.length, TEXT_POS.offset);
    closeSync(fd);
    return {
      tensor: (type, data, dims) => new ort.Tensor(type, data, dims as number[]) as never,
      embed,
      lm,
      decoder,
      encoder: () => (enc ??= open("speech_encoder.onnx")),
      tokenizer: new StudioTokenizer(await readFile(path.join(modelDir(), "tokenizer.json"), "utf8")),
      textPos: new Float32Array(buf.buffer),
    };
  })();
  models.catch(() => (models = null));
  return models;
}

const speakers = new Map<string, Speaker>();
const speakerFile = (key: string) => path.join(dataDir, "studio", "speakers", `${key.replace(/[^\w.-]/g, "_")}.bin`);
async function speaker(key: string): Promise<Speaker | null> {
  if (speakers.has(key)) return speakers.get(key)!;
  const f = speakerFile(key);
  if (!existsSync(f)) return null;
  const s = unpackSpeaker(new Uint8Array(await readFile(f)));
  speakers.set(key, s);
  return s;
}

async function handle(m: Msg): Promise<unknown> {
  const p = m.params ?? {};
  if (typeof p.dataDir === "string") dataDir = p.dataDir;
  switch (m.method) {
    case "status":
      return status();
    case "install":
      return install(m.id);
    case "speaker": {
      // A reference clip (24 kHz, rendered by VolTune in the voice and mood) becomes a speaker.
      const mdl = await load();
      const s = await makeSpeaker(mdl, p.pcm as Float32Array);
      mkdirSync(path.dirname(speakerFile(String(p.key))), { recursive: true });
      await writeFile(speakerFile(String(p.key)), packSpeaker(s));
      speakers.set(String(p.key), s);
      return true;
    }
    case "say": {
      const key = String(p.key);
      const spk = await speaker(key);
      if (!spk) return { needRef: true };
      const mdl = await load();
      const mood = typeof p.mood === "string" ? p.mood : undefined;
      const act = acting(mood, typeof p.moodAmount === "number" ? p.moodAmount : 1);
      const text = String(p.text);
      let first: Float32Array | null = null;
      for (let attempt = 0; attempt < 3; attempt++) {
        const r = await say(mdl, text, spk, {
          lang: String(p.lang ?? "en"),
          ...act,
          seed: seedFor(key, text, String(attempt)),
          onToken: (n) => n % 25 === 0 && send({ id: m.id, event: "progress", done: n, what: "Acting the line" }),
        });
        if (r.complete) return { pcm: r.pcm };
        first ??= r.pcm;
      }
      return { pcm: first };
    }
    default:
      throw new Error(`Unknown studio voice request: ${m.method}`);
  }
}

// One request at a time (the model is not shared between requests).
let chain: Promise<unknown> = Promise.resolve();
process.on("message", (m: Msg) => {
  chain = chain.then(async () => {
    try {
      send({ id: m.id, ok: true, value: await handle(m) });
    } catch (e) {
      send({ id: m.id, ok: false, error: e instanceof Error ? e.message : String(e) });
    }
  });
});
send({ ready: true });
