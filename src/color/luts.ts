/**
 * The LUTs the renderer can reach right now, by id. Built-in LUTs ("builtin:<id>") are generated on first use;
 * a project's own LUTs are decoded once when the project is drawn. The compositor looks tables up here and
 * re-uploads a GPU texture only when an entry's version changes.
 */
import type { Project } from "../core/model";
import { builtinLut, projectToLut } from "./lut";
import type { Lut3D } from "./types";

interface Entry {
  lut: Lut3D;
  ver: number;
}
const reg = new Map<string, Entry>();
const failed = new Set<string>();
let counter = 1;

/** Add or replace a LUT. */
export function registerLut(id: string, lut: Lut3D): void {
  reg.set(id, { lut, ver: counter++ });
  failed.delete(id);
}

export function forgetLut(id: string): void {
  reg.delete(id);
  failed.delete(id);
}

/** The table for an id, or undefined when it is unknown (the node then simply has no LUT). */
export function lutFor(id: string): Entry | undefined {
  const e = reg.get(id);
  if (e || failed.has(id)) return e;
  if (id.startsWith("builtin:")) {
    const lut = builtinLut(id.slice(8));
    if (lut) {
      registerLut(id, lut);
      return reg.get(id);
    }
    // Only an unknown built-in is remembered as missing; a project LUT may simply not be decoded yet.
    failed.add(id);
  }
  return undefined;
}

/** Plain lookup for the CPU engine. */
export const lutTable = (id: string): Lut3D | null => lutFor(id)?.lut ?? null;

/** Make sure every LUT stored in the project is decoded and reachable. Cheap when nothing is new. */
export function syncProjectLuts(p: Project): void {
  for (const pl of p.luts ?? []) {
    if (reg.has(pl.id) || failed.has(pl.id)) continue;
    try {
      registerLut(pl.id, projectToLut(pl));
    } catch {
      failed.add(pl.id);
    }
  }
}
