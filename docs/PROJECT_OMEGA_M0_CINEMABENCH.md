# OMEGA CinemaBench M0 — runnable, read-only evidence audit

OMEGA M0 is the first executable slice of Project OMEGA: a dependency-free Node 22 quality report over existing VideoStudio native frame manifests. It does not modify Android, any owner's projects, the private MCP control plane or Gallery permissions.

## Run the 7 independent tests

```bash
node --test scripts/omega-cinemabench-test.mjs
```

The fixture suite includes actual generated 1x1 PNG bytes, altered PNG detection, dimension mismatch, out-of-order frame indexes, false declared change counts, frozen repeated PNGs, intentional holds and an optional two-actor wrist-separation check.

## Evaluate actual owned VideoStudio CI frames

Run BEFORE the film encoder deletes its PNG frame directory:

```bash
node scripts/omega-cinemabench.mjs \
  --manifest android/app/build/sketch-duel-film-proof/native-duel-proof.json \
  --frames-dir android/app/build/sketch-duel-film-proof/frames \
  --metrics android/app/build/sketch-duel-film-proof/duel-stage-metrics.json \
  --max-contact-gap-px 80 \
  --out android/app/build/sketch-duel-film-proof/omega-report.json
```

- `--manifest`: required explicit local JSON; maximum 10 MiB.
- `--frames-dir`: optional, explicitly selected directory of contiguous `frame-0000.png`... frames. Reads only these files; never scans phone media or Gallery.
- `--metrics`: optional existing duel stage metrics. With `--max-contact-gap-px`, the audited `batonWristDistancePx` must fall under the supplied authored threshold.
- `--out`: optional JSON report output. Without this option the report is printed to stdout.

## Exit codes and truthfulness

- Exit 0: technical frame-manifest gate returned `READY_FOR_VISUAL_REVIEW`.
- Exit 1: `BLOCK`: malformed/reordered/mismatched frame evidence, nearly static byte-identical frames, explicit PNG check failure or failed optional contact threshold.
- Exit 2: bad command arguments, nonexistent input or malformed input JSON.
- With no `--frames-dir`, the report explicitly says `MANIFEST_ONLY` and `UNVERIFIED_FILES`. With physical PNG integrity checks it says `PNG_SHA256_VERIFIED`.
- Extended byte-identical frame holds around 0.75 seconds or more receive review warnings, not automatic rejection: an animator may deliberately hold a pose.
- Distinct file hashes can reflect metadata, an unmoving camera zoom or other non-character changes; file checks DO NOT certify real character movement, human anatomy, voice, visual quality or installed-phone playback.
- Passing QA cannot publish an APK or mark a film artistically accepted. `releaseDecision` stays `MANUAL_REVIEW_REQUIRED` without further evidence.

## Next integration milestone

Run this CLI on native 100/240-frame video proof outputs before the existing encoder deletes the source PNGs. Preserve the JSON report alongside the already-published MP4, source-art hash, joint/contact metrics and video probe. Add region-level pixel/landmark analysis for Motion DNA M2 before attempting to distinguish camera-only video from articulated character acting.

This feature branch stacks on the draft VideoStudio 4.5.4 PR only to avoid interfering with the latest animation work. No APK build, signing-key change, Cloudflare deployment, project import or native install is performed by M0.
