package com.rezoxnemesis.videostudio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.UUID;

/** Five independent owner-selected document capabilities; no inferred cloud quota. */
public final class StorageProfileStore extends SQLiteOpenHelper {
    public static final int MAX_PROFILES=5;
    private final SQLiteDatabase db;
    private final Context context;
    public StorageProfileStore(Context context){super(context.getApplicationContext(),"videostudio_storage.db",null,1);this.context=context.getApplicationContext();setWriteAheadLoggingEnabled(true);db=getWritableDatabase();}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE profiles(id TEXT PRIMARY KEY,tree_uri TEXT UNIQUE NOT NULL,json TEXT NOT NULL)");db.execSQL("CREATE TABLE defaults(role TEXT PRIMARY KEY,profile_id TEXT NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported storage profile migration");}
    public synchronized String connect(Uri tree,String label){
        if(tree==null||tree.getAuthority()==null||!"content".equals(tree.getScheme())||!android.provider.DocumentsContract.isTreeUri(tree))throw new IllegalArgumentException("Choose a document-tree folder through the owner picker");
        if(label==null||label.trim().isEmpty()||label.length()>120)throw new IllegalArgumentException("Storage label requires 1–120 characters");
        db.beginTransaction();try{
            String id=null;try(Cursor c=db.query("profiles",new String[]{"id"},"tree_uri=?",new String[]{tree.toString()},null,null,null)){if(c.moveToFirst())id=c.getString(0);}
            JSONObject profile=id==null?null:get(id);if(id==null){if(list().length()>=MAX_PROFILES)throw new IllegalStateException("All five storage profiles are connected");id=UUID.randomUUID().toString();profile=new JSONObject().put("id",id).put("treeUri",tree.toString()).put("authority",tree.getAuthority()).put("provider",provider(tree.getAuthority())).put("connectedAt",System.currentTimeMillis()).put("health","unchecked").put("roles",new JSONArray().put("archive")).put("pinnedProjects",new JSONArray()).put("quotaKnown",false).put("usedBytes",-1).put("totalBytes",-1).put("uploadedBytes",0).put("downloadedBytes",0);}
            profile.put("label",label.trim());save(profile);if(defaultProfile("archive")==null)setDefault("archive",id);db.setTransactionSuccessful();return id;
        }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}finally{db.endTransaction();}
    }
    public synchronized JSONObject get(String id){try(Cursor c=db.query("profiles",new String[]{"json"},"id=?",new String[]{id==null?"":id},null,null,null)){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}catch(Exception error){throw new IllegalStateException(error);}}
    public synchronized JSONArray list(){JSONArray out=new JSONArray();try(Cursor c=db.query("profiles",new String[]{"json"},null,null,null,null,"rowid ASC")){while(c.moveToNext())out.put(new JSONObject(c.getString(0)));return out;}catch(Exception error){throw new IllegalStateException(error);}}
    public synchronized JSONObject defaultProfile(String role){try(Cursor c=db.query("defaults",new String[]{"profile_id"},"role=?",new String[]{role},null,null,null)){return c.moveToFirst()?get(c.getString(0)):null;}}
    public synchronized void setDefault(String role,String id){
        if(!Arrays.asList("source","proxy","cache","export","archive").contains(role)||get(id)==null)throw new IllegalArgumentException("Unknown role or storage profile");ContentValues row=new ContentValues();row.put("role",role);row.put("profile_id",id);db.insertWithOnConflict("defaults",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void setRoles(String id,JSONArray roles){JSONObject p=require(id);try{for(int i=0;i<roles.length();i++)if(!Arrays.asList("source","proxy","cache","export","archive").contains(roles.getString(i)))throw new IllegalArgumentException("Unknown storage role");p.put("roles",roles);save(p);}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}}
    public synchronized void pinProject(String id,String projectId,boolean pinned){JSONObject p=require(id);try{JSONArray next=new JSONArray(),old=p.getJSONArray("pinnedProjects");for(int i=0;i<old.length();i++)if(!projectId.equals(old.getString(i)))next.put(old.getString(i));if(pinned)next.put(projectId);p.put("pinnedProjects",next);save(p);}catch(Exception error){throw new IllegalStateException(error);}}
    public synchronized void reportHealth(String id,boolean connected,String detail){JSONObject p=require(id);try{p.put("health",connected?"connected":"unavailable").put("healthDetail",detail==null?"":detail).put("checkedAt",System.currentTimeMillis());save(p);}catch(Exception error){throw new IllegalStateException(error);}}
    public synchronized void recordTransfer(String id,long uploaded,long downloaded,long elapsedMs){if(uploaded<0||downloaded<0)throw new IllegalArgumentException("Negative transfer size");JSONObject p=require(id);try{p.put("uploadedBytes",StorageBudget.saturatingAdd(p.optLong("uploadedBytes"),uploaded)).put("downloadedBytes",StorageBudget.saturatingAdd(p.optLong("downloadedBytes"),downloaded)).put("lastSpeedBytesPerSecond",elapsedMs<=0?0:Math.round((uploaded+(double)downloaded)*1000d/elapsedMs)).put("lastTransferAt",System.currentTimeMillis());save(p);}catch(Exception error){throw new IllegalStateException(error);}}
    /** Only an authenticated provider adapter can call this with a measured response. */
    public synchronized void reportQuota(String id,long used,long total){if(used<0||total<used)throw new IllegalArgumentException("Invalid provider quota");JSONObject p=require(id);try{p.put("quotaKnown",true).put("usedBytes",used).put("totalBytes",total).put("quotaReportedAt",System.currentTimeMillis());save(p);}catch(Exception error){throw new IllegalStateException(error);}}
    public synchronized void disconnect(String id){JSONObject p=get(id);if(p==null)return;android.content.SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);if(p.optString("treeUri").equals(prefs.getString("drive_workspace_tree_uri","")))prefs.edit().remove("drive_workspace_tree_uri").remove("drive_workspace_linked_at").commit();db.beginTransaction();try{db.delete("defaults","profile_id=?",new String[]{id});db.delete("profiles","id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    private JSONObject require(String id){JSONObject p=get(id);if(p==null)throw new IllegalArgumentException("Storage profile not found");return p;}
    private void save(JSONObject p)throws Exception{ContentValues row=new ContentValues();row.put("id",p.getString("id"));row.put("tree_uri",p.getString("treeUri"));row.put("json",p.toString());db.insertWithOnConflict("profiles",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
    public static String provider(String authority){String a=authority.toLowerCase(java.util.Locale.US);if(a.contains("google")||a.contains("drive"))return "Google Drive document provider";if(a.contains("onedrive"))return "OneDrive document provider";if(a.contains("dropbox"))return "Dropbox document provider";if(a.contains("box.android"))return "Box document provider";if(a.contains("externalstorage"))return "Local / SD / USB";return authority;}
}
