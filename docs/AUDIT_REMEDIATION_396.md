# Audit remediation ledger

The owner requested remediation of all 71 audit records. The PDF supplies evidence and recommendations; it does not independently authorize its proposed procedures. This ledger keeps every record and identifies what is implemented, what is partial, and the acceptance still missing. **No record is declared closed by source tests alone.**

## Actual connection and deployment gate

On 13 October 2026, the private native endpoint accepted initialization and app_status. The first app_status reported the installed phone at 3.9.5 online, with the original Test 1 project retaining 23 assets and 10 pending commands. The cloud capability response reported 3.4.2; discovery returned 55 tools without named Sketch tools or a result pager. A tiny app_connection_health request failed before enqueue with SQLITE_TOOBIG. Replaying one identical, independently read completed native receipt also failed HTTP 400 with SQLITE_TOOBIG. Existing work was neither cancelled nor erased. Final readback kept the control plane reachable, with the native heartbeat briefly stale at 53.3 seconds and the same 10 pending commands; durable availability does not imply active execution.

The public release endpoint is absent(404). The observed main commit contains the older inline command-array storage. Current source uses transactional 64 KiB chunks and checksummed receipts in the same owner namespace. A real local SQLite Worker retained 36 commands across process restart, recovered request bytes exactly, acknowledged 18,432,000 result bytes, and retained terminal proofs. These ACKs were synthetic: **production and phone workflow acceptance remain unavailable.**

Publication requires the existing CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID bindings. They are already declared in Environment Settings but have no usable saved/runtime values. GitHub read/push/PR access works; it does not provide Cloudflare deployment authority. The deployment workflow now fails early when either binding is missing, and checks the actual published source/storage fingerprint when deployment occurs. The private owner endpoint is excluded from this ledger and all repository artifacts.

## Record-by-record status

