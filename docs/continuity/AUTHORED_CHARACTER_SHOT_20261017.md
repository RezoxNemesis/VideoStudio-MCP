# Authored character shot — 17 October 2026

The owner rejected the previous vector previews. The accepted next milestone is
one finished character shot judged by artwork and performance. The automatic
story generator remains a predefined vector/pose system; this work does not
establish an upgrade to automatic artwork creation or image-to-character motion.

## Production approach

Use the existing authored full-scene Blender/GLB path with a licensed artist-built
character. The study uses pixiv Inc.'s `VRM1_Constraint_Twist_Sample` v1.0.1 from
the VRM Consortium's public sample repository. Original SHA-256:
`12c2b97e95e700783a6a550dc0eee2d7880aeedccef9ae67bc4c5a2f0f2631a2`.
Its embedded VRM Public License 1.0 settings allow modification and redistribution.
The source attribution, settings and modification notice remain in the compiled
GLB. Private references and the owner's reserved portrait are not included.

The shot is an explicitly authored, non-action performance: attention shift,
head turn, two asymmetric-timed blinks, a small smile, restrained torso movement
and delayed hair follow-through. It has an authored conservatory environment,
PBR materials, camera and lighting. Shoulder helper poses were authored to keep
the sleeves connected; this is not a general VRM-constraint importer. Hair
follow-through is authored motion, without collision-simulation acceptance.

The planned output is 108 distinct frames at 24 fps, 720×960. Cloud Cycles rendering
and external CPU Open Image Denoise are explicitly separate from Android output.
Native Filament depth of field, grading, denoising and final visual parity are
unverified. The original signing key remains unavailable, and no phone update or
private MCP retry is part of this milestone.

## Source changes: 4.6.2 / code 472

- Optional scene `cameraReplay: "authored"` preserves the GLB camera's sampled
  transform and projection. Horizontal coverage adapts to the output aspect;
  vertical framing, offsets and depth remain. Omission or `timeline` preserves
  existing editable timeline behavior.
- The native importer and cloud packager reject unknown/null camera policies.
- Saving a changed timeline camera on an authored-camera scene rejects with
  `BAKED_CAMERA_REBUILD_REQUIRED` before publication. Human controls explain the
  restriction and disable those camera fields.
- Explicit non-action `IDLE` scenes admit one weighted skin. Existing action
  scenes retain their minimum of two. Skin count is not actor-count or artistic
  acceptance evidence.
- Capability metadata advertises both additions and their minimum native version.
  AppProtocol and Android version metadata agree.

The original application ID, signing guard, durable MCP identity and user data
contracts remain intact. No trained model, arbitrary third-party script, private
credential or reference image is added to product source.

## Verification scope

Focused regressions exercise camera-policy propagation, projection resizing,
legacy behavior, invalid policies, single-skin non-action admission, immutable
archive import, refused ignored camera edits and the reachable camera-key UI.
The cloud packager has matching policy tests.

`Cinematic3DAuthoredBundleProofTest` is opt-in using an explicit
`VIDEOSTUDIO_AUTHORED_BUNDLE` path. It imports the real separately authored archive,
decodes its embedded textures through native Skia, saves it through the native
program store and produces the 108-frame plan. It expressly leaves phone GPU,
native execution and cinematic approval false. The initial legacy-graphics test
could not identify the real PNG textures; native Skia successfully imported the
same archive without weakening product resource checks.

Independent Blender reimport comparison sampled seven original/compiled poses.
Maximum nearest-vertex differences were about 1.2 micrometres for the face and
1.9 micrometres for hair; camera matrix error was below 4e-7. The body showed a
3.5 mm maximum difference whose cause remains unestablished. These sampled
measurements do not establish complete geometry identity or phone visual parity.
The exporter initially lost the blouse tint; its ordinary PBR base-color factor
was corrected before packaging.

Final source verification: 1207 native cases across 239 suites, with 1205 passed,
zero failures/errors and two optional legacy proof skips. The opt-in real authored
bundle import ran and passed in this suite. The focused four-suite run passed all
46 cases; all nine Python packaging tests passed. `git diff --check` passed.
No JavaScript runtime behavior changed, so the previously passing Node suite was
not rerun for this source change.

Full-render inspection and output hashes are recorded in the separate delivery
evidence after rendering finishes. Artistic acceptance remains open to the owner;
successful import and encoding do not pass it.
