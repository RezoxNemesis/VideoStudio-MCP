package com.rezoxnemesis.videostudio;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class StoryboardMotionPolicyTest {
    private static final int W=96,H=160;
    private static int[] frame(int brightX,int brightWidth) {
        int[] a=new int[W*H];
        Arrays.fill(a,0xff39444b);
        for(int y=65;y<125;y++)for(int x=brightX;x<Math.min(W,brightX+brightWidth);x++)
            a[y*W+x]=0xffebeff5;
        return a;
    }

    @Test public void largeActionChangesNeedNewCharacterPoses() {
        int[] a=frame(9,45),b=frame(45,45);
        PoseSequenceFlow.Pair pair=PoseSequenceFlow.analyse(a,b,W,H);
        StoryboardMotionPolicy.Diagnosis p=StoryboardMotionPolicy.evaluate(a,b,W,H,pair);
        assertNotEquals(StoryboardMotionPolicy.Decision.FLOW_CANDIDATE,p.decision);
        assertTrue(p.requiresPoseGeneration());
        assertTrue(p.changedActionFraction>.16);
    }

    @Test public void unrelatedScenesAreNotRebrandedAsInbetweenAnimation() {
        int[] a=new int[W*H],b=new int[W*H];
        Arrays.fill(a,0xff010203);
        Arrays.fill(b,0xffeeeeee);
        StoryboardMotionPolicy.Diagnosis d=StoryboardMotionPolicy.evaluate(
                a,b,W,H,PoseSequenceFlow.analyse(a,b,W,H));
        assertEquals(StoryboardMotionPolicy.Decision.NEEDS_SCENE_RECONSTRUCTION,d.decision);
    }

    @Test public void unchangedArtDoesNotInventAnimation() {
        int[] a=frame(22,14);
        StoryboardMotionPolicy.Diagnosis d=StoryboardMotionPolicy.evaluate(
                a,a,W,H,PoseSequenceFlow.analyse(a,a,W,H));
        assertEquals(StoryboardMotionPolicy.Decision.FLOW_CANDIDATE,d.decision);
        assertEquals(0,d.changedActionFraction,0);
    }
}
