package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Full results stay local after delivery; pending rows hold the frozen relay receipt. */
public final class CommandOutbox extends SQLiteOpenHelper {
    private static final long BATCH_BYTES = 4L * 1024 * 1024;
    public CommandOutbox(Context context) { super(context, "mcp_result_outbox.db", null, 3); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE results (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, body TEXT NOT NULL, retry_after_ms INTEGER NOT NULL DEFAULT 0, attempt_count INTEGER NOT NULL DEFAULT 0)");
        createArchive(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<2){
            db.execSQL("ALTER TABLE results ADD COLUMN retry_after_ms INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE results ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0");
        }
        if(oldVersion<3) {
            createArchive(db);
            // A legacy row may already have reached a relay whose response was
            // lost. Preserve its original wire body; changing it could conflict
            // with an accepted terminal ACK. New results use bounded envelopes.
            try(Cursor cursor=db.rawQuery("SELECT id,seq FROM results",null)) {
                while(cursor.moveToNext()) {
                    String id=cursor.getString(0);
                    String wire=SQLiteLargeText.read(db,"SELECT body FROM results WHERE id=?",id);
                    try {
                        JSONObject body=new JSONObject(wire);
                        archive(db,id,cursor.getLong(1),body.getString("status"),body.getJSONObject("result").toString(),wire,true,"");
                    } catch(Exception invalid) {throw new IllegalStateException("Existing native receipt could not be archived; original queue is retained",invalid);}
                }
            }
        }
    }
    private static void createArchive(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE receipt_archive (id TEXT PRIMARY KEY, seq INTEGER NOT NULL, status TEXT NOT NULL, result_blob BLOB NOT NULL, result_sha256 TEXT NOT NULL, wire_body TEXT NOT NULL, legacy INTEGER NOT NULL DEFAULT 0, allowed_file_id TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE receipt_recovery (id TEXT PRIMARY KEY, original_wire_body TEXT NOT NULL, compact_wire_body TEXT NOT NULL, state TEXT NOT NULL, prepared_at_ms INTEGER NOT NULL)");
    }
    private static void archive(SQLiteDatabase db,String id,long seq,String status,String result,String wire,boolean legacy,String allowedFileId) throws Exception {
        ContentValues values=new ContentValues();values.put("id",id);values.put("seq",seq);values.put("status",status);
        byte[] bytes=RelayTransportPolicy.bytes(result);
        values.put("result_blob",bytes);values.put("result_sha256",RelayTransportPolicy.sha256(bytes));
        values.put("wire_body",wire);values.put("legacy",legacy?1:0);values.put("allowed_file_id",allowedFileId);
        db.insertOrThrow("receipt_archive",null,values);
    }
    public synchronized void put(JSONObject command, JSONObject result, String status) throws Exception {
        put(command,result,status,"everything","");
    }
    public synchronized void put(JSONObject command,JSONObject result,String status,String permissionMode,String allowedFileId) throws Exception {
        String id = command.optString("id");
        if (id.isEmpty()) throw new IllegalArgumentException("Missing command id");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            String frozen=SQLiteLargeText.read(db,"SELECT wire_body FROM receipt_archive WHERE id=?",id);
            long sequence=command.optLong("seq");
            if(frozen==null) {
                String full=(result==null?new JSONObject():result).toString();
                String terminalStatus=status==null?"completed":status;
                frozen=new JSONObject().put("status",terminalStatus)
                        .put("result",RelayTransportPolicy.result(id,full)).toString();
                String binding="one_file".equals(permissionMode) && allowedFileId!=null ? allowedFileId : "";
                archive(db,id,sequence,terminalStatus,full,frozen,false,binding);
            } else {
                try(Cursor cursor=db.rawQuery("SELECT seq FROM receipt_archive WHERE id=?",new String[]{id})) {
                    if(!cursor.moveToFirst()) throw new IllegalStateException("Frozen native receipt is missing");
                    sequence=cursor.getLong(0);
                }
            }
            ContentValues values=new ContentValues();
            values.put("id",id);values.put("seq",sequence);values.put("body",frozen);
            db.insertWithOnConflict("results",null,values,SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    /** Only receipts captured under this exact One File Lock may be paged in that scope. */
    public synchronized boolean canReadResultForFile(String id,String allowedFileId) {
        if(id==null || id.isEmpty() || allowedFileId==null || allowedFileId.isEmpty()) return false;
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT 1 FROM receipt_archive WHERE id=? AND allowed_file_id=? LIMIT 1",new String[]{id,allowedFileId})) {
            return cursor.moveToFirst();
        }
    }

    /** Bounded recovery index; unprepared legacy receipts are shown first. */
    public synchronized JSONObject deliveryStatus(int limit) throws Exception {
        if(limit<1 || limit>4) throw new IllegalArgumentException("Delivery status limit must be 1..4");
        SQLiteDatabase db=getReadableDatabase();JSONArray rows=new JSONArray();
        try(Cursor cursor=db.rawQuery("SELECT r.id,r.seq,a.status,a.result_sha256,length(a.result_blob),length(CAST(r.body AS BLOB)),coalesce(c.state,'not_prepared') FROM results r JOIN receipt_archive a ON a.id=r.id LEFT JOIN receipt_recovery c ON c.id=r.id WHERE a.legacy=1 ORDER BY CASE WHEN c.id IS NULL THEN 0 ELSE 1 END,r.seq LIMIT ?",new String[]{Integer.toString(limit)})) {
            while(cursor.moveToNext()) rows.put(new JSONObject().put("commandId",cursor.getString(0))
                    .put("seq",cursor.getLong(1)).put("status",cursor.getString(2)).put("resultSha256",cursor.getString(3))
                    .put("resultBytes",cursor.getLong(4)).put("wireBytes",cursor.getLong(5)).put("recoveryState",cursor.getString(6)));
        }
        return new JSONObject().put("ok",true).put("schema","videostudio.native-delivery-status.v1")
                .put("pendingResults",count()).put("legacyPending",scalar(db,"SELECT COUNT(*) FROM results r JOIN receipt_archive a ON a.id=r.id WHERE a.legacy=1"))
                .put("legacyUncompacted",scalar(db,"SELECT COUNT(*) FROM results r JOIN receipt_archive a ON a.id=r.id LEFT JOIN receipt_recovery c ON c.id=r.id WHERE a.legacy=1 AND c.id IS NULL"))
                .put("reconciliationRequired",scalar(db,"SELECT COUNT(*) FROM results r JOIN receipt_recovery c ON c.id=r.id WHERE c.state='needs_reconciliation'"))
                .put("deliveries",rows);
    }
    private static long scalar(SQLiteDatabase db,String sql) {
        try(Cursor cursor=db.rawQuery(sql,null)) {return cursor.moveToFirst()?cursor.getLong(0):0;}
    }

    /** Explicit history compaction, never an inferred acknowledgement or native re-execution. */
    public synchronized JSONObject compactLegacyReceipt(String id,String expectedSha256) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            String status,sha,state;long originalBytes,compactBytes;
            try(Cursor cursor=db.rawQuery("SELECT a.status,a.result_sha256,a.legacy,coalesce(c.state,'not_prepared') FROM receipt_archive a JOIN results r ON r.id=a.id LEFT JOIN receipt_recovery c ON c.id=a.id WHERE a.id=?",new String[]{id})) {
                if(!cursor.moveToFirst() || cursor.getInt(2)!=1) throw new IllegalArgumentException("Pending legacy receipt not found");
                status=cursor.getString(0);sha=cursor.getString(1);state=cursor.getString(3);
            }
            if(!sha.equals(expectedSha256)) throw new IllegalArgumentException("Original native result hash mismatch");
            if(!java.util.Arrays.asList("completed","failed","denied","cancelled").contains(status)) throw new IllegalArgumentException("Only native terminal receipts can be compacted");
            if("not_prepared".equals(state)) {
                String json=SQLiteLargeText.read(db,"SELECT CAST(result_blob AS TEXT) FROM receipt_archive WHERE id=?",id);
                if(!sha.equals(RelayTransportPolicy.sha256(RelayTransportPolicy.bytes(json)))) throw new IllegalStateException("Native result archive checksum mismatch");
                String original=SQLiteLargeText.read(db,"SELECT wire_body FROM receipt_archive WHERE id=?",id);
                String compact=new JSONObject().put("status",status).put("result",RelayTransportPolicy.externalizedResult(id,json)).toString();
                if(RelayTransportPolicy.bytes(compact).length>=RelayTransportPolicy.bytes(original).length) throw new IllegalArgumentException("Legacy receipt cannot be reduced by compaction");
                ContentValues audit=new ContentValues();audit.put("id",id);audit.put("original_wire_body",original);
                audit.put("compact_wire_body",compact);audit.put("state","prepared");audit.put("prepared_at_ms",System.currentTimeMillis());
                db.insertOrThrow("receipt_recovery",null,audit);
                ContentValues frozen=new ContentValues();frozen.put("wire_body",compact);db.update("receipt_archive",frozen,"id=?",new String[]{id});
                ContentValues pending=new ContentValues();pending.put("body",compact);pending.put("retry_after_ms",0);pending.put("attempt_count",0);
                db.update("results",pending,"id=?",new String[]{id});state="prepared";
            }
            try(Cursor cursor=db.rawQuery("SELECT length(CAST(original_wire_body AS BLOB)),length(CAST(compact_wire_body AS BLOB)) FROM receipt_recovery WHERE id=?",new String[]{id})) {
                if(!cursor.moveToFirst()) throw new IllegalStateException("Recovery audit is missing");
                originalBytes=cursor.getLong(0);compactBytes=cursor.getLong(1);
            }
            db.setTransactionSuccessful();
            return new JSONObject().put("ok",true).put("commandId",id).put("localCompactionPrepared",true)
                    .put("serverAcknowledged",false).put("fullResultRetained",true).put("resultSha256",sha)
                    .put("originalWireBytes",originalBytes).put("compactWireBytes",compactBytes).put("recoveryState",state);
        } finally {db.endTransaction();}
    }

