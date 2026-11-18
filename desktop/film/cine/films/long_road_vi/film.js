// THE LONG ROAD TO VI (second cut): every shot, staged and filmed
import { THREE, V3, clamp, lerp, sm, sm2, seg, rng } from './engine.js';
import * as K from './kit.js';
const PI = Math.PI, HP = PI / 2;
export const PAL = {
  hero: ['#c68e68', '#e9a81c', '#2b3a5c', '#c8402c', '#f2ece0', '#17131a', '#ffffff', '#1a1418', '#efe9dc', '#c98a10', '#e9a81c'],
  frA: ['#b67c58', '#cf4838', '#5c4a3e', '#2c5c8c', '#f2ece0', '#140f12', '#ffffff', '#1a1418', '#e8e2d6', '#a8342a', '#cf4838'],
  frB: ['#d09c7a', '#2a9a8a', '#6a4a8a', '#f0e8e0', '#ffffff', '#24161a', '#ffffff', '#1a1418', '#f4f0ea', '#1c7a6c', '#2a9a8a'],
};
const CROWD = [['#b67c58', '#4c7cc0', '#3a3a44', '#3a3a3a', '#eee', '#151015', '#fff', '#1a1418', '#ddd', '#3a62a0', '#4c7cc0'], ['#d2a07c', '#e8e0d0', '#2c4a6c', '#8c4c2c', '#eee', '#1c1418', '#fff', '#1a1418', '#ddd', '#c8c0b0', '#e8e0d0'],
  ['#a87050', '#6aa84f', '#4a3c34', '#222', '#eee', '#120e10', '#fff', '#1a1418', '#ddd', '#4c8a3a', '#6aa84f'], ['#c89470', '#d86c9c', '#3c3c5c', '#e8e8e8', '#fff', '#1a1216', '#fff', '#1a1418', '#eee', '#b84c7c', '#d86c9c'],
  ['#b88460', '#8c5cc0', '#2c2c34', '#c84a2c', '#eee', '#141014', '#fff', '#1a1418', '#ddd', '#6c3ca0', '#8c5cc0'], ['#c48c68', '#e87c2c', '#3c4c3c', '#2c2c2c', '#eee', '#181216', '#fff', '#1a1418', '#ddd', '#c05c14', '#e87c2c'],
  ['#ad7654', '#d8d8dc', '#1c2c4c', '#f0f0f0', '#fff', '#100c0e', '#fff', '#1a1418', '#ddd', '#b0b0b8', '#d8d8dc'], ['#cf9a76', '#3cb0c8', '#5c3c2c', '#2c2c2c', '#eee', '#1c1418', '#fff', '#1a1418', '#ddd', '#2090a8', '#3cb0c8']];
export async function mk(E, asset, pal, track, x, z, yaw = HP) { await E.loadChar(asset); const a = E.actor(asset, pal); a.track = track ? await E.loadTrack(track) : null; a.x0 = x; a.z0 = z; a.yaw = yaw; a.place(x, 0, z, yaw); a.rate = 1; a.off = 0; return a; }
export function play(a, t) { a.f = Math.max(0, (t * a.rate + a.off) * 30); a.pose(a.track, a.f); return a; }
const _v = new V3();
export function anc(a, f, win = 12) { // smoothed place of a body in the world (never the bobbing hips themselves)
  let x = 0, y = 0, z = 0, n = 0; for (let k = -win; k <= win; k += 3) { a.rootAt(a.track, clamp(f + k, 0, a.track.F - 1.01), _v); x += _v.x; y += _v.y; z += _v.z; n++; } x /= n; y /= n; z /= n;
  const c = Math.cos(a.yaw), s = Math.sin(a.yaw); return [a.x0 + x * c + z * s, y, a.z0 - x * s + z * c]; }
function bag(E, a) { // a shop bag swinging from the fist: a real pendulum
  const g = new THREE.BoxGeometry(0.19, 0.24, 0.045, 1, 3, 1); const p = g.getAttribute('position'); for (let i = 0; i < p.count; i++) { const y = p.getY(i); p.setX(i, p.getX(i) * (0.72 + 0.28 * (0.12 - y) / 0.24)); p.setY(i, y - 0.17); }
  g.computeVertexNormals(); const tex = K.canvasTex(128, 160, (c, w, h) => { c.fillStyle = '#f4efe2'; c.fillRect(0, 0, w, h); c.fillStyle = '#d8402c'; c.fillRect(0, h * 0.34, w, h * 0.3); c.fillStyle = '#fff'; c.font = '700 26px Oswald'; c.textAlign = 'center'; c.fillText('GAME', w / 2, h * 0.5); c.fillText('ZONE', w / 2, h * 0.5 + 24); });
  const m = E.prop(g, { map: tex, side: THREE.DoubleSide }); const hd = E.prop(new THREE.BoxGeometry(0.012, 0.07, 0.012).translate(0, -0.03, 0), { color: '#f4efe2', shadow: false }); m.add(hd);
  const st = { p: null, v: new V3(), m }; const piv = new V3(), d = new V3(), up = new V3(0, 1, 0), q = new THREE.Quaternion();
  st.step = () => { a.world('middle_02_r', piv); if (!st.p) { st.p = piv.clone().add(new V3(0, -0.1, 0)); st.pp = st.p.clone(); }
    for (let k = 0; k < 6; k++) { const nx = st.p.clone().multiplyScalar(1.985).sub(st.pp.clone().multiplyScalar(0.985)); nx.y -= 9.8 * (1 / 180) * (1 / 180); st.pp.copy(st.p); st.p.copy(nx); d.subVectors(st.p, piv); d.setLength(0.1); st.p.addVectors(piv, d); }
    d.subVectors(piv, st.p).normalize(); q.setFromUnitVectors(up, d); m.quaternion.copy(q); m.rotateY(a.yaw); m.position.copy(piv); };
  return st; }
const txt = (E, s, a = 1, sub) => K.stamp(E, s, a, sub);
const io = (t, a, b, f = 0.6) => sm(seg(t, a, a + f)) * (1 - sm(seg(t, b - f, b)));
const CR = ['manA', 'womA', 'manB', 'teenC'];
async function crowd(E, list) { const out = []; for (const [asset, track, x, z, off, pi, line] of list) { const a = await mk(E, asset, CROWD[pi % CROWD.length], track, x, z); a.off = off; if (!line) a.outline.visible = false; out.push(a); } return out; }
export const META = { name: 'The Long Road to VI', width: 2560, height: 1440, fps: 30, audio: 'audio.flac', order: ['s00', 's01', 's02', 's03', 's04', 's05', 's06', 's07', 's08', 's09', 's10', 's11', 's12', 'm1', 'm2', 's13', 's14', 's15', 's16', 's16b', 's17'] };
const CITY = K.CITY_TEX; let cityReady = null; const city = async E => { for (const t of CITY) await E.loadTex(t, { repeat: t === 'water', raw: /^(deco|tower)\d/.test(t) }); };
export const S = {};
// 1. September 2013. A milestone, and a boy who has just bought the game.
S.s01 = { soft: t0 => t0 < 9.9, dur: 14, async build(E) {
    K.roadSet(E, { mood: 'gold', x0: -30, x1: 90, seed: 11, clear: (x, z) => z > 0 && z < 15 && x > -9 && x < 9, flowers: 0.12 });
    const c = { stone: K.milestone(E, 0.6, 4.25, 'GTA VI', '13', 'YRS') }; c.h = await mk(E, 'hero10', PAL.hero, 's01_hero', -16.5, 0.35); c.h.mat.uniforms.uSmile.value = 0.6; c.bag = bag(E, c.h); return c; },
  frame(t, c, E) { play(c.h, t); c.bag.step(); const a = anc(c.h, c.h.f); const u = sm2(seg(t, 4.2, 10.5)); E.P.uPost.value.z = 1 - sm(seg(t, 0, 1.4));
    const p = K.path([[-1.05, 0.66, 5.75], [-1.2, 0.72, 5.7], [-2.6, 1.7, 4.4], [-3.2, 2.7, 2.2], [-2.0, 3.1, 1.0]], u); p[0] += 0.12 * seg(t, 0, 4.2) + (a[0] - 9.0) * sm2(seg(t, 6.5, 12.5)) * 0.82;
    const ta = K.mix3([0.6, 0.6, 4.25], [a[0] + 3, 0.95, a[2]], sm(seg(t, 3.9, 7.4)));
    const dk = Math.hypot(a[0] - p[0], a[2] - p[2]);
    txt(E, t > 9.2 ? '17 SEPTEMBER 2013' : null, io(t, 9.2, 13.9));
    return { p, t: ta, fov: lerp(31, 36, u), focus: lerp(1.9, Math.max(3, dk), sm(seg(t, 3.7, 5.4))), dof: lerp(11, 3.5, sm(seg(t, 4.5, 9))) }; } };
