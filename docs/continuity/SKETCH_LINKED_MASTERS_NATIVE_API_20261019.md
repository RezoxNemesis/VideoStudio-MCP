# Sketch Studio linked Bezier master — first Phase 1 API
Base: VideoStudio 4.8.6 source branch. Requires this PR compiled and installed; does not exist in the currently connected 4.8.6 APK.

## Native v3 commands and semantics
Keep using ordinary `sketch_create` and atomic `sketch_edit` with exact `sketchId, expectedRevision, operationId, operations`.

```json
{
  "sketchId": "existing_sketch",
  "expectedRevision": 2,
  "operationId": "make_lantern_handle_master",
  "operations": [
    {"type":"master_create","masterId":"lantern_handle","sourceObjectId":"drawn_handle_path","name":"Lantern handle"}
  ]
}
```

Then position a second instance in any unlocked layer:

```json
{
  "sketchId":"existing_sketch",
  "expectedRevision":3,
  "operationId":"place_lantern_handle_2",
  "operations":[
    {"type":"master_instance","masterId":"lantern_handle","layerId":"ink",
     "objectId":"lantern_handle_shot_two","frame":40,"exposure":140,
     "transform":{"x":0.15,"y":0,"scaleX":1,"scaleY":1,"rotation":0,"anchorX":0.5,"anchorY":0.5,"opacity":1}}
  ]
}
```

Read-only impact preview (no `operationId` and no state change):

```json
{"sketchId":"existing_sketch","expectedRevision":4,"masterId":"lantern_handle",
 "patch":{"color":"#FF7F5544"}}
```
Call action `sketch_master_preview`. It returns linked object IDs, count of instance frame exposures, overridden copies and locked layers.

Edit master geometry+color using `sketch_edit`:
```json
{"sketchId":"existing_sketch","expectedRevision":4,"operationId":"reshape_all_handles",
 "operations":[{"type":"master_patch","masterId":"lantern_handle","expectedVersion":1,
 "patch":{"commands":[["M",0.2,0.3],["Q",0.5,0.1,0.7,0.3],["L",0.7,0.6]],"color":"#FFBB9966"}}]}
```
All linked objects receive the new commands in **one SQLite transaction**. Their local transforms, transform keys, frame ranges and override values remain untouched. The regular `sketch_undo` restores the prior state; reusing identical operation IDs returns the cached receipt rather than redrawing everything.

Per-instance appearance override:
```json
{"type":"master_override","objectId":"lantern_handle_shot_two",
 "patch":{"color":"#FFFFDDAA"}}
```
To return to the shared color:
```json
{"type":"master_clear_override","objectId":"lantern_handle_shot_two","field":"color"}
```
To leave future propagation and retain this exact editable path: `{"type":"master_detach","objectId":"lantern_handle_shot_two"}`.

## Constraints and explicit boundaries
- Only original Bezier path masters in this slice; no imported raster, paint, shape keys, palettes, human/hand procedural rigs or shared projects.
- Up to 64 masters per sketch, with the original 4 MiB/4096 object/131072 point/4096 key budgets still enforced.
- Locked linked-layer propagation fails atomically.
- Direct changes to linked geometry must use `master_patch`, or `master_detach`; direct `update_object` appearance changes are rejected.
- A saved legacy `sketch_component_save` is an immutable copied snapshot: links are explicitly removed during snapshotting.
- A new Sketch Studio menu item **Linked master artwork** supports create, place, impact preview, change master color, local color override and detach.
- User can still move/animate individual copies with ordinary `set_transform`, `set_keyframe`, `set_exposure`.
- Gallery remains disabled, and this feature does not generate original high-quality artwork automatically.
- Verify GitHub CI and actual native device execution before calling this production-ready.
