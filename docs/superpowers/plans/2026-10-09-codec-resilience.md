# Codec resilience implementation plan

Use executing-plans and TDD within the owner's standing authorization. Spec: ../specs/2026-10-09-codec-resilience.md; approved bundle section6.

- [x] Add failing lifecycle/verification/cancellation and durable reliability regressions.
- [x] Implement serialized bounded retry controller and SQLite reliability index.
- [x] Wire actual Media3 default/conservative/software routes with codec-aware ordering and shared owner/autonomous output proof.
- [x] Add actual device conservative/software render proof without reducing final quality.
- [x] Run fresh Android/Node/Worker verification and independent review, publish the exact tree and inspect matching APK/device CI.
- [x] Append measured continuity and continue segmented rendering and the complete roadmap.

Initial lifecycle/indexRED15 and policyRED3 observed; GREEN18 then source-aliasRED1/GREEN19 and operating-rateRED1. Independent review adds progress scope-cancelRED2, level/bitrateRED2, input-initializationRED1, resolved-formatRED1, ordinary checkpointRED1 and terminal checkpoint unfixed-variantRED1. Corrected full source GREEN269 before final terminal regression; final fresh270 passes (80 main/40 test Java classes). Independent latest-source review closes all Important findings. Node282/20/22 and actual Worker bundle pass. App3.4.10/schema7. New eight-case API33 instrumentation source awaits matching build/device CI; no device codec path claim yet.

Publication bdc5566d: matching APK/unit270 and Worker CI succeed; device37907676874 runs8 cases with6 failures because the encoder's unspecified frame rate was compared against Media3's resolved30. New focused RED1/9 reproduces that rejection. Only unspecified rates now normalize before the actual factory boundary; final fresh271 unit cases and Node282/20/22 pass, and independent correction review closes with no Important findings. Corrected matching-source device rerun remains pending.

Corrected190ab4e matching Android37908874926 passes271/APK; Worker37908874965 succeeds; API33device37908874872 OK8/38files. Actual conservative/software route proof recorded in continuity. Codec task complete; full app remains unfinished and segmented work continues.
