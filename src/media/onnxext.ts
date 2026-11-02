/**
 * Big ONNX models on a phone: rewrite a single-file model so its large weights become "external data" that point
 * back into the ORIGINAL file bytes. The rewritten graph is tiny (a few MB); ONNX Runtime Web then streams the
 * weights from the original buffer straight to the GPU instead of copying the whole model into WebAssembly memory
 * first. Nothing is duplicated, nothing is re-downloaded.
 */

function readVarint(b: Uint8Array, p: number): [number, number] {
  let v = 0,
    mul = 1;
  for (;;) {
    const x = b[p++]!;
    v += (x & 0x7f) * mul;
    if (x < 0x80) return [v, p];
    mul *= 128;
  }
}
function varint(v: number): number[] {
  const out: number[] = [];
  while (v >= 0x80) {
    out.push((v % 128) | 0x80);
    v = Math.floor(v / 128);
  }
  out.push(v);
  return out;
}
const enc = new TextEncoder();

class Out {
  parts: Uint8Array[] = [];
  size = 0;
  push(a: Uint8Array | number[]) {
    const u = a instanceof Uint8Array ? a : Uint8Array.from(a);
    this.parts.push(u);
    this.size += u.length;
  }
  bytes(): Uint8Array {
    const o = new Uint8Array(this.size);
    let p = 0;
    for (const x of this.parts) {
      o.set(x, p);
      p += x.length;
    }
    return o;
  }
}

/** Walk the fields of a message in b[start, end): calls fn(field, wireType, valueStart, valueEnd, tagStart). */
function fields(b: Uint8Array, start: number, end: number, fn: (f: number, wt: number, vs: number, ve: number, ts: number) => void) {
  let p = start;
  while (p < end) {
    const ts = p;
    let tag: number;
    [tag, p] = readVarint(b, p);
    const f = Math.floor(tag / 8),
      wt = tag & 7;
    let vs = p,
      ve: number;
    if (wt === 0) {
      [, ve] = readVarint(b, p);
    } else if (wt === 1) ve = p + 8;
    else if (wt === 5) ve = p + 4;
    else if (wt === 2) {
      let len: number;
      [len, vs] = readVarint(b, p);
      ve = vs + len;
    } else throw new Error(`Unsupported protobuf wire type ${wt}`);
    fn(f, wt, vs, ve, ts);
    p = ve;
  }
}

function lenField(field: number, body: Uint8Array): Uint8Array {
  const head = [...varint(field * 8 + 2), ...varint(body.length)];
  const o = new Uint8Array(head.length + body.length);
  o.set(head, 0);
  o.set(body, head.length);
  return o;
}
function entry(key: string, value: string): Uint8Array {
  const o = new Out();
  o.push(lenField(1, enc.encode(key)));
  o.push(lenField(2, enc.encode(value)));
  return lenField(13, o.bytes());
}

export interface ExternalizeResult {
  graph: Uint8Array;
  tensors: number;
  externalBytes: number;
}

/**
 * Rewrite `model` (a whole .onnx file) so initializers with raw_data ≥ minBytes are external, located in
 * `location` at their offsets inside `model` itself. Pass the same bytes as the external data when loading.
 */
export function externalize(model: Uint8Array, location: string, minBytes = 4096): ExternalizeResult {
  let tensors = 0,
    externalBytes = 0;
  const tensor = (s: number, e: number): Uint8Array => {
    const o = new Out();
    let moved = false;
    fields(model, s, e, (f, _wt, vs, ve, ts) => {
      if (f === 9 && ve - vs >= minBytes) {
        moved = true;
        tensors++;
        externalBytes += ve - vs;
        o.push(entry("location", location));
        o.push(entry("offset", String(vs)));
        o.push(entry("length", String(ve - vs)));
        return;
      }
      o.push(model.subarray(ts, ve));
    });
    if (moved) o.push([...varint(14 * 8 + 0), 1]); // data_location = EXTERNAL
    return o.bytes();
  };
  const graph = (s: number, e: number): Uint8Array => {
    const o = new Out();
    fields(model, s, e, (f, wt, vs, ve, ts) => {
      if (f === 5 && wt === 2) o.push(lenField(5, tensor(vs, ve)));
      else o.push(model.subarray(ts, ve));
    });
    return o.bytes();
  };
  const top = new Out();
  fields(model, 0, model.length, (f, wt, vs, ve, ts) => {
    if (f === 7 && wt === 2) top.push(lenField(7, graph(vs, ve)));
    else top.push(model.subarray(ts, ve));
  });
  return { graph: top.bytes(), tensors, externalBytes };
}
