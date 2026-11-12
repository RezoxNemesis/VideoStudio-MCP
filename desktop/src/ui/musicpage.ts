/**
 * Music workspace: compose a piece from a brief, pick instruments from the bank (261 instruments, 13 drum kits),
 * edit notes in a piano roll, mix the tracks, and send the result to the timeline.
 */
import type { Studio } from "../../../src/app/studio";
import { runQuiet } from "../../../src/agent/api";
import { MOODS, STYLES } from "../../../src/music/compose";
import { barBeats, beatSec, keyName, pitchName, songBeats, type Note, type Song, type Track } from "../../../src/music/model";
import { exportMidi, importMidi } from "../../../src/music/midi";
import { listInstruments, musicProblem, renderSong, type Instrument, type Rendered } from "../../../src/music/synth";
import { renderWithVocals } from "../../../src/music/vocal";
import { openVoice } from "../../../src/voice/open_client";
import { SCALES, TAALS, chordName } from "../../../src/music/theory";
import { b, h, ic, modal, selectEl } from "./kit";
import "../styles/studio2.css";

const GUT = 46, TOP = 30, ROW = 11;
const KEYS = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"];
const DRUM: Record<number, string> = { 35: "Kick 2", 36: "Kick", 37: "Rim", 38: "Snare", 39: "Clap", 40: "Snare 2", 41: "Tom low", 42: "Hat", 43: "Tom", 44: "Hat pedal", 45: "Tom mid", 46: "Hat open", 47: "Tom", 48: "Tom high", 49: "Crash", 50: "Tom", 51: "Ride", 54: "Tamb", 56: "Cowbell", 60: "Bongo hi", 61: "Bongo lo", 62: "Conga mute", 63: "Conga hi", 64: "Conga lo", 69: "Cabasa", 70: "Maracas", 75: "Claves", 76: "Block hi", 77: "Block lo", 80: "Triangle", 81: "Triangle open", 82: "Shaker" };

export class MusicPage {
  readonly el: HTMLElement;
  private songId: string | null = null;
  private trackId: string | null = null;
  private pxBeat = 26;
  private beat0 = 0;
  private topPitch = 84;
  private grid = 0.25;
  private lastLen = 1;
  private sel: Note | null = null;
  private cv = h("canvas", { class: "mu-roll", tabindex: "0" });
  private tracksEl = h("div", { class: "mu-tracks" });
  private infoEl = h("div", { class: "mu-info" });
  private songsEl = h("div", { class: "mu-songs" });
  private posEl = h("span", { class: "mu-pos" }, "0:00");
  private playBtn: HTMLButtonElement;
  private statusEl = h("span", { class: "mu-status" });
  private cache: { key: string; audio: Rendered } | null = null;
  private ctx: AudioContext | null = null;
  private src: AudioBufferSourceNode | null = null;
  private playT0 = 0;
  private playBeat0 = 0;
  private cursor = 0;
  private raf = 0;
  private on = false;
  private drag: { n: Note; mode: "move" | "size"; b0: number; p0: number; x0: number; y0: number; moved: boolean; fresh: boolean } | null = null;
  private instruments: Instrument[] = [];
  private form: Record<string, string> = { style: "bollywood_romantic", mood: "romantic", key: "", scale: "", bpm: "", seconds: "60", lead: "", taal: "", lyrics: "", voice: "hf_alpha" };

  constructor(private s: Studio) {
    this.playBtn = b("Play", "play", () => void this.toggle(), "pri", "Play from the cursor (Space)");
    const compose = this.composeCard();
    const left = h("div", { class: "mu-left" }, compose, h("div", { class: "mu-h" }, "Songs in this project"), this.songsEl);
    const bar = h(
      "div",
      { class: "mu-bar" },
      this.playBtn,
      b(null, "start", () => this.seek(0), "", "Back to the start"),
      this.posEl,
      h("span", { class: "sp" }),
      this.statusEl,
      selectEl([["1", "Grid 1"], ["0.5", "Grid 1/2"], ["0.25", "Grid 1/4"], ["0.125", "Grid 1/8"], ["0.3333", "Triplets"]] as [string, string][], "0.25", (v) => (this.grid = Number(v))),
      b("Add track", "plus", () => void this.addTrack(), "", "Add an instrument track"),
      b("Import MIDI", "folderOpen", () => this.importMid(), "", "Bring a .mid file in as a song"),
      b("Export MIDI", "export", () => this.exportMid(), "", "Save this song as a .mid file"),
      b("To timeline", "film", () => void this.toTimeline(), "pri", "Render this song and place it on the timeline"),
    );
    const mid = h("div", { class: "mu-mid" }, bar, this.tracksEl, h("div", { class: "mu-rollwrap" }, this.cv));
    this.el = h("div", { class: "mu" }, left, mid, h("div", { class: "mu-right" }, this.infoEl));
    this.bindRoll();
    s.on("change", () => this.on && this.refresh());
    s.on("project", () => {
      this.songId = null;
      this.cache = null;
      if (this.on) this.refresh();
    });
    new ResizeObserver(() => this.draw()).observe(this.cv);
  }

