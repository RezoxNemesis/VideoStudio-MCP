package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;

/** Durable upload intents and verified app-managed locations; pending rows are never availability claims. */
public final class VaultReplicaStore extends SQLiteOpenHelper {
    private final SQLiteDatabase db;
    public VaultReplicaStore(Context context){super(context.getApplicationContext(),"videostudio_vault_replicas.db",null,2);setWriteAheadLoggingEnabled(true);db=getWritableDatabase();}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE replicas(manifest TEXT NOT NULL,object TEXT NOT NULL,profile TEXT NOT NULL,location TEXT NOT NULL,bytes INTEGER NOT NULL,sha TEXT NOT NULL,verified_at INTEGER NOT NULL,token TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(manifest,object,profile))");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){if(old==1&&next==2){db.execSQL("ALTER TABLE replicas ADD COLUMN token TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE replicas ADD COLUMN state TEXT NOT NULL DEFAULT 'verified'");}else throw new IllegalStateException("Unsupported Vault replica migration");}
    public JSONObject get(String manifest,String object,String profile)throws Exception{
        try(Cursor c=db.query("replicas",null,"manifest=? AND object=? AND profile=?",new String[]{manifest,object,profile},null,null,null)){return c.moveToFirst()?row(c):null;}
    }
    public void pending(String manifest,String object,String profile,String location,long bytes,String sha,String token){save(manifest,object,profile,location,bytes,sha,token,"pending",0);}
    public void verified(String manifest,String object,String profile,String location,long bytes,String sha,String token){
        if(location==null||location.isEmpty())throw new IllegalArgumentException("Verified replica requires a location");save(manifest,object,profile,location,bytes,sha,token,"verified",System.currentTimeMillis());
    }
    private void save(String manifest,String object,String profile,String location,long bytes,String sha,String token,String state,long time){
        ContentValues values=new ContentValues();values.put("manifest",manifest);values.put("object",object);values.put("profile",profile);values.put("location",location);values.put("bytes",bytes);values.put("sha",sha);values.put("verified_at",System.currentTimeMillis());
        values.put("token",token);values.put("state",state);values.put("verified_at",time);
        if(db.insertWithOnConflict("replicas",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Could not persist Vault replica state");
    }
    public void forget(String manifest,String object,String profile){db.delete("replicas","manifest=? AND object=? AND profile=?",new String[]{manifest,object,profile});}
    public JSONArray list(String manifest)throws Exception{JSONArray out=new JSONArray();try(Cursor c=db.query("replicas",null,"manifest=?",new String[]{manifest},null,null,"object,profile")){while(c.moveToNext())out.put(row(c));}return out;}
    private JSONObject row(Cursor c)throws Exception{return new JSONObject().put("object",c.getString(c.getColumnIndexOrThrow("object"))).put("profileId",c.getString(c.getColumnIndexOrThrow("profile"))).put("location",c.getString(c.getColumnIndexOrThrow("location"))).put("bytes",c.getLong(c.getColumnIndexOrThrow("bytes"))).put("sha256",c.getString(c.getColumnIndexOrThrow("sha"))).put("verifiedAt",c.getLong(c.getColumnIndexOrThrow("verified_at"))).put("uploadToken",c.getString(c.getColumnIndexOrThrow("token"))).put("state",c.getString(c.getColumnIndexOrThrow("state")));}
}
