package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class ClipAudioProcessorTest {
    private ClipAudioProcessor processor()throws Exception{
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.volume=.5f;clip.outMs=1000;
        ClipAudioProcessor p=new ClipAudioProcessor(clip);
        p.configure(new AudioProcessor.AudioFormat(44100,2,C.ENCODING_PCM_16BIT));p.flush();return p;
    }
    @Test public void gainChangesRealPcmSamples()throws Exception{
        ClipAudioProcessor p=processor();ByteBuffer samples=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());samples.putShort((short)20000).putShort((short)-20000).flip();
        p.queueInput(samples);ByteBuffer result=p.getOutput().order(ByteOrder.nativeOrder());assertEquals(10000,result.getShort());assertEquals(-10000,result.getShort());
    }
    @Test public void partialPcmFramesAreBufferedAndProcessedTogether()throws Exception{
        ClipAudioProcessor p=processor();ByteBuffer frame=ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());frame.putShort((short)20000).putShort((short)-20000);
        p.queueInput(ByteBuffer.wrap(frame.array(),0,3));assertEquals("No unprocessed partial sample may escape",0,p.getOutput().remaining());
        p.queueInput(ByteBuffer.wrap(frame.array(),3,1));ByteBuffer result=p.getOutput().order(ByteOrder.nativeOrder());
        assertEquals(4,result.remaining());assertEquals(10000,result.getShort());assertEquals(-10000,result.getShort());
    }
}
