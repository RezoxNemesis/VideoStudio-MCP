// Everything Sensei needs to know about one shot, gathered without drawing it: every body at 60 samples a second on the
// story clock (so slow motion and hit-stops do not look like mistakes), the camera at every sample, who is shown and
// where each body falls in the picture.
import { ShotRun } from '../engine/shot.js';
import { poseAt } from '../engine/bake.js';
import { makeCam } from '../engine/camera.js';
import { capsulesOf } from './geo.js';

export const HZ = 60;
const SCREEN_JOINTS = ['head:tail', 'head', 'neck01', 'wrist.L', 'wrist.R', 'lowerarm01.L', 'lowerarm01.R', 'root', 'lowerleg01.L', 'lowerleg01.R', 'foot.L', 'foot.R', 'toe2-3.L:tail', 'toe2-3.R:tail', 'upperarm01.L', 'upperarm01.R'];

export function collect(P, sh, o = {}) {
  const W = P.config.width, H = P.config.height;
  const run = new ShotRun(sh, P.CAST, { probe: true, W, H, fps: P.config.fps, lang: o.lang ?? P.config.langs[0] });
  const N = Math.max(3, Math.round(sh.dur * HZ) + 1), dt = sh.dur / (N - 1);
  const ts = new Float64Array(N), tw = new Float64Array(N), cams = new Array(N), shown = new Array(N), faces = new Array(N), held = new Array(N);
  const warp = typeof sh.warpT === 'function' ? sh.warpT : null;
  for (let i = 0; i < N; i++) {
    const t = Math.min(sh.dur, i * dt); ts[i] = t; tw[i] = warp && t > 0 ? warp(t) : t;
    const pr = run.probe(t);
    cams[i] = makeCam(pr.cam, W, H); cams[i].spec = pr.cam;
    shown[i] = pr.shown; faces[i] = pr.faces;
    const hp = {}; for (const a of run.actors) hp[a.id] = run.ctx.poses[a.id]?.j ?? null; held[i] = hp;
  }
  const actors = run.actors.map((a) => {
    const J = new Array(N), Lc = new Array(N), C = new Uint8Array(N), props = new Array(N), vis = new Uint8Array(N), size = new Float32Array(N), box = new Array(N), hands = new Array(N);
    const id = a.as ?? a.id, k = (a.bk.actors[id]?.height ?? 1.8) / 1.8;
    for (let i = 0; i < N; i++) {
      const time = (a.t0 ?? 0) + tw[i] * (a.rate ?? 1);
      run.ctx.t = ts[i]; run.ctx.tw = tw[i];
      const xf = typeof a.xf === 'function' ? a.xf(tw[i], run.ctx) : a.xf;
      const p = poseAt(a.bk, id, time, xf ?? null), pl = poseAt(a.bk, id, time, null);
      J[i] = p.j; Lc[i] = pl.j; C[i] = p.contact; props[i] = p.props;
      try { hands[i] = typeof a.hands === 'function' ? a.hands(tw[i], run.ctx) : a.hands ?? null; } catch { hands[i] = null; }
      // where it is in the picture
      const cam = cams[i]; let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, inside = 0, front = 0;
      for (const jn of SCREEN_JOINTS) {
        const q = p.j[jn]; if (!q) continue;
        const c = cam.toCam(q); if (c[2] < 0.15) continue; front++;
        const s = cam.project(q); x0 = Math.min(x0, s[0]); x1 = Math.max(x1, s[0]); y0 = Math.min(y0, s[1]); y1 = Math.max(y1, s[1]);
        if (s[0] >= 0 && s[0] <= W && s[1] >= 0 && s[1] <= H) inside++;
      }
      const on = shown[i]?.[a.id] !== false;
      vis[i] = on && inside >= 3 ? 1 : 0;
      size[i] = vis[i] ? Math.max(0, y1 - y0) / H : 0;
      box[i] = vis[i] ? [x0 / W, y0 / H, x1 / W, y1 / H] : null;
    }
    return { id: a.id, bakeId: id, bake: a.bk.name, k, J, L: Lc, C, props, vis, size, box, hands, design: a.design, rate: a.rate ?? 1, t0: a.t0 ?? 0, bk: a.bk };
  });
  return { sh, id: sh.id, seq: sh.seq, dur: sh.dur, N, dt, ts, tw, cams, shown, faces, held, actors, W, H, fps: P.config.fps, warp: !!warp };
}

/** sample index nearest to shot time t */
export const idxAt = (D, t) => Math.max(0, Math.min(D.N - 1, Math.round(t / D.dt)));
/** picture position (0…1) of a world point at sample i, or null when it is not in front of the lens */
export function screenAt(D, i, pt) {
  const cam = D.cams[i]; if (!cam || !pt) return null;
  const c = cam.toCam(pt); if (c[2] < 0.1) return null;
  const s = cam.project(pt); return { x: s[0] / D.W, y: s[1] / D.H, depth: c[2] };
}
export const onScreen = (s, m = 0) => !!s && s.x >= -m && s.x <= 1 + m && s.y >= -m && s.y <= 1 + m;
/** shot time of a story-clock time (for a shot with a warp) */
export function shotTimeOfSample(D, i) { return D.ts[i]; }
export { capsulesOf };
