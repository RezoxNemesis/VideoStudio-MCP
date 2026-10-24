# VideoStudio 4.10.32 — Professional Multitrack Native Timeline

## Usability requirement

A single crowded VISUAL row makes even correct keyframe engineering difficult
to author on a phone. The new opt-in **Multitrack** editor mode expands each
owned studio layer into its own visible and interactive horizontal lane. This
includes music, images, titles, video overlays, vector shapes and linked motion
parent GROUPs. Compact mode remains unchanged and can be selected at any time.

## Implementation proof

- Source implementation is `StudioTracksView`, the real Android Canvas and
  touch target view shown in `MainActivity` (no browser mock-up).
- Each existing project-layer ID is drawn in its original rendering order,
  labelled by type (AU/IM/VD/SH/GR/TX). Linked children have a grouped marker.
- Individual layer rows have independent touch hit targets. Dragging and
  trimming use the existing `StudioLayerTouchMath` and optional snap points.
  Gesture completion calls the existing editor's exact project-revision guard.
- The timeline supports two-finger pinch zoom between 1x and 16x; that
  gesture has no project mutation. The original +/- controls remain available.
- Audio waveforms continue on the corresponding audio track.
- The nested scroll hierarchy permits vertical scrolling through up to
  32 layer rows, while HorizontalScrollView retains the zoomed time axis.
- A persistent local view-mode preference is separate from project and asset
  serialization, so a ChatGPT edit cannot unexpectedly change user layout.

## Verification

Robolectric native touch tests check that overlapping layers can be selected
and moved independently, compact mode remains pixel-size compatible, and
pinching zooms only the viewport rather than saving a clip movement. CI must
compile the entire Android project and pass all existing unit tests. On-phone
visual QA is required before shipping.

## Non-claims

This does not implement actual Adobe After Effects, mask tracking, full nested
compositions, expression scripting, optical-flow motion blur or parity with
all six owner reference videos. It is a professional interface foundation,
not a finished compositing-equivalence claim.

## QA

1. Verify compact and multitrack mode in portrait and landscape.
2. Select two overlapping captions by their distinct row coordinates.
3. Drag, trim, undo, redo, pinch zoom and confirm project/music unchanged.
4. Test 1, 8, 16 and 32 track rows on the actual Android phone and check
   touch responsiveness, scroll clipping and thermal behaviour.
5. Repeat `Test subject 2` Media3 edited-preview reproduction and 9:16
   rendered-frame comparison after the signed candidate is installed.
