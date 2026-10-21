# Native numeric character construction guides — 4.10.6

Optional `viewYawDegrees` (-360..360) and `viewPitchDegrees` (-75..75)
apply only to `anatomy_sketch` human settings. Negative pitch looks down from
above. Both manual Natural acting controls and ordinary sketch edits use the
same native projection and validation. Explicit fields require native4.10.6;
omitted requests preserve prior named views, defaults, screen-normal lean,
request hashes and version floors.

Numeric cameras project a fixed world pose, including body lean and head tilt.
The camera changes the visible construction, rather than rotating the pose axis
along with the viewer. A full revolution normalizes to exactly the initial paths.
This is orthographic construction geometry. It does not recover a character's
unseen artwork, clothes, likeness or artistic acceptance, and has no automatic
orbit-animation field.

Evidence outside Git: `/workspace/work/native-character-guides-20261021`.
Four new native tests first failed3 cases (unknown settings and missing controls).
The original RED XML/logs remain. Focused combined46 Android tests passed with
0 failures/errors/skips. Node anatomy contract checks pass, comparing the actual
native capabilities JSON with Node. A diagnostic renderer optionally writes16
native projected PNGs using `VIDEOSTUDIO_CAMERA_GUIDE_EVIDENCE` and refuses to
overwrite evidence. These16 positions are one construction-view study, zero
accepted character actions. Native host graphics under Robolectric are distinct
from phone/GPU execution. Target-character artwork remains NEEDS_REWORK.

The integrated4.10.6 release also needs the separately committed cutout-pivot and
frame-source slices, combined regression validation and original signing checks.
Do not claim this slice alone is the complete app or four-minute film.
