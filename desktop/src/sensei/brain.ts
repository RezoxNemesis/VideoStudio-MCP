/**
 * Sensei talks. With a language model (local Ollama or the person's own cloud key) it thinks with tools: it reviews,
 * reads findings and code, looks at frames with a vision model, searches the web, and — when the person allows it —
 * changes the film's code and draws it again. Without a model it still answers from what it measured and what it knows.
 */
import { runCommand } from "../../../src/agent/api";
import type { Studio } from "../../../src/app/studio";
import { vsd } from "../bridge";
import { film } from "../film/prepare";
import { code } from "../film/session";
import { sensei } from "./store";
import { FINDINGS, apiSections, baseKnowledge, search, words, type Know } from "./knowledge";
import type { BrainSettings, LlmMessage, SenseiIssue, ToolDef } from "./types";

export interface ChatMsg { role: "user" | "sensei"; text: string; at: number; images?: string[]; links?: { label: string; issue?: string; url?: string; shot?: string }[]; busy?: boolean; tools?: string[] }

const brainApi = () => { const b = vsd?.sensei; if (!b) throw new Error("This version of VideoStudio has no Sensei brain."); return b; };
let KB: Know[] | null = null;
async function kb(): Promise<Know[]> {
  if (KB) return KB;
  KB = baseKnowledge();
  try { KB.push(...apiSections(await film().reference())); } catch { /* no film engine */ }
  return KB;
}
const SHOT_RE = /\b(s\d{2}_[a-z0-9]+)\b/gi, SEQ_RE = /\b(s\d{2})\b/gi;
const CAT_WORDS: [RegExp, string | null, string | null][] = [
  [/\bhands?|fingers?|wrists?|grip|sword|blade|holding\b/i, "anatomy", null],
  [/\bfeet|foot|skat|slid|ground|walk/i, null, "foot-slide"],
  [/\bcamera|lens|framing|frame|crop|headroom|angle|cut\b/i, "camera", null],
  [/\bsound|audio|music|loud|voice|dialogue|lufs/i, "sound", null],
  [/\bstyle|drawing|art|look|paint|flat|detail|ink|lines?\b/i, "style", null],
  [/\bstory|pace|pacing|subtitles?|establish/i, "story", null],
  [/\bpop|jerk|glitch|jump|teleport/i, null, "pop"],
  [/\bthrough|intersect|inside|clip/i, null, "self-intersect"],
];
const short = (i: SenseiIssue) => `• [${i.sev.toUpperCase()}] ${i.shot ?? "film"}${i.t0 != null && i.shot ? ` ${i.t0}–${i.t1}s` : ""}: ${i.msg}${i.fix ? `\n   Fix: ${i.fix}` : ""}`;

