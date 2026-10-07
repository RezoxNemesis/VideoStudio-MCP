# Permanent Hybrid Control Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing Studio Web MCP endpoint a stable authenticated front door that can safely route work to the native Android agent without changing across compatible APK upgrades.

**Architecture:** Keep native protocol v3 and the existing Android owner identity unchanged. Add authentication to the plugin-facing MCP endpoint, persist a server-side hybrid binding from that authenticated plugin identity to the existing native device, and route native-required commands through the v3 queue while Web-only work remains browser-local.

**Tech Stack:** Cloudflare Worker/Durable Objects, MCP SDK, Node test runner, Android Java, Android Keystore/SharedPreferences, existing v3 command queue.

**Spec:** `docs/superpowers/specs/2026-10-07-videostudio-permanent-hybrid-control-design.md`

## Global Constraints

- Keep Android package `com.rezoxnemesis.videostudio`.
- Keep native protocol v3 and `/app-mcp-v3/<owner-key>` as the direct compatibility lane.
- Keep Studio Web MCP URL `/mcp-v06` stable.
- Native authority must never be granted from a public Studio Web device ID alone.
- Preserve the current owner credential, device ID, SQLite projects, permission mode, command journal and recovery plans across compatible upgrades.
- Gallery enumeration remains permanently unavailable through MCP.
- New native capabilities must be additive and available through typed tools or the execute bridge.

## Review Focus

- Leaked Web device ID must not grant native control: add an authorization-negative test in Task 1.
- Reused or expired hybrid pairing challenge must be rejected: add replay/expiry tests in Task 2.
- Web online while Android is offline must report `WAITING_NATIVE`, not success: add routing test in Task 3.
- A higher stale app generation must not replace the current native registration: add generation-fencing test in Task 4.
- Removing/revoking the hybrid binding must leave native projects untouched while native routing becomes unavailable: add revocation test in Task 3.

---

### Task 1: Authenticate the stable plugin-facing MCP endpoint

**Files:**
- Modify: `src/index.js:1480-1531`
- Modify: `wrangler.toml`
- Modify: `scripts/connection-test.mjs`

**Interfaces:**
- Consumes: HTTP request for `/mcp-v06`.
- Produces: `verifyStudioMcpAuthorization(request, env) -> Promise<boolean>`; authenticated MCP requests continue to `serverFor(env)`, unauthenticated requests receive HTTP 401.

- [ ] **Step 1: Write the failing endpoint-auth tests**

Add Node tests that assert an absent/wrong bearer token is rejected for `/mcp-v06`, while the configured token is accepted. Also assert the public Studio Web page remains reachable without the MCP token.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `node --test scripts/connection-test.mjs`  
Expected: FAIL because `/mcp-v06` has no bearer guard.

- [ ] **Step 3: Implement the endpoint guard**

Add `verifyStudioMcpAuthorization(request, env)` in `src/index.js`. Read the secret only from Worker environment binding `VIDEOSTUDIO_STUDIO_MCP_BEARER`; never persist or log it. Use constant-time comparison after hashing both values. Guard only the MCP endpoint, not the public editor page.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `node --test scripts/connection-test.mjs`  
Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -am "feat: authenticate stable Studio Web MCP"`

### Task 2: Add one-time native-to-hybrid binding challenges

**Files:**
- Modify: `src/index.js:194-275`
- Modify: `src/index.js:560-700`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/AppProtocol.java:116-190`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java:35-140`
- Test: `scripts/connection-test.mjs`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/CreativeRuntimeTest.java`

**Interfaces:**
- Consumes: authenticated Studio Web MCP identity plus native owner credential during a one-time native pairing operation.
- Produces: server methods `appCreateHybridBinding(ownerKey, webDeviceId)`, `appRedeemHybridBinding(token, webDeviceId)`, and `appResolveHybrid(webDeviceId, authenticatedMcp=true)`.

