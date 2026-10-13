package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Immediate local presentation and serial durable saves, independent of render queues. */
public final class SketchEditorSession implements AutoCloseable {
    public interface Listener { void saved(int pending); void failed(String message); }
    private final Context context;
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final AtomicInteger pending=new AtomicInteger();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile JSONObject document;
    private volatile String error;
    private volatile Listener listener;
    private final String id;
    public SketchEditorSession(Context c,JSONObject doc) throws Exception {context=c.getApplicationContext();document=SketchDocument.copy(doc);id=doc.getString("id");File retained=recoveryFile();if(retained.isFile()){byte[] bytes;try(InputStream in=new AtomicFile(retained).openRead()){bytes=in.readNBytes(SketchDocument.MAX_BYTES+1);}if(bytes.length>SketchDocument.MAX_BYTES)throw SketchDocument.bad("Recovery draft budget exceeded");JSONObject old=new JSONObject(new String(bytes,StandardCharsets.UTF_8));SketchDocument.validate(old);if(!SketchDocument.canonical(old).equals(SketchDocument.canonical(doc)))error="SKETCH_LOCAL_DRAFT_RETAINED: recover the existing drawing as a separate sketch";else new AtomicFile(retained).delete();}}
    public void setListener(Listener value){listener=value;}
    public JSONObject document(){return document;}
    public String error(){return error;}
    public int pending(){return pending.get();}
    public File recoveryFile(){return new File(context.getFilesDir(),"sketch_studio/"+id+"/local-draft.json");}
    public synchronized void submit(JSONArray source) throws Exception {
        if(error!=null)throw new IllegalStateException("Local draft retained. Recover it as a separate sketch before continuing.");
        if(source.length()<1||source.length()>64)throw SketchDocument.bad("Use 1..64 atomic operations");
        JSONArray ops=new JSONArray(source.toString());JSONObject next=SketchDocument.copy(document);long revision=next.getLong("revision");
        for(int i=0;i<ops.length();i++)SketchEditOps.apply(next,ops.getJSONObject(i));next.put("revision",revision+1);SketchDocument.validate(next);
        JSONObject request=new JSONObject().put("sketchId",id).put("operationId",UUID.randomUUID().toString()).put("expectedRevision",revision).put("operations",ops);
        document=next;pending.incrementAndGet();
        writer.execute(()->{
            try{writeDraft();if(error==null)SketchEditOps.execute(context,"sketch_edit",request);}
            catch(Exception|OutOfMemoryError failure){error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();try{writeDraft();}catch(Exception recoveryFailure){error+="; recovery write failed: "+recoveryFailure.getMessage();}}
            finally{int left=pending.decrementAndGet();if(left==0&&error==null)new AtomicFile(recoveryFile()).delete();main.post(()->{Listener target=listener;if(target!=null){if(error==null)target.saved(pending.get());else target.failed(error);}});}
        });
    }
    private void writeDraft() throws Exception {
        File file=recoveryFile();File root=file.getParentFile();if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot retain local drawing draft");
        byte[] bytes=document.toString().getBytes(StandardCharsets.UTF_8);AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=null;
        try{stream=atomic.startWrite();stream.write(bytes);atomic.finishWrite(stream);}catch(Exception failure){if(stream!=null)atomic.failWrite(stream);throw failure;}
    }
    public boolean adopt(JSONObject incoming) throws Exception {
        if(pending.get()!=0||error!=null)return false;SketchDocument.validate(incoming);if(!id.equals(incoming.getString("id")))throw SketchDocument.bad("Wrong live sketch");document=SketchDocument.copy(incoming);return true;
    }
    public void awaitIdle(long timeoutMs) throws Exception {
        if(writer.isShutdown()){if(!writer.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS))throw new TimeoutException("Sketch saves are still running");}
        else writer.submit(()->{}).get(timeoutMs,TimeUnit.MILLISECONDS);
    }
    public static String recoverAsCopy(Context context,String sourceId) throws Exception {
        SketchDocument.id(new JSONObject().put("id",sourceId),"id");File file=new File(context.getFilesDir(),"sketch_studio/"+sourceId+"/local-draft.json");
        byte[] bytes;try(InputStream input=new AtomicFile(file).openRead()){bytes=input.readNBytes(SketchDocument.MAX_BYTES+1);}if(bytes.length>SketchDocument.MAX_BYTES)throw SketchDocument.bad("Recovery draft exceeds budget");
        JSONObject draft=new JSONObject(new String(bytes,StandardCharsets.UTF_8));SketchDocument.validate(draft);if(!sourceId.equals(draft.getString("id")))throw SketchDocument.bad("Recovery source mismatch");
        String newId=UUID.nameUUIDFromBytes(("recovered/"+sourceId+"/"+SketchDocument.sha256(bytes)).getBytes(StandardCharsets.UTF_8)).toString();try(SketchProjectStore store=new SketchProjectStore(context)){
            store.verifyReferences(draft);File destination=new File(context.getFilesDir(),"sketch_studio/"+newId+"/references");if(!destination.isDirectory()&&!destination.mkdirs())throw new IOException("Recovery storage unavailable");
            JSONArray layers=draft.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject o=objects.getJSONObject(j);if("image".equals(o.getString("kind"))){JSONObject ref=o.getJSONObject("reference");File original=store.referenceFile(sourceId,ref);java.nio.file.Files.copy(original.toPath(),new File(destination,ref.getString("file")).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}}
            JSONObject args=new JSONObject().put("sketchId",newId).put("operationId","recover").put("name",draft.getString("name").substring(0,Math.min(140,draft.getString("name").length()))+" · recovered");
            draft.put("id",newId).put("name",args.getString("name"));store.restoreDraft(args,draft);store.verifyReferences(store.get(newId));
            // Archive the handled draft only after its copy is durably restored. The original
            // source may then reopen for live work without repeatedly substituting this copy.
            if(!SketchDocument.sha256(bytes).equals(SketchProjectStore.fileHash(file)))throw new IllegalStateException("Recovery draft changed during restore");
            File archiveRoot=new File(file.getParentFile(),"recovered-drafts");if(!archiveRoot.isDirectory()&&!archiveRoot.mkdirs())throw new IOException("Recovery archive unavailable");File archive=new File(archiveRoot,SketchDocument.sha256(bytes)+".json");if(archive.exists()){if(!SketchDocument.sha256(bytes).equals(SketchProjectStore.fileHash(archive)))throw new IllegalStateException("Recovery archive changed");if(!file.delete())throw new IOException("Cannot finish recovery archive");}else if(!file.renameTo(archive))throw new IOException("Cannot archive recovered drawing");
        }
        return newId;
    }
    @Override public void close(){listener=null;writer.shutdown();}
}
