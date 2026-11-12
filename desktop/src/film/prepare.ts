/**
 * What a code film needs before it can be drawn, made here in the window with the studio's own engines and handed to
 * the film's folder: spoken lines (the open voice), solved body motion (the 3D Stage: motion capture + IK), and music /
 * sound beds (the composer and the sound-effect maker). Everything is keyed, so only what changed is made again.
 */
import { Studio } from "../../../src/app/studio";
import { runQuiet, type Command, type CommandResult } from "../../../src/agent/api";
import { readTone } from "../../../src/agent/voiceopen";
import { openVoice } from "../../../src/voice/open_client";
import { SAMPLE_RATE } from "../../../src/voice/engine/audio";
import { getBlob, deleteBlob } from "../../../src/core/storage";
import { dropRuntime } from "../../../src/stage/draw";
import { need } from "../bridge";
import type { BakeNeed, FilmApi, SoundNeed, VoiceNeed } from "./types";

export function film(): FilmApi {
  const f = need().film;
  if (!f) throw new Error("This version of VideoStudio Desktop has no Code Studio.");
  return f;
}

export interface PrepStep {
  phase: "voice" | "motion" | "sound";
  done: number;
  total: number;
  what: string;
}
export interface PrepResult {
  spoke: string[];
  solved: string[];
  sounds: string[];
  /** Things the film's author should look at (a step of the performance the Stage refused, feet sliding …). */
  problems: string[];
}

/** A studio of its own for making things: nothing it does touches the person's project or is saved. */
function scratch(): Studio {
  const s = new Studio();
  Object.assign(s, { save: async () => {}, ensureProxies: () => {} });
  return s;
}
const fromB64 = (b: string): Uint8Array => {
  const bin = atob(b), u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
};
function wavBytes(pcm: Float32Array, rate: number): Uint8Array {
  const n = pcm.length, out = new Uint8Array(44 + n * 2), dv = new DataView(out.buffer);
  const wr = (o: number, s: string) => { for (let i = 0; i < s.length; i++) out[o + i] = s.charCodeAt(i); };
  wr(0, "RIFF"); dv.setUint32(4, 36 + n * 2, true); wr(8, "WAVEfmt "); dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true);
  dv.setUint32(24, rate, true); dv.setUint32(28, rate * 2, true); dv.setUint16(32, 2, true); dv.setUint16(34, 16, true); wr(36, "data"); dv.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) dv.setInt16(44 + i * 2, Math.max(-32768, Math.min(32767, Math.round(pcm[i]! * 32767))), true);
  return out;
}
const failed = (rs: CommandResult[]) => rs.filter((r) => !r.ok).map((r) => `${r.op}: ${r.error}`);

async function speak(dir: string, v: VoiceNeed): Promise<void> {
  const { pcm, voice } = await openVoice.say(v.text ?? "", v.voice ?? "af_heart", readTone(v.tone), v.emphasis?.length ? v.emphasis : undefined);
  if (!pcm.length) throw new Error(`The voice returned no sound for line ${v.id} (${v.lang}).`);
  let peak = 0;
  for (let i = 0; i < pcm.length; i++) peak = Math.max(peak, Math.abs(pcm[i]!));
  const g = peak > 0 ? Math.min(3, 0.85 / peak) : 1;
  if (g !== 1) for (let i = 0; i < pcm.length; i++) pcm[i] = pcm[i]! * g;
  await film().putVoice(dir, v.lang, v.id, wavBytes(pcm, SAMPLE_RATE), { key: v.key, seconds: Math.round((pcm.length / SAMPLE_RATE) * 1000) / 1000, voice, who: v.who, text: v.text, show: v.show });
}

