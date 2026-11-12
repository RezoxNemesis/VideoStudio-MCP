/** Code Studio: what the main process offers for films written as code (electron/film.cjs → film/runner/worker.mjs). */

export interface FilmShot {
  id: string;
  seq: string;
  /** Where the shot starts in the film, seconds. */
  start: number;
  dur: number;
  lip: boolean;
  off: boolean;
  note: string;
  variants?: { lang: string | null; fp: string | null; state: "ready" | "changed" | "new" | "error"; file: string | null; ms: number | null; error?: string }[];
  actors?: string[];
  t0?: number;
}
export interface FilmConfig {
  name: string;
  width: number;
  height: number;
  fps: number;
  langs: string[];
  crf: number;
  preset: string;
  off: string[];
  /** The kind of animation the film is drawn as (film/engine/look.js); null or absent = anime. */
  look?: string | { style: string; [k: string]: unknown } | null;
}
export interface FilmOpen {
  dir: string;
  config: FilmConfig;
  total: number;
  engine: string;
  seqs: { id: string; file: string | null; seconds: number; lines: string[]; marks: Record<string, number>; motion: boolean }[];
  shots: FilmShot[];
  files: { file: string; bytes: number; hash: string }[];
}
export interface FilmStatus {
  total: number;
  fps: number;
  width: number;
  height: number;
  langs: string[];
  shots: FilmShot[];
  needs: { motion: string[]; voices: string[]; sounds: string[]; problems: string[] };
}
export interface VoiceNeed {
  id: string;
  lang: string;
  have: boolean;
  who?: string;
  text?: string;
  show?: string;
  voice?: string;
  tone?: Record<string, unknown>;
  emphasis?: string[];
  key?: string;
}
export interface BakeNeed {
  seq: string;
  name: string;
  key: string;
  have: boolean;
  spec?: { name: string; seconds: number; fps?: number; env?: string; check?: boolean; joints?: string[]; bodies?: boolean | string[]; rigEvery?: number; actors: Record<string, unknown>[]; steps?: Record<string, unknown>[] };
}
export interface SoundNeed {
  name: string;
  dir: "music" | "sfx" | "amb";
  have: boolean;
  key?: string;
  cmd?: Record<string, unknown> & { op: string };
  seconds?: number;
}
export interface FilmNeeds {
  bakes: BakeNeed[];
  voices: VoiceNeed[];
  sounds: SoundNeed[];
  problems: string[];
}
export interface FilmResult {
  file: string;
  bytes: number;
  seconds: number;
  shots: number;
  sound: boolean;
  subtitles: string;
  ms: number;
  kept?: boolean;
}
export interface BuildResult {
  rendered: string[];
  reused: number;
  renderMs: number;
  films: FilmResult[];
  ms: number;
}
export interface BuildOptions {
  dir: string;
  langs?: string[];
  /** Only these shots (no joining). */
  only?: string[];
  force?: boolean;
  assemble?: boolean;
  subs?: "soft" | "burn" | "none";
  sound?: boolean;
  /** Below 1: a quick draft at a smaller size (0.5 = a quarter of the pixels). */
  scale?: number;
  out?: string;
}
export interface FilmProgress {
  dir: string;
  phase: "check" | "render" | "frames" | "shot" | "sound" | "join" | "done";
  shot?: string;
  frame?: number;
  frames?: number;
  done?: number;
  total?: number;
  shotsDone?: number;
  shots?: number;
  ids?: string[];
  lang?: string;
}
export interface LibraryPerson {
  id: string;
  group: string;
  preset: string;
  name?: string;
  about?: string;
}
export interface FilmApi {
  info(): Promise<{ root: string; engine: boolean; templates: { id: string; name: string; about: string }[]; workers: number; reference: string | null }>;
  reference(): Promise<string>;
  list(): Promise<{ dir: string; name: string; width: number; height: number; fps: number; langs: string[]; changed: number }[]>;
  create(name: string, template?: string, config?: Partial<FilmConfig>): Promise<string>;
  open(dir: string): Promise<FilmOpen>;
  status(dir: string, scale?: number): Promise<FilmStatus>;
  needs(dir: string): Promise<FilmNeeds>;
  timeline(dir: string): Promise<{ total: number; shots: { id: string; start: number; dur: number }[]; cues: Record<string, unknown>[]; sounds: Record<string, unknown>[] }>;
  read(dir: string, file: string): Promise<string>;
  write(dir: string, file: string, text: string): Promise<{ file: string; bytes: number }>;
  remove(dir: string, file: string): Promise<{ file: string; keptAt: string }>;
  config(dir: string, patch?: Partial<FilmConfig>): Promise<FilmConfig>;
  frame(dir: string, id: string, t?: number, lang?: string, scale?: number, quality?: number): Promise<{ jpeg: string; width: number; height: number; t: number; shot: string }>;
  sheet(dir: string, id: string, p?: { times?: number[]; count?: number; cols?: number; cell?: number; lang?: string }): Promise<{ jpeg: string; file: string; times: number[] }>;
  build(p: BuildOptions): Promise<BuildResult>;
  cancel(dir: string): Promise<boolean>;
  farm(): Promise<{ workers: number; busy: number; waiting: number; started: boolean }>;
  scan(file: string, threshold?: number): Promise<{ frames: number; events: { t: number; frames: number; area: number; box: number[] }[] } & Record<string, unknown>>;
  putBake(dir: string, name: string, key: string, meta: string, bin: Uint8Array): Promise<boolean>;
  putVoice(dir: string, lang: string, id: string, wav: Uint8Array, meta: Record<string, unknown>): Promise<boolean>;
  voicesFit(dir: string): Promise<Record<string, number>>;
  putSound(dir: string, sub: string, name: string, data: Uint8Array, ext: string, key: string, info?: unknown): Promise<{ seconds: number }>;
  addSound(dir: string, sub: string, name: string, from: string): Promise<boolean>;
  people(group?: string): Promise<{ groups: { id: string; name: string; count: number }[] | Record<string, unknown>; people: LibraryPerson[]; cast: string[] }>;
  sets(): Promise<{ sets: string[] }>;
  personThumb(id: string, size?: number): Promise<{ jpeg: string }>;
  onProgress(fn: (m: FilmProgress) => void): () => void;
  // Sensei (the judge): see desktop/film/sensei and src/sensei
  sensei(dir: string, opts?: Record<string, unknown>): Promise<{ digest: import("../sensei/types").SenseiDigest; files: { json: string; md: string } }>;
  senseiCancel(dir: string): Promise<boolean>;
  senseiReport(dir: string): Promise<import("../sensei/types").SenseiReport | null>;
  senseiMarkdown(dir: string): Promise<string | null>;
  senseiIssue(dir: string, issue: import("../sensei/types").SenseiIssue, scale?: number): Promise<{ jpeg: string; width: number; height: number }>;
  senseiStyle(files: string[]): Promise<Record<string, number>>;
  senseiStyles(): Promise<Record<string, { name: string; from: string; fp: Record<string, number> }>>;
  senseiImage(file: string): Promise<string>;
  onSensei(fn: (m: { dir: string; phase: string; done?: number; of?: number; shot?: string; ms?: number }) => void): () => void;
}
