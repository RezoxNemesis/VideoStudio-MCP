/**
 * VideoStudio Desktop — Electron main process.
 *
 * The editor engine (compositor, animation, voices, AI, agent API) runs in the window, exactly the engine the
 * phone app uses. This process adds what only a desktop can do: real files in the person's folders, ffmpeg for
 * reading media and sound, saving renders straight into ~/Videos, and screenshots of the window for the agent.
 */
const { app, BrowserWindow, ipcMain, protocol, net, shell, dialog, nativeImage, session } = require("electron");
const path = require("node:path");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const os = require("node:os");
const { spawn } = require("node:child_process");
const { pathToFileURL } = require("node:url");
const crypto = require("node:crypto");

// VideoStudio 2 is a new app (new package, new name) that keeps the data of VideoStudio Desktop: projects, media
// links and — above all — the Claude link key, so the connector the person already added keeps working untouched.
// This must run before anything asks for the data folder.
app.setPath("userData", path.join(app.getPath("appData"), "VideoStudio Desktop"));

const WEB_ROOT = path.join(__dirname, "..", "dist-web");
const HOME = os.homedir();
const DEV_URL = process.env.VSD_DEV_URL || "";

// GPU: WebGPU (on-device AI) and GPU rasterisation for the compositor.
// On Linux, Chromium's WebGPU needs Vulkan to reach the real GPU (otherwise it falls back to SwiftShader on the
// CPU). Crash guard: a marker is written at start and cleared once the window has been up for a while; if the
// previous start never got that far, Vulkan is left off this time.
const GUARD = path.join(app.getPath("userData"), "gpu-boot.marker");
const GPU_CFG = path.join(app.getPath("userData"), "gpu.json");
const CRASH_LOG = path.join(app.getPath("userData"), "crash.log");
const crashLog = (m) => {
  try {
    fs.mkdirSync(path.dirname(CRASH_LOG), { recursive: true });
    fs.appendFileSync(CRASH_LOG, `${new Date().toISOString()} ${m}\n`);
  } catch {
    /* ignore */
  }
};
// Only the first copy of the app takes part in the GPU guard (a duplicate launch quits at once).
if (process.argv.includes("--vsd-relaunch-wait")) {
  // Started by the updater: give the old process a moment to release the profile locks.
  const until = Date.now() + 4000;
  while (Date.now() < until) {
    /* short busy wait before any profile access */
  }
}
const firstInstance = app.requestSingleInstanceLock();
// Session marker: written at start, touched while running, removed on a normal quit. If it is still there at the
// next start and the session lived under three minutes, that start counts as failed (it crashed soon after opening).
const SESSION = path.join(app.getPath("userData"), "session.marker");
let vulkan = true;
let safeMode = false;
if (firstInstance) {
  let cfg = {};
  try {
    cfg = JSON.parse(fs.readFileSync(GPU_CFG, "utf8")) || {};
    // A "Vulkan off" decision made before X11 mode existed (1.0.8 and older) gets one fresh try.
    if (cfg.vulkan === false && cfg.x11 === true) vulkan = false;
  } catch {
    /* default on */
  }
  try {
    let failed = fs.existsSync(GUARD) ? "previous start did not finish" : "";
    try {
      const st = fs.statSync(SESSION);
      const began = Number(fs.readFileSync(SESSION, "utf8")) || st.mtimeMs;
      if (!failed && st.mtimeMs - began < 180_000) failed = "previous session crashed soon after opening";
    } catch {
      /* no earlier session marker */
    }
    if (failed) {
      fs.mkdirSync(path.dirname(GPU_CFG), { recursive: true });
      if (vulkan) {
        // First step down: no Vulkan (and with it, no XWayland).
        vulkan = false;
        fs.writeFileSync(GPU_CFG, JSON.stringify({ vulkan: false, x11: true, reason: failed, at: new Date().toISOString() }));
        crashLog(`${failed}: Vulkan switched off`);
      } else if (!cfg.safeTried) {
        // Already on the plain GPU path and it still failed: open once in safe graphics (software rendering) so the
        // app is usable and its log can be read; the next start tries the GPU again.
        safeMode = true;
        fs.writeFileSync(GPU_CFG, JSON.stringify({ ...cfg, safeTried: true, safeReason: failed, safeAt: new Date().toISOString() }));
        crashLog(`${failed}: this start uses safe graphics (software rendering)`);
      } else {
        fs.writeFileSync(GPU_CFG, JSON.stringify({ ...cfg, safeTried: false }));
        crashLog(`${failed}: trying the GPU again`);
      }
    } else if (cfg.safeTried) {
      fs.writeFileSync(GPU_CFG, JSON.stringify({ ...cfg, safeTried: false }));
    }
    fs.mkdirSync(path.dirname(GUARD), { recursive: true });
    fs.writeFileSync(GUARD, String(Date.now()));
    fs.writeFileSync(SESSION, String(Date.now()));
    const beat = setInterval(() => {
      try {
        const t = new Date();
        fs.utimesSync(SESSION, t, t);
      } catch {
        /* ignore */
      }
    }, 30_000);
    beat.unref?.();
  } catch {
    /* ignore */
  }
}
if (process.env.VSD_SAFE_GRAPHICS === "1") safeMode = true;
// Windowing: the desktop's own (native Wayland on KDE) unless Vulkan is on, which needs XWayland.
// 1.0.14 forced XWayland for every NVIDIA PC and the app closed right after opening there, so that is opt-in only:
// VSD_OZONE=x11 forces XWayland, VSD_OZONE=wayland forces native Wayland.
const useVulkan = !safeMode && vulkan && process.env.VSD_NO_VULKAN !== "1";
const useX11 = process.platform === "linux" && !!process.env.DISPLAY && process.env.VSD_OZONE !== "wayland" && (useVulkan || process.env.VSD_OZONE === "x11");
if (useX11) app.commandLine.appendSwitch("ozone-platform", "x11");
if (useVulkan) app.commandLine.appendSwitch("enable-features", "Vulkan,VaapiVideoDecodeLinuxGL");
if (safeMode) app.disableHardwareAcceleration();
if (firstInstance) crashLog(`start ${app.getVersion()}: vulkan=${useVulkan} windowing=${useX11 ? "x11" : process.env.XDG_SESSION_TYPE || "auto"} safe=${safeMode}`);
// A GPU process restart must never leave the editor without a picture: keep WebGL allowed for this window
// after GPU crashes, and let Chromium fall back to its software renderer if the GPU cannot come back.
try {
  app.disableDomainBlockingFor3DAPIs();
} catch {
  /* older Electron */
}
app.commandLine.appendSwitch("enable-unsafe-swiftshader");
app.commandLine.appendSwitch("enable-unsafe-webgpu");
app.commandLine.appendSwitch("ignore-gpu-blocklist");
app.commandLine.appendSwitch("enable-gpu-rasterization");
app.commandLine.appendSwitch("enable-zero-copy");
if (process.env.VSD_NO_SANDBOX) app.commandLine.appendSwitch("no-sandbox");
app.setName("VideoStudio");

protocol.registerSchemesAsPrivileged([
  { scheme: "app", privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true, codeCache: true } },
  { scheme: "vsfile", privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true, bypassCSP: true } },
  { scheme: "vsmedia", privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true, bypassCSP: true } },
]);

/* ------------------------------------------------------------------ binaries */

function bin(name) {
  // 1) The full GPU build shipped with the installer (resources/ffmpeg: BtbN GPL build with NVENC/CUDA),
  // 2) the small bundled build (ffmpeg-static), 3) the system one.
  const vendor = [path.join(process.resourcesPath || "", "ffmpeg", name), path.join(__dirname, "..", "vendor", "ffmpeg", name)];
  for (const v of vendor) if (v && fs.existsSync(v)) return v;
  try {
    let p = name === "ffmpeg" ? require("ffmpeg-static") : require("ffprobe-static").path;
    if (p) {
      p = p.replace("app.asar", "app.asar.unpacked");
      if (fs.existsSync(p)) return p;
    }
  } catch {
    /* not bundled */
  }
  return name;
}
const FFMPEG = bin("ffmpeg");
const FFPROBE = bin("ffprobe");

function run(cmd, args, { maxOut = 64 * 1024 * 1024, binary = false, timeout = 30 * 60_000 } = {}) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"] });
    const out = [];
    let outLen = 0;
    let err = "";
    const t = setTimeout(() => p.kill("SIGKILL"), timeout);
    p.stdout.on("data", (d) => {
      outLen += d.length;
      if (outLen <= maxOut) out.push(d);
    });
    p.stderr.on("data", (d) => {
      err += d.toString();
      if (err.length > 400_000) err = err.slice(-200_000);
    });
    p.on("error", (e) => {
      clearTimeout(t);
      reject(new Error(`${path.basename(cmd)} could not start: ${e.message}`));
    });
    p.on("close", (code) => {
      clearTimeout(t);
      const buf = Buffer.concat(out);
      resolve({ code, stdout: binary ? buf : buf.toString(), stderr: err });
    });
  });
}

/* ------------------------------------------------------------------ paths */

const MEDIA_EXT = {
  video: ["mp4", "mov", "mkv", "webm", "avi", "m4v", "mts", "m2ts", "3gp", "mpg", "mpeg", "wmv", "flv"],
  audio: ["mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma", "aiff", "aif"],
  image: ["png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff", "heic", "avif", "svg"],
  model: ["glb", "gltf", "obj", "fbx"],
  project: ["vsproj", "json"],
};
function kindOf(name) {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  for (const [k, list] of Object.entries(MEDIA_EXT)) if (list.includes(ext)) return k;
  return "other";
}