/** The offline brain: intent, measured data and the knowledge base. */
async function builtin(q: string): Promise<ChatMsg> {
  const at = Date.now(), ql = q.toLowerCase();
  const shots = [...q.matchAll(SHOT_RE)].map((m) => m[1]!.toLowerCase()), seqs = [...q.matchAll(SEQ_RE)].map((m) => m[1]!.toLowerCase()).filter((s) => !shots.some((x) => x.startsWith(s + "_")));
  const reply = (text: string, extra: Partial<ChatMsg> = {}): ChatMsg => ({ role: "sensei", text, at, ...extra });
  if (/^(hi|hello|hey|namaste|yo)\b/.test(ql)) return reply(`Hello. I am Sensei, VideoStudio's judge. Open a code film and ask me to review it, or ask what is wrong with a shot, the hands, the camera, the sound or the style. ${code.dir ? "" : "(No film is open right now.)"}`);
  if (/\b(thanks|thank you|shukriya|dhanyavad)\b/.test(ql)) return reply("Happy to help. Fix the MUST findings first — those are binding.");
  const wantsReview = /\b(review|check|judge|analy[sz]e|inspect|scan|look at)\b/.test(ql) && !/\bweb|internet|online\b/.test(ql);
  if (wantsReview) {
    if (!code.dir) return reply("Open a code film first (Code page), then I can review it.");
    const r = await sensei.review({ only: [...shots, ...seqs].length ? [...shots, ...seqs] : undefined, bench: sensei.report?.bench.id ?? "youtube", images: 8 });
    const d = r.digest;
    return reply(`${d.film}: ${d.overall}/100, grade ${d.grade} — ${d.pass ? "PASSES" : "does NOT pass"} the ${d.bench} benchmark. ${d.counts.must} MUST, ${d.counts.should} SHOULD, ${d.counts.nice} nice.${d.progress ? ` Since last time: ${d.progress.before} → ${d.overall}, ${d.progress.fixed} fixed, ${d.progress.new ?? 0} new.` : ""}${d.style ? ` Drawing style vs "${d.style.ref}": ${d.style.score}/100.` : ""}\n\nWorst first:\n${d.top.slice(0, 8).map((t) => `• [${t.sev.toUpperCase()}] ${t.shot ?? "film"}${t.t ? " " + t.t + "s" : ""}: ${t.msg}`).join("\n")}`, { links: d.top.slice(0, 8).map((t) => ({ label: `${t.shot ?? "film"} ${t.kind}`, issue: t.id })) });
  }
  if (/\b(pass|gate|ready|export|score|grade)\b/.test(ql)) {
    const r = await sensei.load();
    if (!r) return reply("There is no review yet. Say \"review the film\".");
    const must = r.issues.filter((i) => i.sev === "must");
    return reply(r.pass ? `Yes — ${r.overall}/100 (${r.grade}) and no MUST findings: the gate is open for export.` : `Not yet: ${must.length} MUST finding${must.length === 1 ? "" : "s"} block the export (${r.overall}/100, ${r.grade}).\n${must.slice(0, 6).map(short).join("\n")}`, { links: must.slice(0, 6).map((i) => ({ label: `${i.shot} ${i.kind}`, issue: i.id })) });
  }
  if (/\b(progress|improv|better|fixed|since)\b/.test(ql)) {
    const r = await sensei.load(); if (!r?.progress) return reply("I need two reviews to compare. Review again after your changes.");
    return reply(`Before ${r.progress.before.overall} (${r.progress.before.grade}), now ${r.overall} (${r.grade}). ${r.progress.fixed} problems fixed, ${r.progress.new ?? 0} new.\n${r.progress.fixedList.slice(0, 6).map((f) => `✓ ${f.shot}: ${f.msg}`).join("\n")}`);
  }
  if (/\b(search|look up|google|find online|internet|web)\b/.test(ql)) {
    const topic = q.replace(/.*?\b(search( the web)?( for)?|look up|google|find online( about)?|on the internet|on the web)\b/i, "").trim() || q;
    const res = await brainApi().search(topic, 6);
    const ok = res.filter((x) => x.url);
    return reply(ok.length ? `Here is what the web says about "${topic}":\n${ok.map((x) => `• ${x.title} — ${x.snippet ?? ""}`).join("\n")}` : `I could not search the web: ${res.map((x) => x.error).filter(Boolean).join("; ") || "no results"}.`, { links: ok.map((x) => ({ label: x.title ?? x.url!, url: x.url })) });
  }
  // questions about problems in the film
  if (/\b(wrong|problem|issue|bad|fix|broken|mistake|error|off|weird)\b/.test(ql) || shots.length || seqs.length) {
    const r = await sensei.load();
    if (r) {
      let list = r.issues;
      if (shots.length) list = list.filter((i) => i.shot && shots.includes(i.shot));
      else if (seqs.length) list = list.filter((i) => i.seq && seqs.includes(i.seq));
      if (/\bhands?|fingers?|wrists?|grip\b/i.test(q)) { const f = list.filter((i) => /hand|wrist|finger/.test(`${i.part ?? ""}`) || ["wrist-limit", "grip-lost", "blade-through-self", "twinning", "no-arc"].includes(i.kind)); if (f.length) list = f; }
      else for (const [re, cat, kind] of CAT_WORDS) if (re.test(q)) { const f = list.filter((i) => (cat && i.cat === cat) || (kind && i.kind === kind)); if (f.length) { list = f; break; } }
      const top = list.filter((i) => i.sev !== "nice").slice(0, 8);
      if (top.length) return reply(`${top.length < list.length ? `${list.length} findings match; the worst ${top.length}` : `${top.length} finding${top.length > 1 ? "s" : ""}`}:\n${top.map(short).join("\n")}`, { links: top.map((i) => ({ label: `${i.shot} ${i.kind}`, issue: i.id })) });
      if (list.length) return reply(`Only small things there (${list.length} nice-to-fix): ${[...new Set(list.map((i) => i.kind))].join(", ")}.`);
      if (shots.length || seqs.length) return reply(`No findings for ${[...shots, ...seqs].join(", ")} in the latest review (${r.at.slice(0, 16).replace("T", " ")}).`);
    } else if (code.dir) return reply("I have not reviewed this film yet. Say \"review the film\" (it takes a minute or two).");
  }
  // why does X matter / what is X
  for (const [kind, f] of Object.entries(FINDINGS)) if (ql.includes(kind.replace(/-/g, " ")) || ql.includes(kind) || ql.includes(f.title.toLowerCase())) return reply(`${f.title}: ${f.why}\nHow I measure it: ${f.how}\nHow to fix it: ${f.fix}`);
  const hits = search(await kb(), q, 3);
  if (hits.length) return reply(hits.map((k) => `${k.title}\n${k.text.trim().slice(0, 900)}`).join("\n\n— — —\n\n"));
  return reply(`I don't have an answer for that without a language model. I can review the film, list problems by shot or kind (hands, feet, camera, sound, style), explain any finding, tell you if the film passes, or search the web. For free-form conversation, connect a brain in Sensei → Brain (Ollama on this PC, or your own key).`);
}

