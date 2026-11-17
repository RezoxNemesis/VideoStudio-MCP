// ANIMAL HEADS for people with office jobs: a painted lion (a full mane) or a tabby cat, worn by a Stage body in
// place of its own head. The head is painted every frame where the body's head really is, turned with it (the face
// slides across the head as he turns, the far eye narrows), blinks, and opens its mouth with the words.
// animalHead(id, kind, o) → (t, ctx) => item for a sequence's `dyn`. o: { talk: (t) => 0…1, mood: (t) => 'calm' | 'stern' | 'afraid' | 'happy', size }
const V = { mid: (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2] };
const hash = (n) => { const s = Math.sin(n * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };
const KIND = {
  lion: { fur: '#d99a48', dark: '#a8682a', light: '#f2d29a', mane: ['#7a3e16', '#9a5420', '#b86e2c', '#c98436'], iris: '#c8901a', mane0: 1, ear: 0.035 },
  cat: { fur: '#c9a27a', dark: '#7a5a3e', light: '#f1e2cc', mane: null, iris: '#7ab03a', mane0: 0, ear: 0.07, stripes: true },
};
export function animalHead(id, kind = 'lion', o = {}) {
  const K = KIND[kind], S = o.size ?? 1;
  return (t, ctx) => {
    const j = ctx?.poses?.[id]?.j; if (!j) return null;
    const eye = V.mid(j['eye.L'], j['eye.R']), nk = j.neck01 ?? j.head, f0 = [eye[0] - nk[0], 0, eye[2] - nk[2]], fl = Math.hypot(f0[0], f0[2]) || 1, fw = [f0[0] / fl, 0, f0[2] / fl];
    const c = [eye[0] - fw[0] * 0.05, eye[1] - 0.01, eye[2] - fw[2] * 0.05];
    return { p: c, r: 0.6, bias: -0.06, draw(g, cam) {
      const P = cam.project(c); if (P[2] < 0.15) return; const k = (cam.f / P[2]) * S, x = P[0], y = P[1];
      const toCam = [cam.pos[0] - c[0], 0, cam.pos[2] - c[2]], tl = Math.hypot(toCam[0], toCam[2]) || 1, front = (fw[0] * toCam[0] + fw[2] * toCam[2]) / tl;
      const side = cam.project([c[0] + fw[0] * 0.1, c[1], c[2] + fw[2] * 0.1]), turn = Math.max(-1, Math.min(1, (side[0] - x) / (0.1 * k))); // −1…1: where his nose points on screen
      const talk = o.talk ? o.talk(t) : 0, mood = o.mood ? o.mood(t) : 'calm', blink = (t * 0.27 + hash(id.length)) % 1 > 0.965;
      g.save(); g.translate(x, y); g.lineJoin = g.lineCap = 'round';
      // the mane (behind everything), in tufts that stir a little
      if (K.mane) for (let ring = 0; ring < 3; ring++) { const R = (0.25 - ring * 0.035) * k, n = 26 - ring * 4;
        for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2 + ring * 0.13, sway = Math.sin(t * 1.6 + i) * 0.04, rr = R * (0.9 + hash(i * 3.1 + ring) * 0.25);
          g.fillStyle = K.mane[(i + ring) % K.mane.length]; g.beginPath(); g.moveTo(Math.cos(a - 0.22) * R * 0.45, Math.sin(a - 0.22) * R * 0.45 + 0.02 * k);
          g.quadraticCurveTo(Math.cos(a + sway) * rr * 1.05, Math.sin(a + sway) * rr * 1.05 + 0.02 * k, Math.cos(a + 0.3 + sway) * rr * 0.55, Math.sin(a + 0.3 + sway) * rr * 0.55 + 0.02 * k); g.closePath(); g.fill(); } }
      if (front < -0.2) { // seen from behind: only the back of the head
        g.fillStyle = K.mane ? K.mane[1] : K.dark; g.beginPath(); g.ellipse(0, 0, 0.13 * k, 0.15 * k, 0, 0, 7); g.fill(); g.restore(); return; }
      const fx = turn * 0.05 * k, sq = 1 - Math.abs(turn) * 0.25;
      // ears
      for (const sd of [-1, 1]) { const ex = sd * 0.1 * k + fx * 0.4, ey = -0.13 * k, er = K.ear * k;
        g.fillStyle = K.dark; g.beginPath(); if (kind === 'cat') { g.moveTo(ex - sd * er * 0.2 - er * 0.5, ey + er * 0.4); g.lineTo(ex + sd * er * 0.15, ey - er * 1.1); g.lineTo(ex + er * 0.6, ey + er * 0.4); } else g.arc(ex, ey, er, Math.PI, 0); g.fill();
        g.fillStyle = '#e8b0a0'; g.beginPath(); if (kind === 'cat') { g.moveTo(ex - er * 0.3, ey + er * 0.2); g.lineTo(ex + sd * er * 0.1, ey - er * 0.6); g.lineTo(ex + er * 0.35, ey + er * 0.2); } else g.arc(ex, ey + er * 0.1, er * 0.55, Math.PI, 0); g.fill(); }
      // the face: fur, lit from above, the muzzle lighter
      const gr = g.createRadialGradient(fx * 0.5 - 0.03 * k, -0.06 * k, 0.02 * k, fx * 0.3, 0, 0.17 * k); gr.addColorStop(0, K.light); gr.addColorStop(0.45, K.fur); gr.addColorStop(1, K.dark);
      g.fillStyle = gr; g.beginPath(); g.ellipse(fx * 0.3, 0, 0.13 * k * sq, 0.15 * k, 0, 0, 7); g.fill();
      if (K.stripes) { g.strokeStyle = 'rgba(90,60,35,0.55)'; g.lineWidth = 0.012 * k; for (const sx of [-0.03, 0, 0.03]) { g.beginPath(); g.moveTo(fx * 0.3 + sx * k, -0.145 * k); g.lineTo(fx * 0.3 + sx * 1.3 * k, -0.09 * k); g.stroke(); } }
      g.fillStyle = K.light; g.beginPath(); g.ellipse(fx, 0.065 * k, 0.075 * k * sq, 0.06 * k, 0, 0, 7); g.fill();
      // eyes: amber, the far one narrower as he turns; brows by mood
      for (const sd of [-1, 1]) { const ex = fx * 0.8 + sd * 0.052 * k * sq, ey = -0.025 * k, far = sd * turn < 0 ? 1 - Math.abs(turn) * 0.4 : 1, ew = 0.03 * k * far, eh = blink ? 0.003 * k : (mood === 'afraid' ? 0.026 : 0.02) * k;
        g.fillStyle = '#f6efd8'; g.beginPath(); g.ellipse(ex, ey, ew, eh, sd * -0.15, 0, 7); g.fill();
        if (!blink) { g.fillStyle = K.iris; g.beginPath(); g.ellipse(ex + turn * ew * 0.3, ey, Math.min(ew, eh) * 0.85, eh * 0.95, 0, 0, 7); g.fill(); g.fillStyle = '#120a06'; g.beginPath(); g.ellipse(ex + turn * ew * 0.3, ey, ew * (kind === 'cat' ? 0.18 : 0.35), eh * 0.75, 0, 0, 7); g.fill();
          g.fillStyle = '#fff'; g.beginPath(); g.arc(ex + turn * ew * 0.3 - ew * 0.3, ey - eh * 0.35, Math.max(0.6, 0.005 * k), 0, 7); g.fill(); }
        g.strokeStyle = '#3a200e'; g.lineWidth = Math.max(1, 0.008 * k); g.beginPath(); g.ellipse(ex, ey, ew, eh, sd * -0.15, Math.PI * 1.05, Math.PI * 1.95); g.stroke();
        const bt = mood === 'stern' ? 0.25 : mood === 'afraid' ? -0.3 : 0; g.lineWidth = Math.max(1, 0.012 * k); g.strokeStyle = K.dark;
        g.beginPath(); g.moveTo(ex - sd * ew * 1.1, ey - eh - 0.02 * k - sd * bt * 0.01 * k * -1); g.lineTo(ex + sd * ew * 1.0, ey - eh - 0.02 * k + bt * 0.015 * k); g.stroke(); }
      // nose and mouth
      const nx = fx * 1.15, ny = 0.035 * k; g.fillStyle = kind === 'cat' ? '#d9827a' : '#4a2616';
      g.beginPath(); g.moveTo(nx - 0.022 * k, ny - 0.008 * k); g.lineTo(nx + 0.022 * k, ny - 0.008 * k); g.lineTo(nx, ny + 0.016 * k); g.closePath(); g.fill();
      const mo = talk * 0.03 * k; g.strokeStyle = '#3a200e'; g.lineWidth = Math.max(1, 0.007 * k);
      g.beginPath(); g.moveTo(nx, ny + 0.016 * k); g.lineTo(nx, ny + 0.03 * k); g.moveTo(nx - 0.03 * k, ny + 0.035 * k + (mood === 'happy' ? -0.006 * k : 0)); g.quadraticCurveTo(nx - 0.015 * k, ny + 0.045 * k, nx, ny + 0.03 * k); g.quadraticCurveTo(nx + 0.015 * k, ny + 0.045 * k, nx + 0.03 * k, ny + 0.035 * k + (mood === 'happy' ? -0.006 * k : 0)); g.stroke();
      if (mo > 0.5) { g.fillStyle = '#4a1a14'; g.beginPath(); g.ellipse(nx, ny + 0.045 * k + mo * 0.3, 0.022 * k, mo * 0.55, 0, 0, 7); g.fill(); g.fillStyle = '#d8706a'; g.beginPath(); g.ellipse(nx, ny + 0.045 * k + mo * 0.55, 0.013 * k, mo * 0.25, 0, 0, 7); g.fill(); }
      // whiskers
      g.strokeStyle = 'rgba(250,245,230,0.8)'; g.lineWidth = Math.max(0.6, 0.003 * k); for (const sd of [-1, 1]) for (let w = 0; w < 3; w++) { g.beginPath(); g.moveTo(nx + sd * 0.035 * k, ny + 0.03 * k); g.lineTo(nx + sd * (0.12 + w * 0.01) * k, ny + (0.005 + w * 0.025) * k); g.stroke(); }
      g.restore(); } };
  };
}
