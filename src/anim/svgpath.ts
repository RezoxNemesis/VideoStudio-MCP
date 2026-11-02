/** Flatten SVG path data (M L H V C S Q T A Z, absolute and relative) into polylines of [x, y] points. */

export function flattenPath(d: string, tolerance = 1.5): number[][] {
  const toks = d.match(/[a-zA-Z]|-?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?/g) ?? [];
  const out: number[][] = [];
  let cur: number[] = [];
  let i = 0;
  let cmd = "";
  let x = 0,
    y = 0,
    sx = 0,
    sy = 0,
    lcx = 0,
    lcy = 0,
    lqx = 0,
    lqy = 0;
  let prev = "";
  const num = () => {
    const t = toks[i++];
    if (t === undefined || /[a-zA-Z]/.test(t)) throw new Error("Bad SVG path data.");
    return parseFloat(t);
  };
  const push = (px: number, py: number) => cur.push(px, py);
  /** Straight segments are densified so the renderer's smoothing keeps their corners sharp. */
  const line = (px: number, py: number) => {
    const lx = cur.length >= 2 ? cur[cur.length - 2]! : x;
    const ly = cur.length >= 2 ? cur[cur.length - 1]! : y;
    const n = Math.max(1, Math.min(64, Math.ceil(Math.hypot(px - lx, py - ly) / 10)));
    if (n > 1) {
      // Keep a point right next to each corner so the curve hugs it.
      push(lx + (px - lx) * 0.02, ly + (py - ly) * 0.02);
      for (let k = 1; k < n; k++) push(lx + ((px - lx) * k) / n, ly + ((py - ly) * k) / n);
      push(lx + (px - lx) * 0.98, ly + (py - ly) * 0.98);
    }
    push(px, py);
  };
  const steps = (len: number) => Math.max(4, Math.min(64, Math.ceil(len / (tolerance * 4))));
  const cubic = (x1: number, y1: number, x2: number, y2: number, x3: number, y3: number) => {
    const n = steps(Math.hypot(x1 - x, y1 - y) + Math.hypot(x2 - x1, y2 - y1) + Math.hypot(x3 - x2, y3 - y2));
    for (let k = 1; k <= n; k++) {
      const t = k / n;
      const a = (1 - t) ** 3,
        b = 3 * (1 - t) ** 2 * t,
        c = 3 * (1 - t) * t * t,
        e = t ** 3;
      push(a * x + b * x1 + c * x2 + e * x3, a * y + b * y1 + c * y2 + e * y3);
    }
    lcx = x2;
    lcy = y2;
    x = x3;
    y = y3;
  };
  const quad = (x1: number, y1: number, x2: number, y2: number) => {
    const n = steps(Math.hypot(x1 - x, y1 - y) + Math.hypot(x2 - x1, y2 - y1));
    for (let k = 1; k <= n; k++) {
      const t = k / n;
      push((1 - t) ** 2 * x + 2 * (1 - t) * t * x1 + t * t * x2, (1 - t) ** 2 * y + 2 * (1 - t) * t * y1 + t * t * y2);
    }
    lqx = x1;
    lqy = y1;
    x = x2;
    y = y2;
  };
  const arc = (rx: number, ry: number, phi: number, large: number, sweep: number, x2: number, y2: number) => {
    // Endpoint → centre parameterisation (SVG spec F.6.5).
    if (rx === 0 || ry === 0) {
      push(x2, y2);
      x = x2;
      y = y2;
      return;
    }
    rx = Math.abs(rx);
    ry = Math.abs(ry);
    const p = (phi * Math.PI) / 180;
    const cp = Math.cos(p),
      sp = Math.sin(p);
    const dx = (x - x2) / 2,
      dy = (y - y2) / 2;
    const x1p = cp * dx + sp * dy,
      y1p = -sp * dx + cp * dy;
    const lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
    if (lam > 1) {
      rx *= Math.sqrt(lam);
      ry *= Math.sqrt(lam);
    }
    const sign = large === sweep ? -1 : 1;
    const num2 = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
    const co = sign * Math.sqrt(Math.max(0, num2 / (rx * rx * y1p * y1p + ry * ry * x1p * x1p)));
    const cxp = (co * rx * y1p) / ry,
      cyp = (-co * ry * x1p) / rx;
    const cx = cp * cxp - sp * cyp + (x + x2) / 2,
      cy = sp * cxp + cp * cyp + (y + y2) / 2;
    const ang = (ux: number, uy: number, vx: number, vy: number) => Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    const t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
    let dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
    if (!sweep && dt > 0) dt -= 2 * Math.PI;
    if (sweep && dt < 0) dt += 2 * Math.PI;
    const n = steps(Math.abs(dt) * Math.max(rx, ry));
    for (let k = 1; k <= n; k++) {
      const t = t1 + (dt * k) / n;
      push(cx + rx * Math.cos(t) * cp - ry * Math.sin(t) * sp, cy + rx * Math.cos(t) * sp + ry * Math.sin(t) * cp);
    }
    x = x2;
    y = y2;
  };
  while (i < toks.length) {
    const t = toks[i]!;
    if (/[a-zA-Z]/.test(t)) {
      cmd = t;
      i++;
    } else if (!cmd) throw new Error("SVG path must start with M.");
    const rel = cmd === cmd.toLowerCase();
    const ox = rel ? x : 0,
      oy = rel ? y : 0;
    switch (cmd.toUpperCase()) {
      case "M": {
        if (cur.length >= 4) out.push(cur);
        x = num() + ox;
        y = num() + oy;
        sx = x;
        sy = y;
        cur = [x, y];
        cmd = rel ? "l" : "L";
        break;
      }
      case "L": {
        const nx = num() + ox;
        const ny = num() + oy;
        line(nx, ny);
        x = nx;
        y = ny;
        break;
      }
      case "H": {
        const nx = num() + ox;
        line(nx, y);
        x = nx;
        break;
      }
      case "V": {
        const ny = num() + oy;
        line(x, ny);
        y = ny;
        break;
      }
      case "C": {
        const a = num() + ox,
          b = num() + oy,
          c = num() + ox,
          e = num() + oy,
          f = num() + ox,
          g = num() + oy;
        cubic(a, b, c, e, f, g);
        break;
      }
      case "S": {
        const r1x = /[CS]/i.test(prev) ? 2 * x - lcx : x;
        const r1y = /[CS]/i.test(prev) ? 2 * y - lcy : y;
        const c = num() + ox,
          e = num() + oy,
          f = num() + ox,
          g = num() + oy;
        cubic(r1x, r1y, c, e, f, g);
        break;
      }
      case "Q": {
        const a = num() + ox,
          b = num() + oy,
          c = num() + ox,
          e = num() + oy;
        quad(a, b, c, e);
        break;
      }
      case "T": {
        const r1x = /[QT]/i.test(prev) ? 2 * x - lqx : x;
        const r1y = /[QT]/i.test(prev) ? 2 * y - lqy : y;
        quad(r1x, r1y, num() + ox, num() + oy);
        break;
      }
      case "A": {
        const rx = num(),
          ry = num(),
          phi = num(),
          large = num(),
          sweep = num();
        arc(rx, ry, phi, large, sweep, num() + ox, num() + oy);
        break;
      }
      case "Z":
        line(sx, sy);
        x = sx;
        y = sy;
        if (cur.length >= 4) out.push(cur);
        cur = [];
        break;
      default:
        throw new Error(`Unsupported SVG path command ${cmd}`);
    }
    prev = cmd;
  }
  if (cur.length >= 4) out.push(cur);
  return out;
}
