package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** All agent workspace actions must be recognized by the stable private MCP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAgentWorkspaceMcpAdmissionTest {
    @Test public void closedAndroidMailboxAndStudioAliasesAdmitOnlyOwnedNativeActions(){
        for(String action:new String[]{
                "sketch_agent_workspace_capabilities",
                "sketch_agent_workspace_plan",
                "sketch_agent_workspace_apply",
                "sketch_agent_workspace_status",
                "sketch_agent_workspace_cancel"}){
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
        }
        assertFalse(NativeAgentMailboxActions.supports("gallery_browse"));
        assertFalse(NativeAgentMailboxActions.supports("sketch_agent_inject_unverified_touch"));
    }
    @Test public void nativeCapabilityDistinguishesLiveRealStrokesFromVisibleUiSelections()throws Exception{
        JSONObject capability=SketchAgentWorkspace.capabilities();
        assertEquals("4.10.32",capability.getString("minimumNativeVersion"));
        assertEquals("sketch_edit",capability.getString("drawAction"));
        assertTrue(capability.getBoolean("liveDrawingUsesRealStrokePrefixes"));
        assertTrue(capability.getBoolean("stateChangesAcknowledgedByVisibleApp"));
        assertFalse(capability.getBoolean("physicalTouchInjection"));
        assertFalse(capability.getBoolean("galleryAccess"));
        assertEquals(24,capability.getInt("maximumUiSteps"));
        assertNotNull(SketchCapabilities.get().getJSONObject("agentWorkspace"));
        assertTrue(NativeWorkbenchBridge.capabilities().getJSONArray("canonicalActions")
                .toString().contains("sketch_agent_workspace_apply"));
    }
}
