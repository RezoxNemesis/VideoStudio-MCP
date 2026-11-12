// Looks: the same film, the same real bodies and the same performances drawn in another kind of animation.
//
//   anime      2D anime (the default): flat cel tones, ink lines, held drawings where the shot asks
//   paper      paper cut: flat paper colours, no ink, every layer casting a soft shadow on the one behind, paper grain,
//              moving on threes like stop motion
//   puppet     cut-out puppet theatre: paper, a little jolt each drawing, on threes
//   flat       motion-graphics flat design: clean flat colours and thin lines, no grain, no vignette, punchy colour
//   pixel      pixel art: a small canvas, a limited palette with ordered dithering, on twos
//   sketch     pencil: graphite lines and hatching on paper, a faint colour wash
//   manga      black and white manga: ink, screen tone for the half tones
//   soft3d     3D render: smooth shading, no lines, soft bloom                                         (also "3d")
//   cel3d      cel-shaded 3D: three tones, rim light, an outer line, thinner inner lines               (also "3.5d")
//   immersive  cel-shaded 3D with light that fills the air: bloom, light shafts from the sun, depth haze, floating motes,
//              edge colour fringe, a breathing camera                                                  (also "4d")
//   multiplane the 2.5D multiplane camera of anime films: anime drawing, layered depth haze, a soft far background
//                                                                                                       (also "2.5d")
// A film sets one in film.json ("look": "paper" or {"style": "pixel", "size": 4}); a shot can set its own (shot.look),
// for a flashback in pencil or a dream in paper.
import { createCanvas } from '@napi-rs/canvas';

const ALIAS = { 'anime-classic': 'classic', cel: 'classic', '2d': 'anime', '2.5d': 'multiplane', '3d': 'soft3d', '3.5d': 'cel3d', '4d': 'immersive', papercut: 'paper', 'paper-cut': 'paper', cutout: 'puppet', motion: 'flat', mograph: 'flat', pencil: 'sketch', '8bit': 'pixel' };
/**
 * paint: how the cel painter shades (mode cel | smooth | flat | tri; lines false = none; inner 0…1 = inner line width)
 * hold: drawings held for so many frames when the shot does not say; layers: soft shadow under every painted layer
 * post: passes over the finished frame; grain / vignette: multipliers of the usual amounts
 */
export const LOOKS = {
  anime: { about: '2D anime, painted: crisp cel shadows with soft light across the lit side, deeper tones where forms tuck in, warm skin at the turn of the light, polished metal, hair sheen, rim light, ink outlines and inner lines tinted by their colours, a soft glow on lights', paint: { mode: 'paint', rim: true }, post: ['bloom', 'grade'], bloom: 0.35 },
  classic: { about: '2D anime, classic: two flat cel tones and black ink (the look before 2.5)' },
  paper: { about: 'Paper cut: flat paper colours with pale cut edges, layered shadows, paper grain, on threes', paint: { mode: 'flat', ink: [244, 236, 220], outer: 0.6, inner: 0.4 }, hold: 3, layers: { blur: 8, dx: 4, dy: 6, a: 0.38 }, post: ['paper'], grain: 0.4 },
  puppet: { about: 'Cut-out puppet theatre: paper pieces that jolt from drawing to drawing, on threes', paint: { mode: 'flat', ink: [236, 226, 206], outer: 0.7, inner: 0.5 }, hold: 3, jitter: 2.2, layers: { blur: 6, dx: 5, dy: 7, a: 0.4 }, post: ['paper'], grain: 0.4 },
  flat: { about: 'Flat motion-graphics design: clean flat colour, thin lines, no grain', paint: { mode: 'flat', inner: 0.4, outer: 0.6 }, post: ['punch'], grain: 0, vignette: 0 },
  pixel: { about: 'Pixel art: a small canvas, a limited palette, ordered dither, on twos', hold: 2, post: ['pixel'], size: 6, dither: 0.4, levels: [8, 8, 7], grain: 0, vignette: 0.5 },
  sketch: { about: 'Pencil: graphite lines and hatching on paper, a faint colour wash', post: ['sketch'], grain: 0.3, vignette: 0.4 },
  manga: { about: 'Black and white manga: ink and screen tone', post: ['manga'], grain: 0, vignette: 0 },
  soft3d: { about: '3D render: smooth shading, no lines, soft bloom', paint: { mode: 'smooth', lines: false }, post: ['bloom'] },
  cel3d: { about: 'Cel-shaded 3D: three tones, rim light, an outer line, fine inner lines', paint: { mode: 'tri', inner: 0.5, rim: true }, post: ['bloom'] },
  immersive: { about: '4D: cel-shaded 3D with light shafts, bloom, depth haze, floating motes and a breathing camera', paint: { mode: 'tri', inner: 0.5, rim: true }, post: ['rays', 'bloom', 'motes', 'fringe'], fog: 1.6, breathe: 1, autoDof: 1.6 },
  multiplane: { about: '2.5D multiplane: anime drawing in layered depth, haze and a soft far background', fog: 1.8, autoDof: 2.4, post: ['bloom'], bloom: 0.5 },
};

