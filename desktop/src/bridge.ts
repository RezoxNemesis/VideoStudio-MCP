/** Typed access to the desktop bridge (desktop/electron/preload.cjs). */

export interface FsEntry {
  name: string;
  path: string;
  kind: "folder" | "video" | "audio" | "image" | "model" | "project" | "other";
  size: number;
  modified: number;
}

export interface DesktopInfo {
  version: string;
  platform: string;
  arch: string;
  host: string;
  user: string;
  cpus: number;
  cpuModel?: string;
  memoryGB: number;
  dirs: Record<"home" | "desktop" | "documents" | "downloads" | "music" | "pictures" | "videos", string>;
  out: Record<"Movies" | "Music" | "Pictures" | "Documents", string>;
  drives: { name: string; path: string }[];
  ffmpeg: string;
  gpu: Record<string, string>;
}

export interface Probe {
  path: string;
  name: string;
  size: number;
  duration: number;
  format: string;
  bitrate: number;
  video: { codec: string; width: number; height: number; fps: number; pixfmt: string; rotation: number; hdr: boolean } | null;
  audio: { codec: string; sampleRate: number; channels: number; layout?: string } | null;
  tags: Record<string, string | undefined>;
}

export interface AudioReport {
  file: string;
  path: string;
  duration: number;
  loudness: { integratedLUFS: number | null; rangeLU: number | null; truePeakDBFS: number | null; samplePeakDB: number };
  tempo?: { bpm: number; confidence: number };
  beats?: number[];
  hits?: number[];
  sections: { start: number; end: number; energy: string }[];
  silences: { start: number; end: number }[];
  vocals?: { segments: { start: number; end: number }[]; mono: boolean; step: number; curve: number[] };
  envelope: { step: number; startsAt: number; rmsDb: number[]; peakDb: number[] };
  waveform?: string;
  spectrogram?: string;
  [k: string]: unknown;
}

export interface Vsd {
  desktop: true;
  pathOf(file: File): string;
  info(): Promise<DesktopInfo>;
  list(dir: string, o?: { kinds?: string[]; hidden?: boolean; sort?: "name" | "modified"; limit?: number }): Promise<{ dir: string; parent: string | null; entries: FsEntry[]; total: number }>;
  find(root: string, q: string, o?: { kinds?: string[]; limit?: number; depth?: number }): Promise<{ root: string; query: string; results: FsEntry[] }>;
  stat(p: string): Promise<{ path: string; size: number; dir: boolean; modified: number; kind: string }>;
  fileUrl(p: string): Promise<string>;
  reveal(p: string): Promise<void>;
  openPath(p: string): Promise<void>;
  pick(o?: { title?: string; dir?: string; folder?: boolean; multiple?: boolean }): Promise<string[]>;
  pickSave(o?: { title?: string; dir?: string; name?: string }): Promise<string | null>;
  writeText(p: string, t: string): Promise<string>;
  readText(p: string): Promise<string>;
  probe(p: string): Promise<Probe>;
  /** Native media core (VideoStudio 2). */
  mediaDescribe(p: string): Promise<import("./native-media").MediaDescription>;
  mediaOpen(o: { path: string; ss: number; w: number; h: number; fps: number; quality: "preview" | "export" }): Promise<{ id: string; w: number; h: number; fps: number; src: string; hw: boolean }>;
  mediaRead(id: string, skip?: number): Promise<Uint8Array | null>;
  mediaClose(id: string): Promise<boolean>;
  mediaStatus(): Promise<{ gpuDecode: boolean | null; note: string; decodersRunning: number; streamsOpened: number; gpuFallbacks: number; editingCopiesMade: number; editingCopyFailures: number; lastError: string; ffmpeg: string }>;
  mediaSelfTest(): Promise<unknown>;
  mediaEnsureProxy(p: string): Promise<{ proxy: boolean; ready?: boolean; failed?: boolean }>;
  onMediaProxy?(fn: (m: { path: string; done: boolean; ok?: boolean; progress?: number }) => void): void;
  captureSave(name?: string): Promise<{ path: string; width: number; height: number }>;
  frames(p: string, times: number[], max?: number): Promise<{ t: number; image: string | null }[]>;
  audioInspect(p: string, o?: Record<string, unknown>): Promise<AudioReport>;
  audioInspectBytes(bytes: Uint8Array, name: string, o?: Record<string, unknown>): Promise<AudioReport>;
  capture(max?: number, quality?: number): Promise<string>;
  download(url: string, headers?: Record<string, string>): Promise<{ path: string; bytes: number; url: string }>;
  win: { minimize(): Promise<void>; maximize(): Promise<void>; close(): Promise<void>; fullscreen(): Promise<void>; isMaximized(): Promise<boolean>; onState(fn: (s: { maximized: boolean }) => void): void };
  openExternal(url: string): Promise<void>;
  enc: {
    caps(): Promise<EncCaps>;
    open(o: { width: number; height: number; fps: number; codec: string; quality?: number; name: string; audio?: Uint8Array | null; input?: "rgba" | "nv12" }): Promise<{ id: string; path: string; codec: string; input?: "rgba" | "nv12" }>;
    frame(id: string, data: Uint8Array): Promise<void>;
    finish(id: string): Promise<{ path: string; bytes: number; codec: string; frames: number }>;
    abort(id: string): Promise<void>;
  };
  process(kind: string, input: string, o?: Record<string, unknown>): Promise<{ path: string; note: string }>;
  needsConversion(p: string): Promise<boolean>;
  voice16k(p: string, from?: number, seconds?: number): Promise<Uint8Array>;
  crashLog(): Promise<string>;
  updateCheck(): Promise<{ current: string; latest?: string; available: boolean; notes?: string; size?: number; name?: string }>;
  updateInstall(): Promise<{ installed: boolean; version?: string; note: string }>;
  workWrite(name: string, bytes: Uint8Array): Promise<string>;
  workClean(): Promise<boolean>;
  onProgress(fn: (m: { tag: string; frac: number }) => void): void;
  /** The GPU process went away and restarted (driver hiccup); pictures in the window must be rebuilt. */
  onGpuLost?(fn: (m: { reason: string; code: number; restarts: number }) => void): void;
}

