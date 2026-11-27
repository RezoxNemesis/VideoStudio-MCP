import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

// The CodeScape Library film is the showcase of the motion library (hands, speech, the whole body, captured motion) and what every
// CodeScape film copies from. It must always pass its own deep review: no shaking lens, no hold out of reach, no sliding foot, no locked knee.
describe("the CodeScape Library film", () => {
  it("passes the deep review with the engine the app ships (cameras, holds, bodies)", () => {
    const cine = path.resolve(__dirname, "../desktop/film/cine"), dir = mkdtempSync(path.join(tmpdir(), "cslib-"));
    try {
      // a film folder as the app resolves it: the film's own files beside the engine's (node follows no resolver, so a plain copy)
      cpSync(path.join(cine, "web/codescape"), dir, { recursive: true });
      cpSync(path.join(cine, "web/three.module.js"), path.join(dir, "three.module.js"));
      cpSync(path.join(cine, "films/codescape-library"), dir, { recursive: true });
      let out = "";
      try { out = execFileSync(process.execPath, [path.join(cine, "tools/review/run.mjs"), dir], { encoding: "utf8", timeout: 240_000 }); }
      catch (e) { out = String((e as { stdout?: string }).stdout ?? e); }
      expect(out).toContain("REVIEW: clean");
      for (const shot of ["vikas", "maa", "walk", "turn", "sit", "gesture", "give", "mocap", "live", "hold", "place", "talk"]) expect(out).toMatch(new RegExp(`ok\\s+${shot}\\b`));
    } finally { rmSync(dir, { recursive: true, force: true }); }
  }, 300_000);

  it("keeps the stand-in take as whole pictures with a true index (not a video: a video in the film's page lost the graphics context)", () => {
    const dir = path.resolve(__dirname, "../desktop/film/cine/films/codescape-library");
    const meta = JSON.parse(readFileSync(path.join(dir, "live_demo.actor.json"), "utf8")) as { pack: string; w: number; h: number; fps: number; frames: number; feet: number; body: number; at: number[] };
    const pack = readFileSync(path.join(dir, meta.pack));
    expect(meta.at.length).toBe(meta.frames + 1); expect(meta.at[0]).toBe(0); expect(meta.at[meta.frames]).toBe(pack.length);
    // every place in the index is the beginning of a picture (FF D8), and the picture before it ends there (FF D9)
    for (let f = 0; f < meta.frames; f++) { const a = meta.at[f]!, b = meta.at[f + 1]!; expect(b).toBeGreaterThan(a); expect([pack[a], pack[a + 1]]).toEqual([0xff, 0xd8]); expect([pack[b - 2], pack[b - 1]]).toEqual([0xff, 0xd9]); }
    // the size written down is the first picture's own (a wrong width stood the filmed person squeezed in the room)
    let w = 0, h = 0; for (let i = 2; i + 9 < pack.length && pack[i] === 0xff;) { const m = pack[i + 1]!, len = pack.readUInt16BE(i + 2); if (m >= 0xc0 && m <= 0xc3) { h = pack.readUInt16BE(i + 5); w = pack.readUInt16BE(i + 7); break; } i += 2 + len; }
    expect([meta.w, meta.h]).toEqual([w, h]);
    // and where the person stands in it, as cine.actor measures: the feet near the bottom, the body most of the height
    expect(meta.feet).toBeGreaterThanOrEqual(0); expect(meta.feet).toBeLessThan(0.15);
    expect(meta.body).toBeGreaterThan(0.5); expect(meta.body).toBeLessThanOrEqual(1);
    // no video element anywhere in the engine a film runs on
    const live = readFileSync(path.resolve(__dirname, "../desktop/film/cine/web/codescape/live.js"), "utf8");
    expect(live).not.toMatch(/createElement\(['"]video['"]\)/); expect(live).not.toContain("VideoTexture");
  });
});
