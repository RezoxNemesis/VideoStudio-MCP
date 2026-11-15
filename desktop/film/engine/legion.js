// A legion: an army of drawn figures. Every soldier is a drawing of one of a few figures (real performers, see
// kit/legions.js), made once at the size and from the side the lens sees him and laid down wherever a man stands:
// near men large and covering the far, far men paling into the air and at last only marks along the horizon, all of
// them in step when they march. Ten thousand men cost little more than the few drawings they are made from.
import { createCanvas } from '@napi-rs/canvas';
import { V, clamp, hash, hex, rgb } from './core.js';
import { makeCam } from './camera.js';

const SIZES = [24, 48, 96, 192, 384, 768]; // pixels a figure's height is drawn at (a man is laid down from the next larger)
const EL = [3, 20, 42, 68], EL_TO = [11, 31, 55]; // heights the lens can look down from (degrees), and where one gives way to the next
const AZ = 15; // degrees between the sides a figure is drawn from

/**
 * Men in ranks and files: o: { at: [x, z] (the middle of the front rank), yaw (degrees: which way they face), files (men
 * across), ranks (rows deep), dx: 1.1, dz: 1.2 (metres between men), jitter: 0.12 (how far from the line a man may stand),
 * seed, skip(rank, file) → true to leave a place empty, figs: 1 (how many figures to deal among them) }
 * → [{ x, z, k (his size), fig, rank }]
 */
export function ranks(o) {
  const yaw = ((o.yaw ?? 0) * Math.PI) / 180, fwd = [Math.sin(yaw), Math.cos(yaw)], side = [Math.cos(yaw), -Math.sin(yaw)], dx = o.dx ?? 1.1, dz = o.dz ?? 1.2, j = o.jitter ?? 0.12, seed = o.seed ?? 1, out = [];
  for (let r = 0; r < o.ranks; r++) for (let c = 0; c < o.files; c++) {
    if (o.skip && o.skip(r, c)) continue;
    const h1 = hash(r * 13.7 + c * 3.1 + seed), h2 = hash(r * 5.3 + c * 9.7 + seed * 2), s = (c - (o.files - 1) / 2) * dx + (h1 - 0.5) * 2 * j, b = -r * dz + (h2 - 0.5) * 2 * j;
    out.push({ x: o.at[0] + side[0] * s + fwd[0] * b, z: o.at[1] + side[1] * s + fwd[1] * b, k: 0.95 + 0.1 * hash(r * 2.1 + c * 7.7 + seed), fig: Math.floor(hash(r * 1.3 + c * 2.9 + seed * 3) * (o.figs ?? 1)), rank: r });
  }
  return out;
}

/**
 * o: { men: [{ x, z, k?, fig?, ph? (his own place in the stride, 0…1: 0 = in step), rank? }], yaw (degrees: which way they
 *      face), figures: [{ h (m, a man's height), top (m, how high anything of his reaches: a spear), half (m, half his
 *      width with what he carries), poses (drawings in his loop), mark (his colour when he is only a mark),
 *      paint(g, cam, i, { at, yaw, sun }) draws drawing i of him standing at `at`, facing `yaw` }],
 *      state(t) → { advance (m gone forward), phase (how far through the stride, in strides) }, shadow: true,
 *      keepMB: 220 (how much memory the drawings may take; those not needed any more are let go) }
 * A figure that WAITS ({ dur: seconds of its loop, swing: true = played forth and back, poses: drawings of it }) is
 * played by the legion itself when there is no `state`: every man at his own moment of the wait and at his own pace
 * (m.ph gives him one), near men on finer moments than far men — a standing army is never a still picture.
 * Returns world items (one a rank, and the ground they darken) for a place or a shot's dyn list; `.kept()` says how
 * many drawings are held.
 */
