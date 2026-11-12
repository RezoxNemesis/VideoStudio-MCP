/**
 * Sensei's state in the window: the open film's latest review, what it is doing now, and its jobs. The Sensei page,
 * the Code page's problems list and Claude's commands all read the same store, so everyone sees the same verdict.
 */
import { film } from "../film/prepare";
import { code } from "../film/session";
import type { SenseiDigest, SenseiIssue, SenseiReport } from "./types";

export type SenseiEvent = "report" | "busy" | "tasks" | "chat" | "brain";
export interface SenseiBusy { label: string; frac: number; detail?: string }

class SenseiStore {
  report: SenseiReport | null = null;
  reportDir: string | null = null;
  busy: SenseiBusy | null = null;
  private fns = new Set<(e: SenseiEvent) => void>();
  private unProgress: (() => void) | null = null;
  on(fn: (e: SenseiEvent) => void): () => void { this.fns.add(fn); return () => this.fns.delete(fn); }
  emit(e: SenseiEvent): void { for (const f of this.fns) { try { f(e); } catch (err) { console.error(err); } } }
  setBusy(b: SenseiBusy | null): void { this.busy = b; this.emit("busy"); }

  /** The latest review of the open film (from disk when the film changed). */
  async load(dir = code.dir): Promise<SenseiReport | null> {
    if (!dir) { this.report = null; this.reportDir = null; this.emit("report"); return null; }
    if (this.reportDir === dir && this.report) return this.report;
    this.report = await film().senseiReport(dir).catch(() => null);
    this.reportDir = dir; this.emit("report");
    return this.report;
  }
  /** Judge the open film (or some of its shots / sequences). */
  async review(opts: Record<string, unknown> = {}): Promise<{ digest: SenseiDigest; files: { json: string; md: string } }> {
    const dir = code.must();
    if (!this.unProgress) {
      this.unProgress = film().onSensei((m) => {
        if (m.dir !== code.dir) return;
        if (m.phase === "shots") this.setBusy({ label: "Sensei is looking at every shot", frac: m.of ? (m.done ?? 0) / m.of : -1, detail: m.shot });
        else if (m.phase === "pictures") this.setBusy({ label: "Sensei is marking the problems", frac: m.of ? (m.done ?? 0) / m.of : -1 });
        else if (m.phase === "finish") this.setBusy({ label: "Sensei is weighing cuts, story, sound and style", frac: -1 });
      });
    }
    this.setBusy({ label: "Sensei is starting", frac: -1 });
    try {
      const r = await film().sensei(dir, opts);
      this.reportDir = null; await this.load(dir);
      return r;
    } finally { this.setBusy(null); }
  }
  issues(f: { sev?: string; cat?: string; kind?: string; shot?: string; seq?: string } = {}): SenseiIssue[] {
    return (this.report?.issues ?? []).filter((i) => (!f.sev || i.sev === f.sev) && (!f.cat || i.cat === f.cat) && (!f.kind || i.kind === f.kind) && (!f.shot || i.shot === f.shot) && (!f.seq || i.seq === f.seq));
  }
  /** Is the latest review older than the film's last build (or than the code)? */
  stale(): boolean {
    const r = this.report; if (!r) return true;
    const at = Date.parse(r.at), built = code.lastBuildAt ?? 0;
    return built > at;
  }
}
export const sensei = new SenseiStore();