function xdg(name, fallback) {
  // Respect the person's XDG folder names (localised or moved folders).
  try {
    const cfg = fs.readFileSync(path.join(HOME, ".config", "user-dirs.dirs"), "utf8");
    const m = new RegExp(`^XDG_${name}_DIR="(.+)"`, "m").exec(cfg);
    if (m) return m[1].replace("$HOME", HOME);
  } catch {
    /* default */
  }
  return path.join(HOME, fallback);
}
const DIRS = {
  home: HOME,
  desktop: xdg("DESKTOP", "Desktop"),
  documents: xdg("DOCUMENTS", "Documents"),
  downloads: xdg("DOWNLOAD", "Downloads"),
  music: xdg("MUSIC", "Music"),
  pictures: xdg("PICTURES", "Pictures"),
  videos: xdg("VIDEOS", "Videos"),
};
const OUT = {
  Movies: path.join(DIRS.videos, "VideoStudio"),
  Music: path.join(DIRS.music, "VideoStudio"),
  Pictures: path.join(DIRS.pictures, "VideoStudio"),
  Documents: path.join(DIRS.documents, "VideoStudio"),
};

/** Only the person's own files: their home, mounted drives, and the app's own data. */
function allowed(p) {
  const r = path.resolve(p);
  const roots = [HOME, "/media", "/mnt", "/run/media", app.getPath("userData"), os.tmpdir()];
  return roots.some((root) => r === root || r.startsWith(root + path.sep));
}
function mustAllow(p) {
  if (typeof p !== "string" || !p) throw new Error("A path is required.");
  const r = path.resolve(p.replace(/^~(?=$|\/)/, HOME));
  if (!allowed(r)) throw new Error(`Outside your folders: ${r}`);
  return r;
}

function mountedDrives() {
  const out = [];
  for (const base of [path.join("/media", os.userInfo().username), path.join("/run/media", os.userInfo().username), "/mnt"]) {
    try {
      for (const d of fs.readdirSync(base, { withFileTypes: true })) if (d.isDirectory()) out.push({ name: d.name, path: path.join(base, d.name) });
    } catch {
      /* none */
    }
  }
  return out;
}

/* ------------------------------------------------------------------ protocols */

const MIME = {
  html: "text/html; charset=utf-8", js: "text/javascript", mjs: "text/javascript", css: "text/css", json: "application/json",
  wasm: "application/wasm", svg: "image/svg+xml", png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp",
  gif: "image/gif", woff: "font/woff", woff2: "font/woff2", ttf: "font/ttf", otf: "font/otf", webmanifest: "application/manifest+json",
  mp4: "video/mp4", mov: "video/quicktime", mkv: "video/x-matroska", webm: "video/webm", m4v: "video/mp4", avi: "video/x-msvideo",
  mp3: "audio/mpeg", wav: "audio/wav", flac: "audio/flac", m4a: "audio/mp4", aac: "audio/aac", ogg: "audio/ogg", opus: "audio/ogg",
  glb: "model/gltf-binary", gltf: "model/gltf+json", txt: "text/plain",
};
const mimeOf = (p) => MIME[p.split(".").pop()?.toLowerCase() ?? ""] || "application/octet-stream";
const ISOLATION = { "Cross-Origin-Opener-Policy": "same-origin", "Cross-Origin-Embedder-Policy": "credentialless", "Cross-Origin-Resource-Policy": "cross-origin" };

function fileResponse(file, req, extra = {}) {
  const st = fs.statSync(file);
  const type = mimeOf(file);
  const range = req.headers.get("range");
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    let start = m && m[1] ? Number(m[1]) : 0;
    let end = m && m[2] ? Number(m[2]) : st.size - 1;
    if (!m || !m[1]) start = Math.max(0, st.size - (Number(m?.[2]) || 0));
    end = Math.min(end, st.size - 1);
    return new Response(fs.createReadStream(file, { start, end }), {
      status: 206,
      headers: { "content-type": type, "content-length": String(end - start + 1), "content-range": `bytes ${start}-${end}/${st.size}`, "accept-ranges": "bytes", ...extra },
    });
  }
  return new Response(fs.createReadStream(file), { headers: { "content-type": type, "content-length": String(st.size), "accept-ranges": "bytes", ...extra } });
}

function setupProtocols() {
  protocol.handle("app", async (req) => {
    const u = new URL(req.url);
    let rel = decodeURIComponent(u.pathname).replace(/^\/+/, "");
    if (!rel) rel = "index.html";
    // Stage asset packs (bodies, motion library) live beside the app, not inside the web bundle.
    if (rel.startsWith("stage-assets/")) {
      const sub = rel.slice("stage-assets/".length);
      for (const root of [path.join(process.resourcesPath || "", "stage-assets"), path.join(__dirname, "..", "stage-assets")]) {
        const f = path.join(root, sub);
        if (f.startsWith(root) && fs.existsSync(f) && fs.statSync(f).isFile()) return fileResponse(f, req, { ...ISOLATION, "cache-control": "max-age=86400" });
      }
      return new Response("missing stage asset", { status: 404 });
    }
    let file = path.join(WEB_ROOT, rel);
    if (!file.startsWith(WEB_ROOT)) return new Response("no", { status: 403 });
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(WEB_ROOT, "index.html");
    return fileResponse(file, req, { ...ISOLATION, "cache-control": "no-cache" });
  });
  // vsfile://f/<url-encoded absolute path>  — streams the person's own files (with seeking).
  protocol.handle("vsfile", async (req) => {
    try {
      const u = new URL(req.url);
      const file = mustAllow(decodeURIComponent(u.pathname.replace(/^\/+/, "/")));
      if (!fs.existsSync(file)) return new Response("not found", { status: 404 });
      return fileResponse(file, req, { "access-control-allow-origin": "*", "Cross-Origin-Resource-Policy": "cross-origin" });
    } catch (e) {
      return new Response(String(e.message || e), { status: 403 });
    }
  });
}
const fileUrl = (p) => `vsfile://f${p.split("/").map(encodeURIComponent).join("/")}`;

/* ------------------------------------------------------------------ native media core (VideoStudio 2) */
let mediaCore = null;
function media() {
  if (!mediaCore) {
    mediaCore = require("./media-core.cjs")({
      FFMPEG,
      probe: (f) => probe(f),
      mustAllow: (f) => mustAllow(f),
      cacheDir: path.join(app.getPath("userData"), "media-cache"),
      log: (m) => crashLog(m),
      onProxy: (m) => win && !win.isDestroyed() && win.webContents.send("media:proxy", m),
      encoderCaps: () => encoderCaps(),
    });
  }
  return mediaCore;
}

/* ------------------------------------------------------------------ media reading */

async function probe(file) {
  const r = await run(FFPROBE, ["-v", "error", "-print_format", "json", "-show_format", "-show_streams", file], { timeout: 60_000 });
  if (r.code !== 0) throw new Error(`Could not read ${path.basename(file)}: ${r.stderr.trim().split("\n").pop()}`);
  const j = JSON.parse(r.stdout);
  const v = j.streams.find((s) => s.codec_type === "video" && !(s.disposition && s.disposition.attached_pic));
  const a = j.streams.find((s) => s.codec_type === "audio");
  const fr = v?.avg_frame_rate || v?.r_frame_rate || "0/1";
  const [n, d] = fr.split("/").map(Number);
  const tags = j.format.tags || {};
  return {
    path: file,
    name: path.basename(file),
    size: Number(j.format.size || 0),
    duration: Number(j.format.duration || 0),
    format: j.format.format_name,
    bitrate: Number(j.format.bit_rate || 0),
    video: v ? { codec: v.codec_name, width: v.width, height: v.height, fps: d ? Math.round((n / d) * 1000) / 1000 : 0, pixfmt: v.pix_fmt, colorspace: v.color_space || "", rotation: Number(v.tags?.rotate || v.side_data_list?.find((s) => s.rotation !== undefined)?.rotation || 0), hdr: /bt2020|smpte2084|arib/.test(`${v.color_primaries} ${v.color_transfer}`) } : null,
    audio: a ? { codec: a.codec_name, sampleRate: Number(a.sample_rate), channels: a.channels, layout: a.channel_layout } : null,
    tags: { title: tags.title || tags.TITLE, artist: tags.artist || tags.ARTIST, album: tags.album || tags.ALBUM, date: tags.date || tags.DATE, genre: tags.genre || tags.GENRE },
  };
}

/** Mono PCM (float32) at `rate` Hz for analysis. */
async function pcm(file, rate = 11025, from = 0, seconds = 0) {
  const args = ["-v", "error"];
  if (from) args.push("-ss", String(from));
  args.push("-i", file);
  if (seconds) args.push("-t", String(seconds));
  args.push("-vn", "-ac", "1", "-ar", String(rate), "-f", "f32le", "-");
  const r = await run(FFMPEG, args, { binary: true, maxOut: 800 * 1024 * 1024, timeout: 10 * 60_000 });
  if (r.code !== 0) throw new Error(`Could not decode audio: ${r.stderr.trim().split("\n").pop()}`);
  const b = r.stdout;
  return new Float32Array(b.buffer, b.byteOffset, Math.floor(b.length / 4));
}

const db = (x) => (x > 1e-9 ? Math.round(20 * Math.log10(x) * 10) / 10 : -120);