// 2. He runs; two friends catch up. The lens travels beside them, then swings in front.
S.s02 = { dur: 14, async build(E) {
    K.roadSet(E, { mood: 'gold', x0: 30, x1: 130, seed: 12, clear: (x, z) => z > 5.2 && z < 10.5, flowers: 0.12, nearLow: true }); K.birds(E, 7, 60, 16, -60, { seed: 3 });
    const c = {}; c.h = await mk(E, 'hero10', PAL.hero, 's02_hero', 40, 0.3); c.bag = bag(E, c.h); c.a = await mk(E, 'frA10', PAL.frA, 's02_frA', 35.0, -1.25); c.b = await mk(E, 'frB10', PAL.frB, 's02_frB', 34.0, 1.45);
    for (const k of 'hab') c[k].mat.uniforms.uSmile.value = 0.8; return c; },
  frame(t, c, E) { play(c.h, t); play(c.a, t); play(c.b, t); c.bag.step(); const a = anc(c.h, c.h.f); const u = sm2(seg(t, 7.0, 12.2));
    const p = [a[0] + K.keys([[0, 0.4], [7, 0.7], [10.6, 7.6], [13.97, 1.5]], t), lerp(1.12, 0.8, u), lerp(7.2, 6.0, sm(seg(t, 0, 7))) + lerp(0, -3.3, u)];
    const ta = [a[0] + lerp(0.9, -0.5, u), lerp(0.78, 0.86, u), lerp(0, 0.25, u)];
    txt(E, null);
    return { p, t: ta, fov: lerp(30, 37, u), focus: Math.hypot(p[0] - a[0], p[2] - a[2]), dof: lerp(6, 4, u) }; } };
// 3. January 2014: kites. From straight above, down behind them, up into the sky.
S.s03 = { dur: 14, async build(E) {
    K.roadSet(E, { mood: 'kite', x0: 140, x1: 250, seed: 13, field: { amt: 1, z: 5.6, col: '#e3c52c' }, trees: { neem: 2, palm: 1, gulmohar: 0.3 }, dens: 0.5, clear: (x, z) => Math.abs(z) < 36, flowers: 0.65, grass: 3000, grassz: [3.4, 10], cloudv: 12, clouds: 9 });
    K.kites(E, 34, 215, { seed: 9, shadows: true, spread: 150, zs: 90 }); K.milestone(E, 197.5, 3.7, 'GTA VI', '12', 'YRS', { ry: -1.9 });
    const c = {}; c.h = await mk(E, 'hero10', PAL.hero, 's03_hero', 150, 0.3); c.bag = bag(E, c.h); c.a = await mk(E, 'frA10', PAL.frA, 's03_frA', 151.3, -1.3); c.b = await mk(E, 'frB10', PAL.frB, 's03_frB', 150.5, 1.55); return c; },
  frame(t, c, E) { play(c.h, t); play(c.a, t); play(c.b, t); c.bag.step(); const a = anc(c.h, c.h.f); const u = sm2(seg(t, 4.4, 10.6));
    const p = K.mix3([a[0] - 0.4, lerp(17.5, 13.5, sm(seg(t, 0, 4.6))), 0.4], [a[0] - 7.0, 1.3, 2.0], u); p[0] -= 3.4 * Math.sin(Math.PI * u);
    const ta = K.mix3([a[0] + 1.0, 0, 0.2], [a[0] + 17, 5.4, -2.4], u);
    const r = 0.22 * (1 - sm(seg(t, 0, 5))); const uu = sm(seg(t, 4.6, 8.8)); const up = [Math.cos(r) * (1 - uu), uu, Math.sin(r) * (1 - uu)];
    txt(E, t < 5 ? 'JANUARY 2014' : (t > 10.4 ? 'JANUARY 2015' : null), t < 5 ? io(t, 0.7, 4.6) : io(t, 10.6, 13.6));
    return { p, t: ta, up, fov: lerp(36, 41, u), focus: lerp(15, 9, u), dof: lerp(0.5, 2.5, u) }; } };
// 4. July 2016: monsoon. Teenagers now. Low at puddle height, they run past the lens.
S.s04 = { dur: 14, async build(E) {
    K.roadSet(E, { mood: 'monsoon', x0: 300, x1: 400, seed: 14, wet: 1, puddle: 1, trees: { neem: 3, banyan: 1.2, palm: 1.5 }, dens: 1.25, grass: 3000, grassh: 0.12, cloudv: 45, clouds: 22, clear: (x, z) => z > 4.4 && z < 9.5, grassNearMin: 7.4, marks: 0.5 });
    const c = {}; c.h = await mk(E, 'hero16', PAL.hero, 's04_hero', 300, 0.3); c.a = await mk(E, 'frA16', PAL.frA, 's04_frA', 299.0, -1.35); c.b = await mk(E, 'frB16', PAL.frB, 's04_frB', 298.5, 1.6);
    c.rain = K.rainFx(E, {}); c.B = K.bursts(E, 90, { col: '#dfe9ee' }); K.footSplash(E, [c.h, c.a, c.b], c.B); K.mist(E, 10, 300, 400, { op: 0.3, zs: 120, y: 0.5, hk: 0.2 });
    K.milestone(E, 338, 3.75, 'GTA VI', '10', 'YRS', { ry: -1.1 }); K.wipeBox(E, 300 + 3.9 * (14 - 1 / 30) + 0.4, 3.75); return c; },
  frame(t, c, E) { play(c.h, t); play(c.a, t); play(c.b, t); const a = anc(c.h, c.h.f); c.rain.center.set(a[0] + 2, 0, 0);
    const p = [a[0] + K.keys([[0, 9.5], [4.4, 1.3], [14, 0.4]], t), K.keys([[0, 0.38], [5, 0.48], [10, 0.95], [14, 1.15]], t), K.keys([[0, 3.3], [4.4, 4.9], [9, 5.5], [14, 4.5]], t)];
    const ta = [a[0] + K.keys([[0, 0], [4.4, 0.5], [14, 1.4]], t), 0.92, 0.1];
    txt(E, t > 5 ? 'JULY 2016' : null, io(t, 5.2, 9.5));
    return { p, t: ta, fov: 34, focus: Math.hypot(p[0] - a[0], p[2] - a[2]), dof: 4 }; } };
// 5. 2018: the fork. The friends stop and wave; the crane lifts; he runs on alone.
S.s05 = { dur: 14, async build(E) {
    const fx = 414, ang = 0.62; K.roadSet(E, { mood: 'amber', x0: 395, x1: 470, seed: 15, road2: true, trees: { neem: 3, banyan: 1, gulmohar: 1, palm: 0.6 }, dens: 0.9, wet: 0.35, clear: (x, z) => (z > 0 && z < 12 && x < 420) || (z < 0 && Math.abs((x - fx) * Math.sin(ang) + z * Math.cos(ang)) < 6 && x > fx - 4), grass: 2400 });
    E.G.uRoad2.value.set(fx, -0.8, Math.cos(ang), -Math.sin(ang));
    const c = {}; c.h = await mk(E, 'hero16', PAL.hero, 's05_hero', 401.5, 0.4); c.a = await mk(E, 'frA16', PAL.frA, 's05_frA', 400.6, -1.3); c.b = await mk(E, 'frB16', PAL.frB, 's05_frB', 400.0, 1.4);
    K.milestone(E, 424, -3.9, 'GTA VI', '8', 'YRS', { ry: -2.2 }); c.w = K.wipeBox(E, 397.4, 3.4, 0.7, 2.6); K.birds(E, 9, 430, 22, -40, { seed: 6, vx: 6 }); return c; },
  frame(t, c, E) { play(c.h, t); play(c.a, t); play(c.b, t); const a = anc(c.h, c.h.f); const u = sm2(seg(t, 3.2, 8.2)), u2 = sm(seg(t, 8.4, 14));
    K.look(c.h, -78 * io(t, 6.3, 8.6, 0.7), 4 * io(t, 6.3, 8.6, 0.7));
    const p0 = [a[0] - 5.2, 1.45, lerp(3.4, 1.3, sm2(seg(t, 0, 1.1)))]; const p = K.mix3(K.mix3(p0, [404.6, 2.35, 2.3], u), [402.6, 5.4, 3.1], u2);
    const ta = K.mix3(K.mix3([a[0] + 4, 1.1, -0.2], [418, 1.3, -0.5], u), [421, 0.7, -2.2], u2);
    txt(E, t > 8.6 ? '2018' : null, io(t, 8.8, 13.4));
    return { p, t: ta, fov: lerp(36, 38, u), focus: lerp(5.5, 8, u), dof: lerp(3, 2, u) }; } };
