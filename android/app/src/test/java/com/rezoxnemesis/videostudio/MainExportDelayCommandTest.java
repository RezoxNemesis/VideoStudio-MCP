package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Real foreground command/outbox/job wiring. Test-only NRE boundary stops before rendering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=MainExportDelayCommandTest.ExportBoundaryShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainExportDelayCommandTest {
    private static final String FIELD="maxOutputSampleDelayMs";

    @Before public void resetBoundary(){ExportBoundaryShadow.reset();}

    @Test public void omittedForegroundExportQueuesWithoutInventingAnOverrideOrWritingTheProject()throws Exception{
        try(Fixture f=new Fixture()){
            String before=SketchDocument.canonical(f.store.get(f.project.id).toJson());
            JSONObject receipt=f.export(new JSONObject());assertTrue(receipt.getBoolean("ok"));assertTrue(receipt.getBoolean("queued"));
            assertEquals(f.project.id,receipt.getString("projectId"));assertEquals(1,f.deferred.size());assertEquals(0,ExportBoundaryShadow.calls.get());
            f.runOneAtBoundary();assertEquals(1,ExportBoundaryShadow.calls.get());assertNull(ExportBoundaryShadow.wait.get());
            assertEquals(f.project.id,ExportBoundaryShadow.project.get());
            assertEquals(before,SketchDocument.canonical(f.store.get(f.project.id).toJson()));
        }
    }

    @Test public void explicitForegroundExportWaitSurvivesCommandAndJobClosureToNativeBoundary()throws Exception{
        for(long wait:new long[]{60000,120000}){
            ExportBoundaryShadow.reset();
            try(Fixture f=new Fixture()){
                String before=SketchDocument.canonical(f.store.get(f.project.id).toJson());
                JSONObject receipt=f.export(new JSONObject().put(FIELD,wait));assertTrue(receipt.getBoolean("ok"));assertTrue(receipt.getBoolean("queued"));
                assertEquals(1,f.deferred.size());assertEquals(0,ExportBoundaryShadow.calls.get());
                f.runOneAtBoundary();assertEquals(Long.valueOf(wait),ExportBoundaryShadow.wait.get());
                assertEquals(f.project.id,ExportBoundaryShadow.project.get());
                assertEquals(before,SketchDocument.canonical(f.store.get(f.project.id).toJson()));
            }
        }
    }

    @Test public void malformedForegroundOverridesAreRejectedBeforeAnyHeavyJobIsAdmitted()throws Exception{
        try(Fixture f=new Fixture()){
            String before=SketchDocument.canonical(f.store.get(f.project.id).toJson());
            for(Object raw:new Object[]{JSONObject.NULL,"60000",true,false,60000.5,24999,120001,0,-1,Long.MAX_VALUE}){
                JSONObject receipt=f.export(new JSONObject().put(FIELD,raw));
                assertFalse("Invalid explicit override must fail before job admission: "+raw,receipt.optBoolean("ok"));
                assertFalse(receipt.optBoolean("queued"));assertEquals(0,f.deferred.size());
                assertEquals(0,f.jobs.state().getJSONArray("jobs").length());assertEquals(0,ExportBoundaryShadow.calls.get());
                assertEquals(before,SketchDocument.canonical(f.store.get(f.project.id).toJson()));
            }
        }
    }

    /** Captures either old/default or new/explicit public API without running a codec or GPU. */
    @Implements(value=NativeRenderEngine.class,isInAndroidSdk=false)
    public static final class ExportBoundaryShadow {
        static final AtomicInteger calls=new AtomicInteger();
        static final AtomicReference<Long> wait=new AtomicReference<>();
        static final AtomicReference<String> project=new AtomicReference<>();
        static volatile CountDownLatch entered=new CountDownLatch(1);
        static void reset(){calls.set(0);wait.set(null);project.set(null);entered=new CountDownLatch(1);}
        @Implementation public NativeRenderEngine.Handle export(ProjectStore.Project p,File output,String aspect,String quality,NativeRenderEngine.Listener listener){
            return capture(p,null,listener);
        }
        // This shadow method is not a product call or a reference to a missing
        // helper; the pre-feature product calls its existing five-argument API.
        @Implementation public NativeRenderEngine.Handle export(ProjectStore.Project p,File output,String aspect,String quality,Long requestedWait,NativeRenderEngine.Listener listener){
            return capture(p,requestedWait,listener);
        }
        private static NativeRenderEngine.Handle capture(ProjectStore.Project p,Long requestedWait,NativeRenderEngine.Listener listener){
            calls.incrementAndGet();wait.set(requestedWait);project.set(p.id);
            listener.onError("TEST_ONLY_NATIVE_EXPORT_BOUNDARY_CAPTURE");entered.countDown();return null;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        final ProjectStore store=new ProjectStore(activity);
        final AppProtocol protocol=new AppProtocol(activity,null);
        final JobManager jobs=new JobManager(activity,"export_delay_"+UUID.randomUUID().toString().replace("-",""));
        final DeferredExecutor deferred=new DeferredExecutor();
        final ProjectStore.Project project;
        Fixture()throws Exception{
            protocol.stop();
            ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();memory.availMem=1024L*1024*1024;memory.totalMem=2L*1024*1024*1024;memory.threshold=32L*1024*1024;memory.lowMemory=false;
            Shadows.shadowOf((ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
            ((ExecutorService)field(jobs,"heavyPool")).shutdownNow();set(jobs,"heavyPool",deferred);
            project=store.create("Foreground export delay command");
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-video";asset.mime="video/mp4";asset.uri="file:///not-decoded-export-wait.mp4";asset.durationMs=10000;project.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip";clip.assetId=asset.id;clip.outMs=5000;project.clips.add(clip);store.save(project);
            set(activity,"store",store);set(activity,"protocol",protocol);set(activity,"jobs",jobs);set(activity,"activeProject",project);
            set(activity,"prefs",activity.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));
            set(activity,"renderEngine",new NativeRenderEngine(activity));
        }
        JSONObject export(JSONObject parameters)throws Exception{
            String id=UUID.randomUUID().toString();
            JSONObject p=new JSONObject(parameters.toString()).put("aspect","9:16").put("quality","720p").put("fileName","wait-test.mp4");
            activity.onCommand(new JSONObject().put("id",id).put("action","export_project").put("parameters",p));
            JSONArray pending=((CommandOutbox)field(protocol,"outbox")).pending();
            for(int i=0;i<pending.length();i++)if(id.equals(pending.getJSONObject(i).optString("id")))return pending.getJSONObject(i).getJSONObject("result");
            throw new AssertionError("Foreground export must produce its ordinary durable command receipt");
        }
        void runOneAtBoundary()throws Exception{
            Runnable task=deferred.remove();Thread worker=new Thread(task,"unit-export-delay-boundary");worker.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(ExportBoundaryShadow.entered.getCount()>0&&System.nanoTime()<deadline){ShadowLooper.idleMainLooper();ExportBoundaryShadow.entered.await(10,TimeUnit.MILLISECONDS);}
            assertEquals("Actual Main worker must reach the test-only NRE boundary",0,ExportBoundaryShadow.entered.getCount());
            ShadowLooper.idleMainLooper();worker.join(2000);assertFalse(worker.isAlive());
        }
        @Override public void close()throws Exception{
            jobs.shutdown();deferred.shutdownNow();protocol.stop();((CommandOutbox)field(protocol,"outbox")).close();store.close();
            ShadowLooper.idleMainLooper();
        }
    }

    private static final class DeferredExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();private boolean stopped;
        @Override public synchronized void execute(Runnable task){if(stopped)throw new java.util.concurrent.RejectedExecutionException();tasks.addLast(task);}
        synchronized int size(){return tasks.size();}
        synchronized Runnable remove(){assertFalse(tasks.isEmpty());return tasks.removeFirst();}
        @Override public synchronized void shutdown(){stopped=true;}
        @Override public synchronized List<Runnable> shutdownNow(){stopped=true;List<Runnable> result=new ArrayList<>(tasks);tasks.clear();return result;}
        @Override public synchronized boolean isShutdown(){return stopped;}
        @Override public synchronized boolean isTerminated(){return stopped;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return stopped;}
    }
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
