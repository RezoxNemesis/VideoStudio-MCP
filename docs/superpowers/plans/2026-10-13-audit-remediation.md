# Audit Remediation Implementation Plan

> For agentic workers: Use superpowers:executing-plans to implement this plan task by task. Steps use checkbox syntax for tracking.

Goal: Repair the audited autonomous drawing/editor path and provide evidence for each closure without promoting source-only checks into phone acceptance.

Architecture: Keep the existing Worker/DO identity, chunk storage and native editing/rendering facades. Add focused diagnostics and capabilities at their owning layer. Run focused regressions before full verification; defer another signed APK until production command admission works.

Tech stack: Java/Android Canvas/Media3/SQLite, JavaScript MCP SDK/Cloudflare SQLite Durable Objects, existing Gradle/signing workflow.

Spec: docs/superpowers/specs/2026-10-13-audit-remediation-design.md

## Global constraints

- applicationId `com.rezoxnemesis.videostudio.next`; stable protocol v3 owner identity.
- Original signer SHA256 7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd.
- Original artwork/projects preserved; Gallery browsing forbidden; source/revision admission retained.
- No deployed/phone/creative pass from a local test. Missing credentials remain a deployment blocker.

## Review focus

- Sleeping executor must retain accepted commands without claiming visible execution.
- A terminal command carrying a queued job must not imply finished native output.
- Missing deployment credentials must not produce a successful deployment run.
- Compressed/source imports must remain checksum-bound and bounded against malformed expansion.
- Dense live strokes and editable text must preserve order, native output and human controls without UI-thread geometry floods.

## Task 1: Relay identity and execution evidence

Files: src/relay-diagnostics.js, src/native-result-pages.js, src/index.js, scripts/relay-diagnostics-test.mjs, scripts/bake-personal-client.mjs, .github/workflows/deploy-worker.yml.

Interfaces: relayRelease(env) returns source/storage identity; commandExecution(command) returns transport/job/output states; commandBacklog(rows,time) returns status counts/ages.

- [x] Write failing tests: job admission cannot imply completion; pending/running/backlog counts and expired leases remain accurate; relay identity exposes known/unknown explicitly.
- [x] Run focused Node tests and observe failures.
- [x] Implement pure diagnostics and wire public release/status/capabilities/results without changing owner namespace or accepted commands.
- [x] Add deployment credential failure and post-deploy fingerprint readback.
- [x] Run focused tests plus actual local MCP/SQLite backlog and cold-restart harness; commit the deliverable.

## Task 2: Native sketch capabilities and import scale

Files: SketchDocument.java, SketchEditOps.java, SketchFrameRenderer.java, SketchStudioActivity.java, SketchFrameManifestImporter.java, SketchLivePlayer.java, native/JS contract tests.

Interfaces: existing native add_object/update_object gains bounded editable text; sketch inspect gains explicit per-frame content/geometry coverage; exact-file packet import retains native edits and provenance.

- [x] Write failing native tests for editable text, frame-content gaps, compressed packet expansion and streamed source hashes.
- [x] Implement shared rendering/touch/MCP paths with conservative defaults and version floors.
- [x] Reproduce dense live preparation on the UI path, move expensive detached geometry preparation to the existing reader lane with lifecycle fencing.
- [x] Verify native pixels/persistence, ordered dense saved-event presentation, import replay and privacy; commit the source deliverable. Installed-phone latency remains unverified.

## Task 3: Remaining editor/animation paths

Files: NativeRenderEngine.java, StudioPixelEffect.java, MainActivity.java, source rig/choreography classes and appropriate native regressions.

- [x] Trace existing disabled/editor/rig capabilities to actual render ownership.
- [x] Implement supported missing functions end to end, with focused output/geometry regressions.
- [x] Keep non-implemented or unverified operations explicit; do not substitute mannequins for original reconstruction.
- [x] Update all 71 ledger states with exact evidence and external/creative blockers.

## Task 4: Verification and compatible artifact

- [x] Independent review; resolve material findings.
- [x] Run full Node/native regressions once stabilized and verify every executed result.
- [ ] After production relay admission passes, build through the pinned signing workflow and verify APK identity and original assets. This gate is deferred while production is blocked; another APK cannot update the Worker.
- [x] Publish reviewable source PR #73 and closure ledger. No new APK or main merge; the existing signer and prior branches remain intact.

## Current acceptance boundary

The owner requires all issues resolved, without routine approvals. Work proceeds autonomously, but no missing deployment credential or device/output proof may be invented. Production MCP admission and ACK both fail before Android despite a connected 3.9.5 phone. Existing Cloudflare credential requirements are declared but unbound; deployment/readback and actual phone workflows remain blocked. Independent source improvements are reviewable separately, with all 71 records retained in docs/AUDIT_REMEDIATION_396.md.

Verified source evidence: 663 Android tests / 145 suites, 174 Node tests, 306 smoke checks, Worker dry-run and independent read-only review. Local SQLite cold-restart proof retained 36 accepted requests and 18,432,000 synthetic ACK bytes. No real phone/output/creative acceptance or Worker deployment inferred from these checks.

Current deployment attempt: GitHub run 38376968160 from reviewed commit 3534eff failed at the Cloudflare credential gate before any build/deployment. Existing bindings are required; repeated attempts cannot supply them.
