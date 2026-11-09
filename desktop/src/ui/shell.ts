/** The desktop shell: title bar with workspaces, the workspaces themselves, status bar, menus and shortcuts. */
import type { Studio } from "../../../src/app/studio";
import { AnimateView } from "../../../src/ui/animate";
import { VoiceView } from "../../../src/ui/voice";
import { runCommand } from "../../../src/agent/api";
import { deleteProject } from "../../../src/core/storage";
import { toast } from "../../../src/ui/dom";
import { setShellHooks } from "../commands";
import type { DesktopLink } from "../link";
import { fmtSize, vsd } from "../bridge";
import { importPath } from "../importer";
import { AudioWorkspace } from "./audio";
import { ClaudeWorkspace } from "./claude";
import { ASPECTS, Inspector } from "./inspector";
import { b, h, ic, menu, modal, panel, selectEl, type MenuItem } from "./kit";
import { Library } from "./library";
import { DesktopTimeline } from "./timeline";
import { Deliver } from "./deliver";
import { GraphEditor } from "./graph";
import { Viewer } from "./viewer";

export type WsName = "edit" | "color" | "audio" | "animate" | "voice" | "deliver" | "claude";
const WS: [WsName, string, string][] = [
  ["edit", "Edit", "film"],
  ["color", "Color", "color"],
  ["audio", "Audio", "wave"],
  ["animate", "Animate", "brush"],
  ["voice", "Voice", "mic"],
  ["deliver", "Deliver", "export"],
  ["claude", "Claude", "spark"],
];

function inkTree(): SVGSVGElement {
  // A sumi-e style tree, drawn once from a seeded branching walk (the OREZ wallpaper motif).
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 760 640");
  let seed = 7;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  const paths: string[] = [];
  const branch = (x: number, y: number, a: number, len: number, w: number, depth: number) => {
    if (depth > 9 || len < 6) return;
    const x2 = x + Math.cos(a) * len;
    const y2 = y + Math.sin(a) * len;
    const cx = (x + x2) / 2 + (rnd() - 0.5) * len * 0.35;
    const cy = (y + y2) / 2 + (rnd() - 0.5) * len * 0.35;
    paths.push(`<path d="M${x.toFixed(1)} ${y.toFixed(1)} Q${cx.toFixed(1)} ${cy.toFixed(1)} ${x2.toFixed(1)} ${y2.toFixed(1)}" stroke-width="${w.toFixed(2)}"/>`);
    const n = depth < 2 ? 2 : rnd() < 0.7 ? 2 : 3;
    for (let i = 0; i < n; i++) branch(x2, y2, a + (rnd() - 0.5) * 1.15 - 0.08, len * (0.66 + rnd() * 0.16), w * 0.68, depth + 1);
    if (depth > 6 && rnd() < 0.5) paths.push(`<circle cx="${x2.toFixed(1)}" cy="${y2.toFixed(1)}" r="${(2 + rnd() * 4).toFixed(1)}" fill="#ff8fb8" stroke="none" opacity="0.7"/>`);
  };
  branch(560, 650, -1.62, 150, 22, 0);
  svg.innerHTML = `<g fill="none" stroke="#e8edf4" stroke-linecap="round">${paths.join("")}</g>`;
  return svg;
}

export class Shell {
  readonly el: HTMLElement;
  private ws: Record<WsName, HTMLElement> = {} as never;
  private cur: WsName = "edit";
  private tabsEl: HTMLElement;
  private linkBtn: HTMLElement;
  private statusMsg: HTMLElement;
  private statusRight: HTMLElement;
  private projEl: HTMLElement;
  readonly viewer: Viewer;
  readonly timeline: DesktopTimeline;
  private tlPanel: HTMLElement;
  private tlBody!: HTMLElement;
  private graph: GraphEditor;
  private graphBtn!: HTMLButtonElement;
  private graphOn = false;
  private library: Library;
  private animate: AnimateView;
  private deliver: Deliver;
  private rendersBody: HTMLElement | null = null;

