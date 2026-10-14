# Video studio 4.3.1 reliability repair

This patch addresses the recurring `SQLITE_TOOBIG` command failure and drawing-file handoff reported after 4.3.0. It preserves the application ID, original signer, device identity, projects and media. Gallery access remains disabled.

## Live evidence, 14 October 2026

The installed phone reported 4.3.0 and a fresh heartbeat while a new, empty `connection_health` request failed at command admission with `SQLITE_TOOBIG`. Heartbeat success therefore did not prove command delivery.

The deployed legacy relay retains commands and their results in a single storage value. One already completed `sketch_get` result was privately archived and replaced with an explicit checksum/byte-count history envelope, preserving its completed status. After this reduction, a fresh primary health command was accepted and completed on the phone. Nine older native result deliveries still required recovery; the temporary reduction does not establish that the legacy server is permanently repaired.

All three MP4s from the uploaded archive were imported through the encrypted native media mailbox, checked by native size/SHA-256 results, and sampled by Video studio's native analyzer. No Gallery browsing or host video processing was used. The first recording shows vertically mirrored 3D content. Native inspection of its registered pre-encode JPEG confirmed the same mirror before video encoding (540 × 960, EXIF orientation 1). The pinned Filament 1.74.0 OpenGL driver already returns top-first rows; Video studio reversed them again. The composer now follows that documented row contract. Existing generations remain unchanged; device acceptance requires inspecting a newly rendered generation after installation. No artistic-fidelity claim follows from this orientation repair.

## Changes

- Native terminal results are archived in full before sending a bounded relay envelope. The first receipt and original command sequence remain stable across retries and process restart. Large results advertise exact bytes, SHA-256 and `get_native_result_page` for reconstruction: decode each `encodedChunk` using the declared `encoding: "base64"`, join the bytes in page order, and verify `resultBytes` and `resultSha256` before parsing JSON. In One File Lock, paging requires the same selected file at admission, receipt capture and retrieval; older, unrestricted-mode and restored work without proven admission scope receive no file binding.
- Existing pending receipts keep their original bytes on migration. `get_native_delivery_status` exposes bounded recovery metadata; `compact_native_delivery` explicitly verifies the original hash, retains the full result and original wire receipt, and prepares a smaller delivery. Preparation never claims server acknowledgement. Conflicting terminal acknowledgements remain pending for reconciliation.
- Registration sends a bounded project index. State diagnostics summarize journal entries instead of embedding prior drawing documents and image results. Only allowlisted HTTP error causes enter diagnostics.
- Backup receipt delivery batches fairly with limits of 16 requests, 640 KiB and six seconds per sweep. Every acknowledged fragment has a durable checkpoint; retries preserve ciphertext and IDs. Poll status, receipt progress and failures are visible independently of the primary heartbeat.
- State reads and receipt recovery run away from Android's UI thread.
- `sketch_import_drawing_asset` reads a packet and source already registered as private project assets. Both hashes, project revision, canonical ownership, bounded gzip expansion, declared drawing method and the complete geometry are checked before marks. Resume preserves human edits. Every batch atomically requires the same live session, so human takeover stops further import marks.
- Human users can choose imported project files by name from Sketch Studio's drawing import dialog. The linked-folder flow remains available. Neither flow requires entering internal IDs or hashes.
- Cinematic readback no longer reverses Filament’s already top-first pixel rows. An asymmetric 2 × 3 fixture guards both axes, buffer offsets and bounds. Dimension verification is recorded separately from physical content orientation. Library contract: https://github.com/google/filament/blob/v1.74.0/filament/backend/src/opengl/OpenGLDriver.cpp#L4224-L4237 .
- Native video analysis distinguishes coded/display dimensions and decoded frame sizes, reports rotation/duration availability and labels approximate sync-frame sampling. Unknown metadata is not treated as successful motion evidence.

## Verification and deployment boundary

Focused regressions cover oversized ACKs, frozen retry bytes, Unicode result reconstruction, explicit legacy recovery, receipt fragment retry/restart/budgets, drawing ownership/hashes/gzip/replay, human takeover, project lock release and the human import UI. The complete Node suite passes 233 tests plus 306 smoke assertions. The final signed Android run passed **1,002 tests across 191 suites**, with zero failures, errors or skips. Build: https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/38558946631 . Publication: https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/38559454709 .

Verified application source: `c0ecacf043cec80d2917c333b47e8f8fb6c52315`. Version 4.3.1 / code 431; package `com.rezoxnemesis.videostudio.next`; signer `7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`. APK: 129,893,789 bytes, SHA-256 `b7e425e7002dd33a308f76402b24ce42aaad3e9105138147d1b43950894b8df6`. The public APK and public proof were downloaded without authentication and matched the verified CI artifact.

Download: https://github.com/RezoxNemesis/VideoStudio-MCP/releases/download/videostudio-v4.3.1-preview/Video-studio-4.3.1.apk . Documentation-only commits after the application source do not change that APK.

The repaired Worker implementation must still be deployed to bound aggregate cloud command storage. Existing Cloudflare deployment credentials are not configured in this environment; an APK cannot replace the running Worker. New APK functions require installation and real-device acceptance before being called verified on the phone.
