/**
 * The window's side of the native media core: frames and sound arrive from ffmpeg (vsmedia://) as plain RGBA /
 * float PCM and are put on ordinary CPU canvases. No browser video decoder and no GPU colour conversion is
 * involved, so a full graphics card can no longer turn the picture green.
 */
import type { Studio } from "../../src/app/studio";
import type { Asset } from "../../src/core/model";
import { fileRefPath, isFileRef, kvGet, setFileRefReader } from "../../src/core/storage";
import { MediaPool, type FramerLike } from "../../src/media/library";
import { setProxyMaking } from "../../src/media/proxy";
import { pathBlob, vsd } from "./bridge";

export interface MediaDescription {
  path: string;
  name: string;
  size: number;
  duration: number;
  width: number;
  height: number;
  fps: number;
  video: { codec: string; width: number; height: number; fps: number; pixfmt: string; rotation: number; hdr: boolean } | null;
  audio: { codec: string; sampleRate: number; channels: number } | null;
  proxy: boolean;
  proxyReady: boolean;
}

type Wrapped = { canvas: OffscreenCanvas; timestamp: number; duration: number };

const enc = (p: string) => encodeURIComponent(p);
const even = (n: number) => Math.max(2, Math.round(n / 2) * 2);
const liveFramers = new Set<NativeFramer>();
export const nativeStats = { framesShown: 0, streamsOpened: 0, errors: 0, lastError: "" };

/** Sequential-friendly reader: plays forward by reading the stream, seeks by reopening it at the new time. */
export class NativeFramer implements FramerLike {
  private cur: Wrapped | null = null;
  private reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  private abort: AbortController | null = null;
  private base = 0;
  private index = 0;
  private chunk: Uint8Array | null = null;
  private chunkOff = 0;
  private readonly bytes: number;
  private readonly buf: Uint8ClampedArray<ArrayBuffer>;
  private pool: { c: OffscreenCanvas; x: OffscreenCanvasRenderingContext2D }[] = [];
  private slot = 0;
  private tail: Promise<unknown> = Promise.resolve();
  private pending = 0;
  private wanted: number | null = null;
  private failedAt = 0;
  private disposed = false;
  lastUse = performance.now();

  constructor(
    private readonly path: string,
    readonly width: number,
    readonly height: number,
    private readonly fps: number,
    private readonly duration: number,
    private readonly quality: "preview" | "export",
    private readonly coalesce = false,
  ) {
    this.bytes = width * height * 4;
    this.buf = new Uint8ClampedArray(new ArrayBuffer(this.bytes));
    liveFramers.add(this);
  }

  get isBusy(): boolean {
    return this.pending > 0;
  }
  get latest(): Wrapped | null {
    return this.cur;
  }

  /**
   * Frame at t (seconds). Requests run strictly one after another (the stream is read by one caller at a time).
   * The preview only ever needs the newest request, so older waiting ones are skipped there; every other reader
   * (thumbnails, export, analysis) gets exactly the frame it asked for.
   */
  frameAt(t: number): Promise<Wrapped | null> {
    this.wanted = t;
    this.pending++;
    const run = this.tail
      .then(async () => {
        this.lastUse = performance.now();
        if (this.disposed) return this.cur;
        if (this.coalesce && this.wanted !== t && this.cur) return this.cur;
        if (!this.reader && this.failedAt && performance.now() - this.failedAt < 1500) return this.cur;
        try {
          return await this.step(t);
        } catch (e) {
          this.failedAt = performance.now();
          nativeStats.errors++;
          nativeStats.lastError = e instanceof Error ? e.message : String(e);
          console.warn("native frame read failed", e);
          await this.close();
          return this.cur;
        }
      })
      .finally(() => {
        this.pending--;
        this.lastUse = performance.now();
      });
    this.tail = run.catch(() => null);
    return run;
  }

