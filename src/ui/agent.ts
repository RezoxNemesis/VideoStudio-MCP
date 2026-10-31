/** Agent tab: run JSON command batches by hand, browse the command list, and link a remote AI through the relay. */
import type { Studio } from "../app/studio";
import { commandList, runBatch, type Command } from "../agent/api";
import type { RemoteLink } from "../agent/remote";
import { btn, h, toast } from "./dom";

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

  constructor(
    private readonly s: Studio,
    private readonly remote: RemoteLink,
  ) {
    this.input = h("textarea", { class: "code", rows: 8, spellcheck: "false", "aria-label": "Commands (JSON)" }, JSON.stringify(EXAMPLES[0]![1], null, 1)) as HTMLTextAreaElement;
    this.log = h("pre", { class: "log" });
    this.remoteBox = h("div", { class: "remote" });
    const ref = h("details", { class: "ref" }, h("summary", {}, `Command reference (${commandList().length})`), h("dl", {}, ...commandList().flatMap((c) => [h("dt", {}, c.op), h("dd", {}, c.help)])));
    this.el = h(
      "div",
      { class: "view agent" },
      h("div", { class: "panel" },
        h("h2", {}, "Agent control"),
        h("p", { class: "muted small" }, "Everything in the app is a JSON command. Claude and any AI you link can drive editing, drawing, voices and rendering. No command can open your gallery."),
        this.remoteBox,
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

  private drawRemote() {
    const r = this.remote;
    const url = h("input", { value: r.config.relay, placeholder: "https://videostudio-relay.<you>.workers.dev", "aria-label": "Relay URL", inputmode: "url" }) as HTMLInputElement;
    url.addEventListener("change", () => {
      r.config.relay = url.value.trim();
      void r.save();
      this.drawRemote();
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
