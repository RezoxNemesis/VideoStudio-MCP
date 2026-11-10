/**
 * Video scopes for the Color page: luma waveform, RGB parade, vectorscope and histogram of the picture in the
 * viewer, redrawn as the playhead moves or a grade changes. Measured from the viewer's own output, so they show
 * exactly what the grade does.
 */
import type { Studio } from "../../../src/app/studio";
import type { Preview } from "../../../src/ui/preview";
import { h, selectEl } from "./kit";

type Mode = "waveform" | "parade" | "vector" | "histogram";
const SW = 256;
const SH = 144;

export class Scopes {
  readonly el: HTMLElement;
  private canvas: HTMLCanvasElement;
  private sample: HTMLCanvasElement;
  private mode: Mode = (localStorage.getItem("vs.scope") as Mode) || "waveform";
  private timer = 0;
  private lastDraw = 0;
  private stats: HTMLElement;

  constructor(
    private readonly s: Studio,
    private readonly preview: Preview,
  ) {
    this.canvas = h("canvas", { class: "scope-canvas", width: "512", height: "256" }) as HTMLCanvasElement;
    this.sample = document.createElement("canvas");
    this.sample.width = SW;
    this.sample.height = SH;
    this.stats = h("span", { class: "scope-stats mono" }, "");
    const sel = selectEl<Mode>(
      [
        ["waveform", "Waveform"],
        ["parade", "RGB parade"],
        ["vector", "Vectorscope"],
        ["histogram", "Histogram"],
      ],
      this.mode,
      (v) => {
        this.mode = v;
        localStorage.setItem("vs.scope", v);
        this.draw();
      },
    );
    this.el = h("div", { class: "scopes" }, h("div", { class: "scope-head" }, h("span", { class: "group-title" }, "Scopes"), sel, h("span", { class: "sp" }), this.stats), this.canvas);
    const req = () => this.request();
    s.on("time", req);
    s.on("change", req);
    s.on("project", req);
    new ResizeObserver(req).observe(this.el);
    setTimeout(req, 600);
  }

  private request(): void {
    if (this.timer || !this.el.isConnected || this.el.offsetParent === null) return;
    const wait = Math.max(60, (this.s.playing ? 140 : 60) - (performance.now() - this.lastDraw));
    this.timer = window.setTimeout(() => {
      this.timer = 0;
      // Let the viewer finish the frame it is drawing first.
      requestAnimationFrame(() => this.draw());
    }, wait);
  }

  private pixels(): Uint8ClampedArray | null {
    const src = this.preview.el.querySelector("canvas") as HTMLCanvasElement | null;
    if (!src || !src.width || !src.height) return null;
    const g = this.sample.getContext("2d", { willReadFrequently: true })!;
    // The viewer canvas shows the frame fitted inside it; measure the frame only.
    const v = this.preview.viewRect();
    const kx = src.width / Math.max(1, src.clientWidth);
    const ky = src.height / Math.max(1, src.clientHeight);
    const zoomed = this.preview.zoomLevel > 1.001;
    g.fillStyle = "#000";
    g.fillRect(0, 0, SW, SH);
    try {
      if (zoomed) g.drawImage(src, 0, 0, SW, SH);
      else g.drawImage(src, v.x * kx, v.y * ky, Math.max(1, v.w * kx), Math.max(1, v.h * ky), 0, 0, SW, SH);
      return g.getImageData(0, 0, SW, SH).data;
    } catch {
      return null;
    }
  }

