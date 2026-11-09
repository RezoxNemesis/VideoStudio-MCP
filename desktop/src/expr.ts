/**
 * A small, safe maths language for procedural animation (no eval, no access to anything but numbers).
 * Grammar: numbers, variables, + - * / % ^, unary -, comparison (< > <= >= == !=), ternary a ? b : c,
 * function calls. Used to generate keyframes: e.g. "sin(t*2*pi)*40" or "lerp(1, 1.3, smooth(t/dur))".
 */

type Node =
  | { k: "n"; v: number }
  | { k: "v"; name: string }
  | { k: "u"; op: string; a: Node }
  | { k: "b"; op: string; a: Node; b: Node }
  | { k: "t"; c: Node; a: Node; b: Node }
  | { k: "f"; name: string; args: Node[] };

function hash(x: number): number {
  const s = Math.sin(x * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
}
function noise1(x: number): number {
  const i = Math.floor(x);
  const f = x - i;
  const u = f * f * (3 - 2 * f);
  return (hash(i) * (1 - u) + hash(i + 1) * u) * 2 - 1;
}

const FN: Record<string, (...a: number[]) => number> = {
  sin: Math.sin, cos: Math.cos, tan: Math.tan, asin: Math.asin, acos: Math.acos, atan: Math.atan, atan2: Math.atan2,
  abs: Math.abs, min: Math.min, max: Math.max, pow: Math.pow, sqrt: Math.sqrt, exp: Math.exp, log: Math.log,
  floor: Math.floor, ceil: Math.ceil, round: Math.round, sign: Math.sign,
  clamp: (x, a = 0, b = 1) => Math.max(a, Math.min(b, x)),
  lerp: (a, b, t) => a + (b - a) * t,
  mix: (a, b, t) => a + (b - a) * t,
  step: (e, x) => (x < e ? 0 : 1),
  smooth: (t) => {
    const x = Math.max(0, Math.min(1, t));
    return x * x * (3 - 2 * x);
  },
  smoother: (t) => {
    const x = Math.max(0, Math.min(1, t));
    return x * x * x * (x * (x * 6 - 15) + 10);
  },
  easeIn: (t) => Math.pow(Math.max(0, Math.min(1, t)), 3),
  easeOut: (t) => 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3),
  easeInOut: (t) => {
    const x = Math.max(0, Math.min(1, t));
    return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
  },
  back: (t) => {
    const x = Math.max(0, Math.min(1, t));
    const c = 1.70158;
    return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2);
  },
  elastic: (t) => {
    const x = Math.max(0, Math.min(1, t));
    return x === 0 || x === 1 ? x : Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * ((2 * Math.PI) / 3)) + 1;
  },
  bounce: (t) => {
    let x = Math.max(0, Math.min(1, t));
    const n = 7.5625, d = 2.75;
    if (x < 1 / d) return n * x * x;
    if (x < 2 / d) return n * (x -= 1.5 / d) * x + 0.75;
    if (x < 2.5 / d) return n * (x -= 2.25 / d) * x + 0.9375;
    return n * (x -= 2.625 / d) * x + 0.984375;
  },
  /** Smooth 1D noise in -1..1 (handheld shake, flicker, drift). */
  noise: (x, seed = 0) => noise1(x + seed * 97.13),
  /** Fractal noise (layered) in about -1..1. */
  fbm: (x, seed = 0) => noise1(x + seed * 97.13) * 0.6 + noise1(x * 2.1 + seed * 13.7) * 0.3 + noise1(x * 4.3 + seed * 5.1) * 0.1,
  rand: (x, seed = 0) => hash(x + seed * 17.17),
  /** Damped spring from 0 to 1 (overshoot settle): spring(t, frequency=2, damping=4). */
  spring: (t, f = 2, d = 4) => (t <= 0 ? 0 : 1 - Math.exp(-d * t) * Math.cos(2 * Math.PI * f * t)),
  /** Triangle and square waves with period 1. */
  tri: (x) => 1 - 4 * Math.abs(Math.round(x - 0.25) - (x - 0.25)),
  square: (x) => (x - Math.floor(x) < 0.5 ? 1 : -1),
  deg: (r) => (r * 180) / Math.PI,
  rad: (d) => (d * Math.PI) / 180,
};

const CONST: Record<string, number> = { pi: Math.PI, tau: Math.PI * 2, e: Math.E };

