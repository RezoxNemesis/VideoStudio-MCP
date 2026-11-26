// @engine codescape
// THE CODESCAPE LIBRARY, LIVE — the people, their hands, the holds and the speech movement that every CodeScape film can use, each as a shot.
// Open it in Cine Studio (film: "Library"): turn a person round, watch a hand take a card and set it down, see a head ride its words.
// Copy what you need into a film: the code of each shot is the recipe. The functions are in motion.js, rig.js, people.js (Library tab).
import { THREE, V3, clamp, lerp, sm, sm2, seg } from './engine.js';
import * as K from './kit.js';
import { blinkAt } from './rig.js';
import { makeMaa, makeSon } from './people.js';
import { refineHands, pinchSheet, snap, blendHand, looseHand, speechPlan, speechHead, nodHead, steady, anchorAt, blinks, saccade } from './motion.js';
export const META = { name: 'CodeScapeLibrary', width: 1080, height: 1920, fps: 30, order: ['vikas', 'maa', 'hold', 'place', 'talk'] };
export const S = {};
const PI = Math.PI, XV = new V3(1, 0, 0), _x = new V3(), _y = new V3(), _z = new V3(), _m = new THREE.Matrix4();
function setL(L, c, ax, nrm) { _x.copy(ax).normalize(); _z.copy(nrm).addScaledVector(_x, -nrm.dot(_x)).normalize(); _y.crossVectors(_z, _x); _m.makeBasis(_x, _y, _z); L.quaternion.setFromRotationMatrix(_m); L.position.copy(c); L.updateMatrixWorld(true); }
function room(E) { E.scene.add(new THREE.HemisphereLight('#fff4e0', '#6a5a4a', 1.1)); const d = new THREE.DirectionalLight('#fff0dc', 2.4); d.position.set(1.2, 2.6, 2.0); d.castShadow = true; d.shadow.mapSize.set(2048, 2048); d.shadow.camera.left = -1.5; d.shadow.camera.right = 1.5; d.shadow.camera.top = 2.2; d.shadow.camera.bottom = -0.2; E.scene.add(d); const r = new THREE.DirectionalLight('#9ab4ff', 1.1); r.position.set(-1.4, 1.8, -1.6); E.scene.add(r);
  const fl = new THREE.Mesh(new THREE.CircleGeometry(3, 64), new THREE.MeshStandardMaterial({ color: '#7d7468', roughness: 0.9 })); fl.rotation.x = -PI / 2; fl.receiveShadow = true; E.scene.add(fl); const bw = new THREE.Mesh(new THREE.CylinderGeometry(3, 3, 4, 48, 1, true), new THREE.MeshStandardMaterial({ color: '#a89a86', roughness: 1, side: THREE.BackSide })); bw.position.y = 2; E.scene.add(bw); }
function card(text = 'A LETTER', w = 0.17, h = 0.095) { const t = K.canvasTex(512, 286, (g, W, H) => { g.fillStyle = '#e4d3a6'; g.fillRect(0, 0, W, H); g.strokeStyle = 'rgba(90,60,30,.35)'; g.lineWidth = 6; g.strokeRect(10, 10, W - 20, H - 20); g.fillStyle = '#2a2f55'; g.textAlign = 'center'; g.font = 'bold 54px PoppinsB'; g.fillText(text, W / 2, H * 0.58); }); const b = new THREE.MeshStandardMaterial({ color: '#cdb98a', roughness: 0.9 });
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, 0.003), [b, b, b, b, new THREE.MeshStandardMaterial({ map: t, roughness: 0.85 }), b]); m.castShadow = true; m.userData = { W: w, H: h }; return m; }
function stand(p, x, z, yaw, t, o = {}) { p.place(x, z, yaw); const f = p.fwd(), l = p.left(), sz = p.size, br = Math.sin(t * 1.6) * 0.004, sh = Math.sin(t * 0.5) * 0.008;
  p.body({ pel: new V3(x, 0.8 * sz + br, z).addScaledVector(l, sh).addScaledVector(f, o.pf || 0), lean: o.lean ?? 0.03, chestLean: -0.012 * Math.sin(t * 1.6 + 0.5), chestSide: sh * 0.9, twist: 0.025 * Math.sin(t * 0.37 + 0.7), pelRoll: -sh * 0.8, feet: { l: { p: new V3(x, 0.075 * sz, z).addScaledVector(l, 0.085).addScaledVector(f, 0.02), dir: f.clone().addScaledVector(l, 0.12) }, r: { p: new V3(x, 0.075 * sz, z).addScaledVector(l, -0.085).addScaledVector(f, -0.02), dir: f.clone().addScaledVector(l, -0.12) } } }); }
