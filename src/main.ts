import "@fontsource-variable/geist";
import "@fontsource/instrument-serif/latin-400.css";
import "./styles/app.css";
import { Studio } from "./app/studio";
import { EditorView } from "./ui/editor";
import { AnimateView } from "./ui/animate";
import { VoiceView } from "./ui/voice";
import { ExportView } from "./ui/export";
import { AgentView } from "./ui/agent";
import { RemoteLink } from "./agent/remote";
import { commandList, runBatch, runCommand, type Command } from "./agent/api";
import { deleteProject, storageEstimate } from "./core/storage";
import { btn, confirmBox, fmtBytes, h, icon, pickFiles, select, sheet, toast } from "./ui/dom";
import { saveBlob } from "./native/storage";

type ViewName = "edit" | "animate" | "voice" | "export" | "agent";

async function main() {
  const studio = new Studio();
  const app = document.getElementById("app")!;
  app.replaceChildren(h("div", { class: "boot" }, h("div", { class: "logo" }, "VideoStudio"), h("p", {}, "Loading…")));
  try {
    await studio.boot();
  } catch (e) {
    console.error(e);
    toast("Storage is unavailable; working in memory.", "error", 5000);
  }
  const remote = new RemoteLink(studio);

  let current: ViewName = "edit";
  const views: Record<ViewName, { el: HTMLElement }> = {} as never;
  const animate = new AnimateView(studio);
  const host = { goto: (v: ViewName, arg?: string) => show(v, arg) };
  views.edit = new EditorView(studio, host);
  views.animate = animate;
  views.voice = new VoiceView(studio);
  views.export = new ExportView(studio);
  views.agent = new AgentView(studio, remote);

  const title = h("button", { class: "title", type: "button", onclick: () => projectsSheet() }, h("span", {}, studio.project.name), icon("menu", 16));
  const status = h("span", { class: "status" });
  const top = h("header", { class: "top" }, title, status, h("div", { class: "grow" }), btn("Render", "export", () => show("export"), "primary small"));
  const main = h("main", { class: "views" });
  const nav = h("nav", { class: "bottom" });
  const items: [ViewName, string, string][] = [
    ["edit", "Edit", "film"],
    ["animate", "Animate", "brush"],
    ["voice", "Voice", "mic"],
    ["export", "Render", "export"],
    ["agent", "Agent", "bot"],
  ];

  function show(v: ViewName, arg?: string) {
    if (current === "animate" && v !== "animate") animate.hide();
    current = v;
    main.replaceChildren(views[v].el);
    nav.replaceChildren(...items.map(([id, label, ic]) => h("button", { class: `nav ${id === v ? "on" : ""}`, type: "button", "aria-label": label, onclick: () => show(id) }, icon(ic, 22), h("span", {}, label))));
    if (v === "animate") animate.open(arg);
    if (v === "export") (views.export as ExportView).draw();
    if (v !== "edit") studio.pause();
  }

  studio.on("project", () => (title.firstChild!.textContent = studio.project.name));
  studio.on("change", () => (title.firstChild!.textContent = studio.project.name));
  studio.on("status", () => (status.textContent = studio.status));

  function projectsSheet() {
    const body = h("div", { class: "stack" });
    const s = sheet("Projects", body, { tall: true });
    const draw = async () => {
      const list = await studio.list();
      const est = await storageEstimate();
      const name = h("input", { value: studio.project.name, "aria-label": "Project name" }) as HTMLInputElement;
      name.addEventListener("change", () => studio.mutate("rename project", (p) => (p.name = name.value.trim() || "Untitled")));
      let preset = "1080x1920";
      body.replaceChildren(
        h("h3", {}, "This project"),
        name,
        h("div", { class: "row wrap" },
          btn("Save copy (JSON)", "download", async () => {
            const r = await saveBlob(studio.exportJson(), `${studio.project.name}.videostudio.json`, "Documents");
            toast(`Saved ${r.path}`, "ok");
          }),
          btn("Open JSON", "folder", async () => {
            const [f] = await pickFiles(".json,application/json", false);
            if (!f) return;
            try {
              await studio.importJson(await f.text());
              s.close();
            } catch (e) {
              toast(e instanceof Error ? e.message : String(e), "error");
            }
          }),
        ),
        h("h3", {}, "New project"),
        select("Format", [["1080x1920", "9:16 Shorts / Reels"], ["1920x1080", "16:9 YouTube"], ["1080x1080", "1:1 Square"], ["1080x1350", "4:5 Portrait"], ["3840x2160", "16:9 4K"]] as const, "1080x1920", (v) => (preset = v)),
        btn("Create", "plus", async () => {
          const [w, hh] = preset.split("x").map(Number) as [number, number];
          await studio.newProject("Untitled", w, hh, 30);
          s.close();
          show("edit");
        }, "primary"),
        h("h3", {}, `All projects (${list.length})`),
        ...list.map((p) =>
          h("div", { class: `proj ${p.id === studio.project.id ? "on" : ""}` },
            h("button", { class: "proj-open", type: "button", onclick: async () => (await studio.open(p.id), s.close(), show("edit")) }, h("b", {}, p.name), h("span", { class: "muted small" }, `${p.width}×${p.height} · ${new Date(p.updatedAt).toLocaleString()}`)),
            p.id !== studio.project.id
              ? btn("Delete", "trash", async () => {
                  if (!(await confirmBox(`Delete “${p.name}” and its media?`, "Delete"))) return;
                  await deleteProject(p.id);
                  void draw();
                }, "icon-only ghost")
              : h("span", { class: "muted small" }, "open"),
          ),
        ),
        h("p", { class: "muted small" }, `App storage: ${fmtBytes(est.used)} used of ${fmtBytes(est.quota)}. Media you import is copied into the app's private storage; renders go to Movies/VideoStudio.`),
      );
    };
    void draw();
  }

  app.replaceChildren(top, main, nav);
  show("edit");
  await remote.load();

  // Agent API for in-page automation (and debugging from a desktop browser).
  const api = {
    run: (cmds: Command | Command[], continueOnError = false) => runBatch(studio, Array.isArray(cmds) ? cmds : [cmds], continueOnError),
    one: (cmd: Command) => runCommand(studio, cmd),
    help: () => commandList(),
    get project() {
      return studio.project;
    },
    studio,
  };
  (window as unknown as { videostudio: typeof api }).videostudio = api;

  document.addEventListener("keydown", (e) => {
    if ((e.target as HTMLElement).closest("input, textarea, select")) return;
    if (e.key === " ") {
      e.preventDefault();
      studio.toggle();
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") {
      e.preventDefault();
      if (e.shiftKey) studio.redo();
      else studio.undo();
    }
  });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      studio.pause();
      void studio.save();
    }
  });
}

void main();
