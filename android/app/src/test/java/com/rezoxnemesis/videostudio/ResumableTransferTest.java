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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
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

    private static final class FakeConnection extends HttpURLConnection {
        private final int code;
        private final byte[] body;
        private final Map<String,String> headers=new HashMap<>();
        FakeConnection(int code,byte[] body) throws Exception {
            super(new URL("https://example.test/media"));
            this.code=code;this.body=body;
        }
        FakeConnection header(String name,String value){headers.put(name,value);return this;}
        @Override public int getResponseCode(){return code;}
        @Override public String getHeaderField(String name){return headers.get(name);}
        @Override public long getContentLengthLong(){
            String value=headers.get("Content-Length");
            return value==null?body.length:Long.parseLong(value);
        }
        @Override public String getContentType(){return headers.get("Content-Type");}
        @Override public java.io.InputStream getInputStream(){return new ByteArrayInputStream(body);}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
    }

    @Test public void managerResumesOnlyFromMatching206AndPromotesCompletedFile() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        TransferJournal journal=new TransferJournal(context);journal.clearAll();
        File target=new File(context.getCacheDir(),"resumable-final.bin");
        File partial=ResumableTransferManager.partialFileFor(target);
        target.delete();partial.delete();
        try(RandomAccessFile out=new RandomAccessFile(partial,"rw")){out.write(new byte[]{1,2,3});}
        journal.save(new TransferJournal.Entry(
                "resume-job","https://example.test/media",partial.getAbsolutePath(),
                6L,3L,"etag-1","","","paused"
        ));
        ResumableTransferManager manager=new ResumableTransferManager(journal);
        java.util.ArrayList<Long> offsets=new java.util.ArrayList<>();
        ResumableTransferManager.Result result=manager.download(
                new ResumableTransferManager.Request("resume-job","https://example.test/media",target,6L,""),
                (source,offset,etag,lastModified)->{
                    offsets.add(offset);
                    return new FakeConnection(206,new byte[]{4,5,6})
                            .header("Content-Range","bytes 3-5/6")
                            .header("ETag","etag-1")
                            .header("Content-Type","video/mp4");
                },
                null
        );
        assertEquals(Arrays.asList(3L),offsets);
        assertTrue(result.resumed);
        assertEquals(6L,result.bytes);
        assertTrue(target.isFile());
        assertFalse(partial.exists());
        assertNull(journal.get("resume-job"));
        try(RandomAccessFile in=new RandomAccessFile(target,"r")){
            byte[] bytes=new byte[6];in.readFully(bytes);
            assertArrayEquals(new byte[]{1,2,3,4,5,6},bytes);
        } finally {target.delete();partial.delete();journal.clearAll();}
    }

    @Test public void managerRestartsWhenServerIgnoresRangeInsteadOfAppendingCorruption() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        TransferJournal journal=new TransferJournal(context);journal.clearAll();
        File target=new File(context.getCacheDir(),"range-ignored-final.bin");
        File partial=ResumableTransferManager.partialFileFor(target);
        target.delete();partial.delete();
        try(RandomAccessFile out=new RandomAccessFile(partial,"rw")){out.write(new byte[]{9,9,9});}
        journal.save(new TransferJournal.Entry(
                "range-job","https://example.test/media",partial.getAbsolutePath(),
                4L,3L,"","","","paused"
        ));
        ResumableTransferManager manager=new ResumableTransferManager(journal);
        java.util.ArrayList<Long> offsets=new java.util.ArrayList<>();
        ResumableTransferManager.Result result=manager.download(
                new ResumableTransferManager.Request("range-job","https://example.test/media",target,4L,""),
                (source,offset,etag,lastModified)->{
                    offsets.add(offset);
                    if(offset>0) return new FakeConnection(200,new byte[]{1,2,3,4}).header("Content-Length","4");
                    return new FakeConnection(200,new byte[]{1,2,3,4}).header("Content-Length","4");
                },
                null
        );
        assertEquals(Arrays.asList(3L,0L),offsets);
        assertFalse(result.resumed);
        assertEquals(4L,result.bytes);
        try(RandomAccessFile in=new RandomAccessFile(target,"r")){
            byte[] bytes=new byte[4];in.readFully(bytes);
            assertArrayEquals(new byte[]{1,2,3,4},bytes);
        } finally {target.delete();partial.delete();journal.clearAll();}
    }
}
