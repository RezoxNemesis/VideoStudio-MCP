/** Sensei — VideoStudio's own judge and helper. Shapes shared by the page, the commands and the panel. */
export type Sev = "must" | "should" | "nice";
export interface SenseiIssue {
  id: string;
  cat: string;
  kind: string;
  sev: Sev;
  shot: string | null;
  seq: string | null;
  t0: number | null;
  t1: number | null;
  at: number | null;
  actor: string | null;
  part: string | null;
  where: { x: number; y: number } | null;
  pt: number[] | null;
  value: number | null;
  limit: number | null;
  unit: string | null;
  msg: string;
  fix: string | null;
  data: Record<string, unknown> | null;
  img?: string;
}
export interface SenseiReport {
  film: string;
  dir: string;
  at: string;
  scope: string;
  shots: number;
  seconds: number;
  lang: string;
  bench: { id: string; name: string };
  overall: number;
  grade: string;
  pass: boolean;
  counts: { must: number; should: number; nice: number };
  categories: Record<string, { name: string; score: number; issues: number }>;
  style: { ref: string; score: number; gaps: { key: string; me: number; ref: number; sev: Sev; say: string; fix: string }[] } | null;
  sound: { measured: { I: number | null; LRA: number | null; peak: number | null } | null; note?: string } | null;
  progress: { before: { overall: number; grade: string; must: number; should: number }; fixed: number; fixedList: { id: string; kind: string; shot: string; msg: string }[]; new: number | null } | null;
  binding: string;
  issues: SenseiIssue[];
}
export interface SenseiDigest {
  film: string;
  overall: number;
  grade: string;
  pass: boolean;
  bench: string;
  counts: { must: number; should: number; nice: number };
  categories: Record<string, number>;
  style?: { ref: string; score: number };
  sound?: { I: number | null; LRA: number | null; peak: number | null };
  progress?: { before: number; fixed: number; new: number | null };
  top: { id: string; sev: Sev; shot: string | null; t?: string; kind: string; actor?: string; msg: string; fix?: string; img?: string }[];
  binding: string;
}
export const BENCHES: [string, string][] = [
  ["youtube", "YouTube / Instagram"],
  ["anime", "Anime TV episode"],
  ["feature", "Feature film (strict)"],
  ["draft", "Draft (only what breaks)"],
];

export type Provider = "builtin" | "ollama" | "openai" | "anthropic" | "gemini";
export interface BrainSettings {
  provider: Provider;
  model: string;
  visionModel: string;
  url: string;
  web: boolean;
  edits: boolean;
  temperature: number;
  keys: Record<string, string>;
  encrypted: boolean;
  modelDefault: Record<string, string>;
  visionDefault: Record<string, string>;
}
export interface LlmMessage { role: "user" | "assistant" | "tool"; content: string; images?: string[]; name?: string; callId?: string; toolCalls?: ToolCall[] }
export interface ToolCall { id?: string; name: string; args: Record<string, unknown> }
export interface ToolDef { name: string; description: string; parameters: Record<string, unknown> }
export interface LlmReply { text: string; toolCalls: ToolCall[]; model: string; ms: number }
export interface OllamaStatus { running: boolean; version: string | null; models: { name: string; size: number; family?: string; params?: string }[]; managed: boolean; installed: boolean; system: boolean; dir: string }
export interface SenseiBrainApi {
  settings(): Promise<BrainSettings>;
  setSettings(patch: Partial<BrainSettings> & { key?: string; keyFor?: string }): Promise<BrainSettings>;
  llm(req: { system?: string; messages: LlmMessage[]; tools?: ToolDef[]; vision?: boolean; provider?: Provider }): Promise<LlmReply>;
  test(): Promise<{ ok: boolean; text: string; model: string; ms: number }>;
  search(q: string, n?: number): Promise<{ title?: string; url?: string; snippet?: string; source?: string; error?: string }[]>;
  fetch(url: string, max?: number): Promise<{ url: string; title: string; text: string; cut?: boolean }>;
  ollama(): Promise<OllamaStatus>;
  ollamaStart(): Promise<OllamaStatus>;
  ollamaStop(): Promise<OllamaStatus>;
  ollamaInstall(): Promise<{ installed: boolean; version: string }>;
  ollamaPull(model: string): Promise<{ model: string; status: string }>;
  onOllama(fn: (m: { phase: string; done?: number; total?: number; model?: string; status?: string; name?: string; version?: string }) => void): () => void;
}
