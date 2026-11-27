#!/usr/bin/env node
// The deep review in a workspace (no GPU, no browser): node run.mjs <film dir> [shot]   — the same measures as cine.review in the app.
// The film's own engine.js is used (a CodeScape film carries or resolves one); canvases, fonts and fetch are stood in for.
import path from 'node:path'; import url from 'node:url';
const noop = () => {}; const ctx = new Proxy({}, { get: (t, k) => k === 'measureText' ? () => ({ width: 10 }) : (k === 'createLinearGradient' || k === 'createRadialGradient') ? () => ({ addColorStop: noop }) : noop, set: () => true });
globalThis.document = { createElement: () => ({ width: 1, height: 1, getContext: () => ctx, style: {} }), fonts: { add: noop } }; globalThis.FontFace = class { load() { return Promise.resolve(); } }; const readFile = (await import('node:fs')).readFileSync;
// a film's own data files (a motion clip beside it) are read from disk, as the app serves them; anything else gets an empty answer
globalThis.fetch = async (u) => { const s = String(u); if (s.startsWith('file:')) { try { const b = readFile(new URL(s)); return { ok: true, arrayBuffer: async () => b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength), json: async () => JSON.parse(b.toString('utf8')), text: async () => b.toString('utf8') }; } catch (e) { return { ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0), json: async () => ({}), text: async () => '' }; } } return { ok: true, arrayBuffer: async () => new ArrayBuffer(0), json: async () => ({}), text: async () => '' }; };
const dir = path.resolve(process.argv[2] || '.'), here = path.dirname(url.fileURLToPath(import.meta.url)); const imp = f => import(url.pathToFileURL(f).href);
const eng = await imp(path.join(dir, 'engine.js')), F = await imp(path.join(dir, 'film.js')), { reviewFilm, reviewText } = await imp(path.join(here, '..', '..', 'web', 'review.js'));
const R = await reviewFilm(F, () => ({ scene: new eng.THREE.Scene(), setEnv: noop, updaters: [] }), eng.V3, { shots: process.argv[3] ? [process.argv[3]] : null });
console.log(reviewText(R)); process.exit(R.ok ? 0 : 1);
