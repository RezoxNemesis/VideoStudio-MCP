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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class NativeOrphanPortraitRecoveryTest {
    @Test public void aClaimedPortraitWithoutNativeJobIsQuarantinedAfterUpgrade()throws Exception {
        Context ctx=RuntimeEnvironment.getApplication();
        String id="orphan_"+UUID.randomUUID();
        JSONObject command=new JSONObject().put("id",id).put("seq",21)
                .put("action","animate_images");
        CommandJournal journal=new CommandJournal(ctx);
        journal.begin(command);
        assertNotNull(journal.inflight(id));
        JSONArray old=journal.inflightEntries(160);
        for(int i=0;i<old.length();i++)
            assertNotEquals(id,old.getJSONObject(i).optString("id"));
        JSONArray startup=journal.pendingForStartupSafety(160);
        boolean found=false;
        for(int i=0;i<startup.length();i++){
            JSONObject item=startup.getJSONObject(i);
            if(id.equals(item.optString("id"))){
                found=true;
                assertEquals("animate_images",item.getString("action"));
                assertFalse(item.getBoolean("hasNativeJob"));
            }
        }
        assertTrue("Unlinked render commands must participate in restart safety",found);
        NativeStartupGuard guard=new NativeStartupGuard(ctx);
        guard.userRetry();
        try {
            guard.holdInterruptedUpgrade(startup,true);
            assertTrue("Do not automatically restart interrupted native portrait analysis",guard.held());
            assertFalse(guard.mayAutoResume(id,"animate_images",System.currentTimeMillis()));
        } finally {
            journal.finish(command,new JSONObject().put("ok",false),"failed");
            guard.userRetry();
            guard.cleanStop();
        }
        assertNull(journal.inflight(id));
    }

    @Test public void harmlessReadOnlyInspectionsDoNotBlockRecovery()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();
        CommandJournal journal=new CommandJournal(ctx);
        NativeStartupGuard guard=new NativeStartupGuard(ctx);
        guard.userRetry();
        String id="review_"+UUID.randomUUID();
        JSONObject command=new JSONObject().put("id",id).put("action","sketch_review");
        journal.begin(command);
        try{
            guard.holdInterruptedUpgrade(journal.pendingForStartupSafety(160),true);
            assertFalse(guard.held());
        } finally{
            journal.finish(command,new JSONObject().put("ok",true),"completed");
            guard.userRetry();
            guard.cleanStop();
        }
    }
}
