/**
 * The ground a film's performance is solved on. A film's world is flat (the ground is y = 0 everywhere; its places are
 * drawn by the film itself), so unless a sequence names a Stage environment — `motion: () => ({ env: "village", … })` —
 * its performance is solved on the Stage's flat studio floor. On the Stage's open "plain" the land rises beyond 26 m
 * from the middle: a figure who walked out there was lifted off the film's ground.
 */
export function filmStageEnv(spec: { env?: string }): string {
  return spec.env ?? "studio";
}