/** Tempo + beat grid from an onset envelope (spectral-flux-like, from band energies). */
function tempoAndBeats(x, rate) {
  const hop = Math.round(rate / 100); // 10 ms
  const win = hop * 4;
  const frames = Math.floor((x.length - win) / hop);
  if (frames < 300) return { bpm: 0, confidence: 0, beats: [], onsets: [] };
  // Three crude bands by first-order filters: low (kick), mid, high (hats). Flux = positive energy change.
  const env = new Float32Array(frames);
  let lp = 0, hp = 0, prevX = 0;
  const bands = [new Float32Array(frames), new Float32Array(frames), new Float32Array(frames)];
  const aL = Math.exp((-2 * Math.PI * 150) / rate), aH = Math.exp((-2 * Math.PI * 3000) / rate);
  let i0 = 0;
  for (let f = 0; f < frames; f++) {
    let eL = 0, eM = 0, eH = 0;
    const end = f * hop + win;
    for (let i = Math.max(i0, f * hop); i < end; i++) {
      const s = x[i];
      lp = aL * lp + (1 - aL) * s;
      hp = aH * (hp + s - prevX);
      prevX = s;
      eL += lp * lp;
      eH += hp * hp;
      eM += (s - lp - hp) * (s - lp - hp);
    }
    i0 = end;
    bands[0][f] = Math.log1p(eL * 1000);
    bands[1][f] = Math.log1p(eM * 1000);
    bands[2][f] = Math.log1p(eH * 1000);
  }
  for (let f = 1; f < frames; f++) env[f] = Math.max(0, bands[0][f] - bands[0][f - 1]) * 1.4 + Math.max(0, bands[1][f] - bands[1][f - 1]) + Math.max(0, bands[2][f] - bands[2][f - 1]) * 0.6;
  // Normalise with a moving mean.
  const norm = new Float32Array(frames);
  let acc = 0;
  const W = 50;
  for (let f = 0; f < frames; f++) {
    acc += env[f];
    if (f >= W) acc -= env[f - W];
    norm[f] = Math.max(0, env[f] - (acc / Math.min(f + 1, W)) * 1.1);
  }
  // Autocorrelation over 60..190 BPM (lags 32..100 frames of 10 ms), weighted towards ~110 BPM.
  let best = 0, bestLag = 0;
  const scores = [];
  for (let lag = 31; lag <= 100; lag++) {
    let s = 0;
    for (let f = lag; f < frames; f++) s += norm[f] * norm[f - lag];
    const bpm = 6000 / lag;
    const w = Math.exp(-0.5 * Math.pow(Math.log2(bpm / 112) / 0.9, 2));
    scores.push(s);
    if (s * w > best) {
      best = s * w;
      bestLag = lag;
    }
  }
  const mean = scores.reduce((a, b) => a + b, 0) / scores.length;
  const confidence = mean > 0 ? Math.min(1, (best / mean - 1) / 2) : 0;
  // Phase: pick the offset whose comb lines up with the most onset energy, then walk with small corrections.
  let bestPh = 0, bestPhS = -1;
  for (let ph = 0; ph < bestLag; ph++) {
    let s = 0;
    for (let f = ph; f < frames; f += bestLag) s += norm[f];
    if (s > bestPhS) {
      bestPhS = s;
      bestPh = ph;
    }
  }
  const beats = [];
  for (let f = bestPh; f < frames; ) {
    let bf = f, bv = norm[f] || 0;
    for (let d = -3; d <= 3; d++) if ((norm[f + d] || 0) > bv) {
      bv = norm[f + d];
      bf = f + d;
    }
    beats.push(Math.round(bf + 3) / 100); // +30 ms: the analysis window reads onsets slightly early
    f = bf + bestLag;
  }
  // Strong onsets (hits) for cutting.
  const onsets = [];
  const thr = (() => {
    const s = Array.from(norm).sort((a, b) => a - b);
    return s[Math.floor(s.length * 0.97)] || 0;
  })();
  for (let f = 2; f < frames - 2; f++) if (norm[f] > thr && norm[f] >= norm[f - 1] && norm[f] >= norm[f + 1] && norm[f] >= norm[f - 2] && norm[f] >= norm[f + 2]) {
    if (!onsets.length || (f + 3) / 100 - onsets[onsets.length - 1] > 0.12) onsets.push((f + 3) / 100);
  }
  return { bpm: Math.round((6000 / bestLag) * 10) / 10, confidence: Math.round(confidence * 100) / 100, beats, onsets: onsets.slice(0, 400) };
}

/** Energy sections: smoothed loudness, split where it changes for good. */
function sections(rmsDb, step) {
  const n = rmsDb.length;
  if (!n) return [];
  const win = Math.max(1, Math.round(4 / step));
  const sm = rmsDb.map((_, i) => {
    let s = 0, c = 0;
    for (let j = Math.max(0, i - win); j <= Math.min(n - 1, i + win); j++) if (rmsDb[j] > -90) {
      s += rmsDb[j];
      c++;
    }
    return c ? s / c : -90;
  });
  const sorted = [...sm].filter((v) => v > -90).sort((a, b) => a - b);
  let lo = sorted[Math.floor(sorted.length * 0.33)] ?? -40, hi = sorted[Math.floor(sorted.length * 0.7)] ?? -20;
  // A steady track (little dynamic range) is one section, not noise-level wobbles.
  const spread = (sorted[Math.floor(sorted.length * 0.9)] ?? 0) - (sorted[Math.floor(sorted.length * 0.1)] ?? 0);
  if (spread < 4) {
    lo = -Infinity;
    hi = Infinity;
  } else {
    lo = Math.min(lo, hi - 2);
  }
  const level = (v) => (v < -60 ? "silent" : v < lo ? "quiet" : v < hi ? "medium" : "loud");
  const out = [];
  let cur = { start: 0, level: level(sm[0]) };
  const minLen = 3;
  for (let i = 1; i < n; i++) {
    const l = level(sm[i]);
    if (l !== cur.level && i * step - cur.start >= minLen) {
      out.push({ start: Math.round(cur.start * 100) / 100, end: Math.round(i * step * 100) / 100, energy: cur.level });
      cur = { start: i * step, level: l };
    }
  }
  out.push({ start: Math.round(cur.start * 100) / 100, end: Math.round(n * step * 100) / 100, energy: cur.level });
  // Merge neighbours with the same level.
  return out.reduce((a, s) => {
    const last = a[a.length - 1];
    if (last && last.energy === s.energy) last.end = s.end;
    else a.push(s);
    return a;
  }, []);
}

/** Stereo PCM (interleaved float32) for analysis. */
async function pcmStereo(file, rate = 11025, from = 0, seconds = 0) {
  const args = ["-v", "error"];
  if (from) args.push("-ss", String(from));
  args.push("-i", file);
  if (seconds) args.push("-t", String(seconds));
  args.push("-vn", "-ac", "2", "-ar", String(rate), "-f", "f32le", "-");
  const r = await run(FFMPEG, args, { binary: true, maxOut: 1200 * 1024 * 1024, timeout: 10 * 60_000 });
  if (r.code !== 0) throw new Error(`Could not decode audio: ${r.stderr.trim().split("\n").pop()}`);
  const b = r.stdout;
  return new Float32Array(b.buffer, b.byteOffset, Math.floor(b.length / 4));
}

/**
 * Where a voice is singing or speaking. Lead vocals sit in the centre of a stereo mix: in the voice band
 * (≈250-3500 Hz) the centre (mid) carries much more than the sides when someone sings, while instruments are
 * spread wide. The mid/side ratio, normalised against the song's own quiet passages, gives a 0..1 curve.
 * Mono files (no stereo image) use voice-band energy against the full band instead.
 */
function vocalPresence(x, rate, step = 0.25) {
  const n = Math.floor(x.length / 2);
  // Two biquads (band-pass 250..3500) for mid and side.
  const bp = (f0, q) => {
    const w = (2 * Math.PI * f0) / rate, a = Math.sin(w) / (2 * q), c = Math.cos(w);
    const b0 = a, b2 = -a, a0 = 1 + a, a1 = -2 * c, a2 = 1 - a;
    return { b0: b0 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 };
  };
  const k = bp(950, 0.55);
  const st = () => ({ x1: 0, x2: 0, y1: 0, y2: 0 });
  const run1 = (s, v) => {
    const y = k.b0 * v + k.b2 * s.x2 - k.a1 * s.y1 - k.a2 * s.y2;
    s.x2 = s.x1; s.x1 = v; s.y2 = s.y1; s.y1 = y;
    return y;
  };
  const sm = st(), ss = st();
  const hop = Math.max(1, Math.round(rate * step));
  const ratio = [], band = [], full = [];
  let em = 0, es = 0, ef = 0, c = 0, mono = true;
  for (let i = 0; i < n; i++) {
    const l = x[2 * i], r = x[2 * i + 1];
    const m = (l + r) * 0.5, d = (l - r) * 0.5;
    if (mono && Math.abs(d) > 1e-4) mono = false;
    const ym = run1(sm, m), yd = run1(ss, d);
    em += ym * ym; es += yd * yd; ef += m * m; c++;
    if (c === hop) {
      ratio.push(10 * Math.log10((em + 1e-9) / (es + 1e-9)));
      band.push(em / c); full.push(ef / c);
      em = es = ef = c = 0;
    }
  }
  const N = ratio.length;
  if (!N) return { curve: [], segments: [], mono };
  const vals = mono ? band.map((b, i) => 10 * Math.log10((b + 1e-12) / (full[i] + 1e-12))) : ratio;
  // Normalise: 10th percentile → 0, 90th → 1, then smooth over ~0.75 s and gate quiet frames.
  const sorted = [...vals].sort((a, b) => a - b);
  const lo = sorted[Math.floor(N * 0.1)], hi = sorted[Math.floor(N * 0.9)];
  const loud = full.map((f) => 10 * Math.log10(f + 1e-12));
  const ls = [...loud].sort((a, b) => a - b);
  const gate = ls[Math.floor(N * 0.15)] + 3;
  const raw = vals.map((v, i) => (loud[i] < gate ? 0 : Math.max(0, Math.min(1, (v - lo) / Math.max(1e-6, hi - lo)))));
  const W = Math.max(1, Math.round(0.75 / step));
  const curve = raw.map((_, i) => {
    let s = 0, n2 = 0;
    for (let j = Math.max(0, i - W); j <= Math.min(N - 1, i + W); j++) (s += raw[j]), n2++;
    return Math.round((s / n2) * 100) / 100;
  });
  // Segments where the curve stays above 0.5 for at least 1 s (gaps under 0.6 s are bridged).
  const segs = [];
  let on = -1;
  for (let i = 0; i <= N; i++) {
    const v = i < N ? curve[i] : 0;
    if (v >= 0.5 && on < 0) on = i;
    if (v < 0.5 && on >= 0) {
      const a = on * step, b = i * step;
      const last = segs[segs.length - 1];
      if (last && a - last.end < 0.6) last.end = Math.round(b * 100) / 100;
      else segs.push({ start: Math.round(a * 100) / 100, end: Math.round(b * 100) / 100 });
      on = -1;
    }
  }
  return { curve, segments: segs.filter((s) => s.end - s.start >= 1), mono };
}

