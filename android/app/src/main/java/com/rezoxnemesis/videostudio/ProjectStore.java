package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.Comparator;
import java.io.File;
import java.io.InputStream;

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
    private static final int DB_VERSION = 3;
    public static final long ANY_REVISION = -1L;
    private static final int MAX_HISTORY_ENTRIES = 64;
    private static final long MAX_HISTORY_BYTES = 16L * 1024L * 1024L;
    private static final String META_ACTIVE = "active_project";
    private static final String META_MIGRATED = "legacy_projects_migrated";

    public static final class Asset {
        public String id;
        public String uri;
        public String name;
        public String mime;
        public long durationMs;
        public int width;
        public int height;
        public int rotation;
        public boolean hasAudio;
        public JSONObject importMetadata = new JSONObject();
        public long sizeBytes = -1L;
        public boolean seekable = false;
        public boolean persistedReadAccess = false;
        public String providerAuthority = "";
        public String role = "source";
        public boolean generated = false;
        public JSONObject generationMetadata = new JSONObject();
        public long createdAt = System.currentTimeMillis();

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("uri", uri);
                o.put("name", name);
                o.put("mime", mime);
                o.put("durationMs", durationMs);
                o.put("width", width);
                o.put("height", height);
                o.put("rotation", rotation);
                o.put("hasAudio", hasAudio);
                o.put("importMetadata", importMetadata == null ? new JSONObject() : importMetadata);
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
            a.width = o.optInt("width", 0);
            a.height = o.optInt("height", 0);
            a.rotation = o.optInt("rotation", 0);
            a.hasAudio = o.optBoolean("hasAudio", a.mime.startsWith("audio/") || a.mime.startsWith("video/"));
            JSONObject imported = o.optJSONObject("importMetadata");
            a.importMetadata = imported == null ? new JSONObject() : imported;
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
        public String trackId = "";
        /** Aligned clips edited as one unit; empty means independent. */
        public String linkGroupId = "";
        /** Absolute program time. -1 means append during legacy normalization. */
        public long startMs = -1L;
        /** Exact span used only when a razor cut quantizes source milliseconds. */
        public long programDurationMs = -1L;
        public long inMs;
        public long outMs;
        public float speed = 1f;
        public float volume = 1f;
        public String transition = "none";
        public String title = "";
        public JSONObject effects = new JSONObject();

        public long outputDurationMs() {
            if (programDurationMs >= 0L) return programDurationMs;
            double rate = Float.isNaN(speed) || Float.isInfinite(speed) || speed <= 0 ? 1d : speed;
            return Math.max(0L, Math.round((outMs - inMs) / rate));
        }

        public float effectiveSpeed() {
            return programDurationMs > 0L ? (float) ((outMs - inMs) / (double) programDurationMs) : speed;
        }

        public long endMs() {
            return ProjectTimeline.safeAdd(Math.max(0L, startMs), outputDurationMs());
        }

        public long sourceTimeMs(long programMs) {
            long local = Math.max(0L, Math.min(outputDurationMs(), programMs - Math.max(0L, startMs)));
            return Math.max(inMs, Math.round(Math.min((double) outMs, (double) inMs + local * (double) effectiveSpeed())));
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("assetId", assetId);
                o.put("trackId", trackId);
                o.put("linkGroupId", linkGroupId == null ? "" : linkGroupId);
                o.put("startMs", startMs);
                o.put("programDurationMs", programDurationMs);
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
            c.trackId = o.optString("trackId", "");
            c.linkGroupId = o.optString("linkGroupId", "");
            c.startMs = o.has("startMs") ? o.optLong("startMs", -1L) : -1L;
            c.programDurationMs = o.optLong("programDurationMs", -1L);
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

    public static final class Track {
        public String id;
        public String name = "Video";
        public String type = "video";
        public int order;
        public boolean locked;
        public boolean muted;
        public boolean solo;
        public boolean visible = true;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("name", name); o.put("type", type); o.put("order", order);
                o.put("locked", locked); o.put("muted", muted); o.put("solo", solo); o.put("visible", visible);
            } catch (Exception ignored) {}
            return o;
        }

        static Track fromJson(JSONObject o) {
            Track t = new Track();
            t.id = o.optString("id", ""); t.type = o.optString("type", "video");
            t.name = o.optString("name", t.type); t.order = o.optInt("order", 0);
            t.locked = o.optBoolean("locked", false); t.muted = o.optBoolean("muted", false);
            t.solo = o.optBoolean("solo", false); t.visible = o.optBoolean("visible", true);
            return t;
        }

        public boolean isAudio() { return ProjectTimeline.isAudioTrack(type); }
    }

    public static final class Project {
        public String id;
        public String name;
        public long updatedAt;
        /** Monotonic persisted revision, including undo and redo. */
        public long revision;
        public final ArrayList<Track> tracks = new ArrayList<>();
        public JSONArray markers = new JSONArray();
        public JSONObject editorRange = new JSONObject();
        /** 0 retains the legacy 30 fps export profile; animation authoring selects an explicit cadence. */
        public int animationFrameRate = 0;
        public String sourcePrompt = "";
        public String latestExportUri = "";
        public String latestExportName = "";
        public long latestExportAt = 0;
        public final ArrayList<Asset> assets = new ArrayList<>();
        public final ArrayList<Clip> clips = new ArrayList<>();

        public long outputDurationMs() {
            long end = 0L;
            for (Clip c : clips) end = Math.max(end, c.endMs());
            return end;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            JSONArray tt = new JSONArray();
            for (Track t : tracks) tt.put(t.toJson());
            JSONArray aa = new JSONArray();
            JSONArray cc = new JSONArray();
            for (Asset a : assets) aa.put(a.toJson());
            for (Clip c : clips) cc.put(c.toJson());
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("updatedAt", updatedAt);
                o.put("revision", revision);
                o.put("timelineSchema", 2);
                o.put("tracks", tt);
                o.put("markers", markers == null ? new JSONArray() : markers);
                o.put("editorRange", editorRange == null ? new JSONObject() : editorRange);
                o.put("animationFrameRate", animationFrameRate);
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
            p.revision = Math.max(0L, o.optLong("revision", 0L));
            JSONArray savedMarkers = o.optJSONArray("markers");
            p.markers = savedMarkers == null ? new JSONArray() : savedMarkers;
            JSONObject savedRange = o.optJSONObject("editorRange");
            p.editorRange = savedRange == null ? new JSONObject() : savedRange;
            if (o.has("animationFrameRate")) {
                Object value = o.opt("animationFrameRate");
                if (!(value instanceof Number)) throw new IllegalArgumentException("Animation frame rate must be an integer number");
                double rate = ((Number) value).doubleValue();
                if (!Double.isFinite(rate) || rate != Math.rint(rate)
                        || !(rate == 0d || rate == 12d || rate == 24d || rate == 30d || rate == 60d))
                    throw new IllegalArgumentException("Animation frame rate must be 0, 12, 24, 30 or 60; fractional cadence is unavailable");
                p.animationFrameRate = (int) rate;
            }
            JSONArray tt = o.optJSONArray("tracks");
            if (tt != null) for (int i = 0; i < tt.length(); i++) {
                JSONObject item = tt.optJSONObject(i);
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
            ProjectTimeline.normalize(p);
            return p;
        }

        public Clip clip(String id) {
            for (Clip c : clips) if (id != null && id.equals(c.id)) return c;
            return null;
        }

        public Track track(String id) {
            for (Track t : tracks) if (id != null && id.equals(t.id)) return t;
            return null;
        }

        public Track defaultTrack(String type) { return ProjectTimeline.defaultTrack(this, type); }

        public List<Track> renderTracks() {
            ArrayList<Track> ordered = new ArrayList<>(tracks);
            Collections.sort(ordered, Comparator.comparingInt((Track t) -> t.order).thenComparing(t -> t.id));
            return ordered;
        }

        public List<Clip> clipsOnTrack(String trackId) {
            ArrayList<Clip> ordered = new ArrayList<>();
            for (Clip c : clips) if (trackId != null && trackId.equals(c.trackId)) ordered.add(c);
            Collections.sort(ordered, Comparator.comparingLong(c -> c.startMs));
            return ordered;
        }

        /** Includes the selected clip; independent clips return a one-item list. */
        public List<Clip> linkedClips(String clipId) {
            Clip selected = clip(clipId);
            if (selected == null) return new ArrayList<>();
            return ProjectTimeline.linkedClips(this, selected);
        }

        /** Solo is scoped to audio or visual tracks so audio solo does not hide the picture. */
        public boolean isTrackEnabled(Track track) {
            if (track == null || !track.visible) return false;
            boolean hasSolo = false;
            for (Track t : tracks) if (t.visible && t.solo && t.isAudio() == track.isAudio()) hasSolo = true;
            return !hasSolo || track.solo;
        }

        public boolean isTrackAudible(Track track) { return isTrackEnabled(track) && !track.muted; }

        public Asset asset(String id) {
            for (Asset a : assets) if (id != null && id.equals(a.id)) return a;
            return null;
        }
    }

    private static final class Db extends SQLiteOpenHelper {
        private final File filesRoot;
        Db(Context context) {
            super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
            filesRoot = canonicalOwnedRoot(context.getApplicationContext().getFilesDir());
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
            createHistory(db);
            ProjectExportPins.createSchema(db);
        }

        private void createHistory(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS project_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "project_id TEXT NOT NULL," +
                    "before_json TEXT NOT NULL," +
                    "after_json TEXT NOT NULL," +
                    "label TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "applied INTEGER NOT NULL DEFAULT 1)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_project ON project_history(project_id, id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS project_snapshots (" +
                    "id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL, name TEXT NOT NULL," +
                    "json TEXT NOT NULL, revision INTEGER NOT NULL, created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_project ON project_snapshots(project_id, created_at)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Additive migration: existing JSON, assets and active-project metadata remain intact.
            if (oldVersion < 2) createHistory(db);
            if (oldVersion < 3) ProjectExportPins.createSchema(db);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            // Additive caches do not alter or discard existing user graphs.
            // Install their triggers on every open, after migrations created
            // every protected table, before any Store instance can admit work.
            ProjectSourceArchiveReferences.createSchema(db, filesRoot);
            ProjectMediaReferenceIndex.createSchema(db);
            ProjectMediaReferenceIndex.protectMetadataTable(db, ProjectSourceArchiveReferences.TABLE);
        }
    }

    private final ContentResolver resolver;
    private final SharedPreferences legacyPrefs;
    private final SQLiteDatabase db;
    private final ProjectExportPins pinnedExports;
    private final ProjectSourceArchiveReferences sourceArchiveReferences;
    private final File ownedFilesBase;
    private final File ownedCacheBase;
    private final File ownedFilesRoot;
    private final File ownedCacheRoot;

    public ProjectStore(Context context) {
        Context app = context.getApplicationContext();
        resolver = app.getContentResolver();
        ownedFilesBase = app.getFilesDir().getAbsoluteFile();
        ownedCacheBase = app.getCacheDir().getAbsoluteFile();
        ownedFilesRoot = canonicalOwnedRoot(ownedFilesBase);
        ownedCacheRoot = canonicalOwnedRoot(ownedCacheBase);
        legacyPrefs = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        db = new Db(app).getWritableDatabase();
        pinnedExports = new ProjectExportPins(db);
        sourceArchiveReferences = new ProjectSourceArchiveReferences(db);
        migrateLegacyProjectsOnce();
    }

    public synchronized List<Project> list() {
        ArrayList<Project> out = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        try (Cursor c = db.query(
                "projects",
                new String[]{"id"},
                null, null, null, null,
                "updated_at DESC")) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        for (String id : ids) { Project project = get(id); if (project != null) out.add(project); }
        return out;
    }

    public synchronized Project get(String id) {
        if (id == null || id.isEmpty()) return null;
        // Pin admission, owner reload and export registration all need the same
        // chunk-safe live reader. Keep each multi-query read on one SQLite view.
        db.beginTransaction();
        try {
            Project project;
            try { project = pinnedExports.readLiveProject(id); }
            catch (IllegalArgumentException | IllegalStateException unreadable) { project = null; }
            db.setTransactionSuccessful();
            return project;
        } finally { db.endTransaction(); }
    }

    /**
     * Ownership includes every live project, both sides of persisted undo/redo,
     * named snapshots, and unfinished immutable export pins. A damaged record
     * conservatively retains the media.
     * Callers must hold their import admission lock across this check and file
     * cleanup so a second importer cannot adopt the same destination meanwhile.
     */
    public synchronized boolean referencesMediaUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        db.beginTransaction();
        try {
            boolean referenced = referencesMediaUriInTransaction(uri);
            db.setTransactionSuccessful();
            return referenced;
        } finally { db.endTransaction(); }
    }

    /**
     * Delete one regular app-owned file while the SQLite writer transaction
     * excludes every concurrent graph/pin admission. A separate reference check
     * followed by File.delete leaves a race with another ProjectStore instance.
     * Content providers, external storage, symbolic links and path aliases are
     * never deletion capabilities of this API.
     */
    public synchronized boolean deleteMediaIfUnreferenced(File file) {
        if (file == null || !file.isFile()) return false;
        return deleteMediaIfUnreferenced(file, file.length(), file.lastModified());
    }

    /** Offload supplies the exact size/mtime whose archive checksum it verified. */
    public synchronized boolean deleteMediaIfUnreferenced(File file, long expectedLength, long expectedModified) {
        if (file == null || expectedLength < 0L || expectedModified < 0L) return false;
        File target = file.getAbsoluteFile();
        if (!deletableIdentity(target, expectedLength, expectedModified)) return false;
        db.beginTransaction();
        try {
            boolean removed = false;
            File canonicalTarget = ownedCanonicalTarget(target);
            if (!referencesMediaUriInTransaction(Uri.fromFile(target).toString())
                    && (canonicalTarget.equals(target) || !referencesMediaUriInTransaction(Uri.fromFile(canonicalTarget).toString()))
                    && deletableIdentity(target, expectedLength, expectedModified)) removed = target.delete();
            db.setTransactionSuccessful();
            return removed;
        } finally { db.endTransaction(); }
    }

    private boolean referencesMediaUriInTransaction(String uri) {
        return ProjectMediaReferenceIndex.referencesInTransaction(db, uri);
    }

    /** Register archive metadata ownership before publishing its app-private file. */
    public synchronized void recordSourceArchiveReferences(String recordId, String ownerId, JSONObject evidence) {
        db.beginTransaction();
        try {
            sourceArchiveReferences.record(recordId, ownerId, evidence);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Release only after the exact journal was removed and its catalog is durable. */
    public synchronized boolean removeSourceArchiveReferences(String recordId, String ownerId) {
        db.beginTransaction();
        try {
            boolean removed = sourceArchiveReferences.remove(recordId, ownerId);
            db.setTransactionSuccessful();
            return removed;
        } finally { db.endTransaction(); }
    }

    /** Existing pre-ledger files must all be admitted before cleanup can prove ownership. */
    public synchronized boolean sourceArchiveReferencesInitialized() {
        db.beginTransaction();
        try {
            boolean initialized = ProjectSourceArchiveReferences.initialized(db);
            db.setTransactionSuccessful();
            return initialized;
        } finally { db.endTransaction(); }
    }

    public synchronized void markSourceArchiveReferencesInitialized() {
        db.beginTransaction();
        try {
            sourceArchiveReferences.markInitialized();
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private boolean deletableIdentity(File target, long length, long modified) {
        try {
            File expectedCanonical = ownedCanonicalTarget(target);
            if (!target.isFile() || !target.getCanonicalFile().equals(expectedCanonical)
                    || target.length() != length || target.lastModified() != modified) return false;
            return true;
        } catch (java.io.IOException unreadableIdentity) { return false; }
    }

    private File ownedCanonicalTarget(File target) {
        // Android may expose an app root through /data/user/0 while its canonical
        // base is /data/data. Permit that base mapping only. The exact relative
        // path must survive canonicalization, excluding aliases/symlinks inside
        // the owned tree as well as dot or parent path segments.
        File[] bases = {ownedFilesBase, ownedCacheBase, ownedFilesRoot, ownedCacheRoot};
        File[] canonicalRoots = {ownedFilesRoot, ownedCacheRoot, ownedFilesRoot, ownedCacheRoot};
        String path = target.getPath();
        for (int index = 0; index < bases.length; index++) {
            String prefix = bases[index].getPath() + File.separator;
            if (path.startsWith(prefix)) return new File(canonicalRoots[index], path.substring(prefix.length())).getAbsoluteFile();
        }
        throw new IllegalArgumentException("Media cleanup is restricted to this app's private files and cache");
    }

    private static File canonicalOwnedRoot(File root) {
        try { return root.getCanonicalFile(); }
        catch (java.io.IOException unreadable) { throw new IllegalStateException("App-owned storage identity cannot be resolved", unreadable); }
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

    /**
     * Persist the exact graph accepted for one trusted export admission. IDs are
     * generated by the service, never copied from remote caller parameters.
     * A matching replay reads the existing immutable graph even after later
     * project edits or deletion; it never replaces that graph with current state.
     * This does not advance the project revision or editing history.
     */
    public synchronized Project captureExportPin(String projectId, long expectedRevision, String pinId, String ownerId) {
        ProjectExportPins.requireIdentity(pinId, "Export pin ID");
        ProjectExportPins.requireIdentity(ownerId, "Export pin owner");
        ProjectExportPins.requireIdentity(projectId, "Project ID");
        if (expectedRevision < 0L) throw new IllegalArgumentException("An exact export project revision is required");
        db.beginTransaction();
        try {
            Project existing = pinnedExports.read(pinId, ownerId);
            if (existing != null) {
                if (!projectId.equals(existing.id) || existing.revision != expectedRevision)
                    throw new IllegalStateException("Export pin belongs to a different project admission");
                db.setTransactionSuccessful();
                return existing;
            }
            Project captured = pinnedExports.readLiveProject(projectId);
            if (captured == null) throw new IllegalArgumentException("Project not found");
            if (captured.revision != expectedRevision) throw new RevisionConflictException(expectedRevision, captured.revision);
            ProjectTimeline.validate(captured);
            Project pinned = pinnedExports.capture(captured, pinId, ownerId);
            db.setTransactionSuccessful();
            return pinned;
        } finally { db.endTransaction(); }
    }

    /** Returns a detached graph, or null for a missing pin. Wrong ownership or damaged pins fail closed. */
    public synchronized Project readExportPin(String pinId, String ownerId) {
        ProjectExportPins.requireIdentity(pinId, "Export pin ID");
        ProjectExportPins.requireIdentity(ownerId, "Export pin owner");
        db.beginTransaction();
        try {
            Project project = pinnedExports.read(pinId, ownerId);
            db.setTransactionSuccessful();
            return project;
        } finally { db.endTransaction(); }
    }

    /** A source-only admission shares the unfinished-work pin budget and retains no unrelated media. */
    public synchronized Asset captureSourceArchivePin(String projectId, long expectedRevision, String assetId, String pinId, String ownerId) {
        ProjectExportPins.requireIdentity(projectId, "Project ID");
        ProjectExportPins.requireIdentity(assetId, "Source asset ID");
        ProjectExportPins.requireIdentity(pinId, "Source archive pin ID");
        ProjectExportPins.requireIdentity(ownerId, "Source archive pin owner");
        if (expectedRevision < 0L) throw new IllegalArgumentException("An exact source archive project revision is required");
        db.beginTransaction();
        try {
            Project pinned = pinnedExports.read(pinId, ownerId);
            if (pinned == null) {
                Project current = requireProject(projectId, expectedRevision);
                Asset source = current.asset(assetId);
                if (source == null || source.generated || !"source".equals(source.role))
                    throw new IllegalArgumentException("Select a registered original source asset");
                String sourceJson = source.toJson().toString();
                if (sourceJson.length() > 64 * 1024 || sourceJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024)
                    throw new IllegalArgumentException("Source archive admission metadata exceeds 64 KiB");
                Project snapshot = new Project(); snapshot.id = current.id; snapshot.name = "Source media archive";
                snapshot.revision = current.revision; snapshot.updatedAt = current.updatedAt;
                snapshot.sourcePrompt = "source_archive_pin:" + assetId;
                try { snapshot.assets.add(Asset.fromJson(new JSONObject(sourceJson))); }
                catch (Exception error) { throw new IllegalArgumentException("Source archive metadata cannot be copied", error); }
                ProjectTimeline.normalize(snapshot);
                pinned = pinnedExports.capture(snapshot, pinId, ownerId);
            }
            Asset source = requireSourceArchivePin(pinned, projectId, assetId);
            if (pinned.revision != expectedRevision) throw new IllegalStateException("Source archive pin belongs to another accepted revision");
            db.setTransactionSuccessful();
            return source;
        } finally { db.endTransaction(); }
    }

    public synchronized Asset readSourceArchivePin(String projectId, String assetId, String pinId, String ownerId) {
        Project pinned = readExportPin(pinId, ownerId);
        return pinned == null ? null : requireSourceArchivePin(pinned, projectId, assetId);
    }

    public boolean releaseSourceArchivePin(String pinId, String ownerId) { return releaseExportPin(pinId, ownerId); }

    private static Asset requireSourceArchivePin(Project pinned, String projectId, String assetId) {
        if (!projectId.equals(pinned.id) || !pinned.sourcePrompt.equals("source_archive_pin:" + assetId)
                || pinned.assets.size() != 1 || !pinned.clips.isEmpty())
            throw new IllegalStateException("Source archive pin belongs to another admission");
        Asset source = pinned.asset(assetId);
        if (source == null || source.generated || !"source".equals(source.role))
            throw new IllegalStateException("Source archive pin does not contain the accepted original source");
        return source;
    }

    /** Only the trusted owning admission may release a terminal export's retained graph/media. */
    public synchronized boolean releaseExportPin(String pinId, String ownerId) {
        ProjectExportPins.requireIdentity(pinId, "Export pin ID");
        ProjectExportPins.requireIdentity(ownerId, "Export pin owner");
        db.beginTransaction();
        try {
            boolean released = pinnedExports.release(pinId, ownerId);
            db.setTransactionSuccessful();
            return released;
        } finally { db.endTransaction(); }
    }

    /** Metadata for trusted job-ledger reconciliation; graph contents are never listed here. */
    public synchronized JSONArray exportPins() { return pinnedExports.metadata(); }

    /**
     * Explicit terminal-job cleanup only. No age-based or snapshot-count pruning
     * is allowed for unfinished exports. Every supplied owner is checked before
     * any row is released, so a mismatched pair rolls back the entire cleanup.
     */
    public synchronized int cleanupExportPins(java.util.Map<String, String> completedPinOwners) {
        if (completedPinOwners == null) throw new IllegalArgumentException("Completed export ownership is required");
        java.util.Map<String, String> owners = new java.util.LinkedHashMap<>(completedPinOwners);
        if (owners.size() > ProjectExportPins.MAX_PINS) throw new IllegalArgumentException("Too many export pin cleanup requests");
        for (java.util.Map.Entry<String, String> entry : owners.entrySet()) {
            ProjectExportPins.requireIdentity(entry.getKey(), "Export pin ID");
            ProjectExportPins.requireIdentity(entry.getValue(), "Export pin owner");
        }
        db.beginTransaction();
        try {
            for (java.util.Map.Entry<String, String> entry : owners.entrySet())
                pinnedExports.requireOwnerIfPresent(entry.getKey(), entry.getValue());
            int released = 0;
            for (java.util.Map.Entry<String, String> entry : owners.entrySet())
                if (pinnedExports.release(entry.getKey(), entry.getValue())) released++;
            db.setTransactionSuccessful();
            return released;
        } finally { db.endTransaction(); }
    }

    public synchronized Project create(String name) {
        Project p = new Project();
        p.id = UUID.randomUUID().toString();
        p.name = name == null || name.trim().isEmpty() ? "Untitled Project" : name.trim();
        ProjectTimeline.normalize(p);
        p.updatedAt = System.currentTimeMillis();
        p.revision = 1L;
        db.beginTransaction();
        try {
            writeProject(p);
            putMeta(META_ACTIVE, p.id);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return p;
    }

    public synchronized void setActive(String id) {
        if (id != null && !id.isEmpty() && get(id) == null) throw new IllegalArgumentException("Project not found");
        putMeta(META_ACTIVE, id == null ? "" : id);
    }

    @FunctionalInterface
    public interface ProjectMutation { void apply(Project project) throws Exception; }

    public static final class RevisionConflictException extends IllegalStateException {
        public final long expectedRevision;
        public final long actualRevision;
        public RevisionConflictException(long expected, long actual) {
            super("Project changed: expected revision " + expected + ", current revision " + actual);
            expectedRevision = expected; actualRevision = actual;
        }
    }

    /**
     * Read, mutate, validate, journal and autosave as one SQLite transaction.
     * ANY_REVISION reads the latest state inside the transaction; a supplied
     * revision protects commands based on an earlier owner/MCP snapshot.
     */
    public synchronized Project edit(String projectId, long expectedRevision, String label, ProjectMutation mutation) {
        if (mutation == null) throw new IllegalArgumentException("Project mutation is required");
        db.beginTransaction();
        try {
            Project before = requireProject(projectId, expectedRevision);
            Project next = copy(before);
            try { mutation.apply(next); }
            catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalArgumentException(error.getMessage(), error); }
            if (!before.id.equals(next.id)) throw new IllegalArgumentException("Project ID cannot change");
            commitEdit(before, next, label);
            db.setTransactionSuccessful();
            return next;
        } finally { db.endTransaction(); }
    }

    public Project timelineEdit(String projectId, long expectedRevision, String operation, JSONObject settings) {
        JSONObject options = settings == null ? new JSONObject() : settings;
        return edit(projectId, expectedRevision, operation, project -> ProjectTimeline.apply(project, operation, options));
    }

    /** Replacement must have been inspected off the UI thread; this operation performs no media I/O. */
    public Project relinkAsset(String projectId, long expectedRevision, String targetAssetId, Asset inspectedReplacement) {
        return relinkAsset(projectId, expectedRevision, targetAssetId, inspectedReplacement, true);
    }

    public Project relinkAsset(String projectId, long expectedRevision, String targetAssetId, Asset inspectedReplacement, boolean preserveName) {
        if (inspectedReplacement == null) throw new IllegalArgumentException("Inspected replacement media is required");
        final Asset replacement;
        try { replacement = Asset.fromJson(new JSONObject(inspectedReplacement.toJson().toString())); }
        catch (Exception error) { throw new IllegalArgumentException("Replacement metadata is invalid", error); }
        return edit(projectId, expectedRevision, "Relink source media",
                project -> ProjectAssetRelinking.apply(project, targetAssetId, replacement, preserveName));
    }

    /** Backward-compatible save, with stale-snapshot protection and undo history. */
    public synchronized void save(Project project) {
        if (project == null) return;
        if (project.id == null || project.id.isEmpty()) throw new IllegalArgumentException("Project ID is required");
        long originalRevision = project.revision;
        long originalUpdatedAt = project.updatedAt;
        db.beginTransaction();
        try {
            Project before = get(project.id);
            if (before == null) {
                if (project.revision != 0L) throw new RevisionConflictException(project.revision, -1L);
                ProjectTimeline.normalize(project);
                ProjectMarkers.reconcile(project);
                ProjectTimeline.validate(project);
                ProjectTimeline.validateRigChanges(null, project);
                project.revision = 1L;
                project.updatedAt = System.currentTimeMillis();
                writeProject(project);
            } else {
                if (project.revision != before.revision) throw new RevisionConflictException(project.revision, before.revision);
                ProjectTimeline.preserveLegacySequence(before, project);
                commitEdit(before, project, "Edit project");
            }
            db.setTransactionSuccessful();
        } catch (RuntimeException error) {
            project.revision = originalRevision; project.updatedAt = originalUpdatedAt;
            throw error;
        } finally { db.endTransaction(); }
    }

    public synchronized Project undo(String projectId, long expectedRevision) {
        return restoreHistory(projectId, expectedRevision, false);
    }

    public synchronized Project redo(String projectId, long expectedRevision) {
        return restoreHistory(projectId, expectedRevision, true);
    }

    public synchronized JSONObject historyState(String projectId) {
        JSONObject state = new JSONObject();
        int undo = 0, redo = 0;
        String undoLabel = "", redoLabel = "";
        try (Cursor c = db.query("project_history", new String[]{"applied", "label"},
                "project_id=?", new String[]{projectId == null ? "" : projectId}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                if (c.getInt(0) == 1) { undo++; undoLabel = c.getString(1); }
                else { if (redo == 0) redoLabel = c.getString(1); redo++; }
            }
        }
        try {
            state.put("canUndo", undo > 0); state.put("canRedo", redo > 0);
            state.put("undoCount", undo); state.put("redoCount", redo);
            state.put("undoLabel", undoLabel); state.put("redoLabel", redoLabel);
            state.put("maxEntries", MAX_HISTORY_ENTRIES); state.put("maxBytes", MAX_HISTORY_BYTES);
        } catch (Exception ignored) {}
        return state;
    }

    /** Named project checkpoints are durable and do not advance the editing revision. */
    public synchronized JSONObject saveSnapshot(String projectId, long expectedRevision, String name) {
        db.beginTransaction();
        try {
            Project project = requireProject(projectId, expectedRevision);
            String id = UUID.randomUUID().toString();
            String displayName = name == null || name.trim().isEmpty() ? "Snapshot" : name.trim();
            if (displayName.length() > 160) displayName = displayName.substring(0, 160);
            String snapshotJson = project.toJson().toString();
            if (snapshotJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_HISTORY_BYTES)
                throw new IllegalArgumentException("Project exceeds the snapshot storage budget");
            long createdAt = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("id", id); values.put("project_id", project.id); values.put("name", displayName);
            values.put("json", snapshotJson); values.put("revision", project.revision); values.put("created_at", createdAt);
            db.insertOrThrow("project_snapshots", null, values);
            pruneSnapshots(project.id);
            db.setTransactionSuccessful();
            return snapshotSummary(id, displayName, project.revision, createdAt);
        } finally { db.endTransaction(); }
    }

    public synchronized JSONArray listSnapshots(String projectId) {
        JSONArray snapshots = new JSONArray();
        try (Cursor c = db.query("project_snapshots", new String[]{"id", "name", "revision", "created_at"},
                "project_id=?", new String[]{projectId == null ? "" : projectId}, null, null, "created_at DESC, id DESC")) {
            while (c.moveToNext()) snapshots.put(snapshotSummary(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)));
        }
        return snapshots;
    }

    /** Restore is a new, undoable transaction; its revision remains monotonic. */
    public synchronized Project restoreSnapshot(String projectId, long expectedRevision, String snapshotId) {
        db.beginTransaction();
        try {
            Project before = requireProject(projectId, expectedRevision);
            Project saved;
            try (Cursor c = db.query("project_snapshots", new String[]{"json"}, "id=? AND project_id=?",
                    new String[]{snapshotId == null ? "" : snapshotId, projectId}, null, null, null, "1")) {
                if (!c.moveToFirst()) throw new IllegalArgumentException("Snapshot not found");
                try { saved = Project.fromJson(new JSONObject(c.getString(0))); }
                catch (Exception error) { throw new IllegalStateException("Snapshot could not be restored", error); }
            }
            // Like undo, an explicit checkpoint restore also restores lock state.
            commitEdit(before, saved, "Restore snapshot", false);
            db.setTransactionSuccessful();
            return saved;
        } finally { db.endTransaction(); }
    }

    public synchronized Project duplicateProject(String projectId, String name) {
        db.beginTransaction();
        try {
            Project original = requireProject(projectId, ANY_REVISION);
            Project duplicate = copy(original);
            duplicate.id = UUID.randomUUID().toString();
            duplicate.name = name == null || name.trim().isEmpty() ? original.name + " copy" : name.trim();
            duplicate.revision = 1L; duplicate.updatedAt = System.currentTimeMillis();
            duplicate.latestExportUri = ""; duplicate.latestExportName = ""; duplicate.latestExportAt = 0L;
            writeProject(duplicate);
            putMeta(META_ACTIVE, duplicate.id);
            db.setTransactionSuccessful();
            return duplicate;
        } finally { db.endTransaction(); }
    }

    private static JSONObject snapshotSummary(String id, String name, long revision, long createdAt) {
        JSONObject summary = new JSONObject();
        try { summary.put("id", id); summary.put("name", name); summary.put("revision", revision); summary.put("createdAt", createdAt); }
        catch (Exception ignored) {}
        return summary;
    }

    private void pruneSnapshots(String projectId) {
        ArrayList<String> ids = new ArrayList<>(); ArrayList<Long> sizes = new ArrayList<>();
        long total = 0L;
        try (Cursor c = db.rawQuery("SELECT id, length(CAST(json AS BLOB)) FROM project_snapshots " +
                "WHERE project_id=? ORDER BY created_at ASC, rowid ASC", new String[]{projectId})) {
            while (c.moveToNext()) { ids.add(c.getString(0)); sizes.add(c.getLong(1)); total += c.getLong(1); }
        }
        for (int i = 0; i < ids.size() && (ids.size() - i > 16 || total > MAX_HISTORY_BYTES); i++) {
            db.delete("project_snapshots", "id=?", new String[]{ids.get(i)}); total -= sizes.get(i);
        }
    }

    public synchronized void delete(String id) {
        if (id == null || id.isEmpty()) return;
        db.beginTransaction();
        try {
            db.delete("project_history", "project_id=?", new String[]{id});
            db.delete("project_snapshots", "project_id=?", new String[]{id});
            db.delete("projects", "id=?", new String[]{id});
            if (id.equals(getMeta(META_ACTIVE))) {
                List<Project> all = list();
                putMeta(META_ACTIVE, all.isEmpty() ? "" : all.get(0).id);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private Project requireProject(String id, long expectedRevision) {
        Project project = get(id);
        if (project == null) throw new IllegalArgumentException("Project not found");
        if (expectedRevision != ANY_REVISION && expectedRevision != project.revision)
            throw new RevisionConflictException(expectedRevision, project.revision);
        return project;
    }

    private void commitEdit(Project before, Project next, String label) {
        commitEdit(before, next, label, true);
    }

    private void commitEdit(Project before, Project next, String label, boolean enforceLocks) {
        ProjectTimeline.normalize(next);
        ProjectMarkers.reconcile(next);
        for (Clip clip : next.clips) if (Boolean.FALSE.equals(clip.effects.opt("audioDetached")))
            clip.effects.remove("audioExtractionDetached");
        for (Clip previous : before.clips) if (previous.effects.optBoolean("audioExtractionDetached", false)) {
            Clip current = next.clip(previous.id);
            if (current != null && !current.effects.optBoolean("audioExtractionDetached", false)
                    && !Boolean.FALSE.equals(current.effects.opt("audioDetached")))
                throw new IllegalArgumentException("Extraction ownership must be retained until embedded audio is explicitly restored");
        }
        ProjectTimeline.validate(next);
        ProjectTimeline.validateRigChanges(before, next);
        if (enforceLocks) ProjectTimeline.validateLocks(before, next);
        // A no-op neither advances the revision nor destroys a redo branch.
        next.revision = before.revision;
        next.updatedAt = before.updatedAt;
        if (before.toJson().toString().equals(next.toJson().toString())) return;
        next.revision = nextRevision(before.revision);
        next.updatedAt = System.currentTimeMillis();
        db.delete("project_history", "project_id=? AND applied=0", new String[]{before.id});
        ContentValues history = new ContentValues();
        history.put("project_id", before.id);
        history.put("before_json", before.toJson().toString());
        history.put("after_json", next.toJson().toString());
        String value = label == null || label.trim().isEmpty() ? "Edit project" : label.trim();
        history.put("label", value.length() > 160 ? value.substring(0, 160) : value);
        history.put("created_at", next.updatedAt);
        history.put("applied", 1);
        if (db.insertOrThrow("project_history", null, history) < 0) throw new IllegalStateException("Could not save undo history");
        writeProject(next);
        pruneHistory(before.id);
    }

    private Project restoreHistory(String id, long expectedRevision, boolean redo) {
        db.beginTransaction();
        try {
            Project current = requireProject(id, expectedRevision);
            long historyId = -1L;
            String snapshot = null;
            try (Cursor c = db.query("project_history", new String[]{"id", redo ? "after_json" : "before_json"},
                    "project_id=? AND applied=?", new String[]{id, redo ? "0" : "1"}, null, null,
                    redo ? "id ASC" : "id DESC", "1")) {
                if (c.moveToFirst()) { historyId = c.getLong(0); snapshot = c.getString(1); }
            }
            if (snapshot == null) { db.setTransactionSuccessful(); return current; }
            Project restored;
            try { restored = Project.fromJson(new JSONObject(snapshot)); }
            catch (Exception error) { throw new IllegalStateException("Saved history could not be restored", error); }
            ProjectMarkers.reconcile(restored);
            ProjectTimeline.validate(restored);
            restored.revision = nextRevision(current.revision);
            restored.updatedAt = System.currentTimeMillis();
            writeProject(restored);
            ContentValues values = new ContentValues(); values.put("applied", redo ? 1 : 0);
            db.update("project_history", values, "id=?", new String[]{Long.toString(historyId)});
            db.setTransactionSuccessful();
            return restored;
        } finally { db.endTransaction(); }
    }

    private void pruneHistory(String id) {
        ArrayList<Long> ids = new ArrayList<>();
        ArrayList<Long> sizes = new ArrayList<>();
        long total = 0L;
        try (Cursor c = db.rawQuery("SELECT id, length(CAST(before_json AS BLOB))+length(CAST(after_json AS BLOB)) " +
                "FROM project_history WHERE project_id=? ORDER BY id ASC", new String[]{id})) {
            while (c.moveToNext()) { ids.add(c.getLong(0)); sizes.add(c.getLong(1)); total += c.getLong(1); }
        }
        for (int i = 0; i < ids.size() && (ids.size() - i > MAX_HISTORY_ENTRIES || total > MAX_HISTORY_BYTES); i++) {
            db.delete("project_history", "id=?", new String[]{Long.toString(ids.get(i))});
            total -= sizes.get(i);
        }
    }

    private void writeProject(Project project) {
        String json = project.toJson().toString();
        if (json.length() > MAX_HISTORY_BYTES
                || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_HISTORY_BYTES)
            throw new IllegalArgumentException("Project exceeds the 16 MiB metadata storage budget");
        ContentValues values = new ContentValues();
        values.put("id", project.id); values.put("json", json);
        values.put("updated_at", project.updatedAt);
        if (db.insertWithOnConflict("projects", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0L)
            throw new IllegalStateException("Could not persist project");
    }

    private static long nextRevision(long revision) {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Project revision is exhausted");
        return revision + 1L;
    }

    public static Project copy(Project project) {
        if (project == null) return null;
        try { return Project.fromJson(new JSONObject(project.toJson().toString())); }
        catch (Exception error) { throw new IllegalArgumentException("Project could not be copied", error); }
    }

    public Asset importUri(Project project, Uri uri) {
        if (project == null) throw new IllegalArgumentException("Project is required");
        Asset a = inspectUri(uri);
        Project updated = edit(project.id, ANY_REVISION, "Import media", latest -> {
            latest.assets.add(a);
            if (isTimelineMedia(a) && canAutoAppend(latest, a)) ProjectTimeline.appendAsset(latest, a);
        });
        adopt(project, updated);
        return project.asset(a.id);
    }

    /** Nonmutating exact-URI inspection. Blocking provider/media reads belong on the owner worker. */
    public Asset inspectUri(Uri uri) {
        if (uri == null || !("file".equals(uri.getScheme()) || "content".equals(uri.getScheme())))
            throw new IllegalArgumentException("Select a readable local file or document URI");
        AssetProbe.Result probe = AssetProbe.probe(resolver, uri);
        if (!probe.readable) throw new IllegalArgumentException("Selected media cannot be read");
        Asset asset = new Asset();
        asset.id = UUID.randomUUID().toString(); asset.uri = uri.toString(); asset.name = probe.displayName;
        asset.sizeBytes = probe.sizeBytes; asset.seekable = probe.seekable; asset.persistedReadAccess = probe.persistedReadAccess;
        asset.providerAuthority = probe.providerAuthority; asset.role = "source"; asset.generated = false;
        try {
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (asset.name == null || asset.name.equals("Media")) asset.name = file.getName();
                MediaImportInspector.Metadata metadata = MediaImportInspector.inspect(file, probe.mime, asset.name);
                asset.mime = metadata.mime; asset.durationMs = metadata.durationMs; asset.width = metadata.width;
                asset.height = metadata.height; asset.rotation = metadata.rotation; asset.hasAudio = metadata.hasAudio; asset.sizeBytes = metadata.sizeBytes;
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream stream = resolver.openInputStream(uri)) {
                    if (stream == null) throw new IllegalArgumentException("Selected media cannot be opened");
                    try { BitmapFactory.decodeStream(stream, null, bounds); }
                    catch (RuntimeException notAnImage) { bounds.outWidth = -1; bounds.outHeight = -1; }
                }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    if ((long) bounds.outWidth * bounds.outHeight > MediaImportInspector.MAX_IMAGE_PIXELS)
                        throw new IllegalArgumentException("Selected image exceeds the 80 million pixel limit");
                    BitmapFactory.Options sampleOptions = new BitmapFactory.Options(); sampleOptions.inSampleSize = 1; sampleOptions.inScaled = false;
                    sampleOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    while (((long) bounds.outWidth + sampleOptions.inSampleSize - 1) / sampleOptions.inSampleSize > 512L
                            || ((long) bounds.outHeight + sampleOptions.inSampleSize - 1) / sampleOptions.inSampleSize > 512L) sampleOptions.inSampleSize *= 2;
                    Bitmap sample = null;
                    try (InputStream stream = resolver.openInputStream(uri)) {
                        if (stream == null) throw new IllegalArgumentException("Selected image cannot be reopened");
                        sample = BitmapFactory.decodeStream(stream, null, sampleOptions);
                        if (sample == null || sample.getWidth() > 512 || sample.getHeight() > 512)
                            throw new IllegalArgumentException("Selected image has no readable bounded pixel sample");
                    } finally { if (sample != null) sample.recycle(); }
                    asset.mime = mediaMime("image", bounds.outMimeType, probe.mime, MediaImportInspector.nameMime(asset.name));
                    asset.width = bounds.outWidth; asset.height = bounds.outHeight; asset.hasAudio = false;
                } else inspectUriAv(uri, probe, asset);
            }
            if (asset.mime != null && asset.mime.startsWith("image/")) {
                try (InputStream stream = resolver.openInputStream(uri)) {
                    if (stream != null) {
                        int orientation = new android.media.ExifInterface(stream).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                                android.media.ExifInterface.ORIENTATION_NORMAL);
                        asset.importMetadata.put("exifOrientation", orientation);
                        asset.rotation = orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90
                                || orientation == android.media.ExifInterface.ORIENTATION_TRANSPOSE ? 90
                                : orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270
                                || orientation == android.media.ExifInterface.ORIENTATION_TRANSVERSE ? 270
                                : orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180 : 0;
                    }
                } catch (java.io.IOException unsupportedExif) { /* Images without readable EXIF retain their inspected orientation. */ }
            }
            asset.importMetadata.put("sourceUri", asset.uri); asset.importMetadata.put("importedAt", asset.createdAt);
            asset.importMetadata.put("inspection", "exact_uri_media_metadata"); asset.importMetadata.put("readable", true);
            return asset;
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Selected media could not be inspected: " + error.getMessage(), error); }
    }

    private void inspectUriAv(Uri uri, AssetProbe.Result probe, Asset asset) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IllegalArgumentException("Selected media cannot be opened");
            long offset = descriptor.getStartOffset(), length = descriptor.getLength();
            if (length < 0L && offset > 0L) {
                long statSize = descriptor.getParcelFileDescriptor().getStatSize();
                if (statSize <= offset) throw new IllegalArgumentException("Selected media has an unreadable descriptor range");
                length = statSize - offset;
            }
            if (length >= 0L) retriever.setDataSource(descriptor.getFileDescriptor(), offset, length);
            else retriever.setDataSource(descriptor.getFileDescriptor());
            String detectedMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            asset.durationMs = metadataLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            asset.width = metadataInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            asset.height = metadataInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            boolean video = metadataYes(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)) || asset.width > 0 && asset.height > 0;
            asset.hasAudio = metadataYes(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            if (!video && MediaImportInspector.normalizeMime(detectedMime).startsWith("audio/") && asset.durationMs > 0L) asset.hasAudio = true;
            if ((!video && !asset.hasAudio) || asset.durationMs <= 0L) throw new IllegalArgumentException("Selected source is not readable audio or video with a positive duration");
            if (video && (asset.width <= 0 || asset.height <= 0)) throw new IllegalArgumentException("Selected video has no readable dimensions");
            asset.mime = mediaMime(video ? "video" : "audio", detectedMime, probe.mime, MediaImportInspector.nameMime(asset.name));
            asset.rotation = video ? metadataRotation(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)) : 0;
            if (!video) { asset.width = 0; asset.height = 0; }
        } finally { try { retriever.release(); } catch (Exception ignored) {} }
    }

    private static String mediaMime(String kind, String... candidates) {
        for (String candidate : candidates) {
            String mime = MediaImportInspector.normalizeMime(candidate);
            if (mime.startsWith(kind + "/")) return mime;
            if (kind.equals("audio") && mime.startsWith("video/")) return "audio/" + mime.substring(6);
            if (mime.equals("application/ogg")) return kind + "/ogg";
        }
        return kind + "/octet-stream";
    }
    private static long metadataLong(String value) { try { return Math.max(0L, Long.parseLong(value)); } catch (Exception ignored) { return 0L; } }
    private static int metadataInt(String value) { long number = metadataLong(value); return number <= Integer.MAX_VALUE ? (int) number : 0; }
    private static int metadataRotation(String value) { try { return Math.floorMod(Integer.parseInt(value), 360); } catch (Exception ignored) { return 0; } }
    private static boolean metadataYes(String value) { return "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value); }

    /** Register generated media against the latest graph without overwriting concurrent owner edits. */
    public synchronized Asset registerGeneratedAsset(Project project, Uri uri, String name, String role, boolean appendToTimeline) {
        if (project == null) throw new IllegalArgumentException("Project is required");
        if (uri == null) throw new IllegalArgumentException("Generated asset URI is required");
        Asset asset = new Asset();
        asset.id = UUID.randomUUID().toString(); asset.uri = uri.toString();
        asset.name = name == null || name.trim().isEmpty() ? "Generated media" : name.trim();
        AssetProbe.Result probe = AssetProbe.probe(resolver, uri);
        asset.mime = probe.mime;
        if (asset.mime == null || asset.mime.isEmpty() || "application/octet-stream".equals(asset.mime)) {
            String lower = asset.name.toLowerCase(java.util.Locale.US);
            asset.mime = lower.endsWith(".mp4") ? "video/mp4"
                    : lower.endsWith(".png") ? "image/png"
                    : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
                    : lower.endsWith(".wav") ? "audio/wav" : lower.endsWith(".mp3") ? "audio/mpeg"
                    : lower.endsWith(".m4a") ? "audio/mp4" : lower.endsWith(".aac") ? "audio/aac" : "application/octet-stream";
        }
        asset.sizeBytes = probe.sizeBytes; asset.seekable = probe.seekable;
        asset.persistedReadAccess = probe.persistedReadAccess; asset.providerAuthority = probe.providerAuthority;
        asset.durationMs = duration(uri); asset.hasAudio = asset.mime.startsWith("audio/") || asset.mime.startsWith("video/");
        asset.role = role == null || role.trim().isEmpty() ? "generated" : role.trim();
        asset.generated = true; asset.createdAt = System.currentTimeMillis();
        final String[] registeredId = {asset.id};
        final String exportUri = project.latestExportUri;
        final String exportName = project.latestExportName;
        final long exportAt = project.latestExportAt;
        Project updated = edit(project.id, ANY_REVISION, "Register generated media", latest -> {
            if (exportUri != null && !exportUri.isEmpty() && exportAt > latest.latestExportAt) {
                latest.latestExportUri = exportUri; latest.latestExportName = exportName; latest.latestExportAt = exportAt;
            }
            for (Asset existing : latest.assets) if (asset.uri.equals(existing.uri)) {
                existing.generated = true; existing.role = asset.role;
                if (name != null && !name.trim().isEmpty()) existing.name = name.trim();
                registeredId[0] = existing.id;
                return;
            }
            latest.assets.add(asset);
            if (appendToTimeline && isTimelineMedia(asset) && canAutoAppend(latest, asset)) ProjectTimeline.appendAsset(latest, asset);
        });
        adopt(project, updated);
        return project.asset(registeredId[0]);
    }

    public synchronized boolean appendAssetToTimeline(Project project, String assetId) {
        if (project == null || assetId == null || assetId.isEmpty()) return false;
        Project current = get(project.id);
        Asset asset = current == null ? null : current.asset(assetId);
        if (!isTimelineMedia(asset)) return false;
        JSONObject settings = new JSONObject();
        try { settings.put("assetId", assetId); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
        Project updated = timelineEdit(project.id, ANY_REVISION, "append", settings);
        adopt(project, updated);
        return true;
    }

    private static boolean canAutoAppend(Project project, Asset asset) {
        String type = asset.mime != null && asset.mime.startsWith("audio/") ? "audio" : "video";
        return !project.defaultTrack(type).locked;
    }

    private static boolean isTimelineMedia(Asset asset) {
        return asset != null && asset.mime != null && (asset.mime.startsWith("video/")
                || asset.mime.startsWith("image/") || asset.mime.startsWith("audio/"));
    }

    private static void adopt(Project target, Project updated) {
        target.id = updated.id; target.name = updated.name; target.updatedAt = updated.updatedAt; target.revision = updated.revision;
        target.sourcePrompt = updated.sourcePrompt; target.latestExportUri = updated.latestExportUri;
        target.latestExportName = updated.latestExportName; target.latestExportAt = updated.latestExportAt;
        target.assets.clear(); target.assets.addAll(updated.assets);
        target.clips.clear(); target.clips.addAll(updated.clips);
        target.tracks.clear(); target.tracks.addAll(updated.tracks);
        target.markers = updated.markers;
        target.editorRange = updated.editorRange;
        target.animationFrameRate = updated.animationFrameRate;
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
                o.put("trackCount", p.tracks.size());
                o.put("revision", p.revision);
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
        if (db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0L)
            throw new IllegalStateException("Could not persist project metadata");
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

