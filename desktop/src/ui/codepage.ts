/**
 * The Code workspace: a film written as code. Files and a real code editor on the left, the frame under the cursor on
 * the right (drawn from the code at once, no render), and the film's shots below — green when drawn and still right,
 * amber when the code moved on. "Draw changed" redraws only the amber ones and joins the film again in seconds.
 */
import { EditorView, basicSetup } from "codemirror";
import { EditorState, Compartment } from "@codemirror/state";
import { keymap } from "@codemirror/view";
import { indentWithTab } from "@codemirror/commands";
import { javascript } from "@codemirror/lang-javascript";
import { json } from "@codemirror/lang-json";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
import type { Studio } from "../../../src/app/studio";
import { toast } from "../../../src/ui/dom";
import { vsd } from "../bridge";
import { code } from "../film/session";
import { film } from "../film/prepare";
import type { Asset2D, FilmShot, LibraryPerson } from "../film/types";
import { snippet2d } from "../film/session";
import { assistant, diffLines, outline, type AssistContext } from "../film/codeassist";
import { sensei } from "../sensei/store";
import type { KitIndex } from "../film/types";
import { b, h, ic, selectEl } from "./kit";

const ink = EditorView.theme(
  {
    "&": { color: "#dfe6f0", backgroundColor: "transparent", height: "100%", fontSize: "12.5px" },
    ".cm-content": { fontFamily: "var(--mono)", caretColor: "#3ee6c4", padding: "8px 0" },
    ".cm-scroller": { lineHeight: "1.55", overflow: "auto" },
    ".cm-gutters": { backgroundColor: "transparent", color: "#4d566a", border: "none" },
    ".cm-activeLine": { backgroundColor: "rgba(255,255,255,0.035)" },
    ".cm-activeLineGutter": { backgroundColor: "transparent", color: "#9aa6ba" },
    "&.cm-focused .cm-cursor": { borderLeftColor: "#3ee6c4" },
    "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection": { backgroundColor: "rgba(62,230,196,0.22)" },
    ".cm-selectionMatch": { backgroundColor: "rgba(138,125,255,0.22)" },
    ".cm-matchingBracket": { backgroundColor: "rgba(62,230,196,0.18)", outline: "1px solid rgba(62,230,196,0.4)" },
    ".cm-panels": { backgroundColor: "#11151d", color: "#dfe6f0" },
    ".cm-tooltip": { backgroundColor: "#141924", border: "1px solid rgba(255,255,255,0.12)", color: "#dfe6f0" },
    ".cm-tooltip-autocomplete ul li[aria-selected]": { backgroundColor: "rgba(62,230,196,0.2)", color: "#fff" },
    ".cm-searchMatch": { backgroundColor: "rgba(255,191,94,0.3)" },
    ".cm-foldPlaceholder": { backgroundColor: "rgba(255,255,255,0.08)", border: "none", color: "#9aa6ba" },
  },
  { dark: true },
);
const inkSyntax = syntaxHighlighting(
  HighlightStyle.define([
    { tag: [t.keyword, t.operatorKeyword, t.modifier], color: "#b49dff" },
    { tag: [t.string, t.special(t.string), t.regexp], color: "#8fe0a8" },
    { tag: [t.number, t.bool, t.null, t.atom], color: "#ffbf5e" },
    { tag: [t.comment, t.lineComment, t.blockComment], color: "#5f6b80", fontStyle: "italic" },
    { tag: [t.function(t.variableName), t.function(t.propertyName)], color: "#6fd3ff" },
    { tag: [t.propertyName], color: "#cfd8e6" },
    { tag: [t.definition(t.variableName), t.className, t.typeName], color: "#3ee6c4" },
    { tag: [t.operator, t.punctuation, t.bracket], color: "#8a94a6" },
  ]),
);

const fmt = (s: number) => `${Math.floor(s / 60)}:${(s % 60).toFixed(s < 10 ? 2 : 1).padStart(s < 10 ? 4 : 4, "0")}`;
type Tab = "files" | "people" | "places" | "assets";

export class CodePage {
  readonly el: HTMLElement;
  private visible = false;
  private view: EditorView;
  private lang = new Compartment();
  private file: string | null = null;
  private saved = "";
  private saveTimer = 0;
  private loading = false;
  private shot: string | null = null;
  private shotT = 0;
  private frameTimer = 0;
  private frameBusy = false;
  private frameWant = false;
  private tab: Tab = "files";
  private peopleGroup = "";
  private people: LibraryPerson[] | null = null;
  private groups: { id: string; name: string; count: number }[] = [];
  private places: string[] | null = null;
  private assets: Asset2D[] | null = null;
  /** the code assistant's knowledge: the kit's names and catalogues; the film's lines, cues and actors */
  private kit: KitIndex | null = null;
  private scriptLines: Record<string, string> = {};
  private cueNames: string[] = [];
  private outlineSel = h("select", { class: "cs-outline", title: "Go to a sequence, a shot or a function of this file" }) as HTMLSelectElement;
  private consoleEl = h("div", { class: "cs-console" });
  private consoleOut = h("div", { class: "cs-conout mono" });
  private consoleIn = h("input", { class: "cs-conin mono", placeholder: "Console: an expression with the film kit — T('s01').t('M1'), models('tower'), shot('s01_a').dur …", spellcheck: "false" }) as HTMLInputElement;
  private consoleHist: string[] = [];
  private consoleAt = -1;
  private findQ = "";
  private findHits: { file: string; line: number; text: string }[] | null = null;
  private assetCat = "backgrounds";
  private assetQ = "";
  private thumbs = new IntersectionObserver((es) => {
    for (const e of es) if (e.isIntersecting) this.loadThumb(e.target as HTMLElement);
  });

