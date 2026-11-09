/**
 * Claude link for VideoStudio Desktop: long-polls the desktop worker (/desktop-relay) for command batches that
 * Claude queued through the MCP connector, runs them in this window and posts the results back.
 * The key in the URL is the only credential. It is created on this PC and stays in the app.
 */
import type { Studio } from "../../src/app/studio";
import { runBatch, type Command } from "../../src/agent/api";
import { kvGet, kvSet } from "../../src/core/storage";
import { setRelayBase } from "../../src/agent/remote";

export const DEFAULT_WORKER = "https://videostudio-desktop.prakasharuntandon634.workers.dev";

export interface LinkConfig {
  worker: string;
  key: string;
  enabled: boolean;
}

export type LinkState = "off" | "connecting" | "online" | "error";

export interface Activity {
  at: number;
  jobId: string;
  ops: string[];
  done: number;
  ok?: boolean;
  error?: string;
  ms?: number;
}

function newKey(): string {
  const b = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export class DesktopLink {
  config: LinkConfig = { worker: DEFAULT_WORKER, key: "", enabled: true };
  state: LinkState = "off";
  lastError = "";
  busy = false;
  readonly activity: Activity[] = [];
  private listeners = new Set<() => void>();
  private timer = 0;
  private polling = false;
  private abort: AbortController | null = null;
  private lastInfo = 0;
  private lastPoll = 0;
  appInfo: () => Record<string, unknown> = () => ({});

  constructor(private readonly studio: Studio) {
    setRelayBase(async () => ({ base: `${this.base()}/app/${this.config.key}`, key: this.config.key }));
    window.setInterval(() => {
      if (this.config.enabled && !this.polling && !this.busy && Date.now() - this.lastPoll > 60_000) this.loop(0);
    }, 20_000);
  }

  on(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  private emit() {
    for (const f of this.listeners) f();
  }

  private base() {
    return (this.config.worker || DEFAULT_WORKER).replace(/\/+$/, "");
  }

  mcpUrl(): string {
    return `${this.base()}/mcp/${this.config.key}`;
  }

  async load(): Promise<void> {
    const c = await kvGet<LinkConfig>("desktopLink").catch(() => undefined);
    if (c) this.config = { ...this.config, ...c };
    if (!this.config.key) {
      this.config.key = newKey();
      await this.save();
    }
    if (this.config.enabled) this.start();
  }

  private save() {
    return kvSet("desktopLink", this.config);
  }

  start(): void {
    this.config.enabled = true;
    void this.save();
    this.set("connecting");
    this.loop(0);
  }

  stop(): void {
    this.config.enabled = false;
    void this.save();
    window.clearTimeout(this.timer);
    this.abort?.abort();
    this.set("off");
  }

  async newKey(): Promise<void> {
    this.config.key = newKey();
    await this.save();
    this.abort?.abort();
    this.emit();
  }

  private set(s: LinkState) {
    if (this.state !== s) {
      this.state = s;
      this.emit();
    }
  }

  private loop(delay: number) {
    window.clearTimeout(this.timer);
    this.timer = window.setTimeout(() => void this.poll(), delay);
  }

  private async poll() {
    if (!this.config.enabled || this.polling) return;
    this.polling = true;
    this.lastPoll = Date.now();
    let next = 0;
    try {
      this.abort = new AbortController();
      const ac = this.abort;
      const guard = window.setTimeout(() => ac.abort(), 40_000);
      let q = "?wait=25";
      const sendInfo = Date.now() - this.lastInfo > 5 * 60_000;
      if (sendInfo) q += `&info=${encodeURIComponent(JSON.stringify(this.appInfo()))}`;
      let res: Response;
      try {
        res = await fetch(`${this.base()}/app/${this.config.key}/next${q}`, { cache: "no-store", signal: ac.signal });
      } finally {
        window.clearTimeout(guard);
      }
      if (sendInfo && (res.ok || res.status === 204)) this.lastInfo = Date.now();
      if (res.status === 204) this.set("online");
      else if (res.ok) {
        this.set("online");
        const body = (await res.json()) as { jobs?: { id: string; commands: Command[]; continueOnError?: boolean }[] };
        for (const job of body.jobs ?? []) await this.runJob(job);
      } else throw new Error(`The link answered ${res.status}`);
      this.lastError = "";
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") next = 0;
      else {
        this.lastError = e instanceof Error ? e.message : String(e);
        this.set("error");
        next = 5000;
      }
    } finally {
      this.abort = null;
      this.polling = false;
      if (this.config.enabled) this.loop(next);
    }
  }

  private async post(path: string, body: unknown) {
    const r = await fetch(`${this.base()}/app/${this.config.key}/${path}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    if (!r.ok) throw new Error(`${path}: ${r.status} ${await r.text().catch(() => "")}`);
  }

  private async runJob(job: { id: string; commands: Command[]; continueOnError?: boolean }) {
    const act: Activity = { at: Date.now(), jobId: job.id, ops: job.commands.map((c) => String(c.op ?? "")), done: 0 };
    this.activity.unshift(act);
    if (this.activity.length > 80) this.activity.pop();
    this.busy = true;
    this.emit();
    const n = job.commands.length;
    this.studio.setStatus(`Claude: running ${n} command${n > 1 ? "s" : ""}…`);
    let lastSent = 0;
    let cur = { done: 0, op: "" };
    const send = () => {
      lastSent = Date.now();
      void this.post("progress", { id: job.id, state: { done: cur.done, total: n, op: cur.op, status: this.studio.status, at: Date.now() } }).catch(() => {});
    };
    const beat = window.setInterval(() => Date.now() - lastSent > 4000 && send(), 5000);
    send();
    try {
      const results = await runBatch(this.studio, job.commands, !!job.continueOnError, (done, op) => {
        cur = { done, op };
        act.done = done;
        this.emit();
        if (Date.now() - lastSent > 1500) send();
      });
      const state = { project: this.studio.project.id, name: this.studio.project.name, duration: Math.round(this.studio.duration * 1000) / 1000, size: `${this.studio.project.width}x${this.studio.project.height}`, fps: this.studio.project.fps, time: this.studio.time };
      const text = JSON.stringify(results);
      if (text.length < 1_500_000) await this.post("result", { id: job.id, results, state });
      else {
        const size = 1_400_000;
        const parts = Math.ceil(text.length / size);
        for (let i = 0; i < parts; i++) await this.post("result", { id: job.id, part: i, parts, text: text.slice(i * size, (i + 1) * size), state });
      }
      act.done = n;
      act.ok = results.every((r) => r.ok);
      const bad = results.find((r) => !r.ok);
      if (bad) act.error = `${bad.op}: ${bad.error ?? ""}`;
      act.ms = Date.now() - act.at;
      this.studio.setStatus(act.ok ? "Claude: done" : "Claude: stopped with an error");
    } catch (e) {
      act.ok = false;
      act.error = e instanceof Error ? e.message : String(e);
      await this.post("result", { id: job.id, results: [{ ok: false, op: "link", error: act.error }] }).catch(() => {});
    } finally {
      window.clearInterval(beat);
      this.busy = false;
      this.emit();
    }
  }
}
