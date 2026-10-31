/**
 * VideoStudio relay: an MCP endpoint for Claude (or any MCP client) plus a mailbox the app polls.
 *
 *   POST /mcp/<key>              MCP (Streamable HTTP, JSON responses). Tools: studio_run, studio_result, studio_status, studio_help
 *   GET  /device/<key>/next      the app collects queued command batches (204 when none)
 *   POST /device/<key>/result    the app posts results
 *
 * Storage is D1 only (no Durable Objects). The key in the URL is the credential; only its SHA-256 is stored.
 */

export interface Env {
  DB: D1Database;
}

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
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
];
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
      "Covers editing (clip.*, track.*), drawing and animation (anim.*), voices (voice.speak), media links (media.importUrl) and rendering (render.start). " +
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
    if (Date.now() >= until) return { job_id: id, status: row.status, note: row.status === "queued" ? "The app has not picked this up yet. Is VideoStudio open with the remote link on?" : "Still running on the phone. Call studio_result later." };
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
          serverInfo: { name: "videostudio", version: "5.0.0" },
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
      const now = Date.now();
      const { results } = await env.DB.prepare("SELECT id, commands, cont FROM jobs WHERE device = ? AND status = 'queued' ORDER BY created LIMIT 5").bind(device).all<{ id: string; commands: string; cont: number }>();
      // Refresh "last seen" at most every 20 s so idle polling stays far under D1's free write quota.
      const seen = await env.DB.prepare("SELECT last_seen FROM devices WHERE id = ?").bind(device).first<{ last_seen: number }>();
      const writes: D1PreparedStatement[] = [];
      if (!seen || now - seen.last_seen > 20_000) writes.push(env.DB.prepare("INSERT INTO devices (id, last_seen) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen").bind(device, now));
      for (const j of results) writes.push(env.DB.prepare("UPDATE jobs SET status = 'running', updated = ? WHERE id = ?").bind(now, j.id));
      if (writes.length) await env.DB.batch(writes);
      if (!results.length) return new Response(null, { status: 204, headers: CORS });
      return json({ jobs: results.map((j) => ({ id: j.id, commands: JSON.parse(j.commands), continueOnError: !!j.cont })) });
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
