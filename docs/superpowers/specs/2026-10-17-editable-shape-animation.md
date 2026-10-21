# Editable curve-shape animation

The owner asked to resume the reference-quality upgrade autonomously and set aside connection troubleshooting. Continue source work and native-renderer workspace verification; do not retry the blocked endpoint. Preserve the reserved portrait for a later character-validation round.

## Outcome

Allow an existing editable Bezier path to change its shape smoothly over time. This supports authored blinks, expressions and secondary hair/fabric motion without duplicating a whole drawing on every frame. It is an authoring capability, not inferred anatomy, generated acting or reference-quality certification.

## Contract

- Paths may carry optional `shapeKeys`, at most 240, with strictly increasing in-range `frame`, `commands`, `interpolation`, and optional Bezier `curve`. Commands must retain the base path's exact command sequence and coordinate count. Coordinates remain finite and normalized to 0–1.
- Reuse the existing seven timing modes and time-coordinate inversion. Before the first shape key, use the base path. At a key use its exact geometry; after the final key hold it. Interpolate control coordinates, preserving curved commands and authored topology.
- All keyed geometry counts toward the existing 131072-point, 4096-key and 4 MiB document limits. Existing artwork without shape keys behaves unchanged.
- `set_shape_key` uses the same atomic native revision-bound edit path as other operations. It sets/replaces or removes one key. Clearing animation uses `update_object.patch.shapeKeys: []`. Deep-copy incoming geometry and reject invalid complete documents before persistence.
- The native shape-animation dialog creates/removes keys and selects timing. Existing node drags sample the visible shape and edit a key when the selected object has shape animation; other node drags still edit base geometry. Selection, focus, live previews and raster previews must use the sampled geometry.
- Native preview and export share the renderer. Prepared-animation preview resolves shape geometry before rig deformation; baking retains shape keys for unbound/rigid paths and samples them before weighted deformation. Reject baking a duration that truncates retained shape keys rather than silently changing authored timing.
- Cel duplication freezes the sampled source shape; insertion/deletion shifts shape-key times. Consolidation must not merge animated shapes into static artwork.
- `sketch_get` retains command pagination. Return shape-key metadata without embedded geometry; optional `shapeFrame` samples one object's geometry before paging. Do not bypass command paging by returning every key's commands.
- The MCP schema carries exact shape data and requires native 4.6.1/code 471 for this contract. Identity and pinned signing guard remain unchanged.

## Verification

Exercise actual edit persistence, rendered intermediate geometry, cubic timing, topology rejection, node dragging, shape controls, paging, frame edits and prepared-animation preview/bake agreement. Render independently authored blink, hair/fabric and expressive-curve subjects, retaining editable sources. Inspect sampled output and report weaknesses. Workspace native graphics and encoded workspace previews are never phone-execution evidence.
