# Original artwork rig workbench implementation plan

Spec: ../specs/2026-10-12-source-rig-workbench.md. Root controls all Gradle and CI runs. Independent source owners implement in parallel; source/image/phone helpers share fixed interfaces below. User already authorized autonomous design and implementation without approval questions.

## Interfaces and ownership

- Native image owner: `NativeSourceArtworkInspector.inspect(Context, ProjectStore, JSONObject request)` and `NativeSourceRigPreview.render(Context, ProjectStore, JSONObject request)` return bounded JSONObject plus `sourceArtworkImage` or `rigPreviewImage` containing base64/mimeType/width/height/role. `projectId`, `assetId` or `rigId`, expected source/profile SHA, crop (optional), maxDimension <=1024. No ControlService or UI edits.
- Rig batch owner: `NativeRigBatchAuthoring.save(Context, ProjectStore, JSONObject request)` and `NativeRigBatchAuthoring.status(Context, ProjectStore, JSONObject request)`. Eight source-bound profiles maximum, stable batchId, per-profile CAS and explicit replay/partial status. No source image helper, service, UI or Worker edits.
- Phone owner: integrate inspection/pose diagnostic and ordered readiness into `RigEditorActivity` with existing source/project selection; use helpers above. Own added UI activity/dialog and manifest registration only. Root adds native service actions.
- Preview owner: focused fixes in automatic matte/isolation/deformable source material paths and their tests; preserve explicit preview scope and previous generations. No rig, service or Worker edits.
- Root: ControlService, Worker schemas/routes/image projections, AppProtocol/version 373/3.7.3, integration tests, evidence/docs and publication.

## Tasks

1. Reproduce source inspection rejection, missing native image projection, incomplete-matte acceptance and crop/material loss with meaningful failing fixtures.
2. Implement verified oriented original inspection and real source-rig pose diagnostics. Test source mutation, exact crop mapping, aspect, foreign IDs, incomplete profiles and bitmap cleanup.
3. Implement bounded batch saves/status with invalid-last-entry prevalidation, explicit partial application, identical replay, conflicting replay, source/profile CAS and no original mutation.
4. Add phone preparation/readiness and pose-diagnostic controls. Source edits remain separate from test poses; incomplete drafts cannot silently become accepted rigs.
5. Expose version-gated typed MCP tools and source image content. Test exact handler/schema/version/privacy/ownership and large-result bounds through existing native queues.
6. Repair evidenced automatic preview failures and make uncertainty/zero-event audio explicit. Native synthetic material/matte regressions precede any claim of repair.
7. Run focused checks then complete existing suites and actual local runtime checks; independent review, same-signer APK build, PR and verified artifact. Record original-source native acceptance separately.

## Review focus

- Cropped/EXIF preview coordinates accidentally saved in the wrong source coordinate space.
- Source/profile mutation between inspection, batch validation and publication.
- Partial batch saves or process restart presented as complete/replayed success.
- Diagnostic pose editing overwriting source rest annotations or recycling caller images.
- Incomplete masks/ghosts or ambient sound labelled complete actors/action Foley.
