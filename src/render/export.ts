/**
 * Offline renderer: GPU compositing → hardware H.264/HEVC encoding (WebCodecs) → MP4 streamed straight to
 * shared storage. Audio is mixed offline and encoded to AAC.
 */
import { AudioBufferSource, CanvasSource, Mp4OutputFormat, Output, Quality, StreamTarget, canEncodeAudio, canEncodeVideo, type QualityLevel, type VideoCodec } from "mediabunny";
import { projectDuration, type Project } from "../core/model";
import { preloadFills } from "../anim/render";
import type { MediaPool } from "../media/library";
import { OutputFile, keepAwake } from "../native/storage";
import { Compositor } from "./compositor";
import { buildLayers } from "./layers";
import { mixdown } from "./audio";

export interface ExportOptions {
  /** Output height in px; width follows the project aspect. Defaults to the project size. */
  height?: number;
  fps?: number;
  quality?: QualityLevel;
  /** Explicit video bitrate in bits/s (overrides quality). */
  bitrate?: number;
  codec?: "avc" | "hevc" | "auto";
  from?: number;
  to?: number;
  name?: string;
  audio?: boolean;
}

export interface ExportProgress {
  frame: number;
  frames: number;
  fps: number;
  stage: "audio" | "video" | "finalizing" | "done";
  etaSec: number;
}

export interface ExportResult {
  uri: string;
  path: string;
  name: string;
  bytes: number;
  seconds: number;
  width: number;
  height: number;
  fps: number;
  codec: string;
  audioCodec: string;
  renderSeconds: number;
  warnings: string[];
  blob?: Blob;
}

export class ExportJob {
  private cancelled = false;
  readonly warnings: string[] = [];
  onProgress: (p: ExportProgress) => void = () => {};
  constructor(
    readonly project: Project,
    readonly pool: MediaPool,
    readonly opts: ExportOptions = {},
  ) {}

  cancel(): void {
    this.cancelled = true;
  }

  async run(): Promise<ExportResult> {
    const p = JSON.parse(JSON.stringify(this.project)) as Project;
    const o = this.opts;
    const fps = Math.max(1, Math.min(120, o.fps ?? p.fps));
    const H = even(o.height ?? p.height);
    const W = even((p.width / p.height) * H);
    const from = Math.max(0, o.from ?? 0);
    const to = Math.min(o.to ?? projectDuration(p), projectDuration(p));
    if (to - from < 1 / fps) throw new Error("Nothing to render: the timeline is empty.");
    const frames = Math.round((to - from) * fps);
    const quality = o.bitrate ? new Quality(o.bitrate) : new Quality(o.quality ?? "high");

    let codec: VideoCodec = "avc";
    const wantHevc = o.codec === "hevc";
    if (wantHevc && (await canEncodeVideo("hevc", { width: W, height: H, quality }))) codec = "hevc";
    else if (!(await canEncodeVideo("avc", { width: W, height: H, quality }))) {
      throw new Error(`This phone cannot encode ${W}×${H} H.264. Try a smaller size.`);
    }

    const name = `${(o.name ?? p.name).replace(/[^\w\- ]+/g, "").trim() || "VideoStudio"}_${stamp()}.mp4`;
    const file = new OutputFile(name, "video/mp4", "Movies");
    await file.open();
    const output = new Output({ format: new Mp4OutputFormat({ fastStart: false }), target: new StreamTarget(file.writable() as WritableStream<never>) });
    const canvas = new OffscreenCanvas(W, H);
    const comp = new Compositor(canvas);
    const video = new CanvasSource(canvas, { codec, quality, keyFrameInterval: 2, sizeChangeBehavior: "deny" });
    output.addVideoTrack(video, { frameRate: fps });

    const t0 = performance.now();
    await keepAwake(true);
    try {
      for (const s of p.scenes) await preloadFills(s);
      let audio: AudioBufferSource | null = null;
      let audioCodec = "";
      let mixed: AudioBuffer | null = null;
      if (o.audio !== false) {
        this.onProgress({ frame: 0, frames, fps: 0, stage: "audio", etaSec: 0 });
        mixed = await mixdown(p, this.pool, from, to);
        if (mixed) {
          const ac = (await canEncodeAudio("aac", { numberOfChannels: 2, sampleRate: mixed.sampleRate })) ? "aac" : (await canEncodeAudio("opus", { numberOfChannels: 2, sampleRate: mixed.sampleRate })) ? "opus" : null;
          if (ac) {
            audio = new AudioBufferSource({ codec: ac, quality: new Quality("high") });
            output.addAudioTrack(audio);
            audioCodec = ac;
          } else this.warnings.push("This device has no audio encoder, so the video was saved without sound.");
        }
      }
      await output.start();
      if (audio && mixed) {
        await audio.add(mixed);
        audio.close();
      }
      let last = performance.now();
      for (let i = 0; i < frames; i++) {
        if (this.cancelled) throw new Error("Render cancelled.");
        const t = from + i / fps;
        const layers = await buildLayers(p, this.pool, t, { purpose: "export", wait: true, outW: W, outH: H });
        comp.render(layers, W, H, p.background);
        await video.add(i / fps, 1 / fps);
        const now = performance.now();
        if (now - last > 250 || i === frames - 1) {
          last = now;
          const rate = (i + 1) / ((now - t0) / 1000);
          this.onProgress({ frame: i + 1, frames, fps: rate, stage: "video", etaSec: (frames - i - 1) / Math.max(0.01, rate) });
        }
      }
      video.close();
      this.onProgress({ frame: frames, frames, fps: 0, stage: "finalizing", etaSec: 0 });
      await output.finalize();
      const saved = await file.finish();
      const renderSeconds = (performance.now() - t0) / 1000;
      this.onProgress({ frame: frames, frames, fps: frames / renderSeconds, stage: "done", etaSec: 0 });
      return { ...saved, name, bytes: file.bytes, seconds: to - from, width: W, height: H, fps, codec, audioCodec, renderSeconds, warnings: this.warnings };
    } catch (e) {
      await output.cancel().catch(() => {});
      await file.abort();
      throw e;
    } finally {
      comp.dispose();
      await this.pool.dropFramers("export");
      await keepAwake(false);
    }
  }
}

function even(n: number): number {
  return Math.max(2, Math.round(n / 2) * 2);
}

function stamp(): string {
  const d = new Date();
  const z = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${z(d.getMonth() + 1)}${z(d.getDate())}_${z(d.getHours())}${z(d.getMinutes())}${z(d.getSeconds())}`;
}
