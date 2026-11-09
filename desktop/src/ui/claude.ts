/** Claude workspace: the one-time connector setup, the link status, and a live log of what Claude is doing. */
import { commandList } from "../../../src/agent/api";
import { toast } from "../../../src/ui/dom";
import type { DesktopLink } from "../link";
import { vsd } from "../bridge";
import { b, h, panel } from "./kit";

export class ClaudeWorkspace {
  readonly left: HTMLElement;
  readonly right: HTMLElement;
  private leftBody: HTMLElement;
  private rightBody: HTMLElement;
  private reveal = false;
  private q = "";

  constructor(private readonly link: DesktopLink) {
    const l = panel("Claude link");
    this.left = l.el;
    this.leftBody = l.body;
    const r = panel("Activity");
    this.right = r.el;
    this.rightBody = r.body;
    link.on(() => this.render());
    this.render();
  }

  render(): void {
    const L = this.link;
    const label = L.busy ? "Claude is working" : L.state === "online" ? "Connected — ready for Claude" : L.state === "connecting" ? "Connecting…" : L.state === "error" ? "Can't reach the link" : "Link is off";
    const url = h("div", { class: `urlbox ${this.reveal ? "" : "hide"}` }, h("code", {}, L.mcpUrl()), b(this.reveal ? "Hide" : "Show", null, () => {
      this.reveal = !this.reveal;
      this.render();
    }, "sm ghost"), b("Copy", "copy", async () => {
      await navigator.clipboard.writeText(L.mcpUrl());
      toast("Connector URL copied", "ok");
    }, "sm pri"));
    this.leftBody.replaceChildren(
      h(
        "div",
        { class: "cl-hero" },
        h("div", { class: "row" }, h("span", { class: `dot ${L.busy ? "busy" : L.state}` }), h("span", { class: "muted" }, label)),
        h("h2", {}, "Let Claude edit with you"),
        h("p", { class: "hint" }, "Claude works inside this app on your command: it edits, colours, animates, writes voices, reads your music and renders, and you watch every step happen here."),
        url,
        h(
          "ol",
          { class: "steps" },
          h("li", {}, "Press ", h("b", {}, "Copy"), " above."),
          h("li", {}, "In Claude, open ", h("b", {}, "Settings → Connectors → Add custom connector"), "."),
          h("li", {}, "Name it ", h("b", {}, "VideoStudio Desktop"), ", paste the URL, and save."),
          h("li", {}, "Keep this app open. Ask Claude for an edit — that's it."),
        ),
        h(
          "div",
          { class: "row wrap", style: "margin-top:14px" },
          L.config.enabled ? b("Turn link off", null, () => L.stop(), "sm") : b("Turn link on", null, () => L.start(), "sm pri"),
          b("New private URL", null, async () => {
            if (!confirm("Make a new connector URL? The old one stops working and you will paste the new one into Claude.")) return;
            await L.newKey();
            toast("New URL made — update the connector in Claude", "ok");
          }, "sm"),
          b("Open link page", null, () => void vsd?.openExternal(L.mcpUrl()), "sm ghost"),
        ),
        L.lastError ? h("p", { class: "hint", style: "color:var(--danger);margin-top:10px" }, L.lastError) : null,
        h("p", { class: "hint", style: "margin-top:14px" }, "Keep the URL private: it works like a password to this editor. Claude can only use your own folders (home and drives), never system files, and never deletes anything."),
      ),
    );
    const acts = L.activity.slice(0, 40).map((a) => {
      const mins = Math.max(0, Math.round((Date.now() - a.at) / 60000));
      return h(
        "div",
        { class: "act" },
        h("span", { class: `dot ${a.ok === undefined ? "busy" : a.ok ? "online" : "error"}`, style: "margin-top:4px" }),
        h(
          "div",
          {},
          h("div", {}, `${a.ops.length} step${a.ops.length > 1 ? "s" : ""}`, h("span", { class: "faint" }, ` · ${mins ? `${mins} min ago` : "just now"}${a.ms ? ` · ${(a.ms / 1000).toFixed(1)} s` : ""}`)),
          h("div", { class: "ops" }, a.ops.slice(0, 14).join(" → ") + (a.ops.length > 14 ? " …" : "")),
          a.ok === undefined ? h("div", { class: "bar" }, h("i", { style: `width:${(a.done / a.ops.length) * 100}%` })) : null,
          a.error ? h("div", { class: "err" }, a.error) : null,
        ),
        h("span"),
      );
    });
    const search = h("input", { class: "inp", placeholder: "Search what Claude can do…", value: this.q, style: "width:100%;margin:6px 0 8px" }) as HTMLInputElement;
    search.addEventListener("keydown", (e) => e.stopPropagation());
    const list = h("div", { class: "col", style: "gap:4px" });
    const fill = () => {
      const q = this.q.toLowerCase();
      const cmds = commandList().filter((c) => !q || c.op.toLowerCase().includes(q) || c.help.toLowerCase().includes(q));
      list.replaceChildren(...cmds.slice(0, 120).map((c) => h("div", { class: "act", style: "grid-template-columns:1fr" }, h("div", {}, h("b", { class: "mono", style: "font-size:12px;color:var(--neon)" }, c.op), h("div", { class: "ops" }, c.help.slice(0, 260))))));
    };
    search.addEventListener("input", () => {
      this.q = search.value;
      fill();
    });
    fill();
    this.rightBody.replaceChildren(
      ...(acts.length ? acts : [h("div", { class: "empty" }, "Nothing yet. When Claude works, each batch of steps shows up here live.")]),
      h("div", { class: "group-title", style: "margin-top:18px" }, `What Claude can do here (${commandList().length} commands)`),
      search,
      list,
    );
  }
}
