// A code film on disk: a folder with film.js (its sequences), seq/*.js, script.json (lines and voices), cues.json
// (music and sound) and .vs/ (everything made from them: solved motion, voices, sound, rendered shots).
import { readPaint, paintKey } from './paint.js';
import { resolveLook } from '../engine/look.js';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { setEnv, ENV } from '../engine/env.js';
import { dropBakes } from '../engine/bake.js';
import { runnerShots, runnerShot } from './seq.js';
import { VO, lineOff, loadVoices } from './acting.js';
import { CAST as KIT_CAST } from './design.js';
import { PEOPLE } from './people.js';
import { fingerprint, ENGINE_VERSION } from './finger.js';

const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');
const KIT_URL = new URL('./index.js', import.meta.url).href;
export const DEFAULTS = { name: 'Film', width: 1920, height: 1080, fps: 60, langs: ['en'], crf: 15, preset: 'veryfast', entry: 'film.js', off: [], look: null };

/** Folders of a project. */
export function dirs(dir) {
  const vs = path.join(dir, '.vs');
  return { root: dir, vs, bake: path.join(vs, 'bake') + path.sep, vo: path.join(vs, 'vo') + path.sep, audio: path.join(vs, 'audio'), shots: path.join(vs, 'shots'), out: path.join(vs, 'out'), thumbs: path.join(vs, 'thumbs') };
}
export function readConfig(dir) {
  let c = {};
  try { c = JSON.parse(fs.readFileSync(path.join(dir, 'project.json'), 'utf8')); } catch { /* defaults */ }
  return { ...DEFAULTS, ...c };
}
/** Source files of a project (not what was made from them), with a hash of each one's text. */
export function sources(dir) {
  const out = [];
  const walk = (d, rel) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      if (e.name.startsWith('.') || e.name === 'node_modules') continue;
      const p = path.join(d, e.name), r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) { if (rel.split('/').length < 4) walk(p, r); }
      else if (/\.(js|mjs|json)$/.test(e.name)) { const text = fs.readFileSync(p, 'utf8'); out.push({ file: r, bytes: text.length, hash: md5(text) }); }
    }
  };
  walk(dir, '');
  return out.sort((a, b) => a.file.localeCompare(b.file));
}

let seqNo = 0;
/**
 * Load a project: runs its code. Returns { dir, config, FILM, CAST, shots, cues, src } — shots are runner shots with
 * `start` (film time) and `off` (switched off in project.json).
 */
export async function openProject(dir) {
  const D = dirs(dir), config = readConfig(dir);
  for (const d of [D.vs, D.bake, D.vo, D.audio, D.shots, D.out]) fs.mkdirSync(d, { recursive: true });
  // who says which line comes from script.json (so a film knows its speakers before any voice was made)
  const lines = {}; try { for (const l of JSON.parse(fs.readFileSync(path.join(dir, 'script.json'), 'utf8')).lines ?? []) lines[l.id] = l; } catch { /* a film without words */ }
  setEnv({ bakeDir: D.bake, voDir: D.vo, langs: config.langs, script: lines, look: config.look ?? null });
  resolveLook(config.look); // an unknown look is reported now, not when the first shot is drawn
  dropBakes(); loadVoices();
  // the film's code imports the kit through ./vs.js, written here so that it always points at this installation
  const shim = `// Written by VideoStudio: the film kit of this installation. Import from here: import { makeSeq, … } from '../vs.js'\nexport * from ${JSON.stringify(KIT_URL)};\n`;
  const shimPath = path.join(dir, 'vs.js');
  if (!fs.existsSync(shimPath) || fs.readFileSync(shimPath, 'utf8') !== shim) fs.writeFileSync(shimPath, shim);
  const entry = path.join(dir, config.entry);
  if (!fs.existsSync(entry)) throw new Error(`This folder has no ${config.entry}: not a code film.`);
  const mod = await import(`${pathToFileURL(entry).href}?v=${Date.now()}_${++seqNo}`);
  const FILM = mod.FILM ?? mod.default;
  if (!Array.isArray(FILM)) throw new Error(`${config.entry} must export FILM = [sequences] (made with makeSeq).`);
  const CAST = { ...PEOPLE, ...KIT_CAST, ...(mod.CAST ?? {}) };
  const src = sources(dir), off = new Set(config.off ?? []);
  const seqFile = (id) => src.find((f) => f.file === `seq/${id}.js`) ?? null;
  const allSeq = md5(src.filter((f) => f.file.startsWith('seq/')).map((f) => f.hash).join(''));
  const shared = md5(src.filter((f) => f.file.endsWith('.js') && !f.file.startsWith('seq/') && f.file !== 'vs.js').map((f) => f.file + f.hash).join(''));
  // a sequence file that imports a neighbour (from './…') can change when the neighbour does
  const crossSeq = src.some((f) => f.file.startsWith('seq/') && /from\s+['"]\.\/[^'"]+['"]/.test(fs.readFileSync(path.join(dir, f.file), 'utf8')));
  const shots = []; let t = 0;
  for (const seq of FILM) {
    seq.file = seqFile(seq.id)?.file ?? null;
    for (const sh of runnerShots(seq)) {
      // a shot is a whole number of frames: the film's clock counts those, so sound never drifts from the picture
      sh.frames = Math.max(1, Math.round(sh.dur * config.fps)); sh.len = sh.frames / config.fps;
      sh.off = off.has(sh.id); sh.start = t; if (!sh.off) t += sh.len;
      sh.seqText = seqFile(seq.id)?.hash ?? allSeq;
      if (crossSeq) sh.seqText = allSeq;
      shots.push(sh);
    }
  }
  // fixes painted over shots in Animate (paint/<shot>.json): drawn with the shot, part of its fingerprint
  for (const sh of shots) sh.paint = readPaint(dir, sh.id);
  // MOTIONS: performances that are not a sequence of their own (stunt and fight libraries): solved once, used by any shot through `bake: name`
  return { dir, D, config, FILM, CAST, shots, total: t, src, shared, MOTIONS: Array.isArray(mod.MOTIONS) ? mod.MOTIONS : [] };
}

