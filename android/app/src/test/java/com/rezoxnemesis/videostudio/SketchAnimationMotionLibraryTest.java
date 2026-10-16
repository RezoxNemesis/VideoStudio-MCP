package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationMotionLibraryTest {
    private JSONObject source()throws Exception {
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","motion_source")
                .put("width",320).put("height",480).put("frameCount",1));
        JSONArray objects=source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");
        objects.put(SketchEditOps.completeObject(new JSONObject().put("id","left_hand_art").put("kind","line")
                .put("points",new JSONArray().put(new JSONArray().put(.24).put(.60).put(1)).put(new JSONArray().put(.30).put(.5).put(1)))));
        objects.put(SketchEditOps.completeObject(new JSONObject().put("id","right_hand_art").put("kind","line")
                .put("points",new JSONArray().put(new JSONArray().put(.76).put(.60).put(1)).put(new JSONArray().put(.70).put(.5).put(1)))));
        return source;
    }
    private JSONObject program(JSONObject source)throws Exception{
        JSONObject value=SketchAnimationUiModel.createProgram(source).put("frameCount",72);
        SketchAnimationUiModel.rig(value,"character_1").getJSONArray("bindings")
            .put(new JSONObject().put("objectId","left_hand_art").put("jointId","hand_left"))
            .put(new JSONObject().put("objectId","right_hand_art").put("jointId","hand_right"));
        return value;
    }
    @Test public void walkProducesEditableCounterSwingAndIndependentNativeMotion()throws Exception {
        JSONObject source=source(),before=program(source),out=SketchAnimationMotionLibrary.insert(before,"character_1",8,33,"walk");
        assertEquals(0,SketchAnimationUiModel.rig(before,"character_1").getJSONArray("poses").length());
        JSONArray keys=SketchAnimationUiModel.rig(out,"character_1").getJSONArray("poses");
        assertEquals(9,keys.length());assertEquals(8,keys.getJSONObject(0).getInt("frame"));
        assertEquals(40,keys.getJSONObject(8).getInt("frame"));
        assertTrue(keys.getJSONObject(8).getDouble("rootX")>keys.getJSONObject(0).getDouble("rootX"));
        JSONObject middle=keys.getJSONObject(1).getJSONObject("rotations");
        assertTrue(middle.getDouble("hip_left")*middle.getDouble("hip_right")<0);
        JSONObject baked=SketchAnimationCompiler.compile(source,out,"motion_output");
        assertTrue(baked.getJSONObject("report").getBoolean("independentArticulation"));
        assertTrue(baked.getJSONObject("report").getInt("animatedJointCount")>=2);
    }
    @Test public void idleAndStrikeKeysPreserveOtherAuthoredKeys()throws Exception {
        JSONObject source=source(),original=program(source);
        SketchAnimationUiModel.setRotation(original,"character_1",3,"head",12,"linear");
        JSONObject idle=SketchAnimationMotionLibrary.insert(original,"character_1",10,17,"idle");
        JSONObject strike=SketchAnimationMotionLibrary.insert(idle,"character_1",33,19,"strike");
        JSONArray keys=SketchAnimationUiModel.rig(strike,"character_1").getJSONArray("poses");
        assertEquals(13,keys.length());assertEquals(3,keys.getJSONObject(0).getInt("frame"));
        assertEquals(12,keys.getJSONObject(0).getJSONObject("rotations").getDouble("head"),0);
        SketchAnimationCompiler.compile(source,strike,"strike_output");
    }
    @Test public void invalidOrOverlappingPresetDoesNotMutateSource()throws Exception {
        JSONObject source=source(),original=program(source);
        JSONObject applied=SketchAnimationMotionLibrary.insert(original,"character_1",10,20,"run");
        String frozen=SketchDocument.canonical(applied);
        try{SketchAnimationMotionLibrary.insert(applied,"character_1",12,15,"strike");fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("overlaps"));}
        assertEquals(frozen,SketchDocument.canonical(applied));
        try{SketchAnimationMotionLibrary.insert(original,"character_1",60,20,"walk");fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("inside"));}
        assertEquals(0,SketchAnimationUiModel.rig(original,"character_1").getJSONArray("poses").length());
    }
}
