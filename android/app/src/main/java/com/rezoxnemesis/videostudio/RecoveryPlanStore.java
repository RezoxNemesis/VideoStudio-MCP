package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Durable high-level work specifications for restart-safe VideoStudio jobs.
 *
 * JobManager persists runtime checkpoints. This store persists the action and
 * parameters required to reconstruct selected long-running jobs after Android
 * kills or restarts the process.
 */
public final class RecoveryPlanStore {
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY = "durable_recovery_plans_v1";
    private static final int MAX = 24;
    private static final long MAX_AGE_MS = 48L * 60L * 60L * 1000L;
    private static final int MAX_AUTO_ATTEMPTS = 4;

    private final SharedPreferences prefs;

    public RecoveryPlanStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized String begin(String action, JSONObject parameters, String projectId) {
        String id = UUID.randomUUID().toString();
        JSONObject plan = new JSONObject();
        try {
            plan.put("id", id);
            plan.put("action", clean(action, "unknown"));
            plan.put("projectId", clean(projectId, ""));
            plan.put("parameters", parameters == null ? new JSONObject() : scrubInternal(parameters));
            plan.put("state", "queued");
            plan.put("stage", "queued");
            plan.put("progress", 0);
            plan.put("detail", "Durable work plan created");
            plan.put("attempts", 0);
            plan.put("jobId", "");
            plan.put("outputUri", "");
            plan.put("outputName", "");
            plan.put("createdAt", System.currentTimeMillis());
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
        return id;
    }

    public synchronized JSONObject get(String id) {
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan != null && id != null && id.equals(plan.optString("id"))) {
                try { return new JSONObject(plan.toString()); }
                catch (Exception ignored) { return plan; }
            }
        }
        return null;
    }

    public synchronized void attachJob(String planId, String jobId) {
        JSONObject plan = get(planId);
        if (plan == null) return;
        try {
            plan.put("jobId", clean(jobId, ""));
            plan.put("state", "running");
            plan.put("attempts", plan.optInt("attempts", 0) + 1);
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void checkpointForJob(String jobId, String stage, int progress, String detail) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null) return;
        try {
            plan.put("state", progress >= 100 ? "completed" : "running");
            plan.put("stage", clean(stage, "working"));
            plan.put("progress", Math.max(0, Math.min(100, progress)));
            plan.put("detail", clean(detail, ""));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void markOutputForJob(String jobId, String uri, String name) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null) return;
        try {
            plan.put("outputUri", clean(uri, ""));
            plan.put("outputName", clean(name, ""));
            plan.put("stage", "output_published");
            plan.put("progress", Math.max(98, plan.optInt("progress", 0)));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void completeByJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null) return;
        try {
            plan.put("state", "completed");
            plan.put("stage", "completed");
            plan.put("progress", 100);
            plan.put("detail", "Completed");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void completePlan(String planId, String detail) {
        JSONObject plan = get(planId);
        if (plan == null) return;
        try {
            plan.put("state", "completed");
            plan.put("stage", "completed");
            plan.put("progress", 100);
            plan.put("detail", clean(detail, "Completed"));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void markResuming(String planId) {
        JSONObject plan = get(planId);
        if (plan == null) return;
        try {
            plan.put("state", "retrying");
            plan.put("stage", "restart_recovery");
            plan.put("detail", "Resuming durable work after process restart");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void failByJob(String jobId, String error, boolean recoverable) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null) return;
        try {
            plan.put("state", recoverable ? "waiting_retry" : "failed");
            plan.put("detail", clean(error, recoverable ? "Recoverable failure" : "Failed"));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void cancelByJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null) return;
        try {
            plan.put("state", "cancelled");
            plan.put("detail", "Cancelled");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized int cancelActive() {
        JSONArray arr = read();
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan == null) continue;
            String state = plan.optString("state");
            if ("queued".equals(state) || "running".equals(state) || "waiting_retry".equals(state)
                    || "interrupted".equals(state) || "waiting_thermal".equals(state)
                    || "waiting_memory".equals(state)) {
                try {
                    plan.put("state", "cancelled");
                    plan.put("detail", "Cancelled");
                    plan.put("updatedAt", System.currentTimeMillis());
                    count++;
                } catch (Exception ignored) {}
            }
        }
        write(arr);
        return count;
    }

    public synchronized JSONArray pendingForAutoResume() {
        JSONArray source = read();
        JSONArray out = new JSONArray();
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (int i = 0; i < source.length() && out.length() < 8; i++) {
            JSONObject plan = source.optJSONObject(i);
            if (plan == null) continue;
            if (plan.optLong("updatedAt", 0) < cutoff) continue;
            if (plan.optInt("attempts", 0) >= MAX_AUTO_ATTEMPTS) continue;
            String state = plan.optString("state");
            if ("queued".equals(state) || "running".equals(state) || "waiting_retry".equals(state)
                    || "interrupted".equals(state) || "waiting_thermal".equals(state)
                    || "waiting_memory".equals(state)) {
                try { out.put(new JSONObject(plan.toString())); }
                catch (Exception ignored) {}
            }
        }
        return out;
    }

    public synchronized JSONArray recent(int limit) {
        JSONArray source = read();
        JSONArray out = new JSONArray();
        int count = Math.max(1, Math.min(MAX, limit));
        for (int i = 0; i < source.length() && out.length() < count; i++) {
            JSONObject plan = source.optJSONObject(i);
            if (plan != null) out.put(plan);
        }
        return out;
    }

    private JSONObject findByJobId(String jobId) {
        if (jobId == null || jobId.isEmpty()) return null;
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan != null && jobId.equals(plan.optString("jobId"))) {
                try { return new JSONObject(plan.toString()); }
                catch (Exception ignored) { return plan; }
            }
        }
        return null;
    }

    private void upsert(JSONObject plan) {
        if (plan == null) return;
        JSONArray old = read();
        JSONArray next = new JSONArray();
        next.put(plan);
        String id = plan.optString("id");
        for (int i = 0; i < old.length() && next.length() < MAX; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null || id.equals(item.optString("id"))) continue;
            next.put(item);
        }
        write(next);
    }

    private JSONArray read() {
        try { return new JSONArray(prefs.getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void write(JSONArray array) {
        prefs.edit().putString(KEY, array == null ? "[]" : array.toString()).apply();
    }

    private static JSONObject scrubInternal(JSONObject input) {
        JSONObject out = new JSONObject();
        if (input == null) return out;
        JSONArray names = input.names();
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            if (key.startsWith("_")) continue;
            try { out.put(key, input.opt(key)); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private static String clean(String value, String fallback) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty()) out = fallback;
        if (out.length() > 1000) out = out.substring(0, 1000);
        return out;
    }
}
