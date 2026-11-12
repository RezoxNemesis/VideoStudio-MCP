// Cut-out characters: original people as rigs of separate parts (head, hair, eyes, mouths, torso, arms, hands, legs,
// feet) with pivots, so they can be posed, walked and lip-synced like a paper or After Effects puppet. Two builds:
// anime (about six heads tall) and chibi (two and a half). Every part shares the character's own picture space.
import { Doc, rng, P, open, smooth, ellipse, rrect, capsule, shade, mix, INK } from './svg.js';

const SKIN = ['#f6d7bf', '#f1c6a2', '#e3ad86', '#c98d62', '#9a6844', '#6e4a32'];
const HAIR = { black: '#23202a', brown: '#5a3a28', chestnut: '#7a4428', blond: '#e8c46a', silver: '#cfd3dc', red: '#b0362e', navy: '#2a3a6a', pink: '#f08aae', green: '#3a7a5a', white: '#f2f2f4' };
const EYE = { brown: '#7a4a2a', blue: '#3a7ad9', green: '#3a9a5a', amber: '#d98a1a', violet: '#7a4ad9', red: '#c0303a', grey: '#7a8290' };
/** cel shading: light from the upper left, a hard shadow edge */
const cel = (d, c, x1 = 0.1, y1 = 0.05, x2 = 0.95, y2 = 0.95) => d.lin([[0, shade(c, 0.1)], [0.52, c], [0.53, shade(c, -0.2)], [1, shade(c, -0.26)]], x1, y1, x2, y2);
function joints(build, fem) {
  if (build === 'chibi') return { top: 70, chin: 500, eyeY: 360, neck: [300, 505], sh: [[238, 560], [362, 560]], el: [[222, 652], [378, 652]], wr: [[218, 742], [382, 742]], hip: [300, 772], hp: [[270, 786], [330, 786]], kn: [[268, 900], [332, 900]], an: [[268, 1008], [332, 1008]], toe: 1050, headW: 230, w: 600, h: 1100, waist: 744, sw: 62, ww: 50, hw: 52, nw: 22, chibi: true };
  // six and a half heads: the head 167 tall; elbows at the waist, wrists at the crotch, fingertips at mid thigh
  const k = fem ? 0.88 : 1;
  return { top: 60, chin: 228, eyeY: 162, neck: [300, 230], sh: [[300 - 92 * k, 290], [300 + 92 * k, 290]], el: [[300 - 104 * k, 480], [300 + 104 * k, 480]], wr: [[300 - 110 * k, 640], [300 + 110 * k, 640]], hip: [300, 600], hp: [[300 - (fem ? 44 : 46), 606], [300 + (fem ? 44 : 46), 606]], kn: [[300 - 48, 862], [300 + 48, 862]], an: [[300 - 50, 1102], [300 + 50, 1102]], toe: 1148, headW: 74, w: 600, h: 1200, waist: fem ? 470 : 480, sw: 92 * k, ww: fem ? 50 : 62, hw: fem ? 84 : 80, nw: fem ? 15 : 18, fem };
}
function headShape(J, build) {
  const cx = 300, w = J.headW, t = J.top, c = J.chin, m = build === 'chibi' ? 0.93 : 0.78;
  return smooth([[cx, t], [cx + w * 0.78, t + (c - t) * 0.12], [cx + w, t + (c - t) * 0.45], [cx + w * 0.86, t + (c - t) * m], [cx + w * 0.3, c - (c - t) * 0.04], [cx, c], [cx - w * 0.3, c - (c - t) * 0.04], [cx - w * 0.86, t + (c - t) * m], [cx - w, t + (c - t) * 0.45], [cx - w * 0.78, t + (c - t) * 0.12]], true, 0.9);
}
function hairBack(d, J, style, col) {
  const cx = 300, w = J.headW, t = J.top, c = J.chin, hh = c - t;
  if (style === 'long') d.ink(smooth([[cx, t - 14], [cx + w * 1.15, t + hh * 0.3], [cx + w * 1.2, c + hh * 0.9], [cx + w * 0.5, c + hh * 1.1], [cx - w * 0.5, c + hh * 1.1], [cx - w * 1.2, c + hh * 0.9], [cx - w * 1.15, t + hh * 0.3]], true, 0.8), cel(d, col), 4.5);
  else if (style === 'ponytail') { d.ink(smooth([[cx + w * 0.6, t + hh * 0.2], [cx + w * 1.5, t + hh * 0.4], [cx + w * 1.4, c + hh * 0.8], [cx + w * 1.0, c + hh * 0.3], [cx + w * 0.9, t + hh * 0.5]], true, 0.8), cel(d, col), 4.5); d.ink(smooth([[cx, t - 12], [cx + w * 1.08, t + hh * 0.35], [cx + w * 0.9, c - hh * 0.2], [cx - w * 0.9, c - hh * 0.2], [cx - w * 1.08, t + hh * 0.35]], true, 0.8), cel(d, col), 4.5); }
  else if (style === 'bun') { d.ink(ellipse(cx, t - hh * 0.12, w * 0.45, hh * 0.22), cel(d, col), 4.5); d.ink(smooth([[cx, t - 10], [cx + w * 1.08, t + hh * 0.35], [cx + w * 0.92, c - hh * 0.3], [cx - w * 0.92, c - hh * 0.3], [cx - w * 1.08, t + hh * 0.35]], true, 0.8), cel(d, col), 4.5); }
  else if (style !== 'bald' && style !== 'buzz') d.ink(smooth([[cx, t - 16], [cx + w * 1.1, t + hh * 0.3], [cx + w * 1.0, t + hh * 0.75], [cx - w * 1.0, t + hh * 0.75], [cx - w * 1.1, t + hh * 0.3]], true, 0.8), cel(d, col), 4.5);
}
function hairFront(d, J, style, col, seed) {
  const cx = 300, w = J.headW, t = J.top, c = J.chin, hh = c - t, R = rng(seed);
  if (style === 'bald') return;
  if (style === 'buzz') { d.path(smooth([[cx, t + 2], [cx + w * 0.9, t + hh * 0.16], [cx + w * 0.98, t + hh * 0.38], [cx + w * 0.7, t + hh * 0.22], [cx, t + hh * 0.18], [cx - w * 0.7, t + hh * 0.22], [cx - w * 0.98, t + hh * 0.38], [cx - w * 0.9, t + hh * 0.16]], true, 0.8), col, { op: 0.9 }); return; }
  const n = style === 'spiky' ? 7 : style === 'bob' ? 6 : 5, pts = [[cx - w * 1.06, t + hh * 0.5]];
  for (let i = 0; i <= n; i++) { const u = i / n, x = cx - w * 1.0 + u * w * 2.0, tipY = t + hh * (style === 'spiky' ? 0.5 : 0.42) + (R() - 0.5) * hh * 0.12, rootY = t + hh * 0.12; pts.push([x - w * 0.12, rootY + hh * 0.08]); pts.push([x + (style === 'spiky' ? (R() - 0.5) * w * 0.3 : 0), tipY]); }
  pts.push([cx + w * 1.06, t + hh * 0.5]); pts.push([cx + w * 1.1, t + hh * 0.28]); pts.push([cx + w * 0.7, t - hh * 0.05]); pts.push([cx, t - hh * 0.09]); pts.push([cx - w * 0.7, t - hh * 0.05]); pts.push([cx - w * 1.1, t + hh * 0.28]);
  d.ink(P(pts), cel(d, col), 4.5);
  d.path(`M${cx - w * 0.6} ${t + hh * 0.14} Q${cx} ${t + hh * 0.03} ${cx + w * 0.55} ${t + hh * 0.16}`, 'none', { stroke: mix(col, '#ffffff', 0.45), sw: 7, op: 0.7 }); // the sheen band
  if (style === 'long' || style === 'bob') { for (const s of [-1, 1]) d.ink(smooth([[cx + s * w * 0.98, t + hh * 0.3], [cx + s * w * 1.12, t + hh * 0.7], [cx + s * w * 1.05, c + (style === 'long' ? hh * 0.5 : 0)], [cx + s * w * 0.86, t + hh * 0.7]], true, 0.6), cel(d, col), 4); }
}
function eyes(d, J, kind, ec, build) {
  const cx = 300, w = J.headW, y = J.eyeY, ex = w * (build === 'chibi' ? 0.42 : 0.42), ew = w * (build === 'chibi' ? 0.26 : 0.235), eh = ew * (build === 'chibi' ? 1.35 : 1.25);
  const brow = (s, tilt = 0, lift = 0) => d.path(`M${cx + s * (ex - ew * 0.9)} ${y - eh * 1.25 - lift + s * tilt * 0} Q${cx + s * ex} ${y - eh * 1.55 - lift - tilt * 6} ${cx + s * (ex + ew * 1.0)} ${y - eh * 1.3 - lift + tilt * 10}`, 'none', { stroke: INK, sw: 6 });
  for (const s of [-1, 1]) {
    const x = cx + s * ex;
    if (kind === 'closed') { d.path(`M${x - ew} ${y} Q${x} ${y + eh * 0.45} ${x + ew} ${y}`, 'none', { stroke: INK, sw: 6 }); brow(s); continue; }
    if (kind === 'happy') { d.path(`M${x - ew} ${y + eh * 0.2} Q${x} ${y - eh * 0.6} ${x + ew} ${y + eh * 0.2}`, 'none', { stroke: INK, sw: 6.5 }); brow(s, 0, 6); continue; }
    const open = kind === 'half' ? 0.55 : kind === 'surprised' ? 1.15 : 1, irisS = kind === 'surprised' ? 0.55 : 0.82;
    d.ink(smooth([[x - ew, y], [x - ew * 0.7, y - eh * open], [x + ew * 0.7, y - eh * open], [x + ew, y], [x + ew * 0.6, y + eh * 0.55 * open], [x - ew * 0.6, y + eh * 0.55 * open]], true, 0.8), '#ffffff', 3);
    d.path(ellipse(x + s * ew * 0.08, y - eh * 0.2 * open, ew * 0.62 * irisS, eh * 0.78 * open * irisS), d.lin([[0, shade(ec, -0.45)], [0.6, ec], [1, shade(ec, 0.35)]]));
    d.path(ellipse(x + s * ew * 0.08, y - eh * 0.2 * open, ew * 0.26 * irisS, eh * 0.36 * open * irisS), INK);
    d.circle(x - ew * 0.22, y - eh * 0.48 * open, ew * 0.2, '#ffffff'); d.circle(x + ew * 0.2, y + eh * 0.05 * open, ew * 0.09, '#ffffff', { op: 0.85 });
    d.path(`M${x - ew * 1.1} ${y - eh * 0.1} Q${x - ew * 0.2} ${y - eh * (open + 0.2)} ${x + ew * 1.12} ${y - eh * 0.2 * open - eh * 0.25}`, 'none', { stroke: INK, sw: kind === 'half' ? 9 : 7.5 }); // the upper lid, the heavy line
    if (kind === 'angry') { d.path(`M${x - s * ew * 1.1} ${y - eh * 1.5} L${x + s * ew * 1.1} ${y - eh * 1.05}`, 'none', { stroke: INK, sw: 7 }); continue; }
    if (kind === 'sad') { d.path(`M${x - s * ew * 1.0} ${y - eh * 1.15} L${x + s * ew * 1.0} ${y - eh * 1.5}`, 'none', { stroke: INK, sw: 6 }); continue; }
    brow(s, 0, kind === 'surprised' ? 14 : 0);
  }
}
function mouth(d, J, kind, build) {
  const cx = 300, y = J.eyeY + (J.chin - J.eyeY) * (build === 'chibi' ? 0.52 : 0.6), w = J.headW * (build === 'chibi' ? 0.16 : 0.18);
  const inside = '#7a2a34', tongue = '#e86a7a';
  const open = (ow, oh, round = 0.5) => { d.ink(smooth([[cx - ow, y], [cx - ow * round, y - oh * 0.35], [cx + ow * round, y - oh * 0.35], [cx + ow, y], [cx + ow * round, y + oh], [cx - ow * round, y + oh]], true, 0.8), inside, 3.5); d.path(ellipse(cx, y + oh * 0.65, ow * 0.55, oh * 0.28), tongue); };
  switch (kind) {
    case 'rest': d.path(`M${cx - w * 0.6} ${y} Q${cx} ${y + w * 0.12} ${cx + w * 0.6} ${y}`, 'none', { stroke: INK, sw: 4.5 }); break;
    case 'A': open(w * 0.75, w * 1.0); break;
    case 'E': open(w * 0.95, w * 0.45, 0.7); d.path(rrect(cx - w * 0.7, y - w * 0.12, w * 1.4, w * 0.2, 4), '#ffffff'); break;
    case 'I': open(w * 0.8, w * 0.3, 0.8); d.path(rrect(cx - w * 0.6, y - w * 0.08, w * 1.2, w * 0.16, 3), '#ffffff'); break;
    case 'O': open(w * 0.45, w * 0.7, 0.3); break;
    case 'U': open(w * 0.3, w * 0.35, 0.2); break;
    case 'M': d.path(`M${cx - w * 0.55} ${y} L${cx + w * 0.55} ${y}`, 'none', { stroke: INK, sw: 6 }); break;
    case 'F': d.path(`M${cx - w * 0.6} ${y} Q${cx} ${y - w * 0.12} ${cx + w * 0.6} ${y}`, 'none', { stroke: INK, sw: 4.5 }); d.path(rrect(cx - w * 0.35, y - w * 0.02, w * 0.7, w * 0.18, 3), '#ffffff', { stroke: INK, sw: 2 }); break;
    case 'smile': d.path(`M${cx - w * 0.85} ${y - w * 0.2} Q${cx} ${y + w * 0.6} ${cx + w * 0.85} ${y - w * 0.2}`, 'none', { stroke: INK, sw: 5 }); break;
    case 'grin': open(w * 0.95, w * 0.55, 0.85); d.path(rrect(cx - w * 0.8, y - w * 0.1, w * 1.6, w * 0.22, 4), '#ffffff'); break;
    case 'frown': d.path(`M${cx - w * 0.75} ${y + w * 0.25} Q${cx} ${y - w * 0.35} ${cx + w * 0.75} ${y + w * 0.25}`, 'none', { stroke: INK, sw: 5 }); break;
    case 'shout': open(w * 1.0, w * 1.35, 0.6); d.path(rrect(cx - w * 0.8, y - w * 0.18, w * 1.6, w * 0.22, 4), '#ffffff'); break;
  }
}
export const MOUTHS = ['rest', 'A', 'E', 'I', 'O', 'U', 'M', 'F', 'smile', 'grin', 'frown', 'shout'];
export const EYES = ['open', 'half', 'closed', 'happy', 'angry', 'sad', 'surprised'];
/** a limb's outline: from a (radius ra) to b (rb), swelling where muscles are: bulge [u, amount, side] (side 1 = the
 * limb's right as it hangs, -1 the other, 0 both). Returns the closed outline and its two side lines. */
