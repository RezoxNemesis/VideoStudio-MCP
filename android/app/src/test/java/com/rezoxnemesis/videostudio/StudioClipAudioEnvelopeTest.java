package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.transformer.EditedMediaItem;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioClipAudioEnvelopeTest {
    @Test public void linearOutputTimeFadesCombineWithClipGain() throws Exception {
        StudioClipAudioEnvelope envelope=StudioClipAudioEnvelope.fromEffects(
                new JSONObject().put("audioFadeInMs",200).put("audioFadeOutMs",400),1000,2f,1.5f);
        assertEquals(0,envelope.gainAtOutputTimeUs(0),0);
        assertEquals(.75,envelope.gainAtOutputTimeUs(100_000),.00001);
        assertEquals(1.5,envelope.gainAtOutputTimeUs(500_000),.00001);
        assertEquals(.75,envelope.gainAtOutputTimeUs(800_000),.00001);
        assertEquals(0,envelope.gainAtOutputTimeUs(1_000_000),0);
    }

    @Test public void overlappingFadesMultiplyWithoutExceedingAuthoredGain() throws Exception {
        StudioClipAudioEnvelope envelope=StudioClipAudioEnvelope.fromEffects(
                new JSONObject().put("audioFadeInMs",1000).put("audioFadeOutMs",1000),1000,.5f,2f);
        assertEquals(.5,envelope.gainAtOutputTimeUs(500_000),.00001);
        assertEquals(.375,envelope.gainAtOutputTimeUs(250_000),.00001);
    }

    @Test public void splitPreservesEnvelopeAcrossBothClipsAndRepeatedSplits() throws Exception {
        JSONObject first=new JSONObject().put("audioFadeInMs",800).put("audioFadeOutMs",300);
        JSONObject second=new JSONObject(first.toString());
        StudioClipAudioEnvelope original=StudioClipAudioEnvelope.fromEffects(first,1000,1,1);
        StudioClipAudioEnvelope.preserveSplit(first,second,1000,400);
        StudioClipAudioEnvelope left=StudioClipAudioEnvelope.fromEffects(first,400,1,1);
        StudioClipAudioEnvelope right=StudioClipAudioEnvelope.fromEffects(second,600,1,1);
        assertEquals(original.gainAtOutputTimeUs(200_000),left.gainAtOutputTimeUs(200_000),0);
        assertEquals(original.gainAtOutputTimeUs(550_000),right.gainAtOutputTimeUs(150_000),0);
        JSONObject third=new JSONObject(second.toString());
        StudioClipAudioEnvelope.preserveSplit(second,third,600,300);
        assertEquals(original.gainAtOutputTimeUs(850_000),StudioClipAudioEnvelope.fromEffects(third,300,1,1)
                .gainAtOutputTimeUs(150_000),0);
    }

    @Test public void malformedFadesRejectInsteadOfBeingCoercedOrClamped() throws Exception {
        for(Object bad:new Object[]{-1,1001,1.25,"100",true,JSONObject.NULL}){
            try{StudioClipAudioEnvelope.fromEffects(new JSONObject().put("audioFadeInMs",bad),1000,1,1);fail("Accepted "+bad);}
            catch(IllegalArgumentException expected){}
        }
        try{StudioClipAudioEnvelope.fromEffects(new JSONObject().put("studioAudioDurationMs",1000),1000,1,1);fail("Partial clock accepted");}
        catch(IllegalArgumentException expected){}
    }

    @Test public void integerPcmGainSaturatesInsteadOfWrappingOrInvertingSound() throws Exception {
        StudioClipAudioProcessor processor=new StudioClipAudioProcessor(
                StudioClipAudioEnvelope.fromEffects(new JSONObject(),1000,1,2));
        processor.configure(new AudioProcessor.AudioFormat(1000,2,C.ENCODING_PCM_16BIT));processor.flush();
        ByteBuffer input=ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
        input.putShort((short)30000).putShort((short)-30000).putShort((short)1000).putShort((short)-1000).flip();
        processor.queueInput(input);ByteBuffer output=processor.getOutput().order(ByteOrder.nativeOrder());
        assertEquals(32767,output.getShort());assertEquals(-32768,output.getShort());
        assertEquals(2000,output.getShort());assertEquals(-2000,output.getShort());
        assertFalse(input.hasRemaining());
    }

    @Test public void allChannelsUseOneSampleFrameClockAndSeekDoesNotRestartTheFade() throws Exception {
        StudioClipAudioProcessor processor=new StudioClipAudioProcessor(StudioClipAudioEnvelope.fromEffects(
                new JSONObject().put("audioFadeInMs",1000),1000,2,1));
        processor.configure(new AudioProcessor.AudioFormat(1000,6,C.ENCODING_PCM_16BIT));
        processor.flush(new AudioProcessor.StreamMetadata(500_000));
        ByteBuffer input=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        for(int i=0;i<12;i++)input.putShort((short)20000);input.flip();processor.queueInput(input);
        ByteBuffer output=processor.getOutput().order(ByteOrder.nativeOrder());
        for(int channel=0;channel<6;channel++)assertEquals(10000,output.getShort());
        for(int channel=0;channel<6;channel++)assertEquals(10020,output.getShort());
        processor.flush();
        ByteBuffer restart=ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        for(int i=0;i<6;i++)restart.putShort((short)20000);restart.flip();processor.queueInput(restart);
        assertEquals(0,processor.getOutput().order(ByteOrder.nativeOrder()).getShort());
    }

    @Test public void floatPcmIsFiniteAndClampedAndSubmillisecondTimingIsPreserved() throws Exception {
        StudioClipAudioProcessor processor=new StudioClipAudioProcessor(StudioClipAudioEnvelope.fromEffects(
                new JSONObject().put("audioFadeInMs",1),1000,1,2));
        processor.configure(new AudioProcessor.AudioFormat(4000,1,C.ENCODING_PCM_FLOAT));processor.flush();
        ByteBuffer input=ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        input.putFloat(1f).putFloat(1f).putFloat(1f).putFloat(1f).putFloat(Float.NaN).flip();
        processor.queueInput(input);ByteBuffer output=processor.getOutput().order(ByteOrder.nativeOrder());
        assertEquals(0,output.getFloat(),0);assertEquals(.5f,output.getFloat(),0);
        assertEquals(1f,output.getFloat(),0);assertEquals(1f,output.getFloat(),0);assertEquals(0,output.getFloat(),0);
    }

    @Test public void composedPreviewAndExportUseTheSameTrimmedRetimedEnvelope() throws Exception {
        ProjectStore.Project project=new ProjectStore.Project();
        ProjectStore.Asset video=new ProjectStore.Asset();video.id="audio-envelope-owned";
        video.uri="file:///unused.mp4";video.mime="video/mp4";video.durationMs=5000;project.assets.add(video);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="fade-clip";clip.assetId=video.id;
        clip.inMs=1000;clip.outMs=3000;clip.speed=2;clip.volume=.8f;
        clip.effects.put("audioFadeInMs",200).put("audioFadeOutMs",300);project.clips.add(clip);
        EditedMediaItem item=new NativeRenderEngine(RuntimeEnvironment.getApplication())
                .buildPreviewComposition(project,"9:16","720p").sequences.get(0).editedMediaItems.get(0);
        assertEquals(1000,item.mediaItem.clippingConfiguration.startPositionMs);
        assertEquals(3000,item.mediaItem.clippingConfiguration.endPositionMs);
        assertEquals(2,item.speedProvider.getSpeed(0),0);
        assertEquals(1,item.effects.audioProcessors.size());
        assertTrue(item.effects.audioProcessors.get(0) instanceof StudioClipAudioProcessor);
        StudioClipAudioProcessor processor=(StudioClipAudioProcessor)item.effects.audioProcessors.get(0);
        processor.configure(new AudioProcessor.AudioFormat(1000,1,C.ENCODING_PCM_16BIT));
        processor.flush(new AudioProcessor.StreamMetadata(100_000));
        ByteBuffer input=ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder());input.putShort((short)10000).flip();
        processor.queueInput(input);assertEquals(4000,processor.getOutput().order(ByteOrder.nativeOrder()).getShort());
    }
}
