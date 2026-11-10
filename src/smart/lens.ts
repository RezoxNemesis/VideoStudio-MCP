/**
 * Audience Lens: looks at a timeline the way an audience and a platform probably would, before it is published.
 *
 * It PREDICTS; it does not know. Every number it gives is a heuristic built from the project data (and optional
 * measurements passed in by the caller), and every message is written as guidance, never as a promise of views or
 * likes. It needs no network, no AI model and no DOM, and it is deterministic: the same input always gives the same
 * report.
 *
 * Platform numbers (safe areas, lengths, frame rates, sizes) come from the platforms' own published guidance where
 * such guidance exists; the source is named next to each number in PLATFORMS. Where no official number exists, the
 * value is a clearly labelled conservative default ("DEFAULT").
 *
 * Fixes are emitted as real agent commands from src/agent/api.ts (clip.value, clip.set, clip.trim, clip.transition,
 * clip.keys) with the argument names those commands read.
 */
import { valueAt } from "../core/keyframes";
import { TRANSFORM_DEFAULTS, findAsset, projectDuration, type Clip, type Project, type TextStyle, type TransformProp } from "../core/model";

/* ---------------------------------------------------------------- types --------------------------------------------------------------- */

export type Platform = "instagram-reel" | "tiktok" | "youtube-shorts" | "youtube" | "general";

export interface LensContext {
  /** Default: guessed from the frame shape (9:16 → instagram-reel, 16:9 → youtube, anything else → general). */
  platform?: Platform;
  /** Timeline seconds, when the music was analysed. */
  beats?: number[];
  downbeats?: number[];
  bpm?: number;
  loudness?: { integratedLufs?: number; truePeakDb?: number; silences?: { start: number; end: number }[] };
  /** Optional picture samples: mean luma 0..1, and 0..1 difference to the previous sample. */
  frames?: { t: number; luma: number; diffPrev?: number }[];
  /** Optional 0..1 difference between the first and the last frame. */
  loopDiff?: number;
  /** Size of the whole text block in px at project scale. When absent, estimateTextSize() is used. */
  measureText?: (text: string, style: TextStyle) => { w: number; h: number };
}

export type Severity = "high" | "medium" | "low" | "good";

export interface Finding {
  id: string;
  area: "hook" | "pacing" | "text" | "safe-zone" | "sound" | "ending" | "technical";
  severity: Severity;
  title: string;
  detail: string;
  at?: number;
  clipIds?: string[];
  fix?: { label: string; commands: Record<string, unknown>[] };
}

export interface LensReport {
  platform: Platform;
  /** 0..100, 100 = nothing to improve. */
  score: number;
  verdict: string;
  areas: { area: Finding["area"]; score: number; title: string }[];
  findings: Finding[];
  /** Predicted share of viewers still watching, 0..1 (a heuristic, see attentionCurve). */
  attention: { t: number; v: number }[];
  facts: Record<string, number | string>;
}

export interface PlatformInfo {
  name: string;
  /** Expected frame shape, width / height. */
  aspect: number;
  /** Short-form vertical feed (stricter pacing and hook expectations, loops matter). */
  shortForm: boolean;
  /** Margins covered by the platform's own buttons and captions, as fractions of the frame width (left/right) and height (top/bottom). */
  safe: { top: number; bottom: number; left: number; right: number };
  safeNote: string;
  /** Longest video the platform takes in this format, seconds (undefined: no practical limit checked). */
  maxSeconds?: number;
  maxNote: string;
  /** Recommended frame size (portrait or landscape as the platform expects). */
  recommended: { w: number; h: number };
  /** Smallest short side the platform accepts or that is still sensible. */
  minShortSide: number;
  sizeNote: string;
  /** Frame rates the platform accepts or lists as common. */
  fps: { min: number; max: number };
  fpsNote: string;
}

/* ------------------------------------------------------------- platforms -------------------------------------------------------------- */

/** Share of the frame kept clear for text on platforms without a published safe zone: 5 % on every side.
 *  DEFAULT, not a platform number: it is the classic broadcast "title safe" convention (90 % of the frame). */
const DEFAULT_MARGIN = 0.05;

export const PLATFORMS: Record<Platform, PlatformInfo> = {
  "instagram-reel": {
    name: "Instagram Reels",
    aspect: 9 / 16,
    shortForm: true,
    // Meta, Instagram Reels video ad specs (facebook.com/business/ads-guide/update/video/instagram-reels) and
    // "About text overlays and the Safe Zone for ads in Stories and Reels" (facebook.com/business/help/980593475366490):
    // leave at least 14 % of the top, 35 % of the bottom and 6 % of each side free of text and logos.
    // (Meta publishes percentages only; the pages could not be fetched directly here, the wording was confirmed via
    // pages quoting them, e.g. jem.social/blog/instagram-reels-safe-zone.)
    safe: { top: 0.14, bottom: 0.35, left: 0.06, right: 0.06 },
    safeNote: "Meta's Reels guidance: keep the top 14 %, bottom 35 % and 6 % on each side free of text.",
    // Instagram Help Centre, "Record a reel on Instagram" (help.instagram.com/2720958398006062): "Record and edit videos up to 3 minutes".
    maxSeconds: 180,
    maxNote: "Instagram Help Centre: reels can be up to 3 minutes.",
    // DEFAULT: 1080×1920 is the common 9:16 Full HD size; Meta's 9:16 guidance (developers.facebook.com/blog/post/2024/11/07/unlock-the-power-of-reel-ads/) gives the shape, not a size we could verify.
    recommended: { w: 1080, h: 1920 },
    minShortSide: 720, // DEFAULT (no official minimum verified).
    sizeNote: "Reels are 9:16; 1080×1920 is a labelled default size, not a verified Meta number.",
    fps: { min: 23, max: 60 }, // DEFAULT (no official Instagram frame-rate range verified; same as TikTok's published range).
    fpsNote: "No published Instagram frame-rate range was found; 23–60 fps is a labelled default.",
  },
  tiktok: {
    name: "TikTok",
    aspect: 9 / 16,
    shortForm: true,
    // TikTok does not publish safe-zone numbers in text: Ads Manager offers downloadable overlay files
    // (ads.tiktok.com/help/article/tiktok-auction-in-feed-ads, "safe zone files"). These pixel values at 1080×1920
    // (top 130, bottom 484, left 44, right 140) are a reading of that official overlay with a full caption,
    // as reported by sprites.ai/ad-specs/tiktok/safe-zone and cadenus.io/resources/blog/tiktok-safe-zone.
    safe: { top: 130 / 1920, bottom: 484 / 1920, left: 44 / 1080, right: 140 / 1080 },
    safeNote: "TikTok's safe-zone overlay (Ads Manager download): about 130 px top, 484 px bottom, 44 px left, 140 px right at 1080×1920.",
    // TikTok for Developers, Content Posting API media transfer guide: "All TikTok creators can post 3-minute videos,
    // while some have access to post 5-minute or 10-minute videos."
    maxSeconds: 180,
    maxNote: "TikTok's developer guide: every creator can post 3-minute videos; some accounts can post up to 10 minutes.",
    // TikTok Ads Help, In-Feed Ads: vertical 9:16, ≥ 540×960 px. 1080×1920 recommended size is a DEFAULT.
    recommended: { w: 1080, h: 1920 },
    minShortSide: 540,
    sizeNote: "TikTok Ads Help: 9:16 at 540×960 or more (1080×1920 is a labelled default).",
    // TikTok for Developers, Content Posting API media transfer guide: "Minimum of 23 FPS", "Maximum of 60 FPS".
    fps: { min: 23, max: 60 },
    fpsNote: "TikTok's developer guide: 23 to 60 fps.",
  },
  "youtube-shorts": {
    name: "YouTube Shorts",
    aspect: 9 / 16,
    shortForm: true,
    // Google Ads Help, "About video ad specs" (support.google.com/google-ads/answer/13547298), vertical safe-zone diagram
    // at 1080×1920: top 288, bottom 672, left 48, right 192 px (read from the diagram/PNG template, as reported by
    // recruitmentads.com/free-tools/ad-mockup/youtube). It is Google's ad safe zone; Google publishes no separate
    // pixel safe zone for organic Shorts, so it is used as the closest official number.
    safe: { top: 288 / 1920, bottom: 672 / 1920, left: 48 / 1080, right: 192 / 1080 },
    safeNote: "Google's vertical video ad safe zone: about 288 px top, 672 px bottom, 48 px left, 192 px right at 1080×1920.",
    // YouTube Help, "Understand three-minute YouTube Shorts" (support.google.com/youtube/answer/15424877): Shorts up to three minutes.
    maxSeconds: 180,
    maxNote: "YouTube Help: Shorts can be up to three minutes.",
    // Google Ads Help 13547298: recommended 1080×1920 (vertical), also accepts 720×1280.
    recommended: { w: 1080, h: 1920 },
    minShortSide: 720,
    sizeNote: "Google's video specs: 1080×1920 recommended, 720×1280 accepted.",
    // YouTube Help, "YouTube recommended upload encoding settings" (support.google.com/youtube/answer/1722171):
    // common frame rates 24, 25, 30, 48, 50, 60 fps (others acceptable).
    fps: { min: 23.976, max: 60 },
    fpsNote: "YouTube Help: common frame rates are 24, 25, 30, 48, 50 and 60 fps.",
  },
  youtube: {
    name: "YouTube",
    aspect: 16 / 9,
    shortForm: false,
    // DEFAULT: YouTube publishes no safe zone for its regular player; 5 % title-safe margins on every side.
    safe: { top: DEFAULT_MARGIN, bottom: DEFAULT_MARGIN, left: DEFAULT_MARGIN, right: DEFAULT_MARGIN },
    safeNote: "No published safe zone for the YouTube player; 5 % margins on every side are a labelled default.",
    maxSeconds: undefined,
    maxNote: "No length limit is checked for regular YouTube videos.",
    // YouTube Help 1722171: "The standard aspect ratio for YouTube on a computer is 16:9." 1920×1080 is a DEFAULT size.
    recommended: { w: 1920, h: 1080 },
    minShortSide: 720, // DEFAULT.
    sizeNote: "YouTube Help: 16:9 is the standard shape; 1920×1080 is a labelled default size.",
    fps: { min: 23.976, max: 60 },
    fpsNote: "YouTube Help: common frame rates are 24, 25, 30, 48, 50 and 60 fps.",
  },
  general: {
    name: "General",
    aspect: 0,
    shortForm: false,
    safe: { top: DEFAULT_MARGIN, bottom: DEFAULT_MARGIN, left: DEFAULT_MARGIN, right: DEFAULT_MARGIN },
    safeNote: "No platform chosen; 5 % margins on every side are a labelled default.",
    maxSeconds: undefined,
    maxNote: "No length limit is checked.",
    recommended: { w: 0, h: 0 },
    minShortSide: 480, // DEFAULT.
    sizeNote: "No platform chosen; only very small frames are flagged.",
    fps: { min: 23.976, max: 60 }, // DEFAULT.
    fpsNote: "No platform chosen; 24–60 fps is a labelled default.",
  },
};

