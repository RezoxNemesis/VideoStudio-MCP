package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Original, reusable adult figure cleanup, baked to ordinary editable single-frame Bezier ink.
 * Coordinates are reference pixels in a 1280x720 view box; positive Y points down.
 * Hand anchors live in the neutral torso frame (hip at 0,-180) and follow lean/hip/root.
 * Foot anchors live on the unchanged floor frame and never follow root automatically.
 * Near/far identify the +X/-X model arms; camera depth decides their drawing order.
 */
final class SketchHumanFigure {
    static final String[] POSE_CHANNELS={"rootX","rootY","rootZ","bodyLean","headTilt","gazeX","gazeY","mouth","mouthWide","mouthRound","mouthTeeth","mouthTongue","smile","brow","nearHandX","nearHandY","nearHandZ","farHandX","farHandY","farHandZ","hipX","hipY","hipZ","footLeftX","footLeftY","footLeftZ","footRightX","footRightY","footRightZ","blink","nearWrist","farWrist","handOpen","nearElbowSwivel","farElbowSwivel"};
    private static final String INK="#FF25313A";
    private SketchHumanFigure(){}

    static JSONObject defaults()throws Exception {
        return new JSONObject().put("view","front").put("build","slender").put("hairStyle","short").put("skinColor","#FFD6A078").put("hairColor","#FF302D32")
                .put("coatColor","#FF526B78").put("trouserColor","#FF343F50").put("x",.5).put("floorY",.88).put("size",1).put("faceStyle","graphic").put("shading","cel").put("lightAngle",135);
    }
    static JSONObject settings(JSONObject input)throws Exception {
        SketchDocument.fields(input,"view,build,hairStyle,skinColor,hairColor,coatColor,trouserColor,x,floorY,size,faceStyle,shading,lightAngle,clothingStyle,figureStyle,constructionGuides,viewYawDegrees,viewPitchDegrees");JSONObject s=defaults();
        Iterator<String> keys=input.keys();while(keys.hasNext()){String key=keys.next();s.put(key,input.get(key));}
        if(s.has("figureStyle")&&(!(s.get("figureStyle") instanceof String)||!Set.of("legacy","anatomy_sketch").contains(s.getString("figureStyle"))))throw SketchDocument.bad("Unknown human figure style");
        if(s.has("constructionGuides"))SketchDocument.bool(s,"constructionGuides");
        boolean anatomy=anatomy(s);
        if(s.has("viewYawDegrees")||s.has("viewPitchDegrees")){if(!anatomy)throw SketchDocument.bad("Numeric camera guides require anatomy_sketch");if(s.has("viewYawDegrees"))SketchDocument.number(s,"viewYawDegrees",-360,360);if(s.has("viewPitchDegrees"))SketchDocument.number(s,"viewPitchDegrees",-75,75);}
        if(s.optBoolean("constructionGuides",false)&&!anatomy)throw SketchDocument.bad("Construction guides require anatomy sketch style");
        if(!(s.get("view") instanceof String)||!(anatomy?Set.of("front","left","right","back","high","low","three_quarter_left","three_quarter_right"):Set.of("front","left","right","back","high","low")).contains(s.getString("view")))throw SketchDocument.bad("Unknown human view for figure style");
        if(!(s.get("build") instanceof String)||!Set.of("slender","broad").contains(s.getString("build")))throw SketchDocument.bad("Unknown adult build");
        if(!(s.get("hairStyle") instanceof String)||!Set.of("short","long").contains(s.getString("hairStyle")))throw SketchDocument.bad("Unknown hair style");
        if(!(s.get("faceStyle") instanceof String)||!Set.of("graphic","angular","soft").contains(s.getString("faceStyle")))throw SketchDocument.bad("Unknown face structure");
        if(!(s.get("shading") instanceof String)||!Set.of("cel","soft").contains(s.getString("shading")))throw SketchDocument.bad("Unknown human surface shading");SketchDocument.number(s,"lightAngle",-180,180);
        if(s.has("clothingStyle")&&(!(s.get("clothingStyle") instanceof String)||!Set.of("legacy","tailored").contains(s.getString("clothingStyle"))))throw SketchDocument.bad("Unknown human clothing style");
        if(anatomy&&s.optString("clothingStyle","legacy").equals("tailored"))throw SketchDocument.bad("Anatomy sketch uses study shorts rather than tailored garments");
        for(String key:new String[]{"skinColor","hairColor","coatColor","trouserColor"}){
            if(!(s.get(key) instanceof String)||!s.getString(key).matches("#[a-fA-F0-9]{8}"))throw SketchDocument.bad("Human colours require #AARRGGBB");SketchDocument.color(s.getString(key));
        }
        SketchDocument.number(s,"x",0,1);SketchDocument.number(s,"floorY",0,1);SketchDocument.number(s,"size",.1,2);return s;
    }
    static JSONObject poseDefaults()throws Exception {
        return new JSONObject().put("rootX",0).put("rootY",0).put("rootZ",0).put("bodyLean",0).put("headTilt",0).put("gazeX",0).put("gazeY",0).put("blink",0)
                .put("mouth",0).put("mouthWide",0).put("mouthRound",0).put("mouthTeeth",0).put("mouthTongue",0).put("smile",0).put("brow",0)
                .put("nearHandX",58).put("nearHandY",-166).put("nearHandZ",16).put("farHandX",-58).put("farHandY",-166).put("farHandZ",-16)
                .put("hipX",0).put("hipY",-180).put("hipZ",0).put("footLeftX",-28).put("footLeftY",0).put("footLeftZ",-9).put("footRightX",28).put("footRightY",0).put("footRightZ",9)
                .put("nearWrist",0).put("farWrist",0).put("handOpen",1).put("nearElbowSwivel",0).put("farElbowSwivel",0);
    }
    /** Every coordinate channel is -4096..4096; IK and actual ink bounds impose physical limits. */
    static JSONObject validatePose(JSONObject input)throws Exception {
        SketchDocument.fields(input,String.join(",",POSE_CHANNELS));JSONObject p=poseDefaults();Iterator<String> keys=input.keys();
        while(keys.hasNext()){String key=keys.next();p.put(key,input.get(key));}
        for(String key:POSE_CHANNELS){double min=-4096,max=4096;
            if(key.equals("bodyLean")){min=-25;max=25;}else if(key.equals("headTilt")){min=-20;max=20;}
            else if(Set.of("gazeX","gazeY","smile","brow","mouthWide").contains(key)){min=-1;max=1;}else if(Set.of("mouth","mouthRound","mouthTeeth","mouthTongue","blink","handOpen").contains(key)){min=0;max=1;}
            else if(Set.of("nearWrist","farWrist","nearElbowSwivel","farElbowSwivel").contains(key)){min=-180;max=180;}
            SketchDocument.number(p,key,min,max);
        }
        return p;
    }

