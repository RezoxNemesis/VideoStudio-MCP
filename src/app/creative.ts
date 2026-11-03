/**
 * Creative director tools: one-call building blocks that turn a plain clip into an edit.
 *
 *  - Auto camera (virtual multicam): one static take becomes a cut-to-the-beat sequence of shots — wide, medium,
 *    close-up, extreme close-up, rule-of-thirds left/right, Dutch tilts — framed on the performer's face (from the
 *    person matte when there is one) and mathematically guaranteed never to show black corners.
 *  - Text motion: entrance and exit transitions for any title (write-on, rise, pop, slide, blur, drop...).
 *  - Handwritten lyrics: timed lines written in ink like a notebook; older lines float up and dissolve as new ones
 *    arrive; optionally behind the singer.
 *  - Doodles: hand-drawn ink graphics (heart, loop, underline, arrow, emphasis marks, speed lines, stars, spiral,
 *    music notes, sheet-music staff, crown, scribble), placed anywhere or around the performer's head.
 */
import * as ops from "../core/ops";
import { newTrack, type Clip, type Ease, type Project, type TextStyle } from "../core/model";
import type { MotionEl } from "../anim/motion";

/* ------------------------------------------------------------------ framing math ------------------------------------------------------------------ */

export interface Framing {
  s: number;
  x: number;
  y: number;
  r: number;
}

/** Does a clip that fills the frame at scale s, offset (x,y) px and rotation r° still cover every frame corner? */
export function covers(f: Framing, W: number, H: number): boolean {
  const th = (f.r * Math.PI) / 180;
  const c = Math.cos(th),
    s = Math.sin(th);
  for (const cx of [-W / 2, W / 2])
    for (const cy of [-H / 2, H / 2]) {
      const dx = cx - f.x,
        dy = cy - f.y;
      const u = (c * dx + s * dy) / f.s,
        v = (-s * dx + c * dy) / f.s;
      if (Math.abs(u) > W / 2 + 0.5 || Math.abs(v) > H / 2 + 0.5) return false;
    }
  return true;
}

/** Nearest framing that covers the frame: pull the offset towards centre, and only if needed zoom in a little. */
export function safeFraming(f: Framing, W: number, H: number): Framing {
  if (covers(f, W, H)) return f;
  let lo = 0,
    hi = 1;
  if (covers({ ...f, x: 0, y: 0 }, W, H)) {
    for (let i = 0; i < 20; i++) {
      const m = (lo + hi) / 2;
      if (covers({ ...f, x: f.x * m, y: f.y * m }, W, H)) lo = m;
      else hi = m;
    }
    return { ...f, x: f.x * lo, y: f.y * lo };
  }
  let s = f.s;
  while (!covers({ ...f, s, x: 0, y: 0 }, W, H) && s < 4) s += 0.01;
  return safeFraming({ ...f, s }, W, H);
}

/** Head point of the person in a matte frame (fractions of the frame): centre of the top of the silhouette. */
export function headPoint(alpha: Uint8Array, w: number, h: number): { x: number; y: number; found: boolean } {
  let top = -1;
  for (let y = 0; y < h && top < 0; y += 1)
    for (let x = 0; x < w; x += 2)
      if (alpha[y * w + x]! > 140) {
        top = y;
        break;
      }
  if (top < 0) return { x: 0.5, y: 0.22, found: false };
  // The head: the silhouette from its top down ~9% of the frame height.
  const band = Math.max(4, Math.round(h * 0.09));
  let sx = 0,
    n = 0;
  for (let y = top; y < Math.min(h, top + band); y++)
    for (let x = 0; x < w; x += 2)
      if (alpha[y * w + x]! > 140) {
        sx += x;
        n++;
      }
  return { x: n ? sx / n / w : 0.5, y: Math.min(0.9, (top + band * 0.6) / h), found: true };
}

/* ------------------------------------------------------------------ auto camera ------------------------------------------------------------------ */

