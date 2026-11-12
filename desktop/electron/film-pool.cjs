// The film farm: several worker processes that load a code film and draw its shots, one shot each, side by side.
// One more worker (the "desk") stays free for quick questions — a frame under the playhead, what changed — so the
// workspace answers at once while a render is running. A worker that crashes is replaced; the app is not touched.
const { fork } = require("node:child_process");
const os = require("node:os");
const path = require("node:path");

class Worker {
  constructor(pool, name) { this.pool = pool; this.name = name; this.busy = null; this.proc = null; this.ready = null; this.seq = 0; this.start(); }
  start() {
    const p = this.pool;
    this.proc = fork(p.script, [], { execPath: p.execPath, env: { ...process.env, ...p.env }, stdio: ["ignore", "pipe", "pipe", "ipc"], serialization: "advanced" });
    let log = "";
    const keep = (d) => { log = (log + d).slice(-4000); };
    this.proc.stdout.on("data", keep); this.proc.stderr.on("data", keep);
    this.ready = new Promise((res) => {
      const first = (m) => { if (m && m.ready) { this.proc.off("message", first); res(true); } };
      this.proc.on("message", first);
    }).then(() => this.send("init", { ffmpeg: p.ffmpeg, ffprobe: p.ffprobe }));
    this.proc.on("message", (m) => {
      const b = this.busy; if (!b || !m || m.id !== b.id) return;
      if (m.event) { try { b.onEvent && b.onEvent(m); } catch { /* a listener must not break the farm */ } return; }
      this.busy = null;
      if (m.ok) b.resolve(m.value); else b.reject(new Error(m.error || "The film worker failed."));
      p.pump();
    });
    this.proc.on("exit", (code, sig) => {
      const b = this.busy; this.busy = null;
      if (b) b.reject(new Error(`The film worker stopped while working (${sig || "code " + code}).${log ? "\n" + log.split("\n").slice(-6).join("\n") : ""}`));
      if (!p.closed) { this.start(); p.pump(); }
    });
  }
  /** Send one request; the caller makes sure the worker is free. */
  send(method, params, onEvent) {
    return new Promise((resolve, reject) => { const id = `${this.name}.${++this.seq}`; this.busy = { id, resolve, reject, onEvent }; this.proc.send({ id, method, params }); });
  }
  kill() { try { this.proc.kill(); } catch { /* already gone */ } }
}

class FilmPool {
  /**
   * script: the worker file · execPath: the program that runs it as Node · env: extra environment (ELECTRON_RUN_AS_NODE)
   * ffmpeg/ffprobe: the encoder programs · size: how many shots are drawn at once
   */
  constructor(o) {
    this.script = o.script; this.execPath = o.execPath || process.execPath; this.env = o.env || {};
    this.ffmpeg = o.ffmpeg || "ffmpeg"; this.ffprobe = o.ffprobe || "ffprobe";
    const threads = os.cpus().length, mem = os.totalmem() / 2 ** 30;
    this.size = Math.max(1, Math.min(o.size || Math.max(1, Math.floor(threads / 2)), 8, Math.max(1, Math.floor((mem - 3) / 1.2))));
    this.closed = false; this.queue = []; this.workers = []; this.desk = null; this.cancelled = new Set();
  }
  boot() {
    if (this.desk) return;
    this.desk = new Worker(this, "desk");
    for (let i = 0; i < this.size; i++) this.workers.push(new Worker(this, "w" + i));
  }
  /** A quick question, answered by the desk worker (in order). */
  ask(method, params, onEvent) { this.boot(); return this.enqueue({ lane: "desk", method, params, onEvent }); }
  /** Heavy work, done by whichever farm worker is free. `group` lets a whole job be cancelled. */
  work(method, params, onEvent, group) { this.boot(); return this.enqueue({ lane: "farm", method, params, onEvent, group }); }
  enqueue(job) { return new Promise((resolve, reject) => { this.queue.push({ ...job, resolve, reject }); this.pump(); }); }
  pump() {
    for (let i = 0; i < this.queue.length; i++) {
      const job = this.queue[i];
      if (job.group && this.cancelled.has(job.group)) { this.queue.splice(i--, 1); job.reject(new Error("Stopped.")); continue; }
      const w = job.lane === "desk" ? (this.desk.busy || this.desk.claimed ? null : this.desk) : this.workers.find((x) => !x.busy && !x.claimed);
      if (!w) continue;
      this.queue.splice(i--, 1); w.claimed = true; w.group = job.group;
      w.ready.then(() => { w.claimed = false; return w.send(job.method, job.params, job.onEvent); }).then(job.resolve, job.reject).finally(() => { w.group = null; this.pump(); });
    }
  }
  /** Stop a running job: what waits is dropped, what is being drawn is stopped (its worker is replaced). */
  cancel(group) {
    this.cancelled.add(group);
    for (const w of this.workers) if (w.group === group && (w.busy || w.claimed)) w.kill();
    this.pump();
    setTimeout(() => this.cancelled.delete(group), 5000);
  }
  /** After a film's files changed in a way a loaded worker cannot pick up (new motion, new voices). */
  async reloadAll() { this.boot(); await Promise.all([this.ask("reload", {}), ...this.workers.map((w) => (w.busy || w.claimed ? null : this.work("reload", {})))]); }
  status() { return { workers: this.size, busy: this.workers.filter((w) => w.busy).length, waiting: this.queue.length, started: !!this.desk }; }
  close() { this.closed = true; for (const w of [this.desk, ...this.workers]) if (w) w.kill(); this.workers = []; this.desk = null; }

