import { describe, it, expect } from "vitest";
import { gpuPlan } from "../src/media/asr_gpu";

// Listening tried the GPU first and, on a machine with none, waited four minutes for a model that could never
// load before it went to the CPU. Without an adapter there is nothing to try.
describe("the speech model's use of a GPU", () => {
  it("no adapter: no GPU attempt at all", () => {
    expect(gpuPlan(null)).toEqual({ use: false, f16: false });
    expect(gpuPlan(undefined)).toEqual({ use: false, f16: false });
  });
  it("an adapter: used, with half-precision shaders when it has them", () => {
    expect(gpuPlan({ features: new Set(["shader-f16"]) })).toEqual({ use: true, f16: true });
    expect(gpuPlan({ features: new Set<string>() })).toEqual({ use: true, f16: false });
  });
});
