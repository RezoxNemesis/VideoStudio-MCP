package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OMEGA Phase 3's pure-Java, source-preserving precision contour engine.
 * Coordinates and native path commands use normalized sketch space. No raster
 * substitution, reference inference, project mutation, or Android side effects.
 */
final class OmegaBezierFitter {
    static final int MAX_ANCHORS = 512;
    static final int MAX_SAMPLES = 4096;
    static final int MAX_COMMANDS = 4096; // SketchDocument.validateCommands
    private static final double DUPLICATE = 1e-10;
    private OmegaBezierFitter() {}

    static final class Point {
        final double x, y, pressure;
        final boolean corner;
        Point(double x, double y) { this(x, y, 1, false); }
        Point(double x, double y, double pressure, boolean corner) {
            if (!inside(x) || !inside(y) || !inside(pressure))
                throw new IllegalArgumentException("Point/pressure must be finite in [0,1]");
            this.x = x; this.y = y; this.pressure = pressure; this.corner = corner;
        }
    }

    static final class Cubic {
        final Point from, c1, c2, to;
        Cubic(Point from, Point c1, Point c2, Point to) {
            this.from = from; this.c1 = c1; this.c2 = c2; this.to = to;
        }
        Point at(double t) {
            if (!Double.isFinite(t) || t < 0 || t > 1)
                throw new IllegalArgumentException("Curve parameter must be in [0,1]");
            double u = 1 - t, a = u*u*u, b = 3*u*u*t, c = 3*u*t*t, d = t*t*t;
            Point position = rounded(a*from.x+b*c1.x+c*c2.x+d*to.x,
                    a*from.y+b*c1.y+c*c2.y+d*to.y);
            return new Point(position.x,position.y,
                    from.pressure*(1-t)+to.pressure*t,false);
        }
    }

    /** Explicit anchors and user-adjustable incoming/outgoing handles. */
    static final class Anchor {
        final Point point, in, out;
        Anchor(Point point, Point in, Point out) {
            if (point == null || in == null || out == null)
                throw new IllegalArgumentException("Missing anchor or handle");
            this.point = point; this.in = in; this.out = out;
        }
    }

