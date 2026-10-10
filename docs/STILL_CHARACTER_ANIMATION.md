# Single-still Character Animation in Studio Web

This feature adds **local WebGL character-region deformation** to the existing VideoStudio browser editor and MCP generation queue. It is a distinct mode from camera-only `image_motion`, slideshow `story_video`, and GPU neural *multi-anchor* interpolation.

## What it really produces

An actual MP4 or WebM file is recorded through the existing browser canvas/MediaRecorder pipeline and added to the project's browser-local Media Bin. Up to eight independently controlled image regions shift and flex: heads, forward fists, torsos, hair, capes, arms, and cloth. The `duel` preset provides a collision-timed burst and particles on an anime showdown composition; the `portrait` preset prioritises subtler breathing, hair and fabric movement.

The renderer **does not create new photoreal or hand-drawn poses, solve full body kinematics, hallucinate unseen limbs, or run a diffusion model.** Strong/long-range movement can reveal warping artifacts. For genuinely new actions, provide multiple artist-made keyframes or a verified generative model, then use the separate Neural Temporal Motion mode for interpolation.

## Website workflow (Android browser)

1. Open [VideoStudio Personal](/personal/) and enter the existing native private connection key if asked. A cloud plan is not the same as a video timeline.
2. In **Editor**, choose **Animate a still image**. This opens the separate browser-local Studio Web editor at `/?generation=character_action`.
3. Create or select a Studio Web project, then use **Add media** to import your explicitly selected still image. No gallery enumeration is performed.
4. In **AI → Generation Studio**, select **Still → Character animation · WebGL rig**; choose **Anime duel** for two-actor battle scenes, or **Portrait** for one subject. Use 720p at first on an older phone; 1080p requires more GPU memory.
5. Select the image in the Media Bin and press **Use selected media**, then **Generate real video**. Keep the browser visible while recording. The output appears in the same project's Media Bin.

**Do not expect the site to access a ChatGPT conversation image automatically:** the user must explicitly import it into the browser. Local media bytes remain in browser IndexedDB unless the owner separately authorises existing Drive sync. The server's cloud projects contain notes/plans, not the browser image bytes.

## Autonomous MCP workflow

After deploying this branch and refreshing the MCP schema, use the existing Studio Web server and the same paired device ID:

- `device_status` and `list_video_projects` establish that the browser and chosen project exist and that the image asset has been imported.
- `generate_studio_video` with `mode:"character_action"`, the exact `assetId`, `motionPreset:"duel"`, `duration:6`, `fps:24`, `quality:"720p"`, `aspect:"9:16"`, and optional `intensity:1`.
- Poll `get_studio_runtime_result` until `completed` or `failed`. A **queued** response means no video has been rendered yet. Use `inspect_video_render` to review the final locally rendered frames.

Optional `regions`: 1–8 objects of `{x,y,rx,ry,dx,dy,frequency?,phase?}`. Position and radius are fractions of the **whole portrait canvas** (`x/y`: 0–1, `rx/ry`: 0.025–0.5). `dx/dy` are bounded shifts of −0.1 to +0.1. If omitted, the preset's actor positions are used; a two-character duel assumes a left foreground subject and right midground subject. If supplied, motion regions override the preset. The model does not detect new skeletons automatically.

Legacy MCP callers that can only select `image_motion` can explicitly request `anime battle / character animation` in the prompt; the Studio Runtime routes that job to this renderer instead of its older camera-only band effect.

## Offline, reliability, and verification

- No paid inference APIs, backend video storage or secret configuration are added.
- Uses WebGL in the open browser; returns a clear error rather than quietly downgrading to a slideshow if GPU support or memory is missing.
- Queued runtime commands are replayed if a browser closed or acknowledgement was lost, even if the local sequence cursor had advanced.
- Successful generation assets carry their command ID; retries can reuse the existing output rather than producing duplicate videos.
- The website cloud workspace is available offline from the *native app*, but rendering browser-local images still requires that browser tab to be active. No renderer runs magically on the Worker while the user's phone is sleeping.
- `npm test` runs syntax and deterministic region/command tests. CI dry-builds the Worker. A physical Android/browser render must be confirmed separately before claiming a live completed video.
