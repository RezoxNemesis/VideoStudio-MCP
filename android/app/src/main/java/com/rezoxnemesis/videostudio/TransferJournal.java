package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Durable metadata-only journal for resumable media transfers. */
public final class TransferJournal {
    private static final String PREFS = "videostudio_transfer_journal_v1";
    private static final String KEY = "entries";

    public static final class Entry {
        public final String id;
        public final String sourceUrl;
        public final String partialPath;
        public final long expectedBytes;
        public final long completedBytes;
        public final String etag;
        public final String lastModified;
        public final String sha256;
        public final String state;

        public Entry(String id,
                     String sourceUrl,
                     String partialPath,
                     long expectedBytes,
                     long completedBytes,
                     String etag,
                     String lastModified,
                     String sha256,
                     String state) {
            this.id = id == null ? "" : id;
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
            this.partialPath = partialPath == null ? "" : partialPath;
            this.expectedBytes = expectedBytes;
            this.completedBytes = Math.max(0L, completedBytes);
            this.etag = etag == null ? "" : etag;
            this.lastModified = lastModified == null ? "" : lastModified;
            this.sha256 = sha256 == null ? "" : sha256;
            this.state = state == null ? "" : state;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("sourceUrl", sourceUrl);
                o.put("partialPath", partialPath);
                o.put("expectedBytes", expectedBytes);
                o.put("completedBytes", completedBytes);
                o.put("etag", etag);
                o.put("lastModified", lastModified);
                o.put("sha256", sha256);
                o.put("state", state);
            } catch (Exception ignored) {}
            return o;
        }

        static Entry fromJson(JSONObject o) {
            return new Entry(
                    o.optString("id", ""),
                    o.optString("sourceUrl", ""),
                    o.optString("partialPath", ""),
                    o.optLong("expectedBytes", -1L),
                    o.optLong("completedBytes", 0L),
                    o.optString("etag", ""),
                    o.optString("lastModified", ""),
                    o.optString("sha256", ""),
                    o.optString("state", "")
            );
        }
    }

    private final SharedPreferences prefs;

    public TransferJournal(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(Entry entry) {
        JSONArray next = new JSONArray();
        JSONArray current = readAll();
        boolean replaced = false;
        for (int i = 0; i < current.length(); i++) {
            JSONObject raw = current.optJSONObject(i);
            if (raw == null) continue;
            if (entry.id.equals(raw.optString("id"))) {
                next.put(entry.toJson());
                replaced = true;
            } else {
                next.put(raw);
            }
        }
        if (!replaced) next.put(entry.toJson());
        prefs.edit().putString(KEY, next.toString()).commit();
    }

    public synchronized Entry get(String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray current = readAll();
        for (int i = 0; i < current.length(); i++) {
            JSONObject raw = current.optJSONObject(i);
            if (raw != null && id.equals(raw.optString("id"))) return Entry.fromJson(raw);
        }
        return null;
    }

    public synchronized void remove(String id) {
        JSONArray next = new JSONArray();
        JSONArray current = readAll();
        for (int i = 0; i < current.length(); i++) {
            JSONObject raw = current.optJSONObject(i);
            if (raw != null && !String.valueOf(id).equals(raw.optString("id"))) next.put(raw);
        }
        prefs.edit().putString(KEY, next.toString()).commit();
    }

    public synchronized void clearAll() {
        prefs.edit().remove(KEY).commit();
    }

    private JSONArray readAll() {
        try { return new JSONArray(prefs.getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
}
