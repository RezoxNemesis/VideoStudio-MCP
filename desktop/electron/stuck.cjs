// A copy of the app that hangs must not keep every new start away.
// Only one copy runs (the single-instance lock): a second start hands over to the first and quits. When the first copy's
// main thread hangs, it never shows its window again, and the person could not open the app at all — only killing it from
// a terminal helped (16 Nov 2026). The running copy touches its session marker every 30 s; a second start that finds the
// marker untouched for two minutes, and the first copy still there, ends that copy and starts in its place.
const fs = require("node:fs");

/**
 * o: { session (the marker file), pidFile, now (ms), maxAge (ms, 120 000), alive(pid) → bool, isApp(pid) → bool }
 * → the pid of a hung first copy to end, or null (it is alive and well, or not there).
 */
function hungFirst(o) {
  let pid = null, beat = 0;
  try { pid = Number(String(fs.readFileSync(o.pidFile, "utf8")).trim()) || null; } catch { return null; }
  try { beat = fs.statSync(o.session).mtimeMs; } catch { return null; }
  if (!pid || pid === process.pid) return null;
  if (o.now - beat < (o.maxAge ?? 120_000)) return null;
  if (!o.alive(pid) || !o.isApp(pid)) return null;
  return pid;
}
const alive = (pid) => { try { process.kill(pid, 0); return true; } catch { return false; } };
/** Is that process this app (and not something that reused its number)? On Linux its command line says so. */
const isApp = (pid, exeName = "videostudio") => { try { return fs.readFileSync(`/proc/${pid}/cmdline`, "utf8").includes(exeName); } catch { return process.platform !== "linux"; } };

module.exports = { hungFirst, alive, isApp };
