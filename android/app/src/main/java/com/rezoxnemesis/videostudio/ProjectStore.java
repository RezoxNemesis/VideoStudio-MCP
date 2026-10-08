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
public final class ProjectStore {
    private static final String LEGACY_PREFS = "videostudio_native_v1";
    private static final String LEGACY_PROJECTS = "projects";
    private static final String LEGACY_ACTIVE = "active_project";

    private static final String DB_NAME = "videostudio_v3.db";
    private static final int DB_VERSION = 2;
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
        public String importState = "ready", importError = "";
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
                o.put("importState",importState);o.put("importError",importError);
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
            a.importState=o.optString("importState","ready");a.importError=o.optString("importError","");
            a.generated = o.optBoolean("generated", false);
            JSONObject metadata = o.optJSONObject("generationMetadata");
            a.generationMetadata = metadata == null ? new JSONObject() : metadata;
            a.createdAt = o.optLong("createdAt", System.currentTimeMillis());
            return a;
        }
    }

    public static final class Track {
        public String id = UUID.randomUUID().toString();
        public String name = "Video 1";
        public String type = "video";
        public int order;
        public int height = 72;
        public boolean locked, muted, solo;
        public boolean visible = true;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("name", name); o.put("type", type);
                o.put("order", order); o.put("height", height);
                o.put("locked", locked); o.put("muted", muted);
                o.put("solo", solo); o.put("visible", visible);
            } catch (Exception error) { throw new IllegalStateException(error); }
            return o;
        }
        static Track fromJson(JSONObject o) {
            Track t = new Track();
            t.id = o.optString("id", t.id); t.name = o.optString("name", t.name);
            t.type = o.optString("type", "video"); t.order = o.optInt("order", 0);
            t.height = Math.max(40, Math.min(240, o.optInt("height", 72)));
            t.locked = o.optBoolean("locked"); t.muted = o.optBoolean("muted");
            t.solo = o.optBoolean("solo"); t.visible = o.optBoolean("visible", true);
            return t;
        }
        public boolean audioOnly() { return type.startsWith("audio") || "voice_over".equals(type); }
    }

    public static final class Clip {
        public String id;
        public String assetId;
        public long inMs;
        public long outMs;
        public long startMs = -1;
        public String trackId = "";
        public String linkGroup = "";
        public JSONArray keyframes = new JSONArray();
        public float pan = 0f;
        public float speed = 1f;
        public float volume = 1f;
        public String transition = "none";
        public String title = "";
        public JSONObject effects = new JSONObject();

        public long outputDurationMs() {
            return TimelineMath.duration(Math.max(0, inMs), Math.max(inMs, outMs), Math.max(.1f, speed));
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("assetId", assetId);
                o.put("inMs", inMs);
                o.put("outMs", outMs);
                o.put("startMs", startMs);
                o.put("trackId", trackId);
                o.put("linkGroup", linkGroup);
                o.put("keyframes", keyframes);
                o.put("pan", pan);
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
            c.startMs = o.optLong("startMs", -1);
            c.trackId = o.optString("trackId", "");
            c.linkGroup = o.optString("linkGroup", "");
            c.keyframes = o.optJSONArray("keyframes");
            if (c.keyframes == null) c.keyframes = new JSONArray();
            c.pan = (float) o.optDouble("pan", 0);
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
        public long revision;
        public JSONObject settings = new JSONObject();
        public JSONArray markers = new JSONArray();
        public final ArrayList<Track> tracks = new ArrayList<>();
        public String sourcePrompt = "";
        public String latestExportUri = "";
        public String latestExportName = "";
        public long latestExportAt = 0;
        public final ArrayList<Asset> assets = new ArrayList<>();
        public final ArrayList<Clip> clips = new ArrayList<>();

        public long outputDurationMs() {
            ensureTimelineDefaults();
            long end = 0;
            for (Clip c : clips) end = Math.max(end, TimelineMath.add(c.startMs, c.outputDurationMs()));
            return end;
        }

        JSONObject toJson() {
            ensureTimelineDefaults();
            JSONObject o = new JSONObject();
            JSONArray aa = new JSONArray();
            JSONArray cc = new JSONArray();
            JSONArray tt = new JSONArray();
            for (Asset a : assets) aa.put(a.toJson());
            for (Clip c : clips) cc.put(c.toJson());
            for (Track t : tracks) tt.put(t.toJson());
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("updatedAt", updatedAt);
                o.put("schemaVersion", 2);
                o.put("revision", revision);
                o.put("tracks", tt);
                o.put("settings", settings);
                o.put("markers", markers);
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
            p.revision = o.optLong("revision", 0);
            JSONObject settings = o.optJSONObject("settings");
            if (settings != null) p.settings = settings;
            JSONArray markers = o.optJSONArray("markers");
            if (markers != null) p.markers = markers;
            JSONArray tracks = o.optJSONArray("tracks");
            if (tracks != null) for (int i = 0; i < tracks.length(); i++) {
                JSONObject item = tracks.optJSONObject(i);
                if (item != null) p.tracks.add(Track.fromJson(item));
            }
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
            p.ensureTimelineDefaults();
            return p;
        }

        public Asset asset(String id) {
            for (Asset a : assets) if (a.id.equals(id)) return a;
            return null;
        }

        public Track track(String id) {
            for (Track t : tracks) if (t.id.equals(id)) return t;
            return null;
        }

        public Clip clip(String id) {
            for (Clip c : clips) if (c.id.equals(id)) return c;
            return null;
        }

        public void ensureTimelineDefaults() {
            if (tracks.isEmpty()) {
                Track t = new Track(); t.id = "video-1"; tracks.add(t);
            }
            java.util.HashMap<String, Long> ends = new java.util.HashMap<>();
            for (Clip c : clips) {
                if (c.trackId == null || c.trackId.isEmpty()) {
                    Asset a = asset(c.assetId);
                    if (a != null && a.mime != null && a.mime.startsWith("audio/")) {
                        if (track("audio-1") == null) {
                            Track t = new Track(); t.id = "audio-1"; t.name = "Audio 1";
                            t.type = "audio_dialogue"; t.order = tracks.size(); tracks.add(t);
                        }
                        c.trackId = "audio-1";
                    } else c.trackId = tracks.get(0).id;
                }
                if (c.startMs < 0) c.startMs = ends.getOrDefault(c.trackId, 0L);
                ends.put(c.trackId, Math.max(ends.getOrDefault(c.trackId, 0L), TimelineMath.add(c.startMs, c.outputDurationMs())));
            }
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
            createEditorTables(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) createEditorTables(db);
        }

        private void createEditorTables(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS project_history (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT NOT NULL," +
                    "before_json TEXT NOT NULL, after_json TEXT NOT NULL, actor TEXT NOT NULL," +
                    "description TEXT NOT NULL, applied INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_project ON project_history(project_id,seq)");
            db.execSQL("CREATE TABLE IF NOT EXISTS project_snapshots (" +
                    "id TEXT PRIMARY KEY, project_id TEXT NOT NULL, name TEXT NOT NULL," +
                    "json TEXT NOT NULL, created_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS project_receipts (" +
                    "command_id TEXT PRIMARY KEY, project_id TEXT NOT NULL, json TEXT NOT NULL)");
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
        db.enableWriteAheadLogging();
        migrateLegacyProjectsOnce();
    }

    public synchronized List<Project> list() {
        ArrayList<Project> out = new ArrayList<>();
        try (Cursor c = db.query(
                "projects",
                new String[]{"json"},
                null, null, null, null,
                "updated_at DESC")) {
            while (c.moveToNext()) {
                try {
                    Project p = Project.fromJson(new JSONObject(c.getString(0)));
                    out.add(p);
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    public synchronized Project get(String id) {
        if (id == null || id.isEmpty()) return null;
        try (Cursor c = db.query(
                "projects",
                new String[]{"json"},
                "id=?",
                new String[]{id},
                null, null, null,
                "1")) {
            if (c.moveToFirst()) {
                try { return Project.fromJson(new JSONObject(c.getString(0))); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    public synchronized Project active() {
        String id = getMeta(META_ACTIVE);
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
        boolean conflict = false;
        long currentRevision = 0;
        try {
            Project previous = get(project.id);
            if (previous != null && previous.revision != project.revision) {
                currentRevision = previous.revision;
                insertSnapshot(project, "Conflict at revision " + currentRevision);
                conflict = true;
            } else {
                writeRevision(project, previous, "system", "Save project", true);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (conflict) throw new RevisionConflict(project.id, project.revision, currentRevision);
    }

    public static final class RevisionConflict extends IllegalStateException {
        public final String projectId;
        public final long expectedRevision, actualRevision;
        RevisionConflict(String id, long expected, long actual) {
            super("Project changed: expected revision " + expected + ", current " + actual + ". Reload or restore the saved conflict snapshot.");
            projectId = id; expectedRevision = expected; actualRevision = actual;
        }
    }

    public interface Mutation { void apply(Project project) throws Exception; }

    public synchronized Project transact(String id, long expectedRevision, String actor,
                                         String commandId, String description, Mutation mutation) {
        db.beginTransaction();
        try {
            if (commandId != null && !commandId.isEmpty()) {
                try (Cursor c = db.query("project_receipts", new String[]{"project_id", "json"},
                        "command_id=?", new String[]{commandId}, null, null, null)) {
                    if (c.moveToFirst()) {
                        if (!id.equals(c.getString(0))) throw new IllegalArgumentException("Command ID belongs to another project");
                        Project result = Project.fromJson(new JSONObject(c.getString(1)));
                        db.setTransactionSuccessful(); return result;
                    }
                }
            }
            Project before = get(id);
            if (before == null) throw new IllegalArgumentException("Project not found");
            if (expectedRevision >= 0 && before.revision != expectedRevision)
                throw new RevisionConflict(id, expectedRevision, before.revision);
            Project next = Project.fromJson(before.toJson());
            mutation.apply(next);
            writeRevision(next, before, actor == null ? "owner" : actor,
                    description == null ? "Edit project" : description, true);
            if (commandId != null && !commandId.isEmpty()) {
                ContentValues receipt = new ContentValues(); receipt.put("command_id", commandId);
                receipt.put("project_id", id); receipt.put("json", next.toJson().toString());
                db.insertOrThrow("project_receipts", null, receipt);
            }
            db.setTransactionSuccessful(); return next;
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Could not commit editor transaction", error); }
        finally { db.endTransaction(); }
    }

    private void writeRevision(Project next, Project before, String actor, String description, boolean history) {
        if (before != null && before.revision == Long.MAX_VALUE) throw new IllegalStateException("Project revision exhausted");
        next.revision = before == null ? 1 : before.revision + 1;
        next.updatedAt = System.currentTimeMillis();
        next.ensureTimelineDefaults();
        ContentValues values = new ContentValues();
        values.put("id", next.id); values.put("json", next.toJson().toString()); values.put("updated_at", next.updatedAt);
        db.insertWithOnConflict("projects", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (before != null && history) {
            db.delete("project_history", "project_id=? AND applied=0", new String[]{next.id});
            ContentValues row = new ContentValues(); row.put("project_id", next.id);
            row.put("before_json", before.toJson().toString()); row.put("after_json", next.toJson().toString());
            row.put("actor", actor); row.put("description", description); row.put("applied", 1);
            row.put("created_at", next.updatedAt); db.insertOrThrow("project_history", null, row);
            // A practical disk history budget. Named snapshots are never pruned.
            db.execSQL("DELETE FROM project_history WHERE project_id=? AND seq NOT IN " +
                    "(SELECT seq FROM project_history WHERE project_id=? ORDER BY seq DESC LIMIT 500)", new Object[]{next.id, next.id});
        }
    }

    public synchronized Project undo(String id, long expectedRevision) { return historyStep(id, expectedRevision, false); }
    public synchronized Project redo(String id, long expectedRevision) { return historyStep(id, expectedRevision, true); }

    private Project historyStep(String id, long expectedRevision, boolean redo) {
        db.beginTransaction();
        try {
            Project current = get(id);
            if (current == null) throw new IllegalArgumentException("Project not found");
            if (current.revision != expectedRevision) throw new RevisionConflict(id, expectedRevision, current.revision);
            try (Cursor c = db.query("project_history", new String[]{"seq", redo ? "after_json" : "before_json"},
                    "project_id=? AND applied=?", new String[]{id, redo ? "0" : "1"}, null, null,
                    "seq " + (redo ? "ASC" : "DESC"), "1")) {
                if (!c.moveToFirst()) { db.setTransactionSuccessful(); return current; }
                Project next = Project.fromJson(new JSONObject(c.getString(1)));
                writeRevision(next, current, "owner", redo ? "Redo" : "Undo", false);
                ContentValues row = new ContentValues(); row.put("applied", redo ? 1 : 0);
                db.update("project_history", row, "seq=?", new String[]{Long.toString(c.getLong(0))});
                db.setTransactionSuccessful(); return next;
            }
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(error); }
        finally { db.endTransaction(); }
    }

    public synchronized String snapshot(String id, String name) {
        Project p = get(id);
        if (p == null) throw new IllegalArgumentException("Project not found");
        return insertSnapshot(p, name == null || name.trim().isEmpty() ? "Snapshot" : name.trim());
    }

    private String insertSnapshot(Project project, String name) {
        String id = UUID.randomUUID().toString();
        ContentValues row = new ContentValues(); row.put("id", id); row.put("project_id", project.id);
        row.put("name", name); row.put("json", project.toJson().toString()); row.put("created_at", System.currentTimeMillis());
        db.insertOrThrow("project_snapshots", null, row); return id;
    }

    public synchronized JSONArray snapshots(String projectId) {
        JSONArray result = new JSONArray();
        try (Cursor c = db.query("project_snapshots", new String[]{"id", "name", "created_at"},
                "project_id=?", new String[]{projectId}, null, null, "created_at DESC")) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try { o.put("id", c.getString(0)); o.put("name", c.getString(1)); o.put("createdAt", c.getLong(2)); }
                catch (Exception error) { throw new IllegalStateException(error); }
                result.put(o);
            }
        }
        return result;
    }

    public synchronized Project restore(String projectId, long revision, String snapshotId) {
        return transact(projectId, revision, "owner", "", "Restore snapshot", p -> {
            try (Cursor c = db.query("project_snapshots", new String[]{"json"}, "id=? AND project_id=?",
                    new String[]{snapshotId, projectId}, null, null, null)) {
                if (!c.moveToFirst()) throw new IllegalArgumentException("Snapshot not found in this project");
                Project snapshot = Project.fromJson(new JSONObject(c.getString(0)));
                p.name = snapshot.name; p.settings = snapshot.settings; p.markers = snapshot.markers;
                p.tracks.clear(); p.tracks.addAll(snapshot.tracks); p.clips.clear(); p.clips.addAll(snapshot.clips);
                // Retain media published/imported after the snapshot for recovery.
                for (Asset a : snapshot.assets) if (p.asset(a.id) == null) p.assets.add(a);
            }
        });
    }

    public synchronized void delete(String id) {
        if (id == null || id.isEmpty()) return;
        db.delete("projects", "id=?", new String[]{id});
        if (id.equals(getMeta(META_ACTIVE))) {
            List<Project> all = list();
            putMeta(META_ACTIVE, all.isEmpty() ? "" : all.get(0).id);
        }
    }

    /** No provider query or media decoding: immediately journal the owner's selection. */
    public Asset beginImport(String projectId,Uri uri,boolean appendToTimeline){
        if(uri==null)throw new IllegalArgumentException("Media URI is required");
        Asset placeholder=new Asset();placeholder.id=UUID.randomUUID().toString();placeholder.uri=uri.toString();
        placeholder.name="Importing media";placeholder.mime=mimeForName(uri.getLastPathSegment());placeholder.importState="importing";
        try{placeholder.generationMetadata.put("appendAfterImport",appendToTimeline);}catch(Exception error){throw new IllegalStateException(error);}
        Project latest=transact(projectId,-1,"owner","","Import selected media",p->p.assets.add(placeholder));
        return latest.asset(placeholder.id);
    }

    /** Run on a background thread. Only this asset's metadata is merged into the current graph. */
    public Asset completeImport(String projectId,String assetId){
        Project snapshot=get(projectId);Asset source=snapshot==null?null:snapshot.asset(assetId);
        if(source==null)return null;
        if(!"importing".equals(source.importState))return source;
        Uri uri=Uri.parse(source.uri);AssetProbe.Result probe=AssetProbe.probe(resolver,uri);
        long durationMs=duration(uri);
        Project latest=transact(projectId,-1,"system","","Probe imported media",p->{
            Asset a=p.asset(assetId);if(a==null||!source.uri.equals(a.uri)||!"importing".equals(a.importState))return;
            a.mime="application/octet-stream".equals(probe.mime)?mimeForName(probe.displayName.equals("Media")?uri.getLastPathSegment():probe.displayName):probe.mime;
            if("Importing media".equals(a.name))a.name="Media".equals(probe.displayName)?uri.getLastPathSegment():probe.displayName;
            a.sizeBytes=probe.sizeBytes;a.seekable=probe.seekable;a.persistedReadAccess=probe.persistedReadAccess;
            a.providerAuthority=probe.providerAuthority;a.durationMs=durationMs;
            a.importState=probe.readable?"ready":"unavailable";
            a.importError=probe.readable?"":"Storage access is unavailable. Relink this media through Android.";
            if(probe.readable&&(a.mime.startsWith("video/")||a.mime.startsWith("audio/"))&&durationMs<=0){
                a.importState="unavailable";a.importError="The decoder could not read this source's duration. Try a compatible file or proxy.";
            }
            if("ready".equals(a.importState)&&a.generationMetadata.optBoolean("appendAfterImport")&&canUseOnTimeline(a))appendMedia(p,a);
            a.generationMetadata.remove("appendAfterImport");
        });
        return latest.asset(assetId);
    }

    public Asset importUri(Project project, Uri uri) {
        if(project==null)throw new IllegalArgumentException("Project is required");
        Asset selected=beginImport(project.id,uri,true);completeImport(project.id,selected.id);
        refreshReference(project,get(project.id));return project.asset(selected.id);
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

        Asset asset = new Asset();
        asset.id = UUID.randomUUID().toString();
        asset.uri = uri.toString();
        asset.name = name == null || name.trim().isEmpty() ? "Generated media" : name.trim();
        asset.mime = resolver.getType(uri);
        if (asset.mime == null || asset.mime.isEmpty()) {
            asset.mime = mimeForName(asset.name);
        }
        AssetProbe.Result probe=AssetProbe.probe(resolver,uri);
        asset.sizeBytes=probe.sizeBytes;asset.seekable=probe.seekable;asset.persistedReadAccess=probe.persistedReadAccess;asset.providerAuthority=probe.providerAuthority;
        asset.durationMs = duration(uri);
        asset.role = role == null || role.trim().isEmpty() ? "generated" : role.trim();
        asset.generated = true;
        asset.createdAt = System.currentTimeMillis();
        final String[] registered={asset.id};
        Project latest=transact(project.id,-1,"system","","Register generated media",p->{
            Asset existing=null;for(Asset a:p.assets)if(asset.uri.equals(a.uri)){existing=a;break;}
            if(existing==null){p.assets.add(asset);if(appendToTimeline&&canUseOnTimeline(asset))appendMedia(p,asset);}
            else{existing.generated=true;existing.role=asset.role;existing.name=asset.name;registered[0]=existing.id;}
            if("final_render".equals(asset.role)){p.latestExportUri=asset.uri;p.latestExportName=asset.name;p.latestExportAt=System.currentTimeMillis();}
        });
        refreshReference(project,latest);return project.asset(registered[0]);
    }

    public synchronized boolean appendAssetToTimeline(Project project, String assetId) {
        if (project == null || assetId == null || assetId.isEmpty()) return false;
        final boolean[] appended={false};
        Project latest=transact(project.id,-1,"owner","","Add owned media to timeline",p->{
            Asset asset=p.asset(assetId);if(!canUseOnTimeline(asset))return;appendMedia(p,asset);appended[0]=true;
        });
        refreshReference(project,latest);return appended[0];
    }

    public Asset updateAssetMetadata(String projectId,String assetId,JSONObject metadata,long sizeBytes){
        Project latest=transact(projectId,-1,"system","","Update generated media metadata",p->{
            Asset a=p.asset(assetId);if(a==null)throw new IllegalArgumentException("Media was removed from this project");
            a.generationMetadata=new JSONObject(metadata.toString());if(sizeBytes>=0)a.sizeBytes=sizeBytes;
        });return latest.asset(assetId);
    }
    private static boolean canUseOnTimeline(Asset a){return a!=null&&a.mime!=null&&(a.mime.startsWith("video/")||a.mime.startsWith("image/")||a.mime.startsWith("audio/"));}
    private static void appendMedia(Project p,Asset asset){
        p.ensureTimelineDefaults();boolean audio=asset.mime.startsWith("audio/");Track target=null;
        for(Track t:p.tracks)if(!t.locked&&t.audioOnly()==audio){target=t;break;}
        if(target==null){target=new Track();target.type=audio?"audio_music":"video";target.name=audio?"Audio":"Video";target.order=p.tracks.size();p.tracks.add(target);}
        Clip clip=new Clip();clip.id=UUID.randomUUID().toString();clip.assetId=asset.id;clip.trackId=target.id;
        clip.startMs=EditorEngine.trackEnd(p,target.id);clip.outMs=asset.mime.startsWith("image/")?3000:asset.durationMs>0?asset.durationMs:1000;p.clips.add(clip);
    }
    private static String mimeForName(String name){
        String lower=name==null?"":name.toLowerCase(java.util.Locale.US);
        if(lower.endsWith(".mp4")||lower.endsWith(".m4v"))return "video/mp4";if(lower.endsWith(".webm"))return "video/webm";if(lower.endsWith(".mov"))return "video/quicktime";
        if(lower.endsWith(".png"))return "image/png";if(lower.endsWith(".jpg")||lower.endsWith(".jpeg"))return "image/jpeg";if(lower.endsWith(".webp"))return "image/webp";
        if(lower.endsWith(".wav"))return "audio/wav";if(lower.endsWith(".mp3"))return "audio/mpeg";if(lower.endsWith(".m4a"))return "audio/mp4";if(lower.endsWith(".aac"))return "audio/aac";return "application/octet-stream";
    }
    private static void refreshReference(Project target,Project current){
        if(current==null)return;
        target.name=current.name;target.revision=current.revision;target.updatedAt=current.updatedAt;target.settings=current.settings;target.markers=current.markers;
        target.sourcePrompt=current.sourcePrompt;target.latestExportUri=current.latestExportUri;target.latestExportName=current.latestExportName;target.latestExportAt=current.latestExportAt;
        target.tracks.clear();target.tracks.addAll(current.tracks);target.clips.clear();target.clips.addAll(current.clips);target.assets.clear();target.assets.addAll(current.assets);
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