    /** Physical 3D joints, before the view's foreshortening. Lengths are invariant reference pixels. */
    static final class Skeleton {
        double[] hip,leftHip,rightHip,nearShoulder,farShoulder,nearElbow,farElbow,nearHand,farHand;
        double[] leftKnee,rightKnee,leftAnkle,rightAnkle,leftFoot,rightFoot,neck,head,shoulderCentre;
        double lean,headTilt;JSONObject controls;Camera camera;
    }
    static Skeleton skeleton(JSONObject input,JSONObject pose)throws Exception {
        JSONObject s=settings(input),p=validatePose(pose);Skeleton a=new Skeleton();a.controls=p;a.camera=camera(s);a.lean=p.getDouble("bodyLean");a.headTilt=p.getDouble("headTilt");
        a.hip=v(p.getDouble("rootX")+p.getDouble("hipX"),p.getDouble("rootY")+p.getDouble("hipY"),p.getDouble("rootZ")+p.getDouble("hipZ"));
        double span=s.getString("build").equals("broad")?47:40,pelvis=s.getString("build").equals("broad")?23:18;
        a.shoulderCentre=body(a,0,-120,0);a.nearShoulder=body(a,span,-120,0);a.farShoulder=body(a,-span,-120,0);
        a.nearHand=body(a,p.getDouble("nearHandX"),p.getDouble("nearHandY")+180,p.getDouble("nearHandZ"));
        a.farHand=body(a,p.getDouble("farHandX"),p.getDouble("farHandY")+180,p.getDouble("farHandZ"));
        a.nearElbow=joint(a.nearShoulder,a.nearHand,76,70,v(1,0,.3),p.getDouble("nearElbowSwivel"));a.farElbow=joint(a.farShoulder,a.farHand,76,70,v(-1,0,.3),p.getDouble("farElbowSwivel"));
        a.leftHip=add(a.hip,v(-pelvis,0,0));a.rightHip=add(a.hip,v(pelvis,0,0));
        a.leftFoot=v(p.getDouble("footLeftX"),p.getDouble("footLeftY"),p.getDouble("footLeftZ"));a.rightFoot=v(p.getDouble("footRightX"),p.getDouble("footRightY"),p.getDouble("footRightZ"));
        a.leftAnkle=add(a.leftFoot,v(0,-14,0));a.rightAnkle=add(a.rightFoot,v(0,-14,0));
        a.leftKnee=joint(a.leftHip,a.leftAnkle,90,86,v(0,0,1));a.rightKnee=joint(a.rightHip,a.rightAnkle,90,86,v(0,0,1));
        a.neck=body(a,0,anatomy(s)?-141:-156,0);a.head=add(a.neck,rotate(v(0,-24,0),a.camera.poseAxis(),Math.toRadians(a.lean+a.headTilt)));return a;
    }
    private static double[] body(Skeleton p,double x,double y,double z){return add(p.hip,rotate(v(x,y,z),p.camera.poseAxis(),Math.toRadians(p.lean)));}
    private static double[] joint(double[] start,double[] end,double upper,double lower,double[] bend)throws Exception {
        return joint(start,end,upper,lower,bend,0);
    }
    private static double[] joint(double[] start,double[] end,double upper,double lower,double[] bend,double swivel)throws Exception {
        double[] delta=sub(end,start);double distance=length(delta);
        if(distance<Math.abs(upper-lower)+1e-6||distance>upper+lower-1e-6)throw SketchDocument.bad("Human limb anchor is unreachable without stretching its fixed bones");
        double[] axis=mul(delta,1/distance),normal=sub(bend,mul(axis,dot(bend,axis)));
        if(length(normal)<1e-8)normal=sub(v(0,1,0),mul(axis,axis[1]));
        normal=rotate(mul(normal,1/length(normal)),axis,Math.toRadians(swivel));double along=(upper*upper-lower*lower+distance*distance)/(2*distance),height=Math.sqrt(Math.max(0,upper*upper-along*along));
        return add(start,add(mul(axis,along),mul(normal,height)));
    }

    /** Project local model coordinates; translation/size/canvas fit is applied only to emitted ink. */
    static double[] project(JSONObject settings,double[] point)throws Exception {return camera(settings).point(point);}

