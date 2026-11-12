/**
 * The Sensei workspace: VideoStudio's own judge and helper. Left, the verdict (score, grade, the gate, every category,
 * the drawing style against the reference, the sound); middle, every finding with its marked frame and the fix; right,
 * Sensei to talk to, its jobs, and its brain (built-in, a local model through Ollama, or the person's own key).
 */
import type { Studio } from "../../../src/app/studio";
import { toast } from "../../../src/ui/dom";
import { vsd } from "../bridge";
import { film } from "../film/prepare";
import { code } from "../film/session";
import { ask, type ChatMsg } from "../sensei/brain";
import { sensei } from "../sensei/store";
import { tasks, type TaskKind } from "../sensei/tasks";
import { BENCHES, type BrainSettings, type OllamaStatus, type SenseiIssue } from "../sensei/types";
import { b, h, ic, selectEl } from "./kit";

const SEVN: Record<string, string> = { must: "MUST", should: "SHOULD", nice: "nice" };
const CHATKEY = (d: string | null) => `vs.sensei.chat:${d ?? "-"}`;

export class SenseiPage {
  el: HTMLElement;
  private bench = "youtube";
  private filter: { sev: string; cat: string; q: string } = { sev: "", cat: "", q: "" };
  private sel: SenseiIssue | null = null;
  private side: "chat" | "jobs" | "brain" = "chat";
  private chat: ChatMsg[] = [];
  private thinking = false;
  private brain: BrainSettings | null = null;
  private ollama: OllamaStatus | null = null;
  private ollamaNote = "";
  // parts
  private topEl = h("div", { class: "sx-top" });
  private scoreEl = h("div", { class: "sx-score" });
  private listEl = h("div", { class: "sx-list" });
  private detailEl = h("div", { class: "sx-detail" });
  private chipsEl = h("div", { class: "sx-chips" });
  private sideTabs = h("div", { class: "sx-tabs" });
  private sideBody = h("div", { class: "sx-sidebody" });
  private busyEl = h("div", { class: "sx-busy" });

  constructor(private s: Studio, private openShot: (shot: string, t?: number) => void) {
    void this.s;
    this.el = h("div", { class: "sx" },
      this.topEl,
      h("div", { class: "sx-main" },
        this.scoreEl,
        h("div", { class: "sx-mid" }, this.chipsEl, this.listEl, this.detailEl),
        h("div", { class: "sx-side" }, this.sideTabs, this.sideBody),
      ),
    );
    sensei.on((e) => { if (e === "report") { this.drawScore(); this.drawList(); this.drawDetail(); } else if (e === "busy") this.drawBusy(); else if (e === "tasks" && this.side === "jobs") this.drawSide(); else if (e === "chat") this.drawSide(); });
    code.on((e) => { if (e === "film") { this.chat = this.loadChat(); void sensei.load().then(() => this.drawAll()); } });
    vsd?.sensei?.onOllama((m) => { this.ollamaNote = m.phase === "download" ? `Downloading ${m.name} ${m.total ? Math.round(((m.done ?? 0) / m.total) * 100) + "%" : ""}` : m.phase === "pull" ? `${m.model}: ${m.status ?? ""} ${m.total ? Math.round(((m.done ?? 0) / m.total) * 100) + "%" : ""}` : m.phase === "installed" ? `Ollama ${m.version} installed` : m.phase; if (this.side === "brain") this.drawSide(); });
    this.chat = this.loadChat();
    this.drawAll();
  }
  shown(on: boolean): void { if (on) { void sensei.load().then(() => this.drawAll()); void this.loadBrain(); } }
  private drawAll(): void { this.drawTop(); this.drawScore(); this.drawChips(); this.drawList(); this.drawDetail(); this.drawSideTabs(); this.drawSide(); this.drawBusy(); }

