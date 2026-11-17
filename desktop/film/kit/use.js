// USING THINGS: a phone looked at or held to the ear, a sheet of paper read in both hands or held out, a cup, a book,
// a tablet. Two halves, and a film needs both:
//
//  1. THE THING IN THE HAND is drawn inside the body's own depth picture (engine/bodydraw.js `pose.held`), so the
//     fingers close OVER it. Drawn as a separate world item (kit/hold.js `item`, or a film's own slab at the wrist) it
//     lies wholly in front of the hand or wholly behind it — the concept tests of 17 Nov 2026: phones that floated in
//     front of open fingers, a drawing that hung at a child's knee, a tablet nobody could see.
//  2. THE ARMS GO WHERE THE USE PUTS THEM (`usePose`): a phone is read at the chest with the forearm turned palm-up and
//     the head bowed to it; it is not carried at the hip while its owner talks. Wrist targets are in the actor's own
//     frame [right, up, forward] for a body of 1.75 m and scale with its height; the wrist turn is measured on a lab
//     sheet (Practice_Loop/seq/h1.js), not guessed.
//
//   const ph = useThing('phone', { id: 'ph', life: [{ t: 0, by: 'a', hand: 'R' }] });
//   motion steps:  { op: 'stage.overlay', actor: 'a', add: usePose('phone', { t0: 1, t1: 6, height: 1.75 }) }
//   actors:        { id: 'a', …, held: ph.held('a'), hands: ph.hands('a') }
//   dyn:           (ts, ctx) => [ph.item(ts, ctx)]            (drawn only while it lies somewhere, in no hand)
// scene() does all three by itself for a cast member with `uses: [{ thing: 'phone', t0, t1 }]` (kit/director.js).
import { prop, gripFrame, holdShape } from './hold.js';
import { HANDS } from '../engine/body.js';
import { Mesh } from '../engine/scene.js';

const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const lerp3 = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];

/* ---------------------------------------------------------------- shapes as triangles */
/** A box: centre c, unit axes u (length), v (width), n (thickness); sizes in metres. → { pos, idx } */
function boxTris(c, u, v, n, L, Wd, T) {
  const P = []; for (const a of [-1, 1]) for (const b of [-1, 1]) for (const d of [-1, 1]) P.push(add(add(add(c, mul(u, a * L / 2)), mul(v, b * Wd / 2)), mul(n, d * T / 2)));
  const pos = Float32Array.from(P.flat()), F = [[0, 1, 3, 2], [4, 6, 7, 5], [0, 4, 5, 1], [2, 3, 7, 6], [0, 2, 6, 4], [1, 5, 7, 3]], idx = [];
  for (const [a, b, c2, d] of F) idx.push(a, b, c2, a, c2, d);
  return { pos, idx: Uint32Array.from(idx) };
}
/** A flat quad with a little bow (paper): corners from c along u and v. */
function sheetTris(c, u, v, n, L, Wd, bow = 0) {
  const P = [], idx = [], NU = 4, NV = 3;
  for (let i = 0; i <= NU; i++) for (let j = 0; j <= NV; j++) { const a = i / NU - 0.5, b = j / NV - 0.5; P.push(add(add(add(c, mul(u, a * L)), mul(v, b * Wd)), mul(n, bow * (1 - 4 * b * b) * 0.5))); }
  for (let i = 0; i < NU; i++) for (let j = 0; j < NV; j++) { const a = i * (NV + 1) + j, b = a + 1, c2 = a + NV + 1, d = c2 + 1; idx.push(a, c2, b, b, c2, d); }
  return { pos: Float32Array.from(P.flat()), idx: Uint32Array.from(idx) };
}
function cylTris(c, ax, e1, r, h, n = 12, open = false) {
  const e2 = cross(ax, e1), P = [], idx = [];
  for (const y of [-h / 2, h / 2]) for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2; P.push(add(add(c, mul(ax, y)), add(mul(e1, Math.cos(a) * r), mul(e2, Math.sin(a) * r)))); }
  for (let i = 0; i < n; i++) { const a = i, b = (i + 1) % n, c2 = i + n, d = b + n; idx.push(a, c2, b, b, c2, d); }
  const bot = P.length; P.push(add(c, mul(ax, -h / 2))); for (let i = 0; i < n; i++) idx.push(bot, i, (i + 1) % n);
  if (!open) { const top = P.length; P.push(add(c, mul(ax, h / 2))); for (let i = 0; i < n; i++) idx.push(top, n + (i + 1) % n, n + i); }
  return { pos: Float32Array.from(P.flat()), idx: Uint32Array.from(idx) };
}
const part = (tris, c, o = {}) => ({ ...tris, c, ...o });

