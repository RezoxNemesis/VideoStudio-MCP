/**
 * VideoStudio Desktop link: the MCP endpoint Claude connects to, plus the mailbox the desktop app polls.
 *
 *   GET  /                          landing page
 *   GET  /mcp/<key>                 status page for people (the connector URL opened in a browser)
 *   POST /mcp/<key>                 MCP (Streamable HTTP, JSON responses)
 *   GET  /app/<key>/next?wait=25    the desktop app collects queued command batches (long poll, 204 when none)
 *   POST /app/<key>/progress        {id, state}
 *   POST /app/<key>/result          {id, results, state}  (results may be split: {id, part, parts, text})
 *   POST /app/<key>/files           start an upload {name, mime, size} → {id, chunkSize}
 *   PUT  /app/<key>/files/<id>/<n>  upload chunk n (raw bytes, ≤ 1.5 MB)
 *   GET  /app/<key>/files           inbox
 *   GET  /app/<key>/files/<id>      download (DELETE removes)
 *
 * Storage is one D1 database. The key in the URL is the only credential; only its SHA-256 is stored.
 * Results that carry pictures (data:image/...;base64) are returned to Claude as real MCP image content, so the
 * agent can look at frames, waveforms, spectrograms and screenshots of the app directly.
 */

export interface Env {
  DB: D1Database;
}

const VERSION = "1.0.0";
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
  "access-control-allow-headers": "content-type, mcp-session-id, mcp-protocol-version, authorization",
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...CORS } });

