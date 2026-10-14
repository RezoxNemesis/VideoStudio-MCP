# Video studio 4.3.0 authoring tools

This release adds authoring tools to the existing native studios. Human controls and agent commands share validated operations and the native preview/export pipeline. It retains the original package, signing certificate, device identity, projects and previous features.

## Editing motion

Open a clip's **Keyframes** tool to browse ordered keys, load an exact edited time, inspect the sampled transform, navigate previous/next keys, preview the chosen time and add/update/delete keys. The manager remains open after a successful save. Clear-all asks explicitly because it also removes retained keys outside a split clip.

Older normalized motion opens through the existing compatibility reader. Incremental edits preserve the original duration/offset retained by split clips and preserve keys outside the selected part. `precision_edit` exposes the same operation:

```json
{"projectId":"owned-project","expectedUpdatedAt":1,"operations":[{"clipId":"stable-clip","keyframeEdits":[{"action":"upsert","keyframe":{"timeMs":250,"x":0.2,"easing":"ease_in_out"}},{"action":"delete","timeMs":500}]}]}
```

Times are clip-local edited milliseconds. Upserts merge existing keys; a new partial key samples unspecified transform/opacity values. Replacement and clear retain their earlier semantics and cannot be mixed with an incremental patch.

## 3D camera paths

Open **Camera keys** for a cinematic shot. Author camera position, target and up at phases 0..1, replace/remove a key or clear the path. The timeline samples these paths in native preview and export. This edits camera data and does not require rebuilding unchanged geometry. Camera operations run through the studio worker, reject stale drafts and preflight actual output-frame bases, including interpolation and impact shake.

The agent action `cinematic_3d_edit_program` requires project ID, source project revision, program ID and its exact SHA-256. Its bounded operations are `upsert_camera_key`, `remove_camera_key` and `clear_camera_keys`. Successful edits publish through the existing immutable-generation and compare-and-swap guards. Character reconstruction and missing source rigs remain separate work.

## Drawing symmetry

Sketch Workspace symmetry offers vertical, horizontal, both axes and radial two through eight sectors around a chosen center. Touch guides and previews agree with the committed vector geometry. Every generated copy remains an editable ordinary stroke; the gesture commits and undoes atomically.

Agent `sketch_edit` accepts:

```json
{"type":"symmetry_stroke","layerId":"layer_1","object":{"id":"petal","kind":"stroke","points":[[0.5,0.5,1],[0.6,0.5,0.8]]},"symmetry":{"mode":"radial","segments":6,"centerX":0.5,"centerY":0.5}}
```

Centers default to 0.5. Radial segments are required only for radial mode. Rotation uses canvas pixels so rectangular canvases preserve angles. Out-of-canvas copies reject without partial mutation or clamping. This operation accepts authored vector strokes/erasers, not reference insertion, clone paint or copied image pixels. Existing paint tools remain available separately. Live hand replay receives detached snapshots of the expanded ordinary strokes.

## Audio and captions

**Clip Volume & Fades** adds stereo balance and folding the left/right pair to dual mono. Balance attenuates the opposite side and leaves center gain unchanged. Mono averaging happens before balance and the existing fade/volume envelope. Mono sources keep their original single channel; additional surround channels keep their positions. This is not a whole-surround downmix. Agent fields are `audioPan` in [-1,1] and boolean `audioMono` within `precision_edit.set`.

Timed captions add font family, size, weight/italic, alignment, text colour and background colour. `set_studio_layers` exposes `fontSize` (integer 12..96 DIP), `fontFamily`, `fontStyle`, `textAlign` and signed/unsigned ARGB `backgroundColor` on title layers only. The form preserves invalid or revision-rejected edits. Legacy multiline captions retain leading alignment; explicitly selected center/right alignment remains available.

## Connection and evidence boundaries

All new fields/actions require a native 4.3.0 executor. Check `app_status` first. [The app-key connection](VIDEO_STUDIO_APP_KEY.md) can deliver these operations through encrypted backup commands without waiting for production tool discovery to change. Delivery and queue admission remain distinct from authenticated terminal execution.

The primary Cloudflare deployment is still blocked by unavailable existing account/token bindings. Source and local MCP/SQLite checks do not establish that these backend changes are deployed. Tests and signed artifacts also do not establish phone GPU/audio performance, notification delivery, exact artistic likeness or cinematic acceptance. Release build and public artifact evidence are recorded after verification.

## Verified release artifact

[Download Video studio 4.3.0](https://github.com/RezoxNemesis/VideoStudio-MCP/releases/download/videostudio-v4.3.0-preview/Video-studio-4.3.0.apk). Install over the existing app without uninstalling or clearing data.

All **960 Android tests** across 186 suites passed, with no failures/errors/skips. The Node suite passed **229 tests plus 306 smoke checks**. [Signed build 38540534006](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/38540534006) compiled source `2a8dd6a6729b4ea8559f42154ac58aa8bd2edd9f`; [publication 38541045823](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/38541045823) reused that artifact without rebuilding.

The independently downloaded public APK exactly matched the signed artifact's 129,828,253 bytes, original signing certificate, SHA-256 and CI proof. APK SHA-256: `a9d426da6b8dac13b5fe722e569b3562c14f30ce65f7d0599e3a064ea1157312`.

Installed native 4.3.0 execution remains unverified. Backend deployment remains blocked as described above; local SDK evidence is not production activation or phone execution.