function idleArm(p, s, t) { p.hang(s, 0.03 * Math.sin(t * 0.8 + (s === 'l' ? 0 : 2)), 0.28); looseHand(p, s, t); }
function face(p, T, o = {}) { Object.assign(p.face, { blink: Math.max(o.nb ? 0 : blinkAt(T, o.seed ?? 2), o.blink || 0), lidL: o.lid ?? 0.1, lidR: o.lid ?? 0.1, squint: 0, browL: o.brow ?? 0.1, browR: o.brow ?? 0.1, knit: 0, sad: o.sad || 0, jaw: o.jaw || 0, wide: 0, round: 0, smile: o.smile ?? 0.2, puff: 0, press: 0 }); p.faceUpdate(T); }
const eyes = p => { p.head.updateWorldMatrix(true, false); return p.head.localToWorld(new V3(0, 0.11, 0.12)); };
const look = { exp: 0.9, grade: [1, 1, 1.06, 0.2], vig: 0.25, bloom: 0.03 };
const person = who => async E => { await K.init(E); room(E); const p = who === 'maa' ? makeMaa(E) : makeSon(E); if (p.bagOff) p.bagOff(); refineHands(p, { k: who === 'maa' ? 0.9 : 1 }); return { p }; };

// a person, turning before the lens: breathing, weight that shifts, hands that live, eyes that look about and blink
const turn = who => ({ dur: 8, about: `${who === 'maa' ? 'Maa' : 'Vikas'} on a turntable: the rig alive at rest — breath, a shifting weight, loose hands, eyes that look and blink. makeMaa / makeSon (people.js) + refineHands (motion.js)`, build: person(who),
  frame(t, c) { const { p } = c, a = t / 8 * 2 * PI; stand(p, 0, 0, a, t); idleArm(p, 'l', t); idleArm(p, 'r', t); const cam = new V3(0, 1.3 * p.size, 2.2); p.look(cam.clone().add(saccade(t, 3, 0.25)), 0.55); face(p, t, { smile: 0.25 }); if (p.sleeveTick) p.sleeveTick();
    return { p: [0, 1.05 * p.size, 2.9], t: [0, 0.92 * p.size, 0], fov: 34, focus: 2.9, dof: 0, ...look }; } });
S.vikas = turn('son'); S.maa = turn('maa');

// the holds: the same card held flat (fingers behind, thumb in front) and then in a curled pinch — pinchSheet(p, side, sheet, { at, into, flex })
S.hold = { dur: 8, about: 'the holds, close: a card held flat in the left hand (thumb on its front, finger pads on its back), then pinched by its top edge in the right. pinchSheet (motion.js): the hand follows the thing, so it cannot float or slide', build: async E => { const c = await person('son')(E); c.card = card('HOLD'); E.scene.add(c.card); c.rep = {}; return c; },
  frame(t, c) { const { p, card: L } = c; stand(p, 0, 0, 0, t); const f = p.fwd(), l = p.left(); p.sp2.updateWorldMatrix(true, false); const ch = p.sp2.localToWorld(new V3(0, 0.08, 0)); const two = sm2(seg(t, 3.6, 4.6));
    setL(L, ch.clone().addScaledVector(f, 0.3).add(new V3(0, -0.02 + 0.004 * Math.sin(t * 1.7), 0)), l.clone().negate(), f.clone().negate().add(new V3(0, 0.3, 0)));
    if (two < 1) { c.rep.flat = { ...pinchSheet(p, 'l', L, { at: [-L.userData.W / 2 + 0.022, -0.004], into: 0.3, flex: 0.08, th: 0.003, pole: l.clone().multiplyScalar(0.5).addScaledVector(f, -0.3).add(new V3(0, -0.9, 0)), open: two }), t }; /* the elbow's pole is given in HIS frame: out to his left, down, a little back */ idleArm(p, 'r', t); }
    else { idleArm(p, 'l', t); c.rep.pinch = { ...pinchSheet(p, 'r', L, { at: [0.03, L.userData.H / 2 - 0.016], into: -1.2, flex: 1, th: 0.003, pole: l.clone().multiplyScalar(-0.7).addScaledVector(f, -0.4).add(new V3(0, -0.5, 0)) }), t }; }
    p.look(L.position, 0.7); face(p, t, { lid: 0.25 }); if (p.sleeveTick) p.sleeveTick(); const a = 0.6 * Math.sin(t / 8 * 2 * PI); const C = new V3(0, 1.16, 0.3); /* the lens circles a fixed point, not the card (which breathes with him) */
    return { p: [C.x + Math.sin(a + PI) * 0.62, C.y + 0.08, C.z + Math.cos(a + PI) * 0.62 * -1], t: [C.x, C.y + 0.02, C.z], fov: 36, focus: 0.62, dof: 0, ...look }; } };

