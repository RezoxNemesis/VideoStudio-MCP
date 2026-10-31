/**
 * Media pool: probing imports, hardware-decoded video frames (WebCodecs via mediabunny), images and audio.
 * Assets come only from files the person picks, links an agent supplies, voices and renders made here.
 */
import { ALL_FORMATS, AudioBufferSink, BlobSource, CanvasSink, Input, type InputAudioTrack, type InputVideoTrack, type WrappedCanvas } from "mediabunny";
import { uid, type Asset, type AssetKind, type Project } from "../core/model";
import { getBlob, putBlob } from "../core/storage";

export function kindFor(mime: string, name: string): AssetKind | null {
  const m = mime.toLowerCase();
  if (m.startsWith("video/")) return "video";
  if (m.startsWith("image/")) return "image";
  if (m.startsWith("audio/")) return "audio";
  const ext = name.toLowerCase().split(".").pop() ?? "";
  if (["mp4", "mov", "m4v", "webm", "mkv", "3gp"].includes(ext)) return "video";
  if (["png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic"].includes(ext)) return "image";
  if (["mp3", "wav", "m4a", "aac", "ogg", "opus", "flac"].includes(ext)) return "audio";
  return null;
}

/** Copy a blob into private storage and describe it. */
export async function importBlob(p: Project, blob: Blob, name: string, createdBy: Asset["createdBy"] = "user"): Promise<Asset> {
  const kind = kindFor(blob.type, name);
  if (!kind) throw new Error(`"${name}" is not a video, image or audio file.`);
  const id = uid("ast");
  const info = await probe(blob, kind);
  const ref = await putBlob(id, blob);
  const asset: Asset = { id, kind, name, mime: blob.type || "application/octet-stream", size: blob.size, ref, createdBy, ...info };
  p.assets.push(asset);
  return asset;
}

async function probe(blob: Blob, kind: AssetKind): Promise<{ duration: number; width: number; height: number; peaks?: number[] }> {
  if (kind === "image") {
    const bmp = await createImageBitmap(blob);
    const r = { duration: 0, width: bmp.width, height: bmp.height };
    bmp.close();
    return r;
  }
  const input = new Input({ formats: ALL_FORMATS, source: new BlobSource(blob) });
  try {
    if (kind === "video") {
      const v = await input.getPrimaryVideoTrack();
      if (!v) throw new Error("This file has no video track.");
      if (!(await v.canDecode())) throw new Error("This phone cannot decode this video's codec.");
      const duration = await input.computeDuration();
      return { duration, width: v.displayWidth, height: v.displayHeight };
    }
    const a = await input.getPrimaryAudioTrack();
    if (!a) throw new Error("This file has no audio track.");
    const duration = await input.computeDuration();
    const peaks = await computePeaks(a, duration);
    return { duration, width: 0, height: 0, peaks };
  } finally {
    input.dispose();
  }
}

/** Waveform peaks at up to 50 per second (capped at 6000 values). */
async function computePeaks(track: InputAudioTrack, duration: number): Promise<number[]> {
  const rate = Math.max(1, Math.min(50, Math.floor(6000 / Math.max(1, duration))));
  const peaks = new Array<number>(Math.ceil(duration * rate)).fill(0);
  const sink = new AudioBufferSink(track);
  for await (const { buffer, timestamp } of sink.buffers()) {
    const ch = buffer.getChannelData(0);
    const per = buffer.sampleRate / rate;
    for (let i = 0; i < ch.length; i += 64) {
      const idx = Math.floor(timestamp * rate + i / per);
      const v = Math.abs(ch[i]!);
      if (idx < peaks.length && v > peaks[idx]!) peaks[idx] = v;
    }
  }
  return peaks.map((v) => Math.round(v * 1000) / 1000);
}

/* ------------------------------ video frames ------------------------------ */

/** Sequential-friendly frame reader: plays forward by iterating, seeks by restarting the decoder. */
export class VideoFramer {
  private iter: AsyncGenerator<WrappedCanvas, void, unknown> | null = null;
  private cur: WrappedCanvas | null = null;
  private next: WrappedCanvas | null = null;
  private busy: Promise<WrappedCanvas | null> | null = null;
  private wanted: number | null = null;
  readonly sink: CanvasSink;

  constructor(
    readonly track: InputVideoTrack,
    width?: number,
    height?: number,
  ) {
    this.sink = new CanvasSink(track, { width, height, fit: width && height ? "contain" : undefined, poolSize: 4 });
  }

  /** Last frame that was ready (never waits). */
  get latest(): WrappedCanvas | null {
    return this.cur;
  }

  /** Frame at t (seconds). Concurrent calls coalesce: only the newest request is decoded. */
  async frameAt(t: number): Promise<WrappedCanvas | null> {
    this.wanted = t;
    if (this.busy) return this.busy.then(() => (this.wanted === t ? this.decode(t) : this.cur));
    return this.decode(t);
  }

  private decode(t: number): Promise<WrappedCanvas | null> {
    const run = (async () => {
      const covers = (w: WrappedCanvas | null) => !!w && t >= w.timestamp - 1e-4 && t < w.timestamp + Math.max(w.duration, 1e-3) - 1e-4;
      if (covers(this.cur)) return this.cur;
      const ahead = this.cur && t > this.cur.timestamp && t - this.cur.timestamp < 2;
      if (!this.iter || !ahead) {
        await this.iter?.return();
        this.iter = this.sink.canvases(Math.max(0, t));
        this.next = null;
        const first = await this.iter.next();
        this.cur = first.done ? this.cur : first.value;
        if (first.done) return this.cur;
      }
      // Advance until the frame covering t.
      for (;;) {
        if (!this.next) {
          const r = await this.iter.next();
          if (r.done) break;
          this.next = r.value;
        }
        if (this.next.timestamp <= t + 1e-4) {
          this.cur = this.next;
          this.next = null;
        } else break;
      }
      return this.cur;
    })();
    this.busy = run;
    return run.finally(() => {
      if (this.busy === run) this.busy = null;
    });
  }

