// Kiri: a river-valley village. Shared by the morning, the night under the stars and the raid.
import { THREE, V3, fbm, ridged, smooth, clamp, lerp, mulberry } from './core.js';
import * as W from './world.js';
export const riverX = (z) => -46 + 11 * Math.sin(z * 0.018) + 5 * Math.sin(z * 0.05 + 1.3);
export function kiriHeight(x, z) {
  const d = Math.hypot(x, z * 0.8); let h = (fbm(x * 0.012, z * 0.012, 3.1, 4) - 0.5) * 9;
  h *= smooth(38, 120, d);                                              // flat village floor
  h += smooth(95, 330, d) * (22 + ridged(x * 0.004 + 3, z * 0.004, 1.7, 5) * 130 * smooth(140, 520, d));   // hills, then mountains
  const hill = Math.exp(-((x + 78) ** 2 + (z - 34) ** 2) / 900) * 9; h += hill;                               // the star-gazing hill
  const rd = Math.abs(x - riverX(z)); h -= smooth(9, 2, rd) * 2.2 * smooth(260, 150, d);                      // river bed
  return h;
}
export function buildKiri(st, o = {}) {
  const scene = st.scene, g = new THREE.Group(); scene.add(g); const r = mulberry(77); const burnt = o.burnt ?? 0;
  const street = (x, z) => Math.max(smooth(4.2, 2.2, Math.abs(x)) * smooth(75, 60, Math.abs(z)), smooth(15, 9, Math.hypot(x, z - 2)), smooth(3, 1.5, Math.abs(z - 2)) * smooth(-46, -36, x) * smooth(4, -2, x));
  const terrain = W.makeTerrain({ size: 640, seg: 260, height: kiriHeight, snowLine: 150, uvScale: 0.25,
    mask: (x, z, h) => [clamp(street(x, z) + smooth(4.5, 2.5, Math.abs(x - riverX(z))) * 0.9), 0, burnt * smooth(70, 30, Math.hypot(x, z)) * (0.4 + 0.6 * fbm(x * 0.1, z * 0.1, 5)), smooth(5.5, 3, Math.abs(x - riverX(z))) * 0.7] });
  g.add(terrain);
  const far = W.makeTerrain({ size: 6400, seg: 150, snowLine: 210, uvScale: 0.05, rockSlope: 0.3, height: (x, z) => { const d = Math.hypot(x, z); return d < 290 ? -30 : kiriHeight(x, z) * 0.9 + smooth(600, 2600, d) * 260 * ridged(x * 0.0011, z * 0.0011, 4.2, 5) - 6; } }); g.add(far);
  const water = W.makeWater(40, 640, { deep: o.night ? [0.01, 0.02, 0.04] : [0.05, 0.13, 0.15], sky: o.waterSky || [0.55, 0.66, 0.78] }); water.position.set(-46, -1.05, 0); g.add(water);
  // houses along the street and around the plaza
  const houses = []; const lots = [];
  for (let i = 0; i < 9; i++) for (const s of [-1, 1]) { const z = -62 + i * 14.5 + (r() - 0.5) * 3; if (Math.abs(z - 2) < 12) continue; lots.push([s * (9.5 + r() * 2.5), z, s > 0 ? -Math.PI / 2 : Math.PI / 2]); }
  for (let i = 0; i < 12; i++) { const a = r() * 6.283, d = 26 + r() * 30; const x = Math.cos(a) * d, z = Math.sin(a) * d * 1.25; if (Math.abs(x) < 17 || x < -34) continue; lots.push([x, z, Math.atan2(-x, -z) + (r() - 0.5)]); }
  lots.push([13, 6, -Math.PI / 2], [-14, -3, Math.PI / 2], [6, 20, Math.PI], [-7, 21, Math.PI]);
  lots.forEach(([x, z, rot], i) => { const hs = W.house(i + 3, { roof: r() < 0.7 ? 'thatch' : 'wood', lit: o.lit ?? 0, burnt: burnt * (0.5 + 0.5 * r()) }); hs.position.set(x, kiriHeight(x, z) - 0.1, z); hs.rotation.y = rot; g.add(hs); houses.push(hs); });
  // plaza: well, stalls, fences
  const well = W.well(); well.position.set(3.5, 0, 4); g.add(well);
  if (!o.noStalls) [[-5, -2, 0.3], [-1, 9, 3.3], [7, -3, -0.5], [-8, 6, 1.6], [8, 9, 4.0]].forEach(([x, z, rot], i) => { const s = W.stall(i + 1); s.position.set(x, 0, z); s.rotation.y = rot; g.add(s); });
  for (let i = 0; i < 14; i++) { const a = r() * 6.283, d = 30 + r() * 28; const f = W.fence(4 + r() * 5); const x = Math.cos(a) * d, z = Math.sin(a) * d; if (x < -36) continue; f.position.set(x, kiriHeight(x, z), z); f.rotation.y = r() * 3.14; g.add(f); }
  // vegetation
  const clear = (x, z) => Math.hypot(x, z * 0.8) > 16 && Math.abs(x) > 6 && Math.abs(x - riverX(z)) > 6 && !lots.some(l => Math.hypot(l[0] - x, l[1] - z) < 6.5);
  const kindL = o.dead ? 'dead' : 'leaf';
  g.add(W.forest(W.scatter(70, 11, [-70, 75, -85, 85], kiriHeight, (x, z) => clear(x, z) && Math.hypot(x, z) > 24), kindL, 5));
  g.add(W.forest(W.scatter(520, 12, [-300, 300, -300, 300], kiriHeight, (x, z, y) => Math.hypot(x, z * 0.8) > 85 && y < 110), o.dead ? 'dead' : 'leaf', 9, { shadow: false, far: true }));
  g.add(W.forest(W.scatter(420, 13, [-420, 420, -420, 420], kiriHeight, (x, z, y) => y > 28 && y < 170 && Math.hypot(x, z) > 120), 'pine', 4, { shadow: false, far: true }));
  if (!o.dead) g.add(W.forest(W.scatter(130, 14, [-70, 70, -80, 80], kiriHeight, (x, z) => clear(x, z)), 'bush', 3, { shadow: false }));
  // the old tree by the river
  const old = W.forest([{ x: -34, y: kiriHeight(-34, 16) - 0.2, z: 16, s: 1.9, rot: 1.2, tint: o.dead ? 0.5 : 1 }], o.dead ? 'dead' : 'leaf', 21, { variants: 1, height: 8, radius: 4.2, cards: 90 }); g.add(old);
  g.add(W.rocks(W.scatter(60, 15, [-60, -30, -80, 80], kiriHeight, (x, z) => Math.abs(x - riverX(z)) < 9 && Math.hypot(x + 34, z - 16) > 7), 5));
  g.add(W.rocks(W.scatter(40, 16, [-220, 220, -220, 220], kiriHeight, (x, z) => Math.hypot(x, z) > 60).map(s => ({ ...s, s: s.s * 2.5 })), 6));
  if (!o.noGrass) g.add(W.grassField(W.scatter(o.grass ?? 5000, 17, o.grassArea || [-40, 40, -50, 50], kiriHeight, (x, z) => street(x, z) < 0.3 && Math.abs(x - riverX(z)) > 3.5 && !lots.some(l => Math.hypot(l[0] - x, l[1] - z) < 4.2)), { color: o.dead ? [0.2, 0.18, 0.12] : [0.36, 0.46, 0.16] }));
  return { group: g, houses, lots, terrain };
}
