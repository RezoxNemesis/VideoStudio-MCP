/** Agent tab: run JSON command batches by hand, browse the command list, and link a remote AI through the relay. */
import { agentNative } from "../native/storage";
import type { Studio } from "../app/studio";
import { commandList, runBatch, type Command } from "../agent/api";
import { listInbox, type RemoteLink } from "../agent/remote";
import { fmtBytes } from "./dom";
import { btn, h, toast } from "./dom";
import type { LiveDirector, LivePace } from "./live";
import type { Notifier, NotifySettings } from "../native/notify";

const EXAMPLES: [string, Command[]][] = [
  [
    "Title card",
    [
      { op: "clip.add", kind: "color", start: 0, dur: 4, props: { color: "#101828" } },
      { op: "clip.add", text: "VideoStudio", start: 0, dur: 4 },
      { op: "clip.set", id: "$1.id", patch: { text: { animate: "rise", size: 160, color: "#ffffff" } } },
      { op: "clip.transition", id: "$1.id", out: "fade", dur: 0.6 },
    ],
  ],
  [
    "Draw a bouncing ball",
    [
      { op: "anim.scene.new", name: "Ball", frames: 24, fps: 24 },
      { op: "anim.shape", sceneId: "$0.id", layerId: "$0.layers[0].id", frame: 0, shape: "ellipse", x: 540, y: 300, w: 200, h: 200, fill: "#e53935", color: "#111111" },
      { op: "anim.layer.key", sceneId: "$0.id", layerId: "$0.layers[0].id", prop: "y", frame: 0, v: 0, ease: "in" },
      { op: "anim.layer.key", sceneId: "$0.id", layerId: "$0.layers[0].id", prop: "y", frame: 12, v: 1300, ease: "out" },
      { op: "anim.layer.key", sceneId: "$0.id", layerId: "$0.layers[0].id", prop: "y", frame: 23, v: 0, ease: "in" },
      { op: "anim.toTimeline", sceneId: "$0.id", start: 0, dur: 4 },
    ],
  ],
  ["Voice line", [{ op: "voice.speak", text: "Maya (excited): We did it!\nJon (whispering): Not so loud.", mode: "dialogue" }]],
  ["Project state", [{ op: "state" }]],
  ["Render 1080p", [{ op: "render.start", height: 1080, quality: "high" }]],
];

export class AgentView {
  readonly el: HTMLElement;
  private input: HTMLTextAreaElement;
  private log: HTMLElement;
  private remoteBox: HTMLElement;
  private inbox: HTMLElement;
  private settingsBox: HTMLElement;

  constructor(
    private readonly s: Studio,
    private readonly remote: RemoteLink,
    private readonly live: LiveDirector,
    private readonly notifier: Notifier,
  ) {
    this.settingsBox = h("div", { class: "remote" });
    this.input = h("textarea", { class: "code", rows: 8, spellcheck: "false", "aria-label": "Commands (JSON)" }, JSON.stringify(EXAMPLES[0]![1], null, 1)) as HTMLTextAreaElement;
    this.log = h("pre", { class: "log" });
    this.remoteBox = h("div", { class: "remote" });
    this.inbox = h("div", { class: "inbox" });
    const ref = h("details", { class: "ref" }, h("summary", {}, `Command reference (${commandList().length})`), h("dl", {}, ...commandList().flatMap((c) => [h("dt", {}, c.op), h("dd", {}, c.help)])));
    this.el = h(
      "div",
      { class: "view agent" },
      h("div", { class: "panel" },
        h("h2", {}, "Agent control"),
        h("p", { class: "muted small" }, "Everything in the app is a JSON command. Claude and any AI you link can drive editing, drawing, voices and rendering. No command can open your gallery."),
        this.remoteBox,
        this.settingsBox,
        this.inbox,
        h("h3", {}, "Run commands"),
        h("div", { class: "chips" }, ...EXAMPLES.map(([name, cmds]) => h("button", { class: "chip", type: "button", onclick: () => (this.input.value = JSON.stringify(cmds, null, 1)) }, name))),
        this.input,
        h("div", { class: "row" }, btn("Run", "play", () => void this.run(), "primary"), btn("Clear log", "trash", () => (this.log.textContent = ""), "ghost")),
        this.log,
        ref,
      ),
    );
    remote.onState = () => this.drawRemote();
    this.drawRemote();
    this.drawSettings();
    void this.drawInbox();
  }

