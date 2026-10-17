# OMEGA M1 — connected native CinemaBench film-proof adapter

## What is actually implemented

CinemaBench M1 is called from both existing `scripts/encode-story-film-proof.py` and
`scripts/encode-duel-film-proof.py`, **after FFmpeg/FFprobe publish `mp4-verified.json` and before the
scripts delete their source PNGs**. If any validation fails, the encoder exits nonzero and
retains the original files so an engineer can diagnose the failure.

- **240-frame story film:** The original native proof stores sampled PNG hashes (usually one per
  second), a native pixel-signature transition count, and the sketch source hash. The adapter
  validates those original samples/source and produces a full SHA-256 frame manifest from all
  240 CI-owned PNGs. It reports sampled pixel transitions separately from byte-distinct frames.
- **100-frame two-actor film:** The original native proof already supplies a full ordered PNG
  hash manifest. Each source hash must match the corresponding actual native frame. The
  original vector source/program SHA-256 and authored wrist separation are independently
  cross-checked, with an 80-pixel tolerance; this is **joint proximity, not mesh collision**.
- **Both film types:** CinemaBench checks frame indexes, PNG headers/dimensions, PNG file hash,
  manifest adjacency, frozen-run warnings and optional contact constraints. It verifies the
  FFmpeg encoder's separate `mp4-verified.json` receipt against actual movie hash/bytes,
  FFprobe decoded frame count, frame rate, dimensions, duration, original document hash and
  number of native frames. The native proof is *Robolectric Android Canvas in CI*, not playback
  on the owner's phone.

New outputs kept beside each MP4:

- `omega-frame-manifest.json`: normalized ordered SHA-256 evidence for **every** frame.
- `omega-native-report.json`: gate, technical findings, source/pose SHA, Git checkout SHA when
  available, MP4/FFprobe linkage and explicit unsupported quality claims.
- Existing `mp4-verified.json`, MP4, contact sheet and human-review frame samples remain.

### Run the adapter on a CI-owned film already encoded (before cleanup)

```bash
node scripts/omega-native-proof.mjs --kind story --root android/app/build/sketch-story-film-proof
node scripts/omega-native-proof.mjs --kind duel --root android/app/build/sketch-duel-film-proof
```

Run local synthetic contract tests without Android or any network:

```bash
node --test scripts/omega-cinemabench-test.mjs scripts/omega-native-proof-test.mjs
```

### CI proof

`.github/workflows/omega.yml` has a fast Node/CLI test job and a **targeted, opt-in expensive
native-proof PR job** against the M0 branch. The native job runs the real Android Canvas
`SketchStoryFilmNativeProofTest` and `SketchDuelAnimationExampleTest` with film-proof flags,
encodes the complete native frames using FFmpeg, executes CinemaBench M1, and uploads the
native reports and MP4s. It does **not** assemble/sign an APK, install anything on Android,
call the private native MCP URL, access phone Gallery, or deploy Cloudflare.

### Acceptance boundary

A successful M1 proof means the GitHub CI pipeline produced the declared sequence of
native PNGs and an FFprobe-verified encoded film with auditable provenance; it does **not**
mean fluid acting, believable anatomy, automatic pose reconstruction, audio sync, on-device
performance or film-grade visuals. `releaseDecision` remains `MANUAL_REVIEW_REQUIRED`.

**Next: M2 Motion DNA**, compare cropped character regions/pose trajectories versus estimated
camera-global movement on opt-in original videos; build true/false-labeled fixtures before
attaching new animation scoring to CI.
