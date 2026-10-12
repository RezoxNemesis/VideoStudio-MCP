# Cinematic blueprint acceptance record

Priority: the owner's 20-page `VideoStudio_Cinematic_Animation_System_Blueprint_and_QA.pdf`, received 2026-10-12. The earlier [complete engineering blueprint](ENGINEERING_BLUEPRINT.md) remains intact. The previous implementation branch `studio/complete-editor-blueprint-20261010` remains at `7bbae6092622bb261fea4cab381cf5d793cd24db`; this repair starts from PR #67, `de8724ec9679ebfb7ca0f2fb98b2dabce2c0bf04`.

This document distinguishes implementation checks from acceptance of the actual Test 1 film. No synthetic fixture, successful encoding, pixel-change score, predicted joint trace or CI result is a cinematic acceptance certificate.

## Source and native access

The supplied PDF and failure MP4 are retained privately outside Git in `/workspace/engineering-review/cinematic-20261012/`. The MP4 is approximately 5.095 seconds, coded 1280×720 with a −90° rotation for portrait display. Reference images inside the PDF express intent; they are not substitutes for the 23 original imported images and 13 authored anchors.

The later `VideoStudio_Animation_Reference_and_9_Ordered_Storyboards.zip` is retained in the same private engineering directory under `ordered-nine-reference/`. All nine source hashes, literal 01–09 order, sizes and 864×1536 dimensions were verified against its manifest. Despite their `.png` names and declared PNG format, all nine originals contain JPEG data; bytes were preserved without conversion. The independent reference is H.264/AAC, 720×1280, 24 FPS, 192 frames and 8.000 seconds. The supplied overview is not a render or a source replacement. `WORKSPACE_SOURCE_VERIFICATION.json` records these workspace checks and explicitly leaves native import/acceptance false.

The new nine-frame library is available for source inspection. It does not provide the earlier Test 1 library of 23 originals/13 anchors, any verified native actor/weapon/contact profiles, or an installed app project. `adb devices -l` returned no attached device. No callable VideoStudio native connector is available in this session. Unavailable native access blocks authoring/importing verified app-owned masks and profiles, installed-state inspection, in-place update, actual app rendering/playback, decoded output measurement, thermal/reconnect QA and the app's folder-scoped Drive archive. The nine-frame packet additionally requires faithful airborne takeoff/landing; empty contact arrays are not accepted as an implicit airborne certificate. Authentic support/flight authoring and hidden-limb/occlusion evidence remain unresolved acceptance prerequisites. There is no Gallery enumeration, Python-produced replacement film or fabricated device result.

## Verification evidence

- A corrected focused baseline run executed 49 tests with 21 genuine failures. The first attempt also had a forked Robolectric proxy problem; that setup problem was corrected and is not counted as a product defect.
- Five additional tests against an exact clean `de8724e` source snapshot reproduced mesh alpha 255 instead of 128, abrupt source appearance switching, actor-only camera shake, a 60 FPS request becoming 30, and a 33 ms hold becoming 34 ms.
- Original failing JUnit XML and synthetic native PNG/JSON evidence are retained outside Git in `red-regressions/` and `characterization-red/` under the private engineering directory.
- The Gradle signing guard was executed without an original key and correctly failed: `Original VideoStudio signing key unavailable; refusing a replacement or unsigned APK`. Unit tests do not need an APK key. No replacement signing material was created.
- Final review regressions executed 41 tests with zero failures, including actual-frame cancellation retention, source-raster publication guards, body/weapon admission limits, explicit empty-contact rejection, 3-second/24-FPS planning, and stale/missing-registration draft repair.
- The complete local Android suite executed **359 tests in 87 suites, with zero failures, errors or skips**. JUnit XML and the exact summary are retained in `android-full-final/`; synthetic native Canvas PNG/JSON and generated PCM remain separately labelled. The native film fixture proves source-deformation behavior and zero-event ambience, not original cinematic acting.
- Final targeted Worker storage/route/privacy/race checks executed **51 tests with zero failures**. The complete final Worker suite also passed **306 smoke checks and 125 executable tests**; Wrangler dry-run bundling passed. The original-signer APK build succeeded on source `f68acb7` in [Android CI run 38213139946](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/38213139946). The downloaded artifact was independently checked with local aapt, apksigner and SHA-256. Subsequent changes repair only Worker personal-workspace compatibility; Android source is unchanged.
- Read-only review findings were repaired: arbitrary aspect cropping, empty cinematic contacts, environmental phone intent, obsolete beat/duration routing, stale-draft dead ends, destructive legacy render rejection, missing recovery command binding, renderer limits after allocation, intentional standoff handling, unredacted/oversized result pages, and stale device admission during alias convergence.

