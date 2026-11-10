/**
 * Bringing files from the PC into a project. Anything Chromium cannot decode (phone HEVC/HDR, ProRes, DNxHD,
 * MPEG-2, rotated or 10-bit video, AC-3 sound…) is first converted on this PC to a visually lossless H.264 / FLAC
 * edit copy, so every file imports and plays smoothly. The original stays untouched.
 */
import type { Studio } from "../../src/app/studio";
import { uid, type Asset } from "../../src/core/model";
import { addClip } from "../../src/core/ops";
import { fileRefPath, isFileRef, kvGet, kvSet } from "../../src/core/storage";
import { guessMime, need, pathBlob, vsd } from "./bridge";
import { describe, forgetPathCache } from "./native-media";

let paths: Record<string, string> | null = null;
async function pathMap(): Promise<Record<string, string>> {
  return (paths ??= (await kvGet<Record<string, string>>("assetPaths").catch(() => undefined)) ?? {});
}

/** Where an asset's media lives on disk (its source file, or a work copy written now). */
export async function assetPath(s: Studio, a: Asset): Promise<string> {
  if (isFileRef(a.ref)) return fileRefPath(a.ref);
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
  forgetPathCache();
}

/**
 * VideoStudio 2 import: a video or sound file is registered where it is — no copy into app storage, no
 * conversion — so it is on the timeline at once whatever its size or codec. The native media core reads it
 * directly (and prepares a light editing copy in the background when the file is heavy).
 */
async function importInPlace(s: Studio, p: string, add: boolean): Promise<Asset> {
  const d = await describe(p);
  if (!d.video && !d.audio) throw new Error(`${d.name} has no picture or sound that can be read.`);
  const asset: Asset = {
    id: uid("ast"),
    kind: d.video ? "video" : "audio",
    name: d.name,
    mime: guessMime(d.name),
    size: d.size,
    duration: d.duration,
    width: d.width,
    height: d.height,
    ref: `file:${d.path}`,
    createdBy: "user",
  };
  s.mutate(`import ${d.name}`, (pr) => {
    pr.assets.push(asset);
    if (add) addClip(pr, { assetId: asset.id });
  });
  await remember(asset.id, d.path);
  s.setStatus(`Imported ${d.name}`);
  if (d.proxy) void vsd!.mediaEnsureProxy(d.path).catch(() => {});
  // Sound and waveform load in the background so the first play starts at once.
  if (d.audio) {
    const ctx = new OfflineAudioContext(2, 1, 48000);
    void s.pool.audioBuffer(asset, ctx).catch(() => {});
  }
  return asset;
}

export async function importPath(s: Studio, p: string, add = false, convert = true): Promise<{ asset: Asset; converted?: string }> {
  if (vsd?.mediaDescribe) {
    const st = await vsd.stat(p);
    if (st.kind === "video" || st.kind === "audio") return { asset: await importInPlace(s, st.path || p, add) };
  }
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

/**
 * Media whose stored copy is gone (storage wiped, imported while storage was unavailable) is found again on
 * disk — the remembered source path first, then a search by file name in the usual folders — stored again
 * and re-linked in place, so clips, keys and timing stay exactly as edited.
 */
export async function relinkMissing(s: Studio, only?: string[]): Promise<{ relinked: { id: string; name: string; path: string }[]; missing: { id: string; name: string }[]; ok: number }> {
  const { getBlob, putBlob } = await import("../../src/core/storage");
  const m = await pathMap();
  const relinked: { id: string; name: string; path: string }[] = [];
  const missing: { id: string; name: string }[] = [];
  let ok = 0;
  const info = vsd ? await vsd.info() : null;
  for (const a of s.project.assets) {
    if (only && !only.includes(a.id)) continue;
    if (!a.ref) continue;
    const fine = isFileRef(a.ref)
      ? !!vsd && (await vsd.stat(fileRefPath(a.ref)).then((st) => st.size > 0).catch(() => false))
      : await getBlob(a.ref).then((b) => b.size > 0).catch(() => false);
    if (fine) {
      ok++;
      continue;
    }
    let found: string | undefined;
    const known = m[a.id];
    if (known && vsd) found = await vsd.stat(known).then(() => known).catch(() => undefined);
    if (!found && vsd && info) {
      const base = a.name.replace(/\.[^.]+$/, "");
      for (const root of [info.dirs.videos, info.dirs.music, info.dirs.downloads, info.dirs.desktop, info.dirs.documents, info.dirs.home]) {
        const r = await vsd.find(root, base, { limit: 20, depth: root === info.dirs.home ? 3 : 5 }).catch(() => null);
        const hit = r?.results.find((e) => e.name === a.name) ?? r?.results.find((e) => e.name.startsWith(base) && !e.name.endsWith(".part"));
        if (hit) {
          found = hit.path;
          break;
        }
      }
    }
    if (!found) {
      missing.push({ id: a.id, name: a.name });
      continue;
    }
    s.setStatus(`Re-linking ${a.name}…`);
    const inPlace = !!vsd?.mediaDescribe && (a.kind === "video" || a.kind === "audio");
    const ref = inPlace ? `file:${found}` : await putBlob(a.id, (await pathBlob(found)).blob);
    await s.pool.forget(a.id);
    s.mutate("relink media", (p) => {
      const pa = p.assets.find((x) => x.id === a.id);
      if (pa) {
        pa.ref = ref;
        delete pa.proxy;
      }
    });
    await remember(a.id, found);
    relinked.push({ id: a.id, name: a.name, path: found });
  }
  s.setStatus("");
  return { relinked, missing, ok };
}