  shown(on: boolean): void {
    this.on = on;
    if (on) {
      this.refresh();
      if (!this.instruments.length) void listInstruments().then((x) => (this.instruments = x), () => this.say(musicProblem() || "The instrument bank could not be loaded."));
    } else this.stop();
  }

  /* ------------------------------------------------------------ data */

  private song(): Song | null {
    const list = this.s.project.songs ?? [];
    const sg = list.find((x) => x.id === this.songId) ?? list[list.length - 1] ?? null;
    this.songId = sg?.id ?? null;
    return sg;
  }
  private track(): Track | null {
    const sg = this.song();
    if (!sg) return null;
    const t = sg.tracks.find((x) => x.id === this.trackId) ?? sg.tracks[0] ?? null;
    this.trackId = t?.id ?? null;
    return t;
  }
  private say(t: string): void {
    this.statusEl.textContent = t;
  }
  private async run(cmd: Record<string, unknown>): Promise<unknown> {
    const [r] = await runQuiet(this.s, [cmd as never]);
    if (r && !r.ok) {
      this.say(String(r.error ?? "That did not work."));
      return null;
    }
    this.say("");
    return r?.result ?? null;
  }

  /* ------------------------------------------------------------ compose */

  private composeCard(): HTMLElement {
    const f = this.form;
    const sel = (k: string, opts: [string, string][]) => {
      const e = selectEl(opts, f[k] ?? "", (v) => (f[k] = v));
      return e;
    };
    const txt = (k: string, ph: string, list?: string) => {
      const e = h("input", { class: "in", placeholder: ph, value: f[k] ?? "", list });
      e.addEventListener("input", () => (f[k] = e.value));
      return e;
    };
    const row = (label: string, c: HTMLElement) => h("label", { class: "mu-row" }, h("span", {}, label), c);
    const styleSel = sel("style", Object.keys(STYLES).map((k) => [k, k.replace(/_/g, " ")]));
    const about = h("div", { class: "mu-about" }, STYLES[f.style!]?.about ?? "");
    styleSel.addEventListener("change", () => (about.textContent = STYLES[f.style!]?.about ?? ""));
    const ragas = Object.entries(SCALES);
    const dl = h("datalist", { id: "mu-ins" });
    void listInstruments().then((all) => dl.append(...all.filter((i) => !i.drums).map((i) => h("option", { value: i.name }))), () => undefined);
    const go = async (vary: boolean) => {
      const cmd: Record<string, unknown> = { op: "music.generate", style: f.style, toTimeline: false };
      if (f.mood) cmd.mood = f.mood;
      if (f.key) cmd.key = f.key;
      if (f.scale) cmd.scale = f.scale;
      if (f.taal) cmd.taal = f.taal;
      if (Number(f.bpm)) cmd.bpm = Number(f.bpm);
      if (Number(f.seconds)) cmd.seconds = Number(f.seconds);
      if (f.lead) cmd.lead = f.lead;
      if ((f.lyrics ?? "").trim()) {
        cmd.lyrics = f.lyrics;
        cmd.voice = f.voice;
        delete cmd.seconds;
      }
      if (vary && this.songId) cmd.songId = this.songId;
      this.say((f.lyrics ?? "").trim() ? "Reading the lyrics and composing…" : "Composing…");
      const r = (await this.run(cmd)) as { id?: string } | null;
      if (r?.id) {
        this.songId = r.id;
        this.trackId = null;
        this.cache = null;
        this.refresh();
        void this.play(0);
      }
    };
    return h(
      "div",
      { class: "mu-card" },
      h("div", { class: "mu-h" }, "Compose"),
      row("Style", styleSel),
      about,
      row("Mood", sel("mood", [["", "any"], ...Object.keys(MOODS).map((k) => [k, k] as [string, string])])),
      row("Key", sel("key", [["", "any"], ...KEYS.map((k) => [k, k] as [string, string])])),
      row("Scale / raga", sel("scale", [["", "style's choice"], ...ragas.map(([k, v]) => [k, `${k.replace(/_/g, " ")}${v.family === "raga" ? " (raga)" : ""}`] as [string, string])])),
      row("Taal", sel("taal", [["", "style's choice"], ...Object.entries(TAALS).map(([k, v]) => [k, `${k} · ${v.beats}`] as [string, string])])),
      row("Tempo", txt("bpm", "auto")),
      row("Seconds", txt("seconds", "60")),
      row("Lead", txt("lead", "style's choice", "mu-ins")),
      dl,
      h("div", { class: "mu-h" }, "Lyrics (optional: a sung song)"),
      (() => {
        const ta = h("textarea", { class: "in mu-lyrics", rows: "6", placeholder: "One line of the song per line.\nA blank line starts a new part; [chorus] names it.\nHindi, Hinglish or English." });
        ta.value = f.lyrics ?? "";
        ta.addEventListener("input", () => (f.lyrics = ta.value));
        return ta;
      })(),
      row("Singer", sel("voice", [["hf_alpha", "Hindi · woman (alpha)"], ["hf_beta", "Hindi · woman (beta)"], ["hm_omega", "Hindi · man (omega)"], ["hm_psi", "Hindi · man (psi)"], ["af_heart", "English · woman"], ["am_michael", "English · man"]])),
      h("div", { class: "mu-btns" }, b("Compose", "spark", () => void go(false), "pri", "Write a new piece"), b("Another take", "redo", () => void go(true), "", "Rewrite the selected song with the same settings")),
    );
  }