  constructor(
    private readonly s: Studio,
    private readonly link: DesktopLink,
  ) {
    this.viewer = new Viewer(s);
    this.timeline = new DesktopTimeline(s);
    this.graph = new GraphEditor(s);
    const graphBtn = b("Graph", "curve", () => this.toggleGraph(), "sm", "Animation curves of the selected clip (G)");
    this.graphBtn = graphBtn;
    const tl = panel(h("span", { class: "row", style: "gap:6px" }, ic("layers", 14), "Timeline"), "tlp", graphBtn, this.timeline.toolbar);
    tl.body.style.padding = "0";
    tl.body.style.display = "flex";
    tl.body.append(this.timeline.el);
    this.tlBody = tl.body;
    this.tlPanel = tl.el;
    this.addSplitH(this.tlPanel);
    this.library = new Library(s);
    this.addSplitV(this.library.el, "--lib", 1);

    // Edit workspace.
    const insp = panel("Inspector", "insp");
    insp.body.append(new Inspector(s, "all").el);
    this.addSplitV(insp.el, "--insp", -1);
    this.ws.edit = h("div", { class: "d-ws ws-edit" }, this.library.el, insp.el);
    // Color workspace.
    const cinsp = panel("Color", "insp");
    cinsp.body.append(new Inspector(s, "color").el);
    this.ws.color = h("div", { class: "d-ws ws-color" }, cinsp.el);
    // Audio workspace.
    const aw = new AudioWorkspace(s);
    this.ws.audio = h("div", { class: "d-ws ws-audio" }, aw.lib, aw.wave, aw.mix);
    // Animate, Voice: the engine's own workstations, hosted full-size.
    this.animate = new AnimateView(s);
    const an = panel("Animate · drawing, rigs, 2D & 3D");
    an.body.className = "hosted";
    an.body.append(this.animate.el);
    this.ws.animate = h("div", { class: "d-ws ws-full" }, an.el);
    const vo = panel("Voice · VolTune");
    vo.body.className = "hosted";
    vo.body.append(new VoiceView(s).el);
    this.ws.voice = h("div", { class: "d-ws ws-full" }, vo.el);
    // Deliver.
    this.deliver = new Deliver(s);
    const ex = panel("Render · GPU");
    ex.body.replaceWith(this.deliver.el);
    const rn = panel("Renders on this PC", "", b(null, "loop", () => void this.drawRenders(), "sm ghost", "Refresh"), b(null, "folder", async () => vsd && void vsd.openPath((await vsd.info()).out.Movies), "sm ghost", "Open the folder"));
    this.rendersBody = rn.body;
    this.ws.deliver = h("div", { class: "d-ws", style: "grid-template-columns:1fr 420px" }, ex.el, rn.el);
    // Claude.
    const cw = new ClaudeWorkspace(link);
    this.ws.claude = h("div", { class: "d-ws ws-claude" }, cw.left, cw.right);

    // Title bar.
    this.tabsEl = h("div", { class: "d-tabs" });
    this.linkBtn = h("button", { class: "d-link", type: "button", title: "Claude link" });
    this.linkBtn.addEventListener("click", () => this.go("claude"));
    this.projEl = h("button", { class: "d-proj b ghost sm", type: "button", title: "Projects" });
    this.projEl.addEventListener("click", () => this.projects());
    const menus = h(
      "div",
      { class: "d-menus" },
      ...(
        [
          ["File", () => this.fileMenu()],
          ["Edit", () => this.editMenu()],
          ["View", () => this.viewMenu()],
          ["Help", () => this.helpMenu()],
        ] as [string, () => MenuItem[]][]
      ).map(([label, items]) => {
        const bt = h("button", { type: "button" }, label);
        bt.addEventListener("click", () => {
          const r = bt.getBoundingClientRect();
          menu(items(), r.left, r.bottom + 4);
        });
        return bt;
      }),
    );
    const maxBtn = h("button", { type: "button", title: "Maximise" }, ic("max", 13));
    const winctl = h(
      "div",
      { class: "d-winctl" },
      (() => {
        const x = h("button", { type: "button", title: "Minimise" }, ic("min", 14));
        x.addEventListener("click", () => void vsd?.win.minimize());
        return x;
      })(),
      maxBtn,
      (() => {
        const x = h("button", { type: "button", class: "x", title: "Close" }, ic("close", 14));
        x.addEventListener("click", () => void vsd?.win.close());
        return x;
      })(),
    );
    maxBtn.addEventListener("click", () => void vsd?.win.maximize());
    vsd?.win.onState((st) => maxBtn.replaceChildren(ic(st.maximized ? "restore" : "max", 13)));
    const title = h("header", { class: "d-title" }, h("div", { class: "d-brand" }, h("span", { class: "mark" }), "VideoStudio", h("small", {}, "Desktop")), menus, this.tabsEl, this.projEl, this.linkBtn, vsd ? winctl : null);
    title.addEventListener("dblclick", (e) => e.target === title && void vsd?.win.maximize());

    this.statusMsg = h("span", { class: "msg" });
    this.statusRight = h("span", { class: "mono" });
    const status = h("footer", { class: "d-status" }, this.statusMsg, this.statusRight);
    const body = h("div", { class: "d-body" }, ...Object.values(this.ws));
    const wall = h("div", { class: "d-wall" }, inkTree());
    const drop = h("div", { class: "drop" }, h("div", {}, "Drop to import into the project"));
    this.el = h("div", { style: "height:100%" }, wall, h("div", { class: "d-shell" }, title, body, status), drop);

    s.on("status", () => (this.statusMsg.textContent = s.status || "Ready"));
    s.on("project", () => this.drawProj());
    s.on("change", () => this.drawProj());
    s.on("time", () => this.drawStatus());
    link.on(() => this.drawLink());
    document.addEventListener("vsd:rendered", () => this.cur === "deliver" && void this.drawRenders());
    vsd?.onProgress((m) => this.s.setStatus(`${m.tag.split(":")[0]} · ${Math.round(m.frac * 100)}%`));
    void vsd?.workClean();
    document.addEventListener("vsd:openScene", (e) => this.go("animate", (e as CustomEvent<string>).detail));
    (window as unknown as { vsdFullscreen: () => void }).vsdFullscreen = () => void vsd?.win.fullscreen();
    this.keys();
    this.dnd(drop);
    setShellHooks({
      workspace: (n) => {
        if (!WS.some(([id]) => id === n)) throw new Error(`Workspaces: ${WS.map(([id]) => id).join(", ")}`);
        this.go(n as WsName);
      },
      current: () => this.cur,
      toast: (m) => toast(m, "info", 5000),
    });
    this.go("edit");
    this.drawProj();
    this.drawLink();
    this.statusMsg.textContent = "Ready";
  }

