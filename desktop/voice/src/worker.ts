/**
 * The studio voice worker (a Node process started by electron/voice-studio.cjs with ELECTRON_RUN_AS_NODE):
 * Chatterbox Multilingual through onnxruntime-node, so the heavy model (≈2 GB in memory) never sits in the
 * editor window or the main process. It downloads the model once into the app's data folder, keeps one
 * small speaker file per voice and mood, and speaks lines. Messages: {id, method, params} → {id, ok, value}.
 */
import { createWriteStream, existsSync, mkdirSync, openSync, readSync, closeSync, renameSync, statSync } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import { availableParallelism, setPriority } from "node:os";
import { execFileSync } from "node:child_process";
import { voiceThreads, cudaReady } from "./threads";
import path from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import * as ort from "onnxruntime-node";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import {
  acting,
  exposeAlignment,
  makeSpeaker,
  revoice,
  packSpeaker,
  say,
  seedFor,
  takeSeed,
  STUDIO_FILES,
  STUDIO_FULL_LM,
  STUDIO_REPO,
  STUDIO_REVISION,
  StudioTokenizer,
  TEXT_POS,
  unpackSpeaker,
  type OrtSession,
  type Speaker,
  type StudioModels,
} from "../../../src/voice/engine/studio";
import { leadFor } from "../../../src/voice/engine/studio_lead";
import { steadiness, cracked, endingOf, endsWrong } from "../../../src/voice/engine/studio_steady";

type Msg = { id: string; method: string; params: Record<string, unknown> };
const send = (m: unknown) => process.send?.(m);
let dataDir = "";
// (V3 lives in its own folder: V2's files and speakers are of no use to it)
const modelDir = () => path.join(dataDir, "models", "chatterbox-v3");
/** The real readers the voices are spoken from (studio_bank.ts), shipped beside this worker. */
const bankDir = path.join(path.dirname(fileURLToPath(import.meta.url)), "refs");

/** Is there a graphics card the voice can run on (NVIDIA, CUDA 12 and cuDNN 9 present, the provider shipped)? */
let gpuKnown: boolean | null = null;
function gpu(): boolean {
  if (gpuKnown !== null) return gpuKnown;
  let ld = "";
  try { ld = execFileSync("ldconfig", ["-p"], { encoding: "utf8", timeout: 3000 }); } catch { /* no ldconfig: CPU */ }
  let provider = false;
  try {
    const pkg = path.dirname(createRequire(import.meta.url).resolve("onnxruntime-node/package.json"));
    provider = execFileSync("find", [path.join(pkg, "bin"), "-name", "libonnxruntime_providers_cuda.so"], { encoding: "utf8", timeout: 5000 }).trim().length > 0;
  } catch { /* not shipped: CPU */ }
  return (gpuKnown = cudaReady(process.platform, ld) && provider);
}
/** The files this PC needs: the 4-bit language model on the CPU, the full one on a graphics card. */
const ALL_FILES = () => [...STUDIO_FILES, "onnx/language_model_q4.onnx", ...(gpu() ? STUDIO_FULL_LM : [])];
/** Download sizes at the pinned revision, for progress. */
const SIZES: Record<string, number> = {
  "tokenizer.json": 72765,
  "onnx/speech_encoder.onnx": 1204600,
  "onnx/speech_encoder.onnx_data": 591274880,
  "onnx/embed_tokens.onnx": 17616,
  "onnx/embed_tokens.onnx_data": 68808704,
  "onnx/language_model_q4.onnx": 228633,
  "onnx/language_model_q4.onnx_data": 353621248,
  "onnx/language_model.onnx": 171445,
  "onnx/language_model.onnx_data": 2080632832,
  "onnx/conditional_decoder.onnx": 6371670,
  "onnx/conditional_decoder.onnx_data": 533970816,
};
const total = () => ALL_FILES().reduce((n, f) => n + (SIZES[f] ?? 0), 0);

function status() {
  const missing = ALL_FILES().filter((f) => !existsSync(path.join(modelDir(), f)));
  return { installed: missing.length === 0, missing, downloadBytes: missing.reduce((n, f) => n + (SIZES[f] ?? 0), 0), totalBytes: total(), dir: modelDir(), gpu: gpu(), model: "Chatterbox Multilingual V3" };
}

