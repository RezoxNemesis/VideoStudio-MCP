# Beneath the canopy — original Sketch Studio benchmark

The story is authored from the owner's jungle/dragon/cub brief. No image generator,
reference pixels, Gallery access or imported artwork participates in this film.
It is an original, stylized vector draft with procedural cel authoring, not a
claim of professional hand-drawn anime or general-purpose autonomous drawing.

## Picture and staging

The book is 1280×720, 24 fps, 120 seconds, with 2,148 editable cels exposing 2,880
frames. Quiet shots use two-frame holds; action and recognition/remorse passages
use single-frame exposures. Each document contains ordinary native paths/text
across eight layers: background, ground, background effects, dragon, hero,
foreground, effects, titles. No image objects, paint references or raster character
meshes are used. Geometry is reconstructed at cel times; clothing, limbs, wings,
head poses and facial features have authored changes. Similar drawings and the
jungle layout are reused. These are not 2,148 separately hand-drawn illustrations.

| Seconds | Shot / dramatic purpose |
| --- | --- |
| 0–6 | Establish the huge jungle canopy |
| 6–11 | The traveller follows claw marks |
| 11–17 | Reveal the mother dragon |
| 17–21 | The traveller's challenge, close-up |
| 21–26 | Dragon claw, dodge |
| 26–30 | Run across roots |
| 30–35 | Sword/claw exchange, macro contact shot |
| 35–39 | Dragon breath, evasive slide |
| 39–44 | Wind-step preparation |
| 44–49 | Three super-speed passes |
| 49–53 | Aerial turn, separately drawn overhead view |
| 53–57 | Tail sweep and landing |
| 57–62 | Skybreaker special attack |
| 62–66 | The dragon shields something |
| 66–71 | The cub's cry interrupts the attack |
| 71–78 | Reveal the cub |
| 78–84 | Mother and cub nuzzle |
| 84–90 | Recognition close-up |
| 90–97 | Shame and self-disgust |
| 97–104 | Kneeling apology, sword set down |
| 104–111 | Give mother and cub space |
| 111–120 | Retreat and quiet resolution |

`JungleCelInk` creates the editable curves. `JungleFilmHero`,
`JungleFilmDragon` and `JungleFilmScenery` contain the original authored geometry.
`SketchJungleFilm` assembles shots, exposure timing, scripts and construction
studies. It uses native stores/import receipts so resuming a saved range preserves
existing human cel edits. A range is limited to 64 cels; a complete-film request is
not stuffed into one MCP edit message.

The construction document and component library contain front, quarter, profile,
back and overhead studies plus anger, recognition and shame. These are editable
geometry samples. They are not a learned anatomy/rotation solver or a complete
character library for arbitrary faces, costumes, styles and genders.

## App entry points

In the source candidate, **Sketch Studio → File → Original jungle film** creates
or resumes a separate retained book in bounded batches. It saves the construction
sheet/library, opens the first saved page and reports progress. Existing sketches
are preserved. Cels remain available through Sketch books, the navigator, layers,
onion skin and the component library. Picture export uses the existing book export.
An already opened future page is retained, including a blank page or human artwork;
it does not block creation of later cels. This deliberately preserves the user's
page rather than overwriting it with the template. Resume also retains edited
book timing, and reports the resulting clock rather than assuming 120 seconds.

In the production workbench dialogue form, select Hindi or English and choose
**Use jungle film dialogue**. This fills the ten authored lines and timing slots;
generation still uses the existing configured runner/export/import workflow.
It does not imply that Kokoro runs on the phone. Existing book/cel/component and
dialogue MCP operations can inspect and edit the same native data. There is no new
one-command jungle-film MCP endpoint.

The manual picture template uses authored mouth cycles. The workspace benchmark
additionally passes measured per-frame voice amplitude into native cel authoring
for each language; a manual automatic waveform-binding control is not added by
this change. `JungleFilmSoundtrack` is a native PCM composer used by workspace QA;
there is not yet a dedicated manual score-generation button. Existing workbench
audio lanes permit manual import/assembly. Do not describe the complete private QA
orchestrator as an installed, one-button phone workflow.

## Dialogue, music and review outputs

English uses stock voice `am_adam`; Hindi uses `hm_omega`, with real local Kokoro
CPU inference. Native import verifies actual audio samples, hashes and timed
requests. Both complete performances fit their authored slots. English and Hindi
native pictures have separate amplitude-timed mouth geometry; amplitude timing
does not distinguish phonemes or establish convincing speech acting.

`JungleFilmSoundtrack` writes original 24 kHz stereo PCM for 120 seconds. It combines
an authored pentatonic motif/pads, percussion, forest calls, footsteps, wind,
blade/claw transients, dragon/cub calls and a quieter remorse/resolution section.
It ducks the score around dialogue. The mixed review files contain H.264 video,
48 kHz stereo AAC and language-tagged optional subtitles.

Workspace production exercises native `SketchProjectStore`, `SketchBookStore`,
`SketchEditOps`, `SketchLongBookExport` and `SketchFrameRenderer` under native Skia.
Final-review corrections to the English remorse passage also use native edit
operations. Cloud FFmpeg packages native pixels and original audio. This is not a
phone/MediaCodec export, deployed MCP test, signed APK or in-place upgrade proof.

Private, ignored QA evidence is retained in `.work/jungle-film/full/`: editable
English/Hindi cel JSON, native book manifests, original WAVs, actual frame samples,
MP4s and `Production-Evidence.json`. The source tests cover editable original
geometry, full-film exposure timing, preservation of a human edit on resume,
construction layers and real PCM output. Container checks, test counts and changing
pixels are engineering evidence; they do not certify animation quality.