  /**
   * Bring a film up to date: find the shots whose result would differ from what is on disk, draw only those (several
   * at once), record them, mix the sound and join the film. Returns what was done.
   *   o: { dir, langs?, only? (shot ids), force?, assemble? (default true), subs?, sound?, scale? }
   *   on(event): { phase, ... } progress for the workspace
   */
  async build(o, on = () => {}) {
    const group = "build:" + o.dir, t0 = Date.now();
    this.cancelled.delete(group);
    const info = await this.ask("open", { dir: o.dir });
    const langs = o.langs && o.langs.length ? o.langs : info.config.langs;
    on({ phase: "check", shots: info.shots.length });
    const st = await this.ask("status", { dir: o.dir, scale: o.scale });
    const jobs = [];
    for (const s of st.shots) {
      if (s.off || (o.only && !o.only.includes(s.id))) continue;
      for (const v of s.variants) { if (!langs.includes(v.lang ?? langs[0]) && v.lang) continue; if (v.error) throw new Error(`${s.id}: ${v.error}`); if (o.force || v.state !== "ready") jobs.push({ id: s.id, lang: v.lang, frames: Math.round(s.dur * info.config.fps) }); }
    }
    const total = jobs.reduce((a, j) => a + j.frames, 0), done = new Map(); let finished = 0;
    on({ phase: "render", shots: jobs.length, of: st.shots.length, frames: total, ids: jobs.map((j) => j.id + (j.lang ? "." + j.lang : "")) });
    const results = [];
    await Promise.all(jobs.map(async (j) => {
      const key = j.id + (j.lang ? "." + j.lang : "");
      const r = await this.work("render", { dir: o.dir, id: j.id, lang: j.lang ?? undefined, scale: o.scale }, (e) => {
        done.set(key, e.frame); let sum = 0; for (const v of done.values()) sum += v;
        on({ phase: "frames", shot: key, frame: e.frame, frames: e.frames, done: sum, total, shotsDone: finished, shots: jobs.length });
      }, group);
      done.set(key, j.frames); finished++;
      await this.ask("commit", { dir: o.dir, entries: [r] });
      results.push(r);
      on({ phase: "shot", shot: key, ms: r.ms, shotsDone: finished, shots: jobs.length });
    }));
    const out = { rendered: results.map((r) => r.key), reused: st.shots.reduce((a, s) => a + s.variants.filter((v) => v.state === "ready").length, 0) - (o.force ? results.length : 0), renderMs: Date.now() - t0, films: [] };
    if (o.assemble !== false && !o.only) {
      for (const lang of langs) {
        on({ phase: "sound", lang });
        if (o.sound !== false) await this.work("mix", { dir: o.dir, lang }, null, group);
        on({ phase: "join", lang });
        out.films.push(await this.work("assemble", { dir: o.dir, lang, subs: o.subs || "soft", sound: o.sound !== false, scale: o.scale, out: o.out && langs.length === 1 ? o.out : undefined }, null, group));
      }
    }
    out.ms = Date.now() - t0;
    on({ phase: "done", ...out });
    return out;
  }
}

module.exports = { FilmPool, defaultScript: path.join(__dirname, "..", "film", "runner", "worker.mjs") };
