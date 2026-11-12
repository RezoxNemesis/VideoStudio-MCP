// Sensei looks through the lens: a camera inside a body or under the ground, frames that cut people at the knees and
// ankles, heads cut off or lost in headroom, faces turned into the frame edge, figures too small to read, a speaker
// hidden behind someone, whip-fast turns, and between shots the 180° line, jump cuts and colour jumps.
import { issue } from './rules.js';
import { onScreen } from './collect.js';
import { sub, dot, len, dist, norm, angleBetween, capsulesOf, bodyFrames, runs, mergeRuns, partName, cm } from './geo.js';

const t2 = (x) => Math.round(x * 100) / 100;
const pct = (x) => `${Math.round(x * 100)}%`;
/** shot size from the main figure's height in the picture */
export function sizeClass(h) { return h > 1.6 ? 'ECU' : h > 1.05 ? 'CU' : h > 0.75 ? 'MCU' : h > 0.5 ? 'MS' : h > 0.3 ? 'MLS' : h > 0.12 ? 'LS' : 'ELS'; }

export function cameraChecks(D, B) {
  const out = [];
  const push = (o) => { if (o.sev) out.push(issue({ shot: D.id, seq: D.seq, ...o })); };
  const N = D.N, dt = D.dt, H = D.H, W = D.W;
  // ---------- the lens inside a body, or too close to one
  for (const a of D.actors) {
    const near = new Float32Array(N).fill(9), what = new Array(N).fill(null);
    for (let i = 0; i < N; i++) {
      if (D.shown[i]?.[a.id] === false) continue;
      const cam = D.cams[i];
      if (dist(cam.pos, a.J[i].root) > 3.5) continue;
      for (const c of capsulesOf(a.J[i], a.k)) {
        // distance from the lens to the capsule's surface, counting only what is in front of the lens
        const ab = sub(c.b, c.a), u = Math.max(0, Math.min(1, dot(sub(cam.pos, c.a), ab) / Math.max(1e-9, dot(ab, ab)))), q = [c.a[0] + ab[0] * u, c.a[1] + ab[1] * u, c.a[2] + ab[2] * u];
        const d = dist(cam.pos, q) - c.r;
        if (d < near[i]) { near[i] = d; what[i] = c; }
      }
    }
    for (const [i0, i1] of mergeRuns(runs(Array.from(near, (d) => d < B.camNear[0])), 4)) {
      let w = i0; for (let i = i0; i <= i1; i++) if (near[i] < near[w]) w = i;
      const inside = near[w] < 0.02, sev = near[w] < B.camNear[1] ? 'must' : 'should';
      const c = what[w];
      push({ sev, cat: 'camera', kind: inside ? 'camera-inside-body' : 'camera-too-close', actor: a.id, part: c ? partName(c) : null, t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), where: { x: 0.5, y: 0.5 }, value: +near[w].toFixed(3), limit: B.camNear[1], unit: 'm',
        msg: inside ? `The camera is inside ${a.id}'s ${c ? partName(c) : 'body'} at ${t2(D.ts[w])} s — the picture fills with a smear of body.` : `The camera is ${cm(Math.max(0, near[w]))} from ${a.id}'s ${c ? partName(c) : 'body'} — it will slice through it or fill the frame with a blur.`,
        fix: `Move the camera back: pos further from ${a.id} by ${cm(B.camNear[0] - near[w] + 0.1)} (or raise it), or hide ${a.id} in this shot (hide: ['${a.id}']).` });
    }
  }
  // under the ground
  let under = [];
  for (let i = 0; i < N; i++) under.push(D.cams[i].pos[1] < 0.03);
  for (const [i0, i1] of runs(under)) push({ sev: 'must', cat: 'camera', kind: 'camera-underground', t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[i0]), where: { x: 0.5, y: 0.9 }, value: +D.cams[i0].pos[1].toFixed(2), unit: 'm', msg: `The camera is below the ground (y ${D.cams[i0].pos[1].toFixed(2)} m).`, fix: `Raise cam pos[1] above 0.1 m (above the ground under it).` });
  // ---------- framing of the main figure
  const main = new Array(N).fill(null);
  for (let i = 0; i < N; i++) { let best = null; for (const a of D.actors) if (a.vis[i] && (!best || a.size[i] > best.size[i])) best = a; main[i] = best; }
  const mids = []; for (let i = 0; i < N; i += Math.max(1, Math.round(0.25 / dt))) mids.push(i);
  // the most common main figure decides the shot's subject
  const count = new Map(); for (const i of mids) if (main[i]) count.set(main[i].id, (count.get(main[i].id) ?? 0) + 1);
  const subjId = [...count.entries()].sort((x, y) => y[1] - x[1])[0]?.[0] ?? null, S = D.actors.find((a) => a.id === subjId) ?? null;
  const sizes = S ? mids.map((i) => S.size[i]).filter((x) => x > 0) : [];
  const sz = sizes.length ? sizes.sort((x, y) => x - y)[sizes.length >> 1] : 0;
  const cls = S ? sizeClass(sz) : 'ELS';
  if (S && cls !== 'ELS') {
    const cutAt = { knee: [], ankle: [], eyes: [], head: [] };
    let hr = [];
    for (const i of mids) {
      if (!S.vis[i]) continue;
      const cam = D.cams[i], y = (jn) => { const p = S.J[i][jn]; if (!p) return null; const c = cam.toCam(p); if (c[2] < 0.15) return null; return cam.project(p)[1] / H; };
      const kL = y('lowerleg01.L'), kR = y('lowerleg01.R'), aL = y('foot.L'), aR = y('foot.R'), top = y('head:tail'), eyes = y('eye.L');
      const near = (v, e) => v != null && Math.abs(v - 1) < e;
      if ((near(kL, B.cutJoint) || near(kR, B.cutJoint)) && (aL > 1 || aR > 1)) cutAt.knee.push(i);
      if ((near(aL, B.cutJoint * 0.8) || near(aR, B.cutJoint * 0.8))) cutAt.ankle.push(i);
      if (top != null && eyes != null && eyes < 0.02 && (cls === 'MS' || cls === 'MLS' || cls === 'LS')) cutAt.eyes.push(i);
      if (top != null && (cls === 'MS' || cls === 'MCU' || cls === 'CU')) hr.push({ i, gap: top - 0.0 });
    }
    const share = (arr) => arr.length / Math.max(1, mids.length);
    if (share(cutAt.knee) > 0.5) push({ sev: 'should', cat: 'framing', kind: 'cut-at-knees', actor: S.id, part: 'knees', t0: 0, t1: t2(D.dur), at: t2(D.ts[cutAt.knee[0]]), where: { x: 0.5, y: 0.97 }, value: +share(cutAt.knee).toFixed(2), unit: 'of the shot',
      msg: `The bottom of the frame cuts ${S.id} at the knees for ${pct(share(cutAt.knee))} of the shot — an awkward crop (it amputates the figure).`, fix: `Frame at mid-thigh (tilt down / move closer) or show the whole figure with the feet (pull back ~${cm(0.6)}).` });
    if (share(cutAt.ankle) > 0.5) push({ sev: 'should', cat: 'framing', kind: 'cut-at-ankles', actor: S.id, part: 'feet', t0: 0, t1: t2(D.dur), at: t2(D.ts[cutAt.ankle[0]]), where: { x: 0.5, y: 0.98 }, value: +share(cutAt.ankle).toFixed(2), unit: 'of the shot',
      msg: `The frame cuts ${S.id}'s feet off at the ankles for ${pct(share(cutAt.ankle))} of the shot — show the feet on the ground or cut higher.`, fix: `Lower the camera target by ~${cm(0.15)} or pull back so the feet are in the picture.` });
    if (share(cutAt.eyes) > 0.4) push({ sev: 'should', cat: 'framing', kind: 'head-cut', actor: S.id, part: 'head', t0: 0, t1: t2(D.dur), at: t2(D.ts[cutAt.eyes[0]]), where: { x: 0.5, y: 0.03 }, value: +share(cutAt.eyes).toFixed(2), unit: 'of the shot',
      msg: `The top of the frame cuts through ${S.id}'s eyes in a ${cls} — the face is lost.`, fix: `Tilt up: raise the camera target ~${cm(0.2)}.` });
    if (hr.length) {
      const g = hr.map((x) => x.gap).sort((x, y) => x - y)[hr.length >> 1];
      if (g > B.headroom) push({ sev: 'nice', cat: 'framing', kind: 'headroom', actor: S.id, part: 'head', t0: 0, t1: t2(D.dur), at: t2(D.ts[hr[hr.length >> 1].i]), where: { x: 0.5, y: g / 2 }, value: +g.toFixed(2), limit: B.headroom, unit: 'of the height',
        msg: `Too much empty space above ${S.id}'s head in a ${cls} (${pct(g)} of the frame) — the figure sinks to the bottom.`, fix: `Tilt down or raise the camera so the eyes sit near the upper third.` });
    }
    // looking into the edge (no lead room)
    let into = 0, n = 0, wi = mids[0];
    for (const i of mids) {
      if (!S.vis[i] || !S.box[i]) continue; n++;
      const F = bodyFrames(S.J[i]), head = S.J[i].head, cam = D.cams[i], a0 = cam.project(head), a1 = cam.project([head[0] + F.head[0] * 0.5, head[1], head[2] + F.head[2] * 0.5]);
      const dir = Math.sign(a1[0] - a0[0]), cx = (S.box[i][0] + S.box[i][2]) / 2;
      const side = Math.abs(a1[0] - a0[0]) / W > 0.02; // turned sideways enough to have a direction in the picture
      if (side && ((dir > 0 && cx > 0.74) || (dir < 0 && cx < 0.26))) { into++; wi = i; }
    }
    if (n && into / n > 0.6 && (cls === 'MS' || cls === 'MCU' || cls === 'CU' || cls === 'MLS')) push({ sev: 'nice', cat: 'framing', kind: 'no-lead-room', actor: S.id, part: 'face', t0: 0, t1: t2(D.dur), at: t2(D.ts[wi]), where: { x: (S.box[wi][0] + S.box[wi][2]) / 2, y: 0.35 }, value: +(into / n).toFixed(2), unit: 'of the shot',
      msg: `${S.id} looks into the near edge of the frame — no room in front of the face.`, fix: `Put the face on the far third: pan so ${S.id} sits on the side away from where they look.` });
  }
  // the figure is in the shot but never seen (drawn for nothing), or tiny when it is the only one
  {
    const never = D.actors.filter((a) => D.shown.some((s) => s?.[a.id] !== false) && !a.vis.some((v) => v)).map((a) => a.id);
    if (never.length && D.actors.length < 40) push({ sev: 'nice', cat: 'framing', kind: 'never-seen', actor: never.join(', '), t0: 0, t1: t2(D.dur), value: never.length, unit: 'actors', msg: `${never.length} actor${never.length > 1 ? 's are' : ' is'} in this shot but never in the picture (${never.slice(0, 6).join(', ')}${never.length > 6 ? '…' : ''}) — drawn for nothing.`, fix: `Add hide: [${never.slice(0, 6).map((x) => `'${x}'`).join(', ')}] to the shot (or check the camera if they should be seen).` });
  }
  if (S && D.actors.filter((a) => a.vis.some((v) => v)).length === 1 && sz < B.tiny && !/^[a-z0-9]+_a$/.test(D.id)) push({ sev: 'nice', cat: 'framing', kind: 'tiny-subject', actor: S.id, t0: 0, t1: t2(D.dur), value: +sz.toFixed(3), limit: B.tiny, unit: 'of the height', msg: `${S.id} is only ${pct(sz)} of the frame tall — hard to read what they do.`, fix: `Use a longer lens (fov down) or move in.` });
  // a speaker hidden behind someone nearer
  for (const a of D.actors) {
    let hid = 0, tot = 0, by = null, wi = 0;
    for (const i of mids) {
      if (!a.vis[i]) continue; const mo = D.faces[i]?.[a.id]?.mouthOpen ?? D.faces[i]?.[a.id]?.mouth ?? 0;
      tot++;
      const cam = D.cams[i], h = a.J[i].head, ch = cam.toCam(h); if (ch[2] < 0.2) continue; const hs = cam.project(h);
      for (const b of D.actors) {
        if (b === a || !b.vis[i]) continue;
        for (const c of capsulesOf(b.J[i], b.k)) {
          if (c.group !== 'torso' && c.group !== 'head') continue;
          const m = [(c.a[0] + c.b[0]) / 2, (c.a[1] + c.b[1]) / 2, (c.a[2] + c.b[2]) / 2], cm2 = cam.toCam(m); if (cm2[2] < 0.2 || cm2[2] > ch[2] - 0.2) continue;
          const ps = cam.project(m), rr = cam.ppm(cm2[2]) * (c.r + 0.03);
          if (Math.hypot(ps[0] - hs[0], ps[1] - hs[1]) < rr) { hid++; by = b.id; wi = i; break; }
        }
      }
      void mo;
    }
    if (tot > 4 && hid / tot > 0.6) push({ sev: 'should', cat: 'framing', kind: 'face-hidden', actor: a.id, part: 'face', t0: 0, t1: t2(D.dur), at: t2(D.ts[wi]), where: D.cams[wi] ? (() => { const s = D.cams[wi].project(a.J[wi].head); return { x: s[0] / W, y: s[1] / H }; })() : null, value: +(hid / tot).toFixed(2), unit: 'of the shot',
      msg: `${a.id}'s face is behind ${by} for ${pct(hid / tot)} of the time ${a.id} is on screen.`, fix: `Move the camera ${cm(0.5)} sideways (or ${by}) so the face clears, or hide ${by} if this is ${a.id}'s shot.` });
  }
  // ---------- how the camera moves
  const ang = new Float32Array(N), jit = new Float32Array(N);
  for (let i = 1; i < N; i++) ang[i] = angleBetween(D.cams[i - 1].F, D.cams[i].F) / dt;
  for (let i = 1; i < N - 1; i++) { const a = D.cams[i - 1].pos, b = D.cams[i].pos, c = D.cams[i + 1].pos; jit[i] = len([a[0] - 2 * b[0] + c[0], a[1] - 2 * b[1] + c[1], a[2] - 2 * b[2] + c[2]]) / (dt * dt); }
  const shakeOn = (i) => !!D.cams[i].spec?.shake;
  for (const [i0, i1] of mergeRuns(runs(Array.from(ang, (v, i) => v > B.camWhip[0] && !shakeOn(i))), 3)) {
    if ((i1 - i0) * dt < 0.08) continue;
    let w = i0; for (let i = i0; i <= i1; i++) if (ang[i] > ang[w]) w = i;
    push({ sev: ang[w] > B.camWhip[1] ? 'should' : 'nice', cat: 'camera', kind: 'camera-whip', t0: t2(D.ts[i0]), t1: t2(D.ts[i1]), at: t2(D.ts[w]), where: { x: 0.5, y: 0.5 }, value: Math.round(ang[w]), limit: B.camWhip[0], unit: '°/s',
      msg: `The camera turns ${Math.round(ang[w])}°/s — at ${D.fps} fps that strobes and is hard to follow.`, fix: `Slow the move (longer t0…t1 in move()) or cut instead of panning; an intended whip-pan wants motion blur.` });
  }
  {
    const spikes = runs(Array.from(jit, (v, i) => v > 25 && !shakeOn(i)));
    if (spikes.length) {
      let w = spikes[0][0]; for (const [i0, i1] of spikes) for (let i = i0; i <= i1; i++) if (jit[i] > jit[w]) w = i;
      const gaps = spikes.slice(1).map((r, k) => r[0] - spikes[k][0]), steady = gaps.length >= 3 && Math.max(...gaps) - Math.min(...gaps) <= 2;
      const hold = typeof D.sh.hold === 'number' ? D.sh.hold : null;
      push({ sev: spikes.length >= 3 || jit[w] > 80 ? 'should' : 'nice', cat: 'camera', kind: steady ? 'camera-steps' : 'camera-jerk', t0: t2(D.ts[spikes[0][0]]), t1: t2(D.ts[spikes[spikes.length - 1][1]]), at: t2(D.ts[w]), where: { x: 0.5, y: 0.5 }, value: spikes.length, unit: 'jerks',
        msg: steady ? `The camera moves in steps (${spikes.length} jerks, every ${Math.round(gaps.reduce((s, x) => s + x, 0) / gaps.length / (60 / D.fps))} frames) — it follows a drawing that is held${hold ? ' on ' + (hold === 2 ? 'twos' : hold + 's') : ''}, so the whole background judders.` : `The camera jerks ${spikes.length} time${spikes.length > 1 ? 's' : ''} (worst at ${t2(D.ts[w])} s): it changes speed in one frame.`,
        fix: steady ? `Let the camera follow the smooth performance, not the held drawing: aim at a point eased over time (move/breathe), or set hold: 1 on the actor the camera follows.` : `Ease the move (move(a, b, t, t0, t1) eases by default); a camera that follows a body should follow a smoothed point.` });
    }
  }
  // ---------- length
  if (D.dur < B.shotShort && D.actors.length) push({ sev: 'nice', cat: 'story', kind: 'shot-short', t0: 0, t1: t2(D.dur), value: t2(D.dur), limit: B.shotShort, unit: 's', msg: `This shot lasts ${t2(D.dur)} s — barely readable unless it is a flash cut in action.`, fix: `Lengthen it to ≥ ${B.shotShort} s or fold it into its neighbour.` });
  const moving = Array.from(ang).some((v) => v > 2) || D.cams[0] && dist(D.cams[0].pos, D.cams[N - 1].pos) > 0.2;
  if (D.dur > B.shotLong && !moving) push({ sev: 'nice', cat: 'story', kind: 'shot-long', t0: 0, t1: t2(D.dur), value: t2(D.dur), limit: B.shotLong, unit: 's', msg: `${t2(D.dur)} s on a locked camera — the eye gets tired.`, fix: `Cut to a reaction or a closer angle in the middle, or let the camera drift (breathe/move).` });
  // what the next shot needs to know about this one (for the 180° line and jump cuts)
  const ends = [0, N - 1].map((i) => ({ i, cam: { pos: D.cams[i].pos, F: D.cams[i].F }, x: Object.fromEntries(D.actors.filter((a) => a.vis[i] && a.box[i]).map((a) => [a.id, +(((a.box[i][0] + a.box[i][2]) / 2)).toFixed(3)])) }));
  const flips = new Set(); // pairs whose left/right order changes inside the shot (the camera crossed the line on screen)
  for (const a of D.actors) for (const b of D.actors) if (a.id < b.id) { let s0 = 0; for (let i = 0; i < N; i += 4) { if (!a.vis[i] || !b.vis[i]) continue; const s = Math.sign((a.box[i][0] + a.box[i][2]) - (b.box[i][0] + b.box[i][2])); if (s0 && s && s !== s0) flips.add(`${a.id}|${b.id}`); if (s) s0 = s; } }
  return { issues: out, summary: { id: D.id, seq: D.seq, dur: D.dur, subject: subjId, size: +sz.toFixed(3), cls, start: ends[0], end: ends[1], flips: [...flips] } };
}

