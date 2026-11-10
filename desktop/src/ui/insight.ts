/**
 * Insight panels for VideoStudio 2:
 *  - LensPanel ("Audience Lens"): looks at the edit the way an audience and a platform probably would — a predicted
 *    score, area scores, a predicted attention curve, findings with one-click fixes, and "Evolve" (apply every safe
 *    fix, then look again). Everything it shows is a prediction to guide the edit, never a promise of views or reach.
 *  - DnaPanel ("Style DNA"): measures the feel of a finished video or of your own projects (cut rhythm, pacing,
 *    colour, titles), keeps it in a library on this PC and transplants it onto the current edit.
 * Both panels only call the analysis that already exists in src/agent/smart.ts and the agent commands; they add no
 * analysis of their own.
 */
import type { Studio } from "../../../src/app/studio";
import { runCommand } from "../../../src/agent/api";
import { SIGNATURE, dnaLibrary, evolveEdit, extractDna, latestLens, latestLensMeta, onDnaLibrary, onLens, projectDna, runLens, transplantDna, type LensMeta } from "../../../src/agent/smart";
import { findClip } from "../../../src/core/model";
import { describeDna, type Dna } from "../../../src/smart/dna";
import { AREA_WEIGHTS, PLATFORMS, evolve, safeArea, type Finding, type LensReport, type Platform, type Severity } from "../../../src/smart/lens";
import { ask, b, h, ic, menu, modal, numRow, panel, selectEl, tc } from "./kit";

/* ------------------------------------------------------------------ shared helpers */

const SVG_NS = "http://www.w3.org/2000/svg";

/** The message of anything thrown. */
function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** An SVG element with attributes. */
function svgEl<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string | number> = {}): SVGElementTagNameMap[K] {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
  return el;
}