/** The look a name or an object asks for, with its settings filled in. */
export function resolveLook(v) {
  if (!v) return { style: 'anime', ...LOOKS.anime };
  const o = typeof v === 'string' ? { style: v } : { ...v };
  const style = ALIAS[String(o.style ?? 'anime').toLowerCase()] ?? String(o.style ?? 'anime').toLowerCase();
  const base = LOOKS[style];
  if (!base) throw new Error(`Unknown look "${o.style}". Looks: ${Object.keys(LOOKS).join(', ')} (or 2d, 2.5d, 3d, 3.5d, 4d).`);
  return { ...base, ...o, style, paint: { ...(base.paint ?? {}), ...(o.paint ?? {}) } };
}

let current = resolveLook(null);
/** The look being drawn now (set by the shot renderer for every frame). */
export const look = () => current;
export function setLook(l) { current = l && l.style ? l : resolveLook(l); }

/* ------------------------------------------------------------------ passes over the finished frame */

const clamp = (v, a = 0, b = 1) => (v < a ? a : v > b ? b : v);
const hash = (x, y) => { let h = (x * 374761393 + y * 668265263) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const scratch = new Map();
function canvasOf(key, w, h) {
  let c = scratch.get(key);
  if (!c || c.width !== w || c.height !== h) { c = createCanvas(w, h); scratch.set(key, c); }
  return c;
}

/** Paper: fibres and tooth, multiplied over the frame (made once per size). */
function paper(g, W, H, k) {
  const key = `paper${W}x${H}`;
  let tex = scratch.get(key);
  if (!tex) {
    tex = createCanvas(W, H);
    const t = tex.getContext('2d'), im = t.createImageData(W, H), d = im.data, s = H / 1080;
    // value noise at two scales: the tooth of the paper and its cloudy fibres
    const cell = Math.max(2, Math.round(3 * s)), big = Math.max(8, Math.round(26 * s));
    const vn = (x, y, c, o) => { const X = Math.floor(x / c), Y = Math.floor(y / c), fx = x / c - X, fy = y / c - Y, a = hash(X + o, Y), b = hash(X + 1 + o, Y), cc = hash(X + o, Y + 1), dd = hash(X + 1 + o, Y + 1), u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy); return a + (b - a) * u + (cc - a) * v + (a - b - cc + dd) * u * v; };
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const v = 0.55 * vn(x, y, cell, 0) + 0.3 * vn(x, y, big, 91) + 0.15 * hash(x, y);
      const fibre = Math.abs(Math.sin((x * 0.021 + y * 0.013 + vn(x, y, big * 3, 7) * 9) * 3)) < 0.02 ? 0.06 : 0;
      const l = 255 * (0.9 + 0.1 * v - fibre), i = (y * W + x) * 4;
      d[i] = l; d[i + 1] = l * 0.985; d[i + 2] = l * 0.95; d[i + 3] = 255;
    }
    t.putImageData(im, 0, 0);
    scratch.set(key, tex);
  }
  g.save(); g.globalCompositeOperation = 'multiply'; g.globalAlpha = clamp(0.85 * k); g.drawImage(tex, 0, 0); g.restore();
}

/** Pixel art: the frame drawn small, its colours brought to a short palette with a 4×4 ordered dither, drawn back up. */
const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map((v) => (v + 0.5) / 16 - 0.5);
function pixel(g, W, H, L, src) {
  // size: how many screen pixels one art pixel covers at 1080p (6 → a 320 × 180 picture)
  const size = Math.max(2, (L.size ?? 6) * H / 1080), w = Math.max(16, Math.round(W / size)), h = Math.max(9, Math.round(H / size)), dz = L.dither ?? 0.55;
  const small = canvasOf('pix', w, h), sg = small.getContext('2d');
  sg.imageSmoothingEnabled = true; sg.imageSmoothingQuality = 'high'; sg.clearRect(0, 0, w, h); sg.drawImage(src, 0, 0, w, h);
  const im = sg.getImageData(0, 0, w, h), d = im.data;
  // levels per channel: a warm palette (more steps of red and green than of blue), lifted shadows
  const lv = L.levels ?? [6, 6, 5];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4, b = BAYER[(y & 3) * 4 + (x & 3)];
    for (let c = 0; c < 3; c++) {
      const n = lv[c] - 1, v = d[i + c] / 255;
      d[i + c] = Math.round(clamp(Math.round(v * n + b * dz) / n) * 255);
    }
  }
  sg.putImageData(im, 0, 0);
  g.save(); g.imageSmoothingEnabled = false; g.drawImage(small, 0, 0, W, H); g.restore();
}

