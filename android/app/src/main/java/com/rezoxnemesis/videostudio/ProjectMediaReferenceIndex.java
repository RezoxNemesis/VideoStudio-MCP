package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Persistent exact-URI ownership cache. SQLite triggers invalidate the cache in
 * the same transaction as every protected graph mutation, across Store instances.
 * Cleanup holds the writer transaction from rebuilding/checking through deletion.
 * Incomplete, malformed or over-budget evidence protects every private file.
 */
final class ProjectMediaReferenceIndex {
    private static final String STATE = "project_media_reference_state";
    private static final String URIS = "project_media_reference_uris";
    private static final int FORMAT_VERSION = 1;
    private static final int CHUNK_BYTES = 128 * 1024;
    private static final int MAX_GRAPH_BYTES = 16 * 1024 * 1024;
    private static final long MAX_GRAPH_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_GRAPH_ROWS = 4096;
    private static final int MAX_URIS = 131072;
    private static final int MAX_URI_BYTES = 16384;
    private static final long MAX_INDEX_BYTES = 32L * 1024L * 1024L;
    private static final String[][] GRAPH_TABLES = {
            {"projects", "json"},
            {"project_history", "before_json", "after_json"},
            {"project_snapshots", "json"}
    };
    private ProjectMediaReferenceIndex() { }

