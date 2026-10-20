package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** The Phase 5 engine now changes genuine per-object native drawn geometry. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchNaturalMotionBridgeTest {
    private static JSONObject sample()throws Exception{
        JSONObject doc=SketchDocument.create(new JSONObject()
                .put("sketchId","acting_test").put("name","Sample actor motion")
                .put("width",256).put("height",256)
                .put("fps",30).put("frameCount",90));
        SketchEditOps.apply(doc,new JSONObject().put("type","add_object")
                .put("layerId","layer_1").put("object",new JSONObject()
                        .put("id","actor_head").put("kind","ellipse")
                        .put("bounds",new JSONObject().put("x",.15).put("y",.37)
                                .put("width",.10).put("height",.12))
                        .put("color","#FFCC3388").put("fill",true)
                        .put("frame",0).put("exposure",1)));
        return doc;
    }
    private static JSONObject move()throws Exception{
        return new JSONObject().put("type","apply_acting_motion")
                .put("objectId","actor_head").put("startFrame",0)
                .put("x",.48).put("y",-.12).put("rotation",18)
                .put("anticipationFrames",8).put("driveFrames",12)
                .put("recoilFrames",6).put("recoveryFrames",12)
                .put("extendExposure",true);
    }
    @Test public void omegMotionWritesActualEditableSubjectKeysAndExtendsExposure()throws Exception {
        JSONObject doc=sample();
        SketchEditOps.apply(doc,move());
        SketchDocument.validate(doc);
        JSONObject actor=SketchDocument.object(doc,"actor_head");
        JSONArray keys=actor.getJSONArray("keys");
        assertTrue(keys.length()>14);
        assertEquals(39,actor.getInt("exposure"));
        assertEquals(0,keys.getJSONObject(0).getInt("frame"));
        assertEquals(38,keys.getJSONObject(keys.length()-1).getInt("frame"));
        double p0=SketchDocument.transformAt(actor,0).getDouble("x");
        double anticipation=SketchDocument.transformAt(actor,6).getDouble("x");
        double impact=SketchDocument.transformAt(actor,20).getDouble("x");
        double end=SketchDocument.transformAt(actor,38).getDouble("x");
        assertTrue("Subject must anticipate",Math.abs(anticipation-p0)>.001);
        assertTrue("Subject must move, not just camera",Math.abs(impact-p0)>.2);
        assertEquals(.48,end,1e-6);
        assertEquals(18,SketchDocument.transformAt(actor,38).getDouble("rotation"),1e-7);
        assertEquals("ellipse",actor.getString("kind"));
    }
    @Test public void refusesExistingKeysAndLockedLayerWithoutOverwritingThePerson()throws Exception{
        JSONObject original=sample();JSONObject op=move();
        SketchEditOps.apply(original,op);
        String after=SketchDocument.canonical(original);
        try{SketchEditOps.apply(original,op);fail();}
        catch(IllegalStateException expected){
            assertTrue(expected.getMessage().contains("Existing acting key"));
        }
        assertEquals(after,SketchDocument.canonical(original));
        original.getJSONArray("layers").getJSONObject(0).put("locked",true);
        String locked=SketchDocument.canonical(original);
        try{SketchEditOps.apply(original,new JSONObject(op.toString())
                .put("replaceExistingKeys",true));fail();}
        catch(IllegalStateException expected){}
        assertEquals(locked,SketchDocument.canonical(original));
    }
    @Test public void missingExposureConsentAndOutOfBoundsSceneStopBeforeWrite()throws Exception {
        JSONObject doc=sample();String before=SketchDocument.canonical(doc);
        try{SketchEditOps.apply(doc,new JSONObject(move().toString())
                .put("extendExposure",false));fail();}
        catch(IllegalArgumentException expected){}
        assertEquals(before,SketchDocument.canonical(doc));
        try{SketchEditOps.apply(doc,new JSONObject(move().toString())
                .put("x",8));fail();}
        catch(IllegalArgumentException expected){}
        assertEquals(before,SketchDocument.canonical(doc));
        try{SketchEditOps.apply(doc,new JSONObject(move().toString())
                .put("driveFrames",1000));fail();}
        catch(IllegalArgumentException expected){}
        assertEquals(before,SketchDocument.canonical(doc));
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void actualCanvasRendersDifferentSubjectPosesWithoutCameraTrick()throws Exception{
        JSONObject doc=sample();
        SketchEditOps.apply(doc,move());
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(
                RuntimeEnvironment.getApplication(),doc)){
            Bitmap atStart=renderer.render(0),atImpact=renderer.render(20);
            int size=atStart.getWidth()*atStart.getHeight();
            int[] a=new int[size],b=new int[size];
            atStart.getPixels(a,0,atStart.getWidth(),0,0,
                    atStart.getWidth(),atStart.getHeight());
            atImpact.getPixels(b,0,atImpact.getWidth(),0,0,
                    atImpact.getWidth(),atImpact.getHeight());
            int moved=0;
            for(int i=0;i<size;i++)if(a[i]!=b[i])moved++;
            assertTrue("Actual authored ellipse must visibly animate",moved>200);
        }
    }
}
