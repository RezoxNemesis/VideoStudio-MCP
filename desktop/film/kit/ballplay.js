// BALLS IN PLAY: a ball that is caught, held, thrown, dribbled and kicked by the people of a film — one ball, in one
// place at every moment, that goes where hands and feet send it and falls the way things fall (16 Nov 2026: in the
// Movement Study people caught, dribbled and kicked nothing; the moves read as mime).
//
//   const ball = ballPlay({ kind: 'basket', life: [
//     { t: 2.0, hold: 'both', by: 'a' },            // in both hands (between the palms)
//     { t: 3.1, away: { k: 1.2 } },                  // released: it leaves with the speed the hands gave it, falls,
//                                                    //   bounces and rolls (k: how much faster than the hands)
//     { t: 5.0, on: [x, 0, z] },                     // lies there (placed, or where it came to rest)
//     { t: 6.0, fly: true },                         // from here it FLIES, on the arc that brings it to the next key in time
//                                                    //   (fly: true, from: [x, y, z] — thrown in from there by someone unseen)
//     { t: 6.8, hold: 'R', by: 'b' },                // … caught in one hand
//     { t: 8.0, dribble: 'R', by: 'b', until: 11 },  // bounced on the ground under that hand, struck at each lowest point of it
//     { t: 12.0, foot: 'R', by: 'b' },               // at the foot (a kick's contact), then usually `away`
//     { t: 0, lies: { by: 'b', foot: 'R', at: 12 } },  // on the ground just where that foot (or hands: 'both') will meet it
//   ] });
//   sequence:  dyn: (ts, ctx) => [ball(ts, ctx)]          ball.at(ts, ctx) → its centre
// The hands and feet are read from the solved performance (ctx.jointsAt), so a ball follows the body it is given to.
// kind: 'basket' (orange, black seams) | 'soccer' (white, black patches) | 'plain' (color, shade); r: radius (m).
import { gripFrame } from './hold.js';
import { Mesh, box } from '../engine/scene.js';