export type ShotKind = "wide" | "medium" | "close" | "ecu" | "left" | "right" | "tiltL" | "tiltR" | "low";
/** Scale, where the face sits on screen (fractions), tilt in degrees. */
export const SHOTS: Record<ShotKind, { s: number; fx: number; fy: number; r: number; push: number }> = {
  wide: { s: 1.03, fx: 0.5, fy: -1, r: 0, push: 0.06 },
  medium: { s: 1.3, fx: 0.5, fy: 0.3, r: 0, push: 0.04 },
  close: { s: 1.58, fx: 0.5, fy: 0.36, r: 0, push: 0.06 },
  ecu: { s: 1.9, fx: 0.5, fy: 0.42, r: 0, push: 0.05 },
  left: { s: 1.38, fx: 0.64, fy: 0.3, r: -1.5, push: 0.03 },
  right: { s: 1.38, fx: 0.36, fy: 0.3, r: 1.5, push: 0.03 },
  tiltL: { s: 1.45, fx: 0.5, fy: 0.32, r: -4, push: 0.03 },
  tiltR: { s: 1.45, fx: 0.5, fy: 0.32, r: 4, push: 0.03 },
  low: { s: 1.35, fx: 0.5, fy: 0.2, r: 0, push: 0.05 },
};
export const SHOT_PATTERNS: Record<string, ShotKind[]> = {
  performance: ["wide", "close", "left", "right", "ecu", "medium", "tiltL", "close", "wide", "right", "ecu", "tiltR"],
  calm: ["wide", "medium", "close", "medium", "left", "close", "right", "medium"],
  hype: ["close", "tiltL", "ecu", "tiltR", "left", "right", "ecu", "wide", "close", "low"],
};

export interface CameraShot {
  t0: number;
  t1: number;
  kind: ShotKind;
  a: Framing;
  b: Framing;
}

/**
 * Plan shots between cut times. `head(tSource)` gives the face position (frame fractions) when known.
 * Every framing is checked against the frame corners (and fixed), so there are never black edges.
 */
export function planShots(o: { W: number; H: number; cuts: number[]; start: number; end: number; pattern?: ShotKind[]; head?: (t: number) => { x: number; y: number } | null }): CameraShot[] {
  const pat = o.pattern?.length ? o.pattern : SHOT_PATTERNS.performance!;
  const times = [o.start, ...o.cuts.filter((t) => t > o.start + 0.25 && t < o.end - 0.25).sort((a, b) => a - b), o.end];
  const shots: CameraShot[] = [];
  for (let i = 0; i < times.length - 1; i++) {
    const t0 = times[i]!,
      t1 = times[i + 1]!;
    const kind = pat[i % pat.length]!;
    const sh = SHOTS[kind];
    const mk = (t: number, s: number): Framing => {
      const hd = o.head?.(t) ?? { x: 0.5, y: 0.22 };
      // Put the face at (fx, fy) on screen: screen = centre + (src - 0.5)·size·s + offset.
      const x = (sh.fx - 0.5) * o.W - (hd.x - 0.5) * o.W * s;
      const y = sh.fy < 0 ? 0 : (sh.fy - 0.5) * o.H - (hd.y - 0.5) * o.H * s;
      return safeFraming({ s, x, y, r: sh.r }, o.W, o.H);
    };
    const drift = i % 2 ? 1 : -1;
    const a = mk(t0 + 0.05, sh.s);
    let b = mk(Math.max(t0, t1 - 0.05), sh.s * (1 + sh.push));
    b = safeFraming({ ...b, r: sh.r * 0.7, x: b.x + drift * o.W * 0.01 }, o.W, o.H);
    shots.push({ t0, t1, kind, a, b });
  }
  return shots;
}

