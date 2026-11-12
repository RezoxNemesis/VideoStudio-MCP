// The fingerprint of a shot: a short code that changes when, and only when, the picture of that shot would change.
// It is made from what the shot will show (poses from the solved motion, the camera, focus, faces, designs, the set's
// options) and not from the text of the code, so moving a camera in one shot leaves every other shot's fingerprint alone.
// A shot is drawn again only when its fingerprint is new.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ShotRun } from '../engine/shot.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
/** Changes by itself whenever the engine or the kit is different (a new version of the app): cached shots are then drawn again. */
export const ENGINE_VERSION = (() => {
  const h = crypto.createHash('md5');
  for (const d of ['../engine', '../kit']) { try { for (const f of fs.readdirSync(path.join(HERE, d)).filter((n) => n.endsWith('.js')).sort()) h.update(f).update(fs.readFileSync(path.join(HERE, d, f))); } catch { /* a missing folder changes the version too */ } }
  return 'film-' + h.digest('hex').slice(0, 10);
})();

const round = (x) => (Number.isFinite(x) ? Math.round(x * 2000) / 2000 : x);
/** JSON with functions as their source, numbers rounded, typed arrays as lists, keys in order. */
export function stable(v, depth = 0) {
  if (v === null || v === undefined) return 'null';
  const t = typeof v;
  if (t === 'number') return String(round(v));
  if (t === 'string' || t === 'boolean') return JSON.stringify(v);
  if (t === 'function') return JSON.stringify('ƒ' + String(v));
  if (depth > 12) return '"…"';
  if (ArrayBuffer.isView(v)) return '[' + Array.from(v, round).join(',') + ']';
  if (Array.isArray(v)) return '[' + v.map((x) => stable(x, depth + 1)).join(',') + ']';
  // names that start with _ are notes the engine leaves on a thing while drawing it (never part of what it is)
  if (t === 'object') return '{' + Object.keys(v).filter((k) => k[0] !== '_').sort().map((k) => JSON.stringify(k) + ':' + stable(v[k], depth + 1)).join(',') + '}';
  return '""';
}

const ACTOR_SKIP = new Set(['face', 'ch', 'bk', 'pose', 'was', 'design', 'emote', 'look', 'faceFx']);

/**
 * shot: a runner shot (see kit/seq.js runnerShots). o: { lang, W, H, fps, shared (hash of the project's shared files),
 * seqText (hash of the text of the sequence's own file) }. Returns a 16-character code.
 */
export function fingerprint(shot, cast, o = {}) {
  const run = new ShotRun(shot, cast, { probe: true, lang: o.lang ?? 'en', W: o.W, H: o.H, fps: o.fps });
  const h = crypto.createHash('sha1');
  const hooks = shot.src?.hooks ?? '';
  h.update(stable({ v: ENGINE_VERSION, W: run.W, H: run.H, fps: run.fps, dur: shot.dur, t0: shot.t0, post: shot.post, warm: shot.warm, settle: shot.settle,
    set: shot.src?.set, setOpts: shot.src?.setOpts, hooks, shared: o.shared ?? '' }));
  // code that draws by itself (overlays, effects, extra things in the world) can depend on anything around it:
  // such a shot is also judged by what it looks like — a few small frames are drawn and their pixels counted in
  if (hooks.replace(/\u0001/g, '')) {
    const small = new ShotRun(shot, cast, { lang: o.lang ?? 'en', W: 240, H: 136, fps: o.fps, noGrain: true });
    for (const u of [0.02, 0.2, 0.4, 0.6, 0.8, 0.98]) h.update(small.draw(Math.min(shot.dur - 1 / run.fps, shot.dur * u)).data());
  }
  for (const a of run.actors) {
    const flat = {}; for (const k of Object.keys(a)) if (!ACTOR_SKIP.has(k)) flat[k] = a[k];
    h.update(stable({ flat, design: a.ch.d }));
  }
  // what happens over time: every 0.2 s, and the first and last frames
  const n = Math.max(2, Math.min(600, Math.ceil(shot.dur / 0.2) + 1)), q = new Int32Array(3);
  for (let i = 0; i < n; i++) {
    const t = Math.min(shot.dur - 1 / run.fps, (shot.dur * i) / (n - 1));
    const p = run.probe(Math.max(0, t));
    h.update(stable({ cam: p.cam, dof: p.dof, faces: p.faces, shown: p.shown }));
    for (const id of Object.keys(p.poses).sort()) {
      const pose = p.poses[id];
      for (const jn of Object.keys(pose.j)) { const v = pose.j[jn]; q[0] = Math.round(v[0] * 1000); q[1] = Math.round(v[1] * 1000); q[2] = Math.round(v[2] * 1000); h.update(new Uint8Array(q.buffer)); }
      if (pose.props) h.update(stable(pose.props));
    }
  }
  return h.digest('hex').slice(0, 16);
}
