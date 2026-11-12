/**
 * Music commands: write songs as notes on instrument tracks (261 instruments and 13 drum kits), let the composer
 * draft whole arrangements from a brief, edit anything, and bounce to the timeline.
 */
import type { Studio } from "../app/studio";
import * as ops from "../core/ops";
import type { Project } from "../core/model";
import { importBlob } from "../media/library";
import { registerCommand } from "./api";
import { MOODS, STYLES, composeSong, describeSong, type Brief } from "../music/compose";
import { barBeats, beatSec, keyName, keyOf, newSong, newTrack, pitchName, pitchOf, songBeats, songSeconds, type Note, type Song, type Track } from "../music/model";
import { exportMidi, importMidi } from "../music/midi";
import { findInstrument, listInstruments, wavBytes } from "../music/synth";
import { renderWithVocals } from "../music/vocal";
import { singRange } from "../voice/engine/sing";
import { openVoice } from "../voice/open_client";
import { SCALES, TAALS, chordName, parseChord, scaleDef } from "../music/theory";

const r2 = (n: number) => Math.round(n * 100) / 100;

function songOf(p: Project, id: string | undefined): Song {
  const list = p.songs ?? [];
  const s = id ? list.find((x) => x.id === id || x.name === id) : list[list.length - 1];
  if (!s) throw new ops.OpError(id ? `No song "${id}". Songs: ${list.map((x) => `${x.id} (${x.name})`).join(", ") || "none yet (music.new or music.generate makes one)"}.` : "There is no song yet. music.generate or music.new makes one.");
  return s;
}
function trackOf(s: Song, id: string): Track {
  const t = s.tracks.find((x) => x.id === id || x.name.toLowerCase() === id.toLowerCase());
  if (!t) throw new ops.OpError(`No track "${id}" in ${s.name}. Tracks: ${s.tracks.map((x) => `${x.id} (${x.name})`).join(", ") || "none"}.`);
  return t;
}
function wrap<T>(fn: () => T): T {
  try {
    return fn();
  } catch (e) {
    if (e instanceof ops.OpError) throw e;
    throw new ops.OpError((e as Error).message);
  }
}
/** Notes given by the agent: [beat, length, pitch | "C4", velocity?] or {at, len, pitch, vel}. */
function parseNotes(raw: unknown): Note[] {
  if (!Array.isArray(raw)) throw new ops.OpError('Notes must be a list like [[0, 1, "C4", 90], [1, 0.5, "E4"]] ([beat, length in beats, pitch, loudness 1-127]).');
  return raw.map((n) => {
    const a = Array.isArray(n) ? n : [(n as { at: number }).at, (n as { len: number }).len, (n as { pitch: number }).pitch, (n as { vel?: number }).vel];
    const at = Number(a[0]), len = Number(a[1]);
    if (!Number.isFinite(at) || at < 0 || !Number.isFinite(len) || len <= 0) throw new ops.OpError("Each note needs a start beat (0 or later) and a length in beats (above 0).");
    const pitch = wrap(() => pitchOf(a[2] as string | number));
    if (!(pitch >= 0 && pitch <= 127)) throw new ops.OpError(`Pitch ${String(a[2])} is outside 0-127.`);
    return [at, len, pitch, Math.max(1, Math.min(127, Math.round(Number(a[3] ?? 90))))] as Note;
  });
}

