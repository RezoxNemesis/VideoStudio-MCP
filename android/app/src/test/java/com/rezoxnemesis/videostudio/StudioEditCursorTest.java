package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class StudioEditCursorTest {
    @Test public void playheadMapsExactlyThroughSpeedAndTrim(){
        assertEquals(3500,StudioEditCursor.sourceAt(1000,7000,2,4000,5250));
        assertEquals(1250,StudioEditCursor.sourceAt(1000,7000,.5,4000,4500));
    }
    @Test public void cursorAtStartNeverBecomesAnUnrequestedMidpointSplit(){
        assertFalse(StudioEditCursor.canSplit(1000,7000,2,4000,4000));
        assertTrue(StudioEditCursor.canSplit(1000,7000,2,4000,4100));
        assertFalse(StudioEditCursor.canSplit(1000,7000,2,4000,6999));
    }
    @Test(expected=IllegalArgumentException.class) public void anotherClipsCursorIsRejected(){
        StudioEditCursor.sourceAt(1000,7000,1,4000,3500);
    }
    @Test public void sourceOutBoundaryRemainsAvailableForExactTrimming(){
        assertEquals(7000,StudioEditCursor.sourceAt(1000,7000,2,4000,7000));
    }
    @Test public void cutNavigationSkipsCurrentCutAndClampsAtEnds(){
        long[] cuts={0,1500,4200,9000};
        assertEquals(0,StudioEditCursor.adjacentBoundary(cuts,1500,-1));
        assertEquals(4200,StudioEditCursor.adjacentBoundary(cuts,1500,1));
        assertEquals(1500,StudioEditCursor.adjacentBoundary(cuts,1800,-1));
        assertEquals(0,StudioEditCursor.adjacentBoundary(cuts,0,-1));
        assertEquals(9000,StudioEditCursor.adjacentBoundary(cuts,9000,1));
    }
}
