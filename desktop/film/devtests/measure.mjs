// MEASURING A FIGURE WITH LINES: node devtests/measure.mjs <picture> <out.jpg> [--views front,side,back] [--json out.json] [--head H]
// The way a model sheet is studied before a character is built (the owner, 16 Nov 2026: "by multiple green and red
// horizontal and vertical line method, you have to do the proper measurement"). The picture holds one or more standing
// figures on a plain ground. For each figure the tool finds its outline, lays GREEN horizontal lines at the heights that
// matter (top of the head, chin, shoulders, armpits, hem of the top, where the legs part, hem of the trousers, soles) and
// RED vertical lines at the widths (middle, head, neck, shoulders, chest, hips, a leg, the feet), and reads every one
// in HEADS (the head's height with its hair = 1), so two figures of any size can be compared number by number.
// The same tool is then run on our own character drawn from the same sides; what differs is what to correct.
// A reference is MEASURED, never traced: only its proportions are taken, the character built from them is our own.
import fs from 'node:fs';
import { createCanvas, loadImage } from '@napi-rs/canvas';

const a = process.argv.slice(2), opt = (n, d) => { const i = a.indexOf('--' + n); if (i < 0) return d; const v = a[i + 1]; a.splice(i, 2); return v; };
const views = String(opt('views', 'front')).split(','), jsonOut = opt('json', null), headGiven = +opt('head', 0);
const [src, out] = a;
if (!src || !out) { console.error('node devtests/measure.mjs <picture> <out.jpg> [--views front,side,back] [--json out.json]'); process.exit(1); }
const img = await loadImage(src), W = img.width, H = img.height, cv = createCanvas(W, H), g = cv.getContext('2d');
g.drawImage(img, 0, 0); const px = g.getImageData(0, 0, W, H).data;
const lum = (i) => (px[i * 4] * 0.299 + px[i * 4 + 1] * 0.587 + px[i * 4 + 2] * 0.114), at = (x, y) => y * W + x;
// the ground: what the corners are made of. A pixel belongs to the ground when it is as light as it and can be reached
// from the picture's edge without crossing a darker line (a white shirt inside a drawn outline is not ground).
// (the ground is read a little inside the picture: a drawn frame darkens towards its edge)
const ring = [[], [], []]; { const ix = Math.round(W * 0.03), iy = Math.round(H * 0.03), take = (i) => { for (let c = 0; c < 3; c++) ring[c].push(px[i * 4 + c]); }; for (let x = ix; x < W - ix; x += 7) { take(at(x, iy)); take(at(x, H - 1 - iy)); } for (let y = iy; y < H - iy; y += 7) { take(at(ix, y)); take(at(W - 1 - ix, y)); } }
const BG = ring.map((c) => c.sort((p, q) => p - q)[c.length >> 1]), bg = BG[0] * 0.299 + BG[1] * 0.587 + BG[2] * 0.114, EDGE = Math.max(3, Math.round(Math.min(W, H) * 0.012));
// as light as the ground and of its colour (a ground that shades off a little towards the floor is still ground; a drawn line is not)
const lightEnough = (i) => Math.abs(px[i * 4] - BG[0]) < 44 && Math.abs(px[i * 4 + 1] - BG[1]) < 44 && Math.abs(px[i * 4 + 2] - BG[2]) < 44;
const wall = new Uint8Array(W * H); // a line, grown by a pixel, so a hair-fine gap in an outline does not let the ground in
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (!lightEnough(at(x, y))) for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) { const xx = x + dx, yy = y + dy; if (xx >= 0 && yy >= 0 && xx < W && yy < H) wall[at(xx, yy)] = 1; }
const ground = new Uint8Array(W * H), stack = [];
for (let x = 0; x < W; x++) stack.push(at(x, 0), at(x, H - 1)); for (let y = 0; y < H; y++) stack.push(at(0, y), at(W - 1, y));
const rim = (i) => { const x = i % W, y = (i / W) | 0; return x < EDGE || y < EDGE || x >= W - EDGE || y >= H - EDGE; };
while (stack.length) { const i = stack.pop(); if (ground[i] || (wall[i] && !rim(i))) continue; ground[i] = 1; const x = i % W, y = (i / W) | 0; if (x > 0) stack.push(i - 1); if (x < W - 1) stack.push(i + 1); if (y > 0) stack.push(i - W); if (y < H - 1) stack.push(i + W); }
const fig = (x, y) => !ground[at(x, y)] && !(wall[at(x, y)] && lightEnough(at(x, y)) && false);
// the figures: stretches of columns that hold something
const col = new Int32Array(W); for (let x = 0; x < W; x++) for (let y = 0; y < H; y++) if (fig(x, y)) col[x]++;
const spans = []; for (let x = 0, s = -1; x <= W; x++) { const on = x < W && col[x] > 2; if (on && s < 0) s = x; if (!on && s >= 0) { if (x - s > W * 0.04) spans.push([s, x - 1]); s = -1; } }