async function bounce(st: Studio, songId: string, o: { toTimeline: boolean; start?: number; volume?: number; only?: string[]; name?: string }): Promise<Record<string, unknown>> {
  const song = songOf(st.project, songId);
  if (!song.tracks.some((t) => t.notes.length)) throw new ops.OpError("This song has no notes yet.");
  st.setStatus(`Rendering ${song.name}…`);
  const sung = song.tracks.some((t) => t.kind === "vocal" && t.notes.length && !t.mute);
  openVoice.onProgress = (d, what) => st.setStatus(`${what}… ${Math.round(d * 100)}%`);
  const audio = await renderWithVocals(song, sung ? openVoice : null, { only: o.only, onProgress: (d) => st.setStatus(`Rendering ${song.name}… ${Math.round(d * 100)}%`) });
  const blob = new Blob([wavBytes(audio) as BlobPart], { type: "audio/wav" });
  st.setStatus("");
  return await st.mutateAsync("render music", async (p) => {
    const sg = songOf(p, songId);
    const asset = await importBlob(p, blob, `${o.name ?? sg.name}.wav`, "agent");
    let clipId: string | undefined;
    if (o.toTimeline) {
      // re-rendering a song replaces its clip where it sits
      let old: { start: number; volume?: number } | undefined;
      if (sg.assetId && !o.only) {
        for (const tr of p.tracks) for (const c of [...tr.clips]) if (c.assetId === sg.assetId) {
          old = { start: c.start, volume: (c as { volume?: number }).volume };
          ops.deleteClip(p, c.id);
        }
      }
      const clip = ops.addClip(p, { assetId: asset.id, start: o.start ?? old?.start ?? 0 });
      ops.patchClip(p, clip.id, { volume: o.volume ?? old?.volume ?? 0.9 });
      clipId = clip.id;
    }
    if (!o.only) sg.assetId = asset.id;
    return { assetId: asset.id, clipId, seconds: r2(audio.seconds), peak: r2(audio.peak) };
  });
}

/** Lyrics as parts: blank lines separate them; "[chorus]", "Verse 2:" and the like name them. */
export function lyricBlocks(text: string): { name: string; lines: string[] }[] {
  const out: { name: string; lines: string[] }[] = [];
  let cur: { name: string; lines: string[] } | null = null;
  const names = ["verse", "chorus", "verse 2", "chorus", "bridge", "chorus"];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    const tag = /^\[(.+)\]$/.exec(line) ?? /^(verse|chorus|bridge|hook|mukhda|antara|intro|outro)[\s\d]*:?$/i.exec(line);
    if (!line || tag) {
      if (cur && cur.lines.length) out.push(cur);
      cur = tag ? { name: tag[1]!.trim().toLowerCase(), lines: [] } : null;
      continue;
    }
    cur ??= { name: names[Math.min(out.length, names.length - 1)]!, lines: [] };
    cur.lines.push(line);
  }
  if (cur && cur.lines.length) out.push(cur);
  return out;
}

