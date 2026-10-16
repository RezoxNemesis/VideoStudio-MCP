# ADR: native anime Xsheet + optional external Grease Pencil 2.5D renderer

Status: **architecture proposal / review only**, 2026-10-16. Source base: 4.5.4 draft PR #85. No OpenToonz, Blender, Krita, Skia or Lottie binaries have been added to the VideoStudio APK by this ADR. No extra Gallery access, paid API or arbitrary external media upload has been authorized.

## 1. Decision and why

We want editable, professional **original anime** from the VideoStudio Android Sketch Studio, not a slideshow or whole-character zoom. We adopt **OpenToonz production concepts** for on-device 2D authoring and an **optional, isolated Blender Grease Pencil render worker** for advanced 2.5D shots. VideoStudio remains the project owner, reference registry, frame/exposure/timing authority and local Android editor.

We do **not** copy the entire OpenToonz desktop renderer into the Android APK or embed Blender as an Android library. The costs, portability and licensing must be evaluated independently, and exporting assets to other tools must be opt-in, explicit and auditable.

Source audit:
- OpenToonz: official license is modified/new BSD (BSD-3-Clause) **outside** third-party and MyPaint brush folders; those require their own license audit. https://github.com/opentoonz/opentoonz/blob/master/LICENSE.txt
- OpenToonz official plugin SDK: extends *OpenToonz* by adding FX nodes/plugins; it is **not** a general embed-ready drawing/compositor runtime API for other apps. https://github.com/opentoonz/plugin_sdk
- OpenToonz documentation explicitly describes Xsheet/timeline, separate palettes and an FX-schematic node tree. https://opentoonz.github.io/e/ and https://opentoonz.readthedocs.io/en/latest/interface_overview.html
- Blender Grease Pencil 4.5 supports native drawing in 3D and scripted rendering in a separate background Blender process. This is a genuine 2.5D work mode, not automatic character reconstruction. https://docs.blender.org/api/4.5/bpy.ops.grease_pencil.html and https://docs.blender.org/manual/en/4.5/advanced/command_line/arguments.html
- Blender is under the GPL (v3 or later for current binaries); Python scripts using its in-process API may have GPL compatibility obligations. Separate process boundaries reduce coupling but are **not a legal guarantee of independence**. Request an actual license review before distributing a separate Blender runtime or plugin/add-on. https://www.blender.org/about/license/
- Skia already underlies Android/Android Canvas rendering; a second vendored Skia NDK renderer is not justified until benchmarks identify a specific gap. Skia itself is BSD-3-Clause. https://skia.org/docs/ and https://github.com/google/skia/blob/main/LICENSE
- **Correction to the supplied PDF**: Lottie for Android (Airbnb) is **Apache-2.0**, not MIT. rlottie and lottie-web are separate projects whose licenses must be checked independently. Lottie solves lightweight vector playback/UI, not the multi-character film authoring pipeline. https://github.com/airbnb/lottie-android

The user's provided five-page *2D Animation Engine Architecture* PDF is directional research, not a vetted SDK compatibility or legal assessment.

## 2. Grounded VideoStudio baseline (source in 4.5.4)

| Existing source | Works today | Important missing production feature |
| --- | --- | --- |
| `SketchFrameRenderer` | Android Canvas drawings, vector paths/raster, per-layer visible object index, cached geometry and actual PNG/MP4 frame input | style-indexed fills, scene-scale compositing graph, mobile frame-budget tracking |
| `SketchTimelineTools`, `SketchBookStore`, `SketchCelOps` | page/frame holds, object exposure, insert/delete/clone | actual multi-level Xsheet with exposure cells per level, sound/dialogue columns, dope-sheet shot structure |
| `SketchPaletteLibrary` | pre-defined swatches, nearest color lookup | global stable palette **style IDs**, color revisions propagating coherently to already-painted cels |
| `SketchAnimationCompiler`, `SketchAnimationFootwork` | rigged poses, weighted paths, contact constraints, camera-affine animations | camera-accurate 2D↔3D depth stage, physically staged action, better silhouette/acting |
| `SketchAnimationVisemeTiming`, `SketchVoiceActivityTiming` | approximate text visemes plus selected-audio waveform gating | actual phoneme recognition/forced alignment, recorded audio muxing and end-to-end A/V QA |
| `SketchNativeExport` | revision-checked native frame→MP4 export with original project/source preserved | independent effects render passes, 2.5D worker round-trip and audit-able frame re-import |

The renderer is already Android Canvas/Skia-backed at OS level; simply linking Skia or OpenToonz will **not** solve missing character artwork, choreography or lip-sync.

## 3. Proposed modular production system

```text
Story / director / dialogue planner → Versioned Shot + Character IDs
                    |
          Native Android 2D Authoring
      ┌─────────────┼─────────────────────┐
      │             │                     │
  Xsheet/cels   Palette IDs &        Rig/IK/shape keys
  frame holds   ink-and-paint        + in-between timing
      └─────────────┼─────────────────────┘
                    ↓
        Versioned Scene Package (immutable source checksums)
                    |
             Shot Render Plan
        ┌───────────┴───────────┐
        ↓                       ↓
 Native Canvas/Skia       Optional external
 2D compositor            Blender Grease Pencil
                          2.5D/3D scene worker
        │                       │
    native PNG/pass       source-aligned PNG/depth/
                         camera and render manifests
        └───────────┬───────────┘
                    ↓
      Deterministic multi-pass compositor
                    ↓
       MP4/A-V inspection + source archive
```

