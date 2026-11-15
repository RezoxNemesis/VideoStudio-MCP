import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { HumanFactory, type CharData, type PartData } from "../src/stage/human/factory";
import { Pack } from "../src/stage/pack";
import { makeChar, garmentsOf } from "../src/stage/presets";
import { Human } from "../src/stage/engine/human.js";
import { Actor, KITS, scabbardRest, trunkPoints } from "../src/stage/engine/actors.js";
import { playSword, wearDraw } from "../src/stage/engine/fight.js";
import { gripPoint } from "../src/stage/engine/crowd.js";

// What a dressed body wears must lie on it the way worn things do: a coat over the trousers under it (no seat of the
// trousers coming through the back of the coat), a sword outside the clothes at the hip (not through the body), its hilt
// free of the belly so that a hand can close on it.
const ASSETS = [process.env.VS_STAGE_ASSETS_DIR, "desktop/stage-assets", "/home/claude/ws/squashfs-root/resources/stage-assets"].find((d) => d && existsSync(`${d}/human.pack`));

/** The hero's cut: wide trousers, a bagged tunic, a long coat over both. */
const HERO = {
  preset: "swordsman", age: 22, muscle: 0.55, weight: 0.38, proportions: 0.9,
  garments: garmentsOf([
    { kind: "trousers", name: "trousers", wide0: 1.12, wide1: 1.4, offset: 0.012 },
    { kind: "boots", name: "boots", material: "leather", shaft: 0.3, offset: 0.01 },
    { kind: "tunic", name: "tunic", flare: 1.3, droop: 0.03, bag: 0.2, offset: 0.012, below_hip: 0 },
    { kind: "skirt", name: "coat", length: 0.42, flare: 0.12, offset: 0.02, hemTrim: true },
    { kind: "wraps", name: "wraps", material: "leather", offset: 0.006 },
  ]),
};
/** A warlord's cut: armour over a tunic, a short skirt of plates. */
const WARLORD = {
  preset: "warlord", age: 57, muscle: 0.92, weight: 0.45, proportions: 1,
  garments: garmentsOf([
    { kind: "trousers", name: "trousers", wide0: 1.06, wide1: 1.14, offset: 0.012 },
    { kind: "boots", name: "boots", material: "leather", shaft: 0.34, offset: 0.012 },
    { kind: "tunic", name: "tunic", sleeve: "short", flare: 1.05, droop: 0, bag: 0.03, offset: 0.012, below_hip: 0, vneck: false },
    { kind: "skirt", name: "coat", length: 0.3, flare: 0.06, offset: 0.02 },
    { kind: "cuirass", name: "cuirass", material: "metal", offset: 0.022, cap: 0.085 },
    { kind: "vambrace", name: "vambrace", material: "metal", offset: 0.014 },
    { kind: "greave", name: "greave", material: "metal", offset: 0.016 },
  ]),
};
const ARM = /arm|wrist|finger|metacarpal|clavicle|shoulder/;

