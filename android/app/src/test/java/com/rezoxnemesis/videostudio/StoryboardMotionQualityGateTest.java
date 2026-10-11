package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryboardMotionQualityGateTest {
    private Rig2DProfile profile(boolean black,float shift,String id){
        float[][] points=Rig2DProfileTest.joints();
        for(float[] p:points)p[0]+=shift;
        return Rig2DProfile.create("project-test-1","asset-"+id,
                black,false,points,Rig2DProfileTest.outline(),
                new float[]{.71f+shift,.5f});
    }

    @Test public void rejectsUnmovingArtworkWithoutPretendingCameraZoomIsAnimation() {
        Rig2DProfile b=profile(true,0,"black-1111");
        Rig2DProfile w=profile(false,0,"white-1111");
        StoryboardMotionQualityGate.Verdict verdict=
                StoryboardMotionQualityGate.inspect(b,w,b,w,432,768);
        assertFalse(verdict.approved);
        assertTrue(verdict.reason.contains("no measurable"));
    }
    @Test public void acceptsDocumentedActorDisplacementWithMeasuredPixels(){
        StoryboardMotionQualityGate.Verdict motion=
                StoryboardMotionQualityGate.inspect(
                profile(true,0,"b0-11111"),profile(false,0,"w0-11111"),
                profile(true,.055f,"b1-11111"),profile(false,.045f,"w1-11111"),
                432,768);
        assertTrue(motion.approved);
        assertTrue(motion.actorMotionPixels>15);
    }
    @Test public void rejectsUnrealisticallyHugeCameraJump(){
        StoryboardMotionQualityGate.Verdict motion=
                StoryboardMotionQualityGate.inspect(
                profile(true,-.12f,"b0-11111"),profile(false,-.12f,"w0-11111"),
                profile(true,.28f,"b1-11111"),profile(false,.28f,"w1-11111"),
                432,768);
        assertFalse(motion.approved || motion.actorMotionPixels<170);
    }
}