/** Seconds as a short label for axes and notes ("4.2 s", "1:05"). */
function shortTime(t: number): string {
  if (!Number.isFinite(t) || t < 0) t = 0;
  if (t < 60) return `${t < 10 ? (Math.round(t * 10) / 10).toFixed(1) : Math.round(t)} s`;
  const m = Math.floor(t / 60);
  const s = Math.floor(t % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

/** "#rrggbb" or "#rgb" with an alpha, as rgba(); other colour strings are returned as they are. */
function withAlpha(colour: string, a: number): string {
  const c = colour.trim();
  const m6 = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(c);
  const m3 = /^#([0-9a-f])([0-9a-f])([0-9a-f])$/i.exec(c);
  const hex = m6 ? [m6[1]!, m6[2]!, m6[3]!] : m3 ? [m3[1]! + m3[1]!, m3[2]! + m3[2]!, m3[3]! + m3[3]!] : null;
  if (!hex) return c;
  const [r, g, bl] = hex.map((x) => parseInt(x, 16));
  return `rgba(${r}, ${g}, ${bl}, ${a})`;
}

/** The modal backdrop the kit's modal() just added (it is always appended last to the body). */
function lastModalBg(): HTMLElement | null {
  const el = document.body.lastElementChild;
  return el instanceof HTMLElement && el.classList.contains("modal-bg") ? el : null;
}

/**
 * Open a kit modal that also closes on Escape and tells us when it was dismissed (backdrop click or Escape), so a
 * waiting promise never hangs. Returns the function that closes it from a button.
 */
function openDialog(title: string, body: HTMLElement, actions: HTMLElement[], onDismiss: () => void): () => void {
  let open = true;
  const m = modal(title, body, actions);
  const bg = lastModalBg();
  bg?.classList.add("in-modal");
  const finish = () => {
    if (!open) return false;
    open = false;
    removeEventListener("keydown", onKey, true);
    return true;
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key !== "Escape") return;
    e.preventDefault();
    e.stopPropagation();
    if (finish()) {
      m.close();
      onDismiss();
    }
  };
  addEventListener("keydown", onKey, true);
  bg?.addEventListener("mousedown", (e) => {
    // The kit removes the backdrop itself on this click; we only record that it was dismissed.
    if (e.target === bg && finish()) onDismiss();
  });
  return () => {
    if (finish()) m.close();
  };
}

/** Ask a yes/no question in a small modal. Resolves false when cancelled or dismissed. */
function confirmBox(title: string, body: string | HTMLElement, okLabel: string, danger = false): Promise<boolean> {
  return new Promise((res) => {
    let close = () => {};
    const ok = b(okLabel, null, () => {
      close();
      res(true);
    }, danger ? "in-danger" : "pri");
    const cancel = b("Cancel", null, () => {
      close();
      res(false);
    });
    close = openDialog(title, typeof body === "string" ? h("div", { class: "in-dialog-text" }, body) : body, [cancel, ok], () => res(false));
    setTimeout(() => ok.focus());
  });
}

/** The "working…" row with an indeterminate bar. */
function workingRow(text: string): HTMLElement {
  return h("div", { class: "in-working", role: "status", "aria-live": "polite" }, h("div", { class: "in-working-text" }, h("span", { class: "in-pulse" }), text), h("div", { class: "in-indet" }, h("i")));
}

/** An inline message box (error, warning or plain note) with an optional close button. */
function noteBox(tone: "err" | "warn" | "info" | "ok", text: string | HTMLElement, onClose?: () => void): HTMLElement {
  return h("div", { class: `in-note in-note-${tone}`, role: tone === "err" ? "alert" : "status" }, h("div", { class: "in-note-text" }, text), onClose ? b(null, "close", onClose, "sm ghost in-note-x", "Dismiss") : null);
}

/** True when the timeline has nothing on it. */
function timelineEmpty(s: Studio): boolean {
  return s.duration <= 0 || !s.project.tracks.some((t) => t.clips.length > 0);
}

/* ================================================================== Audience Lens */

type PlatformChoice = "auto" | Platform;

const SEV_RANK: Record<Severity, number> = { high: 0, medium: 1, low: 2, good: 3 };
const SEV_LABEL: Record<Severity, string> = { high: "High", medium: "Medium", low: "Low", good: "Good" };
const SEV_HELP: Record<Severity, string> = {
  high: "High priority: likely to cost viewers.",
  medium: "Worth a look: may cost some viewers.",
  low: "Small polish.",
  good: "Already working.",
};
const SEV_VAR: Record<Severity, string> = { high: "--danger", medium: "--warn", low: "--accent-2", good: "--ok" };

const WORKING_LOOK = "Listening to the mix and watching the picture…";
const PLATFORM_KEY = "vs.lens.platform";

interface EvolveSummary {
  before: number;
  after: number;
  applied: { id: string; title: string; fix: string }[];
  remaining: Finding[];
  /** A round did not improve the predicted score and was undone. */
  rolledBack: boolean;
  /** Fixes offered in the first round (what the confirm dialog listed). */
  tried: number;
  /** Commands that failed in the rounds that were kept. */
  failed: number;
}

/** One drawn marker on the attention chart, for hover and click. */
interface ChartMark {
  x: number;
  y: number;
  f: Finding;
}

/**
 * The Audience Lens panel. `el` is a complete panel (glass card with its own header); place it in a dock cell.
 * `o.reveal` is called with a timeline time when the user jumps to a finding, so the timeline can scroll to it.
 */
export class LensPanel {
  readonly el: HTMLElement;
  private readonly body: HTMLElement;
  private readonly sel: HTMLSelectElement;
  private readonly lookBtn: HTMLButtonElement;
  private readonly evolveBtn: HTMLButtonElement;
  private readonly statusEl: HTMLElement;
  private readonly evolveEl: HTMLElement;
  private readonly contentEl: HTMLElement;
  private readonly chartWrap: HTMLElement;
  private readonly canvas: HTMLCanvasElement;
  private readonly tip: HTMLElement;

  private platform: PlatformChoice;
  private report: LensReport | null = null;
  /** Studio revision the shown report describes; a different revision means the edit changed since. */
  private reportRev = -1;
  private reportProject = "";
  private busy: string | null = null;
  private error: string | null = null;
  private notice: { tone: "warn" | "info" | "ok"; text: string } | null = null;
  private evolveRes: EvolveSummary | null = null;
  private goodOpen = false;
  private shownStale = false;
  private shownEmpty = false;
  private marks: ChartMark[] = [];
  private hoverX: number | null = null;
  private raf = 0;
  private readonly offs: (() => void)[] = [];

  constructor(
    private readonly s: Studio,
    private readonly o: { reveal?: (t: number) => void } = {},
  ) {
    const saved = localStorage.getItem(PLATFORM_KEY);
    this.platform = saved && (saved === "auto" || saved in PLATFORMS) ? (saved as PlatformChoice) : "auto";

    const p = panel(h("span", { class: "row", style: "gap:6px" }, ic("eye", 14), "Audience Lens"), "in-panel in-lens");
    this.el = p.el;
    this.body = p.body;
    this.body.classList.add("in-body");

    const options: [PlatformChoice, string][] = [["auto", "Auto (from the frame shape)"], ...(Object.keys(PLATFORMS) as Platform[]).map((k) => [k, PLATFORMS[k].name] as [PlatformChoice, string])];
    this.sel = selectEl<PlatformChoice>(options, this.platform, (v) => {
      this.platform = v;
      localStorage.setItem(PLATFORM_KEY, v);
      this.render();
    });
    this.sel.classList.add("in-platform");
    this.sel.title = "Which platform to judge the edit for";
    this.lookBtn = b("Look at the edit", "eye", () => void this.look(), "pri", "Look at the edit the way an audience and the platform would");
    this.evolveBtn = b("Evolve", "wand", () => void this.evolve(), "", "Apply every safe fix, then look again");

    this.statusEl = h("div", { class: "in-status" });
    this.evolveEl = h("div");
    this.contentEl = h("div", { class: "in-content" });

    this.canvas = h("canvas", { class: "in-chart", tabindex: "0", role: "img", "aria-label": "Predicted attention curve. Click to move the playhead there." }) as HTMLCanvasElement;
    this.tip = h("div", { class: "in-chart-tip" });
    this.chartWrap = h("div", { class: "in-chart-wrap" }, this.canvas, this.tip);
    this.bindChart();

    this.body.append(
      h("p", { class: "in-lede" }, "Looks at your edit the way an audience and the platform would. Predictions to guide you, not guarantees."),
      h("div", { class: "in-controls" }, this.sel, h("div", { class: "in-controls-btns" }, this.lookBtn, this.evolveBtn)),
      this.statusEl,
      this.evolveEl,
      this.contentEl,
    );

    const first = latestLens();
    const firstMeta = latestLensMeta();
    if (first && firstMeta) this.adopt(first, firstMeta);

    this.offs.push(
      onLens((r, meta) => {
        this.adopt(r, meta);
        this.render();
      }),
      s.on("change", () => this.onChange()),
      s.on("project", () => this.onProject()),
      s.on("time", () => this.drawSoon()),
    );
    const ro = new ResizeObserver(() => this.drawSoon());
    ro.observe(this.chartWrap);
    this.offs.push(() => ro.disconnect());
    this.render();
  }

  /** Stop listening to the studio (call only if the panel is thrown away for good). */
  dispose(): void {
    for (const f of this.offs.splice(0)) f();
    cancelAnimationFrame(this.raf);
  }

  /* ------------------------------------------------------------ state */

  /**
   * Take a report with what it was measured from (revision and project at the start of the run, whoever started it).
   * A report measured from another project is not shown.
   */
  private adopt(r: LensReport, meta: LensMeta): void {
    if (meta.projectId !== this.s.project.id) return;
    this.report = r;
    this.reportRev = meta.revision;
    this.reportProject = meta.projectId;
  }

  private get stale(): boolean {
    return !!this.report && (this.reportProject !== this.s.project.id || this.s.revision !== this.reportRev);
  }

  private get chosen(): Platform | undefined {
    return this.platform === "auto" ? undefined : this.platform;
  }

  private onChange(): void {
    const empty = timelineEmpty(this.s);
    if (empty !== this.shownEmpty || this.stale !== this.shownStale) this.render();
  }

  private onProject(): void {
    if (this.report && this.reportProject !== this.s.project.id) {
      this.report = null;
      this.evolveRes = null;
      this.error = null;
      this.notice = null;
    }
    this.render();
  }

  private fail(e: unknown): void {
    const msg = errText(e);
    this.error = msg;
    this.s.setStatus(`Audience Lens: ${msg}`);
  }

  private setBusy(text: string | null): void {
    this.busy = text;
    this.render();
  }

  /* ------------------------------------------------------------ actions */

  /** Run the lens on the current edit. */
  private async look(keepEvolve = false): Promise<void> {
    if (this.busy || timelineEmpty(this.s)) return;
    this.error = null;
    if (!keepEvolve) {
      this.evolveRes = null;
      this.notice = null;
    }
    this.setBusy(WORKING_LOOK);
    try {
      // The report arrives through onLens, dated from the revision at the start of the run.
      const r = await runLens(this.s, { platform: this.chosen });
      this.s.setStatus(`Audience Lens: predicted score ${r.score}`);
    } catch (e) {
      this.fail(e);
    } finally {
      this.setBusy(null);
    }
  }

  /** Apply the fix one finding offers, then look again. */
  private async fix(f: Finding): Promise<void> {
    if (this.busy || !f.fix || this.stale) return;
    this.error = null;
    this.notice = null;
    this.evolveRes = null;
    let ok = false;
    this.setBusy(`Applying “${f.fix.label}”…`);
    try {
      const r = await runCommand(this.s, { op: "lens.fix", finding: f.id });
      if (!r.ok) throw new Error(r.error ?? "The fix could not be applied.");
      const res = (r.result ?? {}) as { failed?: unknown };
      const failed = Array.isArray(res.failed) ? res.failed.filter((x) => x !== undefined && x !== null).map(String) : [];
      if (failed.length) this.notice = { tone: "warn", text: `Part of the fix could not be applied: ${failed.join(" · ")}` };
      else this.notice = { tone: "ok", text: `Applied “${f.fix.label}”. Undo brings it back.` };
      this.s.setStatus(`Applied: ${f.fix.label}`);
      ok = true;
    } catch (e) {
      this.fail(e);
    } finally {
      this.setBusy(null);
    }
    if (ok) await this.look(true);
  }

  /** Confirm, then apply every safe fix and look again (repeating while the predicted score improves). */
  private async evolve(): Promise<void> {
    if (this.busy || timelineEmpty(this.s)) return;
    this.error = null;
    this.notice = null;
    this.evolveRes = null;
    // Count the fixes on a report that matches the edit and the chosen platform.
    const r0 = this.report;
    const fresh = r0 && !this.stale && (this.platform === "auto" || r0.platform === this.platform);
    if (!fresh) {
      await this.look();
      if (this.error || !this.report || this.stale) return;
    }
    const rep = this.report;
    if (!rep) return;
    const plan = evolve(this.s.project, rep);
    if (!plan.applied.length) {
      const open = rep.findings.filter((f) => f.severity !== "good").length;
      this.notice = { tone: "info", text: open ? "Nothing here can be fixed safely on its own: the remaining items need an editing decision." : "Nothing to evolve: no findings left." };
      this.render();
      return;
    }
    const n = plan.applied.length;
    const list = h(
      "ul",
      { class: "in-list" },
      ...plan.applied.map((id) => {
        const f = rep.findings.find((x) => x.id === id);
        return h("li", {}, h("b", {}, f?.fix?.label ?? id), f ? h("span", { class: "muted" }, ` — ${f.title}`) : null);
      }),
    );
    const body = h(
      "div",
      { class: "col in-dialog" },
      h("div", { class: "in-dialog-text" }, `Evolve will apply ${n} safe ${n === 1 ? "fix" : "fixes"} as one undo step.`),
      list,
      h("div", { class: "hint" }, "Afterwards it looks again. A round that does not improve the predicted score is undone; while the score improves it applies the next safe fixes (at most two rounds)."),
    );
    const go = await confirmBox("Evolve the edit", body, "Evolve");
    if (!go) return;
    this.setBusy("Applying the safe fixes, then looking again…");
    try {
      // The resulting report (or, after a roll-back, the one from before) arrives through onLens with its meta.
      const res = await evolveEdit(this.s, { platform: this.chosen });
      this.evolveRes = { before: res.before.score, after: res.after.score, applied: res.applied, remaining: res.after.findings.filter((f) => f.severity !== "good"), rolledBack: res.rolledBack, tried: n, failed: res.failed };
      this.s.setStatus(res.rolledBack && !res.applied.length ? "Evolve: the predicted score did not improve, so the fixes were undone" : `Evolve: predicted score ${res.before.score} → ${res.after.score}`);
    } catch (e) {
      this.fail(e);
    } finally {
      this.setBusy(null);
    }
  }

  private jump(t: number): void {
    this.s.seek(t);
    this.o.reveal?.(t);
  }

  private selectClip(id: string): void {
    const found = findClip(this.s.project, id);
    if (!found) {
      this.notice = { tone: "warn", text: "That clip is no longer on the timeline. Look again for an up-to-date report." };
      this.render();
      return;
    }
    this.s.select(id);
    this.o.reveal?.(found.clip.start);
  }

  /* ------------------------------------------------------------ rendering */

  private render(): void {
    const empty = timelineEmpty(this.s);
    this.shownEmpty = empty;
    this.shownStale = this.stale;
    const busy = !!this.busy;
    this.sel.disabled = busy;
    this.lookBtn.disabled = busy || empty;
    this.evolveBtn.disabled = busy || empty;
    const lookLabel = this.report ? "Look again" : "Look at the edit";
    if (this.lookBtn.lastChild) this.lookBtn.lastChild.textContent = lookLabel;
    this.lookBtn.title = empty ? "Put something on the timeline first" : lookLabel;
    this.lookBtn.classList.toggle("pri", !this.report || this.stale);
    this.evolveBtn.title = empty ? "Put something on the timeline first" : "Apply every safe fix, then look again";

    const status: HTMLElement[] = [];
    if (this.busy) status.push(workingRow(this.busy));
    if (this.error) status.push(noteBox("err", this.error, () => ((this.error = null), this.render())));
    if (this.notice) status.push(noteBox(this.notice.tone, this.notice.text, () => ((this.notice = null), this.render())));
    this.statusEl.replaceChildren(...status);

    this.evolveEl.replaceChildren(...(this.evolveRes ? [this.evolveCard(this.evolveRes)] : []));
    this.renderContent(empty);
  }

  private renderContent(empty: boolean): void {
    const r = this.report;
    if (empty || (r && r.findings.some((f) => f.id === "technical:empty"))) {
      this.contentEl.replaceChildren(
        h("div", { class: "in-empty" }, h("div", { class: "in-empty-ic" }, ic("eye", 22)), h("b", {}, "Nothing to look at yet"), h("span", {}, "Add footage, titles or music to the timeline. The lens will then predict how an audience is likely to meet the opening, the pacing, the text, the sound and the ending.")),
      );
      return;
    }
    if (!r) {
      this.contentEl.replaceChildren(
        h(
          "div",
          { class: "in-empty" },
          h("div", { class: "in-empty-ic" }, ic("eye", 22)),
          h("b", {}, "Ready when you are"),
          h("span", {}, "Look at the edit to get a predicted score, the moments where viewers are likely to drift away, and fixes you can apply in one click. Everything is measured on this PC."),
        ),
      );
      return;
    }
    const stale = this.stale;
    const parts: HTMLElement[] = [];
    if (stale) parts.push(h("div", { class: "in-stale-bar" }, ic("loop", 14), h("span", {}, "The edit changed — look again"), h("span", { class: "sp" }), b("Look again", null, () => void this.look(), "sm", "Look at the current edit")));
    if (this.platform !== "auto" && r.platform !== this.platform) parts.push(noteBox("info", `This report judged the edit for ${PLATFORMS[r.platform].name}. Look again to judge it for ${PLATFORMS[this.platform].name}.`));
    const sorted = [...r.findings].sort((a, z) => SEV_RANK[a.severity] - SEV_RANK[z.severity]);
    const open = sorted.filter((f) => f.severity !== "good");
    const good = sorted.filter((f) => f.severity === "good");

    const report = h(
      "div",
      { class: `in-report ${stale ? "in-stale" : ""}` },
      this.summary(r),
      h("div", { class: "in-section-title" }, "Areas"),
      this.areas(r),
      h("div", { class: "in-section-title" }, "Predicted attention"),
      this.chartWrap,
      h("div", { class: "in-caption" }, "Predicted — a model of how attention usually falls, not a measurement"),
      this.chartLegend(r),
      h("div", { class: "in-section-title" }, open.length ? `What to look at (${open.length})` : "What to look at"),
      open.length ? h("div", { class: "in-findings" }, ...open.map((f) => this.findingRow(f, stale))) : h("div", { class: "in-quiet" }, "Nothing stands out. The lens has no suggestions for this edit."),
      good.length ? this.goodSection(good, stale) : null,
    );
    parts.push(report);
    this.contentEl.replaceChildren(...parts);
    this.drawSoon();
  }

  private summary(r: LensReport): HTMLElement {
    const score = Math.max(0, Math.min(100, Math.round(r.score)));
    const band = score >= 85 ? "good" : score >= 60 ? "mid" : "low";
    const R = 33;
    const C = 2 * Math.PI * R;
    const svg = svgEl("svg", { viewBox: "0 0 84 84", width: 84, height: 84, class: `in-dial in-dial-${band}`, "aria-hidden": "true" });
    svg.append(
      svgEl("circle", { cx: 42, cy: 42, r: R, class: "in-dial-track" }),
      svgEl("circle", { cx: 42, cy: 42, r: R, class: "in-dial-arc", "stroke-dasharray": `${C.toFixed(2)} ${C.toFixed(2)}`, "stroke-dashoffset": (C * (1 - score / 100)).toFixed(2), transform: "rotate(-90 42 42)" }),
    );
    const dial = h("div", { class: "in-dial-box", title: "Predicted score, 0–100. A guide to what may help, not a forecast of views." }, svg, h("div", { class: "in-dial-num" }, h("b", {}, String(score)), h("span", {}, "predicted")));
    const info = PLATFORMS[r.platform];
    const end = typeof r.facts.predictedEndShare === "number" ? r.facts.predictedEndShare : r.attention[r.attention.length - 1]?.v;
    const dur = typeof r.facts.duration === "number" ? r.facts.duration : this.s.duration;
    return h(
      "div",
      { class: "in-summary" },
      dial,
      h(
        "div",
        { class: "in-summary-text" },
        h("div", { class: "in-verdict" }, r.verdict),
        h("div", { class: "in-meta" }, this.safeGlyph(r.platform), h("span", { title: info.safeNote }, `Judged for ${info.name}`), h("span", { class: "in-dot-sep" }, "·"), h("span", { class: "mono" }, shortTime(dur))),
        end !== undefined ? h("div", { class: "in-meta", title: "From the attention model; real audiences vary." }, `Predicted still watching at the end: about ${Math.round(end * 100)} %`) : null,
      ),
    );
  }

  /** A tiny frame with the platform's safe area drawn in it. */
  private safeGlyph(platform: Platform): SVGSVGElement {
    const p = this.s.project;
    const W = Math.max(1, p.width);
    const H = Math.max(1, p.height);
    const k = 18 / Math.max(W, H);
    const w = W * k;
    const hh = H * k;
    const a = safeArea(platform, W, H);
    const svg = svgEl("svg", { width: 20, height: 20, viewBox: "0 0 20 20", class: "in-safe", "aria-hidden": "true" });
    const x0 = (20 - w) / 2;
    const y0 = (20 - hh) / 2;
    svg.append(
      svgEl("rect", { x: x0.toFixed(2), y: y0.toFixed(2), width: w.toFixed(2), height: hh.toFixed(2), rx: 1.5, class: "in-safe-frame" }),
      svgEl("rect", { x: (x0 + a.x0 * k).toFixed(2), y: (y0 + a.y0 * k).toFixed(2), width: Math.max(0.5, (a.x1 - a.x0) * k).toFixed(2), height: Math.max(0.5, (a.y1 - a.y0) * k).toFixed(2), rx: 0.8, class: "in-safe-area" }),
    );
    const t = svgEl("title");
    t.textContent = `Safe area for text: ${a.note}`;
    svg.append(t);
    return svg;
  }

  private areas(r: LensReport): HTMLElement {
    return h(
      "div",
      { class: "in-areas" },
      ...r.areas.map((a) => {
        const [label, ...rest] = a.title.split(":");
        const state = rest.join(":").trim();
        const sc = Math.max(0, Math.min(100, Math.round(a.score)));
        const band = sc >= 85 ? "good" : sc >= 60 ? "mid" : "low";
        const bar = h("div", { class: "in-area-bar" }, h("i", { class: `in-band-${band}`, style: `width:${sc}%` }));
        return h("div", { class: "in-area", title: `${a.title}. Counts for ${AREA_WEIGHTS[a.area]} % of the predicted score.` }, h("span", { class: "in-area-label" }, label ?? a.area), bar, h("span", { class: "in-area-score mono" }, String(sc)), state ? h("span", { class: "in-area-state" }, state) : null);
      }),
    );
  }

  private chartLegend(r: LensReport): HTMLElement | null {
    const sevs = new Set(r.findings.filter((f) => f.at !== undefined).map((f) => f.severity));
    if (!sevs.size) return null;
    return h("div", { class: "in-legend" }, ...(["high", "medium", "low", "good"] as Severity[]).filter((x) => sevs.has(x)).map((x) => h("span", {}, h("i", { class: `in-sev-dot in-sev-${x}` }), SEV_LABEL[x])), h("span", { class: "in-legend-hint" }, "Click the curve to move the playhead"));
  }

  private findingRow(f: Finding, stale: boolean): HTMLElement {
    const fps = this.s.project.fps || 30;
    const links: HTMLElement[] = [];
    if (f.at !== undefined && Number.isFinite(f.at)) {
      const at = f.at;
      const t = h("button", { class: "in-tc mono", type: "button", title: "Move the playhead here" }, tc(at, fps));
      t.addEventListener("click", () => this.jump(at));
      links.push(t);
    }
    const first = f.clipIds?.[0];
    if (first) {
      const sl = h("button", { class: "in-link", type: "button", title: "Select this clip on the timeline" }, "Select");
      sl.addEventListener("click", () => this.selectClip(first));
      links.push(sl);
    }
    let fixBtn: HTMLButtonElement | null = null;
    if (f.fix) {
      const fx = f.fix;
      fixBtn = b(fx.label, "wand", () => void this.fix(f), "sm in-fix", stale ? "The edit changed — look again before applying a fix" : `${fx.label} (can be undone)`);
      fixBtn.disabled = !!this.busy || stale;
    }
    return h(
      "div",
      { class: `in-finding in-finding-${f.severity}` },
      h("div", { class: "in-finding-head" }, h("span", { class: `in-sev in-sev-${f.severity}`, title: SEV_HELP[f.severity] }, SEV_LABEL[f.severity]), h("b", { class: "in-finding-title" }, f.title)),
      h("div", { class: "in-finding-detail" }, f.detail),
      links.length || fixBtn ? h("div", { class: "in-finding-foot" }, ...links, h("span", { class: "sp" }), fixBtn) : null,
    );
  }

  private goodSection(good: Finding[], stale: boolean): HTMLElement {
    const d = h("details", { class: "in-good" }) as HTMLDetailsElement;
    d.open = this.goodOpen;
    d.addEventListener("toggle", () => (this.goodOpen = d.open));
    d.append(h("summary", {}, `What is already right (${good.length})`), h("div", { class: "in-findings" }, ...good.map((f) => this.findingRow(f, stale))));
    return d;
  }

  private evolveCard(r: EvolveSummary): HTMLElement {
    const delta = r.after - r.before;
    const fps = this.s.project.fps || 30;
    return h(
      "div",
      { class: "in-evolve" },
      h(
        "div",
        { class: "in-evolve-head" },
        ic("wand", 14),
        h("b", {}, "Evolve"),
        h("span", { class: "in-evolve-score mono" }, `Score ${r.before} → ${r.after}`),
        h("span", { class: `in-delta ${delta > 0 ? "in-up" : delta < 0 ? "in-down" : ""}` }, delta > 0 ? `+${delta}` : String(delta)),
        h("span", { class: "sp" }),
        b(null, "close", () => ((this.evolveRes = null), this.render()), "sm ghost", "Dismiss"),
      ),
      r.rolledBack && !r.applied.length
        ? noteBox("warn", `Evolve tried ${r.tried} ${r.tried === 1 ? "fix" : "fixes"} but the predicted score did not improve, so they were undone.`)
        : h("div", { class: "in-evolve-sub" }, r.applied.length ? `Changed (${r.applied.length}) — all of it is one undo step:` : "No fix could be applied safely."),
      r.applied.length ? h("ul", { class: "in-list" }, ...r.applied.map((a) => h("li", {}, h("b", {}, a.fix), h("span", { class: "muted" }, ` — ${a.title}`)))) : null,
      r.rolledBack && r.applied.length ? h("div", { class: "in-evolve-sub" }, "A further round of fixes did not improve the predicted score, so it was undone.") : null,
      r.failed > 0 ? h("div", { class: "in-evolve-sub" }, `${r.failed} ${r.failed === 1 ? "change" : "changes"} could not be applied and ${r.failed === 1 ? "was" : "were"} left out.`) : null,
      r.remaining.length
        ? h(
            "div",
            {},
            h("div", { class: "in-evolve-sub" }, `Still needs a decision (${r.remaining.length}):`),
            h(
              "ul",
              { class: "in-list" },
              ...r.remaining.map((f) => {
                const li = h("li", {}, h("i", { class: `in-sev-dot in-sev-${f.severity}`, title: SEV_HELP[f.severity] }), f.title);
                if (f.at !== undefined && Number.isFinite(f.at)) {
                  const at = f.at;
                  const t = h("button", { class: "in-tc mono", type: "button", title: "Move the playhead here" }, tc(at, fps));
                  t.addEventListener("click", () => this.jump(at));
                  li.append(" ", t);
                }
                return li;
              }),
            ),
          )
        : h("div", { class: "in-evolve-sub" }, "Nothing else is flagged."),
      h("div", { class: "in-caption" }, "Scores are predictions to guide the edit, not a forecast of views."),
    );
  }

  /* ------------------------------------------------------------ attention chart */

  private drawSoon(): void {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => {
      this.raf = 0;
      this.drawChart();
    });
  }

  private chartGeom(): { L: number; R: number; T: number; B: number; W: number; H: number; end: number } | null {
    const r = this.report;
    const W = this.canvas.clientWidth;
    const H = this.canvas.clientHeight;
    if (!r || W < 20 || H < 20) return null;
    const last = r.attention[r.attention.length - 1];
    const end = Math.max(1e-3, typeof r.facts.duration === "number" && r.facts.duration > 0 ? r.facts.duration : last?.t ?? 0);
    return { L: 32, R: 8, T: 8, B: 18, W, H, end };
  }

  /** Predicted share still watching at time t (linear between samples). */
  private valueAt(t: number): number {
    const a = this.report?.attention ?? [];
    if (!a.length) return 1;
    if (t <= a[0]!.t) return a[0]!.v;
    let lo = 0;
    let hi = a.length - 1;
    if (t >= a[hi]!.t) return a[hi]!.v;
    while (hi - lo > 1) {
      const m = (lo + hi) >> 1;
      if (a[m]!.t <= t) lo = m;
      else hi = m;
    }
    const p = a[lo]!;
    const q = a[hi]!;
    const k = q.t > p.t ? (t - p.t) / (q.t - p.t) : 0;
    return p.v + (q.v - p.v) * k;
  }

  private drawChart(): void {
    const g0 = this.chartGeom();
    const c = this.canvas;
    const r = this.report;
    if (!g0 || !r) return;
    const { L, R, T, B, W, H, end } = g0;
    const dpr = window.devicePixelRatio || 1;
    const pw = Math.round(W * dpr);
    const ph = Math.round(H * dpr);
    if (c.width !== pw || c.height !== ph) {
      c.width = pw;
      c.height = ph;
    }
    const g = c.getContext("2d");
    if (!g) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, W, H);
    const css = getComputedStyle(this.el);
    const col = (v: string, d: string) => css.getPropertyValue(v).trim() || d;
    const accent = col("--accent", "#3ee6c4");
    const ink = col("--ink", "#e8edf4");
    const faint = col("--faint", "#5d6678");
    const mono = col("--mono", "ui-monospace, monospace");
    const iw = W - L - R;
    const ih = H - T - B;
    const X = (t: number) => L + (Math.max(0, Math.min(end, t)) / end) * iw;
    const Y = (v: number) => T + (1 - Math.max(0, Math.min(1, v))) * ih;

    // Grid: 0 %, 50 %, 100 % of viewers.
    g.font = `10px ${mono}`;
    g.textBaseline = "middle";
    g.lineWidth = 1;
    for (const v of [0, 0.25, 0.5, 0.75, 1]) {
      const y = Math.round(Y(v)) + 0.5;
      g.strokeStyle = v === 0 || v === 1 || v === 0.5 ? "rgba(255,255,255,0.08)" : "rgba(255,255,255,0.04)";
      g.beginPath();
      g.moveTo(L, y);
      g.lineTo(W - R, y);
      g.stroke();
      if (v === 0 || v === 0.5 || v === 1) {
        g.fillStyle = faint;
        g.textAlign = "right";
        g.fillText(`${Math.round(v * 100)}%`, L - 5, y);
      }
    }
    // Time labels.
    g.textBaseline = "alphabetic";
    g.fillStyle = faint;
    g.textAlign = "left";
    g.fillText("0", L, H - 4);
    g.textAlign = "right";
    g.fillText(shortTime(end), W - R, H - 4);
    if (iw > 200) {
      g.textAlign = "center";
      g.fillText(shortTime(end / 2), L + iw / 2, H - 4);
    }

    // The curve, filled under.
    const pts = r.attention.filter((p) => Number.isFinite(p.t) && Number.isFinite(p.v));
    if (pts.length) {
      g.beginPath();
      g.moveTo(X(pts[0]!.t), Y(pts[0]!.v));
      for (const p of pts) g.lineTo(X(p.t), Y(p.v));
      const lastX = X(pts[pts.length - 1]!.t);
      const fill = new Path2D();
      fill.moveTo(X(pts[0]!.t), Y(0));
      for (const p of pts) fill.lineTo(X(p.t), Y(p.v));
      fill.lineTo(lastX, Y(0));
      fill.closePath();
      const grad = g.createLinearGradient(0, T, 0, T + ih);
      grad.addColorStop(0, withAlpha(accent, 0.28));
      grad.addColorStop(1, withAlpha(accent, 0.02));
      g.fillStyle = grad;
      g.fill(fill);
      g.strokeStyle = accent;
      g.lineWidth = 1.6;
      g.lineJoin = "round";
      g.stroke();
    }

    // Finding markers.
    this.marks = [];
    const withAt = [...r.findings].filter((f) => f.at !== undefined && Number.isFinite(f.at)).sort((a, z) => SEV_RANK[z.severity] - SEV_RANK[a.severity]);
    for (const f of withAt) {
      const t = f.at!;
      const x = X(t);
      const y = Y(this.valueAt(t));
      const sc = col(SEV_VAR[f.severity], accent);
      g.strokeStyle = withAlpha(sc, 0.45);
      g.lineWidth = 1;
      g.setLineDash([2, 2]);
      g.beginPath();
      g.moveTo(Math.round(x) + 0.5, y);
      g.lineTo(Math.round(x) + 0.5, T + ih);
      g.stroke();
      g.setLineDash([]);
      g.fillStyle = sc;
      g.strokeStyle = "rgba(7,9,13,0.9)";
      g.lineWidth = 1.5;
      g.beginPath();
      g.arc(x, y, f.severity === "good" ? 2.5 : 3.5, 0, Math.PI * 2);
      g.fill();
      g.stroke();
      this.marks.push({ x, y, f });
    }

    // Playhead.
    const t = this.s.time;
    if (t >= 0 && t <= end + 1e-6) {
      const x = Math.round(X(t)) + 0.5;
      g.strokeStyle = withAlpha(ink, 0.75);
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(x, T - 2);
      g.lineTo(x, T + ih);
      g.stroke();
      g.fillStyle = ink;
      g.beginPath();
      g.moveTo(x - 3.5, T - 4);
      g.lineTo(x + 3.5, T - 4);
      g.lineTo(x, T + 1);
      g.closePath();
      g.fill();
    }

    // Hover guide.
    if (this.hoverX !== null && this.hoverX >= L && this.hoverX <= W - R) {
      const hx = Math.round(this.hoverX) + 0.5;
      g.strokeStyle = "rgba(255,255,255,0.22)";
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(hx, T);
      g.lineTo(hx, T + ih);
      g.stroke();
    }
  }

  private bindChart(): void {
    const c = this.canvas;
    const timeAt = (clientX: number): { t: number; x: number } | null => {
      const g = this.chartGeom();
      if (!g) return null;
      const rect = c.getBoundingClientRect();
      const x = clientX - rect.left;
      const k = (x - g.L) / (g.W - g.L - g.R);
      return { t: Math.max(0, Math.min(1, k)) * g.end, x: Math.max(g.L, Math.min(g.W - g.R, x)) };
    };
    const nearMark = (clientX: number, clientY: number): ChartMark | null => {
      const rect = c.getBoundingClientRect();
      const x = clientX - rect.left;
      const y = clientY - rect.top;
      let best: ChartMark | null = null;
      let bd = 7;
      for (const m of this.marks) {
        const d = Math.abs(m.x - x) + Math.abs(m.y - y) * 0.35;
        if (d < bd) {
          bd = d;
          best = m;
        }
      }
      return best;
    };
    c.addEventListener("mousemove", (e) => {
      const p = timeAt(e.clientX);
      if (!p || !this.report) return;
      const mk = nearMark(e.clientX, e.clientY);
      const t = mk ? mk.f.at! : p.t;
      this.hoverX = mk ? mk.x : p.x;
      const share = Math.round(this.valueAt(t) * 100);
      const fps = this.s.project.fps || 30;
      this.tip.replaceChildren(h("span", { class: "mono" }, tc(t, fps)), h("span", {}, ` · about ${share} % predicted still watching`));
      if (mk) this.tip.append(h("div", { class: `in-tip-f in-tip-${mk.f.severity}` }, mk.f.title));
      this.tip.classList.add("in-on");
      const w = this.chartWrap.clientWidth;
      const tw = this.tip.offsetWidth;
      this.tip.style.left = `${Math.max(0, Math.min(w - tw, this.hoverX - tw / 2))}px`;
      this.drawSoon();
    });
    c.addEventListener("mouseleave", () => {
      this.hoverX = null;
      this.tip.classList.remove("in-on");
      this.drawSoon();
    });
    c.addEventListener("click", (e) => {
      const mk = nearMark(e.clientX, e.clientY);
      const p = timeAt(e.clientX);
      const t = mk ? mk.f.at! : p?.t;
      if (t !== undefined) this.jump(t);
    });
    c.addEventListener("keydown", (e) => {
      // Arrow keys step the playhead through the finding markers.
      if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
      const times = [...new Set(this.marks.map((m) => m.f.at!))].sort((a, z) => a - z);
      if (!times.length) return;
      e.preventDefault();
      e.stopPropagation();
      const now = this.s.time;
      const next = e.key === "ArrowRight" ? times.find((x) => x > now + 1e-3) : [...times].reverse().find((x) => x < now - 1e-3);
      if (next !== undefined) this.jump(next);
    });
  }
}

