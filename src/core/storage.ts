/**
 * App-private storage. Project JSON lives in IndexedDB; media bytes live in the origin-private file system
 * (OPFS), falling back to IndexedDB. Nothing here can see the phone's gallery.
 */
import type { Project } from "./model";

const DB = "videostudio";
const VERSION = 1;

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains("projects")) db.createObjectStore("projects", { keyPath: "id" });
      if (!db.objectStoreNames.contains("blobs")) db.createObjectStore("blobs");
      if (!db.objectStoreNames.contains("kv")) db.createObjectStore("kv");
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

let dbp: Promise<IDBDatabase> | null = null;
function db(): Promise<IDBDatabase> {
  return (dbp ??= openDb());
}

function tx<T>(store: string, mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return db().then(
    (d) =>
      new Promise<T>((resolve, reject) => {
        const t = d.transaction(store, mode);
        const r = fn(t.objectStore(store));
        r.onsuccess = () => resolve(r.result);
        r.onerror = () => reject(r.error);
      }),
  );
}

export async function saveProject(p: Project): Promise<void> {
  p.updatedAt = Date.now();
  await tx("projects", "readwrite", (s) => s.put(JSON.parse(JSON.stringify(p))));
}

export async function loadProject(id: string): Promise<Project | undefined> {
  return (await tx<Project | undefined>("projects", "readonly", (s) => s.get(id))) ?? undefined;
}

export async function listProjects(): Promise<Pick<Project, "id" | "name" | "updatedAt" | "width" | "height">[]> {
  const all = await tx<Project[]>("projects", "readonly", (s) => s.getAll());
  return all.map((p) => ({ id: p.id, name: p.name, updatedAt: p.updatedAt, width: p.width, height: p.height })).sort((a, b) => b.updatedAt - a.updatedAt);
}

export async function deleteProject(id: string): Promise<void> {
  const p = await loadProject(id);
  if (p) for (const a of p.assets) await deleteBlob(a.ref).catch(() => {});
  await tx("projects", "readwrite", (s) => s.delete(id));
}

export async function kvGet<T>(key: string): Promise<T | undefined> {
  return (await tx<T | undefined>("kv", "readonly", (s) => s.get(key))) ?? undefined;
}

export async function kvSet(key: string, value: unknown): Promise<void> {
  await tx("kv", "readwrite", (s) => s.put(value, key));
}

/* ---------------------------------- blobs --------------------------------- */

async function opfsDir(): Promise<FileSystemDirectoryHandle | null> {
  try {
    const root = await navigator.storage?.getDirectory?.();
    if (!root) return null;
    return await root.getDirectoryHandle("media", { create: true });
  } catch {
    return null;
  }
}

/** Store bytes and return a ref ("opfs:<id>" or "idb:<id>"). Streams large files without loading them whole. */
export async function putBlob(id: string, data: Blob): Promise<string> {
  const dir = await opfsDir();
  if (dir) {
    try {
      const fh = await dir.getFileHandle(id, { create: true });
      const w = await fh.createWritable();
      await data.stream().pipeTo(w);
      return `opfs:${id}`;
    } catch {
      /* fall back */
    }
  }
  await tx("blobs", "readwrite", (s) => s.put(data, id));
  return `idb:${id}`;
}

export async function getBlob(ref: string): Promise<Blob> {
  const [kind, id] = ref.split(":") as [string, string];
  if (kind === "opfs") {
    const dir = await opfsDir();
    if (!dir) throw new Error("Private storage is unavailable.");
    const fh = await dir.getFileHandle(id);
    return await fh.getFile();
  }
  const b = await tx<Blob | undefined>("blobs", "readonly", (s) => s.get(id));
  if (!b) throw new Error(`Media ${id} is missing from storage.`);
  return b;
}

export async function deleteBlob(ref: string): Promise<void> {
  const [kind, id] = ref.split(":") as [string, string];
  if (kind === "opfs") {
    const dir = await opfsDir();
    await dir?.removeEntry(id).catch(() => {});
  } else await tx("blobs", "readwrite", (s) => s.delete(id));
}

export async function requestPersistence(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false;
  } catch {
    return false;
  }
}

export async function storageEstimate(): Promise<{ used: number; quota: number }> {
  try {
    const e = await navigator.storage.estimate();
    return { used: e.usage ?? 0, quota: e.quota ?? 0 };
  } catch {
    return { used: 0, quota: 0 };
  }
}
