package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryboardMotionGrammarTest {
    @Test public void actionVocabularyWorksBeyondCombat() {
        assertEquals(StoryboardMotionGrammar.Action.LEAP,
                StoryboardMotionGrammar.parseAction("animal jumping over rocks",null));
        assertEquals(StoryboardMotionGrammar.Action.REVEAL,
                StoryboardMotionGrammar.parseAction("write-on logo reveal",null));
        assertEquals(StoryboardMotionGrammar.Action.SWAY,
                StoryboardMotionGrammar.parseAction("trees sway with the wind",null));
        assertEquals(StoryboardMotionGrammar.Action.ORBIT,
                StoryboardMotionGrammar.parseAction("camera orbits a product",null));
        assertEquals(StoryboardMotionGrammar.Action.RUN,
                StoryboardMotionGrammar.parseAction("car chase through downtown",null));
        assertEquals(StoryboardMotionGrammar.SceneKind.COMIC,
                StoryboardMotionGrammar.scene("Manhwa panel animation"));
        assertEquals(StoryboardMotionGrammar.SceneKind.EXPLAINER,
                StoryboardMotionGrammar.scene("Educational diagram"));
        assertEquals(StoryboardMotionGrammar.SceneKind.PRODUCT,
                StoryboardMotionGrammar.scene("Product showcase"));
        assertEquals(StoryboardMotionGrammar.SceneKind.LANDSCAPE,
                StoryboardMotionGrammar.scene("Mountain forest nature"));
    }
    @Test public void sourceMotionStartsAndEndsAtActualDrawnPositions(){
        for(StoryboardMotionGrammar.Action action:StoryboardMotionGrammar.Action.values()){
            if(action==StoryboardMotionGrammar.Action.CUT)continue;
            StoryboardMotionGrammar.Motion a=StoryboardMotionGrammar.at(action,0,
                    .16f,.10f,0,20,1,1.15f,
                    StoryboardMotionGrammar.SubjectKind.OBJECT);
            StoryboardMotionGrammar.Motion b=StoryboardMotionGrammar.at(action,1,
                    .16f,.10f,0,20,1,1.15f,
                    StoryboardMotionGrammar.SubjectKind.OBJECT);
            assertEquals(0,a.x,.00001f);
            assertEquals(0,a.y,.00001f);
            assertEquals(.16f,b.x,.00001f);
            assertEquals(.10f,b.y,.00001f);
            assertEquals(1.15f,b.scale,.00001f);
        }
    }
    @Test public void leapingHasBallisticArcAndImpactHasContactPeak(){
        StoryboardMotionGrammar.Motion jump=StoryboardMotionGrammar.at(
                StoryboardMotionGrammar.Action.LEAP,.5f,
                .05f,0,0,0,1,1,
                StoryboardMotionGrammar.SubjectKind.CHARACTER);
        assertTrue(jump.y<-.05f);
        StoryboardMotionGrammar.Motion impact=StoryboardMotionGrammar.at(
                StoryboardMotionGrammar.Action.IMPACT,.62f,
                .1f,.02f,0,0,1,1,
                StoryboardMotionGrammar.SubjectKind.OBJECT);
        assertTrue(impact.impact>.98f);
        assertTrue(impact.shake>0);
    }
    @Test public void zeroMotionDoesNotClaimHumanArticulation(){
        StoryboardMotionGrammar.Motion landscape=StoryboardMotionGrammar.at(
                StoryboardMotionGrammar.Action.PAN,.5f,
                .01f,0,0,0,1,1,
                StoryboardMotionGrammar.SubjectKind.ENVIRONMENT);
        assertFalse(landscape.bodyMotion);
        try{
            StoryboardMotionGrammar.at(StoryboardMotionGrammar.Action.RUN,
                    Float.NaN,0,0,0,0,1,1,
                    StoryboardMotionGrammar.SubjectKind.CHARACTER);
            fail();
        }catch(IllegalArgumentException expected){}
    }
}