  async dispose(): Promise<void> {
    await this.iter?.return().catch(() => {});
    this.iter = null;
  }
}

interface Entry {
  input: Input;
  video: InputVideoTrack | null;
  audio: InputAudioTrack | null;
}

export class MediaPool {
  private entries = new Map<string, Promise<Entry>>();
  private framers = new Map<string, VideoFramer>();
  private images = new Map<string, Promise<ImageBitmap>>();
  private imagesReady = new Map<string, ImageBitmap>();
  private audio = new Map<string, Promise<AudioBuffer | null>>();
  private blobs = new Map<string, Promise<Blob>>();

  blob(a: Asset): Promise<Blob> {
    let b = this.blobs.get(a.id);
    if (!b) {
      b = getBlob(a.ref);
      this.blobs.set(a.id, b);
      b.catch(() => this.blobs.delete(a.id));
    }
    return b;
  }

  private entry(a: Asset): Promise<Entry> {
    let e = this.entries.get(a.id);
    if (!e) {
      e = (async () => {
        const input = new Input({ formats: ALL_FORMATS, source: new BlobSource(await this.blob(a)) });
        return { input, video: await input.getPrimaryVideoTrack(), audio: await input.getPrimaryAudioTrack() };
      })();
      this.entries.set(a.id, e);
      e.catch(() => this.entries.delete(a.id));
    }
    return e;
  }

  /** A framer per (asset, purpose). Preview framers decode at reduced size for speed. */
  async framer(a: Asset, purpose: string, maxDim?: number): Promise<VideoFramer | null> {
    const key = `${a.id}|${purpose}`;
    const have = this.framers.get(key);
    if (have) return have;
    const e = await this.entry(a);
    if (!e.video) return null;
    let w: number | undefined;
    let h: number | undefined;
    if (maxDim && Math.max(e.video.displayWidth, e.video.displayHeight) > maxDim) {
      const k = maxDim / Math.max(e.video.displayWidth, e.video.displayHeight);
      w = Math.max(2, Math.round((e.video.displayWidth * k) / 2) * 2);
      h = Math.max(2, Math.round((e.video.displayHeight * k) / 2) * 2);
    }
    const f = new VideoFramer(e.video, w, h);
    this.framers.set(key, f);
    return f;
  }

  async dropFramers(purpose: string): Promise<void> {
    for (const [k, f] of this.framers) {
      if (!k.endsWith(`|${purpose}`)) continue;
      await f.dispose();
      this.framers.delete(k);
    }
  }

  image(a: Asset): Promise<ImageBitmap> {
    let p = this.images.get(a.id);
    if (!p) {
      p = this.blob(a).then((b) => createImageBitmap(b));
      this.images.set(a.id, p);
      p.then((bmp) => this.imagesReady.set(a.id, bmp)).catch(() => this.images.delete(a.id));
    }
    return p;
  }

  imageNow(id: string): ImageBitmap | null {
    return this.imagesReady.get(id) ?? null;
  }

  /** Whole audio track decoded to one AudioBuffer (for preview playback and the export mix). */
  audioBuffer(a: Asset, ctx: BaseAudioContext): Promise<AudioBuffer | null> {
    let p = this.audio.get(a.id);
    if (!p) {
      p = (async () => {
        const e = await this.entry(a);
        if (!e.audio) return null;
        const duration = a.duration || (await e.input.computeDuration());
        const rate = e.audio.sampleRate;
        const chans = Math.min(2, e.audio.numberOfChannels);
        const out = ctx.createBuffer(chans, Math.max(1, Math.ceil(duration * rate)), rate);
        const sink = new AudioBufferSink(e.audio);
        for await (const { buffer, timestamp } of sink.buffers()) {
          const off = Math.round(timestamp * rate);
          for (let c = 0; c < chans; c++) {
            const src = buffer.getChannelData(Math.min(c, buffer.numberOfChannels - 1));
            const room = out.length - off;
            if (room <= 0 || off < 0) continue;
            out.copyToChannel(src.length > room ? src.subarray(0, room) : src, c, off);
          }
        }
        return out;
      })();
      this.audio.set(a.id, p);
      p.catch(() => this.audio.delete(a.id));
    }
    return p;
  }

  async forget(id: string): Promise<void> {
    for (const [k, f] of this.framers) if (k.startsWith(`${id}|`)) {
      await f.dispose();
      this.framers.delete(k);
    }
    const e = this.entries.get(id);
    this.entries.delete(id);
    this.images.delete(id);
    this.imagesReady.get(id)?.close();
    this.imagesReady.delete(id);
    this.audio.delete(id);
    this.blobs.delete(id);
    if (e) (await e.catch(() => null))?.input.dispose();
  }
}

/** Download a file from a link (agents use this; the person's gallery is never read). */
export async function fetchToBlob(url: string, maxBytes = 2_000_000_000): Promise<{ blob: Blob; name: string }> {
  if (!/^https:\/\//i.test(url)) throw new Error("Only https links can be imported.");
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Download failed (${res.status}).`);
  const len = Number(res.headers.get("content-length") ?? 0);
  if (len > maxBytes) throw new Error("File is too large.");
  const blob = await res.blob();
  const fromUrl = decodeURIComponent(new URL(url).pathname.split("/").pop() || "download");
  return { blob, name: fromUrl };
}
