package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.ExifInterface;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Own database, transactional snapshot/history/receipt updates and immutable owned references. */
public final class SketchProjectStore implements AutoCloseable {
    public interface Mutation { JSONObject apply(JSONObject current) throws Exception; }
    private static final int HISTORY_ENTRIES = 32, HISTORY_BYTES = 16 * 1024 * 1024;
    private final Context context;
    private final Db helper;
    private final SQLiteDatabase db;
    private static final class Db extends SQLiteOpenHelper {
        Db(Context context) { super(context, "sketch_studio_v1.db", null, 6); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE sketches(id TEXT PRIMARY KEY, revision INTEGER NOT NULL,json TEXT NOT NULL,position INTEGER NOT NULL,next_position INTEGER NOT NULL,summary TEXT NOT NULL)");
            db.execSQL("CREATE TABLE sketch_history(sketch_id TEXT NOT NULL,position INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(sketch_id,position))");
            db.execSQL("CREATE TABLE sketch_live_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,sketch_id TEXT NOT NULL,base_revision INTEGER NOT NULL,revision INTEGER NOT NULL,operations TEXT NOT NULL)");
            db.execSQL("CREATE INDEX sketch_live_lookup ON sketch_live_events(sketch_id,sequence)");
            db.execSQL("CREATE TABLE sketch_live_sessions(sequence INTEGER PRIMARY KEY AUTOINCREMENT,sketch_id TEXT NOT NULL,session_id TEXT NOT NULL,json TEXT NOT NULL,end_sequence INTEGER NOT NULL DEFAULT 0,ended INTEGER NOT NULL DEFAULT 0,consumed INTEGER NOT NULL DEFAULT 0,UNIQUE(sketch_id,session_id))");
            db.execSQL("CREATE TABLE sketch_operations(sketch_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(sketch_id,operation_id))");
            db.execSQL("CREATE TABLE sketch_paint_strokes(sketch_id TEXT NOT NULL,object_id TEXT NOT NULL,stroke_id TEXT NOT NULL,intent_hash TEXT NOT NULL,PRIMARY KEY(sketch_id,object_id,stroke_id))");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int old, int next) { if(old==1&&next>=2){db.execSQL("ALTER TABLE sketches ADD COLUMN summary TEXT NOT NULL DEFAULT '{}'");try(Cursor c=db.rawQuery("SELECT id FROM sketches",null)){while(c.moveToNext()){try{ContentValues values=new ContentValues();values.put("summary",summary(new JSONObject(SQLiteLargeText.read(db,"SELECT json FROM sketches WHERE id=?",c.getString(0)))).toString());db.update("sketches",values,"id=?",new String[]{c.getString(0)});}catch(Exception error){throw new IllegalStateException("Sketch metadata migration failed",error);}}}}
            if(old<3&&next>=3){db.execSQL("CREATE TABLE sketch_live_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,sketch_id TEXT NOT NULL,base_revision INTEGER NOT NULL,revision INTEGER NOT NULL,operations TEXT NOT NULL)");db.execSQL("CREATE INDEX sketch_live_lookup ON sketch_live_events(sketch_id,sequence)");}
            if(old<4&&next>=4){db.execSQL("CREATE TABLE sketch_live_sessions(sequence INTEGER PRIMARY KEY AUTOINCREMENT,sketch_id TEXT NOT NULL,session_id TEXT NOT NULL,json TEXT NOT NULL,end_sequence INTEGER NOT NULL DEFAULT 0,ended INTEGER NOT NULL DEFAULT 0,consumed INTEGER NOT NULL DEFAULT 0,UNIQUE(sketch_id,session_id))");}
            else if(old==4&&next>=5)db.execSQL("ALTER TABLE sketch_live_sessions ADD COLUMN consumed INTEGER NOT NULL DEFAULT 0");
            if(old<6&&next>=6)db.execSQL("CREATE TABLE sketch_paint_strokes(sketch_id TEXT NOT NULL,object_id TEXT NOT NULL,stroke_id TEXT NOT NULL,intent_hash TEXT NOT NULL,PRIMARY KEY(sketch_id,object_id,stroke_id))"); }
    }
    public SketchProjectStore(Context context) {
        this.context = context.getApplicationContext(); helper = new Db(this.context); db = helper.getWritableDatabase();
    }
    @Override public void close() { helper.close(); }

