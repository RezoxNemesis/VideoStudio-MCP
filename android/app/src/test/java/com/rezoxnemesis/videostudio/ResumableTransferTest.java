package com.rezoxnemesis.videostudio;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class ResumableTransferTest {
    @Test public void transferJournalRoundTripsOffsetsBeyondFiveGigabytes() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        TransferJournal journal=new TransferJournal(context);
        journal.clearAll();
        long offset=5L*1024L*1024L*1024L+12345L;
        TransferJournal.Entry entry=new TransferJournal.Entry(
                "transfer-1",
                "https://example.test/large.mp4",
                "/tmp/large.mp4.partial",
                8L*1024L*1024L*1024L,
                offset,
                "\"etag-1\"",
                "Wed, 07 Oct 2026 10:00:00 GMT",
                "",
                "running"
        );
        journal.save(entry);
        TransferJournal.Entry restored=journal.get("transfer-1");
        assertNotNull(restored);
        assertEquals(offset,restored.completedBytes);
        assertTrue(restored.expectedBytes>Integer.MAX_VALUE);
        journal.remove("transfer-1");
    }

    @Test public void resumeIsAcceptedOnlyForMatchingPartialContent() {
        assertTrue(ResumableTransferManager.canAppendResume(
                5L*1024L*1024L*1024L,
                206,
                "bytes 5368709120-8589934591/8589934592"
        ));
        assertFalse(ResumableTransferManager.canAppendResume(1024L,200,null));
        assertFalse(ResumableTransferManager.canAppendResume(1024L,206,"bytes 0-99/1000"));
    }

    @Test public void progressMathStaysValidAboveFiveGigabytesAndForUnknownLength() {
        long expected=8L*1024L*1024L*1024L;
        assertEquals(62,ResumableTransferManager.progressPercent(5L*1024L*1024L*1024L,expected));
        assertEquals(-1,ResumableTransferManager.progressPercent(123456L,-1L));
    }

    @Test public void streamingCopyUsesBoundedBufferAndAppendsFromCheckpoint() throws Exception {
        assertTrue(ResumableTransferManager.BUFFER_BYTES>0);
        assertTrue(ResumableTransferManager.BUFFER_BYTES<=512*1024);

        File file=new File(RuntimeEnvironment.getApplication().getCacheDir(),"resume-copy.bin");
        try(RandomAccessFile out=new RandomAccessFile(file,"rw")){
            out.setLength(3);
            out.seek(3);
            byte[] payload=new byte[1024*1024+17];
            Arrays.fill(payload,(byte)7);
            long copied=ResumableTransferManager.copyStream(
                    new ByteArrayInputStream(payload),out,3,null
            );
            assertEquals(payload.length,copied);
            assertEquals(payload.length+3L,out.length());
        } finally {
            file.delete();
        }
    }

    @Test public void partialTargetNeverMasqueradesAsCompletedMedia() {
        File target=new File(RuntimeEnvironment.getApplication().getCacheDir(),"movie.mp4");
        File partial=ResumableTransferManager.partialFileFor(target);
        assertNotEquals(target.getAbsolutePath(),partial.getAbsolutePath());
        assertTrue(partial.getName().endsWith(".partial"));
    }
}
