package com.rezoxnemesis.videostudio;

/**
 * Deterministic 2D articulated combat rig. This creates entirely new moving
 * limb positions at arbitrary subframe times — not optical-flow crossfades,
 * still-image transitions, or Ken Burns camera movement.
 *
 * All coordinates are normalized in a fixed world-space camera. The procedural
 * renderer owns appearances, textures, atmospheric effects and backgrounds.
 */
public final class CombatRigSolver {
    private static final double[] T={0,.20,.405,.48,.565,.705,.845,1};
    private static final double[] BLACK_X={.245,.255,.402,.435,.468,.425,.373,.362};
    private static final double[] WHITE_X={.767,.758,.619,.570,.605,.694,.646,.667};
    private static final double[] BLACK_Y={.692,.688,.663,.683,.725,.688,.690,.694};
    private static final double[] WHITE_Y={.689,.691,.664,.685,.724,.706,.691,.693};
    private static final double[] BLACK_HAND_X={.285,.296,.353,.397,.498,.429,.389,.402};
    private static final double[] WHITE_HAND_X={.725,.714,.661,.601,.619,.642,.604,.611};
    private static final double[] BLACK_HAND_Y={.694,.654,.584,.644,.744,.601,.639,.649};
    private static final double[] WHITE_HAND_Y={.647,.645,.592,.645,.740,.624,.605,.640};
    private static final double[] BLACK_ANGLE={.72,-.23,-.95,-.78,.64,-1.29,.30,.38};
    private static final double[] WHITE_ANGLE={2.58,3.37,3.90,3.91,2.46,3.81,3.33,3.52};
    private static final double[] BLACK_FORWARD={.316,.316,.418,.453,.449,.398,.361,.365};
    private static final double[] BLACK_REAR={.190,.190,.355,.405,.429,.374,.324,.316};
    private static final double[] WHITE_FORWARD={.690,.690,.581,.547,.554,.595,.574,.580};
    private static final double[] WHITE_REAR={.840,.840,.707,.650,.711,.774,.751,.769};
    private static final double[] BLACK_HEAD_TILT={.04,.11,.28,.12,.15,.10,-.04,.01};
    private static final double[] WHITE_HEAD_TILT={-.02,-.08,-.13,-.12,.34,.11,-.03,.0};
    private static final double BLADE_LENGTH=.145;

    public static final class Vec {
        public final double x,y;
        public Vec(double x,double y){this.x=x;this.y=y;}
        public Vec plus(double x,double y){return new Vec(this.x+x,this.y+y);}
        public double distance(Vec v){return Math.hypot(x-v.x,y-v.y);}
    }
    public static final class Warrior {
        public final Vec pelvis,chest,neck,head;
        public final Vec swordShoulder,swordElbow,swordHand,swordTip;
        public final Vec freeShoulder,freeElbow,freeHand;
        public final Vec leadingHip,leadingKnee,leadingFoot,trailingHip,trailingKnee,trailingFoot;
        public final boolean black;
        public final double headTilt;
        Warrior(boolean black,Vec pelvis,Vec chest,Vec neck,Vec head,Vec swordShoulder,
                Vec swordElbow,Vec swordHand,Vec swordTip,Vec freeShoulder,Vec freeElbow,
                Vec freeHand,Vec leadingHip,Vec leadingKnee,Vec leadingFoot,
                Vec trailingHip,Vec trailingKnee,Vec trailingFoot,double headTilt){
            this.black=black;this.pelvis=pelvis;this.chest=chest;this.neck=neck;this.head=head;
            this.swordShoulder=swordShoulder;this.swordElbow=swordElbow;this.swordHand=swordHand;
            this.swordTip=swordTip;this.freeShoulder=freeShoulder;this.freeElbow=freeElbow;
            this.freeHand=freeHand;this.leadingHip=leadingHip;this.leadingKnee=leadingKnee;
            this.leadingFoot=leadingFoot;this.trailingHip=trailingHip;
            this.trailingKnee=trailingKnee;this.trailingFoot=trailingFoot;this.headTilt=headTilt;
        }
    }
    public static final class Frame {
        public final Warrior black,white;
        public final double sceneTime,impactIntensity,dustIntensity,counterIntensity;
        public final boolean cameraLocked=true;
        public final double swordTipSeparation;
        Frame(double s,Warrior a,Warrior b,double impact,double dust,double counter){
            sceneTime=s;black=a;white=b;impactIntensity=impact;
            dustIntensity=dust;counterIntensity=counter;
            swordTipSeparation=a.swordTip.distance(b.swordTip);
        }
    }

    private CombatRigSolver(){}

    /** Fully deterministic with no stateful stepping or external assets. */
    public static Frame at(double seconds,double durationSeconds){
        if(!Double.isFinite(seconds)||!Double.isFinite(durationSeconds)
                ||durationSeconds<=0) throw new IllegalArgumentException("Invalid combat time");
        double real=bound(seconds/durationSeconds,0,1);
        // Retiming stretches actual movement near the weapon collision while
        // preserving continuous motion — no repeated/frozen image frames.
        double story=remap(real);
        Warrior black=pose(true,story);
        Warrior white=pose(false,story);
        double impact=Math.exp(-Math.pow((story-.482)/.031,2));
        double dust=bound(.10+impact*.82+Math.exp(-Math.pow((story-.565)/.045,2))*.65,0,1);
        double counter=Math.exp(-Math.pow((story-.79)/.052,2));
        return new Frame(story,black,white,impact,dust,counter);
    }

