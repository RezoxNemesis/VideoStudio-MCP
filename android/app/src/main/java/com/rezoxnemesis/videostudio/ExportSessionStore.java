package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Durable owner export sessions, independent from autonomous queue/status cards. */
public final class ExportSessionStore {
    public static final class Session {
        public String id,projectId,name,state="preparing",detail="Starting foreground export",jobId="",uri="";
        public long projectRevision,createdAt=System.currentTimeMillis(),updatedAt=createdAt;
        public int progress;
        public boolean verified;
        public JSONObject settings=new JSONObject();
        public JSONObject outputProof=new JSONObject();
        JSONObject json(){
            JSONObject o=new JSONObject();try{
                o.put("id",id);o.put("projectId",projectId);o.put("name",name);o.put("projectRevision",projectRevision);
                o.put("state",state);o.put("detail",detail);o.put("progress",progress);o.put("jobId",jobId);
                o.put("uri",uri);o.put("verified",verified);o.put("settings",settings);o.put("outputProof",outputProof);o.put("createdAt",createdAt);o.put("updatedAt",updatedAt);
            }catch(Exception error){throw new IllegalStateException(error);}return o;
        }
        static Session from(JSONObject o){
            Session s=new Session();s.id=o.optString("id");s.projectId=o.optString("projectId");s.name=o.optString("name");
            s.projectRevision=o.optLong("projectRevision");s.state=o.optString("state","preparing");s.detail=o.optString("detail");
            s.progress=o.optInt("progress");s.jobId=o.optString("jobId");s.uri=o.optString("uri");s.verified=o.optBoolean("verified");
            s.settings=o.optJSONObject("settings");if(s.settings==null)s.settings=new JSONObject();
            s.outputProof=o.optJSONObject("outputProof");if(s.outputProof==null)s.outputProof=new JSONObject();
            s.createdAt=o.optLong("createdAt");s.updatedAt=o.optLong("updatedAt");return s;
        }
        public boolean terminal(){return "completed".equals(state)||"failed".equals(state)||"cancelled".equals(state);}
    }
    private static final class Db extends SQLiteOpenHelper{
        Db(Context c){super(c.getApplicationContext(),"videostudio_exports.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE exports(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,json TEXT NOT NULL,snapshot TEXT NOT NULL,created_at INTEGER NOT NULL)");}
        @Override public void onUpgrade(SQLiteDatabase db,int old,int next){}
    }
    private final SQLiteDatabase db;
    public ExportSessionStore(Context c){db=new Db(c).getWritableDatabase();db.enableWriteAheadLogging();}
    public synchronized Session create(ProjectStore.Project p,JSONObject settings){
        if(p==null)throw new IllegalArgumentException("Project is required");
        Session s=new Session();s.id=UUID.randomUUID().toString();s.projectId=p.id;s.projectRevision=p.revision;s.name=p.name;
        try{s.settings=new JSONObject(settings.toString());}catch(Exception error){throw new IllegalArgumentException(error);}
        ContentValues row=new ContentValues();row.put("id",s.id);row.put("project_id",p.id);row.put("json",s.json().toString());
        row.put("snapshot",p.toJson().toString());row.put("created_at",s.createdAt);db.insertOrThrow("exports",null,row);return s;
    }
    public synchronized Session get(String id){
        try(Cursor c=db.query("exports",new String[]{"json"},"id=?",new String[]{id},null,null,null)){
            if(!c.moveToFirst())return null;return Session.from(new JSONObject(c.getString(0)));
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
    }
    public synchronized ProjectStore.Project project(String id){
        try(Cursor c=db.query("exports",new String[]{"snapshot"},"id=?",new String[]{id},null,null,null)){
            if(!c.moveToFirst())throw new IllegalArgumentException("Export session not found");return ProjectStore.Project.fromJson(new JSONObject(c.getString(0)));
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
    }
    public synchronized Session latest(String projectId){
        try(Cursor c=db.query("exports",new String[]{"json"},"project_id=?",new String[]{projectId},null,null,"created_at DESC","1")){
            if(!c.moveToFirst())return null;return Session.from(new JSONObject(c.getString(0)));
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
    }
    public synchronized JSONArray resumable(){
        JSONArray out=new JSONArray();try(Cursor c=db.query("exports",new String[]{"json"},null,null,null,null,"created_at ASC")){
            while(c.moveToNext()){Session s=Session.from(new JSONObject(c.getString(0)));if(!s.terminal())out.put(s.json());}
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}return out;
    }
    private Session required(String id){Session s=get(id);if(s==null)throw new IllegalArgumentException("Export session not found");return s;}
    private void write(Session s){s.updatedAt=System.currentTimeMillis();ContentValues row=new ContentValues();row.put("json",s.json().toString());db.update("exports",row,"id=?",new String[]{s.id});}
    private interface Change { void apply(Session session) throws Exception; }
    private void change(String id,Change mutation){
        // A monitor only protects one Java object. The SQLite write transaction also
        // serializes Activity and Service instances before either reads the row.
        db.beginTransaction();
        try{Session s=required(id);mutation.apply(s);write(s);db.setTransactionSuccessful();}
        catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
        finally{db.endTransaction();}
    }
    public synchronized void bindJob(String id,String jobId){change(id,s->{if(!s.terminal())s.jobId=jobId;});}
    public synchronized void progress(String id,String state,int percent,String detail){
        if(!java.util.Arrays.asList("preparing","running","checkpointed","verifying","waiting_memory","waiting_thermal","waiting_storage","waiting_network").contains(state))throw new IllegalArgumentException("Invalid export progress state");
        change(id,s->{if(!s.terminal()){s.state=state;s.progress=Math.max(0,Math.min(99,percent));s.detail=detail;}});
    }
    public synchronized void cancel(String id){change(id,s->{if(!s.terminal()){s.state="cancelled";s.detail="Export cancelled";}});}
    public synchronized void fail(String id,String detail){change(id,s->{if(!s.terminal()){s.state="failed";s.detail=detail;}});}
    /** Persist the session-owned destination before bytes are copied; resume verifies it. */
    public synchronized void stageOutput(String id,String uri){
        stageOutput(id,uri,new JSONObject());
    }
    public synchronized void stageOutput(String id,String uri,JSONObject expectedProof){
        if(uri==null||uri.isEmpty())throw new IllegalArgumentException("Output URI is required");
        change(id,s->{if(s.terminal())throw new IllegalStateException("Export is "+s.state);s.uri=uri;s.verified=false;
            s.outputProof=new JSONObject(expectedProof.toString());s.outputProof.put("uri",uri);});
    }
    public synchronized void discardOutput(String id){change(id,s->{if(!s.terminal()){s.uri="";s.verified=false;s.outputProof=new JSONObject();}});}
    public synchronized void recordProof(String id,JSONObject proof){
        if(proof==null||!proof.optBoolean("playable")||proof.optString("uri").isEmpty())throw new IllegalArgumentException("Playable output proof is required");
        change(id,s->{if(s.terminal())throw new IllegalStateException("Export is "+s.state);
            s.uri=proof.getString("uri");s.outputProof=new JSONObject(proof.toString());s.state="verifying";s.progress=99;});
    }
    public synchronized void finish(String id,String uri,boolean verified){
        if(!verified || uri==null || uri.isEmpty())throw new IllegalArgumentException("Verified playable output is required");
        change(id,s->{
            if("completed".equals(s.state)&&uri.equals(s.uri)&&s.verified)return;
            if(s.terminal())throw new IllegalStateException("Export is "+s.state);
            s.state="completed";s.progress=100;s.detail="Playable export verified";s.uri=uri;s.verified=true;
        });
    }
}
