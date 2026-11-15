import { describe, expect, it } from "vitest";
import { filmStageEnv } from "../desktop/src/film/stageenv";

// A film's world is flat: the ground is y = 0 everywhere, and its places are drawn by the film itself. The Stage's open
// land rises beyond 26 m from the middle — a figure solved out there stood half a metre to two metres above the film's
// ground. So a film's performance is solved on a flat floor unless the sequence names a Stage environment.
describe("the ground a film's performance is solved on", () => {
  it("is the Stage's flat floor when the sequence names none", () => {
    expect(filmStageEnv({})).toBe("studio");
    expect(filmStageEnv({ env: undefined })).toBe("studio");
  });
  it("is the environment a sequence asks for", () => {
    expect(filmStageEnv({ env: "village" })).toBe("village");
    expect(filmStageEnv({ env: "plain" })).toBe("plain");
  });
});
