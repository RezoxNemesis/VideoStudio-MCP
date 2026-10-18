package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OMEGA M3.3: a deliberately authored, editable crossed-baton shot.
 * This is NOT 3D collision simulation or automatic human-quality choreography.
 * Only already prepared 2D weapon source transforms change; the exact existing
 * IK/pose/camera program and every non-weapon object remain untouched.
 */
final class SketchAnimationCombatStaging {
    private static final int CONTACT = 16;
    private static final int[] KEYS = {0,8,10,12,14,16,18,20,24,30,35};
    private static final double[] ENVELOPE = {0,0,0,.10,.79,1,1,1,0,0,0};
    private static final String[][] GROUPS = {
        {"wide_baton","wide_baton_edge","wide_baton_core","wide_baton_grip"},
        {"rook_wide_baton","rook_wide_baton_edge","rook_wide_baton_core","rook_wide_baton_grip"}
    };
    private static final String[] RIGS = {"wide_rig","rook_wide_rig"};
    private static final double RAD=Math.PI/180;
    private SketchAnimationCombatStaging() {}

    static JSONObject cross(JSONObject original, JSONObject program) throws Exception {
        SketchDocument.validate(original);
        if(original.getInt("width")!=1280||original.getInt("height")!=720||
                program.getInt("frameCount")!=100)
            throw bad("Crossed weapon study requires the reviewed 100-frame 1280x720 scene");
        // Check the actual previous native weapon proof, not a guessed wrist location.
        JSONArray beforeFrames=SketchAnimationWeaponContact.trace(original,program,0,35)
                .getJSONArray("frames");
        JSONObject before=beforeFrames.getJSONObject(CONTACT);
        if(before.getDouble("tipGapPx")>6 || before.getDouble("centerlineGapPx")>6)
            throw bad("M3.2 verified tip contact is required before staging the crossed shafts");
        JSONObject sampled=SketchAnimationCompiler.sample(original,program,CONTACT);
        JSONArray hardContacts=sampled.getJSONArray("contactErrors");
        if(hardContacts.length()!=4)throw bad("Both actors must retain their planted feet");

        JSONObject next=SketchDocument.copy(original);
        double[][] grips=new double[2][2];
        double[][] tips=new double[2][2];
        double[] nativeAngles=new double[2];
        double[] restAngles=new double[2];
        double[] lengths=new double[2];
        for(int group=0;group<2;group++) {
            JSONObject rig=rig(program,RIGS[group]);
            JSONObject wristRest=joint(rig.getJSONArray("joints"),"wrist");
            JSONObject wristNow=solverJoint(sampled,RIGS[group],"wrist");
            JSONObject core=SketchDocument.object(original,GROUPS[group][2]);
            JSONArray paths=core.getJSONArray("commands");
            if(paths.length()!=2 || !"M".equals(paths.getJSONArray(0).getString(0)) ||
                    !"L".equals(paths.getJSONArray(1).getString(0)))
                throw bad("The original baton core must remain one editable M/L centre-line");
            double x0=paths.getJSONArray(0).getDouble(1)*1280;
            double y0=paths.getJSONArray(0).getDouble(2)*720;
            double x1=paths.getJSONArray(1).getDouble(1)*1280;
            double y1=paths.getJSONArray(1).getDouble(2)*720;
            double oldX=wristRest.getDouble("x")*1280,oldY=wristRest.getDouble("y")*720;
            double nowX=wristNow.getDouble("x")*1280,nowY=wristNow.getDouble("y")*720;
            double a=wristNow.getDouble("rotation")*RAD;
            grips[group][0]=nowX+Math.cos(a)*(x0-oldX)-Math.sin(a)*(y0-oldY);
            grips[group][1]=nowY+Math.sin(a)*(x0-oldX)+Math.cos(a)*(y0-oldY);
            nativeAngles[group]=wristNow.getDouble("rotation");
            restAngles[group]=Math.toDegrees(Math.atan2(y1-y0,x1-x0));
            lengths[group]=Math.hypot(x1-x0,y1-y0);
            if(lengths[group]<60 || lengths[group]>180)throw bad("Prepared baton is not a bounded arm-length vector");
        }
        double gripGap=dist(grips[0],grips[1]);
        if(gripGap<40 || gripGap>100)throw bad("The original independently drawn glove/grip staging is unsuitable");
        double targetX=(grips[0][0]+grips[1][0])*.5;
        double targetY=(grips[0][1]+grips[1][1])*.5-.54*Math.min(lengths[0],lengths[1]);
        double[] degrees=new double[2];
        for(int group=0;group<2;group++) {
            double aim=Math.toDegrees(Math.atan2(targetY-grips[group][1],targetX-grips[group][0]));
            degrees[group]=normalize(aim-nativeAngles[group]-restAngles[group]);
            if(Math.abs(degrees[group])>112 || (group==0 && degrees[group]>-70) ||
                    (group==1 && degrees[group]<70))
                throw bad("Safe joint/grip-held baton staging angle cannot reach the requested crossing");
            for(String id:GROUPS[group]) {
                JSONObject obj=SketchDocument.object(next,id);
                if(!"path".equals(obj.getString("kind")) || obj.getInt("frame")!=0 ||
                        obj.getInt("exposure")<36 || obj.getJSONArray("keys").length()!=KEYS.length)
                    throw bad("Refuse to replace incompatible/artist-edited weapon: "+id);
                // The gripped point comes from the original *core* path, not the
                // wrist pivot: avoid the baton's M3.2 hand-detachment artifact.
                JSONObject base=obj.getJSONObject("transform");
                JSONObject core=SketchDocument.object(next,GROUPS[group][2]);
                JSONArray M=core.getJSONArray("commands").getJSONArray(0);
                JSONObject rest=joint(rig(program,RIGS[group]).getJSONArray("joints"),"wrist");
                if(Math.abs(base.getDouble("anchorX")-rest.getDouble("x"))>1e-6 ||
                        Math.abs(base.getDouble("anchorY")-rest.getDouble("y"))>1e-6)
                    throw bad("Refusing to restage a non-M3.2 weapon pivot");
                base.put("anchorX",M.getDouble(1)).put("anchorY",M.getDouble(2));
                JSONArray keys=obj.getJSONArray("keys");
                for(int i=0;i<KEYS.length;i++) {
                    JSONObject existing=keys.getJSONObject(i);
                    if(existing.getInt("frame")!=KEYS[i] ||
                            !"linear".equals(existing.getString("interpolation")))
                        throw bad("Refuse to override an independently authored weapon key");
                    // Retain the reviewed contact-swing timing; replace only its
                    // bounded rotation and geometric pivot, never the skeleton.
                    JSONObject pose=SketchDocument.copy(base)
                            .put("rotation",degrees[group]*ENVELOPE[i]);
                    existing.put("transform",pose);
                }
            }
        }
        SketchDocument.validate(next);
        JSONArray newFrames=SketchAnimationWeaponContact.trace(next,program,0,35)
                .getJSONArray("frames");
        JSONObject contact=newFrames.getJSONObject(CONTACT);
        Segment a=segment(contact.getJSONArray("wideCore"));
        Segment b=segment(contact.getJSONArray("rookCore"));
        Crossing hit=crossing(a,b);
        if(hit==null || hit.angle<35 || hit.angle>90 || hit.t<.35 || hit.t>.75 ||
                hit.u<.35 || hit.u>.75 || hit.point.x<500 || hit.point.x>850 ||
                hit.point.y<245 || hit.point.y>410 ||
                dist(a.b,b.b)<25)
            throw bad("Native baton shafts do not form a clear interior X-shaped impact");
        double maxTipStep=0;
        Segment priorA=null,priorB=null;
        for(int frame=0;frame<36;frame++) {
            JSONObject sampledRow=newFrames.getJSONObject(frame);
            Segment fA=segment(sampledRow.getJSONArray("wideCore"));
            Segment fB=segment(sampledRow.getJSONArray("rookCore"));
            for(Point p:new Point[]{fA.a,fA.b,fB.a,fB.b})
                if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||p.x<0||p.x>1280||p.y<0||p.y>720)
                    throw bad("Staged baton leaves the source frame at "+frame);
            if(frame>=8 && frame<=24 && priorA!=null)
                maxTipStep=Math.max(maxTipStep,Math.max(dist(fA.b,priorA.b),dist(fB.b,priorB.b)));
            priorA=fA;priorB=fB;
        }
        if(maxTipStep>=85)throw bad("Cinematic cross introduces an unsafe single-frame baton snap");
        JSONObject checked=SketchAnimationCompiler.sample(next,program,CONTACT);
        JSONArray contacts=checked.getJSONArray("contactErrors");
        if(contacts.length()!=hardContacts.length())throw bad("Cross staging changed planted foot count");
        for(int i=0;i<contacts.length();i++) {
            JSONObject c=contacts.getJSONObject(i),d=hardContacts.getJSONObject(i);
            if(!c.getString("rigId").equals(d.getString("rigId")) ||
                    !c.getString("jointId").equals(d.getString("jointId")) ||
                    Math.abs(c.getDouble("errorPixels")-d.getDouble("errorPixels"))>.001)
                throw bad("Native foot-contact proof changed during weapon edit");
        }
        JSONObject report=new JSONObject()
                .put("schema","videostudio.crossed-baton-staging.v1")
                .put("frame",CONTACT).put("sourceSpace","native-canvas-pixels")
                .put("previousTipGapPx",before.getDouble("tipGapPx"))
                .put("gripSeparationPx",gripGap)
                .put("tipSeparationPx",dist(a.b,b.b))
                .put("shaftCrossAngleDeg",hit.angle)
                .put("intersectionFractionA",hit.t).put("intersectionFractionB",hit.u)
                .put("impactX",hit.point.x/1280).put("impactY",hit.point.y/720)
                .put("maxTipStepPx",maxTipStep)
                .put("footContactsPreserved",true)
                .put("realisticGloveDeformationVerified",false)
                .put("releaseDecision","MANUAL_REVIEW_REQUIRED")
                .put("evidence","Two native sketched baton CORE shafts intersect strictly inside both drawn segments. No mesh collision, finger rig, or professional acting certification");
        return new JSONObject().put("source",next).put("report",report);
    }

    /** Native bake must preserve the same interior crossing after key reduction. */
    static JSONObject bakedCross(JSONObject compiled,int frame) throws Exception {
        if(frame!=CONTACT)throw bad("Reviewed combat impact is frame 16");
        Segment a=bakedSegment(compiled,"wide_baton_core",frame);
        Segment b=bakedSegment(compiled,"rook_wide_baton_core",frame);
        Crossing hit=crossing(a,b);
        if(hit==null)throw bad("Compiled vector paths no longer cross");
        return new JSONObject().put("shaftCrossAngleDeg",hit.angle)
                .put("intersectionFractionA",hit.t).put("intersectionFractionB",hit.u)
                .put("gripSeparationPx",dist(a.a,b.a))
                .put("tipSeparationPx",dist(a.b,b.b))
                .put("impactX",hit.point.x/compiled.getDouble("width"))
                .put("impactY",hit.point.y/compiled.getDouble("height"));
    }
    private static Segment bakedSegment(JSONObject doc,String id,int frame)throws Exception{
        JSONObject o=SketchDocument.object(doc,id);
        JSONObject t=SketchDocument.transformAt(o,frame);
        JSONArray commands=o.getJSONArray("commands");
        if(commands.length()!=2)throw bad("Compiled baton lost original two-point editable path");
        int w=doc.getInt("width"),h=doc.getInt("height");
        Point[] points=new Point[2];
        for(int i=0;i<2;i++){
            JSONArray cmd=commands.getJSONArray(i);
            if((i==0&&!"M".equals(cmd.getString(0)))||(i==1&&!"L".equals(cmd.getString(0))))
                throw bad("Compiled baton has invalid path commands");
            double x=cmd.getDouble(1)*w,y=cmd.getDouble(2)*h;
            double ax=t.getDouble("anchorX")*w,ay=t.getDouble("anchorY")*h;
            double deltaX=(x-ax)*t.getDouble("scaleX"),deltaY=(y-ay)*t.getDouble("scaleY");
            double a=Math.toRadians(t.getDouble("rotation"));
            points[i]=new Point(ax+t.getDouble("x")*w+Math.cos(a)*deltaX-Math.sin(a)*deltaY,
                    ay+t.getDouble("y")*h+Math.sin(a)*deltaX+Math.cos(a)*deltaY);
        }
        return new Segment(points[0],points[1]);
    }
    private static Segment segment(JSONArray points)throws Exception{
        if(points.length()!=2)throw bad("Native weapon core must have two endpoints");
        JSONArray a=points.getJSONArray(0),b=points.getJSONArray(1);
        return new Segment(new Point(a.getDouble(0),a.getDouble(1)),
                new Point(b.getDouble(0),b.getDouble(1)));
    }
    private static Crossing crossing(Segment a,Segment b){
        double rx=a.b.x-a.a.x,ry=a.b.y-a.a.y,sx=b.b.x-b.a.x,sy=b.b.y-b.a.y;
        double den=rx*sy-ry*sx;
        if(Math.abs(den)<1e-6)return null;
        double dx=b.a.x-a.a.x,dy=b.a.y-a.a.y;
        double t=(dx*sy-dy*sx)/den;
        double u=(dx*ry-dy*rx)/den;
        if(t<0||t>1||u<0||u>1)return null;
        double dot=rx*sx+ry*sy;
        double cos=Math.min(1,Math.abs(dot)/(Math.hypot(rx,ry)*Math.hypot(sx,sy)));
        return new Crossing(new Point(a.a.x+t*rx,a.a.y+t*ry),t,u,Math.toDegrees(Math.acos(cos)));
    }
    private static JSONObject rig(JSONObject program,String id)throws Exception{
        JSONArray list=program.getJSONArray("rigs");
        for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))return list.getJSONObject(i);
        throw bad("Expected original wide-shot actor rig: "+id);
    }
    private static JSONObject joint(JSONArray list,String id)throws Exception{
        for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))return list.getJSONObject(i);
        throw bad("Missing original gripped joint: "+id);
    }
    private static JSONObject solverJoint(JSONObject sampled,String rig,String id)throws Exception{
        JSONArray list=sampled.getJSONArray("joints");
        for(int i=0;i<list.length();i++){
            JSONObject j=list.getJSONObject(i);
            if(rig.equals(j.getString("rigId"))&&id.equals(j.getString("jointId")))return j;
        }
        throw bad("Missing native joint sample: "+rig+":"+id);
    }
    private static double normalize(double deg){return ((deg+540)%360)-180;}
    private static double dist(double[] a,double[] b){return Math.hypot(a[0]-b[0],a[1]-b[1]);}
    private static double dist(Point a,Point b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException("OMEGA_COMBAT_STAGING: "+message);}
    private static final class Point {final double x,y;Point(double x,double y){this.x=x;this.y=y;}}
    private static final class Segment {final Point a,b;Segment(Point a,Point b){this.a=a;this.b=b;}}
    private static final class Crossing {
        final Point point;final double t,u,angle;
        Crossing(Point point,double t,double u,double angle){this.point=point;this.t=t;this.u=u;this.angle=angle;}
    }
}