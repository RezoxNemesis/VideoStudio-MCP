package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OMEGA Phase 3: bounded editable cubic Bézier construction from distinct
 * normalized contour anchors (Catmull-Rom conversion, not a raster paste).
 * Caller controls reference fidelity; results remain user-editable paths.
 */
final class OmegaBezierFitter {
    static final int MAX_ANCHORS=512;
    private OmegaBezierFitter(){}
    static final class Point{
        final double x,y;
        Point(double x,double y){
            if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)
                throw new IllegalArgumentException("Contour anchor must be normalized");
            this.x=x;this.y=y;
        }
    }
    static final class Cubic {
        final Point from,c1,c2,to;
        Cubic(Point from,Point c1,Point c2,Point to){
            this.from=from;this.c1=c1;this.c2=c2;this.to=to;
        }
    }
    static List<Cubic> fit(List<Point> source,boolean closed,double tension){
        if(source==null||source.size()<(closed?3:2)||source.size()>MAX_ANCHORS
            ||!Double.isFinite(tension)||tension<0||tension>1)
            throw new IllegalArgumentException("Contour anchor count/tension out of bounds");
        List<Point> points=new ArrayList<>(source.size());
        for(Point p:source) {
            if(p==null)throw new IllegalArgumentException("Null anchor");
            if(!points.isEmpty()&&distance(p,points.get(points.size()-1))<1e-12)
                throw new IllegalArgumentException("Duplicate adjacent anchors");
            points.add(p);
        }
        int n=points.size();
        if(closed&&distance(points.get(0),points.get(n-1))<1e-12)
            throw new IllegalArgumentException("Closed contour must not repeat first anchor");
        List<Cubic> output=new ArrayList<>(closed?n:n-1);
        for(int i=0;i<(closed?n:n-1);i++){
            Point prev=points.get(closed?(i+n-1)%n:Math.max(0,i));
            Point a=points.get(i),b=points.get((i+1)%n);
            Point next=points.get(closed?(i+2)%n:Math.min(n-1,i+2));
            double weight=tension/6.0;
            Point c1=p(a.x+(b.x-prev.x)*weight,a.y+(b.y-prev.y)*weight);
            Point c2=p(b.x-(next.x-a.x)*weight,b.y-(next.y-a.y)*weight);
            output.add(new Cubic(a,c1,c2,b));
        }
        return Collections.unmodifiableList(output);
    }
    private static Point p(double x,double y){
        return new Point(Math.max(0,Math.min(1,x)),Math.max(0,Math.min(1,y)));
    }
    private static double distance(Point a,Point b){return Math.hypot(a.x-b.x,a.y-b.y);}
}