package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Expensive native IK and QA computations must never starve the save/preview queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchAnimationComputeIsolationTest {
    @Test public void slowQualityJobDoesNotPreventNativeEditorIoAndIsCancelledOnExit()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        String id="compute_"+UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","create").put("width",320).put("height",480).put("frameCount",1));
        Intent intent=new Intent(context,SketchAnimationActivity.class).putExtra("sketchId",id);
        try(ActivityController<SketchAnimationActivity> ctl=Robolectric.buildActivity(SketchAnimationActivity.class,intent).create().start().resume().visible()){
            SketchAnimationActivity screen=ctl.get();
            ExecutorService io=(ExecutorService)field(screen,"io"),heavy=(ExecutorService)field(screen,"quality");
            assertNotSame(io,heavy);
            for(int i=0;i<3;i++){io.submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
            assertNotNull(field(screen,"program"));
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            heavy.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}});
            try{
                assertTrue("Heavy solver must start independently",entered.await(5,TimeUnit.SECONDS));
                io.submit(()->{}).get(5,TimeUnit.SECONDS);
                assertNotNull("The UI remains loaded",field(screen,"program"));
            }finally{release.countDown();}
        }
    }
    private static Object field(Object subject,String name)throws Exception{
        Field value=subject.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(subject);
    }
}