  async drawInbox(): Promise<void> {
    if (!this.remote.config.relay) {
      this.inbox.replaceChildren();
      return;
    }
    let files: Awaited<ReturnType<typeof listInbox>> = [];
    try {
      files = await listInbox();
    } catch {
      this.inbox.replaceChildren();
      return;
    }
    this.inbox.replaceChildren(
      h("h3", {}, `Inbox (${files.length})`, btn("Refresh", "redo", () => void this.drawInbox(), "icon-only ghost")),
      files.length
        ? h("div", { class: "stack" }, ...files.map((f) =>
            h("div", { class: "proj" },
              h("div", { class: "proj-open" }, h("b", {}, f.name), h("span", { class: "muted small" }, `${fmtBytes(f.size)}${f.complete ? "" : " · uploading…"}`)),
              f.complete ? btn("Import", "download", async () => {
                const [r] = await runBatch(this.s, [{ op: "media.importInbox", fileId: f.id }]);
                if (r?.ok) toast(`Imported ${f.name}`, "ok");
                else toast(r?.error ?? "Import failed", "error");
                void this.drawInbox();
              }, "primary small") : h("span", {}),
            )))
        : h("p", { class: "hint" }, "Files shared with Claude in chat arrive here, ready to import."),
    );
  }

  private async run() {
    let cmds: Command[];
    try {
      const v = JSON.parse(this.input.value) as Command | Command[];
      cmds = Array.isArray(v) ? v : [v];
    } catch (e) {
      return toast(`Not valid JSON: ${e instanceof Error ? e.message : e}`, "error");
    }
    const t0 = performance.now();
    const res = await runBatch(this.s, cmds);
    this.log.textContent = `// ${res.filter((r) => r.ok).length}/${cmds.length} ok in ${Math.round(performance.now() - t0)} ms\n${JSON.stringify(res, null, 1)}\n\n${this.log.textContent}`.slice(0, 60_000);
    if (res.some((r) => !r.ok)) toast(res.find((r) => !r.ok)?.error ?? "Error", "error", 5000);
  }

  private drawSettings() {
    const L = this.live;
    const N = this.notifier;
    const chk = (label: string, on: boolean, fn: (v: boolean) => void, disabled = false) => {
      const cb = h("input", { type: "checkbox", "aria-label": label }) as HTMLInputElement;
      cb.checked = on;
      cb.disabled = disabled;
      cb.addEventListener("change", () => fn(cb.checked));
      return h("label", { class: "frow" }, cb, h("span", {}, ` ${label}`));
    };
    const pace = (p: LivePace, label: string) => h("button", { class: `chip ${L.settings.pace === p ? "on" : ""}`, type: "button", onclick: () => ((L.settings.pace = p), L.save(), this.drawSettings()) }, label);
    const n = N.settings;
    const setN = (k: keyof NotifySettings) => (v: boolean) => {
      (n as unknown as Record<string, boolean>)[k] = v;
      void N.save();
    };
    this.settingsBox.replaceChildren(
      h("h3", {}, "Live mode"),
      h("p", { class: "muted small" }, "Watch Claude work: the app switches tabs, opens tools, selects clips, zooms the timeline and scrubs frames as each command runs. Pause, step or stop from the bar at the top."),
      chk("Show agent work live", L.settings.enabled, (v) => ((L.settings.enabled = v), L.save())),
      h("div", { class: "chips" }, pace("instant", "Instant"), pace("watch", "Watch"), pace("cinematic", "Cinematic")),
      h("h3", {}, "Notifications"),
      chk("Enable notifications", n.enabled, async (v) => {
        if (v) {
          const ok = await N.enable();
          toast(ok ? "Notifications on" : "Android blocked notifications. Allow them in Settings → Apps → VideoStudio.", ok ? "ok" : "error", 4000);
        } else await N.disable();
        this.drawSettings();
      }),
      chk("When Claude starts a job", n.jobStart, setN("jobStart"), !n.enabled),
      chk("When a job finishes (or fails)", n.jobDone, setN("jobDone"), !n.enabled),
      chk("When a render is saved", n.render, setN("render"), !n.enabled),
      chk("When a file arrives from chat", n.inbox, setN("inbox"), !n.enabled),
      chk("Also while the app is open", n.whileOpen, setN("whileOpen"), !n.enabled),
    );
  }

