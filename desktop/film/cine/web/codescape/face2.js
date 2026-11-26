// PEHLI SALARY — face v4 (26 Nov 2026): one sculpted head surface with a real nose, cheeks, chin and lips that part.
// The mouth is a slit in the mesh (two seam rows a hair apart, no triangles between them inside the mouth); the jaw morph
// swings the lower lip and chin down, so the mouth opens into a dark inside with upper teeth, lower teeth and a tongue.
// Morphs: 0 jaw, 1 smile, 2 round (oo), 3 wide (ee), 4 sad, 5 press (mm). Lip colour, nostrils, blush in vertex colours.
import { THREE, V3, clamp, lerp } from './engine.js';

const ss = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
const gx = (d, s) => Math.exp(-(d / s) * (d / s));
// smooth table lookup (y descending or ascending)
const tab = (T, y) => { const n = T.length; if (y <= T[0][0]) return T[0][1]; if (y >= T[n - 1][0]) return T[n - 1][1];
  // monotone cubic (Fritsch–Carlson): smooth through the points, no terraces
  if (!T.m) { const d = [], m = []; for (let i = 0; i < n - 1; i++) d.push((T[i + 1][1] - T[i][1]) / (T[i + 1][0] - T[i][0])); m.push(d[0]); for (let i = 1; i < n - 1; i++) m.push(d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2); m.push(d[n - 2]);
    for (let i = 0; i < n - 1; i++) { if (d[i] === 0) { m[i] = m[i + 1] = 0; continue; } const a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b; if (s > 9) { const k = 3 / Math.sqrt(s); m[i] = k * a * d[i]; m[i + 1] = k * b * d[i]; } } T.m = m; }
  let i = 0; while (y > T[i + 1][0]) i++; const h = T[i + 1][0] - T[i][0], u = (y - T[i][0]) / h, u2 = u * u, u3 = u2 * u;
  return (2 * u3 - 3 * u2 + 1) * T[i][1] + (u3 - 2 * u2 + u) * h * T.m[i] + (-2 * u3 + 3 * u2) * T[i + 1][1] + (u3 - u2) * h * T.m[i + 1]; };

// the rig's skull: centre (0, 0.08, 0), radii 0.148 / 0.156 / 0.146, narrowing to the jaw
function base(phi, th, jawW, o_short = 0.12) { const dy = Math.sin(phi), c = Math.cos(phi), dx = c * Math.sin(th), dz = c * Math.cos(th); const low = Math.max(0, -dy);
  return [dx * 0.148 * (1 - jawW * low * low), 0.08 + dy * 0.156 * (1 + 0.04 * low) * (1 - (o_short) * low * low), dz * 0.146 * (1 - 0.16 * low * low) * (dz > 0 ? 1 - 0.05 * (1 - Math.abs(dy)) : 1.03)]; }

