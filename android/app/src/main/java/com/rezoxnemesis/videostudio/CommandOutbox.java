package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Results stay on disk until the relay confirms their receipt. No owner credentials are stored. */
public final class CommandOutbox extends SQLiteOpenHelper {
    public static final int MAX_UNACKNOWLEDGED = 16;
    public static final long MAX_STORED_BYTES = (long) MAX_UNACKNOWLEDGED * (NativeCommandResults.MAX_RESULT_BYTES + 4096);
    public CommandOutbox(Context context) { super(context, "mcp_result_outbox.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE admissions (id TEXT PRIMARY KEY, seq INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS admissions (id TEXT PRIMARY KEY, seq INTEGER NOT NULL)");
    }
    /** Reserve completion storage before any accepted native effect. Existing reservations survive restart. */
    public synchronized boolean reserve(JSONObject command) throws Exception {
        String id = command.optString("id", "");
        if (id.isEmpty()) throw new IllegalArgumentException("Missing command id");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (exists(db, "results", id)) { db.setTransactionSuccessful(); return false; }
            if (exists(db, "admissions", id)) { db.setTransactionSuccessful(); return true; }
            long maximumBody = NativeCommandResults.MAX_RESULT_BYTES + 4096L;
            if (inventory(db) >= MAX_UNACKNOWLEDGED || oversizedLegacyResults(db) > 0
                    || storedBytes(db) + (admissionsCount(db) + 1L) * maximumBody > MAX_STORED_BYTES) return false;
            ContentValues values = new ContentValues(); values.put("id", id); values.put("seq", command.optLong("seq"));
            db.insertOrThrow("admissions", null, values);
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    public synchronized void put(JSONObject command, JSONObject result, String status) throws Exception {
        String id = command.optString("id");
        if (id.isEmpty()) throw new IllegalArgumentException("Missing command id");
        JSONObject body = new JSONObject();
        String terminalStatus = status == null ? "completed" : status;
        if (!"completed".equals(terminalStatus) && !"failed".equals(terminalStatus)
                && !"denied".equals(terminalStatus) && !"cancelled".equals(terminalStatus))
            throw new IllegalArgumentException("Result delivery requires a terminal status");
        body.put("status", terminalStatus);
        body.put("result", NativeCommandResults.bounded(result, terminalStatus));
        String serialized = body.toString();
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            try (Cursor prior = db.rawQuery("SELECT body FROM results WHERE id=?", new String[]{id})) {
                if (prior.moveToFirst()) {
                    if (!NativeCommandResults.canonical(new JSONObject(prior.getString(0))).equals(NativeCommandResults.canonical(body)))
                        throw new IllegalStateException("Conflicting native result replay rejected; the first unacknowledged receipt is retained");
                    db.setTransactionSuccessful(); return;
                }
            }
            if (!exists(db, "admissions", id) && inventory(db) >= MAX_UNACKNOWLEDGED)
                throw new IllegalStateException("Result outbox is full; unacknowledged receipts are retained");
            long newBytes = serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (storedBytes(db) + newBytes > MAX_STORED_BYTES)
                throw new IllegalStateException("Result storage is full; legacy unacknowledged receipts require recovery");
            ContentValues values = new ContentValues();
            values.put("id", id); values.put("seq", command.optLong("seq")); values.put("body", serialized);
            db.insertOrThrow("results", null, values);
            db.delete("admissions", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized boolean contains(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id FROM results WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst();
        }
    }
    public synchronized JSONArray pending() throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,seq,body FROM results ORDER BY seq,id LIMIT 4", null)) {
            while (cursor.moveToNext()) {
                JSONObject value = new JSONObject(cursor.getString(2));
                value.put("id", cursor.getString(0)); value.put("seq", cursor.getLong(1)); out.put(value);
            }
        }
        return out;
    }
    public synchronized int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM results", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }
    public synchronized int acceptedCount() { return inventory(getReadableDatabase()); }
    public synchronized JSONObject storageStatus() throws Exception {
        SQLiteDatabase db = getReadableDatabase(); long bytes = storedBytes(db);
        int reservations = admissionsCount(db), legacy = oversizedLegacyResults(db), accepted = inventory(db);
        return new JSONObject().put("storedResultBytes", bytes).put("reservedCommands", reservations)
                .put("acceptedCommands", accepted).put("oversizedLegacyResults", legacy)
                .put("maximumStoredBytes", MAX_STORED_BYTES)
                .put("admissionBlocked", accepted >= MAX_UNACKNOWLEDGED || legacy > 0
                        || bytes + (reservations + 1L) * (NativeCommandResults.MAX_RESULT_BYTES + 4096L) > MAX_STORED_BYTES);
    }
    private static boolean exists(SQLiteDatabase db, String table, String id) {
        try (Cursor cursor = db.rawQuery("SELECT id FROM " + table + " WHERE id=?", new String[]{id})) { return cursor.moveToFirst(); }
    }
    private static int inventory(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM (SELECT id FROM results UNION SELECT id FROM admissions)", null)) { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
    }
    private static long storedBytes(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM results", null)) { return cursor.moveToFirst() ? cursor.getLong(0) : 0; }
    }
    private static int admissionsCount(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM admissions", null)) { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
    }
    private static int oversizedLegacyResults(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM results WHERE length(CAST(body AS BLOB))>?", new String[]{String.valueOf(NativeCommandResults.MAX_RESULT_BYTES + 4096L)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }
    public synchronized void acknowledge(String id) {
        getWritableDatabase().delete("results", "id=?", new String[]{id});
    }
}
