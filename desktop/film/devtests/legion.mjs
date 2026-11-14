// An army of drawn figures: node devtests/legion.mjs        (exit 1 when a rule is broken)
// Thousands of soldiers, each a drawing of one of a few figures made at the size and from the side the lens sees
// him: every man stands at his own place on the ground, the near ones large and covering the far, the far ones
// paling into the air, all of them in step when they march.
import { createCanvas } from '@napi-rs/canvas';
import { makeCam } from '../engine/camera.js';
import { Scene } from '../engine/scene.js';
import { setLook } from '../engine/look.js';
import { legion } from '../engine/legion.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(3) : JSON.stringify(got)}`); };
setLook('anime');
const W = 640, H = 360, HAZE = [0xd9, 0xc9, 0xc4];
const scene = () => new Scene({ sun: [0.3, 0.6, 0.7], fog: { color: '#d9c9c4', dist: 220, max: 0.92 } });
/** a figure for the tests: a plain slab of a man 1.8 m tall and half a metre wide, in a colour of its own; it notes how it was asked to be drawn */
function slab(color, log = []) {
  return { h: 1.8, top: 1.9, half: 0.4, poses: 8, log,
    paint(g, cam, i, o) {
      log.push({ i, az: Math.atan2(cam.pos[0] - o.at[0], cam.pos[2] - o.at[2]), el: Math.atan2(cam.pos[1] - 0.9, Math.hypot(cam.pos[0] - o.at[0], cam.pos[2] - o.at[2])), yaw: o.yaw });
      const a = cam.project([o.at[0], 0, o.at[2]]), b = cam.project([o.at[0], 1.8, o.at[2]]), w = 0.25 * cam.f / a[2];
      g.fillStyle = color; g.fillRect(a[0] - w, b[1], w * 2, a[1] - b[1]);
    } };
}
function shot(items, camO, t = 0, sc = scene()) {
  const cam = makeCam(camO, W, H), cv = createCanvas(W, H), g = cv.getContext('2d');
  const list = items.map((it) => [cam.toCam(typeof it.p === 'function' ? it.p(t) : it.p)[2] + (it.bias ?? 0), it]).sort((a, b) => b[0] - a[0]);
  for (const [z, it] of list) it.draw(g, cam, sc, t, z);
  const d = g.getImageData(0, 0, W, H).data;
  const px = (x, y) => { const i = (Math.round(y) * W + Math.round(x)) * 4; return [d[i], d[i + 1], d[i + 2], d[i + 3]]; };
  const box = () => { let x0 = W, y0 = H, x1 = -1, y1 = -1; for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (d[(y * W + x) * 4 + 3] > 128) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; } return x1 < 0 ? null : { x0, y0, x1, y1 }; };
  return { cam, d, px, box };
}
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const CAM = { pos: [0, 1.6, 0], target: [0, 1.0, -10], fov: 40 };

// ---- 1. a man stands at his place, at his size
{
  const s = shot(legion({ men: [{ x: 0, z: -10 }], yaw: 0, figures: [slab('#b02020')] }), CAM), b = s.box(), feet = s.cam.project([0, 0, -10]), head = s.cam.project([0, 1.8, -10]);
  ok(!!b, 'a man of the legion is drawn', b);
  ok(b && Math.abs(b.y1 - feet[1]) <= 2.5, 'his feet are on the ground at his place', b && [b.y1, feet[1]]);
  ok(b && Math.abs((b.y1 - b.y0) - (feet[1] - head[1])) <= (feet[1] - head[1]) * 0.1, 'he is as tall in the picture as a man there is', b && [b.y1 - b.y0, feet[1] - head[1]]);
  ok(b && Math.abs((b.x0 + b.x1) / 2 - feet[0]) <= 2.5, 'and stands where he stands, not beside it', b && [(b.x0 + b.x1) / 2, feet[0]]);
  const far = shot(legion({ men: [{ x: 3, z: -40 }], yaw: 0, figures: [slab('#b02020')] }), CAM), fb = far.box(), ff = far.cam.project([3, 0, -40]);
  ok(fb && Math.abs(fb.y1 - ff[1]) <= 2 && Math.abs((fb.x0 + fb.x1) / 2 - ff[0]) <= 2 && (fb.y1 - fb.y0) < (b.y1 - b.y0) * 0.35, 'a far man is small, and at his place too', fb && [fb, ff]);
}

