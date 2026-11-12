// Set pieces from the 3D model library (buildings, furniture, castle and dungeon parts, food, vehicles…): a .gltf file
// read into coloured triangle groups, placed in the world and drawn by the cel painter — flat tones, a shadow side,
// cast shadows, inked edges — so a town or a room has real form, parallax and detail and still looks drawn.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { V, hex, clamp } from './core.js';
import { ENV } from './env.js';
import { CelPainter, normalsOf } from './body.js';

const HERE = path.dirname(new URL(import.meta.url).pathname);
/** Where the library is: beside the film engine, in the app and in the source tree alike. */
export function modelsDir() { return ENV.modelsDir ?? path.join(HERE, '..', '..', 'stage-assets', 'models'); }
let index = null;
/** The library's catalogue: { packs: [{ id, scale, … }], models: [{ id, pack, name, file, size }] } (empty when not installed). */
export function modelIndex() {
  if (index) return index;
  try { index = JSON.parse(fs.readFileSync(path.join(modelsDir(), 'index.json'), 'utf8')); } catch { index = { packs: [], models: [] }; }
  index.byId = Object.fromEntries(index.models.map((m) => [m.id, m])); index.scale = Object.fromEntries(index.packs.map((p) => [p.id, p.scale ?? 1]));
  return index;
}
const cache = new Map(), texCache = new Map();
const COMP = { 5120: [Int8Array, 1], 5121: [Uint8Array, 1], 5122: [Int16Array, 2], 5123: [Uint16Array, 2], 5125: [Uint32Array, 4], 5126: [Float32Array, 4] }, NUM = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT4: 16 };
/** A PNG read at once (8 bits a channel; grey, RGB, palette, with or without alpha; not interlaced) → { w, h, d: RGBA bytes }. */
function readPng(buf) {
  let o = 8, w = 0, h = 0, bd = 8, ct = 6, pal = null, trns = null; const idat = [];
  while (o < buf.length) { const len = buf.readUInt32BE(o), type = buf.toString('latin1', o + 4, o + 8), d = buf.subarray(o + 8, o + 8 + len); o += 12 + len;
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); bd = d[8]; ct = d[9]; if (d[12]) throw new Error('interlaced'); } else if (type === 'PLTE') pal = d; else if (type === 'tRNS') trns = d; else if (type === 'IDAT') idat.push(d); else if (type === 'IEND') break; }
  if (bd !== 8) throw new Error('bit depth');
  const ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ct], raw = zlib.inflateSync(Buffer.concat(idat)), bpl = w * ch, out = new Uint8Array(w * h * 4), cur = new Uint8Array(bpl); let prev = new Uint8Array(bpl);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (bpl + 1)], row = raw.subarray(y * (bpl + 1) + 1, (y + 1) * (bpl + 1));
    for (let x = 0; x < bpl; x++) { const a = x >= ch ? cur[x - ch] : 0, b = prev[x], c = x >= ch ? prev[x - ch] : 0; let v = row[x];
      if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1; else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; } cur[x] = v & 255; }
    for (let x = 0; x < w; x++) { const q = (y * w + x) * 4, s = x * ch;
      if (ct === 6) { out[q] = cur[s]; out[q + 1] = cur[s + 1]; out[q + 2] = cur[s + 2]; out[q + 3] = cur[s + 3]; } else if (ct === 2) { out[q] = cur[s]; out[q + 1] = cur[s + 1]; out[q + 2] = cur[s + 2]; out[q + 3] = 255; }
      else if (ct === 3) { const i = cur[s]; out[q] = pal[i * 3]; out[q + 1] = pal[i * 3 + 1]; out[q + 2] = pal[i * 3 + 2]; out[q + 3] = trns && i < trns.length ? trns[i] : 255; } else { out[q] = out[q + 1] = out[q + 2] = cur[s]; out[q + 3] = ct === 4 ? cur[s + 1] : 255; } }
    prev = Uint8Array.from(cur);
  }
  return { w, h, d: out };
}
function texture(file) {
  let t = texCache.get(file); if (t !== undefined) return t;
  try { t = readPng(fs.readFileSync(file)); } catch { t = null; }
  texCache.set(file, t); return t;
}
/** Read a model: { groups: [{ color: [r,g,b], pos (Float32Array, metres ×  the pack's scale, y up), idx }], min, max }. file: a library id ("medieval/barrel") or a path to a .gltf. */
export function loadModel(file) {
  const ix = modelIndex(), ent = ix.byId?.[file], full = ent ? path.join(modelsDir(), ent.file) : file, k0 = ent ? (ix.scale[ent.pack] ?? 1) : 1;
  let m = cache.get(full); if (m) return m;
  const g = JSON.parse(fs.readFileSync(full, 'utf8')), dir = path.dirname(full);
  const bufs = (g.buffers ?? []).map((b) => (b.uri?.startsWith('data:') ? Buffer.from(b.uri.slice(b.uri.indexOf(',') + 1), 'base64') : fs.readFileSync(path.join(dir, decodeURIComponent(b.uri)))));
  const acc = (i) => { const a = g.accessors[i], bv = g.bufferViews[a.bufferView], [T, sz] = COMP[a.componentType], n = NUM[a.type], stride = bv.byteStride ?? sz * n, buf = bufs[bv.buffer], off = (bv.byteOffset ?? 0) + (a.byteOffset ?? 0), out = new (a.componentType === 5126 ? Float32Array : Uint32Array)(a.count * n), dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    const rd = { 5120: (o) => dv.getInt8(o), 5121: (o) => dv.getUint8(o), 5122: (o) => dv.getInt16(o, true), 5123: (o) => dv.getUint16(o, true), 5125: (o) => dv.getUint32(o, true), 5126: (o) => dv.getFloat32(o, true) }[a.componentType];
    for (let i2 = 0; i2 < a.count; i2++) for (let c = 0; c < n; c++) out[i2 * n + c] = rd(off + i2 * stride + c * sz); void T; return out; };
  // node transforms
  const mat = (nd) => { if (nd.matrix) return nd.matrix; const t = nd.translation ?? [0, 0, 0], q = nd.rotation ?? [0, 0, 0, 1], s = nd.scale ?? [1, 1, 1], [x, y, z, w] = q;
    return [(1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y + z * w) * s[0], 2 * (x * z - y * w) * s[0], 0, 2 * (x * y - z * w) * s[1], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z + x * w) * s[1], 0, 2 * (x * z + y * w) * s[2], 2 * (y * z - x * w) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0, t[0], t[1], t[2], 1]; };
  const mul = (a, b) => { const o = new Array(16); for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]; return o; };
  const groups = new Map(); let mn = [1e9, 1e9, 1e9], mx = [-1e9, -1e9, -1e9];
  const walk = (ni, M) => {
    const nd = g.nodes[ni], W = mul(M, mat(nd));
    if (nd.mesh !== undefined) for (const pr of g.meshes[nd.mesh].primitives) {
      if (pr.attributes.POSITION === undefined || (pr.mode ?? 4) !== 4) continue;
      const P = acc(pr.attributes.POSITION), uv = pr.attributes.TEXCOORD_0 !== undefined ? acc(pr.attributes.TEXCOORD_0) : null, col = pr.attributes.COLOR_0 !== undefined ? acc(pr.attributes.COLOR_0) : null, nC = col ? NUM[g.accessors[pr.attributes.COLOR_0].type] : 0, colK = col && g.accessors[pr.attributes.COLOR_0].componentType !== 5126 ? 1 / (g.accessors[pr.attributes.COLOR_0].componentType === 5121 ? 255 : 65535) : 1;
      const I = pr.indices !== undefined ? acc(pr.indices) : Uint32Array.from({ length: P.length / 3 }, (_, i) => i);
      const mt = pr.material !== undefined ? g.materials[pr.material] : null, pbr = mt?.pbrMetallicRoughness ?? {}, bf = pbr.baseColorFactor ?? [1, 1, 1, 1];
      const ti = pbr.baseColorTexture?.index, img = ti !== undefined ? g.images?.[g.textures[ti].source] : null, tex = img?.uri && !img.uri.startsWith('data:') ? texture(path.join(dir, decodeURIComponent(img.uri))) : null;
      const n = P.length / 3, wp = new Float32Array(P.length);
      for (let v = 0; v < n; v++) { const x = P[v * 3], y = P[v * 3 + 1], z = P[v * 3 + 2]; wp[v * 3] = (W[0] * x + W[4] * y + W[8] * z + W[12]) * k0; wp[v * 3 + 1] = (W[1] * x + W[5] * y + W[9] * z + W[13]) * k0; wp[v * 3 + 2] = (W[2] * x + W[6] * y + W[10] * z + W[14]) * k0; for (let c = 0; c < 3; c++) { if (wp[v * 3 + c] < mn[c]) mn[c] = wp[v * 3 + c]; if (wp[v * 3 + c] > mx[c]) mx[c] = wp[v * 3 + c]; } }
      for (let t = 0; t < I.length; t += 3) {
        const a = I[t], b = I[t + 1], c = I[t + 2]; let r = bf[0], gg = bf[1], bb = bf[2];
        if (tex && uv) { const u = (uv[a * 2] + uv[b * 2] + uv[c * 2]) / 3, vv = (uv[a * 2 + 1] + uv[b * 2 + 1] + uv[c * 2 + 1]) / 3, X = clamp(Math.floor((u - Math.floor(u)) * tex.w), 0, tex.w - 1), Y = clamp(Math.floor((vv - Math.floor(vv)) * tex.h), 0, tex.h - 1), o = (Y * tex.w + X) * 4; r *= tex.d[o] / 255; gg *= tex.d[o + 1] / 255; bb *= tex.d[o + 2] / 255; }
        else { r = Math.pow(r, 1 / 2.2); gg = Math.pow(gg, 1 / 2.2); bb = Math.pow(bb, 1 / 2.2); }
        if (col) { r *= (col[a * nC] + col[b * nC] + col[c * nC]) / 3 * colK; gg *= (col[a * nC + 1] + col[b * nC + 1] + col[c * nC + 1]) / 3 * colK; bb *= (col[a * nC + 2] + col[b * nC + 2] + col[c * nC + 2]) / 3 * colK; }
        const key = (Math.round(r * 31) << 10) | (Math.round(gg * 31) << 5) | Math.round(bb * 31);
        let gp = groups.get(key); if (!gp) groups.set(key, (gp = { color: [Math.round(r * 255), Math.round(gg * 255), Math.round(bb * 255)], pos: [], idx: [], map: new Map() }));
        for (const v of [a, b, c]) { const kk = v + ni * 1e7; let j = gp.map.get(kk); if (j === undefined) { j = gp.pos.length / 3; gp.map.set(kk, j); gp.pos.push(wp[v * 3], wp[v * 3 + 1], wp[v * 3 + 2]); } gp.idx.push(j); }
      }
    }
    for (const ch of nd.children ?? []) walk(ch, W);
  };
  const I4 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
  for (const r of g.scenes?.[g.scene ?? 0]?.nodes ?? [0]) walk(r, I4);
  m = { groups: [...groups.values()].map((gp) => ({ color: gp.color, pos: Float32Array.from(gp.pos), idx: Uint32Array.from(gp.idx) })), min: mn, max: mx };
  cache.set(full, m); return m;
}