  private drawRemote() {
    const r = this.remote;
    const url = h("input", { value: r.config.relay, placeholder: "https://videostudio-relay.<you>.workers.dev", "aria-label": "Relay URL", inputmode: "url" }) as HTMLInputElement;
    url.addEventListener("change", () => {
      r.config.relay = url.value.trim();
      void r.save();
      this.drawRemote();
      void this.drawInbox();
    });
    const mcp = r.mcpUrl();
    const dot = h("span", { class: `dot ${r.state}` });
    this.remoteBox.replaceChildren(
      h("h3", {}, "Remote link ", dot, h("span", { class: "muted small" }, ` ${r.state}${r.lastError ? ` · ${r.lastError}` : ""}${r.jobsRun ? ` · ${r.jobsRun} jobs` : ""}`)),
      h("label", { class: "frow" }, h("span", { class: "lbl" }, "Relay"), url),
      mcp ? h("div", { class: "mcp" }, h("code", {}, mcp.replace(r.config.key, "••••••")), btn("Copy MCP URL", "link", async () => {
        try {
          await navigator.clipboard.writeText(mcp);
          toast("Copied. Add it in Claude as a custom connector.", "ok");
        } catch {
          prompt("MCP connector URL", mcp);
        }
      })) : h("p", { class: "hint" }, "Deploy the relay in /relay of the repo (one Cloudflare Worker), paste its address here, then add the MCP URL in Claude → Settings → Connectors."),
      (() => {
        const cb = h("input", { type: "checkbox", "aria-label": "Keep the screen on while linked" }) as HTMLInputElement;
        cb.checked = r.config.awake !== false;
        cb.addEventListener("change", () => r.setAwake(cb.checked));
        return h("label", { class: "frow" }, cb, h("span", {}, " Keep screen on while linked (jobs start instantly)"));
      })(),
      (() => {
        const cb = h("input", { type: "checkbox", "aria-label": "Background agent mode" }) as HTMLInputElement;
        cb.checked = !!r.config.background;
        cb.addEventListener("change", async () => {
          const res = await r.setBackground(cb.checked);
          if (cb.checked) toast(res.canAutoOpen ? "Agents can now work in the background and open the app themselves." : "Agents keep working in the background. To let them open the app when it's closed, tap 'Allow auto-open'.", "ok", 5000);
        });
        return h("label", { class: "frow" }, cb, h("span", {}, " Background agent mode: keep working behind other apps and with the screen off, and wake the app for new work"));
      })(),
      r.config.background ? btn("Allow auto-open (Display over other apps)", "next", () => void agentNative.openAutoOpenSettings(), "ghost") : h("span"),
      h("div", { class: "row wrap" },
        r.state === "off" ? btn("Connect", "link", () => (r.config.relay ? r.start() : toast("Enter the relay address first", "error")), "primary") : btn("Disconnect", "close", () => r.stop(), "ghost"),
        btn("New key", "key", async () => {
          await r.rotateKey();
          toast("New key made. Update the connector URL in Claude.");
        }, "ghost"),
      ),
    );
  }
}
