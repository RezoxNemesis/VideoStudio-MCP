// Faces that act: an emotion track, where the eyes go, and a mouth that follows the spoken line.
import fs from 'node:fs';
import { V, clamp, lerp, hash, smooth } from '../engine/core.js';

import { ENV, onEnv } from '../engine/env.js';
import { look } from '../engine/look.js';
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
  cheeky: { smile: 0.62, eyeSmile: 0.45, browL: 0.6, browR: -0.1, eyeOpen: 0.82, blush: 0.4 }, // a mischief-maker's grin: one brow up, eyes narrowed by the smile
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
const ENVS = new WeakMap();
/** The loudest the line gets within a sixth of a second of each moment (60 a second): a syllable is measured against it. */
function speechEnv(lp) { let e = ENVS.get(lp); if (e) return e; const o = lp.open, n = lp.n; e = new Float32Array(n); for (let i = 0; i < n; i++) { let m = 0; for (let k = Math.max(0, i - 10); k <= Math.min(n - 1, i + 10); k++) if (o[k] > m) m = o[k]; e[i] = m; } ENVS.set(lp, e); return e; }
/** How lively a mouth track is: changes of drawing a second and the share of the line the mouth is (nearly) shut. */
export function lipStats(cues, who, t0, t1, lang, fps = 24) {
  const v = []; for (let t = t0; t < t1; t += 1 / fps) { const m = mouthAt(cues, who, t, lang); if (m) v.push(m.open); }
  if (v.length < 3) return null; let ch = 0; for (let i = 1; i < v.length; i++) if (Math.abs(v[i] - v[i - 1]) > 0.05) ch++;
  return { changes: ch / (v.length / fps), shut: v.filter((x) => x < 0.15).length / v.length, max: Math.max(...v) };
}
export function mouthAt(cues, who, t, lang) {
  for (const c of cues) {
    if (c.who !== who || c.vo) continue;
    const lp = LIP[lang]?.[c.id]; if (!lp) continue;
    const u = (t - c.t0 - lineOff(c.id, lang)) * 60;
    if (u < -4 || u > lp.n + 6) continue;
    // the anime way (serifu / kuchipaku): a mouth has a few drawings — shut, half open, open — and each is HELD a
    // twelfth of a second; it opens downwards, small when the voice is quiet, and widens only a little on wide vowels.
    // A smooth, every-frame mouth that also spreads to the corners reads as a grin (the owner, 17 Nov 2026: the lips
    // were "more on wide"). look.lip: 'smooth' keeps the old, continuous mouth.
    // 'speech' (the default since 17 Nov, evening): the measured fix for both complaints. 'serifu' held ONE drawing for
    // half a second at a time (2.8 changes a second: the mouth looked stuck open) and 'smooth' opened to the tongue on
    // loud vowels (too wide). Here every syllable opens and nearly closes again (7–10 changes a second, like the Vikas
    // opening that the owner liked), the opening is measured against the line's own loudness close by, it never goes
    // past 0.72, and each drawing is held a twelfth of a second, a frame and a half ahead of the sound.
    const L = look(), mode = L?.lip ?? (L?.style === 'anime' || L?.style === 'multiplane' ? 'speech' : 'smooth'), serifu = mode === 'serifu';
    if (mode === 'speech') {
      // (the study of 17 Nov: the mouth leads the sound a frame or two; a shut M/B/P is held at least two frames; at the
      // end of a phrase the last vowel eases through a half-open drawing and the mouth shuts 2–3 frames into the silence,
      // never wide open to shut in one frame; hold vowels, snap consonants)
      const env = speechEnv(lp), at = (k) => (k >= 0 && k < lp.n ? lp.open[k] : 0);
      const draw = (k) => { const o = at(k); if (o < 0.06) return 0; const e = env[clamp(k, 0, lp.n - 1)], rel = o / Math.max(e, 0.2); return Math.min(1, 0.08 + 0.64 * rel ** 1.6) * (0.75 + 0.25 * Math.min(1, e)); };
      const i = Math.floor((u + 2.5) / 5) * 5, open = draw(i), ic = clamp(i, 0, lp.n - 1);
      if (open === 0) {
        let pause = true; for (let k = i; k < i + 12; k++) if (at(k) >= 0.06) { pause = false; break; }
        const before = draw(i - 5);
        if (pause && before > 0.3) return { open: Math.min(0.26, before * 0.45), wide: 0, id: c.id };
        return { open: 0, wide: 0, id: c.id };
      }
      return { open, wide: clamp(lp.wide[ic] * 0.5, -0.6, 0.3), id: c.id };
    }
    if (serifu) {
      const k = clamp(Math.floor(u / 5) * 5, 0, lp.n - 1), k2 = Math.min(lp.n - 1, k + 2), o = Math.max(lp.open[k], lp.open[k2]), w = (lp.wide[k] + lp.wide[k2]) / 2;
      const open = o < 0.14 ? 0 : o < 0.45 ? 0.36 : 0.7;
      return { open, wide: clamp(w * 0.45, -0.6, 0.3), id: c.id };
    }
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
/**
 * How eyes move: a dart. They do not glide to a new target and they do not teleport: the move takes two or three frames
 * and most of it happens in the first. d: seconds since the eyes set off → how much of the way they have gone (0…1).
 */
export const DART = 0.085;
export function dartTo(d) { if (d <= 0) return 0; if (d >= DART) return 1; const u = 1 - d / DART; return 1 - u * u * u; }
/**
 * A big change of gaze takes a blink with it (the lids shut as the eyes set off and open on the new target); a small
 * one does not. d: seconds since the change, size: how far the gaze went (in the face's look units, 0…2),
 * force: true = blink whatever the size, false = never. → 0 (open) … 1 (shut)
 */
export function gazeBlink(d, size, force) {
  if (force === false || d < 0 || d >= 0.2) return 0;
  if (force !== true && size < 0.45) return 0;
  return Math.pow(Math.sin((d / 0.2) * Math.PI), 0.6);
}
/**
 * The eyes lead, the head follows. One list of beats — [{ t, at: actor id | 'cam' | [x, y, z], head: 0…1, lead?, fade? }] —
 * gives both halves of a look: `look`, the eyes' keys for the film's actor, and `head`, the Stage overlays that turn the
 * head, each setting off `lead` seconds (0.22) after the eyes. head: 0 is a look with the eyes alone — careful, shy,
 * sneaking — and the head stays where it was; a small head (0.4) is a guarded look, a full one (0.9) an open one.
 *   const L = eyesLead([...], { end: T.dur });   actors: look: L.look   ·   steps: { op: 'stage.overlay', actor, add: L.head }
 */
export function eyesLead(beats, o = {}) {
  const look = beats.map((b) => [b.t, b.at, ...(b.blink !== undefined ? [{ blink: b.blink }] : [])]);
  const moves = beats.filter((b) => (b.head ?? 0.6) > 0), late = (b) => b.lead ?? o.lead ?? 0.22;
  const head = moves.map((b, i) => {
    const nx = moves[i + 1];
    return { t0: i === 0 ? b.t : b.t + late(b), t1: nx ? nx.t + late(nx) : (o.end ?? b.t + 4), fade: b.fade ?? o.fade ?? 0.5, look: b.at, head: b.head ?? 0.6 };
  });
  return { look, head };
}
/**
 * Where the eyes are at ts, for look keys [[t, target, { blink? }], …]: on the target of the latest key, having darted
 * there from the one before, with a blink if the change was big. aim(target) → the look [x, y] of that target for these
 * eyes right now (null when it is not there). → { look: [x, y] | null (free: let them wander), blink: 0…1 }
 */
export function gazeOf(keys, ts, aim) {
  let cur = null, prev = null;
  if (keys) for (const k of keys) { if (ts >= k[0]) { prev = cur; cur = k; } else break; }
  const to = cur ? aim(cur[1]) : null;
  if (!to) return { look: null, blink: 0 };
  const d = ts - cur[0], from = prev && d < 0.2 ? aim(prev[1]) : null;
  if (!from) return { look: to, blink: 0 };
  const k = dartTo(d);
  return { look: k >= 1 ? to : [lerp(from[0], to[0], k), lerp(from[1], to[1], k)], blink: gazeBlink(d, Math.hypot(to[0] - from[0], to[1] - from[1]), cur[2]?.blink) };
}
/** Small quick movements of the eyes when nothing holds them. */
export function saccade(t, seed) {
  const period = 1.7, i = Math.floor(t / period + hash(seed) * 3), u = (t / period + hash(seed) * 3) - i;
  const a = [(hash(i * 3.1 + seed) - 0.5) * 0.36, (hash(i * 7.7 + seed) - 0.5) * 0.16], b = [(hash((i + 1) * 3.1 + seed) - 0.5) * 0.36, (hash((i + 1) * 7.7 + seed) - 0.5) * 0.16], k = smooth(clamp((u - 0.86) / 0.14));
  return [lerp(a[0], b[0], k), lerp(a[1], b[1], k)];
}
