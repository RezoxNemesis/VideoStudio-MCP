"use strict";
/**
 * Native media core (VideoStudio 2).
 *
 * Every picture and every sound the editor shows or renders is decoded here by ffmpeg — on the graphics card when
 * it has room (NVDEC), on the processor otherwise — and handed to the window as plain RGBA frames / float PCM.
 * Nothing goes through the browser engine's own video path any more: that path returned empty (green) frames
 * whenever the graphics card's memory was full (for example while Blender was rendering).
 *
 *   vsmedia://m/frames?p=<file>&ss=<s>&w=<px>&h=<px>&fps=<n>&q=preview|export   raw RGBA frames, one after another
 *   vsmedia://m/audio?p=<file>&sr=48000                                         interleaved stereo float32 PCM
 *   vsmedia://m/thumb?p=<file>&t=<s>&w=<px>                                     one JPEG picture
 *
 * Big / heavy videos also get a light editing copy (720p, a key frame every half second) made in the background,
 * checked against the original before it is used, and picked automatically for preview-sized requests.
 */
const { spawn } = require("child_process");
const path = require("path");
const fs = require("fs");
const fsp = fs.promises;
const crypto = require("crypto");
const { PassThrough, Readable } = require("stream");

module.exports = function createMediaCore({ FFMPEG, probe, mustAllow, cacheDir, log = () => {}, onProxy = () => {}, encoderCaps = async () => ({}) }) {
  const PROXY_DIR = path.join(cacheDir, "proxies");
  const HW_CODECS = new Set(["h264", "hevc", "av1", "vp9"]);
  const state = { hw: null, hwNote: "not tested yet", streams: 0, started: 0, hwFallbacks: 0, proxies: 0, proxyFailures: 0, lastError: "" };
  const live = new Map(); // id -> { child, at, file }
  let seq = 0;

  /* ---------------------------------------------------------------- probing (cached per file + mtime) */
  const infoCache = new Map();
  async function info(file) {
    const st = await fsp.stat(file);
    const key = `${file}|${st.size}|${st.mtimeMs}`;
    let p = infoCache.get(key);
    if (!p) {
      p = probe(file).then((i) => ({ ...i, key, mtimeMs: st.mtimeMs }));
      infoCache.set(key, p);
      p.catch(() => infoCache.delete(key));
      if (infoCache.size > 400) infoCache.delete(infoCache.keys().next().value);
    }
    return p;
  }
  /** Display size (rotation applied) and a sane frame rate. */
  function shape(i) {
    const v = i.video;
    if (!v) return null;
    const rot = Math.abs(Math.round(Number(v.rotation) || 0)) % 180 === 90;
    const fps = v.fps && v.fps > 0.5 && v.fps < 480 ? v.fps : 30;
    return { w: rot ? v.height : v.width, h: rot ? v.width : v.height, fps };
  }

  /* ---------------------------------------------------------------- small helpers */
  function runBuf(args, { timeout = 60_000, max = 256 * 1024 * 1024 } = {}) {
    return new Promise((resolve) => {
      const c = spawn(FFMPEG, args, { stdio: ["ignore", "pipe", "pipe"] });
      const out = [];
      let n = 0;
      let err = "";
      const t = setTimeout(() => c.kill("SIGKILL"), timeout);
      c.stdout.on("data", (d) => {
        n += d.length;
        if (n <= max) out.push(d);
      });
      c.stderr.on("data", (d) => {
        err = (err + d.toString()).slice(-4000);
      });
      c.on("error", (e) => {
        clearTimeout(t);
        resolve({ code: -1, out: Buffer.alloc(0), err: String(e.message || e) });
      });
      c.on("close", (code) => {
        clearTimeout(t);
        resolve({ code, out: Buffer.concat(out), err });
      });
    });
  }
  const even = (n) => Math.max(2, Math.round(n / 2) * 2);
  /** Colour handling so ffmpeg's RGB matches what players show: HD without a tag is BT.709, HDR is tone-mapped. */
  function colourChain(i, w, h, quality) {
    const v = i.video;
    const flags = quality === "export" ? "bicubic+accurate_rnd+full_chroma_int" : "fast_bilinear";
    const untaggedHd = !v.colorspace || v.colorspace === "unknown" ? (Math.max(v.width, v.height) >= 1000 ? ":in_color_matrix=bt709" : ":in_color_matrix=bt601") : "";
    if (v.hdr && state.zscale) {
      return `zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p,scale=${w}:${h}:flags=${flags}:in_color_matrix=bt709`;
    }
    return `scale=${w}:${h}:flags=${flags}${untaggedHd}`;
  }
  function wantHw(i) {
    const v = i.video;
    return state.hw === true && v && HW_CODECS.has(v.codec) && v.width * v.height >= 2560 * 1440 && !/422|444/.test(v.pixfmt || "");
  }

  /* ---------------------------------------------------------------- self test (is the GPU decoder usable right now?) */
  async function selfTest() {
    try {
      await fsp.mkdir(cacheDir, { recursive: true });
      const flt = await runBuf(["-hide_banner", "-filters"], { timeout: 15_000 });
      state.zscale = / zscale /.test(flt.out.toString());
      const clip = path.join(cacheDir, "selftest.mp4");
      if (!fs.existsSync(clip)) {
        const mk = await runBuf(["-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "15", clip], { timeout: 30_000 });
        if (mk.code !== 0) throw new Error(`test clip: ${mk.err.trim().split("\n").pop()}`);
      }
      const grab = (hw) => runBuf(["-v", "error", ...(hw ? ["-hwaccel", "cuda"] : []), "-i", clip, "-frames:v", "1", "-vf", "scale=64:36", "-f", "rawvideo", "-pix_fmt", "rgba", "-"], { timeout: 20_000 });
      const sw = await grab(false);
      if (sw.code !== 0 || sw.out.length !== 64 * 36 * 4) throw new Error(`software decode failed: ${sw.err.trim().split("\n").pop() || sw.code}`);
      const hw = await grab(true);
      if (hw.code === 0 && hw.out.length === sw.out.length) {
        let diff = 0;
        for (let k = 0; k < sw.out.length; k += 4) diff += Math.abs(sw.out[k] - hw.out[k]) + Math.abs(sw.out[k + 1] - hw.out[k + 1]) + Math.abs(sw.out[k + 2] - hw.out[k + 2]);
        const mean = diff / ((sw.out.length / 4) * 3);
        state.hw = mean < 6;
        state.hwNote = state.hw ? "graphics-card decoding works (used for 1440p and larger)" : `graphics-card decoding gave a different picture (mean difference ${mean.toFixed(1)}); using the processor`;
      } else {
        state.hw = false;
        state.hwNote = `graphics-card decoding is not available (${(hw.err.trim().split("\n").pop() || "no output").slice(0, 160)}); using the processor`;
      }
    } catch (e) {
      state.hw = false;
      state.hwNote = `self test failed: ${e.message || e}`;
    }
    log(`media core: ${state.hwNote}`);
    return status();
  }

  /* ---------------------------------------------------------------- editing copies (proxies) */
  const proxyState = new Map(); // key -> "ready" | "failed" | Promise
  const proxyFile = (i) => path.join(PROXY_DIR, `${crypto.createHash("sha1").update(i.key).digest("hex").slice(0, 20)}.mp4`);
  function proxyWorthIt(i) {
    const v = i.video;
    if (!v || !(i.duration > 2)) return false;
    const big = v.width * v.height > 1920 * 1080 * 1.1;
    const heavy = i.bitrate > 45_000_000;
    const odd = !["h264"].includes(v.codec) || /10|12|422|444/.test(v.pixfmt || "");
    const hugeFile = i.size > 1.5e9;
    return big || heavy || hugeFile || (odd && v.width * v.height > 1280 * 720);
  }
  function proxyReady(i) {
    return proxyState.get(i.key) === "ready" ? proxyFile(i) : null;
  }
  /** Mean absolute colour difference of one small frame taken from two files at the same time (0..255). */
  async function sameFrame(a, b, t) {
    const grab = (f, i) => runBuf(["-v", "error", "-ss", String(t), "-i", f, "-frames:v", "1", "-vf", colourChain(i, 64, 36, "preview"), "-f", "rawvideo", "-pix_fmt", "rgba", "-"], { timeout: 60_000 });
    const [ia, ib] = await Promise.all([info(a), info(b)]);
    const [x, y] = await Promise.all([grab(a, ia), grab(b, ib)]);
    if (x.code !== 0 || y.code !== 0 || x.out.length !== 64 * 36 * 4 || y.out.length !== x.out.length) return 999;
    let diff = 0;
    for (let k = 0; k < x.out.length; k += 4) diff += Math.abs(x.out[k] - y.out[k]) + Math.abs(x.out[k + 1] - y.out[k + 1]) + Math.abs(x.out[k + 2] - y.out[k + 2]);
    return diff / ((x.out.length / 4) * 3);
  }
  let proxyChain = Promise.resolve();
  function ensureProxy(file) {
    return info(file).then((i) => {
      if (!proxyWorthIt(i)) return { proxy: false };
      const cur = proxyState.get(i.key);
      if (cur === "ready") return { proxy: true, ready: true };
      if (cur === "failed") return { proxy: false, failed: true };
      if (cur) return { proxy: true, ready: false };
      const job = proxyChain.then(() => makeProxy(i)).catch((e) => {
        state.proxyFailures++;
        state.lastError = `editing copy of ${i.name}: ${e.message || e}`;
        log(`media core: ${state.lastError}`);
        proxyState.set(i.key, "failed");
        onProxy({ path: file, done: true, ok: false });
      });
      proxyChain = job;
      proxyState.set(i.key, job);
      return { proxy: true, ready: false };
    });
  }
  async function makeProxy(i) {
    const s = shape(i);
    const out = proxyFile(i);
    await fsp.mkdir(PROXY_DIR, { recursive: true });
    const verify = async () => {
      const t = Math.min(Math.max(0.2, i.duration / 3), Math.max(0, i.duration - 0.5));
      return (await sameFrame(i.path, out, t)) < 14;
    };
    if (fs.existsSync(out) && (await verify())) {
      proxyState.set(i.key, "ready");
      fs.utimes(out, new Date(), new Date(), () => {});
      onProxy({ path: i.path, done: true, ok: true });
      return;
    }
    const k = Math.min(1, 1280 / Math.max(s.w, s.h));
    const w = even(s.w * k);
    const h = even(s.h * k);
    const gop = String(Math.max(6, Math.round(s.fps / 2)));
    const caps = await encoderCaps().catch(() => ({}));
    const part = `${out}.part.mp4`;
    const attempt = (hwDec, nvenc) =>
      new Promise((resolve) => {
        const args = ["-hide_banner", "-v", "error", "-nostdin", "-y", ...(hwDec ? ["-hwaccel", "cuda"] : []), "-i", i.path, "-an", "-sn", "-dn", "-vf", `${colourChain(i, w, h, "preview")},format=yuv420p`,
          ...(nvenc ? ["-c:v", "h264_nvenc", "-preset", "p2", "-rc", "vbr", "-cq", "27", "-b:v", "0", "-bf", "0"] : ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-bf", "0"]),
          "-g", gop, "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-movflags", "+faststart", "-progress", "pipe:1", part];
        const c = spawn(FFMPEG, args, { stdio: ["ignore", "pipe", "pipe"] });
        try {
          require("os").setPriority(c.pid, 10);
        } catch {
          /* best effort */
        }
        let err = "";
        c.stderr.on("data", (d) => (err = (err + d.toString()).slice(-2000)));
        c.stdout.on("data", (d) => {
          const m = /out_time_ms=(\d+)/.exec(d.toString());
          if (m) onProxy({ path: i.path, done: false, progress: Math.min(0.99, Number(m[1]) / 1e6 / Math.max(0.1, i.duration)) });
        });
        c.on("error", (e) => resolve({ code: -1, err: String(e.message || e) }));
        c.on("close", (code) => resolve({ code, err }));
      });
    let r = await attempt(wantHw(i), !!caps.nvenc_h264);
    if (r.code !== 0 && (wantHw(i) || caps.nvenc_h264)) r = await attempt(false, false); // the graphics card is busy: do it on the processor
    if (r.code !== 0) throw new Error((r.err.trim().split("\n").pop() || `ffmpeg exited ${r.code}`).slice(0, 200));
    await fsp.rename(part, out);
    if (!(await verify())) {
      // A copy that does not look like the original is thrown away (this is what used to stay green).
      await fsp.rm(out, { force: true });
      const again = await attempt(false, false);
      if (again.code !== 0) throw new Error("the copy did not match the original and the second try failed");
      await fsp.rename(part, out);
      if (!(await verify())) {
        await fsp.rm(out, { force: true });
        throw new Error("the copy did not match the original picture; the original is used instead");
      }
    }
    proxyState.set(i.key, "ready");
    state.proxies++;
    onProxy({ path: i.path, done: true, ok: true });
  }
  async function cleanProxies(maxBytes = 30e9, maxAgeDays = 45) {
    try {
      const list = [];
      for (const f of await fsp.readdir(PROXY_DIR)) {
        const p = path.join(PROXY_DIR, f);
        const st = await fsp.stat(p);
        if (f.includes(".part")) {
          if (Date.now() - st.mtimeMs > 3_600_000) await fsp.rm(p, { force: true });
          continue;
        }
        list.push({ p, size: st.size, at: Math.max(st.atimeMs, st.mtimeMs) });
      }
      list.sort((a, b) => b.at - a.at);
      let total = 0;
      for (const e of list) {
        total += e.size;
        if (total > maxBytes || Date.now() - e.at > maxAgeDays * 86_400_000) await fsp.rm(e.p, { force: true });
      }
    } catch {
      /* nothing cached yet */
    }
  }

  /* ---------------------------------------------------------------- streams */
  function reap() {
    // Never keep more than a handful of decoders alive: the one that has gone longest without being read goes first
    // (a split screen can legitimately run several decoders on the same file at once).
    while (live.size > 16) {
      const [id, e] = [...live.entries()].sort((a, b) => a[1].at - b[1].at)[0];
      try {
        e.child.kill("SIGKILL");
      } catch {
        /* gone */
      }
      live.delete(id);
    }
  }
  function streamResponse(build, headers, label) {
    const id = ++seq;
    const body = new PassThrough({ highWaterMark: 8 * 1024 * 1024 });
    let child = null;
    let closed = false;
    const stop = () => {
      closed = true;
      live.delete(id);
      try {
        child?.kill("SIGKILL");
      } catch {
        /* gone */
      }
    };
    body.on("close", stop);
    body.on("error", stop);
    const start = (tryHw) => {
      if (closed) return;
      const args = build(tryHw);
      let bytes = 0;
      let err = "";
      child = spawn(FFMPEG, args, { stdio: ["ignore", "pipe", "pipe"] });
      live.set(id, { child, at: Date.now(), label });
      reap();
      state.started++;
      child.stdout.on("data", (d) => {
        bytes += d.length;
        if (!body.write(d)) {
          child.stdout.pause();
          body.once("drain", () => child?.stdout.resume());
        }
      });
      child.stderr.on("data", (d) => (err = (err + d.toString()).slice(-2000)));
      child.on("error", (e) => {
        state.lastError = `${label}: ${e.message || e}`;
        if (!closed) body.end();
      });
      child.on("close", (code) => {
        live.delete(id);
        if (closed) return;
        if (tryHw && bytes === 0) {
          // The graphics card refused (busy or out of memory): same request on the processor.
          state.hwFallbacks++;
          return start(false);
        }
        if (code !== 0 && bytes === 0) {
          state.lastError = `${label}: ${(err.trim().split("\n").pop() || `ffmpeg exited ${code}`).slice(0, 240)}`;
          log(`media core: ${state.lastError}`);
        }
        body.end();
      });
    };
    return { start, response: () => new Response(Readable.toWeb(body), { headers: { "content-type": "application/octet-stream", "cache-control": "no-store", "access-control-allow-origin": "*", "access-control-expose-headers": "*", "Cross-Origin-Resource-Policy": "cross-origin", ...headers } }) };
  }

  async function frames(q) {
    const file = mustAllow(q.get("p") || "");
    const i = await info(file);
    const s = shape(i);
    if (!s) return new Response("no video", { status: 415 });
    const quality = q.get("q") === "export" ? "export" : "preview";
    const ss = Math.max(0, Number(q.get("ss")) || 0);
    const fps = Math.min(240, Math.max(1, Number(q.get("fps")) || s.fps));
    let w = even(Number(q.get("w")) || s.w);
    let h = even(Number(q.get("h")) || s.h);
    // Preview-sized requests read the light editing copy once it exists (fast seeking, no load on the graphics card).
    let src = file;
    let si = i;
    const px = quality === "preview" && Math.max(w, h) <= 1300 ? proxyReady(i) : null;
    if (px && fs.existsSync(px)) {
      src = px;
      si = await info(px);
    } else if (quality === "preview") void ensureProxy(file).catch(() => {});
    const chain = `fps=${fps}:round=near,${colourChain(si, w, h, quality)},setsar=1`;
    const hw = src === file && wantHw(i);
    const build = (tryHw) => ["-hide_banner", "-v", "error", "-nostdin", ...(tryHw ? ["-hwaccel", "cuda"] : ["-threads", "0"]), "-ss", ss.toFixed(6), "-i", src, "-an", "-sn", "-dn", "-vf", chain, "-f", "rawvideo", "-pix_fmt", "rgba", "-"];
    const st = streamResponse(build, { "x-w": String(w), "x-h": String(h), "x-fps": String(fps), "x-src": src === file ? "original" : "proxy", "x-hw": hw ? "1" : "0" }, `frames ${path.basename(file)}`);
    st.start(hw);
    state.streams++;
    return st.response();
  }

  /* ---------------------------------------------------------------- frame streams over IPC (fast path)
   * Whole frames are assembled here and handed to the window one per call. This moves 4K frames several times
   * faster than a network-style stream, which arrives in 64 KB pieces. */
  const fstreams = new Map();
  let fseq = 0;
  async function openFrames(o) {
    const file = mustAllow(String(o.path || ""));
    const i = await info(file);
    const s = shape(i);
    if (!s) throw new Error(`${i.name} has no picture.`);
    const quality = o.quality === "export" ? "export" : "preview";
    const ss = Math.max(0, Number(o.ss) || 0);
    const fps = Math.min(240, Math.max(1, Number(o.fps) || s.fps));
    const w = even(Number(o.w) || s.w);
    const h = even(Number(o.h) || s.h);
    let src = file;
    let si = i;
    const px = quality === "preview" && Math.max(w, h) <= 1300 ? proxyReady(i) : null;
    if (px && fs.existsSync(px)) {
      src = px;
      si = await info(px);
    } else if (quality === "preview") void ensureProxy(file).catch(() => {});
    const chain = `fps=${fps}:round=near,${colourChain(si, w, h, quality)},setsar=1`;
    const hw = src === file && wantHw(i);
    const id = `f${++fseq}`;
    const st = { id, bytes: w * h * 4, queue: [], cur: null, have: 0, waiters: [], ended: false, closed: false, child: null, produced: 0, at: Date.now(), label: `frames ${i.name}` };
    fstreams.set(id, st);
    const wake = () => {
      while (st.waiters.length && (st.queue.length || st.ended)) st.waiters.shift()();
    };
    const start = (tryHw) => {
      if (st.closed) return;
      const args = ["-hide_banner", "-v", "error", "-nostdin", ...(tryHw ? ["-hwaccel", "cuda"] : ["-threads", "0"]), "-ss", ss.toFixed(6), "-i", src, "-an", "-sn", "-dn", "-vf", chain, "-f", "rawvideo", "-pix_fmt", "rgba", "-"];
      let err = "";
      const c = spawn(FFMPEG, args, { stdio: ["ignore", "pipe", "pipe"] });
      st.child = c;
      live.set(id, { child: c, at: Date.now(), label: st.label });
      reap();
      state.started++;
      c.stdout.on("data", (d) => {
        let off = 0;
        while (off < d.length) {
          if (!st.cur) {
            st.cur = Buffer.allocUnsafe(st.bytes);
            st.have = 0;
          }
          const n = Math.min(st.bytes - st.have, d.length - off);
          d.copy(st.cur, st.have, off, off + n);
          st.have += n;
          off += n;
          if (st.have === st.bytes) {
            st.queue.push(st.cur);
            st.cur = null;
            st.produced++;
          }
        }
        if (st.queue.length >= 3) c.stdout.pause();
        wake();
      });
      c.stderr.on("data", (d) => (err = (err + d.toString()).slice(-2000)));
      c.on("error", (e) => {
        state.lastError = `${st.label}: ${e.message || e}`;
        st.ended = true;
        wake();
      });
      c.on("close", (code) => {
        live.delete(id);
        if (st.closed) return;
        if (tryHw && st.produced === 0) {
          state.hwFallbacks++;
          return start(false);
        }
        if (code !== 0 && st.produced === 0) {
          state.lastError = `${st.label}: ${(err.trim().split("\n").pop() || `ffmpeg exited ${code}`).slice(0, 240)}`;
          log(`media core: ${state.lastError}`);
        }
        st.ended = true;
        wake();
      });
    };
    start(hw);
    state.streams++;
    return { id, w, h, fps, src: src === file ? "original" : "proxy", hw };
  }
  /** The next frame (after dropping `skip` frames the window does not need), or null at the end. */
  async function readFrame(id, skip = 0) {
    const st = fstreams.get(id);
    if (!st) return null;
    st.at = Date.now();
    const lv = live.get(id);
    if (lv) lv.at = st.at;
    for (;;) {
      if (st.queue.length) {
        const f = st.queue.shift();
        if (st.queue.length < 2 && st.child && !st.ended) st.child.stdout.resume();
        if (skip > 0) {
          skip--;
          continue;
        }
        return f;
      }
      if (st.ended || st.closed) return null;
      await new Promise((r) => st.waiters.push(r));
    }
  }
  function closeFrames(id) {
    const st = fstreams.get(id);
    if (!st) return false;
    st.closed = true;
    st.ended = true;
    fstreams.delete(id);
    live.delete(id);
    try {
      st.child?.kill("SIGKILL");
    } catch {
      /* gone */
    }
    st.queue.length = 0;
    while (st.waiters.length) st.waiters.shift()();
    return true;
  }
  // A window that went away (reload, crash) must not leave decoders behind.
  setInterval(() => {
    const now = Date.now();
    for (const [id, st] of fstreams) if (now - st.at > 20_000) closeFrames(id);
  }, 5000).unref?.();

  async function audio(q) {
    const file = mustAllow(q.get("p") || "");
    const i = await info(file);
    if (!i.audio) return new Response("no audio", { status: 204 });
    const sr = Math.min(96000, Math.max(8000, Number(q.get("sr")) || 48000));
    const ss = Math.max(0, Number(q.get("ss")) || 0);
    const dur = Number(q.get("t")) || 0;
    const build = () => ["-hide_banner", "-v", "error", "-nostdin", ...(ss ? ["-ss", String(ss)] : []), "-i", file, ...(dur ? ["-t", String(dur)] : []), "-vn", "-sn", "-dn", "-ac", "2", "-ar", String(sr), "-f", "f32le", "-"];
    const st = streamResponse(build, { "x-sr": String(sr), "x-ch": "2", "x-duration": String(i.duration || 0) }, `audio ${path.basename(file)}`);
    st.start(false);
    return st.response();
  }

  const thumbCache = new Map();
  async function thumb(q) {
    const file = mustAllow(q.get("p") || "");
    const i = await info(file);
    const s = shape(i);
    if (!s) return new Response("no video", { status: 415 });
    const t = Math.max(0, Math.min(Number(q.get("t")) || 0, Math.max(0, i.duration - 0.05)));
    const w = even(Math.min(1920, Math.max(32, Number(q.get("w")) || 320)));
    const h = even((w * s.h) / s.w);
    const key = `${i.key}|${t.toFixed(3)}|${w}`;
    let buf = thumbCache.get(key);
    if (!buf) {
      const px = proxyReady(i);
      const src = px && fs.existsSync(px) && w <= 1280 ? px : file;
      const si = src === file ? i : await info(src);
      const r = await runBuf(["-v", "error", "-ss", t.toFixed(4), "-i", src, "-frames:v", "1", "-vf", colourChain(si, w, h, "preview"), "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "4", "-"], { timeout: 60_000 });
      if (r.code !== 0 || !r.out.length) return new Response(r.err.slice(-300) || "no picture", { status: 500 });
      buf = r.out;
      thumbCache.set(key, buf);
      if (thumbCache.size > 600) thumbCache.delete(thumbCache.keys().next().value);
    }
    return new Response(buf, { headers: { "content-type": "image/jpeg", "cache-control": "max-age=3600", "access-control-allow-origin": "*", "Cross-Origin-Resource-Policy": "cross-origin" } });
  }

  async function handle(req) {
    try {
      const u = new URL(req.url);
      const kind = u.pathname.replace(/^\/+/, "");
      if (kind === "frames") return await frames(u.searchParams);
      if (kind === "audio") return await audio(u.searchParams);
      if (kind === "thumb") return await thumb(u.searchParams);
      return new Response("unknown", { status: 404 });
    } catch (e) {
      state.lastError = String(e.message || e);
      return new Response(state.lastError, { status: /Outside your folders/.test(state.lastError) ? 403 : 500 });
    }
  }

  function status() {
    return { gpuDecode: state.hw, note: state.hwNote, decodersRunning: live.size, streamsOpened: state.streams, gpuFallbacks: state.hwFallbacks, editingCopiesMade: state.proxies, editingCopyFailures: state.proxyFailures, lastError: state.lastError, ffmpeg: FFMPEG };
  }
  /** What the importer needs to register a file without copying it. */
  async function describe(file) {
    const f = mustAllow(file);
    const i = await info(f);
    const s = shape(i);
    return { path: f, name: i.name, size: i.size, duration: i.duration, width: s?.w ?? 0, height: s?.h ?? 0, fps: s?.fps ?? 0, video: i.video, audio: i.audio, proxy: s ? proxyWorthIt(i) : false, proxyReady: !!proxyReady(i) };
  }
  function stopAll() {
    for (const [, e] of live) {
      try {
        e.child.kill("SIGKILL");
      } catch {
        /* gone */
      }
    }
    live.clear();
    for (const id of [...fstreams.keys()]) closeFrames(id);
  }

  return { handle, openFrames, readFrame, closeFrames, selfTest, status, describe, ensureProxy: (f) => ensureProxy(mustAllow(f)), cleanProxies, stopAll };
};