// 6. 2019: alone, straight into a huge low sun. A long lens far down the road.
S.s06 = { soft: true, dur: 9, async build(E) {
    K.roadSet(E, { mood: 'amber', x0: 480, x1: 600, seed: 16, trees: { neem: 3, banyan: 1, palm: 1.2 }, dens: 1.3, grass: 1800, clear: (x, z) => Math.abs(z) < 6.2, clouds: 0, hills: false });
    const sd = new V3(-0.995, 0.031, 0.02).normalize(); E.L.uSunDir.value.copy(sd); E.L.uRimDir.value.copy(sd); E.L.uLightDir.value.set(-0.95, 0.24, 0.05).normalize(); E.S.uSky.value.set(60, 0.45, 0, 0); E.S.uHor.value.set('#ff9a52'); E.S.uSunCol.value.set('#fff6d8');
    const c = {}; c.h = await mk(E, 'hero16', PAL.hero, 's06_hero', 500, 0.5); K.milestone(E, 520, -3.8, 'GTA VI', '7', 'YRS', { ry: 1.2 }); K.mist(E, 8, 500, 570, { op: 0.22, zs: 30, y: 0.2, hk: 0.1, col: '#ffc890' }); return c; },
  frame(t, c, E) { play(c.h, t); const a = anc(c.h, c.h.f); txt(E, t > 1 ? '2019' : null, io(t, 1.2, 6));
    return { p: [572, 1.22, 0.75], t: [500, 1.08 + 0.1 * sm(seg(t, 0, 9)), 0.45], fov: lerp(5.2, 4.6, sm(seg(t, 0, 9))), focus: 572 - a[0], dof: 60, maxBlur: 3.2 }; } };
// 7. March 2020: fog. The run becomes a walk. One wide, still frame.
S.s07 = { dur: 9, async build(E) {
    K.roadSet(E, { mood: 'fog', x0: 600, x1: 650, seed: 17, trees: { bare: 3, neem: 1.2 }, dens: 0.9, clouds: 0, hills: false, grass: 1500, marks: 0.5, wind: 0.35 });
    const c = {}; c.h = await mk(E, 'hero23', PAL.hero, 's07_hero', 604.5, 0.4); K.mist(E, 16, 590, 660, { op: 0.5, zs: 60, y: 0.2, hk: 0.16, v: 0.5 }); c.l = K.lamp(E, 628, -4.6, { col: '#fff0d0', pool: 0.16 }); return c; },
  frame(t, c, E) { play(c.h, t); txt(E, 'MARCH 2020', io(t, 1.5, 7.5)); const u = sm(seg(t, 0, 9));
    return { p: [611 + 0.5 * u, 6.6 - 0.25 * u, 17.5 - 1.0 * u], t: [620.5, 1.0, 0], fov: 33, focus: 20, dof: 1.5 }; } };
// 8. 2020-22: the hoarding. Over his shoulder; focus goes to the board and comes back.
S.s08 = { soft: true, dur: 10, async build(E) {
    K.roadSet(E, { mood: 'fog', x0: 640, x1: 690, seed: 18, trees: { bare: 3, neem: 1.5 }, dens: 0.8, clouds: 0, hills: false, grass: 1200, marks: 0.5, wind: 0.35, clear: (x, z) => z < 0 && x > 650 && x < 676 });
    E.L.uFog.value.x = 0.014;
    const c = {}; c.h = await mk(E, 'hero23', PAL.hero, 's08_hero', 655, 0.6);
    K.sign(E, 664.5, -5.6, 7.4, 3.9, (g, w, h) => { g.fillStyle = '#16181f'; g.fillRect(0, 0, w, h); g.fillStyle = '#e9e4d6'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = `700 ${h * 0.44}px Oswald`; g.fillText('GTA V', w / 2, h * 0.36);
      g.font = `500 ${h * 0.115}px Oswald`; g.fillStyle = '#b9b4a6'; g.fillText('NOW ON NEW CONSOLES', w / 2, h * 0.68); g.font = `500 ${h * 0.075}px Oswald`; g.fillText('2013  ·  2014  ·  2015  ·  2020  ·  2022', w / 2, h * 0.86);
      g.save(); g.translate(w * 0.845, h * 0.2); g.rotate(-0.2); g.strokeStyle = '#e04434'; g.lineWidth = 9; g.strokeRect(-w * 0.13, -h * 0.11, w * 0.26, h * 0.22); g.fillStyle = '#e04434'; g.font = `700 ${h * 0.16}px Oswald`; g.fillText('AGAIN', 0, h * 0.012); g.restore(); }, { y0: 3.0 });
    K.mist(E, 10, 640, 690, { op: 0.4, zs: 50, y: 0.2, hk: 0.16, v: 0.4 }); return c; },
  frame(t, c, E) { play(c.h, t); const dn = sm(seg(t, 5.3, 6.6)); K.look(c.h, lerp(30, 4, dn) * sm(seg(t, 0.2, 1.4)) + (1 - sm(seg(t, 0.2, 1.4))) * 6, lerp(-17, 26, dn) * sm(seg(t, 0.2, 1.4)), 10 * dn);
    const y = t < 3.3 ? '2020' : t < 6.4 ? '2021' : '2022'; txt(E, y, Math.min(io(t, 0.4, 9.6), 0.35 + 0.65 * Math.abs(Math.sin(Math.PI * clamp((t % 3.2) / 0.5, 0, 1) * 0.5))));
    const f = K.keys([[0, 2.5], [1.6, 2.5], [2.8, 12.4], [5.6, 12.4], [6.6, 2.6], [10, 4.5]], t); const u = sm(seg(t, 0, 10));
    return { p: [652.9 + 0.25 * u, 1.52, 2.25 - 0.2 * u], t: [661.5, 3.55 - 1.1 * sm(seg(t, 6.6, 9.5)), -3.4], fov: 37, focus: f, dof: 10 }; } };
// 9. 2022-23: a light on the horizon. The lens goes all the way round him; he starts to run.
S.s09 = { dur: 16, async build(E) {
    K.roadSet(E, { mood: 'blue', x0: 690, x1: 780, seed: 19, trees: { neem: 3, palm: 1.4, banyan: 0.8 }, dens: 0.9, grass: 1800, clouds: 12, cloudv: 10, clear: (x, z) => Math.abs(z) < 6.5, grassNearMin: 6.6 });
    const c = {}; c.h = await mk(E, 'hero23', PAL.hero, 's09_hero', 700, 0.3); c.city = K.cityFar(E, 2300, { w: 2100 }); c.city.set(0);
    K.sign(E, 752, -4.8, 4.2, 1.5, (g, w, h) => { g.fillStyle = '#12141c'; g.fillRect(0, 0, w, h); g.fillStyle = '#ffe9a8'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = `700 ${h * 0.5}px Oswald`; g.fillText('COMING 2025', w / 2, h * 0.54); }, { y0: 2.4, emis: '#3a3020' }); return c; },
  frame(t, c, E) { play(c.h, t); const a = anc(c.h, c.h.f, 8); const m = sm(seg(t, 4.6, 8.6)); K.mixMood(E, 'blue', 'pink', m); c.city.set(m);
    K.look(c.h, 0, lerp(8, -9, sm(seg(t, 5.2, 7.4))) * (1 - sm(seg(t, 9.6, 10.6))));
    const th = Math.PI * sm2(seg(t, 1.2, 9.4)); const run = sm(seg(t, 10.2, 12.6)); const R = lerp(4.3, 5.0, run);
    const p = [a[0] + R * Math.cos(th), lerp(1.5, 0.62, run), a[2] + R * Math.sin(th) * 0.8 + 0.25 + 0.5 * run];
    const ta = [a[0] + 2.2 * th / Math.PI + 5 * run, lerp(1.32, 1.5, run), a[2]];
    txt(E, t < 4.5 ? 'FEBRUARY 2022' : (t > 6.4 ? 'DECEMBER 2023' : null), t < 4.5 ? io(t, 0.6, 4.2) : io(t, 6.6, 11));
    return { p, t: ta, fov: lerp(38, 46, run), focus: R, dof: lerp(5, 2, run) }; } };
