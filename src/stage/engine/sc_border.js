// The border: a stone bridge over a mist-filled gorge, a small town on the west bank, the road to Valdhar on the east.
import { THREE, V3, fbm, ridged, smooth, clamp, lerp, mulberry } from './core.js';
import * as W from './world.js';
import * as BGU from 'three/addons/utils/BufferGeometryUtils.js';
export const DECK = 0.9;         // bridge deck height
export const BX0 = -34, BX1 = 34; // bridge ends (x)
export function borderHeight(x, z) {
  const rim = 27 + (fbm(z * 0.03, 1.7, 0, 4) - 0.5) * 9 + (fbm(z * 0.15, 9.1, 0, 3) - 0.5) * 2.5; const ax = Math.abs(x + (fbm(z * 0.01, 4.4, 0, 3) - 0.5) * 14);
  let h = (fbm(x * 0.02, z * 0.02, 5.5, 4) - 0.5) * 3.5 * smooth(40, 90, ax);
  const g = smooth(rim + 3, rim - 5, ax); h -= g * (62 + (fbm(x * 0.05, z * 0.05, 2.2, 4) - 0.5) * 16) * (0.55 + 0.45 * smooth(rim - 4, rim - 14, ax));
  const d = Math.hypot(x, z); h += smooth(170, 700, d) * (10 + ridged(x * 0.003 + 7, z * 0.003, 3.3, 5) * 190);
  if (Math.abs(z) < 7) h = lerp(h, Math.max(h, 0.2), smooth(7, 3.5, Math.abs(z)) * smooth(rim - 1, rim + 6, ax));   // level road at the bridge heads
  return h;
}
const C = (r, g, b) => new THREE.Color().setRGB(r, g, b, THREE.SRGBColorSpace);
export function buildBorder(st, o = {}) {
  const g = new THREE.Group(); st.scene.add(g); const r = mulberry(31); const burnt = o.burnt ?? 0.0;
  const road = (x, z) => smooth(4.5, 2.5, Math.abs(z)) * smooth(26, 36, Math.abs(x)) * smooth(330, 250, Math.abs(x));
  g.add(W.makeTerrain({ size: 700, seg: 280, height: borderHeight, snowLine: 120, uvScale: 0.22, rockSlope: 0.36, grass: 'grass', tint: o.tint || [0.9, 0.86, 0.8], castShadow: true,
    mask: (x, z, h) => [clamp(road(x, z) + (x < -40 && x > -120 && Math.abs(z) < 45 ? 0.5 : 0)), 0, burnt * smooth(-130, -60, x) * smooth(-30, -50, x) * fbm(x * 0.1, z * 0.1, 3), 0] }));
  g.add(W.makeTerrain({ size: 6400, seg: 140, snowLine: 150, uvScale: 0.05, rockSlope: 0.3, tint: [0.85, 0.85, 0.9], height: (x, z) => { const d = Math.hypot(x, z); return d < 320 ? -80 : borderHeight(x, z) * 0.9 + smooth(700, 2800, d) * 300 * ridged(x * 0.001, z * 0.001, 8.2, 5) - 8; } }));
  // bridge
  const stone = W.propMat('stone', { color: [0.78, 0.76, 0.74] }); const parts = []; const bx = (w, h, d, x, y, z, uvs = 0.25) => { const b = new THREE.BoxGeometry(w, h, d); const uv = b.attributes.uv, p = b.attributes.position, n = b.attributes.normal; for (let i = 0; i < uv.count; i++) { const nx = Math.abs(n.getX(i)), ny = Math.abs(n.getY(i)); const a = nx > 0.5 ? p.getZ(i) : p.getX(i), bb = ny > 0.5 ? p.getZ(i) : p.getY(i); uv.setXY(i, a * uvs, bb * uvs); } b.translate(x, y, z); return b; };
  const L = BX1 - BX0; parts.push(bx(L + 8, 1.2, 5.6, 0, DECK - 0.6, 0));
  for (const s of [-1, 1]) { for (let i = 0; i < 22; i++) { if (o.broken && (i === 13 && s === 1 || i === 6 && s === -1)) continue; const x0 = BX0 - 2 + i * (L + 4) / 22; parts.push(bx((L + 4) / 22 - 0.12, 0.95, 0.42, x0 + (L + 4) / 44, DECK + 0.47, s * 2.6)); } for (let i = 0; i <= 5; i++) { if (o.skipPillar === i) continue; parts.push(bx(0.7, 1.5, 0.7, BX0 + i * L / 5, DECK + 0.75, s * 2.6)); } }
  for (let i = 0; i < 4; i++) { const x = BX0 + 10 + i * (L - 20) / 3; parts.push(bx(4.2, 70, 6.2, x, DECK - 36.2, 0, 0.12)); for (const s of [-1, 1]) parts.push(bx(5.4, 3.0, 7.0, x, DECK - 2.4, 0, 0.15)); }
  const bridge = new THREE.Mesh(BGU.mergeGeometries(parts), stone); bridge.castShadow = true; bridge.receiveShadow = true; g.add(bridge);
  // town on the west bank
  const houses = []; const lots = [[-58, -14, 0.4], [-60, 13, 2.7], [-74, -20, 0.1], [-78, 16, 3.3], [-92, -13, 0.2], [-95, 17, 3.0], [-110, -18, 0.5], [-66, 30, 2.2], [-84, -34, 0.9], [-104, 32, 2.5]];
  lots.forEach(([x, z, rot], i) => { const hs = W.house(i + 40, { roof: i % 3 ? 'thatch' : 'wood', lit: o.lit ?? 0, burnt: burnt * (i % 2 ? 0.9 : 0.4) }); hs.position.set(x, borderHeight(x, z) - 0.1, z); hs.rotation.y = rot; g.add(hs); houses.push(hs); });
  const pal = []; for (let i = 0; i < 60; i++) { const a = -1.1 + i * 2.2 / 59; const x = -44 + Math.cos(a + Math.PI) * -6 - Math.abs(Math.sin(a)) * 30, z = Math.sin(a) * 52; if (Math.abs(z) < 5) continue; const c = new THREE.CylinderGeometry(0.16, 0.2, 3.4 + r() * 0.5, 5); c.translate(x, borderHeight(x, z) + 1.6, z); pal.push(c); }
  const pm = new THREE.Mesh(BGU.mergeGeometries(pal), W.propMat('wood', { color: [0.45, 0.4, 0.35] })); pm.castShadow = true; g.add(pm);
  const tw = W.house(77, { w: 3, d: 3, h: 6.5, rh: 1.6, roof: 'wood' }); tw.position.set(-47, borderHeight(-47, 9), 9); g.add(tw);
  // road-side banners of Valdhar on the east bank, trees, rocks
  const banners = []; if (o.banners !== false) for (let i = 0; i < 6; i++) { const b = W.banner({ color: [0.36, 0.03, 0.03], emblem: [0.08, 0.08, 0.09], seed: i, height: 5 }); const x = 42 + i * 9, z = (i % 2 ? 1 : -1) * 6.5; b.position.set(x, borderHeight(x, z), z); b.rotation.y = Math.PI / 2; g.add(b); banners.push(b); }
  const land = (x, z) => Math.abs(x) > 38 && Math.abs(z) > 9;
  g.add(W.forest(W.scatter(46, 51, [-170, 170, -150, 150], borderHeight, (x, z, y) => land(x, z) && y > -3 && !(x < -44 && x > -118 && Math.abs(z) < 48)), o.dead ? 'dead' : 'pine', 6, { far: !o.dead, shadow: true }));
  g.add(W.forest(W.scatter(520, 52, [-330, 330, -330, 330], borderHeight, (x, z, y) => Math.abs(x) > 60 && y > -3 && y < 90 && Math.hypot(x, z) > 150), 'pine', 4, { shadow: false, far: true }));
  g.add(W.rocks(W.scatter(110, 53, [-80, 80, -160, 160], borderHeight, (x, z, y) => Math.abs(x) > 26 && Math.abs(x) < 60 && Math.abs(z) > 7 && y > -6).map(s => ({ ...s, s: s.s * 1.8 })), 7));
  g.add(W.rocks(W.scatter(40, 54, [-200, 200, -200, 200], borderHeight, (x, z, y) => land(x, z) && y > -3).map(s => ({ ...s, s: s.s * 3.2 })), 8));
  return { group: g, houses, bridge, banners, lots };
}
