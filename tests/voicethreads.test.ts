import { describe, expect, it } from "vitest";
import { voiceThreads, cudaReady } from "../desktop/voice/src/threads";

describe("studio voice threads", () => {
  it("uses one thread per real core, not every hardware thread", () => {
    expect(voiceThreads(12)).toBe(6);   // Ryzen 5 5600: 6 cores, 12 threads
    expect(voiceThreads(8)).toBe(4);
    expect(voiceThreads(32)).toBe(6);   // never more than 6
    expect(voiceThreads(2)).toBe(2);    // never fewer than 2
    expect(voiceThreads(NaN)).toBe(2);
  });
  it("tries the GPU only when every CUDA library is installed", () => {
    const all = "libcudart.so.12 libcublasLt.so.12 libcublas.so.12 libcudnn.so.9";
    expect(cudaReady("linux", all)).toBe(true);
    expect(cudaReady("linux", "libcudart.so.12 libcublas.so.12")).toBe(false);
    expect(cudaReady("win32", all)).toBe(false);
  });
});
