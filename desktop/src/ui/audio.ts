/** Audio workspace: read a song / voice / effect (loudness, tempo, key, sections, waveform, spectrogram) and mix tracks. */
import type { Studio } from "../../../src/app/studio";
import type { Asset } from "../../../src/core/model";
import { runCommand } from "../../../src/agent/api";
import * as ops from "../../../src/core/ops";
import { toast } from "../../../src/ui/dom";
import { vsd } from "../bridge";
import { b, h, ic, numRow, panel } from "./kit";

interface Report {
  file: string;
  duration: number;
  loudness: { integratedLUFS: number | null; rangeLU: number | null; truePeakDBFS: number | null };
  tempo?: { bpm: number; confidence: number };
  beats?: number[];
  sections: { start: number; end: number; energy: string }[];
  silences: { start: number; end: number }[];
  waveform?: string;
  spectrogram?: string;
  song?: { bpm: number; key: string; mood: { label: string }; sections: { start: number; end: number; label: string; energy: number }[]; downbeats: number[] } | { error: string };
}

const SEC_COLORS: Record<string, string> = { intro: "#3a6fe8", verse: "#1fa596", chorus: "#c050a6", drop: "#ff6b7d", bridge: "#d5832a", outro: "#7a6be6", break: "#5d6678", build: "#ffbf5e" };

export class AudioWorkspace {
  readonly lib: HTMLElement;
  readonly wave: HTMLElement;
  readonly mix: HTMLElement;
  private libBody: HTMLElement;
  private waveBody: HTMLElement;
  private mixBody: HTMLElement;
  private current: string | null = null;
  private reports = new Map<string, Report>();
  private busy = false;

  constructor(private readonly s: Studio) {
    const l = panel("Sounds in project", "alib", b(null, "folder", () => void this.inspectFile(), "sm ghost", "Read a sound file on this PC"));
    this.lib = l.el;
    this.libBody = l.body;
    const w = panel("Read the sound", "wave");
    this.wave = w.el;
    this.waveBody = w.body;
    const m = panel("Mixer", "mix");
    this.mix = m.el;
    this.mixBody = m.body;
    s.on("project", () => this.render());
    s.on("change", () => this.renderSoon());
    document.addEventListener("vsd:inspectAudio", (e) => {
      this.current = (e as CustomEvent<string>).detail;
      this.render();
      void this.analyse(this.current);
    });
    this.render();
  }

  private t = 0;
  private renderSoon() {
    window.clearTimeout(this.t);
    this.t = window.setTimeout(() => {
      this.renderLib();
      this.renderMix();
    }, 250);
  }

  render(): void {
    this.renderLib();
    this.renderWave();
    this.renderMix();
  }

  private sounds(): Asset[] {
    return this.s.project.assets.filter((a) => a.kind === "audio" || a.kind === "video");
  }

  private renderLib() {
    const list = this.sounds();
    this.libBody.replaceChildren(
      ...(list.length
        ? list.map((a) => {
            const r = this.reports.get(a.id);
            const row = h(
              "div",
              { class: `fitem ${this.current === a.id ? "sel" : ""}` },
              h("span", { style: `color:${a.kind === "audio" ? "var(--audio)" : "var(--video)"};display:grid` }, ic(a.kind === "audio" ? "wave" : "film", 15)),
              h("span", { class: "nm" }, a.name),
              h("span", { class: "sz" }, r?.tempo?.bpm ? `${Math.round(r.tempo.bpm)} bpm` : `${Math.round(a.duration)}s`),
            );
            row.addEventListener("click", () => {
              this.current = a.id;
              this.render();
              if (!this.reports.has(a.id)) void this.analyse(a.id);
            });
            return row;
          })
        : [h("div", { class: "empty" }, "No sounds yet. Import music, voices or video, or generate a bed in Library → Sound.")]),
    );
  }

  private async inspectFile() {
    if (!vsd) return;
    const info = await vsd.info();
    const [p] = await vsd.pick({ dir: info.dirs.music, multiple: false, title: "Read a sound file" });
    if (!p) return;
    this.busy = true;
    this.current = `path:${p}`;
    this.renderWave();
    const r = await runCommand(this.s, { op: "audio.inspect", path: p });
    this.busy = false;
    if (r.ok) this.reports.set(this.current, r.result as Report);
    else toast(r.error ?? "Could not read it", "error");
    this.renderWave();
  }

  private async analyse(id: string) {
    this.busy = true;
    this.renderWave();
    const r = await runCommand(this.s, { op: "audio.inspect", assetId: id });
    this.busy = false;
    if (r.ok) this.reports.set(id, r.result as Report);
    else toast(r.error ?? "Could not read it", "error");
    this.renderWave();
    this.renderLib();
  }

