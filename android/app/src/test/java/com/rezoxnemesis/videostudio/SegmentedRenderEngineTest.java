package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Lifecycle evidence uses real source hashes/SQLite and explicitly synthetic media/codec proofs. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SegmentedRenderEngineTest {
    private static final class Queue implements Executor {
        final java.util.concurrent.ConcurrentLinkedQueue<Runnable> work=new java.util.concurrent.ConcurrentLinkedQueue<>();
        public void execute(Runnable task){work.add(task);}
        void drain(){Runnable task;while((task=work.poll())!=null)try{task.run();}finally{Thread.interrupted();}}
    }
    private interface Action {void run(RenderSessionStore.Kind kind,long start,long end,File file,BooleanSupplier cancelled,SegmentedRenderEngine.Progress progress)throws Exception;}
    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();
        final File root=new File(context.getFilesDir(),"engine-fixture-"+UUID.randomUUID()),original;
        final ProjectStore.Project project=new ProjectStore.Project();
        final Queue worker=new Queue(),events=new Queue();
        final List<String> stages=new ArrayList<>();final List<File> stageFiles=new ArrayList<>();
        final AtomicReference<JSONObject> result=new AtomicReference<>();final AtomicReference<String> error=new AtomicReference<>();
        final AtomicInteger joins=new AtomicInteger(),completed=new AtomicInteger(),failures=new AtomicInteger();
        final boolean audio;Action action=(kind,start,end,file,cancelled,progress)->{};
        boolean incompatibleOnce,alwaysIncompatible;int outputs,mismatchesRemaining;RenderSessionStore.Faults journalFaults=point->{};
        Fixture(boolean audio)throws Exception {
            this.audio=audio;assertTrue(root.mkdirs());original=new File(root,"original.png");Files.write(original.toPath(),new byte[]{1,2,3});
            project.id="lifecycle-project";project.name="Owner";project.revision=1;project.ensureTimelineDefaults();
            var asset=new ProjectStore.Asset();asset.id="image";asset.mime="image/png";asset.uri=Uri.fromFile(original).toString();project.assets.add(asset);
            var clip=new ProjectStore.Clip();clip.id="clip";clip.assetId=asset.id;clip.trackId=project.tracks.get(0).id;clip.outMs=12000;project.clips.add(clip);
        }
        JSONObject proof(File file,boolean video)throws Exception {
            var bytes=Files.readAllBytes(file.toPath());var hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));
            return new JSONObject().put("playable",true).put("sha256",hash.toString()).put("sizeBytes",bytes.length).put("durationMs",12000).put("hasVideo",video).put("hasAudio",!video).put("codecRoute","conservative");
        }
        RenderSessionStore journal(){return new RenderSessionStore(context,new File(root,"sessions"),"same-process",this::proof,journalFaults);}
        SegmentedRenderEngine engine(Executor callbacks){
            return new SegmentedRenderEngine((p,aspect,quality,cancelled)->new RenderSourceIdentity(new File(root,"inputs"),uri->new FileInputStream(new File(Uri.parse(uri).getPath()))).capture(p,aspect,quality,cancelled),
                this::journal,(p,kind,start,end,file,aspect,quality,route,cancelled,progress)->{
                    stages.add(kind+":"+start+":"+end+":"+route);stageFiles.add(file);action.run(kind,start,end,file,cancelled,progress);SegmentMediaMuxer.check(cancelled);
                    Files.write(file.toPath(),new byte[]{(byte)(kind.ordinal()+1),(byte)(start/5_000_000),(byte)stages.size()});return proof(file,kind==RenderSessionStore.Kind.VIDEO).put("codecRoute",route.name().toLowerCase(Locale.ROOT)).put("videoEncoder",kind==RenderSessionStore.Kind.VIDEO?"synthetic-avc":"").put("audioEncoder",kind==RenderSessionStore.Kind.AUDIO?"synthetic-aac":"");
                },(p,video,sound,file,cancelled)->{
                    SegmentMediaMuxer.check(cancelled);joins.incrementAndGet();if(alwaysIncompatible||incompatibleOnce||mismatchesRemaining>0){incompatibleOnce=false;if(mismatchesRemaining>0)mismatchesRemaining--;throw new SegmentMediaMuxer.IncompatibleConfigurationException("Synthetic header mismatch");}
                    assertEquals(3,video.size());for(int i=0;i<video.size();i++){assertEquals(i*5_000_000L,video.get(i).startUs);assertTrue(video.get(i).file.isFile());}
                    if(audio){assertNotNull(sound);assertEquals(0,sound.startUs);assertEquals(12_000_000,sound.endUs);}else assertNull(sound);
                    Files.write(file.toPath(),new byte[]{7,8,9},java.nio.file.StandardOpenOption.CREATE_NEW);return proof(file,true);
                },new SegmentedRenderEngine.Timing(){public long durationUs(ProjectStore.Project p){return TimelineCompositionFactory.programDurationUs(p);}public boolean hasAudio(ProjectStore.Project p){return audio;}},worker,callbacks);
        }
        NativeRenderEngine.Handle start(File output,Executor callbacks){result.set(null);error.set(null);return engine(callbacks).export(project,output,"16:9","720p",new NativeRenderEngine.Listener(){public void onProgress(int value,String detail){}public void onCompleted(File file,JSONObject proof){result.set(proof);completed.incrementAndGet();}public void onError(String detail){error.set(detail);failures.incrementAndGet();}});}
        NativeRenderEngine.Handle start(){return start(new File(root,"output-"+(outputs++)+".mp4"),Runnable::run);}
        void run(){worker.drain();}
        void assertSuccess(){assertNull(error.get());assertNotNull(result.get());}
    }
    @Test public void windowsRetainExactShortTailAndRejectInvalidOrUnboundedPlans(){assertEquals(3,SegmentedRenderEngine.windowCount(10_000_001));var tail=SegmentedRenderEngine.window(10_000_001,2);assertEquals(10_000_000,tail.startUs);assertEquals(10_000_001,tail.endUs);assertThrows(IllegalArgumentException.class,()->SegmentedRenderEngine.windowCount(0));assertThrows(IllegalArgumentException.class,()->SegmentedRenderEngine.windowCount(Long.MAX_VALUE));}
    @Test public void successfulExportUsesOriginalGraphAndThreeWindowsWithoutAudio()throws Exception {var f=new Fixture(false);String before=f.project.snapshotJson().toString();f.start();f.run();f.assertSuccess();assertEquals(3,f.stages.size());assertEquals(before,f.project.snapshotJson().toString());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(f.original.toPath()));assertEquals(0,f.result.get().getInt("reusedVideoWindows"));assertEquals(3,f.result.get().getInt("renderedVideoWindows"));}
    @Test public void unchangedRetryReusesEveryVerifiedWindowWithoutEncoding()throws Exception {var f=new Fixture(false);f.start();f.run();f.assertSuccess();String session=f.result.get().getString("renderSessionId");f.start();f.run();f.assertSuccess();assertEquals(session,f.result.get().getString("renderSessionId"));assertEquals(3,f.stages.size());assertEquals(3,f.result.get().getInt("reusedVideoWindows"));}
    @Test public void retryAfterFailureKeepsCompletedWindowAndRetiresOnlyPendingStage()throws Exception {var f=new Fixture(false);var failed=new AtomicBoolean();f.action=(kind,start,end,file,cancelled,progress)->{if(start==5_000_000&&failed.compareAndSet(false,true)){Files.write(file.toPath(),new byte[]{4});throw new IOException("Synthetic encoder interrupted");}};f.start();f.run();assertNotNull(f.error.get());assertNull(f.result.get());File unfinished=f.stageFiles.get(1);f.start();f.run();f.assertSuccess();assertEquals(4,f.stages.size());assertEquals(1,f.result.get().getInt("reusedVideoWindows"));assertFalse(unfinished.exists());assertNotEquals(unfinished,f.stageFiles.get(2));}
    @Test public void corruptedWindowAloneIsRenderedAgain()throws Exception {var f=new Fixture(false);f.start();f.run();f.assertSuccess();File session=new File(new File(f.root,"sessions"),f.result.get().getString("renderSessionId"));byte[] other=Files.readAllBytes(new File(session,"video_0.mp4").toPath());Files.write(new File(session,"video_1.mp4").toPath(),new byte[]{0});f.start();f.run();f.assertSuccess();assertEquals(4,f.stages.size());assertTrue(f.stages.get(3).startsWith("VIDEO:5000000:"));assertEquals(2,f.result.get().getInt("reusedVideoWindows"));assertArrayEquals(other,Files.readAllBytes(new File(session,"video_0.mp4").toPath()));}
    @Test public void settingsOrSourceChangesCannotReuseOldSession()throws Exception {var f=new Fixture(false);f.start();f.run();f.assertSuccess();String old=f.result.get().getString("renderSessionId");f.project.settings.put("exportBitrate",4_000_000);f.start();f.run();f.assertSuccess();assertNotEquals(old,f.result.get().getString("renderSessionId"));String settings=f.result.get().getString("renderSessionId");Files.write(f.original.toPath(),new byte[]{6,5,4});f.start();f.run();f.assertSuccess();assertNotEquals(settings,f.result.get().getString("renderSessionId"));assertEquals(9,f.stages.size());}
    @Test public void continuousAudioIsRenderedOnceAndReusedAsWholeProgram()throws Exception {var f=new Fixture(true);f.start();f.run();f.assertSuccess();assertEquals(4,f.stages.size());assertTrue(f.stages.get(3).startsWith("AUDIO:0:12000000:"));f.start();f.run();f.assertSuccess();assertEquals(4,f.stages.size());assertTrue(f.result.get().getBoolean("reusedContinuousAudio"));}
    @Test public void stopSuppressesAlreadyQueuedCompletionCallbacks()throws Exception {var f=new Fixture(false);var handle=f.start(new File(f.root,"queued.mp4"),f.events);f.run();assertTrue(f.events.work.size()>0);handle.cancel();f.events.drain();assertNull(f.result.get());assertNull(f.error.get());assertEquals(0,f.completed.get());f.start();f.run();f.assertSuccess();assertEquals(3,f.stages.size());}
    @Test public void stopInterruptsActiveStageAndKeepsVerifiedPrecedingWindow()throws Exception {var f=new Fixture(false);var reached=new CountDownLatch(1);f.action=(kind,start,end,file,cancelled,progress)->{if(start==5_000_000){reached.countDown();new CountDownLatch(1).await();}};var handle=f.start();Thread thread=new Thread(f.worker::drain);thread.start();assertTrue(reached.await(5,TimeUnit.SECONDS));handle.cancel();thread.join(5000);assertFalse(thread.isAlive());assertNull(f.result.get());assertNull(f.error.get());assertEquals(0,f.joins.get());f.action=(kind,start,end,file,cancelled,progress)->{};f.start();f.run();f.assertSuccess();assertEquals(1,f.result.get().getInt("reusedVideoWindows"));}
    @Test public void incompatibleVideoIsRebuiltOnOneConsistentRouteWithoutRepeatingAudio()throws Exception {var f=new Fixture(true);f.incompatibleOnce=true;f.start();f.run();f.assertSuccess();assertEquals(7,f.stages.size());assertEquals(1,f.stages.stream().filter(s->s.startsWith("AUDIO:")).count());assertEquals(2,f.joins.get());assertTrue(f.result.get().getBoolean("codecConfigurationRepaired"));for(int i=4;i<7;i++)assertTrue(f.stages.get(i).endsWith("SOFTWARE_DECODER"));assertNotEquals(f.stageFiles.get(0),f.stageFiles.get(4));}
    @Test public void repeatedIncompatibilityTerminatesWithoutPublishingBadBytes()throws Exception {var f=new Fixture(false);f.alwaysIncompatible=true;f.start();f.run();assertNotNull(f.error.get());assertNull(f.result.get());assertTrue(f.joins.get()<=3);assertEquals(0,f.completed.get());}
    @Test public void outputAdmissionPreservesOriginalAndExistingUnrelatedFiles()throws Exception {var f=new Fixture(false);f.start(f.original,Runnable::run);f.run();assertNotNull(f.error.get());assertTrue(f.stages.isEmpty());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(f.original.toPath()));File existing=new File(f.root,"existing.mp4");Files.write(existing.toPath(),new byte[]{4,5,6});f.start(existing,Runnable::run);f.run();assertNotNull(f.error.get());assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(existing.toPath()));assertTrue(f.stages.isEmpty());}
    @Test public void retiredStageProgressCannotDeliverOrReplaceSuccessfulTerminalResult()throws Exception{
        var f=new Fixture(false);var retained=new AtomicReference<SegmentedRenderEngine.Progress>();f.action=(kind,start,end,file,cancelled,progress)->retained.compareAndSet(null,progress);
        var late=new AtomicBoolean();var notifications=new AtomicInteger();f.engine(Runnable::run).export(f.project,new File(f.root,"late.mp4"),"16:9","720p",new NativeRenderEngine.Listener(){public void onProgress(int value,String detail){notifications.incrementAndGet();if(late.get())throw new IllegalStateException("Finished owner job");}public void onCompleted(File file,JSONObject proof){f.completed.incrementAndGet();}public void onError(String detail){f.failures.incrementAndGet();}});
        f.run();assertEquals(1,f.completed.get());int before=notifications.get();late.set(true);retained.get().report(70,"Old encoder callback");assertEquals("Retired stage progress must be ignored",before,notifications.get());assertEquals(0,f.failures.get());assertEquals(1,f.completed.get());
    }
    @Test public void ownerGraphIsFrozenBeforeQueuedWorkAndEncoderMetadataUsesItsActualTrack()throws Exception{
        var f=new Fixture(true);long duration=TimelineCompositionFactory.programDurationUs(f.project);f.start();f.project.clips.get(0).outMs=1000;f.run();f.assertSuccess();assertEquals(duration,Long.parseLong(f.stages.get(2).split(":")[2]));assertEquals("synthetic-aac",f.result.get().getString("audioEncoder"));assertEquals("synthetic-avc",f.result.get().getString("videoEncoder"));
    }
    @Test public void delayedProgressQueueIsBoundedDuringEncodingAndCheckpointReuse()throws Exception{
        var f=new Fixture(false);f.action=(kind,start,end,file,cancelled,progress)->{for(int i=0;i<10_000;i++)progress.report(i%100,"Synthetic busy encoder");};f.start(new File(f.root,"coalesced.mp4"),f.events);f.run();assertTrue("Only one progress callback and one completion may remain pending",f.events.work.size()<=2);f.events.drain();f.assertSuccess();assertEquals(1,f.completed.get());
        f.start(new File(f.root,"cached.mp4"),f.events);f.run();assertTrue("Cached windows cannot accumulate progress callbacks",f.events.work.size()<=2);f.events.drain();f.assertSuccess();assertEquals(2,f.completed.get());assertEquals(3,f.stages.size());
    }
    @Test public void failedCompletionPersistenceExplicitlyWakesItsWaitingCaller()throws Exception{
        var f=new Fixture(false);var waiter=new CountDownLatch(1);f.engine(Runnable::run).export(f.project,new File(f.root,"throwing.mp4"),"16:9","720p",new NativeRenderEngine.Listener(){public void onProgress(int value,String detail){}public void onCompleted(File file,JSONObject proof){throw new IllegalStateException("Terminal checkpoint persistence failed before signaling");}public void onError(String detail){f.failures.incrementAndGet();waiter.countDown();}});f.run();assertTrue("Explicit error must wake a waiting caller",waiter.await(1,TimeUnit.SECONDS));assertEquals(0,f.completed.get());assertEquals(1,f.failures.get());
    }
    @Test public void interruptedCodecRepairResumesChosenRouteAndKeepsItsVerifiedPrefixAndAudio()throws Exception{
        var f=new Fixture(true);f.incompatibleOnce=true;var interrupted=new AtomicBoolean();f.action=(kind,start,end,file,cancelled,progress)->{if(kind==RenderSessionStore.Kind.VIDEO&&start==5_000_000&&file.getName().contains("_g2.")&&interrupted.compareAndSet(false,true))throw new IOException("Synthetic interrupted codec rebuild");};f.start();f.run();assertNotNull(f.error.get());assertNull(f.result.get());assertEquals(6,f.stages.size());
        File prefix=new File(f.stageFiles.get(4).getParentFile(),"video_0.mp4");byte[] expected=Files.readAllBytes(prefix.toPath());f.start();f.run();f.assertSuccess();assertEquals(8,f.stages.size());assertEquals(1,f.result.get().getInt("reusedVideoWindows"));assertEquals(2,f.result.get().getInt("renderedVideoWindows"));assertTrue(f.result.get().getBoolean("reusedContinuousAudio"));assertArrayEquals(expected,Files.readAllBytes(prefix.toPath()));for(int i=6;i<8;i++)assertTrue("Missing repaired windows must use the persisted route",f.stages.get(i).endsWith("SOFTWARE_DECODER"));
    }
    @Test public void queuedProgressFailureOrCancellationSuppressesPreparedCompletion()throws Exception{
        for(boolean stop:new boolean[]{false,true}){var f=new Fixture(false);f.engine(f.events).export(f.project,new File(f.root,"rejected-progress.mp4"),"16:9","720p",new NativeRenderEngine.Listener(){public void onProgress(int value,String detail){if(stop)throw new CancellationException("Owner STOP");throw new IllegalStateException("Job checkpoint rejected");}public void onCompleted(File file,JSONObject proof){f.completed.incrementAndGet();}public void onError(String detail){f.failures.incrementAndGet();}});f.run();f.events.drain();assertEquals(0,f.completed.get());assertEquals(stop?0:1,f.failures.get());}
    }
    @Test public void interruptedLastRouteRetirementReplaysOnSameRouteAndKeepsAudio()throws Exception{
        var f=new Fixture(true);f.mismatchesRemaining=2;var retired=new AtomicInteger();f.journalFaults=point->{if(point.equals("invalidated_final")&&retired.incrementAndGet()==4)throw new IOException("Synthetic death during final-route retirement");};f.start();f.run();assertNotNull(f.error.get());assertNull(f.result.get());assertEquals(7,f.stages.size());
        f.start();f.run();f.assertSuccess();assertEquals("Every old-route video window must be replaced on the saved final route",10,f.stages.size());assertEquals(1,f.stages.stream().filter(s->s.startsWith("AUDIO:")).count());assertTrue(f.result.get().getBoolean("reusedContinuousAudio"));for(int i=7;i<10;i++)assertTrue(f.stages.get(i).endsWith("SOFTWARE_CODECS"));
    }
}
