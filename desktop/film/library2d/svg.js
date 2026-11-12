// The drawing kit of the 2D library: seeded randomness, shapes as SVG paths, gradients, and a document wrapper.
// Every asset is drawn by code, so the library is the same on every machine and costs nothing to ship.
export const INK = '#2a2433';
export function rng(seed) { let s = (Math.floor(seed * 9301 + 49297) % 233280 + 233280) % 233280 || 1; return () => (s = (s * 16807) % 2147483647) / 2147483647; }
export const f = (n) => (Math.round(n * 10) / 10).toString();
export const P = (pts) => `M${pts.map((p) => `${f(p[0])} ${f(p[1])}`).join(' L')}Z`;
export const open = (pts) => `M${pts.map((p) => `${f(p[0])} ${f(p[1])}`).join(' L')}`;
/** a smooth closed curve through points (Catmull-Rom as cubic Béziers) */
export function smooth(pts, closed = true, k = 1) {
  const n = pts.length; if (n < 3) return P(pts);
  const g = (i) => pts[closed ? (i + n) % n : Math.max(0, Math.min(n - 1, i))];
  let d = `M${f(pts[0][0])} ${f(pts[0][1])}`;
  for (let i = 0; i < (closed ? n : n - 1); i++) {
    const p0 = g(i - 1), p1 = g(i), p2 = g(i + 1), p3 = g(i + 2);
    const c1 = [p1[0] + (p2[0] - p0[0]) / 6 * k, p1[1] + (p2[1] - p0[1]) / 6 * k], c2 = [p2[0] - (p3[0] - p1[0]) / 6 * k, p2[1] - (p3[1] - p1[1]) / 6 * k];
    d += ` C${f(c1[0])} ${f(c1[1])} ${f(c2[0])} ${f(c2[1])} ${f(p2[0])} ${f(p2[1])}`;
  }
  return d + (closed ? 'Z' : '');
}
/** a star/burst: n points between radii r0 (inner) and r1 (outer), jitter 0…1 */
export function burst(cx, cy, n, r0, r1, jit = 0, seed = 1, rot = 0) {
  const R = rng(seed), pts = [];
  for (let i = 0; i < n * 2; i++) { const a = rot + (i / (n * 2)) * Math.PI * 2, r = (i % 2 ? r0 : r1) * (1 + (R() - 0.5) * jit); pts.push([cx + Math.cos(a) * r, cy + Math.sin(a) * r]); }
  return P(pts);
}
/** a soft blob (a cloud lobe, a puff): n points round a centre with a wobble */
export function blob(cx, cy, rx, ry, wob = 0.18, seed = 1, n = 10) {
  const R = rng(seed), pts = [];
  for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2, k = 1 + (R() - 0.5) * wob * 2; pts.push([cx + Math.cos(a) * rx * k, cy + Math.sin(a) * ry * k]); }
  return smooth(pts);
}
export const ellipse = (cx, cy, rx, ry) => `M${f(cx - rx)} ${f(cy)} a${f(rx)} ${f(ry)} 0 1 0 ${f(rx * 2)} 0 a${f(rx)} ${f(ry)} 0 1 0 ${f(-rx * 2)} 0Z`;
export const rrect = (x, y, w, h, r) => `M${f(x + r)} ${f(y)} H${f(x + w - r)} Q${f(x + w)} ${f(y)} ${f(x + w)} ${f(y + r)} V${f(y + h - r)} Q${f(x + w)} ${f(y + h)} ${f(x + w - r)} ${f(y + h)} H${f(x + r)} Q${f(x)} ${f(y + h)} ${f(x)} ${f(y + h - r)} V${f(y + r)} Q${f(x)} ${f(y)} ${f(x + r)} ${f(y)}Z`;
/** a capsule from a to b with radii ra, rb (a limb, a finger) */
export function capsule(a, b, ra, rb = ra, n = 8) {
  const dx = b[0] - a[0], dy = b[1] - a[1], L = Math.hypot(dx, dy) || 1, ux = dx / L, uy = dy / L, px = -uy, py = ux, pts = [];
  // round the far end from +p through +u to −p, then the near end from −p through −u back to +p
  for (let i = 0; i <= n; i++) { const t = (i / n) * Math.PI, c = Math.cos(t), s2 = Math.sin(t); pts.push([b[0] + (px * c + ux * s2) * rb, b[1] + (py * c + uy * s2) * rb]); }
  for (let i = 0; i <= n; i++) { const t = (i / n) * Math.PI, c = Math.cos(t), s2 = Math.sin(t); pts.push([a[0] + (-px * c - ux * s2) * ra, a[1] + (-py * c - uy * s2) * ra]); }
  return P(pts);
}
export function hexToRgb(h) { const x = h.replace('#', ''); const n = parseInt(x.length === 3 ? x.split('').map((c) => c + c).join('') : x, 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
export const rgbHex = (c) => '#' + c.map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
/** lighter (k > 0) or darker (k < 0) */
export const shade = (h, k) => { const c = hexToRgb(h); return rgbHex(c.map((v) => (k >= 0 ? v + (255 - v) * k : v * (1 + k)))); };
export const mix = (a, b, t) => { const x = hexToRgb(a), y = hexToRgb(b); return rgbHex(x.map((v, i) => v + (y[i] - v) * t)); };
let gid = 0;
/** A document being drawn: defs (gradients, filters) and body; .svg() gives the file. */
export class Doc {
  constructor(w, h, o = {}) { this.w = w; this.h = h; this.defs = []; this.body = []; this.o = o; this.k = (o.seed ?? 0) * 1000 + (++gid); }
  id(p) { return `${p}${this.k}_${this.defs.length}`; }
  lin(stops, x1 = 0, y1 = 0, x2 = 0, y2 = 1) { const id = this.id('l'); this.defs.push(`<linearGradient id="${id}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}">${stops.map(([o, c, a = 1]) => `<stop offset="${o}" stop-color="${c}" stop-opacity="${a}"/>`).join('')}</linearGradient>`); return `url(#${id})`; }
  /** a gradient in the picture's own units (from x1, y1 to x2, y2), for shading that follows a shape's axis */
  linU(stops, x1, y1, x2, y2) { const id = this.id('u'); this.defs.push(`<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${f(x1)}" y1="${f(y1)}" x2="${f(x2)}" y2="${f(y2)}">${stops.map(([o, c, a = 1]) => `<stop offset="${o}" stop-color="${c}" stop-opacity="${a}"/>`).join('')}</linearGradient>`); return `url(#${id})`; }
  rad(stops, cx = 0.5, cy = 0.5, r = 0.5, fx, fy) { const id = this.id('r'); this.defs.push(`<radialGradient id="${id}" cx="${cx}" cy="${cy}" r="${r}"${fx != null ? ` fx="${fx}" fy="${fy}"` : ''}>${stops.map(([o, c, a = 1]) => `<stop offset="${o}" stop-color="${c}" stop-opacity="${a}"/>`).join('')}</radialGradient>`); return `url(#${id})`; }
  blur(sd) { const id = this.id('b'); this.defs.push(`<filter id="${id}" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="${sd}"/></filter>`); return `url(#${id})`; }
  path(d, fill, o = {}) { this.body.push(`<path d="${d}" fill="${fill ?? 'none'}"${o.stroke ? ` stroke="${o.stroke}" stroke-width="${f(o.sw ?? 2)}" stroke-linejoin="round" stroke-linecap="round"` : ''}${o.op != null ? ` opacity="${o.op}"` : ''}${o.filter ? ` filter="${o.filter}"` : ''}${o.tr ? ` transform="${o.tr}"` : ''}${o.rule ? ` fill-rule="${o.rule}"` : ''}${o.clip ? ` clip-path="${o.clip}"` : ''}/>`); return this; }
  /** a clip shape: paths drawn with { clip } only show inside it */
  clip(d) { const id = this.id('c'); this.defs.push(`<clipPath id="${id}"><path d="${d}"/></clipPath>`); return `url(#${id})`; }
  /** a filled shape with an ink outline (the anime way) */
  ink(d, fill, sw = 2.4, ink = INK, o = {}) { return this.path(d, fill, { ...o, stroke: ink, sw }); }
  circle(cx, cy, r, fill, o = {}) { return this.path(ellipse(cx, cy, r, r), fill, o); }
  raw(s) { this.body.push(s); return this; }
  group(inner, tr) { this.body.push(`<g${tr ? ` transform="${tr}"` : ''}>`); inner(this); this.body.push('</g>'); return this; }
  text(x, y, s, o = {}) { this.body.push(`<text x="${f(x)}" y="${f(y)}" font-family="${o.font ?? 'Inter, DejaVu Sans, sans-serif'}" font-size="${o.size ?? 24}" font-weight="${o.weight ?? 700}" fill="${o.fill ?? INK}" text-anchor="${o.anchor ?? 'middle'}"${o.stroke ? ` stroke="${o.stroke}" stroke-width="${o.sw ?? 3}" paint-order="stroke"` : ''}>${String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')}</text>`); return this; }
  svg() { return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${this.w} ${this.h}" width="${this.w}" height="${this.h}">${this.defs.length ? `<defs>${this.defs.join('')}</defs>` : ''}${this.body.join('')}</svg>`; }
}
