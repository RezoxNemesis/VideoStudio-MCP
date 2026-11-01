/**
 * On-device music and ambience. Everything is synthesised with an OfflineAudioContext (no samples, no downloads):
 * pads, piano, strings, bass and soft drums following a chord plan, with a "lift" where the score brightens,
 * plus river / wind / city / rain beds. Output is a 16-bit stereo WAV blob.
 */

export const MOODS = ["melancholy", "hopeful", "dark", "tense", "calm", "epic"] as const;
export type Mood = (typeof MOODS)[number];
export const AMBIENCES = ["river", "wind", "city", "rain", "night"] as const;
export type Ambience = (typeof AMBIENCES)[number];

const SR = 44100;

export interface ComposeOptions {
  mood?: Mood;
  seconds: number;
  /** Seconds where the score lifts (brighter chords, melody, pulse). Omit for no lift. */
  lift?: number;
  bpm?: number;
  /** Root note as a MIDI number (57 = A3). */
  root?: number;
  seed?: number;
}

const midiHz = (m: number) => 440 * Math.pow(2, (m - 69) / 12);

function rng(seed: number) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return ((s >>> 0) % 100000) / 100000;
  };
}

/** Chords as semitone offsets from the key root (minor key). */
const PLANS: Record<Mood, { before: number[][]; after: number[][]; bpm: number }> = {
  melancholy: { before: [[0, 3, 7], [-4, 0, 3], [-9, -5, -2], [-2, 2, 5]], after: [[-4, 0, 3], [-2, 2, 5], [3, 7, 10], [-9, -5, -2]], bpm: 72 },
  hopeful: { before: [[-4, 0, 3], [3, 7, 10], [-2, 2, 5], [0, 3, 7]], after: [[3, 7, 10], [-2, 2, 5], [-4, 0, 3], [5, 8, 12]], bpm: 84 },
  dark: { before: [[0, 3, 7], [1, 5, 8], [0, 3, 7], [-1, 3, 6]], after: [[0, 3, 7], [-4, 0, 3], [1, 5, 8], [-1, 2, 7]], bpm: 70 },
  tense: { before: [[0, 3, 7], [0, 3, 6], [1, 4, 8], [0, 3, 7]], after: [[0, 3, 7], [1, 5, 8], [-2, 2, 5], [-1, 3, 6]], bpm: 96 },
  calm: { before: [[3, 7, 10, 14], [-4, 0, 3, 7], [-2, 2, 5, 9], [0, 3, 7, 10]], after: [[3, 7, 10, 14], [5, 8, 12, 15], [-4, 0, 3, 7], [-2, 2, 5, 9]], bpm: 66 },
  epic: { before: [[0, 3, 7], [-4, 0, 3], [3, 7, 10], [-2, 2, 5]], after: [[0, 3, 7], [-4, 0, 3], [3, 7, 10], [-2, 2, 5]], bpm: 90 },
};

function impulse(ctx: BaseAudioContext, seconds: number, decay: number, seed: number): AudioBuffer {
  const n = Math.floor(SR * seconds);
  const b = ctx.createBuffer(2, n, SR);
  const r = rng(seed);
  for (let c = 0; c < 2; c++) {
    const d = b.getChannelData(c);
    for (let i = 0; i < n; i++) d[i] = (r() * 2 - 1) * Math.pow(1 - i / n, decay);
  }
  return b;
}

function noiseBuffer(ctx: BaseAudioContext, seconds: number, seed: number, brown = false): AudioBuffer {
  const n = Math.floor(SR * seconds);
  const b = ctx.createBuffer(2, n, SR);
  const r = rng(seed);
  for (let c = 0; c < 2; c++) {
    const d = b.getChannelData(c);
    let last = 0;
    for (let i = 0; i < n; i++) {
      const w = r() * 2 - 1;
      if (brown) {
        last = (last + 0.02 * w) / 1.02;
        d[i] = last * 3.5;
      } else d[i] = w;
    }
  }
  return b;
}

