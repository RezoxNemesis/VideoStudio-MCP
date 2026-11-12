import fs from 'node:fs';
import { fork } from 'node:child_process';
import { createCanvas, loadImage } from '@napi-rs/canvas';
const { PEOPLE_GROUPS } = await import('../kit/people.js');
const w = fork(new URL('../runner/worker.mjs', import.meta.url).pathname, [], { stdio: ['ignore', 'ignore', 'inherit', 'ipc'] });
let n = 0; const wait = new Map();
w.on('message', (m) => { if (m.ready) return; const r = wait.get(m.id); if (r && !m.event) { wait.delete(m.id); m.ok ? r.res(m.value) : r.rej(new Error(m.error)); } });
const ask = (method, params) => new Promise((res, rej) => { const id = 'x' + ++n; wait.set(id, { res, rej }); w.send({ id, method, params }); });
await new Promise((r) => setTimeout(r, 600));
const ids = PEOPLE_GROUPS.flatMap((g) => [`${g.id}_01`, `${g.id}_0${Math.min(4, g.count)}`]);
const cols = 14, W = 150, H = 240, cv = createCanvas(cols * W, Math.ceil(ids.length / cols) * H), g = cv.getContext('2d');
const t0 = Date.now();
for (const [i, id] of ids.entries()) {
  const r = await ask('personThumb', { id, size: 240 });
  g.drawImage(await loadImage(Buffer.from(r.jpeg, 'base64')), (i % cols) * W, Math.floor(i / cols) * H);
  g.fillStyle = '#000a'; g.fillRect((i % cols) * W, Math.floor(i / cols) * H + H - 16, W, 16); g.fillStyle = '#fff'; g.font = '11px sans-serif'; g.fillText(id, (i % cols) * W + 3, Math.floor(i / cols) * H + H - 4);
}
console.log(ids.length, 'pictures in', Date.now() - t0, 'ms');
fs.writeFileSync('/home/claude/vs2-notes/tests/out/people.jpg', cv.toBuffer('image/jpeg', 86));
w.kill();
