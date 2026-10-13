# Connect ChatGPT to Video studio

Install the signed Video studio APK and open its Native Agent connection settings. Tap **Share app key**. Treat the `vsapp1.` key as private: it authorizes commands on your phone. Keep your projects and the existing app installation in place.

A ChatGPT session needs terminal access to this repository and GitHub **write** capability for `RezoxNemesis/VideoStudio-MCP`. Sharing the key alone does not grant GitHub writing capability. No additional hosting account or MCP connector is needed for this route. Native installation and phone execution have not yet been verified for this build.

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
