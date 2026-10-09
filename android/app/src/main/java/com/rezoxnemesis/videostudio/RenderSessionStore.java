package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONObject;

/** Private durable checkpoints. A process restart retires its previous writer generation. */
final class RenderSessionStore implements AutoCloseable {
    enum Kind {VIDEO,AUDIO}
    interface Verifier {JSONObject verify(File file,boolean requireVideo)throws Exception;}
    interface Faults {void at(String point)throws Exception;}
    static final class Writer implements AutoCloseable {
        final String sessionId,token;
        final long generation;
        private final RenderSessionStore owner;
        private boolean released;
        Writer(RenderSessionStore owner,String sessionId,String token,long generation){this.owner=owner;this.sessionId=sessionId;this.token=token;this.generation=generation;}
        public void close(){owner.release(this);}
    }
    static final class Checkpoint {
        final File file;final JSONObject proof;final long startUs,endUs;
        Checkpoint(File file,JSONObject proof,long startUs,long endUs){this.file=file;this.proof=proof;this.startUs=startUs;this.endUs=endUs;}
    }
    private static final Object LOCK=new Object();
    private static final String PROCESS=UUID.randomUUID().toString();
    private final File root;
    private final String process;
    private final Verifier verifier;
    private final Faults faults;
    private final SQLiteOpenHelper helper;
    private final ArrayList<Writer> writers=new ArrayList<>();
    private boolean closed;

    RenderSessionStore(Context context){this(context,new File(context.getApplicationContext().getFilesDir(),"render_sessions_v1"),PROCESS,
        (file,video)->PlayableMediaVerifier.verify(context.getApplicationContext(),Uri.fromFile(file),video),point->{});}
    RenderSessionStore(Context context,File root,String processIdentity,Verifier verifier,Faults faults){
        if(processIdentity==null||processIdentity.isEmpty()||processIdentity.contains(":"))throw new IllegalArgumentException("Invalid render process identity");
        this.root=root;process=processIdentity;this.verifier=verifier;this.faults=faults;
        if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("Could not create render session storage");
        helper=new SQLiteOpenHelper(context.getApplicationContext(),new File(root,"journal.sqlite").getAbsolutePath(),null,1){
            public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
            public void onCreate(SQLiteDatabase db){
                db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,manifest TEXT NOT NULL,graph TEXT NOT NULL,generation INTEGER NOT NULL,writer TEXT NOT NULL,updated_at INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE checkpoints(session TEXT NOT NULL,kind TEXT NOT NULL,ordinal INTEGER NOT NULL,start_us INTEGER NOT NULL,end_us INTEGER NOT NULL,state TEXT NOT NULL,stage_generation INTEGER NOT NULL,bytes INTEGER NOT NULL,sha TEXT NOT NULL,metadata TEXT NOT NULL,PRIMARY KEY(session,kind,ordinal),FOREIGN KEY(session) REFERENCES sessions(id))");
            }
            public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unsupported render journal version "+oldVersion);}
        };
        helper.setWriteAheadLoggingEnabled(true);
    }

    Writer acquire(RenderSourceIdentity.Snapshot snapshot)throws Exception{
        interrupted();
        if(snapshot==null||snapshot.boundProject==null||snapshot.manifest==null)throw new IllegalArgumentException("Invalid captured render identity");
        // Validate and save the same private copy; caller mutation cannot change the admitted graph later.
        snapshot=new RenderSourceIdentity.Snapshot(ProjectStore.Project.fromJson(snapshot.boundProject.snapshotJson()),new JSONObject(snapshot.manifest.toString()),snapshot.sessionId,snapshot.inputRoot);
        RenderSourceIdentity.validate(snapshot);
        String manifest=RenderSourceIdentity.canonical(snapshot.manifest),graph=RenderSourceIdentity.canonical(snapshot.boundProject.snapshotJson());
        synchronized(LOCK){
            SQLiteDatabase db=database();db.beginTransaction();
            try{
                long generation=1;
                try(Cursor row=db.query("sessions",null,"id=?",new String[]{snapshot.sessionId},null,null,null)){
                    if(row.moveToFirst()){
                        if(!manifest.equals(string(row,"manifest"))||!graph.equals(string(row,"graph")))throw new IllegalArgumentException("Captured graph does not match its saved render identity");
                        String active=string(row,"writer");if(active.startsWith(process+":"))throw new IllegalStateException("This render session already has an active writer");
                        generation=Math.addExact(row.getLong(row.getColumnIndexOrThrow("generation")),1);
                    }
                }
                String token=process+":"+UUID.randomUUID();ContentValues values=new ContentValues();values.put("id",snapshot.sessionId);values.put("manifest",manifest);values.put("graph",graph);values.put("generation",generation);values.put("writer",token);values.put("updated_at",System.currentTimeMillis());
                if(db.update("sessions",values,"id=?",new String[]{snapshot.sessionId})==0)db.insertOrThrow("sessions",null,values);
                db.setTransactionSuccessful();Writer writer=new Writer(this,snapshot.sessionId,token,generation);writers.add(writer);return writer;
            }finally{db.endTransaction();}
        }
    }