const G = 9.81;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const lerp = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
const len = (a) => Math.hypot(a[0], a[1], a[2]), norm = (a) => { const l = len(a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const KINDS = { basket: { r: 0.12, color: '#e0762e', shade: '#a8481d', seam: '#24150c' }, soccer: { r: 0.11, color: '#f4f4f0', shade: '#b9bdc8', seam: '#1c1c22' }, plain: { r: 0.1, color: '#d8463a', shade: '#93271f', seam: null }, box: { size: 0.3, side: '#9a6a3c', top: '#c48c54' } };

/** The place on an arc that leaves p0 and reaches p1 after T seconds under gravity, s seconds into it. */
export function arcAt(p0, p1, T, s) { const u = T > 0 ? Math.max(0, Math.min(1, s / T)) : 1, q = lerp(p0, p1, u); return [q[0], q[1] + 0.5 * G * (s * (T - s) > 0 ? s * (T - s) : 0), q[2]]; }

/** A ball set off at p with velocity v, after s seconds: it falls, bounces (losing speed) and rolls to a stop. */
export function loose(p, v, s, r, o = {}) {
  const e = o.bounce ?? 0.55, fr = o.friction ?? 1.6, dt = 1 / 240; let x = [...p], w = [...v], left = s;
  for (let i = 0; left > 0 && i < 20000; i++) {
    const h = Math.min(dt, left); left -= h;
    w = [w[0], w[1] - G * h, w[2]]; x = add(x, mul(w, h));
    if (x[1] < r) { x[1] = r; if (w[1] < 0) w[1] = Math.abs(w[1]) > 0.6 ? -w[1] * e : 0; if (w[1] === 0) { const sp = Math.hypot(w[0], w[2]), dec = Math.min(sp, fr * h); if (sp > 1e-6) { w[0] -= (w[0] / sp) * dec; w[2] -= (w[2] / sp) * dec; } } }
  }
  return { p: x, v: w };
}

export function ballPlay(o) {
  const K = { ...KINDS[o.kind ?? 'plain'], ...o, kind: o.kind ?? 'plain' }, isBox = K.kind === 'box', r = isBox ? K.size * 0.4 : K.r, life = [...o.life].sort((a, b) => a.t - b.t);
  const J = (ctx, id, t, jn) => { const j = ctx.jointsAt ? ctx.jointsAt(id, t) : (ctx.poses[id]?.j); return j?.[jn] ?? null; };
  const poseOf = (ctx, id, t) => (ctx.jointsAt ? { j: ctx.jointsAt(id, t), height: ctx.poses[id]?.height } : ctx.poses[id]);
  /** where a held ball is: between the palms, or lying on one palm */
  const inHands = (pose, how) => {
    if (!pose?.j) return null;
    if (how === 'both') { const a = gripFrame(pose, 'L', { r: 0 }), b = gripFrame(pose, 'R', { r: 0 }); if (!a || !b) return null; const m = lerp(a.c, b.c, 0.5), f = norm(add(a.fwd, b.fwd)); return isBox ? m : add(m, mul(f, r * 0.35)); }
    const F = gripFrame(pose, how, { r: 0 }); return F ? add(F.c, mul(F.palm, r * 0.95)) : null;
  };
  const atFoot = (ctx, id, t, side) => { const a = J(ctx, id, t, `foot.${side}`), toe = J(ctx, id, t, `toe1-1.${side}`) ?? a; if (!a) return null; const f = norm([toe[0] - a[0], 0, toe[2] - a[2]]); return [toe[0] + f[0] * r * 1.1, r, toe[2] + f[2] * r * 1.1]; };
  /** the hand's lowest moments in [t0, t1] (where a dribbled ball is struck) */
  const lows = new Map();
  const lowsOf = (ctx, k) => {
    const key = `${k.by}${k.dribble}${k.t}`; if (lows.has(key)) return lows.get(key);
    const ys = [], dt = 1 / 60; for (let t = k.t; t <= k.until + 1e-6; t += dt) ys.push([t, J(ctx, k.by, t, `wrist.${k.dribble}`)?.[1] ?? 0]);
    const m = [k.t]; for (let i = 2; i < ys.length - 2; i++) if (ys[i][1] < ys[i - 1][1] && ys[i][1] <= ys[i + 1][1] && ys[i][1] < ys[i - 2][1] && ys[i][1] <= ys[i + 2][1] && ys[i][0] - m[m.length - 1] > 0.28) m.push(ys[i][0]);
    lows.set(key, m); return m;
  };
  /** the centre of the ball at time t under key k (k's own kind of place), or null when k does not place it by itself */
  const placeOf = (ctx, k, t, live) => {
    if (k.on) return [k.on[0], Math.max(r, k.on[1] + r), k.on[2]];
    // (lies: { by, foot | hands, at } — on the ground just where that foot or those hands will come to it at `at`)
    if (k.lies) { const q = k.lies.foot ? atFoot(ctx, k.lies.by, k.lies.at, k.lies.foot) : inHands(poseOf(ctx, k.lies.by, k.lies.at), k.lies.hands ?? 'both'); return q ? [q[0], r, q[2]] : null; }
    if (k.hold) return inHands(live && ctx.poses[k.by] && Math.abs((ctx.ts ?? t) - t) < 1e-6 ? ctx.poses[k.by] : poseOf(ctx, k.by, t), k.hold);
    if (k.foot) return atFoot(ctx, k.by, t, k.foot);
    if (k.dribble) { const hand = inHands(poseOf(ctx, k.by, t), k.dribble); return hand ? add(hand, [0, -r * 0.2, 0]) : null; }
    return null;
  };
  /** a key's own place at ITS moment (where a flight starts or ends) */
  const startOf = (ctx, i) => { const k = life[i]; if (k.fly && k.from) return k.from; if (k.fly || k.away) return endOf(ctx, i - 1, k.t); return placeOf(ctx, k, k.t); };
  function endOf(ctx, i, t) { if (i < 0) return null; return at(ctx, t, i); }
  function at(ctx, ts, only) {
    let i = only ?? -1; if (only === undefined) for (let q = 0; q < life.length; q++) if (life[q].t <= ts) i = q;
    if (i < 0) return life[0].on || life[0].lies ? startOf(ctx, 0) : null; // (before its first key it is nowhere — unless it lies somewhere from the start)
    const k = life[i], next = life[i + 1];
    if (k.fly) { if (!next) return k.from ?? endOf(ctx, i - 1, k.t); const p0 = k.from ?? endOf(ctx, i - 1, k.t), p1 = startOf(ctx, i + 1); return p0 && p1 ? arcAt(p0, p1, next.t - k.t, ts - k.t) : p0 ?? p1; }
    if (k.away) {
      const p0 = endOf(ctx, i - 1, k.t), pb = endOf(ctx, i - 1, k.t - 0.04); if (!p0) return null;
      const v = k.away.v ?? mul(sub(p0, pb ?? p0), (k.away.k ?? 1.2) / 0.04);
      return loose(p0, k.away.v ?? [v[0], v[1] + (k.away.lift ?? 0), v[2]], ts - k.t, r, k.away).p;
    }
    if (k.dribble && ts <= k.until) {
      const m = lowsOf(ctx, k); let a = 0; for (let q = 0; q < m.length; q++) if (m[q] <= ts) a = q;
      const t0 = m[a], t1 = a + 1 < m.length ? m[a + 1] : k.until, h0 = placeOf(ctx, k, t0), h1 = placeOf(ctx, k, t1); if (!h0 || !h1) return h0 ?? h1;
      const tm = t0 + (t1 - t0) * 0.45, gp = [(h0[0] + h1[0]) / 2, r, (h0[2] + h1[2]) / 2];
      // (struck down, it hits the ground and comes back up into the hand: two short arcs pushed so they meet the ground)
      return ts < tm ? arcAt(h0, gp, tm - t0, ts - t0).map((x, n) => (n === 1 ? Math.max(r, h0[1] + (gp[1] - h0[1]) * Math.pow((ts - t0) / (tm - t0), 1.6)) : x)) : arcAt(gp, h1, t1 - tm, ts - tm).map((x, n) => (n === 1 ? Math.max(r, gp[1] + (h1[1] - gp[1]) * (1 - Math.pow(1 - (ts - tm) / (t1 - tm), 1.6))) : x));
    }
    if (k.dribble) return placeOf(ctx, k, k.until);
    return placeOf(ctx, k, ts, true);
  }
  let spin = 0, last = null;
  const item = (ts, ctx) => {
    ctx.ts = ts; const p = at(ctx, ts);
    if (!p) return [];
    if (last) spin += len(sub(p, last)) / r; last = p;
    if (isBox) { const by = life.find((q) => q.by)?.by, rt = ctx.poses[by]?.j, yaw = rt ? Math.atan2(rt['clavicle.L']?.[2] - rt['clavicle.R']?.[2], rt['clavicle.L']?.[0] - rt['clavicle.R']?.[0]) * -180 / Math.PI : 0, z = K.size, m = new Mesh(box([p[0], p[1] - r, p[2]], z, r * 2, z * 0.85, yaw, { side: K.side, top: K.top }), { p, bias: -0.02 }); return { p, bias: -0.02, draw: (g, cam, sc) => m.draw(g, cam, sc) }; }
    return { p, bias: -0.02, draw: (g, cam, sc) => drawBall(g, cam, p, r, K, sc?.sun ?? [0.35, 0.8, 0.5], spin) };
  };
  const f = (ts, ctx) => item(ts, ctx);
  f.at = (ts, ctx) => at(ctx, ts);
  return f;
}

/** A ball drawn as an animator draws one: flat colour, one shade on the side away from the light, its seams, an ink line, its shadow on the ground. */
export function drawBall(g, cam, p, r, K, sun, spin = 0) {
  const q = cam.project(p); if (!q || q[2] < 0.3) return;
  const R = (cam.f / q[2]) * r; if (R < 0.6) return;
  const gq = cam.project([p[0], 0.002, p[2]]), up = Math.max(0, p[1] - r);
  // its shadow on the ground (smaller and fainter the higher it is)
  if (gq && gq[2] > 0.3) { const sR = (cam.f / gq[2]) * r * Math.max(0.35, 1 - up * 0.6); g.save(); g.globalAlpha = Math.max(0, 0.28 - up * 0.12); g.fillStyle = '#1d2030'; g.beginPath(); g.ellipse(gq[0], gq[1], sR, sR * 0.32, 0, 0, Math.PI * 2); g.fill(); g.restore(); }
  // the light's side on the screen: where the sun direction from the ball's centre falls in the picture
  const ql = cam.project(add(p, mul(norm(sun), r))), lx = ql ? ql[0] - q[0] : 0, ly = ql ? ql[1] - q[1] : -1, ll = Math.hypot(lx, ly) || 1, ux = lx / ll, uy = ly / ll;
  g.save(); g.beginPath(); g.arc(q[0], q[1], R, 0, Math.PI * 2); g.clip();
  // one shade, the side away from the light: the shade colour under a lit disc pushed towards the light
  g.fillStyle = K.shade; g.fillRect(q[0] - R - 2, q[1] - R - 2, 2 * R + 4, 2 * R + 4);
  g.fillStyle = K.color; g.beginPath(); g.arc(q[0] + ux * R * 0.38, q[1] + uy * R * 0.38, R * 1.02, 0, Math.PI * 2); g.fill();
  if (K.seam) {
    g.strokeStyle = K.seam; g.lineWidth = Math.max(0.8, R * 0.07); g.globalAlpha = 0.9;
    if (K.kind === 'soccer' || K.patches) {
      // (a football: a black pentagon in the middle and five more round it, cut by the ball's edge; all turn as it rolls)
      const pent = (cx, cy, rr, a0) => { g.beginPath(); for (let s2 = 0; s2 < 5; s2++) { const b = a0 + (s2 / 5) * Math.PI * 2 - Math.PI / 2; g.lineTo(cx + Math.cos(b) * rr, cy + Math.sin(b) * rr); } g.closePath(); g.fill(); };
      g.fillStyle = K.seam; const a0 = spin * 0.6;
      pent(q[0], q[1], R * 0.3, a0);
      for (let i = 0; i < 5; i++) { const a = a0 + (i / 5) * Math.PI * 2 + Math.PI / 5 - Math.PI / 2; pent(q[0] + Math.cos(a) * R * 0.95, q[1] + Math.sin(a) * R * 0.95, R * 0.3, a + Math.PI); }
      g.lineWidth = Math.max(0.7, R * 0.045); g.beginPath(); for (let i = 0; i < 5; i++) { const a = a0 + (i / 5) * Math.PI * 2 - Math.PI / 2, a2 = a0 + (i / 5) * Math.PI * 2 + Math.PI / 5 - Math.PI / 2; g.moveTo(q[0] + Math.cos(a) * R * 0.3, q[1] + Math.sin(a) * R * 0.3); g.lineTo(q[0] + Math.cos(a2) * R * 0.66, q[1] + Math.sin(a2) * R * 0.66); } g.stroke();
    }
    else { const c = Math.cos(spin), s2 = Math.sin(spin); g.beginPath(); g.ellipse(q[0], q[1], R * Math.abs(c), R, 0, 0, Math.PI * 2); g.stroke(); g.beginPath(); g.ellipse(q[0], q[1], R, R * Math.abs(s2) * 0.9 + R * 0.1, 0, 0, Math.PI * 2); g.stroke(); g.beginPath(); g.moveTo(q[0] - R, q[1]); g.lineTo(q[0] + R, q[1]); g.stroke(); }
    g.globalAlpha = 1;
  }
  g.restore();
  // a small glint, and the ink line round it
  g.fillStyle = 'rgba(255,255,255,0.75)'; g.beginPath(); g.ellipse(q[0] + ux * R * 0.45, q[1] + uy * R * 0.45, R * 0.16, R * 0.1, -0.5, 0, Math.PI * 2); g.fill();
  g.strokeStyle = '#1a1820'; g.lineWidth = Math.max(1, R * 0.08); g.beginPath(); g.arc(q[0], q[1], R, 0, Math.PI * 2); g.stroke();
}
