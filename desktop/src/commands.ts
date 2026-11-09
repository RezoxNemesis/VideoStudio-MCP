/**
 * Desktop-only agent commands. They sit next to the engine's own commands (help lists both), so Claude can
 * browse the person's folders, read music and voices before cutting, bring files in, and look at the app.
 */
import type { Studio } from "../../src/app/studio";
import { registerCommand } from "../../src/agent/api";
import * as ops from "../../src/core/ops";
import * as model from "../../src/core/model";
import { ProRender, type ProCodec } from "./render";
import { assetPath, importPath } from "./importer";
import { compile, EXPR_FUNCTIONS } from "./expr";
import { analyseSong } from "../../src/media/song";
import { need, pathBlob, type AudioReport } from "./bridge";

export interface ShellHooks {
  workspace(name: string): void;
  current(): string;
  toast(msg: string): void;
}

let hooks: ShellHooks | null = null;
export function setShellHooks(h: ShellHooks): void {
  hooks = h;
}

const KINDS = ["video", "audio", "image", "model", "project", "other"];
function kinds(v: unknown): string[] | undefined {
  if (v === undefined || v === null) return undefined;
  const list = Array.isArray(v) ? v.map(String) : String(v).split(",");
  const ok = list.map((s) => s.trim()).filter((s) => KINDS.includes(s));
  return ok.length ? ok : undefined;
}

async function songOf(buf: ArrayBuffer): Promise<ReturnType<typeof analyseSong> | { error: string }> {
  try {
    const ctx = new OfflineAudioContext(1, 1, 22050);
    const b = await ctx.decodeAudioData(buf);
    const chans: Float32Array[] = [];
    for (let c = 0; c < Math.min(2, b.numberOfChannels); c++) chans.push(b.getChannelData(c));
    return analyseSong(chans, b.sampleRate);
  } catch (e) {
    return { error: `Song analysis unavailable: ${(e as Error).message}` };
  }
}

/** Keep the report readable: long per-step arrays are thinned so the answer stays small. */
function trim(r: AudioReport, detail: boolean) {
  if (!detail) {
    const n = r.envelope.rmsDb.length;
    const every = Math.max(1, Math.ceil(n / 240));
    r.envelope = { ...r.envelope, step: r.envelope.step * every, rmsDb: r.envelope.rmsDb.filter((_, i) => i % every === 0), peakDb: r.envelope.peakDb.filter((_, i) => i % every === 0) };
    if (r.beats && r.beats.length > 400) r.beats = r.beats.slice(0, 400);
  }
  return r;
}

