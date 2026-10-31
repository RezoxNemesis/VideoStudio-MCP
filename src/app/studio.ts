/** The single owner of app state. UI panels and the agent API both edit through `mutate`, so undo covers both. */
import { History } from "../core/history";
import { newProject, projectDuration, uid, type Asset, type Project } from "../core/model";
import { kvGet, kvSet, listProjects, loadProject, requestPersistence, saveProject, putBlob } from "../core/storage";
import { MediaPool, importBlob } from "../media/library";
import { scheduleAudio, type Scheduled } from "../render/audio";
import { VoiceService, type SpeakRequest, type SpeakResult } from "../voice/service";
import { addClip } from "../core/ops";

export type StudioEvent = "change" | "time" | "select" | "play" | "status" | "project";

export class Studio {
  project: Project = newProject();
  readonly history = new History<Project>();
  readonly pool = new MediaPool();
  readonly voice = new VoiceService();
  selection: string | null = null;
  time = 0;
  playing = false;
  status = "";
  private listeners = new Map<StudioEvent, Set<() => void>>();
  private saveTimer = 0;
  private audioCtx: AudioContext | null = null;
  private playback: Scheduled | null = null;
  private playStart = 0;
  private playFrom = 0;
  private raf = 0;
  /** Bumped on any change so renderers know to redraw. */
  revision = 0;

  on(e: StudioEvent, fn: () => void): () => void {
    let s = this.listeners.get(e);
    if (!s) this.listeners.set(e, (s = new Set()));
    s.add(fn);
    return () => s!.delete(fn);
  }

  emit(e: StudioEvent): void {
    for (const fn of this.listeners.get(e) ?? []) {
      try {
        fn();
      } catch (err) {
        console.error(err);
      }
    }
  }

  async boot(): Promise<void> {
    void requestPersistence();
    const last = await kvGet<string>("lastProject").catch(() => undefined);
    const p = last ? await loadProject(last).catch(() => undefined) : undefined;
    if (p) this.project = migrate(p);
    else await this.save();
    this.emit("project");
    this.emit("change");
  }

  /** Apply an edit with undo. Throws through so agents get the error. */
  mutate<T>(label: string, fn: (p: Project) => T): T {
    const before = this.history.wouldRecord(label) ? JSON.stringify(this.project) : null;
    const out = fn(this.project);
    if (before !== null) this.history.push(before, label);
    else this.history.touch(label);
    this.touched();
    return out;
  }

  /** Async edit (imports, voice). History records the state before. */
  async mutateAsync<T>(label: string, fn: (p: Project) => Promise<T>): Promise<T> {
    const before = JSON.stringify(this.project);
    const out = await fn(this.project);
    this.history.push(before, label);
    this.touched();
    return out;
  }

  touched(): void {
    this.revision++;
    if (this.selection && !this.project.tracks.some((t) => t.clips.some((c) => c.id === this.selection))) this.selection = null;
    this.emit("change");
    window.clearTimeout(this.saveTimer);
    this.saveTimer = window.setTimeout(() => void this.save(), 800);
  }

  async save(): Promise<void> {
    await saveProject(this.project);
    await kvSet("lastProject", this.project.id);
  }

  undo(): boolean {
    const r = this.history.undo(this.project);
    if (!r) return false;
    this.project = r.state;
    this.touched();
    this.setStatus(`Undid ${r.label}`);
    return true;
  }

  redo(): boolean {
    const r = this.history.redo(this.project);
    if (!r) return false;
    this.project = r.state;
    this.touched();
    this.setStatus(`Redid ${r.label}`);
    return true;
  }

  async newProject(name: string, width: number, height: number, fps: number): Promise<Project> {
    this.pause();
    await this.save();
    this.project = newProject(name, width, height, fps);
    this.history.clear();
    this.selection = null;
    this.time = 0;
    await this.save();
    this.revision++;
    this.emit("project");
    this.emit("change");
    this.emit("time");
    return this.project;
  }

  async open(id: string): Promise<Project> {
    const p = await loadProject(id);
    if (!p) throw new Error(`No project ${id}`);
    this.pause();
    await this.save();
    this.project = migrate(p);
    this.history.clear();
    this.selection = null;
    this.time = 0;
    await kvSet("lastProject", id);
    this.revision++;
    this.emit("project");
    this.emit("change");
    this.emit("time");
    return this.project;
  }

  list() {
    return listProjects();
  }

  select(id: string | null): void {
    this.selection = id;
    this.emit("select");
  }

  setStatus(s: string): void {
    this.status = s;
    this.emit("status");
  }