export function buildFace(p, o = {}) {
  const jawW = o.jaw ?? 0.2, W0 = o.mouthW ?? 0.025, SY = o.seamY ?? 0.008, age = o.age ?? 0;
  // ---- the grid: uniform, plus fine rows/columns through the nose and lips, plus the seam pair
  // smooth-density grid: rows and columns crowd gradually toward the nose and lips (no sudden jumps in spacing, which shade as lines)
  let phiS = -0.5; for (let k = 0; k < 40; k++) { const y = base(phiS, 0, jawW)[1]; phiS += (SY - y) / (0.156 * Math.cos(phiS) + 1e-3); }
  const warp = (lo, hi, n, dens) => { const M = 4000, cdf = [0]; for (let i = 1; i <= M; i++) { const a = lo + (hi - lo) * (i - 0.5) / M; cdf.push(cdf[i - 1] + dens(a)); } const out = []; let j = 0;
    for (let k = 0; k < n; k++) { const tgt = cdf[M] * k / (n - 1); while (j < M && cdf[j + 1] < tgt) j++; const u = j >= M ? 1 : (tgt - cdf[j]) / Math.max(1e-12, cdf[j + 1] - cdf[j]); out.push(lo + (hi - lo) * (j + u) / M); } return out; };
  let ph = warp(-Math.PI / 2, Math.PI / 2, o.rows ?? 300, a => 1 + 2.6 * Math.exp(-(((a - 0.05) / 0.32) ** 2)) + 3.4 * Math.exp(-(((a - phiS) / 0.09) ** 2)));
  { let k = 0; for (let i = 1; i < ph.length; i++) if (Math.abs(ph[i] - phiS) < Math.abs(ph[k] - phiS)) k = i; const EPS = 0.0007; ph.splice(k, 1, phiS - EPS, phiS + EPS); }
  const iSeam = ph.indexOf(ph.find(a => Math.abs(a - phiS) < 0.0008 && a < phiS));
  let thv = warp(-Math.PI, Math.PI, o.cols ?? 300, a => 1 + 4 * Math.exp(-((a / 0.3) ** 2))); thv.pop();
  const R = ph.length, C = thv.length, N = R * C; let j0 = 0; thv.forEach((a, j) => { if (Math.abs(a) < Math.abs(thv[j0])) j0 = j; });
  const pos = new Float32Array(N * 3), col = new Float32Array(N * 3), uv = new Float32Array(N * 2), stub = new Float32Array(N);
  const MT = [0, 1, 2, 3, 4, 5].map(() => new Float32Array(N * 3));
  const skin = new THREE.Color(o.skin || p.C.skin), lipC = new THREE.Color(o.lip || '#a55a55'), nostC = new THREE.Color('#3a1810'), blushC = new THREE.Color('#d46a5a');
  const hingeY = 0.05, hingeZ = -0.005, TH = o.jawAngle ?? 0.24, cT = Math.cos(TH), sT = Math.sin(TH);
const PN = [[0.032, 0], [0.039, 0.0065], [0.046, 0.0175], [0.053, 0.0232], [0.062, 0.0205], [0.075, 0.0155], [0.095, 0.0102], [0.115, 0.006], [0.133, 0.002], [0.148, 0]];
  const WN = [[0.034, 0.011], [0.045, 0.0135], [0.054, 0.014], [0.065, 0.0122], [0.085, 0.0105], [0.11, 0.0098], [0.14, 0.011]];
  let seamZ = 0.15; const zMidT = []; for (let a = -1.5; a <= 0.6; a += 0.01) { const q = base(a, 0, jawW); zMidT.push([q[1], q[2]]); } const zMid = y => tab(zMidT, y);
  const PROF = (o.prof || [[-0.068, 0.03], [-0.062, 0.075], [-0.055, 0.1], [-0.045, 0.117], [-0.032, 0.126], [-0.018, 0.127], [-0.006, 0.134], [0.008, 0.138], [0.03, 0.14], [0.06, 0.13]]).map(r => [r[0] + SY - 0.008, r[1]]);
  for (let i = 0; i < R; i++) for (let j = 0; j < C; j++) { const k = i * C + j, phi = ph[i], th = thv[j];
    let [x, y, z] = base(phi, th, jawW); const ax = Math.abs(x), sg = x < 0 ? -1 : 1, front = z > 0 ? 1 : 0; const up = i > iSeam;
    const cx = new V3(x, y - 0.08, z).normalize(); let rad = 0, dz = 0, dyy = 0; const F = ss(0.0, 0.07, z);
    // ---- face structure (radial)
    rad += 0.0045 * gx(ax - 0.044, 0.034) * gx(y - 0.146, 0.012) * F;                         // brow ridge
    rad -= 0.0045 * gx(ax - 0.052, 0.028) * gx(y - 0.113, 0.02) * F;                           // eye socket
    rad += 0.0048 * gx(ax - 0.08, 0.028) * gx(y - 0.074, 0.018) * F;                           // cheekbone
    rad += (o.cheek ?? 0.006) * gx(ax - 0.058, 0.032) * gx(y - (o.cheekY ?? 0.034), 0.03) * F;               // the round cheeks
    rad -= 0.003 * gx(ax - 0.13, 0.025) * gx(y - 0.13, 0.03);                                  // temples
    rad += (o.jawA ?? 0.003) * gx(ax - 0.105, 0.022) * gx(y + 0.025, 0.025) * ss(-0.05, 0.03, z); // jaw angle
    rad += age * 0.0012 * gx(ax - 0.05, 0.016) * gx(y - 0.086, 0.006) * F;                     // soft under-eye
    // nasolabial: a soft crease from the nose wing to past the mouth corner, cheek mass outside it
    { const u = clamp((0.047 - y) / 0.05, 0, 1), lx = lerp(0.024, 0.036, u); const wn = ss(0.06, 0.045, y) * ss(-0.02, -0.004, y); rad -= (0.0006 + age * 0.001) * gx(ax - lx, 0.004) * F * wn; rad += 0.0014 * gx(ax - lx - 0.011, 0.009) * F * wn; }
    // ---- the lower face's profile: lips under the nose, a chin that comes forward (the bare skull recedes too much)
    { const zc = zMid(y), zt = tab(PROF, y); const wx = Math.max(0, 1 - (x / (o.jawV ?? 0.1)) ** 2); const d = zt - zc; dz += 0.5 * (d + Math.sqrt(d * d + 0.000016)) * wx * wx * F; }
    dz += 0.001 * gx(x, 0.03) * gx(y - SY - 0.004, 0.024) * F;
    // ---- chin
    dz += (o.chin ?? 0.006) * gx(x, 0.026) * gx(y + 0.042, 0.016) * F; dyy -= 0.002 * gx(x, 0.025) * gx(y + 0.058, 0.016) * F;
    // ---- nose: a profile down the middle, wings, nostrils
    const nl = o.nose ?? 1; dz += nl * tab(PN, y) * gx(x, tab(WN, y)) * F;
    const wing = 0.0105 * gx(ax - 0.0165, 0.0072) * gx(y - 0.047, 0.0078) * F; dz += wing * nl;
    dz -= 0.0016 * gx(ax - 0.0255, 0.0022) * gx(y - 0.047, 0.009) * F;                         // crease round the wing
    const nost = gx(ax - 0.0068, 0.0034) * gx(y - 0.0405, 0.0022) * F; dz -= 0.0038 * nost;
    // ---- lips
    const xn = ax / W0, cw = Math.sqrt(Math.max(0, 1 - xn * xn)); const t = y - SY; let mask = 0;
    const hu = 0.0074 * (0.35 + 0.65 * Math.pow(cw, 0.7)) + 0.001 * gx(ax - 0.0068, 0.0045) - 0.0007 * gx(x, 0.0026);
    const hl = 0.0096 * (0.3 + 0.7 * Math.pow(cw, 0.6));
    const inLip = xn < 1.18 ? ss(1.18, 0.9, xn) : 0;
    if (up) { const tt = t / hu; let f = tt < 1 ? Math.exp(-(((tt - 0.38) / 0.45) ** 2)) - 0.02 * Math.exp(-((tt / 0.1) ** 2)) : 0.1498 * Math.exp(-(((tt - 1) / 0.35) ** 2));
      f += 0.03 * Math.exp(-(((tt - 1) / 0.07) ** 2)); dz += (o.lipU ?? 0.0046) * Math.pow(cw, 0.45) * f * inLip * F; mask = ss(1.07, 0.93, tt) * ss(1.1, 0.96, xn) * F;
      // philtrum: two soft ridges from the bow peaks up to the nose, a groove between
      { const wv = ss(0.85, 1.05, tt) * ss(0.046, 0.036, y), v = clamp((y - SY - hu) / (0.04 - SY - hu), 0, 1), px = lerp(0.0066, 0.0042, v); dz += wv * (0.0008 * gx(ax - px, 0.0019) - 0.0005 * gx(x, 0.0024)) * F; } }
    else { const tt = -t / hl; let f = tt < 1 ? Math.exp(-(((tt - 0.55) / 0.42) ** 2)) - 0.1 * Math.exp(-((tt / 0.12) ** 2)) : 0.3173 * Math.exp(-(((tt - 1) / 0.3) ** 2)) - 0.1 * Math.exp(-(((tt - 1.9) / 0.6) ** 2)) * ss(1, 1.5, tt);
      dz += (o.lipL ?? 0.0055) * Math.pow(cw, 0.45) * f * inLip * F; mask = ss(1.15, 0.8, tt) * ss(1.1, 0.96, xn) * F; }
    rad -= 0.0016 * gx(ax - W0 * 1.02, 0.0042) * gx(t, 0.0045) * F;                           // corner dimple
    dyy -= 0.0012 * (xn <= 1 ? xn * xn : Math.exp(-(((xn - 1) / 0.45) ** 2))) * gx(t, 0.012) * F;             // the line of the mouth dips a little at the corners
    x += cx.x * rad; y += cx.y * rad + dyy; z += cx.z * rad + dz;
    pos.set([x, y, z], k * 3); uv.set([(th + Math.PI) / (2 * Math.PI), (phi + Math.PI / 2) / Math.PI], k * 2);
    if (j === j0 && i === iSeam + 1) seamZ = z;
    // ---- colour (ratio to the skin colour, linear)
    let cr = 1, cg = 1, cb = 1; const mix = (c, a) => { cr = lerp(cr, c.r / skin.r, a); cg = lerp(cg, c.g / skin.g, a); cb = lerp(cb, c.b / skin.b, a); };
    mix(blushC, (o.blush ?? 0.12) * gx(ax - 0.06, 0.026) * gx(y - 0.045, 0.022) * F); mix(lipC, mask * (o.lipA ?? 0.85)); if (up && mask > 0) { const dk = 1 - 0.12 * mask; cr *= dk; cg *= dk; cb *= dk; } mix(nostC, 0.8 * nost);
    const sh = 1 - (o.sock ?? 0.08) * gx(ax - 0.05, 0.03) * gx(y - 0.11, 0.022) * F - 0.18 * gx(ax - W0, 0.003) * gx(t, 0.003) * F; cr *= sh; cg *= sh; cb *= sh;
    col.set([cr, cg, cb], k * 3);
    stub[k] = (o.stubble ? 1 : 0) * F * (1 - mask) * (1 - nost) * Math.max(ss(0.026, -0.005, y) * ss(0.13, 0.095, ax), ss(0.05, 0.035, ax) * ss(0.04, 0.03, y) * ss(SY + 0.003, SY + 0.009, y)) * ss(-0.03, 0.03, z);
    // ---------------- morph targets
    const inm = ss(1.25, 0.85, xn) * front, prof = Math.sqrt(Math.max(0, 1 - xn * xn)) * front, ramp = ss(SY + 0.012, SY - 0.026, y);
    // 0 jaw: the lower face swings down about the hinge; inside the mouth the lower lip follows fully and the upper lip lifts
    { let w = up ? ramp * (1 - inm * prof) : lerp(ramp, 1, inm * prof); w *= ss(0.13, 0.075, ax) * ss(-0.04, 0.05, z); const ry = y - hingeY, rz = z - hingeZ;
      const nyy = ry * cT - rz * sT, nzz = ry * sT + rz * cT; let mx = 0, my = (nyy - ry) * w, mz = (nzz - rz) * w;
      if (up) { my += 0.0042 * prof * gx(t, 0.009); mz += 0.0008 * prof * gx(t, 0.009); } MT[0].set([mx, my, mz], k * 3); }
    const near = gx(t, 0.014) * front, gxx = xn <= 1 ? xn * xn : Math.exp(-(((xn - 1) / 0.55) ** 2));
    // 1 smile: corners up and back, lips stretch thinner, cheeks rise
    { const ch = gx(ax - 0.056, 0.022) * gx(y - 0.042, 0.022) * F; let mx = sg * 0.0032 * Math.min(xn, 1) * near * (xn <= 1 ? 1 : gxx), my = 0.0062 * gxx * near + 0.0042 * ch + 0.0016 * gx(ax - 0.05, 0.02) * gx(y - 0.088, 0.01) * F, mz = -0.0022 * gxx * near * ss(0.5, 1, xn) + 0.0032 * ch - 0.0008 * mask;
      MT[1].set([mx, my, mz], k * 3); }
    // 2 round (oo): the mouth gathers toward the middle and pushes forward
    { const env = gx(t, 0.017) * (xn < 1.3 ? 1 : Math.exp(-(((xn - 1.3) / 0.4) ** 2))) * front; MT[2].set([-x * 0.36 * env, -t * 0.12 * env, 0.0062 * env * (0.4 + 0.6 * cw)], k * 3); }
    // 3 wide (ee): corners out, lips thin and part a little
    { MT[3].set([sg * 0.0055 * Math.min(xn, 1) * near * (xn <= 1 ? 1 : gxx), (up ? 0.0014 : -0.0012) * prof * gx(t, 0.008) + 0.0012 * gxx * near, -0.001 * near - 0.0006 * mask], k * 3); }
    // 4 sad: corners down, lower lip out, chin up
    { const chn = gx(x, 0.02) * gx(y + 0.04, 0.016) * F; MT[4].set([0, -0.0042 * gxx * near + 0.0022 * chn, (up ? 0 : 0.0016 * mask) + 0.0018 * chn], k * 3); }
    // 5 press (mm): lips roll in and meet
    { MT[5].set([0, (up ? -0.0007 : 0.0007) * mask, -0.0024 * mask], k * 3); }
  }
  const idx = []; const xAt = j => Math.abs(Math.cos(phiS) * Math.sin(thv[j]) * 0.148), frontAt = j => Math.cos(thv[j]) > 0;
  for (let i = 0; i < R - 1; i++) for (let j = 0; j < C; j++) { const j2 = (j + 1) % C; if (i === iSeam && frontAt(j) && frontAt(j2) && xAt(j) < W0 * 0.995 && xAt(j2) < W0 * 0.995) continue;
    const a = i * C + j, b = i * C + j2, c = (i + 1) * C + j, d = (i + 1) * C + j2; idx.push(a, b, c, b, d, c); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('color', new THREE.BufferAttribute(col, 3)); g.setAttribute('uv', new THREE.BufferAttribute(uv, 2)); g.setAttribute('stub', new THREE.BufferAttribute(stub, 1));
  g.setIndex(idx); g.computeVertexNormals(); g.morphAttributes.position = MT.map(a => new THREE.BufferAttribute(a, 3)); g.morphTargetsRelative = true;
  const m = (o.mat || p.mSkin).clone(); m.vertexColors = true; m.side = THREE.FrontSide; m.bumpMap = null; m.sheen = 0.25; m.sheenColor = new THREE.Color('#ffb090'); m.roughness = 0.7; m.specularIntensity = 0.25; m.emissive = new THREE.Color(o.skin || p.C.skin).multiplyScalar(0.05); m.sheen = 0.4; m.sheenColor = new THREE.Color('#ff9a7a');
  if (o.stubble) m.onBeforeCompile = sh => { sh.vertexShader = 'attribute float stub; varying float vSt; varying vec3 vP;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n vSt = stub; vP = position;');
    sh.fragmentShader = 'varying float vSt; varying vec3 vP;\nfloat hh(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n' + sh.fragmentShader.replace('#include <color_fragment>', `#include <color_fragment>
      float n = 0.45 + 0.55 * hh(floor(vec2(vP.x * 1700.0, vP.y * 1700.0 + vP.z * 900.0))); diffuseColor.rgb *= 1.0 - ${(o.stubble).toFixed(2)} * 0.22 * vSt * n;`); };
  const mesh = new THREE.Mesh(g, m); mesh.castShadow = o.cast ?? false; mesh.receiveShadow = true; mesh.morphTargetInfluences = [0, 0, 0, 0, 0, 0]; p.head.add(mesh);
  // ---- inside the mouth: a dark cavity (seen from inside), teeth, tongue
  const inside = new THREE.Group(); p.head.add(inside);
  const cav = new THREE.Mesh(new THREE.SphereGeometry(0.034, 24, 18), new THREE.MeshBasicMaterial({ color: '#2a0d10', side: THREE.BackSide })); cav.scale.set(1.05, 0.9, 0.85); cav.position.set(0, SY - 0.008, seamZ - 0.03); inside.add(cav);
  const toothM = new THREE.MeshPhysicalMaterial({ color: '#f3ece0', roughness: 0.32, clearcoat: 0.4, side: THREE.DoubleSide });
  const teethArc = (Rr, h, top, z0, n) => { const gq = new THREE.BufferGeometry(); const P = [], I = []; const A = 1.05, S = 48;
    for (let s = 0; s <= S; s++) { const a = -A + 2 * A * s / S, tooth = Math.abs(a) * Rr / 0.0048, sc = 0.0004 * Math.abs(Math.cos(Math.PI * tooth)) ** 6; const r = Rr - sc;
      for (const [yy, dr] of [[top, -0.002], [top - h * 0.85, 0], [top - h, -0.0012], [top - h, -0.0035], [top, -0.0045]]) P.push(Math.sin(a) * (r + dr), yy, z0 - Rr + Math.cos(a) * (r + dr)); }
    for (let s = 0; s < S; s++) for (let q = 0; q < 4; q++) { const a = s * 5 + q, b = a + 5, c = a + 1, d = b + 1; I.push(a, b, c, c, b, d); }
    gq.setAttribute('position', new THREE.Float32BufferAttribute(P, 3)); gq.setIndex(I); gq.computeVertexNormals(); const me = new THREE.Mesh(gq, toothM); return me; };
  const zAt = (xq, yq) => { let best = 9, zz = 0.13; for (let k = 0; k < N; k++) { if (pos[k * 3 + 2] < 0.05) continue; const d = (pos[k * 3] - xq) ** 2 + (pos[k * 3 + 1] - yq) ** 2; if (d < best) { best = d; zz = pos[k * 3 + 2]; } } return zz; };
  const zLow = Math.min(zAt(0, SY - 0.006), zAt(0, SY - 0.011), zAt(0, SY - 0.016));
  const upT = teethArc(0.026, 0.0095, SY + 0.0055, seamZ - 0.0042); inside.add(upT);
  const jawP = new THREE.Group(); jawP.position.set(0, hingeY, hingeZ); p.head.add(jawP); const jl = new THREE.Group(); jl.position.set(0, -hingeY, -hingeZ); jawP.add(jl);
  const loT = teethArc(0.0235, 0.0065, SY - 0.0042, Math.min(seamZ - 0.0062, zLow - 0.0045)); jl.add(loT);
  const tg = new THREE.Mesh(new THREE.SphereGeometry(0.02, 24, 16), new THREE.MeshStandardMaterial({ color: '#b25660', roughness: 0.45 })); tg.scale.set(0.95, 0.3, 1.0); tg.position.set(0, SY - 0.011, Math.min(seamZ - 0.026, zLow - 0.026)); jl.add(tg);
  for (const c of [cav, upT, loT, tg]) { c.castShadow = false; c.receiveShadow = false; }
  const fc = { mesh, jawP, TH, seamZ, SY };
  // hide the old head surface and the old mouth/nose; drive the new morphs after the rig's face update
  p.headM.visible = false; p.mouth.visible = false; p.lipL.visible = false; if (p.noseG) p.noseG.visible = false;
  const orig = p.faceUpdate.bind(p);
  p.faceUpdate = t => { const F0 = p.face, sv = [F0.lidL, F0.lidR]; const lb = o.lidBias ?? 0.14; F0.lidL = Math.min(1, F0.lidL + lb); F0.lidR = Math.min(1, F0.lidR + lb); orig(t); F0.lidL = sv[0]; F0.lidR = sv[1]; const F = p.face, I = mesh.morphTargetInfluences; const jaw = clamp(F.jaw, 0, 1.1);
    I[0] = jaw; I[1] = clamp(F.smile, 0, 1.25); I[2] = clamp(F.round, 0, 1); I[3] = clamp(F.wide, 0, 1); I[4] = clamp(F.sad, 0, 1); I[5] = clamp(F.press ?? 0, 0, 1); jawP.rotation.x = jaw * TH; };
  p.face2 = fc; return fc; }