  /* ------------------------------------------------------------ layout */

  toggleGraph(on = !this.graphOn): void {
    this.graphOn = on;
    this.graphBtn.classList.toggle("on", on);
    this.tlBody.replaceChildren(on ? this.graph.el : this.timeline.el);
    if (on) this.graph.redraw();
    else this.timeline.redraw();
  }

  go(n: WsName, arg?: string): void {
    if (this.cur === "animate" && n !== "animate") this.animate.hide();
    this.cur = n;
    for (const [id, el] of Object.entries(this.ws)) el.classList.toggle("on", id === n);
    // Shared panels follow the workspace.
    const host = this.ws[n];
    if (n === "edit") {
      host.insertBefore(this.viewer.el, host.children[1] ?? null);
      host.append(this.tlPanel);
    } else if (n === "color") {
      host.insertBefore(this.viewer.el, host.firstChild);
      host.append(this.tlPanel);
    } else if (n === "audio") host.append(this.tlPanel);
    if (n === "animate") this.animate.open(arg);
    if (n === "deliver") {
      this.deliver.draw();
      void this.drawRenders();
    }
    if (n !== "edit" && n !== "color") this.s.pause();
    this.tabsEl.replaceChildren(
      ...WS.map(([id, label, i]) => {
        const bt = h("button", { class: id === n ? "on" : "", type: "button", title: `${label} workspace` }, ic(i, 14), label);
        bt.addEventListener("click", () => this.go(id));
        return bt;
      }),
    );
    this.timeline.redraw();
  }

