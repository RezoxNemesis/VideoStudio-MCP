// Fire arrows: burning points that cross the sky on an arc and land. Drawn over the scene (they are light).
import { V, clamp, lerp } from '../engine/core.js';
import { glow } from '../engine/fx.js';
/** list: [{ t0, dur, from: [x,y,z], to: [x,y,z], arc (m of extra height at the top), size? }] → draw(g, cam, ts) */
export function fireArrows(list) {
  const at = (a, u) => { const p = V.lerp(a.from, a.to, u); return [p[0], p[1] + (a.arc ?? 0) * 4 * u * (1 - u), p[2]]; };
  return (g, cam, ts) => {
    g.save(); g.globalCompositeOperation = 'screen'; g.lineCap = 'round';
    for (const a of list) {
      const u = (ts - a.t0) / a.dur; if (u < 0) continue;
      if (u >= 1) { // the flash where it lands
        const f = (ts - a.t0 - a.dur) / 0.45; if (f < 1) { const q = cam.project(a.to); if (q[2] > 0.3) glow(g, q[0], q[1], cam.f / q[2] * 2.2 * (0.5 + f), '#ffb050', (1 - f) * 0.9); }
        continue;
      }
      const head = cam.project(at(a, u)); if (head[2] < 2.5) continue; // an arrow brushing the lens would fill the picture for two frames: not drawn that close
      const k = cam.f / head[2], sz = Math.min(Math.max(1.6, (a.size ?? 0.16) * k), head[2] < 7 ? 0.014 * cam.H : 1e9); // close to the camera an arrow stays a streak, not a bar
      let prev = head; const n = 10;
      for (let i = 1; i <= n; i++) {
        const q = cam.project(at(a, Math.max(0, u - i * 0.012 * (1 + 2 * u)))); if (q[2] < 2.5) break;
        g.beginPath(); g.moveTo(prev[0], prev[1]); g.lineTo(q[0], q[1]); g.lineWidth = sz * (1 - i / (n + 1)) * 1.3; g.strokeStyle = i < 3 ? '#fff2c0' : i < 6 ? '#ffb040' : '#e0541a'; g.globalAlpha = 0.95 * (1 - i / (n + 1)); g.stroke(); prev = q;
      }
      g.globalAlpha = 1; glow(g, head[0], head[1], sz * 5, '#ff9a30', 0.75); glow(g, head[0], head[1], sz * 1.8, '#fff6d8', 1);
    }
    g.restore();
  };
}