/* ================================================================== Style DNA */

/** A result shown on a library card after an action (compare, apply, extract, learn). */
interface CardNote {
  tone: "ok" | "warn" | "err" | "info";
  head: string;
  lines: string[];
  linesTitle?: string;
  distance?: { after: number; before?: number };
}

/** Plain reading of a DNA distance (as documented in src/smart/dna.ts, dnaDistance). */
function distanceReading(d: number): string {
  return d < 0.1 ? "same style" : d < 0.25 ? "close" : d <= 0.5 ? "related" : "different";
}

const SOURCE_LABEL: Record<Dna["source"], string> = { video: "Video", project: "Project", merged: "Merged" };
const SOURCE_HELP: Record<Dna["source"], string> = { video: "Measured from a finished video", project: "Measured from one of your projects", merged: "An average of several videos or projects" };

/** Seconds with one decimal under 10 s. */
function secs(x: number): string {
  return x >= 10 ? `${Math.round(x)} s` : `${(Math.round(x * 10) / 10).toFixed(1)} s`;
}

/** Same name, ignoring case and outer spaces (how the library matches names). */
function sameName(a: string, z: string): boolean {
  return a.trim().toLowerCase() === z.trim().toLowerCase();
}

/**
 * The Style DNA panel. `el` is a complete panel (glass card with its own header); place it in a dock cell.
 */
