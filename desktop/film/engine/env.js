// Where the engine finds a film's files, and the tools it calls. Set once per project by whoever runs the engine.
export const ENV = {
  /** solved body motion: <bakeDir>/<name>.json + .bin */
  bakeDir: new URL('../bake/', import.meta.url).pathname,
  /** spoken lines: <voDir>/<lang>.json, <lang>.lip.json, <lang>/<id>.wav */
  voDir: new URL('../audio/vo/', import.meta.url).pathname,
  ffmpeg: 'ffmpeg',
  ffprobe: 'ffprobe',
  /** languages of the film, in order; the first is the main one */
  langs: ['en', 'hi'],
};
const listeners = new Set();
/** Change paths/tools; modules that cache files per project drop their caches. */
export function setEnv(o) { Object.assign(ENV, o); for (const fn of listeners) fn(ENV); }
export function onEnv(fn) { listeners.add(fn); }