// 10. May 2025: the first barrier. He stops short. Then the lens pulls back while the lens zooms in: the road stretches away.
S.s10 = { dur: 14, async build(E) {
    K.roadSet(E, { mood: 'pink', x0: 790, x1: 880, seed: 20, trees: { neem: 3, palm: 1.6 }, dens: 0.9, grass: 1800, clouds: 12, cloudv: 10, clear: (x, z) => Math.abs(z) < 8.5 && x > 812, nearLow: true, nearLowZ: 9 });
    const c = {}; c.g = K.gate(E, 822, 'ARRIVING|2025', { emis: '#4a3a20' }); c.h = await mk(E, 'hero23', PAL.hero, 's10_hero', 800, 0.3); c.city = K.cityFar(E, 2400, { w: 2100 }); c.sx = 800 + K.MARKS.s10_hero.stop[2]; return c; },
  frame(t, c, E) { play(c.h, t); const a = anc(c.h, c.h.f, 6);
    const dr = seg(t, 2.6, 3.5); c.g.arm(1.45 * (1 - (dr < 1 ? sm(dr) : 1)) + 0.07 * Math.exp(-(t - 3.5) * 5) * Math.sin((t - 3.5) * 22) * (t > 3.5 ? 1 : 0));
    if (t >= 6.1 && !c.fl) { c.fl = 1; c.g.text('ARRIVING|26 MAY 2026'); } c.g.emis(Math.abs(t - 6.1) < 0.12 ? '#000000' : '#4a3a20');
    const gl = sm2(seg(t, 7.9, 12.4)); c.g.grp.position.x = 822 + 150 * gl;
    K.look(c.h, 0, lerp(-10, 16, sm(seg(t, 9.5, 11.5))) * sm(seg(t, 5.8, 6.6)), 7 * sm(seg(t, 9.8, 11.8)));
    txt(E, t > 5 ? 'MAY 2025' : null, io(t, 5.2, 11));
    if (t < 6.9) { const s = sm(seg(t, 3.4, 6.0)); return { p: [lerp(a[0] + 1.8, 819.5, s), 1.3, lerp(8.0, 6.9, s)], t: [lerp(a[0] + 2.2, 819.9, s), 1.25, 0], fov: 34, focus: 7.5, dof: 3 }; }
    const d = lerp(3.0, 11.0, gl); const fov = 2 * Math.atan(Math.tan(29 * Math.PI / 180) * 3.0 / d) * 180 / Math.PI;
    return { p: [c.sx - d, 1.55, 0.3 + 0.75], t: [c.sx + 6, 1.75, 0.2], fov, focus: d, dof: 2 }; } };
// 11. November 2025: the second barrier simply slides away. He does not even stop. He sits.
S.s11 = { dur: 14, async build(E) {
    const sx = 916.68, sz = 3.84; K.roadSet(E, { mood: 'blue', x0: 890, x1: 960, seed: 21, trees: { neem: 3, palm: 1.2, banyan: 0.8 }, dens: 0.8, grass: 1700, clouds: 12, cloudv: 10, clear: (x, z) => z > 0 && x > 900 && x < 932, nearLow: true, nearLowZ: 12 });
    const c = {}; c.g = K.gate(E, 931, 'ARRIVING|26 MAY 2026', { emis: '#4a3a20', open: 0 }); c.h = await mk(E, 'hero23', PAL.hero, 's11_hero', 900, 1.9); const M = K.MARKS.s11_hero.seat; c.hs = M[3];
    const wx = 900 + M[2], wz = 1.9 - M[0]; const hw = HP + M[3]; K.parapet(E, wx - Math.sin(hw) * 0.06, wz - Math.cos(hw) * 0.06, hw, M[1] - 0.10); c.seat = [wx, wz];
    K.milestone(E, wx + 2.3, wz + 0.5, 'GTA VI', '1', 'YR', { ry: -0.5 }); c.l = K.lamp(E, wx + 3.4, wz + 1.6, { pool: 0.34 }); c.city = K.cityFar(E, 2500, { w: 2100, gop: 0.4 }); return c; },
  frame(t, c, E) { play(c.h, t); if (t >= 2.3 && !c.fl) { c.fl = 1; c.g.text('ARRIVING|19 NOV 2026'); } c.g.emis(Math.abs(t - 2.3) < 0.12 ? '#000000' : '#4a3a20'); c.g.grp.position.x = 931 + 420 * sm2(seg(t, 3.0, 7.5));
    K.look(c.h, 0, 14 * sm(seg(t, 3.2, 4.6)) * (1 - sm(seg(t, 8, 9))), 6 * sm(seg(t, 3.2, 4.6)) * (1 - sm(seg(t, 8, 9))));
    const u = sm2(seg(t, 4.5, 13.5)); txt(E, t > 1 ? 'NOVEMBER 2025' : null, io(t, 1.2, 8));
    E.P.uFadeCol.value.set('#0a0f24'); E.P.uPost.value.z = 0.75 * sm(seg(t, 13.3, 14));
    return { p: K.mix3([905.5, 1.7, 13.5], [c.seat[0] + 0.9, 0.98, c.seat[1] + 5.4], u), t: K.mix3([916, 1.6, 0.5], [c.seat[0] + 0.15, 0.82, c.seat[1]], u), fov: lerp(36, 31, u), focus: lerp(15, 5.0, u), dof: lerp(1.5, 6, u) }; } };
// 12. The wait. One year in twelve seconds: the sky hurries, he only sits.
const MON = ['DECEMBER 2025', 'JANUARY 2026', 'FEBRUARY 2026', 'MARCH 2026', 'APRIL 2026', 'MAY 2026', 'JUNE 2026', 'JULY 2026', 'AUGUST 2026', 'SEPTEMBER 2026', 'OCTOBER 2026', 'NOVEMBER 2026'];
S.s12 = { dur: 12, async build(E) {
    const sx = 916.68, sz = 3.84; K.roadSet(E, { mood: 'night', x0: 890, x1: 960, seed: 21, trees: { neem: 3, palm: 1.2, banyan: 0.8 }, dens: 0.8, grass: 1700, clouds: 14, cloudv: 420, clear: (x, z) => z > 0 && x > 900 && x < 932, nearLow: true, nearLowZ: 12, wet: 0.5, puddle: 0.6 });
    const c = { hs: K.MARKS.s11_hero.seat[3] }; c.h = await mk(E, 'hero23', PAL.hero, 's12_hero', 0, 0); const M = K.MARKS.s12_hero.seat; const hw = HP + c.hs; K.placeBy(c.h, M, sx, sz, hw - M[3]);
    K.parapet(E, sx - Math.sin(hw) * 0.06, sz - Math.cos(hw) * 0.06, hw, M[1] - 0.10); c.st = K.milestone(E, sx + 2.3, sz + 0.5, 'GTA VI', '1', 'YR', { ry: -0.5 }); c.l = K.lamp(E, sx + 3.4, sz + 1.6, { pool: 0.4 });
    c.rain = K.rainFx(E, { op: 0.5 }); c.rain.center.set(sx, 0, sz); c.mist = K.mist(E, 8, 900, 935, { op: 0.5, zs: 16, y: 0.2, hk: 0.2, v: 3, col: '#5a6a9a' }); c.city = K.cityFar(E, 2500, { w: 2100, gop: 0.35 }); c.k = -1; c.seat = [sx, sz]; return c; },
  frame(t, c, E) { play(c.h, t); const k = Math.min(11, Math.floor(t / 12 * 12)); if (k !== c.k) { c.k = k; const left = 11 - k; c.st.userData.set('GTA VI', left > 0 ? String(left) : '1', left > 1 ? 'MONTHS' : left === 1 ? 'MONTH' : 'DAY'); }
    c.rain.op = io(t, 3.2, 7.2, 1.2); for (const m of c.mist) m.u.uOpacity.value = 0.45 * io(t, 7.5, 11, 1.2); c.l.set(0.82 + 0.18 * Math.sin(t * 37) * Math.sin(t * 5.1));
    const a = t / 12 * 2.2 - 1.1; E.S.uMoonDir.value.set(Math.sin(a) * 0.8, 0.5 * Math.cos(a) + 0.25, -0.75).normalize(); E.S.uSky.value.w = t * 14;
    txt(E, MON[k], io(t, 0.3, 11.7)); const u = sm(seg(t, 0, 12)); E.P.uFadeCol.value.set('#0a0f24'); E.P.uPost.value.z = 0.75 * (1 - sm(seg(t, 0, 0.7)));
    return { p: [c.seat[0] + 0.9 - 0.25 * u, 0.98, c.seat[1] + 5.4 - 0.9 * u], t: [c.seat[0] + 0.15, 0.82, c.seat[1]], fov: 31, focus: 5.0 - 0.9 * u, dof: 4 }; } };
