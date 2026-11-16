// HAIRSTYLES drawn the way an animator draws hair (studied 16 Nov 2026 from a hair-drawing guide the owner sent; measured
// and learned from, not copied): hair is a few LARGE CLUMPS, not strands; it GROWS FROM THE CROWN (the whorl, or the
// parting) and flows out and down; it is built in four LAYERS — the top (volume and shape), the front (the bangs: three
// to five big pointed clumps), the sides (framing the face), the back (tufts at the nape) — over one clear silhouette;
// a stray or two keeps it natural. Each style is a `hair3`: a smooth mass and designed locks (engine/hair.js), painted as
// one form (design.hair.paint 'clean': flat colour, one soft shade, one shine band, ink round the outline).
//
// A lock: from / via / to are [° round from the front (+ = the head's own left), ° up from level] on the head; w its
// width (m); lift [root, middle, tip] how far it stands off the mass (m); broad 0…1 how long it keeps its width before
// its point; hang (m) how far it falls freely past `to`; flick 0…1 how far that free end turns out.
// (Rules: devtests/hairdesign.mjs.)

/** The hairline of a cut over the tops of the ears. */
export const LINE_EARS = [[0, 27], [32, 25], [50, 13], [64, 2], [80, -8], [100, -14], [130, -26], [180, -36]];
/** A short cut: the ears free, the nape short. */
export const LINE_SHORT = [[0, 27], [32, 25], [50, 14], [64, 7], [80, 3], [100, -5], [130, -22], [180, -32]];
/** Long hair: it covers the ears and runs low at the nape. */
export const LINE_LONG = [[0, 27], [32, 25], [50, 12], [64, -2], [80, -14], [100, -22], [130, -32], [180, -40]];

// (th: a clump that stands off is SOLID to the head — a wedge, not a ribbon)
const lock = (from, to, o = {}) => ({ from, to, ...(o.via ? { via: o.via } : {}), w: o.w ?? 0.06, lift: o.lift ?? [0.004, 0.018, 0.014], broad: o.broad ?? 0.5,
  ...(o.hang ? { hang: o.hang, flick: o.flick ?? 0 } : {}), th: o.th ?? Math.max(0.012, (o.lift ?? [0, 0, 0.014])[2] * 0.9), thTip: 0.08, ...(o.stiff ? { stiff: o.stiff } : {}), ...(o.wave ? { wave: o.wave } : {}) });
const both = (f) => [...f(1), ...f(-1)];
/** a steady unevenness from clump to clump (−0.5…0.5): drawn hair is never a comb's teeth */
const jit = (i, k = 0) => (((i * 7 + k * 3) % 5) / 4) - 0.5;

/** the front layer: bangs as `n` big pointed clumps from the top of the head down over the forehead.
 *  o: { across: [az of the outer tips], low (° up of the lowest tip), sweep (° every tip leans: + to the head's left), w } */
function bangs(o) {
  const tips = o.across, low = o.low ?? 8, sweep = o.sweep ?? 0, L = [];
  tips.forEach((az, i) => {
    const edge = Math.abs(az) / Math.max(...tips.map(Math.abs), 1), el = low + 10 * edge * edge + 3 * jit(i, 1); // (the middle ones longest: the bangs make an arch)
    const root = (o.root ?? 70) + 4 * jit(i);
    L.push(lock([az * 0.35 - sweep * 0.8, root], [az + sweep * 0.3, el], { via: [az * 0.75 - sweep * 0.2, (root + el) / 2 + 4], w: (o.w ?? 0.05) * (1 - 0.25 * edge) * (1 + 0.15 * jit(i, 2)), lift: [0.004, 0.016, 0.006 + 0.004 * edge], broad: o.broad ?? 0.35 }));
  });
  return L;
}

/** the top layer: clumps that fan from the crown (or the parting) out over the head, ending where the next layer starts.
 *  o: { from: [az, el] the crown, at: [az of the clumps on one side], end (° up where they end), lift (tip), w, part (a
 *  parting at that az: each side's clumps start on its own side of it) } */
function topLayer(o) {
  const L = [];
  for (const sd of [1, -1]) o.at.forEach((az, i) => {
    const a = sd * az, j = jit(i, sd > 0 ? 0 : 2), from = o.part !== undefined ? [o.part + sd * 5, 80 - Math.abs(a - o.part) * 0.08] : [o.from[0] + (a - o.from[0]) * 0.05, o.from[1]];
    const end = typeof o.end === 'function' ? o.end(Math.abs(a)) : o.end;
    L.push(lock(from, [a, end + 5 * j], { via: [from[0] + (a - from[0]) * 0.6, (from[1] + end) / 2 + 12], w: (o.w ?? 0.075) * (1 + 0.14 * j), lift: [0.004, 0.02 * (o.vol ?? 1), (o.lift ?? 0.016) * (1 + 0.4 * j)], broad: o.broad ?? 0.55, ...(o.hang ? { hang: o.hang * (1 + 0.3 * j), flick: o.flick ?? 0 } : {}) }));
  });
  return L;
}

