package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

final class CodecReliabilityStore extends SQLiteOpenHelper {
    private static final Object LOCK=new Object();
    private final SQLiteDatabase db;
    private final String device=Build.FINGERPRINT;
    CodecReliabilityStore(Context context){super(context.getApplicationContext(),"videostudio_codecs.db",null,1);setWriteAheadLoggingEnabled(true);db=getWritableDatabase();}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE reliability(device TEXT NOT NULL,codec TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,profile TEXT NOT NULL,fps INTEGER NOT NULL,signature TEXT NOT NULL,failures INTEGER NOT NULL,successes INTEGER NOT NULL,sha TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(device,codec,width,height,profile,fps,signature))");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported codec index migration");}
    void failure(String codec,int width,int height,String profile,int fps,String signature){if(codec==null||codec.isEmpty())return;save(codec,width,height,profile,fps,signature,"");}
    void verified(String codec,int width,int height,String profile,int fps,String sha256){
        if(sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Verified codec success requires checksum proof");
        if(codec==null||codec.isEmpty())return;save(codec,width,height,profile,fps,"verified",sha256);
    }
    private String[] config(String codec,int width,int height,String profile,int fps){return new String[]{device,codec,Integer.toString(width),Integer.toString(height),profile,Integer.toString(fps)};}
    private static final String CONFIG="device=? AND codec=? AND width=? AND height=? AND profile=? AND fps=?";
    private void save(String codec,int width,int height,String profile,int fps,String signature,String sha){
        if(codec.length()>256||profile==null||profile.length()>80||signature==null||signature.length()>128||width<1||height<1||fps<1)throw new IllegalArgumentException("Invalid codec configuration");
        synchronized(LOCK){db.beginTransaction();try{
            String[] base=config(codec,width,height,profile,fps),args=java.util.Arrays.copyOf(base,7);args[6]=signature;long failures=0,successes=0;
            try(Cursor c=db.query("reliability",new String[]{"failures","successes"},CONFIG+" AND signature=?",args,null,null,null)){if(c.moveToFirst()){failures=c.getLong(0);successes=c.getLong(1);}}
            ContentValues row=new ContentValues();row.put("device",device);row.put("codec",codec);row.put("width",width);row.put("height",height);row.put("profile",profile);row.put("fps",fps);row.put("signature",signature);
            row.put("failures",sha.isEmpty()?Math.min(Integer.MAX_VALUE,failures+1):failures);row.put("successes",sha.isEmpty()?successes:Math.min(Integer.MAX_VALUE,successes+1));row.put("sha",sha);row.put("updated",System.currentTimeMillis());
            if(db.insertWithOnConflict("reliability",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Could not save codec history");
            db.execSQL("DELETE FROM reliability WHERE rowid NOT IN (SELECT rowid FROM reliability ORDER BY updated DESC,rowid DESC LIMIT 128)");db.setTransactionSuccessful();
        }finally{db.endTransaction();}}
    }
    int penalty(String codec,int width,int height,String profile,int fps){
        try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(failures),0),COALESCE(SUM(successes),0) FROM reliability WHERE "+CONFIG,config(codec,width,height,profile,fps))){c.moveToFirst();return (int)Math.min(Integer.MAX_VALUE,Math.max(0,c.getLong(0)-c.getLong(1)));}
    }
    JSONArray entries()throws Exception{
        JSONArray out=new JSONArray();try(Cursor c=db.query("reliability",null,"device=?",new String[]{device},null,null,"updated DESC,rowid DESC")){while(c.moveToNext()){
            JSONObject row=new JSONObject();for(String key:new String[]{"codec","profile","signature","sha"})row.put(key,c.getString(c.getColumnIndexOrThrow(key)));
            for(String key:new String[]{"width","height","fps","failures","successes","updated"})row.put(key,c.getLong(c.getColumnIndexOrThrow(key)));out.put(row);
        }}return out;
    }
}