describe.skipIf(!ASSETS)("what a dressed body wears", () => {
  const factory = (() => { if (!ASSETS) return null as unknown as HumanFactory; const b = readFileSync(`${ASSETS}/human.pack`); return new HumanFactory(new Pack(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer)); })();
  const built = new Map<string, CharData>();
  const body = (name: string, req: Record<string, unknown>): CharData => { let d = built.get(name); if (!d) { d = factory.build(name, makeChar(req as never).char); built.set(name, d); } return d; };
  const part = (d: CharData, n: string): PartData => { const p = d.parts.find((x) => x.name === n); if (!p) throw new Error(`no part ${n}: ${d.parts.map((x) => x.name).join(" ")}`); return p; };
  /** The points of a part that belong to the trunk and legs (not to an arm), in the rest pose. */
  const trunk = (d: CharData, p: PartData): THREE.Vector3[] => {
    const out: THREE.Vector3[] = [];
    for (let i = 0; i < p.vcount; i++) {
      let best = 0; for (let c = 1; c < 4; c++) if (p.skinWeight[i * 4 + c]! > p.skinWeight[i * 4 + best]!) best = c;
      if (!ARM.test(d.bones[p.skinIndex[i * 4 + best]!]!.name)) out.push(new THREE.Vector3(p.pos[i * 3]!, p.pos[i * 3 + 1]!, p.pos[i * 3 + 2]!));
    }
    return out;
  };
  /** How far along a ray (origin o, unit direction r) a triangle is met; Infinity when it is not. */
  const hit = (o: THREE.Vector3, r: THREE.Vector3, a: THREE.Vector3, b: THREE.Vector3, c: THREE.Vector3): number => {
    const e1 = b.clone().sub(a), e2 = c.clone().sub(a), pv = r.clone().cross(e2), det = e1.dot(pv); if (Math.abs(det) < 1e-12) return Infinity;
    const tv = o.clone().sub(a), u = tv.dot(pv) / det; if (u < 0 || u > 1) return Infinity;
    const qv = tv.cross(e1), v = r.dot(qv) / det; if (v < 0 || u + v > 1) return Infinity;
    const t = e2.dot(qv) / det; return t > 0 ? t : Infinity;
  };

  for (const [who, req] of [["the hero", HERO], ["a warlord", WARLORD]] as const) {
    it(`${who}: a coat lies over the trousers and the tunic under it — nothing under it comes through`, () => {
      const d = body(who, req), coat = part(d, "coat");
      const V = (i: number) => new THREE.Vector3(coat.pos[i * 3]!, coat.pos[i * 3 + 1]!, coat.pos[i * 3 + 2]!);
      const tris: [THREE.Vector3, THREE.Vector3, THREE.Vector3][] = []; let top = -Infinity, bot = Infinity;
      for (let t = 0; t < coat.idx.length; t += 3) tris.push([V(coat.idx[t]!), V(coat.idx[t + 1]!), V(coat.idx[t + 2]!)]);
      for (let i = 0; i < coat.vcount; i++) { top = Math.max(top, coat.pos[i * 3 + 1]!); bot = Math.min(bot, coat.pos[i * 3 + 1]!); }
      let through = 0, seen = 0, worst = "";
      for (const under of ["trousers", "tunic"]) {
        for (const u of trunk(d, part(d, under))) {
          if (u.y > top || u.y < bot) continue;
          // straight out from the body's upright line through the point: under a coat, the coat is met on the way out,
          // and not within a hair of the point; a point that has come through has the coat behind it, on the way in
          const r = new THREE.Vector3(u.x, 0, u.z), reach = r.length(); if (reach < 1e-4) continue; r.multiplyScalar(1 / reach);
          const back = r.clone().negate(); let outside = Infinity, inside = Infinity;
          for (const [a, b, c] of tris) { outside = Math.min(outside, hit(u, r, a, b, c)); inside = Math.min(inside, hit(u, back, a, b, c)); }
          if (outside < Infinity) seen++;
          if (inside < reach || outside < 0.002) { through++; if (!worst) worst = `${under} at ${u.x.toFixed(3)}, ${u.y.toFixed(3)}, ${u.z.toFixed(3)}`; }
        }
      }
      expect(seen, "points of the trousers and tunic under the coat").toBeGreaterThan(200);
      expect(through, `points that come through the coat (the first: ${worst})`).toBe(0);
    });

    it(`${who}: where a coat covers the trousers they are cut away — in no pose can their seat come through it`, () => {
      // (a coat and the trousers under it are carried by different bones: lying under it at rest is not enough once the body moves)
      const d = body(who, req), coat = part(d, "coat"), tr = part(d, "trousers"); let top = -Infinity, bot = Infinity, under = 0, below = 0;
      for (let i = 0; i < coat.vcount; i++) { top = Math.max(top, coat.pos[i * 3 + 1]!); bot = Math.min(bot, coat.pos[i * 3 + 1]!); }
      for (let i = 0; i < tr.vcount; i++) { const y = tr.pos[i * 3 + 1]!; if (y > bot + 0.2 && y < top - 0.1) under++; if (y < bot) below++; }
      expect(under, "points of the trousers left under the coat (between a span above its hem and its top)").toBe(0);
      expect(below, "the trousers are still there below the hem").toBeGreaterThan(50);
    });

    it(`${who}: the sword rides at the left hip outside the clothes, its hilt before the belly where a hand can close on it`, () => {
      const d = body(who, req);
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const h: any = new Human(d), a: any = new Actor(h, null, "x"); (KITS as any).orez(a, { cloak: false });
      const R = scabbardRest(h) as { pos: THREE.Vector3; dir: THREE.Vector3 };
      const sc = a.props.scabbard as THREE.Object3D, sw = a.props.sword as THREE.Object3D;
      h.update(); h.root.updateMatrixWorld(true);
      const mouth = sc.getWorldPosition(new THREE.Vector3()), dir = new THREE.Vector3(0, 1, 0).applyQuaternion(sc.getWorldQuaternion(new THREE.Quaternion())), len = sw.userData.len as number;
      expect(mouth.distanceTo(R.pos), "the kit hangs the scabbard where scabbardRest says").toBeLessThan(1e-4);
      expect(dir.angleTo(R.dir) * 180 / Math.PI).toBeLessThan(0.1);
      const tip = mouth.clone().addScaledVector(dir, len + 0.03), hipY = d.landmarks.hipY as number;
      expect(mouth.x, "the mouth of the scabbard is at his left").toBeGreaterThan(0.1);
      expect(mouth.z, "before the hip").toBeGreaterThan(0.03);
      expect(mouth.y - hipY, "at the height of the sash").toBeGreaterThan(0.03); expect(mouth.y - hipY).toBeLessThan(0.16);
      expect(tip.z, "its length goes back").toBeLessThan(-0.3);
      expect(tip.y, "and down").toBeLessThan(mouth.y - 0.15);
      expect(tip.x, "and out past the hip, not across behind the body").toBeGreaterThan(mouth.x);
      // the sword sits in it up to the guard: the guard on the scabbard's mouth, the whole grip before it
      const grip = sw.getWorldPosition(new THREE.Vector3()), blade = new THREE.Vector3(0, 1, 0).applyQuaternion(sw.getWorldQuaternion(new THREE.Quaternion()));
      expect(blade.angleTo(dir) * 180 / Math.PI, "the blade lies down the scabbard").toBeLessThan(0.5);
      expect(grip.clone().addScaledVector(blade, 0.135).distanceTo(mouth), "the guard sits on the mouth of the scabbard").toBeLessThan(0.012);
      expect((sc.userData.len as number) - len, "the scabbard is made to the blade").toBeGreaterThan(0.01);
      // nothing of the dressed trunk and legs is inside the scabbard or the hilt, and the grip has room for a hand
      const pommel = grip.clone().addScaledVector(blade, -0.13), guard = grip.clone().addScaledVector(blade, 0.13);
      const seg = (p: THREE.Vector3, a0: THREE.Vector3, b0: THREE.Vector3) => { const ab = b0.clone().sub(a0), t = Math.max(0, Math.min(1, p.clone().sub(a0).dot(ab) / ab.lengthSq())); return p.distanceTo(a0.clone().addScaledVector(ab, t)); };
      let nearScabbard = Infinity, nearGrip = Infinity;
      for (const p of d.parts) for (const v of trunk(d, p)) { nearScabbard = Math.min(nearScabbard, seg(v, mouth, tip)); nearGrip = Math.min(nearGrip, seg(v, pommel, guard)); }
      expect(nearScabbard, "the scabbard lies outside everything he wears (its own half thickness clear)").toBeGreaterThan(0.02);
      expect(nearScabbard, "and against the clothes, not out in the air").toBeLessThan(0.07);
      expect(nearGrip, "the grip stands clear of the belly: room for the fingers of the hand that takes it").toBeGreaterThan(0.045);
    });

    it(`${who}: a draw — the right hand on the hilt, the left on the scabbard; the blade rides the scabbard out and passes to the hand without a jump; nothing goes through the body`, () => {
      const d = body(who, req);
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const h: any = new Human(d), a: any = new Actor(h, null, "x"); (KITS as any).orez(a, { cloak: false });
      const sc = a.props.scabbard as THREE.Object3D, sw = a.props.sword as THREE.Object3D, k = h.height / 1.8, rest = h.snapshot();
      // (from the scabbard straight to a position out at the right: the engine takes the blade round the front itself)
      const seq: [number, string][] = [[0, "sheath"], [1, "sheath"], [1.2, "unsheath"], [1.55, "loR"], [2, "loR"]];
      const T = trunkPoints(h) as Float32Array;
      /** How far a point is outside the dressed body (negative: inside it): against the outline of the body at the point's own height, round its middle. */
      const clear = (p: THREE.Vector3) => {
        let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity; const near: number[] = [];
        for (let i = 0; i < T.length; i += 3) if (Math.abs(T[i + 1]! - p.y) < 0.025) { near.push(T[i]!, T[i + 2]!); x0 = Math.min(x0, T[i]!); x1 = Math.max(x1, T[i]!); z0 = Math.min(z0, T[i + 2]!); z1 = Math.max(z1, T[i + 2]!); }
        if (!near.length) return Infinity;
        const cx = (x0 + x1) / 2, cz = (z0 + z1) / 2, ang = Math.atan2(p.z - cz, p.x - cx); let rim = 0;
        for (let i = 0; i < near.length; i += 2) { let d = Math.abs(Math.atan2(near[i + 1]! - cz, near[i]! - cx) - ang); if (d > Math.PI) d = 2 * Math.PI - d; if (d < 0.22) rim = Math.max(rim, Math.hypot(near[i]! - cx, near[i + 1]! - cz)); }
        return Math.hypot(p.x - cx, p.z - cz) - rim;
      };
      /** The body at a moment of the draw, the way the Stage plays a sword overlay. */
      const at = (t: number) => {
        h.blendTo(rest, 1); wearDraw(a, null, null);
        const { own, ph } = (playSword as any)(a, t, seq, {}) as { own: any; ph: { sheathed: boolean; slide: number; give: number } };
        h.update(); h.root.updateMatrixWorld(true);
        const q = sw.getWorldQuaternion(new THREE.Quaternion()), dir = new THREE.Vector3(0, 1, 0).applyQuaternion(q), pos = sw.getWorldPosition(new THREE.Vector3());
        const scDir = new THREE.Vector3(0, 1, 0).applyQuaternion(sc.getWorldQuaternion(new THREE.Quaternion())), mouth = sc.getWorldPosition(new THREE.Vector3());
        return { own, ph, pos, dir, edge: new THREE.Vector3(0, 0, 1).applyQuaternion(q), scDir, mouth, right: gripPoint(a, "R") as THREE.Vector3, left: gripPoint(a, "L") as THREE.Vector3 };
      };
      const stretch = (sd: string) => { const R = h.rest, full = R[`upperarm01.${sd}`].head.distanceTo(R[`lowerarm01.${sd}`].head) + R[`lowerarm01.${sd}`].head.distanceTo(R[`wrist.${sd}`].head); return h.wpos(`upperarm01.${sd}`).distanceTo(h.wpos(`wrist.${sd}`)) / full; };

      // the hands take hold
      const m0 = at(0.5);
      expect(m0.own, "a fighter with a scabbard has their own sheath and unsheath").toBeTruthy();
      expect(sw.parent === sc, "the sword is still in its scabbard").toBe(true);
      expect(m0.right.distanceTo(m0.pos), "the right hand is on the grip").toBeLessThan(0.02);
      expect(m0.left.distanceTo(m0.mouth.clone().addScaledVector(m0.scDir, 0.075 * k)), "the left hand holds the scabbard behind its mouth").toBeLessThan(0.03);
      expect(stretch("R"), "the right arm reaches the hilt with the elbow still bent").toBeLessThan(0.95);
      expect(stretch("L"), "the left arm is bent at the hip").toBeLessThan(0.9);
      for (const sd of ["R", "L"]) for (const f of [0, 0.3, 0.55, 0.8, 1]) expect(clear(h.wpos(`lowerarm01.${sd}`).lerp(h.wpos(`wrist.${sd}`), f)), `the ${sd} forearm lies outside the body, not through it (${f} of the way from elbow to wrist)`).toBeGreaterThan(0.035);
      expect(clear(m0.right), "the hand on the hilt is free of the belly").toBeGreaterThan(0.045);
      expect(Math.abs(m0.edge.x), "the scabbard is turned flat for the draw: the edge outward").toBeGreaterThan(0.85);

      // the blade slides out along the scabbard, which the left hand draws back
      const m1 = at(1.1);
      expect(sw.parent === sc, "half way out the sword still rides in the scabbard").toBe(true);
      expect(m1.dir.angleTo(m1.scDir) * 180 / Math.PI).toBeLessThan(0.5);
      expect(m1.mouth.distanceTo(m1.pos), "it has slid out").toBeGreaterThan(m0.mouth.distanceTo(m0.pos) + 0.15);
      expect(m1.mouth.clone().sub(m0.mouth).dot(m0.scDir), "the scabbard is drawn back along its own line").toBeGreaterThan(0.05);
      expect(m1.right.distanceTo(m1.pos), "the right hand is still on the grip").toBeLessThan(0.025);
      expect(m1.left.distanceTo(m1.mouth.clone().addScaledVector(m1.scDir, 0.075 * k)), "the left hand still on the scabbard").toBeLessThan(0.035);

      // out of the scabbard into the hand: no jump
      const mA = at(1.2 - 1e-4), mB = at(1.2 + 1e-4);
      expect(mA.ph.sheathed && !mB.ph.sheathed, "at the end of the slide the sword passes to the hand").toBe(true);
      expect(mA.pos.distanceTo(mB.pos), "the sword is where it was (metres)").toBeLessThan(0.03);
      expect(mA.dir.angleTo(mB.dir) * 180 / Math.PI, "and points where it pointed (degrees)").toBeLessThan(8);
      expect(mA.edge.angleTo(mB.edge) * 180 / Math.PI, "its edge turned no more than a little (degrees)").toBeLessThan(35);
      const inside = (sw.userData.len as number) - (mA.mouth.distanceTo(mA.pos) - (sc.userData.seat as number));
      expect(inside, "by then only the last of the blade is still in the scabbard (metres)").toBeLessThan(0.22);

      // the swing out and the hold: the blade never passes through the body
      let worst = Infinity, when = 0;
      for (let t = 1.2 + 1 / 96; t <= 2; t += 1 / 96) {
        const m = at(t);
        for (const f of [0.15, 0.3, 0.45, 0.6, 0.75, 0.9, 1]) { const c = clear(m.pos.clone().addScaledVector(m.dir, 0.135 + (sw.userData.len as number) * f)); if (c < worst) { worst = c; when = t; } }
      }
      expect(worst, `the blade stays clear of the body all the way out (closest at ${when.toFixed(2)} s)`).toBeGreaterThan(0.03);
      const m2 = at(2);
      expect(m2.mouth.distanceTo(m0.mouth.clone().addScaledVector(m0.scDir, 0)), "afterwards the scabbard hangs where it hung").toBeLessThan(0.01);
    });
  }
});
