// Dev tool: give a baked actor another body — sculpted by the Stage's own character factory in this working tree, with
// no app running. For trying a face, a build or a costume cut quickly: the performance stays as it was solved (every
// bone keeps its turn; the bones are laid end to end again along the new skeleton), only the body changes.
//   node devtests/sculpt.mjs <film dir> <bake name> <actor id> '<character request json>' [--list prefix]
// The request is what stage.actor takes: { preset, body, origin, outfit, hair, height, age, gender, muscle, weight,
// proportions, detail: { "chin/chin-width-decr": 0.4 }, garments: [...], colors, look }.
// --list nose/   prints the names of the detail targets that start so, and stops.
// The first run keeps the app's own bake beside it as <name>.bin.app / <name>.json.app; --restore puts them back.
// The Stage's bodies come from human.pack: VS_STAGE_ASSETS_DIR, or desktop/stage-assets in this tree.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url)), repo = path.resolve(here, '../../..');
const args = process.argv.slice(2), flag = (n, d = null) => { const i = args.indexOf(`--${n}`); return i < 0 ? d : (args[i + 1] ?? true); };

/** The Stage's character code (TypeScript), bundled once for node. */
async function stage() {
  const { build } = await import(pathToFileURL(path.join(repo, 'node_modules/esbuild/lib/main.js')).href);
  const out = path.join(os.tmpdir(), `vs_sculpt_${process.pid}.mjs`), entry = path.join(os.tmpdir(), `vs_sculpt_${process.pid}_entry.ts`);
  fs.writeFileSync(entry, `export { HumanFactory } from ${JSON.stringify(path.join(repo, 'src/stage/human/factory.ts'))};\nexport { makeChar } from ${JSON.stringify(path.join(repo, 'src/stage/presets/index.ts'))};\nexport { Pack } from ${JSON.stringify(path.join(repo, 'src/stage/pack.ts'))};\n`);
  await build({ entryPoints: [entry], bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error', loader: { '.json': 'json' } });
  const m = await import(pathToFileURL(out).href); fs.rmSync(out); fs.rmSync(entry); return m;
}
function packFile() {
  for (const d of [process.env.VS_STAGE_ASSETS_DIR, path.join(repo, 'desktop/stage-assets')]) if (d && fs.existsSync(path.join(d, 'human.pack'))) return path.join(d, 'human.pack');
  throw new Error('human.pack not found: set VS_STAGE_ASSETS_DIR to the app\'s stage-assets folder');
}

const S = await stage();
const pk = fs.readFileSync(packFile()), factory = new S.HumanFactory(new S.Pack(pk.buffer.slice(pk.byteOffset, pk.byteOffset + pk.byteLength)));
if (args.includes('--list')) { console.log(factory.targets(String(flag('list', '') === true ? '' : flag('list', ''))).join('\n')); process.exit(0); }

const [dir, name, actorId, reqJson] = args;
const base = path.join(dir, '.vs/bake', name), bin = `${base}.bin`, js = `${base}.json`;
if (args.includes('--restore')) { fs.copyFileSync(`${bin}.app`, bin); if (fs.existsSync(`${js}.app`)) fs.copyFileSync(`${js}.app`, js); console.log('restored the app\'s bake'); process.exit(0); }
if (!fs.existsSync(`${bin}.app`)) fs.copyFileSync(bin, `${bin}.app`);
if (!fs.existsSync(`${js}.app`)) fs.copyFileSync(js, `${js}.app`);
const meta = JSON.parse(fs.readFileSync(js, 'utf8')), A = meta.actors.find((x) => x.id === actorId);
if (!A?.rig) throw new Error(`actor ${actorId} has no body in bake ${name}`);

const { char } = S.makeChar(JSON.parse(reqJson)), data = factory.build(actorId, char);
if (data.bones.length !== A.body.bones.length || data.bones.some((b, i) => b.name !== A.body.bones[i].name)) throw new Error('the new body has another skeleton than the baked one');

// the new body's arrays go on the end of the bake (what was there stays where it is)
const old = fs.readFileSync(bin), chunks = [old]; let at = old.length;
const put = (arr) => { const pad = (4 - (at % 4)) % 4; if (pad) { chunks.push(Buffer.alloc(pad)); at += pad; } const b = Buffer.from(arr.buffer, arr.byteOffset, arr.byteLength); chunks.push(b); const r = [at, b.length]; at += b.length; return r; };
const plain = (o) => Object.fromEntries(Object.entries(o ?? {}).filter(([, v]) => ['number', 'string', 'boolean'].includes(typeof v)));
const look = {}; for (const [k, v] of Object.entries(data.look ?? {})) if (v === null || ['number', 'string', 'boolean'].includes(typeof v) || (Array.isArray(v) && v.length <= 4 && v.every((x) => typeof x === 'number'))) look[k] = v;
const before = A.body.bones;
A.body = { name: data.name, height: data.height, scale: A.body.scale ?? 1, colors: data.look?.colors ?? {}, look, landmarks: data.landmarks, morphNames: data.morphNames ?? [],
  bones: data.bones.map((b) => ({ name: b.name, parent: b.parent, head: [...b.head], tail: [...b.tail] })),
  parts: data.parts.map((p) => ({ name: p.name, material: p.material, vcount: p.vcount, params: plain(p.params), pos: put(p.pos), idx: put(p.idx), skinIndex: put(p.skinIndex), skinWeight: put(p.skinWeight), ...(p.mask ? { mask: put(p.mask) } : {}), ...(p.morphs ? { morphs: p.morphs.map((m) => ({ i: put(m.i), d: put(m.d) })) } : {}) })) };
A.height = data.height;
const out = Buffer.concat(chunks);

// the performance on the new skeleton: every bone keeps its turn, and stands where its parent's turn puts it
const nb = data.bones.length, rig = new Float32Array(out.buffer, out.byteOffset + A.rig.data[0], A.rig.frames * nb * 7);
const rot = (q, v) => { const [x, y, z, w] = q, tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]); return [v[0] + w * tx + y * tz - z * ty, v[1] + w * ty + z * tx - x * tz, v[2] + w * tz + x * ty - y * tx]; };
const rootLift = data.bones[0].head[1] - before[0].head[1];
for (let f = 0; f < A.rig.frames; f++) for (let b = 0; b < nb; b++) {
  const o = (f * nb + b) * 7, par = data.bones[b].parent;
  if (par < 0) { rig[o + 1] += rootLift; continue; }
  const po = (f * nb + par) * 7, d = [0, 1, 2].map((c) => data.bones[b].head[c] - data.bones[par].head[c]), r = rot([rig[po + 3], rig[po + 4], rig[po + 5], rig[po + 6]], d);
  rig[o] = rig[po] + r[0]; rig[o + 1] = rig[po + 1] + r[1]; rig[o + 2] = rig[po + 2] + r[2];
}
// the 60-a-second joint tracks (what cameras and looks aim at) follow the rig
const tracks = new Float32Array(out.buffer, out.byteOffset + A.data[0], A.data[1] / 4), every = Math.round(meta.fps / A.rig.fps), bi = Object.fromEntries(data.bones.map((b, i) => [b.name, i]));
meta.joints.forEach((j, n) => {
  const tailEnd = j.endsWith(':tail'), b = bi[tailEnd ? j.slice(0, -5) : j]; if (b === undefined) return;
  const off = tailEnd ? [0, 1, 2].map((c) => data.bones[b].tail[c] - data.bones[b].head[c]) : null;
  for (let i = 0; i < meta.frames; i++) {
    const g = i / every, f0 = Math.min(A.rig.frames - 1, Math.floor(g)), f1 = Math.min(A.rig.frames - 1, f0 + 1), u = g - f0, p = [0, 0, 0];
    for (const [f, w] of [[f0, 1 - u], [f1, u]]) { const o = (f * nb + b) * 7, q = [rig[o + 3], rig[o + 4], rig[o + 5], rig[o + 6]], e = off ? rot(q, off) : [0, 0, 0]; for (let c = 0; c < 3; c++) p[c] += (rig[o + c] + e[c]) * w; }
    tracks.set(p, i * meta.stride + n * 3);
  }
});
fs.writeFileSync(bin, out); fs.writeFileSync(js, JSON.stringify(meta));
console.log(`${name}/${actorId}: a new body (${data.parts.map((p) => p.name).join(', ')}), ${(out.length / 1e6).toFixed(1)} MB`);