/** Write a shot plan onto clips as keyframes (replacing their scale/x/y/rotation animation), with cut accents. */
export function applyShots(p: Project, ids: string[], shots: CameraShot[], o: { accent?: number; hits?: number[] } = {}): void {
  const acc = o.accent ?? 1;
  for (const id of ids) {
    const c = ops.getClip(p, id);
    for (const pr of ["scale", "x", "y", "rotation"] as const) c.transform[pr] = pr === "scale" ? 1 : 0;
    c.fx = { ...(c.fx ?? {}), zoomBlur: 0, rgbSplit: 0 };
    const key = (prop: string, t: number, v: number, e: Ease = "linear") => {
      if (t < c.start - 1e-6 || t > c.start + c.dur + 1e-6) return;
      ops.keyframe(p, id, prop, t, v, e);
    };
    for (const sh of shots) {
      const tb = Math.max(sh.t0, sh.t1 - 0.02);
      key("scale", sh.t0, sh.a.s);
      key("scale", tb, sh.b.s);
      key("x", sh.t0, sh.a.x);
      key("x", tb, sh.b.x);
      key("y", sh.t0, sh.a.y);
      key("y", tb, sh.b.y);
      key("rotation", sh.t0, sh.a.r);
      key("rotation", tb, sh.b.r);
      if (acc > 0 && sh.t0 > c.start + 0.01) {
        const hit = o.hits?.some((h) => Math.abs(h - sh.t0) < 0.06);
        key("fx.zoomBlur", sh.t0 - 0.01, 0);
        key("fx.zoomBlur", sh.t0, (hit ? 0.4 : 0.22) * acc, "out");
        key("fx.zoomBlur", sh.t0 + 0.16, 0);
        if (hit) {
          key("fx.rgbSplit", sh.t0 - 0.01, 0);
          key("fx.rgbSplit", sh.t0, 10 * acc, "out");
          key("fx.rgbSplit", sh.t0 + 0.2, 0);
        }
      }
    }
  }
}

/* ------------------------------------------------------------------ text motion ------------------------------------------------------------------ */

export const TEXT_IN = ["none", "write", "typewriter", "rise", "pop", "blurIn", "slideLeft", "slideRight", "drop", "scramble", "track", "fade", "zoomOut", "wave"] as const;
export const TEXT_OUT = ["none", "rise", "blur", "fade", "slideLeft", "slideRight", "drop", "zoom", "shrink"] as const;
export type TextIn = (typeof TEXT_IN)[number];
export type TextOut = (typeof TEXT_OUT)[number];

/** Entrance and exit transitions for a text (or any) clip. Keys are added on top of its resting position. */
export function textMotion(p: Project, id: string, o: { in?: TextIn; out?: TextOut; inDur?: number; outDur?: number }): void {
  const c = ops.getClip(p, id);
  const W = p.width,
    H = p.height;
  const t0 = c.start,
    t1 = c.start + c.dur;
  const base = (prop: "x" | "y" | "opacity" | "scale") => {
    const v = c.transform[prop];
    return typeof v === "number" ? v : (v?.[0]?.v ?? (prop === "opacity" || prop === "scale" ? 1 : 0));
  };
  const x0 = base("x"),
    y0 = base("y"),
    o0 = base("opacity"),
    s0 = base("scale");
  const inD = Math.min(o.inDur ?? 0.5, c.dur * 0.45),
    outD = Math.min(o.outDur ?? 0.35, c.dur * 0.45);
  const k = (prop: string, t: number, v: number, e: Ease = "linear") => ops.keyframe(p, id, prop, t, v, e);
  const anim: Partial<Record<TextIn, TextStyle["animate"]>> = { write: "write", typewriter: "typewriter", pop: "pop", blurIn: "blurIn", drop: "drop", scramble: "scramble", track: "track", fade: "fade", zoomOut: "zoomOut", wave: "wave", rise: "rise" };
  if (o.in && o.in !== "none") {
    if (c.text && anim[o.in]) {
      c.text.animate = anim[o.in]!;
      c.text.animateDur = Math.max(0.3, o.inDur ?? c.text.animateDur ?? 0.8);
    }
    if (o.in === "rise" || o.in === "write") {
      k("y", t0, y0 + H * 0.015);
      k("y", t0 + inD, y0, "out");
    }
    if (o.in === "slideLeft" || o.in === "slideRight") {
      const d = (o.in === "slideLeft" ? 1 : -1) * W * 0.9;
      k("x", t0, x0 + d);
      k("x", t0 + inD, x0, "out");
      k("opacity", t0, 0);
      k("opacity", t0 + inD * 0.4, o0);
    }
  }
  if (o.out && o.out !== "none") {
    const a = t1 - outD;
    const hold = (prop: "x" | "y" | "opacity" | "scale", v: number) => k(prop, a, v);
    switch (o.out) {
      case "rise":
        hold("y", y0);
        k("y", t1, y0 - H * 0.04, "in");
        hold("opacity", o0);
        k("opacity", t1, 0, "in");
        k("blur", a, 0);
        k("blur", t1, 14, "in");
        break;
      case "blur":
        hold("opacity", o0);
        k("opacity", t1, 0, "in");
        k("blur", a, 0);
        k("blur", t1, 26, "in");
        hold("scale", s0);
        k("scale", t1, s0 * 1.06, "in");
        break;
      case "fade":
        hold("opacity", o0);
        k("opacity", t1, 0, "in");
        break;
      case "slideLeft":
      case "slideRight":
        hold("x", x0);
        k("x", t1, x0 + (o.out === "slideLeft" ? -1 : 1) * W * 1.1, "in");
        break;
      case "drop":
        hold("y", y0);
        k("y", t1, y0 + H * 0.35, "in");
        hold("opacity", o0);
        k("opacity", t1, 0, "in");
        break;
      case "zoom":
        hold("scale", s0);
        k("scale", t1, s0 * 2.4, "in");
        hold("opacity", o0);
        k("opacity", t1, 0, "in");
        break;
      case "shrink":
        hold("scale", s0);
        k("scale", t1, s0 * 0.2, "in");
        hold("opacity", o0);
        k("opacity", t1, 0, "in");
        break;
    }
  }
}

