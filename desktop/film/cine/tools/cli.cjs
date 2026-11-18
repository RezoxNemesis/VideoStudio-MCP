#!/usr/bin/env node
/**
 * Cine 3D without the app: the same engine and the same frame server, drawn by a headless Chrome.
 * For a cloud workspace (software drawing: slow but identical pictures) or any PC with Chrome.
 *   node cli.cjs frame  <film dir> <shot> <t> [width]
 *   node cli.cjs sheet  <film dir> <shot> <t,t,t…> [cols] [cell]
 *   node cli.cjs render <film dir> [shot,shot…] [width] [height]     (resumable: pieces already drawn are kept)
 *   node cli.cjs join   <film dir> [maxMB]
 *   node cli.cjs open   <film dir>
 * Environment: CINE_ROOT (folder that holds `assets/`; default: the film's parent), CINE_CHROME, CINE_FFMPEG, CINE_GPU=1 (use the real GPU).
 */
const path = require("node:path"), fs = require("node:fs"), os = require("node:os"), { spawn } = require("node:child_process");
const { createCine } = require("../../../electron/cine.cjs");
const [cmd, filmArg, ...rest] = process.argv.slice(2);
if (!cmd || !filmArg) { console.log(fs.readFileSync(__filename, "utf8").split("*/")[0]); process.exit(1); }
const film = path.resolve(filmArg), root = process.env.CINE_ROOT || path.dirname(film);
const chrome = process.env.CINE_CHROME || ["google-chrome", "chromium", "chromium-browser"].find((c) => (process.env.PATH || "").split(":").some((d) => fs.existsSync(path.join(d, c)))) || "google-chrome";
let n = 0;
const openPage = async (url, size) => {
  const ud = path.join(os.tmpdir(), `cine-chrome-${process.pid}-${n++}`);
  const gpu = process.env.CINE_GPU ? ["--ignore-gpu-blocklist"] : ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"];
  const p = spawn(chrome, ["--headless=new", "--no-sandbox", "--disable-dev-shm-usage", ...gpu, "--disable-background-timer-throttling", "--disable-renderer-backgrounding", "--js-flags=--max-old-space-size=3500", `--user-data-dir=${ud}`, `--window-size=${size.width},${size.height}`, url], { stdio: "ignore", detached: true });
  let gone; const goneP = new Promise((r) => { gone = r; }); p.on("exit", (c) => gone(`browser exited ${c}`));
  return { close: () => { try { process.kill(-p.pid, "SIGKILL"); } catch { /* gone */ } setTimeout(() => fs.rm(ud, { recursive: true, force: true }, () => {}), 500); }, gone: goneP };
};
const cine = createCine({ root, web: path.join(__dirname, "..", "web"), guide: path.join(__dirname, "..", "GUIDE.md"), ffmpeg: process.env.CINE_FFMPEG || "ffmpeg", openPage });
const strip = (r) => { if (r && r.jpeg) r = { ...r, jpeg: `(${Math.round(r.jpeg.length / 1366)} kB)` }; return r; };
(async () => {
  try {
    if (cmd === "frame") console.log(strip(await cine.frame(film, rest[0], +rest[1] || 0, +rest[2] || 1280)));
    else if (cmd === "sheet") console.log(strip(await cine.sheet(film, rest[0], String(rest[1] || "0").split(",").map(Number), +rest[2] || 4, +rest[3] || 640)));
    else if (cmd === "open") console.log(JSON.stringify(await cine.open(film), null, 1));
    else if (cmd === "join") console.log(await cine.join(film, { maxMB: +rest[0] || 0 }));
    else if (cmd === "render") {
      const shots = rest[0] && rest[0] !== "all" ? rest[0].split(",") : undefined;
      await cine.render(film, { shots, width: +rest[1] || undefined, height: +rest[2] || undefined, preset: process.env.CINE_PRESET || "veryfast" });
      let last = "";
      for (;;) { await new Promise((r) => setTimeout(r, 5000)); const p = cine.progress(); const line = `${p.state} ${p.pieces} ${p.piece || ""} ${p.framesPerSecond} f/s`; if (line !== last) { console.log(new Date().toTimeString().slice(0, 8), line); last = line; } if (p.state !== "drawing" && p.state !== "joining") { console.log(JSON.stringify(p, null, 1)); break; } }
    } else throw new Error(`unknown command ${cmd}`);
  } catch (e) { console.error(String(e && e.message)); for (const l of cine.said().slice(-8)) console.error("  " + l); process.exitCode = 1; }
  cine.close(); setTimeout(() => process.exit(), 300);
})();