/**
 * THE THINGS. Each knows how it lies in a hand (`inHand(F, k)` from the grip frame of kit/hold.js: c, axis = little
 * finger → index, palm = out of the palm, fwd = towards the fingers) and how it is drawn (`parts(c, u, v, n, k)`).
 * A flat thing lies ON the palm (its back on the hand, its face out of the palm); a cup or a stem goes through the fist.
 */
export const THINGS = {
  phone: { r: 0.008, style: 'edge', size: [0.15, 0.072, 0.009], color: '#20232a', face: '#5ab0ff',
    // its middle a little behind the knuckles, its back on the palm, its long side along the fingers
    inHand: (F, k) => ({ c: add(add(F.knuckles, mul(F.palm, 0.017 * k)), mul(F.fwd, -0.035 * k)), u: F.fwd, v: F.axis, n: F.palm }) },
  tablet: { r: 0.01, style: 'support', size: [0.24, 0.17, 0.01], color: '#2a2d34', face: '#7ec4ff', two: true,
    inHand: (F, k) => ({ c: add(add(F.knuckles, mul(F.palm, 0.018 * k)), mul(F.axis, -0.06 * k)), u: F.axis, v: F.fwd, n: F.palm }) },
  book: { r: 0.012, style: 'support', size: [0.22, 0.15, 0.03], color: '#7a2f2a', face: '#efe6d2',
    inHand: (F, k) => ({ c: add(add(F.knuckles, mul(F.palm, 0.03 * k)), mul(F.fwd, -0.02 * k)), u: F.fwd, v: F.axis, n: F.palm }) },
  // a sheet is pinched at its top edge between thumb and the first fingers, and hangs from there
  paper: { r: 0.004, style: 'pinch', size: [0.297, 0.21, 0.001], color: '#f4f1e8', sheet: true, two: true,
    inHand: (F, k) => { const down = norm(sub([0, -1, 0], mul(F.palm, dot([0, -1, 0], F.palm)))); return { c: add(add(F.knuckles, mul(F.fwd, 0.01 * k)), mul(down, 0.13 * k)), u: down, v: norm(cross(F.palm, down)), n: F.palm }; } },
  // a child's crayon drawing: a sheet with a figure on it (a round face, a blue body, a red cape, a sun) on its face side
  drawing: { r: 0.004, style: 'pinch', size: [0.297, 0.21, 0.001], color: '#f6f2e6', sheet: true, two: true, doodle: true,
    inHand: (F, k) => THINGS.paper.inHand(F, k) },
  cup: { r: 0.038, style: 'fist', size: [0.1, 0.038], color: '#e9e3d6', inside: '#5a3418',
    inHand: (F, k) => ({ c: add(F.c, mul(F.axis, 0.02 * k)), u: F.axis, v: F.fwd, n: F.palm }) },
};

/**
 * One thing used by people through a sequence: kit/hold.js `prop` (it lies, is taken, given, set down) drawn in the
 * hands of whoever holds it. o: { id, life (as prop's), color, face, size, make(c, u, v, n, k) → parts (own shape) }.
 * → { held(actor) → (ts, ctx, pose) => parts | null, hands(actor), item(ts, ctx) (only while it lies), where, prop }
 */