/* ------------------------------------------------------------------ tracks ------------------------------------------------------------------ */

const busy = (t: Project["tracks"][number], a: number, b: number) => t.clips.some((c) => c.start < b - 1e-6 && c.start + c.dur > a + 1e-6);

/** A free video track directly beneath `aboveTrackId` (inserted there if needed), for layers that sit behind a cut-out. */
export function trackBelow(p: Project, aboveTrackId: string, start: number, dur: number): string {
  const idx = p.tracks.findIndex((t) => t.id === aboveTrackId);
  // Tracks are listed top first; "below" is a larger index. Reuse a free video track between `above` and the next busy one.
  for (let i = idx + 1; i < p.tracks.length; i++) {
    const t = p.tracks[i]!;
    if (t.kind !== "video") continue;
    if (!busy(t, start, start + dur) && !t.locked && !t.clips.some((c) => c.subject)) return t.id;
    break;
  }
  const t = newTrack("video", `Behind ${p.tracks.filter((x) => x.kind === "video").length + 1}`);
  p.tracks.splice(idx + 1, 0, t);
  return t.id;
}

/** A free video track above everything that is busy in the range. */
export function trackOnTop(p: Project, start: number, dur: number): string {
  const vids = p.tracks.filter((t) => t.kind === "video");
  const highest = vids.findIndex((t) => busy(t, start, start + dur));
  if (highest < 0) return ops.trackFor(p, "video", start, dur).id;
  for (let i = highest - 1; i >= 0; i--) if (!busy(vids[i]!, start, start + dur) && !vids[i]!.locked) return vids[i]!.id;
  const t = newTrack("video", `V${vids.length + 1}`);
  p.tracks.unshift(t);
  return t.id;
}

/** The cut-out copy of a subject clip (made once, on top), for things that go behind the person. */
export function cutoutFor(p: Project, clipId: string): Clip {
  const src = ops.getClip(p, clipId);
  for (const t of p.tracks)
    for (const c of t.clips)
      if (c.assetId === src.assetId && c.subject?.mode === "cutout" && c.start <= src.start + 1e-3 && c.start + c.dur >= src.start + src.dur - 1e-3) return c;
  const cut = ops.duplicateClip(p, src.id);
  ops.moveClip(p, cut.id, src.start, trackOnTop(p, src.start, src.dur));
  cut.subject = { mode: "cutout", bg: "transparent" };
  cut.name = `${src.name} (cut-out)`;
  cut.volume = 0;
  return cut;
}