/** Luminance (0…1) of every pixel, and the pixels themselves. */
function lumaOf(g, W, H) {
  const im = g.getImageData(0, 0, W, H), d = im.data, Lm = new Float32Array(W * H);
  for (let i = 0, p = 0; p < W * H; p++, i += 4) Lm[p] = (0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]) / 255;
  return { im, d, Lm };
}

/** Pencil: graphite for the lines and the edges of things, hatching that thickens with the dark, a faint colour wash. */
function sketch(g, W, H, frame) {
  const { im, d, Lm } = lumaOf(g, W, H), s = H / 1080, gap = 7 * s, wob = frame % 3;
  const PAPER = [250, 246, 236], LEAD = [58, 56, 66];
  for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
    const p = y * W + x, i = p * 4, l = Lm[p];
    // edges of shapes: a pencil follows them even where nothing was inked
    const gx = Lm[p + 1] - Lm[p - 1], gy = Lm[p + W] - Lm[p - W], edge = clamp((Math.hypot(gx, gy) - 0.06) * 5);
    // hatching: one direction from the mid tones, a second across it in the shadows, a third in the deep dark
    const n = hash(x >> 3, y >> 3) * 0.6 + 0.7, h1 = ((x + y + wob) % gap) < 1.25 * s ? 1 : 0, h2 = ((x - y + 4096 + wob) % gap) < 1.15 * s ? 1 : 0, h3 = ((x + wob) % Math.round(gap * 0.7)) < 1 ? 1 : 0;
    let dark = 0;
    if (l < 0.62) dark = Math.max(dark, h1 * clamp((0.62 - l) * 3) * n);
    if (l < 0.4) dark = Math.max(dark, h2 * clamp((0.4 - l) * 4) * n);
    if (l < 0.2) dark = Math.max(dark, h3 * 0.9);
    if (l < 0.1) dark = Math.max(dark, clamp((0.1 - l) * 12)); // ink
    dark = Math.max(dark, edge * 0.85);
    // a faint wash of the colour underneath
    const r = d[i], gg = d[i + 1], b = d[i + 2], m = (r + gg + b) / 3, sat = 0.22;
    for (let c = 0; c < 3; c++) {
      const wash = PAPER[c] * (0.86 + 0.14 * l) + ((c === 0 ? r : c === 1 ? gg : b) - m) * sat;
      d[i + c] = wash + (LEAD[c] - wash) * dark;
    }
  }
  g.putImageData(im, 0, 0);
}

/** Manga: black ink, white paper and screen tone (dots on a 45° grid) for everything between. */
function manga(g, W, H) {
  const { im, d, Lm } = lumaOf(g, W, H), s = H / 1080, cell = 6 * s;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const p = y * W + x, i = p * 4, l = Lm[p];
    let v;
    if (l < 0.16) v = 18;
    else if (l > 0.78) v = 250;
    else {
      // rotated grid; the dot grows as the tone darkens
      const u = (x + y) / (cell * 1.414), w = (x - y) / (cell * 1.414), fu = u - Math.floor(u) - 0.5, fw = w - Math.floor(w) - 0.5, r = Math.sqrt(fu * fu + fw * fw);
      const want = Math.sqrt(clamp((0.78 - l) / 0.62) / Math.PI) * 0.9;
      v = r < want ? 22 : 248;
    }
    d[i] = v; d[i + 1] = v; d[i + 2] = v * 0.98;
  }
  g.putImageData(im, 0, 0);
}

/** Bloom: what is bright spills a soft glow round itself. */
function bloom(g, W, H, amt, src) {
  const w = W >> 2, h = H >> 2, a = canvasOf('bloomA', w, h), b = canvasOf('bloomB', w, h), ga = a.getContext('2d'), gb = b.getContext('2d');
  ga.clearRect(0, 0, w, h); ga.drawImage(src, 0, 0, w, h);
  const im = ga.getImageData(0, 0, w, h), d = im.data;
  for (let i = 0; i < d.length; i += 4) { const l = (0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]) / 255, k = clamp((l - 0.72) / 0.28); d[i] *= k; d[i + 1] *= k; d[i + 2] *= k; }
  ga.putImageData(im, 0, 0);
  gb.clearRect(0, 0, w, h); gb.filter = `blur(${(6 * H / 1080).toFixed(1)}px)`; gb.drawImage(a, 0, 0); gb.filter = 'none';
  g.save(); g.globalCompositeOperation = 'screen'; g.globalAlpha = clamp(amt); g.drawImage(b, 0, 0, W, H); g.restore();
}