    File begin(Writer writer,Kind kind,int index,long startUs,long endUs,JSONObject sourceSeek)throws Exception{
        bounds(kind,index,startUs,endUs);
        synchronized(LOCK){
            SQLiteDatabase db=active(writer);Row prior=row(db,writer,kind,index);
            if(prior!=null&&!prior.state.equals("pending")){
                if(prior.start!=startUs||prior.end!=endUs)throw new IllegalArgumentException("Window bounds differ from the saved checkpoint");
                throw new IllegalStateException("Verify or recover the existing checkpoint before rendering it again");
            }
            if(prior!=null&&prior.stageGeneration==writer.generation)throw new IllegalStateException("This writer already started the checkpoint");
            if(prior!=null&&prior.stageGeneration>0)remove(stage(writer,kind,index,prior.stageGeneration));
            File directory=directory(writer);if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Could not create render checkpoint storage");
            JSONObject metadata=new JSONObject().put("sourceSeek",sourceSeek==null?new JSONObject():new JSONObject(sourceSeek.toString()));
            ContentValues values=new ContentValues();values.put("session",writer.sessionId);values.put("kind",kind.name());values.put("ordinal",index);values.put("start_us",startUs);values.put("end_us",endUs);values.put("state","pending");values.put("stage_generation",writer.generation);values.put("bytes",0);values.put("sha","");values.put("metadata",metadata.toString());
            if(db.insertWithOnConflict("checkpoints",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new android.database.sqlite.SQLiteException("Could not persist render stage intent");
            return stage(writer,kind,index,writer.generation);
        }
    }

    Checkpoint complete(Writer writer,Kind kind,int index,JSONObject producerProof)throws Exception{
        key(kind,index);Row saved;
        synchronized(LOCK){saved=row(active(writer),writer,kind,index);if(saved==null)throw new IllegalStateException("Checkpoint was not started");}
        if(saved.state.equals("verified")){
            if(!matches(producerProof,saved.sha,saved.bytes))throw new IllegalArgumentException("Duplicate completion has different bytes");
            Checkpoint existing=reusable(writer,kind,index,saved.start,saved.end);if(existing==null)throw new IllegalStateException("Completed checkpoint is unavailable");return existing;
        }
        if(!saved.state.equals("pending")||saved.stageGeneration!=writer.generation)throw new IllegalStateException("Checkpoint belongs to another completion or writer generation");
        File stage=stage(writer,kind,index,saved.stageGeneration);
        JSONObject fresh=verifier.verify(stage,kind==Kind.VIDEO);validateProof(fresh,kind);
        if(!matches(producerProof,fresh.getString("sha256"),fresh.getLong("sizeBytes")))throw new IllegalArgumentException("Producer checksum does not match the encoded checkpoint");
        JSONObject metadata=PlayableMediaVerifier.withFreshProof(producerProof,fresh).put("sourceSeek",saved.metadata.optJSONObject("sourceSeek"));metadata.remove("path");
        try(RandomAccessFile sync=new RandomAccessFile(stage,"rw")){sync.getFD().sync();}
        synchronized(LOCK){SQLiteDatabase db=active(writer);unchanged(saved,row(db,writer,kind,index));update(db,writer,kind,index,"encoded",fresh.getLong("sizeBytes"),fresh.getString("sha256"),metadata);}
        faults.at("encoded");
        synchronized(LOCK){SQLiteDatabase db=active(writer);Row encoded=row(db,writer,kind,index);if(encoded==null||!encoded.state.equals("encoded")||encoded.stageGeneration!=saved.stageGeneration||!encoded.sha.equals(fresh.getString("sha256")))throw new IllegalStateException("Encoded checkpoint changed before publication");move(stage,finalFile(writer,kind,index));}
        faults.at("renamed");
        synchronized(LOCK){SQLiteDatabase db=active(writer);Row encoded=row(db,writer,kind,index);if(encoded==null||!encoded.state.equals("encoded")||!encoded.sha.equals(fresh.getString("sha256")))throw new IllegalStateException("Encoded checkpoint changed before commit");update(db,writer,kind,index,"verified",fresh.getLong("sizeBytes"),fresh.getString("sha256"),metadata);}
        return new Checkpoint(finalFile(writer,kind,index),metadata,saved.start,saved.end);
    }

    Checkpoint reusable(Writer writer,Kind kind,int index,long startUs,long endUs)throws Exception{
        bounds(kind,index,startUs,endUs);Row saved;
        synchronized(LOCK){saved=row(active(writer),writer,kind,index);}
        if(saved==null||saved.state.equals("pending")||saved.start!=startUs||saved.end!=endUs)return null;
        File target=finalFile(writer,kind,index),candidate=target;JSONObject fresh=freshIfBound(target,saved,kind);
        if(fresh==null&&saved.state.equals("encoded")){candidate=stage(writer,kind,index,saved.stageGeneration);fresh=freshIfBound(candidate,saved,kind);}
        synchronized(LOCK){
            SQLiteDatabase db=active(writer);unchanged(saved,row(db,writer,kind,index));
            if(fresh==null){
                // Missing or checksum-corrupt managed bytes invalidate this row only. I/O errors propagate above.
                remove(target);remove(stage(writer,kind,index,saved.stageGeneration));
                ContentValues values=new ContentValues();values.put("state","pending");values.put("stage_generation",0);values.put("bytes",0);values.put("sha","");db.update("checkpoints",values,where(),args(writer,kind,index));return null;
            }
            if(!candidate.equals(target))move(candidate,target);
            JSONObject metadata=PlayableMediaVerifier.withFreshProof(saved.metadata,fresh);metadata.remove("path");
            update(db,writer,kind,index,"verified",saved.bytes,saved.sha,metadata);
            return new Checkpoint(target,metadata,saved.start,saved.end);
        }
    }

    JSONObject session(Writer writer)throws Exception{
        synchronized(LOCK){try(Cursor row=active(writer).query("sessions",null,"id=?",new String[]{writer.sessionId},null,null,null)){
            if(!row.moveToFirst())throw new IllegalStateException("Render session disappeared");return new JSONObject().put("project",new JSONObject(string(row,"graph"))).put("manifest",new JSONObject(string(row,"manifest")));
        }}
    }
    private JSONObject freshIfBound(File file,Row saved,Kind kind)throws Exception{
        interrupted();if(!file.isFile()||file.length()!=saved.bytes)return null;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[256*1024];long bytes=0;
        try(FileInputStream input=new FileInputStream(file)){int count;while((count=input.read(buffer))!=-1){interrupted();if(count>0){bytes=Math.addExact(bytes,count);digest.update(buffer,0,count);}}}
        if(bytes!=saved.bytes||!hex(digest.digest()).equals(saved.sha))return null;
        // A decoder or transient read failure must not erase a checksum-valid checkpoint.
        JSONObject proof=verifier.verify(file,kind==Kind.VIDEO);validateProof(proof,kind);return matches(proof,saved.sha,saved.bytes)?proof:null;
    }
    private static void validateProof(JSONObject proof,Kind kind){
        if(proof==null||!proof.optBoolean("playable")||!validSha(proof.optString("sha256"))||proof.optLong("sizeBytes")<=0||proof.optLong("durationMs")<=0||(kind==Kind.VIDEO?!proof.optBoolean("hasVideo"):!proof.optBoolean("hasAudio")))throw new IllegalArgumentException("Checkpoint has no valid playable proof");
    }
    private static boolean matches(JSONObject proof,String sha,long bytes){return proof!=null&&sha.equals(proof.optString("sha256"))&&bytes==proof.optLong("sizeBytes",-1)&&PlayableMediaVerifier.matchesSavedProof(proof,newProof(sha));}
    private static JSONObject newProof(String sha){try{return new JSONObject().put("sha256",sha);}catch(Exception error){throw new IllegalStateException(error);}}
    private static boolean validSha(String sha){return sha!=null&&sha.matches("[0-9a-f]{64}");}
    private static void bounds(Kind kind,int index,long start,long end){key(kind,index);if(start<0||end<=start||(kind==Kind.VIDEO&&end-start>10_000_000)||(kind==Kind.AUDIO&&start!=0))throw new IllegalArgumentException("Invalid checkpoint bounds");}
    private static void key(Kind kind,int index){if(kind==null||index<0||index>1_000_000||(kind==Kind.AUDIO&&index!=0))throw new IllegalArgumentException("Invalid checkpoint key");}
    private File directory(Writer writer){return new File(root,writer.sessionId);}
    private File stage(Writer writer,Kind kind,int index,long generation){return new File(directory(writer),kind.name().toLowerCase(java.util.Locale.ROOT)+"_"+index+"_g"+generation+".part"+(kind==Kind.VIDEO?".mp4":".m4a"));}
    private File finalFile(Writer writer,Kind kind,int index){return new File(directory(writer),kind.name().toLowerCase(java.util.Locale.ROOT)+"_"+index+(kind==Kind.VIDEO?".mp4":".m4a"));}
    private static void move(File source,File target)throws IOException{Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static void remove(File file)throws IOException{if(file.exists()&&!file.delete())throw new IOException("Could not remove corrupt render checkpoint");}
    private SQLiteDatabase database(){if(closed)throw new IllegalStateException("Render journal is closed");return helper.getWritableDatabase();}
    private SQLiteDatabase active(Writer writer)throws InterruptedIOException{
        if(writer==null||writer.owner!=this||writer.released)throw new IllegalStateException("Render writer is retired or belongs to another journal");interrupted();SQLiteDatabase db=database();
        try(Cursor row=db.query("sessions",new String[]{"generation","writer"},"id=?",new String[]{writer.sessionId},null,null,null)){
            if(!row.moveToFirst()||row.getLong(0)!=writer.generation||!row.getString(1).equals(writer.token))throw new IllegalStateException("Render writer generation was replaced");
        }return db;
    }
    private void release(Writer writer){synchronized(LOCK){if(writer.owner!=this||writer.released)return;writer.released=true;writers.remove(writer);if(closed)return;ContentValues values=new ContentValues();values.put("writer","");database().update("sessions",values,"id=? AND generation=? AND writer=?",new String[]{writer.sessionId,Long.toString(writer.generation),writer.token});}}
    public void close(){synchronized(LOCK){if(closed)return;for(Writer writer:new ArrayList<>(writers))release(writer);closed=true;helper.close();}}
    private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Render checkpoint operation cancelled");}
    private static String string(Cursor cursor,String key){return cursor.getString(cursor.getColumnIndexOrThrow(key));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String where(){return "session=? AND kind=? AND ordinal=?";}
    private static String[] args(Writer writer,Kind kind,int index){return new String[]{writer.sessionId,kind.name(),Integer.toString(index)};}
    private static final class Row {
        final long start,end,stageGeneration,bytes;final String state,sha;final JSONObject metadata;
        Row(Cursor row)throws Exception{start=row.getLong(row.getColumnIndexOrThrow("start_us"));end=row.getLong(row.getColumnIndexOrThrow("end_us"));stageGeneration=row.getLong(row.getColumnIndexOrThrow("stage_generation"));bytes=row.getLong(row.getColumnIndexOrThrow("bytes"));state=string(row,"state");sha=string(row,"sha");metadata=new JSONObject(string(row,"metadata"));}
    }
    private static Row row(SQLiteDatabase db,Writer writer,Kind kind,int index)throws Exception{try(Cursor row=db.query("checkpoints",null,where(),args(writer,kind,index),null,null,null)){return row.moveToFirst()?new Row(row):null;}}
    private static void unchanged(Row before,Row after){if(after==null||before.start!=after.start||before.end!=after.end||before.stageGeneration!=after.stageGeneration||!before.state.equals(after.state)||!before.sha.equals(after.sha))throw new IllegalStateException("Checkpoint changed during verification");}
    private static void update(SQLiteDatabase db,Writer writer,Kind kind,int index,String state,long bytes,String sha,JSONObject metadata){ContentValues values=new ContentValues();values.put("state",state);values.put("bytes",bytes);values.put("sha",sha);values.put("metadata",metadata.toString());if(db.update("checkpoints",values,where(),args(writer,kind,index))!=1)throw new IllegalStateException("Checkpoint disappeared");}
}
