/**
 * Sensei's brain in the main process: the language models it can think with (a local one through Ollama, or the
 * person's own key for a cloud one), its eyes on the internet (search and read pages, with guards), and the local
 * Ollama runtime it can install and run for the person. Keys never reach the page: they are kept encrypted here and
 * added to requests on the way out.
 */
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const dns = require("node:dns/promises");
const net = require("node:net");
const crypto = require("node:crypto");
const { spawn, execFile } = require("node:child_process");

const DEFAULTS = {
  provider: "builtin", // builtin | ollama | openai | anthropic | gemini
  model: "", // chat model (empty = the provider's default)
  visionModel: "", // model that looks at frames (empty = model)
  url: "", // base URL for ollama / an OpenAI-compatible server
  web: true, // may search and read the internet
  edits: false, // may change the film's code when asked to fix
  temperature: 0.3,
};
const MODEL_DEFAULT = { ollama: "qwen3:8b", openai: "gpt-4o-mini", anthropic: "claude-sonnet-4-5", gemini: "gemini-2.5-flash" };
const VISION_DEFAULT = { ollama: "qwen2.5vl:7b", openai: "gpt-4o-mini", anthropic: "claude-sonnet-4-5", gemini: "gemini-2.5-flash" };
const OLLAMA_URL = "http://127.0.0.1:11434";