  private titleEl = h("button", { class: "cs-film", type: "button", title: "Films" });
  private metaEl = h("span", { class: "cs-meta" });
  /** The kind of animation the whole film is drawn as (film/engine/look.js). */
  private lookSel = selectEl(
    [["anime", "Look: 2D anime"], ["multiplane", "Look: 2.5D multiplane"], ["soft3d", "Look: 3D render"], ["cel3d", "Look: 3.5D cel-shaded"], ["immersive", "Look: 4D immersive"], ["paper", "Look: paper cut"], ["puppet", "Look: cut-out puppet"], ["flat", "Look: flat motion graphics"], ["pixel", "Look: pixel art"], ["sketch", "Look: pencil sketch"], ["manga", "Look: manga"]] as [string, string][],
    "anime",
    (v) => void this.setLook(v),
  );
  private barEl = h("div", { class: "cs-bar" }, h("i", {}));
  private busyEl = h("span", { class: "cs-busy" });
  private sideEl = h("div", { class: "cs-side-body" });
  private tabsEl = h("div", { class: "cs-tabs" });
  private fileEl = h("span", { class: "cs-file" }, "No file");
  private dirtyEl = h("span", { class: "cs-dirty" });
  private edEl = h("div", { class: "cs-editor" });
  private imgEl = h("img", { class: "cs-img", alt: "" }) as HTMLImageElement;
  private vidEl = h("video", { class: "cs-vid", controls: "", playsinline: "" }) as HTMLVideoElement;
  private stageEl = h("div", { class: "cs-stage" });
  private scrub = h("input", { type: "range", min: "0", max: "1", step: "0.001", value: "0.5", class: "cs-scrub" }) as HTMLInputElement;
  private tEl = h("span", { class: "cs-t mono" }, "–");
  private shotEl = h("span", { class: "cs-shotname" }, "No shot");
  private probEl = h("div", { class: "cs-problems" });
  private stripEl = h("div", { class: "cs-strip" });
  private drawBtn: HTMLButtonElement;
  private draftBtn: HTMLButtonElement;
  private stopBtn: HTMLButtonElement;

  constructor(private s: Studio) {
    this.view = new EditorView({
      parent: this.edEl,
      state: EditorState.create({
        doc: "",
        extensions: [
          basicSetup,
          keymap.of([indentWithTab, { key: "Mod-s", preventDefault: true, run: () => (void this.save(), true) }, { key: "Mod-Enter", preventDefault: true, run: () => (void this.build(false), true) }]),
          this.lang.of(javascript()),
          ink,
          inkSyntax,
          ...assistant(this.assist()),
          EditorView.updateListener.of((u) => {
            if (u.docChanged) this.drawOutline();
            if (!u.docChanged || this.loading) return;
            this.markDirty();
            window.clearTimeout(this.saveTimer);
            this.saveTimer = window.setTimeout(() => void this.save(), 1400);
          }),
        ],
      }),
    });
    this.drawBtn = b("Draw changed", "play", () => void this.build(false), "pri", "Draw only the shots whose picture changed, then join the film (Ctrl+Enter)");
    this.draftBtn = b("Draft", null, () => void this.build(true), "", "A quick small version of the changed shots");
    this.stopBtn = b("Stop", null, () => void code.stop(), "", "Stop drawing (finished shots are kept)");
    this.titleEl.addEventListener("click", () => void this.pickFilm());
    const top = h(
      "div",
      { class: "cs-top" },
      this.titleEl,
      this.metaEl,
      this.lookSel,
      h("span", { class: "sp" }),
      this.busyEl,
      this.barEl,
      this.drawBtn,
      this.draftBtn,
      this.stopBtn,
      b("Play film", "film", () => void this.playFilm(), "", "Play the joined film"),
      b("Export", "export", () => void this.exportFilm(), "", "Save the film into your Videos folder"),
      b(null, "folderOpen", () => code.dir && void vsd?.openPath(code.dir), "", "Open the film's folder"),
    );
    for (const [id, label] of [["files", "Files"], ["people", "People"], ["places", "Places"], ["assets", "2D"]] as [Tab, string][]) {
      const el = h("button", { class: "cs-tab", type: "button" }, label);
      el.addEventListener("click", () => {
        this.tab = id;
        this.drawSide();
      });
      this.tabsEl.append(el);
    }
    const side = h("div", { class: "cs-side" }, this.tabsEl, this.sideEl);
    this.outlineSel.addEventListener("change", () => {
      const ln = Number(this.outlineSel.value);
      if (ln > 0) this.goLine(ln);
      this.outlineSel.value = "";
    });
    const insertSel = h("select", { class: "cs-outline", title: "Put a ready piece of film code at the cursor" }) as HTMLSelectElement;
    insertSel.append(h("option", { value: "" }, "Insert…"), ...SNIPPETS.map(([label], i) => h("option", { value: String(i) }, label)));
    insertSel.addEventListener("change", () => {
      const sn = SNIPPETS[Number(insertSel.value)];
      if (sn) this.insert(sn[1]);
      insertSel.value = "";
    });
    this.consoleIn.addEventListener("keydown", (e) => {
      if (e.key === "Enter") void this.runConsole();
      else if (e.key === "ArrowUp" && this.consoleHist.length) { this.consoleAt = Math.min(this.consoleHist.length - 1, this.consoleAt + 1); this.consoleIn.value = this.consoleHist[this.consoleHist.length - 1 - this.consoleAt] ?? ""; e.preventDefault(); }
      else if (e.key === "ArrowDown") { this.consoleAt = Math.max(-1, this.consoleAt - 1); this.consoleIn.value = this.consoleAt < 0 ? "" : this.consoleHist[this.consoleHist.length - 1 - this.consoleAt] ?? ""; e.preventDefault(); }
    });
    this.consoleEl.append(this.consoleOut, this.consoleIn);
    const conBtn = b(null, "code", () => { this.consoleEl.classList.toggle("on"); if (this.consoleEl.classList.contains("on")) this.consoleIn.focus(); }, "sm ghost", "Console: run an expression with the film kit and this film");
    const editor = h("div", { class: "cs-mid" }, h("div", { class: "cs-edhead" }, this.fileEl, this.dirtyEl, h("span", { class: "sp" }), this.outlineSel, insertSel, b(null, "undo", () => void this.history(), "sm ghost", "Earlier versions of this file: compare and bring one back"), conBtn, h("span", { class: "cs-hint" }, "Ctrl+S save · Ctrl+Enter draw · Ctrl+Space complete")), this.edEl, this.consoleEl);
    this.stageEl.append(this.imgEl, this.vidEl);
    this.scrub.addEventListener("input", () => {
      const sh = this.curShot();
      if (!sh) return;
      this.shotT = Number(this.scrub.value) * sh.dur;
      this.tEl.textContent = `${this.shotT.toFixed(2)}s`;
      this.askFrame(60);
    });
    const view = h(
      "div",
      { class: "cs-view" },
      this.stageEl,
      h(
        "div",
        { class: "cs-viewbar" },
        this.shotEl,
        this.scrub,
        this.tEl,
        b(null, "play", () => void this.playShot(), "", "Play this shot as it was drawn"),
        b("Sheet", null, () => void this.sheet(), "sm", "Six moments of this shot in one picture"),
        b("Paint fix", "brush", () => this.shot && document.dispatchEvent(new CustomEvent("vsd:openScene", { detail: `fix:${this.shot}` })), "sm", "Fix this shot by painting over it in Animate: what you draw follows the body part on every frame"),
      ),
      this.probEl,
    );
    this.el = h("div", { class: "cs" }, top, h("div", { class: "cs-main" }, side, editor, view), this.stripEl);

    code.on((e) => {
      if (!this.visible && e !== "film") return;
      if (e === "film") {
        this.file = null;
        this.shot = null;
        this.findHits = null;
        void this.loadFilmWords();
        this.drawAll();
        void this.openFile(this.firstFile());
      } else if (e === "files") {
        this.drawTop();
        this.drawSide();
        void this.syncFile();
      } else if (e === "status") {
        this.drawTop();
        this.drawStrip();
        this.drawProblems();
      } else if (e === "busy") this.drawBusy();
      else if (e === "frame") this.showPicture();
    });
  }

