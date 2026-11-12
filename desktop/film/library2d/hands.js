// Hands: a drawn hand set for cut-out and frame-by-frame animation — palm and five three-jointed fingers posed by curls,
// ink outline, cel shading, knuckle lines and nails. 16 poses (relaxed … victory) in two skin tones, left and right by flip.
import { Doc, smooth, ellipse, capsule, shade, mix, INK, f } from './svg.js';

const POSES = {
  relaxed: { curl: [[10, 10], [18, 20, 14], [22, 26, 18], [28, 32, 22], [34, 36, 26]], spread: 0.05, thumb: 0.2 },
  open: { curl: [[0, 0], [2, 3, 2], [2, 3, 2], [3, 4, 3], [4, 5, 4]], spread: 0.35, thumb: 0 },
  spread: { curl: [[0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]], spread: 0.7, thumb: -0.2 },
  reach: { curl: [[8, 6], [10, 14, 10], [12, 16, 12], [16, 20, 14], [20, 22, 16]], spread: 0.25, thumb: 0.1 },
  grasp: { curl: [[30, 30], [50, 50, 40], [52, 54, 42], [54, 56, 44], [56, 58, 46]], spread: 0.1, thumb: 0.6 },
  claw: { curl: [[20, 30], [30, 60, 50], [30, 62, 52], [32, 62, 52], [34, 60, 50]], spread: 0.45, thumb: 0.3 },
  point: { curl: [[40, 40], [0, 2, 2], [86, 96, 70], [88, 98, 72], [86, 96, 70]], spread: 0, thumb: 0.9 },
  fingergun: { curl: [[0, 0], [0, 2, 2], [86, 96, 70], [88, 98, 72], [86, 96, 70]], spread: 0, thumb: -0.3 },
  fist: { curl: [[40, 50], [86, 96, 70], [88, 98, 72], [88, 98, 72], [86, 96, 70]], spread: -0.15, thumb: 1 },
  halffist: { curl: [[25, 30], [50, 60, 40], [55, 62, 44], [58, 64, 46], [60, 64, 46]], spread: -0.05, thumb: 0.7 },
  pinch: { curl: [[30, 34], [40, 46, 30], [20, 26, 18], [26, 30, 20], [30, 34, 24]], spread: 0.15, thumb: 0.8 },
  peace: { curl: [[40, 40], [0, 2, 2], [0, 2, 2], [88, 98, 72], [86, 96, 70]], spread: 0.3, thumb: 0.9 },
  thumbsup: { curl: [[-10, -10], [86, 96, 70], [88, 98, 72], [88, 98, 72], [86, 96, 70]], spread: -0.15, thumb: -0.8 },
  stop: { curl: [[0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]], spread: 0.08, thumb: 0.15 },
  wave: { curl: [[4, 4], [4, 6, 4], [4, 6, 4], [6, 8, 6], [8, 10, 8]], spread: 0.5, thumb: 0 },
  rockon: { curl: [[40, 40], [0, 2, 2], [88, 98, 72], [88, 98, 72], [0, 2, 2]], spread: 0.25, thumb: 0.9 },
};
function drawHand(d, pose, skin) {
  const ink = INK, cx = 256, wristY = 440, palmTop = 250;
  const base = cel(d, skin);
  // palm
  d.ink(smooth([[cx - 74, wristY], [cx - 92, 330], [cx - 84, palmTop], [cx + 84, palmTop - 6], [cx + 96, 330], [cx + 70, wristY]], true, 0.7), base, 5, ink);
  // fingers: index (1) … little (4) side by side along the top of the palm; each bends toward the viewer (shortens) and down
  const lens = [[0, 0], [70, 46, 34], [78, 50, 36], [72, 46, 34], [56, 38, 28]], widths = [0, 20, 21, 20, 17];
  for (let i = 4; i >= 1; i--) {
    const fx = cx + (i - 2.5) * -38 * 1 + 0, fy = palmTop + (i === 1 || i === 4 ? 8 : 0), spread = (i - 2.5) * pose.spread * -0.35;
    let a = -Math.PI / 2 + spread, x = fx, y = fy;
    const segs = [];
    for (let j = 0; j < 3; j++) {
      const curl = (pose.curl[i][j] ?? 0) * Math.PI / 180, L = lens[i][j] * Math.cos(curl * 0.8); // bending toward the lens foreshortens
      a += curl * 0.35; const nx = x + Math.cos(a) * L, ny = y + Math.sin(a) * L + Math.sin(curl) * lens[i][j] * 0.55; segs.push([[x, y], [nx, ny]]); x = nx; y = ny;
    }
    const w = widths[i];
    for (let j = 2; j >= 0; j--) d.ink(capsule(segs[j][0], segs[j][1], w * (1 - j * 0.06), w * (0.92 - j * 0.06)), base, 4.5, ink);
    const tip = segs[2][1], prev = segs[2][0], ang = Math.atan2(tip[1] - prev[1], tip[0] - prev[0]);
    const curled = (pose.curl[i][1] ?? 0) > 60;
    if (!curled) d.path(ellipse(tip[0] - Math.cos(ang) * 8, tip[1] - Math.sin(ang) * 8, w * 0.55, w * 0.45), mix(skin, '#ffffff', 0.5), { stroke: shade(skin, -0.35), sw: 2, op: 0.9 });
    for (let j = 0; j < 2; j++) { const p = segs[j][1]; d.path(`M${f(p[0] - w * 0.4)} ${f(p[1])} q${f(w * 0.4)} 6 ${f(w * 0.8)} 0`, 'none', { stroke: shade(skin, -0.38), sw: 2.5 }); }
  }
  // thumb: from the lower side of the palm, across it when the hand closes
  const t = pose.thumb, tx = cx + 82, ty = 380, a0 = -Math.PI * (0.25 + t * 0.35);
  const t1 = [tx + Math.cos(a0) * 62, ty + Math.sin(a0) * 62], a1 = a0 - 0.4 - t * 0.6, t2 = [t1[0] + Math.cos(a1) * 50, t1[1] + Math.sin(a1) * 50];
  d.ink(capsule([tx, ty], t1, 26, 22), base, 4.5, ink); d.ink(capsule(t1, t2, 22, 19), base, 4.5, ink);
  d.path(`M${cx - 40} 360 Q${cx} 380 ${cx + 30} 350`, 'none', { stroke: shade(skin, -0.32), sw: 3 }); // palm crease
  d.ink(smooth([[cx - 78, wristY - 4], [cx + 72, wristY - 4], [cx + 76, 500], [cx - 80, 500]], true, 0.4), base, 5, ink); // the wrist
}
const cel = (d, c) => d.lin([[0, shade(c, 0.1)], [0.55, c], [0.56, shade(c, -0.18)], [1, shade(c, -0.24)]], 0.1, 0.05, 0.9, 0.95);
export function hands() {
  const out = [];
  for (const [tone, skin] of [['light', '#f2cba8'], ['deep', '#9a6844']]) for (const [k, pose] of Object.entries(POSES)) {
    const d = new Doc(512, 512); drawHand(d, pose, skin);
    out.push({ id: `hand_${k}_${tone}`, name: `Hand — ${k} (${tone})`, cat: 'hands', sub: k, tags: ['hand', 'gesture', 'pose', k, tone], w: 512, h: 512, svg: d.svg(), anchor: [0.5, 0.95] });
  }
  return out;
}
