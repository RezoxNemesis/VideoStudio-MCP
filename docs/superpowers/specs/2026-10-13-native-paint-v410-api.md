# Frozen native paint API for the 4.1.0 integration

Classes are new files in package `com.rezoxnemesis.videostudio`. This agent owns `SketchPaintStore`, `SketchPaintBrush` and their new tests only. File operations are worker APIs; renderer/UI must prepare bounded bitmaps off the main thread. Existing package/signing/identity are untouched.

## Document descriptor and operations

Paint object uses `kind: "paint"`, full-canvas normalized bounds and a field named **`paint`**:
`{file:"<sha256>.json",sha256:"<sha256>",width:720,height:1280,tileSize:256}`.

External `sketch_edit` operation:
```
{type:"paint_stroke", objectId, strokeId, frameIndex?, points:[[x,y,pressure],...],
 brush:{mode:"paint"|"erase"|"clone",color:"#AARRGGBB",sizePx,
        opacity?,flow?,hardness?,spacing?,pressure?,texture?:"smooth"|"grain",
        seed?,angle?,tip?:"round"|"flat"},
 alphaLock?:boolean,clip?:{x,y,width,height},
 cloneSource?:{objectId:<owned image object>,sha256:<its reference PNG hash>,offsetX?,offsetY?,scale?,rotation?,mapping?}}
```

Points normalized 0..1, 1..2,048; size 0.5..256 px; opacity/flow/hardness 0..1; spacing 0.05..1; angle -180..180; seed integer 0..2,147,483,647. Defaults opacity=1, flow=1, hardness=1, spacing=.2, pressure=true, texture=smooth, seed=0, angle=0, tip=round. Clone source offsets -1..1 in normalized source-image space; scale .01..8, rotation -180..180, defaults 1/0. Optional `mapping` is six finite numbers `[a,b,c,d,tx,ty]`, mapping paint-local normalized coordinates to normalized source `(u=a*x+c*y+tx, v=b*x+d*y+ty)`. Linear terms -8..8, translations -4..4, nonzero determinant; mapping is exclusive with offset/scale/rotation. With no mapping, native code uses the reference's crop/bounds/transform and then applies normalized source offset and inverse scale/rotation about source center. Frozen final mapping survives later pose/page/guide changes. Clone source allowed only for clone mode; erase plus alphaLock rejects. Plan bounds ≤ 8,192 dabs and ≤ 32,000,000 pixel visits before writes. Clip fits normalized canvas. A single source-sampled center color is not clone mode; the footprint samples texture per target pixel.

`frameIndex` optionally captures the active exposed frame (0..1199); native code validates the document and selected cel exposure. Omission uses the cel's first frame. Default clone source mapping uses that captured frame's guide/layer/object transforms. Live playback uses the same captured frame. Explicit affine source mapping remains intrinsic and precise.

Integrators create empty paint cels using `empty`; storage preparation returns authoritative descriptor and replay event. They must not pass unresolved `paint_stroke` to pure vector `apply`.

## `SketchPaintStore` public API

```java
public final class SketchPaintStore implements AutoCloseable {
  public static final int TILE_SIZE = 256;
  public SketchPaintStore(Context context, String sketchId) throws Exception;
  public static void validateDescriptor(JSONObject descriptor) throws Exception;
  public JSONObject empty(int width, int height) throws Exception;
  public PreparedChange prepare(JSONObject document, JSONObject operation) throws Exception;
  public void verify(JSONObject descriptor) throws Exception;
  public Bitmap render(JSONObject descriptor, int maxDimension, JSONObject crop) throws Exception;
  public Bitmap renderPrefix(JSONObject visualOperation, double fraction,
                             int maxDimension, JSONObject crop) throws Exception;
  public String sample(JSONObject descriptor, double x, double y) throws Exception;
  public String sampleReference(JSONObject document, String referenceObjectId,
                                String expectedSha256, double x, double y, int frame) throws Exception;
  public JSONArray journal(JSONObject descriptor, int offset, int limit) throws Exception;
  public java.util.LinkedHashMap<String, File> dependencies(JSONObject descriptor) throws Exception;
  public void copyTo(String targetSketchId, JSONObject descriptor) throws Exception;
  public void close();
  public static final class PreparedChange {
    public final JSONObject descriptor;
    public final JSONObject visualOperation;
    public final String strokeSha256;
    public final int dirtyTileCount;
  }
}
```

