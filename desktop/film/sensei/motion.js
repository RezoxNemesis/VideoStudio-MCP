// Sensei looks at bodies: feet that skate, bodies that pass through themselves or each other, hands that let go of what
// they hold, joints bent past what a person can do, sudden pops, arms that hang dead while walking or talking, twins,
// gestures without arcs, weight outside the feet. Every number is measured on the solved performance, frame by frame.
import { issue } from './rules.js';
import { screenAt, onScreen } from './collect.js';
import { sub, dot, len, dist, norm, mid, lerp3, flat, angleBetween, yawBetween, segSeg, capsulesOf, SELF_PAIRS, partName, bodyFrames, robust, runs, mergeRuns, hull2, polyDist, cm, fmt } from './geo.js';

const side = (s) => (s === 'L' ? 'left' : 'right');
const HELD = ['sword', 'spear', 'staff', 'axe', 'torch', 'club', 'knife', 'dagger', 'bow', 'hammer', 'whip'];
const t2 = (x) => Math.round(x * 100) / 100;

/** severity lowered when the viewer can hardly see it (off the picture, tiny figure, foot out of frame) */
function seen(D, a, i0, i1, pt, sev) {
  let visN = 0, onN = 0, sz = 0;
  for (let i = i0; i <= i1; i++) {
    if (!a.vis[i]) continue; visN++; sz = Math.max(sz, a.size[i]);
    const p = typeof pt === 'function' ? pt(i) : pt; if (onScreen(screenAt(D, i, p), 0.02)) onN++;
  }
  const n = i1 - i0 + 1;
  if (!visN || onN / n < 0.15) return null; // nobody sees it: not a finding
  const order = ['nice', 'should', 'must']; let k = order.indexOf(sev);
  if (sz < 0.1) k -= 1; // a small figure in the distance
  if (onN / n < 0.4) k -= 1;
  return k < 0 ? 'nice' : order[k];
}
const whereAt = (D, i, pt) => { const s = screenAt(D, i, pt); return s && onScreen(s, 0.05) ? { x: +s.x.toFixed(3), y: +s.y.toFixed(3) } : null; };

