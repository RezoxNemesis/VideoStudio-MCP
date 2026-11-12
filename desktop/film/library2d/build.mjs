// Build the 2D library: every generator → SVG files, an index, PNG thumbnails and (with --sheets) contact sheets.
//   node library2d/build.mjs [out dir] [--sheets] [--only fx,props]
import fs from 'node:fs';
import path from 'node:path';
import { createCanvas, loadImage } from '@napi-rs/canvas';
import { GENERATORS } from './index.js';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const args = process.argv.slice(2), OUT = path.resolve(args.find((a) => !a.startsWith('--') && !/^[a-z,]+$/.test(a) || a.includes('/')) ?? path.join(HERE, 'out'));
const only = (() => { const i = args.indexOf('--only'); return i >= 0 ? args[i + 1].split(',') : null; })();
const sheets = args.includes('--sheets');
const t0 = Date.now();
fs.mkdirSync(path.join(OUT, 'thumbs'), { recursive: true });
const index = [], byCat = {};
for (const [cat, gen] of Object.entries(GENERATORS)) {
  if (only && !only.includes(cat)) continue;
  const list = gen();
  fs.mkdirSync(path.join(OUT, cat), { recursive: true });
  for (const a of list) {
    const file = `${cat}/${a.id}.svg`; fs.writeFileSync(path.join(OUT, file), a.svg);
    const layers = a.layers ? a.layers.map((L, k) => { const lf = `${cat}/${a.id}.L${k}.svg`; fs.writeFileSync(path.join(OUT, lf), L.svg); return { name: L.name, depth: L.depth, file: lf, speed: L.speed }; }) : undefined;
    const parts = a.parts ? Object.fromEntries(Object.entries(a.parts).map(([k, p]) => { const pf = `${cat}/${a.id}.${k}.svg`; fs.writeFileSync(path.join(OUT, pf), p.svg); return [k, { file: pf, pivot: p.pivot, parent: p.parent ?? null, at: p.at ?? null, z: p.z ?? 0, w: p.w, h: p.h }]; })) : undefined;
    const e = { id: a.id, name: a.name, cat: a.cat ?? cat, sub: a.sub, tags: a.tags, w: a.w, h: a.h, file, blend: a.blend, fullscreen: a.fullscreen, tile: a.tile, frames: a.frames, fps: a.fps, layers, parts, rig: a.rig, sets: a.sets, anchor: a.anchor, pixel: a.pixel, joints: a.joints, ground: a.ground, palette: a.palette };
    for (const k of Object.keys(e)) if (e[k] === undefined) delete e[k];
    index.push(e); (byCat[cat] ??= []).push(e);
  }
}
// thumbnails: a 192 px picture of every asset on a checker (so white things show)
const T = 192;
for (const e of index) {
  const img = await loadImage(path.join(OUT, e.file)), c = createCanvas(T, T), g = c.getContext('2d');
  g.fillStyle = '#20232b'; g.fillRect(0, 0, T, T); g.fillStyle = '#2a2e38'; for (let y = 0; y < T; y += 16) for (let x = (y / 16) % 2 ? 16 : 0; x < T; x += 32) g.fillRect(x, y, 16, 16);
  const k = Math.min((T - 12) / e.w, (T - 12) / e.h), w = e.w * k, h = e.h * k; g.drawImage(img, (T - w) / 2, (T - h) / 2, w, h);
  fs.writeFileSync(path.join(OUT, 'thumbs', `${e.id}.png`), c.toBuffer('image/png'));
}
fs.writeFileSync(path.join(OUT, 'index.json'), JSON.stringify({ version: 1, count: index.length, built: new Date().toISOString(), license: 'VideoStudio original assets, drawn by code (MIT, same as the app)', cats: Object.fromEntries(Object.entries(byCat).map(([k, v]) => [k, v.length])), assets: index }));
if (sheets) {
  fs.mkdirSync(path.join(OUT, 'sheets'), { recursive: true });
  for (const [cat, list] of Object.entries(byCat)) {
    for (let p = 0; p * 48 < list.length; p++) {
      const page = list.slice(p * 48, p * 48 + 48), cols = 8, rows = Math.ceil(page.length / cols), c = createCanvas(cols * T, rows * (T + 18)), g = c.getContext('2d');
      g.fillStyle = '#15171d'; g.fillRect(0, 0, c.width, c.height);
      for (let i = 0; i < page.length; i++) { const img = await loadImage(path.join(OUT, 'thumbs', `${page[i].id}.png`)); const x = (i % cols) * T, y = Math.floor(i / cols) * (T + 18); g.drawImage(img, x, y); g.fillStyle = '#c9cfdb'; g.font = '11px sans-serif'; g.fillText(page[i].id.slice(0, 30), x + 4, y + T + 13); }
      fs.writeFileSync(path.join(OUT, 'sheets', `${cat}_${p + 1}.jpg`), c.toBuffer('image/jpeg', 85));
    }
  }
}
console.log(`${index.length} assets (${Object.entries(byCat).map(([k, v]) => `${k} ${v.length}`).join(', ')}) in ${((Date.now() - t0) / 1000).toFixed(1)} s → ${OUT}`);
