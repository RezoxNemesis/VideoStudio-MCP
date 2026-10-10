# VideoStudio 3.4.9 — Native Animation Director

## Why this exists

VideoStudio 3.4.8 previously required an external workflow or 40 individual manual MCP edits to animate the *Ashes of Revenge* source frames. Its “Animate Stills” tool primarily creates face-aware 2.5D camera/parallax effects, while the experimental pose tool uses patch-based optical flow which can ghost swords and limbs during fast motion.

Version 3.4.9 provides an **in-app Animation Director** and the same private MCP action. It works from the current VideoStudio **image timeline**; it does not enumerate the Android Gallery, make cloud uploads, manufacture undocumented neural weights, or discard existing sources.

## App UI

1. Open VideoStudio → Editor → your imported, ordered image timeline.
2. Tap **Animation Director • Make a Real Sequence** (also in AI Tools).
3. Select either:
   - **Cinematic frame sequence (recommended)** — directly displays every original authored frame in order. No zoom, text, crossfade, filters, or fake motion synthesis. Pacing adapts to neighboring source-image differences; brief impact emphasis is available.
   - **Experimental optical-flow in-betweens** — generates actual new pixel positions between imported images using local bidirectional block matching. It now uses a finer spatial lattice, linear timing, background pixel locking, forward/backward consistency diagnostics, and a no-ghost frame-cut fallback. Can still fail on nonrigid poses and occluded limbs. Not a generative video model.
4. Choose 24 or 30 fps. Optionally provide **1-based** image numbers for brief impact emphasis (e.g. \`21,23,24\`). Empty selection enables automatic motion-peak emphasis (not semantically guaranteed sword impacts).
5. Tap **Animate + Render**. Native background work creates a **new project** and processes the export under the existing thermal/memory governor. See Activity for progress.

Existing projects stay unchanged. Output is a real MP4 produced and published by Android Media3. The service never opens or enumerates Gallery files.

## Stable private MCP v3 command

Dispatch through the paired private VideoStudio native action bridge:

\`\`\`json
{
  "nativeAction": "animate_timeline",
  "nativeParameters": {
    "projectId": "EXISTING_IMAGE_PROJECT_ID",
    "method": "direct",
    "fps": 30,
    "quality": "720p",
    "render": true,
    "impactIndices": [20, 22, 23],
    "fileName": "My_Animated_Sequence.mp4"
  }
}
\`\`\`

- \`method\`: \`direct\` (2–120 image clips) or \`flow\` (2–40 image clips).
- \`impactIndices\` is **zero-based** in the MCP API, unlike the 1-based Editor UI.
- \`render=false\`: create an editable direct-sequence project without exporting; flow mode also allows prepared frame sequences without export.
- \`fps\`: 24 or 30, consistently used for output image sampling.
- Frames are strictly ordered by the existing timeline. No automatic story reordering.
- In flow mode, a motion-score-based frame map chooses fewer interpolated frames where adjacent images change substantially (to reduce optical-flow ghosts). This is a safety heuristic, not proof of character-joint tracking.

Every tool result returns \`sourceProjectId\`, \`projectId\`, \`sourceFrames\`, and the selected native \`method\`. Direct mode includes \`animationPlan.frameDurationsMs\`, \`motionScores\`, and selected emphasis positions.

## Motion QA rules

The optical-flow engine:

- Preserves the exact first/last authored frames.
- Avoids moving static background pixels when two source frames agree.
- Exposes per-pair consistency, scene difference and cut-risk metrics.
- Uses linear instead of smoothstep interpolation to avoid fake freezes at the start/end of each segment.
- Avoids double-exposing extreme mismatched motion fields.
- Detects major shot changes and falls back to a clear hard cut, rather than blending unrelated scenes.

**Realism limitation:** Optical flow cannot infer unseen limbs behind a torso, recover hidden sword geometry, or animate a human skeleton from two highly different stills. Studio-grade pose generation needs a verified articulated rig/IK system and/or an installed, appropriately licensed, actually executing temporal inference model. VideoStudio does not claim either merely because this Director exists.

## Release acceptance

1. \`node --check src/index.js\`; \`npm test\`; Worker dry-run must pass.
2. Android \`:app:testDebugUnitTest :app:assembleDebug\` must pass; verify release APK.
3. Run \`NativeAnimationDirectorTest\` and \`PoseSequenceFlowTest\` for original-project preservation, timing, impact marker bounds, static-background stability, moving-shape intermediates, and scene cut protection.
4. On a real 3.4.9 device, use *Ashes of Revenge — Full 40 Frame Directors Cut* as source. Confirm the new Editor button appears, 40 original images are preserved, exported duration roughly reflects timing, actual playback and portrait geometry are sound, and visible ghosting is reported rather than hidden.

**CI success confirms code health, not cinematic quality.** The final visual acceptance test requires actual Android playback and an inspected output file.
