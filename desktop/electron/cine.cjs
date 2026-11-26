/**
 * Cine 3D: films staged in real depth and drawn on the graphics card.
 *
 * A Cine film is a folder (by default ~/Videos/VideoStudio/Cine/<name>) with a film.js that stages every shot with the
 * engine in film/cine/web (painted layers standing in real depth, toon bodies driven by captured motion, one real
 * lens: focus, cranes, orbits, dolly-zooms). A page of the engine draws the frames in a hidden window on this PC's
 * graphics card and hands each one to ffmpeg; pieces of five seconds are kept, so only what changed is drawn again.
 *
 * Files are looked for in this order: the film's own folder, the shared assets folder (Cine/assets: bodies, painted
 * textures, fonts), the engine that came with the app. So a film can carry its own newer engine file.
 *
 * CLAY — the stop-motion studio. A film whose film.js says `// @engine clay` near its top is drawn by the clay engine
 * (film/cine/web/clay: clay puppets with talking mouths, felt and card sets, a miniature lens). Its files are looked
 * for in the film, the shared assets, the clay engine, then the Cine engine. The same commands draw it.
 *
 * Pieces are encoded on the graphics card (NVENC) when this PC can, so the processor is left alone; otherwise x264.
 *
 * `createCine` knows nothing about Electron (it is given a way to open a page), so it is tested with any browser.
 * The whole method, with every number that was used, is film/cine/GUIDE.md (command cine.help).
 */
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const http = require("node:http");
const os = require("node:os");
const { spawn, execFile } = require("node:child_process");

const MIME = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".json": "application/json", ".png": "image/png", ".jpg": "image/jpeg", ".bin": "application/octet-stream", ".woff2": "font/woff2", ".wav": "audio/wav" };
const safeName = (s) => String(s || "").replace(/[^\p{L}\p{N} _.-]+/gu, "").trim().replace(/\s+/g, " ").slice(0, 60);
const safeId = (s) => { const v = String(s ?? ""); if (!/^[A-Za-z0-9_.-]{1,80}$/.test(v)) throw new Error(`Not a usable name: ${v}`); return v; };

/**
 * o: { root (where Cine films live), web (the engine's pages), guide (GUIDE.md), ffmpeg, ffprobe?,
 *      openPage(url, { width, height }) → { close(), gone: Promise<string> }  (a hidden window that draws the page),
 *      movies? (where finished films are also copied), log?(line), send?(msg) }
 */
