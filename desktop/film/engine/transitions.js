// TRANSITIONS: how one shot gives way to the next. A shot opens with one through post.into:
//   post: { into: { type: 'whip', dur: 0.5, dir: 'left', color: '#fff', x: 0.5, y: 0.5, seed: 1 } }
// The shot before is held on its last picture (drawn once) while the new shot runs underneath; k goes 0 → 1 over `dur`.
// post.dissolve: 0.6 is still the same as into: { type: 'dissolve', dur: 0.6 }.
import { createCanvas } from '@napi-rs/canvas';
import { clamp, hash } from './core.js';

export const TRANSITION_TYPES = ['dissolve', 'dip', 'flash', 'whip', 'push', 'slide', 'zoom', 'spin', 'iris', 'clock', 'wipe', 'diagonal', 'blinds', 'ink', 'shape', 'glitch', 'filmBurn', 'lightLeak', 'pixel', 'smash', 'doors', 'shutter', 'grid', 'bars'];

const smooth = (x) => { x = clamp(x); return x * x * x * (x * (x * 6 - 15) + 10); };
const ease = (x) => 1 - Math.pow(1 - clamp(x), 3);
const easeIn = (x) => Math.pow(clamp(x), 3);
const pool = new Map();
function scratch(name, W, H) { const key = `${name}:${W}x${H}`; let c = pool.get(key); if (!c) { c = createCanvas(W, H); pool.set(key, c); } return c; }
const DIRS = { left: [-1, 0], right: [1, 0], up: [0, -1], down: [0, 1] };
const dirOf = (o) => DIRS[o.dir ?? 'left'] ?? DIRS.left;
/** a picture drawn moved and scaled about the middle */
function place(g, img, W, H, dx = 0, dy = 0, s = 1, rot = 0, alpha = 1) { g.save(); g.globalAlpha = alpha; g.translate(W / 2 + dx, H / 2 + dy); if (rot) g.rotate(rot); g.scale(s, s); g.drawImage(img, -W / 2, -H / 2, W, H); g.restore(); }
/** a picture moved by (ox, oy) and smeared along its line of travel (ax, ay): motion blur from many faint copies */
function smear(g, img, W, H, ox, oy, ax, ay, len, n = 8) { for (let i = 0; i < n; i++) { const f = n > 1 ? i / (n - 1) - 0.5 : 0; place(g, img, W, H, ox + ax * f * len, oy + ay * f * len, 1, 0, i === 0 ? 1 : 1 / (i + 1)); } }
/** a picture blurred outward from the middle (zoom blur) */
function zoomBlur(g, img, W, H, s, amt, n = 6) { for (let i = 0; i < n; i++) place(g, img, W, H, 0, 0, s * (1 + amt * i / n), 0, i === 0 ? 1 : 0.5 / i); }
/** draw `img` only inside the path built by `path(g)` */
function inside(g, img, W, H, path, feather = 0) { g.save(); g.beginPath(); path(g); g.closePath(); g.clip(); g.drawImage(img, 0, 0, W, H); g.restore(); void feather; }
function star(g, cx, cy, r, n = 5, inner = 0.45, rot = -Math.PI / 2) { for (let i = 0; i <= n * 2; i++) { const a = rot + (i / (n * 2)) * Math.PI * 2, rr = i % 2 ? r * inner : r; g.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr); } }
function heart(g, cx, cy, r) { g.moveTo(cx, cy + r * 0.9); g.bezierCurveTo(cx - r * 1.6, cy - r * 0.1, cx - r * 0.7, cy - r * 1.3, cx, cy - r * 0.5); g.bezierCurveTo(cx + r * 0.7, cy - r * 1.3, cx + r * 1.6, cy - r * 0.1, cx, cy + r * 0.9); }

