// THE DEEP REVIEW — measured, not eyeballed. Runs a film's shots without drawing them and reports what a viewer would feel as "weak":
//   camera   the picture shaking (a lens tied to a talking head or a trembling hand)            limit: 3 px at 720×1280
//   lens     a body or prop on the lens (a sleeve that fills the frame; a lens inside a person)  limit: 12 cm
//   holds    hands that do not reach, bend too far at the wrist, or float off what they hold      limits: 92 % of the arm, 60°, 2 mm
//   bodies   a planted foot that slides, a leg stretched past its length, weight standing off its feet   limits: 6 mm a frame, 99.9 % of the leg, 13 cm
//   meshes   every drawn mesh whole: each per-point list as long as the points, every index inside them, every number finite
// A film notes its holds itself: c.rep[name] = { ratio, bend, gap, far, t } (motion.js pinchSheet returns exactly that).
// Used by: cine.review (review.html, in the app), Cine Studio's Check button, and node (tools/review/run.mjs in a workspace).
export const LIMITS = { shakePx: 3, lensM: 0.12, reach: 0.92, bendRad: 1.05, gapM: 0.002, slideM: 0.006, legReach: 0.9992, offM: 0.13 };

/** F: the film module ({ META, S }); mkE: () => an engine or a stand-in ({ scene, setEnv }); V3: the film's THREE.Vector3; o.shots: only these; o.fps */
/** Every mesh must be whole: each per-point list (normals, uv…) as long as the points, every index inside them, every number finite.
 *  A normal list ONE point short draws as a black block over half the picture on a GPU, only at full size, and no code looks wrong
 *  (the seat's closed underside, 27 Nov 2026: points were added and computeVertexNormals kept the old, shorter list). */
function meshFaults(scene, seen) { const bad = []; if (!scene || !scene.traverse) return bad;
  if (scene.userData && scene.userData.ticks) for (const f of scene.userData.ticks) { try { f(); } catch (e) { /* a tick that needs a renderer */ } }   // meshes remade before each draw (trouser legs)
  scene.traverse(m => { const g = m.isMesh && m.geometry, P = g && g.attributes && g.attributes.position; if (!P) return; const ver = P.version || 0; if (seen.get(g) === ver) return; seen.set(g, ver); const say = [];
    for (const [k, a] of Object.entries(g.attributes)) { if (a.count !== P.count) say.push(`its ${k} list has ${a.count} entries for ${P.count} points`);
      else if (!a.isInterleavedBufferAttribute && (k === 'position' || k === 'normal' || k === 'uv')) { const A = a.array; for (let i = 0; i < A.length; i++) if (!Number.isFinite(A[i])) { say.push(`its ${k} list holds a number that is not finite (point ${Math.floor(i / a.itemSize)})`); break; } } }
    if (g.index) { const I = g.index.array; let mx = 0; for (let i = 0; i < I.length; i++) if (I[i] > mx) mx = I[i]; if (mx >= P.count) say.push(`a triangle uses point ${mx} of ${P.count}`); }
    if (say.length) { let who = ''; for (let q = m; q; q = q.parent) if (q.name) { who = q.name; break; } bad.push({ what: say.join('; '), mesh: `${who ? who + ': ' : ''}${g.type} of ${P.count} points${m.material && m.material.color ? ', colour #' + m.material.color.getHexString() : ''}` }); } });
  return bad; }

