/**
 * The speaker a studio line is acted from. Chatterbox takes a voice from a short reference clip; that clip is
 * spoken by the voice itself in the wanted mood. A character voice is a blend with a pitch of its own (a little
 * boy is a blend raised four and a half semitones) and a line or a cast member may add to it, so the clip is
 * spoken at that pitch and each pitch is a speaker of its own: the model then acts in the character's register
 * instead of the blend's plain one.
 */
import { moodTone, type Tone } from "./open";
import { seedFor } from "./studio";

export interface StudioSpeaker {
  /** The name the speaker is kept under. */
  key: string;
  /** How the reference clip is spoken (by the character voice itself, which brings its own pitch and pace). */
  tone: Tone;
}

/**
 * tone: the line's tone as worked out (readTone): its pitch is the pitch the line is wanted at, the mood's own
 * when the line and its cast member add nothing.
 */
export function studioSpeaker(voice: { mix: string; pitch?: number }, mood: string | undefined, tone: Tone | undefined): StudioSpeaker {
  const ref: Tone = mood ? moodTone(mood) : {};
  const moods = ref.pitch ?? 0;
  // what the line stands above or below its mood's own pitch, with the character voice's own pitch:
  // to the half semitone (less is not heard, and every pitch is a reference clip of its own)
  const total = Math.round(((voice.pitch ?? 0) + (tone?.pitch ?? moods) - moods) * 2) / 2;
  if (tone?.pitch !== undefined) ref.pitch = tone.pitch;
  return { key: `v1-${seedFor(voice.mix, mood ?? "neutral").toString(36)}${total ? `-p${total}` : ""}`, tone: ref };
}
