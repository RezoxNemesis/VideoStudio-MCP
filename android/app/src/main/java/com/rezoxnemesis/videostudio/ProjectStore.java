package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ProjectStore {
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_PROJECTS = "projects";
    private static final String KEY_ACTIVE = "active_project";

    public static final class Asset {
        public String id;
        public String uri;
        public String name;
        public String mime;
        public long durationMs;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("uri", uri);
                o.put("name", name);
                o.put("mime", mime);
                o.put("durationMs", durationMs);
            } catch (Exception ignored) {}
            return o;
        }

        static Asset fromJson(JSONObject o) {
            Asset a = new Asset();
            a.id = o.optString("id");
            a.uri = o.optString("uri");
            a.name = o.optString("name", "Media");
            a.mime = o.optString("mime", "application/octet-stream");
            a.durationMs = o.optLong("durationMs", 0);
            return a;
        }
    }

    public static final class Clip {
        public String id;
        public String assetId;
        public long inMs;
        public long outMs;
        public float speed = 1f;
        public float volume = 1f;
        public String transition = "none";
        public String title = "";
        public JSONObject effects = new JSONObject();

        public long outputDurationMs() {
            return Math.max(0, (long) ((outMs - inMs) / Math.max(.1f, speed)));
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("assetId", assetId);
                o.put("inMs", inMs);
                o.put("outMs", outMs);
                o.put("speed", speed);
                o.put("volume", volume);
                o.put("transition", transition);
                o.put("title", title);
                o.put("effects", effects == null ? new JSONObject() : effects);
            } catch (Exception ignored) {}
            return o;
        }

        static Clip fromJson(JSONObject o) {
            Clip c = new Clip();
            c.id = o.optString("id", UUID.randomUUID().toString());
            c.assetId = o.optString("assetId");
            c.inMs = o.optLong("inMs", 0);
            c.outMs = o.optLong("outMs", 0);
            c.speed = (float) o.optDouble("speed", 1);
            c.volume = (float) o.optDouble("volume", 1);
            c.transition = o.optString("transition", "none");
            c.title = o.optString("title", "");
            c.effects = o.optJSONObject("effects");
            if (c.effects == null) c.effects = new JSONObject();
            return c;
        }
    }

    public static final class Project {
        public String id;
        public String name;
        public long updatedAt;
        public String sourcePrompt = "";
        public String latestExportUri = "";
        public String latestExportName = "";
        public long latestExportAt = 0;
        public final ArrayList<Asset> assets = new ArrayList<>();
        public final ArrayList<Clip> clips = new ArrayList<>();

        public long outputDurationMs() {
            long total = 0;
            for (Clip c : clips) total += c.outputDurationMs();
            return total;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            JSONArray aa = new JSONArray();
            JSONArray cc = new JSONArray();
            for (Asset a : assets) aa.put(a.toJson());
            for (Clip c : clips) cc.put(c.toJson());
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("updatedAt", updatedAt);
                o.put("sourcePrompt", sourcePrompt);
                o.put("latestExportUri", latestExportUri);
                o.put("latestExportName", latestExportName);
                o.put("latestExportAt", latestExportAt);
                o.put("assets", aa);
                o.put("clips", cc);
            } catch (Exception ignored) {}
            return o;
        }

        static Project fromJson(JSONObject o) {
            Project p = new Project();
            p.id = o.optString("id", UUID.randomUUID().toString());
            p.name = o.optString("name", "Untitled Project");
            p.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
            p.sourcePrompt = o.optString("sourcePrompt", "");
            p.latestExportUri = o.optString("latestExportUri", "");
            p.latestExportName = o.optString("latestExportName", "");
            p.latestExportAt = o.optLong("latestExportAt", 0);
            JSONArray aa = o.optJSONArray("assets");
            if (aa != null) for (int i = 0; i < aa.length(); i++) {
                JSONObject item = aa.optJSONObject(i);
                if (item != null) p.assets.add(Asset.fromJson(item));
            }
            JSONArray cc = o.optJSONArray("clips");
            if (cc != null) for (int i = 0; i < cc.length(); i++) {
                JSONObject item = cc.optJSONObject(i);
                if (item != null) p.clips.add(Clip.fromJson(item));
            }
            return p;
        }

        public Asset asset(String id) {
            for (Asset a : assets) if (a.id.equals(id)) return a;
            return null;
        }
    }

    private final SharedPreferences prefs;
    private final ContentResolver resolver;

    public ProjectStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        resolver = context.getContentResolver();
    }

    public synchronized List<Project> list() {
        ArrayList<Project> out = new ArrayList<>();
        String raw = prefs.getString(KEY_PROJECTS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Project.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized Project get(String id) {
        for (Project p : list()) if (p.id.equals(id)) return p;
        return null;
    }

    public synchronized Project active() {
        String id = prefs.getString(KEY_ACTIVE, "");
        Project p = id.isEmpty() ? null : get(id);
        if (p == null) {
            List<Project> all = list();
            if (!all.isEmpty()) p = all.get(0);
        }
        return p;
    }

    public synchronized Project create(String name) {
        Project p = new Project();
        p.id = UUID.randomUUID().toString();
        p.name = name == null || name.trim().isEmpty() ? "Untitled Project" : name.trim();
        p.updatedAt = System.currentTimeMillis();
        ArrayList<Project> all = new ArrayList<>(list());
        all.add(0, p);
        write(all);
        setActive(p.id);
        return p;
    }

    public synchronized void setActive(String id) {
        prefs.edit().putString(KEY_ACTIVE, id == null ? "" : id).apply();
    }

    public synchronized void save(Project project) {
        project.updatedAt = System.currentTimeMillis();
        ArrayList<Project> all = new ArrayList<>(list());
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(project.id)) {
                all.set(i, project);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(0, project);
        write(all);
    }

    public synchronized void delete(String id) {
        ArrayList<Project> all = new ArrayList<>(list());
        all.removeIf(p -> p.id.equals(id));
        write(all);
        if (id != null && id.equals(prefs.getString(KEY_ACTIVE, ""))) {
            prefs.edit().putString(KEY_ACTIVE, all.isEmpty() ? "" : all.get(0).id).apply();
        }
    }

    public Asset importUri(Project project, Uri uri) {
        Asset a = new Asset();
        a.id = UUID.randomUUID().toString();
        a.uri = uri.toString();
        a.mime = resolver.getType(uri);
        if (a.mime == null) a.mime = "application/octet-stream";
        a.name = displayName(uri);
        a.durationMs = duration(uri);

        project.assets.add(a);
        if (a.mime.startsWith("video/") || a.mime.startsWith("image/")) {
            Clip clip = new Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = a.id;
            clip.inMs = 0;
            clip.outMs = a.mime.startsWith("image/") ? 3000 : Math.max(1000, a.durationMs);
            project.clips.add(clip);
        }
        save(project);
        return a;
    }

    public synchronized Asset importGeneratedFile(Project project, java.io.File file, String displayName, String mime, boolean addToTimeline) {
        Asset a = new Asset();
        a.id = UUID.randomUUID().toString();
        a.uri = Uri.fromFile(file).toString();
        a.name = displayName == null || displayName.trim().isEmpty() ? file.getName() : displayName.trim();
        a.mime = mime == null || mime.trim().isEmpty() ? "application/octet-stream" : mime.trim();
        a.durationMs = duration(Uri.fromFile(file));
        project.assets.add(a);
        if (addToTimeline && (a.mime.startsWith("video/") || a.mime.startsWith("image/"))) {
            Clip clip = new Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = a.id;
            clip.inMs = 0;
            clip.outMs = a.mime.startsWith("image/") ? 3000 : Math.max(1000, a.durationMs);
            project.clips.add(clip);
        }
        save(project);
        return a;
    }

    public synchronized JSONObject fullState(String projectId) {
        Project p = get(projectId);
        if (p == null) return null;
        return p.toJson();
    }

    public JSONObject summaries() {
        JSONArray arr = new JSONArray();
        for (Project p : list()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("assetCount", p.assets.size());
                o.put("clipCount", p.clips.size());
                o.put("durationMs", p.outputDurationMs());
                o.put("updatedAt", p.updatedAt);
                o.put("latestExportUri", p.latestExportUri);
                o.put("latestExportName", p.latestExportName);
                o.put("latestExportAt", p.latestExportAt);
                o.put("sourcePrompt", p.sourcePrompt);
            } catch (Exception ignored) {}
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        try { root.put("projects", arr); } catch (Exception ignored) {}
        return root;
    }

    private String displayName(Uri uri) {
        String name = "Media";
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {}
        return name == null ? "Media" : name;
    }

    private long duration(Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = resolver.openFileDescriptor(uri, "r");
            if (pfd == null) return 0;
            r.setDataSource(pfd.getFileDescriptor());
            String value = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void write(List<Project> projects) {
        JSONArray arr = new JSONArray();
        for (Project p : projects) arr.put(p.toJson());
        prefs.edit().putString(KEY_PROJECTS, arr.toString()).apply();
    }
}
