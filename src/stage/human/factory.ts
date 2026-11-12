/**
 * Character factory: sculpts a complete person (body, face, clothes, hair, skeleton, skin weights, expression morphs)
 * from the CC0 MakeHuman base body in human.pack and a small JSON spec. Runs in a few tens of milliseconds, so bodies
 * can be reshaped live: age, build, height, proportions, every face detail, outfit pieces, hair and beard.
 * Units: metres, feet on y = 0, facing +Z. Same construction the Orez film used (tools: mhlib.py, build_char.py).
 */
import type { Pack } from "../pack";

export interface HumanPackMeta {
  verts: number;
  bodyVerts: number;
  groups: Record<string, number>;
  bones: { name: string; parent: number; head: [number, number]; tail: [number, number] }[];
  regions: Record<string, number>;
  targets: Record<string, [number, number, number]>;
  expressions: string[];
}

export interface BodyMacro {
  /** 0 female … 1 male */
  gender: number;
  /** Age in years (1…90). */
  age: number;
  /** 0…1 */
  muscle: number;
  /** 0…1 */
  weight: number;
  /** MakeHuman height modifier 0…1 (0.5 neutral); real height is `CharSpec.height`. */
  height?: number;
  /** 0 uncommon … 1 ideal proportions (0.5 neutral). */
  proportions?: number;
  /** [caucasian, asian, african] mix, sums to 1. */
  race?: [number, number, number];
  cup?: number;
  firm?: number;
}

export interface Garment {
  kind: "tunic" | "trousers" | "boots" | "cuirass" | "vambrace" | "greave" | "wraps" | "skirt";
  name?: string;
  material?: "cloth" | "leather" | "metal";
  /** Distance from the skin, metres. */
  offset?: number;
  [param: string]: unknown;
}

export interface HairSpec {
  /** Scalp cap thickness (m); omit for no cap (bald or helmet). */
  cap?: number;
  mess?: number;
  /** Hairline shift (m, negative = lower forehead). */
  line?: number;
  /** Long hair shell from the guide strips. */
  shell?: boolean;
  cut?: number;
  puff?: number;
  rag?: number;
  nobangs?: boolean;
  beard?: { thr?: number; thick?: number; mess?: number; length?: number; mustache?: boolean };
  /** A simulated tail of hair from the crown (top-knot, ponytail): length and thickness in metres. */
  tail?: { length: number; radius?: number };
}

export interface CharSpec {
  /** Standing height in metres. */
  height: number;
  macro: BodyMacro;
  /** Extra MakeHuman targets by name (e.g. "nose/nose-hump-incr": 0.3). */
  detail?: Record<string, number>;
  garments?: Garment[];
  hair?: HairSpec;
  /** Colours and surface look, passed to the materials. */
  look?: Record<string, unknown>;
}

export interface PartData {
  name: string;
  material: string;
  vcount: number;
  pos: Float32Array;
  aux: Float32Array;
  idx: Uint32Array;
  skinIndex: Uint16Array;
  skinWeight: Float32Array;
  mask?: Float32Array;
  morphs?: { i: Uint32Array; d: Float32Array }[];
  params: Record<string, unknown>;
}

export interface CharData {
  name: string;
  height: number;
  bones: { name: string; parent: number; head: [number, number, number]; tail: [number, number, number] }[];
  morphNames: string[];
  parts: PartData[];
  landmarks: Record<string, number | number[]>;
  look: Record<string, unknown>;
}

/* ------------------------------ macro modifiers ------------------------------ */

function tri(v: number, names: [string, string, string]): Record<string, number> {
  if (v > 0.5) return { [names[2]]: (v - 0.5) * 2, [names[1]]: 1 - (v - 0.5) * 2 };
  return { [names[0]]: (0.5 - v) * 2, [names[1]]: 1 - (0.5 - v) * 2 };
}
/** MakeHuman age slider (0…1) from years. */
export function ageFromYears(y: number): number {
  if (y < 10) return (0.1875 * Math.max(0, y - 1)) / 9;
  if (y < 25) return 0.1875 + ((y - 10) / 15) * 0.3125;
  return 0.5 + (Math.min(90, y) - 25) / 65 * 0.5;
}
function ageWeights(a: number): Record<string, number> {
  if (a < 0.1875) {
    const t = a / 0.1875;
    return { baby: 1 - t, child: t };
  }
  if (a < 0.5) {
    const t = (a - 0.1875) / 0.3125;
    return { child: 1 - t, young: t };
  }
  const t = (a - 0.5) / 0.5;
  return { young: 1 - t, old: t };
}

