package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchStrokePrecisionTest {
    private JSONArray samples(double...xy)throws Exception{
        JSONArray out=new JSONArray();
        for(int i=0;i<xy.length;i+=2)out.put(new JSONArray().put(xy[i]).put(xy[i+1]).put(1));
        return out;
    }
    @Test public void respectsExactInputWhenStabilizationIsOff()throws Exception{
        double[] a=SketchStrokePrecision.filter(samples(.2,.3),.7,.31,0,1280,720);
        assertEquals(.7,a[0],1e-10);assertEquals(.31,a[1],1e-10);
    }
    @Test public void slowNoiseIsDampedButFastIntentionalStrokeDoesNotLag()throws Exception{
        double[] tiny=SketchStrokePrecision.filter(samples(.2,.3),.201,.301,.8,1280,720);
        double[] fast=SketchStrokePrecision.filter(samples(.2,.3),.7,.7,.8,1280,720);
        assertTrue(tiny[0]>.2&&tiny[0]<.201);
        assertTrue(Math.abs(fast[0]-.7)<.01);
        assertTrue(Math.abs(fast[1]-.7)<.01);
    }
    @Test public void sharpCornerIsPreserved()throws Exception{
        JSONArray draw=samples(.2,.2,.3,.2);
        double[] turn=SketchStrokePrecision.filter(draw,.3,.3,.9,1000,1000);
        assertEquals(.3,turn[0],1e-10);assertEquals(.3,turn[1],1e-10);
    }
    @Test public void rejectsUnboundedOrNonFiniteValues()throws Exception{
        try{SketchStrokePrecision.filter(samples(.2,.2),Double.NaN,.2,.5,720,1280);fail();}
        catch(IllegalArgumentException expected){}
        try{SketchStrokePrecision.filter(samples(.2,.2),.4,.4,.5,0,1280);fail();}
        catch(IllegalArgumentException expected){}
    }
}
