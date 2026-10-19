# VideoStudio: native Animate-style timeline

**Status:** implementation branch `feat/animate-style-native-timeline-20261019`; not merged, installed or shipped. Review native Android CI and a real-device end-to-end film before release.

## Design precedent and provenance

- [Flare](https://github.com/Flare-Animate/Flare) provides a public Adobe Animate-style creative editing **workflow** atop OpenToonz. Its `LICENSE.txt` is BSD-3-Clause for its principal source, with separate third-party, bundled-brush and optional decoder licenses; desktop Qt/C++/Rust code is **not** copied into this Android module. Flare's Flash import is experimental, including meaningful gaps in symbol, timeline and compiled SWF fidelity.
- [Ruffle](https://github.com/ruffle-rs/ruffle) independently recreates Flash *playback*, not an authoring IDE. Consult it for compatibility requirements, not for a ready-made editor. **No Ruffle source or runtime is bundled.**
- Adobe Animate and Flash are Adobe trademarks and proprietary products; VideoStudio implements an original native, Android-first authoring workflow. Do not ship Adobe binaries, assets, icons, or decompiled proprietary implementation.
- This implementation has no new third-party software dependency, and remains within the existing VideoStudio source and document model.

## Using it on Android

1. Open a **standalone** editable Sketch Studio drawing (not a paged Sketch Book), then choose **More → Frames / Cels → Animate timeline ◆**.
2. Navigate the 18-frame touch timeline. The cel indicators are produced from the same `SketchXsheetProjection` and `SketchFrameRenderer.exposed` data as the native artwork: **●** drawing, **=** hold, **−** blank, **◆** motion key, **◇** shape key. Tap a frame then choose **Go to frame** to move the actual studio playhead.
3. Choose the **whole layer** for a combined transform, or select an existing drawing on the canvas before opening the timeline to keyframe just that object. The whole-layer mode still allows multiple independent cels and shapes on that layer.
4. Choose **Insert key ◆** at a frame, or **Motion tween**. A tween accepts the end frame, absolute X and Y in pixels, horizontal/vertical scale, rotation, opacity and easing. It stores **two standard native transform keys** (plus an optional exposure edit), not prerendered frame images.
5. Enable **Extend the selected drawing hold** only when the object would otherwise end before its tween destination. Without consent, the edit refuses to animate invisible artwork. All keys inside the span are protected against being silently replaced.
6. **Remove key** deletes only the selected native key. **Symbol library** opens the existing reusable-component library. **Full Xsheet** opens the original multi-layer read-only exposure sheet for inspection. **Undo/Redo** remain the canonical Sketch Studio history.
7. Play/export through the **existing native renderer**. This feature does not claim complete Flash/FLA/XFL import/export, Adobe JSFL, ActionScript, inverse-kinematics parity or desktop workspace parity.

## Agent protocol

The existing revision-safe `sketch_edit` operation accepts a new single **`motion_tween`** edit, so both a person and an agent produce the same art:

```json
{
  "sketchId": "<existing sketch ID>",
  "operationId": "<unique idempotency token>",
  "expectedRevision": 1,
  "operations": [
    {
      "type": "motion_tween",
      "objectId": "car_body",
      "startFrame": 0,
      "endFrame": 23,
      "destination": {
        "x": 0.30,
        "y": -0.05,
        "rotation": 8.0,
        "scaleX": 1.0,
        "scaleY": 1.0,
        "opacity": 1.0
      },
      "interpolation": "ease_in_out",
      "extendExposure": false
    }
  ]
}
```

Invoke this object as the *arguments* of the pre-existing `sketch_edit` command. Source coordinates `x`/`y` are **normalized**, and the UI converts them from/to pixels. For whole-layer motion, supply `"layerId"` **instead of** `"objectId"`. Supported interpolation modes: `hold`, `linear`, `smooth`, `ease_in`, `ease_out`, `ease_in_out`, `bezier`. The native Bezier preset is currently `[0.42, 0, 0.58, 1]`; custom curves remain available in the existing transform-key tool. A hold/stop-motion interval is intentional, not a smooth transition.

Validation rejects locked layers, out-of-range frames, invalid transforms and unsupported curves, missing source cels, crossing existing intermediate keys, and missing explicit consent to lengthen a drawing. The whole transaction is revision-checked and idempotent by the normal `SketchProjectStore` machinery. A tween never replaces the original raster or collapses original vector shape geometry.

Limits are inherited from native Sketch: up to 32 layers, 1,200 frames, 4,096 objects, 4,096 keys and 4 MiB per document. Larger film productions should use paged sequences, shot-based composition and the established export pipeline, not a single enormous editable document.

## QA and release gate

Run the targeted Android tests:

```sh
cd android
./gradlew :app:testDebugUnitTest --tests com.rezoxnemesis.videostudio.SketchAnimateTimelineToolsTest
```

Checks include easing mathematics, transform-key projection in Xsheet, layer vs object animation, source non-mutation, key collision refusal, exposure extension, locked layers, input bounds, idempotent public edits, and persisted undo. Existing Sketch/native animation tests should also pass. Then inspect a real animation export and validate frame-by-frame continuity, artifacts, input latency and memory on an Android device.

**Do not** change the Android package identity, signer/keystore, MCP endpoint, existing project databases, or the user's Gallery. No silent APK installation, upstream merge, paid inference or remote publishing.
