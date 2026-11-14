/**
 * VolTune Studio in the desktop app: natural, acted speech for every VolTune voice from Chatterbox Multilingual
 * (Resemble AI, MIT), run in its own Node worker (voice/worker.mjs, onnxruntime-node) so the ≈2 GB model never
 * sits in the window or the main process. The model is downloaded once (≈1.5 GB) into the app's data folder.
 *
 * The window asks studio.say; when a voice+mood has no speaker yet the worker answers {needRef}, the window
 * renders a short reference clip with VolTune (studio_refs.ts) and sends it to studio.speaker, then asks again.
 */
const { fork } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

/** o: { handle, userData, resources, send(channel, msg) } */
function setupStudio(o) {
  const dir = [path.join(o.resources || "", "voice"), path.join(__dirname, "..", "voice")].find((d) => d && fs.existsSync(path.join(d, "worker.mjs")));
  let proc = null;
  let ready = null;
  let seq = 0;
  const waiting = new Map();
  let log = "";
  function start() {
    if (!dir) throw new Error("The studio voice engine is not part of this installation.");
    proc = fork(path.join(dir, "worker.mjs"), [], { execPath: process.execPath, env: { ...process.env, ELECTRON_RUN_AS_NODE: "1" }, stdio: ["ignore", "pipe", "pipe", "ipc"], serialization: "advanced" });
    const keep = (d) => { log = (log + d).slice(-4000); };
    proc.stdout.on("data", keep);
    proc.stderr.on("data", keep);
    ready = new Promise((res) => {
      const first = (m) => { if (m && m.ready) { proc.off("message", first); res(true); } };
      proc.on("message", first);
    });
    proc.on("message", (m) => {
      const w = m && waiting.get(m.id);
      if (!w) return;
      if (m.event) { o.send("studio:progress", { id: m.id, ...m }); return; }
      waiting.delete(m.id);
      if (m.ok) w.resolve(m.value); else w.reject(new Error(m.error || "The studio voice failed."));
    });
    proc.on("exit", (code, sig) => {
      const tail = log ? "\n" + log.split("\n").slice(-6).join("\n") : "";
      for (const w of waiting.values()) w.reject(new Error(`The studio voice stopped (${sig || "code " + code}).${tail}`));
      waiting.clear();
      proc = null;
    });
  }
  async function call(method, params = {}) {
    if (!proc) start();
    await ready;
    const id = `s${++seq}`;
    return new Promise((resolve, reject) => {
      waiting.set(id, { resolve, reject });
      proc.send({ id, method, params: { ...params, dataDir: o.userData } });
    });
  }
  o.handle("studio.status", async () => (dir ? { available: true, ...(await call("status")) } : { available: false }));
  o.handle("studio.install", () => call("install"));
  o.handle("studio.say", (p) => call("say", p));
  o.handle("studio.speaker", (p) => call("speaker", p));
  return { stop: () => { try { proc && proc.kill(); } catch { /* already gone */ } } };
}

module.exports = { setupStudio };
