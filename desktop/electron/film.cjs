/**
 * Code Studio in the main process: code films live in folders (by default ~/Videos/VideoStudio/Code/<name>), are read
 * and drawn by the film farm (film-pool.cjs → film/runner/worker.mjs), and everything made from the code is kept in
 * the film's own .vs folder so only what changed is ever drawn again.
 */
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const crypto = require("node:crypto");
const { execFile } = require("node:child_process");
const { FilmPool } = require("./film-pool.cjs");

const TEXT = /\.(js|mjs|json|md|txt|srt)$/i;
const safeName = (s) => String(s || "").replace(/[^\p{L}\p{N} _.-]+/gu, "").trim().replace(/\s+/g, " ").slice(0, 60);

/** o: { handle, mustAllow, ffmpeg, ffprobe, root (where films live), userData, send(channel, msg), resources } */
function setupFilm(o) {
  const filmDir = [path.join(o.resources || "", "film"), path.join(__dirname, "..", "film")].find((d) => d && fs.existsSync(path.join(d, "runner", "worker.mjs")));
  let pool = null;
  const farm = () => {
    if (!filmDir) throw new Error("The film engine is not part of this installation.");
    if (!pool) pool = new FilmPool({ script: path.join(filmDir, "runner", "worker.mjs"), execPath: process.execPath, env: { ELECTRON_RUN_AS_NODE: "1" }, ffmpeg: o.ffmpeg, ffprobe: o.ffprobe });
    return pool;
  };
  const ROOT = o.root;
  /** A film folder the person may use: inside their folders and holding a project.json. */
  const film = (dir) => {
    const d = o.mustAllow(dir);
    if (!fs.existsSync(path.join(d, "project.json"))) throw new Error(`Not a code film (no project.json): ${d}`);
    return d;
  };
  /** A source file of a film: inside the folder, not inside .vs, a text file. */
  const source = (dir, file) => {
    const d = film(dir), f = path.resolve(d, String(file || ""));
    if (!f.startsWith(d + path.sep)) throw new Error("That file is outside the film's folder.");
    const rel = path.relative(d, f).split(path.sep).join("/");
    if (rel.startsWith(".") || rel.includes("/.") || rel.includes("node_modules")) throw new Error("That is not one of the film's own files.");
    if (!TEXT.test(f)) throw new Error("Film files are text: .js, .json, .md, .txt.");
    return { d, f, rel };
  };
  const run = (cmd, args) => new Promise((res, rej) => execFile(cmd, args, { maxBuffer: 1 << 26 }, (e, so, se) => (e ? rej(new Error(String(se || e.message).slice(-600))) : res(so))));
  const readJson = async (f, d) => { try { return JSON.parse(await fsp.readFile(f, "utf8")); } catch { return d; } };
  const bytes = (b) => Buffer.from(b.buffer, b.byteOffset, b.byteLength);
  const templates = () => { try { return fs.readdirSync(path.join(filmDir, "templates"), { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => { const info = JSON.parse(fs.readFileSync(path.join(filmDir, "templates", e.name, "project.json"), "utf8")); return { id: e.name, name: info.name, about: info.about || "" }; }); } catch { return []; } };

  const h = o.handle;
  h("film.info", async () => ({ root: ROOT, engine: !!filmDir, templates: templates(), workers: farm().size, reference: filmDir ? path.join(filmDir, "API.md") : null }));
  h("film.reference", async () => fsp.readFile(path.join(filmDir, "API.md"), "utf8"));
  h("film.list", async () => {
    const out = [];
    try {
      for (const e of await fsp.readdir(ROOT, { withFileTypes: true })) {
        if (!e.isDirectory()) continue;
        const d = path.join(ROOT, e.name), c = await readJson(path.join(d, "project.json"), null);
        if (!c) continue;
        const st = await fsp.stat(d);
        out.push({ dir: d, name: c.name || e.name, width: c.width, height: c.height, fps: c.fps, langs: c.langs, changed: st.mtimeMs });
      }
    } catch { /* no films yet */ }
    return out.sort((a, b) => b.changed - a.changed);
  });
  h("film.create", async (name, template = "starter", config = {}) => {
    const nm = safeName(name); if (!nm) throw new Error("Give the film a name.");
    const d = path.join(ROOT, nm.replace(/ /g, "_"));
    if (fs.existsSync(d)) throw new Error(`There already is a film called ${nm}.`);
    const src = path.join(filmDir, "templates", String(template).replace(/[^\w-]/g, ""));
    if (!fs.existsSync(src)) throw new Error(`No template ${template}. Templates: ${templates().map((t) => t.id).join(", ")}`);
    await fsp.mkdir(ROOT, { recursive: true });
    await fsp.cp(src, d, { recursive: true, filter: (f) => !/(^|\/)(\.vs|vs\.js)$/.test(f) });
    const c = await readJson(path.join(d, "project.json"), {});
    delete c.about;
    await fsp.writeFile(path.join(d, "project.json"), JSON.stringify({ ...c, ...config, name: nm }, null, 1));
    return d;
  });
  h("film.open", (dir) => farm().ask("open", { dir: film(dir) }));
  h("film.status", (dir, scale) => farm().ask("status", { dir: film(dir), scale }));
  h("film.needs", (dir) => farm().ask("needs", { dir: film(dir) }));
  h("film.timeline", (dir) => farm().ask("timeline", { dir: film(dir) }));
  h("film.read", async (dir, file) => { const s = source(dir, file); return fsp.readFile(s.f, "utf8"); });
  h("film.write", async (dir, file, text) => {
    const s = source(dir, file);
    if (s.rel === "vs.js") throw new Error("vs.js is written by VideoStudio (it points at the film kit): leave it as it is.");
    await fsp.mkdir(path.dirname(s.f), { recursive: true });
    await fsp.writeFile(s.f, String(text));
    return { file: s.rel, bytes: Buffer.byteLength(String(text)) };
  });
  // Nothing is ever deleted for good: a removed file goes to the film's .vs/trash.
  h("film.remove", async (dir, file) => {
    const s = source(dir, file), t = path.join(s.d, ".vs", "trash");
    await fsp.mkdir(t, { recursive: true });
    const to = path.join(t, `${Date.now()}-${s.rel.replace(/\//g, "_")}`);
    await fsp.rename(s.f, to);
    return { file: s.rel, keptAt: to };
  });
  h("film.config", async (dir, patch) => {
    const d = film(dir), f = path.join(d, "project.json"), c = await readJson(f, {});
    if (patch && typeof patch === "object") { Object.assign(c, patch); await fsp.writeFile(f, JSON.stringify(c, null, 1)); }
    return c;
  });
  h("film.frame", (dir, id, t, lang, scale, quality) => farm().ask("frame", { dir: film(dir), id, t, lang, scale, quality }));
  h("film.sheet", async (dir, id, p = {}) => {
    const r = await farm().ask("sheet", { dir: film(dir), id, ...p });
    return { ...r, jpeg: (await fsp.readFile(r.file)).toString("base64") };
  });
  h("film.build", async (p) => {
    const dir = film(p.dir);
    return farm().build({ ...p, dir, out: p.out ? o.mustAllow(p.out) : undefined }, (e) => o.send("film:progress", { dir, ...e }));
  });
  h("film.cancel", async (dir) => { farm().cancel("build:" + film(dir)); return true; });
  h("film.farm", async () => (pool ? pool.status() : { workers: 0, busy: 0, waiting: 0, started: false }));
  h("film.scan", (file, threshold) => farm().work("scan", { file: o.mustAllow(file), threshold }));

  // ---- Sensei: the film is judged by every farm worker at once (shots shared out), then the whole is joined
  h("film.sensei", async (dir, opts = {}) => {
    const d = film(dir), pool = farm(), t0 = Date.now(), group = "sensei:" + d;
    pool.cancelled.delete(group);
    const info = await pool.ask("open", { dir: d });
    const only = Array.isArray(opts.only) && opts.only.length ? new Set(opts.only) : null;
    const ids = info.shots.filter((s) => !s.off && (!only || only.has(s.id) || only.has(s.seq))).map((s) => s.id);
    if (!ids.length) throw new Error("No shots to look at.");
    const n = Math.max(1, Math.min(pool.size, ids.length)), chunks = Array.from({ length: n }, () => []);
    ids.forEach((id, i) => chunks[i % n].push(id));
    let done = 0;
    o.send("sensei:progress", { dir: d, phase: "shots", done: 0, of: ids.length });
    const parts = await Promise.all(chunks.map((c) => pool.work("senseiShots", { dir: d, ids: c, opts }, (e) => { if (e.done) { done++; o.send("sensei:progress", { dir: d, phase: "shots", done, of: ids.length, shot: e.shot }); } }, group)));
    o.send("sensei:progress", { dir: d, phase: "finish" });
    const r = await pool.work("senseiFinish", { dir: d, parts, opts: { ...opts, ms: Date.now() - t0, scope: only ? "part" : "film" } }, (e) => o.send("sensei:progress", { dir: d, phase: e.phase || "finish", done: e.done, of: e.of }), group);
    o.send("sensei:progress", { dir: d, phase: "done", ms: Date.now() - t0 });
    return r;
  });
  h("film.senseiCancel", async (dir) => { farm().cancel("sensei:" + film(dir)); return true; });
  h("film.senseiReport", async (dir) => { try { return JSON.parse(await fsp.readFile(path.join(film(dir), ".vs", "sensei", "latest.json"), "utf8")); } catch { return null; } });
  h("film.senseiMarkdown", async (dir) => { try { return await fsp.readFile(path.join(film(dir), ".vs", "sensei", "latest.md"), "utf8"); } catch { return null; } });
  h("film.senseiIssue", (dir, issue, scale) => farm().ask("senseiIssue", { dir: film(dir), issue, scale }));
  h("film.senseiStyle", (files) => farm().ask("senseiStyle", { files: (files || []).map((f) => o.mustAllow(f)) }));
  h("film.senseiStyles", () => farm().ask("senseiStyles", {}));
  h("film.senseiImage", async (file) => { const f = o.mustAllow(file); if (!/\.jpe?g$/i.test(f) || !f.includes(`${path.sep}.vs${path.sep}sensei${path.sep}`)) throw new Error("Not a Sensei picture."); return (await fsp.readFile(f)).toString("base64"); });

  // ---- what the window makes for a film (motion solved by the Stage, voices, music) and hands over to keep ----
  h("film.putBake", async (dir, name, key, meta, bin) => {
    const d = path.join(film(dir), ".vs", "bake"), n = String(name).replace(/[^\w-]/g, "");
    await fsp.mkdir(d, { recursive: true });
    await fsp.writeFile(path.join(d, `${n}.bin`), bytes(bin));
    await fsp.writeFile(path.join(d, `${n}.json`), typeof meta === "string" ? meta : JSON.stringify(meta));
    await fsp.writeFile(path.join(d, `${n}.key`), String(key));
    await farm().reloadAll();
    return true;
  });
  h("film.putVoice", async (dir, lang, id, wav, meta) => {
    const l = String(lang).replace(/[^\w-]/g, ""), i = String(id).replace(/[^\w-]/g, ""), d = path.join(film(dir), ".vs", "vo", "raw");
    await fsp.mkdir(path.join(d, l), { recursive: true });
    await fsp.writeFile(path.join(d, l, `${i}.wav`), bytes(wav));
    const f = path.join(d, `${l}.json`), all = await readJson(f, {});
    all[i] = meta;
    await fsp.writeFile(f, JSON.stringify(all, null, 1));
    return true;
  });
  h("film.voicesFit", async (dir) => { const r = await farm().ask("voicesFit", { dir: film(dir) }); await farm().reloadAll(); return r; });
  h("film.putSound", async (dir, sub, name, data, ext, key, info) => {
    const s = ["music", "sfx", "amb"].includes(sub) ? sub : "music", n = String(name).replace(/[^\w-]/g, ""), d = path.join(film(dir), ".vs", "audio");
    await fsp.mkdir(path.join(d, s), { recursive: true });
    const tmp = path.join(os.tmpdir(), `vs-film-${crypto.randomUUID()}.${String(ext || "wav").replace(/[^\w]/g, "")}`), out = path.join(d, s, `${n}.wav`);
    await fsp.writeFile(tmp, bytes(data));
    try { await run(o.ffmpeg, ["-y", "-v", "error", "-i", tmp, "-ar", "48000", "-ac", "2", out]); } finally { await fsp.rm(tmp, { force: true }); }
    const secs = parseFloat(await run(o.ffprobe, ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out])) || 0;
    const f = path.join(d, "index.json"), all = await readJson(f, {});
    all[n] = { key, seconds: +secs.toFixed(2), dir: s, info: info || null };
    await fsp.writeFile(f, JSON.stringify(all, null, 1));
    return { seconds: all[n].seconds };
  });
  /** Bring a sound file of the person's own into the film (music they made elsewhere, a recording). */
  h("film.addSound", async (dir, sub, name, from) => {
    const s = ["music", "sfx", "amb"].includes(sub) ? sub : "music", n = String(name).replace(/[^\w-]/g, ""), d = path.join(film(dir), ".vs", "audio", s);
    await fsp.mkdir(d, { recursive: true });
    await run(o.ffmpeg, ["-y", "-v", "error", "-i", o.mustAllow(from), "-ar", "48000", "-ac", "2", path.join(d, `${n}.wav`)]);
    return true;
  });

  // ---- the library ----
  const thumbs = path.join(o.userData, "film-thumbs");
  h("film.people", (group) => farm().ask("people", { group }));
  h("film.sets", () => farm().ask("sets", {}));
  h("film.personThumb", async (id, size = 320) => {
    const f = path.join(thumbs, `${String(id).replace(/[^\w-]/g, "")}-${size}.jpg`);
    try { return { jpeg: (await fsp.readFile(f)).toString("base64"), cached: true }; } catch { /* not drawn yet */ }
    const r = await farm().ask("personThumb", { id, size });
    await fsp.mkdir(thumbs, { recursive: true });
    await fsp.writeFile(f, Buffer.from(r.jpeg, "base64"));
    return r;
  });
  return { close: () => pool && pool.close(), filmDir };
}

module.exports = { setupFilm };
