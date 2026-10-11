package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public final class StoryboardInteractionSolverTest {
    @Test public void sourceImpactProvidesBoundedSeparationWithoutMovingEndpoints(){
        StoryboardInteractionSolver.Body a=
                new StoryboardInteractionSolver.Body(.46f,.68f,.10f,.14f);
        StoryboardInteractionSolver.Body b=
                new StoryboardInteractionSolver.Body(.51f,.68f,.10f,.14f);
        StoryboardInteractionSolver.resolve(Arrays.asList(a,b),.5f,
                StoryboardMotionGrammar.Action.IMPACT);
        assertTrue(a.x<.46f);
        assertTrue(b.x>.51f);
        assertTrue(a.contactEnergy>0);
        assertTrue(b.contactEnergy>0);
        assertTrue(Math.abs(a.x-.46f)<.02f);
        assertTrue(Math.abs(b.x-.51f)<.02f);
        StoryboardInteractionSolver.Body c=
                new StoryboardInteractionSolver.Body(.46f,.68f,.10f,.14f);
        StoryboardInteractionSolver.Body d=
                new StoryboardInteractionSolver.Body(.51f,.68f,.10f,.14f);
        StoryboardInteractionSolver.resolve(Arrays.asList(c,d),1f,
                StoryboardMotionGrammar.Action.IMPACT);
        assertEquals(.46f,c.x,.00001f);
        assertEquals(.51f,d.x,.00001f);
    }
    @Test public void ordinaryObjectTrackingHasNoInventedCollisionImpulse(){
        StoryboardInteractionSolver.Body a=
                new StoryboardInteractionSolver.Body(.42f,.62f,.06f,.12f);
        StoryboardInteractionSolver.Body b=
                new StoryboardInteractionSolver.Body(.48f,.62f,.06f,.12f);
        StoryboardInteractionSolver.resolve(Arrays.asList(a,b),.4f,
                StoryboardMotionGrammar.Action.DRIFT);
        assertEquals(.42f,a.x,.00001f);
        assertEquals(.48f,b.x,.00001f);
        assertEquals(0,a.contactEnergy,.00001f);
    }
}
