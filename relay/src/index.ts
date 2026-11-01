/**
 * VideoStudio relay: an MCP endpoint for Claude (or any MCP client) plus a mailbox the app polls.
 *
 *   POST /mcp/<key>              MCP (Streamable HTTP, JSON responses). Tools: studio_run, studio_result, studio_status, studio_help
 *   GET  /device/<key>/next      the app collects queued command batches (204 when none)
 *   POST /device/<key>/result    the app posts results
 *   POST /device/<key>/files            start an upload {name, mime, size} → {id, chunkSize}
 *   PUT  /device/<key>/files/<id>/<n>   upload chunk n (raw bytes, ≤ 1.5 MB)
 *   GET  /device/<key>/files            inbox: files waiting to be imported
 *   GET  /device/<key>/files/<id>       download (the app imports it), DELETE removes it
 *
 * Storage is D1 only (no Durable Objects). The key in the URL is the credential; only its SHA-256 is stored.
 */

export interface Env {
  DB: D1Database;
}

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
  "access-control-allow-headers": "content-type, mcp-session-id, mcp-protocol-version",
};

const json = (body: unknown, status = 200, extra: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...CORS, ...extra } });

async function hashKey(key: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, device TEXT NOT NULL, commands TEXT NOT NULL, cont INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, results TEXT, state TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS jobs_device_status ON jobs (device, status, created)`,
  `CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, last_seen INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS files (id TEXT PRIMARY KEY, device TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, chunks INTEGER NOT NULL, received INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS file_chunks (file TEXT NOT NULL, idx INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (file, idx))`,
];
const CHUNK = 1_500_000;
const MAX_FILE = 300 * 1024 * 1024;
let ready: Promise<void> | null = null;
function migrate(env: Env): Promise<void> {
  return (ready ??= env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s))).then(() => undefined));
}

const TOOLS = [
  {
    name: "studio_run",
    description:
      "Run a batch of VideoStudio commands on the person's phone and return the results. Commands are objects like " +
      '{"op":"clip.add","text":"Hello"}. Strings like "$0.id" refer to the result of an earlier command in the same batch. ' +
      'Start with [{"op":"help"}] for the full command list and [{"op":"state"}] for the project. ' +
      "Covers editing (clip.*, track.*), drawing and animation (anim.*), anime characters (char.create/char.act/char.say, scene.backdrop), voices (voice.speak), media links (media.importUrl) and rendering (render.start). " +
      'Fastest path for a whole animated video: one {"op":"shot", ...} command (see help). ' +
      "The app must be open with the remote link connected. Long jobs (voices, renders) keep running after the wait; fetch them with studio_result.",
    inputSchema: {
      type: "object",
      properties: {
        commands: { type: "array", items: { type: "object" }, description: "Commands to run in order." },
        continue_on_error: { type: "boolean", description: "Keep going after a failed command. Default false." },
        wait_seconds: { type: "number", description: "How long to wait for results (0-50). Default 40." },
      },
      required: ["commands"],
    },
  },
  {
    name: "studio_result",
    description: "Get the status and results of a job queued with studio_run (when it was still running).",
    inputSchema: { type: "object", properties: { job_id: { type: "string" }, wait_seconds: { type: "number" } }, required: ["job_id"] },
  },
  {
    name: "studio_files",
    description:
      "List files in the phone's inbox (uploaded through the relay, e.g. files the person shared in chat). Import one with studio_run " +
      '[{"op":"media.importInbox","fileId":"<id>"}]. Small files (≤ 1 MB) can also be sent with studio_upload.',
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "studio_upload",
    description: "Put a small file (base64, ≤ 1 MB decoded) into the phone's inbox. Returns its file id for media.importInbox.",
    inputSchema: { type: "object", properties: { name: { type: "string" }, mime: { type: "string" }, data_base64: { type: "string" } }, required: ["name", "data_base64"] },
  },
  {
    name: "studio_status",
    description: "Whether the phone app is connected (last poll time) and how many jobs are queued.",
    inputSchema: { type: "object", properties: {} },
  },
];

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function waitFor(env: Env, device: string, id: string, seconds: number) {
  const until = Date.now() + Math.max(0, Math.min(50, seconds)) * 1000;
  let delay = 400;
  for (;;) {
    const row = await env.DB.prepare("SELECT status, results, state FROM jobs WHERE id = ? AND device = ?").bind(id, device).first<{ status: string; results: string | null; state: string | null }>();
    if (!row) return { job_id: id, status: "unknown" };
    if (row.status === "done") return { job_id: id, status: "done", results: JSON.parse(row.results ?? "[]"), app: row.state ? JSON.parse(row.state) : undefined };
    if (Date.now() >= until) return { job_id: id, status: row.status, progress: row.status === "running" && row.state ? JSON.parse(row.state) : undefined, note: row.status === "queued" ? "The app has not picked this up yet. Is VideoStudio open with the remote link on?" : "Still running on the phone. Call studio_result later." };
    await sleep(delay);
    delay = Math.min(1500, delay * 1.4);
  }
}

async function callTool(env: Env, device: string, name: string, args: Record<string, unknown>) {
  switch (name) {
    case "studio_run": {
      const commands = args.commands;
      if (!Array.isArray(commands) || commands.length === 0) throw new Error("commands must be a non-empty array");
      if (JSON.stringify(commands).length > 900_000) throw new Error("batch too large");
      const id = crypto.randomUUID();
      const now = Date.now();
      await env.DB.batch([
        env.DB.prepare("INSERT INTO jobs (id, device, commands, cont, status, created, updated) VALUES (?, ?, ?, ?, 'queued', ?, ?)").bind(id, device, JSON.stringify(commands), args.continue_on_error ? 1 : 0, now, now),
        env.DB.prepare("DELETE FROM jobs WHERE updated < ?").bind(now - 3 * 86_400_000),
      ]);
      return await waitFor(env, device, id, typeof args.wait_seconds === "number" ? args.wait_seconds : 40);
    }
    case "studio_result":
      return await waitFor(env, device, String(args.job_id ?? ""), typeof args.wait_seconds === "number" ? args.wait_seconds : 20);
    case "studio_files":
      return { files: await listFiles(env, device) };
    case "studio_upload": {
      const bin = Uint8Array.from(atob(String(args.data_base64 ?? "")), (c) => c.charCodeAt(0));
      if (!bin.length || bin.length > 1_048_576) throw new Error("data must be 1 byte to 1 MB");
      const id = await createFile(env, device, String(args.name ?? "file"), String(args.mime ?? "application/octet-stream"), bin.length);
      await putChunk(env, device, id, 0, bin);
      return { file_id: id, size: bin.length };
    }
    case "studio_status": {
      const d = await env.DB.prepare("SELECT last_seen FROM devices WHERE id = ?").bind(device).first<{ last_seen: number }>();
      const q = await env.DB.prepare("SELECT COUNT(*) AS n FROM jobs WHERE device = ? AND status IN ('queued','running')").bind(device).first<{ n: number }>();
      const ago = d ? Math.round((Date.now() - d.last_seen) / 1000) : null;
      return { connected: ago !== null && ago < 45, last_seen_seconds_ago: ago, pending_jobs: q?.n ?? 0 };
    }
  }
  throw new Error(`Unknown tool ${name}`);
}

async function mcp(req: Request, env: Env, device: string): Promise<Response> {
  if (req.method === "GET") return new Response("Method not allowed", { status: 405, headers: CORS });
  const msg = (await req.json().catch(() => null)) as { id?: string | number; method?: string; params?: Record<string, unknown> } | Array<unknown> | null;
  if (!msg || Array.isArray(msg)) return json({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "Invalid request" } }, 400);
  const { id, method, params = {} } = msg;
  if (id === undefined) return new Response(null, { status: 202, headers: CORS }); // notification
  const ok = (result: unknown) => json({ jsonrpc: "2.0", id, result });
  try {
    switch (method) {
      case "initialize":
        return ok({
          protocolVersion: typeof params.protocolVersion === "string" ? params.protocolVersion : "2025-06-18",
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: "videostudio", version: "5.1.0" },
          instructions: "Controls the VideoStudio app on the person's phone. Use studio_run with [{\"op\":\"help\"}] first. Gallery browsing is not possible by design.",
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
          return ok({ content: [{ type: "text", text: JSON.stringify(out, null, 1) }] });
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

async function deleteFile(env: Env, device: string, id: string): Promise<void> {
  const f = await env.DB.prepare("SELECT id FROM files WHERE id = ? AND device = ?").bind(id, device).first();
  if (!f) return;
  await env.DB.batch([env.DB.prepare("DELETE FROM file_chunks WHERE file = ?").bind(id), env.DB.prepare("DELETE FROM files WHERE id = ?").bind(id)]);
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
    await deleteFile(env, device, id);
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

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const parts = url.pathname.split("/").filter(Boolean);
    if (parts.length === 0) return new Response("VideoStudio relay is running.", { headers: { "content-type": "text/plain", ...CORS } });
    const key = parts[1] ?? "";
    if (key.length < 20) return json({ error: "not found" }, 404);
    await migrate(env);
    const device = await hashKey(key);

    if (parts[0] === "mcp") return mcp(req, env, device);

    if (parts[0] === "device" && parts[2] === "next" && req.method === "GET") {
      // Long poll: newer apps pass ?wait=N and get a job the moment it is queued (older apps get an instant answer).
      const wait = Math.max(0, Math.min(25, Number(url.searchParams.get("wait") ?? 0) || 0));
      const until = Date.now() + wait * 1000;
      let first = true;
      for (;;) {
        const now = Date.now();
        const { results } = await env.DB.prepare("SELECT id, commands, cont FROM jobs WHERE device = ? AND status = 'queued' ORDER BY created LIMIT 5").bind(device).all<{ id: string; commands: string; cont: number }>();
        const writes: D1PreparedStatement[] = [];
        if (first) {
          // Refresh "last seen" at most every 20 s so idle polling stays far under D1's free write quota.
          const seen = await env.DB.prepare("SELECT last_seen FROM devices WHERE id = ?").bind(device).first<{ last_seen: number }>();
          if (!seen || now - seen.last_seen > 20_000) writes.push(env.DB.prepare("INSERT INTO devices (id, last_seen) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen").bind(device, now));
          first = false;
        }
        for (const j of results) writes.push(env.DB.prepare("UPDATE jobs SET status = 'running', updated = ? WHERE id = ? AND status = 'queued'").bind(now, j.id));
        if (writes.length) await env.DB.batch(writes);
        if (results.length) return json({ jobs: results.map((j) => ({ id: j.id, commands: JSON.parse(j.commands), continueOnError: !!j.cont })) });
        if (Date.now() + 700 >= until) return new Response(null, { status: 204, headers: CORS });
        await sleep(650);
      }
    }

    if (parts[0] === "device" && parts[2] === "progress" && req.method === "POST") {
      const body = (await req.json().catch(() => null)) as { id?: string; state?: unknown } | null;
      if (!body?.id) return json({ error: "id required" }, 400);
      await env.DB.prepare("UPDATE jobs SET state = ?, updated = ? WHERE id = ? AND device = ? AND status = 'running'").bind(JSON.stringify(body.state ?? null), Date.now(), body.id, device).run();
      return json({ ok: true });
    }

    if (parts[0] === "device" && parts[2] === "files") {
      try {
        return await files(req, env, device, parts);
      } catch (e) {
        return json({ error: e instanceof Error ? e.message : String(e) }, 400);
      }
    }

    if (parts[0] === "device" && parts[2] === "result" && req.method === "POST") {
      const body = (await req.json().catch(() => null)) as { id?: string; results?: unknown; state?: unknown } | null;
      if (!body?.id) return json({ error: "id required" }, 400);
      await env.DB.prepare("UPDATE jobs SET status = 'done', results = ?, state = ?, updated = ? WHERE id = ? AND device = ?").bind(JSON.stringify(body.results ?? []), JSON.stringify(body.state ?? null), Date.now(), body.id, device).run();
      return json({ ok: true });
    }

    return json({ error: "not found" }, 404);
  },
};
