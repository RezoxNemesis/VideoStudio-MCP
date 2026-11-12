/**
 * The Blender forge: Blender, run without a window, as a machine that makes 3D assets for the Stage. A recipe (tree,
 * rock, terrain, building, lettering …), a script of Blender Python, or a model file of another kind goes in; a .glb
 * file comes out into the person's Models folder, ready for stage.prop. Blender itself is the person's own install.
 */
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const crypto = require("node:crypto");
const { execFile } = require("node:child_process");

function setupForge(o) {
  const dir = [path.join(o.resources || "", "forge"), path.join(__dirname, "..", "forge")].find((d) => d && fs.existsSync(path.join(d, "run.py")));
  const MODELS = o.models;
  const run = (cmd, args, timeout = 600_000) => new Promise((res) => execFile(cmd, args, { maxBuffer: 1 << 26, timeout }, (e, so, se) => res({ code: e ? (typeof e.code === "number" ? e.code : 1) : 0, out: String(so || ""), err: String(se || ""), killed: !!(e && e.killed), missing: !!(e && e.code === "ENOENT") })));
  let found; // undefined = not looked yet; null = no Blender
  async function blender(again = false) {
    if (found !== undefined && !again) return found;
    const home = os.homedir(), cands = [];
    if (process.env.VIDEOSTUDIO_BLENDER) cands.push([process.env.VIDEOSTUDIO_BLENDER, []]);
    cands.push(["blender", []], ["/usr/bin/blender", []], ["/usr/local/bin/blender", []], ["/snap/bin/blender", []], ["/opt/blender/blender", []], [path.join(home, ".local/bin/blender"), []]);
    for (const base of [home, path.join(home, "Applications"), path.join(home, "Downloads"), "/opt"]) {
      try { for (const n of fs.readdirSync(base)) if (/^blender/i.test(n) && fs.existsSync(path.join(base, n, "blender"))) cands.push([path.join(base, n, "blender"), []]); } catch { /* no such folder */ }
    }
    cands.push(["flatpak", ["run", "org.blender.Blender"]]);
    for (const [cmd, pre] of cands) {
      if (cmd.includes("/") && !fs.existsSync(cmd)) continue;
      const r = await run(cmd, [...pre, "--version"], 30_000);
      const m = /Blender\s+(\d+\.\d+(?:\.\d+)?)/.exec(r.out);
      if (r.code === 0 && m) return (found = { cmd, pre, version: m[1] });
    }
    return (found = null);
  }
  const recipes = () => {
    const out = {};
    try { for (const m of fs.readFileSync(path.join(dir, "recipes.py"), "utf8").matchAll(/^def (\w+)\(P\):\n\s+"""([^]*?)"""/gm)) if (!m[1].startsWith("_")) out[m[1]] = m[2].trim(); } catch { /* no forge files */ }
    return out;
  };
  const safe = (s, d) => (String(s || "").replace(/[^\p{L}\p{N} _-]+/gu, "").trim().replace(/\s+/g, "_").slice(0, 60) || d);

  async function job(spec, name) {
    if (!dir) throw new Error("The forge's files are not part of this installation.");
    const b = await blender();
    if (!b) throw new Error("Blender was not found on this PC. Install Blender (blender.org, or your software centre) and try again; a Blender in an unusual place can be named in the environment variable VIDEOSTUDIO_BLENDER.");
    await fsp.mkdir(MODELS, { recursive: true });
    const out = path.join(MODELS, `${safe(name, "model")}.glb`), tmp = path.join(os.tmpdir(), `vs-forge-${crypto.randomUUID()}`);
    await fsp.mkdir(tmp, { recursive: true });
    try {
      const j = { out, params: spec.params || {}, ground: spec.ground !== false };
      if (spec.code !== undefined) { j.script = path.join(tmp, "script.py"); await fsp.writeFile(j.script, String(spec.code)); }
      else if (spec.convert) j.convert = o.mustAllow(spec.convert);
      else j.recipe = String(spec.recipe || "");
      await fsp.writeFile(path.join(tmp, "job.json"), JSON.stringify(j));
      const t0 = Date.now();
      const r = await run(b.cmd, [...b.pre, "--background", "--factory-startup", "--python-exit-code", "3", "--python", path.join(dir, "run.py"), "--", path.join(tmp, "job.json")]);
      const res = /^FORGE_RESULT (.*)$/m.exec(r.out), bad = /^FORGE_ERROR (.*)$/m.exec(r.out);
      if (!res) {
        const trace = r.out.split("\n").filter((l) => /^\s+File |Error|error|line \d+/.test(l)).slice(-8).join("\n");
        throw new Error(`${bad ? bad[1] : r.killed ? "Blender took too long and was stopped." : "Blender did not finish the job."}${trace ? "\n" + trace : ""}`.slice(0, 1800));
      }
      const st = await fsp.stat(out);
      return { path: out, name: path.basename(out), bytes: st.size, seconds: Math.round((Date.now() - t0) / 100) / 10, ...JSON.parse(res[1]) };
    } finally {
      await fsp.rm(tmp, { recursive: true, force: true });
    }
  }

  const h = o.handle;
  h("forge.status", async (again) => { const b = await blender(!!again); return { blender: b ? (b.pre.length ? `${b.cmd} ${b.pre.join(" ")}` : b.cmd) : null, version: b ? b.version : null, recipes: recipes(), folder: MODELS, ready: !!b && !!dir }; });
  h("forge.make", (recipe, name, params) => job({ recipe, params }, name || recipe));
  h("forge.script", (name, code, params) => job({ code, params }, name));
  h("forge.convert", (from, name) => job({ convert: from, ground: false }, name || path.basename(String(from)).replace(/\.\w+$/, "")));
  h("forge.list", async () => {
    const out = [];
    try { for (const n of await fsp.readdir(MODELS)) if (/\.(glb|gltf)$/i.test(n)) { const st = await fsp.stat(path.join(MODELS, n)); out.push({ name: n, path: path.join(MODELS, n), bytes: st.size, modified: st.mtimeMs }); } } catch { /* none yet */ }
    return { folder: MODELS, models: out.sort((a, b) => b.modified - a.modified) };
  });
}

module.exports = { setupForge };
