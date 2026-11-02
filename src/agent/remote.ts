/**
 * Remote agent link. The app polls a small relay (Cloudflare Worker, see /relay) for command batches that
 * Claude or another AI queued through the relay's MCP endpoint, runs them here, and posts the results back.
 * The device key in the URL is the only credential; it never leaves the person's own relay.
 */
import type { Studio } from "../app/studio";
import { runBatch, type Command } from "./api";
import { kvGet, kvSet } from "../core/storage";
import { agentNative, keepAwake } from "../native/storage";
import type { Notifier } from "../native/notify";

export interface RemoteConfig {
  relay: string;
  key: string;
  enabled: boolean;
  /** Keep the screen on while the link is on, so jobs never wait for the phone to wake. */
  awake?: boolean;
  /** Background agent mode: keep working behind other apps / screen off, and wake the app for new work. */
  background?: boolean;
}

export type RemoteState = "off" | "connecting" | "online" | "error";

export class RemoteLink {
  config: RemoteConfig = { relay: "", key: "", enabled: false };
  state: RemoteState = "off";
  lastError = "";
  jobsRun = 0;
  onState: () => void = () => {};
  private timer = 0;
  private running = false;
  private awakeOn = false;
  notifier: Notifier | null = null;
  private seenFiles = new Set<string>();
  private lastInboxCheck = 0;
  private abort: AbortController | null = null;

  constructor(private readonly studio: Studio) {
    // Back in the foreground: check for work right away instead of waiting out a slow background poll.
    document.addEventListener("visibilitychange", () => {
      if (!this.config.enabled) return;
      this.syncAwake();
      if (!document.hidden) {
        this.abort?.abort();
        this.loop(0);
      }
    });
  }

  /** Hold the screen on while linked and visible (the setting can switch this off). */
  private syncAwake() {
    const want = this.config.enabled && this.config.awake !== false && !document.hidden;
    if (want !== this.awakeOn) {
      this.awakeOn = want;
      void keepAwake(want);
    }
  }

