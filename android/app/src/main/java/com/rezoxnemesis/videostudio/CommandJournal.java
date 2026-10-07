package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Small durable idempotency journal for MCP v3 commands.
 *
 * The network queue may lease a command again after a process/network failure.
 * VideoStudio records terminal command results locally so a repeated lease can
 * be acknowledged with the same result instead of executing the edit twice.
 */
public final class CommandJournal {
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY = "mcp_v3_command_journal";
    private static final int MAX = 160;

    private final SharedPreferences prefs;

    public CommandJournal(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized JSONObject terminal(String commandId) {
        if (commandId == null || commandId.isEmpty()) return null;
        JSONArray entries = read();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null || !commandId.equals(item.optString("id"))) continue;
            String status = item.optString("status");
            if (!"completed".equals(status) && !"failed".equals(status) && !"denied".equals(status)) return null;
            JSONObject out = new JSONObject();
            try {
                out.put("status", status);
                JSONObject result = item.optJSONObject("result");
                out.put("result", result == null ? new JSONObject() : result);
            } catch (Exception ignored) {}
            return out;
        }
        return null;
    }

    public synchronized void begin(JSONObject command) {
        if (command == null) return;
        String id = command.optString("id");
        if (id.isEmpty()) return;
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("seq", command.optLong("seq", 0));
            entry.put("action", command.optString("action", ""));
            entry.put("status", "running");
            entry.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(id, entry);
    }

    public synchronized void finish(JSONObject command, JSONObject result, String status) {
        if (command == null) return;
        String id = command.optString("id");
        if (id.isEmpty()) return;
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("seq", command.optLong("seq", 0));
            entry.put("action", command.optString("action", ""));
            entry.put("status", status == null ? "completed" : status);
            entry.put("result", result == null ? new JSONObject() : result);
            entry.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(id, entry);
    }

    public synchronized JSONArray recent(int limit) {
        JSONArray entries = read();
        JSONArray out = new JSONArray();
        int count = Math.max(1, Math.min(MAX, limit));
        for (int i = 0; i < entries.length() && out.length() < count; i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null) out.put(item);
        }
        return out;
    }

    private void upsert(String id, JSONObject value) {
        JSONArray old = read();
        JSONArray next = new JSONArray();
        next.put(value);
        for (int i = 0; i < old.length() && next.length() < MAX; i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null || id.equals(item.optString("id"))) continue;
            next.put(item);
        }
        prefs.edit().putString(KEY, next.toString()).apply();
    }

    private JSONArray read() {
        try { return new JSONArray(prefs.getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
}
