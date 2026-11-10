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
  if (["glb", "gltf"].includes(ext) || m === "model/gltf-binary" || m === "model/gltf+json") return "model";
  return null;
}

/** Copy a blob into private storage and describe it. */
export async function importBlob(p: Project, blob: Blob, name: string, createdBy: Asset["createdBy"] = "user"): Promise<Asset> {
  const kind = kindFor(blob.type, name);
  if (!kind) throw new Error(`"${name}" is not a video, image, audio or 3D model (.glb/.gltf) file.`);
  const id = uid("ast");
  const info = await probe(blob, kind);
  const ref = await putBlob(id, blob);
  const asset: Asset = { id, kind, name, mime: blob.type || "application/octet-stream", size: blob.size, ref, createdBy, ...info };
  p.assets.push(asset);
  return asset;
}

async function probe(blob: Blob, kind: AssetKind): Promise<{ duration: number; width: number; height: number; peaks?: number[] }> {
  if (kind === "model") return { duration: 0, width: 0, height: 0 };
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

/** Loudness envelope (0..1) of an asset's speech for caption timing: VolTune lip data, or decoded audio. */
export async function speechEnvelope(a: Asset): Promise<{ env: number[]; fps: number }> {
  if (a.mouth?.length) return { env: a.mouth, fps: 30 };
  const { getBlob } = await import("../core/storage");
  const input = new Input({ formats: ALL_FORMATS, source: new BlobSource(await getBlob(a.ref)) });
  try {
    const tr = await input.getPrimaryAudioTrack();
    if (!tr) throw new Error(`"${a.name}" has no audio.`);
    const duration = a.duration || (await input.computeDuration());
    const fps = Math.max(1, Math.min(50, Math.floor(6000 / Math.max(1, duration))));
    const peaks = await computePeaks(tr, duration);
    const sorted = [...peaks].sort((x, y) => x - y);
    const top = sorted[Math.floor(sorted.length * 0.95)] || 1;
    // Smooth over ~60 ms so syllables merge into words.
    const env = peaks.map((_, i) => {
      let m = 0;
      for (let k = -1; k <= 1; k++) m = Math.max(m, peaks[i + k] ?? 0);
      return Math.min(1, m / top);
    });
    return { env, fps };
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

  get isBusy(): boolean {
    return !!this.busy;
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

  /** Last time this framer was asked for a frame (the pool closes idle helper decoders). */
  lastUse = performance.now();

  /** After a decoder failure, wait before opening another one (never hammer the phone's media service). */
  private failedAt = 0;

  private decode(t: number): Promise<WrappedCanvas | null> {
    this.lastUse = performance.now();
    if (!this.iter && this.failedAt && performance.now() - this.failedAt < 3000) return Promise.resolve(this.cur);
    const run = (async () => {
      try {
        return await this.step(t);
      } catch (e) {
        this.failedAt = performance.now();
        // A failed or reclaimed hardware decoder (phones allow only a few at once) must not leave this reader
        // broken forever: drop the iterator and try once more from a fresh decoder.
        console.warn("frame decode failed, restarting decoder", e);
        await this.resetIter();
        return this.cur;
      }
    })();
    this.busy = run;
    return run.finally(() => {
      if (this.busy === run) this.busy = null;
    });
  }

  private async resetIter(): Promise<void> {
    const it = this.iter;
    this.iter = null;
    this.next = null;
    await it?.return().catch(() => {});
  }

  private async step(t: number): Promise<WrappedCanvas | null> {
    {
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
    }
  }

  async dispose(): Promise<void> {
    await this.iter?.return().catch(() => {});
    this.iter = null;
  }
}

/** What the renderer needs from any frame reader (the in-window decoder or the desktop's native one). */
export interface FramerLike {
  readonly isBusy: boolean;
  readonly latest: { canvas: HTMLCanvasElement | OffscreenCanvas; timestamp: number; duration: number } | null;
  lastUse: number;
  frameAt(t: number): Promise<{ canvas: HTMLCanvasElement | OffscreenCanvas; timestamp: number; duration: number } | null>;
  dispose(): Promise<void>;
}

/** Desktop hook: decode pictures and sound outside the window (native ffmpeg). Unset on phones. */
export interface NativeMedia {
  /** A native frame reader for this asset, or null to use the in-window decoder. */
  framer(a: Asset, purpose: string, maxDim?: number): Promise<FramerLike | null>;
  /** The asset's whole sound; undefined = not handled here (use the in-window decoder). */
  audio(a: Asset, ctx: BaseAudioContext): Promise<AudioBuffer | null | undefined>;
}

interface Entry {
  input: Input;
  video: InputVideoTrack | null;
  audio: InputAudioTrack | null;
}

/** Most native decoders one video can have running for one purpose (clips showing it at the same time). */
const MAX_LANES = 6;

export class MediaPool {
  /** Set once by the desktop app at start. */
  static native: NativeMedia | null = null;
  private entries = new Map<string, Promise<Entry>>();
  private framers = new Map<string, VideoFramer>();
  private nativePending = new Map<string, Promise<VideoFramer | null>>();
  /** Who is using each native decoder lane, and in which frame it was last asked for. */
  private laneUser = new Map<string, { user: string; tick: number }>();
  private laneSeq = 0;
  private ticks = new Map<string, number>();
  private onScreen = new Map<string, Set<string>>();

  /**
   * Called once before the layers of a frame are gathered, with the clips that are on screen in it, so decoder lanes
   * know which clips need a decoder at the same moment (and which lanes have just become free).
   */
  beginFrame(purpose: string, clipIds: string[] = []): void {
    this.ticks.set(purpose, (this.ticks.get(purpose) ?? 0) + 1);
    this.onScreen.set(purpose, new Set(clipIds));
  }
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

  /** The proxy's video track (light 720p edit copy) when the asset has one. */
  private proxyEntry(a: Asset): Promise<Entry> | null {
    const px = a.proxy;
    if (!px) return null;
    const key = `${a.id}|proxy:${px.ref}`;
    let e = this.entries.get(key);
    if (!e) {
      e = (async () => {
        const input = new Input({ formats: ALL_FORMATS, source: new BlobSource(await getBlob(px.ref)) });
        return { input, video: await input.getPrimaryVideoTrack(), audio: null };
      })();
      this.entries.set(key, e);
      e.catch(() => this.entries.delete(key));
    }
    return e;
  }

  private reaper: ReturnType<typeof setInterval> | null = null;
  /**
   * Phones have only a few hardware video decoders. Helper readers (matte, scenes, thumbnails, selection...) are
   * closed after a few idle seconds so the preview and the export always get one.
   */
  private reapIdle(): void {
    const now = performance.now();
    for (const [k, f] of this.framers) {
      if (/\|(preview|export)(\|px)?$/.test(k)) continue;
      if (now - f.lastUse > 4000 && !f.isBusy) {
        this.framers.delete(k);
        this.laneUser.delete(k);
        void f.dispose();
      }
    }
    if (![...this.framers.keys()].some((k) => !/\|(preview|export)(\|px)?$/.test(k)) && this.reaper) {
      clearInterval(this.reaper);
      this.reaper = null;
    }
  }

  /**
   * A framer per (asset, purpose). Preview framers decode at reduced size for speed.
   * `user` names who is asking (the clip). With the desktop's native reader every clip that shows the same video at
   * the same moment — split screens, picture-in-picture, a blurred copy behind — gets its own decoder ("lane"), so
   * each one plays forward smoothly instead of all of them fighting over one decoder and re-seeking for every frame.
   */
  async framer(a: Asset, purpose: string, maxDim?: number, user = "", at?: number): Promise<VideoFramer | null> {
    if (MediaPool.native && a.kind === "video") {
      const head = `${a.id}|native:`;
      const tail = `|${purpose}`;
      const tick = this.ticks.get(purpose) ?? 0;
      const lanes = [...this.framers.entries()].filter(([k]) => k.startsWith(head) && k.endsWith(tail));
      const mine = lanes.find(([k]) => this.laneUser.get(k)?.user === user);
      if (mine) {
        this.laneUser.get(mine[0])!.tick = tick;
        return mine[1];
      }
      // A lane is free as soon as its clip is no longer on screen (a cut to the next clip takes it over at once).
      // Among free lanes, one already standing just before the wanted moment plays straight on without a seek.
      const active = this.onScreen.get(purpose);
      const ahead = (f: VideoFramer) => {
        const ts = f.latest?.timestamp;
        return at !== undefined && ts !== undefined && at >= ts - 1e-3 && at - ts < 2 ? at - ts : Infinity;
      };
      const free = lanes
        .filter(([k]) => {
          const u = this.laneUser.get(k);
          return !u || (active ? !active.has(u.user) && u.tick < tick : u.tick < tick - 1);
        })
        .sort((x, y) => ahead(x[1]) - ahead(y[1]) || (this.laneUser.get(x[0])?.tick ?? 0) - (this.laneUser.get(y[0])?.tick ?? 0));
      if (free.length) {
        // Spare lanes beyond the one being taken are let go so their pictures do not pile up in memory.
        for (const [k, f] of free.slice(1)) {
          if (f.isBusy || performance.now() - f.lastUse < 20_000) continue;
          this.framers.delete(k);
          this.laneUser.delete(k);
          void f.dispose();
        }
        const [k, f] = free[0]!;
        this.laneUser.set(k, { user, tick });
        return f;
      }
      if (lanes.length >= MAX_LANES) {
        // More simultaneous copies of one video than lanes: share the least recently used one (as before lanes existed).
        const [k, f] = lanes.sort((x, y) => (this.laneUser.get(x[0])?.tick ?? 0) - (this.laneUser.get(y[0])?.tick ?? 0))[0]!;
        this.laneUser.set(k, { user, tick });
        return f;
      }
      const pkey = `${head}${purpose}#${user}`;
      const pending = this.nativePending.get(pkey);
      if (pending) return pending;
      const made = (async () => {
        const nf = await MediaPool.native!.framer(a, purpose, maxDim).catch((e) => {
          console.warn("native frame reader unavailable, using the in-window decoder", e);
          return null;
        });
        if (!nf) return null;
        const f = nf as unknown as VideoFramer;
        const nkey = `${head}${this.laneSeq++}${tail}`;
        this.framers.set(nkey, f);
        this.laneUser.set(nkey, { user, tick });
        if (purpose !== "preview" && purpose !== "export" && !this.reaper) this.reaper = setInterval(() => this.reapIdle(), 2000);
        return f;
      })();
      this.nativePending.set(pkey, made);
      const got = await made.finally(() => this.nativePending.delete(pkey));
      if (got) return got;
    }
    // Everything but the export reads the light proxy when there is one.
    const usesProxy = purpose !== "export" && !!a.proxy;
    const key = `${a.id}|${purpose}${usesProxy ? "|px" : ""}`;
    const have = this.framers.get(key);
    if (have) return have;
    if (usesProxy) {
      // The original-resolution reader for this purpose is no longer needed.
      const old = this.framers.get(`${a.id}|${purpose}`);
      if (old) {
        this.framers.delete(`${a.id}|${purpose}`);
        void old.dispose();
      }
    }
    const e = usesProxy ? ((await this.proxyEntry(a)!.catch(() => null)) ?? (await this.entry(a))) : await this.entry(a);
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
    if (purpose !== "preview" && purpose !== "export" && !this.reaper) this.reaper = setInterval(() => this.reapIdle(), 2000);
    return f;
  }

  async dropFramers(purpose: string): Promise<void> {
    for (const [k, f] of this.framers) {
      if (!k.endsWith(`|${purpose}`) && !k.endsWith(`|${purpose}|px`)) continue;
      await f.dispose();
      this.framers.delete(k);
      this.laneUser.delete(k);
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
        if (MediaPool.native) {
          const nat = await MediaPool.native.audio(a, ctx).catch((e) => {
            console.warn("native sound reader unavailable, using the in-window decoder", e);
            return undefined;
          });
          if (nat !== undefined) return nat;
        }
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
