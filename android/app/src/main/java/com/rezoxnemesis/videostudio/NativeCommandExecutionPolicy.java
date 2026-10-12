package com.rezoxnemesis.videostudio;

/** Native commands that must not block Android's main/UI event loop. */
public final class NativeCommandExecutionPolicy {
    private NativeCommandExecutionPolicy(){}

    public static boolean requiresBackgroundThread(String action) {
        if(action==null)return false;
        switch(action){
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
