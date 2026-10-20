package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaBezierFitterTest {
    private static OmegaBezierFitter.Point p(double x,double y){return new OmegaBezierFitter.Point(x,y);}
    @Test public void curvedContourPreservesAllAnchorsAndProducesEditableHandles(){
        List<OmegaBezierFitter.Cubic> curves=OmegaBezierFitter.fit(
            Arrays.asList(p(.1,.5),p(.3,.2),p(.6,.25),p(.8,.6)),false,1);
        assertEquals(3,curves.size());
        assertEquals(.1,curves.get(0).from.x,0);
        assertEquals(.8,curves.get(2).to.x,0);
        assertTrue(curves.get(1).c1.x>.3);
        assertTrue(curves.get(1).c2.x<.6);
        assertEquals(curves.get(0).to.x,curves.get(1).from.x,0);
    }
    @Test public void closedShapeHasNoOpenEndpointOrDuplicateControl(){
        List<OmegaBezierFitter.Cubic> curves=OmegaBezierFitter.fit(
            Arrays.asList(p(.2,.3),p(.6,.3),p(.65,.65),p(.2,.7)),true,.85);
        assertEquals(4,curves.size());
        assertEquals(curves.get(0).from.x,curves.get(3).to.x,0);
    }
    @Test public void invalidGeometryRejectedRatherThanSimplifiedToBox(){
        try{OmegaBezierFitter.fit(Arrays.asList(p(.2,.2),p(.2,.2)),false,1);fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Duplicate"));}
        try{new OmegaBezierFitter.Point(Double.NaN,.1);fail();}
        catch(IllegalArgumentException expected){}
    }
}