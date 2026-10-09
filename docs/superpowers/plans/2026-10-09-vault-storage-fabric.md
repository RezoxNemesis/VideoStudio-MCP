# Vault Storage Fabric Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in this session. Steps use checkbox syntax for tracking.

**Goal:** Replicate verified Vault chunks and manifests across five owner-connected storage folders through owner and MCP controls.

**Architecture:** A bounded streaming replica engine validates local and remote object hashes and commits a SQLite location index. A SAF blob adapter receives only existing profile capabilities. Service jobs and owner media-bin controls share one implementation.

**Tech Stack:** Java 17, Android API 29+, SQLite, DocumentsContract, JUnit/Robolectric, existing durable JobManager/MCP.

**Spec:** docs/superpowers/specs/2026-10-09-vault-storage-fabric.md; original bundle sections 17, 20, 30–32.

## Global Constraints

- At most five owner-connected profiles; no Gallery enumeration or caller-supplied folder URI.
- All sizes/offsets use long; streaming buffer at most 256 KiB.
- Reuse only checksum-verified replicas; publish location metadata after verification.
- Preserve originals and local Vault objects; enforce current project/asset scopes and STOP.
- Quotas remain unknown unless actually reported; no fake cloud/network evidence.

## Review Focus

- Remote corruption after a successful upload must be detected before a retry reuses it.
- Cancel after one verified copy must resume the remaining copies without duplicating it.
- Identical plain chunks must deduplicate without losing logical manifest order.
- An edited/disconnected source/profile must fail safely rather than silently rebind work.
- Manifest replication must finish before the whole operation is marked complete.

### Task 1: Verified restart-safe replication engine

Create VaultReplicaStore.java and VaultStorageFabric.java; test VaultStorageFabricTest.java.
Interfaces: BlobStore.resolve(profileId,name,token), put(profileId,name,token,File,Progress), repair(profileId,location,File,Progress) -> String location; open(profileId,location) -> InputStream; delete(profileId,location). VaultStorageFabric.replicate(manifestId,List<String> profiles,int copies,Progress) -> JSONObject verified result. VaultReplicaStore stores manifest/object/profile/URI/bytes/hash/proof timestamp.

- [x] Write tests with actual local Vault chunks and file-backed BlobStore for distribution/hash/restart/corruption/cancel/quota.
- [x] Run focused suite and verify intended RED before implementation.
- [x] Implement bounded streaming verification, distinct-profile allocation, durable replica index and manifest replication.
- [x] Run GREEN and commit the verified engine.

### Task 2: Real folder adapter and shared controls

Create DocumentTreeBlobStore.java; update VaultManager, ControlService, MainActivity, OwnerAccessPolicy and src/index.js. Tests extend scope/service/relay cases.

- [x] Implement app-namespace-only SAF object creation and read-back verification, using existing connected profiles.
- [x] Add owner Vault replication dialog and durable autonomous MCP job/recovery routing.
- [x] Return durable verified replica status through vault_inspect without exposing unrelated folders.
- [ ] Run current-source Android/Node/Worker checks, bounded independent review, publish exact tree and verify matching APK/device CI.
- [ ] Append continuity with measured local/provider evidence and explicit direct-OAuth/device limits; continue the remaining blueprint.

2026-10-09: Original RED 6/6, review RED 4 native/1 relay, then focused recovery/migration/real DocumentsProvider GREEN 30 cases and full current-source GREEN 222. Node 282/20/21 and actual Wrangler bundle pass. Database v2 token intents, in-place repair, temporary failure retention and scoped discovery are included. Full matching-source Gradle/APK and seven-case device CI still pending publication; direct provider accounts/network evidence is pending.

Read-only independent follow-up closes all three Important storage findings; no remaining Important issue within this bounded review. Owner dialog suite after null-safe metadata display passes 10 cases.
