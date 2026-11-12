/**
 * Motion library: 2,500+ motion-capture clips (CMU Graphics Lab database) with descriptions and tags.
 * The index is small and always in memory; a subject's motion data (sNNN.pack) loads the first time one of its
 * clips is used. Search by words ("kick", "sit down", "walk turn left") or pick by properties (speed, travel, turn).
 */
import { loadJson, loadPack, type Pack } from "../pack";

export interface ClipMeta {
  /** Frames at the library rate (30 fps). */
  n: number;
  dur: number;
  /** Subject number (which pack holds it). */
  s: number;
  desc: string;
  tags: string[];
  /** Median speed over the ground, m/s for a typical adult. */
  speed: number;
  /** Path length and straight-line travel, metres. */
  path: number;
  travel: number;
  /** Net turn of the hips over the clip, degrees (+ = to the left). */
  turn: number;
  /** Share of frames with both feet off the ground. */
  air: number;
  ground: number;
  stand: number;
  leg?: number;
  rest?: Record<string, [number, number, number]>;
}
export interface MotionIndex {
  fps: number;
  joints: string[];
  source: string;
  subjects: Record<string, { title: string; leg: number; rest: Record<string, [number, number, number]>; clips: number }>;
  clips: Record<string, ClipMeta>;
}

export interface MotionQuery {
  /** Words matched against the description, tags and subject title (all must match). */
  q?: string;
  tags?: string[];
  minDur?: number;
  maxDur?: number;
  /** [min, max] m/s. */
  speed?: [number, number];
  /** Mostly straight travel (path ≈ travel) and little turning. */
  straight?: boolean;
  /** Stays roughly on the spot. */
  inPlace?: boolean;
  subject?: number;
  limit?: number;
}

export class MotionLibrary {
  index!: MotionIndex;
  private packs = new Map<number, Pack>();
  private loading = new Map<number, Promise<Pack>>();
  private ready: Promise<void> | null = null;

  /** Load the index (once). */
  load(): Promise<void> {
    if (!this.ready) {
      this.ready = loadJson<MotionIndex>("motion/index.json").then((ix) => {
        this.index = ix;
      });
      this.ready.catch(() => (this.ready = null));
    }
    return this.ready;
  }
  get loaded(): boolean {
    return !!this.index;
  }
  has(name: string): boolean {
    return !!this.index?.clips[name];
  }
  meta(name: string): ClipMeta {
    const m = this.index.clips[name];
    if (!m) throw new Error(`No motion "${name}". Search the library with stage.motions.`);
    return m;
  }
  /** The subject pack if it is already in memory (the engine reads clips synchronously). */
  packNow(subject: number): Pack | undefined {
    return this.packs.get(subject);
  }
  isReady(names: Iterable<string>): boolean {
    for (const n of names) {
      const m = this.index?.clips[n];
      if (m && !this.packs.has(m.s)) return false;
    }
    return true;
  }
  /** Make sure the packs for these clips are in memory. Unknown names are reported, not ignored. */
  async ensure(names: Iterable<string>): Promise<void> {
    await this.load();
    const need = new Set<number>();
    for (const n of names) need.add(this.meta(n).s);
    await Promise.all(
      [...need].map((s) => {
        if (this.packs.has(s)) return Promise.resolve();
        let p = this.loading.get(s);
        if (!p) {
          p = loadPack(`motion/s${String(s).padStart(3, "0")}.pack`);
          this.loading.set(s, p);
          p.then((pk) => this.packs.set(s, pk)).catch(() => {}).finally(() => this.loading.delete(s));
        }
        return p.then(() => undefined);
      }),
    );
  }

  /** Search. Results are ranked: description hits first, then tags, shorter clips before very long ones. */
  search(o: MotionQuery = {}): { name: string; meta: ClipMeta; score: number }[] {
    const words = (o.q ?? "").toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
    const out: { name: string; meta: ClipMeta; score: number }[] = [];
    for (const [name, m] of Object.entries(this.index.clips)) {
      if (o.subject !== undefined && m.s !== o.subject) continue;
      if (o.minDur !== undefined && m.dur < o.minDur) continue;
      if (o.maxDur !== undefined && m.dur > o.maxDur) continue;
      if (o.speed && (m.speed < o.speed[0] || m.speed > o.speed[1])) continue;
      if (o.straight && !(m.travel > 0.8 * m.path && Math.abs(m.turn) < 25 && m.travel > 1)) continue;
      if (o.inPlace && m.travel > 0.6) continue;
      if (o.tags && !o.tags.every((t) => m.tags.includes(t))) continue;
      let score = 0;
      if (words.length) {
        const d = m.desc.toLowerCase();
        const title = (this.index.subjects[String(m.s)]?.title ?? "").toLowerCase();
        let ok = true;
        for (const w of words) {
          const stem = w.length > 4 ? w.replace(/(ing|ed|es|s)$/, "") : w;
          if (d.includes(stem)) score += 3;
          else if (m.tags.some((t) => t === w || t === stem)) score += 2;
          else if (title.includes(stem)) score += 1;
          else if (name === w) score += 5;
          else ok = false;
        }
        if (!ok) continue;
      }
      score -= Math.max(0, m.dur - 12) * 0.03;
      out.push({ name, meta: m, score });
    }
    out.sort((a, b) => b.score - a.score || a.name.localeCompare(b.name, undefined, { numeric: true }));
    return out.slice(0, o.limit ?? 40);
  }

  /** The clip that best fits a need, or undefined. `want` is a target speed for locomotion. */
  pick(o: MotionQuery & { want?: number }): string | undefined {
    const r = this.search({ ...o, limit: 400 });
    if (!r.length) return undefined;
    if (o.want !== undefined) r.sort((a, b) => Math.abs(a.meta.speed - o.want!) - Math.abs(b.meta.speed - o.want!) || b.meta.dur - a.meta.dur);
    return r[0]!.name;
  }
}

/** One library for the whole app. */
export const motions = new MotionLibrary();
