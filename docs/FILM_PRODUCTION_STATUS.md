# Film production status — 2026-10-17

The owner's latest 2D benchmark replaces the sword-duel story: an original
traveller fights a dragon in a large jungle, uses wind/super-speed attacks, then
discovers that she is protecting her cub. Recognition becomes shame, apology and
withdrawal. The owner explicitly requested drawings authored in Sketch Studio
from imagination, without an image generator or imported artwork.

## Original jungle benchmark

`SketchJungleFilm` now authors a complete 120-second, 1280×720, 24 fps book:
22 shots, 2,148 editable cels and 2,880 exposed output frames. Quiet shots use
two-frame exposures; action and recognition/remorse passages use single-frame
exposures. These are procedurally authored Bezier drawings, not 2,148 separately
hand-drawn illustrations or an automatic general-purpose story-to-animation model.

The drawings include the original jungle, traveller, mother dragon and cub;
constructed limbs and grips; front/quarter/back and separately drawn profile and
overhead studies; action poses; a blade/claw macro shot; and recognition/shame
close-ups. Every cel retains ordinary editable native paths and layers. Native
component-library entries retain eight traveller turn/expression studies.

Full English and Hindi performances were generated with the real local Kokoro
CPU model through native request export and verified native audio import. The
two language pictures use their own recordings' amplitude timing for mouth
drawings. This is not phoneme-accurate lip sync or actor-directed emotional speech.
A native Java PCM composition provides original music, forest ambience, battle
effects, dragon/cub calls and a quieter resolution. No stock artwork/audio was used.

Workspace QA streams the app's frozen-book frame source through
`SketchFrameRenderer` / Android Canvas with native Skia. FFmpeg encodes those
native pixels and mixes/packages the WAVs, subtitles and MP4s. This does not
establish Android MediaCodec execution, phone playback or a deployed MCP update.
Private production receipts retain hashes, editable cels, voice/score clocks and
actual final MP4 probes. See [Original jungle film](ORIGINAL_JUNGLE_FILM.md).

## What the benchmark establishes

* The app can store, edit, freeze and render a full authored two-minute cel book.
* Original geometry can change anatomy, pose, perspective, expression and
  occlusion without warping a source bitmap. A drawing being editable or changing
  across frames does not establish its artistic correctness.
* The story, ten dialogue slots, music and effects can fit a complete two-minute
  output. Sampled native frames exposed staging and anatomy faults; corrections
  moved the cub away from the mother's face, aligned an impact with the blade tip,
  and joined the remorse hand to a drawn forearm.

## Quality and execution limits

| Area | Current evidence | Remaining limitation |
| --- | --- | --- |
| 2D drawing/acting | Complete original flat-vector draft, editable turn/acting studies, sampled native cels | Below the hand-drawn professional references; poses, weight, contact and nuanced acting need further art direction/redraws |
| In-betweens | Native geometry reconstructed at cel times, mixed single/double exposures | Some motion remains mechanical; more frames do not prove convincing animation |
| Dialogue | Real English/Hindi inference, actual native export/import, separate amplitude-timed mouth drawings | Pronunciation, performance and final audible listening review are unverified; no phoneme visemes |
| Sound/music | Original 120-second stereo PCM score/effects plus complete mixed outputs | Synthetic composition/sound design, not orchestral or actor-quality acceptance |
| Manual app workflow | Original-film menu, editable books/layers/components, dialogue preset and existing production controls | The private workspace orchestrator is not a one-button phone film exporter; manual audio assembly remains necessary |
| MCP workflow | Existing revision-bound book/cel/component/dialogue operations | No new jungle-specific MCP action, live server deployment or phone execution was verified |
| 3D first-love film | Earlier rigged-model and short cloud Blender diagnostics remain available | Separate 120-second two-character film is still outstanding |

No professional-quality certification or device approval is claimed. The draft is
the requested capability benchmark, not a declaration that the app can now
automatically animate arbitrary stories at the reference quality. Existing
source-bound cinematic action guards remain intact; this original vector film
does not bypass them or claim reconstruction of the supplied portrait.