/** Light shafts: the bright sky round the sun smeared towards it, the way light hangs in dusty air. */
function rays(g, W, H, cam, scene, amt, src) {
  if (!scene?.sun) return;
  const s = scene.sun, z = s[0] * cam.F[0] + s[1] * cam.F[1] + s[2] * cam.F[2];
  if (z < 0.15) return; // the sun behind the camera throws no shafts at us
  const sx = cam.cx + cam.f * (s[0] * cam.R[0] + s[1] * cam.R[1] + s[2] * cam.R[2]) / z, sy = cam.cy - cam.f * (s[0] * cam.U[0] + s[1] * cam.U[1] + s[2] * cam.U[2]) / z;
  const w = W >> 2, h = H >> 2, a = canvasOf('rayA', w, h), b = canvasOf('rayB', w, h), ga = a.getContext('2d'), gb = b.getContext('2d');
  ga.clearRect(0, 0, w, h); ga.drawImage(src, 0, 0, w, h);
  const im = ga.getImageData(0, 0, w, h), d = im.data;
  for (let i = 0; i < d.length; i += 4) { const l = (0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]) / 255, k = clamp((l - 0.8) / 0.2); d[i] *= k; d[i + 1] *= k * 0.95; d[i + 2] *= k * 0.85; }
  ga.putImageData(im, 0, 0);
  // radial smear towards the sun: the bright picture drawn again and again, each copy a little smaller about the sun
  gb.clearRect(0, 0, w, h); gb.globalCompositeOperation = 'lighter';
  const cx = sx / 4, cy = sy / 4, N = 14;
  for (let k = 0; k < N; k++) { const sc = 1 - k * 0.035; gb.globalAlpha = 0.16 * (1 - k / N); gb.setTransform(sc, 0, 0, sc, cx * (1 - sc), cy * (1 - sc)); gb.drawImage(a, 0, 0); }
  gb.setTransform(1, 0, 0, 1, 0, 0); gb.globalAlpha = 1; gb.globalCompositeOperation = 'source-over';
  // fade with how far the sun is off the picture
  const off = Math.max(0, Math.hypot(sx - W / 2, sy - H / 2) / Math.hypot(W / 2, H / 2) - 0.6);
  g.save(); g.globalCompositeOperation = 'screen'; g.globalAlpha = clamp(amt * (1 - off)); g.drawImage(b, 0, 0, W, H); g.restore();
}

/** Floating motes: specks of dust and pollen near the lens, out of focus, drifting and catching the light. */
function motes(g, W, H, t, cam, amt) {
  const s = H / 1080, n = 26;
  const yaw = Math.atan2(cam.F[0], cam.F[2]), px = cam.pos[0] + cam.pos[2]; // the camera's turn and travel move them (parallax)
  g.save(); g.globalCompositeOperation = 'screen';
  for (let i = 0; i < n; i++) {
    const r1 = hash(i, 1), r2 = hash(i, 2), r3 = hash(i, 3), depth = 0.4 + r3 * 1.6;
    const x = (((r1 * W + t * 14 * s * (0.5 + r2) + yaw * W * 0.9 / depth + px * 40 * s / depth) % W) + W) % W;
    const y = (((r2 * H - t * 9 * s * (0.4 + r1) + Math.sin(t * 0.7 + i) * 12 * s) % H) + H) % H;
    const rad = (5 + 22 * (1 - r3)) * s, a = amt * 0.13 * (0.5 + 0.5 * Math.sin(t * (0.8 + r1) + i * 1.7));
    const gr = g.createRadialGradient(x, y, 0, x, y, rad);
    gr.addColorStop(0, `rgba(255,244,214,${a.toFixed(3)})`); gr.addColorStop(0.55, `rgba(255,236,200,${(a * 0.5).toFixed(3)})`); gr.addColorStop(1, 'rgba(255,230,190,0)');
    g.fillStyle = gr; g.beginPath(); g.arc(x, y, rad, 0, Math.PI * 2); g.fill();
  }
  g.restore();
}

