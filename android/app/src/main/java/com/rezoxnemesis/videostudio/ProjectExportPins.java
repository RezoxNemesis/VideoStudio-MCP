package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * App-private, immutable export graphs, independent of undo and named snapshots.
 * ProjectStore holds a database transaction across every multi-query operation.
 * Small BLOB rows avoid CursorWindow limits when reopening a large graph.
 */
final class ProjectExportPins {
    static final int MAX_PINS = 24;
    private static final int CHUNK_BYTES = 128 * 1024;
    private static final int MAX_GRAPH_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final String TABLE = "export_pins";
    private static final String CHUNKS = "export_pin_chunks";
    private final SQLiteDatabase db;

    ProjectExportPins(SQLiteDatabase db) { this.db = db; }

    static void createSchema(SQLiteDatabase db) {
        // Deliberately no foreign key to projects: an admitted export survives
        // project deletion until its own durable request explicitly terminates.
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "pin_id TEXT PRIMARY KEY NOT NULL, owner_id TEXT NOT NULL," +
                "project_id TEXT NOT NULL, revision INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL, json_bytes INTEGER NOT NULL," +
                "chunk_count INTEGER NOT NULL, sha256 TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + CHUNKS + " (" +
                "pin_id TEXT NOT NULL, ordinal INTEGER NOT NULL, payload BLOB NOT NULL," +
                "PRIMARY KEY(pin_id, ordinal))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_export_pins_owner ON " + TABLE + "(owner_id)");
    }

