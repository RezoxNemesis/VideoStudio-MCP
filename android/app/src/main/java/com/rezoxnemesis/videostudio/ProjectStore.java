package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * VideoStudio v3 project store.
 *
 * Projects, timelines and asset metadata are app-owned and persisted in an
 * app-private SQLite database. Existing v1/v1.1 JSON projects are migrated
 * once on upgrade; the old SharedPreferences copy is intentionally left
 * untouched as a rollback safety net.
 */
public final class ProjectStore implements AutoCloseable {
    private static final String LEGACY_PREFS = "videostudio_native_v1";
    private static final String LEGACY_PROJECTS = "projects";
    private static final String LEGACY_ACTIVE = "active_project";

    private static final String DB_NAME = "videostudio_v3.db";
    private static final int DB_VERSION = 1;
    private static final String META_ACTIVE = "active_project";
    private static final String META_MIGRATED = "legacy_projects_migrated";

    public static final class Asset {
        public String id;
        public String uri;
        public String name;
        public String mime;
        public long durationMs;
        public long sizeBytes = -1L;
        public boolean seekable = false;
        public boolean persistedReadAccess = false;
        public String providerAuthority = "";
        public String role = "source";
        public boolean generated = false;
        public JSONObject generationMetadata = new JSONObject();
        public long createdAt = System.currentTimeMillis();

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("uri", uri);
                o.put("name", name);
                o.put("mime", mime);
                o.put("durationMs", durationMs);
                o.put("sizeBytes", sizeBytes);
                o.put("seekable", seekable);
                o.put("persistedReadAccess", persistedReadAccess);
                o.put("providerAuthority", providerAuthority);
                o.put("role", role);
                o.put("generated", generated);
                o.put("generationMetadata", generationMetadata);
                o.put("createdAt", createdAt);
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
            a.sizeBytes = o.has("sizeBytes") ? o.optLong("sizeBytes", -1L) : -1L;
            a.seekable = o.optBoolean("seekable", false);
            a.persistedReadAccess = o.optBoolean("persistedReadAccess", false);
            a.providerAuthority = o.optString("providerAuthority", "");
            if (a.providerAuthority.isEmpty()) {
                try {
                    String authority = Uri.parse(a.uri).getAuthority();
                    a.providerAuthority = authority == null ? "" : authority;
                } catch (Exception ignored) {}
            }
            a.role = o.optString("role", "source");
            a.generated = o.optBoolean("generated", false);
            JSONObject metadata = o.optJSONObject("generationMetadata");
            a.generationMetadata = metadata == null ? new JSONObject() : metadata;
            a.createdAt = o.optLong("createdAt", System.currentTimeMillis());
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
        public final ArrayList<StudioLayer> studioLayers = new ArrayList<>();

        public long outputDurationMs() {
            long total = 0;
            for (Clip c : clips) total += c.outputDurationMs();
            return total;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            JSONArray aa = new JSONArray();
            JSONArray cc = new JSONArray();
            JSONArray ll = new JSONArray();
            for (StudioLayer lane:studioLayers) ll.put(lane.toJson());
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
                o.put("studioLayers", ll);
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
            JSONArray ll = o.optJSONArray("studioLayers");
            if(ll!=null)for(int i=0;i<Math.min(64,ll.length());i++){
                JSONObject item=ll.optJSONObject(i);
                if(item!=null)try{p.studioLayers.add(StudioLayer.fromJson(item));}
                    catch(IllegalArgumentException ignored){} // malformed edits cannot crash startup
            }
            return p;
        }

        public Asset asset(String id) {
            for (Asset a : assets) if (a.id.equals(id)) return a;
            return null;
        }
    }

    private static final class Db extends SQLiteOpenHelper {
        Db(Context context) {
            super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS projects (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "json TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_updated ON projects(updated_at DESC)");
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (" +
                    "key TEXT PRIMARY KEY NOT NULL," +
                    "value TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // v3 schema starts at 1. Future migrations belong here.
        }
    }

    private final ContentResolver resolver;
    private final SharedPreferences legacyPrefs;
    private final SQLiteDatabase db;

    public ProjectStore(Context context) {
        Context app = context.getApplicationContext();
        resolver = app.getContentResolver();
        legacyPrefs = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        db = new Db(app).getWritableDatabase();
        migrateLegacyProjectsOnce();
    }

    /** Close only this store instance's SQLite handle; returned project data is detached. */
    @Override public synchronized void close(){db.close();}

