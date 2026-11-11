package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryboardPoseMotionTest {
    private static float[][] joints(float dx,float dy) {
        float[][] p=Rig2DProfileTest.joints();
        for(float[] a:p){
            a[0]+=dx;a[1]+=dy;
        }
        return p;
    }

    private static Rig2DProfile rig(boolean dark,float dx,float dy,String id){
        return new Rig2DProfile(id,"project-1111","source-"+id,
                dark,false,joints(dx,dy),Rig2DProfileTest.outline(),
                new float[]{.72f+dx,.52f+dy});
    }

    @Test public void sourcesArePixelKeyframesNotGenericCombatSolver() {
        Rig2DProfile a=rig(true,0,0,"from-12345");
        Rig2DProfile b=rig(true,.06f,-.03f,"next-12345");
        float[][] start=StoryboardPoseMotion.interpolate(a.joints,b.joints,0,
                StoryboardPoseMotion.Action.STRIKE);
        float[][] end=StoryboardPoseMotion.interpolate(a.joints,b.joints,1,
                StoryboardPoseMotion.Action.STRIKE);
        for(int i=0;i<16;i++){
            assertArrayEquals(a.joints[i],start[i],.000001f);
            assertArrayEquals(b.joints[i],end[i],.000001f);
        }
        float[][] between=StoryboardPoseMotion.interpolate(a.joints,b.joints,.5,
                StoryboardPoseMotion.Action.STRIKE);
        assertTrue("Actual actor moved",between[0][0]>a.joints[0][0]);
        assertTrue(between[0][0]<b.joints[0][0]);
        assertTrue(Math.abs(between[3][1]-a.joints[3][1])>.005);
    }

    @Test public void sourceSwordStaysAttachedThroughoutCinematicStrike() {
        Rig2DProfile a=rig(true,0,0,"start-1234");
        Rig2DProfile b=rig(true,.03f,.03f,"end-123456");
        float[] start={.72f,.52f},end={.74f,.60f};
        for(int t=0;t<=20;t++){
            double time=t/20d;
            float[][] pose=StoryboardPoseMotion.interpolate(a.joints,b.joints,
                    time,StoryboardPoseMotion.Action.STRIKE);
            float[] tip=StoryboardPoseMotion.blade(a.joints,b.joints,start,end,
                    time,StoryboardPoseMotion.Action.STRIKE);
            float distance=(float)Math.hypot(
                    tip[0]-pose[Rig2DProfile.SWORD_HAND][0],
                    tip[1]-pose[Rig2DProfile.SWORD_HAND][1]);
            assertTrue(distance>.012f && distance<.65f);
            assertTrue(Float.isFinite(tip[0]) && Float.isFinite(tip[1]));
        }
        assertArrayEquals(start,StoryboardPoseMotion.blade(a.joints,b.joints,
                start,end,0,StoryboardPoseMotion.Action.STRIKE),.000001f);
        assertArrayEquals(end,StoryboardPoseMotion.blade(a.joints,b.joints,
                start,end,1,StoryboardPoseMotion.Action.STRIKE),.000001f);
    }

    @Test public void sourceAppearanceAndPolarityCannotSwapActors() {
        Rig2DProfile firstBlack=rig(true,0,0,"bf-123456");
        Rig2DProfile lastBlack=rig(true,.06f,0,"bl-123456");
        Rig2DProfile firstWhite=rig(false,.01f,0,"wf-123456");
        Rig2DProfile lastWhite=rig(false,.04f,0,"wl-123456");
        StoryboardPoseMotion.Frame shot=StoryboardPoseMotion.between(
                firstBlack,firstWhite,lastBlack,lastWhite,
                firstBlack.swordTip,firstWhite.swordTip,
                lastBlack.swordTip,lastWhite.swordTip,.4,
                StoryboardPoseMotion.Action.PARRY);
        assertNotNull(shot.black);
        assertNotNull(shot.white);
        assertEquals(16,shot.black.length);
        assertEquals(16,shot.white.length);
        try{
            StoryboardPoseMotion.between(firstWhite,firstBlack,lastBlack,lastWhite,
                    firstBlack.swordTip,firstWhite.swordTip,
                    lastBlack.swordTip,lastWhite.swordTip,.4,
                    StoryboardPoseMotion.Action.PARRY);
            fail("Swapped source fighters should not pass validation");
        }catch(IllegalArgumentException correct){}
    }

    @Test public void rejectUntracedOrImpossibleAnimationInputs() {
        float[][] a=joints(0,0),b=joints(.05f,0);
        try{StoryboardPoseMotion.interpolate(a,b,Double.NaN,
                StoryboardPoseMotion.Action.ADVANCE);fail();}
        catch(IllegalArgumentException expected){}
        try{StoryboardPoseMotion.blade(a,b,new float[]{1.2f,.2f},
                new float[]{.8f,.2f},.5,
                StoryboardPoseMotion.Action.STRIKE);fail();}
        catch(IllegalArgumentException expected){}
        assertEquals(StoryboardPoseMotion.Action.LEAP,
                StoryboardPoseMotion.action("leap"));
    }

    @Test public void footContactsRemainStableWhenAuthorDrewPlant() {
        float[][] a=joints(0,0),b=joints(.04f,0);
        b[12]=a[12].clone();b[15]=a[15].clone();
        float[][] middle=StoryboardPoseMotion.interpolate(a,b,.5,
                StoryboardPoseMotion.Action.PARRY);
        assertEquals(a[12][0],middle[12][0],.00001f);
        assertEquals(a[12][1],middle[12][1],.00001f);
        assertEquals(a[15][0],middle[15][0],.00001f);
    }
}