/** Solve one sequence's performance on a stage of its own and keep the joint tracks with the film. */
async function solve(dir: string, b: BakeNeed, problems: string[]): Promise<void> {
  const spec = b.spec!;
  const s = scratch();
  const made = await runQuiet(s, [{ op: "stage.new", name: spec.name, env: spec.env ?? "plain", time: "day", seconds: spec.seconds, toTimeline: false }]);
  if (!made[0]?.ok) throw new Error(`${b.seq}: the Stage could not start: ${made[0]?.error}`);
  const { sceneId, layerId } = made[0].result as { sceneId: string; layerId: string };
  try {
    const cmds: Command[] = [...spec.actors.map((a) => ({ op: "stage.actor", sceneId, ...a })), ...(spec.steps ?? []).map((st) => ({ sceneId, ...st }) as unknown as Command)];
    const res = cmds.length ? await runQuiet(s, cmds, true) : [];
    const bad = failed(res);
    if (bad.length) throw new Error(`${b.seq}: the performance has ${bad.length === 1 ? "a step" : "steps"} the Stage cannot do — ${bad.slice(0, 4).join(" | ")}`);
    const info: Record<string, unknown[]> = {};
    cmds.forEach((c, i) => { if (c.op === "stage.do" || c.op === "stage.fight" || c.op === "stage.say") (info[c.op] ??= []).push(res[i]?.result); });
    let issues: { level: string; t?: number; actor?: string; what: string }[] | null = null;
    if (spec.check !== false) {
      const ck = (await runQuiet(s, [{ op: "stage.check", sceneId, step: 1 / 30 }]))[0];
      if (ck?.ok) issues = ((ck.result as { issues?: { level: string; t?: number; actor?: string; what: string }[] }).issues ?? []).filter((i) => i.level !== "note" && !i.what.includes("shot") && !i.what.includes("frame"));
      for (const i of (issues ?? []).slice(0, 6)) problems.push(`${b.seq}${i.t !== undefined ? ` at ${i.t}s` : ""}${i.actor ? ` (${i.actor})` : ""}: ${i.what}`);
    }
    const bk = (await runQuiet(s, [{ op: "stage.bake", sceneId, fps: spec.fps ?? 60, from: 0, to: spec.seconds, ...(spec.joints ? { joints: spec.joints } : {}) }]))[0];
    if (!bk?.ok) throw new Error(`${b.seq}: the motion could not be solved: ${bk?.error}`);
    const r = bk.result as { fps: number; frames: number; joints: string[]; quats: string[]; stride: number; actors: { id: string; height: number; data: string; contact: string; props: { name: string; data: string }[] }[] };
    const parts: Uint8Array[] = [];
    let len = 0;
    const push = (u: Uint8Array): [number, number] => { const at = len; parts.push(u); len += u.length; return [at, u.length]; };
    const meta = {
      name: spec.name, fps: r.fps, frames: r.frames, joints: r.joints, quats: r.quats, stride: r.stride, info, issues,
      actors: r.actors.map((a) => {
        const data = push(fromB64(a.data)), contact = push(fromB64(a.contact));
        if (len % 4) push(new Uint8Array(4 - (len % 4)));
        return { id: a.id, height: a.height, data, contact, props: a.props.map((p) => ({ name: p.name, data: push(fromB64(p.data)) })) };
      }),
    };
    const bin = new Uint8Array(len);
    let o = 0;
    for (const u of parts) { bin.set(u, o); o += u.length; }
    await film().putBake(dir, spec.name, b.key, JSON.stringify(meta), bin);
  } finally {
    dropRuntime(layerId);
  }
}

/** Make one music / sound bed with the studio's composer or sound maker and keep it with the film. */
async function sound(dir: string, c: SoundNeed, s: Studio): Promise<void> {
  const before = new Set(s.project.assets.map((a) => a.id));
  const r = (await runQuiet(s, [{ toTimeline: false, ...c.cmd! } as Command]))[0];
  if (!r?.ok) throw new Error(`Sound "${c.name}": ${r?.error}`);
  let res = (r.result ?? {}) as Record<string, unknown>;
  if ((c.cmd!.op === "music.generate" || c.cmd!.op === "music.new") && typeof res.id === "string") {
    const rr = (await runQuiet(s, [{ op: "music.render", songId: res.id, toTimeline: false, name: c.name }]))[0];
    if (!rr?.ok) throw new Error(`Sound "${c.name}": ${rr?.error}`);
    res = { ...res, ...(rr.result as Record<string, unknown>) };
  }
  const fresh = s.project.assets.filter((a) => !before.has(a.id));
  const asset = s.project.assets.find((a) => a.id === res.assetId) ?? fresh[fresh.length - 1];
  if (!asset?.ref) throw new Error(`Sound "${c.name}": ${c.cmd!.op} made no sound file.`);
  const blob = await getBlob(asset.ref);
  const ext = /wav/.test(blob.type) ? "wav" : /webm/.test(blob.type) ? "webm" : /mp4|aac/.test(blob.type) ? "m4a" : /ogg/.test(blob.type) ? "ogg" : /mpeg/.test(blob.type) ? "mp3" : (asset.name.split(".").pop() ?? "wav");
  const info: Record<string, unknown> = {};
  for (const k of ["bpm", "key", "scale", "bars", "seconds", "sections"]) if (k in res) info[k] = res[k];
  await film().putSound(dir, c.dir, c.name, new Uint8Array(await blob.arrayBuffer()), ext, c.key ?? "", info);
  for (const a of fresh) if (a.ref) await deleteBlob(a.ref).catch(() => {});
}

/**
 * Make everything the film still needs. Voices come first (how long a line takes decides the timing of the
 * performance), then motion, then sound. `only` limits the work: { voices?, motion?, sounds? }.
 */
export async function prepare(dir: string, on: (m: PrepStep) => void = () => {}, only: { voices?: boolean; motion?: boolean; sounds?: boolean } = {}): Promise<PrepResult> {
  const f = film(), out: PrepResult = { spoke: [], solved: [], sounds: [], problems: [] };
  let n = await f.needs(dir);
  out.problems.push(...n.problems);
  const lines = only.voices === false ? [] : n.voices.filter((v) => !v.have);
  if (lines.length) {
    for (const [i, v] of lines.entries()) {
      on({ phase: "voice", done: i, total: lines.length, what: `${v.id} · ${v.lang}` });
      await speak(dir, v);
      out.spoke.push(`${v.id}.${v.lang}`);
    }
    await f.voicesFit(dir);
    n = await f.needs(dir);
  }
  const bakes = only.motion === false ? [] : n.bakes.filter((b) => !b.have);
  for (const [i, b] of bakes.entries()) {
    on({ phase: "motion", done: i, total: bakes.length, what: b.seq });
    await solve(dir, b, out.problems);
    out.solved.push(b.seq);
  }
  const snd = only.sounds === false ? [] : n.sounds.filter((x) => !x.have);
  if (snd.length) {
    const s = scratch();
    for (const [i, c] of snd.entries()) {
      on({ phase: "sound", done: i, total: snd.length, what: c.name });
      await sound(dir, c, s);
      out.sounds.push(c.name);
    }
  }
  return out;
}
