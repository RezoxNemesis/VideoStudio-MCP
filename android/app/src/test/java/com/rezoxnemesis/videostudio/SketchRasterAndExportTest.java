package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchRasterAndExportTest {
    final Context context = RuntimeEnvironment.getApplication();
    JSONObject create(int count, int fps) throws Exception {
        String id = UUID.randomUUID().toString(); SketchEditOps.execute(context, "sketch_create", new JSONObject().put("sketchId", id).put("operationId", "create").put("width", 256).put("height", 256).put("frameCount", count).put("fps", fps).put("background", "#00000000"));
        try (SketchProjectStore store = new SketchProjectStore(context)) { return store.get(id); }
    }
    JSONObject rect(String id, String color, double x, double y, double w, double h, int exposure) throws Exception {
        return new JSONObject().put("id", id).put("kind", "rect").put("color", color).put("fill", true).put("frame", 0).put("exposure", exposure)
                .put("bounds", new JSONObject().put("x", x).put("y", y).put("width", w).put("height", h));
    }
    JSONObject edit(JSONObject doc, JSONArray operations) throws Exception {
        SketchEditOps.execute(context, "sketch_edit", new JSONObject().put("sketchId", doc.getString("id")).put("operationId", UUID.randomUUID().toString()).put("expectedRevision", doc.getLong("revision")).put("operations", operations));
        try (SketchProjectStore store = new SketchProjectStore(context)) { return store.get(doc.getString("id")); }
    }
    JSONObject add(String layer, JSONObject object) throws Exception { return new JSONObject().put("type", "add_object").put("layerId", layer).put("object", object); }

    @Test public void dstOutErasesOnlyItsLayerAndCelExposureActuallyChangesPixels() throws Exception {
        JSONObject doc = create(3, 24);
        JSONObject eraser = new JSONObject().put("id", "eraser").put("kind", "eraser").put("width", .1).put("frame", 0).put("exposure", 1)
                .put("points", new JSONArray().put(new JSONArray().put(.2).put(.5).put(1)).put(new JSONArray().put(.8).put(.5).put(1)));
        doc = edit(doc, new JSONArray().put(add("layer_1", rect("red", "#FFFF0000", 0, 0, 1, 1, 3)))
                .put(new JSONObject().put("type", "add_layer").put("layer", new JSONObject().put("id", "top"))).put(add("top", rect("blue", "#FF0000FF", .1, .1, .8, .8, 1))).put(add("top", eraser)));
        try (SketchFrameRenderer renderer = new SketchFrameRenderer(context, doc)) {
            Bitmap frame0 = renderer.render(0), frame1 = renderer.render(1);
            try { assertEquals(Color.RED, frame0.getPixel(128,128)); assertEquals(Color.BLUE, frame0.getPixel(128,70)); assertEquals(Color.RED, frame1.getPixel(128,70)); }
            finally { frame0.recycle(); frame1.recycle(); }
        }
    }
    @Test public void pressureWidthAndTransformKeyAreMeasuredFromRealBitmapPixels() throws Exception {
        JSONObject doc = create(3, 24); JSONArray operations = new JSONArray();
        for (int i = 0; i < 2; i++) operations.put(add("layer_1", new JSONObject().put("id", "pressure" + i).put("kind", "stroke").put("width", .1).put("color", "#FF000000").put("exposure", 3)
                .put("points", new JSONArray().put(new JSONArray().put(.1).put(i == 0 ? .2 : .5).put(i)).put(new JSONArray().put(.4).put(i == 0 ? .2 : .5).put(i)))));
        operations.put(add("layer_1", rect("keyed", "#FF00FF00", .1, .7, .1, .1, 3)))
                .put(new JSONObject().put("type", "set_keyframe").put("objectId", "keyed").put("frame", 0).put("transform", new JSONObject().put("x", 0)))
                .put(new JSONObject().put("type", "set_keyframe").put("objectId", "keyed").put("frame", 2).put("transform", new JSONObject().put("x", .4)));
        doc = edit(doc, operations);
        try (SketchFrameRenderer renderer = new SketchFrameRenderer(context, doc)) {
            Bitmap frame = renderer.render(1);
            try {
                int thin = countVertical(frame, 64, 35, 70), thick = countVertical(frame, 64, 105, 152); assertTrue("Pressure must change actual brush pixels", thick > thin * 3);
                assertEquals(Color.GREEN, frame.getPixel(90,192)); assertEquals(0, Color.alpha(frame.getPixel(38,192)));
                assertEquals("keyed", SketchFrameRenderer.hitTest(doc, 1, 90 / 256d, 192 / 256d, .002));
                assertNull(SketchFrameRenderer.hitTest(doc, 1, 38 / 256d, 192 / 256d, .002));
            } finally { frame.recycle(); }
        }
    }
    @Test public void lineEllipsePolygonAndRectRasterizeDistinctGeometryAndPreviewUsesSamePixels() throws Exception {
        JSONObject doc = create(1,24);
        JSONObject line = new JSONObject().put("id","line").put("kind","line").put("color","#FFFF0000").put("width",.02)
                .put("points",new JSONArray().put(new JSONArray().put(.1).put(.1).put(1)).put(new JSONArray().put(.4).put(.1).put(1)));
        JSONObject ellipse = rect("ellipse","#FF00FF00",.55,.05,.35,.3,1).put("kind","ellipse");
        JSONObject polygon = new JSONObject().put("id","polygon").put("kind","polygon").put("color","#FF0000FF").put("fill",true)
                .put("points",new JSONArray().put(new JSONArray().put(.1).put(.6).put(1)).put(new JSONArray().put(.4).put(.6).put(1)).put(new JSONArray().put(.25).put(.9).put(1)));
        doc = edit(doc,new JSONArray().put(add("layer_1",line)).put(add("layer_1",ellipse)).put(add("layer_1",polygon)).put(add("layer_1",rect("rect","#FFFFFF00",.55,.6,.3,.3,1))));
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)) {
            Bitmap bitmap=renderer.render(0);
            try { assertEquals(Color.RED,bitmap.getPixel(64,26)); assertEquals(Color.GREEN,bitmap.getPixel(185,51)); assertEquals(Color.BLUE,bitmap.getPixel(64,180)); assertEquals(Color.YELLOW,bitmap.getPixel(179,192)); assertEquals(0,Color.alpha(bitmap.getPixel(142,14))); }
            finally { bitmap.recycle(); }
        }
        JSONObject result=SketchEditOps.execute(context,"sketch_preview",new JSONObject().put("sketchId",doc.getString("id")).put("expectedRevision",doc.getLong("revision")).put("frameIndex",0).put("maxDimension",256));
        JSONObject image=result.getJSONObject("sketchPreviewImage");byte[] bytes=android.util.Base64.decode(image.getString("base64"),android.util.Base64.DEFAULT);
        assertEquals(SketchDocument.sha256(bytes),image.getString("sha256")); assertTrue(bytes.length<=1024*1024);
        Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length);try { assertEquals(Color.BLUE,decoded.getPixel(64,180)); }finally { decoded.recycle(); }
    }
    @Test public void imageReferenceIsAnOwnedCopyAndMutationOrForeignHashIsRejected() throws Exception {
        JSONObject doc = create(1, 24); ProjectStore projects = new ProjectStore(context); ProjectStore.Project original = projects.create("Sketch reference source");
        File file = new File(context.getFilesDir(), UUID.randomUUID() + ".png"); writeImage(file, Color.MAGENTA);
        ProjectStore.Asset source = new ProjectStore.Asset(); source.id = UUID.randomUUID().toString(); source.uri = Uri.fromFile(file).toString(); source.mime = "image/png"; source.name = "Original"; original.assets.add(source); projects.save(original);
        JSONObject args = new JSONObject().put("sketchId", doc.getString("id")).put("operationId", "reference").put("expectedRevision", 1).put("projectId", original.id)
                .put("sourceProjectUpdatedAt", original.updatedAt).put("assetId", source.id).put("layerId", "layer_1").put("objectId", "image");
        SketchEditOps.execute(context, "sketch_import_reference", args); writeImage(file, Color.YELLOW);
        File owned;
        try (SketchProjectStore store = new SketchProjectStore(context)) { doc = store.get(doc.getString("id")); owned = store.referenceFile(doc.getString("id"), SketchDocument.object(doc, "image").getJSONObject("reference")); }
        assertNotEquals(file.getCanonicalPath(), owned.getCanonicalPath());
        try (SketchFrameRenderer renderer = new SketchFrameRenderer(context, doc)) { Bitmap bitmap = renderer.render(0); try { assertEquals(Color.MAGENTA, bitmap.getPixel(128,128)); } finally { bitmap.recycle(); } }
        writeImage(owned, Color.CYAN);
        try { new SketchFrameRenderer(context, doc); fail("Changed owned image admitted"); } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("REFERENCE_CHANGED")); }
        Bitmap originalBytes=BitmapFactory.decodeFile(file.getPath());try { assertEquals(Color.YELLOW, originalBytes.getPixel(0,0)); }finally { originalBytes.recycle(); }
    }
    @Test public void realPngSequenceHasRationalClockSeparateOutputAndResumableGeneration() throws Exception {
        JSONObject doc = create(6, 24); ProjectStore projects = new ProjectStore(context); ProjectStore.Project active = projects.create("Existing user project"); String activeId = active.id;
        doc = edit(doc, new JSONArray().put(add("layer_1", rect("box", "#FF3366CC", .1, .1, .8, .8, 6))));
        JSONObject args = new JSONObject().put("sketchId", doc.getString("id")).put("expectedRevision", doc.getLong("revision")).put("operationId", "export_one");
        final int[] callbacks = {0}; SketchNativeExport.Prepared prepared = SketchNativeExport.prepare(context, args, (index,count) -> { assertEquals(6,count); callbacks[0]++; });
        assertEquals(6, callbacks[0]); assertEquals(activeId, projects.active().id); assertNotEquals(activeId, prepared.project.id); assertEquals(6, prepared.project.clips.size()); assertEquals("source:256x256", prepared.aspect);
        for (int i = 0; i < 6; i++) {
            ProjectStore.Clip clip = prepared.project.clips.get(i); assertEquals(NativeFrameClock.frameDurationMs(i,24), clip.outMs); assertEquals(i,clip.effects.getInt("frameIndex"));
            ProjectStore.Asset asset = prepared.project.asset(clip.assetId); assertEquals("generated_sketch_frame",asset.role);
            Bitmap actual = BitmapFactory.decodeFile(Uri.parse(asset.uri).getPath()); assertNotNull(actual); assertEquals(0xff3366cc, actual.getPixel(128,128)); actual.recycle();
        }
        try(FileOutputStream interrupted=new FileOutputStream(prepared.encodingFile)){interrupted.write(new byte[]{1,2,3});}
        try(FileOutputStream orphan=new FileOutputStream(prepared.file)){orphan.write(new byte[]{4,5});}
        SketchNativeExport.Prepared retry = SketchNativeExport.prepare(context, args); assertEquals(prepared.project.id,retry.project.id); assertFalse(retry.file.exists()); assertFalse(retry.encodingFile.exists()); assertEquals(250000,prepared.metadata.getLong("durationUs")); assertFalse(prepared.metadata.getBoolean("decodedFrameEvidenceAvailable"));
        try { SketchNativeExport.prepare(context, new JSONObject(args.toString()).put("quality", "1080p")); fail("Conflicting export operation reused"); } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("OPERATION_ID_CONFLICT")); }
        File png = new File(Uri.parse(prepared.project.assets.get(0).uri).getPath()); writeImage(png, Color.RED);
        try { SketchNativeExport.prepare(context, args); fail("Mutated raster replayed"); } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("FRAME_CHANGED")); }
    }
    @Test public void completedReceiptRepairsInterruptedMediaBinRegistrationWithoutOverwritingOutput() throws Exception {
        JSONObject doc = create(1, 60); JSONObject args = new JSONObject().put("sketchId", doc.getString("id")).put("expectedRevision", 1).put("operationId", "completed");
        SketchNativeExport.Prepared prepared = SketchNativeExport.prepare(context,args);
        // This is a persistence fixture, explicitly not a native encoder/video acceptance test.
        try(FileOutputStream out = new FileOutputStream(prepared.encodingFile)) { out.write("synthetic encoder persistence fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        JSONObject metadata = SketchNativeExport.complete(context,prepared,new JSONObject().put("evidence", "synthetic-persistence-fixture"));
        ProjectStore projects = new ProjectStore(context); ProjectStore.Project stale = projects.get(prepared.project.id);
        StudioPrecisionEdits.apply(context,projects,new JSONObject().put("projectId",stale.id).put("expectedUpdatedAt",stale.updatedAt)
                .put("operations",new JSONArray().put(new JSONObject().put("clipId",stale.clips.get(0).id).put("set",new JSONObject().put("x",.2)))));
        java.lang.reflect.Method reconcile=SketchNativeExport.class.getDeclaredMethod("reconcileProject",Context.class,ProjectStore.class,ProjectStore.Project.class,File.class,JSONObject.class);reconcile.setAccessible(true);
        try{reconcile.invoke(null,context,projects,stale,prepared.file,metadata);fail("Stale export completion overwrote a newer edit");}
        catch(java.lang.reflect.InvocationTargetException conflict){assertTrue(conflict.getCause() instanceof IllegalStateException);}
        ProjectStore.Project output = projects.get(prepared.project.id);assertEquals(.2,output.clips.get(0).effects.getDouble("x"),0);
        output.assets.removeIf(a -> "sketch_encoded_output".equals(a.id)); output.latestExportUri = ""; projects.save(output);
        SketchNativeExport.Prepared recovered = SketchNativeExport.prepare(context,args); assertTrue(recovered.completed); assertEquals(metadata.getString("outputSha256"), recovered.metadata.getString("outputSha256"));
        output = projects.get(prepared.project.id); assertNotNull(output.asset("sketch_encoded_output")); assertEquals(Uri.fromFile(prepared.file).toString(), output.latestExportUri);
        assertFalse(recovered.metadata.getBoolean("decodedFrameEvidenceAvailable"));
    }
    private static int countVertical(Bitmap bitmap,int x,int from,int to) { int count=0; for(int y=from;y<to;y++) if(Color.alpha(bitmap.getPixel(x,y))>128)count++; return count; }
    private static void writeImage(File file,int color) throws Exception { Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888); bitmap.eraseColor(color); try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();} }
}
