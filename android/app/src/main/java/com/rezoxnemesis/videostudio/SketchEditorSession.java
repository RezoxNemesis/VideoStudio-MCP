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
    public interface Listener { void saved(int pending); void failed(String message); default void preparedSaved(JSONObject drawing,JSONArray operations,int pending){saved(pending);} }
    private final Context context;
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final AtomicInteger pending=new AtomicInteger();
    private final java.util.ArrayList<JSONObject> paintIntents=new java.util.ArrayList<>();
    private JSONObject paintBase;
    private int paintPending;
    private static final int INTENT_BYTES=8*1024*1024;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile JSONObject document;
    private volatile String error;
    private volatile Listener listener;
    private final String id;
    public SketchEditorSession(Context c,JSONObject doc) throws Exception {context=c.getApplicationContext();document=SketchDocument.copy(doc);id=doc.getString("id");if(preparedRecoveryFile().isFile())error="SKETCH_LOCAL_PAINT_INTENT_RETAINED: recover your interrupted strokes as a separate sketch";File retained=recoveryFile();if(retained.isFile()){byte[] bytes;try(InputStream in=new AtomicFile(retained).openRead()){bytes=in.readNBytes(SketchDocument.MAX_BYTES+1);}if(bytes.length>SketchDocument.MAX_BYTES)throw SketchDocument.bad("Recovery draft budget exceeded");JSONObject old=new JSONObject(new String(bytes,StandardCharsets.UTF_8));SketchDocument.validate(old);if(!SketchDocument.canonical(old).equals(SketchDocument.canonical(doc)))error="SKETCH_LOCAL_DRAFT_RETAINED: recover the existing drawing as a separate sketch";else new AtomicFile(retained).delete();}}
    public void setListener(Listener value){listener=value;}
    public JSONObject document(){return document;}
    public String error(){return error;}
    public int pending(){return pending.get();}
    public File preparedRecoveryFile(){return new File(context.getFilesDir(),"sketch_studio/"+id+"/local-paint-intent.json");}
    public synchronized boolean hasPreparedSaves(){return paintPending>0;}
    public File recoveryFile(){return new File(context.getFilesDir(),"sketch_studio/"+id+"/local-draft.json");}
    public synchronized void submit(JSONArray source) throws Exception {
        if(error!=null)throw new IllegalStateException("Local draft retained. Recover it as a separate sketch before continuing.");
        if(paintPending>0)throw new IllegalStateException("Your paint strokes are saving. Continue painting or wait before changing vector objects.");
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
    /** Raster requests retain their intent before preparation and become visible as saved only after the native receipt. */
    public synchronized void submitPrepared(JSONArray source) throws Exception {
        if(error!=null)throw new IllegalStateException("Local drawing retained. Recover it as a separate sketch before continuing.");
        if(source.length()<1||source.length()>64)throw SketchDocument.bad("Use 1..64 atomic operations");
        JSONArray operations=new JSONArray(source.toString());
        if(operations.toString().getBytes(StandardCharsets.UTF_8).length>500*1024)throw SketchDocument.bad("Split a paint gesture into smaller strokes");
        if(paintIntents.size()>=64)throw SketchDocument.bad("Finish the current paint saves before starting another gesture");
        JSONObject intent=new JSONObject().put("operationId",UUID.randomUUID().toString()).put("operations",operations);
        int bytes=intent.toString().getBytes(StandardCharsets.UTF_8).length;
        for(JSONObject old:paintIntents)bytes+=old.toString().getBytes(StandardCharsets.UTF_8).length;
        if(bytes>INTENT_BYTES)throw SketchDocument.bad("Local paint intent budget reached; let the current saves finish");
        if(paintIntents.isEmpty())paintBase=SketchDocument.copy(document);
        paintIntents.add(intent);paintPending++;pending.incrementAndGet();
        writer.execute(()->{
            try {
                synchronized(this){intent.put("expectedRevision",document.getLong("revision"));}
                writePaintIntents();
                if(error==null){
                    JSONObject request=new JSONObject().put("sketchId",id).put("operationId",intent.getString("operationId"))
                            .put("expectedRevision",intent.getLong("expectedRevision")).put("operations",intent.getJSONArray("operations"));
                    JSONObject receipt=SketchEditOps.execute(context,"sketch_edit",request);
                    if(!receipt.optBoolean("persisted"))throw new IllegalStateException("Native paint save was not confirmed");
                    JSONObject saved;try(SketchProjectStore store=new SketchProjectStore(context)){saved=store.get(id);}
                    if(saved.getLong("revision")!=receipt.getLong("revision"))throw new IllegalStateException("Your stroke was saved, but another edit arrived. Remaining stroke intent is retained for recovery.");
                    synchronized(this){document=saved;paintIntents.remove(intent);paintBase=saved;}
                    writePaintIntents();
                }
            }catch(Exception|OutOfMemoryError failure){
                error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                try{writePaintIntents();}catch(Exception retained){error+="; stroke intent write failed: "+retained.getMessage();}
            }finally{
                synchronized(this){paintPending--;}
                pending.decrementAndGet();
                main.post(()->{Listener target=listener;if(target!=null){if(error==null)target.preparedSaved(document,intent.optJSONArray("operations"),pending.get());else target.failed(error);}});
            }
        });
    }
    private void writePaintIntents() throws Exception {
        JSONObject base;java.util.ArrayList<JSONObject> snapshot;
        synchronized(this){base=paintBase;snapshot=new java.util.ArrayList<>(paintIntents);}
        AtomicFile retained=new AtomicFile(preparedRecoveryFile());
        if(snapshot.isEmpty()){retained.delete();return;}
        JSONArray requests=new JSONArray();for(JSONObject request:snapshot)requests.put(new JSONObject(request.toString()));
        JSONObject envelope=new JSONObject().put("schema","videostudio.sketch.local-paint-intent.v1").put("sketchId",id).put("baseDocument",base).put("requests",requests);
        byte[] bytes=envelope.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>SketchDocument.MAX_BYTES+INTENT_BYTES+16384)throw new IOException("Local paint recovery budget exceeded");
        atomicWrite(preparedRecoveryFile(),bytes);
    }
    private static void atomicWrite(File file,byte[] bytes) throws Exception {
        File directory=file.getParentFile();if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot retain local drawing intent");
        AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
        try{output=atomic.startWrite();output.write(bytes);atomic.finishWrite(output);}catch(Exception failure){if(output!=null)atomic.failWrite(output);throw failure;}
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
        SketchDocument.id(new JSONObject().put("id",sourceId),"id");File intentFile=new File(context.getFilesDir(),"sketch_studio/"+sourceId+"/local-paint-intent.json");
        if(intentFile.isFile())return recoverPaintAsCopy(context,sourceId,intentFile);
        File file=new File(context.getFilesDir(),"sketch_studio/"+sourceId+"/local-draft.json");
        byte[] bytes;try(InputStream input=new AtomicFile(file).openRead()){bytes=input.readNBytes(SketchDocument.MAX_BYTES+1);}if(bytes.length>SketchDocument.MAX_BYTES)throw SketchDocument.bad("Recovery draft exceeds budget");
        JSONObject draft=new JSONObject(new String(bytes,StandardCharsets.UTF_8));SketchDocument.validate(draft);if(!sourceId.equals(draft.getString("id")))throw SketchDocument.bad("Recovery source mismatch");
        String newId=UUID.nameUUIDFromBytes(("recovered/"+sourceId+"/"+SketchDocument.sha256(bytes)).getBytes(StandardCharsets.UTF_8)).toString();try(SketchProjectStore store=new SketchProjectStore(context)){
            store.copyOwnedDependencies(draft,newId);
            JSONObject args=new JSONObject().put("sketchId",newId).put("operationId","recover").put("name",draft.getString("name").substring(0,Math.min(140,draft.getString("name").length()))+" · recovered");
            draft.put("id",newId).put("name",args.getString("name"));store.restoreDraft(args,draft);store.verifyReferences(store.get(newId));
            // Archive the handled draft only after its copy is durably restored. The original
            // source may then reopen for live work without repeatedly substituting this copy.
            if(!SketchDocument.sha256(bytes).equals(SketchProjectStore.fileHash(file)))throw new IllegalStateException("Recovery draft changed during restore");
            File archiveRoot=new File(file.getParentFile(),"recovered-drafts");if(!archiveRoot.isDirectory()&&!archiveRoot.mkdirs())throw new IOException("Recovery archive unavailable");File archive=new File(archiveRoot,SketchDocument.sha256(bytes)+".json");if(archive.exists()){if(!SketchDocument.sha256(bytes).equals(SketchProjectStore.fileHash(archive)))throw new IllegalStateException("Recovery archive changed");if(!file.delete())throw new IOException("Cannot finish recovery archive");}else if(!file.renameTo(archive))throw new IOException("Cannot archive recovered drawing");
        }
        return newId;
    }
    private static String recoverPaintAsCopy(Context context,String sourceId,File file) throws Exception {
        byte[] bytes;try(InputStream input=new AtomicFile(file).openRead()){bytes=input.readNBytes(SketchDocument.MAX_BYTES+INTENT_BYTES+16385);}
        if(bytes.length>SketchDocument.MAX_BYTES+INTENT_BYTES+16384)throw SketchDocument.bad("Paint recovery intent exceeds budget");
        JSONObject envelope=new JSONObject(new String(bytes,StandardCharsets.UTF_8));SketchDocument.fields(envelope,"schema,sketchId,baseDocument,requests");
        if(!"videostudio.sketch.local-paint-intent.v1".equals(envelope.getString("schema"))||!sourceId.equals(envelope.getString("sketchId")))throw SketchDocument.bad("Paint recovery source mismatch");
        JSONObject draft=envelope.getJSONObject("baseDocument");SketchDocument.validate(draft);
        if(!sourceId.equals(draft.getString("id")))throw SketchDocument.bad("Paint recovery document mismatch");
        JSONArray requests=envelope.getJSONArray("requests");if(requests.length()<1||requests.length()>64)throw SketchDocument.bad("Paint recovery request count rejected");
        String digest=SketchDocument.sha256(bytes),newId=UUID.nameUUIDFromBytes(("recovered-paint/"+sourceId+"/"+digest).getBytes(StandardCharsets.UTF_8)).toString();
        try(SketchProjectStore store=new SketchProjectStore(context)){
            store.copyOwnedDependencies(draft,newId);
            String name=draft.getString("name");name=name.substring(0,Math.min(140,name.length()))+" · recovered";
            JSONObject args=new JSONObject().put("sketchId",newId).put("operationId","recover").put("name",name);
            draft=SketchDocument.copy(draft).put("id",newId).put("name",name);store.restoreDraft(args,draft);
        }
        for(int i=0;i<requests.length();i++){
            JSONObject intent=requests.getJSONObject(i);SketchDocument.fields(intent,"operationId,operations,expectedRevision");SketchDocument.id(intent,"operationId");
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",newId).put("operationId","recover_"+i).put("expectedRevision",2L+i).put("operations",intent.getJSONArray("operations")));
        }
        try(SketchProjectStore store=new SketchProjectStore(context)){store.verifyReferences(store.get(newId));}
        if(!digest.equals(SketchProjectStore.fileHash(file)))throw new IllegalStateException("Paint intent changed while recovering");
        File archived=new File(file.getParentFile(),"recovered-drafts/"+digest+".paint.json");
        if(!archived.getParentFile().isDirectory()&&!archived.getParentFile().mkdirs())throw new IOException("Cannot archive recovered paint intent");
        if(archived.isFile()){if(!digest.equals(SketchProjectStore.fileHash(archived)))throw new IOException("Paint recovery archive mismatch");if(!file.delete())throw new IOException("Cannot finish paint recovery");}
        else if(!file.renameTo(archived))throw new IOException("Cannot archive recovered paint intent");
        return newId;
    }
    @Override public void close(){listener=null;writer.shutdown();}
}
