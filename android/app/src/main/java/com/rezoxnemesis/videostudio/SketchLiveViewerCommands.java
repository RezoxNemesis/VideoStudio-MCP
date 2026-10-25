package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/**
 * Durable native-only control plane for the foreground *viewer* of recorded
 * ChatGPT pen operations. This queue cannot mutate a SketchDocument, change
 * timing, operate Android apps, inspect Gallery, or emulate fabricated strokes.
 *
 * Native MCP ACK means "queued". Only SketchStudioActivity reports "applied"
 * after it has actually changed the live player on screen.
 */
public final class SketchLiveViewerCommands implements AutoCloseable {
    public static final int MAX_PENDING=24;
    public static final long MAX_AGE_MS=6L*60*60*1000;
    private static final Set<String> COMMANDS=Set.of("pause","resume","next_operation","speed");
    private final SQLiteOpenHelper helper;

    private static final class DB extends SQLiteOpenHelper {
        DB(Context context){super(context,"sketch_live_view_controls_v1.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE live_view_commands ("+
                    "id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                    "sketch_id TEXT NOT NULL,session_id TEXT NOT NULL,operation_id TEXT NOT NULL,"+
                    "program TEXT NOT NULL,state TEXT NOT NULL,result TEXT NOT NULL,"+
                    "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,"+
                    "UNIQUE(sketch_id,session_id,operation_id))");
            db.execSQL("CREATE INDEX live_view_order ON live_view_commands("+
                    "sketch_id,session_id,state,id)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
            throw new IllegalStateException("Unknown native replay queue migration");
        }
    }

    public SketchLiveViewerCommands(Context context){
        if(context==null)throw new IllegalArgumentException("Native context required");
        helper=new DB(context.getApplicationContext());
    }
    @Override public void close(){helper.close();}

    public static JSONObject capabilities()throws Exception{
        JSONArray speeds=new JSONArray();
        for(float v:SketchLivePlaybackClock.PRESETS)speeds.put((Object)Double.valueOf(v));
        return new JSONObject()
                .put("schema","videostudio.sketch-live-view-control.v1")
                .put("minimumNativeVersion","4.10.40")
                .put("enqueueAction","sketch_live_view_control")
                .put("statusAction","sketch_live_view_status")
                .put("commands",new JSONArray(new String[]{"pause","resume","next_operation","speed"}))
                .put("speeds",speeds)
                .put("maxPending",MAX_PENDING)
                .put("durableSQLite",true)
                .put("appliedOnlyByVisibleSketchStudio",true)
                .put("noSketchRevisionWrites",true)
                .put("noGalleryAccess",true)
                .put("sourceStrokeJournalRequired",true)
                .put("stepMeansStartOneSavedOperation",true)
                .put("notAFrameRenderer",true);
    }

    public static JSONObject normalize(JSONObject raw)throws Exception{
        if(raw==null)throw SketchDocument.bad("Native replay viewer request required");
        SketchDocument.fields(raw,"sketchId,sessionId,operationId,command,speed");
        String sketch=SketchDocument.id(raw,"sketchId");
        String session=SketchDocument.id(raw,"sessionId");
        String operation=SketchDocument.id(raw,"operationId");
        String action=raw.optString("command","");
        if(!COMMANDS.contains(action))
            throw SketchDocument.bad("Replay viewer command must be pause, resume, next_operation or speed");
        JSONObject clean=new JSONObject().put("sketchId",sketch)
                .put("sessionId",session).put("operationId",operation).put("command",action);
        if("speed".equals(action)){
            Object requested=raw.opt("speed");
            if(!(requested instanceof Number))
                throw SketchDocument.bad("Replay rate must be a numeric preset");
            float speed=((Number)requested).floatValue();
            if(!SketchLivePlaybackClock.supported(speed))
                throw SketchDocument.bad("Replay speed must be 0.1, 0.25, 0.5, 1, 2, 4 or 8");
            clean.put("speed",(double)speed);
        }else if(raw.has("speed"))throw SketchDocument.bad("Only speed command accepts a rate");
        return clean;
    }

