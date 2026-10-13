package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeFrameClockTest {
    @Test public void oneHourOfPerFrameHoldsDoesNotAccumulateRoundedCadenceDrift() {
        for(int fps:new int[]{24,30,60}) {
            long total=0;
            for(int frame=0;frame<fps*3600;frame++)total+=NativeFrameClock.frameDurationMs(frame,fps);
            assertEquals(3_600_000L,total);
            assertEquals(3_600_000_000L,NativeFrameClock.presentationTimeUs(fps*3600L,fps));
        }
    }
    @Test public void boundariesUseRationalPtsAndCumulativeMillisecondHolds() {
        assertEquals(41_666,NativeFrameClock.presentationTimeUs(1,24));
        assertEquals(33_333,NativeFrameClock.presentationTimeUs(1,30));
        assertEquals(16_666,NativeFrameClock.presentationTimeUs(1,60));
        assertEquals(16_666,NativeFrameClock.frameDurationUs(0,60));
        assertEquals(16_667,NativeFrameClock.frameDurationUs(1,60));
        assertEquals(17,NativeFrameClock.frameDurationMs(0,60));
        assertEquals(16,NativeFrameClock.frameDurationMs(1,60));
        assertEquals(17,NativeFrameClock.frameDurationMs(2,60));
        assertEquals(1000,NativeFrameClock.durationMs(60,60));
    }
    @Test(expected=IllegalArgumentException.class)public void unsupportedFpsCannotSilentlyChange(){NativeFrameClock.requireFps(25);}
    @Test(expected=IllegalArgumentException.class)public void overflowingFrameClockRejects(){NativeFrameClock.presentationTimeUs(Long.MAX_VALUE,24);}
}