export class DnaPanel {
  readonly el: HTMLElement;
  private readonly body: HTMLElement;
  private readonly extractBtn: HTMLButtonElement;
  private readonly learnBtn: HTMLButtonElement;
  private readonly saveBtn: HTMLButtonElement;
  private readonly pasteBtn: HTMLButtonElement;
  private readonly statusEl: HTMLElement;
  private readonly listEl: HTMLElement;

  private lib: Dna[] = [];
  private loaded = false;
  private loadError: string | null = null;
  private busy: string | null = null;
  private error: string | null = null;
  private readonly notes = new Map<string, CardNote>();
  private seq = 0;
  private shownEmpty = false;
  private readonly offs: (() => void)[] = [];

  constructor(private readonly s: Studio) {
    const p = panel(h("span", { class: "row", style: "gap:6px" }, ic("spark", 14), "Style DNA"), "in-panel in-dna");
    this.el = p.el;
    this.body = p.body;
    this.body.classList.add("in-body");

    this.extractBtn = b("Extract from a video…", "film", (e) => this.extractMenu(e), "pri", "Measure the cut rhythm, pacing and colour of a finished video in the media list");
    this.learnBtn = b("Learn from this project", "spark", () => void this.learn(), "", `Folds this timeline into “${SIGNATURE}”, the running average of your own projects. Do it once per finished project.`);
    this.saveBtn = b("Save this project's DNA as…", "download", () => void this.saveAs(), "", "Measure this timeline and keep it in the library under a name");
    this.pasteBtn = b("Paste DNA…", "copy", () => this.pasteDialog(), "", "Add a style DNA someone shared with you (JSON)");
    this.statusEl = h("div", { class: "in-status" });
    this.listEl = h("div", { class: "in-dna-list" });

    this.body.append(
      h("p", { class: "in-lede" }, "Measure the feel of a finished video — its cut rhythm, pacing and colour — and transplant it onto your edit. Your own projects build your signature. Everything stays on this PC."),
      h("div", { class: "in-dna-actions" }, this.extractBtn, this.learnBtn, this.saveBtn, this.pasteBtn),
      this.statusEl,
      h("div", { class: "in-section-title" }, "Library"),
      this.listEl,
    );

    this.offs.push(
      onDnaLibrary(() => void this.refresh()),
      s.on("change", () => {
        if (timelineEmpty(this.s) !== this.shownEmpty) this.render();
      }),
      s.on("project", () => {
        this.notes.clear();
        this.render();
      }),
    );
    this.render();
    void this.refresh();
  }