/** Words and marks that usually signal a question or a call to action near the end (English, Hindi, Hinglish, a little
 *  Spanish/Portuguese). Matched case-insensitively as whole words; the punctuation marks match anywhere. */
export const PROMPT_WORDS: readonly string[] = [
  // Question marks (Latin, Arabic, full-width, Spanish opening mark).
  "?", "؟", "？", "¿",
  // English.
  "comment", "comments", "share", "follow", "subscribe", "save this", "tag", "let me know", "tell me", "what do you think",
  "link in bio", "like and", "drop a", "which one", "your turn",
  // Hinglish (Hindi in Latin letters).
  "batao", "btao", "bataiye", "bataye", "bataao", "likho", "likhiye", "likhe", "comment karo", "share karo", "follow karo",
  "subscribe karo", "like karo", "kaisa laga", "kya lagta",
  // Hindi (Devanagari).
  "बताओ", "बताइए", "बताएं", "बताये", "लिखो", "लिखिए", "कमेंट", "शेयर", "फॉलो", "सब्सक्राइब", "लाइक", "कैसा लगा",
  // Spanish / Portuguese.
  "comenta", "comparte", "sígueme", "suscríbete", "comente", "compartilhe", "siga",
];

/* --------------------------------------------------------------- tuning --------------------------------------------------------------- */

/** Area weights for the overall score (they add up to 100). The hook carries the most because, in a scrolling feed,
 *  viewers who leave in the first seconds never see the rest. All weights are product defaults, not platform numbers. */
export const AREA_WEIGHTS: Record<Finding["area"], number> = {
  hook: 25,
  pacing: 15,
  text: 15,
  "safe-zone": 15,
  sound: 15,
  technical: 10,
  ending: 5,
};

/** Points an area loses per finding (area scores start at 100 and stop at 0). */
const PENALTY: Record<Severity, number> = { high: 40, medium: 20, low: 8, good: 0 };

const AREA_TITLES: Record<Finding["area"], string> = {
  hook: "Hook",
  pacing: "Pacing",
  text: "Text",
  "safe-zone": "Safe zone",
  sound: "Sound",
  ending: "Ending",
  technical: "Technical",
};

/** Seconds that count as "the opening". */
const HOOK_WINDOW = 1.5;
/** TikTok Ads Help, "Creative best practices for performance ads": "Introduce your content proposition in the first 3 seconds". */
const FIRST_TEXT_BY = 3;
/** Longest fade from black that still feels like an opening rather than a wait (DEFAULT). */
const MAX_OPEN_FADE = 0.5;
/** A cut "lands on the beat" within this many seconds of a beat or downbeat. */
const BEAT_TOLERANCE = 0.08;
/** Picture samples whose difference to the previous one is at least this are treated as a cut inside the footage (DEFAULT). */
const FRAME_CUT_DIFF = 0.35;
/** Picture samples whose difference is at least this are treated as visible change (DEFAULT). */
const FRAME_CHANGE_DIFF = 0.05;
/** Reading rule (DEFAULT): 4 words per second (about 240 words a minute, a typical adult silent-reading rate),
 *  and never less than 5/6 s on screen (Netflix Timed Text Style Guide, General Requirements: minimum duration 5/6 s). */
const READ_WPS = 4;
const MIN_ON_SCREEN = 5 / 6;
/** Text size thresholds in px at a 1920-high frame (DEFAULT): below 40 is likely small on a phone, below 28 very small. */
const SMALL_TEXT = 40;
const TINY_TEXT = 28;
/** Most lines of text on screen at once before it likely feels dense. DEFAULT informed by Netflix's 2-line subtitle limit. */
const MAX_LINES = 3;

/* --------------------------------------------------------------- helpers -------------------------------------------------------------- */

const EPS = 1e-6;
const r2 = (n: number) => Math.round(n * 100) / 100;
const r3 = (n: number) => Math.round(n * 1000) / 1000;
const clamp = (n: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, n));
const fmt = (s: number) => `${r2(s).toFixed(s < 10 ? 1 : 0)} s`;

function tv(c: Clip, prop: TransformProp, local: number): number {
  return valueAt(c.transform[prop], local, TRANSFORM_DEFAULTS[prop]);
}

interface Placed {
  clip: Clip;
  /** Index in p.tracks (0 = top of the timeline). */
  track: number;
}

/** A segment of the main picture: which clip is the main picture from start to end (null = no main picture). */
interface Segment {
  start: number;
  end: number;
  clipId: string | null;
}

interface Box {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

const boxArea = (b: Box) => Math.max(0, b.x1 - b.x0) * Math.max(0, b.y1 - b.y0);
function intersect(a: Box, b: Box): number {
  const w = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0);
  const h = Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0);
  return w > 0 && h > 0 ? w * h : 0;
}

/** Clips that draw something on visible video tracks (adjustment clips and audio-only media excluded). */
function visualClips(p: Project): Placed[] {
  const out: Placed[] = [];
  p.tracks.forEach((t, i) => {
    if (t.kind !== "video" || t.hidden) return;
    for (const c of t.clips) {
      if (c.dur <= EPS || c.kind === "adjust") continue;
      if (c.kind === "media" && findAsset(p, c.assetId)?.kind === "audio") continue;
      out.push({ clip: c, track: i });
    }
  });
  return out;
}

/**
 * Rule for "main picture" clips (the ones whose changes count as shots):
 * clips on visible video tracks of kind media (video or image asset), anim or color, that fill the frame:
 * normal blend, opacity at the clip's middle at least 0.9, scale at the middle at least 0.9 on both axes,
 * and no mask, window, corner pin or chroma key. Text, shapes, adjustment clips, audio and anything smaller,
 * see-through or keyed (overlays) are ignored. At any moment the main picture is the top-most such clip.
 */
function isMainPicture(p: Project, c: Clip): boolean {
  if (c.kind === "media") {
    const a = findAsset(p, c.assetId);
    if (a && a.kind !== "video" && a.kind !== "image") return false;
  } else if (c.kind !== "anim" && c.kind !== "color") return false;
  if (c.blend !== "normal") return false;
  if ((c.mask && c.mask.shape !== "none") || (c.win && c.win.length) || c.pin || c.chroma?.enabled) return false;
  const mid = c.dur / 2;
  if (tv(c, "opacity", mid) < 0.9) return false;
  const s = tv(c, "scale", mid);
  if (Math.abs(s * tv(c, "scaleX", mid)) < 0.9 || Math.abs(s * tv(c, "scaleY", mid)) < 0.9) return false;
  return true;
}

/** Main-picture segments over [0, end], top-most qualifying clip wins. Continuous splits of one clip are merged. */
function mainSegments(p: Project, end: number): Segment[] {
  const cands = visualClips(p).filter((v) => isMainPicture(p, v.clip));
  const cuts = new Set<number>([0, end]);
  for (const v of cands) {
    cuts.add(clamp(v.clip.start, 0, end));
    cuts.add(clamp(v.clip.start + v.clip.dur, 0, end));
  }
  const pts = [...cuts].sort((a, b) => a - b);
  const segs: Segment[] = [];
  for (let i = 0; i + 1 < pts.length; i++) {
    const a = pts[i]!;
    const b = pts[i + 1]!;
    if (b - a <= EPS) continue;
    const m = (a + b) / 2;
    let best: Placed | null = null;
    for (const v of cands) {
      if (m < v.clip.start || m >= v.clip.start + v.clip.dur) continue;
      if (!best || v.track < best.track || (v.track === best.track && v.clip.start > best.clip.start)) best = v;
    }
    const id = best ? best.clip.id : null;
    const last = segs[segs.length - 1];
    if (last && last.clipId === id && Math.abs(last.end - a) < EPS) last.end = b;
    else segs.push({ start: a, end: b, clipId: id });
  }
  return segs;
}

/** True when clip b continues clip a's source without a visible jump (a split that was left in place). */
function continuous(p: Project, a: Clip, b: Clip): boolean {
  if (a.kind !== b.kind || a.reverse || b.reverse || a.speedKeys || b.speedKeys) return false;
  if (a.kind === "media" && a.assetId && a.assetId === b.assetId) {
    const asset = findAsset(p, a.assetId);
    if (asset?.kind === "image") return true;
    return Math.abs(b.in - (a.in + a.dur * a.speed)) < 1 / Math.max(1, p.fps) + 1e-3;
  }
  if (a.kind === "color") return a.color === b.color;
  if (a.kind === "anim" && a.sceneId && a.sceneId === b.sceneId) return Math.abs(b.in - (a.in + a.dur * a.speed)) < 1 / Math.max(1, p.fps) + 1e-3;
  return false;
}

interface Shot {
  start: number;
  end: number;
  clipId: string;
}

interface Cut {
  t: number;
  /** "edit": two different clips meet; "frames": a jump seen in the picture samples. */
  source: "edit" | "frames";
}

