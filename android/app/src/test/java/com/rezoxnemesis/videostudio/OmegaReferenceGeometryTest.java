package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaReferenceGeometryTest {
    private static OmegaReferenceGeometry.Point p(double x,double y){return new OmegaReferenceGeometry.Point(x,y);}
    @Test public void guideAlignmentFitsTranslationScaleAndRotation(){
        List<OmegaReferenceGeometry.Pair> data=Arrays.asList(
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.65,.15),1),
          new OmegaReferenceGeometry.Pair(p(.3,.1),p(.65,.25),1),
          new OmegaReferenceGeometry.Pair(p(.1,.3),p(.55,.15),1));
        OmegaReferenceGeometry.Fit fit=OmegaReferenceGeometry.align(data);
        assertEquals(.5,fit.scale,1e-10);
        assertEquals(0,fit.cos,1e-10);
        assertEquals(1,fit.sin,1e-10);
        assertEquals(.65,fit.x(.1,.1),1e-10);
        assertEquals(.15,fit.y(.1,.1),1e-10);
        assertEquals(0,fit.rmsError,1e-10);
    }
    @Test public void duplicateGuideLandmarksCannotFabricateTransform(){
        try{OmegaReferenceGeometry.align(Arrays.asList(
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.2,.2),1),
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.5,.7),1)));fail();
        }catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Degenerate"));}
    }
    @Test public void rejectsInvalidSourceGeometryAndPreservesCanvasBounds(){
        try{p(Double.NaN,.3);fail();}catch(IllegalArgumentException expected){}
        try{p(1.1,.3);fail();}catch(IllegalArgumentException expected){}
        OmegaReferenceGeometry.Fit fit=OmegaReferenceGeometry.align(Arrays.asList(
          new OmegaReferenceGeometry.Pair(p(.1,.1),p(.2,.2),1),
          new OmegaReferenceGeometry.Pair(p(.3,.3),p(.4,.4),1)));
        List<OmegaReferenceGeometry.Point> result=OmegaReferenceGeometry.transform(Arrays.asList(p(0,0),p(1,1)),fit);
        assertEquals(0,result.get(0).x,1e-8);
        assertEquals(1,result.get(1).x,1e-8);
    }
}