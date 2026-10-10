# Native drawing and exposure MCP contract

`app_cel_create` and `app_cel_update` invoke the same `AnimationCelFactory`
used by the owner's drawing canvas. They rasterize actual paint/eraser strokes,
verify an immutable PNG generation, and publish its owned asset and exposure
through the exact accepted project revision. A redraw can change its hold in the
same transaction using optional `exposure` settings. Other exposures and undo
history keep the preceding immutable image.

Drawing JSON uses `version: 1`, dimensions from 16 to 2048 pixels, an optional
`#AARRGGBB` background, and explicit strokes. Each stroke has a stable `id`,
`type: paint | erase`, brush width from .001 to .2, optional color, and normalized
`x`/`y` points with optional pressure from .1 to 1.5. Erasing clears the foreground
without removing the chosen background. Limits are 128 KiB, 512 strokes and 8192
points per drawing. No caller media locator, inferred drawing or external model
execution is accepted.

## Editing existing cel vectors

`app_cel_edit_strokes` edits an existing cel using its actual `projectId`,
`clipId`, explicit `expectedRevision`, and an `actions` array. It uses the shared
`AnimationCelVectorEdits` helper and `AnimationCelFactory.editStrokes`; the
owner's drawing dialog uses the same pure helper for local vector edits.
The native factory checks committed request replay before reading or changing
the current document, then publishes a new immutable PNG at the accepted
revision. Track locks and normal project undo still apply. No caller-supplied
drawing, asset locator or replacement stroke identity is accepted by this action.
The queued plan retains the exact batch, clip, revision and trusted request key.
An uncommitted stale revision rejects before rendering; committed retries reuse
the verified PNG rather than applying relative edits again. Deleted or redrawn
exposures are not recreated, and vector edits do not change exposure timing.

Each action has an `op` and an existing stable `strokeId` from the editable
document returned by `app_cel_describe`. Unknown fields, IDs, operations and
unavailable point indices reject the whole batch. Batches contain 1–64 actions
and their normalized JSON is bounded to 32 KiB. Numeric fields must be finite
numbers; point indices must be exact integer numbers.

| `op` | Required fields besides `op` and `strokeId` | Optional fields |
| --- | --- | --- |
| `set_stroke` | At least one of `color`, `width` | The other property |
| `move_point` | `pointIndex`, `x`, `y` | `pressure` |
| `translate_stroke` | `dx`, `dy` | None |
| `delete_stroke` | None | None |
| `insert_point` | `pointIndex`, `x`, `y` | `pressure` |
| `remove_point` | `pointIndex` | None |

Coordinates use normalized `x`/`y` values from 0 to 1, pressure is .1–1.5,
width is .001–.2 of the shorter frame edge, and color is `#RRGGBB` or
`#AARRGGBB`. Moving a point preserves its pressure when omitted; insertion
defaults pressure to 1. Translation offsets are from -1 to 1 and every resulting
point must remain within the frame; points are never clamped individually.

Actions execute in array order. A point index therefore refers to the stroke
after all preceding actions in the same batch. Insertion accepts indices from
0 through the current point count, including append. Moving or removing requires
an existing point. Removal must retain at least one point; use `delete_stroke`
to remove the last point and its stroke together. Insertions cannot exceed the
8192-point drawing budget, so remove before inserting when the drawing is full.

`validateActions(JSONArray)` provides detached schema-only admission validation.
`apply(JSONObject drawing, JSONArray actions)` returns a detached complete
drawing, normalized with the existing `AnimationCelFactory.validateDrawing` API.
The single-action convenience overload accepts `apply(drawing, op, settings)`
with `op` omitted from `settings`. Background, canvas dimensions, stroke types,
stroke order, unrelated strokes and untouched point properties are preserved in
normalized form. Failed batches never mutate supplied documents or action
arrays. A completely unchanged normalized result is refused before rasterization;
an authored vector change can still be valid when it has no visible pixel effect.

`app_cel_describe` returns the actual editable document and exposure metadata.
`app_cel_exposure_add`, `hold`, `extend`, `duplicate`, and `delete` use the shared
native exposure helper and undo history. Cumulative frame boundaries are rounded
once to the existing millisecond editing timeline; native export uses precise
exposure timestamps when the exposure remains on its frame grid. Generic trims
or speed changes can take a clip off that grid, which is reported explicitly.

Actual project cadence supports integer 12, 24, 30 or 60 fps. Fractional rates are
refused. `app_animation_frame_rate` changes the stored project cadence, and an
optional export `frameRate` must match its accepted immutable graph. Publication
proof binds cadence, graph revision, output profile and explicit range bounds.
The renderer checks encoded video sample timestamps; this source implementation
has not been exercised on a device or built during the deferred testing phase.

Native cel exposure metadata, rigs, spatial paths, markers, rehearsal ranges and
cadence remain native authoring state during metadata-mirror application. Mirror
operations cannot manufacture, delete or rebind existing opaque animation state;
use the corresponding shared native tools.

Worker command storage keeps the existing v3 endpoint and public JSON commands.
Its private storage uses atomic checksummed 64 KiB chunks, a 4 MiB queue budget,
bounded request/result budgets, and up to 64 newly accepted active commands.
Legacy active overflow remains retained for draining. Only terminal history may
be pruned, with compact canonical result receipts retained for replay. Missing
chunks, inconsistent migration state or corrupt proof requires explicit recovery.
Attachment transfer remains a separate streaming private relay.

All validation here is source review and authored future regression coverage.
No tests, build, deployment or live ChatGPT/native verification was run.
