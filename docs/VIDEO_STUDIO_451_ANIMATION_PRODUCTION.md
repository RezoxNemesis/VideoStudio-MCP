# VideoStudio 4.5.1 — practical 2D animation production tools

This change is based on the **4.5.0 source branch** and its prepared-sketch animator from PRs #78 and #80. It does not replace the installed app or its original projects. It does not silently browse Gallery media, download a paid AI model or rotate the app signing certificate.

## Use in the Android app

1. **Sketch Studio**: create separate editable body parts and four distinct vector mouth drawings (e.g. `mouth_closed`, `mouth_open`, `mouth_round`, `mouth_teeth`). Align the artwork so all four mouth shapes occupy the same face region.
2. **Sketch Studio → More → 2D animation**: use Rig mode and Bind artwork to attach independently moving body parts to joints. Check that each mouth shape is appropriately aligned and, if needed, bound to the head joint.
3. **Acting presets**: choose Idle, Walk, Run or Anticipation → Strike → Recover. Set an empty time interval, minimum nine frames. This inserts actual native pose keys (angles and root offsets) into the existing editable character rig; it does **not** replace the source drawing or existing poses.
4. **Foot contact**: pin each planted foot to the intended target, checking knee bend and world rotation. Motion presets intentionally do not guess floor contact or solve stride-dependent steps.
5. **Cels / dialogue → Timed dialogue**: add a frame-bounded text cue, then choose **Automatic text mouth timing**. Select an existing cue and four distinct prepared vector mouth objects. The output consists of individual, editable original/clone replacement cels that show only inside the dialogue interval.
6. **Camera**: author layered pans/cuts as before. For real reverse shots, prepare new artwork instead of rotating a single flat picture.
7. **Production QA**: run asynchronous source compilation and inspect articulated frames, bound artwork count, mouth-cel count, contacts, document budget and camera diagnostics. It runs the actual native compilation rules rather than a fake HTML preview. Fix compilation failures before **Save scene / Bake animation**.
8. **Export video** from the newly baked **separate** editable sketch using the existing protected export path. Watch the actual MP4 and confirm motion, anatomy, color, frame order and sound after export.

## Engineering contracts

- `SketchAnimationMotionLibrary.insert`: finite editable normalized-key poses; rejects overlap and out-of-range intervals without mutating the caller's program; supports 9–360-frame clips inside 1–1200-frame scenes. Uses the rig's existing authored joint IDs and angles. The four motions are reusable *starting poses*, not fully realized motion capture or a substitute for animating acting.
- `SketchAnimationVisemeTiming.apply`: requires four **different** actual vector mouth objects, a timed dialogue, and non-conflicting cel ranges. Creates a deterministic text-based viseme timeline. Selectively hidden originals ensure unused mouth shapes do **not** stay overlaid on the face between sentences, including preview and baked export. Previously authored cel timings are not silently replaced.
- `SketchAnimationCompiler` understands the optional `hiddenArtwork` field. Older programs without it compile unchanged. Cloned replacement cels inherit rig bindings from their prepared originals. Hidden objects not explicitly visible via a replacement range do not render.
- `SketchAnimationProductionPreflight.inspect`: uses the *same compiler* as Bake animation and produces actionable diagnostics. The operation does **not** write/save/replace a project, upload frames or render audio.
- Existing Android ID `com.rezoxnemesis.videostudio.next`, original signing certificate, native MCP, source sketch immutability and local project storage remain unchanged. Version code is 451, name 4.5.1.

## Limits and reality checks

Text-to-viseme maps **written letters** into four approximate mouth shapes. It is **not** audio phoneme extraction, speech synthesis, recorded voice alignment, or facial performance reconstruction. Mouth cels must be drawn by the artist. Run/Walk require manual foot-contact editing to prevent sliding. Pose presets do not infer realistic anatomy from arbitrary flattened images.

2D production still needs artist-designed key poses, believable timing, multiple new camera angles, full-length shot continuity and editorial judgment. Native compilation and a structurally valid MP4 do not certify film-grade visual quality or that an actual phone has played the film.

## Validation gates

- `SketchAnimationMotionLibraryTest`: non-destructive key creation, different limb phases, real compiler articulated-motion report, strike/idle preservation and collision rejection.
- `SketchAnimationVisemeTimingTest`: exactly one mouth cel per speaking frame, no visual leak outside cue, actual compiler preview/bake consistency, duplicate/overlapping timing rejection.
- `SketchAnimationProductionPreflightTest`: source-preserving compiler QA and honest visual/audio caveats.
- Existing 4.4/4.5 Android unit tests, APK signing checks, native 10-second story proof and Node contract tests must remain green.
- **Separate acceptance required:** install signed APK on the owner's Realme phone, animate a prepared character, replay/inspect full MP4 including sound, measure on-device speed and approve acting quality.

## The seven-system implementation ladder

| Production subsystem | Baseline in 4.4–4.5 | 4.5.1 concrete addition | Still needed |
| --- | --- | --- | --- |
| Drawing | Sketch Studio vector/raster layers, paint, onion skin, palettes | Works with existing prepared character/mouth vectors | More accurate automated sketch generation and reference continuity |
| Character animation | Rigs, IK, weighted vectors, authored poses | Four real editable action-key templates | Real weight shift/contact adaptation and richer deforming paint |
| In-betweening | Key interpolation and exposure ranges | Timed preset key sequences through existing solver | Better action arcs, acceleration and artist corrections |
| Cameras/effects | Camera keys, 2D depth layers | QA for unexamined/blank camera choices | Better multi-shot staging and per-shot character continuity |
| Timeline/dialogue | Cels, exposure, captions, audio editor | Automatic **approximate text** viseme replacement | Recorded-voice phoneme alignment and full soundtrack |
| Story director | Three-shot vector draft, person/car/dragon templates | Reusable character action primitives | Arbitrary scripted actions, semantic continuity, cross-shot planning |
| Rendering/QA | Android Canvas + safeguarded MP4 export | Compile-based preflight with explicit limitations | Native physical-phone performance QA and pixel/audio acceptance tests |

This is a substantive incremental engineering delivery, not a claim that all seven subsystems are now finished.
