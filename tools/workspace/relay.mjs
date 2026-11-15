// A relay of this workspace's own for VideoStudio 2's Claude link (the app is started with VSD_LINK_WORKER pointing here).
//   node relay.mjs serve                      the relay (127.0.0.1:8787); state in /home/claude/ws/relay/
//   node relay.mjs run '<commands json>' [--wait 55] [--continue]     queue a batch for the app, wait for the result
//   node relay.mjs result <jobId> [--wait 55]                          a job that was still running
//   node relay.mjs status
// Pictures in results (data: URLs) are saved under /home/claude/ws/relay/img/ and replaced by their paths.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
const PORT = 8787, DIR = '/home/claude/ws/relay', IMG = path.join(DIR, 'img'), FILES = path.join(DIR, 'files');
const [cmd, ...rest] = process.argv.slice(2), flag = (n, d) => { const i = rest.indexOf(`--${n}`); return i < 0 ? d : (rest[i + 1] ?? true); };

if (cmd === 'serve') {
  fs.mkdirSync(IMG, { recursive: true }); fs.mkdirSync(FILES, { recursive: true });
  const queue = [], jobs = new Map(), waiters = [], files = new Map(); let seq = 0, lastPoll = 0, info = null;
  const body = (req) => new Promise((res) => { const ch = []; req.on('data', (c) => ch.push(c)); req.on('end', () => res(Buffer.concat(ch))); });
  const json = (res, code, o) => { const t = JSON.stringify(o); res.writeHead(code, { 'content-type': 'application/json', 'access-control-allow-origin': '*' }); res.end(t); };
  const wake = () => { while (waiters.length && queue.length) waiters.shift()(); };
  const finish = (j, results, state) => { j.results = results; j.state = state; j.done = true; j.at = Date.now(); for (const w of j.waiters.splice(0)) w(); };
  http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x'), p = url.pathname.split('/').filter(Boolean);
    if (req.method === 'OPTIONS') { res.writeHead(204, { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' }); return res.end(); }
    try {
      if (p[0] === 'app' && p[2] === 'next') { // the app collects work
        lastPoll = Date.now(); if (url.searchParams.get('info')) { try { info = JSON.parse(url.searchParams.get('info')); } catch { /* ignore */ } }
        const wait = Math.min(25, +(url.searchParams.get('wait') ?? 25)) * 1000;
        if (!queue.length) await new Promise((r) => { const t = setTimeout(r, wait); waiters.push(() => { clearTimeout(t); r(); }); req.on('close', () => { clearTimeout(t); r(); }); });
        if (!queue.length) { res.writeHead(204, { 'access-control-allow-origin': '*' }); return res.end(); }
        const out = queue.splice(0); for (const j of out) jobs.get(j.id).started = Date.now();
        return json(res, 200, { jobs: out });
      }
      if (p[0] === 'app' && p[2] === 'progress' && req.method === 'POST') { const b = JSON.parse((await body(req)).toString()); const j = jobs.get(b.id); if (j) j.progress = b.state; return json(res, 200, { ok: true }); }
      if (p[0] === 'app' && p[2] === 'result' && req.method === 'POST') {
        const b = JSON.parse((await body(req)).toString()), j = jobs.get(b.id); if (!j) return json(res, 404, { error: 'no such job' });
        if (b.parts) { j.parts ??= []; j.parts[b.part] = b.text; if (j.parts.filter((x) => x != null).length === b.parts) finish(j, JSON.parse(j.parts.join('')), b.state); } else finish(j, b.results, b.state);
        return json(res, 200, { ok: true });
      }
      if (p[0] === 'app' && p[2] === 'files') { // files the app sends over, or collects
        if (req.method === 'POST' && !p[3]) { const b = JSON.parse((await body(req)).toString()), id = `f${++seq}`; files.set(id, { id, name: b.name, mime: b.mime, size: b.size, path: path.join(FILES, `${id}_${String(b.name).replace(/[^\w.\-]+/g, '_')}`), got: 0 }); fs.writeFileSync(files.get(id).path, ''); return json(res, 200, { id, chunkSize: 1_400_000 }); }
        if (req.method === 'PUT' && p[3]) { const f = files.get(p[3]); if (!f) return json(res, 404, {}); const b = await body(req); fs.appendFileSync(f.path, b); f.got += b.length; return json(res, 200, { ok: true, got: f.got }); }
        if (req.method === 'GET' && !p[3]) return json(res, 200, { files: [...files.values()] });
        if (req.method === 'GET' && p[3]) { const f = files.get(p[3]); if (!f) return json(res, 404, {}); res.writeHead(200, { 'content-type': f.mime || 'application/octet-stream', 'access-control-allow-origin': '*' }); return fs.createReadStream(f.path).pipe(res); }
        if (req.method === 'DELETE' && p[3]) { files.delete(p[3]); return json(res, 200, { ok: true }); }
      }
      if (p[0] === 'run' && req.method === 'POST') { // this workspace queues work
        const b = JSON.parse((await body(req)).toString()), id = `j${++seq}`, j = { id, commands: b.commands, continueOnError: !!b.continueOnError, queued: Date.now(), waiters: [], done: false };
        jobs.set(id, j); queue.push({ id, commands: j.commands, continueOnError: j.continueOnError }); wake();
        return json(res, 200, { id });
      }
      if (p[0] === 'job' && p[1]) {
        const j = jobs.get(p[1]); if (!j) return json(res, 404, { error: 'no such job' });
        const wait = Math.min(600, +(url.searchParams.get('wait') ?? 0)) * 1000;
        if (!j.done && wait) await new Promise((r) => { const t = setTimeout(r, wait); j.waiters.push(() => { clearTimeout(t); r(); }); });
        return json(res, 200, { id: j.id, done: j.done, started: !!j.started, progress: j.progress ?? null, results: j.done ? j.results : null, state: j.state ?? null });
      }
      if (p[0] === 'status') return json(res, 200, { app: info, lastPollSecondsAgo: lastPoll ? Math.round((Date.now() - lastPoll) / 1000) : null, queued: queue.length, running: [...jobs.values()].filter((j) => j.started && !j.done).map((j) => ({ id: j.id, progress: j.progress })) });
      json(res, 404, { error: 'not found' });
    } catch (e) { json(res, 500, { error: String(e?.message ?? e) }); }
  }).listen(PORT, '127.0.0.1', () => console.log(`relay on 127.0.0.1:${PORT}`));
} else {
  const base = `http://127.0.0.1:${PORT}`, wait = +flag('wait', 55);
  let n = 0; const stamp = Date.now().toString(36);
  const save = (o) => { // pictures out of the result into files
    if (typeof o === 'string') { const m = /^data:(image|audio|video)\/([\w.+-]+);base64,/.exec(o.slice(0, 64)); if (m) { fs.mkdirSync(IMG, { recursive: true }); const f = path.join(IMG, `${stamp}_${n++}.${m[2].replace('jpeg', 'jpg').replace('mpeg', 'mp3')}`); fs.writeFileSync(f, Buffer.from(o.slice(o.indexOf(',') + 1), 'base64')); return `[file ${f}]`; } return o; }
    if (Array.isArray(o)) return o.map(save);
    if (o && typeof o === 'object') { const r = {}; for (const [k, v] of Object.entries(o)) r[k] = save(v); return r; }
    return o;
  };
  const show = async (id) => { const r = await (await fetch(`${base}/job/${id}?wait=${wait}`)).json(); if (!r.done) console.log(JSON.stringify({ job: id, running: true, progress: r.progress })); else console.log(JSON.stringify(save(r.results), null, flag('pretty', false) ? 1 : 0)); };
  if (cmd === 'run') { const commands = JSON.parse(rest[0].startsWith('@') ? fs.readFileSync(rest[0].slice(1), 'utf8') : rest[0]); const { id } = await (await fetch(`${base}/run`, { method: 'POST', body: JSON.stringify({ commands, continueOnError: rest.includes('--continue') }) })).json(); await show(id); }
  else if (cmd === 'result') await show(rest[0]);
  else if (cmd === 'status') console.log(JSON.stringify(await (await fetch(`${base}/status`)).json()));
  else console.log('usage: relay.mjs serve | run <json|@file> [--wait s] [--continue] | result <id> | status');
}