// ---------------- the thinking brain (a language model with tools)
const SYSTEM = (s: BrainSettings) => `You are Sensei, the senior animation director and critic built into VideoStudio 2 (a desktop video, animation and Code Studio app).
You judge animation honestly and precisely — like a strict anime studio supervisor — and you help the person and Claude make it better.
Rules:
- Ground every judgement in data: call tools to review, read findings (they carry measurements: where, how much, limits) and look at frames. Never invent numbers.
- Findings marked MUST are binding: they are the person's own words and block export until fixed.
- Be specific: shot id, seconds, actor, body part, what is wrong, and the exact change (code, camera numbers, timing).
- Be honest about what looks bad even when the numbers pass (bad drawing, stiff acting, weak composition, flat colour).
- Keep answers short and scannable; bullet points for lists.
- The film is code (Code Studio): sequences in seq/*.js, a performance per sequence solved from motion capture, shots with cameras. Use api_help to find the right API before suggesting code.
${s.edits ? "- You MAY change the film's code with patch_code and redraw shots with build, when the person asked you to fix something." : "- You may NOT change code (the person has not allowed it): describe the fix instead."}
${s.web ? "- You may search and read the web for references and techniques." : "- The internet is switched off."}
Open film: ${code.dir ? `${code.info?.config.name ?? code.dir} (${code.info?.shots.length ?? "?"} shots, ${code.info?.total?.toFixed?.(1) ?? "?"} s)` : "none"}.`;