  shown(on: boolean): void {
    this.visible = on;
    if (!on) {
      this.vidEl.pause();
      return;
    }
    if (!vsd?.film) {
      this.el.replaceChildren(h("div", { class: "cs-empty" }, "Code Studio needs VideoStudio Desktop."));
      return;
    }
    void (async () => {
      if (!code.dir) await code.restore();
      else await code.refresh();
      this.drawAll();
      if (!this.file) await this.openFile(this.firstFile());
      if (!this.kit) this.kit = (await film().kitIndex?.().catch(() => null)) ?? null;
      void this.loadFilmWords();
      void sensei.load?.().catch(() => undefined);
    })();
  }

  /* ------------------------------------------------------------ drawing the page */

  private drawAll(): void {
    this.drawTop();
    this.drawSide();
    this.drawStrip();
    this.drawProblems();
    this.drawBusy();
    this.showPicture();
  }
  private drawTop(): void {
    const i = code.info;
    this.titleEl.replaceChildren(ic("film", 15), document.createTextNode(i ? i.config.name : code.dir ? code.dir.split("/").pop()! : "Open or start a film"), ic("down", 13));
    const todo = this.todo();
    this.metaEl.textContent = i ? `${i.config.width}×${i.config.height} · ${i.config.fps} fps · ${i.config.langs.join(" + ")} · ${fmt(i.total)} · ${i.shots.length} shots` : "";
    const lk = i?.config.look, style = typeof lk === "string" ? lk : lk?.style ?? "anime";
    const ALIAS: Record<string, string> = { "2d": "anime", "2.5d": "multiplane", "3d": "soft3d", "3.5d": "cel3d", "4d": "immersive" };
    this.lookSel.value = ALIAS[style] ?? style;
    this.lookSel.disabled = !i;
    this.lookSel.title = "The kind of animation the whole film is drawn as. A sequence or a shot can have its own (look: 'sketch').";
    this.drawBtn.lastChild!.textContent = todo ? `Draw ${todo} changed` : "Up to date";
    this.drawBtn.classList.toggle("pri", todo > 0 || this.needsWork());
  }
  private async setLook(v: string): Promise<void> {
    if (!code.info) return;
    const { runQuiet } = await import("../../../src/agent/api");
    const [r] = await runQuiet(this.s, [{ op: "code.config", look: v === "anime" ? "" : v } as never]);
    if (r && !r.ok) toast(String(r.error ?? "The look could not be changed."));
    else toast("The film will be drawn in the new look. Draw changed to see it.");
    this.drawTop();
  }
  private needsWork(): boolean {
    const n = code.status?.needs;
    return !!n && n.motion.length + n.voices.length + n.sounds.length > 0;
  }
  private todo(): number {
    return (code.status?.shots ?? []).filter((s) => !s.off && (s.variants ?? []).some((v) => v.state !== "ready")).length;
  }
  private drawBusy(): void {
    const bz = code.busy;
    this.el.classList.toggle("working", !!bz);
    this.busyEl.textContent = bz ? `${bz.label}${bz.detail ? ` · ${bz.detail}` : ""}` : "";
    const bar = this.barEl.firstElementChild as HTMLElement;
    this.barEl.classList.toggle("run", !!bz && bz.frac < 0);
    bar.style.width = bz ? `${bz.frac < 0 ? 100 : Math.round(bz.frac * 100)}%` : "0";
    this.stopBtn.style.display = bz ? "" : "none";
    this.drawBtn.disabled = this.draftBtn.disabled = !!bz;
  }
  private firstFile(): string | null {
    const files = (code.info?.files ?? []).map((f) => f.file).filter((f) => f !== "vs.js");
    return files.find((f) => f.startsWith("seq/")) ?? files.find((f) => f === "film.js") ?? files[0] ?? null;
  }