export function useThing(kind, o = {}) {
  const K = { ...(THINGS[kind] ?? THINGS.phone), ...o }, life = o.life.map((S) => (S.by ? { style: K.style, ...S } : S));
  const P = prop({ id: o.id ?? kind, r: K.r, at: 0, life });
  const shape = (c, u, v, n, k) => {
    if (K.make) return K.make(c, u, v, n, k);
    const [L, Wd, T] = K.size.map((x) => x * (K.scale ?? 1));
    if (kind === 'cup') return [part(cylTris(c, u, v, Wd, L, 14, true), K.color, { shade: -0.25 }), part(cylTris(add(c, mul(u, L * 0.3)), u, v, Wd * 0.92, 0.002, 14), K.inside)];
    if (K.sheet) { const out = [part(sheetTris(c, u, v, n, L, Wd, 0.012), K.color, { shade: -0.18 })]; if (K.doodle) out.push(...doodle(c, u, v, n, L, Wd)); return out; }
    if (K.two && kind === 'tablet') return [part(boxTris(c, u, v, n, Wd, L, T), K.color, { shade: -0.35 }), part(sheetTris(add(c, mul(n, T / 2 + 0.0012)), u, v, n, Wd * 0.88, L * 0.9, 0), K.face, { glow: true, two: false })];
    // a slab with its face a little proud of it (the screen, the pages)
    return [part(boxTris(c, u, v, n, L, Wd, T), K.color, { shade: -0.35, hi: '#6a7080' }), part(sheetTris(add(c, mul(n, T / 2 + 0.0012)), u, v, n, L * 0.9, Wd * 0.88, 0), K.face, { glow: kind === 'phone' || kind === 'tablet', two: false })];
  };
  const frameOf = (pose, hand) => { const F = gripFrame(pose, hand, { r: K.r }); if (!F) return null; const j = pose.j; F.knuckles = lerp3(j[`finger2-1.${hand}`], j[`finger5-1.${hand}`], 0.5); return F; };
  const placed = (pose, hand, out = false) => {
    const F = frameOf(pose, hand); if (!F) return null; const k = (pose.height ?? 1.75) / 1.8;
    // held in BOTH hands (a sheet read, a tablet): it lies between them, its face turned to the eyes
    if (K.two) { const G = frameOf(pose, hand === 'R' ? 'L' : 'R'); if (G && Math.hypot(...sub(G.knuckles, F.knuckles)) < 0.5 * k) {
      const A = F.knuckles, B = G.knuckles; let v = norm(sub(B, A)); const eyes = lerp3(pose.j['eye.L'] ?? A, pose.j['eye.R'] ?? B, 0.5), mid = lerp3(A, B, 0.5);
      let n = sub(eyes, mid); n = norm(sub(n, mul(v, dot(n, v)))); if (out) n = mul(n, -1); let u = norm(cross(v, n));
      // a held sheet is upright whichever way round the hands are: its top (−u) up
      if (u[1] > 0) { u = mul(u, -1); v = mul(v, -1); }
      // paper is pinched at its side edges a little above the middle; a tablet rests on the fingers, its lower half in the hands
      const c = K.sheet ? add(add(mid, mul(u, -0.04 * k)), mul(n, 0.012 * k)) : add(add(mid, mul(u, 0.03 * k)), mul(n, 0.02 * k));
      return { c, u, v, n, k, two: true, span: Math.hypot(...sub(B, A)) };
    } }
    return { ...K.inHand(F, k), k };
  };
  /** where it is and how it lies at ts: in a hand (or between two in a hand-off), or where it was put down */
  const at = (ts, ctx) => {
    const w = P.where(ts, ctx); if (!w.owner) return { free: true, w };
    const i = life.reduce((q, S, n) => (S.t <= ts ? n : q), 0), S = life[i], Pv = life[i - 1];
    const now = placed(ctx.poses[S.by], S.hand ?? 'R', !!S.out); if (!now) return { free: true, w };
    if (Pv?.by && ts < S.t + (S.over ?? 0.45)) { // a hand-off: from the giver's hand into the taker's, only once both hold it
      const was = placed(ctx.poses[Pv.by], Pv.hand ?? 'R', !!Pv.out), u = Math.max(0, Math.min(1, ((ts - S.t) / (S.over ?? 0.45) - 0.45) / 0.2)), e = u * u * (3 - 2 * u);
      if (was) return { owner: e < 0.5 ? Pv.by : S.by, c: lerp3(was.c, now.c, e), u: norm(lerp3(was.u, now.u, e)), v: norm(lerp3(was.v, now.v, e)), n: norm(lerp3(was.n, now.n, e)), k: now.k };
    }
    return { owner: S.by, ...now };
  };
  return {
    prop: P, where: P.where,
    hands: (actor) => (ts) => { const h = P.hands(actor)(ts); if (K.two) { if (h.R && !h.L) h.L = h.R; else if (h.L && !h.R) h.R = h.L; } return h; },
    held: (actor) => (ts, ctx) => { const a = at(ts, ctx); return a.free || a.owner !== actor ? null : shape(a.c, a.u, a.v, a.n, a.k); },
    item: (ts, ctx) => {
      const a = at(ts, ctx); if (!a.free) return null;
      const d = a.w.dir, side = Math.abs(d[1]) > 0.9 ? [1, 0, 0] : norm(cross(d, [0, 1, 0])), n = norm(cross(side, d));
      const parts = shape(a.w.grip, d, side, n, 1), faces = [];
      for (const pt of parts) for (let t = 0; t < pt.idx.length; t += 3) { const q = [0, 1, 2].map((m) => [pt.pos[pt.idx[t + m] * 3], pt.pos[pt.idx[t + m] * 3 + 1], pt.pos[pt.idx[t + m] * 3 + 2]]); faces.push({ p: q, c: pt.c }); }
      return new Mesh(faces, { p: a.w.grip, bias: 0 });
    },
  };
}
/** The crayon picture on a sheet (u runs down the sheet, v across, n out of its face): flat shapes just proud of its bow. */
function doodle(c, u, v, n, L, Wd) {
  const o = add(c, mul(n, 0.009)), P = (a, b) => add(add(o, mul(u, a * L)), mul(v, b * Wd)), out = [];
  const poly = (pts, col) => { const pos = pts.flatMap((q) => P(q[0], q[1])), idx = []; for (let i = 1; i < pts.length - 1; i++) idx.push(0, i, i + 1); out.push({ pos: Float32Array.from(pos), idx: Uint32Array.from(idx), c: col, two: true, shade: -0.1 }); };
  const circ = (a, b, r, col, n2 = 10) => poly(Array.from({ length: n2 }, (_, i) => [a + Math.cos((i / n2) * 6.283) * r, b + Math.sin((i / n2) * 6.283) * r * (L / Wd)]), col);
  circ(-0.36, 0.3, 0.07, '#f2c230');                                                      // the sun in a corner
  poly([[-0.05, -0.22], [0.3, -0.3], [0.32, 0.3], [-0.05, 0.22]], '#d8382f');               // the cape
  poly([[-0.04, -0.1], [0.24, -0.12], [0.24, 0.12], [-0.04, 0.1]], '#2f63c8');              // the body
  circ(-0.14, 0, 0.085, '#e8b48a');                                                       // the face
  poly([[0.24, -0.1], [0.4, -0.12], [0.4, -0.05], [0.24, -0.03]], '#2f63c8'); poly([[0.24, 0.03], [0.4, 0.05], [0.4, 0.12], [0.24, 0.1]], '#2f63c8'); // the legs
  poly([[-0.24, -0.08], [-0.22, -0.11], [-0.2, -0.06], [-0.22, -0.03]], '#2a1e16'); poly([[-0.24, 0.08], [-0.22, 0.11], [-0.2, 0.06], [-0.22, 0.03]], '#2a1e16'); // tired eyebrows
  return out;
}
/** The hand round a flat thing in the palm: fingers bent over its far edge, the thumb along its near side. */
export function cradleShape() { return holdShape('edge'); }
void HANDS;