    public synchronized JSONObject enqueue(Context context,JSONObject raw)throws Exception{
        JSONObject program=normalize(raw);
        String sketch=program.getString("sketchId"),session=program.getString("sessionId");
        try(SketchProjectStore store=new SketchProjectStore(context)){
            store.liveSession(sketch,session); // Existing original live journal only.
        }
        SQLiteDatabase db=helper.getWritableDatabase();
        db.beginTransaction();
        try{
            expire(db,System.currentTimeMillis());
            JSONObject previous=read(db,sketch,session,program.getString("operationId"));
            if(previous!=null){
                if(!same(previous.getJSONObject("program"),program))
                    throw new IllegalStateException("SKETCH_REPLAY_OPERATION_CONFLICT");
                db.setTransactionSuccessful();
                return receipt(previous,true);
            }
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM live_view_commands WHERE state IN ('queued','running')",null)){
                if(c.moveToFirst()&&c.getInt(0)>=MAX_PENDING)
                    throw new IllegalStateException("SKETCH_REPLAY_CONTROL_QUEUE_FULL");
            }
            long now=System.currentTimeMillis();
            ContentValues cv=new ContentValues();
            cv.put("sketch_id",sketch);cv.put("session_id",session);
            cv.put("operation_id",program.getString("operationId"));
            cv.put("program",program.toString());cv.put("state","queued");
            cv.put("result","{}");cv.put("created_at",now);cv.put("updated_at",now);
            long id=db.insertOrThrow("live_view_commands",null,cv);
            if(id<1)throw new IllegalStateException("Could not queue native replay intent");
            JSONObject inserted=read(db,sketch,session,program.getString("operationId"));
            db.setTransactionSuccessful();
            return receipt(inserted,false);
        }finally{db.endTransaction();}
    }

    public synchronized JSONObject status(JSONObject query)throws Exception{
        if(query==null)throw SketchDocument.bad("Native replay receipt lookup required");
        SketchDocument.fields(query,"sketchId,sessionId,operationId");
        String sketch=SketchDocument.id(query,"sketchId"),
                session=SketchDocument.id(query,"sessionId"),
                operation=SketchDocument.id(query,"operationId");
        SQLiteDatabase db=helper.getWritableDatabase();
        expire(db,System.currentTimeMillis());
        JSONObject row=read(db,sketch,session,operation);
        if(row==null)throw SketchDocument.bad("Unknown replay command for this saved native session");
        return receipt(row,false);
    }

    /** Claims only the exact foreground journal; cannot control another sketch. */
    public synchronized JSONObject claim(String sketch,String session)throws Exception{
        if(sketch==null||sketch.isEmpty()||session==null||session.isEmpty())return null;
        SQLiteDatabase db=helper.getWritableDatabase();
        db.beginTransaction();
        try{
            expire(db,System.currentTimeMillis());
            JSONObject found=null;
            try(Cursor c=db.rawQuery(
                    "SELECT id,sketch_id,session_id,operation_id,program,state,result,created_at,updated_at "+
                            "FROM live_view_commands WHERE sketch_id=? AND session_id=? AND state='queued' "+
                            "ORDER BY id LIMIT 1",new String[]{sketch,session})){
                if(c.moveToFirst())found=row(c);
            }
            if(found!=null){
                ContentValues values=new ContentValues();
                values.put("state","running");values.put("updated_at",System.currentTimeMillis());
                if(db.update("live_view_commands",values,"id=? AND state='queued'",
                        new String[]{Long.toString(found.getLong("id"))})!=1)
                    throw new IllegalStateException("Replay command claim changed");
                found.put("state","running");
            }
            db.setTransactionSuccessful();
            return found;
        }finally{db.endTransaction();}
    }

    /** Early UI navigation rejection; nothing was applied, so retry is safe. */
    public synchronized void release(JSONObject claimed)throws Exception{
        settle(claimed,"running","queued",new JSONObject());
    }

    /** Foreground proof only: no pretend completion from MCP request enqueue. */
    public synchronized JSONObject finish(JSONObject claimed,boolean applied,JSONObject evidence)throws Exception{
        if(evidence==null||evidence.toString().length()>2048)
            throw SketchDocument.bad("Bounded real viewer evidence required");
        JSONObject safe=new JSONObject()
                .put("foregroundApplied",applied)
                .put("command",claimed.getJSONObject("program").getString("command"))
                .put("sketchId",claimed.getString("sketchId"))
                .put("sessionId",claimed.getString("sessionId"))
                .put("note",evidence.optString("note",""))
                .put("viewPaused",evidence.optBoolean("viewPaused",false))
                .put("viewRate",evidence.optDouble("viewRate",1));
        if(safe.getString("note").length()>180)
            throw SketchDocument.bad("Replay evidence description too long");
        settle(claimed,"running",applied?"applied":"rejected",safe);
        return status(new JSONObject().put("sketchId",claimed.getString("sketchId"))
                .put("sessionId",claimed.getString("sessionId"))
                .put("operationId",claimed.getString("operationId")));
    }