function shotsAndCuts(p: Project, segs: Segment[], frames: LensContext["frames"]): { shots: Shot[]; cuts: Cut[] } {
  const byId = new Map<string, Clip>();
  for (const v of visualClips(p)) byId.set(v.clip.id, v.clip);
  const shots: Shot[] = [];
  const cuts: Cut[] = [];
  let prev: Segment | null = null;
  for (const s of segs) {
    if (s.clipId === null) {
      prev = s;
      continue;
    }
    const last = shots[shots.length - 1];
    const adjacent = prev !== null && prev.clipId !== null && Math.abs(prev.end - s.start) < EPS;
    if (adjacent && last && prev && prev.clipId) {
      const a = byId.get(prev.clipId);
      const b = byId.get(s.clipId);
      if (a && b && continuous(p, a, b)) {
        last.end = s.end;
        prev = s;
        continue;
      }
      cuts.push({ t: s.start, source: "edit" });
    }
    shots.push({ start: s.start, end: s.end, clipId: s.clipId });
    prev = s;
  }
  // Cuts inside the footage, seen in the picture samples.
  for (const f of frames ?? []) {
    if ((f.diffPrev ?? 0) < FRAME_CUT_DIFF) continue;
    if (cuts.some((c) => Math.abs(c.t - f.t) < 0.2)) continue;
    const i = shots.findIndex((s) => f.t > s.start + 0.2 && f.t < s.end - 0.2);
    if (i < 0) continue;
    const s = shots[i]!;
    shots.splice(i, 1, { start: s.start, end: f.t, clipId: s.clipId }, { start: f.t, end: s.end, clipId: s.clipId });
    cuts.push({ t: f.t, source: "frames" });
  }
  cuts.sort((a, b) => a.t - b.t);
  return { shots, cuts };
}

/** Time intervals in which something visibly moves or changes because of the edit itself. */
function motionIntervals(p: Project): { a: number; b: number }[] {
  const out: { a: number; b: number }[] = [];
  for (const { clip: c } of visualClips(p)) {
    const s = c.start;
    const e = c.start + c.dur;
    const add = (a: number, b: number) => {
      const lo = clamp(a, s, e);
      const hi = clamp(b, s, e);
      if (hi >= lo) out.push({ a: lo, b: hi });
    };
    for (const k of Object.keys(c.transform) as TransformProp[]) {
      const keys = c.transform[k];
      if (!Array.isArray(keys)) continue;
      for (let i = 0; i + 1 < keys.length; i++) {
        const k0 = keys[i]!;
        const k1 = keys[i + 1]!;
        if (Math.abs(k1.v - k0.v) <= 1e-6) continue;
        if (k0.ease === "hold") add(s + k1.t, s + k1.t);
        else add(s + k0.t, s + k1.t);
      }
    }
    if (c.win && c.win.length > 1) {
      const w = [...c.win].sort((x, y) => x.t - y.t);
      for (let i = 0; i + 1 < w.length; i++) {
        const a = w[i]!;
        const b = w[i + 1]!;
        if (a.x0 !== b.x0 || a.y0 !== b.y0 || a.x1 !== b.x1 || a.y1 !== b.y1) add(s + a.t, s + b.t);
      }
    }
    // Animation scenes are assumed to move; shake moves the whole clip.
    if (c.kind === "anim" || (c.shake && c.shake.amp > 0)) add(s, e);
    if (c.kind === "text" && c.text && c.text.animate !== "none") add(s, s + c.text.animateDur);
    if (c.transitionIn.kind !== "none" && c.transitionIn.dur > 0) add(s, s + c.transitionIn.dur);
    if (c.transitionOut.kind !== "none" && c.transitionOut.dur > 0) add(e - c.transitionOut.dur, e);
  }
  return out;
}

/** Moments where something changes instantly: cuts, overlays/text appearing or leaving, picture appearing after black,
 *  and visible changes in the picture samples. */
function changeInstants(p: Project, segs: Segment[], cuts: Cut[], end: number, frames: LensContext["frames"]): number[] {
  const out: number[] = cuts.map((c) => c.t);
  for (let i = 1; i < segs.length; i++) {
    const a = segs[i - 1]!;
    const b = segs[i]!;
    if ((a.clipId === null) !== (b.clipId === null)) out.push(b.start);
  }
  const mains = new Set(segs.map((s) => s.clipId));
  for (const { clip: c } of visualClips(p)) {
    if (mains.has(c.id) && isMainPicture(p, c)) continue;
    if (c.start > EPS) out.push(c.start);
    if (c.start + c.dur < end - EPS) out.push(c.start + c.dur);
  }
  for (const f of frames ?? []) if ((f.diffPrev ?? 0) >= FRAME_CHANGE_DIFF && f.t > EPS) out.push(f.t);
  return out.filter((t) => t >= 0 && t <= end).sort((a, b) => a - b);
}

/** Stretches with no change at all, longest first. */
function staticStretches(motion: { a: number; b: number }[], instants: number[], end: number): { start: number; end: number }[] {
  const ev = [...motion.map((m) => ({ a: m.a, b: m.b })), ...instants.map((t) => ({ a: t, b: t }))].sort((x, y) => x.a - y.a || x.b - y.b);
  const out: { start: number; end: number }[] = [];
  let cursor = 0;
  for (const e of ev) {
    if (e.a > cursor + EPS) out.push({ start: cursor, end: Math.min(e.a, end) });
    cursor = Math.max(cursor, e.b);
  }
  if (end > cursor + EPS) out.push({ start: cursor, end });
  return out.filter((s) => s.end - s.start > EPS).sort((a, b) => b.end - b.start - (a.end - a.start) || a.start - b.start);
}

/* ------------------------------------------------------------------ text -------------------------------------------------------------- */

/**
 * Documented estimate of a text block's size in px at project scale, used when no measureText is given.
 * Per character, as a share of the font size: space 0.30, upper-case letters and digits 0.62, CJK 1.0,
 * Devanagari combining signs 0, other characters 0.55 (a typical bold sans). Letter spacing adds (chars − 1) × tracking.
 * Each line is font size × 1.2 tall (as src/render/layers.ts lays it out). An outline adds its width on each side;
 * a background panel adds the renderer's padding. Expect an error of roughly ±20 % for real fonts.
 */
export function estimateTextSize(st: TextStyle, projectHeight: number): { w: number; h: number } {
  const k = projectHeight / 1920;
  const size = Math.max(4, st.size * k);
  const lines = st.text.split("\n");
  let widest = 0;
  for (const line of lines) {
    let w = 0;
    let n = 0;
    for (const ch of line) {
      n++;
      const cp = ch.codePointAt(0) ?? 0;
      if (ch === " ") w += 0.3;
      else if (/[ऀ-ःऺ-ॏ॑-ॗॢॣ]/.test(ch)) w += 0;
      else if ((cp >= 0x3040 && cp <= 0x30ff) || (cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0xac00 && cp <= 0xd7af)) w += 1;
      else if (/[A-Z0-9]/.test(ch)) w += 0.62;
      else w += 0.55;
    }
    widest = Math.max(widest, w * size + Math.max(0, n - 1) * (st.tracking ?? 0) * k);
  }
  let w = widest;
  let h = lines.length * size * 1.2;
  const stroke = st.strokeWidth > 0 ? st.strokeWidth * k : 0;
  w += stroke * 2;
  h += stroke * 2;
  if (st.bg) {
    // Panel drawn from pad × 0.4 to (w − pad × 0.4) around the lines (src/render/layers.ts textCanvas).
    const pad = size * 0.35 + st.strokeWidth * k + st.shadow * k + (st.glow ? size * 0.4 : 0);
    w = widest + pad * 1.2;
    h = lines.length * size * 1.2 + pad * 1.2;
  }
  return { w, h };
}

interface TextItem {
  clip: Clip;
  st: TextStyle;
  track: number;
  /** Unscaled block size at project scale. */
  w: number;
  h: number;
}

function textItems(p: Project, ctx: LensContext): TextItem[] {
  const out: TextItem[] = [];
  for (const v of visualClips(p)) {
    const c = v.clip;
    if (c.kind !== "text" || !c.text || !c.text.text.trim()) continue;
    const m = ctx.measureText ? ctx.measureText(c.text.text, c.text) : estimateTextSize(c.text, p.height);
    out.push({ clip: c, st: c.text, track: v.track, w: Math.max(0, m.w), h: Math.max(0, m.h) });
  }
  return out.sort((a, b) => a.clip.start - b.clip.start || a.track - b.track || (a.clip.id < b.clip.id ? -1 : a.clip.id > b.clip.id ? 1 : 0));
}

/**
 * Where a text block sits at timeline time t, in frame px. The compositor centres the block at the frame centre
 * plus (x, y) whatever the anchor (src/render/compositor.ts); align only moves lines inside the block, so the
 * block's outline does not depend on it. Scale and rotation at t are applied (rotation as the axis-aligned bounds).
 */
function textBox(p: Project, it: TextItem, t: number, dx = 0, dy = 0): Box {
  const c = it.clip;
  const local = clamp(t - c.start, 0, c.dur);
  const s = tv(c, "scale", local);
  const w = Math.abs(it.w * s * tv(c, "scaleX", local));
  const h = Math.abs(it.h * s * tv(c, "scaleY", local));
  const rot = (tv(c, "rotation", local) * Math.PI) / 180;
  const bw = Math.abs(w * Math.cos(rot)) + Math.abs(h * Math.sin(rot));
  const bh = Math.abs(w * Math.sin(rot)) + Math.abs(h * Math.cos(rot));
  const cx = p.width / 2 + tv(c, "x", local) + dx;
  const cy = p.height / 2 + tv(c, "y", local) + dy;
  return { x0: cx - bw / 2, y0: cy - bh / 2, x1: cx + bw / 2, y1: cy + bh / 2 };
}

const midOf = (c: Clip) => c.start + c.dur / 2;
const overlapTime = (a: Clip, b: Clip) => Math.min(a.start + a.dur, b.start + b.dur) - Math.max(a.start, b.start);
const wordsOf = (s: string) => s.split(/\s+/).filter((w) => w.length > 0).length;

function hasPrompt(text: string): boolean {
  const low = text.toLowerCase();
  for (const w of PROMPT_WORDS) {
    if (!/[\p{L}\p{N}]/u.test(w)) {
      if (low.includes(w)) return true;
      continue;
    }
    const esc = w.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    if (new RegExp(`(^|[^\\p{L}\\p{N}\\p{M}])${esc}($|[^\\p{L}\\p{N}\\p{M}])`, "u").test(low)) return true;
  }
  return false;
}

/* ------------------------------------------------------------------ sound ------------------------------------------------------------- */

interface Audible {
  clip: Clip;
  kind: "music" | "voice" | "camera";
  assetDuration: number;
  /** Clip name, or the asset's name when the clip still has its default name. */
  name: string;
}

