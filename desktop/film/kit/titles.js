// Lettering: the main title, chapter cards, the end card. Drawn straight onto the picture by a shot's `top`.
import { clamp, lerp, smooth } from '../engine/core.js';
const ease = (x) => 1 - Math.pow(1 - clamp(x), 3);
/** Letters set one by one with their own spacing; returns the total width. draw(ch, x, i) is called for each. */
function track(g, text, spacing, draw) {
  let w = 0; const ws = [...text].map((ch) => g.measureText(ch).width); const total = ws.reduce((a, b) => a + b, 0) + spacing * (text.length - 1);
  let x = -total / 2; [...text].forEach((ch, i) => { if (draw) draw(ch, x + ws[i] / 2, i); x += ws[i] + spacing; }); void w; return total;
}
/** The main title. u = seconds since the card began. o: { x, y (0..1 of the frame), scale, sub, small, out (second the letters leave) } */
export function mainTitle(g, W, H, u, o = {}) {
  const k = H / 1080 * (o.scale ?? 1), cx = W * (o.x ?? 0.5), cy = H * (o.y ?? 0.44), out = o.out ?? 1e9, gone = smooth(clamp((u - out) / 0.7));
  g.save(); g.translate(cx, cy); g.textAlign = 'center'; g.textBaseline = 'alphabetic'; g.globalAlpha = 1 - gone;
  // OREZ: each letter arrives a little after the last, settling from large to its place
  g.font = `700 ${Math.round(250 * k)}px "Lora"`;
  track(g, o.word ?? 'OREZ', 34 * k, (ch, x, i) => {
    const a = ease((u - 0.25 - i * 0.16) / 0.7); if (a <= 0) return;
    const s = lerp(1.35, 1, a); g.save(); g.translate(x, 0); g.scale(s, s); g.globalAlpha = a * (1 - gone);
    g.shadowColor = 'rgba(10,6,20,0.55)'; g.shadowBlur = 26 * k; g.shadowOffsetY = 8 * k;
    const gr = g.createLinearGradient(0, -190 * k, 0, 10 * k); gr.addColorStop(0, '#fffaf0'); gr.addColorStop(0.55, '#ffe2a8'); gr.addColorStop(1, '#e89a48');
    g.fillStyle = gr; g.fillText(ch, 0, 0); g.shadowColor = 'transparent'; g.lineWidth = 2.2 * k; g.strokeStyle = 'rgba(70,30,10,0.55)'; g.strokeText(ch, 0, 0); g.restore();
  });
  // a blade of light under the name, drawn left to right
  const b = ease((u - 1.15) / 0.6), bw = 430 * k;
  if (b > 0) { const gr = g.createLinearGradient(-bw, 0, bw, 0); gr.addColorStop(0, 'rgba(255,220,160,0)'); gr.addColorStop(0.5, 'rgba(255,244,214,0.98)'); gr.addColorStop(1, 'rgba(255,220,160,0)'); g.fillStyle = gr; g.globalAlpha = (1 - gone); g.fillRect(-bw, 44 * k, 2 * bw * b, 3.2 * k); }
  // THE INFINITE KING: the letters draw together from wide apart
  const c = ease((u - 1.45) / 0.9);
  if (c > 0) { g.globalAlpha = c * (1 - gone); g.font = `600 ${Math.round(56 * k)}px "Inter"`; g.shadowColor = 'rgba(10,6,20,0.7)'; g.shadowBlur = 14 * k; g.fillStyle = '#fff6e6'; track(g, o.sub ?? 'THE INFINITE KING', lerp(46, 19, c) * k, (ch, x) => g.fillText(ch, x, 128 * k)); }
  const d = ease((u - 2.3) / 0.8);
  if (d > 0 && o.small) { g.globalAlpha = d * 0.92 * (1 - gone); g.font = `500 ${Math.round(30 * k)}px "Inter"`; g.fillStyle = '#ffe9c4'; g.shadowBlur = 10 * k; track(g, o.small, 9 * k, (ch, x) => g.fillText(ch, x, 196 * k)); }
  g.restore();
}
/** A line of place-and-time lettering in a lower corner ("Kiri village — ten winters earlier"). u = seconds since it began; lasts `hold`. */
export function caption(g, W, H, u, text, o = {}) {
  const k = H / 1080, hold = o.hold ?? 3.6, a = ease(u / 0.6) * (1 - smooth(clamp((u - hold) / 0.7))); if (a <= 0) return;
  g.save(); g.globalAlpha = a; g.textBaseline = 'alphabetic'; g.textAlign = o.align ?? 'left';
  const x = W * (o.x ?? 0.07) + (1 - ease(u / 0.8)) * -24 * k, y = H * (o.y ?? 0.86);
  g.font = `600 ${Math.round(52 * k)}px "Lora"`; g.shadowColor = 'rgba(8,6,16,0.75)'; g.shadowBlur = 16 * k; g.shadowOffsetY = 3 * k; g.fillStyle = '#fff6e4'; g.fillText(text, x, y);
  if (o.sub) { g.font = `500 ${Math.round(30 * k)}px "Inter"`; g.fillStyle = '#ffe2b0'; g.fillText(o.sub.toUpperCase().split('').join(' '), x + 3 * k, y + 46 * k); }
  g.shadowColor = 'transparent'; g.fillStyle = 'rgba(255,214,150,0.95)'; const lw = g.measureText(text).width; g.fillRect(o.align === 'right' ? x - 150 * k * ease(u / 0.9) : x, y - 64 * k, 150 * k * ease(u / 0.9), 3 * k); void lw;
  g.restore();
}