function setupSensei(o) {
  const { handle, userData, send, safeStorage } = o;
  const FILE = path.join(userData, "sensei.json"), KEYS = path.join(userData, "sensei.keys");
  let settings = { ...DEFAULTS };
  try { settings = { ...DEFAULTS, ...JSON.parse(fs.readFileSync(FILE, "utf8")) }; } catch { /* first run */ }
  const save = () => fs.writeFileSync(FILE, JSON.stringify(settings, null, 1));
  // ---- keys: one per provider, encrypted by the system keyring when there is one
  const readKeys = () => { try { const raw = fs.readFileSync(KEYS); const txt = safeStorage && safeStorage.isEncryptionAvailable() && raw[0] !== 0x7b ? safeStorage.decryptString(raw) : raw.toString("utf8"); return JSON.parse(txt); } catch { return {}; } };
  const writeKeys = (k) => { const txt = JSON.stringify(k); const data = safeStorage && safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(txt) : Buffer.from(txt); fs.writeFileSync(KEYS, data, { mode: 0o600 }); };
  const keyOf = (p) => readKeys()[p] || "";
  const pub = () => { const k = readKeys(); return { ...settings, keys: Object.fromEntries(Object.keys(k).filter((x) => k[x]).map((x) => [x, "••••" + String(k[x]).slice(-4)])), encrypted: !!(safeStorage && safeStorage.isEncryptionAvailable()), modelDefault: MODEL_DEFAULT, visionDefault: VISION_DEFAULT }; };
  handle("sensei.settings", async () => pub());
  handle("sensei.setSettings", async (patch = {}) => {
    const allowed = ["provider", "model", "visionModel", "url", "web", "edits", "temperature"];
    for (const k of allowed) if (k in patch) settings[k] = patch[k];
    if (!["builtin", "ollama", "openai", "anthropic", "gemini"].includes(settings.provider)) settings.provider = "builtin";
    if (patch.key !== undefined) { const k = readKeys(); const p = patch.keyFor || settings.provider; if (patch.key) k[p] = String(patch.key).trim(); else delete k[p]; writeKeys(k); }
    save();
    return pub();
  });

  // ---- talking to a model. req: { system, messages: [{ role: user|assistant|tool, content, images?: [base64 jpeg], name?, toolCalls? }], tools?: [{ name, description, parameters }], vision? }
  async function llm(req) {
    const p = req.provider || settings.provider;
    if (p === "builtin") throw new Error("Sensei is using its built-in brain (no language model). Pick Ollama or a cloud model in Sensei → Brain.");
    const model = (req.vision ? settings.visionModel || settings.model : settings.model) || (req.vision ? VISION_DEFAULT[p] : MODEL_DEFAULT[p]);
    const t0 = Date.now();
    if (p === "ollama") {
      const url = (settings.url || OLLAMA_URL).replace(/\/$/, "");
      const body = { model, stream: false, options: { temperature: settings.temperature }, messages: [...(req.system ? [{ role: "system", content: req.system }] : []), ...req.messages.map((m) => ({ role: m.role, content: m.content || "", ...(m.images?.length ? { images: m.images } : {}), ...(m.toolCalls ? { tool_calls: m.toolCalls.map((c) => ({ function: { name: c.name, arguments: c.args } })) } : {}), ...(m.role === "tool" ? { tool_name: m.name } : {}) }))], ...(req.tools?.length ? { tools: req.tools.map((t) => ({ type: "function", function: t })) } : {}) };
      const r = await post(`${url}/api/chat`, body, {}, 300000);
      const msg = r.message || {};
      return { text: msg.content || "", toolCalls: (msg.tool_calls || []).map((c) => ({ name: c.function?.name, args: typeof c.function?.arguments === "string" ? safeJson(c.function.arguments) : c.function?.arguments || {} })), model, ms: Date.now() - t0 };
    }
    if (p === "openai") {
      const key = keyOf("openai"), url = (settings.url || "https://api.openai.com/v1").replace(/\/$/, "");
      if (!key && !settings.url) throw new Error("No key for the OpenAI-compatible model (Sensei → Brain).");
      const messages = [...(req.system ? [{ role: "system", content: req.system }] : [])];
      for (const m of req.messages) {
        if (m.role === "tool") messages.push({ role: "tool", tool_call_id: m.callId || m.name, content: m.content || "" });
        else if (m.toolCalls) messages.push({ role: "assistant", content: m.content || null, tool_calls: m.toolCalls.map((c) => ({ id: c.id || c.name, type: "function", function: { name: c.name, arguments: JSON.stringify(c.args || {}) } })) });
        else if (m.images?.length) messages.push({ role: m.role, content: [{ type: "text", text: m.content || "" }, ...m.images.map((b) => ({ type: "image_url", image_url: { url: `data:image/jpeg;base64,${b}` } }))] });
        else messages.push({ role: m.role, content: m.content || "" });
      }
      const r = await post(`${url}/chat/completions`, { model, messages, temperature: settings.temperature, ...(req.tools?.length ? { tools: req.tools.map((t) => ({ type: "function", function: t })) } : {}) }, key ? { authorization: `Bearer ${key}` } : {}, 180000);
      const msg = r.choices?.[0]?.message || {};
      return { text: msg.content || "", toolCalls: (msg.tool_calls || []).map((c) => ({ id: c.id, name: c.function?.name, args: safeJson(c.function?.arguments || "{}") })), model, ms: Date.now() - t0 };
    }
    if (p === "anthropic") {
      const key = keyOf("anthropic"); if (!key) throw new Error("No Anthropic API key (Sensei → Brain).");
      const messages = [];
      for (const m of req.messages) {
        if (m.role === "tool") messages.push({ role: "user", content: [{ type: "tool_result", tool_use_id: m.callId || m.name, content: m.content || "" }] });
        else if (m.toolCalls) messages.push({ role: "assistant", content: [...(m.content ? [{ type: "text", text: m.content }] : []), ...m.toolCalls.map((c) => ({ type: "tool_use", id: c.id || c.name, name: c.name, input: c.args || {} }))] });
        else messages.push({ role: m.role, content: [...(m.images || []).map((b) => ({ type: "image", source: { type: "base64", media_type: "image/jpeg", data: b } })), { type: "text", text: m.content || "" }] });
      }
      const r = await post("https://api.anthropic.com/v1/messages", { model, max_tokens: 2048, system: req.system || undefined, messages, temperature: settings.temperature, ...(req.tools?.length ? { tools: req.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters })) } : {}) }, { "x-api-key": key, "anthropic-version": "2023-06-01" }, 180000);
      const parts = r.content || [];
      return { text: parts.filter((x) => x.type === "text").map((x) => x.text).join("\n"), toolCalls: parts.filter((x) => x.type === "tool_use").map((x) => ({ id: x.id, name: x.name, args: x.input || {} })), model, ms: Date.now() - t0 };
    }
    if (p === "gemini") {
      const key = keyOf("gemini"); if (!key) throw new Error("No Gemini API key (Sensei → Brain).");
      const contents = [];
      for (const m of req.messages) {
        if (m.role === "tool") contents.push({ role: "user", parts: [{ functionResponse: { name: m.name, response: { result: m.content || "" } } }] });
        else if (m.toolCalls) contents.push({ role: "model", parts: [...(m.content ? [{ text: m.content }] : []), ...m.toolCalls.map((c) => ({ functionCall: { name: c.name, args: c.args || {} } }))] });
        else contents.push({ role: m.role === "assistant" ? "model" : "user", parts: [{ text: m.content || "" }, ...(m.images || []).map((b) => ({ inlineData: { mimeType: "image/jpeg", data: b } }))] });
      }
      const r = await post(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, { contents, systemInstruction: req.system ? { parts: [{ text: req.system }] } : undefined, generationConfig: { temperature: settings.temperature }, ...(req.tools?.length ? { tools: [{ functionDeclarations: req.tools }] } : {}) }, { "x-goog-api-key": key }, 180000);
      const parts = r.candidates?.[0]?.content?.parts || [];
      return { text: parts.filter((x) => x.text).map((x) => x.text).join("\n"), toolCalls: parts.filter((x) => x.functionCall).map((x) => ({ name: x.functionCall.name, args: x.functionCall.args || {} })), model, ms: Date.now() - t0 };
    }
    throw new Error(`Unknown brain ${p}.`);
  }
  handle("sensei.llm", (req) => llm(req || {}));
  handle("sensei.test", async () => { const r = await llm({ messages: [{ role: "user", content: "Reply with the single word READY." }] }); return { ok: /ready/i.test(r.text), text: r.text.slice(0, 200), model: r.model, ms: r.ms }; });

  // ---- the internet: search and read, never anything inside the person's own network
  async function safeUrl(u) {
    let url; try { url = new URL(u); } catch { throw new Error("Not a web address."); }
    if (!/^https?:$/.test(url.protocol)) throw new Error("Only http(s) pages.");
    const host = url.hostname.replace(/^\[|\]$/g, "");
    if (/^(localhost|.*\.local|.*\.internal)$/i.test(host)) throw new Error("Pages on this computer or its network are not read.");
    const ips = net.isIP(host) ? [host] : (await dns.lookup(host, { all: true })).map((x) => x.address);
    for (const ip of ips) if (privateIp(ip)) throw new Error("Pages on this computer or its network are not read.");
    return url;
  }
  async function getText(u, max = 2_000_000, accept = "text/html,application/xhtml+xml,application/json,text/plain") {
    let url = await safeUrl(u);
    for (let hop = 0; hop < 5; hop++) {
      const ctl = new AbortController(), tm = setTimeout(() => ctl.abort(), 15000);
      try {
        const r = await fetch(url, { redirect: "manual", signal: ctl.signal, headers: { "user-agent": "Mozilla/5.0 (X11; Linux x86_64) VideoStudio-Sensei/2.5", accept, "accept-language": "en,hi;q=0.6" } });
        if (r.status >= 300 && r.status < 400 && r.headers.get("location")) { url = await safeUrl(new URL(r.headers.get("location"), url).href); continue; }
        if (!r.ok) throw new Error(`The page answered ${r.status}.`);
        const ct = r.headers.get("content-type") || "";
        if (!/text\/|json|xml/.test(ct)) throw new Error(`Not a text page (${ct.split(";")[0]}).`);
        const reader = r.body.getReader(); const chunks = []; let n = 0;
        for (;;) { const { done, value } = await reader.read(); if (done) break; n += value.length; if (n > max) { ctl.abort(); break; } chunks.push(value); }
        return { url: url.href, type: ct, text: Buffer.concat(chunks.map((c) => Buffer.from(c))).toString("utf8") };
      } finally { clearTimeout(tm); }
    }
    throw new Error("Too many redirects.");
  }
  handle("sensei.search", async (q, n = 8) => {
    if (!settings.web) throw new Error("Sensei may not use the internet (Sensei → Brain → Internet).");
    q = String(q || "").slice(0, 300); if (!q.trim()) throw new Error("Nothing to search for.");
    const out = [], seen = new Set();
    try {
      const r = await getText(`https://html.duckduckgo.com/html/?q=${encodeURIComponent(q)}`);
      for (const m of r.text.matchAll(/<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?(?:class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)<\/a>)?/g)) {
        let href = m[1].replace(/&amp;/g, "&"); const uddg = href.match(/[?&]uddg=([^&]+)/); if (uddg) href = decodeURIComponent(uddg[1]); if (href.startsWith("//")) href = "https:" + href;
        if (!/^https?:/.test(href) || seen.has(href) || /duckduckgo\.com\/y\.js/.test(href)) continue;
        seen.add(href); out.push({ title: strip(m[2]), url: href, snippet: strip(m[3] || ""), source: "web" });
        if (out.length >= n) break;
      }
    } catch (e) { out.push({ error: `web search: ${e.message}` }); }
    try {
      const r = await getText(`https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=${encodeURIComponent(q)}`);
      for (const x of JSON.parse(r.text).query?.search ?? []) { const url = `https://en.wikipedia.org/wiki/${encodeURIComponent(x.title.replace(/ /g, "_"))}`; if (!seen.has(url)) { seen.add(url); out.push({ title: x.title, url, snippet: strip(x.snippet), source: "wikipedia" }); } }
    } catch { /* wikipedia is a second source only */ }
    return out;
  });
  handle("sensei.fetch", async (u, max = 20000) => {
    if (!settings.web) throw new Error("Sensei may not use the internet (Sensei → Brain → Internet).");
    const r = await getText(String(u));
    if (/json/.test(r.type)) return { url: r.url, title: "", text: r.text.slice(0, max) };
    const title = strip((r.text.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1] || "");
    const body = r.text.replace(/<(script|style|noscript|svg|nav|footer|header|form)[\s\S]*?<\/\1>/gi, " ").replace(/<\/(p|div|li|h\d|tr|br|section|article)>/gi, "\n").replace(/<br\s*\/?>/gi, "\n");
    const text = strip(body).split("\n").map((l) => l.trim()).filter((l) => l.length > 2).join("\n").replace(/\n{3,}/g, "\n\n");
    return { url: r.url, title, text: text.slice(0, max), cut: text.length > max };
  });

  // ---- Ollama: a local brain on the person's own PC (their GPU), installed and run by VideoStudio if they want
  const OL_DIR = path.join(userData, "ollama"), OL_BIN = path.join(OL_DIR, "bin", "ollama"), OL_MODELS = path.join(OL_DIR, "models");
  let olProc = null;
  const olUrl = () => (settings.url || OLLAMA_URL).replace(/\/$/, "");
  async function olStatus() {
    const st = { running: false, version: null, models: [], managed: !!olProc, installed: fs.existsSync(OL_BIN), system: !!which("ollama"), dir: OL_DIR };
    try { const v = await get(`${olUrl()}/api/version`, 2500); st.running = true; st.version = v.version; const t = await get(`${olUrl()}/api/tags`, 5000); st.models = (t.models || []).map((m) => ({ name: m.name, size: m.size, family: m.details?.family, params: m.details?.parameter_size })); } catch { /* not running */ }
    return st;
  }
  handle("sensei.ollama", olStatus);
  handle("sensei.ollamaStart", async () => {
    const st = await olStatus(); if (st.running) return st;
    const bin = fs.existsSync(OL_BIN) ? OL_BIN : which("ollama");
    if (!bin) throw new Error("Ollama is not installed. Install it from Sensei → Brain (VideoStudio downloads the official build from GitHub and checks it).");
    fs.mkdirSync(OL_MODELS, { recursive: true });
    olProc = spawn(bin, ["serve"], { env: { ...process.env, OLLAMA_HOST: "127.0.0.1:11434", OLLAMA_MODELS: bin === OL_BIN ? OL_MODELS : process.env.OLLAMA_MODELS || "" }, stdio: "ignore", detached: false });
    olProc.on("exit", () => { olProc = null; });
    for (let i = 0; i < 40; i++) { await new Promise((r) => setTimeout(r, 500)); const s = await olStatus(); if (s.running) return s; }
    throw new Error("Ollama did not start.");
  });
  handle("sensei.ollamaStop", async () => { if (olProc) { olProc.kill(); olProc = null; } return olStatus(); });
  handle("sensei.ollamaInstall", async () => {
    if (process.platform !== "linux" || process.arch !== "x64") throw new Error("The built-in installer is for Linux x64; elsewhere install Ollama from ollama.com.");
    send("sensei:ollama", { phase: "find" });
    const rel = await get("https://api.github.com/repos/ollama/ollama/releases/latest", 20000);
    const asset = (rel.assets || []).find((a) => /^ollama-linux-amd64\.(tgz|tar\.gz|tar\.zst)$/.test(a.name));
    if (!asset) throw new Error("No Linux build in Ollama's latest release.");
    const sums = (rel.assets || []).find((a) => /sha256sum/.test(a.name));
    fs.mkdirSync(OL_DIR, { recursive: true });
    const file = path.join(OL_DIR, asset.name);
    await download(asset.browser_download_url, file, (done, total) => send("sensei:ollama", { phase: "download", done, total, name: asset.name, version: rel.tag_name }));
    if (sums) {
      const txt = (await getText(sums.browser_download_url, 200000, "text/plain")).text;
      const want = (txt.split("\n").find((l) => l.trim().endsWith(asset.name)) || "").split(/\s+/)[0];
      const have = await sha256(file);
      if (!want || want !== have) { fs.rmSync(file, { force: true }); throw new Error("The download does not match Ollama's published checksum; it was deleted."); }
    }
    send("sensei:ollama", { phase: "unpack" });
    await new Promise((res, rej) => execFile("tar", [asset.name.endsWith(".zst") ? "--zstd" : "-z", "-xf", file, "-C", OL_DIR], (e) => (e ? rej(e) : res())));
    fs.rmSync(file, { force: true });
    send("sensei:ollama", { phase: "installed", version: rel.tag_name });
    return { installed: fs.existsSync(OL_BIN), version: rel.tag_name };
  });
  handle("sensei.ollamaPull", async (model) => {
    model = String(model || "").trim(); if (!/^[\w.:/-]+$/.test(model)) throw new Error("Not a model name.");
    const r = await fetch(`${olUrl()}/api/pull`, { method: "POST", body: JSON.stringify({ name: model, stream: true }), headers: { "content-type": "application/json" } });
    if (!r.ok) throw new Error(`Ollama: ${r.status}`);
    const reader = r.body.getReader(); let buf = "", last = null;
    for (;;) { const { done, value } = await reader.read(); if (done) break; buf += Buffer.from(value).toString("utf8"); let k; while ((k = buf.indexOf("\n")) >= 0) { const line = buf.slice(0, k); buf = buf.slice(k + 1); if (!line.trim()) continue; const m = safeJson(line); last = m; send("sensei:ollama", { phase: "pull", model, status: m.status, done: m.completed, total: m.total }); if (m.error) throw new Error(m.error); } }
    return { model, status: last?.status || "done" };
  });
  return { stop: () => { if (olProc) olProc.kill(); } };
}

