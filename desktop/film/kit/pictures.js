// Pictures on surfaces: a photograph in a hand, a poster on a wall, a face in the sky. A film keeps the picture files
// beside its code (assets/…) and draws them on a face of a Mesh — lit like that face — or over the frame.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { loadImage } from '@napi-rs/canvas';

const CACHE = new Map();
/**
 * A picture file (png, jpg) as something a canvas draws. It is read once and must be waited for — at the top of a
 * film file:   const PHOTO = await picture(new URL('../assets/photo.png', import.meta.url));
 * then in a face's detail onFace(g, F, PHOTO), or in a shot's `top` / `over`: g.drawImage(PHOTO, x, y, w, h).
 */
export function picture(file) {
  const f = file instanceof URL || String(file).startsWith('file:') ? fileURLToPath(file) : String(file);
  let im = CACHE.get(f);
  if (!im) { im = loadImage(fs.readFileSync(f)); CACHE.set(f, im); }
  return im;
}

/**
 * Draw a picture on a face of a Mesh, inside the face's detail: detail: (g, F) => onFace(g, F, img, 0.06, 0.06, 0.94, 0.94).
 * u0, v0, u1, v1: the part of the face it covers (u from left to right, v up). o: { fit: 'cover' (cropped to fill, the
 * default) | 'fill' (stretched), lit: false (keep the picture's own colours: a screen that glows), alpha }.
 */
export function onFace(g, F, img, u0 = 0, v0 = 0, u1 = 1, v1 = 1, o = {}) {
  // the part of the face as the picture sees it: its top left corner, the way along its top, the way down its side
  const p0 = F.uv(u0, v1), pu = F.uv(u1, v1), pv = F.uv(u0, v0), ex = [pu[0] - p0[0], pu[1] - p0[1]], ey = [pv[0] - p0[0], pv[1] - p0[1]];
  if (Math.hypot(ex[0], ex[1]) < 2 || Math.hypot(ey[0], ey[1]) < 2) return; // too small to see
  // its true shape in metres where the face knows it (so a face seen at a slant is not cropped by its squashed picture)
  const w = (u1 - u0) * (F.w > 0 ? F.w : Math.hypot(ex[0], ex[1])), h = (v1 - v0) * (F.h > 0 ? F.h : Math.hypot(ey[0], ey[1]));
  let sx = 0, sy = 0, sw = img.width, sh = img.height;
  if ((o.fit ?? 'cover') === 'cover') { const ra = w / h, ri = sw / sh; if (ri > ra) { const nw = sh * ra; sx = (sw - nw) / 2; sw = nw; } else { const nh = sw / ra; sy = (sh - nh) / 2; sh = nh; } }
  // (drawn in the picture's own pixels, the face folded into the transform: a canvas does not draw a picture a fifth of a unit wide)
  g.save(); g.transform(ex[0] / sw, ex[1] / sw, ey[0] / sh, ey[1] / sh, p0[0], p0[1]);
  if (o.alpha !== undefined) g.globalAlpha *= o.alpha;
  g.drawImage(img, sx, sy, sw, sh, 0, 0, sw, sh);
  // lit like the face it is on: the light that falls on a white card there is multiplied over it
  if (o.lit !== false && F.col) { g.globalCompositeOperation = 'multiply'; g.fillStyle = F.col('#ffffff'); g.fillRect(0, 0, sw, sh); }
  g.restore();
}
