// The finish over the drawings — what anime calls photography (satsuei): the last pass, where a flat-painted figure
// is laid on its background and the light is built over both. A figure that is only pasted on looks cut out; these
// three things seat it in the picture:
//   wrap    the background's light bleeds a little over the figure's edge (just short of being noticed; light bleeds,
//           darkness does not)
//   para    a soft gradient over the whole figure: lighter towards the light, deeper and cooler away from it
//   diffuse a soft glow over the whole picture round what is light (a soft knee, no threshold; blacks stay black)
// A look asks for them: look.finish = { wrap: 0…1, para: 0…1 } and post: ['diffuse'] with look.diffuse = 0…1.
import { createCanvas } from '@napi-rs/canvas';

const cache = new Map();
const canvasOf = (key, w, h) => { let c = cache.get(key); if (!c || c.width < w || c.height < h) { c = createCanvas(Math.max(w, c?.width ?? 0, 16), Math.max(h, c?.height ?? 0, 16)); cache.set(key, c); } return c; };
const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const rgba = (c, a) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${clamp(a).toFixed(4)})`;

/**
 * Seat a painted figure in the picture it is about to be laid on. g: the picture's context (read only: the background
 * behind the figure); spr: { canvas, x, y, w, h } as the painter returns it (its canvas is changed in place); F: the
 * look's finish { wrap, para }; env: { H: picture height, light2: [x, y] towards the light on the screen,
 * shade: [r, g, b] the colour of the dark side, glow: [r, g, b] the colour of the light }.
 */
export function finishFigure(g, spr, F, env = {}) {
  if (!F || !spr?.canvas || !(F.wrap > 0 || F.para > 0)) return;
  const w = Math.round(spr.w), h = Math.round(spr.h); if (w < 4 || h < 4) return;
  const sg = spr.canvas.getContext('2d'), Hp = env.H ?? g.canvas.height;
  const own = sg.getImageData(0, 0, w, h).data; // the figure's own outline: the layers below must not add to it
  if (F.para > 0) {
    const L = env.light2 ?? [-0.5, -0.86], r = 0.5 * (Math.abs(L[0]) * w + Math.abs(L[1]) * h) || 1, cx = w / 2, cy = h / 2;
    const P = canvasOf('para', w, h), pg = P.getContext('2d'), shade = env.shade ?? [70, 78, 120], glow = env.glow ?? [255, 240, 214];
    const lay = (stops, op, alpha) => {
      const gr = pg.createLinearGradient(cx - L[0] * r, cy - L[1] * r, cx + L[0] * r, cy + L[1] * r); for (const [at, col] of stops) gr.addColorStop(at, col);
      pg.save(); pg.globalCompositeOperation = 'source-over'; pg.clearRect(0, 0, P.width, P.height); pg.fillStyle = gr; pg.fillRect(0, 0, w, h);
      pg.globalCompositeOperation = 'destination-in'; pg.drawImage(spr.canvas, 0, 0); pg.restore(); // only where the figure is
      sg.save(); sg.globalCompositeOperation = op; sg.globalAlpha = alpha; sg.drawImage(P, 0, 0, w, h, 0, 0, w, h); sg.restore();
    };
    lay([[0, rgba(shade, F.para * 1.6)], [0.55, rgba(shade, 0)], [1, rgba(shade, 0)]], 'multiply', 1); // away from the light: deeper, cooler
    lay([[0, rgba(glow, 0)], [0.5, rgba(glow, 0)], [1, rgba(glow, F.para * 0.9)]], 'screen', 1); // towards it: lighter, warmer
  }
  if (F.wrap > 0) {
    const R = clamp(Hp * 0.011, 2, 24), tr = g.getTransform ? g.getTransform() : { e: 0, f: 0 }, ox = Math.round(spr.x + (tr.e ?? 0)), oy = Math.round(spr.y + (tr.f ?? 0));
    const sx0 = Math.max(0, ox), sy0 = Math.max(0, oy), sx1 = Math.min(g.canvas.width, ox + w), sy1 = Math.min(g.canvas.height, oy + h); if (sx1 - sx0 < 2 || sy1 - sy0 < 2) { keepOutline(sg, own, w, h); return; }
    // the background behind the figure, softened: its light as it falls round the figure
    const B = canvasOf('wrapB', w, h), bg = B.getContext('2d'); bg.save(); bg.globalCompositeOperation = 'source-over'; bg.clearRect(0, 0, B.width, B.height);
    bg.filter = `blur(${(R * 1.6).toFixed(1)}px)`; bg.drawImage(g.canvas, sx0, sy0, sx1 - sx0, sy1 - sy0, sx0 - ox, sy0 - oy, sx1 - sx0, sy1 - sy0); bg.filter = 'none';
    // kept only in a band inside the figure's edge: where the figure is, less where the softened figure is whole
    const A = canvasOf('wrapA', w, h), ag = A.getContext('2d'); ag.save(); ag.clearRect(0, 0, A.width, A.height); ag.filter = `blur(${R.toFixed(1)}px)`; ag.drawImage(spr.canvas, 0, 0); ag.filter = 'none'; ag.restore();
    bg.globalCompositeOperation = 'destination-in'; bg.drawImage(spr.canvas, 0, 0);
    bg.globalCompositeOperation = 'destination-out'; bg.drawImage(A, 0, 0, w, h, 0, 0, w, h); bg.restore();
    // and it only ever lightens (a dark background gives nothing)
    sg.save(); sg.globalCompositeOperation = 'lighten'; sg.globalAlpha = clamp(F.wrap); sg.drawImage(B, 0, 0, w, h, 0, 0, w, h); sg.restore();
  }
  keepOutline(sg, own, w, h);
}
/**
 * Laying a layer over a soft edge thickens it: give every point of the figure back the cover it had. And the ink stays
 * ink: a line (a near-black point of the figure) keeps its own colour, so the light laid over the figure never turns the
 * line art grey (16 Nov 2026: against a light ground every outline read as a soft grey-brown).
 */
function keepOutline(sg, own, w, h) {
  const im = sg.getImageData(0, 0, w, h), d = im.data; let changed = false;
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] !== own[i + 3]) { d[i + 3] = own[i + 3]; changed = true; }
    const l = own[i] * 0.3 + own[i + 1] * 0.59 + own[i + 2] * 0.11;
    if (own[i + 3] > 0 && l < 58) { const k = 1 - Math.max(0, (l - 38) / 20); for (let c = 0; c < 3; c++) { const v = Math.round(d[i + c] + (own[i + c] - d[i + c]) * k); if (v !== d[i + c]) { d[i + c] = v; changed = true; } } }
  }
  if (changed) sg.putImageData(im, 0, 0);
}

/** A soft glow over the whole picture round what is light: the picture's own light, softened wide and laid back over it. */
export function diffuse(g, W, H, amt, src) {
  if (!(amt > 0)) return;
  const w = Math.max(2, W >> 2), h = Math.max(2, H >> 2), a = canvasOf('difA', w, h), b = canvasOf('difB', w, h), ga = a.getContext('2d'), gb = b.getContext('2d');
  ga.save(); ga.globalCompositeOperation = 'source-over'; ga.clearRect(0, 0, a.width, a.height); ga.drawImage(src, 0, 0, W, H, 0, 0, w, h); ga.restore();
  const im = ga.getImageData(0, 0, w, h), d = im.data;
  for (let i = 0; i < d.length; i += 4) { const l = (0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]) / 255, k = l * l * l; d[i] *= k; d[i + 1] *= k; d[i + 2] *= k; } // a soft knee: the lighter, the more it glows
  ga.putImageData(im, 0, 0);
  gb.save(); gb.clearRect(0, 0, b.width, b.height); gb.filter = `blur(${Math.max(1, 12 * H / 1080).toFixed(1)}px)`; gb.drawImage(a, 0, 0, w, h, 0, 0, w, h); gb.filter = 'none'; gb.restore();
  g.save(); g.setTransform(1, 0, 0, 1, 0, 0); g.globalCompositeOperation = 'screen'; g.globalAlpha = clamp(amt); g.drawImage(b, 0, 0, w, h, 0, 0, W, H); g.restore();
}
