package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Explicit source-UV foot plants. IK predictions are not measured raster evidence. */
public final class StoryboardContactPlan {
    public static final int MAX_INTERVALS_PER_ACTOR=32;
    public final String projectId,fromAssetId,toAssetId;
    public final int sourceWidth,sourceHeight;
    private final Rig2DProfile blackFrom,whiteFrom,blackTo,whiteTo;
    private final List<Interval> blackIntervals,whiteIntervals;

    private static final class Interval {
        final String foot;
        final int index,joint;
        final double start,end,u,v;
        Interval(String foot,int index,double start,double end,double u,double v) {
            this.foot=foot;this.index=index;this.start=start;this.end=end;this.u=u;this.v=v;
            joint="leadingFoot".equals(foot)?Rig2DProfile.LEADING_FOOT:Rig2DProfile.TRAILING_FOOT;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("foot",foot).put("start",start).put("end",end)
                    .put("anchor",new JSONArray().put(u).put(v));
        }
    }

    /** Values are in source-height units; this is a solver trace, never a pixel QA pass. */
    public static final class FootSample {
        public final String eventId,foot;
        public final boolean black,active,targetReachable;
        public final int intervalIndex;
        public final double targetU,targetV,predictedU,predictedV,errorSourceHeight;
        FootSample(boolean black,Interval interval,double targetU,double targetV,
                   float[] predicted,boolean reachable,double aspect) {
            this.black=black;foot=interval.foot;intervalIndex=interval.index;active=true;
            eventId=(black?"black":"white")+":"+foot+":"+interval.index;
            this.targetU=targetU;this.targetV=targetV;
            predictedU=predicted[0];predictedV=predicted[1];targetReachable=reachable;
            errorSourceHeight=Math.hypot((predictedU-targetU)*aspect,predictedV-targetV);
        }
    }
    public static final class Solved {
        public final float[][] black,white;
        public final List<FootSample> predictedFootContacts;
        Solved(float[][] black,float[][] white,List<FootSample> samples) {
            this.black=black;this.white=white;
            predictedFootContacts=Collections.unmodifiableList(new ArrayList<>(samples));
        }
    }

    private StoryboardContactPlan(Rig2DProfile blackFrom,Rig2DProfile whiteFrom,
            Rig2DProfile blackTo,Rig2DProfile whiteTo,int width,int height,
            List<Interval> blackIntervals,List<Interval> whiteIntervals) throws Exception {
        this.blackFrom=copy(blackFrom);this.whiteFrom=copy(whiteFrom);
        this.blackTo=copy(blackTo);this.whiteTo=copy(whiteTo);
        projectId=blackFrom.projectId;fromAssetId=blackFrom.sourceAssetId;toAssetId=blackTo.sourceAssetId;
        sourceWidth=width;sourceHeight=height;
        this.blackIntervals=Collections.unmodifiableList(blackIntervals);
        this.whiteIntervals=Collections.unmodifiableList(whiteIntervals);
    }

    public static StoryboardContactPlan parse(JSONObject authored,Rig2DProfile blackFrom,
            Rig2DProfile whiteFrom,Rig2DProfile blackTo,Rig2DProfile whiteTo,
            int sourceWidth,int sourceHeight) throws Exception {
        quartet(blackFrom,whiteFrom,blackTo,whiteTo);
        if(sourceWidth<=0||sourceHeight<=0||sourceWidth>65535||sourceHeight>65535)
            throw new IllegalArgumentException("Oriented source dimensions are required");
        fields(authored,"black","white");
        List<Interval> black=intervals(authored,"black",blackFrom,blackTo);
        List<Interval> white=intervals(authored,"white",whiteFrom,whiteTo);
        return new StoryboardContactPlan(blackFrom,whiteFrom,blackTo,whiteTo,
                sourceWidth,sourceHeight,black,white);
    }

