import type { Animatable, Ease, Keyframe } from "./model";

export function ease(kind: Ease, x: number): number {
  const t = Math.max(0, Math.min(1, x));
  switch (kind) {
    case "linear":
      return t;
    case "in":
      return t * t * t;
    case "out":
      return 1 - Math.pow(1 - t, 3);
    case "inOut":
      return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    case "hold":
      return t < 1 ? 0 : 1;
    case "back": {
      const c1 = 1.70158;
      const c3 = c1 + 1;
      return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }
    case "elastic": {
      if (t === 0 || t === 1) return t;
      const c4 = (2 * Math.PI) / 3;
      return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1;
    }
    case "bounce": {
      const n1 = 7.5625;
      const d1 = 2.75;
      let u = t;
      if (u < 1 / d1) return n1 * u * u;
      if (u < 2 / d1) return n1 * (u -= 1.5 / d1) * u + 0.75;
      if (u < 2.5 / d1) return n1 * (u -= 2.25 / d1) * u + 0.9375;
      return n1 * (u -= 2.625 / d1) * u + 0.984375;
    }
  }
}

/** Value of an animatable property at local time t. The ease of a key shapes the segment that starts at it. */
export function valueAt(a: Animatable | undefined, t: number, fallback: number): number {
  if (a === undefined) return fallback;
  if (typeof a === "number") return a;
  if (a.length === 0) return fallback;
  const first = a[0]!;
  if (t <= first.t) return first.v;
  const last = a[a.length - 1]!;
  if (t >= last.t) return last.v;
  // Binary search for the segment.
  let lo = 0;
  let hi = a.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (a[mid]!.t <= t) lo = mid;
    else hi = mid;
  }
  const k0 = a[lo]!;
  const k1 = a[hi]!;
  const span = k1.t - k0.t;
  const x = span > 0 ? (t - k0.t) / span : 1;
  return k0.v + (k1.v - k0.v) * ease(k0.ease, x);
}

/** Set (or add) a keyframe at t, keeping the list sorted. Converts a constant into keyframes. */
export function setKey(a: Animatable | undefined, t: number, v: number, kind: Ease = "inOut", fallback = 0): Keyframe[] {
  const list: Keyframe[] = typeof a === "number" ? [] : a ? a.map((k) => ({ ...k })) : [];
  if (typeof a === "number" && list.length === 0 && Math.abs(t) > 1e-6) list.push({ t: 0, v: a, ease: kind });
  if (a === undefined && Math.abs(t) > 1e-6 && fallback !== v) list.push({ t: 0, v: fallback, ease: kind });
  const i = list.findIndex((k) => Math.abs(k.t - t) < 1e-4);
  if (i >= 0) list[i] = { t, v, ease: kind };
  else list.push({ t, v, ease: kind });
  list.sort((x, y) => x.t - y.t);
  return list;
}

export function removeKey(a: Animatable | undefined, t: number): Animatable | undefined {
  if (!Array.isArray(a)) return a;
  const list = a.filter((k) => Math.abs(k.t - t) >= 1e-4);
  if (list.length === 0) return undefined;
  if (list.length === 1) return list[0]!.v;
  return list;
}

export function isAnimated(a: Animatable | undefined): a is Keyframe[] {
  return Array.isArray(a) && a.length > 0;
}

export function hasKeyAt(a: Animatable | undefined, t: number): boolean {
  return Array.isArray(a) && a.some((k) => Math.abs(k.t - t) < 1e-4);
}

/** Shift every keyframe by dt (used when trimming the head of a clip). */
export function shiftKeys(a: Animatable | undefined, dt: number): Animatable | undefined {
  if (!Array.isArray(a)) return a;
  return a.map((k) => ({ ...k, t: k.t + dt }));
}