    private void settle(JSONObject claimed,String current,String next,JSONObject result)throws Exception{
        if(claimed==null)throw SketchDocument.bad("Replay claim receipt required");
        SQLiteDatabase db=helper.getWritableDatabase();
        ContentValues values=new ContentValues();
        values.put("state",next);values.put("updated_at",System.currentTimeMillis());
        values.put("result",result.toString());
        int changed=db.update("live_view_commands",values,"id=? AND state=? AND sketch_id=? AND session_id=?",
                new String[]{Long.toString(claimed.getLong("id")),current,
                        claimed.getString("sketchId"),claimed.getString("sessionId")});
        if(changed!=1)throw new IllegalStateException("SKETCH_REPLAY_ALREADY_SETTLED");
    }

    private static void expire(SQLiteDatabase db,long now){
        long threshold=now-MAX_AGE_MS;
        ContentValues expired=new ContentValues();expired.put("state","expired");
        expired.put("result","{\"foregroundApplied\":false,\"note\":\"Viewer command expired without foreground execution\"}");
        expired.put("updated_at",now);
        db.update("live_view_commands",expired,"state='queued' AND created_at<?",
                new String[]{Long.toString(threshold)});
        ContentValues uncertain=new ContentValues();uncertain.put("state","uncertain");
        uncertain.put("result","{\"foregroundApplied\":false,\"note\":\"Foreground acknowledgment interrupted; will not repeat an uncertain next-operation command\"}");
        uncertain.put("updated_at",now);
        db.update("live_view_commands",uncertain,"state='running' AND updated_at<?",
                new String[]{Long.toString(now-60_000)});
        // Leave settled receipts for one week, bounded by expired cleanup.
        db.delete("live_view_commands","state NOT IN ('queued','running') AND updated_at<?",
                new String[]{Long.toString(now-7L*24*60*60*1000)});
    }

    private static JSONObject read(SQLiteDatabase db,String sketch,String session,String operation)throws Exception{
        try(Cursor c=db.rawQuery(
                "SELECT id,sketch_id,session_id,operation_id,program,state,result,created_at,updated_at "+
                        "FROM live_view_commands WHERE sketch_id=? AND session_id=? AND operation_id=?",
                new String[]{sketch,session,operation})){
            return c.moveToFirst()?row(c):null;
        }
    }
    private static JSONObject row(Cursor c)throws Exception{
        return new JSONObject().put("id",c.getLong(0)).put("sketchId",c.getString(1))
                .put("sessionId",c.getString(2)).put("operationId",c.getString(3))
                .put("program",new JSONObject(c.getString(4)))
                .put("state",c.getString(5)).put("evidence",new JSONObject(c.getString(6)))
                .put("createdAtMs",c.getLong(7)).put("updatedAtMs",c.getLong(8));
    }
    private static JSONObject receipt(JSONObject stored,boolean reused)throws Exception{
        JSONObject output=new JSONObject()
                .put("schema","videostudio.sketch-live-view-receipt.v1")
                .put("sketchId",stored.getString("sketchId"))
                .put("sessionId",stored.getString("sessionId"))
                .put("operationId",stored.getString("operationId"))
                .put("command",stored.getJSONObject("program").getString("command"))
                .put("state",stored.getString("state"))
                .put("foregroundApplied","applied".equals(stored.getString("state")))
                .put("reused",reused)
                .put("evidence",stored.getJSONObject("evidence"))
                .put("galleryAccess",false);
        if("speed".equals(stored.getJSONObject("program").getString("command")))
            output.put("speed",stored.getJSONObject("program").getDouble("speed"));
        return output;
    }

    private static boolean same(JSONObject a,JSONObject b)throws Exception{
        return SketchDocument.canonical(a).equals(SketchDocument.canonical(b));
    }
}
