# Native Editor workspace — implementation design, not implemented yet

Isolated branch `feat/native-editor-workspace-20261024` starts frozen candidate523
`acf57cd6b8b9190d8b82df283d2529c99f77ec56`. Root owns version selection, the
currently running clock524 Gradle lane and all native/JUnit/ADB/CI/signing work.
This document and baseline RED test sources precede production edits. Preserve
all published changes, dirty worktrees, source media, projects, history and signer.
The confirmed render-clock repair takes priority. PNG-save production is parked.

## Concrete problem and acceptance

In the actual720×1280 screenshot, only the Timeline heading is visible above the
global navigation. A three-line project title, PlayerView controller, duplicate
scrubber/time controls and status/render rows push clips and image keys offscreen.
The existing native editing operations should be reachable in a fixed workspace
without changing their renderer, data model, CAS, archive or MCP contracts.

At480×853dp portrait, and853×480dp landscape, require: fixed single-line project
selector and48dp Export; visible native preview, one edited transport/time/scrub,
and a useful timeline without whole-page scrolling. A lower Media / Timeline /
Inspector pane changes only its own contents. Keep the same preview/player and
playhead, selection, pending changes and Undo/Redo on pane changes. Native image
motion must be discoverable from the selected image; source Crop belongs to the
selected base clip. No new rendering/AI/editor capability is implied.

## Smallest implementation

1. Keep `MainActivity` the existing coordinator. Introduce a UI-only
   `StudioEditorWorkspace` container/builder if it reduces this monolithic method;
   it must not own ProjectStore writes, render code, media decoding or a new edit
   model. Preserve `studioPreviewFrame`, `studioSeek`, `studioTimeLabel`,
   `studioSplitButton`, `studioTracks` and existing error/retry tags and callbacks.
2. First build attaches the native player once. Use an adaptive preview height
   and horizontal landscape arrangement instead of putting the entire Editor in
   one ScrollView. Individual panes may scroll. Timeline remains the default.
   On phones tabs switch lower content; tablet/landscape may show side panes
   together where space permits. Preserve global Home/Editor/Activity/Tools/Agent.
3. Project selector ellipsizes only its displayed line; its raw accessible text
   and existing project-library action retain the complete name/identity. Export
   remains outside scrolling panes. Native status is a truthful compact entry
   retaining Agent/Activity navigation, not a fabricated connected state.
4. Hide duplicate PlayerView controller only after the new edited transport has
   play, pause, seeking, current time, exact Time and cut navigation wired to the
   existing handlers. Add a UI-only controller-visibility method to LiveEditPlayer
   if needed; do not change composition, timestamp, error/fallback or export code.
   Keep Latest MP4/All Renders reachable in a separate Renders menu, preserving
   their exact native functions and accessible identities. They are not the
   current edited composition. Preserve native preview settings access.
5. Lower tabs are labelled Media / Timeline / Inspector. Switching tabs must
   not call the full showEditor()/setScreen path, reattach the player, write a
   project, reset history or silently change selected clip. Wrap compact tab
   buttons appropriately so StudioUi's large-font row reflow does not force the
   whole workspace offscreen. Each target remains at least48dp and text readable.
6. Timeline reuses current clip navigation/arrange/split/trim and StudioTracksView
   timing, snapping, zoom, drag/trim behavior. This first layout change does not
   claim a new true per-layer track compositor: the existing two-band custom
   view still has limits when several visual overlays overlap. Keep a reachable
   list/selector for every existing lane. Do not drop any current lane to fit.
7. Inspector holds UI-only typed selection `(projectId, clipId or layerId, type)`.
   Initial selection remains current base clip. Tapping an existing image lane
   selects that layer and exposes its Inspector rather than launching an
   unrelated clip control. Never write this presentation state into project JSON
   or MCP fingerprints. If a source revision removes that target, reselect an
   existing target explicitly; never reuse an ID from another project.

## Pane contents and existing handler mapping

| Pane/target | Keep reachable | Existing native source |
|---|---|---|
| Media | Files import, Import from project, owned assets, Append to timeline, Image Overlay, Music, Caption | pickMedia, ProjectAssetImportDialog, renderStudioMediaBin, chooseStudioImage/Audio, editStudioTitle |
| Timeline | clip select/reorder/duplicate/delete, Split/Trim, layer selection/drag/trim, zoom/snap, Undo/Redo | StudioPrecisionEdits, StudioTimelineClock, StudioTracksView, restoreStudioHistory |
| Base-clip Inspector | Trim/Speed/Volume, Transform/Keyframes, Crop/Reframe, Color/Blur/Mask/Chroma/Glow | existing performStudioTool and dedicated dialogs; exact project/clip/revision unchanged |
| Image Inspector | static placement/timing, Animate overlay, delete | editStudioImage + shared StudioImageMotionDialog; preserve source/static precision and all other lanes |
| Audio Inspector | source trim, placement/duration/gain, existing waveform | editStudioAudio + StudioLayerContract |
| Title Inspector | supported static timed text and styles | StudioCaptionEditor + StudioLayerContract |
| More | searchable tool catalogue, Sketch Studio, Animation Director, Rig Builder, 3D, Open Pipeline | existing StudioToolCatalog/buildStudioDirectory callbacks, truthful unavailable labels |

