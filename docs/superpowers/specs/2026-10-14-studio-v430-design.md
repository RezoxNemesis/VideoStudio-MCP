# Video studio 4.3.0 authoring upgrade

The owner asks for another autonomous overall upgrade and delegates feature selection. The goal is useful native authoring controls with equal native-agent access, preservation of existing work, and a signed compatible APK. Routine design, workspace and GitHub confirmation are already waived by the owner.

This upgrade changes shared authoring interfaces across existing subsystems. It keeps the existing renderers, transaction facades and private control plane. A renderer rewrite or an unconfigured neural generation provider would not produce a verifiable improvement in this scope.

## Deliverables

1. A keyframe manager showing ordered keys, selection/navigation, sampled transforms, explicit CRUD and easing. Older normalized keys open safely. Editing a split clip retains its original authored clock and outside keys, avoiding an implicit motion reset.
2. Native 3D camera-path authoring: sorted phase keys with position, target and up vectors, plus guarded program edits through `cinematic_3d_edit_program`. Each affected rendered frame must have a valid camera basis. Scene geometry, actor animation and source bindings remain intact.
3. Configurable vector symmetry: vertical, horizontal, both axes and radial two through eight sectors around a chosen center. A shared `symmetry_stroke` operation expands into ordinary editable strokes atomically. Live playback receives the expanded authored strokes. Image copying and clone paint cannot enter an original-stroke session through this operation.
4. Stereo balance and stereo-to-dual-mono mixing in the shared native preview/export PCM processor. `audioPan` is finite in [-1,1], attenuation-only with unchanged center gain; `audioMono` averages the left/right pair before balance and envelope gain. Mono sources are unchanged by balance; additional surround channels retain their original positions and existing envelope gain.
5. Caption styling using native timed overlays: font family, size, bold, italic, alignment and background colour. The human form, saved layer schema, preview and export agree; old captions retain their current appearance.

## Interfaces and compatibility

- Release version 4.3.0, code430; retain `com.rezoxnemesis.videostudio.next` and pinned original signing certificate.
- New precision keyframe edits use a version-gated shared operation, defined with exact schema before integration. Existing full replacement and clear semantics stay compatible.
- Symmetry geometry uses canvas-pixel space on rectangular canvases. Nonfinite/invalid/outside generated geometry is rejected atomically; coordinates are never silently clamped into a different drawing.
- New caption fields are title-specific and strictly validated. `set_studio_layers` and authored JSON expose the same settings.
- Root owns JS schema/version/route integration, version metadata and release. Each worker owns separate native files; MainActivity has one owner with root integration patches applied after that owner freezes it.
- No Gallery access, user-media processing outside native surfaces, credential changes, paid services, replacement signing keys, deletion of projects or source-copy claims.

## Verification and release

Exercise actual transactions, drawing expansion/live events, camera sampling, PCM samples and caption spans/settings. Cover revision conflicts, malformed inputs, cancellation/lifecycle and split/legacy compatibility. Run one coordinated focused Android suite and the Node suites before the complete signed CI build. Verify and publish that exact artifact, then hash the public download.

Unit tests do not establish phone GPU/audio/latency, notification delivery or artistic likeness. The existing missing Cloudflare account/token bindings remain an external production-deployment blocker; do not repeatedly dispatch the unchanged blocked workflow. New native operations remain available through the independently verified encrypted app-key connection when a compatible executor reconnects.
