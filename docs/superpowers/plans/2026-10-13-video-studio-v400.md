# Video studio 4.0 Implementation Plan

> For agentic workers: Use superpowers:subagent-driven-development for independent components; root owns all Gradle runs, integration, reviews and artifact publication.

Goal: Deliver the named redesigned APK with existing fixes and app-key-based encrypted backup task delivery.
Architecture: Keep native operation/render ownership. Shared StudioUi components modernize Main/Sketch. Authenticated serverless GitHub packets feed the same native controller; complete encrypted receipts use a separate bounded direct metadata path.
Tech Stack: Native Java/Android Canvas/Media3/SQLite, Node crypto/GitHub API, existing original-key Android workflow.
Spec: docs/superpowers/specs/2026-10-13-video-studio-v400-design.md

Global constraints: Package/signer/protocol identity unchanged; no private owner/app key in Git/APK/logs; no Gallery; no blind side-effect replay; no missing backend/phone/creative success claim. Owner authorizes autonomous design/implementation and accepts fresh relay history; retain phone projects.
Review focus: narrow/large-font controls, human/live takeover, forged/expired packets, duplicate/conflicting IDs and offline/large/fair receipt delivery.

1. Shared native UI and MainActivity: create StudioUi with BG,SURFACE,SURFACE_ALT,TEXT,MUTED,ACCENT,BORDER and styleButton(Button,boolean); modernize main screens/navigation/search while preserving action handlers. Verify touch bounds and real navigation.
2. Sketch Studio: use StudioUi; compact header/canvas/filmstrip/playback; group all existing file/brush/layer/cel/workspace operations in touch menus. Preserve session/lifecycle/undo/live controls. Verify canvas space and function reachability.
3. Native backup: add NativeAgentMailboxCrypto, NativeAgentMailbox (poll/persist/replay/receipt publishing); integrate into ControlService and expose AppProtocol appKey plus Share action. Crypto verification/revision/native journal are before effects. Follow exact contracts in spec. Write malformed/expiry/replay/Node interoperability tests first; root runs red/green.
4. Node helper: create scripts/native-agent-mailbox.mjs with exported encodePacket/decodePacket/encodeReceipt/decodeReceipt/channelForKey and CLI send/status (key file only). Publish ciphertext on dedicated branch via gh or injected token without leaking values. Retrieve bounded direct-API metadata receipt pages. Real crypto/intent/collision/transport tests, no credential required for synthetic tests.
5. Branding/build: change launcher resources and version400/name4.0.0; add workflow_dispatch without modifying signer or main publishing conditions. Integrate all components and focused checks, fresh independent review, one full native+Node suite, exact source commit/PR and one signed CI APK.
6. Deliver: fetch artifact, verify public certificate/package/version/label/SHA/retained assets, publish clearly labelled prerelease APK. Stage one read-only encrypted native health command on the real backup path only after app_status. Phone execution after installation remains a separate gate.
