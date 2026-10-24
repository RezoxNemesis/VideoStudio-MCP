package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Closed native MCP alias admission with no Gallery or shell expansion. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StudioMotionGroupMcpTest {
    @Test public void bridgeAndMailboxAdmitOnlyExplicitLinkedMotionCommands() throws Exception {
        for(String action:new String[]{"motion_group_capabilities","motion_group_plan","motion_group_edit"}){
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        }
        assertEquals("studio_gallery_list",NativeWorkbenchBridge.canonicalAction("studio_gallery_list"));
        assertFalse(NativeAgentMailboxActions.supports("studio_gallery_list"));
        assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_gallery_list"));
    }

    @Test public void capabilitiesAreExplicitAndDoNotPretendToBeThreeDPrecomposition() throws Exception {
        JSONObject a=NativeWorkbenchBridge.capabilities().getJSONObject("motionGroups");
        assertEquals("4.10.30",a.getString("minimumNativeVersion"));
        assertEquals("motion_group_edit",a.getString("nativeAction"));
        assertEquals(8,a.getInt("maximumGroups"));
        assertEquals(1,a.getInt("maximumLevels"));
        assertFalse(a.getBoolean("true3D"));
        assertFalse(a.getBoolean("onDevicePreviewVerified"));
        assertFalse(a.getBoolean("galleryAccess"));
        assertEquals("4.10.30",StudioEditingCapabilities.capabilities()
                .getJSONObject("motionParentGroups").getString("minimumNativeVersion"));
    }
}
