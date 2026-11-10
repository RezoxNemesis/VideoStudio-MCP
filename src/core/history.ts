/** Snapshot undo/redo. Edits within `coalesceMs` under the same label merge into one step (e.g. slider drags). */
export class History<T> {
  private past: { state: string; label: string; at: number }[] = [];
  private future: { state: string; label: string }[] = [];
  /** How many steps have ever been recorded (so a group can tell which steps are its own, even after old ones fell off the end). */
  count = 0;
  constructor(
    private readonly limit = 120,
    private readonly coalesceMs = 600,
  ) {}

  /** True if a change with this label would start a new undo step (so the caller should snapshot). */
  wouldRecord(label: string): boolean {
    const last = this.past[this.past.length - 1];
    return !(last && last.label === label && Date.now() - last.at < this.coalesceMs);
  }

  /** Extend the current step's coalescing window without a snapshot. */
  touch(label: string): void {
    const last = this.past[this.past.length - 1];
    if (last && last.label === label) last.at = Date.now();
  }

  /** Record the state *before* a change (an object, or its JSON). */
  push(state: T | string, label: string): void {
    const now = Date.now();
    if (!this.wouldRecord(label)) {
      this.touch(label);
      return;
    }
    this.past.push({ state: typeof state === "string" ? state : JSON.stringify(state), label, at: now });
    this.count++;
    if (this.past.length > this.limit) this.past.shift();
    this.future = [];
  }

  /**
   * Fold every step recorded since `since` (a value of `count` taken earlier) into one step that undoes back to
   * `state` — or, with no state, simply forget those steps (after a roll-back).
   */
  collapse(since: number, state?: string, label = "edit"): void {
    const mine = Math.min(this.past.length, Math.max(0, this.count - since));
    this.past.length -= mine;
    this.count = since;
    if (state !== undefined) {
      this.past.push({ state, label, at: 0 });
      this.count++;
      if (this.past.length > this.limit) this.past.shift();
    }
    this.future = [];
  }

  undo(current: T): { state: T; label: string } | null {
    const prev = this.past.pop();
    if (!prev) return null;
    this.future.push({ state: JSON.stringify(current), label: prev.label });
    return { state: JSON.parse(prev.state) as T, label: prev.label };
  }

  redo(current: T): { state: T; label: string } | null {
    const next = this.future.pop();
    if (!next) return null;
    this.past.push({ state: JSON.stringify(current), label: next.label, at: 0 });
    return { state: JSON.parse(next.state) as T, label: next.label };
  }

  get canUndo(): boolean {
    return this.past.length > 0;
  }
  get canRedo(): boolean {
    return this.future.length > 0;
  }
  clear(): void {
    this.past = [];
    this.future = [];
  }
}
