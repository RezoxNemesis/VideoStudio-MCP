/** Snapshot undo/redo. Edits within `coalesceMs` under the same label merge into one step (e.g. slider drags). */
export class History<T> {
  private past: { state: string; label: string; at: number }[] = [];
  private future: { state: string; label: string }[] = [];
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
    if (this.past.length > this.limit) this.past.shift();
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
