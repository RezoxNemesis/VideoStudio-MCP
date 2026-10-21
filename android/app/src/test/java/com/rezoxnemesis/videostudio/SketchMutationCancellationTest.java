package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.InterruptedIOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Interruption after a prepared mutation returns must roll back document/history/receipt together. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchMutationCancellationTest {
 @Test public void cancellationAtStoreAdmissionRollsBackAndSameIdentityCanBeRetried()throws Exception {
  ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{
   Context context=RuntimeEnvironment.getApplication();String id="cancel_at_store";
   SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256));
   JSONObject operation=new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","authored_square").put("kind","rect").put("fill",true).put("bounds",new JSONObject().put("x",.2).put("y",.2).put("width",.3).put("height",.3)));
   String fingerprint=SketchDocument.sha256(SketchDocument.canonical(operation).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   try(SketchProjectStore store=new SketchProjectStore(context)){
    JSONObject before=store.get(id);String canonical=SketchDocument.canonical(before);
    try{assertThrows(InterruptedIOException.class,()->store.mutate(id,"prepared_edit",1,fingerprint,doc->{SketchEditOps.apply(doc,operation);Thread.currentThread().interrupt();return doc;},new JSONArray().put(operation)));}finally{Thread.interrupted();}
    assertEquals(canonical,SketchDocument.canonical(store.get(id)));assertNull("Cancelled work must leave no durable receipt",store.operationReceipt(id,"prepared_edit",fingerprint));
    JSONObject receipt=store.mutate(id,"prepared_edit",1,fingerprint,doc->{SketchEditOps.apply(doc,operation);return doc;},new JSONArray().put(operation));assertEquals(2,receipt.getInt("revision"));
    assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(store.mutate(id,"prepared_edit",1,fingerprint,doc->{throw new AssertionError("Committed replay must not rerun preparation");})));
    store.historyMove(id,"undo_retry",2,"undo_retry",false);assertEquals(SketchDocument.canonical(before.getJSONArray("layers")),SketchDocument.canonical(store.get(id).getJSONArray("layers")));
   }return null;
  }catch(Exception e){throw new RuntimeException(e);}}).get(60,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
 }
}
