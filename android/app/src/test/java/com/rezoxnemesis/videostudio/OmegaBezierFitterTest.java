package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaBezierFitterTest {
    private static OmegaBezierFitter.Point p(double x,double y){return new OmegaBezierFitter.Point(x,y);}
    private static OmegaBezierFitter.Point s(double x,double y,double pressure,boolean corner){
        return new OmegaBezierFitter.Point(x,y,pressure,corner);
    }
    private static void rejects(Runnable action){try{action.run();fail("Expected rejection");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    private static void same(OmegaBezierFitter.Point a,OmegaBezierFitter.Point b,double tolerance){
        assertEquals(a.x,b.x,tolerance);assertEquals(a.y,b.y,tolerance);
    }

    @Test public void originalFitApiKeepsEditableSmoothCubicControls(){
        List<OmegaBezierFitter.Cubic> c=OmegaBezierFitter.fit(Arrays.asList(p(.1,.5),p(.3,.2),p(.6,.25),p(.8,.6)),false,1);
        assertEquals(3,c.size());assertEquals(.1,c.get(0).from.x,0);assertEquals(.8,c.get(2).to.x,0);
        assertTrue(c.get(1).c1.x>.3);assertTrue(c.get(1).c2.x<.6);
        same(c.get(0).to,c.get(1).from,0);
        // Equal incoming/outgoing derivatives across every smooth join (C1).
        for(int i=0;i<c.size()-1;i++){
            assertEquals(c.get(i).to.x-c.get(i).c2.x,c.get(i+1).c1.x-c.get(i+1).from.x,1e-14);
            assertEquals(c.get(i).to.y-c.get(i).c2.y,c.get(i+1).c1.y-c.get(i+1).from.y,1e-14);
        }
    }

    @Test public void closedHandSilhouetteRetainsAllAnatomicalAnchors(){
        List<OmegaBezierFitter.Point> outline=Arrays.asList(
          p(.20,.70),p(.18,.56),p(.19,.40),p(.23,.31),p(.27,.42),
          p(.30,.18),p(.34,.17),p(.37,.42),p(.40,.12),p(.44,.12),p(.46,.42),
          p(.51,.20),p(.55,.21),p(.54,.48),p(.60,.34),p(.64,.36),p(.57,.66),p(.49,.78),p(.30,.81));
        OmegaBezierFitter.Contour c=OmegaBezierFitter.construct(outline,true,1);
        assertEquals(outline.size(),c.anchors.size());
        assertEquals(outline.size(),c.cubics().size());
        assertEquals(outline.size()+2,OmegaBezierFitter.nativeCommands(c).size());
        assertEquals("Z",OmegaBezierFitter.nativeCommands(c).get(outline.size()+1).kind);
        same(c.cubics().get(0).from,c.cubics().get(outline.size()-1).to,0);
        for(OmegaBezierFitter.Anchor a:c.anchors){assertNotNull(a.in);assertNotNull(a.out);}
    }

    @Test public void edgesAndCornersNeverExceedCanvasOrSilentlyMoveAnchors(){
        List<OmegaBezierFitter.Point> points=Arrays.asList(p(0,.1),p(.1,.8),p(.9,.2),p(1,.9));
        OmegaBezierFitter.Contour c=OmegaBezierFitter.construct(points,false,1);
        for(int i=0;i<points.size();i++)same(points.get(i),c.anchors.get(i).point,0);
        for(OmegaBezierFitter.Cubic cubic:c.cubics())for(OmegaBezierFitter.Point point:Arrays.asList(cubic.from,cubic.c1,cubic.c2,cubic.to)){
            assertTrue(point.x>=0&&point.x<=1&&point.y>=0&&point.y<=1);
        }
        OmegaBezierFitter.Contour cusp=OmegaBezierFitter.construct(Arrays.asList(p(.2,.2),s(.4,.2,.7,true),p(.5,.6)),false,1);
        same(cusp.anchors.get(1).point,cusp.anchors.get(1).in,0);
        same(cusp.anchors.get(1).point,cusp.anchors.get(1).out,0);
    }

    @Test public void movingAnAnchorPreservesItsHandlesAndOriginalSnapshot(){
        OmegaBezierFitter.Contour original=OmegaBezierFitter.construct(Arrays.asList(p(.1,.3),p(.4,.6),p(.7,.4)),false,1);
        OmegaBezierFitter.Contour edited=OmegaBezierFitter.moveAnchor(original,1,s(.42,.61,.55,false));
        assertEquals(.4,original.anchors.get(1).point.x,0);
        assertEquals(.42,edited.anchors.get(1).point.x,0);
        assertEquals(.55,edited.anchors.get(1).point.pressure,0);
        assertEquals(original.anchors.get(1).out.x+.02,edited.anchors.get(1).out.x,1e-14);
        rejects(()->OmegaBezierFitter.moveAnchor(original,1,p(1,1)));
        rejects(()->OmegaBezierFitter.moveAnchor(original,99,p(.2,.2)));
    }

    @Test public void independentVsSmoothHandlesAreExplicit(){
        OmegaBezierFitter.Contour original=OmegaBezierFitter.construct(Arrays.asList(p(.2,.3),p(.45,.4),p(.75,.6)),false,.8);
        OmegaBezierFitter.Contour smooth=OmegaBezierFitter.moveHandle(original,1,true,p(.51,.48),true);
        assertEquals(2*.45-.51,smooth.anchors.get(1).in.x,1e-14);
        assertEquals(2*.4-.48,smooth.anchors.get(1).in.y,1e-14);
        OmegaBezierFitter.Contour asymmetric=OmegaBezierFitter.moveHandle(original,1,true,p(.51,.48),false);
        same(original.anchors.get(1).in,asymmetric.anchors.get(1).in,0);
        rejects(()->OmegaBezierFitter.moveHandle(original,0,false,p(.2,.2),false));
        rejects(()->OmegaBezierFitter.moveHandle(original,1,true,p(.99,.9),true));
    }

    @Test public void splittingAnyCubicPreservesShapeExactly(){
        OmegaBezierFitter.Contour base=OmegaBezierFitter.construct(Arrays.asList(p(.2,.3),p(.4,.1),p(.7,.6)),false,1);
        OmegaBezierFitter.Cubic old=base.cubics().get(0);
        double split=.42;
        OmegaBezierFitter.Contour newShape=OmegaBezierFitter.split(base,0,split);
        assertEquals(4,newShape.anchors.size());
        for(int i=0;i<=60;i++){
            double t=i/60d;
            OmegaBezierFitter.Point expected=old.at(t);
            OmegaBezierFitter.Point actual=t<=split?newShape.cubics().get(0).at(t/split):newShape.cubics().get(1).at((t-split)/(1-split));
            same(expected,actual,1e-12);
        }
        assertEquals(3,base.anchors.size());
        rejects(()->OmegaBezierFitter.split(base,2,.5));
        rejects(()->OmegaBezierFitter.split(base,0,0));
    }

    @Test public void closedLastSegmentMayBeSplitWithoutLosingClosure(){
        OmegaBezierFitter.Contour base=OmegaBezierFitter.construct(Arrays.asList(p(.2,.2),p(.7,.2),p(.8,.75),p(.2,.8)),true,.8);
        OmegaBezierFitter.Cubic old=base.cubics().get(3);
        OmegaBezierFitter.Contour next=OmegaBezierFitter.split(base,3,.5);
        assertEquals(5,next.anchors.size());
        same(old.at(.25),next.cubics().get(3).at(.5),1e-12);
        same(old.at(.75),next.cubics().get(4).at(.5),1e-12);
        same(next.cubics().get(4).to,next.cubics().get(0).from,0);
    }

    @Test public void stylusCurveSimplifiesButStaysWithinMeasuredTolerance(){
        List<OmegaBezierFitter.Point> source=new ArrayList<>();
        for(int i=0;i<160;i++){
            double t=i/159d;
            source.add(s(.15+.7*t,.5-.2*Math.sin(2*Math.PI*t),.2+.8*t,false));
        }
        OmegaBezierFitter.StrokeFit fit=OmegaBezierFitter.fitStroke(source,1280,720,2,false,.95);
        assertTrue(fit.contour.anchors.size()<source.size());
        assertTrue(fit.contour.anchors.size()>=4);
        assertTrue(fit.maxDeviationPx<=2.000001);
        assertEquals(160,fit.inputSamples);
        assertEquals(.2,fit.contour.anchors.get(0).point.pressure,0);
        assertEquals(1,fit.contour.anchors.get(fit.contour.anchors.size()-1).point.pressure,1e-12);
    }

    @Test public void closedEllipseAllowsTouchToReturnToStart(){
        List<OmegaBezierFitter.Point> ellipse=new ArrayList<>();
        for(int i=0;i<100;i++){
            double angle=Math.PI*2*i/100;
            ellipse.add(p(.5+.23*Math.cos(angle),.5+.3*Math.sin(angle)));
        }
        ellipse.add(ellipse.get(0));
        OmegaBezierFitter.StrokeFit fit=OmegaBezierFitter.fitStroke(ellipse,720,1280,2,true,1);
        assertTrue(fit.contour.closed);
        assertTrue(fit.maxDeviationPx<=2.000001);
        assertTrue(fit.contour.anchors.size()>=6);
        assertEquals("Z",OmegaBezierFitter.nativeCommands(fit.contour).get(fit.contour.anchors.size()+1).kind);
    }

    @Test public void manualCornerAndPressureAreNotSmoothedAway(){
        List<OmegaBezierFitter.Point> samples=Arrays.asList(
            s(.2,.2,.3,false),s(.3,.2,.5,false),s(.4,.2,.6,true),
            s(.4,.3,.7,false),s(.4,.4,.8,false));
        OmegaBezierFitter.StrokeFit result=OmegaBezierFitter.fitStroke(samples,720,720,.5,false,.9);
        OmegaBezierFitter.Anchor corner=null;
        for(OmegaBezierFitter.Anchor a:result.contour.anchors)if(a.point.corner)corner=a;
        assertNotNull(corner);
        same(corner.point,corner.in,0);same(corner.point,corner.out,0);
        assertEquals(.6,corner.point.pressure,0);
    }

    @Test public void nativeCubicCommandsMatchSketchDocumentGeometryContract(){
        OmegaBezierFitter.Contour contour=OmegaBezierFitter.construct(Arrays.asList(p(.2,.3),p(.5,.1),p(.7,.8)),false,.9);
        List<OmegaBezierFitter.Command> out=OmegaBezierFitter.nativeCommands(contour);
        assertEquals(3,out.size());assertEquals("M",out.get(0).kind);
        assertEquals(2,out.get(0).values().length);
        assertEquals("C",out.get(1).kind);
        assertEquals(6,out.get(1).values().length);
        assertEquals("C",out.get(2).kind);
        double[] mutated=out.get(1).values();mutated[0]=999;
        assertTrue(out.get(1).values()[0]<1);
        try{out.add(new OmegaBezierFitter.Command("Z"));fail();}catch(UnsupportedOperationException expected){}
    }

    @Test public void pressureCreatesAnEditableFilledOutlineWithRoundCaps(){
        OmegaBezierFitter.Contour c=OmegaBezierFitter.construct(Arrays.asList(
           s(.20,.5,.3,false),s(.40,.35,.8,false),s(.72,.46,1,false)),false,.8);
        List<OmegaBezierFitter.Command> commands=OmegaBezierFitter.pressureOutline(c,.025,1280,720);
        assertEquals("M",commands.get(0).kind);
        assertEquals("Z",commands.get(commands.size()-1).kind);
        assertTrue(commands.size()>10&&commands.size()<=OmegaBezierFitter.MAX_COMMANDS);
        for(OmegaBezierFitter.Command command:commands)for(double v:command.values())assertTrue(Double.isFinite(v)&&v>=0&&v<=1);
        assertTrue(commands.get(0).values()[1]>c.anchors.get(0).point.y);
        rejects(()->OmegaBezierFitter.pressureOutline(c,.201,1280,720));
        rejects(()->OmegaBezierFitter.pressureOutline(c,.025,721,720));
        rejects(()->OmegaBezierFitter.pressureOutline(OmegaBezierFitter.construct(Arrays.asList(p(.1,.1),p(.4,.1),p(.3,.4)),true,.9),.025,1280,720));
    }

    @Test public void pressureOutlineRejectsBoundaryClippingRatherThanChangingDrawing(){
        OmegaBezierFitter.Contour nearEdge=OmegaBezierFitter.construct(Arrays.asList(p(.2,.0001),p(.5,.0001),p(.8,.0001)),false,1);
        rejects(()->OmegaBezierFitter.pressureOutline(nearEdge,.02,720,720));
    }

    @Test public void invalidInputFailsClosedWithoutNaNOrSilentBoxFallback(){
        rejects(()->p(Double.NaN,.1));rejects(()->p(Double.POSITIVE_INFINITY,.2));rejects(()->s(.4,.3,1.01,false));
        rejects(()->OmegaBezierFitter.fit(Arrays.asList(p(.2,.2),p(.2,.2)),false,1));
        rejects(()->OmegaBezierFitter.fit(Arrays.asList(p(.1,.1),p(.6,.6)),true,1));
        rejects(()->OmegaBezierFitter.fit(Arrays.asList(p(.1,.1),p(.9,.9)),false,Double.NaN));
        rejects(()->OmegaBezierFitter.fit(Arrays.asList(p(.1,.1),p(.9,.9)),false,1.1));
        rejects(()->OmegaBezierFitter.fitStroke(Arrays.asList(p(.1,.1),p(.3,.3)),720,720,0,false,1));
        rejects(()->OmegaBezierFitter.fitStroke(Arrays.asList(p(.1,.1),p(.3,.3)),720,720,2,false,-1));
        rejects(()->OmegaBezierFitter.fitStroke(Arrays.asList(p(.1,.1),p(.3,.3)),200,720,2,false,1));
        List<OmegaBezierFitter.Point> overflow=new ArrayList<>();for(int i=0;i<4097;i++)overflow.add(p((i+1)/4098d,.5));
        rejects(()->OmegaBezierFitter.fitStroke(overflow,720,720,2,false,1));
        List<OmegaBezierFitter.Point> tooMany=new ArrayList<>();for(int i=0;i<513;i++)tooMany.add(p(i/512d,.5));
        rejects(()->OmegaBezierFitter.fit(tooMany,false,1));
    }

    @Test public void deterministicFuzzProducesFiniteNormalizedAndRepeatableNativePaths(){
        Random rng=new Random(20261020);
        for(int run=0;run<100;run++){
            List<OmegaBezierFitter.Point> anchors=new ArrayList<>();
            double old=-1;
            for(int i=0;i<32;i++){double x=.05+i*.025,y=.2+.6*rng.nextDouble();assertTrue(x>old);old=x;anchors.add(p(x,y));}
            OmegaBezierFitter.Contour contour=OmegaBezierFitter.construct(anchors,false,.88);
            List<OmegaBezierFitter.Command> one=OmegaBezierFitter.nativeCommands(contour),two=OmegaBezierFitter.nativeCommands(OmegaBezierFitter.construct(anchors,false,.88));
            assertEquals(one.size(),two.size());
            for(int i=0;i<one.size();i++)assertTrue(Arrays.equals(one.get(i).values(),two.get(i).values()));
        }
    }
}
