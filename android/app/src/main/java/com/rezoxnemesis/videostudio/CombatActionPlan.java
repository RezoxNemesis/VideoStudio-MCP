package com.rezoxnemesis.videostudio;

/**
 * A source-independent fight director. A small set of meaningful action beats
 * drives every animated timestamp; no hundreds of stills, zero slideshow cuts.
 *
 * Real animation frames still require actor rigs / skinned GLB geometry.
 * This is choreography timing and camera direction, not image synthesis.
 */
public final class CombatActionPlan {
    public enum Beat {
        GUARD, APPROACH, LEAP, STRIKE, CONTACT, COUNTER, KNOCKBACK, LAND
    }

    public static final int BEAT_COUNT=8;
    private static final double[] DEFAULT_TIMES={
        0,.12,.29,.41,.52,.60,.73,.89,1
    };
    // Standard CombatRigSolver input times, anchored to its physical
    // sword collision at input ~.53 and fall/recovery phases thereafter.
    private static final double[] MOTION_TIMES={
        0,.185,.405,.46,.53,.57,.705,.845,1
    };
    private static final double[] CAMERA_DISTANCE={
        5.6,5.35,5.12,4.76,4.38,4.52,5.17,5.48,5.7
    };
    private static final double[] CAMERA_ORBIT={
        -.035,-.025,-.04,.055,.085,.065,-.085,-.08,-.025
    };
    private static final Beat[] BEATS=Beat.values();
    private final double[] times;

    public CombatActionPlan() { this(DEFAULT_TIMES); }

    /**
     * All nine ordered normalized beat boundaries. A user or director can
     * change shot pacing WITHOUT authoring any extra images.
     */
    public CombatActionPlan(double[] boundaries) {
        if(boundaries==null||boundaries.length!=BEAT_COUNT+1)
            throw new IllegalArgumentException("Exactly nine action timestamps are required");
        times=boundaries.clone();
        for(int i=0;i<times.length;i++){
            if(!Double.isFinite(times[i])||times[i]<0||times[i]>1)
                throw new IllegalArgumentException("Action timestamps must be finite in 0..1");
        }
        if(Math.abs(times[0])>1e-6||Math.abs(times[times.length-1]-1)>1e-6)
            throw new IllegalArgumentException("Action starts at 0 and ends at 1");
        for(int i=1;i<times.length;i++){
            if(times[i]-times[i-1]<.035)
                throw new IllegalArgumentException("Action beats must be separated by at least 3.5%");
        }
    }

    public double[] boundaries(){return times.clone();}

    public static final class Frame {
        public final Beat beat;
        public final int beatIndex;
        public final double normalizedTime,seconds,solverSeconds;
        public final double beatStartSeconds,beatDurationSeconds,beatProgress;
        public final double cameraDistance,cameraOrbitRadians,cameraShake;
        public final double contactEnergy;
        public final boolean bladeCollisionWindow;
        Frame(Beat beat,int i,double t,double seconds,double solver,
              double beatStart,double beatDuration,double beatProgress,
              double cameraDistance,double yaw,double shake,double contactEnergy,
              boolean collision) {
            this.beat=beat;beatIndex=i;normalizedTime=t;this.seconds=seconds;
            solverSeconds=solver;beatStartSeconds=beatStart;
            beatDurationSeconds=beatDuration;this.beatProgress=beatProgress;
            this.cameraDistance=cameraDistance;cameraOrbitRadians=yaw;
            cameraShake=shake;this.contactEnergy=contactEnergy;
            bladeCollisionWindow=collision;
        }
    }

    public Frame at(double seconds,double durationSeconds) {
        if(!Double.isFinite(seconds)||!Double.isFinite(durationSeconds)||
                durationSeconds<=0)
            throw new IllegalArgumentException("Invalid animation clock");
        double t=clamp(seconds/durationSeconds,0,1);
        int beat=segment(t,times);
        double start=times[beat],end=times[beat+1];
        double portion=clamp((t-start)/(end-start),0,1);
        // Monotonic C1 time remapping: camera and character motion do not
        // arbitrarily freeze at every waypoint.
        double motion=pchip(times,MOTION_TIMES,t);
        double distance=pchip(times,CAMERA_DISTANCE,t);
        double yaw=pchip(times,CAMERA_ORBIT,t);
        double contact=Math.exp(-Math.pow((t-times[4])/.033,2));
        double shake=Math.sin(t*147.0)*contact*.033;
        boolean contactWindow=Math.abs(t-times[4])<=.028;
        return new Frame(BEATS[beat],beat,t,t*durationSeconds,
                motion*durationSeconds,start*durationSeconds,
                (end-start)*durationSeconds,portion,
                distance,yaw,shake,contact,contactWindow);
    }

    /** Metadata-only event markers; actual sounds are generated/attached later. */
    public String cueAt(double seconds,double durationSeconds) {
        Frame f=at(seconds,durationSeconds);
        if(f.bladeCollisionWindow)return "swords_clash";
        switch(f.beat){
            case APPROACH: return "footsteps_and_rush";
            case LEAP: return "jump_and_wind";
            case STRIKE: return "blade_whoosh";
            case COUNTER: return "counter_whoosh";
            case KNOCKBACK: return "impact_and_debris";
            case LAND: return "landing_and_recovery";
            default: return "tense_ambience";
        }
    }

    public static double sampleMonotone(double[] x,double[] y,double t) {
        if(x==null||y==null||x.length<2||x.length!=y.length)
            throw new IllegalArgumentException("Invalid action keyframes");
        for(int i=0;i<x.length;i++){
            if(!Double.isFinite(x[i])||!Double.isFinite(y[i])||
                    (i>0&&x[i]<=x[i-1]))
                throw new IllegalArgumentException("Action knots must be finite and increasing");
        }
        return pchip(x,y,clamp(t,x[0],x[x.length-1]));
    }

    private static double pchip(double[] x,double[] y,double t) {
        int i=segment(t,x);
        double h=x[i+1]-x[i];
        double u=(t-x[i])/h;
        double m0=slope(x,y,i),m1=slope(x,y,i+1);
        double u2=u*u,u3=u2*u;
        double v=(2*u3-3*u2+1)*y[i]
                +(u3-2*u2+u)*h*m0
                +(-2*u3+3*u2)*y[i+1]
                +(u3-u2)*h*m1;
        return clamp(v,Math.min(y[i],y[i+1]),Math.max(y[i],y[i+1]));
    }
    private static double slope(double[] x,double[] y,int i) {
        if(i==0)return (y[1]-y[0])/(x[1]-x[0]);
        if(i==x.length-1){
            int j=x.length-2;
            return (y[j+1]-y[j])/(x[j+1]-x[j]);
        }
        double h0=x[i]-x[i-1],h1=x[i+1]-x[i];
        double d0=(y[i]-y[i-1])/h0,d1=(y[i+1]-y[i])/h1;
        if(d0*d1<=0)return 0;
        double w0=2*h1+h0,w1=h1+2*h0;
        return (w0+w1)/(w0/d0+w1/d1);
    }
    private static int segment(double t,double[] knots){
        if(t<=knots[0])return 0;
        for(int i=0;i<knots.length-2;i++)
            if(t<knots[i+1])return i;
        return knots.length-2;
    }
    private static double clamp(double a,double lo,double hi){
        return Math.max(lo,Math.min(hi,a));
    }
}