    public synchronized List<Project> list() {
        ArrayList<Project> out = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        try (Cursor c = db.query(
                "projects",
                new String[]{"id"},
                null, null, null, null,
                "updated_at DESC")) {
            while (c.moveToNext()) {
                ids.add(c.getString(0));
            }
        }
        for(String id:ids){Project project=get(id);if(project!=null)out.add(project);}
        return out;
    }

    /** Cold-start dashboard only needs a bounded recent subset. Avoid parsing
     * the entire project archive while displaying the splash/home screen. */
    public synchronized List<Project> listRecent(int limit){
        if(limit<1||limit>32)throw new IllegalArgumentException("Recent project limit must be 1..32");
        ArrayList<Project> recent=new ArrayList<>();
        ArrayList<String> ids=new ArrayList<>();
        try(Cursor c=db.query("projects",new String[]{"id"},null,null,null,null,
                "updated_at DESC, rowid DESC",Integer.toString(limit))){
            while(c.moveToNext())ids.add(c.getString(0));
        }
        for(String id:ids){
            Project project=get(id);
            if(project!=null)recent.add(project);
        }
        return recent;
    }

    public synchronized Project get(String id) {
        if (id == null || id.isEmpty()) return null;
        try {String json=SQLiteLargeText.read(db,"SELECT json FROM projects WHERE id=?",id);if(json!=null)return Project.fromJson(new JSONObject(json));}
        catch(Exception invalid){return null;}
        return null;
    }

    /** Merge completed imports into the current project while holding the shared SQLite writer lock. */
    public synchronized Asset registerImportedAsset(String projectId,Uri uri,String name,String mime,long durationMs,long sizeBytes){
        if(uri==null||name==null||name.trim().isEmpty()||mime==null||mime.isEmpty()||durationMs<0||sizeBytes<0)throw new IllegalArgumentException("Valid imported media metadata is required");
        db.beginTransaction();try{
            Project current=get(projectId);if(current==null)throw new IllegalArgumentException("Import project no longer exists");
            for(Asset existing:current.assets)if(uri.toString().equals(existing.uri)){db.setTransactionSuccessful();return existing;}
            Asset asset=new Asset();asset.id=UUID.randomUUID().toString();asset.uri=uri.toString();asset.name=name;asset.mime=mime;asset.durationMs=durationMs;asset.sizeBytes=sizeBytes;asset.seekable=true;asset.persistedReadAccess=true;asset.providerAuthority="";current.assets.add(asset);
            if(mime.startsWith("video/")||mime.startsWith("image/")){Clip clip=new Clip();clip.id=UUID.randomUUID().toString();clip.assetId=asset.id;clip.inMs=0;clip.outMs=mime.startsWith("image/")?3000:Math.max(1000,durationMs);current.clips.add(clip);}
            save(current);db.setTransactionSuccessful();return asset;
        }finally{db.endTransaction();}
    }

    @FunctionalInterface
    public interface GuardedProjectWork<T> {
        T run(Project current) throws Exception;
    }

