# VideoStudio MCP

VideoStudio MCP is a mobile-first, local-first video editor that can be controlled from ChatGPT through a custom MCP server.

## What works in v0.5

- Installable web app/PWA served by the same Cloudflare Worker as the MCP server.
- One-tap pairing helper for ChatGPT.
- Device identity generated locally in the browser.
- Project creation and project metadata sync.
- Video/image import that stays in the browser using IndexedDB.
- Timeline reorder and clip removal.
- Per-clip trim for video.
- Aspect ratios: 9:16, 16:9, 1:1, 4:5.
- Playback speed, mute, title overlay, quality and transition settings.
- Local WebM rendering with Canvas + MediaRecorder.
- Durable Object command relay so ChatGPT can send edits to the open app.
- MCP tools for device status, project inspection, remote edit commands and command results.
- On-device contact-sheet generation so ChatGPT can visually inspect sampled frames without uploading the full video.
- Remote clip removal, movement and full timeline reordering.
- Batched remote edits so ChatGPT can queue a sequence of changes with render last.
- Adaptive local export: MP4 when supported by the browser, otherwise WebM.
- Multi-cut timelines from one source asset, enabling real jump-cut edits.
- Per-clip playback speed and per-clip title overlays.
- Autonomous requests can submit a complete clip plan and render it in one remote workflow.

## Privacy model

Video bytes stay on the user's device in this version. Only lightweight project metadata, settings and command status are stored in the Cloudflare Durable Object. This avoids requiring R2 or another cloud media bucket.

The device ID acts as the pairing secret for this early build. Do not post it publicly. A production multi-user version should add OAuth.

## MCP endpoint

`https://wispy-queen-f9b5.prakasharuntandon634.workers.dev/mcp`

## App

`https://wispy-queen-f9b5.prakasharuntandon634.workers.dev/`

## MCP tools

- `server_status`
- `device_status`
- `create_video_project`
- `list_video_projects`
- `get_video_project`
- `queue_video_edit`
- `get_video_command_result`
- `request_media_analysis`
- `queue_video_edit_batch`
- `video_project_plan`

Supported remote edit actions:

- `set_trim`
- `set_speed`
- `set_mute`
- `set_aspect`
- `set_title`
- `set_quality`
- `set_transition`
- `remove_clip`
- `move_clip`
- `reorder_timeline`
- `replace_timeline`
- `set_clip_speed`
- `set_clip_title`
- `analyse_media`
- `render`
- `autonomous_request`

## Local rendering

Rendering happens on the device and currently exports WebM. Some browsers may require one user tap before allowing local media playback, especially when ChatGPT requests a render remotely. In that case, open VideoStudio and tap Render once.

No paid media storage is required.

## Development

```bash
npm install
npm run dev
```

Deploy:

```bash
npm run deploy
```

Cloudflare Git integration is configured on `main`, so merging to `main` can deploy automatically.