/**
 * HOW A THING IS USED: the arms (wrist targets for a body 1.75 m tall, in its own frame [right, up, forward]), the
 * turn of the wrist (degrees, measured on the lab sheet), and where the eyes go. Mirrored for the left hand.
 */
export const USES = {
  // reading a phone held in the right hand: at the chest, palm up, the head bowed to it
  phone: { R: [0.03, 1.22, 0.34], wristR: [-75, -15, 0], look: 'thing', head: 0.85 }, // (wrist turn: the best of 36 on lab sheet h3 — palm to the eyes 0.81, fingers pointing away and up)
  // the phone at the ear: the hand up beside the head, the elbow out
  'phone-ear': { R: [0.13, 1.5, 0.02], wristR: [0, -40, 0], look: null },
  // a sheet read in both hands, at the chest
  read: { R: [0.12, 1.27, 0.34], L: [-0.12, 1.27, 0.34], span: 0.13, wristR: [0, 60, 0], wristL: [0, -60, 0], look: 'thing', head: 0.7 },
  // held out to someone in both hands, its face turned to them (a child showing a drawing)
  present: { R: [0.1, 1.3, 0.45], L: [-0.1, 1.3, 0.45], span: 0.13, wristR: [0, 60, 0], wristL: [0, -60, 0], look: 'to', head: 0.5, out: true },
  // held out to someone (a man offering a cup): one hand forward at the chest
  show: { R: [0.06, 1.12, 0.46], wristR: [0, 40, 0], look: 'to', head: 0.6 },
  // a tablet in both hands, lower, the head bowed over it
  tablet: { R: [0.13, 1.12, 0.3], L: [-0.13, 1.12, 0.3], span: 0.14, wristR: [0, 85, 0], wristL: [0, -85, 0], look: 'thing', head: 0.9 },
  // a cup held at the chest, and lifted to the mouth
  cup: { R: [0.1, 1.2, 0.2], wristR: [0, 10, 0], look: null },
  sip: { R: [0.04, 1.47, 0.14], wristR: [-30, 20, 0], look: null },
};
/**
 * The overlays that put a body into a use from t0 to t1 (they ease in and out over `fade`, so the hand travels).
 * o: { t0, t1, fade, height (m), hand 'R' | 'L', to: what 'look: to' looks at (an actor id or a point), at: [x, y, z]
 * where the body stands and yaw (deg) for a 'look: thing' point }.
 */
