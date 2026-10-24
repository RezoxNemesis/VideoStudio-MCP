package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SketchStoryAudioInterruptedChannelTest {
    @Test public void interruptionBetweenStreamOpenAndNativePcmHeaderReadHasTheCancellationContract()throws Exception {
        File folder=Files.createTempDirectory("story_audio_channel_cancel_").toFile();
        File actual=new File(folder,"source.wav"),output=new File(folder,"mix.wav");
        byte[] pcm=new byte[44+4800*2];ByteBuffer b=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(pcm.length-8).put(new byte[]{'W','A','V','E','f','m','t',' '})
                .putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000)
                .putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(pcm.length-44);
        Files.write(actual.toPath(),pcm);
        JSONObject dry=new JSONObject().put("musicGain",0).put("ambienceGain",0).put("dialogueGain",1)
                .put("ambience","none").put("events",new JSONArray());
        AtomicInteger sourceOpens=new AtomicInteger();
        File interruptAtSecondOpen=new File(actual.getPath()) {
            @Override public String getPath() {
                for(StackTraceElement frame:Thread.currentThread().getStackTrace()) {
                    if(frame.getClassName().equals("java.io.FileInputStream")&&frame.getMethodName().equals("<init>")) {
                        // First open hashes the source; second opens the actual native PCM channel.
                        if(sourceOpens.incrementAndGet()==2)Thread.currentThread().interrupt();
                        break;
                    }
                }
                return super.getPath();
            }
        };
        try {
            InterruptedIOException failure;
            try {
                failure=assertThrows(InterruptedIOException.class,()->SketchStoryAudio.render(interruptAtSecondOpen,output,dry));
                assertEquals(2,sourceOpens.get());
                assertTrue("Keep the thread's cancellation flag",Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertTrue("Preserve the actual NIO cancellation cause",failure.getCause() instanceof ClosedByInterruptException);
            assertFalse(output.exists());assertArrayEquals(pcm,Files.readAllBytes(actual.toPath()));
            assertEquals("No partial/staging publication survives cancellation",1,folder.listFiles().length);
            SketchStoryAudio.render(actual,output,dry);
            assertArrayEquals("An ordinary fresh retry still preserves the dry PCM",pcm,Files.readAllBytes(output.toPath()));
        } finally {
            Thread.interrupted();
            for(File file:folder.listFiles())Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(folder.toPath());
        }
    }
}
