# Editable shading and timing — 17 October 2026

The native editor now supports multi-stop radial material shading and authored animation curves. This is a reusable authoring improvement. The overall reference-quality animation target remains unmet.

## Implemented behavior

- Filled vector artwork can retain 2–16 radial colour stops, a light centre, and a radius. Existing linear gradients remain valid. Shading can be removed without replacing the shape or its geometry.
- Drawing object/layer keys, prepared character poses, and camera keys support linear, hold, smooth, ease-in, ease-out, ease-in/out, and explicit cubic-Bezier timing. Bezier sampling solves the time coordinate first.
- Native touch dialogs expose the new controls. Changing joint geometry, saving a pose, and editing an existing camera key preserve its authored timing. A deliberate timing change removes incompatible old curve controls.
- Touch camera inversion and prepared-pose sampling use the same timing implementation as rendering.
- MCP validates and transmits the exact material/curve fields. Requests that need the new contract refuse native versions below 4.6.0. The current source version is 4.6.0/code 470; it has not been released or installed.

## Native authoring

In the drawing surface, select an editable shape and open **Brush / Text → Material shading**. Enter the colours and optional stop positions, then light centre and radius in pixels. **Flat colour** removes its gradient.

Open **Transform / key**, enable a transform key, and choose its timing. Bezier accepts four values: x1, y1, x2, y2. Controls are bounded to 0–1; overshooting curves are not supported. The existing Hold option remains available.

In prepared animation, **Pose tools → Timing curve…** edits the current pose segment. The camera-key dialog has separate timing controls. Timing belongs to the segment starting at that key.

For example, an MCP `studio_sketch_edit` operation can carry:

```json
{
  "type": "set_keyframe",
  "objectId": "iris",
  "frame": 0,
  "transform": {"x": 0},
  "interpolation": "bezier",
  "curve": [0.42, 0, 1, 1]
}
```

Use the existing owned sketch ID, operation ID, and expected revision in the enclosing request. Local transport was tested with the real MCP SDK and Worker queue. Completion replies in those transport tests are synthetic; they do not prove phone execution or a live phone connection.

## Visual review of independent subjects

All three subjects were independently authored as editable native vector documents. Each complete study rendered 72 frames at 24 fps using Android Canvas through local Robolectric native graphics. Preview MP4 files were encoded from those unchanged PNG frames with FFmpeg. They are workspace previews, not phone exports.

| Subject | Observed improvement | Remaining weakness |
|---|---|---|
| Eye, blink, and gaze | Multi-stop iris shading, fine radial iris marks, highlights, authored eyelid cels and eased movement remain editable | Simplified surrounding anatomy and lashes; the blink is a short authored study, not evidence of automatic character acting or new-pose synthesis |
| Metallic watch | Editable shaded rim, dial and hands show controllable material contrast | Stylized vector construction remains much simpler than the reflective painting reference; it does not establish realistic materials |
| Moving typography | Authored timing can be adjusted independently of the text and geometry | A short timing exercise does not establish complex editing, music synchronization, or character animation |

The existing story generator still uses limited procedural archetypes. These controls do not replace it with a learned illustration or motion system. Fine hair/fabric deformation, varied expressive character acting, contact/occlusion, and detailed 3D scene acceptance remain open in the reference ledger.

## Reserved portrait

The owner's `1000069164.jpg` is preserved privately with its original-byte hash. It has not been animated or used by this test suite. A later animation-validation round must use it alongside independently chosen subjects, checking detail/style retention, articulated motion, artifacts, editability, and failures. Camera-only movement must be labelled as such.

Private execution evidence and media are retained outside Git at `/workspace/engineering-review/reference-rebuild-20261017/`. No uploaded portrait, reference video, signing key, or credential is included in the source archive.

## Verification and delivery limits

| Executed check | Result |
|---|---|
| Final full Android unit suite | 1,182 cases across 235 suites: 1,180 passed, zero failures/errors, two optional larger story/duel frame proofs skipped by default |
| Focused shading/timing and native authoring regressions | 25 cases passed, including actual material, drawing, pose and camera dialogs; exact Bezier sampling and inverse touch mapping |
| Full quality-study frame proof | Opt-in render passed: three editable subjects × 72 frames = 216 actual native Canvas frames |
| `npm test` | 265 Node tests and 306 smoke assertions passed; zero failures |
| Actual local MCP SDK/queue | Exact material and curve values retained; unsupported native runtime refused before queuing. Native replies are synthetic |
| Preserved full study versus final suite samples | Editable source hashes match for all three subjects; sampled PNG files match byte-for-byte |
| Source whitespace check | `git diff --check` passed |

The two optional legacy frame proofs were also run explicitly during the preceding edit-foundation milestone, as recorded in `REFERENCE_REBUILD_20261017.md`. The final full suite emitted an existing SQLite close-guard warning from `StudioAudioWaveformsOwnedAudioTest`; XML results report zero failures/errors. This warning does not establish a new shading/timing failure or phone resource acceptance.

No physical Android device/emulator is attached. The original pinned signing key is unavailable; the continuity guard refuses replacement or unsigned packaging. Phone-side MCP, in-place installation, GPU playback, and final native audiovisual export remain unverified. No deployment or release was performed.
