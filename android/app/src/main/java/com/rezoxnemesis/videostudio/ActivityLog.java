package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

public final class ActivityLog {
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY = "chatgpt_activity_log";
    private static final int MAX = 220;

    private ActivityLog() {}

    public static synchronized void add(Context context,
                                        String source,
                                        String action,
                                        String detail,
                                        String status,
                                        Integer progress,
                                        String commandId,
                                        String projectId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray old = readArray(prefs);
        JSONArray next = new JSONArray();

        JSONObject entry = new JSONObject();
        try {
            entry.put("id", UUID.randomUUID().toString());
            entry.put("time", System.currentTimeMillis());
            entry.put("source", clean(source, "system"));
            entry.put("action", clean(action, "Activity"));
            entry.put("detail", clean(detail, ""));
            entry.put("status", clean(status, "info"));
            if (progress != null) entry.put("progress", Math.max(0, Math.min(100, progress)));
            if (commandId != null && !commandId.isEmpty()) entry.put("commandId", commandId);
            if (projectId != null && !projectId.isEmpty()) entry.put("projectId", projectId);
        } catch (Exception ignored) {}
        next.put(entry);

        for (int i = 0; i < old.length() && next.length() < MAX; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null) next.put(item);
        }
        prefs.edit().putString(KEY, next.toString()).apply();
    }

    public static synchronized void progress(Context context,
                                             String jobId,
                                             String action,
                                             String detail,
                                             int progress,
                                             String projectId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray old = readArray(prefs);
        JSONArray next = new JSONArray();
        boolean replaced = false;

        for (int i = 0; i < old.length() && next.length() < MAX; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) continue;
            if (!replaced && jobId != null && jobId.equals(item.optString("jobId"))) {
                try {
                    JSONObject updated = new JSONObject(item.toString());
                    int previous = updated.optInt("progress", -1);
                    if (progress >= 100 || previous < 0 || progress - previous >= 5 || !detail.equals(updated.optString("detail"))) {
                        updated.put("time", System.currentTimeMillis());
                        updated.put("action", clean(action, updated.optString("action", "Working")));
                        updated.put("detail", clean(detail, ""));
                        updated.put("status", progress >= 100 ? "success" : "running");
                        updated.put("progress", Math.max(0, Math.min(100, progress)));
                        if (projectId != null && !projectId.isEmpty()) updated.put("projectId", projectId);
                    }
                    next.put(updated);
                    replaced = true;
                    continue;
                } catch (Exception ignored) {}
            }
            next.put(item);
        }

        if (!replaced) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", UUID.randomUUID().toString());
                entry.put("jobId", jobId == null ? "" : jobId);
                entry.put("time", System.currentTimeMillis());
                entry.put("source", "chatgpt");
                entry.put("action", clean(action, "Working"));
                entry.put("detail", clean(detail, ""));
                entry.put("status", progress >= 100 ? "success" : "running");
                entry.put("progress", Math.max(0, Math.min(100, progress)));
                if (projectId != null && !projectId.isEmpty()) entry.put("projectId", projectId);
            } catch (Exception ignored) {}
            JSONArray merged = new JSONArray();
            merged.put(entry);
            for (int i = 0; i < next.length() && merged.length() < MAX; i++) merged.put(next.opt(i));
            next = merged;
        }

        prefs.edit().putString(KEY, next.toString()).apply();
    }

    public static synchronized JSONArray recent(Context context, int limit) {
        JSONArray old = readArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        JSONArray out = new JSONArray();
        int count = Math.max(1, Math.min(MAX, limit));
        for (int i = 0; i < old.length() && out.length() < count; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null) out.put(item);
        }
        return out;
    }

    /**
     * Returns only user/ChatGPT work. Connection heartbeats and service
     * housekeeping stay available in the full activity history but never drive
     * the "Autonomous work" cards.
     */
    public static synchronized JSONArray recentWork(Context context, int limit) {
        JSONArray old = readArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        JSONArray out = new JSONArray();
        int count = Math.max(1, Math.min(MAX, limit));
        for (int i = 0; i < old.length() && out.length() < count; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) continue;
            if (!ExecutionTruthPolicy.shouldSurfaceAsWork(
                    item.optString("source", ""),
                    item.optString("action", ""))) {
                continue;
            }
            out.put(item);
        }
        return out;
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, "[]").apply();
    }

    private static JSONArray readArray(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String v = value.trim();
        if (v.length() > 500) v = v.substring(0, 500);
        return v.isEmpty() ? fallback : v;
    }
}
