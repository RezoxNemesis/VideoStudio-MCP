package com.rezoxnemesis.videostudio;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;

/** Durable coordinator data kept separately from artwork and its ordinary undo history. */
final class SketchCorrectionStore implements AutoCloseable {
    static final int MAX_REVIEWS=16,MAX_PROPOSALS=4,MAX_NOTES=32;
    static final long MAX_STORAGE_BYTES=128L*1024*1024;
    // Durable OPEN status reserves bounded terminal metadata even across process loss.
    static final int FINALIZATION_RESERVE=2048;
    // Closure releases an OPEN slot but retains space for acknowledgement of an
    // artwork transaction committed before a process interruption and closure.
    static final int CLOSED_RECEIPT_RESERVE=1024;
    final Db helper;final SQLiteDatabase db;private final long maximumBytes;
    private static final class Db extends SQLiteOpenHelper {
        Db(Context c){super(c,"sketch_corrections_v1.db",null,1);}
        public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE reviews(id TEXT PRIMARY KEY,sketch_id TEXT NOT NULL,start_hash TEXT NOT NULL,source TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,status TEXT NOT NULL)");d.execSQL("CREATE TABLE proposals(review_id TEXT NOT NULL,id TEXT NOT NULL,request_hash TEXT NOT NULL,operations TEXT NOT NULL,document TEXT NOT NULL,metadata TEXT NOT NULL,PRIMARY KEY(review_id,id))");}
        public void onUpgrade(SQLiteDatabase d,int old,int next){throw new IllegalStateException("Correction storage migration required");}
    }
    SketchCorrectionStore(Context c){this(c,MAX_STORAGE_BYTES);}
    SketchCorrectionStore(Context c,long maximumBytes){if(maximumBytes<1||maximumBytes>MAX_STORAGE_BYTES)throw new IllegalArgumentException("Invalid correction storage budget");this.maximumBytes=maximumBytes;helper=new Db(c.getApplicationContext());db=helper.getWritableDatabase();}
    public void close(){helper.close();}
    boolean exists(String review){try(Cursor c=db.rawQuery("SELECT 1 FROM reviews WHERE id=?",new String[]{review})){return c.moveToFirst();}}
    JSONObject state(String sketch,String review)throws Exception{
        String owner=SQLiteLargeText.read(db,"SELECT sketch_id FROM reviews WHERE id=?",review);if(owner==null||!owner.equals(sketch))throw SketchDocument.bad("UNKNOWN_CORRECTION_REVIEW: review does not belong to this drawing");
        return new JSONObject(SQLiteLargeText.read(db,"SELECT state FROM reviews WHERE id=?",review));
    }
    JSONObject source(String sketch,String review)throws Exception{state(sketch,review);return new JSONObject(SQLiteLargeText.read(db,"SELECT source FROM reviews WHERE id=?",review));}
    String startHash(String sketch,String review)throws Exception{state(sketch,review);return SQLiteLargeText.read(db,"SELECT start_hash FROM reviews WHERE id=?",review);}
    void create(JSONObject source,JSONObject state,String hash)throws Exception{
        db.beginTransaction();try{
            String review=state.getString("reviewId"),sketch=state.getString("sketchId");
            if(exists(review)){if(!startHash(sketch,review).equals(hash))throw conflict("Review identity was already used for another request");db.setTransactionSuccessful();return;}
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM reviews WHERE status='OPEN'",null)){c.moveToFirst();if(c.getInt(0)>=MAX_REVIEWS)throw new IllegalStateException("CORRECTION_STORAGE_BUDGET: at most16 open reviews; close an unused review to keep its history and release a slot");}
            ContentValues v=new ContentValues();v.put("id",review);v.put("sketch_id",sketch);v.put("start_hash",hash);v.put("source",source.toString());v.put("state",state.toString());v.put("revision",1);v.put("status","OPEN");requireCapacity(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+FINALIZATION_RESERVE);db.insertOrThrow("reviews",null,v);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void write(String sketch,String review,long expected,JSONObject next)throws Exception{
        JSONObject prior=state(sketch,review);next.put("revision",Math.addExact(expected,1));
        long growth=Math.max(0,next.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length-prior.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        long released=reservation(prior.getString("status"))-reservation(next.getString("status"));
        if(released>0&&growth>released)throw new IllegalStateException("CORRECTION_FINALIZATION_BUDGET: terminal metadata exceeds its reserved bytes");
        requireCapacity(growth-released);ContentValues v=new ContentValues();v.put("revision",expected+1);v.put("state",next.toString());v.put("status",next.getString("status"));
        if(db.update("reviews",v,"id=? AND sketch_id=? AND revision=?",new String[]{review,sketch,Long.toString(expected)})!=1)throw conflict("Review changed; refresh before editing");
    }
    JSONObject proposal(String sketch,String review,String proposal)throws Exception{
        state(sketch,review);String doc=SQLiteLargeText.read(db,"SELECT document FROM proposals WHERE review_id=? AND id=?",review,proposal);if(doc==null)throw SketchDocument.bad("UNKNOWN_CORRECTION_PROPOSAL");
        return new JSONObject().put("document",new JSONObject(doc)).put("operations",new JSONArray(SQLiteLargeText.read(db,"SELECT operations FROM proposals WHERE review_id=? AND id=?",review,proposal))).put("requestHash",SQLiteLargeText.read(db,"SELECT request_hash FROM proposals WHERE review_id=? AND id=?",review,proposal)).put("metadata",new JSONObject(SQLiteLargeText.read(db,"SELECT metadata FROM proposals WHERE review_id=? AND id=?",review,proposal)));
    }
    boolean hasProposal(String review,String proposal){try(Cursor c=db.rawQuery("SELECT 1 FROM proposals WHERE review_id=? AND id=?",new String[]{review,proposal})){return c.moveToFirst();}}
    void saveProposal(String sketch,String review,long expected,String hash,JSONObject document,JSONArray operations,JSONObject metadata)throws Exception{
        db.beginTransaction();try{JSONObject state=state(sketch,review);requireRevision(state,expected);JSONArray proposals=state.getJSONArray("proposals");if(proposals.length()>=MAX_PROPOSALS)throw new IllegalStateException("CORRECTION_PROPOSAL_BUDGET: at most4 alternatives per review");
            requireCapacity(document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+operations.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            ContentValues v=new ContentValues();v.put("review_id",review);v.put("id",metadata.getString("proposalId"));v.put("request_hash",hash);v.put("operations",operations.toString());v.put("document",document.toString());v.put("metadata",metadata.toString());db.insertOrThrow("proposals",null,v);proposals.put(metadata);write(sketch,review,expected,state);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    JSONObject list(String sketch,int offset,int limit)throws Exception{
        JSONArray items=new JSONArray();boolean more=false;try(Cursor c=db.rawQuery("SELECT id FROM reviews WHERE sketch_id=? ORDER BY rowid DESC LIMIT ? OFFSET ?",new String[]{sketch,Integer.toString(limit+1),Integer.toString(offset)})){
            while(c.moveToNext()){if(items.length()==limit){more=true;break;}JSONObject s=state(sketch,c.getString(0));items.put(new JSONObject().put("reviewId",s.getString("reviewId")).put("target",s.getString("target")).put("status",s.getString("status")).put("revision",s.getLong("revision")).put("sourceRevision",s.getLong("sourceRevision")).put("noteCount",s.getJSONArray("notes").length()).put("proposalCount",s.getJSONArray("proposals").length()));}}
        return new JSONObject().put("ok",true).put("sketchId",sketch).put("reviews",items).put("offset",offset).put("more",more).put("sourceModified",false);
    }
    private static int reservation(String status){if(status.equals("OPEN"))return FINALIZATION_RESERVE;if(status.equals("CLOSED"))return CLOSED_RECEIPT_RESERVE;if(status.equals("APPLIED"))return 0;throw new IllegalStateException("Unknown correction review status");}
    private void requireCapacity(long additional){long total=0;for(String sql:new String[]{"SELECT COALESCE(SUM(length(CAST(source AS BLOB))+length(CAST(state AS BLOB))),0) FROM reviews","SELECT COALESCE(SUM(length(CAST(document AS BLOB))+length(CAST(operations AS BLOB))+length(CAST(metadata AS BLOB))),0) FROM proposals"})try(Cursor c=db.rawQuery(sql,null)){c.moveToFirst();total+=c.getLong(0);}try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(CASE WHEN status='OPEN' THEN "+FINALIZATION_RESERVE+" WHEN status='CLOSED' THEN "+CLOSED_RECEIPT_RESERVE+" ELSE 0 END),0) FROM reviews",null)){c.moveToFirst();total+=c.getLong(0);}if(total+additional>maximumBytes)throw new IllegalStateException("CORRECTION_STORAGE_BUDGET: retained review data exceeds128MiB; existing artwork and reviews were preserved");}
    static void requireRevision(JSONObject state,long expected)throws Exception{if(state.getLong("revision")!=expected)throw conflict("Review changed; refresh before editing");}
    static IllegalStateException conflict(String message){return new IllegalStateException("CORRECTION_REVIEW_CONFLICT: "+message);}
}