// ---- 2. near men cover far ones; far men pale into the air
{
  const s = shot(legion({ men: [{ x: 0, z: -9, fig: 0 }, { x: 0.15, z: -8, fig: 1 }], yaw: 0, figures: [slab('#b02020'), slab('#2030b0')] }), CAM), p = s.cam.project([0.12, 1.0, -8]);
  ok(s.px(p[0], p[1])[2] > 120 && s.px(p[0], p[1])[0] < 90, 'the nearer man covers the farther', s.px(p[0], p[1]));
  const one = (z) => { const q = shot(legion({ men: [{ x: 0, z }], yaw: 0, figures: [slab('#b02020')] }), { ...CAM, target: [0, 1, z] }), c = q.cam.project([0, 1.0, z]); return q.px(c[0], c[1]); };
  ok(dist(one(-300), HAZE) < dist(one(-10), HAZE) * 0.45, 'a man far off has paled into the air', [one(-10), one(-300)]);
}

// ---- 3. thousands of men from a few drawings
{
  const log = [], men = []; for (let r = 0; r < 50; r++) for (let c = 0; c < 40; c++) men.push({ x: (c - 20) * 1.1, z: -8 - r * 1.2 });
  const items = legion({ men, yaw: 0, figures: [slab('#b02020', log)] }), s = shot(items, { pos: [0, 6, 0], target: [0, 0, -30], fov: 50 });
  let n = 0; for (let i = 3; i < s.d.length; i += 4) if (s.d[i] > 128) n++;
  ok(n > W * H * 0.2, 'two thousand men fill the field', n / (W * H));
  ok(log.length > 0 && log.length <= 90, 'and are drawn from a few drawings of the figure, not two thousand', log.length);
  const again = shot(items, { pos: [0, 6, 0], target: [0, 0, -30], fov: 50 });
  let same = true; for (let i = 0; i < s.d.length; i++) if (s.d[i] !== again.d[i]) { same = false; break; }
  ok(same, 'the same moment is drawn the same', same);
  // far ranks are still there, though a man is only a mark
  const farMen = []; for (let c = 0; c < 200; c++) farMen.push({ x: (c - 100) * 1.1, z: -900 });
  const fs = shot(legion({ men: farMen, yaw: 0, figures: [slab('#301010')] }), { pos: [0, 30, 0], target: [0, 0, -900], fov: 30 }), fb = fs.box();
  ok(!!fb && fb.x1 - fb.x0 > 100, 'a rank near the horizon is still a line of marks', fb);
}

// ---- 4. a man is drawn from the side the lens sees him
{
  const view = (camPos, yaw = 0) => { const log = []; shot(legion({ men: [{ x: 0, z: -10 }], yaw, figures: [slab('#b02020', log)] }), { pos: camPos, target: [0, 1, -10], fov: 40 }); return log[0]; };
  const front = view([0, 1.6, 0]), back = view([0, 1.6, -20]), side = view([10, 1.6, -10]), high = view([0, 14, -2]);
  const near = (a, b, tol) => { let d = a - b; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; return Math.abs(d) <= tol; };
  ok(front && near(front.az, 0, 0.14), 'from in front he is drawn from in front', front);
  ok(back && near(back.az, Math.PI, 0.14), 'from behind, from behind', back);
  ok(side && near(side.az, Math.PI / 2, 0.14), 'from his side, from that side', side);
  ok(high && high.el > 0.7 && front.el < 0.2, 'from above, from above', [front?.el, high?.el]);
  ok(front && front.yaw === 0 && view([0, 1.6, 0], 90).yaw === 90, 'and he faces the way the legion faces', [front?.yaw]);
}

// ---- 5. marching
{
  const log = [], men = []; for (let r = 0; r < 6; r++) for (let c = 0; c < 10; c++) men.push({ x: (c - 5) * 1.1, z: -14 - r * 1.2 });
  const L = legion({ men, yaw: 0, figures: [slab('#b02020', log)], state: (t) => ({ advance: t * 1.3, phase: t / 1.1 }) });
  const a = shot(L, CAM, 0), i0 = new Set(log.map((q) => q.i)); log.length = 0;
  const b = shot(L, CAM, 2), i1 = new Set(log.map((q) => q.i));
  ok(a.box() && b.box() && b.box().y1 > a.box().y1 + 3, 'a marching legion comes on', [a.box()?.y1, b.box()?.y1]);
  ok(i0.size === 1, 'its men are in step: one drawing of the stride serves them all at one moment', [...i0]);
  const c = shot(L, CAM, 2 + 1.1 * 0.5); void c;
  ok(i1.size >= 1 && [...new Set(log.map((q) => q.i))].some((i) => !i0.has(i)), 'and the stride moves on with time', [[...i0], [...new Set(log.map((q) => q.i))]]);
}

if (fails.length) { console.log(`legion: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`legion: ${checks} rules hold`);
