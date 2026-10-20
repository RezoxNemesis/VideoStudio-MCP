package com.rezoxnemesis.videostudio;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ResumableTransferManagerLargeMediaTest {
    private static final long GB=1024L*1024L*1024L;

    @Test public void tenGibibyteBoundaryUsesLongMetadataAndNeverAllocatesFileInMemory(){
        assertEquals(10*GB,ResumableTransferManager.MAX_TRANSFER_BYTES);
        File target=new File(System.getProperty("java.io.tmpdir"),"large.mp4");
        ResumableTransferManager.Request request=new ResumableTransferManager.Request(
                "large_10g","https://cdn.example.invalid/video.mp4",target,10*GB,"");
        assertEquals(10*GB,request.expectedBytes);
        try{
            new ResumableTransferManager.Request("large_oversize","https://cdn.example.invalid/video.mp4",
                    target,10*GB+1,"");
            fail("Oversized source must be rejected before opening a connection");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("10 GiB"));
        }
        assertEquals(50,ResumableTransferManager.progressPercent(5*GB,10*GB));
        assertEquals(99,ResumableTransferManager.progressPercent(10*GB-1,10*GB));
        assertEquals(100,ResumableTransferManager.progressPercent(10*GB,10*GB));
    }

    @Test public void streamingImportRefusesOverflowBeforeWritingPastLimit() throws Exception{
        File target=File.createTempFile("media_ceiling",".partial");
        try(RandomAccessFile output=new RandomAccessFile(target,"rw")){
            assertThrows(IllegalStateException.class,()->ResumableTransferManager.copyStream(
                    new ByteArrayInputStream(new byte[16]),output,10*GB-8,null));
            assertEquals(0,target.length());
        }finally{Files.deleteIfExists(target.toPath());}
    }

    @Test public void streamPreservesBytesAnd64BitProgressOffset()throws Exception{
        File target=File.createTempFile("media_stream",".partial");
        byte[] input=new byte[300000];
        for(int i=0;i<input.length;i++)input[i]=(byte)(i*31+11);
        AtomicLong absolute=new AtomicLong();
        try(RandomAccessFile output=new RandomAccessFile(target,"rw")){
            assertEquals(input.length,ResumableTransferManager.copyStream(
                    new ByteArrayInputStream(input),output,5*GB,absolute::set));
            assertEquals(5*GB+input.length,absolute.get());
            output.seek(0);
            byte[] actual=new byte[input.length];output.readFully(actual);
            assertArrayEquals(input,actual);
        }finally{Files.deleteIfExists(target.toPath());}
    }

    @Test public void contentRangeResumptionSupportsOffsetsOverFourGiB(){
        long at=7*GB+12345;
        assertTrue(ResumableTransferManager.canAppendResume(at,206,
                "bytes "+at+"-"+(10*GB-1)+"/"+(10*GB)));
        assertFalse(ResumableTransferManager.canAppendResume(at,200,
                "bytes "+at+"-"+(10*GB-1)+"/"+(10*GB)));
        assertFalse(ResumableTransferManager.canAppendResume(at,206,
                "bytes "+(at-1)+"-"+(10*GB-1)+"/"+(10*GB)));
    }
}
