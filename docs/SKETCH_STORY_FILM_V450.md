# VideoStudio 4.5.0: Story Film + Sketch Colour / Precision Upgrade

This source branch builds on native 4.4.0 prepared-sketch character animation (PR #78). It does not claim the phone already has this version installed.

## In-app workflow

Open Sketch Studio → More → Story → 2D film (also under File). Enter a short story/topic and select 4–12 seconds. The engine creates a separate editable 1280×720 native sketch at 24 FPS, with three connected camera shots. Play, scrub, change objects, edit keyframes and use File → Export video to render through the existing native export. Refine prepared character artwork via More → 2D animation to use the prior rigging, IK and authored pose tools.

Optionally select Watch the actual vector drawing journal. This creates blank owned layers, begins a source-bound original_strokes live session, and adds all objects in ordered revision-checked batches. Sketch Studio can replay real accepted draw operations. The phone must be open for live viewing; the replay journal is retained. Rendering/physical-phone playback are separate acceptance steps.

Brush / Text → Colours now gives eleven collections of 16 pigments (176 distinct true #AARRGGBB colours): multiple skin-tone/undertone groups, hair, fabrics, nature, night/city, sunset, effects, stone/metal. Personal saved colours, exact ARGB input and the existing advanced full-spectrum HSV editor are retained.

Vector pen and eraser stabilization now backs off as strokes accelerate and at sharp corners. Brush/render/export all use the same original saved point geometry. Paint, clone, reference import, human edits and existing projects are unaffected.

## New engineering details

- Twelve native editable layers across three story shots: sky, atmospheric depth, characters, captions/effects.
- Bounded vector environment geometry, gradients, faceted shapes, shadow/reflection surfaces, rain, clothing and face/hair elements.
- Independently moved head, eyes, eyebrows, mouth, arms, legs, boots, scarf and jacket panels.
- Eased authored pose inbetweens (13 native keys per articulated object) rather than camera-only movement.
- Genre-sensitive templates for struggle, racing, nature, relationships and adventure.
- Full native timeline coverage checks before save, document schema and 4 MiB budget enforcement.
- Revision-checked source-bound original_strokes drawing events, live journal notifications and typed canvas playback.
- No imported generated bitmaps or external paid model required.

This is a deterministic vector-animation story draft, not a trained model that can automatically produce feature-film drawings for any conceivable script. Story-specific drawing quality, new views, high-end character acting and finger/face details still require native artist refinement.

## New tests

SketchStoryFilmGeneratorTest: original 240-frame/24FPS sequence coverage, independently animated body parts, three different actual Android Canvas images, alternate genre/30FPS cases, invalid input, and durable original/live-drawing parity.

SketchPaletteLibraryTest: 160 distinct legal #AARRGGBB pigments, immutable swatches and deterministic palette matching.

SketchStrokePrecisionTest: pen jitter suppression, fast-stroke catch-up, corner retention and non-finite data rejection.

The full existing Android suite and all three new test files must pass before an APK can be called verified. The signed installable APK must keep package com.rezoxnemesis.videostudio.next and the pinned original signing certificate SHA256 7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. No Gallery browsing, uninstallation, database resets, or credential/key rotation.

## Next engineering milestone (not claimed implemented)

Long-story semantic storyboard planning with exact subject/location continuity, independently authored 360° turnarounds, arbitrary multi-character action and facial/phoneme animation, advanced skin-mesh deformation, audio timing, and real-device export/performance QA. No measured 50x-quality improvement is asserted without side-by-side artistic evaluation.

CI note: the temporary ci/v450-stacked-native-qa branch adds only an Android workflow pull-request filter, so native compilation and unit tests can run without merging the diverged main branch. Restore the PR base to feat/sketch-character-animation-v440 after verification.

## Second artistic-quality engineering pass

Native full-sequence visual review exposed repetitive human staging and intrusive dark cloud ellipses. The source now positions softer high-altitude clouds, makes the night character more legible, gives the middle shot a closer impact view, increases the collapse/recovery body-angle range, and adds independent articulated hands, pencil-like hair strands and coat folds. The timing keeps native keyframes and the original sketch structure; it does not pretend to be independently re-drawn cels.

The scripted protagonist is no longer always the same human figure. The subject compiler now chooses:
- A person for emotional/dramatic and general subjects.
- A wheeled car for car/racing narratives, with original vector car silhouette, suspension, independent spinning rims/spokes and motion streaks.
- A dragon for dragon/fantasy narratives, with foreground/background wings, keyed tail and claws, head, crest and fire/breath components.

Text subject detection uses word boundaries so "career" does not accidentally become a car race. Users still have editable native layers/cels. These are explicit artist-coded subject archetypes, not unrestricted generative animation from arbitrary nouns.

## Full native video verification

The optional SketchStoryFilmNativeProofTest exports all 240 native 1280×720 Android Canvas PNG frames from the exact 10-second "Struggle of a Man" story. It verifies frame coverage, distinct adjacent frame content and source hashes. scripts/encode-story-film-proof.py checks ordered PNG identity, packages the exact sequence with FFmpeg at 24 FPS, verifies all 240 decodable MP4 frames, ten-second duration, mobile delivery size, and makes a 20-panel contact sheet with selected native screenshots. The encoder is a CI tool; it is not a phone render or AI-generated imagery.

GitHub-hosted CI may not have FFmpeg initially, so the workflow installs Ubuntu-distributed ffmpeg/ffprobe when absent; the Android app gets no new binary/external dependency.

Artistic approval still requires inspecting the new contact sheet and actual 10-second MP4. Successful frame count or changed pixels alone are not grounds to claim an arbitrary animation is professional quality.