  private drawSide(): void {
    [...this.tabsEl.children].forEach((el, i) => el.classList.toggle("on", ["files", "people", "places", "assets"][i] === this.tab));
    if (this.tab === "files") this.drawFiles();
    else if (this.tab === "people") void this.drawPeople();
    else if (this.tab === "assets") void this.drawAssets();
    else void this.drawPlaces();
  }
  private drawFiles(): void {
    const files = (code.info?.files ?? []).filter((f) => f.file !== "vs.js");
    const rows = files.map((f) => {
      const el = h("button", { class: `cs-row ${f.file === this.file ? "on" : ""}`, type: "button", title: f.file }, h("span", { class: "nm" }, f.file), h("span", { class: "sz" }, f.bytes > 9999 ? `${Math.round(f.bytes / 1000)}k` : String(f.bytes)));
      el.addEventListener("click", () => void this.openFile(f.file));
      return el;
    });
    const add = b("New file", "plus", () => void this.newFile(), "sm ghost", "Add a sequence or a helper file");
    // find in every file of the film
    const q = h("input", { class: "cs-sel", type: "search", placeholder: "Find in all files…", value: this.findQ }) as HTMLInputElement;
    q.addEventListener("keydown", (e) => { if (e.key === "Enter") void this.findAll(q.value); else if (e.key === "Escape") { this.findQ = ""; this.findHits = null; this.drawFiles(); } });
    let body: HTMLElement[] = rows.length ? rows : [h("div", { class: "cs-note" }, code.dir ? (code.error ?? "This film has no files yet.") : "Open a film, or start a new one from a template.")];
    if (this.findHits) {
      body = this.findHits.length
        ? [h("div", { class: "cs-note" }, `${this.findHits.length} place${this.findHits.length === 1 ? "" : "s"} · Esc to go back`), ...this.findHits.slice(0, 300).map((x) => {
            const el = h("button", { class: "cs-row cs-hit", type: "button", title: `${x.file}:${x.line}` }, h("span", { class: "nm" }, `${x.file}:${x.line}`), h("span", { class: "tx mono" }, x.text.trim().slice(0, 90)));
            el.addEventListener("click", () => void this.openFile(x.file).then(() => this.goLine(x.line)));
            return el;
          })]
        : [h("div", { class: "cs-note" }, `"${this.findQ}" is in no file of this film.`)];
    }
    this.sideEl.replaceChildren(q, ...body, h("div", { class: "cs-sidefoot" }, add, b("Guide", "info", () => void this.guide(), "sm ghost", "How to write a film as code")));
  }
  private async findAll(query: string): Promise<void> {
    this.findQ = query;
    if (!query.trim() || !code.dir) {
      this.findHits = null;
      return this.drawFiles();
    }
    const dir = code.dir, files = (code.info?.files ?? []).filter((f) => f.file !== "vs.js" && /\.(js|mjs|json)$/.test(f.file)), hits: { file: string; line: number; text: string }[] = [];
    const re = (() => { try { return query.startsWith("/") && query.lastIndexOf("/") > 0 ? new RegExp(query.slice(1, query.lastIndexOf("/")), query.slice(query.lastIndexOf("/") + 1) || "i") : null; } catch { return null; } })(), low = query.toLowerCase();
    for (const f of files) {
      const text = f.file === this.file ? this.view.state.doc.toString() : await film().read(dir, f.file).catch(() => "");
      text.split("\n").forEach((l, i) => { if (re ? re.test(l) : l.toLowerCase().includes(low)) hits.push({ file: f.file, line: i + 1, text: l }); });
    }
    this.findHits = hits;
    this.drawFiles();
  }
  private async drawPeople(): Promise<void> {
    if (!this.people) {
      this.sideEl.replaceChildren(h("div", { class: "cs-note" }, "Loading the people library…"));
      try {
        const r = await film().people();
        this.people = r.people;
        const by = new Map<string, number>();
        for (const p of r.people) by.set(p.group, (by.get(p.group) ?? 0) + 1);
        this.groups = [...by.entries()].map(([id, count]) => ({ id, name: id.replace(/_/g, " "), count }));
      } catch (e) {
        this.sideEl.replaceChildren(h("div", { class: "cs-note" }, e instanceof Error ? e.message : String(e)));
        return;
      }
      if (this.tab !== "people") return;
    }
    const sel = h("select", { class: "cs-sel" }) as HTMLSelectElement;
    sel.append(h("option", { value: "" }, `All groups · ${this.people.length} people`), ...this.groups.map((g) => h("option", { value: g.id }, `${g.name} · ${g.count}`)));
    sel.value = this.peopleGroup;
    sel.addEventListener("change", () => {
      this.peopleGroup = sel.value;
      void this.drawPeople();
    });
    const list = this.people.filter((p) => !this.peopleGroup || p.group === this.peopleGroup).slice(0, this.peopleGroup ? 400 : 120);
    const grid = h("div", { class: "cs-people" });
    for (const p of list) {
      const card = h("button", { class: "cs-person", type: "button", title: `${p.id} — click to put person('${p.id}') into the code`, "data-id": p.id }, h("span", { class: "ph" }), h("span", { class: "nm" }, this.peopleGroup ? p.id.slice(this.peopleGroup.length + 1) : p.id.replace(/_/g, " ")));
      card.addEventListener("click", () => this.insert(`person('${p.id}')`));
      this.thumbs.observe(card);
      grid.append(card);
    }
    this.sideEl.replaceChildren(sel, grid, h("div", { class: "cs-note" }, this.peopleGroup ? "Click a person to put them into the code at the cursor." : "Pick a group to see everyone in it."));
  }
  /** The 2D library: pick a kind, search, click to put the asset's code at the cursor. */
  private async drawAssets(): Promise<void> {
    const f = film();
    if (!this.assets) {
      if (!f.library2d) return void this.sideEl.replaceChildren(h("div", { class: "cs-note" }, "The 2D library comes with the desktop app."));
      this.sideEl.replaceChildren(h("div", { class: "cs-note" }, "Loading the 2D library…"));
      try {
        this.assets = (await f.library2d()).assets;
      } catch (e) {
        return void this.sideEl.replaceChildren(h("div", { class: "cs-note" }, e instanceof Error ? e.message : String(e)));
      }
      if (this.tab !== "assets") return;
    }
    const all = this.assets, cats = [...new Set(all.map((a) => a.cat))];
    const sel = h("select", { class: "cs-sel" }) as HTMLSelectElement;
    sel.append(h("option", { value: "" }, `Everything · ${all.length}`), ...cats.map((c) => h("option", { value: c }, `${c} · ${all.filter((a) => a.cat === c).length}`)));
    sel.value = this.assetCat;
    sel.addEventListener("change", () => {
      this.assetCat = sel.value;
      void this.drawAssets();
    });
    const q = h("input", { class: "cs-sel", type: "search", placeholder: "Search: fire, katana, dusk, chibi…", value: this.assetQ }) as HTMLInputElement;
    q.addEventListener("change", () => {
      this.assetQ = q.value;
      void this.drawAssets();
    });
    const words = this.assetQ.toLowerCase().split(/\s+/).filter(Boolean);
    const list = all.filter((a) => (!this.assetCat || a.cat === this.assetCat) && words.every((w) => `${a.id} ${a.name} ${(a.tags ?? []).join(" ")}`.toLowerCase().includes(w))).slice(0, 240);
    const grid = h("div", { class: "cs-people cs-assets" });
    for (const a of list) {
      const card = h("button", { class: "cs-person", type: "button", title: `${a.name}\n${a.id}${a.layers ? ` · ${a.layers} layers for the multiplane camera` : ""}${a.rig ? " · a cut-out rig: walks, poses, blinks, speaks" : ""}\nClick to put it into the code`, "data-id": a.id, "data-kind": "asset" }, h("span", { class: "ph" }), h("span", { class: "nm" }, a.name.replace(/ — /g, " · ")));
      card.addEventListener("click", () => this.insert(snippet2d(a)));
      this.thumbs.observe(card);
      grid.append(card);
    }
    this.sideEl.replaceChildren(sel, q, grid, h("div", { class: "cs-note" }, list.length ? "Click an asset to put the code that uses it at the cursor. The film kit draws it: parallax(), cutoutActor(), sticker(), pop()." : "Nothing matches."));
  }
  private loadThumb(card: HTMLElement): void {
    this.thumbs.unobserve(card);
    const id = card.dataset.id!;
    if (card.dataset.kind === "asset") {
      const f = film();
      if (!f.asset2dThumb) return;
      void f
        .asset2dThumb(id)
        .then((r) => card.querySelector(".ph")?.replaceWith(h("img", { alt: id, src: `data:image/png;base64,${r.png}` })))
        .catch(() => card.querySelector(".ph")?.classList.add("bad"));
      return;
    }
    void film()
      .personThumb(id, 240)
      .then((r) => {
        const img = h("img", { alt: id, src: `data:image/jpeg;base64,${r.jpeg}` });
        card.querySelector(".ph")?.replaceWith(img);
      })
      .catch(() => card.querySelector(".ph")?.classList.add("bad"));
  }
  private async drawPlaces(): Promise<void> {
    if (!this.places) {
      try {
        this.places = (await film().sets()).sets;
      } catch (e) {
        this.sideEl.replaceChildren(h("div", { class: "cs-note" }, e instanceof Error ? e.message : String(e)));
        return;
      }
      if (this.tab !== "places") return;
    }
    const rows = this.places.map((p) => {
      const el = h("button", { class: "cs-row", type: "button", title: `Put ${p}() into the code` }, h("span", { class: "nm" }, p), h("span", { class: "sz" }, "set"));
      el.addEventListener("click", () => this.insert(`${p}({ time: 'day' })`));
      return el;
    });
    this.sideEl.replaceChildren(...rows, h("div", { class: "cs-note" }, "A sequence plays in one place: set: () => town({ time: 'dusk' }). Click to put a place into the code."));
  }

