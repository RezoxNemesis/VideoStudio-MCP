import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { cpSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

// 27 Nov 2026: a closed underside was added to the trouser seat. Its points grew by one and computeVertexNormals kept the old normal
// list, one entry short. No code looked wrong, small check sheets looked right, and the full-size render on the GPU had a black block
// over half the picture for the first two seconds. Three things now stand between such a mesh and a sent film:
//   the deep review names a mesh that is not whole; the engine never blurs a pixel that is not a number; a finished render is looked
//   over for a solid black block.
const cine = path.resolve(__dirname, "../desktop/film/cine");
const film = (mesh: string) => `// @engine codescape
import { THREE } from './engine.js';
export const META = { name: 'mesh', fps: 30, order: ['a'] };
export const S = { a: { dur: 1, about: 'one mesh', build: async E => { ${mesh} E.scene.add(new THREE.Mesh(g, new THREE.MeshStandardMaterial({ color: '#808080' }))); return {}; },
  frame() { return { p: [0, 1, 3], t: [0, 1, 0], fov: 40 }; } } };
`;
const review = (mesh: string) => {
  const dir = mkdtempSync(path.join(tmpdir(), "csmesh-"));
  try {
    cpSync(path.join(cine, "web/codescape"), dir, { recursive: true }); cpSync(path.join(cine, "web/three.module.js"), path.join(dir, "three.module.js")); writeFileSync(path.join(dir, "film.js"), film(mesh));
    try { return execFileSync(process.execPath, [path.join(cine, "tools/review/run.mjs"), dir], { encoding: "utf8", timeout: 120_000 }); } catch (e) { return String((e as { stdout?: string }).stdout ?? e); }
  } finally { rmSync(dir, { recursive: true, force: true }); }
};
// a square with normals; then a fifth point and two more triangles are added, as the seat's underside was
const GROWN = `const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0], 3)); g.setIndex([0, 1, 2, 0, 2, 3]); g.computeVertexNormals();
  g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0.5, 0.5, 0.3], 3)); g.setIndex([0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4]);`;

describe("a mesh that is not whole", () => {
  it("is named by the deep review when its normal list is a point short", () => {
    const out = review(GROWN + " g.computeVertexNormals();");
    expect(out).toContain("a mesh is not whole");
    expect(out).toContain("its normal list has 4 entries for 5 points");
    expect(out).not.toContain("REVIEW: clean");
  });
  it("passes once the old list is thrown away before the normals are made again", () => {
    expect(review(GROWN + " g.deleteAttribute('normal'); g.computeVertexNormals();")).toContain("REVIEW: clean");
  });
  it("is named when a triangle uses a point that is not there, or a number is not finite", () => {
    expect(review(`const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0, 1, 0, 0, 1, 1, 0], 3)); g.setIndex([0, 1, 3]);`)).toContain("a triangle uses point 3 of 3");
    expect(review(`const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0, 1, 0, 0, 1, NaN, 0], 3)); g.setIndex([0, 1, 2]);`)).toContain("a number that is not finite");
  });
});

describe("the look over a finished render", () => {
  const { blockShare } = createRequire(import.meta.url)("../desktop/electron/cine.cjs") as { blockShare: (a: Uint8Array, n: number) => number };
  const N = 96, pic = (paint: (x: number, y: number) => number) => { const a = new Uint8Array(N * N); for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) a[y * N + x] = paint(x, y); return a; };
  it("finds a solid black block", () => {
    expect(blockShare(pic((x, y) => (x > 5 && y > 20 && y < 73 ? 0 : 140)), N)).toBeGreaterThan(0.4);   // the block as it was: half the frame
    expect(blockShare(pic((x, y) => (x >= 40 && x < 60 && y >= 40 && y < 60 ? 2 : 90)), N)).toBeGreaterThan(0.03);
  });
  it("leaves a fade, black bars, a dark scene and a clean picture alone", () => {
    expect(blockShare(pic(() => 0), N)).toBe(0);                                               // a fade to black
    expect(blockShare(pic((x, y) => (y < 12 || y >= 84 ? 0 : 120)), N)).toBe(0);               // bars above and below
    expect(blockShare(pic((x, y) => ((x * 7 + y * 13) % 5 === 0 ? 0 : 30)), N)).toBe(0);       // black scattered through a dark scene
    expect(blockShare(pic((x, y) => 60 + ((x + y) % 40)), N)).toBe(0);
  });

  it("tells of a stretch that is wholly black and cut in and out (a lost graphics context), and leaves fades alone", () => {
    const { blackCuts } = createRequire(import.meta.url)("../desktop/electron/cine.cjs") as { blackCuts: (lit: number[], fps: number) => { from: number; to: number }[] };
    // the live shot as the owner's PC first drew it: a lit film, a second of nothing, lit again (six pictures a second)
    expect(blackCuts([120, 121, 119, -1, -1, -1, -1, -1, -1, 101, 102, 101], 6)).toEqual([{ from: 0.5, to: 1.5 }]);
    expect(blackCuts([-1, -1, -1, 110, 112], 6)).toEqual([{ from: 0, to: 0.5 }]);            // black from the first picture, then a cut to the room
    expect(blackCuts([118, 90, 50, 14, 3, -1, -1, -1, 2, 20, 70, 118], 6)).toEqual([]);      // a fade to black and back
    expect(blackCuts([-1, -1, -1, 4, 12, 40, 90], 6)).toEqual([]);                           // a film that fades in from black
    expect(blackCuts([-1, -1, -1, -1], 6)).toEqual([]);                                      // nothing but black is not a cut
    expect(blackCuts([120, 118, 119], 6)).toEqual([]);
  });
});
