package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class StudioTimelineGesturesTest {
    @Test public void leftTrimKeepsTheExistingOutPoint(){
        StudioLayerTouchMath.Timing timing=StudioLayerTouchMath.trimLeft(10000,2000,3000,750,0);
        assertEquals(2750,timing.startMs);
        assertEquals(2250,timing.durationMs);
        assertEquals(5000,timing.startMs+timing.durationMs);
    }
    @Test public void leftTrimCannotRevealAudioBeforeItsSourceIn(){
        StudioLayerTouchMath.Timing timing=StudioLayerTouchMath.trimLeft(10000,2000,3000,-1500,1600);
        assertEquals(1600,timing.startMs);
        assertEquals(3400,timing.durationMs);
        assertEquals(100,StudioLayerTouchMath.trimLeft(10000,2000,3000,9000,0).durationMs);
    }
    @Test public void movingSnapsEitherEdgeWithoutChangingDuration(){
        StudioLayerTouchMath.Timing timing=StudioLayerTouchMath.moveSnapped(10000,2000,1200,985,new long[]{0,4200,10000},40);
        assertEquals(3000,timing.startMs);
        assertEquals(1200,timing.durationMs);
    }
    @Test public void distantCutsDoNotMagnetizeAClip(){
        assertEquals(2985,StudioLayerTouchMath.moveSnapped(10000,2000,1200,985,new long[]{4200},5).startMs);
    }
}