/** Clips that make sound, as src/render/audio.ts plays them: media clips with a video or audio asset on unmuted tracks
 *  with gain above zero. Audio assets made by the voice engine or carrying a mouth envelope count as voice, other
 *  audio assets as music, and the sound of video clips as camera sound (it may be silent; the lens cannot tell). */
function audibles(p: Project): Audible[] {
  const out: Audible[] = [];
  for (const t of p.tracks) {
    if (t.muted || t.volume <= 0) continue;
    for (const c of t.clips) {
      if (c.kind !== "media" || c.dur <= EPS) continue;
      const a = findAsset(p, c.assetId);
      if (!a || (a.kind !== "video" && a.kind !== "audio")) continue;
      if (typeof c.volume === "number" && c.volume <= 0) continue;
      const kind = a.kind === "video" ? "camera" : a.createdBy === "voice" || a.mouth ? "voice" : "music";
      out.push({ clip: c, kind, assetDuration: a.duration, name: c.name && c.name !== c.kind ? c.name : a.name });
    }
  }
  return out;
}

/* ---------------------------------------------------------------- safe area ----------------------------------------------------------- */

/** The region (px) free of the platform's own buttons, captions and profile overlays. Margins scale with the frame. */
export function safeArea(platform: Platform, width: number, height: number): { x0: number; y0: number; x1: number; y1: number; note: string } {
  const info = PLATFORMS[platform];
  const m = info.safe;
  return { x0: width * m.left, y0: height * m.top, x1: width * (1 - m.right), y1: height * (1 - m.bottom), note: info.safeNote };
}

function guessPlatform(p: Project): Platform {
  if (p.width <= 0 || p.height <= 0) return "general";
  const a = p.width / p.height;
  if (Math.abs(a - 9 / 16) / (9 / 16) < 0.02) return "instagram-reel";
  if (Math.abs(a - 16 / 9) / (16 / 9) < 0.02) return "youtube";
  return "general";
}

/* ------------------------------------------------------------------- lens ------------------------------------------------------------- */