export async function reviewFilm(F, mkE, V3, o = {}) {
  const fps = o.fps || (F.META && F.META.fps) || 30, order = (o.shots && o.shots.length ? o.shots : (F.META && F.META.order) || Object.keys(F.S)).filter(id => F.S[id]);
  const H = 1280, out = { film: (F.META && F.META.name) || '', limits: LIMITS, shots: [], must: [] };
  for (const id of order) { const sh = F.S[id], E = mkE(), c = await sh.build(E), n = Math.max(2, Math.floor(sh.dur * fps));
    const yaw = [], pit = [], pos = [], fov = [], foc = [], holds = {}, bodies = {}; let lens = [9, 0, '']; const v = new V3(), meshes = [], seenG = new Map();
    for (let i = 0; i < n; i++) { const t = i / fps, r = sh.frame(t, c, E); const d = new V3(r.t[0] - r.p[0], r.t[1] - r.p[1], r.t[2] - r.p[2]); foc.push(d.length()); d.normalize(); { let y = Math.atan2(d.x, d.z); if (yaw.length) { const pv = yaw[yaw.length - 1]; while (y - pv > Math.PI) y -= 2 * Math.PI; while (y - pv < -Math.PI) y += 2 * Math.PI; } yaw.push(y); } pit.push(Math.asin(Math.max(-1, Math.min(1, d.y)))); const P = new V3(r.p[0], r.p[1], r.p[2]); pos.push(P); fov.push(r.fov || 40);
      for (const [name, h] of Object.entries(c.rep || {})) { if (!h || h.t !== t) continue; const w = holds[name] || (holds[name] = { name, frames: 0, reach: 0, bend: 0, gap: 0, thumbShort: 0, at: {} }); w.frames++; if (h.ratio > w.reach) { w.reach = h.ratio; w.at.reach = t; } if (h.bend > w.bend) { w.bend = h.bend; w.at.bend = t; } if (h.gap > w.gap) { w.gap = h.gap; w.at.gap = t; } if (h.far) w.thumbShort++; }
      for (const [name, p] of Object.entries(c)) { if (!p || !p.root || !p.root.visible || !p.leg || !p.leg.l || !p.L || !p.L.thigh) continue; const b = bodies[name] || (bodies[name] = { name, slide: 0, reach: 0, off: 0, at: {}, prev: null }), now = {}, len = p.L.thigh + p.L.shin, g0 = p.root.position.y;
        for (const sd of ['l', 'r']) { const lg = p.leg[sd]; lg.an.updateWorldMatrix(true, false); lg.hip.updateWorldMatrix(true, false); const a = new V3().setFromMatrixPosition(lg.an.matrixWorld), h = new V3().setFromMatrixPosition(lg.hip.matrixWorld), r = a.distanceTo(h) / len; if (r > b.reach) { b.reach = r; b.at.reach = t; }
          const flat = a.y - g0 <= p.L.ankle + 0.004; now[sd] = { flat, x: a.x, z: a.z }; if (flat && b.prev && b.prev[sd].flat) { const d = Math.hypot(a.x - b.prev[sd].x, a.z - b.prev[sd].z) * fps / 30; if (d > b.slide) { b.slide = d; b.at.slide = t; } } }
        b.prev = now; if (p.bodyRep && p.bodyRep.t === t && p.bodyRep.off > b.off) { b.off = p.bodyRep.off; b.at.off = t; } }
      if (i % 15 === 0 && meshes.length < 6) for (const b of meshFaults(E && E.scene, seenG)) if (!meshes.some(q => q.mesh === b.mesh && q.what === b.what)) meshes.push({ ...b, at: Math.round(t * 100) / 100 });
      if (i % 2 === 0) for (const [name, p] of Object.entries(c)) { if (!p || !p.root || !p.root.isObject3D || !p.root.visible) continue; p.root.updateMatrixWorld(true); p.root.traverse(m => { if (!m.isMesh || !m.visible || !m.geometry || !m.geometry.attributes.position) return; for (let q = m.parent; q; q = q.parent) if (!q.visible) return; const a = m.geometry.attributes.position, step = Math.max(1, Math.floor(a.count / 60)); for (let k = 0; k < a.count; k += step) { v.fromBufferAttribute(a, k).applyMatrix4(m.matrixWorld); const dd = v.distanceTo(P); if (dd < lens[0]) lens = [dd, t, name]; } }); } }
    // camera: what a smooth local curve (a quadratic over ±0.33 s) does not explain is shake; an eased push, pan or rack passes untouched
    const w = Math.max(2, Math.round(fps * 0.33)), den = (2 * w + 3) * (2 * w + 1) * (2 * w - 1); const sg = a => a.map((_, i) => { if (i < w || i >= a.length - w) return a[i]; let s = 0; for (let j = -w; j <= w; j++) s += a[i + j] * (3 * (3 * w * w + 3 * w - 1) - 15 * j * j) / den; return s; });
    const hy = sg(yaw), hp = sg(pit), px = pos.map(p => p.x), py = pos.map(p => p.y), pz = pos.map(p => p.z), hx = sg(px), hY = sg(py), hz = sg(pz); let peak = 0, at = 0, rms = 0, k = 0;
    for (let i = w; i < n - w; i++) { const ppr = H / (fov[i] * Math.PI / 180); const dev = Math.hypot((yaw[i] - hy[i]) * ppr, (pit[i] - hp[i]) * ppr) + Math.hypot(px[i] - hx[i], py[i] - hY[i], pz[i] - hz[i]) / Math.max(0.3, foc[i]) * ppr; rms += dev * dev; k++; if (dev > peak) { peak = dev; at = i / fps; } }
    const r2 = x => Math.round(x * 100) / 100; const S = { id, dur: sh.dur, camera: { shakePx: r2(peak), rmsPx: r2(Math.sqrt(rms / Math.max(1, k))), at: r2(at), ok: peak <= LIMITS.shakePx }, lens: { nearestCm: Math.round(lens[0] * 100), who: lens[2], at: r2(lens[1]), ok: lens[0] >= LIMITS.lensM || lens[0] > 8 },
      holds: Object.values(holds).map(h => ({ name: h.name, frames: h.frames, reachPct: Math.round(h.reach * 100), wristDeg: Math.round(h.bend * 57.3), gapMm: r2(h.gap * 1000), thumbShort: h.thumbShort, at: h.at, ok: h.reach <= LIMITS.reach && h.bend <= LIMITS.bendRad && h.gap <= LIMITS.gapM && !h.thumbShort })) };
    S.bodies = Object.values(bodies).map(b => ({ name: b.name, slideMm: r2(b.slide * 1000), legPct: Math.round(b.reach * 1000) / 10, offCm: r2(b.off * 100), at: b.at, ok: b.slide <= LIMITS.slideM && b.reach <= LIMITS.legReach && b.off <= LIMITS.offM }));
    S.meshes = meshes; for (const b of meshes) out.must.push(`${id}: a mesh is not whole at ${b.at} s (${b.mesh}) — ${b.what}. On a GPU this draws as a black block. Where points were added to a mesh, remake every list: deleteAttribute('normal') before computeVertexNormals()`);
    for (const b of S.bodies) { if (b.slideMm > LIMITS.slideM * 1000) out.must.push(`${id}: ${b.name}'s planted foot slides ${b.slideMm} mm a frame at ${r2(b.at.slide)} s — a foot on the floor stays where it landed (body.js planWalk / planTurn + stepper keep it there)`);
      if (b.legPct > LIMITS.legReach * 100) out.must.push(`${id}: ${b.name}'s leg is stretched to its whole length at ${r2(b.at.reach)} s — the foot is out of reach: lower the hips or bring the foot nearer (a knee never locks)`);
      if (b.offCm > LIMITS.offM * 100) out.must.push(`${id}: ${b.name} stands ${b.offCm} cm off their own feet at ${r2(b.at.off)} s — put the hips back over the feet, or step`); }
    if (!S.camera.ok) out.must.push(`${id}: the picture shakes ${S.camera.shakePx} px at ${S.camera.at} s — aim the lens at a rest position (motion.js steady), never at a live head or a trembling hand`);
    if (!S.lens.ok) out.must.push(`${id}: ${S.lens.who || 'a body'} is ${S.lens.nearestCm} cm from the lens at ${S.lens.at} s — move the lens or the person`);
    for (const h of S.holds) if (!h.ok) out.must.push(`${id}: ${h.name} — reach ${h.reachPct}% of the arm, wrist ${h.wristDeg}°, gap ${h.gapMm} mm${h.thumbShort ? ', the thumb cannot reach' : ''} — move the stand mark, turn the thing, or change the hold (into, flex)`);
    out.shots.push(S); if (o.onShot) o.onShot(S);
    if (E && E.renderer && E.renderer.dispose) { try { E.renderer.dispose(); E.renderer.forceContextLoss && E.renderer.forceContextLoss(); } catch (e) { /* gone */ } } }
  out.ok = out.must.length === 0; return out; }