## R01–R12

| Requirement | Implementation scope | Native acceptance |
| --- | --- | --- |
| R01 source identity/order | Bounded source SHA-256 manifest, literal order/repeated anchors, explicit variant groups, exact-byte aliases and frozen recovery inputs | Workspace nine-frame package verified; earlier 23 originals / 13 anchors unavailable; native import/idempotence unverified |
| R02 segmentation | Editable actor and original sword polygons, distinct ownership and clean-world removal | Blocked: real masks, debris/occlusion annotations and overlays unavailable |
| R03 articulated rigs | Sixteen named source joints, validated polygons, shared physical skinning kernel, revision/digest guards | Synthetic engine checks only; original actor articulation unverified |
| R04 rigid original swords | Source RGB contour, original hilt/tip and owning-hand transform; no procedural replacement in cinematic path | Dense actual-frame attachment/contour measurements unavailable |
| R05 choreography | Twelve typed source-bound beats with independent actor pacing and ordered shot binding | Full original 12-beat acting, force transfer and style unverified |
| R06 in-betweens | Source-conditioned physical interpolation, source texture handoff and explicit provider admission | Real dense limb motion/occlusion QA unavailable; no neural-provider claim |
| R07 foot contact | Explicit stance intervals, source-bound ground anchors, reachable two-bone constraints | Actual stance drift/weight-transfer measurements unavailable |
| R08 world/depth | Shared camera transform and source/prop clean plate | Real world registration and crossing/occlusion order unverified |
| R09 camera | Common world/actor/prop transform and camera-disabled diagnostic scope | Camera-disabled original acting and final motivated framing unverified |
| R10 causal effects | Predicted contacts distinguished from captured/decoded events | Actual collision, foot impulse, effect onset and moving-region blur unavailable |
| R11 audio | Explicit event clock, valid audible PCM receipts, onset/peak provenance, rational tolerance | Final decoded AAC/visual contact sync unavailable |
| R12 render/provenance | 24/30/60 request plumbing, cumulative authored frame clock, immutable generations, frozen source/rig/program proofs, truthful evidence | Actual H.264/AAC cadence/duration/rotation, phone recovery and Drive archive unavailable |

## T01–T21

| Test | Gate / required evidence | Status |
| --- | --- | --- |
| T01 | Actual repeat import: 23 originals, 13 ordered anchors, no new duplicate user assets | BLOCKED: earlier 23/13 library and device absent; later nine-frame workspace hashes/order verified |
| T02 | Original WALK/RUN/STRIKE/IMPACT/RECOIL/FALL grammar; role-specific reaction | Local grammar checks PASS; later nine-frame narrative order inspected; actual output unavailable |
| T03 | Original masks overlay both fighters/swords/debris/occluded limbs | BLOCKED: real annotations absent |
| T04 | Exact authentic actor/weapon/contact/program admission before heavy work | Local source/admission regressions PASS; actual native-provider readiness unverified |
| T05 | Native source RGB, independent elbow/knee articulation | Synthetic native Canvas PASS; later nine originals inspected, source-specific rendering unavailable |
| T06 | Black/white head/weapon identity in every real frame | BLOCKED: original sequence absent |
| T07 | Actual first/last landmark reprojection | Local exact-endpoint checks PASS; authentic decoded landmarks unavailable |
| T08 | Registered city after compensating actual camera transform | Synthetic shared-camera Canvas PASS; real scene registration unverified |
| T09 | Cancel/retry/reconnect preserves source and prior outputs | Local frozen-proof/CAS/real-frame cancellation checks PASS; phone interruption blocked |
| T10 | Decode actual portrait H.264/AAC at requested 24/30/60 FPS | BLOCKED: native export/device absent; authored clock is not decoded PTS |
| T11 | Five key phases + ≥16 dense frames; ≥75% non-affine articulation | Local semantic-gate checks PASS; decoded original landmarks unavailable |
| T12 | Stance drift <1.5% actor height over 0.5 seconds | Local predicted constraint checks PASS; observed stance unavailable |
| T13 | Observed hilt error ≤2% actor height, continuous original blade | Native synthetic original-texture prop checks PASS; authentic decoded observations unavailable |
| T14 | Intended single blade contact followed by physical reaction | Authored/predicted events remain incomplete; real collision evidence absent |
| T15 | No unintended four-frame static runs / flipbook switching | Local coverage checks PASS; authentic dense native output unavailable |
| T16 | No texture swaps/ghosts during crossing limbs/actors/weapons | BLOCKED: native original crossing/occlusion evidence absent |
| T17 | Original genuine actor action with camera disabled | Diagnostic scope supported; actual comparison blocked |
| T18 | Contact sparks, physical foot dust, moving-region blur | BLOCKED: captured/decoded event evidence absent |
| T19 | Actual per-event waveform/visual alignment within ±2 frames | Local actual PCM/event-clock checks PASS; decoded MP4 synchronization unavailable |
| T20 | Independent original-film review at 1× / 0.25×, owner final acceptance | BLOCKED: new actual film absent; no approval requested for routine engineering |
| T21 | Actual portrait/product/landscape/comic regression | Complete local Android suite PASS; native source-specific samples unavailable |

