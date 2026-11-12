// Faces that act: an emotion track, where the eyes go, and a mouth that follows the spoken line.
import fs from 'node:fs';
import { V, clamp, lerp, hash, smooth } from '../engine/core.js';

import { ENV, onEnv } from '../engine/env.js';
const readJ = (f) => { try { return fs.existsSync(ENV.voDir + f) ? JSON.parse(fs.readFileSync(ENV.voDir + f, 'utf8')) : {}; } catch { return {}; } };
/** Spoken lines per language: VO[lang][id] = { seconds, who, text, show }, LIP[lang][id] = { n, open[], wide[] }. */
export const VO = { en: {}, hi: {} };
export const LIP = { en: {}, hi: {} };
/** (Re)read the voices of the current project. */
export function loadVoices() {
  for (const k of Object.keys(VO)) if (!ENV.langs.includes(k) && k !== 'en' && k !== 'hi') { delete VO[k]; delete LIP[k]; }
  for (const l of new Set([...ENV.langs, 'en', 'hi'])) { VO[l] = readJ(`${l}.json`); LIP[l] = readJ(`${l}.lip.json`); }
}
loadVoices(); onEnv(loadVoices);
/** How long a line takes in the slowest of the film's languages (the picture is cut to fit them all). */
export const lineDur = (id) => Math.max(0, ...Object.values(VO).map((v) => v?.[id]?.seconds ?? 0)) || 2;

export const EMO = {
  neutral: {}, calm: { browTilt: -0.06, eyeOpen: 0.9 }, soft: { browTilt: 0.2, smile: 0.3, eyeOpen: 0.86 },
  stern: { browTilt: -0.38, eyeOpen: 0.82, smile: -0.15 }, determined: { browTilt: -0.55, eyeOpen: 1.0, smile: -0.1 },
  angry: { browTilt: -0.75, eyeOpen: 0.95, smile: -0.4, mouthWide: 0.2 }, rage: { browTilt: -1, eyeOpen: 1.15, smile: -0.55, pupil: 0.8, eyeShade: 0.45, mouthWide: 0.35 },
  sad: { browTilt: 0.6, eyeOpen: 0.78, smile: -0.4 }, grief: { browTilt: 0.9, eyeOpen: 0.7, smile: -0.6, tears: 0.95, blush: 0.35 }, weep: { browTilt: 1, eyeOpen: 0.12, smile: -0.7, tears: 1, blush: 0.4 },
  afraid: { browTilt: 0.7, eyeOpen: 1.25, pupil: 0.72, smile: -0.3 }, shock: { browL: 1, browR: 1, eyeOpen: 1.38, pupil: 0.58, mouthOpen: 0.3 },
  happy: { smile: 0.6, eyeSmile: 0.35, browL: 0.3, browR: 0.3 }, joy: { smile: 1, eyeSmile: 1, eyeOpen: 0.05, browL: 0.5, browR: 0.5, mouthOpen: 0.4, blush: 0.4 }, laugh: { smile: 1, eyeSmile: 1, eyeOpen: 0.06, mouthOpen: 0.7, mouthWide: 0.4 },
  smirk: { smile: 0.38, browL: 0.45, browR: -0.25, eyeOpen: 0.84 }, sneer: { smile: -0.25, browL: 0.5, browR: -0.4, eyeOpen: 0.8, mouthWide: 0.2 },
  pain: { browTilt: 0.5, eyeOpen: 0.3, smile: -0.6, mouthOpen: 0.22, mouthWide: 0.3 }, tired: { eyeOpen: 0.55, browTilt: 0.3, smile: -0.2 }, fading: { eyeOpen: 0.4, browTilt: 0.35, smile: 0.2 },
  dead: { eyeOpen: 0, smile: 0.1, browTilt: 0.1 }, wonder: { browL: 0.8, browR: 0.8, eyeOpen: 1.2, smile: 0.25, mouthOpen: 0.15 },
  king: { browTilt: -0.6, eyeOpen: 0.95, smile: -0.05, glow: '#ffd24a', glowAmt: 0.85 },
};
const NUM = ['browTilt', 'browL', 'browR', 'eyeOpen', 'eyeSmile', 'smile', 'mouthOpen', 'mouthWide', 'pupil', 'tears', 'blush', 'eyeShade', 'glowAmt'];
const BASE = { browTilt: 0, browL: 0, browR: 0, eyeOpen: 1, eyeSmile: 0, smile: 0, mouthOpen: 0, mouthWide: 0, pupil: 1, tears: 0, blush: undefined, eyeShade: 0, glowAmt: 0 };
const state = (e) => ({ ...BASE, ...(typeof e === 'string' ? (EMO[e] ?? {}) : e.like ? { ...(EMO[e.like] ?? {}), ...e } : e) });

/** The emotion at time t from keys [[t, name | {params}, blend seconds?]…]. */
export function emotionAt(keys, t) {
  if (!keys || !keys.length) return { ...BASE };
  let cur = state(keys[0][1]);
  for (let i = 0; i < keys.length; i++) {
    const [kt, e, bl = 0.35] = keys[i];
    if (t < kt) break;
    const to = state(e), u = bl <= 0 ? 1 : smooth((t - kt) / bl), nx = { ...to };
    for (const k of NUM) { const a = cur[k], b = to[k]; if (a === undefined && b === undefined) continue; nx[k] = lerp(a ?? BASE[k] ?? 0, b ?? BASE[k] ?? 0, u); }
    if (u < 1 && cur.glow && !to.glow) nx.glow = cur.glow;
    cur = nx;
  }
  return cur;
}

/** The mouth of whoever is speaking at t. cues: [{ id, who, t0 }]; returns { open, wide } or null when silent. */
/** The shorter reading of a line starts a little late inside its slot, so the spare time is not all at the end. */
export const lineOff = (id, lang) => { const d = lineDur(id), own = VO[lang]?.[id]?.seconds ?? d; return Math.min(0.45, Math.max(0, d - own) * 0.4); };
export function mouthAt(cues, who, t, lang) {
  for (const c of cues) {
    if (c.who !== who || c.vo) continue;
    const lp = LIP[lang]?.[c.id]; if (!lp) continue;
    const u = (t - c.t0 - lineOff(c.id, lang)) * 60;
    if (u < -2 || u > lp.n + 3) continue;
    const i = clamp(Math.floor(u), 0, lp.n - 1), j = Math.min(lp.n - 1, i + 1), f = clamp(u - i);
    return { open: lerp(lp.open[i], lp.open[j], f), wide: lerp(lp.wide[i], lp.wide[j], f), id: c.id };
  }
  return null;
}

/** Where the eyes point, as the face's look [x, y], for a target in the world seen from this head. head: { c, s, u, f } */
export function lookAt(head, target, gain = 2.3) {
  const d = V.norm(V.sub(target, head.c));
  return [clamp(V.dot(d, head.s) * gain, -1, 1), clamp(V.dot(d, head.u) * gain, -1, 1)];
}
/** Small quick movements of the eyes when nothing holds them. */
export function saccade(t, seed) {
  const period = 1.7, i = Math.floor(t / period + hash(seed) * 3), u = (t / period + hash(seed) * 3) - i;
  const a = [(hash(i * 3.1 + seed) - 0.5) * 0.36, (hash(i * 7.7 + seed) - 0.5) * 0.16], b = [(hash((i + 1) * 3.1 + seed) - 0.5) * 0.36, (hash((i + 1) * 7.7 + seed) - 0.5) * 0.16], k = smooth(clamp((u - 0.86) / 0.14));
  return [lerp(a[0], b[0], k), lerp(a[1], b[1], k)];
}
