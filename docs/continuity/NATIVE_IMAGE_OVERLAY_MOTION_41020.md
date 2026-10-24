# Native editor image-overlay motion

This candidate addresses the independently moving image-panel editing shown in
the supplied nine-video reference archive. References remain separate from
app-authored output; their footage is not our animation.

Existing set_studio_layers retains its normal atomic replacement, project
revision guard and imported-project-asset ownership. Optional image-only
motionKeyframes uses the existing StudioClipKeyframes grammar restricted to
timeMs, x, y, scale, scaleX, scaleY, rotate, opacity and easing. Local milliseconds
start at the image layer start, not project or source clip time. X/Y are
normalized canvas displacement; scale axes multiply static width/height, rotate
adds to static rotation, opacity multiplies static alpha. Pivot stays centered.
Each omitted key channel has its identity value, matching existing clip-key
semantics. Existing five outgoing easings and 64-key bound apply. Keys must
strictly increase within0..durationMs. Empty array explicitly clears motion;
omission retains the legacy serialization and feature floor. Explicit fields,
including empty arrays, require4.10.20. No duration extension or resampling.

Native renderer must sample microsecond presentation timestamps without early
rounding. Source image/cache stays unchanged; inactive image spans remain
half-open. Native preview/export composition uses the same timed-image overlay.
Manual controls must preserve the existing image recipe and use the same
revision-guarded project save/history as ordinary image-layer editing.

Verification will record actual RED/GREEN sources and outcomes outside Git.
Mathematical settings/Canvas tests are not hardware GPU/codec execution. This
capability is not a two-source dissolve, moving video-overlay lane, perspective
carousel, automatic beat detection, improved character drawing or completed film.

## Manual workflow and executable capability reporting

Editor > image overlay > Animate overlay opens a detached draft. Enter local
milliseconds and load a time to sample the shared native motion numerically.
Change normalized delta X/Y, axis scale multipliers, added rotation and opacity
multiplier; select outgoing easing, then Add/update key. Remove or clear draft
keys, then Apply animation once. Close discards the draft. The ordinary edited
video preview shows the applied recipe. The draft sampler is numeric, not an
unapplied GPU-rendered preview. Static image values use exact Float.toString
inputs so opening animation does not quantize the saved base to two decimals.

Manual commit captures project ID, revision and editing surface generation;
closed/destroyed/stale dialogs cannot apply. Existing native project CAS and
one normal project Undo/Redo retain the complete recipe. Shortening a layer
past a retained key rejects instead of silently resampling. Start-time movement
keeps layer-local keys unchanged.

The native and Node studioEditing capability registry uses the same JSON.
Executable effects/transitions are separate from old planning names. Existing
native/cached creatorCatalog retains its old lists but is explicitly labelled
executable:false with a pointer to studioEditing. The Node app_catalog similarly
retains legacy names only under a non-executable planning catalog. This does not
implement the unavailable effects or verify a live Worker/ChatGPT connection.

## Executed focused evidence and retained failures

Private evidence: /workspace/work/editing-nine-references-20261024 and
/workspace/work/film-production-20261022/editor-image-motion-*. No supplied video
or reference artwork is committed to Git.

* Genuine native core RED:14 tests,12 failures,2 passes in2.527s on full522.
  Original source SHA044c9e48bdd30b0a7a1ae32a6ae22f82a8d5622af0d8a72c131e88eca4190a72.
* Genuine initial UI RED:4 failures/4 in3.625s at missing Animate overlay.
  Initial source SHA7ac82801fcd57a3004f56e6559445ac038cff8a3b52e20f915af17d9cdd3c727.
* First21-case integration failed2 in5.475s: accepted .01 width/height promoted
  through float serialization below the strict external minimum, and a paused
  Robolectric onShow callback not delivered by the UI fixture. Failed sources,
  logs and exact launcher inputs are retained. No assertion was weakened.
* Separate genuine old-layer boundary RED:3 tests,1 failure in1.64s. Serialize
  exact.01 only for width/height==.01f; retain all other values and strict external
  bounds, including rejection of nextDown(.01) and the old promoted float value.
* Corrected UI fixture only idles the main looper after showing the manager;
  every assertion remains unchanged. This ensures Apply and rejection handlers
  actually execute. The corrected source still has4 genuine failures on full522.
* A31-case retake found one real precision failure: x.314159 became.31 merely
  by opening/applying motion. Only the six image-form field initialization
  strings changed; the global numeric helper and other editor controls did not.
* Broader focused retake passed119 native tests in9.476s on full522 retained
  runtime plus10 exact production class overrides, including all31 new cases
  and16 existing editor classes. This is focused verification, not full523 CI.
* Native capability RED3/3 and native planning-catalog RED1/1 were preserved.
  Node original8-case RED had7 failures; the same8 assertions then passed.
  Full Node819+306smoke passed before the final native catalog guard/metadata
  update; fresh final integrated results remain a separate required gate.

Full Android regression, exact-source CI, original signing and actual installed
APK/GPU/codec proof must be reported independently after execution. Source review
and CPU sampler/settings tests cannot establish those outcomes or reference-level
visual acceptance. Live private MCP is separately blocked by the existing403
owner-action-required response; no identity reset or access-control bypass.

Additional placement check: the original SDK33 Robolectric matrix shadow failed
one of two unchanged geometric tests. A separate dual-SDK diagnostic proved its
in-place multiply differs from disjoint output; actual Media3 repeatedly uses
in-place multiplication. SDK35 platform Java multiplication is alias-safe and
produces the original expected138/210-pixel rotated corner. Only this two-case
placement class's SDK annotation changed33 to35; no assertion, fixture geometry,
tolerance or production code changed. Original failed source, matrix dumps,
bytecode and diagnostic are retained. This is CPU placement evidence, not GPU.

The owner subsequently supplied two actual editing subjects in a separate ZIP;
the nine earlier clips remain quality references. Their originals and source
hashes are retained privately. Editing pre-existing live-action footage does not
create or verify its acting, drawings, unseen character views or original audio.
