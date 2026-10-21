# Native AI key drawings — 4.10.5 / code508

Extends frozen4.10.4 source33f0f1e6a3ab95cec509d4e750c79dc9d6a87ff7.
The owner now prioritizes the foreground long-haired young man in the supplied
monochrome two-character pencil image. Keep the rear older man, Temple studies,
other characters and all previous generations separate. One source view does not
show unseen anatomy/costume surfaces or the feet. No mastered360° or finished film
claim is justified by the current artwork.

## Reusable native flow

The optional pretrained SD1.5 model already executes through VideoStudio's own
LocalDrawingOperations/ONNX Runtime CPU path. This change connects a completed,
chosen raster draft to ordinary native animation editing. It does not train a
character model, improve the underlying weights, recover artist layers, generate
consistent unseen views, or automatically create animation in-betweens.

Manual: Sketch Studio > More > Local AI drawing. Import an explicit reference,
check/install the optional model, enter instructions, generate and inspect the
actual draft. Choose an unlocked target layer, Start frame (1 based), and Exposure
(frames), then Place draft as an animation key. The start defaults to the editor's
current frame. Placement fits the current canvas and existing duration. Normal
Undo is available on return to Sketch Studio. Retry last key placement reuses the
saved exact operation and cannot append a second copy after a committed response
was lost. Refresh references after unrelated source-sketch edits.

For reuse, Sketch Studio's existing Artwork library > Save selected layer / cel
saves the chosen frame as an immutable component, including AI provenance. Place
component reuses it on explicit frames/exposures. This is reuse of copied artwork,
not a newly drawn pose. Usual image, layer, document and sequence limits remain.

Ordinary command flow:
1. `app_status`, then inspect `sketch_capabilities.localNeuralDrawing`.
2. `local_drawing_models`; install only the existing pinned optional model pack.
3. `local_drawing_generate` with a unique requestId, owned sketch/object identity,
   expectedRevision, sourceSha256, optional full-oriented sourceRect and settings
   (prompt, optional negativePrompt, seed, steps, strength, guidance).
4. `local_drawing_get` with includePreview true; inspect actual output.
5. `sketch_import_reference` with normal target/source identities, explicit frame
   and exposure, plus `localDrawing:{requestId,outputSha256}` from that receipt.
6. Preview/review the cel with its reference hidden; save/reuse only reviewed work.

No new canonical action is introduced. Old requests/omitted fields retain their
schemas, fingerprints and feature floors. Explicit AI key bindings and inline
reference.localDrawing provenance require native4.10.5 in matching relay contracts.
Generation itself retains4.10.2. Native/manual and Node/MCP metadata agree. These
source contracts do not prove the live Worker is deployed or a phone upgraded.
No Worker deployment or phone installation is performed by this change.

## Source binding, history and portability

The native import transaction requires a completed local study; it independently
checks retained PNG size, MIME/dimensions/SHA256, selected output project/asset and
chosen outputSha256. After ordinary source staging, the staged original-upload
hash must equal that chosen output. Changed asset bytes with unchanged IDs reject.
The reference retains strict localDrawing v1 metadata: kind local_ai_raster, full
original generation request, requestSha256, modelIdentity and outputSha256. The
fingerprint binds the canonical request to the model, and outputSha256 binds the
imported original bytes. The working PNG may be re-encoded, so its identity stays
separate. Prompt metadata is descriptive provenance, not proof of visual accuracy.

Normal store mutation supplies revision checks, locks, atomic history, durable
operation receipts and replay. Source hashes/prompts survive save/reopen,
Undo/Redo, component reuse and self-contained editable archive restoration.
Published content-addressed dependencies survive a rejected transaction; another
successful operation may already own them. Optional model/study removal does not
remove the imported owned cel or its saved metadata/receipt.

## Evidence and limits

Focused RED Android import rejected localDrawing as unknown; RED manual UI lacked
the placement button; RED Node schema rejected the new binding. RED inputs/logs
are retained privately. Focused tests cover clock visibility at both boundaries,
archive/history/replay, altered prompt fingerprints, source-file replacement,
component reuse and actual manual button execution. Synthetic PNG/receipt fixtures
exercise placement only, never neural generation or creative quality.

Run fresh combined Android/Node regression and independent candidate verification
before marking a build verified. Actual real-weight studies use the same app
operations, an explicit private reference and the pinned local model. Host Android
source/JNI execution is distinct from phone/APK/GPU/codec execution. Record model
RAM/storage admission fixtures and actual output review separately.

A512px opaque raster key is not editable pen strokes, hundreds of poses, a character
turnaround, natural speech or a240s film. The optional model still needs a64-bit
process,6144MiB available RAM, about4GiB weights plus staging storage. Weak likeness,
wrong angles, altered costumes, flat fingers or temporal drift require rejection,
not relabeling as success. Voice remains after matching artwork and movement.

Fresh combined local validation: 2227 Android tests,0failures/0errors,20 skips across394 suites;700 Node tests and306 smoke checks pass. Candidate CI, signing and real-weight artistic review remain separate evidence.
