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
    @Test public void authoringRasterAndNetworkOperationsNeverBlockThePhoneUi(){
        for(String action:new String[]{"precision_edit","apply_tool","set_studio_layers","sketch_create","sketch_get","sketch_edit","sketch_preview","sketch_render",
                "open_pipeline_configure","open_pipeline_capabilities","open_pipeline_export_request","open_pipeline_submit","open_pipeline_status","open_pipeline_cancel","open_pipeline_download_result","open_pipeline_import_result","open_pipeline_archive_artifact","open_pipeline_restore_artifact"})
            assertTrue(action,NativeCommandExecutionPolicy.requiresBackgroundThread(action));
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