  // ---------------- top bar
  private drawTop(): void {
    const name = code.info?.config.name ?? (code.dir ? code.dir.split("/").pop() : null);
    const onlyIn = h("input", { class: "sx-in", placeholder: "shots or sequences (s03, s05_b) — empty = whole film", style: "width:250px" }) as HTMLInputElement;
    const go = async (only?: string[]) => {
      if (!code.dir) { toast("Open a code film first (Code page).", "error"); return; }
      try { const r = await sensei.review({ bench: this.bench, only, images: 12 }); toast(`Sensei: ${r.digest.overall}/100 · ${r.digest.grade} · ${r.digest.pass ? "passes" : `${r.digest.counts.must} MUST`}`, r.digest.pass ? "ok" : "info"); }
      catch (e) { toast(`Sensei: ${(e as Error).message}`, "error"); }
    };
    this.topEl.replaceChildren(
      h("div", { class: "sx-brand" }, ic("spark", 18), h("b", {}, "Sensei"), h("span", {}, "the judge")),
      h("span", { class: "sx-film" }, name ? `${name}` : "No code film open"),
      h("span", { class: "sp" }),
      selectEl(BENCHES, this.bench as never, (v) => { this.bench = v; }),
      onlyIn,
      b("Review", "spark", () => void go(onlyIn.value.trim() ? onlyIn.value.split(/[\s,]+/).filter(Boolean) : undefined), "pri sm", "Look at the film (or the shots you typed) and judge it"),
      b(null, "loop", () => { const t = tasks.watching(); if (t) { tasks.cancel(t.id); toast("Sensei stopped watching builds."); } else { tasks.start("watch"); toast("Sensei will review every build's redrawn shots."); } this.drawTop(); }, `sm ${tasks.watching() ? "on" : "ghost"}`, "Watch every build and review what was redrawn"),
      this.busyEl,
    );
  }
  private drawBusy(): void {
    const bz = sensei.busy;
    this.el.classList.toggle("working", !!bz);
    this.busyEl.replaceChildren(...(bz ? [h("span", { class: "sx-busytxt" }, bz.label + (bz.detail ? ` · ${bz.detail}` : "")), h("span", { class: `sx-bar ${bz.frac < 0 ? "run" : ""}` }, h("i", { style: `width:${Math.round(Math.max(0, bz.frac) * 100)}%` })), b(null, "close", () => { if (code.dir) void film().senseiCancel(code.dir); }, "sm ghost", "Stop")] : []));
  }

  // ---------------- the verdict
  private drawScore(): void {
    const r = sensei.report;
    if (!r) {
      this.scoreEl.replaceChildren(h("div", { class: "sx-empty" }, h("div", { class: "sx-ring big" }, ring(0, "—")), h("p", {}, code.dir ? "No review yet. Press Review: Sensei looks at every body, camera, picture and sound of the film and says what is wrong, where, and how to fix it." : "Open a code film on the Code page, then review it here.")));
      return;
    }
    const cats = Object.entries(r.categories).map(([k, c]) => h("div", { class: "sx-cat", title: `${c.issues} finding(s)` }, h("span", { class: "nm" }, c.name), h("span", { class: "bar" }, h("i", { style: `width:${c.score}%;background:${col(c.score)}` })), h("span", { class: "v" }, String(c.score)), ...(void k, [])));
    const st = r.style ? h("div", { class: "sx-box" }, h("div", { class: "sx-boxh" }, "Drawing style", h("span", { class: "sp" }), h("b", { style: `color:${col(r.style.score)}` }, `${r.style.score}/100`)), h("div", { class: "sx-note" }, `against “${r.style.ref}”`), ...r.style.gaps.slice(0, 4).map((g) => h("div", { class: "sx-gap" }, g.say))) : null;
    const snd = r.sound?.measured ? h("div", { class: "sx-box" }, h("div", { class: "sx-boxh" }, "Sound"), h("div", { class: "sx-note" }, `${r.sound.measured.I ?? "?"} LUFS · range ${r.sound.measured.LRA ?? "?"} LU · peak ${r.sound.measured.peak ?? "?"} dBFS`)) : r.sound?.note ? h("div", { class: "sx-box" }, h("div", { class: "sx-boxh" }, "Sound"), h("div", { class: "sx-note" }, r.sound.note)) : null;
    const prog = r.progress ? h("div", { class: "sx-prog" }, `Since last review: ${r.progress.before.overall} → ${r.overall}`, h("b", {}, ` · ${r.progress.fixed} fixed`), r.progress.new ? ` · ${r.progress.new} new` : "") : null;
    this.scoreEl.replaceChildren(
      h("div", { class: "sx-head" }, h("div", { class: "sx-ring big" }, ring(r.overall, r.grade)), h("div", { class: "sx-verdict" }, h("div", { class: `sx-gate ${r.pass ? "pass" : "block"}` }, r.pass ? "PASSES" : "BLOCKED"), h("div", { class: "sx-note" }, r.bench.name), h("div", { class: "sx-counts" }, h("span", { class: "must" }, `${r.counts.must} MUST`), h("span", { class: "should" }, `${r.counts.should} SHOULD`), h("span", { class: "nice" }, `${r.counts.nice} nice`)))),
      prog ?? h("span"),
      h("div", { class: "sx-cats" }, ...cats),
      st ?? h("span"), snd ?? h("span"),
      h("div", { class: "sx-bind" }, ic("info", 13), r.binding),
      h("div", { class: "sx-note", style: "margin-top:auto" }, `${r.shots} shots · ${r.seconds}s · ${new Date(r.at).toLocaleString()}`),
      h("div", { class: "row", style: "gap:6px" }, b("Report", "export", async () => { const md = code.dir ? await film().senseiMarkdown(code.dir) : null; if (md) { await navigator.clipboard.writeText(md).catch(() => undefined); toast("The report (Markdown) is on the clipboard; the file is in the film's .vs/sensei folder."); } }, "sm ghost", "Copy the whole report")),
    );
  }