  private draw(): void {
    this.lastDraw = performance.now();
    const d = this.pixels();
    const c = this.canvas;
    const g = c.getContext("2d")!;
    const W = c.width;
    const H = c.height;
    g.fillStyle = "#05070a";
    g.fillRect(0, 0, W, H);
    if (!d) return;
    const out = g.createImageData(W, H);
    const o = out.data;
    const put = (x: number, y: number, r: number, gg: number, b: number) => {
      if (x < 0 || y < 0 || x >= W || y >= H) return;
      const k = (y * W + x) * 4;
      o[k] = Math.min(255, o[k]! + r);
      o[k + 1] = Math.min(255, o[k + 1]! + gg);
      o[k + 2] = Math.min(255, o[k + 2]! + b);
      o[k + 3] = 255;
    };
    let lo = 255;
    let hi = 0;
    let sum = 0;
    const n = SW * SH;
    if (this.mode === "waveform" || this.mode === "parade") {
      const parade = this.mode === "parade";
      const colW = parade ? W / 3 : W;
      for (let y = 0; y < SH; y++) {
        for (let x = 0; x < SW; x++) {
          const k = (y * SW + x) * 4;
          const r = d[k]!;
          const gg = d[k + 1]!;
          const b = d[k + 2]!;
          const l = 0.2126 * r + 0.7152 * gg + 0.0722 * b;
          if (l < lo) lo = l;
          if (l > hi) hi = l;
          sum += l;
          const px = Math.floor((x / SW) * colW);
          if (parade) {
            put(px, H - 1 - Math.round((r / 255) * (H - 1)), 46, 6, 6);
            put(Math.floor(colW) + px, H - 1 - Math.round((gg / 255) * (H - 1)), 6, 40, 8);
            put(Math.floor(colW * 2) + px, H - 1 - Math.round((b / 255) * (H - 1)), 8, 12, 52);
          } else put(px, H - 1 - Math.round((l / 255) * (H - 1)), 14, 34, 28);
        }
      }
    } else if (this.mode === "vector") {
      const cx = W / 2;
      const cy = H / 2;
      const R = Math.min(W, H) / 2 - 6;
      for (let k = 0; k < n * 4; k += 4) {
        const r = d[k]! / 255;
        const gg = d[k + 1]! / 255;
        const b = d[k + 2]! / 255;
        const l = 0.2126 * r + 0.7152 * gg + 0.0722 * b;
        const cb = (b - l) / 1.8556;
        const cr = (r - l) / 1.5748;
        const L = l * 255;
        if (L < lo) lo = L;
        if (L > hi) hi = L;
        sum += L;
        put(Math.round(cx + cb * 2 * R), Math.round(cy - cr * 2 * R), 10 + r * 40, 10 + gg * 40, 10 + b * 40);
      }
    } else {
      const hr = new Uint32Array(256);
      const hg = new Uint32Array(256);
      const hb = new Uint32Array(256);
      for (let k = 0; k < n * 4; k += 4) {
        hr[d[k]!]!++;
        hg[d[k + 1]!]!++;
        hb[d[k + 2]!]!++;
        const l = 0.2126 * d[k]! + 0.7152 * d[k + 1]! + 0.0722 * d[k + 2]!;
        if (l < lo) lo = l;
        if (l > hi) hi = l;
        sum += l;
      }
      let top = 1;
      for (let i = 1; i < 255; i++) top = Math.max(top, hr[i]!, hg[i]!, hb[i]!);
      for (let x = 0; x < W; x++) {
        const i = Math.min(255, Math.floor((x / W) * 256));
        const hsR = Math.min(H, Math.round((hr[i]! / top) * (H - 4)));
        const hsG = Math.min(H, Math.round((hg[i]! / top) * (H - 4)));
        const hsB = Math.min(H, Math.round((hb[i]! / top) * (H - 4)));
        for (let y = 0; y < Math.max(hsR, hsG, hsB); y++) put(x, H - 1 - y, y < hsR ? 150 : 0, y < hsG ? 130 : 0, y < hsB ? 170 : 0);
      }
    }
    g.putImageData(out, 0, 0);
    // Graticule.
    g.strokeStyle = "rgba(255,255,255,0.10)";
    g.fillStyle = "rgba(255,255,255,0.38)";
    g.font = "10px ui-monospace, monospace";
    g.lineWidth = 1;
    if (this.mode === "vector") {
      const R = Math.min(W, H) / 2 - 6;
      for (const f of [0.25, 0.5, 0.75, 1]) {
        g.beginPath();
        g.arc(W / 2, H / 2, R * f, 0, Math.PI * 2);
        g.stroke();
      }
      g.beginPath();
      g.moveTo(W / 2 - R, H / 2);
      g.lineTo(W / 2 + R, H / 2);
      g.moveTo(W / 2, H / 2 - R);
      g.lineTo(W / 2, H / 2 + R);
      // Skin-tone line.
      g.moveTo(W / 2, H / 2);
      g.lineTo(W / 2 - R * Math.cos((57 * Math.PI) / 180), H / 2 - R * Math.sin((57 * Math.PI) / 180));
      g.stroke();
    } else if (this.mode !== "histogram") {
      for (const p of [0, 25, 50, 75, 100]) {
        const y = Math.round(H - 1 - (p / 100) * (H - 1)) + 0.5;
        g.beginPath();
        g.moveTo(0, y);
        g.lineTo(W, y);
        g.stroke();
        g.fillText(String(p), 3, Math.min(H - 3, Math.max(10, y - 2)));
      }
    }
    this.stats.textContent = `black ${Math.round((lo / 255) * 100)} · white ${Math.round((hi / 255) * 100)} · avg ${Math.round((sum / n / 255) * 100)}`;
  }
}
