// Small things the runner's looking tools share: a part of the picture to enlarge, where a body's joints are.

/** A crop given in parts of the picture — [left, top, width, height], each 0…1 — as whole pixels [x, y, w, h] inside it; null for none (or all of it). */
export function cropRect(W, H, crop) {
  if (!crop) return null;
  if (!Array.isArray(crop) || crop.length !== 4 || crop.some((v) => typeof v !== 'number' || !(v >= 0)) || !(crop[2] > 0) || !(crop[3] > 0)) throw new Error('crop is [left, top, width, height] in parts of the picture, each from 0 to 1 — e.g. [0.4, 0.3, 0.2, 0.2] for a fifth of the picture near its middle.');
  const x = Math.min(W - 2, Math.round(crop[0] * W)), y = Math.min(H - 2, Math.round(crop[1] * H)), w = Math.max(2, Math.min(W - x, Math.round(crop[2] * W))), h = Math.max(2, Math.min(H - y, Math.round(crop[3] * H)));
  return x === 0 && y === 0 && w === W && h === H ? null : [x, y, w, h];
}

/** Where joints of a pose are (metres, to the millimetre): { name: [x, y, z] }. names: default the head and the hips. */
export function jointsOf(pose, names = ['head', 'root']) {
  const out = {};
  for (const n of names) { const p = pose.j[n]; if (!p) throw new Error(`No joint "${n}". Joints: ${Object.keys(pose.j).join(' ')}.`); out[n] = p.map((v) => +v.toFixed(3)); }
  return out;
}
