package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

/**
 * End-to-end source-safe cel exposure edit: authored geometry remains owned,
 * holds match the actual renderer and native MCP writes share the sketch_edit
 * revision and undo history.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchWritableXsheetTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private static JSONObject mark(String id,int frame,int exposure) throws Exception {
        return SketchEditOps.completeObject(new JSONObject()
                .put("id",id).put("kind","rect").put("frame",frame)
                .put("exposure",exposure).put("color","#FF3088BD")
                .put("fill",true).put("bounds",new JSONObject()
                        .put("x",.12).put("y",.20).put("width",.18).put("height",.2)));
    }
    private static JSONObject base() throws Exception {
        JSONObject d=SketchDocument.create(new JSONObject().put("sketchId","xs_edit_test")
                .put("frameCount",24).put("width",256).put("height",256));
        JSONArray marks=d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        marks.put(mark("line_eye",0,1)).put(mark("line_hair",0,1))
                .put(mark("next_cel",8,2));
        SketchDocument.validate(d);
        return d;
    }
    private static JSONObject hold(String layer,int frame,int duration)throws Exception {
        return new JSONObject().put("type","set_exposure_cel")
                .put("layerId",layer).put("frameIndex",frame)
                .put("exposureFrames",duration);
    }

    @Test public void oneCelHoldRetimesMultipleAuthoredMarksWithoutNewImagesOrPoints()throws Exception{
        JSONObject source=base();
        String immutable=SketchDocument.canonical(source);
        JSONObject planned=SketchXsheetEdits.plan(source,hold("layer_1",0,3));
        assertTrue(planned.getBoolean("readOnly"));
        assertEquals(2,planned.getInt("changedObjectCount"));
        assertEquals(2,planned.getInt("sourceObjectCount"));
        assertEquals(8,planned.getInt("maximumSafeExposureFrames"));
        assertEquals(3,planned.getInt("requestedExposureFrames"));
        assertEquals(immutable,SketchDocument.canonical(source));

        JSONObject after=SketchDocument.copy(source);
        SketchEditOps.apply(after,hold("layer_1",0,3));
        assertEquals(3,SketchDocument.object(after,"line_eye").getInt("exposure"));
        assertEquals(3,SketchDocument.object(after,"line_hair").getInt("exposure"));
        assertEquals(2,SketchDocument.object(after,"next_cel").getInt("exposure"));
        assertEquals(source.getJSONArray("layers").getJSONObject(0)
                        .getJSONArray("objects").getJSONObject(0).getJSONObject("bounds").toString(),
                SketchDocument.object(after,"line_eye").getJSONObject("bounds").toString());
        JSONArray row=SketchXsheetProjection.project(after,0,10)
                .getJSONArray("columns").getJSONObject(0).getJSONArray("cells");
        assertEquals("drawing",row.getJSONObject(0).getString("state"));
        assertEquals("hold",row.getJSONObject(1).getString("state"));
        assertEquals("hold",row.getJSONObject(2).getString("state"));
        assertEquals("blank",row.getJSONObject(3).getString("state"));
        assertEquals("drawing",row.getJSONObject(8).getString("state"));
        assertEquals(immutable,SketchDocument.canonical(source));
    }

    @Test public void cannotOverlapNextCelOrEditAProtectedLayerOrHeldBlank()throws Exception{
        JSONObject source=base();
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,hold("layer_1",0,9)));
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,hold("layer_1",1,2)));
        JSONObject frozen=SketchDocument.copy(source);
        frozen.getJSONArray("layers").getJSONObject(0).put("locked",true);
        assertThrows(IllegalStateException.class,()->
                SketchXsheetEdits.plan(frozen,hold("layer_1",0,2)));
        assertEquals(1,SketchDocument.object(source,"line_hair").getInt("exposure"));
    }

    @Test public void shorteningCannotSilentlyCutAcrossAuthoredTransformOrShapeAnimation()throws Exception{
        JSONObject source=base();
        JSONObject eye=SketchDocument.object(source,"line_eye");
        eye.put("exposure",6);
        eye.getJSONArray("keys").put(new JSONObject()
                .put("frame",4).put("interpolation","linear")
                .put("transform",SketchDocument.transform()));
        SketchDocument.validate(source);
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,hold("layer_1",0,2)));
        eye.put("keys",new JSONArray());
        eye.put("shapeKeys",new JSONArray().put(new JSONObject()
                .put("frame",4).put("commands",new JSONArray())));
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,hold("layer_1",0,2)));
    }

    @Test public void noPhantomRevisionsForRedundantOrUnusableHolds()throws Exception{
        JSONObject source=base();
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,hold("layer_1",0,1)));
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,hold("layer_1",8,0)));
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,hold("layer_1",24,3)));
        assertThrows(IllegalArgumentException.class,()->
                SketchXsheetEdits.plan(source,new JSONObject()
                        .put("type","set_exposure_cel")
                        .put("layerId","layer_1").put("frameIndex",0)
                        .put("exposureFrames",3).put("galleryUri","file:///secret")));
    }

    @Test public void authenticatedNativeSketchEditRetainsIdempotenceRevisionAndUndo()throws Exception{
        String id="xsheet_mcp_"+UUID.randomUUID();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","new")
                .put("width",256).put("height",256).put("frameCount",24));
        JSONArray initial=new JSONArray()
                .put(new JSONObject().put("type","add_object")
                        .put("layerId","layer_1").put("object",mark("original_ink",0,1)))
                .put(new JSONObject().put("type","add_object")
                        .put("layerId","layer_1").put("object",mark("second_ink",8,1)));
        SketchEditOps.execute(context,"sketch_edit",new JSONObject()
                .put("sketchId",id).put("operationId","original_native_ink")
                .put("expectedRevision",1).put("operations",initial));
        JSONObject request=new JSONObject().put("sketchId",id)
                .put("operationId","native_xsheet_hold")
                .put("expectedRevision",2)
                .put("operations",new JSONArray().put(hold("layer_1",0,3)));
        JSONObject first=SketchEditOps.execute(context,"sketch_edit",request);
        JSONObject retry=SketchEditOps.execute(context,"sketch_edit",request);
        assertEquals(first.getLong("revision"),retry.getLong("revision"));
        assertEquals(3,first.getLong("revision"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            JSONObject saved=store.get(id);
            assertEquals(3,SketchDocument.object(saved,"original_ink").getInt("exposure"));
            assertEquals(1,SketchDocument.object(saved,"second_ink").getInt("exposure"));
            assertEquals(2,saved.getJSONArray("layers").getJSONObject(0)
                    .getJSONArray("objects").length());
            assertEquals("hold",SketchXsheetProjection.project(saved,1,1)
                    .getJSONArray("columns").getJSONObject(0).getJSONArray("cells")
                    .getJSONObject(0).getString("state"));
        }
        assertThrows(IllegalStateException.class,()->
                SketchEditOps.execute(context,"sketch_edit",
                        new JSONObject().put("sketchId",id).put("operationId","stale-other")
                                .put("expectedRevision",2)
                                .put("operations",new JSONArray().put(hold("layer_1",0,2)))));
        SketchEditOps.execute(context,"sketch_undo",new JSONObject()
                .put("sketchId",id).put("operationId","undo_xsheet")
                .put("expectedRevision",3));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            JSONObject undo=store.get(id);
            assertEquals(1,SketchDocument.object(undo,"original_ink").getInt("exposure"));
            assertNotNull(SketchDocument.object(undo,"original_ink"));
        }
    }

    @Test public void capabilitiesTruthfullyExposeOneNativeSourcePreservingOperation()throws Exception{
        JSONObject nativeFlags=SketchCapabilities.get().getJSONObject("nativeXsheet")
                .getJSONObject("writableExposureEdit");
        assertEquals("sketch_edit",nativeFlags.getString("action"));
        assertEquals("set_exposure_cel",nativeFlags.getString("operationType"));
        assertTrue(nativeFlags.getBoolean("collisionChecked"));
        assertFalse(nativeFlags.getBoolean("renderVerifiedOnPhone"));
        assertFalse(nativeFlags.getBoolean("galleryAccess"));
    }
}
