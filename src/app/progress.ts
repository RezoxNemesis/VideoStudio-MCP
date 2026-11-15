/**
 * Progress with numbers, for every kind of work that takes time: a percentage, the counts, the speed and the time left.
 *
 *   Meter      one job made of stages (speak → solve → draw → mix → join). The stages are weighed by what they cost in
 *              seconds, so the whole job has ONE honest percentage; the cost is a guess at first, then what the clock
 *              measures, and what was measured is remembered for the next job on this computer (rates()).
 *   countLine  one line for a single counted piece of work (an export: "Rendering · 13% · 120 / 900 frames · …").
 *   WorkBoard  the one place every kind of work reports to, so the window can show a bar with numbers for all of it
 *              and Claude can read the same numbers.
 */

const nf = (n: number): string => Math.round(n).toLocaleString("en-US");
const pct = (frac: number, done: boolean): number => (done ? 100 : Math.max(0, Math.min(99, Math.round(frac * 100))));

/** "about 4 min left": vague when little is left, rounder the more is left; "" when not known. */
export function fmtLeft(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || !Number.isFinite(sec) || sec < 0) return "";
  if (sec < 8) return "a few seconds left";
  if (sec < 60) return `about ${Math.round(sec / 5) * 5} s left`;
  if (sec < 240) {
    const r = Math.round(sec / 10) * 10, m = Math.floor(r / 60), s = r % 60;
    return s ? `about ${m} min ${s} s left` : `about ${m} min left`;
  }
  if (sec < 3600) return `about ${Math.round(sec / 60)} min left`;
  const mins = Math.round(sec / 60), hh = Math.floor(mins / 60);
  return `about ${hh} h ${String(mins % 60).padStart(2, "0")} min left`;
}

/** "frames" → "frame", "seconds of performance" → "second of performance": the first word is the one counted. */
const singular = (unit: string): string => {
  const [first, ...rest] = unit.split(" ");
  return [first!.endsWith("s") ? first!.slice(0, -1) : first!, ...rest].join(" ");
};
/** A speed in the unit's own words: fast work per second ("6.1 frames/s"), slow work per piece ("20 s per line"). */
export function fmtSpeed(secPerUnit: number, unit: string): string {
  if (!(secPerUnit > 0) || !unit) return "";
  if (secPerUnit <= 1) return `${(1 / secPerUnit).toFixed(1)} ${unit}/s`;
  return secPerUnit < 90 ? `${Math.round(secPerUnit)} s per ${singular(unit)}` : `${(secPerUnit / 60).toFixed(1)} min per ${singular(unit)}`;
}

export interface CountLine { label: string; done: number; total: number; unit?: string; perSec?: number; leftSec?: number | null; detail?: string }
/** One line for one counted piece of work. */
export function countLine(c: CountLine): string {
  const parts = [c.label];
  if (c.total > 0) {
    parts.push(`${pct(c.done / c.total, c.done >= c.total)}%`);
    parts.push(`${nf(c.done)} / ${nf(c.total)}${c.unit ? ` ${c.unit}` : ""}`);
    if (c.perSec && c.perSec > 0 && c.unit) parts.push(`${c.perSec.toFixed(1)} ${c.unit}/s`);
    const left = fmtLeft(c.leftSec);
    if (left && c.done < c.total) parts.push(left);
  }
  if (c.detail) parts.push(c.detail);
  return parts.join(" · ");
}

export interface StagePlan {
  id: string;
  label: string;
  /** How much work: lines, seconds of performance, frames … A stage with none is left out. */
  units: number;
  /** The plural word for a unit ("frames"). */
  unit?: string;
  /** A first guess of the seconds one unit takes (replaced by what was learnt, then by what the clock measures). */
  per?: number;
  /** Which learnt speed to use and to teach (default: the id). */
  rate?: string;
}
export interface ProgressView {
  label: string;
  detail?: string;
  /** The stage that runs, counted over the stages that have work. */
  step: number;
  steps: number;
  /** The whole job, 0…1, or -1 before there is a plan. */
  frac: number;
  percent: number;
  stageFrac: number;
  stagePercent: number;
  done: number;
  total: number;
  unit: string;
  speed: string;
  leftSec: number | null;
  left: string;
  elapsedSec: number;
  /** The stage cannot report, so its percentage follows the clock. */
  estimated: boolean;
  /** Everything in one line. */
  text: string;
}

interface Stage extends StagePlan { done: number; t0: number | null; t1: number | null; at: number; detail?: string }

export class Meter {
  private stages: Stage[] = [];
  private cur: Stage | null = null;
  private floor = 0;
  private words = "";
  private readonly started: number;
  private learnt: Record<string, number> = {};