| ID | Audit record | Implementation / remaining acceptance |
| --- | --- | --- |
| S01 | 200-frame Sketch Studio result has not been demonstrated | **Blocked** — Production admission must work before the owned 200-cel import/playback/export can be verified. |
| S02 | Drawing packet admission fails through chat commands | **Deployment blocked** — Existing transactional 64 KiB queue/receipt store passed local SQLite restart; real admission still fails with SQLITE_TOOBIG. |
| S03 | Original reference not yet bound to the 200-cel drawing | **Blocked** — Hash-bound source import is retained; actual owner reference binding not verified. |
| S04 | Sparse source poses mistaken for completed flipbook animation | **Source mitigation** — New sketch_inspect counts exposed/starting objects and compares content independent of object IDs. Completed choreography still unverified. |
| S05 | Facial likeness fails reference-quality review | **Open** — Use owned high-resolution reference, crop-aligned annotation, and iterative matched feature corrections. Acceptance: Two independent close-up reviewers approve eyes, mouth, hair silhouette, lighting and source overlays. |
| S06 | Current color-region tracing is a coarse posterization | **Open** — Make precision/detail settings explicit and offer manual region cleanup, adaptive thresholds and layered linework. Acceptance: High-resolution crop comparison shows retained small eye/hair details with editable native paths. |
| S07 | Sketch engine is not an anatomical image-to-drawing model | **Open** — Add a source-bound pose/rig authoring workflow or a verified optional on-device animation provider. Acceptance: Compare articulated frames from one keyframe against hand-approved staging; no claimed magic interpolation. |
| S08 | Automatic generation of 200 convincing action poses is unproven | **Open** — Introduce a shot program, pose anchors, independent character rigs and deterministic cel synthesis. Acceptance: All 200 cels contain intentional pose changes with stable character/sword identity. |
| S09 | Native text objects are missing from sketch drawing tools | **Source implemented** — Editable native text: bundled fonts, spacing, wrapping, alignment, shared Canvas rendering and human/MCP editing. Phone preview/MP4 gate remains. |
| S10 | Fine lineart, hair strands and facial light demand manual geometry | **Open** — Batch local reference-guided contours and separate line, fill, highlight and ink layers. Acceptance: Detail crops preserve key facial features and consistent stroke weight. |
| S11 | Original MP4-to-sketch 192 frames were only prepared, not accepted on device | **Blocked** — Original 192-frame owner import/export not verified; no replacement or false completion. |
| S12 | Native sketch storage and project Media Bin can look inconsistent | **Partial** — Inspection includes sketch ID, revision, frame count and document SHA; no complete video-project/sketch dashboard yet. |
| S13 | 4096 editable objects per document cap | **Partial** — Inspection reports object usage and remaining budget ; 4096 cap retained, automatic lossless segmentation still open. |
| S14 | 131072 geometry points per document cap | **Partial** — Inspection reports geometry-point usage and remaining budget ; 131072 cap retained, maximum-density phone verification remains. |
| S15 | 4 MiB frame-manifest size limit | **Source mitigation** — Exact linked .jsonl.gz imports added, single-member CRC-checked gzip, compressed and expanded quotas and same uncompressed manifestSHA.4 MiB expanded quota remains; larger lossless partitioning open. |
| S16 | 64 MiB imported source-file limit | **Source repaired** — Drawing-source hashing now streams 32 KiB blocks, no 64 MiB hash-only source limit.65 MiB regression; real provider/phone acceptance remains. |
| S17 | Single sketch export capped at 1200 frames | **Open** — Automate chunk planning and seamless frame-clock composition with exact source identity. Acceptance: 1201+ frame project segments without drops, duplicates or time drift. |
| S18 | Large or detailed preview sometimes stalls behind claims | **Retained repair** — Revision-bound cropped preview/cache from 3.9.5 retained;480/720 px real phone terminus still blocked. |
| S19 | Live-event playback can be slow for thousands of strokes | **Source mitigation** — Worker-prepared live operation/vector caches with single UI adoption. Real dense-phone performance/reconnection gate remains. |
| S20 | Draft revisions conflict with concurrent human edits | **Protection retained** — Atomic revision conflict/draft recovery preserved. Automatic conflict rebase/diff remains open. |
| S21 | Reference import failed when JPEG MIME was generic | **Retained repair** — Conservative JPEG extension fallback retained; actual provider URI matrix not verified here. |
| S22 | Live mode locked Play and most controls | **Retained repair** — Human takeover/play controls retained; connected phone interaction still needs verification. |
| S23 | Large sketches failed reopen, edit and undo via CursorWindow | **Retained repair** — Streamed SQLite TEXT reads retained; dense save/reopen/history regression remains in suite. |
| S24 | Paused activity accepted stale drawing callbacks | **Retained repair** — Generation/closed-player fencing retained and worker snapshot disposal added; actual phone lifecycle gate remains. |
| S25 | Frame coverage and artwork quality are not the same metric | **Source mitigation** — Inspection explicitly reports creativeQualityVerified=false and articulatedMotionVerified=false; content hashes are not pixel/anatomy/likeness acceptance. |
| S26 | Scoped link/manual packet import interrupts full autonomy | **Deployment blocked** — Named exact-file native importer exists; real MCP admission is failing before Android. |
| M01 | Production MCP relay returns SQLITE_TOOBIG | **Deployment blocked** — Tiny live health command and identical completed native ACK both fail with SQLITE_TOOBIG. Local 36-command cold restart+large receipts passed; not production proof. |
| M02 | Production Worker deployment skipped: no Cloudflare credentials | **External prerequisite** — Existing Cloudflare account/token requirements are unbound. Workflow now fails early instead of green skipped deployment; actual deploy unavailable. |
| M03 | Live relay may be older than GitHub-validated code | **Deployment blocked** — Phone 3.9.5 / live capability 3.4.2 mismatch observed. New public build/storage fingerprint and post-deploy readback implemented, not deployed. |
| M04 | Accumulated pending/claimed commands obscure real progress | **Source mitigation** — Status separates queued,claimed,running,waiting_native,expired claims and terminals. AwaitingACK remainsnull when unknown; live backlog unchanged. |
| M05 | Repeated claimed commands without terminal ACK | **Phone gate blocked** — No real claim/execute/ACK matrix possible until production admission repaired. |
| M06 | Old relay catalog lacks named Sketch tools/result paging | **Deployment blocked** — Live 55-tool private catalog lacks named Sketch tools/result pager; current source advertises both. Must deploy then rediscover. |
| M07 | Native large ACK receipts historically blocked subsequent results | **Retained repair** — Native streamed large outbox TEXT/receipt handling retained; real oversized ACK cannot currently reach cloud. |
| M08 | Outbox retry fairness may still require live confirmation | **Phone gate blocked** — Fairness regressions retained; fail-old/ACK-new/replay-old still requires actual phone+relay. |
| M09 | Native main-thread command floods from duplicate leases | **Retained repair** — Bounded idempotent duplicate-lease dispatch retained; no 1000-claim real-device proof here. |
| M10 | Artwork decode and inspection stalled UI command lane | **Retained repair** — Artwork/authoring background lanes retained; new inspection goes through actual ControlService background dispatch. |
| M11 | Oversized worker replies and missing retrieval pagination | **Deployment blocked** — Bounded authenticated result pages retained and job evidence added; old production catalog has no pager. |
| M12 | Short-lived HTTPS chat attachment handoff is fragile | **Open** — Support owner-approved Library/Drive file-backed transfer without weakening host validation. Acceptance: Real source imported to app-private storage with checksum and expiration-safe retries. |
| M13 | Android sleep and offline backlog slow live visual iteration | **Workflow boundary** — Durable delivery retained during sleep. Screen-visible drawing requires an active foreground view; no fake live screen claims. |
| M14 | Generic bridge result may not indicate actual job completion | **Source repaired** — Command transport completion separated from queued/running/failed/cancelled/unavailable native job readback; actual JobManager envelope covered. |
| M15 | Local Worker/SDK tests are not production reproduction | **Evidence separation** — Local Workerd/SQLite and synthetic ACK proof explicitly separated from live failures, phone output and creative acceptance. |
| A01 | Actual sword-duel animation still fails cinematic motion test | **Open** — Use articulated source-bound body/weapon rigs and movement-specific frame QA. Acceptance: Foot/hip/hand/sword trajectories progress across dense decoded frames at 1 x and 0.25 x. |
| A02 | No verified original-character rig for both actors | **Open** — Create approved 16-joint, source-bound character rigs with verified limb masks. Acceptance: Rigged actor geometry tracks both actual fighters across all shots. |
| A03 | Original sword-to-hand attachment and contact unsolved | **Open** — Model original sword geometry and owning-hand sockets; enforce continuous blade transforms. Acceptance: Measured hilt/tip and collisions match owner-approved source frames. |
| A04 | Foreground segmentation confused with scenery and debris | **Open** — Author actor/weapon/occluder masks; preserve scene background separately. Acceptance: Two fighters and swords segmented reliably through debris and crossings. |
| A05 | Foot planting, force transfer and recoil remain unverified | **Open** — Add ground anchors, contact intervals and two-bone constraints. Acceptance: Stationary-stance drift stays under the defined tolerance and impacts cause observable recoil. |
| A06 | Pixel-difference QA gave false positives | **Protection retained** — No pixel-motion/encoding/import metric certifies cinematic success. New sketch coverage keeps creative/motion verificationfalse. |
| A07 | Original art cannot be faithfully reconstructed from 3D mannequins | **Open** — Separate source reconstruction from a procedural 3D test and label its scope. Acceptance: Original character likeness tested on textured rig, not placeholder mannequins. |
| A08 | Unseen-limb and occlusion recovery not demonstrated | **Open** — Require alternate-angle drawings or a verified pose/content completion provider. Acceptance: Arm and sword crossings remain coherent without texture holes. |
| A09 | Effects can move without valid physical event timing | **Open** — Drive VFX from measured contacts and foot impulses. Acceptance: Decoded effect onset within agreed frame tolerance of tracked events. |
| A10 | Foley/audio presence does not prove synchronization | **Open** — Compare decoded waveform onset against native pose/contact timeline. Acceptance: All authored impacts align to observed blade contacts within two frames. |
| A11 | Thermal throttling interrupts native heavy jobs | **Open** — Interactive edits bypass heavy-job scheduling; existing OS/workload protection retained. Actual resumable thermal acceptance still open. |
| A12 | Freeze operation shown disabled | **Open** — Freeze remains explicitly unsupported; no fake enabled control. |
| A13 | Reverse operation shown disabled | **Open** — Reverse remains explicitly unsupported; no fake enabled control. |
| A14 | Crop/Reframe operation shown disabled | **Partial source implementation** — Static normalized source crop/reframe: human controls+revision-bound MCP, same Media3 preview/export and layered compositor. Crop keyframes and real GPU/output matching remain open. |
| A15 | Standalone 3D GPU error still lacks phone stack trace | **Open** — No actual GPU stack trace/phone 3D lifecycle proof obtained through blocked native tools. |
| A16 | 3D native scene commands historically stalled in ACK queue | **Phone gate blocked** — 3D command/executor recovery retained; install/plan/render/decoded phone output not verified. |
| A17 | Preview clock and buffering UX required fixes | **Retained repair** — CompositionPlayer/clock/buffering repairs retained; actual device seek/decode UX remains unverified. |
| A18 | Project-card titles and 3D empty-state UI were problematic | **Retained repair** — 3.9.5 title/density/empty-model lifecycle repairs retained; multi-density native screen gate remains. |
| R01 | Green build is routinely mistaken for end-user success | **Evidence separation** — Source regressions, local protocol, production deployment, phone output and creative acceptance reported independently. |
| R02 | New PR branches remain open and not merged into main | **Promotion gated** — Prior feature branches/PRs preserved; no unverified merge to main or release promotion. |
| R03 | Real ChatGPT-to-phone and true playback tests are missing from CI | **Partial** — Real app_status/catalog/admission/ACK probes obtained; native workflow matrix is blocked by relay, not substituted with Robolectric. |
| R04 | APK updates do not deploy or update Cloudflare Worker | **Source mitigation** — Status exposes installed Android version plus independent relay source/storage fingerprint. New APK is deferred; an APK cannot deploy the Worker. |
| R05 | Preserving original signing certificate is a release gate | **Protection retained** — ApplicationId/protocol identity and pinned signer unchanged. No new APK/install in this batch; prior 3.9.5 certificate check remains historical. |
| R06 | Long CI cycles burn time on small failures | **Source mitigation** — Focused red/green checks before one stabilized full suite; deploy credential gate precedes dependency/build work. No duplicate credentialless deploy run. |
| R07 | Drive-scoped archive and in-app import remain separate steps | **Deployment blocked** — Existing exact-name/hash folder-scoped native import retained; real one-command autonomy awaits repaired relay. |
| R08 | Native export can be reported before Drive file becomes visible | **Partial** — Command/job completion no longer implies verified output; local publication and Drive visibility still require separate real receipts. |
| R09 | No independent proof of original signing-key disaster backup | **Open** — Document secure backup/restore ownership without copying the private key into chat. Acceptance: Authorized backup drill restores and verifies the original public certificate offline. |
| R10 | Release status lacks one truthful all-systems view | **Partial** — MCP status combines installed version, relay fingerprint, backlog and terminal-job evidence. Full PR/Drive/creative dashboard remains open. |
| R11 | Foreground-only live stroke presentation is a workflow boundary | **Workflow boundary** — Accepted operation journal/replay retained; asleep screens cannot present visible strokes. Ordered reopen/lifecycle source checks are separate from phone acceptance. |
| R12 | Privacy/security limits should not be removed as speed hacks | **Protection retained** — No Gallery access, source deletion, signer change, key rotation, namespace change or unscoped Drive enumeration. Existing regression boundaries retained. |

