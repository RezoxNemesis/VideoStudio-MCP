/**
 * Pro render for VideoStudio Desktop: the engine's GPU compositor draws each frame, the pixels are read straight
 * from the GPU and streamed to native ffmpeg — NVENC on NVIDIA cards (H.264 / HEVC 10-bit / AV1), x264/x265 on the
 * CPU, or ProRes 422 HQ / 4444 masters — with the engine's offline audio mix as 48 kHz AAC (PCM for ProRes).
 * No browser encoder limits: any size up to 8K, any frame rate up to 120.
 */
import { preloadStage } from "../../src/stage/draw";
import { preloadModels } from "../../src/anim/three3d";
import { preloadFills } from "../../src/anim/render";
import { getBlob } from "../../src/core/storage";
import { projectDuration, type Project } from "../../src/core/model";
import type { MediaPool } from "../../src/media/library";
import { Compositor } from "../../src/render/compositor";
import { buildLayers } from "../../src/render/layers";
import { mixdown } from "../../src/render/audio";
import { loadFonts } from "../../src/render/fonts";
import { need } from "./bridge";

export type ProCodec = "auto" | "h264" | "hevc" | "nvenc_h264" | "nvenc_hevc" | "nvenc_av1" | "x264" | "x265" | "prores" | "prores4444" | "vp9";

export interface ProRenderOptions {
  height?: number;
  fps?: number;
  codec?: ProCodec;
  /** 0..100 (100 = best). Default 88. */
  quality?: number;
  from?: number;
  to?: number;
  name?: string;
  audio?: boolean;
  /** Convert frames to the encoder's colour format (BT.709 NV12) on the GPU. Default true; false sends RGBA. */
  gpuConvert?: boolean;
}

export interface ProProgress {
  frame: number;
  frames: number;
  fps: number;
  stage: "audio" | "video" | "finalizing" | "done";
  etaSec: number;
}

const even = (n: number) => Math.max(2, Math.round(n / 2) * 2);

/** 32-bit float WAV (no quantisation before the AAC/PCM encoder). */
function wavF32(b: AudioBuffer): Uint8Array {
  const ch = Math.min(2, b.numberOfChannels);
  const n = b.length;
  const out = new ArrayBuffer(44 + n * ch * 4);
  const v = new DataView(out);
  const w = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, "RIFF");
  v.setUint32(4, 36 + n * ch * 4, true);
  w(8, "WAVE");
  w(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 3, true); // IEEE float
  v.setUint16(22, ch, true);
  v.setUint32(24, b.sampleRate, true);
  v.setUint32(28, b.sampleRate * ch * 4, true);
  v.setUint16(32, ch * 4, true);
  v.setUint16(34, 32, true);
  w(36, "data");
  v.setUint32(40, n * ch * 4, true);
  const f = new Float32Array(out, 44);
  const d = [b.getChannelData(0), b.getChannelData(ch > 1 ? 1 : 0)];
  for (let i = 0, o = 0; i < n; i++) for (let c = 0; c < ch; c++) f[o++] = d[c]![i]!;
  return new Uint8Array(out);
}

export class ProRender {
  private cancelled = false;
  onProgress: (p: ProProgress) => void = () => {};

  constructor(
    readonly project: Project,
    readonly pool: MediaPool,
    readonly opts: ProRenderOptions = {},
  ) {}

  cancel(): void {
    this.cancelled = true;
  }