async function picture(args) {
  const r = await run(FFMPEG, args, { binary: true, maxOut: 20 * 1024 * 1024, timeout: 5 * 60_000 });
  if (r.code !== 0 || !r.stdout.length) return null;
  return `data:image/jpeg;base64,${r.stdout.toString("base64")}`;
}

async function audioInspect(file, o = {}) {
  const info = await probe(file);
  if (!info.audio) throw new Error(`${info.name} has no sound.`);
  const from = Math.max(0, Number(o.from) || 0);
  const len = Number(o.seconds) || 0;
  const span = len || Math.max(0, info.duration - from);
  const tsel = [...(from ? ["-ss", String(from)] : []), "-i", file, ...(len ? ["-t", String(len)] : [])];

  // Loudness (EBU R128), true peak, silences — one ffmpeg pass.
  const lr = await run(FFMPEG, ["-hide_banner", "-nostats", ...tsel, "-vn", "-af", "ebur128=peak=true:framelog=quiet,silencedetect=noise=-42dB:d=0.35", "-f", "null", "-"], { timeout: 10 * 60_000 });
  const L = lr.stderr;
  const num = (re) => {
    const m = re.exec(L);
    return m ? Number(m[1]) : null;
  };
  const silences = [];
  const re = /silence_start: ([\d.]+)[\s\S]*?silence_end: ([\d.]+)/g;
  let m;
  while ((m = re.exec(L))) silences.push({ start: Math.round((Number(m[1]) + from) * 100) / 100, end: Math.round((Number(m[2]) + from) * 100) / 100 });

  // Envelope, peaks, tempo/beats from mono PCM.
  const rate = 11025;
  const x = await pcm(file, rate, from, len);
  const step = Math.max(0.1, Number(o.step) || (span > 600 ? 1 : span > 120 ? 0.5 : 0.25));
  const hopN = Math.round(rate * step);
  const rmsDb = [], peakDb = [];
  let maxAbs = 0;
  for (let i = 0; i < x.length; i += hopN) {
    let s = 0, pk = 0;
    const end = Math.min(x.length, i + hopN);
    for (let j = i; j < end; j++) {
      const v = x[j];
      s += v * v;
      const a = v < 0 ? -v : v;
      if (a > pk) pk = a;
    }
    if (pk > maxAbs) maxAbs = pk;
    rmsDb.push(db(Math.sqrt(s / Math.max(1, end - i))));
    peakDb.push(db(pk));
  }
  const rhythm = o.rhythm === false ? null : tempoAndBeats(x, rate);
  let vocals = null;
  if (o.vocals !== false) {
    try {
      const st = await pcmStereo(file, rate, from, len);
      const v = vocalPresence(st, rate, 0.25);
      vocals = { segments: v.segments.map((g) => ({ start: Math.round((g.start + from) * 100) / 100, end: Math.round((g.end + from) * 100) / 100 })), mono: v.mono, step: 0.25, curve: v.curve };
    } catch {
      vocals = null;
    }
  }
  const secs = sections(rmsDb, step).map((s) => ({ ...s, start: Math.round((s.start + from) * 100) / 100, end: Math.round((s.end + from) * 100) / 100 }));

  // Pictures the agent can look at: waveform and spectrogram (with time and frequency axes).
  const pics = {};
  if (o.pictures !== false) {
    const W = Math.min(1800, Math.max(800, Math.round(span * 20)));
    pics.waveform = await picture(["-v", "error", ...tsel, "-filter_complex", `aformat=channel_layouts=mono,showwavespic=s=${W}x220:colors=0x3ee6c4:scale=sqrt,format=yuvj420p`, "-frames:v", "1", "-f", "image2", "-c:v", "mjpeg", "-q:v", "5", "-"]);
    pics.spectrogram = await picture(["-v", "error", ...tsel, "-filter_complex", `aformat=channel_layouts=mono,showspectrumpic=s=${W}x320:legend=1:scale=log:fscale=log:color=magma:gain=1.4,format=yuvj420p`, "-frames:v", "1", "-f", "image2", "-c:v", "mjpeg", "-q:v", "6", "-"]);
  }
  const shift = (t) => Math.round((t + from) * 100) / 100;
  return {
    file: info.name,
    path: file,
    tags: info.tags,
    codec: info.audio.codec,
    sampleRate: info.audio.sampleRate,
    channels: info.audio.channels,
    duration: info.duration,
    range: { from, seconds: Math.round(span * 100) / 100 },
    loudness: { integratedLUFS: num(/I:\s+(-?[\d.]+) LUFS\s*\n\s*Threshold/), rangeLU: num(/LRA:\s+(-?[\d.]+) LU/), truePeakDBFS: num(/Peak:\s+(-?[\d.]+) dBFS/), samplePeakDB: db(maxAbs) },
    tempo: rhythm ? { bpm: rhythm.bpm, confidence: rhythm.confidence } : undefined,
    beats: rhythm ? rhythm.beats.map(shift) : undefined,
    hits: rhythm ? rhythm.onsets.map(shift) : undefined,
    sections: secs,
    vocals: vocals ?? undefined,
    silences,
    envelope: { step, startsAt: from, rmsDb, peakDb },
    waveform: pics.waveform ?? undefined,
    spectrogram: pics.spectrogram ?? undefined,
  };
}

async function videoFrames(file, times, max = 480) {
  const out = [];
  for (const t of times.slice(0, 24)) {
    out.push({ t, image: await picture(["-v", "error", "-ss", String(Math.max(0, t)), "-i", file, "-frames:v", "1", "-vf", `scale='min(${max},iw)':-2`, "-f", "image2", "-c:v", "mjpeg", "-q:v", "4", "-"]) });
  }
  return out;
}

/* ------------------------------------------------------------------ files */

async function list(dir, o = {}) {
  const d = mustAllow(dir);
  const ents = await fsp.readdir(d, { withFileTypes: true });
  const out = [];
  for (const e of ents) {
    if (!o.hidden && e.name.startsWith(".")) continue;
    const p = path.join(d, e.name);
    let st;
    try {
      st = await fsp.stat(p);
    } catch {
      continue;
    }
    const isDir = st.isDirectory();
    const kind = isDir ? "folder" : kindOf(e.name);
    if (o.kinds && !isDir && !o.kinds.includes(kind)) continue;
    out.push({ name: e.name, path: p, kind, size: isDir ? 0 : st.size, modified: Math.round(st.mtimeMs) });
  }
  out.sort((a, b) => (a.kind === "folder") === (b.kind === "folder") ? (o.sort === "modified" ? b.modified - a.modified : a.name.localeCompare(b.name, undefined, { numeric: true })) : a.kind === "folder" ? -1 : 1);
  return { dir: d, parent: path.dirname(d) !== d && allowed(path.dirname(d)) ? path.dirname(d) : null, entries: out.slice(0, o.limit || 2000), total: out.length };
}