  private addSplitH(panelEl: HTMLElement) {
    const hd = h("div", { class: "split-h", style: "top:-8px;left:0;right:0;position:absolute" });
    panelEl.style.position = "relative";
    panelEl.style.overflow = "visible";
    panelEl.append(hd);
    hd.addEventListener("mousedown", (e) => {
      const ws = panelEl.parentElement!;
      const y0 = e.clientY;
      const h0 = panelEl.getBoundingClientRect().height;
      const mv = (ev: MouseEvent) => ws.style.setProperty("--tl", `${Math.max(150, Math.min(innerHeight - 260, h0 - (ev.clientY - y0)))}px`);
      const up = () => {
        removeEventListener("mousemove", mv);
        removeEventListener("mouseup", up);
      };
      addEventListener("mousemove", mv);
      addEventListener("mouseup", up);
    });
  }

  private addSplitV(panelEl: HTMLElement, varName: string, dir: 1 | -1) {
    const hd = h("div", { class: "split-v", style: `top:0;bottom:0;position:absolute;${dir === 1 ? "right:-8px" : "left:-8px"}` });
    panelEl.style.position = "relative";
    panelEl.style.overflow = "visible";
    panelEl.append(hd);
    hd.addEventListener("mousedown", (e) => {
      const ws = panelEl.parentElement!;
      const x0 = e.clientX;
      const w0 = panelEl.getBoundingClientRect().width;
      const mv = (ev: MouseEvent) => ws.style.setProperty(varName, `${Math.max(220, Math.min(620, w0 + (ev.clientX - x0) * dir))}px`);
      const up = () => {
        removeEventListener("mousemove", mv);
        removeEventListener("mouseup", up);
      };
      addEventListener("mousemove", mv);
      addEventListener("mouseup", up);
    });
  }

  private drawProj() {
    const p = this.s.project;
    this.projEl.replaceChildren(ic("folderOpen", 14), h("b", {}, p.name), h("span", { class: "faint" }, `${p.width}×${p.height}`));
    this.drawStatus();
  }

  private lastStatus = 0;
  private drawStatus() {
    const now = performance.now();
    if (now - this.lastStatus < 250) return;
    this.lastStatus = now;
    const p = this.s.project;
    this.statusRight.textContent = `${p.width}×${p.height} · ${p.fps} fps · ${p.tracks.length} tracks · ${p.assets.length} media`;
  }

  private drawLink() {
    const L = this.link;
    const st = L.busy ? "busy" : L.state;
    this.linkBtn.replaceChildren(h("span", { class: `dot ${st}` }), L.busy ? "Claude is working…" : L.state === "online" ? "Claude linked" : L.state === "off" ? "Claude off" : L.state === "error" ? "Link error" : "Connecting…");
  }

  private async drawRenders() {
    if (!this.rendersBody || !vsd) return;
    const info = await vsd.info();
    let list;
    try {
      list = await vsd.list(info.out.Movies, { sort: "modified" });
    } catch {
      this.rendersBody.replaceChildren(h("div", { class: "empty" }, `Renders go to ${info.out.Movies}`));
      return;
    }
    const files = list.entries.filter((e) => e.kind === "video" || e.kind === "image" || e.kind === "audio");
    this.rendersBody.replaceChildren(
      h("div", { class: "hint mono", style: "margin-bottom:8px;font-size:11px" }, info.out.Movies),
      ...(files.length
        ? files.map((f) => {
            const row = h("div", { class: "fitem", title: f.path }, ic(f.kind === "video" ? "film" : f.kind === "audio" ? "wave" : "image", 15), h("span", { class: "nm" }, f.name), h("span", { class: "sz" }, `${fmtSize(f.size)} · ${new Date(f.modified).toLocaleString()}`));
            row.addEventListener("dblclick", () => void vsd!.openPath(f.path));
            row.addEventListener("contextmenu", (e) => {
              e.preventDefault();
              menu([{ label: "Play", run: () => void vsd!.openPath(f.path) }, { label: "Show in folder", run: () => void vsd!.reveal(f.path) }], e.clientX, e.clientY);
            });
            return row;
          })
        : [h("div", { class: "empty" }, "No renders yet. Your finished videos land here.")]),
    );
  }

  /* ------------------------------------------------------------ menus */

