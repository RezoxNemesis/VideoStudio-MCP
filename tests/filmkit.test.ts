// The film engine of Code Studio can be renewed without installing the app again: a release carries the engine as one
// small file (film-kit.json.gz); the app fetches it into the person's own folder and draws with it from then on. No
// installer, no password. (The owner was installing a new package by hand for every engine upgrade, 16 Nov 2026.)
import { describe, it, expect } from "vitest";
import { createRequire } from "node:module";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { execFileSync } from "node:child_process";

const K = createRequire(import.meta.url)("../desktop/electron/film-kit.cjs");

const put = (root: string, rel: string, text: string) => { const f = path.join(root, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, text); };
/** A film engine as an installation carries it. */
function engine(mark: string) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vs-film-"));
  put(dir, "runner/worker.mjs", `// worker ${mark}`); put(dir, "kit/index.js", `export const MARK = "${mark}";`); put(dir, "engine/shot.js", `// shot ${mark}`);
  put(dir, "API.md", `# handbook ${mark}`); put(dir, "templates/starter/film.js", "export const FILM = [];"); put(dir, "package.json", `{"type":"module"}`);
  put(dir, "node_modules/@napi-rs/canvas/index.js", "// canvas"); put(dir, "assets/fonts/a.ttf", "font"); put(dir, "library2d/out/index.json", "{}");
  put(dir, "devtests/life.mjs", "// test"); put(dir, "package-lock.json", "{}");
  return dir;
}
const home = () => fs.mkdtempSync(path.join(os.tmpdir(), "vs-user-"));