    /** Called after all protected tables exist, on every database open. */
    static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + STATE + " (" +
                "id INTEGER PRIMARY KEY CHECK(id=1), format_version INTEGER NOT NULL," +
                "dirty INTEGER NOT NULL CHECK(dirty IN (0,1))," +
                "protect_all INTEGER NOT NULL CHECK(protect_all IN (0,1)))");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + URIS + " (" +
                "uri TEXT PRIMARY KEY NOT NULL COLLATE BINARY)");
        db.execSQL("INSERT OR IGNORE INTO " + STATE +
                "(id,format_version,dirty,protect_all) VALUES(1," + FORMAT_VERSION + ",1,1)");
        // Each trigger is intentionally independent of which Java Store instance
        // submitted the edit. Cascading deletions and pin chunk changes also dirty it.
        for (String[] table : GRAPH_TABLES) installTriggers(db, table[0]);
        installTriggers(db, "export_pins");
        installTriggers(db, "export_pin_chunks");
        installTriggers(db, ProjectSourceArchiveReferences.STATE);
    }

    /** Add a protected metadata table only after its own additive schema exists. */
    static void protectMetadataTable(SQLiteDatabase db, String table) {
        if (!"source_archive_reference_records".equals(table))
            throw new IllegalArgumentException("Unrecognized media ownership table");
        installTriggers(db, table);
    }

    private static void installTriggers(SQLiteDatabase db, String table) {
        for (String operation : new String[]{"INSERT", "UPDATE", "DELETE"}) {
            String trigger = "media_reference_" + table + "_" + operation.toLowerCase(java.util.Locale.ROOT);
            db.execSQL("CREATE TRIGGER IF NOT EXISTS " + trigger + " AFTER " + operation + " ON " + table +
                    " BEGIN UPDATE " + STATE + " SET dirty=1 WHERE id=1; END");
        }
    }

    /** The caller must keep this transaction until its private-file deletion completes. */
    static boolean referencesInTransaction(SQLiteDatabase db, String uri) {
        if (uri == null || uri.isEmpty()) return false;
        if (!db.inTransaction() || !ProjectMediaReferences.isUriString(uri)) return true;
        try {
            if (!ProjectSourceArchiveReferences.initialized(db)) return true;
            boolean dirty, protectAll;
            try (Cursor cursor = db.rawQuery("SELECT format_version,dirty,protect_all FROM " + STATE + " WHERE id=1", null)) {
                if (!cursor.moveToFirst() || cursor.getInt(0) != FORMAT_VERSION
                        || cursor.isNull(1) || cursor.isNull(2)
                        || !binary(cursor.getInt(1)) || !binary(cursor.getInt(2))) return true;
                dirty = cursor.getInt(1) == 1; protectAll = cursor.getInt(2) == 1;
                if (cursor.moveToNext()) return true;
            }
            if (dirty) protectAll = rebuild(db);
            if (protectAll) return true;
            try (Cursor cursor = db.rawQuery("SELECT 1 FROM " + URIS + " WHERE uri=? COLLATE BINARY LIMIT 1", new String[]{uri})) {
                return cursor.moveToFirst();
            }
        } catch (Exception | OutOfMemoryError unavailableProof) { return true; }
    }

    /**
     * Parse each accepted graph once after mutation, rather than once per file.
     * A failed scan commits a protect-all cache, so damaged metadata is not
     * repeatedly parsed during a large cleanup batch. Its next mutation retries.
     */
    private static boolean rebuild(SQLiteDatabase db) {
        boolean protectAll = true;
        try {
            Collector collector = new Collector();
            for (String[] table : GRAPH_TABLES) collectTable(db, collector, table, true);
            collectPins(db, collector);
            // Every source archive metadata file is admitted to this ledger
            // before publication. Missing schema is incomplete proof, not an
            // empty source archive catalog.
            collectTable(db, collector, new String[]{ProjectSourceArchiveReferences.TABLE, "json"}, false);
            db.delete(URIS, null, null);
            ContentValues values = new ContentValues();
            for (String uri : collector.uris) {
                values.clear(); values.put("uri", uri);
                db.insertOrThrow(URIS, null, values);
            }
            protectAll = false;
        } catch (Exception | OutOfMemoryError incompleteEvidence) {
            // Partial index entries never authorize deletion. Clear them only
            // for space; the state below independently denies every deletion.
            try { db.delete(URIS, null, null); } catch (Exception ignored) { }
        }
        ContentValues state = new ContentValues();
        state.put("dirty", 0); state.put("protect_all", protectAll ? 1 : 0);
        state.put("format_version", FORMAT_VERSION);
        if (db.update(STATE, state, "id=1", null) != 1)
            throw new IllegalStateException("Media reference cache state cannot be committed");
        return protectAll;
    }

    private static void collectTable(SQLiteDatabase db, Collector collector, String[] table, boolean graph) throws Exception {
        StringBuilder select = new StringBuilder("SELECT rowid");
        for (int index = 1; index < table.length; index++) select.append(",length(CAST(").append(table[index]).append(" AS BLOB))");
        select.append(" FROM ").append(table[0]);
        // The outer cursor contains only numeric identity/length fields, never
        // a multi-megabyte graph that could exceed Android's CursorWindow.
        try (Cursor cursor = db.rawQuery(select.toString(), null)) {
            while (cursor.moveToNext()) {
                collector.row();
                long row = cursor.getLong(0);
                for (int index = 1; index < table.length; index++) {
                    if (cursor.isNull(index)) throw new IllegalStateException("Retained graph has no readable metadata");
                    long length = cursor.getLong(index);
                    collector.graphBytes(length);
                    byte[] bytes = new byte[(int) length];
                    for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
                        int expected = Math.min(CHUNK_BYTES, bytes.length - offset);
                        try (Cursor chunk = db.rawQuery("SELECT substr(CAST(" + table[index] + " AS BLOB),?,?) FROM " + table[0] + " WHERE rowid=?",
                                new String[]{Integer.toString(offset + 1), Integer.toString(expected), Long.toString(row)})) {
                            if (!chunk.moveToFirst()) throw new IllegalStateException("Retained graph disappeared during ownership scan");
                            byte[] payload = chunk.getBlob(0);
                            if (payload == null || payload.length != expected || chunk.moveToNext())
                                throw new IllegalStateException("Retained graph bytes cannot be proven complete");
                            System.arraycopy(payload, 0, bytes, offset, expected);
                        }
                    }
                    collectJson(bytes, graph, collector);
                }
            }
        }
    }

    private static void collectPins(SQLiteDatabase db, Collector collector) throws Exception {
        ArrayList<Pin> pins = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT pin_id,owner_id,project_id,revision,json_bytes,chunk_count,sha256,created_at FROM export_pins", null)) {
            while (cursor.moveToNext()) {
                if (pins.size() >= ProjectExportPins.MAX_PINS) throw new IllegalStateException("Retained pin ledger exceeds its capacity");
                collector.row();
                Pin pin = new Pin(); pin.id = cursor.getString(0); pin.owner = cursor.getString(1); pin.project = cursor.getString(2);
                ProjectExportPins.requireIdentity(pin.id, "Media pin");
                ProjectExportPins.requireIdentity(pin.owner, "Media pin owner");
                ProjectExportPins.requireIdentity(pin.project, "Media pin project");
                pin.revision = cursor.getLong(3); pin.bytes = cursor.getLong(4); pin.chunks = cursor.getLong(5); pin.sha256 = cursor.getString(6);
                collector.graphBytes(pin.bytes);
                if (pin.revision < 0L || cursor.getLong(7) <= 0L || pin.chunks != (pin.bytes + CHUNK_BYTES - 1L) / CHUNK_BYTES
                        || pin.sha256 == null || !pin.sha256.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("Retained pin receipt cannot prove its graph");
                pins.add(pin);
            }
        }
        long expectedChunks = 0L;
        for (Pin pin : pins) {
            byte[] bytes = new byte[(int) pin.bytes]; int offset = 0, count = 0;
            try (Cursor cursor = db.rawQuery("SELECT ordinal,length(payload) FROM export_pin_chunks WHERE pin_id=? ORDER BY ordinal ASC", new String[]{pin.id})) {
                while (cursor.moveToNext()) {
                    int expected = Math.min(CHUNK_BYTES, bytes.length - offset);
                    if (count >= pin.chunks || cursor.getLong(0) != count || cursor.getLong(1) != expected)
                        throw new IllegalStateException("Retained pin graph has missing or extra chunks");
                    // Read even corrupted oversized BLOBs through a bounded SQL
                    // projection, rather than admitting their whole Cursor row.
                    try (Cursor chunk = db.rawQuery("SELECT substr(payload,1,?) FROM export_pin_chunks WHERE pin_id=? AND ordinal=?",
                            new String[]{Integer.toString(expected), pin.id, Integer.toString(count)})) {
                        if (!chunk.moveToFirst()) throw new IllegalStateException("Retained pin chunk disappeared");
                        byte[] payload = chunk.getBlob(0);
                        if (payload == null || payload.length != expected || chunk.moveToNext())
                            throw new IllegalStateException("Retained pin chunk cannot be proven complete");
                        System.arraycopy(payload, 0, bytes, offset, expected);
                    }
                    offset += expected; count++;
                }
            }
            if (count != pin.chunks || offset != bytes.length || !pin.sha256.equals(sha256(bytes)))
                throw new IllegalStateException("Retained pin graph differs from its checksum receipt");
            JSONObject graph = json(bytes);
            Object revision = graph.get("revision");
            if (!(revision instanceof Number) || ((Number) revision).doubleValue() != pin.revision
                    || !pin.project.equals(graph.getString("id")) || graph.getLong("revision") != pin.revision)
                throw new IllegalStateException("Retained pin graph differs from its admitted identity");
            ProjectMediaReferences.collectGraphUris(graph, collector);
            expectedChunks += pin.chunks;
        }
        // Orphan chunks are retained evidence too. They cannot be silently
        // ignored just because their damaged header no longer decodes.
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM export_pin_chunks", null)) {
            if (!cursor.moveToFirst() || cursor.getLong(0) != expectedChunks)
                throw new IllegalStateException("Retained pin chunks have incomplete ownership metadata");
        }
    }

    private static void collectJson(byte[] bytes, boolean graph, Collector collector) throws Exception {
        JSONObject value = json(bytes);
        if (graph) ProjectMediaReferences.collectGraphUris(value, collector);
        else ProjectMediaReferences.collectValueUris(value, collector);
    }

    private static JSONObject json(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        new JsonProof(text).validate();
        memoryBudget(bytes.length);
        return new JSONObject(text);
    }

    static JSONObject decodeOwnershipMetadata(byte[] bytes) throws Exception { return json(bytes); }

    private static void memoryBudget(long bytes) {
        Runtime runtime = Runtime.getRuntime();
        long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        if (bytes > (available - 32L * 1024L * 1024L) / 8L)
            throw new IllegalStateException("Ownership metadata cannot be safely decoded within the available heap");
    }

    private static boolean binary(int value) { return value == 0 || value == 1; }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] output = new char[digest.length * 2]; final char[] hex = "0123456789abcdef".toCharArray();
        for (int index = 0; index < digest.length; index++) {
            int value = digest[index] & 0xff; output[index * 2] = hex[value >>> 4]; output[index * 2 + 1] = hex[value & 15];
        }
        return new String(output);
    }

    private static final class Collector implements ProjectMediaReferences.UriCollector {
        final HashSet<String> uris = new HashSet<>();
        private long indexBytes, graphBytes;
        private int rows;
        void row() {
            if (++rows > MAX_GRAPH_ROWS) throw new IllegalStateException("Ownership graph row budget exceeded");
        }
        void graphBytes(long bytes) {
            if (bytes <= 0L || bytes > MAX_GRAPH_BYTES || bytes > MAX_GRAPH_TOTAL_BYTES - graphBytes)
                throw new IllegalStateException("Ownership graph byte budget exceeded");
            memoryBudget(bytes);
            graphBytes += bytes;
        }
        @Override public void add(String uri) {
            if (uris.contains(uri)) return;
            if (uri.length() > MAX_URI_BYTES || uris.size() >= MAX_URIS)
                throw new IllegalStateException("Ownership URI index capacity exceeded");
            int bytes = uri.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_URI_BYTES || bytes > MAX_INDEX_BYTES - indexBytes)
                throw new IllegalStateException("Ownership URI index byte budget exceeded");
            uris.add(uri); indexBytes += bytes;
        }
    }

    private static final class Pin {
        String id, owner, project, sha256;
        long revision, bytes, chunks;
    }

    /**
     * Validate depth, token count and unique decoded object keys before org.json
     * allocates its graph. A permissive parser must not discard an earlier URI
     * behind a duplicate key or accept a partial/trailing JSON document.
     */
    private static final class JsonProof {
        private final String text;
        private int position, remaining = 262144;
        JsonProof(String text) { this.text = text; }
        void validate() {
            value(0); whitespace();
            if (position != text.length()) fail();
        }
        private void value(int depth) {
            if (--remaining < 0 || depth > 64) fail();
            whitespace(); if (position >= text.length()) fail();
            char next = text.charAt(position);
            if (next == '{') object(depth);
            else if (next == '[') array(depth);
            else if (next == '"') string(false);
            else if (next == 't') literal("true");
            else if (next == 'f') literal("false");
            else if (next == 'n') literal("null");
            else number();
        }
        private void object(int depth) {
            position++; whitespace(); if (take('}')) return;
            HashSet<String> keys = new HashSet<>();
            do {
                whitespace(); if (--remaining < 0 || position >= text.length() || text.charAt(position) != '"') fail();
                String key = string(true);
                if (!keys.add(key)) fail();
                whitespace(); require(':'); value(depth + 1); whitespace();
                if (take('}')) return;
                require(',');
            } while (true);
        }
        private void array(int depth) {
            position++; whitespace(); if (take(']')) return;
            do {
                value(depth + 1); whitespace();
                if (take(']')) return;
                require(',');
            } while (true);
        }
        private String string(boolean key) {
            require('"'); StringBuilder decoded = key ? new StringBuilder() : null;
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (character == '"') return key ? decoded.toString() : "";
                if (character < 0x20) fail();
                if (character == '\\') {
                    if (position >= text.length()) fail();
                    char escaped = text.charAt(position++);
                    if (escaped == '"' || escaped == '\\' || escaped == '/') character = escaped;
                    else if (escaped == 'b') character = '\b';
                    else if (escaped == 'f') character = '\f';
                    else if (escaped == 'n') character = '\n';
                    else if (escaped == 'r') character = '\r';
                    else if (escaped == 't') character = '\t';
                    else if (escaped == 'u') {
                        int unicode = 0;
                        for (int index = 0; index < 4; index++) {
                            if (position >= text.length()) fail();
                            int digit = hex(text.charAt(position++)); if (digit < 0) fail();
                            unicode = unicode * 16 + digit;
                        }
                        character = (char) unicode;
                    } else fail();
                }
                if (key) {
                    if (decoded.length() >= 16384) fail();
                    decoded.append(character);
                }
            }
            fail(); return "";
        }
        private void number() {
            take('-');
            if (!take('0')) digits();
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
        }
        private void digits() {
            int start = position;
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character < '0' || character > '9') break;
                position++;
            }
            if (position == start) fail();
        }
        private void literal(String literal) {
            if (!text.regionMatches(position, literal, 0, literal.length())) fail();
            position += literal.length();
        }
        private boolean take(char expected) {
            if (position < text.length() && text.charAt(position) == expected) { position++; return true; }
            return false;
        }
        private void require(char expected) { if (!take(expected)) fail(); }
        private void whitespace() {
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') return;
                position++;
            }
        }
        private static int hex(char value) {
            if (value >= '0' && value <= '9') return value - '0';
            if (value >= 'a' && value <= 'f') return value - 'a' + 10;
            if (value >= 'A' && value <= 'F') return value - 'A' + 10;
            return -1;
        }
        private static void fail() { throw new IllegalStateException("Retained JSON cannot prove complete unique-key ownership metadata"); }
    }
}
