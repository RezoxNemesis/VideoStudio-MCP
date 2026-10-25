package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Closed routing must not grant arbitrary filesystem or Gallery access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioTrackMatteMcpTest {
    @Test public void threeNativeActionsHaveExactlyTheirBackgroundAliases()throws Exception{
        for(String action:new String[]{"track_matte_capabilities","track_matte_plan","track_matte_edit"}){
            assertTrue(NativeAgentMailboxActions.supports(action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread(action));
        }
        assertFalse(NativeAgentMailboxActions.supports("studio_gallery_list"));
        assertEquals(101,NativeWorkbenchBridge.capabilities()
                .getJSONArray("canonicalActions").length());
    }
    @Test public void schemaDeclaresExplicitHonestLimitations()throws Exception{
        JSONObject obj=NativeWorkbenchBridge.capabilities().getJSONObject("trackMattes");
        assertEquals("shape",obj.getString("matteType"));
        assertFalse(obj.getBoolean("dynamicMattes"));
        assertFalse(obj.getBoolean("videoMattes"));
        assertFalse(obj.getBoolean("galleryAccess"));
        assertTrue(obj.getBoolean("sourceUnchanged"));
    }
}