async function hashKey(key: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, device TEXT NOT NULL, commands TEXT NOT NULL, cont INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, state TEXT, parts INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, updated INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS jobs_device_status ON jobs (device, status, created)`,
  `CREATE TABLE IF NOT EXISTS job_parts (job TEXT NOT NULL, idx INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY (job, idx))`,
  `CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, last_seen INTEGER NOT NULL, info TEXT)`,
  `CREATE TABLE IF NOT EXISTS files (id TEXT PRIMARY KEY, device TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, chunks INTEGER NOT NULL, received INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS file_chunks (file TEXT NOT NULL, idx INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (file, idx))`,
];
const CHUNK = 1_500_000;
const MAX_FILE = 500 * 1024 * 1024;
let ready: Promise<void> | null = null;
function migrate(env: Env): Promise<void> {
  return (ready ??= env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s))).then(() => undefined));
}

const TOOLS = [
  {
    name: "desktop_run",
    description:
      "Run a batch of VideoStudio Desktop commands on the person's PC and return the results. Commands are objects like " +
      '{"op":"clip.add","text":"Hello"}. Strings like "$0.id" refer to the result of an earlier command in the same batch. ' +
      'Start with [{"op":"help"}] for every command and [{"op":"state"}] for the open project. ' +
      "Desktop extras: fs.roots / fs.list / fs.find (browse the person's folders), media.importPath (bring a file from the PC into the project), " +
      "audio.inspect (loudness, peaks, silences, tempo, beats, sections, plus waveform and spectrogram pictures), media.probe, " +
      "ui.screenshot (see the app), ui.workspace, render.start (MP4 into ~/Videos/VideoStudio). " +
      "Pictures in results (render.frame, media.thumb, audio.inspect, ui.screenshot) come back as images you can see. " +
      "Long jobs keep running after the wait; fetch them with desktop_result.",
    inputSchema: {
      type: "object",
      properties: {
        commands: { type: "array", items: { type: "object" }, description: "Commands to run in order." },
        continue_on_error: { type: "boolean", description: "Keep going after a failed command. Default false." },
        wait_seconds: { type: "number", description: "How long to wait for results (0-55). Default 45." },
      },
      required: ["commands"],
    },
  },
  {
    name: "desktop_result",
    description: "Status and results of a job queued with desktop_run that was still running.",
    inputSchema: { type: "object", properties: { job_id: { type: "string" }, wait_seconds: { type: "number" } }, required: ["job_id"] },
  },
  {
    name: "desktop_status",
    description: "Whether VideoStudio Desktop is open and linked (last poll time, app version, open project) and how many jobs are queued.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "desktop_files",
    description: 'Files waiting in the desktop inbox (sent through this link). Import one with desktop_run [{"op":"media.importInbox","fileId":"<id>"}].',
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "desktop_upload",
    description: "Put a small file (base64, ≤ 1 MB decoded) into the desktop inbox. Returns its file id for media.importInbox.",
    inputSchema: { type: "object", properties: { name: { type: "string" }, mime: { type: "string" }, data_base64: { type: "string" } }, required: ["name", "data_base64"] },
  },
];

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function readResults(env: Env, id: string, parts: number): Promise<unknown> {
  const { results } = await env.DB.prepare("SELECT idx, text FROM job_parts WHERE job = ? ORDER BY idx").bind(id).all<{ idx: number; text: string }>();
  if (results.length < parts) return null;
  try {
    return JSON.parse(results.map((r) => r.text).join(""));
  } catch {
    return null;
  }
}

async function waitFor(env: Env, device: string, id: string, seconds: number) {
  const until = Date.now() + Math.max(0, Math.min(55, seconds)) * 1000;
  let delay = 350;
  for (;;) {
    const row = await env.DB.prepare("SELECT status, state, parts FROM jobs WHERE id = ? AND device = ?").bind(id, device).first<{ status: string; state: string | null; parts: number }>();
    if (!row) return { job_id: id, status: "unknown" };
    if (row.status === "done") {
      const results = await readResults(env, id, row.parts);
      if (results !== null) return { job_id: id, status: "done", results, app: row.state ? JSON.parse(row.state) : undefined };
    }
    if (Date.now() >= until)
      return {
        job_id: id,
        status: row.status,
        progress: row.status === "running" && row.state ? JSON.parse(row.state) : undefined,
        note: row.status === "queued" ? "VideoStudio Desktop has not picked this up yet. Is the app open with the Claude link on?" : "Still working on the PC. Call desktop_result later.",
      };
    await sleep(delay);
    delay = Math.min(1500, delay * 1.4);
  }
}

/** Pull every data:image URL out of a result, so Claude gets them as images instead of base64 text. */
function extractImages(value: unknown, out: { data: string; mimeType: string }[]): unknown {
  if (typeof value === "string") {
    const m = /^data:(image\/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)$/.exec(value);
    if (m && out.length < 12) {
      out.push({ mimeType: m[1]!, data: m[2]! });
      return `[image ${out.length}]`;
    }
    return value.length > 20_000 ? value.slice(0, 20_000) + "…(cut)" : value;
  }
  if (Array.isArray(value)) return value.map((v) => extractImages(v, out));
  if (value && typeof value === "object") {
    const o: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) o[k] = extractImages(v, out);
    return o;
  }
  return value;
}

async function callTool(env: Env, device: string, name: string, args: Record<string, unknown>): Promise<unknown> {
  switch (name) {
    case "desktop_run": {
      const commands = args.commands;
      if (!Array.isArray(commands) || commands.length === 0) throw new Error("commands must be a non-empty array");
      const text = JSON.stringify(commands);
      if (text.length > 1_800_000) throw new Error("batch too large (keep it under 1.8 MB)");
      const id = crypto.randomUUID();
      const now = Date.now();
      const old = now - 3 * 86_400_000;
      await env.DB.batch([
        env.DB.prepare("INSERT INTO jobs (id, device, commands, cont, status, created, updated) VALUES (?, ?, ?, ?, 'queued', ?, ?)").bind(id, device, text, args.continue_on_error ? 1 : 0, now, now),
        env.DB.prepare("DELETE FROM job_parts WHERE job IN (SELECT id FROM jobs WHERE updated < ?)").bind(old),
        env.DB.prepare("DELETE FROM jobs WHERE updated < ?").bind(old),
      ]);
      return await waitFor(env, device, id, typeof args.wait_seconds === "number" ? args.wait_seconds : 45);
    }
    case "desktop_result":
      return await waitFor(env, device, String(args.job_id ?? ""), typeof args.wait_seconds === "number" ? args.wait_seconds : 30);
    case "desktop_files":
      return { files: await listFiles(env, device) };
    case "desktop_upload": {
      const bin = Uint8Array.from(atob(String(args.data_base64 ?? "")), (c) => c.charCodeAt(0));
      if (!bin.length || bin.length > 1_048_576) throw new Error("data must be 1 byte to 1 MB");
      const id = await createFile(env, device, String(args.name ?? "file"), String(args.mime ?? "application/octet-stream"), bin.length);
      await putChunk(env, device, id, 0, bin);
      return { file_id: id, size: bin.length };
    }
    case "desktop_status": {
      const d = await env.DB.prepare("SELECT last_seen, info FROM devices WHERE id = ?").bind(device).first<{ last_seen: number; info: string | null }>();
      const q = await env.DB.prepare("SELECT COUNT(*) AS n FROM jobs WHERE device = ? AND status IN ('queued','running')").bind(device).first<{ n: number }>();
      const ago = d ? Math.round((Date.now() - d.last_seen) / 1000) : null;
      return { connected: ago !== null && ago < 60, last_seen_seconds_ago: ago, app: d?.info ? JSON.parse(d.info) : undefined, pending_jobs: q?.n ?? 0 };
    }
  }
  throw new Error(`Unknown tool ${name}`);
}

async function mcp(req: Request, env: Env, device: string): Promise<Response> {
  const msg = (await req.json().catch(() => null)) as { id?: string | number; method?: string; params?: Record<string, unknown> } | unknown[] | null;
  if (!msg || Array.isArray(msg)) return json({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "Invalid request" } }, 400);
  const { id, method, params = {} } = msg;
  if (id === undefined) return new Response(null, { status: 202, headers: CORS });
  const ok = (result: unknown) => json({ jsonrpc: "2.0", id, result });
  try {
    switch (method) {
      case "initialize":
        return ok({
          protocolVersion: typeof params.protocolVersion === "string" ? params.protocolVersion : "2025-06-18",
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: "videostudio-desktop", version: VERSION },
          instructions:
            "Controls VideoStudio Desktop on the person's own PC: editing, colour, effects, drawing and 2D/3D animation, VolTune voices, music and sound design, rendering. " +
            'Call desktop_run with [{"op":"help"}] first, then [{"op":"state"}]. Use audio.inspect to read music, voices and sound effects before cutting to them.',
        });
      case "ping":
        return ok({});
      case "tools/list":
        return ok({ tools: TOOLS });
      case "tools/call": {
        const name = String(params.name ?? "");
        const args = (params.arguments ?? {}) as Record<string, unknown>;
        try {
          const out = await callTool(env, device, name, args);
          const images: { data: string; mimeType: string }[] = [];
          const clean = extractImages(out, images);
          return ok({ content: [{ type: "text", text: JSON.stringify(clean, null, 1) }, ...images.map((i) => ({ type: "image", data: i.data, mimeType: i.mimeType }))] });
        } catch (e) {
          return ok({ content: [{ type: "text", text: e instanceof Error ? e.message : String(e) }], isError: true });
        }
      }
      default:
        return json({ jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } });
    }
  } catch (e) {
    return json({ jsonrpc: "2.0", id, error: { code: -32603, message: e instanceof Error ? e.message : String(e) } });
  }
}

async function listFiles(env: Env, device: string) {
  const { results } = await env.DB.prepare("SELECT id, name, mime, size, chunks, received, created FROM files WHERE device = ? ORDER BY created DESC LIMIT 50").bind(device).all<{ id: string; name: string; mime: string; size: number; chunks: number; received: number; created: number }>();
  return results.map((f) => ({ id: f.id, name: f.name, mime: f.mime, size: f.size, complete: f.received >= f.chunks, created: f.created }));
}

async function createFile(env: Env, device: string, name: string, mime: string, size: number): Promise<string> {
  if (!(size > 0) || size > MAX_FILE) throw new Error(`size must be 1 byte to ${MAX_FILE} bytes`);
  const id = crypto.randomUUID();
  const now = Date.now();
  const old = await env.DB.prepare("SELECT id FROM files WHERE created < ?").bind(now - 2 * 86_400_000).all<{ id: string }>();
  const stmts = [env.DB.prepare("INSERT INTO files (id, device, name, mime, size, chunks, created) VALUES (?, ?, ?, ?, ?, ?, ?)").bind(id, device, name.slice(0, 200), mime.slice(0, 100), size, Math.ceil(size / CHUNK), now)];
  for (const o of old.results) stmts.push(env.DB.prepare("DELETE FROM file_chunks WHERE file = ?").bind(o.id), env.DB.prepare("DELETE FROM files WHERE id = ?").bind(o.id));
  await env.DB.batch(stmts);
  return id;
}

async function putChunk(env: Env, device: string, id: string, idx: number, data: Uint8Array): Promise<void> {
  const f = await env.DB.prepare("SELECT chunks FROM files WHERE id = ? AND device = ?").bind(id, device).first<{ chunks: number }>();
  if (!f) throw new Error("unknown file");
  if (!(idx >= 0 && idx < f.chunks) || data.length === 0 || data.length > CHUNK) throw new Error("bad chunk");
  const r = await env.DB.prepare("INSERT OR IGNORE INTO file_chunks (file, idx, data) VALUES (?, ?, ?)").bind(id, idx, data).run();
  if (r.meta.changes) await env.DB.prepare("UPDATE files SET received = received + 1 WHERE id = ?").bind(id).run();
}

async function files(req: Request, env: Env, device: string, parts: string[]): Promise<Response> {
  const id = parts[3];
  if (!id) {
    if (req.method === "GET") return json({ files: await listFiles(env, device) });
    if (req.method === "POST") {
      const b = (await req.json().catch(() => null)) as { name?: string; mime?: string; size?: number } | null;
      if (!b?.name || !b.size) return json({ error: "name and size required" }, 400);
      return json({ id: await createFile(env, device, b.name, b.mime || "application/octet-stream", Number(b.size)), chunkSize: CHUNK });
    }
  } else if (parts[4] !== undefined && req.method === "PUT") {
    await putChunk(env, device, id, Number(parts[4]), new Uint8Array(await req.arrayBuffer()));
    return json({ ok: true });
  } else if (req.method === "DELETE") {
    await env.DB.batch([env.DB.prepare("DELETE FROM file_chunks WHERE file = (SELECT id FROM files WHERE id = ? AND device = ?)").bind(id, device), env.DB.prepare("DELETE FROM files WHERE id = ? AND device = ?").bind(id, device)]);
    return json({ ok: true });
  } else if (req.method === "GET") {
    const f = await env.DB.prepare("SELECT name, mime, size, chunks, received FROM files WHERE id = ? AND device = ?").bind(id, device).first<{ name: string; mime: string; size: number; chunks: number; received: number }>();
    if (!f) return json({ error: "not found" }, 404);
    if (f.received < f.chunks) return json({ error: "upload not finished" }, 409);
    let i = 0;
    const body = new ReadableStream<Uint8Array>({
      async pull(ctrl) {
        if (i >= f.chunks) return ctrl.close();
        const row = await env.DB.prepare("SELECT data FROM file_chunks WHERE file = ? AND idx = ?").bind(id, i++).first<{ data: ArrayBuffer | number[] }>();
        if (!row) return ctrl.error(new Error("missing chunk"));
        ctrl.enqueue(row.data instanceof ArrayBuffer ? new Uint8Array(row.data) : Uint8Array.from(row.data));
      },
    });
    return new Response(body, { headers: { "content-type": f.mime, "content-length": String(f.size), "x-file-name": encodeURIComponent(f.name), "access-control-expose-headers": "x-file-name", ...CORS } });
  }
  return json({ error: "not found" }, 404);
}

const PAGE = (title: string, body: string) =>
  new Response(
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(1200px 600px at 70% -10%,#1b2a3a 0,#07090d 60%);color:#e7edf5;font:15px/1.55 system-ui,sans-serif}
.card{max-width:560px;margin:24px;padding:28px 30px;border-radius:18px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);backdrop-filter:blur(18px);box-shadow:0 30px 80px rgba(0,0,0,.45)}
h1{margin:0 0 6px;font-size:22px;letter-spacing:.2px}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:8px;vertical-align:1px}
.on{background:#3ee6c4;box-shadow:0 0 12px #3ee6c4}.off{background:#ff6b7d;box-shadow:0 0 12px #ff6b7d}p{color:#aab6c6}code{background:rgba(255,255,255,.08);padding:2px 6px;border-radius:6px}</style></head><body><div class="card">${body}</div></body></html>`,
    { headers: { "content-type": "text/html; charset=utf-8", ...CORS } },
  );

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const parts = url.pathname.split("/").filter(Boolean);
    if (parts.length === 0)
      return PAGE("VideoStudio Desktop link", `<h1>VideoStudio Desktop link</h1><p>This is the private link between Claude and VideoStudio Desktop. Open the app, press <b>Claude link</b>, and copy the connector URL it shows into Claude → Settings → Connectors → Add custom connector.</p><p style="font-size:12px">v${VERSION}</p>`);
    const key = parts[1] ?? "";
    if (key.length < 24) return json({ error: "not found" }, 404);
    await migrate(env);
    const device = await hashKey(key);

    if (parts[0] === "mcp") {
      if (req.method === "POST") return mcp(req, env, device);
      const d = await env.DB.prepare("SELECT last_seen FROM devices WHERE id = ?").bind(device).first<{ last_seen: number }>();
      const on = !!d && Date.now() - d.last_seen < 60_000;
      return PAGE(
        "VideoStudio Desktop link",
        `<h1><span class="dot ${on ? "on" : "off"}"></span>${on ? "VideoStudio Desktop is connected" : "VideoStudio Desktop is not open"}</h1><p>This address is your Claude connector. Add it once in Claude → Settings → Connectors → <b>Add custom connector</b> (name it <code>VideoStudio Desktop</code>). Keep it private: it works like a password to your editor.</p>`,
      );
    }

    if (parts[0] !== "app") return json({ error: "not found" }, 404);

    if (parts[2] === "next" && req.method === "GET") {
      const wait = Math.max(0, Math.min(25, Number(url.searchParams.get("wait") ?? 0) || 0));
      const info = url.searchParams.get("info");
      const until = Date.now() + wait * 1000;
      let first = true;
      for (;;) {
        const now = Date.now();
        const { results } = await env.DB.prepare("SELECT id, commands, cont FROM jobs WHERE device = ? AND status = 'queued' ORDER BY created LIMIT 5").bind(device).all<{ id: string; commands: string; cont: number }>();
        const writes: D1PreparedStatement[] = [];
        if (first) {
          const seen = await env.DB.prepare("SELECT last_seen FROM devices WHERE id = ?").bind(device).first<{ last_seen: number }>();
          if (!seen || now - seen.last_seen > 20_000 || info)
            writes.push(env.DB.prepare("INSERT INTO devices (id, last_seen, info) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen, info = COALESCE(excluded.info, devices.info)").bind(device, now, info ? info.slice(0, 2000) : null));
          first = false;
        }
        for (const j of results) writes.push(env.DB.prepare("UPDATE jobs SET status = 'running', updated = ? WHERE id = ? AND status = 'queued'").bind(now, j.id));
        if (writes.length) await env.DB.batch(writes);
        if (results.length) return json({ jobs: results.map((j) => ({ id: j.id, commands: JSON.parse(j.commands), continueOnError: !!j.cont })) });
        if (Date.now() + 700 >= until) return new Response(null, { status: 204, headers: CORS });
        await sleep(600);
      }
    }

    if (parts[2] === "progress" && req.method === "POST") {
      const body = (await req.json().catch(() => null)) as { id?: string; state?: unknown } | null;
      if (!body?.id) return json({ error: "id required" }, 400);
      await env.DB.prepare("UPDATE jobs SET state = ?, updated = ? WHERE id = ? AND device = ? AND status = 'running'").bind(JSON.stringify(body.state ?? null).slice(0, 20_000), Date.now(), body.id, device).run();
      return json({ ok: true });
    }

    if (parts[2] === "files") {
      try {
        return await files(req, env, device, parts);
      } catch (e) {
        return json({ error: e instanceof Error ? e.message : String(e) }, 400);
      }
    }

    if (parts[2] === "result" && req.method === "POST") {
      // Either the whole result {id, results, state} or one part of a big one {id, part, parts, text}.
      const body = (await req.json().catch(() => null)) as { id?: string; results?: unknown; state?: unknown; part?: number; parts?: number; text?: string } | null;
      if (!body?.id) return json({ error: "id required" }, 400);
      const job = await env.DB.prepare("SELECT id FROM jobs WHERE id = ? AND device = ?").bind(body.id, device).first();
      if (!job) return json({ error: "unknown job" }, 404);
      const now = Date.now();
      if (typeof body.text === "string" && typeof body.part === "number" && typeof body.parts === "number") {
        if (body.text.length > 1_900_000 || body.parts > 64) return json({ error: "part too large" }, 413);
        await env.DB.prepare("INSERT OR REPLACE INTO job_parts (job, idx, text) VALUES (?, ?, ?)").bind(body.id, body.part, body.text).run();
        if (body.part === body.parts - 1)
          await env.DB.prepare("UPDATE jobs SET status = 'done', parts = ?, state = ?, updated = ? WHERE id = ?").bind(body.parts, JSON.stringify(body.state ?? null), now, body.id).run();
        return json({ ok: true });
      }
      const text = JSON.stringify(body.results ?? []);
      if (text.length > 1_900_000) return json({ error: "result too large; send it in parts" }, 413);
      await env.DB.batch([
        env.DB.prepare("INSERT OR REPLACE INTO job_parts (job, idx, text) VALUES (?, 0, ?)").bind(body.id, text),
        env.DB.prepare("UPDATE jobs SET status = 'done', parts = 1, state = ?, updated = ? WHERE id = ?").bind(JSON.stringify(body.state ?? null), now, body.id),
      ]);
      return json({ ok: true });
    }

    return json({ error: "not found" }, 404);
  },
};