/** Look at the project as an audience and a platform probably would. Pure and deterministic. */
export function lens(p: Project, ctx: LensContext = {}): LensReport {
  const platform = ctx.platform ?? guessPlatform(p);
  const info = PLATFORMS[platform];
  const end = projectDuration(p);
  const findings: Finding[] = [];
  const add = (f: Finding) => findings.push(f);
  const frames = (ctx.frames ?? []).filter((f) => Number.isFinite(f.t) && Number.isFinite(f.luma)).sort((a, b) => a.t - b.t);

  const facts: Record<string, number | string> = {
    platform: info.name,
    duration: r3(end),
    width: p.width,
    height: p.height,
    fps: p.fps,
  };

  const vis = visualClips(p);
  const sound = audibles(p);
  if (end <= EPS || (vis.length === 0 && sound.length === 0)) {
    add({ id: "technical:empty", area: "technical", severity: "high", title: "The timeline is empty", detail: "There is nothing on the timeline yet, so there is nothing for viewers to watch. Add footage, images, text or sound first." });
    return finish(platform, findings, [{ t: 0, v: 1 }], facts, true);
  }

  const segs = mainSegments(p, end);
  const { shots, cuts } = shotsAndCuts(p, segs, frames);
  const motion = motionIntervals(p);
  const instants = changeInstants(p, segs, cuts, end, frames);
  const texts = textItems(p, ctx);
  const clipById = new Map<string, Clip>();
  for (const v of vis) clipById.set(v.clip.id, v.clip);
  const pictureStart = vis.reduce((m, v) => Math.min(m, v.clip.start), Infinity);
  const pictureEnd = vis.reduce((m, v) => Math.max(m, v.clip.start + v.clip.dur), 0);
  const beatsAll = [...(ctx.beats ?? []), ...(ctx.downbeats ?? [])].filter((b) => Number.isFinite(b)).sort((a, b) => a - b);
  const onBeat = (t: number) => beatsAll.some((b) => Math.abs(b - t) <= BEAT_TOLERANCE);

  facts.shots = shots.length;
  facts.cuts = cuts.length;
  facts.textClips = texts.length;
  facts.audioClips = sound.length;
  if (ctx.bpm !== undefined) facts.bpm = ctx.bpm;

  /* ---------------- hook ---------------- */
  const hookEnd = Math.min(HOOK_WINDOW, end);
  const changeEarly =
    instants.some((t) => t > 0.05 && t <= hookEnd + EPS) ||
    motion.some((m) => m.a <= hookEnd && m.b >= 0 && (m.b > m.a + EPS ? Math.min(m.b, hookEnd) - Math.max(m.a, 0) > 0.05 : m.a > 0.05));
  let hookSeverity: Severity = "good";
  const bump = (s: Severity) => {
    const order: Severity[] = ["good", "low", "medium", "high"];
    if (order.indexOf(s) > order.indexOf(hookSeverity)) hookSeverity = s;
  };
  if (changeEarly) {
    add({ id: "hook:change", area: "hook", severity: "good", title: "Something happens right away", detail: `There is a cut, movement or text arriving in the first ${fmt(HOOK_WINDOW)}, which is likely to hold attention while viewers decide whether to stay.` });
  } else {
    const first = shots[0] ? clipById.get(shots[0].clipId) : undefined;
    let fix: Finding["fix"];
    if (first && shots[0]!.start <= 0.05 && first.kind !== "anim" && !Array.isArray(first.transform.scale) && !Array.isArray(first.transform.scaleX) && !Array.isArray(first.transform.scaleY)) {
      const s0 = tv(first, "scale", 0);
      const t1 = first.start + Math.min(HOOK_WINDOW, first.dur);
      fix = { label: "Add a gentle push-in to the first shot", commands: [{ op: "clip.keys", id: first.id, prop: "scale", keys: [[first.start, r3(s0), "out"], [r3(t1), r3(s0 * 1.08), "out"]] }] };
    }
    add({ id: "hook:change", area: "hook", severity: "high", title: "The first seconds may feel still", detail: `Nothing in the edit changes in the first ${fmt(HOOK_WINDOW)} (no cut, movement or text arriving). In a scrolling feed many viewers likely decide within that time; a cut, a move or a line of text there often helps.`, at: 0, clipIds: first ? [first.id] : undefined, fix });
    bump("high");
  }

  // Opening from black: picture starting late, a long fade-like transition in, or an opacity ramp from near zero.
  {
    const s0 = shots[0];
    const fadeKinds = new Set(["fade", "dip", "blur", "iris", "zoomIn"]);
    let black = 0;
    let fix: Finding["fix"];
    let ids: string[] | undefined;
    const c = s0 ? clipById.get(s0.clipId) : undefined;
    if (s0 && c) {
      black = s0.start;
      ids = [c.id];
      if (Math.abs(c.start - s0.start) < EPS && fadeKinds.has(c.transitionIn.kind) && c.transitionIn.dur > 0) {
        black += c.transitionIn.dur;
        if (c.transitionIn.dur > MAX_OPEN_FADE) fix = { label: "Shorten the opening fade to 0.3 s", commands: [{ op: "clip.transition", id: c.id, in: c.transitionIn.kind, dur: 0.3 }] };
      }
      const op = c.transform.opacity;
      if (Array.isArray(op) && op.length > 1 && op[0]!.v < 0.2) {
        const reach = op.find((k) => k.v >= 0.9);
        if (reach) black = Math.max(black, s0.start + reach.t);
      }
    } else black = end;
    if (black > MAX_OPEN_FADE) {
      add({ id: "hook:fade", area: "hook", severity: "medium", title: "Opens on black", detail: `The picture takes about ${fmt(black)} to appear. Viewers in a feed often scroll past a dark opening; starting on the picture (or a fade of ${MAX_OPEN_FADE} s or less) is likely to work better.`, at: 0, clipIds: ids, fix });
      bump("medium");
    } else add({ id: "hook:fade", area: "hook", severity: "good", title: "Opens on the picture", detail: "The first picture appears straight away, without a long fade from black." });
  }

  // First frame brightness, when picture samples are given.
  if (frames.length) {
    const f0 = frames[0]!;
    if (f0.t <= 0.25 && f0.luma < 0.1) {
      add({ id: "hook:dark", area: "hook", severity: "medium", title: "The first frame is very dark", detail: `The first frame's average brightness is about ${Math.round(f0.luma * 100)} %. A dark first frame often reads as "nothing here" in a feed and also makes a dull cover; a brighter opening frame is likely to work better.`, at: f0.t });
      bump("medium");
    } else if (f0.t <= 0.25) add({ id: "hook:dark", area: "hook", severity: "good", title: "The first frame is bright enough", detail: "The opening frame is not dark, so it is likely to read well in a feed." });
  }

  // First text.
  if (texts.length) {
    const t0 = texts[0]!.clip.start;
    facts.firstText = r3(t0);
    if (t0 <= FIRST_TEXT_BY) add({ id: "hook:text", area: "hook", severity: "good", title: "Text arrives early", detail: `The first text appears at ${fmt(t0)}. TikTok's creative guidance suggests introducing the main idea within the first 3 seconds, which this does.` });
    else {
      const sev: Severity = t0 > 2 * FIRST_TEXT_BY ? "medium" : "low";
      add({ id: "hook:text", area: "hook", severity: sev, title: "Text arrives late", detail: `The first text appears at ${fmt(t0)}. Many people watch with the sound off; a short line in the first ${FIRST_TEXT_BY} s (TikTok's creative guidance) is likely to tell them what the video is about sooner.`, at: t0, clipIds: [texts[0]!.clip.id] });
      bump(sev);
    }
  } else if (info.shortForm) {
    add({ id: "hook:text", area: "hook", severity: "low", title: "No on-screen text", detail: "There is no text on the timeline. Many people likely watch short videos with the sound off; a short title or caption early on often helps them follow." });
    bump("low");
  }

  /* ---------------- pacing ---------------- */
  const shotLens = shots.map((s) => s.end - s.start).sort((a, b) => a - b);
  if (shotLens.length) {
    const median = shotLens.length % 2 ? shotLens[(shotLens.length - 1) / 2]! : (shotLens[shotLens.length / 2 - 1]! + shotLens[shotLens.length / 2]!) / 2;
    facts.medianShot = r3(median);
    facts.longestShot = r3(shotLens[shotLens.length - 1]!);
    const slow = info.shortForm ? 5 : 15; // DEFAULT thresholds.
    if (median > slow && shots.length > 1) add({ id: "pacing:shots", area: "pacing", severity: "low", title: "Shots are long for this platform", detail: `The typical shot lasts about ${fmt(median)}. On ${info.name} viewers often respond to quicker changes; trimming the slower shots may help.` });
    else if (median < 0.4 && shots.length > 3) add({ id: "pacing:shots", area: "pacing", severity: "low", title: "Shots may be too quick to follow", detail: `The typical shot lasts about ${fmt(median)}. Very fast cutting can be hard to follow; letting key shots breathe may help.` });
    else add({ id: "pacing:shots", area: "pacing", severity: "good", title: "Shot lengths suit the platform", detail: `The typical shot lasts about ${fmt(median)}, which is likely to feel comfortable on ${info.name}.` });

    if (shotLens.length >= 4) {
      const mean = shotLens.reduce((a, b) => a + b, 0) / shotLens.length;
      const sd = Math.sqrt(shotLens.reduce((a, b) => a + (b - mean) * (b - mean), 0) / shotLens.length);
      const cv = mean > 0 ? sd / mean : 0;
      facts.shotVariety = r3(cv);
      if (cv < 0.15) add({ id: "pacing:variety", area: "pacing", severity: "low", title: "Every shot is about the same length", detail: "A fixed rhythm can start to feel predictable. Mixing a few short and long shots often keeps it lively." });
      else add({ id: "pacing:variety", area: "pacing", severity: "good", title: "Shot lengths vary", detail: "The mix of shorter and longer shots is likely to keep the rhythm interesting." });
    }
  }

  // Longest unchanged stretch.
  const stretches = staticStretches(motion, instants, end);
  const longest = stretches[0];
  const medStatic = info.shortForm ? 4 : 10; // DEFAULT thresholds.
  const highStatic = info.shortForm ? 8 : 20;
  facts.longestStill = r3(longest ? longest.end - longest.start : 0);
  if (longest && longest.end - longest.start > medStatic) {
    const len = longest.end - longest.start;
    const mid = (longest.start + longest.end) / 2;
    const under = segs.find((s) => mid >= s.start && mid < s.end);
    const uc = under?.clipId ? clipById.get(under.clipId) : undefined;
    const footage = !!uc && uc.kind === "media" && findAsset(p, uc.assetId)?.kind === "video" && !uc.hold;
    const sev: Severity = footage && !frames.length ? "low" : len > highStatic ? "high" : "medium";
    const note = footage && !frames.length ? " (Movement inside the footage itself is not measured unless picture samples are given.)" : "";
    add({ id: "pacing:still", area: "pacing", severity: sev, title: "A long stretch without change", detail: `From ${fmt(longest.start)} to ${fmt(longest.end)} (${fmt(len)}) nothing in the edit changes. Viewers often drift away in long unchanged stretches; a cut, a move or a text beat there may help.${note}`, at: r3(longest.start), clipIds: uc ? [uc.id] : undefined });
  } else add({ id: "pacing:still", area: "pacing", severity: "good", title: "No long still stretches", detail: `Something changes at least every ${fmt(medStatic)}, which is likely to keep the eye busy.` });

  // Cuts on beats.
  if (beatsAll.length && cuts.length >= 2) {
    const hit = cuts.filter((c) => onBeat(c.t)).length;
    const ratio = hit / cuts.length;
    facts.cutsOnBeat = r3(ratio);
    if (ratio >= 0.5) add({ id: "pacing:beats", area: "pacing", severity: "good", title: "Cuts land on the beat", detail: `${hit} of ${cuts.length} cuts land within ${Math.round(BEAT_TOLERANCE * 1000)} ms of a beat, which usually makes an edit feel tight.` });
    else add({ id: "pacing:beats", area: "pacing", severity: "low", title: "Cuts drift off the beat", detail: `Only ${hit} of ${cuts.length} cuts land within ${Math.round(BEAT_TOLERANCE * 1000)} ms of a beat. Snapping cuts to the music often makes an edit feel tighter.`, at: r3(cuts.find((c) => !onBeat(c.t))?.t ?? 0) });
  }

  // Total length.
  if (info.maxSeconds !== undefined) {
    if (end > info.maxSeconds + 0.01) add({ id: "pacing:length", area: "pacing", severity: platform === "tiktok" && end <= 600 ? "medium" : "high", title: "Longer than the platform's usual limit", detail: `The video runs ${fmt(end)}. ${info.maxNote} It may be refused or not treated as a short; consider trimming.` });
    else add({ id: "pacing:length", area: "pacing", severity: "good", title: "Length fits the platform", detail: `The video runs ${fmt(end)}, within the limit for ${info.name}. ${info.maxNote}` });
  }

  /* ---------------- text ---------------- */
  const unreadable: { a: number; b: number }[] = [];
  const sa = safeArea(platform, p.width, p.height);
  const inSafe = (b: Box) => b.x0 >= sa.x0 - 0.5 && b.y0 >= sa.y0 - 0.5 && b.x1 <= sa.x1 + 0.5 && b.y1 <= sa.y1 + 0.5;
  /** Would this text's box (at its middle, optionally with other dimensions) touch another text shown at the same time? */
  const collides = (it: TextItem, box: Box, from: number, to: number) =>
    texts.some((o) => o !== it && Math.min(to, o.clip.start + o.clip.dur) - Math.max(from, o.clip.start) > 0.05 && intersect(box, textBox(p, o, midOf(o.clip))) > 0);
  if (texts.length) {
    let shortCount = 0;
    for (const it of texts) {
      const c = it.clip;
      const words = wordsOf(it.st.text);
      const shown = c.dur - (it.st.animate !== "none" ? Math.min(c.dur, it.st.animateDur) * 0.5 : 0);
      const need = Math.max(MIN_ON_SCREEN, words / READ_WPS);
      if (shown + 1e-3 < need) {
        shortCount++;
        const sev: Severity = shown < need * 0.6 ? "high" : "medium";
        unreadable.push({ a: c.start, b: c.start + c.dur });
        // Fix: lengthen the clip when its track has room after it, it stays within the rest of the picture, and no
        // other text appears in the same place during the extra time.
        const track = p.tracks.find((t) => t.clips.includes(c));
        const extra = need - shown;
        const newEnd = c.start + c.dur + extra;
        const nextStart = track ? track.clips.filter((o) => o !== c && o.start >= c.start + c.dur - EPS).reduce((m, o) => Math.min(m, o.start), Infinity) : Infinity;
        const otherEnd = vis.filter((v) => v.clip !== c).reduce((m, v) => Math.max(m, v.clip.start + v.clip.dur), 0);
        const room = newEnd <= nextStart + EPS && newEnd <= otherEnd + EPS && !collides(it, textBox(p, it, midOf(c)), c.start + c.dur, newEnd);
        const fix: Finding["fix"] = room ? { label: `Keep it on screen ${fmt(c.dur + extra)}`, commands: [{ op: "clip.set", id: c.id, patch: { dur: r3(Math.ceil((c.dur + extra) * 1000) / 1000) } }] } : undefined;
        add({ id: `text:read:${c.id}`, area: "text", severity: sev, title: "Text may be too quick to read", detail: `"${short(it.st.text)}" has ${words} word${words === 1 ? "" : "s"} and is readable for about ${fmt(shown)}; at ${READ_WPS} words a second (and at least ${r2(MIN_ON_SCREEN)} s) it likely needs ${fmt(need)}.`, at: r3(c.start), clipIds: [c.id], fix });
      }
    }
    if (!shortCount) add({ id: "text:read", area: "text", severity: "good", title: "Text stays long enough to read", detail: `Every text stays on screen for at least ${READ_WPS} words a second.` });

    let small = 0;
    for (const it of texts) {
      const c = it.clip;
      const mid = c.dur / 2;
      const s = Math.abs(tv(c, "scale", mid)) * Math.min(Math.abs(tv(c, "scaleX", mid)), Math.abs(tv(c, "scaleY", mid)));
      const eff = it.st.size * s;
      if (eff < SMALL_TEXT) {
        small++;
        const sev: Severity = eff < TINY_TEXT ? "high" : "medium";
        unreadable.push({ a: c.start, b: c.start + c.dur });
        // Fix: enlarge, only when the bigger text still sits inside the safe area and clear of other texts.
        const size = Math.ceil(SMALL_TEXT / Math.max(1e-6, s));
        const bigSt: TextStyle = { ...it.st, size };
        const m = ctx.measureText ? ctx.measureText(bigSt.text, bigSt) : estimateTextSize(bigSt, p.height);
        const big: TextItem = { ...it, st: bigSt, w: Math.max(0, m.w), h: Math.max(0, m.h) };
        const bigBox = textBox(p, big, midOf(c));
        const ok = inSafe(bigBox) && !collides(it, bigBox, c.start, c.start + c.dur);
        const fix: Finding["fix"] = ok ? { label: `Enlarge to ${SMALL_TEXT} px`, commands: [{ op: "clip.set", id: c.id, patch: { text: { size } } }] } : undefined;
        const why = ok ? "" : " (Enlarging it where it is would likely reach the app's buttons or another text, so no automatic fix is offered.)";
        add({ id: `text:size:${c.id}`, area: "text", severity: sev, title: "Text may be too small on a phone", detail: `"${short(it.st.text)}" is about ${Math.round(eff)} px tall on a 1920-high frame (${r2((eff / 1920) * 100)} % of the height). Below about ${SMALL_TEXT} px it is likely hard to read on a phone.${why}`, at: r3(c.start), clipIds: [c.id], fix });
      }
    }
    if (!small) add({ id: "text:size", area: "text", severity: "good", title: "Text is large enough", detail: `All text is at least ${SMALL_TEXT} px tall on a 1920-high frame, likely readable on a phone.` });

    let bare = 0;
    for (const it of texts) {
      const st = it.st;
      if (st.strokeWidth > 0 || st.shadow > 0 || st.bg || st.glow) continue;
      bare++;
      const c = it.clip;
      const during = frames.filter((f) => f.t >= c.start && f.t < c.start + c.dur);
      const bright = during.length ? during.reduce((a, f) => a + f.luma, 0) / during.length : undefined;
      const sev: Severity = bright !== undefined && bright >= 0.6 ? "medium" : "low";
      add({ id: `text:contrast:${c.id}`, area: "text", severity: sev, title: "Text has no outline, shadow or background", detail: `"${short(st.text)}" may be hard to read over bright or busy footage${bright !== undefined ? ` (the picture behind it averages ${Math.round(bright * 100)} % brightness)` : ""}. A soft shadow usually helps.`, at: r3(c.start), clipIds: [c.id], fix: { label: "Add a soft shadow", commands: [{ op: "clip.set", id: c.id, patch: { text: { shadow: 12 } } }] } });
    }
    if (!bare) add({ id: "text:contrast", area: "text", severity: "good", title: "Text has an outline, shadow or background", detail: "Every text has something that separates it from the picture." });

    // Overlapping texts at the same time and place.
    let overlaps = 0;
    for (let i = 0; i < texts.length; i++) {
      for (let j = i + 1; j < texts.length; j++) {
        const a = texts[i]!;
        const b = texts[j]!;
        const ot = overlapTime(a.clip, b.clip);
        if (ot <= 0.1) continue;
        const tm = Math.max(a.clip.start, b.clip.start) + ot / 2;
        if (tv(a.clip, "opacity", tm - a.clip.start) < 0.05 || tv(b.clip, "opacity", tm - b.clip.start) < 0.05) continue;
        const ba = textBox(p, a, tm);
        const bb = textBox(p, b, tm);
        const inter = intersect(ba, bb);
        if (inter > 0.1 * Math.max(1, Math.min(boxArea(ba), boxArea(bb)))) {
          overlaps++;
          add({ id: `text:overlap:${a.clip.id}:${b.clip.id}`, area: "text", severity: "medium", title: "Two texts overlap", detail: `"${short(a.st.text)}" and "${short(b.st.text)}" are on screen together in the same place, so both are likely hard to read. Move one or show them one after the other.`, at: r3(Math.max(a.clip.start, b.clip.start)), clipIds: [a.clip.id, b.clip.id] });
        }
      }
    }
    if (!overlaps) add({ id: "text:overlap", area: "text", severity: "good", title: "No overlapping text", detail: "Texts never cover each other." });

    // Lines on screen at once.
    let maxLines = 0;
    let maxAt = 0;
    for (const it of texts) {
      const t = it.clip.start + Math.min(0.01, it.clip.dur / 2);
      let n = 0;
      for (const o of texts) if (t >= o.clip.start && t < o.clip.start + o.clip.dur) n += o.st.text.split("\n").length;
      if (n > maxLines) {
        maxLines = n;
        maxAt = it.clip.start;
      }
    }
    facts.maxTextLines = maxLines;
    if (maxLines > MAX_LINES) add({ id: "text:lines", area: "text", severity: "medium", title: "A lot of text at once", detail: `Up to ${maxLines} lines of text are on screen together around ${fmt(maxAt)}. Viewers often skip dense text; ${MAX_LINES} lines or fewer at a time is likely easier to take in.`, at: r3(maxAt) });
    else add({ id: "text:lines", area: "text", severity: "good", title: "Text is light enough", detail: `No more than ${maxLines} line${maxLines === 1 ? "" : "s"} of text at a time.` });
    facts.words = texts.reduce((a, it) => a + wordsOf(it.st.text), 0);
  }

  /* ---------------- safe zone ---------------- */
  if (texts.length) {
    interface Out {
      it: TextItem;
      box: Box;
      frac: number;
      move?: { dx: number; dy: number };
    }
    const outs: Out[] = [];
    for (const it of texts) {
      const box = textBox(p, it, midOf(it.clip));
      const area = boxArea(box);
      const inside = intersect(box, sa);
      const frac = area > 0 ? 1 - inside / area : 0;
      const over = Math.max(sa.x0 - box.x0, box.x1 - sa.x1, sa.y0 - box.y0, box.y1 - sa.y1);
      if (over <= 0.5) continue;
      let move: Out["move"];
      const fitsX = box.x1 - box.x0 <= sa.x1 - sa.x0 - 2;
      const fitsY = box.y1 - box.y0 <= sa.y1 - sa.y0 - 2;
      const c = it.clip;
      if (fitsX && fitsY && !Array.isArray(c.transform.x) && !Array.isArray(c.transform.y)) {
        const need = (lo: number, hi: number, slo: number, shi: number) => (lo < slo ? slo - lo + 1 : hi > shi ? shi - hi - 1 : 0);
        move = { dx: need(box.x0, box.x1, sa.x0, sa.x1), dy: need(box.y0, box.y1, sa.y0, sa.y1) };
      }
      outs.push({ it, box, frac, move });
    }
    // Drop moves that would land on another text (using every other text's final position), until stable.
    const finalBox = (it: TextItem): Box => {
      const o = outs.find((x) => x.it === it);
      return o?.move ? textBox(p, it, midOf(it.clip), o.move.dx, o.move.dy) : textBox(p, it, midOf(it.clip));
    };
    for (let pass = 0; pass <= outs.length; pass++) {
      let changed = false;
      for (const o of outs) {
        if (!o.move) continue;
        const nb = finalBox(o.it);
        const hit = texts.some((t) => t !== o.it && overlapTime(t.clip, o.it.clip) > 0.1 && intersect(nb, finalBox(t)) > 0);
        if (hit) {
          o.move = undefined;
          changed = true;
        }
      }
      if (!changed) break;
    }
    for (const o of outs) {
      const c = o.it.clip;
      let fix: Finding["fix"];
      if (o.move) {
        const cmds: Record<string, unknown>[] = [];
        const x = tv(c, "x", 0);
        const y = tv(c, "y", 0);
        if (o.move.dx !== 0) cmds.push({ op: "clip.value", id: c.id, prop: "x", v: roundAway(x + o.move.dx, o.move.dx) });
        if (o.move.dy !== 0) cmds.push({ op: "clip.value", id: c.id, prop: "y", v: roundAway(y + o.move.dy, o.move.dy) });
        if (cmds.length) fix = { label: "Move it inside the safe area", commands: cmds };
      }
      const sides = [o.box.y0 < sa.y0 ? "top" : "", o.box.y1 > sa.y1 ? "bottom" : "", o.box.x0 < sa.x0 ? "left" : "", o.box.x1 > sa.x1 ? "right" : ""].filter(Boolean);
      const where = sides.length > 1 ? `${sides.slice(0, -1).join(", ")} and ${sides[sides.length - 1]} edges` : `${sides[0] ?? "outer"} edge`;
      const tooBig = o.box.x1 - o.box.x0 > sa.x1 - sa.x0 - 2 || o.box.y1 - o.box.y0 > sa.y1 - sa.y0 - 2;
      const why = fix ? "" : tooBig ? " It is larger than the safe area, so shorter text, a line break or a smaller size is likely needed." : Array.isArray(c.transform.x) || Array.isArray(c.transform.y) ? " Its position is animated, so no automatic move is offered." : " Moving it would land on another text, so no automatic move is offered.";
      add({ id: `safe-zone:text:${c.id}`, area: "safe-zone", severity: o.frac >= 0.25 ? "high" : "medium", title: "Text may be covered by the app's buttons", detail: `"${short(o.it.st.text)}" reaches into the ${where} where ${info.name} likely shows its own buttons and captions (about ${Math.round(o.frac * 100)} % of it).${why} ${sa.note}`, at: r3(c.start), clipIds: [c.id], fix });
    }
    if (!outs.length) add({ id: "safe-zone:text", area: "safe-zone", severity: "good", title: "Text stays clear of the app's buttons", detail: `Every text sits inside the safe area for ${info.name}. ${sa.note}` });
  }

  /* ---------------- sound ---------------- */
  if (!sound.length) {
    add({ id: "sound:none", area: "sound", severity: info.shortForm ? "high" : "medium", title: "No sound", detail: "The video has no audio at all. Feeds often play with sound on, and a silent video is likely to feel unfinished; music or a voice usually helps." });
  } else {
    const music = sound.filter((s) => s.kind === "music");
    const voice = sound.filter((s) => s.kind === "voice");
    facts.musicClips = music.length;
    facts.voiceClips = voice.length;
    if (music.length) add({ id: "sound:music", area: "sound", severity: "good", title: "Music is present", detail: "There is a music track, which usually helps carry the mood." });
    else add({ id: "sound:music", area: "sound", severity: "low", title: "No music track", detail: voice.length ? "There is a voice but no music. A quiet music bed often makes a video feel more finished." : "The only sound comes from the video clips (which may be silent). A music track often makes a video feel more finished." });

    // Fades on music and voice clips.
    let fadeIssues = 0;
    const soundEnd = sound.reduce((m, s) => Math.max(m, s.clip.start + s.clip.dur), 0);
    // When sound runs on past the picture, the technical fix trims it to the picture's end; judge the ending there.
    const trimTo = pictureEnd > 0 && soundEnd > pictureEnd + 0.5 ? pictureEnd : undefined;
    const videoEnd = trimTo ?? end;
    for (const s of sound) {
      if (s.kind === "camera") continue;
      const c = s.clip;
      const clipEnd = Math.min(c.start + c.dur, videoEnd);
      const dur = clipEnd - c.start;
      if (dur <= 0.1) continue;
      // The music is cut before its own end (by the clip's out-point, or by the coming trim).
      const cutMidSong = s.assetDuration > c.in + c.dur * c.speed + 0.25 || clipEnd < c.start + c.dur - EPS;
      if (s.kind === "music" && Math.abs(clipEnd - videoEnd) < 0.05 && c.fadeOut < 0.05 && cutMidSong) {
        fadeIssues++;
        const fo = r3(Math.min(1, dur / 4));
        add({ id: `sound:fadeout:${c.id}`, area: "sound", severity: "medium", title: "Music stops abruptly at the end", detail: `"${s.name}" is cut off at ${fmt(clipEnd)} without a fade, which often sounds like a mistake. A short fade-out is likely to feel smoother.`, at: r3(clipEnd), clipIds: [c.id], fix: { label: `Fade out over ${fo} s`, commands: [{ op: "clip.set", id: c.id, patch: { fadeOut: fo } }] } });
      }
      if (c.fadeIn < 0.01 && c.in > 0.05) {
        fadeIssues++;
        add({ id: `sound:fadein:${c.id}`, area: "sound", severity: "low", title: "Sound starts mid-waveform", detail: `"${s.name}" starts part-way into its source with no fade, which may click. A very short fade-in (0.05 s) usually hides it.`, at: r3(c.start), clipIds: [c.id], fix: { label: "Add a 0.05 s fade-in", commands: [{ op: "clip.set", id: c.id, patch: { fadeIn: 0.05 } }] } });
      }
    }
    if (!fadeIssues) add({ id: "sound:fades", area: "sound", severity: "good", title: "Sound starts and ends cleanly", detail: "Music and voice clips do not start or stop abruptly." });

    // Loudness (labelled defaults: none of the platforms publishes a loudness target in its help pages).
    const L = ctx.loudness;
    if (L && L.integratedLufs !== undefined && Number.isFinite(L.integratedLufs)) {
      // DEFAULT: about −14 LUFS integrated is the level short-video and streaming players are widely measured to
      // normalise to; YouTube, TikTok and Instagram help pages publish no loudness target. Flag below −18 or above −9.
      const lufs = L.integratedLufs;
      facts.integratedLufs = r2(lufs);
      if (lufs < -18) add({ id: "sound:loudness", area: "sound", severity: "medium", title: "Sound is likely quieter than most videos", detail: `The mix measures about ${r2(lufs)} LUFS. Players often turn loud videos down but rarely turn quiet ones up, so it may sound weak next to other videos; around −14 LUFS (a labelled default, not a platform number) is a common target.` });
      else if (lufs > -9) add({ id: "sound:loudness", area: "sound", severity: "low", title: "Sound is very loud", detail: `The mix measures about ${r2(lufs)} LUFS. Players likely turn it down, and a mix this loud often sounds squashed; around −14 LUFS (a labelled default) is a common target.` });
      else add({ id: "sound:loudness", area: "sound", severity: "good", title: "Loudness is in a usual range", detail: `The mix measures about ${r2(lufs)} LUFS, near the common −14 LUFS level (a labelled default, not a platform number).` });
    }
    if (L && L.truePeakDb !== undefined && Number.isFinite(L.truePeakDb)) {
      // EBU R 128: maximum true-peak level −1 dBTP (broadcast standard; used here as a labelled default for online video).
      facts.truePeakDb = r2(L.truePeakDb);
      if (L.truePeakDb > -1) add({ id: "sound:peak", area: "sound", severity: "medium", title: "Peaks may distort", detail: `True peaks reach about ${r2(L.truePeakDb)} dBTP. After the platform re-encodes the sound, peaks above −1 dBTP (the EBU R 128 limit, used here as a default) may crackle; a limiter usually prevents it.` });
      else add({ id: "sound:peak", area: "sound", severity: "good", title: "Peaks have headroom", detail: `True peaks stay at about ${r2(L.truePeakDb)} dBTP, below −1 dBTP.` });
    }

    // Long silences: measured ones when given, otherwise timeline stretches with no sound clip.
    const limit = info.shortForm ? 1.5 : 3; // DEFAULT.
    const silences: { start: number; end: number }[] = [];
    if (L?.silences) for (const s of L.silences) if (Number.isFinite(s.start) && Number.isFinite(s.end) && s.end - s.start >= limit) silences.push({ start: s.start, end: s.end });
    if (!L?.silences) {
      const ivs = sound.map((s) => ({ a: s.clip.start, b: s.clip.start + s.clip.dur })).sort((a, b) => a.a - b.a);
      let cur = 0;
      const until = Math.max(pictureEnd, 0);
      for (const iv of ivs) {
        if (iv.a - cur >= limit && iv.a <= until) silences.push({ start: cur, end: iv.a });
        cur = Math.max(cur, iv.b);
      }
      if (until - cur >= limit) silences.push({ start: cur, end: until });
    }
    silences.sort((a, b) => a.start - b.start);
    if (silences.length) {
      const s0 = silences[0]!;
      add({ id: "sound:silence", area: "sound", severity: "medium", title: "Long silence", detail: `There ${silences.length === 1 ? "is a silence" : `are ${silences.length} silences`} of ${fmt(limit)} or more (the first from ${fmt(s0.start)} to ${fmt(s0.end)}). A silent gap often reads as a mistake unless it is a deliberate pause.`, at: r3(s0.start) });
    } else add({ id: "sound:silence", area: "sound", severity: "good", title: "No long silences", detail: "Sound runs without long gaps." });
  }

  /* ---------------- ending ---------------- */
  {
    const win = Math.max(3, end * 0.2);
    const near = texts.filter((it) => it.clip.start + it.clip.dur > end - win);
    const prompt = near.find((it) => hasPrompt(it.st.text));
    if (prompt) add({ id: "ending:prompt", area: "ending", severity: "good", title: "Ends with a question or a prompt", detail: `"${short(prompt.st.text)}" near the end gives viewers something to answer, which often invites comments.`, clipIds: [prompt.clip.id] });
    else add({ id: "ending:prompt", area: "ending", severity: "low", title: "No question or prompt at the end", detail: `No text in the last ${fmt(win)} asks a question or invites a reply. A short question or prompt near the end often invites comments.`, at: r3(Math.max(0, end - win)) });

    if (info.shortForm && ctx.loopDiff !== undefined && Number.isFinite(ctx.loopDiff)) {
      facts.loopDiff = r3(ctx.loopDiff);
      if (ctx.loopDiff <= 0.2) add({ id: "ending:loop", area: "ending", severity: "good", title: "The end likely loops into the start", detail: "The last frame looks close to the first, so a replay is likely to feel seamless." });
      else add({ id: "ending:loop", area: "ending", severity: "low", title: "The end does not loop into the start", detail: `The last frame differs from the first by about ${Math.round(ctx.loopDiff * 100)} %. On ${info.name} a video replays on its own; an ending that flows back into the opening often gets watched again.` });
    }

    const last = shots[shots.length - 1];
    const lc = last ? clipById.get(last.clipId) : undefined;
    if (last && lc && Math.abs(last.end - pictureEnd) < 0.05) {
      const op = lc.transform.opacity;
      const fades = (lc.transitionOut.kind !== "none" && lc.transitionOut.dur > 0) || (Array.isArray(op) && op.length > 1 && op[op.length - 1]!.v < 0.2);
      if (fades) add({ id: "ending:fade", area: "ending", severity: "good", title: "The ending is eased out", detail: "The last shot has a transition out." });
      else if (info.shortForm) add({ id: "ending:fade", area: "ending", severity: "good", title: "Clean cut at the end", detail: "The last shot ends on a cut, which suits a video that replays." });
      else {
        const fix: Finding["fix"] = lc.dur >= 1.5 ? { label: "Fade out the last shot over 0.5 s", commands: [{ op: "clip.transition", id: lc.id, out: "fade", dur: 0.5 }] } : undefined;
        add({ id: "ending:fade", area: "ending", severity: "low", title: "The video stops abruptly", detail: "The last shot cuts straight to the end. A short fade-out usually makes an ending feel intentional.", at: r3(last.end), clipIds: [lc.id], fix });
      }
    }
  }

  /* ---------------- technical ---------------- */
  {
    const a = p.width / Math.max(1, p.height);
    if (info.aspect > 0) {
      const off = Math.abs(a - info.aspect) / info.aspect;
      const shortsOk = platform === "youtube-shorts" && a <= 1 + 1e-6;
      if (off > 0.02) add({ id: "technical:shape", area: "technical", severity: shortsOk ? "low" : "high", title: "Frame shape does not match the platform", detail: `The frame is ${p.width}×${p.height}; ${info.name} expects ${info.aspect < 1 ? "9:16 (vertical)" : "16:9 (horizontal)"}. It is likely to be shown with bars or cropped.${shortsOk ? " (YouTube Help: square and vertical videos up to three minutes count as Shorts.)" : ""}` });
      else add({ id: "technical:shape", area: "technical", severity: "good", title: "Frame shape matches the platform", detail: `${p.width}×${p.height} fits ${info.name}.` });
    }
    const shortSide = Math.min(p.width, p.height);
    const recShort = Math.min(info.recommended.w, info.recommended.h);
    if (shortSide < info.minShortSide) add({ id: "technical:size", area: "technical", severity: "high", title: "Frame is very small", detail: `The short side is ${shortSide} px, below ${info.minShortSide} px. ${info.sizeNote} It is likely to look soft.` });
    else if (recShort > 0 && shortSide < recShort) add({ id: "technical:size", area: "technical", severity: "low", title: "Frame is below the recommended size", detail: `The short side is ${shortSide} px; ${info.sizeNote} Exporting at ${info.recommended.w}×${info.recommended.h} is likely to look sharper.` });
    else add({ id: "technical:size", area: "technical", severity: "good", title: "Frame size is fine", detail: `${p.width}×${p.height} is large enough. ${info.sizeNote}` });

    if (p.fps < info.fps.min - 0.01 || p.fps > info.fps.max + 0.01) add({ id: "technical:fps", area: "technical", severity: "medium", title: "Unusual frame rate", detail: `The project runs at ${p.fps} fps. ${info.fpsNote} It may be converted, which can make motion stutter.` });
    else add({ id: "technical:fps", area: "technical", severity: "good", title: "Frame rate is fine", detail: `${p.fps} fps is within the usual range. ${info.fpsNote}` });

    // Gaps with no picture at all, after the first picture (black at the very start is a hook finding).
    const ivs = vis.map((v) => ({ a: v.clip.start, b: v.clip.start + v.clip.dur })).sort((x, y) => x.a - y.a);
    const gaps: { start: number; end: number }[] = [];
    let cur = ivs[0]?.a ?? 0;
    for (const iv of ivs) {
      if (iv.a - cur >= 1 / Math.max(1, p.fps) - 1e-6) gaps.push({ start: cur, end: iv.a });
      cur = Math.max(cur, iv.b);
    }
    // (Black after the last picture, under sound that runs on, is the "sound runs on" finding below.)
    facts.gaps = gaps.length;
    const g0 = gaps[0];
    if (!vis.length) add({ id: "technical:picture", area: "technical", severity: "high", title: "No picture", detail: "There are no visible clips, so the video would be black. Add footage, an image or text." });
    else if (g0) add({ id: "technical:gaps", area: "technical", severity: "medium", title: "Black gap in the picture", detail: `Between ${fmt(g0.start)} and ${fmt(g0.end)} nothing is on screen${gaps.length > 1 ? ` (${gaps.length} gaps in all)` : ""}. A black flash usually looks like a mistake.`, at: r3(g0.start) });
    else add({ id: "technical:gaps", area: "technical", severity: "good", title: "No black gaps", detail: "The picture runs without gaps." });

    // Sound versus picture end (music and voice clips only).
    const mv = sound.filter((s) => s.kind !== "camera");
    if (mv.length && vis.length) {
      const se = mv.reduce((m, s) => Math.max(m, s.clip.start + s.clip.dur), 0);
      facts.pictureEnd = r3(pictureEnd);
      facts.soundEnd = r3(se);
      if (se > pictureEnd + 0.5) {
        const over = mv.filter((s) => s.clip.start + s.clip.dur > pictureEnd + 0.05 && s.clip.start < pictureEnd - 0.05);
        const fix: Finding["fix"] = over.length ? { label: `Trim sound to end with the picture at ${fmt(pictureEnd)}`, commands: over.map((s) => ({ op: "clip.trim", id: s.clip.id, edge: "end", t: r3(pictureEnd) })) } : undefined;
        add({ id: "technical:soundOverBlack", area: "technical", severity: "medium", title: "Sound runs on after the picture", detail: `The picture ends at ${fmt(pictureEnd)} but sound continues to ${fmt(se)}, so the last ${fmt(se - pictureEnd)} would likely be black.`, at: r3(pictureEnd), clipIds: over.map((s) => s.clip.id), fix });
      } else if (pictureEnd > se + 2) add({ id: "technical:soundEnds", area: "technical", severity: "low", title: "Sound ends well before the picture", detail: `Music and voice end at ${fmt(se)} but the picture runs to ${fmt(pictureEnd)}; the last ${fmt(pictureEnd - se)} may feel empty.`, at: r3(se) });
      else add({ id: "technical:ends", area: "technical", severity: "good", title: "Sound and picture end together", detail: "Music and voice end close to the last picture." });
    }
    if (Number.isFinite(pictureStart)) facts.pictureStart = r3(pictureStart);
  }

  /* ---------------- attention ---------------- */
  const beatCuts = beatsAll.length ? cuts.filter((c) => onBeat(c.t)).map((c) => c.t) : [];
  const attention = attentionCurve(end, info.shortForm, hookSeverity, stretches, unreadable, beatCuts);
  const last = attention[attention.length - 1];
  if (last) facts.predictedEndShare = last.v;
  return finish(platform, findings, attention, facts, false);
}

