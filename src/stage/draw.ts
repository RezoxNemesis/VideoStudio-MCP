/**
 * Stage layers in the 2D scene pipeline: load what a stage needs, keep one runtime per layer, and draw a frame into
 * the layer's canvas. A stage that is still loading (or a renderer that cannot start) draws a quiet placeholder and
 * never throws, so the rest of the edit keeps working.
 */
import type { AnimLayer, AnimScene } from "../core/model";
import { motions } from "./motion/library";
import { filmProblem, getFilm, stageAssets, StageRuntime, type StageView } from "./runtime";
import { clipsOf, type StageSpec } from "./spec";
import { ensureModels, modelSrcs, modelsLoaded } from "./models";

const runtimes = new Map<string, StageRuntime>();
const order: string[] = [];
const MAX_RUNTIMES = 4;

/** The runtime of a stage layer (made on first use; the least recently used one is dropped beyond a few). */
export function runtimeOf(layerId: string): StageRuntime | null {
  const film = getFilm();
  if (!film) return null;
  let rt = runtimes.get(layerId);
  if (!rt) {
    rt = new StageRuntime(film);
    runtimes.set(layerId, rt);
  }
  const i = order.indexOf(layerId);
  if (i >= 0) order.splice(i, 1);
  order.push(layerId);
  while (order.length > MAX_RUNTIMES) {
    const old = order.shift()!;
    runtimes.get(old)?.dispose();
    runtimes.delete(old);
  }
  return rt;
}
export function dropRuntime(layerId: string): void {
  runtimes.get(layerId)?.dispose();
  runtimes.delete(layerId);
  const i = order.indexOf(layerId);
  if (i >= 0) order.splice(i, 1);
}

/** True when everything the stage needs is in memory. */
export function stageReady(spec: StageSpec): boolean {
  return stageAssets.ready && motions.isReady(clipsOf(spec)) && modelsLoaded(modelSrcs(spec.env));
}
/** Load bodies, the motion index and the motion packs this stage uses. */
export async function loadStage(spec: StageSpec): Promise<void> {
  await stageAssets.load();
  await motions.ensure(clipsOf(spec));
  await ensureModels(modelSrcs(spec.env));
}
/** Load every stage layer of a scene (call before export or a snapshot). */
export async function preloadStage(s: AnimScene): Promise<void> {
  for (const l of s.layers) if (l.kind === "stage" && l.stage) await loadStage(l.stage);
}

const loading = new Set<string>();
let lastError = "";
export function stageError(): string {
  return lastError || filmProblem() || "";
}

function note(c: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, W: number, H: number, text: string, bad = false): void {
  c.save();
  c.setTransform(1, 0, 0, 1, 0, 0);
  c.fillStyle = bad ? "#1a0c10" : "#0c1016";
  c.fillRect(0, 0, W, H);
  c.fillStyle = bad ? "#ff8a8a" : "#8a94a6";
  c.font = `${Math.max(12, Math.round(H / 34))}px system-ui, sans-serif`;
  c.textAlign = "center";
  c.textBaseline = "middle";
  const lines = text.split("\n");
  lines.forEach((ln, i) => c.fillText(ln, W / 2, H / 2 + (i - (lines.length - 1) / 2) * Math.max(16, H / 26)));
  c.restore();
}

/** Draw a stage layer for a frame into a 2D context of W×H pixels. */
/**
 * `view`: draw through a director's free camera instead of the shot's (the Stage page). The scope bars are left out
 * then, and the runtime is put back at once, so the next ordinary draw or export is the real film again.
 */
export function drawStage(c: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, l: AnimLayer, s: AnimScene, frame: number, W: number, H: number, onReady?: () => void, view?: StageView | null): void {
  const spec = l.stage;
  if (!spec) return;
  if (!stageReady(spec)) {
    const key = l.id;
    if (!loading.has(key)) {
      loading.add(key);
      loadStage(spec)
        .then(() => {
          lastError = "";
          onReady?.();
        })
        .catch((e) => {
          lastError = e instanceof Error ? e.message : String(e);
          console.error("Stage could not load:", e);
        })
        .finally(() => loading.delete(key));
    }
    note(c, W, H, lastError ? `Stage: ${lastError}` : "Loading the stage…", !!lastError);
    return;
  }
  const rt = runtimeOf(l.id);
  if (!rt) {
    note(c, W, H, `The 3D stage could not start on this device.\n${filmProblem() ?? ""}`, true);
    return;
  }
  try {
    const t = frame / s.fps;
    rt.sync(spec);
    rt.view = view ?? null;
    try {
      rt.evaluate(spec, t, 1 / s.fps);
    } finally {
      rt.view = null;
    }
    // Letterbox to the look's aspect (2.39 for scope) by rendering the inner frame and leaving bars.
    const asp = view ? 0 : (spec.look.aspect ?? 0);
    let w = W, h = H, y = 0;
    if (asp > 0 && asp > W / H + 0.01) {
      h = Math.max(2, Math.round(W / asp / 2) * 2);
      y = Math.round((H - h) / 2);
    }
    const img = rt.render(w, h, t);
    c.save();
    c.setTransform(1, 0, 0, 1, 0, 0);
    if (y > 0) {
      c.fillStyle = "#000";
      c.fillRect(0, 0, W, H);
    }
    c.drawImage(img, 0, y, w, h);
    c.restore();
    lastError = "";
  } catch (e) {
    lastError = e instanceof Error ? e.message : String(e);
    console.error("Stage draw failed:", e);
    note(c, W, H, `Stage: ${lastError}`, true);
  }
}
