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
    if (o.want !== undefined) {
      // A gait wants the plain thing at about that speed. A capture that veers, side-steps, leaps or stops on the way
      // is passed over while a plain one exists, however near its speed (a "Run Right" once became everyone's run).
      const asked = new Set((o.q ?? "").toLowerCase().split(/[^a-z0-9]+/).filter(Boolean));
      const cost = (m: ClipMeta) => {
        const more = m.desc.toLowerCase().split(/[^a-z]+/).filter((w) => w && !asked.has(w) && !PLAIN_GAIT_WORDS.has(w)).length;
        const other = m.tags.filter((t) => !asked.has(t) && !PLAIN_GAIT_WORDS.has(t)).length;
        return Math.abs(m.speed - o.want!) + Math.abs(m.turn) * 0.02 + more * 0.35 + other * 0.25;
      };
      r.sort((a, b) => cost(a.meta) - cost(b.meta) || b.meta.dur - a.meta.dur);
    }
    return r[0]!.name;
  }

  /**
   * The locomotion clip for a gait (walk, stroll, march, jog, run, sneak — or any words to look for), at about `speed`
   * m/s. The plain, straight capture is looked for first; where the library has none long and straight enough (its
   * marches are two seconds long, its sneaks all curve), a shorter one and then one that bends are taken instead.
   */
  pickGait(gait = "walk", speed?: number): string {
    const g = GAITS[gait], q = g?.q ?? gait, want = speed ?? g?.want ?? 1.3, fast = !!g && g.want > 2;
    // the captures known to hold a whole, clean stride of this gait (see tests/stagegait.test.ts): the one nearest the speed
    const known = (g?.clips ?? []).filter((n) => this.index.clips[n]).sort((a, b) => Math.abs(this.index.clips[a]!.speed - want) - Math.abs(this.index.clips[b]!.speed - want))[0];
    const name =
      known ??
      this.pick({ q, straight: true, minDur: fast ? 0.9 : 2.2, maxDur: 14, want }) ??
      this.pick({ q, straight: true, minDur: fast ? 0.9 : 1.6, maxDur: 14, want }) ??
      this.search({ q, minDur: 1.6, maxDur: 14, limit: 400 }).filter((x) => x.meta.travel > 0.75 * x.meta.path && x.meta.travel > 1 && Math.abs(x.meta.turn) < 65).sort((a, b) => Math.abs(a.meta.turn) - Math.abs(b.meta.turn))[0]?.name;
    if (!name) throw new Error(`No "${gait}" motion to travel with in the library. Try gait ${Object.keys(GAITS).join(", ")}, or give a clip.`);
    return name;
  }
}

/**
 * The gaits a "go" knows: what to look for, the speed it wants (m/s), how long one stride may be (s), and the captures
 * to use first — measured to hold a whole stride whose two ends are the same pose (a clean loop), plain and straight.
 */
export const GAITS: Record<string, { q: string; want: number; pmin: number; pmax: number; clips?: string[] }> = {
  walk: { q: "walk", want: 1.3, pmin: 0.85, pmax: 1.35, clips: ["35_29", "35_03", "35_33", "39_04", "16_22"] },
  stroll: { q: "walk", want: 0.9, pmin: 0.95, pmax: 1.5, clips: ["49_01", "35_29"] },
  march: { q: "march", want: 1.0, pmin: 0.8, pmax: 1.3, clips: ["91_19", "138_05"] },
  jog: { q: "jog", want: 2.6, pmin: 0.55, pmax: 0.95, clips: ["16_35", "35_18"] },
  run: { q: "run", want: 3.8, pmin: 0.5, pmax: 0.9, clips: ["09_01", "35_24"] },
  sneak: { q: "sneak", want: 0.7, pmin: 0.9, pmax: 1.8, clips: ["120_08"] },
};

/** Words of a capture's description (and tags) that say nothing but the gait itself. */
const PLAIN_GAIT_WORDS = new Set(["walk", "walking", "run", "running", "jog", "jogging", "march", "marching", "sneak", "sneaking", "forward", "forwards", "straight", "normal", "and", "a", "the", "slow", "fast", "pace", "stride", "cycle"]);

/** One library for the whole app. */
export const motions = new MotionLibrary();