    /**
     * Protect source-bound annotation writes with the SQLite writer lock shared
     * by all ProjectStore instances. The callback acquires any profile-file lock
     * only after this lock, and must not publish unrelated project changes.
     */
    public synchronized <T> T guardedProject(String projectId, long expectedUpdatedAt,
                                             GuardedProjectWork<T> work) throws Exception {
        if (expectedUpdatedAt < 0 || work == null)
            throw new IllegalArgumentException("An exact source revision and callback are required");
        db.beginTransaction();
        try {
            Project current = get(projectId);
            if (current == null) throw new IllegalArgumentException("Unknown source project");
            if (current.updatedAt != expectedUpdatedAt)
                throw new IllegalStateException("SOURCE_PROJECT_CHANGED: reopen the source before saving annotations");
            T result = work.run(current);
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized Project active() {
        String id = getMeta(META_ACTIVE);
        Project p = id.isEmpty() ? null : get(id);
        if (p == null) {
            List<Project> all = listRecent(1);
            if (!all.isEmpty()) p = all.get(0);
        }
        return p;
    }

    public synchronized Project create(String name) {
        Project p = new Project();
        p.id = UUID.randomUUID().toString();
        p.name = name == null || name.trim().isEmpty() ? "Untitled Project" : name.trim();
        p.updatedAt = System.currentTimeMillis();
        save(p);
        setActive(p.id);
        return p;
    }

    public synchronized void setActive(String id) {
        putMeta(META_ACTIVE, id == null ? "" : id);
    }

    public synchronized void save(Project project) {
        if (project == null) return;
        db.beginTransaction();
        long before = project.updatedAt;
        try {
            Project persisted = get(project.id);
            long next = persisted == null ? 0 : Math.addExact(persisted.updatedAt, 1L);
            project.updatedAt = Math.max(System.currentTimeMillis(), next);
            ContentValues values = new ContentValues();
            values.put("id", project.id);
            values.put("json", project.toJson().toString());
            values.put("updated_at", project.updatedAt);
            if (db.insertWithOnConflict("projects", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new IllegalStateException("Project persistence failed");
            db.setTransactionSuccessful();
        } catch (RuntimeException failure) {
            project.updatedAt = before;
            throw failure;
        } finally {
            db.endTransaction();
        }
    }

    /** Durable ownership witness for the one-asset native still export; survives later deletion. */
    synchronized JSONObject sketchStillCommit(String operationKey) throws Exception {
        String value=getMeta(sketchStillCommitKey(operationKey));
        if(value.isEmpty())return null;
        if(value.length()>64*1024)throw new IllegalStateException("SKETCH_STILL_COMMIT_CHANGED");
        return new JSONObject(value);
    }

    /** Insert the new output and its recovery witness in ONE writer transaction, without activation. */
    synchronized JSONObject createSketchStillOutput(Project project,String operationKey,JSONObject receipt) throws Exception {
        String key=sketchStillCommitKey(operationKey);
        if(project==null||project.assets.size()!=1||!project.clips.isEmpty()||!project.studioLayers.isEmpty()||
                !project.id.equals(receipt.getString("outputProjectId"))||!"png".equals(receipt.getString("format"))||
                !"image/png".equals(project.assets.get(0).mime)||!project.assets.get(0).generated||
                !project.assets.get(0).id.equals(receipt.getString("assetId")))
            throw new IllegalArgumentException("A new one-image native still output is required");
        JSONObject committed=new JSONObject(receipt.toString());long before=project.updatedAt;
        db.beginTransaction();
        try {
            if(!getMeta(key).isEmpty()||get(project.id)!=null)
                throw new IllegalStateException("SKETCH_STILL_OUTPUT_PROJECT_CHANGED");
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch still export cancelled; source retained");
            project.updatedAt=Math.max(1,System.currentTimeMillis());committed.put("updatedAt",project.updatedAt);
            String proof=committed.toString();if(proof.length()>64*1024)throw new IllegalArgumentException("Still commit receipt exceeds budget");
            ContentValues row=new ContentValues();row.put("id",project.id);row.put("json",project.toJson().toString());row.put("updated_at",project.updatedAt);
            db.insertOrThrow("projects",null,row);
            ContentValues witness=new ContentValues();witness.put("key",key);witness.put("value",proof);db.insertOrThrow("meta",null,witness);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch still export cancelled; source retained");
            db.setTransactionSuccessful();return committed;
        }catch(Exception failure){project.updatedAt=before;throw failure;}
        finally{db.endTransaction();}
    }

    private static String sketchStillCommitKey(String operationKey){
        if(operationKey==null||!operationKey.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact still operation identity required");
        return "sketch_still_commit/"+operationKey;
    }

    public synchronized void delete(String id) {
        if (id == null || id.isEmpty()) return;
        db.delete("projects", "id=?", new String[]{id});
        if (id.equals(getMeta(META_ACTIVE))) {
            List<Project> all = list();
            putMeta(META_ACTIVE, all.isEmpty() ? "" : all.get(0).id);
        }
    }

    public Asset importUri(Project project, Uri uri) {
        Asset a = new Asset();
        a.id = UUID.randomUUID().toString();
        a.uri = uri.toString();
        AssetProbe.Result probe = AssetProbe.probe(resolver, uri);
        a.mime = probe.mime;
        a.name = probe.displayName;
        a.sizeBytes = probe.sizeBytes;
        a.seekable = probe.seekable;
        a.persistedReadAccess = probe.persistedReadAccess;
        a.providerAuthority = probe.providerAuthority;
        a.durationMs = duration(uri);
        a.role = "source";
        a.generated = false;
        a.createdAt = System.currentTimeMillis();

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

    /**
     * Register media created by VideoStudio itself as a first-class project asset.
     * Generated outputs live in the project media bin even when they are not
     * automatically inserted into the timeline.
     */
    public synchronized Asset registerGeneratedAsset(Project project,
                                                     Uri uri,
                                                     String name,
                                                     String role,
                                                     boolean appendToTimeline) {
        if (project == null) throw new IllegalArgumentException("Project is required");
        if (uri == null) throw new IllegalArgumentException("Generated asset URI is required");

        String uriValue = uri.toString();
        for (Asset existing : project.assets) {
            if (uriValue.equals(existing.uri)) {
                existing.generated = true;
                existing.role = role == null || role.trim().isEmpty() ? "generated" : role.trim();
                if (name != null && !name.trim().isEmpty()) existing.name = name.trim();
                save(project);
                return existing;
            }
        }

        Asset asset = new Asset();
        asset.id = UUID.randomUUID().toString();
        asset.uri = uriValue;
        asset.name = name == null || name.trim().isEmpty() ? "Generated media" : name.trim();
        asset.mime = resolver.getType(uri);
        if (asset.mime == null || asset.mime.isEmpty()) {
            String lower = asset.name.toLowerCase();
            asset.mime = lower.endsWith(".mp4") ? "video/mp4"
                    : (lower.endsWith(".png") ? "image/png"
                    : (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
                    : (lower.endsWith(".wav") ? "audio/wav"
                    : (lower.endsWith(".mp3") ? "audio/mpeg"
                    : (lower.endsWith(".m4a") ? "audio/mp4"
                    : (lower.endsWith(".aac") ? "audio/aac" : "application/octet-stream"))))));
        }
        asset.durationMs = duration(uri);
        asset.role = role == null || role.trim().isEmpty() ? "generated" : role.trim();
        asset.generated = true;
        asset.createdAt = System.currentTimeMillis();
        project.assets.add(asset);

        if (appendToTimeline && (asset.mime.startsWith("video/") || asset.mime.startsWith("image/"))) {
            Clip clip = new Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = asset.id;
            clip.inMs = 0;
            clip.outMs = asset.mime.startsWith("image/") ? 3000 : Math.max(1000, asset.durationMs);
            project.clips.add(clip);
        }

        save(project);
        return asset;
    }

    public synchronized boolean appendAssetToTimeline(Project project, String assetId) {
        if (project == null || assetId == null || assetId.isEmpty()) return false;
        Asset asset = project.asset(assetId);
        if (asset == null || asset.mime == null) return false;
        if (!asset.mime.startsWith("video/") && !asset.mime.startsWith("image/")) return false;

        Clip clip = new Clip();
        clip.id = UUID.randomUUID().toString();
        clip.assetId = asset.id;
        clip.inMs = 0;
        clip.outMs = asset.mime.startsWith("image/") ? 3000 : Math.max(1000, asset.durationMs);
        project.clips.add(clip);
        save(project);
        return true;
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
        try {
            root.put("projects", arr);
            root.put("storageBackend", "sqlite-v3");
        } catch (Exception ignored) {}
        return root;
    }

    public String storageBackend() {
        return "sqlite-v3";
    }

    private synchronized void migrateLegacyProjectsOnce() {
        if ("1".equals(getMeta(META_MIGRATED))) return;

        db.beginTransaction();
        try {
            String raw = legacyPrefs.getString(LEGACY_PROJECTS, "[]");
            JSONArray arr;
            try { arr = new JSONArray(raw); }
            catch (Exception ignored) { arr = new JSONArray(); }

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Project p = Project.fromJson(o);
                ContentValues values = new ContentValues();
                values.put("id", p.id);
                values.put("json", p.toJson().toString());
                values.put("updated_at", p.updatedAt);
                db.insertWithOnConflict("projects", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }

            String legacyActive = legacyPrefs.getString(LEGACY_ACTIVE, "");
            if (!legacyActive.isEmpty() && get(legacyActive) != null) {
                putMeta(META_ACTIVE, legacyActive);
            } else if (getMeta(META_ACTIVE).isEmpty()) {
                List<Project> all = list();
                if (!all.isEmpty()) putMeta(META_ACTIVE, all.get(0).id);
            }
            putMeta(META_MIGRATED, "1");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private String getMeta(String key) {
        try (Cursor c = db.query("meta", new String[]{"value"}, "key=?", new String[]{key}, null, null, null, "1")) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    private void putMeta(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value == null ? "" : value);
        db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
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
}

