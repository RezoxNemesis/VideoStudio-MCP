/** The page's side of the open-prosody voice (see engine/openworker.ts): toned speech, syllables and singing. */
import { lookupVoiceRef } from "./engine/plan";
import { resolveVoice } from "./engine/voicebank";
import { loadCustoms } from "./voicelab";
import type { VocalEngine } from "../music/vocal";
import type { Tone } from "./engine/open";
import type { OpenReply, OpenRequest } from "./engine/openworker";
import type { SingOptions, SungNote } from "./engine/sing";

type Body = { type: "syllables"; lines: string[]; voice: string } | { type: "say"; text: string; voice: string; tone?: Tone | string; emphasis?: string[]; lang?: string; shift?: number; pace?: number } | { type: "sing"; notes: SungNote[]; voice: string; options?: SingOptions };

export class OpenVoice implements VocalEngine {
  private worker: Worker | null = null;
  private waiting = new Map<number, { resolve: (r: OpenReply) => void; reject: (e: Error) => void }>();
  private seq = 1;
  /** Called with 0…1 while the model loads or a song is sung. */
  onProgress: ((done: number, what: string) => void) | null = null;

  private start(): Worker {
    if (this.worker) return this.worker;
    const w = new Worker(new URL("./engine/openworker.ts", import.meta.url), { type: "module" });
    w.onmessage = (ev: MessageEvent<OpenReply>) => {
      const m = ev.data;
      if (m.id === 0) {
        if ("progress" in m) this.onProgress?.(m.progress, m.what);
        return;
      }
      const p = this.waiting.get(m.id);
      this.waiting.delete(m.id);
      if (!p) return;
      if (m.ok) p.resolve(m);
      else p.reject(new Error(m.error));
    };
    w.onerror = (e) => {
      for (const p of this.waiting.values()) p.reject(new Error(e.message || "The voice worker stopped."));
      this.waiting.clear();
      this.worker = null;
    };
    this.worker = w;
    return w;
  }
  private call(body: Body): Promise<OpenReply> {
    const id = this.seq++;
    const w = this.start();
    return new Promise((resolve, reject) => {
      this.waiting.set(id, { resolve, reject });
      w.postMessage({ id, ...body } as OpenRequest);
    });
  }

  async syllables(lines: string[], voice: string): Promise<string[][]> {
    const r = await this.call({ type: "syllables", lines, voice });
    return "syllables" in r && r.syllables ? r.syllables : [];
  }
  async sing(notes: SungNote[], voice: string, o?: SingOptions): Promise<Float32Array> {
    const r = await this.call({ type: "sing", notes, voice, options: o });
    return "pcm" in r && r.pcm ? r.pcm : new Float32Array(0);
  }
  /** One line said with a tone. Returns 24 kHz mono and the voice that actually read it. */
  async say(text: string, voice: string, tone?: Tone | string, emphasis?: string[], lang?: string): Promise<{ pcm: Float32Array; voice: string }> {
    // a character or Voice Lab voice is a blend with its own pitch and pace
    const customs = loadCustoms();
    const v = resolveVoice(lookupVoiceRef(voice, customs), customs);
    const r = await this.call({ type: "say", text, voice: v.mix, tone, emphasis, lang, shift: v.pitch || undefined, pace: v.speed !== 1 ? v.speed : undefined });
    return { pcm: "pcm" in r && r.pcm ? r.pcm : new Float32Array(0), voice: "voice" in r && r.voice ? r.voice : voice };
  }
}

export const openVoice = new OpenVoice();
