/**
 * The film engine of Code Studio, renewed without installing the app again.
 *
 * An installation carries the engine in resources/film. A release also carries it as ONE small file,
 * film-kit.json.gz: the engine's own text files (engine/, kit/, runner/, sensei/, templates/, API.md), gzipped JSON.
 * `updateKit` fetches the newest one into the person's own folder (<userData>/film-kit/<version>), checks it against
 * the release's checksum and sets it up; `activeKit` says which engine draws: the fetched one while it is newer than
 * the installation, else the built-in one. No installer runs and no password is asked for. What the file does not
 * carry — the canvas library, fonts and sounds, the 2D library — is borrowed from the installation by links.
 *
 * A kit names the oldest app it works with (minApp): an engine that needs something new in the app itself is refused
 * with a sentence that says the app must be installed.
 */
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const crypto = require("node:crypto");

const ASSET = "film-kit.json.gz";
const CARRIED = ["engine", "kit", "runner", "sensei", "templates"]; // folders of text the engine is made of
const CARRIED_FILES = ["API.md", "STYLE.md", "package.json"];
const BORROWED = ["node_modules", "assets", "library2d"]; // what stays with the installation
const TEXT = /\.(js|mjs|cjs|json|md|txt)$/i;

const vnum = (v) => String(v || "").replace(/^[^\d]*/, "").split(".").map((n) => parseInt(n, 10) || 0);
/** Is version a newer than b? */
const newer = (a, b) => { const x = vnum(a), y = vnum(b); for (let i = 0; i < 3; i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) > (y[i] ?? 0); return false; };
const kitRoot = (userData) => path.join(userData, "film-kit");
const works = (dir) => !!dir && fs.existsSync(path.join(dir, "runner", "worker.mjs")) && fs.existsSync(path.join(dir, "kit", "index.js"));

/** Every text file under dir/rel, as { "rel/with/slashes": text }. */
function gather(dir, rel, out) {
  for (const e of fs.readdirSync(path.join(dir, rel), { withFileTypes: true })) {
    const r = rel ? `${rel}/${e.name}` : e.name;
    if (e.isDirectory()) { if (e.name !== "node_modules" && !e.name.startsWith(".")) gather(dir, r, out); } else if (e.isFile() && TEXT.test(e.name)) out[r] = fs.readFileSync(path.join(dir, r), "utf8");
  }
  return out;
}
/** The engine in filmDir as one file (a release asset). minApp: the oldest app it works with. */
function packKit(filmDir, version, minApp) {
  const files = {};
  for (const d of CARRIED) if (fs.existsSync(path.join(filmDir, d))) gather(filmDir, d, files);
  for (const f of CARRIED_FILES) if (fs.existsSync(path.join(filmDir, f))) files[f] = fs.readFileSync(path.join(filmDir, f), "utf8");
  if (!files["runner/worker.mjs"] || !files["kit/index.js"]) throw new Error(`No film engine in ${filmDir}.`);
  return zlib.gzipSync(JSON.stringify({ format: 1, version: String(version), minApp: String(minApp || version), files }), { level: 9 });
}
function readKit(buf) {
  let k; try { k = JSON.parse(zlib.gunzipSync(buf).toString("utf8")); } catch { throw new Error("The film engine file is damaged (it does not open)."); }
  if (k?.format !== 1 || !k.version || !k.files || typeof k.files !== "object") throw new Error("The film engine file is not one this app understands.");
  return k;
}
const listKit = (buf) => Object.keys(readKit(buf).files);
/** A name inside the kit must stay inside it: plain folders and file names, forward slashes, nothing that climbs out. */
const safeRel = (rel) => /^[A-Za-z0-9_][A-Za-z0-9_.\-]*(\/[A-Za-z0-9_][A-Za-z0-9_.\-]*)*$/.test(rel) && !rel.split("/").includes("..");

/** The downloaded file against the release's checksum line ("<sha256>  film-kit.json.gz"). Throws when they differ. */
function verifyKit(buf, shaText) {
  const want = String(shaText || "").trim().split(/\s+/)[0]?.toLowerCase() ?? "";
  const got = crypto.createHash("sha256").update(buf).digest("hex");
  if (!/^[0-9a-f]{64}$/.test(want) || want !== got) throw new Error("The film engine did not arrive whole (its checksum does not match): nothing was changed.");
}

/**
 * Set a kit up in the person's own folder. o: { userData, builtin (the installation's film folder), appVersion }.
 * Returns { version, dir }. Everything is written beside its final place and moved in at the end, so a kit is either
 * whole or not there; older fetched kits are removed.
 */
