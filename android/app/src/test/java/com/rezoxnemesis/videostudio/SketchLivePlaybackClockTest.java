package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** The UI clock is separate from immutable native drawing and cel timing. */
public class SketchLivePlaybackClockTest {
    @Test public void pausesOnAnExactFractionAndResumesWithoutSkippingSavedPixels(){
        SketchLivePlaybackClock clock=new SketchLivePlaybackClock(100);
        clock.resetOperation(100);
        assertEquals(.25,clock.fraction(120,80),.0001);
        clock.pause(120);
        assertEquals(.25,clock.fraction(120000,80),.0001);
        clock.setRate(.25f,121000);
        assertEquals(.25,clock.fraction(122000,80),.0001);
        clock.resume(122100);
        assertEquals(.50,clock.fraction(122180,80),.0001);
        assertEquals(1,clock.fraction(123000,80),.0001);
    }

    @Test public void speedChangesPreserveCurrentStrokePosition(){
        SketchLivePlaybackClock clock=new SketchLivePlaybackClock(0);
        clock.resetOperation(10);
        assertEquals(.5,clock.fraction(50,80),.00001);
        clock.setRate(2,50);
        assertEquals(.5,clock.fraction(50,80),.00001);
        assertEquals(.75,clock.fraction(60,80),.00001);
        clock.pause(60);
        clock.setRate(.1f,2000);
        clock.resume(2100);
        assertEquals(.875,clock.fraction(2200,80),.0001);
        clock.resetOperation(2210);
        assertEquals(0,clock.fraction(2210,80),.0001);
        assertEquals(.125,clock.fraction(2310,80),.0001);
    }

    @Test public void onlyBoundedHumanViewingSpeedsAreAccepted(){
        assertEquals(7,SketchLivePlaybackClock.PRESETS.length);
        for(float allowed:SketchLivePlaybackClock.PRESETS)
            assertTrue(SketchLivePlaybackClock.supported(allowed));
        for(float forbidden:new float[]{0f,Float.NaN,Float.POSITIVE_INFINITY,12f,.2f})
            assertFalse(SketchLivePlaybackClock.supported(forbidden));
        SketchLivePlaybackClock clock=new SketchLivePlaybackClock(100);
        assertThrows(IllegalArgumentException.class,()->clock.setRate(.2f,200));
        assertThrows(IllegalArgumentException.class,()->clock.fraction(200,1));
        assertThrows(IllegalArgumentException.class,()->clock.fraction(200,20000));
        assertThrows(IllegalArgumentException.class,()->new SketchLivePlaybackClock(-1));
    }

    @Test public void slowReplayDoesNotNeedToChangeDurableSessionDuration(){
        SketchLivePlaybackClock clock=new SketchLivePlaybackClock(0);
        clock.setRate(.1f,0);
        clock.resetOperation(100);
        assertEquals(.5,clock.fraction(500,80),.00001);
        assertEquals(1,clock.fraction(900,80),.00001);
        // Only the viewing timeline is rescaled; native session still says 80.
        assertEquals(.1f,clock.rate(),0f);
    }
}
