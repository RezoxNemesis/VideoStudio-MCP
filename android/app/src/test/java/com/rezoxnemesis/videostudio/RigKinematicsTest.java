package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class RigKinematicsTest {
    private static RigKinematics.Vec3 v(double x,double y,double z) {
        return new RigKinematics.Vec3(x,y,z);
    }

    @Test public void twoBoneSolvesCorrectSegmentLengthsInThreeDimensions() {
        RigKinematics.TwoBonePose pose=RigKinematics.solveTwoBone(
                v(0,0,0),v(1.2,0,0.4),v(0,1,1),.8,.8);
        assertTrue(pose.targetReachable);
        assertEquals(.8,pose.root.distance(pose.joint),1e-5);
        assertEquals(.8,pose.joint.distance(pose.effector),1e-5);
        assertEquals(1.2,pose.effector.x,1e-5);
        assertEquals(.4,pose.effector.z,1e-5);
    }

    @Test public void plantedFootStaysFixedAsHipMoves() {
        RigKinematics.Vec3 foot=v(.1,1.0,0), pole=v(.4,.6,.3);
        RigKinematics.TwoBonePose a=RigKinematics.plantedLeg(
                v(.1,0,0),foot,pole,.65,.65);
        RigKinematics.TwoBonePose b=RigKinematics.plantedLeg(
                v(.2,.1,.1),foot,pole,.65,.65);
        assertEquals(foot.x,a.effector.x,1e-6);
        assertEquals(foot.x,b.effector.x,1e-6);
        assertEquals(foot.y,a.effector.y,1e-6);
        assertEquals(foot.y,b.effector.y,1e-6);
        assertTrue(a.joint.distance(b.joint)>.01);
    }

    @Test public void detectsBladeCollisionAlongMetalNotOnlyTips() {
        assertEquals(0,RigKinematics.segmentDistance(
                v(0,0,0),v(2,0,0),v(1,-1,0),v(1,1,0)),1e-7);
        assertEquals(1,RigKinematics.segmentDistance(
                v(0,0,0),v(2,0,0),v(1,-1,1),v(1,1,1)),1e-7);
        assertEquals(2,RigKinematics.segmentDistance(
                v(0,0,0),v(0,0,0),v(2,0,0),v(2,0,0)),1e-7);
    }

    @Test public void temporalMotionHasMovingSubframesAndExactAnchors() {
        RigKinematics.Vec3 before=v(-1,0,0),start=v(0,0,0);
        RigKinematics.Vec3 end=v(2,1,1),after=v(3,1.5,1);
        RigKinematics.Vec3 p=RigKinematics.samplePosition(
                before,start,end,after,1,.7,1,.42);
        assertTrue(p.x>0&&p.x<2);
        assertTrue(p.y>0&&p.y<1);
        assertTrue(p.z>0&&p.z<1);
        assertEquals(start.x,RigKinematics.samplePosition(
                before,start,end,after,1,.7,1,0).x,0);
        assertEquals(end.x,RigKinematics.samplePosition(
                before,start,end,after,1,.7,1,1).x,0);
    }

    @Test(expected=IllegalArgumentException.class)
    public void invalidLegMeasurementsCannotBeAnimated() {
        RigKinematics.plantedLeg(v(0,0,0),v(0,0,1),v(0,1,0),0,.5);
    }
}
