// A WALK OR A RUN BY NUMBERS — what a body's joints say about its gait, measured the way a coach reads a runner:
// how many steps a minute, how long a step, how much of the time both feet are off the ground, how far the trunk leans,
// how high the knee comes, whether the arms swing against the legs, how far apart the hands go.
// (A real runner, read frame by frame from reference footage: about 180 steps a minute, in the air between every two
// steps, the trunk 8–10° forward from the ankles, the knee driven toward hip height, elbows bent and close to the ribs.)
//   node devtests/gait.mjs <film> <seq> <actor> [from] [to]      prints it for a solved performance.

const med = (a) => { const b = [...a].sort((x, y) => x - y); return b.length ? b[b.length >> 1] : 0; };
const r2 = (x, n = 2) => Math.round(x * 10 ** n) / 10 ** n;
const corr = (a, b) => {
  const n = a.length, ma = a.reduce((x, y) => x + y, 0) / n, mb = b.reduce((x, y) => x + y, 0) / n;
  let sab = 0, saa = 0, sbb = 0; for (let i = 0; i < n; i++) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) ** 2; sbb += (b[i] - mb) ** 2; }
  return saa > 1e-12 && sbb > 1e-12 ? sab / Math.sqrt(saa * sbb) : 0;
};

/**
 * samples: one entry a frame, each { root, neck, footL, footR, kneeL, kneeR, wristL, wristR } as [x, y, z] in metres
 * (toeL / toeR — the ball of the foot — are used for the ground contact when given: an ankle rises before the toe leaves).
 * → { kind: 'run' | 'walk' | 'still', speed (m/s), cadence (steps a minute), usual (what is usual for these legs),
 *     stepLength (m), flight (share of the time with both feet off the ground), lean (° forward), kneeLift (highest
 *     knee as a share of hip height), armLeg (−1…1: 1 = each arm swings with the opposite leg), wrists: [nearest,
 *     farthest] (m), bounce (m the hips go up and down), steps: [seconds], notes: [what a coach would say is wrong] }
 */
export function gaitReport(S, fps, o = {}) {
  const N = S.length, dt = 1 / fps;
  const none = { kind: 'still', speed: 0, cadence: 0, usual: 0, stepLength: 0, flight: 0, lean: 0, kneeLift: 0, armLeg: 0, wrists: [0, 0], bounce: 0, steps: [], notes: [] };
  if (N < 8) return none;
  const a = S[0].root, b = S[N - 1].root, dx = b[0] - a[0], dz = b[2] - a[2], dist = Math.hypot(dx, dz), speed = dist / ((N - 1) * dt);
  let hip = 0, lo = Infinity, hi = -Infinity; for (const s of S) { hip += s.root[1] / N; lo = Math.min(lo, s.root[1]); hi = Math.max(hi, s.root[1]); }
  if (speed < 0.3) return none;
  const ux = dx / dist, uz = dz / dist, along = (p) => p[0] * ux + p[2] * uz;
  // a foot stands when it is low and hardly moves over the ground
  const stands = (key, alt) => {
    const P = S.map((s) => s[key] ?? s[alt]); let low = Infinity; for (const p of P) low = Math.min(low, p[1]);
    const slow = Math.max(0.5, 0.2 * speed), on = P.map((p, i) => { const q = P[Math.min(N - 1, i + 1)], r = P[Math.max(0, i - 1)], n = Math.min(N - 1, i + 1) - Math.max(0, i - 1); return p[1] < low + 0.06 && Math.hypot(q[0] - r[0], q[2] - r[2]) / (n * dt) < slow; });
    const gap = Math.max(1, Math.round(0.04 * fps));               // a blink of less than 0.04 s is still the same stand
    for (let i = 1; i < N - 1; i++) if (!on[i] && on[i - 1]) { let k = i; while (k < N && !on[k]) k++; if (k < N && k - i <= gap) for (let q = i; q < k; q++) on[q] = true; }
    return on;
  };
  const L = stands('toeL', 'footL'), R = stands('toeR', 'footR'), strikes = [];
  for (let i = 1; i < N; i++) { if (L[i] && !L[i - 1]) strikes.push(i * dt); if (R[i] && !R[i - 1]) strikes.push(i * dt); }
  strikes.sort((x, y) => x - y);
  const gaps = strikes.slice(1).map((t, i) => t - strikes[i]).filter((g) => g > 0.08), stepT = med(gaps), cadence = stepT ? 60 / stepT : 0;
  let air = 0, n = 0; const i0 = strikes.length ? Math.round(strikes[0] * fps) : 0, i1 = strikes.length ? Math.round(strikes[strikes.length - 1] * fps) : N - 1;
  for (let i = i0; i <= Math.min(N - 1, i1); i++) { n++; if (!L[i] && !R[i]) air++; }
  const flight = n ? air / n : 0, kind = flight >= 0.04 ? 'run' : 'walk';
  let lean = 0, knee = 0, wn = Infinity, wf = 0; const aL = [], aR = [], fL = [], fR = [];
  for (const s of S) {
    const r = along(s.root);
    lean += (Math.atan2(along(s.neck) - r, s.neck[1] - s.root[1]) * 180) / Math.PI / N;
    knee = Math.max(knee, s.kneeL[1], s.kneeR[1]);
    const d = Math.hypot(s.wristL[0] - s.wristR[0], s.wristL[1] - s.wristR[1], s.wristL[2] - s.wristR[2]); wn = Math.min(wn, d); wf = Math.max(wf, d);
    aL.push(along(s.wristL) - r); aR.push(along(s.wristR) - r); fL.push(along(s.footL) - r); fR.push(along(s.footR) - r);
  }
  const armLeg = (corr(aL, fR) + corr(aR, fL)) / 2, running = kind === 'run' || speed >= 2.5;
  // what is usual: 180 steps a minute for a runner with hips 0.9 m up, 115 for a walker; short legs step faster (a pendulum)
  const usual = (running ? 180 : 115) * Math.sqrt(0.9 / Math.max(0.3, hip)), notes = [];
  if (running) {
    if (flight < 0.04) notes.push(`no moment in the air: at ${r2(speed, 1)} m/s both feet leave the ground between steps (a run without it is a fast walk)`);
    if (lean < 1) notes.push(`the trunk does not lean forward (${r2(lean, 0)}°): a runner leans from the ankles, more the faster he goes`);
    if (lean > 35) notes.push(`the trunk leans ${r2(lean, 0)}° forward: a sprinter leans 15 to 30°`);
  }
  if (cadence && (cadence > usual * (speed > 6 ? 1.6 : 1.45) || cadence < usual * 0.72)) notes.push(`${Math.round(cadence)} steps a minute where about ${Math.round(usual)} is usual for these legs`);
  if (strikes.length >= 4 && armLeg < -0.3) notes.push('each arm swings with the leg on the same side: the left arm should go forward with the RIGHT leg');
  else if (strikes.length >= 4 && armLeg < 0.3) notes.push('the arms do not swing against the legs (left arm forward with the right leg)');
  if (wf > 0.8) notes.push(`the hands go ${r2(wf)} m apart: a ${running ? 'runner' : 'walker'}'s arms stay close to the ribs`);
  return { kind, speed: r2(speed), cadence: r2(cadence, 1), usual: r2(usual, 0), stepLength: r2(speed * stepT), flight: r2(flight), lean: r2(lean, 1), kneeLift: r2(knee / hip), armLeg: r2(armLeg), wrists: [r2(wn), r2(wf)], bounce: r2(hi - lo), steps: strikes.map((t) => r2(t)), notes };
}
