package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.widget.FrameLayout;
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
 * Native timing edit admission, original key preservation, actual graph
 * raster pixels and durable stage protocol boundaries. Device playback still
 * needs separately verified proof and is never inferred from Robolectric.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAgentCurveDragTest {
    private static JSONArray handles(double x1,double y1,double x2,double y2) throws Exception {
        return new JSONArray().put(x1).put(y1).put(x2).put(y2);
    }
    private static JSONObject key(int frame,JSONObject transform,JSONArray curve)throws Exception{
        return new JSONObject().put("frame",frame)
                .put("transform",SketchDocument.copy(transform))
                .put("interpolation","bezier")
                .put("curve",new JSONArray(curve.toString()));
    }
    static JSONObject drawing()throws Exception{
        JSONObject doc=SketchAgentGestureOpsTest.drawing();
        JSONObject rectangle=SketchDocument.object(doc,"ink_rect");
        JSONArray animation=rectangle.getJSONArray("keys");
        animation.put(key(0,SketchDocument.transform(),handles(.2,.1,.8,.9)));
        animation.put(key(8,SketchDocument.transform().put("x",.4),
                handles(.3,.3,.7,.8)));
        JSONObject path=SketchDocument.object(doc,"edit_curve");
        JSONArray base=path.getJSONArray("commands");
        path.put("shapeKeys",new JSONArray()
                .put(new JSONObject().put("frame",0)
                        .put("commands",new JSONArray(base.toString()))
                        .put("interpolation","bezier")
                        .put("curve",handles(.25,.2,.75,.8)))
                .put(new JSONObject().put("frame",8)
                        .put("commands",new JSONArray(base.toString()))
                        .put("interpolation","bezier")
                        .put("curve",handles(.3,.25,.75,.7))));
        JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);
        layer.getJSONArray("keys")
                .put(key(0,SketchDocument.transform(),handles(.2,.1,.8,.9)))
                .put(key(8,SketchDocument.transform().put("opacity",.7),
                        handles(.3,.4,.6,.8)));
        SketchDocument.validate(doc);
        return doc;
    }
    static JSONObject request(String mode,String field,String id,int frame)throws Exception {
        return new JSONObject().put("action","curve_handle_drag").put("mode",mode)
                .put(field,id).put("frameIndex",frame)
                .put("handleIndex",0).put("x",.4).put("y",.6)
                .put("durationMs",600);
    }

    @Test public void transformCurveDragUsesExistingExactAuthoredKeyAndDoesNotMutateOriginal()throws Exception{
        JSONObject doc=drawing();
        String before=SketchDocument.canonical(doc);
        JSONObject originalKey=SketchDocument.copy(
                SketchDocument.object(doc,"ink_rect").getJSONArray("keys").getJSONObject(0));
        SketchAgentCurveDrag.Plan plan=SketchAgentCurveDrag.plan(doc,
                request("transform","objectId","ink_rect",0));
        assertEquals("set_keyframe",plan.editOperation.getString("type"));
        assertEquals("ink_rect",plan.editOperation.getString("objectId"));
        assertEquals(0,plan.frameIndex);
        assertEquals(600,plan.durationMs);
        assertEquals(.2,plan.sample(0).getDouble(0),.000001);
        assertEquals(.4,plan.sample(1).getDouble(0),.000001);
        assertEquals(.3,plan.sample(.5f).getDouble(0),.00001);
        assertEquals(.35,plan.sample(.5f).getDouble(1),.00001);
        assertEquals(before,SketchDocument.canonical(doc));

        JSONObject edited=SketchDocument.copy(doc);
        SketchEditOps.apply(edited,plan.editOperation);
        JSONObject changed=SketchDocument.object(edited,"ink_rect")
                .getJSONArray("keys").getJSONObject(0);
        assertEquals(.4,changed.getJSONArray("curve").getDouble(0),.000001);
        assertEquals(.6,changed.getJSONArray("curve").getDouble(1),.000001);
        assertEquals(originalKey.getJSONObject("transform").toString(),
                changed.getJSONObject("transform").toString());
        assertEquals(2,SketchDocument.object(edited,"ink_rect")
                .getJSONArray("keys").length());
    }

    @Test public void shapeCurveHandleChangesOnlyTimingAndNotPathGeometry()throws Exception{
        JSONObject doc=drawing();
        JSONObject path=SketchDocument.object(doc,"edit_curve");
        JSONArray originalPath=new JSONArray(path.getJSONArray("commands").toString());
        JSONArray before=new JSONArray(path.getJSONArray("shapeKeys")
                .getJSONObject(0).getJSONArray("commands").toString());
        SketchAgentCurveDrag.Plan plan=SketchAgentCurveDrag.plan(doc,
                request("shape","objectId","edit_curve",0)
                        .put("handleIndex",1).put("x",.61).put("y",.35));
        assertEquals("set_shape_key",plan.editOperation.getString("type"));
        assertEquals(1,plan.handleIndex);
        assertEquals(.61,plan.targetCurve.getDouble(2),.000001);
        assertEquals(.35,plan.targetCurve.getDouble(3),.000001);
        JSONObject clone=SketchDocument.copy(doc);
        SketchEditOps.apply(clone,plan.editOperation);
        JSONObject saved=SketchDocument.object(clone,"edit_curve");
        assertEquals(originalPath.toString(),saved.getJSONArray("commands").toString());
        assertEquals(before.toString(),saved.getJSONArray("shapeKeys")
                .getJSONObject(0).getJSONArray("commands").toString());
        assertEquals(.61,saved.getJSONArray("shapeKeys").getJSONObject(0)
                .getJSONArray("curve").getDouble(2),.000001);
        assertEquals(.25,path.getJSONArray("shapeKeys").getJSONObject(0)
                .getJSONArray("curve").getDouble(0),.000001);
    }

    @Test public void visibleTimingHandleCanAlsoTargetOriginalLayer()throws Exception{
        JSONObject doc=drawing();
        String layer=doc.getJSONArray("layers").getJSONObject(0).getString("id");
        SketchAgentCurveDrag.Plan plan=SketchAgentCurveDrag.plan(doc,
                request("transform","layerId",layer,0)
                        .put("handleIndex",1).put("x",.71).put("y",.28));
        assertEquals(layer,plan.ownerId);
        assertEquals("set_keyframe",plan.editOperation.getString("type"));
        assertEquals(layer,plan.editOperation.getString("layerId"));
        JSONObject copy=SketchDocument.copy(doc);
        SketchEditOps.apply(copy,plan.editOperation);
        assertEquals(.71,SketchDocument.layer(copy,layer).getJSONArray("keys")
                .getJSONObject(0).getJSONArray("curve").getDouble(2),.000001);
    }

    @Test public void malformedStaleOrHiddenCurveCannotEnterAgentStage()throws Exception{
        JSONObject doc=drawing();
        JSONObject request=request("transform","objectId","ink_rect",0);
        JSONObject[] invalid=new JSONObject[]{
            new JSONObject(request.toString()).put("frameIndex",7),
            new JSONObject(request.toString()).put("handleIndex",4),
            new JSONObject(request.toString()).put("x",-2),
            new JSONObject(request.toString()).put("y",2),
            new JSONObject(request.toString()).put("durationMs",5000),
            new JSONObject(request.toString()).put("gallery","content://private"),
            new JSONObject(request.toString()).put("objectId","nonexistent"),
            new JSONObject(request.toString()).put("mode","shape"),
            new JSONObject(request.toString()).put("layerId","some-other"),
            new JSONObject(request.toString()).put("x",.2).put("y",.1)
        };
        for(JSONObject invalidOp:invalid)
            assertThrows(Exception.class,()->SketchAgentCurveDrag.plan(doc,invalidOp));
        JSONObject original=SketchDocument.owner(doc,"ink_rect");
        original.put("locked",true);
        assertThrows(Exception.class,()->SketchAgentCurveDrag.plan(doc,request));
        original.put("locked",false).put("visible",false);
        assertThrows(Exception.class,()->SketchAgentCurveDrag.plan(doc,request));
    }

    @Test public void stageOnlyAllowsFinalSourceChangingHandleAndNeverClaimsQueuedSave()throws Exception{
        JSONObject doc=drawing();
        JSONArray steps=new JSONArray().put(
                new JSONObject().put("action","frame").put("frameIndex",0))
                .put(request("transform","objectId","ink_rect",0));
        JSONObject program=new JSONObject().put("sketchId",doc.getString("id"))
                .put("operationId","source-pinned-curve-001")
                .put("expectedRevision",doc.getLong("revision")).put("steps",steps);
        JSONObject normalized=SketchAgentStageCommands.normalize(program,doc);
        assertEquals(2,normalized.getJSONArray("steps").length());
        assertThrows(Exception.class,()->SketchAgentStageCommands.normalize(
                new JSONObject(program.toString()).put("steps",new JSONArray()
                        .put(request("transform","objectId","ink_rect",0))
                        .put(new JSONObject().put("action","frame").put("frameIndex",8))),doc));
        assertThrows(Exception.class,()->SketchAgentStageCommands.normalize(
                new JSONObject(program.toString()).put("expectedRevision",doc.getLong("revision")+1),doc));
        assertEquals("4.10.45",SketchAgentCurveDrag.capabilities()
                .getString("minimumNativeVersion"));
        assertTrue(SketchAgentStageCommands.capabilities()
                .getJSONArray("availableSteps").toString().contains("curve_handle_drag"));
    }

    @Test public void actualSketchCanvasDrawsAcceptedTimingGraphBeforeVisualReceipt()throws Exception {
        JSONObject doc=drawing();
        SketchAgentCurveDrag.Plan plan=SketchAgentCurveDrag.plan(doc,
                request("transform","objectId","ink_rect",0));
        SketchAgentCurveCanvas graph=new SketchAgentCurveCanvas(
                RuntimeEnvironment.getApplication(),plan);
        graph.layout(0,0,264,237);
        Bitmap pixels=Bitmap.createBitmap(264,237,Bitmap.Config.ARGB_8888);
        try{
            assertThrows(IllegalStateException.class,()->
                plan.visualReceipt(0,plan.targetCurve));
            graph.present(0f);
            graph.draw(new Canvas(pixels));
            assertEquals(.2,graph.displayedCurve().getDouble(0),.000001);
            graph.present(1f);
            graph.draw(new Canvas(pixels));
            assertTrue(graph.atTarget());
            assertTrue(graph.actualDraws()>=1);
            int difference=0;int prior=0;
            for(int y=0;y<237;y+=5)for(int x=0;x<264;x+=5){
                int rgb=pixels.getPixel(x,y);
                if(prior!=0&&prior!=rgb)difference++;
                prior=rgb;
            }
            assertTrue("Real on-screen graph, control handles and visible agent pen",difference>30);
            JSONObject receipt=plan.visualReceipt(graph.actualDraws(),graph.displayedCurve());
            assertFalse(receipt.getBoolean("renderedMp4Verified"));
            assertTrue(receipt.getBoolean("sourcePixelsUnchangedUntilCommit"));
        }finally{graph.close();pixels.recycle();}
    }
}
