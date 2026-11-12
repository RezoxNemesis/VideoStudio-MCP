/**
 * Sensei's jobs: work it does on its own, side by side, while the person (or Claude) keeps working — full reviews,
 * watching every build, chasing a goal with its tools, researching a topic on the web, learning a style from pictures,
 * a fix loop that reviews, repairs and reviews again. Each job reports what it found.
 */
import { vsd } from "../bridge";
import { film } from "../film/prepare";
import { code } from "../film/session";
import { ask, type ChatMsg } from "./brain";
import { sensei } from "./store";

export type TaskKind = "review" | "watch" | "goal" | "research" | "style" | "fix";
export interface Task { id: string; kind: TaskKind; title: string; state: "running" | "done" | "failed" | "cancelled"; progress: number; note: string; started: number; ended?: number; result?: unknown; text?: string; error?: string; log: string[]; film?: string | null }
const ID = () => Math.random().toString(36).slice(2, 8);

class Tasks {
  list: Task[] = [];
  private fns = new Set<() => void>();
  private stops = new Map<string, () => void>();
  on(fn: () => void): () => void { this.fns.add(fn); return () => this.fns.delete(fn); }
  private emit(): void { for (const f of this.fns) { try { f(); } catch (e) { console.error(e); } } sensei.emit("tasks"); }
  get(id: string): Task | undefined { return this.list.find((t) => t.id === id); }
  watching(): Task | undefined { return this.list.find((t) => t.kind === "watch" && t.state === "running"); }
  cancel(id: string): boolean { const t = this.get(id); if (!t || t.state !== "running") return false; this.stops.get(id)?.(); t.state = "cancelled"; t.ended = Date.now(); this.emit(); return true; }

  start(kind: TaskKind, p: Record<string, unknown> = {}): Task {
    const t: Task = { id: ID(), kind, title: TITLE[kind](p), state: "running", progress: -1, note: "", started: Date.now(), log: [], film: code.dir };
    this.list.unshift(t); this.list = this.list.slice(0, 40); this.emit();
    const say = (note: string, progress = t.progress) => { t.note = note; t.progress = progress; t.log.push(`${new Date().toLocaleTimeString()} ${note}`); this.emit(); };
    const done = (result: unknown, text?: string) => { if (t.state !== "running") return; t.state = "done"; t.ended = Date.now(); t.result = result; t.text = text; t.progress = 1; this.emit(); };
    const fail = (e: unknown) => { if (t.state !== "running") return; t.state = "failed"; t.ended = Date.now(); t.error = e instanceof Error ? e.message : String(e); this.emit(); };
    const run = RUN[kind];
    void run(p, { say, done, fail, task: t, setStop: (f) => this.stops.set(t.id, f) }).then((r) => { if (r !== undefined) done(r); }, fail);
    return t;
  }
}
export const tasks = new Tasks();
interface Ctx { say(note: string, progress?: number): void; done(result: unknown, text?: string): void; fail(e: unknown): void; task: Task; setStop(f: () => void): void }