/** {target: weight} for the macro sliders, the way MakeHuman's macro modifiers combine them. */
export function macroTargets(m: BodyMacro): Record<string, number> {
  const gender = clamp01(m.gender);
  const age = ageFromYears(m.age);
  const height = m.height ?? 0.5;
  const proportions = m.proportions ?? 0.5;
  const race = m.race ?? [1 / 3, 1 / 3, 1 / 3];
  const G: Record<string, number> = { male: gender, female: 1 - gender };
  const A = ageWeights(age);
  const M = tri(clamp01(m.muscle), ["minmuscle", "averagemuscle", "maxmuscle"]);
  const W = tri(clamp01(m.weight), ["minweight", "averageweight", "maxweight"]);
  const out: Record<string, number> = {};
  const add = (k: string, w: number) => {
    if (w > 1e-6) out[k] = (out[k] ?? 0) + w;
  };
  const races = ["caucasian", "asian", "african"] as const;
  for (const [g, gw] of Object.entries(G))
    for (const [a, aw] of Object.entries(A)) {
      races.forEach((r, i) => add(`macrodetails/${r}-${g}-${a}`, gw * aw * (race[i] ?? 0)));
      for (const [mu, mw] of Object.entries(M))
        for (const [w, ww] of Object.entries(W)) {
          const k = gw * aw * mw * ww;
          add(`macrodetails/universal-${g}-${a}-${mu}-${w}`, k);
          if (height !== 0.5) add(`macrodetails/height/${g}-${a}-${mu}-${w}-${height > 0.5 ? "maxheight" : "minheight"}`, k * Math.abs(height - 0.5) * 2);
          if (proportions !== 0.5) add(`macrodetails/proportions/${g}-${a}-${mu}-${w}-${proportions > 0.5 ? "idealproportions" : "uncommonproportions"}`, k * Math.abs(proportions - 0.5) * 2);
          if (g === "female" && a !== "baby") {
            const C = tri(clamp01(m.cup ?? 0.5), ["mincup", "averagecup", "maxcup"]);
            const F = tri(clamp01(m.firm ?? 0.5), ["minfirmness", "averagefirmness", "maxfirmness"]);
            for (const [c, cw] of Object.entries(C)) for (const [f, fw] of Object.entries(F)) add(`breast/female-${a}-${mu}-${w}-${c}-${f}`, k * cw * fw);
          }
        }
    }
  return out;
}

/* ------------------------------ small array helpers ------------------------------ */

const clamp01 = (x: number) => Math.min(1, Math.max(0, x));
const clip = (x: number, a: number, b: number) => Math.min(b, Math.max(a, x));
const num = (v: unknown, d: number): number => (typeof v === "number" && Number.isFinite(v) ? v : d);

/** Per-vertex normals (N×3) from triangles given as quads (n×4). Vertices no quad touches stay zero. */
function quadNormals(P: Float32Array, quads: Uint32Array, N: number): Float32Array {
  const n = new Float32Array(N * 3);
  const tri = (a: number, b: number, c: number) => {
    const ax = P[a * 3]!, ay = P[a * 3 + 1]!, az = P[a * 3 + 2]!;
    const ux = P[b * 3]! - ax, uy = P[b * 3 + 1]! - ay, uz = P[b * 3 + 2]! - az;
    const vx = P[c * 3]! - ax, vy = P[c * 3 + 1]! - ay, vz = P[c * 3 + 2]! - az;
    const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    for (const i of [a, b, c]) {
      n[i * 3] = n[i * 3]! + nx;
      n[i * 3 + 1] = n[i * 3 + 1]! + ny;
      n[i * 3 + 2] = n[i * 3 + 2]! + nz;
    }
  };
  for (let q = 0; q < quads.length; q += 4) {
    tri(quads[q]!, quads[q + 1]!, quads[q + 2]!);
    tri(quads[q]!, quads[q + 2]!, quads[q + 3]!);
  }
  for (let i = 0; i < N; i++) {
    const l = Math.hypot(n[i * 3]!, n[i * 3 + 1]!, n[i * 3 + 2]!) || 1;
    n[i * 3] = n[i * 3]! / l;
    n[i * 3 + 1] = n[i * 3 + 1]! / l;
    n[i * 3 + 2] = n[i * 3 + 2]! / l;
  }
  return n;
}