  private renderWave() {
    const id = this.current;
    if (!id) return this.waveBody.replaceChildren(h("div", { class: "empty" }, "Pick a sound on the left to see its loudness, tempo, key, sections, waveform and spectrogram."));
    const r = this.reports.get(id);
    if (!r) return this.waveBody.replaceChildren(h("div", { class: "empty" }, this.busy ? "Listening…" : "Not read yet."));
    const song = r.song && !("error" in r.song) ? r.song : null;
    const stat = (v: string, l: string) => h("div", { class: "stat" }, h("b", {}, v), h("span", {}, l));
    const dur = r.duration || 1;
    const secs = song?.sections?.length ? song.sections.map((s) => ({ start: s.start, end: s.end, label: s.label })) : r.sections.map((s) => ({ start: s.start, end: s.end, label: s.energy }));
    const bar = h(
      "div",
      { class: "secbar" },
      ...secs.map((s) => h("div", { style: `width:${((s.end - s.start) / dur) * 100}%;background:${SEC_COLORS[s.label.replace(/\d+$/, "")] ?? (s.label === "loud" ? "#c050a6" : s.label === "medium" ? "#1fa596" : "#3a4558")}`, title: `${s.label} ${s.start.toFixed(1)}–${s.end.toFixed(1)}s` }, s.label)),
    );
    const isAsset = !id.startsWith("path:");
    const actions = h(
      "div",
      { class: "row wrap", style: "margin-top:10px" },
      isAsset ? b("Beats → markers", "marker", () => void this.cmd({ op: "audio.beats", assetId: id }, "Beat markers added"), "sm") : null,
      isAsset && song ? b("Sections → markers", "marker", () => this.s.mutate("song markers", (p) => (p.markers = song.sections.map((x) => ({ t: x.start, label: x.label })))), "sm") : null,
      isAsset ? b("Cut my clips to this", "scissors", () => void this.cmd({ op: "edit.beatCut", musicId: id, style: "cinematic", replace: false }, "Cut to the music"), "sm") : null,
      isAsset ? b("Lyrics (on this PC)", "text", () => void this.cmd({ op: "audio.analyze", assetId: id, lyrics: true }, "Lyrics transcribed"), "sm") : null,
      isAsset ? b("Add at playhead", "plus", () => this.s.mutate("add clip", (p) => ops.addClip(p, { assetId: id, start: this.s.time })), "sm") : null,
      !isAsset ? b("Import this file", "download", () => void this.cmd({ op: "media.importPath", path: id.slice(5), add: false }, "Imported"), "sm") : null,
      b("Read again", "loop", () => (isAsset ? void this.analyse(id) : undefined), "sm ghost"),
    );
    const kids: (HTMLElement | null)[] = [
      h("div", { class: "row", style: "margin-bottom:10px" }, h("b", { style: "font-size:14px" }, r.file), h("span", { class: "sp" }), h("span", { class: "muted mono" }, `${Math.floor(dur / 60)}:${String(Math.round(dur % 60)).padStart(2, "0")}`)),
      h(
        "div",
        { class: "stats" },
        stat(r.loudness.integratedLUFS !== null ? `${r.loudness.integratedLUFS}` : "–", "LUFS integrated"),
        stat(r.loudness.truePeakDBFS !== null ? `${r.loudness.truePeakDBFS}` : "–", "True peak dBFS"),
        stat(song ? `${Math.round(song.bpm)}` : r.tempo ? `${Math.round(r.tempo.bpm)}` : "–", "Tempo BPM"),
        stat(song ? song.key : r.loudness.rangeLU !== null ? `${r.loudness.rangeLU} LU` : "–", song ? `Key · ${song.mood.label}` : "Loudness range"),
      ),
      h("div", { class: "group-title" }, song ? "Song sections" : "Energy"),
      bar,
      r.waveform ? h("div", { class: "group-title" }, "Waveform") : null,
      r.waveform ? h("img", { class: "aw-canvas", src: r.waveform, alt: "Waveform" }) : null,
      r.spectrogram ? h("div", { class: "group-title" }, "Spectrogram (frequency over time)") : null,
      r.spectrogram ? h("img", { class: "aw-canvas", src: r.spectrogram, alt: "Spectrogram" }) : null,
      r.silences.length ? h("div", { class: "hint", style: "margin-top:8px" }, `Silences: ${r.silences.slice(0, 12).map((x) => `${x.start.toFixed(1)}–${x.end.toFixed(1)}s`).join(", ")}${r.silences.length > 12 ? "…" : ""}`) : null,
      actions,
    ];
    this.waveBody.replaceChildren(...(kids.filter(Boolean) as HTMLElement[]));
  }

  private async cmd(c: Record<string, unknown>, ok: string) {
    this.s.setStatus(`Working: ${c.op}…`);
    const r = await runCommand(this.s, c as never);
    toast(r.ok ? ok : r.error ?? "Failed", r.ok ? "ok" : "error");
    this.s.setStatus("");
  }

  private renderMix() {
    const tracks = this.s.project.tracks;
    this.mixBody.replaceChildren(
      ...tracks.map((t) => {
        const mute = b(t.muted ? "Muted" : "Mute", null, () => this.s.mutate("mute", (p) => (ops.getTrack(p, t.id).muted = !t.muted)), `sm ${t.muted ? "on" : ""}`);
        return h(
          "div",
          { class: "strip" },
          h("b", { style: "font-size:12.5px" }, t.name, h("span", { class: "faint", style: "font-weight:400" }, `  ${t.kind} · ${t.clips.length} clips`)),
          mute,
          h("span"),
          h(
            "div",
            { style: "grid-column:1/-1" },
            numRow({ label: "Track volume", min: 0, max: 2, step: 0.01, value: t.volume ?? 1, fmt: (v) => `${v > 0 ? (20 * Math.log10(v)).toFixed(1) : "-∞"} dB`, onInput: (v) => this.s.mutate("track volume", (p) => (ops.getTrack(p, t.id).volume = v)) }),
          ),
        );
      }),
      h("div", { class: "group-title" }, "Master"),
      h("div", { class: "row wrap" }, b("Duck music under voices", null, () => void this.cmd({ op: "audio.duck" }, "Ducking added"), "sm"), b("Auto sound design", null, () => void this.cmd({ op: "sfx.auto", style: "cinematic" }, "Sound design added"), "sm")),
      h("div", { class: "hint", style: "margin-top:8px" }, "Renders are mastered by the engine; use Clean & master on a clip (inspector) to hit about −14 LUFS for Instagram and YouTube."),
    );
  }
}