function limbShape(a, b, ra, rb, bulges = [], n = 14) {
  const dx = b[0] - a[0], dy = b[1] - a[1], L = Math.hypot(dx, dy) || 1, ux = dx / L, uy = dy / L, px = -uy, py = ux;
  const rad = (u, side) => { let r = ra + (rb - ra) * u; for (const [bu, amt, sd] of bulges) if (!sd || sd === side) r += amt * Math.exp(-((u - bu) ** 2) / 0.045); return r; };
  const right = [], left = [];
  for (let i = 0; i <= n; i++) { const u = i / n, c = [a[0] + dx * u, a[1] + dy * u]; right.push([c[0] + px * rad(u, 1), c[1] + py * rad(u, 1)]); left.push([c[0] - px * rad(u, -1), c[1] - py * rad(u, -1)]); }
  const capB = [], capA = [];
  for (let i = 1; i < 8; i++) { const t = (i / 8) * Math.PI, cc = Math.cos(t), s2 = Math.sin(t), r = (rad(1, 1) + rad(1, -1)) / 2; capB.push([b[0] + (px * cc + ux * s2) * r, b[1] + (py * cc + uy * s2) * r]); }
  for (let i = 1; i < 8; i++) { const t = (i / 8) * Math.PI, cc = Math.cos(t), s2 = Math.sin(t), r = (rad(0, 1) + rad(0, -1)) / 2; capA.push([a[0] + (-px * cc - ux * s2) * r, a[1] + (-py * cc - uy * s2) * r]); }
  return { all: smooth([...right, ...capB, ...left.slice().reverse(), ...capA], true, 0.9), right, left, capB, ux, uy, px, py };
}
/** which way is the light (upper left) across a limb: +1 if its right side faces it */
const litSide = (sh) => (sh.px * -0.6 + sh.py * -0.8 > 0 ? 1 : -1);
/** a shaded limb: the colour, a shadow along the side away from the light (its edge follows the limb's swell), the outline
 * round the far end and down both sides (the joint end tucks under the part above it, so no seam shows) */