    static final class Contour {
        final List<Anchor> anchors;
        final boolean closed;
        Contour(List<Anchor> anchors, boolean closed) {
            if (anchors == null || anchors.size() < (closed ? 3 : 2) || anchors.size() > MAX_ANCHORS)
                throw new IllegalArgumentException("Contour requires bounded anchors");
            List<Anchor> safe = new ArrayList<>(anchors);
            for (int i = 0; i < safe.size(); i++) {
                Anchor a = safe.get(i);
                if (a == null) throw new IllegalArgumentException("Null anchor");
                if (i > 0 && distance(a.point, safe.get(i-1).point) < DUPLICATE)
                    throw new IllegalArgumentException("Duplicate adjacent anchors");
            }
            if (closed && distance(safe.get(0).point, safe.get(safe.size()-1).point) < DUPLICATE)
                throw new IllegalArgumentException("Closed contour must not repeat first anchor");
            this.anchors = Collections.unmodifiableList(safe);
            this.closed = closed;
        }
        List<Cubic> cubics() {
            int count = closed ? anchors.size() : anchors.size() - 1;
            List<Cubic> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Anchor a = anchors.get(i), b = anchors.get((i+1)%anchors.size());
                result.add(new Cubic(a.point, a.out, b.in, b.point));
            }
            return Collections.unmodifiableList(result);
        }
    }

    /** The existing API remains stable; endpoints, corner flags and pressure remain authored. */
    static List<Cubic> fit(List<Point> source, boolean closed, double tension) {
        return construct(source, closed, tension).cubics();
    }

    static Contour construct(List<Point> source, boolean closed, double tension) {
        if (source == null || source.size() < (closed ? 3 : 2) || source.size() > MAX_ANCHORS ||
                !Double.isFinite(tension) || tension < 0 || tension > 1)
            throw new IllegalArgumentException("Contour anchor count/tension out of bounds");
        int n = source.size();
        for (int i = 0; i < n; i++) {
            if (source.get(i) == null) throw new IllegalArgumentException("Null anchor");
            if (i > 0 && distance(source.get(i), source.get(i-1)) < DUPLICATE)
                throw new IllegalArgumentException("Duplicate adjacent anchors");
        }
        if (closed && distance(source.get(0), source.get(n-1)) < DUPLICATE)
            throw new IllegalArgumentException("Closed contour must not repeat first anchor");
        List<Anchor> anchors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Point p = source.get(i);
            if (p.corner || tension == 0) {
                anchors.add(new Anchor(p, new Point(p.x,p.y), new Point(p.x,p.y)));
                continue;
            }
            Point prev = source.get(closed ? (i+n-1)%n : Math.max(0,i-1));
            Point next = source.get(closed ? (i+1)%n : Math.min(n-1,i+1));
            double dx = (next.x-prev.x)*tension/6, dy = (next.y-prev.y)*tension/6;
            boolean incoming = closed || i > 0, outgoing = closed || i < n-1;
            // One common per-anchor tangent scale preserves exact C1 at smooth joins.
            double scale = 1;
            if (incoming) scale = Math.min(scale, room(p.x,-dx));
            if (incoming) scale = Math.min(scale, room(p.y,-dy));
            if (outgoing) scale = Math.min(scale, room(p.x,dx));
            if (outgoing) scale = Math.min(scale, room(p.y,dy));
            // Leave one ULP-scale margin at a constrained boundary, preserving
            // the shared tangent scale rather than clamping one handle only.
            if (scale < 1) scale *= (1 - 1e-12);
            dx *= scale; dy *= scale;
            anchors.add(new Anchor(p, incoming ? rounded(p.x-dx,p.y-dy) : p,
                    outgoing ? rounded(p.x+dx,p.y+dy) : p));
        }
        return new Contour(anchors, closed);
    }

    /** Move an anchor and both attached handles as one editable unit; reject clipping. */
    static Contour moveAnchor(Contour contour, int index, Point replacement) {
        if (contour == null || replacement == null || index < 0 || index >= contour.anchors.size())
            throw new IllegalArgumentException("Unknown contour anchor");
        List<Anchor> copy = new ArrayList<>(contour.anchors);
        Anchor a = copy.get(index);
        double dx = replacement.x - a.point.x, dy = replacement.y - a.point.y;
        copy.set(index, new Anchor(replacement, translate(a.in,dx,dy), translate(a.out,dx,dy)));
        return new Contour(copy,contour.closed);
    }

    /** Smooth mode mirrors an edited handle; corner mode changes only the chosen side. */
    static Contour moveHandle(Contour contour, int index, boolean outgoing, Point handle, boolean smooth) {
        if (contour == null || handle == null || index < 0 || index >= contour.anchors.size())
            throw new IllegalArgumentException("Unknown contour handle");
        int n = contour.anchors.size();
        if (!contour.closed && ((index == 0 && !outgoing) || (index == n-1 && outgoing)))
            throw new IllegalArgumentException("Endpoint has no curve on that side");
        List<Anchor> copy = new ArrayList<>(contour.anchors);
        Anchor a = copy.get(index);
        Point mirror = smooth ? new Point(2*a.point.x-handle.x,2*a.point.y-handle.y) : null;
        copy.set(index, new Anchor(a.point,
                outgoing ? (smooth ? mirror : a.in) : handle,
                outgoing ? handle : (smooth ? mirror : a.out)));
        return new Contour(copy,contour.closed);
    }

    /** De Casteljau subdivision inserts a node without changing any position on the curve. */
    static Contour split(Contour contour, int segment, double t) {
        if (contour == null || segment < 0 || segment >= (contour.closed ? contour.anchors.size() : contour.anchors.size()-1)
                || !Double.isFinite(t) || t <= .001 || t >= .999 || contour.anchors.size() == MAX_ANCHORS)
            throw new IllegalArgumentException("Cannot subdivide this segment");
        List<Anchor> copy = new ArrayList<>(contour.anchors);
        int next = (segment+1)%copy.size();
        Anchor a = copy.get(segment), b = copy.get(next);
        Point p01 = mix(a.point,a.out,t), p12 = mix(a.out,b.in,t), p23 = mix(b.in,b.point,t);
        Point p012 = mix(p01,p12,t), p123 = mix(p12,p23,t), middle = mix(p012,p123,t);
        // Geometry of new control points is correct regardless of the pressure interpolation.
        Point positioned = new Point(middle.x,middle.y,a.point.pressure*(1-t)+b.point.pressure*t,false);
        copy.set(segment,new Anchor(a.point,a.in,p01));
        copy.set(next,new Anchor(b.point,p23,b.out));
        copy.add(segment+1,new Anchor(positioned,p012,p123));
        return new Contour(copy,contour.closed);
    }

    static final class Command {
        final String kind;
        private final double[] values;
        Command(String kind, double... values) {
            this.kind = kind; this.values = values.clone();
            for (double value : values) if (!inside(value))
                throw new IllegalArgumentException("Native path coordinate out of bounds");
            int expected = "M".equals(kind)||"L".equals(kind)?2:"C".equals(kind)?6:"Z".equals(kind)?0:-1;
            if (values.length != expected) throw new IllegalArgumentException("Unsupported native path command");
        }
        double[] values() { return values.clone(); }
    }

    /** Compatible with SketchDocument path commands: [M,x,y],[C,c1x,c1y,c2x,c2y,x,y],[Z]. */
    static List<Command> nativeCommands(Contour contour) {
        if (contour == null) throw new IllegalArgumentException("Missing contour");
        List<Command> result = new ArrayList<>();
        Point first = contour.anchors.get(0).point;
        result.add(new Command("M",first.x,first.y));
        for (Cubic c : contour.cubics())
            result.add(new Command("C",c.c1.x,c.c1.y,c.c2.x,c.c2.y,c.to.x,c.to.y));
        if (contour.closed) result.add(new Command("Z"));
        return Collections.unmodifiableList(result);
    }

    /** A bounded stylus-to-editable-contour fit, retaining source order and pressure. */
    static final class StrokeFit {
        final Contour contour;
        final double maxDeviationPx;
        final int inputSamples;
        StrokeFit(Contour contour, double maxDeviationPx, int inputSamples) {
            this.contour = contour; this.maxDeviationPx = maxDeviationPx; this.inputSamples = inputSamples;
        }
    }

    static StrokeFit fitStroke(List<Point> samples, int width, int height,
                               double tolerancePx, boolean closed, double tension) {
        dimensions(width,height);
        if (samples == null || samples.size() > MAX_SAMPLES ||
                !Double.isFinite(tolerancePx) || tolerancePx < .25 || tolerancePx > 12 ||
                !Double.isFinite(tension) || tension < 0 || tension > 1)
            throw new IllegalArgumentException("Invalid source stroke or pixel tolerance");
        List<Point> deduped = new ArrayList<>(samples.size()+1);
        for (Point p : samples) {
            if (p == null) throw new IllegalArgumentException("Null source point");
            if (!deduped.isEmpty() && pixelDistance(p,deduped.get(deduped.size()-1),width,height) < .05) {
                Point prior = deduped.get(deduped.size()-1);
                deduped.set(deduped.size()-1,new Point(p.x,p.y,p.pressure,p.corner||prior.corner));
            } else deduped.add(p);
        }
        if (closed && deduped.size() > 2 && pixelDistance(deduped.get(0),deduped.get(deduped.size()-1),width,height) < .05)
            deduped.remove(deduped.size()-1);
        int n = deduped.size();
        if (n < (closed?3:2)) throw new IllegalArgumentException("Not enough distinct source points");
        List<Point> extended = new ArrayList<>(deduped);
        if (closed) extended.add(deduped.get(0));
        boolean[] keep = new boolean[extended.size()];
        keep[0] = true; keep[extended.size()-1] = true;
        if (closed) {
            int pivot = -1; double farthest = 0;
            for (int i=1;i<n;i++) {
                double d = pixelDistance(deduped.get(0),deduped.get(i),width,height);
                if (d > farthest) { farthest=d; pivot=i; }
            }
            if (pivot < 1) throw new IllegalArgumentException("Degenerate closed source");
            keep[pivot] = true;
        }
        for (int i=0;i<n;i++) if (extended.get(i).corner) keep[i]=true;
        int beginning=0;
        for (int i=1;i<extended.size();i++) if (keep[i]) {
            simplify(extended,keep,beginning,i,tolerancePx*.6,width,height);
            beginning=i;
        }
        for (int attempt=0;attempt<=24;attempt++) {
            List<Integer> positions = positions(keep,closed);
            if (positions.size() > MAX_ANCHORS)
                throw new IllegalArgumentException("Stroke exceeds 512 editable anchors; split it into shorter strokes");
            List<Point> path = new ArrayList<>(positions.size());
            for (int index : positions) path.add(deduped.get(index));
            Contour contour = construct(path,closed,tension);
            int[] worstIndex = {-1};
            double deviation = worstDeviation(extended,positions,contour,width,height,worstIndex);
            if (deviation <= tolerancePx + 1e-8)
                return new StrokeFit(contour,deviation,samples.size());
            if (attempt == 24 || worstIndex[0] < 1 || worstIndex[0] >= extended.size()-1 || keep[worstIndex[0]])
                throw new IllegalArgumentException("Cannot preserve source stroke inside chosen pixel tolerance");
            keep[worstIndex[0]] = true;
        }
        throw new IllegalStateException("Unreachable fitting bound");
    }

    /** Open stroke pressure becomes a genuinely editable, filled native vector silhouette. */
    static List<Command> pressureOutline(Contour contour, double baseWidth,
                                         int width, int height) {
        dimensions(width,height);
        if (contour == null || contour.closed || !Double.isFinite(baseWidth) || baseWidth < .0005 || baseWidth > .2)
            throw new IllegalArgumentException("Pressure outline needs an open contour and native brush width");
        List<Cubic> curves = contour.cubics();
        List<Point> left = new ArrayList<>(), right = new ArrayList<>();
        double[] firstTangent = null, lastTangent = null;
        double radiusScale = baseWidth * Math.min(width,height)/2;
        for (int i=0;i<curves.size();i++) for (int step=0;step<=3;step++) {
            if (i>0 && step==0) continue;
            double t=step/3d; Cubic c=curves.get(i); Point center=c.at(t);
            double[] tangent = tangent(c,t,width,height);
            if (firstTangent==null) firstTangent=tangent;
            lastTangent=tangent;
            double radius=radiusScale*center.pressure;
            left.add(offset(center,-tangent[1]*radius,tangent[0]*radius,width,height));
            right.add(offset(center,tangent[1]*radius,-tangent[0]*radius,width,height));
        }
        List<Command> out = new ArrayList<>(left.size()*2+16);
        Point start = left.get(0); out.add(new Command("M",start.x,start.y));
        for (int i=1;i<left.size();i++) out.add(new Command("L",left.get(i).x,left.get(i).y));
        Point endCenter = curves.get(curves.size()-1).to;
        cap(out,endCenter,lastTangent,radiusScale*endCenter.pressure,width,height,true);
        for (int i=right.size()-2;i>=0;i--) out.add(new Command("L",right.get(i).x,right.get(i).y));
        Point startCenter = curves.get(0).from;
        cap(out,startCenter,firstTangent,radiusScale*startCenter.pressure,width,height,false);
        out.add(new Command("Z"));
        if (out.size()>MAX_COMMANDS) throw new IllegalArgumentException("Native command budget exceeded");
        return Collections.unmodifiableList(out);
    }

    private static void cap(List<Command> out,Point center,double[] tangent,double r,int width,int height,boolean end) {
        for (int i=1;i<=6;i++) {
            double angle=Math.PI*i/6, c=Math.cos(angle), s=Math.sin(angle);
            double nx=-tangent[1],ny=tangent[0];
            double dx=end?(c*nx+s*tangent[0])*r:(-c*nx-s*tangent[0])*r;
            double dy=end?(c*ny+s*tangent[1])*r:(-c*ny-s*tangent[1])*r;
            Point p=offset(center,dx,dy,width,height);
            out.add(new Command("L",p.x,p.y));
        }
    }

    private static double[] tangent(Cubic c,double t,int w,int h) {
        double u=1-t;
        double dx=(3*u*u*(c.c1.x-c.from.x)+6*u*t*(c.c2.x-c.c1.x)+3*t*t*(c.to.x-c.c2.x))*w;
        double dy=(3*u*u*(c.c1.y-c.from.y)+6*u*t*(c.c2.y-c.c1.y)+3*t*t*(c.to.y-c.c2.y))*h;
        double size=Math.hypot(dx,dy);
        if (size<1e-8) {
            double before=Math.max(0,t-.002),after=Math.min(1,t+.002);
            Point a=c.at(before),b=c.at(after);
            dx=(b.x-a.x)*w;dy=(b.y-a.y)*h;size=Math.hypot(dx,dy);
        }
        if (size<1e-8) {dx=(c.to.x-c.from.x)*w;dy=(c.to.y-c.from.y)*h;size=Math.hypot(dx,dy);}
        if (size<1e-8) throw new IllegalArgumentException("Cannot outline a stationary curve");
        return new double[]{dx/size,dy/size};
    }

    private static Point offset(Point center,double dx,double dy,int w,int h) {
        double x=center.x+dx/w,y=center.y+dy/h;
        if (!inside(x)||!inside(y)) throw new IllegalArgumentException("Pressure outline would leave canvas");
        return new Point(x,y);
    }

    private static double worstDeviation(List<Point> source,List<Integer> positions,Contour contour,
                                         int width,int height,int[] worstIndex) {
        List<Cubic> curves = contour.cubics();
        double worst = 0;
        for (int segment=0;segment<curves.size();segment++) {
            int start=positions.get(segment);
            int end=segment+1<positions.size()?positions.get(segment+1):source.size()-1;
            Cubic curve=curves.get(segment);
            for (int sample=start+1;sample<end;sample++) {
                Point last=curve.from;
                double best=Double.POSITIVE_INFINITY;
                for (int step=1;step<=16;step++) {
                    Point next=curve.at(step/16d);
                    // Sampled Bézier chord distance is only an approximation;
                    // native visual review is still required.
                    best=Math.min(best,segmentDistance(source.get(sample),last,next,width,height));
                    last=next;
                }
                if (best>worst) { worst=best; worstIndex[0]=sample; }
            }
        }
        return worst;
    }

    private static void simplify(List<Point> points,boolean[] keep,int start,int end,
                                 double threshold,int w,int h) {
        int[] stack=new int[points.size()*2],limit=new int[points.size()*2];
        int top=0;stack[top]=start;limit[top++]=end;
        while(top>0) {
            int a=stack[--top],b=limit[top];
            int far=-1;double max=threshold;
            for (int i=a+1;i<b;i++) {
                double d=segmentDistance(points.get(i),points.get(a),points.get(b),w,h);
                if (d>max) {max=d;far=i;}
            }
            if(far>=0) {
                keep[far]=true;
                stack[top]=a;limit[top++]=far;
                stack[top]=far;limit[top++]=b;
            }
        }
    }

    private static List<Integer> positions(boolean[] keep,boolean closed) {
        List<Integer> result=new ArrayList<>();
        for(int i=0;i<keep.length-(closed?1:0);i++) if(keep[i]) result.add(i);
        return result;
    }

    private static double segmentDistance(Point p,Point a,Point b,int w,int h) {
        double dx=(b.x-a.x)*w,dy=(b.y-a.y)*h;
        double t=(dx*dx+dy*dy)<1e-16?0:Math.max(0,Math.min(1,((p.x-a.x)*w*dx+(p.y-a.y)*h*dy)/(dx*dx+dy*dy)));
        return Math.hypot((p.x-a.x)*w-dx*t,(p.y-a.y)*h-dy*t);
    }
    private static Point translate(Point p,double dx,double dy) {return rounded(p.x+dx,p.y+dy);}
    private static Point mix(Point a,Point b,double t) {return rounded(a.x*(1-t)+b.x*t,a.y*(1-t)+b.y*t);}
    private static Point rounded(double x,double y) {return new Point(roundoff(x),roundoff(y));}
    private static double roundoff(double value) {
        if(value >= -1e-12 && value <= 1+1e-12) return Math.max(0,Math.min(1,value));
        throw new IllegalArgumentException("Curve geometry leaves canvas");
    }
    private static double distance(Point a,Point b) {return Math.hypot(a.x-b.x,a.y-b.y);}
    private static double pixelDistance(Point a,Point b,int w,int h) {return Math.hypot((a.x-b.x)*w,(a.y-b.y)*h);}
    private static double room(double p,double delta) {return delta>0?(1-p)/delta:delta<0?-p/delta:1;}
    private static boolean inside(double v) {return Double.isFinite(v)&&v>=0&&v<=1;}
    private static void dimensions(int w,int h) {
        if(w<256||w>2048||h<256||h>2048||(w&1)!=0||(h&1)!=0)
            throw new IllegalArgumentException("Use valid even SketchDocument pixel dimensions");
    }
}
