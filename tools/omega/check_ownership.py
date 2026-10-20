#!/usr/bin/env python3
"""Strict OMEGA parallel-branch ownership gate. Standard library only."""
import argparse
import subprocess
import sys

ROOT = "android/app/src/"
JAVA = ROOT + "main/java/com/rezoxnemesis/videostudio/"
TEST = ROOT + "test/java/com/rezoxnemesis/videostudio/"
OWNERS = {
    0: set(),
    1: {JAVA + "OmegaMasterDependencyIndex.java", TEST + "OmegaMasterDependencyIndexTest.java"},
    2: {JAVA + "OmegaReferenceGeometry.java", TEST + "OmegaReferenceGeometryTest.java"},
    3: {JAVA + "OmegaBezierFitter.java", TEST + "OmegaBezierFitterTest.java"},
    4: {JAVA + "OmegaTwoBoneIK.java", TEST + "OmegaTwoBoneIKTest.java"},
    5: {JAVA + "OmegaMotionTimeline.java", TEST + "OmegaMotionTimelineTest.java"},
    6: {JAVA + "OmegaVisualAudit.java", TEST + "OmegaVisualAuditTest.java"},
    7: {JAVA + "OmegaAudioTimeline.java", TEST + "OmegaAudioTimelineTest.java"},
    8: {JAVA + "OmegaRenderPlan.java", TEST + "OmegaRenderPlanTest.java"},
}
BRANCHES = {
    "phase/omega-0-audit": 0,
    "phase/omega-1-linked-art": 1,
    "phase/omega-2-reference-guides": 2,
    "phase/omega-3-sketch-construction": 3,
    "phase/omega-4-hybrid-rig": 4,
    "phase/omega-5-natural-motion": 5,
    "phase/omega-6-visual-quality": 6,
    "phase/omega-7-audio-timeline": 7,
    "phase/omega-8-render-recovery": 8,
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    if args.branch not in BRANCHES:
        raise SystemExit("OMEGA_OWNERSHIP: unrecognized phase branch. Use an integration-owner PR.")
    number = BRANCHES[args.branch]
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", args.base, args.head],
        text=True
    ).splitlines()
    if not changed:
        raise SystemExit("OMEGA_OWNERSHIP: empty phase PR")
    allowed = OWNERS[number]
    bad = []
    for path in changed:
        phase_doc = path.startswith("docs/omega/phase-" + str(number) + "-") and path.endswith(".md")
        if path not in allowed and not phase_doc:
            bad.append(path)
    if bad:
        raise SystemExit(
            "OMEGA_OWNERSHIP: Phase " + str(number) +
            " changed unowned files. Propose shared adapters through integration owner:\n" +
            "\n".join(" - " + x for x in bad)
        )
    print("OMEGA_OWNERSHIP PASS phase=" + str(number) + " files=" + str(len(changed)))
    for path in changed:
        print(" + " + path)

if __name__ == "__main__":
    main()
