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
    public CommandOutbox(Context context) { super(context, "mcp_result_outbox.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, body TEXT NOT NULL, retry_after_ms INTEGER NOT NULL DEFAULT 0, attempt_count INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<2){
            db.execSQL("ALTER TABLE results ADD COLUMN retry_after_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE results ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0");
        }
    }
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
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,seq,body FROM results WHERE retry_after_ms<=? ORDER BY seq LIMIT 16",new String[]{String.valueOf(System.currentTimeMillis())})) {
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
    /** Retain failed result bytes while letting later native ACKs pass. */
    public synchronized void defer(String id) {
        if(id==null||id.isEmpty())return;
        try(Cursor cursor=getReadableDatabase().rawQuery(
                "SELECT attempt_count FROM results WHERE id=?",new String[]{id})){
            if(!cursor.moveToFirst())return;
            int attempts=Math.min(20,Math.max(0,cursor.getInt(0)))+1;
            long delay=Math.min(300000L,5000L*(1L<<Math.min(7,attempts-1)));
            ContentValues values=new ContentValues();
            values.put("attempt_count",attempts);
            values.put("retry_after_ms",System.currentTimeMillis()+delay);
            getWritableDatabase().update("results",values,"id=?",new String[]{id});
        }
    }
    public synchronized void acknowledge(String id) {
        getWritableDatabase().delete("results", "id=?", new String[]{id});
    }
}