    static void requireIdentity(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > 256)
            throw new IllegalArgumentException(name + " must contain 1 to 256 characters");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character))
                throw new IllegalArgumentException(name + " cannot contain whitespace or control characters");
        }
    }

    ProjectStore.Project capture(ProjectStore.Project project, String pinId, String ownerId) {
        String json = project.toJson().toString();
        if (json.length() > MAX_GRAPH_BYTES) throw new IllegalArgumentException("Export graph exceeds the 16 MiB metadata limit");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_GRAPH_BYTES)
            throw new IllegalArgumentException("Export graph exceeds the 16 MiB metadata limit");
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(json_bytes),0) FROM " + TABLE, null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("Export pin capacity cannot be read");
            long count = cursor.getLong(0), total = cursor.getLong(1);
            if (count < 0L || total < 0L || count > MAX_PINS || total > MAX_TOTAL_BYTES)
                throw new IllegalStateException("Export pin ledger is damaged; retain admitted exports and reconcile their jobs");
            if (count >= MAX_PINS || total + bytes.length > MAX_TOTAL_BYTES)
                throw new IllegalStateException("Export pin capacity is full; finish or cancel admitted exports before queuing another");
        }
        int chunks = chunkCount(bytes.length);
        ContentValues metadata = new ContentValues();
        metadata.put("pin_id", pinId); metadata.put("owner_id", ownerId);
        metadata.put("project_id", project.id); metadata.put("revision", project.revision);
        metadata.put("created_at", System.currentTimeMillis()); metadata.put("json_bytes", bytes.length);
        metadata.put("chunk_count", chunks); metadata.put("sha256", sha256(bytes));
        db.insertOrThrow(TABLE, null, metadata);
        for (int index = 0; index < chunks; index++) {
            int offset = index * CHUNK_BYTES;
            ContentValues chunk = new ContentValues();
            chunk.put("pin_id", pinId); chunk.put("ordinal", index);
            chunk.put("payload", Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + CHUNK_BYTES)));
            db.insertOrThrow(CHUNKS, null, chunk);
        }
        return decode(json, project.id, project.revision);
    }

    ProjectStore.Project read(String pinId, String ownerId) {
        Pin pin = pin(pinId);
        if (pin == null) return null;
        if (!ownerId.equals(pin.ownerId)) throw new IllegalStateException("Export pin ownership does not match this request");
        validateMetadata(pin);
        byte[] bytes = new byte[(int) pin.bytes];
        int count = 0, offset = 0;
        try (Cursor cursor = db.query(CHUNKS, new String[]{"ordinal", "payload"},
                "pin_id=?", new String[]{pinId}, null, null, "ordinal ASC")) {
            while (cursor.moveToNext()) {
                if (count >= pin.chunks || cursor.getLong(0) != count)
                    throw damaged("Export graph chunks are missing, duplicated or out of order", null);
                byte[] chunk = cursor.getBlob(1);
                int expected = Math.min(CHUNK_BYTES, bytes.length - offset);
                if (chunk == null || chunk.length != expected)
                    throw damaged("Export graph chunk length does not match its receipt", null);
                System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                offset += chunk.length; count++;
            }
        }
        if (count != pin.chunks || offset != bytes.length || !pin.sha256.equals(sha256(bytes)))
            throw damaged("Export graph checksum or length does not match its receipt", null);
        return decode(new String(bytes, StandardCharsets.UTF_8), pin.projectId, pin.revision);
    }

    /** Read the accepted live graph without putting a multi-megabyte row in CursorWindow. */
    ProjectStore.Project readLiveProject(String projectId) {
        long length;
        try (Cursor cursor = db.rawQuery("SELECT length(CAST(json AS BLOB)) FROM projects WHERE id=?", new String[]{projectId})) {
            if (!cursor.moveToFirst()) return null;
            length = cursor.getLong(0);
        }
        if (length <= 0L || length > MAX_GRAPH_BYTES)
            throw new IllegalArgumentException("Export graph exceeds the 16 MiB metadata limit or is unreadable");
        byte[] bytes = new byte[(int) length];
        for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
            int expected = Math.min(CHUNK_BYTES, bytes.length - offset);
            try (Cursor cursor = db.rawQuery("SELECT substr(CAST(json AS BLOB),?,?) FROM projects WHERE id=?",
                    new String[]{Integer.toString(offset + 1), Integer.toString(expected), projectId})) {
                if (!cursor.moveToFirst()) throw damaged("Accepted project disappeared during export admission", null);
                byte[] chunk = cursor.getBlob(0);
                if (chunk == null || chunk.length != expected) throw damaged("Accepted project graph cannot be read", null);
                System.arraycopy(chunk, 0, bytes, offset, expected);
            }
        }
        try {
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            ProjectStore.Project project = ProjectStore.Project.fromJson(json);
            if (!projectId.equals(project.id)) throw damaged("Accepted project identity does not match its database row", null);
            return project;
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw damaged("Accepted project graph cannot be decoded", error); }
    }

    boolean release(String pinId, String ownerId) {
        if (!requireOwnerIfPresent(pinId, ownerId)) return false;
        // Delete every chunk, including any unexpected extra, only after exact
        // ownership validation. The outer transaction covers both deletions.
        db.delete(CHUNKS, "pin_id=?", new String[]{pinId});
        if (db.delete(TABLE, "pin_id=? AND owner_id=?", new String[]{pinId, ownerId}) != 1)
            throw new IllegalStateException("Export pin ownership changed during release");
        return true;
    }

    boolean requireOwnerIfPresent(String pinId, String ownerId) {
        try (Cursor cursor = db.query(TABLE, new String[]{"owner_id"}, "pin_id=?", new String[]{pinId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return false;
            if (!ownerId.equals(cursor.getString(0))) throw new IllegalStateException("Export pin ownership does not match this request");
            return true;
        }
    }

    JSONArray metadata() {
        JSONArray result = new JSONArray();
        try (Cursor cursor = db.query(TABLE,
                new String[]{"pin_id", "owner_id", "project_id", "revision", "created_at", "json_bytes", "sha256"},
                null, null, null, null, "created_at ASC, pin_id ASC")) {
            while (cursor.moveToNext()) {
                if (result.length() >= MAX_PINS) throw damaged("Export pin ledger exceeds its admitted capacity", null);
                JSONObject item = new JSONObject();
                try {
                    item.put("pinId", cursor.getString(0)); item.put("ownerId", cursor.getString(1));
                    item.put("projectId", cursor.getString(2)); item.put("revision", cursor.getLong(3));
                    item.put("createdAt", cursor.getLong(4)); item.put("bytes", cursor.getLong(5)); item.put("sha256", cursor.getString(6));
                } catch (Exception error) { throw damaged("Export pin metadata cannot be listed", error); }
                result.put(item);
            }
        }
        return result;
    }

    boolean referencesMediaUri(String uri) {
        try {
            ArrayList<String[]> identities = new ArrayList<>();
            try (Cursor cursor = db.query(TABLE, new String[]{"pin_id", "owner_id"}, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    if (identities.size() >= MAX_PINS) return true;
                    identities.add(new String[]{cursor.getString(0), cursor.getString(1)});
                }
            }
            for (String[] identity : identities) {
                ProjectStore.Project graph = read(identity[0], identity[1]);
                if (graph == null) return true;
                if (ProjectMediaReferences.references(graph.toJson(), uri)) return true;
            }
            return false;
        } catch (Exception damagedRecord) {
            // A damaged retained graph cannot prove that deleting a file is safe.
            return true;
        }
    }

    private Pin pin(String pinId) {
        try (Cursor cursor = db.query(TABLE,
                new String[]{"owner_id", "project_id", "revision", "created_at", "json_bytes", "chunk_count", "sha256"},
                "pin_id=?", new String[]{pinId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            Pin pin = new Pin(); pin.ownerId = cursor.getString(0); pin.projectId = cursor.getString(1);
            pin.revision = cursor.getLong(2); pin.createdAt = cursor.getLong(3); pin.bytes = cursor.getLong(4);
            long chunkCount = cursor.getLong(5);
            pin.chunks = chunkCount > 0L && chunkCount <= MAX_GRAPH_BYTES / CHUNK_BYTES ? (int) chunkCount : -1;
            pin.sha256 = cursor.getString(6);
            return pin;
        }
    }

    private static void validateMetadata(Pin pin) {
        try { requireIdentity(pin.ownerId, "Export pin owner"); requireIdentity(pin.projectId, "Project ID"); }
        catch (IllegalArgumentException error) { throw damaged("Export pin identity is damaged", error); }
        if (pin.revision < 0L || pin.createdAt <= 0L || pin.bytes <= 0L || pin.bytes > MAX_GRAPH_BYTES
                || pin.chunks != chunkCount((int) pin.bytes) || pin.sha256 == null || !pin.sha256.matches("[0-9a-f]{64}"))
            throw damaged("Export pin receipt is damaged", null);
    }

    private static ProjectStore.Project decode(String json, String projectId, long revision) {
        try {
            JSONObject graph = new JSONObject(json);
            if (!projectId.equals(graph.getString("id")) || graph.getLong("revision") != revision
                    || !(graph.get("assets") instanceof JSONArray) || !(graph.get("clips") instanceof JSONArray)
                    || !(graph.get("tracks") instanceof JSONArray))
                throw damaged("Export graph identity or structure does not match its receipt", null);
            ProjectStore.Project project = ProjectStore.Project.fromJson(graph);
            if (!projectId.equals(project.id) || project.revision != revision)
                throw damaged("Export graph identity does not match its receipt", null);
            ProjectTimeline.validate(project);
            return project;
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw damaged("Export graph cannot be decoded or validated", error); }
    }

    private static int chunkCount(int bytes) { return (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES; }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int index = 0; index < digest.length; index++) {
                int value = digest[index] & 0xff;
                hex[index * 2] = digits[value >>> 4]; hex[index * 2 + 1] = digits[value & 0xf];
            }
            return new String(hex);
        } catch (Exception error) { throw new IllegalStateException("Export graph checksum cannot be computed", error); }
    }

    private static IllegalStateException damaged(String message, Throwable cause) {
        return new IllegalStateException(message + "; retain the export pin for recovery", cause);
    }

    private static final class Pin {
        String ownerId, projectId, sha256;
        long revision, createdAt, bytes;
        int chunks;
    }
}
