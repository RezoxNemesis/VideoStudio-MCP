package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchDrawingPolicyTest {
    interface Checked { void run() throws Exception; }
    private void rejected(Checked action) throws Exception {
        try { action.run(); fail("Expected original drawing policy rejection"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage(), expected.getMessage().startsWith("SKETCH_ORIGINAL_DRAWING_REQUIRED:")); }
    }
    private JSONObject original() throws Exception { return new JSONObject().put("drawingMethod",SketchDrawingPolicy.ORIGINAL).put("ended",false); }
    private JSONObject doc() throws Exception { return SketchDocument.create(new JSONObject().put("sketchId","policy")); }
    private JSONArray objects(JSONObject doc) throws Exception { return doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects"); }
    private JSONObject guide(double opacity) throws Exception { return new JSONObject().put("id","guide").put("kind","image").put("transform",SketchDocument.transform().put("opacity",opacity)).put("keys",new JSONArray()); }
    private JSONObject brush(String mode) throws Exception { return new JSONObject().put("type","paint_stroke").put("brush",new JSONObject().put("mode",mode)); }
    private void ops(JSONObject operation) throws Exception { SketchDrawingPolicy.requireOperations(original(),new JSONArray().put(operation)); }

    @Test public void legacySessionsNeverAcquireOriginalDrawingProvenance() throws Exception {
        assertEquals("source_assisted",SketchDrawingPolicy.method(null));
        JSONObject legacy=new JSONObject();assertEquals("source_assisted",SketchDrawingPolicy.method(legacy));
        assertFalse(SketchDrawingPolicy.requiresOriginal(legacy));
        SketchDrawingPolicy.requireOperations(legacy,new JSONArray().put(brush("clone")));
        assertFalse(SketchDrawingPolicy.requiresOriginal(original().put("ended",true)));
    }
    @Test public void originalBaselineMustBeBlankApartFromInvisibleGuides() throws Exception {
        JSONObject baseline=doc();SketchDrawingPolicy.requireBegin(baseline,"original_strokes");
        objects(baseline).put(guide(0));SketchDrawingPolicy.requireBegin(baseline,"original_strokes");
        objects(baseline).put(new JSONObject().put("kind","stroke"));
        rejected(()->SketchDrawingPolicy.requireBegin(baseline,"original_strokes"));
        SketchDrawingPolicy.requireBegin(baseline,"source_assisted");
    }
    @Test public void hiddenLayerCannotLaunderVisibleOrKeyedGuide() throws Exception {
        JSONObject baseline=doc();baseline.getJSONArray("layers").getJSONObject(0).put("visible",false);
        JSONObject guide=guide(1);objects(baseline).put(guide);
        rejected(()->SketchDrawingPolicy.requireBegin(baseline,"original_strokes"));
        guide.getJSONObject("transform").put("opacity",0);
        guide.getJSONArray("keys").put(new JSONObject().put("frame",1).put("transform",SketchDocument.transform()));
        rejected(()->SketchDrawingPolicy.requireBegin(baseline,"original_strokes"));
        rejected(()->SketchDrawingPolicy.requireDocument(original(),doc(),baseline));
    }
    @Test public void cloneAndSourcePinnedPreparedStrokesAreRejected() throws Exception {
        rejected(()->ops(brush("clone")));
        rejected(()->ops(brush("paint").put("cloneSource",new JSONObject())));
        JSONObject prepared=new JSONObject().put("type","paint_stroke").put("stroke",new JSONObject().put("request",brush("clone")));
        rejected(()->ops(prepared));
        prepared.getJSONObject("stroke").put("request",brush("paint")).put("source",new JSONObject());
        rejected(()->ops(prepared));
        ops(brush("paint"));ops(brush("erase"));
    }
    @Test public void automaticTraceCopyAndImageInsertionCannotMasqueradeAsOriginalDrawing() throws Exception {
        for(String type:new String[]{"trace_reference","vectorize_reference","duplicate_cel"})
            rejected(()->ops(new JSONObject().put("type",type)));
        rejected(()->ops(new JSONObject().put("type","add_object").put("object",guide(0))));
        rejected(()->ops(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("objects",new JSONArray().put(guide(0))))));
        rejected(()->ops(new JSONObject().put("type","add_object").put("object",new JSONObject().put("kind","paint").put("paint",new JSONObject()))));
        rejected(()->ops(new JSONObject().put("type","set_reference_visibility").put("visible",true)));
    }
    @Test public void authoredGeometryFillsEmptyPaintCelsAndHiddenImportsRemainAvailable() throws Exception {
        ops(new JSONObject().put("type","add_object").put("object",new JSONObject().put("kind","path").put("fill",true)));
        ops(new JSONObject().put("type","add_paint_cel"));
        ops(new JSONObject().put("type","set_reference_visibility").put("visible",false));
        SketchDrawingPolicy.requireAction(original(),"sketch_import_reference",new JSONObject().put("guideVisible",false));
        rejected(()->SketchDrawingPolicy.requireAction(original(),"sketch_import_reference",new JSONObject()));
        for(String action:new String[]{"sketch_undo","sketch_redo","sketch_import_frame_manifest","sketch_import_drawing_manifest"})
            rejected(()->SketchDrawingPolicy.requireAction(original(),action,new JSONObject()));
    }
    @Test public void nativeSessionBlocksCopyUntilEndedWithoutPermanentlyLockingHumanTools() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create"));
        SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","original").put("expectedRevision",1).put("drawingMethod","original_strokes"));
        JSONArray drawing=new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","line").put("kind","line").put("points",new JSONArray("[[0.2,0.2,1],[0.7,0.7,1]]"))));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","draw").put("expectedRevision",1).put("operations",drawing));
        JSONObject duplicate=new JSONObject().put("type","duplicate_cel").put("layerId","layer_1").put("fromFrame",0).put("toFrame",1).put("idPrefix","copy");
        JSONObject copyArgs=new JSONObject().put("sketchId",id).put("operationId","copy").put("expectedRevision",2).put("operations",new JSONArray().put(duplicate));
        rejected(()->SketchEditOps.execute(context,"sketch_edit",copyArgs));
        rejected(()->SketchEditOps.execute(context,"sketch_undo",new JSONObject().put("sketchId",id).put("operationId","undo").put("expectedRevision",2)));
        try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(2,store.get(id).getLong("revision"));}
        SketchEditOps.execute(context,"sketch_live_end",new JSONObject().put("sketchId",id).put("sessionId","original").put("expectedRevision",2));
        SketchEditOps.execute(context,"sketch_edit",copyArgs);
        try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(3,store.get(id).getLong("revision"));}
    }
    @Test public void nativePaintStrokeRejectsCloneBeforeAnySourceResolutionOrRevisionChange() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create"));
        SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","original").put("expectedRevision",1).put("drawingMethod","original_strokes"));
        JSONObject clone=brush("clone").put("objectId","no_cel").put("strokeId","clone").put("points",new JSONArray("[[0.2,0.2,1],[0.7,0.7,1]]"));
        rejected(()->SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","clone").put("expectedRevision",1).put("operations",new JSONArray().put(clone))));
        try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(1,store.get(id).getLong("revision"));}
    }
    @Test public void nativeOriginalPaintCreatesPixelsAndSourceFreeJournalFromEmptyCel() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256));
        SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","original").put("expectedRevision",1).put("drawingMethod","original_strokes"));
        JSONObject cel=new JSONObject().put("type","add_paint_cel").put("layerId","layer_1").put("objectId","cel");
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","empty").put("expectedRevision",1).put("operations",new JSONArray().put(cel)));
        JSONObject paint=brush("paint").put("objectId","cel").put("strokeId","red").put("points",new JSONArray("[[0.25,0.5,1],[0.75,0.5,1]]"));
        paint.getJSONObject("brush").put("color","#FFFF0000").put("sizePx",12).put("hardness",1).put("pressure",false);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId","paint").put("expectedRevision",2).put("operations",new JSONArray().put(paint)));
        JSONObject pixel=SketchEditOps.execute(context,"sketch_sample_color",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("objectId","cel").put("source","paint").put("x",.5).put("y",.5));
        assertEquals("#FFFF0000",pixel.getString("color"));
        JSONObject journal=SketchEditOps.execute(context,"sketch_paint_journal",new JSONObject().put("sketchId",id).put("expectedRevision",3).put("objectId","cel"));
        JSONObject stroke=journal.getJSONArray("strokes").getJSONObject(0);
        assertFalse(stroke.has("source"));assertEquals("paint",stroke.getJSONObject("request").getJSONObject("brush").getString("mode"));
    }
    @Test public void hiddenOwnedGuideImportRecordsRevisionWithoutRevealingItsPixels() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256));
        SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","original").put("expectedRevision",1).put("drawingMethod","original_strokes"));
        java.io.File source=new java.io.File(context.getFilesDir(),UUID.randomUUID()+".png");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
        try{bitmap.eraseColor(android.graphics.Color.RED);try(java.io.FileOutputStream out=new java.io.FileOutputStream(source)){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}}finally{bitmap.recycle();}
        ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Hidden guide");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.name="Source";asset.mime="image/png";asset.uri=android.net.Uri.fromFile(source).toString();project.assets.add(asset);projects.save(project);
        SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","guide").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","guide").put("guideVisible",false));
        JSONObject events=SketchEditOps.execute(context,"sketch_live_events",new JSONObject().put("sketchId",id).put("afterSequence",0));
        assertEquals(1,events.getJSONArray("events").length());JSONObject event=events.getJSONArray("events").getJSONObject(0);
        assertEquals(1,event.getLong("baseRevision"));assertEquals(2,event.getLong("revision"));
        JSONObject image=event.getJSONArray("operations").getJSONObject(0).getJSONObject("object");
        assertEquals("image",image.getString("kind"));assertEquals(0,image.getJSONObject("transform").getDouble("opacity"),0);
        JSONObject pixel=SketchEditOps.execute(context,"sketch_sample_color",new JSONObject().put("sketchId",id).put("expectedRevision",2).put("source","composite").put("x",.5).put("y",.5));
        assertEquals("#FFFFFFFF",pixel.getString("color"));
    }
    @Test public void directUnrecordedStoreMutationAndPageCopyCannotBypassActivePolicy() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString(),sourceId=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create"));
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",sourceId).put("operationId","create"));
        SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId","original").put("expectedRevision",1).put("drawingMethod","original_strokes"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            rejected(()->store.mutate(id,"unrecorded",1,"fingerprint",current->current.put("background","#FFFF0000")));
            rejected(()->store.copyDrawingToPage(store.get(sourceId),store.get(id)));
            assertEquals(1,store.get(id).getLong("revision"));assertEquals("#FFFFFFFF",store.get(id).getString("background"));
            assertEquals(0,store.liveEvents(id,0,4).getJSONArray("events").length());
        }
    }
}
