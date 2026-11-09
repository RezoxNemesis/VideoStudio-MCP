/**
 * Bringing files from the PC into a project. Anything Chromium cannot decode (phone HEVC/HDR, ProRes, DNxHD,
 * MPEG-2, rotated or 10-bit video, AC-3 sound…) is first converted on this PC to a visually lossless H.264 / FLAC
 * edit copy, so every file imports and plays smoothly. The original stays untouched.
 */
import type { Studio } from "../../src/app/studio";
import type { Asset } from "../../src/core/model";
import { kvGet, kvSet } from "../../src/core/storage";
import { need, pathBlob, vsd } from "./bridge";

let paths: Record<string, string> | null = null;
async function pathMap(): Promise<Record<string, string>> {
  return (paths ??= (await kvGet<Record<string, string>>("assetPaths").catch(() => undefined)) ?? {});
}

/** Where an asset's media lives on disk (its source file, or a work copy written now). */
export async function assetPath(s: Studio, a: Asset): Promise<string> {
  const m = await pathMap();
  const known = m[a.id];
  if (known && vsd) {
    try {
      await vsd.stat(known);
      return known;
    } catch {
      /* moved or deleted: fall back to a work copy */
    }
  }
  const blob = await s.pool.blob(a);
  const p = await need().workWrite(a.name, new Uint8Array(await blob.arrayBuffer()));
  m[a.id] = p;
  await kvSet("assetPaths", m);
  return p;
}

async function remember(id: string, p: string) {
  const m = await pathMap();
  m[id] = p;
  await kvSet("assetPaths", m);
}

export async function importPath(s: Studio, p: string, add = false, convert = true): Promise<{ asset: Asset; converted?: string }> {
  let src = p;
  let converted: string | undefined;
  if (convert && vsd) {
    const st = await vsd.stat(p);
    if (st.kind === "video" || st.kind === "audio") {
      if (await vsd.needsConversion(p).catch(() => false)) {
        s.setStatus(`Making an edit copy of ${p.split("/").pop()}…`);
        const r = await vsd.process("convert", p);
        src = r.path;
        converted = r.note;
      }
    }
  }
  const { blob, name } = await pathBlob(src);
  const nice = converted ? p.split("/").pop()! : name;
  const [asset] = await s.importFiles([new File([blob], nice, { type: blob.type })], add);
  if (!asset) throw new Error(`Could not import ${nice}.`);
  await remember(asset.id, p === src ? p : src);
  s.setStatus("");
  return { asset, converted };
}

export async function importBlobWithPath(s: Studio, blob: Blob, name: string, p: string, add = false): Promise<Asset> {
  const [asset] = await s.importFiles([new File([blob], name, { type: blob.type })], add);
  if (!asset) throw new Error(`Could not import ${name}.`);
  await remember(asset.id, p);
  return asset;
}
