package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Clock checks do not create or claim to validate a native GPU renderer. */
public class Preview3DPlaybackTest {
    @Test public void pauseResumeKeepsClipPositionAndExcludesBackgroundTime() {
        Preview3DPlayback clock=new Preview3DPlayback();
        clock.frame(1_000_000_000L,true,10);clock.frame(3_000_000_000L,true,10);
        assertEquals(2,clock.position(),.00001);
        clock.suspend();clock.frame(100_000_000_000L,true,10);
        assertEquals(2,clock.position(),.00001);
        clock.frame(101_000_000_000L,true,10);
        assertEquals(3,clock.position(),.00001);
    }
    @Test public void seekAndRateChangesKeepTheChosenTimeInsteadOfRestarting() {
        Preview3DPlayback clock=new Preview3DPlayback();clock.seek(4,10);clock.rate(2);
        assertEquals(4,clock.frame(1_000_000_000L,true,10),.00001);
        assertEquals(6,clock.frame(2_000_000_000L,true,10),.00001);
        clock.frame(3_000_000_000L,false,10);
        assertEquals(6,clock.position(),.00001);
        assertEquals(6,clock.frame(4_000_000_000L,true,10),.00001);
    }
    @Test public void wrapsAtTheAuthoredClipDurationAndCanSeekToItsLastPose() {
        Preview3DPlayback clock=new Preview3DPlayback();clock.seek(10,10);
        assertEquals(10,clock.frame(1_000_000_000L,false,10),0);
        clock.frame(2_000_000_000L,true,10);
        assertEquals(1,clock.frame(3_000_000_000L,true,10),.00001);
    }
    @Test public void invalidNumbersNeverReachNativeAnimation() {
        Preview3DPlayback clock=new Preview3DPlayback();
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}) {
            try{clock.seek(value,10);fail("Invalid position accepted");}catch(IllegalArgumentException expected){}
        }
        for(double value:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,0,5}) {
            try{clock.rate(value);fail("Invalid playback rate accepted");}catch(IllegalArgumentException expected){}
        }
        assertEquals(0,clock.frame(10,true,Double.NaN),0);
    }
}
