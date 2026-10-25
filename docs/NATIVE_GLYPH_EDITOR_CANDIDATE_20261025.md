# Native glyph editor — isolated candidate (2026-10-25)

## Why this is being built

The owner's ten reference MP4s require individual character motion that VideoStudio
4.10.43's whole-title keyframes and source-free vector shapes cannot provide.
This patch is a **bounded, actual Media3 implementation** of per-codepoint 2D
title motion, not an After Effects clone or neural typography generator.

## Native render path

`StudioLayer.TITLE` optionally stores:

```json
"glyphAnimation": {
  "mode": "scatter",
  "staggerMs": 45,
  "enterMs": 360,
  "exitMs": 260,
  "spacing": 0.14,
  "spread": 0.31,
  "waveAmplitude": 0.07
}
```

Each supported non-space Unicode codepoint is a separate Media3 `TextOverlay`
with per-timestamp position, scale, rotation and alpha. Whitespace retains the
layout gap without allocating a texture. A title's existing whole-title
keyframes and optional one-level group parent still affect all its glyphs.
This preserves the same compositor used by the editor preview and Media3 export.

The modes are `assemble`, `scatter`, `cascade` and `wave`; all use deterministic
authored/eased motion rather than random per-frame jitter or bitmap zooms.
The sampler is precompiled once; frame callbacks allocate no new bitmaps/JSON.

Constraints: **18 codepoints/title, 48 native glyph overlays/composition**,
80–1600 ms entrance/exit, 0–160 ms letter stagger, normalized frame-spacing
bounds. Unsupported data fails before saving. This does **not** include
complex-script grapheme clustering/ligatures, full 3D type, physics shattering,
optical-flow motion blur or shader glow.

Legacy TITLE rows without `glyphAnimation` persist and render identically.

## Android editor workflow

From the project's Editor: **Kinetic Director → Glyph**. Enter one short phrase
per line, select Scatter/Assemble/Cascade/Wave, optionally use explicit BPM or
existing clip boundaries and author vector accents. VideoStudio creates an
**independent editable project**; original clips, music and filenames stay
unchanged. No render starts automatically.

For a saved title: **Title → Animate title** now exposes an independent letter
effect selector (Off, Assemble, Scatter, Cascade, Wave), stagger, spread and
wave amplitude. Off clears only glyph motion; existing title transform keys
remain editable. Apply runs normal guarded project-layer persistence; Close
discards draft changes.

## Existing private MCP route

Use the existing `kinetic_benchmark_plan` dry-run, then `kinetic_benchmark_apply`:
```json
{
  "projectId": "<existing-project-id>",
  "expectedUpdatedAt": 123456,
  "operationId": "glyph-reference-cut-001",
  "style": "glyph",
  "glyphMode": "scatter",
  "titles": ["NEVER", "SLOW DOWN"],
  "beatMarkersMs": [200, 2300, 4900]
}
```

The source revision and title durations must be valid; the tool may reject the
request without changing anything. Fusion scenes can likewise set the
`glyphAnimation` object on `type: "title"` nodes. It is not valid on shape/image
nodes. Existing Gallery/media-library enumeration remains prohibited.

## Verification contract

- Run native Java compilation and the entire Robolectric Android unit suite
  with the isolated CI workflow; examine exact test reports on failure.
- Recheck legacy `StudioTimedTitleOverlayTest`, title Motion, Editor and Fusion
  compilation; verify saved glyph program survives close/reopen.
- On a **signed, explicitly installed candidate** compare rendered title frames
  at the first/last glyph's entrance, peak, and exit. Scrub across cuts.
- Measure 1/8/18/48 simultaneous GPU text textures, frame latency, memory and
  thermal behavior on the user's Android phone.
- Export an owned reference test H.264 MP4; decode and inspect a contact sheet
  plus audio, zero blank frames, no 66% stall or recovery confusion. Only then
  evaluate visual similarity to references 1000072374/1000072589.
- Current production version 4.10.43 reports `NATIVE_RECOVERY_HELD`. **Do not
  remotely override the startup circuit breaker.** The owner must first verify
  the app opens safely and explicitly choose the on-device Retry saved render
  control. Running a Java test is not evidence that this blocker is repaired.

**Status:** code candidate + native source tests; not merged to main, not
installed on the device and not certified against the ten references.
