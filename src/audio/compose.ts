/**
 * On-device music and ambience. Everything is synthesised with an OfflineAudioContext (no samples, no downloads):
 * pads, piano, strings, bass and soft drums following a chord plan, with a "lift" where the score brightens,
 * plus river / wind / city / rain beds. Output is a 16-bit stereo WAV blob.
 */

export const MOODS = ["melancholy", "hopeful", "dark", "tense", "calm", "epic", "battle"] as const;
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
  battle: { before: [[0, 3, 7], [0, 3, 7], [-4, 0, 3], [-2, 2, 5]], after: [[0, 3, 7], [-4, 0, 3], [-7, -4, 0], [-2, 2, 5]], bpm: 140 },
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
  if (mood === "battle") {
    const nb = noiseBuffer(ctx, 0.5, 13);
    const hit = (t: number, f0: number, f1: number, dec: number, lvl: number) => {
      const o = ctx.createOscillator();
      o.frequency.setValueAtTime(f0, t);
      o.frequency.exponentialRampToValueAtTime(f1, t + dec * 0.6);
      const g = ctx.createGain();
      g.gain.setValueAtTime(lvl, t);
      g.gain.exponentialRampToValueAtTime(0.001, t + dec);
      o.connect(g).connect(dry);
      o.start(t);
      o.stop(t + dec + 0.05);
    };
    const noiseHit = (t: number, hp: number, dec: number, lvl: number, wet = 0.2) => {
      const src = ctx.createBufferSource();
      src.buffer = nb;
      const f = ctx.createBiquadFilter();
      f.type = "highpass";
      f.frequency.value = hp;
      const g = ctx.createGain();
      g.gain.setValueAtTime(lvl, t);
      g.gain.exponentialRampToValueAtTime(0.001, t + dec);
      src.connect(f).connect(g);
      send(g, wet);
      src.start(t);
      src.stop(t + dec + 0.05);
    };
    const start = Math.min(secs, 0);
    for (let t = start; t < secs - 0.3; t += beat / 2) {
      const step = Math.round(t / (beat / 2)) % 16;
      const hot = t >= lift;
      // Kick: 1, the "and" of 2, 3 (+ extra after the lift); snare on 2 and 4; hats on every eighth.
      if ([0, 3, 8, ...(hot ? [6, 11, 14] : [])].includes(step)) hit(t, 120, 40, 0.35, hot ? 0.55 : 0.45);
      if (step === 4 || step === 12) {
        hit(t, 220, 140, 0.12, 0.25);
        noiseHit(t, 1800, 0.18, hot ? 0.32 : 0.24, 0.35);
      }
      noiseHit(t, 8000, 0.04, step % 2 ? 0.05 : 0.08, 0.05);
      if (hot) noiseHit(t + beat / 4, 9000, 0.03, 0.04, 0.05);
      // Staccato string stabs on the chord.
      if (step % 2 === 0) {
        const ch = chordAt(t);
        const n = ch[step % 4 === 0 ? 0 : 2 % ch.length]!;
        const o = ctx.createOscillator();
        o.type = "sawtooth";
        o.frequency.value = midiHz(root + 12 + n);
        const lp = ctx.createBiquadFilter();
        lp.type = "lowpass";
        lp.frequency.value = hot ? 2600 : 1600;
        const g = ctx.createGain();
        g.gain.setValueAtTime(0, t);
        g.gain.linearRampToValueAtTime(hot ? 0.06 : 0.045, t + 0.01);
        g.gain.exponentialRampToValueAtTime(0.001, t + beat * 0.45);
        o.connect(lp).connect(g);
        send(g, 0.4);
        o.start(t);
        o.stop(t + beat * 0.5);
      }
    }
    // Tom fill into the lift.
    if (lift < secs) for (let i = 0; i < 6; i++) hit(lift - beat * 1.5 + i * (beat / 4), 180 - i * 18, 70, 0.25, 0.35);
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

export const SFX = ["whoosh", "punch", "kick", "impact", "clash", "slash", "rumble", "riser", "land", "block"] as const;
export type Sfx = (typeof SFX)[number];

/** One-shot sound effects for action scenes. */
export async function sfx(type: Sfx, seed = 1): Promise<Blob> {
  const len = { whoosh: 0.5, punch: 0.45, kick: 0.6, impact: 1.6, clash: 2.4, slash: 0.6, rumble: 2.5, riser: 2.2, land: 0.8, block: 0.4 }[type];
  const ctx = new OfflineAudioContext(2, Math.ceil(SR * len), SR);
  const out = ctx.createGain();
  out.connect(ctx.destination);
  const nb = noiseBuffer(ctx, len, seed);
  const noise = (filter: BiquadFilterType, f0: number, f1: number, q: number, lvl: number, a: number, d: number) => {
    const src = ctx.createBufferSource();
    src.buffer = nb;
    const f = ctx.createBiquadFilter();
    f.type = filter;
    f.Q.value = q;
    f.frequency.setValueAtTime(f0, 0);
    f.frequency.exponentialRampToValueAtTime(f1, Math.max(0.01, a + d * 0.8));
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, 0);
    g.gain.exponentialRampToValueAtTime(lvl, Math.max(0.002, a));
    g.gain.exponentialRampToValueAtTime(0.0001, a + d);
    src.connect(f).connect(g).connect(out);
    src.start(0);
  };
  const tone = (f0: number, f1: number, lvl: number, d: number, type: OscillatorType = "sine", at = 0) => {
    const o = ctx.createOscillator();
    o.type = type;
    o.frequency.setValueAtTime(f0, at);
    o.frequency.exponentialRampToValueAtTime(f1, at + d * 0.7);
    const g = ctx.createGain();
    g.gain.setValueAtTime(lvl, at);
    g.gain.exponentialRampToValueAtTime(0.0001, at + d);
    o.connect(g).connect(out);
    o.start(at);
    o.stop(at + d + 0.02);
  };
  switch (type) {
    case "whoosh":
      noise("bandpass", 400, 2400, 1.2, 0.6, 0.18, 0.3);
      break;
    case "slash":
      noise("bandpass", 1800, 7000, 2, 0.6, 0.08, 0.35);
      tone(2400, 1200, 0.08, 0.4, "sawtooth");
      break;
    case "punch":
      tone(140, 50, 0.9, 0.25);
      noise("lowpass", 3000, 300, 0.7, 0.7, 0.002, 0.12);
      break;
    case "kick":
      tone(110, 38, 1, 0.4);
      noise("lowpass", 2400, 200, 0.7, 0.8, 0.002, 0.2);
      noise("bandpass", 600, 2000, 1, 0.35, 0.05, 0.3);
      break;
    case "block":
      tone(320, 180, 0.5, 0.15, "triangle");
      noise("bandpass", 1200, 800, 3, 0.5, 0.002, 0.12);
      break;
    case "land":
      tone(90, 35, 0.9, 0.5);
      noise("lowpass", 1200, 150, 0.7, 0.6, 0.004, 0.6);
      break;
    case "impact":
      tone(80, 28, 1, 1.2);
      noise("lowpass", 6000, 200, 0.6, 0.9, 0.002, 1.1);
      noise("highpass", 3000, 6000, 0.7, 0.25, 0.002, 0.25);
      break;
    case "clash":
      tone(70, 25, 1, 1.8);
      noise("lowpass", 8000, 150, 0.6, 1, 0.002, 1.8);
      for (const [f, l] of [[880, 0.12], [1320, 0.08], [1975, 0.06]] as const) tone(f, f * 0.98, l, 2.2, "triangle");
      break;
    case "rumble":
      noise("lowpass", 180, 120, 0.8, 0.9, 0.6, 1.9);
      tone(45, 40, 0.5, 2.4);
      break;
    case "riser":
      noise("bandpass", 300, 6000, 1.5, 0.5, 2.0, 0.2);
      tone(200, 1600, 0.12, 2.2, "sawtooth");
      break;
  }
  return toWav(await ctx.startRendering());
}