/** A shot of the film starting `off` seconds in (for looking at one frame). */
export function shotFrom(P, id, off = 0) {
  for (const seq of P.FILM) for (const sh of seq.shotList) if (`${seq.id}_${sh.id}` === id) { const r = runnerShot(seq, sh, Math.max(0, Math.min(off, sh.t1 - sh.t0 - 1 / P.config.fps))); r.paint = readPaint(P.dir, id); if (r.paint && off > 0) r.paint = { ...r.paint, fixes: r.paint.fixes.map((f) => ({ ...f, span: f.span ? [f.span[0] - off, f.span[1] - off] : null })) }; return r; }
  throw new Error(`No shot ${id}.`);
}

const readJson = (f, d) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } };
// a character with no voice for a language keeps its English voice and speaks the language with it
const DEFAULT_VOICE = { en: 'af_heart', hi: 'hf_alpha', es: 'ef_dora', fr: 'ff_siwis', it: 'if_sara', pt: 'pf_dora', de: 'bm_george', ru: 'af_heart' };
/** JSON with keys in order (so the same thing always gives the same text). */
const ordered = (v) => (Array.isArray(v) ? v.map(ordered) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, ordered(v[k])])) : v);

/**
 * How one line of script.json is spoken in one language: the character's voice and own tone (cast), the line's tone
 * on top of it, the film's pace. → { id, lang, who, text (what is said), show (the subtitle), voice, tone, emphasis, key }
 */
export function voiceSpec(script, ln, lang) {
  const [voice, base = {}] = script.cast?.[ln.who]?.[lang] ?? script.cast?.[ln.who]?.en ?? [DEFAULT_VOICE[lang] ?? 'af_heart', {}];
  let tone; const t = ln.tone ?? {};
  if (typeof t === 'string') tone = { like: t, ...base };
  else {
    tone = { ...t };
    for (const [k, v] of Object.entries(base)) {
      if (k === 'pitch') tone.pitch = (tone.pitch ?? 0) + v; // the character's own pitch adds to the line's
      else if (k === 'rate') tone.rate = (tone.rate ?? 1) * v;
      else if (!(k in tone)) tone[k] = v;
    }
  }
  tone.rate = +((tone.rate ?? 1) * (script.pace?.[lang] ?? 1)).toFixed(3);
  const text = ln[`${lang}_say`] ?? ln[lang] ?? ln.en ?? '', emphasis = ln[`em_${lang}`] ?? [];
  return { id: ln.id, lang, who: ln.who, text, show: ln[lang] ?? ln.en ?? text, voice, tone, emphasis, key: md5(JSON.stringify(ordered([text, voice, tone, emphasis]))).slice(0, 12) };
}

/**
 * What a project still needs before it can be drawn and heard: solved motion per sequence, spoken lines, and the
 * music / sound beds of cues.json. Each entry says whether it is there (`have`) and, when not, how to make it.
 */
