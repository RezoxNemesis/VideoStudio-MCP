# Source-assisted cutout pivots and retained preview — 4.10.6

This slice extends frozen 4.10.5/code508 source
8e5c3625506088c839fd61b1eec81fbf1affc966 in an isolated feature worktree.
It preserves the existing package, signing identity, source artwork and prior work.
The integrated root selects the next version and owns all Gradle/CI/signing work.
The user's latest instruction explicitly authorizes parallel agents; one local
Gradle process remains owned by root.

## Product behavior

Existing `sketch_reference_layers_preview` and `sketch_import_reference_layers`
accept optional per-mask `pivot:[x,y]`. Values are strict finite normalized 0..1
coordinates in the full EXIF-oriented working source. The native operation maps
them through the untransformed source bounds into ordinary canvas transform
anchors. It neither crops/replaces source artwork nor infers anatomical joints.
Omission preserves the historical canvas-center pivot. Source transformations,
keys/crops and other unsupported separation inputs still reject.

Optional strict `allowPartOverlap` allows deliberate shared source pixels at
visible joints. Omitted/false still rejects overlapping nontransparent source
pixels. Null/coercion reject. Explicit true or false binds into sourceMask v1 and
its canonical mask hash; old omitted provenance hashes remain unchanged.
`sharedSourcePixelCount` counts distinct shared nontransparent working-source
pixels and is persisted in the same native transaction receipt for replay.
An actual shared-pixel result rejects optional v1 background complement: its
existing even-odd ring representation cannot represent overlapping union masks.
No silent geometry conversion or hidden artwork is generated. Translucent shared
pixels can darken when stacked. This is not an automatic seamless-joint claim.

Preview and import use the same source-bound accepted rings/settings. The new
`retainedPreviewImage` is an actual native transparent PNG of the native ordered source-over stack of named
retained parts without optional background, bounded to360px/1MiB. Masked-away
pixels are cleared before bounded filtering; source RGBA inside masks remains
native source-assisted artwork. The older source/selection overlay remains a
separate `sketchPreviewImage`. Native manual preview checks hashes/MIME/size and
dimensions before decode, and shows Dark/Light/checkerboard, zoom/pan/fit through
an existing native viewport. Backdrop changes do not alter preview acceptance.

Manual: Sketch Studio > Separate reference into parts. Select/import an owned
reference, author named polygon masks, enter source-pixel Pivot X/Y and Set part
pivot or enable Tap image to set pivot. Each source/part retains its committed
pivot. Clear uses the canvas-center pivot. Meaningful edits invalidate prior
preview. Enable Allow shared pixels at joints only when deliberate. Preview,
inspect actual transparent art over light/dark backgrounds, then Create layers.
Normal transform keys, Undo/Redo, component reuse and editable archives remain
ordinary app editing rather than a second authoring engine.

MCP: call app_status and read sketch_capabilities.referenceLayers. Explicit
pivots/overlap flags (including false and inline sourceMask metadata) require
native4.10.6. Omission retains earlier action/feature floors and fingerprints.
Both result images use the existing image response channel. No canonical action
was added. Contract/source parity does not assert live relay deployment or phone
installation.

## Validation and evidence

RED was observed by root, logs preserved outside Git at
/workspace/work/character-cutouts-20261021/red:
- Core pivot/transparent preview:4 tests,2 failures (missing pivot/retained image).
- Manual controls:3 tests,3 failures (missing pivot/backdrop/tap controls).
- Shared-pixel option:2 tests,2 failures (unknown allowPartOverlap).
- Node optional pivot contract rejected as unknown before schema implementation.

The first integrated focused attempt had a source-loader javac error from an
incorrect retained-preview variable scope; its log is preserved as
green-focused.log. It ran no tests. The source limit was restored to2048 and
retained preview decode bounded independently. This failure is not a passing run.
The next focused attempt ran48 tests and5 failures; all preview failures were
caused by rewriting an immutable Bitmap created from a color array. The original
JUnit XML/log is retained in failed-green2. The native preview now allocates the
same single full-source bitmap mutable, fills it once, then clears excluded rows
before scaling. Assertions were not weakened.

Fresh Node reference contract:16 tests PASS,0 fail (node-green.log). Affected
queue/media/native transport and workbench regression:97 tests PASS,0 fail
(node-transport-regression.log). Root ran the final focused native core/dialog/reference/visual suite:
48 tests PASS,0 failures,0 errors,0 skips (green-focused3.log and green-junit).
Fresh integrated regression/CI is still the root agent's next step.
No local Gradle was run by this agent. Final root evidence must supply actual
native/CI results rather than substitute historical counts.

## Actual actor study

A private untracked source-specific study driver uses the private
VIDEOSTUDIO_OWNER_DRAWING_ASSET and a new VIDEOSTUDIO_OWNER_DRAWING_EVIDENCE folder.
It refuses to overwrite a previous generation, verifies the expected source hash,
and performs all import/selection/authoring/render through VideoStudio Java
surfaces. It creates seven source-assisted parts for the foreground young man only:
torso/lower robe, both visible sleeves, both closed hands, head/hair plus a fixed source-visible hanging-hair underlay.
The older man/Temple remain separate. It renders96 native frames at24FPS of one
four-second rigid planar head/cuff study, actual light/dark head/wrist closeups,
source hash checks, Undo and editable archive restoration. It logs literal native
requests and receipts. An optional FFmpeg review package may encode unchanged
native frames; that is not Android codec execution.

The supplied source identity, literal coordinates, driver and all derived actor
artwork stay in private evidence outside the public repository.
The larger latest inline composition has no separately available file bytes here.
White garment pixels/shading are intentionally retained. Polygon selection is
not paper removal. This baseline has paint deformation, not imageMesh, so these
are rigid image transforms. No feet/rear/hidden anatomy, new camera views, finger
articulation, independently drawn original artwork or mastered360° is claimed.
The first native actor generation passed technical execution but failed visual
review: a large neck/shoulder gap, unwanted sword strip and cut cuff fabric.
Native original-source head/cuff magnified previews then guided the second
generation. Cuff shape improved, but its head still failed visible joint seams.
Both generations and explicit Visual-Review.json failures remain preserved.
The third private source-mask revision closes the large open joins and retains
the folded cuffs; root inspected actual head/wrist crops and native editor
screenshots. Remaining fringe/possible duplicate hand lines keep it NEEDS_REWORK.
No technical check accepts its art. Product synthetic regression is independent of art review.

Visual/contact quality must be reviewed from actual native outputs;
source/clock/hash/archive tests alone cannot accept it. One study's96frames are
one scenario, not96different actions. The240-second film remains unfinished.


A follow-up exact alpha-compositing regression was observed RED: a shared pixel
from two128-alpha source parts rendered192-alpha after import but only128-alpha
in the original union preview. The failure/log remains private in
alpha-preview-red-junit.xml and alpha-preview-red.log. The repair now clears and
draws each named part sequentially through native Canvas source-over into the
360px transparent output, using one reused mutable full-source bitmap, row-sized
scratch and per-row cancellation checks. It skips optional background complement.
This shows actual shared alpha stacking instead of a raw source-pixel union.
Fresh exact unchanged regression plus full affected reference suite:49 tests
PASS,0 failures,0 errors,0 skips (alpha-preview-green.log and
alpha-preview-green-junit). Node reference contract16/16 PASS again after this
repair. The root agent owns final integrated regression and CI/signing.
