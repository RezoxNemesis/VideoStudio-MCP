import { describe, expect, it } from "vitest";
import { linkConfigFrom } from "../desktop/src/linkenv";

describe("where the Claude link polls", () => {
  const stored = { worker: "https://videostudio-desktop.example.workers.dev", key: "abc", enabled: false };

  it("is what the app has stored, when the environment says nothing", () => {
    expect(linkConfigFrom(stored, undefined)).toEqual({ config: stored, fixed: false });
    expect(linkConfigFrom(stored, {})).toEqual({ config: stored, fixed: false });
    expect(linkConfigFrom(stored, { worker: "  " })).toEqual({ config: stored, fixed: false });
  });

  it("is the relay named in the environment (a machine without a screen, a relay of one's own): switched on, never stored", () => {
    expect(linkConfigFrom(stored, { worker: "http://127.0.0.1:8787/", key: "ws" })).toEqual({ config: { worker: "http://127.0.0.1:8787", key: "ws", enabled: true }, fixed: true });
  });

  it("keeps the app's own key when the environment names only the relay, and makes one up when there is none", () => {
    expect(linkConfigFrom(stored, { worker: "http://127.0.0.1:8787" }).config.key).toBe("abc");
    expect(linkConfigFrom({ ...stored, key: "" }, { worker: "http://127.0.0.1:8787" }).config.key).toBe("local");
  });
});
