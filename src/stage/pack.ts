/**
 * VSPK asset packs: one JSON header plus named typed arrays, read without copying (see tools/stage/packlib.py).
 * The Stage's bodies (human.pack) and motion library (motion/*.pack) ship inside the app in this format.
 */
export type PackArray = Float32Array | Uint16Array | Uint32Array | Int16Array | Uint8Array | Int8Array | Int32Array;
type Ctor = { new (buf: ArrayBufferLike, off: number, n: number): PackArray; BYTES_PER_ELEMENT: number };
const TYPES: Record<string, Ctor> = { f32: Float32Array, u16: Uint16Array, u32: Uint32Array, i16: Int16Array, u8: Uint8Array, i8: Int8Array, i32: Int32Array };

export class Pack<M = Record<string, unknown>> {
  readonly meta: M;
  private readonly index: Record<string, [number, number, string]>;
  private readonly base: number;
  constructor(readonly buf: ArrayBuffer) {
    const dv = new DataView(buf);
    if (buf.byteLength < 16 || dv.getUint32(0, true) !== 0x4b505356) throw new Error("Not a VideoStudio asset pack.");
    const headLen = dv.getUint32(8, true);
    const head = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, 16, headLen))) as { meta: M; arrays: Record<string, [number, number, string]> };
    this.meta = head.meta;
    this.index = head.arrays;
    this.base = 16 + headLen;
  }
  has(name: string): boolean {
    return name in this.index;
  }
  names(): string[] {
    return Object.keys(this.index);
  }
  private view(name: string): PackArray {
    const e = this.index[name];
    if (!e) throw new Error(`Asset pack has no "${name}".`);
    const C = TYPES[e[2]];
    if (!C) throw new Error(`Asset pack: unknown type ${e[2]}.`);
    return new C(this.buf, this.base + e[0], e[1]);
  }
  f32(name: string): Float32Array {
    return this.view(name) as Float32Array;
  }
  u16(name: string): Uint16Array {
    return this.view(name) as Uint16Array;
  }
  u32(name: string): Uint32Array {
    return this.view(name) as Uint32Array;
  }
  i16(name: string): Int16Array {
    return this.view(name) as Int16Array;
  }
  u8(name: string): Uint8Array {
    return this.view(name) as Uint8Array;
  }
}

/* ------------------------------ where packs live ------------------------------ */

/** Host hook: return the bytes of a bundled Stage asset ("human.pack", "motion/index.json", "motion/135.pack"…). */
export type AssetReader = (rel: string) => Promise<ArrayBuffer>;

let reader: AssetReader = async (rel) => {
  const base = (globalThis as { VS_STAGE_ASSETS?: string }).VS_STAGE_ASSETS ?? "./stage-assets/";
  const r = await fetch(base + rel);
  if (!r.ok) throw new Error(`Stage asset "${rel}" is missing (${r.status}). Reinstall VideoStudio or rebuild the asset packs.`);
  return r.arrayBuffer();
};
export function setAssetReader(r: AssetReader): void {
  reader = r;
  cache.clear();
}

const cache = new Map<string, Promise<ArrayBuffer>>();
/** Load (once) a bundled asset. A failed load is forgotten so the next call tries again. */
export function loadAsset(rel: string): Promise<ArrayBuffer> {
  let p = cache.get(rel);
  if (!p) {
    p = reader(rel);
    cache.set(rel, p);
    p.catch(() => cache.delete(rel));
  }
  return p;
}
export async function loadPack<M>(rel: string): Promise<Pack<M>> {
  return new Pack<M>(await loadAsset(rel));
}
export async function loadJson<T>(rel: string): Promise<T> {
  return JSON.parse(new TextDecoder().decode(new Uint8Array(await loadAsset(rel)))) as T;
}
