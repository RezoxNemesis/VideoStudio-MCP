// @engine codescape
// THE CODESCAPE LIBRARY, LIVE — the people, their hands, the holds and the speech movement that every CodeScape film can use, each as a shot.
// Open it in Cine Studio (film: "Library"): turn a person round, watch a hand take a card and set it down, see a head ride its words.
// Copy what you need into a film: the code of each shot is the recipe. The functions are in motion.js, rig.js, people.js (Library tab).
import { THREE, V3, clamp, lerp, sm, sm2, seg } from './engine.js';
import * as K from './kit.js';
import { blinkAt } from './rig.js';
import { makeMaa, makeSon } from './people.js';
import { refineHands, pinchSheet, snap, blendHand, looseHand, speechPlan, speechHead, nodHead, steady, anchorAt, blinks, saccade } from './motion.js';
import { stand, restArm, idleWeight, planWalk, planTurn, stepper, sit, gesture, namaste, passSheet, clipOf, playClip } from './body.js';
import { liveActor } from './live.js';
export const META = { name: 'CodeScapeLibrary', width: 1080, height: 1920, fps: 30, order: ['vikas', 'maa', 'walk', 'turn', 'sit', 'gesture', 'give', 'mocap', 'live', 'hold', 'place', 'talk'] };
/** numbers Cine Studio shows as sliders (move one and the picture follows) */
export const TUNE = { liveTall: 1.72, liveTol: 0.16, liveSpill: 0.8, walkSpeed: 1.1, walkStep: 0.6, giveInto: 0.2, takeInto: 0.2, holdInto: 0.3, holdFlex: 0.08, pinchInto: -1.2, pinchFlex: 1 };
export const S = {};
const PI = Math.PI, XV = new V3(1, 0, 0), _x = new V3(), _y = new V3(), _z = new V3(), _m = new THREE.Matrix4();
function setL(L, c, ax, nrm) { _x.copy(ax).normalize(); _z.copy(nrm).addScaledVector(_x, -nrm.dot(_x)).normalize(); _y.crossVectors(_z, _x); _m.makeBasis(_x, _y, _z); L.quaternion.setFromRotationMatrix(_m); L.position.copy(c); L.updateMatrixWorld(true); }
function room(E) { E.scene.add(new THREE.HemisphereLight('#fff4e0', '#6a5a4a', 1.1)); const d = new THREE.DirectionalLight('#fff0dc', 2.4); d.position.set(1.2, 2.6, 2.0); d.castShadow = true; d.shadow.mapSize.set(2048, 2048); d.shadow.camera.left = -1.5; d.shadow.camera.right = 1.5; d.shadow.camera.top = 2.2; d.shadow.camera.bottom = -0.2; E.scene.add(d); const r = new THREE.DirectionalLight('#9ab4ff', 1.1); r.position.set(-1.4, 1.8, -1.6); E.scene.add(r);
  const fl = new THREE.Mesh(new THREE.CircleGeometry(3, 64), new THREE.MeshStandardMaterial({ color: '#7d7468', roughness: 0.9 })); fl.rotation.x = -PI / 2; fl.receiveShadow = true; E.scene.add(fl); const bw = new THREE.Mesh(new THREE.CylinderGeometry(3, 3, 4, 48, 1, true), new THREE.MeshStandardMaterial({ color: '#a89a86', roughness: 1, side: THREE.BackSide })); bw.position.y = 2; E.scene.add(bw); }
