package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class CombatActionPlanTest {
    @Test public void aFewMeaningfulActionBeatsProduceHundredsOfContinuousTimestamps(){
        CombatActionPlan plan=new CombatActionPlan();
        double duration=10;
        double lastMotion=-1,largestStep=0;
        boolean[] visited=new boolean[CombatActionPlan.BEAT_COUNT];
        for(int frame=0;frame<=300;frame++){
            CombatActionPlan.Frame state=plan.at(frame/30.0,duration);
            visited[state.beatIndex]=true;
            assertTrue(state.solverSeconds>=lastMotion);
            if(lastMotion>=0)
                largestStep=Math.max(largestStep,state.solverSeconds-lastMotion);
            lastMotion=state.solverSeconds;
            assertTrue(state.cameraDistance>3&&state.cameraDistance<7);
            assertTrue(state.beatProgress>=0&&state.beatProgress<=1);
        }
        for(boolean reached:visited)assertTrue(reached);
        assertTrue("No sudden temporal jumps across action phases",largestStep<.11);
        assertEquals(0,plan.at(0,duration).solverSeconds,.00001);
        assertEquals(duration,plan.at(duration,duration).solverSeconds,.00001);
    }

    @Test public void impactEnergyPeaksAtSwordContactNotRandomIntervals(){
        CombatActionPlan plan=new CombatActionPlan();
        CombatActionPlan.Frame clash=plan.at(5.2,10);
        assertEquals(CombatActionPlan.Beat.CONTACT,clash.beat);
        assertTrue(clash.contactEnergy>.98);
        assertTrue(clash.bladeCollisionWindow);
        assertEquals("swords_clash",plan.cueAt(5.2,10));
        assertTrue(plan.at(.5,10).contactEnergy<.01);
        assertEquals("blade_whoosh",plan.cueAt(4.8,10));
    }

    @Test public void customPacingRetimesWithoutRequiringAnyMoreImages(){
        CombatActionPlan plan=new CombatActionPlan(new double[]{
            0,.08,.20,.32,.47,.56,.70,.89,1
        });
        assertEquals(CombatActionPlan.Beat.CONTACT,plan.at(4.75,10).beat);
        assertEquals(10,plan.at(10,10).solverSeconds,.0001);
        assertEquals(9,plan.boundaries().length);
        double[] external=plan.boundaries();
        external[4]=.01; 
        assertEquals(.47,plan.boundaries()[4],.00001);
    }

    @Test(expected=IllegalArgumentException.class)
    public void cannotConfigureOverlappingStoryBeats(){
        new CombatActionPlan(new double[]{0,.2,.28,.3,.41,.52,.7,.84,1});
    }

    @Test public void pureMonotoneCurveKeepsInterpolationInsideEndpoints(){
        double[] t={0,.13,.5,.73,1};
        double[] p={0,.19,.55,.68,1};
        for(int i=0;i<=100;i++){
            double pos=CombatActionPlan.sampleMonotone(t,p,i/100.0);
            assertTrue(pos>=0&&pos<=1);
            if(i>0)assertTrue(pos>=CombatActionPlan.sampleMonotone(t,p,(i-1)/100.0));
        }
    }
}
