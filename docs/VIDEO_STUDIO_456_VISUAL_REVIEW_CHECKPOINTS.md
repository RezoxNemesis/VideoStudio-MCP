# VideoStudio 4.5.6 — native Visual Review Checkpoints for ChatGPT drawing

Status: staged native development branch. Previous 4.5.5 APK remains the current installed build until the new signed APK is independently verified and installed.

## What failed in the Royal Blue Portrait exercise

The Android artist produced hundreds of editable vector objects using revision-bound `sketch_edit`, but ChatGPT failed to properly *see* the rendered result before continuing. The user's separately supplied comparison screenshot showed large deviations in face proportion, seated pose, clasped hands, hair texture and gold accessory details.

The original native `sketch_preview` returned **real Canvas PNGs**, but large raw base64 image results were externalized by the v3 relay's intentionally strict 2 KiB metadata limit. Full-size previews remained available via `get_native_result_page`, in 768-byte chunks. A text receipt or drawing-operation count is not a visual QA pass.

### Why we do NOT relax private transport limits

The 2048-byte relay metadata budget prevents large previews, gallery data or multi-megabyte project blobs from repeatedly entering the command/acknowledgment stores. We preserve it unchanged.

## New safe native review action

`sketch_review` accepts only:

```json
{
  "sketchId": "blue_portrait_20261016_001",
  "expectedRevision": 13,
  "frameIndex": 0,
  "focus": "face likeness and expression",
  "crop": {"x": 0.29, "y": 0.10, "width": 0.41, "height": 0.31}
}
```

- **Read-only.** The action uses the same SketchFrameRenderer as editor preview and MP4 source frames. It does not redraw, edit, save or import anything.
- **Revision-bound.** A stale `expectedRevision` is rejected. Each checkpoint carries sketch identity, actual revision, frame, normalized crop, image hash, current visible object/layer counts and sampled color groups.
- **Native renderer-based.** Draw once at a bounded 256-pixel maximum edge and create small, true JPEG thumbnails of the exact same pixels. Image quality and dimension decrease in bounded steps until the entire metadata + image response is **no more than RelayTransportPolicy.RESULT_BYTES (2 KiB)**.
- **Direct MCP image.** The existing private MCP worker already recognizes the result field `sketchPreviewImage` and emits a first-class image block, instead of printing the raw base64 in the chat. This removes the existing 768-byte fragment barrier for a *small checkpoint thumbnail* while preserving the archive/large-preview path.
- **Crops can be reviewed separately.** Request overview, face/eyes, hair silhouette, hands, jewelry, dress and background in independent read-only requests. Crops are normalized to the full native canvas; they don't require an external reference file or image upload.
- **Privacy:** Rejects reference photo URL or base64 parameters, never browses Gallery/MediaStore, and never transfers or caches the user's target photo on the phone. The user's reference photo stays in ChatGPT; only the actual sketch's derived preview returns over the already owner-authenticated v3 MCP.
- **No fake success:** `referenceLikenessVerified:false` and `requiresChatVisualComparison:true` are always present. Pixel colors or number of saved vector paths are *not* evidence of accurate likeness. A directly visible tiny JPEG is for composition gating, not micro-detail or skin-texture acceptance.

### Recommended assistant creative workflow

1. Read user-supplied target image **inside ChatGPT only** and describe concrete positions and proportions (hair outline, face tilt, shoulder width, garment, hands, lighting, background). Never invoke a native photo import unless the user explicitly requests one.
2. Begin a blank native Sketch Studio document. Draw only **composition and silhouette first**, not gold embroidery or tiny hair strokes.
3. After **every major stage or ~20–50 new drawing objects**, invoke `sketch_review` for overview and relevant crops. Use actual MCP image blocks to assess alignment with the ChatGPT target. If the response is externalized unexpectedly, explicitly retrieve and verify `get_native_result_page` rather than proceeding blind.
4. Write an honest discrepancy list in the conversation: misplaced features, wrong scale/pose, missing art/limbs, incorrect outfit colors, visible pixel artifacts. These are model-human assessments, not measurements fabricated by the device.
5. Change **structural shapes first**, re-render and review again. Continue to detailed strokes **only** when the composition and face/hand landmarks are satisfactory.
6. For important micro-details, use the full-resolution `sketch_preview` and its original hash-verified paged result path. The tiny direct thumbnail cannot establish facial precision.
7. Finish with a full Canvas preview and explicit user/artistic approval. A green Android CI run, 2 KiB thumbnail or 500 vector objects do not certify photorealistic fidelity.

### Important implementation limits

- The action does not include a built-in computer-vision facial similarity model or target-photo recognition. This must not be represented as an AI likeness score.
- Even with vision feedback, the underlying drawing architecture still needs higher-resolution raster-paint illustration, a more precise drawing planner, landmark/shape adjustment and expressive hands/hair tools.
- Production acceptance needs testing on the owner's physical Realme device, plus actual cropped image quality and observable editor behavior. The new API's direct image result is tested in Robolectric using the same relay result policy, but first-party Android MCP end-to-end delivery is separately verified after installation.

## Technical regression gates

- `SketchVisualReviewTest`: true Android Canvas JPEG, <=2048-byte total result, `RelayTransportPolicy.result` never externalizes that small image, decodable image proof and hash, separate actual face crop, blank still not likeness-certified, source immutability and input rejection.
- `SketchVisualReviewProtocolTest`: all native admission lists and background dispatch include the new action, existing alias and native v3 permissions apply, persisted source revision is checked, reference image fields remain forbidden.
- Full 4.5.5 native Android suite, ten-second original 2D storyboard film proof, APK identity and signing continuity must pass. The original signing certificate, stable MCP endpoint and user project storage remain unchanged.

## Engineering next steps

This is the **reliable feedback first** milestone. After testing it on the live Android 4.5.6 build, add a structured *review–correct–re-review* authoring controller, followed by more capable portrait construction and visual action QA. Do not claim that viewing a result automatically improves the underlying artwork; improvements require explicitly adjusting shapes, positions, colors and timing.
