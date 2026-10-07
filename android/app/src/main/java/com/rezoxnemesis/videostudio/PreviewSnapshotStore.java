package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Durable immutable preview/checkpoint index for the editor. */
public final class PreviewSnapshotStore {
    private static final String PREFS = "videostudio_preview_snapshots_v1";
    private static final String KEY = "snapshots";
    private static final int MAX = 80;

    public static final class Snapshot {
        public final String id;
        public final String projectId;
        public final long projectRevision;
        public final long mediaRevision;
        public final String uri;
        public final String qualityTier;
        public final String sourceType;
        public final String jobId;
        public final long createdAt;

        public Snapshot(String id,
                        String projectId,
                        long projectRevision,
                        long mediaRevision,
                        String uri,
                        String qualityTier,
                        String sourceType,
                        String jobId,
                        long createdAt) {
            this.id = clean(id);
            this.projectId = clean(projectId);
            this.projectRevision = Math.max(0L, projectRevision);
            this.mediaRevision = Math.max(0L, mediaRevision);
            this.uri = clean(uri);
            this.qualityTier = clean(qualityTier);
            this.sourceType = clean(sourceType);
            this.jobId = clean(jobId);
            this.createdAt = Math.max(0L, createdAt);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("projectId", projectId);
                o.put("projectRevision", projectRevision);
                o.put("mediaRevision", mediaRevision);
                o.put("uri", uri);
                o.put("qualityTier", qualityTier);
                o.put("sourceType", sourceType);
                o.put("jobId", jobId);
                o.put("createdAt", createdAt);
            } catch (Exception ignored) {}
            return o;
        }

        static Snapshot fromJson(JSONObject o) {
            return new Snapshot(
                    o.optString("id", ""),
                    o.optString("projectId", ""),
                    o.optLong("projectRevision", 0L),
                    o.optLong("mediaRevision", 0L),
                    o.optString("uri", ""),
                    o.optString("qualityTier", ""),
                    o.optString("sourceType", ""),
                    o.optString("jobId", ""),
                    o.optLong("createdAt", 0L)
            );
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private final SharedPreferences prefs;

    public PreviewSnapshotStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void publish(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Preview snapshot is required");
        if (snapshot.id.isEmpty()) throw new IllegalArgumentException("Preview snapshot ID is required");
        if (snapshot.projectId.isEmpty()) throw new IllegalArgumentException("Preview project ID is required");
        if (snapshot.uri.isEmpty()) throw new IllegalArgumentException("Preview URI is required");
        if (snapshot.uri.toLowerCase().endsWith(".partial")) {
            throw new IllegalArgumentException("Partial preview files cannot be published");
        }

        ArrayList<Snapshot> next = new ArrayList<>();
        next.add(snapshot);
        for (Snapshot existing : all()) {
            if (snapshot.id.equals(existing.id)) continue;
            if (sameIdentity(snapshot, existing)) continue;
            next.add(existing);
            if (next.size() >= MAX) break;
        }
        persist(next);
    }

    public synchronized Snapshot get(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Snapshot snapshot : all()) if (id.equals(snapshot.id)) return snapshot;
        return null;
    }

    public synchronized Snapshot latest(String projectId) {
        if (projectId == null || projectId.isEmpty()) return null;
        List<Snapshot> items = list(projectId);
        return items.isEmpty() ? null : items.get(0);
    }

    public synchronized List<Snapshot> list(String projectId) {
        ArrayList<Snapshot> out = new ArrayList<>();
        for (Snapshot snapshot : all()) {
            if (projectId == null || projectId.isEmpty() || projectId.equals(snapshot.projectId)) {
                out.add(snapshot);
            }
        }
        out.sort(Comparator.comparingLong((Snapshot s) -> s.createdAt).reversed());
        return out;
    }

    public synchronized void clearAll() {
        prefs.edit().remove(KEY).commit();
    }

    public static boolean shouldAutoSwitch(boolean autoSwitchEnabled, boolean currentlyPlaying) {
        return autoSwitchEnabled && !currentlyPlaying;
    }

    private List<Snapshot> all() {
        ArrayList<Snapshot> out = new ArrayList<>();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) out.add(Snapshot.fromJson(item));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void persist(List<Snapshot> snapshots) {
        JSONArray array = new JSONArray();
        for (Snapshot snapshot : snapshots) array.put(snapshot.toJson());
        prefs.edit().putString(KEY, array.toString()).commit();
    }

    private static boolean sameIdentity(Snapshot a, Snapshot b) {
        return a.projectId.equals(b.projectId)
                && a.uri.equals(b.uri)
                && a.projectRevision == b.projectRevision
                && a.mediaRevision == b.mediaRevision;
    }
}