const TITLE: Record<TaskKind, (p: Record<string, unknown>) => string> = {
  review: (p) => `Review ${Array.isArray(p.only) && p.only.length ? (p.only as string[]).join(", ") : "the whole film"}`,
  watch: () => "Watch every build",
  goal: (p) => String(p.text ?? "A goal").slice(0, 80),
  research: (p) => `Research: ${String(p.topic ?? "").slice(0, 60)}`,
  style: (p) => `Learn a style from ${Array.isArray(p.files) ? p.files.length : 0} pictures`,
  fix: () => "Fix loop: review → repair → review",
};
const RUN: Record<TaskKind, (p: Record<string, unknown>, c: Ctx) => Promise<unknown>> = {
  async review(p, c) {
    c.say("looking at every shot");
    const r = await sensei.review({ only: p.only, bench: p.bench ?? "youtube", checks: p.checks, images: p.images ?? 12 });
    const d = r.digest;
    c.done(d, `${d.overall}/100 · ${d.grade} · ${d.pass ? "passes" : "does not pass"} · ${d.counts.must} MUST, ${d.counts.should} SHOULD`);
  },
  async watch(_p, c) {
    // after every build, look at the shots that were drawn again
    let seen = code.lastBuildAt ?? 0, busy = false;
    const off = code.on(async (e) => {
      if (e !== "status" || busy || !code.lastBuildAt || code.lastBuildAt === seen) return;
      seen = code.lastBuildAt; const drawn = [...new Set((code.lastBuild?.rendered ?? []).map((k) => String(k).split(/[|.@]/)[0]!))];
      if (!drawn.length) return;
      busy = true; c.say(`build seen: reviewing ${drawn.length} redrawn shot${drawn.length > 1 ? "s" : ""}`);
      try { const r = await sensei.review({ only: drawn, bench: sensei.report?.bench.id ?? "youtube", images: 6 }); c.say(`${drawn.join(" ")} → ${r.digest.counts.must} MUST, ${r.digest.counts.should} SHOULD`); } catch (e) { c.say(`review failed: ${(e as Error).message}`); }
      busy = false;
    });
    c.setStop(off);
    c.say("waiting for the next build");
    return undefined; // runs until cancelled
  },
  async goal(p, c) {
    const hist: ChatMsg[] = [];
    const r = await ask(String(p.text ?? ""), hist, (n) => c.say(n), { goal: true, maxSteps: Number(p.steps ?? 16) });
    c.done({ reply: r.text, tools: r.tools }, r.text);
  },
  async research(p, c) {
    const b = vsd?.sensei; if (!b) throw new Error("No Sensei brain in this version.");
    const topic = String(p.topic ?? ""); c.say("searching");
    const res = (await b.search(topic, 8)).filter((x) => x.url);
    const pages: { title: string; url: string; text: string }[] = [];
    for (const r of res.slice(0, Number(p.pages ?? 3))) { c.say(`reading ${r.url}`); try { const pg = await b.fetch(r.url!, 9000); pages.push({ title: pg.title || r.title || r.url!, url: pg.url, text: pg.text }); } catch (e) { c.say(`could not read ${r.url}: ${(e as Error).message}`); } }
    const st = await b.settings();
    if (st.provider !== "builtin" && pages.length) {
      c.say("summarising");
      const s = await b.llm({ system: "You research for an animation studio. Summarise what these pages say about the topic as practical guidance for animators, with the sources' names. Bullet points.", messages: [{ role: "user", content: `Topic: ${topic}\n\n${pages.map((x) => `# ${x.title} (${x.url})\n${x.text.slice(0, 6000)}`).join("\n\n")}` }] });
      return c.done({ topic, results: res, summary: s.text }, s.text);
    }
    const text = pages.map((x) => `${x.title}\n${x.url}\n${x.text.split("\n").filter((l) => l.length > 60).slice(0, 6).join("\n")}`).join("\n\n");
    c.done({ topic, results: res, extracts: text }, text || res.map((r) => `${r.title} — ${r.url}`).join("\n"));
  },
  async style(p, c) {
    const files = (p.files as string[]) ?? []; if (!files.length) throw new Error("Give pictures (files).");
    c.say("measuring the pictures");
    const fp = await film().senseiStyle(files);
    await sensei.load();
    const me = (sensei.report?.style as unknown as { me?: Record<string, number> })?.me ?? null;
    c.done({ name: p.name ?? "references", fingerprint: fp, filmNow: me }, `Style fingerprint of ${files.length} picture(s) ready: use it with sensei.review { refs: [...] }.`);
  },
  async fix(p, c) {
    const max = Number(p.rounds ?? 3), b = vsd?.sensei, st = b ? await b.settings() : null;
    for (let round = 1; round <= max; round++) {
      c.say(`round ${round}: review`, (round - 1) / max);
      const r = await sensei.review({ bench: p.bench ?? sensei.report?.bench.id ?? "youtube", images: 4 });
      if (r.digest.pass) return c.done(r.digest, `Passes after ${round - 1} repair round${round === 2 ? "" : "s"}: ${r.digest.overall}/100.`);
      if (!st || st.provider === "builtin" || !st.edits) return c.done(r.digest, `${r.digest.counts.must} MUST findings remain. Automatic repair needs a language-model brain with code edits allowed (Sensei → Brain); until then, Claude or you fix them from the report.`);
      c.say(`round ${round}: repairing ${r.digest.counts.must} MUST findings`);
      await ask(`Fix the MUST findings of the latest review in the film's code, one by one: read the findings, read the code of each shot, patch it (patch_code), then build the changed shots. Keep every other part of the film as it is.`, [], (n) => c.say(n), { maxSteps: 24 });
    }
    const r = await sensei.load();
    c.done(r ? { overall: r.overall, pass: r.pass, counts: r.counts } : null, r ? `After ${max} rounds: ${r.overall}/100, ${r.counts.must} MUST left.` : "done");
  },
};
