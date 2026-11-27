import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { cpSync, mkdtempSync, rmSync } from "node:fs";
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
      for (const shot of ["walk", "turn", "sit", "gesture", "give", "mocap", "live", "hold", "place", "talk"]) expect(out).toMatch(new RegExp(`ok\\s+${shot}\\b`));
    } finally { rmSync(dir, { recursive: true, force: true }); }
  }, 300_000);
});
