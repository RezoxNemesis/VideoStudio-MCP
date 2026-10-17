# Reference rebuild continuation — 17 October 2026

## Current source

Worktree: `/workspace/VideoStudio-Reference-Rebuild`, branch `studio/reference-rebuild-20261017`, recovered from saved engineering source `d02e1323c6ad256da8fefd2a0004fe8e72d55915`. The older restored worktree snapshots remain untouched. Their missing Git metadata and the screenshots' uncommitted rebuild files were not restored.

The owner explicitly requested reviewing both video archives and directly continuing the interrupted engineering work. Nine screenshots provide historical discussion context. Both video archives contain ten clips each. Three frames per clip and metadata/source hashes were inspected; this is not a claim of full audio transcription or frame-by-frame review. Private media, the twenty-entry requirement ledger, screenshots, and execution evidence are outside Git at `/workspace/engineering-review/reference-rebuild-20261017/`.

## Completed editing foundation

- Browser autonomous/replacement/speed/effect operations share validated normalization. They preserve 0.25–4× speed, volume including zero, fades, title styling, colour and pan/zoom/rotation.
- Complete plans validate before saving. Oversized plans, duplicate IDs, nonfinite values and unsupported/native-only browser settings fail explicitly. Failed persistence returns failed receipts without adopting replacement edits.
- Native `StudioEditPlan` uses the same precision validator and SQLite revision guard as touch edits, retains supplied IDs and detached effects/keys, and rejects invalid plans as a whole.
- Image holds can exceed their default duration; invalid video source trims reject explicitly.
- Native plan tools expose project/revision fields through MCP. Older native versions reject requests whose revision/ID guarantees they cannot enforce. This first source milestone is 4.5.12 / code 462; it has not been released or installed.
- The dependency lockfile records the tool versions used for the local Node/Worker checks.

## Verification executed

| Check | Observed result |
|---|---|
| `npm test` | 262 Node tests and 306 smoke assertions passed; zero failures |
| `:app:testDebugUnitTest` | 1,167 cases across 232 suites; zero failures/errors; two optional larger frame proofs skipped by default |
| Explicit opt-in frame proofs | Both passed: 100 duel frames and 240 story frames produced by the existing Android Canvas renderer under local Robolectric native graphics |
| `node scripts/studio-authoring-runtime-test.mjs` | Actual local MCP SDK discovery/queue transport passed, including version refusal and exact revision/ID/effect delivery. Native replies are synthetic |
| `python scripts/edit-state-browser-test.py --video <selected fixture> --image <selected fixture>` | Real Chromium UI import, MCP edit, saved-state read-back, reload, manual trim, export/download, and failed-save recovery passed; no page errors or horizontal mobile overflow |
| Decoded browser export | 720×720, expected edited duration, zero clip gain verified as silent decoded audio |
| `:app:verifyContinuitySigning` | Blocked: original signing key unavailable; original-identity guard refused replacement/unsigned packaging |

No physical Android device/emulator is attached. No phone-side MCP execution, install/update, Filament GPU playback, final native video encode, deployment, or release was verified. The upstream frame-proof JSON producers contain fixed CI labels; these executions were local, and no GitHub Actions run was triggered.

## Quality is still below target

The owner explicitly rejected the simple, flat, weak animation visible in the current native examples. Visual inspection supports that concern. Passing the rendering tests and producing changing frames does not establish reference-quality artwork or motion.

The next source milestone implements editable radial material shading and shared animation timing. Its actual rendered eye, material and typography studies, executed tests, authoring instructions and remaining artistic gaps are recorded in [SHADING_TIMING_20261017.md](SHADING_TIMING_20261017.md). The current source is 4.6.0/code 470. The broader painting, 2D character, 3D scene, contact/action, and audio acceptance work remains recorded in the private reference ledger.
