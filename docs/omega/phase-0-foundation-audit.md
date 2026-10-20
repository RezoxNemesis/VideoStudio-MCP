# OMEGA Phase 0 — freeze / audit handoff
**Phase state:** Audit previously completed in PR #107; this branch hardens the nine-agent engineering baseline, not a new APK.

## Verified current foundation
- 4.8.6 source snapshot in `release/v486-source-base-20261019`; PR #107 contains bounded document-owned linked path masters and atomic undo/preview tests.
- Installed Android 4.8.7 reported by user; compare against draft PR #106 before any release candidate. No validated compatible APK exists yet for this program.
- Source already has `SketchReferenceLayers`, `SketchShapeKeys`, `SketchHandAnimation`, `SketchHumanAnimation`, `SketchPaletteStyles`, `NativeSpeechEngine`, and native frame clock. Extend rather than replace proven native systems.
- Existing rendering safety: preserve signing fingerprint check, native owner recovery hold, project revision, idempotent operations and Gallery prohibition.

## Failure-to-gate matrix
| Prior failure | Owned remediation | Evidence necessary |
|---|---|---|
| Generic rectangles / inaccurate hands | Phase 2+3 | Full-size before/after source-target sketch review including fingers, face and props |
| Repeated redraw / propagation gap | Phase 1 | 100 instances, locked rollback, cross-shot versioned plan, stable local animation |
| Stiff contact and robotic motion | Phase 4+5 | Stance/prop contact views at 0/25/50/75/100% |
| Flat background and camera-only effects | Phase 6 | Multiplane parallax with subject-local pixel diagnostics and human review |
| WAV exists but final MP4 silent | Phase 7 | Visible waveform, scrub timing, audible muxed result |
| Render hold, crash and cache waste | Phase 8 | Controlled recovery test, selective rerender, no project loss |
| Green CI mistaken for artistry | All | Compile + unit tests + on-phone sampled-frame visual acceptance |

## Shared API freeze
Normalized coordinates; 24/30/60 FPS; finite bounded values; no implicit source key changes; no new authoritative project database. Phase-owned `Omega*` classes are initially additive algorithmic cores and require future authorized adapter work before user-facing native enablement.

## Release gate
No merged production change or signed APK in this document. All phase PRs target `integration/omega-nine-phases-20261020`. Compatibility of PRs #106 and #107 is an explicit integration blocker. A phase is not 'complete' until actual native device demonstrations and checks pass.