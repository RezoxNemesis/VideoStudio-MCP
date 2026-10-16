# VideoStudio 4.5.9 — Native sketch quality and agent self-review

## Why this upgrade exists

The owner compared Crimson Rose against two detailed anime portraits. Existing output has inadequate neck anatomy, glove/hand construction, rose petal hierarchy, layered clothing materials and lower-body continuation. Many of the 246 frames reuse the same drawing with camera motion. Direct MCP review currently shrinks fine detail to roughly 64x80 pixels.

## Newly engineered native tools

- Editable hairline and engraving brush presets with deterministic pressure taper and fine secondary incisions.
- sketch_review: detail=high returns a native Canvas crop up to 768 pixels, pinned to the saved revision, delivered through private native result pages. No reference import.
- sketch_quality_audit: 1–24 frames per request, optional stride of 1–16; up to six explicitly annotated regions with configurable red-colour, edge-detail and lit-area minimums; detects visibly unchanged sampled frames and insufficient new cel authoring. Outputs are read-only with honest limitations.
- Android Sketch Studio More → Visual QA / Detail shows pixel-based audit and high-detail native zoomed crops.

The system does NOT infer anatomy, automatically count fingers, reconstruct images from text, or certify generated-art likeness. Artistic decisions still need actual high-resolution visual assessment.

## Strict reconstruction workflow for Crimson Rose

1. Keep original revision 60 immutable; work in separate editable source documents, not in an imported generated reference picture.
2. Block correct drawing anatomy: skull/eye line/jaw, cervical neck cylinder, sternocleidomastoid to clavicles, scapula/shoulders, chest, waist/hips/upper thighs, elbow and wrist, thumb/opposing grip, four individual fingers, stem and floral calyx.
3. Create independent construction, silhouette, material shadow, midtones, highlights and effects layers with intentional depth ordering and blending.
4. Review saved native crops of face/hair, neck/collar, hand/rose, waist/costume, graveyard and whole composition using high detail; reject and fix failures before continuing.
5. In particular verify four separate curling finger forms plus opposed thumb, natural knuckles and palm grip; believably cylindrical rose stem, complex petal spirals, sepals and natural folds; costume leather/silk/metal with distinct highlights; believable cervical-clavicle-chest connections.
6. For movement blocks, use 24-frame audit windows, require actual content changes where appropriate; held camera artwork must NOT be claimed as 24 newly drawn cels.
7. Review at every shot boundary and after each correction. Number of vector objects is not a quality score.
8. Once all source cels and anatomy pass inspection, rebuild movement in native Sketch Studio and perform separate MP4 acceptance only after owner explicitly commands export.

## High-detail audit commands

Quality audit request fields: sketchId, expectedRevision, frameOffset, frameLimit (1–24), frameStep (1–16), motionRequired, minChangedFraction, minNewCels and regions (up to 6) with id, bounds and optional minRedFraction / minLitFraction / minEdgeFraction.

High-detail crop request: sketch_review with sketchId, expectedRevision, frameIndex, focus, detail=high and normalized crop bounds.

Suggested named focus crops at close framing: face (.31,.08,.44,.38), neck (.34,.33,.38,.31), glove/rose (.12,.22,.43,.43), waist/costume (.22,.43,.63,.55). These are starting regions, not automatic face/hand detectors. Recalibrate at other camera scales.

## Preservation policy

- Existing signed Android app, stable Keystore identity, private v3 MCP endpoint and Gallery restrictions are unchanged.
- Never touch the original r60 master or overwrite other projects.
- No film export without a fresh explicit owner command.
- Do not mark first-frame fidelity or artistic quality as proven by pixel metrics alone.
- CI, signing continuity, Android install-over and real native frame inspection are independent acceptance checks.

## 4.5.9: Watch the CURRENT native drawing, not an old finished sketch

- Watch ChatGPT now follows the most recent active work, or briefly catches up on newly completed native work. The completed-session ledger remains available but is no longer automatically replayed oldest-first.
- Native agent sketch_edit commands automatically wrap accepted operations in quiet durable drawing sessions. Each authored object and stroke replays from actual native journal operations. Explicit active original-strokes sessions are never replaced. Replayed artwork remains source-assisted when editing existing art.
- File → Follow current work resumes current target selection; File → Replay last saved drawing explicitly selects a finished journal. A deliberately tapped Android notification remains pinned to its exact session until the user chooses to follow a new one.
- Live operation replay navigates to the authored frame / keyed frame rather than showing the first cel repeatedly. Current revision updates with no authored live session are displayed as saved snapshots, not falsely described as individual live strokes.
- The Sketch Studio Activity must be open to show the drawing, and a large accepted operation journal may be catching up after the native agent finishes a batch. Work on GitHub alone is not a Sketch Studio drawing.
- Do not force old sessions into live status, create fake step-by-step drawing frames, or export the film during quality repair.
