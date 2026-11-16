// How hard the studio voice may work the machine.
//
// The voice is a language model run by onnxruntime on the CPU. Given every hardware thread it holds the whole
// processor at 100 % (16 Nov 2026: a Ryzen 5 5600 at 90 °C+ while the voices of a film were spoken) and gains
// little: the matrix work is bound by the six real cores and the memory, not by the twelve SMT threads.
// So: one thread per real core (half the hardware threads), at most 6, never under 2, and the process asks
// for a lower scheduling priority so the desktop stays smooth while lines are spoken.

/** Threads for the voice's sessions, from the number of hardware threads. */
export function voiceThreads(hw: number): number {
  const n = Math.floor(Number.isFinite(hw) && hw > 0 ? hw : 4);
  return Math.max(2, Math.min(6, Math.ceil(n / 2)));
}

/**
 * The GPU, when it can really be used: onnxruntime's CUDA provider needs the CUDA 12 runtime, cuBLAS and cuDNN 9
 * installed. Only when the dynamic linker knows all of them is it tried (a missing library must never be loaded
 * half-way). `ldconfig` is the text of `ldconfig -p`.
 */
export function cudaReady(platform: string, ldconfig: string): boolean {
  if (platform !== "linux") return false;
  return ["libcudart.so.12", "libcublasLt.so.12", "libcublas.so.12", "libcudnn.so.9"].every((l) => ldconfig.includes(l));
}
