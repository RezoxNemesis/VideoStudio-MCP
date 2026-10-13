# VideoStudio 3.7.5 — Android main-thread source inspection isolation

## Device evidence

On VideoStudio 3.7.4, the user requested Test 1 cinematic animation. The
native v3 bridge processed previously stuck commands and reported a decisive
render failure: the 00_Original_Standoff → 01_Black_Step sequence had too many
foreground proposals to isolate source objects without authored source masks.
Cinematic original-rig readiness was also false; exact source actor joint,
original sword and ground-contact annotations are still missing.

Subsequently, two \`inspect_source_artwork\` native commands (original, owned
source images) stayed \`claimed\` for repeated lease periods. A subsequent
\`get_state\` also stayed claimed while the heartbeat remained online.
The current ControlService invokes expensive image reads, oriented decoding,
source geometry verification, and image encoding synchronously inside
onCommand, which AppProtocol posts to Android's MAIN Looper. This provides
a plausible cause of main-thread starvation. It is not independently proven
without device main-thread stack traces.

## Changes

- Dispatch source inspector, source-rig preview/save/status, source program,
  storyboard planning and storyboard animation preparation off the main Looper
  to a bounded two-worker executor. Lightweight ping/get_state/self_test stay
  on the main loop to remain responsive.
- Do not change the original image files, app project IDs, native storage,
  relay endpoint, owner credential, stored application namespace, Gallery
  privacy wall, thermal safeguards, or update-signing certificate.
- Existing 3.7.4 duplicate-lease guard and non-blocking durable result upload
  remain active.
- VersionCode 375 / versionName 3.7.5; same pinned certificate, with CI's
  signing-continuity requirement unchanged.
- Unit regressions identify every offloaded source command and ensure
  lightweight health commands remain independent.

## Not an animation acceptance

This patch may restore tool responsiveness, but cannot infer invisible anatomy,
draw convincing new in-between poses, or solve two original fighters and their
weapons without source-bound actor annotations. A green APK build is not proof
of source-faithful cinematic action. Acceptance requires in-place installation,
real native self-test and source-image inspection/visual verification, followed
by a single properly sourced Test 1 output and dense frame-quality review.

Do not enqueue repeated whole-film renders before this diagnostic is verified.
