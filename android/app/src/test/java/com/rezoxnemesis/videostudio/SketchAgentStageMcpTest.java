package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** No generic remote Android input or Gallery escape: these actions are closed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAgentStageMcpTest {
    @Test public void stageRoutesRequireKnownAuthenticatedNativeMailboxActions()throws Exception{
        for(String action:new String[]{"sketch_stage_capabilities","sketch_stage_execute","sketch_stage_status"}){
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        }
        assertFalse(NativeAgentMailboxActions.supports("studio_gallery_list"));
        assertEquals("studio_gallery_list",NativeWorkbenchBridge.canonicalAction("studio_gallery_list"));
    }
    @Test public void capabilitiesExplicitlyDistinguishQueuedAndVisibleWork()throws Exception{
        JSONObject advertised=NativeWorkbenchBridge.capabilities().getJSONObject("sketchVisibleAgent");
        assertEquals("4.10.43",advertised.getString("minimumNativeVersion"));
        assertEquals("sketch_stage_execute",advertised.getString("enqueueAction"));
        assertEquals("sketch_stage_status",advertised.getString("statusAction"));
        assertEquals(16,advertised.getInt("maxStepsPerCommand"));
        assertTrue(advertised.getBoolean("persistedWithoutBackgroundUI"));
        assertTrue(advertised.getBoolean("navigationStepsSourceUnchanged"));
        assertTrue(advertised.getBoolean("sourceChangesOnlyAfterForegroundCommit"));
        assertFalse(advertised.getBoolean("originalDocumentUnchanged"));
        assertTrue(advertised.getJSONArray("sourceChangingGestureSteps").toString().contains("object_drag"));
        assertTrue(advertised.getJSONArray("sourceChangingGestureSteps").toString().contains("node_drag"));
        assertFalse(advertised.getBoolean("galleryAccess"));
        assertEquals("4.10.43",SketchCapabilities.get()
                .getJSONObject("visibleAgentDirector").getString("minimumNativeVersion"));
    }
}
