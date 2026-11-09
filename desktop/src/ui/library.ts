/** Library: project media, the PC's own folders, and ready-made titles, motion, effects, transitions and sound. */
import type { Studio } from "../../../src/app/studio";
import type { Asset } from "../../../src/core/model";
import { runCommand, type Command } from "../../../src/agent/api";
import { assetThumb } from "../../../src/render/snapshot";
import * as proc from "../../../src/anim/proc";
import { AMBIENCES, MOODS, SFX } from "../../../src/audio/compose";
import * as ops from "../../../src/core/ops";
import { toast } from "../../../src/ui/dom";
import { fmtSize, vsd, type FsEntry } from "../bridge";
import { importPath } from "../importer";
import { b, h, ic, menu, tabs } from "./kit";

type Tab = "media" | "files" | "titles" | "motion" | "effects" | "sound";

export class Library {
  readonly el: HTMLElement;
  private body: HTMLElement;
  private tab: Tab = "media";
  private thumbs = new Map<string, string>();
  private dir = "";
  private sel: string | null = null;
  private filter: "all" | "video" | "audio" | "image" = "all";
  private search = "";

  constructor(private readonly s: Studio) {
    const t = tabs<Tab>(
      [
        ["media", "Media"],
        ["files", "This PC"],
        ["titles", "Titles"],
        ["motion", "Motion"],
        ["effects", "Effects"],
        ["sound", "Sound"],
      ],
      this.tab,
      (v) => {
        this.tab = v;
        this.render();
      },
    );
    this.body = h("div", { class: "p-body" });
    this.el = h("section", { class: "p lib" }, t.el, this.body);
    s.on("project", () => this.tab === "media" && this.render());
    s.on("change", () => this.tab === "media" && this.renderSoon());
    this.render();
  }

  private soonT = 0;
  private renderSoon() {
    window.clearTimeout(this.soonT);
    this.soonT = window.setTimeout(() => this.render(), 200);
  }

  render(): void {
    const scroll = this.body.scrollTop;
    const views: Record<Tab, () => HTMLElement[]> = {
      media: () => this.media(),
      files: () => this.files(),
      titles: () => this.titles(),
      motion: () => this.motion(),
      effects: () => this.effects(),
      sound: () => this.sound(),
    };
    this.body.replaceChildren(...views[this.tab]());
    this.body.scrollTop = scroll;
  }

  private async run(cmd: Command, ok?: string): Promise<void> {
    this.s.setStatus(`Working: ${cmd.op}…`);
    const r = await runCommand(this.s, cmd);
    if (!r.ok) toast(r.error ?? "Failed", "error");
    else if (ok) toast(ok, "ok");
    this.s.setStatus("");
  }

  /* ------------------------------------------------------------ media */

  async importPaths(paths: string[]): Promise<void> {
    for (const p of paths) {
      try {
        this.s.setStatus(`Importing ${p.split("/").pop()}…`);
        const r = await importPath(this.s, p, false);
        if (r.converted) toast(`${r.asset.name}: ${r.converted}`, "ok", 4000);
      } catch (e) {
        toast((e as Error).message, "error");
      }
    }
    this.s.setStatus("");
    this.tab = "media";
    this.render();
  }

  async importDialog(): Promise<void> {
    if (!vsd) return;
    const paths = await vsd.pick({});
    if (paths.length) await this.importPaths(paths);
  }

  private media(): HTMLElement[] {
    const p = this.s.project;
    const search = h("input", { class: "inp", placeholder: "Search media", value: this.search, style: "flex:1" }) as HTMLInputElement;
    search.addEventListener("keydown", (e) => e.stopPropagation());
    search.addEventListener("input", () => {
      this.search = search.value;
      grid.replaceChildren(...this.tiles());
    });
    const chips = (["all", "video", "audio", "image"] as const).map((k) => {
      const c = h("button", { class: `chip ${this.filter === k ? "on" : ""}`, type: "button" }, k === "all" ? "All" : k[0]!.toUpperCase() + k.slice(1));
      c.addEventListener("click", () => {
        this.filter = k;
        this.render();
      });
      return c;
    });
    const grid = h("div", { class: "lib-grid" }, ...this.tiles());
    return [
      h("div", { class: "row", style: "margin-bottom:8px" }, b("Import", "plus", () => void this.importDialog(), "pri sm"), search),
      h("div", { class: "places" }, ...chips),
      p.assets.length ? grid : h("div", { class: "empty" }, "No media yet.", h("br"), "Import files, drag them in from your file manager, or browse ", h("b", {}, "This PC"), "."),
    ];
  }

