/**
 * Remote agent link. The app polls a small relay (Cloudflare Worker, see /relay) for command batches that
 * Claude or another AI queued through the relay's MCP endpoint, runs them here, and posts the results back.
 * The device key in the URL is the only credential; it never leaves the person's own relay.
 */
import type { Studio } from "../app/studio";
import { runBatch, type Command } from "./api";
import { kvGet, kvSet } from "../core/storage";

export interface RemoteConfig {
  relay: string;
  key: string;
  enabled: boolean;
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

  constructor(private readonly studio: Studio) {}

  async load(): Promise<void> {
    const c = await kvGet<RemoteConfig>("remote").catch(() => undefined);
    if (c) this.config = c;
    if (!this.config.key) {
      this.config.key = newKey();
      await this.save();
    }
    if (this.config.enabled && this.config.relay) this.start();
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
    this.loop(0);
  }

  stop(): void {
    window.clearTimeout(this.timer);
    this.config.enabled = false;
    void this.save();
    this.set("off");
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

  private loop(delay: number) {
    window.clearTimeout(this.timer);
    this.timer = window.setTimeout(() => void this.poll(), delay);
  }

  private async poll() {
    if (!this.config.enabled || this.running) return;
    const base = this.config.relay.replace(/\/+$/, "");
    this.running = true;
    let next = document.hidden ? 15_000 : 2_500;
    try {
      const res = await fetch(`${base}/device/${this.config.key}/next`, { method: "GET", cache: "no-store" });
      if (res.status === 204) {
        this.set("online");
      } else if (res.ok) {
        this.set("online");
        const body = (await res.json()) as { jobs?: { id: string; commands: Command[]; continueOnError?: boolean }[] };
        for (const job of body.jobs ?? []) {
          this.studio.setStatus(`Agent: running ${job.commands.length} command${job.commands.length > 1 ? "s" : ""}…`);
          const results = await runBatch(this.studio, job.commands, !!job.continueOnError);
          await fetch(`${base}/device/${this.config.key}/result`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: job.id, results, state: summaryLite(this.studio) }) });
          this.jobsRun++;
          this.studio.setStatus(`Agent: ${results.every((r) => r.ok) ? "done" : "finished with an error"}`);
        }
        next = 300;
      } else {
        throw new Error(`Relay answered ${res.status}`);
      }
      this.lastError = "";
    } catch (e) {
      this.lastError = e instanceof Error ? e.message : String(e);
      this.set("error");
      next = 10_000;
    } finally {
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