export function legion(o) {
  const yawDeg = o.yaw ?? 0, yaw = (yawDeg * Math.PI) / 180, fwd = [Math.sin(yaw), 0, Math.cos(yaw)], figs = o.figures;
  // the men by rank (given, or by how far along their facing they stand)
  const rows = new Map();
  for (const m of o.men) { const key = m.rank ?? Math.round((m.x * fwd[0] + m.z * fwd[2]) / 1.0); if (!rows.has(key)) rows.set(key, []); rows.get(key).push({ k: 1, fig: 0, ...m }); }
  const sprites = new Map(); let tick = 0, bytes = 0;
  const budget = (o.keepMB ?? 220) * 1048576, keepN = o.keep ?? Infinity;
  /** the drawing of a figure at a size, from a side: { cv, ax, ay (where his feet are in it), ppm } */
  const sprite = (fi, i, azB, elB, S, sc) => {
    const key = `${fi}|${i}|${azB}|${elB}|${S}`;
    let s = sprites.get(key);
    if (!s) {
      const F = figs[fi], ppm = S / F.h, w = Math.ceil(2 * F.half * ppm) + 8, h = Math.ceil(F.top * ppm * 1.03) + 8, D = 30;
      const az = yaw + (azB * AZ * Math.PI) / 180, el = (EL[elB] * Math.PI) / 180, tgt = [0, F.top * 0.5, 0];
      const pos = [tgt[0] + Math.sin(az) * Math.cos(el) * D, tgt[1] + Math.sin(el) * D, tgt[2] + Math.cos(az) * Math.cos(el) * D];
      const cam = makeCam({ pos, target: tgt, fov: (2 * Math.atan(h / 2 / (ppm * D)) * 180) / Math.PI }, w, h), cv = createCanvas(w, h), g = cv.getContext('2d');
      F.paint(g, cam, i, { at: [0, 0, 0], yaw: yawDeg, sun: sc.sun, sc });
      const a = cam.project([0, 0, 0]);
      s = { cv, ax: a[0], ay: a[1], ppm: cam.f / a[2], tint: new Map(), bytes: w * h * 4 };
      sprites.set(key, s); bytes += s.bytes;
    }
    s.used = tick;
    return s;
  };
  /** let go of drawings that no rank has asked for since the pass before last (a long shot must not fill the memory) */
  const tidy = () => {
    if (bytes <= budget && sprites.size <= keepN) return;
    const old = [...sprites.entries()].filter(([, v]) => v.used < tick - rows.size * 2).sort((a, b) => a[1].used - b[1].used);
    for (const [k, v] of old) { if (bytes <= budget * 0.8 && sprites.size <= keepN) break; sprites.delete(k); bytes -= v.bytes * (1 + v.tint.size); }
  };
  /** the same drawing as it looks through so much air (q eighths of the way to the colour of the air) */
  const aired = (s, q, sc) => {
    if (!q) return s.cv;
    let cv = s.tint.get(q);
    if (!cv) {
      cv = createCanvas(s.cv.width, s.cv.height); const g = cv.getContext('2d');
      g.drawImage(s.cv, 0, 0); g.globalCompositeOperation = 'source-atop'; g.globalAlpha = q / 8; g.fillStyle = rgb(sc.haze); g.fillRect(0, 0, cv.width, cv.height);
      s.tint.set(q, cv); bytes += s.bytes;
    }
    return cv;
  };
  const items = [];
  for (const [, men] of rows) {
    const cx = men.reduce((n, m) => n + m.x, 0) / men.length, cz = men.reduce((n, m) => n + m.z, 0) / men.length;
    items.push({
      p: (t) => { const a = o.state ? (o.state(t).advance ?? 0) : 0; return [cx + fwd[0] * a, 0, cz + fwd[2] * a]; },
      r: 14,
      draw(g, cam, sc, t) {
        const st = o.state ? o.state(t) : {}, adv = st.advance ?? 0, phase = st.phase ?? 0, list = []; tick++;
        for (const m of men) {
          const x = m.x + fwd[0] * adv, z = m.z + fwd[2] * adv, dx = x - cam.pos[0], dy = -cam.pos[1], dz = z - cam.pos[2];
          const zc = dx * cam.F[0] + dy * cam.F[1] + dz * cam.F[2]; if (zc < 0.8) continue;
          const sx = cam.cx + (cam.f * (dx * cam.R[0] + dy * cam.R[1] + dz * cam.R[2])) / zc, F = figs[m.fig % figs.length], hpx = (F.h * m.k * cam.f) / zc;
          if (sx < -hpx * 1.5 || sx > cam.W + hpx * 1.5) continue;
          const sy = cam.cy - (cam.f * (dx * cam.U[0] + dy * cam.U[1] + dz * cam.U[2])) / zc; if (sy < -4 || sy - hpx * (F.top / F.h) > cam.H + 4) continue;
          list.push([zc, m, sx, sy, hpx, dx, dz]);
        }
        list.sort((a, b) => b[0] - a[0]);
        for (const [zc, m, sx, sy, hpx, dx, dz] of list) {
          const fi = m.fig % figs.length, F = figs[fi], d = Math.hypot(dx, dz), q = Math.round(sc.fog(d) * 8);
          if (hpx < 4) {
            // too small to be a figure: a mark, so that the ranks reach to the horizon
            const c = hex(F.mark ?? '#3a3038'), a = q / 8, w = Math.max(1, hpx * 0.34);
            g.fillStyle = `rgb(${(c[0] + (sc.haze[0] - c[0]) * a) | 0},${(c[1] + (sc.haze[1] - c[1]) * a) | 0},${(c[2] + (sc.haze[2] - c[2]) * a) | 0})`;
            g.fillRect(sx - w / 2, sy - Math.max(1.5, hpx), w, Math.max(1.5, hpx));
            continue;
          }
          // which drawing: its size, the side and the height the lens sees him from, where he is in the stride
          let S = SIZES[SIZES.length - 1]; for (const v of SIZES) if (v >= hpx) { S = v; break; }
          let rel = (Math.atan2(-dx, -dz) - yaw) * (180 / Math.PI); rel = ((rel % 360) + 540) % 360 - 180;
          const azB = Math.round(rel / AZ), elD = (Math.atan2(cam.pos[1] - F.h * 0.5, d) * 180) / Math.PI, elB = elD < EL_TO[0] ? 0 : elD < EL_TO[1] ? 1 : elD < EL_TO[2] ? 2 : 3;
          let i;
          if (o.state || !F.dur) { const u = phase + (m.ph ?? 0); i = Math.floor((u - Math.floor(u)) * F.poses) % F.poses; } // in step (a march), or one drawing
          else {
            // a man who waits: his own moment of the wait, at his own pace; near the lens on finer moments
            const sub = S >= 192 ? 4 : S >= 96 ? 2 : 1, n = F.poses * sub, own = m.ph ?? hash(m.x * 7.31 + m.z * 3.17 + fi * 1.7), pace = 0.85 + 0.3 * hash(m.x * 1.93 + m.z * 5.71);
            const u = ((t ?? 0) * pace) / F.dur + own * (F.swing ? 2 : 1);
            if (F.swing) { const v = ((u % 2) + 2) % 2, w = v < 1 ? v : 2 - v; i = Math.min(n - 1, Math.floor(w * n)) / sub; }
            else i = (Math.floor((u - Math.floor(u)) * n) % n) / sub;
          }
          const s = sprite(fi, i, azB, elB, S, sc), k = hpx / F.h / s.ppm;
          g.drawImage(aired(s, q, sc), sx - s.ax * k, sy - s.ay * k, s.cv.width * k, s.cv.height * k);
        }
        tidy();
      },
    });
  }
  if (o.shadow !== false) {
    // the ground the ranks darken: every rank's line of men, drawn out away from the sun
    const rowLines = [...rows.values()].map((men) => { let a = men[0], b = men[0], lo = 1e9, hi = -1e9; for (const m of men) { const s = m.x * fwd[2] - m.z * fwd[0]; if (s < lo) { lo = s; a = m; } if (s > hi) { hi = s; b = m; } } return [a, b]; });
    items.push({
      p: [0, 0, 0], depth: 1e7, r: 1e6,
      draw(g, cam, sc, t) {
        const S = sc.sun, sl = Math.hypot(S[0], S[2]); if (sl < 1e-3) return;
        const adv = o.state ? (o.state(t).advance ?? 0) : 0, len = Math.min(o.shadowMax ?? 14, 1.7 / Math.max(0.06, S[1] / sl)), ox = (-S[0] / sl) * len, oz = (-S[2] / sl) * len;
        g.save(); g.fillStyle = sc.o.shadowColor ?? '#1a1c33'; g.globalAlpha = (sc.o.shadowAlpha ?? 0.3) * (o.shadowAmt ?? 1); g.beginPath();
        for (const [a, b] of rowLines) {
          const P = [[a.x, a.z], [b.x, b.z], [b.x + ox, b.z + oz], [a.x + ox, a.z + oz]].map(([x, z]) => cam.project([x + fwd[0] * adv, 0.01, z + fwd[2] * adv]));
          if (P.some((q) => q[2] < 0.5)) continue;
          g.moveTo(P[0][0], P[0][1]); for (let i = 1; i < 4; i++) g.lineTo(P[i][0], P[i][1]); g.closePath();
        }
        g.fill('nonzero'); g.restore();
      },
    });
  }
  items.kept = () => sprites.size;
  return items;
}
void V; void clamp;