  async load(): Promise<void> {
    const c = await kvGet<RemoteConfig>("remote").catch(() => undefined);
    if (c) this.config = c;
    if (!this.config.key) {
      this.config.key = newKey();
      await this.save();
    }
    if (this.config.enabled && this.config.relay) this.start();
    // A job that was running when the app stopped (closed by the system, e.g. out of memory): report it as failed
    // with the last step it reached, instead of leaving it "running" forever.
    try {
      const raw = localStorage.getItem("vs.activeJob");
      if (raw && this.config.relay) {
        localStorage.removeItem("vs.activeJob");
        const a = JSON.parse(raw) as { id: string; op: string; status: string; done: number };
        const base = this.config.relay.replace(/\/+$/, "");
        void fetch(`${base}/device/${this.config.key}/result`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ id: a.id, results: [{ ok: false, op: a.op, error: `The app was stopped by the phone while working (step ${a.done + 1}: ${a.op}; last status: "${a.status}"). Usually this means it ran out of memory.` }] }),
        }).catch(() => {});
      }
    } catch {
      /* ignore */
    }
    // Work the background service collected while the app was closed.
    const held = await agentNative.takeHeld();
    if (held.length && this.config.relay) void this.runJobs(held as { id: string; commands: Command[]; continueOnError?: boolean }[]);
  }

  /** Background agent mode on/off (Android): a small "ready" notification keeps the engine running. */
  async setBackground(on: boolean): Promise<{ canAutoOpen: boolean }> {
    this.config.background = on;
    await this.save();
    const r = await agentNative.mode(on && this.config.enabled, this.config.relay, this.config.key);
    this.onState();
    return r;
  }

  async save(): Promise<void> {
    await kvSet("remote", this.config);
  }

  mcpUrl(): string {
    return this.config.relay ? `${this.config.relay.replace(/\/+$/, "")}/mcp/${this.config.key}` : "";
  }

  start(): void {
    this.stop();
    this.config.enabled = true;
    void this.save();
    this.set("connecting");
    this.syncAwake();
    if (this.config.background) void agentNative.mode(true, this.config.relay, this.config.key);
    this.loop(0);
  }

  stop(): void {
    window.clearTimeout(this.timer);
    this.abort?.abort();
    this.config.enabled = false;
    void this.save();
    this.syncAwake();
    void agentNative.mode(false);
    this.set("off");
  }

  /** Notify about files that arrived in the relay inbox (checked at most every 30 s). */
  private async checkInbox() {
    if (!this.notifier?.settings.enabled || !this.notifier.settings.inbox) return;
    const now = Date.now();
    if (now - this.lastInboxCheck < 30_000) return;
    this.lastInboxCheck = now;
    try {
      const files = await listInbox();
      const fresh = files.filter((f) => f.complete && !this.seenFiles.has(f.id));
      for (const f of files) this.seenFiles.add(f.id);
      if (fresh.length && this.seenFiles.size > fresh.length) void this.notifier.send("inbox", "New file for VideoStudio", fresh.length === 1 ? `${fresh[0]!.name} is ready to import` : `${fresh.length} files are ready to import`);
    } catch {
      // Inbox is optional.
    }
  }

  setAwake(on: boolean): void {
    this.config.awake = on;
    void this.save();
    this.syncAwake();
  }

  async rotateKey(): Promise<void> {
    this.config.key = newKey();
    await this.save();
    this.onState();
  }

  private set(s: RemoteState) {
    this.state = s;
    this.onState();
  }

  private async runJobs(jobs: { id: string; commands: Command[]; continueOnError?: boolean }[]) {
    const base = this.config.relay.replace(/\/+$/, "");
    for (const job of jobs) {
      const n = job.commands.length;
      this.studio.setStatus(`Agent: running ${n} command${n > 1 ? "s" : ""}…`);
      void this.notifier?.send("jobStart", "Claude is working", `Running ${n} command${n > 1 ? "s" : ""} in VideoStudio`);
      void agentNative.busy(true);
      let lastSent = 0;
      let cur = { done: 0, op: "" };
      const send = () => {
        lastSent = Date.now();
        const st = { done: cur.done, total: n, op: cur.op, status: this.studio.status, at: Date.now() };
        try {
          localStorage.setItem("vs.activeJob", JSON.stringify({ id: job.id, ...st }));
        } catch {
          /* ignore */
        }
        void fetch(`${base}/device/${this.config.key}/progress`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: job.id, state: st }) }).catch(() => {});
      };
      const progress = (done: number, op: string) => {
        cur = { done, op };
        if (Date.now() - lastSent < 2000) return;
        send();
      };
      // Heartbeat: long steps (AI, renders) report what they are doing, so a stop can be traced.
      const beat = window.setInterval(() => {
        if (Date.now() - lastSent > 4000) send();
      }, 5000);
      send();
      try {
        const results = await runBatch(this.studio, job.commands, !!job.continueOnError, progress);
        await fetch(`${base}/device/${this.config.key}/result`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: job.id, results, state: summaryLite(this.studio) }) });
        this.jobsRun++;
        const ok = results.every((r) => r.ok);
        this.studio.setStatus(`Agent: ${ok ? "done" : "finished with an error"}`);
        const bad = results.find((r) => !r.ok);
        void this.notifier?.send("jobDone", ok ? "Claude finished" : "Claude stopped with an error", ok ? `${results.length} steps done in “${this.studio.project.name}”` : `${bad?.op}: ${bad?.error ?? ""}`.slice(0, 180));
      } finally {
        window.clearInterval(beat);
        try {
          localStorage.removeItem("vs.activeJob");
        } catch {
          /* ignore */
        }
        void agentNative.busy(false);
      }
    }
  }

  private lastPoll = 0;
  private watchdog = 0;
  private loop(delay: number) {
    window.clearTimeout(this.timer);
    this.timer = window.setTimeout(() => void this.poll(), delay);
    // Watchdog: if polling ever stalls (a lost timer after the app was frozen in the background), restart it.
    if (!this.watchdog)
      this.watchdog = window.setInterval(() => {
        if (!this.config.enabled || this.running) return;
        if (Date.now() - this.lastPoll > 60_000) this.loop(0);
      }, 30_000);
  }

  private async poll() {
    if (!this.config.enabled || this.running) return;
    this.lastPoll = Date.now();
    const base = this.config.relay.replace(/\/+$/, "");
    this.running = true;
    // In the foreground the relay holds the request open until a job arrives (long poll), so jobs start
    // within a second. In the background the WebView is throttled, so fall back to a slow check.
    const hidden = document.hidden && !this.config.background;
    let next = hidden ? 15_000 : 0;
    try {
      this.abort = new AbortController();
      // A long poll can hang forever if the network changes under it (Wi-Fi ↔ mobile, sleep). Never wait
      // longer than the relay's own 20 s hold plus margin, then try again.
      const ac = this.abort;
      const guard = window.setTimeout(() => ac.abort(), 35_000);
      let res: Response;
      try {
        res = await fetch(`${base}/device/${this.config.key}/next${hidden ? "" : "?wait=20"}`, { method: "GET", cache: "no-store", signal: ac.signal });
      } finally {
        window.clearTimeout(guard);
      }
      if (res.status === 204) {
        this.set("online");
      } else if (res.ok) {
        this.set("online");
        const body = (await res.json()) as { jobs?: { id: string; commands: Command[]; continueOnError?: boolean }[] };
        await this.runJobs(body.jobs ?? []);
        next = 0;
      } else {
        throw new Error(`Relay answered ${res.status}`);
      }
      this.lastError = "";
      void this.checkInbox();
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") {
        next = 0;
      } else {
        this.lastError = e instanceof Error ? e.message : String(e);
        this.set("error");
        next = 5_000;
      }
    } finally {
      this.abort = null;
      this.running = false;
      if (this.config.enabled) this.loop(next);
    }
  }
}

