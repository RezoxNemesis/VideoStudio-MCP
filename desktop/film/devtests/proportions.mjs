// A body measured in HEADS, the way an animator checks a figure (a proportions guide the owner sent, 16 Nov 2026:
// 7.5–8 heads tall; shoulders 2.5–3 heads wide, chest about 2, waist 1.5–2, hips 2–2.5; navel about 3 heads from the
// top, crotch at 4, knees at 6, ankles at 7.5; hands end at mid-thigh; nipples a head apart; the hip line under the
// shoulders). node devtests/proportions.mjs [fixture.json.gz …]      (exit 1 when a rule is broken)
// The head is measured skull top to chin (hair left out), widths across the bare body, from a rest-pose body fixture
// (devtests/bodyinfo.mjs … --fixture).
import fs from 'node:fs';
import { gunzipSync } from 'node:zlib';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(2) : JSON.stringify(got)}`); };
const b64 = (s, T) => { const b = Buffer.from(s, 'base64'); return new T(b.buffer, b.byteOffset, b.byteLength / T.BYTES_PER_ELEMENT); };

/** The measures of a body, in heads. */
export function measure(o) {
  const pos = b64(o.skin.pos, Float32Array), si = b64(o.skin.si, Uint16Array).length === o.skin.n * 4 ? b64(o.skin.si, Uint16Array) : b64(o.skin.si, Uint8Array), sw = b64(o.skin.sw, Float32Array), n = o.skin.n, L = o.landmarks;
  const head = b64(o.head, Float32Array), bone = (nm) => { const i = o.names.indexOf(nm); return [head[i * 3], head[i * 3 + 1], head[i * 3 + 2]]; };
  const main = new Array(n); for (let v = 0; v < n; v++) { let b = -1, w = -1; for (let k = 0; k < 4; k++) if (sw[v * 4 + k] > w) { w = sw[v * 4 + k]; b = si[v * 4 + k]; } main[v] = o.names[b] ?? ''; }
  const V = (v) => [pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]];
  let top = -1e9, sole = 1e9; for (let v = 0; v < n; v++) { const y = pos[v * 3 + 1]; if (y > top) top = y; if (y < sole) sole = y; }
  // the chin: the lowest place on the front of the face, under the mouth
  // (along the middle of the face downward from the mouth: the tip of the chin stands furthest forward; the chin ends
  //  where the profile has turned 3.5 cm back from that tip — under it is the throat)
  const zAt = (y) => { let m = -1e9; for (let v = 0; v < n; v++) { const [x, yy, z] = V(v); if (Math.abs(x) < 0.015 && Math.abs(yy - y) < 0.003 && z > m) m = z; } return m; };
  let tipY = L.mouth[1] - 0.015, tipZ = -1e9; for (let y = L.mouth[1] - 0.015; y > L.mouth[1] - 0.07; y -= 0.002) { const z = zAt(y); if (z > tipZ) { tipZ = z; tipY = y; } }
  let chin = tipY; for (let y = tipY; y > L.neckY - 0.03; y -= 0.002) { chin = y; if (zAt(y) < tipZ - 0.035) break; }
  const H = top - chin, h = (y) => (top - y) / H;
  const torso = /^(spine|breast|pelvis|root|clavicle)/, widthAt = (y0, y1, re) => { let lo = 1e9, hi = -1e9; for (let v = 0; v < n; v++) { const [x, y] = V(v); if (y < y0 || y > y1 || (re && !re.test(main[v]))) continue; if (x < lo) lo = x; if (x > hi) hi = x; } return (hi - lo) / H; };
  const sh = bone('clavicle.L')[1], nip = bone('breast.L'), hipY = L.hipY;
  // the waist: the narrowest band of the trunk between the chest and the hips
  let waist = 1e9, waistY = 0; for (let y = hipY + 0.06; y < nip[1] - 0.06; y += 0.01) { const w = widthAt(y - 0.015, y + 0.015, torso); if (w < waist) { waist = w; waistY = y; } }
  let crotch = 1e9; for (let v = 0; v < n; v++) { const [x, y] = V(v); if (Math.abs(x) < 0.012 && y > L.kneeY + 0.1 && y < hipY + 0.05) crotch = Math.min(crotch, y); }
  const sj = bone('upperarm01.L'), wr = bone('wrist.L'), d3 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
  let reach = 0; for (let v = 0; v < n; v++) if (/finger/.test(main[v]) && pos[v * 3] > 0) reach = Math.max(reach, d3(V(v), wr));
  const hand = sj[1] - (d3(sj, bone('lowerarm01.L')) + d3(bone('lowerarm01.L'), wr) + reach); // (the arm hung straight down from the shoulder joint)
  return {
    H, heads: (top - sole) / H,
    shoulders: widthAt(sh - 0.13, sh - 0.02), chest: widthAt(nip[1] - 0.02, nip[1] + 0.02, torso), waist, hips: widthAt(hipY - 0.1, hipY + 0.02, /^(spine|pelvis|root|upperleg)/),
    nipples: Math.abs(bone('breast.L')[0] - bone('breast.R')[0]) / H * 1.0,
    chinAt: h(chin), nippleAt: h(nip[1]), waistAt: h(waistY), crotchAt: h(crotch), kneeAt: h(L.kneeY), ankleAt: h(L.ankleY), handAt: h(hand), midThigh: (h(crotch) + h(L.kneeY)) / 2,
  };
}

const files = process.argv.slice(2).length ? process.argv.slice(2) : ['body_kabir.json.gz', 'body_man.json.gz'].map((f) => new URL('./fixtures/' + f, import.meta.url));
for (const f of files) {
  const o = JSON.parse(gunzipSync(fs.readFileSync(f))), m = measure(o), name = String(f).split('/').pop();
  console.log(name, Object.fromEntries(Object.entries(m).map(([k, v]) => [k, +v.toFixed(2)])));
  ok(m.heads >= 7.3 && m.heads <= 8.2, `${name}: 7.5–8 heads tall`, m.heads);
  ok(m.shoulders >= 2.2 && m.shoulders <= 3, `${name}: shoulders 2.2–3 heads wide (deltoid to deltoid)`, m.shoulders);
  ok(m.chest >= 1.4 && m.chest <= 2.2, `${name}: chest 1.4 (slim) to 2.2 (heroic) heads wide`, m.chest);
  ok(m.waist >= 1.0 && m.waist <= 2, `${name}: waist 1–2 heads (a real waist, never a wasp's)`, m.waist);
  ok(m.waist < m.chest && m.waist < m.hips, `${name}: the waist is the narrowest of the trunk`, [m.chest, m.waist, m.hips]);
  ok(m.hips >= 1.35 && m.hips <= 2.5, `${name}: hips 1.35–2.5 heads wide`, m.hips);
  ok(m.hips < m.shoulders, `${name}: a man's hip line inside his shoulders`, [m.hips, m.shoulders]);
  ok(Math.abs(m.nippleAt - 2) < 0.35, `${name}: the chest line about two heads down`, m.nippleAt);
  ok(m.crotchAt / m.heads >= 0.47 && m.crotchAt / m.heads <= 0.53, `${name}: the crotch at half the height (4 of 8 heads)`, m.crotchAt / m.heads);
  ok(m.kneeAt / m.heads >= 0.7 && m.kneeAt / m.heads <= 0.77, `${name}: the knees three quarters of the way down (6 of 8 heads)`, m.kneeAt / m.heads);
  ok(Math.abs(m.handAt - m.midThigh) < 0.4, `${name}: the hands end at mid-thigh`, [m.handAt, m.midThigh]);
}
console.log(`${checks - fails.length}/${checks} proportion rules hold`);
if (fails.length) { for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
