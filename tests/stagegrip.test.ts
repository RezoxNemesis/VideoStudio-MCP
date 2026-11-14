import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { Human } from "../src/stage/engine/human.js";
import { Actor } from "../src/stage/engine/actors.js";
import { SW, swordAt, applySword } from "../src/stage/engine/fight.js";

// The rest pose of the standard body (every bone).
const FX = JSON.parse(readFileSync(new URL("./fixtures/body_bones.json", import.meta.url), "utf8")) as { height: number; bones: { name: string; head: number[]; tail: number[]; parent: number }[] };
const DEG = 180 / Math.PI;
/** The named sword positions, by name. */
type Key = { h: THREE.Vector3; d: THREE.Vector3; e: THREE.Vector3 };
const ALL = SW as Record<string, Key>;
const key = (n: string): Key => { const p = ALL[n]; if (!p) throw new Error(`no sword position ${n}`); return p; };

/** A fighter that is only a skeleton, holding a sword (a bare object: +Y its blade, +Z its true edge) in the right hand. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function fighter(): any {
  const h = new Human({ name: "fighter", height: FX.height, landmarks: {}, look: {}, morphNames: [], parts: [], bones: FX.bones });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const a: any = new Actor(h, null, "fighter");
  a.props.sword = a.hold(new THREE.Object3D(), "R");
  return a;
}
/** What a sword hand looks like in numbers, after a pose. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function hand(a: any, side: "L" | "R" = "R") {
  const h = a.h; h.update();
  const q = h.B[`wrist.${side}`].getWorldQuaternion(new THREE.Quaternion()), H = h.hand[side];
  const wrist = h.wpos(`wrist.${side}`), elbow = h.wpos(`lowerarm01.${side}`), fore = wrist.clone().sub(elbow).normalize();
  const fwd = H.fwd.clone().applyQuaternion(q), palm = H.back.clone().negate().applyQuaternion(q), thumbSide = H.across.clone().negate().applyQuaternion(q);
  const sw = a.props.sword, sq = sw.getWorldQuaternion(new THREE.Quaternion()), blade = new THREE.Vector3(0, 1, 0).applyQuaternion(sq), edge = new THREE.Vector3(0, 0, 1).applyQuaternion(sq), at = sw.getWorldPosition(new THREE.Vector3());
  // the twist left in the wrist itself: how far the hand is turned about the forearm's own line against the forearm's last bone
  const l2 = h.B[`lowerarm02.${side}`] ?? h.B[`lowerarm01.${side}`], rel = l2.getWorldQuaternion(new THREE.Quaternion()).invert().multiply(q), ax = h.rest[`wrist.${side}`].head.clone().sub(h.rest[`lowerarm01.${side}`].head).normalize();
  const tw = 2 * Math.atan2(Math.abs(rel.x * ax.x + rel.y * ax.y + rel.z * ax.z), Math.abs(rel.w)) * DEG;
  // how far the arm is stretched towards where it was sent (1 = straight: it could not reach further)
  const sh = h.wpos(`upperarm01.${side}`), R = h.rest, full = R[`upperarm01.${side}`].head.distanceTo(R[`lowerarm01.${side}`].head) + R[`lowerarm01.${side}`].head.distanceTo(R[`wrist.${side}`].head), stretch = sh.distanceTo(wrist) / full;
  return { stretch, bend: Math.acos(Math.max(-1, Math.min(1, fore.dot(fwd)))) * DEG, tilt: Math.abs(90 - Math.acos(Math.max(-1, Math.min(1, blade.dot(fwd)))) * DEG), fwd, palm, thumbSide, blade, edge, at, wrist, twist: tw > 180 ? 360 - tw : tw };
}

describe("the Stage's sword hand", () => {
  const names = Object.keys(SW);
  it("has the named sword positions to test", () => { expect(names.length).toBeGreaterThan(15); });

  it("every sword position: the blade lies where the position says, through the hand", () => {
    for (const n of names) {
      const a = fighter(), P = key(n); applySword(a, P, {});
      const m = hand(a), h = a.h, k = h.height / 1.8, R = h.rest;
      expect(m.blade.angleTo(P.d) * DEG, `${n}: blade`).toBeLessThan(2.5);
      // the hilt passes through the hand's position (within 3 cm of the blade's own line through it)
      const off = P.h.clone().multiplyScalar(k).sub(m.at), along = off.dot(m.blade), away = off.clone().addScaledVector(m.blade, -along).length();
      // a position beyond the arm's reach is held as near as the arm reaches: the arm straight, and no further off than it is short
      const arm = R["upperarm01.R"].head.distanceTo(R["lowerarm01.R"].head) + R["lowerarm01.R"].head.distanceTo(R["wrist.R"].head), short = Math.max(0, h.wpos("upperarm01.R").distanceTo(P.h.clone().multiplyScalar(k)) - arm - 0.09 * k);
      if (short > 0) { expect(m.stretch, `${n}: out of reach, so the arm is all but straight`).toBeGreaterThan(0.97); expect(off.length(), `${n}: as near as the arm reaches`).toBeLessThan(short + 0.1); continue; }
      // (an arm near the end of its reach gives a little: it is not locked straight to gain the last centimetres)
      expect(away, `${n}: the sword's line passes through the hand's place`).toBeLessThan(m.stretch > 0.93 ? 0.075 : 0.03);
      expect(Math.abs(along), `${n}: the hand's place is on the hilt`).toBeLessThan(0.12);
    }
  });

  it("every sword position: the wrist is a wrist — the hand lies along the forearm, bent no further than a wrist bends", () => {
    for (const n of names) {
      const a = fighter(); applySword(a, key(n), {});
      const m = hand(a);
      expect(m.bend, `${n}: wrist bend`).toBeLessThan(62);
      expect(m.tilt, `${n}: how far the sword is turned in the grip`).toBeLessThan(62);
    }
  });

  it("a low guard: the back of the hand is turned outwards and the fingers close under the hilt — the palm faces the body, the true edge is down", () => {
    const a = fighter(); applySword(a, SW.low, {});
    const m = hand(a);
    expect(m.palm.x, "the palm faces the body's middle (x = the fighter's left)").toBeGreaterThan(0.25);
    expect(m.fwd.y, "the fingers run downwards from the knuckles, not up").toBeLessThan(0.1);
    expect(m.edge.y, "the true edge is the lower one").toBeLessThan(0);
    expect(m.thumbSide.angleTo(m.blade) * DEG, "the blade leaves the hand on the thumb's side").toBeLessThan(62);
  });

  it("a thrust and a point: the blade runs on from the forearm, the sword turned in the grip rather than the wrist broken", () => {
    for (const n of ["thrust", "point", "fwd"]) {
      const a = fighter(); applySword(a, key(n), {});
      const m = hand(a);
      expect(m.bend, `${n}: wrist bend`).toBeLessThan(50);
      expect(m.thumbSide.dot(m.blade), `${n}: thumb towards the point`).toBeGreaterThan(0.3);
    }
  });

  it("the forearm turns with the hand: no more than a third of a turn is left to the wrist joint itself", () => {
    for (const n of names) {
      const a = fighter(); applySword(a, key(n), {});
      expect(hand(a).twist, `${n}: twist at the wrist`).toBeLessThan(75);
    }
  });

  it("a swing passes smoothly from one position to the next: the hand never flips", () => {
    // a flip is a jump: however finely time is cut, the palm is suddenly somewhere else. (A fast cut is not a flip: cut finely, it moves a little each step.)
    const seq: [number, string][] = [[0, "guard"], [0.3, "hiR"], [0.5, "loL"], [0.8, "guard"], [1.1, "low"], [1.4, "side"], [1.7, "high"], [2.0, "thrust"], [2.3, "backR"], [2.6, "guard"]];
    const a = fighter(); let prev: THREE.Vector3 | null = null, worst = 0, at = 0;
    for (let i = 0; i <= 2.6 * 480; i++) {
      const t = i / 480; a.h.B.spine05.quaternion.identity(); a.h.B.spine03.quaternion.identity(); applySword(a, swordAt(t, seq), {}); // (the body is posed afresh each frame, as on the Stage)
      const m = hand(a); if (prev) { const d = prev.angleTo(m.palm) * DEG; if (d > worst) { worst = d; at = t; } } prev = m.palm.clone();
    }
    expect(worst, `the palm never jumps (worst at ${at.toFixed(3)} s), 480 steps a second`).toBeLessThan(15);
  });

  it("from one position to the next the hand goes round the shoulder in an arc, never through it", () => {
    const pivot = new THREE.Vector3(-0.2, 1.42, 0);
    for (const [from, to] of [["side", "high"], ["low", "high"], ["rest", "overhead"], ["loR", "hiR"], ["guard", "hiR"], ["high", "smash"]]) {
      let nearest = 9;
      for (let i = 0; i <= 100; i++) nearest = Math.min(nearest, swordAt(i / 100, [[0, from], [1, to]], (u: number) => u).h.distanceTo(pivot));
      expect(nearest, `${from} to ${to}: the hand's nearest to the shoulder`).toBeGreaterThan(0.26);
    }
  });

  it("a cut from high on one side to low on the other passes with the point forward, at an even pace: no whip through the middle", () => {
    let fwd = -1, fastest = 0, prev: THREE.Vector3 | null = null;
    for (let i = 0; i <= 200; i++) { const d = swordAt(i / 200, [[0, "hiR"], [1, "loL"]], (u: number) => u).d; fwd = Math.max(fwd, d.z); if (prev) fastest = Math.max(fastest, prev.angleTo(d) * DEG); prev = d.clone(); }
    expect(fwd, "the point comes forward on the way").toBeGreaterThan(0.75);
    expect(fastest, "the blade's quickest turn in one of 200 steps (an even turn through 150° is 0.75°)").toBeLessThan(2.6);
  });

  it("the body turns with the sword without a jerk: the chest never jumps", () => {
    const seq: [number, string][] = [[0, "guard"], [0.4, "backR"], [0.8, "overhead"], [1.2, "hiL"], [1.6, "outL"], [2.0, "hiR"], [2.4, "guard"]];
    const a = fighter(); let prev: THREE.Quaternion | null = null, worst = 0, at = 0;
    for (let i = 0; i <= 2.4 * 480; i++) {
      const t = i / 480; a.h.B.spine05.quaternion.identity(); a.h.B.spine03.quaternion.identity(); applySword(a, swordAt(t, seq), {}); a.h.update();
      const q = a.h.B.spine03.getWorldQuaternion(new THREE.Quaternion()); if (prev) { const d = prev.angleTo(q) * DEG; if (d > worst) { worst = d; at = t; } } prev = q;
    }
    expect(worst, `the chest's biggest turn in one of 480 steps a second (at ${at.toFixed(3)} s)`).toBeLessThan(1.5);
  });

  it("a hand's breadth away is the same grip: moving the sword a little never turns the hand over", () => {
    for (const n of names) {
      const a = fighter(), P = key(n); applySword(a, P, {}); const m0 = hand(a);
      for (const [dx, dy, dz] of [[0.01, 0, 0], [-0.01, 0, 0], [0, 0.01, 0], [0, -0.01, 0], [0, 0, 0.01], [0, 0, -0.01]]) {
        const b = fighter(); applySword(b, { h: P.h.clone().add(new THREE.Vector3(dx, dy, dz)), d: P.d, e: P.e }, {});
        expect(hand(b).palm.angleTo(m0.palm) * DEG, `${n}: the palm, with the hand moved by ${[dx, dy, dz]}`).toBeLessThan(15);
      }
      for (const [dx, dy, dz] of [[0.04, 0, 0], [-0.04, 0, 0], [0, 0.04, 0], [0, -0.04, 0], [0, 0, 0.04], [0, 0, -0.04]]) {
        const b = fighter(); applySword(b, { h: P.h, d: P.d.clone().add(new THREE.Vector3(dx, dy, dz)).normalize(), e: P.e }, {});
        expect(hand(b).palm.angleTo(m0.palm) * DEG, `${n}: the palm, with the blade turned by ${[dx, dy, dz]}`).toBeLessThan(15);
      }
    }
  });

  it("the forearm turns no further than a forearm turns: the thumb stays within a quarter turn of where the elbow's hinge leaves it", () => {
    for (const n of names) {
      const a = fighter(); applySword(a, key(n), {});
      const m = hand(a), h = a.h, sh = h.wpos("upperarm01.R"), el = h.wpos("lowerarm01.R"), fore = m.wrist.clone().sub(el).normalize(), up = el.clone().sub(sh).normalize();
      // unturned, the thumb lies in the arm's own plane, on the side the elbow folds to (a handshake)
      const rest = up.clone().negate().addScaledVector(fore, up.dot(fore)).normalize(), thumb = m.thumbSide.clone().addScaledVector(fore, -m.thumbSide.dot(fore)).normalize();
      expect(rest.angleTo(thumb) * DEG, `${n}: the forearm's turn from its rest`).toBeLessThan(95);
    }
  });

  it("every sword position is a place an arm can hold a sword: the elbow folds no tighter than an elbow, the fist is clear of the head", () => {
    for (const n of names) {
      const a = fighter(); applySword(a, key(n), {});
      const m = hand(a), h = a.h, k = h.height / 1.8, R = h.rest;
      const sh = h.wpos("upperarm01.R"), el = h.wpos("lowerarm01.R"), l1 = sh.distanceTo(el), l2 = el.distanceTo(m.wrist);
      const fold = Math.acos(Math.max(-1, Math.min(1, (l1 * l1 + l2 * l2 - sh.distanceToSquared(m.wrist)) / (2 * l1 * l2)))) * DEG; // the angle inside the elbow
      expect(fold, `${n}: the angle inside the elbow`).toBeGreaterThan(38);
      const skull = h.wpos("head").lerp(h.B.head.localToWorld(R.head.tail.clone().sub(R.head.head)), 0.45), q = h.B["wrist.R"].getWorldQuaternion(new THREE.Quaternion());
      const grip = m.wrist.clone().add(h.hand.R.fwd.clone().multiplyScalar(0.085 * k).add(h.hand.R.back.clone().multiplyScalar(-0.03 * k)).applyQuaternion(q));
      expect(grip.distanceTo(skull), `${n}: the fist's distance from the middle of the head`).toBeGreaterThan(0.17 * k);
    }
  });

  it("the elbow goes where an elbow goes: out to the side under a raised hand, below a thrust, behind a hanging arm", () => {
    const pose = (n: string) => { const a = fighter(); applySword(a, key(n), {}); const m = hand(a), sh = a.h.wpos("upperarm01.R"), el = a.h.wpos("lowerarm01.R"); return { sh, el, wr: m.wrist, mid: sh.clone().lerp(m.wrist, 0.5) }; };
    for (const n of ["high", "overhead", "hiR"]) { const p = pose(n); expect(p.el.x - p.mid.x, `${n}: the elbow is out to the fighter's right of the arm's line`).toBeLessThan(-0.04); expect(p.el.y, `${n}: the elbow stays below the hand`).toBeLessThan(p.wr.y); }
    for (const n of ["guard", "midF", "block", "chamber"]) { const p = pose(n); expect(p.el.y - p.mid.y, `${n}: the elbow hangs below the arm's line`).toBeLessThan(-0.02); }
    for (const n of ["rest", "side"]) { const p = pose(n); expect(p.el.z - p.mid.z, `${n}: the elbow is behind the arm's line`).toBeLessThan(0); expect(Math.abs(p.el.x - p.mid.x), `${n}: the elbow is not winged out`).toBeLessThan(0.09); }
  });

  it("an arm reaching out does not snap straight: the elbow opens a little at a time, and is straight only well beyond its reach", () => {
    const elbow = (z: number) => { const a = fighter(); applySword(a, { h: new THREE.Vector3(-0.1, 1.32, z), d: SW.point.d, e: SW.point.e }, {}); const m = hand(a), sh = a.h.wpos("upperarm01.R"), el = a.h.wpos("lowerarm01.R"), l1 = sh.distanceTo(el), l2 = el.distanceTo(m.wrist); return Math.acos(Math.max(-1, Math.min(1, (l1 * l1 + l2 * l2 - sh.distanceToSquared(m.wrist)) / (2 * l1 * l2)))) * DEG; };
    let prev = elbow(0.3), worst = 0, at = 0;
    for (let z = 0.305; z <= 0.9; z += 0.005) { const e = elbow(z); if (Math.abs(e - prev) > worst) { worst = Math.abs(e - prev); at = z; } prev = e; }
    expect(worst, `the most the elbow opens for 5 mm more reach (at z = ${at.toFixed(3)})`).toBeLessThan(4);
    expect(elbow(0.9), "far beyond reach, the arm is straight").toBeGreaterThan(168);
  });

  it("two hands on the hilt: the left hand lies on the hilt below the right, a wrist that is a wrist", () => {
    for (const n of ["guard", "high", "low", "overhead"]) {
      const a = fighter(), P = key(n); applySword(a, P, { two: true });
      const r = hand(a, "R"), l = hand(a, "L"), k = a.h.height / 1.8;
      expect(l.bend, `${n}: left wrist bend`).toBeLessThan(70);
      // the left hand's grip: on the sword's line, towards the pommel
      const H = a.h.hand.L, q = a.h.B["wrist.L"].getWorldQuaternion(new THREE.Quaternion()), grip = l.wrist.clone().add(H.fwd.clone().multiplyScalar(0.085 * k).add(H.back.clone().multiplyScalar(-0.03 * k)).applyQuaternion(q));
      const off = grip.clone().sub(r.at), along = off.dot(r.blade), away = off.clone().addScaledVector(r.blade, -along).length();
      if (l.stretch > 0.985) continue; // out of the left arm's reach from here: it comes as near as it can
      expect(away, `${n}: the left hand is on the hilt's line`).toBeLessThan(0.05);
      expect(along, `${n}: below the right hand, towards the pommel`).toBeLessThan(-0.03);
    }
  });
});
