package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/** DB-first ownership admission for app-private source archive journals/catalogs. */
final class ProjectSourceArchiveReferences {
    static final String TABLE = "source_archive_reference_records";
    static final String STATE = "source_archive_reference_state";
    private static final int MAX_RECORDS = 560;
    private static final int MAX_BYTES = 256 * 1024;
    private static final int CHUNK_BYTES = 128 * 1024;
    private static final int MAX_URIS = 4096;
    private final SQLiteDatabase db;

    ProjectSourceArchiveReferences(SQLiteDatabase db) { this.db = db; }

    static void createSchema(SQLiteDatabase db, File appFilesRoot) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id TEXT PRIMARY KEY NOT NULL,owner_id TEXT NOT NULL," +
                "json TEXT NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + STATE + " (" +
                "id INTEGER PRIMARY KEY CHECK(id=1),initialized INTEGER NOT NULL CHECK(initialized IN (0,1)))");
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM " + STATE + " WHERE id=1", null)) {
            if (cursor.moveToFirst()) return;
        }
        // Existing vault files predate this ownership ledger and require an
        // explicit bounded bootstrap. Until then cleanup protects every file.
        ContentValues state = new ContentValues(); state.put("id", 1);
        state.put("initialized", emptyMetadata(appFilesRoot) ? 1 : 0);
        db.insertWithOnConflict(STATE, null, state, SQLiteDatabase.CONFLICT_IGNORE);
    }

    static boolean initialized(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT initialized FROM " + STATE + " WHERE id=1", null)) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1 && !cursor.moveToNext();
        }
    }

    void markInitialized() {
        ContentValues values = new ContentValues(); values.put("initialized", 1);
        if (db.update(STATE, values, "id=1", null) != 1)
            throw new IllegalStateException("Source archive ownership initialization cannot be committed");
    }

    void record(String recordId, String ownerId, JSONObject evidence) {
        ProjectExportPins.requireIdentity(recordId, "Source archive reference record");
        ProjectExportPins.requireIdentity(ownerId, "Source archive reference owner");
        if (evidence == null) throw new IllegalArgumentException("Source archive reference evidence is required");
        String evidenceJson = evidence.toString(); requireBytes(evidenceJson);
        String previous = null;
        try (Cursor cursor = db.rawQuery("SELECT owner_id,length(CAST(json AS BLOB)) FROM " + TABLE + " WHERE id=?", new String[]{recordId})) {
            if (cursor.moveToFirst()) {
                if (!ownerId.equals(cursor.getString(0)))
                    throw new IllegalStateException("Source archive metadata ownership changed");
                long bytes = cursor.getLong(1);
                previous = readJson(recordId, bytes);
                if (cursor.moveToNext()) throw new IllegalStateException("Source archive ownership identity is duplicated");
            }
        }
        if (previous == null) {
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
                if (!cursor.moveToFirst() || cursor.getLong(0) < 0L || cursor.getLong(0) >= MAX_RECORDS)
                    throw new IllegalStateException("Source archive ownership ledger capacity is full");
            }
        }
        try {
            HashSet<String> retained = new HashSet<>();
            ProjectMediaReferences.UriCollector collector = uri -> {
                if (uri.length() > 16384 || retained.size() >= MAX_URIS && !retained.contains(uri))
                    throw new IllegalStateException("Source archive retained URI capacity is full");
                retained.add(uri);
            };
            if (previous != null) ProjectMediaReferences.collectValueUris(new JSONObject(previous), collector);
            ProjectMediaReferences.collectValueUris(evidence, collector);
            ArrayList<String> ordered = new ArrayList<>(retained); Collections.sort(ordered);
            JSONArray uris = new JSONArray(); for (String uri : ordered) uris.put(uri);
            // Retain previous exact URI evidence even if a DB-first filesystem
            // update later fails. No stale catalog file can lose its protection.
            JSONObject wrapped = new JSONObject().put("evidence", new JSONObject(evidenceJson)).put("retainedUris", uris);
            String json = wrapped.toString(); requireBytes(json);
            ContentValues values = new ContentValues(); values.put("id", recordId); values.put("owner_id", ownerId);
            values.put("json", json); values.put("updated_at", System.currentTimeMillis());
            if (previous == null) db.insertOrThrow(TABLE, null, values);
            else if (db.update(TABLE, values, "id=? AND owner_id=?", new String[]{recordId, ownerId}) != 1)
                throw new IllegalStateException("Source archive metadata ownership changed during admission");
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Source archive reference evidence cannot be retained", error); }
    }

    boolean remove(String recordId, String ownerId) {
        ProjectExportPins.requireIdentity(recordId, "Source archive reference record");
        ProjectExportPins.requireIdentity(ownerId, "Source archive reference owner");
        try (Cursor cursor = db.rawQuery("SELECT owner_id FROM " + TABLE + " WHERE id=?", new String[]{recordId})) {
            if (!cursor.moveToFirst()) return false;
            if (!ownerId.equals(cursor.getString(0))) throw new IllegalStateException("Source archive metadata ownership changed");
            if (cursor.moveToNext()) throw new IllegalStateException("Source archive ownership identity is duplicated");
        }
        if (db.delete(TABLE, "id=? AND owner_id=?", new String[]{recordId, ownerId}) != 1)
            throw new IllegalStateException("Source archive metadata ownership changed during release");
        return true;
    }

    private String readJson(String id, long bytes) {
        if (bytes <= 0L || bytes > MAX_BYTES) throw new IllegalStateException("Source archive ownership metadata exceeds its bound");
        byte[] value = new byte[(int) bytes];
        for (int offset = 0; offset < value.length; offset += CHUNK_BYTES) {
            int expected = Math.min(CHUNK_BYTES, value.length - offset);
            try (Cursor cursor = db.rawQuery("SELECT substr(CAST(json AS BLOB),?,?) FROM " + TABLE + " WHERE id=?",
                    new String[]{Integer.toString(offset + 1), Integer.toString(expected), id})) {
                if (!cursor.moveToFirst()) throw new IllegalStateException("Source archive ownership metadata disappeared");
                byte[] chunk = cursor.getBlob(0);
                if (chunk == null || chunk.length != expected || cursor.moveToNext())
                    throw new IllegalStateException("Source archive ownership metadata cannot be proven complete");
                System.arraycopy(chunk, 0, value, offset, expected);
            }
        }
        try {
            return ProjectMediaReferenceIndex.decodeOwnershipMetadata(value).toString();
        } catch (Exception unreadable) { throw new IllegalStateException("Source archive ownership metadata is unreadable", unreadable); }
    }

    private static void requireBytes(String value) {
        if (value.length() > MAX_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Source archive ownership metadata exceeds 256 KiB");
    }

    private static boolean emptyMetadata(File appFilesRoot) {
        try {
            File base = new File(appFilesRoot, "source_media_vault").getAbsoluteFile();
            if (!base.getCanonicalFile().equals(base)) return false;
            if (base.exists() && !base.isDirectory()) return false;
            for (String name : new String[]{"journals", "catalogs", "restore_journals"}) {
                File directory = new File(base, name);
                if (!directory.exists()) continue;
                if (!directory.isDirectory() || !directory.getCanonicalFile().equals(directory)) return false;
                File[] files = directory.listFiles(); if (files == null || files.length > 1024) return false;
                // Staging files and unfamiliar entries are retained evidence
                // too. Only a truly empty directory proves no bootstrap work.
                if (files.length != 0) return false;
            }
            return true;
        } catch (Exception unknownMetadata) { return false; }
    }
}