// M1. Still waiting. The lens leaves him and sinks to a puddle at his feet; a warm light flickers in it that is not the street's.
S.m1 = { soft: true, dur: 10, about: 'into the puddle: a memory begins', async build(E) {
    const sx = 916.68, sz = 3.84; K.roadSet(E, { mood: 'night', x0: 890, x1: 960, seed: 21, trees: { neem: 3, palm: 1.2, banyan: 0.8 }, dens: 0.8, grass: 1700, clouds: 14, cloudv: 30, clear: (x, z) => z > 0 && x > 900 && x < 932, nearLow: true, nearLowZ: 12, wet: 0.6, puddle: 0.9 });
    const c = { hs: K.MARKS.s11_hero.seat[3] }; c.h = await mk(E, 'hero23', PAL.hero, 'm1_hero', 0, 0); const M = K.MARKS.m1_hero.seat; const hw = HP + c.hs; K.placeBy(c.h, M, sx, sz, hw - M[3]);
    K.parapet(E, sx - Math.sin(hw) * 0.06, sz - Math.cos(hw) * 0.06, hw, M[1] - 0.10); K.milestone(E, sx + 2.3, sz + 0.5, 'GTA VI', '1', 'DAY', { ry: -0.5 }); c.l = K.lamp(E, sx + 3.4, sz + 1.6, { pool: 0.4 });
    await city(E); c.city = K.viceCity(E, 1052, { towers: 10 }); c.seat = [sx, sz]; c.pud = [sx + 0.25, sz - 2.3];
    c.tv = E.card(E.tex.glow, { x: c.pud[0], y: 0.035, z: c.pud[1], w: 1.5, h: 0.95, ry: 0, rx: -HP, center: true, tint: '#ffd9a0', add: true, fog: 0, blur: 0, op: 0 }); c.tv2 = E.card(E.tex.glow, { x: c.pud[0] + 0.1, y: 0.036, z: c.pud[1] - 0.05, w: 0.7, h: 0.45, ry: 0, rx: -HP, center: true, tint: '#8ab8ff', add: true, fog: 0, blur: 0, op: 0 });
    c.mist = K.mist(E, 6, 900, 935, { op: 0.35, zs: 16, y: 0.2, hk: 0.2, v: 1, col: '#5a6a9a' }); return c; },
  frame(t, c, E) { play(c.h, t); const u = sm2(seg(t, 1.2, 8.2)); K.look(c.h, -14 * sm(seg(t, 1, 3)), 24 * sm(seg(t, 1, 3.2)), 8 * sm(seg(t, 1, 3.2)));
    const fl = 0.75 + 0.25 * Math.sin(t * 31) * Math.sin(t * 7.3); c.tv.u.uOpacity.value = 0.85 * sm(seg(t, 4.2, 7.5)) * fl; c.tv2.u.uOpacity.value = 0.6 * sm(seg(t, 5, 8)) * (0.6 + 0.4 * Math.sin(t * 19));
    E.P.uFadeCol.value.set('#ffe9c8'); E.P.uPost.value.z = sm(seg(t, 8.6, 10)); txt(E, null);
    return { p: K.mix3([c.seat[0] + 0.65, 0.98, c.seat[1] + 4.5], [c.pud[0] + 0.5, 0.20, c.pud[1] + 1.25], u), t: K.mix3([c.seat[0] + 0.15, 0.82, c.seat[1]], [c.pud[0], 0.0, c.pud[1]], u), fov: lerp(31, 26, u), focus: lerp(4.1, 1.35, u), dof: lerp(4, 9, u) }; } };
// M2. The room. One turn of the lens round the edge of the same bed, and ten years go by: 2013, 2016, 2023.
S.m2 = { dur: 22, about: 'memories: the same bed edge at ten, sixteen and twenty-three', async build(E) {
    K.setMood(E, 'room'); E.sky.visible = false; const c = { T1: 8.5, T2: 15 }; c.room = K.room(E, { w: 5.4, d: 6.6, screen: t => K.gameScreen(t) });
    const seat = async (asset, pal, track, x) => { const a = await mk(E, asset, pal, track, 0, 0); const M = K.MARKS[track].seat; K.placeBy(a, M, x, 0.14, Math.PI - M[3]); a.seatY = M[1] - 0.10 * a.meta.S; a.off = 1.6; return a; };
    c.A = [await seat('hero10', PAL.hero, 'm2_hero10', 0), await seat('frA10', PAL.frA, 'm2_frA10', -0.62), await seat('frB10', PAL.frB, 'm2_frB10', 0.6)];
    c.B = [await seat('hero16', PAL.hero, 'm2_hero16', 0), await seat('frB16', PAL.frB, 'm2_frB16', 0.62)]; c.C = [await seat('hero23', PAL.hero, 'm2_hero23', 0)];
    for (const a of [...c.A, ...c.B, ...c.C]) a.outline.visible = true;
    // faces are lit by the screen; the lamp behind them gives the rim
    E.L.uLightDir.value.set(0.1, 0.22, -1).normalize(); E.L.uRimDir.value.set(0.45, 0.4, 0.8).normalize(); E.L.uRimCol.value.set('#ffb070'); return c; },
  frame(t, c, E) { const st = t < c.T1 ? 0 : t < c.T2 ? 1 : 2; const G = [c.A, c.B, c.C]; const t0 = [0, c.T1, c.T2][st];
    G.forEach((g, i) => g.forEach(a => a.show(i === st))); const bedH = G[st][0].seatY; if (c.st !== st) { c.st = st; c.room.setBed(bedH, ['#c8503c', '#3a5a9a', '#4a4a52'][st]); }
    for (const a of G[st]) { play(a, t - t0); a.mesh.position.y = bedH - a.seatY; a.mat.uniforms.uSmile.value = st === 2 ? sm(seg(t, 17.5, 19)) : 0.75; }
    const tr = st === 2 ? sm(seg(t, c.T2 + 1.2, c.T2 + 2.2)) : 0;   // 2023: the game stops, a trailer plays
    c.room.screen.draw(tr > 0.5 ? (g, w, h) => { K.gameScreen(t, { a: '#3a1a6a', b: '#ff5aa8', car: '#ffc84a', label: '' })(g, w, h); g.fillStyle = 'rgba(20,6,40,0.35)'; g.fillRect(0, 0, w, h); g.fillStyle = '#fff'; g.textAlign = 'center'; g.font = '700 86px Oswald'; g.fillText('VI', w / 2, h * 0.52); g.font = '500 24px Oswald'; g.fillText('TRAILER 1', w / 2, h * 0.72); } : K.gameScreen(t, st === 1 ? { car: '#4ae8ff', a: '#1a2a6a', b: '#ff9a5a' } : {}));
    const fl = 0.8 + 0.2 * Math.sin(t * 23) * Math.sin(t * 5.1 + 1); const sc = tr > 0.5 ? new THREE.Color('#ff9ad0') : new THREE.Color('#a8c8ff'); E.L.uLightCol.value.copy(sc).multiplyScalar(1.05 * fl); c.room.tvGlow.u.uTint.value.copy(sc); c.room.tvGlow.u.uTint2.value.copy(sc); c.room.tvGlow.u.uOpacity.value = 0.4 * fl;
    const flash = Math.exp(-Math.pow((t - c.T1) / 0.11, 2)) + Math.exp(-Math.pow((t - c.T2) / 0.11, 2));   // the screen whites out for a moment: years pass
    E.P.uFadeCol.value.set(t < 1.5 ? '#ffe9c8' : t > 20 ? '#ffd0e4' : '#ffffff'); E.P.uPost.value.z = Math.max(1 - sm(seg(t, 0, 1.4)), 0.9 * Math.min(1, flash), sm(seg(t, 20.6, 22)));
    txt(E, ['2013', '2016', 'DECEMBER 2023'][st], io(t, t0 + 0.6, [c.T1, c.T2, 21][st] - 0.3, 0.5));
    const th = K.keys([[0, -0.14 * PI], [3.5, 0.06 * PI], [8.5, 0.74 * PI], [15, 1.32 * PI], [22, 2.0 * PI]], t), front = Math.pow(Math.sin(clamp(th, 0, 2 * PI) / 2), 2), r = 1.95 - 0.25 * front;
    return { p: [r * Math.sin(th), lerp(1.28, 0.86, front), 0.05 + r * Math.cos(th)], t: [0, lerp(0.98, 0.84, front) + 0.06 * st, lerp(-0.7, 0.12, front)], fov: lerp(40, 33, front), focus: lerp(2.6, r, front), dof: 3.5, line: 1.4 }; } };