  private drawStrip(): void {
    const st = code.status, info = code.info;
    if (!info) {
      this.stripEl.replaceChildren(h("div", { class: "cs-note" }, code.error ? "The film's code does not load — see the message on the right." : "The film's shots appear here."));
      return;
    }
    const total = Math.max(0.001, info.shots.reduce((a, s) => a + s.dur, 0));
    const groups: HTMLElement[] = [];
    for (const seq of info.seqs) {
      const shots = info.shots.filter((s) => s.seq === seq.id);
      const row = h("div", { class: "cs-seq", style: `flex:${Math.max(0.02, shots.reduce((a, s) => a + s.dur, 0) / total)} 1 0` }, h("div", { class: "cs-seqname" }, seq.id, h("small", {}, ` ${fmt(seq.seconds)}`)));
      const chips = h("div", { class: "cs-chips" });
      for (const sh of shots) {
        const v = st?.shots.find((x) => x.id === sh.id)?.variants ?? [];
        const state = sh.off ? "off" : v.some((x) => x.state === "error") ? "error" : v.length && v.every((x) => x.state === "ready") ? "ready" : v.some((x) => x.state === "changed") ? "changed" : "new";
        const ms = v.find((x) => x.ms)?.ms, painted = v.some((x) => (x as { painted?: boolean }).painted), frames = Math.max(1, Math.round(sh.dur * (code.info?.config.fps ?? 30)));
        const chip = h("button", { class: `cs-chip ${state} ${sh.id === this.shot ? "on" : ""} ${painted ? "painted" : ""}`, type: "button", style: `flex:${Math.max(0.6, sh.dur)} 1 0`, title: `${sh.id} · ${sh.dur.toFixed(2)}s · ${state === "ready" ? "drawn" : state === "changed" ? "changed — will be drawn again" : state === "new" ? "not drawn yet" : state}${ms ? ` · drawn in ${(ms / 1000).toFixed(1)} s (${Math.round(ms / frames)} ms a frame)` : ""}${painted ? " · has painted fixes" : ""}${sh.note ? `\n${sh.note}` : ""}\nRight-click: switch off / on` }, sh.id.slice(seq.id.length + 1));
        chip.addEventListener("click", () => this.selectShot(sh.id));
        chip.addEventListener("contextmenu", (e) => {
          e.preventDefault();
          void code.shots(sh.off ? [] : [sh.id], sh.off ? [sh.id] : []).then(() => toast(sh.off ? `${sh.id} is back in the film` : `${sh.id} is left out of the film (nothing deleted)`, "info", 2600));
        });
        chips.append(chip);
      }
      row.append(chips);
      groups.push(row);
    }
    this.stripEl.replaceChildren(...groups);
  }
  private drawProblems(): void {
    const out: HTMLElement[] = [];
    if (code.error) out.push(h("div", { class: "cs-prob bad" }, h("b", {}, "The code does not load"), h("pre", {}, code.error)));
    const n = code.status?.needs;
    if (n) {
      const bits = [n.voices.length ? `${n.voices.length} line${n.voices.length === 1 ? "" : "s"} to speak` : "", n.motion.length ? `performance to solve: ${n.motion.join(", ")}` : "", n.sounds.length ? `sounds to make: ${n.sounds.join(", ")}` : ""].filter(Boolean);
      if (bits.length) out.push(h("div", { class: "cs-prob note" }, h("b", {}, "Made at the next draw"), h("span", {}, bits.join(" · "))));
      for (const p of n.problems) out.push(h("div", { class: "cs-prob warn" }, p));
    }
    for (const p of code.problems.slice(0, 8)) out.push(h("div", { class: "cs-prob warn" }, p));
    const lb = code.lastBuild;
    if (lb) out.push(h("div", { class: "cs-prob ok" }, h("b", {}, lb.rendered.length ? `Drew ${lb.rendered.length} shot${lb.rendered.length === 1 ? "" : "s"}, kept ${lb.reused}` : `Nothing to draw — ${lb.reused} shots kept`), h("span", {}, ` in ${(lb.ms / 1000).toFixed(1)} s${lb.draft ? " (draft)" : ""}${lb.films.length ? ` · ${lb.films.map((f) => f.file.split("/").pop()).join(", ")}` : ""}`)));
    this.probEl.replaceChildren(...out);
  }

  /* ------------------------------------------------------------ files and the editor */