/** Quads whose four corners all pass `ok`. */
function quadsAll(quads: Uint32Array, ok: Uint8Array): Uint32Array {
  const out: number[] = [];
  for (let q = 0; q < quads.length; q += 4) if (ok[quads[q]!] && ok[quads[q + 1]!] && ok[quads[q + 2]!] && ok[quads[q + 3]!]) out.push(quads[q]!, quads[q + 1]!, quads[q + 2]!, quads[q + 3]!);
  return Uint32Array.from(out);
}
function uniqueSorted(idx: ArrayLike<number>, N: number): Uint32Array {
  const seen = new Uint8Array(N);
  for (let i = 0; i < idx.length; i++) seen[idx[i]!] = 1;
  let n = 0;
  for (let i = 0; i < N; i++) n += seen[i]!;
  const out = new Uint32Array(n);
  for (let i = 0, k = 0; i < N; i++) if (seen[i]) out[k++] = i;
  return out;
}
function concatU32(...a: Uint32Array[]): Uint32Array {
  const out = new Uint32Array(a.reduce((s, x) => s + x.length, 0));
  let o = 0;
  for (const x of a) {
    out.set(x, o);
    o += x.length;
  }
  return out;
}
/** 1 on vertices that sit on the open border of a quad patch (edges used by one quad only). */
function edgeMask(quads: Uint32Array, N: number): Float32Array {
  const count = new Map<number, number>();
  for (let q = 0; q < quads.length; q += 4)
    for (let k = 0; k < 4; k++) {
      const a = quads[q + k]!, b = quads[q + ((k + 1) % 4)]!;
      const key = a < b ? a * 65536 + b : b * 65536 + a;
      count.set(key, (count.get(key) ?? 0) + 1);
    }
  const m = new Float32Array(N);
  for (const [key, c] of count)
    if (c === 1) {
      m[Math.floor(key / 65536)] = 1;
      m[key % 65536] = 1;
    }
  return m;
}
const hnoise = (x: number, y: number, z: number, f: number) => Math.sin(x * f * 1.7 + y * f) * Math.cos(z * f * 1.3 - y * f * 0.7);

/* ------------------------------ the factory ------------------------------ */

export class HumanFactory {
  readonly meta: HumanPackMeta;
  private readonly V: Float32Array;
  private readonly N: number;
  private readonly body: number;
  private readonly region: Uint8Array;
  private readonly R: Record<string, number>;
  constructor(private readonly pack: Pack<HumanPackMeta>) {
    this.meta = pack.meta;
    this.V = pack.f32("v");
    this.N = this.meta.verts;
    this.body = this.meta.bodyVerts;
    this.region = pack.u8("region");
    this.R = this.meta.regions;
  }

  /** Names of every shape target (for "detail"), optionally filtered by a prefix like "nose/". */
  targets(prefix = ""): string[] {
    return Object.keys(this.meta.targets).filter((k) => k.startsWith(prefix) && !k.startsWith("macrodetails/") && !k.startsWith("breast/") && !k.startsWith("expression/"));
  }

  private applyTargets(tw: Record<string, number>): { P: Float32Array; missing: string[] } {
    const P = Float32Array.from(this.V);
    const tI = this.pack.u16("tIdx");
    const tD = this.pack.i16("tD");
    const missing: string[] = [];
    for (const [name, w] of Object.entries(tw)) {
      const t = this.meta.targets[name];
      if (!t) {
        missing.push(name);
        continue;
      }
      const [start, n, scale] = t;
      const k = scale * w;
      for (let i = 0; i < n; i++) {
        const v = tI[start + i]! * 3;
        const d = (start + i) * 3;
        P[v] = P[v]! + tD[d]! * k;
        P[v + 1] = P[v + 1]! + tD[d + 1]! * k;
        P[v + 2] = P[v + 2]! + tD[d + 2]! * k;
      }
    }
    return { P, missing };
  }

