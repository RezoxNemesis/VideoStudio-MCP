/**
 * Code Studio's session: the film that is open, what its code says, what is drawn and what is not — one place that
 * both the Code workspace and Claude's commands use, so whatever Claude does shows in the window as it happens.
 */
import type { Studio } from "../../../src/app/studio";
import { registerCommand } from "../../../src/agent/api";
import { toast } from "../../../src/ui/dom";
import { need } from "../bridge";
import { importPath } from "../importer";
import { film, prepare, type PrepResult } from "./prepare";
import type { BuildOptions, BuildResult, FilmOpen, FilmProgress, FilmStatus } from "./types";

export type CodeEvent = "film" | "files" | "status" | "busy" | "frame" | "library";
const KEY = "vs.code.film";
const dataUrl = (b64: string) => `data:image/jpeg;base64,${b64}`;
const r2 = (n: number) => Math.round(n * 100) / 100;

export interface Busy {
  label: string;
  /** 0…1, or -1 while the amount of work is not known. */
  frac: number;
  detail?: string;
}

export class CodeSession {
  dir: string | null = null;
  info: FilmOpen | null = null;
  status: FilmStatus | null = null;
  /** Why the film's code does not load right now (a mistake in a file), if it does not. */
  error: string | null = null;
  busy: Busy | null = null;
  /** What the last build said needs a look. */
  problems: string[] = [];
  /** The picture last asked for (a frame or a sheet), for the workspace's viewer. */
  shown: { jpeg: string; shot: string; t: number; sheet?: boolean } | null = null;
  lastBuild: (BuildResult & { prep: PrepResult; draft: boolean }) | null = null;
  /** When the last build finished (ms), so a review older than it is known to be out of date. */
  lastBuildAt: number | null = null;
  private listeners = new Set<(e: CodeEvent) => void>();