    /** Actual palm/pinch contact in projected MODEL pixels, before x/floorY, size, uniform
     * canvas fit and human-object transforms. Closed fingers meet at hand-local (0,15);
     * an open hand exposes a palm contact at (2,17). Wrist angles are degrees relative
     * to the projected forearm; positive rotates clockwise. No wrist anchor is substituted.
     */
    static double[] grip(JSONObject input,JSONObject pose,boolean near)throws Exception {
        JSONObject s=settings(input);Skeleton p=skeleton(s,pose);HandFrame hand=handFrame(camera(s),p,near);
        double x=2*hand.open,y=15+2*hand.open,c=Math.cos(hand.angle),n=Math.sin(hand.angle);
        return new double[]{hand.wrist[0]+x*c-y*n,hand.wrist[1]+x*n+y*c};
    }
    private static final class HandFrame {double[] wrist;double forearmAngle,angle,open;}
    private static HandFrame handFrame(Camera camera,Skeleton p,boolean near)throws Exception {
        HandFrame hand=new HandFrame();double[] elbow=camera.point(near?p.nearElbow:p.farElbow);hand.wrist=camera.point(near?p.nearHand:p.farHand);
        hand.forearmAngle=Math.atan2(hand.wrist[1]-elbow[1],hand.wrist[0]-elbow[0])-Math.PI/2;
        hand.angle=hand.forearmAngle+Math.toRadians(p.controls.getDouble(near?"nearWrist":"farWrist"));hand.open=p.controls.getDouble("handOpen");return hand;
    }
    static boolean anatomy(JSONObject s){return s.optString("figureStyle","legacy").equals("anatomy_sketch");}
    private static Camera camera(JSONObject s)throws Exception{return new Camera(s.getString("view"),anatomy(s),s.has("viewYawDegrees")?s.getDouble("viewYawDegrees"):null,s.has("viewPitchDegrees")?s.getDouble("viewPitchDegrees"):null);}
    static final class Camera {
        final String view;final double yaw,pitch;final boolean profile,back,worldPose;
        Camera(String view){this(view,false);}
        Camera(String view,boolean anatomy){this(view,anatomy,null,null);}
        Camera(String view,boolean anatomy,Double degrees,Double elevation){
            this.view=view;worldPose=degrees!=null||elevation!=null;
            double namedYaw=view.equals("left")?-Math.PI/2:view.equals("right")?Math.PI/2:view.equals("back")?Math.PI:view.equals("three_quarter_left")?-Math.PI/4:view.equals("three_quarter_right")?Math.PI/4:view.equals("high")?.20:view.equals("low")?-.16:0;
            double normalized=degrees==null?0:Math.IEEEremainder(degrees,360);
            yaw=degrees==null?namedYaw:Math.toRadians(normalized==0?0:normalized);
            double namedPitch=view.equals("high")?(anatomy?-.62:.32):view.equals("low")?(anatomy?.48:-.24):0;
            pitch=elevation==null?namedPitch:Math.toRadians(elevation==0?0:elevation);
            profile=worldPose?Math.abs(Math.cos(yaw))<1e-9:view.equals("left")||view.equals("right");back=worldPose?Math.cos(yaw)<-1e-9:view.equals("back");
        }
        double depth(double[] p){return -Math.sin(yaw)*p[0]+Math.cos(yaw)*p[2];}
        double clothDepth(double[] p){return Math.sin(pitch)*p[1]+Math.cos(pitch)*depth(p);}
        double[] point(double[] p){return new double[]{Math.cos(yaw)*p[0]+Math.sin(yaw)*p[2],Math.cos(pitch)*p[1]-Math.sin(pitch)*depth(p)};}
        double[] normal(){return v(-Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));}
        // New degree cameras orbit a world-fixed pose; omitted legacy controls keep their screen-normal lean.
        double[] poseAxis(){return worldPose?v(0,0,1):normal();}
    }

    static void draw(JSONObject doc,JSONObject layer,String actorId,JSONObject input,JSONObject pose,double seconds)throws Exception {
        if(!Double.isFinite(seconds)||seconds<0)throw SketchDocument.bad("Human drawing time must be finite and non-negative");
        if(doc.getInt("frameCount")!=1)throw SketchDocument.bad("Human figure draw requires a single-frame inner document");
        JSONObject s=settings(input);Skeleton p=skeleton(s,pose);String layerId=layer.getString("id");
        if(SketchDocument.layer(doc,layerId)!=layer)throw SketchDocument.bad("Human drawing layer must belong to its document");
        if(layer.getBoolean("locked"))throw SketchDocument.bad("Human drawing layer is locked");
        SketchDocument.id(new JSONObject().put("actor",actorId),"actor");if(actorId.length()>32)throw SketchDocument.bad("Human actor identity must fit 32 characters");
        JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<objects.length();i++)if(objects.getJSONObject(i).getString("id").startsWith(actorId+"_"))throw SketchDocument.bad("Human actor identity already exists");}
        JSONObject stage=SketchDocument.copy(layer).put("objects",new JSONArray());Painter ink=new Painter(stage,actorId);Camera camera=p.camera;
        ink.tailored=s.optString("clothingStyle","legacy").equals("tailored");ink.broad=s.getString("build").equals("broad");
        String skin=s.getString("skinColor"),coat=s.getString("coatColor"),trousers=s.getString("trouserColor");
        boolean leftNear=camera.depth(p.leftHip)>camera.depth(p.rightHip),plusNear=camera.depth(p.nearShoulder)>=camera.depth(p.farShoulder);
        if(anatomy(s)){SketchHumanAnatomy.draw(stage,actorId,s,p);}else{
        rearHair(ink,camera,p,s);leg(ink,camera,p,!leftNear,trousers);arm(ink,camera,p,!plusNear,coat,skin);
        leg(ink,camera,p,leftNear,trousers);torso(ink,camera,p,s);head(ink,camera,p,s);
        arm(ink,camera,p,plusNear,coat,skin);
        if(ink.tailored)ink.order();
        }
        JSONArray additions=stage.getJSONArray("objects");int width=doc.getInt("width"),height=doc.getInt("height");double fit=Math.min(width/1280d,height/720d),size=s.getDouble("size");
        for(int i=0;i<additions.length();i++){
            JSONObject o=additions.getJSONObject(i);JSONArray commands=new JSONArray(o.getJSONArray("commands").toString());o.put("commands",commands);
            for(int n=0;n<commands.length();n++){JSONArray command=commands.getJSONArray(n);for(int k=1;k<command.length();k+=2){
                // Staging at a small scale avoids JungleCelInk's clamp; all intended coordinates
                // are subsequently checked before being committed to the actual document.
                double x=(command.getDouble(k)*1280-640)*16,y=(command.getDouble(k+1)*720-360)*16;
                x=s.getDouble("x")+x*size*fit/width;y=s.getDouble("floorY")+y*size*fit/height;
                if(!Double.isFinite(x)||!Double.isFinite(y)||x<=0||x>=1||y<=0||y>=1)throw SketchDocument.bad("Human figure leaves canvas; adjust placement, size or anchors");
                command.put(k,x);command.put(k+1,y);
            }}
            if(o.has("clipOut")){JSONArray rings=o.getJSONArray("clipOut");for(int r=0;r<rings.length();r++){JSONArray ring=rings.getJSONArray(r);for(int j=0;j<ring.length();j++){JSONArray q=ring.getJSONArray(j);double x=s.getDouble("x")+(q.getDouble(0)*1280-640)*16*size*fit/width,y=s.getDouble("floorY")+(q.getDouble(1)*720-360)*16*size*fit/height;if(!Double.isFinite(x)||!Double.isFinite(y)||x<=0||x>=1||y<=0||y>=1)throw SketchDocument.bad("Human path clipping leaves canvas");q.put(0,x).put(1,y);}}}
            o.put("width",Math.max(.0005,Math.min(.2,o.getDouble("width")*size*fit*720/Math.min(width,height))));
            Path path=SketchFrameRenderer.path(commands,width,height);path.transform(SketchFrameRenderer.matrix(SketchDocument.transformAt(layer,0),width,height));RectF bounds=new RectF();path.computeBounds(bounds,true);
            double pad=o.getBoolean("fill")?0:o.getDouble("width")*Math.min(width,height)/2;
            if(bounds.left-pad<0||bounds.top-pad<0||bounds.right+pad>width||bounds.bottom+pad>height)throw SketchDocument.bad("Human figure leaves its transformed destination canvas");
        }
        if(!anatomy(s)&&s.getString("shading").equals("soft")){if(ink.tailored)tailoredMaterials(additions,width,height,s.getDouble("lightAngle"),actorId);else materials(additions,width,height,s.getDouble("lightAngle"));}
        JSONObject candidate=SketchDocument.copy(doc);JSONArray result=SketchDocument.layer(candidate,layerId).getJSONArray("objects");for(int i=0;i<additions.length();i++)result.put(additions.getJSONObject(i));SketchDocument.validate(candidate);
        layer.put("objects",result);
    }

    private static final java.util.regex.Pattern MATERIAL_PART=java.util.regex.Pattern.compile(".*_(head|ears|ear|neck|coat|near_hand|far_hand|near_finger|far_finger|near_thumb|far_thumb|near_wrist|far_wrist|near_upper_arm|far_upper_arm|near_forearm|far_forearm|near_shoulder|far_shoulder|near_elbow|far_elbow|near_shoulder_blend|far_shoulder_blend|near_elbow_blend|far_elbow_blend|left_thigh|right_thigh|left_shin|right_shin|left_knee|right_knee)_\\d+");
    /** Connected pieces of one material share a light field, including joint coverage. */
    private static void materials(JSONArray objects,int width,int height,double angle)throws Exception {
        Map<String,RectF> fields=new HashMap<>();
        for(int i=0;i<objects.length();i++){
            JSONObject ink=objects.getJSONObject(i);if(!ink.getBoolean("fill")||!MATERIAL_PART.matcher(ink.getString("id")).matches())continue;
            RectF bounds=new RectF();SketchFrameRenderer.path(ink.getJSONArray("commands"),width,height).computeBounds(bounds,true);String color=ink.getString("color");RectF existing=fields.get(color);if(existing==null)fields.put(color,bounds);else existing.union(bounds);
        }
        for(int i=0;i<objects.length();i++){JSONObject ink=objects.getJSONObject(i);RectF field=fields.get(ink.getString("color"));if(field!=null)material(ink,field,width,height,angle);}
    }
    private static void material(JSONObject ink,RectF bounds,int width,int height,double angle)throws Exception {
        if(!ink.getBoolean("fill")||!MATERIAL_PART.matcher(ink.getString("id")).matches()||bounds.width()<.001||bounds.height()<.001)return;
        double dx=Math.cos(Math.toRadians(angle)),dy=Math.sin(Math.toRadians(angle));double distance=Math.min(Math.abs(dx)<1e-9?Double.POSITIVE_INFINITY:bounds.width()/(2*Math.abs(dx)),Math.abs(dy)<1e-9?Double.POSITIVE_INFINITY:bounds.height()/(2*Math.abs(dy)));
        ink.put("gradient",new JSONObject().put("startColor",tone(ink.getString("color"),.11)).put("endColor",tone(ink.getString("color"),-.16))
                .put("x0",(bounds.centerX()-dx*distance)/width).put("y0",(bounds.centerY()-dy*distance)/height).put("x1",(bounds.centerX()+dx*distance)/width).put("y1",(bounds.centerY()+dy*distance)/height));
    }
    private static String tailoredMaterial(JSONObject o,String actor)throws Exception{
        if(!o.getBoolean("fill"))return null;String name=o.getString("id").substring(actor.length()+1);
        if(name.matches("(near|far)_sleeve_(surface|front)_\\d+")||name.matches("coat_\\d+"))return "coat";
        if(name.matches("(left|right)_trouser_(surface|panel)_\\d+"))return "trousers";
        return MATERIAL_PART.matcher(o.getString("id")).matches()?"skin":null;
    }
    private static void tailoredMaterials(JSONArray objects,int width,int height,double angle,String actor)throws Exception{
        Map<String,RectF> fields=new HashMap<>();
        for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);String role=tailoredMaterial(o,actor);if(role==null)continue;RectF b=new RectF();SketchFrameRenderer.path(o.getJSONArray("commands"),width,height).computeBounds(b,true);if(fields.containsKey(role))fields.get(role).union(b);else fields.put(role,b);}
        double dx=Math.cos(Math.toRadians(angle)),dy=Math.sin(Math.toRadians(angle));
        for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);String role=tailoredMaterial(o,actor);if(role==null)continue;RectF b=fields.get(role);if(b.width()<.001||b.height()<.001)continue;double distance=Math.min(Math.abs(dx)<1e-9?Double.POSITIVE_INFINITY:b.width()/(2*Math.abs(dx)),Math.abs(dy)<1e-9?Double.POSITIVE_INFINITY:b.height()/(2*Math.abs(dy)));
            o.put("gradient",new JSONObject().put("startColor",tone(o.getString("color"),.11)).put("endColor",tone(o.getString("color"),-.16)).put("x0",(b.centerX()-dx*distance)/width).put("y0",(b.centerY()-dy*distance)/height).put("x1",(b.centerX()+dx*distance)/width).put("y1",(b.centerY()+dy*distance)/height));}
    }
    private interface Paint {void draw(JungleCelInk d)throws Exception;}
    private static final class Painter {
        final JSONObject layer;final String actor;final JungleCelInk drawing;final Map<String,Integer> serial=new HashMap<>();
        final Map<String,Double> orders=new HashMap<>();boolean tailored,broad;double paintOrder;
        Painter(JSONObject layer,String actor){this.layer=layer;this.actor=actor;drawing=new JungleCelInk(layer).at(640,360,1/16d,0);}
        void part(String name,Paint action)throws Exception {part(name,drawing,action);}
        void part(String name,JungleCelInk drawing,Paint action)throws Exception {
            JSONArray objects=layer.getJSONArray("objects");int before=objects.length();action.draw(drawing);int next=serial.getOrDefault(name,0);
            for(int i=before;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);String id=actor+"_"+name+"_"+(next++);o.put("id",id);orders.put(id,paintOrder);}serial.put(name,next);
        }
        void order()throws Exception{JSONArray original=layer.getJSONArray("objects");List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<original.length();i++)sorted.add(original.getJSONObject(i));sorted.sort(Comparator.comparingDouble(o->orders.getOrDefault(o.optString("id"),20d)));JSONArray result=new JSONArray();for(JSONObject o:sorted)result.put(o);layer.put("objects",result);}
    }
    private static JungleCelInk.Shape pen(JungleCelInk d,String fill,String stroke,double weight){return d.ink(fill,stroke,weight*16);}
    private static void ellipse(JungleCelInk d,String fill,String stroke,double weight,double x,double y,double rx,double ry)throws Exception {d.ellipse(fill,stroke,weight*16,x,y,rx,ry);}
    private static void bone(Painter ink,Camera camera,String name,double[] start,double[] end,double wa,double wb,String fill)throws Exception {
        bone(ink,camera,name,start,end,wa,wb,fill,true,1);
    }
    private static void bone(Painter ink,Camera camera,String name,double[] start,double[] end,double wa,double wb,String fill,boolean outline,double cap)throws Exception {
        double[] a=camera.point(start),b=camera.point(end);double dx=b[0]-a[0],dy=b[1]-a[1],len=Math.max(.001,Math.hypot(dx,dy)),tx=dx/len,ty=dy/len,nx=-ty,ny=tx,k=.55228475;
        ink.part(name,d->pen(d,fill,outline?INK:null,.85).m(a[0]+nx*wa,a[1]+ny*wa).c(a[0]+dx*.36+nx*wa,a[1]+dy*.36+ny*wa,b[0]-dx*.15+nx*wb,b[1]-dy*.15+ny*wb,b[0]+nx*wb,b[1]+ny*wb)
                .c(b[0]+nx*wb+tx*wb*k,b[1]+ny*wb+ty*wb*k,b[0]+tx*wb+nx*wb*k,b[1]+ty*wb+ny*wb*k,b[0]+tx*wb,b[1]+ty*wb)
                .c(b[0]+tx*wb-nx*wb*k,b[1]+ty*wb-ny*wb*k,b[0]-nx*wb+tx*wb*k,b[1]-ny*wb+ty*wb*k,b[0]-nx*wb,b[1]-ny*wb)
                .c(b[0]-dx*.28-nx*wb,b[1]-dy*.28-ny*wb,a[0]+dx*.25-nx*wa,a[1]+dy*.25-ny*wa,a[0]-nx*wa,a[1]-ny*wa)
                .c(a[0]-nx*wa-tx*wa*cap*k,a[1]-ny*wa-ty*wa*cap*k,a[0]-tx*wa*cap-nx*wa*k,a[1]-ty*wa*cap-ny*wa*k,a[0]-tx*wa*cap,a[1]-ty*wa*cap)
                .c(a[0]-tx*wa*cap+nx*wa*k,a[1]-ty*wa*cap+ny*wa*k,a[0]+nx*wa-tx*wa*cap*k,a[1]+ny*wa-ty*wa*cap*k,a[0]+nx*wa,a[1]+ny*wa).z().end());
        ink.part(name+"_shade",d->pen(d,tone(fill,-.18),null,1).m(a[0]-nx*wa*.8,a[1]-ny*wa*.8).q(a[0]+dx*.45-nx*wa,a[1]+dy*.45-ny*wa,b[0]-nx*wb*.8,b[1]-ny*wb*.8).l(b[0]-nx*wb*.15,b[1]-ny*wb*.15).q(a[0]+dx*.45-nx*wa*.35,a[1]+dy*.45-ny*wa*.35,a[0]-nx*wa*.2,a[1]-ny*wa*.2).z().end());
    }
    private static void arm(Painter ink,Camera camera,Skeleton p,boolean plus,String coat,String skin)throws Exception {
        String side=plus?"near":"far";double[] shoulder=plus?p.nearShoulder:p.farShoulder,elbow=plus?p.nearElbow:p.farElbow,wrist=plus?p.nearHand:p.farHand;
        double[] shoulderPoint=camera.point(shoulder),elbowPoint=camera.point(elbow);HandFrame hand=handFrame(camera,p,plus);double[] b=hand.wrist;double open=hand.open;
        if(ink.tailored){
            SketchHumanClothingGeometry.DepthPlane torso=new SketchHumanClothingGeometry.DepthPlane(camera,p.hip,p.shoulderCentre);
            SketchHumanClothingGeometry.Limb cloth=SketchHumanClothingGeometry.limb(camera,shoulder,elbow,wrist,ink.broad?13:12,10.2,5.2,7.5,0,torso);
            tailoredSleeve(ink,side,cloth,coat,torso.relative(elbow));
            ink.paintOrder=(torso.relative(wrist)>=0?30:-10)+camera.clothDepth(wrist)*.0001;
        }else{
        ink.part(side+"_shoulder",d->ellipse(d,coat,null,.8,shoulderPoint[0],shoulderPoint[1],8.3,8.3));
        ink.part(side+"_elbow",d->ellipse(d,coat,null,.75,elbowPoint[0],elbowPoint[1],10,10));
        // The sleeve's elbow allowance overlaps the upper-arm cap rather than sharing
        // equal-radius cubic arcs, which failed native Skia union under foreshortening.
        bone(ink,camera,side+"_upper_arm",shoulder,elbow,12,9,coat,false,.3);bone(ink,camera,side+"_forearm",elbow,wrist,9.5,5.2,coat,false,1);
        ink.part(side+"_elbow_blend",d->ellipse(d,coat,null,1,elbowPoint[0],elbowPoint[1],9.7,9.7));
        ink.part(side+"_shoulder_blend",d->ellipse(d,coat,null,1,shoulderPoint[0],shoulderPoint[1],8.1,8.1));
        sleeveContour(ink,side,shoulderPoint,camera.point(p.shoulderCentre));
        JungleCelInk fold=ink.drawing.at(elbowPoint[0],elbowPoint[1],1,Math.toDegrees(hand.forearmAngle));
        ink.part(side+"_sleeve_fold",fold,q->pen(q,null,tone(coat,-.28),.5).m(-4,-3).q(-1,-1,2,-2).end());
        }
        ink.part(side+"_wrist",d->ellipse(d,skin,INK,.5,b[0],b[1],5.6,5.6));
        JungleCelInk cuff=ink.drawing.at(b[0],b[1],1,Math.toDegrees(hand.forearmAngle));
        ink.part(side+"_cuff",cuff,q->pen(q,tone(coat,.20),INK,.5).m(-5.8,-9).q(0,-10,5.8,-9).l(5.1,.5).q(0,2,-5.1,.5).z().end());
        JungleCelInk d=ink.drawing.at(b[0],b[1],1,Math.toDegrees(hand.angle));
        ink.part(side+"_hand",d,q->pen(q,skin,INK,.55).m(-4.8,-2).q(0,-3,4.8,-2).c(6,3,7,9,6.8,12).q(6,19,0,20).q(-6,19,-6.5,12).z().end());
        String[] fingers={"index","middle","ring","little"};double[] lengths={15,18,16,12};
        for(int i=0;i<4;i++){final double x=-4.7+i*3.15,length=lengths[i],tipX=x*open+i*1.7*(1-open),tipY=13+length*open+(2+i*.7)*(1-open);final String finger=fingers[i];
            ink.part(side+"_finger_"+finger,d,q->pen(q,skin,INK,.42).m(x-1.55,12).c(x-1.75,18+length*.25*open,tipX-1.5-2.5*(1-open),tipY-2+5*(1-open),tipX-.8,tipY).q(tipX+.4,tipY+2,tipX+1.2,tipY-1).c(tipX+1.5,tipY-3,x+1.6,14,x+1.6,12).z().end());
        }
        ink.part(side+"_thumb",d,q->pen(q,skin,INK,.48).m(4,5).q(8,3,9,7).c(10+3*open,10,4+12*open,13,1+12*open,13.5).q(-2+12*open,13,-2+12*open,15).q(-1+12*open,18,1.5+12*open,17).l(6,13).z().end());
        ink.part(side+"_palm_crease",d,q->pen(q,null,tone(skin,-.35),.38).m(-3,9).q(1,6,5,9).m(-3,15).q(0,12,4,14).end());
    }
    private static void tailoredSleeve(Painter ink,String side,SketchHumanClothingGeometry.Limb cloth,String color,double elbowDepth)throws Exception{
        ink.paintOrder=-10;ink.part(side+"_sleeve_surface",d->cloth.surface.fill(pen(d,color,null,1)));
        ink.part(side+"_sleeve_tension",d->cloth.surface.band(.28).fill(pen(d,tone(color,-.12),null,1)));
        for(SketchHumanClothingGeometry.Panel panel:cloth.panels){
            ink.paintOrder=(panel.depth>=0?30:-10)+panel.depth*.0001;
            if(panel.depth>=0){ink.part(side+"_sleeve_front",d->panel.fill(pen(d,color,null,1)));ink.part(side+"_sleeve_tension",d->panel.band(.28).fill(pen(d,tone(color,-.12),null,1)));}
            ink.part(side+"_sleeve_edge",d->panel.edges(pen(d,null,INK,.8)));
        }
        ink.paintOrder=(elbowDepth>=0?30:-10)+elbowDepth*.0001+.00001;
        for(SketchHumanClothingGeometry.Fold fold:cloth.folds)ink.part(side+"_sleeve_compression",d->fold.trace(pen(d,tone(color,-.25),null,1)));
    }
    /** One external sleeve edge; filled Bezier panels retain their own editable geometry.
     * Flatten only the edge union to 0.08 model pixels, avoiding coincident cubic seams. */
    private static void sleeveContour(Painter ink,String side,double[] shoulder,double[] centre)throws Exception {
        Path silhouette=new Path();JSONArray objects=ink.layer.getJSONArray("objects");
        for(String part:new String[]{"shoulder","elbow","upper_arm","forearm"}){
            for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(!object.getString("id").equals(ink.actor+"_"+side+"_"+part+"_0"))continue;
                float[] points=SketchFrameRenderer.path(object.getJSONArray("commands"),1280,720).approximate(.005f);Path panel=new Path();
                for(int j=0;j<points.length;j+=3){if(j==0||points[j]==points[j-3]){if(j>0)panel.close();panel.moveTo(points[j+1],points[j+2]);}else panel.lineTo(points[j+1],points[j+2]);}panel.close();
                if(!silhouette.op(panel,Path.Op.UNION))throw SketchDocument.bad("Cannot join native sleeve contours");
            }
        }
        float[] points=silhouette.approximate(.005f);double ix=centre[0]-shoulder[0],iy=centre[1]-shoulder[1],length=Math.hypot(ix,iy);final double inwardX=ix/Math.max(1,length),inwardY=iy/Math.max(1,length);final boolean profile=length<1;
        ink.part(side+"_sleeve_contour",d->{JungleCelInk.Shape edge=pen(d,null,INK,.8);boolean connected=false;
            for(int i=3;i<points.length;i+=3){if(points[i]==points[i-3]){connected=false;continue;}double ax=(points[i-2]-640)*16,ay=(points[i-1]-360)*16,bx=(points[i+1]-640)*16,by=(points[i+2]-360)*16,mx=(ax+bx)/2-shoulder[0],my=(ay+by)/2-shoulder[1];
                // The sleeve enters the coat: do not outline an internal shoulder cap.
                if(Math.hypot(mx,my)<18&&(profile||mx*inwardX+my*inwardY>-2)){connected=false;continue;}
                if(!connected)edge.m(ax,ay);edge.l(bx,by);connected=true;
            }edge.end();
        });
    }
    private static void leg(Painter ink,Camera camera,Skeleton p,boolean left,String trousers)throws Exception {
        String side=left?"left":"right";double[] hip=left?p.leftHip:p.rightHip,knee=left?p.leftKnee:p.rightKnee,ankle=left?p.leftAnkle:p.rightAnkle,foot=left?p.leftFoot:p.rightFoot;
        if(ink.tailored){
            SketchHumanClothingGeometry.DepthPlane torso=new SketchHumanClothingGeometry.DepthPlane(camera,p.hip,p.shoulderCentre);
            SketchHumanClothingGeometry.Limb cloth=SketchHumanClothingGeometry.limb(camera,hip,knee,ankle,ink.broad?14:13,9,6.5,0,4,torso);
            ink.paintOrder=-21;ink.part(side+"_trouser_surface",d->cloth.surface.fill(pen(d,trousers,null,1)));ink.part(side+"_trouser_tension",d->cloth.surface.band(.28).fill(pen(d,tone(trousers,-.12),null,1)));
            for(SketchHumanClothingGeometry.Panel panel:cloth.panels){ink.paintOrder=-20+panel.depth*.0001;ink.part(side+"_trouser_panel",d->panel.fill(pen(d,trousers,null,1)));ink.part(side+"_trouser_tension",d->panel.band(.28).fill(pen(d,tone(trousers,-.12),null,1)));ink.part(side+"_trouser_edge",d->panel.edges(pen(d,null,INK,.85)));}
            ink.paintOrder=-20+torso.relative(knee)*.0001+.00001;
            for(SketchHumanClothingGeometry.Fold fold:cloth.folds)ink.part(side+"_trouser_compression",d->fold.trace(pen(d,tone(trousers,-.22),null,1)));
            ink.paintOrder=-20+torso.relative(ankle)*.0001+.00001;
        }else{
        bone(ink,camera,side+"_thigh",hip,knee,13,10,trousers);bone(ink,camera,side+"_shin",knee,ankle,10,6.5,trousers);
        }
        double[] k=camera.point(knee),a=camera.point(ankle),f=camera.point(foot);double dir=camera.profile?(camera.view.equals("left")?-1:1):(left?-1:1),dx=a[0]-f[0],dy=a[1]-f[1];
        if(!ink.tailored)ink.part(side+"_trouser_fold",d->pen(d,null,tone(trousers,.25),.55).m(k[0]-5,k[1]-6).q(k[0]+2,k[1]-3,k[0]+6,k[1]-7).m(k[0]-3,k[1]+5).q(a[0]-6,a[1]-18,a[0]-3,a[1]-8).end());
        JungleCelInk d=ink.drawing.at(f[0],f[1],1,0);
        ink.part(side+"_shoe",d,q->pen(q,tone(trousers,-.25),INK,.8).m(dx-6.5,dy-2).l(dx+6.5,dy-2).l(dx+7,-7).q(dir*18,-7,dir*23,-3).q(dir*25,0,dir*19,0).l(-dir*10,0).q(-dir*12,-4,dx-6.5,dy-2).z().end());
        ink.part(side+"_shoe_detail",d,q->pen(q,null,tone(trousers,.45),.5).m(-dir*9,-1.5).l(dir*20,-1.5).m(dx-4,dy+4).l(dx+4,dy+4).end());
    }

    private static void torso(Painter ink,Camera camera,Skeleton p,JSONObject s)throws Exception {
        if(ink.tailored)ink.paintOrder=0;
        String coat=s.getString("coatColor"),skin=s.getString("skinColor");boolean broad=s.getString("build").equals("broad");
        double[] hip=camera.point(p.hip),shoulder=camera.point(p.shoulderCentre);double height=Math.hypot(shoulder[0]-hip[0],shoulder[1]-hip[1]),angle=Math.toDegrees(Math.atan2(shoulder[0]-hip[0],hip[1]-shoulder[1]));
        JungleCelInk d=ink.drawing.at(hip[0],hip[1],1,angle);double w=camera.profile?(broad?30:24):(broad?51:43),waist=camera.profile?w*.84:w*.79,top=-height;
        // Illustrated adults get a higher neck root and sloping trapezius coverage.
        // This changes garment ink only; graphic archives and every joint stay fixed.
        double lift=s.getString("faceStyle").equals("graphic")?0:12;
        bone(ink,camera,"neck",body(p,0,-125,0),p.neck,10,7.5,skin);
        if(ink.tailored){
            double nearPull=SketchHumanClothingGeometry.armPull(p.nearShoulder,p.nearElbow),farPull=SketchHumanClothingGeometry.armPull(p.farShoulder,p.farElbow);
            double right=camera.point(p.nearShoulder)[0]>=shoulder[0]?nearPull:farPull,left=camera.point(p.nearShoulder)[0]>=shoulder[0]?farPull:nearPull;
            double compression=Math.max(0,(SketchHumanClothingGeometry.compression(p.leftHip,p.leftKnee,p.leftAnkle)+SketchHumanClothingGeometry.compression(p.rightHip,p.rightKnee,p.rightAnkle))*.5-.25);
            ink.part("coat",d,q->pen(q,coat,INK,1.05).m(-w,top-7).c(-w+4,top-9,-17,top-10-lift*2/3,-11,top-5-lift).q(0,top+5-lift,11,top-5-lift).c(24,top-9-lift*2/3,w-3,top-9,w,top-6).c(w+1+right*2,top+35-right*8,waist-2-compression*3,-24-compression*7,waist,2).q(waist+1,13,waist+5,20).q(0,28,-waist-5,20).q(-waist-1,12,-waist,2).c(-waist+2+compression*3,-24-compression*7,-w-1-left*2,top+35-left*8,-w,top-7).z().end());
            ink.part("coat_compression",d,q->pen(q,tone(coat,-.18),null,1).m(-waist+6,-22).c(-waist+12,-24-compression*5,-11,-21-compression*9,-5,-23).c(-13,-19-compression*5,-waist+9,-20,-waist+6,-22).z().end());
        }else ink.part("coat",d,q->pen(q,coat,INK,1.05).m(-w,top-7).c(-w+4,top-9,-17,top-10-lift*2/3,-11,top-5-lift).q(0,top+5-lift,11,top-5-lift).c(24,top-9-lift*2/3,w-3,top-9,w,top-6).c(w+1,top+35,waist-2,-24,waist,2).q(waist+1,13,waist+5,20).q(0,28,-waist-5,20).q(-waist-1,12,-waist,2).c(-waist+2,-24,-w-1,top+35,-w,top-7).z().end());
        ink.part("coat_shade",d,q->pen(q,tone(coat,-.10),null,1).m(-w+3,top+6).q(-w+8,top+60,-waist+3,5).l(-waist-3,18).q(-12,21,-5,22).l(-10,top+20).z().end());
        if(camera.back){
            ink.part("back_seam",d,q->pen(q,null,tone(coat,.27),.6).m(0,top+15).q(-2,top+60,0,19).m(-w+7,top+22).q(0,top+9,w-7,top+22).end());
            ink.part("back_vent",d,q->pen(q,null,INK,.55).m(0,2).l(-3,21).m(0,2).l(4,21).end());
        }else if(camera.profile){
            double facing=camera.view.equals("left")?-1:1;
            ink.part("side_seam",d,q->pen(q,null,tone(coat,.30),.55).m(-facing*8,top+17).q(-facing*14,top+68,-facing*7,17).end());
            ink.part("collar",d,q->pen(q,tone(coat,.25),INK,.55).m(-12,top-5-lift).l(6,top-8-lift).l(facing*17,top+10).l(0,top+19).z().end());
            ink.part("side_pocket",d,q->pen(q,null,INK,.6).m(facing*3,-37).l(facing*17,-33).l(facing*12,-17).end());
        }else{
            ink.part("shirt",d,q->pen(q,"#FFE8E2D4",INK,.5).m(-12,top-5-lift).q(0,top+3-lift,12,top-5-lift).l(5,top+45).l(-5,top+45).z().end());
            ink.part("lapel",d,q->pen(q,tone(coat,.21),INK,.65).m(-12,top-7-lift).l(-23,top+10).l(-12,top+19).l(-16,top+30).l(-2,top+55).l(-6,top+12).z().end());
            ink.part("lapel",d,q->pen(q,tone(coat,.12),INK,.65).m(12,top-7-lift).l(23,top+10).l(12,top+19).l(16,top+30).l(2,top+55).l(6,top+12).z().end());
            ink.part("coat_seam",d,q->pen(q,null,INK,.55).m(1,top+49).q(4,top+82,2,21).end());
            for(int i=0;i<3;i++){final double y=top+65+i*20;ink.part("coat_button",d,q->ellipse(q,"#FFD8C5A2",INK,.4,5,y,1.35,1.35));}
            ink.part("pockets",d,q->pen(q,null,tone(coat,-.38),.65).m(-waist+5,-32).l(-10,-29).m(12,-29).l(waist-3,-32).end());
        }
        ink.part("coat_folds",d,q->pen(q,null,tone(coat,.32),.55).m(-w+9,top+24).q(-w+15,top+37,-w+16,top+49).m(w-10,top+26).q(w-15,top+37,w-16,top+47).m(-waist,12).q(-12,16,-5,15).m(8,15).q(19,13,waist,16).end());
    }

    /** The rear hair mass is below torso, neck and head in the native painter order. */
    private static void rearHair(Painter ink,Camera camera,Skeleton p,JSONObject s)throws Exception {
        if(ink.tailored)ink.paintOrder=-50;
        if(!s.getString("hairStyle").equals("long"))return;
        double[] centre=camera.point(p.head),neck=camera.point(p.neck);double angle=Math.toDegrees(Math.atan2(centre[0]-neck[0],neck[1]-centre[1]));
        JungleCelInk d=ink.drawing.at(centre[0],centre[1],1,angle);String hair=s.getString("hairColor");double direction=camera.profile?(camera.view.equals("left")?1:-1):1;
        ink.part("long_hair",d,q->pen(q,hair,INK,.75).m(-22,-15).c(-29,1,-24,27,-29,52).q(-18,66,-4,58).q(13,65,27,52).c(22,31,29,4,22,-15).q(0,-39,-22,-15).z().end());
        ink.part("long_hair_strands",d,q->pen(q,null,tone(hair,.27),.5).m(-19,-3).c(-24,16,-16,38,-23,53).m(18,-1).c(23,20,14,41,21,54).m(direction*13,18).q(direction*8,40,direction*14,56).end());
    }
    /** A true side silhouette includes forehead, nose, lips and chin; rear hides facial features. */
    private static void head(Painter ink,Camera camera,Skeleton p,JSONObject s)throws Exception {
        if(ink.tailored)ink.paintOrder=20;
        double[] centre=camera.point(p.head),neck=camera.point(p.neck);double angle=Math.toDegrees(Math.atan2(centre[0]-neck[0],neck[1]-centre[1]));
        JungleCelInk d=ink.drawing.at(centre[0],centre[1],1,angle);String skin=s.getString("skinColor"),hair=s.getString("hairColor");
        if(!s.getString("faceStyle").equals("graphic")){SketchHumanFace.draw(d,ink.layer,ink.actor,s,p.controls,camera.view,camera.view.equals("high")?.88:camera.view.equals("low")?1.03:1);return;}
        JSONObject pose=p.controls;double gazeX=pose.getDouble("gazeX"),gazeY=pose.getDouble("gazeY"),brow=pose.getDouble("brow"),blink=pose.getDouble("blink");
        if(camera.back){
            ink.part("head",d,q->pen(q,hair,INK,.9).m(-20,-13).c(-22,-28,-7,-31,5,-29).c(24,-28,24,-8,21,8).q(16,22,6,25).l(-8,25).q(-21,13,-20,-13).z().end());
            ink.part("nape",d,q->pen(q,tone(hair,.17),null,1).m(-16,4).q(0,13,17,4).l(13,21).q(0,25,-12,21).z().end());
            ink.part("hair_strands",d,q->pen(q,null,tone(hair,.35),.48).m(-12,-18).q(-17,-3,-11,12).m(-3,-23).q(3,-8,0,9).m(10,-20).q(18,-7,12,11).end());return;
        }
        boolean high=camera.view.equals("high"),low=camera.view.equals("low");double yScale=high?.88:low?1.03:1;
        Face f=new Face(d,camera.profile?(camera.view.equals("left")?-1:1):1,yScale);
        if(camera.profile){
            ink.part("head",d,q->f.pen(skin,INK,.8).m(-12,-17).c(-4,-30,12,-25,15,-15).q(18,-9,16,-4).l(23,3).q(25,7,18,8).l(19,12).q(23,14,18,16).q(18,23,8,26).l(-5,24).q(-17,15,-15,1).z().end());
            ink.part("cheek_shade",d,q->f.pen(tone(skin,-.18),null,1).m(-10,-7).q(-5,10,7,16).l(9,24).q(-3,24,-9,14).z().end());
            ink.part("ear",d,q->f.pen(skin,INK,.45).m(-7,-3).q(-16,-9,-14,3).q(-12,13,-6,9).l(-3,1).z().end());
            ink.part("ear_detail",d,q->f.pen(null,tone(skin,-.35),.4).m(-10,-1).q(-5,-3,-8,5).l(-10,7).end());
            eye(ink,d,f,"eye_near",10,-3,4.3,gazeX,gazeY,brow,blink,hair);
            ink.part("nose_detail",d,q->f.pen(null,tone(skin,-.35),.4).m(18,6).q(16,5,15,7).end());
            lips(ink,d,f,15,14,3.4,pose);
            ink.part("hair",d,q->f.pen(hair,INK,.65).m(-16,5).q(-24,-8,-15,-24).c(-6,-36,12,-31,16,-20).q(19,-17,16,-12).q(11,-14,8,-19).q(-1,-11,-7,-14).l(-9,5).z().end());
            ink.part("hair_strands",d,q->f.pen(null,tone(hair,.35),.45).m(-15,-15).q(-8,-27,7,-24).m(-14,-7).q(-9,-16,-6,-17).end());
        }else{
            ink.part("ears",d,q->{ellipse(q,skin,INK,.45,-21,3,4,7);ellipse(q,skin,INK,.45,21,3,4,7);});
            ink.part("head",d,q->f.pen(skin,INK,.8).m(-20,-14).c(-23,-2,-18,15,-9,23).q(0,31,10,23).c(19,15,23,-2,19,-16).q(1,-32,-20,-14).z().end());
            ink.part("cheek_shade",d,q->f.pen(tone(skin,-.16),null,1).m(-20,-10).q(-22,10,-10,22).l(0,27).l(5,24).q(-9,17,-12,4).l(-10,-17).z().end());
            ink.part("face_light",d,q->f.pen(tone(skin,.15),null,1).m(-6,-19).q(8,-23,15,-11).l(16,-4).q(4,-10,-6,-5).z().end());
            double eyeY=high?4:low?-3:0;eye(ink,d,f,"eye_near",9,eyeY,5.2,gazeX,gazeY,brow,blink,hair);eye(ink,d,f,"eye_far",-9,eyeY+.3,4.9,gazeX,gazeY,brow,blink,hair);
            ink.part("nose",d,q->f.pen(tone(skin,-.16),null,1).m(1,-5).q(1,3,5,8).q(2,11,-3,9).l(-4,7).q(1,8,1,-5).z().end());
            ink.part("nose_detail",d,q->f.pen(null,tone(skin,-.37),.4).m(-3,9).q(0,7,3,10).m(4,7).q(7,9,4,10).end());
            lips(ink,d,f,0,16,7,pose);
            if(low)ink.part("jaw_plane",d,q->f.pen(tone(skin,-.23),null,1).m(-11,20).q(0,30,12,20).l(6,26).q(0,30,-6,26).z().end());
            ink.part("hair",d,q->f.pen(hair,INK,.65).m(-21,1).q(-28,-17,-14,-27).c(-2,-35,14,-31,20,-20).l(21,-7).q(15,-8,12,-18).q(3,-10,-8,-10).l(-12,-15).q(-17,-6,-18,4).z().end());
            ink.part("hair_strands",d,q->f.pen(null,tone(hair,.36),.43).m(-18,-17).q(-7,-29,7,-25).m(-15,-11).q(-8,-21,2,-22).m(7,-24).q(16,-23,17,-15).end());
            if(high)ink.part("crown",d,q->f.pen(tone(hair,.18),null,1).m(-14,-25).q(1,-35,15,-25).q(3,-25,-4,-20).z().end());
        }
    }
    private static void eye(Painter ink,JungleCelInk d,Face f,String name,double x,double y,double w,double gazeX,double gazeY,double brow,double blink,String hair)throws Exception {
        double open=1-blink;
        if(open>0){
            ink.part(name,d,q->f.pen("#FFF4EDE1",INK,.4).m(x-w,y).q(x,y-3*open,x+w,y).q(x,y+2*open,x-w,y).z().end());
            ink.part(name+"_iris",d,q->oval(f,"#FF3B4C48",null,1,x+gazeX*1.2,y+(-.25+gazeY*.45)*open,1.15,.6*open).end());
            ink.part(name+"_lid",d,q->f.pen(null,"#FF805C53",.36).m(x-w+.5,y+3*open).q(x,y+4*open,x+w-.5,y+2.6*open).end());
        }else ink.part(name+"_closed_lid",d,q->f.pen(null,INK,.65).m(x-w,y).q(x,y+.8,x+w,y).end());
        ink.part(name+"_brow",d,q->f.pen(hair,null,1).m(x-w,y-5-brow*2.6).q(x,y-7-brow*2.4,x+w,y-5+brow).l(x+w-.3,y-4+brow).q(x,y-5-brow*2.4,x-w,y-4-brow*2.6).z().end());
    }
    private static void lips(Painter ink,JungleCelInk d,Face f,double x,double y,double width,JSONObject pose)throws Exception {
        double mouth=pose.getDouble("mouth"),smile=pose.getDouble("smile"),wide=pose.getDouble("mouthWide"),round=pose.getDouble("mouthRound"),teeth=pose.getDouble("mouthTeeth"),tongue=pose.getDouble("mouthTongue");
        width*= (1+.45*wide)*(1-.45*round)*(1+.08*smile);
        double corner=y-smile*2,height=(.6+mouth*3)*(1+.5*round)*(1-.15*smile),cy=y+height*.45-smile*.6;
        final double radiusX=width,radiusY=height,centreY=cy;
        if(mouth>.02){
            ink.part("mouth",d,q->oval(f,"#FF57363B","#FF744C4A",.4,x,centreY,radiusX,radiusY).end());
            // Both ellipses are strictly inscribed in the cavity (including their full
            // cubic surfaces), so no tooth or tongue can leak through a lip at any size.
            if(teeth>0)ink.part("teeth",d,q->oval(f,"#FFF1DFCA",null,1,x,centreY-radiusY*.35,radiusX*.68,radiusY*.18*teeth).end());
            if(tongue>0)ink.part("tongue",d,q->oval(f,"#FFB96B74",null,1,x,centreY+radiusY*.35,radiusX*.58,radiusY*.28*tongue).end());
        }else ink.part("mouth",d,q->f.pen(null,"#FF80504F",.65).m(x-radiusX,corner).q(x,y+1,x+radiusX,corner).end());
        ink.part("lower_lip",d,q->f.pen(null,"#FFA87567",.4).m(x-radiusX*.45,centreY+radiusY+2).q(x,centreY+radiusY+3,x+radiusX*.5,centreY+radiusY+1.5).end());
    }
    private static FacePath oval(Face f,String fill,String outline,double weight,double x,double y,double rx,double ry)throws Exception {
        double k=.55228475;return f.pen(fill,outline,weight).m(x-rx,y).c(x-rx,y-k*ry,x-k*rx,y-ry,x,y-ry).c(x+k*rx,y-ry,x+rx,y-k*ry,x+rx,y).c(x+rx,y+k*ry,x+k*rx,y+ry,x,y+ry).c(x-k*rx,y+ry,x-rx,y+k*ry,x-rx,y).z();
    }
    /** Face-local Beziers retain actual profile reflection and high/low surface compression. */
    private static final class Face {
        final JungleCelInk d;final double side,vertical;Face(JungleCelInk d,double side,double vertical){this.d=d;this.side=side;this.vertical=vertical;}
        FacePath pen(String fill,String outline,double weight){return new FacePath(SketchHumanFigure.pen(d,fill,outline,weight),side,vertical);}
    }
    private static final class FacePath {
        final JungleCelInk.Shape path;final double sx,sy;FacePath(JungleCelInk.Shape path,double sx,double sy){this.path=path;this.sx=sx;this.sy=sy;}
        FacePath m(double x,double y)throws Exception{path.m(x*sx,y*sy);return this;}FacePath l(double x,double y)throws Exception{path.l(x*sx,y*sy);return this;}
        FacePath q(double a,double b,double c,double d)throws Exception{path.q(a*sx,b*sy,c*sx,d*sy);return this;}
        FacePath c(double a,double b,double c,double d,double e,double f)throws Exception{path.c(a*sx,b*sy,c*sx,d*sy,e*sx,f*sy);return this;}
        FacePath z(){path.z();return this;}void end()throws Exception{path.end();}
    }
    private static String tone(String color,double amount){int c=Color.parseColor(color);double mix=Math.abs(amount);int goal=amount<0?0:255;return String.format(Locale.ROOT,"#%02X%02X%02X%02X",Color.alpha(c),(int)Math.round(Color.red(c)*(1-mix)+goal*mix),(int)Math.round(Color.green(c)*(1-mix)+goal*mix),(int)Math.round(Color.blue(c)*(1-mix)+goal*mix));}
    private static double[] v(double x,double y,double z){return new double[]{x,y,z};}
    private static double[] add(double[] a,double[] b){return v(a[0]+b[0],a[1]+b[1],a[2]+b[2]);}
    private static double[] sub(double[] a,double[] b){return v(a[0]-b[0],a[1]-b[1],a[2]-b[2]);}
    private static double[] mul(double[] a,double t){return v(a[0]*t,a[1]*t,a[2]*t);}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double length(double[] a){return Math.sqrt(dot(a,a));}
    private static double[] rotate(double[] point,double[] axis,double angle){double c=Math.cos(angle),s=Math.sin(angle);double[] cross=v(axis[1]*point[2]-axis[2]*point[1],axis[2]*point[0]-axis[0]*point[2],axis[0]*point[1]-axis[1]*point[0]);return add(add(mul(point,c),mul(cross,s)),mul(axis,dot(axis,point)*(1-c)));}
}