  build(name: string, spec: CharSpec): CharData {
    const { N, body, region, R } = this;
    const pack = this.pack;
    const tw = macroTargets(spec.macro);
    for (const [k, w] of Object.entries(spec.detail ?? {})) tw[k] = (tw[k] ?? 0) + w;
    const { P } = this.applyTargets(tw);
    let ymin = Infinity, ymax = -Infinity;
    for (let i = 0; i < body; i++) {
      const y = P[i * 3 + 1]!;
      if (y < ymin) ymin = y;
      if (y > ymax) ymax = y;
    }
    const s = spec.height / (ymax - ymin);
    for (let i = 0; i < N; i++) {
      P[i * 3] = P[i * 3]! * s;
      P[i * 3 + 1] = (P[i * 3 + 1]! - ymin) * s;
      P[i * 3 + 2] = P[i * 3 + 2]! * s;
    }
    const X = (i: number) => P[i * 3]!, Y = (i: number) => P[i * 3 + 1]!, Z = (i: number) => P[i * 3 + 2]!;

    // ---------- skeleton ----------
    const jv = pack.u32("jointVerts");
    const jpos = ([off, n]: [number, number]): [number, number, number] => {
      let x = 0, y = 0, z = 0;
      for (let i = 0; i < n; i++) {
        const v = jv[off + i]!;
        x += X(v);
        y += Y(v);
        z += Z(v);
      }
      return [x / n, y / n, z / n];
    };
    const bones = this.meta.bones.map((b) => ({ name: b.name, parent: b.parent, head: jpos(b.head), tail: jpos(b.tail) }));
    const bone = (n: string) => {
      const b = bones.find((x) => x.name === n);
      if (!b) throw new Error(`Body has no bone ${n}`);
      return b;
    };
    const top = pack.u16("skinIndex");
    const w4 = pack.f32("skinWeight");

    // ---------- expression morphs (sparse, scaled) ----------
    const tI = pack.u16("tIdx");
    const tD = pack.i16("tD");
    const EXPR = this.meta.expressions;
    const morphs = EXPR.map((e) => {
      const t = this.meta.targets[`expression/units/caucasian/${e}`]!;
      const [start, n, scale] = t;
      const i = new Uint32Array(n);
      const d = new Float32Array(n * 3);
      for (let k = 0; k < n; k++) {
        i[k] = tI[start + k]!;
        for (let c = 0; c < 3; c++) d[k * 3 + c] = tD[(start + k) * 3 + c]! * scale * s;
      }
      return { i, d };
    });

    // ---------- masks on the skin: lip, blush, lid, beard ----------
    const lip = new Float32Array(N);
    {
      const m = morphs[EXPR.indexOf("mouth-eversion")];
      if (m) {
        let mx = 1e-9;
        const mag = new Float32Array(m.i.length);
        for (let k = 0; k < m.i.length; k++) {
          mag[k] = Math.hypot(m.d[k * 3]!, m.d[k * 3 + 1]!, m.d[k * 3 + 2]!);
          if (mag[k]! > mx) mx = mag[k]!;
        }
        for (let k = 0; k < m.i.length; k++) lip[m.i[k]!] = Math.pow(clamp01((mag[k]! / mx) * 1.6), 0.8);
      }
    }
    const wRis = pack.f32("w:risorius"), wOrb = pack.f32("w:orbicularis"), wJaw = pack.f32("w:jaw"), wOris = pack.f32("w:oris");
    const beardW = new Float32Array(N);
    const mask = new Float32Array(N * 4);
    for (let i = 0; i < N; i++) {
      beardW[i] = clamp01(wJaw[i]! * 1.4 + wOris[i]! * 0.9 + wRis[i]! * 0.35);
      mask[i * 4] = lip[i]!;
      mask[i * 4 + 1] = clamp01(wRis[i]! * 1.2);
      mask[i * 4 + 2] = clamp01(wOrb[i]! * 1.5);
      mask[i * 4 + 3] = beardW[i]!;
    }

    const G = (g: string) => pack.u32(`g:${g}`);
    const parts: PartData[] = [];
    const addPart = (pname: string, material: string, quads: Uint32Array, o: { pos?: Float32Array; aux?: Float32Array; morphs?: boolean; mask?: Float32Array; params?: Record<string, unknown> } = {}) => {
      const used = uniqueSorted(quads, N);
      const remap = new Int32Array(N).fill(-1);
      used.forEach((v, k) => (remap[v] = k));
      const src = o.pos ?? P;
      const auxSrc = o.aux ?? src;
      const vcount = used.length;
      const pos = new Float32Array(vcount * 3), aux = new Float32Array(vcount * 3), si = new Uint16Array(vcount * 4), sw = new Float32Array(vcount * 4);
      const pm = o.mask ? new Float32Array(vcount * 4) : undefined;
      for (let k = 0; k < vcount; k++) {
        const v = used[k]!;
        for (let c = 0; c < 3; c++) {
          pos[k * 3 + c] = src[v * 3 + c]!;
          aux[k * 3 + c] = auxSrc[v * 3 + c]!;
        }
        for (let c = 0; c < 4; c++) {
          si[k * 4 + c] = top[v * 4 + c]!;
          sw[k * 4 + c] = w4[v * 4 + c]!;
          if (pm) pm[k * 4 + c] = o.mask![v * 4 + c]!;
        }
      }
      const nq = quads.length / 4;
      const idx = new Uint32Array(nq * 6);
      for (let q = 0; q < nq; q++) {
        const a = remap[quads[q * 4]!]!, b = remap[quads[q * 4 + 1]!]!, c = remap[quads[q * 4 + 2]!]!, d = remap[quads[q * 4 + 3]!]!;
        idx.set([a, b, c, a, c, d], q * 6);
      }
      const part: PartData = { name: pname, material, vcount, pos, aux, idx, skinIndex: si, skinWeight: sw, params: o.params ?? {} };
      if (pm) part.mask = pm;
      if (o.morphs)
        part.morphs = morphs.map((m) => {
          const ii: number[] = [], dd: number[] = [];
          for (let k = 0; k < m.i.length; k++) {
            const r = remap[m.i[k]!]!;
            if (r < 0) continue;
            const dx = m.d[k * 3]!, dy = m.d[k * 3 + 1]!, dz = m.d[k * 3 + 2]!;
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 1e-7) continue;
            ii.push(r);
            dd.push(dx, dy, dz);
          }
          return { i: Uint32Array.from(ii), d: Float32Array.from(dd) };
        });
      parts.push(part);
      return part;
    };