    public double sourceAspect() { return (double)sourceWidth/sourceHeight; }
    public boolean hasContacts(boolean black) { return !(black?blackIntervals:whiteIntervals).isEmpty(); }
    /** Evaluate the actual output clock plus narrow authored interval boundaries/interiors before allocation. */
    public List<Double> validationPhases(int frameCount) {
        if(frameCount<1||frameCount>4096)throw new IllegalArgumentException("Contact validation frame count must be 1..4096");
        java.util.TreeSet<Double> phases=new java.util.TreeSet<>();
        for(int i=0;i<=frameCount;i++) {
            phases.add(i/(double)frameCount);
            phases.add((double)(i/(float)frameCount)); // Composer's existing float clock is also evaluated.
        }
        for(List<Interval> actor:java.util.Arrays.asList(blackIntervals,whiteIntervals))for(Interval interval:actor) {
            phases.add(interval.start);phases.add(interval.end);phases.add(interval.start+(interval.end-interval.start)*.5);
        }
        return Collections.unmodifiableList(new ArrayList<>(phases));
    }
    public JSONObject toJson() throws Exception {
        JSONArray black=new JSONArray(),white=new JSONArray();
        for(Interval interval:blackIntervals)black.put(interval.json());
        for(Interval interval:whiteIntervals)white.put(interval.json());
        return new JSONObject().put("black",black).put("white",white);
    }
    public JSONObject bindingProof() throws Exception {
        return new JSONObject().put("version",1).put("projectId",projectId)
                .put("fromAssetId",fromAssetId).put("toAssetId",toAssetId)
                .put("sourceWidth",sourceWidth).put("sourceHeight",sourceHeight)
                .put("coordinateSpace","oriented-source-uv")
                .put("contactSha256",Rig2DProjectStore.sha256Json(toJson()))
                .put("blackFrom",Rig2DProjectStore.binding(blackFrom))
                .put("whiteFrom",Rig2DProjectStore.binding(whiteFrom))
                .put("blackTo",Rig2DProjectStore.binding(blackTo))
                .put("whiteTo",Rig2DProjectStore.binding(whiteTo));
    }
    public void requireBindingProof(JSONObject accepted) throws Exception {
        if(accepted==null||!Rig2DProjectStore.canonicalJson(bindingProof()).equals(
                Rig2DProjectStore.canonicalJson(accepted)))
            throw new IllegalStateException("SOURCE_RIG_CHANGED: contact bindings or source geometry differ");
    }
    public void requireRigBindings(Rig2DProfile blackFrom,Rig2DProfile whiteFrom,
            Rig2DProfile blackTo,Rig2DProfile whiteTo) throws Exception {
        quartet(blackFrom,whiteFrom,blackTo,whiteTo);
        Rig2DProfile[] supplied={blackFrom,whiteFrom,blackTo,whiteTo};
        Rig2DProfile[] frozen={this.blackFrom,this.whiteFrom,this.blackTo,this.whiteTo};
        for(int i=0;i<supplied.length;i++)if(!Rig2DProjectStore.sha256(supplied[i]).equals(
                Rig2DProjectStore.sha256(frozen[i])))
            throw new IllegalStateException("SOURCE_RIG_CHANGED: foot contacts belong to different annotations");
    }

    /** Program phase controls intervals; independently paced actor phases control limb measurements. */
    public Solved solve(float[][] black,float[][] white,double programPhase,
                        double blackPosePhase,double whitePosePhase) {
        clock(programPhase);clock(blackPosePhase);clock(whitePosePhase);
        float[][] b=poseCopy(black),w=poseCopy(white);
        ArrayList<FootSample> samples=new ArrayList<>();
        solveActor(b,blackFrom,blackTo,blackIntervals,true,programPhase,blackPosePhase,samples);
        solveActor(w,whiteFrom,whiteTo,whiteIntervals,false,programPhase,whitePosePhase,samples);
        return new Solved(b,w,samples);
    }

