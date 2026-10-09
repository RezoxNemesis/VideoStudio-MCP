package com.rezoxnemesis.videostudio;

import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class PlayableMediaChecksumTest {
    @org.junit.Test public void nativeAacMediaDurationIncludesPrerollButPresentationDoesNot(){var format=android.media.MediaFormat.createAudioFormat("audio/mp4a-latm",44100,1);format.setLong(android.media.MediaFormat.KEY_DURATION,10_036_281);format.setInteger(android.media.MediaFormat.KEY_ENCODER_DELAY,1600);assertTrue(Math.abs(PlayableMediaVerifier.presentationDurationUs(format)-10_000_000)<=1);format.setLong(android.media.MediaFormat.KEY_DURATION,10_059_501);format.setInteger(android.media.MediaFormat.KEY_ENCODER_PADDING,1024);assertTrue(Math.abs(PlayableMediaVerifier.presentationDurationUs(format)-10_000_000)<=1);}
    @org.junit.Test public void ordinaryVideoOrAacWithoutGaplessMetadataKeepsItsReportedDuration(){var audio=android.media.MediaFormat.createAudioFormat("audio/mp4a-latm",44100,1);audio.setLong(android.media.MediaFormat.KEY_DURATION,10_000_000);assertEquals(10_000_000,PlayableMediaVerifier.presentationDurationUs(audio));var video=android.media.MediaFormat.createVideoFormat("video/avc",1280,720);video.setLong(android.media.MediaFormat.KEY_DURATION,10_000_000);video.setInteger(android.media.MediaFormat.KEY_ENCODER_DELAY,1600);assertEquals(10_000_000,PlayableMediaVerifier.presentationDurationUs(video));}
    @org.junit.Test public void invalidAacGaplessMetadataCannotProduceAnInventedPresentationExtent(){var f=android.media.MediaFormat.createAudioFormat("audio/mp4a-latm",44100,1);f.setLong(android.media.MediaFormat.KEY_DURATION,1000);f.setInteger(android.media.MediaFormat.KEY_ENCODER_DELAY,1600);assertThrows(IllegalArgumentException.class,()->PlayableMediaVerifier.presentationDurationUs(f));f.setLong(android.media.MediaFormat.KEY_DURATION,10_000_000);f.setInteger(android.media.MediaFormat.KEY_ENCODER_DELAY,-1);assertThrows(IllegalArgumentException.class,()->PlayableMediaVerifier.presentationDurationUs(f));}
    @Test public void flagCancellationStopsOnTheFirstHashChunk()throws Exception{
        AtomicBoolean cancelled=new AtomicBoolean();AtomicInteger read=new AtomicInteger();var input=new ByteArrayInputStream(new byte[1_000_000]){public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(n>0){read.addAndGet(n);cancelled.set(true);}return n;}};
        assertThrows(InterruptedIOException.class,()->PlayableMediaVerifier.bytesProof(input,cancelled::get));assertEquals(256*1024,read.get());
    }
    @Test public void finalReadCancellationCannotReturnASuccessProof(){AtomicBoolean cancelled=new AtomicBoolean();var input=new ByteArrayInputStream(new byte[]{1,2,3}){public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(n<0)cancelled.set(true);return n;}};assertThrows(InterruptedIOException.class,()->PlayableMediaVerifier.bytesProof(input,cancelled::get));}
    @Test public void exactStreamProducesAWholeFileChecksumAndEmptyMediaIsRejected()throws Exception{var proof=PlayableMediaVerifier.bytesProof(new ByteArrayInputStream(new byte[]{1,2,3}),()->false);assertEquals(3,proof.getLong("sizeBytes"));assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",proof.getString("sha256"));assertThrows(IllegalArgumentException.class,()->PlayableMediaVerifier.bytesProof(new ByteArrayInputStream(new byte[0]),()->false));}
    @Test public void interruptedThreadIsPreservedAndNoInputBytesAreRead(){AtomicInteger reads=new AtomicInteger();var input=new ByteArrayInputStream(new byte[]{1}){public synchronized int read(byte[] b,int off,int len){reads.incrementAndGet();return super.read(b,off,len);}};Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->PlayableMediaVerifier.bytesProof(input,()->false));assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,reads.get());}finally{Thread.interrupted();}}
    @Test public void descriptorHashKeepsTheOpenedInodeWhenThePathIsReplaced()throws Exception{
        var root=java.nio.file.Files.createTempDirectory("held-proof");var path=root.resolve("output.mp4");java.nio.file.Files.write(path,new byte[]{1,2,3});try(var input=new java.io.FileInputStream(path.toFile())){java.nio.file.Files.move(path,root.resolve("old.mp4"));java.nio.file.Files.write(path,new byte[]{9,9,9});var proof=PlayableMediaVerifier.descriptorBytes(input.getFD(),()->false);assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",proof.getString("sha256"));assertTrue("Verification must not close its caller's descriptor",input.getFD().valid());assertEquals(1,input.read());}
    }
    @Test public void cancelledDescriptorHashDoesNotCloseOrAdvanceItsCaller()throws Exception{
        var path=java.nio.file.Files.createTempFile("cancel-held",".mp4");java.nio.file.Files.write(path,new byte[]{1,2,3});try(var input=new java.io.FileInputStream(path.toFile())){assertThrows(InterruptedIOException.class,()->PlayableMediaVerifier.descriptorBytes(input.getFD(),()->true));assertTrue(input.getFD().valid());assertEquals(1,input.read());}
    }
}
