// Characters: load a sculpted body, build skeleton + meshes + materials, and give actors a face and eyes to perform with.
import { THREE, clamp, lerp, wob } from './core.js';
import * as M from './mat.js';

const _q = new THREE.Quaternion(), _q2 = new THREE.Quaternion(), _v = new THREE.Vector3(), _v2 = new THREE.Vector3(), _e = new THREE.Euler(), _m = new THREE.Matrix4();
const X = new THREE.Vector3(1, 0, 0), Y = new THREE.Vector3(0, 1, 0), Z = new THREE.Vector3(0, 0, 1);

export class Human {
  /** data: CharData from ../human/factory.ts (typed arrays in memory) */
  constructor(data, opts = {}) {
    const man = data; this.man = man; this.lm = man.landmarks; this.name = man.name; this.height = man.height;
    const look = this.look = { ...man.look, ...(opts.look || {}) };
    this.root = new THREE.Group(); this.root.name = man.name;
    // skeleton: every bone's rest frame is world-aligned, so a bone rotation is a rotation in character space
    this.bones = man.bones.map(b => { const o = new THREE.Bone(); o.name = b.name; return o; });
    this.B = {}; this.rest = {}; const inv = [];
    man.bones.forEach((b, i) => {
      const o = this.bones[i], h = new THREE.Vector3(...b.head); this.B[b.name] = o; this.rest[b.name] = { head: h, tail: new THREE.Vector3(...b.tail), parent: b.parent >= 0 ? man.bones[b.parent].name : null };
      if (b.parent >= 0) { o.position.copy(h).sub(new THREE.Vector3(...man.bones[b.parent].head)); this.bones[b.parent].add(o); } else { o.position.copy(h); this.root.add(o); }
      inv.push(new THREE.Matrix4().makeTranslation(-h.x, -h.y, -h.z));
    });
    this.skeleton = new THREE.Skeleton(this.bones, inv);
    this.meshes = {}; this.morphIndex = Object.fromEntries(man.morphNames.map((n, i) => [n, i])); this.morph = {};
    const colors = look.colors || {};
    for (const p of man.parts) {
      if (opts.skip && opts.skip.includes(p.name)) continue;
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(p.pos.slice(), 3));
      g.setAttribute('aux', new THREE.BufferAttribute(p.aux, 3));
      g.setAttribute('skinIndex', new THREE.BufferAttribute(p.skinIndex, 4));
      g.setAttribute('skinWeight', new THREE.BufferAttribute(p.skinWeight, 4));
      if (p.mask) g.setAttribute('mask', new THREE.BufferAttribute(p.mask, 4));
      g.setIndex(new THREE.BufferAttribute(p.idx, 1));
      if (p.morphs) {
        g.morphAttributes.position = p.morphs.map(mm => { const a = new Float32Array(p.vcount * 3); const ii = mm.i, dd = mm.d; for (let k = 0; k < ii.length; k++) { const j = ii[k] * 3; a[j] = dd[k * 3]; a[j + 1] = dd[k * 3 + 1]; a[j + 2] = dd[k * 3 + 2]; } return new THREE.BufferAttribute(a, 3); });
        g.morphTargetsRelative = true;
      }
      g.computeVertexNormals();
      let mat; const c = colors[p.name] || [0.2, 0.2, 0.2]; const pr = p.params || {};
      switch (p.material) {
        case 'skin': mat = M.skinMaterial(look, this.lm); break;
        case 'eye': mat = M.eyeMaterial(look); break;
        case 'teeth': mat = M.teethMaterial(); break;
        case 'tongue': mat = M.tongueMaterial(); break;
        case 'lash': mat = M.lashMaterial(look); break;
        case 'hair': mat = M.hairMaterial(look, { rag: (pr.rag ?? 1) * 0.75, flow: p.name === 'haircap' ? (look.hairFlow ?? 1) : 0, center: [0, this.lm.eyeL[1] + 0.03, 0.03], sph: p.name !== 'beard', fine: p.name === 'beard' }); break;
        case 'leather': mat = M.leatherMaterial(c, { bands: p.name === 'wraps' ? 55 : 0 }); break;
        case 'metal': mat = M.metalMaterial(c, { rust: pr.rust ?? 0.2, rough: pr.rough }); break;
        default: {
          g.computeBoundingBox();
          mat = M.clothMaterial(c, this.lm, { trim: look.trim, sash: look.sash, vneck: p.name === 'tunic' && pr.vneck !== false, vdepth: pr.vdepth, sashOn: (p.name === 'tunic' || p.name === 'coat' || p.name === 'skirt') && !!look.sash,
            hem: pr.hemTrim ? g.boundingBox.min.y : -99, worn: pr.worn ?? look.worn ?? 0.3, pattern: pr.pattern, rough: pr.rough, sheen: pr.sheen });
        }
      }
      const mesh = new THREE.SkinnedMesh(g, mat); mesh.name = p.name; mesh.castShadow = p.material !== 'lash'; mesh.receiveShadow = true; mesh.frustumCulled = false;
      mesh.bind(this.skeleton, new THREE.Matrix4()); this.root.add(mesh); this.meshes[p.name] = mesh;
      if (p.morphs) mesh.morphTargetInfluences = new Array(p.morphs.length).fill(0);
    }
    this.skinU = this.meshes.skin?.material.userData.u; this.eyeU = this.meshes.eyes?.material.userData.u;
    this.face = { open: 0, shape: 'M', blink: 0, smile: 0, sad: 0, anger: 0, fear: 0, surprise: 0, squint: 0, browUp: 0, browDown: 0, pain: 0, purse: 0, sneer: 0, wide: 0 };
    this._blinkSeed = (opts.seed ?? man.name.length * 7.3); this.gaze = null; this.autoBlink = true; this.props = {}; this.sims = [];
    this._calcHands();
  }
  /** local rotation from Euler degrees (order YXZ: yaw, pitch, roll in character space) */
  rot(name, x = 0, y = 0, z = 0, order = 'XYZ') { const b = this.B[name]; if (b) b.quaternion.setFromEuler(_e.set(x * Math.PI / 180, y * Math.PI / 180, z * Math.PI / 180, order)); return this; }
  addRot(name, x = 0, y = 0, z = 0) { const b = this.B[name]; if (b) b.quaternion.multiply(_q.setFromEuler(_e.set(x * Math.PI / 180, y * Math.PI / 180, z * Math.PI / 180, 'XYZ'))); return this; }
  preRot(name, x = 0, y = 0, z = 0) { const b = this.B[name]; if (b) b.quaternion.premultiply(_q.setFromEuler(_e.set(x * Math.PI / 180, y * Math.PI / 180, z * Math.PI / 180, 'XYZ'))); return this; }
  /** point a bone (towards `to`'s head, or its own tail) along a direction given in the character's space */
  aim(name, dir, to = null) { const b = this.B[name], r = this.rest[name]; const d0 = _v.copy(to ? this.rest[to].head : r.tail).sub(r.head).normalize();
    b.parent.getWorldQuaternion(_q); this.root.getWorldQuaternion(_q2); _q.premultiply(_q2.invert()); const d = _v2.copy(dir).normalize().applyQuaternion(_q.invert()); b.quaternion.setFromUnitVectors(d0, d); return this; }
  resetPose() { for (const b of this.bones) b.quaternion.identity(); const r = this.B.root; r.position.copy(this.rest.root.head); return this; }
  /** pose = {bone:[x,y,z] degrees | Quaternion}; w blends from the current pose */
  pose(p, w = 1) { for (const k in p) { const b = this.B[k]; if (!b) continue; const v = p[k]; if (v.isQuaternion) _q.copy(v); else _q.setFromEuler(_e.set(v[0] * Math.PI / 180, v[1] * Math.PI / 180, v[2] * Math.PI / 180, 'XYZ')); if (w >= 1) b.quaternion.copy(_q); else b.quaternion.slerp(_q, w); } return this; }
  snapshot() { const o = {}; for (const b of this.bones) o[b.name] = b.quaternion.clone(); o.__rootPos = this.B.root.position.clone(); return o; }
  blendTo(snap, w) { for (const b of this.bones) { const q = snap[b.name]; if (q) b.quaternion.slerp(q, w); } if (snap.__rootPos) this.B.root.position.lerp(snap.__rootPos, w); return this; }
  setMorph(name, v) { const i = this.morphIndex[name]; if (i === undefined) return; for (const k in this.meshes) { const m = this.meshes[k]; if (m.morphTargetInfluences) m.morphTargetInfluences[i] = v; } }
  /** world position of a bone head (after updateMatrixWorld) */
  wpos(name, out = new THREE.Vector3()) { return out.setFromMatrixPosition(this.B[name].matrixWorld); }
  update() { this.root.updateMatrixWorld(true); }

  /* ----- hands ----- */
  _calcHands() {
    this.hand = {};
    for (const s of ['L', 'R']) {
      const w = this.rest[`wrist.${s}`].head, mid = this.rest[`finger3-1.${s}`].head, idx = this.rest[`finger2-1.${s}`].head, pk = this.rest[`finger5-1.${s}`].head;
      const d1 = mid.clone().sub(w).normalize(), d2 = pk.clone().sub(idx).normalize(); const n = new THREE.Vector3().crossVectors(d1, d2).normalize(); if (s === 'R') n.negate();   // back-of-hand normal
      this.hand[s] = { fwd: d1, across: d2, back: n };
    }
  }
  /** curl fingers 0..1 (1 = fist). thumb separately. spread optional */
  fingers(side, curl = 0.5, thumb = curl, spread = 0) {
    const H = this.hand[side];
    for (let f = 2; f <= 5; f++) for (let j = 1; j <= 3; j++) {
      const n = `finger${f}-${j}.${side}`; const r = this.rest[n]; if (!r) continue; const dir = _v.copy(r.tail).sub(r.head).normalize();
      const axis = _v2.crossVectors(dir, H.back).normalize(); // the same on both hands: `back` is each hand's own back, so this always closes towards the palm
      const amt = curl * [1.25, 1.55, 1.15][j - 1] * (1 + (f - 3.5) * 0.05); this.B[n].quaternion.setFromAxisAngle(axis, -amt);
      if (j === 1 && spread) this.B[n].quaternion.premultiply(_q.setFromAxisAngle(H.back, (f - 3.5) * spread * 0.12 * (side === 'L' ? 1 : -1)));
    }
    for (let j = 1; j <= 3; j++) { const n = `finger1-${j}.${side}`; const r = this.rest[n]; if (!r) continue; const dir = _v.copy(r.tail).sub(r.head).normalize(); const axis = _v2.crossVectors(dir, H.across).normalize(); this.B[n].quaternion.setFromAxisAngle(axis, thumb * [0.5, 0.6, 0.8][j - 1]); } // folds across the palm, towards the little finger, on either hand
    return this;
  }

  /* ----- face ----- */
  /** write this.face (emotion sliders + mouth) into the expression morphs. t drives automatic blinks. */
  applyFace(t = 0) {
    const f = this.face, s = (n, v) => this.setMorph(n, clamp(v, -0.2, 1.4));
    let blink = f.blink;
    if (this.autoBlink) { const period = 3.1 + wob(Math.floor(t / 3.1), this._blinkSeed) * 1.2; const ph = (t + this._blinkSeed) % period; if (ph < 0.14) blink = Math.max(blink, Math.sin(ph / 0.14 * Math.PI)); }
    const o = clamp(f.open), sh = f.shape;
    const A = sh === 'A' ? 1 : 0, E = sh === 'E' ? 1 : 0, O = sh === 'O' ? 1 : 0;
    const openAmt = o * (0.50 * A + 0.26 * E + 0.40 * O) + f.surprise * 0.35 + f.fear * 0.18 + f.pain * 0.25;
    s('mouth-open', openAmt); s('mouth-part-later', o * 0.25 * A + f.fear * 0.3 + f.pain * 0.4);
    s('mouth-retraction', o * 0.45 * E + f.smile * 0.25 + f.fear * 0.35 + f.pain * 0.5); s('mouth-protusion', o * 0.55 * O + f.purse * 0.6); s('mouth-pursing', o * 0.5 * O + f.purse * 0.7);
    s('mouth-corner-puller', f.smile * 0.9); s('mouth-elevation', f.smile * 0.25 + f.sneer * 0.3); s('mouth-upward-retraction', f.smile * 0.35 + f.anger * 0.25 + f.pain * 0.4);
    s('mouth-depression', f.sad * 0.75 + f.anger * 0.35 + f.fear * 0.3); s('mouth-depression-retraction', f.sad * 0.3 + f.fear * 0.35);
    s('mouth-compression', f.anger * 0.45 * (1 - o) + (1 - o) * 0.0); s('mouth-eversion', f.sad * 0.2 + o * 0.12 * A);
    const cl = clamp(blink + f.squint * 0.18 + f.sad * 0.12 + f.smile * 0.1 + f.pain * 0.6 - f.surprise * 0.2);
    s('eye-left-closure', cl); s('eye-right-closure', cl); s('eye-left-slit', f.squint * 0.34 + f.anger * 0.3 + f.smile * 0.18); s('eye-right-slit', f.squint * 0.34 + f.anger * 0.3 + f.smile * 0.18);
    s('eye-left-opened-up', (f.surprise * 0.9 + f.fear * 0.8 + f.wide) * (1 - blink)); s('eye-right-opened-up', (f.surprise * 0.9 + f.fear * 0.8 + f.wide) * (1 - blink));
    const bu = f.browUp + f.surprise * 0.9 + f.fear * 0.5, bd = f.browDown + f.anger * 1.0 + f.squint * 0.3 + f.pain * 0.6, bi = f.sad * 1.0 + f.fear * 0.7 + f.pain * 0.5;
    for (const sd of ['left', 'right']) { s(`eyebrows-${sd}-up`, bu * 0.8); s(`eyebrows-${sd}-extern-up`, bu * 0.35 + f.anger * 0.3); s(`eyebrows-${sd}-inner-up`, bi); s(`eyebrows-${sd}-down`, bd * 0.9); s(`nose-${sd}-elevation`, f.anger * 0.45 + f.sneer * 0.7 + f.pain * 0.5); s(`nose-${sd}-dilatation`, f.anger * 0.5 + f.fear * 0.3); }
    s('neck-platysma', f.fear * 0.6 + f.pain * 0.7 + f.anger * 0.3);
    // jaw bone follows the mouth so the chin moves too
    if (this.B.jaw) this.B.jaw.quaternion.setFromAxisAngle(X, openAmt * 0.10);
  }
  /** aim the eyes (and optionally head) at a world point */
  lookAt(target, headW = 0, eyeW = 1, keep = false) {
    this.root.updateMatrixWorld(true);
    if (headW > 0) {
      const head = this.B.head, par = this.B.neck01.parent; const local = par.worldToLocal(_v.copy(target)).sub(this.B.neck01.position); local.y -= this.rest.head.head.y - this.rest.neck01.head.y;
      const yaw = clamp(Math.atan2(local.x, local.z), -1.1, 1.1) * headW, pitch = clamp(-Math.atan2(local.y, Math.hypot(local.x, local.z)), -0.7, 0.7) * headW;
      for (const [n, k] of [['neck01', 0.2], ['neck02', 0.2], ['neck03', 0.2], ['head', 0.4]]) { const b = this.B[n]; if (!b) continue; if (!keep) b.quaternion.slerp(_qi, Math.min(1, headW)); b.quaternion.multiply(_q.setFromEuler(_e.set(pitch * k, yaw * k, 0, 'YXZ'))); }
      this.root.updateMatrixWorld(true);
    }
    for (const s of ['L', 'R']) {
      const b = this.B[`eye.${s}`]; const local = b.parent.worldToLocal(_v.copy(target)).sub(b.position);
      const yaw = clamp(Math.atan2(local.x, local.z), -0.5, 0.5) * eyeW, pitch = clamp(-Math.atan2(local.y, Math.hypot(local.x, local.z)), -0.26, 0.3) * eyeW;
      b.quaternion.setFromEuler(_e.set(pitch, yaw, 0, 'YXZ'));
    }
  }
  eyesRot(yawDeg, pitchDeg) { for (const s of ['L', 'R']) this.B[`eye.${s}`].quaternion.setFromEuler(_e.set(pitchDeg * Math.PI / 180, yawDeg * Math.PI / 180, 0, 'YXZ')); }
  /** attach an object to a bone, positioned in character rest space */
  attach(obj, boneName, restPos = null) { const b = this.B[boneName]; b.add(obj); if (restPos) obj.position.copy(restPos).sub(this.rest[boneName].head); return obj; }
  setShadow(cast) { for (const k in this.meshes) this.meshes[k].castShadow = cast && k !== 'lashes'; }
  /** free GPU memory (geometry, materials, the bone texture) */
  dispose() { for (const k in this.meshes) { const m = this.meshes[k]; m.geometry.dispose(); const mats = Array.isArray(m.material) ? m.material : [m.material]; for (const x of mats) x.dispose(); } this.skeleton.dispose(); this.root.removeFromParent(); }
}

/** standing pose from the A-pose: arms relaxed at the sides */
export function relax(h, k = 1) {
  const d = (x, y, z) => _v3.set(x, y, z);
  h.aim('upperarm01.L', d(0.17, -1, 0.02), 'lowerarm01.L').aim('lowerarm01.L', d(0.10, -1, 0.20), 'wrist.L');
  h.aim('upperarm01.R', d(-0.17, -1, 0.02), 'lowerarm01.R').aim('lowerarm01.R', d(-0.10, -1, 0.20), 'wrist.R');
  h.fingers('L', 0.3, 0.25).fingers('R', 0.3, 0.25); return h;
}
const _v3 = new THREE.Vector3(); const _qi = new THREE.Quaternion();