  /* ------------------------------------------------------------ refresh */

  private refresh(): void {
    const sg = this.song();
    const list = this.s.project.songs ?? [];
    this.songsEl.replaceChildren(
      ...(list.length ? list : []).map((x) => {
        const row = h("div", { class: `mu-song ${x.id === this.songId ? "on" : ""}` }, h("b", {}, x.name), h("span", {}, `${x.bpm} bpm · ${keyName(x.key)} ${x.scale.replace(/_/g, " ")} · ${x.bars} bars`));
        row.addEventListener("click", () => {
          this.stop();
          this.songId = x.id;
          this.trackId = null;
          this.refresh();
        });
        row.append(b(null, "trash", () => void this.run({ op: "music.delete", songId: x.id }).then(() => this.refresh()), "", "Remove this song"));
        return row;
      }),
    );
    if (!list.length) this.songsEl.append(h("p", { class: "hint" }, "No songs yet. Pick a style and press Compose, or import a MIDI file."));
    this.tracksEl.replaceChildren();
    this.infoEl.replaceChildren();
    if (!sg) {
      this.draw();
      return;
    }
    const cur = this.track();
    for (const t of sg.tracks) {
      const slider = (k: "volume" | "pan" | "reverb", min: number, max: number, title: string) => {
        const e = h("input", { type: "range", min: String(min), max: String(max), step: "0.01", value: String(t[k]), title, class: "mu-sl" });
        e.addEventListener("change", () => void this.run({ op: "music.track", songId: sg.id, id: t.id, [k]: Number(e.value) }));
        e.addEventListener("click", (ev) => ev.stopPropagation());
        return e;
      };
      const row = h(
        "div",
        { class: `mu-track ${t.id === cur?.id ? "on" : ""} ${t.mute ? "muted" : ""}` },
        h("span", { class: "mu-tn", title: t.name }, t.name),
        t.kind === "vocal" ? h("span", { class: "mu-voice" }, `Sung by ${t.voice ?? "hf_alpha"}`) : b(t.instrument ?? `Program ${t.program}`, null, () => void this.pickInstrument(sg, t), "mu-ins", "Change the instrument"),
        b("M", null, () => void this.run({ op: "music.track", songId: sg.id, id: t.id, mute: !t.mute }), t.mute ? "on" : "", "Mute"),
        b("S", null, () => void this.run({ op: "music.track", songId: sg.id, id: t.id, solo: !t.solo }), t.solo ? "on" : "", "Solo"),
        slider("volume", 0, 1, "Volume"),
        slider("pan", -1, 1, "Left / right"),
        slider("reverb", 0, 1, "Reverb"),
        h("span", { class: "mu-cnt" }, String(t.notes.length)),
        b(null, "trash", () => void this.run({ op: "music.track", songId: sg.id, id: t.id, remove: true }), "", "Remove this track"),
      );
      row.addEventListener("click", () => {
        this.trackId = t.id;
        this.sel = null;
        const ps = t.notes.map((n) => n[2]);
        if (ps.length) this.topPitch = Math.min(127, Math.max(...ps) + 6);
        this.refresh();
      });
      this.tracksEl.append(row);
    }
    const bb = barBeats(sg);
    const num = (label: string, key: "bpm" | "swing" | "humanize" | "room", step: string) => {
      const e = h("input", { class: "in", type: "number", step, value: String(sg[key] ?? 0) });
      e.addEventListener("change", () => void this.run({ op: "music.edit", songId: sg.id, [key]: Number(e.value) }));
      return h("label", { class: "mu-row" }, h("span", {}, label), e);
    };
    const keySel = selectEl(KEYS.map((k) => [k, k] as [string, string]), KEYS[sg.key] ?? "C", (v) => void this.run({ op: "music.edit", songId: sg.id, key: v }));
    this.infoEl.append(
      h("div", { class: "mu-h" }, sg.name),
      h("p", { class: "hint" }, `${sg.meter[0]}/${sg.meter[1]} · ${sg.scale.replace(/_/g, " ")}${SCALES[sg.scale]?.about ? " — " + SCALES[sg.scale]!.about : ""}`),
      num("Tempo", "bpm", "1"),
      h("label", { class: "mu-row" }, h("span", {}, "Key (moves the music)"), keySel),
      num("Swing", "swing", "0.02"),
      num("Human feel", "humanize", "0.05"),
      num("Room", "room", "0.05"),
      ...this.mixCard(sg),
      h("div", { class: "mu-h" }, "Sections"),
      ...sg.sections.map((x) => {
        const r = h("div", { class: "mu-sec" }, h("b", {}, x.name), h("span", {}, `bars ${x.bar + 1}–${x.bar + x.bars}`), h("i", { style: `width:${Math.round(x.energy * 100)}%` }));
        r.addEventListener("click", () => this.seek(x.bar * bb));
        return r;
      }),
      h("div", { class: "mu-h" }, "Chords"),
      h("div", { class: "mu-chords" }, ...sg.chords.slice(0, 96).map((c) => h("span", { title: `beat ${c.at}` }, chordName(c)))),
      h("p", { class: "hint" }, "Piano roll: click to add a note, drag to move, drag its end to resize, right-click to delete. Wheel scrolls pitch, Shift+wheel scrolls time, Ctrl+wheel zooms."),
    );
    this.draw();
  }