/** Between shots: the 180° line and jump cuts (the summaries of consecutive shots of one sequence). */
export function cutChecks(sums, B) {
  const out = [];
  for (let k = 1; k < sums.length; k++) {
    const a = sums[k - 1], b = sums[k];
    if (!a || !b || a.seq !== b.seq) continue;
    // 180°: two people swap sides across the cut
    const ax = a.end.x, bx = b.start.x;
    const ids = Object.keys(ax).filter((id) => id in bx);
    for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
      const p = ids[i], q = ids[j], key = p < q ? `${p}|${q}` : `${q}|${p}`;
      if (a.flips.includes(key) || b.flips.includes(key)) continue;
      const s1 = Math.sign(ax[p] - ax[q]), s2 = Math.sign(bx[p] - bx[q]);
      if (s1 && s2 && s1 !== s2 && Math.abs(ax[p] - ax[q]) > 0.08 && Math.abs(bx[p] - bx[q]) > 0.08) out.push(issue({ sev: 'should', cat: 'camera', kind: 'crosses-line', shot: b.id, seq: b.seq, t0: 0, t1: 0, at: 0, actor: `${p}+${q}`, where: { x: bx[p], y: 0.5 },
        msg: `Cut ${a.id} → ${b.id} crosses the 180° line: ${p} and ${q} swap sides of the screen, so they seem to turn around.`, fix: `Keep ${b.id}'s camera on the same side of the line between ${p} and ${q} as ${a.id}, or show the camera crossing within a shot.` }));
    }
    // jump cut: same subject, nearly the same angle and size
    if (a.subject && a.subject === b.subject && a.cls === b.cls) {
      const turn = angleBetween(a.end.cam.F, b.start.cam.F), move = dist(a.end.cam.pos, b.start.cam.pos), ratio = Math.max(a.size, b.size) / Math.max(1e-3, Math.min(a.size, b.size));
      if (turn < B.jumpAngle && ratio < 1.3 && move < 1.5) out.push(issue({ sev: 'should', cat: 'camera', kind: 'jump-cut', shot: b.id, seq: b.seq, t0: 0, t1: 0, at: 0, actor: a.subject, where: { x: 0.5, y: 0.5 }, value: Math.round(turn), limit: B.jumpAngle, unit: '°',
        msg: `Jump cut ${a.id} → ${b.id}: same subject (${a.subject}), same size (${a.cls}), the angle changes only ${Math.round(turn)}° — it looks like a skip, not a cut.`, fix: `Change the angle by ≥ 30° or the size by a step (e.g. ${a.cls} → ${a.cls === 'MS' ? 'MCU' : 'MS'}), or join the two shots.` }));
    }
  }
  return out;
}