function card(text = 'A LETTER', w = 0.17, h = 0.095) { const t = K.canvasTex(512, 286, (g, W, H) => { g.fillStyle = '#e4d3a6'; g.fillRect(0, 0, W, H); g.strokeStyle = 'rgba(90,60,30,.35)'; g.lineWidth = 6; g.strokeRect(10, 10, W - 20, H - 20); g.fillStyle = '#2a2f55'; g.textAlign = 'center'; g.font = 'bold 54px PoppinsB'; g.fillText(text, W / 2, H * 0.58); }); const b = new THREE.MeshStandardMaterial({ color: '#cdb98a', roughness: 0.9 });
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, 0.003), [b, b, b, b, new THREE.MeshStandardMaterial({ map: t, roughness: 0.85 }), b]); m.castShadow = true; m.userData = { W: w, H: h }; return m; }
const idleArm = restArm;
function face(p, T, o = {}) { Object.assign(p.face, { blink: Math.max(o.nb ? 0 : blinkAt(T, o.seed ?? 2), o.blink || 0), lidL: o.lid ?? 0.1, lidR: o.lid ?? 0.1, squint: 0, browL: o.brow ?? 0.1, browR: o.brow ?? 0.1, knit: 0, sad: o.sad || 0, jaw: o.jaw || 0, wide: 0, round: 0, smile: o.smile ?? 0.2, puff: 0, press: 0 }); p.faceUpdate(T); }
const eyes = p => { p.head.updateWorldMatrix(true, false); return p.head.localToWorld(new V3(0, 0.11, 0.12)); };
const look = { exp: 0.9, grade: [1, 1, 1.06, 0.2], vig: 0.25, bloom: 0.03 };
const person = who => async E => { await K.init(E); room(E); const p = who === 'maa' ? makeMaa(E) : makeSon(E); if (p.bagOff) p.bagOff(); refineHands(p, { k: who === 'maa' ? 0.9 : 1 }); return { p }; };

// a person, turning before the lens: breathing, weight that shifts, hands that live, eyes that look about and blink
const turn = who => ({ dur: 8, about: `${who === 'maa' ? 'Maa' : 'Vikas'} on a turntable: the rig alive at rest — breath, a shifting weight, loose hands, eyes that look and blink. makeMaa / makeSon (people.js) + refineHands (motion.js)`, build: person(who),
  frame(t, c) { const { p } = c, a = t / 8 * 2 * PI; stand(p, 0, 0, a, t, { weight: idleWeight(t, who === 'maa' ? 5 : 2) }); idleArm(p, 'l', t); idleArm(p, 'r', t); const cam = new V3(0, 1.3 * p.size, 2.2); p.look(cam.clone().add(saccade(t, 3, 0.25)), 0.55); face(p, t, { smile: 0.25 }); if (p.sleeveTick) p.sleeveTick();
    return { p: [0, 1.05 * p.size, 2.9], t: [0, 0.92 * p.size, 0], fov: 34, focus: 2.9, dof: 0, ...look }; } });
S.vikas = turn('son'); S.maa = turn('maa');

// ---- the whole body (body.js) ----------------------------------------------------------------------------------------------
const both = async E => { await K.init(E); room(E); const maa = makeMaa(E), son = makeSon(E); if (son.bagOff) son.bagOff(); refineHands(maa, { k: 0.9 }); refineHands(son); return { maa, son }; };
const label = (text, sub) => (g, W, H) => { g.save(); g.textAlign = 'center'; g.fillStyle = 'rgba(0,0,0,.45)'; g.fillRect(0, H * 0.035, W, H * (sub ? 0.095 : 0.065)); g.font = `600 ${Math.round(W * 0.05)}px PoppinsB, sans-serif`; g.fillStyle = '#ffd27a'; g.fillText(text, W / 2, H * 0.08); if (sub) { g.font = `500 ${Math.round(W * 0.03)}px PoppinsB, sans-serif`; g.fillStyle = '#e8e2d6'; g.fillText(sub, W / 2, H * 0.113); } g.restore(); };   /* at the top: the feet are the point */
const marks = (E, n = 14) => { const out = []; for (let i = 0; i < n; i++) { const m = new THREE.Mesh(new THREE.RingGeometry(0.05, 0.075, 32), new THREE.MeshBasicMaterial({ color: '#ffd27a', transparent: true, opacity: 0.85, depthWrite: false })); m.rotation.x = -PI / 2; m.visible = false; E.scene.add(m); out.push(m); } return out; };
const showMarks = (ms, plan, t) => ms.forEach((m, i) => { const k = plan.footfalls[i]; m.visible = !!k && t >= k.t; if (k) m.position.set(k.p[0], 0.004, k.p[2]); });

