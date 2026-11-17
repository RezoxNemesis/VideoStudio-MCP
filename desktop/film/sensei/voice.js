// VOICE checks (17 Nov 2026): a father spoke with a woman's voice for a whole film, because the cast named a voice
// preset ("Storyteller") that is a woman's. Every spoken line is measured — its median pitch — and held against the
// body of who says it: a grown man speaks at about 85–165 Hz, a woman at 160–260, a child of under twelve 220–320.
import fs from 'node:fs';
import path from 'node:path';
import { issue } from './rules.js';

/** Median pitch (Hz) of a 16-bit PCM wav, from the voiced 40 ms windows (autocorrelation). null when it cannot tell. */
export function wavPitch(file) {
  let b; try { b = fs.readFileSync(file); } catch { return null; }
  if (b.toString('ascii', 0, 4) !== 'RIFF') return null;
  let p = 12, sr = 24000, ch = 1, bits = 16, data = null;
  while (p + 8 <= b.length) { const id = b.toString('ascii', p, p + 4), n = b.readUInt32LE(p + 4); if (id === 'fmt ') { ch = b.readUInt16LE(p + 10); sr = b.readUInt32LE(p + 12); bits = b.readUInt16LE(p + 22); } else if (id === 'data') { data = [p + 8, Math.min(b.length, p + 8 + n)]; break; } p += 8 + n + (n & 1); }
  if (!data || bits !== 16) return null;
  const n = Math.floor((data[1] - data[0]) / (2 * ch)), x = new Float32Array(n);
  for (let i = 0; i < n; i++) x[i] = b.readInt16LE(data[0] + i * 2 * ch) / 32768;
  const N = Math.round(0.04 * sr), H = Math.round(0.01 * sr), lo = Math.floor(sr / 450), hi = Math.ceil(sr / 70), f0 = [];
  for (let i = 0; i + N + hi < n; i += H) {
    let e = 0, m = 0; for (let k = 0; k < N; k++) m += x[i + k]; m /= N; for (let k = 0; k < N; k++) e += (x[i + k] - m) ** 2;
    if (Math.sqrt(e / N) < 0.02) continue;
    // (the shortest lag whose peak is nearly the best: the best alone often lands on two or three periods, an octave low)
    const ac = new Float32Array(hi + 2); let best = -1;
    for (let lag = lo; lag <= hi + 1; lag++) { let s = 0; for (let k = 0; k < N; k++) s += (x[i + k] - m) * (x[i + k + lag] - m); ac[lag] = s; if (lag <= hi && s > best) best = s; }
    if (best <= 0.4 * e) continue;
    let bk = 0; for (let lag = lo + 1; lag <= hi; lag++) if (ac[lag] >= 0.85 * best && ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1]) { bk = lag; break; }
    if (bk) f0.push(sr / bk);
  }
  if (f0.length < 5) return null;
  f0.sort((a, c) => a - c); return f0[Math.floor(f0.length / 2)];
}
/** The range a body speaks in: [low, high] Hz, or null (a teenager, or a body the check does not know). */
export function pitchRange(body = '', age) {
  if (/teen/.test(body)) return null;
  if (/girl|boy|child/.test(body) || (age != null && age < 12)) return [200, 340];
  if (/woman|lady/.test(body)) return [150, 280];
  if (/man|heavy|old/.test(body)) return [75, 170];
  return null;
}
/** The gender of a voice from its blend ("af_heart*0.6+af_aoede*0.4": the second letter of each name, f or m, weighed).
 *  → { f, m } shares (0…1), or null when the names say nothing. */
export function voiceGender(voice = '') {
  let f = 0, m = 0;
  for (const part of String(voice).split('+')) { const [name, w = '1'] = part.trim().split('*'), code = name.split('_')[0]; if (code.length !== 2) continue; const k = +w || 0; if (code[1] === 'f') f += k; else if (code[1] === 'm') m += k; }
  return f + m > 0 ? { f: f / (f + m), m: m / (f + m) } : null;
}
export function voiceChecks(P, lang) {
  const out = [], seen = new Set(), json = (() => { try { return JSON.parse(fs.readFileSync(path.join(P.D.vo, `${lang}.json`), 'utf8')); } catch { return {}; } })();
  const bodyOf = {};
  for (const s of P.FILM ?? []) for (const c of s.cast ?? []) if (c.who) bodyOf[c.who] ??= { body: c.stage?.body ?? '', age: c.stage?.age, id: c.id };
  for (const [id, v] of Object.entries(json)) {
    const who = v.who, b = bodyOf[who]; if (!b || seen.has(`${who}|${v.voice}`)) continue;
    seen.add(`${who}|${v.voice}`);
    const range = pitchRange(b.body, b.age), child = range && range[0] >= 200, hz = range ? wavPitch(path.join(P.D.vo, lang, `${id}.wav`)) : null, g = voiceGender(v.voice);
    const woman = /woman|lady/.test(b.body), man = !woman && !child && /man|heavy|old/.test(b.body);
    let why = null;
    if (!child && g && man && g.f >= 0.6) why = `a woman's voice (${v.voice})`;
    else if (!child && g && woman && g.m >= 0.6) why = `a man's voice (${v.voice})`;
    else if (child && hz != null && hz < range[0] * 0.9) why = `a grown-up's pitch (${Math.round(hz)} Hz)`;
    else if (!child && range && hz != null && (hz < range[0] * 0.85 || hz > range[1] * 1.12)) why = `a pitch of ${Math.round(hz)} Hz`;
    if (why) out.push(issue({ sev: 'must', cat: 'sound', kind: 'voice-body', shot: null, seq: null, actor: b.id, value: hz != null ? Math.round(hz) : null, limit: range, unit: 'Hz',
      msg: `${who} (${b.body}${b.age ? `, ${b.age}` : ''}) speaks ${id} (${lang}) with ${why} — the voice does not belong to that body (a ${b.body} speaks at about ${range?.[0]}–${range?.[1]} Hz).`,
      fix: `Give ${who} a voice of the right kind in script.json's cast (a preset whose gender and age fit) and speak the lines again.` }));
  }
  return out;
}