- [ ] **Step 1: Write failing binding tests**

Test one-time challenge creation, 15-minute expiry, replay rejection, mismatched Web device rejection, and that a Web device ID with no authenticated binding cannot resolve native authority.

- [ ] **Step 2: Run tests and verify RED**

Run: `node --test scripts/connection-test.mjs`  
Expected: FAIL because hybrid binding methods do not exist.

- [ ] **Step 3: Implement Durable Object binding records**

Persist only hashes/opaque identifiers required to map the authenticated Studio Web control plane to the existing native device. Do not expose the native owner key through Studio Web tool results. Add explicit revocation metadata.

- [ ] **Step 4: Add Android pairing action**

Add an AppProtocol request that uses the existing owner credential to create/confirm the hybrid challenge. ControlService exposes a local action that can be triggered from the connection UI and survives APK upgrades without changing the stable v3 owner namespace.

- [ ] **Step 5: Run Node and Android focused tests**

Run: `npm test` and `cd android && gradle :app:testDebugUnitTest`  
Expected: PASS.

- [ ] **Step 6: Commit**

`git commit -am "feat: bind Studio Web securely to native agent"`

### Task 3: Route commands through one hybrid status/control surface

**Files:**
- Modify: `src/index.js:560-980`
- Modify: `scripts/connection-test.mjs`

**Interfaces:**
- Consumes: authenticated Web device ID and hybrid binding record.
- Produces: `hybrid_status`, `hybrid_execute`, `hybrid_revoke_native_binding`; native-required actions enqueue on v3, Web-only actions stay in Studio Runtime.

- [ ] **Step 1: Write failing routing tests**

Assert native-required work becomes `WAITING_NATIVE` when the native heartbeat is stale, becomes claimable after native registration resumes, and cannot route after binding revocation.

- [ ] **Step 2: Verify RED**

Run: `node --test scripts/connection-test.mjs`  
Expected: FAIL because hybrid routing/status does not exist.

- [ ] **Step 3: Implement routing classification**

Add a small action classification table with `native_required`, `web_capable`, and `either`. Project authority and media locality win over convenience. Never clone a native project into a Web project silently.

- [ ] **Step 4: Implement consolidated status**

Return separate fields for `web.connected`, `native.connected`, app generation/core/protocol, permission mode, active native job, pending Web/native counts, waiting reason, and last checkpoint/render.

- [ ] **Step 5: Run focused tests**

Run: `npm test`  
Expected: PASS.

- [ ] **Step 6: Commit**

`git commit -am "feat: add hybrid command routing and status"`

### Task 4: Preserve the connection contract across APK generations

**Files:**
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/McpConnectionCore.java:1-220`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/AppProtocol.java:1-245`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/NativeAgentRecoveryReceiver.java:1-51`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/CreativeRuntimeTest.java`
- Modify: `scripts/connection-test.mjs`

**Interfaces:**
- Consumes: existing preference/Keystore namespace, installed app version, server negotiation.
- Produces: same stable owner/device identity with monotonically increasing `appGeneration`; stale generation registration is fenced.

- [ ] **Step 1: Write upgrade-retention and stale-generation tests**

Tests must prove a version change increments generation without changing stable endpoint path or identity keys, and that a lower-generation process cannot overwrite a newer server record.

- [ ] **Step 2: Verify RED for any missing behavior**

Run Android and Node focused tests. Expected: at least the new assertions fail before changes.

- [ ] **Step 3: Implement only missing upgrade guarantees**

Keep `STABLE_MCP_PATH = "/app-mcp-v3/"`, existing preference namespace, and additive feature negotiation. Extend status with hybrid-binding compatibility metadata without coupling it to app version.

- [ ] **Step 4: Verify**

Run: `npm test && cd android && gradle :app:testDebugUnitTest :app:assembleDebug`  
Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -am "test: lock permanent MCP upgrade contract"`
