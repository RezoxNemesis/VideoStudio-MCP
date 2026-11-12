// 2D drawing helpers: capsules, smooth outlines, ink-then-fill groups, cel shading.

/** Add a tapered capsule (two circles and their outer tangents) to the current path. */
export function capsule(g, x0, y0, r0, x1, y1, r1) {
  const dx = x1 - x0, dy = y1 - y0, L = Math.hypot(dx, dy);
  if (L <= Math.abs(r0 - r1) + 1e-6) {
    if (r0 >= r1) { g.moveTo(x0 + r0, y0); g.arc(x0, y0, r0, 0, Math.PI * 2); } else { g.moveTo(x1 + r1, y1); g.arc(x1, y1, r1, 0, Math.PI * 2); }
    g.closePath();
    return;
  }
  const ux = dx / L, uy = dy / L, s = (r0 - r1) / L, c = Math.sqrt(Math.max(0, 1 - s * s));
  // common normals of the two outer tangent lines: n = s·u ± c·perp(u)
  const a = Math.atan2(uy, ux), b = Math.acos(Math.max(-1, Math.min(1, s)));
  g.moveTo(x0 + r0 * Math.cos(a + b), y0 + r0 * Math.sin(a + b));
  g.arc(x0, y0, r0, a + b, a - b + Math.PI * 2, false);
  g.lineTo(x1 + r1 * Math.cos(a - b), y1 + r1 * Math.sin(a - b));
  g.arc(x1, y1, r1, a - b, a + b, false);
  g.closePath();
  void c; void ux; void uy;
}

/** The sides and far end of a capsule, open at its start (for a limb that grows out of a body without a seam). */
export function capsuleOpen(g, x0, y0, r0, x1, y1, r1) {
  const dx = x1 - x0, dy = y1 - y0, L = Math.hypot(dx, dy);
  if (L <= Math.abs(r0 - r1) + 1e-6) return;
  const s = (r0 - r1) / L, a = Math.atan2(dy, dx), b = Math.acos(Math.max(-1, Math.min(1, s)));
  g.moveTo(x0 + r0 * Math.cos(a - b), y0 + r0 * Math.sin(a - b));
  g.lineTo(x1 + r1 * Math.cos(a - b), y1 + r1 * Math.sin(a - b));
  g.arc(x1, y1, r1, a - b, a + b, false);
  g.lineTo(x0 + r0 * Math.cos(a + b), y0 + r0 * Math.sin(a + b));
}

/** Only the two long sides of a capsule (no round ends): the outline of a limb segment whose ends meet other segments. */
export function capsuleSides(g, x0, y0, r0, x1, y1, r1) {
  const dx = x1 - x0, dy = y1 - y0, L = Math.hypot(dx, dy);
  if (L <= Math.abs(r0 - r1) + 1e-6) return;
  const s = (r0 - r1) / L, a = Math.atan2(dy, dx), b = Math.acos(Math.max(-1, Math.min(1, s)));
  g.moveTo(x0 + r0 * Math.cos(a - b), y0 + r0 * Math.sin(a - b)); g.lineTo(x1 + r1 * Math.cos(a - b), y1 + r1 * Math.sin(a - b));
  g.moveTo(x0 + r0 * Math.cos(a + b), y0 + r0 * Math.sin(a + b)); g.lineTo(x1 + r1 * Math.cos(a + b), y1 + r1 * Math.sin(a + b));
}
/** The outline round the outside of a bent joint (elbow, knee) at c with radius r, between the segment a→c and the segment c→b. */
export function jointArc(g, ax, ay, cx, cy, bx, by, r) {
  let ux = cx - ax, uy = cy - ay, fx = bx - cx, fy = by - cy; const lu = Math.hypot(ux, uy), lf = Math.hypot(fx, fy);
  if (lu < 1e-3 || lf < 1e-3) return; ux /= lu; uy /= lu; fx /= lf; fy /= lf;
  const cr = ux * fy - uy * fx; if (Math.abs(cr) < 0.02) return;
  const sg = cr > 0 ? -1 : 1, a0 = Math.atan2(ux * sg, -uy * sg), a1 = Math.atan2(fx * sg, -fy * sg);
  let d = a1 - a0; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI;
  g.moveTo(cx + r * Math.cos(a0), cy + r * Math.sin(a0)); g.arc(cx, cy, r, a0, a0 + d, d < 0);
}