/**
 * Predicted share of viewers still watching. A deliberately simple heuristic, NOT a measurement:
 * - starts at 1 and loses a baseline share every second (2.5 %/s on short-form platforms, 0.4 %/s otherwise);
 * - a weak hook adds 12 %/s (high) or 6 %/s (medium) or 2 %/s (low) of extra loss during the first 1.5 s;
 * - inside an unchanged stretch, after a grace time (2 s short-form, 6 s otherwise), loss rises by 4 %/s (1 %/s long-form);
 * - while text that is likely too quick or too small is on screen, loss rises by 1.5 %/s;
 * - for 1 s after a cut that lands on a beat, the loss rate is multiplied by 0.6.
 * Each step multiplies by exp(−rate × dt), so the curve never rises and stays within 0..1.
 */
function attentionCurve(end: number, shortForm: boolean, hook: Severity, stretches: { start: number; end: number }[], unreadable: { a: number; b: number }[], beatCuts: number[]): { t: number; v: number }[] {
  if (end <= EPS) return [{ t: 0, v: 1 }];
  const dt = end <= 120 ? 0.25 : end / 480;
  const base = shortForm ? 0.025 : 0.004;
  const hookExtra = hook === "high" ? 0.12 : hook === "medium" ? 0.06 : hook === "low" ? 0.02 : 0;
  const grace = shortForm ? 2 : 6;
  const stillExtra = shortForm ? 0.04 : 0.01;
  const out: { t: number; v: number }[] = [{ t: 0, v: 1 }];
  let v = 1;
  const n = Math.ceil(end / dt - 1e-9);
  for (let i = 1; i <= n; i++) {
    const t1 = Math.min(end, i * dt);
    const t0 = Math.min(end, (i - 1) * dt);
    const h = t1 - t0;
    const m = (t0 + t1) / 2;
    let rate = base;
    if (m < HOOK_WINDOW) rate += hookExtra;
    if (stretches.some((s) => m > s.start + grace && m < s.end)) rate += stillExtra;
    if (unreadable.some((u) => m >= u.a && m < u.b)) rate += 0.015;
    if (beatCuts.some((c) => m >= c && m < c + 1)) rate *= 0.6;
    v = v * Math.exp(-rate * h);
    out.push({ t: r3(t1), v: Math.round(clamp(v, 0, 1) * 10000) / 10000 });
  }
  return out;
}

