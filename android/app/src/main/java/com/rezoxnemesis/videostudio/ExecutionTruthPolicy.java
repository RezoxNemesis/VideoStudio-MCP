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

    public static JSONObject presentDeferredJob(String defaultAction, JSONObject job) {
        JSONObject out = new JSONObject();
        String fallbackAction = defaultAction == null || defaultAction.trim().isEmpty()
                ? "Native work" : defaultAction.trim();
        String state = job == null ? "" : job.optString("state", "").trim().toLowerCase();
        String stage = job == null ? "" : job.optString("stage", "").trim();
        String detail = job == null ? "" : job.optString("detail", "").trim();
        int progress = job == null ? 0 : Math.max(0, Math.min(100, job.optInt("progress", 0)));

        String action = fallbackAction;
        String status = "running";

        switch (state) {
            case "waiting_thermal":
                action = "Waiting for phone to cool";
                status = "queued";
                break;
            case "waiting_memory":
                action = "Waiting for memory";
                status = "queued";
                break;
            case "waiting_network":
                action = "Waiting for network";
                status = "queued";
                break;
            case "waiting_storage":
                action = "Waiting for storage";
                status = "queued";
                break;
            case "waiting_native":
                action = "Waiting for native executor";
                status = "queued";
                break;
            case "checkpointed":
                action = "Native work checkpointed";
                status = "queued";
                break;
            case "queued":
            case "preparing":
                if (!stage.isEmpty() && !"queued".equalsIgnoreCase(stage)) action = stage;
                status = "queued";
                break;
            case "completed":
                if (!stage.isEmpty() && !"queued".equalsIgnoreCase(stage)) action = stage;
                status = "success";
                progress = 100;
                break;
            case "failed":
            case "cancelled":
                if (!stage.isEmpty() && !"queued".equalsIgnoreCase(stage)) action = stage;
                status = "failed";
                break;
            default:
                if (!stage.isEmpty() && !"queued".equalsIgnoreCase(stage)) action = stage;
                status = "running";
                break;
        }

        if (detail.isEmpty()) {
            if ("waiting_thermal".equals(state)) detail = "Thermal governor paused heavy work; checkpoint preserved until the phone cools";
            else if ("waiting_memory".equals(state)) detail = "Memory governor paused heavy work; checkpoint preserved";
            else if ("waiting_network".equals(state)) detail = "Waiting for network before native work can continue";
            else if ("waiting_storage".equals(state)) detail = "Waiting for storage before native work can continue";
            else if ("waiting_native".equals(state)) detail = "Native executor is offline; queued work will resume automatically";
            else detail = state.isEmpty() ? "Native job state unavailable" : state.replace('_', ' ');
        }

        try {
            out.put("action", action);
            out.put("detail", detail);
            out.put("status", status);
            out.put("progress", progress);
            out.put("jobState", state);
        } catch (Exception ignored) {}
        return out;
    }

    public static boolean requiresValidatedMediaOutput(String action) {
        String value = action == null ? "" : action.trim().toLowerCase();
        return "prompt_video".equals(value)
                || "animate_images".equals(value)
                || "animate_pose_sequence".equals(value)
                || "animate_timeline".equals(value)
                || "export_project".equals(value)
                || "autonomous_edit".equals(value);
    }
}
