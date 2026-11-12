/**
 * eSpeak NG (GPL-3.0-or-later, compiled to WebAssembly: the `espeak-ng` package): text of any of its languages → IPA.
 * It is used only as a pronunciation dictionary; no sound comes from it. Results are remembered, so a film's lines
 * are looked up once.
 */
type Factory = (o: Record<string, unknown>) => Promise<{ FS: { readFile(p: string, o: { encoding: "utf8" }): string; writeFile(p: string, d: string): void } }>;

let wasm: { url?: string; module?: Promise<WebAssembly.Module> } = {};
/** Where the page or worker finds espeak-ng.wasm (bundlers move it; Node finds it by itself). */
export function setEspeakWasm(url: string): void {
  wasm = { url };
}
const memo = new Map<string, string[]>();

/** The sounds of `text` in eSpeak voice `lang` ("de", "ru", "fr-fr" …): one string per clause, stress marks kept. */
export async function espeakIpa(text: string, lang: string): Promise<string[]> {
  const key = `${lang}\u0001${text}`;
  const hit = memo.get(key);
  if (hit) return hit;
  const factory = (await import("espeak-ng")).default as unknown as Factory;
  const o: Record<string, unknown> = {
    arguments: ["--phonout", "out", "--sep=", "-q", "--ipa=3", "-b", "1", "-v", lang, "-f", "in.txt"],
    preRun: [(m: { FS: { writeFile(p: string, d: string): void } }) => m.FS.writeFile("in.txt", text)],
    print: () => undefined,
    printErr: () => undefined,
  };
  if (wasm.url) {
    // compile once, start a fresh copy for every call (each call is a whole run of the program)
    wasm.module ??= fetch(wasm.url).then((r) => {
      if (!r.ok) throw new Error(`The pronunciation engine is not installed (espeak-ng.wasm, ${r.status}).`);
      return r.arrayBuffer().then((b) => WebAssembly.compile(b));
    });
    wasm.module.catch(() => (wasm.module = undefined));
    const mod = await wasm.module;
    o.instantiateWasm = (imports: WebAssembly.Imports, done: (i: WebAssembly.Instance, m: WebAssembly.Module) => void) => {
      void WebAssembly.instantiate(mod, imports).then((inst) => done(inst, mod));
      return {};
    };
  }
  const e = await factory(o);
  const out = e.FS.readFile("out", { encoding: "utf8" })
    .split(/\n+/)
    .map((s) => s.trim())
    .filter(Boolean);
  if (memo.size > 4000) memo.clear();
  memo.set(key, out);
  return out;
}