/* ------------------------------------------------------------------ handwritten lyrics ------------------------------------------------------------------ */

export interface LyricIn {
  start: number;
  end?: number;
  text: string;
}
export const LYRIC_STYLES: Record<string, { font: string; weight: number; size: number; color: string; shadow: number }> = {
  handwritten: { font: '"Caveat", cursive', weight: 700, size: 112, color: "#fff3e0", shadow: 16 },
  marker: { font: '"Permanent Marker", cursive', weight: 400, size: 92, color: "#ffffff", shadow: 14 },
  signature: { font: '"Great Vibes", cursive', weight: 400, size: 130, color: "#fff3e0", shadow: 18 },
  hindi: { font: '"Kalam", cursive', weight: 700, size: 96, color: "#fff3e0", shadow: 14 },
  serif: { font: '"Cormorant Garamond", serif', weight: 700, size: 100, color: "#f6efe4", shadow: 16 },
};

/**
 * Lines are written in ink as they are sung; the page keeps `keep` lines, older ones float up and dissolve.
 * Placed behind the singer when `behind` is a subject clip (uses its cut-out copy).
 */
export function lyricsWrite(p: Project, o: { lines: LyricIn[]; style?: string; y?: number; gap?: number; keep?: number; behind?: string; color?: string; size?: number; underline?: boolean; trackId?: string }): { ids: string[]; underline?: MotionEl[] } {
  const st = { ...(LYRIC_STYLES[o.style ?? "handwritten"] ?? LYRIC_STYLES.handwritten!) };
  if (o.color) st.color = o.color;
  if (o.size) st.size = o.size;
  const H = p.height;
  const lines = [...o.lines].sort((a, b) => a.start - b.start);
  const keep = Math.max(1, o.keep ?? 2);
  const gap = o.gap ?? (st.size * 1.25) / 1920;
  const yTop = o.y ?? 0.12;
  const ids: string[] = [];
  const cut = o.behind ? cutoutFor(p, o.behind) : null;
  const cutTrack = cut ? p.tracks.find((t) => t.clips.includes(cut))!.id : null;
  const ink: MotionEl[] = [];
  // Lifetime of each line: until `keep` lines later push it off the page, or its own end.
  const ends = lines.map((ln, i) => {
    const e = Math.min(ln.end ?? Infinity, lines[i + keep]?.start ?? Infinity);
    return Math.max(ln.start + 0.6, Number.isFinite(e) ? e : ln.start + 4);
  });
  // Slot (row) of line i at time t: how many earlier lines are still on the page.
  const slotAt = (i: number, t: number) => lines.filter((l, j) => j < i && l.start <= t + 1e-6 && ends[j]! > t + 1e-6).length;
  const yOf = (sl: number) => (yTop + Math.min(keep - 1, sl) * gap - 0.5) * H;
  lines.forEach((ln, i) => {
    const end = ends[i]!;
    const dur = end - ln.start;
    const track = o.trackId ?? (cutTrack ? trackBelow(p, cutTrack, ln.start, dur) : trackOnTop(p, ln.start, dur));
    const c = ops.addClip(p, { text: ln.text, start: ln.start, dur, trackId: track });
    c.name = `Lyric: ${ln.text.slice(0, 24)}`;
    c.text = { ...c.text!, font: st.font, weight: st.weight, size: st.size, color: st.color, stroke: "", strokeWidth: 0, shadow: st.shadow, glow: "", bg: "", align: "center", tracking: 0, animate: "write", animateDur: Math.max(0.5, Math.min(1.4, ln.text.length * 0.055)) };
    const slot = slotAt(i, ln.start);
    ops.keyframe(p, c.id, "y", ln.start, yOf(slot) + H * 0.015, "linear");
    ops.keyframe(p, c.id, "y", ln.start + 0.45, yOf(slot), "out");
    // Older lines leaving: this one moves up into their place.
    let cur = slot;
    const events = ends.filter((e, j) => j < i && e > ln.start + 0.5 && e < end - 0.1).sort((x, y) => x - y);
    for (const e of [...new Set(events)]) {
      const next = slotAt(i, e + 0.01);
      if (next < cur) {
        ops.keyframe(p, c.id, "y", e, yOf(cur), "linear");
        ops.keyframe(p, c.id, "y", e + 0.35, yOf(next), "out");
        cur = next;
      }
    }
    textMotion(p, c.id, { out: "rise", outDur: 0.32 });
    ids.push(c.id);
    if (o.underline) {
      const w = Math.min(0.42, ln.text.length * 0.017);
      const y = yTop + Math.min(keep - 1, slotAt(i, ln.start)) * gap + gap * 0.42;
      ink.push({ k: "ink", t0: ln.start + 0.35, t1: end, pts: [[0.5 - w, y + 0.004], [0.5, y + 0.009], [0.5 + w, y]], s: 0.006, n: 0.4, out: 0.3, color: st.color, seed: i + 1 });
    }
  });
  return { ids, underline: ink.length ? ink : undefined };
}

