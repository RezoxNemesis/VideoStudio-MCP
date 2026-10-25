package com.rezoxnemesis.videostudio;

/** Native commands that must not block Android's main/UI event loop. */
public final class NativeCommandExecutionPolicy {
    private NativeCommandExecutionPolicy(){}

    public static boolean requiresBackgroundThread(String action) {
        if(action==null)return false;
        action=NativeWorkbenchBridge.canonicalAction(action);
        switch(action){
            case "get_state": case "ping": case "get_native_result_page": case "get_native_delivery_status": case "compact_native_delivery":
            case "import_mailbox_media": case "import_inline_base64": case "append_frame_chunk": case "finish_frame_transfer":
            case "import_chat_file": case "import_attachment": case "import_url":
            case "project_asset_info": case "project_import_asset":
            case "precision_edit":
            case "kinetic_benchmark_capabilities": case "kinetic_benchmark_plan": case "kinetic_benchmark_apply":
            case "motion_group_capabilities": case "motion_group_plan": case "motion_group_edit":
            case "apply_tool": case "set_studio_layers":
            case "sketch_create": case "sketch_list": case "sketch_get": case "sketch_inspect": case "sketch_review": case "sketch_quality_audit": case "sketch_edit":
            case "sketch_agent_workspace_capabilities": case "sketch_agent_workspace_plan": case "sketch_agent_workspace_apply":
            case "sketch_agent_workspace_status": case "sketch_agent_workspace_cancel":
            case "sketch_animation_save": case "sketch_animation_get": case "sketch_animation_list": case "sketch_animation_bake": case "sketch_animation_place_in_book":
            case "sketch_correction_start": case "sketch_correction_get": case "sketch_correction_note": case "sketch_correction_propose": case "sketch_correction_preview": case "sketch_correction_apply": case "sketch_correction_options": case "sketch_correction_list": case "sketch_correction_close":
            case "sketch_story_create": case "sketch_story_preview": case "sketch_story_audio_create": case "sketch_story_example":
            case "sketch_paint_journal": case "sketch_sample_color": case "sketch_archive": case "sketch_restore_archive":
            case "sketch_book_create": case "sketch_book_edit": case "sketch_book_list": case "sketch_book_get": case "sketch_book_open_page": case "sketch_book_timeline": case "sketch_book_render":
            case "sketch_live_begin": case "sketch_live_events": case "sketch_live_end": case "sketch_capabilities": case "sketch_component_save": case "sketch_component_list": case "sketch_component_apply":
            case "sketch_undo": case "sketch_redo": case "sketch_import_reference": case "sketch_import_reference_layers": case "sketch_reference_layers_preview": case "sketch_layered_drawing_preview": case "sketch_preview": case "sketch_render":
            case "sketch_import_frame_manifest": case "sketch_import_drawing_manifest": case "sketch_import_drawing_asset": case "sketch_import_animation":
            case "local_drawing_models": case "local_drawing_install": case "local_drawing_generate": case "local_drawing_get":
            case "open_pipeline_configure": case "open_pipeline_capabilities": case "open_pipeline_list":
            case "open_pipeline_export_request": case "open_pipeline_submit": case "open_pipeline_status": case "open_pipeline_cancel":
            case "open_pipeline_download_result": case "open_pipeline_import_result": case "open_pipeline_archive_artifact": case "open_pipeline_restore_artifact":
            case "cinematic_3d_install_builtin":
            case "cinematic_3d_import_bundle":
            case "cinematic_3d_save_program":
            case "cinematic_3d_edit_program":
            case "cinematic_3d_get_program":
            case "cinematic_3d_list_programs":
            case "cinematic_3d_plan":
            case "cinematic_3d_render":
            case "cinematic_3d_export_request":
            case "inspect_source_artwork":
            case "preview_source_rig":
            case "save_source_rig_profiles":
            case "save_source_rig_profile":
            case "list_source_rig_profiles":
            case "source_rig_status":
            case "storyboard_series_plan":
            case "storyboard_series_audit":
            case "storyboard_artwork_preflight":
            case "animate_storyboard_series":
            case "animate_storyboard_artwork":
            case "render_rigged_2d":
            case "save_storyboard_program":
            case "get_storyboard_program":
                return true;
            default:
                return false;
        }
    }
}
