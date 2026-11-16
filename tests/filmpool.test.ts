import { describe, it, expect } from "vitest";
import { createRequire } from "node:module";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const require = createRequire(import.meta.url);
const { FilmPool } = require("../desktop/electron/film-pool.cjs");

// a worker script that dies before it is ready (an engine that cannot start: a missing module, a broken file)
const dead = () => { const d = fs.mkdtempSync(path.join(os.tmpdir(), "vsp-")), f = path.join(d, "worker.mjs"); fs.writeFileSync(f, "process.stderr.write('Cannot find package @napi-rs/canvas\\n'); process.exit(3);\n"); return f; };
// a worker that starts and answers
const live = () => { const d = fs.mkdtempSync(path.join(os.tmpdir(), "vsp-")), f = path.join(d, "worker.mjs"); fs.writeFileSync(f, "process.send({ ready: true }); process.on('message', (m) => process.send({ id: m.id, ok: true, value: m.method }));\n"); return f; };

describe("the film workers", () => {
  it("are not started again and again when the engine cannot start: they give up, say why, and the job fails", async () => {
    let broken = "", starts = 0;
    const pool = new FilmPool({ script: dead(), execPath: process.execPath, size: 2, onBroken: (log: string) => { broken = log; }, onStart: () => { starts++; } });
    await expect(pool.ask("open", { dir: "/x" })).rejects.toThrow(/could not start/i);
    expect(broken).toMatch(/@napi-rs\/canvas/);
    const at = starts;
    await new Promise((r) => setTimeout(r, 1500));
    expect(starts).toBe(at);                       // (no more starts once it gave up)
    expect(starts).toBeLessThanOrEqual(3 * 3);     // (three tries for each of desk + 2 workers at most)
    await expect(pool.work("render", {})).rejects.toThrow(/could not start/i);
    pool.close();
  }, 20000);
  it("still start and answer when the engine is sound", async () => {
    const pool = new FilmPool({ script: live(), execPath: process.execPath, size: 1 });
    await expect(pool.ask("open", {})).resolves.toBe("open");
    pool.close();
  }, 20000);
});
