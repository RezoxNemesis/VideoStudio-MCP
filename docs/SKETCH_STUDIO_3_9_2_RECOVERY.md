# Sketch Studio 3.9.2 recovery

Version code 392; package `com.rezoxnemesis.videostudio.next`; original pinned update signer retained. This is a repair of 3.9.1, preserving documents, references, live events, sketches/books, pairing and earlier animation work. No database reset, owner rotation or Gallery access is involved.

## Reproduced Android failures and repairs

- 3.9.1 rejected Play and almost every button for the entire live session, even while waiting for more strokes. 3.9.2 has a visible **Draw myself / Watch ChatGPT** control. Play and other actions pause live presentation directly, load the authoritative saved revision, then execute. Local takeover does not end or delete the agent's session or its strokes. Paused watching stays paused across reopening.
- The result outbox selected full JSON bodies through a CursorWindow. One 3.2 MiB saved receipt raised `SQLiteBlobTooBigException` before any later ACK could be read. The reader now selects only IDs/sequence/length and reads each exact TEXT value through a bound SQLiteStatement. Batches load at most 4 MiB; an oversized legacy receipt can travel alone. Existing retry/defer behaviour retains a failed result while later ones progress.
- The sketch store supported 4 MiB documents but read current/history JSON through the same approximately 2 MiB CursorWindow. Dense sketches failed during later edits, reopening or undo. Current/history and v1-summary-migration reads now bypass that window while keeping the original rows/schema, UTF-8 artwork, revision checks and atomic transactions.
- Delayed live baselines and Play continuations could start after Activity pause. A late database failure from a closed player could also disable the new workspace. Foreground/generation checks and closed-player checks reject those stale callbacks. Resume reloads current artwork before editing.

## Relay evidence and remaining boundary

The owner reported 36 claimed native commands and `string or blob too big: SQLITE_TOOBIG` through normal ChatGPT. That cloud error is distinct from Android's reproduced `SQLiteBlobTooBigException`. Backlog counts alone did not reproduce the cloud error: both local current and main-branch Workers accepted a small edit/ACK after 36 claims and 4.8 MiB retained synthetic results. Do not call that a reproduction or resolution of the production failure.

The stronger source-runtime integration uses the installed MCP SDK and SQLite-backed local workerd. It accumulates 18 MiB terminal payloads, checks explicit bounded terminal-proof compaction, claims 36 pending edits in order, acknowledges a newer edit first, verifies idempotent ACK replay, then drains every older command. Its replies are synthetic and its origin is restricted to loopback. It proves the source transport/storage path, not phone strokes or the user's deployed relay.

A read-only public schema inspection on 2026-10-13 found the deployed relay's older catalog, with no named Sketch Studio tools or result-page tool. Native `studio_sketch_*` aliases remain callable through its generic `app_execute`, but tool discovery does not prove its storage implementation. GitHub's latest Deploy Studio Web Worker run 38284064653 finished source validation while its actual Deploy Worker step was **skipped** because Cloudflare deploy credentials were unavailable. The cloud environment likewise exposes no configured deploy credential/identity. This session cannot claim that the production Worker was updated.

The required cloud repair is to activate the existing chunked command/device store from this branch in the original Worker/account, retaining the same Durable Object namespace/keys and private device endpoint. Its transactionally migrated queue preserves accepted pending commands and compact terminal proof; clearing the production queue is not a repair. The Android APK alone does not activate it.

## Verification and scope

New regressions failed before their changes for the Play lock, large outbox row, dense sketch, pause/baseline and pause/Play races, late closed-player error, and oversized ACK batch. Focused tests use Android native SQLite/Canvas and lifecycle scheduling; a controlled database-open boundary is used solely to order the late-error race. All original motion/3D, imports, thermal behaviour and render scheduling remain outside this repair's scope.

Run the local relay integration after starting the current Worker:

```sh
npm run dev -- --local --ip 127.0.0.1 --port 8790
node scripts/native-relay-backlog-runtime-test.mjs
```

The synthetic loopback result is written to ignored `.work/native-relay-backlog-runtime-results.json`. Full Android/source checks and signed-APK verification must pass before shipping. Actual native/device drawing, reference likeness and live ChatGPT host acceptance remain unverified without the private connected execution surface; successful builds do not establish professional artistic quality.
