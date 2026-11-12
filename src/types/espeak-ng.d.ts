/** eSpeak NG compiled to WebAssembly (GPL-3.0-or-later): text → phonemes for 100+ languages. */
declare module "espeak-ng" {
  const factory: (options?: Record<string, unknown>) => Promise<{ FS: { readFile(path: string, o: { encoding: "utf8" }): string; writeFile(path: string, data: string): void } }>;
  export default factory;
}
declare module "espeak-ng/dist/espeak-ng.wasm?url" {
  const url: string;
  export default url;
}