/**
 * A model as a thing of the world. at: [x, z] or [x, y, z]; o: { yaw (degrees), scale, tint: colour → every colour is
 * multiplied towards it, recolor: { '#from': '#to' }, lines: false (no inner lines), shadow: false (casts none) }.
 * Returns a world item for Scene.add('world', …).
 */
export function modelItem(file, at, o = {}) {
  const M = loadModel(file), k = o.scale ?? 1, yaw = (o.yaw ?? 0) * Math.PI / 180, c = Math.cos(yaw), s = Math.sin(yaw), p0 = at.length === 2 ? [at[0], 0, at[1]] : at;
  const parts = M.groups.map((gp) => { const n = gp.pos.length / 3, pos = new Float32Array(gp.pos.length); for (let v = 0; v < n; v++) { const x = gp.pos[v * 3] * k, y = gp.pos[v * 3 + 1] * k, z = gp.pos[v * 3 + 2] * k; pos[v * 3] = p0[0] + x * c + z * s; pos[v * 3 + 1] = p0[1] + y; pos[v * 3 + 2] = p0[2] - x * s + z * c; } return { color: gp.color, pos, idx: gp.idx, nrm: normalsFlat(pos, gp.idx) }; });
  const cx = (M.min[0] + M.max[0]) / 2 * k, cz = (M.min[2] + M.max[2]) / 2 * k, ctr = [p0[0] + cx * c + cz * s, p0[1] + (M.max[1] - M.min[1]) * k * 0.35, p0[2] - cx * s + cz * c], rad = Math.hypot((M.max[0] - M.min[0]), (M.max[1] - M.min[1]), (M.max[2] - M.min[2])) * k / 2;
  const tint = o.tint ? hex(o.tint) : null, hexOf = (cc) => '#' + cc.map((v) => Math.round(clamp(v, 0, 255)).toString(16).padStart(2, '0')).join('');
  let last = null;
  return {
    p: ctr, r: rad, bias: o.bias ?? 0, zone: o.zone, model: file,
    draw(g, cam, sc, t) {
      const zc = cam.toCam(ctr)[2]; if (zc < -rad) return;
      // nothing moved: the same picture as last frame
      const sig = `${cam.pos}|${cam.F}|${cam.f}|${cam.cx}|${cam.cy}|${cam.R}`;
      if (!last || last.sig !== sig) {
        const lit = sc.litAt(V.dist(cam.pos, ctr)), ppm = cam.ppm(Math.max(0.5, zc)), rs = cam.H / 1080, lw = clamp(0.0042 * ppm, 0.7 * rs, 2.2 * rs);
        const cp = new CelPainter(cam, { ss: 2, light: sc.sun, ink: hex(lit(o.ink ?? '#2a2430')), lw: lw * 1.6, lwIn: o.lines === false ? 0 : lw * 0.8, depthEdge: 0.06 });
        for (const pt of parts) {
          let col = pt.color; if (o.recolor) { const to = o.recolor[hexOf(col)]; if (to) col = hex(to); }
          if (tint) col = [col[0] * tint[0] / 255, col[1] * tint[1] / 255, col[2] * tint[2] / 255];
          const f = hex(lit(hexOf(col))), sh = hex(lit(hexOf([col[0] * 0.6, col[1] * 0.58, col[2] * 0.7])));
          cp.add(pt.pos, pt.idx, { fill: f, shade: sh, thr: 0.12, two: true, soft: true, edge: 2.2, cast: o.shadow !== false, line: o.lines === false ? false : undefined, group: o.lines === false ? 'm' : undefined }, pt.nrm);
        }
        last = { sig, spr: cp.finish() };
      }
      if (last.spr) g.drawImage(last.spr.canvas, last.spr.x, last.spr.y);
    },
  };
}
/** Flat-faced normals (set pieces are built of planes: every corner keeps its faces' own direction where they meet at an angle). */
function normalsFlat(pos, idx) { return normalsOf(pos, idx, new Float32Array(pos.length)); }