// a walk: from a stand, along a curve, to a stand. Every ring on the floor is a footfall — watch the foot stay on it.
S.walk = { dur: 6.5, about: 'a walk from a stand to a stand along a curve: planWalk + stepper (body.js). The weight goes over one foot before the other lifts, the heel lands first and the foot rolls, the pelvis rides over the standing leg, the chest turns against the hips, each arm swings against its own leg. The rings are the footfalls: a planted foot does not slide. Sliders: walkSpeed, walkStep', build: async E => { const c = await person('son')(E); c.marks = marks(E); return c; },
  frame(t, c) { const { p } = c, key = TUNE.walkSpeed + ':' + TUNE.walkStep; if (c.key !== key) { c.key = key; c.plan = planWalk([[-0.9, -1.75], [-0.75, -0.9], [-0.3, 0.0], [0.1, 0.75]], { size: p.size, speed: TUNE.walkSpeed, step: TUNE.walkStep, t0: 0.7 }); }
    const r = stepper(p, c.plan, t); showMarks(c.marks, c.plan, t); face(p, t, { smile: 0.2 }); if (p.sleeveTick) p.sleeveTick();
    const g = r.ground || r.pelvis;   /* the lens pans with the PATH (smooth), not with the pelvis (which rides up and down) */
    return { p: [2.55, 0.95, -0.2], t: [g.x * 0.85, 0.74, g.z * 0.85 - 0.05], fov: 42, focus: 2.9, dof: 0, ...look, hud: label('walk', `${c.plan.steps.length} steps · ${(c.plan.t1 - c.plan.t0).toFixed(1)} s · ${r.strike ? 'footfall ' + r.strike.toUpperCase() : ' '}`) }; } };

// a turn on the spot: stepped round, never pivoted
S.turn = { dur: 7, about: 'turning on the spot: planTurn + stepper (body.js). The foot on the side of the turn opens out first, the other comes round to it; the weight is over the standing foot each time. A quarter turn is one pair of steps, a half turn two', build: async E => { const c = await person('son')(E); c.marks = marks(E, 8); const z = c.p.size; c.a = planTurn([0, 0], 0.2, 0.2 + PI / 2, { size: z, t0: 0.8 }); c.b = planTurn([0, 0], 0.2 + PI / 2, 0.2 - PI / 2, { size: z, t0: 3.4 }); return c; },
  frame(t, c) { const { p } = c, plan = t < 3.1 ? c.a : c.b; stepper(p, plan, t, { look: false }); showMarks(c.marks, plan, t); const d = p.fwd(); p.look(wp0(p).addScaledVector(d, 3), 0.8); face(p, t, { smile: 0.22 }); if (p.sleeveTick) p.sleeveTick();
    return { p: [0.6, 1.15, 2.85], t: [0, 0.74 * p.size, 0], fov: 37, focus: 2.9, dof: 0, ...look, hud: label('turn', t < 3.1 ? 'a quarter turn: two steps' : 'a half turn: four steps') }; } };
const wp0 = p => { p.head.updateWorldMatrix(true, false); return p.head.localToWorld(new V3(0, 0.1, 0)); };

// sitting down and standing up
const SEAT = { x: 0, z: 0, yaw: -0.15, h: 0.45 };   /* seen three-quarter from the side: the hips going back is the point */
S.sit = { dur: 7.5, about: 'sitting down and standing up: sit (body.js). Going down, the hips go back as the chest comes forward, so the weight stays over the feet; the hands come to the thighs. Getting up, the chest goes forward first (nose over toes), then the hips leave the seat. The feet never move', build: async E => { const c = await person('son')(E); const wood = new THREE.MeshStandardMaterial({ color: '#6b4a2e', roughness: 0.7 }); const st = new THREE.Group(); const top = new THREE.Mesh(new THREE.CylinderGeometry(0.19, 0.19, 0.04, 32), wood); top.position.y = SEAT.h - 0.02; top.castShadow = top.receiveShadow = true; st.add(top);
    for (let i = 0; i < 4; i++) { const a = PI / 4 + i * PI / 2, lg = new THREE.Mesh(new THREE.CylinderGeometry(0.016, 0.02, SEAT.h - 0.04, 10), wood); lg.position.set(Math.cos(a) * 0.14, (SEAT.h - 0.04) / 2, Math.sin(a) * 0.14); lg.castShadow = true; st.add(lg); } st.position.set(SEAT.x, 0, SEAT.z); E.scene.add(st); return c; },
  frame(t, c) { const { p } = c, up = t >= 4.3, k = up ? seg(t, 4.5, 6.6) : seg(t, 0.7, 2.9); const r = sit(p, SEAT, k, t, { up }); const ahead = new V3(SEAT.x, 1.3, SEAT.z).addScaledVector(p.fwd(), 3), dn = Math.sin(PI * k); p.look(ahead.setY(1.3 - 1.1 * dn), 0.6);   /* the eyes go to where the body is going: down at the floor ahead while it folds, level again at each end */ face(p, t, { smile: 0.2 }); if (p.sleeveTick) p.sleeveTick();
    return { p: [2.35, 1.0, 1.75], t: [-0.03, 0.74, 0.2], fov: 42, focus: 2.9, dof: 0, ...look, hud: label(up ? 'stand up' : 'sit down', up ? 'chest forward first, then the hips rise' : 'hips back as the chest comes forward') }; } };

