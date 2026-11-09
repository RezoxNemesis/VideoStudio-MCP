/**
 * Desktop-only agent commands. They sit next to the engine's own commands (help lists both), so Claude can
 * browse the person's folders, read music and voices before cutting, bring files in, and look at the app.
 */
import type { Studio } from "../../src/app/studio";
import { registerCommand } from "../../src/agent/api";
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
    "{path | paths:[...], add?=true, start?, trackId?} Bring files from the PC into the project (copied into the project; the original is never changed). Returns the new asset ids (and clip ids when added).",
    async (st, a) => {
      const list = (a.any("paths") as string[] | undefined) ?? [a.str("path")];
      const add = a.bool("add", true);
      const out: { assetId: string; name: string; kind: string; duration: number; clipId?: string }[] = [];
      for (const p of list) {
        const { blob, name } = await pathBlob(p);
        const before = new Set(st.project.tracks.flatMap((t) => t.clips.map((c) => c.id)));
        const [asset] = await st.importFiles([new File([blob], name, { type: blob.type })], add);
        if (!asset) throw new Error(`Could not import ${name}.`);
        const clip = st.project.tracks.flatMap((t) => t.clips).find((c) => !before.has(c.id) && c.assetId === asset.id);
        if (clip && (a.optNum("start") !== undefined || a.optStr("trackId"))) {
          const { moveClip } = await import("../../src/core/ops");
          st.mutate("place import", (pr) => moveClip(pr, clip.id, a.num("start", clip.start), a.optStr("trackId")));
        }
        out.push({ assetId: asset.id, name: asset.name, kind: asset.kind, duration: asset.duration, clipId: clip?.id });
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
