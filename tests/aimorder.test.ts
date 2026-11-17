// The Stage aims actors (reaches, strikes, looks at other bodies) in dependency order: a taker reaches for where the
// giver's hand really is, after the giver's own arms are posed (17 Nov 2026: a father reached under his daughter's
// drawing, to where her hand hung before she held it up).
import { describe, expect, it } from "vitest";
import { aimOrder } from "../src/stage/runtime";

const reach = (actor: string) => ({ id: "r", t0: 0, t1: 1, kind: "reach" as const, side: "R" as const, target: { actor, bone: "wrist.L" } });
describe("aimOrder", () => {
  it("puts the taker after the one whose hand he reaches for", () => {
    const order = aimOrder([{ id: "papa", overlays: [reach("mini")] }, { id: "mini", overlays: [reach("mini")] }]);
    expect(order.map((a) => a.id)).toEqual(["mini", "papa"]);
  });
  it("does not let looks make a cycle: she looks at him while he reaches for her hand", () => {
    const look = (actor: string) => ({ id: "l", t0: 0, t1: 1, kind: "look" as const, target: { actor } });
    const order = aimOrder([{ id: "papa", overlays: [reach("mini"), look("mini")] }, { id: "mini", overlays: [reach("mini"), look("papa")] }]);
    expect(order.map((a) => a.id)).toEqual(["mini", "papa"]);
  });
  it("keeps the spec's order when nobody depends on anybody, and survives a cycle", () => {
    expect(aimOrder([{ id: "a" }, { id: "b" }]).map((a) => a.id)).toEqual(["a", "b"]);
    const cyc = aimOrder([{ id: "a", overlays: [reach("b")] }, { id: "b", overlays: [reach("a")] }]);
    expect(cyc.map((a) => a.id).sort()).toEqual(["a", "b"]);
  });
});