  private tiles(): HTMLElement[] {
    const q = this.search.toLowerCase();
    return this.s.project.assets
      .filter((a) => a.kind !== "model" && (this.filter === "all" || a.kind === this.filter) && (!q || a.name.toLowerCase().includes(q)))
      .map((a) => this.tile(a));
  }

  private tile(a: Asset): HTMLElement {
    const kindColor = a.kind === "video" ? "var(--video)" : a.kind === "audio" ? "var(--audio)" : "var(--image)";
    const dur = a.duration ? `${Math.floor(a.duration / 60)}:${String(Math.floor(a.duration % 60)).padStart(2, "0")}` : "";
    const pic = h("div", { class: "ph" }, ic(a.kind === "audio" ? "wave" : a.kind === "image" ? "image" : "film", 26));
    const el = h("div", { class: `tile ${this.sel === a.id ? "sel" : ""}`, draggable: "true", title: `${a.name}\n${a.width ? `${a.width}×${a.height} · ` : ""}${fmtSize(a.size)}` }, pic, h("span", { class: "kind", style: `background:${kindColor}` }), dur ? h("span", { class: "badge" }, dur) : null, h("div", { class: "cap" }, a.name));
    if (a.kind === "audio" && a.peaks?.length) {
      const c = h("canvas", { width: "240", height: "150" }) as HTMLCanvasElement;
      const g = c.getContext("2d")!;
      g.fillStyle = "#0c1a14";
      g.fillRect(0, 0, 240, 150);
      g.fillStyle = "#2fb36a";
      const pk = a.peaks;
      for (let x = 0; x < 240; x += 2) {
        const v = pk[Math.floor((x / 240) * pk.length)] ?? 0;
        g.fillRect(x, 75 - v * 60, 1.5, v * 120 + 1);
      }
      pic.replaceWith(c);
    } else if (a.kind !== "audio") {
      const cached = this.thumbs.get(a.id);
      const setImg = (src: string) => {
        const img = h("img", { src, alt: "" });
        el.firstChild?.replaceWith(img);
      };
      if (cached) setImg(cached);
      else
        void assetThumb(this.s.project, this.s.pool, a.id, Math.min(1, a.duration / 3), 260)
          .then((r) => {
            this.thumbs.set(a.id, r.jpeg);
            setImg(r.jpeg);
          })
          .catch(() => {});
    }
    el.addEventListener("dragstart", (e) => {
      e.dataTransfer?.setData("application/x-vs-asset", a.id);
      e.dataTransfer!.effectAllowed = "copy";
    });
    el.addEventListener("click", () => {
      this.sel = a.id;
      for (const t of el.parentElement?.children ?? []) t.classList.remove("sel");
      el.classList.add("sel");
      document.dispatchEvent(new CustomEvent("vsd:asset", { detail: a.id }));
    });
    el.addEventListener("dblclick", () => this.s.mutate("add clip", (p) => this.s.select(ops.addClip(p, { assetId: a.id, start: this.s.time }).id)));
    el.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      menu(
        [
          { label: "Add at playhead", run: () => this.s.mutate("add clip", (p) => this.s.select(ops.addClip(p, { assetId: a.id, start: this.s.time }).id)) },
          { label: "Add at the end", run: () => this.s.mutate("add clip", (p) => this.s.select(ops.addClip(p, { assetId: a.id }).id)) },
          ...(a.kind !== "image" ? [{ label: "Read the sound (Audio workspace)", run: () => document.dispatchEvent(new CustomEvent("vsd:inspectAudio", { detail: a.id })) }] : []),
          ...(a.kind === "audio" ? [{ label: "Detect beats → markers", run: () => void this.run({ op: "audio.beats", assetId: a.id }, "Beat markers added") }] : []),
          ...(a.kind === "video" ? [{ label: "Find shot changes", run: () => void this.run({ op: "media.scenes", assetId: a.id }, "Scenes analysed") }] : []),
          { sep: true, label: "" },
          { label: "Remove from project", run: () => this.s.mutate("remove media", (p) => ops.removeAsset(p, a.id)) },
        ],
        e.clientX,
        e.clientY,
      );
    });
    return el;
  }

  /* ------------------------------------------------------------ PC files */

  private files(): HTMLElement[] {
    const box = h("div", { class: "col", style: "gap:6px" }, h("div", { class: "empty" }, "Reading your folders…"));
    if (!vsd) return [h("div", { class: "empty" }, "Your folders are available in VideoStudio Desktop.")];
    void (async () => {
      const info = await vsd.info();
      if (!this.dir) this.dir = info.dirs.videos;
      const places = h(
        "div",
        { class: "places" },
        ...(
          [
            ["Videos", info.dirs.videos, "film"],
            ["Music", info.dirs.music, "wave"],
            ["Pictures", info.dirs.pictures, "image"],
            ["Downloads", info.dirs.downloads, "download"],
            ["Desktop", info.dirs.desktop, "folder"],
            ["Home", info.dirs.home, "home"],
            ...info.drives.map((d) => [d.name, d.path, "drive"] as [string, string, string]),
          ] as [string, string, string][]
        ).map(([n, p, i]) => {
          const c = h("button", { class: `chip ${this.dir === p ? "on" : ""}`, type: "button" }, ic(i, 12), n);
          c.addEventListener("click", () => {
            this.dir = p;
            this.render();
          });
          return c;
        }),
      );
      const search = h("input", { class: "inp", placeholder: "Find media on this PC…", style: "width:100%;margin-bottom:6px" }) as HTMLInputElement;
      search.addEventListener("keydown", async (e) => {
        e.stopPropagation();
        if (e.key !== "Enter" || !search.value.trim()) return;
        list.replaceChildren(h("div", { class: "empty" }, "Searching…"));
        const r = await vsd!.find(info.dirs.home, search.value, { limit: 150 });
        list.replaceChildren(...(r.results.length ? r.results.map((f) => this.fileRow(f)) : [h("div", { class: "empty" }, "Nothing found.")]));
      });
      const list = h("div", { class: "flist" });
      let res;
      try {
        res = await vsd.list(this.dir, { sort: "name" });
      } catch (e) {
        box.replaceChildren(places, h("div", { class: "empty" }, (e as Error).message));
        return;
      }
      const crumbs = h("div", { class: "crumbs" });
      const parts = res.dir.split("/").filter(Boolean);
      let acc = "";
      for (const part of parts) {
        acc += `/${part}`;
        const target = acc;
        const bt = h("button", { type: "button" }, part);
        bt.addEventListener("click", () => {
          this.dir = target;
          this.render();
        });
        crumbs.append(bt, h("span", { class: "faint" }, "/"));
      }
      if (res.parent) {
        const up = h("div", { class: "fitem" }, ic("up", 14), h("span", { class: "nm muted" }, "Up one folder"), h("span"));
        up.addEventListener("click", () => {
          this.dir = res!.parent!;
          this.render();
        });
        list.append(up);
      }
      const shown = res.entries.filter((e) => e.kind !== "other");
      list.append(...shown.map((f) => this.fileRow(f)));
      if (!shown.length) list.append(h("div", { class: "empty" }, "No media in this folder."));
      const media = shown.filter((f) => ["video", "audio", "image"].includes(f.kind));
      box.replaceChildren(
        places,
        search,
        crumbs,
        media.length ? h("div", { class: "row", style: "margin-bottom:6px" }, b(`Import all ${media.length}`, "download", () => void this.importPaths(media.map((m) => m.path)), "sm")) : h("span"),
        list,
      );
    })();
    return [box];
  }

  private fileRow(f: FsEntry): HTMLElement {
    const iconName = f.kind === "folder" ? "folder" : f.kind === "video" ? "film" : f.kind === "audio" ? "wave" : f.kind === "image" ? "image" : "layers";
    const color = f.kind === "folder" ? "var(--warn)" : f.kind === "video" ? "var(--video)" : f.kind === "audio" ? "var(--audio)" : "var(--image)";
    const row = h("div", { class: "fitem", title: f.path }, h("span", { style: `color:${color};display:grid` }, ic(iconName, 15)), h("span", { class: "nm" }, f.name), h("span", { class: "sz" }, f.kind === "folder" ? "" : fmtSize(f.size)));
    row.addEventListener("click", () => {
      if (f.kind === "folder") {
        this.dir = f.path;
        this.render();
      }
    });
    row.addEventListener("dblclick", () => f.kind !== "folder" && void this.importPaths([f.path]));
    row.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      if (f.kind === "folder") return;
      menu(
        [
          { label: "Import into project", run: () => void this.importPaths([f.path]) },
          { label: "Import and add at playhead", run: async () => {
            const r = await runCommand(this.s, { op: "media.importPath", path: f.path, add: true, start: this.s.time });
            if (!r.ok) toast(r.error ?? "Failed", "error");
          } },
          { label: "Show in file manager", run: () => void vsd?.reveal(f.path) },
        ],
        e.clientX,
        e.clientY,
      );
    });
    return row;
  }

  /* ------------------------------------------------------------ presets */

  private preset(title: string, desc: string, cmd: () => Command | Promise<Command>, ok?: string): HTMLElement {
    const el = h("button", { class: "preset", type: "button" }, h("b", {}, title), h("span", {}, desc));
    el.addEventListener("click", async () => {
      try {
        await this.run(await cmd(), ok);
      } catch (e) {
        toast((e as Error).message, "error");
      }
    });
    return el;
  }

  private titles(): HTMLElement[] {
    const t = () => this.s.time;
    const styles = Object.keys(ops.TEXT_PRESETS);
    return [
      h("div", { class: "group-title" }, "Text"),
      h(
        "div",
        { class: "preset-grid" },
        this.preset("Title", "Plain title at the playhead", () => ({ op: "clip.add", text: "Your title", start: t(), dur: 3 })),
        this.preset("Lower third", "Name and role strip", () => ({ op: "kinetic.add", text: "Name Surname/Role", style: proc.KINETIC_STYLES[0], start: t(), dur: 3.5 })),
        this.preset("Captions", "Word-by-word, timed to speech", () => {
          const voice = this.s.project.assets.find((a) => a.kind === "audio" || a.kind === "video");
          return { op: "captions.add", text: "Type your words here", assetId: voice?.id, start: t(), style: proc.CAPTION_STYLES[0] };
        }),
        this.preset("Handwritten lines", "Lyrics inked as they are sung", () => ({ op: "lyrics.write", lines: [{ start: t(), end: t() + 3, text: "first line" }, { start: t() + 3, end: t() + 6, text: "second line" }] })),
      ),
      h("div", { class: "group-title" }, "Kinetic typography"),
      h("div", { class: "preset-grid" }, ...proc.KINETIC_STYLES.map((st) => this.preset(st, "Big animated words", () => ({ op: "kinetic.add", text: "MAKE IT/MOVE", style: st, start: t(), dur: 2.5 })))),
      h("div", { class: "group-title" }, "Title looks (select a text clip)"),
      h(
        "div",
        { class: "preset-grid" },
        ...styles.map((st) =>
          this.preset(st, "Font, animation and glow", () => {
            const id = this.s.selection;
            return id ? { op: "clip.textPreset", id, preset: st } : { op: "clip.add", text: st.toUpperCase(), start: t(), dur: 3 };
          }),
        ),
      ),
    ];
  }

  private motion(): HTMLElement[] {
    const t = () => this.s.time;
    return [
      h("div", { class: "group-title" }, "Motion graphics"),
      h("div", { class: "preset-grid" }, ...proc.MOGRAPH_TYPES.map((ty) => this.preset(ty, "Motion-design element", () => ({ op: "mograph.add", type: ty, start: t(), dur: 3 })))),
      h("div", { class: "group-title" }, "Hand-drawn effects"),
      h("div", { class: "preset-grid" }, ...proc.FX_TYPES.map((ty) => this.preset(ty, "Drawn on twos", () => ({ op: "fx.add", type: ty, start: t(), dur: 2 })))),
      h("div", { class: "group-title" }, "Cinematic"),
      h(
        "div",
        { class: "preset-grid" },
        this.preset("Letterbox reveal", "Shot opens from a thin band", () => {
          const id = this.s.selection;
          if (!id) throw new Error("Select a clip first.");
          return { op: "fx.bandReveal", id };
        }),
        this.preset("Virtual multicam", "Close-ups and wides cut on the beat", () => {
          const id = this.s.selection;
          if (!id) throw new Error("Select a clip first.");
          return { op: "camera.auto", id };
        }),
        this.preset("Punch-in zoom", "Snap in, ease back at the playhead", () => {
          const id = this.s.selection;
          if (!id) throw new Error("Select a clip first.");
          return { op: "clip.punch", id, at: t() };
        }),
        this.preset("Speed ramp", "Smooth slow-mo ramp", () => {
          const id = this.s.selection;
          if (!id) throw new Error("Select a clip first.");
          return { op: "clip.speedRamp", id, preset: Object.keys(ops.RAMPS)[0] };
        }),
      ),
    ];
  }

  private effects(): HTMLElement[] {
    const t = () => this.s.time;
    return [
      h("div", { class: "group-title" }, "Film overlays"),
      h("div", { class: "preset-grid" }, ...proc.OVERLAY_TYPES.map((ty) => this.preset(ty, "Overlay at the playhead", () => ({ op: "overlay.add", type: ty, start: t(), dur: 2 })))),
      h("div", { class: "group-title" }, "Transition overlays"),
      h("div", { class: "preset-grid" }, ...(["stripe", "filmBurn", "lightLeak"] as const).map((ty) => this.preset(ty, "Centred on the playhead cut", () => ({ op: "transition.overlay", at: t(), type: ty })))),
      h("div", { class: "group-title" }, "Adjustment"),
      h(
        "div",
        { class: "preset-grid" },
        this.preset("Adjustment layer", "Grade everything under it", () => ({ op: "clip.add", kind: "adjust", start: t(), dur: 5 })),
        this.preset("Colour matte", "Solid colour clip", () => ({ op: "clip.add", kind: "color", start: t(), dur: 3 })),
      ),
      h("div", { class: "hint", style: "margin-top:10px" }, "Clip effects (glow, blur, RGB split, glitch…) and colour are in the inspector on the right."),
    ];
  }

  private sound(): HTMLElement[] {
    const t = () => this.s.time;
    return [
      h("div", { class: "group-title" }, "Ambience beds"),
      h(
        "div",
        { class: "preset-grid" },
        ...AMBIENCES.map((ty) =>
          this.preset(ty === "sea" ? "Calm sea" : ty === "shore" ? "Shore waves" : ty, "Generated bed at the playhead", () => ({ op: "sfx.ambience", type: ty, seconds: Math.max(10, Math.min(300, this.s.duration - t() || 30)), start: t(), volume: 0.45 }), "Ambience added"),
        ),
      ),
      h("div", { class: "group-title" }, "Sound effects"),
      h("div", { class: "preset-grid" }, ...SFX.map((ty) => this.preset(ty, "At the playhead", () => ({ op: "sfx.add", type: ty, at: t() })))),
      h("div", { class: "group-title" }, "Score"),
      h("div", { class: "preset-grid" }, ...MOODS.map((m) => this.preset(m, "Original music, 30 s", () => ({ op: "music.compose", seconds: 30, mood: m, start: t() }), "Score added"))),
      h("div", { class: "group-title" }, "Whole edit"),
      h("div", { class: "preset-grid" }, this.preset("Auto sound design", "Whooshes, pops and hits for every cut and title", () => ({ op: "sfx.auto", style: "cinematic" }), "Sound design added"), this.preset("Duck music under voices", "Lowers music when someone speaks", () => ({ op: "audio.duck" }))),
    ];
  }
}