    public synchronized void markNeedsReconciliation(String id) {
        ContentValues values=new ContentValues();values.put("state","needs_reconciliation");
        getWritableDatabase().update("receipt_recovery",values,"id=?",new String[]{id});
    }

    /** Exact UTF-8 JSON result bytes, available after ACK and process restart. */
    public synchronized JSONObject readResultPage(String id,int pageIndex) throws Exception {
        if(id==null || id.isEmpty()) throw new IllegalArgumentException("Missing command id");
        if(pageIndex<0) throw new IllegalArgumentException("Result page index must be nonnegative");
        SQLiteDatabase db=getReadableDatabase();
        String status,sha;int total;
        try(Cursor cursor=db.rawQuery("SELECT status,length(result_blob),result_sha256 FROM receipt_archive WHERE id=?",new String[]{id})) {
            if(!cursor.moveToFirst()) throw new IllegalArgumentException("Native result not found");
            status=cursor.getString(0);total=cursor.getInt(1);sha=cursor.getString(2);
        }
        int pages=Math.max(1,(int)(((long)total+RelayTransportPolicy.RESULT_PAGE_BYTES-1)/RelayTransportPolicy.RESULT_PAGE_BYTES));
        if(pageIndex>=pages) throw new IllegalArgumentException("Result page index exceeds available pages");
        int start=pageIndex*RelayTransportPolicy.RESULT_PAGE_BYTES;
        byte[] part;
        // Read only this bounded BLOB slice, never the full result through a
        // CursorWindow or a fresh multi-megabyte decode/hash on every page.
        try(Cursor cursor=db.rawQuery("SELECT substr(result_blob,?,?) FROM receipt_archive WHERE id=?",
                new String[]{Integer.toString(start+1),Integer.toString(RelayTransportPolicy.RESULT_PAGE_BYTES),id})) {
            if(!cursor.moveToFirst()) throw new IllegalStateException("Native result archive changed");
            part=cursor.getBlob(0);
        }
        return new JSONObject().put("ok",true).put("schema","videostudio.native-result-page.v1")
                .put("commandId",id).put("status",status).put("resultSha256",sha)
                .put("resultBytes",total).put("encoding","base64").put("pageIndex",pageIndex)
                // Generic binary fields are redacted from ordinary relay JSON;
                // this explicitly encoded result fragment must survive intact.
                .put("pageCount",pages).put("encodedChunk",Base64.encodeToString(part,Base64.NO_WRAP));
    }
    public synchronized boolean contains(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id FROM results WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst();
        }
    }
    public synchronized JSONArray pending() throws Exception {
        JSONArray out = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        long bytes = 2;
        try (Cursor cursor = db.rawQuery("SELECT id,seq,length(CAST(body AS BLOB)) FROM results WHERE retry_after_ms<=? ORDER BY seq LIMIT 16",new String[]{String.valueOf(System.currentTimeMillis())})) {
            while (cursor.moveToNext()) {
                long nextBytes = cursor.getLong(2) + 256;
                // A legacy oversized receipt may travel alone, retaining its exact
                // bytes. Ordinary batches never load sixteen large JSON bodies.
                if (out.length() > 0 && bytes + nextBytes > BATCH_BYTES) break;
                String body = SQLiteLargeText.read(db, "SELECT body FROM results WHERE id=?", cursor.getString(0));
                if (body == null) continue;
                JSONObject value = new JSONObject(body);
                value.put("id", cursor.getString(0)); value.put("seq", cursor.getLong(1)); out.put(value);
                bytes += nextBytes;
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
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            db.delete("results","id=?",new String[]{id});
            ContentValues values=new ContentValues();values.put("state","acknowledged");db.update("receipt_recovery",values,"id=?",new String[]{id});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
}
