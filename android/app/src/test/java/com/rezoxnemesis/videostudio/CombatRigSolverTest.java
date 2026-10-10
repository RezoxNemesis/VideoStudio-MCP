package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the generated physical movement, not re-timed copies of a still. */
public class CombatRigSolverTest {
    @Test public void bladesActuallyMeetAtTheScriptedImpact() {
        CombatRigSolver.Frame f=CombatRigSolver.at(.53*3.6,3.6);
        assertEquals(.48,f.sceneTime,1e-6);
        assertTrue("Sword contact must be geometrically exact",
                f.swordTipSeparation<.025);
        assertTrue("Contact must trigger a localized impact",f.impactIntensity>.9);
        CombatRigSolver.Frame before=CombatRigSolver.at(.20*3.6,3.6);
        assertTrue("Before the attack swords must be separate",
                before.swordTipSeparation>.10);
    }

    @Test public void feetRemainLockedWhileFightersWindUp() {
        CombatRigSolver.Frame a=CombatRigSolver.at(0,3.6);
        CombatRigSolver.Frame b=CombatRigSolver.at(.17*3.6,3.6);
        assertEquals(a.black.leadingFoot.x,b.black.leadingFoot.x,1e-7);
        assertEquals(a.black.trailingFoot.x,b.black.trailingFoot.x,1e-7);
        assertEquals(a.white.leadingFoot.x,b.white.leadingFoot.x,1e-7);
        assertEquals(a.white.trailingFoot.x,b.white.trailingFoot.x,1e-7);
        assertEquals(a.black.leadingFoot.y,b.black.leadingFoot.y,1e-7);
        assertEquals(a.white.leadingFoot.y,b.white.leadingFoot.y,1e-7);
    }

    @Test public void motionIsContinuousWithNoTeleportingHeadOrSword() {
        CombatRigSolver.Frame last=CombatRigSolver.at(0,3.6);
        double distinct=0;
        for(int i=1;i<=240;i++){
            double t=i*3.6/240.0;
            CombatRigSolver.Frame now=CombatRigSolver.at(t,3.6);
            assertTrue("black head jumps at frame "+i,
                    last.black.head.distance(now.black.head)<.03);
            assertTrue("white sword teleports at "+i,
                    last.white.swordTip.distance(now.white.swordTip)<.055);
            assertTrue("every frame has a fixed background camera",now.cameraLocked);
            distinct+=now.black.swordTip.distance(last.black.swordTip);
            last=now;
        }
        assertTrue("Sword tip must actually move",distinct>.30);
    }

    @Test public void slowMotionIsRealChangingPoseNotFrozenFrame() {
        double a=CombatRigSolver.remap(.38);
        double b=CombatRigSolver.remap(.40);
        double c=CombatRigSolver.remap(.49);
        double d=CombatRigSolver.remap(.51);
        assertTrue(b>a);
        assertTrue(d>c);
        assertTrue("Impact retiming slows timeline progress",(d-c)<(b-a));
        CombatRigSolver.Frame before=CombatRigSolver.at(.49*3.6,3.6);
        CombatRigSolver.Frame after=CombatRigSolver.at(.51*3.6,3.6);
        assertTrue(before.black.swordTip.distance(after.black.swordTip)>0.00001);
    }

    @Test public void fixedLimbSegmentsHonorAnalyticIkLength() {
        CombatRigSolver.Frame f=CombatRigSolver.at(1.68,3.6);
        assertEquals(.083,f.black.swordShoulder.distance(f.black.swordElbow),.00001);
        assertEquals(.091,f.white.leadingHip.distance(f.white.leadingKnee),.00001);
        assertEquals(.082,f.white.freeShoulder.distance(f.white.freeElbow),.00001);
    }

    @Test(expected=IllegalArgumentException.class)
    public void invalidTimelineIsNotExecuted(){CombatRigSolver.at(.2,0);}
}