/* ------------------------------------------------------------------ doodles ------------------------------------------------------------------ */

export const DOODLES = ["heart", "circle", "underline", "arrow", "emphasis", "speedlines", "stars", "spiral", "notes", "staff", "crown", "scribble", "sparkle"] as const;
export type Doodle = (typeof DOODLES)[number];

/**
 * Hand-drawn ink graphic as motion-design elements, centred at (x, y) (frame fractions), size = fraction of width.
 * t0/t1 are local seconds inside the motion clip.
 */
export function doodleEls(type: Doodle, o: { x: number; y: number; size: number; t0: number; t1: number; color: string; aspect: number; seed?: number; draw?: number }): MotionEl[] {
  const { x, y, size: s, t0, t1, color } = o;
  const sy = s * o.aspect; // keep shapes round on tall frames (y is a fraction of the height)
  const ink = (pts: [number, number][], w = 0.01, n = o.draw ?? 0.35, d = 0, seed = 1): MotionEl => ({ k: "ink", t0: t0 + d, t1, pts, s: w, n, out: 0.3, color, seed: (o.seed ?? 0) + seed });
  const P = (u: number, v: number): [number, number] => [x + u * s, y + v * sy];
  switch (type) {
    case "heart": {
      const pts: [number, number][] = [[0, -0.12], [0.24, -0.4], [0.48, -0.34], [0.52, -0.06], [0.32, 0.22], [0, 0.48], [-0.32, 0.22], [-0.52, -0.06], [-0.48, -0.34], [-0.24, -0.4], [0, -0.12]];
      return [ink(pts.map(([u, v]) => P(u, v)), 0.011, o.draw ?? 1.0)];
    }
    case "circle": {
      const pts: [number, number][] = [];
      for (let i = 0; i <= 16; i++) {
        const a = -1.9 + ((Math.PI * 2 * 1.12) / 16) * i;
        const r = 1 + 0.06 * Math.sin(i * 1.7);
        pts.push(P(Math.cos(a) * 0.5 * r, Math.sin(a) * 0.42 * r));
      }
      return [ink(pts, 0.009, o.draw ?? 0.4)];
    }
    case "underline":
      return [ink([P(-0.5, 0.02), P(0, 0.06), P(0.5, -0.01), P(0.55, -0.06)], 0.008, o.draw ?? 0.35)];
    case "arrow":
      return [ink([P(-0.5, 0.3), P(-0.15, 0.1), P(0.3, -0.15)], 0.009, 0.3), ink([P(0.1, -0.2), P(0.32, -0.16), P(0.24, 0.04)], 0.009, 0.15, 0.3, 2)];
    case "emphasis":
      return [ink([P(-0.3, 0), P(-0.42, -0.35)], 0.012, 0.15), ink([P(0, -0.08), P(0, -0.5)], 0.012, 0.15, 0.04, 2), ink([P(0.3, 0), P(0.42, -0.35)], 0.012, 0.15, 0.08, 3)];
    case "speedlines": {
      const els: MotionEl[] = [];
      [-1, 1].forEach((side, si) =>
        [-0.3, 0, 0.3].forEach((v, i) => els.push(ink([P(side * 0.95, v * 1.1), P(side * 0.55, v * 0.8)], 0.011, 0.14, i * 0.03, si * 3 + i + 1))),
      );
      return els;
    }
    case "stars":
      return [
        { k: "star4", t0, t1, x: x - s * 0.4, y: y - sy * 0.2, s: s * 0.12, color, out: 0.3 },
        { k: "star4", t0: t0 + 0.12, t1, x: x + s * 0.42, y: y - sy * 0.35, s: s * 0.09, color, out: 0.3 },
        { k: "star4", t0: t0 + 0.24, t1, x: x + s * 0.1, y: y + sy * 0.3, s: s * 0.07, color, out: 0.3 },
      ];
    case "spiral": {
      const pts: [number, number][] = [];
      for (let i = 0; i <= 26; i++) {
        const a = i * 0.55,
          r = 0.04 + i * 0.018;
        pts.push(P(Math.cos(a) * r, Math.sin(a) * r * 0.9));
      }
      return [ink(pts, 0.008, o.draw ?? 0.7)];
    }
    case "notes":
      return [
        { k: "note", t0, t1, x: x - s * 0.35, y: y, s: s * 0.22, text: "♪", color, seed: 1, out: 0.4 },
        { k: "note", t0: t0 + 0.3, t1, x: x + s * 0.35, y: y - sy * 0.1, s: s * 0.2, text: "♫", color, seed: 2, out: 0.4 },
        { k: "note", t0: t0 + 0.6, t1, x: x, y: y + sy * 0.15, s: s * 0.16, text: "♪", color, seed: 3, out: 0.4 },
      ];
    case "staff":
      return [{ k: "staff", t0, t1, y, s: 0.017, n: o.draw ?? 1.0, color, out: 0.3, seed: o.seed }];
    case "crown":
      return [ink([P(-0.4, 0.15), P(-0.45, -0.25), P(-0.2, 0), P(0, -0.35), P(0.2, 0), P(0.45, -0.25), P(0.4, 0.15), P(-0.4, 0.15)], 0.01, o.draw ?? 0.6)];
    case "scribble": {
      const pts: [number, number][] = [];
      for (let i = 0; i <= 18; i++) pts.push(P(-0.45 + i * 0.05, Math.sin(i * 2.3) * 0.18 + Math.cos(i * 1.1) * 0.05));
      return [ink(pts, 0.008, o.draw ?? 0.45)];
    }
    case "sparkle":
      return [
        ink([P(0, -0.4), P(0, 0.4)], 0.008, 0.15),
        ink([P(-0.4, 0), P(0.4, 0)], 0.008, 0.15, 0.06, 2),
        ink([P(-0.22, -0.22), P(0.22, 0.22)], 0.006, 0.12, 0.12, 3),
        ink([P(0.22, -0.22), P(-0.22, 0.22)], 0.006, 0.12, 0.16, 4),
      ];
  }
}

/** Where a source point (frame fractions) lands on screen for a clip at timeline time t (scale, x, y; rotation ignored). */
export function sourceToScreen(p: Project, c: Clip, t: number, pt: { x: number; y: number }, valueAt: (k: unknown, local: number, def: number) => number): { x: number; y: number } {
  const local = t - c.start;
  const s = valueAt(c.transform.scale, local, 1);
  const x = valueAt(c.transform.x, local, 0),
    y = valueAt(c.transform.y, local, 0);
  return { x: 0.5 + ((pt.x - 0.5) * p.width * s + x) / p.width, y: 0.5 + ((pt.y - 0.5) * p.height * s + y) / p.height };
}