export interface EncCaps {
  ffmpeg: string;
  nvenc_h264: boolean;
  nvenc_hevc: boolean;
  nvenc_av1: boolean;
  x264: boolean;
  x265: boolean;
  prores: boolean;
  svtav1: boolean;
  vp9: boolean;
  filters: string[];
  reasons?: Record<string, string>;
  driver?: string | null;
}

/** The bridge, or a stand-in when the page runs in a plain browser (dev preview, tests). */
export const vsd: Vsd | null = (globalThis as { vsd?: Vsd }).vsd ?? null;

export function need(): Vsd {
  if (!vsd) throw new Error("This needs VideoStudio Desktop (not a browser tab).");
  return vsd;
}

/** Bring a file from the PC into memory as a Blob (streamed through vsfile://, never base64 over IPC). */
export async function pathBlob(p: string): Promise<{ blob: Blob; name: string }> {
  const url = await need().fileUrl(p);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Could not read ${p} (${res.status})`);
  const blob = await res.blob();
  const name = p.split("/").pop() || "file";
  return { blob: blob.type ? blob : new Blob([blob], { type: guessMime(name) }), name };
}

export function guessMime(name: string): string {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  const m: Record<string, string> = {
    mp4: "video/mp4", m4v: "video/mp4", mov: "video/quicktime", mkv: "video/x-matroska", webm: "video/webm", avi: "video/x-msvideo",
    mp3: "audio/mpeg", wav: "audio/wav", flac: "audio/flac", m4a: "audio/mp4", aac: "audio/aac", ogg: "audio/ogg", opus: "audio/ogg",
    png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif", bmp: "image/bmp", avif: "image/avif", svg: "image/svg+xml",
    glb: "model/gltf-binary", gltf: "model/gltf+json",
  };
  return m[ext] ?? "application/octet-stream";
}

export const fmtSize = (n: number): string => (n > 1e9 ? `${(n / 1e9).toFixed(2)} GB` : n > 1e6 ? `${(n / 1e6).toFixed(1)} MB` : n > 1e3 ? `${Math.round(n / 1e3)} KB` : `${n} B`);