function tokenize(src: string): string[] {
  const re = /\s*(\d+\.?\d*(?:e[+-]?\d+)?|\.\d+|[A-Za-z_][A-Za-z0-9_]*|<=|>=|==|!=|&&|\|\||[-+*/%^(),?:<>!])/gy;
  const out: string[] = [];
  let m: RegExpExecArray | null;
  let i = 0;
  while (i < src.length) {
    re.lastIndex = i;
    m = re.exec(src);
    if (!m) {
      if (/\s/.test(src.slice(i))) break;
      throw new Error(`Unexpected "${src.slice(i, i + 8)}" in expression.`);
    }
    out.push(m[1]!);
    i = re.lastIndex;
  }
  return out;
}

export function compile(src: string): (vars: Record<string, number>) => number {
  if (src.length > 2000) throw new Error("Expression too long.");
  const tk = tokenize(src);
  let p = 0;
  const peek = () => tk[p];
  const eat = (t?: string) => {
    const x = tk[p++];
    if (t && x !== t) throw new Error(`Expected "${t}" but found "${x ?? "end"}".`);
    return x;
  };
  const prim = (): Node => {
    const t = eat();
    if (t === undefined) throw new Error("Expression ended early.");
    if (t === "(") {
      const n = ternary();
      eat(")");
      return n;
    }
    if (t === "-" || t === "!") return { k: "u", op: t, a: unary() };
    if (/^[\d.]/.test(t)) return { k: "n", v: Number(t) };
    if (/^[A-Za-z_]/.test(t)) {
      if (peek() === "(") {
        eat("(");
        const args: Node[] = [];
        if (peek() !== ")") {
          args.push(ternary());
          while (peek() === ",") {
            eat(",");
            args.push(ternary());
          }
        }
        eat(")");
        if (!FN[t]) throw new Error(`Unknown function ${t}(). Functions: ${Object.keys(FN).join(", ")}.`);
        return { k: "f", name: t, args };
      }
      return { k: "v", name: t };
    }
    throw new Error(`Unexpected "${t}".`);
  };
  const unary = (): Node => prim();
  const power = (): Node => {
    const a = unary();
    if (peek() === "^") {
      eat();
      return { k: "b", op: "^", a, b: power() };
    }
    return a;
  };
  const level = (next: () => Node, ops: string[]) => (): Node => {
    let a = next();
    while (ops.includes(peek() ?? "")) {
      const op = eat()!;
      a = { k: "b", op, a, b: next() };
    }
    return a;
  };
  const mul = level(power, ["*", "/", "%"]);
  const add = level(mul, ["+", "-"]);
  const cmp = level(add, ["<", ">", "<=", ">=", "==", "!="]);
  const and = level(cmp, ["&&"]);
  const or = level(and, ["||"]);
  const ternary = (): Node => {
    const c = or();
    if (peek() === "?") {
      eat("?");
      const a = ternary();
      eat(":");
      return { k: "t", c, a, b: ternary() };
    }
    return c;
  };
  const root = ternary();
  if (p < tk.length) throw new Error(`Unexpected "${tk[p]}" in expression.`);
  const ev = (n: Node, v: Record<string, number>): number => {
    switch (n.k) {
      case "n":
        return n.v;
      case "v": {
        if (n.name in v) return v[n.name]!;
        if (n.name in CONST) return CONST[n.name]!;
        throw new Error(`Unknown name "${n.name}".`);
      }
      case "u":
        return n.op === "-" ? -ev(n.a, v) : ev(n.a, v) ? 0 : 1;
      case "t":
        return ev(n.c, v) ? ev(n.a, v) : ev(n.b, v);
      case "f":
        return FN[n.name]!(...n.args.map((x) => ev(x, v)));
      case "b": {
        const a = ev(n.a, v);
        const b = ev(n.b, v);
        switch (n.op) {
          case "+": return a + b;
          case "-": return a - b;
          case "*": return a * b;
          case "/": return a / b;
          case "%": return a % b;
          case "^": return Math.pow(a, b);
          case "<": return a < b ? 1 : 0;
          case ">": return a > b ? 1 : 0;
          case "<=": return a <= b ? 1 : 0;
          case ">=": return a >= b ? 1 : 0;
          case "==": return a === b ? 1 : 0;
          case "!=": return a !== b ? 1 : 0;
          case "&&": return a && b ? 1 : 0;
          case "||": return a || b ? 1 : 0;
        }
      }
    }
    return NaN;
  };
  return (vars) => {
    const r = ev(root, vars);
    if (!Number.isFinite(r)) throw new Error(`Expression gave ${r} (at t=${vars.t}).`);
    return r;
  };
}

export const EXPR_FUNCTIONS = Object.keys(FN);
