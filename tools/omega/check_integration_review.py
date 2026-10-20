#!/usr/bin/env python3
"""Fail-closed OMEGA cross-phase review gate (integration owner only).

Only a pinned, isolated engine/document test assembly may enter the PR.
Production SketchDocument/EditOps/ProjectStore/Activities, recovery, signing
and main branch remain prohibited until separately reviewed.
"""
import argparse
import subprocess

ENGINE = {
    "OmegaMasterDependencyIndex", "OmegaReferenceGeometry", "OmegaBezierFitter",
    "OmegaTwoBoneIK", "OmegaMotionTimeline", "OmegaVisualAudit",
    "OmegaAudioTimeline", "OmegaRenderPlan",
}
ROOT = "android/app/src/"
OWNED = (
    {ROOT + "main/java/com/rezoxnemesis/videostudio/" + name + ".java" for name in ENGINE}
    | {ROOT + "test/java/com/rezoxnemesis/videostudio/" + name + "Test.java" for name in ENGINE}
)
STAGING_BRANCH = "review/omega-nine-phase-integration-20261020"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    if args.branch != STAGING_BRANCH:
        raise SystemExit("OMEGA_INTEGRATION: only the pinned review branch may use this gate")
    ancestor = subprocess.check_output(
        ["git", "merge-base", args.base, args.head], text=True
    ).strip()
    if not ancestor:
        raise SystemExit("OMEGA_INTEGRATION: no verified common contract ancestry")
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", ancestor, args.head], text=True
    ).splitlines()
    if not changed:
        raise SystemExit("OMEGA_INTEGRATION: empty candidate")
    prohibited = []
    for path in changed:
        permitted = (
            path in OWNED
            or (path.startswith("docs/omega/phase-") and path.endswith(".md"))
            or path in {
                ".github/workflows/omega-premerge-native-ci.yml",
                "docs/omega/INTEGRATION_AUDIT_20261020.md",
            }
        )
        if not permitted:
            prohibited.append(path)
    if prohibited:
        raise SystemExit("OMEGA_INTEGRATION: protected/unreviewed files changed:\n"
                         + "\n".join(prohibited))
    missing = OWNED.difference(changed)
    if missing:
        raise SystemExit("OMEGA_INTEGRATION: missing phase engine/test paths:\n"
                         + "\n".join(sorted(missing)))
    print("OMEGA_INTEGRATION_PASS reviewed files=" + str(len(changed)))
    for path in changed:
        print(" + " + path)

if __name__ == "__main__":
    main()
