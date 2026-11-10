/**
 * Player transport for the editing screen: 10-second jumps, frame steps, J/K/L shuttle (reverse and fast, up to 8×),
 * slow motion, in/out marks, loop and "play the marked range" — the controls of a professional player, driven from
 * buttons, keys and the scrub bar under the picture.
 */
import type { Studio } from "../../../src/app/studio";

export const RATES = [0.25, 0.5, 1, 2, 4, 8] as const;

export class Transport {
  /** Speed used by Play when it is not 1 (silent fast / slow playback). */
  rate = 1;
  /** Current shuttle speed (J/K/L): 0 = not shuttling, negative = reverse. */
  shuttle = 0;
  inPoint: number | null = null;
  outPoint: number | null = null;
  loop = false;
  private raf = 0;
  private last = 0;
  private listeners = new Set<() => void>();
  private marks = new Map<string, { i: number | null; o: number | null }>();
  private guard = false;

  constructor(private readonly s: Studio) {
    s.on("time", () => this.onTime());
    s.on("project", () => {
      this.stopShuttle();
      const m = this.marks.get(s.project.id);
      this.inPoint = m?.i ?? null;
      this.outPoint = m?.o ?? null;
      this.emit();
    });
  }

  on(fn: () => void): void {
    this.listeners.add(fn);
  }
  private emit(): void {
    this.marks.set(this.s.project.id, { i: this.inPoint, o: this.outPoint });
    for (const f of this.listeners) f();
  }

  get frame(): number {
    return 1 / this.s.project.fps;
  }
  /** What the player is doing, for the speed read-out. */
  get speedLabel(): string {
    if (this.shuttle) return `${this.shuttle < 0 ? "◀ " : ""}${Math.abs(this.shuttle)}×${this.shuttle > 0 ? " ▶" : ""}`;
    return this.rate === 1 ? "1×" : `${this.rate}×`;
  }
  get moving(): boolean {
    return this.s.playing || this.shuttle !== 0;
  }

  /* ------------------------------------------------------------------ moving the playhead */
  jump(seconds: number): void {
    const was = this.s.playing;
    this.stopShuttle();
    this.s.seek(Math.max(0, Math.min(this.s.duration, this.s.time + seconds)));
    if (was && !this.s.playing) void this.s.play();
  }
  step(frames: number): void {
    this.stopShuttle();
    this.s.pause();
    const f = this.frame;
    this.s.seek(Math.max(0, Math.min(this.s.duration, Math.round(this.s.time / f + frames) * f)));
  }
  goto(t: number): void {
    this.stopShuttle();
    this.s.seek(Math.max(0, Math.min(this.s.duration, t)));
  }

  /* ------------------------------------------------------------------ play / pause / speed */
  toggle(): void {
    if (this.moving) return this.pause();
    this.play();
  }
  play(): void {
    this.stopShuttle();
    if (this.outPoint !== null && this.s.time >= this.outPoint - this.frame / 2) this.s.seek(this.inPoint ?? 0);
    if (this.rate === 1) void this.s.play();
    else this.startShuttle(this.rate);
  }
  pause(): void {
    this.stopShuttle();
    this.s.pause();
  }
  setRate(r: number): void {
    this.rate = r;
    if (this.moving) {
      this.pause();
      this.play();
    }
    this.emit();
  }
  /** J / K / L: K stops; L plays forward and doubles on each press; J the same in reverse. */
  key(k: "j" | "k" | "l"): void {
    if (k === "k") return this.pause();
    const dir = k === "l" ? 1 : -1;
    let next: number;
    if (this.shuttle !== 0 && Math.sign(this.shuttle) === dir) next = dir * Math.min(8, Math.abs(this.shuttle) * 2);
    else if (this.s.playing && dir === 1) next = 2;
    else next = dir;
    if (next === 1) {
      this.stopShuttle();
      void this.s.play();
      this.emit();
    } else this.startShuttle(next);
  }
  private startShuttle(rate: number): void {
    this.s.pause();
    this.shuttle = rate;
    this.last = performance.now();
    cancelAnimationFrame(this.raf);
    const loop = () => {
      if (!this.shuttle) return;
      const now = performance.now();
      const dt = Math.min(0.1, (now - this.last) / 1000);
      this.last = now;
      let t = this.s.time + dt * this.shuttle;
      const lo = this.loop && this.inPoint !== null ? this.inPoint : 0;
      const hi = this.outPoint !== null && this.s.time <= this.outPoint + 1e-3 ? this.outPoint : this.s.duration;
      if (t >= hi) {
        if (this.loop) t = lo;
        else {
          this.s.seek(hi);
          return this.stopShuttle();
        }
      }
      if (t <= 0 && this.shuttle < 0) {
        this.s.seek(0);
        return this.stopShuttle();
      }
      this.guard = true;
      this.s.seek(t);
      this.guard = false;
      this.raf = requestAnimationFrame(loop);
    };
    this.raf = requestAnimationFrame(loop);
    this.emit();
  }
  private stopShuttle(): void {
    if (!this.shuttle) return;
    this.shuttle = 0;
    cancelAnimationFrame(this.raf);
    this.emit();
  }

  /* ------------------------------------------------------------------ marks */
  markIn(t = this.s.time): void {
    this.inPoint = t;
    if (this.outPoint !== null && this.outPoint <= t) this.outPoint = null;
    this.emit();
  }
  markOut(t = this.s.time): void {
    this.outPoint = t;
    if (this.inPoint !== null && this.inPoint >= t) this.inPoint = null;
    this.emit();
  }
  clearMarks(): void {
    this.inPoint = this.outPoint = null;
    this.emit();
  }
  setLoop(on: boolean): void {
    this.loop = on;
    this.emit();
  }
  /** Play only the marked range (or the whole edit when nothing is marked). */
  playRange(): void {
    this.stopShuttle();
    this.s.pause();
    this.s.seek(this.inPoint ?? 0);
    this.play();
  }

  private onTime(): void {
    if (this.guard || !this.s.playing) return;
    const end = this.outPoint !== null && this.s.time <= this.outPoint + 0.25 ? this.outPoint : this.s.duration;
    if (this.s.time >= end - 0.02 && (this.loop || end < this.s.duration - 0.02)) {
      this.s.pause();
      if (this.loop) {
        this.s.seek(this.inPoint ?? 0);
        void this.s.play();
      } else this.s.seek(end);
    }
  }
}
