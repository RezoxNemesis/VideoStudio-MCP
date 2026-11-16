import { describe, it, expect, vi } from "vitest";
import { createRequire } from "node:module";
const { watchdog } = createRequire(import.meta.url)("../desktop/electron/watchdog.cjs");

describe("a window that stops answering", () => {
  it("is restarted when it stays frozen, and left alone when it answers again in time", () => {
    vi.useFakeTimers();
    let hung = 0; const w = watchdog({ ms: 12000, onHang: () => { hung++; } });
    w.unresponsive(); vi.advanceTimersByTime(5000); w.responsive(); vi.advanceTimersByTime(20000);
    expect(hung).toBe(0);                                   // (a moment's stall: nothing done)
    w.unresponsive(); w.unresponsive(); vi.advanceTimersByTime(12001);
    expect(hung).toBe(1);                                   // (frozen: restarted, once)
    vi.useRealTimers();
  });
});
