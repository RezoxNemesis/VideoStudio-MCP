# VideoStudio 4.10.34 — Original DaVinci-inspired Color Studio

## Why this phase exists

The user requires an original Android-native workflow reaching toward the quality of Adobe After Effects and Blackmagic DaVinci Resolve reference videos. Real Resolve has distinct Edit/Cut, Fusion, Color, Fairlight and Deliver workspaces. This feature implements a bounded native **Color page foundation** rather than importing either company's proprietary code, user interfaces, trademarks or project files.

## Confirmed source changes

1. New project-owned serial color nodes: primaries (exposure/contrast/pivot/saturation/temperature/tint), RGB lift/gamma/gain controls, per-channel curves with ordered points, hue-limited correction and filmic toe/shoulder.
2. A strict 1..8 node validator refuses unknown fields, file URLs, NaN/Infinity, oversized curves and out-of-range values.
3. One 17³ pixel-space LUT is built per clip program; Media3 `SingleColorLut` applies that GPU LUT in the same `NativeRenderEngine` video-effect method shared by preview and export. No per-frame CPU grading and no file-system LUT access.
4. `resolve_color_capabilities`, `resolve_grade_plan` and `resolve_grade_apply` enter the stable private MCP mailbox, closed alias registry and native background admission list.
5. Owner human Color Studio offers presets, primaries, optional filmic tone curve, exact node JSON and apply-to-current or bounded multi-clip grade. All changes are stored as `resolveColorNodes` within existing clip effects, with guarded revision/Undo compatibility.
6. Batch operations apply atomically to 1–64 distinct project-owned timeline clips, only after full validation. Reset removes this original Color graph and does not remove legacy adjustments.
7. The multitrack workspace prioritizes the actual hit-testable timeline band above secondary compact/pro mode controls, fixing one regression affecting enlarged Android fonts.

## What this does not promise

No Adobe AEP/Resolve DRP import, no Resolve YRGB scene-referred HDR pipeline, DaVinci Wide Gamut, ACES 2.0, external .cube parsing, false-color/waveform RGB parade/vectorscope/scopes, temporal denoising, local beauty tracker, tracking a moving qualifier, 3D camera tracking or AI video generation. Those need independent native modules and GPU device QA.

## On-device release gates

- All existing Node/MCP contracts, Android Java compilation, Robolectric unit suite and original signing continuity must pass.
- The user's `Test subject 2` must play in edited preview without black frames or unrelated old project footage.
- At least 3 source still/video clips must show visibly different neutral/cinematic/warm grades in a real output H.264 MP4, while decoded source assets remain byte-identical.
- Preview/extracted export frames must agree on corrections (the unit-test LUT is only arithmetic proof).
- Thermal, GPU memory and timeline first-frame latency measurements are needed before claiming speed improvements.

## Follow-on DaVinci and After Effects roadmap (not implemented in 4.10.34)

- Fairlight: multitrack equalization, calibrated compressor/limiter, fades, metering and loudness checks.
- Fusion: bounded node graph, matte compositing and tracked mask trajectories.
- AE: layered 2.5D projection, 3D camera/depth, full precomposition caching, controlled motion blur and particle emitters.
- Professional waveform/histogram, colour-space transforms, proxy/cache and audio-video QC.

The installed APK and its private MCP credentials were not altered by this draft branch.
