// Balls lit from one side — planets, moons, a football — and things that go round: rings and Kepler's law.
//   const S = solarSystem({ sun: [0, 5, -24], year: 9, planets: [{ name: 'earth', ring: 6.9, r: 0.4, color: '#4f8fd6', year: true }, …] })
//   in a shot:  over: (g, cam, sc, t) => S.draw(g, cam, t)          S.placeOf('earth', t) is where it is (for a camera to follow)
import { V } from '../engine/core.js';

/** A point on a ring of radius r round a centre, `turns` of the way round; tilt (radians) leans the ring's near side down towards the lens. */
export const onRing = (c, r, turns, tilt = 0) => { const a = turns * Math.PI * 2, x = Math.cos(a) * r, z = Math.sin(a) * r; return [c[0] + x, c[1] - z * Math.sin(tilt), c[2] + z * Math.cos(tilt)]; };
/** Kepler's third law: how many times round after t seconds, on a ring of this radius, when the ring `yearRing` takes `year` seconds. */
export const keplerTurns = (t, ring, yearRing, year) => t / (year * Math.pow(ring / yearRing, 1.5));

/**
 * A ball lit from one side: a dark disc with its lit part cut by the terminator — full with the light behind the lens,
 * half from the side, a crescent with the light almost behind it. p: centre, r: radius (m), light: where the light is.
 * o: dark (the night side), flat (no highlight), bands: [colours] (a gas giant), land: colour + spin (continents that turn),
 *    hoop: colour (a ring round it), hoopR (2.1 radii), hoopTilt (how open the ring is seen, 0…1).
 */
export function ball(g, cam, p, r, color, light, o = {}) {
  const q = cam.project(p); if (q[2] < 0.3) return;
  const R = (cam.f / q[2]) * r;
  if (R < 0.7) { g.fillStyle = color; g.globalAlpha = Math.max(0.5, Math.min(1, R * 1.4)); g.fillRect(q[0] - 0.8, q[1] - 0.8, 1.6, 1.6); g.globalAlpha = 1; return; }
  const toL = V.norm(V.sub(light, p)), toC = V.norm(V.sub(cam.pos, p)), k = Math.max(-1, Math.min(1, V.dot(toL, toC)));   // 1 full, 0 half, −1 new
  const ql = cam.project(V.add(p, V.mul(toL, r * 0.5))), dx = ql[0] - q[0], dy = ql[1] - q[1], ang = Math.hypot(dx, dy) > 1e-6 ? Math.atan2(dy, dx) : 0;
  const hoop = (front) => {
    const rr = R * (o.hoopR ?? 2.1), sq = Math.max(0.06, o.hoopTilt ?? 0.3);
    g.beginPath(); g.ellipse(q[0], q[1], rr, rr * sq, -0.12, front ? 0 : Math.PI, front ? Math.PI : Math.PI * 2);
    g.lineWidth = Math.max(1, R * 0.34); g.strokeStyle = o.hoop; g.globalAlpha = 0.85; g.stroke(); g.globalAlpha = 1;
  };
  if (o.hoop) hoop(false);
  g.save();
  g.beginPath(); g.arc(q[0], q[1], R, 0, Math.PI * 2); g.fillStyle = o.dark ?? '#0b0d18'; g.fill(); g.clip();
  g.translate(q[0], q[1]); g.rotate(ang);
  // the lit part, with the light along +x: the half disc on that side, grown (gibbous) or cut back (crescent) by the terminator's half ellipse
  g.beginPath(); g.arc(0, 0, R, -Math.PI / 2, Math.PI / 2);
  g.ellipse(0, 0, R * Math.abs(k), R, 0, Math.PI / 2, -Math.PI / 2, k < 0);
  g.closePath(); g.clip();
  g.rotate(-ang);
  g.fillStyle = color; g.fillRect(-R, -R, R * 2, R * 2);
  if (o.bands) { const n = o.bands.length * 2 + 1; g.globalAlpha = 0.75; for (let i = 1; i < n; i += 2) { g.fillStyle = o.bands[((i - 1) / 2) % o.bands.length]; g.fillRect(-R, -R + (i / n) * 2 * R, 2 * R, ((2 * R) / n) * 0.9); } g.globalAlpha = 1; }
  if (o.land) { g.fillStyle = o.land; g.globalAlpha = 0.85; const s = o.spin ?? 0; for (const [a, b, w, h] of [[-0.5, -0.2, 0.5, 0.42], [0.25, 0.15, 0.42, 0.5], [-0.1, 0.5, 0.3, 0.2]]) { g.beginPath(); g.ellipse(((((a + s) % 2) + 3) % 2 - 1) * R, b * R, w * R * 0.5, h * R * 0.5, 0.4, 0, Math.PI * 2); g.fill(); } g.globalAlpha = 1; }
  if (!o.flat) {
    const hi = g.createRadialGradient(Math.cos(ang) * R * 0.45, Math.sin(ang) * R * 0.45, R * 0.05, 0, 0, R * 1.05);
    hi.addColorStop(0, 'rgba(255,255,255,0.42)'); hi.addColorStop(0.45, 'rgba(255,255,255,0)'); hi.addColorStop(1, 'rgba(0,0,10,0.28)');
    g.fillStyle = hi; g.fillRect(-R, -R, R * 2, R * 2);
  }
  g.restore();
  if (o.hoop) hoop(true);
}