`prepare` finds the selected paint object from document, checks width/height and unlocked owner layer, and pins original descriptor. Caller owns revision/idempotency checks and final SQLite swap. Preparation writes only immutable owned assets and does not edit the document. `visualOperation` is internal-only:
`{type:"paint_stroke",objectId,strokeId,beforePaint:<descriptor>,afterPaint:<descriptor>,stroke:<frozen journal record>}`.
It contains enough native-owned source pin/mapping data for replay even if the guide object is removed. Native generated event is never accepted verbatim as external input.

`render`/`renderPrefix` return caller-owned ARGB_8888 Bitmap, with longest output edge ≤ maxDimension (128..1024); optional normalized crop is in full paint-canvas coordinates. Rendering uses bounded output pixels and streamed tiles; no full-canvas bitmap is first allocated. Prefix calls perform no durable mutation. A renderer may cache these prepared results, then blit them on the UI. Prefix fraction 0 is the before snapshot and fraction 1 matches the after snapshot pixels. Returned bitmaps must be recycled only after UI detachment.

`sample` uses paint-local normalized coordinates; `sampleReference` uses full-document normalized coordinates and the image object's frame-specific crop/transform. Both return #AARRGGBB. They are positive native read APIs and never browse Gallery.

`journal` returns newest-first frozen journal records, offset 0..8192, limit 1..128. `dependencies` includes current tiles, ancestor manifests/journals and frozen copied clone sources under safe relative paths starting `paint/`; its files are hash-verified. `copyTo` prepares that closure in the target owned directory; it does not mutate target DB. Archive code uses the same closure and inventory hashes. No destructive GC.

Store layout: `files/sketch_studio/<sketchId>/paint/{<hash>.json,tiles/<hash>.png,strokes/<hash>.json,sources/<hash>.png}`. Manifest ≤ 64 KiB; each PNG tile is exactly 256² and ≤ 384 KiB. Immutable journal pins before snapshot, not after (avoid circular hashes). After pins are in manifest/receipt/event. Per sketch paint budget 512 MiB, free-space headroom 64 MiB. Source references are copied from existing `SketchProjectStore.referenceFile` into immutable paint sources; original files are read-only.

## `SketchPaintBrush` public API

```java
public final class SketchPaintBrush {
  public static Plan plan(JSONObject operation, int canvasWidth, int canvasHeight) throws Exception;
  public static void apply(Bitmap targetTile, Bitmap beforeTile, int tileX, int tileY,
                           Plan plan, Source source, double fraction) throws Exception;
  public interface Source { int sample(double paintX, double paintY) throws Exception; }
  public static final class Plan {
    public final JSONObject stroke; // normalized request/settings, detached copy
    public final int dabCount;
  }
}
```

Tile indexes are grid indexes, not pixel offsets. `Source.sample` receives paint-local native pixel coordinates and returns ARGB; it is required for clone only. `beforeTile` is the immutable alpha-lock baseline and may be null for an empty tile. Brush applies deterministic real pressure/tip/flow/softness/grain coverage, with clipping and source-over/erase compositing. It performs no filesystem work. Fraction is 0..1 and controls a continuous ordered dab prefix, used by store replay. Plans and returned JSON are detached from caller input. Source callbacks and all expensive painting run on a worker.

## Deferred surface

Journal replacement/removal replay UI is deferred until the first native engine, shared brush controls and source sampling are verified; do not advertise editable old strokes before that implementation. Snapshot undo and redo are provided immediately by existing history. Stronger full-canvas resolution is also gated on preview/onion integration being bounded.
