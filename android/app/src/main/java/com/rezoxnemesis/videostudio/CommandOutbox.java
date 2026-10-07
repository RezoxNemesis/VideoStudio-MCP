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
    public CommandOutbox(Context context) { super(context, "mcp_result_outbox.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, body TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    public synchronized void put(JSONObject command, JSONObject result, String status) throws Exception {
        String id = command.optString("id");
        if (id.isEmpty()) throw new IllegalArgumentException("Missing command id");
        JSONObject body = new JSONObject();
        body.put("status", status == null ? "completed" : status);
        body.put("result", result == null ? new JSONObject() : result);
        ContentValues values = new ContentValues();
        values.put("id", id); values.put("seq", command.optLong("seq")); values.put("body", body.toString());
        getWritableDatabase().insertOrThrow("results", null, values);
    }
    public synchronized boolean contains(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id FROM results WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst();
        }
    }
    public synchronized JSONArray pending() throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,seq,body FROM results ORDER BY seq LIMIT 16", null)) {
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
    public synchronized void acknowledge(String id) {
        getWritableDatabase().delete("results", "id=?", new String[]{id});
    }
}
