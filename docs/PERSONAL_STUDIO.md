# VideoStudio Personal

A private mobile-first workspace at `/personal/`, added to the existing VideoStudio Worker. The root editor, native Android source, stable MCP v3 endpoint, legacy queues, existing Durable Object class name, binding and storage identity are preserved.

## What works without a phone or browser

Authenticated cloud project creation, revision-checked notes/brief/reference/shot-list updates, search, export/import, preferences and activity reads use real persistent SQLite-backed Durable Object storage. The existing native MCP can invoke them through the explicit `personal_cloud` action.

This is not a provisioned cloud video renderer. Rendering, visual analysis and native media operations still use the existing native/browser executors. A private cloud plan is not a synchronized copy of the native timeline. A phone-only source is unavailable while that phone is offline. Browser-local files do not become cloud files when metadata is saved.

## Open and use

Open `/personal/` on the existing Worker origin. Sign in with the existing private native connection key. The form can also extract the key from a complete stable-v3 MCP connection URL. The key is exchanged over same-origin HTTPS for a seven-day session and is not saved in localStorage or IndexedDB. Cookies are HttpOnly, Secure and SameSite=Lax; session tokens are hashed at rest. Keep the original MCP URL private.

Create a project; add its brief, notes, HTTPS reference links and shot list; choose Save to cloud. Local drafts are explicitly device-only. Stale revisions are rejected instead of overwriting another session's work. Export an important draft before Refresh discards it in favour of a newer cloud revision.

The Editor section loads the existing root video editor on demand. Its browser-local timeline is separate. Native Activity shows recorded device command status, not invented progress. Cloud plans and status can be accessed directly from ChatGPT without opening this website.

## Existing MCP bridge

Use the current private native MCP owner credential; never publish it in source, logs or task descriptions.

Native app_execute arguments:

```json
{"action":"personal_cloud","parameters":{"operation":"project.create","data":{"name":"My next film","aspect":"9:16"},"requestId":"unique-stable-request-id"}}
```

The installed Studio Web compatibility tool also accepts `queue_video_edit` with the existing private native owner key as `deviceId`, `projectId: "active-native"`, `action: "autonomous_request"`, and `parameters: {nativeAction: "personal_cloud", nativeParameters: {operation, data, requestId}}`.

Read the returned command ID with get_video_command_result. The compatibility wrapper may say queued=true; the authoritative completed cloud command has status=completed and executionSurface=cloud. Native commands remain pending when the phone is offline.

Supported operations:
- snapshot: optional query; sanitized cloud/native activity and project summaries.
- project.read / project.export: id.
- project.create: name, optional brief/notes/aspect/links/shots/nativeProjectId.
- project.update: id, expectedRevision and patch.
- project.delete: id and expectedRevision; cloud plan only.
- project.import: versioned archive; creates a new project ID.
- preferences.update: theme dark or light.

Every mutation requires a stable requestId. Retry uncertain requests with the same ID and content. Different content with the same ID is rejected. Retains 100 recent mutation receipts and 8 MCP read receipts. STOP CHATGPT CONTROL blocks writes. One File Lock, project-only, selected-asset and unknown restricted modes cannot bypass cloud access limits.

## Storage

Cloud: up to 50 projects, 12,000 note characters per project, 24 HTTPS links, 48 shots, 100 mutation receipts; bounded requests and 90 writes per owner per minute. These are application limits, not statements of available account quota.

Selected files: real IndexedDB blobs, up to 20 MB per file, only on that browser/device. Download important files separately. Clearing site data can remove drafts and attachments. Persistent-storage requests are subject to browser approval. No Gallery enumeration is implemented.

Exports and Drive backups contain cloud project plans, not browser/native media. Deleting a cloud plan leaves native media and local attachments untouched.

## Optional website-owned Google Drive

Requires Worker environment values PERSONAL_DRIVE_CLIENT_ID (or GOOGLE_DRIVE_CLIENT_ID), PERSONAL_DRIVE_CLIENT_SECRET, and a separate random PERSONAL_TOKEN_KEY of at least 32 characters. Store secrets only in approved provider secret storage.

Enable Drive API for a Google OAuth web client, and register the exact HTTPS callback `/api/personal/drive/callback` on the deployed origin. The owner must complete Google's consent once from Storage. ChatGPT's Drive grant is not a website credential.

The implementation requests only drive.file, uses PKCE and one-time state, obtains offline refresh access and encrypts credentials with AES-GCM. A private VideoStudio Personal folder holds plan JSON backups; bytes are read back and compared before success is reported. Reconnecting resets previous account folder IDs. Google app testing status, revocation or token expiry can require new consent. Rotating the encryption secret also requires reconnecting.

Live Google authorisation and uploads remain unverified until the configured account completes consent. There is no cloud media upload or Drive video-streaming implementation in this release. The optional backup endpoint is authenticated through the website session, not a newly advertised MCP tool.

## Build and verification

npm test preserves existing tests and runs personal state, HTTP, Drive and browser-script tests. npm run test:personal runs the personal suite. Both first bake the browser script into `.generated/personal-client.js` using Node, before esbuild can inject out-of-scope helpers into a serialized function. Wrangler runs the same custom build before dev/deploy. Generated files contain source only, no credentials, and are ignored by Git.

The Personal Website Verification workflow uses explicit bash pipefail; a failed test piped through tee must fail CI. It dry-builds the actual Worker, starts local workerd/SQLite, exercises real MCP while its synthetic phone is offline, then runs actual Chromium interactions and requires screenshot/result artifacts. Synthetic test identities are not inserted into production.

Browser emulation is not physical Android testing. A green build does not establish deployment: inspect the deployed MCP personalCloud capability, run an isolated cloud create/read/delete, verify the live website, and confirm the stable native endpoint remains reachable.

## Preservation and rollback

Developed against main d652066ce428e4f128514da4d1caee919974554e. Separate native 3.4.11 work in PR35 is not merged or overwritten by this feature.

The additive entrypoint delegates existing routes unchanged. Personal records use the separate personal: namespace. Roll back by restoring wrangler.jsonc's main to src/index.js and redeploying, without renaming/deleting the Durable Object class, binding or storage.

No paid renderer, R2 subscription, billing upgrade or paid AI API is activated by this feature. Existing provider quotas still apply.

Official references: https://developers.cloudflare.com/workers/platform/limits/ ; https://developers.cloudflare.com/workers/wrangler/bundling/ ; https://developers.google.com/workspace/drive/api/guides/api-specific-auth ; https://developers.google.com/identity/protocols/oauth2/policies .
