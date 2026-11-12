/** Standard MIDI File in and out (format 1, 480 ticks per quarter note). */
import { newSong, newTrack, type Note, type Song } from "./model";

const PPQ = 480;
const vlq = (n: number): number[] => {
  const out = [n & 0x7f];
  while ((n >>= 7)) out.unshift((n & 0x7f) | 0x80);
  return out;
};
const u32 = (n: number) => [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255];

export function exportMidi(song: Song): Uint8Array {
  const chunks: number[][] = [];
  const track = (events: [number, number[]][]): number[] => {
    events.sort((a, b) => a[0] - b[0]);
    const body: number[] = [];
    let last = 0;
    for (const [tick, data] of events) {
      body.push(...vlq(Math.max(0, tick - last)), ...data);
      last = Math.max(last, tick);
    }
    body.push(0, 0xff, 0x2f, 0);
    return [0x4d, 0x54, 0x72, 0x6b, ...u32(body.length), ...body];
  };
  const us = Math.round(60000000 / song.bpm);
  const nameBytes = [...new TextEncoder().encode(song.name)].slice(0, 120);
  chunks.push(track([[0, [0xff, 0x03, nameBytes.length, ...nameBytes]], [0, [0xff, 0x51, 3, (us >> 16) & 255, (us >> 8) & 255, us & 255]], [0, [0xff, 0x58, 4, song.meter[0], Math.round(Math.log2(song.meter[1])), 24, 8]]]));
  let next = 0;
  let drumUsed = false;
  for (const t of song.tracks) {
    if (t.kind === "vocal" || !t.notes.length) continue;
    let ch: number;
    if (t.kind === "drums" && !drumUsed) {
      ch = 9;
      drumUsed = true;
    } else {
      if (next === 9) next++;
      ch = next++ % 16;
      if (ch === 9) ch = next++ % 16;
    }
    const nm = [...new TextEncoder().encode(t.name)].slice(0, 120);
    const ev: [number, number[]][] = [[0, [0xff, 0x03, nm.length, ...nm]], [0, [0xb0 | ch, 0, t.bank ?? 0]], [0, [0xc0 | ch, t.program & 127]], [0, [0xb0 | ch, 7, Math.round(t.volume * 127)]], [0, [0xb0 | ch, 10, Math.round(64 + t.pan * 63)]], [0, [0xb0 | ch, 91, Math.round(t.reverb * 127)]]];
    for (const n of t.notes) {
      const a = Math.round(n[0] * PPQ), b = Math.max(a + 1, Math.round((n[0] + n[1]) * PPQ) - 1);
      ev.push([a, [0x90 | ch, n[2] & 127, n[3] & 127]], [b, [0x80 | ch, n[2] & 127, 0]]);
    }
    for (const [beat, semi] of t.bends ?? []) {
      const v = Math.max(0, Math.min(16383, Math.round(8192 + (semi / 12) * 8191)));
      ev.push([Math.round(beat * PPQ), [0xe0 | ch, v & 127, (v >> 7) & 127]]);
    }
    chunks.push(track(ev));
  }
  const head = [0x4d, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1, (chunks.length >> 8) & 255, chunks.length & 255, (PPQ >> 8) & 255, PPQ & 255];
  return new Uint8Array([...head, ...chunks.flat()]);
}

export function importMidi(bytes: Uint8Array, name: string): Song {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let p = 0;
  const str = (n: number) => String.fromCharCode(...bytes.subarray(p, (p += n)));
  if (str(4) !== "MThd") throw new Error("This is not a MIDI file.");
  const hl = dv.getUint32(p);
  p += 4;
  const ntr = dv.getUint16(p + 2), div = dv.getUint16(p + 4);
  p += hl;
  if (div & 0x8000) throw new Error("This MIDI file uses film timecode timing, which is not supported.");
  const song = newSong({ name });
  let bpmSet = false;
  let end = 0;
  // one song track per (file track, channel)
  for (let ti = 0; ti < ntr && p < bytes.length - 8; ti++) {
    if (str(4) !== "MTrk") break;
    const len = dv.getUint32(p);
    p += 4;
    const stop = p + len;
    let tick = 0, run = 0, tname = "";
    const byCh = new Map<number, { notes: Note[]; on: Map<number, [number, number][]>; prog: number; bank: number }>();
    const chan = (c: number) => {
      let x = byCh.get(c);
      if (!x) byCh.set(c, (x = { notes: [], on: new Map(), prog: 0, bank: 0 }));
      return x;
    };
    while (p < stop) {
      let d = 0, b: number;
      do {
        b = bytes[p++]!;
        d = (d << 7) | (b & 0x7f);
      } while (b & 0x80);
      tick += d;
      let st = bytes[p]!;
      if (st & 0x80) p++;
      else st = run;
      if (st === 0xff) {
        const type = bytes[p++]!;
        let l = 0;
        do {
          b = bytes[p++]!;
          l = (l << 7) | (b & 0x7f);
        } while (b & 0x80);
        if (type === 0x51 && !bpmSet) {
          song.bpm = Math.round(60000000 / ((bytes[p]! << 16) | (bytes[p + 1]! << 8) | bytes[p + 2]!));
          bpmSet = true;
        } else if (type === 0x58) song.meter = [bytes[p]!, 1 << bytes[p + 1]!];
        else if (type === 0x03 && !tname) tname = new TextDecoder().decode(bytes.subarray(p, p + l));
        p += l;
        continue;
      }
      if (st === 0xf0 || st === 0xf7) {
        let l = 0;
        do {
          b = bytes[p++]!;
          l = (l << 7) | (b & 0x7f);
        } while (b & 0x80);
        p += l;
        continue;
      }
      run = st;
      const hi = st & 0xf0, c = st & 0x0f;
      const a = bytes[p++]!;
      const b2 = hi === 0xc0 || hi === 0xd0 ? 0 : bytes[p++]!;
      const beat = tick / div;
      const C = chan(c);
      if (hi === 0x90 && b2 > 0) {
        // the same pitch can sound twice at once (a strum, a roll): each strike waits for its own release
        const q = C.on.get(a);
        if (q) q.push([beat, b2]);
        else C.on.set(a, [[beat, b2]]);
      } else if (hi === 0x80 || (hi === 0x90 && b2 === 0)) {
        const o = C.on.get(a)?.shift();
        if (o) {
          C.notes.push([o[0], Math.max(0.02, beat - o[0]), a, o[1]]);
          end = Math.max(end, beat);
        }
      } else if (hi === 0xc0) C.prog = a;
      else if (hi === 0xb0 && a === 0) C.bank = b2;
    }
    p = stop;
    for (const [c, C] of byCh) {
      if (!C.notes.length) continue;
      song.tracks.push(newTrack({ name: tname ? (byCh.size > 1 ? `${tname} ${c + 1}` : tname) : c === 9 ? "Drums" : `Track ${song.tracks.length + 1}`, kind: c === 9 ? "drums" : "inst", program: C.prog, bank: c === 9 ? undefined : C.bank || undefined, notes: C.notes.sort((x, y) => x[0] - y[0]) }));
    }
  }
  if (!song.tracks.length) throw new Error("This MIDI file has no notes.");
  song.bars = Math.max(1, Math.ceil(end / ((song.meter[0] * 4) / song.meter[1])));
  song.humanize = 0;
  return song;
}