/** Closed shapes all wind the same way (as capsules and ellipses do), so that shapes added to one path unite instead of cutting holes. */
function clockwise(pts) {
  let s = 0;
  for (let i = 0, n = pts.length; i < n; i++) { const a = pts[i], b = pts[(i + 1) % n]; s += a[0] * b[1] - b[0] * a[1]; }
  return s >= 0 ? pts : [...pts].reverse();
}

/** A closed smooth curve through points (Catmull-Rom as Béziers). tension 0 = polygon, 1 = round. */
export function smoothClosed(g, pts, tension = 1) {
  const n = pts.length;
  if (n < 3) return;
  pts = clockwise(pts);
  const k = tension / 6;
  g.moveTo(pts[0][0], pts[0][1]);
  for (let i = 0; i < n; i++) {
    const p0 = pts[(i - 1 + n) % n], p1 = pts[i], p2 = pts[(i + 1) % n], p3 = pts[(i + 2) % n];
    g.bezierCurveTo(p1[0] + (p2[0] - p0[0]) * k, p1[1] + (p2[1] - p0[1]) * k, p2[0] - (p3[0] - p1[0]) * k, p2[1] - (p3[1] - p1[1]) * k, p2[0], p2[1]);
  }
  g.closePath();
}
/** An open smooth curve through points. `move` false continues the current sub-path with a line to the first point. */
export function smoothOpen(g, pts, tension = 1, move = true) {
  const n = pts.length;
  if (!n) return;
  if (move) g.moveTo(pts[0][0], pts[0][1]); else g.lineTo(pts[0][0], pts[0][1]);
  const k = tension / 6;
  for (let i = 0; i < n - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(n - 1, i + 2)];
    g.bezierCurveTo(p1[0] + (p2[0] - p0[0]) * k, p1[1] + (p2[1] - p0[1]) * k, p2[0] - (p3[0] - p1[0]) * k, p2[1] - (p3[1] - p1[1]) * k, p2[0], p2[1]);
  }
}
export function poly(g, pts) {
  if (!pts.length) return;
  pts = clockwise(pts);
  g.moveTo(pts[0][0], pts[0][1]);
  for (let i = 1; i < pts.length; i++) g.lineTo(pts[i][0], pts[i][1]);
  g.closePath();
}

/**
 * Draw one group of shapes the way a cel is painted: ink outline of the whole group, flat fill, then a shadow on the
 * side away from the light (and an optional rim on the lit side).
 *   path(g): adds every sub-path of the group (no beginPath)
 *   o: { fill, shade, ink, lw, light: [x, y] unit towards the light in the picture, amt (px), rim, rimW }
 */
export function cel(g, path, o) {
  if (o.ink && o.lw > 0) {
    g.beginPath(); (o.inkPath ?? path)(g);
    g.lineJoin = 'round'; g.lineCap = 'round'; g.lineWidth = o.lw * 2; g.strokeStyle = o.ink; g.stroke();
  }
  g.beginPath(); path(g); g.fillStyle = o.fill; g.fill();
  if (!o.shade || !(o.amt > 0.9)) return;
  const lx = o.light?.[0] ?? -0.6, ly = o.light?.[1] ?? -0.8;
  g.save();
  g.beginPath(); path(g); g.clip();
  if (o.rim) {
    // rim on the lit edge, then everything else shifted away from the light
    g.fillStyle = o.rim; g.beginPath(); path(g); g.fill();
    const rw = o.rimW ?? 2;
    g.translate(-lx * rw, -ly * rw); g.beginPath(); path(g); g.clip(); g.translate(lx * rw, ly * rw);
  }
  g.fillStyle = o.shade; g.beginPath(); path(g); g.fill();
  g.translate(lx * o.amt, ly * o.amt);
  g.fillStyle = o.fill; g.beginPath(); path(g); g.fill();
  g.restore();
}

/** Convex hull of 2D points (Andrew's monotone chain), counter-clockwise. */
export function hull(pts) {
  const p = [...pts].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  if (p.length < 3) return p;
  const cr = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lo = [];
  for (const q of p) { while (lo.length >= 2 && cr(lo[lo.length - 2], lo[lo.length - 1], q) <= 0) lo.pop(); lo.push(q); }
  const up = [];
  for (let i = p.length - 1; i >= 0; i--) { const q = p[i]; while (up.length >= 2 && cr(up[up.length - 2], up[up.length - 1], q) <= 0) up.pop(); up.push(q); }
  lo.pop(); up.pop();
  return lo.concat(up);
}
