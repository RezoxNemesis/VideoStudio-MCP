package com.rezoxnemesis.videostudio;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Durable ID/intent ledger; payloads and full receipts are encrypted private atomic files. */
final class NativeAgentMailboxStore {
    private final File dir;
    private final String owner;
    private final SQLiteDatabase db;
    NativeAgentMailboxStore(File dir,String owner)throws Exception{
        this.dir=dir;this.owner=owner;if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Mailbox storage unavailable");
        db=SQLiteDatabase.openOrCreateDatabase(new File(dir,"ledger.db"),null);
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS packets (id TEXT PRIMARY KEY, digest TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS commands (position INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,digest TEXT NOT NULL,packet TEXT NOT NULL,ordinal INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'queued',receipt INTEGER NOT NULL DEFAULT 0,retryAt INTEGER NOT NULL DEFAULT 0,page INTEGER NOT NULL DEFAULT 0)");
    }
    synchronized void accept(String envelope,long now)throws Exception{
        JSONObject p=NativeAgentMailboxCrypto.decodePacket(owner,envelope,now);String packet=p.getString("packetId"),digest=NativeAgentMailboxCrypto.sha256(NativeAgentMailboxCrypto.canonical(p));JSONArray commands=p.getJSONArray("commands");
        db.beginTransaction();try{
            try(Cursor c=db.rawQuery("SELECT digest FROM packets WHERE id=?",new String[]{packet})){if(c.moveToFirst()){if(!digest.equals(c.getString(0)))throw new IllegalArgumentException("Packet ID intent conflict");db.setTransactionSuccessful();return;}}
            for(int i=0;i<commands.length();i++){JSONObject cmd=commands.getJSONObject(i);String id=cmd.getString("id"),hash=intent(cmd);try(Cursor c=db.rawQuery("SELECT digest FROM commands WHERE id=?",new String[]{id})){if(c.moveToFirst()&&!hash.equals(c.getString(0)))throw new IllegalArgumentException("Command ID intent conflict");}}
            write(packet+".vscmd",envelope);
            ContentValues pv=new ContentValues();pv.put("id",packet);pv.put("digest",digest);db.insertOrThrow("packets",null,pv);
            for(int i=0;i<commands.length();i++){JSONObject cmd=commands.getJSONObject(i);ContentValues cv=new ContentValues();cv.put("id",cmd.getString("id"));cv.put("digest",intent(cmd));cv.put("packet",packet);cv.put("ordinal",i);db.insertWithOnConflict("commands",null,cv,SQLiteDatabase.CONFLICT_IGNORE);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    static boolean ownsSavedCommand(android.content.Context context,String id)throws Exception{
        File ledger=new File(context.getNoBackupFilesDir(),"agent-mailbox-v1/ledger.db");
        if(!ledger.exists())return false;
        try(SQLiteDatabase read=SQLiteDatabase.openDatabase(ledger.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor c=read.rawQuery("SELECT 1 FROM commands WHERE id=?",new String[]{id})){return c.moveToFirst();}
    }
    private String intent(JSONObject cmd)throws Exception{return NativeAgentMailboxCrypto.sha256(NativeAgentMailboxCrypto.canonical(cmd));}
    synchronized void rejectPacket(String id){ContentValues values=new ContentValues();values.put("id",id);values.put("digest","rejected");db.insertWithOnConflict("packets",null,values,SQLiteDatabase.CONFLICT_IGNORE);}
    synchronized boolean hasPacket(String id){try(Cursor c=db.rawQuery("SELECT 1 FROM packets WHERE id=?",new String[]{id})){return c.moveToFirst();}}
    synchronized boolean owns(String id){try(Cursor c=db.rawQuery("SELECT 1 FROM commands WHERE id=?",new String[]{id})){return c.moveToFirst();}}
    synchronized JSONObject next()throws Exception{
        try(Cursor c=db.rawQuery("SELECT id,packet,ordinal,state FROM commands WHERE state IN ('queued','dispatched') ORDER BY position LIMIT 1",null)){return c.moveToFirst()?command(c):null;}
    }
    synchronized JSONArray recovering()throws Exception{
        JSONArray a=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,packet,ordinal,state FROM commands WHERE state IN ('dispatched','admitted') ORDER BY position",null)){while(c.moveToNext())a.put(command(c));}return a;
    }
    private JSONObject command(Cursor c)throws Exception{
        JSONObject p=NativeAgentMailboxCrypto.decodeStoredPacket(owner,read(c.getString(1)+".vscmd",NativeAgentMailboxCrypto.MAX_OUTER));JSONObject cmd=p.getJSONArray("commands").getJSONObject(c.getInt(2));
        // Transport metadata is created locally after authentication, never accepted from a host.
        cmd.put("_mailboxState",c.getString(3));cmd.put("_mailboxExpiresAtMs",p.getLong("expiresAtMs"));return cmd;
    }
    synchronized String state(String id){try(Cursor c=db.rawQuery("SELECT state FROM commands WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getString(0):"";}}
    synchronized void markDispatched(String id){db.execSQL("UPDATE commands SET state='dispatched' WHERE id=? AND state='queued'",new Object[]{id});}
    synchronized void requeue(String id){db.execSQL("UPDATE commands SET state='queued' WHERE id=? AND state='dispatched'",new Object[]{id});}
    synchronized void admitted(String id){db.execSQL("UPDATE commands SET state='admitted' WHERE id=? AND state!='complete'",new Object[]{id});}
    synchronized void complete(String id,JSONObject result,String status)throws Exception{
        if(!owns(id))throw new IllegalArgumentException("Unknown mailbox command");
        if(receipt(id)!=null){db.execSQL("UPDATE commands SET state='complete',receipt=CASE WHEN receipt=0 THEN 1 ELSE receipt END WHERE id=?",new Object[]{id});return;}
        JSONObject plain=new JSONObject().put("schema",NativeAgentMailboxCrypto.RECEIPT).put("commandId",id).put("status",status).put("result",result).put("completedAtMs",System.currentTimeMillis());
        if(NativeAgentMailboxCrypto.bytes(plain.toString()).length>NativeAgentMailboxCrypto.MAX_PLAIN){
            write(id+".private-result",NativeAgentMailboxCrypto.encodePrivateResult(owner,id,plain).toString());
            // Delivery failure must never imply a completed native side effect should be repeated.
            plain.put("result",new JSONObject().put("resultUnavailable",true)
                    .put("error","RESULT_EXCEEDS_MAILBOX_LIMIT").put("executionStatus",status)
                    .put("doNotRetry",true).put("completeResultSavedPrivately",true));
            plain.put("resultDelivery","oversized; encrypted private backup retained");
        }
        JSONObject e=NativeAgentMailboxCrypto.encodeReceipt(owner,id,plain);write(id+".receipt",e.toString());
        db.execSQL("UPDATE commands SET state='complete',receipt=1,retryAt=0,page=0 WHERE id=?",new Object[]{id});
    }
    synchronized JSONObject receipt(String id)throws Exception{NativeAgentMailboxCrypto.uuid(id);AtomicFile f=new AtomicFile(new File(dir,id+".receipt"));if(!f.getBaseFile().exists()&&!new File(f.getBaseFile()+".bak").exists())return null;return NativeAgentMailboxCrypto.parse(read(id+".receipt",NativeAgentMailboxCrypto.MAX_OUTER),NativeAgentMailboxCrypto.MAX_OUTER);}
    synchronized JSONArray pendingReceipts(long now)throws Exception{JSONArray out=new JSONArray();try(Cursor c=db.rawQuery("SELECT id FROM commands WHERE receipt=1 AND retryAt<=? ORDER BY retryAt,position LIMIT 16",new String[]{Long.toString(now)})){while(c.moveToNext())out.put(c.getString(0));}return out;}
    synchronized void deferReceipt(String id,long until){db.execSQL("UPDATE commands SET retryAt=? WHERE id=?",new Object[]{until,id});}
    synchronized int receiptPage(String id){try(Cursor c=db.rawQuery("SELECT page FROM commands WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getInt(0):0;}}
    synchronized void pageSent(String id,int next,boolean done){db.execSQL("UPDATE commands SET page=?,receipt=? WHERE id=?",new Object[]{next,done?2:1,id});}
    private void write(String name,String value)throws Exception{AtomicFile f=new AtomicFile(new File(dir,name));FileOutputStream stream=null;try{stream=f.startWrite();stream.write(value.getBytes(StandardCharsets.UTF_8));f.finishWrite(stream);}catch(Exception e){if(stream!=null)f.failWrite(stream);throw e;}}
    private String read(String name,int limit)throws Exception{try(InputStream in=new AtomicFile(new File(dir,name)).openRead()){return NativeAgentMailbox.readBounded(in,limit);}}
}