  private async step(t: number): Promise<Wrapped | null> {
    const fd = 1 / this.fps;
    t = Math.max(0, Math.min(t, Math.max(0, this.duration - fd * 0.5)));
    const c = this.cur;
    if (c && t >= c.timestamp - 1e-4 && t < c.timestamp + fd - 1e-4) return c;
    const next = this.base + this.index * fd; // time of the next frame the open stream will deliver
    const ahead = this.reader && t >= next - 1e-4 && t - next < 2;
    if (!ahead) await this.open(t);
    for (;;) {
      const ts = this.base + this.index * fd;
      const wantThis = ts + fd > t + 1e-4; // this frame covers t (or is already past it)
      const ok = await this.readFrame(wantThis);
      if (!ok) {
        // End of the file (or the decoder went away): keep the last picture.
        await this.close();
        return this.cur;
      }
      this.index++;
      if (wantThis) {
        const s = this.pool[this.slot]!;
        this.cur = { canvas: s.c, timestamp: ts, duration: fd };
        nativeStats.framesShown++;
        return this.cur;
      }
    }
  }

  private async open(t: number): Promise<void> {
    await this.close();
    const snap = Math.floor(t * this.fps + 1e-6) / this.fps;
    // A third of a frame early: the wanted frame is the first one out, and the frame-rate filter never sees a tie.
    const ss = Math.max(0, snap - 0.3 / this.fps);
    this.abort = new AbortController();
    const url = `vsmedia://m/frames?p=${enc(this.path)}&ss=${ss.toFixed(6)}&w=${this.width}&h=${this.height}&fps=${this.fps}&q=${this.quality}`;
    const res = await fetch(url, { signal: this.abort.signal, cache: "no-store" });
    if (!res.ok || !res.body) throw new Error(`The media core could not read ${this.path.split("/").pop()}: ${await res.text().catch(() => res.status)}`);
    this.reader = res.body.getReader();
    this.base = snap;
    this.index = 0;
    this.chunk = null;
    this.chunkOff = 0;
    nativeStats.streamsOpened++;
  }

  /** Read one frame from the stream; draw it only when it will be shown. False at end of stream. */
  private async readFrame(draw: boolean): Promise<boolean> {
    const r = this.reader;
    if (!r) return false;
    let got = 0;
    while (got < this.bytes) {
      if (!this.chunk || this.chunkOff >= this.chunk.length) {
        const { done, value } = await r.read();
        if (done || !value) return false;
        this.chunk = value;
        this.chunkOff = 0;
      }
      const n = Math.min(this.bytes - got, this.chunk.length - this.chunkOff);
      if (draw) this.buf.set(this.chunk.subarray(this.chunkOff, this.chunkOff + n), got);
      this.chunkOff += n;
      got += n;
    }
    if (draw) {
      if (this.pool.length < 3) {
        const c = new OffscreenCanvas(this.width, this.height);
        const x = c.getContext("2d", { willReadFrequently: true, alpha: true }) as OffscreenCanvasRenderingContext2D;
        this.pool.push({ c, x });
        this.slot = this.pool.length - 1;
      } else this.slot = (this.slot + 1) % this.pool.length;
      this.pool[this.slot]!.x.putImageData(new ImageData(this.buf, this.width, this.height), 0, 0);
    }
    return true;
  }

  /** Stop the decoder but keep the last picture (called when idle, on seeks and on dispose). */
  async close(): Promise<void> {
    const r = this.reader;
    this.reader = null;
    this.chunk = null;
    try {
      this.abort?.abort();
    } catch {
      /* already closed */
    }
    this.abort = null;
    if (r) await r.cancel().catch(() => {});
  }

  get streaming(): boolean {
    return !!this.reader;
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    liveFramers.delete(this);
    await this.close();
    this.pool = [];
  }
}

// A paused clip does not need a running decoder: close idle streams, keep their last picture.
setInterval(() => {
  const now = performance.now();
  for (const f of liveFramers) if (f.streaming && !f.isBusy && now - f.lastUse > 2500) void f.close();
}, 1000);

/* ------------------------------------------------------------------ where an asset's file is */

let pathsCache: Record<string, string> | null = null;
async function pathMap(): Promise<Record<string, string>> {
  return (pathsCache ??= (await kvGet<Record<string, string>>("assetPaths").catch(() => undefined)) ?? {});
}
export function forgetPathCache(): void {
  pathsCache = null;
}
/** The file behind an asset when it is one of the person's own files (no copy is made here). */
export async function knownPath(a: Asset): Promise<string | null> {
  if (!vsd) return null;
  if (isFileRef(a.ref)) return fileRefPath(a.ref);
  const p = (await pathMap())[a.id];
  if (!p) return null;
  return vsd
    .stat(p)
    .then(() => p)
    .catch(() => null);
}

