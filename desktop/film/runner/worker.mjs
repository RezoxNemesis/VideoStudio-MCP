// A film worker: one process that loads a code film and draws it. The app runs several side by side (one shot each),
// so a film renders on every core and a crash in one never takes the app down.
//   forked by the app:  messages { id, method, params } → { id, ok, value | error }, and { id, event, … } while working
//   by hand:            node runner/worker.mjs <method> '<json params>'
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { register } from 'node:module';
import crypto from 'node:crypto';
import { GlobalFonts, createCanvas } from '@napi-rs/canvas';
import { setEnv, ENV } from '../engine/env.js';
import { ShotRun, renderVideo, renderSheet } from '../engine/shot.js';
import { loadBake, poseAt, dropBakes } from '../engine/bake.js';
import { Character } from '../engine/character.js';
import { makeCam } from '../engine/camera.js';
import { Scene, sky, ground } from '../engine/scene.js';
import { openProject, needs, timeline, fingerprints, readIndex, writeIndex, shotKey, shotFrom, sources, readConfig, dirs } from '../kit/project.js';
import { fitAndLip } from '../kit/lip.js';
import { mix, srt, kitSounds } from '../kit/sound.js';
import { PROPS } from '../kit/props.js';
import { PLACES } from '../kit/places.js';
import { flashScan } from '../kit/qa.js';
import { PEOPLE, PEOPLE_LIST, PEOPLE_GROUPS } from '../kit/people.js';
import { CAST as KIT_CAST } from '../kit/design.js';
import * as SETS from '../kit/sets.js';
import { ENGINE_VERSION } from '../kit/finger.js';

register('./hooks.mjs', import.meta.url);
const HERE = path.dirname(new URL(import.meta.url).pathname), ASSETS = path.join(HERE, '..', 'assets');
for (const f of fs.existsSync(path.join(ASSETS, 'fonts')) ? fs.readdirSync(path.join(ASSETS, 'fonts')) : []) { try { GlobalFonts.registerFromPath(path.join(ASSETS, 'fonts', f)); } catch { /* a font that does not load is not fatal */ } }

let P = null, Pkey = '';
/** The project in `dir`, loaded once and again whenever one of its source files changed. */
async function project(dir) {
  const key = dir + '|' + sources(dir).map((f) => f.file + f.hash).join('') + '|' + JSON.stringify(readConfig(dir)) + '|' + stamp(dirs(dir).vo) + stamp(dirs(dir).bake);
  if (!P || Pkey !== key) { P = await openProject(dir); Pkey = key; }
  return P;
}
/** Changes when voices or bakes were written (they decide timing and poses). */
function stamp(d) { try { return fs.readdirSync(d).filter((f) => /\.(json|key)$/.test(f)).map((f) => f + fs.statSync(path.join(d, f)).mtimeMs).join(','); } catch { return ''; } }
const md5 = (x) => crypto.createHash('md5').update(x).digest('hex');
/** Changes when any file below a folder was written. */
function tree(d) { try { return fs.readdirSync(d, { recursive: true, withFileTypes: true }).filter((e) => e.isFile()).map((e) => { const st = fs.statSync(path.join(e.parentPath ?? e.path, e.name)); return e.name + st.size + '.' + st.mtimeMs; }).sort().join(','); } catch { return ''; } }
const jpeg = (cv, q = 82) => cv.toBuffer('image/jpeg', q).toString('base64');
const shotOf = (Pr, id) => { const s = Pr.shots.find((q) => q.id === id); if (!s) throw new Error(`No shot ${id}. Shots: ${Pr.shots.slice(0, 40).map((q) => q.id).join(' ')}`); return s; };