async function find(root, query, o = {}) {
  const base = mustAllow(root || HOME);
  const q = String(query || "").toLowerCase().split(/\s+/).filter(Boolean);
  const kinds = o.kinds || null;
  const limit = Math.min(500, o.limit || 100);
  const maxDepth = Math.min(8, o.depth || 6);
  const out = [];
  const skip = new Set(["node_modules", ".git", ".cache", ".local", ".steam", ".var", "snap", ".wine", "proc"]);
  async function walk(d, depth) {
    if (out.length >= limit || depth > maxDepth) return;
    let ents;
    try {
      ents = await fsp.readdir(d, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of ents) {
      if (out.length >= limit) return;
      if (e.name.startsWith(".") || skip.has(e.name)) continue;
      const p = path.join(d, e.name);
      if (e.isDirectory()) await walk(p, depth + 1);
      else {
        const kind = kindOf(e.name);
        if (kinds && !kinds.includes(kind)) continue;
        if (kind === "other" && !kinds) continue;
        const n = e.name.toLowerCase();
        if (q.every((w) => n.includes(w) || p.toLowerCase().includes(w))) {
          let st = null;
          try {
            st = await fsp.stat(p);
          } catch {
            /* ignore */
          }
          out.push({ name: e.name, path: p, kind, size: st?.size ?? 0, modified: Math.round(st?.mtimeMs ?? 0) });
        }
      }
    }
  }
  await walk(base, 0);
  out.sort((a, b) => b.modified - a.modified);
  return { root: base, query, results: out };
}

const saves = new Map();
async function uniquePath(dir, name) {
  await fsp.mkdir(dir, { recursive: true });
  const safe = name.replace(/[\/\\:*?"<>|\0]/g, "_").slice(0, 180) || "output";
  const ext = path.extname(safe);
  const stem = safe.slice(0, safe.length - ext.length);
  let p = path.join(dir, safe);
  for (let i = 2; fs.existsSync(p); i++) p = path.join(dir, `${stem} (${i})${ext}`);
  return p;
}

/**
 * Chromium's encoder writes Opus audio into MP4. Instagram, many phones and editors want AAC, so finished MP4s get
 * their audio re-encoded to AAC 320k with the video stream copied untouched (fast, no quality loss on the picture).
 */
async function toAacIfNeeded(file) {
  if (!/\.(mp4|m4v|mov)$/i.test(file)) return null;
  let info;
  try {
    info = await probe(file);
  } catch {
    return null;
  }
  if (!info.audio || info.audio.codec === "aac") return info.audio?.codec ?? null;
  const tmp = `${file}.aac.mp4`;
  const r = await run(FFMPEG, ["-v", "error", "-y", "-i", file, "-map", "0", "-c:v", "copy", "-c:a", "aac", "-b:a", "320k", "-ar", "48000", "-movflags", "+faststart", tmp], { timeout: 30 * 60_000 });
  if (r.code !== 0) {
    await fsp.rm(tmp, { force: true });
    return info.audio.codec;
  }
  await fsp.rename(tmp, file);
  return "aac";
}

/* ------------------------------------------------------------------ GPU / pro encoding */

let caps = null;
let ENC_BIN = null; // set when GPU encoding must use a different ffmpeg than the bundled one
/** What this PC's ffmpeg can do: encoders, filters, and whether NVENC really works on the GPU (a 1-frame test). */
async function encoderCaps() {
  if (caps) return caps;
  const enc = (await run(FFMPEG, ["-hide_banner", "-encoders"], { timeout: 20_000 }).catch(() => ({ stdout: "" }))).stdout;
  const flt = (await run(FFMPEG, ["-hide_banner", "-filters"], { timeout: 20_000 }).catch(() => ({ stdout: "" }))).stdout;
  const has = (n) => new RegExp(`\\s${n}\\s`).test(enc);
  const reasons = {};
  const test = async (codec) => {
    if (!has(codec)) {
      reasons[codec] = "not in this ffmpeg build";
      return false;
    }
    const r = await run(FFMPEG, ["-v", "error", "-f", "lavfi", "-i", "color=c=black:s=256x256:d=0.2", "-frames:v", "3", "-c:v", codec, "-f", "null", "-"], { timeout: 20_000 }).catch((e) => ({ code: 1, stderr: String(e) }));
    if (r.code !== 0) reasons[codec] = String(r.stderr || "").split("\n").filter((l) => /driver|nvenc|cuda|device|api/i.test(l)).slice(0, 2).join(" ").slice(0, 300) || "failed";
    return r.code === 0;
  };
  let nv = await test("h264_nvenc");
  if (!nv && FFMPEG !== "ffmpeg") {
    // The bundled build may need a newer NVIDIA driver than this PC has; the system ffmpeg is built against
    // the distro's NVENC headers and often works with the installed driver.
    const sys = await run("ffmpeg", ["-v", "error", "-f", "lavfi", "-i", "color=c=black:s=256x256:d=0.2", "-frames:v", "3", "-c:v", "h264_nvenc", "-f", "null", "-"], { timeout: 20_000 }).catch(() => ({ code: 1 }));
    if (sys.code === 0) {
      ENC_BIN = "ffmpeg";
      nv = true;
      reasons.h264_nvenc = "bundled ffmpeg needs a newer driver; using the system ffmpeg for GPU encoding";
    }
  }
  caps = {
    ffmpeg: FFMPEG,
    encoderBinary: ENC_BIN,
    nvenc_h264: nv,
    nvenc_hevc: ENC_BIN ? true : await test("hevc_nvenc"),
    nvenc_av1: await test("av1_nvenc"),
    x264: has("libx264"),
    x265: has("libx265"),
    prores: has("prores_ks"),
    svtav1: has("libsvtav1"),
    vp9: has("libvpx-vp9"),
    reasons,
    vulkan: useVulkan,
    windowing: useX11 ? "x11 (XWayland)" : process.env.XDG_SESSION_TYPE || "auto",
    gpuRestarts,
    safeGraphics: safeMode,
    driver: await run("nvidia-smi", ["--query-gpu=name,driver_version", "--format=csv,noheader"], { timeout: 10_000 }).then((r) => (r.code === 0 ? r.stdout.trim() : null)).catch(() => null),
    filters: ["vidstabdetect", "minterpolate", "nlmeans", "hqdn3d", "lut3d", "loudnorm", "zscale", "deshake", "scale_cuda"].filter((f) => new RegExp(`\\s${f}\\s`).test(flt)),
  };
  return caps;
}

const BT709 = ["-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv"];
function videoArgs(codec, q, c) {
  // q: quality 0 (smallest) .. 100 (best). Maps to CQ/CRF per encoder.
  const cq = (lo, hi) => String(Math.round(hi - (Math.max(0, Math.min(100, q)) / 100) * (hi - lo)));
  switch (codec) {
    case "nvenc_h264":
      return { ext: "mp4", args: ["-c:v", "h264_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", cq(12, 30), "-b:v", "0", "-maxrate", "200M", "-bufsize", "400M", "-spatial-aq", "1", "-temporal-aq", "1", "-rc-lookahead", "32", "-bf", "3", "-profile:v", "high", "-pix_fmt", "yuv420p", ...BT709] };
    case "nvenc_hevc":
      return { ext: "mp4", args: ["-c:v", "hevc_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", cq(14, 32), "-b:v", "0", "-maxrate", "200M", "-bufsize", "400M", "-spatial-aq", "1", "-temporal-aq", "1", "-profile:v", "main10", "-pix_fmt", "p010le", "-tag:v", "hvc1", ...BT709] };
    case "nvenc_av1":
      return { ext: "mp4", args: ["-c:v", "av1_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", cq(18, 40), "-b:v", "0", "-pix_fmt", "p010le", ...BT709] };
    case "x265":
      return { ext: "mp4", args: ["-c:v", "libx265", "-preset", "medium", "-crf", cq(12, 30), "-pix_fmt", "yuv420p10le", "-tag:v", "hvc1", "-x265-params", "aq-mode=3:log-level=error", ...BT709] };
    case "prores":
      return { ext: "mov", audio: ["-c:a", "pcm_s24le"], args: ["-c:v", "prores_ks", "-profile:v", "3", "-vendor", "apl0", "-bits_per_mb", "8000", "-pix_fmt", "yuv422p10le", ...BT709] };
    case "prores4444":
      return { ext: "mov", audio: ["-c:a", "pcm_s24le"], args: ["-c:v", "prores_ks", "-profile:v", "4", "-vendor", "apl0", "-pix_fmt", "yuva444p10le", ...BT709] };
    case "vp9":
      return { ext: "webm", audio: ["-c:a", "libopus", "-b:a", "256k"], args: ["-c:v", "libvpx-vp9", "-crf", cq(15, 40), "-b:v", "0", "-row-mt", "1", "-deadline", "good", "-cpu-used", "2", "-pix_fmt", "yuv420p"] };
    case "x264":
    default:
      return { ext: "mp4", args: ["-c:v", "libx264", "-preset", "slow", "-crf", cq(10, 28), "-profile:v", "high", "-pix_fmt", "yuv420p", "-x264-params", "aq-mode=3", ...BT709] };
  }
}

const encoders = new Map();
async function encOpen(o) {
  const c = await encoderCaps();
  let codec = o.codec || "auto";
  if (codec === "auto") codec = c.nvenc_h264 ? "nvenc_h264" : "x264";
  if (codec === "hevc") codec = c.nvenc_hevc ? "nvenc_hevc" : "x265";
  if (codec === "h264") codec = c.nvenc_h264 ? "nvenc_h264" : "x264";
  if (codec.startsWith("nvenc") && !c[codec]) throw new Error(`${codec} is not available on this PC (no NVIDIA encoder found). Use auto, x264 or x265.`);
  const v = videoArgs(codec, o.quality ?? 85, c);
  const dir = OUT.Movies;
  const out = await uniquePath(dir, `${String(o.name || "VideoStudio").replace(/\.(mp4|mov|webm)$/i, "")}.${v.ext}`);
  // Frames arrive either as RGBA (bottom row first, as the GPU reads them) or — for 4:2:0 outputs — already converted on
  // the GPU to broadcast BT.709 NV12, top row first: under half the bytes to read back, send and encode.
  // Only for 8-bit 4:2:0 outputs: 10-bit and 4:2:2 encoders keep the full-precision RGBA → YUV conversion.
  const nv12 = o.input === "nv12" && ["nvenc_h264", "x264", "vp9"].includes(codec);
  const args = ["-hide_banner", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", nv12 ? "nv12" : "rgba", "-s", `${o.width}x${o.height}`, "-framerate", String(o.fps), ...(nv12 ? ["-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv"] : []), "-i", "pipe:0"];
  let wav = null;
  if (o.audio && o.audio.byteLength) {
    wav = path.join(os.tmpdir(), `vsd-mix-${crypto.randomUUID()}.wav`);
    await fsp.writeFile(wav, Buffer.from(o.audio.buffer, o.audio.byteOffset, o.audio.byteLength));
    args.push("-i", wav);
  }
  // RGBA from the compositor → broadcast BT.709 YUV with correct matrix and range.
  // WebGL readPixels rows are bottom-up: flip, then RGBA → broadcast BT.709 YUV with the right matrix and range.
  const vf = codec === "prores4444" ? "vflip,format=rgba" : "vflip,scale=in_range=full:out_range=tv:out_color_matrix=bt709:flags=accurate_rnd+full_chroma_int";
  args.push("-map", "0:v", ...(wav ? ["-map", "1:a"] : []), ...(nv12 ? [] : ["-vf", vf]), ...v.args, "-r", String(o.fps));
  if (wav) args.push(...(v.audio || ["-c:a", "aac", "-b:a", "320k", "-ar", "48000"]), "-shortest");
  if (v.ext === "mp4") args.push("-movflags", "+faststart");
  args.push(`${out}.part.${v.ext}`);
  const binary = codec.startsWith("nvenc") && ENC_BIN ? ENC_BIN : FFMPEG;
  const p = spawn(binary, args, { stdio: ["pipe", "ignore", "pipe"] });
  let err = "";
  p.stderr.on("data", (d) => (err = (err + d.toString()).slice(-8000)));
  const done = new Promise((res) => p.on("close", (code) => res(code)));
  p.stdin.on("error", () => {});
  const id = crypto.randomUUID();
  encoders.set(id, { p, out, tmp: `${out}.part.${v.ext}`, wav, done, err: () => err, codec, frames: 0, bytesPerFrame: nv12 ? o.width * o.height * 1.5 : o.width * o.height * 4 });
  return { id, path: out, codec, input: nv12 ? "nv12" : "rgba" };
}
async function encFrame(id, data) {
  const e = encoders.get(id);
  if (!e) throw new Error("Encoder closed.");
  if (data.byteLength !== e.bytesPerFrame) throw new Error(`Frame size ${data.byteLength} != ${e.bytesPerFrame}`);
  const buf = Buffer.from(data.buffer, data.byteOffset, data.byteLength);
  if (e.p.exitCode !== null) throw new Error(`Encoder stopped: ${e.err().trim().split("\n").slice(-3).join(" ")}`);
  e.frames++;
  if (!e.p.stdin.write(buf)) await new Promise((r) => e.p.stdin.once("drain", r));
}
async function encFinish(id) {
  const e = encoders.get(id);
  if (!e) throw new Error("Encoder closed.");
  encoders.delete(id);
  e.p.stdin.end();
  const code = await e.done;
  if (e.wav) fsp.rm(e.wav, { force: true }).catch(() => {});
  if (code !== 0) {
    await fsp.rm(e.tmp, { force: true });
    throw new Error(`Encoding failed: ${e.err().trim().split("\n").slice(-4).join(" ")}`);
  }
  await fsp.rename(e.tmp, e.out);
  const st = await fsp.stat(e.out);
  return { path: e.out, bytes: st.size, codec: e.codec, frames: e.frames };
}
async function encAbort(id) {
  const e = encoders.get(id);
  if (!e) return;
  encoders.delete(id);
  e.p.kill("SIGKILL");
  await e.done;
  await fsp.rm(e.tmp, { force: true });
  if (e.wav) await fsp.rm(e.wav, { force: true });
}

/* ------------------------------------------------------------------ pro media tools (fixed, safe recipes) */

const WORK = () => path.join(app.getPath("userData"), "work");
let progressTarget = null;
function sendProgress(tag, frac) {
  progressTarget?.send("ffmpeg:progress", { tag, frac });
}

/** Run ffmpeg with -progress, reporting 0..1 for a known duration. */
function runProgress(args, duration, tag) {
  return new Promise((resolve, reject) => {
    const p = spawn(FFMPEG, ["-hide_banner", "-v", "error", "-y", "-progress", "pipe:1", "-nostats", ...args], { stdio: ["ignore", "pipe", "pipe"] });
    let err = "";
    p.stderr.on("data", (d) => (err = (err + d.toString()).slice(-6000)));
    p.stdout.on("data", (d) => {
      const m = /out_time_us=(\d+)/.exec(d.toString());
      if (m && duration > 0) sendProgress(tag, Math.min(1, Number(m[1]) / 1e6 / duration));
    });
    p.on("error", reject);
    p.on("close", (code) => (code === 0 ? resolve() : reject(new Error(err.trim().split("\n").slice(-3).join(" ") || `ffmpeg exited ${code}`))));
  });
}

/** Video/audio codecs Chromium decodes itself; anything else is converted to an edit-friendly copy on import. */
function needsConversion(info) {
  const v = info.video;
  const a = info.audio;
  const okV = !v || (["h264", "vp8", "vp9", "av1", "mjpeg", "png", "gif", "webp"].includes(v.codec) && !/10|12|422|444/.test(v.pixfmt || "") && !v.rotation);
  const okA = !a || ["aac", "mp3", "opus", "vorbis", "flac", "pcm_s16le", "pcm_s24le", "pcm_f32le", "pcm_u8"].includes(a.codec);
  return !(okV && okA);
}

async function processMedia(kind, input, o = {}) {
  const src = mustAllow(input);
  const info = await probe(src);
  await fsp.mkdir(WORK(), { recursive: true });
  const base = path.basename(src).replace(/\.[^.]+$/, "");
  const c = await encoderCaps();
  const hq = c.nvenc_h264 ? ["-c:v", "h264_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", "14", "-b:v", "0", "-pix_fmt", "yuv420p"] : ["-c:v", "libx264", "-preset", "medium", "-crf", "13", "-pix_fmt", "yuv420p"];
  const aac = ["-c:a", "aac", "-b:a", "320k", "-ar", "48000"];
  const out = (suffix, ext = "mp4") => path.join(WORK(), `${base} ${suffix}.${ext}`);
  const dur = info.duration;
  const v = info.video;
  const tag = `${kind}:${path.basename(src)}`;
  switch (kind) {
    case "convert": {
      if (!v) {
        const f = out("edit", "flac");
        await runProgress(["-i", src, "-vn", "-c:a", "flac", f], dur, tag);
        return { path: f, note: "Converted to lossless FLAC" };
      }
      const f = out("edit");
      // Phone HEVC/HDR, ProRes, DNxHD, MPEG-2…: visually lossless H.264 with rotation applied and HDR tone-mapped to SDR.
      const hdr = v.hdr && c.filters.includes("zscale") ? "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p" : "format=yuv420p";
      await runProgress(["-i", src, "-map", "0:v:0", "-map", "0:a:0?", "-vf", hdr, ...hq, ...BT709, ...aac, "-movflags", "+faststart", f], dur, tag);
      return { path: f, note: v.hdr ? "Converted to H.264, HDR tone-mapped to SDR" : "Converted to edit-friendly H.264" };
    }
    case "stabilize": {
      if (!v) throw new Error("Stabilise needs a video.");
      if (!c.filters.includes("vidstabdetect")) throw new Error("This ffmpeg has no vid.stab; using deshake instead is possible with mode: deshake.");
      const trf = path.join(WORK(), `${crypto.randomUUID()}.trf`);
      const shake = Math.max(1, Math.min(10, Number(o.shakiness ?? 6)));
      const smooth = Math.max(1, Math.min(100, Number(o.smoothing ?? 30)));
      sendProgress(tag, 0);
      await runProgress(["-i", src, "-vf", `vidstabdetect=shakiness=${shake}:accuracy=15:result=${trf}`, "-f", "null", "-"], dur * 2, tag);
      const f = out("stabilised");
      await runProgress(["-i", src, "-map", "0:v:0", "-map", "0:a:0?", "-vf", `vidstabtransform=input=${trf}:smoothing=${smooth}:optzoom=${o.zoom === false ? 0 : 1}:interpol=bicubic,unsharp=5:5:0.6:3:3:0.2`, ...hq, ...aac, "-movflags", "+faststart", f], dur, tag);
      fsp.rm(trf, { force: true }).catch(() => {});
      return { path: f, note: `Stabilised (smoothing ${smooth})` };
    }
    case "slowmo": {
      if (!v) throw new Error("Slow motion needs a video.");
      const factor = Math.max(1.5, Math.min(8, Number(o.factor ?? 2)));
      const fps = v.fps || 30;
      const f = out(`slowmo ${factor}x`);
      // Motion-compensated interpolation (optical-flow style): new in-between frames, then played at the source rate.
      const mode = o.mode === "blend" ? "mi_mode=blend" : "mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1";
      await runProgress(["-i", src, "-an", "-vf", `minterpolate=fps=${Math.round(fps * factor)}:${mode},setpts=${factor}*PTS`, "-r", String(fps), ...hq, "-movflags", "+faststart", f], dur * factor, tag);
      return { path: f, note: `${factor}× slow motion with interpolated frames` };
    }
    case "denoise": {
      if (!v) throw new Error("Denoise needs a video.");
      const s = Math.max(0.5, Math.min(10, Number(o.strength ?? 4)));
      const f = out("denoised");
      const vf = o.quality === "best" && c.filters.includes("nlmeans") ? `nlmeans=s=${(s * 0.8).toFixed(1)}:p=7:r=15` : `hqdn3d=${s}:${s * 0.75}:${s * 1.5}:${s * 1.1}`;
      await runProgress(["-i", src, "-map", "0:v:0", "-map", "0:a:0?", "-vf", vf, ...hq, ...aac, "-movflags", "+faststart", f], dur, tag);
      return { path: f, note: "Video noise removed" };
    }
    case "upscale": {
      if (!v) throw new Error("Upscale needs a video or picture.");
      const H = Math.max(240, Math.min(4320, Number(o.height ?? 2160)));
      const f = out(`${H}p`);
      await runProgress(["-i", src, "-map", "0:v:0", "-map", "0:a:0?", "-vf", `scale=-2:${H}:flags=lanczos,unsharp=5:5:0.5:5:5:0.0`, ...hq, ...aac, "-movflags", "+faststart", f], dur, tag);
      return { path: f, note: `Upscaled to ${H}p` };
    }
    case "lut": {
      const lut = mustAllow(String(o.lut || ""));
      if (!/\.(cube|3dl|dat|m3d|csp)$/i.test(lut)) throw new Error("LUT must be a .cube / .3dl file.");
      const f = out(`lut ${path.basename(lut).replace(/\.[^.]+$/, "")}`);
      const k = Math.max(0, Math.min(1, Number(o.amount ?? 1)));
      const vf = k >= 0.999 ? `lut3d=file='${lut.replace(/'/g, "\\'")}'` : `split[a][b];[b]lut3d=file='${lut.replace(/'/g, "\\'")}'[l];[a][l]blend=all_expr='A*(1-${k})+B*${k}'`;
      await runProgress(["-i", src, "-map", "0:v:0", "-map", "0:a:0?", "-vf", vf, ...hq, ...aac, "-movflags", "+faststart", f], dur, tag);
      return { path: f, note: `LUT applied (${Math.round(k * 100)}%)` };
    }
    case "reverse": {
      if (dur > 120) throw new Error("Reverse works on clips up to 2 minutes (trim first).");
      const f = out("reversed");
      await runProgress(["-i", src, "-map", "0:v:0?", "-map", "0:a:0?", "-vf", "reverse", "-af", "areverse", ...hq, ...aac, "-movflags", "+faststart", f], dur, tag);
      return { path: f, note: "Reversed" };
    }
    case "loudnorm": {
      const I = Math.max(-30, Math.min(-8, Number(o.target ?? -14)));
      const f = out(`${I}LUFS`, v ? "mp4" : "m4a");
      await runProgress(["-i", src, ...(v ? ["-map", "0:v:0", "-c:v", "copy"] : ["-vn"]), "-map", "0:a:0", "-af", `loudnorm=I=${I}:TP=-1:LRA=11`, ...aac, f], dur, tag);
      return { path: f, note: `Loudness normalised to ${I} LUFS` };
    }
    case "extractAudio": {
      const f = out("audio", "flac");
      await runProgress(["-i", src, "-vn", "-map", "0:a:0", "-c:a", "flac", f], dur, tag);
      return { path: f, note: "Sound extracted (lossless)" };
    }
    case "speed": {
      const k = Math.max(0.25, Math.min(4, Number(o.factor ?? 2)));
      const f = out(`${k}x`);
      const atempo = [];
      let rem = k;
      while (rem > 2) (atempo.push("atempo=2"), (rem /= 2));
      while (rem < 0.5) (atempo.push("atempo=0.5"), (rem /= 0.5));
      atempo.push(`atempo=${rem.toFixed(4)}`);
      await runProgress(["-i", src, "-map", "0:v:0?", "-map", "0:a:0?", "-vf", `setpts=PTS/${k}`, "-af", atempo.join(","), ...hq, ...aac, "-movflags", "+faststart", f], dur / k, tag);
      return { path: f, note: `${k}× speed (pitch kept)` };
    }
  }
  throw new Error(`Unknown process ${kind}. Use convert, stabilize, slowmo, denoise, upscale, lut, reverse, loudnorm, extractAudio, speed.`);
}

/* ------------------------------------------------------------------ self-update (GitHub releases) */

const REPO = "RezoxNemesis/VideoStudio-MCP";
const vnum = (v) => String(v).replace(/^desktop-v/, "").split(".").map((x) => Number(x) || 0);
const newer = (a, b) => {
  const x = vnum(a), y = vnum(b);
  for (let i = 0; i < 3; i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) > (y[i] ?? 0);
  return false;
};
async function updateCheck() {
  const res = await net.fetch(`https://api.github.com/repos/${REPO}/releases?per_page=20`, { headers: { accept: "application/vnd.github+json", "user-agent": "VideoStudio" } });
  if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
  const list = await res.json();
  const rel = list.filter((r) => /^desktop-v\d/.test(r.tag_name) && !r.draft).sort((a, b) => (newer(a.tag_name, b.tag_name) ? -1 : 1))[0];
  if (!rel) return { current: app.getVersion(), available: false };
  const deb = rel.assets.find((a) => a.name.endsWith(".deb"));
  const version = rel.tag_name.replace(/^desktop-v/, "");
  return { current: app.getVersion(), latest: version, available: newer(version, app.getVersion()) && !!deb, notes: rel.body?.slice(0, 1500) ?? "", url: deb?.browser_download_url, size: deb?.size ?? 0, name: deb?.name };
}
// One install at a time: a second caller (the startup check plus a remote job, say) joins the first.
let installing = null;
function updateInstall() {
  if (!installing) installing = doUpdateInstall().finally(() => { installing = null; });
  return installing;
}
async function doUpdateInstall() {
  const u = await updateCheck();
  if (!u.available) return { installed: false, note: `Already up to date (${u.current}).` };
  const file = path.join(DIRS.downloads, u.name);
  const have = await fsp.stat(file).catch(() => null);
  if (!(have && u.size && have.size === u.size)) await downloadUpdate(u, file);
  return installDeb(u, file);
}
async function downloadUpdate(u, file) {
  // Download with progress (resumable is overkill: the file is a few hundred MB from GitHub's CDN).
  const res = await net.fetch(u.url);
  if (!res.ok || !res.body) throw new Error(`Download failed (${res.status}).`);
  await fsp.mkdir(DIRS.downloads, { recursive: true });
  const part = `${file}.part`;
  const out = fs.createWriteStream(part);
  let got = 0;
  const reader = res.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    got += value.length;
    if (!out.write(Buffer.from(value))) await new Promise((r) => out.once("drain", r));
    sendProgress("update", u.size ? got / u.size : 0);
  }
  await new Promise((r) => out.end(r));
  if (u.size && got !== u.size) throw new Error(`Download incomplete (${got} of ${u.size} bytes).`);
  await fsp.rename(part, file);
}
async function installDeb(u, file) {
  // The system asks for the person's password in its own dialog (polkit); nothing runs without it.
  const r = await run("pkexec", ["apt-get", "install", "-y", "--allow-downgrades", file], { timeout: 20 * 60_000 });
  if (r.code !== 0) throw new Error(r.code === 126 || r.code === 127 ? "The password dialog was closed — nothing was installed. The update is saved in Downloads." : `Install failed: ${r.stderr.trim().split("\n").slice(-2).join(" ")}`);
  setTimeout(() => {
    app.relaunch({ args: process.argv.slice(1).concat(["--vsd-relaunch-wait"]) });
    clearGuard();
    app.exit(0);
  }, 1500);
  return { installed: true, version: u.latest, file, note: "Installed — VideoStudio restarts now." };
}

/* ------------------------------------------------------------------ window */

let win = null;

function createWindow() {
  const icon = nativeImage.createFromPath(path.join(__dirname, "..", "build", "icon.png"));
  win = new BrowserWindow({
    width: 1600,
    height: 960,
    minWidth: 1100,
    minHeight: 680,
    frame: false,
    backgroundColor: "#07090d",
    title: "VideoStudio",
    icon: icon.isEmpty() ? undefined : icon,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
      spellcheck: false,
    },
  });
  win.setMenuBarVisibility(false);
  win.once("ready-to-show", () => {
    setTimeout(() => fs.rm(GUARD, { force: true }, () => {}), 12_000);
    win.show();
    if (process.env.VSD_MAXIMIZE !== "0") win.maximize();
  });
  win.on("maximize", () => win.webContents.send("win:state", { maximized: true }));
  win.on("unmaximize", () => win.webContents.send("win:state", { maximized: false }));
  win.webContents.on("render-process-gone", (_e, d) => {
    crashLog(`window gone: ${d.reason} code=${d.exitCode}`);
    if (d.reason !== "clean-exit") setTimeout(() => win && !win.isDestroyed() && win.reload(), 1500);
  });
  win.webContents.on("unresponsive", () => crashLog("window unresponsive"));
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) void shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("before-input-event", (e, input) => {
    if (input.type === "keyDown" && input.control && input.shift && input.key.toLowerCase() === "i") win.webContents.toggleDevTools();
  });
  progressTarget = win.webContents;
  win.loadURL(DEV_URL || "app://studio/index.html");
}

/* ------------------------------------------------------------------ IPC */

function handle(name, fn) {
  ipcMain.handle(name, async (_e, ...args) => {
    try {
      return { ok: true, value: await fn(...args) };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e) };
    }
  });
}