For image motion, factor the existing `editStudioImage` prepared-draft/snapshot
construction into an internal method if necessary; do not simulate button clicks
or create a second keyframe sampler. Source Crop must not appear as supported on
an IMAGE overlay. The Inspector's Animate overlay action uses the same native
helper/guarded layer save, not new shape, hand, camera or video-overlay inference.

## Lifecycle and editing safeguards

Pane switches preserve source selection and do not invalidate source-bound
numeric drafts merely because a presentation tab changed. True project/drawing
navigation, source revision changes and Activity destruction retain current stale
admission protections. Do not bypass expectedUpdatedAt in any Inspector action.
Keep one normal Undo per existing save; pane selection itself adds no history.
Existing asynchronous image/audio thumbnail/analysis operations remain worker
owned and cancel old-view callbacks. No bitmap file decode on the UI thread.
A pane toggle should not restart the preview at zero, stop imported source audio,
or overwrite manual playback intent. Existing global source/renderer clocks stay
authoritative; root's524 correction will be integrated without being replaced by
layout-side time math.

## First meaningful baseline tests

New test sources compile against523 and first exercise actual native Views,
measurement, clipping, button actions and current saved project fixtures. Root
must execute/freeze their RED against the baseline before any production edit.
Tests cover portrait/landscape viewport visibility; bounded project header and
Export; PlayerView-controller duplication; lower-pane navigation preserving
preview instance/project/selection/playhead/history; Media Files/project import
routing and base Crop/Keyframes dialogs; actual StudioTracksView image selection
leading to its motion controls rather than base Crop. The tests do not assert
screenshot colors, mirror implementation code, mock an app screenshot or claim
GPU/codec/film verification.

After passing focused tests, root will capture actual installed-APK screenshots
in both orientations and enlarged text, exercise touch navigation, native
preview/seek and editing, preserve source bytes, then run relevant complete
regressions/CI and original signing. No merge/main/Worker/stable publication.

## Prepared implementation and new visual steering

Root executed and froze the genuine seven-case baseline RED:7 failures in5.282s.
Portrait tracks were actually measured at y1181..1331 outside the853px viewport;
landscape tracks at1211..1361 outside480px. The title wrapped to four lines,
PlayerView duplicated controls and the pane buttons were absent. Original test
SHA9725e9148d8cc2aa160d3b9363365af2f6cd1d2559a9daeabce8a8d7d7c72378
is unchanged. Failed source/log evidence remains in the private UX audit root.

A first unexecuted implementation was preserved privately before the owner sent
VideoStudio_Quiet_Canvas_10_PNGs.zip. Its04_Video_Editor is visual direction only:
charcoal/sage/ivory, restrained typography, thin native outline icons, compact
header, one transport and Trim/Adjust/Motion/Audio/More. No mock scenery, project
name, waveform, job percentage or AI result is copied into product state.

The current prepared source keeps the fixed workspace and functional pane tabs,
adds Editor-scoped quiet presentation and an optional StudioTracksView palette
setter whose default retains all previous colors/geometry. Native hit mapping,
timing and renderer math are unchanged. MainActivity's global navigation hides
only while this Editor is visible; Back/More retains Home/Activity/Tools/Agent,
and other screens restore the original navigation. Footer actions route existing
selected-target controls. Native preview settings and previous renders stay in
More, distinct from the current edit. The source uses only real project data.

Root's first Quiet native retake executed12tests/5failures in8.125s, including
the unchanged seven new tests and five existing EditorPreviewUiTest cases. The
existing five, header and landscape checks passed; portrait tracks remained
below the viewport and the tabs had zero height. Exact failed sources, tests,
launcher and log are privately preserved in quiet-green-v1-failed-preserved.
The cause was native production measurement: generic touch styling changed the
preview container to WRAP_CONTENT because it owns the Retry button, letting
PlayerView aspect sizing consume the portrait workspace. The Editor now owns
its preview height in onMeasure, after styling, budgets actual font-aware fixed
controls and reserves space for the real Timeline heading and tracks. Generic
styling, tests, renderer and landscape source timing remain unchanged.

Separately, root froze three baseline follow-up cases:3tests/2failures in4.722s.
The real paused clock unnecessarily published identical time twice; the manual
image form still bounded authored duration to the default3seconds. The existing
video source bound passed. Root authorized scoped production repairs: image
numeric trim accepts the existing native24h bound while seek handles retain a
practical current extent; video source limits remain. Paused UI polling updates
only changed text/seek/playhead/button state, at250ms paused or100ms playing.
All three follow-up assertions and the original seven remain unchanged.

Root's retake passed all15focused cases in9.081s: unchanged seven layout,
three follow-up and five legacy preview assertions. This is focused native
View/handler verification, not installed-APK/GPU/codec or artistic acceptance.

