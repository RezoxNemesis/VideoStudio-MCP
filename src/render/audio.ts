/** Schedules every audible clip into an AudioContext (live preview) or OfflineAudioContext (export mix). */
import { clipGain } from "../core/evaluate";
import { findAsset, type Clip, type Project } from "../core/model";
import type { MediaPool } from "../media/library";

const reversed = new WeakMap<AudioBuffer, AudioBuffer>();
function reverseOf(ctx: BaseAudioContext, b: AudioBuffer): AudioBuffer {
  let r = reversed.get(b);
  if (!r) {
    r = ctx.createBuffer(b.numberOfChannels, b.length, b.sampleRate);
    for (let c = 0; c < b.numberOfChannels; c++) {
      const src = b.getChannelData(c);
      const dst = r.getChannelData(c);
      for (let i = 0, n = src.length; i < n; i++) dst[i] = src[n - 1 - i]!;
    }
    reversed.set(b, r);
  }
  return r;
}

export interface Scheduled {
  stop(): void;
}

/**
 * Play the timeline from `from` seconds, starting at ctx time `when`, until `to`.
 * Loads audio as needed; returns once everything is scheduled.
 */
export async function scheduleAudio(ctx: BaseAudioContext, p: Project, pool: MediaPool, from: number, to: number, when: number, dest: AudioNode = ctx.destination): Promise<Scheduled> {
  const nodes: AudioScheduledSourceNode[] = [];
  const master = ctx.createGain();
  master.connect(dest);
  const jobs: Promise<void>[] = [];
  const solo = p.tracks.some((t) => t.kind === "audio" && !t.muted) || p.tracks.some((t) => !t.muted);
  if (!solo) return { stop() {} };
  for (const track of p.tracks) {
    if (track.muted) continue;
    for (const c of track.clips) {
      if (c.kind !== "media") continue;
      const a = findAsset(p, c.assetId);
      if (!a || a.kind === "image") continue;
      if (c.start + c.dur <= from || c.start >= to) continue;
      jobs.push(
        (async () => {
          const buf = await pool.audioBuffer(a, ctx).catch(() => null);
          if (!buf) return;
          placeClip(ctx, c, buf, track.volume, from, to, when, master, nodes);
        })(),
      );
    }
  }
  await Promise.all(jobs);
  return {
    stop() {
      for (const n of nodes) {
        try {
          n.stop();
        } catch {
          /* already stopped */
        }
      }
      master.disconnect();
    },
  };
}

function placeClip(ctx: BaseAudioContext, c: Clip, buf: AudioBuffer, trackVol: number, from: number, to: number, when: number, out: AudioNode, nodes: AudioScheduledSourceNode[]) {
  const begin = Math.max(from, c.start);
  const end = Math.min(to, c.start + c.dur);
  if (end <= begin) return;
  const src = ctx.createBufferSource();
  const data = c.reverse ? reverseOf(ctx, buf) : buf;
  src.buffer = data;
  src.playbackRate.value = c.speed;
  const g = ctx.createGain();
  src.connect(g).connect(out);
  const startAt = when + (begin - from);
  // Source offset in seconds of the (possibly reversed) buffer.
  const local = begin - c.start;
  const srcPos = c.reverse ? data.duration - (c.in + (c.dur - local) * c.speed) : c.in + local * c.speed;
  // Gain curve sampled at 50 Hz covers keyframes and fades.
  const steps = Math.max(2, Math.ceil((end - begin) * 50));
  const curve = new Float32Array(steps);
  for (let i = 0; i < steps; i++) curve[i] = Math.max(0, clipGain(c, begin + ((end - begin) * i) / (steps - 1), trackVol));
  g.gain.setValueAtTime(curve[0]!, startAt);
  try {
    g.gain.setValueCurveAtTime(curve, startAt, Math.max(0.01, end - begin));
  } catch {
    /* curve overlap at very short spans: constant gain is fine */
  }
  src.start(startAt, Math.max(0, srcPos), (end - begin) * c.speed);
  nodes.push(src);
}

/** Offline mix of [from, to) at 48 kHz stereo. */
export async function mixdown(p: Project, pool: MediaPool, from: number, to: number, sampleRate = 48_000): Promise<AudioBuffer | null> {
  const len = Math.max(1, Math.ceil((to - from) * sampleRate));
  const ctx = new OfflineAudioContext(2, len, sampleRate);
  const hasAudio = p.tracks.some((t) => !t.muted && t.clips.some((c) => c.kind === "media" && p.assets.find((a) => a.id === c.assetId)?.kind !== "image"));
  if (!hasAudio) return null;
  await scheduleAudio(ctx, p, pool, from, to, 0);
  return await ctx.startRendering();
}

/** Encode an AudioBuffer as 16-bit WAV. */
export function audioBufferToWav(b: AudioBuffer): Blob {
  const ch = b.numberOfChannels;
  const n = b.length;
  const buf = new ArrayBuffer(44 + n * ch * 2);
  const v = new DataView(buf);
  const w = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, "RIFF");
  v.setUint32(4, 36 + n * ch * 2, true);
  w(8, "WAVE");
  w(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, ch, true);
  v.setUint32(24, b.sampleRate, true);
  v.setUint32(28, b.sampleRate * ch * 2, true);
  v.setUint16(32, ch * 2, true);
  v.setUint16(34, 16, true);
  w(36, "data");
  v.setUint32(40, n * ch * 2, true);
  const chans = Array.from({ length: ch }, (_, i) => b.getChannelData(i));
  let o = 44;
  for (let i = 0; i < n; i++)
    for (let c = 0; c < ch; c++) {
      const s = Math.max(-1, Math.min(1, chans[c]![i]!));
      v.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true);
      o += 2;
    }
  return new Blob([buf], { type: "audio/wav" });
}