export function motionChecks(D, B) {
  const out = [];
  const push = (o) => { if (o.sev) out.push(issue({ shot: D.id, seq: D.seq, ...o })); };
  const N = D.N, dt = D.dt;
  for (const a of D.actors) {
    const anyVis = a.vis.some((v) => v); if (!anyVis) continue;
    const k = a.k;
    // ---------- feet: skating, sinking, floating (on the solved performance, before it is placed in the world)
    for (const [s, bit] of [['L', 1], ['R', 2]]) {
      const flags = Array.from({ length: N }, (_, i) => !!(a.C[i] & bit));
      for (const [i0, i1] of runs(flags)) {
        if (i1 - i0 < 4) continue;
        const pnt = (i) => { const f = a.L[i][`foot.${s}`], t = a.L[i][`toe2-1.${s}`] ?? f; return [(f[0] + t[0]) / 2, 0, (f[2] + t[2]) / 2]; };
        const base = pnt(i0); let slide = 0, worst = i0;
        for (let i = i0; i <= i1; i++) { const d = dist(pnt(i), base); if (d > slide) { slide = d; worst = i; } }
        if (slide > B.footSlide[0] * k) {
          const sev = seen(D, a, i0, i1, (i) => a.J[i][`foot.${s}`], slide > B.footSlide[1] * k ? 'must' : 'should');
          push({ sev, cat: 'motion', kind: 'foot-slide', actor: a.id, part: `${side(s)} foot`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[worst]), pt: a.J[worst][`foot.${s}`], where: whereAt(D, worst, a.J[worst][`foot.${s}`]),
            value: +slide.toFixed(3), limit: B.footSlide[0], unit: 'm',
            msg: `${a.id}'s ${side(s)} foot skates ${cm(slide)} while it should stand still (limit ${cm(B.footSlide[0])}).`,
            fix: `In motion(T) make the step land: match the walk's speed to the clip (speed / gait), shorten the blend into the next action, or pin the step with a pose/keys overlay; stage.trace around ${t2(a.t0 + D.tw[worst] * a.rate)} s of bake '${a.bake}' shows the feet.` });
        }
      }
      // sinking into / floating above the ground while planted
      let sink = [], float = [];
      for (let i = 0; i < N; i++) {
        if (!(a.C[i] & bit)) { sink.push(false); float.push(false); continue; }
        const toe = a.L[i][`toe2-1.${s}`] ?? a.L[i][`foot.${s}`], ank = a.L[i][`foot.${s}`];
        sink.push(toe[1] < -B.sink * k || ank[1] < 0.015 * k);
        float.push(Math.min(toe[1], ank[1] - 0.065 * k) > B.float * k);
      }
      for (const [flags, kind] of [[sink, 'sink'], [float, 'float']]) for (const [i0, i1] of mergeRuns(runs(flags), 6)) {
        if (i1 - i0 < 5) continue;
        const mi = (i0 + i1) >> 1, f = a.J[mi][`foot.${s}`];
        const sev = seen(D, a, i0, i1, (i) => a.J[i][`foot.${s}`], 'should');
        const h = a.L[mi][`toe2-1.${s}`]?.[1] ?? 0;
        push({ sev, cat: 'motion', kind: kind === 'sink' ? 'foot-sinks' : 'foot-floats', actor: a.id, part: `${side(s)} foot`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[mi]), pt: f, where: whereAt(D, mi, f), value: +h.toFixed(3), unit: 'm',
          msg: kind === 'sink' ? `${a.id}'s ${side(s)} foot goes ${cm(-h)} into the ground while standing on it.` : `${a.id}'s ${side(s)} foot hovers ${cm(h)} above the ground while it should stand on it.`,
          fix: kind === 'sink' ? `Check the actor's ground (actors: ground) or the clip's height; for stairs give ground: (pose) => height.` : `The body is placed too high: check xf.pos[1] / ground for this actor, or the clip blends out of a jump too early.` });
      }
    }
    // ---------- per frame: own body passing through itself, wrists, neck, knees
    const selfHits = new Map(); // pair → flags with depth
    const wristBad = { L: [], R: [] }, neckBad = [], twistBad = [], kneeBad = { L: [], R: [] };
    for (let i = 0; i < N; i++) {
      const j = a.J[i];
      if (!a.vis[i]) { for (const s of ['L', 'R']) { wristBad[s].push(0); kneeBad[s].push(0); } neckBad.push(0); twistBad.push(0); continue; }
      const caps = capsulesOf(j, k), byG = {};
      for (const c of caps) (byG[c.group] ??= []).push(c);
      for (const [ga, gb, only] of SELF_PAIRS) {
        let best = null;
        for (const ca of byG[ga] ?? []) { if (only && ca.name !== only) continue; for (const cb of byG[gb] ?? []) {
          const r = segSeg(ca.a, ca.b, cb.a, cb.b), depth = ca.r + cb.r - r.d;
          if (depth > B.selfDepth * k && (!best || depth > best.depth)) best = { depth, a: ca, b: cb, at: r.at };
        } }
        const key = `${ga}|${gb}`; if (!selfHits.has(key)) selfHits.set(key, new Array(N).fill(null));
        selfHits.get(key)[i] = best;
      }
      for (const s of ['L', 'R']) {
        const el = j[`lowerarm01.${s}`], wr = j[`wrist.${s}`], kn = j[`finger3-1.${s}`];
        // a hand round a grip is turned by what it holds (the drawn fingers close on it): judged by the grip instead
        const g = wr && kn ? lerp3(wr, kn, 0.6) : null, holds = g && Object.entries(a.props[i] ?? {}).some(([n, p]) => HELD.includes(n) && p.visible && dist(p.pos, g) < 0.14 * k);
        wristBad[s].push(el && wr && kn && !holds ? angleBetween(sub(wr, el), sub(kn, wr)) : 0);
        const hp = j[`upperleg01.${s}`], kk = j[`lowerleg01.${s}`], an = j[`foot.${s}`], to = j[`toe2-1.${s}`];
        if (hp && kk && an && to) {
          const ha = sub(an, hp), u = Math.max(0, Math.min(1, dot(sub(kk, hp), ha) / Math.max(1e-6, dot(ha, ha)))), onLine = [hp[0] + ha[0] * u, hp[1] + ha[1] * u, hp[2] + ha[2] * u];
          const fwd = norm(flat(sub(to, an))), off = dot(sub(kk, onLine), fwd);
          const straight = angleBetween(sub(hp, kk), sub(an, kk)) > 165;
          kneeBad[s].push(straight && off < -B.kneeBack * k ? -off : 0);
        } else kneeBad[s].push(0);
      }
      const F = bodyFrames(j);
      neckBad.push(Math.abs(yawBetween(F.chest, F.head)));
      twistBad.push(Math.abs(yawBetween(F.hips, F.chest)));
    }
    for (const [key, arr] of selfHits) {
      for (const [i0, i1] of mergeRuns(runs(arr.map((x) => !!x)), 4)) {
        if ((i1 - i0) * dt < 0.12) continue;
        let w = i0, dmax = 0; for (let i = i0; i <= i1; i++) if (arr[i] && arr[i].depth > dmax) { dmax = arr[i].depth; w = i; }
        const h = arr[w]; if (!h) continue;
        const long = (i1 - i0) * dt;
        const sev = seen(D, a, i0, i1, (i) => arr[i]?.at ?? h.at, dmax > 2 * B.selfDepth * k && long > 0.3 ? 'must' : dmax > 1.2 * B.selfDepth * k ? 'should' : 'nice');
        push({ sev, cat: 'anatomy', kind: 'self-intersect', actor: a.id, part: `${partName(h.a)} / ${partName(h.b)}`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: h.at, where: whereAt(D, w, h.at),
          value: +dmax.toFixed(3), limit: B.selfDepth, unit: 'm',
          msg: `${a.id}'s ${partName(h.a)} goes ${cm(dmax)} into the ${partName(h.b)} for ${t2(long)} s — the body passes through itself.`,
          fix: `Move the ${partName(h.a)} clear: a keys overlay on hand.${h.a.side ?? 'R'} that keeps it ${cm(dmax + 0.03)} further out, a lean, or another clip for ${t2(D.ts[i0])}–${t2(D.ts[i1])} s.` });
      }
    }
    for (const s of ['L', 'R']) {
      for (const [i0, i1] of mergeRuns(runs(wristBad[s].map((x) => x > B.wrist)), 4)) {
        if (i1 - i0 < 4) continue;
        let w = i0; for (let i = i0; i <= i1; i++) if (wristBad[s][i] > wristBad[s][w]) w = i;
        const p = a.J[w][`wrist.${s}`];
        push({ sev: seen(D, a, i0, i1, (i) => a.J[i][`wrist.${s}`], 'should'), cat: 'anatomy', kind: 'wrist-limit', actor: a.id, part: `${side(s)} wrist`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p),
          value: Math.round(wristBad[s][w]), limit: B.wrist, unit: '°', msg: `${a.id}'s ${side(s)} wrist bends ${Math.round(wristBad[s][w])}° — a human wrist stops near ${B.wrist}°; the hand looks broken.`,
          fix: `Ease the hand's IK key (keys on hand.${s}) closer to the forearm line, or let the elbow do the work: move the target ${cm(0.08)} toward the body.` });
      }
      for (const [i0, i1] of mergeRuns(runs(kneeBad[s].map((x) => x > 0)), 4)) {
        if (i1 - i0 < 5) continue;
        let w = i0; for (let i = i0; i <= i1; i++) if (kneeBad[s][i] > kneeBad[s][w]) w = i;
        const p = a.J[w][`lowerleg01.${s}`];
        push({ sev: seen(D, a, i0, i1, (i) => a.J[i][`lowerleg01.${s}`], 'should'), cat: 'anatomy', kind: 'knee-back', actor: a.id, part: `${side(s)} knee`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p),
          value: +kneeBad[s][w].toFixed(3), unit: 'm', msg: `${a.id}'s ${side(s)} knee bends backwards (${cm(kneeBad[s][w])} behind the line from hip to ankle).`,
          fix: `Keep a soft knee: lower the hips 2–3 cm (lean or pose) or use a stance with bent knees for ${t2(D.ts[i0])}–${t2(D.ts[i1])} s.` });
      }
    }
    for (const [arr, lim, kind, part, what] of [[neckBad, B.neck, 'neck-twist', 'neck', 'head turns'], [twistBad, B.spineTwist, 'spine-twist', 'waist', 'chest turns']]) {
      for (const [i0, i1] of mergeRuns(runs(arr.map((x) => x > lim)), 6)) {
        if (i1 - i0 < 6) continue;
        let w = i0; for (let i = i0; i <= i1; i++) if (arr[i] > arr[w]) w = i;
        const p = a.J[w][kind === 'neck-twist' ? 'head' : 'spine03'];
        push({ sev: seen(D, a, i0, i1, p, 'should'), cat: 'anatomy', kind, actor: a.id, part, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p), value: Math.round(arr[w]), limit: lim, unit: '°',
          msg: `${a.id}'s ${what} ${Math.round(arr[w])}° away from the ${kind === 'neck-twist' ? 'chest' : 'hips'} (an owl turn; people stop near ${lim}°).`,
          fix: kind === 'neck-twist' ? `Turn the whole body toward what it looks at (a 'face' in the go action, or a turn) and let the head do the last 40°.` : `Turn the feet/hips with the chest (face on the action) instead of twisting the waist.` });
      }
    }
    // ---------- held things: the hand lets go; the blade cuts through its own body
    const propNames = new Set(); for (let i = 0; i < N; i++) for (const n in a.props[i] ?? {}) propNames.add(n);
    for (const name of propNames) {
      if (!HELD.includes(name)) continue;
      const dHand = new Float32Array(N).fill(-1), through = new Array(N).fill(null);
      for (let i = 0; i < N; i++) {
        const pr = a.props[i]?.[name]; if (!pr || !pr.visible || !a.vis[i]) continue;
        const j = a.J[i]; let best = 1e9;
        for (const s of ['L', 'R']) { const g = lerp3(j[`wrist.${s}`], j[`finger3-1.${s}`] ?? j[`wrist.${s}`], 0.6); best = Math.min(best, dist(g, pr.pos)); }
        dHand[i] = best;
        if (name === 'sword' || name === 'spear' || name === 'knife' || name === 'dagger') {
          const q = pr.q, ax = [2 * (q[0] * q[1] - q[3] * q[2]), 1 - 2 * (q[0] * q[0] + q[2] * q[2]), 2 * (q[1] * q[2] + q[3] * q[0])];
          const tip = [pr.pos[0] + ax[0] * 0.75 * k, pr.pos[1] + ax[1] * 0.75 * k, pr.pos[2] + ax[2] * 0.75 * k], base = [pr.pos[0] + ax[0] * 0.15 * k, pr.pos[1] + ax[1] * 0.15 * k, pr.pos[2] + ax[2] * 0.15 * k];
          for (const c of capsulesOf(j, k)) { if (c.group !== 'torso' && c.group !== 'head' && c.group !== 'legL' && c.group !== 'legR') continue; const r = segSeg(base, tip, c.a, c.b); const depth = c.r + 0.012 - r.d; if (depth > 0.04 * k && (!through[i] || depth > through[i].depth)) through[i] = { depth, part: partName(c), at: r.at }; }
        }
      }
      const vals = Array.from(dHand).filter((x) => x >= 0), heldShare = vals.filter((x) => x < 0.1 * k).length / Math.max(1, vals.length);
      if (heldShare > 0.4) {
        for (const [i0, i1] of mergeRuns(runs(Array.from(dHand, (x) => x > B.gripDrift * k)), 6)) {
          if (i1 - i0 < 4) continue;
          // only a slip between held frames (a throw or a sheathing ends with the thing far away for good)
          const before = dHand.slice(Math.max(0, i0 - 30), i0).some((x) => x >= 0 && x < 0.1 * k), after = dHand.slice(i1 + 1, i1 + 31).some((x) => x >= 0 && x < 0.1 * k);
          if (!before || !after) continue;
          let w = i0; for (let i = i0; i <= i1; i++) if (dHand[i] > dHand[w]) w = i;
          const p = a.props[w][name].pos;
          push({ sev: seen(D, a, i0, i1, p, 'should'), cat: 'anatomy', kind: 'grip-lost', actor: a.id, part: name, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p), value: +dHand[w].toFixed(3), limit: B.gripDrift, unit: 'm',
            msg: `${a.id}'s ${name} floats ${cm(dHand[w])} away from the hand for ${t2((i1 - i0) * dt)} s — nothing holds it.`, fix: `Keep the ${name} in the hand through the move (the prop's hand in the fight/clip), or hide it while it is not held.` });
        }
      }
      for (const [i0, i1] of mergeRuns(runs(through.map((x) => !!x)), 4)) {
        if (i1 - i0 < 3) continue;
        let w = i0; for (let i = i0; i <= i1; i++) if (through[i] && (!through[w] || through[i].depth > through[w].depth)) w = i;
        const h = through[w]; if (!h) continue;
        push({ sev: seen(D, a, i0, i1, h.at, 'should'), cat: 'anatomy', kind: 'blade-through-self', actor: a.id, part: `${name} / ${h.part}`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: h.at, where: whereAt(D, w, h.at), value: +h.depth.toFixed(3), unit: 'm',
          msg: `${a.id}'s own ${name} passes through their ${h.part} for ${t2((i1 - i0) * dt)} s.`, fix: `Angle the ${name} away from the body in this part of the move (a wrist keys overlay), or swap the clip.` });
      }
    }
    // ---------- pops: a joint jumps in one frame far more than around it (blend seams, broken keys) — one finding per moment
    const popHits = [];
    for (const jn of ['root', 'head', 'wrist.L', 'wrist.R', 'foot.L', 'foot.R']) {
      // a pop is a jump of position in one frame: that frame's speed is far above the speed just before and after it
      // (a fast swing builds its speed over several frames; a heel strike slows down over several frames)
      const vel = new Float32Array(N);
      for (let i = 1; i < N; i++) { const h = D.tw[i] - D.tw[i - 1]; vel[i] = h > 1e-4 && a.vis[i] && a.vis[i - 1] ? dist(a.J[i][jn], a.J[i - 1][jn]) / h : 0; }
      const vmin = jn === 'root' || jn === 'head' ? 1.4 : 2.4;
      for (let i = 2; i < N - 2; i++) {
        if (vel[i] < vmin) continue;
        const around = []; for (let q = Math.max(1, i - 10); q <= Math.min(N - 1, i + 10); q++) if (Math.abs(q - i) > 1) around.push(vel[q]);
        const { med } = robust(around), nb = Math.max(vel[i - 1], vel[i + 1]);
        if (vel[i] > 4 * Math.max(med, 0.3) && vel[i] > 2.2 * nb) popHits.push({ i, jn, acc: vel[i], x: vel[i] / Math.max(med, 0.3) });
      }
      if (jn === 'root') for (let i = 1; i < N; i++) {
        const h = D.tw[i] - D.tw[i - 1]; if (h < 1e-4 || !a.vis[i] || !a.vis[i - 1]) continue;
        const sp = len(flat(sub(a.J[i].root, a.J[i - 1].root))) / h;
        if (sp > B.teleport * 2.2) { const p = a.J[i].root; push({ sev: 'must', cat: 'motion', kind: 'teleport', actor: a.id, part: 'whole body', t0: t2(D.ts[i - 1]), t1: t2(D.ts[i]), at: t2(D.ts[i]), pt: p, where: whereAt(D, i, p), value: +(sp * h).toFixed(2), unit: 'm', msg: `${a.id} jumps ${cm(sp * h)} in one frame at ${t2(D.ts[i])} s while on screen.`, fix: `An 'at' jump or an xf change happens while the actor is visible: hide it (show) across the jump or cut there.` }); break; }
      }
    }
    popHits.sort((x, y) => x.i - y.i);
    const NAMES = { root: 'hips', head: 'head', 'wrist.L': 'left hand', 'wrist.R': 'right hand', 'foot.L': 'left foot', 'foot.R': 'right foot' };
    for (let q = 0; q < popHits.length;) {
      const grp = [popHits[q]]; let r = q + 1; while (r < popHits.length && popHits[r].i - grp[grp.length - 1].i <= 4) grp.push(popHits[r++]);
      q = r;
      const w = grp.reduce((m, x) => (x.acc > m.acc ? x : m), grp[0]), parts = [...new Set(grp.map((x) => NAMES[x.jn]))];
      const whole = grp.some((x) => x.jn === 'root') || parts.length >= 4, p = a.J[w.i][w.jn];
      push({ sev: seen(D, a, Math.max(0, w.i - 3), Math.min(N - 1, w.i + 3), p, whole ? 'must' : 'should'), cat: 'motion', kind: 'pop', actor: a.id, part: whole ? 'whole body' : parts.join(', '), t0: t2(D.ts[grp[0].i]), t1: t2(D.ts[grp[grp.length - 1].i]), at: t2(D.ts[w.i]), pt: p, where: whereAt(D, w.i, p),
        value: +(w.acc * (D.tw[w.i] - D.tw[w.i - 1])).toFixed(3), unit: 'm in one frame', msg: `${a.id}'s ${whole ? 'whole body' : parts.join(' and ')} ${parts.length > 1 && !whole ? 'jump' : 'jumps'} ${cm(w.acc * (D.tw[w.i] - D.tw[w.i - 1]))} in one frame at ${t2(D.ts[w.i])} s (${Math.round(w.x)}× faster than the frames around it) — a visible pop.`,
        fix: `A seam between two actions (or an 'at' jump): give the next action a longer blend (blend: 0.4–0.6), start it from the pose the last one ends in, or cut on that frame.` });
    }
    // ---------- life: arm swing while walking, still arms while talking, twins, straight gestures, balance
    const speed = new Float32Array(N); for (let i = 1; i < N; i++) { const h = D.tw[i] - D.tw[i - 1]; speed[i] = h > 1e-4 ? len(flat(sub(a.J[i].root, a.J[i - 1].root))) / h : speed[i - 1]; }
    const sm = (arr, w) => Float32Array.from(arr, (_, i) => { let s = 0, n = 0; for (let q = Math.max(0, i - w); q <= Math.min(arr.length - 1, i + w); q++) { s += arr[q]; n++; } return s / n; });
    const spS = sm(speed, 15);
    const holding = (i) => [...propNames].some((n) => HELD.includes(n) && a.props[i]?.[n]?.visible);
    const W1 = Math.round(1.2 / dt);
    for (let i0 = 0; i0 + W1 < N; i0 += Math.round(W1 / 2)) {
      const i1 = i0 + W1; let walk = true; for (let i = i0; i <= i1; i++) if (spS[i] < 0.7 || !a.vis[i]) { walk = false; break; }
      if (!walk || holding(i0) || a.size.slice(i0, i1).reduce((m, x) => Math.max(m, x), 0) < 0.12) continue;
      const rng = (s) => { let lo = 1e9, hi = -1e9; for (let i = i0; i <= i1; i++) { const F = bodyFrames(a.J[i]); const v = dot(sub(a.J[i][`wrist.${s}`], a.J[i].root), F.hips); lo = Math.min(lo, v); hi = Math.max(hi, v); } return hi - lo; };
      const rl = rng('L'), rr = rng('R');
      if (rl < B.swing * k && rr < B.swing * k) {
        const w = (i0 + i1) >> 1, p = a.J[w]['wrist.R'];
        push({ sev: 'should', cat: 'motion', kind: 'no-arm-swing', actor: a.id, part: 'arms', t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p), value: +Math.max(rl, rr).toFixed(3), limit: B.swing, unit: 'm',
          msg: `${a.id} walks at ${fmt(spS[w], 1)} m/s with arms that hang still (swing ${cm(Math.max(rl, rr))}); walking arms swing opposite the legs.`, fix: `Use a walking clip/gait with arm swing (gait: 'walk' or a clip), or drop an arm overlay that freezes them.` });
        i0 = i1; // one finding per stretch
      }
    }
    // twins: both arms doing the same, mirrored, while standing
    const W2 = Math.round(1.6 / dt);
    for (let i0 = 0; i0 + W2 < N; i0 += Math.round(W2 / 2)) {
      const i1 = i0 + W2; if (spS[(i0 + i1) >> 1] > 0.35 || a.vis.slice(i0, i1).some((v) => !v)) continue;
      let dotS = 0, nL = 0, nR = 0, ampL = 0, ampR = 0;
      const loc = (i, s) => { const F = bodyFrames(a.J[i]), d = sub(a.J[i][`wrist.${s}`], a.J[i].spine03); return [dot(d, F.chestSide), d[1], dot(d, F.chest)]; };
      let pl = loc(i0, 'L'), pr = loc(i0, 'R'); const sL = pl, sR = pr;
      for (let i = i0 + 2; i <= i1; i += 2) {
        const l = loc(i, 'L'), r = loc(i, 'R'), vl = sub(l, pl), vr = sub(r, pr); vr[0] = -vr[0];
        dotS += dot(vl, vr); nL += dot(vl, vl); nR += dot(vr, vr); pl = l; pr = r;
        ampL = Math.max(ampL, dist(l, sL)); ampR = Math.max(ampR, dist(r, sR));
      }
      const corr = dotS / Math.sqrt(Math.max(1e-9, nL * nR));
      if (ampL > 0.12 * k && ampR > 0.12 * k && corr > 0.93) {
        const w = (i0 + i1) >> 1, p = a.J[w].spine03;
        push({ sev: 'nice', cat: 'motion', kind: 'twinning', actor: a.id, part: 'arms', t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p), value: +corr.toFixed(2), unit: 'corr',
          msg: `${a.id}'s arms move as mirror twins (${Math.round(corr * 100)}% the same) — it reads mechanical.`, fix: `Offset one arm by 3–6 frames or give it a smaller, different action (a keys overlay on one hand).` });
        i0 = i1;
      }
    }
    // gestures in a straight line (no arc)
    for (const s of ['L', 'R']) {
      const sp = new Float32Array(N); for (let i = 1; i < N; i++) { const h = D.tw[i] - D.tw[i - 1]; sp[i] = h > 1e-4 ? dist(a.J[i][`wrist.${s}`], a.J[i - 1][`wrist.${s}`]) / h : 0; }
      for (const [i0, i1] of runs(Array.from(sp, (v) => v > 1.3))) {
        const durS = (i1 - i0) * dt; if (durS < 0.12 || durS > 0.8 || !a.vis[i0] || a.size[i0] < 0.15) continue;
        const A = a.J[i0][`wrist.${s}`], Z = a.J[i1][`wrist.${s}`], C = dist(A, Z); if (C < 0.3 * k) continue;
        // relative to the body (a walking body carries the hand in a line anyway)
        const rel = (i) => sub(a.J[i][`wrist.${s}`], a.J[i].spine03), RA = rel(i0), RZ = rel(i1), RC = dist(RA, RZ); if (RC < 0.25 * k) continue;
        let dev = 0; for (let i = i0; i <= i1; i++) dev = Math.max(dev, (() => { const p = rel(i), ab = sub(RZ, RA), u = Math.max(0, Math.min(1, dot(sub(p, RA), ab) / dot(ab, ab))); return dist(p, [RA[0] + ab[0] * u, RA[1] + ab[1] * u, RA[2] + ab[2] * u]); })());
        if (dev / RC < B.arcDev) {
          const w = (i0 + i1) >> 1, p = a.J[w][`wrist.${s}`];
          push({ sev: 'nice', cat: 'motion', kind: 'no-arc', actor: a.id, part: `${side(s)} hand`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p), value: +(dev / RC).toFixed(3), limit: B.arcDev, unit: 'bend',
            msg: `${a.id}'s ${side(s)} hand travels ${cm(RC)} in a dead-straight line — living moves follow arcs.`, fix: `Add a middle key that lifts or swings the hand ${cm(RC * 0.12)} off the straight path (keys with three points).` });
        }
      }
    }
    // balance: standing still with the weight outside the feet
    const comOf = (j) => { const Wt = [['root', 0.15], ['spine03', 0.2], ['neck01', 0.1], ['head', 0.08], ['upperleg01.L', 0.1], ['upperleg01.R', 0.1], ['lowerleg01.L', 0.045], ['lowerleg01.R', 0.045], ['upperarm01.L', 0.03], ['upperarm01.R', 0.03], ['lowerarm01.L', 0.02], ['lowerarm01.R', 0.02], ['wrist.L', 0.015], ['wrist.R', 0.015]]; let x = 0, z = 0, w = 0; for (const [n, q] of Wt) if (j[n]) { x += j[n][0] * q; z += j[n][2] * q; w += q; } return [x / w, z / w]; };
    const off = new Float32Array(N);
    for (let i = 0; i < N; i++) {
      if (spS[i] > 0.12 || (a.C[i] & 3) !== 3 || !a.vis[i] || a.L[i].root[1] < 0.75 * k) continue; // sitting, kneeling, lying: the ground carries the weight
      const j = a.L[i], pts = []; for (const s of ['L', 'R']) for (const n of [`foot.${s}`, `toe2-1.${s}`, `toe2-3.${s}:tail`]) if (j[n]) pts.push([j[n][0], j[n][2]]);
      off[i] = Math.max(0, polyDist(hull2(pts), comOf(j)) - 0.03 * k);
    }
    for (const [i0, i1] of mergeRuns(runs(Array.from(off, (x) => x > B.balance * k)), 8)) {
      if ((i1 - i0) * dt < 0.6) continue;
      let w = i0; for (let i = i0; i <= i1; i++) if (off[i] > off[w]) w = i;
      const p = a.J[w].root;
      push({ sev: seen(D, a, i0, i1, p, 'should'), cat: 'motion', kind: 'off-balance', actor: a.id, part: 'weight', t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: p, where: whereAt(D, w, p), value: +off[w].toFixed(3), limit: B.balance, unit: 'm',
        msg: `${a.id} stands still with the weight ${cm(off[w])} outside the feet for ${t2((i1 - i0) * dt)} s — a real body would fall or step.`, fix: `Widen the stance or move the hips over the feet (a lean overlay), or let the actor take a step.` });
    }
    // too fast for a person (unless riding)
    let fast = 0, fw = 0; for (let i = 0; i < N; i++) if (a.vis[i] && spS[i] > fast) { fast = spS[i]; fw = i; }
    if (fast > 10.5 && !a.design?.rider) { const p = a.J[fw].root; push({ sev: 'nice', cat: 'motion', kind: 'too-fast', actor: a.id, part: 'whole body', t0: t2(D.ts[fw]), at: t2(D.ts[fw]), pt: p, where: whereAt(D, fw, p), value: +fast.toFixed(1), unit: 'm/s', msg: `${a.id} moves at ${fast.toFixed(1)} m/s — faster than any runner.`, fix: `Check the actor's rate or an xf that moves it.` }); }
  }
  // ---------- bodies passing through each other
  const A = D.actors.filter((a) => a.vis.some((v) => v));
  for (let p = 0; p < A.length; p++) for (let q = p + 1; q < A.length; q++) {
    const a = A[p], b = A[q], hits = new Array(N).fill(null);
    for (let i = 0; i < N; i++) {
      if (!a.vis[i] && !b.vis[i]) continue;
      if (dist(a.J[i].root, b.J[i].root) > 2.4) continue;
      const ca = capsulesOf(a.J[i], a.k), cb = capsulesOf(b.J[i], b.k); let best = null;
      for (const x of ca) for (const y of cb) { const r = segSeg(x.a, x.b, y.a, y.b), depth = x.r + y.r - r.d; if (depth > B.actorDepth[0] && (!best || depth > best.depth)) best = { depth, x, y, at: r.at }; }
      hits[i] = best;
    }
    for (const [i0, i1] of mergeRuns(runs(hits.map((h) => !!h)), 6)) {
      const long = (i1 - i0) * dt; if (long < B.actorTime[0]) continue;
      let w = i0; for (let i = i0; i <= i1; i++) if (hits[i] && (!hits[w] || hits[i].depth > hits[w].depth)) w = i;
      const h = hits[w]; if (!h) continue;
      const sev0 = h.depth > B.actorDepth[1] && long > B.actorTime[1] ? 'must' : 'should';
      const sev = seen(D, a.vis[w] ? a : b, i0, i1, h.at, sev0);
      push({ sev, cat: 'anatomy', kind: 'bodies-overlap', actor: `${a.id}+${b.id}`, part: `${partName(h.x)} / ${partName(h.y)}`, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), pt: h.at, where: whereAt(D, w, h.at), value: +h.depth.toFixed(3), limit: B.actorDepth[0], unit: 'm',
        msg: `${a.id}'s ${partName(h.x)} is ${cm(h.depth)} inside ${b.id}'s ${partName(h.y)} for ${t2(long)} s — two bodies pass through each other.`,
        fix: `Start them further apart (motion actors 'at'), stop the walk ${cm(h.depth + 0.15)} earlier ('go' target), or offset one actor's xf.pos.` });
    }
  }
  return out;
}