const described = new Map<string, Promise<MediaDescription>>();
export function describe(path: string): Promise<MediaDescription> {
  let d = described.get(path);
  if (!d) {
    d = vsd!.mediaDescribe(path);
    described.set(path, d);
    d.catch(() => described.delete(path));
  }
  return d;
}

/** Waveform peaks in the engine's format (up to 50 per second, at most 6000 values). */
export function peaksFromPcm(ch: Float32Array, sampleRate: number, duration: number): number[] {
  const rate = Math.max(1, Math.min(50, Math.floor(6000 / Math.max(1, duration))));
  const n = Math.max(1, Math.ceil(duration * rate));
  const peaks = new Array<number>(n).fill(0);
  const per = sampleRate / rate;
  for (let i = 0; i < ch.length; i += 32) {
    const idx = Math.floor(i / per);
    const v = Math.abs(ch[i]!);
    if (idx < n && v > peaks[idx]!) peaks[idx] = v;
  }
  return peaks.map((v) => Math.round(v * 1000) / 1000);
}

export async function nativeAudio(path: string, ctx: BaseAudioContext): Promise<AudioBuffer | null> {
  const sr = Math.round(ctx.sampleRate) || 48000;
  const res = await fetch(`vsmedia://m/audio?p=${enc(path)}&sr=${sr}`, { cache: "no-store" });
  if (res.status === 204) return null;
  if (!res.ok) throw new Error(`The media core could not read the sound of ${path.split("/").pop()}: ${await res.text().catch(() => res.status)}`);
  const raw = new Float32Array(await res.arrayBuffer());
  const frames = Math.floor(raw.length / 2);
  if (!frames) return null;
  const out = ctx.createBuffer(2, frames, sr);
  const l = out.getChannelData(0);
  const r = out.getChannelData(1);
  for (let i = 0, k = 0; i < frames; i++, k += 2) {
    l[i] = raw[k]!;
    r[i] = raw[k + 1]!;
  }
  return out;
}

/** Turn the native core on for this window. */
export function installNativeMedia(studio: Studio, resolvePath: (a: Asset) => Promise<string>): void {
  if (!vsd?.mediaDescribe) return;
  setProxyMaking(false); // editing copies are made natively and verified; the in-window maker is retired
  setFileRefReader(async (p) => (await pathBlob(p)).blob);
  MediaPool.native = {
    async framer(a, purpose, maxDim) {
      const path = (await knownPath(a)) ?? (await resolvePath(a));
      const d = await describe(path);
      if (!d.width || !d.height) return null;
      const exporting = purpose === "export";
      let w = even(d.width);
      let h = even(d.height);
      if (maxDim && Math.max(w, h) > maxDim) {
        const k = maxDim / Math.max(w, h);
        w = even(w * k);
        h = even(h * k);
      }
      return new NativeFramer(path, w, h, d.fps || 30, d.duration || a.duration || 0, exporting ? "export" : "preview", purpose === "preview");
    },
    async audio(a, ctx) {
      if (a.kind !== "video" && a.kind !== "audio") return undefined;
      const path = await knownPath(a);
      if (!path) return undefined; // made inside the app (voice, music): the in-window decoder reads it
      const buf = await nativeAudio(path, ctx);
      if (buf && !a.peaks?.length) {
        // Waveform for the timeline, worked out once from the decoded sound.
        const cur = studio.project.assets.find((x) => x.id === a.id);
        if (cur && !cur.peaks?.length) {
          cur.peaks = peaksFromPcm(buf.getChannelData(0), buf.sampleRate, buf.duration);
          studio.touched();
        }
      }
      return buf;
    },
  };
  vsd.onMediaProxy?.((m) => {
    const name = m.path.split("/").pop();
    if (!m.done) studio.setStatus(`Preparing a smooth editing copy of ${name}… ${Math.round((m.progress ?? 0) * 100)}%`);
    else {
      studio.setStatus(m.ok ? `Smooth editing copy of ${name} is ready` : "");
      // New preview streams pick the editing copy up by themselves; drop the open ones so they switch now.
      if (m.ok) void studio.pool.dropFramers("preview").then(() => studio.touched());
    }
  });
}
