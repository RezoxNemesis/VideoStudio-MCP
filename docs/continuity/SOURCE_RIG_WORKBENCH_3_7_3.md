# Original artwork rig workbench — 3.7.3

This milestone makes original-image rig preparation usable on the phone and through
native MCP. It does not install anatomical reconstruction, unseen-limb synthesis
or a neural video model. A completed export remains separate from cinematic QA.

## Phone workflow

Open **Real 2D Character Rigging → Rig My Black & White Fighters** for the selected
project. Inspect the actual oriented original, mark the sixteen labelled joints,
body outline, and original sword outline/hilt/tip for each actor, then save.
Source hashes and project/profile revision guards reject stale annotations.

**Pose diagnostic** opens the saved actor on a labelled checkerboard stage. Drag
joint and sword handles and scrub between rest and the test pose. These changes
affect only the diagnostic; the source rest definition and original bytes remain
unchanged. Rejected or cancelled previews retain the last valid pose and image.
The stage preserves physical source proportions and exposes its exact coordinate
mapping when containment is required.

**Sequence readiness** preserves explicitly selected source occurrences or the
saved program order, including repeats. Without a selection it uses project image
import order. Missing-actor rows reopen the corresponding source/actor. Multiple
profiles require a selection. Fields-present status does not certify anatomy,
source-byte validity, contacts or completed animation.

## Native MCP workflow

New typed tools require native 3.7.3; application protocol v3, owner identity and
the stable endpoint remain unchanged.

| Tool | Result and boundary |
| --- | --- |
| `app_inspect_source_artwork` | Owned original image, optional crop/verified rig overlays, original SHA/EXIF dimensions and `previewToOriginalUv`. |
| `app_preview_source_rig` | Saved source-textured body and original rigid sword at rest or `targetJoints16`; diagnostic image and exact profile proof. `targetJoints` is a compatibility alias; both together reject. |
| `app_save_source_rig_profiles` | One to eight source-SHA-bound profiles under a stable `batchId`, source revision and optional per-profile CAS. |
| `app_source_rig_status` | Exact ordered preparation status, missing/ambiguous actors, and saved-program binding where selected. Source bytes are not rehashed by this structural status query. |

Read queue results with `app_get_command_result`; images are MCP image blocks and
JSON contains bounded geometry/proofs rather than image base64 or credentials.
Large metadata uses `app_get_command_result_page`; concatenate fragments and
verify `viewSha256`. The canonical retained receipt has its separate SHA.

If the deployed relay has not yet discovered the new typed tools, its existing
`app_execute` can queue native actions `inspect_source_artwork`,
`preview_source_rig`, `save_source_rig_profiles` and `source_rig_status` with the
same parameters after the APK update. Native image responses also contain the
existing `contactSheet` compatibility field so that relay's current result
reader can deliver the image. Updated readers deduplicate the identical image.
This compatibility behavior was exercised in a local runtime; live ChatGPT
presentation still requires the actual host and updated installation.

Use normalized **EXIF-oriented original** coordinates for all stored geometry,
not cropped-preview or padded-stage coordinates. Map cropped previews through
`previewToOriginalUv`; stage handles use `stage.originalToStageUv` and its inverse.
No guessed occluded anatomy or silhouette extrema can serve as verified joints.

Batch saves prevalidate every entry before publication. Each profile replacement
is atomic; the whole batch is **not** an all-or-nothing transaction. A stopped
batch returns `PARTIAL_APPLIED`, exact durable receipts and pending indices.
Retry the identical batch to resume/replay. A conflicting request, stale source,
changed profile or uncertain interrupted replacement rejects without overwriting
unrelated accepted work. An interrupted replay may have all profiles already
published while aggregate source-byte verification remains incomplete.

Each native image is limited to 768 KiB of **base64 characters**, not raw PNG
bytes. Including the compatibility alias keeps both images within 1.5 MiB;
complete responses remain under the relay's 2 MiB result limit. Image computation
uses frozen originals and bounded bitmaps; source/profile publication checks
remain separate from diagnostic rendering.

## Render repairs and acceptance

Automatic source-deformation previews reject ambiguous mattes, unresolved or
disappearing candidates, oversized/thin unmatched subjects and excessive candidate
counts. Deformation paints a continuous source-material surface into expanded
bounds instead of clipping moving edge material to the old crop. Material
retention is explicitly `SOURCE_MASK_MATERIAL_ONLY`; its threshold is not an
anatomical coverage certificate. Failed frames release their bitmaps.

The supplied 3.7.2 Test 1 MP4 has 169 valid 30-FPS frames and correct portrait
rotation but visibly loses body material and lacks convincing independent joint
and weapon action. That cinematic acceptance remains **FAIL**. The reported
native session used `AUTO_LAYERS` / `source_deformation`, with no verified actor
rigs; ambient or motion audio is not evidence of physical clash/footstep Foley.

The exact 13 Test 1 anchors still require authentic actor/weapon profiles and
shot-by-shot contacts/choreography in the native project before an articulated
film can be assessed. The separate nine-frame reference packet is preserved and
must not replace that project order. This Codex session has no callable native
VideoStudio connector; the connected phone reported by normal ChatGPT is separate
real evidence. No automatic original-film acceptance is claimed.

## Verification record

The final full local Android run executed **422 tests in 97 suites**, with zero
failures, errors or skips. Regressions cover actual native raster material and
sword motion, EXIF/crop/stage coordinates, owner writes during slow source reads,
source/profile rejection, partial/replayed batch receipts, phone lifecycle/gesture
rollback, ambiguous masks and boundary material. Two integration cases pass real
helper images through `ControlService.complete` into the durable command journal
and stopped-protocol result outbox, asserting completed status and exact proofs.

The Worker full suite passed **306 smoke checks and 131 executable tests**. The
installed MCP SDK was exercised against actual loopback workerd/SQLite for eight
verification groups: discovery/schemas, native version admission, owner/generation
fences, real image content and compatibility alias deduplication, large paged
metadata reconstruction, immutable ACK replay, batch/order receipts and foreign
owner rejection. These use isolated synthetic ACKs; Android raster evidence is
measured separately. They do not establish live ChatGPT presentation or actual
Test 1 acceptance.

Independent source reviews covered image publication, stage geometry, phone
lifecycle, batch recovery and material bounds. The missing image-result `ok`
flag found during review was reproduced and repaired before the final full run.
The earlier complete-blueprint branch remains at
`7bbae6092622bb261fea4cab381cf5d793cd24db`.
