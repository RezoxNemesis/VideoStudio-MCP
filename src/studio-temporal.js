const STUDIO_TEMPORAL_JS = String.raw`
/*
 * VideoStudio Neural Temporal Motion
 *
 * Local-first temporal synthesis for Studio Web.
 * Neural component: RAFT dense optical flow executed with ONNX Runtime WebGPU.
 * The optical-flow model is downloaded on demand and cached in the browser.
 *
 * Default model:
 *   opencv/optical_flow_estimation_raft
 * Original RAFT: BSD-3-Clause. ONNX conversion: MIT.
 *
 * This engine does not pretend that optical flow is a full video diffusion
 * model. It turns multiple neural/imported anchor frames into coherent moving
 * video by estimating dense bidirectional motion and rendering flow-guided
 * interpolation. The resulting video is a normal VideoStudio Media Bin asset
 * and can be used directly by Cinematic Worlds.
 */
(() => {
  "use strict";

  const $ = id => document.getElementById(id);
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, Number(n) || 0));
  const lerp = (a, b, t) => a + (b - a) * t;
  const ease = t => t * t * (3 - 2 * t);

  const TEMPORAL_RUNTIME_VERSION = "1.0.0";
  const FLOW_W = 480;
  const FLOW_H = 360;
  const ORT_VERSION = "1.17.1";
  const ORT_SCRIPT = "https://cdn.jsdelivr.net/npm/onnxruntime-web@" + ORT_VERSION + "/dist/ort.webgpu.min.js";
  const ORT_DIST = "https://cdn.jsdelivr.net/npm/onnxruntime-web@" + ORT_VERSION + "/dist/";
  const DEFAULT_MODEL_BASE = "https://huggingface.co/opencv/optical_flow_estimation_raft/resolve/main";
  const MODEL_FILES = [
    "optical_flow_estimation_raft_2023aug_int8bq.onnx",
    "optical_flow_estimation_raft_2023aug.onnx"
  ];

  const state = {
    ort: null,
    session: null,
    busy: false,
    backend: "",
    modelBase: localStorage.getItem("vs-temporal-model-base") || DEFAULT_MODEL_BASE,
    modelFile: localStorage.getItem("vs-temporal-model-file") || MODEL_FILES[0],
    cacheName: "videostudio-temporal-models-v1",
    lastProbe: null,
    selectedAnchorIds: []
  };

  const deviceId = () => localStorage.getItem("vs-device-id") || "";
  const projectId = () => localStorage.getItem("vs-project-id") || "";
  const assetKey = (pid, aid) => pid + ":" + aid;

  async function api(path, options = {}) {
    const r = await fetch(path, {
      ...options,
      headers: {"content-type": "application/json", ...(options.headers || {})}
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) { const error = new Error(data.error || "Request failed"); error.status = r.status; throw error; }
    return data;
  }

  function toast(message) {
    const n = $("toast");
    if (n) {
      n.textContent = message;
      n.classList.add("show");
      clearTimeout(toast.t);
      toast.t = setTimeout(() => n.classList.remove("show"), 2800);
    } else console.log("[VideoStudio Temporal]", message);
  }

  function log(title, detail) {
    const host = $("commandLog");
    if (!host) return;
    const item = document.createElement("div");
    item.className = "log";
    const a = document.createElement("strong");
    const b = document.createElement("span");
    a.textContent = title;
    b.textContent = detail || "";
    item.append(a, b);
    host.prepend(item);
  }

  function setStatus(text, percent) {
    const n = $("vsTemporalStatus");
    const bar = $("vsTemporalProgress");
    if (n) n.textContent = text;
    if (bar && percent != null) bar.style.width = clamp(percent, 0, 100) + "%";
  }

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open("videostudio-local", 3);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains("assets")) db.createObjectStore("assets", {keyPath: "key"});
        if (!db.objectStoreNames.contains("renders")) db.createObjectStore("renders", {keyPath: "key"});
        if (!db.objectStoreNames.contains("thumbs")) db.createObjectStore("thumbs", {keyPath: "key"});
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function idbGet(store, key) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readonly");
      const req = tx.objectStore(store).get(key);
      req.onsuccess = () => { db.close(); resolve(req.result || null); };
      req.onerror = () => { db.close(); reject(req.error); };
    });
  }

  async function idbPut(store, value) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, "readwrite");
      tx.objectStore(store).put(value);
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    });
  }

  function projectTarget(options = {}) {
    // Owner calls use the current selection once; queued calls supply both IDs.
    if (options.remote === true && (options.projectId === undefined || options.deviceId === undefined))
      throw new Error("Queued rendering requires its captured project and device");
    const pid = options.projectId === undefined ? projectId() : options.projectId;
    const did = options.deviceId === undefined ? deviceId() : options.deviceId;
    if (typeof pid !== "string" || !pid.trim()) throw new Error("Select a project first");
    if (typeof did !== "string" || !did.trim()) throw new Error("Select a paired device first");
    return {projectId: pid, deviceId: did};
  }

  async function getProject(options = {}) {
    const target = projectTarget(options);
    const data = await api("/api/projects?deviceId=" + encodeURIComponent(target.deviceId));
    const project = (data.projects || []).find(x => x.id === target.projectId);
    if (!project) throw new Error("Project not found");
    if (project.deviceId && project.deviceId !== target.deviceId) throw new Error("Project belongs to another device");
    project.deviceId = target.deviceId;
    project.assets = project.assets || [];
    project.timeline = project.timeline || [];
    project.settings = project.settings || {};
    project.generation = project.generation || {};
    return project;
  }

  async function patchProject(project, patch) {
    return (await api("/api/projects/" + encodeURIComponent(project.id), {
      method: "POST",
      body: JSON.stringify({deviceId: project.deviceId, expectedRevision: project.revision ?? 0, patch})
    })).project;
  }

  function canonicalMetadata(value, depth = 0) {
    if (depth > 32) throw new Error("Registration acknowledgement exceeds bounded metadata depth");
    return Array.isArray(value) ? value.map(item => canonicalMetadata(item, depth + 1)) : value && typeof value === "object"
      ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonicalMetadata(value[key], depth + 1)])) : value;
  }
  const sameMetadata = (left, right) => JSON.stringify(canonicalMetadata(left)) === JSON.stringify(canonicalMetadata(right));

  async function appendGenerated(project, assets, timeline, generation) {
    try {
      const registered = await api("/api/projects/" + encodeURIComponent(project.id) + "/append-generated", {
        method: "POST", body: JSON.stringify({deviceId: project.deviceId, assets, timeline, generation})
      });
      if (registered.ok !== true || registered.project?.id !== project.id || registered.project?.deviceId !== project.deviceId
        || !Array.isArray(registered.project.assets) || !Array.isArray(registered.project.timeline)
        || assets.some(asset => !registered.project.assets.some(saved => saved.id === asset.id && sameMetadata(saved, asset)))
        || timeline.some(clip => !registered.project.timeline.some(saved => saved.id === clip.id && sameMetadata(saved, clip))))
        throw new Error("Registration acknowledgement does not identify the generated media in the captured project");
      return {ok: true};
    } catch (error) {
      const registrationState = error.status === 409 ? "conflict" : error.status >= 400 && error.status < 500 ? "rejected" : "unacknowledged";
      return {ok: false, executionState: registrationState === "unacknowledged" ? "generated_registration_unconfirmed" : "generated_registration_rejected", registrationState, projectId: project.id, deviceId: project.deviceId,
        assets, timeline, generation, localBytesPreserved: true, mayHaveSideEffects: true, rerun: false,
        error: "Generated video bytes are retained locally; project registration " + registrationState + ": " + String(error.message || error).slice(0, 4096)};
    }
  }

  async function getAssetBlob(project, asset) {
    const row = await idbGet("assets", assetKey(project.id, asset.id));
    if (row && row.blob) return row.blob;
    if (window.VideoStudioCloud && typeof window.VideoStudioCloud.restoreAssetBlob === "function") {
      const restored = await window.VideoStudioCloud.restoreAssetBlob(project.id, asset.id, {deviceId: project.deviceId}).catch(() => null);
      if (restored && restored.blob) return restored.blob;
    }
    throw new Error("Local bytes are missing for " + asset.name + ". Restore the project from Drive first.");
  }

  async function loadImage(project, asset) {
    const blob = await getAssetBlob(project, asset);
    const url = URL.createObjectURL(blob);
    const image = new Image();
    image.decoding = "async";
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = () => reject(new Error("Could not decode " + asset.name));
      image.src = url;
    });
    return {asset, blob, url, image};
  }

  function dimensions(aspect, quality) {
    const base = quality === "1080p" ? 1080 : 720;
    if (aspect === "16:9") return [Math.round(base * 16 / 9), base];
    if (aspect === "1:1") return [base, base];
    if (aspect === "4:5") return [base, Math.round(base * 5 / 4)];
    return [base, Math.round(base * 16 / 9)];
  }

  function drawCover(ctx, source, w, h) {
    const sw = source.naturalWidth || source.videoWidth || source.width || w;
    const sh = source.naturalHeight || source.videoHeight || source.height || h;
    const fit = Math.max(w / sw, h / sh);
    const dw = sw * fit, dh = sh * fit;
    ctx.drawImage(source, (w - dw) / 2, (h - dh) / 2, dw, dh);
  }

  function makeCoverCanvas(source, w, h) {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    const x = c.getContext("2d", {alpha: false});
    x.fillStyle = "#000";
    x.fillRect(0, 0, w, h);
    drawCover(x, source, w, h);
    return c;
  }

  async function loadOrt() {
    if (state.ort) return state.ort;
    if (!window.ort) {
      await new Promise((resolve, reject) => {
        const old = document.querySelector('script[data-vs-temporal-ort="1"]');
        if (old) {
          if (window.ort) return resolve();
          old.addEventListener("load", resolve, {once: true});
          old.addEventListener("error", reject, {once: true});
          return;
        }
        const s = document.createElement("script");
        s.src = ORT_SCRIPT;
        s.async = true;
        s.dataset.vsTemporalOrt = "1";
        s.onload = resolve;
        s.onerror = () => reject(new Error("Could not load ONNX Runtime Web"));
        document.head.appendChild(s);
      });
    }
    if (!window.ort) throw new Error("ONNX Runtime Web did not initialize");
    state.ort = window.ort;
    state.ort.env.wasm.wasmPaths = ORT_DIST;
    state.ort.env.wasm.numThreads = 1;
    state.ort.env.wasm.simd = true;
    return state.ort;
  }

  async function probe() {
    const result = {
      webgpu: !!navigator.gpu,
      recommended: false,
      deviceMemoryGb: navigator.deviceMemory || null,
      model: "RAFT 360x480 ONNX optical flow",
      runtimeVersion: TEMPORAL_RUNTIME_VERSION
    };
    if (!navigator.gpu) {
      result.reason = "WebGPU is unavailable. WASM fallback may work but will be much slower.";
      state.lastProbe = result;
      return result;
    }
    try {
      const adapter = await navigator.gpu.requestAdapter({powerPreference: "high-performance"});
      result.adapter = !!adapter;
      result.shaderF16 = !!adapter && adapter.features.has("shader-f16");
      result.recommended = !!adapter;
      result.reason = adapter ? "WebGPU adapter ready for local neural optical flow." : "No WebGPU adapter was returned.";
    } catch (error) {
      result.reason = error.message;
    }
    state.lastProbe = result;
    return result;
  }

  async function fetchModelBytes(url) {
    const cache = await caches.open(state.cacheName);
    let response = await cache.match(url);
    if (!response) {
      setStatus("Downloading neural motion model…", 2);
      response = await fetch(url, {cache: "no-store"});
      if (!response.ok) throw new Error("Could not download RAFT model (" + response.status + ")");
      await cache.put(url, response.clone());
    }
    return await response.arrayBuffer();
  }

  async function ensureSession() {
    if (state.session) return state.session;
    const ort = await loadOrt();
    const files = [state.modelFile, ...MODEL_FILES.filter(x => x !== state.modelFile)];
    const downloaded = new Map();
    let lastError = null;

    // Prefer every available model on WebGPU before accepting a CPU/WASM
    // fallback. The int8 model is smaller, while fp32 can have wider WebGPU
    // operator support depending on the browser/GPU driver.
    for (const file of files) {
      const url = state.modelBase.replace(/\/+$/, "") + "/" + file;
      try {
        const bytes = await fetchModelBytes(url);
        downloaded.set(file, bytes);
        setStatus("Loading " + file.replace("optical_flow_estimation_raft_2023aug_", "RAFT ") + " on WebGPU…", 5);
        state.session = await ort.InferenceSession.create(bytes, {
          executionProviders:["webgpu"],
          graphOptimizationLevel: "all",
          enableCpuMemArena: false,
          enableMemPattern: false
        });
        state.backend = "webgpu";
        state.modelFile = file;
        localStorage.setItem("vs-temporal-model-file", file);
        return state.session;
      } catch (error) {
        console.warn("RAFT WebGPU candidate failed", file, error);
        lastError = error;
        state.session = null;
      }
    }

    for (const file of files) {
      try {
        const url = state.modelBase.replace(/\/+$/, "") + "/" + file;
        const bytes = downloaded.get(file) || await fetchModelBytes(url);
        setStatus("WebGPU RAFT unavailable; loading WASM fallback…", 5);
        state.session = await ort.InferenceSession.create(bytes, {
          executionProviders:["wasm"],
          graphOptimizationLevel: "all"
        });
        state.backend = "wasm";
        state.modelFile = file;
        localStorage.setItem("vs-temporal-model-file", file);
        return state.session;
      } catch (error) {
        lastError = error;
        state.session = null;
      }
    }
    throw lastError || new Error("Could not load a RAFT temporal model");
  }

  function imageTensor(image) {
    const c = document.createElement("canvas");
    c.width = FLOW_W;
    c.height = FLOW_H;
    const x = c.getContext("2d", {willReadFrequently: true});
    x.drawImage(image, 0, 0, FLOW_W, FLOW_H);
    const rgba = x.getImageData(0, 0, FLOW_W, FLOW_H).data;
    const plane = FLOW_W * FLOW_H;
    const chw = new Float32Array(plane * 3);
    for (let i = 0, p = 0; i < rgba.length; i += 4, p++) {
      chw[p] = rgba[i];
      chw[plane + p] = rgba[i + 1];
      chw[plane * 2 + p] = rgba[i + 2];
    }
    return new state.ort.Tensor("float32", chw, [1, 3, FLOW_H, FLOW_W]);
  }

  function normalizeFlowTensor(tensor) {
    if (!tensor || !tensor.data || !tensor.dims) throw new Error("RAFT returned no optical flow tensor");
    const d = tensor.dims.map(Number);
    const data = tensor.data instanceof Float32Array ? tensor.data : Float32Array.from(tensor.data);
    if (d.length === 4 && d[0] === 1 && d[1] === 2) {
      return {data, width: d[3], height: d[2], layout: "nchw"};
    }
    if (d.length === 4 && d[0] === 1 && d[3] === 2) {
      const h = d[1], w = d[2], out = new Float32Array(w * h * 2);
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const src = (y * w + x) * 2, dst = y * w + x;
        out[dst] = data[src];
        out[w * h + dst] = data[src + 1];
      }
      return {data: out, width: w, height: h, layout: "nchw"};
    }
    throw new Error("Unexpected RAFT flow shape: " + JSON.stringify(d));
  }

  async function estimateFlow(a, b) {
    const session = await ensureSession();
    const inputNames = session.inputNames;
    if (!inputNames || inputNames.length < 2) throw new Error("RAFT model does not expose two image inputs");
    const feeds = {};
    feeds[inputNames[0]] = imageTensor(a);
    feeds[inputNames[1]] = imageTensor(b);
    const result = await session.run(feeds);
    const outputNames = session.outputNames || Object.keys(result);
    if (!outputNames.length) throw new Error("RAFT model returned no outputs");
    const preferred = outputNames[outputNames.length - 1];
    return normalizeFlowTensor(result[preferred] || result[Object.keys(result).at(-1)]);
  }

  function sampleFlow(flow, u, v) {
    const x = clamp(Math.round(u * (flow.width - 1)), 0, flow.width - 1);
    const y = clamp(Math.round(v * (flow.height - 1)), 0, flow.height - 1);
    const i = y * flow.width + x;
    const plane = flow.width * flow.height;
    return [Number(flow.data[i] || 0), Number(flow.data[plane + i] || 0)];
  }

  function drawFlowWarp(ctx, source, flow, progress, w, h, strength, alpha, direction) {
    const cols = w >= 1000 ? 22 : 18;
    const rows = Math.max(18, Math.round(cols * h / w));
    const cellW = w / cols, cellH = h / rows;
    const scaleX = w / flow.width, scaleY = h / flow.height;
    ctx.save();
    ctx.globalAlpha = clamp(alpha, 0, 1);
    for (let gy = 0; gy < rows; gy++) {
      const v = (gy + .5) / rows;
      for (let gx = 0; gx < cols; gx++) {
        const u = (gx + .5) / cols;
        const [fx, fy] = sampleFlow(flow, u, v);
        const amount = direction === "forward" ? progress : (1 - progress);
        const dx = fx * scaleX * amount * strength;
        const dy = fy * scaleY * amount * strength;
        const sx = gx * cellW, sy = gy * cellH;
        const pad = 1.25;
        ctx.drawImage(
          source,
          Math.max(0, sx - pad), Math.max(0, sy - pad),
          Math.min(w - sx + pad, cellW + pad * 2), Math.min(h - sy + pad, cellH + pad * 2),
          sx + dx - pad, sy + dy - pad,
          cellW + pad * 2, cellH + pad * 2
        );
      }
    }
    ctx.restore();
  }

  function drawInterpolated(ctx, aCanvas, bCanvas, flowAB, flowBA, t, w, h, strength) {
    const e = ease(clamp(t, 0, 1));
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, w, h);
    drawFlowWarp(ctx, aCanvas, flowAB, e, w, h, strength, 1 - e * .72, "forward");
    drawFlowWarp(ctx, bCanvas, flowBA, e, w, h, strength, e, "backward");

    // A tiny camera drift prevents dead-looking borders when motion is small.
    const driftX = Math.sin(e * Math.PI) * w * .0025 * strength;
    const driftY = Math.sin(e * Math.PI * 2) * h * .0015 * strength;
    ctx.save();
    ctx.globalAlpha = .08;
    ctx.globalCompositeOperation = "screen";
    ctx.drawImage(e < .5 ? aCanvas : bCanvas, driftX, driftY, w, h);
    ctx.restore();
  }

  function bestRecorder(stream, quality) {
    const candidates = [
      quality === "1080p" ? "video/mp4;codecs=avc1.42E01E" : "video/mp4;codecs=avc1.42E01E",
      "video/webm;codecs=vp9",
      "video/webm;codecs=vp8",
      "video/webm"
    ];
    const mimeType = candidates.find(x => window.MediaRecorder.isTypeSupported && window.MediaRecorder.isTypeSupported(x)) || "";
    return new MediaRecorder(stream, mimeType ? {mimeType, videoBitsPerSecond: quality === "1080p" ? 9000000 : 5500000} : undefined);
  }

  async function registerGenerated(project, blob, duration, anchorAssetIds, metadata) {
    const id = crypto.randomUUID();
    const ext = blob.type.includes("mp4") ? ".mp4" : ".webm";
    const asset = {
      id,
      name: "Neural-Temporal-" + new Date().toISOString().replace(/[:.]/g, "-") + ext,
      type: blob.type || "video/webm",
      size: blob.size,
      duration,
      kind: "video",
      generated: true,
      role:"neural_temporal_video",
      importedAt: new Date().toISOString(),
      temporal: {
        engine: "raft-onnx-webgpu-flow-mesh-v1",
        runtimeVersion: TEMPORAL_RUNTIME_VERSION,
        backend: state.backend,
        modelFile: state.modelFile,
        anchorAssetIds,
        ...metadata
      }
    };
    await idbPut("assets", {key: assetKey(project.id, id), blob, meta: asset});
    const timeline = [{id: crypto.randomUUID(), assetId: id, inPoint: 0, outPoint: duration, speed: 1, title: "Neural Temporal Motion"}];
    const generation = {
      lastAssetId: id,
      lastTemporalAssetId: id,
      lastMode: "neural_temporal_motion",
      lastTemporalEngine: asset.temporal.engine,
      generatedAt: new Date().toISOString()
    };
    if (window.VideoStudioCloud && localStorage.getItem("vs-drive-auto-upload") === "1") {
      try { await window.VideoStudioCloud.uploadSingleAsset(project, asset); await idbPut("assets", {key: assetKey(project.id, id), blob, meta: asset}); } catch {}
    }
    const registered = await appendGenerated(project, [asset], timeline, generation);
    if (!registered.ok) return registered;
    if (window.VideoStudioCinematic && window.VideoStudioCinematic.refreshSelectors) {
      window.VideoStudioCinematic.refreshSelectors().catch(() => {});
    }
    return {ok: true, asset};
  }

  async function maybeGenerateAnchors(project, options) {
    const prompts = Array.isArray(options.prompts)
      ? options.prompts.map(x => String(x || "").trim()).filter(Boolean)
      : String(options.prompts || "").split(/\n+/).map(x => x.trim()).filter(Boolean);
    if (prompts.length < 2) return null;
    if (!window.VideoStudioNeural || typeof window.VideoStudioNeural.generateKeyframes !== "function") {
      throw new Error("Neural Keyframe runtime is not ready");
    }
    setStatus("Generating temporal anchor frames…", 1);
    const result = await window.VideoStudioNeural.generateKeyframes({projectId: project.id, deviceId: project.deviceId, prompts: prompts.slice(0, 9)});
    if (result && result.ok === false) {
      const error = new Error(result.error || "Generated anchors could not be registered");
      error.retainedGeneration = result; throw error;
    }
    return result && result.assets ? result.assets.map(a => a.id) : null;
  }

  async function resolveAnchors(project, options) {
    let ids = Array.isArray(options.anchorAssetIds) ? options.anchorAssetIds.filter(Boolean) : [];
    if (ids.length < 2 && options.prompts) {
      const generatedIds = await maybeGenerateAnchors(project, options);
      if (generatedIds && generatedIds.length >= 2) {
        project = await getProject({projectId: project.id, deviceId: project.deviceId});
        ids = generatedIds;
      }
    }
    if (ids.length < 2 && Array.isArray(project.generation.lastNeuralWorldIds)) ids = project.generation.lastNeuralWorldIds.slice();
    if (ids.length < 2) {
      ids = project.assets.filter(a => a.kind === "image" && (a.role === "neural_world_keyframe" || a.generated)).map(a => a.id).slice(-9);
    }
    const assets = ids.map(id => project.assets.find(a => a.id === id)).filter(Boolean).filter(a => a.kind === "image");
    if (assets.length < 2) throw new Error("Select or generate at least two image anchor frames");
    return {project, assets: assets.slice(0, 9)};
  }

  async function renderTemporalMotion(options = {}) {
    if (state.busy) throw new Error("Neural temporal motion is already running");
    // Anchor generation and later refetches retain this request's target.
    const target = projectTarget(options);
    state.busy = true;
    let loaded = [];
    try {
      if (options.modelBase) {
        state.modelBase = String(options.modelBase).replace(/\/+$/, "");
        localStorage.setItem("vs-temporal-model-base", state.modelBase);
        if (state.session && typeof state.session.release === "function") { try { state.session.release(); } catch {} }
        state.session = null;
      }
      let project = await getProject(target);
      const resolved = await resolveAnchors(project, options);
      project = resolved.project;
      const anchors = resolved.assets;
      state.selectedAnchorIds = anchors.map(a => a.id);

      const fps = clamp(options.fps || 24, 12, 30);
      const duration = clamp(options.duration || Math.max(4, (anchors.length - 1) * 2.5), 2, 60);
      const strength = clamp(options.motionStrength == null ? 1 : options.motionStrength, .2, 1.6);
      const aspect = options.aspect || project.settings.aspect || "9:16";
      const quality = options.quality || project.settings.quality || "720p";
      const [w, h] = dimensions(aspect, quality);

      const hardware = await probe();
      setStatus("Loading " + anchors.length + " temporal anchors…", 3);
      for (const asset of anchors) loaded.push(await loadImage(project, asset));

      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext("2d", {alpha: false, desynchronized: true});
      if (!canvas.captureStream || !window.MediaRecorder) throw new Error("This browser cannot record the temporal render");

      const coverFrames = loaded.map(x => makeCoverCanvas(x.image, w, h));
      const pairFlows = [];
      for (let i = 0; i < loaded.length - 1; i++) {
        const startPct = 6 + (i / (loaded.length - 1)) * 36;
        setStatus("Neural motion analysis " + (i + 1) + "/" + (loaded.length - 1) + "…", startPct);
        const flowAB = await estimateFlow(loaded[i].image, loaded[i + 1].image);
        const flowBA = await estimateFlow(loaded[i + 1].image, loaded[i].image);
        pairFlows.push({flowAB, flowBA});
        await sleep(20);
      }

      const stream = canvas.captureStream(fps);
      const recorder = bestRecorder(stream, quality);
      const chunks = [];
      recorder.ondataavailable = e => { if (e.data && e.data.size) chunks.push(e.data); };
      const stopped = new Promise(resolve => recorder.onstop = resolve);
      recorder.start(500);

      const totalFrames = Math.max(2, Math.round(duration * fps));
      const segmentCount = loaded.length - 1;
      const frameMs = 1000 / fps;
      for (let frame = 0; frame < totalFrames; frame++) {
        const globalT = frame / Math.max(1, totalFrames - 1);
        const scaled = globalT * segmentCount;
        const pair = Math.min(segmentCount - 1, Math.floor(scaled));
        const localT = pair === segmentCount - 1 && scaled >= segmentCount ? 1 : scaled - pair;
        drawInterpolated(
          ctx,
          coverFrames[pair],
          coverFrames[pair + 1],
          pairFlows[pair].flowAB,
          pairFlows[pair].flowBA,
          localT,
          w,
          h,
          strength
        );
        const pct = 44 + (frame / totalFrames) * 50;
        if (frame % Math.max(1, Math.round(fps / 3)) === 0) {
          setStatus("Rendering neural temporal motion… " + Math.round(pct) + "%", pct);
        }
        await sleep(frameMs);
      }

      recorder.stop();
      await stopped;
      stream.getTracks().forEach(t => t.stop());
      if (!chunks.length) throw new Error("Temporal recorder produced no video data");
      const blob = new Blob(chunks, {type: chunks[0].type || recorder.mimeType || "video/webm"});
      const registered = await registerGenerated(project, blob, duration, anchors.map(a => a.id), {
        fps, aspect, quality, motionStrength: strength,
        neuralOpticalFlow: true,
        bidirectionalFlow: true,
        flowResolution: FLOW_W + "x" + FLOW_H
      });
      if (!registered.ok) {
        setStatus(registered.error, null); log("Temporal video retained", registered.error);
        toast("Rendered video retained locally; project registration was not acknowledged"); return registered;
      }
      const asset = registered.asset;
      setStatus("Temporal video ready • " + state.backend.toUpperCase() + " RAFT motion", 100);
      log("Neural temporal video ready", asset.name + " • " + anchors.length + " anchors • " + state.backend);
      toast("Neural temporal motion added to Media Bin");
      return {
        ok: true,
        temporal: true,
        runtimeVersion: TEMPORAL_RUNTIME_VERSION,
        engine: "raft-onnx-webgpu-flow-mesh-v1",
        backend: state.backend,
        modelFile: state.modelFile,
        asset,
        anchorAssetIds: anchors.map(a => a.id),
        hardware,
        note: "Dense bidirectional RAFT optical flow drives frame interpolation. This is a neural motion/interpolation engine, not full diffusion video."
      };
    } catch (error) {
      if (error.retainedGeneration) {
        setStatus(error.message, null);
        return {...error.retainedGeneration, temporal: false, failedStage: "anchor_registration"};
      }
      throw error;
    } finally {
      for (const item of loaded) if (item && item.url) URL.revokeObjectURL(item.url);
      state.busy = false;
    }
  }

  async function clearModelCache() {
    if (state.session && typeof state.session.release === "function") {
      try { state.session.release(); } catch {}
    }
    state.session = null;
    await caches.delete(state.cacheName);
    setStatus("Temporal model cache cleared.", 0);
    return {ok: true};
  }

  async function refreshSelectors() {
    const sel = $("vsTemporalAnchors");
    if (!sel) return;
    try {
      const project = await getProject();
      const old = new Set([...sel.selectedOptions].map(o => o.value));
      sel.innerHTML = "";
      project.assets.filter(a => a.kind === "image").forEach(asset => {
        const o = document.createElement("option");
        o.value = asset.id;
        o.textContent = (asset.role === "neural_world_keyframe" ? "✦ " : "") + asset.name;
        o.selected = old.has(asset.id) || state.selectedAnchorIds.includes(asset.id);
        sel.appendChild(o);
      });
      if (![...sel.selectedOptions].length && Array.isArray(project.generation.lastNeuralWorldIds)) {
        const ids = new Set(project.generation.lastNeuralWorldIds);
        for (const o of sel.options) o.selected = ids.has(o.value);
      }
    } catch {}
  }

  function inject() {
    if ($("vsTemporalLab")) return;
    const host = $("vsRuntimeRoot") || $("aiSection");
    if (!host) return;

    const style = document.createElement("style");
    style.textContent =
      ".vsTemporal{margin-top:12px;border:1px solid rgba(45,212,191,.28);background:linear-gradient(145deg,rgba(6,45,43,.24),rgba(9,12,20,.98));border-radius:16px;padding:13px}.vsTemporal h4{margin:0}.vsTemporalGrid{display:grid;grid-template-columns:1.2fr .8fr;gap:10px}.vsTemporal select,.vsTemporal input,.vsTemporal textarea{width:100%;box-sizing:border-box;border:1px solid #33445a;background:#080c14;color:#eef4ff;border-radius:10px;padding:8px}.vsTemporal textarea{min-height:80px}.vsTemporal label{display:block;margin-top:9px;font-size:9px;color:#9fb6b3;font-weight:800;letter-spacing:.06em}.vsTemporal .row{display:flex;gap:7px;flex-wrap:wrap;margin-top:9px}.vsTemporal button{border:1px solid #36515a;background:#111c26;color:#f2f6ff;border-radius:10px;padding:8px 10px;font-size:10px;font-weight:800}.vsTemporal button.primary{background:linear-gradient(135deg,#0f9f8f,#2563eb);border-color:transparent}.vsTemporal .progress{height:6px;background:#1b2433;border-radius:999px;overflow:hidden;margin-top:9px}.vsTemporal .progress i{display:block;height:100%;width:0;background:linear-gradient(90deg,#2dd4bf,#60a5fa)}.vsTemporal .muted{font-size:10px;color:#97a3b8;margin-top:5px}@media(max-width:820px){.vsTemporalGrid{grid-template-columns:1fr}}";
    document.head.appendChild(style);

    const card = document.createElement("div");
    card.id = "vsTemporalLab";
    card.className = "vsTemporal";
    card.innerHTML =
      '<h4>Neural Temporal Motion <span class="vsBadge">RAFT · WEBGPU</span></h4>' +
      '<div class="muted">Turns multiple neural or imported still frames into a moving video using real bidirectional neural optical flow. For best continuity, use 3–6 anchors describing the same world at progressive moments. The resulting video can be selected directly inside Cinematic Worlds.</div>' +
      '<div class="vsTemporalGrid"><div><label>ANCHOR FRAMES · multi-select · 2–9</label><select id="vsTemporalAnchors" multiple size="6"></select>' +
      '<label>OR GENERATE ANCHORS FROM PROMPTS · one progressive moment per line</label><textarea id="vsTemporalPrompts" placeholder="Same divine mountain realm, warrior standing still, wind beginning to move cloth\\nSame realm and character, cloth flowing left, clouds advancing\\nSame realm and character, warrior turns slightly, birds cross distant sky"></textarea></div>' +
      '<div><label>DURATION SECONDS</label><input id="vsTemporalDuration" type="number" min="2" max="60" value="8">' +
      '<label>FPS</label><select id="vsTemporalFps"><option>24</option><option>30</option><option>18</option><option>15</option></select>' +
      '<label>MOTION STRENGTH</label><input id="vsTemporalStrength" type="range" min=".2" max="1.6" step=".05" value="1">' +
      '<label>MODEL BASE URL</label><input id="vsTemporalModelBase" value="' + state.modelBase.replaceAll('"', "&quot;") + '">' +
      '<div class="row"><button id="vsTemporalProbe">Probe GPU</button><button id="vsTemporalClear">Clear flow model</button></div></div></div>' +
      '<div class="row"><button id="vsTemporalRender" class="primary">Generate moving neural world</button><button id="vsTemporalRefresh">Refresh anchors</button></div>' +
      '<div class="progress"><i id="vsTemporalProgress"></i></div><div id="vsTemporalStatus" class="muted">Ready. First use downloads the RAFT ONNX optical-flow model and caches it locally.</div>';
    host.appendChild(card);

    $("vsTemporalProbe").onclick = async () => {
      const r = await probe();
      setStatus((r.recommended ? "Ready" : "Fallback") + " • " + r.reason, r.recommended ? 1 : 0);
    };
    $("vsTemporalClear").onclick = () => clearModelCache().catch(e => toast(e.message));
    $("vsTemporalRefresh").onclick = () => refreshSelectors();
    $("vsTemporalRender").onclick = async () => {
      try {
        state.modelBase = $("vsTemporalModelBase").value.trim() || DEFAULT_MODEL_BASE;
        localStorage.setItem("vs-temporal-model-base", state.modelBase);
        const project = await getProject();
        const anchorAssetIds = [...$("vsTemporalAnchors").selectedOptions].map(o => o.value);
        const prompts = $("vsTemporalPrompts").value.split(/\n+/).map(x => x.trim()).filter(Boolean);
        const result = await renderTemporalMotion({
          projectId: project.id,
          deviceId: project.deviceId,
          anchorAssetIds,
          prompts,
          duration: Number($("vsTemporalDuration").value || 8),
          fps: Number($("vsTemporalFps").value || 24),
          motionStrength: Number($("vsTemporalStrength").value || 1),
          aspect: project.settings.aspect || "9:16",
          quality: project.settings.quality || "720p"
        });
        if (result.ok !== false) setTimeout(() => location.reload(), 900);
      } catch (error) {
        setStatus(error.message, null);
        toast(error.message);
      }
    };
    refreshSelectors();
    setInterval(refreshSelectors, 15000);
  }

  window.VideoStudioTemporal = {
    probe,
    renderTemporalMotion,
    clearModelCache,
    refreshSelectors,
    state
  };

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", inject);
  else inject();
})();
`;

export default STUDIO_TEMPORAL_JS;
