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
    @Test public void acceptsIndependentAuthoredElbowWristAndKneeChanges(){
        StoryboardMotionQualityGate.Verdict motion=
                StoryboardMotionQualityGate.inspect(
                profile(true,0,"b0-11111"),profile(false,0,"w0-11111"),
                articulatedProfile(true,.055f,"b1-11111"),articulatedProfile(false,.045f,"w1-11111"),
                432,768);
        assertTrue(motion.approved);
        assertTrue(motion.actorMotionPixels>15);
        assertTrue(motion.blackNonAffineMotionPixels>1);
        assertTrue(motion.whiteNonAffineMotionPixels>1);
    }
    private Rig2DProfile articulatedProfile(boolean black,float shift,String id){
        float[][] points=Rig2DProfileTest.joints();
        for(float[] point:points)point[0]+=shift;
        points[Rig2DProfile.SWORD_ELBOW][1]-=.035f;
        points[Rig2DProfile.SWORD_HAND][1]-=.06f;
        points[Rig2DProfile.LEADING_KNEE][0]-=.04f;
        points[Rig2DProfile.FREE_ELBOW][0]+=.035f;
        return Rig2DProfile.create("project-test-1","asset-"+id,
                black,false,points,Rig2DProfileTest.outline(),new float[]{.71f+shift,.44f});
    }
    @Test public void rigidRootTranslationIsNotArticulatedAction(){
        StoryboardMotionQualityGate.Verdict motion=
                StoryboardMotionQualityGate.inspect(
                profile(true,0,"rigid-black-start"),profile(false,0,"rigid-white-start"),
                profile(true,.055f,"rigid-black-end"),profile(false,.045f,"rigid-white-end"),
                432,768);
        assertTrue("The fixture really moves visible source landmarks",motion.actorMotionPixels>15);
        assertFalse("Every joint inheriting root translation cannot qualify as articulated combat",motion.approved);
    }
    @Test public void oneCommonAffineTransformIsNotIndependentLimbMotion(){
        StoryboardMotionQualityGate.Verdict motion=
                StoryboardMotionQualityGate.inspect(
                profile(true,0,"affine-black-start"),profile(false,0,"affine-white-start"),
                affineProfile(true,"affine-black-end"),affineProfile(false,"affine-white-end"),
                432,768);
        assertTrue("The camera-like affine fixture changes measured pixels",motion.actorMotionPixels>3);
        assertFalse("Rigid rotation/scale/translation of all joints is not limb articulation",motion.approved);
    }
    private Rig2DProfile affineProfile(boolean black,String id){
        float[][] points=Rig2DProfileTest.joints();
        for(int index=0;index<points.length;index++)points[index]=affine(points[index]);
        return Rig2DProfile.create("project-test-1","asset-"+id,
                black,false,points,Rig2DProfileTest.outline(),affine(new float[]{.71f,.5f}));
    }
    private float[] affine(float[] point){
        double x=point[0]-.5,y=point[1]-.55,c=Math.cos(.17),s=Math.sin(.17);
        return new float[]{(float)(.53+1.08*(c*x-s*y)),(float)(.56+1.08*(s*x+c*y))};
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
