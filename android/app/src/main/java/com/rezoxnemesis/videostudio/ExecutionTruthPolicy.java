package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/**
 * Shared truth rules for autonomous execution UI and command lifecycle.
 * Transport health is not creative work, and queued background work is not
 * complete until the native job reaches a terminal state.
 */
public final class ExecutionTruthPolicy {
    public static final String JOB_RECOVERY_PREF_KEY = "job_recovery_snapshot";
    public static final String LIVE_JOB_PREF_KEY = "job_live_snapshot";

    private ExecutionTruthPolicy() {}

    public static boolean isTransportActivity(String source, String action) {
        String src = source == null ? "" : source.trim().toLowerCase();
        String value = action == null ? "" : action.trim().toLowerCase();
        if (!"system".equals(src) && !"transport".equals(src)) return false;
        return value.equals("mcp connected")
                || value.equals("mcp reconnecting")
                || value.equals("videostudio control starting")
                || value.equals("videostudio control stopped")
                || value.equals("native agent starting")
                || value.equals("native agent start failed");
    }

    public static boolean shouldSurfaceAsWork(String source, String action) {
        if (isTransportActivity(source, action)) return false;
        String value = action == null ? "" : action.trim().toLowerCase();
        return !value.equals("running videostudio v3 self-test")
                && !value.equals("checking stable mcp connection")
                && !value.equals("reading videostudio state")
                && !value.equals("reading native job status")
                && !value.equals("reading capability providers")
                && !value.equals("reading device compute profile")
                && !value.equals("reading model packs")
                && !value.equals("reading cloud workspace");
    }

    public static boolean shouldAdoptStoreActive(boolean currentProjectEmpty,
                                                 String currentProjectId,
                                                 String storeActiveProjectId) {
        String current = currentProjectId == null ? "" : currentProjectId;
        String active = storeActiveProjectId == null ? "" : storeActiveProjectId;
        return currentProjectEmpty
                && !active.isEmpty()
                && !active.equals(current);
    }

    public static boolean isDeferredResult(JSONObject result) {
        return result != null
                && result.optBoolean("queued", false)
                && !result.optString("jobId", "").isEmpty();
    }

    public static boolean requiresValidatedMediaOutput(String action) {
        String value = action == null ? "" : action.trim().toLowerCase();
        return "prompt_video".equals(value)
                || "animate_images".equals(value)
                || "export_project".equals(value)
                || "autonomous_edit".equals(value);
    }
}