/** the side layer: clumps down the side of the head that frame the face (in front of the ear or over it). */
const sides = (o) => both((sd) => (o.at ?? [72]).map((az, i) => lock([sd * (az - 18), o.root ?? 52], [sd * az, o.to + 4 * jit(i, sd)], { via: [sd * (az - 6), (o.to + (o.root ?? 52)) / 2], w: o.w ?? 0.055, lift: [0.004, 0.014, o.lift ?? 0.012], broad: o.broad ?? 0.4, ...(o.hang ? { hang: o.hang, flick: o.flick ?? 0.4 } : {}) })));

/** the back layer: tufts at the nape, their tips turned out. */
const back = (o) => (o.at ?? [-150, -170, 170, 150]).map((az, i) => lock([az * 0.92, o.root ?? 30], [az, o.to + 5 * jit(i, 4)], { w: o.w ?? 0.06, lift: [0.004, 0.014, o.lift ?? 0.014], broad: 0.4, ...(o.hang ? { hang: o.hang * (1 + 0.3 * jit(i, 1)), flick: o.flick ?? 0.7 } : {}) }));

/** spikes: a row of wedges round the head, each lying on it from one height to a lower one and standing off in a point. */
const spikes = (o) => o.at.map((az, i) => lock([az, o.row[0] + 3 * jit(i)], [az + 8 * jit(i, 2), o.row[1] + 5 * jit(i, 1)], { w: o.w * (1 + 0.2 * jit(i, 3)), lift: [0.004, o.lift * 0.45, o.lift * (1 + 0.3 * jit(i, 4))], broad: 0, th: o.lift * 0.8, stiff: 4 }));

/** strays: a thin clump or two off the crown that breaks the outline. */
const strays = (list) => list.map(([a, b]) => lock(a, b, { w: 0.02, lift: [0.004, 0.022, 0.034], broad: 0 }));

const style = (vol, line, locks, extra = {}) => ({ n: 0, mass: { vol, flat: 0.12, line, crown: extra.crown ?? 0.7, nape: extra.nape ?? 0.75 }, locks, ...Object.fromEntries(Object.entries(extra).filter(([k]) => k !== 'crown' && k !== 'nape')) });

