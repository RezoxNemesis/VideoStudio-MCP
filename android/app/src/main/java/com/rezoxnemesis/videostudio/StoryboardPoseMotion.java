package com.rezoxnemesis.videostudio;

/**
 * Source-conditioned 2D motion between two ACTUAL illustrated poses.
 *
 * Unlike CombatRigSolver, no generic fighter trajectory is invented here.
 * Endpoints are exact authored joints and blade tips. Intermediate motion is
 * driven by their measured changes, with modest limb-length and foot-contact
 * constraints. The 2D joints are never rendered as visible skeletons.
 *
 * This deterministic fallback is NOT neural synthesis of hidden anatomy.
 */
public final class StoryboardPoseMotion {
    public static final int JOINTS=16;
    private static final int[][] CHAINS={
            {0,1},{1,2},{2,3},
            {1,4},{4,5},{5,6},{1,7},{7,8},{8,9},
            {0,10},{10,11},{11,12},{0,13},{13,14},{14,15}
    };

    public enum Action { ADVANCE, PARRY, STRIKE, LEAP, IMPACT, KNOCKBACK }

    public static final class Frame {
        public final float[][] black,white;
        public final float[] blackBladeTip,whiteBladeTip;
        public final float[] blackBladeHilt,whiteBladeHilt;
        /** Physical source-height coordinates, predicted from authored original weapon anchors. */
        public final RigKinematics.SegmentWitness bladeWitness;
        public final double contact,progress;
        /** Explicit IK predictions only. Raster foot drift must be measured independently. */
        public final java.util.List<StoryboardContactPlan.FootSample> predictedFootContacts;
        Frame(float[][] black,float[][] white,float[] blackBladeTip,
              float[] whiteBladeTip,double contact,double progress) {
            this(black,white,blackBladeTip,whiteBladeTip,contact,progress,java.util.Collections.emptyList());
        }
        Frame(float[][] black,float[][] white,float[] blackBladeTip,float[] whiteBladeTip,
              double contact,double progress,java.util.List<StoryboardContactPlan.FootSample> samples) {
            this(black,white,blackBladeTip,whiteBladeTip,contact,progress,samples,null,null,null);
        }
        Frame(float[][] black,float[][] white,float[] blackBladeTip,float[] whiteBladeTip,
              double contact,double progress,java.util.List<StoryboardContactPlan.FootSample> samples,
              float[] blackHilt,float[] whiteHilt,RigKinematics.SegmentWitness witness) {
            this.black=black;this.white=white;
            this.blackBladeTip=blackBladeTip;
            this.whiteBladeTip=whiteBladeTip;
            blackBladeHilt=blackHilt;whiteBladeHilt=whiteHilt;bladeWitness=witness;
            this.contact=contact;this.progress=progress;
            predictedFootContacts=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(samples));
        }
    }

    /** Explicit physical source metric, without guessing stance from similar endpoint feet. */
    public static Frame between(Rig2DProfile blackA,Rig2DProfile whiteA,Rig2DProfile blackB,Rig2DProfile whiteB,
            float[] blackSwordA,float[] whiteSwordA,float[] blackSwordB,float[] whiteSwordB,
            double time,Action action,double sourceAspect) {
        return physicalFrame(blackA,whiteA,blackB,whiteB,blackSwordA,whiteSwordA,blackSwordB,whiteSwordB,
                time,time,time,action,action,sourceAspect,null,false);
    }

    public static Frame between(Rig2DProfile blackA,Rig2DProfile whiteA,Rig2DProfile blackB,Rig2DProfile whiteB,
            float[] blackSwordA,float[] whiteSwordA,float[] blackSwordB,float[] whiteSwordB,
            double time,Action action,StoryboardContactPlan contacts) {
        if(contacts==null)throw new IllegalArgumentException("Authored contact plan required");
        return physicalFrame(blackA,whiteA,blackB,whiteB,blackSwordA,whiteSwordA,blackSwordB,whiteSwordB,
                time,time,time,action,action,contacts.sourceAspect(),contacts,false);
    }

    public static Frame between(Rig2DProfile blackA,Rig2DProfile whiteA,Rig2DProfile blackB,Rig2DProfile whiteB,
            float[] blackSwordA,float[] whiteSwordA,float[] blackSwordB,float[] whiteSwordB,
            double time,StoryboardChoreography.Beat beat,StoryboardContactPlan contacts) {
        if(contacts==null)throw new IllegalArgumentException("Authored contact plan required");
        return physicalFrame(blackA,whiteA,blackB,whiteB,blackSwordA,whiteSwordA,blackSwordB,whiteSwordB,time,
                StoryboardChoreography.phase(beat,true,time),StoryboardChoreography.phase(beat,false,time),
                StoryboardChoreography.actionFor(beat,true),StoryboardChoreography.actionFor(beat,false),contacts.sourceAspect(),contacts,true);
    }

    private static Frame physicalFrame(Rig2DProfile blackA,Rig2DProfile whiteA,Rig2DProfile blackB,Rig2DProfile whiteB,
            float[] blackSwordA,float[] whiteSwordA,float[] blackSwordB,float[] whiteSwordB,
            double programTime,double blackTime,double whiteTime,Action blackAction,Action whiteAction,
            double aspect,StoryboardContactPlan contacts,boolean explicitBeatPhase) {
        if(blackA==null||blackB==null||whiteA==null||whiteB==null||!blackA.black||!blackB.black||whiteA.black||whiteB.black
                ||!blackA.projectId.equals(whiteA.projectId)||!blackA.projectId.equals(blackB.projectId)||!blackA.projectId.equals(whiteB.projectId)
                ||!blackA.sourceAssetId.equals(whiteA.sourceAssetId)||!blackB.sourceAssetId.equals(whiteB.sourceAssetId))
            throw new IllegalArgumentException("Both exact source-owned actor pairs required");
        metric(aspect);clock(programTime);
        tipBinding(blackA,blackSwordA);tipBinding(whiteA,whiteSwordA);tipBinding(blackB,blackSwordB);tipBinding(whiteB,whiteSwordB);
        float[][] black=interpolateMetric(blackA.joints,blackB.joints,blackTime,blackAction,aspect,false,explicitBeatPhase);
        float[][] white=interpolateMetric(whiteA.joints,whiteB.joints,whiteTime,whiteAction,aspect,false,explicitBeatPhase);
        java.util.List<StoryboardContactPlan.FootSample> samples=java.util.Collections.emptyList();
        if(contacts!=null) {
            try{contacts.requireRigBindings(blackA,whiteA,blackB,whiteB);}catch(Exception mismatch){throw new IllegalArgumentException(mismatch.getMessage(),mismatch);}
            StoryboardContactPlan.Solved solved=contacts.solve(black,white,programTime,
                    explicitBeatPhase?blackTime:timing((float)blackTime,blackAction),
                    explicitBeatPhase?whiteTime:timing((float)whiteTime,whiteAction));
            black=solved.black;white=solved.white;samples=solved.predictedFootContacts;
        }
        float[] bk=physicalBlade(blackA.joints,blackB.joints,blackSwordA,blackSwordB,blackTime,blackAction,aspect,black,explicitBeatPhase);
        float[] wh=physicalBlade(whiteA.joints,whiteB.joints,whiteSwordA,whiteSwordB,whiteTime,whiteAction,aspect,white,explicitBeatPhase);
        float[] blackHilt=appearanceHilt(blackA,blackB,blackTime,black[Rig2DProfile.SWORD_HAND],bk,aspect);
        float[] whiteHilt=appearanceHilt(whiteA,whiteB,whiteTime,white[Rig2DProfile.SWORD_HAND],wh,aspect);
        RigKinematics.SegmentWitness witness=blackHilt==null||whiteHilt==null?null:
                RigKinematics.closestSegments(vec(blackHilt,aspect),vec(bk,aspect),vec(whiteHilt,aspect),vec(wh,aspect));
        boolean intended=blackAction==Action.PARRY||blackAction==Action.IMPACT||whiteAction==Action.PARRY||whiteAction==Action.IMPACT;
        double tolerance=.01*Math.min(actorHeight(black,aspect),actorHeight(white,aspect));
        double proximity=!intended||witness==null||tolerance==0?0:Math.max(0,1-witness.separation/tolerance);
        return new Frame(black,white,bk,wh,proximity,programTime,samples,blackHilt,whiteHilt,witness);
    }

    private StoryboardPoseMotion(){}

    public static Action action(String name) {
        if(name==null)return Action.ADVANCE;
        try {return Action.valueOf(name.trim().toUpperCase(java.util.Locale.US));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException(
                "Unsupported storyboard beat: "+name);}
    }

    public static Frame between(Rig2DProfile blackA,Rig2DProfile whiteA,
                                Rig2DProfile blackB,Rig2DProfile whiteB,
                                float[] blackSwordA,float[] whiteSwordA,
                                float[] blackSwordB,float[] whiteSwordB,
                                double time,Action action) {
        if(blackA==null||blackB==null||whiteA==null||whiteB==null)
            throw new IllegalArgumentException("Two complete source-keyframe actor pairs required");
        if(!blackA.black||!blackB.black||whiteA.black||whiteB.black)
            throw new IllegalArgumentException("Both black/white actors must retain their identity");
        if(time<0||time>1||!Double.isFinite(time))
            throw new IllegalArgumentException("Timestamp must be in 0..1");
        if(action==null)throw new IllegalArgumentException("Choreography action is required");
        float[][] black=interpolate(blackA.joints,blackB.joints,time,action);
        float[][] white=interpolate(whiteA.joints,whiteB.joints,time,action);
        float[] bk=blade(blackA.joints,blackB.joints,blackSwordA,blackSwordB,time,action);
        float[] wh=blade(whiteA.joints,whiteB.joints,whiteSwordA,whiteSwordB,time,action);
        double gap=Math.hypot(bk[0]-wh[0],bk[1]-wh[1]);
        double contact=action==Action.IMPACT||action==Action.PARRY
                ? Math.max(0,1-gap/.055)*Math.exp(-Math.pow((time-.5)/.11,2))
                : 0;
        return new Frame(black,white,bk,wh,contact,time);
    }

    /**
     * Geometry-aware interpolation: preserves source points at both endpoints,
     * approximates constant-speed movement for rushes, uses controlled arc
     * for leaps and keeps planted feet anchored when the two drawings agree.
     */
    public static float[][] interpolate(float[][] from,float[][] to,
                                        double time,Action action) {
        return interpolateMetric(from,to,time,action,1,true,false);
    }
    public static float[][] interpolate(float[][] from,float[][] to,double time,Action action,double sourceAspect) {
        metric(sourceAspect);return interpolateMetric(from,to,time,action,sourceAspect,false,false);
    }
    private static float[][] interpolateMetric(float[][] from,float[][] to,double time,Action action,
                                               double sourceAspect,boolean legacyFootHeuristic,boolean explicitBeatPhase) {
        valid(from);valid(to);
        if(time<0||time>1||!Double.isFinite(time))
            throw new IllegalArgumentException("Invalid pose clock");
        if(action==null)throw new IllegalArgumentException("Action required");
        if(time==0)return copy(from);
        if(time==1)return copy(to);
        float t=(float)time;
        float motion=explicitBeatPhase?t:timing(t,action);
        float[][] points=new float[JOINTS][2];
        for(int i=0;i<JOINTS;i++){
            for(int a=0;a<2;a++)points[i][a]=from[i][a]
                    +(to[i][a]-from[i][a])*motion;
        }
        if(action==Action.LEAP) {
            // The waist follows a rise/fall ballistic arc relative to the
            // authored start/end, with smaller upper-body overshoot.
            float up=(float)(Math.sin(Math.PI*time)*.035);
            for(int i=0;i<JOINTS;i++)points[i][1]-=up;
        }
        if(action==Action.KNOCKBACK) {
            // Tiny recoil, never a generic full-body stunt.
            float recoil=(float)Math.sin(Math.PI*time)*.008f;
            points[1][0]+=(to[0][0]>from[0][0]?-recoil:recoil);
        }

        // Preserve observed body proportions without forcing the image into
        // a different skeleton. Constraint fades to zero at authored keys.
        float strength=(float)(.34*4*time*(1-time));
        for(int[] bone:CHAINS){
            int a=bone[0],b=bone[1];
            float ideal=lerp(length(from[a],from[b],sourceAspect),
                    length(to[a],to[b],sourceAspect),motion);
            float dx=points[b][0]-points[a][0],dy=points[b][1]-points[a][1];
            float actual=(float)Math.hypot(dx*sourceAspect,dy);
            if(actual>0) {
                float desired=ideal/actual;
                float scale=1+strength*(desired-1);
                points[b][0]=points[a][0]+dx*scale;
                points[b][1]=points[a][1]+dy*scale;
            }
        }
        if(legacyFootHeuristic&&action!=Action.LEAP && action!=Action.KNOCKBACK){
            for(int foot:new int[]{12,15}){
                // A stationary contact foot may not drift just because
                // the upper torso pivots. No automatic contact is invented
                // for a moving foot.
                if(length(from[foot],to[foot])<.018f){
                    points[foot][0]=lerp(from[foot][0],to[foot][0],motion);
                    points[foot][1]=lerp(from[foot][1],to[foot][1],motion);
                }
            }
        }
        for(float[] p:points){
            p[0]=clamp(p[0],-.12f,1.12f);
            p[1]=clamp(p[1],-.12f,1.12f);
        }
        return points;
    }

    /**
     * Interpolate sword DIRECTION relative to its illustrated hand.
     * This avoids unattached tips and ensures source blade length is
     * preserved through strikes, instead of freely morphing across the city.
     */
    public static float[] blade(float[][] from,float[][] to,
                                float[] tipA,float[] tipB,
                                double time,Action action) {
        valid(from);valid(to);validTip(tipA);validTip(tipB);
        if(time<0||time>1||!Double.isFinite(time)||action==null)
            throw new IllegalArgumentException("Invalid sword animation request");
        if(time==0)return tipA.clone();
        if(time==1)return tipB.clone();
        float[][] pts=interpolate(from,to,time,action);
        float[] hand=pts[Rig2DProfile.SWORD_HAND];
        float[] handA=from[Rig2DProfile.SWORD_HAND],
                handB=to[Rig2DProfile.SWORD_HAND];
        float ax=tipA[0]-handA[0],ay=tipA[1]-handA[1],
              bx=tipB[0]-handB[0],by=tipB[1]-handB[1];
        float r0=(float)Math.hypot(ax,ay),r1=(float)Math.hypot(bx,by);
        if(r0<.012f||r1<.012f||r0>.65f||r1>.65f)
            throw new IllegalArgumentException("Sword tip must stay attached to its actor");
        double angle0=Math.atan2(ay,ax),angle1=Math.atan2(by,bx);
        double difference=Math.atan2(Math.sin(angle1-angle0),Math.cos(angle1-angle0));
        float phase=timing((float)time,action);
        double sweep=angle0+difference*phase;
        float length=lerp(r0,r1,phase);
        return new float[]{hand[0]+(float)Math.cos(sweep)*length,
                           hand[1]+(float)Math.sin(sweep)*length};
    }

    public static float[] blade(float[][] from,float[][] to,float[] tipA,float[] tipB,
                                 double time,Action action,double sourceAspect) {
        metric(sourceAspect);
        return physicalBlade(from,to,tipA,tipB,time,action,sourceAspect,
                interpolate(from,to,time,action,sourceAspect),false);
    }
    private static float[] physicalBlade(float[][] from,float[][] to,float[] tipA,float[] tipB,
            double time,Action action,double aspect,float[][] solvedPose,boolean explicitBeatPhase) {
        valid(from);valid(to);validTip(tipA);validTip(tipB);clock(time);
        if(action==null)throw new IllegalArgumentException("Action required");
        if(time==0)return tipA.clone();if(time==1)return tipB.clone();
        float[] handA=from[Rig2DProfile.SWORD_HAND],handB=to[Rig2DProfile.SWORD_HAND];
        double ax=((double)tipA[0]-handA[0])*aspect,ay=(double)tipA[1]-handA[1];
        double bx=((double)tipB[0]-handB[0])*aspect,by=(double)tipB[1]-handB[1];
        double r0=Math.hypot(ax,ay),r1=Math.hypot(bx,by);
        if(r0==0||r1==0)throw new IllegalArgumentException("Source sword hand and tip must differ");
        double angle0=Math.atan2(ay,ax),angle1=Math.atan2(by,bx);
        double delta=Math.atan2(Math.sin(angle1-angle0),Math.cos(angle1-angle0));
        double phase=explicitBeatPhase?time:timing((float)time,action),angle=angle0+delta*phase,length=r0+(r1-r0)*phase;
        float[] hand=solvedPose[Rig2DProfile.SWORD_HAND];
        return new float[]{(float)(hand[0]+Math.cos(angle)*length/aspect),(float)(hand[1]+Math.sin(angle)*length)};
    }

    /** Same original-prop hand-to-tip similarity and independent appearance clock as native drawing. */
    private static float[] appearanceHilt(Rig2DProfile from,Rig2DProfile to,double phase,
            float[] targetHand,float[] targetTip,double aspect) {
        if(from.swordHilt==null||to.swordHilt==null||from.swordTip==null||to.swordTip==null)return null;
        if(phase<=.465)return projectHilt(from,targetHand,targetTip,aspect);
        if(phase>=.535)return projectHilt(to,targetHand,targetTip,aspect);
        double u=(phase-.465)/.07;u=u*u*(3-2*u);
        float[] a=projectHilt(from,targetHand,targetTip,aspect),b=projectHilt(to,targetHand,targetTip,aspect);
        return new float[]{(float)(a[0]+(b[0]-a[0])*u),(float)(a[1]+(b[1]-a[1])*u)};
    }
    private static float[] projectHilt(Rig2DProfile source,float[] targetHand,float[] targetTip,double aspect) {
        float[] hand=source.joints[Rig2DProfile.SWORD_HAND];
        double rx=((double)source.swordTip[0]-hand[0])*aspect,ry=(double)source.swordTip[1]-hand[1];
        double tx=((double)targetTip[0]-targetHand[0])*aspect,ty=(double)targetTip[1]-targetHand[1];
        double denominator=rx*rx+ry*ry;
        if(denominator==0)throw new IllegalArgumentException("Source sword hand-to-tip segment is degenerate");
        double a=(rx*tx+ry*ty)/denominator,b=(rx*ty-ry*tx)/denominator;
        double hx=((double)source.swordHilt[0]-hand[0])*aspect,hy=(double)source.swordHilt[1]-hand[1];
        return new float[]{(float)(targetHand[0]+(a*hx-b*hy)/aspect),(float)(targetHand[1]+b*hx+a*hy)};
    }
    private static double actorHeight(float[][] pose,double aspect) {
        double feetX=((double)pose[12][0]+pose[15][0])*.5,feetY=((double)pose[12][1]+pose[15][1])*.5;
        return Math.hypot((pose[Rig2DProfile.HEAD][0]-feetX)*aspect,pose[Rig2DProfile.HEAD][1]-feetY);
    }
    private static void tipBinding(Rig2DProfile rig,float[] tip) {
        validTip(tip);
        if(rig.swordTip!=null&&(tip[0]!=rig.swordTip[0]||tip[1]!=rig.swordTip[1]))
            throw new IllegalArgumentException("Sword target endpoint differs from its exact source rig annotation");
    }

    public static double maxActorTravel(float[][] from,float[][] to) {
        valid(from);valid(to);
        return Math.hypot(from[0][0]-to[0][0],from[0][1]-to[0][1]);
    }

    private static float timing(float t,Action action) {
        if(action==Action.PARRY||action==Action.IMPACT)
            return t*t*(3-2*t);
        if(action==Action.KNOCKBACK)
            return 1-(1-t)*(1-t);
        return t;
    }
    private static float length(float[] a,float[] b){
        return (float)Math.hypot(a[0]-b[0],a[1]-b[1]);
    }
    private static float length(float[] a,float[] b,double aspect){return(float)Math.hypot(((double)a[0]-b[0])*aspect,(double)a[1]-b[1]);}
    private static RigKinematics.Vec3 vec(float[] p,double aspect){return new RigKinematics.Vec3(p[0]*aspect,p[1],0);}
    private static void metric(double aspect){if(!Double.isFinite(aspect)||aspect<=0)throw new IllegalArgumentException("Positive physical source aspect required");}
    private static void clock(double time){if(!Double.isFinite(time)||time<0||time>1)throw new IllegalArgumentException("Pose clock must be finite in 0..1");}
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private static float clamp(float v,float lo,float hi){
        return Math.max(lo,Math.min(hi,v));
    }
    private static float[][] copy(float[][] values) {
        float[][] result=new float[values.length][2];
        for(int i=0;i<values.length;i++)result[i]=values[i].clone();
        return result;
    }
    private static void valid(float[][] pose){
        if(pose==null||pose.length!=JOINTS)
            throw new IllegalArgumentException("Exactly sixteen authentic 2D joints required");
        for(float[] p:pose)if(p==null||p.length!=2||
                !Float.isFinite(p[0])||!Float.isFinite(p[1])||
                p[0]<0||p[0]>1||p[1]<0||p[1]>1)
            throw new IllegalArgumentException("Invalid 2D joint coordinates");
    }
    private static void validTip(float[] tip){
        if(tip==null||tip.length!=2||!Float.isFinite(tip[0])||
                !Float.isFinite(tip[1])||tip[0]<0||tip[0]>1||
                tip[1]<0||tip[1]>1)
            throw new IllegalArgumentException("Sword tip must be visible in the source frame");
    }
}
