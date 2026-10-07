package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Durable metadata store for immutable playable preview checkpoints.
 *
 * A new checkpoint never invalidates an older one, so the editor can keep
 * playing snapshot N while autonomous work prepares N+1.
 */
public final class PreviewSnapshotStore {
    private static final String PREFS = "videostudio_preview_snapshots_v1";
    private static final String KEY = "snapshots";
    private static final int MAX_SNAPSHOTS = 80;

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
            this.id = safe(id);
            this.projectId = safe(projectId);
            this.projectRevision = Math.max(0L, projectRevision);
            this.mediaRevision = Math.max(0L, mediaRevision);
            this.uri = safe(uri);
            this.qualityTier = safe(qualityTier);
            this.sourceType = safe(sourceType);
            this.jobId = safe(jobId);
            this.createdAt = Math.max(0L, createdAt);
        }

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try {
                out.put("id", id);
                out.put("projectId", projectId);
                out.put("projectRevision", projectRevision);
                out.put("mediaRevision", mediaRevision);
                out.put("uri", uri);
                out.put("qualityTier", qualityTier);
                out.put("sourceType", sourceType);
                out.put("jobId", jobId);
                out.put("createdAt", createdAt);
            } catch (Exception ignored) {}
            return out;
        }

        static Snapshot fromJson(JSONObject o) {
            if (o == null) return null;
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
    }

    private final SharedPreferences prefs;

    public PreviewSnapshotStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void publish(Snapshot snapshot) {
        validate(snapshot);
        List<Snapshot> current = all();
        ArrayList<Snapshot> next = new ArrayList<>();
        boolean replaced = false;
        for (Snapshot item : current) {
            if (snapshot.id.equals(item.id)) {
                next.add(snapshot);
                replaced = true;
            } else {
                next.add(item);
            }
        }
        if (!replaced) next.add(snapshot);
        next.sort(Comparator.comparingLong((Snapshot s) -> s.createdAt).reversed());
        if (next.size() > MAX_SNAPSHOTS) next = new ArrayList<>(next.subList(0, MAX_SNAPSHOTS));
        write(next);
    }

    public synchronized Snapshot get(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Snapshot item : all()) if (id.equals(item.id)) return item;
        return null;
    }

    public synchronized Snapshot latest(String projectId) {
        List<Snapshot> list = list(projectId);
        return list.isEmpty() ? null : list.get(0);
    }

    public synchronized List<Snapshot> list(String projectId) {
        ArrayList<Snapshot> out = new ArrayList<>();
        for (Snapshot item : all()) {
            if (projectId == null || projectId.isEmpty() || projectId.equals(item.projectId)) out.add(item);
        }
        out.sort(Comparator.comparingLong((Snapshot s) -> s.createdAt).reversed());
        return Collections.unmodifiableList(out);
    }

    public synchronized void clearAll() {
        prefs.edit().remove(KEY).commit();
    }

    /**
     * Auto-switch is opt-in and only safe while the user is not actively playing.
     */
    public static boolean shouldAutoSwitch(boolean autoSwitchEnabled, boolean playbackActive) {
        return autoSwitchEnabled && !playbackActive;
    }

    private List<Snapshot> all() {
        ArrayList<Snapshot> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                Snapshot item = Snapshot.fromJson(rows.optJSONObject(i));
                if (item != null && !item.id.isEmpty()) out.add(item);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void write(List<Snapshot> rows) {
        JSONArray out = new JSONArray();
        for (Snapshot item : rows) out.put(item.toJson());
        prefs.edit().putString(KEY, out.toString()).commit();
    }

    private static void validate(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Preview snapshot is required");
        if (snapshot.id.isEmpty()) throw new IllegalArgumentException("Preview snapshot ID is required");
        if (snapshot.projectId.isEmpty()) throw new IllegalArgumentException("Preview project ID is required");
        if (snapshot.uri.isEmpty()) throw new IllegalArgumentException("Preview URI is required");
        String lower = snapshot.uri.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".partial") || lower.contains(".partial?")) {
            throw new IllegalArgumentException("Partial preview output cannot be published");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
