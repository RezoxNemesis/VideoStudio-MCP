import { describe, it, expect } from "vitest";
import { createRequire } from "node:module";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const { hungFirst } = createRequire(import.meta.url)("../desktop/electron/stuck.cjs");
const setup = (pid: number, ageMs: number) => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "vss-")), session = path.join(d, "session.marker"), pidFile = path.join(d, "running.pid");
  fs.writeFileSync(session, "1"); fs.writeFileSync(pidFile, String(pid)); const t = new Date(Date.now() - ageMs); fs.utimesSync(session, t, t);
  return { session, pidFile, now: Date.now() };
};

describe("a hung copy of the app", () => {
  it("is ended by a new start when it has not touched its marker for two minutes (the app opens again)", () => {
    expect(hungFirst({ ...setup(4242, 300_000), alive: () => true, isApp: () => true })).toBe(4242);
  });
  it("is left alone while it is alive and well, gone, or not the app", () => {
    expect(hungFirst({ ...setup(4242, 20_000), alive: () => true, isApp: () => true })).toBeNull();     // (it beats: it is only busy)
    expect(hungFirst({ ...setup(4242, 300_000), alive: () => false, isApp: () => true })).toBeNull();   // (gone already)
    expect(hungFirst({ ...setup(4242, 300_000), alive: () => true, isApp: () => false })).toBeNull();   // (the number is someone else's now)
    expect(hungFirst({ ...setup(process.pid, 300_000), alive: () => true, isApp: () => true })).toBeNull(); // (never itself)
  });
});
