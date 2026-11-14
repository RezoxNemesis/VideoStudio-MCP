/**
 * Where the Claude link polls. Normally that is what the app has stored (the relay it was linked through, its own
 * key, on or off). A machine without a screen — a render box, a workspace — names a relay of its own in the
 * environment (VSD_LINK_WORKER, optionally VSD_LINK_KEY): the link then uses that relay, switched on, and nothing
 * of it is stored, so the app's own link is untouched the next time it starts without the variables.
 */
export interface LinkTarget {
  worker: string;
  key: string;
  enabled: boolean;
}
export function linkConfigFrom(stored: LinkTarget, env: { worker?: string; key?: string } | undefined | null): { config: LinkTarget; fixed: boolean } {
  const worker = (env?.worker ?? "").trim().replace(/\/+$/, "");
  if (!worker) return { config: stored, fixed: false };
  return { config: { worker, key: (env?.key ?? "").trim() || stored.key || "local", enabled: true }, fixed: true };
}
