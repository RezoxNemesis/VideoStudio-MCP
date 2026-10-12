# VideoStudio 3.8.1 native 3D execution recovery

## Observed 3.8.0 device evidence

Connected owner-authenticated MCP v3 agent reports version 3.8.0 online
and retains original Test 1 (23 source assets, prior exports) unchanged.
The native "Cinematic 3D • Sword Duel Test" project was created and
appeared in project metadata. The builtin-scene install, capabilities,
saved 11-shot / 226-frame timeline, scene plan and 540x960 MP4 render
were queued, but their relay commands were repeatedly claimed without
terminal acknowledgements. No new 3D output project or MP4 was visible.

## Fix

SQLite durable result outbox v2 preserves all previous result rows while
adding a bounded retry timestamp and count. After a failed relay ACK,
the exact saved result is temporarily deferred (5 seconds to 5 minutes)
so the next command may be acknowledged. Deferred receipts are not
deleted, fabricated, nor mistaken for success.

An independent serial Cinematic 3D worker dispatches install, save,
plan and render in order without being blocked by two older source
image inspector workers. Existing native job ownership and thermal
checkpoints remain the authority.

## Version and protected update identity

VersionCode 381 and versionName 3.8.1. The existing Android application
ID, pinned signing certificate and original keystore verification remain
unchanged; project data, MCP owner credential and offline archives
must be kept. Install the signed APK OVER 3.8.0; never uninstall,
clear storage or rotate the owner credential.

## Device acceptance

After installing, check native self-test / 3D capability terminal ACK,
then inspect builtin bundle and saved "duel_3d_v380" program identity.
Retain existing durable commands; do not recreate duplicate projects
unless a terminal result proves a real prior failure. Execute the
saved source-independent 3D procedural-mannequin duel and verify its
actual frames/MP4 via native VideoStudio render inspection. The 3D
mannequins do not reconstruct the owner's 2D original swordsmen.
Green CI and a queued render are not cinematic acceptance.