    // ---------- landmarks ----------
    const meanOf = (quads: Uint32Array): [number, number, number] => {
      let x = 0, y = 0, z = 0;
      for (let i = 0; i < quads.length; i++) {
        x += X(quads[i]!);
        y += Y(quads[i]!);
        z += Z(quads[i]!);
      }
      return [x / quads.length, y / quads.length, z / quads.length];
    };
    const eyeL = meanOf(G("helper-l-eye")), eyeR = meanOf(G("helper-r-eye"));
    let eyeRadius = 0;
    {
      const ids = uniqueSorted(G("helper-l-eye"), N);
      for (const v of ids) eyeRadius += Math.hypot(X(v) - eyeL[0], Y(v) - eyeL[1], Z(v) - eyeL[2]);
      eyeRadius /= ids.length;
    }
    const neckY = bone("neck01").head[1], hipY = bone("upperleg01.L").head[1], kneeY = bone("lowerleg01.L").head[1], ankleY = bone("foot.L").head[1];
    const side = { L: 0, R: 1 } as const;
    const J = (n: string) => [bone(`${n}.L`).head, bone(`${n}.R`).head] as const;
    const wristJ = J("wrist"), shoulderJ = J("upperarm01"), elbowJ = J("lowerarm01"), hipJ = J("upperleg01"), footJ = J("foot");

    const tq = G("helper-tights"), sq = G("helper-skirt");
    const tn = quadNormals(P, tq, N), sn = quadNormals(P, sq, N);
    const isReg = (i: number, ...names: string[]) => names.some((n) => region[i] === R[n]);

    /** Push verts away from the bone axis a→b; the factor grows from k0 at a to k1 at b. */
    const radial = (Pg: Float32Array, ids: number[], a: readonly number[], b: readonly number[], k0: number, k1: number, power = 1.4, droop = 0, bag = 0) => {
      const abx = b[0]! - a[0]!, aby = b[1]! - a[1]!, abz = b[2]! - a[2]!;
      const L2 = abx * abx + aby * aby + abz * abz;
      for (const v of ids) {
        const px = Pg[v * 3]!, py = Pg[v * 3 + 1]!, pz = Pg[v * 3 + 2]!;
        const t = clamp01(((px - a[0]!) * abx + (py - a[1]!) * aby + (pz - a[2]!) * abz) / L2);
        const cx = a[0]! + t * abx, cy = a[1]! + t * aby, cz = a[2]! + t * abz;
        const rx = px - cx, ry = py - cy, rz = pz - cz;
        const f = k0 + (k1 - k0) * Math.pow(t, power);
        const rl = Math.max(Math.hypot(rx, ry, rz), 1e-6);
        const under = clip(-ry / rl, 0, 1);
        const g = f + bag * Math.pow(t, 1.5) * under;
        Pg[v * 3] = cx + rx * g;
        Pg[v * 3 + 1] = cy + ry * g - droop * t * t * under;
        Pg[v * 3 + 2] = cz + rz * g;
      }
    };

