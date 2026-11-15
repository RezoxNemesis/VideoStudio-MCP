import { describe, expect, it } from "vitest";
import { senseiNote } from "../desktop/src/film/senseinote";
import type { SenseiIssue, SenseiReport } from "../desktop/src/sensei/types";

// A build tells whoever asked for it what the judge found — nobody has to remember to ask. The note is short: how the
// film stands, how many findings bind, the worst few in words with their shot and second, and where the rest are.
const issue = (o: Partial<SenseiIssue>): SenseiIssue => ({ id: "x", cat: "motion", kind: "foot-slide", sev: "nice", shot: "s00_a", seq: "s00", t0: 0, t1: 1, at: 0.5, actor: "v", part: null, where: null, pt: null, value: null, limit: null, unit: null, msg: "m", fix: "f", data: null, ...o });
const report = (issues: SenseiIssue[]): SenseiReport => ({ film: "F", dir: "/d", at: "2026-11-15T00:00:00Z", scope: "film", shots: 3, seconds: 9, lang: "en", bench: { id: "youtube", name: "YouTube" }, overall: 90, grade: "C", pass: !issues.some((i) => i.sev === "must"),
  counts: { must: issues.filter((i) => i.sev === "must").length, should: issues.filter((i) => i.sev === "should").length, nice: issues.filter((i) => i.sev === "nice").length }, categories: {}, style: null, sound: null, progress: null, issues } as unknown as SenseiReport);

describe("the judge's note on a build", () => {
  it("no review, no note", () => { expect(senseiNote(null)).toBeUndefined(); });
  it("says how the film stands and how many findings bind", () => {
    const n = senseiNote(report([issue({ sev: "must", shot: "s00_scout", t0: 0.95, t1: 3, msg: "scout's right foot skates 67 cm." }), issue({ sev: "should", shot: "s00_b", msg: "feet cut off." }), issue({ sev: "nice" })]))!;
    expect(n.grade).toBe("C"); expect(n.pass).toBe(false); expect(n.must).toBe(1); expect(n.should).toBe(1);
    expect(n.first[0]).toMatch(/^MUST s00_scout 0\.95–3 s: scout's right foot skates 67 cm\./);
    expect(n.first[0]).toMatch(/→ f$/);
    expect(n.first[1]).toMatch(/^SHOULD s00_b/);
    expect(n.first.some((l) => /^NICE/i.test(l))).toBe(false);
    expect(n.next).toMatch(/sensei\.report/);
  });
  it("names the worst first and no more than five", () => {
    const many = [issue({ sev: "should", msg: "a" }), ...Array.from({ length: 7 }, (_, i) => issue({ sev: "must", msg: `m${i}` }))];
    const n = senseiNote(report(many))!;
    expect(n.first).toHaveLength(5); expect(n.first.every((l) => l.startsWith("MUST"))).toBe(true); expect(n.must).toBe(7);
  });
  it("a film with nothing that binds says so in one line", () => {
    const n = senseiNote(report([issue({ sev: "nice" })]))!;
    expect(n.pass).toBe(true); expect(n.must).toBe(0); expect(n.first).toHaveLength(0); expect(n.next).toMatch(/nothing/i);
  });
});
