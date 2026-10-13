# 3.7.4 / VideoStudio native MCP command recovery

## Real device failure evidence

The stable MCP v3 relay showed the user's original Test 1 project and an
online VideoStudio 3.7.3 device, but **seven submitted native commands**
(including get_state, self_test, source_rig_status and a Test 1 render) remained
\`claimed\`, with 40+ claim renewals and no terminal ACK or verified MP4.
The native heartbeat later became stale/offline. Existing media remained.

The failure is **not** proof the rig editor or render backend is functioning.
These changes specifically address plausible claim floods and a durable
result-queue head-of-line blocker. A real-device 3.7.4 test is still mandatory.

## Focused reliability protections

- The command poller now dispatches each command ID at most once per
  two-minute lease-replay window in process, suppressing repeated
  \`main.post\` to the Android Looper. Process restarts reset only that
  in-memory guard; the persisted journal/outbox remain authoritative.
- Claimed commands no longer induce a hot poll/repost loop. A bounded
  650 ms backoff prevents busy polling while server-side claims remain.
- A relay error for one durable result no longer prevents delivery of
  every other result in the outbox batch; the original result is retained
  for retries. Reporting uses sanitized HTTP status or generic text,
  never keys, bearer tokens or source paths.
- Unit tests reproduce 1000 repeated leases, retry after expiry,
  acknowledgement and failed-post recovery, and bounded memory.
- Existing package identity, signing keystore/certificate and owner
  credential, original source files, private project database, thermal
  gate and gallery privacy boundary are unchanged.

## Mandatory update and device acceptance

This release is versionCode 374 / versionName 3.7.4. Confirm the pinned
certificate verification in CI, then install over 3.7.3 WITHOUT uninstalling.
Verify actual app version and retained original Test 1 (23 original assets).
Send one \`get_state\` and \`self_test\`, then one
\`source_rig_status\`; require all three to become terminal on relay with
no duplicate native executions. Verify the native result outbox drains.
Only then retry exactly one Test 1 animation; inspect the new output MP4
and the visual fidelity separately. A successful command ACK is not
cinematic animation acceptance. The intended cinematic fight still needs
source-authored black/white 16-point rigs, sword textures and foot contacts.
