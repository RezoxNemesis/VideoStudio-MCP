// Sensei reads the film as a whole: does each scene establish where we are, do shot sizes vary, can the subtitles be
// read in time, does every scene have sound.
import { issue } from './rules.js';
import { timeline } from '../kit/project.js';

export function storyChecks(P, sums, lang, B) {
  const out = [], push = (o) => { if (o.sev) out.push(issue(o)); };
  const bySeq = new Map(); for (const s of sums) { if (!s) continue; if (!bySeq.has(s.seq)) bySeq.set(s.seq, []); bySeq.get(s.seq).push(s); }
  for (const [seq, list] of bySeq) {
    const first = list[0];
    if (first && first.subject && !['LS', 'ELS', 'MLS'].includes(first.cls) && list.length >= 3) push({ sev: 'nice', cat: 'story', kind: 'no-establishing', shot: first.id, seq, t0: 0, msg: `Sequence ${seq} opens on a ${first.cls} of ${first.subject} — the viewer never sees where we are.`, fix: `Open ${seq} with a wide shot of the place (LS/ELS), then move in.` });
    if (list.length >= 5) {
      const c = new Map(); for (const s of list) c.set(s.cls, (c.get(s.cls) ?? 0) + 1);
      const [cls, n] = [...c.entries()].sort((a, b) => b[1] - a[1])[0];
      if (n / list.length >= 0.75) push({ sev: 'nice', cat: 'story', kind: 'monotone-sizes', seq, shot: list[0].id, t0: 0, value: +(n / list.length).toFixed(2), msg: `${Math.round((n / list.length) * 100)}% of the shots in ${seq} are ${cls}s — the rhythm flattens.`, fix: 'Vary the sizes: wides for geography, close-ups for emotion, inserts of hands/objects.' });
    }
  }
  let TL = null; try { TL = timeline(P); } catch { /* the film does not load */ }
  if (TL) {
    const fast = [];
    for (const c of TL.cues) { const x = c[lang]; if (!x?.text) continue; const secs = x.secs || c.slot || 0; if (secs > 0.3) { const cps = x.text.length / (secs + 0.3); if (cps > B.cps) fast.push({ id: c.id, at: +(c.at).toFixed(2), cps: +cps.toFixed(1), text: x.text.slice(0, 60) }); } }
    if (fast.length) { fast.sort((a, b) => b.cps - a.cps); const w = fast[0]; push({ sev: fast.length > 3 ? 'should' : 'nice', cat: 'story', kind: 'subs-too-fast', at: w.at, t0: w.at, value: w.cps, limit: B.cps, unit: 'chars/s', data: { lines: fast.slice(0, 10) }, msg: `${fast.length} subtitle${fast.length > 1 ? 's are' : ' is'} too fast to read (worst ${w.id}: ${w.cps} characters a second, limit ${B.cps}).`, fix: 'Shorten those lines, or give them more time (a longer pause after them).' }); }
    const withSound = new Set(); for (const e of TL.sounds ?? []) if ('music' in e || 'amb' in e) for (const sh of P.shots) if (e.t0 < sh.start + sh.len && (e.t1 ?? 1e9) > sh.start) withSound.add(sh.seq);
    for (const seq of bySeq.keys()) if (!withSound.has(seq) && (TL.sounds ?? []).length) push({ sev: 'nice', cat: 'sound', kind: 'no-bed', seq, msg: `Sequence ${seq} has no music or ambience under it.`, fix: `Add an ambience bed (sound: [{ amb: 'wind', t0: 0, t1: T.dur }]) or a music cue.` });
  }
  return out;
}