const methods = {
  async init(p) { setEnv({ ffmpeg: p.ffmpeg ?? ENV.ffmpeg, ffprobe: p.ffprobe ?? ENV.ffprobe }); return { engine: ENGINE_VERSION, fonts: GlobalFonts.families.length, pid: process.pid }; },

  /** What the film is: its sequences and shots on the film's clock. */
  async open({ dir }) {
    const Pr = await project(dir);
    return {
      dir, config: Pr.config, total: +Pr.total.toFixed(3), engine: ENGINE_VERSION,
      seqs: Pr.FILM.map((s) => ({ id: s.id, file: s.file, seconds: +s.T.dur.toFixed(3), lines: s.cues.map((c) => c.id), marks: s.T.marks, motion: !!s.motion })),
      shots: Pr.shots.map((s) => ({ id: s.id, seq: s.seq, start: +s.start.toFixed(3), dur: +s.dur.toFixed(3), t0: +s.t0.toFixed(3), lip: !!s.lip, off: !!s.off, note: s.note ?? '', actors: s.actors.map((a) => a.id) })),
      files: Pr.src,
    };
  },
  async needs({ dir }) { return needs(await project(dir)); },
  async timeline({ dir }) { return timeline(await project(dir)); },
  async fingerprints({ dir, ids }) { return fingerprints(await project(dir), ids ?? null); },
  /** Every shot with what is on disk for it: 'ready' (drawn and still right), 'changed' (drawn, but the film moved on) or 'new'. */
  async status({ dir, scale = 1 }) {
    const Pr = await project(dir), idx = readIndex(Pr), sfx = scale !== 1 ? '@' + scale : '', by = new Map();
    const n = needs(Pr), unsolved = new Set(n.bakes.filter((b) => !b.have).map((b) => b.seq));
    for (const f of fingerprints(Pr)) {
      const e = idx[f.key + sfx], on = !!e && fs.existsSync(path.join(Pr.D.shots, e.file));
      // a shot whose performance is not solved yet cannot be looked at: it is simply new, not wrong
      const wait = !!f.error && unsolved.has(f.seq);
      const v = { lang: f.lang, fp: f.fp ?? null, state: wait ? (on ? 'changed' : 'new') : f.error ? 'error' : on && e.fp === f.fp ? 'ready' : on ? 'changed' : 'new', file: on ? e.file : null, ms: on ? e.ms : null, error: wait ? undefined : f.error };
      if (!by.has(f.id)) by.set(f.id, []);
      by.get(f.id).push(v);
    }
    return {
      total: +Pr.total.toFixed(3), fps: Pr.config.fps, width: Pr.config.width, height: Pr.config.height, langs: Pr.config.langs,
      shots: Pr.shots.map((s) => ({ id: s.id, seq: s.seq, start: +s.start.toFixed(3), dur: +s.dur.toFixed(3), lip: !!s.lip, off: !!s.off, note: s.note ?? '', variants: by.get(s.id) ?? [] })),
      needs: { motion: n.bakes.filter((b) => !b.have).map((b) => b.seq), voices: n.voices.filter((v) => !v.have).map((v) => `${v.id}.${v.lang}`), sounds: n.sounds.filter((x) => !x.have).map((x) => x.name), problems: n.problems },
    };
  },

  /** Draw one shot to its own video file. → { key, fp, file, frames, ms } (not yet in the index: see commit). */
  async render({ dir, id, lang, scale = 1 }, emit) {
    const Pr = await project(dir), sh = shotOf(Pr, id), l = lang ?? Pr.config.langs[0];
    const fp = fingerprints(Pr, [id]).find((f) => (f.lang ?? l) === l || f.lang === null);
    if (!fp || fp.error) throw new Error(fp?.error ?? `No fingerprint for ${id}.`);
    const W = Math.round((Pr.config.width * scale) / 2) * 2, H = Math.round((Pr.config.height * scale) / 2) * 2;
    const file = `${id}${sh.lip ? '.' + l : ''}${scale !== 1 ? '@' + scale : ''}-${fp.fp}.mp4`, out = path.join(Pr.D.shots, file);
    const r = await renderVideo(sh, Pr.CAST, out, { lang: l, W, H, fps: Pr.config.fps, crf: Pr.config.crf, preset: Pr.config.preset, progress: (f, n) => emit({ event: 'progress', shot: id, frame: f, frames: n }) });
    return { key: shotKey(sh, l) + (scale !== 1 ? '@' + scale : ''), fp: fp.fp, file, frames: r.frames, ms: r.ms, seconds: +sh.dur.toFixed(3) };
  },
  /** Record rendered shots in the index and delete the files they replace. */
  async commit({ dir, entries }) {
    const Pr = await project(dir), idx = readIndex(Pr);
    for (const e of entries) {
      const old = idx[e.key]?.file;
      idx[e.key] = { fp: e.fp, file: e.file, frames: e.frames, ms: e.ms, seconds: e.seconds, at: Date.now() };
      if (old && old !== e.file) fs.rmSync(path.join(Pr.D.shots, old), { force: true });
    }
    writeIndex(Pr, idx);
    return { shots: Object.keys(idx).length };
  },

  /** One frame of a shot, now (no need to draw what comes before it). → { jpeg (base64), width, height } */
  async frame({ dir, id, t = 0, lang, scale = 0.5, quality = 82 }) {
    const Pr = await project(dir), base = shotOf(Pr, id), sh = shotFrom(Pr, id, t);
    const W = Math.round((Pr.config.width * scale) / 2) * 2, H = Math.round((Pr.config.height * scale) / 2) * 2;
    const run = new ShotRun(sh, Pr.CAST, { lang: lang ?? Pr.config.langs[0], W, H, fps: Pr.config.fps, noGrain: true });
    const cv = run.draw(0);
    return { jpeg: jpeg(cv, quality), width: W, height: H, t: +Math.min(t, base.dur).toFixed(3), shot: id };
  },
  /** Several moments of a shot in one picture (drawn in order, so cloth and hair are as in the film). */
  async sheet({ dir, id, times, count = 6, cols = 3, cell = 0.5, lang }) {
    const Pr = await project(dir), sh = shotOf(Pr, id);
    const ts = times ?? Array.from({ length: count }, (_, i) => +((sh.dur * (i + 0.5)) / count).toFixed(2));
    const out = path.join(Pr.D.thumbs, `${id}_sheet.jpg`); fs.mkdirSync(Pr.D.thumbs, { recursive: true });
    renderSheet(sh, Pr.CAST, out, ts, { cols, cell, lang: lang ?? Pr.config.langs[0], W: undefined });
    return { file: out, times: ts };
  },

  /** After voices were spoken: fit the languages to each other and listen for the mouth movement. */
  async voicesFit({ dir }) { const D = dirs(dir), c = readConfig(dir); setEnv({ voDir: D.vo, langs: c.langs }); const r = fitAndLip(D.vo, c.langs); P = null; return r; },
  /** Forget loaded motion (after a solve wrote new files). */
  async reload() { dropBakes(); P = null; return true; },

  /** Mix the film's sound for one language (kept when nothing it is made from changed). */
  async mix({ dir, lang }) {
    const Pr = await project(dir), l = lang ?? Pr.config.langs[0], TL = timeline(Pr), out = path.join(Pr.D.out, `mix.${l}.wav`), sf = out + '.sig';
    const sig = md5(JSON.stringify([TL.cues, TL.sounds, TL.total, l]) + tree(path.join(Pr.D.vo, l)) + tree(Pr.D.audio));
    if (fs.existsSync(out) && fs.existsSync(sf) && fs.readFileSync(sf, 'utf8').split('\n')[0] === sig) return { ...JSON.parse(fs.readFileSync(sf, 'utf8').split('\n')[1]), kept: true };
    const r = mix(TL, Pr.D, l, out);
    fs.writeFileSync(sf, sig + '\n' + JSON.stringify(r));
    return r;
  },
  /**
   * Join the rendered shots (no re-encoding), lay the mixed sound under them, add subtitles as a track.
   * subs: 'soft' (a subtitle track, instant) | 'burn' (drawn into the picture: the whole film is encoded again) | 'none'.
   */
  async assemble({ dir, lang, out, subs = 'soft', sound = true, scale = 1 }) {
    const Pr = await project(dir), l = lang ?? Pr.config.langs[0], TL = timeline(Pr), idx = readIndex(Pr), t0 = Date.now(), sfx = scale !== 1 ? '@' + scale : '';
    const missing = [], list = [];
    for (const s of Pr.shots) {
      if (s.off) continue;
      const e = idx[shotKey(s, l) + sfx];
      if (!e || !fs.existsSync(path.join(Pr.D.shots, e.file))) { missing.push(s.id); continue; }
      list.push(`file '${path.join(Pr.D.shots, e.file).replace(/'/g, "'\\''")}'`);
    }
    if (missing.length) throw new Error(`Not rendered yet: ${missing.slice(0, 12).join(', ')}${missing.length > 12 ? ` and ${missing.length - 12} more` : ''}. Render the changed shots first.`);
    const name = (Pr.config.name || 'film').replace(/[^\w\- ]+/g, '').trim().replace(/\s+/g, '_') || 'film';
    const listFile = path.join(Pr.D.out, `${name}.${l}.txt`), srtFile = path.join(Pr.D.out, `${name}.${l}.srt`), audio = path.join(Pr.D.out, `mix.${l}.wav`);
    const target = out ?? path.join(Pr.D.out, `${name}.${l}${scale !== 1 ? '.draft' : ''}.mp4`), tmp = target.replace(/\.mp4$/, '.part.mp4');
    fs.writeFileSync(listFile, list.join('\n') + '\n'); const text = srt(TL, l); fs.writeFileSync(srtFile, text);
    const hasAudio = sound && fs.existsSync(audio), soft = subs === 'soft' && text.trim().length > 0, burn = subs === 'burn' && text.trim().length > 0;
    const args = ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', listFile];
    if (hasAudio) args.push('-i', audio);
    if (soft) args.push('-i', srtFile);
    args.push('-map', '0:v');
    if (hasAudio) args.push('-map', '1:a');
    if (soft) args.push('-map', `${hasAudio ? 2 : 1}:s`);
    if (burn) args.push('-vf', `subtitles=${srtFile.replace(/([:\\'])/g, '\\$1')}:force_style='FontName=Poppins,FontSize=20,Outline=1.6,Shadow=0,MarginV=34'`, '-c:v', 'libx264', '-preset', Pr.config.preset, '-crf', String(Pr.config.crf + 2), '-pix_fmt', 'yuv420p');
    else args.push('-c:v', 'copy');
    if (hasAudio) args.push('-c:a', 'aac', '-b:a', '256k');
    args.push('-t', TL.total.toFixed(4)); // the picture decides the length (a subtitle track ends with its last line)
    if (soft) args.push('-c:s', 'mov_text', '-metadata:s:s:0', `language=${l === 'hi' ? 'hin' : l === 'en' ? 'eng' : l}`);
    args.push('-movflags', '+faststart', tmp);
    const sig = md5(list.join('|') + text + subs + String(hasAudio) + (hasAudio ? fs.statSync(audio).mtimeMs + '.' + fs.statSync(audio).size : '') + TL.total + (burn ? Pr.config.crf + Pr.config.preset : '')), sf = target + '.sig';
    if (fs.existsSync(target) && fs.existsSync(sf) && fs.readFileSync(sf, 'utf8') === sig) return { file: target, bytes: fs.statSync(target).size, seconds: TL.total, shots: list.length, sound: hasAudio, subtitles: soft ? 'track' : burn ? 'burnt in' : 'none', ms: Date.now() - t0, kept: true };
    execFileSync(ENV.ffmpeg, args, { stdio: ['ignore', 'ignore', 'pipe'], maxBuffer: 1 << 26 });
    fs.renameSync(tmp, target); fs.writeFileSync(sf, sig);
    return { file: target, bytes: fs.statSync(target).size, seconds: TL.total, shots: list.length, sound: hasAudio, subtitles: soft ? 'track' : burn ? 'burnt in' : 'none', ms: Date.now() - t0 };
  },
  /** Look at every frame of a video for parts of the picture that change for an instant and come back. */
  async scan({ file, threshold }) { return flashScan(file, { threshold }); },

  /* ---- the library -------------------------------------------------------------------------------------------- */
  async people({ group }) { return { groups: PEOPLE_GROUPS, people: PEOPLE_LIST.filter((p) => !group || p.group === group), cast: Object.keys(KIT_CAST) }; },
  async sounds() { return kitSounds(); },
  async props() { return { props: Object.keys(PROPS) }; },
  async sets() { return { places: Object.keys(PLACES), storySets: ['town', 'village', 'courtyard', 'hall', 'terrace', 'bridge', 'steps'], sets: [...Object.keys(PLACES), 'town', 'village', 'courtyard', 'hall', 'terrace', 'bridge', 'steps'], hours: ['dawn', 'day', 'dusk', 'night'], props: Object.keys(PROPS), sounds: kitSounds() }; },
  /** A picture of a library person (standing, three-quarter view). size: height in pixels. */
  async personThumb({ id, size = 360, design, yaw = 0.5 }) {
    const d = design ?? PEOPLE[id] ?? KIT_CAST[id]; if (!d) throw new Error(`No person ${id}.`);
    const meta = PEOPLE_LIST.find((p) => p.id === id), preset = meta?.preset ?? 'villager';
    setEnv({ bakeDir: path.join(ASSETS, 'poses') + path.sep });
    const bake = loadBake('poses'), who = bake.actors[preset] ? preset : Object.keys(bake.actors)[0], pose = poseAt(bake, who, 0.5);
    const W = Math.round(size * 0.62), H = size, cv = createCanvas(W, H), g = cv.getContext('2d');
    g.fillStyle = '#1b1e26'; g.fillRect(0, 0, W, H);
    const ch = new Character(d), h = pose.height, c = pose.j.root, cam = makeCam({ pos: [c[0] + Math.sin(yaw) * 5.2, h * 0.56, c[2] + Math.cos(yaw) * 5.2], target: [c[0], h * 0.52, c[2]], fov: 2 * Math.atan((h * 0.62) / 5.2) * (180 / Math.PI) }, W, H);
    for (let i = 0; i < 40; i++) ch.step(pose, 1 / 60, i / 60, { ground: 0 });
    ch.draw(g, cam, pose, { blink: 0 }, (col) => col, { light: [-0.5, -0.8], ground: 0, shadow: false });
    P = null; Pkey = '';
    return { jpeg: jpeg(cv, 86), width: W, height: H, preset: who };
  },
};

async function handle(msg, emit) {
  const fn = methods[msg.method];
  if (!fn) throw new Error(`No method ${msg.method}.`);
  return fn(msg.params ?? {}, emit);
}

if (process.send) {
  process.on('message', async (msg) => {
    try { const value = await handle(msg, (e) => process.send({ id: msg.id, ...e })); process.send({ id: msg.id, ok: true, value }); }
    catch (e) { process.send({ id: msg.id, ok: false, error: String(e?.stack ?? e).split('\n').slice(0, 6).join('\n') }); }
  });
  process.on('disconnect', () => process.exit(0));
  process.send({ ready: true, pid: process.pid });
} else {
  const [method, json] = process.argv.slice(2);
  if (!method) { console.log('methods:', Object.keys(methods).join(' ')); process.exit(0); }
  try { const v = await handle({ method, params: json ? JSON.parse(json) : {} }, (e) => process.stderr.write(`\r${JSON.stringify(e)}   `)); console.log(JSON.stringify(v, (k, x) => (k === 'jpeg' ? `<${x.length} base64 chars>` : x), 1)); }
  catch (e) { console.error(String(e?.stack ?? e)); process.exit(1); }
}
void Scene; void sky; void ground;