const TOOLS = (s: BrainSettings): ToolDef[] => [
  { name: "review", description: "Run Sensei's full measured review of the open film (or some shots/sequences). Returns scores, the gate and the worst findings.", parameters: { type: "object", properties: { only: { type: "array", items: { type: "string" }, description: "shot or sequence ids" }, bench: { type: "string", enum: ["youtube", "anime", "feature", "draft"] } } } },
  { name: "findings", description: "Findings of the latest review, filtered.", parameters: { type: "object", properties: { sev: { type: "string", enum: ["must", "should", "nice"] }, cat: { type: "string" }, kind: { type: "string" }, shot: { type: "string" }, seq: { type: "string" }, count: { type: "number" } } } },
  { name: "look", description: "Look at a frame of a shot with your eyes (a vision model) and judge the drawing, acting, hands, faces, composition and colour honestly. Returns your critique.", parameters: { type: "object", properties: { shot: { type: "string" }, t: { type: "number", description: "seconds into the shot" }, focus: { type: "string", description: "what to judge (e.g. hands, faces, composition)" } }, required: ["shot"] } },
  { name: "finding_frame", description: "Look at the marked frame of one finding (vision) and say whether it is really a problem and how bad it looks.", parameters: { type: "object", properties: { id: { type: "string" } }, required: ["id"] } },
  { name: "film_status", description: "The open film: sequences, shots, what is drawn, what changed.", parameters: { type: "object", properties: {} } },
  { name: "read_code", description: "Read one of the film's files (seq/s01.js, film.js, script.json …).", parameters: { type: "object", properties: { file: { type: "string" }, from: { type: "number" }, to: { type: "number" } }, required: ["file"] } },
  { name: "api_help", description: "Search the Code Studio reference and Sensei's handbook (animation principles, camera rules, how to fix each finding).", parameters: { type: "object", properties: { query: { type: "string" } }, required: ["query"] } },
  ...(s.web ? [
    { name: "web_search", description: "Search the internet.", parameters: { type: "object", properties: { q: { type: "string" } }, required: ["q"] } },
    { name: "web_read", description: "Read a web page as text.", parameters: { type: "object", properties: { url: { type: "string" } }, required: ["url"] } },
  ] : []),
  ...(s.edits ? [
    { name: "patch_code", description: "Change the film's code: replace an exact piece of text in a file with new text.", parameters: { type: "object", properties: { file: { type: "string" }, find: { type: "string" }, replace: { type: "string" } }, required: ["file", "find", "replace"] } },
    { name: "build", description: "Draw the shots that changed (only those) and join the film.", parameters: { type: "object", properties: { only: { type: "array", items: { type: "string" } } } } },
  ] : []),
];

let STUDIO: Studio | null = null;
/** The window's studio, for running app commands as tools. */
export const setStudio = (s: Studio) => { STUDIO = s; };
async function cmd(op: string, args: Record<string, unknown> = {}): Promise<unknown> {
  if (!STUDIO) throw new Error("Sensei is not attached to the window yet.");
  const r = await runCommand(STUDIO, { op, ...args } as never) as { ok: boolean; error?: string; result?: unknown; value?: unknown };
  if (!r.ok) throw new Error(r.error ?? `${op} failed`);
  return r.result ?? r.value ?? r;
}
const clip = (v: unknown, n = 6000) => { const s = typeof v === "string" ? v : JSON.stringify(v, (k, x) => (k === "jpeg" ? "<image>" : x)); return s.length > n ? s.slice(0, n) + "…" : s; };