/** the report as lines of text */
export function reviewText(R) { const L = []; for (const s of R.shots) { L.push(`${s.camera.ok && s.lens.ok && s.holds.every(h => h.ok) && (s.bodies || []).every(b => b.ok) && !(s.meshes || []).length ? 'ok  ' : 'FIX '} ${String(s.id).padEnd(8)} camera ${s.camera.shakePx} px · nearest body ${s.lens.nearestCm > 800 ? '—' : s.lens.nearestCm + ' cm'}${s.holds.length ? ' · ' + s.holds.map(h => `${h.name}: reach ${h.reachPct}% wrist ${h.wristDeg}° gap ${h.gapMm} mm${h.thumbShort ? ' THUMB SHORT' : ''}`).join(' | ') : ''}${(s.bodies || []).some(b => !b.ok) ? ' · ' + s.bodies.filter(b => !b.ok).map(b => `${b.name}: feet ${b.slideMm} mm, leg ${b.legPct}%, off ${b.offCm} cm`).join(' | ') : ''}${(s.meshes || []).length ? ' · ' + s.meshes.length + ' broken mesh' + (s.meshes.length > 1 ? 'es' : '') : ''}`); }
  L.push(R.ok ? 'REVIEW: clean' : `REVIEW: ${R.must.length} to fix`); for (const m of R.must) L.push('  • ' + m); return L.join('\n'); }
