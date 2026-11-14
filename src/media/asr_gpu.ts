/**
 * What the speech model can use of a GPU. Without an adapter there is none to try: a machine with no GPU
 * goes straight to the CPU instead of waiting for a model that will never load.
 */
export function gpuPlan(adapter: { features: { has(name: string): boolean } } | null | undefined): { use: boolean; f16: boolean } {
  return adapter ? { use: true, f16: adapter.features.has("shader-f16") } : { use: false, f16: false };
}
