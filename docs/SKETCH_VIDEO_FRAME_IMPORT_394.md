# VideoStudio Sketch Studio 3.9.4 — source-video frame sketch import

## Request and input

The user supplied **1000067989.mp4**, 720×1280 portrait, 24 fps,
**192 frames / 8 seconds**. Their explicit task is to **draw those
frames inside VideoStudio Sketch Studio**, not return a still-picture
generator's output or a movie slideshow.

A source-derived immutable-frame ink vector manifest was prepared in
the owner's selected **VideoStudio-MCP-Temp-Frames** Google Drive
DocumentsProvider folder. The exact name is
\`VideoStudio_Sword_Duel_192_Frame_Complete_Native_Import.jsonl\`.
The original unmodified source video is stored alongside it as
\`1000067989_SketchStudio_Source.mp4\`.

The original source SHA-256 is
\`286a8a951b527cc7bea7cce7428726dbe7a20467d0314c66d3d1cc6435cd16f0\`.

The source manifest is a 193-line JSONL (header + ordered 0..191
native frame operations) with **660 unique editable path/shape objects**:
two independent combatants, action contours and a shared ruined-city
ink background. All path commands, coordinates and frame exposures
have been validated. These are bounded stylized source-derived line
drawings, not pixel-exact repaints of every source color or learned 3D
poses.

## New native workflow

Invoke \`studio_sketch_import_frame_manifest\` through the existing
owner-authenticated native MCP v3 bridge, or use the compatible generic
\`autonomous_request\` with \`nativeAction=sketch_import_frame_manifest\`
and these flat parameters:

- \`sketchId\`:
  \`sword_duel_192_frames_complete_20261013\`
- \`videoName\`: \`1000067989_SketchStudio_Source.mp4\`
- \`manifestName\`:
  \`VideoStudio_Sword_Duel_192_Frame_Complete_Native_Import.jsonl\`
- \`expectedSourceSha256\`: exact source hex string above
- \`expectedFrameCount\`: 192

The native importer reads files **only by exact name** in the
already user-authorized, persisted scoped SAF document tree. It streams
the owner video into SHA-256 without opening or enumerating Gallery,
then validates every manifest row and its immutable source identity
BEFORE creating any sketch or modifying existing sketches.

It applies the manifest through the SAME native
\`SketchEditOps.execute("sketch_edit")\` path used by touch editing.
The output is a separate 720×1280, 24 fps, 192-cel editable Sketch
Studio document. Small batches have deterministic receipt IDs and
strict sequential revisions. A crash/resume reconciles already saved
object IDs, maintaining original source identity and preserving
completed work. A changed source manifest refuses to overwrite.

For work-lane admission, use the existing durable transfer queue
rather than the 2 MiB MCP command receipt channel. The import job
returns \`jobId\` immediately; a completion acknowledgement is NOT
evidence that all frames were saved. Verify the terminal native job
result contains \`validatedObjects=663\` (660 per-frame +
3 background objects), \`frameCount=192\`, \`fps=24\`,
\`verifiedSourceSha256\`, and \`nativeSketchEditable=true\`.
Read \`sketch_get\` and sample \`sketch_preview\` at frames
0, 32, 64, 96, 128, 160, 191. Export a new animation using
\`sketch_render\` only after verifying the native frame data.
Use \`operationId\` and expected sketch revision for export.

## Update compatibility — DO NOT BREAK

In-place update **3.9.3 → 3.9.4**:
\`versionCode=394\`, same existing
\`com.rezoxnemesis.videostudio.next\` application ID.
Do not rotate/change the pinned existing signing certificate or key.
Preserve private Android app data, all VideoStudio projects, the
stable MCP owner control plane, previously saved sketches and the
linked SAF folder. Uninstalling the APK is NOT an acceptable
upgrade procedure. Signing-continuity checks must be green before
delivering an APK.

## Truthful acceptance

Green compilation/JUnit proves code-level operations, not the output.
Inspect real on-device Sketch Studio previews and a Media3 MP4 with 192
decoded frames to confirm shapes, source sequence and 8-second timing.
A traced frame does not automatically become anatomically realistic
articulated motion. Mark pixel-perfect source likeness as **not
verified** if not established by visual comparison. Never pretend a
queued import is a finished result.
