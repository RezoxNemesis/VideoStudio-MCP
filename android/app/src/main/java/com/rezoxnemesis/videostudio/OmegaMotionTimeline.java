package com.rezoxnemesis.videostudio;

/**
 * OMEGA Phase 5: frame-pinned Hermite pose interpolator.
 * Contacts with identical locked endpoints never slip.
 * This predicts designed easing, not proof of natural acting.
 */
final class OmegaMotionTimeline {
    private OmegaMotionTimeline(){}
    static final class Key {
        final int frame;
        final double x,y,angle,vx,vy,angularVelocity;
        final boolean lockedContact;
        Key(int frame,double x,double y,double angle,double vx,double vy,
            double angularVelocity,boolean lockedContact){
            if(frame<0||frame>1200||!finite(x,y,angle,vx,vy,angularVelocity)
                ||Math.abs(x)>8||Math.abs(y)>8||Math.abs(angle)>720
                ||Math.abs(vx)>4||Math.abs(vy)>4||Math.abs(angularVelocity)>360)
                throw new IllegalArgumentException("Invalid motion key");
            this.frame=frame;this.x=x;this.y=y;this.angle=angle;
            this.vx=vx;this.vy=vy;this.angularVelocity=angularVelocity;
            this.lockedContact=lockedContact;
        }
    }
    static final class Pose {
        final double x,y,angle;
        Pose(double x,double y,double angle){this.x=x;this.y=y;this.angle=angle;}
    }
    static Pose at(Key start,Key end,double frame){
        if(start==null||end==null||end.frame<=start.frame||!Double.isFinite(frame))
            throw new IllegalArgumentException("Motion keys must have increasing frames");
        double t=Math.max(0,Math.min(1,(frame-start.frame)/(end.frame-start.frame)));
        if(start.lockedContact&&end.lockedContact){
            if(Math.hypot(start.x-end.x,start.y-end.y)>1e-9)
                throw new IllegalArgumentException("Locked contact endpoints cannot drift");
            return new Pose(start.x,start.y,start.angle);
        }
        double duration=end.frame-start.frame;
        return new Pose(hermite(start.x,end.x,start.vx*duration,end.vx*duration,t),
                        hermite(start.y,end.y,start.vy*duration,end.vy*duration,t),
                        hermite(start.angle,end.angle,start.angularVelocity*duration,
                                end.angularVelocity*duration,t));
    }
    static double secondary(double elapsedFrames,double amplitude,double cyclesPerFrame,double damping){
        if(!finite(elapsedFrames,amplitude,cyclesPerFrame,damping)
            ||elapsedFrames<0||elapsedFrames>1200||Math.abs(amplitude)>8
            ||cyclesPerFrame<0||cyclesPerFrame>2||damping<0||damping>2)
            throw new IllegalArgumentException("Secondary motion inputs invalid");
        return amplitude*Math.exp(-damping*elapsedFrames)
              *Math.sin(2*Math.PI*cyclesPerFrame*elapsedFrames);
    }
    private static double hermite(double a,double b,double m0,double m1,double t){
        double t2=t*t,t3=t2*t;
        return (2*t3-3*t2+1)*a+(t3-2*t2+t)*m0
                +(-2*t3+3*t2)*b+(t3-t2)*m1;
    }
    private static boolean finite(double...a){
        for(double v:a)if(!Double.isFinite(v))return false;
        return true;
    }
}