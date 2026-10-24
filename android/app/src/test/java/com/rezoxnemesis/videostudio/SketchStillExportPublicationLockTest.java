package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Public export behavior with an independent cooperating owner of the stable lock.
 * Same-JVM held-lock tests exercise coordination, not actual cross-process Android concurrency.
 * Source only until root executes; actual Android525 hardlink denial is the original RED.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStillExportPublicationLockTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void untrustedStableLockSymlinkRejectsWithoutExportingOrChangingItsTarget()throws Exception {
        Fixture f=new Fixture();File lock=f.lockFile();assertTrue(lock.getParentFile().mkdirs()||lock.getParentFile().isDirectory());
        File unrelated=File.createTempFile("unrelated_owned_lock_target_",".txt",context.getFilesDir());
        byte[] keep="Keep existing unrelated owned bytes".getBytes(StandardCharsets.UTF_8);Files.write(unrelated.toPath(),keep);
        Files.createSymbolicLink(lock.toPath(),unrelated.toPath());String source=f.document();
        assertThrows(Exception.class,f::prepare);
        assertTrue(Files.isSymbolicLink(lock.toPath()));assertArrayEquals(keep,Files.readAllBytes(unrelated.toPath()));
        assertFalse(new File(f.generation(),"snapshot.json").exists());assertFalse(f.png().exists());
        assertNull(new ProjectStore(context).get(f.outputId()));assertEquals(source,f.document());
    }

    @Test public void prepareWaitsForTheCooperatingLockBeforePublishingThenReleasesItForReplay()throws Exception {
        Fixture f=new Fixture();ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1);
        Future<SketchNativeExport.Prepared> future;
        try {
            try(HeldLock held=new HeldLock(f)){
                future=worker.submit(()->{entered.countDown();return f.prepare();});awaitBlocked(future,entered);
                assertFalse("No immutable snapshot before the prior writer releases",new File(f.generation(),"snapshot.json").exists());
                assertFalse(f.png().exists());assertNull(new ProjectStore(context).get(f.outputId()));
            }
            SketchNativeExport.Prepared first=future.get(10,TimeUnit.SECONDS);String hash=SketchProjectStore.fileHash(first.file);
            SketchNativeExport.Prepared replay=f.prepare();assertTrue(replay.metadata.getBoolean("replayed"));
            assertEquals(hash,SketchProjectStore.fileHash(replay.file));assertEquals(first.project.id,replay.project.id);
            assertEquals(1,replay.project.assets.size());assertTrue(replay.project.clips.isEmpty());
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test public void admissionReadsACompetingWritersCommittedSnapshotOnlyAfterTheSameLock()throws Exception {
        Fixture f=new Fixture();ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1);
        byte[] conflict=new JSONObject().put("requestSha256","different-prior-operation-meaning").toString().getBytes(StandardCharsets.UTF_8);
        Future<JSONObject> future;
        try {
            try(HeldLock held=new HeldLock(f)){
                future=worker.submit(()->{entered.countDown();return SketchStillExport.admit(context,f.args());});awaitBlocked(future,entered);
                assertTrue(f.generation().mkdirs()||f.generation().isDirectory());Files.write(new File(f.generation(),"snapshot.json").toPath(),conflict);
            }
            ExecutionException error=assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));
            assertTrue(String.valueOf(error.getCause()).contains("SKETCH_OPERATION_ID_CONFLICT"));
            assertArrayEquals(conflict,Files.readAllBytes(new File(f.generation(),"snapshot.json").toPath()));
            assertFalse(f.png().exists());assertNull(new ProjectStore(context).get(f.outputId()));
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test public void receiptVerificationWaitsAndRejectsChangedOwnedBytesRatherThanReturningStaleSuccess()throws Exception {
        Fixture f=new Fixture();SketchNativeExport.Prepared first=f.prepare();String source=f.document();byte[] changed={5,6,7};
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1);Future<JSONObject> future;
        try {
            try(HeldLock held=new HeldLock(f)){
                future=worker.submit(()->{entered.countDown();return SketchStillExport.verify(context,first.metadata);});awaitBlocked(future,entered);
                Files.write(first.file.toPath(),changed);
            }
            ExecutionException error=assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));
            assertTrue(String.valueOf(error.getCause()).contains("SKETCH_STILL_OUTPUT_CHANGED"));
            assertArrayEquals(changed,Files.readAllBytes(first.file.toPath()));assertEquals(source,f.document());
            assertEquals(1,new ProjectStore(context).get(first.project.id).assets.size());
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test public void interruptedLockWaitRetainsCancellationAndPublishesNothingThenAnExactRetrySucceeds()throws Exception {
        Fixture f=new Fixture();CountDownLatch entered=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();
        Thread worker=new Thread(()->{entered.countDown();try{f.prepare();}catch(Throwable error){failure.set(error);}finally{flag.set(Thread.currentThread().isInterrupted());finished.countDown();}},"owned-png-lock-cancellation");
        try {
            try(HeldLock held=new HeldLock(f)){
                worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertFalse("A locked export must still be waiting",finished.await(750,TimeUnit.MILLISECONDS));
                worker.interrupt();assertTrue(finished.await(5,TimeUnit.SECONDS));
                assertTrue("Lock cancellation is a normal export cancellation: "+failure.get(),failure.get() instanceof InterruptedException);
                assertTrue("Do not consume the worker interrupt",flag.get());
                assertFalse(new File(f.generation(),"snapshot.json").exists());assertFalse(f.png().exists());assertNull(new ProjectStore(context).get(f.outputId()));
            }
            assertTrue(f.prepare().completed);
        } finally {worker.interrupt();worker.join(5000);assertFalse("No leaked waiting writer",worker.isAlive());}
    }

    @Test public void legacyMp4PreparationCooperatesWithTheSameGenerationLockAndRetainsItsOriginalRequest()throws Exception {
        Fixture f=new Fixture();JSONObject request=f.args();request.remove("format");request.remove("frame");
        request.put("aspect","original").put("quality","720p");String original=request.toString(),source=f.document();
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1);Future<SketchNativeExport.Prepared> future;
        try {
            try(HeldLock held=new HeldLock(f)){
                future=worker.submit(()->{entered.countDown();return SketchNativeExport.prepare(context,request);});awaitBlocked(future,entered);
                assertFalse(new File(f.generation(),"snapshot.json").exists());
            }
            SketchNativeExport.Prepared prepared=future.get(10,TimeUnit.SECONDS);assertFalse(prepared.completed);
            assertEquals(1,prepared.project.clips.size());assertEquals(original,request.toString());assertEquals(source,f.document());
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }

    private static void awaitBlocked(Future<?> work,CountDownLatch entered)throws Exception {
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertThrows("A cooperating owner holds the generation lock",TimeoutException.class,()->work.get(750,TimeUnit.MILLISECONDS));
    }
    private final class HeldLock implements AutoCloseable {
        final FileChannel channel;final FileLock lock;
        HeldLock(Fixture fixture)throws Exception {
            File file=fixture.lockFile();assertTrue(file.getParentFile().mkdirs()||file.getParentFile().isDirectory());
            channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);lock=channel.lock();
        }
        public void close()throws Exception {try{lock.close();}finally{channel.close();}}
    }
    private final class Fixture {
        final String id=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();final long revision;
        Fixture()throws Exception {
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create")
                    .put("width",256).put("height",256).put("frameCount",1).put("background","#00000000"));
            JSONObject ink=new JSONObject().put("id","kept_native_ink").put("kind","rect").put("frame",0).put("exposure",1)
                    .put("fill",true).put("color","#80112233").put("bounds",new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","ink")
                    .put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",ink))));
            try(SketchProjectStore sketches=new SketchProjectStore(context)){revision=sketches.get(id).getLong("revision");}
        }
        File generation(){return new File(context.getFilesDir(),"sketch_studio/"+id+"/exports/"+operation);}
        File png(){return new File(generation(),"frame_0000.png");}
        File lockFile()throws Exception {
            String key=SketchDocument.sha256(generation().getCanonicalPath().getBytes(StandardCharsets.UTF_8));
            return new File(context.getFilesDir(),".sketch_export_locks/"+key+".lock");
        }
        String outputId()throws Exception{return SketchStillExport.outputProjectId(id,operation);}
        String document()throws Exception{try(SketchProjectStore sketches=new SketchProjectStore(context)){return sketches.get(id).toString();}}
        JSONObject args()throws Exception{return new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",operation).put("format","png").put("frame",0);}
        SketchNativeExport.Prepared prepare()throws Exception{return SketchNativeExport.prepare(context,args());}
    }
}
