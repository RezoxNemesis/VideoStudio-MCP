# VideoStudio 3.4.2 stability preview

This build targets the reported ChatGPT image-import instability on a Realme P1 Speed running VideoStudio 3.3.3. These are diagnosed code defects and tested fixes; the original phone crash has not been reproduced or verified fixed on a connected device.

Native commands and recovery planning now execute on a serial background executor instead of Android's main looper. Imports have two light-work threads separate from the render queue. Inline still images stream through a bounded buffer to app-private storage, with checksum verification and cleanup on failure. Image headers are inspected without pixel allocation, and still imports avoid the video metadata codec.

Independent downloads append to current persisted project state, retaining both imported assets and timeline clips. Opening or closing the Activity cannot rewrite the foreground service's job snapshot. Recovery plans attach to jobs before resource waiting, retrying plans remain resumable/cancellable, and service shutdown preserves interrupted work. Explicit cancellation remains terminal. Ping/state diagnostics are excluded from the mutation journal to prevent recursively embedded state growth.

The Connect ChatGPT dialog offers a private MCP URL copy action, marks clipboard content sensitive, and explains actual connector setup. Sharing text to ChatGPT alone does not install a connector. Android package visibility includes the ChatGPT app.

## Installation and connection

The ARM64 preview uses package `com.rezoxnemesis.videostudio.preview`, versionCode 342, versionName `3.4.2-preview`, and label **VideoStudio Preview**. Android 10 or later is required. It installs beside the existing app and uses a separate project database and MCP owner identity. Keep the existing installation; its projects are not automatically migrated to the preview.

1. Install `VideoStudio-3.4.2-preview-arm64.apk` and open VideoStudio Preview.
2. Tap **Connect ChatGPT**, wait for online status, and choose **Copy MCP URL**.
3. Add that private URL in ChatGPT's supported custom MCP connector settings. This requires an eligible account and may require the web interface.
4. Enable the connector in a chat. Run `app_status`, then `app_self_test`.
5. Import one explicitly attached image, check its job result and editor Media Bin, then exercise cancellation and restart recovery.

The existing production Worker remains the target. The preview retains the stable v3 endpoint and Gallery restriction. No Worker deployment or GitHub publication was performed. The local development signer differs from GitHub's existing signing cache; use normal GitHub CI with that cache for an in-place update to the original package. Do not uninstall the original app to work around a signature mismatch.

## Verified and outstanding

- 19 Android unit tests passed, including seven import/recovery regressions and native procedural graphics tests.
- 196 Worker source checks and seven behavioural connection tests passed.
- Worker dry-run build and local HTTP/MCP initialize handshake passed.
- Debug and ARM64 preview APKs built; preview APK signature and package metadata verified.
- `/workspace` and `/tmp` read/write access verified. Setup and build caches stay inside writable paths; TLS verification uses the system Java CA store.
- No Android phone or emulator is connected. Actual installation, Realme crash reproduction, media codec rendering, screen-off behaviour and ChatGPT-to-phone operation remain unverified.
- Optional neural image/video adapters, realistic human synthesis and advanced model-backed motion remain unimplemented as described in `INDEPENDENT_CREATIVE_RUNTIME.md`. This release does not complete that full roadmap.

## Cloud development

Use the existing checkout at `/workspace/VideoStudio-MCP`; no worktree is needed. Run `bash /workspace/setup-videostudio.sh` to refresh the prepared environment without changing manifests or lockfiles. Android commands use `/workspace/toolchains/gradle-cloud` from the `android` directory. This launcher supplies the JDK, Android SDK, writable caches, proxy/trust settings, test-JVM settings and local signing path.

For the local Worker, run `XDG_CONFIG_HOME=/workspace/.config WRANGLER_SEND_METRICS=false npx wrangler dev --ip 127.0.0.1 --port 8787` from the repository and validate it with local HTTP requests. Installation and startup instructions are also saved in the environment configuration draft. The draft adds the existing production Worker hostname to outbound networking for future cloud checks; saving the draft does not apply or publish it.