// hands that talk
const GEST = [['beat', 'r', 0.4, 2.0, 'a small open hand that marks the words'], ['offer', 'r', 2.2, 3.6, 'an open palm held out: "you see"'], ['chest', 'r', 3.8, 5.2, 'the palm to the chest: "I", "mine"'], ['point', 'r', 5.4, 6.7, 'the finger continues the forearm'], ['stop', 'l', 6.9, 8.1, 'the palm raised, facing out'], ['namaste', 'b', 8.3, 9.8, 'palms together, a small bow']];
S.gesture = { dur: 10.2, about: 'hands that talk: gesture and namaste (body.js). Each rises from the arm at rest on an arc, holds, and goes back; the fingers are never a flat paddle. beat marks a word (hit), offer holds a palm out, chest is "I", point continues the forearm, stop raises the palm', build: person('son'),
  frame(t, c) { const { p } = c; stand(p, 0, 0, 0.25, t, { weight: 0.5 * idleWeight(t, 4) }); idleArm(p, 'l', t); idleArm(p, 'r', t); const cam = new V3(0.5, 1.35, 2.2); p.look(cam.clone().add(saccade(t, 5, 0.03)), 0.75); let now = null;
    for (const [kind, s, a, b, say] of GEST) { const k = sm2(seg(t, a, a + 0.38)) * (1 - sm2(seg(t, b - 0.42, b))); if (k <= 0) continue; now = [kind, say]; if (kind === 'namaste') namaste(p, k, t); else gesture(p, s, kind, k, t, { hit: kind === 'beat' ? Math.pow(Math.max(0, Math.sin((t - a) * 7.5)), 3) : 0, at: new V3(-0.7, 1.2, 2.2) }); }
    face(p, t, { smile: 0.22, jaw: now ? 0.06 + 0.12 * Math.abs(Math.sin(t * 9)) : 0, brow: now ? 0.25 : 0.1 }); if (p.sleeveTick) p.sleeveTick();
    return { p: [0.75, 1.22, 2.75], t: [-0.06, 1.06, 0.1], fov: 36, focus: 2.8, dof: 0, ...look, hud: label(now ? now[0] : '·', now ? now[1] : '') }; } };

