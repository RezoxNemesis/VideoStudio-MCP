import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { Human } from "../src/stage/engine/human.js";
import { Mocap, Motion, gaitCycle } from "../src/stage/engine/anim.js";
import { MotionLibrary, type MotionIndex } from "../src/stage/motion/library";
import { Pack } from "../src/stage/pack";

// Captured hands are the weakest part of a capture: a walker's hand must not be carried bent out at the wrist.
const FX = JSON.parse(readFileSync(new URL("./fixtures/body_bones.json", import.meta.url), "utf8")) as { height: number; bones: { name: string; head: number[]; tail: number[]; parent: number }[] };
const ASSETS = [process.env.VS_STAGE_ASSETS_DIR, "desktop/stage-assets", "/home/claude/ws/squashfs-root/resources/stage-assets"].find((d) => d && existsSync(`${d}/motion/index.json`));
const DEG = 180 / Math.PI;

describe.skipIf(!ASSETS)("hands in the gaits of the motion library", () => {
  const index = ASSETS ? (JSON.parse(readFileSync(`${ASSETS}/motion/index.json`, "utf8")) as MotionIndex) : (null as unknown as MotionIndex);
  const packs = new Map<number, Pack>();
  const packNow = (s: number) => { let p = packs.get(s); if (!p) { const b = readFileSync(`${ASSETS}/motion/s${String(s).padStart(3, "0")}.pack`); p = new Pack(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer); packs.set(s, p); } return p; };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const body = (): any => new Human({ name: "walker", height: FX.height, landmarks: {}, look: {}, morphNames: [], parts: [], bones: FX.bones });
  /** How far each hand is bent away from its forearm's line (degrees), the worst over a whole stride. */
  const worstBend = (gait: string) => {
    const lib = new MotionLibrary(); lib.index = index;
    const name = lib.pickGait(gait)!, h = body(), mc = new Mocap({ index, packNow }), mo = new Motion(h, mc), clip = mc.clip(name), c = gaitCycle(clip, mc.jid) as { t0: number; t1: number };
    let worst = 0;
    for (let i = 0; i <= 24; i++) {
      mo.apply(name, c.t0 + ((c.t1 - c.t0) * i) / 24, { inPlace: true, face: true }); h.update();
      for (const sd of ["L", "R"] as const) {
        const q = h.B[`wrist.${sd}`].getWorldQuaternion(new THREE.Quaternion()), fwd = h.hand[sd].fwd.clone().applyQuaternion(q), fore = h.wpos(`wrist.${sd}`).sub(h.wpos(`lowerarm01.${sd}`)).normalize();
        worst = Math.max(worst, Math.acos(Math.max(-1, Math.min(1, fore.dot(fwd)))) * DEG);
      }
    }
    return { name, worst };
  };
  it("a walker's hands stay in line with the forearms (no hand carried bent out at the wrist)", () => {
    for (const gait of ["walk", "stroll", "march", "jog", "run", "sneak"]) {
      const r = worstBend(gait);
      expect(r.worst, `${gait} (${r.name}): the worst bend of a wrist over the stride, in degrees`).toBeLessThan(40);
    }
  });
});
