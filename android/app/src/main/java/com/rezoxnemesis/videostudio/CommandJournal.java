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
        // Legacy get_state snapshots contain the journal itself. Purge them on upgrade,
        // preventing exponential diagnostic growth and associated memory pressure.
        prefs.edit().putString(KEY, read().toString()).commit();
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

    /** A command ID is bound to its action and arguments, not merely its result. */
    public synchronized void validateReplay(JSONObject command){
        String candidate=requestFingerprint(command);
        JSONObject prior=existing(command.optString("id",""));if(prior==null)return;
        String saved=prior.optString("requestFingerprint","");
        if(saved.isEmpty()){
            JSONObject captured=prior.optJSONObject("command");
            if(captured==null)throw new IllegalArgumentException("Cannot verify an older command ID; use a new command ID");
            saved=requestFingerprint(captured);
        }
        if(!saved.equals(candidate))throw new IllegalArgumentException("Command ID conflicts with a different request");
    }

    private static String requestFingerprint(JSONObject command){
        try{
            if(command.has("parameters")&&!(command.opt("parameters") instanceof JSONObject))throw new IllegalArgumentException("Command parameters must be an object");
            JSONObject parameters=command.optJSONObject("parameters");
            JSONObject copy=parameters==null?new JSONObject():new JSONObject(parameters.toString());
            copy.remove("_origin");copy.remove("_mcpCommandId");
            if(copy.toString().length()>20*1024*1024)throw new IllegalArgumentException("Command arguments exceed the supported transfer envelope");
            return EditorEngine.fingerprint("command:"+command.optString("action",""),copy,0);
        }catch(org.json.JSONException invalid){throw new IllegalArgumentException("Invalid command arguments",invalid);}
    }

    public synchronized void begin(JSONObject command) {
        if (command == null || "get_state".equals(command.optString("action"))) return;
        String id = command.optString("id");
        if (id.isEmpty()) return;
        String binding=requestFingerprint(command);
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("seq", command.optLong("seq", 0));
            entry.put("action", command.optString("action", ""));
            entry.put("status", "running");
            entry.put("requestFingerprint",binding);
            entry.put("command", new JSONObject(command.toString()));
            entry.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(id, entry);
    }

    public synchronized void linkJob(JSONObject command,
                                     String jobId,
                                     String projectId,
                                     JSONObject queuedResult) {
        if (command == null) return;
        String id = command.optString("id", "");
        if (id.isEmpty() || jobId == null || jobId.isEmpty()) return;
        String binding=requestFingerprint(command);
        JSONObject entry = existing(id);
        if (entry == null) entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("seq", command.optLong("seq", entry.optLong("seq", 0)));
            entry.put("action", command.optString("action", entry.optString("action", "")));
            entry.put("status", "running");
            entry.put("jobId", jobId);
            entry.put("projectId", projectId == null ? "" : projectId);
            entry.put("command", new JSONObject(command.toString()));
            entry.put("requestFingerprint",binding);
            if (queuedResult != null) entry.put("queuedResult", new JSONObject(queuedResult.toString()));
            entry.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(id, entry);
    }

    public synchronized void relinkJob(String commandId,
                                       String jobId,
                                       String projectId,
                                       JSONObject queuedResult) {
        if (commandId == null || commandId.isEmpty() || jobId == null || jobId.isEmpty()) return;
        JSONObject entry = existing(commandId);
        if (entry == null) return;
        try {
            entry.put("status", "running");
            entry.put("jobId", jobId);
            entry.put("projectId", projectId == null ? "" : projectId);
            if (queuedResult != null) entry.put("queuedResult", new JSONObject(queuedResult.toString()));
            entry.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(commandId, entry);
    }

    public synchronized JSONObject inflight(String commandId) {
        if (commandId == null || commandId.isEmpty()) return null;
        JSONObject entry = existing(commandId);
        if (entry == null || !"running".equals(entry.optString("status"))) return null;
        try { return new JSONObject(entry.toString()); }
        catch (Exception ignored) { return entry; }
    }

    public synchronized JSONArray inflightEntries(int limit) {
        JSONArray entries = read();
        JSONArray out = new JSONArray();
        int count = Math.max(1, Math.min(MAX, limit));
        for (int i = 0; i < entries.length() && out.length() < count; i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null && "running".equals(item.optString("status"))
                    && !item.optString("jobId", "").isEmpty()) {
                out.put(item);
            }
        }
        return out;
    }

    public synchronized void finish(JSONObject command, JSONObject result, String status) {
        if (command == null || "get_state".equals(command.optString("action"))) return;
        String id = command.optString("id");
        if (id.isEmpty()) return;
        String binding=requestFingerprint(command);
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("seq", command.optLong("seq", 0));
            entry.put("action", command.optString("action", ""));
            entry.put("status", status == null ? "completed" : status);
            entry.put("requestFingerprint",binding);
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

    private JSONObject existing(String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray entries = read();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
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
        if(!prefs.edit().putString(KEY, next.toString()).commit())throw new IllegalStateException("Could not persist command journal");
    }

    private JSONArray read() {
        try {
            JSONArray stored = new JSONArray(prefs.getString(KEY, "[]"));
            JSONArray safe = new JSONArray();
            for (int i = 0; i < stored.length() && safe.length() < MAX; i++) {
                JSONObject entry = stored.optJSONObject(i);
                if (entry != null && !"get_state".equals(entry.optString("action"))) safe.put(entry);
            }
            return safe;
        }
        catch (Exception ignored) { return new JSONArray(); }
    }
}

