import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { bufferSource, externalize, lazyBytes } from "../src/media/onnxext";

describe("externalize big models", () => {
  it("moves weights out of the graph and serves them lazily", async () => {
    const ort = await import("../node_modules/onnxruntime-web/dist/ort.wasm.min.mjs" as string);
    ort.env.wasm.numThreads = 1;
    const buf = new Uint8Array(readFileSync(new URL("./fixtures/tiny-fp16.onnx", import.meta.url)));
    const x = new Uint16Array(64).map((_, i) => 0x3c00 ^ (i % 3 ? 0 : 0x8000));
    const run = async (s: { run(f: unknown): Promise<{ y: { data: Uint16Array } }> }) => Array.from((await s.run({ x: new ort.Tensor("float16", x, [1, 64]) })).y.data);
    const a = await run(await ort.InferenceSession.create(buf));
    const r = externalize(bufferSource(buf), "weights.bin", 256);
    expect(r.tensors).toBe(3);
    expect(r.graph.length).toBeLessThan(1000);
    const b = await run(await ort.InferenceSession.create(r.graph, { externalData: [{ path: "weights.bin", data: lazyBytes(bufferSource(buf)) }] }));
    expect(b).toEqual(a);
  });
});