const runsOf = (y, x0, x1) => { const r = []; for (let x = x0, s = -1; x <= x1 + 1; x++) { const on = x <= x1 && fig(x, y); if (on && s < 0) s = x; if (!on && s >= 0) { if (x - s >= 3) r.push([s, x - 1]); s = -1; } } return r; };
const skin = (i) => { const r = px[i * 4], gg = px[i * 4 + 1], b = px[i * 4 + 2]; return r > 150 && r - b > 28 && r > gg && gg > b - 6; };
if (process.env.MEASURE_DEBUG) console.error("ground", bg, "spans", JSON.stringify(spans));
const BGdark = bg < 80;
const results = [];
spans.forEach(([x0, x1], k) => {
  const view = views[k] ?? views.at(-1);
  let top = -1, bot = -1; for (let y = 0; y < H; y++) { const on = runsOf(y, x0, x1).length > 0; if (on && top < 0) top = y; if (on) bot = y; }
  const hgt = bot - top + 1, R = []; for (let y = top; y <= bot; y++) R.push(runsOf(y, x0, x1));
  const Wd = (y) => { const r = R[y - top]; return r.length ? r.at(-1)[1] - r[0][0] + 1 : 0; }, L = (y) => R[y - top][0]?.[0] ?? x0, Rt = (y) => R[y - top].at(-1)?.[1] ?? x1;
  // the head: the widest place in the top sixth; the neck: the narrowest place under it, before the shoulders
  let headW = 0, headY = top; for (let y = top; y < top + hgt * 0.1; y++) if (Wd(y) > headW) { headW = Wd(y); headY = y; }
  let neckY = headY, neckW = 1e9; for (let y = headY; y < top + hgt * 0.24; y++) if (Wd(y) < neckW) { neckW = Wd(y); neckY = y; }
  const cx = Math.round((L(neckY) + Rt(neckY)) / 2);
  // the chin: going up the middle from the neck, the first drawn line (front); from the side: where the front of the face steps back to the neck
  let chin = -1;
  if (view === 'front') {
    // (seen from the front the jaw's point lies INSIDE the neck's outline, so the outline is narrowest above it: the chin is
    //  found on the middle line — up from the top's neckline, through the skin of the neck, to the first drawn line)
    let collar = -1; for (let y = neckY; y < top + hgt * 0.3; y++) { if (!skin(at(cx, y)) && !skin(at(cx, y + 3)) && !skin(at(cx, y + 6))) { collar = y; break; } }
    let y = (collar > 0 ? collar : neckY + Math.round(hgt * 0.03)) - 1; while (y > headY && !skin(at(cx, y))) y--; // over the neckline's own line
    const neckSkin = y;
    for (; y > headY; y--) if (lum(at(cx, y)) < 110) { chin = y; break; }
    // (a face drawn without a line under the chin: the chin is where the neck's shadow ends — the largest step to lighter skin going up)
    if (chin < 0 || neckSkin - chin > hgt * 0.06) { let best = 0; chin = -1; for (let q = neckSkin - 2; q > neckSkin - hgt * 0.05; q--) { const d = lum(at(cx, q - 2)) - lum(at(cx, q + 2)); if (d > best) { best = d; chin = q; } } }
  }
  if (view === 'side') { // the face looks one way: that side's outline steps in under the jaw
    const fr = (y) => Rt(y), bk = (y) => L(y), faceRight = (fr(headY) - cx) > (cx - bk(headY)) * 0.8; const edge = faceRight ? Rt : (y) => -L(y);
    let best = 0; for (let y = headY + 4; y < neckY; y++) { const d = edge(y - 3) - edge(y + 3); if (d > best) { best = d; chin = y; } }
  }
  const headH = headGiven || (chin > 0 ? chin - top : 0);
  // the shoulders: under the neck the outline widens fast and then goes straight down — the line is where it stops widening
  let shY = neckY; for (let y = neckY + 2; y < top + hgt * 0.34; y++) { if (Wd(y + 6) - Wd(y) < 3 && Wd(y) > neckW * 2.2) { shY = y; break; } }
  let shW = 0; for (let y = shY; y < shY + hgt * 0.05; y++) shW = Math.max(shW, Wd(y));
  // the body's own run (the one that holds the middle), the arms beside it
  const body = (y) => R[y - top].find((r) => r[0] <= cx && r[1] >= cx) ?? null;
  let pit = -1; for (let y = shY; y < top + hgt * 0.5; y++) { const r = R[y - top]; if (r.length >= 2 && body(y) && (view !== 'front' || r.length >= 3)) { pit = y; break; } }
  // the hem of the top and of the trousers: down the middle of the body (then of one leg) where light cloth turns dark, and dark turns light
  // the colour of a stretch of a line (its drawn lines left out), and where it changes most going down: the hem of the top
  // (cloth to cloth), the hem of the trousers (cloth to shoe), the end of a sleeve (cloth to skin)
  const meanOf = (y, q) => { if (!q || y < top || y > bot) return null; let r = 0, gg = 0, bb = 0, n = 0; for (let x = q[0] + 3; x <= q[1] - 3; x++) { const i = at(x, y); r += px[i * 4]; gg += px[i * 4 + 1]; bb += px[i * 4 + 2]; n++; } return n > 2 ? [r / n, gg / n, bb / n] : null; };
  const stepAt = (runAt, y0, y1) => { let best = 0, by = -1; for (let y = Math.round(y0); y < y1; y++) { const A = meanOf(y - 4, runAt(y - 4)), B = meanOf(y + 4, runAt(y + 4)), A2 = meanOf(y - 9, runAt(y - 9)), B2 = meanOf(y + 9, runAt(y + 9)); if (!A || !B || !A2 || !B2) continue; const d = Math.min(Math.hypot(A[0] - B[0], A[1] - B[1], A[2] - B[2]), Math.hypot(A2[0] - B2[0], A2[1] - B2[1], A2[2] - B2[2])); if (d > best) { best = d; by = y; } } return best > 38 ? by : -1; };
  let hem = stepAt((y) => body(y), shY + hgt * 0.1, top + hgt * 0.62);
  // where the legs part: the first line under the hem where ground shows between two runs near the middle
  let split = -1; for (let y = (hem > 0 ? hem : shY) + 10; y < bot - 5; y++) { const r = R[y - top]; if (r.length >= 2 && !body(y)) { split = y; break; } if (r.length >= 2) { const m = r.filter((q) => q[1] < cx || q[0] > cx); if (m.length >= 2 && r.every((q) => !(q[0] <= cx && q[1] >= cx))) { split = y; break; } } }
  // a leg: the run nearest the middle on one side, low down; the trouser hem: where its dark cloth ends
  const legAt = (y) => { const r = R[y - top]; return r.length ? r.reduce((b, q) => (Math.abs((q[0] + q[1]) / 2 - cx) < Math.abs((b[0] + b[1]) / 2 - cx) || (b[0] <= cx && b[1] >= cx && r.length > 1) ? q : b)) : null; };
  let cuff = split > 0 ? stepAt((y) => legAt(y), split + hgt * 0.15, bot - hgt * 0.012) : -1;
  // the hands: the lowest point of the runs beside the body; the sleeve: on an arm, where cloth gives way to skin
  let hand = -1, sleeve = -1;
  if (pit > 0) for (let y = Math.min(bot, top + Math.round(hgt * 0.75)); y > pit; y--) { const r = R[y - top]; if (r.length >= 2 && r.some((q) => q[1] < cx - headW * 0.4 || q[0] > cx + headW * 0.4) && (body(y) || r.length >= 3)) { hand = y; break; } }
  if (pit > 0 && view !== 'side') { const arm = (y) => { const q = R[y - top]?.[0]; return q && !(q[0] <= cx && q[1] >= cx) ? q : null; }; sleeve = stepAt(arm, pit, hand > 0 ? hand - hgt * 0.05 : pit + hgt * 0.25); if (sleeve < 0) sleeve = pit; }
  const bw = (y) => { const b = body(y); return b ? b[1] - b[0] + 1 : 0; };
  const chestY = pit > 0 ? pit + Math.round(hgt * 0.015) : shY + Math.round(hgt * 0.08), hipY = hem > 0 ? hem + Math.round(hgt * 0.03) : -1;
  let waistW = 1e9, waistY = -1; if (pit > 0 && hem > 0) for (let y = pit + 4; y < hem; y++) if (bw(y) && bw(y) < waistW) { waistW = bw(y); waistY = y; }
  const footY = bot - Math.round(hgt * 0.012), feet = R[footY - top], legY = cuff > 0 ? cuff - Math.round(hgt * 0.02) : -1, leg = legY > 0 ? legAt(legY) : null, thigh = split > 0 ? legAt(split + Math.round(hgt * 0.03)) : null;
  const m = {
    view, box: [x0, top, x1, bot], px: { height: hgt, head: headH },
    y: { top, chin, neck: neckY, shoulder: shY, armpit: pit, sleeve, waist: waistY, hem, legsPart: split, hand, cuff, sole: bot },
    w: { head: headW, neck: neckW, shoulders: shW, chest: bw(chestY), waist: waistW < 1e9 ? waistW : 0, hips: hipY > 0 ? bw(hipY) : 0, thigh: thigh ? thigh[1] - thigh[0] + 1 : 0, legAtHem: leg ? leg[1] - leg[0] + 1 : 0, feet: feet.length ? feet.at(-1)[1] - feet[0][0] + 1 : 0, all: Math.max(...Array.from({ length: hgt }, (_, i) => Wd(top + i))) },
    cx,
  };
  results.push(m);
});
// in heads: every figure of a sheet is the same person at the same size, so the head measured on the front view serves the others
const headPx = headGiven || results.find((r) => r.view === 'front' && r.px.head)?.px.head || results.find((r) => r.px.head)?.px.head;
const r2 = (x) => Math.round(x * 100) / 100;
for (const m of results) {
  const h = headPx, top = m.y.top;
  m.heads = { tall: r2(m.px.height / h), ...Object.fromEntries(Object.entries(m.y).filter(([k, v]) => k !== 'top' && v > 0).map(([k, v]) => [k, r2((v - top) / h)])) };
  m.wide = Object.fromEntries(Object.entries(m.w).filter(([, v]) => v > 0).map(([k, v]) => [k, r2(v / h)]));
}
// the lines
const k = Math.max(1, W / 1200), GREEN = '#17a34a', RED = '#dc2626';
g.lineWidth = 1.4 * k; g.font = `600 ${Math.round(12 * k)}px Inter, sans-serif`; g.textBaseline = 'bottom';
for (const m of results) {
  const [x0, , x1] = m.box, pad = 10 * k;
  let lastY = -99, shift = 0;
  for (const [name, y] of Object.entries(m.y).filter(([, v]) => v >= 0).sort((p, q) => p[1] - q[1])) {
    g.strokeStyle = GREEN; g.beginPath(); g.moveTo(x0 - pad, y + 0.5); g.lineTo(x1 + pad, y + 0.5); g.stroke();
    shift = y - lastY < 13 * k ? shift + 1 : 0; lastY = y; const text = `${name} ${m.heads[name] ?? 0}`, tw = g.measureText(text).width, room = x1 + pad + 2 + tw < W;
    g.fillStyle = '#ffffff'; g.globalAlpha = 0.8; g.fillRect(room ? x1 + pad + 1 : x1 + pad - tw - 3, y - 9 * k + shift * 13 * k, tw + 3, 13 * k); g.globalAlpha = 1;
    g.fillStyle = GREEN; g.textAlign = room ? 'left' : 'right'; g.fillText(text, room ? x1 + pad + 2 : x1 + pad - 2, y + 5 * k + shift * 13 * k);
  }
  // head-height rule down the left: a tick every head
  g.strokeStyle = GREEN; for (let i = 0; i * headPx <= m.px.height + 1; i++) { const y = m.y.top + i * headPx; g.beginPath(); g.moveTo(x0 - pad - 14 * k, y); g.lineTo(x0 - pad, y); g.stroke(); g.textAlign = 'right'; g.fillText(String(i), x0 - pad - 16 * k, y + 6 * k); }
  const V = (x, y0, y1) => { g.strokeStyle = RED; g.beginPath(); g.moveTo(x + 0.5, y0); g.lineTo(x + 0.5, y1); g.stroke(); };
  V(m.cx, m.y.top - pad, m.y.sole + pad);
  const span = (y, wname) => { if (y < 0 || !m.w[wname]) return; const half = m.w[wname] / 2; V(Math.round(m.cx - half), y - 16 * k, y + 16 * k); V(Math.round(m.cx + half), y - 16 * k, y + 16 * k); };
  span(m.y.top + Math.round(m.px.head * 0.5), 'head'); span(m.y.neck, 'neck'); span(m.y.shoulder, 'shoulders'); span(m.y.waist, 'waist'); span(m.y.hem, 'hips');
  g.fillStyle = RED; g.textAlign = 'center'; g.fillText(m.view, m.cx, m.y.top - pad - 2);
}
fs.writeFileSync(out, cv.toBuffer('image/jpeg', 92));
if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify(results, null, 1));
for (const m of results) console.log(`${m.view}: ${m.heads.tall} heads tall (head ${headPx} px)\n  down from the top, in heads: ${JSON.stringify(m.heads)}\n  widths, in heads: ${JSON.stringify(m.wide)}`);