  constructor(private now: () => number = () => Date.now(), private known: Record<string, number> = {}) {
    this.started = now();
  }
  /** Say what is being done before the amount of work is known. */
  note(label: string): void {
    this.words = label;
  }
  /** Set (or set again, when more is known) the stages of the job. What was done so far is kept. */
  plan(list: StagePlan[]): void {
    const old = new Map(this.stages.map((s) => [s.id, s]));
    this.stages = list.map((p) => {
      const o = old.get(p.id);
      return { ...p, done: o ? Math.min(o.done, p.units) : 0, t0: o?.t0 ?? null, t1: o?.t1 ?? null, at: o?.at ?? this.now(), detail: o?.detail };
    });
    for (const s of this.stages) if (s.t1 !== null) s.done = s.units;
    this.cur = this.cur ? (this.stages.find((s) => s.id === this.cur!.id) ?? null) : null;
  }
  private get(id: string): Stage {
    let s = this.stages.find((x) => x.id === id);
    if (!s) {
      s = { id, label: id, units: 1, done: 0, t0: null, t1: null, at: this.now() };
      this.stages.push(s);
    }
    return s;
  }
  begin(id: string, detail?: string): void {
    const s = this.get(id);
    if (this.cur && this.cur !== s && this.cur.t1 === null) this.end(this.cur.id);
    s.t0 ??= this.now();
    s.t1 = null;
    s.at = this.now();
    s.detail = detail;
    this.cur = s;
    this.words = "";
  }
  /** How many units of the stage are done (parts of a unit are welcome). */
  set(id: string, done: number, detail?: string): void {
    const s = this.get(id);
    if (s.t0 === null) this.begin(id);
    s.done = Math.max(0, Math.min(s.units, done));
    s.at = this.now();
    if (detail !== undefined) s.detail = detail;
  }
  end(id: string): void {
    const s = this.get(id);
    if (s.t1 !== null) return;
    s.t0 ??= this.now();
    s.t1 = this.now();
    s.done = s.units;
    const sec = (s.t1 - s.t0) / 1000, key = s.rate ?? s.id;
    if (s.units > 0 && sec >= 0.5) {
      const measured = sec / s.units, was = this.known[key];
      this.learnt[key] = was && was > 0 ? was * 0.5 + measured * 0.5 : measured;
    }
  }
  /** Seconds per unit learnt for each kind of stage: keep them and give them to the next Meter. */
  rates(): Record<string, number> {
    return { ...this.known, ...this.learnt };
  }
  private guess(s: Stage): number {
    return this.known[s.rate ?? s.id] ?? s.per ?? 1;
  }
  /** Seconds per unit: measured once the clock has something to say, the guess before. */
  private rateOf(s: Stage): { sec: number; measured: boolean } {
    if (s.t0 !== null) {
      const el = ((s.t1 ?? s.at) - s.t0) / 1000;
      if (s.done > 0 && el >= 0.05) return { sec: el / s.done, measured: true };
    }
    return { sec: this.guess(s), measured: false };
  }
  view(): ProgressView {
    const now = this.now(), elapsedSec = (now - this.started) / 1000;
    const live = this.stages.filter((s) => s.units > 0);
    const cur = this.cur && this.cur.units > 0 ? this.cur : null;
    if (!live.length || (!cur && !live.some((s) => s.t1 !== null))) {
      const label = this.words || cur?.label || "Working";
      return { label, step: 0, steps: live.length, frac: -1, percent: 0, stageFrac: -1, stagePercent: 0, done: 0, total: 0, unit: "", speed: "", leftSec: null, left: "", elapsedSec, estimated: false, text: label };
    }
    let cost = 0, spent = 0, left = 0, stageFrac = 0, estimated = false, speed = "";
    for (const s of live) {
      const r = this.rateOf(s);
      let eff = s.done;
      if (s === cur && s.t1 === null) {
        // between two reports the work goes on: move by the clock, but wait just short of the next unit until told
        const whole = Number.isInteger(s.done) && s.done < s.units;
        const creep = whole ? Math.min(0.95, (now - s.at) / 1000 / Math.max(1e-6, r.sec)) : 0;
        eff = Math.min(s.units, s.done + creep);
        estimated = creep / s.units > 0.02;
        stageFrac = eff / s.units;
        if (r.measured && s.units > 1) speed = fmtSpeed(r.sec, s.unit ?? "");
      }
      cost += s.units * r.sec;
      spent += eff * r.sec;
      left += (s.units - eff) * r.sec;
    }
    const allDone = live.every((s) => s.t1 !== null);
    const frac = allDone ? 1 : Math.max(this.floor, cost > 0 ? spent / cost : 0);
    this.floor = Math.min(frac, 0.999);
    const shown = cur ?? live[live.length - 1]!;
    if (!cur || cur.t1 !== null) stageFrac = 1;
    const step = live.indexOf(shown) + 1, leftSec = allDone ? 0 : left;
    const stagePercent = pct(stageFrac, shown.t1 !== null);
    const parts: string[] = [];
    if (live.length > 1) parts.push(`Step ${step} of ${live.length}`);
    parts.push(shown.label, `${estimated ? "~" : ""}${stagePercent}%`);
    if (shown.units > 1) parts.push(`${nf(Math.floor(shown.done))} / ${nf(shown.units)}${shown.unit ? ` ${shown.unit}` : ""}`);
    if (speed) parts.push(speed);
    const leftWords = allDone ? "" : fmtLeft(leftSec);
    if (leftWords) parts.push(leftWords);
    if (shown.detail) parts.push(shown.detail);
    return {
      label: shown.label, detail: shown.detail, step, steps: live.length, frac, percent: pct(frac, allDone), stageFrac, stagePercent,
      done: Math.floor(shown.done), total: shown.units, unit: shown.unit ?? "", speed, leftSec, left: leftWords, elapsedSec, estimated, text: parts.join(" · "),
    };
  }
}

