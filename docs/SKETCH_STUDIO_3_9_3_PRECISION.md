# Sketch Studio 3.9.3: reference imports and precision inspection

Version code 393 preserves the 3.9.2 recovery, original application ID, signer,
MCP pairing, documents, history and accepted live drawing events.

## Reference import repair

Some file URIs and document providers return no specific MIME type. Previously a
JPEG could enter the editor as `application/octet-stream`; Sketch Studio then
rejected it as a non-image even though Android could decode the file. `AssetProbe`
now uses the provider's display filename, or the file URI's leaf name, to infer a
known extension when the reported MIME is generic. An authoritative provider MIME
still wins. Unknown extensions remain generic. This is metadata fallback in the
native URI import path, not a replacement for download permissions or ownership.

## Revision-bound detail previews

`sketch_preview` accepts optional normalized `crop` bounds:

```json
{
  "sketchId": "owned-sketch",
  "expectedRevision": 20,
  "maxDimension": 720,
  "crop": {"x": 0.59, "y": 0.4, "width": 0.22, "height": 0.09}
}
```

The actual shared Android renderer draws vectors directly into the cropped
viewport. A small eye or curve can therefore be inspected at the requested
resolution without enlarging a previously reduced full-frame bitmap. The reply
retains the document revision and SHA and adds `canvasWidth`, `canvasHeight` and
integer `sourcePixelBounds`. The latter maps every returned preview pixel back to
the original canvas, including fractional-bound rounding and PNG-budget resizing.
Full-frame preview behavior stays compatible. Crop requests require native 3.9.3;
older basic previews still work.

Human artists already have canvas pinch zoom. This adds equivalent detail
inspection to the agent workflow; both paths render the same editable document.
The existing native `contactSheet` conversion remains the image transport for
older MCP relays. It was already implemented and is not a new 3.9.3 repair.

## Reference evaluation and acceptance limits

A private portrait assessment authored 227 editable objects through 242 native
operations in 19 revision-bound batches, with seven captured construction stages.
The Android `SketchEditOps`, SQLite store and native Bitmap Canvas ran under
Robolectric. The original image was imported into an owned hidden reference layer;
the finished redraw contained no visible copied source bitmap. Exact operation
replay did not duplicate events, and every accepted operation remained in order.

Visual comparison **failed reference-quality acceptance**: the redraw reproduced
the broad composition and features but had simplified contours, hair detail,
shadows and lighting. This demonstrates useful editable geometry; it does not
prove automatic portrait likeness, a professional artist, or frame-to-frame
animation quality. More precise authored geometry and iterative visual correction
are needed. Lettering was drawn with paths because there is no native text-object
tool. The supplied artwork, drawing packet and resulting images remain outside
Git and the APK.

This is workspace native-engine evidence, not an installed-phone session or live
ChatGPT-host test. No private device endpoint was callable in this workspace.
The production cloud `SQLITE_TOOBIG` also remains unverified; deployment run
38331069420 passed validation but skipped its actual Worker deployment because
Cloudflare credentials were absent. The signed APK cannot deploy the Worker.

Focused import/crop regressions reproduce the pre-change failures. Full checks
passed: 622 Android tests across 133 suites, 161 JavaScript tests and 306 source
smoke checks, with no failures. Independent focused review found no remaining
actionable issues after the redundant compatibility helper was removed. The
installed MCP SDK also received the exact cropped native PNG through the original
main-branch localhost relay's existing image mapping; its registration and ACK
were simulated locally, not on the phone. Signed-APK package/certificate
verification is required before presenting the update. The 3.9.2 recovery evidence
and remaining storage boundaries are documented in
[the recovery record](SKETCH_STUDIO_3_9_2_RECOVERY.md).