### A. Xsheet/cel timing
- A `level` is a named, revisable drawing set (line art, fill, highlights, mouth, effects, background).
- A column references that level; a row is an integer source frame with an explicit cel ID or **blank**.
- Repeated cel IDs are purposeful holds, not duplicate-looking evidence of movement. Audio/phoneme markers occupy separate time-aligned tracks.
- Preserve existing source `frame`/`exposure` semantics. Implement a **read-only migration/projection first**; require explicit revisions and atomic/non-destructive edit receipts before writable Xsheet changes.
- Support onionskinning and a quick Xsheet↔timeline toggle suitable for phone screen size. Do not attempt the full desktop Xsheet UI on a narrow Android display.

### B. Indexed palette/ink-and-paint
- Separate **stable style ID** from RGBA paint value. One palette revision can recolor thousands of inked cels without rewriting each drawing object.
- Store per-object or per-region `styleId` in **future** schema while retaining existing literal `color` fallback for legacy projects.
- Store color-space/alpha/profile explicitly and test palette preservation across at least three character views. No destructive color quantization as a silent default.

### C. Local FX compositor
- Proposed DAG nodes: Layer/Cel Input → Transform → Matte → Blur/Glow → Color Correction → Grain/Line Effect → Blend → Output.
- Topological sort, cycle rejection, immutable source, deterministic frame caching by node+source hashes, bounded memory and cancellation.
- First build two carefully benchmarked Canvas/Skia filters and preview them on Android. Do not copy OpenToonz's FX plugin host into Java and claim compatibility.

### D. Optional Grease Pencil render worker
- For complex shots, user explicitly exports the needed **approved** project assets only (SVG strokes/PNG + JSON frame and camera manifest); no broad device media scanning.
- Standalone Blender runs in its **own installed runtime** on a user-owned PC/server or permitted CI worker, *not on the Android UI thread and not inside our APK*. Import stroke and prop placements onto proper 3D planes, with explicit per-layer depth and camera paths.
- Background invocation is bounded and auditable; conceptually `blender --background --factory-startup --disable-autoexec --python-exit-code 1 --python bridge.py -- manifest.json`. Verify exact CLI order and Blender version in the worker. Never evaluate arbitrary instructions embedded in untrusted .blend, assets or prompts.
- The worker returns PNG (and optionally EXR/depth if reviewed), each frame's SHA-256, exact FPS, scene camera report and clean process exit. Native compositor imports only named verified outputs. No automatic upload of user projects.
- Remote rendering availability, hosting/CI cost, render latency, OpenGL/GPU availability and GPL distribution require separate decisions. In particular, a GPL Blender Python add-on must not be assumed safe for closed-source bundling.

### E. Interchange first; direct ports last
1. Begin with **PNG image sequence + small versioned JSON scene manifest**, and optional SVG vectors. These are supported independently of OpenToonz internal .tnz/.tlv proprietary project encodings.
2. Prove same frame order, visual alignment, palette consistency and timing after export/import.
3. Later investigate OpenToonz/XDTS timing interchange, Grease Pencil .blend interchange, and explicit native C++ extraction only after engineering/third-party dependency auditing.
4. Do not promise that OpenToonz's *FX Plugin SDK* can be simply embedded inside VideoStudio.

## 4. First engineering increments (not implemented by this ADR)

| Phase | Deliverable | Technical acceptance |
| --- | --- | --- |
| 1 | Read-only lossless Xsheet projection over the existing SketchDocument | 240-frame/20-level test, frame and blank/hold parity with `SketchFrameRenderer` |
| 2 | Versioned cel/level/time operations | atomic undo/redo/recovery, revision conflicts, stable exposed frames across inserts/deletes |
| 3 | Palette style IDs | recolor 500+ cels with stable topology, preserve exact legacy fallback and non-mutated source |
| 4 | Source-checked local FX DAG | detect cycles; cache consistency; cancellation; bounded 720p device memory |
| 5 | Explicit external 2.5D interchange | 3 prepared planes, scripted camera, independent frame hashes, round-trip deterministic alignment |
| 6 | Native Blender-worker queue after separate review | no arbitrary scripts, no credentials in project data, work can be cancelled, compute budgets and explicit transfer consent |
| 7 | Cinema-quality acceptance | original two-character multi-shot film: new hand-drawn angles, staging, expressive acting, planted feet, audio-aligned lips, multi-layer lighting and owner visual approval |

These tests are **targets**, not completed promises. Performance thresholds must be set using actual on-phone baselines.

## 5. Rollout and risk rules

- Never overwrite owner data or original source; all new schema fields must be optional/backward compatible at the read boundary.
- Keep stacked 4.4 → 4.5 → 4.5.1 → 4.5.2 → 4.5.3 → 4.5.4 PRs unmerged until their parents safely land.
- Retain signing certificate, Android package identity, private MCP owner trust, explicit-asset-only permissions and Gallery browsing prohibition.
- Use one small feature PR at a time; unit tests plus a distinct **actual rendered film**, not only compiler pass.
- Record both source-package integrity and failed render diagnostics. Keep full-resolution phone MP4 playback, audio drift, thermal/memory and human creative critique as separate acceptance gates.
- A valid vector/Xsheet/Grease Pencil pipeline improves artistic *control* but cannot create unseen character art, anatomy or credible expressions automatically.

Next issue: [Seven-system 2D film acceptance #82](https://github.com/RezoxNemesis/VideoStudio-MCP/issues/82).