/** Colour fringe at the edges of the picture, as in a real lens: red a hair outwards, blue a hair inwards. */
function fringe(g, W, H, amt, src) {
  const k = 0.003 * amt, c = canvasOf('fringe', W, H), cg = c.getContext('2d'), t = canvasOf('fringeT', W, H), tg = t.getContext('2d');
  cg.globalCompositeOperation = 'source-over'; cg.clearRect(0, 0, W, H);
  // the picture again, each channel taken from a copy of its own size, added together
  for (const [col, sc] of [['#ff0000', 1 + k], ['#00ff00', 1], ['#0000ff', 1 - k]]) {
    tg.globalCompositeOperation = 'source-over'; tg.clearRect(0, 0, W, H);
    tg.setTransform(sc, 0, 0, sc, (W / 2) * (1 - sc), (H / 2) * (1 - sc)); tg.drawImage(src, 0, 0); tg.setTransform(1, 0, 0, 1, 0, 0);
    tg.globalCompositeOperation = 'multiply'; tg.fillStyle = col; tg.fillRect(0, 0, W, H);
    cg.globalCompositeOperation = 'lighter'; cg.drawImage(t, 0, 0);
  }
  // only towards the edges: the middle of the picture stays exactly as it was
  cg.globalCompositeOperation = 'destination-in';
  const gr = cg.createRadialGradient(W / 2, H / 2, H * 0.3, W / 2, H / 2, Math.hypot(W, H) / 2);
  gr.addColorStop(0, 'rgba(0,0,0,0)'); gr.addColorStop(1, 'rgba(0,0,0,1)'); cg.fillStyle = gr; cg.fillRect(0, 0, W, H);
  cg.globalCompositeOperation = 'source-over';
  g.drawImage(c, 0, 0);
}

/** Punch: clean, saturated colour for flat design. */
function punch(g, W, H, src) {
  const c = canvasOf('punch', W, H), cg = c.getContext('2d');
  cg.clearRect(0, 0, W, H); cg.filter = 'saturate(1.35) contrast(1.06)'; cg.drawImage(src, 0, 0); cg.filter = 'none';
  g.drawImage(c, 0, 0);
}

/** Run the look's passes over a finished frame (before the vignette, grain and titles). */
export function postLook(L, g, W, H, ctx) {
  const src = ctx.canvas;
  for (const p of L.post ?? []) {
    if (p === 'paper') paper(g, W, H, L.paperAmt ?? 1);
    else if (p === 'pixel') pixel(g, W, H, L, src);
    else if (p === 'sketch') sketch(g, W, H, ctx.frame ?? 0);
    else if (p === 'manga') manga(g, W, H);
    else if (p === 'bloom') bloom(g, W, H, L.bloom ?? 0.7, src);
    else if (p === 'rays') rays(g, W, H, ctx.cam, ctx.scene, L.rays ?? 0.8, src);
    else if (p === 'motes') motes(g, W, H, ctx.t ?? 0, ctx.cam, L.motes ?? 1);
    else if (p === 'fringe') fringe(g, W, H, L.fringe ?? 1, src);
    else if (p === 'punch') punch(g, W, H, src);
    else if (p === 'grade') grade(g, W, H, L.grade ?? 1, src);
  }
}
/** A painted grade: warm light, cool shadows, a gentle S of contrast (what a background painter does by eye). */
function grade(g, W, H, amt, src) {
  g.save();
  // the picture over itself in overlay: shadows go deeper, lights brighter — the value range a painter gives a scene
  if (src) { const c = canvasOf('gradeA', W, H), gc = c.getContext('2d'); gc.globalCompositeOperation = 'copy'; gc.drawImage(src, 0, 0); g.globalAlpha = 0.32 * amt; g.globalCompositeOperation = 'overlay'; g.drawImage(c, 0, 0); g.globalAlpha = 1; }
  g.globalCompositeOperation = 'soft-light'; g.fillStyle = `rgba(255,206,160,${0.22 * amt})`; g.fillRect(0, 0, W, H);
  g.globalCompositeOperation = 'multiply'; g.fillStyle = `rgba(214,222,255,${0.12 * amt})`; g.fillRect(0, 0, W, H);
  g.restore();
}

/** The small jolt of a puppet piece from one drawing to the next (pixels at 1080p), stable while a drawing is held. */
export function jolt(L, id, t, fps, H) {
  if (!L.jitter) return [0, 0];
  const step = Math.floor(t * fps / (L.hold ?? 3) + 1e-6), k = id.length * 31 + id.charCodeAt(0);
  return [(hash(step, k) - 0.5) * 2 * L.jitter * H / 1080, (hash(step, k + 7) - 0.5) * 2 * L.jitter * H / 1080];
}