async function install(id: string) {
  const TOTAL = total();
  let done = ALL_FILES().filter((f) => existsSync(path.join(modelDir(), f))).reduce((n, f) => n + (SIZES[f] ?? 0), 0);
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
    const onGpu = gpu();
    // on a graphics card the full-precision language model (clearer words); on the CPU the 4-bit one (fast enough)
    const lmName = onGpu && existsSync(path.join(dir, "language_model.onnx_data")) ? "language_model" : "language_model_q4";
    const aligned = path.join(dir, `${lmName}_align.onnx`);
    if (!existsSync(aligned)) await writeFile(aligned, exposeAlignment(new Uint8Array(await readFile(path.join(dir, `${lmName}.onnx`)))));
    try { setPriority(10); } catch { /* not allowed: keep the normal priority */ }
    const opts: ort.InferenceSession.SessionOptions = { graphOptimizationLevel: "all", intraOpNumThreads: voiceThreads(availableParallelism()) };
    const open = async (f: string, o = opts) => {
      if (onGpu) { try { return (await ort.InferenceSession.create(path.join(dir, f), { ...o, executionProviders: ["cuda", "cpu"] })) as unknown as OrtSession; } catch { /* the GPU would not take it: the CPU does */ } }
      return (await ort.InferenceSession.create(path.join(dir, f), o)) as unknown as OrtSession;
    };
    // The decoder's graph is huge: onnxruntime 1.31's sequential planner takes minutes on it, the parallel one seconds.
    const [embed, lm, decoder] = await Promise.all([open("embed_tokens.onnx"), open(`${lmName}_align.onnx`), open("conditional_decoder.onnx", { ...opts, executionMode: "parallel", interOpNumThreads: 1 })]);
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
const speakerFile = (key: string) => path.join(dataDir, "studio", "speakers-v3", `${key.replace(/[^\w.-]/g, "_")}.bin`);

/** A bank clip (RIFF WAVE, 16-bit PCM, mono, 24 kHz) as samples. */
function readWav(b: Buffer): Float32Array {
  if (b.toString("ascii", 0, 4) !== "RIFF" || b.toString("ascii", 8, 12) !== "WAVE") throw new Error("A voice bank clip is not a WAV file.");
  let o = 12, fmt = { ch: 1, bits: 16, rate: 24000 };
  while (o + 8 <= b.length) {
    const id = b.toString("ascii", o, o + 4), n = b.readUInt32LE(o + 4);
    if (id === "fmt ") fmt = { ch: b.readUInt16LE(o + 10), rate: b.readUInt32LE(o + 12), bits: b.readUInt16LE(o + 22) };
    if (id === "data") {
      if (fmt.bits !== 16 || fmt.ch !== 1 || fmt.rate !== 24000) throw new Error("A voice bank clip must be 24 kHz mono 16-bit.");
      const k = Math.floor(n / 2), x = new Float32Array(k);
      for (let i = 0; i < k; i++) x[i] = b.readInt16LE(o + 8 + i * 2) / 32768;
      return x;
    }
    o += 8 + n + (n & 1);
  }
  throw new Error("A voice bank clip has no sound.");
}
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
      let spk = await speaker(key);
      if (!spk && typeof p.bank === "string" && /^[\w-]+$/.test(p.bank)) {
        // a real reader from the bank (studio_bank.ts): the speaker is made here, from the clip shipped with the worker
        const clip = readWav(await readFile(path.join(bankDir, `${p.bank}.wav`)));
        const s = await makeSpeaker(await load(), clip);
        mkdirSync(path.dirname(speakerFile(key)), { recursive: true });
        await writeFile(speakerFile(key), packSpeaker(s));
        speakers.set(key, s);
        spk = s;
      }
      if (!spk) return { needRef: true };
      const mdl = await load();
      const mood = typeof p.mood === "string" ? p.mood : undefined;
      const act = acting(mood, typeof p.moodAmount === "number" ? p.moodAmount : 1);
      // a slow line asks for a more deliberate delivery than its mood alone would give
      if (typeof p.cfg === "number") act.cfg = Math.min(act.cfg, Math.max(0.1, p.cfg));
      const text = String(p.text);
      // (with trace: the same takes are made and the same one chosen; how each went is told as well)
      type Take = Awaited<ReturnType<typeof say>>;
      const told = (r: Take, attempt: number, tries: unknown[]) =>
        p.trace ? { pcm: r.pcm, complete: r.complete, reason: r.reason, tokens: r.tokens, trace: r.trace, pass: r.pass, lead: r.lead, stops: r.stops, attempt, tries } : { pcm: r.pcm, stops: r.stops };
      const tries: Array<{ complete: boolean; reason: string; lead?: boolean; pass?: Take["pass"]; found?: unknown; jumps?: number; ending?: number | null }> = [];
      let first: Take | null = null;
      let firstAt = 0;
      let firstScore = Infinity;
      // a complete take whose pitch cracks (studio_steady.ts) is kept in hand while another is tried: the steadiest complete one is told.
      // Crying and fear break the voice on purpose, so they are not asked to be steady.
      const mayCrack = mood === "crying" || mood === "scared";
      let steady: Take | null = null;
      let steadyAt = 0;
      let steadyJumps = Infinity;
      for (let attempt = 0; attempt < 3; attempt++) {
        const r = await say(mdl, text, spk, {
          lang: String(p.lang ?? "en"),
          ...act,
          seed: takeSeed(key, text, attempt, typeof p.take === "number" ? p.take : 0),
          trace: p.trace === true,
          lead: p.lead === false ? "" : leadFor(text, String(p.lang ?? "en")),
          onToken: (n) => n % 25 === 0 && send({ id: m.id, event: "progress", done: n, what: "Acting the line" }),
        });
        const how = r.complete && !mayCrack ? steadiness(r.pcm) : null;
        // and how it ENDS: a statement that ends above its middle sounds unfinished (studio_steady.ts endingOf); asked for by the
        // owner, "more natural ending of any dialogue". A question may rise; crying and fear are left alone.
        const ending = r.complete && !mayCrack ? endingOf(r.pcm) : null, wrongEnd = endsWrong(text, ending);
        tries.push({ complete: r.complete, reason: r.reason, lead: r.lead, pass: r.pass, found: r.trace?.lead, ...(how ? { jumps: how.jumps } : {}), ...(ending !== null ? { ending } : {}) });
        if (r.complete) {
          if (!cracked(how) && !wrongEnd) return told(r, attempt, tries);
          const score = (how ? how.jumps : 0) + (wrongEnd ? 2 : 0);
          if (score < steadyJumps) { steady = r; steadyAt = attempt; steadyJumps = score; }
          continue;
        }
        // none clean so far: a take that said the line with a leap is still better than one that never got through it;
        // a lead that could not be parted cleanly from the line (the first word may be clipped, or a trace of the
        // lead left) counts like a small leap, less than words leapt over
        const score = (r.pass ? r.pass.dirt : 1e6) + (r.lead === false ? 3 : 0);
        if (!first || score < firstScore) { first = r; firstAt = attempt; firstScore = score; }
      }
      return steady ? told(steady, steadyAt, tries) : told(first!, firstAt, tries);
    }
    case "revoice": {
      // a recorded performance (24 kHz) said in the voice `key`: the delivery is the performer's, the voice the character's
      const key = String(p.key);
      let spk = await speaker(key);
      if (!spk && typeof p.bank === "string" && /^[\w-]+$/.test(p.bank)) {
        const clip = readWav(await readFile(path.join(bankDir, `${p.bank}.wav`)));
        const s = await makeSpeaker(await load(), clip);
        mkdirSync(path.dirname(speakerFile(key)), { recursive: true });
        await writeFile(speakerFile(key), packSpeaker(s));
        speakers.set(key, s);
        spk = s;
      }
      if (!spk) return { needRef: true };
      const pcm = await revoice(await load(), p.pcm as Float32Array, spk, (done, of) => send({ id: m.id, event: "progress", done, total: of, what: "Giving it the voice" }));
      return { pcm };
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
