package com.rezoxnemesis.videostudio;

import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class RenderSourceIdentityTest {
    private final File root=new File(RuntimeEnvironment.getApplication().getFilesDir(),"source-fixture-"+java.util.UUID.randomUUID());
    private final File inputs=new File(root,"inputs");
    private final RenderSourceIdentity identity=new RenderSourceIdentity(inputs,uri->new FileInputStream(new File(Uri.parse(uri).getPath())));
    private File media(String name,byte...bytes)throws Exception{assertTrue(root.isDirectory()||root.mkdirs());File f=new File(root,name);Files.write(f.toPath(),bytes);return f;}
    private File pinned(RenderSourceIdentity.Snapshot s){return new File(Uri.parse(s.boundProject.assets.get(0).uri).getPath());}
    private ProjectStore.Project project(File file){
        ProjectStore.Project p=new ProjectStore.Project();p.id="bound-project";p.name="Owner";p.revision=4;p.ensureTimelineDefaults();
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="image";a.uri=Uri.fromFile(file).toString();a.mime="image/png";p.assets.add(a);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="image-clip";c.assetId=a.id;c.trackId=p.tracks.get(0).id;c.outMs=12000;p.clips.add(c);return p;
    }
    @Test public void pinnedOriginalBytesAndOwnerGraphSurviveLaterSourceEdits()throws Exception{
        File source=media("original.png",(byte)1,(byte)2,(byte)3);ProjectStore.Project p=project(source);String before=p.snapshotJson().toString();var snapshot=identity.capture(p,"16:9","720p",()->false);
        assertTrue(snapshot.sessionId.matches("[0-9a-f]{64}"));assertNotEquals(p.assets.get(0).uri,snapshot.boundProject.assets.get(0).uri);assertEquals(before,p.snapshotJson().toString());Files.write(source.toPath(),new byte[]{9,8,7});assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(pinned(snapshot).toPath()));
        assertNotEquals(snapshot.sessionId,identity.capture(p,"16:9","720p",()->false).sessionId);
    }
    @Test public void canonicalGraphKeyOrderReusesTheSameIdentity()throws Exception{
        ProjectStore.Project p=project(media("same.png",(byte)1));p.settings=new JSONObject().put("fps",30).put("exportBitrate",2_000_000);String first=identity.capture(p,"16:9","720p",()->false).sessionId;
        p.settings=new JSONObject().put("exportBitrate",2_000_000).put("fps",30);assertEquals(first,identity.capture(p,"16:9","720p",()->false).sessionId);
    }
    @Test public void projectRevisionSettingsAndFinalFormatBindTheSession()throws Exception{
        ProjectStore.Project p=project(media("same.png",(byte)1));String first=identity.capture(p,"16:9","720p",()->false).sessionId;
        assertNotEquals(first,identity.capture(p,"9:16","720p",()->false).sessionId);assertNotEquals(first,identity.capture(p,"16:9","1080p",()->false).sessionId);p.revision++;assertNotEquals(first,identity.capture(p,"16:9","720p",()->false).sessionId);p.revision--;p.settings.put("fps",60);assertNotEquals(first,identity.capture(p,"16:9","720p",()->false).sessionId);
    }
    @Test public void identicalAssetBytesShareOnePinnedObject()throws Exception{
        File a=media("a.png",(byte)1,(byte)2),b=media("b.png",(byte)1,(byte)2);ProjectStore.Project p=project(a);ProjectStore.Asset second=new ProjectStore.Asset();second.id="second";second.uri=Uri.fromFile(b).toString();second.mime="image/png";p.assets.add(second);
        ProjectStore.Clip c=new ProjectStore.Clip();c.id="second-clip";c.assetId=second.id;c.trackId=p.tracks.get(0).id;c.startMs=12000;c.outMs=12000;p.clips.add(c);var snapshot=identity.capture(p,"16:9","720p",()->false);
        assertEquals(snapshot.boundProject.assets.get(0).uri,snapshot.boundProject.assets.get(1).uri);assertEquals(2,snapshot.manifest.getJSONArray("sources").length());assertEquals(1,inputs.listFiles(f->!f.getName().endsWith(".part")).length);
    }
    @Test public void corruptPinnedObjectIsRepairedBeforeReuse()throws Exception{
        ProjectStore.Project p=project(media("original.png",(byte)1,(byte)2,(byte)3));var snapshot=identity.capture(p,"16:9","720p",()->false);Files.write(pinned(snapshot).toPath(),new byte[]{7,7,7});var recovered=identity.capture(p,"16:9","720p",()->false);
        assertEquals(snapshot.sessionId,recovered.sessionId);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(pinned(recovered).toPath()));
    }
    @Test public void staleSourceStageIsReclaimedEvenWhenPinnedObjectIsReusable()throws Exception{
        ProjectStore.Project p=project(media("same.png",(byte)1));var snapshot=identity.capture(p,"16:9","720p",()->false);File stage=new File(pinned(snapshot).getPath()+".part");Files.write(stage.toPath(),new byte[]{8,8});assertEquals(snapshot.sessionId,identity.capture(p,"16:9","720p",()->false).sessionId);assertFalse("A dead input-copy stage must not consume space indefinitely",stage.exists());
    }
    @Test public void abandonedStageIsReclaimedAfterTheOriginalSourceChanges()throws Exception{
        File original=media("changing.png",(byte)1);ProjectStore.Project p=project(original);var first=identity.capture(p,"16:9","720p",()->false);File deadStage=new File(pinned(first).getPath()+".part");Files.write(deadStage.toPath(),new byte[]{1,1});Files.write(original.toPath(),new byte[]{9});var second=identity.capture(p,"16:9","720p",()->false);
        assertFalse("A previous-hash stage remained after death and a source change",deadStage.exists());assertTrue("Completed pins belonging to another captured session must survive",pinned(first).exists());assertNotEquals(first.sessionId,second.sessionId);assertArrayEquals(new byte[]{1},Files.readAllBytes(pinned(first).toPath()));
    }
    @Test public void growingSourceIsStoppedBeforeUnboundedSnapshotWrites()throws Exception{
        ProjectStore.Project p=project(media("growing.png",(byte)1));AtomicInteger opens=new AtomicInteger(),copied=new AtomicInteger();
        RenderSourceIdentity growing=new RenderSourceIdentity(inputs,uri->{if(opens.getAndIncrement()==0)return new ByteArrayInputStream(new byte[]{1,2,3});return new ByteArrayInputStream(new byte[600000]){public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(n>0)copied.addAndGet(n);return n;}};});
        try{growing.capture(p,"16:9","720p",()->false);fail("Growing source accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("changed"));}
        assertTrue("Source grew beyond its captured bound",copied.get()<=256*1024);assertEquals(0,inputs.listFiles().length);
    }
    @Test public void cancellationDuringSnapshotCopyClearsOnlyItsOwnStage()throws Exception{
        ProjectStore.Project p=project(media("cancel-copy.png",(byte)1));AtomicInteger opens=new AtomicInteger();AtomicBoolean cancelled=new AtomicBoolean();
        RenderSourceIdentity copying=new RenderSourceIdentity(inputs,uri->{if(opens.getAndIncrement()==0)return new ByteArrayInputStream(new byte[600000]);return new ByteArrayInputStream(new byte[600000]){public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);cancelled.set(true);return n;}};});
        try{copying.capture(p,"16:9","720p",cancelled::get);fail("Cancelled copy completed");}catch(InterruptedIOException expected){}
        assertEquals(0,inputs.listFiles().length);assertTrue(new File(root,"cancel-copy.png").exists());
    }
    @Test public void cancellingAWaitingCaptureLeavesTheActiveWritersStageAlone()throws Exception{
        ProjectStore.Project p=project(media("locked.png",(byte)1));var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var secondOpened=new java.util.concurrent.CountDownLatch(1);var secondFinished=new java.util.concurrent.CountDownLatch(1);AtomicInteger opens=new AtomicInteger();AtomicBoolean cancelled=new AtomicBoolean();var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        RenderSourceIdentity first=new RenderSourceIdentity(inputs,uri->{if(opens.getAndIncrement()==0)return new ByteArrayInputStream(new byte[]{1,2,3});return new ByteArrayInputStream(new byte[]{1,2,3}){public synchronized int read(byte[] b,int off,int len){entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return -1;}return super.read(b,off,len);}};});
        RenderSourceIdentity second=new RenderSourceIdentity(inputs,uri->{secondOpened.countDown();return new ByteArrayInputStream(new byte[]{1,2,3});});var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var running=pool.submit(()->first.capture(p,"16:9","720p",()->false));
        Thread waiting=new Thread(()->{try{second.capture(p,"16:9","720p",cancelled::get);}catch(Throwable failure){error.set(failure);}finally{secondFinished.countDown();}},"cancelled-pin-waiter");
        try{
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));waiting.start();assertTrue(secondOpened.await(5,java.util.concurrent.TimeUnit.SECONDS));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(waiting.getState()!=Thread.State.BLOCKED&&waiting.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.sleep(5);
            assertTrue("Second capture must actually be waiting for the writer",waiting.getState()==Thread.State.BLOCKED||waiting.getState()==Thread.State.TIMED_WAITING);cancelled.set(true);
            assertTrue("STOP must release a waiting capture while the other copy remains blocked",secondFinished.await(1,java.util.concurrent.TimeUnit.SECONDS));assertTrue(error.get() instanceof InterruptedIOException);
            assertEquals("Cancelling the waiting call must preserve the active stage",1,inputs.listFiles(f->f.getName().endsWith(".part")).length);
        }finally{release.countDown();running.get(5,java.util.concurrent.TimeUnit.SECONDS);waiting.join(5000);pool.shutdownNow();}
    }
    @Test public void changedBytesBetweenHashAndSnapshotCopyAreRejected()throws Exception{
        ProjectStore.Project p=project(media("changing.png",(byte)1));AtomicInteger reads=new AtomicInteger();RenderSourceIdentity changing=new RenderSourceIdentity(inputs,uri->new ByteArrayInputStream(reads.getAndIncrement()==0?new byte[]{1,2,3}:new byte[]{9,8,7}));
        try{changing.capture(p,"16:9","720p",()->false);fail("Changed source was pinned under the old checksum");}catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("changed"));}
        assertEquals(0,inputs.listFiles().length);
    }
    @Test public void cancellationDuringReadClosesInputAndPublishesNoSnapshot()throws Exception{
        ProjectStore.Project p=project(media("cancel.png",(byte)1));AtomicBoolean cancelled=new AtomicBoolean(),closed=new AtomicBoolean();
        RenderSourceIdentity cancelling=new RenderSourceIdentity(inputs,uri->new ByteArrayInputStream(new byte[600000]){public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);cancelled.set(true);return n;}public void close(){closed.set(true);}});
        try{cancelling.capture(p,"16:9","720p",cancelled::get);fail("Cancelled source completed");}catch(InterruptedIOException expected){}
        assertTrue(closed.get());assertTrue(!inputs.exists()||inputs.listFiles().length==0);
    }
    @Test public void missingUsedSourceFailsWithoutChangingTheOwnerUri()throws Exception{
        ProjectStore.Project p=project(new File(root,"absent.png"));String uri=p.assets.get(0).uri;
        try{identity.capture(p,"16:9","720p",()->false);fail("Missing source accepted");}catch(IOException expected){}
        assertEquals(uri,p.assets.get(0).uri);
    }
    @Test public void generatedLayerSourcesAreAlsoPinned()throws Exception{
        File original=media("original.png",(byte)1),plate=media("plate.png",(byte)2),background=media("background.png",(byte)3);ProjectStore.Project p=project(original);var clip=p.clips.get(0);clip.effects.put("animatedScene",true).put("foregroundUri",Uri.fromFile(plate).toString()).put("backgroundUri",Uri.fromFile(background).toString());String originalPlate=clip.effects.getString("foregroundUri");
        var snapshot=identity.capture(p,"16:9","720p",()->false);RenderSourceIdentity.validate(snapshot);assertEquals(3,snapshot.manifest.getJSONArray("sources").length());assertNotEquals(originalPlate,snapshot.boundProject.clips.get(0).effects.getString("foregroundUri"));Files.write(plate.toPath(),new byte[]{9});RenderSourceIdentity.validate(snapshot);assertArrayEquals(new byte[]{2},Files.readAllBytes(new File(Uri.parse(snapshot.boundProject.clips.get(0).effects.getString("foregroundUri")).getPath()).toPath()));assertEquals(originalPlate,clip.effects.getString("foregroundUri"));
    }
    @Test public void sourceMimeHintReachesTheWholeAndWindowMediaItems()throws Exception{
        ProjectStore.Project p=project(media("original.jpg",(byte)1));p.assets.get(0).mime="image/jpeg";var snapshot=identity.capture(p,"16:9","720p",()->false);var factory=new TimelineCompositionFactory(RuntimeEnvironment.getApplication());
        assertEquals("image/jpeg",factory.build(snapshot.boundProject,"16:9","720p",false).sequences.get(0).editedMediaItems.get(0).mediaItem.localConfiguration.mimeType);
        assertEquals("image/jpeg",factory.buildVideoWindow(snapshot.boundProject,"16:9","720p",new TimelineWindow(0,5_000_000)).sequences.get(0).editedMediaItems.get(0).mediaItem.localConfiguration.mimeType);
    }
    @Test public void excludedSoloSourcesAreNotOpenedAndRawOwnerDefaultsStayRaw()throws Exception{
        ProjectStore.Project p=project(media("selected.png",(byte)1));p.tracks.get(0).solo=true;ProjectStore.Track excluded=new ProjectStore.Track();excluded.id="excluded";p.tracks.add(excluded);ProjectStore.Clip absent=new ProjectStore.Clip();absent.id="absent";absent.assetId="missing";absent.trackId=excluded.id;absent.outMs=12000;p.clips.add(absent);assertEquals(1,identity.capture(p,"16:9","720p",()->false).manifest.getJSONArray("sources").length());
        p.clips.remove(absent);p.tracks.clear();p.clips.get(0).trackId="";p.clips.get(0).startMs=-1;identity.capture(p,"16:9","720p",()->false);assertTrue(p.tracks.isEmpty());assertEquals(-1,p.clips.get(0).startMs);assertEquals("",p.clips.get(0).trackId);
    }
}
