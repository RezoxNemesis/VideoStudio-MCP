// PUPPETS — clay people on wire armatures. Big heads, small bodies (about four heads tall), mitten hands with a thumb,
// eyes that are beads behind clay lids, a mouth that is swapped like a replacement mouth. A pose is plain numbers; the
// arms and legs find their own elbows and knees (two-bone IK), so a hand is put where it should be and never guessed.
// Metres. The puppet faces +z, its left is +x, y is up. Hips are the root.
import { THREE, mat, ball, limb, rbox, tube, mesh, group, paintTex, V3, lerp, clamp, hash } from './clay.js';

const Q = () => new THREE.Quaternion(), X = V3(1, 0, 0), Y = V3(0, 1, 0), Z = V3(0, 0, 1);
const D = Math.PI / 180;
/** world quaternion whose local −y points along `dir` and whose local +z leans towards `fwd` */
const aimQ = (dir, fwd) => { const y = dir.clone().normalize().multiplyScalar(-1); let z = fwd.clone().addScaledVector(y, -fwd.dot(y)); if (z.lengthSq() < 1e-8) z = V3(0, 0, 1).addScaledVector(y, -y.z); z.normalize(); const x = V3().crossVectors(y, z); return Q().setFromRotationMatrix(new THREE.Matrix4().makeBasis(x, y, z)); };
const setWorldQ = (obj, qW) => { const pq = Q(); obj.parent.getWorldQuaternion(pq); obj.quaternion.copy(pq.invert().multiply(qW)); };

export const LOOKS = {
  // ROHAN VERMA, 29 — customer support, last local every night. Lean, a long face, heavy lids, a side parting that has given up.
  rohan: { h: 1, skin: '#b9794f', hair: '#1b1716', shirt: '#a9c3e6', trousers: '#2e3340', shoes: '#191a1f', tie: '#8a2334', glasses: true, lanyard: true, headW: 0.158, headH: 0.178, headD: 0.158, chest: 0.155, belly: 0.14, hairStyle: 'part', brow: '#14100e', lids: 0.7 },
  // MUKESH "BHAU" PATIL, 58 — night conductor: round, a grey moustache like a broom, a navy cap, a bag strap across the belly
  mukesh: { h: 0.97, skin: '#a9683f', hair: '#8f8f95', shirt: '#e4e0d4', trousers: '#23283a', shoes: '#141418', tie: null, glasses: false, lanyard: false, headW: 0.172, headH: 0.166, headD: 0.165, chest: 0.185, belly: 0.2, hairStyle: 'cap', brow: '#77777c', lids: 0.86, moustache: '#9a9aa0', strap: '#6a4a2c', jaw: 0.1, narrow: 0.12 },
  // MEHER KHAN, 27 — night-shift nurse: a blue cardigan over teal scrubs, a long plait, asleep against the window
  meher: { h: 0.93, skin: '#c48a62', hair: '#17110f', shirt: '#3f6fb4', trousers: '#2f8f8a', shoes: '#e8e6e0', tie: null, glasses: false, lanyard: false, headW: 0.152, headH: 0.166, headD: 0.152, chest: 0.135, belly: 0.125, hairStyle: 'plait', brow: '#17110f', lids: 0.0, inner: '#2f8f8a', jaw: 0.12, narrow: 0.2 },
};

