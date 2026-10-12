package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryboardCameraDirectorTest {
    @Test public void restoresExactSourceViewAtEveryStoryboardAnchor(){
        for(StoryboardMotionGrammar.Action beat:StoryboardMotionGrammar.Action.values()) {
            for(float time:new float[]{0f,1f}){
                StoryboardCameraDirector.State c=StoryboardCameraDirector.at(
                    StoryboardMotionGrammar.SceneKind.ACTION,beat,time,
                    .45f,.5f,.21f,.09f,12);
                assertEquals("No drifting frame boundaries",0,c.dx,.00001f);
                assertEquals(0,c.dy,.00001f);
                assertEquals(1,c.scale,.00001f);
                assertEquals(0,c.rotation,.00001f);
            }
        }
    }
    @Test public void impactCameraIsBoundedAndIsNotOnlyAnimation(){
        StoryboardCameraDirector.State c=StoryboardCameraDirector.at(
                StoryboardMotionGrammar.SceneKind.ACTION,
                StoryboardMotionGrammar.Action.IMPACT,.62f,
                .38f,.65f,.15f,0f,14);
        assertTrue(c.scale>1);
        assertTrue(c.scale<=1.045f);
        assertTrue(Math.abs(c.dx)<=.0121);
        assertTrue(Math.abs(c.dy)<=.0121);
        assertTrue(c.shake>0);
    }
    @Test public void rejectsInvalidCameraPositionsAndNoMagic3D(){
        try {
            StoryboardCameraDirector.at(
                StoryboardMotionGrammar.SceneKind.ACTION,
                StoryboardMotionGrammar.Action.LEAP,Float.NaN,
                .2f,.5f,0,0,2);
            fail("NaN source frame cannot animate");
        }catch(IllegalArgumentException expected){}
    }
}
