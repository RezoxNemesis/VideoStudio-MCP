package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import static org.junit.Assert.*;

/** Real controller -> recoverable JobManager worker -> native publication/cancellation. No service/network startup. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=33,manifest=Config.NONE,shadows=SketchStoryJobIntegrationTest.ShadowPlans.class)
public class SketchStoryJobIntegrationTest {
    private final List<Fixture> fixtures=new ArrayList<>();
    private static final String[] ACTIONS={"sketch_story_create","sketch_story_audio_create","sketch_animation_place_in_book"};
    private static final class HoldingExecutor extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>();boolean closed;
        public void execute(Runnable task){tasks.add(task);}public void shutdown(){closed=true;}
        public List<Runnable> shutdownNow(){closed=true;return new ArrayList<>(tasks);}
        public boolean isShutdown(){return closed;}public boolean isTerminated(){return closed;}
        public boolean awaitTermination(long n,TimeUnit u){return closed;}
    }
    @Implements(value=RecoveryPlanStore.class,isInAndroidSdk=false)
    public static class ShadowPlans {
        @RealObject RecoveryPlanStore real;
        final CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        final AtomicBoolean once=new AtomicBoolean();volatile String blockStage="";
        @Implementation protected void checkpointForJob(String id,String stage,int progress,String detail){
            org.robolectric.shadow.api.Shadow.directlyOn(real,RecoveryPlanStore.class,"checkpointForJob",org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,id),org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,stage),org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,progress),org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,detail));
            if(stage.equals(blockStage)&&once.compareAndSet(false,true)){
                entered.countDown();while(!Thread.currentThread().isInterrupted())LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                interrupted.countDown(); // Return with the interrupt flag intact; the real core/checkpoint must stop.
            }
        }
    }
    private static final class Fixture {
        final Context c=RuntimeEnvironment.getApplication();final ControlService service=Robolectric.buildService(ControlService.class).get();
        final ProjectStore store=new ProjectStore(c);final RecoveryPlanStore plans=new RecoveryPlanStore(c);final ShadowPlans gate=org.robolectric.shadow.api.Shadow.extract(plans);final CommandJournal journal=new CommandJournal(c);
        final AppProtocol protocol=new AppProtocol(c,null);final JobManager jobs=new JobManager(c,"story_job_integration");
        final HoldingExecutor watchers=new HoldingExecutor();ExecutorService heavy;
        Fixture(boolean held)throws Exception {
            android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();memory.availMem=1024L*1024*1024;memory.threshold=32L*1024*1024;
            Shadows.shadowOf((android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
            protocol.stop();heavy=held?new HoldingExecutor():Executors.newSingleThreadExecutor();set(jobs,"heavyPool",heavy);
            set(service,"serviceAlive",true);set(service,"jobs",jobs);set(service,"recoveryPlans",plans);set(service,"store",store);
            set(service,"commandJournal",journal);set(service,"protocol",protocol);set(service,"commandCompletionWatchers",watchers);
            set(service,"prefs",c.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));
        }
        JSONObject queue(String action,JSONObject args)throws Exception{return (JSONObject)call(service,"queueSketchStoryWork",new Class<?>[]{String.class,JSONObject.class},action,args);}
        JSONObject job(JSONObject queued)throws Exception{return jobs.get(queued.getString("jobId")).getJSONObject("job");}
        void stopAll()throws Exception{
            JSONObject command=new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",99).put("action","stop_all").put("parameters",new JSONObject());
            service.onCommand(command);assertTrue(journal.terminal(command.getString("id")).getJSONObject("result").getInt("cancelledJobs")>0);
        }
        void drained()throws Exception{heavy.submit(()->{}).get(10,TimeUnit.SECONDS);}
        void close()throws Exception{
            jobs.shutdown();heavy.shutdownNow();heavy.awaitTermination(10,TimeUnit.SECONDS);protocol.stop();
            for(String name:new String[]{"commandCompletionWatchers","directorSetupExecutor","nativeSourceExecutor","cinematic3DExecutor","authoringExecutor","pipelineExecutor"}){
                Field f=ControlService.class.getDeclaredField(name);f.setAccessible(true);((ExecutorService)f.get(service)).shutdownNow();
            }
        }
    }
    private Fixture fixture(boolean held)throws Exception{Fixture f=new Fixture(held);fixtures.add(f);return f;}
    @After public void cleanup()throws Exception{for(Fixture f:fixtures)f.close();}
    private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object call(ControlService s,String name,Class<?>[] types,Object...args)throws Exception{
        try{Method m=ControlService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(s,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
    }
    private static JSONObject command(String action,JSONObject args)throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",1).put("action",action).put("parameters",args);}
    private static JSONObject plan()throws Exception{
        JSONObject object=new JSONObject().put("id","subject").put("kind","rect").put("bounds",new JSONObject().put("x",.3).put("y",.3).put("width",.3).put("height",.3)).put("fill",true).put("exposure",24);
        JSONArray ops=new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object));
        return new JSONObject().put("bookId","story_"+UUID.randomUUID().toString().replace("-","")).put("operationId","create").put("name","Cancellable story").put("width",320).put("height",480).put("fps",24)
                .put("shots",new JSONArray().put(new JSONObject().put("shotId","shot").put("frameCount",24).put("operations",ops)));
    }
    private static JSONObject placement(Fixture f)throws Exception{
        String sourceId=UUID.randomUUID().toString(),animationId=UUID.randomUUID().toString(),bookId=UUID.randomUUID().toString();
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId",sourceId).put("width",320).put("height",480).put("fps",24));
        try(SketchProjectStore sketches=new SketchProjectStore(f.c)){sketches.importOwnedDocument(source,"source");}
        JSONObject saved=SketchAnimationOps.execute(f.c,"sketch_animation_save",new JSONObject().put("animationId",animationId).put("sketchId",sourceId).put("sourceRevision",1).put("expectedRevision",0).put("operationId","save")
                .put("program",new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("fps",24).put("frameCount",24).put("rigs",new JSONArray())));
        JSONObject book=SketchBookStore.execute(f.c,"sketch_book_create",new JSONObject().put("bookId",bookId).put("operationId","book").put("pageCount",3).put("width",320).put("height",480).put("fps",24)).getJSONObject("book");
        return new JSONObject().put("animationId",animationId).put("expectedAnimationRevision",saved.getLong("revision")).put("bookId",bookId).put("expectedBookRevision",book.getLong("revision"))
                .put("sourceFrameStart",0).put("frameCount",3).put("destinationPageStart",0).put("operationId","place");
    }
    private static JSONObject audio(Fixture f)throws Exception{
        File file=new File(f.c.getFilesDir(),UUID.randomUUID()+".wav");int samples=24000;
        ByteBuffer b=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(36+samples*2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(samples*2);while(b.hasRemaining())b.putShort((short)1200);Files.write(file.toPath(),b.array());
        ProjectStore.Project project=f.store.create("Owned master");ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="master";asset.name="Master.wav";asset.uri=Uri.fromFile(file).toString();asset.mime="audio/wav";asset.durationMs=1000;project.assets.add(asset);f.store.save(project);
        return new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",asset.id).put("operationId","audio_"+UUID.randomUUID()).put("settings",new JSONObject().put("musicGain",0).put("ambienceGain",0));
    }
    @Test public void allWritesQueueDurablyAndLinkCommandsBeforeTheirWorkersRun()throws Exception{
        Fixture f=fixture(true);JSONObject[] requests={plan(),audio(f),placement(f)};
        for(int i=0;i<ACTIONS.length;i++){
            String action=ACTIONS[i];JSONObject original=command(action,requests[i]);f.journal.begin(original);
            JSONObject queued=f.queue(action,new JSONObject(requests[i].toString()).put("_mcpCommandId",original.getString("id")));
            assertTrue(queued.getBoolean("ok"));assertTrue(queued.getBoolean("queued"));assertTrue(queued.getBoolean("durableRecovery"));assertTrue(ExecutionTruthPolicy.isDeferredResult(queued));
            assertEquals("queued",f.job(queued).getString("state"));assertEquals("heavy",f.job(queued).getString("kind"));
            call(f.service,"complete",new Class<?>[]{JSONObject.class,JSONObject.class},original,queued);
            assertNull(f.journal.terminal(original.getString("id")));assertEquals(queued.getString("jobId"),f.journal.inflight(original.getString("id")).getString("jobId"));
            assertTrue((Boolean)call(f.service,"mayQueueBackgroundWork",new Class<?>[]{String.class},action));assertTrue(NativeStartupGuard.heavy(action));
        }
        assertEquals(3,((HoldingExecutor)f.heavy).tasks.size());assertEquals(3,f.plans.pendingForAutoResume().length());assertEquals(3,f.watchers.tasks.size());
    }
    @Test public void stopAllInterruptsPublishedStoryAndIdenticalRetryResumesWithoutOverwritingHumanEdits()throws Exception{cancelAndRetry(false);}
    @Test public void stopAllInterruptsPublishedPlacementAndIdenticalRetryResumesWithoutOverwritingHumanEdits()throws Exception{cancelAndRetry(true);}
    private void cancelAndRetry(boolean placement)throws Exception{
        Fixture f=fixture(false);JSONObject args=placement?placement(f):plan();String action=placement?ACTIONS[2]:ACTIONS[0];f.gate.blockStage=placement?"Book placement pages":"Story pages";
        JSONObject first=f.queue(action,args);assertTrue("First page was not published",f.gate.entered.await(15,TimeUnit.SECONDS));f.stopAll();assertTrue(f.gate.interrupted.await(5,TimeUnit.SECONDS));f.drained();
        assertEquals("cancelled",f.job(first).getString("state"));assertEquals(0,f.plans.pendingForAutoResume().length());
        String page=args.getString("bookId")+"_p0";try(SketchProjectStore sketches=new SketchProjectStore(f.c)){assertTrue(sketches.revision(page)>=1);sketches.mutate(page,"human_edit",sketches.revision(page),"a".repeat(64),d->d.put("name","Human edits stay"));}
        JSONObject retry=f.queue(action,new JSONObject(args.toString()));f.drained();assertEquals(f.job(retry).toString(),"completed",f.job(retry).getString("state"));assertTrue(f.job(retry).getJSONObject("result").getBoolean("ok"));
        try(SketchProjectStore sketches=new SketchProjectStore(f.c)){assertEquals("Human edits stay",sketches.get(page).getString("name"));}
    }
    @Test public void stopAllInterruptsAudioWorkerAndExactRetryPreservesTheMaster()throws Exception{
        Fixture f=fixture(false);JSONObject args=audio(f);ProjectStore.Project source=f.store.get(args.getString("projectId"));File input=new File(Uri.parse(source.assets.get(0).uri).getPath());byte[] original=Files.readAllBytes(input.toPath());
        f.gate.blockStage="Story audio";JSONObject first=f.queue(ACTIONS[1],args);assertTrue(f.gate.entered.await(10,TimeUnit.SECONDS));f.stopAll();assertTrue(f.gate.interrupted.await(5,TimeUnit.SECONDS));f.drained();
        assertEquals("cancelled",f.job(first).getString("state"));assertArrayEquals(original,Files.readAllBytes(input.toPath()));
        JSONObject retry=f.queue(ACTIONS[1],new JSONObject(args.toString()));f.drained();assertEquals(f.job(retry).toString(),"completed",f.job(retry).getString("state"));
        JSONObject result=f.job(retry).getJSONObject("result");assertTrue(result.getBoolean("ok"));assertNotEquals(source.id,result.getString("projectId"));assertArrayEquals(original,Files.readAllBytes(input.toPath()));
        JSONObject replay=f.queue(ACTIONS[1],new JSONObject(args.toString()));f.drained();assertEquals(result.getString("projectId"),f.job(replay).getJSONObject("result").getString("projectId"));assertTrue(f.job(replay).getJSONObject("result").getBoolean("replayed"));
    }
    @Test public void interruptedIoBecomesCancellationButOrdinaryIoRemainsAFailure()throws Exception{
        Fixture f=fixture(false);
        for(boolean interrupted:new boolean[]{true,false}){
            JobManager.Work work=state->{if(interrupted)Thread.currentThread().interrupt();throw new InterruptedIOException("PCM stopped");};
            JobManager.Job job=(JobManager.Job)call(f.service,"submitRecoverableHeavy",new Class<?>[]{String.class,JSONObject.class,String.class,String.class,JobManager.Work.class},ACTIONS[1],new JSONObject(),"","Audio interruption",work);
            f.drained();assertEquals(interrupted?"cancelled":"failed",f.jobs.get(job.id).getJSONObject("job").getString("state"));
        }
    }
    @Test public void deferredWatcherPublishesTheActualResultOnlyAfterItsWorkerCompletes()throws Exception{
        Fixture f=fixture(false);JSONObject args=audio(f),command=command(ACTIONS[1],args);f.journal.begin(command);
        JSONObject queued=f.queue(ACTIONS[1],new JSONObject(args.toString()).put("_mcpCommandId",command.getString("id")));
        call(f.service,"complete",new Class<?>[]{JSONObject.class,JSONObject.class},command,queued);
        assertNull(f.journal.terminal(command.getString("id")));assertEquals(1,f.watchers.tasks.size());
        f.drained();assertEquals("completed",f.job(queued).getString("state"));assertNull(f.journal.terminal(command.getString("id")));
        f.watchers.tasks.get(0).run();JSONObject receipt=f.journal.terminal(command.getString("id"));assertNotNull(receipt);
        assertEquals("completed",receipt.getString("status"));JSONObject result=receipt.getJSONObject("result");assertTrue(result.getBoolean("ok"));assertFalse(result.getBoolean("queued"));
        assertTrue(result.getBoolean("completed"));assertEquals("completed",result.getString("jobState"));
        assertEquals(f.job(queued).getJSONObject("result").getString("sha256"),result.getJSONObject("jobResult").getString("sha256"));
    }
    @Test public void processRecoveryRequeuesAllThreeActionsAndRelinksTheirOriginalCommands()throws Exception{
        Fixture first=fixture(true);JSONObject[] requests={plan(),audio(first),placement(first)};List<String> commands=new ArrayList<>(),jobs=new ArrayList<>();
        for(int i=0;i<ACTIONS.length;i++){
            JSONObject command=command(ACTIONS[i],requests[i]);first.journal.begin(command);commands.add(command.getString("id"));
            JSONObject queued=first.queue(ACTIONS[i],new JSONObject(requests[i].toString()).put("_mcpCommandId",command.getString("id")));jobs.add(queued.getString("jobId"));call(first.service,"complete",new Class<?>[]{JSONObject.class,JSONObject.class},command,queued);
        }
        Fixture restarted=fixture(true);call(restarted.service,"recoverDurablePlans",new Class<?>[]{});assertEquals(3,((HoldingExecutor)restarted.heavy).tasks.size());
        for(int i=0;i<ACTIONS.length;i++){JSONObject binding=restarted.journal.inflight(commands.get(i));assertNotNull(binding);assertNotEquals(jobs.get(i),binding.getString("jobId"));}
        assertEquals(3,restarted.plans.recent(24).length());
    }
}