export const HAIR_DESIGNS = {
  /** messy (the default lead's hair): full on top, five bangs leaning one way, the ears half covered, tufts at the nape */
  messy: () => style(0.016, LINE_EARS, [
    ...topLayer({ from: [170, 84], at: [30, 60, 90, 118, 146, 172], end: (a) => (a < 50 ? 40 : a < 100 ? 4 : a < 140 ? -12 : -24), lift: 0.03, w: 0.075, hang: 0.014, flick: 0.8, vol: 1.3 }),
    ...sides({ at: [76], root: 50, to: -2, w: 0.05, hang: 0.012, flick: 0.6 }),
    ...back({ at: [-128, -150, -170, 170, 150, 128], to: -40, hang: 0.03, flick: 0.75, w: 0.055 }),   // (the nape: tufts that point down into the neck, not a bowl's edge)
    ...bangs({ across: [-30, -14, 1, 15, 29], low: 7, sweep: 10, w: 0.055 }),
    ...strays([[[160, 86], [100, 74]], [[-170, 86], [-120, 72]]]),
    ...spikes({ row: [86, 66], at: [150, -170, 120, -130], lift: 0.03, w: 0.07 }),
  ], { crown: 1, nape: 0.9 }),
  /** neat / short: close on the sides, a little volume on top, a short fringe combed to one side */
  neat: () => style(0.012, LINE_SHORT, [
    ...topLayer({ from: [175, 86], at: [30, 64, 100, 136, 168], end: (a) => (a < 50 ? 44 : a < 110 ? 14 : -10), lift: 0.008, w: 0.08, vol: 0.6, broad: 0.7 }),
    ...bangs({ across: [-22, -4, 14], low: 22, sweep: -16, w: 0.06, broad: 0.55 }),
  ]),
  /** spiky: every clump of the top stands off the head in a point, the bangs short and pointed */
  spiky: () => style(0.014, LINE_SHORT, [
    ...spikes({ row: [56, 84], at: [10, 60, 120, 175, -60, -120], lift: 0.06, w: 0.085 }),   // (the top ones sweep UP: their points stand over the crown)
    ...spikes({ row: [36, 64], at: [35, 90, 150, -150, -90, -35], lift: 0.05, w: 0.085 }),
    ...spikes({ row: [44, 8], at: [62, 100, 136, 168, -168, -136, -100, -62], lift: 0.034, w: 0.07 }),
    ...spikes({ row: [14, -26], at: [120, 150, 180, -150, -120], lift: 0.022, w: 0.075 }),
    ...bangs({ across: [-26, -9, 8, 25], low: 14, sweep: 6, w: 0.05, broad: 0.1 }),
  ]),
  /** wavy: to the bottom of the ears, every clump in an S, the ends turned out */
  wavy: () => style(0.026, LINE_EARS, [
    ...both((sd) => [30, 62, 94, 126, 158].map((az, i) => lock([sd * az * 0.1, 84], [sd * az, (az < 50 ? 26 : az < 110 ? -12 : -28) + 4 * jit(i, sd)], { via: [sd * az * 0.6, 54], w: 0.08, lift: [0.004, 0.024, 0.02], broad: 0.5, wave: [0.012, 1.5], hang: az < 50 ? 0 : 0.045, flick: 0.85 }))),
    ...bangs({ across: [-28, -10, 10, 28], low: 10, sweep: -8, w: 0.06, broad: 0.45 }).map((l) => ({ ...l, wave: [0.006, 1] })),
    ...back({ to: -32, hang: 0.045, flick: 0.85, w: 0.075 }).map((l) => ({ ...l, wave: [0.01, 1.5] })),
  ]),
  /** middle part: parted down the middle, two curtains that open off the forehead and frame the face */
  middlePart: () => style(0.018, LINE_EARS, [
    ...topLayer({ part: 0, at: [44, 78, 112, 146, 176], end: (a) => (a < 60 ? 22 : a < 120 ? 0 : -16), lift: 0.014, w: 0.08, hang: 0.012, flick: 0.5 }),
    ...both((sd) => [lock([sd * 4, 74], [sd * 34, 6], { via: [sd * 10, 40], w: 0.06, lift: [0.004, 0.018, 0.008], broad: 0.35 }), lock([sd * 6, 70], [sd * 20, 12], { via: [sd * 6, 40], w: 0.045, lift: [0.004, 0.016, 0.006], broad: 0.3 })]),
    ...back({ to: -30, hang: 0.012, flick: 0.8 }),
  ]),
  /** side part: parted over the left eye, the big side swept across the forehead, the small side neat */
  sidePart: () => style(0.018, LINE_EARS, [
    ...[18, -10, -40, -70, -104, -140, -172].map((az, i) => lock([42, 80 - i], [az, az > 0 ? 36 : az > -60 ? 22 + 4 * jit(i) : az > -120 ? -2 : -18], { via: [(42 + az) / 2, 64], w: 0.085, lift: [0.004, 0.022, 0.014], broad: 0.55, ...(az < -60 ? { hang: 0.01, flick: 0.5 } : {}) })),
    ...[74, 108, 142, 174].map((az, i) => lock([50, 78], [az, az < 90 ? 6 : az < 130 ? -6 : -20 + 3 * jit(i)], { via: [(50 + az) / 2, 58], w: 0.075, lift: [0.004, 0.012, 0.01], broad: 0.6 })),
    ...bangs({ across: [-34, -18, -2], low: 14, sweep: -26, w: 0.06, root: 76, broad: 0.4 }),
  ]),
  /** long, tied: combed back into a low ponytail, a lock either side of the face left loose */
  longTied: () => style(0.012, LINE_LONG, [
    ...both((sd) => [0, 30, 62, 96, 132].map((az) => lock([sd * az, az < 50 ? 30 : az < 80 ? 6 : -10], [sd * 172, -18], { via: [sd * (az + 180) / 2, az < 80 ? 72 : 30], w: 0.085, lift: [0.002, 0.01, 0.004], broad: 0.75 }))),
    ...both((sd) => [lock([sd * 30, 60], [sd * 44, 10], { via: [sd * 34, 34], w: 0.035, lift: [0.004, 0.012, 0.01], broad: 0.2, hang: 0.07, flick: 0.1 })]),
  ], { tail: { len: 0.3, w: 0.075, at: [180, -10], up: -1.6, locks: 5, tie: true } }),
  /** long, loose: to the shoulders, falling from a parting in long clumps, bangs over the brow */
  longLoose: () => style(0.022, LINE_LONG, [
    ...topLayer({ part: 6, at: [40, 70, 100, 130, 158, 178], end: (a) => (a < 60 ? 10 : -20), lift: 0.016, w: 0.085, hang: 0.17, flick: 0.12 }),
    ...bangs({ across: [-30, -12, 8, 26], low: 8, sweep: 8, w: 0.055, broad: 0.35 }),
    ...back({ at: [-140, -165, 165, 140], to: -36, hang: 0.18, flick: 0.1, w: 0.075 }),
  ]),
};
