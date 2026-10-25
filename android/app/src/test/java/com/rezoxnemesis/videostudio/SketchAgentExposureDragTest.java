package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/**
 * Tests author-controlled cel exposure edges, not synthetic frame generation.
 * Source is always detached until one normal native guarded sketch_edit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAgentExposureDragTest {
    private static JSONObject mark(String id,int frame,int exposure)throws Exception {
        return SketchEditOps.completeObject(new JSONObject()
                .put("id",id).put("kind","rect")
                .put("frame",frame).put("exposure",exposure)
                .put("color","#FF42A8D8").put("fill",true)
                .put("bounds",new JSONObject().put("x",.15)
                        .put("y",.2).put("width",.2).put("height",.3)));
    }
    static JSONObject drawing()throws Exception {
        JSONObject document=SketchDocument.create(new JSONObject()
                .put("sketchId","xsheet_agent_001")
                .put("name","Visible original cel timing")
                .put("frameCount",24).put("width",256).put("height",256)
                .put("fps",24));
        JSONArray original=document.getJSONArray("layers")
                .getJSONObject(0).getJSONArray("objects");
        original.put(mark("original_face",0,1))
                .put(mark("original_hair",0,1))
                .put(mark("next_actual_frame",8,1));
        SketchDocument.validate(document);
        return document;
    }
    static JSONObject drag(int frame,int hold)throws Exception {
        return new JSONObject().put("action","exposure_drag")
                .put("layerId","layer_1").put("frameIndex",frame)
                .put("exposureFrames",hold).put("durationMs",600);
    }

    @Test public void visibleExposurePlanUsesExactlyTheHumanXsheetSourceAndDoesNotMutateDrawing()throws Exception{
        JSONObject doc=drawing(),before=SketchDocument.copy(doc);
        SketchAgentExposureDrag.Plan plan=SketchAgentExposureDrag.plan(doc,drag(0,3));
        assertEquals("layer_1",plan.layerId);
        assertEquals(0,plan.frameIndex);
        assertEquals(1,plan.fromExposure);
        assertEquals(3,plan.toExposure);
        assertEquals(8,plan.maxSafeExposure);
        assertEquals(2,plan.authoredObjectIds.length());
        assertEquals(1,plan.sample(0));
        assertEquals(3,plan.sample(1));
        int previous=1;
        for(int i=0;i<=SketchAgentExposureDrag.MAX_SAMPLES;i++){
            int shown=plan.sample(i/(float)SketchAgentExposureDrag.MAX_SAMPLES);
            assertTrue(shown>=previous);previous=shown;
        }
        assertEquals("set_exposure_cel",plan.editOperation.getString("type"));
        assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(doc));
        JSONObject result=SketchDocument.copy(doc);
        SketchEditOps.apply(result,plan.editOperation);
        assertEquals(3,SketchDocument.object(result,"original_face").getInt("exposure"));
        assertEquals(3,SketchDocument.object(result,"original_hair").getInt("exposure"));
        assertEquals(1,SketchDocument.object(result,"next_actual_frame").getInt("exposure"));
        assertEquals(SketchDocument.object(doc,"original_face").getJSONObject("bounds").toString(),
                SketchDocument.object(result,"original_face").getJSONObject("bounds").toString());
        JSONArray cells=SketchXsheetProjection.project(result,0,10)
                .getJSONArray("columns").getJSONObject(0).getJSONArray("cells");
        assertEquals("drawing",cells.getJSONObject(0).getString("state"));
        assertEquals("hold",cells.getJSONObject(1).getString("state"));
        assertEquals("hold",cells.getJSONObject(2).getString("state"));
        assertEquals("blank",cells.getJSONObject(3).getString("state"));
        assertEquals("drawing",cells.getJSONObject(8).getString("state"));
        assertEquals(SketchDocument.canonical(before),SketchDocument.canonical(doc));
    }

    @Test public void untrustedInvalidBlankLockedAndOverlappingHoldsCannotBegin()throws Exception{
        JSONObject doc=drawing();
        JSONObject[] invalid={
                drag(0,9),drag(1,2),drag(24,1),
                drag(0,1),drag(0,0),
                drag(0,3).put("mediaUri","content://Gallery/private"),
                drag(0,3).put("durationMs",5000),
                drag(0,3).put("exposureFrames","Infinity"),
                drag(0,3).put("layerId","missing-layer")
        };
        for(JSONObject step:invalid)
            assertThrows(Exception.class,()->SketchAgentExposureDrag.plan(doc,step));
        JSONObject owner=SketchDocument.layer(doc,"layer_1");
        owner.put("locked",true);
        assertThrows(Exception.class,()->SketchAgentExposureDrag.plan(doc,drag(0,3)));
        owner.put("locked",false).put("visible",false);
        assertThrows(Exception.class,()->SketchAgentExposureDrag.plan(doc,drag(0,3)));
    }

    @Test public void shorteningCannotSilentlyHideAuthoredActingKeyframes()throws Exception{
        JSONObject doc=drawing();
        JSONObject eye=SketchDocument.object(doc,"original_face");
        eye.put("exposure",7);
        eye.getJSONArray("keys").put(new JSONObject()
                .put("frame",5).put("interpolation","linear")
                .put("transform",SketchDocument.transform()));
        SketchDocument.validate(doc);
        assertThrows(Exception.class,()->SketchAgentExposureDrag.plan(doc,drag(0,2)));
        assertEquals(7,SketchDocument.object(doc,"original_face").getInt("exposure"));
    }

    @Test public void stageOnlyRunsExposureDragAsItsFinalSourceChangingStep()throws Exception{
        JSONObject doc=drawing();
        JSONArray steps=new JSONArray()
                .put(new JSONObject().put("action","layer").put("layerId","layer_1"))
                .put(new JSONObject().put("action","frame").put("frameIndex",0))
                .put(drag(0,3));
        JSONObject request=new JSONObject().put("sketchId",doc.getString("id"))
                .put("operationId","xsheet-gesture-20261025")
                .put("expectedRevision",doc.getLong("revision"))
                .put("steps",steps);
        assertEquals(3,SketchAgentStageCommands.normalize(request,doc)
                .getJSONArray("steps").length());
        assertThrows(Exception.class,()->SketchAgentStageCommands.normalize(
                new JSONObject(request.toString()).put("steps",new JSONArray()
                        .put(drag(0,3))
                        .put(new JSONObject().put("action","frame").put("frameIndex",3))),doc));
        assertThrows(Exception.class,()->SketchAgentStageCommands.normalize(
                new JSONObject(request.toString())
                        .put("expectedRevision",doc.getLong("revision")+1),doc));
        assertTrue(SketchAgentStageCommands.capabilities()
                .getJSONArray("availableSteps").toString().contains("exposure_drag"));
        assertEquals("4.10.46",SketchAgentStageCommands.capabilities()
                .getString("minimumNativeVersion"));
        assertFalse(SketchAgentExposureDrag.capabilities().getBoolean("deviceMp4Verified"));
        assertFalse(SketchAgentExposureDrag.capabilities().getBoolean("galleryAccess"));
    }

    @Test public void realNativeCanvasDisplaysHeldFrameRulerAndOnlyThenIssuesReceipt()throws Exception{
        SketchAgentExposureDrag.Plan plan=SketchAgentExposureDrag.plan(drawing(),drag(0,5));
        SketchAgentExposureCanvas nativeView=
                new SketchAgentExposureCanvas(RuntimeEnvironment.getApplication(),plan);
        nativeView.layout(0,0,290,205);
        Bitmap pixels=Bitmap.createBitmap(290,205,Bitmap.Config.ARGB_8888);
        try{
            assertThrows(IllegalStateException.class,()->plan.receipt(0,5));
            nativeView.present(0f);nativeView.draw(new Canvas(pixels));
            assertEquals(1,nativeView.exposure());
            nativeView.present(1f);nativeView.draw(new Canvas(pixels));
            assertEquals(5,nativeView.exposure());
            assertTrue(nativeView.actualDraws()>=2);
            int different=0,previous=0;
            for(int y=3;y<202;y+=6)for(int x=3;x<287;x+=6){
                int rgb=pixels.getPixel(x,y);
                if(previous!=0&&rgb!=previous)different++;
                previous=rgb;
            }
            assertTrue("Native Xsheet source/hold markers, scale and moving ChatGPT hand are real pixels",different>25);
            JSONObject receipt=plan.receipt(nativeView.actualDraws(),nativeView.exposure());
            assertEquals(5,receipt.getInt("actualDisplayedExposureFrames"));
            assertEquals(2,receipt.getInt("affectedOriginalObjects"));
            assertFalse(receipt.getBoolean("newCelsGenerated"));
            assertFalse(receipt.getBoolean("renderedPixelsVerified"));
        }finally{nativeView.close();pixels.recycle();}
    }
}