  /** Stop listening (call only if the panel is thrown away for good). */
  dispose(): void {
    for (const f of this.offs.splice(0)) f();
  }

  /* ------------------------------------------------------------ data */

  private async refresh(): Promise<void> {
    const my = ++this.seq;
    try {
      const lib = await dnaLibrary();
      if (my !== this.seq) return;
      this.lib = lib;
      this.loadError = null;
    } catch (e) {
      if (my !== this.seq) return;
      // Keep the list already on screen; only say that it could not be refreshed.
      this.loadError = errText(e);
      this.s.setStatus(`Style DNA: the library could not be read: ${this.loadError}`);
    }
    this.loaded = true;
    this.render();
  }

  private find(name: string): Dna | undefined {
    return this.lib.find((d) => sameName(d.name, name));
  }

  private fail(e: unknown): void {
    const msg = errText(e);
    this.error = msg;
    this.s.setStatus(`Style DNA: ${msg}`);
  }

  /** Run a long operation with a working state; buttons are always released afterwards. */
  private async work<T>(label: string, fn: () => Promise<T>): Promise<{ ok: true; value: T } | { ok: false }> {
    if (this.busy) return { ok: false };
    this.busy = label;
    this.error = null;
    this.render();
    try {
      return { ok: true, value: await fn() };
    } catch (e) {
      this.fail(e);
      return { ok: false };
    } finally {
      this.busy = null;
      this.render();
    }
  }

