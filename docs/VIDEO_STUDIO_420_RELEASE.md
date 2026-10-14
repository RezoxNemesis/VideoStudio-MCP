# Video studio 4.2.0

Version 4.2.0 / code 420 preserves `com.rezoxnemesis.videostudio.next`, the original signing identity, device-owned app key and existing projects. The compatible APK, its exact source revision, signer and download hash are recorded in the release evidence separately.

## Editor and audio

The editor splits at the actual playhead, maps output time back through clip speed, and offers exact source-time trim fields, **Set In Here**, **Set Out Here**, cut navigation and 0.1-second steps. Timeline zoom, snapping and left/right trim gestures use the same revision-guarded edit facade as native agent commands. Trim forms remain open when validation fails, and revision guards reject stale edits.

Clip Volume now includes exact fade-in and fade-out durations. Fades use output time, multiply the existing clip gain and remain continuous when a clip is split. Preview and export build the same Media3 composition. Per-channel PCM gain saturates at the supported range instead of wrapping loud 16-bit samples into the opposite sign; seeks restore the correct fade position.

## 3D, rigging and Sketch

GLB preview has named motion selection, clip scrubbing, playback-rate controls, camera views, drag orbit, pinch zoom and Fit. Playback clocks pause with the Activity. Model loading is cancellable, stale callbacks cannot replace a newer selection, and renderer ownership and cleanup are explicit. Cinematic preview retains its requested or restored frame.

The rig editor adds exact normalized source coordinates and one-original-pixel nudges, with coordinate validation independent of the displayed zoom. These are authoring controls; they do not infer missing anatomy or reconstruct an original rig automatically.

Sketch adds exact frame/page navigation, large-book jumps, a range-based exposure sheet, holds on ones/twos/threes and persistent onion-skin settings. Filmstrip scrolling, fling and selection preserve the active book identity. Book playback respects authored holds and rejects stale background page loads. Existing original-stroke protection, live hand and drawing-alert behavior remain in place.

## Private storage and encrypted media

Primary relay uploads use bounded chunks in the existing SQLite Durable Object, retaining owner, size, hash and expiry proofs across Worker restarts. Publication of a complete upload and its command is transactional. Legacy command queues migrate to the correct protocol store; bounded exports publish only after complete validation. Native project reads handle large metadata rows, while import registration merges into the current project rather than overwriting concurrent human edits or duplicating a completed import.

The additional app-key media helper stages encrypted immutable chunks and an encrypted manifest before publishing an `import_mailbox_media` command. The native importer verifies the manifest, every chunk and the complete source hash, resumes verified partial files and publishes into app-private storage before asset registration. Admitted imports retain recovery and receipt identity across service restart. This GitHub fallback is limited to **256 MiB per attachment**; the separate primary relay upload route has its own 250 MiB limit. See [app-key connection and media instructions](VIDEO_STUDIO_APP_KEY.md).

## Verification boundaries

Regression coverage includes editor transactions and gestures, PCM samples and fade clocks, Activity/lifecycle state, rig coordinates, Sketch timing, encrypted cross-language fixtures and interrupted imports. Local Worker/SQLite checks use synthetic uploads and device replies, including a process restart; they do not establish production deployment or phone execution.

Prior installed-phone app-key commands and native imports have authenticated receipts. Those earlier results do not verify installation of 4.2.0 or execution of its new encrypted media action. APK/source checks, backend deployment, installed version and authenticated terminal receipts must be assessed separately. This release does not claim measured phone GPU rendering, audible playback, notification delivery, drawing latency, exact artistic matching or cinematic acceptance.