// 13. 18 November 2026: the friends come back for him. The crane rises: everybody is on the road tonight.
S.s13 = { dur: 18, async build(E) {
    const sx = 916.68, sz = 3.84; K.roadSet(E, { mood: 'neon', x0: 880, x1: 990, seed: 21, trees: { neem: 3, palm: 1.2, banyan: 0.8 }, dens: 0.8, grass: 1700, clouds: 12, cloudv: 12, clear: (x, z) => z > 0 && x > 900 && x < 932, nearLow: true, nearLowZ: 12 });
    const c = { hs: K.MARKS.s11_hero.seat[3] }; const hw = HP + c.hs; c.h = await mk(E, 'hero23', PAL.hero, 's13_hero', 0, 0); const M = K.MARKS.s13_hero.seat; K.placeBy(c.h, M, sx, sz, hw - M[3]);
    K.parapet(E, sx - Math.sin(hw) * 0.06, sz - Math.cos(hw) * 0.06, hw, M[1] - 0.10); K.milestone(E, sx + 2.3, sz + 0.5, 'GTA VI', '1', 'DAY', { ry: -0.5 }); c.l = K.lamp(E, sx + 3.4, sz + 1.6, { pool: 0.4 }); await city(E); c.city = K.viceCity(E, 1052, { towers: 14 });
    c.a = await mk(E, 'frA23', PAL.frA, 's13_frA', 0, 0); K.placeBy(c.a, K.MARKS.s13_frA.stop, sx - 1.5, sz - 1.7, HP); c.b = await mk(E, 'frB23', PAL.frB, 's13_frB', 0, 0); K.placeBy(c.b, K.MARKS.s13_frB.stop, sx - 2.6, sz - 2.9, HP);
    const R = rng(77); const L = []; for (let i = 0; i < 24; i++) { const as = CR[i % 4]; const kind = ['walk', 'jog', 'walk', 'jog', 'run'][i % 5]; const v = kind === 'walk' ? 1.3 : kind === 'jog' ? 2.7 : 3.5; const tin = 7.5 + i * 0.36; L.push([as, `cr_${as}_${kind}`, sx - 14 - v * tin + R() * 3, -2.4 + 4.6 * R(), R() * 0.6, i, false]); }
    c.cr = await crowd(E, L); c.seat = [sx, sz]; return c; },
  frame(t, c, E) { play(c.h, t); play(c.a, t); play(c.b, t); for (const a of c.cr) play(a, t);
    K.look(c.h, -62 * io(t, 3.6, 6.7, 0.8), -8 * io(t, 3.6, 6.7, 0.8)); c.h.mat.uniforms.uSmile.value = 0.8 * sm(seg(t, 4.6, 6));
    const u = sm2(seg(t, 9.0, 16.5)); txt(E, t > 10.5 ? '18 NOVEMBER 2026' : null, io(t, 10.8, 17.4), '11:42 PM');
    return { p: K.mix3([c.seat[0] + 2.6, 1.3, c.seat[1] + 6.0], [c.seat[0] - 12, 11.5, c.seat[1] + 6.5], u), t: K.mix3([c.seat[0] - 0.9, 1.05, c.seat[1] - 0.9], [c.seat[0] + 46, 0, 0], u), fov: lerp(33, 40, u), focus: lerp(6.2, 30, u), dof: lerp(4.5, 0.6, u) }; } };
// 14. Midnight. The gate. Three set-ups: his face, the crowd before the clock, the boom going up.
S.s14 = { soft: t0 => t0 < 4.9, dur: 16, async build(E) {
    const G = 1040; K.roadSet(E, { mood: 'neon', x0: 1010, x1: 1080, seed: 23, trees: { neem: 2, palm: 2 }, dens: 0.8, grass: 1400, clouds: 10, cloudv: 10, clear: (x, z) => Math.abs(z) < 9 && x > 1020 });
    const c = { G }; c.g = K.gate(E, G, 'UNLOCKS IN|00:00:10', { emis: '#6a5630', open: 0 }); await city(E); c.city = K.viceCity(E, G + 12, { towers: 16 });
    c.h = await mk(E, 'hero23', PAL.hero, 's14_hero', G - 3.4, 0.4); c.a = await mk(E, 'frA23', PAL.frA, 's14_frA', G - 3.1, -1.0); c.b = await mk(E, 'frB23', PAL.frB, 's14_frB', G - 3.8, 1.7);
    const R = rng(5); const L = []; for (let i = 0; i < 26; i++) { const as = CR[i % 4]; L.push([as, 'crg_' + as, G - 4.6 - Math.floor(i / 5) * 1.45 - R() * 0.7, -3.9 + (i % 5) * 1.95 + R() * 0.7, R() * 0.8, i + 2, i < 12]); }
    c.cr = await crowd(E, L); K.lamp(E, G - 6, 6.2, { pool: 0.3 }); K.lamp(E, G - 6, -6.2, { pool: 0.3 }); return c; },
  frame(t, c, E) { const G = c.G; play(c.h, t); play(c.a, t); play(c.b, t); for (const a of c.cr) play(a, t);
    const n = Math.max(0, Math.ceil(10 - t)); if (n !== c.n) { c.n = n; c.g.text(n > 0 ? `UNLOCKS IN|00:00:${String(n).padStart(2, '0')}` : 'WELCOME TO|VI'); } c.g.emis(n === 0 ? '#9a8050' : '#6a5630'); c.g.arm(1.5 * sm2(seg(t, 10.0, 11.7)));
    K.look(c.h, 0, -13 * (1 - sm(seg(t, 10.6, 11.6)))); c.h.mat.uniforms.uSmile.value = sm(seg(t, 10.1, 11)); for (const a of [c.a, c.b]) a.mat.uniforms.uSmile.value = sm(seg(t, 10.1, 10.8));
    txt(E, t < 4.4 ? '18 NOVEMBER 2026' : null, io(t, 0.4, 4.3), '11:59 PM');
    if (t < 4.6) { const u = sm(seg(t, 0, 4.6)); return { p: [G - 2.75 + 0.1 * u, 1.56, 0.4 + 2.5 - 0.35 * u], t: [G - 3.4, 1.60, 0.35], fov: 27, focus: 2.25, dof: 12 }; }
    if (t < 10.25) { const u = sm(seg(t, 4.6, 10.25)); return { p: [G - 10.5 + 0.9 * u, 2.75 - 0.2 * u, 0.9], t: [G, 4.4 + 0.5 * u, 0], fov: 38, focus: 10, dof: 4 }; }
    const u = sm(seg(t, 10.25, 16)); return { p: [G + 3.6 + 0.4 * u, 0.5 + 0.1 * u, 1.9], t: [G - 3, 1.7 - 0.2 * u, 0.2], fov: 42, focus: 5, dof: 2.5 }; } };