    // ---------- garments (they decide which skin is hidden) ----------
    const hide = new Uint8Array(N);
    const REGS: Record<string, string[]> = { tunic: ["torso", "upperarm", "forearm"], trousers: ["thigh", "shin", "torso"], boots: ["foot", "shin"], cuirass: ["torso", "upperarm"], vambrace: ["forearm"], greave: ["shin"], wraps: ["forearm"] };
    for (const g of spec.garments ?? []) {
      const kind = g.kind;
      const off = num(g.offset, 0.012);
      const Pg = Float32Array.from(P);
      if (kind !== "skirt") {
        const regs = REGS[kind];
        if (!regs) continue;
        const ok = new Uint8Array(N);
        const shaft = num(g.shaft, 0.28);
        for (let i = 0; i < N; i++) {
          if (!isReg(i, ...regs)) continue;
          const y = Y(i);
          let k = true;
          if (kind === "tunic") k = y > hipY - num(g.below_hip, 0.02) && !(g.sleeve === "short" && isReg(i, "forearm")) && !(g.sleeve === "none" && isReg(i, "forearm", "upperarm") && dist3(P, i, shoulderJ[X(i) > 0 ? 0 : 1]) > num(g.cap, 0.07));
          else if (kind === "trousers") k = y < hipY + num(g.rise, 0.16) && y > ankleY + num(g.ankle, 0.03);
          else if (kind === "boots") k = y < ankleY + shaft;
          else if (kind === "cuirass") k = y > hipY + 0.02 && !(isReg(i, "upperarm") && (!g.shoulders || dist3(P, i, shoulderJ[X(i) > 0 ? 0 : 1]) > num(g.cap, 0.1)));
          else if (kind === "greave") k = y > ankleY + 0.04 && y < kneeY + 0.03;
          else if (kind === "vambrace" || kind === "wraps") k = !(isReg(i, "forearm") && (dist3(P, i, wristJ[0]) < 0.035 || dist3(P, i, wristJ[1]) < 0.035));
          if (k) ok[i] = 1;
        }
        const quads = quadsAll(tq, ok);
        if (!quads.length) continue;
        const ids = uniqueSorted(quads, N);
        for (const v of ids) for (let c = 0; c < 3; c++) Pg[v * 3 + c] = Pg[v * 3 + c]! + tn[v * 3 + c]! * off;
        if (kind === "tunic") {
          const fl = num(g.flare, 1.5);
          const long = g.sleeve !== "short" && g.sleeve !== "none";
          for (const sd of ["L", "R"] as const) {
            const arm: number[] = [];
            for (const v of ids) if (isReg(v, "upperarm", "forearm") && X(v) > 0 === (sd === "L")) arm.push(v);
            if (arm.length) radial(Pg, arm, shoulderJ[side[sd]], long ? wristJ[side[sd]] : elbowJ[side[sd]], 1.0, fl, 2.2, num(g.droop, 0.05), num(g.bag, 0.5));
          }
          for (const v of ids)
            if (Y(v) > neckY - 0.035 && isReg(v, "torso")) {
              for (let c = 0; c < 3; c++) Pg[v * 3 + c] = Pg[v * 3 + c]! + tn[v * 3 + c]! * 0.006;
              Pg[v * 3 + 1] = Pg[v * 3 + 1]! + 0.012;
            }
        }
        if (kind === "trousers")
          for (const sd of ["L", "R"] as const) {
            const leg: number[] = [];
            for (const v of ids) if (isReg(v, "thigh", "shin") && X(v) > 0 === (sd === "L")) leg.push(v);
            if (leg.length) radial(Pg, leg, hipJ[side[sd]], footJ[side[sd]], num(g.wide0, 1.12), num(g.wide1, 1.35), 0.8);
          }
        // Hide the skin underneath tight things and well inside loose ones.
        for (let i = 0; i < body; i++) {
          if (!isReg(i, ...regs)) continue;
          const y = Y(i);
          let c = false;
          if (kind === "tunic") c = y > hipY && y < neckY - 0.1 && !(isReg(i, "forearm") && !g.hide_forearm) && !(g.sleeve === "none" && isReg(i, "upperarm"));
          else if (kind === "trousers") c = y < hipY + 0.1 && y > ankleY + Math.max(0.1, num(g.ankle, 0.03) + 0.07);
          else if (kind === "boots") c = y < ankleY + shaft - 0.04;
          else if (kind === "cuirass") c = y > hipY + 0.06 && y < neckY - 0.06 && isReg(i, "torso");
          if (c) hide[i] = 1;
        }
        addPart(g.name ?? kind, g.material ?? "cloth", quads, { pos: Pg, aux: P, params: g as Record<string, unknown> });
      } else {
        const all = uniqueSorted(sq, N);
        let topY = -Infinity, minY = Infinity, zmean = 0;
        for (const v of all) {
          topY = Math.max(topY, Y(v));
          minY = Math.min(minY, Y(v));
        }
        const length = num(g.length, 1.0);
        const bot = topY - (topY - minY) * length;
        const ok = new Uint8Array(N);
        for (const v of all) if (Y(v) >= bot - 1e-4) ok[v] = 1;
        const quads = quadsAll(sq, ok);
        if (!quads.length) continue;
        const ids = uniqueSorted(quads, N);
        for (const v of ids) zmean += Z(v);
        zmean /= ids.length;
        const flare = num(g.flare, 0.25);
        for (const v of ids) {
          for (let c = 0; c < 3; c++) Pg[v * 3 + c] = Pg[v * 3 + c]! + sn[v * 3 + c]! * off;
          const t = (topY - Y(v)) / Math.max(topY - bot, 1e-6);
          Pg[v * 3] = Pg[v * 3]! * (1 + flare * t);
          Pg[v * 3 + 2] = zmean + (Pg[v * 3 + 2]! - zmean) * (1 + flare * t);
        }
        addPart(g.name ?? "skirt", g.material ?? "cloth", quads, { pos: Pg, aux: P, params: g as Record<string, unknown> });
        if (length > 0.45) for (let i = 0; i < body; i++) if (isReg(i, "thigh") && Y(i) < hipY - 0.05 && Y(i) > bot + 0.08) hide[i] = 1;
      }
    }

