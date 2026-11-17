// Ready-made 3D assets for films (from the concept tests, Nov 2026). See API.md "Ready-made assets".
const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const circle = (g, x, y, r) => { g.beginPath(); g.arc(x, y, Math.max(0.1, r), 0, Math.PI * 2); };
/** An LED-dot face painted at cx, cy, h tall. f: { mood: happy|tired|sad|shock|angry|open, talk 0-1, look [x, y], blink } */
export function ledFace(g, cx, cy, h, f, t) {
  const mood = f.mood ?? 'happy', talk = clamp(f.talk ?? 0), lk = f.look ?? [0, 0], blink = f.blink ?? (((t * 0.29) % 1) > 0.96 ? 0.15 : 1);
  const sp = h * 0.095, dot = sp * 0.72;
  g.save();
  const on = (u, v, a = 1) => { g.fillStyle = `rgba(230,255,250,${a})`; g.shadowColor = 'rgba(120,255,230,0.9)'; g.shadowBlur = dot * 2.2; circle(g, cx + u * sp, cy + v * sp, dot / 2); g.fill(); };
  // eyes: 3×3 dot blocks shaped by mood
  const EY = { happy: [[-1, 0], [0, -1], [1, 0]], tired: [[-1, 0.4], [0, 0.4], [1, 0.4]], sad: [[-1, -0.6], [0, -0.2], [1, 0.3]], shock: [[-1, -1], [0, -1], [1, -1], [-1, 0], [1, 0], [-1, 1], [0, 1], [1, 1]], angry: [[-1, -0.8], [0, -0.3], [1, 0.2], [-1, 0.6], [0, 0.6], [1, 0.6]], open: [[-1, -1], [0, -1], [1, -1], [-1, 0], [0, 0], [1, 0], [-1, 1], [0, 1], [1, 1]] };
  for (const sd of [-1, 1]) {
    const ex = sd * 4 + lk[0] * 0.8, ey = -1.6 + lk[1] * 0.5;
    let pts = EY[mood] ?? EY.open; if (mood === 'sad' || mood === 'angry') pts = pts.map(([u, v]) => [u * sd, v]);
    if (blink < 0.5) pts = [[-1, 0], [0, 0], [1, 0]];
    for (const [u, v] of pts) on(ex + u, ey + v, 0.95);
  }
  // mouth: a line that opens with the voice
  const open = Math.round(talk * 2.2), w = mood === 'shock' ? 1 : 3;
  for (let u = -w; u <= w; u++) { const curve = mood === 'happy' ? -Math.abs(u) * 0.12 + 0.4 : mood === 'sad' || mood === 'tired' ? Math.abs(u) * 0.12 - 0.2 : 0; on(u, 1.9 + curve, 0.95); if (open > 0 && Math.abs(u) < w) { for (let k = 1; k <= open; k++) on(u, 1.9 + curve + k * 0.85, 0.7); } }
  g.restore();
}