// ---- helpers
function safeJson(s) { try { return JSON.parse(s); } catch { return {}; } }
function strip(h) { return String(h || "").replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/[ \t]+/g, " ").trim(); }
function privateIp(ip) {
  if (net.isIPv4(ip)) { const [a, b] = ip.split(".").map(Number); return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224; }
  const x = ip.toLowerCase(); return x === "::1" || x === "::" || x.startsWith("fc") || x.startsWith("fd") || x.startsWith("fe80") || x.startsWith("::ffff:127.") || x.startsWith("::ffff:10.") || x.startsWith("::ffff:192.168.");
}
function which(cmd) { for (const d of (process.env.PATH || "").split(":")) { const f = path.join(d, cmd); try { fs.accessSync(f, fs.constants.X_OK); return f; } catch { /* next */ } } return null; }
async function post(url, body, headers = {}, ms = 120000) {
  const ctl = new AbortController(), tm = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { method: "POST", body: JSON.stringify(body), headers: { "content-type": "application/json", ...headers }, signal: ctl.signal });
    const txt = await r.text(); const j = safeJson(txt);
    if (!r.ok) throw new Error(`${new URL(url).host} answered ${r.status}: ${(j.error?.message || j.error || txt).toString().slice(0, 300)}`);
    return j;
  } catch (e) { if (e.name === "AbortError") throw new Error("The model took too long to answer."); throw e; } finally { clearTimeout(tm); }
}
async function get(url, ms = 10000) { const ctl = new AbortController(), tm = setTimeout(() => ctl.abort(), ms); try { const r = await fetch(url, { signal: ctl.signal, headers: { "user-agent": "VideoStudio-Sensei/2.5", accept: "application/json" } }); if (!r.ok) throw new Error(`${r.status}`); return await r.json(); } finally { clearTimeout(tm); } }
async function download(url, file, onProgress) {
  const r = await fetch(url, { redirect: "follow" }); if (!r.ok) throw new Error(`Download failed: ${r.status}`);
  const total = +(r.headers.get("content-length") || 0), out = fs.createWriteStream(file + ".part"); let done = 0, last = 0;
  const reader = r.body.getReader();
  for (;;) { const { done: end, value } = await reader.read(); if (end) break; done += value.length; if (!out.write(Buffer.from(value))) await new Promise((res) => out.once("drain", res)); if (Date.now() - last > 300) { last = Date.now(); onProgress(done, total); } }
  await new Promise((res) => out.end(res)); await fsp.rename(file + ".part", file); onProgress(done, total);
}
function sha256(file) { return new Promise((res, rej) => { const h = crypto.createHash("sha256"); fs.createReadStream(file).on("data", (d) => h.update(d)).on("end", () => res(h.digest("hex"))).on("error", rej); }); }

module.exports = { setupSensei, privateIp, strip };