export interface Work { id: string; label: string; frac: number; percent: number | null; text: string; left?: string; since: number }
/** What is being made right now, of every kind: the window draws its bars from this and Claude reads it. */
export class WorkBoard {
  private items = new Map<string, Work>();
  private listeners = new Set<() => void>();
  on(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  private tell(): void {
    for (const fn of this.listeners) {
      try {
        fn();
      } catch (e) {
        console.error(e);
      }
    }
  }
  /** Report a piece of work (frac 0…1, or -1 while the amount is unknown); null when it ended. */
  set(id: string, w: { label: string; frac: number; text?: string; left?: string } | null): void {
    if (!w) {
      if (!this.items.delete(id)) return;
    } else {
      const frac = w.frac < 0 ? -1 : Math.min(1, w.frac);
      this.items.set(id, { id, label: w.label, frac, percent: frac < 0 ? null : pct(frac, frac >= 1), text: w.text ?? w.label, left: w.left, since: this.items.get(id)?.since ?? Date.now() });
    }
    this.tell();
  }
  /** For work that only has a status line: the percentage is read out of it ("… 37%"); an empty line ends it. */
  say(id: string, line: string): void {
    const m = /(\d{1,3}(?:\.\d+)?)\s?%/.exec(line);
    // (a line about work: it starts with a doing word — "Rendering", "Speaking" — or sets its number off with … or ·)
    const isWork = !!m && (/^\s*\p{L}+ing\b/u.test(line) || /[…·]\s*\d{1,3}(?:\.\d+)?\s?%/.test(line));
    if (!line.trim() || (m && !isWork)) return this.set(id, null);
    this.set(id, { label: line.replace(/[…·\s]*\d{1,3}(?:\.\d+)?\s?%.*$/, "").trim() || line, frac: m ? Math.min(100, Number(m[1])) / 100 : -1, text: line });
  }
  list(): Work[] {
    return [...this.items.values()];
  }
}
/** The one board of this window. */
export const work = new WorkBoard();

const breath = (): Promise<void> =>
  new Promise((done) => {
    // (a message, not a timer: timers are slowed to one a second while the window is hidden, which would slow the work)
    if (typeof MessageChannel !== "undefined") {
      const ch = new MessageChannel();
      ch.port1.onmessage = () => { ch.port1.close(); done(); };
      ch.port2.postMessage(0);
    } else setTimeout(done, 0);
  });
/**
 * For loops that run for minutes inside the window (solving a performance, checking it): call tick() in the loop. It
 * tells who listens how far the loop is, gives the window a breath now and then so its bars move and its buttons work,
 * and stops the loop ("Stopped.") when asked to.
 */
export const longWork = {
  on: null as ((frac: number, what: string) => void) | null,
  stop: false,
  /** Milliseconds of work between two breaths. */
  every: 120,
  breaths: 0,
  last: 0,
  async tick(frac: number, what: string): Promise<void> {
    if (this.stop) throw new Error("Stopped.");
    this.on?.(frac, what);
    const now = Date.now();
    if (now - this.last >= this.every) {
      this.breaths++;
      await breath();
      this.last = Date.now();
    }
  },
};