  on(fn: (e: CodeEvent) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  emit(e: CodeEvent): void {
    for (const fn of this.listeners) {
      try {
        fn(e);
      } catch (err) {
        console.error(err);
      }
    }
  }
  private setBusy(b: Busy | null): void {
    this.busy = b;
    this.emit("busy");
  }
  must(): string {
    if (!this.dir) throw new Error('No film is open. Open one with code.open {film}, or start one with code.new {name}.');
    return this.dir;
  }

  /** Open a film by folder or by name (a film in the Code folder). */
  async open(which: string): Promise<FilmOpen> {
    const f = film();
    let dir = which;
    if (!which.includes("/")) {
      const all = await f.list();
      const hit = all.find((x) => x.name.toLowerCase() === which.toLowerCase()) ?? all.find((x) => x.dir.split("/").pop()!.toLowerCase() === which.toLowerCase().replace(/ /g, "_"));
      if (!hit) throw new Error(`No film called "${which}". Films: ${all.map((x) => x.name).join(", ") || "none yet — code.new {name}"}`);
      dir = hit.dir;
    }
    this.dir = dir;
    this.shown = null;
    this.problems = [];
    this.lastBuild = null;
    try {
      localStorage.setItem(KEY, dir);
    } catch {
      /* private mode */
    }
    await this.refresh();
    this.emit("film");
    if (!this.info) throw new Error(this.error ?? "The film could not be opened.");
    return this.info;
  }
  /** Reopen the film the person had open last time (quietly). */
  async restore(): Promise<void> {
    let dir: string | null = null;
    try {
      dir = localStorage.getItem(KEY);
    } catch {
      /* private mode */
    }
    if (!dir || this.dir) return;
    this.dir = dir;
    await this.refresh();
    if (!this.info && !this.error) this.dir = null;
    this.emit("film");
  }
  /** Read the film's code again: what it is now, and what of it is already drawn. */
  async refresh(): Promise<void> {
    const dir = this.dir;
    if (!dir) return;
    try {
      this.info = await film().open(dir);
      this.error = null;
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      if (/Not a code film|ENOENT/.test(this.error)) {
        this.info = null;
        this.status = null;
      }
      this.emit("files");
      this.emit("status");
      return;
    }
    this.emit("files");
    try {
      this.status = await film().status(dir);
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
    }
    this.emit("status");
  }
  async write(file: string, text: string): Promise<{ file: string; bytes: number; loads: boolean; error?: string }> {
    const r = await film().write(this.must(), file, text);
    await this.refresh();
    return { ...r, loads: !this.error, error: this.error ?? undefined };
  }
  async frame(shot: string, t?: number, lang?: string, scale = 0.5): Promise<{ jpeg: string; shot: string; t: number; width: number; height: number }> {
    const sh = this.info?.shots.find((s) => s.id === shot);
    const r = await film().frame(this.must(), shot, t ?? (sh ? sh.dur / 2 : 0), lang, scale);
    this.shown = { jpeg: dataUrl(r.jpeg), shot, t: r.t };
    this.emit("frame");
    return { ...r, jpeg: this.shown.jpeg };
  }
  async sheet(shot: string, p: { times?: number[]; count?: number; cols?: number; cell?: number; lang?: string } = {}): Promise<{ jpeg: string; times: number[] }> {
    const r = await film().sheet(this.must(), shot, p);
    this.shown = { jpeg: dataUrl(r.jpeg), shot, t: r.times[0] ?? 0, sheet: true };
    this.emit("frame");
    return { jpeg: this.shown.jpeg, times: r.times };
  }
  /**
   * Bring the film up to date: make the voices, motion and sounds it still needs, draw the shots that changed (and
   * only those), mix the sound and join the film. draft: a quick small-size version.
   */
  async build(o: Partial<BuildOptions> & { draft?: boolean } = {}): Promise<BuildResult & { prep: PrepResult; draft: boolean; problems: string[] }> {
    const dir = this.must();
    if (this.busy) throw new Error(`Already working: ${this.busy.label}. Stop it with code.stop first.`);
    const draft = !!o.draft;
    let off: (() => void) | null = null;
    try {
      this.setBusy({ label: "Getting ready", frac: -1 });
      const prep = await prepare(dir, (m) => this.setBusy({ label: m.phase === "voice" ? "Speaking the lines" : m.phase === "motion" ? "Solving the performance" : "Making the sound", frac: m.total ? m.done / m.total : -1, detail: m.what }));
      await this.refresh();
      if (this.error) throw new Error(this.error);
      this.setBusy({ label: "Looking for what changed", frac: -1 });
      off = film().onProgress((e: FilmProgress) => {
        if (e.dir !== dir) return;
        if (e.phase === "render") this.setBusy({ label: e.shots ? `Drawing ${e.shots} shot${e.shots === 1 ? "" : "s"}` : "Nothing to draw", frac: 0, detail: e.shots ? `${(e.ids ?? []).slice(0, 6).join(" ")}${(e.ids ?? []).length > 6 ? " …" : ""}` : "every shot is already right" });
        else if (e.phase === "frames") this.setBusy({ label: `Drawing · ${e.shotsDone}/${e.shots} shots`, frac: e.total ? (e.done ?? 0) / e.total : -1, detail: e.shot });
        else if (e.phase === "shot") this.emit("status");
        else if (e.phase === "sound") this.setBusy({ label: "Mixing the sound", frac: -1, detail: e.lang });
        else if (e.phase === "join") this.setBusy({ label: "Joining the film", frac: -1, detail: e.lang });
      });
      const { draft: _d, ...rest } = o;
      void _d;
      const r = await film().build({ ...rest, dir, scale: draft ? (o.scale ?? 0.5) : o.scale });
      this.problems = prep.problems;
      this.lastBuild = { ...r, prep, draft };
      this.lastBuildAt = Date.now();
      return { ...r, prep, draft, problems: prep.problems };
    } finally {
      off?.();
      this.setBusy(null);
      await this.refresh();
    }
  }
  async stop(): Promise<void> {
    if (this.dir) await film().cancel(this.dir);
  }
  /** Switch shots off (they are left out of the film, nothing is deleted) or back on. */
  async shots(off: string[], on: string[]): Promise<string[]> {
    const dir = this.must(), c = await film().config(dir);
    const set = new Set(c.off ?? []);
    const known = new Set((this.info?.shots ?? []).map((s) => s.id));
    for (const id of [...off, ...on]) if (!known.has(id)) throw new Error(`No shot ${id}. Shots: ${[...known].slice(0, 60).join(" ")}`);
    for (const id of off) set.add(id);
    for (const id of on) set.delete(id);
    await film().config(dir, { off: [...set] });
    await this.refresh();
    return [...set];
  }
  /** Paths of the joined films on disk (per language), if they were made. */
  filmFile(lang?: string): string | null {
    const f = this.lastBuild?.films.find((x) => !lang || x.file.includes(`.${lang}.`)) ?? this.lastBuild?.films[0];
    return f?.file ?? null;
  }
}

export const code = new CodeSession();

/** A short account of a film for Claude: sequences, shots with their state, what is missing. */
function account(c: CodeSession) {
  const st = c.status, info = c.info;
  if (!info) return { dir: c.dir, error: c.error };
  const state = (id: string) => {
    const v = st?.shots.find((s) => s.id === id)?.variants ?? [];
    return v.length && v.every((x) => x.state === "ready") ? "ready" : v.some((x) => x.state === "error") ? "error" : v.some((x) => x.state === "changed") ? "changed" : "new";
  };
  return {
    dir: info.dir, name: info.config.name, size: `${info.config.width}x${info.config.height}`, fps: info.config.fps, langs: info.config.langs, seconds: info.total,
    error: c.error ?? undefined,
    sequences: info.seqs.map((s) => ({ id: s.id, file: s.file, seconds: s.seconds, lines: s.lines })),
    shots: info.shots.map((s) => ({ id: s.id, start: s.start, seconds: s.dur, state: s.off ? "off" : state(s.id), note: s.note || undefined })),
    toDraw: info.shots.filter((s) => !s.off && state(s.id) !== "ready").length,
    needs: st?.needs,
    files: info.files.filter((f) => f.file !== "vs.js").map((f) => f.file),
  };
}

export function registerCodeCommands(s: Studio, go: () => void): void {
  const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));
  registerCommand(
    "code.help",
    "How to write films as code in Code Studio: the whole reference (film folder, sequences, script and voices, performances, cameras, faces, sets, people, sound, effects) with a worked example. Read it before writing a film.",
    async () => ({ reference: await film().reference(), commands: "code.list, code.new, code.open, code.status, code.files, code.read, code.write, code.patch, code.remove, code.frame, code.sheet, code.build, code.stop, code.shots, code.check, code.export, code.toTimeline, code.config, code.people, code.person, code.sets" }),
  );
  registerCommand("code.list", "The code films on this PC, the templates a new one can start from, and where films are kept.", async () => {
    const i = await film().info();
    return { films: (await film().list()).map((f) => ({ name: f.name, dir: f.dir, size: `${f.width}x${f.height}`, fps: f.fps, langs: f.langs })), templates: i.templates, folder: i.root, open: code.dir, drawsAtOnce: i.workers };
  });
  registerCommand(
    "code.new",
    '{name, template?="starter", width?, height?, fps?, langs?: ["en", "hi" …], look?: anime (2d) | multiplane (2.5d) | soft3d (3d) | cel3d (3.5d) | immersive (4d) | paper | puppet | flat | pixel | sketch | manga} Start a new code film from a template and open it. Returns its files; read them with code.read, change them with code.write / code.patch, then code.build.',
    async (_s, a) => {
      const cfg: Record<string, unknown> = {};
      for (const k of ["width", "height", "fps"]) if (a.optNum(k) !== undefined) cfg[k] = a.num(k);
      if (Array.isArray(a.any("langs"))) cfg.langs = (a.any("langs") as unknown[]).map(String);
      if (a.any("look") !== undefined) cfg.look = a.any("look");
      const dir = await film().create(a.str("name"), a.str("template", "starter"), cfg);
      await code.open(dir);
      go();
      return account(code);
    },
  );
  registerCommand("code.open", "{film: name or folder} Open a code film: its sequences, shots (with what is already drawn) and files.", async (_s, a) => {
    await code.open(a.str("film"));
    go();
    return account(code);
  });
  registerCommand("code.status", "The open film now: every shot with its state (ready = drawn and still right, changed = must be drawn again, new, off), what is missing (voices, motion, sounds) and any mistake that stops the code from loading.", async () => {
    code.must();
    await code.refresh();
    return account(code);
  });
  registerCommand("code.files", "The open film's own files (code and data).", async () => {
    code.must();
    await code.refresh();
    return { dir: code.dir, files: (code.info?.files ?? []).map((f) => ({ file: f.file, bytes: f.bytes })), error: code.error ?? undefined };
  });
  registerCommand("code.read", "{file, from?, to? (line numbers)} Read one of the film's files.", async (_s, a) => {
    const text = await film().read(code.must(), a.str("file"));
    const lines = text.split("\n"), from = Math.max(1, a.num("from", 1)), to = Math.min(lines.length, a.num("to", lines.length));
    return { file: a.str("file"), lines: lines.length, from, to, text: from === 1 && to === lines.length ? text : lines.slice(from - 1, to).join("\n") };
  });
  registerCommand(
    "code.write",
    "{file, text} Write a file of the film (new or whole replacement). The film is read again at once: the answer says whether the code loads, and the mistake if it does not. Nothing is drawn until code.build.",
    async (_s, a) => {
      const r = await code.write(a.str("file"), a.str("text"));
      return { ...r, toDraw: code.info ? account(code).toDraw : undefined };
    },
  );
  registerCommand(
    "code.patch",
    "{file, find, replace, all?=false} Change part of a file: `find` must be in it exactly once (or give all:true). The quickest way to fix one shot — only shots whose picture changes are drawn again.",
    async (_s, a) => {
      const file = a.str("file"), find = a.str("find"), text = await film().read(code.must(), file);
      const n = text.split(find).length - 1;
      if (!find || n === 0) throw new Error("`find` is not in the file. Read the lines around it with code.read and copy the text exactly.");
      if (n > 1 && !a.bool("all", false)) throw new Error(`\`find\` is in the file ${n} times. Give more of the text around it, or all:true.`);
      const r = await code.write(file, a.bool("all", false) ? text.split(find).join(str(a.any("replace"))) : text.replace(find, () => str(a.any("replace"))));
      return { ...r, changed: n, toDraw: code.info ? account(code).toDraw : undefined };
    },
  );
  registerCommand("code.remove", "{file} Take a file out of the film (it is kept in the film's .vs/trash, never deleted for good).", async (_s, a) => {
    const r = await film().remove(code.must(), a.str("file"));
    await code.refresh();
    return r;
  });
  registerCommand(
    "code.frame",
    "{shot, t? (seconds into the shot, default its middle), lang?, scale?=0.5} One frame of a shot as it will be in the film, drawn now from the code (no need to draw what comes before). Look at frames while writing; build when it reads right.",
    async (_s, a) => {
      go();
      const r = await code.frame(a.str("shot"), a.optNum("t"), a.optStr("lang"), a.num("scale", 0.5));
      return { jpeg: r.jpeg, shot: r.shot, t: r.t, width: r.width, height: r.height };
    },
  );
  registerCommand(
    "code.sheet",
    "{shot, times?: [s…] | count?=6, cols?=3, cell?=0.5, lang?} Several moments of a shot in one picture, drawn in order (cloth and hair as in the film). The way to check movement, staging and continuity of a shot.",
    async (_s, a) => {
      go();
      const times = a.any("times");
      return code.sheet(a.str("shot"), { times: Array.isArray(times) ? times.map(Number) : undefined, count: a.optNum("count"), cols: a.optNum("cols"), cell: a.optNum("cell"), lang: a.optStr("lang") });
    },
  );
  registerCommand(
    "code.build",
    '{draft?=false (a quick small version), only?: [shot ids] (draw just these, no joining), force?=false, langs?, subs?: "soft" (a subtitle track, default) | "burn" | "none", sound?=true} Bring the film up to date: speak new lines, solve changed performances, make new sounds, draw ONLY the shots whose picture changed (several at once), mix the sound and join the film without re-encoding. Returns what was drawn, what was reused and the film files.',
    async (_s, a) => {
      go();
      const only = a.any("only"), langs = a.any("langs");
      const r = await code.build({ draft: a.bool("draft", false), only: Array.isArray(only) ? only.map(String) : undefined, force: a.bool("force", false), langs: Array.isArray(langs) ? langs.map(String) : undefined, subs: (a.optStr("subs") as "soft" | "burn" | "none" | undefined) ?? "soft", sound: a.bool("sound", true) });
      return {
        drew: r.rendered, reused: r.reused, seconds: r2(r.ms / 1000), spoke: r.prep.spoke.length || undefined, solved: r.prep.solved.length ? r.prep.solved : undefined, sounds: r.prep.sounds.length ? r.prep.sounds : undefined,
        films: r.films.map((f) => ({ file: f.file, seconds: f.seconds, MB: r2(f.bytes / 1e6), subtitles: f.subtitles, sound: f.sound })), draft: r.draft || undefined, problems: r.problems.length ? r.problems : undefined,
      };
    },
  );
  registerCommand("code.stop", "Stop the build that is running (shots already drawn are kept).", async () => {
    await code.stop();
    return true;
  });
  registerCommand(
    "code.shots",
    "{off?: [shot ids], on?: [shot ids]} Switch shots off or back on. A shot that is off is left out of the film (the cut closes over it, sound follows); nothing is deleted and nothing else is drawn again.",
    async (_s, a) => {
      const off = a.any("off"), on = a.any("on");
      const list = await code.shots(Array.isArray(off) ? off.map(String) : [], Array.isArray(on) ? on.map(String) : []);
      return { off: list, seconds: code.info?.total };
    },
  );
  registerCommand(
    "code.check",
    "{lang?} Look at every frame of the joined film for parts of the picture that flash for an instant and come back (a body at the lens, a broken shape), and list what the film still needs. Run it after code.build.",
    async (_s, a) => {
      const file = code.filmFile(a.optStr("lang"));
      if (!file) throw new Error("Build the film first (code.build).");
      const r = await film().scan(file);
      return { film: file, ...r, needs: code.status?.needs, problems: code.problems.length ? code.problems : undefined };
    },
  );
  registerCommand(
    "code.export",
    '{lang?, to? (file or folder; default the Videos/VideoStudio folder), subs?: "soft" | "burn" (drawn into the picture: the whole film is encoded once more) | "none", force?=false} Save the finished film where the person keeps videos. Build first. Sensei\'s gate must be open (no MUST findings) unless force: true.',
    async (_s, a) => {
      const dir = code.must(), info = code.info;
      if (!info) throw new Error(code.error ?? "The film does not load.");
      // Sensei's gate: MUST findings are the person's own words — they block an export unless it is forced
      if (!a.bool("force", false)) {
        const { sensei } = await import("../sensei/store");
        let r = await sensei.load(dir);
        if (!r || sensei.stale()) { await sensei.review({ bench: r?.bench.id ?? "youtube", checks: ["motion", "camera", "story"], images: 0, draw: false }); r = await sensei.load(dir); }
        if (r && !r.pass) {
          const must = r.issues.filter((i) => i.sev === "must");
          throw new Error(`Sensei's gate is closed: ${must.length} MUST finding${must.length === 1 ? "" : "s"} (${r.bench.name}), e.g. ${must.slice(0, 3).map((i) => `${i.shot ?? "film"}: ${i.msg}`).join(" | ")}. Fix them (sensei.report {sev: "must"}) or export with force: true.`);
        }
      }
      const lang = a.optStr("lang") ?? info.config.langs[0]!;
      const pc = await need().info();
      let to = a.optStr("to") ?? pc.out.Movies;
      if (!/\.mp4$/i.test(to)) to = `${to.replace(/\/$/, "")}/${info.config.name.replace(/[^\p{L}\p{N} _-]+/gu, "").trim().replace(/\s+/g, "_") || "film"}${info.config.langs.length > 1 ? "_" + lang : ""}.mp4`;
      const r = await film().build({ dir, langs: [lang], subs: (a.optStr("subs") as "soft" | "burn" | "none" | undefined) ?? "soft", out: to });
      const f = r.films[0];
      if (!f) throw new Error("The film was not joined.");
      if (r.rendered.length) await code.refresh();
      return { file: f.file, seconds: f.seconds, MB: r2(f.bytes / 1e6), subtitles: f.subtitles, drewFirst: r.rendered.length || undefined };
    },
  );
  registerCommand("code.toTimeline", "{lang?, start?} Put the finished film on the editor's timeline (to cut it with other footage, grade it, add titles). Build first.", async (st, a) => {
    const file = code.filmFile(a.optStr("lang"));
    if (!file) throw new Error("Build the film first (code.build).");
    const { asset } = await importPath(st, file);
    const { runQuiet } = await import("../../../src/agent/api");
    const r = await runQuiet(st, [{ op: "clip.add", assetId: asset.id, start: a.optNum("start") ?? st.time }]);
    return { assetId: asset.id, clip: r[0]?.ok ? r[0].result : undefined, note: r[0]?.ok ? "On the timeline." : `In the media pool (${r[0]?.error}).` };
  });
  registerCommand("code.config", "{name?, width?, height?, fps?, langs?: [..], crf? (quality, lower is finer; 15), preset?, look?: anime (2d) | multiplane (2.5d) | soft3d (3d) | cel3d (3.5d) | immersive (4d) | paper | puppet | flat | pixel | sketch | manga or {style, …} (the kind of animation the whole film is drawn as; null = anime)} The open film's settings; give values to change them (a new size, frame rate or look means every shot is drawn again).", async (_s, a) => {
    const patch: Record<string, unknown> = {};
    if (a.any("look") !== undefined) patch.look = a.any("look") === "" ? null : a.any("look");
    for (const k of ["width", "height", "fps", "crf"]) if (a.optNum(k) !== undefined) patch[k] = a.num(k);
    for (const k of ["name", "preset"]) if (a.optStr(k) !== undefined) patch[k] = a.str(k);
    if (Array.isArray(a.any("langs"))) patch.langs = (a.any("langs") as unknown[]).map(String);
    const c = await film().config(code.must(), Object.keys(patch).length ? patch : undefined);
    if (Object.keys(patch).length) await code.refresh();
    return c;
  });
  registerCommand("code.people", "{group?, q? (words to look for)} The people library: hundreds of ready characters by group (townsfolk, farmers, nobles, soldiers of four armies, monks, children, sailors, bandits, royals …). Use one in a film as design: person('townsman_07') or with changes person('townsman_07', { top: { color: '#7a2a2a' } }).", async (_s, a) => {
    const r = await film().people(a.optStr("group"));
    const q = (a.optStr("q") ?? "").toLowerCase().split(/\s+/).filter(Boolean);
    const people = r.people.filter((p) => q.every((w) => `${p.id} ${p.group} ${p.about ?? ""}`.toLowerCase().includes(w)));
    return { groups: r.groups, count: people.length, people: (a.optStr("group") || q.length ? people : people.filter((_p, i) => i % 8 === 0)).slice(0, 120).map((p) => ({ id: p.id, group: p.group, body: p.preset, about: p.about })), named: r.cast, note: a.optStr("group") || q.length ? undefined : "A sample of every group; give group or q for all of one kind." };
  });
  registerCommand("code.person", "{id, size?=360} A picture of a library person.", async (_s, a) => ({ id: a.str("id"), jpeg: dataUrl((await film().personThumb(a.str("id"), a.num("size", 360))).jpeg) }));
  registerCommand("code.sets", "The ready-made places a sequence can play in (set: () => town({ time: 'dusk' }) …) — see code.help for their options.", async () => film().sets());
  void s;
  void toast;
}
