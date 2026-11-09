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

const WEB_ROOT = path.join(__dirname, "..", "dist-web");
const HOME = os.homedir();
const DEV_URL = process.env.VSD_DEV_URL || "";

// GPU: WebGPU (on-device AI) and GPU rasterisation for the compositor.
app.commandLine.appendSwitch("enable-unsafe-webgpu");
app.commandLine.appendSwitch("ignore-gpu-blocklist");
app.commandLine.appendSwitch("enable-gpu-rasterization");
app.commandLine.appendSwitch("enable-zero-copy");
if (process.env.VSD_NO_SANDBOX) app.commandLine.appendSwitch("no-sandbox");
app.setName("VideoStudio Desktop");

protocol.registerSchemesAsPrivileged([
  { scheme: "app", privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true, codeCache: true } },
  { scheme: "vsfile", privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true, bypassCSP: true } },
]);

/* ------------------------------------------------------------------ binaries */

function bin(name) {
  // Bundled ffmpeg/ffprobe (inside the package, unpacked from the asar), else the system one.
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
    video: v ? { codec: v.codec_name, width: v.width, height: v.height, fps: d ? Math.round((n / d) * 1000) / 1000 : 0, pixfmt: v.pix_fmt, rotation: Number(v.tags?.rotate || v.side_data_list?.find((s) => s.rotation !== undefined)?.rotation || 0), hdr: /bt2020|smpte2084|arib/.test(`${v.color_primaries} ${v.color_transfer}`) } : null,
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
    title: "VideoStudio Desktop",
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
    win.show();
    if (process.env.VSD_MAXIMIZE !== "0") win.maximize();
  });
  win.on("maximize", () => win.webContents.send("win:state", { maximized: true }));
  win.on("unmaximize", () => win.webContents.send("win:state", { maximized: false }));
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) void shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("before-input-event", (e, input) => {
    if (input.type === "keyDown" && input.control && input.shift && input.key.toLowerCase() === "i") win.webContents.toggleDevTools();
  });
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
  handle("media.frames", (p, times, max) => videoFrames(mustAllow(p), times, max));
  handle("audio.inspect", (p, o) => audioInspect(mustAllow(p), o));
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
  handle("win.capture", async (max = 1600, quality = 78) => {
    const img = await win.webContents.capturePage();
    const sz = img.getSize();
    const scaled = sz.width > max ? img.resize({ width: max, quality: "good" }) : img;
    return `data:image/jpeg;base64,${scaled.toJPEG(quality).toString("base64")}`;
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

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on("second-instance", () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(() => {
    setupProtocols();
    setupIpc();
    // Allow the engine's own features (clipboard, media, notifications); nothing else.
    session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) => cb(["clipboard-read", "clipboard-sanitized-write", "media", "notifications", "fullscreen", "window-management"].includes(perm)));
    createWindow();
    app.on("activate", () => BrowserWindow.getAllWindows().length === 0 && createWindow());
  });
  app.on("window-all-closed", () => app.quit());
}
