# Motion paths and custom timing curves

`effects.motionPath` is a compiled spatial cubic path used by the shared
`MotionTimeline` evaluator in preview and native export. It is not a preset label.

```json
{
  "version": 1,
  "mode": "replace",
  "orientToPath": true,
  "rotationOffsetDeg": 0,
  "points": [
    {"id": "start", "t": 0, "x": -0.6, "y": 0, "outX": -0.6, "outY": 0.6},
    {"id": "finish", "t": 1, "x": 0.6, "y": 0, "inX": 0.6, "inY": 0.6}
  ]
}
```

Coordinates use normalized GL program space: X = 2 moves one canvas width right,
and Y = 2 moves one canvas height up. They are not image UV coordinates. Point
times use the clip's normalized authored clock, including its signed animation
offset and original authored duration after split, trim, speed change or range
export. The first and last positions hold outside the authored point interval.

Each segment uses its left point's outgoing handle and right point's incoming
handle. Handles are absolute program coordinates and default to their anchor.
They must be supplied as X/Y pairs. Orientation follows the analytic tangent;
degenerate endpoint derivatives use a deterministic one-sided direction, and a
fully stationary segment uses the rotation offset. `add` adds path position and
orientation to the existing clip transform. `replace` replaces position and,
when orientation is enabled, rotation. The path is evaluated after user transform
keys and before edge transitions.

The parser requires 2–128 ordered points, unique stable IDs and strictly increasing
times within 0–1. Anchor and handle coordinates stay within −10 to 10, rotation
offset stays within −3600 to 3600 degrees, and serialized path metadata is bounded
to 64 KiB. Unknown fields, string booleans, unpaired handles and duplicate point
times are rejected.

`ProjectMotionPathEdits.set/clear` mutates a visual clip inside the existing
`ProjectStore.edit` transaction, enforcing track locks. The owner dialog edits a
draft using actual anchor/handle gestures, point timing, numeric coordinates and
mode/orientation controls; Save passes the captured project revision to the
transaction. Its geometry scrubber shows the actual spatial curve. Applying the
draft to the native monitor still requires Save.

Custom timing curves use `easing: "cubic_bezier"` and
`bezier: [x1, y1, x2, y2]` on individual transform/audio keys. Bone curves use their
own `ease` key with the same controls and helper. X controls must be 0–1; Y
controls may be −4 to 4, allowing overshoot. `CubicBezierEasing` solves the cubic's
X coordinate using guarded Newton iteration and bisection, then evaluates Y at
that parameter. Directly evaluating Y at the input progress would be incorrect.

Default transform timing uses `effects.ease` plus `effects.bezier`; default audio
timing uses `effects.audioEasing` plus `effects.audioBezier`; camera timing uses
`effects.animationSpec.easing` plus its `bezier`. Explicit custom keys carry their
own controls, while keys without an easing name inherit the compiled default.
Named timing removes obsolete control arrays. Camera presets use the camera
default; explicit clip motion presets use the visual default. User transform keys
and camera keys retain their own defaults independently.

Curves compile once when clip settings change. `EditorEasingControls` draws the
actual timing function and provides numeric and draggable control handles. The
same solver evaluates transform, audio and rig keys, with existing output-property
bounds applied after interpolation.

These implementations and future regression sources are unrun. No app build,
device preview or encoded animation output has been exercised in the current
implementation phase.