// ---- an iris with depth: a lighter centre, fibres, a dark ring at the edge (sphere rotated so its pole looks forward)
/** z of the face surface at (x, y) in head space (front) */
export function faceZ(p, x, y) { const P = p.face2.mesh.geometry.attributes.position; let best = 9, z = 0.14; for (let i = 0; i < P.count; i++) { const zz = P.getZ(i); if (zz < 0.05) continue; const d = (P.getX(i) - x) ** 2 + (P.getY(i) - y) ** 2; if (d < best) { best = d; z = zz; } } return z; }

export function irisUpgrade(p, colA, colB) { if (typeof document === 'undefined') return; const cv = document.createElement('canvas'); cv.width = 256; cv.height = 256; const g = cv.getContext('2d');
  const gr = g.createLinearGradient(0, 0, 0, 128); gr.addColorStop(0, colA); gr.addColorStop(0.55, colB); gr.addColorStop(0.86, colB); gr.addColorStop(0.97, '#120804'); gr.addColorStop(1, '#120804'); g.fillStyle = gr; g.fillRect(0, 0, 256, 256);
  for (let i = 0; i < 260; i++) { const x = Math.random() * 256; g.strokeStyle = `rgba(${Math.random() < 0.5 ? '255,220,170' : '20,10,4'},${0.06 + Math.random() * 0.1})`; g.lineWidth = 1 + Math.random() * 1.5; g.beginPath(); g.moveTo(x, 20 + Math.random() * 20); g.lineTo(x + (Math.random() - 0.5) * 4, 90 + Math.random() * 30); g.stroke(); }
  const tx = new THREE.CanvasTexture(cv); tx.colorSpace = THREE.SRGBColorSpace;
  for (const e of p.eyes) { const ir = e.ball.children[0]; const geo = new THREE.SphereGeometry(0.0195, 32, 24); geo.rotateX(Math.PI / 2); ir.geometry.dispose(); ir.geometry = geo; ir.material = new THREE.MeshPhysicalMaterial({ map: tx, roughness: 0.25, clearcoat: 1, clearcoatRoughness: 0.05 }); ir.scale.set(1, 1, 0.3); } }
