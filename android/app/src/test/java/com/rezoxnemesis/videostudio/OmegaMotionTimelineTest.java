package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaMotionTimelineTest {
    private static OmegaMotionTimeline.Key k(int f,double x,double y,boolean lock){
        return new OmegaMotionTimeline.Key(f,x,y,0,0,0,0,lock);
    }
    @Test public void keyframesEaseWithStableEndpoints(){
        OmegaMotionTimeline.Key a=k(0,.1,.2,false),b=k(20,.9,.7,false);
        assertEquals(.1,OmegaMotionTimeline.at(a,b,-10).x,1e-10);
        assertEquals(.9,OmegaMotionTimeline.at(a,b,100).x,1e-10);
        assertEquals(.5,OmegaMotionTimeline.at(a,b,10).x,1e-10);
        assertEquals(.45,OmegaMotionTimeline.at(a,b,10).y,1e-10);
    }
    @Test public void lockedContactMustRemainPlanted(){
        OmegaMotionTimeline.Pose p=OmegaMotionTimeline.at(k(0,.4,.5,true),k(24,.4,.5,true),12);
        assertEquals(.4,p.x,0);assertEquals(.5,p.y,0);
        try{OmegaMotionTimeline.at(k(0,.4,.5,true),k(24,.6,.5,true),12);fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("contact"));}
    }
    @Test public void secondaryOscillationDecaysAndRejectsInvalidInputs(){
        double early=Math.abs(OmegaMotionTimeline.secondary(2,1,.125,.08));
        double late=Math.abs(OmegaMotionTimeline.secondary(10,1,.125,.08));
        assertTrue(early>late);
        try{OmegaMotionTimeline.secondary(-1,1,.1,.1);fail();}
        catch(IllegalArgumentException expected){}
    }
}