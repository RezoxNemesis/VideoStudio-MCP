// THE OTHER MODEL — the city at night seen from outside: a viaduct of card arches, the last local with its strip of
// lit windows, streets with toy traffic and sodium lamps, rows of buildings going back into haze, a painted sky.
import { THREE, mat, ball, limb, rbox, tube, mesh, group, paintTex, V3, lerp, clamp, hash } from './clay.js';
import { building, vehicle, tree, sky } from './city.js';

export function buildExterior(scene, E, o = {}) {
  const G = new THREE.Group(); scene.add(G); E.clear = 0x02040c; const ticks = [], hq = !!E.hq; scene.fog = new THREE.FogExp2('#0a1030', o.fog ?? 0.028);
  const card = (c, q) => mat(c, 'paint', { ridge: 0.6, ...q });
  // the ground: asphalt card; a road along the viaduct with its lane marks
  G.add(mesh(new THREE.PlaneGeometry(80, 80), card('#0e1322'), { p: [0, 0, 0], r: [-Math.PI / 2, 0, 0], cast: false }));
  const road = paintTex(64, 512, (g, w, h) => { g.fillStyle = '#1a1d28'; g.fillRect(0, 0, w, h); g.fillStyle = 'rgba(230,225,200,0.5)'; for (let i = 0; i < 16; i++) g.fillRect(w / 2 - 1.5, i * 32 + 6, 3, 16); g.fillStyle = 'rgba(230,225,200,0.25)'; g.fillRect(3, 0, 2, h); g.fillRect(w - 5, 0, 2, h); }, { repeat: [1, 10] });
  G.add(mesh(new THREE.PlaneGeometry(0.9, 44), mat('#ffffff', 'paint', { map: road, ridge: 0.2 }), { p: [1.75, 0.004, 0], r: [-Math.PI / 2, 0, 0], cast: false }));
  // the viaduct: a deck on piers, round arches cut from card, a rail each side, ballast and the two rails
  const stone = card('#4a4654'), stone2 = card('#3c3946'); G.add(mesh(new THREE.BoxGeometry(0.66, 0.1, 44), stone, { p: [0, 1.0, 0] }), mesh(new THREE.BoxGeometry(0.72, 0.03, 44), stone2, { p: [0, 0.94, 0] }));
  for (let z = -21; z <= 21; z += 1.4) { G.add(mesh(new THREE.BoxGeometry(0.5, 0.95, 0.3), stone, { p: [0, 0.475, z] }), mesh(new THREE.BoxGeometry(0.58, 0.06, 0.38), stone2, { p: [0, 0.03, z] }));
    const arch = new THREE.Shape(); arch.moveTo(-0.55, 0); arch.lineTo(-0.55, 0.38); arch.absarc(0, 0.38, 0.55, Math.PI, 0, true); arch.lineTo(0.55, 0); arch.lineTo(0.7, 0); arch.lineTo(0.7, 0.96); arch.lineTo(-0.7, 0.96); arch.lineTo(-0.7, 0); arch.closePath();
    for (const x of [0.27, -0.27]) G.add(mesh(new THREE.ExtrudeGeometry(arch, { depth: 0.03, bevelEnabled: false }), stone, { p: [x - 0.015, 0, z + 0.7], r: [0, Math.PI / 2, 0] })); }
  for (const x of [0.32, -0.32]) { G.add(mesh(new THREE.BoxGeometry(0.02, 0.02, 44), card('#2a2c36'), { p: [x, 1.16, 0] })); for (let z = -21.8; z < 22; z += 0.35) G.add(mesh(new THREE.BoxGeometry(0.014, 0.12, 0.014), card('#2a2c36'), { p: [x, 1.1, z], cast: false })); }
  for (const x of [0.09, -0.09]) G.add(mesh(new THREE.BoxGeometry(0.014, 0.014, 44), mat('#8a8f98', 'tin'), { p: [x, 1.057, 0], cast: false }));
  for (let z = -21.9; z < 22; z += 0.16) G.add(mesh(new THREE.BoxGeometry(0.3, 0.008, 0.05), card('#2a2420'), { p: [0, 1.052, z], cast: false }));
  // masts and the wire
  for (let z = -20; z <= 21; z += 2.8) G.add(mesh(new THREE.BoxGeometry(0.03, 1.0, 0.03), card('#23252e'), { p: [-0.38, 1.55, z] }), mesh(new THREE.BoxGeometry(0.44, 0.02, 0.02), card('#23252e'), { p: [-0.17, 1.98, z] }), mesh(ball(0.012), mat('#c9ced6', 'tin'), { p: [0, 1.95, z], cast: false }));
  G.add(mesh(new THREE.BoxGeometry(0.006, 0.006, 44), card('#15161c'), { p: [0, 1.93, 0], cast: false }));
  // the train: painted wood blocks, a strip of tiny bulbs behind paper windows, doors, a pantograph
  const train = new THREE.Group(); G.add(train); const winT = paintTex(256, 32, (g, w, h) => { g.fillStyle = '#1b2233'; g.fillRect(0, 0, w, h); for (let i = 0; i < 9; i++) { if (i === 2 || i === 6) { g.fillStyle = '#ffe2b0'; g.fillRect(6 + i * 27.5, 2, 24, 30); continue; } g.fillStyle = i === 4 ? '#ffd9a0' : '#ffe9c4'; g.fillRect(8 + i * 27.5, 6, 20, 18); g.fillStyle = 'rgba(40,30,20,0.55)'; g.fillRect(8 + i * 27.5, 11, 20, 1.5); g.fillRect(8 + i * 27.5, 17, 20, 1.5); } });
  for (let i = 0; i < 9; i++) { const c = new THREE.Group(); c.position.z = -i * 1.36; train.add(c);
    c.add(mesh(rbox(0.36, 0.16, 1.3, 0.03), card('#3f5f96'), { p: [0, 0.1, 0] }), mesh(rbox(0.36, 0.2, 1.3, 0.03), card('#d9cfb6'), { p: [0, 0.27, 0] }), mesh(rbox(0.33, 0.05, 1.26, 0.02), card('#8d8f96'), { p: [0, 0.385, 0] }), mesh(new THREE.BoxGeometry(0.3, 0.05, 1.1), card('#191b22'), { p: [0, 0.01, 0], cast: false }), mesh(new THREE.BoxGeometry(0.43, 0.012, 1.3), card('#d9b01e'), { p: [0, 0.178, 0], cast: false }));
    for (const sd of [1, -1]) c.add(mesh(new THREE.PlaneGeometry(1.22, 0.14), new THREE.MeshBasicMaterial({ map: winT, color: new THREE.Color(2.6, 2.3, 1.8) }), { p: [sd * 0.2125, 0.265, 0], r: [0, sd * Math.PI / 2, 0], cast: false, recv: false }));
    for (const z of [0.42, -0.42]) c.add(mesh(new THREE.CylinderGeometry(0.045, 0.045, 0.3, 10), card('#101116'), { p: [0, -0.012, z], r: [0, 0, Math.PI / 2], cast: false }));
    if (i % 3 === 1) c.add(mesh(tube([[0, 0.41, -0.2], [0, 0.52, 0], [0, 0.41, 0.2]], 0.006, 8, 5), mat('#8a8f98', 'tin'), { cast: false }), mesh(new THREE.BoxGeometry(0.2, 0.008, 0.02), mat('#8a8f98', 'tin'), { p: [0, 0.525, 0], cast: false }));
    if (i === 0) { c.add(mesh(ball(0.035), mat('#fff0c8', 'glow', { power: 9 }), { p: [0, 0.2, 0.685], cast: false }), mesh(new THREE.PlaneGeometry(0.26, 0.1), new THREE.MeshBasicMaterial({ color: new THREE.Color(1.6, 1.5, 1.2) }), { p: [0, 0.3, 0.682], cast: false })); const hl = new THREE.SpotLight('#ffe2b0', 30, 7, 0.4, 0.7, 1.4); hl.position.set(0, 0.2, 0.7); hl.target.position.set(0, -0.3, 4); c.add(hl, hl.target); }
    if (i === 8) for (const s of [1, -1]) c.add(mesh(ball(0.02), mat('#ff2a1a', 'glow', { power: 6 }), { p: [s * 0.12, 0.14, -0.685], cast: false })); }
  train.position.y = 1.064;
  // the city: a near row across the road (with shops and balconies), rows behind going back; a few low ones this side of the track
  const B = (w, d, h, x, z, q) => { const b = building(w, d, h, q); b.position.set(x, 0, z); G.add(b); return b; };
  let z = -21; for (let i = 0; z < 21; i++) { const w = 1.2 + hash(i * 1.3) * 1.4, h = 1.4 + hash(i * 3.1) * 2.2; B(w, 0.9, h, -1.75 - hash(i) * 0.3, z + w / 2, { seed: i + 1, near: true, glow: 2.4, face: 1, hoarding: hash(i * 5.5) < 0.22 }); z += w + 0.12 + (hash(i * 7.7) < 0.2 ? 0.5 : 0); }
  z = -22; for (let i = 0; z < 22; i++) { const w = 1.6 + hash(i * 2.3) * 2.2, h = 2.4 + hash(i * 4.1) ** 1.4 * 5; B(w, 1.3, h, -3.6 - hash(i * 3) * 0.8, z + w / 2, { seed: i + 40, glow: 2.1, face: 1 }); z += w + 0.2; }
  z = -24; for (let i = 0; z < 24; i++) { const w = 2.2 + hash(i * 3.3) * 3, h = 4 + hash(i * 5.1) ** 1.3 * 8; B(w, 1.6, h, -6.6 - hash(i * 7) * 2.4, z + w / 2, { seed: i + 80, glow: 1.8, face: 1, roof: false }); z += w + 0.3; }
  z = -24; for (let i = 0; z < 24; i++) { const w = 3 + hash(i * 4.3) * 3, h = 6 + hash(i * 6.1) * 9; B(w, 2, h, -11.5 - hash(i * 9) * 2, z + w / 2, { seed: i + 120, glow: 1.5, face: 1, roof: false }); z += w + 0.6; if (hash(i * 13.1) < 0.4) G.add(mesh(ball(0.05), mat('#ff2a1a', 'glow', { power: 5 }), { p: [-11.5, h + 0.2, z - w / 2], cast: false })); }
  for (let i = 0; i < 9; i++) B(0.9 + hash(i * 2.9), 0.7, 0.5 + hash(i * 4.7) * 0.7, 2.9 + hash(i * 6.1) * 1.6, -20 + i * 4.6 + hash(i) * 2, { seed: i + 160, glow: 1.3, face: -1 });
  // the road's life: lamps, trees, traffic going both ways
  for (let zz = -20; zz < 21; zz += 1.9) { const x = 1.2 + hash(zz) * 0.06; G.add(mesh(new THREE.BoxGeometry(0.02, 0.66, 0.02), card('#1a1c24'), { p: [x, 0.33, zz] }), mesh(new THREE.BoxGeometry(0.14, 0.014, 0.02), card('#1a1c24'), { p: [x + 0.06, 0.66, zz], cast: false }), mesh(ball(0.032), mat('#ffb060', 'glow', { power: 7 }), { p: [x + 0.13, 0.645, zz], cast: false })); if (Math.round(zz * 10) % 3 === 0) { const pl = new THREE.PointLight('#ff9a40', 1.5, 2.8, 1.6); pl.position.set(x + 0.13, 0.6, zz); G.add(pl); } }
  for (let i = 0; i < 16; i++) { const t = tree(i, 0.42 + 0.3 * hash(i * 3)); t.position.set(i % 2 ? 2.45 + hash(i) * 0.3 : -1.12 - hash(i) * 0.1, 0, -20 + i * 2.6 + hash(i * 7)); G.add(t); }
  const cars = []; for (let i = 0; i < 9; i++) { const v = vehicle(['taxi', 'auto', 'taxi', 'bus', 'car', 'auto', 'taxi', 'car', 'bus'][i], i), dir = i % 2 ? 1 : -1; v.rotation.y = dir > 0 ? 0 : Math.PI; v.userData = { z0: -20 + i * 4.7, v: dir * (0.5 + 0.35 * hash(i * 3.3)), x: 1.75 + dir * 0.2 }; G.add(v); cars.push(v); }
  ticks.push((t) => { for (const v of cars) { const u = v.userData; v.position.set(u.x, 0, ((u.z0 + t * u.v + 22 + 4400) % 44) - 22); } });
  // the sky behind it all
  const sk = sky({ x: -19, face: 1, w: 110, h: 34, y0: -2, moonZ: o.moonZ ?? 7.5, moonY: o.moonY ?? 13.5, moonR: 1.5, clouds: 10, star: hq ? 3.2 : 1.7 }); G.add(sk.group); ticks.push(sk.tick);
  scene.add(new THREE.HemisphereLight('#5a6fb4', '#1a1420', 0.9));
  const SM = hq ? 4096 : 2048, key = new THREE.DirectionalLight('#9fb6ff', 1.7); key.position.set(-3, 6, 4); key.target.position.set(0, 1, 0); key.castShadow = true; key.shadow.mapSize.set(SM, SM); Object.assign(key.shadow.camera, { left: -9, right: 9, top: 9, bottom: -9, near: 0.5, far: 24 }); key.shadow.bias = -0.0008; key.shadow.normalBias = 0.02; key.shadow.radius = 4; scene.add(key, key.target);
  E.ticks.push(...ticks);
  return { G, train, key };
}