const SEV_ORDER: Record<Severity, number> = { high: 0, medium: 1, low: 2, good: 3 };

function finish(platform: Platform, findings: Finding[], attention: { t: number; v: number }[], facts: Record<string, number | string>, empty: boolean): LensReport {
  findings.sort((a, b) => SEV_ORDER[a.severity] - SEV_ORDER[b.severity] || AREA_WEIGHTS[b.area] - AREA_WEIGHTS[a.area] || (a.at ?? 0) - (b.at ?? 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const areaNames = Object.keys(AREA_WEIGHTS) as Finding["area"][];
  const areas = areaNames.map((area) => {
    const fs = findings.filter((f) => f.area === area);
    const score = empty ? 0 : Math.max(0, 100 - fs.reduce((s, f) => s + PENALTY[f.severity], 0));
    const worst = fs.reduce<Severity>((w, f) => (SEV_ORDER[f.severity] < SEV_ORDER[w] ? f.severity : w), "good");
    const title = empty ? `${AREA_TITLES[area]}: nothing to check yet` : worst === "high" ? `${AREA_TITLES[area]}: likely needs work` : worst === "medium" ? `${AREA_TITLES[area]}: worth a look` : worst === "low" ? `${AREA_TITLES[area]}: small polish possible` : `${AREA_TITLES[area]}: looks good`;
    return { area, score, title };
  });
  const score = empty ? 0 : Math.round(areas.reduce((s, a) => s + a.score * AREA_WEIGHTS[a.area], 0) / 100);
  const highs = findings.filter((f) => f.severity === "high").length;
  const verdict = empty
    ? "Nothing to look at yet: add something to the timeline first."
    : score >= 90 && highs === 0
      ? "Likely ready to post. A few optional polish ideas are below."
      : score >= 70 && highs === 0
        ? "Close. The suggestions below would likely help viewers stay."
        : score >= 50
          ? "Worth another pass: some issues may cost viewers early."
          : "Likely to lose many viewers early. Start with the high-priority items below.";
  facts.findings = findings.filter((f) => f.severity !== "good").length;
  return { platform, score, verdict, areas, findings, attention, facts };
}

function short(s: string): string {
  const one = s.replace(/\s+/g, " ").trim();
  return one.length > 40 ? `${one.slice(0, 39)}…` : one;
}

/** Round a px position to a whole pixel, away from where it came from, so a move never falls short. */
function roundAway(v: number, dir: number): number {
  return dir > 0 ? Math.ceil(v) : Math.floor(v);
}

/* ------------------------------------------------------------------ evolve ------------------------------------------------------------ */

/** Order fixes are applied in: structural edits first (trims), then sound, text style and length, positions, opening, ending. */
const EVOLVE_ORDER: Finding["area"][] = ["technical", "sound", "text", "safe-zone", "hook", "pacing", "ending"];

/** What a command changes, as "clipId:field" keys, so two fixes never touch the same thing on the same clip. */
function touches(cmd: Record<string, unknown>): string[] {
  const id = String(cmd.id ?? "");
  switch (cmd.op) {
    case "clip.value":
    case "clip.keys":
    case "clip.key":
      return [`${id}:${String(cmd.prop)}`];
    case "clip.trim":
      return [`${id}:dur`, `${id}:start`];
    case "clip.move":
      return [`${id}:start`];
    case "clip.transition":
      return [cmd.in !== undefined ? `${id}:transitionIn` : "", cmd.out !== undefined ? `${id}:transitionOut` : ""].filter(Boolean);
    case "clip.set": {
      const patch = (cmd.patch ?? {}) as Record<string, unknown>;
      const out: string[] = [];
      for (const [k, v] of Object.entries(patch)) {
        if (v && typeof v === "object" && !Array.isArray(v)) for (const sub of Object.keys(v)) out.push(`${id}:${k}.${sub}`);
        else out.push(`${id}:${k}`);
      }
      return out;
    }
    default:
      return [`${id}:${String(cmd.op)}`];
  }
}

/**
 * Gather every safe fix of a report into one batch for the agent API, in a safe order, de-duplicated so that no two
 * fixes change the same property of the same clip (and so never move a clip twice). Fixes whose clips no longer exist
 * in the project are skipped. Returns the commands and the ids of the findings they fix.
 */
export function evolve(p: Project, report: LensReport): { commands: Record<string, unknown>[]; applied: string[] } {
  const exists = new Set<string>();
  for (const t of p.tracks) for (const c of t.clips) exists.add(c.id);
  const taken = new Set<string>();
  const commands: Record<string, unknown>[] = [];
  const applied: string[] = [];
  const fixable = report.findings.filter((f) => f.severity !== "good" && f.fix && f.fix.commands.length);
  for (const area of EVOLVE_ORDER) {
    for (const f of fixable) {
      if (f.area !== area || !f.fix) continue;
      const cmds = f.fix.commands;
      if (cmds.some((c) => typeof c.id !== "string" || !exists.has(c.id))) continue;
      const keys = cmds.flatMap(touches);
      // A trim changes timing; keep every other fix off that clip's timing and position.
      if (keys.some((k) => taken.has(k))) continue;
      for (const k of keys) taken.add(k);
      for (const c of cmds) commands.push(JSON.parse(JSON.stringify(c)) as Record<string, unknown>);
      applied.push(f.id);
    }
  }
  return { commands, applied };
}
