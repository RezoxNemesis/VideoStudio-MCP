// List the world items of a shot's set inside a box (to find what blocks a camera): node devtests/setitems.mjs <dir> <shot> x0 x1 z0 z1
import { openProject, shotFrom } from '../kit/project.js';
const [dir, shot, x0, x1, z0, z1] = process.argv.slice(2);
const P = await openProject(dir);
const sh = shotFrom(P, shot, 0);
let sc = sh.set({ shot: sh });
const W = sc?.layers?.world ?? [];
console.log('world items', W.length);
for (const it of W) { const p = it.p; if (!Array.isArray(p)) continue; if (p[0] >= +x0 && p[0] <= +x1 && p[2] >= +z0 && p[2] <= +z1) console.log(it.k ?? it.kind ?? it.type ?? Object.keys(it).join('|'), p.map((v) => +v.toFixed(2)).join(','), it.s ?? it.size ?? it.h ?? ''); }
