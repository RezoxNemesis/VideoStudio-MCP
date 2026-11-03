/**
 * Edit proxies: a light 720p copy of a big (2K/4K) video, with a key frame every half second.
 * The preview, scrubbing, the person matte and other helpers read the proxy; the export always reads the original.
 * Phones have very few hardware decoders and 4K decoding is slow, so this keeps the editor responsive and the
 * preview from going blank while the agent works or a render runs.
 */
import { BlobSource, BufferTarget, Conversion, Input, Mp4OutputFormat, Output, ALL_FORMATS } from "mediabunny";
import type { Asset } from "../core/model";
import { getBlob, putBlob } from "../core/storage";

export const PROXY_MAX = 1280;
const making = new Map<string, Promise<Asset["proxy"]>>();

/** True when a video is big enough to deserve a proxy. */
export function wantsProxy(a: Asset): boolean {
  return a.kind === "video" && !a.proxy && Math.max(a.width, a.height) > 1920;
}

export function makeProxy(a: Asset, onProgress?: (p: number) => void): Promise<Asset["proxy"]> {
  if (a.proxy) return Promise.resolve(a.proxy);
  let p = making.get(a.id);
  if (p) return p;
  p = (async () => {
    const src = await getBlob(a.ref);
    const input = new Input({ formats: ALL_FORMATS, source: new BlobSource(src) });
    try {
      const k = Math.min(1, PROXY_MAX / Math.max(a.width, a.height));
      const w = Math.max(2, Math.round((a.width * k) / 2) * 2),
        h = Math.max(2, Math.round((a.height * k) / 2) * 2);
      const target = new BufferTarget();
      const output = new Output({ format: new Mp4OutputFormat({ fastStart: "in-memory" }), target });
      const conv = await Conversion.init({
        input,
        output,
        tracks: "primary",
        video: { width: w, height: h, fit: "fill", codec: "avc", bitrate: 2_500_000, keyFrameInterval: 0.5, forceTranscode: true },
        audio: { discard: true },
      });
      if (!conv.isValid) throw new Error("This video cannot be converted for editing.");
      conv.onProgress = (x: number) => onProgress?.(x);
      await conv.execute();
      const blob = new Blob([target.buffer!], { type: "video/mp4" });
      const ref = await putBlob(`${a.id}-proxy`, blob);
      a.proxy = { ref, width: w, height: h };
      return a.proxy;
    } finally {
      input.dispose();
      making.delete(a.id);
    }
  })();
  making.set(a.id, p);
  return p;
}
