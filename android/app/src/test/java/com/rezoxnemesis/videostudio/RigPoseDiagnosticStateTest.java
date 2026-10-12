package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Diagnostic gestures must never become source annotations. */
public class RigPoseDiagnosticStateTest {
    @Test public void testEditsAndInterpolationKeepSourceMarksAndSavedSnapshotsIndependent() {
        float[][] original=Rig2DProfileTest.joints();float[] tip={.82f,.35f};
        RigPoseDiagnosticState state=new RigPoseDiagnosticState(original,tip);
        RigPoseDiagnosticState.Snapshot accepted=state.snapshot();
        state.moveJoint(Rig2DProfile.PELVIS,.52f,.67f);state.moveSwordTip(.92f,.45f);state.setPhase(.5f);
        assertArrayEquals(new float[]{.47f,.62f},state.targetJoints()[0],.00001f);
        assertArrayEquals(new float[]{.87f,.40f},state.targetSwordTip(),.00001f);
        assertArrayEquals(new float[]{.42f,.57f},original[0],.00001f);
        assertArrayEquals(new float[]{.82f,.35f},tip,.00001f);
        original[0][0]=.99f;tip[0]=.99f;
        state.restore(accepted);
        assertArrayEquals(new float[]{.42f,.57f},state.targetJoints()[0],.00001f);
        assertArrayEquals(new float[]{.82f,.35f},state.targetSwordTip(),.00001f);
        assertEquals(1,state.phase(),0);
    }

    @Test public void cancelledGestureRestoresOnlyTestPose() {
        RigPoseDiagnosticState state=new RigPoseDiagnosticState(Rig2DProfileTest.joints(),new float[]{.82f,.35f});
        state.moveJoint(0,.6f,.65f);RigPoseDiagnosticState.Snapshot before=state.snapshot();
        state.moveJoint(0,.7f,.7f);state.setPhase(.25f);state.restore(before);
        assertArrayEquals(new float[]{.6f,.65f},state.targetJoints()[0],.00001f);
        state.setPhase(0);
        assertArrayEquals(new float[]{.42f,.57f},state.targetJoints()[0],.00001f);
    }

    @Test public void invalidPoseOrPhaseDoesNotOverwriteTheLastTestPose() {
        RigPoseDiagnosticState state=new RigPoseDiagnosticState(Rig2DProfileTest.joints(),new float[]{.82f,.35f});
        assertThrows(IllegalArgumentException.class,()->state.moveJoint(0,Float.NaN,.5f));
        assertThrows(IllegalArgumentException.class,()->state.moveSwordTip(1.01f,.5f));
        assertThrows(IllegalArgumentException.class,()->state.setPhase(-.01f));
        assertArrayEquals(new float[]{.42f,.57f},state.targetJoints()[0],.00001f);
        assertEquals(1,state.phase(),0);
    }
}