  private note(name: string, n: CardNote): void {
    this.notes.set(name.trim().toLowerCase(), n);
  }

  /** Ask for a library name; refuses the signature's name and confirms replacing an existing one. */
  private async askName(title: string, def: string): Promise<string | null> {
    const raw = await ask(title, "Name in the library", def);
    if (raw === null) return null;
    const name = (raw.trim() || def.trim()).slice(0, 60);
    if (!name) return null;
    if (sameName(name, SIGNATURE)) {
      this.error = `“${SIGNATURE}” is kept for your signature. Use “Learn from this project” to add to it, or pick another name.`;
      this.render();
      return null;
    }
    if (this.find(name) && !(await confirmBox("Replace style DNA", `A style DNA called “${name}” is already saved. Replace it?`, "Replace"))) return null;
    return name;
  }

  /* ------------------------------------------------------------ actions */

  private extractMenu(e: MouseEvent): void {
    if (this.busy) return;
    const vids = this.s.project.assets.filter((a) => a.kind === "video");
    const r = (e.currentTarget instanceof HTMLElement ? e.currentTarget : this.extractBtn).getBoundingClientRect();
    if (!vids.length) {
      menu([{ label: "No videos in the media list", disabled: true }, { label: "Import a finished video you like first", disabled: true }], r.left, r.bottom + 4);
      return;
    }
    menu(
      vids.map((a) => ({ label: a.name, key: a.duration > 0 ? shortTime(a.duration) : undefined, run: () => void this.extract(a.id, a.name) })),
      r.left,
      r.bottom + 4,
    );
  }

  private async extract(assetId: string, fileName: string): Promise<void> {
    const name = await this.askName("Extract style DNA", fileName.replace(/\.[^.]+$/, "") || "Reference");
    if (!name) return;
    const res = await this.work(`Measuring the cuts, the music and the colour of ${fileName}…`, () => extractDna(this.s, assetId, name));
    if (!res.ok) return;
    this.note(res.value.name, { tone: "ok", head: `Measured from ${fileName}`, lines: [] });
    this.s.setStatus(`Style DNA “${res.value.name}” saved`);
    await this.refresh();
  }

  private async learn(): Promise<void> {
    if (timelineEmpty(this.s)) return;
    const res = await this.work("Measuring this timeline and folding it into your signature…", () => projectDna(this.s, { signature: true }));
    if (!res.ok) return;
    const n = res.value.samples;
    this.note(SIGNATURE, { tone: "ok", head: `Learned from “${this.s.project.name}”`, lines: [`Your signature is now built from ${n} ${n === 1 ? "edit" : "edits"}.`] });
    this.s.setStatus(`${SIGNATURE} updated`);
    await this.refresh();
  }

  private async saveAs(): Promise<void> {
    if (timelineEmpty(this.s)) return;
    const name = await this.askName("Save this project's DNA", this.s.project.name || "My project");
    if (!name) return;
    const res = await this.work("Measuring this timeline…", () => projectDna(this.s, { name }));
    if (!res.ok) return;
    this.note(res.value.name, { tone: "ok", head: `Measured from “${this.s.project.name}”`, lines: [] });
    this.s.setStatus(`Style DNA “${res.value.name}” saved`);
    await this.refresh();
  }

  private pasteDialog(): void {
    if (this.busy) return;
    const ta = h("textarea", { class: "inp in-json", rows: "10", spellcheck: "false", placeholder: '{ "v": 1, "name": "…", "source": "video", … }' }) as HTMLTextAreaElement;
    const err = h("div", { class: "in-dialog-err", role: "alert" });
    let confirmReplace = "";
    let close = () => {};
    const add = b("Add to library", null, () => void submit(), "pri");
    const submit = async () => {
      err.textContent = "";
      let parsed: unknown;
      try {
        parsed = JSON.parse(ta.value);
      } catch (e) {
        err.textContent = `That is not valid JSON: ${errText(e)}`;
        return;
      }
      const nm = parsed && typeof parsed === "object" && !Array.isArray(parsed) && typeof (parsed as { name?: unknown }).name === "string" ? (parsed as { name: string }).name : "";
      if (nm && this.find(nm) && !sameName(confirmReplace, nm)) {
        confirmReplace = nm;
        err.textContent = `A style DNA called “${nm}” is already saved. Press “Add to library” again to replace it.`;
        return;
      }
      add.disabled = true;
      try {
        const r = await runCommand(this.s, { op: "dna.add", dna: parsed });
        if (!r.ok) {
          err.textContent = r.error ?? "The style DNA could not be added.";
          return;
        }
        close();
        const added = (r.result ?? {}) as { name?: unknown };
        const name = typeof added.name === "string" ? added.name : nm;
        if (name) this.note(name, { tone: "ok", head: "Added from pasted JSON", lines: [] });
        this.s.setStatus(name ? `Style DNA “${name}” added` : "Style DNA added");
        await this.refresh();
      } finally {
        add.disabled = false;
      }
    };
    ta.addEventListener("input", () => {
      err.textContent = "";
      confirmReplace = "";
    });
    const body = h("div", { class: "col in-dialog" }, h("div", { class: "in-dialog-text" }, "Paste a style DNA record (as copied with “Copy JSON”). It is checked before it is added."), ta, err);
    close = openDialog("Paste style DNA", body, [b("Cancel", null, () => close()), add], () => {});
    setTimeout(() => ta.focus());
  }

