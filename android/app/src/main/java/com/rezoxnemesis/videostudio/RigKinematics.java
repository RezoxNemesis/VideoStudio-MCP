package com.rezoxnemesis.videostudio;

/**
 * Source-independent 2D/3D rig mathematics.
 *
 * Positions are expressed in one consistent world space. The z=0 plane
 * supports 2D rigs, while arbitrary z allows future skinned 3D meshes.
 * This is a pose/constraint solver, NOT an image-to-3D reconstruction model.
 */
public final class RigKinematics {
    private static final double EPS = 1e-8;
    private RigKinematics() {}

    public static final class Vec3 {
        public final double x, y, z;
        public Vec3(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Rig coordinates must be finite");
            this.x=x; this.y=y; this.z=z;
        }
        public Vec3 add(Vec3 b) { return new Vec3(x+b.x,y+b.y,z+b.z); }
        public Vec3 subtract(Vec3 b) { return new Vec3(x-b.x,y-b.y,z-b.z); }
        public Vec3 multiply(double amount) { return new Vec3(x*amount,y*amount,z*amount); }
        public double dot(Vec3 b) { return x*b.x+y*b.y+z*b.z; }
        public Vec3 cross(Vec3 b) {
            return new Vec3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);
        }
        public double length() { return Math.hypot(Math.hypot(x,y),z); }
        public Vec3 normalized() {
            double l=length(); return l==0?new Vec3(1,0,0):new Vec3(x/l,y/l,z/l);
        }
        public double distance(Vec3 b) { return subtract(b).length(); }
    }

    public static final class TwoBonePose {
        public final Vec3 root, joint, effector;
        public final boolean targetReachable;
        TwoBonePose(Vec3 r, Vec3 j, Vec3 e, boolean reachable) {
            root=r; joint=j; effector=e; targetReachable=reachable;
        }
    }

    /**
     * Analytical two-bone IK with a stable pole vector controlling the bend.
     * This also plants a foot: keep world-space target fixed while moving hip.
     */
    public static TwoBonePose solveTwoBone(Vec3 root, Vec3 target, Vec3 pole,
                                            double upper, double lower) {
        if (root==null||target==null||pole==null ||
                !Double.isFinite(upper)||!Double.isFinite(lower)||
                upper<=0||lower<=0||!Double.isFinite(upper+lower))throw new IllegalArgumentException("Invalid bone rig");
        Vec3 delta=target.subtract(root);
        double raw=delta.length();
        if(!Double.isFinite(raw))throw new IllegalArgumentException("Rig distance exceeds numerical bounds");
        double min=Math.abs(upper-lower),max=upper+lower;
        double tolerance=Math.ulp(Math.max(max,raw))*16;
        boolean reachable=raw>=min-tolerance&&raw<=max+tolerance;
        double reach=reachable?raw:clamp(raw,min,max);
        if(reach==0) {
            Vec3 bend=pole.subtract(root).normalized();
            return new TwoBonePose(root,root.add(bend.multiply(upper)),target,reachable);
        }
        Vec3 direction=raw==0?new Vec3(1,0,0):new Vec3(delta.x/raw,delta.y/raw,delta.z/raw);
        Vec3 poleVector=pole.subtract(root);
        Vec3 transverse=poleVector.subtract(direction.multiply(poleVector.dot(direction)));
        if (transverse.length()==0) {
            Vec3 axis=Math.abs(direction.z)<.8?new Vec3(0,0,1):new Vec3(0,1,0);
            transverse=direction.cross(axis);
        }
        transverse=transverse.normalized();
        double metric=Math.max(Math.max(upper,lower),reach);
        double u=upper/metric,l=lower/metric,r=reach/metric;
        double along=metric*(u*u-l*l+r*r)/(2*r);
        double radius=metric*Math.sqrt(Math.max(0,u*u-Math.pow(along/metric,2)));
        Vec3 elbow=root.add(direction.multiply(along)).add(transverse.multiply(radius));
        Vec3 effector=reachable?target:root.add(direction.multiply(reach));
        return new TwoBonePose(root,elbow,effector,reachable);
    }

    /** A planted foot has one fixed world anchor: the knee moves, not the foot. */
    public static TwoBonePose plantedLeg(Vec3 movingHip, Vec3 plantedFoot,
                                          Vec3 kneePole, double thigh, double shin) {
        return solveTwoBone(movingHip,plantedFoot,kneePole,thigh,shin);
    }

    /**
     * Closest separation of two finite weapon blade segments, including
     * intersecting blades, their handles and tips. No tip-only spark shortcuts.
     * Based on standard closest-segments clamping; handles degenerate segments.
     */
    public static double segmentDistance(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        return closestSegments(a,b,c,d).separation;
    }

    /** Closest physical segment points. Sampling time and actual collision evidence belong to the caller. */
    public static final class SegmentWitness {
        public final Vec3 pointA,pointB;
        public final double parameterA,parameterB,separation;
        SegmentWitness(Vec3 pointA,Vec3 pointB,double a,double b) {
            this.pointA=pointA;this.pointB=pointB;parameterA=a;parameterB=b;
            separation=pointA.distance(pointB);
        }
    }
    public static SegmentWitness closestSegments(Vec3 a,Vec3 b,Vec3 c,Vec3 d) {
        if (a==null||b==null||c==null||d==null)
            throw new IllegalArgumentException("Weapon endpoints are required");
        Vec3 u=b.subtract(a),v=d.subtract(c),w=a.subtract(c);
        double scale=Math.max(Math.max(u.length(),v.length()),w.length());
        if(scale==0)return new SegmentWitness(a,c,0,0);
        // Relative scaling keeps tiny distant actors out of a fixed world-size epsilon.
        Vec3 us=new Vec3(u.x/scale,u.y/scale,u.z/scale),vs=new Vec3(v.x/scale,v.y/scale,v.z/scale),
                ws=new Vec3(w.x/scale,w.y/scale,w.z/scale);
        double uu=us.dot(us),vv=vs.dot(vs),uv=us.dot(vs),uw=us.dot(ws),vw=vs.dot(ws),s,t;
        if(uu==0&&vv==0){s=0;t=0;}
        else if(uu==0){s=0;t=clamp(vw/vv,0,1);}
        else if(vv==0){s=clamp(-uw/uu,0,1);t=0;}
        else {
            double denominator=uu*vv-uv*uv;
            s=denominator>Math.ulp(uu*vv)*8?clamp((uv*vw-vv*uw)/denominator,0,1):0;
            t=(uv*s+vw)/vv;
            if(t<0){t=0;s=clamp(-uw/uu,0,1);}
            else if(t>1){t=1;s=clamp((uv-uw)/uu,0,1);}
        }
        return new SegmentWitness(a.add(u.multiply(s)),c.add(v.multiply(t)),s,t);
    }

    /**
     * Continuous cubic motion for animation keyframe positions. Temporal
     * derivatives are calculated from neighboring poses, not frozen at each
     * artwork anchor. Coordinates are bounded to avoid spline overshoot.
     */
    public static Vec3 samplePosition(Vec3 previous, Vec3 start, Vec3 end,
                                      Vec3 following, double beforeSeconds,
                                      double segmentSeconds, double afterSeconds,
                                      double u) {
        if(previous==null||start==null||end==null||following==null||
                !Double.isFinite(beforeSeconds)||beforeSeconds<=0||
                !Double.isFinite(segmentSeconds)||segmentSeconds<=0||
                !Double.isFinite(afterSeconds)||afterSeconds<=0||
                !Double.isFinite(u)||u<0||u>1)
            throw new IllegalArgumentException("Invalid animated pose timing");
        if(u==0)return start;
        if(u==1)return end;
        return new Vec3(
                hermite(previous.x,start.x,end.x,following.x,
                        beforeSeconds,segmentSeconds,afterSeconds,u),
                hermite(previous.y,start.y,end.y,following.y,
                        beforeSeconds,segmentSeconds,afterSeconds,u),
                hermite(previous.z,start.z,end.z,following.z,
                        beforeSeconds,segmentSeconds,afterSeconds,u));
    }

    private static double hermite(double previous,double start,double end,double after,
                                  double prevDt,double dt,double nextDt,double u) {
        double secant=(end-start)/dt;
        if(Math.abs(secant)<EPS)return start;
        double m0=(end-previous)/(prevDt+dt);
        double m1=(after-start)/(dt+nextDt);
        // Velocity reversal shouldn't pull joints past an authored endpoint.
        if(Math.signum(m0)!=Math.signum(secant))m0=0;
        if(Math.signum(m1)!=Math.signum(secant))m1=0;
        double limit=Math.abs(secant)*3;
        m0=clamp(m0,-limit,limit);
        m1=clamp(m1,-limit,limit);
        double u2=u*u,u3=u2*u;
        double value=(2*u3-3*u2+1)*start+(u3-2*u2+u)*dt*m0
                +(-2*u3+3*u2)*end+(u3-u2)*dt*m1;
        return clamp(value,Math.min(start,end),Math.max(start,end));
    }

    private static double clamp(double n,double lo,double hi) {
        return Math.max(lo,Math.min(hi,n));
    }
}