  private fileMenu(): MenuItem[] {
    return [
      { label: "New project…", key: "Ctrl+N", run: () => this.newProject() },
      { label: "Open project…", key: "Ctrl+O", run: () => this.projects() },
      { sep: true, label: "" },
      { label: "Import media…", key: "Ctrl+I", run: () => void this.library.importDialog() },
      { label: "Save backup (.vsproj)", key: "Ctrl+S", run: async () => {
        const r = await runCommand(this.s, { op: "project.backup" });
        toast(r.ok ? `Saved ${(r.result as { path: string }).path}` : r.error ?? "Failed", r.ok ? "ok" : "error", 4000);
      } },
      { label: "Open backup…", run: async () => {
        if (!vsd) return;
        const [p] = await vsd.pick({ title: "Open a .vsproj backup", multiple: false, dir: (await vsd.info()).out.Documents });
        if (!p) return;
        try {
          await this.s.importJson(await vsd.readText(p));
          toast("Project opened", "ok");
        } catch (e) {
          toast((e as Error).message, "error");
        }
      } },
      { sep: true, label: "" },
      { label: "Render…", key: "Ctrl+M", run: () => this.go("deliver") },
      { label: "Save frame as picture", run: async () => {
        const r = await runCommand(this.s, { op: "render.still", t: this.s.time });
        toast(r.ok ? "Frame saved to Pictures/VideoStudio" : r.error ?? "Failed", r.ok ? "ok" : "error");
      } },
      { sep: true, label: "" },
      { label: "Quit", key: "Ctrl+Q", run: () => void vsd?.win.close() },
    ];
  }

  private editMenu(): MenuItem[] {
    const sel = !!this.s.selection;
    return [
      { label: "Undo", key: "Ctrl+Z", run: () => this.s.undo() },
      { label: "Redo", key: "Ctrl+Shift+Z", run: () => this.s.redo() },
      { sep: true, label: "" },
      { label: "Split at playhead", key: "S", run: () => this.timeline.splitAtPlayhead() },
      { label: "Duplicate", key: "Ctrl+D", disabled: !sel, run: () => this.timeline.duplicateSelected() },
      { label: "Delete", key: "Del", disabled: !sel, run: () => this.timeline.deleteSelected(false) },
      { label: "Ripple delete", key: "Shift+Del", disabled: !sel, run: () => this.timeline.deleteSelected(true) },
      { sep: true, label: "" },
      { label: "Add marker", key: "M", run: () => this.timeline.addMarker() },
      { label: "Blade tool", key: "B", run: () => this.timeline.setTool("blade") },
      { label: "Select tool", key: "V", run: () => this.timeline.setTool("select") },
    ];
  }

  private viewMenu(): MenuItem[] {
    return [
      ...WS.map(([id, label], i) => ({ label: `${label} workspace`, key: `Alt+${i + 1}`, run: () => this.go(id) })),
      { sep: true, label: "" },
      { label: "Fit timeline", key: "Shift+Z", run: () => this.timeline.fit() },
      { label: "Full screen", key: "F11", run: () => void vsd?.win.fullscreen() },
    ];
  }

  private helpMenu(): MenuItem[] {
    return [
      { label: "Keyboard shortcuts", key: "?", run: () => this.shortcuts() },
      { label: "About VideoStudio Desktop", run: async () => {
        const i = await vsd?.info();
        modal(
          "VideoStudio Desktop",
          h("div", { class: "hint", style: "line-height:1.8" }, `Version ${i?.version ?? "dev"} · ${i?.platform ?? "web"} ${i?.arch ?? ""}`, h("br"), `${i?.cpuModel ?? ""} · ${i?.cpus ?? "?"} threads · ${i?.memoryGB ?? "?"} GB`, h("br"), `GPU: WebGL ${i?.gpu?.webgl ?? "?"} · WebGPU ${i?.gpu?.webgpu ?? "?"} · video decode ${i?.gpu?.video_decode ?? "?"}`, h("br"), `Renders: ${i?.out.Movies ?? ""}`),
          [b("Close", null, () => document.querySelector(".modal-bg")?.remove(), "pri")],
        );
      } },
    ];
  }

