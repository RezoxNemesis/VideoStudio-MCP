package com.rezoxnemesis.videostudio;

import android.graphics.Path;
import android.graphics.PathMeasure;
import org.json.JSONArray;
import java.io.InterruptedIOException;
import java.util.List;

/** Native fitted centerlines retain ordinary pressure-brush rendering and selection. */
final class SketchFittedBrush {
    static final int MAX_POINTS=8192;
    static final float SPACING_PX=.5f;
    private SketchFittedBrush(){}

    static void active()throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Fitted brush cancelled");
    }
    static JSONArray points(OmegaBezierFitter.Contour contour,List<OmegaBezierFitter.Point> source,
                            int width,int height)throws Exception {
        active();
        if(contour.closed||source==null||source.size()<2||source.size()>OmegaBezierFitter.MAX_SAMPLES)
            throw SketchDocument.bad("Pressure-brush fitting requires a bounded open authored stroke");
        double[] lengths=new double[source.size()];
        for(int i=1;i<source.size();i++){
            if((i&127)==0)active();
            lengths[i]=lengths[i-1]+Math.hypot((source.get(i).x-source.get(i-1).x)*width,
                    (source.get(i).y-source.get(i-1).y)*height);
        }
        double sourceLength=lengths[lengths.length-1];
        if(!Double.isFinite(sourceLength)||sourceLength<=0)throw SketchDocument.bad("Authored brush length is invalid");
        Path path=new Path();List<OmegaBezierFitter.Cubic> curves=contour.cubics();
        OmegaBezierFitter.Point first=curves.get(0).from,last=curves.get(curves.size()-1).to;
        path.moveTo((float)(first.x*width),(float)(first.y*height));
        for(OmegaBezierFitter.Cubic curve:curves){
            active();path.cubicTo((float)(curve.c1.x*width),(float)(curve.c1.y*height),
                    (float)(curve.c2.x*width),(float)(curve.c2.y*height),
                    (float)(curve.to.x*width),(float)(curve.to.y*height));
        }
        PathMeasure measure=new PathMeasure(path,false);double length=measure.getLength();
        if(!Double.isFinite(length)||length<=0)throw SketchDocument.bad("Fitted pressure brush has no finite native length");
        if(length>(MAX_POINTS-1)*SPACING_PX)throw budget();
        int steps=Math.max(1,(int)Math.ceil(length/SPACING_PX));
        Sampler sampler=new Sampler(measure,source,lengths,sourceLength,length,width,height,first,last);
        // Keep every pressure knot, including stationary samples, as well as geometry samples.
        // Geometric simplification must not erase a pressure peak on a straight centerline.
        int uniform=0,knot=0;
        while(uniform<=steps||knot<source.size()){
            active();
            double uniformAt=uniform<=steps?length*(uniform/(double)steps):Double.POSITIVE_INFINITY;
            double knotAt=knot<source.size()?length*(lengths[knot]/sourceLength):Double.POSITIVE_INFINITY;
            double at,pressure;
            if(knotAt<=uniformAt){
                at=knotAt;pressure=source.get(knot).pressure;knot++;
                if(uniformAt==at)uniform++;
            }else{at=uniformAt;uniform++;pressure=sampler.pressure(at);}
            sampler.append(at,pressure,0);
        }
        active();return sampler.output;
    }
    private static final class Sampler {
        final PathMeasure measure;
        final List<OmegaBezierFitter.Point> source;
        final double[] lengths;
        final double sourceLength,length;
        final int width,height;
        final OmegaBezierFitter.Point first,last;
        final JSONArray output=new JSONArray();
        final float[] position=new float[2];
        double lastAt,lastX,lastY;
        Sampler(PathMeasure measure,List<OmegaBezierFitter.Point> source,double[] lengths,
                double sourceLength,double length,int width,int height,
                OmegaBezierFitter.Point first,OmegaBezierFitter.Point last){
            this.measure=measure;this.source=source;this.lengths=lengths;
            this.sourceLength=sourceLength;this.length=length;this.width=width;this.height=height;
            this.first=first;this.last=last;
        }
        double pressure(double at){
            if(at<=0)return source.get(0).pressure;
            if(at>=length)return source.get(source.size()-1).pressure;
            double distance=at/length*sourceLength;int low=0,high=source.size()-1;
            while(low<high){int mid=(low+high+1)/2;if(lengths[mid]<=distance)low=mid;else high=mid-1;}
            if(low==source.size()-1)return source.get(low).pressure;
            double span=lengths[low+1]-lengths[low];
            double fraction=Math.max(0,Math.min(1,(distance-lengths[low])/span));
            return source.get(low).pressure+(source.get(low+1).pressure-source.get(low).pressure)*fraction;
        }
        void append(double at,double pressure,int depth)throws Exception {
            if((output.length()&127)==0)active();
            double x,y;
            if(at==0){x=first.x;y=first.y;}
            else if(at>=length){x=last.x;y=last.y;}
            else{
                if(!measure.getPosTan((float)at,position,null))throw SketchDocument.bad("Native fitted brush sampling failed");
                x=position[0]/(double)width;y=position[1]/(double)height;
                // Fitter controls stay inside the canvas; only native float boundary noise is rounded.
                if(x< -1e-6||x>1+1e-6||y< -1e-6||y>1+1e-6)
                    throw SketchDocument.bad("Fitted brush escaped its authored canvas");
                x=Math.max(0,Math.min(1,x));y=Math.max(0,Math.min(1,y));
            }
            // PathMeasure's arc-length table is approximate. Check actual source-pixel spacing.
            if(output.length()>0&&Math.hypot((x-lastX)*width,(y-lastY)*height)>SPACING_PX+1e-7){
                if(depth>=24||at<=lastAt+1e-10)throw SketchDocument.bad("Native fitted brush spacing could not be bounded");
                double midpoint=(lastAt+at)/2;append(midpoint,pressure(midpoint),depth+1);
                append(at,pressure,depth+1);return;
            }
            if(output.length()>=MAX_POINTS)throw budget();
            output.put(new JSONArray().put(x).put(y).put(pressure));lastAt=at;lastX=x;lastY=y;
        }
    }
    private static IllegalArgumentException budget(){
        return SketchDocument.bad("Fitted pressure brush exceeds8192 retained points; split the authored stroke");
    }
}
