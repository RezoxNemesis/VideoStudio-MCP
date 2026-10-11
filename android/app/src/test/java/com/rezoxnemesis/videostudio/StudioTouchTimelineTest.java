package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;

public class StudioTouchTimelineTest {
    private static ProjectStore.Clip clip(String id,long in,long out,float speed){
        ProjectStore.Clip c=new ProjectStore.Clip();
        c.id=id;c.assetId=id;c.inMs=in;c.outMs=out;c.speed=speed;
        return c;
    }
    @Test public void movingClipChangesOrderWithoutChangingSourceOrTrim(){
        ArrayList<ProjectStore.Clip> sequence=new ArrayList<>(Arrays.asList(
                clip("a",0,3000,1),clip("b",500,4500,2),clip("c",0,1000,1)));
        assertTrue(StudioTimelineEditOps.move(sequence,"c",0));
        assertEquals(Arrays.asList("c","a","b"),StudioTimelineEditOps.order(sequence));
        assertEquals(500,sequence.get(2).inMs);
        assertEquals(4500,sequence.get(2).outMs);
        assertEquals(1000,StudioTimelineEditOps.startOf(sequence,"a"));
        assertFalse(StudioTimelineEditOps.move(sequence,"not-found",1));
        assertFalse(StudioTimelineEditOps.move(sequence,"c",-1));
    }
    @Test public void laneDraggingClampsInsideVideoAndOnlyRightHandleChangesLength(){
        StudioLayerTouchMath.Timing left=StudioLayerTouchMath.move(10000,3000,2500,-9000);
        assertEquals(0,left.startMs);assertEquals(2500,left.durationMs);
        StudioLayerTouchMath.Timing right=StudioLayerTouchMath.move(10000,3000,2500,20000);
        assertEquals(7500,right.startMs);
        StudioLayerTouchMath.Timing trim=StudioLayerTouchMath.trimRight(10000,2000,3000,20000);
        assertEquals(8000,trim.durationMs);
        assertEquals(100,StudioLayerTouchMath.trimRight(10000,2000,3000,-20000).durationMs);
    }
    @Test public void snapsOnlyNearRealClipBoundaries(){
        long[] clips={0,1700,4200,10000};
        assertEquals(4200,StudioLayerTouchMath.snap(4180,clips,70));
        assertEquals(4500,StudioLayerTouchMath.snap(4500,clips,70));
    }
    @Test(expected=IllegalArgumentException.class)
    public void invalidClipTimingIsNeverApplied(){
        StudioLayerTouchMath.move(300,100,250,0);
    }
}
