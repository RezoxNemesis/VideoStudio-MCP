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
public final class SketchAnimationProductionPreflightTest {
    @Test public void reportsStaticSceneWithoutPretendingCreativeAcceptance()throws Exception {
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","preflight_source")
                .put("width",320).put("height",480).put("frameCount",1));
        JSONObject program=SketchAnimationUiModel.createProgram(source).put("frameCount",24);
        program.put("cameraLayerIds",new JSONArray());
        String before=SketchDocument.canonical(program);
        JSONObject preflight=SketchAnimationProductionPreflight.inspect(source,program);
        assertTrue(preflight.getBoolean("structuralCompilationPassed"));
        assertFalse(preflight.getBoolean("filmQualityCertified"));
        assertFalse(preflight.getBoolean("renderOrAudioInspected"));
        assertEquals(24,preflight.getInt("frameCount"));
        JSONArray diagnostics=preflight.getJSONArray("diagnostics");
        String joined=diagnostics.toString();
        assertTrue(joined.contains("Character motion"));assertTrue(joined.contains("Motion:"));
        assertTrue(joined.contains("Export check needed"));
        assertEquals(before,SketchDocument.canonical(program));
    }
    @Test public void tellsArtistWhenVoiceCuesHaveNoMouthDrawings()throws Exception{
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId","dialogue_preflight")
                .put("width",320).put("height",480).put("frameCount",1));
        JSONObject program=SketchAnimationUiModel.createProgram(source).put("frameCount",24);
        program.getJSONArray("dialogues").put(new JSONObject().put("start",1).put("end",18)
                .put("text","Speak.").put("layerId","layer_1")
                .put("bounds",new JSONObject().put("x",.1).put("y",.8).put("width",.8).put("height",.1)));
        JSONObject preflight=SketchAnimationProductionPreflight.inspect(source,program);
        assertTrue(preflight.getJSONArray("diagnostics").toString().contains("no mouth replacement"));
    }
}