const T = {};
T.dissolve = (g, W, H, A, B, k) => { g.drawImage(B, 0, 0); place(g, A, W, H, 0, 0, 1, 0, 1 - smooth(k)); };
T.dip = (g, W, H, A, B, k, o) => { g.drawImage(k < 0.5 ? A : B, 0, 0); g.fillStyle = o.color ?? '#000000'; g.globalAlpha = 1 - Math.abs(k - 0.5) * 2; g.fillRect(0, 0, W, H); g.globalAlpha = 1; };
T.flash = (g, W, H, A, B, k, o) => { g.drawImage(k < 0.35 ? A : B, 0, 0); const a = k < 0.35 ? easeIn(k / 0.35) : 1 - ease((k - 0.35) / 0.65); g.globalCompositeOperation = 'screen'; g.fillStyle = o.color ?? '#ffffff'; g.globalAlpha = a; g.fillRect(0, 0, W, H); g.globalCompositeOperation = 'source-over'; g.globalAlpha = a * 0.6; g.fillRect(0, 0, W, H); g.globalAlpha = 1; };
T.whip = (g, W, H, A, B, k, o) => {
  // a whip pan: the old shot flies out (dir: the way the picture moves), blurred, and the new one flies in behind it
  const [dx, dy] = dirOf(o), D = dx ? W : H, e = k < 0.5 ? easeIn(k * 2) * 0.5 : 0.5 + ease((k - 0.5) * 2) * 0.5, blur = (1 - Math.abs(k - 0.5) * 2) * D * 0.4;
  g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
  smear(g, A, W, H, dx * e * D, dy * e * D, dx, dy, blur, 10); smear(g, B, W, H, dx * (e - 1) * D, dy * (e - 1) * D, dx, dy, blur, 10);
};
T.push = (g, W, H, A, B, k, o) => { const [dx, dy] = dirOf(o), e = smooth(k), D = dx ? W : H; place(g, A, W, H, dx * e * D, dy * e * D); place(g, B, W, H, dx * (e - 1) * D, dy * (e - 1) * D); };
T.slide = (g, W, H, A, B, k, o) => { const [dx, dy] = dirOf(o), e = ease(k), D = dx ? W : H; g.drawImage(A, 0, 0); g.save(); g.shadowColor = 'rgba(0,0,0,0.5)'; g.shadowBlur = 40; place(g, B, W, H, dx * (e - 1) * D, dy * (e - 1) * D); g.restore(); };
T.zoom = (g, W, H, A, B, k) => { if (k < 0.5) zoomBlur(g, A, W, H, 1 + easeIn(k * 2) * 1.2, easeIn(k * 2) * 0.5); else { g.drawImage(B, 0, 0); zoomBlur(g, B, W, H, 1 + (1 - ease((k - 0.5) * 2)) * 0.6, (1 - ease((k - 0.5) * 2)) * 0.4); } const a = 1 - Math.abs(k - 0.5) * 2; g.fillStyle = '#ffffff'; g.globalAlpha = a * a * 0.5; g.fillRect(0, 0, W, H); g.globalAlpha = 1; };
T.spin = (g, W, H, A, B, k, o) => {
  // a spin: the picture twists away and the next twists in, scaled up enough that no corner shows
  const s = o.dir === 'right' ? -1 : 1, first = k < 0.5, e = first ? easeIn(k * 2) : 1 - ease((k - 0.5) * 2), rot = s * e * 1.4 * (first ? 1 : -1), cover = Math.abs(Math.sin(rot)) * (W / H + H / W) * 0.5 + Math.abs(Math.cos(rot));
  for (let i = 0; i < 6; i++) place(g, first ? A : B, W, H, 0, 0, cover * (1 + e * 0.6), rot * (1 + i * 0.03), i ? 0.25 : 1);
};
T.iris = (g, W, H, A, B, k, o) => { const cx = (o.x ?? 0.5) * W, cy = (o.y ?? 0.5) * H, R = Math.hypot(Math.max(cx, W - cx), Math.max(cy, H - cy)) * ease(k); g.drawImage(A, 0, 0); inside(g, B, W, H, (p) => p.arc(cx, cy, Math.max(0.1, R), 0, Math.PI * 2)); if (o.ring !== false && k < 1) { g.beginPath(); g.arc(cx, cy, R, 0, 7); g.lineWidth = 10 * H / 1080; g.strokeStyle = o.color ?? '#ffffff'; g.globalAlpha = 1 - k; g.stroke(); g.globalAlpha = 1; } };
T.clock = (g, W, H, A, B, k, o) => { const cx = W / 2, cy = H / 2, R = Math.hypot(W, H), a0 = -Math.PI / 2, a1 = a0 + smooth(k) * Math.PI * 2 * (o.dir === 'left' ? -1 : 1); g.drawImage(A, 0, 0); inside(g, B, W, H, (p) => { p.moveTo(cx, cy); p.arc(cx, cy, R, a0, a1, o.dir === 'left'); }); };
T.wipe = (g, W, H, A, B, k, o) => {
  // a straight wipe with a soft edge
  const [dx, dy] = dirOf(o), e = smooth(k), D = dx ? W : H, edge = D * 0.12, pos = e * (D + edge) - edge;
  g.drawImage(A, 0, 0); const m = scratch('wipe', W, H), mg = m.getContext('2d'); mg.clearRect(0, 0, W, H); mg.drawImage(B, 0, 0);
  mg.globalCompositeOperation = 'destination-in'; const x0 = dx < 0 ? W - pos : dx > 0 ? pos : 0, y0 = dy < 0 ? H - pos : dy > 0 ? pos : 0;
  const gr = dx ? mg.createLinearGradient(x0 - (dx > 0 ? edge : -edge), 0, x0, 0) : mg.createLinearGradient(0, y0 - (dy > 0 ? edge : -edge), 0, y0);
  gr.addColorStop(0, 'rgba(0,0,0,1)'); gr.addColorStop(1, 'rgba(0,0,0,0)');
  mg.fillStyle = gr; mg.fillRect(0, 0, W, H); mg.globalCompositeOperation = 'source-over';
  g.drawImage(m, 0, 0);
};
T.diagonal = (g, W, H, A, B, k, o) => {
  // the anime cut: a slanted edge with a coloured stripe races across
  const e = smooth(k), slant = H * 0.5, x = -slant - 40 + e * (W + slant * 2 + 80), band = 28 * H / 1080, c = o.color ?? '#ffffff';
  g.drawImage(A, 0, 0); inside(g, B, W, H, (p) => { p.moveTo(0, 0); p.lineTo(x + slant, 0); p.lineTo(x - slant, H); p.lineTo(0, H); });
  if (k > 0 && k < 1) { g.beginPath(); g.moveTo(x + slant, 0); g.lineTo(x + slant + band, 0); g.lineTo(x - slant + band, H); g.lineTo(x - slant, H); g.closePath(); g.fillStyle = c; g.fill(); g.beginPath(); g.moveTo(x + slant + band * 1.6, 0); g.lineTo(x + slant + band * 2.1, 0); g.lineTo(x - slant + band * 2.1, H); g.lineTo(x - slant + band * 1.6, H); g.closePath(); g.fillStyle = o.color2 ?? '#ff3b5c'; g.fill(); }
};
T.blinds = (g, W, H, A, B, k, o) => { const n = o.n ?? 10, vert = o.dir === 'up' || o.dir === 'down'; g.drawImage(A, 0, 0); inside(g, B, W, H, (p) => { for (let i = 0; i < n; i++) { const e = smooth(k * 1.4 - (i / n) * 0.4); if (vert) p.rect((i / n) * W, 0, (W / n) * e, H); else p.rect(0, (i / n) * H, W, (H / n) * e); } }); };
T.ink = (g, W, H, A, B, k, o) => {
  // ink dropped on paper: blots that spread and run together
  const s = o.seed ?? 3, n = 7, R = Math.hypot(W, H) * 0.42;
  g.drawImage(A, 0, 0);
  inside(g, B, W, H, (p) => { for (let i = 0; i < n; i++) { const d = hash(i * 2.7 + s) * 0.35, e = smooth((k - d) / (1 - d)), cx = (0.15 + 0.7 * hash(i * 1.3 + s)) * W, cy = (0.15 + 0.7 * hash(i * 4.1 + s)) * H, r = R * e * (0.6 + 0.6 * hash(i * 3.3)); if (r <= 1) continue; p.moveTo(cx + r, cy); for (let j = 1; j <= 24; j++) { const a = (j / 24) * Math.PI * 2, rr = r * (0.82 + 0.3 * hash(i * 31 + j * 1.7 + s)); p.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr); } } });
  if (k > 0.02 && k < 0.98) { g.save(); g.globalAlpha = 0.25 * (1 - k); g.fillStyle = o.color ?? '#111018'; for (let i = 0; i < 24; i++) { g.beginPath(); g.arc(hash(i * 5.3 + s) * W, hash(i * 7.9 + s) * H, (4 + 18 * hash(i)) * H / 1080 * (0.5 + k), 0, 7); g.fill(); } g.restore(); }
};
T.shape = (g, W, H, A, B, k, o) => { const cx = (o.x ?? 0.5) * W, cy = (o.y ?? 0.5) * H, r = Math.hypot(W, H) * 1.1 * easeIn(k) + 2, kind = o.shape ?? 'star'; g.drawImage(A, 0, 0); inside(g, B, W, H, (p) => { if (kind === 'heart') heart(p, cx, cy, r * 0.8); else if (kind === 'diamond') { p.moveTo(cx, cy - r); p.lineTo(cx + r, cy); p.lineTo(cx, cy + r); p.lineTo(cx - r, cy); } else star(p, cx, cy, r, kind === 'burst' ? 12 : 5, kind === 'burst' ? 0.7 : 0.45, k * 2); }); };
T.glitch = (g, W, H, A, B, k, o) => {
  // the picture tears into shifted bands and colour, and comes back as the new shot
  const img = k < 0.5 ? A : B, amt = 1 - Math.abs(k - 0.5) * 2, f = Math.floor(k * 40) + (o.seed ?? 0), n = 14;
  g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
  for (let i = 0; i < n; i++) { const y0 = Math.floor(hash(f * 3 + i * 7.1) * H), h = Math.ceil((0.02 + 0.12 * hash(i * 2.2 + f)) * H), dx = (hash(i * 9.7 + f) - 0.5) * W * 0.3 * amt; g.drawImage(img, 0, 0); g.save(); g.beginPath(); g.rect(0, y0, W, h); g.clip(); g.drawImage(img, dx, 0); g.restore(); }
  g.save(); g.globalCompositeOperation = 'screen'; g.globalAlpha = amt * 0.55; g.fillStyle = '#ff004c'; g.fillRect(-amt * 18, 0, W, H); g.fillStyle = '#00e5ff'; g.globalAlpha = amt * 0.35; g.fillRect(amt * 18, 0, W, H); g.restore();
  if (amt > 0.6) { g.fillStyle = 'rgba(255,255,255,0.8)'; for (let i = 0; i < 6; i++) g.fillRect(0, hash(f + i * 3.3) * H, W, 2 + 4 * hash(i)); }
};
T.filmBurn = (g, W, H, A, B, k, o) => {
  // film that catches in the gate and burns: orange holes bloom to white, the next shot comes up through them
  g.drawImage(k < 0.5 ? A : B, 0, 0); const a = 1 - Math.abs(k - 0.5) * 2, s = o.seed ?? 7;
  g.save(); g.globalCompositeOperation = 'screen';
  for (let i = 0; i < 6; i++) { const cx = hash(i * 3.1 + s) * W, cy = hash(i * 5.7 + s) * H, r = (0.25 + 0.6 * hash(i * 1.9 + s)) * W * (0.3 + a); const gr = g.createRadialGradient(cx, cy, 0, cx, cy, r); gr.addColorStop(0, `rgba(255,250,230,${a})`); gr.addColorStop(0.35, `rgba(255,170,60,${0.9 * a})`); gr.addColorStop(0.7, `rgba(200,40,10,${0.6 * a})`); gr.addColorStop(1, 'rgba(120,0,0,0)'); g.fillStyle = gr; g.fillRect(0, 0, W, H); }
  g.restore(); g.fillStyle = '#fff8ea'; g.globalAlpha = Math.pow(a, 3) * 0.85; g.fillRect(0, 0, W, H); g.globalAlpha = 1;
};
T.lightLeak = (g, W, H, A, B, k) => { T.dissolve(g, W, H, A, B, k); const a = Math.sin(clamp(k) * Math.PI), x = (-0.3 + 1.6 * k) * W; g.save(); g.globalCompositeOperation = 'screen'; const gr = g.createRadialGradient(x, H * 0.4, 0, x, H * 0.4, W * 0.7); gr.addColorStop(0, `rgba(255,236,190,${0.95 * a})`); gr.addColorStop(0.4, `rgba(255,140,60,${0.7 * a})`); gr.addColorStop(1, 'rgba(255,60,90,0)'); g.fillStyle = gr; g.fillRect(0, 0, W, H); g.restore(); };
T.pixel = (g, W, H, A, B, k) => { const img = k < 0.5 ? A : B, a = 1 - Math.abs(k - 0.5) * 2, bs = Math.max(1, Math.round(2 + a * a * 70 * H / 1080)), sw = Math.max(1, Math.round(W / bs)), sh = Math.max(1, Math.round(H / bs)), s = scratch('pix', sw, sh), sg = s.getContext('2d'); sg.imageSmoothingEnabled = true; sg.drawImage(img, 0, 0, sw, sh); g.save(); g.imageSmoothingEnabled = false; g.drawImage(s, 0, 0, sw, sh, 0, 0, W, H); g.restore(); };
T.smash = (g, W, H, A, B, k, o) => {
  // the anime smash cut: the new shot slams in at scale, white flash, speed lines, a jolt
  const e = ease(k / 0.45), s = 1 + (1 - e) * 0.45, j = k < 0.5 ? (1 - k * 2) * 18 * H / 1080 : 0;
  g.drawImage(A, 0, 0); place(g, B, W, H, (hash(Math.floor(k * 50)) - 0.5) * j, (hash(Math.floor(k * 50) + 4) - 0.5) * j, s, 0, Math.min(1, k / 0.12));
  const fl = k < 0.25 ? 1 - k / 0.25 : 0; g.fillStyle = o.color ?? '#ffffff'; g.globalAlpha = fl * 0.85; g.fillRect(0, 0, W, H); g.globalAlpha = 1;
  if (k < 0.7) { const a = 1 - k / 0.7, cx = W / 2, cy = H / 2, R = Math.hypot(W, H) * 0.6; g.save(); g.fillStyle = '#ffffff'; g.globalAlpha = 0.75 * a; g.beginPath(); for (let i = 0; i < 90; i++) { const ang = hash(i * 1.37 + Math.floor(k * 30)) * Math.PI * 2, w = 0.004 + 0.012 * hash(i * 2.1), r0 = R * (0.45 + 0.25 * hash(i * 3.3)); g.moveTo(cx + Math.cos(ang) * r0, cy + Math.sin(ang) * r0); g.lineTo(cx + Math.cos(ang - w) * R * 1.3, cy + Math.sin(ang - w) * R * 1.3); g.lineTo(cx + Math.cos(ang + w) * R * 1.3, cy + Math.sin(ang + w) * R * 1.3); g.closePath(); } g.fill(); g.restore(); }
};
T.doors = (g, W, H, A, B, k, o) => { const e = smooth(k), vert = o.dir === 'up' || o.dir === 'down'; g.drawImage(B, 0, 0); g.save(); g.shadowColor = 'rgba(0,0,0,0.6)'; g.shadowBlur = 30; if (vert) { g.save(); g.beginPath(); g.rect(0, -e * H / 2, W, H / 2); g.clip(); g.drawImage(A, 0, -e * H / 2); g.restore(); g.save(); g.beginPath(); g.rect(0, H / 2 + e * H / 2, W, H / 2); g.clip(); g.drawImage(A, 0, e * H / 2); g.restore(); } else { g.save(); g.beginPath(); g.rect(-e * W / 2, 0, W / 2, H); g.clip(); g.drawImage(A, -e * W / 2, 0); g.restore(); g.save(); g.beginPath(); g.rect(W / 2 + e * W / 2, 0, W / 2, H); g.clip(); g.drawImage(A, e * W / 2, 0); g.restore(); } g.restore(); };
T.shutter = (g, W, H, A, B, k, o) => { const c = o.color ?? '#000000', a = k < 0.5 ? easeIn(k * 2) : 1 - ease((k - 0.5) * 2); g.drawImage(k < 0.5 ? A : B, 0, 0); g.fillStyle = c; g.fillRect(0, 0, W, a * H / 2 + 1); g.fillRect(0, H - a * H / 2 - 1, W, a * H / 2 + 1); };
T.grid = (g, W, H, A, B, k, o) => { const cols = o.cols ?? 8, rows = Math.max(1, Math.round(cols * H / W)), cw = W / cols, ch = H / rows; g.drawImage(A, 0, 0); for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) { const d = ((r + c) / (rows + cols)) * 0.55 + hash(r * 7 + c * 3) * 0.1, e = ease((k - d) / 0.35); if (e <= 0) continue; g.save(); g.beginPath(); const x = c * cw + cw / 2, y = r * ch + ch / 2; g.rect(x - (cw / 2) * e - 0.5, y - (ch / 2) * e - 0.5, cw * e + 1, ch * e + 1); g.clip(); g.drawImage(B, 0, 0); g.restore(); } };
T.bars = (g, W, H, A, B, k, o) => { const n = o.n ?? 6, c = [o.color ?? '#ff3b5c', o.color2 ?? '#ffd23a', '#2a7aff', '#ffffff']; g.drawImage(k < 0.55 ? A : B, 0, 0); for (let i = 0; i < n; i++) { const d = i * 0.06, e = k < 0.55 ? ease((k - d) / 0.3) : 1 - ease((k - 0.55 - d) / 0.3), y = (i / n) * H; g.fillStyle = c[i % c.length]; g.fillRect(i % 2 ? W - e * W : 0, y, e * W, H / n + 1); } };

/** Draw the frame at progress k (0…1) of a transition from picture A (the shot before) to B (this shot) onto g. */
export function transition(g, W, H, A, B, k, o = {}) {
  const fn = T[o.type ?? 'dissolve']; if (!fn) throw new Error(`No transition "${o.type}". Transitions: ${TRANSITION_TYPES.join(', ')}.`);
  g.save(); try { fn(g, W, H, A, B, clamp(k), o); } finally { g.restore(); }
}
/** A copy of a canvas kept for the length of a frame (the shot as drawn, before the transition goes over it). */
export function snapshot(src, W, H) { const c = scratch('snap', W, H), cg = c.getContext('2d'); cg.clearRect(0, 0, W, H); cg.drawImage(src, 0, 0, W, H); return c; }
