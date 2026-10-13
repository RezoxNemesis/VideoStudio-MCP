package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.ContextWrapper;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchLivePlayerLifecycleTest {
    @Test public void anInFlightDatabaseFailureAfterCloseCannotNotifyOrDisableTheWorkspace() throws Exception {
        Context c=RuntimeEnvironment.getApplication();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        // Control the database-open boundary to reproduce an error arriving after close.
        Context delayed=new ContextWrapper(c){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getDatabasePath(String name){entered.countDown();boolean interrupted=false;while(true)try{release.await();break;}catch(InterruptedException ignored){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();throw new IllegalStateException("Delayed database failure");}
        };
        JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",UUID.randomUUID().toString()));
        JSONObject baseline=new JSONObject().put("document",doc).put("sketchId",doc.getString("id")).put("sessionId","session").put("afterSequence",0).put("strokeDurationMs",120);AtomicInteger failures=new AtomicInteger();
        SketchLivePlayer player=new SketchLivePlayer(delayed,baseline,new SketchLivePlayer.Listener(){public void drawing(JSONObject doc,String target,float fraction,String caption){}public void committed(JSONObject doc){}public void idle(){}public void finished(long seq){}public void failed(String message){failures.incrementAndGet();}});
        player.start();ShadowLooper.idleMainLooper();assertTrue(entered.await(5,TimeUnit.SECONDS));player.close();release.countDown();
        java.lang.reflect.Field field=SketchLivePlayer.class.getDeclaredField("reader");field.setAccessible(true);assertTrue(((ExecutorService)field.get(player)).awaitTermination(5,TimeUnit.SECONDS));ShadowLooper.idleMainLooper();assertEquals("Closed players must suppress late failure callbacks",0,failures.get());
    }
}
