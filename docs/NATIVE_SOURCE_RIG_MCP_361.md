# VideoStudio 3.6.1 — Source-textured native rig authoring over private MCP

## Why this patch exists

The 3.6.0 procedural `animate_combat_rig` benchmark creates moving but
**generic** stickmen. It does not animate the original fighters in imported
storyboards. Version 3.6.0 already contains source-textured 2D mesh skinning
(`render_rigged_2d`), but the private ChatGPT control plane previously had no
action to author and inspect the requisite masks and 16-joint profiles.

This release exposes two bounded **native-only** actions, preserving project
ownership and the user's exact artwork.

## New owner-authenticated MCP actions

`save_source_rig_profile` with:

- `projectId`: a currently existing native VideoStudio project
- `sourceProjectUpdatedAt`: exactly `get_state.activeProjectUpdatedAt`
- `sourceAssetId`: an image asset already imported into that project
- `black`: boolean actor identifier (true for black, false for white)
- `transparentSource`: boolean, usually false for a full-frame illustration
- `joints`: exactly 16 `[x,y]` normalized coordinates in
  `Rig2DProfile.JOINTS` order
- `outline`: 3–64 normalized `[x,y]` points outlining the character,
  **not** the surrounding city or sword
- optional `rigId` for a stable, validated rig identity

The native service validates the existing project and asset, checks the current
project revision, converts the request with `NativeRigAuthoring.parse`, and
persists the `Rig2DProfile` inside the app's scoped
`creative_workspace/projects/<projectId>/rigs/` area. It does not accept
filesystem paths, enumerate Gallery media or download external images.

`list_source_rig_profiles` takes only `projectId` and returns the project's
persisted rig profiles for verification. Both actions use the durable MCP v3
control channel.

## Rendering genuine source-character motion

Once both profiles exist, invoke the pre-existing `render_rigged_2d` action
with `sourceProjectId`, `blackRigId`, `whiteRigId`,
`backgroundMode=source_median` (or a separately selected clean image),
`storyboardMotion=true`, `fps=24` or 30, bounded portrait dimensions,
`durationSeconds` between 2 and 6, and `render=true`.

This uses the actual imported 2D actor texture and skinned mesh animation
instead of displaying generic overlaid silhouettes. The user-selected masks
must be accurately traced. A flattened image does not contain true 3D
backside geometry or hidden limbs; this is **2D mesh skinning, not automatic
2D-to-3D neural animation**.

## Acceptance / safety

- Source images, source clips and existing projects remain unmodified.
- The app and stable owner credential remain unchanged across the APK upgrade.
- Source/project validation and rig polygon and bone validations are tested.
- Android Gradle compile, JUnit and APK CI must pass before installing.
- Real phone: save/list both source rigs, inspect masked previews, run a short
  native render, inspect the actual MP4 for limb/sword deformation, background
  ghosts, missing scene continuity, letterboxing, and incorrect rotations.
- Do not mistake a green APK build for visual-quality acceptance.
- All generation, preview and export remain native VideoStudio operations. No
  Android Gallery enumeration, paid inference API, or public media upload.