function setupIpc() {
  handle("info", async () => ({
    version: app.getVersion(),
    platform: process.platform,
    arch: process.arch,
    host: os.hostname(),
    user: os.userInfo().username,
    cpus: os.cpus().length,
    cpuModel: os.cpus()[0]?.model,
    memoryGB: Math.round(os.totalmem() / 1e9),
    dirs: DIRS,
    out: OUT,
    drives: mountedDrives(),
    ffmpeg: FFMPEG,
    gpu: app.getGPUFeatureStatus(),
  }));
  handle("fs.list", (dir, o) => list(dir, o));
  handle("fs.find", (root, q, o) => find(root, q, o));
  handle("fs.stat", async (p) => {
    const f = mustAllow(p);
    const st = await fsp.stat(f);
    return { path: f, size: st.size, dir: st.isDirectory(), modified: st.mtimeMs, kind: st.isDirectory() ? "folder" : kindOf(f) };
  });
  handle("fs.url", async (p) => fileUrl(mustAllow(p)));
  handle("fs.reveal", async (p) => shell.showItemInFolder(mustAllow(p)));
  handle("fs.open", async (p) => shell.openPath(mustAllow(p)));
  handle("fs.pick", async (o = {}) => {
    const r = await dialog.showOpenDialog(win, {
      title: o.title || "Import media",
      defaultPath: o.dir || DIRS.videos,
      properties: [o.folder ? "openDirectory" : "openFile", ...(o.multiple === false ? [] : ["multiSelections"])],
      filters: o.folder ? undefined : [{ name: "Media", extensions: [...MEDIA_EXT.video, ...MEDIA_EXT.audio, ...MEDIA_EXT.image, ...MEDIA_EXT.model] }, { name: "All files", extensions: ["*"] }],
    });
    return r.canceled ? [] : r.filePaths;
  });
  handle("fs.pickSave", async (o = {}) => {
    const r = await dialog.showSaveDialog(win, { title: o.title || "Save", defaultPath: path.join(o.dir || OUT.Documents, o.name || "project.vsproj") });
    return r.canceled ? null : r.filePath;
  });
  handle("fs.writeText", async (p, text) => {
    const f = mustAllow(p);
    await fsp.mkdir(path.dirname(f), { recursive: true });
    await fsp.writeFile(f, text);
    return f;
  });
  handle("fs.readText", async (p) => fsp.readFile(mustAllow(p), "utf8"));
  handle("media.probe", (p) => probe(mustAllow(p)));
  handle("media.describe", (p) => media().describe(p));
  handle("media.open", (o) => media().openFrames(o));
  handle("media.read", (id, skip) => media().readFrame(id, skip));
  handle("media.close", (id) => media().closeFrames(id));
  handle("media.status", () => media().status());
  handle("media.selfTest", () => media().selfTest());
  handle("media.ensureProxy", (p) => media().ensureProxy(p));
  handle("media.frames", (p, times, max) => videoFrames(mustAllow(p), times, max));
  handle("audio.inspect", (p, o) => audioInspect(mustAllow(p), o));
  handle("audio.voice16k", async (p, from = 0, seconds = 0) => {
    // 16 kHz mono for Whisper, voice-focused: centre channel, voice band, gentle de-noise. Returns bytes.
    const file = mustAllow(p);
    const args = ["-v", "error"];
    if (from) args.push("-ss", String(from));
    args.push("-i", file);
    if (seconds) args.push("-t", String(seconds));
    args.push("-vn", "-af", "pan=mono|c0=0.5*c0+0.5*c1,highpass=f=110,lowpass=f=7500,afftdn=nf=-30,dynaudnorm=f=200:g=11", "-ar", "16000", "-f", "f32le", "-");
    const r = await run(FFMPEG, args, { binary: true, maxOut: 400 * 1024 * 1024, timeout: 10 * 60_000 });
    if (r.code !== 0) throw new Error(`Could not prepare the voice: ${r.stderr.trim().split("\n").pop()}`);
    return new Uint8Array(r.stdout.buffer, r.stdout.byteOffset, r.stdout.length);
  });
  handle("audio.inspectBytes", async (bytes, name, o) => {
    // For project assets that only live inside the app: write a temp copy, inspect, remove.
    const tmp = path.join(os.tmpdir(), `vsd-${crypto.randomUUID()}-${String(name || "audio").replace(/[^\w.-]/g, "_")}`);
    await fsp.writeFile(tmp, Buffer.from(bytes));
    try {
      return await audioInspect(tmp, o);
    } finally {
      fsp.rm(tmp, { force: true }).catch(() => {});
    }
  });
  handle("save.open", async (collection, name) => {
    const dir = OUT[collection] || OUT.Movies;
    const p = await uniquePath(dir, name);
    const part = `${p}.part`;
    const fd = await fsp.open(part, "w");
    const id = crypto.randomUUID();
    saves.set(id, { fd, path: p, part });
    return { id, path: p };
  });
  handle("save.write", async (id, position, data) => {
    const s = saves.get(id);
    if (!s) throw new Error("That file is closed.");
    const b = Buffer.from(data.buffer, data.byteOffset, data.byteLength);
    await s.fd.write(b, 0, b.length, position);
  });
  handle("save.finish", async (id) => {
    const s = saves.get(id);
    if (!s) throw new Error("That file is closed.");
    saves.delete(id);
    await s.fd.close();
    await fsp.rename(s.part, s.path);
    const fixed = await toAacIfNeeded(s.path);
    return { path: s.path, audio: fixed };
  });
  handle("save.abort", async (id) => {
    const s = saves.get(id);
    if (!s) return;
    saves.delete(id);
    await s.fd.close().catch(() => {});
    await fsp.rm(s.part, { force: true });
  });
  handle("net.download", async (url, headers = {}) => {
    if (!/^https:\/\//i.test(url)) throw new Error("Only https links can be downloaded.");
    const res = await net.fetch(url, { headers });
    if (!res.ok) throw new Error(`Download failed (${res.status}).`);
    const dir = path.join(app.getPath("userData"), "downloads");
    await fsp.mkdir(dir, { recursive: true });
    const p = path.join(dir, crypto.randomUUID());
    const buf = Buffer.from(await res.arrayBuffer());
    await fsp.writeFile(p, buf);
    setTimeout(() => fsp.rm(p, { force: true }).catch(() => {}), 10 * 60_000);
    return { path: p, bytes: buf.length, url: fileUrl(p) };
  });
  handle("enc.caps", () => encoderCaps());
  handle("app.crashLog", async () => {
    try {
      return (await fsp.readFile(CRASH_LOG, "utf8")).split("\n").slice(-40).join("\n");
    } catch {
      return "";
    }
  });
  handle("update.check", () => updateCheck());
  handle("update.install", () => updateInstall());
  handle("enc.open", (o) => encOpen(o));
  handle("enc.frame", (id, data) => encFrame(id, data));
  handle("enc.finish", (id) => encFinish(id));
  handle("enc.abort", (id) => encAbort(id));
  handle("media.process", (kind, input, o) => processMedia(kind, input, o));
  handle("media.needsConversion", async (p) => needsConversion(await probe(mustAllow(p))));
  handle("work.write", async (name, bytes) => {
    await fsp.mkdir(WORK(), { recursive: true });
    const f = path.join(WORK(), `${crypto.randomUUID()}-${String(name).replace(/[^\w.-]+/g, "_").slice(-80)}`);
    await fsp.writeFile(f, Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength));
    return f;
  });
  handle("work.clean", async () => {
    // Drop work files older than a day.
    try {
      for (const f of await fsp.readdir(WORK())) {
        const p = path.join(WORK(), f);
        const st = await fsp.stat(p);
        if (Date.now() - st.mtimeMs > 86_400_000) await fsp.rm(p, { force: true, recursive: true });
      }
    } catch {
      /* none yet */
    }
    return true;
  });
  // Code Studio: films written as code, drawn by the film farm.
  filmHost = require("./film.cjs").setupFilm({
    handle, mustAllow, ffmpeg: FFMPEG, ffprobe: FFPROBE, root: path.join(OUT.Movies, "Code"), userData: app.getPath("userData"), resources: process.resourcesPath,
    send: (channel, msg) => { if (win && !win.isDestroyed()) win.webContents.send(channel, msg); },
  });
  // The Blender forge: the person's own Blender as a machine for making 3D assets.
  require("./forge.cjs").setupForge({ handle, mustAllow, models: path.join(OUT.Movies, "Models"), resources: process.resourcesPath });
  handle("win.capture", async (max = 1600, quality = 78) => {
    const img = await win.webContents.capturePage();
    const sz = img.getSize();
    const scaled = sz.width > max ? img.resize({ width: max, quality: "good" }) : img;
    return `data:image/jpeg;base64,${scaled.toJPEG(quality).toString("base64")}`;
  });
  handle("win.captureSave", async (name) => {
    const img = await win.webContents.capturePage();
    const dir = path.join(DIRS.videos, "VideoStudio", "Screenshots");
    await fsp.mkdir(dir, { recursive: true });
    const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
    const file = path.join(dir, `${String(name || "VideoStudio").replace(/[^\w.-]+/g, "_").slice(0, 60)}_${stamp}.png`);
    await fsp.writeFile(file, img.toPNG());
    const sz = img.getSize();
    return { path: file, width: sz.width, height: sz.height };
  });
  handle("win.minimize", async () => win.minimize());
  handle("win.maximize", async () => (win.isMaximized() ? win.unmaximize() : win.maximize()));
  handle("win.close", async () => win.close());
  handle("win.fullscreen", async () => win.setFullScreen(!win.isFullScreen()));
  handle("win.isMaximized", async () => win.isMaximized());
  handle("app.openExternal", async (url) => {
    if (!/^https:\/\//.test(url)) throw new Error("https only");
    await shell.openExternal(url);
  });
}

/* ------------------------------------------------------------------ boot */

process.on("uncaughtException", (e) => crashLog(`main uncaught: ${e?.stack ?? e}`));
let gpuRestarts = 0;
app.on("child-process-gone", (_e, d) => {
  crashLog(`child gone: ${d.type} ${d.reason} code=${d.exitCode}`);
  if (d.type === "GPU") {
    gpuRestarts++;
    // Tell the window so the viewer rebuilds its picture as soon as the GPU process is back.
    for (const ms of [800, 3000]) setTimeout(() => win && !win.isDestroyed() && win.webContents.send("gpu:lost", { reason: d.reason, code: d.exitCode, restarts: gpuRestarts }), ms);
  }
});
// A normal quit is not a failed start: clear the GPU guard so the next start keeps its GPU settings.
const clearGuard = () => {
  for (const f of [GUARD, SESSION]) {
    try {
      fs.rmSync(f, { force: true });
    } catch {
      /* ignore */
    }
  }
};
app.on("will-quit", clearGuard);
app.on("will-quit", () => mediaCore?.stopAll());
let filmHost = null;
app.on("will-quit", () => filmHost?.close());
// Logging out or shutting down ends the app with a signal; that is a normal end too.
for (const sig of ["SIGTERM", "SIGINT", "SIGHUP"])
  process.on(sig, () => {
    clearGuard();
    app.quit();
  });
if (!firstInstance) app.quit();
else {
  app.on("second-instance", () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(() => {
    setupProtocols();
    protocol.handle("vsmedia", (req) => media().handle(req));
    setupIpc();
    void media().selfTest().then(() => media().cleanProxies());
    // Allow the engine's own features (clipboard, media, notifications); nothing else.
    session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) => cb(["clipboard-read", "clipboard-sanitized-write", "media", "notifications", "fullscreen", "window-management"].includes(perm)));
    createWindow();
    app.on("activate", () => BrowserWindow.getAllWindows().length === 0 && createWindow());
  });
  app.on("window-all-closed", () => app.quit());
}