function summaryLite(s: Studio) {
  return { project: s.project.id, name: s.project.name, duration: Math.round(s.duration * 1000) / 1000, time: s.time };
}

function newKey(): string {
  const b = crypto.getRandomValues(new Uint8Array(24));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export interface InboxFile {
  id: string;
  name: string;
  mime: string;
  size: number;
  complete: boolean;
  created: number;
}

async function relayBase(): Promise<{ base: string; key: string }> {
  const c = await kvGet<RemoteConfig>("remote").catch(() => undefined);
  if (!c?.relay || !c.key) throw new Error("Set up the remote link (Agent tab) first.");
  return { base: `${c.relay.replace(/\/+$/, "")}/device/${c.key}`, key: c.key };
}

/** Files waiting in the relay inbox (shared in chat, uploaded by an agent). */
export async function listInbox(): Promise<InboxFile[]> {
  const { base } = await relayBase();
  const r = await fetch(`${base}/files`, { cache: "no-store" });
  if (!r.ok) throw new Error(`Inbox unavailable (${r.status})`);
  return ((await r.json()) as { files: InboxFile[] }).files;
}

/** Download an inbox file; it is removed from the relay once it is safely in the project. */
export async function fetchInbox(id: string): Promise<{ blob: Blob; name: string; done: () => Promise<void> }> {
  const { base } = await relayBase();
  const r = await fetch(`${base}/files/${encodeURIComponent(id)}`, { cache: "no-store" });
  if (!r.ok) throw new Error(r.status === 409 ? "That file is still uploading." : `Download failed (${r.status})`);
  const name = decodeURIComponent(r.headers.get("x-file-name") ?? "file");
  const blob = await r.blob();
  return { blob, name, done: async () => void (await fetch(`${base}/files/${encodeURIComponent(id)}`, { method: "DELETE" }).catch(() => {})) };
}
