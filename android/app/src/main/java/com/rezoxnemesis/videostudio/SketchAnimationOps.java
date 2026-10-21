package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Shared human/agent animation persistence. Prepared artwork is frozen; baking publishes a new sketch. */
public final class SketchAnimationOps {
    private static final Object LOCK=new Object();
    private static final int PROGRAM_BYTES=512*1024,REPORT_BYTES=64*1024,ANIMATION_COUNT=128,OPERATIONS_PER_ANIMATION=8192;
    private static final long STORE_BYTES=256L*1024*1024;
    private static final String RECORD_SCHEMA="videostudio.sketch-animation.saved.v1";
    private SketchAnimationOps(){}

    /** Public commands return bounded metadata/programs, never a full source or compiled drawing in an ACK. */
    public static JSONObject execute(Context context,String action,JSONObject args)throws Exception {
        if(context==null||args==null)throw SketchDocument.bad("Animation context and arguments are required");
        if("sketch_animation_place_in_book".equals(action))return SketchAnimationBookPlacement.place(context,args,null);
        synchronized(LOCK){try(Store store=new Store(context)){
            switch(action){
                case "sketch_animation_save":return store.save(args);
                case "sketch_animation_get":return store.readRequest(args);
                case "sketch_animation_list":SketchDocument.fields(args,"");return store.list();
                case "sketch_animation_bake":return store.bake(args);
                default:throw SketchDocument.bad("Unknown sketch animation action");
            }
        }}
    }

    /** Native UI only: frozen geometry stays inside the app instead of traversing MCP transport. */
    public static JSONObject load(Context context,String animationId)throws Exception {
        synchronized(LOCK){SketchDocument.id(new JSONObject().put("id",animationId),"id");try(Store store=new Store(context)){return store.read(animationId,true);}}
    }

