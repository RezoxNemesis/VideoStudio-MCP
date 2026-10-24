package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** MCP admission and alias consistency for the real native Kinetic Director. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class KineticDirectorMcpAdmissionTest {
    @Test public void canonicalAndPrefixedActionsEnterTheExistingNativeMailboxes() throws Exception {
        for(String action:new String[]{"kinetic_benchmark_capabilities",
                "kinetic_benchmark_plan","kinetic_benchmark_apply"}) {
            assertTrue("Mailbox must accept native Kinetic Director action",
                    NativeAgentMailboxActions.supports(action));
            assertTrue("The new action must not block the UI thread",
                    NativeCommandExecutionPolicy.requiresBackgroundThread(action));
            assertEquals(action,NativeWorkbenchBridge.canonicalAction("studio_"+action));
            assertTrue(NativeAgentMailboxActions.supports("studio_"+action));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_"+action));
        }
    }
    @Test public void capabilityReportsAreHonestAndPreserveTheGalleryWall() throws Exception {
        JSONObject all=NativeWorkbenchBridge.capabilities();
        JSONObject kinetic=all.getJSONObject("kineticDirector");
        assertFalse(kinetic.getBoolean("galleryAccess"));
        assertFalse(kinetic.getBoolean("actualBeatDetection"));
        assertFalse(kinetic.getBoolean("autoExport"));
        assertTrue(kinetic.getBoolean("createsIndependentEditableProject"));
        assertEquals("4.10.26",kinetic.getString("minimumNativeVersion"));
        assertTrue(all.getJSONArray("canonicalActions").toString()
                .contains("kinetic_benchmark_apply"));
    }
}