export function registerDesktopCommands(s: Studio): void {
  registerCommand("desktop.info", "This PC: folders, drives, CPU, memory, GPU status, app version, where renders are saved.", () => need().info());

  registerCommand("fs.roots", "The person's main folders (Videos, Music, Pictures, Downloads, Desktop, Documents, home), mounted drives and the VideoStudio output folders.", async () => {
    const i = await need().info();
    return { folders: i.dirs, drives: i.drives, renders: i.out };
  });

  registerCommand("fs.list", "{dir, kinds?: [video|audio|image|model|project], sort?: name|modified, hidden?=false} List a folder on the PC (folders first).", (_s, a) =>
    need().list(a.str("dir").replace(/^~/, ""), { kinds: kinds(a.any("kinds")), sort: (a.optStr("sort") as "name" | "modified" | undefined) ?? "name", hidden: a.bool("hidden") }).then((r) => (r.dir.startsWith("/") ? r : r)),
  );

  registerCommand("fs.find", "{query (words in the name or path), root?=home, kinds?, limit?=100} Search the PC for media by name, newest first.", (_s, a) =>
    need().find(a.str("root", ""), a.str("query", ""), { kinds: kinds(a.any("kinds")), limit: a.num("limit", 100) }),
  );

  registerCommand("media.probe", "{path} Everything about a media file on the PC before importing: duration, resolution, fps, codecs, rotation, HDR, sample rate, tags (title/artist).", (_s, a) => need().probe(a.str("path")));

  registerCommand("media.peek", "{path, times?: [s...] | count?=6, max?=480} Look at frames of a video on the PC without importing it.", async (_s, a) => {
    const p = a.str("path");
    let times = a.any("times") as number[] | undefined;
    if (!Array.isArray(times)) {
      const info = await need().probe(p);
      const n = Math.max(1, Math.min(16, a.num("count", 6)));
      times = Array.from({ length: n }, (_, i) => Math.round(((info.duration * (i + 0.5)) / n) * 100) / 100);
    }
    return need().frames(p, times, Math.min(960, a.num("max", 480)));
  });

  registerCommand(
    "media.importPath",
    "{path | paths:[...], add?=true, start?, trackId?, convert?=true} Bring files from the PC into the project. Phone HEVC/HDR, ProRes, DNxHD, rotated or 10-bit video and unusual audio are converted to a visually lossless edit copy first (GPU when available); originals are never changed. Returns asset ids (and clip ids when added).",
    async (st, a) => {
      const list = (a.any("paths") as string[] | undefined) ?? [a.str("path")];
      const add = a.bool("add", true);
      const out: { assetId: string; name: string; kind: string; duration: number; clipId?: string; converted?: string }[] = [];
      for (const p of list) {
        const before = new Set(st.project.tracks.flatMap((t) => t.clips.map((c) => c.id)));
        const { asset, converted } = await importPath(st, p, add, a.bool("convert", true));
        const clip = st.project.tracks.flatMap((t) => t.clips).find((c) => !before.has(c.id) && c.assetId === asset.id);
        if (clip && (a.optNum("start") !== undefined || a.optStr("trackId"))) st.mutate("place import", (pr) => ops.moveClip(pr, clip.id, a.num("start", clip.start), a.optStr("trackId")));
        out.push({ assetId: asset.id, name: asset.name, kind: asset.kind, duration: asset.duration, clipId: clip?.id, converted });
      }
      return out;
    },
  );

  registerCommand(
    "audio.inspect",
    "{assetId | path, from?, seconds?, pictures?=true, song?=true, detail?=false} Read music, a voice or a sound effect: loudness (LUFS, range, true peak), loud/quiet sections, silences, tempo, beat grid, hits, plus the song's key, mood and labelled sections (intro/verse/chorus/drop…), and pictures of the waveform and the spectrogram (time and frequency axes).",
    async (st, a) => {
      const o = { from: a.optNum("from"), seconds: a.optNum("seconds"), pictures: a.bool("pictures", true), step: a.optNum("step") };
      let report: AudioReport;
      let bytes: ArrayBuffer | null = null;
      const assetId = a.optStr("assetId");
      if (assetId) {
        const asset = st.project.assets.find((x) => x.id === assetId);
        if (!asset) throw new Error(`No asset ${assetId}.`);
        bytes = await (await st.pool.blob(asset)).arrayBuffer();
        report = await need().audioInspectBytes(new Uint8Array(bytes), asset.name, o);
        report.file = asset.name;
        report.path = `asset:${asset.id}`;
      } else {
        const p = a.str("path");
        report = await need().audioInspect(p, o);
        if (a.bool("song", true)) bytes = await (await pathBlob(p)).blob.arrayBuffer();
      }
      trim(report, a.bool("detail"));
      if (a.bool("song", true) && bytes && !o.from && !o.seconds) {
        const song = await songOf(bytes);
        if ("error" in song) report.song = song;
        else report.song = { bpm: song.bpm, key: song.key, mood: song.mood, sections: song.sections, downbeats: song.downbeats.slice(0, 300), hits: song.hits.slice(0, 40) };
      }
      return report;
    },
  );


  /* ---------------------------------------------------------------- GPU render */

  let job: ProRender | null = null;
  const proHelp =
    "{codec?: auto|h264|hevc|nvenc_h264|nvenc_hevc|nvenc_av1|x264|x265|prores|prores4444|vp9 (auto = NVIDIA GPU H.264 when present, else x264), height? (up to 4320; width follows the frame), fps? (up to 120), quality? 0-100 (=88), from?, to?, name?, audio?=true} Render on this PC: GPU compositing → native ffmpeg (NVENC / x264 / x265 / ProRes), 10-bit HEVC, 48 kHz AAC 320k (PCM in ProRes). Saved to ~/Videos/VideoStudio. Waits until done.";
  const proRun = async (st: Studio, a: import("../../src/agent/api").HostArgs) => {
    if (job) throw new Error("A render is already running.");
    const q = a.optStr("quality");
    const quality = q === undefined ? 88 : q === "very-high" ? 95 : q === "high" ? 88 : q === "medium" ? 72 : q === "low" ? 55 : Number(q);
    job = new ProRender(st.project, st.pool, {
      codec: (a.optStr("codec") as ProCodec | undefined) ?? "auto",
      height: a.optNum("height"),
      fps: a.optNum("fps"),
      quality,
      from: a.optNum("from"),
      to: a.optNum("to"),
      name: a.optStr("name"),
      audio: a.bool("audio", true),
    });
    job.onProgress = (pr) => st.setStatus(pr.stage === "video" ? `Rendering ${pr.frame}/${pr.frames} · ${pr.fps.toFixed(1)} fps · ${Math.ceil(pr.etaSec)} s left` : pr.stage === "audio" ? "Mixing sound…" : pr.stage === "finalizing" ? "Finishing the file…" : "Render done");
    try {
      const r = await job.run();
      hooks?.toast(`Rendered ${r.name}`);
      document.dispatchEvent(new CustomEvent("vsd:rendered", { detail: r }));
      return r;
    } finally {
      job = null;
    }
  };
  registerCommand("render.start", proHelp, proRun);
  registerCommand("render.pro", proHelp, proRun);
  registerCommand("render.cancel", "Stop the running render.", () => {
    job?.cancel();
    return !!job;
  });
  registerCommand("desktop.gpu", "Which encoders and pro filters this PC has (NVENC H.264/HEVC/AV1, x264, x265, ProRes, vid.stab, minterpolate…).", () => need().enc.caps());

  /* ---------------------------------------------------------------- pro media tools */

  const PROC = ["convert", "stabilize", "slowmo", "denoise", "upscale", "lut", "reverse", "loudnorm", "extractAudio", "speed"];
  const sameLength = new Set(["convert", "stabilize", "denoise", "upscale", "lut", "loudnorm"]);
  registerCommand(
    "media.process",
    `{kind: ${PROC.join("|")}, assetId | path, clipId? (put the result in place of this clip), add?=false, ...options} Pro processing with native ffmpeg on this PC, result imported as a new asset. Options — stabilize: smoothing 1-100 (30), shakiness 1-10, zoom; slowmo: factor 1.5-8 (2), mode mci|blend (mci = motion-interpolated frames); denoise: strength 0.5-10, quality fast|best; upscale: height (2160); lut: lut (path to .cube), amount 0-1; loudnorm: target LUFS (-14); speed: factor 0.25-4 (pitch kept).`,
    async (st, a) => {
      const kind = a.str("kind");
      if (!PROC.includes(kind)) throw new Error(`kind must be one of ${PROC.join(", ")}`);
      let input = a.optStr("path");
      const assetId = a.optStr("assetId");
      if (!input) {
        const asset = st.project.assets.find((x) => x.id === assetId);
        if (!asset) throw new Error("Give assetId or path.");
        input = await assetPath(st, asset);
      }
      const opts: Record<string, unknown> = {};
      for (const k of ["smoothing", "shakiness", "zoom", "factor", "mode", "strength", "quality", "height", "lut", "amount", "target"]) if (a.any(k) !== undefined) opts[k] = a.any(k);
      st.setStatus(`${kind}…`);
      const r = await need().process(kind, input, opts);
      const { asset } = await importPath(st, r.path, a.bool("add") && !a.optStr("clipId"), false);
      let clipId: string | undefined;
      const target = a.optStr("clipId");
      if (target) {
        st.mutate(kind, (p) => {
          const f = model.findClip(p, target);
          if (!f) throw new Error(`No clip ${target}`);
          if (sameLength.has(kind)) {
            ops.patchClip(p, target, { assetId: asset.id, name: asset.name });
            clipId = target;
          } else {
            const start = f.clip.start;
            const trackId = f.track.id;
            ops.deleteClip(p, target, false);
            clipId = ops.addClip(p, { assetId: asset.id, start, trackId }).id;
          }
        });
      }
      st.setStatus("");
      return { assetId: asset.id, name: asset.name, duration: asset.duration, clipId, note: r.note };
    },
  );

  /* ---------------------------------------------------------------- procedural animation (safe maths language) */

  registerCommand(
    "keys.expr",
    `{id (clip), prop (x y scale scaleX scaleY rotation opacity anchorX anchorY | grade.<name> | fx.<name> | volume), expr, from? (timeline s, default clip start), to? (default clip end), step? (s between keys, default 1/fps; ≥ 1/120), ease?=linear, replace?=true} Generate keyframes from a maths expression — handheld shake, orbits, pulses, springs, beat-synced bounces. Variables: t (s since 'from'), u (0..1 across the range), T (timeline time), dur, fps, i (key index), beat (s per beat when bpm given), bpm, v0 (current value). Functions: ${EXPR_FUNCTIONS.join(", ")}; constants pi, tau, e. Examples: "noise(t*3)*12" (handheld x), "1 + 0.08*spring(t, 2, 5)" (scale pop), "lerp(0, 360, easeInOut(u))" (rotation).`,
    (st, a) => {
      const id = a.str("id");
      const prop = a.str("prop");
      const path = /^(grade|fx)\./.test(prop) || prop === "volume" || prop.startsWith("transform.") ? prop : `transform.${prop}`;
      const f = compile(a.str("expr"));
      return st.mutate("expression keys", (p) => {
        const c = ops.getClip(p, id);
        const from = a.num("from", c.start);
        const to = a.num("to", c.start + c.dur);
        if (!(to > from)) throw new Error("to must be after from.");
        const step = Math.max(1 / 120, a.num("step", 1 / p.fps));
        const n = Math.min(4000, Math.floor((to - from) / step) + 1);
        const bpm = a.num("bpm", 0);
        const tgt = ops.parseTarget(path);
        const cur = tgt.group === "volume" ? c.volume : tgt.group === "transform" ? c.transform[tgt.prop] : tgt.group === "fx" ? c.fx?.[tgt.prop] : c.grade[tgt.prop];
        const v0 = typeof cur === "number" ? cur : tgt.group === "transform" ? model.TRANSFORM_DEFAULTS[tgt.prop] : tgt.group === "fx" ? model.FX_DEFAULTS[tgt.prop] : tgt.group === "grade" ? model.GRADE_DEFAULTS[tgt.prop] : 1;
        if (a.bool("replace", true)) ops.setConstant(p, id, path, v0);
        const ease = (a.optStr("ease") as model.Ease | undefined) ?? "linear";
        let first = 0, last = 0;
        for (let i = 0; i < n; i++) {
          const T = Math.min(to, from + i * step);
          const v = f({ t: T - from, u: (T - from) / (to - from), T, dur: to - from, fps: p.fps, i, bpm, beat: bpm ? 60 / bpm : 0, v0 });
          if (i === 0) first = v;
          last = v;
          ops.keyframe(p, id, path, T, v, ease, v0);
        }
        return { keys: n, from, to, first: Math.round(first * 1000) / 1000, last: Math.round(last * 1000) / 1000 };
      });
    },
  );

  registerCommand(
    "keys.evaluate",
    "{expr, from?=0, to?=1, samples?=11, bpm?} Try a maths expression before using it: returns its values (t from 'from' to 'to').",
    (_s, a) => {
      const f = compile(a.str("expr"));
      const from = a.num("from", 0), to = a.num("to", 1), n = Math.max(2, Math.min(200, a.num("samples", 11)));
      const bpm = a.num("bpm", 0);
      return Array.from({ length: n }, (_, i) => {
        const t = from + ((to - from) * i) / (n - 1);
        return [Math.round(t * 1000) / 1000, Math.round(f({ t, u: (t - from) / (to - from || 1), T: t, dur: to - from, fps: 30, i, bpm, beat: bpm ? 60 / bpm : 0, v0: 0 }) * 10000) / 10000];
      });
    },
  );

  registerCommand("ui.screenshot", "{max?=1600} See the VideoStudio Desktop window as it is now (JPEG).", async (_s, a) => {
    // Let the window paint what earlier commands changed (workspace switches, new clips) before capturing.
    await new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(r, 250))));
    return { image: await need().capture(Math.min(2400, a.num("max", 1600))), workspace: hooks?.current() };
  });

  registerCommand("ui.workspace", "{name: edit|animate|audio|color|voice|deliver|claude} Switch the workspace the person sees.", (_s, a) => {
    const n = a.str("name");
    if (!hooks) throw new Error("The window is still loading.");
    hooks.workspace(n);
    return { workspace: hooks.current() };
  });

  registerCommand("ui.notify", "{text} Show the person a short message in the app.", (_s, a) => {
    hooks?.toast(a.str("text").slice(0, 300));
    return true;
  });

  registerCommand("project.backup", "{path?} Save the project (edit decisions, not the media) as a .vsproj file; default ~/Documents/VideoStudio/<name>.vsproj.", async (st, a) => {
    const i = await need().info();
    const name = `${st.project.name.replace(/[\/\\:*?"<>|]/g, "_") || "project"}.vsproj`;
    const p = a.optStr("path") ?? `${i.out.Documents}/${name}`;
    return { path: await need().writeText(p, await st.exportJson().text()) };
  });

  registerCommand("fs.reveal", "{path} Open the folder that holds this file in the file manager (e.g. a finished render).", async (_s, a) => {
    await need().reveal(a.str("path"));
    return true;
  });
}
