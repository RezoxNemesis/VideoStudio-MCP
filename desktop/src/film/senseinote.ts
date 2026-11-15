// What a build says about the judge's findings: short enough to read at a glance, and never left out — whoever asked
// for the build (a person, any model) sees what is wrong without having to remember to ask.
import type { SenseiReport } from "../sensei/types";

export interface SenseiNote {
  grade: string;
  pass: boolean;
  must: number;
  should: number;
  /** the worst findings in words: "MUST s00_scout 0.95–3 s: … → what to do" (MUST first, at most five) */
  first: string[];
  next: string;
}

const r2 = (n: number): number => Math.round(n * 100) / 100;

export function senseiNote(r: SenseiReport | null | undefined): SenseiNote | undefined {
  if (!r) return undefined;
  const bind = [...r.issues.filter((i) => i.sev === "must"), ...r.issues.filter((i) => i.sev === "should")];
  const first = bind.slice(0, 5).map((i) => `${i.sev.toUpperCase()} ${i.shot ?? "film"}${i.t0 != null && i.t1 != null ? ` ${r2(i.t0)}–${r2(i.t1)} s` : ""}: ${i.msg}${i.fix ? ` → ${i.fix}` : ""}`);
  return {
    grade: r.grade, pass: r.pass, must: r.counts.must, should: r.counts.should, first,
    next: bind.length ? 'Fix the MUST findings before showing or exporting the film: sensei.report {sev: "must"} lists every one with its fix, sensei.issue {id} shows it marked in the picture.' : "Nothing that binds: the judge has no MUST or SHOULD findings on the bodies, cameras and story.",
  };
}
