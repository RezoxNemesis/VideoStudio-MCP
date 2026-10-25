# VideoStudio 4.10.33 — Static Alpha Track Mattes

## Engine

VideoStudio now accepts a hidden, project-owned vector shape track as the alpha source for a static IMAGE or SHAPE overlay. This is an original on-device matte implementation inspired by professional compositing, not an import of Adobe After Effects software or proprietary media.

The matte's saved position, dimensions, rotation, transparency and optional native feather mask are mapped to the target's decoded pixels. Target alpha is multiplied by matte alpha, or by its inverse. The bitmap computation is bounded to <= 1,048,576 pixels per target and is cached once per native Media3 overlay generation; no source file bytes are overwritten and no Gallery scanning is performed.

The same Stage 4 OverlayEffect receives these prepared matte pixels in live edited preview and MP4 export. The independent matte source shape is hidden from rendering while linked. Its full timeline range must cover the target. Matte and target must be static, unparented and in the same project; video mattes, animated mattes and tracked mattes are NOT supported.

## ChatGPT and human authoring

Open the Inspector for an image or shape and choose Track matte. Select alpha or inverted alpha of another static shape in the same project. Unlinking restores the source's visual presence only when no other targets refer to it. Commands are available over the existing private MCP: track_matte_capabilities, track_matte_plan (no mutation), track_matte_edit (atomic guarded edit). Fields: projectId, expectedUpdatedAt, operation (link or unlink), targetLayerId and, when linking, matteLayerId and optional matteMode. The closed MCP mailbox rejects arbitrary Gallery/file source fields.

## Quality/performance gates

Required: passing native compilation and full unit suite, current stable owner signature validation, source-preserving project roundtrip, per-pixel alpha/inverse tests, hidden matte source compositing tests, bounded decoder memory, and on-device preview/export visual comparison. Real mobile GPU/decoder reliability is NOT inferred from CI success.

## Unsupported After Effects-class functions

Full nested precompositions, animated and video track mattes, 3D camera/light hierarchies, motion-vector blur, automatic rotoscoping, tracking, optical flow, particle/fluid simulations, and Adobe .aep project compatibility are separate engineering programmes. The five older and six newer owner reference videos remain visual acceptance standards after real device exports.