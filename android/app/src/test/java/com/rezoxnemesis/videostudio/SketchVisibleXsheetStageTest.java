package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Human-visible Xsheet agent navigation: no fake pencil or hidden document writes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchVisibleXsheetStageTest {
    private static JSONObject source()throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId","stage_owned_xsheet")
                .put("width",256).put("height",256).put("frameCount",48));
    }
    private static JSONObject steps(JSONObject doc,JSONArray script)throws Exception {
        return new JSONObject().put("sketchId",doc.getString("id"))
                .put("operationId","xsheet-stage-0001")
                .put("expectedRevision",doc.getLong("revision"))
                .put("steps",script);
    }
    @Test public void xsheetIsARealForegroundStepWithExactVisibleLayerAndFrame()throws Exception {
        JSONObject doc=source();
        String frozen=SketchDocument.canonical(doc);
        JSONObject prepared=SketchAgentStageCommands.normalize(doc==null?null:
                steps(doc,new JSONArray()
                    .put(new JSONObject().put("action","tool").put("tool","select"))
                    .put(new JSONObject().put("action","xsheet")
                            .put("frameIndex",18).put("layerId","layer_1"))),doc);
        assertEquals(2,prepared.getJSONArray("steps").length());
        assertEquals("xsheet",prepared.getJSONArray("steps")
                .getJSONObject(1).getString("action"));
        assertEquals(18,prepared.getJSONArray("steps")
                .getJSONObject(1).getInt("frameIndex"));
        assertEquals(frozen,SketchDocument.canonical(doc));
        assertTrue(SketchAgentStageCommands.capabilities()
                .getJSONArray("availableSteps").toString().contains("xsheet"));
        assertEquals("set_exposure_cel",SketchAgentStageCommands.capabilities()
                .getString("xsheetHoldEditOperation"));
    }
    @Test public void stageRefusesForeignLevelsInvalidFramesAndAnyNextActionAfterXsheet()throws Exception {
        JSONObject doc=source();
        for(JSONObject action:new JSONObject[]{
            new JSONObject().put("action","xsheet").put("frameIndex",49).put("layerId","layer_1"),
            new JSONObject().put("action","xsheet").put("frameIndex",0).put("layerId","foreign_level"),
            new JSONObject().put("action","xsheet").put("frameIndex",0).put("layerId","layer_1")
                .put("videoUri","file:///phone/gallery")
        }){
            assertThrows(IllegalArgumentException.class,()->
                    SketchAgentStageCommands.normalize(steps(doc,new JSONArray().put(action)),doc));
        }
        assertThrows(IllegalArgumentException.class,()->
                SketchAgentStageCommands.normalize(steps(doc,new JSONArray()
                    .put(new JSONObject().put("action","xsheet").put("frameIndex",0)
                            .put("layerId","layer_1"))
                    .put(new JSONObject().put("action","frame").put("frameIndex",1))),doc));
        doc.getJSONArray("layers").getJSONObject(0).put("visible",false);
        assertThrows(IllegalArgumentException.class,()->
                SketchAgentStageCommands.normalize(steps(doc,new JSONArray()
                    .put(new JSONObject().put("action","xsheet").put("frameIndex",0)
                            .put("layerId","layer_1"))),doc));
    }
    @Test public void authenticatedMailboxAllowsOnlyNativeXsheetReadAndNoGalleryEscape() throws Exception {
        assertTrue(NativeAgentMailboxActions.supports("sketch_xsheet"));
        assertTrue(NativeAgentMailboxActions.supports("studio_sketch_xsheet"));
        assertEquals("sketch_xsheet",NativeWorkbenchBridge.canonicalAction("studio_sketch_xsheet"));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_sketch_xsheet"));
        assertFalse(NativeAgentMailboxActions.supports("studio_gallery_list"));
        assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_gallery_list"));
    }
}
