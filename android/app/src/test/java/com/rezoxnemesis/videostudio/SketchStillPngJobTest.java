package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Existing sketch_render controller -> real guarded recoverable PNG job, with no service startup/network/video renderer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStillPngJobTest {
    private final List<Fixture> fixtures=new ArrayList<>();
    private static final class HoldingExecutor extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>();boolean closed;
        public void execute(Runnable task){tasks.add(task);}public void shutdown(){closed=true;}
        public List<Runnable> shutdownNow(){closed=true;return new ArrayList<>(tasks);}
        public boolean isShutdown(){return closed;}public boolean isTerminated(){return closed;}
        public boolean awaitTermination(long n,TimeUnit unit){return closed;}
    }
    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();
        final ControlService service=Robolectric.buildService(ControlService.class).get();
        final ProjectStore store=new ProjectStore(context);
        final RecoveryPlanStore plans=new RecoveryPlanStore(context);
        final CommandJournal journal=new CommandJournal(context);
        final AppProtocol protocol=new AppProtocol(context,null);
        final JobManager jobs=new JobManager(context,"still_png_bridge_jobs");
        final HoldingExecutor heavy=new HoldingExecutor(),light=new HoldingExecutor(),watchers=new HoldingExecutor();
        final ExecutorService transfer;final JSONObject request;final String before;
        Fixture(boolean held)throws Exception {
            ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();memory.availMem=1024L*1024*1024;memory.threshold=32L*1024*1024;
            Shadows.shadowOf((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
            protocol.stop();transfer=held?new HoldingExecutor():Executors.newSingleThreadExecutor();set(jobs,"transferPool",transfer);set(jobs,"lightPool",light);set(jobs,"heavyPool",heavy);
            set(service,"store",store);set(service,"jobs",jobs);set(service,"recoveryPlans",plans);set(service,"protocol",protocol);set(service,"commandJournal",journal);set(service,"commandCompletionWatchers",watchers);
            set(service,"serviceAlive",true);set(service,"prefs",context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));
            String id="still_"+UUID.randomUUID().toString().replace("-","");
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",320).put("height",256).put("frameCount",3).put("background","#00000000"));
            JSONObject object=new JSONObject().put("id","frame_one_ink").put("kind","rect").put("frame",1).put("exposure",1).put("fill",true).put("color","#80FF0000")
                    .put("bounds",new JSONObject().put("x",.2).put("y",.2).put("width",.3).put("height",.3));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","ink")
                    .put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",object))));
            request=new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","still_frame").put("format","png").put("frame",1);
            try(SketchProjectStore sketches=new SketchProjectStore(context)){before=SketchDocument.canonical(sketches.get(id));}
        }
        JSONObject queue(JSONObject args)throws Exception{return (JSONObject)call(service,"queueSketchRender",new Class<?>[]{JSONObject.class},args);}
        JSONObject job(JSONObject queued)throws Exception{return jobs.get(queued.getString("jobId")).getJSONObject("job");}
        void drain()throws Exception{transfer.submit(()->{}).get(10,TimeUnit.SECONDS);}
        JSONObject verified(JSONObject queued)throws Exception{return (JSONObject)call(service,"buildVerifiedDeferredResult",new Class<?>[]{String.class,JSONObject.class,JSONObject.class,String.class,String.class},"sketch_render",queued,job(queued),queued.getString("jobId"),queued.getString("projectId"));}
        void close()throws Exception {
            jobs.shutdown();transfer.shutdownNow();transfer.awaitTermination(10,TimeUnit.SECONDS);protocol.stop();
            for(String name:new String[]{"commandCompletionWatchers","directorSetupExecutor","nativeSourceExecutor","cinematic3DExecutor","authoringExecutor","pipelineExecutor"})((ExecutorService)field(service,name)).shutdownNow();
        }
    }
    private Fixture fixture(boolean held)throws Exception{Fixture fixture=new Fixture(held);fixtures.add(fixture);return fixture;}
    @After public void cleanup()throws Exception{for(Fixture fixture:fixtures)fixture.close();}

    @Test public void existingControllerQueuesOneAlphaPngWithoutAnyVideoRendererOrTimeline()throws Exception {
        Fixture f=fixture(false);JSONObject queued=f.queue(f.request);assertTrue(queued.getBoolean("queued"));assertTrue(queued.getBoolean("durableRecovery"));assertEquals("png",queued.getString("format"));
        assertEquals("transfer",f.job(queued).getString("kind"));f.drain();JSONObject job=f.job(queued);assertEquals(job.toString(),"completed",job.getString("state"));
        JSONObject result=job.getJSONObject("result");assertTrue(result.getBoolean("ok"));assertEquals("image/png",result.getString("mime"));assertEquals(1,result.getInt("frame"));assertEquals(320,result.getInt("width"));assertEquals(256,result.getInt("height"));
        ProjectStore.Project output=f.store.get(result.getString("projectId"));assertNotNull(output);assertEquals(1,output.assets.size());assertEquals(0,output.clips.size());assertEquals(0,output.outputDurationMs());assertEquals("",output.latestExportUri);
        ProjectStore.Asset asset=output.asset(result.getString("assetId"));assertNotNull(asset);assertEquals("image/png",asset.mime);
        File png=new File(Uri.parse(asset.uri).getPath());assertEquals(result.getLong("bytes"),png.length());assertEquals(result.getString("sha256"),SketchProjectStore.fileHash(png));
        Bitmap image=BitmapFactory.decodeFile(png.getPath());try{assertEquals(0x80FF0000,image.getPixel(100,100));assertEquals(0,image.getPixel(315,250));}finally{image.recycle();}
        JSONObject verified=f.verified(queued);assertTrue(verified.toString(),verified.getBoolean("ok"));assertTrue(verified.getBoolean("verifiedImageOutput"));assertFalse(verified.optBoolean("verifiedPlayableOutput",false));
        try(SketchProjectStore sketches=new SketchProjectStore(f.context)){assertEquals(f.before,SketchDocument.canonical(sketches.get(f.request.getString("sketchId"))));}
        assertEquals(0,f.heavy.tasks.size());
    }

    @Test public void deferredCommandGetsVerifiedPngReceiptAndExactRetryReusesOneAsset()throws Exception {
        Fixture f=fixture(false);JSONObject command=new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",1).put("action","sketch_render").put("parameters",f.request);f.journal.begin(command);
        JSONObject queued=f.queue(new JSONObject(f.request.toString()).put("_mcpCommandId",command.getString("id")));
        call(f.service,"complete",new Class<?>[]{JSONObject.class,JSONObject.class},command,queued);assertNull(f.journal.terminal(command.getString("id")));assertEquals(1,f.watchers.tasks.size());
        f.drain();f.watchers.tasks.get(0).run();JSONObject terminal=f.journal.terminal(command.getString("id"));assertNotNull(terminal);assertEquals("completed",terminal.getString("status"));
        JSONObject delivered=terminal.getJSONObject("result");assertTrue(delivered.getBoolean("verifiedImageOutput"));assertFalse(delivered.optBoolean("verifiedPlayableOutput",false));assertEquals(f.job(queued).getJSONObject("result").getString("sha256"),delivered.getJSONObject("jobResult").getString("sha256"));
        JSONObject second=f.queue(new JSONObject(f.request.toString()));f.drain();assertEquals("completed",f.job(second).getString("state"));assertTrue(f.job(second).getJSONObject("result").getBoolean("replayed"));
        assertEquals(queued.getString("projectId"),second.getString("projectId"));assertEquals(1,f.store.get(second.getString("projectId")).assets.size());assertEquals(0,f.store.get(second.getString("projectId")).clips.size());
    }

    @Test public void corruptPngCannotTurnACompletedWorkerIntoSuccessfulNativeDelivery()throws Exception {
        Fixture f=fixture(false);JSONObject queued=f.queue(f.request);f.drain();JSONObject job=f.job(queued);assertEquals(job.toString(),"completed",job.getString("state"));JSONObject result=job.getJSONObject("result");
        File png=new File(Uri.parse(f.store.get(result.getString("projectId")).asset(result.getString("assetId")).uri).getPath());Files.write(png.toPath(),new byte[]{1,2,3});
        JSONObject verified=f.verified(queued);assertFalse(verified.toString(),verified.getBoolean("ok"));assertFalse(verified.optBoolean("verifiedImageOutput",false));assertFalse(verified.optBoolean("verifiedPlayableOutput",false));assertTrue(verified.has("error"));
    }

    @Test public void durableAdmissionRetainsPngAndFrameWithoutWritingAssetsBeforeWorker()throws Exception {
        Fixture f=fixture(true);int before=f.store.list().size();JSONObject queued=f.queue(new JSONObject(f.request.toString()).put("_mcpCommandId","still-command"));assertEquals("queued",f.job(queued).getString("state"));assertEquals(before,f.store.list().size());
        JSONObject plan=f.plans.recent(10).getJSONObject(0);assertEquals("sketch_render",plan.getString("action"));JSONObject parameters=plan.getJSONObject("parameters");assertEquals("png",parameters.getString("format"));assertEquals(1,parameters.getInt("frame"));assertEquals("still-command",parameters.getString("_mcpCommandId"));
        assertEquals(1,((HoldingExecutor)f.transfer).tasks.size());assertEquals(0,f.heavy.tasks.size());
    }

    @Test public void malformedOrStalePngAdmissionCannotCreateJobsOrProjects()throws Exception {
        Fixture f=fixture(true);int projects=f.store.list().size();
        for(JSONObject patch:new JSONObject[]{new JSONObject().put("frame",-1),new JSONObject().put("frame",3),new JSONObject().put("frame","1"),new JSONObject().put("frame",JSONObject.NULL),new JSONObject().put("format","PNG"),new JSONObject().put("aspect","original"),new JSONObject().put("quality","720p"),new JSONObject().put("expectedRevision",1)}){
            JSONObject bad=new JSONObject(f.request.toString());java.util.Iterator<String> names=patch.keys();while(names.hasNext()){String name=names.next();bad.put(name,patch.get(name));}
            try{f.queue(bad);fail("Invalid/stale PNG request was queued");}catch(IllegalArgumentException|IllegalStateException expected){}
        }
        assertEquals(projects,f.store.list().size());assertEquals(0,((HoldingExecutor)f.transfer).tasks.size());assertEquals(0,f.heavy.tasks.size());assertEquals(0,f.plans.recent(10).length());
    }

    @Test public void omittedFormatKeepsLegacyVideoAdmissionAndExactOutputIdentity()throws Exception {
        Fixture f=fixture(true);JSONObject legacy=new JSONObject().put("sketchId",f.request.getString("sketchId")).put("expectedRevision",2).put("operationId","legacy_video").put("aspect","original").put("quality","720p");
        JSONObject queued=f.queue(legacy);assertTrue(queued.getBoolean("queued"));assertEquals("heavy",f.job(queued).getString("kind"));assertFalse(queued.has("format"));
        String expected=UUID.nameUUIDFromBytes(("sketch-output/"+legacy.getString("sketchId")+"/legacy_video").getBytes(StandardCharsets.UTF_8)).toString();assertEquals(expected,queued.getString("projectId"));
        JSONObject saved=f.plans.recent(10).getJSONObject(0).getJSONObject("parameters");assertEquals(SketchDocument.canonical(legacy),SketchDocument.canonical(saved));assertEquals(1,f.heavy.tasks.size());assertEquals(0,((HoldingExecutor)f.transfer).tasks.size());
    }
    private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception{try{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
}
