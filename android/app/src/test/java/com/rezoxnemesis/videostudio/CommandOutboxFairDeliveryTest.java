package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

/** SQLite result ACK fairness on the installed app database, not an HTTP mock. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class CommandOutboxFairDeliveryTest {
    @Test public void pendingBatchBoundsMemoryWhileRetainingEveryLargeReceipt() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String payload="x".repeat(700000);
        try(CommandOutbox q=new CommandOutbox(context)){
            for(int n=0;n<10;n++)q.put(command("heavy_"+n,n+1),new JSONObject().put("ok",true).put("payload",payload),"completed");
            JSONArray first=q.pending();assertTrue("ACK reads must not load sixteen large bodies at once",first.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4*1024*1024);assertEquals(10,q.count());
            for(int n=0;n<first.length();n++)q.defer(first.getJSONObject(n).getString("id"));
            assertEquals("heavy_"+first.length(),q.pending().getJSONObject(0).getString("id"));assertEquals(10,q.count());
        }
    }
    @Test public void receiptLargerThanCursorWindowDoesNotBlockSmallAcknowledgements() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String large=UUID.randomUUID().toString(),small=UUID.randomUUID().toString();
        String payload="\uD83C\uDFA8".repeat(800000);
        try(CommandOutbox q=new CommandOutbox(context)){
            q.put(command(large,1),new JSONObject().put("ok",true).put("artwork",payload),"completed");
            q.put(command(small,2),new JSONObject().put("ok",true),"completed");
            JSONArray pending=q.pending();assertEquals(2,pending.length());assertEquals(payload,pending.getJSONObject(0).getJSONObject("result").getString("artwork"));
            q.defer(large);assertEquals(small,q.pending().getJSONObject(0).getString("id"));q.acknowledge(small);assertTrue(q.contains(large));
        }
    }
    private static JSONObject command(String id,int seq)throws Exception{
        return new JSONObject().put("id",id).put("seq",seq).put("action","self_test");
    }
    private static boolean has(JSONArray all,String id){
        for(int i=0;i<all.length();i++)
            if(id.equals(all.optJSONObject(i).optString("id")))return true;
        return false;
    }
    @Test public void failedOldReceiptCannotStarveNewNativeReceipt()throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String old=UUID.randomUUID().toString(),newer=UUID.randomUUID().toString();
        try(CommandOutbox q=new CommandOutbox(context)){
            q.put(command(old,10000),new JSONObject().put("ok",true).put("kind","old"),"completed");
            q.put(command(newer,10001),new JSONObject().put("ok",true).put("kind","new"),"completed");
            assertTrue(has(q.pending(),old));
            assertTrue(has(q.pending(),newer));
            q.defer(old);
            assertTrue(q.contains(old));
            assertFalse("Old ACK temporarily deferred",has(q.pending(),old));
            assertTrue("New ACK remains deliverable",has(q.pending(),newer));
            q.acknowledge(newer);
            assertFalse(q.contains(newer));
            assertTrue(q.contains(old));
            q.acknowledge(old);
        }
    }
    @Test public void repeatedFailureDoesNotEraseOwnerReceipt()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        String id=UUID.randomUUID().toString();
        try(CommandOutbox q=new CommandOutbox(context)){
            q.put(command(id,10002),new JSONObject().put("ok",true)
                    .put("note","immutable saved cinematic program"),"completed");
            for(int i=0;i<8;i++)q.defer(id);
            assertTrue(q.contains(id));
            q.acknowledge(id);
            assertFalse(q.contains(id));
        }
    }
}
