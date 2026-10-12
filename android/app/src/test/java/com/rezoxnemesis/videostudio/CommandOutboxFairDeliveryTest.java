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
