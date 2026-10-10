# Native pose-to-pose animation (VideoStudio Android 3.4.8 candidate)

## Purpose and truthfulness

Animate multiple **different character poses** in a verified order. This is a local bidirectional block-flow motion-interpolation system producing actual intermediate **image pixels**. It does **not** invent unseen limbs, perform skeletal IK, recreate a 3D scene, or ship an industry-trained neural frame model. Fast attacks and changing camera perspective can produce ghosting; inspect finished MP4 frames before declaring the animation successful.

Existing \`animate_images\` remains a separate **layered 2.5D parallax** feature. Do not report its camera movement as pose-to-pose animation.

## Explicit source assets only

The user attaches images in ChatGPT or imports them inside VideoStudio. Neither the Worker nor native app can browse or enumerate the Gallery. This does not change in Full Autonomous mode. \`app_execute\` / the stable MCP v3 command bridge can route new actions without reconnecting the plugin after an APK upgrade.

The source project is left unchanged; \`animate_pose_sequence\` creates a separate project for generated in-between images.

## Safe private frame transfer

When normal \`import_attachment\` with a short-lived HTTPS URL fails or the host rejects a large inline payload, prefer the **bounded native chunk protocol**, not public hosting or weakening HTTPS/SSRF defenses:

1. Compute SHA-256 and byte length locally on an explicitly attached image. Use JPEG/PNG/WebP only; max decoded 12 MiB.
2. Choose a random UUID transfer ID and a safe filename. Split raw binary into <=24 KiB parts. Base64 each part separately; never paste the entire image into one tool call.
3. Through the existing stable native action bridge, execute \`append_frame_chunk\` with \`projectId\`, \`transferId\`, \`name\`, \`mime\`, \`totalBytes\`, \`sha256\`, \`offset\`, \`base64\`.
4. Confirm \`bytesReceived\`. Retries at the **same offset with identical bytes** are safe. Different bytes for an already accepted offset are rejected; out-of-order gaps are rejected.
5. Execute \`finish_frame_transfer\` using the same metadata except chunk bytes and offset. Only a SHA-256-verified, fully received, safely decodable image becomes a VideoStudio-owned source asset.
6. Read returned \`assetId\` and keep it in the intended timeline order. \`frame_transfer_status\` returns bytes received without exposing content.

Native storage paths are app-private and bounded by validated transfer IDs. Completed payloads and temporary signed URLs are purged from the control-plane history and omitted from native journal state; delivery itself remains owner-authenticated.

**Important:** These new actions are in the 3.4.8 code branch. An Android device still running 3.4.7 cannot execute them until the new APK is installed. Do not pretend a queued command is successfully imported.

## Rendering and choreography

Call the stable native action \`animate_pose_sequence\` with:

\`\`\`json
{
  "projectId": "EXISTING-SOURCE-PROJECT-ID",
  "anchorAssetIds": ["asset-0", "asset-1", "asset-2"],
  "framesByPair": [8, 4],
  "fps": 24,
  "width": 432,
  "height": 768,
  "quality": "720p",
  "render": true,
  "fileName": "PoseFight_v1.mp4"
}
\`\`\`

- \`anchorAssetIds\` must be 2–40 source **image assets in the same project**, in exact story order.
- \`framesByPair\` (optional) is an array with exactly \`anchors.length-1\` entries, each 2–16; total output <=240 frames. Long segments support anticipation, short ones a sword-impact burst, and longer aftermath segments slow down recovery. When omitted, \`framesPerPair\` (default 6) applies uniformly.
- \`fps\` 12–30, bounded resolution (default 432x768, even dimensions, max 1280x1920), \`aspect\` fixed to 9:16.
- Output frames are rendered as lossless, app-private PNGs into a separate project before Media3 export. The original project and sources are untouched.
- The render job is resumable after process death and must not be marked successful until a real published MP4 exists with verified portrait geometry.

For the 13-anchor **Ashes of Revenge** fight, a possible pacing map is \`[12,8,6,4,3,3,5,4,6,7,9,12]\`: a deliberate build, fast clash, staggered knockback and slow recovery. This is an example, not a claim that those anchors have all been imported.

## Test and release gates

- Run Robolectric tests for flow endpoint fidelity, new intermediate pixels, stable backgrounds, private byte transfer resume/checksums/traversal and journal secret redaction.
- Run Node MCP tests verifying original chunk delivery to Android and removal after final ACK.
- Run Android release build and inspect the installable APK artifact.
- On an updated real native app, verify: original anchor order, all expected images present, final playable MP4, actual width/height, several generated intermediate poses, absence of static-only camera tricks, flicker/ghosting/occlusion problems, and original source preservation.
- **CI green is not proof of artistic quality.** A sampled native render/real-device evaluation remains required.

## Next-level animation roadmap

Achieving animation-studio quality from sparse rendered screenshots requires more than optical flow: scene-consistent 2D/3D character rigs, IK and joint constraints, per-part masks, learned correspondence/occlusion, temporal modeling, or an actually installed and verified licensed neural animation model. Hardware/software providers must be registered and tested against device RAM/thermal constraints; a code-only label does not count as a working model.
