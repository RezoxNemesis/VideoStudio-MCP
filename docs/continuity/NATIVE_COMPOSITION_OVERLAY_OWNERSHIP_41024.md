# Native composition overlay ownership — 4.10.24/code527 candidate

An actual original-signed526 creative12.4-second export failed around20 percent
at its first source cut. Its failed generation remains preserved. Independently,
the pinned native Media3 lifecycle and four actual app/library CPU ownership cases
reproduced composition IMAGE resource reuse after a shader-program retirement.
This source fix is scoped-verified; actual changed-APK decoder/GL/encoder export,
full integrated527 validation and original-signed candidate verification remain
pending. It is not a finished edit or accepted240-second animation film.

Media3 DefaultVideoFrameProcessor releases its intermediate shader programs when
per-item effect lists change, then calls the retained composition GlEffect again.
OverlayEffect retains its TextureOverlay instances, and OverlayShaderProgram.release
releases those instances. The app's StudioTimedImageOverlay intentionally recycles
its source and transparent bitmaps and rejects every read after release. Attaching
one mutable OverlayEffect for a whole composition therefore reused retired IMAGE
objects after a cut, including while their authored alpha was zero.

NativeRenderEngine now attaches a StudioCompositionOverlayEffect containing only
detached layer and owned-image-asset descriptors. Each toGlShaderProgram call creates
fresh native IMAGE/TITLE objects in the original order, then delegates to Media3's
ordinary OverlayEffect. A successful shader program owns its new objects. Partial
construction or allocation failure releases only the newly created objects and
retains the primary exception with any cleanup exceptions suppressed. The previous
generation cannot be retired by another generation's failure. Source PNG bytes,
source identity, authored bounds/motion clocks, title text/typography, cut duration,
MCP schema and feature floors are unchanged. Direct IMAGE release remains terminal;
no released-object resurrection or per-cut raster replacement was introduced.

Root genuine behavior RED: four tests on FULL526 ran4/3FAIL in2.835s; the direct
terminal-release control passed. The failures show active image reuse, an inactive
transparent bitmap followed by later activation, and two generations sharing a
recycled decoded bitmap. Corrected source then passed the89-case scoped union in
27.72s, including all four unchanged cases and prior IMAGE/TITLE/VIDEO/manual tests.
These checks use native BitmapFactory and actual TextOverlay Canvas glyphs, source
bytes, overlay settings and actual overlay release behavior.

The unit-only Media3 allocation boundary returns a small ownership token with an
empty texture pool. Its drawFrame ALWAYS throws, and tests assert that it cannot
render. Configure/release exercise the actual overlays. This establishes CPU
resource ownership only; it never simulates successful GL pixels, codec execution
or completed export. Original V1 fixture compile failure (missing checked throws)
and all corrected source generations are retained. Two additional allocation-
failure and frozen-caller-snapshot cases are prepared for separate original526
baseline execution; they are not yet claimed verified here.

Four old native tests inspected a directly attached final OverlayEffect rather
than an executable composition recipe. Two were narrowly adapted to allocate a
real fresh factory generation. Root then ran the remaining19 legacy cases and
froze2 failures in6.666s at the other obsolete attachment checks; those checks now
use the same native factory allocation seam. Their numeric, source, clock and pixel
assertions are retained. Full legacy retakes remain required.

Other native overlay paths were inspected but left unchanged: legacy per-item
TextOverlay resets its GL texture state without recycling its cached text bitmap;
CanvasOverlay used by atmosphere/procedural drawing recycles and nulls its bitmap,
then recreates it. The proven permanent released IMAGE failure is repaired only
in the composition timed IMAGE/TITLE attachment path.

Root owns all JVM/native/Gradle/ADB runs, integration, original signing and actual
APK tests. Preserve original package/signer, source assets/projects/history, failed
media/evidence and MCP identity. No source fallback, Gallery, external artwork or
animation renderer, main/stable publication or Worker deployment belongs to this
repair. Live owner MCP403 remains blocked; local native tests do not prove live
ordinary-ChatGPT execution or artistic acceptance.

Root additional native cleanup/snapshot RED2/1FAIL2.642s againstFULL526, then unchanged six newregressions plus19legacycases PASS25/0FAIL6.711s with exactfactoryoverrides. Original four reusecases previously3/4FAIL2.835s; broad union89PASS27.72s. Actual signed527 export retake remains pending, so first-cut source-lifecycle defect is reproduced/fixed in scopednativechecks but actual526genericfault cause not yet independentlyconfirmed. Two older attachment-only testexpectations failed2/19before narrowfactoryseamadaptation; originals retained. Fullintegrated527/CI/originalsigning/GPUcodec stillpending.