    private void solveActor(float[][] pose,Rig2DProfile from,Rig2DProfile to,
            List<Interval> intervals,boolean black,double programPhase,double posePhase,
            List<FootSample> samples) {
        double aspect=sourceAspect();
        for(int foot:new int[]{Rig2DProfile.LEADING_FOOT,Rig2DProfile.TRAILING_FOOT}) {
            Interval active=null,before=null,after=null;
            boolean authored=false;
            for(Interval interval:intervals)if(interval.joint==foot) {
                authored=true;
                if(programPhase>=interval.start&&programPhase<=interval.end)active=interval;
                if(interval.end<programPhase)before=interval;
                if(interval.start>programPhase&&after==null)after=interval;
            }
            if(!authored)continue; // Absence is not a guessed stance.
            double u,v;
            if(active!=null){u=active.u;v=active.v;}
            else {
                double start=before==null?0:before.end,end=after==null?1:after.start;
                double phase=end==start?0:(programPhase-start)/(end-start);
                phase=Math.max(0,Math.min(1,phase));phase=phase*phase*(3-2*phase);
                double au=before==null?from.joints[foot][0]:before.u;
                double av=before==null?from.joints[foot][1]:before.v;
                double bu=after==null?to.joints[foot][0]:after.u;
                double bv=after==null?to.joints[foot][1]:after.v;
                u=au+(bu-au)*phase;v=av+(bv-av)*phase;
            }
            int knee=foot-1,hip=foot-2;
            double upper=length(from.joints[hip],from.joints[knee],aspect)*(1-posePhase)
                    +length(to.joints[hip],to.joints[knee],aspect)*posePhase;
            double lower=length(from.joints[knee],from.joints[foot],aspect)*(1-posePhase)
                    +length(to.joints[knee],to.joints[foot],aspect)*posePhase;
            RigKinematics.TwoBonePose solved=RigKinematics.solveTwoBone(metric(pose[hip],aspect),
                    new RigKinematics.Vec3(u*aspect,v,0),metric(pose[knee],aspect),upper,lower);
            if(active!=null&&!solved.targetReachable)
                throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: unreachable authored "+
                        (black?"black":"white")+" "+active.foot+" plant at phase "+programPhase);
            // Authored endpoints stay byte-for-byte exact, including their knee direction.
            if(programPhase!=0&&programPhase!=1) {
                pose[knee]=uv(solved.joint,aspect);pose[foot]=uv(solved.effector,aspect);
            }
            if(active!=null)samples.add(new FootSample(black,active,u,v,pose[foot],
                    solved.targetReachable,aspect));
        }
    }

    private static List<Interval> intervals(JSONObject authored,String actor,
            Rig2DProfile from,Rig2DProfile to) throws Exception {
        JSONArray values=authored.optJSONArray(actor);
        if(values==null||values.length()>MAX_INTERVALS_PER_ACTOR)
            throw new IllegalArgumentException(actor+" must be an array of at most 32 authored contacts");
        ArrayList<Interval> result=new ArrayList<>();
        for(int i=0;i<values.length();i++) {
            JSONObject value=values.optJSONObject(i);fields(value,"foot","start","end","anchor");
            Object name=value.opt("foot");
            if(!(name instanceof String)||!("leadingFoot".equals(name)||"trailingFoot".equals(name)))
                throw new IllegalArgumentException("Contact foot must name leadingFoot or trailingFoot");
            double start=number(value.opt("start")),end=number(value.opt("end"));
            if(start>=end)throw new IllegalArgumentException("Contact start must precede end");
            JSONArray anchor=value.optJSONArray("anchor");
            if(anchor==null||anchor.length()!=2)throw new IllegalArgumentException("Contact anchor requires source [u,v]");
            Interval interval=new Interval((String)name,i,start,end,number(anchor.get(0)),number(anchor.get(1)));
            if(start==0)endpoint(interval,from.joints[interval.joint]);
            if(end==1)endpoint(interval,to.joints[interval.joint]);
            result.add(interval);
        }
        result.sort(Comparator.comparingDouble((Interval a)->a.start).thenComparing(a->a.foot).thenComparingDouble(a->a.end));
        for(int i=0;i<result.size();i++)for(int j=i+1;j<result.size();j++) {
            Interval a=result.get(i),b=result.get(j);
            if(a.joint!=b.joint)continue;
            if(b.start<a.end)throw new IllegalArgumentException("Authored intervals overlap on the same foot");
            if(b.start==a.end&&(a.u!=b.u||a.v!=b.v))
                throw new IllegalArgumentException("Touching contacts cannot teleport a foot to another anchor");
        }
        ArrayList<Interval> indexed=new ArrayList<>();
        for(int i=0;i<result.size();i++) {
            Interval interval=result.get(i);
            indexed.add(new Interval(interval.foot,i,interval.start,interval.end,interval.u,interval.v));
        }
        return indexed;
    }
    private static void endpoint(Interval interval,float[] point) {
        if(Math.abs(interval.u-point[0])>2e-6||Math.abs(interval.v-point[1])>2e-6)
            throw new IllegalArgumentException("Endpoint contact must match its authored source foot");
    }
    private static void quartet(Rig2DProfile bf,Rig2DProfile wf,Rig2DProfile bt,Rig2DProfile wt) {
        if(bf==null||wf==null||bt==null||wt==null||!bf.black||!bt.black||wf.black||wt.black
                ||!bf.projectId.equals(wf.projectId)||!bf.projectId.equals(bt.projectId)||!bf.projectId.equals(wt.projectId)
                ||!bf.sourceAssetId.equals(wf.sourceAssetId)||!bt.sourceAssetId.equals(wt.sourceAssetId))
            throw new IllegalArgumentException("Contacts require exact source-owned black/white endpoint pairs");
    }
    private static void fields(JSONObject value,String... allowed) {
        if(value==null)throw new IllegalArgumentException("Authored contact object required");
        for(Iterator<String> keys=value.keys();keys.hasNext();) {
            String key=keys.next();boolean found=false;
            for(String name:allowed)if(name.equals(key)){found=true;break;}
            if(!found)throw new IllegalArgumentException("Unknown contact field: "+key);
        }
    }
    private static double number(Object value) {
        if(!(value instanceof Number))throw new IllegalArgumentException("Contact values must be numbers");
        double result=((Number)value).doubleValue();clock(result);return result;
    }
    private static void clock(double value) {
        if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Contact phase/coordinates must be finite in 0..1");
    }
    private static Rig2DProfile copy(Rig2DProfile profile) throws Exception { return Rig2DProfile.fromJson(profile.toJson()); }
    private static float[][] poseCopy(float[][] input) {
        if(input==null||input.length!=16)throw new IllegalArgumentException("Sixteen solved pose points required");
        float[][] result=new float[16][2];
        for(int i=0;i<16;i++) {
            if(input[i]==null||input[i].length!=2||!Float.isFinite(input[i][0])||!Float.isFinite(input[i][1]))
                throw new IllegalArgumentException("Solved pose coordinates must be finite");
            result[i]=input[i].clone();
        }
        return result;
    }
    private static RigKinematics.Vec3 metric(float[] point,double aspect) { return new RigKinematics.Vec3(point[0]*aspect,point[1],0); }
    private static float[] uv(RigKinematics.Vec3 point,double aspect) { return new float[]{(float)(point.x/aspect),(float)point.y}; }
    private static double length(float[] a,float[] b,double aspect) { return Math.hypot(((double)a[0]-b[0])*aspect,(double)a[1]-b[1]); }
}
