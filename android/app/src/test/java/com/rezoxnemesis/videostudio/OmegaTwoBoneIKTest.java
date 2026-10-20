package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaTwoBoneIKTest {
    private static OmegaTwoBoneIK.Joint p(double x,double y){return new OmegaTwoBoneIK.Joint(x,y);}
    @Test public void preservesBoneLengthsAndKnownReachableContact(){
        OmegaTwoBoneIK.Solution pose=OmegaTwoBoneIK.solve(p(0,0),p(1,1),1,1,1);
        assertTrue(pose.reachable);
        assertEquals(1,pose.root.distanceTo(pose.elbow),1e-10);
        assertEquals(1,pose.elbow.distanceTo(pose.tip),1e-10);
        assertEquals(1,pose.tip.x,1e-10);assertEquals(1,pose.tip.y,1e-10);
        OmegaTwoBoneIK.Solution reverse=OmegaTwoBoneIK.solve(p(0,0),p(1,1),1,1,-1);
        assertTrue(pose.elbow.distanceTo(reverse.elbow)>.1);
    }
    @Test public void unreachableTargetsAreClampedWithoutBoneStretch(){
        OmegaTwoBoneIK.Solution pose=OmegaTwoBoneIK.solve(p(0,0),p(10,0),1,1,1);
        assertFalse(pose.reachable);
        assertEquals(2,pose.tip.x,1e-10);
        assertEquals(1,pose.root.distanceTo(pose.elbow),1e-10);
        assertEquals(1,pose.elbow.distanceTo(pose.tip),1e-10);
    }
    @Test public void coincidentTargetAndMalformedInputsAreHandled(){
        OmegaTwoBoneIK.Solution pose=OmegaTwoBoneIK.solve(p(.2,.2),p(.2,.2),.4,.4,1);
        assertEquals(.4,pose.root.distanceTo(pose.elbow),1e-7);
        assertEquals(.4,pose.elbow.distanceTo(pose.tip),1e-7);
        try{OmegaTwoBoneIK.solve(p(0,0),p(1,1),0,1,1);fail();}
        catch(IllegalArgumentException expected){}
        try{p(Double.NaN,0);fail();}catch(IllegalArgumentException expected){}
    }
}