/** Compose a score. Returns a WAV blob. */
export async function compose(o: ComposeOptions): Promise<Blob> {
  const mood = o.mood ?? "melancholy";
  const plan = PLANS[mood] ?? PLANS.melancholy;
  const secs = Math.max(2, Math.min(300, o.seconds));
  const bpm = o.bpm ?? plan.bpm;
  const beat = 60 / bpm;
  const bar = beat * 4;
  const root = o.root ?? 57;
  const lift = o.lift ?? Infinity;
  const r = rng(o.seed ?? 11);
  const ctx = new OfflineAudioContext(2, Math.ceil(SR * (secs + 0.1)), SR);

  const master = ctx.createGain();
  master.gain.value = 0.9;
  const comp = ctx.createDynamicsCompressor();
  comp.threshold.value = -14;
  comp.ratio.value = 3;
  master.connect(comp).connect(ctx.destination);
  const verb = ctx.createConvolver();
  verb.buffer = impulse(ctx, 3.8, 2.6, 5);
  const verbGain = ctx.createGain();
  verbGain.gain.value = 0.55;
  verb.connect(verbGain).connect(master);
  const dry = ctx.createGain();
  dry.gain.value = 0.75;
  dry.connect(master);
  const send = (node: AudioNode, wet = 0.6) => {
    const g = ctx.createGain();
    g.gain.value = wet;
    node.connect(g).connect(verb);
    node.connect(dry);
  };

  // Global fades.
  master.gain.setValueAtTime(0, 0);
  master.gain.linearRampToValueAtTime(0.9, Math.min(3, secs * 0.15));
  master.gain.setValueAtTime(0.9, Math.max(0, secs - 3));
  master.gain.linearRampToValueAtTime(0, secs);

  const chordAt = (t: number) => {
    const list = t >= lift ? plan.after : plan.before;
    const i = Math.floor((t >= lift ? t - lift : t) / bar) % list.length;
    return list[i]!;
  };

  // Pad: detuned saws through a slow low-pass; opens up after the lift.
  for (let t = 0; t < secs; t += bar) {
    const ch = chordAt(t);
    const end = Math.min(secs, t + bar);
    const bright = t >= lift ? 2600 : 900;
    for (const n of ch) {
      for (const det of [-7, 6]) {
        const osc = ctx.createOscillator();
        osc.type = "sawtooth";
        osc.frequency.value = midiHz(root + n);
        osc.detune.value = det;
        const lp = ctx.createBiquadFilter();
        lp.type = "lowpass";
        lp.frequency.setValueAtTime(bright * 0.6, t);
        lp.frequency.linearRampToValueAtTime(bright, t + bar * 0.6);
        lp.Q.value = 0.5;
        const g = ctx.createGain();
        const lvl = (t >= lift ? 0.035 : 0.028) / ch.length;
        g.gain.setValueAtTime(0, t);
        g.gain.linearRampToValueAtTime(lvl, t + Math.min(1.6, bar * 0.4));
        g.gain.setValueAtTime(lvl, Math.max(t, end - 0.4));
        g.gain.linearRampToValueAtTime(0, end + 0.6);
        osc.connect(lp).connect(g);
        send(g, 0.8);
        osc.start(t);
        osc.stop(end + 0.7);
      }
    }
    // Sub bass on the chord root.
    const b = ctx.createOscillator();
    b.type = "sine";
    b.frequency.value = midiHz(root - 24 + ch[0]!);
    const bg = ctx.createGain();
    const bl = t >= lift ? 0.16 : 0.1;
    bg.gain.setValueAtTime(0, t);
    bg.gain.linearRampToValueAtTime(bl, t + 0.3);
    bg.gain.setValueAtTime(bl, Math.max(t, end - 0.3));
    bg.gain.linearRampToValueAtTime(0, end + 0.2);
    b.connect(bg).connect(dry);
    b.start(t);
    b.stop(end + 0.3);
  }

  // Piano: arpeggios (eighths), sparse before the lift, with a melody on top after it.
  const piano = (t: number, midi: number, vel: number, len = 2.2) => {
    if (t >= secs) return;
    const f = midiHz(midi);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(vel, t + 0.006);
    g.gain.exponentialRampToValueAtTime(vel * 0.25, t + 0.4);
    g.gain.exponentialRampToValueAtTime(0.0001, t + len);
    const lp = ctx.createBiquadFilter();
    lp.type = "lowpass";
    lp.frequency.value = 1800 + vel * 6000;
    for (const [mult, amp, type] of [
      [1, 1, "triangle"],
      [2, 0.35, "sine"],
      [3, 0.12, "sine"],
      [4.01, 0.06, "sine"],
    ] as [number, number, OscillatorType][]) {
      const o2 = ctx.createOscillator();
      o2.type = type;
      o2.frequency.value = f * mult;
      o2.detune.value = (r() - 0.5) * 6;
      const a = ctx.createGain();
      a.gain.value = amp;
      o2.connect(a).connect(lp);
      o2.start(t);
      o2.stop(t + len + 0.05);
    }
    lp.connect(g);
    send(g, 0.7);
  };
  for (let t = 0; t < secs; t += beat / 2) {
    const ch = chordAt(t);
    const step = Math.round(t / (beat / 2));
    const after = t >= lift;
    const play = after ? true : step % 2 === 0 || r() < 0.25;
    if (!play) continue;
    const pattern = [0, 1, 2, 1, 2, 3, 2, 1];
    const idx = pattern[step % pattern.length]! % ch.length;
    const oct = idx === 0 && step % 8 === 0 ? 12 : 12 + (pattern[step % pattern.length]! >= ch.length ? 12 : 0);
    piano(t + (r() - 0.5) * 0.012, root + oct + ch[idx]!, (after ? 0.11 : 0.075) * (0.8 + r() * 0.3));
  }
  // Melody after the lift: long notes from the chord tones, moving stepwise.
  if (lift < secs) {
    let prev = 24 + plan.after[0]![2]!;
    for (let t = lift; t < secs - 1; t += beat * 2) {
      const ch = chordAt(t).map((n) => 24 + n);
      const cands = ch.flatMap((n) => [n, n + 12]).filter((n) => Math.abs(n - prev) <= 5);
      const note = cands.length ? cands[Math.floor(r() * cands.length)]! : ch[ch.length - 1]!;
      piano(t, root + note, 0.13, 3);
      prev = note;
    }
    // String swell into the lift.
    for (const n of plan.after[0]!) {
      const osc = ctx.createOscillator();
      osc.type = "sawtooth";
      osc.frequency.value = midiHz(root + 12 + n);
      const lp = ctx.createBiquadFilter();
      lp.type = "lowpass";
      lp.frequency.value = 2200;
      const g = ctx.createGain();
      g.gain.setValueAtTime(0, Math.max(0, lift - 4));
      g.gain.linearRampToValueAtTime(0.03, lift);
      g.gain.linearRampToValueAtTime(0.012, Math.min(secs, lift + bar * 2));
      g.gain.linearRampToValueAtTime(0, Math.min(secs, lift + bar * 4));
      osc.connect(lp).connect(g);
      send(g, 0.9);
      osc.start(Math.max(0, lift - 4));
      osc.stop(Math.min(secs, lift + bar * 4) + 0.1);
    }
    // Soft pulse (kick + shaker) after the lift for drive.
    const nb = noiseBuffer(ctx, 0.2, 9);
    for (let t = lift; t < secs - 1.5; t += beat) {
      const k = ctx.createOscillator();
      k.frequency.setValueAtTime(110, t);
      k.frequency.exponentialRampToValueAtTime(42, t + 0.18);
      const kg = ctx.createGain();
      kg.gain.setValueAtTime(0.28, t);
      kg.gain.exponentialRampToValueAtTime(0.001, t + 0.35);
      k.connect(kg).connect(dry);
      k.start(t);
      k.stop(t + 0.4);
      const sh = ctx.createBufferSource();
      sh.buffer = nb;
      const hp = ctx.createBiquadFilter();
      hp.type = "highpass";
      hp.frequency.value = 7000;
      const sg = ctx.createGain();
      const st = t + beat / 2;
      sg.gain.setValueAtTime(0.05, st);
      sg.gain.exponentialRampToValueAtTime(0.001, st + 0.08);
      sh.connect(hp).connect(sg).connect(dry);
      sh.start(st);
    }
  }
  return toWav(await ctx.startRendering());
}

