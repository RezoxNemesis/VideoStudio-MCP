/**
 * Magic Eraser: one screen for cleaning a picture or video.
 *  1. Mark what to change — tap it (on-phone AI finds the exact outline), paint it with the brush, or rub out.
 *  2. Choose what happens there:
 *     Erase (rebuild the background, fast) · Smart fill (repaint with generative AI: skin, arms, cloth, anything,
 *     optionally guided by words) · Recolour (keep the shape, new colour) · Blur / Pixelate (hide).
 *  3. Apply — to the whole clip or just this moment.
 * Everything runs on the phone.
 */
import type { Studio } from "../app/studio";
import { findAsset, findClip } from "../core/model";
import { runCommand } from "../agent/api";
import { segment, maskToPng } from "../media/ai";
import { genStatus, GEN_TOTAL_MB } from "../media/genfill";
import { btn, h, sheet, toast } from "./dom";

type Tool = "tap" | "brush" | "rub";
type Action = "erase" | "smart" | "recolor" | "blur" | "pixelate";

export async function openMagicEraser(s: Studio, clipId: string): Promise<void> {
  const found = findClip(s.project, clipId);
  const c = found?.clip;
  const asset = findAsset(s.project, c?.assetId);
  if (!c || !asset || (asset.kind !== "video" && asset.kind !== "image")) return toast("Pick a video or photo clip first.", "error");
  const at = c.in + Math.max(0, s.time - c.start) * c.speed;
  s.setStatus("Opening Magic Eraser…");
  const src = asset.kind === "image" ? ((await s.pool.image(asset)) as CanvasImageSource) : ((await (await s.pool.framer(asset, "magic", 1920))?.frameAt(at))?.canvas as CanvasImageSource | undefined);
  s.setStatus("");
  if (!src) return toast("Could not read this frame.", "error");
  const SW = (src as { width: number }).width,
    SH = (src as { height: number }).height;
  // Work at up to 1024 px (the mask is scaled to the clip when applied).
  const k = Math.min(1, 1024 / Math.max(SW, SH));
  const W = Math.round(SW * k),
    H = Math.round(SH * k);
  const mask = new Uint8Array(W * H);

  const view = h("canvas", { class: "magic-canvas", width: W, height: H, style: "width:100%;max-height:52vh;object-fit:contain;touch-action:none;border-radius:10px;background:#111" }) as HTMLCanvasElement;
  const vx = view.getContext("2d")!;
  const base = document.createElement("canvas");
  base.width = W;
  base.height = H;
  base.getContext("2d")!.drawImage(src, 0, 0, W, H);
  const overlay = new ImageData(W, H);
  const redraw = () => {
    vx.drawImage(base, 0, 0);
    const d = overlay.data;
    for (let i = 0; i < W * H; i++) {
      const on = mask[i]! > 127;
      d[i * 4] = 255;
      d[i * 4 + 1] = 40;
      d[i * 4 + 2] = 90;
      d[i * 4 + 3] = on ? 120 : 0;
    }
    const oc = document.createElement("canvas");
    oc.width = W;
    oc.height = H;
    oc.getContext("2d")!.putImageData(overlay, 0, 0);
    vx.drawImage(oc, 0, 0);
  };
  redraw();

  let tool: Tool = "tap";
  let brush = Math.round(Math.max(W, H) / 30);
  let action: Action = "smart";
  let negative = false;
  const points: [number, number, number][] = [];
  const toolRow = h("div", { class: "row wrap" });
  const drawTools = () => {
    toolRow.replaceChildren(
      ...([
        ["tap", "Tap object", "hand"],
        ["brush", "Brush", "brush"],
        ["rub", "Rub out", "eraser"],
      ] as const).map(([t, l, ic]) => btn(l, ic, () => ((tool = t), drawTools()), tool === t ? "primary" : "ghost")),
      tool === "tap" ? btn(negative ? "Mode: not this" : "Mode: add", negative ? "close" : "plus", () => ((negative = !negative), drawTools()), "ghost") : h("span"),
      btn("Clear", "trash", () => (mask.fill(0), (points.length = 0), redraw()), "ghost"),
    );
  };
  drawTools();
  const sizeRow = h("label", { class: "row" }, "Brush size ", h("input", { type: "range", min: "4", max: String(Math.round(Math.max(W, H) / 6)), value: String(brush), oninput: (e: Event) => (brush = Number((e.target as HTMLInputElement).value)) }));

  const toLocal = (ev: PointerEvent) => {
    const r = view.getBoundingClientRect();
    // object-fit: contain → account for letterboxing inside the element.
    const sc = Math.min(r.width / W, r.height / H);
    const ox = (r.width - W * sc) / 2,
      oy = (r.height - H * sc) / 2;
    return [(ev.clientX - r.left - ox) / sc, (ev.clientY - r.top - oy) / sc] as const;
  };
  const paint = (x: number, y: number, on: boolean) => {
    const R = brush / 2;
    for (let yy = Math.max(0, Math.floor(y - R)); yy < Math.min(H, Math.ceil(y + R)); yy++)
      for (let xx = Math.max(0, Math.floor(x - R)); xx < Math.min(W, Math.ceil(x + R)); xx++) if ((xx - x) ** 2 + (yy - y) ** 2 <= R * R) mask[yy * W + xx] = on ? 255 : 0;
  };
  let down = false;
  let last: readonly [number, number] | null = null;
  view.addEventListener("pointerdown", async (ev) => {
    ev.preventDefault();
    const [x, y] = toLocal(ev);
    if (x < 0 || y < 0 || x >= W || y >= H) return;
    if (tool === "tap") {
      points.push([x, y, negative ? 0 : 1]);
      hint.textContent = "Finding the outline (on-phone AI)…";
      try {
        const seg = await segment(base, W, H, points, undefined, (m) => (hint.textContent = m));
        for (let i = 0; i < W * H; i++) mask[i] = seg.mask[i] ? 255 : 0;
        hint.textContent = `Selected (${Math.round(seg.score * 100)}% sure). Tap more parts to add, switch to "not this" to remove parts, or touch up with the brush.`;
      } catch (e) {
        hint.textContent = e instanceof Error ? e.message : String(e);
      }
      redraw();
      return;
    }
    down = true;
    view.setPointerCapture(ev.pointerId);
    last = [x, y];
    paint(x, y, tool === "brush");
    redraw();
  });
  view.addEventListener("pointermove", (ev) => {
    if (!down) return;
    const [x, y] = toLocal(ev);
    if (last) {
      const n = Math.max(1, Math.ceil(Math.hypot(x - last[0], y - last[1]) / Math.max(1, brush / 4)));
      for (let i = 1; i <= n; i++) paint(last[0] + ((x - last[0]) * i) / n, last[1] + ((y - last[1]) * i) / n, tool === "brush");
    }
    last = [x, y];
    redraw();
  });
  const up = () => ((down = false), (last = null));
  view.addEventListener("pointerup", up);
  view.addEventListener("pointercancel", up);

  const hint = h("p", { class: "hint" }, "Tap the thing you want gone or changed. The AI finds its exact outline. Use the brush for anything it misses.");
  const prompt = h("input", { type: "text", placeholder: "What should be there? e.g. bare arms, white t-shirt sleeves (optional)", style: "width:100%" }) as HTMLInputElement;
  const color = h("input", { type: "color", value: "#2a3f6e" }) as HTMLInputElement;
  const quality = h("select", {}, h("option", { value: "14" }, "Fast (14 steps)"), h("option", { value: "22", selected: "selected" }, "Balanced (22 steps)"), h("option", { value: "34" }, "Best (34 steps)")) as HTMLSelectElement;
  const scope = h("select", {}, h("option", { value: "all" }, "Whole clip"), h("option", { value: "here" }, "Only around this moment (±1 s)")) as HTMLSelectElement;
  const actRow = h("div", { class: "row wrap" });
  const extra = h("div", {});
  const st = await genStatus().catch(() => ({ ready: false, missingMB: GEN_TOTAL_MB, totalMB: GEN_TOTAL_MB }));
  const drawActions = () => {
    actRow.replaceChildren(
      ...([
        ["smart", "Smart fill"],
        ["erase", "Erase"],
        ["recolor", "Recolour"],
        ["blur", "Blur"],
        ["pixelate", "Pixelate"],
      ] as const).map(([a, l]) => btn(l, null, () => ((action = a), drawActions()), action === a ? "primary" : "ghost")),
    );
    const info: Record<Action, string> = {
      smart: `Repaints the marked area with generative AI on your phone: it can draw skin, arms, cloth or background that was hidden. About 1–3 minutes. Needs a phone with lots of free memory (about 2.3 GB) — on phones where it can't fit, use Erase or Recolour.${st.ready ? "" : ` First use downloads ${st.missingMB} MB once (Wi-Fi).`}`,
      erase: "Removes it and rebuilds what was behind from the surroundings. Best for logos, text, stickers and small objects. A few seconds.",
      recolor: "Keeps the shape, folds and shading — only the colour changes. Safest for clothes.",
      blur: "Hides it with a soft blur.",
      pixelate: "Hides it with big pixels.",
    };
    extra.replaceChildren(
      h("p", { class: "hint" }, info[action]),
      action === "smart" ? h("div", {}, prompt, h("label", { class: "row" }, "Quality ", quality)) : h("span"),
      action === "recolor" ? h("label", { class: "row" }, "New colour ", color) : h("span"),
      asset.kind === "video" ? h("label", { class: "row" }, "Apply to ", scope) : h("span"),
    );
  };
  drawActions();

  const apply = btn("Apply", "check", async () => {
    if (!mask.some((v) => v > 127)) return toast("Mark something first: tap it or paint it.", "error");
    const png = await maskToPng(mask, W, H, 1024);
    const mode = action === "erase" ? "ai" : action === "smart" ? "smart" : action;
    const range = asset.kind === "video" && scope.value === "here" ? { from: Math.max(0, at - 1), to: at + 1 } : {};
    sh.close();
    const r = await runCommand(s, { op: "clip.addRegion", id: clipId, mask: png, mode, color: color.value, ...range });
    if (!r.ok) return toast(r.error ?? "Could not apply", "error", 5000);
    if (action === "smart") {
      toast("Smart fill is painting on your phone. You can keep working.", "info", 4000);
      const res = await runCommand(s, { op: "clip.smartFill", id: clipId, regionId: (r.result as { regionId: string }).regionId, prompt: prompt.value || undefined, steps: Number(quality.value), at });
      if (!res.ok) toast(res.error ?? "Smart fill failed", "error", 7000);
      else toast(`Smart fill done in ${(res.result as { seconds: number }).seconds}s`, "ok", 4000);
    } else toast("Done. Fine-tune it in Remove.", "ok");
  }, "primary");

  const body = h("div", { class: "magic" }, view, hint, toolRow, sizeRow, h("h3", {}, "What should happen there?"), actRow, extra, h("div", { class: "row" }, apply));
  const sh = sheet("Magic Eraser", body, { tall: true });
}
