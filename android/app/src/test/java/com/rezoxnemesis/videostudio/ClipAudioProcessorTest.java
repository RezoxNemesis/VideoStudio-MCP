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
    @Test public void dspLimiterProcessesActualFloatPcm()throws Exception{
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.outMs=1000;clip.effects.put("audioDsp",new org.json.JSONObject().put("limiterDb",-6));
        ClipAudioProcessor p=new ClipAudioProcessor(clip);p.configure(new AudioProcessor.AudioFormat(48000,1,C.ENCODING_PCM_FLOAT));p.flush();
        ByteBuffer input=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());input.putFloat(.9f).flip();p.queueInput(input);
        assertEquals(Math.pow(10,-6/20d),p.getOutput().order(ByteOrder.nativeOrder()).getFloat(),.000001);
    }
    @Test public void retimedPcmUsesLocalOutputClockWithoutApplyingSpeedTwice()throws Exception{
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.outMs=4000;clip.speed=2;clip.volume=0;
        clip.keyframes.put(new org.json.JSONObject().put("property","volume").put("timeMs",0).put("value",0).put("easing","linear"));
        clip.keyframes.put(new org.json.JSONObject().put("property","volume").put("timeMs",1000).put("value",1).put("easing","linear"));
        ClipAudioProcessor p=new ClipAudioProcessor(clip);p.configure(new AudioProcessor.AudioFormat(8000,1,C.ENCODING_PCM_FLOAT));p.flush();
        ByteBuffer input=ByteBuffer.allocateDirect(8001*4).order(ByteOrder.nativeOrder());for(int i=0;i<=8000;i++)input.putFloat(.8f);input.flip();p.queueInput(input);
        ByteBuffer output=p.getOutput().order(ByteOrder.nativeOrder());assertEquals(0,output.getFloat(),.00001);output.position(4000*4);assertEquals(.4,output.getFloat(),.00001);output.position(8000*4);assertEquals(.8,output.getFloat(),.00001);
        p.flush(new AudioProcessor.StreamMetadata.Builder().setPositionOffsetUs(500000).build());
        ByteBuffer seek=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putFloat(.8f);seek.flip();p.queueInput(seek);assertEquals(.4,p.getOutput().order(ByteOrder.nativeOrder()).getFloat(),.00001);
    }

}