    /**
     * Piecewise smooth time warp, preserving monotonic progress everywhere.
     * Slow-motion at impact still samples moving poses at every output frame.
     */
    public static double remap(double real){
        final double[] play={0,.185,.405,.53,.665,.83,1};
        final double[] story={0,.20,.405,.48,.565,.845,1};
        return sample(story,play,bound(real,0,1));
    }

    private static Warrior pose(boolean black,double story){
        double face=black?1:-1;
        double x=sample(black?BLACK_X:WHITE_X,T,story);
        double y=sample(black?BLACK_Y:WHITE_Y,T,story);
        double handX=sample(black?BLACK_HAND_X:WHITE_HAND_X,T,story);
        double handY=sample(black?BLACK_HAND_Y:WHITE_HAND_Y,T,story);
        double angle=sample(black?BLACK_ANGLE:WHITE_ANGLE,T,story);
        double headTilt=sample(black?BLACK_HEAD_TILT:WHITE_HEAD_TILT,T,story);

        Vec pelvis=new Vec(x,y);
        Vec chest=new Vec(x+headTilt*.14,y-.063);
        Vec neck=new Vec(chest.x+headTilt*.055,chest.y-.027);
        Vec head=neck.plus(headTilt*.038,-.039);
        Vec swordShoulder=chest.plus(face*.025,-.004);
        Vec freeShoulder=chest.plus(-face*.024,.006);
        Vec swordHand=new Vec(handX,handY);
        Vec swordElbow=elbow(swordShoulder,swordHand,.083,.086,face*.85);
        Vec freeHand=new Vec(x-face*.034,y-.008-Math.max(0,.05-Math.abs(story-.48))*.25);
        Vec freeElbow=elbow(freeShoulder,freeHand,.082,.085,-face);

        Vec leadingHip=pelvis.plus(face*.019,.009);
        Vec trailingHip=pelvis.plus(-face*.019,.009);
        Vec leadingFoot=new Vec(sample(black?BLACK_FORWARD:WHITE_FORWARD,T,story),.838);
        Vec trailingFoot=new Vec(sample(black?BLACK_REAR:WHITE_REAR,T,story),.841);
        Vec leadingKnee=elbow(leadingHip,leadingFoot,.091,.100,-face*.95);
        Vec trailingKnee=elbow(trailingHip,trailingFoot,.091,.100,face*.95);
        Vec tip=swordHand.plus(BLADE_LENGTH*Math.cos(angle),BLADE_LENGTH*Math.sin(angle));
        return new Warrior(black,pelvis,chest,neck,head,swordShoulder,swordElbow,
                swordHand,tip,freeShoulder,freeElbow,freeHand,
                leadingHip,leadingKnee,leadingFoot,trailingHip,trailingKnee,
                trailingFoot,headTilt);
    }

    /** Two-bone analytic IK with length constraints and deterministic knee/elbow bend. */
    public static Vec elbow(Vec root,Vec target,double upper,double lower,double bendDirection){
        double dx=target.x-root.x,dy=target.y-root.y;
        double dist=Math.hypot(dx,dy);
        double safe=bound(dist,Math.abs(upper-lower)+.00001,upper+lower-.00001);
        double dirX=dist<.00001?1:dx/dist,dirY=dist<.00001?0:dy/dist;
        double along=(upper*upper-lower*lower+safe*safe)/(2*safe);
        double height=Math.sqrt(Math.max(0,upper*upper-along*along));
        double sign=bendDirection>=0?1:-1;
        return new Vec(root.x+dirX*along-dirY*height*sign,
                root.y+dirY*along+dirX*height*sign);
    }

    /**
     * Fritsch-Carlson monotone Hermite interpolation. One-sided derivatives
     * are zero at reversals to preserve planted contacts; the curve is C1
     * continuous across all linear-motion segments, without pose jumps.
     */
    public static double sample(double[] values,double[] times,double time){
        if(values==null||times==null||values.length!=times.length||values.length<2)
            throw new IllegalArgumentException("Curve arrays must be paired");
        if(time<=times[0])return values[0];
        int n=times.length;
        if(time>=times[n-1])return values[n-1];
        int k=0;
        while(k<n-2&&time>times[k+1])k++;
        double h=times[k+1]-times[k];
        if(h<=0)throw new IllegalArgumentException("Curve time must increase");
        double slope=(values[k+1]-values[k])/h;
        double m0=tangent(values,times,k),m1=tangent(values,times,k+1);
        double t=(time-times[k])/h,t2=t*t,t3=t2*t;
        return (2*t3-3*t2+1)*values[k]+(t3-2*t2+t)*h*m0
                +(-2*t3+3*t2)*values[k+1]+(t3-t2)*h*m1;
    }

    private static double tangent(double[] v,double[] t,int i){
        if(i==0 || i==v.length-1)return 0;
        double d0=(v[i]-v[i-1])/(t[i]-t[i-1]);
        double d1=(v[i+1]-v[i])/(t[i+1]-t[i]);
        if(d0*d1<=0)return 0;
        double a=t[i]-t[i-1],b=t[i+1]-t[i];
        double w1=2*b+a,w2=b+2*a;
        return (w1+w2)/(w1/d0+w2/d1);
    }
    private static double bound(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
}