export function needs(P) {
  const bakes = [], lines = new Map(), problems = [];
  for (const seq of P.FILM) {
    for (const c of seq.cues) lines.set(c.id, c);
    if (!seq.motion) continue;
    const spec = { name: seq.bakeName, seconds: +(seq.T.dur + 0.3).toFixed(2), ...seq.motion(seq.T) };
    const text = JSON.stringify(spec), key = md5(text), kf = path.join(P.D.bake, `${seq.bakeName}.key`);
    const have = fs.existsSync(kf) && fs.readFileSync(kf, 'utf8') === key && fs.existsSync(path.join(P.D.bake, `${seq.bakeName}.bin`));
    bakes.push({ seq: seq.id, name: seq.bakeName, key, have, spec: have ? undefined : spec });
  }
  for (const m of P.MOTIONS ?? []) {
    const spec = { ...m, seconds: +(+m.seconds).toFixed(2) }, key = md5(JSON.stringify(spec)), kf = path.join(P.D.bake, `${m.name}.key`);
    const have = fs.existsSync(kf) && fs.readFileSync(kf, 'utf8') === key && fs.existsSync(path.join(P.D.bake, `${m.name}.bin`));
    bakes.push({ seq: m.name, name: m.name, key, have, spec: have ? undefined : spec });
  }
  const script = readJson(path.join(P.dir, 'script.json'), { lines: [] }), LN = Object.fromEntries((script.lines ?? []).map((l) => [l.id, l]));
  const voices = [];
  for (const lang of P.config.langs) {
    const raw = readJson(path.join(P.D.vo, 'raw', `${lang}.json`), {});
    for (const id of lines.keys()) {
      const ln = LN[id];
      if (!ln) { if (lang === P.config.langs[0]) problems.push(`Line ${id} is used in the film but is not in script.json.`); continue; }
      const sp = voiceSpec(script, ln, lang);
      const have = raw[id]?.key === sp.key && fs.existsSync(path.join(P.D.vo, 'raw', lang, `${id}.wav`)) && fs.existsSync(path.join(P.D.vo, lang, `${id}.wav`)) && !!VO[lang]?.[id];
      voices.push(have ? { id, lang, have } : { ...sp, have });
    }
  }
  const cues = readJson(path.join(P.dir, 'cues.json'), []), idx = readJson(path.join(P.D.audio, 'index.json'), {}), sounds = [];
  for (const c of Array.isArray(cues) ? cues : []) {
    if (!c?.name || !c.op) { problems.push('Every entry of cues.json needs a name and an op.'); continue; }
    const { name, note, dir = 'music', ...cmd } = c; void note;
    const key = md5(JSON.stringify(ordered(cmd))).slice(0, 12), have = idx[name]?.key === key && fs.existsSync(path.join(P.D.audio, dir, `${name}.wav`));
    sounds.push(have ? { name, dir, have, seconds: idx[name].seconds } : { name, dir, have, key, cmd });
  }
  return { bakes, voices, sounds, problems };
}

/** Every shot, spoken line and sound on the film's clock (shots that are switched off take no time). */
export function timeline(P) {
  let script = { lines: [] };
  try { script = JSON.parse(fs.readFileSync(path.join(P.dir, 'script.json'), 'utf8')); } catch { /* a film without words */ }
  const LN = Object.fromEntries((script.lines ?? []).map((l) => [l.id, l]));
  const out = { fps: P.config.fps, langs: P.config.langs, seqs: [], shots: [], cues: [], sounds: [] };
  for (const seq of P.FILM) {
    const shots = P.shots.filter((s) => s.seq === seq.id && !s.off);
    if (!shots.length) continue;
    const start = shots[0].start;
    // sequence time → film time (inside a shot that is off: where the cut now is; null = wholly cut away)
    const map = (ts) => { let acc = start; for (const s of shots) { if (ts < s.t0) return acc; if (ts < s.t0 + s.dur + 1e-6) return acc + Math.min(s.warpInv ? s.warpInv(ts - s.t0) : ts - s.t0, s.len); acc += s.len; } return acc; };
    const inside = (ts) => shots.some((s) => ts >= s.t0 - 1e-6 && ts < s.t0 + s.dur + 1e-6);
    for (const s of shots) out.shots.push({ id: s.id, seq: seq.id, start: +s.start.toFixed(4), dur: +s.len.toFixed(4), lip: !!s.lip, note: s.note ?? '' });
    out.seqs.push({ id: seq.id, start: +start.toFixed(3), end: +(shots[shots.length - 1].start + shots[shots.length - 1].len).toFixed(3) });
    for (const c of seq.cues) {
      if (!inside(c.t0)) continue; // its shot was cut
      const row = { id: c.id, who: c.who, seq: seq.id, at: +map(c.t0).toFixed(3), slot: +(c.t1 - c.t0).toFixed(3), vo: !!c.vo };
      for (const lang of P.config.langs) row[lang] = { off: +lineOff(c.id, lang).toFixed(3), secs: VO[lang]?.[c.id]?.seconds ?? 0, text: LN[c.id]?.[lang] ?? LN[c.id]?.en ?? VO[lang]?.[c.id]?.show ?? '' };
      out.cues.push(row);
    }
    for (const e of (seq.sound ? seq.sound(seq.T) : [])) {
      const r = { ...e, seq: seq.id };
      if ('t' in e) { if (!inside(e.t)) continue; r.t = +map(e.t).toFixed(3); }
      if ('t0' in e) r.t0 = +map(e.t0).toFixed(3);
      if ('t1' in e) r.t1 = +map(e.t1).toFixed(3);
      if ('t0' in r && 't1' in r && r.t1 - r.t0 < 0.05) continue;
      out.sounds.push(r);
    }
  }
  out.total = +P.total.toFixed(3);
  return out;
}

