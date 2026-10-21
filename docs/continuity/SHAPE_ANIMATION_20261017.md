# Editable shape animation — 17 October 2026

VideoStudio now supports authored deformation of editable Bezier paths between shape keys. Eye contours, hair strands and fabric curves can change shape continuously while retaining their editable curves. This extends the previous shading/timing work; it does not establish professional illustration, character acting or reference-quality acceptance.

Current source: **4.6.1 / code 471**. This version has not been installed or released.

## Native authoring

1. Select an editable curve. Open **Brush / Text → Shape animation** and set a key at the first pose frame.
2. Move to another frame and use the existing **Nodes** tool to drag its curve controls. With shape animation enabled, this creates/updates a shape key; the base drawing remains intact.
3. Open **Shape animation** to select segment timing: Linear, Smooth, Hold, Ease in/out or explicit Bezier controls. Timing belongs to the segment beginning at that key.
4. **Remove key** removes the current shape key. **Clear shape animation** restores the base path. The exposure sheet marks authored shape keys with ◇ and transform keys with *.

Before the first shape key, the base path is visible. Exact key frames use their authored geometry; the final key holds. Different path structures still require separate drawings/cels: interpolation requires the same M/L/Q/C/Z command sequence and coordinate count throughout.

All coordinates remain normalized, finite and bounded to 0–1. There are at most 240 shape keys per path. Every stored control point and key counts toward the existing document budgets: 131072 points, 4096 keys and 4 MiB. No dependency or raster replacement was added.

Native node previews, selection, focus, the shared Canvas renderer, cel copying and prepared animation use the same sampled geometry. Duplicate cel freezes the visible source shape; insert/delete frame shifts its keys. Static path consolidation refuses animated shapes. Prepared preview resolves shapes before rigging; baking retains editable shape keys on unbound/rigid paths and samples shape geometry before weighted deformation.

## MCP contract

`app_sketch_edit` accepts a `set_shape_key` operation within the existing sketch ID, expected revision and stable operation ID envelope. Supply `objectId`, `frame`, `commands` matching that object's path topology, and its timing; Bezier additionally requires four control values in `curve`. Use `remove: true` to remove a key. A patch with `shapeKeys: []` clears shape animation. Incoming key geometry is detached from mutable caller data, and invalid whole documents reject before persistence.

`app_sketch_get` accepts `shapeFrame` together with `objectId`. It samples that shape before applying the existing `commandOffset` / `commandLimit` pagination. Returned `shapeKeys` are timing metadata; their full geometry is not embedded to bypass paging. The native editable document/archive retains the complete geometry.

The schema refuses shape-animation requests on native versions below 4.6.1. Actual local MCP SDK transport was verified against the Worker queue, with explicitly synthetic native completion replies. The blocked private endpoint was not retried during this continuation, and these checks do not establish phone execution.

## Independent visual review

Three separately authored native studies each rendered 72 frames at 24 fps. They retain editable source documents. Workspace preview MP4 files encode those unchanged Android Canvas/Robolectric PNG frames using FFmpeg; they are not phone exports.

| Subject | Verified behavior | Remaining visual weakness |
|---|---|---|
| Eye/blink | Eyelid, lash, ink and cast-shadow curves deform continuously; detailed iris marks and gaze timing remain independently editable | Simplified anatomy and mechanical acting; a blink study is not full-character performance |
| Hair | Authored strand roots remain fixed while lower controls and tips move; fine curves and shaded locks remain editable | Stylized, simplified illustration with crude face/neck construction and visible clump boundaries; no collision or hair physics |
| Fabric | Authored contour, weave, hem and embroidery move through curve keys while retaining their geometry | Flat staged material with a fixed lighting field; no cloth simulation, depth, folds in perspective or contact handling |

Visual inspection found detached blink ink/cast-shadow contours in the first QA fixture. Their target-key mapping was corrected and a regression now checks the closed lid. This correction was to the authored fixture, not evidence of automatically fixing arbitrary artwork. Inspection also found that content diagnostics ignored sampled shape changes; the native inspector now includes those changes and counts their stored geometry.

The owner's portrait is still reserved privately and has not been animated. Full-character testing must include that image and independently chosen subjects once a suitable character-animation upgrade is ready. Style/detail retention, articulation, contact/occlusion and failures remain required acceptance work.

## Implementation choices and limits

- Shape keys are path-only and preserve topology. Supporting a different drawing structure requires cels; silently inventing matching geometry would change artwork.
- Baking a shorter timeline rejects retained shape keys outside the requested duration. This preserves authored timing; move/remove those later keys explicitly rather than silently clipping or distorting their easing.
- Static consolidation explicitly refuses when no safe static candidates remain, leaving animated source objects intact.
- Existing restrictions on weighted deformation of gradient-filled artwork remain in force. This work adds authored shape changes, not a general material-skinning system.
- Review was performed directly by the implementing agent; no independent reviewer, deployment, signing, phone GPU playback or phone audiovisual-export acceptance is claimed.

## Execution evidence

| Executed check | Observed result |
|---|---|
| Final full native suite | 1201 cases across 238 suites: 1199 passed, zero failures/errors; two optional legacy larger-frame proofs skipped by default |
| Shape-animation regressions | 15 cases passed: native pixels, persistence, malformed topology/timing, detached input, paging, frame/cel operations, selection, inspection and prepared-animation preview/bake |
| Actual native shape authoring controls | Two activity/canvas tests passed: timing-dialog persistence/removal and a sampled-node drag that preserves the base drawing |
| Independent quality studies | Full opt-in run passed: three subjects × 72 frames = 216 native Canvas PNG frames; the closed-lid contour regression also passed |
| `npm test` | 268 Node tests plus 306 smoke assertions passed; zero failures |
| Local MCP SDK/Worker transport | Exact shape-key geometry and timing retained; older runtime refused before queuing; native completions explicitly synthetic |
| Final suite versus preserved full-frame proof | All three editable source files and seven sampled PNGs per subject match byte-for-byte |
| Workspace preview files | All three decode fully without errors; each is 72 frames, 24 fps, 3 seconds |
| Source review | Direct implementing-agent review and `git diff --check`; no independent reviewer claimed |

Private execution receipts, XML summaries, source/preview hashes and complete native frames remain outside Git under `/workspace/engineering-review/reference-rebuild-20261017/`. Red-to-green logs preserve the reproduced missing feature and later inspection, blink-fixture and mutable-patch failures. No reference archive, portrait, MCP credential or signing key is included in the source archive.
