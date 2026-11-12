// Sensei's report: findings merged and ranked, scores per category, the gate, what changed since the last review,
// written as JSON (for Claude and the app) and as a page a person reads.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { CATS, SEV, score } from './rules.js';

const ORDER = { must: 0, should: 1, nice: 2 };
const overlap = (a, b) => a.t0 == null || b.t0 == null || (a.t0 <= (b.t1 ?? b.t0) + 0.25 && b.t0 <= (a.t1 ?? a.t0) + 0.25);
/** one finding per problem: neighbours of the same kind on the same body part are one */
export function dedupe(list) {
  const out = [], keyOf = (i) => `${i.shot}|${i.kind}|${i.actor}|${i.part}`;
  const groups = new Map();
  for (const i of list) { const k = keyOf(i); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(i); }
  for (const g of groups.values()) {
    g.sort((a, b) => (a.t0 ?? 0) - (b.t0 ?? 0));
    let cur = null;
    for (const i of g) {
      if (cur && overlap(cur, i)) {
        const worse = ORDER[i.sev] < ORDER[cur.sev] || (i.sev === cur.sev && Math.abs(i.value ?? 0) > Math.abs(cur.value ?? 0));
        const t0 = Math.min(cur.t0 ?? 0, i.t0 ?? 0), t1 = Math.max(cur.t1 ?? 0, i.t1 ?? 0);
        if (worse) { i.data = { ...(i.data ?? {}), merged: (cur.data?.merged ?? 0) + 1 }; cur = Object.assign(i, { t0, t1 }); out[out.length - 1] = cur; }
        else { cur.t0 = t0; cur.t1 = t1; cur.data = { ...(cur.data ?? {}), merged: (cur.data?.merged ?? 0) + 1 }; }
      } else { cur = i; out.push(i); }
    }
  }
  // at most 3 of one kind per shot (the worst); the rest are counted
  const per = new Map(), kept = [];
  out.sort((a, b) => ORDER[a.sev] - ORDER[b.sev] || Math.abs(b.value ?? 0) - Math.abs(a.value ?? 0));
  for (const i of out) { const k = `${i.shot}|${i.kind}`, n = per.get(k) ?? 0; per.set(k, n + 1); if (n < 3) kept.push(i); else { const first = kept.find((x) => `${x.shot}|${x.kind}` === k); if (first) first.data = { ...(first.data ?? {}), more: (first.data?.more ?? 0) + 1 }; } }
  for (const i of kept) i.id = stableId(i);
  return kept;
}
export const stableId = (i) => `${i.kind}-${crypto.createHash('md5').update(`${i.shot}|${i.kind}|${i.actor}|${i.part}|${Math.round((i.t0 ?? 0) * 2) / 2}`).digest('hex').slice(0, 8)}`;
const sortIssues = (list, P) => {
  const at = new Map(P.shots.map((s, k) => [s.id, k]));
  return list.sort((a, b) => ORDER[a.sev] - ORDER[b.sev] || (at.get(a.shot) ?? 1e9) - (at.get(b.shot) ?? 1e9) || (a.t0 ?? 0) - (b.t0 ?? 0));
};

