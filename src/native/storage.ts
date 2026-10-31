/**
 * Writes finished renders into the phone's shared storage (Movies/VideoStudio, Music/VideoStudio) through
 * MediaStore. Write-only: the app holds no permission to read the gallery.
 */
import { Capacitor, registerPlugin } from "@capacitor/core";

interface StudioStoragePlugin {
  open(o: { name: string }): Promise<{ id: string }>;
  write(o: { id: string; position: number; data: string }): Promise<void>;
  finish(o: { id: string; name: string; mime: string; collection: "Movies" | "Music" | "Pictures" | "Documents" }): Promise<{ uri: string; path: string }>;
  abort(o: { id: string }): Promise<void>;
  keepAwake(o: { on: boolean }): Promise<void>;
}

const Native = registerPlugin<StudioStoragePlugin>("StudioStorage");

export const hasNativeStorage = (): boolean => Capacitor.getPlatform() === "android" && Capacitor.isPluginAvailable("StudioStorage");

function b64(bytes: Uint8Array): string {
  let s = "";
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) s += String.fromCharCode(...bytes.subarray(i, i + step));
  return btoa(s);
}

/** A random-access file that ends up in shared storage (or a browser download on the web). */
export class OutputFile {
  private id: string | null = null;
  private chunks: { position: number; data: Uint8Array }[] = [];
  private pending: Promise<void> = Promise.resolve();
  private buffered: { position: number; parts: Uint8Array[]; size: number } | null = null;
  bytes = 0;

  constructor(
    readonly name: string,
    readonly mime: string,
    readonly collection: "Movies" | "Music" | "Pictures" | "Documents",
  ) {}

  async open(): Promise<void> {
    if (hasNativeStorage()) this.id = (await Native.open({ name: this.name })).id;
  }

  /** Writes are coalesced into ~4 MB sequential runs to keep the bridge fast. */
  write(position: number, data: Uint8Array): Promise<void> {
    this.bytes = Math.max(this.bytes, position + data.length);
    if (!this.id) {
      this.chunks.push({ position, data: data.slice() });
      return Promise.resolve();
    }
    const b = this.buffered;
    if (b && b.position + b.size === position && b.size < 4 * 1024 * 1024) {
      b.parts.push(data.slice());
      b.size += data.length;
      return this.pending;
    }
    this.flush();
    this.buffered = { position, parts: [data.slice()], size: data.length };
    return this.pending;
  }

  private flush() {
    const b = this.buffered;
    this.buffered = null;
    if (!b || !this.id) return;
    const joined = new Uint8Array(b.size);
    let o = 0;
    for (const part of b.parts) {
      joined.set(part, o);
      o += part.length;
    }
    const id = this.id;
    this.pending = this.pending.then(() => Native.write({ id, position: b.position, data: b64(joined) }));
  }

  writable(): WritableStream<{ type: "write"; data: Uint8Array; position: number }> {
    return new WritableStream({
      write: (chunk) => this.write(chunk.position, chunk.data),
    });
  }

  async finish(): Promise<{ uri: string; path: string; blob?: Blob }> {
    if (this.id) {
      this.flush();
      await this.pending;
      return await Native.finish({ id: this.id, name: this.name, mime: this.mime, collection: this.collection });
    }
    const out = new Uint8Array(this.bytes);
    for (const c of this.chunks) out.set(c.data, c.position);
    const blob = new Blob([out], { type: this.mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = this.name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    return { uri: url, path: this.name, blob };
  }

  async abort(): Promise<void> {
    if (this.id) await Native.abort({ id: this.id }).catch(() => {});
    this.chunks = [];
  }
}

/** Save a whole blob (WAV, PNG, project JSON) to shared storage. */
export async function saveBlob(blob: Blob, name: string, collection: "Movies" | "Music" | "Pictures" | "Documents"): Promise<{ uri: string; path: string }> {
  const f = new OutputFile(name, blob.type || "application/octet-stream", collection);
  await f.open();
  const bytes = new Uint8Array(await blob.arrayBuffer());
  const step = 4 * 1024 * 1024;
  for (let i = 0; i < bytes.length; i += step) await f.write(i, bytes.subarray(i, i + step));
  return await f.finish();
}

export async function keepAwake(on: boolean): Promise<void> {
  if (hasNativeStorage()) await Native.keepAwake({ on }).catch(() => {});
  else {
    try {
      const nav = navigator as Navigator & { wakeLock?: { request(t: "screen"): Promise<{ release(): Promise<void> }> } };
      if (on) wakeLockHandle = (await nav.wakeLock?.request("screen")) ?? null;
      else {
        await wakeLockHandle?.release();
        wakeLockHandle = null;
      }
    } catch {
      /* not supported */
    }
  }
}
let wakeLockHandle: { release(): Promise<void> } | null = null;