export class Puppet {
  constructor(look, o = {}) {
    const L = this.L = { ...LOOKS[look], ...o }, s = L.h; this.name = look;
    const skin = mat(L.skin, 'clay'), hairM = mat(L.hair, 'clay', { rough: 0.5, bump: 0.9, ns: 26, mottle: 0.1 }), shirt = mat(L.shirt, 'felt'), trs = mat(L.trousers, 'felt', { fuzz: 0.35 }), shoe = mat(L.shoes, 'clay', { rough: 0.35 });
    this.M = { skin, hairM, shirt, trs, shoe };
    // ---- lengths
    const B = this.B = { hip: 0.075 * s, thigh: 0.3 * s, shin: 0.29 * s, foot: 0.05 * s, torso: 0.4 * s, shW: (L.chest - 0.004) * s, shY: 0.345 * s, upper: 0.205 * s, fore: 0.195 * s, neck: 0.4 * s, headY: 0.2 * s };
    const root = this.root = new THREE.Group(); root.name = look;
    // pelvis and legs
    root.add(mesh(ball(L.belly * 0.95 * s, 0.105 * s, 0.115 * s), trs, { p: [0, 0.0, 0] }));
    this.legs = {}; for (const sd of [1, -1]) { const k = sd > 0 ? 'L' : 'R';
      const th = group([mesh(limb(0.062 * s, B.thigh, 0.052 * s), trs)], { p: [sd * B.hip, -0.02 * s, 0] }), sh = group([mesh(limb(0.05 * s, B.shin, 0.044 * s), trs), mesh(ball(0.056 * s), trs)], { p: [0, -B.thigh, 0] });
      const ft = group([mesh(rbox(0.085 * s, 0.062 * s, 0.17 * s, 0.028 * s), shoe, { p: [0, -0.02 * s, 0.045 * s] })], { p: [0, -B.shin, 0] }); sh.add(ft); th.add(sh); root.add(th); this.legs[k] = { th, sh, ft, sd }; }
    // spine: torso, belly, clothes on it
    const sp = this.spine = new THREE.Group(); sp.position.set(0, 0.03 * s, 0); root.add(sp);
    sp.add(mesh(ball(L.chest * s, 0.21 * s, 0.118 * s), shirt, { p: [0, 0.225 * s, 0.004] }), mesh(ball(L.belly * s, 0.16 * s, (0.112 + (L.belly > 0.17 ? 0.045 : 0)) * s), shirt, { p: [0, 0.1 * s, 0.012 + (L.belly > 0.17 ? 0.028 : 0)] }));
    sp.add(mesh(new THREE.TorusGeometry(L.belly * 0.97 * s, 0.014 * s, 8, 36), mat('#17181d', 'clay', { rough: 0.4 }), { p: [0, 0.035 * s, 0.005 + (L.belly > 0.17 ? 0.02 : 0)], r: [Math.PI / 2, 0, 0] }).translateZ(0));
    sp.children[sp.children.length - 1].scale.set(1, (0.115 + (L.belly > 0.17 ? 0.045 : 0)) / L.belly, 1);
    sp.add(mesh(rbox(0.03 * s, 0.024 * s, 0.012 * s, 0.004), mat('#b9bdc6', 'tin'), { p: [0, 0.035 * s, (0.118 + (L.belly > 0.17 ? 0.07 : 0)) * s] }));
    sp.add(mesh(ball((L.chest + 0.028) * s, 0.062 * s, 0.092 * s), shirt, { p: [0, 0.335 * s, -0.004] }));
    const chestZ = (y) => 0.004 + 0.118 * s * Math.sqrt(Math.max(0.02, 1 - ((y - 0.225 * s) / (0.21 * s)) ** 2)) * 1.0;
    const bz = L.belly > 0.17 ? 0.045 : 0, frontZ = (y) => { const e = (c, ry, rz, zc) => { const u = (y - c) / ry; return Math.abs(u) < 1 ? zc + rz * Math.sqrt(1 - u * u) : -1; }; return Math.max(e(0.225 * s, 0.21 * s, 0.118 * s, 0.004), e(0.1 * s, 0.16 * s, (0.112 + bz) * s, 0.012 + (bz ? 0.028 : 0)), 0.05 * s); };
    this.chestZ = chestZ; this.frontZ = frontZ;
    // collar, placket, pocket
    const col = mat(new THREE.Color(L.shirt).multiplyScalar(1.08), 'felt');
    for (const sd of [1, -1]) sp.add(mesh(rbox(0.075 * s, 0.04 * s, 0.012 * s, 0.005), col, { p: [sd * 0.043 * s, 0.398 * s, 0.072 * s], r: [-0.5, sd * 0.5, sd * -0.5] }));
    sp.add(mesh(rbox(0.058 * s, 0.064 * s, 0.006, 0.003), col, { p: [0.075 * s, 0.25 * s, chestZ(0.25 * s) + 0.002], r: [-0.12, 0.35, 0] }));
    if (L.inner) sp.add(mesh(ball(0.05 * s, 0.05 * s, 0.03 * s), mat(L.inner, 'felt'), { p: [0, 0.385 * s, 0.075 * s] }));
    if (L.tie) { const tm = mat(L.tie, 'felt', { map: paintTex(64, 256, (g, w, h) => { g.fillStyle = L.tie; g.fillRect(0, 0, w, h); g.strokeStyle = 'rgba(255,220,215,0.5)'; g.lineWidth = 7; for (let i = -4; i < 14; i++) { g.beginPath(); g.moveTo(-4, i * 26); g.lineTo(w + 4, i * 26 + 30); g.stroke(); } }) });
      const tg = this.tie = group([], { p: [0.002, 0.35 * s, frontZ(0.35 * s) + 0.008], r: [0.25, 0, 0.06] }); tg.add(mesh(rbox(0.034 * s, 0.03 * s, 0.018 * s, 0.007), tm));
      const bw = (v) => (0.014 + 0.013 * Math.min(1, v / 0.84) - (v > 0.84 ? 0.027 * (v - 0.84) / 0.16 : 0)) * s, bg = new THREE.PlaneGeometry(1, 1, 2, 14), bp = bg.attributes.position, len = 0.215 * s, y0 = 0.338 * s;
      for (let i = 0; i < bp.count; i++) { const u = bp.getX(i) * 2, v = 0.5 - bp.getY(i), y = y0 - v * len; bp.setXYZ(i, u * bw(v) + 0.012 * s * v, y, frontZ(y) + 0.007 + 0.004 * (1 - u * u)); } bg.computeVertexNormals();
      const tm2 = mat(L.tie, 'felt', { map: tm.map, side: THREE.DoubleSide }); sp.add(mesh(bg, tm2)); this.tieBlade = bg; sp.add(tg); }
    if (L.lanyard) { const lm = mat('#c22a33', 'felt', { fuzz: 0.3 }), lg = this.lany = new THREE.Group(); sp.add(lg);
      for (const sd of [1, -1]) lg.add(mesh(tube([[sd * 0.06 * s, 0.4 * s, 0.02 * s], [sd * 0.062 * s, 0.37 * s, 0.085 * s], [sd * 0.045 * s, 0.3 * s, chestZ(0.3 * s) + 0.012], [sd * 0.012 * s, 0.2 * s, chestZ(0.2 * s) + 0.016], [0, 0.17 * s, chestZ(0.17 * s) + 0.016]], 0.0042 * s, 20, 6), lm));
      const card = this.card = group([], { p: [0, 0.17 * s, chestZ(0.17 * s) + 0.017] });
      card.add(mesh(rbox(0.012, 0.016, 0.006, 0.002), mat('#9a9da6', 'tin'), { p: [0, -0.006, 0] }), mesh(rbox(0.058 * s, 0.084 * s, 0.004, 0.003), mat('#f3f1ea', 'paper', { map: paintTex(128, 184, (g, w, h) => { g.fillStyle = '#f3f1ea'; g.fillRect(0, 0, w, h); g.fillStyle = '#2f6fb0'; g.fillRect(0, 0, w, 34); g.fillStyle = '#fff'; g.font = '700 17px Poppins'; g.fillText('NEXORA', 22, 23); g.fillStyle = '#c9a184'; g.fillRect(40, 48, 48, 56); g.fillStyle = '#2b2622'; g.beginPath(); g.arc(64, 62, 13, 0, 7); g.fill(); g.fillRect(48, 78, 32, 26); g.fillStyle = '#5a5f6b'; g.fillRect(24, 118, 80, 8); g.fillRect(34, 134, 60, 6); g.fillStyle = '#20242c'; for (let i = 0; i < 22; i++) g.fillRect(22 + i * 4, 152, hash(i) > 0.5 ? 2 : 1, 18); }) }), { p: [0, -0.058 * s, 0] })); lg.add(card); }
    if (L.strap) { const sm = mat(L.strap, 'felt', { fuzz: 0.2 }), fz = (y) => frontZ(y * s) + 0.012;
      sp.add(mesh(tube([[0.13 * s, 0.385 * s, 0.03], [0.1 * s, 0.33 * s, fz(0.33) * 0.9], [0.03 * s, 0.22 * s, fz(0.22)], [-0.07 * s, 0.1 * s, fz(0.1) * 0.93], [-0.17 * s, 0.03 * s, 0.07 * s], [-0.2 * s, 0.03 * s, -0.03 * s], [-0.11 * s, 0.14 * s, -0.125 * s], [0.03 * s, 0.3 * s, -0.125 * s], [0.12 * s, 0.385 * s, -0.04]], 0.011 * s, 48, 6, true), sm));
      sp.add(mesh(rbox(0.05 * s, 0.11 * s, 0.13 * s, 0.015), sm, { p: [-0.235 * s, -0.03 * s, 0.01 * s], r: [0, 0, -0.08] })); }
    // arms
    this.arms = {}; for (const sd of [1, -1]) { const k = sd > 0 ? 'L' : 'R';
      const up = group([mesh(limb(0.047 * s, B.upper, 0.042 * s), shirt), mesh(ball(0.052 * s), shirt), mesh(new THREE.TorusGeometry(0.044 * s, 0.009 * s, 6, 20), col, { p: [0, -B.upper * 0.72, 0], r: [Math.PI / 2, 0, 0] })], { p: [sd * B.shW, B.shY, 0] });
      const fo = group([mesh(limb(0.037 * s, B.fore, 0.03 * s), skin), mesh(ball(0.04 * s), skin)], { p: [0, -B.upper, 0] }); const hd = this._hand(sd, s, skin); hd.g.position.set(0, -B.fore, 0); fo.add(hd.g); up.add(fo); sp.add(up); this.arms[k] = { up, fo, hand: hd, sd }; }
    // neck and head
    sp.add(mesh(limb(0.043 * s, 0.1 * s), skin, { p: [0, 0.475 * s, 0.0] }));
    const head = this.head = new THREE.Group(); head.position.set(0, B.neck + 0.058 * s, 0); sp.add(head); this._face(L, s, skin, hairM);
    this.pose = {}; this.root.traverse((m) => { if (m.isMesh) m.frustumCulled = false; });
  }
  _hand(sd, s, skin) {
    // right hand (sd −1): palm faces +x, thumb side +z; left mirrored
    const p = -sd, g = new THREE.Group(), palm = mesh(ball(0.017 * s, 0.04 * s, 0.034 * s), skin, { p: [0, -0.036 * s, 0] }); g.add(palm);
    const fg = new THREE.Group(); fg.position.set(0, -0.066 * s, 0); g.add(fg); const tips = [];
    const zs = [0.024, 0.008, -0.008, -0.024], ls = [0.034, 0.038, 0.036, 0.029];
    zs.forEach((z, i) => { const a = group([mesh(limb(0.0098 * s, ls[i] * 0.55 * s, 0.0092 * s, 10), skin)], { p: [0, 0, z * s] }), b = group([mesh(limb(0.0092 * s, ls[i] * 0.5 * s, 0.0084 * s, 10), skin)], { p: [0, -ls[i] * 0.55 * s, 0] }); a.add(b); fg.add(a); tips.push([a, b]); });
    const th = group([mesh(limb(0.0115 * s, 0.03 * s, 0.0102 * s, 10), skin)], { p: [p * 0.006 * s, -0.024 * s, 0.03 * s] }), th2 = group([mesh(limb(0.0102 * s, 0.024 * s, 0.0094 * s, 10), skin)], { p: [0, -0.03 * s, 0] }); th.add(th2); g.add(th);
    return { g, fg, tips, th, th2, p };
  }
  /** fingers: curl 0 (flat) … 1 (fist); thumb 0 (out) … 1 (laid across to the fingers) */
  setHand(k, curl = 0.25, thumb = 0.3, spread = 0) {
    const h = this.arms[k].hand, p = h.p; h.tips.forEach(([a, b], i) => { a.rotation.set(spread * (i - 1.5) * 0.14, 0, p * (0.12 + curl * 1.25 + i * 0.05 * curl)); b.rotation.set(0, 0, p * (0.1 + curl * 1.3)); });
    h.th.rotation.set(-0.95 + thumb * 0.45, 0, p * (-0.25 + thumb * 1.05)); h.th2.rotation.set(0.0, 0, p * (0.1 + thumb * 0.55));
  }
  _face(L, s, skin, hairM) {
    const hd = this.head, W = L.headW * s, H = L.headH * s, Dp = L.headD * s, cy = this.B.headY - 0.03 * s; this.HS = { W, H, Dp, cy };
    // the head is one lump of clay, pressed into shape: a round skull, cheeks, a longer jaw that narrows to the chin
    const hg = new THREE.SphereGeometry(1, 60, 44), hp = hg.attributes.position, jaw = L.jaw ?? 0.2, nar = L.narrow ?? 0.26;
    for (let i = 0; i < hp.count; i++) { const x = hp.getX(i), y = hp.getY(i), z = hp.getZ(i), t = clamp(-y), fr = clamp(z);
      const sx = W * (1 - nar * t * t + 0.035 * Math.sin(Math.min(1, t * 1.6) * Math.PI)), sy = H * (1 + jaw * t), sz = Dp * (1 - 0.1 * t * t + 0.1 * fr * Math.sin(clamp((t - 0.35) / 0.6) * Math.PI) * t);
      hp.setXYZ(i, x * sx, y * sy + cy, z * sz); } hg.computeVertexNormals();
    const MU = this.MU = { uMouth: { value: new THREE.Vector4(0, 0.03, 0, 0) }, uMouthC: { value: 0 }, uMouthS: { value: 0 }, uJaw: { value: 0 } };
    const headM = mat(L.skin, 'clay', { key: 'head', patch: (sh) => { Object.assign(sh.uniforms, MU);
      sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\nuniform vec4 uMouth; uniform float uJaw;').replace('vOP = position;', 'vOP = position; transformed.y -= uJaw * smoothstep(uMouth.x - 0.034, uMouth.x - 0.085, position.y) * smoothstep(-0.03, 0.02, position.z);');
      sh.fragmentShader = sh.fragmentShader.replace('#include <common>', '#include <common>\nuniform vec4 uMouth; uniform float uMouthC, uMouthS;').replace('#include <color_fragment>', `{ float mx = vOP.x / uMouth.y; if (abs(mx) < 1.0 && vOP.z > 0.0 && uMouth.w > 0.0) { float shp = pow(1.0 - mx * mx, 0.75), yc = uMouth.x - uMouthC * (1.0 - mx * mx) + uMouthS * vOP.x; if (vOP.y < yc + uMouth.z * shp && vOP.y > yc - uMouth.w * shp) discard; } }
        #include <color_fragment>`); } });
    hd.add(mesh(hg, headM)); const probe = new THREE.Mesh(hg), rc = new THREE.Raycaster(); probe.updateMatrixWorld(true);
    const surf = (x, y) => { rc.set(V3(x, y, 1), V3(0, 0, -1)); const h = rc.intersectObject(probe, false)[0]; return h ? h.point.z : 0; }; this.surf = surf;
    for (const sd of [1, -1]) hd.add(mesh(ball(0.02 * s, 0.04 * s, 0.027 * s), skin, { p: [sd * (W - 0.003), cy - 0.018 * s, -0.012], r: [0, sd * -0.35, sd * -0.1] }));
    hd.add(mesh(ball(0.019 * s, 0.024 * s, 0.027 * s), skin, { p: [0, cy - 0.036 * s, surf(0, cy - 0.036 * s) + 0.004], r: [0.35, 0, 0] }));
    // eyes: white beads, dark pupils that turn, clay lids over them
    const lashM = mat(new THREE.Color(L.skin).multiplyScalar(0.3), 'clay', { ridge: 0 }), eyeW = mat('#f3efe6', 'plastic'), pup = mat('#17110e', 'plastic', { rough: 0.12 }), ex = 0.06 * s, ey = cy + 0.022 * s, er = 0.036 * s; this.eyes = {};
    for (const sd of [1, -1]) { const k = sd > 0 ? 'L' : 'R', ez = surf(ex, ey) - er * 0.52, eg = group([], { p: [sd * ex, ey, ez] });
      const bl = mesh(ball(er), eyeW), pg = new THREE.Group(), pm = mesh(ball(er * 0.5, er * 0.5, er * 0.16), pup, { p: [0, 0, er * 0.87] }), gl = mesh(ball(er * 0.1), mat('#ffffff', 'glow', { power: 1.4 }), { p: [er * 0.2, er * 0.24, er * 1.0], cast: false }); pg.add(pm, gl);
      const lidG = new THREE.SphereGeometry(er * 1.07, 24, 12, 0, Math.PI * 2, 0, Math.PI / 2), up = mesh(lidG, skin), lo = mesh(lidG, skin); lo.rotation.x = Math.PI + 1.25; const lash = mesh(new THREE.TorusGeometry(er * 1.075, er * 0.055, 6, 28, Math.PI), lashM, { r: [Math.PI / 2, 0, 0], cast: false }); up.add(lash); eg.add(bl, pg, up, lo); hd.add(eg); this.eyes[k] = { eg, pg, up, lo, gl }; }
    // under-eye shadows: a tired man
    if (L.lids <= 0.75 && L.lids > 0.1) for (const sd of [1, -1]) hd.add(mesh(ball(0.03 * s, 0.012 * s, 0.006 * s), mat(new THREE.Color(L.skin).multiplyScalar(0.72), 'clay'), { p: [sd * ex, ey - 0.043 * s, surf(ex, ey - 0.043 * s) - 0.002], r: [0.2, sd * 0.38, 0], cast: false }));
    // brows
    const bm = mat(L.brow, 'clay', { rough: 0.7 }); this.brows = {};
    for (const sd of [1, -1]) { const b = mesh(ball(0.031 * s, 0.0085 * s, 0.009 * s), bm), bg = group([b], { p: [sd * ex, ey + 0.056 * s, surf(ex, ey + 0.056 * s) + 0.004] }); bg.rotation.y = sd * 0.38; hd.add(bg); this.brows[sd > 0 ? 'L' : 'R'] = { g: bg, y0: ey + 0.056 * s, sd }; }
    // mouth (replaced whenever its shape changes)
    this.mouthG = new THREE.Group(); hd.add(this.mouthG); this.mouthM = mat(new THREE.Color(L.skin).lerp(new THREE.Color('#7a2a26'), 0.5).multiplyScalar(0.8), 'clay', { rough: 0.42, ridge: 0, bump: 0.25 }); this._mouthKey = '';
    { // the face's surface round the mouth, measured once (the lips are laid on it every time the mouth moves)
      const my = cy - 0.108 * s, x0 = -0.066 * s, y0 = my - 0.075 * s, st = 0.003 * s, nx = 45, ny = 40, g = new Float32Array(nx * ny); for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) g[j * nx + i] = surf(x0 + i * st, y0 + j * st);
      this.surfM = (x, y) => { const fx = clamp((x - x0) / st, 0, nx - 1.001), fy = clamp((y - y0) / st, 0, ny - 1.001), i = Math.floor(fx), j = Math.floor(fy), u = fx - i, v = fy - j; return (g[j * nx + i] * (1 - u) + g[j * nx + i + 1] * u) * (1 - v) + (g[(j + 1) * nx + i] * (1 - u) + g[(j + 1) * nx + i + 1] * u) * v; };
      const zs = surf(0, my), inM = new THREE.MeshStandardMaterial({ color: '#2a0b0d', roughness: 0.9, side: THREE.BackSide }), tooth = mat('#f1ead9', 'plastic', { rough: 0.35, side: THREE.DoubleSide }), rT = 0.034 * s;
      const cav = mesh(ball(0.052 * s, 0.05 * s, 0.046 * s, 20), inM, { p: [0, my - 0.014 * s, zs - 0.04 * s], cast: false }), arc = (h) => new THREE.CylinderGeometry(rT, rT * 0.97, h, 28, 1, true, -1.0, 2.0);
      const up = mesh(arc(0.013 * s), tooth, { cast: false }), lo = mesh(arc(0.01 * s), tooth, { cast: false }), tg = mesh(ball(0.021 * s, 0.0075 * s, 0.027 * s, 18), mat('#b5504f', 'clay', { rough: 0.4, ridge: 0, bump: 0.2 }), { cast: false });
      const inner = this.inner = group([cav, up, lo, tg]); inner.visible = false; hd.add(inner); this.MP = { my, zs, rT, up, lo, tg }; }
    if (L.moustache) { const mm = mat(L.moustache, 'clay', { bump: 1, ns: 60 }); for (const sd of [1, -1]) hd.add(mesh(ball(0.036 * s, 0.014 * s, 0.014 * s), mm, { p: [sd * 0.027 * s, cy - 0.082 * s, surf(0.027 * s, cy - 0.082 * s) + 0.008], r: [0, sd * 0.3, sd * -0.32] })); }
    // hair
    if (L.hairStyle === 'part') {
      const cap = new THREE.SphereGeometry(1, 40, 20, 0, Math.PI * 2, 0, Math.PI * 0.43); cap.scale(W * 1.06, H * 1.07, Dp * 1.08); hd.add(mesh(cap, hairM, { p: [0, cy + 0.002, -0.004 * s], r: [-0.52, 0, 0.05] }));
      hd.add(mesh(ball(W * 0.97, H * 0.66, 0.075 * s), hairM, { p: [0, cy + 0.0, -Dp * 0.7] }));
      for (const sd of [1, -1]) hd.add(mesh(ball(0.02 * s, 0.058 * s, 0.05 * s), hairM, { p: [sd * W * 0.965, cy + 0.066 * s, 0.004] }));
      // combed from the parting on his left across the brow: five strands of clay laid side by side, the last one come loose
      const top = (x, y) => surf(x, y);
      for (let i = 0; i < 5; i++) { const x = (0.07 - i * 0.042) * s, y = cy + H * (0.83 - 0.02 * i), z = top(x, y) + 0.011 * s;
        hd.add(mesh(ball(0.066 * s, 0.017 * s, 0.03 * s), hairM, { p: [x, y, z], r: [0.6, x / W * 0.9, -0.62 - i * 0.05] })); }
      hd.add(mesh(ball(0.04 * s, 0.026 * s, 0.04 * s), hairM, { p: [0.112 * s, cy + H * 0.78, Dp * 0.38], r: [0.3, 0.6, 0.9] }));
      hd.add(mesh(limb(0.012 * s, 0.05 * s, 0.005 * s, 10), hairM, { p: [-0.098 * s, cy + H * 0.72, top(-0.098 * s, cy + H * 0.72) + 0.012], r: [0.75, 0, 0.35] }));
    } else if (L.hairStyle === 'cap') {
      const hh = new THREE.SphereGeometry(1, 32, 16, Math.PI / 2 + 1.05, Math.PI * 2 - 2.1, Math.PI * 0.3, Math.PI * 0.3); hh.scale(W * 1.05, H * 1.03, Dp * 1.06); hd.add(mesh(hh, hairM, { p: [0, cy, -0.006], r: [-0.3, 0, 0] }));
      const navy = mat('#1e2740', 'felt'); const cp = new THREE.SphereGeometry(1, 32, 16, 0, Math.PI * 2, 0, Math.PI * 0.42); cp.scale(W * 1.08, H * 1.02, Dp * 1.1); hd.add(mesh(cp, navy, { p: [0, cy + 0.012, 0], r: [-0.16, 0, 0] }));
      hd.add(mesh(rbox(W * 1.5, 0.012, 0.085 * s, 0.006), mat('#141a2c', 'clay', { rough: 0.3 }), { p: [0, cy + H * 0.5, Dp * 0.98], r: [0.22, 0, 0] }));
      hd.add(mesh(ball(0.016 * s, 0.016 * s, 0.006), mat('#d2b25a', 'tin'), { p: [0, cy + H * 0.66, Dp * 0.93], r: [-0.4, 0, 0] }));
    } else if (L.hairStyle === 'plait') {
      const cap = new THREE.SphereGeometry(1, 40, 20, 0, Math.PI * 2, 0, Math.PI * 0.43); cap.scale(W * 1.06, H * 1.07, Dp * 1.08); hd.add(mesh(cap, hairM, { p: [0, cy + 0.002, -0.004 * s], r: [-0.52, 0, 0] }));
      hd.add(mesh(ball(W * 0.97, H * 0.72, 0.078 * s), hairM, { p: [0, cy - 0.006, -Dp * 0.7] }));
      // a centre parting: two smooth sweeps to the ears
      for (const sd of [1, -1]) { hd.add(mesh(ball(0.07 * s, 0.022 * s, 0.045 * s), hairM, { p: [sd * 0.062 * s, cy + H * 0.8, surf(sd * 0.062 * s, cy + H * 0.8) + 0.008 * s], r: [0.6, sd * 0.45, sd * -0.5] }));
        hd.add(mesh(ball(0.022 * s, 0.075 * s, 0.055 * s), hairM, { p: [sd * W * 0.96, cy + 0.045 * s, 0.006] })); }
      for (let i = 0; i < 9; i++) hd.add(mesh(ball(0.03 * s * (1 - i * 0.05), 0.026 * s, 0.028 * s), hairM, { p: [0.1 * s + i * 0.004, cy - H * 0.62 - i * 0.04 * s, -Dp * 0.45 + i * 0.03 * s] }));
    }
    if (L.glasses) { const fm = mat('#22252c', 'plastic', { rough: 0.35 }), gw = 0.096 * s, gh = 0.066 * s, rr = 0.016 * s, gz = surf(ex, ey) + 0.022 * s, gg = this.glasses = new THREE.Group(); hd.add(gg);
      for (const sd of [1, -1]) { const pts = [], n = 40; for (let i = 0; i < n; i++) { const a = i / n * Math.PI * 2, cx = Math.cos(a), sy = Math.sin(a), px = Math.sign(cx) * (gw / 2 - rr) + cx * rr * (Math.abs(cx) ** 0.2), py = Math.sign(sy) * (gh / 2 - rr) + sy * rr * (Math.abs(sy) ** 0.2); pts.push(V3(sd * ex + px, ey - 0.004 + py, gz - Math.abs(px + sd * 0.0) * 0.12)); }
        gg.add(mesh(tube(pts, 0.0042 * s, 80, 6, true), fm));
        gg.add(mesh(rbox(gw - 0.006, gh - 0.006, 0.0015, rr * 0.8), new THREE.MeshStandardMaterial({ color: '#cfe6ff', transparent: true, opacity: 0.13, roughness: 0.05, metalness: 0.6 }), { p: [sd * ex, ey - 0.004, gz - 0.002], cast: false }));
        gg.add(mesh(tube([[sd * (ex + gw / 2), ey + 0.008, gz - 0.006], [sd * (W + 0.006), ey + 0.012, gz - 0.07 * s], [sd * (W + 0.004), ey + 0.004, -0.02]], 0.0036 * s, 12, 6), fm)); }
      gg.add(mesh(tube([[-(ex - gw / 2), ey + 0.006, gz], [0, ey + 0.012, gz + 0.004], [ex - gw / 2, ey + 0.006, gz]], 0.0038 * s, 8, 6), fm)); }
  }
  /**
   * The mouth: two lips of clay laid on the face round an opening that is really cut into the head, with teeth and a tongue inside.
   * curve −1 (frown) … 1 (smile), open 0 … 1 (the jaw), w its width (0.5 pursed … 1 … 1.3 spread), skew, tongue 0 … 1 (up behind the teeth)
   */
  setMouth(curve = -0.1, open = 0, w = 1, skew = 0, tongue = 0) {
    const key = [curve, open, w, skew, tongue].map((x) => (+x || 0).toFixed(3)).join(); if (key === this._mouthKey) return; this._mouthKey = key;
    const g = this.mouthG, s = this.L.h, { my, zs, rT, up, lo, tg } = this.MP; while (g.children.length) { const c = g.children.pop(); c.geometry.dispose(); }
    const o = open < 0.05 ? 0 : open, hw = 0.037 * s * w, cK = curve * 0.016 * s, sk = skew * 0.18, oU = o ? (0.0035 + 0.0045 * o) * s : 0, oL = o ? 0.036 * s * o : 0, purse = clamp((0.85 - w) / 0.35);
    const U = this.MU; U.uMouth.value.set(my, hw, oU, oL); U.uMouthC.value = cK; U.uMouthS.value = sk; U.uJaw.value = o * 0.012 * s;
    const yc = (x) => my - cK * (1 - (x / hw) ** 2) + sk * x, shp = (x) => Math.max(0, 1 - (x / hw) ** 2) ** 0.75, lift = 0.0012 + purse * 0.006 * s, rU = (0.0039 + purse * 0.0014) * s, rL = (0.0047 + purse * 0.0016) * s;
    const line = (f, r) => { const pts = [], n = 14, e = hw * 1.05; for (let i = 0; i <= n; i++) { const x = -e + (i / n) * 2 * e, xx = clamp(x, -hw, hw), y = f(xx); pts.push(V3(x, y, this.surfM(x, y) + lift * (0.4 + 0.6 * shp(xx)))); } return mesh(tube(pts, r, 28, 7), this.mouthM, { cast: false }); };
    g.add(line((x) => yc(x) + oU * shp(x) + rU * (o ? 0.55 : 0.5), rU), line((x) => yc(x) - oL * shp(x) - rL * (o ? 0.55 : 0.42), rL));
    this.inner.visible = o > 0; if (!o) return;
    up.position.set(0, yc(0) + oU - 0.0035 * s, zs - rT - 0.0035 * s); { const yl = yc(0) - oL - 0.001 * s; lo.position.set(0, yl, Math.min(zs, this.surfM(0, yl - 0.006 * s)) - rT * 0.86 - 0.011 * s); lo.scale.set(0.86, 1, 0.86); lo.visible = o > 0.22; }
    tg.position.set(0, yc(0) - oL + (0.009 + 0.011 * tongue) * s, zs - (0.031 - 0.008 * tongue) * s); tg.rotation.x = -0.15 - tongue * 0.55;
  }
  /**
   * Put the puppet into a pose. All optional:
   * { at: [x, y, z] (hips, world), yaw°, lean: [pitch°, roll°, twist°] (spine), tilt: [pitch°, roll°] (hips),
   *   head: [yaw°, pitch°, roll°], gaze: [yaw°, pitch°], lids: 0…1 | [L, R], lower: 0…1, brows: [height −1…1, angle° (inner end up +)] | { L: […], R: […] },
   *   mouth: [curve, open, w, skew],
   *   handL / handR: { p: [x, y, z] in the body's frame from the hips (or w: world), f: [x, y, z] where the fingers point (body frame), n: where the palm faces,
   *                    pole: [x, y, z] where the elbow leans, curl, thumb, spread },
   *   footL / footR: { p: [x, y, z] body frame from the hips (or w: world), toe: yaw°, pitch°, pole: [..] } }
   */
  set(P) {
    const d = { at: [0, 0.64 * this.L.h, 0], yaw: 0, lean: [0, 0, 0], tilt: [0, 0], head: [0, 0, 0], gaze: [0, 0], lids: this.L.lids, lower: 0.12, brows: [0, 0], mouth: [-0.12, 0, 1, 0], ...P }; this.pose = d; const s = this.L.h, B = this.B, r = this.root;
    r.position.set(...d.at); r.rotation.set(d.tilt[0] * D, d.yaw * D, d.tilt[1] * D, 'YXZ'); this.spine.rotation.set(d.lean[0] * D, d.lean[2] * D, d.lean[1] * D, 'YXZ'); this.head.rotation.set(d.head[1] * D, d.head[0] * D, d.head[2] * D, 'YXZ');
    r.updateMatrixWorld(true);
    const bodyQ = Q(); r.getWorldQuaternion(bodyQ); const toW = (v) => V3(...v).applyQuaternion(bodyQ), ptW = (v) => r.localToWorld(V3(...v));
    for (const k of ['L', 'R']) {
      const a = this.arms[k], sd = a.sd, h = d['hand' + k] ?? { p: [sd * (B.shW + 0.035), 0.345 * s - (B.upper + B.fore) * 0.97, 0.03], f: [0, -1, 0.12], n: [-sd, 0, 0.1] };
      const S = a.up.getWorldPosition(V3()), T = h.w ? V3(...h.w) : ptW(h.p), pole = toW(h.pole ?? [sd * 0.6, -0.5, -0.75]); this._ik(a.up, a.fo, S, T, B.upper, B.fore, pole);
      // the hand: fingers along f, the palm facing n
      const f = toW(h.f ?? [0, -1, 0.2]).normalize(), n = toW(h.n ?? [-sd, 0, 0]); n.addScaledVector(f, -n.dot(f)).normalize(); const y = f.clone().multiplyScalar(-1), x = n.clone().multiplyScalar(a.hand.p), z = V3().crossVectors(x, y);
      a.fo.updateMatrixWorld(true); setWorldQ(a.hand.g, Q().setFromRotationMatrix(new THREE.Matrix4().makeBasis(x, y, z))); this.setHand(k, h.curl ?? 0.28, h.thumb ?? 0.3, h.spread ?? 0);
      const l = this.legs[k], ft = d['foot' + k] ?? { p: [sd * B.hip * 1.15, -(B.thigh + B.shin) * 0.995, 0.0] }, HJ = l.th.getWorldPosition(V3()), FT = ft.w ? V3(...ft.w) : ptW(ft.p);
      this._ik(l.th, l.sh, HJ, FT, B.thigh, B.shin, toW(ft.pole ?? [sd * 0.12, 0.1, 1])); l.sh.updateMatrixWorld(true);
      const ty = ((ft.toe ?? sd * 8) + d.yaw) * D, tp = (ft.pitch ?? 0) * D; setWorldQ(l.ft, Q().setFromEuler(new THREE.Euler(tp, ty, 0, 'YXZ')));
    }
    // face
    const lids = Array.isArray(d.lids) ? d.lids : [d.lids, d.lids];
    ['L', 'R'].forEach((k, i) => { const e = this.eyes[k], o = clamp(lids[i], 0, 1.15); e.pg.rotation.set(-d.gaze[1] * D, d.gaze[0] * D, 0, 'YXZ'); e.up.rotation.x = (38 - o * 66.6) * D; e.gl.visible = o > 0.22; e.lo.rotation.x = Math.PI + (72 - (d.lower + (1 - clamp(o / 0.35)) * 0.5) * 60) * D;
      const b = this.brows[k], bv = d.brows[k] ?? d.brows; b.g.position.y = b.y0 + bv[0] * 0.02 * s; b.g.position.z = this.surf(0.06 * s, b.g.position.y) + 0.004; b.g.rotation.z = b.sd * -bv[1] * D; });
    this.setMouth(...d.mouth);
    if (this.card) { /* the card hangs straight whatever the chest does */ this.card.rotation.set(0, 0, 0); const q = Q(); this.card.parent.getWorldQuaternion(q); const e = new THREE.Euler().setFromQuaternion(q, 'YXZ'); this.card.rotation.set(-e.x * 0.9, 0, -e.z * 0.9); }
    r.updateMatrixWorld(true);
  }
  _ik(up, lo, S, T, a, b, pole) {
    const d = T.clone().sub(S); let L = d.length(); const mx = (a + b) * 0.999; if (L > mx) { d.multiplyScalar(mx / L); L = mx; } const dir = d.clone().normalize();
    const ca = clamp((a * a + L * L - b * b) / (2 * a * L), -1, 1), h = a * Math.sqrt(1 - ca * ca); const pn = pole.clone().addScaledVector(dir, -pole.dot(dir)); if (pn.lengthSq() < 1e-8) pn.set(0, 0, 1); pn.normalize();
    const E = S.clone().addScaledVector(dir, a * ca).addScaledVector(pn, h), tgt = S.clone().add(d);
    setWorldQ(up, aimQ(E.clone().sub(S), pn)); up.updateMatrixWorld(true); setWorldQ(lo, aimQ(tgt.clone().sub(E), pn)); lo.updateMatrixWorld(true);
  }
  /** world position of a named point: 'head' | 'eyes' | 'handL' | 'handR' | 'gripL' | 'gripR' (between thumb and fingers) | 'chest' | 'hips' */
  at(name) {
    const s = this.L.h; if (name === 'hips') return this.root.getWorldPosition(V3()); if (name === 'chest') return this.spine.localToWorld(V3(0, 0.25 * s, 0)); if (name === 'head') return this.head.localToWorld(V3(0, this.HS.cy, 0)); if (name === 'eyes') return this.head.localToWorld(V3(0, this.HS.cy + 0.022 * s, this.HS.Dp));
    const k = name.slice(-1), h = this.arms[k].hand; if (name.startsWith('grip')) return h.g.localToWorld(V3(h.p * 0.02 * s, -0.07 * s, 0.012 * s)); return h.g.localToWorld(V3(0, -0.04 * s, 0));
  }
  /** the hand's frame in the world: { f (fingers), n (palm), t (thumb side) } */
  handFrame(k) { const h = this.arms[k].hand, q = Q(); h.g.getWorldQuaternion(q); return { f: V3(0, -1, 0).applyQuaternion(q), n: V3(h.p, 0, 0).applyQuaternion(q), t: V3(0, 0, 1).applyQuaternion(q) }; }
}