export function buildReport(P, o) {
  const issues = sortIssues(o.issues, P), sc = score(issues, o.bench, { shots: o.shots, measured: o.measured, styleScore: o.style?.score });
  const dir = path.join(P.D.vs, 'sensei'); fs.mkdirSync(path.join(dir, 'history'), { recursive: true });
  let prev = null; try { prev = JSON.parse(fs.readFileSync(path.join(dir, 'latest.json'), 'utf8')); } catch { /* the first review */ }
  const ids = new Set(issues.map((i) => i.id)), prevIds = new Set((prev?.issues ?? []).map((i) => i.id));
  const scope = o.scope ?? 'film';
  const same = !!prev && prev.scope === scope && JSON.stringify(prev.only ?? null) === JSON.stringify(o.only ?? null);
  const fixed = same ? (prev.issues ?? []).filter((i) => !ids.has(i.id) && (i.sev === 'must' || i.sev === 'should')).map((i) => ({ id: i.id, kind: i.kind, shot: i.shot, msg: i.msg })) : [];
  const fresh = same ? issues.filter((i) => !prevIds.has(i.id)).length : null;
  const rep = {
    film: P.config.name, dir: P.dir, at: new Date().toISOString(), scope, only: o.only ?? null, shots: o.shots, seconds: +(o.ms / 1000).toFixed(1), lang: o.lang,
    bench: { id: o.bench.id, name: o.bench.name }, overall: sc.overall, grade: sc.grade, pass: sc.pass, counts: { must: sc.must, should: sc.should, nice: sc.nice },
    categories: Object.fromEntries(Object.entries(sc.per).filter(([k]) => !o.measured || o.measured.has(k)).map(([k, v]) => [k, { name: CATS[k].name, score: v, issues: issues.filter((i) => i.cat === k).length }])),
    style: o.style ?? null, sound: o.sound ?? null,
    progress: same ? { before: { overall: prev.overall, grade: prev.grade, must: prev.counts?.must, should: prev.counts?.should }, fixed: fixed.length, fixedList: fixed.slice(0, 30), new: fresh } : null,
    binding: 'Sensei findings marked MUST are the person\'s own words: fix them before the film is exported.',
    issues,
  };
  fs.writeFileSync(path.join(dir, 'latest.json'), JSON.stringify(rep));
  fs.writeFileSync(path.join(dir, 'latest.md'), markdown(rep));
  const stamp = rep.at.replace(/[:.]/g, '-');
  fs.writeFileSync(path.join(dir, 'history', `${stamp}.json`), JSON.stringify({ ...rep, issues: rep.issues.map(({ id, kind, sev, shot, cat }) => ({ id, kind, sev, shot, cat })) }));
  const hist = fs.readdirSync(path.join(dir, 'history')).sort(); for (const f of hist.slice(0, Math.max(0, hist.length - 30))) fs.rmSync(path.join(dir, 'history', f), { force: true });
  return rep;
}
const bar = (v) => '█'.repeat(Math.round(v / 10)) + '░'.repeat(10 - Math.round(v / 10));
export function markdown(r) {
  const L = [];
  L.push(`# Sensei review — ${r.film}`, '', `**${r.overall}/100 · grade ${r.grade} · ${r.pass ? 'PASSES' : 'DOES NOT PASS'} the ${r.bench.name} benchmark**  `, `${r.counts.must} MUST · ${r.counts.should} SHOULD · ${r.counts.nice} nice · ${r.shots} shots looked at in ${r.seconds} s · ${r.at.slice(0, 16).replace('T', ' ')}`, '');
  if (r.progress) L.push(`Since the last review: ${r.progress.before.overall} → ${r.overall}, ${r.progress.fixed} fixed, ${r.progress.new ?? 0} new.`, '');
  L.push('> ' + r.binding, '', '## Scores', '');
  for (const [, c] of Object.entries(r.categories)) L.push(`- \`${bar(c.score)}\` ${c.score} ${c.name} (${c.issues})`);
  if (r.style) L.push('', `## Drawing style vs "${r.style.ref}" — ${r.style.score}/100`, '', ...r.style.gaps.map((g) => `- ${g.say} → ${g.fix}`));
  if (r.sound?.measured) L.push('', '## Sound', '', `Integrated ${r.sound.measured.I} LUFS · range ${r.sound.measured.LRA} LU · peak ${r.sound.measured.peak} dBFS`);
  for (const sev of ['must', 'should']) {
    const list = r.issues.filter((i) => i.sev === sev); if (!list.length) continue;
    L.push('', `## ${SEV[sev].name} (${list.length})`, '');
    for (const i of list.slice(0, sev === 'must' ? 200 : 80)) L.push(`- **${i.shot ?? 'film'}${i.t0 != null && i.shot ? ` ${i.t0}–${i.t1} s` : ''}** · ${i.kind}${i.actor ? ` · ${i.actor}` : ''}: ${i.msg}${i.fix ? `  \n  _Fix:_ ${i.fix}` : ''}${i.img ? `  \n  ![](${i.img})` : ''}`);
    if (list.length > (sev === 'must' ? 200 : 80)) L.push(`- … and ${list.length - (sev === 'must' ? 200 : 80)} more (latest.json has them all)`);
  }
  const nice = r.issues.filter((i) => i.sev === 'nice'); if (nice.length) { const c = {}; for (const i of nice) c[i.kind] = (c[i.kind] ?? 0) + 1; L.push('', `## Nice to fix (${nice.length})`, '', Object.entries(c).map(([k, v]) => `${k} ×${v}`).join(' · ')); }
  return L.join('\n') + '\n';
}
/** A short answer for Claude and the Sensei panel: the gate, scores, the top findings. */
export function digest(r, n = 12) {
  const top = r.issues.filter((i) => i.sev !== 'nice').slice(0, n).map((i) => ({ id: i.id, sev: i.sev, shot: i.shot, t: i.t0 != null ? `${i.t0}-${i.t1}` : undefined, kind: i.kind, actor: i.actor ?? undefined, msg: i.msg, fix: i.fix ?? undefined, img: i.img ?? undefined }));
  return { film: r.film, overall: r.overall, grade: r.grade, pass: r.pass, bench: r.bench.id, counts: r.counts, categories: Object.fromEntries(Object.entries(r.categories).map(([k, c]) => [k, c.score])), style: r.style ? { ref: r.style.ref, score: r.style.score } : undefined, sound: r.sound?.measured ?? undefined, progress: r.progress ? { before: r.progress.before.overall, fixed: r.progress.fixed, new: r.progress.new } : undefined, top, binding: r.binding };
}