// 15. The goodbye. He looks back: far down the road, in his own light, the boy of 2013 waves. He waves back. The boy walks home; the old board goes dark.
S.s15 = { soft: true, dur: 24, about: 'the goodbye to the boy of 2013 and to GTA V', async build(E) {
    const G = 1040; K.roadSet(E, { mood: 'neon', x0: 990, x1: 1080, seed: 23, trees: { neem: 2, palm: 2 }, dens: 0.8, grass: 1400, clouds: 10, cloudv: 10, clear: (x, z) => Math.abs(z) < 9 && x > 1020 });
    const c = { G }; c.g = K.gate(E, G, 'WELCOME TO|VI', { emis: '#9a8050', open: 1.5 }); await city(E); c.city = K.viceCity(E, G + 12, { towers: 12 });
    c.h = await mk(E, 'hero23', PAL.hero, 's15_hero', G + 3.2, 0.3); c.hx = G + 3.2 + K.MARKS.s15_hero.turned[2]; c.kx = G - 34;
    c.kid = await mk(E, 'hero10', PAL.hero, 's15_kid', c.kx, 0.9); const col = h => ({ value: new THREE.Color(h) });
    for (const m of [c.kid.mat, c.kid.omat]) { m.uniforms.uLightCol = col('#ffe6b8'); m.uniforms.uShadeCol = col('#c9a8a0'); m.uniforms.uAmbCol = col('#40302a'); m.uniforms.uFog = { value: new THREE.Vector4(0.0006, 0.01, 0.3, 0) }; m.uniforms.uLightDir = { value: new V3(0.6, 0.5, 0.6).normalize() }; m.uniforms.uRimCol = col('#ffd9a0'); }
    for (const m of [c.h.mat]) { m.uniforms.uLightCol = col('#d2b4ea'); m.uniforms.uLightDir = { value: E.L.uLightDir.value.clone() }; m.uniforms.uShadeCol = { value: E.L.uShadeCol.value.clone() }; } c.hl = c.h.mat.uniforms;
    c.kid.mat.uniforms.uSmile.value = 0.9; c.kg = K.glow(E, c.kx, 1.2, 0.9, 9, '#ffcf8a', { op: 0.5 }); c.pool = E.card(E.tex.glow, { x: c.kx - 3, y: 0.03, z: 0.9, w: 22, h: 12, ry: 0, rx: -HP, center: true, tint: '#ffcf8a', add: true, fog: 0, blur: 0, op: 0.55 });
    c.far = K.glow(E, c.kx - 16, 1.6, 0.9, 16, '#ffd9a0', { op: 0 });
    K.milestone(E, c.kx + 0.8, 3.6, 'GTA VI', '13', 'YRS', { ry: 1.9 });
    const board = on => (g, w, h) => { g.fillStyle = on ? '#16181f' : '#0c0d12'; g.fillRect(0, 0, w, h); g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = on ? '#f2ead8' : '#3a3a40'; g.font = `700 ${h * 0.42}px Oswald`; g.fillText('GTA V', w / 2, h * 0.33);
      g.font = `500 ${h * 0.13}px Oswald`; g.fillStyle = on ? '#f0bd2c' : '#33322c'; g.fillText('2013  —  2026', w / 2, h * 0.64); g.font = `500 ${h * 0.085}px Oswald`; g.fillStyle = on ? '#b9b4a6' : '#2c2c30'; g.fillText('THANK YOU FOR THE RIDE', w / 2, h * 0.85); };
    c.sign = K.sign(E, c.kx - 4.5, -5.4, 7.4, 3.9, board(true), { y0: 3.0, ry: HP, emis: '#4a4030' }); c.board = board; c.on = true;
    c.fw = K.fireworks(E, [[6.2, G + 420, 95, -60, '#ff7aa8', 34, 9], [9.3, G + 480, 120, 70, '#ffd27a', 38, 9], [13.6, G + 400, 80, 20, '#8ad8ff', 30, 8], [16.6, G + 520, 130, -20, '#ff9a6a', 40, 9]]); return c; },
  frame(t, c, E) { play(c.h, t); play(c.kid, t); const sm_ = sm(seg(t, 6.3, 7.6)); c.h.mat.uniforms.uSmile.value = 0.35 + 0.65 * sm_ * (1 - 0.35 * sm(seg(t, 19, 22)));
    if (t >= 5.6 && !c.warm) { c.warm = 1; c.hl.uLightCol.value.set('#e8c0a0'); c.hl.uLightDir.value.set(-0.72, 0.32, 0.6).normalize(); c.hl.uShadeCol.value.set('#7a628e'); }
    c.h.blinkHold = sm(seg(t, 14.6, 14.9)) * (1 - sm(seg(t, 15.5, 15.9)));   // the eyes close for a moment when the hand comes down
    const flick = t > 20.3 && t < 21.0 ? (Math.sin(t * 90) > 0.2 ? 1 : 0) : (t < 20.3 ? 1 : 0); const on = flick > 0.5; if (on !== c.on) { c.on = on; K.redraw(c.sign.userData.tex, c.board(on)); c.sign.userData.board.material.uniforms.uEmis.value.set(on ? '#4a4030' : '#000000'); }
    const inB = t >= 5.6 && t < 11.2; const go = sm(seg(t, 19.0, 24)); c.kg.u.uOpacity.value = (inB ? 0.10 : 0.5) * (1 - go); c.pool.u.uOpacity.value = inB ? 0.2 : 0.55; c.kid.mat.uniforms.uLightCol.value.set(inB ? '#c8a888' : '#ffe6b8'); c.kid.mat.uniforms.uRimCol.value.set(inB ? '#806850' : '#ffd9a0'); c.far.u.uOpacity.value = 0.75 * go; const ka = anc(c.kid, c.kid.f, 6); c.kg.m.position.x = ka[0]; if (t > 23.2 && c.kid.visible) c.kid.show(false);
    txt(E, null); const kidHead = [ka[0], 1.02, ka[2]];
    if (t < 5.6) { const u = sm2(seg(t, 0, 5.6)); const f = sm2(seg(t, 3.6, 5.0));   // A: over his shoulder, the lens closes in on the far figure
      return { p: [c.hx + 2.6 - 0.3 * u, 1.62, 0.3 + 0.95], t: [c.kx, 1.0 + 0.5 * (1 - u), 0.6], fov: lerp(34, 13.5, sm2(seg(t, 2.2, 5.4))), focus: lerp(2.6, 40, f), dof: 26, maxBlur: 4.5 }; }
    if (t < 11.2) { const u = sm(seg(t, 5.6, 11.2)); const f = sm2(seg(t, 8.3, 9.5));   // B: low behind the boy's waving hand: two lights in one frame, focus goes from him to the man
      return { p: [c.kx - 1.75 + 0.2 * u, 0.98, 0.9 + 0.62], t: [c.hx, 1.42, 0.5], fov: 27, focus: lerp(1.9, 38, f), dof: 22, maxBlur: 4.2 }; }
    const lk = sm(seg(t, 11.2, 12)); K.look(c.h, 10 * lk * (1 - sm(seg(t, 17, 18))), -4 * lk);
    if (t < 17.4) { const u = sm(seg(t, 11.2, 17.4));   // C: his face, the hand still in the air
      return { p: [c.hx - 2.35 + 0.35 * u, 1.50, 0.3 + 1.05 - 0.1 * u], t: [c.hx, 1.53, 0.3], fov: lerp(31, 27, u), focus: 2.5 - 0.3 * u, dof: 12 }; }
    const u = sm(seg(t, 17.4, 24));   // D: from behind him, a long lens: the boy turns and walks home into the light
    return { p: [c.hx + 2.4, 1.64, 0.3 + 1.0], t: [lerp(c.kx, ka[0], 0.6), 1.05, 0.7], fov: lerp(13, 11.5, u), focus: 40, dof: 24, maxBlur: 4.2 }; } };
// 0. Before the title: ten seconds of the ending. The boy waves at the far neon gate; the man waves back; black; "13 years earlier".
//    (the same staging as shot 15, seen at its two strongest moments, with one spoken line laid over it)
S.s00 = { soft: true, dur: 10, about: 'cold open: the goodbye, before the story starts', build: E => S.s15.build(E),
  frame(t, c, E) { const ts = t < 4.1 ? 6.3 + t : 11.5 + (t - 4.1) * 0.95; const cam = S.s15.frame(Math.min(ts, 15.4), c, E);
    const cut = Math.exp(-Math.pow((t - 4.1) / 0.07, 2)); E.P.uFadeCol.value.set('#000000'); E.P.uPost.value.z = Math.max(1 - sm(seg(t, 0, 0.9)), 0.85 * cut, sm(seg(t, 7.3, 8.0)));
    const a = sm(seg(t, 8.15, 8.6)) * (1 - sm(seg(t, 9.55, 9.95)));
    E.text(a > 0.004 ? (g, W, H) => { const s = H / 1440; g.globalAlpha = a; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = '#fff4e2'; g.font = `500 ${64 * s}px Oswald`; g.letterSpacing = `${16 * s}px`; g.fillText('13 YEARS EARLIER', W / 2, H / 2); g.letterSpacing = '0px'; g.globalAlpha = 1; } : null);
    if (t < 4.1) cam.focus = lerp(1.9, 38, sm2(seg(t, 2.0, 3.2))); return cam; } };