  // ---------------- findings
  private drawChips(): void {
    const r = sensei.report;
    const chip = (sev: string, label: string) => { const n = sev ? (r?.issues.filter((i) => i.sev === sev).length ?? 0) : (r?.issues.length ?? 0); const bt = h("button", { class: `sx-chip ${sev} ${this.filter.sev === sev ? "on" : ""}`, type: "button" }, `${label} ${n}`); bt.addEventListener("click", () => { this.filter.sev = sev; this.drawChips(); this.drawList(); }); return bt; };
    const cats: [string, string][] = [["", "All kinds"], ...Object.entries(r?.categories ?? {}).map(([k, c]) => [k, c.name] as [string, string])];
    const q = h("input", { class: "sx-in", placeholder: "find: hand, s03_b, camera…", value: this.filter.q }) as HTMLInputElement;
    q.addEventListener("input", () => { this.filter.q = q.value.toLowerCase(); this.drawList(); });
    this.chipsEl.replaceChildren(chip("", "All"), chip("must", "MUST"), chip("should", "SHOULD"), chip("nice", "Nice"), selectEl(cats, this.filter.cat as never, (v) => { this.filter.cat = v; this.drawList(); }), q);
  }
  private filtered(): SenseiIssue[] {
    const f = this.filter;
    return (sensei.report?.issues ?? []).filter((i) => (!f.sev || i.sev === f.sev) && (!f.cat || i.cat === f.cat) && (!f.q || `${i.shot} ${i.kind} ${i.actor} ${i.part} ${i.msg}`.toLowerCase().includes(f.q)));
  }
  private drawList(): void {
    this.drawChips();
    const list = this.filtered();
    if (!sensei.report) { this.listEl.replaceChildren(); return; }
    if (!list.length) { this.listEl.replaceChildren(h("div", { class: "sx-empty small" }, "Nothing here.")); return; }
    this.listEl.replaceChildren(...list.slice(0, 400).map((i) => {
      const row = h("button", { class: `sx-row ${i.sev} ${this.sel?.id === i.id ? "on" : ""}`, type: "button" },
        h("span", { class: "sev" }, SEVN[i.sev] ?? i.sev),
        h("span", { class: "where" }, i.shot ? `${i.shot}${i.t0 != null ? ` · ${i.t0}s` : ""}` : "film"),
        h("span", { class: "msg" }, i.msg),
        h("span", { class: "kind" }, i.kind));
      row.addEventListener("click", () => { this.sel = i; this.drawList(); this.drawDetail(); });
      return row;
    }), ...(list.length > 400 ? [h("div", { class: "sx-note" }, `… ${list.length - 400} more (narrow the filter)`)] : []));
  }
  private drawDetail(): void {
    const i = this.sel && sensei.report?.issues.find((x) => x.id === this.sel!.id);
    if (!i) { this.detailEl.replaceChildren(h("div", { class: "sx-note", style: "padding:10px" }, sensei.report ? "Pick a finding to see the frame where it happens, ringed, with the fix." : "")); return; }
    const pic = h("div", { class: "sx-pic wait" });
    const img = h("img", { alt: "" }) as HTMLImageElement; pic.append(img);
    void (async () => {
      try { const b64 = i.img ? await film().senseiImage(i.img) : i.shot && code.dir ? (await film().senseiIssue(code.dir, i, 0.5)).jpeg : null; if (b64) img.src = `data:image/jpeg;base64,${b64}`; else pic.classList.add("none"); }
      catch { pic.classList.add("none"); } finally { pic.classList.remove("wait"); }
    })();
    const facts = [i.actor && `who: ${i.actor}`, i.part && `part: ${i.part}`, i.value != null && `measured: ${i.value}${i.unit ? " " + i.unit : ""}${i.limit != null ? ` (limit ${i.limit})` : ""}`, i.t0 != null && i.shot && `time: ${i.t0}–${i.t1} s`].filter(Boolean) as string[];
    this.detailEl.replaceChildren(pic, h("div", { class: "sx-dtext" },
      h("div", { class: `sx-dsev ${i.sev}` }, `${SEVN[i.sev]} · ${i.kind} · ${i.cat}`),
      h("div", { class: "sx-dmsg" }, i.msg),
      i.fix ? h("div", { class: "sx-dfix" }, h("b", {}, "Fix: "), i.fix) : h("span"),
      h("div", { class: "sx-facts" }, ...facts.map((f) => h("span", {}, f))),
      h("div", { class: "row", style: "gap:6px;margin-top:8px;flex-wrap:wrap" },
        i.shot ? b("Open shot", "code", () => this.openShot(i.shot!, i.at ?? i.t0 ?? undefined), "sm", "Open this shot at this moment on the Code page") : h("span"),
        i.shot ? b("Fix by painting", "brush", () => document.dispatchEvent(new CustomEvent("vsd:openScene", { detail: `fix:${i.shot}#${i.id}` })), "sm", "Animate → Fix a film shot, at this moment, holding on to the part it names: draw the better version once and it follows that part on every frame") : h("span"),
        i.fix ? b("Copy fix", "copy", () => void navigator.clipboard.writeText(`${i.shot ?? "film"} ${i.t0 ?? ""}s — ${i.msg}\nFix: ${i.fix}`).then(() => toast("Copied.")), "sm ghost") : h("span"),
        b("Ask Sensei", "spark", () => { this.side = "chat"; this.drawSideTabs(); void this.send(`About finding ${i.id} (${i.shot} ${i.kind}): is it really visible, and what exactly should change?`); }, "sm ghost"),
      )));
  }

