import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { Human } from "../src/stage/engine/human.js";

// The rest-pose hand bones of the standard body (both wrists and everything that hangs from them).
const FX = JSON.parse(readFileSync(new URL("../desktop/film/devtests/fixtures/hand_bones.json", import.meta.url), "utf8")) as { bones: { name: string; head: number[]; tail: number[]; parent: number }[] };

/** A body that is only two hands, in its rest pose (the engine is plain JavaScript: its figure is untyped here). */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function hands(): any {
  return new Human({ name: "hands", height: 1.8, landmarks: {}, look: {}, morphNames: [], parts: [], bones: FX.bones });
}
/** Where a bone's tail is in character space after posing. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function tailOf(h: any, name: string): THREE.Vector3 {
  h.update();
  const r = h.rest[name];
  return r.tail.clone().sub(r.head).applyQuaternion(h.B[name].getWorldQuaternion(new THREE.Quaternion())).add(h.wpos(name));
}

describe("the Stage's hands", () => {
  for (const s of ["L", "R"] as const) {
    it(`${s}: closing the fingers brings the fingertips in front of the palm, never behind the hand`, () => {
      const h = hands(), H = h.hand[s], w = h.rest[`wrist.${s}`].head;
      const front = (name: string): number => -tailOf(h, name).sub(w).dot(H.back); // metres on the palm's side of the hand
      h.fingers(s, 0.97, 0.8);
      for (const f of [2, 3, 4, 5]) expect(front(`finger${f}-3.${s}`)).toBeGreaterThan(-0.005); // a tight fist tucks the tips back against the palm, never through to the back
      // the proximal phalanx itself turns towards the palm
      const k = h.rest[`finger3-1.${s}`];
      const dir = k.tail.clone().sub(k.head).applyQuaternion(h.B[`finger3-1.${s}`].getWorldQuaternion(new THREE.Quaternion())).normalize();
      expect(-dir.dot(H.back)).toBeGreaterThan(0.6);
    });
    it(`${s}: closing the thumb folds it across the palm, towards the fingers`, () => {
      const h = hands(), H = h.hand[s], tip = `finger1-3.${s}`;
      const before = tailOf(h, tip).dot(H.across);
      h.fingers(s, 0.2, 0.8);
      expect(tailOf(h, tip).dot(H.across)).toBeGreaterThan(before + 0.01);
    });
  }
  it("both hands close the same way, mirrored", () => {
    const h = hands();
    h.fingers("L", 0.7, 0.7); h.fingers("R", 0.7, 0.7);
    for (const f of [1, 2, 3, 4, 5]) {
      const l = tailOf(h, `finger${f}-3.L`), r = tailOf(h, `finger${f}-3.R`);
      expect(Math.hypot(l.x + r.x, l.y - r.y, l.z - r.z)).toBeLessThan(0.002);
    }
  });
});
