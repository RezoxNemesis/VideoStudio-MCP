/** VolTune voice studio inside VideoStudio. */
import type { Studio } from "../app/studio";
import { fillVoiceSelect, loadCustoms, openVoiceLab, scriptTips } from "../voice/voicelab";
import { DEFAULT_VOICE } from "../voice/engine/voices";
import type { CustomVoice } from "../voice/engine/voicebank";
import * as ops from "../core/ops";
import { saveBlob } from "../native/storage";
import { btn, h, slider, toast } from "./dom";

const DRAFT = "vs.voice.draft";

export class VoiceView {
  readonly el: HTMLElement;
  private text: HTMLTextAreaElement;
  private voice: HTMLSelectElement;
  private status: HTMLElement;
  private bar: HTMLElement;
  private result: HTMLElement;
  private mode: "speak" | "dialogue" = "dialogue";
  private speed = 1;
  private busy = false;

  constructor(private readonly s: Studio) {
    this.text = h("textarea", { class: "script", rows: 9, placeholder: "Maya (excited): We made it!\nJon: Shh… (whispering) they'll hear us.\n[pause 1s]\nNarrator: Night fell over the city.", "aria-label": "Script" }) as HTMLTextAreaElement;
    try {
      this.text.value = localStorage.getItem(DRAFT) ?? "";
    } catch {
      /* storage blocked */
    }
    this.text.addEventListener("input", () => {
      try {
        localStorage.setItem(DRAFT, this.text.value);
      } catch {
        /* ignore */
      }
    });
    this.voice = h("select", { "aria-label": "Voice" }) as HTMLSelectElement;
    fillVoiceSelect(this.voice, DEFAULT_VOICE, loadCustoms());
    this.status = h("p", { class: "muted small" }, s.voice.supported ? "Voices run on this phone. The first use downloads the voice model once (~90–330 MB)." : "Speech needs WebAssembly, which this device lacks.");
    this.bar = h("div", { class: "progress" }, h("i", {}));
    this.result = h("div", { class: "voice-result" });
    const modeSel = h("div", { class: "seg" });
    const drawMode = () =>
      modeSel.replaceChildren(
        ...(["dialogue", "speak"] as const).map((m) => h("button", { class: `seg-btn ${this.mode === m ? "on" : ""}`, type: "button", onclick: () => ((this.mode = m), drawMode()) }, m === "speak" ? "Narration" : "Dialogue")),
      );
    drawMode();
    const tips = h("details", { class: "tips-box" }, h("summary", {}, "Script tips"), scriptTips());
    this.el = h(
      "div",
      { class: "view voice" },
      h("div", { class: "panel" },
        h("h2", {}, "Voices · VolTune"),
        modeSel,
        this.text,
        h("label", { class: "frow" }, h("span", { class: "lbl" }, "Voice"), this.voice),
        slider("Pace", 0.6, 1.6, 0.01, this.speed, (v) => (this.speed = v), { fmt: (v) => `${v.toFixed(2)}×` }).el,
        h("div", { class: "row wrap" },
          btn("Generate", "mic", () => void this.generate(), "primary"),
          btn("Voice Lab", "sliders", () => this.lab()),
          btn("Stop", "close", () => s.voice.cancel(), "ghost"),
        ),
        this.bar,
        this.status,
        this.result,
        tips,
      ),
    );
    s.voice.onProgress = (p) => {
      if (p.total > 0) {
        this.setBar(p.loaded / p.total);
        this.status.textContent = `Downloading voice model… ${Math.round((p.loaded / p.total) * 100)}%`;
      }
    };
  }

  private setBar(f: number) {
    (this.bar.firstChild as HTMLElement).style.width = `${Math.round(Math.max(0, Math.min(1, f)) * 100)}%`;
  }

  private lab() {
    openVoiceLab({
      preview: async (text: string, v: CustomVoice) => {
        const { result } = await this.s.voice.speak({ text, voice: v.id, mode: "speak" }).then((r) => ({ result: r }));
        const a = new Audio(URL.createObjectURL(result.wav));
        await a.play();
      },
      onChange: (list) => fillVoiceSelect(this.voice, this.voice.value, list),
      toast: (m, tone) => toast(m, tone === "error" ? "error" : "info"),
    });
  }

  private async generate() {
    if (this.busy) return;
    const text = this.text.value.trim();
    if (!text) return toast("Write a line or a script first", "error");
    this.busy = true;
    this.setBar(0);
    this.status.textContent = "Preparing voices…";
    try {
      const { result, asset } = await this.s.speak({ text, mode: this.mode, voice: this.voice.value, speed: this.speed }, (done, total) => {
        this.setBar(done / total);
        this.status.textContent = `Speaking… ${done}/${total} lines`;
      });
      this.status.textContent = `Ready · ${result.seconds.toFixed(1)}s · ${this.s.voice.device ?? ""}`;
      this.setBar(1);
      const url = URL.createObjectURL(result.wav);
      const audio = h("audio", { controls: true, src: url });
      const cast = Object.entries(result.cast);
      this.result.replaceChildren(
        audio,
        ...(cast.length ? [h("p", { class: "muted small" }, `Cast: ${cast.map(([n, v]) => `${n} → ${v}`).join(" · ")}`)] : []),
        h("div", { class: "row wrap" },
          btn("Add at playhead", "plus", () => {
            const c = this.s.mutate("voice to timeline", (p) => ops.addClip(p, { assetId: asset.id, start: this.s.time }));
            this.s.select(c.id);
            toast("Voice added to the timeline", "ok");
          }, "primary"),
          btn("Save WAV", "download", async () => {
            const r = await saveBlob(result.wav, `${asset.name.replace(/[^\w\- .]+/g, "")}`, "Music");
            toast(`Saved ${r.path}`, "ok");
          }),
        ),
        h("p", { class: "hint" }, "Lip-sync data is stored with this voice: in Animate, use the agent op anim.lipsync or draw mouth shapes and let it time them."),
      );
    } catch (e) {
      this.status.textContent = e instanceof Error ? e.message : String(e);
      toast(this.status.textContent, "error", 5000);
    } finally {
      this.busy = false;
    }
  }
}