// a card handed from one person to the other
S.give = { dur: 6.5, about: 'a hand-over: passSheet (body.js) on a card the FILM moves. Maa holds it out, the hand of Vikas comes in already open to its own edge and closes, both hold it for a beat, her fingers open and her hand goes back, he brings it in. Side-on, so both hands read', build: async E => { const c = await both(E); c.card = card('FOR YOU'); E.scene.add(c.card); c.rep = {}; return c; },
  frame(t, c) { const { maa, son, card: L } = c, k = seg(t, 1.5, 4.7), reach = sm2(seg(t, 0.4, 1.9)) * (1 - sm2(seg(t, 4.0, 5.4))), took = sm2(seg(t, 3.9, 5.5));
    stand(maa, -0.47, 0, PI / 2, t, { lean: 0.03 + 0.09 * reach, weight: 0.2 }); stand(son, 0.47, 0, -PI / 2, t + 2, { lean: 0.03 + 0.08 * sm2(seg(t, 1.6, 2.8)) * (1 - took), weight: -0.2 });
    for (const q of [maa, son]) { idleArm(q, 'l', t); idleArm(q, 'r', t); }
    const W = L.userData.W, ZC = -0.19, C0 = new V3(-0.47 + 0.24, 0.97, ZC), C1 = new V3(0, 1.1, ZC), C2 = new V3(0.47 - 0.25, 1.06, ZC)   /* the card travels in the plane of the two shoulders that hold it (their far ones), so each forearm runs straight to it */, u = sm2(seg(t, 0.4, 1.9)); const C = C0.clone().lerp(C1, u); C.y += 0.03 * Math.sin(PI * u); C.lerp(C2, took); C.y += 0.02 * Math.sin(PI * took);
    setL(L, C, XV, new V3(0, 0.12 - 0.1 * u + 0.1 * took, 1));
    const r = passSheet(maa, 'l', son, 'r', L, k, { give: { at: [-W / 2 + 0.022, 0.004], into: TUNE.giveInto - 0.25 * (1 - u), flex: 0.06, pole: maa.left().multiplyScalar(0.5).addScaledVector(maa.fwd(), -0.3).add(new V3(0, -0.9, 0)) }, take: { at: [W / 2 - 0.022, 0.004], into: PI - TUNE.takeInto + 0.25 * took, flex: 0.06, pole: son.left().multiplyScalar(-0.5).addScaledVector(son.fwd(), -0.3).add(new V3(0, -0.9, 0)) } });   /* each elbow's pole in its owner's frame: out to that side, down, a little back */
    if (k < 0.62) c.rep.give = { ...r.give, t }; if (k > 0.56) c.rep.take = { ...r.take, t };
    maa.look(t < 2.2 ? C : eyes(son), 0.6); son.look(t < 4.6 ? C : eyes(maa), 0.6); face(maa, t, { smile: 0.35, seed: 3 }); face(son, t, { smile: 0.25 + 0.2 * took, seed: 5 }); for (const q of [maa, son]) if (q.sleeveTick) q.sleeveTick();
    return { p: [0.25, 1.15, 3.1], t: [0, 0.93, -0.1], fov: 40, focus: 3.1, dof: 0, ...look, hud: label('give', r.held === 'both' ? 'both hold it for a beat' : r.held === 'giver' ? 'she holds it out; his hand comes in open' : 'her fingers open; he brings it in') }; } };

// captured motion: a person filmed, played on the rig
S.mocap = { dur: 8, about: 'captured motion on the rig: clipOf + playClip (body.js) play a clip that tools/mocap/video2clip.py made from a video of a person. This one is the owner\'s own test take (shadow boxing, filmed waist up): arms, spine and head follow the video; the legs were out of frame, so they are not guessed and the rig stands. Film the whole body and the legs follow too',
  build: async E => { const c = await person('son')(E); try { c.clip = clipOf(await (await fetch(new URL('mocap_boxing.json', import.meta.url))).json()); } catch (e) { c.clip = null; } return c; },
  frame(t, c) { const { p } = c; if (c.clip) playClip(p, c.clip, t, { at: [0, 0], yaw: 0.1, fist: 0.9 }); else { stand(p, 0, 0, 0.1, t); idleArm(p, 'l', t); idleArm(p, 'r', t); p.look(new V3(0.3, 1.3, 2.5), 0.7); }
    face(p, t, { smile: 0.05, brow: 0.2 }); if (p.sleeveTick) p.sleeveTick();
    return { p: [0.45, 1.2, 2.75], t: [0, 1.02, 0], fov: 36, focus: 2.8, dof: 0, ...look, hud: label('captured motion', c.clip ? 'your own take, on the rig (waist up: the legs stand)' : 'no clip found beside the film') }; } };

