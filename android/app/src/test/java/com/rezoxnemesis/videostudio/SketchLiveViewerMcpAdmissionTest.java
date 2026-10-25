package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The private MCP cannot broaden the native viewer into shell/Gallery control. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SketchLiveViewerMcpAdmissionTest {
    @Test public void allThreeCommandsAreAdmittedByTheSameClosedNativeBackgroundPath()throws Exception {
        for(String action:new String[]{"sketch_live_view_capabilities",
                "sketch_live_view_control","sketch_live_view_status"}){
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction(action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
        }
        assertFalse(NativeAgentMailboxActions.supports("sketch_live_view_gallery"));
        assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_sketch_live_view_gallery"));
        assertFalse(NativeAgentMailboxActions.supports("studio_run_shell"));
    }

    @Test public void advertisedCommandsAreBoundedAndDoNotPromiseDrawingGeneration()throws Exception{
        JSONObject registry=NativeWorkbenchBridge.capabilities();
        JSONObject viewer=registry.getJSONObject("sketchLiveViewer");
        assertEquals("4.10.40",viewer.getString("minimumNativeVersion"));
        assertTrue(viewer.getBoolean("appliedOnlyByVisibleSketchStudio"));
        assertTrue(viewer.getBoolean("noSketchRevisionWrites"));
        assertTrue(viewer.getBoolean("noGalleryAccess"));
        assertTrue(viewer.getBoolean("notAFrameRenderer"));
        assertEquals(7,viewer.getJSONArray("speeds").length());
        JSONArray aliases=registry.getJSONArray("canonicalActions");
        assertEquals(115,aliases.length());
        assertTrue(aliases.toString().contains("sketch_live_view_control"));
        assertEquals("sketch_live_view_control",SketchCapabilities.get()
                .getJSONObject("nativeLiveViewer").getString("enqueueAction"));
    }
}