/** Environmental bed (river, wind, city, rain, night). */
export async function ambience(type: Ambience, seconds: number, seed = 3): Promise<Blob> {
  const secs = Math.max(1, Math.min(300, seconds));
  const ctx = new OfflineAudioContext(2, Math.ceil(SR * secs), SR);
  const out = ctx.createGain();
  out.connect(ctx.destination);
  out.gain.setValueAtTime(0, 0);
  out.gain.linearRampToValueAtTime(1, Math.min(1.5, secs / 4));
  out.gain.setValueAtTime(1, Math.max(0, secs - 1.5));
  out.gain.linearRampToValueAtTime(0, secs);
  const loop = (brown: boolean) => {
    const s = ctx.createBufferSource();
    s.buffer = noiseBuffer(ctx, Math.min(secs, 8), seed + (brown ? 1 : 2), brown);
    s.loop = true;
    s.start(0);
    return s;
  };
  const lfo = (rate: number, depth: number, target: AudioParam) => {
    const o = ctx.createOscillator();
    o.frequency.value = rate;
    const g = ctx.createGain();
    g.gain.value = depth;
    o.connect(g).connect(target);
    o.start(0);
  };
  if (type === "river" || type === "rain") {
    const src = loop(false);
    const bp = ctx.createBiquadFilter();
    bp.type = type === "river" ? "bandpass" : "highpass";
    bp.frequency.value = type === "river" ? 700 : 2500;
    bp.Q.value = 0.6;
    const g = ctx.createGain();
    g.gain.value = type === "river" ? 0.22 : 0.16;
    lfo(0.23, 250, bp.frequency);
    src.connect(bp).connect(g).connect(out);
    const low = loop(true);
    const lg = ctx.createGain();
    lg.gain.value = type === "river" ? 0.5 : 0.2;
    low.connect(lg).connect(out);
    // Water trickles / drips.
    const r = rng(seed);
    const drips = type === "river" ? secs * 3 : secs * 12;
    for (let i = 0; i < drips; i++) {
      const t = r() * secs;
      const o = ctx.createOscillator();
      const f = 600 + r() * 1400;
      o.frequency.setValueAtTime(f, t);
      o.frequency.exponentialRampToValueAtTime(f * 1.8, t + 0.05);
      const dg = ctx.createGain();
      dg.gain.setValueAtTime(0.02 + r() * 0.02, t);
      dg.gain.exponentialRampToValueAtTime(0.0005, t + 0.07);
      o.connect(dg).connect(out);
      o.start(t);
      o.stop(t + 0.08);
    }
  } else if (type === "wind") {
    const src = loop(false);
    const bp = ctx.createBiquadFilter();
    bp.type = "bandpass";
    bp.frequency.value = 450;
    bp.Q.value = 1.4;
    lfo(0.11, 220, bp.frequency);
    const g = ctx.createGain();
    g.gain.value = 0.2;
    lfo(0.07, 0.12, g.gain);
    src.connect(bp).connect(g).connect(out);
  } else {
    // City / night: low rumble, distant traffic swells, crickets at night.
    const low = loop(true);
    const lp = ctx.createBiquadFilter();
    lp.type = "lowpass";
    lp.frequency.value = type === "city" ? 300 : 160;
    const lg = ctx.createGain();
    lg.gain.value = type === "city" ? 0.5 : 0.25;
    lfo(0.05, 0.15, lg.gain);
    low.connect(lp).connect(lg).connect(out);
    if (type === "night") {
      const r = rng(seed);
      for (let t = 0.3; t < secs; t += 0.9 + r() * 0.6) {
        for (let k = 0; k < 3; k++) {
          const o = ctx.createOscillator();
          o.frequency.value = 4300 + r() * 300;
          const g = ctx.createGain();
          const s = t + k * 0.07;
          g.gain.setValueAtTime(0, s);
          g.gain.linearRampToValueAtTime(0.012, s + 0.01);
          g.gain.linearRampToValueAtTime(0, s + 0.05);
          o.connect(g).connect(out);
          o.start(s);
          o.stop(s + 0.06);
        }
      }
    }
  }
  return toWav(await ctx.startRendering());
}

export function toWav(b: AudioBuffer): Blob {
  const ch = Math.min(2, b.numberOfChannels);
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
  const data = [b.getChannelData(0), b.getChannelData(ch > 1 ? 1 : 0)];
  let o = 44;
  for (let i = 0; i < n; i++)
    for (let c = 0; c < ch; c++) {
      const s = Math.max(-1, Math.min(1, data[c]![i]!));
      v.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true);
      o += 2;
    }
  return new Blob([buf], { type: "audio/wav" });
}