  private applyDialog(d: Dna): void {
    if (this.busy) return;
    const want = { rhythm: true, type: !!d.type, colour: !!d.colour };
    let strength = 80;
    const go = b("Apply", null, () => {
      const parts = (["rhythm", "type", "colour"] as const).filter((k) => want[k]);
      if (!parts.length) return;
      close();
      void this.transplant(d, [...parts], strength / 100);
    }, "pri");
    const strengthRow = numRow({ label: "Strength", min: 0, max: 100, step: 1, value: strength, fmt: (v) => `${Math.round(v)} %`, onInput: (v) => (strength = Math.max(0, Math.min(100, v))) });
    strengthRow.classList.add("in-strength");
    const sync = () => {
      go.disabled = !want.rhythm && !want.type && !want.colour;
      strengthRow.classList.toggle("in-off", !want.colour);
      for (const inp of strengthRow.querySelectorAll("input")) (inp as HTMLInputElement).disabled = !want.colour;
    };
    const check = (key: keyof typeof want, label: string, help: string, available: boolean, missing: string) => {
      const box = h("input", { type: "checkbox" }) as HTMLInputElement;
      box.checked = want[key];
      box.disabled = !available;
      box.addEventListener("change", () => {
        want[key] = box.checked;
        sync();
      });
      return h("label", { class: `in-check ${available ? "" : "in-off"}` }, box, h("span", {}, h("b", {}, label), h("span", { class: "muted" }, available ? help : missing)));
    };
    const body = h(
      "div",
      { class: "col in-dialog" },
      h("div", { class: "in-dialog-text" }, `Transplant “${d.name}” onto the current edit.`),
      check("rhythm", "Rhythm", "Re-time the cuts to its pacing and beat pattern (same shots, same total length).", true, ""),
      check("type", "Titles", "Restyle the titles: font, size, colour, position and entrance.", !!d.type, "This DNA has no titles to copy."),
      check("colour", "Colour", "Add a “Style DNA” node (editable curves) to every shot that brings its colour closer to this style.", !!d.colour, "This DNA carries no colour."),
      strengthRow,
      h("div", { class: "hint" }, "The whole transplant is one undo step. Anything that cannot be changed safely is left alone, and the notes will say why."),
    );
    let close = () => {};
    close = openDialog("Apply style DNA", body, [b("Cancel", null, () => close()), go], () => {});
    sync();
    setTimeout(() => go.focus());
  }

  private async transplant(d: Dna, parts: string[], colourStrength: number): Promise<void> {
    if (timelineEmpty(this.s)) return;
    const res = await this.work(`Transplanting “${d.name}”…`, () => transplantDna(this.s, d, { parts, colourStrength }));
    if (!res.ok) return;
    const x = res.value;
    const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
    const colour = x.colour && typeof x.colour === "object" ? (x.colour as { shots?: unknown; before?: unknown; after?: unknown; failed?: unknown }) : undefined;
    let notes = Array.isArray(x.notes) ? x.notes.map(String) : [];
    // Older engines add a hint to use the colour-match tool even when the colour was just brought closer here.
    if (colour) notes = notes.filter((n) => !/match it with the colour-match tool/i.test(n));
    const failed = Array.isArray(x.failed) ? x.failed.filter((v) => v !== undefined && v !== null).map(String) : [];
    const lines = [...notes];
    let colourMissed = 0;
    if (colour) {
      const shots = num(colour.shots) ?? 0;
      const cb = num(colour.before);
      const ca = num(colour.after);
      const colourFailed = Array.isArray(colour.failed) ? colour.failed.filter((v) => v !== undefined && v !== null).map(String) : [];
      lines.push(shots ? `Colour: ${shots} ${shots === 1 ? "shot was" : "shots were"} brought closer to this style${cb !== undefined && ca !== undefined ? ` (colour difference ${cb.toFixed(2)} → ${ca.toFixed(2)})` : ""}.` : colourFailed.length ? "Colour: no shot could be brought closer." : "Colour: there were no full-frame shots to grade.");
      colourMissed = colourFailed.length;
      if (colourFailed.length) lines.push(`Colour left as it was on ${colourFailed.length} ${colourFailed.length === 1 ? "shot" : "shots"} that could not be measured: ${colourFailed.join(", ")}.`);
    }
    for (const f of failed) lines.push(`Could not apply: ${f}`);
    const before = num(x.distanceBefore);
    const after = num(x.distanceAfter);
    const cmds = num(x.commands) ?? 0;
    this.note(d.name, {
      tone: failed.length || colourMissed ? "warn" : "ok",
      head: after !== undefined ? `Applied — distance ${before !== undefined ? `${before.toFixed(2)} → ` : ""}${after.toFixed(2)} (${distanceReading(after)})` : "Applied",
      lines: lines.length ? lines : [cmds ? `${cmds} edits made.` : "Nothing needed to change."],
      linesTitle: "Notes",
      distance: after !== undefined ? { after, before } : undefined,
    });
    this.s.setStatus(`Style DNA “${d.name}” applied${after !== undefined ? ` — distance ${after.toFixed(2)}` : ""}`);
    this.render();
  }

  private async compare(d: Dna): Promise<void> {
    if (timelineEmpty(this.s)) return;
    const res = await this.work(`Comparing this timeline with “${d.name}”…`, async () => {
      const r = await runCommand(this.s, { op: "dna.compare", name: d.name });
      if (!r.ok) throw new Error(r.error ?? "Could not compare.");
      return (r.result ?? {}) as { distance?: unknown; yours?: unknown };
    });
    if (!res.ok) return;
    const dist = typeof res.value.distance === "number" && Number.isFinite(res.value.distance) ? res.value.distance : NaN;
    const yours = Array.isArray(res.value.yours) ? res.value.yours.map(String) : [];
    if (!Number.isFinite(dist)) {
      this.note(d.name, { tone: "err", head: "The comparison gave no distance.", lines: [] });
    } else {
      this.note(d.name, { tone: "info", head: `Distance ${dist.toFixed(2)} — ${distanceReading(dist)}`, lines: yours, linesTitle: "Your edit", distance: { after: dist } });
    }
    this.render();
  }

  private async copyJson(d: Dna): Promise<void> {
    const r = await runCommand(this.s, { op: "dna.get", name: d.name });
    if (!r.ok) {
      this.fail(new Error(r.error ?? "Could not read the style DNA."));
      this.render();
      return;
    }
    const json = JSON.stringify(r.result, null, 2);
    try {
      await navigator.clipboard.writeText(json);
      this.s.setStatus(`Copied “${d.name}” as JSON`);
      this.note(d.name, { tone: "ok", head: "Copied as JSON to the clipboard", lines: [] });
      this.render();
    } catch {
      // The clipboard can refuse (for example when the window is not focused): show the JSON to copy by hand.
      const ta = h("textarea", { class: "inp in-json", rows: "12", spellcheck: "false", readonly: true }) as HTMLTextAreaElement;
      ta.value = json;
      let close = () => {};
      close = openDialog(`Style DNA “${d.name}”`, h("div", { class: "col in-dialog" }, h("div", { class: "in-dialog-text" }, "The clipboard is not available right now. Select the text and copy it."), ta), [b("Close", null, () => close(), "pri")], () => {});
      setTimeout(() => ta.select());
    }
  }

  private async remove(d: Dna): Promise<void> {
    const sig = d.name === SIGNATURE;
    const ok = await confirmBox(
      sig ? "Reset your signature" : "Delete style DNA",
      sig ? `Delete “${SIGNATURE}”? It is built from ${d.samples} ${d.samples === 1 ? "edit" : "edits"}; learning starts again from the next project.` : `Delete “${d.name}” from the library on this PC?`,
      "Delete",
      true,
    );
    if (!ok) return;
    const res = await this.work(`Deleting “${d.name}”…`, async () => {
      const r = await runCommand(this.s, { op: "dna.remove", name: d.name });
      if (!r.ok) throw new Error(r.error ?? "Could not delete.");
    });
    if (!res.ok) return;
    this.notes.delete(d.name.trim().toLowerCase());
    this.s.setStatus(`Deleted “${d.name}”`);
    await this.refresh();
  }