    public JSONObject get(String id) throws Exception {
        SketchDocument.id(new JSONObject().put("id", id), "id");
        String json = SQLiteLargeText.read(db, "SELECT json FROM sketches WHERE id=?", id);
        if (json == null) throw new IllegalArgumentException("UNKNOWN_SKETCH: " + id);
        JSONObject doc = new JSONObject(json); SketchDocument.validate(doc); return doc;
    }
    void beginSnapshot(){db.beginTransactionNonExclusive();}
    void endSnapshot(){db.endTransaction();}
    public JSONObject createLiveSession(JSONObject state) throws Exception {
        String id=state.getString("sketchId"),session=state.getString("sessionId");
        db.beginTransaction();try{
            try{JSONObject previous=liveSession(id,session);
                if(!SketchDrawingPolicy.method(previous).equals(SketchDrawingPolicy.method(state))||previous.getInt("strokeDurationMs")!=state.getInt("strokeDurationMs")||previous.getLong("afterSequence")!=state.getLong("afterSequence")||previous.optLong("baselineRevision",state.getJSONObject("document").getLong("revision"))!=state.getJSONObject("document").getLong("revision"))throw new IllegalStateException("SKETCH_LIVE_SESSION_CONFLICT");
                db.setTransactionSuccessful();return previous;}
            catch(IllegalArgumentException missing){if(!missing.getMessage().contains("Unknown live drawing session"))throw missing;}
            JSONObject current=get(id);requireRevision(current,state.getJSONObject("document").getLong("revision"));
            JSONObject active=activeDrawingSession(id);
            if(SketchDrawingPolicy.requiresOriginal(active))throw new IllegalStateException("SKETCH_ORIGINAL_SESSION_ACTIVE: end the current drawing session before starting another");
            SketchDrawingPolicy.requireBegin(current,SketchDrawingPolicy.method(state));
            JSONObject metadata=SketchDocument.copy(state);metadata.remove("document");ContentValues values=new ContentValues();values.put("sketch_id",id);values.put("session_id",session);values.put("json",metadata.toString());db.insertOrThrow("sketch_live_sessions",null,values);
            JSONObject result=liveSession(id,session);db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    /** Admission policy follows the newest unended session, inside the caller's transaction. */
    public JSONObject activeDrawingSession(String id) throws Exception {try(Cursor c=db.rawQuery("SELECT session_id FROM sketch_live_sessions WHERE sketch_id=? AND ended=0 ORDER BY sequence DESC LIMIT 1",new String[]{id})){return c.moveToFirst()?liveSession(id,c.getString(0)):null;}}
    /** Ends protected agent authoring and snapshots the human's starting revision under one writer lock. */
    public JSONObject takeOverDrawing(String id)throws Exception{
        db.beginTransaction();try{JSONObject current=get(id),active=activeDrawingSession(id);
            if(SketchDrawingPolicy.requiresOriginal(active)){ContentValues values=new ContentValues();values.put("end_sequence",latestSequence(id));values.put("ended",1);
                JSONObject metadata=new JSONObject(active.toString());metadata.remove("endSequence");metadata.remove("sessionSequence");metadata.put("endedAtMs",System.currentTimeMillis());values.put("json",metadata.toString());
                db.update("sketch_live_sessions",values,"sketch_id=? AND session_id=?",new String[]{id,active.getString("sessionId")});}
            db.setTransactionSuccessful();return current;
        }finally{db.endTransaction();}
    }
    public JSONObject liveSession(String id,String session) throws Exception {try(Cursor c=db.rawQuery("SELECT sequence,json,end_sequence,ended FROM sketch_live_sessions WHERE sketch_id=? AND session_id=?",new String[]{id,session})){if(!c.moveToFirst())throw SketchDocument.bad("Unknown live drawing session");JSONObject result=new JSONObject(c.getString(1));return result.put("sessionSequence",c.getLong(0)).put("endSequence",c.getLong(2)).put("ended",c.getInt(3)!=0);}}
    /**
     * Auto-watch current native work, not the oldest unplayed historical stream.
     * The SQL cursor is monotonic; a completed session is eligible for a short
     * catch-up window, while a still-active session remains followable.
     * Legacy sessions with no timestamps are accessible only via explicit replay.
     */
    public JSONObject newestFollowSession(long after,long nowMs)throws Exception{
        try(Cursor c=db.rawQuery("SELECT sketch_id,session_id FROM sketch_live_sessions "
                +"WHERE sequence>? AND consumed=0 ORDER BY sequence DESC LIMIT 16",
                new String[]{String.valueOf(after)})){
            while(c.moveToNext()){
                JSONObject candidate=liveSession(c.getString(0),c.getString(1));
                if(!candidate.optBoolean("ended"))return candidate;
                long endedAt=candidate.optLong("endedAtMs",0);
                if(endedAt>=nowMs-90_000L && endedAt<=nowMs+10_000L
                        && candidate.optLong("endSequence",0)>candidate.optLong("afterSequence",0))
                    return candidate;
            }
            return null;
        }
    }
    /** Old finished drawings only replay after an explicit user choice. */
    public JSONObject latestCompletedLiveSession()throws Exception{
        try(Cursor c=db.rawQuery("SELECT sketch_id,session_id FROM sketch_live_sessions "
                +"WHERE ended=1 ORDER BY sequence DESC LIMIT 1",null)){
            return c.moveToFirst()?liveSession(c.getString(0),c.getString(1)):null;
        }
    }
    public JSONObject nextLiveSession(long after) throws Exception {try(Cursor c=db.rawQuery("SELECT sketch_id,session_id FROM sketch_live_sessions WHERE sequence>? AND consumed=0 ORDER BY sequence LIMIT 1",new String[]{String.valueOf(after)})){return c.moveToFirst()?liveSession(c.getString(0),c.getString(1)):null;}}
    public JSONObject endLiveSession(String id,String session,long revision) throws Exception {
        db.beginTransaction();
        try{
            requireRevision(get(id),revision);
            JSONObject state=liveSession(id,session);
            if(!state.getBoolean("ended")){
                ContentValues values=new ContentValues();
                values.put("end_sequence",latestSequence(id));
                values.put("ended",1);
                JSONObject metadata=new JSONObject(state.toString());
                metadata.remove("endSequence");metadata.remove("sessionSequence");
                metadata.put("endedAtMs",System.currentTimeMillis());
                values.put("json",metadata.toString());
                db.update("sketch_live_sessions",values,"sketch_id=? AND session_id=?",
                        new String[]{id,session});
            }
            JSONObject result=liveSession(id,session);
            db.setTransactionSuccessful();
            return result;
        }finally{db.endTransaction();}
    }
    public long latestSequence(String id){try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(sequence),0) FROM sketch_live_events WHERE sketch_id=?",new String[]{id})){c.moveToFirst();return c.getLong(0);}}
    public JSONObject liveEvents(String id,long after,int limit) throws Exception {
        JSONArray events=new JSONArray();long oldest=0;try(Cursor c=db.rawQuery("SELECT COALESCE(MIN(sequence),0) FROM sketch_live_events WHERE sketch_id=?",new String[]{id})){c.moveToFirst();oldest=c.getLong(0);}
        try(Cursor c=db.rawQuery("SELECT sequence,base_revision,revision,length(CAST(operations AS BLOB)) FROM sketch_live_events WHERE sketch_id=? AND sequence>? ORDER BY sequence LIMIT ?",new String[]{id,String.valueOf(after),String.valueOf(limit)})){int bytes=0;while(c.moveToNext()){int length=c.getInt(3);if(bytes+length>512*1024&&events.length()>0)break;String json=SQLiteLargeText.read(db,"SELECT operations FROM sketch_live_events WHERE sketch_id=? AND sequence=?",id,String.valueOf(c.getLong(0)));bytes+=length;events.put(new JSONObject().put("sequence",c.getLong(0)).put("baseRevision",c.getLong(1)).put("revision",c.getLong(2)).put("operations",new JSONArray(json)));}}
        return new JSONObject().put("sketchId",id).put("events",events).put("latestSequence",latestSequence(id)).put("oldestSequence",oldest);
    }
    public void consumeLiveSession(long sequence){ContentValues values=new ContentValues();values.put("consumed",1);db.update("sketch_live_sessions",values,"sequence=? AND ended=1",new String[]{String.valueOf(sequence)});}
    private void trimLiveEvents(String id){
        long protectedAfter=Long.MAX_VALUE;try(Cursor active=db.rawQuery("SELECT json FROM sketch_live_sessions WHERE sketch_id=? AND consumed=0",new String[]{id})){while(active.moveToNext())try{protectedAfter=Math.min(protectedAfter,new JSONObject(active.getString(0)).getLong("afterSequence"));}catch(Exception invalid){throw new IllegalStateException("Live session metadata changed",invalid);}}
        long bytes=0;int count=0;java.util.ArrayList<Long> remove=new java.util.ArrayList<>();try(Cursor c=db.rawQuery("SELECT sequence,length(CAST(operations AS BLOB)) FROM sketch_live_events WHERE sketch_id=? ORDER BY sequence DESC",new String[]{id})){while(c.moveToNext()){bytes+=c.getLong(1);count++;if(c.getLong(0)<=protectedAfter&&(count>10000||bytes>12L*1024*1024))remove.add(c.getLong(0));}}for(Long seq:remove)db.delete("sketch_live_events","sequence=?",new String[]{seq.toString()});
    }
    public long revision(String id) {try(Cursor c=db.rawQuery("SELECT revision FROM sketches WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getLong(0):-1;}}
    public JSONArray list() throws Exception {
        JSONArray out=new JSONArray();try(Cursor c=db.rawQuery("SELECT summary FROM sketches ORDER BY rowid DESC LIMIT 128",null)){while(c.moveToNext())out.put(new JSONObject(c.getString(0)));}return out;
    }
    private static JSONObject summary(JSONObject doc) throws Exception {return new JSONObject().put("sketchId",doc.getString("id")).put("name",doc.getString("name")).put("revision",doc.getLong("revision")).put("width",doc.getInt("width")).put("height",doc.getInt("height")).put("fps",doc.getInt("fps")).put("frameCount",doc.getInt("frameCount"));}
    public void copyDrawingToPage(JSONObject source,JSONObject target) throws Exception {
        String targetId=target.getString("id");
        if(SketchDrawingPolicy.requiresOriginal(activeDrawingSession(targetId)))throw new IllegalStateException("SKETCH_ORIGINAL_DRAWING_REQUIRED: copied page contents are not authored drawing steps");
        JSONObject drawing=SketchDocument.copy(source);drawing.put("id",targetId).put("name",target.getString("name"));JSONArray layers=drawing.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){JSONObject layer=layers.getJSONObject(i);layer.put("transform",SketchDocument.transformAt(layer,0)).put("keys",new JSONArray());JSONArray items=layer.getJSONArray("objects");for(int j=items.length()-1;j>=0;j--){JSONObject o=items.getJSONObject(j);if(!SketchFrameRenderer.exposed(o,0)){items.remove(j);continue;}o.put("frame",0).put("exposure",1).put("transform",SketchDocument.transformAt(o,0)).put("keys",new JSONArray());}}
        drawing.put("frameCount",1);JSONObject selected=SketchDocument.copy(drawing).put("id",source.getString("id"));copyOwnedDependencies(selected,targetId);java.util.ArrayList<PaintIntent> seeds=paintIntents(drawing);
        mutateWithPaintIntents(targetId,java.util.UUID.randomUUID().toString(),target.getLong("revision"),SketchDocument.sha256(drawing.toString().getBytes(StandardCharsets.UTF_8)),ignored->drawing,null,null,seeds);
    }
    /** Worker-only preparation. Owns all dependencies before any target document is committed. */
    public void copyOwnedDependencies(JSONObject source,String targetSketchId)throws Exception {
        SketchDocument.id(new JSONObject().put("id",targetSketchId),"id");verifyReferences(source);String sourceId=source.getString("id");
        java.util.HashSet<String> references=new java.util.HashSet<>(),paints=new java.util.HashSet<>();JSONArray layers=source.getJSONArray("layers");
        try(SketchPaintStore assets=new SketchPaintStore(context,sourceId)){
            for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);
                if("image".equals(object.getString("kind"))&&references.add(object.getJSONObject("reference").getString("sha256")))copyReference(sourceId,targetSketchId,object.getJSONObject("reference"));
                if("paint".equals(object.getString("kind"))&&paints.add(object.getJSONObject("paint").getString("sha256")))assets.copyTo(targetSketchId,object.getJSONObject("paint"));
            }}
        }
    }
    private void copyReference(String sourceId,String targetId,JSONObject reference)throws Exception {
        File original=referenceFile(sourceId,reference),root=new File(context.getFilesDir(),"sketch_studio/"+targetId+"/references");
        if(!root.getCanonicalPath().equals(context.getFilesDir().getCanonicalPath()+"/sketch_studio/"+targetId+"/references"))throw SketchDocument.bad("Owned reference copy path rejected");
        if(!root.isDirectory()&&!root.mkdirs())throw new java.io.IOException("Reference copy storage unavailable");File target=new File(root,reference.getString("file"));
        if(!target.getCanonicalPath().equals(new File(root.getCanonicalFile(),target.getName()).getPath()))throw SketchDocument.bad("Owned reference copy path rejected");
        String hash=reference.getString("sha256");if(target.exists()){if(!hash.equals(fileHash(target)))throw new IllegalStateException("SKETCH_REFERENCE_CHANGED");return;}
        File staged=File.createTempFile("copy-",".png",root);try{try(InputStream in=new FileInputStream(original);FileOutputStream out=new FileOutputStream(staged)){byte[] buffer=new byte[32768];int n;long total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>8L*1024*1024)throw SketchDocument.bad("Reference copy exceeds 8 MiB");out.write(buffer,0,n);}out.getFD().sync();}if(!hash.equals(fileHash(staged)))throw new IllegalStateException("SKETCH_REFERENCE_CHANGED");if(!staged.renameTo(target)){if(!target.isFile()||!hash.equals(fileHash(target)))throw new java.io.IOException("Reference copy publication failed");}}finally{staged.delete();}
    }
    /** Imports staged owned assets under a fresh identity, without publishing a partly restored document. */
    public JSONObject importOwnedDocument(JSONObject source,String operationId)throws Exception {
        JSONObject drawing=SketchDocument.copy(source);String id=SketchDocument.id(drawing,"id");SketchDocument.id(new JSONObject().put("id",operationId),"id");drawing.put("revision",1);SketchDocument.validate(drawing);
        String fingerprint=SketchDocument.sha256(("sketch_owned_import\n"+SketchDocument.canonical(drawing)).getBytes(StandardCharsets.UTF_8));JSONObject previous=receipt(id,operationId,fingerprint);if(previous!=null)return previous;
        verifyReferences(drawing);java.util.ArrayList<PaintIntent> seeds=paintIntents(drawing);db.beginTransaction();try{previous=receipt(id,operationId,fingerprint);if(previous!=null){db.setTransactionSuccessful();return previous;}
            if(revision(id)>0)throw new IllegalStateException("SKETCH_ALREADY_EXISTS");try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM sketches",null)){count.moveToFirst();if(count.getInt(0)>=2000000)throw new IllegalStateException("SKETCH_STORAGE_BUDGET");}
            if(db.insert("sketches",null,values(id,drawing,0,1))<0)throw new IllegalStateException("SKETCH_ALREADY_EXISTS");history(id,0,drawing);seedPaintIntents(id,seeds);JSONObject result=result(drawing,false,false);saveReceipt(id,operationId,fingerprint,result);db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    public void restoreDraft(JSONObject args,JSONObject drawing) throws Exception {
        verifyReferences(drawing);java.util.ArrayList<PaintIntent> seeds=paintIntents(drawing);db.beginTransaction();try{create(args,SketchDocument.sha256(args.toString().getBytes(StandardCharsets.UTF_8)));mutateWithPaintIntents(args.getString("sketchId"),"restore",1,SketchDocument.sha256(drawing.toString().getBytes(StandardCharsets.UTF_8)),ignored->drawing,null,null,seeds);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public JSONObject create(JSONObject args, String fingerprint) throws Exception {
        String id = SketchDocument.id(args, "sketchId"), op = SketchDocument.id(args, "operationId");
        db.beginTransaction();
        try {
            JSONObject previous = receipt(id, op, fingerprint);
            if (previous != null) { db.setTransactionSuccessful(); return previous; }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sketches", null)) {
                c.moveToFirst(); if (c.getInt(0) >= 2000000) throw new IllegalStateException("SKETCH_STORAGE_BUDGET: at most 2,000,000 sketch documents");
            }
            JSONObject doc = SketchDocument.create(args);
            ContentValues values = values(id, doc, 0, 1);
            if (db.insert("sketches", null, values) < 0) throw new IllegalStateException("SKETCH_ALREADY_EXISTS");
            history(id, 0, doc); JSONObject result = result(doc, false, false); saveReceipt(id, op, fingerprint, result);
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    public JSONObject mutate(String id,String operationId,long expectedRevision,String fingerprint,Mutation mutation) throws Exception {return mutate(id,operationId,expectedRevision,fingerprint,mutation,null);}
    public JSONObject mutate(String id, String operationId, long expectedRevision, String fingerprint, Mutation mutation,JSONArray liveOperations) throws Exception {
        return mutateWithPaintIntents(id,operationId,expectedRevision,fingerprint,mutation,liveOperations,null,null);
    }
    public JSONObject mutatePrepared(String id,String operationId,long expectedRevision,String fingerprint,Mutation mutation,JSONArray liveOperations,JSONObject paintDetails)throws Exception {
        return mutateWithPaintIntents(id,operationId,expectedRevision,fingerprint,mutation,liveOperations,paintDetails,null);
    }
    public JSONObject mutatePrepared(String id,String operationId,long expectedRevision,String fingerprint,Mutation mutation,JSONArray liveOperations,JSONObject paintDetails,JSONObject inheritedPaintObjects)throws Exception {
        java.util.ArrayList<PaintIntent> seeds=inheritedPaintObjects==null?null:paintIntents(inheritedPaintObjects);
        return mutateWithPaintIntents(id,operationId,expectedRevision,fingerprint,mutation,liveOperations,paintDetails,seeds);
    }
    private JSONObject mutateWithPaintIntents(String id,String operationId,long expectedRevision,String fingerprint,Mutation mutation,JSONArray liveOperations,JSONObject paintDetails,java.util.ArrayList<PaintIntent> seeds)throws Exception {
        db.beginTransaction();
        try {
            JSONObject previous = receipt(id, operationId, fingerprint);
            if (previous != null) { db.setTransactionSuccessful(); return previous; }
            requireReceiptCapacity(id);
            JSONObject current = get(id);
            requireRevision(current, expectedRevision);
            JSONObject next = mutation.apply(SketchDocument.copy(current));
            if(SketchDrawingPolicy.requiresOriginal(activeDrawingSession(id))&&(liveOperations==null||liveOperations.length()==0))throw new IllegalStateException("SKETCH_ORIGINAL_DRAWING_REQUIRED: every drawing change must have recorded operations");
            next.put("id", id).put("revision", Math.addExact(expectedRevision, 1));
            SketchLayeredDrawing.requireTransition(current,next,liveOperations);
            SketchDocument.validate(next); verifyReferences(next);
            SketchLayeredDrawing.requireReviewPixels(context,next,liveOperations);
            SketchDrawingPolicy.requireDocument(activeDrawingSession(id),current,next);
            recordPaintIntents(id,liveOperations);if(seeds!=null)seedPaintIntents(id,seeds);
            int position;
            try (Cursor c = db.rawQuery("SELECT position,next_position FROM sketches WHERE id=?", new String[]{id})) {
                c.moveToFirst(); position = c.getInt(1);
                db.delete("sketch_history", "sketch_id=? AND position>?", new String[]{id, Integer.toString(c.getInt(0))});
            }
            history(id, position, next);
            if (db.update("sketches", values(id, next, position, position + 1), "id=? AND revision=?", new String[]{id, Long.toString(expectedRevision)}) != 1)
                throw new IllegalStateException("SKETCH_REVISION_CONFLICT");
            trimHistory(id, position);
            JSONObject result = result(next, canMove(id, position, false), false);
            if(paintDetails!=null){SketchDocument.fields(paintDetails,"paintChanges,authoredStagePromotions");JSONArray changes=paintDetails.getJSONArray("paintChanges");if(changes.length()>64)throw SketchDocument.bad("At most 64 paint changes per receipt");result.put("paintChanges",new JSONArray(changes.toString()));
                if(paintDetails.has("authoredStagePromotions")){JSONArray promoted=paintDetails.getJSONArray("authoredStagePromotions");if(promoted.length()<1||promoted.length()>64)throw SketchDocument.bad("Reuse receipts require1..64 owned marks");for(int i=0;i<promoted.length();i++){JSONObject pin=promoted.getJSONObject(i);SketchDocument.fields(pin,"sourceObjectId,objectId,partId,layerId,sourcePaintSha256");JSONObject source=SketchDocument.object(current,SketchDocument.id(pin,"sourceObjectId")),target=SketchDocument.object(next,SketchDocument.id(pin,"objectId"));SketchDocument.id(pin,"partId");if(!source.getString("kind").equals(target.getString("kind"))||!SketchDocument.owner(next,target.getString("id")).getString("id").equals(SketchDocument.id(pin,"layerId")))throw SketchDocument.bad("Authored stage receipt ownership changed");if("paint".equals(source.getString("kind"))){String hash=source.getJSONObject("paint").getString("sha256");if(!hash.equals(pin.getString("sourcePaintSha256"))||!hash.equals(target.getJSONObject("paint").getString("sha256")))throw SketchDocument.bad("Authored stage paint identity changed");}else if(pin.has("sourcePaintSha256"))throw SketchDocument.bad("Pen reuse cannot claim a paint snapshot");}result.put("authoredStagePromotions",new JSONArray(promoted.toString()));}
            }
            if(liveOperations!=null&&(liveOperations.length()>32768||liveOperations.toString().getBytes(StandardCharsets.UTF_8).length>8*1024*1024))throw new IllegalStateException("SKETCH_LIVE_EVENT_BUDGET: split generated tracing into smaller atomic batches");
            if(liveOperations!=null&&liveOperations.length()>0){if(context.getFilesDir().getUsableSpace()<16L*1024*1024+liveOperations.toString().getBytes(StandardCharsets.UTF_8).length*3L)throw new IllegalStateException("SKETCH_LIVE_STORAGE_FULL");ContentValues event=new ContentValues();event.put("sketch_id",id);event.put("base_revision",expectedRevision);event.put("revision",next.getLong("revision"));event.put("operations",liveOperations.toString());db.insertOrThrow("sketch_live_events",null,event);trimLiveEvents(id);}
            saveReceipt(id, operationId, fingerprint, result);
            // Stop All can arrive after preparation, validation or history writes.
            // Roll the entire uncommitted edit back; an existing receipt above still replays.
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("SKETCH_EDIT_CANCELLED: drawing change rolled back before commit; retry the operation ID to recover any committed receipt");
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    /** A short revision/receipt admission snapshot; expensive immutable painting follows outside SQLite. */
    public MutationPreflight preflightMutation(String id,String operationId,long expectedRevision,String fingerprint,JSONArray operations)throws Exception {
        db.beginTransactionNonExclusive();try{JSONObject old=receipt(id,operationId,fingerprint);if(old!=null){db.setTransactionSuccessful();return new MutationPreflight(null,old);}requireReceiptCapacity(id);JSONObject drawing=get(id);requireRevision(drawing,expectedRevision);java.util.HashSet<String> inBatch=new java.util.HashSet<>();
            for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(!"paint_stroke".equals(op.optString("type")))continue;String object=SketchDocument.id(op,"objectId"),stroke=SketchDocument.id(op,"strokeId"),key=object+"/"+stroke;if(!inBatch.add(key)||hasPaintIntent(id,object,stroke))throw new IllegalStateException("SKETCH_PAINT_STROKE_ID_CONFLICT: choose a new strokeId; retry the original operationId to read its receipt");}
            db.setTransactionSuccessful();return new MutationPreflight(drawing,null);
        }finally{db.endTransaction();}
    }
    public static final class MutationPreflight {
        public final JSONObject document,receipt;
        private MutationPreflight(JSONObject document,JSONObject receipt)throws Exception {this.document=document==null?null:SketchDocument.copy(document);this.receipt=receipt==null?null:SketchDocument.copy(receipt);}
    }
    private static final class PaintIntent {
        final String objectId,strokeId,fingerprint;
        PaintIntent(String objectId,String strokeId,String fingerprint){this.objectId=objectId;this.strokeId=strokeId;this.fingerprint=fingerprint;}
    }
    private boolean hasPaintIntent(String id,String object,String stroke){try(Cursor c=db.rawQuery("SELECT 1 FROM sketch_paint_strokes WHERE sketch_id=? AND object_id=? AND stroke_id=?",new String[]{id,object,stroke})){return c.moveToFirst();}}
    private void recordPaintIntents(String id,JSONArray visualOperations)throws Exception {
        if(visualOperations==null)return;for(int i=0;i<visualOperations.length();i++){JSONObject event=visualOperations.getJSONObject(i);if(!"paint_stroke".equals(event.optString("type")))continue;
            String object=SketchDocument.id(event,"objectId"),stroke=SketchDocument.id(event,"strokeId");if(hasPaintIntent(id,object,stroke))throw new IllegalStateException("SKETCH_PAINT_STROKE_ID_CONFLICT");JSONObject record=event.getJSONObject("stroke");String intent=SketchDocument.sha256(SketchDocument.canonical(record.getJSONObject("request")).getBytes(StandardCharsets.UTF_8));ContentValues values=paintIntentValues(id,new PaintIntent(object,stroke,intent));db.insertOrThrow("sketch_paint_strokes",null,values);
        }
    }
    private static ContentValues paintIntentValues(String id,PaintIntent intent){ContentValues values=new ContentValues();values.put("sketch_id",id);values.put("object_id",intent.objectId);values.put("stroke_id",intent.strokeId);values.put("intent_hash",intent.fingerprint);return values;}
    private java.util.ArrayList<PaintIntent> paintIntents(JSONObject document)throws Exception {
        java.util.ArrayList<PaintIntent> out=new java.util.ArrayList<>();java.util.HashSet<String> ids=new java.util.HashSet<>();JSONArray layers=document.getJSONArray("layers");SketchPaintStore assets=null;
        try{
            for(int i=0;i<layers.length();i++){
                JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");
                for(int j=0;j<objects.length();j++){
                    JSONObject object=objects.getJSONObject(j);if(!"paint".equals(object.getString("kind")))continue;
                    if(assets==null)assets=new SketchPaintStore(context,document.getString("id"));
                    // A single checked closure walk avoids rescanning journal ancestry for every page.
                    java.util.LinkedHashMap<String,File> dependencies=assets.dependencies(object.getJSONObject("paint"));
                    for(java.util.Map.Entry<String,File> entry:dependencies.entrySet()){
                        if(!entry.getKey().startsWith("paint/strokes/"))continue;byte[] bytes;
                        try(InputStream input=new FileInputStream(entry.getValue())){java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[32768];int n,total=0;while((n=input.read(buffer))!=-1){total+=n;if(total>512*1024)throw SketchDocument.bad("Paint journal exceeds 512 KiB");encoded.write(buffer,0,n);}bytes=encoded.toByteArray();}
                        String filename=entry.getValue().getName();if(!filename.equals(SketchDocument.sha256(bytes)+".json"))throw new IllegalStateException("SKETCH_PAINT_JOURNAL_CHANGED");
                        JSONObject record=new JSONObject(new String(bytes,StandardCharsets.UTF_8));String stroke=SketchDocument.id(record,"strokeId"),key=object.getString("id")+"/"+stroke;
                        String intent=SketchDocument.sha256(SketchDocument.canonical(record.getJSONObject("request")).getBytes(StandardCharsets.UTF_8));
                        if(ids.add(key)){if(out.size()>=32768)throw new IllegalStateException("SKETCH_PAINT_IMPORT_JOURNAL_BUDGET: split this drawing into pages before copying");out.add(new PaintIntent(object.getString("id"),stroke,intent));}
                    }
                }
            }
            return out;
        }finally{if(assets!=null)assets.close();}
    }
    private void seedPaintIntents(String id,java.util.ArrayList<PaintIntent> intents)throws Exception {
        for(PaintIntent intent:intents){try(Cursor c=db.rawQuery("SELECT intent_hash FROM sketch_paint_strokes WHERE sketch_id=? AND object_id=? AND stroke_id=?",new String[]{id,intent.objectId,intent.strokeId})){if(c.moveToFirst()){if(!intent.fingerprint.equals(c.getString(0)))throw new IllegalStateException("SKETCH_PAINT_STROKE_ID_CONFLICT");continue;}}db.insertOrThrow("sketch_paint_strokes",null,paintIntentValues(id,intent));}
    }
    public JSONObject historyMove(String id, String operationId, long expectedRevision, String fingerprint, boolean redo) throws Exception {
        db.beginTransaction();
        try {
            JSONObject previous = receipt(id, operationId, fingerprint);
            if (previous != null) { db.setTransactionSuccessful(); return previous; }
            requireReceiptCapacity(id);
            JSONObject current = get(id); requireRevision(current, expectedRevision);
            int position, nextPosition;
            try (Cursor c = db.rawQuery("SELECT position,next_position FROM sketches WHERE id=?", new String[]{id})) {
                c.moveToFirst(); position = c.getInt(0); nextPosition = c.getInt(1);
            }
            String sql = "SELECT position FROM sketch_history WHERE sketch_id=? AND position" + (redo ? ">? ORDER BY position ASC" : "<? ORDER BY position DESC") + " LIMIT 1";
            JSONObject restored; int target;
            try (Cursor c = db.rawQuery(sql, new String[]{id, Integer.toString(position)})) {
                if (!c.moveToFirst()) throw new IllegalStateException(redo ? "SKETCH_REDO_EMPTY" : "SKETCH_UNDO_EMPTY");
                target = c.getInt(0);
                restored = new JSONObject(SQLiteLargeText.read(db, "SELECT json FROM sketch_history WHERE sketch_id=? AND position=?", id, Integer.toString(target)));
            }
            if(!current.has("layeredDrawing")&&!restored.has("layeredDrawing"))SketchDrawingPolicy.requireAction(activeDrawingSession(id),redo?"sketch_redo":"sketch_undo",new JSONObject());
            restored.put("revision", Math.addExact(expectedRevision, 1)); SketchLayeredDrawing.requireHistoryTransition(current,restored);SketchDocument.validate(restored); verifyReferences(restored);
            if (db.update("sketches", values(id, restored, target, nextPosition), "id=? AND revision=?", new String[]{id, Long.toString(expectedRevision)}) != 1)
                throw new IllegalStateException("SKETCH_REVISION_CONFLICT");
            JSONObject result = result(restored, canMove(id, target, false), canMove(id, target, true));
            saveReceipt(id, operationId, fingerprint, result); db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }
    public static void requireRevision(JSONObject doc, long expected) throws Exception {
        if (expected < 1 || doc.getLong("revision") != expected) throw new IllegalStateException("SKETCH_REVISION_CONFLICT: reopen the sketch before editing");
    }
    private static ContentValues values(String id, JSONObject doc, int position, int next) throws Exception {
        ContentValues values = new ContentValues(); values.put("id", id); values.put("revision", doc.getLong("revision"));
        values.put("json", doc.toString()); values.put("summary",summary(doc).toString()); values.put("position", position); values.put("next_position", next); return values;
    }
    private void history(String id, int position, JSONObject doc) {
        ContentValues values = new ContentValues(); values.put("sketch_id", id); values.put("position", position); values.put("json", doc.toString());
        if (db.insertOrThrow("sketch_history", null, values) < 0) throw new IllegalStateException("Sketch history save failed");
    }
    private boolean canMove(String id, int position, boolean forward) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sketch_history WHERE sketch_id=? AND position" + (forward ? ">?" : "<?") + " LIMIT 1", new String[]{id, Integer.toString(position)})) { return c.moveToFirst(); }
    }
    private void trimHistory(String id, int current) {
        int count = 0; long bytes = 0; java.util.ArrayList<Integer> remove = new java.util.ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT position,length(CAST(json AS BLOB)) FROM sketch_history WHERE sketch_id=? ORDER BY position DESC", new String[]{id})) {
            while (c.moveToNext()) { count++; bytes += c.getLong(1); if (c.getInt(0) != current && (count > HISTORY_ENTRIES || bytes > HISTORY_BYTES)) remove.add(c.getInt(0)); }
        }
        for (Integer position : remove) db.delete("sketch_history", "sketch_id=? AND position=?", new String[]{id, position.toString()});
    }
    boolean hasOperation(String id,String operationId){try(Cursor c=db.rawQuery("SELECT 1 FROM sketch_operations WHERE sketch_id=? AND operation_id=?",new String[]{id,operationId})){return c.moveToFirst();}}
    /** Reads an exact authoritative receipt for recovery; never accepts or replays an operation. */
    public JSONObject operationReceipt(String id,String operationId,String fingerprint)throws Exception {
        SketchDocument.id(new JSONObject().put("id",id),"id");SketchDocument.id(new JSONObject().put("id",operationId),"id");if(fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw SketchDocument.bad("Operation receipt requires a canonical request fingerprint");JSONObject saved=receipt(id,operationId,fingerprint);return saved==null?null:SketchDocument.copy(saved);
    }
    private JSONObject receipt(String id, String op, String fingerprint) throws Exception {
        try (Cursor c = db.rawQuery("SELECT fingerprint,result FROM sketch_operations WHERE sketch_id=? AND operation_id=?", new String[]{id, op})) {
            if (!c.moveToFirst()) return null;
            if (!fingerprint.equals(c.getString(0))) throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT: operationId was used for a different request");
            return new JSONObject(c.getString(1));
        }
    }
    private void requireReceiptCapacity(String id) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sketch_operations WHERE sketch_id=?", new String[]{id})) {
            c.moveToFirst(); if (c.getInt(0) >= 8192) throw new IllegalStateException("SKETCH_OPERATION_BUDGET: duplicate this sketch to continue after 8192 operations");
        }
    }
    private void saveReceipt(String id, String op, String fingerprint, JSONObject result) {
        ContentValues values = new ContentValues(); values.put("sketch_id", id); values.put("operation_id", op);
        values.put("fingerprint", fingerprint); values.put("result", result.toString()); db.insertOrThrow("sketch_operations", null, values);
    }
    private static JSONObject result(JSONObject doc, boolean undo, boolean redo) throws Exception {
        return new JSONObject().put("sketchId", doc.getString("id")).put("revision", doc.getLong("revision"))
                .put("documentSha256", SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8)))
                .put("canUndo", undo).put("canRedo", redo).put("persisted", true);
    }

    public File referenceFile(String sketchId, JSONObject reference) throws Exception {
        String id = SketchDocument.id(new JSONObject().put("id", sketchId), "id");
        String hash = reference.getString("sha256"), name = reference.getString("file");
        if (!hash.matches("[a-f0-9]{64}") || !name.equals(hash + ".png")) throw SketchDocument.bad("Reference path/hash rejected");
        File root = new File(context.getFilesDir(), "sketch_studio/" + id + "/references");
        File file = new File(root, name);
        if (!root.getCanonicalPath().equals(context.getFilesDir().getCanonicalPath() + "/sketch_studio/" + id + "/references") ||
                !file.getCanonicalPath().equals(new File(root.getCanonicalFile(), name).getPath()) || !file.isFile() || file.length() > 8 * 1024 * 1024)
            throw new IllegalStateException("SKETCH_REFERENCE_UNAVAILABLE");
        if (!hash.equals(fileHash(file))) throw new IllegalStateException("SKETCH_REFERENCE_CHANGED");
        return file;
    }
    public void verifyReferences(JSONObject doc) throws Exception {
        java.util.HashSet<String> verified = new java.util.HashSet<>(); JSONArray layers = doc.getJSONArray("layers");
        java.util.HashMap<String,JSONArray> support=new java.util.HashMap<>();SketchPaintStore assets=null;try{for (int i = 0; i < layers.length(); i++) {
            JSONArray objects = layers.getJSONObject(i).getJSONArray("objects");
            for (int j = 0; j < objects.length(); j++) {
                JSONObject object = objects.getJSONObject(j);
                if ("image".equals(object.getString("kind")) && verified.add(object.getJSONObject("reference").getString("sha256")))
                    referenceFile(doc.getString("id"), object.getJSONObject("reference"));
                if("paint".equals(object.getString("kind"))&&verified.add("paint:"+object.getJSONObject("paint").getString("sha256"))){if(assets==null)assets=new SketchPaintStore(context,doc.getString("id"));assets.verify(object.getJSONObject("paint"));}
                if("paint".equals(object.getString("kind"))&&object.has("paintMesh")&&object.getJSONObject("paintMesh").has("sourceRect")){String sha=object.getJSONObject("paint").getString("sha256");JSONArray required=support.get(sha);if(required==null){if(assets==null)assets=new SketchPaintStore(context,doc.getString("id"));Bitmap bitmap=assets.renderSource(object.getJSONObject("paint"));try{required=SketchPaintMeshPrepare.supportRectangle(bitmap);support.put(sha,required);}finally{bitmap.recycle();}}SketchPaintMeshPrepare.requireSupport(object,required);}
            }
        }}finally{if(assets!=null)assets.close();}
    }
    /** Called inside a revision-checked transaction. Copies/reencodes; never modifies the user original. */
    public JSONObject importReference(String sketchId, ProjectStore.Project project, ProjectStore.Asset asset) throws Exception {
        SketchDocument.id(new JSONObject().put("id", sketchId), "id");
        if (asset == null || asset.mime == null || !asset.mime.startsWith("image/")) throw SketchDocument.bad("Owned source image asset required");
        Uri uri = Uri.parse(asset.uri);
        File root = new File(context.getFilesDir(), "sketch_studio/" + sketchId + "/references");
        if (!root.getCanonicalPath().equals(context.getFilesDir().getCanonicalPath() + "/sketch_studio/" + sketchId + "/references")) throw SketchDocument.bad("Owned reference directory rejected");
        long ownedBytes = 0; File[] retained = root.listFiles(); if (retained != null) for (File file : retained) { if (file.isFile()) ownedBytes += file.length(); }
        if (ownedBytes > 128L * 1024 * 1024 || context.getFilesDir().getUsableSpace() < 64L * 1024 * 1024) throw new IllegalStateException("SKETCH_REFERENCE_STORAGE_BUDGET");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Sketch reference storage unavailable");
        File staged = File.createTempFile("reference-", ".input", root), encoded = null; Bitmap bitmap = null;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(staged)) {
                if (in == null) throw new IllegalStateException("Source image unavailable");
                byte[] buffer = new byte[32768]; int n; long count = 0;
                while ((n = in.read(buffer)) != -1) { count += n; if (count > 32L * 1024 * 1024) throw SketchDocument.bad("Source image exceeds 32 MiB"); out.write(buffer, 0, n); }
                out.getFD().sync();
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeFile(staged.getPath(), options);
            if (options.outWidth < 1 || options.outHeight < 1 || (long) options.outWidth * options.outHeight > 64_000_000L)
                throw SketchDocument.bad("Source image dimensions exceed decode budget");
            int sourceWidth=options.outWidth,sourceHeight=options.outHeight,orientation=ExifInterface.ORIENTATION_NORMAL;
            if(!"image/bmp".equals(options.outMimeType)&&!"image/gif".equals(options.outMimeType)){
                orientation=new ExifInterface(staged.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
                if(orientation==ExifInterface.ORIENTATION_UNDEFINED)orientation=ExifInterface.ORIENTATION_NORMAL;
                if(orientation<1||orientation>8)throw SketchDocument.bad("Source image EXIF orientation is invalid");
            }
            String sourceSha256=fileHash(staged);
            int orientedWidth=orientation>=5?sourceHeight:sourceWidth,orientedHeight=orientation>=5?sourceWidth:sourceHeight;
            double ratio=Math.min(1,2048d/Math.max(orientedWidth,orientedHeight));
            final int targetWidth=Math.max(1,(int)Math.round(orientedWidth*ratio)),targetHeight=Math.max(1,(int)Math.round(orientedHeight*ratio));
            // ImageDecoder applies all EXIF rotations/mirrors and allocates the exact
            // bounded target. BitmapFactory power-of-two sampling discarded up to
            // half the available detail, even for a modest portrait photograph.
            bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(staged),(decoder,info,input)->{
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSize(targetWidth,targetHeight);
                decoder.setOnPartialImageListener(error->false);
            });
            if(bitmap.getWidth()!=targetWidth||bitmap.getHeight()!=targetHeight)
                throw SketchDocument.bad("Source image decode did not preserve its oriented aspect");
            encoded = File.createTempFile("reference-", ".png", root);
            try (FileOutputStream out = new FileOutputStream(encoded)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Reference PNG encoding failed"); out.getFD().sync(); }
            if (encoded.length() > 8L * 1024 * 1024) throw SketchDocument.bad("Owned reference exceeds 8 MiB");
            String hash = fileHash(encoded); File target = new File(root, hash + ".png");
            if (!target.exists() && ownedBytes + encoded.length() > 128L * 1024 * 1024) throw new IllegalStateException("SKETCH_REFERENCE_STORAGE_BUDGET");
            if (target.exists()) { if (!hash.equals(fileHash(target))) throw new IllegalStateException("SKETCH_REFERENCE_CHANGED"); }
            else if (!encoded.renameTo(target)) throw new IllegalStateException("Atomic reference publication failed");
            return new JSONObject().put("file", target.getName()).put("sha256", hash).put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                    .put("sourceProjectId", project.id).put("sourceAssetId", asset.id)
                    .put("sourceWidth",sourceWidth).put("sourceHeight",sourceHeight).put("sourceOrientation",orientation).put("sourceSha256",sourceSha256);
        } finally { staged.delete(); if (encoded != null) encoded.delete(); if (bitmap != null) bitmap.recycle(); }
    }
    public static String fileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768];
        try (InputStream in = new FileInputStream(file)) { int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
        StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
}
