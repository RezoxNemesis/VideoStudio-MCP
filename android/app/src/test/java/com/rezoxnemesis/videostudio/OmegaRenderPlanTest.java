package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaRenderPlanTest {
    private static final String H="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Test public void selectiveDirtyRangesMergeWithoutTouchingOtherFrames(){
        List<OmegaRenderPlan.Range> ranges=OmegaRenderPlan.invalidated(100,Arrays.asList(
            new OmegaRenderPlan.Range(7,12),new OmegaRenderPlan.Range(10,16),
            new OmegaRenderPlan.Range(80,85)));
        assertEquals(2,ranges.size());
        assertEquals(7,ranges.get(0).first);assertEquals(16,ranges.get(0).last);
        assertEquals(80,ranges.get(1).first);assertEquals(85,ranges.get(1).last);
    }
    @Test public void contentRevisionAndFrameArePartOfCacheIdentity(){
        String a=OmegaRenderPlan.cacheKey("film",H,1,24,4);
        assertEquals(64,a.length());
        assertEquals(a,OmegaRenderPlan.cacheKey("film",H,1,24,4));
        assertNotEquals(a,OmegaRenderPlan.cacheKey("film",H,2,24,4));
        assertNotEquals(a,OmegaRenderPlan.cacheKey("film",H,1,24,5));
    }
    @Test public void ownerRecoveryHoldCannotBeBypassed(){
        OmegaRenderPlan.Checkpoint c=new OmegaRenderPlan.Checkpoint(H,6,30);
        assertFalse(OmegaRenderPlan.mayResume(c,H,6,100,true));
        assertTrue(OmegaRenderPlan.mayResume(c,H,6,100,false));
        assertFalse(OmegaRenderPlan.mayResume(c,H,7,100,false));
        assertFalse(OmegaRenderPlan.mayResume(null,H,6,100,false));
        try{OmegaRenderPlan.invalidated(10,Arrays.asList(new OmegaRenderPlan.Range(9,12)));fail();}
        catch(IllegalArgumentException expected){}
    }
}