function limb(d, a, b, ra, rb, col, bulges = [], sw = 4, cap = true) {
  const sh = limbShape(a, b, ra, rb, bulges), side = litSide(sh), cl = d.clip(sh.all);
  d.path(sh.all, col);
  const far = side > 0 ? sh.left : sh.right, near = side > 0 ? sh.right : sh.left;
  // the shadow: between the far outline and a line 40 % of the way across, softly widened at the swell
  const mid = far.map((p, i) => [p[0] + (near[i][0] - p[0]) * 0.38, p[1] + (near[i][1] - p[1]) * 0.38]);
  d.path(smooth([...far, ...[[b[0] + sh.ux * 60, b[1] + sh.uy * 60]], ...mid.slice().reverse(), [a[0] - sh.ux * 60, a[1] - sh.uy * 60]], true, 0.8), shade(col, -0.22), { clip: cl });
  d.path(smooth(near.map((p, i) => [p[0] + (far[i][0] - p[0]) * 0.1, p[1] + (far[i][1] - p[1]) * 0.1]), false, 1), 'none', { stroke: shade(col, 0.22), sw: 5, op: 0.55, clip: cl }); // the lit edge
  d.path(open([sh.left[0], ...sh.left.slice(1)]) , 'none', { stroke: INK, sw });
  d.path(open([...sh.right]), 'none', { stroke: INK, sw });
  if (cap) d.path(open([sh.right[sh.right.length - 1], ...sh.capB, sh.left[sh.left.length - 1]]), 'none', { stroke: INK, sw });
  return sh;
}
/** a hand hanging relaxed (seen from the front its thumb side shows): the back of the hand, four fingers that curl, a thumb */
function hand(d, wr, dirIn, S, skin) {
  const x = wr[0], y = wr[1], w = 20 * S, L = 62 * S, sh = shade(skin, -0.2);
  const palm = smooth([[x - w * 0.9, y - 4], [x + w * 0.9, y - 4], [x + w * 1.05, y + L * 0.42], [x + w * 0.8, y + L * 0.62], [x - w * 0.85, y + L * 0.6], [x - w * 1.0, y + L * 0.4]], true, 0.8);
  // fingers: curled slightly towards the body, each its own little capsule, the far ones a touch darker
  for (let i = 3; i >= 0; i--) { const fx = x - w * 0.75 + i * w * 0.5, base = [fx, y + L * 0.52], tip = [fx + dirIn * (3 + i) * S, y + L * (0.98 - Math.abs(i - 1.4) * 0.07)]; d.ink(capsule(base, tip, 6.2 * S, 5.2 * S, 6), i === (dirIn > 0 ? 0 : 3) ? sh : skin, 2.6); }
  d.ink(palm, d.linU([[0, shade(skin, 0.08)], [0.55, skin], [0.56, sh], [1, sh]], x - w * dirIn, 0, x + w * dirIn, 0), 3);
  d.path(`M${x - w * 0.5} ${y + L * 0.5} q${w * 0.5} ${6 * S} ${w} 0`, 'none', { stroke: shade(skin, -0.32), sw: 2.2, op: 0.7 }); // the knuckles
  const tb = [x + dirIn * w * 0.8, y + L * 0.12], tt = [x + dirIn * w * 1.25, y + L * 0.62]; d.ink(capsule(tb, tt, 8 * S, 6 * S, 6), skin, 2.8); // the thumb, in front
}
function body(J, c, build) {
  // every body part as its own picture in the shared space, with its pivot (the joint it turns round)
  const parts = {}, PW = J.w, PH = J.h, ch = !!J.chibi, S = ch ? 0.82 : 1, fem = !!J.fem;
  const part = (name, pivot, parent, z, draw) => { const d = new Doc(PW, PH); draw(d); parts[name] = { svg: d.svg(), pivot, parent, z }; };
  const sleeve = c.sleeve ?? c.top, arm = (s) => { const i = s === 'R' ? 0 : 1; return [J.sh[i], J.el[i], J.wr[i]]; };
  part('torso', J.hip, null, 10, (d) => {
    const nx = J.neck[0], ny = J.neck[1], nw = J.nw, sy = J.sh[0][1], sw = J.sw, wy = J.waist, ww = J.ww, hw = J.hw, hy = J.hp[0][1] - 4, cy = J.hp[0][1] + 20;
    // the neck, with the shadow the chin casts
    const neck = rrect(nx - nw, ny - 34, nw * 2, sy - ny + 46, nw * 0.8);
    d.ink(neck, d.linU([[0, shade(c.skin, 0.06)], [0.6, c.skin], [0.61, shade(c.skin, -0.18)], [1, shade(c.skin, -0.22)]], nx - nw, 0, nx + nw, 0), 3.5);
    d.path(`M${nx - nw} ${ny - 30} Q${nx} ${ny + 14} ${nx + nw} ${ny - 30}Z`, shade(c.skin, -0.3), { op: 0.85 });
    // the body: trapezius, shoulders, ribs, a waist, hips
    const half = [[nx + nw * 0.9, sy - 26], [nx + sw * 0.62, sy - 16], [nx + sw * 0.98, sy - 2], [nx + sw * 1.02, sy + 26], [nx + sw * 0.86, sy + 74], [nx + sw * 0.8, sy + (wy - sy) * 0.62], [nx + ww, wy], [nx + (ww + hw) / 2 + 2, (wy + hy) / 2], [nx + hw, hy], [nx + hw - 4, cy], [nx + 10, cy + 8]];
    const pts = [...half, ...half.slice().reverse().map(([x, y]) => [2 * nx - x, y])], torso = smooth(pts, true, 0.75), cl = d.clip(torso);
    d.path(torso, d.linU([[0, shade(c.top, 0.1)], [0.5, c.top], [1, c.top]], nx - sw, 0, nx + sw, 0));
    // the shadow side: its edge runs a third of the way in from the right outline, following the waist in and the hips out
    const edge = half.map(([x, y]) => [nx + (x - nx) * 0.36 + 6, y]);
    d.path(smooth([...half.slice(1), [nx + hw + 40, cy + 40], [nx + sw + 60, sy - 40], ...edge.slice(1).reverse()], true, 0.7), shade(c.top, -0.2), { clip: cl });
    d.path(smooth(half.slice(2, 9).map(([x, y]) => [x - 7, y]), false, 1), 'none', { stroke: shade(c.top, -0.08), sw: 6, op: 0.6, clip: cl }); // light bounced into the shadow's edge
    // the chest and the folds where the arms meet the body
    const chestY = sy + (wy - sy) * (fem ? 0.36 : 0.3);
    if (fem) for (const k of [-1, 1]) d.path(`M${nx + k * sw * 0.7} ${chestY - 6} Q${nx + k * sw * 0.36} ${chestY + 34} ${nx + k * 4} ${chestY + 6}`, 'none', { stroke: shade(c.top, -0.3), sw: 3, op: 0.6, clip: cl });
    else d.path(`M${nx - sw * 0.62} ${chestY + 18} Q${nx} ${chestY + 34} ${nx + sw * 0.62} ${chestY + 18}`, 'none', { stroke: shade(c.top, -0.28), sw: 3, op: 0.5, clip: cl });
    for (const k of [-1, 1]) d.path(`M${nx + k * sw * 0.74} ${sy + 46} q${-k * 6} ${42} ${k * 2} ${86}`, 'none', { stroke: shade(c.top, -0.32), sw: 3, op: 0.55, clip: cl });
    d.path(torso, 'none', { stroke: INK, sw: 4.2 });
    if (c.collar) d.ink(P([[nx - nw * 1.7, ny + 2], [nx - nw * 1.1, ny + 4], [nx, ny + 62 * S], [nx + nw * 1.1, ny + 4], [nx + nw * 1.7, ny + 2], [nx, ny + 80 * S]]), c.collar, 3);
    else d.path(`M${nx - nw * 1.25} ${sy - 22} Q${nx} ${sy + 14} ${nx + nw * 1.25} ${sy - 22}`, 'none', { stroke: INK, sw: 3.2 }); // the neckline
    if (c.belt) d.ink(smooth([[nx - ww - 6, wy + 30], [nx, wy + 38], [nx + ww + 6, wy + 30], [nx + ww + 10, wy + 54], [nx, wy + 62], [nx - ww - 10, wy + 54]], true, 0.5), c.belt, 3);
    if (c.sash) d.ink(P([[nx - sw * 0.8, sy - 6], [nx - sw * 0.5, sy - 18], [nx + ww + 6, wy + 30], [nx + ww - 26, wy + 50]]), c.sash, 3);
  });
  for (const s of ['R', 'L']) {
    const [sh, el, wr] = arm(s), i = s === 'R' ? 0 : 1, zb = s === 'R' ? 4 : 16, out = s === 'R' ? -1 : 1, k = (fem ? 0.86 : 1) * S;
    // the arm swells at the shoulder (deltoid) on its outer side and at the top of the forearm
    part(`upperarm_${s}`, sh, 'torso', zb, (d) => limb(d, sh, el, 25 * k, 17 * k, sleeve, [[0.12, 7 * k, out === -1 ? 1 : -1], [0.45, 3 * k, 0]], 4, sleeve !== (c.cuff ? sleeve : c.skin)));
    part(`forearm_${s}`, el, `upperarm_${s}`, zb - 0.5, (d) => limb(d, el, wr, 18 * k, 12 * k, c.cuff ? sleeve : c.skin, [[0.22, 4 * k, 0]]));
    part(`hand_${s}`, wr, `forearm_${s}`, zb - 1, (d) => hand(d, wr, -out, k, c.skin));
    const [hp, kn, an] = [J.hp[i], J.kn[i], J.an[i]], zl = s === 'L' ? 7.5 : 6;
    part(`thigh_${s}`, hp, 'torso', zl, (d) => limb(d, hp, kn, 34 * S * (fem ? 1.04 : 1), 22 * S, c.legs, [[0.2, 5 * S, out === -1 ? 1 : -1]], 4, c.legs !== (c.socks ?? c.legs)));
    part(`shin_${s}`, kn, `thigh_${s}`, zl - 0.5, (d) => limb(d, kn, an, 21 * S, 12 * S, c.socks ?? c.legs, [[0.28, 6 * S, out === -1 ? 1 : -1], [0.3, 2 * S, 0]]));
    part(`foot_${s}`, an, `shin_${s}`, zl - 1, (d) => { const dir = out, w = 18 * S; d.ink(smooth([[an[0] - w, an[1] - 14], [an[0] + w, an[1] - 14], [an[0] + dir * 34 * S + dir * 8, J.toe - 18], [an[0] + dir * 30 * S, J.toe], [an[0] - dir * 22 * S, J.toe], [an[0] - dir * w * 1.1, J.toe - 12]], true, 0.6), d.linU([[0, shade(c.shoes, 0.18)], [0.5, c.shoes], [0.51, shade(c.shoes, -0.2)], [1, shade(c.shoes, -0.25)]], 0, an[1] - 14, 0, J.toe), 3.5); d.path(`M${an[0] - dir * 8 * S} ${J.toe - 16} q${dir * 22 * S} -6 ${dir * 38 * S} 6`, 'none', { stroke: shade(c.shoes, 0.35), sw: 3, op: 0.6 }); });
  }
  if (c.skirt) part('skirt', J.hip, 'torso', 12, (d) => {
    const top = J.waist + 26, hem = J.kn[0][1] - (ch ? 30 : 70), tw = J.ww + 6, bw = J.hw + (ch ? 22 : 44), nx = 300;
    const shape = smooth([[nx - tw, top], [nx + tw, top], [nx + bw * 0.86, (top + hem) / 2], [nx + bw, hem], [nx + bw * 0.5, hem + 10], [nx, hem + 14], [nx - bw * 0.5, hem + 10], [nx - bw, hem], [nx - bw * 0.86, (top + hem) / 2]], true, 0.45), cl = d.clip(shape);
    d.path(shape, d.linU([[0, shade(c.skirt, 0.12)], [0.58, c.skirt], [0.59, shade(c.skirt, -0.2)], [1, shade(c.skirt, -0.24)]], nx - bw, 0, nx + bw, 0));
    // pleats: each a fold with a lit and a shadowed face
    for (let q = -2; q <= 2; q++) { const x0 = nx + q * tw * 0.4, x1 = nx + q * bw * 0.42; d.path(`M${x0} ${top + 8} L${x1} ${hem + 8 - Math.abs(q) * 3}`, 'none', { stroke: shade(c.skirt, -0.3), sw: 3, op: 0.75, clip: cl }); d.path(`M${x0 + 6} ${top + 14} L${x1 + 9} ${hem - 4}`, 'none', { stroke: shade(c.skirt, 0.18), sw: 3, op: 0.5, clip: cl }); }
    d.path(shape, 'none', { stroke: INK, sw: 4 });
    d.path(`M${nx - tw} ${top + 2} L${nx + tw} ${top + 2}`, 'none', { stroke: shade(c.skirt, -0.35), sw: 5 }); // the waistband
  });
  return parts;
}
const CAST = [
  // id, name, build, skin, hair style, hair, eyes, clothes
  ['kaito', 'Kaito (swordsman)', 'anime', 1, 'spiky', 'black', 'amber', { top: '#2a2a36', collar: '#b0202e', legs: '#24242e', shoes: '#3a2a22', belt: '#b0202e' }],
  ['mira', 'Mira (village girl)', 'anime', 0, 'long', 'chestnut', 'green', { top: '#e8d8b8', legs: '#6a8a5a', shoes: '#6a4a32', skirt: '#4a7a5a', sash: '#c0303a' }],
  ['riya', 'Riya (student)', 'anime', 2, 'ponytail', 'black', 'brown', { top: '#ffffff', collar: '#2a3a6a', legs: '#2a3a6a', shoes: '#2a2a2a', skirt: '#2a3a6a' }],
  ['arjun', 'Arjun (courier)', 'anime', 3, 'short', 'black', 'brown', { top: '#d9643a', legs: '#3a4a6a', shoes: '#f2f2f2', belt: '#2a2a2a' }],
  ['sena', 'Sena (healer)', 'anime', 0, 'bob', 'silver', 'violet', { top: '#f2f0ea', collar: '#7a4ad9', legs: '#4a3a6a', shoes: '#3a2a4a', skirt: '#cfc6e8' }],
  ['dax', 'Dax (mechanic)', 'anime', 4, 'buzz', 'black', 'grey', { top: '#4a6a8a', legs: '#2a3a4a', shoes: '#3a2a22', belt: '#8a5a34', cuff: true }],
  ['lena', 'Lena (archer)', 'anime', 1, 'ponytail', 'blond', 'blue', { top: '#3a6a3a', collar: '#8a5a34', legs: '#5a4a32', shoes: '#4a3222', belt: '#8a5a34' }],
  ['hiro', 'Hiro (monk)', 'anime', 2, 'bald', 'black', 'brown', { top: '#d9902a', legs: '#d9902a', shoes: '#5a4a3a', sash: '#8a3a2a' }],
  ['noor', 'Noor (traveller)', 'anime', 3, 'long', 'black', 'amber', { top: '#3a8a8a', legs: '#e8dcc4', shoes: '#6a4a32', sash: '#e3b23c' }],
  ['ivo', 'Ivo (knight)', 'anime', 0, 'short', 'brown', 'blue', { top: '#a9b1bb', collar: '#3a4a7a', legs: '#7a828c', shoes: '#5a5f68', belt: '#8a5a34', cuff: true }],
  ['zara', 'Zara (pilot)', 'anime', 5, 'bun', 'black', 'brown', { top: '#c94a3a', legs: '#2a2a32', shoes: '#2a2a2a', belt: '#e3b23c' }],
  ['tomo', 'Tomo (chef)', 'anime', 1, 'short', 'chestnut', 'brown', { top: '#f6f6f2', legs: '#3a3a42', shoes: '#2a2a2a', collar: '#c0303a' }],
  ['aya', 'Aya (idol)', 'anime', 0, 'long', 'pink', 'violet', { top: '#ff7aa8', legs: '#ffffff', shoes: '#ff7aa8', skirt: '#ffd0e0', collar: '#ffffff' }],
  ['ren', 'Ren (detective)', 'anime', 1, 'short', 'navy', 'grey', { top: '#6a5a4a', collar: '#e8e2d2', legs: '#3a3a42', shoes: '#2a2422', belt: '#2a2422', cuff: true }],
  ['kofi', 'Kofi (drummer)', 'anime', 5, 'buzz', 'black', 'brown', { top: '#e3b23c', legs: '#2a3a5a', shoes: '#f2f2f2' }],
  ['mei', 'Mei (shrine keeper)', 'anime', 0, 'long', 'black', 'red', { top: '#ffffff', legs: '#c0303a', shoes: '#f2f2f2', skirt: '#c0303a', sash: '#c0303a' }],
  ['omar', 'Omar (merchant)', 'anime', 3, 'short', 'black', 'brown', { top: '#4a6a3a', legs: '#e8dcc4', shoes: '#6a4a32', sash: '#e3b23c', belt: '#8a5a34' }],
  ['elsa_v', 'Vera (mage)', 'anime', 0, 'long', 'white', 'blue', { top: '#3a3a7a', collar: '#e3b23c', legs: '#2a2a5a', shoes: '#2a2a3a', skirt: '#4a4a9a' }],
  ['jin', 'Jin (ninja)', 'anime', 1, 'spiky', 'navy', 'grey', { top: '#20232e', legs: '#20232e', shoes: '#20232e', sash: '#8a2a2a', cuff: true }],
  ['priya', 'Priya (dancer)', 'anime', 2, 'bun', 'black', 'brown', { top: '#e8406a', legs: '#e8406a', shoes: '#e3b23c', skirt: '#ffb030', sash: '#e3b23c' }],
  ['sol', 'Sol (captain)', 'anime', 4, 'short', 'silver', 'amber', { top: '#2a4f8f', collar: '#e3b23c', legs: '#20304a', shoes: '#20201e', belt: '#e3b23c', cuff: true }],
  ['nia', 'Nia (runner)', 'anime', 4, 'ponytail', 'red', 'green', { top: '#3ac08a', legs: '#2a2a32', shoes: '#ffffff' }],
  ['grandpa', 'Old Taro (elder)', 'anime', 1, 'buzz', 'silver', 'brown', { top: '#6a5a8a', legs: '#4a4a52', shoes: '#4a3a32', sash: '#c9a43a' }],
  ['grandma', 'Old Hana (elder)', 'anime', 0, 'bun', 'silver', 'brown', { top: '#8a5a7a', legs: '#4a3a4a', shoes: '#3a2a2a', skirt: '#6a4a5a' }],
  // chibi
  ['chibi_kaito', 'Chibi Kaito', 'chibi', 1, 'spiky', 'black', 'amber', { top: '#2a2a36', collar: '#b0202e', legs: '#24242e', shoes: '#3a2a22' }],
  ['chibi_mira', 'Chibi Mira', 'chibi', 0, 'long', 'chestnut', 'green', { top: '#e8d8b8', legs: '#6a8a5a', shoes: '#6a4a32', skirt: '#4a7a5a' }],
  ['chibi_riya', 'Chibi Riya', 'chibi', 2, 'ponytail', 'black', 'brown', { top: '#ffffff', collar: '#2a3a6a', legs: '#2a3a6a', shoes: '#2a2a2a', skirt: '#2a3a6a' }],
  ['chibi_arjun', 'Chibi Arjun', 'chibi', 3, 'short', 'black', 'brown', { top: '#d9643a', legs: '#3a4a6a', shoes: '#f2f2f2' }],
  ['chibi_aya', 'Chibi Aya', 'chibi', 0, 'long', 'pink', 'violet', { top: '#ff7aa8', legs: '#ffffff', shoes: '#ff7aa8', skirt: '#ffd0e0' }],
  ['chibi_jin', 'Chibi Jin', 'chibi', 1, 'spiky', 'navy', 'grey', { top: '#20232e', legs: '#20232e', shoes: '#20232e', sash: '#8a2a2a' }],
  ['chibi_hiro', 'Chibi Hiro', 'chibi', 2, 'bald', 'black', 'brown', { top: '#d9902a', legs: '#d9902a', shoes: '#5a4a3a' }],
  ['chibi_zara', 'Chibi Zara', 'chibi', 5, 'bun', 'black', 'brown', { top: '#c94a3a', legs: '#2a2a32', shoes: '#2a2a2a' }],
  ['chibi_sena', 'Chibi Sena', 'chibi', 0, 'bob', 'silver', 'violet', { top: '#f2f0ea', legs: '#4a3a6a', shoes: '#3a2a4a', skirt: '#cfc6e8' }],
  ['chibi_kofi', 'Chibi Kofi', 'chibi', 5, 'buzz', 'black', 'brown', { top: '#e3b23c', legs: '#2a3a5a', shoes: '#f2f2f2' }],
  ['chibi_mei', 'Chibi Mei', 'chibi', 0, 'long', 'black', 'red', { top: '#ffffff', legs: '#c0303a', shoes: '#f2f2f2', skirt: '#c0303a' }],
  ['chibi_nia', 'Chibi Nia', 'chibi', 4, 'ponytail', 'red', 'green', { top: '#3ac08a', legs: '#2a2a32', shoes: '#ffffff' }],
];
export function characters() {
  const out = [];
  CAST.forEach(([id, name, build, sk, hs, hc, ec, clothes], q) => {
    const FEM = new Set(['mira', 'riya', 'sena', 'lena', 'noor', 'zara', 'aya', 'mei', 'elsa_v', 'priya', 'nia', 'grandma']), J = joints(build, FEM.has(id)), c = { ...clothes, skin: SKIN[sk] }, hair = HAIR[hc], eye = EYE[ec];
    const parts = body(J, c, build);
    const head = (fn, z) => { const d = new Doc(J.w, J.h); fn(d); return { svg: d.svg(), pivot: J.neck, parent: 'torso', z }; };
    parts.hair_back = head((d) => hairBack(d, J, hs, hair), 2);
    parts.head = head((d) => {
      const hs0 = headShape(J, build), cl = d.clip(hs0), w = J.headW, t = J.top, ch = J.chin, hh = ch - t;
      d.path(hs0, d.linU([[0, shade(c.skin, 0.07)], [0.75, c.skin], [1, shade(c.skin, -0.03)]], 0, t, 0, ch));
      // the cheek and jaw away from the light fall into a soft-edged shadow; light bounces back into the very edge
      d.path(smooth([[300 + w * 1.05, t + hh * 0.36], [300 + w * 0.98, t + hh * 0.7], [300 + w * 0.5, ch - hh * 0.02], [300 + w * 0.05, ch + 4], [300 + w * 0.32, ch - hh * 0.1], [300 + w * 0.7, t + hh * 0.72], [300 + w * 0.8, t + hh * 0.44]], true, 0.8), shade(c.skin, -0.16), { clip: cl, op: 0.9 });
      d.path(ellipse(300, t + hh * 0.2, w * 1.2, hh * 0.13), shade(c.skin, -0.14), { clip: cl, op: 0.55 }); // under the hairline
      d.path(hs0, 'none', { stroke: INK, sw: 4.5 }); for (const s of [-1, 1]) d.ink(ellipse(300 + s * J.headW * 0.98, J.eyeY + 14, J.headW * 0.1, J.headW * 0.16), c.skin, 3.5); d.path(`M300 ${J.eyeY + (J.chin - J.eyeY) * 0.3} l-6 ${(J.chin - J.eyeY) * 0.12} l8 2`, 'none', { stroke: shade(c.skin, -0.35), sw: 3.5 }); for (const s of [-1, 1]) d.path(ellipse(300 + s * J.headW * 0.55, J.eyeY + (J.chin - J.eyeY) * 0.38, J.headW * 0.13, J.headW * 0.06), '#ff8a9a', { op: 0.35 }); }, 20);
    for (const k of EYES) parts[`eyes_${k}`] = head((d) => eyes(d, J, k, eye, build), 22);
    for (const k of MOUTHS) parts[`mouth_${k}`] = head((d) => mouth(d, J, k, build), 22);
    parts.hair_front = head((d) => hairFront(d, J, hs, hair, q + 3), 24);
    // the preview: the rest pose with open eyes and the rest mouth
    const show = ['hair_back', 'thigh_R', 'shin_R', 'foot_R', 'thigh_L', 'shin_L', 'foot_L', 'upperarm_R', 'forearm_R', 'hand_R', 'torso', 'skirt', 'upperarm_L', 'forearm_L', 'hand_L', 'head', 'eyes_open', 'mouth_rest', 'hair_front'].filter((k) => parts[k]).sort((a, b) => parts[a].z - parts[b].z);
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${J.w} ${J.h}" width="${J.w}" height="${J.h}">${show.map((k) => parts[k].svg.replace(/^<svg[^>]*>/, '').replace(/<\/svg>$/, '')).join('')}</svg>`;
    out.push({ id: `char_${id}`, name, cat: 'characters', sub: build, tags: ['character', 'cutout', 'puppet', 'rig', build, hs, hc], w: J.w, h: J.h, svg, parts: Object.fromEntries(Object.entries(parts).map(([k, p]) => [k, { ...p, w: J.w, h: J.h }])), rig: { build, eyes: EYES, mouths: MOUTHS, joints: { neck: J.neck, hip: J.hip, sh: J.sh, el: J.el, wr: J.wr, hp: J.hp, kn: J.kn, an: J.an }, ground: J.toe }, anchor: [0.5, J.toe / J.h] });
  });
  return out;
}
