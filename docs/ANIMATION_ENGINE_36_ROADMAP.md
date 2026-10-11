# VideoStudio Animation Engine 3.6 — Native architecture and quality gates

## Why 132 frames at 24 fps still look like animated pictures

Examined reference: \`1000068057.mp4\` (5.544 seconds, 132 encoded H.264 frames, 9:16 portrait via rotation metadata). A 16-frame contact sheet shows a recognizable black-vs-white duel, preserved skyline and decreasing ghost overlap. Every sampled adjacent frame changes, but the changes are predominantly warped source pixels. The system has no consistent representation of the fighter's hips, knees, sword grip, planted feet, collision or occluded geometry. A sampled sequence can be technically smooth and still lack believable *acting*.

The repository currently separates:
- \`PoseSequenceFlow\`: whole-image bidirectional patch matching; safe for moderate differences; cannot infer unseen limbs or preserve topology under large pose changes.
- \`CombatRigSolver\`: a physically more meaningful 2D hand-authored IK action, but disconnected from textured source-character identity.
- \`NativeScenePlateBuilder\`: fixed background estimate from image medians, not camera-calibrated source/foreground clean plating.
- \`NativeRenderCritic\`: video exposure/freeze/jump checks, not semantic body/sword/contact verification.
- \`ProceduralScene\`: basic polygon/triangle meshes, not skinned glTF character animation with PBR materials.

## Required shared animation scene graph

1. **Source and identity:** user-imported, uniquely indexed shots; stable black/white character IDs; camera intrinsics/extrinsics; shot boundaries.
2. **Segmentation and clean world:** tracked alpha/mattes for each body and sword, separated background/debris/depth layers; temporal clean plate and explicit occlusion/disocclusion handling; reject plate if original figures remain.
3. **Actor representation:** bone hierarchy + authored/analyzed 2D joint tracks and optional 3D skinned character assets. Source textures/appearance must be bound to **the same actor** throughout the shot.
4. **Motion director:** time-coded story beats (stance → anticipation → push-off → approach → collision → recoil/knockback → landing), motion curves, planted-foot anchors, center-of-mass path and camera choreography.
5. **Pose/physics:** 2D/3D IK and forward kinematics, rig limits, weighted center of mass, body inertia, grounded contact and weapon/blade segment intersection. Explicit controller for hands, wrists, head/eye expression where provided.
6. **Rendering:** (2D) GPU skinned textured polygon/deformable mesh with masking, depth ordering, shadows. (3D) real glTF/GLB skeletal skinning, material/shading/lighting and perspective projection. Mixed 2.5D layers and motion-blurred sparks respond to the same animation timeline.
7. **Interpolation:** warp/render between **already solved physical poses**, with optical flow as optional temporal polish only. Never use a cut/zoom/slideshow as silent substitute for a missing pose.
8. **QA:** landmark/joint-length continuity, planted-foot pixel motion, sword grip-to-tip length, collision time, identities per frame, wrong-layer overlap, background stability, out-of-focus/freeze/temporal flicker. Fail visibly when capabilities/models are missing.

## What is implemented in this foundation

- \`RigKinematics\`: pure 2D/3D-world-space analytic two-bone IK with pole vectors and planted-foot targeting; smooth time-position interpolation; finite-blade-to-blade distance (not sword-tip-only hit detection).
- \`StoryboardMotionPolicy\`: image-only action/background-difference and correspondence diagnostics. Classifies \`FLOW_CANDIDATE\`, \`NEEDS_ARTICULATED_RIG\` and \`NEEDS_SCENE_RECONSTRUCTION\`. This is heuristic preflight, NOT a body pose estimator.
- \`StoryboardMotionPreflight\`: bounded decode of only VideoStudio-owned, user-approved images; per-pair diagnostics to MCP through \`analyse_storyboard_motion\`; light job execution, persisted result; no Gallery browsing or external inference.
- Android self-test reports these readiness capabilities separately from the STILL-MISSING source-faithful rigged 2D/3D renderer. This is intentional truthfulness.

Example through installed private MCP v3 \`app_execute\` bridge:

\`\`\`json
{"action":"analyse_storyboard_motion","parameters":{"projectId":"EXISTING_VIDEO_STUDIO_PROJECT_ID","anchorAssetIds":["SOURCE_ASSET_1","SOURCE_ASSET_2"]}}
\`\`\`

The result indicates which pairs require pose generation. It does **not** pretend to solve those pairs.

## Engineering milestones still REQUIRED

### Milestone 2 — Subject registration and 2D mesh deformation
Author a mobile manual bone/outline editor so stylized character models need not depend on unreliable selfie segmentation. Optional licensed on-device segmentation/keypoint models may offer a first pass; use confidence-driven manual correction. Output a stable image-space textured mesh per character and sword, weight per vertex to bones, deform from actual joint trajectories and pass the source reference end points. Acceptance: recognizable original fighters; no double bodies; each hand keeps sword grip; no new camera/scene mismatch.

### Milestone 3 — Shared camera and true 3D backend
Add 3D skeleton/bind-pose/mesh skinning and glTF asset I/O (for example, a hardware-tested Android glTF rendering component). Same pose timeline drives 2D mesh or 3D character. Correct PBR lighting, shadows, projection, camera constraints and temporal object identity. User-facing 2D and 3D authoring should not be conflated; a 2D image is not automatically a full 3D mesh. Acceptance: animated asset has consistent dimensions under camera orbit and limb rotations.

### Milestone 4 — Cinematic fight authoring and renderer
Physically timed stance/weight shift/step/strike/counter/knockback. Foot lock and center of mass, contact events, weapon arcs and local sparks/dust, shot-to-shot continuity and stable reflections. Motion-director clip IDs drive all tracks with common timestamps. Verify stable source actors by eye, not just video codec health.

### Milestone 5 — Hardware, model packs and release gates
Bounded-device 720p preview first, small motion windows, chunked disk checkpoints, asynchronous thermal governor, optional licensed model packs with real inference and benchmarks, Drive warm/cold storage and resumable project files. Require automated motion tests, app integration tests, video output inspection, and paired Android-device quality approval before reporting completion. No free inference model can be assumed merely because its provider is registered.

**Do not call this foundation "completed cinematic 3D animation."** It supplies diagnostics and geometry to support the next rendering milestones. Keep the user-visible native app at 3.5.1 until a later APK is actually tested and installed.