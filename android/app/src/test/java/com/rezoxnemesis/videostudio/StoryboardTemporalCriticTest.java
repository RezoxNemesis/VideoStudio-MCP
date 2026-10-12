package com.rezoxnemesis.videostudio;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class StoryboardTemporalCriticTest {
    private static final int W=64,H=96;
    private int[] canvas(int color){int[] p=new int[W*H];Arrays.fill(p,color);return p;}
    private void square(int[] art,int x,int y,int color){
        for(int yy=y;yy<y+14;yy++)for(int xx=x;xx<x+10;xx++)
            art[yy*W+xx]=color;
    }
    @Test public void genuinelyNewIntermediatePixelsPassOnStationaryBackground(){
        int[] before=canvas(0xffa15c3e),after=canvas(0xffa15c3e),
              middle=canvas(0xffa15c3e);
        square(before,5,45,0xff0b0a0a);
        square(after,38,45,0xff0b0a0a);
        square(middle,21,45,0xff0b0a0a);
        StoryboardTemporalCritic.Report qa=StoryboardTemporalCritic.inspect(
                before,after,middle,W,H,false);
        assertTrue(qa.reason,qa.acceptable);
        assertTrue(qa.middleSourceDifference>.001);
        assertTrue(qa.middleTargetDifference>.001);
        assertTrue(qa.unchangedWorldError<.08);
    }
    @Test public void rejectsStaticImageMasqueradingAsAnimation(){
        int[] first=canvas(0xffa15c3e),last=canvas(0xffa15c3e);
        square(first,6,32,0xff151515);square(last,30,32,0xff151515);
        assertFalse(StoryboardTemporalCritic.inspect(
                first,last,first.clone(),W,H,false).acceptable);
    }
    @Test public void trajectoryRejectsHoldJumpHoldEvenWithMovingMidpoint(){
        int[] a=canvas(0xffa15c3e),b=canvas(0xffa15c3e),
                middle=canvas(0xffa15c3e);
        square(a,5,45,0xff0b0a0a);
        square(b,38,45,0xff0b0a0a);
        square(middle,21,45,0xff0b0a0a);
        assertTrue(StoryboardTemporalCritic.inspect(a,b,middle,W,H,false).acceptable);
        assertFalse(StoryboardTemporalCritic.inspectTrajectory(a,b,a,middle,b,
                W,H,false).acceptable);
    }
    @Test public void movingIntermediatePositionsHaveFourMotionIntervals(){
        int[] a=canvas(0xffa15c3e),q=canvas(0xffa15c3e),
                m=canvas(0xffa15c3e),r=canvas(0xffa15c3e),
                b=canvas(0xffa15c3e);
        square(a,5,45,0xff0b0a0a);
        square(q,13,45,0xff0b0a0a);
        square(m,21,45,0xff0b0a0a);
        square(r,29,45,0xff0b0a0a);
        square(b,38,45,0xff0b0a0a);
        StoryboardTemporalCritic.TrajectoryReport qa=
                StoryboardTemporalCritic.inspectTrajectory(a,b,q,m,r,W,H,false);
        assertTrue(qa.reason,qa.acceptable);
        assertTrue(qa.minAdjacentMotion>0);
    }
    @Test public void dramaticWorldColorShiftFailsConservativeSceneLock(){
        int[] first=canvas(0xff222222),last=canvas(0xff272727),
              intermediate=canvas(0xffffffff);
        assertFalse(StoryboardTemporalCritic.inspect(
                first,last,intermediate,W,H,false).acceptable);
    }
}