async function runTool(name: string, a: Record<string, unknown>, s: BrainSettings, sink: { images: string[]; used: string[] }): Promise<string> {
  sink.used.push(name);
  switch (name) {
    case "review": { const r = await sensei.review({ only: a.only as string[] | undefined, bench: (a.bench as string) ?? "youtube", images: 8 }); return clip(r.digest); }
    case "findings": { await sensei.load(); const l = sensei.issues({ sev: a.sev as string, cat: a.cat as string, kind: a.kind as string, shot: a.shot as string, seq: a.seq as string }); return clip(l.slice(0, Number(a.count ?? 25)).map((i) => ({ id: i.id, sev: i.sev, shot: i.shot, t: `${i.t0}-${i.t1}`, kind: i.kind, actor: i.actor, part: i.part, value: i.value, limit: i.limit, unit: i.unit, msg: i.msg, fix: i.fix }))); }
    case "look": {
      const f = await film().frame(code.must(), String(a.shot), a.t != null ? Number(a.t) : undefined, undefined, 0.5, 86);
      sink.images.push(`data:image/jpeg;base64,${f.jpeg}`);
      const v = await brainApi().llm({ vision: true, system: "You are a strict, honest senior anime animation director. Judge the frame like a studio supervisor: drawing quality (anatomy, hands, faces, eyes, hair, clothes), acting and pose, staging and composition, colour and light, and what looks cheap or wrong. Be concrete about where in the frame. Short bullet points, worst first.", messages: [{ role: "user", content: `Frame of shot ${a.shot} at ${a.t ?? "its middle"} s.${a.focus ? ` Focus on: ${a.focus}.` : ""}`, images: [f.jpeg] }] });
      return v.text || "(no answer from the vision model)";
    }
    case "finding_frame": {
      await sensei.load(); const i = sensei.report?.issues.find((x) => x.id === a.id); if (!i) return "No such finding.";
      const pic = await film().senseiIssue(code.must(), i, 0.5); sink.images.push(`data:image/jpeg;base64,${pic.jpeg}`);
      const v = await brainApi().llm({ vision: true, system: "You check an automatic animation critic's finding on a marked frame. Say if the problem is visible and how bad it looks, in two or three sentences.", messages: [{ role: "user", content: `Finding: ${i.msg}`, images: [pic.jpeg] }] });
      return v.text;
    }
    case "film_status": return clip(await cmd("code.status"));
    case "read_code": return clip(await cmd("code.read", { file: a.file, from: a.from, to: a.to }), 9000);
    case "api_help": return search(await kb(), String(a.query ?? ""), 4).map((k) => `## ${k.title}\n${k.text.slice(0, 1600)}`).join("\n\n") || "Nothing found.";
    case "web_search": return clip(await brainApi().search(String(a.q ?? ""), 6));
    case "web_read": { const r = await brainApi().fetch(String(a.url ?? ""), 12000); return `${r.title}\n${r.text}`; }
    case "patch_code": if (!s.edits) return "Not allowed."; return clip(await cmd("code.patch", { file: a.file, find: a.find, replace: a.replace }));
    case "build": if (!s.edits) return "Not allowed."; return clip(await cmd("code.build", { only: a.only }));
  }
  return `Unknown tool ${name}.`;
}

/** One question to Sensei (with a language model when there is one). onStep shows its work as it goes. */
export async function ask(q: string, history: ChatMsg[], onStep?: (note: string) => void, opts: { goal?: boolean; maxSteps?: number } = {}): Promise<ChatMsg> {
  const s = await brainApi().settings().catch(() => null);
  if (!s || s.provider === "builtin") return builtin(q);
  const msgs: LlmMessage[] = history.slice(-12).map((m) => ({ role: m.role === "user" ? "user" : "assistant", content: m.text }));
  msgs.push({ role: "user", content: q });
  const sink = { images: [] as string[], used: [] as string[] }, tools = TOOLS(s);
  for (let step = 0; step < (opts.maxSteps ?? 10); step++) {
    let r;
    try { r = await brainApi().llm({ system: SYSTEM(s), messages: msgs, tools }); }
    catch (e) { const b = await builtin(q); b.text = `(The ${s.provider} brain did not answer: ${(e as Error).message}. My own answer:)\n\n${b.text}`; return b; }
    if (!r.toolCalls.length) return { role: "sensei", text: r.text.trim() || "(no answer)", at: Date.now(), images: sink.images.slice(-4), tools: sink.used };
    msgs.push({ role: "assistant", content: r.text, toolCalls: r.toolCalls });
    for (const c of r.toolCalls) {
      onStep?.(`${c.name}${c.args && Object.keys(c.args).length ? " " + JSON.stringify(c.args).slice(0, 80) : ""}`);
      let out: string;
      try { out = await runTool(c.name, c.args ?? {}, s, sink); } catch (e) { out = `Error: ${(e as Error).message}`; }
      msgs.push({ role: "tool", name: c.name, callId: c.id, content: out });
    }
  }
  return { role: "sensei", text: "I worked through many steps; here is where I stopped. Ask me to continue.", at: Date.now(), images: sink.images.slice(-4), tools: sink.used };
}
export { builtin, words };
