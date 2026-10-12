package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeCommandExecutionPolicyTest {
    @Test public void noSourceReadMayBlockUiLooper() {
        for(String operation:new String[]{
                "inspect_source_artwork","preview_source_rig",
                "source_rig_status","save_source_rig_profiles",
                "save_source_rig_profile","list_source_rig_profiles",
                "storyboard_series_plan","storyboard_series_audit",
                "animate_storyboard_series","animate_storyboard_artwork",
                "storyboard_artwork_preflight","render_rigged_2d",
                "save_storyboard_program","get_storyboard_program"
        })assertTrue(operation,NativeCommandExecutionPolicy.requiresBackgroundThread(operation));
    }
    @Test public void lightweightCommandsRemainAvailableDuringAnArtworkRead() {
        for(String operation:new String[]{"get_state","ping","self_test",
                "connection_health","reconnect_mcp","cancel_job"})
            assertFalse(operation,NativeCommandExecutionPolicy.requiresBackgroundThread(operation));
    }
    @Test public void nullAndUnknownActionsAreNotAutomaticallyOffloaded() {
        assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread(null));
        assertFalse(NativeCommandExecutionPolicy.requiresBackgroundThread("unknown_action"));
    }
}