    // ---------- skin, eyes, mouth, lashes ----------
    const bodyQ = G("body");
    {
      const keep: number[] = [];
      for (let q = 0; q < bodyQ.length; q += 4) if (!(hide[bodyQ[q]!] && hide[bodyQ[q + 1]!] && hide[bodyQ[q + 2]!] && hide[bodyQ[q + 3]!])) keep.push(bodyQ[q]!, bodyQ[q + 1]!, bodyQ[q + 2]!, bodyQ[q + 3]!);
      addPart("skin", "skin", Uint32Array.from(keep), { morphs: true, mask });
    }
    const eyeAux = new Float32Array(N * 3);
    for (const [gname, c] of [["helper-l-eye", eyeL], ["helper-r-eye", eyeR]] as const)
      for (const v of uniqueSorted(G(gname), N)) {
        const dx = X(v) - c[0], dy = Y(v) - c[1], dz = Z(v) - c[2];
        const l = Math.hypot(dx, dy, dz) || 1;
        eyeAux[v * 3] = dx / l;
        eyeAux[v * 3 + 1] = dy / l;
        eyeAux[v * 3 + 2] = dz / l;
      }
    addPart("eyes", "eye", concatU32(G("helper-l-eye"), G("helper-r-eye")), { aux: eyeAux });
    addPart("teeth", "teeth", concatU32(G("helper-upper-teeth"), G("helper-lower-teeth")), { morphs: true });
    addPart("tongue", "tongue", G("helper-tongue"), { morphs: true });
    addPart("lashes", "lash", concatU32(G("helper-l-eyelashes-1"), G("helper-l-eyelashes-2"), G("helper-r-eyelashes-1"), G("helper-r-eyelashes-2")), { morphs: true });

