package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioClipAudioStereoTest {
    private static StudioClipAudioProcessor processor(JSONObject effects,int channels,int encoding,float volume,long offsetUs)throws Exception{
        StudioClipAudioProcessor processor=new StudioClipAudioProcessor(StudioClipAudioEnvelope.fromEffects(effects,1000,2,volume));
        AudioProcessor.AudioFormat format=new AudioProcessor.AudioFormat(1000,channels,encoding);
        assertEquals(format,processor.configure(format));
        processor.flush(new AudioProcessor.StreamMetadata(offsetUs));
        return processor;
    }
    private static short[] pcm16(StudioClipAudioProcessor processor,int... values){
        ByteBuffer input=ByteBuffer.allocateDirect(values.length*2).order(ByteOrder.nativeOrder());
        for(int value:values)input.putShort((short)value);input.flip();
        // Processing must use native PCM order without changing the caller's declared order.
        input.order(ByteOrder.nativeOrder()==ByteOrder.BIG_ENDIAN?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        processor.queueInput(input);assertFalse(input.hasRemaining());
        assertNotEquals(ByteOrder.nativeOrder(),input.order());
        ByteBuffer output=processor.getOutput().order(ByteOrder.nativeOrder());
        short[] result=new short[output.remaining()/2];for(int i=0;i<result.length;i++)result[i]=output.getShort();return result;
    }
    private static float[] floats(StudioClipAudioProcessor processor,float... values){
        ByteBuffer input=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());
        for(float value:values)input.putFloat(value);input.flip();processor.queueInput(input);assertFalse(input.hasRemaining());
        ByteBuffer output=processor.getOutput().order(ByteOrder.nativeOrder());
        float[] result=new float[output.remaining()/4];for(int i=0;i<result.length;i++)result[i]=output.getFloat();return result;
    }
    @Test public void stereoBalanceAttenuatesOnlyTheOppositeSideAndKeepsCenterUnchanged()throws Exception{
        for(double pan:new double[]{-1,-.5,0,.5,1}){
            StudioClipAudioProcessor processor=processor(new JSONObject().put("audioPan",pan),2,C.ENCODING_PCM_16BIT,2,0);
            assertArrayEquals(new short[]{(short)Math.round(10000*Math.min(1,1-pan)),(short)Math.round(-16000*Math.min(1,1+pan))},pcm16(processor,5000,-8000));
        }
    }
    @Test public void monoAveragesBeforeBalanceAndVolumeWithoutClippingTheInputSum()throws Exception{
        assertArrayEquals(new short[]{5000,10000},pcm16(processor(new JSONObject().put("audioMono",true).put("audioPan",.5),2,C.ENCODING_PCM_16BIT,2,0),30000,-20000));
        assertArrayEquals(new short[]{32767,32767,-32768,-32768},pcm16(processor(new JSONObject().put("audioMono",true),2,C.ENCODING_PCM_16BIT,2,0),30000,30000,-30000,-30000));
        assertArrayEquals(new short[]{0,0},pcm16(processor(new JSONObject().put("audioMono",true),2,C.ENCODING_PCM_16BIT,1,0),20000,-20000));
    }
    @Test public void monoSourceIgnoresStereoControlsAndRetainsItsOwnGain()throws Exception{
        assertArrayEquals(new short[]{10000,-10000},pcm16(processor(new JSONObject().put("audioMono",true).put("audioPan",1),1,C.ENCODING_PCM_16BIT,.5f,0),20000,-20000));
        assertArrayEquals(new float[]{.4f,-.4f},floats(processor(new JSONObject().put("audioMono",true).put("audioPan",-1),1,C.ENCODING_PCM_FLOAT,.5f,0),.8f,-.8f),0);
    }
    @Test public void sixAndEightChannelAudioRetainsExtraChannelPositionsAndOneFadeClockPerFrame()throws Exception{
        for(int count:new int[]{6,8}){
            JSONObject effects=new JSONObject().put("audioMono",true).put("audioPan",1).put("audioFadeInMs",1000);
            StudioClipAudioProcessor processor=processor(effects,count,C.ENCODING_PCM_16BIT,1,500000);
            int[] input=new int[count*2];short[] expected=new short[count*2];
            for(int frame=0;frame<2;frame++)for(int channel=0;channel<count;channel++){
                input[frame*count+channel]=(channel+1)*2000;
                expected[frame*count+channel]=(short)Math.round((channel==0?0:channel==1?3000:(channel+1)*2000)*(.5+frame*.001));
            }
            assertArrayEquals(expected,pcm16(processor,input));
        }
    }
    @Test public void floatStereoMixRemainsFiniteAndSaturatesAfterBalanceAndEnvelopeGain()throws Exception{
        StudioClipAudioProcessor processor=processor(new JSONObject().put("audioMono",true).put("audioPan",-.5),2,C.ENCODING_PCM_FLOAT,2,0);
        assertArrayEquals(new float[]{1,.75f,-1,-.75f,0,0,1,1},floats(processor,1,.5f,-1,-.5f,Float.NaN,.5f,Float.POSITIVE_INFINITY,1),0);
        for(int count:new int[]{6,8}){
            float[] source=new float[count],expected=new float[count];
            source[0]=.8f;source[1]=-.4f;expected[0]=.2f;
            for(int channel=2;channel<count;channel++)source[channel]=expected[channel]=channel/10f;
            assertArrayEquals(expected,floats(processor(new JSONObject().put("audioMono",true).put("audioPan",-1),count,C.ENCODING_PCM_FLOAT,1,0),source),.000001f);
        }
    }
    @Test public void seekAndSeparateBuffersContinueTheSameStereoFadeClock()throws Exception{
        StudioClipAudioProcessor processor=processor(new JSONObject().put("audioMono",true).put("audioPan",.5).put("audioFadeInMs",1000),2,C.ENCODING_PCM_16BIT,2,500000);
        assertArrayEquals(new short[]{7500,15000},pcm16(processor,10000,20000));
        assertArrayEquals(new short[]{7515,15030},pcm16(processor,10000,20000));
        processor.flush(new AudioProcessor.StreamMetadata(750000));
        assertArrayEquals(new short[]{11250,22500},pcm16(processor,10000,20000));
        processor.flush();assertArrayEquals(new short[]{0,0},pcm16(processor,10000,20000));
    }
    @Test public void splitStereoClipKeepsItsAuthoredFadeClockAfterDialogVolumeAndPanChanges()throws Exception{
        JSONObject left=new JSONObject().put("audioFadeInMs",800).put("audioFadeOutMs",300).put("audioPan",-1).put("audioMono",true);
        JSONObject right=new JSONObject(left.toString());StudioClipAudioEnvelope.preserveSplit(left,right,1000,400);
        JSONObject set=StudioVolumeEdits.settings(50,600,800,300,800,300,50,true);
        for(java.util.Iterator<String> keys=set.keys();keys.hasNext();){String key=keys.next();if(!"volume".equals(key))right.put(key,set.get(key));}
        assertEquals(1000,right.getLong("studioAudioDurationMs"));assertEquals(400,right.getLong("studioAudioOffsetMs"));
        StudioClipAudioProcessor processor=new StudioClipAudioProcessor(StudioClipAudioEnvelope.fromEffects(right,600,2,(float)set.getDouble("volume")));
        processor.configure(new AudioProcessor.AudioFormat(1000,2,C.ENCODING_PCM_16BIT));processor.flush();
        assertArrayEquals(new short[]{1875,3750},pcm16(processor,10000,20000));
        processor.flush(new AudioProcessor.StreamMetadata(200000));
        assertArrayEquals(new short[]{2813,5625},pcm16(processor,10000,20000));
        JSONObject third=new JSONObject(right.toString());StudioClipAudioEnvelope.preserveSplit(right,third,600,300);
        assertEquals(700,third.getLong("studioAudioOffsetMs"));assertEquals(.4375,StudioClipAudioEnvelope.fromEffects(third,300,2,.5f).gainAtOutputTimeUs(0),0);
    }
    @Test public void stereoSettingsActivateTheProcessorButExplicitDefaultsRemainInactive()throws Exception{
        assertTrue(StudioClipAudioEnvelope.fromEffects(new JSONObject().put("audioPan",.5),1000,1,1).changesAudio());
        assertTrue(StudioClipAudioEnvelope.fromEffects(new JSONObject().put("audioMono",true),1000,1,1).changesAudio());
        StudioClipAudioProcessor unchanged=new StudioClipAudioProcessor(StudioClipAudioEnvelope.fromEffects(new JSONObject().put("audioPan",0).put("audioMono",false),1000,1,1));
        assertEquals(AudioProcessor.AudioFormat.NOT_SET,unchanged.configure(new AudioProcessor.AudioFormat(1000,2,C.ENCODING_PCM_16BIT)));
    }
    @Test public void malformedStereoSettingsRejectAtTheReaderBoundary()throws Exception{
        for(Object invalid:new Object[]{-1.01,1.01,"0.5",true,JSONObject.NULL}){
            try{StudioClipAudioEnvelope.fromEffects(new JSONObject().put("audioPan",invalid),1000,1,1);fail("Accepted pan "+invalid);}catch(IllegalArgumentException expected){}
        }
        for(Object invalid:new Object[]{0,1,"true",JSONObject.NULL}){
            try{StudioClipAudioEnvelope.fromEffects(new JSONObject().put("audioMono",invalid),1000,1,1);fail("Accepted mono "+invalid);}catch(IllegalArgumentException expected){}
        }
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
            // JSONObject.put rejects these first; exercise the reader itself with a nonfinite Number.
            JSONObject effects=new JSONObject(){@Override public Object opt(String key){return "audioPan".equals(key)?invalid:super.opt(key);}};
            effects.put("audioPan",0);
            try{StudioClipAudioEnvelope.fromEffects(effects,1000,1,1);fail("Accepted nonfinite pan "+invalid);}catch(IllegalArgumentException expected){}
        }
    }
}