// carry, set down, lean, let go, draw back: the nine key poses of placing a flat thing (travel a quarter of the time; contact and the action half)
S.place = { dur: 5, about: 'placing: Maa carries a card in on an arc, sets its bottom edge on a ledge, leans it back, lifts her thumb, slides her fingers out the way they came. The card is moved; the hand holds it (pinchSheet), then snap + blendHand take the hand away', build: async E => { const c = await person('maa')(E); c.card = card('PLACE'); E.scene.add(c.card); const sh = new THREE.Mesh(new THREE.BoxGeometry(0.7, 0.035, 0.3), new THREE.MeshStandardMaterial({ color: '#6b3f22', roughness: 0.6 })); sh.position.set(0.62, 1.0025, -0.13); sh.castShadow = sh.receiveShadow = true; E.scene.add(sh); const back = new THREE.Mesh(new THREE.BoxGeometry(0.7, 0.4, 0.02), new THREE.MeshStandardMaterial({ color: '#8a6a4a' })); back.position.set(0.62, 1.2, -0.27); E.scene.add(back); c.rep = {}; return c; },
  frame(t, c) { const { p, card: L } = c, Hh = L.userData.H, W = L.userData.W; const REST = new V3(0.62, 1.021, -0.2), LEAN = 0.3; const k1 = sm2(seg(t, 0.2, 1.7)), down = sm2(seg(t, 1.65, 2.0)), ln = sm2(seg(t, 2.0, 2.5));
    const B = new V3(0.32, 1.12, -0.1).lerp(new V3(REST.x, REST.y + 0.024, REST.z + 0.008), k1); B.y += Math.sin(k1 * PI) * 0.02; B.lerp(REST, down); const lean = lerp(lerp(0.2, 0.06, k1), LEAN, ln), yaw = lerp(0.35, 0.03, k1);
    { const X = new V3(Math.cos(yaw), 0, -Math.sin(yaw)), N = new V3(Math.cos(lean) * Math.sin(yaw), Math.sin(lean), Math.cos(lean) * Math.cos(yaw)), Y = new V3().crossVectors(N, X); setL(L, B.clone().addScaledVector(Y, Hh / 2), X, N); }
    const rk = sm(seg(t, 0, 1.4)) * (1 - sm(seg(t, 3.0, 4.2))); stand(p, 0.12, 0.03, PI / 2 + 0.18, t, { lean: 0.04 + 0.1 * rk, pf: 0.03 * rk }); idleArm(p, 'r', t); const pole = new V3(-0.3, -1, 0.15);
    const hold = o => pinchSheet(p, 'l', L, { at: [-W / 2 + 0.022, 0.004], into: 0.2, flex: 0.06, th: 0.003, pole, ...o }); const rel = sm(seg(t, 2.7, 3.0)), out = seg(t, 3.0, 4.1);
    if (out <= 0) c.rep.place = { ...hold({ open: rel }), t }; else { hold({ open: 1 }); const A = snap(p, 'l'); idleArm(p, 'l', t); const Bp = snap(p, 'l'); blendHand(p, 'l', A, Bp, out, { via: A.T.clone().add(new V3(-0.13, 0.012, 0)), pole, fingers: u => seg(u, 0.3, 0.95) }); }
    p.look(L.position, 0.6); face(p, t, { smile: 0.25, lid: 0.25, seed: 8 }); return { p: [0.6, 1.2, 1.0], t: [0.5, 1.1, -0.15], fov: 40, focus: 1.15, dof: 0, ...look }; } };

// speech: the head rides the words — a small lift before a phrase, a nod on its weightiest word, a settle after; brows flash; the lens stays still
const WORDS = [['kehte', 0.5, 0.82], ['the', 0.84, 1.0], ['jis', 1.5, 1.68], ['din', 1.7, 1.9], ['mera', 1.92, 2.15], ['beta', 2.17, 2.5], ['kamaayega', 2.52, 3.2], ['usi', 3.75, 3.98], ['din', 4.0, 4.2], ['khulegi', 4.22, 4.9]], PLAN = speechPlan(WORDS);
S.talk = steady({ dur: 6, about: 'speech movement: speechPlan + speechHead + nodHead make a head lift before a phrase, nod on the stressed word and settle; steady() keeps the lens on the rest position of the eyes, so the picture never shakes with the words', build: person('maa'),
  frame(t, c) { const { p } = c; stand(p, 0, 0, 0.25, t); idleArm(p, 'l', t); idleArm(p, 'r', t); const cam = new V3(0.35, 1.3, 1.1); p.look(cam.clone().add(saccade(t, 7, 0.02)), 0.8); const sp = speechHead(PLAN, t); nodHead(p, sp); const w = WORDS.find(w => t >= w[1] && t <= w[2]);
    face(p, t, { nb: 1, blink: blinks(t, [0.3, 1.25, [3.4, 0.3], 5.3]), brow: 0.3 + sp.brow, jaw: w ? 0.12 + 0.25 * Math.abs(Math.sin((t - w[1]) * 22)) : 0, smile: 0.25, sad: 0.3 }); const e = anchorAt(c, 'eyes', t);
    return { p: [0.35, e.y + 0.02, 1.05], t: [e.x, e.y - 0.04, e.z], fov: 30, focus: 1.1, dof: 1.5, ...look, hud: (g, W, H) => { g.save(); g.font = `600 ${Math.round(W * 0.05)}px PoppinsB, sans-serif`; g.textAlign = 'center'; g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(0, H * 0.8, W, H * 0.08); g.fillStyle = '#ffd27a'; g.fillText(w ? w[0] : '·', W / 2, H * 0.855); g.restore(); } }; } }, [0.1, 5.9], c => ({ eyes: eyes(c.p) }));
