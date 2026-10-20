package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OMEGA Phase 2: transparent 2D landmark alignment. No hidden-view or AI claims. */
final class OmegaReferenceGeometry {
    private OmegaReferenceGeometry() {}
    static final int MAX_PAIRS=128;
    static final class Point {
        final double x,y;
        Point(double x,double y) {
            if (!valid(x) || !valid(y) || x<0 || y<0 || x>1 || y>1)
                throw new IllegalArgumentException("Landmark must be normalized and finite");
            this.x=x;this.y=y;
        }
    }
    static final class Pair {
        final Point source,target;
        final double weight;
        Pair(Point source,Point target,double weight) {
            if(source==null||target==null||!valid(weight)||weight<=0||weight>1000)
                throw new IllegalArgumentException("Invalid landmark pair");
            this.source=source;this.target=target;this.weight=weight;
        }
    }
    static final class Fit {
        final double scale,cos,sin,tx,ty,rmsError;
        final int landmarkCount;
        Fit(double scale,double cos,double sin,double tx,double ty,double rmsError,int count) {
            this.scale=scale;this.cos=cos;this.sin=sin;this.tx=tx;this.ty=ty;
            this.rmsError=rmsError;this.landmarkCount=count;
        }
        double x(double x,double y){return scale*(cos*x-sin*y)+tx;}
        double y(double x,double y){return scale*(sin*x+cos*y)+ty;}
    }
    static Fit align(List<Pair> pairs) {
        if(pairs==null||pairs.size()<2||pairs.size()>MAX_PAIRS)
            throw new IllegalArgumentException("Require 2..128 corresponding landmarks");
        double sum=0,sx=0,sy=0,tx=0,ty=0;
        for(Pair p:pairs){
            if(p==null)throw new IllegalArgumentException("Null landmark");
            sum+=p.weight;
            sx+=p.source.x*p.weight;sy+=p.source.y*p.weight;
            tx+=p.target.x*p.weight;ty+=p.target.y*p.weight;
        }
        sx/=sum;sy/=sum;tx/=sum;ty/=sum;
        double a=0,b=0,variance=0;
        for(Pair p:pairs){
            double x=p.source.x-sx,y=p.source.y-sy,u=p.target.x-tx,v=p.target.y-ty;
            a+=p.weight*(x*u+y*v);
            b+=p.weight*(x*v-y*u);
            variance+=p.weight*(x*x+y*y);
        }
        if(variance<1e-12 || Math.hypot(a,b)<1e-12)
            throw new IllegalArgumentException("Degenerate landmark alignment");
        double norm=Math.hypot(a,b),scale=norm/variance,c=a/norm,s=b/norm;
        if(!valid(scale)||scale<.001||scale>1000)
            throw new IllegalArgumentException("Unsafe guide scale");
        Fit partial=new Fit(scale,c,s,tx-scale*(c*sx-s*sy),ty-scale*(s*sx+c*sy),0,pairs.size());
        double error=0;
        for(Pair p:pairs){
            double dx=partial.x(p.source.x,p.source.y)-p.target.x;
            double dy=partial.y(p.source.x,p.source.y)-p.target.y;
            error+=p.weight*(dx*dx+dy*dy);
        }
        return new Fit(scale,c,s,partial.tx,partial.ty,Math.sqrt(error/sum),pairs.size());
    }
    static List<Point> transform(List<Point> points,Fit fit) {
        if(points==null||fit==null||points.size()>2048)
            throw new IllegalArgumentException("Guide transform bounds exceeded");
        List<Point> result=new ArrayList<>(points.size());
        for(Point p:points){
            if(p==null)throw new IllegalArgumentException("Null guide point");
            // Output is intentionally allowed outside canvas for preview clipping.
            result.add(new Point(clamp(fit.x(p.x,p.y)),clamp(fit.y(p.x,p.y))));
        }
        return Collections.unmodifiableList(result);
    }
    private static double clamp(double x){return Math.min(1,Math.max(0,x));}
    private static boolean valid(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}
}