export function usePose(kind, o) {
  const U = USES[kind]; if (!U) throw new Error(`no use "${kind}" (${Object.keys(USES).join(', ')})`);
  const s = (o.height ?? 1.75) / 1.75, fade = o.fade ?? 0.45, mir = o.hand === 'L';
  const side = (v) => (mir ? [-v[0], v[1], v[2]] : v), turn = (r) => (mir ? [r[0], -r[1], -r[2]] : r);
  // with the actor named, the hands are placed from his own chest (spine01, 1.36 m up and 5 cm back on a standing body
  // of 1.75 m): the use holds when he kneels, sits or leans. Without, from his feet (a standing body only).
  // With the actor named, each hand REACHES for a point set from his own chest (spine01, 1.36 m up and 5 cm back on a
  // standing body of 1.75 m) and turned with his body: the use holds when he kneels, sits or leans. (A key placed in the
  // chest's own frame went wrong when the chest stood off to the side of the hips, as it does down on one knee.)
  // Without the actor, from his feet (a standing body only).
  const rel = !!o.actor, tracks = [], reaches = [];
  for (const h of ['R', 'L']) {
    const p = U[h]; if (!p) continue; const hh = mir ? (h === 'R' ? 'L' : 'R') : h, v0 = side(p);
    // (both hands on one thing hold it at its edges: their spacing is the thing's, not the body's — a child's hands too)
    const v = U.span ? [Math.sign(v0[0]) * U.span / s, v0[1], v0[2]] : v0;
    if (rel) reaches.push({ t0: o.t0, t1: o.t1, fade, reach: { actor: o.actor, bone: 'spine01', offset: [-v[0] * s, (v[1] - 1.36) * s, (v[2] + 0.05) * s] }, side: hh });
    else tracks.push({ on: `hand.${hh}`, keys: [[0, v.map((x) => x * s)]] });
    const w = U[`wrist${h}`]; if (w) tracks.push({ on: `wrist.${hh}`, keys: [[0, turn(w)]] });
  }
  const out = [...reaches, { t0: o.t0, t1: o.t1, fade, keys: tracks }], main = U.R ? (mir ? 'L' : 'R') : (mir ? 'R' : 'L');
  if (U.look === 'thing' && rel) out.push({ t0: o.t0, t1: o.t1, fade, look: { actor: o.actor, bone: `wrist.${main}` }, head: U.head ?? 0.8 });
  else if (U.look === 'thing' && o.at) { const p = side(U.R ?? U.L), y = ((o.yaw ?? 0) * Math.PI) / 180, c = Math.cos(y), sn = Math.sin(y); const w = [o.at[0] - p[0] * s * c + p[2] * s * sn, p[1] * s, o.at[2] + p[0] * s * sn + p[2] * s * c]; out.push({ t0: o.t0, t1: o.t1, fade, look: w, head: U.head ?? 0.8 }); }
  if (U.look === 'to' && o.to) out.push({ t0: o.t0, t1: o.t1, fade, look: o.to, head: U.head ?? 0.6 });
  return out;
}
/**
 * Taking a thing from someone's hands: the taker's hand goes to it (a reach to the giver's hand, a little above and
 * in front of it), arrives just before `t` (the moment of the hand-off in the thing's life), and stays there `hold`
 * seconds while both hold it. → overlays for the taker.
 */
export function takeFrom(giver, t, o = {}) {
  const side = o.hand ?? 'R', from = o.from ?? 'R', go = o.reach ?? 0.8;
  return [{ t0: t - go, t1: t + (o.hold ?? 0.5), fade: o.fade ?? 0.5, reach: { actor: giver, bone: `wrist.${from}`, offset: o.offset ?? [0, 0.07, 0.05] }, side },
    { t0: t - go - 0.2, t1: t + (o.hold ?? 0.5) + 0.3, fade: 0.3, look: { actor: giver, bone: `wrist.${from}` }, head: 0.6 }];
}