An independent source review then identified in-place rotation and playback
control regressions. Root froze five further tests against that exact source:
5tests/4failures in5.04s; initial1.6font reachability passed. Constructor-only
orientation left the portrait layout unchanged during real configuration
dispatch. The sole Play indicator stayed stale after native pause/layout
rebuild, and saved-render playback inherited a hidden native controller.
The failed generation, original five assertions and logs are preserved privately
in configuration-genuine-red-v1; testSHA93bd246fe82c2275b4ec3a38246890b2e6198238a11b6f00050e1e3cc1eecf2e
remains unchanged, as do earlier seven/three assertions.

Prepared repairs now reflow the same existing Views on configuration changes,
without player admission or project/history edits. Play/Pause presentation is
derived from actual native playback intent with changed-only updates. Saved
render playback explicitly restores PlayerView controls, hides edited-only
transport and shows Saved render / Return to edit; its More menu avoids edited
time/cut actions. Explicit return uses existing edited-preview admission and
restores one app transport only on success. Both Latest MP4 and All Renders
use this mode handling. These are playback UI fixes, not proof of decoded media.

No successful retake/native screenshot/installed-device/export verification is
claimed for these latest repairs. Root must compile/retest, broaden lifecycle
checks and capture actual APK output before considering the workspace verified.

The next mixed run executed23cases/5failures in10.152s. Earlier seven/three/five
legacy cases remained passing. The remaining configuration failures exposed
layout-only re-admission changing actual playback intent, and a portrait track
width surviving the first landscape measurement. The three new target cases
identified missing explicit source-still return, lost saved-render controls on
an error-preserving rebuild, and one fixture sequencing error: a second
showEditor created new Views but the video fixture clicked Media before its
manual root layout. Root approved exactly one fixture.layout() after that
rebuild/idle; every assertion is unchanged. Original target-testSHA8ab63adb...
and the whole failed generation are preserved; correctedSHA94a091157d50ebde508fe2054b82a6712f3aacaaac1f4b01ca53013bf4d137f8
is documented in playback-target-fixture-layout-correction/Manifest.json.

Current prepared source uses explicit UI-only edited/saved-render/source-video/
source-still targets. Non-edit targets have truthful labels and Return to edit;
only video/render targets show native video controls. Source stills use the
existing bounded worker thumbnail loader, with target tags cleared on return.
Same-project target state survives a layout rebuild, including prior renderer
errors; missing source assets return to the edit. Unchanged admitted edited
revisions reuse the native player without a new seek/admission; explicit seeks
and real revisions still use ordinary admission. Track zoom width is assigned
inside its ScrollView measurement, before the first rotated layout. These
changes await root's native retake; no decoded-source or finished-film claim.

## Focused verification and headless graphics limit

Root preserved the failed23/26-case generations and ran both whole-flow and
failure-only diagnostics. Real `CompositionPlayer.onPlayerError` nested causes
identified a headless Robolectric NPE in `GlProgram.setFloatsUniform` during
`DefaultShaderProgram` configuration. Its GLES20 shadow reports program-link
status but does not enumerate active shader uniforms, leaving the uniform map
empty. This is an observed unit-host graphics limitation; production errors,
fallback, rendering and playback intent were not suppressed or forced.

Only the five NEW UI test classes now configure a UNIT-ONLY Builder shadow at
Media3's public `CompositionPlayer.Builder.setVideoGraphFactory` seam. The local
graph supplies protocol notifications for deterministic UI/admission/seek/control
tests. It never draws pixels, verifies effects, executes GPU/codec/export work or
establishes visual fidelity. The actual CompositionPlayer, native source/admission
code and error/fallback listeners remain. Existing legacy EditorPreviewUiTest's
five cases retain their original fixture and source. Every assertion remains;
only the five test @Config declarations changed. Original/corrected sources and
hashes are recorded in ui-graph-fixture-correction-v1/Manifest.json. Diagnosis-only
test sources remain private work and must not be included as extra regression
cases or committed with the product.

Root re-executed the three isolation cases with the SAME corrected unit fixture
against frozen old Main2ddfe:3tests/3failures in4.353s. The failures now precisely
showed explicit keyframe Preview expecting1000ms but staying0, edited TITLE text
leaking over an external render, and lost external controls when a known edited
error blocked admission. Scoped production repairs mark actual keyframe previews
as pending seeks, retain external targets until admission succeeds, reapply their
controls when blocked, and suppress edited caption overlays on external targets.

Latest focused root GREEN:26tests/0failures in10.139s on retained FULL524 plus:

- MainActivity SHA6254639fba1aef9edf8886395cc0eb76a05333a178145d833eda47490363d489.
- StudioEditorWorkspace SHA46964427e567040743cb6ea2f9dcba250c6db620237d8183316b665ee922b5aa.
- StudioTracksView SHAcfef7d32607731a11be848f27e7f05e2d258f83800f5d1af56cd1681cfcc5291.

The26cases are21new native unit UI cases plus5existing preview cases. They are
engineering checks, not animation scenarios or rendered-frame evidence. This
standalone branch still starts523; root will integrate verified clock524 and
separate PNG525 without replacing either history. Combined full regression,
actual installed-APK screenshots/touch/preview/GPU execution, CI and original
signing remain pending. No new APK UI, decoded clip, artistic acceptance or
finished-film verification is claimed by these focused results.
