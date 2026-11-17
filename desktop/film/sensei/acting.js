// ACTING checks (17 Nov 2026, from the owner's notes on the concept tests): a speaking mouth that hardly changes
// (the 'serifu' lip-sync held one drawing for half a second: "the mouth lipsync is so bad"), and a thing held in a
// hand that the picture never shows (phones at the hip, a drawing at a child's knee, a tablet out of frame).
import { issue } from './rules.js';
const t2 = (x) => +x.toFixed(2);

export function actingChecks(D) {
  const out = [], N = D.N, dt = D.dt;
  for (const a of D.actors) {
    // ---- the mouth while this one speaks, its face big enough to read (a fifth of the picture's height or more)
    let i0 = -1;
    const runsOf = [];
    for (let i = 0; i <= N; i++) {
      const f = i < N ? D.faces[i]?.[a.id] : null, st = i < N ? (D.sh?.t0 ?? 0) + D.tw[i] : 0;
      const speaking = a.who && (D.sh?.seqT?.cues ?? []).some((c) => c.who === a.who && !c.vo && st >= c.t0 && st <= c.t1);
      const talking = f && speaking && a.vis[i] && a.size[i] > 0.2;
      // (a speaking run lasts through the short closes between words)
      if (talking) { if (i0 < 0) i0 = i; a._last = i; } else if (i0 >= 0 && (i >= N || i - a._last > Math.round(0.3 / dt))) { runsOf.push([i0, a._last]); i0 = -1; }
    }
    for (const [r0, r1] of runsOf) {
      const secs = (r1 - r0) * dt; if (secs < 1.0) continue;
      let ch = 0, prev = null; const step = Math.max(1, Math.round(1 / 24 / dt)); // looked at 24 times a second, as drawn
      for (let i = r0; i <= r1; i += step) { const m = D.faces[i][a.id]?.mouthOpen ?? 0; if (prev != null && Math.abs(m - prev) > 0.05) ch++; prev = m; }
      const rate = ch / secs;
      if (rate < 4) out.push(issue({ sev: rate < 2.5 ? 'must' : 'should', cat: 'motion', kind: 'lip-stuck', shot: D.id, seq: D.seq, actor: a.id, part: 'mouth', t0: t2(D.ts[r0]), t1: t2(D.ts[r1]), at: t2(D.ts[r0]), value: +rate.toFixed(1), limit: 4, unit: '/s',
        msg: `${a.id}'s mouth changes only ${rate.toFixed(1)} times a second while speaking (a living mouth: 6–10; under 4 it looks stuck open).`,
        fix: 'Use the speech lip-sync (look.lip "speech", the default for anime and multiplane); do not set lip "serifu"; check the line has its .lip.json (re-speak it) and that a beard or moustache does not cover the mouth.' }));
    }
    // ---- a thing in a hand that the picture does not show
    const H = a.heldAt; if (!H) continue;
    let inHand = 0, shown = 0;
    for (let i = 0; i < N; i++) { const p = H[i]; if (!p || !a.vis[i] || a.size[i] < 0.3) continue; inHand++; const s = D.cams[i].project(p), c = D.cams[i].toCam(p); if (c[2] > 0.15 && s[0] >= 0 && s[0] <= D.W && s[1] >= 0 && s[1] <= D.H) shown++; }
    if (inHand > 30 && shown / inHand < 0.3) out.push(issue({ sev: 'should', cat: 'camera', kind: 'held-unseen', shot: D.id, seq: D.seq, actor: a.id, part: 'hands', t0: 0, t1: t2(D.dur), at: 0, value: +(shown / inHand).toFixed(2), limit: 0.3, unit: '',
      msg: `${a.id} holds something for the whole of this shot, but it is in the picture only ${Math.round((100 * shown) / inHand)}% of the time.`,
      fix: 'Frame the hands (a wider or lower set-up, a cut-in on the thing), or put the use where it is seen (usePose lifts it to the chest).' }));
  }
  return out;
}
