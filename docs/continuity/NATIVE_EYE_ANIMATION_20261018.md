# Focused eye animation — 4.7.5 source

The owner's priority is sequential, isolated animation studies, with the app
improved as shortcomings appear. Eyes come first. Mouth/tongue/chewing/speaking,
other anatomy and wider objects, creatures, scenes and viewpoints are subsequent
separate studies. Earlier primitive studies and unfinished film goals remain.

## Retained capability

The previous app provided general vector curves/timing and eye drawings specific
to film examples, but no shared eye controller. `SketchEyeAnimation` now authors
original eyes directly inside Android. Native path intersections keep irises,
pupils and glints inside the actual lid opening; blinking occludes a round pupil
instead of flattening it. Almond/creature blinks preserve eye corners and assign
80% of lid closure travel to the upper lid. Cartoon eyes retain symmetric lids.
Closure is faster than reopening; presets include holds, a double blink and wink.

The first export exposed an unattractive flat grey shadow strip. A curved native
upper-lid shadow replaces it. A further anatomical regression reproduced the
old symmetric almond blink before the upper-lid lead was implemented. Earlier
native generations are preserved under the private evidence root.

Gaze X/Y, independent left/right lid opening, pupil dilation, near focus, eye
placement/dimensions/spacing, pair tilt, iris size/colour, pupil size, reflections
and contour width are controllable. Shapes are round, almond and creature slit
pupils. The renderer still consumes ordinary editable path cels, gradients and
exposures. No new media type, external asset, model or runtime dependency is used.
Clipped contours are approximated to 0.35 source pixels, with normalized output
coordinates rounded to six decimals. Existing object/point/byte budgets apply;
oversized input is rejected atomically without reducing resolution or FPS.

## Manual use

Create a 1280×720, 120-frame, 30-FPS sketch. Open **Brush / Text → Eye animation**.
Choose anatomy and a motion preset, then adjust geometry and colours. Select a
pose, move the gaze/lid/dilation/focus sliders, or add/select/delete a key by its
one-based frame number. The starting key is retained. **Preview pose** uses the
same native authoring and rasterizer in a background worker; it does not write to
the drawing or history. **Create eyes** saves a new editable layer. All generated
curves remain editable in the ordinary sketch tools, with undo/archive/export.

## Ordinary ChatGPT / MCP interface

Use `app_status` first when a real VideoStudio connector is available. Read
`sketch_capabilities.eyeAnimation` and require APK/server support for 4.7.5.
Create a fresh sketch or use a new layer and unused eye identity on existing
artwork. Send this operation through `sketch_edit` / `studio_sketch_edit` with the
actual current revision and a stable operation ID:

```json
{
  "type": "add_eye_animation",
  "layerId": "layer_1",
  "eyeId": "original_eyes",
  "settings": {"style": "almond", "irisColor": "#FF7E694A"},
  "preset": "look_blink"
}
```

Other presets are `tracking` and `double_blink`; styles are `round`, `almond` and
`slit`. A small structured request is expanded by the installed app, so ChatGPT
does not need a workspace to calculate or draw the frames. The new strict worker
schema and pre-admission version gate are included in source. This is not a claim
that the current deployed worker or a named ChatGPT session has executed it.

Optional `poses` supply 1..240 keys, starting at zero in strictly increasing
frame order. Channels: `gazeX/gazeY` -1..1; `leftOpen/rightOpen` 0..1;
`pupilScale` 0.5..1.5; `vergence` 0..0.5. Omitted channels inherit prior values.
Existing linear/hold/smooth/ease/Bezier timing applies. Manual frame numbers are
one based; API frames are zero based. Documents must contain 24..240 frames;
requested FPS and frame count are preserved. Settings use normalized canvas
dimensions; choose height relative to the actual canvas for portrait layouts.
Irises and pupils are constructed as pixel-space circles before clipping, so a
portrait canvas does not stretch their anatomy.

Operations are admitted through the existing native edit/revision/receipt path.
Retries reuse their receipt. Existing artwork, locked layers, duplicate identities,
bad pose order, invalid geometry and drawing budgets retain the normal protection.
No Gallery access or reference-video tracing is involved.

## Focused evidence and acceptance boundary

Private evidence: `/workspace/engineering-review/eye-animation-20261018`.
Three separate original 4-second, 1280×720 / 30-FPS studies are round look/blink,
brown almond target tracking, and tilted gold creature eyes with double blink/wink.
Each uses native `SketchNativeExport` PNG preparation and editable archive
restoration; nine selected frames per study are compared after recovery.
The native manual pose preview and actual SketchStudioActivity screenshot are
also recorded. FFmpeg may package unchanged native frames into silent review MP4s;
this does not prove Android Media3 video encoding or speech.

Regressions measure pupil roundness under partial lids, clipped iris containment,
full closure, upper/lower lid travel, 30/60 FPS timing, full 240-frame capacity,
portrait drawing preservation, atomic failure/undo/retry, actual manual menu and
pose editing, and geometry parity between manual and native agent requests.
MCP schema checks reject malformed poses/settings and APKs older than 4.7.5.

Fresh local verification: 1,307 Android tests, zero failures/errors, three optional
proof skips; 306 smoke checks and 284 Node tests passed; Worker dry-run bundling
passed without deployment. Runtime Cloudflare account/token bindings were absent
when checked (presence only, no credential values read). Live server rollout is
not established by these source/schema/native-facade tests.

Candidate [CI run 39130234737](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39130234737)
passed for application source `3e65a9b2ee448e1c112393e25f34faebec49077b`:
1,307 Android tests, zero failures/errors, two optional proof skips. Version
4.7.5/code478 was built using the retained original signing cache. Independent
download verification matched source proof, package, version, pinned certificate
and SHA-256 `fd2bb46c6a113847754a6ac9fb0e6e87e45711bf90e6a758fe82193775638fa4`.
The downloaded DEX also contains the new eye operation and manual preview controls;
the functional evidence comes from the native tests, not those strings alone.

The candidate is private at
`candidate-4.7.5/VideoStudio-4.7.5-Eye-Animation-Candidate.apk` below the evidence
root. Final generation `v5` contains all 360 native PNGs, three separate review
MP4s, editable archives and actual manual editor/preview captures. Pixel hashes
include intentional gaze/blink holds: 37, 102 and 102 distinct rendered frames in
the three 120-frame studies. Held frames are not unique drawings. No merge,
stable release, Worker deployment or phone installation was performed.

Technical checks are separate from artistic judgment. These are stylized isolated
eye studies, not a finished facial performance, optical 3D eyeballs or a finished
character film. Phone installation/playback and live ChatGPT/MCP execution need
their own evidence. Do not deploy this worker until the retained legacy queued-
payload hydration compatibility issue is verified. Preserve the original signer,
package and device identity; no merge or stable release is implied.

## Reproduce

Use the existing checkout and toolchain, one Gradle process at a time:

```sh
python /workspace/onboarding/videostudio-gradle.py \
  -p /workspace/VideoStudio-MCP/android --offline :app:testDebugUnitTest \
  --tests '*SketchEyeAnimationTest' --tests '*SketchEyeAnimationUiTest' --console=plain
```

Set `VIDEOSTUDIO_EYE_EVIDENCE` to a new owner-private generation directory for
visual retests. Preserve earlier outputs. Run `npm test` for server contracts and
the full Android suite before packaging. Build candidates only through the
existing original-key GitHub workflow and verify package/version/source/signature
and artifact hashes before reporting the APK. Keep eye acceptance and the next
mouth study distinct; do not silently expand into unproven full-body films.
