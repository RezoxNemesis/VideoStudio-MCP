package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exact closed private MCP admission for native Sketch human-stage controls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SketchAgentStageMcpTest {
    @Test public void commandsUseExistingClosedNativeBackgroundMailbox()throws Exception{
        for(String action:new String[]{"sketch_agent_stage","sketch_agent_stage_status"}){
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
        }
        assertFalse(NativeAgentMailboxActions.supports("studio_gallery_list"));
        assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_gallery_list"));
    }

    @Test public void stageCapabilityProofSeparatesUserVisibleCameraFromSavedStrokes()throws Exception{
        JSONObject app=NativeWorkbenchBridge.capabilities()
                .getJSONObject("sketchAgentStage");
        assertEquals("4.10.32",app.getString("minimumNativeVersion"));
        assertEquals("sketch_agent_stage",app.getString("stageAction"));
        assertEquals("sketch_agent_stage_status",app.getString("statusAction"));
        assertTrue(app.getBoolean("requiresVisibleSketchStudioFollowMode"));
        assertTrue(app.getBoolean("stageReceiptIsNotExecutionProof"));
        assertTrue(app.getBoolean("sourceAndGalleryUnchanged"));
        JSONObject sketch=SketchCapabilities.get().getJSONObject("agentWorkspace");
        assertEquals(16,sketch.getInt("maxSteps"));
        assertEquals("sketch_edit",sketch.getString("drawingOperationAction"));
    }
}
