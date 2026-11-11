package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class StudioAudioWaveformsTest {
    @Test public void normalizedWaveformPeaksComeFromRealAudioEnergy(){
        float[] levels=StudioAudioWaveforms.normalize(
                new double[]{0,1,16,4},new int[]{0,4,4,4});
        assertEquals(0,levels[0],.00001);
        assertEquals(.25,levels[1],.00001);
        assertEquals(1,levels[2],.00001);
        assertEquals(.5,levels[3],.00001);
    }
    @Test(expected=IllegalArgumentException.class)
    public void waveformInputMustBeBoundedAndAligned(){
        StudioAudioWaveforms.normalize(new double[]{1,2},new int[]{1});
    }
}
