/**
 * Sensei's commands: the same judge the person uses in the window, for Claude. What Sensei marks MUST counts as the
 * person's own words — fix it before export (code.export refuses while MUST findings stand, unless force).
 */
import type { Studio } from "../../../src/app/studio";
import { registerCommand } from "../../../src/agent/api";
import { film } from "../film/prepare";
import { code } from "../film/session";
import { sensei } from "./store";
import { ask, setStudio, type ChatMsg } from "./brain";
import { tasks, type TaskKind } from "./tasks";
import { vsd } from "../bridge";
import type { SenseiIssue } from "./types";

const strs = (v: unknown): string[] | undefined => (Array.isArray(v) ? v.map(String) : typeof v === "string" && v ? v.split(/[\s,]+/).filter(Boolean) : undefined);
const brief = (i: SenseiIssue) => ({ id: i.id, sev: i.sev, cat: i.cat, kind: i.kind, shot: i.shot ?? undefined, t: i.t0 != null && i.shot ? `${i.t0}-${i.t1}` : undefined, actor: i.actor ?? undefined, part: i.part ?? undefined, value: i.value ?? undefined, limit: i.limit ?? undefined, unit: i.unit ?? undefined, msg: i.msg, fix: i.fix ?? undefined });

export function registerSenseiCommands(st: Studio, show: () => void): void {
  setStudio(st);
  registerCommand(
    "sensei.review",
    '{only?: [shot or sequence ids], bench?: "youtube" | "anime" | "feature" | "draft", checks?: ["motion","camera","picture","sound","style","story"], style?: "anime-scene" | "anime-closeup" | a saved style name, refs?: [image files], images?: 12 (marked pictures of the worst findings), draw?: true (draw frames of shots that are not built), lang?} Sensei, VideoStudio\'s judge, looks at the open code film exhaustively — every body at 60 samples a second (feet that skate, bodies through each other or themselves, hands that let go, impossible joints, pops, dead arms, twins, balance), every camera (inside a body, crops at knees/ankles, headroom, lead room, hidden faces, whips, jerks, the 180° line, jump cuts), the pictures (exposure, figures that blend in, colour jumps), the sound (loudness, buried lines, dead air), the script (subtitle speed, establishing shots) and the drawing style against a reference. Returns scores, the gate and the worst findings with where, how much and what to change. Findings marked MUST are the person\'s own words: fix them.',
    async (_s, a) => {
      show();
      const r = await sensei.review({ only: strs(a.any("only")), bench: a.optStr("bench") ?? "youtube", checks: strs(a.any("checks")), style: a.optStr("style"), refs: strs(a.any("refs")), images: a.optNum("images") ?? 12, draw: a.bool("draw", true), lang: a.optStr("lang"), top: a.optNum("top") ?? 15 });
      return { ...r.digest, report: r.files.md };
    },
  );
  registerCommand(
    "sensei.report",
    '{sev?: "must" | "should" | "nice", cat?: motion | anatomy | camera | framing | picture | sound | style | story, kind?, shot?, seq?, from?=0, count?=40} The findings of the latest review, filtered (worst first). Each says where (shot, seconds, actor, body part), what was measured against which limit, and the fix.',
    async (_s, a) => {
      const r = await sensei.load();
      if (!r) throw new Error("No review yet: run sensei.review.");
      const list = sensei.issues({ sev: a.optStr("sev"), cat: a.optStr("cat"), kind: a.optStr("kind"), shot: a.optStr("shot"), seq: a.optStr("seq") });
      const from = a.num("from", 0), n = a.num("count", 40);
      return { film: r.film, at: r.at, overall: r.overall, grade: r.grade, pass: r.pass, counts: r.counts, matching: list.length, from, issues: list.slice(from, from + n).map(brief) };
    },
  );
  registerCommand(
    "sensei.issue",
    "{id, image?=true, scale?=0.5} One finding in full, with the frame of the film where it happens and the problem ringed and labelled.",
    async (_s, a) => {
      const r = await sensei.load();
      const i = r?.issues.find((x) => x.id === a.str("id"));
      if (!i) throw new Error(`No finding ${a.str("id")} in the latest review.`);
      const out: Record<string, unknown> = { ...brief(i), data: i.data ?? undefined, where: i.where ?? undefined };
      if (a.bool("image", true) && i.shot) {
        const dir = code.must();
        const pic = await film().senseiIssue(dir, i, a.num("scale", 0.5));
        out.jpeg = `data:image/jpeg;base64,${pic.jpeg}`;
      }
      return out;
    },
  );
  registerCommand(
    "sensei.gate",
    '{bench?} Does the film pass Sensei\'s benchmark? Uses the latest review when it is newer than the last build; otherwise looks again (motion, camera, story — quick). → { pass, must: [findings that block], should }',
    async (_s, a) => {
      let r = await sensei.load();
      const bench = a.optStr("bench") ?? r?.bench.id ?? "youtube";
      if (!r || sensei.stale() || r.bench.id !== bench) { await sensei.review({ bench, checks: ["motion", "camera", "story"], images: 0, draw: false }); r = await sensei.load(); }
      if (!r) throw new Error("Sensei could not review the film.");
      return { pass: r.pass, overall: r.overall, grade: r.grade, bench: r.bench.name, must: r.issues.filter((i) => i.sev === "must").map(brief), should: r.counts.should, binding: r.binding };
    },
  );
  registerCommand("sensei.styles", "The drawing styles Sensei can compare a film with (built in from the person's reference pictures, and this film's own).", async () => {
    const built = await film().senseiStyles();
    return Object.fromEntries(Object.entries(built).map(([k, v]) => [k, { name: v.name, from: v.from }]));
  });
  registerCommand(
    "sensei.style",
    "{files: [image files], name?} The style fingerprint of reference pictures (values, colour, ink, flat vs painted, detail). Give it to sensei.review as refs to judge a film against them.",
    async (_s, a) => {
      const files = strs(a.any("files"));
      if (!files?.length) throw new Error("Give files: [pictures].");
      return { name: a.optStr("name") ?? "references", fingerprint: await film().senseiStyle(files) };
    },
  );

  // ---- talking to Sensei, and its jobs
  const chat: ChatMsg[] = [];
  registerCommand(
    "sensei.ask",
    "{question, fresh?=false} Ask Sensei anything about the open film or animation craft. With a language-model brain (Ollama or a key, Sensei → Brain) it thinks with its tools (review, findings, frames through a vision model, code, the web); without one it answers from its measurements and handbook.",
    async (_s, a) => {
      if (a.bool("fresh", false)) chat.length = 0;
      const steps: string[] = [];
      const r = await ask(a.str("question"), chat, (n) => steps.push(n));
      chat.push({ role: "user", text: a.str("question"), at: Date.now() }, r);
      sensei.emit("chat");
      return { answer: r.text, tools: r.tools ?? (steps.length ? steps : undefined), links: r.links, images: r.images?.length ? r.images.slice(0, 2) : undefined };
    },
  );
  registerCommand(
    "sensei.task",
    '{kind: "review" | "watch" | "goal" | "research" | "style" | "fix", …} Give Sensei a job that runs on its own, side by side with other work. review {only?, bench?} · watch (reviews every build\'s redrawn shots until cancelled) · goal {text, steps?} (an open job for its language-model brain, e.g. "check every fight shot\'s hands and list what to fix") · research {topic, pages?} (web) · style {files, name?} · fix {rounds?=3} (review → repair → review; needs a brain with code edits allowed). → { id } — read it with sensei.result.',
    async (_s, a) => {
      const kind = a.str("kind") as TaskKind;
      if (!["review", "watch", "goal", "research", "style", "fix"].includes(kind)) throw new Error("kind: review | watch | goal | research | style | fix");
      if (kind === "watch" && tasks.watching()) return { id: tasks.watching()!.id, note: "already watching" };
      const p: Record<string, unknown> = {};
      for (const k of ["only", "bench", "text", "steps", "topic", "pages", "files", "name", "rounds", "images", "checks"]) { const v = a.any(k); if (v !== undefined) p[k] = v; }
      const t = tasks.start(kind, p);
      return { id: t.id, title: t.title };
    },
  );
  registerCommand("sensei.tasks", "Sensei's jobs: running and finished, with their progress and notes.", async () => tasks.list.map((t) => ({ id: t.id, kind: t.kind, title: t.title, state: t.state, progress: t.progress >= 0 ? Math.round(t.progress * 100) + "%" : undefined, note: t.note, seconds: Math.round(((t.ended ?? Date.now()) - t.started) / 1000) })));
  registerCommand(
    "sensei.result",
    "{id, wait?=0 (seconds to wait for it to finish)} What a Sensei job found.",
    async (_s, a) => {
      const id = a.str("id"), until = Date.now() + a.num("wait", 0) * 1000;
      let t = tasks.get(id); if (!t) throw new Error(`No job ${id}.`);
      while (t.state === "running" && Date.now() < until) { await new Promise((r) => setTimeout(r, 500)); t = tasks.get(id)!; }
      return { id, state: t.state, title: t.title, note: t.note, text: t.text, result: t.result, error: t.error, log: t.log.slice(-15) };
    },
  );
  registerCommand("sensei.cancel", "{id} Stop a Sensei job.", async (_s, a) => tasks.cancel(a.str("id")));
  registerCommand(
    "sensei.brain",
    '{provider?: "builtin" | "ollama" | "openai" | "anthropic" | "gemini", model?, visionModel?, url? (Ollama or an OpenAI-compatible server), key? (kept encrypted, never shown), web?: true, edits?: false, test?: false} What Sensei thinks with. builtin = no language model (measurements + handbook); ollama = a local model on this PC (free; Sensei → Brain can install Ollama); the others use the person\'s own key.',
    async (_s, a) => {
      const b = vsd?.sensei; if (!b) throw new Error("This version has no Sensei brain.");
      const patch: Record<string, unknown> = {};
      for (const k of ["provider", "model", "visionModel", "url", "key"]) { const v = a.optStr(k); if (v !== undefined) patch[k] = v; }
      for (const k of ["web", "edits"]) { const v = a.any(k); if (typeof v === "boolean") patch[k] = v; }
      const st = Object.keys(patch).length ? await b.setSettings(patch as never) : await b.settings();
      sensei.emit("brain");
      const out: Record<string, unknown> = { provider: st.provider, model: st.model || st.modelDefault[st.provider], visionModel: st.visionModel || st.visionDefault[st.provider], url: st.url || undefined, web: st.web, edits: st.edits, keys: st.keys };
      if (a.bool("test", false)) out.test = await b.test().catch((e: Error) => ({ ok: false, error: e.message }));
      if (st.provider === "ollama") out.ollama = await b.ollama().catch(() => null);
      return out;
    },
  );
  registerCommand("sensei.web", "{q} Search the internet (Sensei's search: web + Wikipedia).", async (_s, a) => { const b = vsd?.sensei; if (!b) throw new Error("No Sensei brain."); return b.search(a.str("q"), a.num("count", 8)); });
  registerCommand("sensei.read", "{url} Read a web page as plain text (pages on this PC or its network are never read).", async (_s, a) => { const b = vsd?.sensei; if (!b) throw new Error("No Sensei brain."); return b.fetch(a.str("url"), a.num("max", 20000)); });
}
