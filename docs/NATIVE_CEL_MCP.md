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