  async run() {
    const vsd = need();
    await loadFonts();
    const p = JSON.parse(JSON.stringify(this.project)) as Project;
    const o = this.opts;
    const fps = Math.max(1, Math.min(120, o.fps ?? p.fps));
    const H = even(Math.min(4320, o.height ?? p.height));
    const W = even(Math.min(7680, (p.width / p.height) * H));
    const from = Math.max(0, o.from ?? 0);
    const to = Math.min(o.to ?? projectDuration(p), projectDuration(p));
    if (to - from < 1 / fps) throw new Error("Nothing to render: the timeline is empty.");
    const frames = Math.round((to - from) * fps);
    const t0 = performance.now();

    for (const s of p.scenes) await preloadFills(s), await preloadModels(s, (id) => getBlob(p.assets.find((a) => a.id === id)!.ref)), await preloadStage(s);
    let audio: Uint8Array | null = null;
    if (o.audio !== false) {
      this.onProgress({ frame: 0, frames, fps: 0, stage: "audio", etaSec: 0 });
      const mixed = await mixdown(p, this.pool, from, to, 48_000);
      if (mixed) audio = wavF32(mixed);
    }
    const name = (o.name ?? p.name).replace(/[^\w\- ]+/g, "").trim() || "VideoStudio";
    const stamp = new Date().toISOString().replace(/[-:]/g, "").replace("T", "_").slice(0, 15);
    // 4:2:0 outputs get their frames converted to NV12 on the GPU (less than half the bytes to read back and send).
    const wantNV12 = o.gpuConvert !== false && W % 4 === 0 && H % 2 === 0;
    const enc = await vsd.enc.open({ width: W, height: H, fps, codec: o.codec ?? "auto", quality: o.quality ?? 88, name: `${name}_${stamp}`, audio, input: wantNV12 ? "nv12" : "rgba" });
    audio = null;
    const nv12 = enc.input === "nv12";

    const canvas = new OffscreenCanvas(W, H);
    const comp = new Compositor(canvas);
    const gl = comp.gl;
    const frameBytes = nv12 ? W * H * 1.5 : W * H * 4;
    const bufs = [new Uint8Array(frameBytes), new Uint8Array(frameBytes)];
    let pending: Promise<void> = Promise.resolve();
    let last = performance.now();
    /** Where the time went (ms per frame, averaged): building layers (decode, text, drawings), GPU draw + read-back, encoder hand-over. */
    const prof = { layers: 0, draw: 0, encode: 0 };
    try {
      for (let i = 0; i < frames; i++) {
        if (this.cancelled) throw new Error("Render cancelled.");
        const t = from + i / fps;
        const m0 = performance.now();
        const layers = await buildLayers(p, this.pool, t, { purpose: "export", wait: true, outW: W, outH: H });
        const m1 = performance.now();
        comp.render(layers, W, H, p.background);
        const buf = bufs[i & 1]!;
        if (nv12) comp.readNV12(buf);
        else gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, buf);
        const m2 = performance.now();
        await pending; // the previous frame is in ffmpeg's pipe before we hand over this one
        pending = vsd.enc.frame(enc.id, buf);
        const now = performance.now();
        prof.layers += m1 - m0;
        prof.draw += m2 - m1;
        prof.encode += now - m2;
        if (now - last > 250 || i === frames - 1) {
          last = now;
          const rate = (i + 1) / ((now - t0) / 1000);
          this.onProgress({ frame: i + 1, frames, fps: rate, stage: "video", etaSec: (frames - i - 1) / Math.max(0.01, rate) });
        }
      }
      await pending;
      this.onProgress({ frame: frames, frames, fps: 0, stage: "finalizing", etaSec: 0 });
      const r = await vsd.enc.finish(enc.id);
      const renderSeconds = (performance.now() - t0) / 1000;
      this.onProgress({ frame: frames, frames, fps: frames / renderSeconds, stage: "done", etaSec: 0 });
      return { path: r.path, name: r.path.split("/").pop(), bytes: r.bytes, seconds: to - from, width: W, height: H, fps, codec: r.codec, audio: o.audio === false ? "none" : r.path.endsWith(".mov") ? "pcm_s24le" : r.path.endsWith(".webm") ? "opus" : "aac 320k", frameFormat: nv12 ? "nv12 (converted on the GPU)" : "rgba", renderSeconds: Math.round(renderSeconds * 10) / 10, speed: `${(frames / renderSeconds).toFixed(1)} fps`, msPerFrame: { layers: Math.round(prof.layers / frames), draw: Math.round(prof.draw / frames), encode: Math.round(prof.encode / frames) } };
    } catch (e) {
      await pending.catch(() => {});
      await vsd.enc.abort(enc.id).catch(() => {});
      throw e;
    } finally {
      comp.dispose();
      await this.pool.dropFramers("export");
    }
  }
}
