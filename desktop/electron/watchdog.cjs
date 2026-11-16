// A window that stops answering is brought back: the app must never sit frozen (16 Nov 2026 — the owner's graphics card
// was full with a Blender render, the app's GPU process died three times, its window hung and the Claude link, which lives
// in the window, went with it). Chromium says "unresponsive"; if it is still so after `ms`, the window is restarted (its
// page is reloaded — the film workers, the files and anything drawing in the background are not touched).

/** o: { ms (12 000), onHang(), setTimeout?, clearTimeout? } → { unresponsive(), responsive(), hangs } */
function watchdog(o) {
  const st = o.setTimeout || setTimeout, ct = o.clearTimeout || clearTimeout;
  let t = null; const w = { hangs: 0 };
  w.unresponsive = () => { if (t) return; t = st(() => { t = null; w.hangs++; o.onHang(); }, o.ms ?? 12_000); };
  w.responsive = () => { if (t) { ct(t); t = null; } };
  return w;
}
module.exports = { watchdog };