    // ---------- hair: scalp cap, shell from the guide strips, beard ----------
    const hs = spec.hair ?? {};
    const kk = eyeL[0] / 0.0319;
    const eyeY = eyeL[1];
    const skinN = quadNormals(P, bodyQ, N);
    const widen = (m: Float32Array) => {
      const out = new Float32Array(N * 4);
      for (let i = 0; i < N; i++) out[i * 4] = m[i]!;
      return out;
    };
    if (hs.cap !== undefined && hs.cap !== null) {
      const sel = new Uint8Array(N);
      for (let i = 0; i < body; i++) {
        const isHead = isReg(i, "head") || (isReg(i, "torso") && Y(i) > neckY + 0.02);
        if (!isHead) continue;
        const z = Z(i);
        const thr = eyeY + (-0.029 + num(hs.line, 0)) * kk + 0.83 * z + 0.045 * kk * Math.exp(-(((z - 0.035 * kk) / (0.028 * kk)) ** 2)) * (Math.abs(X(i)) > 0.05 * kk ? 1 : 0);
        if (Y(i) > thr) sel[i] = 1;
      }
      const cq = quadsAll(bodyQ, sel);
      if (cq.length) {
        const Pc = Float32Array.from(P);
        const ids = uniqueSorted(cq, N);
        let em = edgeMask(cq, N);
        for (let pass = 0; pass < 2; pass++) {
          // soften: spread the edge value inwards one ring
          const e2 = Float32Array.from(em);
          for (let q = 0; q < cq.length; q += 4) {
            const mx = Math.max(em[cq[q]!]!, em[cq[q + 1]!]!, em[cq[q + 2]!]!, em[cq[q + 3]!]!) * 0.5;
            for (let k = 0; k < 4; k++) if (e2[cq[q + k]!]! < mx) e2[cq[q + k]!] = mx;
          }
          em = e2;
        }
        const mess = num(hs.mess, 0);
        for (const v of ids) {
          const lift = (hs.cap + mess * (0.5 + 0.5 * hnoise(X(v), Y(v), Z(v), 90 / kk))) * (1 - 0.9 * em[v]!);
          for (let c = 0; c < 3; c++) Pc[v * 3 + c] = Pc[v * 3 + c]! + skinN[v * 3 + c]! * lift;
        }
        addPart("haircap", "hair", cq, { pos: Pc, aux: Pc, params: { rag: num(hs.rag, 1) }, mask: widen(em) });
      }
    }
    if (hs.shell) {
      const hq0 = G("helper-hair");
      // The guide strips are separate ribbons: weld coincident vertices so the shell is one surface.
      const canon = new Uint32Array(N);
      for (let i = 0; i < N; i++) canon[i] = i;
      const seen = new Map<string, number>();
      const V = this.V;
      for (const v of uniqueSorted(hq0, N)) {
        const key = `${Math.round(V[v * 3]! * 1000)},${Math.round(V[v * 3 + 1]! * 1000)},${Math.round(V[v * 3 + 2]! * 1000)}`;
        const c = seen.get(key);
        if (c === undefined) seen.set(key, v);
        else canon[v] = c;
      }
      let hq: Uint32Array = Uint32Array.from(hq0, (v) => canon[v]!);
      const Ph = Float32Array.from(P);
      const headC = [0, eyeY + 0.03 * kk, 0.04 * kk];
      if (hs.cut !== undefined && hs.cut !== null) {
        const keep = new Uint8Array(N);
        for (const v of uniqueSorted(hq, N)) if (Y(v) > eyeY - hs.cut * kk) keep[v] = 1;
        hq = quadsAll(hq, keep);
      }
      const ids = uniqueSorted(hq, N);
      const puff = num(hs.puff, 1);
      for (const v of ids) for (let c = 0; c < 3; c++) Ph[v * 3 + c] = headC[c]! + (Ph[v * 3 + c]! - headC[c]!) * puff;
      if (hs.nobangs) {
        const front = new Uint8Array(N);
        for (const v of ids) if (Z(v) > 0.09 * kk && Y(v) < eyeY + 0.07 * kk) front[v] = 1;
        const out: number[] = [];
        for (let q = 0; q < hq.length; q += 4) if (!(front[hq[q]!] || front[hq[q + 1]!] || front[hq[q + 2]!] || front[hq[q + 3]!])) out.push(hq[q]!, hq[q + 1]!, hq[q + 2]!, hq[q + 3]!);
        hq = Uint32Array.from(out);
      }
      if (hq.length) {
        let ylow = Infinity, yhigh = -Infinity;
        for (const v of ids) {
          ylow = Math.min(ylow, Y(v));
          yhigh = Math.max(yhigh, Y(v));
        }
        const hm = new Float32Array(N);
        const rate = hs.cut === undefined || hs.cut === null ? 4.0 : 2.2;
        for (const v0 of hq0) {
          const v = canon[v0]!;
          hm[v] = Math.max(hm[v]!, clamp01(1 - ((Y(v) - ylow) / Math.max(yhigh - ylow, 1e-6)) * rate));
        }
        addPart("hair", "hair", hq, { pos: Ph, aux: Ph, params: { rag: num(hs.rag, 1) }, mask: widen(hm) });
      }
    }
    // Mouth centre: the 40 strongest lip vertices.
    const lipTop = Array.from({ length: body }, (_, i) => i).sort((a, b) => lip[b]! - lip[a]! || a - b).slice(0, 40);
    const mouth: [number, number, number] = [0, 0, 0];
    for (const v of lipTop) {
      mouth[0] += X(v) / 40;
      mouth[1] += Y(v) / 40;
      mouth[2] += Z(v) / 40;
    }
    if (hs.beard) {
      const bd = hs.beard;
      const sel = new Uint8Array(N);
      const lim = mouth[1] + (bd.mustache !== false ? 0.016 * kk : -0.004);
      for (let i = 0; i < body; i++) if (beardW[i]! > num(bd.thr, 0.42) && lip[i]! < 0.25 && Y(i) < lim) sel[i] = 1;
      const cq = quadsAll(bodyQ, sel);
      if (cq.length) {
        const Pc = Float32Array.from(P);
        const ids = uniqueSorted(cq, N);
        const bem = edgeMask(cq, N);
        for (const v of ids) {
          const lift = (num(bd.thick, 0.006) + num(bd.mess, 0.0015) * (0.5 + 0.5 * hnoise(X(v), Y(v), Z(v), 60))) * (1 - 0.85 * bem[v]!);
          for (let c = 0; c < 3; c++) Pc[v * 3 + c] = Pc[v * 3 + c]! + skinN[v * 3 + c]! * lift;
        }
        const L = num(bd.length, 0);
        if (L > 0) {
          let chinY = Infinity;
          for (const v of ids) chinY = Math.min(chinY, Y(v));
          for (const v of ids) {
            const w = clamp01(1 - (Y(v) - chinY) / (0.05 * kk)) * Math.exp(-((X(v) / (0.035 * kk)) ** 2)) * clamp01((Z(v) - 0.02 * kk) / (0.06 * kk));
            Pc[v * 3 + 1] = Pc[v * 3 + 1]! - L * w;
            Pc[v * 3 + 2] = Pc[v * 3 + 2]! + L * 0.15 * w;
            Pc[v * 3] = Pc[v * 3]! * (1 - 0.5 * w);
          }
        }
        addPart("beard", "hair", cq, { pos: Pc, aux: Pc, params: { rag: 0.7 }, mask: widen(bem) });
      }
    }

    let headTop = -Infinity;
    for (let i = 0; i < body; i++) headTop = Math.max(headTop, Y(i));
    const r5 = (a: number[]) => a.map((x) => Math.round(x * 1e5) / 1e5);
    const landmarks: Record<string, number | number[]> = {
      eyeL: r5(eyeL), eyeR: r5(eyeR), eyeRadius,
      crown: [0, headTop - 0.035 * kk, -0.045 * kk], nape: [0, eyeY - 0.055 * kk, -0.075 * kk], kk,
      neckY, hipY, kneeY, ankleY, headTop, mouth: r5(mouth),
    };
    return { name, height: spec.height, bones, morphNames: EXPR, parts, landmarks, look: spec.look ?? {} };
  }
}

function dist3(P: Float32Array, i: number, c: readonly number[]): number {
  return Math.hypot(P[i * 3]! - c[0]!, P[i * 3 + 1]! - c[1]!, P[i * 3 + 2]! - c[2]!);
}
