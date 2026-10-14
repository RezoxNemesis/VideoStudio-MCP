# Connect ChatGPT to Video studio

Install the signed Video studio APK and open its Native Agent connection settings. Tap **Share app key**. Treat the `vsapp1.` key as private: it authorizes commands on your phone. Keep your projects and the existing app installation in place.

A ChatGPT session needs terminal access to this repository and GitHub **write** capability for `RezoxNemesis/VideoStudio-MCP`. Sharing the key alone does not grant GitHub writing capability. No additional hosting account or MCP connector is needed for this route. The prior installed-phone route has authenticated completed receipts for native health, Sketch capability/listing, owned-project state and native imports. Those results do not establish installation of 4.2.0 or execution of its new encrypted media action; verify the installed version and each command's own receipt.

Save the shared key in a local private file outside the repository (permissions 600). The helper also accepts a JSON file containing `privateEndpoint` with the existing private `/app-mcp-v3/<owner>` address. Never paste the key into source, a public issue, or a command argument. Prepare action parameters in another private JSON file, then run:

```sh
node scripts/native-agent-mailbox.mjs send \
  --app-key-file /private/video-studio-app-key.txt \
  --action connection_health \
  --parameters-file /private/parameters.json
```

The helper prints the stable command ID and encrypted delivery status. Delivery is not terminal execution. Keep the ID; do not repeat uncertain side effects with a new ID. `--command-id <UUID>` can preserve an existing operation identity. The dedicated `videostudio-agent-mailbox` branch must already exist; authorized setup creates it separately. The helper persists encrypted outbox data beside the app-key file and prints the command ID before its first network mutation. It creates immutable `mailbox/<channel>/<packetId>.vscmd` ciphertext and appends the packet ID to `mailbox/<channel>/index.json` with compare-and-swap retries, preserving earlier commands. If delivery is uncertain, run `send --app-key-file /private/video-studio-app-key.txt --retry-file <printed-retry-file>` to republish exactly the saved ciphertext and identity. Never delete that recovery file while execution remains uncertain. Index capacity is 4096 packet IDs; reaching capacity requires explicit maintenance rather than silently dropping history. The helper uploads encrypted JSON using the configured `gh api` route. It never executes native commands itself.

To retrieve the authenticated complete receipt:

```sh
node scripts/native-agent-mailbox.mjs status \
  --app-key-file /private/video-studio-app-key.txt \
  --command-id 12345678-1234-4234-8234-123456789abc \
  --output-file /private/receipt.json
```

The app key alone uses the same fixed public relay origin as the Android app. A private endpoint JSON file preserves its relay origin. Receipts can contain private media references and are written to the private output file; console output contains only receipt authentication/status metadata. Large receipts are fetched across all pages and checked with SHA-256 and AES-GCM before reading their actual status/result. Job admission is not terminal completion. Inspect the authenticated result, including `resultUnavailable` and `doNotRetry`: results above the 2 MiB plaintext transport limit preserve execution status while retaining the full result encrypted on the phone. Do not repeat a completed effect merely because its result exceeded the carrier limit.

A complete Cloudflare outage delays remote receipt visibility. GitHub delivery, local native execution, and the native journal can continue independently; phone privacy/pause and network state still apply. Native backup receipts remain available locally. Google Drive is native folder-scoped storage, not a command execution substitute.

The current app preserves its device identity and has no owner-key rotation control. If the key is exposed, pause native agent execution and seek a supported identity recovery procedure that preserves projects. Each fresh installation generates its own device-owned key; the APK contains no shared owner key.

## Encrypted media import in 4.2

This additional route carries a local source file through immutable GitHub ciphertext into an existing native project. It requires Node.js, an authenticated `gh` CLI with write access to the repository above, the existing `videostudio-agent-mailbox` branch, the private app-key file, an owned native project ID and sufficient local/phone storage. The phone must be online with native execution running and its pause/privacy settings permitting import. No Gallery browsing is involved.

Before staging media, send `connection_health` with the command helper above and retrieve its authenticated receipt. Confirm the result's `appVersion` and `serviceAppVersion` are **4.2.0 or later**, with protocol 3 and the intended device identity. Install the compatible original-signer update and reopen the app if the phone is older. Where capability metadata is available, `encryptedMediaMailbox` advertises `import_mailbox_media` and minimum version 4.2.0. The media helper does not perform this phone-version preflight itself; a new helper or APK download does not update the installed phone or deployed primary Worker.

Use one private outbox directory per attachment:

```sh
node scripts/native-media-mailbox.mjs \
  --app-key-file /private/video-studio-app-key.txt \
  --input /private/source.mp4 \
  --project-id YOUR_OWNED_NATIVE_PROJECT_ID \
  --outbox-dir /private/video-studio-media-outbox \
  --name source.mp4 \
  --mime video/mp4
```

`--name` and `--mime` are optional; defaults use the input basename and a recognized extension. Display names must fit both 180 UTF-16 code units and 255 UTF-8 bytes, with no path separators, control characters or malformed surrogate pairs. The file must be nonempty and no larger than **256 MiB (268,435,456 bytes)**. This fallback uses at most 512 chunks of 512 KiB; it does not increase the separate primary relay's 250 MiB upload limit. Helper manifests expire after 24 hours; the protocol permits no more than seven days.

The helper writes private, synchronized staging files and the encrypted import command before its first publication. Only AES-256-GCM envelopes enter the public branch: source bytes, display name, MIME and source hashes are inside authenticated ciphertext. The owner key stays in the private key file. Public paths contain opaque channel and attachment IDs; encrypted object sizes and Git history remain visible. Keep the private outbox and the printed attachment/command IDs. Console delivery status is not an import result.

If publication fails or its outcome is uncertain, rerun the **same command with the same input, name, MIME, project ID and outbox directory**. The helper checks the complete input intent and reuses the saved ciphertext and command identity byte for byte. Changed input or metadata is rejected. Chunks publish first and the manifest last; only then is the saved native import command published. Do not replace an uncertain operation with a new outbox or command ID.

Retrieve the media command's receipt using `native-agent-mailbox.mjs status` and the printed command ID. A queued response or `jobId` confirms admission only. For an interrupted admitted import, retain the same operation identity while the app's durable recovery plan and verified chunk checkpoint resume after the service restarts. Android force-stop, offline state, expiry, cancellation and privacy controls can still prevent completion. Wait for the authenticated terminal receipt and inspect its actual `assetId`, size, SHA-256 and transport result before using the asset. A complete relay outage can delay remote receipts even when GitHub delivery or native work has progressed; preserve recovery files until the terminal outcome is known.

Cross-language encryption, immutable retry and native private-file recovery have automated checks. A completed 4.2 media import on the updated physical phone remains a separate verification step; earlier import receipts do not prove this new action.