/** The rendered-shot index: { "<id>|<lang or ->": { fp, file, frames, ms } }. */
/** The file of a shot's index entry drawn with this fingerprint: the current one, or an earlier version kept beside it. */
export function variantFile(P, e, fp) {
  if (!e) return null;
  const hit = e.fp === fp ? e.file : (e.keep ?? []).find((k) => k.fp === fp)?.file;
  return hit && fs.existsSync(path.join(P.D.shots, hit)) ? hit : null;
}
export function readIndex(P) { try { return JSON.parse(fs.readFileSync(path.join(P.D.shots, 'index.json'), 'utf8')); } catch { return {}; } }
export function writeIndex(P, idx) { fs.writeFileSync(path.join(P.D.shots, 'index.json'), JSON.stringify(idx, null, 1)); }
export const shotKey = (sh, lang) => `${sh.id}|${sh.lip ? lang : '-'}`;

/**
 * Fingerprints of shots (all, or the ids given), per language where lips move. → [{ id, lang, key, fp, have, file }]
 * A sequence whose own file, motion and voices did not change keeps the fingerprints worked out last time, so a
 * long film is checked in a moment and only the sequence being edited is looked at again.
 */
export function fingerprints(P, ids = null) {
  const idx = readIndex(P), out = [], { width: W, height: H, fps } = P.config;
  const memoFile = path.join(P.D.shots, 'fingerprints.json'), memo = readJson(memoFile, {}); let dirty = false;
  const mt = (f) => { try { return fs.statSync(f).mtimeMs; } catch { return 0; } };
  const vo = P.config.langs.map((l) => mt(path.join(P.D.vo, `${l}.json`)) + '.' + mt(path.join(P.D.vo, `${l}.lip.json`))).join(',');
  for (const seq of P.FILM) {
    const shots = P.shots.filter((s) => s.seq === seq.id && (!ids || ids.includes(s.id)));
    if (!shots.length) continue;
    const skey = md5([ENGINE_VERSION, shots[0].seqText, P.shared, W, H, fps, P.config.langs.join(), JSON.stringify(P.config.look ?? null), vo, seq.bakeName ? mt(path.join(P.D.bake, `${seq.bakeName}.bin`)) : 0, ...(P.MOTIONS ?? []).map((m) => mt(path.join(P.D.bake, `${m.name}.bin`)))].join('|'));
    let m = memo[seq.id];
    if (!m || m.key !== skey) { m = memo[seq.id] = { key: skey, fp: {} }; dirty = true; }
    for (const sh of shots) {
      for (const lang of sh.lip ? P.config.langs : [P.config.langs[0]]) {
        const key = shotKey(sh, lang);
        let fp = m.fp[key], error;
        if (!fp) { try { fp = m.fp[key] = fingerprint(sh, P.CAST, { lang, W, H, fps, shared: P.shared }); dirty = true; } catch (e) { error = String(e?.message ?? e); } }
        const clean = fp, pk = fp ? paintKey(sh.paint) : '';
        if (pk) fp = md5(fp + '|paint|' + pk).slice(0, 20);
        const file = fp ? variantFile(P, idx[key], fp) : null;
        out.push({ id: sh.id, seq: sh.seq, lang: sh.lip ? lang : null, key, fp, fpClean: clean, have: !!file, file, off: !!sh.off, dur: sh.dur, painted: !!pk, error });
      }
    }
  }
  if (dirty) { try { fs.writeFileSync(memoFile, JSON.stringify(memo)); } catch { /* the memo is only a shortcut */ } }
  return out;
}
export { ENV };
