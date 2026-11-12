/** The desktop shell: title bar with workspaces, the workspaces themselves, status bar, menus and shortcuts. */
import type { Studio } from "../../../src/app/studio";
import { AnimateView } from "../../../src/ui/animate";
import { PaintOver } from "./paintover";
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
import { b, h, ic, menu, modal, panel, selectEl, tabs, type MenuItem } from "./kit";
import { Library } from "./library";
import { DesktopTimeline } from "./timeline";
import { Deliver } from "./deliver";
import { GraphEditor } from "./graph";
import { Viewer } from "./viewer";
import { ColorPage } from "./colorpage";
import { DnaPanel, LensPanel } from "./insight";
import { Scopes } from "./scopes";
import { AgentStage } from "./stage";
import { MusicPage } from "./musicpage";
import { CodePage } from "./codepage";
import { SenseiPage } from "./senseipage";
import { registerCodeCommands } from "../film/session";
import { registerSenseiCommands } from "../sensei/commands";
import { StagePage } from "./stage3d";
import { setLiveHooks } from "../../../src/agent/api";

export type WsName = "edit" | "color" | "insight" | "audio" | "animate" | "stage" | "code" | "sensei" | "music" | "voice" | "deliver" | "claude";
const WS: [WsName, string, string][] = [
  ["edit", "Edit", "film"],
  ["color", "Color", "color"],
  ["insight", "Insight", "eye"],
  ["audio", "Audio", "wave"],
  ["animate", "Animate", "brush"],
  ["stage", "3D", "film"],
  ["code", "Code", "code"],
  ["sensei", "Sensei", "spark"],
  ["music", "Music", "wave"],
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
  private colorPage!: ColorPage;
  private tlPanel: HTMLElement;
  private tlBody!: HTMLElement;
  private graph: GraphEditor;
  private graphBtn!: HTMLButtonElement;
  private graphOn = false;
  private library: Library;
  private animate: AnimateView;
  private paintOver: PaintOver;
  private anFix = false;
  private anBody: HTMLElement | null = null;
  private anModeTabs: { el: HTMLElement; set: (v: "draw" | "fix") => void } | null = null;
  private music!: MusicPage;
  private codePage!: CodePage;
  private senseiPage!: SenseiPage;
  private stage3d!: StagePage;
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
    this.colorPage = new ColorPage(s, this.viewer.preview);
    cinsp.body.append(new Scopes(s, this.viewer.preview).el, this.colorPage.el, h("div", { class: "cp-basic-head" }, "Basic grade"), new Inspector(s, "color").el);
    this.ws.color = h("div", { class: "d-ws ws-color" }, cinsp.el);
    // Insight workspace: Audience Lens and Style DNA beside the viewer.
    const lens = new LensPanel(s, { reveal: (t) => void this.timeline.reveal(t, t, true) });
    const dna = new DnaPanel(s);
    lens.el.classList.add("lensp");
    dna.el.classList.add("dnap");
    this.ws.insight = h("div", { class: "d-ws ws-insight" }, lens.el, dna.el);
    // Audio workspace.
    const aw = new AudioWorkspace(s);
    this.ws.audio = h("div", { class: "d-ws ws-audio" }, aw.lib, aw.wave, aw.mix);
    // Animate, Voice: the engine's own workstations, hosted full-size.
    this.animate = new AnimateView(s);
    this.paintOver = new PaintOver();
    // two rooms: the drawing desk, and fixing a film shot by painting over it (the fix follows the body on every frame)
    const anMode = tabs<"draw" | "fix">([["draw", "Drawing"], ["fix", "Fix a film shot"]], "draw", (v) => this.animateMode(v));
    this.anModeTabs = anMode;
    const an = panel("Animate · drawing, rigs, 2D & 3D", "", anMode.el);
    an.body.className = "hosted";
    an.body.append(this.animate.el, this.paintOver.el);
    this.anBody = an.body;
    this.ws.animate = h("div", { class: "d-ws ws-full" }, an.el);
    this.stage3d = new StagePage(s);
    const sg = panel("3D Stage · characters, motion capture, fights, cameras");
    sg.body.className = "hosted";
    sg.body.append(this.stage3d.el);
    this.ws.stage = h("div", { class: "d-ws ws-full" }, sg.el);
    // Code Studio: films written as code, with instant frames and shot-by-shot redraws.
    this.codePage = new CodePage(s);
    const cd = panel("Code Studio · films written as code");
    cd.body.className = "hosted";
    cd.body.append(this.codePage.el);
    this.ws.code = h("div", { class: "d-ws ws-full" }, cd.el);
    registerCodeCommands(s, () => this.cur !== "code" && this.go("code"));
    // Sensei: the judge and helper, with its own workspace.
    this.senseiPage = new SenseiPage(s, (shot, t) => { this.go("code"); this.codePage.showShot(shot, t); });
    const sx = panel("Sensei · the judge — measures every frame, says what is wrong and how to fix it");
    sx.body.className = "hosted";
    sx.body.append(this.senseiPage.el);
    this.ws.sensei = h("div", { class: "d-ws ws-full" }, sx.el);
    registerSenseiCommands(s, () => this.cur !== "code" && this.cur !== "sensei" && this.go("sensei"));
    this.music = new MusicPage(s);
    const mu = panel("Music · compose, instruments, piano roll");
    mu.body.className = "hosted";
    mu.body.append(this.music.el);
    this.ws.music = h("div", { class: "d-ws ws-full" }, mu.el);
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
    const title = h("header", { class: "d-title" }, h("div", { class: "d-brand" }, h("span", { class: "mark" }), "VideoStudio", h("small", {}, "2")), menus, this.tabsEl, this.projEl, this.linkBtn, vsd ? winctl : null);
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
    setTimeout(() => void this.checkUpdate(false), 15_000);
    document.addEventListener("vsd:openScene", (e) => this.go("animate", (e as CustomEvent<string>).detail));
    document.addEventListener("vsd:go", (e) => { const n = (e as CustomEvent<string>).detail; if (WS.some(([id]) => id === n)) this.go(n as WsName); });
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
      preview: () => this.viewer.preview,
      timeline: () => this.timeline,
    });
    // 2.3: Code Studio, the libraries and the Blender forge — said once.
    if (!localStorage.getItem("vs23.new")) {
      setTimeout(
        () => {
          localStorage.setItem("vs23.new", "1");
          const li = (t: string, d: string) => h("div", { style: "margin:0 0 9px" }, h("b", {}, t), h("div", { class: "muted", style: "font-size:12.5px;line-height:1.45" }, d));
          const m = modal(
            "New in VideoStudio 2.3",
            h(
              "div",
              { class: "col", style: "max-width:540px" },
              li("Code Studio", "A new Code page: a film written as code. Type on the left, see the frame on the right at once. Every shot is kept as its own video, so after a change only the shots that changed are drawn again and the film is joined in seconds. Claude writes and fixes films here while you watch."),
              li("Ready people, places and things", "380 characters in 28 groups, 22 places at four times of day, 126 props, 43 sounds — click one to put it into the code."),
              li("3D model library", "854 ready 3D models (city, furniture, castle and dungeon, kitchen, space base, medieval world and more) to stand beside the characters of the 3D Stage."),
              li("Blender forge", "If Blender is installed, VideoStudio uses it as an asset machine: trees, rocks, land, buildings, 3D lettering and anything Claude writes for it are made in Blender and placed on the Stage."),
            ),
            [b("Open Code Studio", null, () => { m.close(); this.go("code"); }, "pri"), b("Later", null, () => m.close())],
          );
        },
        localStorage.getItem("vs2.welcome") ? 2500 : 90_000,
      );
    }
    // First start of VideoStudio 2: say what changed, once.
    if (!localStorage.getItem("vs2.welcome")) {
      setTimeout(() => {
        localStorage.setItem("vs2.welcome", "1");
        const li = (t: string, d: string) => h("div", { style: "margin:0 0 9px" }, h("b", {}, t), h("div", { class: "muted", style: "font-size:12.5px;line-height:1.45" }, d));
        const m = modal(
          "Welcome to VideoStudio 2",
          h(
            "div",
            { class: "col", style: "max-width:520px" },
            li("Native media core", "Video and sound are decoded by the built-in ffmpeg engine (graphics card when it is free, processor otherwise). The green picture is gone, and any file — 4K, HEVC, ProRes, 10-bit — is on the timeline the moment you import it, without copying or converting."),
            li("A real player", "−10 s / +10 s, frame steps, J K L shuttle up to 8× both ways, slow motion, in and out marks (I / O), loop, play range (/), click the timecode to type a time, and a scrub bar under the picture."),
            li("Scopes", "Waveform, RGB parade, vectorscope and histogram on the Color page."),
            li("Claude at work", "When Claude edits, you see it: the pointer moves, pages open, the timeline scrolls and zooms, clips flash as they change. Set the pace (Watch · Fast · Off) on the bar at the bottom."),
            h("p", { class: "hint" }, "Your projects and the Claude link carried over from VideoStudio Desktop."),
          ),
          [b("Start editing", null, () => m.close(), "pri")],
        );
      }, 1400);
    }
    // VideoStudio 2.1: say what is new, once (after the first-start note, if that is showing).
    if (!localStorage.getItem("vs21.news")) {
      setTimeout(
        () => {
          localStorage.setItem("vs21.news", "1");
          const li = (t: string, d: string) => h("div", { style: "margin:0 0 9px" }, h("b", {}, t), h("div", { class: "muted", style: "font-size:12.5px;line-height:1.45" }, d));
          const m = modal(
            "New in VideoStudio 2.1",
            h(
              "div",
              { class: "col", style: "max-width:540px" },
              li("A real colour page", "Grading nodes (serial and layer), colour wheels, custom and hue curves, an HSL qualifier with an eyedropper, soft power windows you can animate, LUTs (.cube import, camera log transforms, film looks, export your grade as a LUT), bypass and a compare wipe. Select a clip and open Color."),
              li("Shot Match and Auto balance", "Match one clip's look to another clip or to any reference picture, as a LUT, as editable curves or as primaries — the app measures both and shows how close it got."),
              li("Audience Lens", "On the Insight page: a predicted score for the edit, the moments where attention is likely to drop, titles hidden behind a platform's buttons, text that is too quick to read — each with a one-click fix, and Evolve to apply every safe fix at once. Predictions to guide you, not guarantees."),
              li("Style DNA", "Measure the feel of any finished video — cut rhythm against the music, pacing, colour — and transplant it onto your edit. Your own projects build a personal signature. Everything stays on this PC."),
              li("Faster heavy renders", "Split screens and picture-in-picture no longer share one decoder, and frames reach the encoder already converted on the graphics card."),
            ),
            [b("Got it", null, () => m.close(), "pri")],
          );
        },
        localStorage.getItem("vs2.welcome") ? 1600 : 60_000,
      );
    }
    // Claude at work: every command is acted out in the window (pointer, pages, timeline, flashes, progress bar).
    setLiveHooks(
      new AgentStage(s, {
        go: (n) => WS.some(([id]) => id === n) && this.go(n as WsName),
        current: () => this.cur,
        timeline: this.timeline,
        tab: (n) => (this.tabsEl.children[WS.findIndex(([id]) => id === n)] as HTMLElement | undefined) ?? null,
        playButton: () => this.viewer.el.querySelector("button.play"),
        importButton: () => this.library.el.querySelector("button.pri"),
        viewerBox: () => this.viewer.el.querySelector(".vstage"),
      }),
    );
    // The GPU process restarted (driver hiccup): rebuild the viewer's picture straight away.
    vsd?.onGpuLost?.(() => {
      this.viewer.preview.rebuild();
      toast("Graphics restarted — the viewer was rebuilt.", "info", 4000);
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

  /** Animate's two rooms: the drawing desk, or fixing a film shot. */
  private animateMode(v: "draw" | "fix"): void {
    this.anFix = v === "fix";
    this.anModeTabs?.set(v);
    this.anBody?.classList.toggle("fixing", this.anFix);
    this.paintOver.shown(this.anFix && this.cur === "animate");
    if (this.anFix) {
      this.animate.hide();
      void this.paintOver.open(null);
    } else if (this.cur === "animate") this.animate.open();
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
    } else if (n === "color" || n === "insight") {
      host.insertBefore(this.viewer.el, host.firstChild);
      host.append(this.tlPanel);
    } else if (n === "audio") host.append(this.tlPanel);
    if (n === "animate") {
      if (arg?.startsWith("fix:") || arg === "fix") {
        this.animateMode("fix");
        const [shot, issue] = arg.slice(4).split("#");
        void this.paintOver.open(shot || null, issue || undefined);
      } else {
        if (this.anFix) this.animateMode("draw");
        this.animate.open(arg);
      }
    } else this.paintOver.shown(false);
    this.music.shown(n === "music");
    this.codePage.shown(n === "code");
    this.senseiPage.shown(n === "sensei");
    this.stage3d.shown(n === "stage");
    if (n === "deliver") {
      this.deliver.draw();
      void this.drawRenders();
    }
    this.colorPage.shown(n === "color");
    if (n !== "edit" && n !== "color" && n !== "insight") this.s.pause();
    this.tabsEl.replaceChildren(
      ...WS.map(([id, label, i]) => {
        const bt = h("button", { class: id === n ? "on" : "", type: "button", title: `${label} workspace` }, ic(i, 14), h("span", { class: "wsl" }, label));
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
      { label: "Check for updates…", run: () => void this.checkUpdate(true) },
      { label: "About VideoStudio 2", run: async () => {
        const i = await vsd?.info();
        modal(
          "VideoStudio 2",
          h("div", { class: "hint", style: "line-height:1.8" }, `Version ${i?.version ?? "dev"} · ${i?.platform ?? "web"} ${i?.arch ?? ""}`, h("br"), `${i?.cpuModel ?? ""} · ${i?.cpus ?? "?"} threads · ${i?.memoryGB ?? "?"} GB`, h("br"), `GPU: WebGL ${i?.gpu?.webgl ?? "?"} · WebGPU ${i?.gpu?.webgpu ?? "?"} · video decode ${i?.gpu?.video_decode ?? "?"}`, h("br"), `Renders: ${i?.out.Movies ?? ""}`),
          [b("Close", null, () => document.querySelector(".modal-bg")?.remove(), "pri")],
        );
      } },
    ];
  }

  async checkUpdate(manual = false): Promise<void> {
    if (!vsd) return;
    try {
      const u = await vsd.updateCheck();
      if (!u.available) {
        if (manual) toast(`VideoStudio ${u.current} is up to date.`, "ok");
        return;
      }
      const m = modal(
        `Update ${u.latest} is ready`,
        h("div", { class: "col" }, h("p", { class: "hint" }, `You have ${u.current}. The update downloads (${Math.round((u.size ?? 0) / 1e6)} MB), your system asks for your password, and the app restarts.`), u.notes ? h("div", { class: "ops", style: "white-space:pre-wrap;max-height:200px;overflow:auto" }, u.notes) : null),
        [
          b("Later", null, () => m.close()),
          b("Install now", "download", async () => {
            m.close();
            try {
              const r = await vsd!.updateInstall();
              toast(r.note, "ok", 8000);
            } catch (e) {
              toast((e as Error).message, "error", 8000);
            }
          }, "pri"),
        ],
      );
    } catch (e) {
      if (manual) toast(`Could not check for updates: ${(e as Error).message}`, "error");
    }
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
      ["Wheel on the viewer", "Zoom the picture at the pointer"],
      ["Middle-drag / Alt-drag", "Pan the zoomed picture"],
      ["+ / − / 0", "Viewer zoom in · out · fit"],
      ["G", "Graph editor (animation curves)"],
      ["Alt+1…8", "Workspaces"],
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
      if (e.altKey && /^[1-8]$/.test(k)) {
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
      if (!["edit", "color", "insight", "audio"].includes(this.cur)) return;
      const tp = this.viewer.transport;
      const tl = this.timeline;
      if (ctrl && k.toLowerCase() === "z") return e.preventDefault(), e.shiftKey ? this.s.redo() : this.s.undo();
      if (ctrl && k.toLowerCase() === "y") return e.preventDefault(), this.s.redo();
      if (ctrl && k.toLowerCase() === "d") return e.preventDefault(), tl.duplicateSelected();
      if (ctrl) return;
      switch (k) {
        case " ":
          e.preventDefault();
          tp.toggle();
          break;
        case "k":
        case "K":
          tp.key("k");
          break;
        case "j":
        case "J":
          tp.key("j");
          break;
        case "l":
        case "L":
          tp.key("l");
          break;
        case "ArrowLeft":
          e.preventDefault();
          if (e.shiftKey) tp.jump(-1);
          else tp.step(-1);
          break;
        case "ArrowRight":
          e.preventDefault();
          if (e.shiftKey) tp.jump(1);
          else tp.step(1);
          break;
        case "PageUp":
          e.preventDefault();
          tp.jump(-10);
          break;
        case "PageDown":
          e.preventDefault();
          tp.jump(10);
          break;
        case "i":
        case "I":
          if (e.shiftKey) tp.goto(tp.inPoint ?? 0);
          else tp.markIn();
          break;
        case "o":
        case "O":
          if (e.shiftKey) tp.goto(tp.outPoint ?? this.s.duration);
          else tp.markOut();
          break;
        case "x":
        case "X":
          if (e.altKey) tp.clearMarks();
          break;
        case "/":
          tp.playRange();
          break;
        case "Home":
          tp.goto(0);
          break;
        case "End":
          tp.goto(this.s.duration);
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
        case "0":
          this.viewer.preview.resetView();
          break;
        case "=":
        case "+":
          this.viewer.preview.zoomTo(this.viewer.preview.zoomLevel * 1.25);
          break;
        case "-":
          this.viewer.preview.zoomTo(this.viewer.preview.zoomLevel / 1.25);
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
