import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { cpSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

// 27 Nov 2026, after "the visuals are plain, the children are the man copied, the walk is stiff": CodeScape got an outdoor world
// (nature.js: a sky that also lights the scene, earth, trees with leaves in the wind, grass, dust, the far side, birds; school.js: a school
// block, the stage and flag, a compound wall) and real children (people.js makeKid: a child's face and build, five haircuts, a half-sleeved
// shirt). They must build in node, so the film's deep review can walk through a film that uses them, and the review must stay clean.
const cine = path.resolve(__dirname, "../desktop/film/cine");
const FILM = `// @engine codescape
import { THREE, V3 } from './engine.js';
import { sky, ground, tree, grass, dust, horizon, birds } from './nature.js';
import { schoolBlock, stage, compoundWall } from './school.js';
import { makeKid } from './people.js';
import { refineHands } from './motion.js';
import { planWalk, stepper, stand, restArm } from './body.js';
export const META = { name: 'outdoor', fps: 30, order: ['a'] };
export const S = { a: { dur: 2, about: 'a school ground and two children: one walks, one stands', build: async E => {
    sky(E, { sun: [0.1, 0.45, -1] }); await ground(E); await schoolBlock(E, { at: [0, -20] }); await stage(E, { at: [0, 9] }); await compoundWall(E, { z: -30 });
    await tree(E, { at: [-8, -6], leaves: 300 }); grass(E, (x, z) => Math.abs(x) > 4 ? 0.5 : 0, { n: 300 }); dust(E, { n: 50 }); horizon(E, { houses: 5 }); birds(E, { n: 2 });
    const kids = ['fringe', 'crew'].map((h, i) => { const p = makeKid(E, { hair: h, seed: i + 1 }); refineHands(p, { armFrom: 0.1, armK: 0.82 }); p.root.scale.setScalar(0.84); for (const k in p.L) p.L[k] *= 0.84; p.size = 0.84; return p; });
    return { kids, plan: planWalk([[-1.5, 0], [1.5, 0]], { size: 0.84, speed: 1.1, step: 0.55, t0: 0.1 }) }; },
  frame(t, c) { stepper(c.kids[0], c.plan, t); stand(c.kids[1], 0, 1.5, 0, t); restArm(c.kids[1], 'l', t); restArm(c.kids[1], 'r', t); for (const p of c.kids) if (p.sleeveTick) p.sleeveTick();
    return { p: [0, 1.2, 5], t: [0, 0.8, 0], fov: 40, ao: 0.8, haze: [10, 0.01, 0.3], sun: [10, 30, -60], rays: 0.4 }; } } };
`;
describe("the outdoor world and the children", () => {
  it("builds in node and passes the deep review (a walking child, a standing child, every set piece)", () => {
    const dir = mkdtempSync(path.join(tmpdir(), "csout-"));
    try {
      cpSync(path.join(cine, "web/codescape"), dir, { recursive: true }); cpSync(path.join(cine, "web/three.module.js"), path.join(dir, "three.module.js")); writeFileSync(path.join(dir, "film.js"), FILM);
      let out = ""; try { out = execFileSync(process.execPath, [path.join(cine, "tools/review/run.mjs"), dir], { encoding: "utf8", timeout: 240_000 }); } catch (e) { out = String((e as { stdout?: string }).stdout ?? e); }
      expect(out).toContain("REVIEW: clean");
    } finally { rmSync(dir, { recursive: true, force: true }); }
  }, 300_000);
  // 27 Nov 2026, after "a richer scale of this world, more realistic bodies, a more dramatic opening": a whole school at once (crowd.js:
  // instanced children with faces, plaits and folded hands, walking in the vertex shader), hills far off, a cycle stand, and folds in the cloth.
  it("a crowd of children, hills and a cycle stand build in node and the review stays clean while the crowd walks off", () => {
    const dir = mkdtempSync(path.join(tmpdir(), "cscrowd-"));
    const film = `// @engine codescape
import { THREE, V3 } from './engine.js';
import { sky, ground, hills } from './nature.js';
import { cycleShed } from './school.js';
import { assembly } from './crowd.js';
import { makeKid } from './people.js';
import { stand, restArm } from './body.js';
export const META = { name: 'crowd', fps: 30, order: ['a'] };
export const S = { a: { dur: 2, about: 'a school assembly that turns and walks back to class', build: async E => {
    sky(E, { sun: [0.1, 0.45, -1] }); await ground(E); hills(E, { r: 160 }); cycleShed(E, { at: [10, -8], n: 6 });
    const crowd = assembly(E, { cols: 6, rows: 4, at: [-2.5, -3] }); for (const q of crowd.kids) q.walk = { dir: new V3(0, 0, -1), yaw: Math.PI, t0: 0.2 + 0.1 * q.c, speed: 1.1 };
    const kid = makeKid(E, { hair: 'curly', seed: 2 }); return { crowd, kid }; },
  frame(t, c) { stand(c.kid, 0, 1.5, 0, t); restArm(c.kid, 'l', t); restArm(c.kid, 'r', t); if (c.kid.sleeveTick) c.kid.sleeveTick();
    if (c.crowd.kids.length !== 24) throw new Error('crowd size ' + c.crowd.kids.length);
    return { p: [0, 3, 8], t: [0, 0.6, -2], fov: 40, ao: 0.8 }; } } };
`;
    try {
      cpSync(path.join(cine, "web/codescape"), dir, { recursive: true }); cpSync(path.join(cine, "web/three.module.js"), path.join(dir, "three.module.js")); writeFileSync(path.join(dir, "film.js"), film);
      let out = ""; try { out = execFileSync(process.execPath, [path.join(cine, "tools/review/run.mjs"), dir], { encoding: "utf8", timeout: 240_000 }); } catch (e) { out = String((e as { stdout?: string }).stdout ?? e); }
      expect(out).toContain("REVIEW: clean");
    } finally { rmSync(dir, { recursive: true, force: true }); }
  }, 300_000);
});
