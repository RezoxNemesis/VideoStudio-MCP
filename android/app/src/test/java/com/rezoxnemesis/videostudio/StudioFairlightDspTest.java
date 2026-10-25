package com.rezoxnemesis.videostudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual bounded PCM sample-processing proof, not just preset validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioFairlightDspTest {
    @Test public void neutralProcessorPreservesOriginalPcmSamplesWithinRounding()throws Exception{
        StudioFairlightDsp dsp=new StudioFairlightDsp(new JSONObject(),48000,2);
        short[] input=new short[9600];
        for(int i=0;i<input.length;i++)input[i]=(short)(Math.sin(i*.17)*27000);
        short[] output=dsp.process(input);
        assertEquals(input.length,output.length);
        for(int i=0;i<input.length;i++)
            assertTrue("Neutral processing must not colour original sound at index "+i,
                    Math.abs(input[i]-output[i])<=2);
        assertTrue(dsp.samplePeakDbfs()<=0);
        assertTrue(dsp.approximateRmsDbfs()>-20);
    }

    @Test public void compressorAndLimiterActuallyReducePeaksWithoutChangingTiming()throws Exception{
        JSONObject spec=new JSONObject()
                .put("compressorThresholdDb",-30)
                .put("compressorRatio",4)
                .put("attackMs",2).put("releaseMs",100)
                .put("limiterCeilingDb",-6);
        StudioFairlightDsp dsp=new StudioFairlightDsp(spec,48000,1);
        short[] wave=new short[12000];
        java.util.Arrays.fill(wave,(short)30000);
        short[] output=dsp.process(wave);
        int max=0;for(short sample:output)max=Math.max(max,Math.abs((int)sample));
        assertEquals(wave.length,output.length);
        assertTrue(max<=16430); // -6 dBFS sample peak threshold
        assertTrue("Compressor must settle below input level",
                Math.abs(output[8000])<Math.abs(wave[8000])*.55);
        assertTrue(dsp.samplePeakDbfs()<-5.8);
        dsp.reset();
        assertEquals(-120,dsp.samplePeakDbfs(),.0001);
    }

    @Test public void lowpassAndHighpassAttenuateTheirSelectedFrequencies()throws Exception{
        int sampleRate=48000,n=48000;
        short[] low=new short[n],high=new short[n];
        for(int i=0;i<n;i++){
            low[i]=(short)Math.round(10000*Math.sin(2*Math.PI*45*i/sampleRate));
            high[i]=(short)Math.round(10000*Math.sin(2*Math.PI*7000*i/sampleRate));
        }
        StudioFairlightDsp hp=new StudioFairlightDsp(new JSONObject().put("highpassHz",210),sampleRate,1);
        StudioFairlightDsp lp=new StudioFairlightDsp(new JSONObject().put("lowpassHz",1000),sampleRate,1);
        short[] cutLow=hp.process(low),cutHigh=lp.process(high);
        double lowEnergy=0,highEnergy=0;
        for(int i=4000;i<n;i++){
            lowEnergy+=cutLow[i]*(double)cutLow[i];
            highEnergy+=cutHigh[i]*(double)cutHigh[i];
        }
        double baseline=10000.0*10000*(n-4000)*.5;
        assertTrue("210Hz highpass attenuates 45Hz",lowEnergy/baseline<.15);
        assertTrue("1kHz lowpass attenuates 7kHz",highEnergy/baseline<.15);
    }

    @Test public void nativeMedia3AudioProcessorAdmitsExactlyPcm16()throws Exception{
        StudioFairlightAudioProcessor processor=new StudioFairlightAudioProcessor(
                new JSONObject().put("compressorRatio",2));
        AudioProcessor.AudioFormat pcm=new AudioProcessor.AudioFormat(
                48000,2,C.ENCODING_PCM_16BIT);
        assertEquals(pcm,processor.configure(pcm));
        assertThrows(AudioProcessor.UnhandledAudioFormatException.class,()->
                processor.configure(new AudioProcessor.AudioFormat(
                        48000,2,C.ENCODING_PCM_FLOAT)));
        processor.reset();
    }

    @Test public void impossibleOrFileBackedAudioEffectsFailClosed()throws Exception{
        assertThrows(IllegalArgumentException.class,()->
                StudioFairlightSettings.validate(new JSONObject()
                        .put("externalPlugin","file:///private/vst-plugin")));
        assertThrows(IllegalArgumentException.class,()->
                StudioFairlightSettings.validate(new JSONObject().put("compressorRatio",21)));
        assertThrows(IllegalArgumentException.class,()->
                StudioFairlightSettings.validate(new JSONObject().put("lowpassHz",500)));
        assertThrows(IllegalArgumentException.class,()->
                StudioFairlightSettings.validate(new JSONObject().put("outputDb","Loud")));
    }
}