  private shortcuts() {
    const rows: [string, string][] = [
      ["Space", "Play / pause"],
      ["J / K / L", "Back 1 s · pause · forward 1 s"],
      ["← / →", "Previous / next frame (Shift: 1 s)"],
      ["Home / End", "Start / end"],
      ["S", "Split at playhead"],
      ["B / V", "Blade / select tool"],
      ["N", "Snapping on/off"],
      ["M", "Add marker"],
      ["Del / Shift+Del", "Delete / ripple delete"],
      ["Ctrl+D", "Duplicate clip"],
      [", / .", "Nudge selected clip one frame"],
      ["Ctrl+Z / Ctrl+Shift+Z", "Undo / redo"],
      ["Ctrl + wheel", "Zoom the timeline"],
      ["Shift+Z", "Fit the edit"],
      ["G", "Graph editor (animation curves)"],
      ["Alt+1…7", "Workspaces"],
      ["Ctrl+I", "Import media"],
      ["Ctrl+S", "Save backup"],
      ["F11", "Full screen"],
    ];
    const g = h("div", { class: "kgrid" });
    for (const [k, d] of rows) g.append(h("kbd", {}, k), h("span", {}, d));
    const m = modal("Keyboard shortcuts", g, [b("Close", null, () => m.close(), "pri")]);
  }

  private newProject() {
    const name = h("input", { class: "inp", value: "Untitled", style: "width:100%" }) as HTMLInputElement;
    name.addEventListener("keydown", (e) => e.stopPropagation());
    let aspect = ASPECTS[0]![0];
    let fps = "30";
    const m = modal(
      "New project",
      h(
        "div",
        { class: "col" },
        h("label", { class: "col", style: "gap:4px" }, h("span", { class: "muted" }, "Name"), name),
        h("label", { class: "col", style: "gap:4px" }, h("span", { class: "muted" }, "Frame"), selectEl<string>(ASPECTS.map(([l]) => l), aspect, (v) => (aspect = v))),
        h("label", { class: "col", style: "gap:4px" }, h("span", { class: "muted" }, "Frame rate"), selectEl<string>(["24", "25", "30", "50", "60"], fps, (v) => (fps = v))),
        h("p", { class: "hint" }, "You can change the frame any time — even animate between widescreen, scope, square and vertical inside one edit."),
      ),
      [
        b("Cancel", null, () => m.close()),
        b("Create", null, async () => {
          const a = ASPECTS.find(([l]) => l === aspect)!;
          m.close();
          await this.s.newProject(name.value || "Untitled", a[1], a[2], Number(fps));
          this.go("edit");
        }, "pri"),
      ],
    );
  }

  private async projects() {
    const list = await this.s.list();
    const box = h("div", { class: "flist", style: "max-height:56vh;overflow:auto" });
    const m = modal("Projects", h("div", { class: "col" }, b("New project", "plus", () => {
      m.close();
      this.newProject();
    }, "pri sm"), box));
    box.append(
      ...list
        .sort((a, b2) => b2.updatedAt - a.updatedAt)
        .map((p) => {
          const row = h("div", { class: `fitem ${p.id === this.s.project.id ? "sel" : ""}` }, ic("film", 15), h("span", { class: "nm" }, p.name), h("span", { class: "sz" }, `${p.width}×${p.height} · ${new Date(p.updatedAt).toLocaleDateString()}`));
          row.addEventListener("click", async () => {
            m.close();
            await this.s.open(p.id);
          });
          row.addEventListener("contextmenu", (e) => {
            e.preventDefault();
            menu(
              [
                { label: "Open", run: async () => {
                  m.close();
                  await this.s.open(p.id);
                } },
                { label: "Delete project", disabled: p.id === this.s.project.id, run: async () => {
                  if (!confirm(`Delete “${p.name}”? This cannot be undone.`)) return;
                  await deleteProject(p.id);
                  row.remove();
                } },
              ],
              e.clientX,
              e.clientY,
            );
          });
          return row;
        }),
    );
  }

  /* ------------------------------------------------------------ input */