// ------------------------------------------------------------------ poses in time
const mixv = (a, b, k) => (typeof a === 'number' && typeof b === 'number' ? lerp(a, b, k) : Array.isArray(a) && Array.isArray(b) ? a.map((x, i) => mixv(x, b[i] ?? x, k)) : a && b && typeof a === 'object' && typeof b === 'object' ? Object.fromEntries([...new Set([...Object.keys(a), ...Object.keys(b)])].map((q) => [q, a[q] === undefined ? b[q] : b[q] === undefined ? a[q] : mixv(a[q], b[q], k)])) : k < 0.5 ? a : b);
/**
 * keys: [[t, pose, ease?], …] in order. A pose key carries only what changes (the rest is held from the keys before).
 * → (t) => pose. Sampled ON TWOS by the film (12 a second). ease: 'io' (default) | 'out' | 'in' | 'lin' | 'snap'
 */
export function track(keys) {
  const full = []; let cur = {}; for (const [t, p, e] of keys) { cur = { ...cur, ...p }; full.push([t, cur, e ?? 'io']); }
  return (t) => { if (t <= full[0][0]) return full[0][1]; for (let i = 1; i < full.length; i++) if (t < full[i][0]) { const [t0, a] = full[i - 1], [t1, b, e] = full[i]; let k = (t - t0) / (t1 - t0);
    k = e === 'lin' ? k : e === 'out' ? 1 - (1 - k) ** 3 : e === 'in' ? k ** 3 : e === 'snap' ? (k < 0.85 ? 0 : 1) : k * k * k * (k * (k * 6 - 15) + 10); return mixv(a, b, k); } return full[full.length - 1][1]; };
}
/** the hand of whoever animates: every pose lands a hair off (a millimetre, half a degree), new each pose — never the same twice */
export function handmade(pose, step, amt = 1) {
  const j = (n) => (hash(step * 7.13 + n * 3.7) - 0.5) * amt, p = { ...pose };
  if (p.head) p.head = [p.head[0] + j(1) * 0.9, p.head[1] + j(2) * 0.7, p.head[2] + j(3) * 0.6]; if (p.lean) p.lean = [p.lean[0] + j(4) * 0.5, p.lean[1] + j(5) * 0.4, p.lean[2] + j(6) * 0.5];
  for (const k of ['handL', 'handR']) if (p[k] && p[k].p) p[k] = { ...p[k], p: [p[k].p[0] + j(k === 'handL' ? 7 : 8) * 0.0025, p[k].p[1] + j(k === 'handL' ? 9 : 10) * 0.0025, p[k].p[2] + j(11) * 0.0025] };
  return p;
}
