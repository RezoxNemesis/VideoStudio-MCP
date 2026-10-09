# VideoStudio 3.4.11 owner preview

An installable checkpoint for trying the editor on your phone. The complete engineering blueprint is still in progress.

[Download the APK](https://github.com/RezoxNemesis/VideoStudio-MCP/releases/download/videostudio-preview-3.4.11-7dd68f364bdd/VideoStudio-3.4.11-preview.apk) — 99.2 MB. The exact signed APK passed 409 Android unit tests and all 10 API33 emulator cases before publication. [Release and verification files](https://github.com/RezoxNemesis/VideoStudio-MCP/releases/tag/videostudio-preview-3.4.11-7dd68f364bdd).

## Try the app

1. Install the preview APK. If Android reports an incompatible update, preserve your current installation/data; this development certificate may differ from an older APK.
2. Tap **Import video, images or audio** and choose your own files in Android's picker.
3. Open **Editor**. Try splitting/trimming clips, undo/redo, titles and effects, then preview the result.
4. Use **Export**, starting with 720p/30fps for a short project. Check the saved MP4 outside the app.

The preview improves the home screen and corrects the measured Android13 software AAC encoder tail loss. Outputs must pass media decoding and checksum verification before reporting success.

## ChatGPT control

Use **Connect ChatGPT** for this device's private MCP pairing information, and keep that private. With the connector paired, ask ChatGPT to call `app_status`, inspect the project/schema, apply edits, export and inspect the completed job. **Control → Full Autonomous** permits app operations without repeated app-level confirmations. **STOP CHATGPT CONTROL** remains available.

Owner-selected/imported media is available to the agent; Gallery enumeration remains blocked. The background foreground-service test exercises a real command, an idempotent retry, and an export while the editor is closed. This is device execution evidence; live external ChatGPT pairing and the owner's Realme background restrictions require separate verification. Android permissions and ChatGPT's connector controls remain platform-managed.

The public connection service accepts the APK's existing headers: a later read-only diagnostic receives configHTTP200 and v3HTTP409 without credentials, as expected. Cloudflare rejects the release runner's bare Python probe with HTTP403/error1010; that result is retained in the original release manifest. No app change was required. A live authenticated ChatGPT session on your phone remains unverified.

## Scope and continuation

The checkpoint's evidence includes editor preview/split/history/persistence, real native image/video/gap/audio export, keyframe/chroma/mask pixels, audio-preserving proxies, encrypted Vault/selected-folder recovery, codec routes, private window recovery/lossless AAC join, and autonomous service edit/export. Exact counts, hashes and source identity are in the release's `verification.json`.

Model-generated video, complete 3D/VFX, direct multi-account provider OAuth, cloud execution and the rest of the full blueprint remain unfinished. The internal segmented engine is not yet the public export route. Realme hardware/performance and a live external ChatGPT session have not been verified.

When the owner says **resume** or **continue**, start from the released source and `BUILD_RESUME.md`. Review reported preview problems and rerun relevant regressions before continuing the original blueprint. Preserve projects, original media, stable MCP identity and compatible signing history. This checkpoint does not replace or mark the blueprint complete.