  private keys() {
    window.addEventListener("keydown", (e) => {
      const t = e.target as HTMLElement;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable)) return;
      const ctrl = e.ctrlKey || e.metaKey;
      const k = e.key;
      if (e.altKey && /^[1-7]$/.test(k)) {
        this.go(WS[Number(k) - 1]![0]);
        return e.preventDefault();
      }
      if (ctrl && k.toLowerCase() === "n") return e.preventDefault(), this.newProject();
      if (ctrl && k.toLowerCase() === "o") return e.preventDefault(), void this.projects();
      if (ctrl && k.toLowerCase() === "i") return e.preventDefault(), void this.library.importDialog();
      if (ctrl && k.toLowerCase() === "m") return e.preventDefault(), this.go("deliver");
      if (ctrl && k.toLowerCase() === "s") return e.preventDefault(), void runCommand(this.s, { op: "project.backup" }).then((r) => toast(r.ok ? "Backup saved" : r.error ?? "Failed", r.ok ? "ok" : "error"));
      if (ctrl && k.toLowerCase() === "q") return void vsd?.win.close();
      if (k === "F11") return e.preventDefault(), void vsd?.win.fullscreen();
      // Editing keys apply in the timeline workspaces only (Animate has its own).
      if (!["edit", "color", "audio"].includes(this.cur)) return;
      const fr = 1 / this.s.project.fps;
      const tl = this.timeline;
      if (ctrl && k.toLowerCase() === "z") return e.preventDefault(), e.shiftKey ? this.s.redo() : this.s.undo();
      if (ctrl && k.toLowerCase() === "y") return e.preventDefault(), this.s.redo();
      if (ctrl && k.toLowerCase() === "d") return e.preventDefault(), tl.duplicateSelected();
      if (ctrl) return;
      switch (k) {
        case " ":
          e.preventDefault();
          this.s.toggle();
          break;
        case "k":
        case "K":
          this.s.pause();
          break;
        case "j":
        case "J":
          this.s.seek(Math.max(0, this.s.time - 1));
          break;
        case "l":
        case "L":
          this.s.seek(this.s.time + 1);
          break;
        case "ArrowLeft":
          e.preventDefault();
          this.s.seek(Math.max(0, this.s.time - (e.shiftKey ? 1 : fr)));
          break;
        case "ArrowRight":
          e.preventDefault();
          this.s.seek(this.s.time + (e.shiftKey ? 1 : fr));
          break;
        case "Home":
          this.s.seek(0);
          break;
        case "End":
          this.s.seek(this.s.duration);
          break;
        case "s":
        case "S":
          tl.splitAtPlayhead();
          break;
        case "b":
        case "B":
          tl.setTool("blade");
          break;
        case "v":
        case "V":
          tl.setTool("select");
          break;
        case "n":
        case "N":
          tl.snap = !tl.snap;
          toast(`Snapping ${tl.snap ? "on" : "off"}`);
          break;
        case "m":
        case "M":
          tl.addMarker();
          break;
        case "Delete":
        case "Backspace":
          tl.deleteSelected(e.shiftKey);
          break;
        case ",":
          tl.nudge(-1);
          break;
        case ".":
          tl.nudge(1);
          break;
        case "Z":
          if (e.shiftKey) tl.fit();
          break;
        case "?":
          this.shortcuts();
          break;
        case "g":
        case "G":
          this.toggleGraph();
          break;
        case "Escape":
          this.s.select(null);
          tl.setTool("select");
          break;
      }
    });
  }

  private dnd(overlay: HTMLElement) {
    let depth = 0;
    const isFiles = (e: DragEvent) => !!e.dataTransfer?.types.includes("Files");
    window.addEventListener("dragenter", (e) => {
      if (!isFiles(e)) return;
      depth++;
      overlay.classList.add("on");
    });
    window.addEventListener("dragleave", (e) => {
      if (!isFiles(e)) return;
      depth = Math.max(0, depth - 1);
      if (!depth) overlay.classList.remove("on");
    });
    window.addEventListener("dragover", (e) => isFiles(e) && e.preventDefault());
    window.addEventListener("drop", async (e) => {
      if (!isFiles(e)) return;
      e.preventDefault();
      depth = 0;
      overlay.classList.remove("on");
      const files = [...(e.dataTransfer?.files ?? [])];
      if (!files.length) return;
      this.s.setStatus(`Importing ${files.length} file${files.length > 1 ? "s" : ""}…`);
      try {
        for (const f of files) {
          const p = vsd?.pathOf(f);
          if (p) await importPath(this.s, p, false);
          else await this.s.importFiles([f], false);
        }
        toast(`Imported ${files.length} file${files.length > 1 ? "s" : ""}`, "ok");
      } catch (err) {
        toast((err as Error).message, "error");
      }
      this.s.setStatus("");
    });
  }
}