// live action: a person FILMED before a plain wall, standing in the coded room (live.js). The wall is keyed out on the graphics card; the person
// stands on the floor, turns to the lens as it moves, is hidden by the stool in front, and throws a shadow of their own outline.
// The take here is a stand-in (a coded figure before a green wall). Film yourself the same way, run cine.actor {film, video, name}, and it is you.
S.live = { dur: 7, about: 'live action in the coded world: liveActor (live.js) puts a take filmed before a plain wall into the scene: keyed on the graphics card, on the floor at a mark, behind and in front of things, with a shadow. cine.actor makes a take ready. Sliders: liveTall (how tall the person is, metres), liveTol (how much like the wall is wall), liveSpill',
  build: async E => { await K.init(E); room(E); const me = await liveActor(E, 'live_demo', { at: [0, 0] }); const wood = new THREE.MeshStandardMaterial({ color: '#6b4a2e', roughness: 0.7 }), st = new THREE.Group();
    const top = new THREE.Mesh(new THREE.CylinderGeometry(0.19, 0.19, 0.04, 32), wood); top.position.y = 0.43; top.castShadow = top.receiveShadow = true; st.add(top); for (let i = 0; i < 4; i++) { const a = i * PI / 2 + 0.4, lg = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, 0.43, 10), wood); lg.position.set(0.13 * Math.cos(a), 0.215, 0.13 * Math.sin(a)); lg.castShadow = true; st.add(lg); }
    st.position.set(0.42, 0, 0.55); E.scene.add(st); const ball = new THREE.Mesh(new THREE.SphereGeometry(0.16, 32, 24), new THREE.MeshStandardMaterial({ color: '#c8402c', roughness: 0.5 })); ball.position.set(-0.55, 0.16, -0.7); ball.castShadow = ball.receiveShadow = true; E.scene.add(ball); return { me, key: '' }; },
  frame(t, c) { const a = -0.55 + 1.1 * sm(t / 7), me = c.me; me.play(t % 6); me.group.scale.setScalar(TUNE.liveTall / me.tall); me.card.material.uniforms.tol.value = TUNE.liveTol; me.card.material.uniforms.spill.value = TUNE.liveSpill;
    return { p: [3.5 * Math.sin(a), 1.05, 3.5 * Math.cos(a)], t: [0, 0.9, 0], fov: 36, focus: 3.5, dof: 0, ...look, hud: label('live action', 'a filmed take in the coded room (a stand-in here)') }; } };


// the holds: the same card held flat (fingers behind, thumb in front) and then in a curled pinch — pinchSheet(p, side, sheet, { at, into, flex })
S.hold = { dur: 8, about: 'the holds, close: a card held flat in the left hand (thumb on its front, finger pads on its back), then pinched by its top edge in the right. pinchSheet (motion.js): the hand follows the thing, so it cannot float or slide', build: async E => { const c = await person('son')(E); c.card = card('HOLD'); E.scene.add(c.card); c.rep = {}; return c; },
  frame(t, c) { const { p, card: L } = c; stand(p, 0, 0, 0, t); const f = p.fwd(), l = p.left(); p.sp2.updateWorldMatrix(true, false); const ch = p.sp2.localToWorld(new V3(0, 0.08, 0)); const two = sm2(seg(t, 3.6, 4.6));
    setL(L, ch.clone().addScaledVector(f, 0.3).add(new V3(0, -0.02 + 0.004 * Math.sin(t * 1.7), 0)), l.clone().negate(), f.clone().negate().add(new V3(0, 0.3, 0)));
    if (two < 1) { c.rep.flat = { ...pinchSheet(p, 'l', L, { at: [-L.userData.W / 2 + 0.022, -0.004], into: TUNE.holdInto, flex: TUNE.holdFlex, th: 0.003, pole: l.clone().multiplyScalar(0.5).addScaledVector(f, -0.3).add(new V3(0, -0.9, 0)), open: two }), t }; /* the elbow's pole is given in HIS frame: out to his left, down, a little back */ idleArm(p, 'r', t); }
    else { idleArm(p, 'l', t); c.rep.pinch = { ...pinchSheet(p, 'r', L, { at: [0.03, L.userData.H / 2 - 0.016], into: TUNE.pinchInto, flex: TUNE.pinchFlex, th: 0.003, pole: l.clone().multiplyScalar(-0.7).addScaledVector(f, -0.4).add(new V3(0, -0.5, 0)) }), t }; }
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