function installKit(buf, o) {
  const k = readKit(buf);
  if (newer(k.minApp, o.appVersion)) throw new Error(`This film engine (${k.version}) needs VideoStudio ${k.minApp} or newer; this is ${o.appVersion}. Install the app itself once (app.update).`);
  for (const rel of Object.keys(k.files)) if (!safeRel(rel) || typeof k.files[rel] !== "string") throw new Error(`The film engine file names a path it may not write: ${JSON.stringify(rel)}`);
  if (!k.files["runner/worker.mjs"] || !k.files["kit/index.js"]) throw new Error("The film engine file is incomplete.");
  const root = kitRoot(o.userData), name = String(k.version).replace(/[^\w.-]/g, ""), dir = path.join(root, name), tmp = path.join(root, `${name}.part`);
  fs.rmSync(tmp, { recursive: true, force: true }); fs.mkdirSync(tmp, { recursive: true });
  for (const [rel, text] of Object.entries(k.files)) { const f = path.join(tmp, ...rel.split("/")); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, text); }
  for (const b of BORROWED) { const from = path.join(o.builtin, b); if (fs.existsSync(from)) fs.symlinkSync(from, path.join(tmp, b), process.platform === "win32" ? "junction" : "dir"); }
  fs.rmSync(dir, { recursive: true, force: true }); fs.renameSync(tmp, dir);
  fs.writeFileSync(path.join(root, "current.json"), JSON.stringify({ version: k.version, minApp: k.minApp, dir: name, at: new Date().toISOString() }));
  for (const e of fs.readdirSync(root, { withFileTypes: true })) if (e.isDirectory() && e.name !== name) fs.rmSync(path.join(root, e.name), { recursive: true, force: true }); // (rmSync removes links, it does not follow them)
  return { version: k.version, dir };
}

/** Which engine draws. o: { userData, builtin, appVersion } → { dir, version, source: 'fetched' | 'built-in' }. */
function activeKit(o) {
  const builtIn = { dir: o.builtin, version: String(o.appVersion), source: "built-in" };
  try {
    const cur = JSON.parse(fs.readFileSync(path.join(kitRoot(o.userData), "current.json"), "utf8")), dir = path.join(kitRoot(o.userData), String(cur.dir).replace(/[^\w.-]/g, ""));
    if (works(dir) && newer(cur.version, o.appVersion) && !newer(cur.minApp, o.appVersion) && BORROWED.every((b) => !fs.existsSync(path.join(o.builtin, b)) || fs.existsSync(path.join(dir, b)))) return { dir, version: String(cur.version), source: "fetched" };
  } catch { /* none fetched, or not readable: the installation's own */ }
  return builtIn;
}

/** The newest desktop release that carries the engine as a file: { version, url, shaUrl, size } or null. */
function chooseRelease(list) {
  const rel = (list || []).filter((r) => /^desktop-v\d/.test(r.tag_name || "") && !r.draft && (r.assets || []).some((a) => a.name === ASSET) && (r.assets || []).some((a) => a.name === `${ASSET}.sha256`))
    .sort((a, b) => (newer(a.tag_name, b.tag_name) ? -1 : 1))[0];
  if (!rel) return null;
  const kit = rel.assets.find((a) => a.name === ASSET), sha = rel.assets.find((a) => a.name === `${ASSET}.sha256`);
  return { version: rel.tag_name.replace(/^desktop-v/, ""), url: kit.browser_download_url, shaUrl: sha.browser_download_url, size: kit.size ?? 0 };
}

/**
 * Fetch the newest engine and set it up. o: { fetch, repo: 'owner/name', userData, builtin, appVersion, progress? }.
 * → { updated, version, source, was } — the engine that draws from now on. Nothing changes unless the whole file
 * arrived, matches its checksum and works with this app.
 */
async function updateKit(o) {
  const was = activeKit(o), head = { accept: "application/vnd.github+json", "user-agent": "VideoStudio" };
  const res = await o.fetch(`https://api.github.com/repos/${o.repo}/releases?per_page=20`, { headers: head });
  if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
  const pick = chooseRelease(await res.json());
  if (!pick || !newer(pick.version, was.version)) return { updated: false, version: was.version, source: was.source, was: was.version, latest: pick?.version ?? null };
  const get = async (url) => { const r = await o.fetch(url, { headers: { "user-agent": "VideoStudio", accept: "application/octet-stream" } }); if (!r.ok) throw new Error(`The download answered ${r.status}`); return r; };
  const buf = Buffer.from(await (await get(pick.url)).arrayBuffer());
  verifyKit(buf, await (await get(pick.shaUrl)).text());
  installKit(buf, o);
  const now = activeKit(o);
  return { updated: now.version !== was.version, version: now.version, source: now.source, was: was.version, latest: pick.version };
}

module.exports = { ASSET, newer, packKit, listKit, verifyKit, installKit, activeKit, chooseRelease, updateKit, kitRoot };
