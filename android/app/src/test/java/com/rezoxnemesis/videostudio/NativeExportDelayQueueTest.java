package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import androidx.media3.transformer.Transformer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * UNIT ONLY: real controller admission, JobManager queue, durable plans and restart
 * dispatch. An actual native export reaches Media3's public Builder, whose build
 * deliberately stops before constructing a transformer, graph, GL or codec.
 * A passing option-forwarding test is not evidence of decoding or export success.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=NativeExportDelayQueueTest.BuilderProbe.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeExportDelayQueueTest {
    private static final String FIELD="maxOutputSampleDelayMs";
    private final List<Fixture> fixtures=new ArrayList<>();

    @Before public void resetBuilderProbe(){BuilderProbe.delays.clear();BuilderProbe.builds=0;}

    @After public void closeFixtures()throws Exception{
        Collections.reverse(fixtures);
        for(Fixture fixture:fixtures)fixture.close();
    }

    @Test public void explicitDelayPersistsBeforeQueueExecutionAndReachesTheNativeBuilder()throws Exception{
        Fixture f=fixture();ProjectStore.Project project=scene(f);String before=SketchDocument.canonical(project.toJson());
        JSONObject request=request(project).put(FIELD,90000L);
        JSONObject queued=f.queue(request);
        assertTrue(queued.getBoolean("queued"));assertTrue(queued.getBoolean("durableRecovery"));
        assertEquals(1,f.heavy.tasks.size());assertEquals(0,BuilderProbe.builds);
        assertEquals(90000L,parameters(f).getLong(FIELD));
        assertEquals(request.toString(),parameters(f).toString());
        f.runOne();assertBuilderDelay(90000L);
        assertEquals("failed",f.jobs.get(queued.getString("jobId")).getJSONObject("job").getString("state"));
        assertEquals("The probe must not claim an encoded output",0,f.store.get(project.id).latestExportAt);
        assertEquals("Admission and a stopped unit probe must preserve source/project state",before,
                SketchDocument.canonical(f.store.get(project.id).toJson()));
    }

    @Test public void bothFiniteBoundaryValuesReachTheActualBuilderUnchanged()throws Exception{
        Fixture f=fixture();ProjectStore.Project project=scene(f);
        for(long delay:new long[]{25000L,120000L}){
            BuilderProbe.delays.clear();BuilderProbe.builds=0;
            f.queue(request(project).put(FIELD,delay));f.runOne();assertBuilderDelay(delay);
        }
    }

    @Test public void omissionKeepsSdkDefaultAndDurableParametersAndQueuedReceiptOmitted()throws Exception{
        Fixture f=fixture();ProjectStore.Project project=scene(f);JSONObject original=request(project);
        JSONObject queued=f.queue(original);
        assertFalse(parameters(f).has(FIELD));assertFalse(queued.has(FIELD));
        assertEquals(original.toString(),parameters(f).toString());
        f.runOne();assertEquals(1,BuilderProbe.builds);assertTrue(BuilderProbe.delays.isEmpty());
    }

    @Test public void acceptedDelayIsCapturedIndependentlyOfLaterCallerMutation()throws Exception{
        Fixture f=fixture();JSONObject original=request(scene(f)).put(FIELD,65000L);
        f.queue(original);original.put(FIELD,120000L);
        assertEquals(65000L,parameters(f).getLong(FIELD));f.runOne();assertBuilderDelay(65000L);
    }

    @Test public void interruptedExportRecoveryRetainsTheExplicitDelayAndOriginalPlan()throws Exception{
        Fixture first=fixture();ProjectStore.Project project=scene(first);
        JSONObject queued=first.queue(request(project).put(FIELD,110000L));
        JSONObject before=first.plans.pendingForAutoResume().getJSONObject(0);
        assertEquals(1,first.heavy.tasks.size());assertEquals(0,BuilderProbe.builds);
        Fixture restarted=fixture();
        assertEquals("checkpointed",restarted.jobs.get(queued.getString("jobId")).getJSONObject("job").getString("state"));
        call(restarted.service,"recoverDurablePlans",new Class<?>[]{});
        assertEquals(1,restarted.heavy.tasks.size());assertEquals(1,restarted.plans.recent(24).length());
        JSONObject resumed=restarted.plans.get(before.getString("id"));
        assertEquals(2,resumed.getInt("attempts"));
        assertEquals(110000L,resumed.getJSONObject("parameters").getLong(FIELD));
        assertNotEquals(queued.getString("jobId"),resumed.getString("jobId"));
        restarted.runOne();assertBuilderDelay(110000L);
        assertEquals("The original queued worker must remain unexecuted",1,first.heavy.tasks.size());
    }

    @Test public void recoveredOmissionDoesNotMaterializeAnOverrideOrCallTheSdkSetter()throws Exception{
        Fixture first=fixture();first.queue(request(scene(first)));
        String plan=first.plans.pendingForAutoResume().getJSONObject(0).getString("id");
        Fixture restarted=fixture();call(restarted.service,"recoverDurablePlans",new Class<?>[]{});
        assertEquals(1,restarted.heavy.tasks.size());
        assertFalse(restarted.plans.get(plan).getJSONObject("parameters").has(FIELD));
        restarted.runOne();assertEquals(1,BuilderProbe.builds);assertTrue(BuilderProbe.delays.isEmpty());
    }

    @Test public void outOfRangeDelaysAreRejectedBeforeAnyJobOrRecoveryPlanExists()throws Exception{
        Fixture f=fixture();ProjectStore.Project project=scene(f);
        for(long invalid:new long[]{-1L,0L,24999L,120001L,Long.MAX_VALUE})
            assertRejectedWithoutQueue(f,request(project).put(FIELD,invalid));
    }

    @Test public void wrongTypedAndFractionalDelaysAreRejectedWithoutCoercion()throws Exception{
        Fixture f=fixture();ProjectStore.Project project=scene(f);
        for(Object invalid:new Object[]{JSONObject.NULL,"90000",true,90000.5d,new JSONArray().put(90000),new JSONObject().put("value",90000)})
            assertRejectedWithoutQueue(f,request(project).put(FIELD,invalid));
    }

    @Test public void integralJsonDoubleRemainsAnExactAcceptedDelay()throws Exception{
        Fixture f=fixture();f.queue(request(scene(f)).put(FIELD,75000.0d));
        assertEquals(75000L,parameters(f).getLong(FIELD));f.runOne();assertBuilderDelay(75000L);
    }

    @Test public void localExportIntentPreservesAnExplicitTypedDelayThroughQueueAndRenderer()throws Exception{
        Fixture f=fixture();ProjectStore.Project project=scene(f);
        f.service.onStartCommand(local(project).putExtra(FIELD,90000L),0,1);
        assertEquals(1,f.heavy.tasks.size());assertEquals(90000L,parameters(f).getLong(FIELD));
        f.runOne();assertBuilderDelay(90000L);
    }

    @Test public void localExportIntentDoesNotCoerceAnInvalidStringIntoADefault()throws Exception{
        Fixture f=fixture();ProjectStore.Project project=scene(f);
        f.service.onStartCommand(local(project).putExtra(FIELD,"90000"),0,1);
        assertEquals(0,f.heavy.tasks.size());assertEquals(0,f.plans.recent(24).length());
        assertEquals(0,f.jobs.state().getJSONArray("jobs").length());assertEquals(0,BuilderProbe.builds);
    }

    @Test public void localExportIntentOmissionPreservesTheSdkDefault()throws Exception{
        Fixture f=fixture();f.service.onStartCommand(local(scene(f)),0,1);
        assertEquals(1,f.heavy.tasks.size());assertFalse(parameters(f).has(FIELD));
        f.runOne();assertEquals(1,BuilderProbe.builds);assertTrue(BuilderProbe.delays.isEmpty());
    }

    private static void assertRejectedWithoutQueue(Fixture f,JSONObject request)throws Exception{
        int projects=f.store.summaries().getJSONArray("projects").length();
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->f.queue(request));
        assertTrue("Invalid-option error must identify its field",error.getMessage()!=null&&error.getMessage().contains(FIELD));
        assertEquals(0,f.heavy.tasks.size());assertEquals(0,f.plans.recent(24).length());
        assertEquals(0,f.jobs.state().getJSONArray("jobs").length());assertEquals(0,BuilderProbe.builds);
        assertEquals(projects,f.store.summaries().getJSONArray("projects").length());
    }

    private static void assertBuilderDelay(long expected){
        assertEquals("The real service worker must reach the real export builder",1,BuilderProbe.builds);
        assertEquals(Collections.singletonList(expected),BuilderProbe.delays);
    }

    private static JSONObject parameters(Fixture f)throws Exception{
        return f.plans.recent(24).getJSONObject(0).getJSONObject("parameters");
    }

    private static JSONObject request(ProjectStore.Project project)throws Exception{
        return new JSONObject().put("projectId",project.id).put("aspect","9:16")
                .put("quality","720p").put("fileName","bounded-native-export.mp4");
    }

    private static Intent local(ProjectStore.Project project){
        return new Intent(ControlService.ACTION_LOCAL_EXPORT).putExtra("projectId",project.id)
                .putExtra("aspect","9:16").putExtra("quality","720p")
                .putExtra("fileName","bounded-native-export.mp4");
    }

    private static ProjectStore.Project scene(Fixture f){
        ProjectStore.Project project=f.store.create("Existing source clock and bytes preserved");
        ProjectStore.Asset source=new ProjectStore.Asset();source.id="source";source.name="Not decoded fixture";
        source.uri="file:///not-opened-unit-contract.mp4";source.mime="video/mp4";
        source.durationMs=5400;source.sizeBytes=100;source.seekable=true;project.assets.add(source);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip";clip.assetId=source.id;
        clip.inMs=2400;clip.outMs=5400;clip.speed=.75f;project.clips.add(clip);f.store.save(project);return project;
    }

    private Fixture fixture()throws Exception{Fixture f=new Fixture();fixtures.add(f);return f;}

    private static final class HoldingExecutor extends AbstractExecutorService{
        final List<Runnable> tasks=new ArrayList<>();private boolean stopped;
        @Override public void execute(Runnable command){if(stopped)throw new java.util.concurrent.RejectedExecutionException();tasks.add(command);}
        @Override public void shutdown(){stopped=true;}
        @Override public List<Runnable> shutdownNow(){stopped=true;return new ArrayList<>(tasks);}
        @Override public boolean isShutdown(){return stopped;}
        @Override public boolean isTerminated(){return stopped;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return stopped;}
    }

    private static final class Fixture{
        final Context context=RuntimeEnvironment.getApplication();
        final ControlService service=Robolectric.buildService(ControlService.class).get();
        final ProjectStore store=new ProjectStore(context);final RecoveryPlanStore plans=new RecoveryPlanStore(context);
        final AppProtocol protocol=new AppProtocol(context,null);final JobManager jobs=new JobManager(context,"native_export_delay_queue_test");
        final HoldingExecutor heavy=new HoldingExecutor(),light=new HoldingExecutor(),transfers=new HoldingExecutor(),watchers=new HoldingExecutor();
        Fixture()throws Exception{
            ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();memory.availMem=1024L*1024*1024;memory.threshold=32L*1024*1024;
            Shadows.shadowOf((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
            context.getSharedPreferences("videostudio_native_v1",0).edit().putBoolean("chatgpt_control_paused",false).commit();
            set(jobs,"heavyPool",heavy);set(jobs,"lightPool",light);set(jobs,"transferPool",transfers);
            set(service,"serviceAlive",true);set(service,"store",store);set(service,"jobs",jobs);set(service,"protocol",protocol);
            set(service,"renderEngine",new NativeRenderEngine(context));set(service,"creativeWorkspace",new CreativeWorkspace(context));
            set(service,"recoveryPlans",plans);set(service,"commandJournal",new CommandJournal(context));
            set(service,"prefs",context.getSharedPreferences("videostudio_native_v1",0));set(service,"commandCompletionWatchers",watchers);
        }
        JSONObject queue(JSONObject request)throws Exception{return (JSONObject)call(service,"queueExport",new Class<?>[]{JSONObject.class},request);}
        void runOne(){assertEquals(1,heavy.tasks.size());heavy.tasks.remove(0).run();}
        void close()throws Exception{
            protocol.stop();jobs.shutdown();
            for(String name:new String[]{"commandCompletionWatchers","directorSetupExecutor","nativeSourceExecutor","cinematic3DExecutor","authoringExecutor","pipelineExecutor"})
                ((ExecutorService)field(service,name)).shutdownNow();
            store.close();
        }
    }

    /** Public builder observation only; no actual Transformer or native worker exists. */
    @Implements(value=Transformer.Builder.class,isInAndroidSdk=false)
    public static final class BuilderProbe{
        @RealObject private Transformer.Builder builder;
        static final List<Long> delays=new ArrayList<>();static int builds;
        @Implementation public Transformer.Builder setMaxDelayBetweenMuxerSamplesMs(long delay){delays.add(delay);return builder;}
        @Implementation public Transformer build(){builds++;throw new ProbeComplete();}
    }
    private static final class ProbeComplete extends RuntimeException{
        ProbeComplete(){super("UNIT_PROBE_STOP_BEFORE_TRANSFORMER_CONSTRUCTION");}
    }
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}
    }
}
