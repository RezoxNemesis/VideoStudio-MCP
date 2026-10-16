package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Deliberately expose, bound and test loss of geometric precision in large 2D scenes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationKeyBudgetTest {
    private JSONObject source()throws Exception {
        JSONObject s=SketchDocument.create(new JSONObject()
                .put("sketchId","compression_source")
                .put("width",320).put("height",480).put("frameCount",1));
        JSONArray objects=s.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        for(String id:new String[]{"left_arm","right_arm"}){
            JSONArray points=new JSONArray()
                    .put(new JSONArray().put(id.equals("left_arm")?.30:.70).put(.40).put(1))
                    .put(new JSONArray().put(id.equals("left_arm")?.24:.76).put(.61).put(1));
            objects.put(SketchEditOps.completeObject(new JSONObject()
                    .put("id",id).put("kind","line").put("points",points)));
        }
        return s;
    }
    private JSONObject motion(JSONObject s)throws Exception {
        JSONObject p=SketchAnimationUiModel.createProgram(s).put("frameCount",72);
        JSONArray binds=SketchAnimationUiModel.rig(p,"character_1").getJSONArray("bindings");
        binds.put(new JSONObject().put("objectId","left_arm").put("jointId","arm_left"));
        binds.put(new JSONObject().put("objectId","right_arm").put("jointId","arm_right"));
        SketchAnimationUiModel.setRotation(p,"character_1",0,"arm_left",0,"smooth");
        SketchAnimationUiModel.setRotation(p,"character_1",20,"arm_left",52,"smooth");
        SketchAnimationUiModel.setRotation(p,"character_1",38,"arm_left",-35,"smooth");
        SketchAnimationUiModel.setRotation(p,"character_1",71,"arm_left",7,"smooth");
        SketchAnimationUiModel.setRotation(p,"character_1",0,"arm_right",0,"smooth");
        SketchAnimationUiModel.setRotation(p,"character_1",20,"arm_right",-44,"smooth");
        SketchAnimationUiModel.setRotation(p,"character_1",38,"arm_right",33,"smooth");
        SketchAnimationUiModel.setRotation(p,"character_1",71,"arm_right",0,"smooth");
        return p;
    }
    @Test public void explicitPixelToleranceReducesOnlyCompiledKeysWithoutMutatingDrawings()throws Exception {
        JSONObject source=source(),normal=motion(source);
        String sourceFrozen=SketchDocument.canonical(source),normalFrozen=SketchDocument.canonical(normal);
        JSONObject packed=SketchDocument.copy(normal).put("keyframeTolerancePixels",2.0);
        JSONObject strict=SketchAnimationCompiler.compile(source,normal,"strict_animation");
        JSONObject coarse=SketchAnimationCompiler.compile(source,packed,"packed_animation");
        assertEquals(.25,strict.getJSONObject("report").getDouble("keyframeTolerancePixels"),1e-10);
        assertEquals(2,coarse.getJSONObject("report").getDouble("keyframeTolerancePixels"),0);
        assertTrue(coarse.getJSONObject("report").getDouble("maxBakedTransformApproximationPixels")<=2.00001);
        assertTrue(coarse.getJSONObject("report").getInt("outputTransformKeys")
                <=strict.getJSONObject("report").getInt("outputTransformKeys"));
        assertEquals(sourceFrozen,SketchDocument.canonical(source));
        assertEquals(normalFrozen,SketchDocument.canonical(normal));
        SketchDocument.validate(coarse.getJSONObject("document"));
    }
    @Test public void unauthorizedLossyBudgetOrNegativeToleranceIsNotAccepted()throws Exception{
        JSONObject s=source(),p=motion(s);
        for(double invalid:new double[]{.1,2.01,9}){
            JSONObject candidate=SketchDocument.copy(p).put("keyframeTolerancePixels",invalid);
            try{SketchAnimationCompiler.compile(s,candidate,"invalid_keys");fail("Accepted loss >2px or illegal tolerance");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("keyframeTolerancePixels"));}
        }
    }
}
