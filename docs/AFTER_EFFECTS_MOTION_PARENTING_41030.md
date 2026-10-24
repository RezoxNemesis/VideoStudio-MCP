# VideoStudio 4.10.30 — Linked 2D Parent Motion

This is an original Android-native motion-graphics building block inspired by
parenting and null objects in professional compositors, not Adobe code or Adobe
After Effects project-file compatibility.

## Source-backed capability

The new source-free GROUP track can contain a single-level parent for as many
as eight existing IMAGE, TITLE and SHAPE overlays. Each child retains its own
local keyframes, exact source identity, layer ordering and saved source file.
The parent adds time-varying position, uniform/nonuniform scale, rotation and
opacity through the same Media3 OverlaySettings path used by preview and export.

The parent has no texture, imported media, audio, Gallery source, and does not
create extra video decoder input. At most eight GROUP tracks are allowed in the
existing 32-layer timeline. No nested parenting or VIDEO parent is claimed.

## Authoring workflows

The Android Media pane now supports "Parent Motion Group": select up to eight
existing titles, images or vector shapes, and choose drift, pulse, rotate,
slide or hold. The group appears in Timeline/Inspector. "Edit precise keyframes
and Bézier curves" exposes key time, x/y, scale, rotation, opacity and custom
cubic handles. Linking, keyframe edits and dissolution are guarded against
stale project revisions and participate in the editor's existing undo history.

The stable private MCP additionally supports these native-only actions:
- `motion_group_capabilities` — discover limits and provenance
- `motion_group_plan` — validate and preview JSON receipt, no save
- `motion_group_edit` — one atomic create / animate / dissolve edit.

Common fields: projectId, expectedUpdatedAt, operation. For create, include
operationId and layerIds; optionally motionKeyframes. The server derives a
stable groupId. For animate, include groupId and motionKeyframes. For dissolve,
include groupId. On failure no project or source asset is changed. On success
the native renderer uses exact frame times and links the same group on preview
and export. This is not a remotely hosted After Effects renderer.

## Acceptance criteria

1. Multi-selection creates one parent and preserves originals.
2. Different layers move together but keep their individual motion and opacity.
3. Unlinking never deletes original media, captions or base clips.
4. A stale expectedUpdatedAt is refused.
5. Parent cannot attach to audio/video, itself, an absent parent, or a second
   parent; group must cover each child's full timeline span.
6. Native keyframe count <=64; max 8 groups and 32 total layers.
7. Robolectric tests measure real Media3 overlay transforms. They do not
   certify GPU output on the phone.

## Known limitations

This is a one-level 2D transform parent. It is **not** nested precomposition,
independent 3D cameras/lights, AE null expressions, mask tracking, optical
flow, full motion blur, water/fire fluid simulation, depth reconstruction or
Adobe .aep compatibility. The user's uploaded six reference videos remain
quality benchmarks. Passing native unit tests is necessary, but never proof
that on-device preview/export and fidelity match those references.

Build status and linked PR evidence belong in GitHub Actions. The owner
stable APK release remains untouched until on-phone regression tests pass.
