package com.rezoxnemesis.videostudio;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class AtomicMediaPublisherTest {
    @Test public void zeroByteOutputIsRejectedBeforePublication() throws Exception {
        File file=File.createTempFile("empty-render",".mp4");
        try {
            AtomicInteger calls=new AtomicInteger();
            try {
                AtomicMediaPublisher.publish(file,new AtomicMediaPublisher.PublishTarget() {
                    @Override public String existingPublishedUri(){return "";}
                    @Override public String publish(File source){calls.incrementAndGet();return "content://video/new";}
                    @Override public String displayName(){return "new.mp4";}
                });
                fail("Zero-byte render accepted");
            } catch(IllegalArgumentException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("empty"));
            }
            assertEquals(0,calls.get());
        } finally {file.delete();}
    }

    @Test public void validRenderPublishesExactlyOnce() throws Exception {
        File file=File.createTempFile("render",".mp4");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{1,2,3,4});}
        AtomicInteger calls=new AtomicInteger();
        AtomicMediaPublisher.PublishResult result=AtomicMediaPublisher.publish(
                file,
                new AtomicMediaPublisher.PublishTarget() {
                    @Override public String existingPublishedUri(){return "";}
                    @Override public String publish(File source){
                        calls.incrementAndGet();
                        assertEquals(file,source);
                        return "content://video/final";
                    }
                    @Override public String displayName(){return "final.mp4";}
                }
        );
        assertFalse(result.reused);
        assertEquals("content://video/final",result.uri);
        assertEquals(4L,result.bytes);
        assertEquals(1,calls.get());
        file.delete();
    }

    @Test public void alreadyCommittedOutputIsReusedWithoutRepublishing() throws Exception {
        File file=File.createTempFile("render",".mp4");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{7,8});}
        AtomicInteger calls=new AtomicInteger();
        AtomicMediaPublisher.PublishResult result=AtomicMediaPublisher.publish(
                file,
                new AtomicMediaPublisher.PublishTarget() {
                    @Override public String existingPublishedUri(){return "content://video/existing";}
                    @Override public String publish(File source){calls.incrementAndGet();return "content://video/duplicate";}
                    @Override public String displayName(){return "final.mp4";}
                }
        );
        assertTrue(result.reused);
        assertEquals("content://video/existing",result.uri);
        assertEquals(0,calls.get());
        file.delete();
    }
}