    private static String hash(JSONObject object)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(object).getBytes(StandardCharsets.UTF_8));}
    private static int bytes(JSONObject object){return object.toString().getBytes(StandardCharsets.UTF_8).length;}
    private static String intent(String action,JSONObject args)throws Exception{return SketchDocument.sha256((action+"\n"+SketchDocument.canonical(args)).getBytes(StandardCharsets.UTF_8));}

    private static final class Db extends SQLiteOpenHelper {
        Db(Context context){super(context,"sketch_animation_v1.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE animations(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,json TEXT NOT NULL,summary TEXT NOT NULL)");
            db.execSQL("CREATE TABLE animation_operations(animation_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,result TEXT,payload TEXT,PRIMARY KEY(animation_id,operation_id))");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported animation storage upgrade");}
    }

    private static final class Store implements AutoCloseable {
        final Context context;final Db helper;final SQLiteDatabase db;final SketchProjectStore sketches;
        Store(Context context){this.context=context.getApplicationContext();helper=new Db(this.context);db=helper.getWritableDatabase();sketches=new SketchProjectStore(this.context);}
        @Override public void close(){try{sketches.close();}finally{helper.close();}}

        JSONObject save(JSONObject args)throws Exception {
            SketchDocument.fields(args,"animationId,sketchId,sourceRevision,expectedRevision,operationId,program");
            String id=SketchDocument.id(args,"animationId"),sourceId=SketchDocument.id(args,"sketchId"),operation=SketchDocument.id(args,"operationId");
            long expected=SketchDocument.integer(args,"expectedRevision",0,Long.MAX_VALUE-2),sourceRevision=SketchDocument.integer(args,"sourceRevision",1,Long.MAX_VALUE-1);
            JSONObject program=args.getJSONObject("program");if(bytes(program)>PROGRAM_BYTES)throw SketchDocument.bad("Animation program exceeds 512 KiB budget");
            String fingerprint=intent("save",args);db.beginTransaction();try{
                JSONObject previous=operation(id,operation,fingerprint);if(previous!=null){db.setTransactionSuccessful();return previous.getJSONObject("result");}
                requireRevision(id,expected);requireOperationCapacity(id);
                JSONObject existing=expected>0?record(id):null;boolean frozen=existing!=null&&sourceId.equals(existing.getString("sketchId"))&&sourceRevision==existing.getLong("sourceRevision");
                JSONObject source;
                if(frozen){verifyRecord(existing);source=SketchDocument.copy(existing.getJSONObject("sourceDocument"));}
                else {source=sketches.get(sourceId);SketchProjectStore.requireRevision(source,sourceRevision);sketches.verifyReferences(source);}
                JSONObject compiled=SketchAnimationCompiler.compile(SketchDocument.copy(source),SketchDocument.copy(program),validationOutputId(sourceId,id));
                JSONObject report=checkedReport(compiled.getJSONObject("report"));
                // A human may edit during compilation. Admit only the requested source revision.
                if(!frozen&&sketches.revision(sourceId)!=sourceRevision)throw new IllegalStateException("SKETCH_REVISION_CONFLICT: prepared artwork changed while saving animation");
                long revision=Math.addExact(expected,1),now=System.currentTimeMillis();JSONObject record=new JSONObject().put("schema",RECORD_SCHEMA)
                        .put("animationId",id).put("revision",revision).put("sketchId",sourceId).put("sourceRevision",sourceRevision)
                        .put("sourceSha256",hash(source)).put("programSha256",hash(program)).put("program",SketchDocument.copy(program))
                        .put("sourceDocument",SketchDocument.copy(source)).put("report",report).put("updatedAt",now);
                JSONObject result=metadata(record);requireStorage(id,bytes(record),bytes(result),expected==0);
                ContentValues values=new ContentValues();values.put("id",id);values.put("revision",revision);values.put("json",record.toString());values.put("summary",result.toString());
                if(expected==0){if(db.insert("animations",null,values)<0)throw new IllegalStateException("SKETCH_ANIMATION_REVISION_CONFLICT");}
                else if(db.update("animations",values,"id=? AND revision=?",new String[]{id,Long.toString(expected)})!=1)throw new IllegalStateException("SKETCH_ANIMATION_REVISION_CONFLICT");
                insertOperation(id,operation,fingerprint,result,null);db.setTransactionSuccessful();return result;
            }finally{db.endTransaction();}
        }

        JSONObject readRequest(JSONObject args)throws Exception {
            SketchDocument.fields(args,"animationId,expectedRevision,sampleFrame");String id=SketchDocument.id(args,"animationId");
            if(args.has("sampleFrame")&&!args.has("expectedRevision"))throw SketchDocument.bad("Pose sampling requires the saved animation expectedRevision");
            Long expected=args.has("expectedRevision")?SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-2):null;
            Integer frame=args.has("sampleFrame")?(int)SketchDocument.integer(args,"sampleFrame",0,1199):null;
            if(expected==null&&frame==null)return read(id,false);
            if(frame!=null)SketchPoseCoordinates.worker();JSONObject record=record(id);
            if(expected!=null&&record.getLong("revision")!=expected)throw new IllegalStateException("SKETCH_ANIMATION_REVISION_CONFLICT: expected "+expected+", actual "+record.getLong("revision"));
            verifyRecord(record);JSONObject result=metadata(record).put("program",SketchDocument.copy(record.getJSONObject("program")));
            if(frame!=null)result.put("poseSample",SketchPoseCoordinates.sample(record.getJSONObject("sourceDocument"),record.getJSONObject("program"),frame));return result;
        }

        JSONObject read(String id,boolean includeSource)throws Exception {
            JSONObject record=record(id);verifyRecord(record);JSONObject result=metadata(record).put("program",SketchDocument.copy(record.getJSONObject("program")));
            if(includeSource)result.put("sourceDocument",SketchDocument.copy(record.getJSONObject("sourceDocument")));return result;
        }

        JSONObject list()throws Exception {
            JSONArray out=new JSONArray();try(Cursor c=db.rawQuery("SELECT summary FROM animations ORDER BY rowid DESC LIMIT 128",null)){while(c.moveToNext()){JSONObject item=new JSONObject(c.getString(0));item.remove("report");out.put(item);}}
            return new JSONObject().put("animations",out).put("persisted",true);
        }

        JSONObject bake(JSONObject args)throws Exception {
            SketchDocument.fields(args,"animationId,expectedRevision,operationId,outputSketchId");
            String id=SketchDocument.id(args,"animationId"),operation=SketchDocument.id(args,"operationId"),outputId=SketchDocument.id(args,"outputSketchId");
            long expected=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);String fingerprint=intent("bake",args);JSONObject staged;
            db.beginTransaction();try{
                JSONObject previous=operation(id,operation,fingerprint);
                if(previous!=null&&previous.has("result")){db.setTransactionSuccessful();return previous.getJSONObject("result");}
                if(previous!=null){staged=previous.getJSONObject("payload");}
                else {
                    requireRevision(id,expected);requireOperationCapacity(id);JSONObject record=record(id);verifyRecord(record);JSONObject source=record.getJSONObject("sourceDocument");
                    if(outputId.equals(source.getString("id")))throw SketchDocument.bad("Animation output must differ from the prepared source sketch");
                    if(sketches.revision(outputId)>0)throw new IllegalStateException("SKETCH_ALREADY_EXISTS: choose a new outputSketchId");
                    JSONObject compiled=SketchAnimationCompiler.compile(SketchDocument.copy(source),SketchDocument.copy(record.getJSONObject("program")),outputId),drawing=compiled.getJSONObject("document");
                    if(!outputId.equals(drawing.getString("id")))throw new IllegalStateException("SKETCH_ANIMATION_OUTPUT_ID_CHANGED");
                    drawing.put("revision",1);SketchDocument.validate(drawing);JSONObject report=checkedReport(compiled.getJSONObject("report"));
                    JSONObject result=metadata(record).put("report",report).put("outputSketchId",outputId).put("outputRevision",1)
                            .put("documentSha256",hash(drawing)).put("nonDestructive",true).put("editable",true);
                    staged=new JSONObject().put("document",drawing).put("result",result);
                    requireStorage(null,0,bytes(staged)+bytes(result),false);
                    // Dependencies are immutable and owned before the recoverable publication intent exists.
                    sketches.copyOwnedDependencies(source,outputId);sketches.verifyReferences(drawing);
                    requireStorage(null,0,bytes(staged)+bytes(result),false);insertOperation(id,operation,fingerprint,null,staged);
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            JSONObject drawing=staged.getJSONObject("document"),result=staged.getJSONObject("result");
            if(!outputId.equals(drawing.getString("id"))||!outputId.equals(result.getString("outputSketchId"))||!id.equals(result.getString("animationId"))||expected!=result.getLong("revision")||!hash(drawing).equals(result.getString("documentSha256")))throw new IllegalStateException("SKETCH_ANIMATION_STAGED_OUTPUT_CHANGED");
            // Stable import operation ID bridges the two databases: a crash after publication reads
            // the exact original sketch receipt, even if the human has since edited its output.
            String importOperation="animation_"+SketchDocument.sha256((id+"\n"+operation).getBytes(StandardCharsets.UTF_8));
            sketches.verifyReferences(drawing);JSONObject publication=sketches.importOwnedDocument(drawing,importOperation);
            if(!result.getString("documentSha256").equals(publication.getString("documentSha256")))throw new IllegalStateException("SKETCH_ANIMATION_PUBLICATION_CHANGED");
            db.beginTransaction();try{
                JSONObject current=operation(id,operation,fingerprint);if(current==null)throw new IllegalStateException("SKETCH_ANIMATION_PUBLICATION_INTENT_MISSING");
                if(current.has("result")){db.setTransactionSuccessful();return current.getJSONObject("result");}
                ContentValues values=new ContentValues();values.put("result",result.toString());
                if(db.update("animation_operations",values,"animation_id=? AND operation_id=? AND fingerprint=? AND result IS NULL",new String[]{id,operation,fingerprint})!=1)throw new IllegalStateException("SKETCH_ANIMATION_OPERATION_CONFLICT");
                db.setTransactionSuccessful();return result;
            }finally{db.endTransaction();}
        }

        private JSONObject record(String id)throws Exception {
            String json=SQLiteLargeText.read(db,"SELECT json FROM animations WHERE id=?",id);if(json==null)throw new IllegalArgumentException("UNKNOWN_SKETCH_ANIMATION: "+id);
            return new JSONObject(json);
        }
        private void verifyRecord(JSONObject record)throws Exception {
            if(!RECORD_SCHEMA.equals(record.getString("schema")))throw new IllegalStateException("SKETCH_ANIMATION_SNAPSHOT_CHANGED");
            JSONObject source=record.getJSONObject("sourceDocument"),program=record.getJSONObject("program");SketchDocument.validate(source);
            if(!record.getString("sketchId").equals(source.getString("id"))||record.getLong("sourceRevision")!=source.getLong("revision")||!record.getString("sourceSha256").equals(hash(source))||!record.getString("programSha256").equals(hash(program))||bytes(program)>PROGRAM_BYTES)throw new IllegalStateException("SKETCH_ANIMATION_SNAPSHOT_CHANGED");
            sketches.verifyReferences(source);
        }
        private JSONObject metadata(JSONObject record)throws Exception {
            JSONObject source=record.getJSONObject("sourceDocument"),program=record.getJSONObject("program");
            return new JSONObject().put("animationId",record.getString("animationId")).put("revision",record.getLong("revision"))
                    .put("sketchId",record.getString("sketchId")).put("sourceSketchId",record.getString("sketchId")).put("sourceRevision",record.getLong("sourceRevision"))
                    .put("sourceSha256",record.getString("sourceSha256")).put("programSha256",record.getString("programSha256"))
                    .put("name",program.optString("name",source.getString("name"))).put("width",source.getInt("width")).put("height",source.getInt("height"))
                    .put("frameCount",program.getInt("frameCount")).put("fps",program.optInt("fps",source.getInt("fps"))).put("report",SketchDocument.copy(record.getJSONObject("report")))
                    .put("sourceFrozen",true).put("persisted",true).put("updatedAt",record.getLong("updatedAt"));
        }
        private JSONObject operation(String id,String op,String fingerprint)throws Exception {
            try(Cursor c=db.rawQuery("SELECT fingerprint FROM animation_operations WHERE animation_id=? AND operation_id=?",new String[]{id,op})){
                if(!c.moveToFirst())return null;if(!fingerprint.equals(c.getString(0)))throw new IllegalStateException("SKETCH_ANIMATION_OPERATION_ID_CONFLICT: operationId was used for a different request");
            }
            JSONObject out=new JSONObject();String result=SQLiteLargeText.read(db,"SELECT result FROM animation_operations WHERE animation_id=? AND operation_id=?",id,op);
            if(result!=null)return out.put("result",new JSONObject(result));
            String payload=SQLiteLargeText.read(db,"SELECT payload FROM animation_operations WHERE animation_id=? AND operation_id=?",id,op);if(payload!=null)out.put("payload",new JSONObject(payload));
            if(!out.has("result")&&!out.has("payload"))throw new IllegalStateException("SKETCH_ANIMATION_OPERATION_CHANGED");return out;
        }
        private void insertOperation(String id,String operation,String fingerprint,JSONObject result,JSONObject payload){
            ContentValues values=new ContentValues();values.put("animation_id",id);values.put("operation_id",operation);values.put("fingerprint",fingerprint);if(result!=null)values.put("result",result.toString());if(payload!=null)values.put("payload",payload.toString());db.insertOrThrow("animation_operations",null,values);
        }
        private void requireRevision(String id,long expected){
            try(Cursor c=db.rawQuery("SELECT revision FROM animations WHERE id=?",new String[]{id})){long actual=c.moveToFirst()?c.getLong(0):0;if(actual!=expected)throw new IllegalStateException("SKETCH_ANIMATION_REVISION_CONFLICT: expected "+expected+", actual "+actual);}
        }
        private void requireOperationCapacity(String id){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM animation_operations WHERE animation_id=?",new String[]{id})){c.moveToFirst();if(c.getInt(0)>=OPERATIONS_PER_ANIMATION)throw new IllegalStateException("SKETCH_ANIMATION_OPERATION_BUDGET: create a new animation after 8192 operations");}}
        private void requireStorage(String replacingId,long recordBytes,long operationBytes,boolean creating){
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM animations",null)){c.moveToFirst();if(creating&&c.getInt(0)>=ANIMATION_COUNT)throw new IllegalStateException("SKETCH_ANIMATION_STORAGE_BUDGET: at most 128 saved animations");}
            long total;try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(length(CAST(json AS BLOB))),0) FROM animations WHERE id!=?",new String[]{replacingId==null?"":replacingId})){c.moveToFirst();total=c.getLong(0);}
            try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(COALESCE(length(CAST(payload AS BLOB)),0)+COALESCE(length(CAST(result AS BLOB)),0)),0) FROM animation_operations",null)){c.moveToFirst();total+=c.getLong(0);}
            if(total+recordBytes+operationBytes>STORE_BYTES||context.getFilesDir().getUsableSpace()<32L*1024*1024+2*(recordBytes+operationBytes))throw new IllegalStateException("SKETCH_ANIMATION_STORAGE_BUDGET: animation storage is full");
        }
    }
    private static JSONObject checkedReport(JSONObject report)throws Exception {if(bytes(report)>REPORT_BYTES)throw SketchDocument.bad("Animation report exceeds 64 KiB budget");return SketchDocument.copy(report);}
    private static String validationOutputId(String source,String animation)throws Exception {String id="animation_check_"+SketchDocument.sha256(animation.getBytes(StandardCharsets.UTF_8));return source.equals(id)?"animation_validation_output":id;}
}