  get duration(): number {
    return projectDuration(this.project);
  }

  /* -------------------------------- imports ------------------------------- */

  async importFiles(files: Iterable<File>, addToTimeline = true): Promise<Asset[]> {
    const out: Asset[] = [];
    for (const f of files) {
      this.setStatus(`Importing ${f.name}…`);
      try {
        const a = await this.mutateAsync(`import ${f.name}`, async (p) => {
          const asset = await importBlob(p, f, f.name, "user");
          if (addToTimeline) addClip(p, { assetId: asset.id });
          return asset;
        });
        out.push(a);
      } catch (e) {
        this.setStatus(e instanceof Error ? e.message : String(e));
        throw e;
      }
    }
    this.setStatus(out.length ? `Imported ${out.length} file${out.length > 1 ? "s" : ""}` : "");
    return out;
  }

  /** Add an already-made blob (voice, render, drawing) as an asset. */
  async addBlobAsset(blob: Blob, name: string, by: Asset["createdBy"], extra: Partial<Asset> = {}): Promise<Asset> {
    return await this.mutateAsync(`add ${name}`, async (p) => {
      const a = await importBlob(p, blob, name, by);
      Object.assign(a, extra);
      return a;
    });
  }

  async speak(req: SpeakRequest, onClip?: (done: number, total: number) => void): Promise<{ result: SpeakResult; asset: Asset }> {
    const result = await this.voice.speak(req, onClip);
    const label = req.text.trim().split(/\s+/).slice(0, 5).join(" ");
    const asset = await this.addBlobAsset(result.wav, `Voice – ${label}.wav`, "voice", { mouth: result.mouth });
    return { result, asset };
  }

  /* ------------------------------- playback ------------------------------- */

  seek(t: number): void {
    const wasPlaying = this.playing;
    if (wasPlaying) this.pause();
    this.time = Math.max(0, Math.min(t, Math.max(this.duration, 0)));
    this.emit("time");
    if (wasPlaying) void this.play();
  }

  async play(): Promise<void> {
    if (this.playing) return;
    if (this.time >= this.duration - 0.05) this.time = 0;
    this.audioCtx ??= new AudioContext({ latencyHint: "playback" });
    await this.audioCtx.resume();
    this.playing = true;
    this.emit("play");
    const ctx = this.audioCtx;
    const when = ctx.currentTime + 0.12;
    this.playFrom = this.time;
    this.playStart = when;
    const sched = await scheduleAudio(ctx, this.project, this.pool, this.time, this.duration, when);
    if (!this.playing) {
      sched.stop();
      return;
    }
    this.playback = sched;
    const loop = () => {
      if (!this.playing) return;
      this.time = Math.min(this.duration, this.playFrom + Math.max(0, ctx.currentTime - this.playStart));
      this.emit("time");
      if (this.time >= this.duration) {
        this.pause();
        return;
      }
      this.raf = requestAnimationFrame(loop);
    };
    this.raf = requestAnimationFrame(loop);
  }

  pause(): void {
    if (!this.playing) return;
    this.playing = false;
    cancelAnimationFrame(this.raf);
    this.playback?.stop();
    this.playback = null;
    this.emit("play");
    this.emit("time");
  }

  toggle(): void {
    if (this.playing) this.pause();
    else void this.play();
  }

  /** Export the project as portable JSON (media stays on the device). */
  exportJson(): Blob {
    return new Blob([JSON.stringify(this.project, null, 2)], { type: "application/json" });
  }

  async importJson(text: string): Promise<Project> {
    const p = migrate(JSON.parse(text) as Project);
    p.id = uid("prj");
    await saveProject(p);
    return await this.open(p.id);
  }

  /** Save a drawing frame as a PNG image asset. */
  async addImageAsset(blob: Blob, name: string): Promise<Asset> {
    const id = uid("ast");
    const ref = await putBlob(id, blob);
    const bmp = await createImageBitmap(blob);
    const a: Asset = { id, kind: "image", name, mime: blob.type, size: blob.size, duration: 0, width: bmp.width, height: bmp.height, ref, createdBy: "render" };
    bmp.close();
    this.mutate(`add ${name}`, (p) => p.assets.push(a));
    return a;
  }
}

function migrate(p: Project): Project {
  p.scenes ??= [];
  p.markers ??= [];
  p.background ??= "#000000";
  for (const t of p.tracks) for (const c of t.clips) {
    c.transform ??= {};
    c.grade ??= {};
    c.transitionIn ??= { kind: "none", dur: 0.5 };
    c.transitionOut ??= { kind: "none", dur: 0.5 };
  }
  return p;
}