  /* ------------------------------------------------------------ piano roll */

  private draw(): void {
    const cv = this.cv;
    const W = cv.clientWidth, H = cv.clientHeight;
    if (!W || !H) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) {
      cv.width = Math.round(W * dpr);
      cv.height = Math.round(H * dpr);
    }
    const g = cv.getContext("2d")!;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = "#0b0e14";
    g.fillRect(0, 0, W, H);
    const sg = this.song(), tr = this.track();
    if (!sg) {
      g.fillStyle = "#8a94a6";
      g.font = "13px system-ui";
      g.fillText("Compose a piece on the left to begin.", GUT + 16, TOP + 30);
      return;
    }
    const bb = barBeats(sg);
    const X = (beat: number) => GUT + (beat - this.beat0) * this.pxBeat;
    const Y = (p: number) => TOP + (this.topPitch - p) * ROW;
    const rows = Math.ceil((H - TOP) / ROW);
    for (let i = 0; i <= rows; i++) {
      const p = this.topPitch - i;
      const black = [1, 3, 6, 8, 10].includes(((p % 12) + 12) % 12);
      g.fillStyle = tr?.kind === "drums" ? (i % 2 ? "#0e121a" : "#0b0e14") : black ? "#0a0c11" : "#10141c";
      g.fillRect(GUT, TOP + i * ROW, W - GUT, ROW - 1);
    }
    const endBeat = Math.max(songBeats(sg), sg.bars * bb);
    for (let beat = Math.floor(this.beat0); beat <= this.beat0 + (W - GUT) / this.pxBeat; beat++) {
      if (beat < 0) continue;
      const bar = Math.abs(beat / bb - Math.round(beat / bb)) < 1e-6;
      if (!bar && this.pxBeat < 9) continue;
      g.fillStyle = bar ? "rgba(255,255,255,0.16)" : "rgba(255,255,255,0.05)";
      g.fillRect(Math.round(X(beat)), TOP, 1, H - TOP);
      if (bar) {
        g.fillStyle = "#8a94a6";
        g.font = "10px system-ui";
        g.fillText(String(Math.round(beat / bb) + 1), X(beat) + 3, TOP - 4);
      }
    }
    g.fillStyle = "rgba(0,0,0,0.35)";
    if (X(endBeat) < W) g.fillRect(X(endBeat), TOP, W - X(endBeat), H - TOP);
    // sections and chords along the top
    for (const s of sg.sections) {
      const x0 = X(s.bar * bb), x1 = X((s.bar + s.bars) * bb);
      if (x1 < GUT || x0 > W) continue;
      g.fillStyle = `rgba(62,230,196,${0.08 + s.energy * 0.22})`;
      g.fillRect(Math.max(GUT, x0), 0, Math.min(W, x1) - Math.max(GUT, x0) - 1, 12);
      g.fillStyle = "#cfe9e2";
      g.font = "9px system-ui";
      if (x0 > GUT - 30) g.fillText(s.name, Math.max(GUT + 2, x0 + 3), 9);
    }
    // the other tracks, faintly
    for (const t of sg.tracks) {
      if (t === tr || t.kind !== tr?.kind || t.mute) continue;
      g.fillStyle = "rgba(138,125,255,0.16)";
      for (const n of t.notes) {
        const x = X(n[0]);
        if (x > W || x + n[1] * this.pxBeat < GUT) continue;
        g.fillRect(x, Y(n[2]), Math.max(2, n[1] * this.pxBeat - 1), ROW - 1);
      }
    }
    if (tr)
      for (const n of tr.notes) {
        const x = X(n[0]), w = Math.max(3, n[1] * this.pxBeat - 1);
        if (x > W || x + w < GUT) continue;
        const y = Y(n[2]);
        if (y < TOP - ROW || y > H) continue;
        const a = 0.45 + (n[3] / 127) * 0.55;
        g.fillStyle = n === this.sel ? "#ffbf5e" : tr.kind === "vocal" ? `rgba(255,111,174,${a})` : `rgba(62,230,196,${a})`;
        g.fillRect(Math.max(GUT, x), y, w - Math.max(0, GUT - x), ROW - 1);
        if (tr.kind === "vocal" && this.pxBeat > 20 && x > GUT) {
          const syl = tr.lyrics?.[tr.notes.indexOf(n)];
          if (syl) {
            g.fillStyle = "#e8edf4";
            g.font = "9px system-ui";
            g.fillText(syl.replace(/[ˈˌ]/g, ""), x + 1, y - 2);
          }
        }
      }
    // keys
    g.fillStyle = "#0b0e14";
    g.fillRect(0, 0, GUT, H);
    g.font = "9px system-ui";
    for (let i = 0; i <= rows; i++) {
      const p = this.topPitch - i;
      const pc = ((p % 12) + 12) % 12;
      const y = TOP + i * ROW;
      if (tr?.kind === "drums") {
        g.fillStyle = "#8a94a6";
        if (DRUM[p]) g.fillText(DRUM[p]!.slice(0, 9), 2, y + 8.5);
      } else {
        g.fillStyle = [1, 3, 6, 8, 10].includes(pc) ? "#1b202b" : "#d8dee9";
        g.fillRect(0, y, GUT - 3, ROW - 1);
        if (pc === 0) {
          g.fillStyle = "#0b0e14";
          g.fillText(pitchName(p), 3, y + 8.5);
        }
      }
    }
    // cursor
    const cx = X(this.cursor);
    if (cx >= GUT && cx <= W) {
      g.fillStyle = "#ff6fae";
      g.fillRect(cx, 0, 1.5, H);
    }
  }

  private at(e: PointerEvent | WheelEvent | MouseEvent): { beat: number; pitch: number; x: number; y: number } {
    const r = this.cv.getBoundingClientRect();
    const x = e.clientX - r.left, y = e.clientY - r.top;
    return { beat: this.beat0 + (x - GUT) / this.pxBeat, pitch: this.topPitch - Math.floor((y - TOP) / ROW), x, y };
  }
  private hit(beat: number, pitch: number): Note | null {
    const tr = this.track();
    if (!tr) return null;
    for (let i = tr.notes.length - 1; i >= 0; i--) {
      const n = tr.notes[i]!;
      if (n[2] === pitch && beat >= n[0] && beat <= n[0] + n[1]) return n;
    }
    return null;
  }
  private snap = (v: number) => Math.max(0, Math.round(v / this.grid) * this.grid);

  private bindRoll(): void {
    const cv = this.cv;
    cv.addEventListener("contextmenu", (e) => e.preventDefault());
    cv.addEventListener("pointerdown", (e) => {
      const sg = this.song(), tr = this.track();
      if (!sg || !tr) return;
      cv.focus();
      const p = this.at(e);
      if (p.y < TOP || p.x < GUT) {
        if (p.x >= GUT) this.seek(Math.max(0, p.beat));
        else void this.audition(tr, p.pitch);
        return;
      }
      const n = this.hit(p.beat, p.pitch);
      if (e.button === 2 || e.altKey) {
        if (n) this.s.mutate("delete note", () => void tr.notes.splice(tr.notes.indexOf(n), 1));
        this.sel = null;
        this.cache = null;
        return;
      }
      cv.setPointerCapture(e.pointerId);
      if (n) {
        const edge = (n[0] + n[1] - p.beat) * this.pxBeat < 6;
        this.sel = n;
        this.drag = { n, mode: edge ? "size" : "move", b0: n[0], p0: n[2], x0: p.beat, y0: p.pitch, moved: false, fresh: false };
      } else {
        const note: Note = [Math.floor(p.beat / this.grid) * this.grid, this.lastLen, Math.max(0, Math.min(127, p.pitch)), 96];
        this.s.mutate("add note", () => {
          tr.notes.push(note);
          tr.notes.sort((x, y) => x[0] - y[0]);
        });
        this.sel = note;
        this.drag = { n: note, mode: "size", b0: note[0], p0: note[2], x0: p.beat, y0: p.pitch, moved: false, fresh: true };
        void this.audition(tr, note[2]);
      }
      this.cache = null;
      this.draw();
    });
    cv.addEventListener("pointermove", (e) => {
      const d = this.drag;
      if (!d) return;
      const p = this.at(e);
      if (d.mode === "move") {
        const nb = this.snap(d.b0 + (p.beat - d.x0)), np = Math.max(0, Math.min(127, d.p0 + (p.pitch - d.y0)));
        if (np !== d.n[2]) {
          const tr = this.track();
          if (tr) void this.audition(tr, np);
        }
        d.n[0] = nb;
        d.n[2] = np;
      } else d.n[1] = Math.max(this.grid, this.snap(p.beat - d.n[0] + (d.fresh ? this.grid * 0.5 : 0)) || this.grid);
      d.moved = true;
      this.draw();
    });
    const up = () => {
      const d = this.drag;
      this.drag = null;
      if (!d) return;
      this.lastLen = d.n[1];
      // one history step for the whole gesture
      this.s.mutate(d.fresh ? "add note" : "edit note", () => {
        const tr = this.track();
        tr?.notes.sort((x, y) => x[0] - y[0]);
      });
    };
    cv.addEventListener("pointerup", up);
    cv.addEventListener("pointercancel", up);
    cv.addEventListener("wheel", (e) => {
      e.preventDefault();
      if (e.ctrlKey) {
        const p = this.at(e);
        const z = Math.max(4, Math.min(120, this.pxBeat * (e.deltaY < 0 ? 1.15 : 1 / 1.15)));
        this.beat0 = Math.max(0, p.beat - (p.x - GUT) / z);
        this.pxBeat = z;
      } else if (e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) this.beat0 = Math.max(0, this.beat0 + ((e.deltaX || e.deltaY) / this.pxBeat) * 0.6);
      else this.topPitch = Math.max(20, Math.min(127, this.topPitch - Math.sign(e.deltaY) * 3));
      this.draw();
    }, { passive: false });
    cv.addEventListener("keydown", (e) => {
      const tr = this.track();
      if (e.key === " ") {
        e.preventDefault();
        void this.toggle();
      } else if ((e.key === "Delete" || e.key === "Backspace") && this.sel && tr) {
        const n = this.sel;
        this.s.mutate("delete note", () => void tr.notes.splice(tr.notes.indexOf(n), 1));
        this.sel = null;
        this.cache = null;
      } else if ((e.key === "ArrowUp" || e.key === "ArrowDown") && this.sel) {
        e.preventDefault();
        const n = this.sel;
        this.s.mutate("move note", () => (n[2] = Math.max(0, Math.min(127, n[2] + (e.key === "ArrowUp" ? 1 : -1) * (e.shiftKey ? 12 : 1)))));
        this.cache = null;
      }
    });
  }

  /* ------------------------------------------------------------ sound */

  private audio(): AudioContext {
    return (this.ctx ??= new AudioContext({ sampleRate: 48000 }));
  }
  private playBuffer(a: Rendered, fromSec: number): AudioBufferSourceNode {
    const ctx = this.audio();
    const buf = ctx.createBuffer(2, a.left.length, a.sampleRate);
    buf.copyToChannel(a.left as Float32Array<ArrayBuffer>, 0);
    buf.copyToChannel(a.right as Float32Array<ArrayBuffer>, 1);
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(ctx.destination);
    src.start(0, Math.max(0, fromSec));
    return src;
  }
  private async audition(tr: Track, pitch: number): Promise<void> {
    const sg = this.song();
    if (!sg) return;
    try {
      const mini: Song = { ...sg, bars: 1, swing: 0, humanize: 0, chords: [], sections: [], tracks: [{ ...tr, mute: false, solo: false, bends: undefined, swell: undefined, notes: [[0, 0.5, pitch, 100]] }] };
      const a = await renderSong(mini, { tail: 0.7 });
      void this.audio().resume();
      this.playBuffer(a, 0);
    } catch {
      this.say(musicProblem());
    }
  }
  private async toggle(): Promise<void> {
    if (this.src) this.stop();
    else await this.play(this.cursor);
  }
  private async play(fromBeat: number): Promise<void> {
    const sg = this.song();
    if (!sg) return;
    this.stop();
    const key = JSON.stringify(sg);
    try {
      if (!this.cache || this.cache.key !== key) {
        this.say("Rendering…");
        const sung = sg.tracks.some((t) => t.kind === "vocal" && t.notes.length && !t.mute);
        openVoice.onProgress = (d, what) => this.say(`${what}… ${Math.round(d * 100)}%`);
        const audio = await renderWithVocals(sg, sung ? openVoice : null, { onProgress: (d) => this.say(`Rendering… ${Math.round(d * 100)}%`) });
        this.cache = { key, audio };
        this.say("");
      }
    } catch (e) {
      this.say(musicProblem() || (e as Error).message);
      return;
    }
    await this.audio().resume();
    const spb = beatSec(sg);
    const src = this.playBuffer(this.cache.audio, fromBeat * spb);
    this.src = src;
    this.playT0 = this.audio().currentTime;
    this.playBeat0 = fromBeat;
    src.onended = () => {
      if (this.src === src) this.stop(true);
    };
    this.playBtn.replaceChildren(ic("pause", 15), document.createTextNode("Stop"));
    const tick = () => {
      if (!this.src) return;
      this.cursor = this.playBeat0 + (this.audio().currentTime - this.playT0) / spb;
      const w = (this.cv.clientWidth - GUT) / this.pxBeat;
      if (this.cursor > this.beat0 + w * 0.92) this.beat0 = this.cursor - w * 0.1;
      this.showPos();
      this.draw();
      this.raf = requestAnimationFrame(tick);
    };
    this.raf = requestAnimationFrame(tick);
  }
  private stop(ended = false): void {
    cancelAnimationFrame(this.raf);
    const src = this.src;
    this.src = null;
    if (src) {
      src.onended = null;
      try {
        src.stop();
      } catch {
        /* already finished */
      }
    }
    if (ended) this.cursor = 0;
    this.playBtn.replaceChildren(ic("play", 15), document.createTextNode("Play"));
    this.showPos();
    this.draw();
  }
  private seek(beat: number): void {
    const was = !!this.src;
    this.stop();
    this.cursor = Math.max(0, beat);
    if (beat === 0) this.beat0 = 0;
    this.showPos();
    this.draw();
    if (was) void this.play(this.cursor);
  }
  private showPos(): void {
    const sg = this.song();
    const sec = sg ? this.cursor * beatSec(sg) : 0;
    const bb = sg ? barBeats(sg) : 4;
    this.posEl.textContent = `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, "0")} · bar ${Math.floor(this.cursor / bb) + 1}`;
  }

  /* ------------------------------------------------------------ actions */

  private async addTrack(): Promise<void> {
    let sg = this.song();
    if (!sg) {
      await this.run({ op: "music.new", name: "Song" });
      sg = this.song();
    }
    if (!sg) return;
    const r = (await this.run({ op: "music.track", songId: sg.id, name: "New track", instrument: "Grand Piano" })) as { id?: string } | null;
    if (r?.id) this.trackId = r.id;
    this.refresh();
  }
  private async pickInstrument(sg: Song, t: Track): Promise<void> {
    const all = this.instruments.length ? this.instruments : await listInstruments().catch(() => [] as Instrument[]);
    const list = h("div", { class: "mu-pick" });
    const q = h("input", { class: "in", placeholder: `Search ${all.length} instruments: sitar, strings, flute, kit…` });
    const fill = () => {
      const w = q.value.toLowerCase();
      list.replaceChildren();
      let fam = "";
      for (const i of all) {
        if (w && !i.name.toLowerCase().includes(w) && !i.family.toLowerCase().includes(w)) continue;
        if (i.family !== fam) list.append(h("div", { class: "mu-fam" }, (fam = i.family)));
        const row = h("button", { class: "mu-pi", type: "button" }, i.name);
        row.addEventListener("click", () => {
          void this.run({ op: "music.track", songId: sg.id, id: t.id, instrument: i.drums ? i.program : i.name, kind: i.drums ? "drums" : "inst" }).then(() => {
            this.cache = null;
            const tr = this.track();
            if (tr) void this.audition(tr, tr.kind === "drums" ? 38 : 60);
          });
        });
        list.append(row);
      }
    };
    q.addEventListener("input", fill);
    fill();
    modal(`Instrument for ${t.name}`, h("div", { class: "mu-pickwrap" }, q, list));
    q.focus();
  }
  private async toTimeline(): Promise<void> {
    const sg = this.song();
    if (!sg) return;
    this.say("Rendering to the timeline…");
    const r = (await this.run({ op: "music.render", songId: sg.id, toTimeline: true })) as { seconds?: number; levels?: { balance: number; note: string } } | null;
    if (r) this.say(`On the timeline (${r.seconds}s).${r.levels ? ` Voice ${r.levels.balance >= 0 ? "+" : ""}${r.levels.balance} dB against the music: ${r.levels.note}.` : " Open Edit to place it."}`);
  }
  /** Voice against instruments, for songs with singing: two faders and a clarity control. */
  private mixCard(sg: Song): HTMLElement[] {
    if (!sg.tracks.some((t) => t.kind === "vocal" && t.notes.length)) return [];
    const m = { vocals: 1, music: 1, clarity: 0.6, ...sg.mix };
    const row = (label: string, key: "vocals" | "music" | "clarity", max: number, title: string) => {
      const pct = (v: number) => (key === "clarity" ? `${Math.round(v * 100)}%` : `${v >= 1 ? "+" : ""}${(20 * Math.log10(Math.max(v, 0.01))).toFixed(1)} dB`);
      const out = h("span", { class: "mu-val" }, pct(m[key]));
      const e = h("input", { type: "range", min: "0", max: String(max), step: "0.05", value: String(m[key]), title, class: "mu-sl" });
      e.addEventListener("input", () => (out.textContent = pct(Number(e.value))));
      e.addEventListener("change", () => {
        this.cache = null;
        void this.run({ op: "music.mix", songId: sg.id, [key]: Number(e.value), render: false });
      });
      return h("label", { class: "mu-row" }, h("span", {}, label), e, out);
    };
    return [
      h("div", { class: "mu-h" }, "Voice and music"),
      row("Vocals", "vocals", 2, "How loud the singing is. The middle is the balanced level; right is louder."),
      row("Music", "music", 2, "How loud the instruments are. The middle is the balanced level; right is louder."),
      row("Clarity", "clarity", 1, "Presence and consonants of the voice, and the room the instruments leave for it."),
      h("p", { class: "hint" }, "The voice is placed just in front of the instruments by itself; these move it from there. Play or render to hear the change."),
    ];
  }
  private exportMid(): void {
    const sg = this.song();
    if (!sg) return;
    const a = h("a", { href: URL.createObjectURL(new Blob([exportMidi(sg) as BlobPart], { type: "audio/midi" })), download: `${sg.name}.mid` });
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }
  private importMid(): void {
    const inp = h("input", { type: "file", accept: ".mid,.midi" });
    inp.addEventListener("change", () => {
      const f = inp.files?.[0];
      if (!f) return;
      void f.arrayBuffer().then((buf) => {
        try {
          const song = importMidi(new Uint8Array(buf), f.name.replace(/\.midi?$/i, ""));
          this.s.mutate("import midi", (p) => void (p.songs ??= []).push(song));
          this.songId = song.id;
          this.trackId = null;
          this.refresh();
        } catch (e) {
          this.say((e as Error).message);
        }
      });
    });
    inp.click();
  }
}
