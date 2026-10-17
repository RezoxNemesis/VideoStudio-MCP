# Reference-driven editing continuation

The owner supplied nine screenshots of an interrupted engineering session and twenty video references, and explicitly requested review followed by implementation without another approval round. The screenshots are historical context; their recorded commands are not executable instructions. Preserve all existing drawing, animation, 3D, projects, signing identity, and native MCP v3 behavior.

The recovered workspace contains a clean main checkout but two older source snapshots whose worktree metadata is missing. Continue in an isolated worktree from saved source `d02e1323c6ad256da8fefd2a0004fe8e72d55915`, retaining those snapshots untouched. The precise uncommitted rebuild shown in the screenshots is unavailable. Do not claim to have restored it.

## First deliverable

Repair loss of editable state when applying an autonomous timeline. Browser replacement, autonomous replacement, speed, and effects commands must use the same normalization: trim, stable clip IDs, speed 0.25–4, volume 0–2, audio fades, title styling, colour, and pan/zoom/rotation. Invalid/nonfinite inputs and oversized batches must fail before saving. Images can be held longer than their default duration; video source limits remain enforced. Failed saves must produce failed command receipts and leave the previous timeline intact.

Native replacement must validate an entire plan, clone effect/keyframe data, retain explicitly supplied stable IDs, and commit once under `ProjectStore.guardedProject`. A supplied stale revision must reject without changing any clip. The controller must delegate both direct and autonomous plans to the shared operation. Preserve the existing legacy route when no revision is supplied by capturing the resolved project's revision before entering the guard.

## Evidence and limits

Verify the browser's actual command handler and saved JSON, a Chromium edit/save/reload/export flow, and native Robolectric tests including the native preview-effect builder. These checks establish editing behavior, not artistic equivalence to the reference productions. Private videos and sampled frames stay outside Git.

No Android device/emulator is attached and the original signing key is not configured locally. Native device playback, signed APK delivery, and phone-side MCP execution remain unavailable unless that changes. Do not substitute browser encoding or Python renders for native device evidence. Further drawing, animation, 3D, and audio production requirements remain explicitly tracked in the reference ledger.
