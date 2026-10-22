# Render production carrier: durable connection handoff

## What went wrong in the October 22 session

A public Render UUID was confused with the private VideoStudio owner credential; an older sleeping VideoStudio installation was then queried repeatedly. The current 4.10.5 branch did not contain the Render Binder dispatcher. The separate 3.5.4 carrier did connect, but its old action allowlist rejected material, primitive and video operations before they reached the updated Render APK. Large concurrent batches overflowed Render's bounded native queue. Status receipts alone did not establish Blender editor readiness or image quality.

Do not repeat these workarounds. Version strings are not capability discovery. Never replace an unavailable sphere/material/render operation with a cube and report equivalent output.

## Connection sequence

1. Use the current private owner credential supplied through the existing VideoStudio MCP connection. Do not put it in this repository, a project file or a public connection card. Do not use the public Render UUID as `deviceId`.
2. Call `device_status` (or the direct native `app_status`) and distinguish control-plane online, Android executor online, paused, and offline. Do not keep querying an old installation when a new credential has been supplied.
3. Use `queue_video_edit` with `projectId=active-native`, `action=autonomous_request`, `nativeAction=render.engine.status`. Put the exact installed package in `nativeParameters.target_package`: `com.rezox.render` for the original or `com.rezox.render.preview` for the isolated preview.
4. Poll that exact command ID with `get_video_command_result`. A successful native receipt must identify `target_app=Render Studio`, the expected `target_package`, and the public UUID from the installed app's share card.
5. Discover `render.production.execute` with `operation=capabilities`, `payload={}` and the same `target_package`. Read the returned operation list, encoder availability and actual editor/window readiness.
6. For every subsequent production request include `target_id=<the verified Render public UUID>`, `target_package=<the same package>`, `operation=<supported operation>`, `payload=<typed arguments>`. These are nativeParameters, not VideoStudio project arguments.
7. `editor.open` requests Render's own editor activity; it is not a claim that Android allowed background launch. Verify a fresh native editor heartbeat and scene inspection before editing. The owner may need to foreground Render once.

The original MCP endpoint, owner credential namespace, VideoStudio projects and Render projects remain separate. There is no new relay and no remote Blender executor.

## New carrier behavior

The stable `render.production.execute` outer action avoids adding a new VideoStudio allowlist entry for every future typed Render operation. Render itself strictly validates the inner operation, arguments, file scope, target identity and memory policy. Arbitrary Python, shell, Gallery enumeration and arbitrary app targeting remain unavailable.

One Binder operation runs at a time. A bounded 32-request FIFO provides backpressure; a rejected request is not executed, and an accepted request is not reported as completed before the native receipt. Recheck pause before dispatch. Do not automatically replay uncertain mutations after disconnect or process death.

Original Render is the default. A preview installation never silently steals its commands. The preview must be explicitly selected and must match its own public UUID.

## Build evidence

Carrier source commit: `3ad512d131c16cf73f9f643a379f3451e4353f32`.
CI run: https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39700362313
All 98 Android unit tests passed across 29 suites. The APK compiled and its v2 signature was verified against the established VideoStudio certificate:
`7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`.
APK SHA-256: `eb628da8de66b76d172cd3bbeb045362878f41dd70657995fe4a75a93cc5fd25`.

This is VideoStudio 3.5.5 based on the working 3.5.4 carrier, not a replacement claim for every feature in the unrelated 4.10.5 branch. Physical on-phone production handshake, screenshots and video encoding remain acceptance tests after owner installation. Do not uninstall a newer or differently signed installation to force this APK.

## Data-preserving installation

Save/export any unsaved Render work first. Update VideoStudio in place only when Android accepts the matching package, version and certificate. If it rejects the update, stop: do not uninstall or clear data. The Render production preview is installed as a separate package to preserve the existing Render installation. Its project store and public UUID are new and separate.
