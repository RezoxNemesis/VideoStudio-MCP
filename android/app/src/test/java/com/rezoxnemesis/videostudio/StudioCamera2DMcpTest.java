package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Closed native action registry, no generic file/gallery access or hidden host commands. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StudioCamera2DMcpTest {
    @Test public void allCameraActionsHaveExplicitNativeMailboxAndBackgroundPolicies()throws Exception{
        for(String action:new String[]{"camera2d_capabilities","camera2d_plan","camera2d_apply"}){
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        }
        assertFalse(NativeAgentMailboxActions.supports("camera2d_gallery_import"));
    }
    @Test public void capabilitiesDoNotAdvertisePhysicalThreeDCamera()throws Exception{
        JSONObject capabilities=NativeWorkbenchBridge.capabilities()
                .getJSONObject("compositionCamera2D");
        assertEquals("4.10.33",capabilities.getString("minimumNativeVersion"));
        assertEquals("camera2d_apply",capabilities.getString("applyAction"));
        assertTrue(capabilities.getBoolean("wholeFrame2D"));
        assertFalse(capabilities.getBoolean("true3D"));
        assertFalse(capabilities.getBoolean("cameraOpacity"));
        assertFalse(capabilities.getBoolean("mutatesOriginalMediaFiles"));
        assertFalse(capabilities.getBoolean("galleryAccess"));
        assertFalse(capabilities.getBoolean("previewVerifiedOnPhone"));
        assertEquals(1,StudioEditingCapabilities.capabilities()
                .getJSONObject("compositionCamera2D").getInt("maxCameraTracks"));
    }
}