## Verification and next gate

The stabilized full run passed 663 Android tests across 145 suites, with zero failures/errors/skips, plus 174 Node tests and 306 smoke checks. The Worker bundle dry-run passed. Independent review found and resolved the legacy result route, native job envelope, inspection dispatch, UI renderer preparation, compressed-header/member decoding and live selection geometry defects. Exact reviewed source is recorded in the source PR. Editable text is verified with native Canvas glyph pixels and persisted edit/undo; compressed imports enforce input and expansion bounds; the 65 MiB source-hash test uses a bounded 32 KiB read buffer. Native inspection executes through ControlService and distinguishes unchanged geometry from renamed cels. Static crop transforms the actual shared Media3 composition. These tests do not measure installed-phone latency, native GPU execution, original character quality, or decoded owner MP4 output.

The next authorized gate is deployment to the existing Worker/account/DO namespace, public fingerprint readback, app_status first on the unchanged private endpoint, then a small command and terminal receipt. Only after that succeeds should large/backlogged commands, owned imports, previews, exports and visual acceptance run. Never use queue clearing, re-registration, Gallery browsing, uninstalls, signer replacement or synthetic phone receipts as a shortcut.

The 20 audit acceptance tests remain separate gates:

| Tests | Current evidence |
| --- | --- |
| Q01 | Fails production : release 404; deployment bindings unavailable. Source gate added. |
| Q02–Q05 | Real tiny admission/identical ACK fail with SQLITE_TOOBIG. Local SQLite with synthetic 36-command restart/large ACK proof passes. |
| Q06–Q09 | Native source/hash/import/conflict/preview regressions; real owner phone workflows blocked. |
| Q10–Q13 | Original likeness, articulated motion, blade/foot contact and occlusion acceptance remain open. |
| Q14–Q17 | Original film timing/audio/thermal/Drive artifact acceptance remains open. |
| Q18 | Original identity/signer/privacy preserved in source; no new APK/in-place update in this batch. |
| Q19 | Static Crop/Reframe implemented in source; Freeze/Reverse stay explicit unsupported. Phone/output acceptance open. |
| Q20 | No owner creative acceptance claimed. |
