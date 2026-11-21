// Cine 3D (desktop/electron/cine.cjs): films staged in real depth are drawn by a page of the engine in a hidden window.
// The server answers a page's files from the film first, then the shared assets, then the engine that came with the
// app; a page that says what the film holds is believed; names that could leave the folders are refused.
import { describe, it, expect } from "vitest";
import { createRequire } from "node:module";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const { createCine } = createRequire(import.meta.url)("../desktop/electron/cine.cjs");
const put = (root: string, rel: string, text: string) => { const f = path.join(root, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, text); };

function setup() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vs-cine-")), web = fs.mkdtempSync(path.join(os.tmpdir(), "vs-cine-web-"));
  put(web, "engine.js", "// engine of the app"); put(web, "kit.js", "// kit of the app"); put(web, "shot.html", "<html></html>");
  put(web, "clay/engine.js", "// the clay engine"); put(path.dirname(web), "films/clay-starter/film.js", "// @engine clay\nexport const S = {};"); put(path.dirname(web), "films/clay-starter/README.md", "a clay film");
  put(root, "assets/tex/grass.png", "png"); put(root, "A Film/film.js", "export const S = {};"); put(root, "A Film/kit.js", "// the film's own kit");
  const seen: string[] = [];
  // a "window": asks for the page's files as a browser would, then says what the film holds
  const openPage = async (url: string) => {
    const u = new URL(url), base = `${u.protocol}//${u.host}`;
    void (async () => {
      for (const f of ["engine.js", "kit.js", "tex/grass.png", "../secret", "nothing.js"]) { const r = await fetch(`${base}/${f}`); seen.push(`${f}:${r.status}:${r.ok ? await r.text() : ""}`); }
      if (u.searchParams.get("info")) await fetch(`${base}/say?name=info`, { method: "POST", body: JSON.stringify({ name: "A Film", width: 2560, height: 1440, fps: 30, shots: [{ id: "s01", dur: 8, about: "" }, { id: "s02", dur: 6.5, about: "" }] }) });
    })();
    return { close: () => {}, gone: new Promise<string>(() => {}) };
  };
  const cine = createCine({ root, web, guide: path.join(web, "GUIDE.md"), ffmpeg: "ffmpeg", openPage });
  return { root, cine, seen };
}

describe("Cine 3D", () => {
  it("lists films and reads a film's shots from its own code", async () => {
    const { cine, seen } = setup();
    try {
      expect((await cine.list()).films.map((f: { name: string }) => f.name)).toEqual(["A Film"]);
      const o = await cine.open("A Film");
      expect(o.shots.map((s: { id: string }) => s.id)).toEqual(["s01", "s02"]);
      expect(o.seconds).toBe(14.5);
      expect(seen).toContain("engine.js:200:// engine of the app"); // the app's engine
      expect(seen).toContain("kit.js:200:// the film's own kit"); // the film's file comes first
      expect(seen).toContain("tex/grass.png:200:png"); // shared assets
      expect(seen.find((s) => s.startsWith("nothing.js"))).toBe("nothing.js:404:");
      expect(seen.find((s) => s.startsWith("../secret"))).toMatch(/:404:/);
    } finally { cine.close(); }
  });
  it("draws a clay film with the clay engine: its files come after the film's and the shared assets, before the Cine engine", async () => {
    const { root, cine, seen } = setup();
    const web = (cine as unknown as { engineOf: (d: string) => string });
    try {
      put(root, "Clay Film/film.js", "// @engine clay\nexport const S = {};");
      expect(web.engineOf(path.join(root, "Clay Film"))).toBe("clay");
      expect(web.engineOf(path.join(root, "A Film"))).toBe("cine");
      expect((await cine.list()).films.find((f: { name: string }) => f.name === "Clay Film").engine).toBe("clay");
      await cine.open("Clay Film");
      expect(seen).toContain("engine.js:200:// the clay engine"); // web/clay/engine.js stands before web/engine.js
      expect(seen).toContain("kit.js:200:// kit of the app"); // what the clay engine has not, the Cine engine gives
    } finally { cine.close(); }
  });
  it("starts a clay film from its starter", async () => {
    const { root, cine } = setup();
    try {
      const r = await cine.create("New Clay", "clay");
      expect(r.engine).toBe("clay");
      expect(fs.readFileSync(path.join(root, "New Clay", "film.js"), "utf8")).toContain("@engine clay");
      expect(fs.existsSync(path.join(root, "New Clay", "data"))).toBe(false);
    } finally { cine.close(); }
  });
  it("keeps to the film's folder", async () => {
    const { cine } = setup();
    try {
      await expect(cine.read("A Film", "../assets/tex/grass.png")).rejects.toThrow();
      await expect(cine.read("A Film", "film.js")).resolves.toContain("export const S");
      await expect(cine.open("No Such Film")).rejects.toThrow(/Not a Cine film/);
      const w = await cine.write("A Film", "notes.md", "hello");
      expect(fs.readFileSync(w.file, "utf8")).toBe("hello");
    } finally { cine.close(); }
  });
});
