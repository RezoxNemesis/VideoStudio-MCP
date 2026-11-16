// node devtests/bodyinfo.mjs <film dir> shot@t actor — the body's rest shape in numbers: bones (rest head → tail), parts
// (name, material, vertices, box), landmarks. For designing clothes and hair by measure.
import { register } from 'node:module';
register('../runner/hooks.mjs', import.meta.url);
const { openProject, shotFrom } = await import('../kit/project.js');
const { ShotRun } = await import('../engine/shot.js');
const [dir, shot, actor] = process.argv.slice(2), P = await openProject(dir), [id, t] = shot.split('@');
const run = new ShotRun(shotFrom(P, id, +t), P.CAST, { lang: P.config.langs[0], W: 192, H: 108, fps: P.config.fps, noGrain: true }); run.draw(0);
const B = run.ctx.poses[actor].body.B, f = (v) => v.toFixed(3);
console.log('height', B.height, 'landmarks', JSON.stringify(Object.fromEntries(Object.entries(B.landmarks).map(([k, v]) => [k, Array.isArray(v) ? v.map((x) => +x.toFixed(3)) : v]))));
if (process.argv[5] !== 'nobones') B.names.forEach((n, i) => { if (/finger|toe|eye|tongue|jaw|orbic|levator|special|risorius|oculi|temporal|metacarpal/.test(n)) return; console.log(n.padEnd(16), [0, 1, 2].map((k) => f(B.head[i * 3 + k])).join(' '), '→', [0, 1, 2].map((k) => f(B.tail[i * 3 + k])).join(' '), 'parent', B.names[B.parent[i]] ?? '-'); });
for (const p of B.parts) { const lo = [1e9, 1e9, 1e9], hi = [-1e9, -1e9, -1e9]; for (let v = 0; v < p.n; v++) for (let k = 0; k < 3; k++) { const x = p.pos[v * 3 + k]; if (x < lo[k]) lo[k] = x; if (x > hi[k]) hi[k] = x; } console.log('part', p.name.padEnd(10), p.material.padEnd(8), 'n', p.n, 'tris', p.idx.length / 3, 'box', lo.map(f).join(' '), '…', hi.map(f).join(' '), JSON.stringify(p.params)); }
// --fixture <file>: the body's rest shape as a test fixture (bones, landmarks, the skin with its weights), gzipped JSON with base64 arrays
const fx = process.argv.indexOf('--fixture');
if (fx > 0) {
  const { gzipSync } = await import('node:zlib'), { writeFileSync } = await import('node:fs');
  const b64 = (a) => Buffer.from(a.buffer, a.byteOffset, a.byteLength).toString('base64');
  const sk = B.parts.find((p) => p.material === 'skin');
  const out = { height: B.height, names: B.names, parent: [...B.parent], head: b64(B.head), tail: b64(B.tail), landmarks: B.landmarks,
    skin: { n: sk.n, pos: b64(sk.rest0 ?? sk.pos), idx: b64(Uint32Array.from(sk.idx)), si: b64(sk.si), sw: b64(sk.sw) } };
  const z = gzipSync(JSON.stringify(out), { level: 9 }); writeFileSync(process.argv[fx + 1], z); console.log('fixture', process.argv[fx + 1], z.length, 'bytes');
}