## U01–U04 and release invariant

The package remains `com.rezoxnemesis.videostudio.next`. The build target is versionCode 372 / versionName 3.7.2, aligned with `AppProtocol.APP_VERSION`. This exceeds the inspected PR's 371, but the installed device version has not been read.

Pinned public signer SHA-256: `7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`, alias `androiddebugkey`. APK packaging requires `VIDEOSTUDIO_SIGNING_KEYSTORE` and this exact certificate. Tests work without it. The existing protected GitHub signing cache is available according to cache metadata; no key was copied into this workspace or exposed.

| Test | Status |
| --- | --- |
| U01 package/version vs installed manifest | Actual APK package/version verified (Next / 372 / 3.7.2); installed comparison blocked |
| U02 apksigner / original installed certificate | Original key restored in CI; actual APK certificate matches pin and local apksigner verification; installed comparison blocked |
| U03 unit checks / restored key / assembled artifact integrity | Local suites and actual protected APK build PASS; downloaded artifact SHA-256 verified |
| U04 durable secure backup of original key | BLOCKED: no accessible authorized backup location/key-management permission; repository secret-name API returned 403. Signing cache restoration is not disaster recovery. |

No uninstall, clear-data, downgrade, endpoint rotation or unverified main release is performed. The in-place device matrix (projects, 23 original assets, prior MP4s, owner identity, cached endpoint and interrupted jobs) remains blocked on native access.

## Milestones and next blueprint

M1–M6 remain **NOT ACCEPTED** until their original-source/native gates pass. Code and synthetic checks can resolve engine defects, but cannot certify original masks, acting, real phone output or release reliability. Missing provider annotations return `INSUFFICIENT_MOTION_PROVIDER`; explicit `source_deformation` and `environmental` modes retain their narrower scope.

The older complete engineering blueprint is preserved and remains queued behind this cinematic priority. The stopping condition allowed by the new PDF is a verified acceptance gate or a documented external blocker; the native/original-source gates above are documented blockers, not product completion.

## Later nine-frame reference packet

The nine input drawings show approach/readiness, acceleration, launch, contact at frame 05, follow-through/recoil, landing, recovery and reset. The decoded sixteen-sample reference strip was inspected independently; it shows changing limb/body poses and weapon action, rather than only camera motion. These observations guide future source authoring and comparison but are not measurements of a new VideoStudio export. The packet's required final PASS still depends on all-nine-frame native output, dense real limb/weapon/foot trajectories, decoded audio timing and independent full-speed/slow-playback review. The packet is not substituted for the earlier 23/13 Test 1 dataset.

## APK and actual local runtime evidence

Verified APK: package `com.rezoxnemesis.videostudio.next`, versionCode 372 / versionName 3.7.2, 127,108,639 bytes. SHA-256 `6a43ac191bac28caf9f716e676295cd232f068aa1f390b81c075f419b5879c0e`; public signer matches the pinned original certificate above. The CI proof records source head `f68acb7cc6014a4eddd463de019c4919cad57882` separately from the GitHub PR merge commit. No install/uninstall or native acceptance is claimed.

The first personal-website CI run found a real compatibility defect: its login and activity code still read legacy raw KV keys after device/queue migration to chunks. The personal store now uses shared transactional readers and retains ownership/session checks. Six new integration regressions passed alongside the existing personal suite (51 focused cases); the full Worker suite passed 125 executable cases plus 306 smoke checks. Actual local workerd/SQLite HTTP login, cloud persistence, isolation, conflicts, CSRF and native permission checks passed, followed by real MCP create/result retrieval and offline native queue visibility. This is loopback runtime evidence with isolated synthetic identities, not live ChatGPT/phone evidence.

The Cloudflare external build check fails without diagnostic details available through the GitHub check API; local and GitHub Worker validation/dry-run pass. A live Worker deployment is not claimed. Further CI results are linked from PR #67 rather than substituted for original cinematic acceptance.