function createCine(o) {
  const ROOT = o.root, ASSETS = path.join(ROOT, "assets");
  let server = null, port = 0;
  const waits = new Map(); // flag name → { resolve }
  const jobs = {}; // encoder per piece
  const logs = []; // last lines the pages said
  let ctxFilm = null; // the film folder the server answers from
  let ctxEngine = null; // an engine folder of its own kind (web/clay for a clay film), looked in before the Cine engine
  let gpuEnc; // undefined: not asked yet; null: this PC cannot; { bin }: the ffmpeg that encodes on the graphics card
  let useEnc = "auto"; // what the running render asked for: auto | gpu | x264
  let run = null; // the render that is going: { film, total, done, piece, state, started, error?, out? }

  const say = (line) => { logs.push(line); if (logs.length > 400) logs.splice(0, logs.length - 400); if (o.log && /^(ERR|EXC)/.test(line)) o.log(`cine: ${line}`); };
  const body = (req) => new Promise((r) => { const c = []; req.on("data", (d) => c.push(d)); req.on("end", () => r(Buffer.concat(c))); });
  const flag = (name, v) => { const w = waits.get(name); if (w) { waits.delete(name); w.resolve(v ?? "ok"); } };

  function filmDir(film) {
    const name = String(film || "");
    const d = path.isAbsolute(name) ? path.resolve(name) : path.join(ROOT, safeName(name));
    if (o.mustAllow) o.mustAllow(d);
    if (!fs.existsSync(path.join(d, "film.js"))) throw new Error(`Not a Cine film (no film.js): ${d}`);
    return d;
  }
  /** A file asked for by a page: the film's own, then the shared assets, then the engine's. */
  function resolve(rel) {
    const clean = path.posix.normalize("/" + rel).replace(/^\/+/, "");
    if (!clean || clean.split("/").includes("..")) return null;
    for (const base of [ctxFilm, ASSETS, ctxEngine, o.web]) {
      if (!base) continue;
      const f = path.join(base, clean);
      if (f.startsWith(base + path.sep) && fs.existsSync(f) && fs.statSync(f).isFile()) return f;
    }
    return null;
  }
  /** Which engine draws a film: the one its film.js names in its first lines (`// @engine clay`, `// @engine codescape`) if the app has it (web/<name>), else "cine". */
  function engineOf(dir) {
    try { const fd = fs.openSync(path.join(dir, "film.js"), "r"), b = Buffer.alloc(600), n = fs.readSync(fd, b, 0, 600, 0); fs.closeSync(fd); const m = /@engine\s+([a-z][a-z0-9_-]{1,30})\b/.exec(b.toString("utf8", 0, n)); return m && o.web && fs.existsSync(path.join(o.web, m[1])) ? m[1] : "cine"; } catch { return "cine"; }
  }
  const engineDir = (dir) => { const e = engineOf(dir); return e !== "cine" && o.web ? path.join(o.web, e) : null; };
  /** A file of one named film, whatever film the drawing pages are on: the film's own, the shared assets, its engine's, the Cine engine's. (Cine Studio reads through /f/<film>/…) */
  function resolveIn(dir, rel) {
    const clean = path.posix.normalize("/" + rel).replace(/^\/+/, "");
    if (!clean || clean.split("/").includes("..")) return null;
    for (const base of [dir, ASSETS, engineDir(dir), o.web]) { if (!base) continue; const f = path.join(base, clean); if (f.startsWith(base + path.sep) && fs.existsSync(f) && fs.statSync(f).isFile()) return f; }
    return null;
  }
  /** The engine's own library film (people, hands, holds, speech: every piece seen live), or a film by name. */
  const LIB = "@library";
  const studioDir = (film) => (String(film) === LIB ? path.join(path.dirname(o.web), "films", "codescape-library") : filmDir(film));
  /** What a module offers: its exported functions and constants with the comment written above each. */
  function exportsOf(file) {
    const out = []; let text = ""; try { text = fs.readFileSync(file, "utf8"); } catch { return out; }
    const re = /(?:\/\*\*([\s\S]*?)\*\/\s*)?export\s+(?:async\s+)?(function|const|class)\s+([A-Za-z_$][\w$]*)\s*(\([^)]*\))?/g; let m;
    while ((m = re.exec(text))) out.push({ name: m[3], kind: m[2], args: (m[4] || "").slice(0, 160), doc: (m[1] || "").replace(/^\s*\*\s?/gm, "").replace(/\s+/g, " ").trim().slice(0, 600) });
    return out;
  }
  let lastFrame = null; // the newest frame a drawing page handed over (for Cine Studio's live view of a render)
  /** The ffmpeg that encodes on the graphics card, if this PC has one that works (asked once). */
  async function gpuEncoder() {
    if (gpuEnc !== undefined) return gpuEnc;
    try { gpuEnc = o.gpuEncoder ? (await o.gpuEncoder()) || null : null; } catch { gpuEnc = null; }
    return gpuEnc;
  }
  function encoder(q, tmp, gpu) {
    const head = ["-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", `${+q.w}x${+q.h}`, "-r", String(+q.fps || 30), "-i", "-", "-vf", "vflip,format=yuv420p"], crf = Math.min(40, Math.max(0, +q.crf || 15));
    // on the graphics card: constant quality a little above the x264 number that looks the same, with look-ahead and adaptive quantisation
    const args = gpu
      ? [...head, "-c:v", "h264_nvenc", "-preset", "p6", "-tune", "hq", "-rc", "vbr", "-cq", String(Math.min(40, crf + 3)), "-b:v", "0", "-maxrate", "160M", "-bufsize", "320M", "-spatial-aq", "1", "-temporal-aq", "1", "-rc-lookahead", "20", "-bf", "3", "-g", String(Math.round((+q.fps || 30) * 2)), "-profile:v", "high", "-pix_fmt", "yuv420p", "-movflags", "+faststart", tmp]
      : [...head, "-c:v", "libx264", "-preset", /^(ultrafast|superfast|veryfast|faster|fast|medium|slow)$/.test(q.preset) ? q.preset : "fast", "-crf", String(crf), "-pix_fmt", "yuv420p", "-movflags", "+faststart", tmp];
    return spawn(gpu ? gpu.bin : o.ffmpeg, args, { stdio: ["pipe", "ignore", "pipe"] });
  }
  async function start() {
    if (server) return port;
    server = http.createServer(async (req, res) => {
      const u = new URL(req.url, "http://x"), q = Object.fromEntries(u.searchParams);
      try {
        if (u.pathname === "/frame") {
          const b = await body(req), id = safeId(q.job); let j = jobs[id];
          // a piece that starts again (or whose encoder died) starts a new file: an old job must never block it
          if (j && (q.first || j.dead)) { try { j.p.kill("SIGKILL"); } catch { /* gone */ } delete jobs[id]; j = null; }
          if (!j) {
            const out = path.join(ctxFilm, "out"); await fsp.mkdir(out, { recursive: true });
            const gpu = useEnc === "x264" ? null : await gpuEncoder();
            const tmp = path.join(out, `${id}.part.mp4`), p = encoder(q, tmp, gpu);
            j = jobs[id] = { p, tmp, n: 0, err: "" };
            p.stderr.on("data", (d) => { j.err = (j.err + d).slice(-600); });
            p.on("close", () => { j.dead = true; }); p.stdin.on("error", () => { j.dead = true; });
          }
          if (b.length !== +q.w * +q.h * 4) throw new Error(`frame of ${b.length} bytes for ${q.w}x${q.h}`);
          if (!j.p.stdin.write(b)) await new Promise((r) => { const go = () => { j.p.stdin.off("drain", go); j.p.off("close", go); r(); }; j.p.stdin.once("drain", go); j.p.once("close", go); });
          j.n++; if (run) run.frames = (run.frames || 0) + 1;
          if (j.n % 6 === 1) lastFrame = { b, w: +q.w, h: +q.h, job: id, n: j.n, at: Date.now() };
          res.end("ok");
        } else if (u.pathname === "/done") {
          const id = safeId(q.job), j = jobs[id]; let ok = false;
          if (j) {
            await new Promise((r) => { j.p.on("close", r); j.p.stdin.end(); });
            ok = fs.existsSync(j.tmp) && !j.err;
            if (ok) await fsp.rename(j.tmp, path.join(ctxFilm, "out", `${id}.mp4`)); else say(`ERR encoder ${id}: ${j.err}`);
            delete jobs[id];
          }
          res.end("ok"); flag(id, ok ? "done" : "failed");
        } else if (u.pathname === "/png") {
          const b = await body(req), name = safeId(q.name), out = path.join(ctxFilm, "out"); await fsp.mkdir(out, { recursive: true });
          const f = path.join(out, `${name}.jpg`), vf = "vflip" + (+q.scale ? `,scale=${Math.round(+q.scale)}:-2:flags=area` : "");
          const p = spawn(o.ffmpeg, ["-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", `${+q.w}x${+q.h}`, "-i", "-", "-vf", vf, "-frames:v", "1", "-q:v", "2", f], { stdio: ["pipe", "ignore", "ignore"] });
          p.stdin.on("error", () => {}); p.stdin.end(b); await new Promise((r) => p.on("close", r)); res.end("ok");
        } else if (u.pathname === "/log") { say((await body(req)).toString().slice(0, 2000)); res.end("ok"); }
        else if (u.pathname === "/flag") { res.end("ok"); flag(safeId(q.name), q.v || "ok"); }
        else if (u.pathname === "/say") { res.end("ok"); flag(safeId(q.name), (await body(req)).toString()); }
        else if (u.pathname.startsWith("/f/")) { // a named film's files (Cine Studio): /f/<film>/<file>
          const rest = decodeURIComponent(u.pathname.slice(3)), k = rest.indexOf("/"), f = k > 0 ? resolveIn(studioDir(rest.slice(0, k)), rest.slice(k + 1)) : null;
          if (!f) { res.statusCode = 404; return res.end("not found"); }
          res.setHeader("Content-Type", MIME[path.extname(f).toLowerCase()] || "application/octet-stream"); res.setHeader("Cache-Control", "no-store"); fs.createReadStream(f).pipe(res);
        } else if (u.pathname.startsWith("/studio/")) {
          const what = u.pathname.slice(8), json = (v) => { res.setHeader("Content-Type", "application/json"); res.setHeader("Cache-Control", "no-store"); res.end(JSON.stringify(v)); };
          if (what === "films") { const l = await list(); json({ films: [{ name: LIB, engine: "codescape", library: true }, ...l.films.map((f) => ({ name: f.name, engine: f.engine }))], root: l.root }); }
          else if (what === "stat") { // what changed (for live reload) and what is being drawn (for the live view of a render)
            const dir = studioDir(q.film); let newest = 0; const files = [];
            for (const base of [dir, engineDir(dir)]) { if (!base) continue; for (const e of fs.readdirSync(base, { withFileTypes: true })) if (e.isFile() && /\.(js|mjs|json)$/.test(e.name)) { const m = fs.statSync(path.join(base, e.name)).mtimeMs; newest = Math.max(newest, m); if (base === dir) files.push({ name: e.name, mtime: Math.round(m) }); } }
            let audio = 0; try { audio = Math.round(fs.statSync(path.join(dir, "audio.wav")).mtimeMs); } catch { /* silent film */ }
            json({ film: q.film, engine: engineOf(dir), newest: Math.round(newest), audio, files, render: progress(), frame: lastFrame ? { job: lastFrame.job, n: lastFrame.n, w: lastFrame.w, h: lastFrame.h, age: Date.now() - lastFrame.at } : null });
          } else if (what === "text") { const f = resolveIn(studioDir(q.film), String(q.file || "film.js")); if (!f || !/\.(js|mjs|json|md|txt|py)$/i.test(f)) { res.statusCode = 404; return res.end("not found"); } res.setHeader("Content-Type", "text/plain; charset=utf-8"); res.setHeader("Cache-Control", "no-store"); fs.createReadStream(f).pipe(res); }
          else if (what === "library") { // every module the film can use: the film's own and its engine's, with what each offers
            const dir = studioDir(q.film), mods = [], seen = new Set();
            for (const [base, where] of [[dir, "film"], [engineDir(dir), "engine"], [o.web, "cine"]]) { if (!base) continue; for (const n of fs.readdirSync(base).sort()) { if (!/\.js$/.test(n) || /^three\./.test(n) || seen.has(n)) continue; seen.add(n); const items = exportsOf(path.join(base, n)); if (items.length) mods.push({ file: n, where, items }); } }
            json({ film: q.film, engine: engineOf(dir), modules: mods });
          } else if (what === "lastframe") { if (!lastFrame) { res.statusCode = 404; return res.end("none"); } res.setHeader("Content-Type", "application/octet-stream"); res.setHeader("Cache-Control", "no-store"); res.setHeader("X-Size", `${lastFrame.w}x${lastFrame.h}`); res.end(lastFrame.b); }
          else { res.statusCode = 404; res.end("not found"); }
        } else {
          const f = resolve(decodeURIComponent(u.pathname));
          if (!f) { res.statusCode = 404; return res.end("not found"); }
          res.setHeader("Content-Type", MIME[path.extname(f).toLowerCase()] || "application/octet-stream"); res.setHeader("Cache-Control", "no-store");
          fs.createReadStream(f).pipe(res);
        }
      } catch (e) { say(`ERR server ${e && e.message}`); res.statusCode = 500; res.end(String(e && e.message)); }
    });
    await new Promise((r, rej) => { server.once("error", rej); server.listen(0, "127.0.0.1", r); });
    port = server.address().port;
    return port;
  }
  /** Open a page of the engine for a film and wait until it raises its flag. → what it said. */
  async function page(dir, name, query, size, timeout) {
    await start(); ctxFilm = dir; ctxEngine = engineDir(dir);
    const url = `http://127.0.0.1:${port}/${query}`;
    let win = null, timer = null;
    try {
      const said = new Promise((resolve) => waits.set(name, { resolve }));
      win = await o.openPage(url, size || { width: 1280, height: 720 });
      const r = await Promise.race([said, win.gone.then((why) => { throw new Error(`The drawing window went away (${why}).`); }), new Promise((_, rej) => { timer = setTimeout(() => rej(new Error(`The page did not answer in ${Math.round(timeout / 1000)} s.`)), timeout); })]);
      return r;
    } finally { clearTimeout(timer); waits.delete(name); try { win && win.close(); } catch { /* closed */ } }
  }
  const lastErr = () => logs.filter((l) => /^(ERR|EXC)/.test(l)).slice(-3).join("\n");

  /** The film's shots, in order: asked of the film's own code. */
  async function shots(dir) {
    const r = await page(dir, "info", "shot.html?info=1", null, 60_000);
    let j; try { j = JSON.parse(r); } catch { throw new Error(`The film's code did not load.\n${lastErr() || r}`.slice(0, 1500)); }
    return j;
  }
  const exec = (cmd, args, opt = {}) => new Promise((res) => execFile(cmd, args, { maxBuffer: 1 << 26, ...opt }, (e, so, se) => res({ code: e ? (typeof e.code === "number" ? e.code : 1) : 0, out: String(so || ""), err: String(se || "") })));

  async function frame(film, shot, t, width, height) {
    const dir = filmDir(film), id = safeId(shot), W = Math.min(3840, Math.max(320, Math.round(width || 1280))), H = Math.round(height || W * 9 / 16);
    const tt = Math.max(0, +t || 0), tag = `st_${id}_${String(Math.round(tt * 100)).padStart(5, "0")}`;
    const r = await page(dir, "still", `shot.html?id=${id}&w=${W}&h=${H}&stills=${tt}&job=still`, null, 240_000);
    const f = path.join(dir, "out", `${tag}.jpg`);
    if (r !== "ok" || !fs.existsSync(f)) throw new Error(`The frame was not drawn.\n${lastErr()}`.slice(0, 1500));
    return { jpeg: `data:image/jpeg;base64,${(await fsp.readFile(f)).toString("base64")}`, file: f, shot: id, t: tt, width: W, height: H };
  }
  async function sheet(film, shot, times, cols, cell, cellHeight) {
    const dir = filmDir(film), id = safeId(shot), W = Math.min(1920, Math.max(240, Math.round(cell || 640))), H = Math.min(3840, Math.max(135, Math.round(cellHeight || W * 9 / 16)));
    const ts = (times || []).map((v) => Math.max(0, +v || 0)).slice(0, 24);
    if (!ts.length) throw new Error("Give the moments to look at: times: [seconds…].");
    const r = await page(dir, "still", `shot.html?id=${id}&w=${W}&h=${H}&stills=${ts.join(",")}&job=still`, null, 600_000);
    if (r !== "ok") throw new Error(`The frames were not drawn.\n${lastErr()}`.slice(0, 1500));
    const files = ts.map((t) => path.join(dir, "out", `st_${id}_${String(Math.round(t * 100)).padStart(5, "0")}.jpg`)).filter((f) => fs.existsSync(f));
    const c = Math.max(1, Math.min(6, Math.round(cols || 4))), rows = Math.ceil(files.length / c), out = path.join(dir, "out", `sheet_${id}.jpg`);
    const args = ["-y", "-loglevel", "error"]; for (const f of files) args.push("-i", f);
    args.push("-filter_complex", `${files.map((_, i) => `[${i}:v]`).join("")}xstack=inputs=${files.length}:layout=${files.map((_, i) => `${(i % c) * W}_${Math.floor(i / c) * H}`).join("|")}:fill=black`, "-frames:v", "1", "-q:v", "3", out);
    if (files.length === 1) await fsp.copyFile(files[0], out); else { const x = await exec(o.ffmpeg, args); if (x.code) throw new Error(`The sheet could not be put together: ${x.err.slice(-300)}`); }
    return { jpeg: `data:image/jpeg;base64,${(await fsp.readFile(out)).toString("base64")}`, file: out, shot: id, times: ts, cols: c, rows };
  }

  /** Draw the film (or some shots) in pieces, join them, lay the sound under. Runs on; ask `progress`. */
  async function render(film, p = {}) {
    if (run && run.state === "drawing") throw new Error(`A film is being drawn (${run.film}). Wait for it or stop it (cine.stop).`);
    const dir = filmDir(film), info = await shots(dir);
    const W = Math.min(3840, Math.max(640, Math.round(p.width || info.width || 2560))), H = Math.round(p.height || W * (info.height || 1440) / (info.width || 2560)), fps = Math.round(p.fps || info.fps || 30);
    const PIECE = Math.max(30, Math.round(p.piece || 150)), crf = p.crf ?? 15; let only = Array.isArray(p.shots) && p.shots.length ? new Set(p.shots.map(String)) : null;
    const seg = path.join(dir, "out", "seg"); await fsp.mkdir(seg, { recursive: true });
    const want = /^(gpu|nvenc)$/.test(String(p.encoder || "")) ? "gpu" : /^(x264|cpu)$/.test(String(p.encoder || "")) ? "x264" : "auto";
    const gpu = want === "x264" ? null : await gpuEncoder();
    if (want === "gpu" && !gpu) throw new Error("This PC has no working GPU encoder (NVENC). Leave encoder out, or give encoder: \"x264\".");
    const made = gpu ? "nvenc" : "x264", note = path.join(seg, "encoder.txt"); let before = null; try { before = (await fsp.readFile(note, "utf8")).trim(); } catch { /* nothing drawn yet */ }
    const size = `${W}x${H}@${fps}`, stamp = `${made} ${size}`; if (before && before !== stamp) { p = { ...p, force: true }; only = null; } // pieces of another size or encoder cannot be joined with new ones: all are drawn again
    await fsp.writeFile(note, stamp); useEnc = gpu ? "auto" : "x264";
    const plan = [];
    for (const s of info.shots) { const N = Math.round(s.dur * fps), K = Math.ceil(N / PIECE); for (let k = 0; k < K; k++) plan.push({ shot: s.id, k, f0: k * PIECE, f1: Math.min(N, (k + 1) * PIECE), job: `${s.id}_${k}`, redo: !!(only && only.has(s.id)) || (!!p.force && !only) }); }
    const todo = plan.filter((x) => x.redo || !fs.existsSync(path.join(seg, `${x.job}.mp4`)));
    const me = run = { film: path.basename(dir), dir, state: "drawing", total: plan.length, done: plan.length - todo.length, todo: todo.length, piece: null, frames: 0, started: Date.now(), size: `${W}x${H}`, fps, encoder: made, engine: info.engine || "cine" };
    (async () => {
      try {
        for (const x of todo) {
          if (me.stop) { me.state = "stopped"; return; }
          me.piece = x.job; let ok = false;
          for (let attempt = 1; attempt <= 3 && !ok && !me.stop; attempt++) {
            await fsp.rm(path.join(dir, "out", `${x.job}.mp4`), { force: true });
            let r = "failed";
            try { r = await page(dir, x.job, `shot.html?id=${x.shot}&job=${x.job}&w=${W}&h=${H}&fps=${fps}&crf=${crf}&preset=${p.preset || "fast"}&f0=${x.f0}&f1=${x.f1}`, null, 30 * 60_000); } catch (e) { say(`ERR ${x.job}: ${e.message}`); }
            ok = r === "done" && fs.existsSync(path.join(dir, "out", `${x.job}.mp4`));
            if (ok) await fsp.rename(path.join(dir, "out", `${x.job}.mp4`), path.join(seg, `${x.job}.mp4`));
          }
          if (!ok) { if (me.stop) { me.state = "stopped"; return; } throw new Error(`The piece ${x.job} could not be drawn.\n${lastErr()}`); }
          me.done++;
          if (o.send) o.send({ film: me.film, done: me.done, total: me.total });
        }
        if (me.stop) { me.state = "stopped"; return; }
        me.state = "joining";
        me.out = await join(dir, plan, info, p);
        me.state = "done"; me.seconds = Math.round((Date.now() - me.started) / 1000);
      } catch (e) { me.state = "failed"; me.error = String(e && e.message).slice(0, 1500); }
    })();
    return progress();
  }
  async function join(dir, plan, info, p = {}) {
    const seg = path.join(dir, "out", "seg"), list = path.join(seg, "list.txt");
    for (const x of plan) if (!fs.existsSync(path.join(seg, `${x.job}.mp4`))) throw new Error(`The piece ${x.job} is not drawn yet.`);
    await fsp.writeFile(list, plan.map((x) => `file '${x.job}.mp4'`).join("\n") + "\n");
    const name = safeName(p.name || info.name || path.basename(dir)) || "film", silent = path.join(dir, "out", "video_only.mp4"), out = path.join(dir, "out", `${name}.mp4`);
    let x = await exec(o.ffmpeg, ["-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list, "-c", "copy", silent], { cwd: seg });
    if (x.code) throw new Error(`The pieces could not be joined: ${x.err.slice(-400)}`);
    const wav = path.join(dir, info.audio || "audio.wav");
    if (fs.existsSync(wav)) {
      // the master of the mix: -14 LUFS, true peak -1.5 dB
      x = await exec(o.ffmpeg, ["-y", "-loglevel", "error", "-i", silent, "-i", wav, "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-af", "loudnorm=I=-14:TP=-1.5:LRA=14", "-ar", "48000", "-c:a", "aac", "-b:a", "320k", "-movflags", "+faststart", "-shortest", out]);
      if (x.code) throw new Error(`The sound could not be laid under the film: ${x.err.slice(-400)}`);
    } else await fsp.copyFile(silent, out);
    const res = { file: out, MB: Math.round((await fsp.stat(out)).size / 1e4) / 100, sound: fs.existsSync(wav) };
    if (p.maxMB) { // a second copy that fits a size (for sending in a chat): the same picture at the bit rate the size allows
      const dur = info.shots.reduce((a, s) => a + s.dur, 0), kbps = Math.floor((p.maxMB * 8192) / dur - 330), small = path.join(dir, "out", `${name}_${Math.round(p.maxMB)}MB.mp4`);
      x = await exec(o.ffmpeg, ["-y", "-loglevel", "error", "-i", out, "-c:v", "libx264", "-preset", "slow", "-b:v", `${kbps}k`, "-maxrate", `${Math.round(kbps * 1.6)}k`, "-bufsize", `${kbps * 3}k`, "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", small], { timeout: 3 * 3600_000 });
      if (!x.code) res.small = { file: small, MB: Math.round((await fsp.stat(small)).size / 1e4) / 100, kbps };
    }
    if (o.movies) { try { await fsp.mkdir(o.movies, { recursive: true }); await fsp.copyFile(out, path.join(o.movies, `${name}.mp4`)); res.copy = path.join(o.movies, `${name}.mp4`); } catch { /* the film's own folder has it */ } }
    return res;
  }
  function progress() {
    if (!run) return { state: "idle" };
    const el = (Date.now() - run.started) / 1000, drawn = run.done - (run.total - run.todo);
    return { film: run.film, state: run.state, pieces: `${run.done}/${run.total}`, piece: run.piece, frames: run.frames, size: run.size, fps: run.fps, encoder: run.encoder, engine: run.engine, seconds: Math.round(el),
      framesPerSecond: run.frames ? Math.round((run.frames / el) * 10) / 10 : 0, left: drawn > 0 && run.state === "drawing" ? `${Math.round((el / drawn) * (run.total - run.done) / 60)} min` : undefined, out: run.out, error: run.error, said: logs.slice(-3) };
  }
  function stop() { if (run && run.state === "drawing") { run.stop = true; for (const w of waits.values()) w.resolve("stopped"); return { stopping: true }; } return { stopping: false }; }

  async function list() {
    const films = [];
    try { for (const e of await fsp.readdir(ROOT, { withFileTypes: true })) if (e.isDirectory() && fs.existsSync(path.join(ROOT, e.name, "film.js"))) films.push({ name: e.name, dir: path.join(ROOT, e.name), engine: engineOf(path.join(ROOT, e.name)) }); } catch { /* no films yet */ }
    const have = (d) => { try { return fs.readdirSync(path.join(ASSETS, d)).length; } catch { return 0; } };
    return { root: ROOT, films, assets: { folder: ASSETS, bodies: Math.floor(have("data") / 2), textures: have("tex"), fonts: have("fonts") }, engine: o.web, guide: "cine.help", clay: { engine: path.join(o.web, "clay"), guide: "cine.help {topic: \"clay\"}", start: "cine.new {name, kind: \"clay\"}" } };
  }
  async function open(film) {
    const dir = filmDir(film), info = await shots(dir);
    const files = []; const walk = async (d, rel) => { for (const e of await fsp.readdir(d, { withFileTypes: true })) { if (e.name.startsWith(".") || e.name === "out" || e.name === "__pycache__") continue; const r = rel ? `${rel}/${e.name}` : e.name; if (e.isDirectory()) { if (r !== "data/mo") await walk(path.join(d, e.name), r); else files.push("data/mo/ (performances)"); } else files.push(r); } };
    await walk(dir, "");
    const seg = path.join(dir, "out", "seg"); let drawn = 0; try { drawn = (await fsp.readdir(seg)).filter((n) => /\.mp4$/.test(n)).length; } catch { /* nothing drawn */ }
    return { dir, ...info, engine: engineOf(dir), seconds: info.shots.reduce((a, s) => a + s.dur, 0), files, piecesDrawn: drawn };
  }
  const TEXT = /\.(js|mjs|json|md|txt|py|sh|html)$/i;
  function source(film, file) {
    const dir = filmDir(film), f = path.resolve(dir, String(file || ""));
    if (!f.startsWith(dir + path.sep)) throw new Error("That file is outside the film's folder.");
    if (!TEXT.test(f)) throw new Error("Film files are text: .js, .json, .py, .md, .txt, .sh, .html.");
    return f;
  }
  async function create(name, kind) {
    const n = safeName(name); if (!n) throw new Error("Give the film a name.");
    const dir = path.join(ROOT, n); if (fs.existsSync(dir)) throw new Error(`There is already a folder named ${n}.`);
    const clay = String(kind || "") === "clay", tpl = path.join(path.dirname(o.web), "films", clay ? "clay-starter" : "starter");
    await fsp.mkdir(clay ? dir : path.join(dir, "data", "mo"), { recursive: true });
    for (const f of clay ? ["film.js", "README.md"] : ["film.js", "moves.py", "README.md"]) if (fs.existsSync(path.join(tpl, f))) await fsp.copyFile(path.join(tpl, f), path.join(dir, f));
    if (clay) return { dir, engine: "clay", next: "Read cine.help {topic: \"clay\"}. Make the look sheet first (cine.frame / cine.sheet with width and height of a vertical frame), write film.js (cine.write), make the lip tracks from the voices (cine.tool lips.py), then cine.render {width: 2160, height: 3840}." };
    return { dir, next: "Read cine.help, write film.js (cine.write), make the performances (moves.py, in the workspace or with cine.tool), then cine.frame / cine.sheet / cine.render." };
  }
  /** The engine's Python tools (bodies, performances, painting, sound), with this PC's python3 if it has numpy, scipy and OpenCV. */
  async function tool(film, script, args) {
    const dir = film ? filmDir(film) : null, tools = path.join(path.dirname(o.web), "tools");
    const s = String(script || ""); const f = [dir && path.join(dir, s), path.join(tools, s)].find((x) => x && /\.py$/.test(x) && fs.existsSync(x));
    if (!f) throw new Error(`No such tool: ${s}. The engine's tools: ${fs.readdirSync(tools).filter((n) => /\.py$/.test(n)).join(", ")}; or a .py of the film's own.`);
    const env = { ...process.env, CINE_ASSETS: ASSETS, CINE_TOOLS: tools, PYTHONPATH: [tools, process.env.PYTHONPATH].filter(Boolean).join(path.delimiter), ...(dir ? { CINE_FILM: dir } : {}) };
    const r = await exec(process.env.VIDEOSTUDIO_PYTHON || "python3", [f, ...(Array.isArray(args) ? args.map(String) : [])], { cwd: dir || tools, env, timeout: 60 * 60_000 });
    if (r.code && /No module named|not found|ENOENT/.test(r.err)) throw new Error(`This PC's python3 cannot run the tool: ${r.err.trim().split("\n").slice(-1)[0]}. It needs: pip install --user numpy scipy opencv-python-headless pillow. (Or run the tool in Claude's workspace and send the data into the film's folder.)`);
    return { code: r.code, out: r.out.slice(-6000), err: r.err.slice(-3000) };
  }
  /** Many files at once: a .tar.gz that lies in the Cine folder is unpacked there (bodies, textures, a film's performances). */
  async function unpack(file) {
    const f = path.resolve(path.isAbsolute(String(file)) ? String(file) : path.join(ROOT, String(file || "")));
    if (!f.startsWith(ROOT + path.sep) || !/\.(tar\.gz|tgz)$/.test(f) || !fs.existsSync(f)) throw new Error("Give a .tar.gz that lies in the Cine folder.");
    const ls = await exec("tar", ["-tzf", f]); if (ls.code) throw new Error(`The archive does not open: ${ls.err.slice(-200)}`);
    const names = ls.out.split("\n").filter(Boolean);
    for (const n of names) if (n.startsWith("/") || n.split("/").includes("..")) throw new Error(`The archive holds a path that leaves the folder: ${n}`);
    const x = await exec("tar", ["-xzf", f, "-C", ROOT, "--no-same-owner", "--no-same-permissions"]); if (x.code) throw new Error(`Unpacking failed: ${x.err.slice(-300)}`);
    const tops = [...new Set(names.map((n) => n.split("/")[0]))];
    return { files: names.filter((n) => !n.endsWith("/")).length, into: ROOT, folders: tops };
  }
  return {
    list, open, create, frame, sheet, render, progress, stop, tool, unpack,
    help: async (topic) => fsp.readFile(String(topic || "") === "clay" ? path.join(path.dirname(o.guide), "CLAY.md") : o.guide, "utf8"),
    engineOf,
    read: async (film, file) => fsp.readFile(source(film, file), "utf8"),
    write: async (film, file, text) => { const f = source(film, file); await fsp.mkdir(path.dirname(f), { recursive: true }); await fsp.writeFile(f, String(text ?? "")); if (path.basename(f) === "film.js") { try { const i = await shots(filmDir(film)); return { file: f, loads: true, shots: i.shots.length }; } catch (e) { return { file: f, loads: false, mistake: String(e.message).slice(0, 1200) }; } } return { file: f }; },
    join: async (film, p) => { const dir = filmDir(film), info = await shots(dir), fps = info.fps || 30, PIECE = 150, plan = []; for (const s of info.shots) { const K = Math.ceil(Math.round(s.dur * fps) / PIECE); for (let k = 0; k < K; k++) plan.push({ job: `${s.id}_${k}` }); } return join(dir, plan, info, p || {}); },
    /** Any page of the engine (lab.html …) for a film: wait for its flag, then say which pictures it left in out/. */
    lab: async (film, query, flagName, timeout) => { const dir = filmDir(film), t0 = Date.now(); const r = await page(dir, flagName || 'lab', String(query), null, timeout || 300_000); const out = path.join(dir, 'out'); const files = fs.existsSync(out) ? fs.readdirSync(out).filter((n) => /\.(jpg|png)$/.test(n) && fs.statSync(path.join(out, n)).mtimeMs >= t0 - 500).map((n) => path.join(out, n)) : []; return { said: r, files, log: logs.slice(-6) }; },
    /** Cine Studio: the film playing live in a window (real time, on the graphics card), its code beside it, the library, the checks, a render watched as it is drawn. */
    studio: async (film) => { await start(); const name = film ? (String(film) === LIB ? LIB : path.basename(filmDir(film))) : ""; const url = `http://127.0.0.1:${port}/studio.html${name ? `?film=${encodeURIComponent(name)}` : ""}`; if (o.openStudio) await o.openStudio(url); return { url, film: name || null, window: !!o.openStudio }; },
    /** The deep review, measured without drawing: cameras that shake, lenses with a body on them, hands that do not reach or hold (the film notes its holds in c.rep). → the report */
    review: async (film, shot) => { const dir = filmDir(film); const r = await page(dir, "review", `review.html?job=review${shot ? `&id=${safeId(shot)}` : ""}`, null, 300_000); try { return JSON.parse(r); } catch { throw new Error(`The review did not run.\n${lastErr() || r}`.slice(0, 1500)); } },
    said: () => logs.slice(-60),
    close: () => { try { server && server.close(); } catch { /* closed */ } server = null; },
  };
}

/** In the app: pages are drawn in a hidden window of its own (the PC's graphics card), never in the editor's window. */
function setupCine(o) {
  const { BrowserWindow } = require("electron");
  const base = [path.join(o.resources || "", "film", "cine"), path.join(__dirname, "..", "film", "cine")].find((d) => d && fs.existsSync(path.join(d, "web", "engine.js")));
  if (!base) return null;
  const openPage = async (url, size) => {
    const w = new BrowserWindow({ show: false, width: size.width, height: size.height, webPreferences: { backgroundThrottling: false, contextIsolation: true, nodeIntegration: false, sandbox: true } });
    let gone; const goneP = new Promise((r) => { gone = r; });
    w.webContents.on("render-process-gone", (_e, d) => gone(d && d.reason ? d.reason : "crashed"));
    w.webContents.on("did-fail-load", (_e, code, desc) => gone(`did not load: ${desc}`));
    w.on("closed", () => gone("closed"));
    w.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
    void w.loadURL(url);
    return { close: () => { if (!w.isDestroyed()) w.destroy(); }, gone: goneP };
  };
  let studioWin = null;
  const openStudio = async (url) => {
    if (studioWin && !studioWin.isDestroyed()) { if (studioWin.webContents.getURL() !== url) void studioWin.loadURL(url); studioWin.show(); studioWin.focus(); return; }
    studioWin = new BrowserWindow({ width: 1560, height: 980, minWidth: 1000, minHeight: 640, title: "Cine Studio — VideoStudio", backgroundColor: "#121418", autoHideMenuBar: true, webPreferences: { backgroundThrottling: false, contextIsolation: true, nodeIntegration: false, sandbox: true } });
    studioWin.webContents.setWindowOpenHandler(() => ({ action: "deny" })); studioWin.on("closed", () => { studioWin = null; }); void studioWin.loadURL(url);
  };
  const cine = createCine({ root: o.root, web: path.join(base, "web"), guide: path.join(base, "GUIDE.md"), ffmpeg: o.ffmpeg, ffprobe: o.ffprobe, openPage, openStudio, movies: o.movies, mustAllow: o.mustAllow, log: o.log, gpuEncoder: o.gpuEncoder, send: (m) => o.send && o.send("cine:progress", m) });
  const h = o.handle;
  h("cine.help", (topic) => cine.help(topic));
  h("cine.list", () => cine.list());
  h("cine.open", (film) => cine.open(film));
  h("cine.new", (name, kind) => cine.create(name, kind));
  h("cine.read", (film, file) => cine.read(film, file));
  h("cine.write", (film, file, text) => cine.write(film, file, text));
  h("cine.frame", (film, shot, t, width, height) => cine.frame(film, shot, t, width, height));
  h("cine.sheet", (film, shot, times, cols, cell, cellHeight) => cine.sheet(film, shot, times, cols, cell, cellHeight));
  h("cine.render", (film, p) => cine.render(film, p || {}));
  h("cine.join", (film, p) => cine.join(film, p || {}));
  h("cine.progress", () => ({ ...cine.progress(), log: cine.said().slice(-12) }));
  h("cine.stop", () => cine.stop());
  h("cine.unpack", (file) => cine.unpack(file));
  h("cine.tool", (film, script, args) => cine.tool(film, script, args));
  h("cine.studio", (film) => cine.studio(film));
  h("cine.review", (film, shot) => cine.review(film, shot));
  return cine;
}

module.exports = { createCine, setupCine };
