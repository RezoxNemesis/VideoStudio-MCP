package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaAudioTimelineTest {
    @Test public void waveformUsesExactFrameClockAndDetectsSilence(){
        short[] pcm=new short[4000];
        Arrays.fill(pcm,0,2000,(short)16384);
        List<OmegaAudioTimeline.Envelope> envelopes=OmegaAudioTimeline.waveform(pcm,48000,24,3);
        assertEquals(3,envelopes.size());
        assertEquals(0,envelopes.get(0).startSample);
        assertEquals(2000,envelopes.get(0).endSample);
        assertEquals(.5,envelopes.get(0).rms,1e-10);
        assertFalse(envelopes.get(0).silent(.2));
        assertEquals(0,envelopes.get(1).peak,0);
        assertTrue(envelopes.get(1).silent(.01));
        assertTrue(envelopes.get(2).silent(.01));
    }
    @Test public void frameLookupNeverLeavesAuthoredWindow(){
        assertEquals(0,OmegaAudioTimeline.frameForSample(0,48000,24,24));
        assertEquals(12,OmegaAudioTimeline.frameForSample(24000,48000,24,24));
        assertEquals(23,OmegaAudioTimeline.frameForSample(2000000,48000,24,24));
    }
    @Test public void unsafeClockRejected(){
        try{OmegaAudioTimeline.waveform(new short[5],44100,25,10);fail();}
        catch(IllegalArgumentException expected){}
        try{OmegaAudioTimeline.frameForSample(-1,48000,24,10);fail();}
        catch(IllegalArgumentException expected){}
    }
}