  /* ------------------------------------------------------------ rendering */

  private render(): void {
    const empty = timelineEmpty(this.s);
    this.shownEmpty = empty;
    const busy = !!this.busy;
    this.extractBtn.disabled = busy;
    this.pasteBtn.disabled = busy;
    this.learnBtn.disabled = busy || empty;
    this.saveBtn.disabled = busy || empty;
    const status: HTMLElement[] = [];
    if (this.busy) status.push(workingRow(this.busy));
    if (this.error) status.push(noteBox("err", this.error, () => ((this.error = null), this.render())));
    this.statusEl.replaceChildren(...status);
    this.renderList(empty);
  }

  private renderList(empty: boolean): void {
    if (!this.loaded) {
      this.listEl.replaceChildren(h("div", { class: "in-quiet" }, "Opening the library…"));
      return;
    }
    const loadNote = this.loadError ? noteBox("err", `The library could not be read: ${this.loadError}${this.lib.length ? " (showing the list from before)" : ""}`, () => ((this.loadError = null), this.render())) : null;
    if (!this.lib.length && loadNote) {
      this.listEl.replaceChildren(loadNote);
      return;
    }
    if (!this.lib.length) {
      this.listEl.replaceChildren(
        h(
          "div",
          { class: "in-empty" },
          h("div", { class: "in-empty-ic" }, ic("spark", 22)),
          h("b", {}, "No style DNA yet"),
          h("span", {}, "Import a finished video you like and extract its DNA, or learn from your own finished projects to build your signature."),
        ),
      );
      return;
    }
    const sig = this.lib.filter((d) => d.name === SIGNATURE);
    const rest = this.lib.filter((d) => d.name !== SIGNATURE).reverse();
    this.listEl.replaceChildren(...(loadNote ? [loadNote] : []), ...[...sig, ...rest].map((d) => this.card(d, empty)));
  }

  private card(d: Dna, empty: boolean): HTMLElement {
    const sig = d.name === SIGNATURE;
    const r = d.rhythm;
    const busy = !!this.busy;
    const facts: (HTMLElement | null)[] = [
      h("span", { class: "in-fact", title: "Shots measured" }, h("b", {}, String(r.shotCount)), r.shotCount === 1 ? " shot" : " shots"),
      r.shotCount > 1 ? h("span", { class: "in-fact", title: "Median shot length" }, "median ", h("b", {}, secs(r.shotMedian))) : null,
      r.bpm ? h("span", { class: "in-fact", title: "Tempo of the music" }, h("b", {}, String(Math.round(r.bpm))), " bpm") : null,
      r.onBeat !== undefined ? h("span", { class: "in-fact", title: "Share of cuts within 80 ms of a beat" }, h("b", {}, `${Math.round(r.onBeat * 100)} %`), " on beat") : null,
      h("span", { class: `in-fact ${d.colour ? "" : "in-fact-off"}`, title: d.colour ? "Carries a colour look" : "No colour measured" }, d.colour ? "Colour" : "No colour"),
      h("span", { class: `in-fact ${d.type ? "" : "in-fact-off"}`, title: d.type ? "Carries a title style" : "No titles measured" }, d.type ? "Titles" : "No titles"),
    ];
    const needTl = "Put something on the timeline first";
    const apply = b("Apply…", "wand", () => this.applyDialog(d), "sm pri", empty ? needTl : "Transplant this style onto the current edit");
    apply.disabled = busy || empty;
    const cmp = b("Compare", null, () => void this.compare(d), "sm", empty ? needTl : "How close the current edit is to this style");
    cmp.disabled = busy || empty;
    const copy = b("Copy JSON", "copy", () => void this.copyJson(d), "sm ghost", "Copy this style DNA as JSON, to keep or share");
    copy.disabled = busy;
    const del = b(null, "trash", () => void this.remove(d), "sm ghost in-del", sig ? "Reset your signature" : "Delete from the library");
    del.disabled = busy;
    const n = this.notes.get(d.name.trim().toLowerCase());
    return h(
      "div",
      { class: `in-dna-card ${sig ? "in-sig" : ""}` },
      h(
        "div",
        { class: "in-dna-head" },
        h("b", { class: "in-dna-name", title: d.name }, d.name),
        sig ? h("span", { class: "in-badge in-badge-sig", title: `The running average of your own projects (${d.samples} ${d.samples === 1 ? "edit" : "edits"})` }, "My signature") : null,
        h("span", { class: `in-badge in-badge-${d.source}`, title: SOURCE_HELP[d.source] }, SOURCE_LABEL[d.source]),
        h("span", { class: "sp" }),
        this.sparkline(r.arc),
      ),
      h("ul", { class: "in-dna-feel" }, ...describeDna(d).map((l) => h("li", {}, l))),
      h("div", { class: "in-facts" }, ...facts),
      n ? this.cardNote(d.name, n) : null,
      h("div", { class: "in-dna-foot" }, apply, cmp, h("span", { class: "sp" }), copy, del),
    );
  }

  private cardNote(name: string, n: CardNote): HTMLElement {
    return h(
      "div",
      { class: `in-card-note in-note-${n.tone}` },
      h("div", { class: "in-card-note-head" }, h("b", {}, n.head), h("span", { class: "sp" }), b(null, "close", () => (this.notes.delete(name.trim().toLowerCase()), this.render()), "sm ghost in-note-x", "Dismiss")),
      n.distance ? this.meter(n.distance.after, n.distance.before) : null,
      n.lines.length ? h("div", {}, n.linesTitle ? h("div", { class: "in-card-note-sub" }, n.linesTitle) : null, h("ul", { class: "in-list" }, ...n.lines.map((l) => h("li", {}, l)))) : null,
    );
  }

  /** A 0..1 distance meter with the reading zones (same style, close, related, different). */
  private meter(after: number, before?: number): HTMLElement {
    const pos = (v: number) => `${Math.max(0, Math.min(1, v)) * 100}%`;
    return h(
      "div",
      { class: "in-meter", title: "0 = the same feel, 1 = as different as can be. Under 0.1 same style, 0.1–0.25 close, 0.25–0.5 related, over 0.5 different." },
      h("div", { class: "in-meter-track" }, h("i", { class: "in-z1" }), h("i", { class: "in-z2" }), h("i", { class: "in-z3" }), h("i", { class: "in-z4" }), before !== undefined ? h("span", { class: "in-meter-dot in-was", style: `left:${pos(before)}`, title: `Before: ${before.toFixed(2)}` }) : null, h("span", { class: "in-meter-dot", style: `left:${pos(after)}`, title: `Now: ${after.toFixed(2)}` })),
      h("div", { class: "in-meter-labels" }, h("span", {}, "same"), h("span", {}, "close"), h("span", {}, "related"), h("span", {}, "different")),
    );
  }

  /** The 8-slice pacing arc: up = longer, calmer shots there; down = faster cutting; the dashed line is the video's own median. */
  private sparkline(arc: number[]): SVGSVGElement {
    const W = 84;
    const H = 24;
    const pad = 3;
    const vals = arc.length ? arc : [1];
    const y = (v: number) => {
      const l = Math.max(-2, Math.min(2, Math.log2(Math.max(1e-3, v))));
      return H / 2 - (l / 2) * (H / 2 - pad);
    };
    const x = (i: number) => (vals.length === 1 ? W / 2 : pad + (i / (vals.length - 1)) * (W - 2 * pad));
    const svg = svgEl("svg", { width: W, height: H, viewBox: `0 0 ${W} ${H}`, class: "in-spark" });
    const pts = vals.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
    svg.append(
      svgEl("line", { x1: pad, x2: W - pad, y1: H / 2, y2: H / 2, class: "in-spark-base" }),
      svgEl("polygon", { points: `${x(0).toFixed(1)},${H / 2} ${pts} ${x(vals.length - 1).toFixed(1)},${H / 2}`, class: "in-spark-fill" }),
      svgEl("polyline", { points: pts, class: "in-spark-line" }),
    );
    const t = svgEl("title");
    t.textContent = "Pacing arc across the running time: up = longer, calmer shots; down = faster cutting. The dashed line is the video's own median shot.";
    svg.append(t);
    return svg;
  }
}