  private markDirty(): void {
    const d = this.view.state.doc.toString() !== this.saved;
    this.dirtyEl.textContent = d ? "● not saved" : "";
  }
  private async openFile(file: string | null): Promise<void> {
    if (!file || !code.dir) {
      this.setDoc("", null);
      return;
    }
    if (this.file && this.view.state.doc.toString() !== this.saved) await this.save();
    try {
      this.setDoc(await film().read(code.dir, file), file);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error", 4000);
    }
    this.drawSide();
  }
  private setDoc(text: string, file: string | null): void {
    this.loading = true;
    this.file = file;
    this.saved = text;
    this.view.dispatch({ changes: { from: 0, to: this.view.state.doc.length, insert: text }, effects: this.lang.reconfigure(file?.endsWith(".json") ? json() : javascript()) });
    this.loading = false;
    this.fileEl.textContent = file ?? "No file";
    this.markDirty();
    this.drawOutline();
  }
  /** What the assistant knows about this film, as the editor asks for it. */
  private assist(): AssistContext {
    return {
      index: () => this.kit,
      file: () => this.file,
      lines: () => this.scriptLines,
      actors: () => [...new Set((code.info?.shots ?? []).flatMap((x) => (x as FilmShot & { actors?: string[] }).actors ?? []))],
      cues: () => this.cueNames,
      error: () => code.error,
      findings: () => sensei.report?.issues ?? [],
      seq: () => (this.file && /^seq\/([\w-]+)\.js$/.test(this.file) ? this.file.slice(4, -3) : null),
      act: (kind, i) => {
        if (kind === "paint" && i.shot) document.dispatchEvent(new CustomEvent("vsd:openScene", { detail: `fix:${i.shot}#${i.id}` }));
        else document.dispatchEvent(new CustomEvent("vsd:go", { detail: "sensei" }));
      },
    };
  }
  /** The film's own words for completion: its lines (script.json) and music cues (cues.json). */
  private async loadFilmWords(): Promise<void> {
    if (!code.dir) return;
    try {
      const sc = JSON.parse(await film().read(code.dir, "script.json")) as { lines?: { id: string; who?: string }[] };
      this.scriptLines = Object.fromEntries((sc.lines ?? []).map((l) => [l.id, l.who ?? ""]));
    } catch {
      this.scriptLines = {};
    }
    try {
      this.cueNames = (JSON.parse(await film().read(code.dir, "cues.json")) as { name: string }[]).map((c) => c.name).filter(Boolean);
    } catch {
      this.cueNames = [];
    }
  }
  private drawOutline(): void {
    const items = outline(this.view.state.doc.toString());
    this.outlineSel.replaceChildren(h("option", { value: "" }, items.length ? `Go to… (${items.filter((x) => x.kind === "shot").length} shots)` : "Go to…"), ...items.map((x) => h("option", { value: String(x.line) }, x.label)));
    this.outlineSel.disabled = !items.length;
  }
  private goLine(line: number): void {
    const doc = this.view.state.doc, ln = doc.line(Math.max(1, Math.min(doc.lines, line)));
    this.view.dispatch({ selection: { anchor: ln.from }, effects: EditorView.scrollIntoView(ln.from, { y: "center" }) });
    this.view.focus();
  }
  /** Earlier versions of the open file: a list, a comparison with now, and bringing one back. */
  private async history(): Promise<void> {
    const f = film();
    if (!code.dir || !this.file || !f.history || !f.historyRead) return;
    await this.save();
    const dir = code.dir, file = this.file, list = await f.history(dir, file);
    if (!list.length) return void toast("No earlier versions of this file yet: one is kept every time it is saved.", "info", 3200);
    const now = this.view.state.doc.toString(), pane = h("pre", { class: "cs-diff mono" }), items = h("div", { class: "cs-hlist" });
    let pick: { at: number; text: string } | null = null;
    const show = async (at: number) => {
      const text = await f.historyRead!(dir, file, at);
      pick = { at, text };
      const d = diffLines(text, now), lines = d.filter((x) => x.op !== " ").length;
      pane.replaceChildren(h("div", { class: "cs-dhead" }, `${new Date(at).toLocaleString()} → now · ${lines} line${lines === 1 ? "" : "s"} differ`), ...d.map((x) => h("div", { class: `dl ${x.op === "+" ? "add" : x.op === "-" ? "del" : ""}` }, `${x.op} ${x.text}`)));
    };
    items.append(...list.slice(0, 60).map((v) => {
      const el = h("button", { class: "cs-hrow", type: "button" }, new Date(v.at).toLocaleString(), h("small", {}, ` ${(v.bytes / 1024).toFixed(1)} KB`));
      el.addEventListener("click", () => { items.querySelectorAll(".on").forEach((x) => x.classList.remove("on")); el.classList.add("on"); void show(v.at); });
      return el;
    }));
    const { modal } = await import("./kit");
    const m = modal(`History · ${file}`, h("div", { class: "cs-history" }, items, pane), [
      b("Bring this version back", "undo", () => { if (!pick) return; this.view.dispatch({ changes: { from: 0, to: this.view.state.doc.length, insert: pick.text } }); void this.save(); m.close(); toast("The earlier version is back (the one you had is in the history too)."); }, "pri"),
    ]);
    (items.firstElementChild as HTMLElement | null)?.click();
  }
  /** Run a line in the film's console. */
  private async runConsole(): Promise<void> {
    const f = film(), expr = this.consoleIn.value.trim();
    if (!expr || !code.dir || !f.eval) return;
    this.consoleHist.push(expr);
    this.consoleAt = -1;
    this.consoleIn.value = "";
    const row = h("div", { class: "in" }, `› ${expr}`);
    this.consoleOut.append(row);
    try {
      const r = await f.eval(code.dir, expr);
      this.consoleOut.append(h("div", { class: "out" }, r.value), h("div", { class: "dim" }, `${r.type} · ${r.ms} ms`));
    } catch (e) {
      this.consoleOut.append(h("div", { class: "err" }, (e instanceof Error ? e.message : String(e)).replace(/^Error invoking remote method '[^']+': (Error: )?/, "")));
    }
    this.consoleOut.scrollTop = this.consoleOut.scrollHeight;
  }
  /** A file changed on disk (Claude wrote it): show the new text unless the person is typing in it. */
  private async syncFile(): Promise<void> {
    if (!this.file || !code.dir || this.view.state.doc.toString() !== this.saved) return;
    if (!(code.info?.files ?? []).some((f) => f.file === this.file)) return;
    try {
      const text = await film().read(code.dir, this.file);
      if (text !== this.saved && this.view.state.doc.toString() === this.saved) {
        const pos = this.view.state.selection.main.head;
        this.setDoc(text, this.file);
        this.view.dispatch({ selection: { anchor: Math.min(pos, text.length) } });
      }
    } catch {
      /* the file is gone: the list shows it */
    }
  }
  private async save(): Promise<void> {
    window.clearTimeout(this.saveTimer);
    if (!this.file || !code.dir) return;
    const text = this.view.state.doc.toString();
    if (text === this.saved) return;
    this.saved = text;
    this.markDirty();
    try {
      await code.write(this.file, text);
      if (this.shot && !code.error) this.askFrame(0);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error", 4000);
    }
  }
  private insert(text: string): void {
    const sel = this.view.state.selection.main;
    this.view.dispatch({ changes: { from: sel.from, to: sel.to, insert: text }, selection: { anchor: sel.from + text.length } });
    this.view.focus();
  }
  private async newFile(): Promise<void> {
    if (!code.dir) return;
    const name = prompt("Name of the new file (for a sequence: seq/s02.js)", "seq/s02.js");
    if (!name) return;
    const id = name.replace(/^seq\//, "").replace(/\.js$/, "");
    const text = name.startsWith("seq/") ? `// ${id}\nimport { makeSeq, at, move, breathe, person, meadow } from '../vs.js';\n\nexport const ${id.toUpperCase().replace(/\W/g, "_")} = makeSeq({\n  id: '${id}',\n  script: [['wait', 4]],\n  set: () => meadow({ time: 'day' }),\n  motion: (T) => ({\n    actors: [{ id: 'a', preset: 'villager', kit: 'none', at: [0, 0], yaw: 0 }],\n    steps: [{ op: 'stage.do', actor: 'a', actions: [{ t: 0, idle: true }] }],\n  }),\n  actors: () => [{ id: 'a', design: person('townsman_01') }],\n  shots: (T) => [\n    { id: 'a', t0: 0, t1: T.dur, cam: (t, ctx) => ({ pos: [0.6, 1.5, 4.2], target: at(ctx, 'a', 'head'), fov: 35 }) },\n  ],\n});\n` : "";
    try {
      await code.write(name, text);
      await this.openFile(name);
      if (name.startsWith("seq/")) toast("Add the new sequence to FILM in film.js to put it into the film.", "info", 5000);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error", 4000);
    }
  }
  private async guide(): Promise<void> {
    try {
      const text = await film().reference();
      this.loading = true;
      this.file = null;
      this.saved = text;
      this.view.dispatch({ changes: { from: 0, to: this.view.state.doc.length, insert: text } });
      this.loading = false;
      this.fileEl.textContent = "Guide (read only)";
      this.dirtyEl.textContent = "";
      this.drawSide();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error", 4000);
    }
  }

  /* ------------------------------------------------------------ the viewer */

  private curShot(): FilmShot | null {
    return code.info?.shots.find((s) => s.id === this.shot) ?? null;
  }
  /** Show a shot at a moment (Sensei's findings open here). */
  showShot(id: string, t?: number): void {
    this.selectShot(id);
    const sh = this.curShot();
    if (sh && t != null) { this.shotT = Math.max(0, Math.min(sh.dur, t)); this.scrub.value = String(sh.dur ? this.shotT / sh.dur : 0); this.tEl.textContent = `${this.shotT.toFixed(2)}s`; this.askFrame(0); }
  }
  private selectShot(id: string): void {
    this.shot = id;
    const sh = this.curShot();
    this.shotT = sh ? sh.dur / 2 : 0;
    this.scrub.value = "0.5";
    this.shotEl.textContent = id;
    this.tEl.textContent = `${this.shotT.toFixed(2)}s`;
    this.drawStrip();
    this.askFrame(0);
  }
  /** Ask for the frame under the scrubber; while one is being drawn, only the newest wish is kept. */
  private askFrame(delay: number): void {
    window.clearTimeout(this.frameTimer);
    this.frameTimer = window.setTimeout(() => {
      if (this.frameBusy) {
        this.frameWant = true;
        return;
      }
      const id = this.shot;
      if (!id || !code.dir) return;
      this.frameBusy = true;
      this.stageEl.classList.add("wait");
      void code
        .frame(id, this.shotT, undefined, 0.5)
        .catch((e) => {
          code.error ??= e instanceof Error ? e.message : String(e);
          this.drawProblems();
        })
        .finally(() => {
          this.frameBusy = false;
          this.stageEl.classList.remove("wait");
          if (this.frameWant) {
            this.frameWant = false;
            this.askFrame(0);
          }
        });
    }, delay);
  }
  private showPicture(): void {
    const p = code.shown;
    this.vidEl.pause();
    this.stageEl.classList.remove("video");
    if (!p) {
      this.imgEl.removeAttribute("src");
      return;
    }
    this.imgEl.src = p.jpeg;
    if (p.shot !== this.shot) {
      this.shot = p.shot;
      this.shotEl.textContent = p.shot;
      const sh = this.curShot();
      if (sh && !p.sheet) {
        this.shotT = p.t;
        this.scrub.value = String(Math.min(1, p.t / Math.max(0.001, sh.dur)));
        this.tEl.textContent = `${p.t.toFixed(2)}s`;
      }
      this.drawStrip();
    }
  }
  private async playPath(path: string | null, why: string): Promise<void> {
    if (!path || !vsd) {
      toast(why, "info", 3200);
      return;
    }
    this.vidEl.src = await vsd.fileUrl(path);
    this.stageEl.classList.add("video");
    void this.vidEl.play().catch(() => {});
  }
  private async playShot(): Promise<void> {
    const v = code.status?.shots.find((s) => s.id === this.shot)?.variants?.find((x) => x.file);
    await this.playPath(v?.file && code.dir ? `${code.dir}/.vs/shots/${v.file}` : null, "This shot is not drawn yet — Draw changed first.");
  }
  private async playFilm(): Promise<void> {
    let f = code.filmFile();
    if (!f && code.info && code.dir) {
      const name = code.info.config.name.replace(/[^\w\- ]+/g, "").trim().replace(/\s+/g, "_") || "film";
      const guess = `${code.dir}/.vs/out/${name}.${code.info.config.langs[0]}.mp4`;
      f = (await vsd?.stat(guess).catch(() => null)) ? guess : null;
    }
    await this.playPath(f, "The film is not joined yet — Draw changed first.");
  }
  private async sheet(): Promise<void> {
    if (!this.shot) return;
    try {
      await code.sheet(this.shot, { count: 6, cols: 3, cell: 0.4 });
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error", 4000);
    }
  }

  /* ------------------------------------------------------------ actions */

  private async build(draft: boolean): Promise<void> {
    if (!code.dir) {
      void this.pickFilm();
      return;
    }
    await this.save();
    try {
      const r = await code.build({ draft });
      toast(r.rendered.length ? `Drew ${r.rendered.length} shot${r.rendered.length === 1 ? "" : "s"}, kept ${r.reused} — ${(r.ms / 1000).toFixed(1)} s` : "Everything was already up to date.", "info", 4200);
      this.drawProblems();
    } catch (e) {
      const m = e instanceof Error ? e.message : String(e);
      if (m !== "Stopped.") {
        code.problems = [m, ...code.problems].slice(0, 8);
        this.drawProblems();
        toast(m.split("\n")[0]!, "error", 6000);
      }
    }
  }
  private async exportFilm(): Promise<void> {
    if (!code.dir || !vsd) return;
    try {
      const { runQuiet } = await import("../../../src/agent/api");
      const r = await runQuiet(this.s, [{ op: "code.export" }]);
      if (r[0]?.ok) {
        const f = (r[0].result as { file: string }).file;
        toast(`Saved ${f.split("/").pop()}`, "info", 5000);
        void vsd.reveal(f);
      } else toast(r[0]?.error ?? "Could not export.", "error", 5000);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error", 5000);
    }
  }
  private async pickFilm(): Promise<void> {
    const f = film();
    const [films, info] = await Promise.all([f.list(), f.info()]);
    const close = () => ov.remove();
    const list = h("div", { class: "cs-pick-list" });
    for (const x of films) {
      const row = h("button", { class: `cs-row ${x.dir === code.dir ? "on" : ""}`, type: "button" }, h("span", { class: "nm" }, x.name), h("span", { class: "sz" }, `${x.width}×${x.height} · ${x.fps} fps`));
      row.addEventListener("click", () => {
        close();
        void code.open(x.dir).catch((e) => toast(e instanceof Error ? e.message : String(e), "error", 5000));
      });
      list.append(row);
    }
    if (!films.length) list.append(h("div", { class: "cs-note" }, "No films yet. Start one below."));
    const name = h("input", { class: "cs-in", placeholder: "Name of a new film", type: "text" }) as HTMLInputElement;
    const tpl = h("select", { class: "cs-sel" }) as HTMLSelectElement;
    tpl.append(...info.templates.map((x) => h("option", { value: x.id, title: x.about }, x.name)));
    const make = b("Start", "plus", () => {
      if (!name.value.trim()) {
        name.focus();
        return;
      }
      void f
        .create(name.value, tpl.value)
        .then((dir) => {
          close();
          return code.open(dir);
        })
        .catch((e) => toast(e instanceof Error ? e.message : String(e), "error", 5000));
    }, "pri");
    const box = h("div", { class: "cs-pick" }, h("h3", {}, "Code films"), list, h("div", { class: "cs-pick-new" }, name, tpl, make), h("div", { class: "cs-note" }, `Films are kept in ${info.root}`));
    const ov = h("div", { class: "cs-overlay" }, box);
    ov.addEventListener("mousedown", (e) => e.target === ov && close());
    this.el.append(ov);
    name.focus();
  }
}

/** Ready pieces of film code (Insert…). */
const SNIPPETS: [string, string][] = [
  ["A shot", `{ id: 'x', t0: 0, t1: 3, note: 'what the shot shows',
  cam: (t, ctx) => breathe({ pos: [0, 1.5, 4], target: at(ctx, 'ACTOR', 'head'), fov: 30 }, t, 0.004) },`],
  ["A camera move", `cam: (t) => move({ pos: [-3, 1.6, 5], target: [0, 1.4, 0], fov: 38 }, { pos: [2, 1.5, 3], target: [0, 1.5, 0], fov: 30 }, t, T0, T1),`],
  ["Over the shoulder", `cam: (t, ctx) => { const a = at(ctx, 'A', 'head'), b = at(ctx, 'B', 'head'); return breathe({ pos: V.add(b, V.add(V.mul(V.norm(V.sub(b, a)), 0.9), [0.35, 0.05, 0])), target: a, fov: 28 }, t, 0.004); },`],
  ["A walk (Stage)", `{ op: 'stage.do', actor: 'ACTOR', actions: [{ t: 0, idle: true }, { t: 1, go: [x, z], gait: 'walk', speed: 1.3, face: 90 }] },`],
  ["Look at someone", `{ op: 'stage.overlay', actor: 'ACTOR', add: [{ t0: 0, t1: 6, fade: 0.4, look: 'OTHER', head: 0.8 }] },`],
  ["Faces: emotions", `emote: [[0, 'calm'], [2, 'happy', 0.6], [T.t('LINE'), 'determined']],`],
  ["A line in the script", `['say', 'LINE', 0.3],`],
  ["Slow motion moment", `warp: ramps(3, [{ at: 1.2, slow: 0.2, len: 0.6 }]),`],
  ["On twos", `hold: 2,`],
  ["Title card", `top: (g, cam, sc, t, ctx, u) => mainTitle(g, cam.W, cam.H, u, { word: 'TITLE', sub: 'A FILM BY VIKAS' }),`],
  ["Place caption", `top: (g, cam, sc, t, ctx, u) => caption(g, cam.W, cam.H, u, 'The village', { sub: 'dusk' }),`],
  ["Sound: ambience + music + a hit", `sound: (T) => [{ t0: 0, t1: T.dur, amb: 'wind', gain: 0.35 }, { t0: 0.5, t1: T.dur, music: 'theme', gain: 0.55, fadeIn: 1.5, fadeOut: 2 }, { t: 2.1, sfx: 'impact', gain: 0.7 }],`],
  ["2D library: multiplane set", `set: () => parallax('bg_village_dusk'),`],
  ["2D library: cut-out actor", `dyn: (t) => [cutoutActor('char_mira', { ...standOn('bg_village_dusk', 0.45, 0.9, 0.6), t, pose: (u) => walkPose(u), who: 'Mira', cues: T.cues })],`],
  ["2D library: effect pop", `top: layered(pop('fx_impact_comic_white', 2.0, { x: 0.6, y: 0.4, h: 0.4, dur: 0.6 })),`],
  ["Rain / snow / petals", `fx: (ctx) => [rain({ x: [-8, 8], z: [-6, 10], y: [0, 7] })],`],
  ["A new sequence file", `import { makeSeq, at, move, breathe, V, park, person, presetOf } from '../vs.js';

export const S0X = makeSeq({
  id: 's0x',
  script: [['wait', 2], ['mark', 'end']],
  set: () => park({ time: 'day' }),
  motion: (T) => ({ actors: [{ id: 'a', preset: presetOf('townsman_07'), kit: 'none', at: [0, 0], yaw: 0 }], steps: [] }),
  actors: (T) => [{ id: 'a', design: person('townsman_07') }],
  shots: (T) => [{ id: 'a', t0: 0, t1: T.m('end'), cam: (t, ctx) => breathe({ pos: [0, 1.5, 4], target: at(ctx, 'a', 'head'), fov: 32 }, t, 0.004) }],
  sound: (T) => [{ t0: 0, t1: T.dur, amb: 'birds', gain: 0.35 }],
});
`],
];