  // ---------------- right side: chat, jobs, brain
  private drawSideTabs(): void {
    const tab = (id: "chat" | "jobs" | "brain", label: string) => { const bt = h("button", { class: `sx-tab ${this.side === id ? "on" : ""}`, type: "button" }, label); bt.addEventListener("click", () => { this.side = id; this.drawSideTabs(); this.drawSide(); if (id === "brain") void this.loadBrain(); }); return bt; };
    const running = tasks.list.filter((t) => t.state === "running").length;
    this.sideTabs.replaceChildren(tab("chat", "Talk"), tab("jobs", `Jobs${running ? ` (${running})` : ""}`), tab("brain", "Brain"));
  }
  private drawSide(): void { this.drawSideTabs(); if (this.side === "chat") this.drawChat(); else if (this.side === "jobs") this.drawJobs(); else this.drawBrain(); }
  private loadChat(): ChatMsg[] { try { return JSON.parse(localStorage.getItem(CHATKEY(code.dir)) ?? "[]") as ChatMsg[]; } catch { return []; } }
  private saveChat(): void { try { localStorage.setItem(CHATKEY(code.dir), JSON.stringify(this.chat.slice(-60).map((m) => ({ ...m, images: undefined })))); } catch { /* full */ } }
  private async send(q: string): Promise<void> {
    if (!q.trim() || this.thinking) return;
    this.chat.push({ role: "user", text: q, at: Date.now() });
    const pending: ChatMsg = { role: "sensei", text: "thinking…", at: Date.now(), busy: true, tools: [] };
    this.chat.push(pending); this.thinking = true; this.drawChat();
    try {
      const r = await ask(q, this.chat.slice(0, -2), (n) => { pending.tools!.push(n); pending.text = `working: ${n}`; this.drawChat(); });
      this.chat[this.chat.length - 1] = r;
    } catch (e) { this.chat[this.chat.length - 1] = { role: "sensei", text: `I could not answer: ${(e as Error).message}`, at: Date.now() }; }
    finally { this.thinking = false; this.saveChat(); this.drawChat(); }
  }
  private drawChat(): void {
    const log = h("div", { class: "sx-chat" });
    if (!this.chat.length) log.append(h("div", { class: "sx-hello" }, h("b", {}, "Ask me anything about the film."), h("p", {}, "I measure every frame, so my answers come with numbers. Try:"), ...["Review the film", "What is wrong with the hands?", "Is it ready to export?", "What changed since the last review?", "Why does the 180° line matter?"].map((t) => { const bt = h("button", { class: "sx-suggest", type: "button" }, t); bt.addEventListener("click", () => void this.send(t)); return bt; })));
    for (const m of this.chat) {
      const bubble = h("div", { class: `sx-msg ${m.role} ${m.busy ? "busy" : ""}` }, h("div", { class: "txt" }, m.text));
      if (m.tools?.length && !m.busy) bubble.append(h("div", { class: "tools" }, `used: ${[...new Set(m.tools.map((t) => t.split(" ")[0]))].join(", ")}`));
      for (const src of m.images ?? []) bubble.append(h("img", { src, alt: "" }));
      if (m.links?.length) bubble.append(h("div", { class: "links" }, ...m.links.slice(0, 10).map((l) => { const bt = h("button", { type: "button", class: "sx-link" }, l.label); bt.addEventListener("click", () => { if (l.issue) { const i = sensei.report?.issues.find((x) => x.id === l.issue); if (i) { this.sel = i; this.filter = { sev: "", cat: "", q: "" }; this.drawList(); this.drawDetail(); } } else if (l.url) void vsd?.openExternal?.(l.url); }); return bt; })));
      log.append(bubble);
    }
    const inp = h("textarea", { class: "sx-ask", rows: "2", placeholder: "Ask Sensei… (Enter to send, Shift+Enter for a new line)" }) as HTMLTextAreaElement;
    inp.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); const q = inp.value; inp.value = ""; void this.send(q); } });
    const foot = h("div", { class: "sx-askrow" }, inp, b(null, "next", () => { const q = inp.value; inp.value = ""; void this.send(q); }, "pri", "Send"), b(null, "trash", () => { this.chat = []; this.saveChat(); this.drawChat(); }, "ghost sm", "Clear the conversation"));
    this.sideBody.replaceChildren(log, foot);
    log.scrollTop = log.scrollHeight;
    if (!this.thinking) inp.focus({ preventScroll: true });
  }
  private drawJobs(): void {
    const start = (k: TaskKind, p: Record<string, unknown> = {}) => { if (!code.dir && k !== "research") { toast("Open a code film first.", "error"); return; } tasks.start(k, p); this.drawJobs(); };
    const topic = h("input", { class: "sx-in", placeholder: "research a topic on the web…" }) as HTMLInputElement;
    const goal = h("input", { class: "sx-in", placeholder: "a job for Sensei (needs a model brain)…" }) as HTMLInputElement;
    const rows = tasks.list.map((t) => h("div", { class: `sx-job ${t.state}` },
      h("div", { class: "top" }, h("b", {}, t.title), h("span", { class: "sp" }), h("span", { class: "st" }, t.state), t.state === "running" ? b(null, "close", () => { tasks.cancel(t.id); this.drawJobs(); }, "sm ghost", "Stop") : h("span")),
      t.state === "running" ? h("span", { class: `sx-bar ${t.progress < 0 ? "run" : ""}` }, h("i", { style: `width:${Math.round(Math.max(0, t.progress) * 100)}%` })) : h("span"),
      h("div", { class: "note" }, t.error ?? t.text ?? t.note)));
    this.sideBody.replaceChildren(h("div", { class: "sx-jobs" },
      h("div", { class: "sx-jobstart" },
        b("Full review", "spark", () => start("review", { bench: this.bench }), "sm"),
        b(tasks.watching() ? "Watching builds" : "Watch builds", "loop", () => { const w = tasks.watching(); if (w) tasks.cancel(w.id); else start("watch"); this.drawJobs(); this.drawTop(); }, `sm ${tasks.watching() ? "on" : ""}`),
        b("Fix loop", "wand", () => start("fix", { bench: this.bench }), "sm", "Review → repair (model brain with code edits) → review, until it passes"),
        b("Learn a style…", "image", async () => { const files = (await vsd?.pick?.({ title: "Pictures in the style to learn", multiple: true }).catch(() => [] as string[])) ?? []; if (files.length) start("style", { files }); }, "sm"),
      ),
      h("div", { class: "row", style: "gap:6px" }, topic, b(null, "search", () => { if (topic.value.trim()) start("research", { topic: topic.value.trim() }); }, "sm", "Research")),
      h("div", { class: "row", style: "gap:6px" }, goal, b(null, "next", () => { if (goal.value.trim()) start("goal", { text: goal.value.trim() }); }, "sm", "Give Sensei this job")),
      ...(rows.length ? rows : [h("div", { class: "sx-note" }, "No jobs yet. Sensei can review, watch every build, research, learn a style from pictures, or chase a goal with its tools — side by side with your work.")]),
    ));
  }
  private async loadBrain(): Promise<void> {
    const api = vsd?.sensei; if (!api) return;
    this.brain = await api.settings().catch(() => null);
    this.ollama = this.brain?.provider === "ollama" || !this.ollama ? await api.ollama().catch(() => null) : this.ollama;
    if (this.side === "brain") this.drawBrain();
  }
  private drawBrain(): void {
    const api = vsd?.sensei, st = this.brain;
    if (!api || !st) { this.sideBody.replaceChildren(h("div", { class: "sx-note" }, "Loading…")); return; }
    const set = async (patch: Record<string, unknown>) => { try { this.brain = await api.setSettings(patch as never); sensei.emit("brain"); this.drawBrain(); } catch (e) { toast((e as Error).message, "error"); } };
    const prov: [string, string][] = [["builtin", "Built-in (no model)"], ["ollama", "Ollama — local, free, your GPU"], ["openai", "OpenAI-compatible (key or server)"], ["anthropic", "Anthropic Claude (key)"], ["gemini", "Google Gemini (key)"]];
    const model = h("input", { class: "sx-in", placeholder: st.modelDefault[st.provider] ?? "", value: st.model }) as HTMLInputElement;
    const vision = h("input", { class: "sx-in", placeholder: st.visionDefault[st.provider] ?? "", value: st.visionModel }) as HTMLInputElement;
    const url = h("input", { class: "sx-in", placeholder: st.provider === "ollama" ? "http://127.0.0.1:11434" : "https://api.openai.com/v1", value: st.url }) as HTMLInputElement;
    const key = h("input", { class: "sx-in", type: "password", placeholder: st.keys[st.provider] ? `saved ${st.keys[st.provider]}` : "API key (kept encrypted on this PC)" }) as HTMLInputElement;
    const ol = this.ollama;
    const olBox = st.provider === "ollama" ? h("div", { class: "sx-box" },
      h("div", { class: "sx-boxh" }, "Ollama on this PC", h("span", { class: "sp" }), h("span", { class: ol?.running ? "ok" : "bad" }, ol?.running ? `running ${ol.version ?? ""}` : "not running")),
      h("div", { class: "sx-note" }, ol?.models.length ? `Models: ${ol.models.map((m) => m.name).join(", ")}` : "No models yet. A chat model (qwen3:8b) and a vision model (qwen2.5vl:7b) make Sensei talk and look at frames; both run on your GPU."),
      this.ollamaNote ? h("div", { class: "sx-note", style: "color:var(--neon)" }, this.ollamaNote) : h("span"),
      h("div", { class: "row", style: "gap:6px;flex-wrap:wrap" },
        !ol?.running && !ol?.installed && !ol?.system ? b("Install Ollama", "download", async () => { try { await api.ollamaInstall(); toast("Ollama installed."); await api.ollamaStart(); } catch (e) { toast((e as Error).message, "error"); } void this.loadBrain(); }, "sm pri", "Downloads the official Linux build from GitHub, checks its SHA-256, unpacks it into VideoStudio's own folder") : h("span"),
        !ol?.running && (ol?.installed || ol?.system) ? b("Start", "play", async () => { try { await api.ollamaStart(); } catch (e) { toast((e as Error).message, "error"); } void this.loadBrain(); }, "sm") : h("span"),
        ol?.running ? b(`Get ${model.value || st.modelDefault.ollama}`, "download", async () => { try { await api.ollamaPull(model.value || st.modelDefault.ollama!); } catch (e) { toast((e as Error).message, "error"); } void this.loadBrain(); }, "sm") : h("span"),
        ol?.running ? b(`Get ${vision.value || st.visionDefault.ollama}`, "download", async () => { try { await api.ollamaPull(vision.value || st.visionDefault.ollama!); } catch (e) { toast((e as Error).message, "error"); } void this.loadBrain(); }, "sm") : h("span"),
        b(null, "loop", () => void this.loadBrain(), "sm ghost", "Refresh"),
      )) : h("span");
    this.sideBody.replaceChildren(h("div", { class: "sx-brain" },
      h("div", { class: "sx-note" }, "Sensei always measures (no model needed). A language model lets it talk freely, look at frames with its own eyes and chase jobs with its tools."),
      h("label", { class: "sx-lbl" }, "Brain", selectEl(prov, st.provider as never, (v) => void set({ provider: v }).then(() => this.loadBrain()))),
      st.provider !== "builtin" ? h("label", { class: "sx-lbl" }, "Chat model", model) : h("span"),
      st.provider !== "builtin" ? h("label", { class: "sx-lbl" }, "Vision model", vision) : h("span"),
      st.provider === "ollama" || st.provider === "openai" ? h("label", { class: "sx-lbl" }, "Server", url) : h("span"),
      ["openai", "anthropic", "gemini"].includes(st.provider) ? h("label", { class: "sx-lbl" }, "Key", key) : h("span"),
      st.provider !== "builtin" ? h("div", { class: "row", style: "gap:6px" }, b("Save", "check", () => void set({ model: model.value.trim(), visionModel: vision.value.trim(), url: url.value.trim(), ...(key.value ? { key: key.value.trim() } : {}) }), "sm pri"), b("Test", "spark", async () => { try { const r = await api.test(); toast(r.ok ? `${r.model} answered in ${r.ms} ms` : `It answered: ${r.text}`, r.ok ? "ok" : "info"); } catch (e) { toast((e as Error).message, "error"); } }, "sm")) : h("span"),
      olBox,
      h("label", { class: "sx-check" }, (() => { const c = h("input", { type: "checkbox" }) as HTMLInputElement; c.checked = st.web; c.addEventListener("change", () => void set({ web: c.checked })); return c; })(), "Sensei may use the internet (search, read pages; never anything on your own network)"),
      h("label", { class: "sx-check" }, (() => { const c = h("input", { type: "checkbox" }) as HTMLInputElement; c.checked = st.edits; c.addEventListener("change", () => void set({ edits: c.checked })); return c; })(), "Sensei may change the film's code when asked to fix (fix loop, jobs)"),
      h("div", { class: "sx-note" }, st.encrypted ? "Keys are encrypted by the system keyring." : "Keys are stored in a private file (no system keyring found)."),
    ));
  }
}

function ring(v: number, label: string): SVGSVGElement {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 100 100");
  const r = 42, c = 2 * Math.PI * r;
  svg.innerHTML = `<circle cx="50" cy="50" r="${r}" fill="none" stroke="rgba(255,255,255,0.08)" stroke-width="9"/><circle cx="50" cy="50" r="${r}" fill="none" stroke="${col(v)}" stroke-width="9" stroke-linecap="round" stroke-dasharray="${(c * v) / 100} ${c}" transform="rotate(-90 50 50)"/><text x="50" y="49" text-anchor="middle" font-size="25" font-weight="700" fill="#eef2f8">${v || "—"}</text><text x="50" y="68" text-anchor="middle" font-size="12" fill="#9aa6ba">${label}</text>`;
  return svg;
}
const col = (v: number) => (v >= 88 ? "#3ee6c4" : v >= 75 ? "#9be15d" : v >= 60 ? "#ffbf5e" : "#ff6b7d");
