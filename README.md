# VideoStudio 5

A phone-first creative studio in one Android app:

- **Edit**: a multi-track timeline (the playhead stays put and the tracks scroll under it), split, trim, ripple delete, speed 0.1–16×, reverse, per-property keyframes with nine eases, transforms by dragging and pinching on the preview, colour grading (exposure, contrast, saturation, vibrance, temperature, tint, hue, lift/gamma/gain) with 11 looks, blur, sharpen, vignette, grain, chroma key, masks, blend modes, camera shake, adjustment layers, 12 transitions, animated titles, shapes and markers.
- **Animate**: a 2D drawing workstation with pressure brushes (ink, pen, pencil, brush, marker, airbrush, eraser), layers, an x-sheet frame strip with holds, onion skin, reference/lightbox layers, bucket fill with gap closing, lasso select and transform, line/rect/ellipse, mirror drawing, stabiliser, auto-inbetweens, cut-out layer motion, lip sync and playback. Scenes drop onto the timeline as clips.
- **Voice**: VolTune built in. Narration or multi-voice dialogue with moods, sounds and pauses, Voice Lab blends, all on the phone. Each voice carries a lip-sync envelope.
- **Render**: GPU compositing (WebGL2) into the phone's hardware H.264/HEVC encoder (WebCodecs), streamed into `Movies/VideoStudio`. Up to 4K.
- **Agent**: every action is a JSON command, so Claude or any AI you link can edit, draw, voice and render. See `src/agent/api.ts`, or run `{"op":"help"}` in the Agent tab.

**Privacy.** The app has no permission to read the gallery. Media comes in only when you pick a file yourself, or when an agent supplies an https link. Renders are written to shared storage through MediaStore (write-only).

## Build

```bash
npm ci
npm test
npm run build && npx cap sync android
cd android && ./gradlew assembleRelease
```

CI (`.github/workflows/apk.yml`) builds a signed APK on every push to `studio5` and publishes it as a GitHub release.

**Signing.** CI signs with a key kept in the Actions cache. GitHub drops cache entries that go unused for 7 days. To make the key permanent, add repository secrets `STUDIO_KEYSTORE_BASE64`, `STUDIO_KEYSTORE_PASSWORD` and `STUDIO_KEY_ALIAS`.

## Remote agent link

`relay/` is a tiny Cloudflare Worker (D1, no Durable Objects). Deploy it, put its address in Agent → Remote link, then add the shown MCP URL in Claude → Settings → Connectors. Claude's `studio_run` tool queues command batches; the app picks them up within seconds while it's open.