/**
 * A sun and its planets, going round by Kepler's law.
 * o: { sun: [x, y, z], sunR (1.9), year (seconds one round of the `year: true` planet takes; default the third planet),
 *      tilt (0.36 rad), rings (draw the orbits, true), planets: [{ name, ring (orbit radius), r (0.3), color, at (turns it starts at),
 *      bands, land, hoop, moon: true | { r, ring, color } }] }
 * → { names, placeOf(name, t), draw(g, cam, t) }
 */
export function solarSystem(o) {
  const sun = o.sun ?? [0, 5, -24], tilt = o.tilt ?? 0.36, sunR = o.sunR ?? 1.9, year = o.year ?? 9;
  const P = o.planets.map((p, i) => ({ r: 0.3, color: '#b9b9c4', at: (i * 0.37) % 1, ...p }));
  const ref = (P.find((p) => p.year) ?? P[Math.min(2, P.length - 1)]).ring;
  const where = (p, t) => onRing(sun, p.ring, p.at + keplerTurns(t, p.ring, ref, year), tilt);
  const placeOf = (name, t) => where(P.find((p) => p.name === name), t);
  const draw = (g, cam, t) => {
    if (o.rings !== false) {
      g.save(); g.lineWidth = 1; g.strokeStyle = o.ringColor ?? 'rgba(150,170,230,0.22)';
      for (const p of P) { g.beginPath(); let pen = false; for (let i = 0; i <= 120; i++) { const q = cam.project(onRing(sun, p.ring, i / 120, tilt)); if (q[2] < 0.3) { pen = false; continue; } if (pen) g.lineTo(q[0], q[1]); else g.moveTo(q[0], q[1]); pen = true; } g.stroke(); }
      g.restore();
    }
    const all = P.map((p) => { const at = where(p, t); return { d: V.dist(cam.pos, at), go: () => {
      ball(g, cam, at, p.r, p.color, sun, { bands: p.bands, land: p.land, spin: t * 0.25, hoop: p.hoop, hoopTilt: Math.max(0.1, Math.abs(Math.sin(tilt + 0.25))) });
      if (p.moon) { const m = p.moon === true ? {} : p.moon, a = t * 2.4, mr = m.ring ?? p.r * 2.4; ball(g, cam, [at[0] + Math.cos(a) * mr, at[1] + 0.1 * Math.sin(a), at[2] + Math.sin(a) * mr], m.r ?? p.r * 0.27, m.color ?? '#c9c9c9', sun); }
    } }; });
    all.push({ d: V.dist(cam.pos, sun), go: () => {
      const q = cam.project(sun); if (q[2] < 0.3) return; const R = (cam.f / q[2]) * sunR;
      g.save(); g.globalCompositeOperation = 'screen';
      for (const [k, a] of [[6.5, 0.1], [3.6, 0.18], [2.0, 0.35]]) { const gl = g.createRadialGradient(q[0], q[1], R * 0.6, q[0], q[1], R * k); gl.addColorStop(0, `rgba(255,214,130,${a})`); gl.addColorStop(1, 'rgba(255,170,60,0)'); g.fillStyle = gl; g.beginPath(); g.arc(q[0], q[1], R * k, 0, Math.PI * 2); g.fill(); }
      g.restore();
      const sg = g.createRadialGradient(q[0] - R * 0.2, q[1] - R * 0.2, R * 0.1, q[0], q[1], R); sg.addColorStop(0, '#fffbe6'); sg.addColorStop(0.6, '#ffd97a'); sg.addColorStop(1, '#ff9d2e');
      g.fillStyle = sg; g.beginPath(); g.arc(q[0], q[1], R, 0, Math.PI * 2); g.fill();
    } });
    all.sort((a, b) => b.d - a.d); for (const x of all) x.go();
  };
  return { names: P.map((p) => p.name), placeOf, draw };
}
