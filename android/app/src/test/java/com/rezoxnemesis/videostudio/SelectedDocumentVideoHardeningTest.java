package com.rezoxnemesis.videostudio;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real independent FileChannel coordination; this is not an actual multiprocess Android execution claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=SelectedDocumentVideoImportTest.VideoMetadata.class)
public class SelectedDocumentVideoHardeningTest {
    @Test public void revisionsRejectRoundedOverflowWhilePreservingLegitimateIntegralJsonNumbers()throws Exception{
        try(SelectedDocumentVideoImportTest.Fixture f=new SelectedDocumentVideoImportTest.Fixture()){
            for(String field:new String[]{"expectedUpdatedAt","sourceProjectUpdatedAt"}){
                for(Number valid:new Number[]{0L,1730000000123L,1730000000123d,new BigDecimal("1730000000123.0"),Long.MAX_VALUE,new BigInteger("9223372036854775807")}){
                    JSONObject accepted=ProjectAssetImport.validate(new JSONObject(f.request.toString()).put(field,valid));assertEquals(valid.longValue(),accepted.getLong(field));
                }
                for(Number invalid:new Number[]{Math.scalb(1d,63),new BigInteger("9223372036854775808"),new BigInteger("18446744073709551616"),new BigDecimal("9223372036854775808.0"),new BigDecimal("1730000000123.1")}){
                    JSONObject request=new JSONObject(f.request.toString()).put(field,invalid);assertThrows(field+" must reject "+invalid,Exception.class,()->ProjectAssetImport.validate(request));
                }
            }
            assertEquals(0,f.provider.opened);
        }
    }

    @Test public void independentStableGenerationOwnerBlocksJournalCopyAndPublicationAndWaitIsInterruptible()throws Exception{
        try(SelectedDocumentVideoImportTest.Fixture f=new SelectedDocumentVideoImportTest.Fixture()){
            String operationHash=SketchDocument.sha256(f.request.getString("operationId").getBytes(StandardCharsets.UTF_8));File generation=new File(f.context.getFilesDir().getCanonicalFile(),"selected_video_imports/"+f.destination.id+"/"+operationHash);
            String identityHash=SketchDocument.sha256(generation.getCanonicalPath().getBytes(StandardCharsets.UTF_8));File stable=new File(f.context.getFilesDir().getCanonicalFile(),".selected_video_import_locks/"+identityHash+".lock");assertTrue(stable.getParentFile().mkdirs()||stable.getParentFile().isDirectory());
            CountDownLatch entered=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
            Thread writer=new Thread(()->{entered.countDown();try{ProjectAssetImport.execute(f.context,f.request,(bytes,total)->{});}catch(Throwable error){failure.set(error);}finally{interrupted.set(Thread.currentThread().isInterrupted());finished.countDown();}},"selected-video-independent-channel-waiter");
            Object inode;
            try(FileChannel channel=FileChannel.open(stable.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                inode=Files.readAttributes(stable.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();assertNotNull("Host fixture must identify the stable inode",inode);writer.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
                try{
                    assertFalse("A separate cooperating FileChannel owns this operation; no source read/write may start",finished.await(750,TimeUnit.MILLISECONDS));
                    assertEquals(0,f.provider.opened);assertFalse(new File(generation,"receipt.json").exists());assertFalse(new File(generation,"source.partial").exists());assertFalse(new File(generation,"source.video").exists());assertEquals(0,f.store.get(f.destination.id).assets.size());
                    writer.interrupt();assertTrue("Stable lock waiting must remain cancellable",finished.await(5,TimeUnit.SECONDS));assertTrue(String.valueOf(failure.get()),failure.get() instanceof InterruptedException);assertTrue("Preserve the waiter's interrupt flag",interrupted.get());assertEquals(0,f.store.get(f.destination.id).assets.size());
                }finally{writer.interrupt();writer.join(5000);assertFalse("No leaked selected-video writer",writer.isAlive());}
            }
            JSONObject first=f.execute(),again=f.execute();assertEquals(first.getString("assetId"),again.getString("assetId"));assertTrue(again.getBoolean("replayed"));assertEquals(1,f.store.get(f.destination.id).assets.size());assertEquals(0,f.store.get(f.destination.id).clips.size());
            assertTrue("The stable operation lock must never be deleted",stable.isFile());assertEquals("Retries must coordinate on the same OS inode",inode,Files.readAttributes(stable.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
            try(FileChannel released=FileChannel.open(stable.toPath(),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock available=released.tryLock()){assertNotNull("Successful/replay paths must release the stable lock",available);}
        }
    }
}
