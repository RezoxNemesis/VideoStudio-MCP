# Explicit bounded output waiting — unpublished 4.10.25/code528

Feature branch `feat/native-explicit-decode-wait-v41025` starts combined source
`c2020d65abc1890b59f9b5b8b84d4f686c4353bb`. The earlier preview/MIME feature branch
still points to `2c15f14f6537295893cad81629a2ba5b83768899`. Root alone owns version
metadata, integration, full validation, signing, APK execution and publication.
This feature does not change versions, package, signer, identity or source media.
No APK 528 has been verified, signed or installed for this feature yet.

## Product behavior

Existing `export_project` accepts optional `maxOutputSampleDelayMs`: a strict,
finite integer 25000..120000. Strings, booleans, null, fractions, out-of-range
values and disabling sentinels are rejected before job admission. Integral JSON
numbers such as 90000.0 have the same numeric integer meaning. No new action or
alias is introduced. Any explicit value requires native 4.10.25; omission retains
the previous version floor, normalized request bytes, routing and fingerprints.

The actual Media3 `Transformer.Builder.setMaxDelayBetweenMuxerSamplesMs` is called
only when the option is explicit. Omission leaves the setter uncalled and retains
the SDK default (currently 10 seconds on a device or 25 seconds on an emulator).
There is no emulator-only product branch, timeout disabling, fake output sample,
timestamp change, automatic retry or automatic claim that a stall was repaired.

The wait is the maximum interval without an output sample, not a video duration
or total render deadline. Existing cancellation, global adaptive render deadline,
memory/thermal/storage gates and publication checks remain independent. Choosing
a longer wait does not guarantee decoding or successful output.

Manual export exposes Default, 60 seconds and 120 seconds. Choices are remembered
only after confirmation; cancelling saves nothing, and confirmed Default removes
the saved explicit choice. Legacy two-argument dialog callers retain their old
API and controls. Main's selected project is captured when opening the dialog.
Only an explicit choice adds the numeric service Intent extra.

Service admission preserves raw Intent types, including explicit null as
`JSONObject.NULL`, before shared strict validation. Both background Service and
legacy foreground command paths carry the nullable choice. Omission retains the
five-argument NRE entry point; explicit waits use the new overload. Durable export
parameters preserve the explicit field, so restart/recovery applies the same
choice without materializing defaults. Successful native encoder receipts include
the choice only when explicit; old omitted receipt shapes remain unchanged.

Native error7002 now reports its exact finite wait in decimal seconds without
copying raw nested exception strings or DebugTrace contents into UI/job messages.
Other native error messages retain their previous handling.

Node validation checks only the new field and preserves unrelated legacy export
parameters. Typed `app_export_project`, native/compatibility queues and hybrid
routes share strict validation and conditional4.10.25 admission. Explicit waits
cannot silently fall back to browser `render {}` or lose their option while a
device sleeps. Omitted requests preserve their older routing behavior. Native
`studioEditing.exportOutputWait` exactly matches the Node capability object.

## Evidence and limitations

Root's APK 527 reproduction with the original signer of the unchanged 12.4-second edit failed
with typed native error7002 after 25 seconds without a muxed sample. Partial video ended
after the fourth clip; all five base clips are videos. The PNG is a later timed
overlay, not a still-image base tail. Do not blame that PNG from the timeout alone.

Native source metadata confirmed the fifth source is 1080x1920 HEVC at 30FPS;
seeking to the previous sync sample for the 2.4-second trim returns0. Full source
SHA/size and project revision/DTO identity remained unchanged. This establishes
real decoder preroll before the visible fifth clip. Source/code analysis confirms
discarded preroll is not counted by Media3's rendered-output trace; accepted input
alone does not prove decoded output progress. The exact post-discard observer is
prepared separately and has not established a completed extended-wait render.

The new control is a bounded explicit capability. Calling it a repair requires
the same 12.4-second snapshot to complete with the option, actual discard/progress
evidence, independent output checks and unchanged source identities. That native
retake remains pending. The analogous 527 JPEG+MP3 controlled preview/export did
work; the original 4.10.22 phone project's exact error remains undiagnosed.

## Validation history

All failed generations and original assertions remain preserved outside Git.

- First combined native attempt stopped at compilation because the SDK muxer
  exception factory is package-private. No behavior RED was claimed for it.
  A test-only same-package bridge fixed access; only the import and three factory
  call targets changed, with every assertion unchanged.
- Genuine corrected baseline: 25 tests, 17 behavior failures, 8 positive controls,
  7.615s against FULLc202. Missing behavior included SDK configuration, strict
  admission, recovery/Intent carry, manual choices and typed7002 explanation.
- Final scoped native: the unchanged 25 tests passed in7.014s using the exact six
  production files. These are UI/admission/SDK-listener checks, not GPU/codec proof.
- Fresh Node: 877 checks and 306 smoke checks passed, zero failures. The original
  ten feature tests remained unchanged. All 1166 candidate product/test files were
  byte-identical across that run.

Root evidence: `film-production-20261022/export-output-wait-528-red-v1-*`,
`export-output-wait-528-red-v2-*` and `export-output-wait-528-green-v1-*`;
Node/source manifests: `editing-export-output-wait-20261024/`. Root's earlier full c202 pass of 2705 tests predates this feature and is not combined verification of it.

Full post-merge regression/CI, original signing and actual updated-APK native
retakes remain required. Do not merge main, publish stable, replace the signing
key, clear/uninstall data, reset MCP identity, browse Gallery or substitute an
external renderer. This change produces no new drawing, voice or accepted film
seconds; the requested 240-second character film remains unfinished.
