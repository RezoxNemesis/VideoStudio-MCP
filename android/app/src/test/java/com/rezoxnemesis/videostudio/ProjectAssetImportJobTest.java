package com.rezoxnemesis.videostudio;

import android.content.Context;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,shadows=ProjectAssetImportJobTest.ShadowPlans.class)
public class ProjectAssetImportJobTest {
    @Implements(value=RecoveryPlanStore.class,isInAndroidSdk=false)
    public static class ShadowPlans {
        @RealObject RecoveryPlanStore real;final CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1);final AtomicBoolean once=new AtomicBoolean();volatile boolean block;
        @Implementation protected void checkpointForJob(String id,String stage,int progress,String detail){
            org.robolectric.shadow.api.Shadow.directlyOn(real,RecoveryPlanStore.class,"checkpointForJob",org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,id),org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,stage),org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,progress),org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,detail));
            if(block&&stage.equals("Project media copy")&&progress>3&&progress<100&&once.compareAndSet(false,true)){
                entered.countDown();while(!Thread.currentThread().isInterrupted())LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));interrupted.countDown();
            }
        }
    }
    private final List<Fixture> fixtures=new ArrayList<>();
    private final class Fixture {
        final ProjectAssetImportTest.Fixture media=new ProjectAssetImportTest.Fixture();final Context c=media.context;
        final ControlService service=Robolectric.buildService(ControlService.class).get();final JobManager jobs=new JobManager(c,"project_asset_job");final RecoveryPlanStore plans=new RecoveryPlanStore(c);final CommandJournal journal=new CommandJournal(c);final AppProtocol protocol=new AppProtocol(c,null);final ExecutorService transfers=Executors.newSingleThreadExecutor();
        final ShadowPlans gate=org.robolectric.shadow.api.Shadow.extract(plans);
        Fixture()throws Exception {
            android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();memory.availMem=1024L*1024*1024;memory.threshold=32L*1024*1024;Shadows.shadowOf((android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
            protocol.stop();set(jobs,"transferPool",transfers);set(service,"jobs",jobs);set(service,"recoveryPlans",plans);set(service,"store",media.store);set(service,"protocol",protocol);set(service,"commandJournal",journal);set(service,"serviceAlive",true);set(service,"prefs",c.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));
            media.request.put("appendToTimeline",true);fixtures.add(this);
        }
        JSONObject queue(JSONObject args)throws Exception {return (JSONObject)call(service,"queueProjectAssetImport",new Class<?>[]{JSONObject.class},args);}
        JSONObject job(JSONObject queued)throws Exception {return jobs.get(queued.getString("jobId")).getJSONObject("job");}
        void drain()throws Exception {transfers.submit(()->{}).get(10,TimeUnit.SECONDS);}
        void stop()throws Exception {service.onCommand(new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",10).put("action","stop_all").put("parameters",new JSONObject()));}
        void close()throws Exception {jobs.shutdown();transfers.shutdownNow();transfers.awaitTermination(10,TimeUnit.SECONDS);protocol.stop();for(String name:new String[]{"commandCompletionWatchers","directorSetupExecutor","nativeSourceExecutor","cinematic3DExecutor","authoringExecutor","pipelineExecutor"})((ExecutorService)field(service,name)).shutdownNow();}
    }
    @After public void cleanup()throws Exception {for(Fixture fixture:fixtures)fixture.close();}
    @Test public void actualStopAllInterruptsCopyAndIdenticalOperationRetryPublishesOneIndependentClip()throws Exception {
        Fixture f=new Fixture();f.gate.block=true;JSONObject queued=f.queue(f.media.request);assertTrue(queued.getBoolean("ok"));assertTrue(queued.getBoolean("queued"));assertTrue(queued.getBoolean("durableRecovery"));
        assertTrue("Actual streaming worker must reach a copy checkpoint",f.gate.entered.await(10,TimeUnit.SECONDS));assertEquals(1,f.media.output().assets.size());
        f.stop();assertTrue("Stop All must interrupt the worker",f.gate.interrupted.await(10,TimeUnit.SECONDS));f.drain();assertEquals("cancelled",f.job(queued).getString("state"));assertEquals(0,f.media.output().clips.size());
        JSONObject retry=f.queue(f.media.request);f.drain();JSONObject complete=f.job(retry);assertEquals(complete.toString(),"completed",complete.getString("state"));assertTrue(complete.getJSONObject("result").getBoolean("independentCopy"));assertEquals(1,f.media.output().clips.size());
        JSONObject again=f.queue(f.media.request);f.drain();assertTrue(f.job(again).getJSONObject("result").getBoolean("replayed"));assertEquals(1,f.media.output().clips.size());
    }
    @Test public void commandsLinkToTransferJobsAndRecoveredRequestsRetainSourceIdentity()throws Exception {
        Fixture f=new Fixture();CountDownLatch release=new CountDownLatch(1);f.transfers.submit(()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try{
            JSONObject args=new JSONObject(f.media.request.toString()).put("_mcpCommandId","copy-command");JSONObject queued=f.queue(args);JSONObject job=f.job(queued);assertEquals("transfer",job.getString("kind"));
            JSONObject plan=f.plans.recent(10).getJSONObject(0);assertEquals("project_import_asset",plan.getString("action"));JSONObject parameters=plan.getJSONObject("parameters");assertEquals("copy-command",parameters.getString("_mcpCommandId"));assertEquals(f.media.source.id,parameters.getString("sourceProjectId"));assertEquals(f.media.destination.id,parameters.getString("projectId"));
            assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("studio_project_import_asset"));assertEquals("project_import_asset",NativeWorkbenchBridge.canonicalAction("studio_project_import_asset"));assertTrue(NativeAgentMailboxActions.supports("project_import_asset"));
        }finally{release.countDown();f.drain();}
    }
    private static void set(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception {try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
}