// 16. Through the gate. The crane lifts over all of them, towards the lights.
S.s16 = { dur: 16, about: 'through the gate, crane over the crowd', async build(E) {
    const G = 1040; K.roadSet(E, { mood: 'neon', x0: 1010, x1: 1140, seed: 23, trees: { neem: 2, palm: 2 }, dens: 0.8, grass: 1400, clouds: 10, cloudv: 10, clear: (x, z) => Math.abs(z) < 9 && x > 1020 });
    const c = { G }; c.g = K.gate(E, G, 'WELCOME TO|VI', { emis: '#9a8050', open: 1.5 }); await city(E); c.city = K.viceCity(E, G + 12, { towers: 22 });
    c.h = await mk(E, 'hero23', PAL.hero, 's16_hero', G + 2, 0.3); c.a = await mk(E, 'frA23', PAL.frA, 's16_frA', G + 0.6, -1.3); c.b = await mk(E, 'frB23', PAL.frB, 's16_frB', G + 0.2, 1.6);
    const R = rng(8); const L = []; for (let i = 0; i < 30; i++) { const as = CR[i % 4]; const kind = i % 3 ? 'run' : 'jog'; L.push([as, `cr_${as}_${kind}`, G - 3 - i * 1.5 - R() * 2, -3.6 + 7.2 * R(), R() * 0.7, i + 1, i < 8]); }
    c.cr = await crowd(E, L); const F = []; const cols = ['#ff7aa8', '#ffd27a', '#8ad8ff', '#ff9a6a', '#c8a0ff', '#9affc0']; for (let i = 0; i < 22; i++) F.push([1.6 + i * 0.62 + R() * 0.3, G + 380 + R() * 260, 90 + R() * 110, -190 + R() * 380, cols[i % 6], 30 + R() * 14, 8 + R() * 3]); c.fw = K.fireworks(E, F);
    K.lamp(E, G - 6, 6.2, { pool: 0.3 }); K.lamp(E, G - 6, -6.2, { pool: 0.3 }); return c; },
  frame(t, c, E) { play(c.h, t); play(c.a, t); play(c.b, t); for (const a of c.cr) play(a, t); const G = c.G; const u = sm2(seg(t, 1.0, 12));
    for (const a of [c.h, c.a, c.b]) a.mat.uniforms.uSmile.value = 0.9; txt(E, t > 9 ? '19 NOVEMBER 2026' : null, io(t, 9.2, 15.4), '12:00 AM');
    return { p: K.mix3([G - 9, 1.15, 1.2], [G - 22, 21, 5], u), t: K.mix3([G + 8, 2.2, 0], [G + 240, 30, 0], u), fov: lerp(38, 44, u), focus: 14, dof: lerp(2.5, 0.3, u) }; } };
// 16b. The boulevard. They slow to a walk and look up; the lens walks backwards before them, then climbs as the night turns to first light and the sign comes on.
S.s16b = { dur: 16, about: 'the city: Now, it is all yours', async build(E) {
    const G = 1040; K.roadSet(E, { mood: 'neon', x0: 1060, x1: 1200, seed: 29, trees: { palm: 1 }, dens: 0.05, grass: 300, clouds: 12, cloudv: 14, clear: (x, z) => true, half: 6.4, shoulder: 0.8, wet: 0.55, puddle: 0.5, hills: false });
    const c = { G }; await city(E); c.city = K.viceCity(E, G + 12, { towers: 26, len: 330 }); c.pave = true; const X = G + 112;
    c.h = await mk(E, 'hero23', PAL.hero, 's16b_hero', X, 0.2); c.a = await mk(E, 'frA23', PAL.frA, 's16b_frA', X - 0.9, -1.5); c.b = await mk(E, 'frB23', PAL.frB, 's16b_frB', X - 1.2, 1.7);
    const R = rng(12); const L = []; for (let i = 0; i < 18; i++) { const as = CR[i % 4]; L.push([as, 'cw_' + as, X - 3.5 - i * 1.3 - R() * 1.5, -4.2 + 8.4 * R(), R() * 0.9, i + 3, i < 6]); } c.cr = await crowd(E, L);
    c.s1 = K.neonSign(E, G + 236, 19.5, 0, 62, 8.4, "NOW, IT'S ALL YOURS", { col: '#ff5aa8', core: '#fff0f6', size: 0.6 }); c.s2 = K.neonSign(E, G + 236, 10.6, 0, 26, 8.2, 'GTA 6', { col: '#ffc84a', core: '#fff6dc', legs: false, size: 0.86 });
    const F = []; const cols = ['#ff7aa8', '#ffd27a', '#8ad8ff', '#ff9a6a', '#c8a0ff', '#9affc0']; for (let i = 0; i < 16; i++) F.push([7.6 + i * 0.5 + R() * 0.3, G + 400 + R() * 240, 90 + R() * 100, -200 + R() * 400, cols[i % 6], 30 + R() * 14, 8 + R() * 3]); c.fw = K.fireworks(E, F); return c; },
  frame(t, c, E) { play(c.h, t); play(c.a, t); play(c.b, t); for (const a of c.cr) play(a, t); const G = c.G; const a = anc(c.h, c.h.f);
    const dawn = sm(seg(t, 7.5, 14.5)); K.mixMood(E, 'neon', 'vice', dawn); E.G.uRoad.value.w = 0.55; E.G.uGTint.value.set('#5a4a70').lerp(new THREE.Color('#8a6a80'), dawn);   // between the hotels the ground is paving, not field c.city.beams.set(1 - 0.7 * dawn);
    K.look(c.h, 22 * Math.sin(t * 0.55), -16 - 6 * Math.sin(t * 0.8 + 1)); K.look(c.a, -30 * Math.sin(t * 0.5 + 1), -18); K.look(c.b, 34 * Math.sin(t * 0.45 + 2.2), -14); for (const q of [c.h, c.a, c.b]) q.mat.uniforms.uSmile.value = 0.85;
    c.s1.set(c.s1.n * seg(t, 8.4, 11.6)); c.s2.set(t > 12.2 ? (Math.sin(t * 70) > -0.3 || t > 12.7 ? 5 : 0) : 0);
    const u = sm2(seg(t, 5.2, 12.5)); const a1 = sm(seg(t, 13.2, 14.2)), fo = 1 - sm(seg(t, 15.2, 15.95)); E.P.uPost.value.z = sm(seg(t, 15.2, 16)); E.P.uFadeCol.value.set('#000000');
    E.text((g, W, H) => { const s = H / 1440; g.textAlign = 'center'; g.textBaseline = 'middle'; g.shadowColor = 'rgba(0,0,0,0.6)'; g.shadowBlur = 24 * s; g.fillStyle = '#fff4e2'; g.globalAlpha = a1 * fo;
      g.font = `500 ${46 * s}px Oswald`; g.letterSpacing = `${12 * s}px`; g.fillText('THE LONG ROAD TO VI', W / 2, H * 0.865); g.fillRect(W / 2 - 60 * s, H * 0.895, 120 * s, 3 * s); g.font = `500 ${30 * s}px Oswald`; g.letterSpacing = `${6 * s}px`; g.globalAlpha = a1 * fo * 0.85; g.fillText('13 YEARS  ·  4,811 DAYS  ·  19 NOVEMBER 2026', W / 2, H * 0.93); g.letterSpacing = '0px'; g.globalAlpha = 1; });
    const p = K.mix3([a[0] + 4.4, 0.95, 1.5], [G + 126, 15.5, 1.5], u), ta = K.mix3([a[0], 1.5, 0.1], [G + 236, 14.6, 0], u);
    return { p, t: ta, fov: lerp(36, 39, u), focus: lerp(4.5, 60, u), dof: lerp(4, 0.4, u) }; } };
// 17. And beside the road, a new stone.
S.s17 = { soft: true, dur: 8, async build(E) {
    K.roadSet(E, { mood: 'gold', x0: -30, x1: 60, seed: 31, clear: (x, z) => z > 0 && z < 15 && x > -9 && x < 9, flowers: 0.2 });
    const c = { stone: K.milestone(E, 0.6, 4.25, 'GTA VII', '?', 'YRS') }; c.h = await mk(E, 'hero23', PAL.hero, 's17_hero', 0, 0); K.placeBy(c.h, K.MARKS.s17_hero.stop, 2.1, 0.5, HP); return c; },
  frame(t, c, E) { play(c.h, t); const lk = io(t, 3.5, 7.0, 0.7); K.look(c.h, -80 * lk + 9 * Math.sin((t - 5.3) * 9) * io(t, 5.2, 6.6, 0.4), 6 * lk); c.h.mat.uniforms.uSmile.value = sm(seg(t, 5.2, 6));
    E.P.uPost.value.z = Math.max(1 - sm(seg(t, 0, 1.0)), sm(seg(t, 7.2, 8))); txt(E, null); const f = K.keys([[0, 1.9], [2.6, 1.9], [3.8, 5.6], [6.2, 5.6], [7.2, 1.9]], t);
    return { p: [-1.05 + 0.1 * seg(t, 0, 8), 0.66, 5.75], t: [0.75, 0.62, 4.1], fov: 31, focus: f, dof: 11 }; } };