export function registerMusicCommands(): void {
  registerCommand(
    "music.sing",
    "{songId?, track (a melody track whose tune is to be sung), lyrics (lines; syllables are set to the notes in order: more notes than syllables hold the last syllable of each line), voice?=hf_alpha} Give an existing tune words and a singer: the track becomes a vocal track. music.render then sings it with the band.",
    async (st, a) => {
      const song = songOf(st.project, a.optStr("songId"));
      const t0 = trackOf(song, a.str("track"));
      if (t0.kind === "drums") throw new ops.OpError("A drum track cannot be sung.");
      const voice = a.str("voice", t0.voice ?? "hf_alpha");
      const lines = String(a.any("lyrics") ?? "").split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
      if (!lines.length) throw new ops.OpError("Give the lyrics, one line of the song per line.");
      const syl = (await openVoice.syllables(lines, voice)).flat();
      return st.mutate("song lyrics", (p) => {
        const sg = songOf(p, song.id);
        const t = trackOf(sg, t0.id);
        // one voice sings one note at a time: keep the top note of any chord
        const byStart = new Map<number, Note>();
        for (const n of t.notes) {
          const k = Math.round(n[0] * 96);
          const o = byStart.get(k);
          if (!o || n[2] > o[2]) byStart.set(k, n);
        }
        t.notes = [...byStart.values()].sort((x, y) => x[0] - y[0]);
        t.kind = "vocal";
        t.voice = voice;
        t.lyrics = t.notes.map((_, i) => syl[i] ?? "");
        t.bends = undefined;
        return { track: t.name, voice, notes: t.notes.length, syllables: syl.length, held: Math.max(0, t.notes.length - syl.length), unsung: Math.max(0, syl.length - t.notes.length) };
      });
    },
  );

  registerCommand("music.instruments", '{q?: "sitar" | "strings" | "kit" …, family?} The instruments in the sound bank (261 instruments, 13 drum kits) with their families. Search by word, or list a family.', async (_s, a) => {
    const all = await listInstruments();
    const q = (a.optStr("q") ?? "").toLowerCase(), fam = (a.optStr("family") ?? "").toLowerCase();
    const hit = all.filter((i) => (!q || i.name.toLowerCase().includes(q) || i.family.toLowerCase().includes(q)) && (!fam || i.family.toLowerCase().includes(fam)));
    const fams: Record<string, number> = {};
    for (const i of all) fams[i.family] = (fams[i.family] ?? 0) + 1;
    return { total: all.length, families: fams, instruments: (q || fam ? hit : all).slice(0, 300).map((i) => (i.drums ? `${i.name} (kit ${i.program})` : `${i.name} (${i.program}${i.bank ? "/" + i.bank : ""}) · ${i.family}`)) };
  });

  registerCommand("music.styles", "The composer's styles, moods, scales and ragas, and rhythm cycles (taals).", () => ({
    styles: Object.fromEntries(Object.entries(STYLES).map(([k, v]) => [k, `${v.about} (${v.bpm[0]}-${v.bpm[1]} bpm)`])),
    moods: Object.keys(MOODS),
    scales: Object.entries(SCALES).filter(([, v]) => v.family !== "raga").map(([k]) => k),
    ragas: Object.fromEntries(Object.entries(SCALES).filter(([, v]) => v.family === "raga").map(([k, v]) => [k, v.about ?? ""])),
    taals: Object.fromEntries(Object.entries(TAALS).map(([k, v]) => [k, v.about])),
  }));

  registerCommand(
    "music.generate",
    `{style: ${Object.keys(STYLES).join("|")}, mood?: ${Object.keys(MOODS).join("|")}, seconds? | bars?, bpm?, key?: "C"|"F#"|"Bb"…, scale? (a scale or raga; music.styles lists them), taal?: ${Object.keys(TAALS).join("|")}, lead? (instrument name for the tune), noLead?: true (a bed without a tune), lyrics? (a song with singing: lines of Hindi, Hinglish or English; a blank line starts a new part; "[verse]" / "[chorus]" name the parts), voice? (who sings: hf_alpha, hf_beta, hm_omega, hm_psi, af_heart, am_michael…), seed? (same seed, same piece), name?, songId? (replace that song), toTimeline?=true, start?, volume?} Compose a complete, original piece: chords, a tune built from motifs, bass, accompaniment, drums or tabla-style hand percussion, with sections that build and release. Returns the song (tracks, sections, chords). Everything is editable notes: change instruments with music.track, rewrite parts with music.notes, then music.render again.`,
    async (st, a) => {
      const brief: Brief = { style: a.str("style"), mood: a.optStr("mood"), bars: a.optNum("bars"), seconds: a.optNum("seconds"), bpm: a.optNum("bpm"), key: a.optStr("key") !== undefined ? wrap(() => keyOf(a.str("key"))) : undefined, scale: a.optStr("scale")?.toLowerCase().replace(/[\s-]+/g, "_"), seed: a.optNum("seed"), name: a.optStr("name"), taal: a.optStr("taal"), noLead: a.bool("noLead", false) };
      if (brief.taal && !TAALS[brief.taal]) throw new ops.OpError(`Unknown taal "${brief.taal}". Taals: ${Object.keys(TAALS).join(", ")}.`);
      const lyr = a.any("lyrics");
      if (lyr !== undefined) {
        const voice = a.str("voice", "hf_alpha");
        const blocks = lyricBlocks(Array.isArray(lyr) ? lyr.map(String).join("\n") : String(lyr));
        if (!blocks.length) throw new ops.OpError("The lyrics are empty.");
        st.setStatus("Reading the lyrics…");
        const out: { name: string; lines: string[][] }[] = [];
        for (const blk of blocks) {
          const syl = await openVoice.syllables(blk.lines, voice);
          const bad = syl.findIndex((x) => !x.length);
          if (bad >= 0) throw new ops.OpError(`This line has nothing to sing: "${blk.lines[bad]}".`);
          out.push({ name: blk.name, lines: syl });
        }
        brief.vocal = { voice, range: singRange(voice), blocks: out };
      }
      const leadName = a.optStr("lead");
      if (leadName) {
        const ins = await findInstrument(leadName);
        brief.lead = ins.program;
        brief.leadName = ins.name;
      }
      const song = wrap(() => composeSong(brief));
      const all = await listInstruments();
      for (const t of song.tracks) t.instrument = all.find((i) => i.drums === (t.kind === "drums") && i.program === t.program && i.bank === (t.bank ?? 0))?.name ?? all.find((i) => i.drums === (t.kind === "drums") && i.program === t.program)?.name;
      const replace = a.optStr("songId");
      st.mutate("compose song", (p) => {
        p.songs ??= [];
        const i = replace ? p.songs.findIndex((x) => x.id === replace) : -1;
        if (i >= 0) {
          song.id = p.songs[i]!.id;
          song.assetId = p.songs[i]!.assetId;
          p.songs[i] = song;
        } else p.songs.push(song);
      });
      const out: Record<string, unknown> = { ...describeSong(song), chords: song.chords.filter((_, i) => i < 16).map((c) => chordName(c)).join(" | ") + (song.chords.length > 16 ? " | …" : "") };
      if (a.bool("toTimeline", true)) Object.assign(out, await bounce(st, song.id, { toTimeline: true, start: a.optNum("start"), volume: a.optNum("volume") }));
      return out;
    },
  );

  registerCommand("music.new", '{name?, bpm?=100, key?="C", scale?="major", bars?=16, meter?: [4, 4], swing? (0..0.4), humanize? (0..1)} An empty song to write by hand with music.track and music.notes.', (st, a) => {
    const meter = a.any("meter") as [number, number] | undefined;
    const scale = a.str("scale", "major").toLowerCase().replace(/[\s-]+/g, "_");
    wrap(() => scaleDef(scale));
    const song = newSong({ name: a.str("name", "Song"), bpm: a.num("bpm", 100), key: wrap(() => keyOf(a.str("key", "C"))), scale, bars: Math.max(1, Math.round(a.num("bars", 16))), meter: Array.isArray(meter) && meter.length === 2 ? [Number(meter[0]), Number(meter[1])] : [4, 4], swing: a.optNum("swing"), humanize: a.optNum("humanize") });
    st.mutate("new song", (p) => {
      (p.songs ??= []).push(song);
    });
    return describeSong(song);
  });

  registerCommand(
    "music.track",
    '{songId?, id? (change that track) | name (a new one), instrument?: name or program number ("nylon guitar", "sitar", 73), kind?: inst|drums, volume? (0..1), pan? (-1..1), reverb? (0..1), chorus? (0..1), voice? (the singer of a vocal track), mute?, solo?, remove?: true} Add a track to a song, or change one: its instrument, level, place in the stereo picture, how wet it sits.',
    async (st, a) => {
      const instr = a.any("instrument") as string | number | undefined;
      const kindArg = a.optStr("kind");
      const song0 = songOf(st.project, a.optStr("songId"));
      const existing = a.optStr("id") ? trackOf(song0, a.str("id")) : null;
      const drums = (kindArg ?? existing?.kind) === "drums" || (typeof instr === "string" && /\bkit\b/i.test(instr));
      const ins = instr !== undefined ? await findInstrument(instr, drums) : null;
      return st.mutate("song track", (p) => {
        const song = songOf(p, song0.id);
        if (a.bool("remove", false)) {
          const t = trackOf(song, a.str("id"));
          song.tracks = song.tracks.filter((x) => x !== t);
          return { removed: t.name, tracks: song.tracks.map((x) => x.name) };
        }
        let t = a.optStr("id") ? trackOf(song, a.str("id")) : null;
        if (!t) {
          t = newTrack({ name: a.str("name", ins?.name ?? "Track"), kind: drums ? "drums" : "inst" });
          song.tracks.push(t);
        } else if (a.optStr("name")) t.name = a.str("name");
        if (ins) {
          t.program = ins.program;
          t.bank = ins.bank || undefined;
          t.instrument = ins.name;
          t.kind = ins.drums ? "drums" : "inst";
        }
        for (const k of ["volume", "pan", "reverb", "chorus"] as const) if (a.optNum(k) !== undefined) t[k] = a.num(k);
        if (a.optStr("voice")) t.voice = a.str("voice");
        if (a.any("mute") !== undefined) t.mute = a.bool("mute") || undefined;
        if (a.any("solo") !== undefined) t.solo = a.bool("solo") || undefined;
        return { id: t.id, name: t.name, kind: t.kind, instrument: t.instrument ?? t.program, volume: t.volume, pan: t.pan, reverb: t.reverb, notes: t.notes.length };
      });
    },
  );

  registerCommand(
    "music.notes",
    '{songId?, track, add?: [[beat, length, pitch ("C4" or 60; middle C = C4), loudness? 1-127], …], replace?: true (clear the range first), from?, to? (beats; with replace or erase), erase?: true, bends?: [[beat, semitones], …] (glides: meend, slides, vibrato; ±12), swell?: [[beat, 0..1], …]} Write, replace or erase notes on a track. Beats count from 0; a 4/4 bar is 4 beats. For drum tracks the pitch is the drum (36 kick, 38 snare, 42 closed hat, 46 open hat, 49 crash, 39 clap, 41/45/48 toms, 60-64 bongos and congas, 54 tambourine, 75 claves).',
    (st, a) =>
      st.mutate("song notes", (p) => {
        const song = songOf(p, a.optStr("songId"));
        const t = trackOf(song, a.str("track"));
        const from = a.num("from", 0), to = a.num("to", 1e9);
        if (a.bool("replace", false) || a.bool("erase", false)) t.notes = t.notes.filter((n) => n[0] < from - 1e-6 || n[0] >= to - 1e-6);
        const add = a.any("add") !== undefined ? parseNotes(a.any("add")) : [];
        t.notes.push(...add);
        t.notes.sort((x, y) => x[0] - y[0] || x[2] - y[2]);
        const pts = (k: string, lo: number, hi: number): [number, number][] | undefined => {
          const v = a.any(k);
          if (v === undefined) return undefined;
          if (!Array.isArray(v) || v.some((x) => !Array.isArray(x) || x.length < 2)) throw new ops.OpError(`${k} must be a list of [beat, value] pairs.`);
          return (v as number[][]).map((x) => [Number(x[0]), Math.max(lo, Math.min(hi, Number(x[1])))] as [number, number]).sort((x, y) => x[0] - y[0]);
        };
        const b = pts("bends", -12, 12), sw = pts("swell", 0, 1);
        if (b) t.bends = b.length ? b : undefined;
        if (sw) t.swell = sw.length ? sw : undefined;
        const end = Math.ceil(songBeats(song) / barBeats(song) - 1e-6);
        if (end > song.bars) song.bars = end;
        return { track: t.name, notes: t.notes.length, added: add.length, bars: song.bars, seconds: r2(songSeconds(song)) };
      }),
  );

  registerCommand(
    "music.get",
    "{songId?, track? (list that track's notes), fromBar?, toBar?} A song as it stands: tempo, key, sections, chords and tracks; with a track, its notes as [beat, length, pitch name, loudness].",
    (st, a) => {
      const song = songOf(st.project, a.optStr("songId"));
      const out: Record<string, unknown> = { ...describeSong(song), swing: song.swing, humanize: song.humanize, chords: song.chords.map((c) => `${r2(c.at)}:${chordName(c)}`).join(" ") };
      if (a.optStr("track")) {
        const t = trackOf(song, a.str("track"));
        const bb = barBeats(song);
        const f = (a.num("fromBar", 1) - 1) * bb, e = a.num("toBar", song.bars) * bb;
        const ns = t.notes.filter((n) => n[0] >= f - 1e-6 && n[0] < e - 1e-6);
        out.notes = ns.slice(0, 400).map((n) => [r2(n[0]), r2(n[1]), t.kind === "drums" ? n[2] : pitchName(n[2]), n[3]]);
        if (ns.length > 400) out.more = `${ns.length - 400} more notes: narrow fromBar/toBar`;
      } else out.songs = (st.project.songs ?? []).map((x) => `${x.id} (${x.name})`);
      return out;
    },
  );

  registerCommand(
    "music.edit",
    '{songId?, bpm?, key?, scale?, swing?, humanize?, room? (0..1 reverb size), gain?, name?, transpose? (semitones, all pitched tracks or just `track`), track?, quantize? (grid in beats, e.g. 0.25), louder? (multiply loudness), shift? (beats), copyBars?: {from: 1, count: 4, to: 9}, chords?: ["Am", "F", "C", "G"] (one per bar from bar `fromBar`=1)} Change a song as a whole: tempo, key, feel; transpose, tighten or copy sections; set the chord plan.',
    (st, a) =>
      st.mutate("song edit", (p) => {
        const song = songOf(p, a.optStr("songId"));
        if (a.optNum("bpm") !== undefined) song.bpm = Math.max(20, Math.min(300, a.num("bpm")));
        if (a.optStr("name")) song.name = a.str("name");
        if (a.optStr("scale")) {
          const sc = a.str("scale").toLowerCase().replace(/[\s-]+/g, "_");
          wrap(() => scaleDef(sc));
          song.scale = sc;
        }
        for (const k of ["swing", "humanize", "room", "gain"] as const) if (a.optNum(k) !== undefined) song[k] = a.num(k);
        const only = a.optStr("track") ? [trackOf(song, a.str("track"))] : song.tracks;
        const tr = a.optNum("transpose");
        if (a.optStr("key") !== undefined) {
          const k = wrap(() => keyOf(a.str("key")));
          // moving the key moves the music with it (the nearer way round)
          let d = k - song.key;
          if (d > 6) d -= 12;
          if (d < -6) d += 12;
          for (const t of song.tracks) if (t.kind !== "drums") for (const n of t.notes) n[2] = Math.max(0, Math.min(127, n[2] + d));
          for (const c of song.chords) c.root = (c.root + d + 12) % 12;
          song.key = k;
        }
        if (tr) for (const t of only) if (t.kind !== "drums") for (const n of t.notes) n[2] = Math.max(0, Math.min(127, n[2] + Math.round(tr)));
        const q = a.optNum("quantize");
        if (q && q > 0) for (const t of only) for (const n of t.notes) n[0] = Math.round(n[0] / q) * q;
        const lv = a.optNum("louder");
        if (lv) for (const t of only) for (const n of t.notes) n[3] = Math.max(1, Math.min(127, Math.round(n[3] * lv)));
        const sh = a.optNum("shift");
        if (sh) for (const t of only) t.notes = t.notes.map((n) => [n[0] + sh, n[1], n[2], n[3]] as Note).filter((n) => n[0] >= 0);
        const cp = a.any("copyBars") as { from?: number; count?: number; to?: number } | undefined;
        if (cp && cp.from && cp.to) {
          const bb = barBeats(song);
          const f = (cp.from - 1) * bb, len = (cp.count ?? 1) * bb, d = (cp.to - 1) * bb - f;
          for (const t of only) {
            t.notes = t.notes.filter((n) => n[0] < f + d - 1e-6 || n[0] >= f + d + len - 1e-6);
            t.notes.push(...t.notes.filter((n) => n[0] >= f - 1e-6 && n[0] < f + len - 1e-6).map((n) => [n[0] + d, n[1], n[2], n[3]] as Note));
            t.notes.sort((x, y) => x[0] - y[0]);
          }
          song.bars = Math.max(song.bars, cp.to - 1 + (cp.count ?? 1));
        }
        const ch = a.any("chords") as string[] | undefined;
        if (Array.isArray(ch)) {
          const bb = barBeats(song);
          const b0 = Math.max(0, a.num("fromBar", 1) - 1);
          song.chords = song.chords.filter((c) => c.at < b0 * bb - 1e-6 || c.at >= (b0 + ch.length) * bb - 1e-6);
          ch.forEach((name, i) => {
            const parts = String(name).trim().split(/\s+/);
            parts.forEach((nm, k) => song.chords.push({ at: (b0 + i) * bb + (k * bb) / parts.length, dur: bb / parts.length, ...wrap(() => parseChord(nm)) }));
          });
          song.chords.sort((x, y) => x.at - y.at);
          song.bars = Math.max(song.bars, b0 + ch.length);
        }
        return describeSong(song);
      }),
  );

  registerCommand(
    "music.render",
    "{songId?, toTimeline?=true, start? (s), volume?, tracks?: [ids or names] (only these: a stem), name?} Bounce a song to audio in the media pool and (by default) onto the timeline. Rendering a song again replaces its clip where it sits.",
    async (st, a) => {
      const song = songOf(st.project, a.optStr("songId"));
      const names = a.any("tracks") as string[] | undefined;
      const only = Array.isArray(names) ? names.map((n) => trackOf(song, String(n)).id) : undefined;
      return bounce(st, song.id, { toTimeline: a.bool("toTimeline", true), start: a.optNum("start"), volume: a.optNum("volume"), only, name: a.optStr("name") });
    },
  );

  registerCommand("music.delete", "{songId} Remove a song (its rendered audio stays in the media pool).", (st, a) =>
    st.mutate("delete song", (p) => {
      const s = songOf(p, a.str("songId"));
      p.songs = (p.songs ?? []).filter((x) => x !== s);
      return { removed: s.name };
    }),
  );

  registerCommand("music.exportMidi", "{songId?} The song as a Standard MIDI File, added to the media pool is not possible for MIDI: returns the file as base64 with its name, for saving.", (st, a) => {
    const song = songOf(st.project, a.optStr("songId"));
    const bytes = exportMidi(song);
    let bin = "";
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]!);
    return { name: `${song.name}.mid`, bytes: bytes.length, base64: btoa(bin) };
  });

  registerCommand("music.importMidi", '{base64 (a .mid file), name?} Bring a MIDI file in as a song: its tracks, instruments, notes and tempo, ready to play through the instrument bank and edit.', async (st, a) => {
    const bin = atob(a.str("base64"));
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const song = wrap(() => importMidi(bytes, a.str("name", "Imported song")));
    const all = await listInstruments();
    for (const t of song.tracks) t.instrument = all.find((i) => i.drums === (t.kind === "drums") && i.program === t.program)?.name;
    st.mutate("import midi", (p) => {
      (p.songs ??= []).push(song);
    });
    return describeSong(song);
  });

  // kept for the page: seconds per beat and key names without importing the model there
  void beatSec;
  void keyName;
}