describe("the film engine as one file", () => {
  it("holds what draws a film and leaves out what an installation already has", () => {
    const names = K.listKit(K.packKit(engine("new"), "2.5.80", "2.5.75"));
    expect(names).toEqual(expect.arrayContaining(["runner/worker.mjs", "kit/index.js", "engine/shot.js", "API.md", "templates/starter/film.js", "package.json"]));
    expect(names.some((n: string) => /^(node_modules|assets|library2d|devtests)\//.test(n) || n === "package-lock.json")).toBe(false);
  });

  it("is set up in the person's own folder and used instead of the built-in engine", () => {
    const builtin = engine("old"), user = home(), kit = K.packKit(engine("new"), "2.5.80", "2.5.75");
    const done = K.installKit(kit, { userData: user, builtin, appVersion: "2.5.75" });
    expect(done.version).toBe("2.5.80");
    expect(done.dir.startsWith(user)).toBe(true);
    expect(fs.readFileSync(path.join(done.dir, "kit/index.js"), "utf8")).toContain("new");
    // what it does not carry it borrows from the installation: the canvas library, fonts and sounds, the 2D library
    for (const d of ["node_modules", "assets", "library2d"]) expect(fs.realpathSync(path.join(done.dir, d))).toBe(fs.realpathSync(path.join(builtin, d)));
    expect(fs.existsSync(path.join(done.dir, "node_modules/@napi-rs/canvas/index.js"))).toBe(true);
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.75" })).toMatchObject({ dir: done.dir, version: "2.5.80", source: "fetched" });
  });

  it("gives way to the installation once that is as new or newer", () => {
    const builtin = engine("old"), user = home();
    K.installKit(K.packKit(engine("new"), "2.5.80", "2.5.75"), { userData: user, builtin, appVersion: "2.5.75" });
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.80" })).toMatchObject({ dir: builtin, source: "built-in" });
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.6.0" })).toMatchObject({ dir: builtin, source: "built-in" });
    expect(K.activeKit({ userData: home(), builtin, appVersion: "2.5.75" })).toMatchObject({ dir: builtin, version: "2.5.75", source: "built-in" });
  });

  it("is refused when it needs a newer app than this one, and says so", () => {
    const builtin = engine("old"), user = home();
    expect(() => K.installKit(K.packKit(engine("new"), "2.5.95", "2.5.90"), { userData: user, builtin, appVersion: "2.5.75" })).toThrow(/2\.5\.90/);
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.75" }).source).toBe("built-in");
  });

  it("never writes outside its own folder", () => {
    const builtin = engine("old"), user = home();
    for (const bad of ["../evil.js", "/tmp/evil.js", "kit/../../evil.js", "kit\\..\\evil.js"]) {
      const buf = zlib.gzipSync(JSON.stringify({ format: 1, version: "2.5.80", minApp: "2.5.75", files: { "runner/worker.mjs": "// w", [bad]: "boom" } }));
      expect(() => K.installKit(buf, { userData: user, builtin, appVersion: "2.5.75" })).toThrow(/path/i);
    }
    expect(fs.existsSync(path.join(user, "evil.js"))).toBe(false);
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.75" }).source).toBe("built-in");
  });

  it("is checked against the release's checksum before anything is set up", () => {
    const kit = K.packKit(engine("new"), "2.5.80", "2.5.75"), sha = crypto.createHash("sha256").update(kit).digest("hex");
    expect(() => K.verifyKit(kit, `${sha}  film-kit.json.gz\n`)).not.toThrow();
    expect(() => K.verifyKit(Buffer.concat([kit, Buffer.from("x")]), `${sha}  film-kit.json.gz\n`)).toThrow(/checksum/i);
    expect(() => K.verifyKit(kit, "")).toThrow(/checksum/i);
  });

  it("falls back to the built-in engine when the fetched one is damaged", () => {
    const builtin = engine("old"), user = home();
    const done = K.installKit(K.packKit(engine("new"), "2.5.80", "2.5.75"), { userData: user, builtin, appVersion: "2.5.75" });
    fs.rmSync(path.join(done.dir, "runner/worker.mjs"));
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.75" })).toMatchObject({ dir: builtin, source: "built-in" });
  });

  it("keeps only the newest one", () => {
    const builtin = engine("old"), user = home();
    const a = K.installKit(K.packKit(engine("a"), "2.5.80", "2.5.75"), { userData: user, builtin, appVersion: "2.5.75" });
    const b = K.installKit(K.packKit(engine("b"), "2.5.81", "2.5.75"), { userData: user, builtin, appVersion: "2.5.75" });
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.75" })).toMatchObject({ dir: b.dir, version: "2.5.81" });
    expect(fs.existsSync(a.dir)).toBe(false);
    expect(fs.existsSync(path.join(builtin, "node_modules/@napi-rs/canvas/index.js"))).toBe(true); // (removing an old one never follows its links)
  });
});

describe("which release to fetch the engine from", () => {
  const rel = (v: string, kit = true, draft = false) => ({ tag_name: `desktop-v${v}`, draft, assets: [{ name: `VideoStudio-${v}-amd64.deb`, browser_download_url: `https://x/${v}.deb` }, ...(kit ? [{ name: "film-kit.json.gz", browser_download_url: `https://x/${v}/film-kit.json.gz`, size: 1000 }, { name: "film-kit.json.gz.sha256", browser_download_url: `https://x/${v}/film-kit.json.gz.sha256` }] : [])] });
  it("is the newest desktop release that carries one", () => {
    const pick = K.chooseRelease([rel("2.5.79"), rel("2.5.81"), rel("2.5.82", false), rel("2.5.83", true, true), { tag_name: "apk-v9.9.9", assets: [] }, rel("2.5.80")]);
    expect(pick).toMatchObject({ version: "2.5.81", url: "https://x/2.5.81/film-kit.json.gz", shaUrl: "https://x/2.5.81/film-kit.json.gz.sha256" });
  });
  it("is none when no release carries one", () => {
    expect(K.chooseRelease([rel("2.5.75", false)])).toBeNull();
  });
});

describe("fetching the newest engine", () => {
  const kit = (v: string, minApp = "2.5.75") => K.packKit(engine(v), v, minApp);
  const net = (v: string, buf: Buffer, sha?: string) => async (url: string) => {
    if (url.includes("/releases")) return { ok: true, status: 200, json: async () => [{ tag_name: `desktop-v${v}`, assets: [{ name: "film-kit.json.gz", browser_download_url: "https://x/kit" }, { name: "film-kit.json.gz.sha256", browser_download_url: "https://x/sha" }] }] };
    if (url === "https://x/kit") return { ok: true, status: 200, arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) };
    return { ok: true, status: 200, text: async () => sha ?? `${crypto.createHash("sha256").update(buf).digest("hex")}  film-kit.json.gz` };
  };
  it("sets it up and says which engine draws now", async () => {
    const builtin = engine("old"), user = home();
    const r = await K.updateKit({ fetch: net("2.5.81", kit("2.5.81")), repo: "o/r", userData: user, builtin, appVersion: "2.5.75" });
    expect(r).toMatchObject({ updated: true, version: "2.5.81", source: "fetched" });
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.75" }).version).toBe("2.5.81");
  });
  it("does nothing when this one is already the newest", async () => {
    const builtin = engine("old"), user = home();
    const r = await K.updateKit({ fetch: net("2.5.75", kit("2.5.75")), repo: "o/r", userData: user, builtin, appVersion: "2.5.75" });
    expect(r).toMatchObject({ updated: false, version: "2.5.75", source: "built-in" });
  });
  it("leaves everything as it was when the download does not match its checksum", async () => {
    const builtin = engine("old"), user = home();
    await expect(K.updateKit({ fetch: net("2.5.81", kit("2.5.81"), "00ff  film-kit.json.gz"), repo: "o/r", userData: user, builtin, appVersion: "2.5.75" })).rejects.toThrow(/checksum/i);
    expect(K.activeKit({ userData: user, builtin, appVersion: "2.5.75" }).source).toBe("built-in");
  });
  it("says that the app itself must be installed when the engine needs a newer one", async () => {
    const builtin = engine("old"), user = home();
    await expect(K.updateKit({ fetch: net("2.5.95", kit("2.5.95", "2.5.90")), repo: "o/r", userData: user, builtin, appVersion: "2.5.75" })).rejects.toThrow(/2\.5\.90/);
  });
});

describe("the release packs the engine of this repository", () => {
  it("as film-kit.json.gz with its checksum beside it, named for the app version and the oldest app it works with", () => {
    const out = fs.mkdtempSync(path.join(os.tmpdir(), "vs-rel-"));
    execFileSync(process.execPath, [path.join(__dirname, "..", "desktop", "build", "pack-kit.cjs"), out], { stdio: "pipe" });
    const buf = fs.readFileSync(path.join(out, "film-kit.json.gz")), names = K.listKit(buf);
    expect(names).toEqual(expect.arrayContaining(["runner/worker.mjs", "kit/index.js", "engine/shot.js", "engine/finish.js", "kit/moves.js", "API.md"]));
    expect(() => K.verifyKit(buf, fs.readFileSync(path.join(out, "film-kit.json.gz.sha256"), "utf8"))).not.toThrow();
    expect(buf.length).toBeLessThan(3_000_000); // small enough to fetch in a moment
    const meta = JSON.parse(zlib.gunzipSync(buf).toString("utf8")), want = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "desktop", "film", "KIT.json"), "utf8"));
    expect(meta.version).toBe(JSON.parse(fs.readFileSync(path.join(__dirname, "..", "desktop", "package.json"), "utf8")).version);
    expect(meta.minApp).toBe(want.minApp);
    expect(/^\d+\.\d+\.\d+$/.test(want.minApp)).toBe(true);
  });
});
