package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import org.json.*;
import java.util.*;

/** Reusable retained human acting, shared by manual controls, MCP, preview and export. */
final class SketchHumanAnimation {
    private SketchHumanAnimation(){}
    static void validatePoses(JSONArray poses,int frames)throws Exception {
        if(poses.length()<1||poses.length()>240)throw SketchDocument.bad("Use1..240 human poses");int previous=-1;
        for(int i=0;i<poses.length();i++){
            JSONObject p=poses.getJSONObject(i);SketchDocument.fields(p,"frame,interpolation,curve,"+String.join(",",SketchHumanFigure.POSE_CHANNELS));int frame=(int)SketchDocument.integer(p,"frame",0,frames-1);if(frame<=previous||i==0&&frame!=0)throw SketchDocument.bad("Human poses must begin at zero and strictly increase");previous=frame;SketchEasing.validate(p);SketchHumanFigure.validatePose(channels(p));
        }
    }
    private static JSONObject channels(JSONObject p)throws Exception {JSONObject out=new JSONObject();for(String key:SketchHumanFigure.POSE_CHANNELS)if(p.has(key))out.put(key,p.get(key));return out;}
    static JSONObject poseAt(JSONArray poses,int frame)throws Exception {
        JSONObject left=poses.getJSONObject(0),right=left;for(int i=1;i<poses.length();i++){right=poses.getJSONObject(i);if(frame<right.getInt("frame"))break;left=right;}
        JSONObject a=SketchHumanFigure.validatePose(channels(left)),b=SketchHumanFigure.validatePose(channels(right));double t=left==right?0:SketchEasing.sample(left.optString("interpolation","linear"),left.optJSONArray("curve"),(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame")));
        JSONObject result=new JSONObject();for(String key:SketchHumanFigure.POSE_CHANNELS)result.put(key,a.getDouble(key)+(b.getDouble(key)-a.getDouble(key))*t);return result;
    }
    static int localFrame(JSONObject object,int frame)throws Exception {JSONObject h=object.getJSONObject("human");return h.has("renderFrame")?h.getInt("renderFrame"):Math.min(h.getInt("durationFrames")-1,Math.max(0,frame-object.getInt("frame")+h.optInt("sourceFrameOffset",0)));}
    static JSONObject sampled(JSONObject object,int frame)throws Exception {JSONObject result=SketchDocument.copy(object),human=result.getJSONObject("human"),frozen=pose(object,frame).put("frame",0).put("interpolation","hold");human.put("poses",new JSONArray().put(frozen)).put("renderFrame",localFrame(object,frame));human.remove("speech");human.remove("blinkTrack");return result;}
    static JSONObject pose(JSONObject object,int frame)throws Exception {
        JSONObject h=object.getJSONObject("human");int local=localFrame(object,frame);JSONObject p=poseAt(h.getJSONArray("poses"),local);
        if(h.has("speech")){double[] v=SketchSpeechTiming.sample(h.getJSONObject("speech"),local*1000d/h.getInt("fps"));p.put("mouth",Math.min(1,v[0])).put("mouthWide",Math.max(-1,Math.min(1,(v[1]-1)/.5))).put("smile",Math.max(-1,Math.min(1,p.getDouble("smile")+v[2]))).put("mouthRound",v[7]).put("mouthTeeth",Math.max(v[5],v[6])).put("mouthTongue",v[4]);}
        if(h.has("blinkTrack"))p.put("blink",Math.max(p.getDouble("blink"),SketchHumanBlink.sample(h.getJSONObject("blinkTrack"),local*1000d/h.getInt("fps"))));return p;
    }
    static JSONArray presetPoses(String preset,int frames,int fps,JSONObject motion,String view,JSONObject overrides)throws Exception {
        JSONObject resolved=SketchHumanPresets.motion(motion),flat=SketchDocument.copy(resolved).put("groundSlope",0).put("groundSlopeX",0);JSONArray poses=SketchHumanPresets.createForView(preset,frames,fps,flat,view);SketchHumanFigure.validatePose(overrides);for(int i=0;i<poses.length();i++){JSONObject p=poses.getJSONObject(i);java.util.Iterator<String> keys=overrides.keys();while(keys.hasNext()){String key=keys.next();p.put(key,overrides.get(key));}JSONObject placed=SketchHumanGround.applyPlane(channels(p),resolved.getDouble("groundSlopeX"),resolved.getDouble("groundSlope"),preset.equals("walk")||preset.equals("run"));if(preset.equals("kneel")&&resolved.getDouble("strength")==1&&p.getInt("frame")>=frames/2&&!overrides.has("hipY")&&!overrides.has("rootY"))placed=SketchHumanKneelContact.apply(placed,resolved.getDouble("groundSlopeX"),resolved.getDouble("groundSlope"));for(String key:SketchHumanFigure.POSE_CHANNELS)p.put(key,placed.get(key));}return poses;
    }
    static void apply(JSONObject doc,JSONObject op)throws Exception {
        SketchDocument.fields(op,"type,layerId,actorId,settings,preset,poses,speech,motion,poseOverrides,fit,blinkTrack,ground");if(op.has("fit"))SketchDocument.bool(op,"fit");String id=SketchDocument.id(op,"actorId"),layerId=SketchDocument.id(op,"layerId");if(id.length()>20)throw SketchDocument.bad("Actor identity must fit20characters");JSONObject layer=SketchDocument.layer(doc,layerId);if(layer.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<objects.length();i++)if(objects.getJSONObject(i).getString("id").startsWith(id+"_"))throw SketchDocument.bad("Actor identity already exists");}
        int frames=doc.getInt("frameCount"),fps=doc.getInt("fps");if(frames<24||frames>1200||frames/(double)fps>40)throw SketchDocument.bad("Human acting needs24..1200frames and at most40seconds");
        JSONObject settings=SketchHumanFigure.settings(op.getJSONObject("settings"));String preset=op.optString("preset","listen");if(!Arrays.asList(SketchHumanPresets.NAMES).contains(preset))throw SketchDocument.bad("Unknown human acting preset");
        if(op.has("motion")){SketchHumanPresets.motion(op.getJSONObject("motion"));if(op.has("poses"))throw SketchDocument.bad("Choose explicit human poses or motion preset controls");}
        JSONObject motion=op.optJSONObject("motion")==null?new JSONObject():SketchDocument.copy(op.getJSONObject("motion"));
        if(op.has("ground")){
            if(!(op.get("ground") instanceof String))throw SketchDocument.bad("Ground must be a native scene location");
            if(op.optBoolean("fit",false)||op.has("poses")||motion.has("groundSlope")||motion.has("groundSlopeX"))throw SketchDocument.bad("Choose scene ground or explicit poses, motion slopes and solo framing");
            JSONObject placement=SketchHumanGround.placement(doc,settings,op.getString("ground"));settings.put("floorY",placement.getDouble("floorY"));motion.put("groundSlope",placement.getDouble("groundSlope")).put("groundSlopeX",placement.getDouble("groundSlopeX"));
        }
        if(op.has("poses")&&op.has("poseOverrides"))throw SketchDocument.bad("Choose explicit human poses or preset pose overrides");
        JSONArray poses=op.has("poses")?new JSONArray(op.getJSONArray("poses").toString()):presetPoses(preset,frames,fps,motion,settings.getString("view"),op.optJSONObject("poseOverrides")==null?new JSONObject():op.getJSONObject("poseOverrides"));validatePoses(poses,frames);
        JSONObject human=new JSONObject().put("settings",settings).put("poses",poses).put("durationFrames",frames).put("fps",fps);if(op.has("speech"))human.put("speech",SketchSpeechTiming.resolve(op.getJSONObject("speech"),frames*1000d/fps));
        if(op.has("blinkTrack")){if(!Boolean.FALSE.equals(op.get("blinkTrack")))human.put("blinkTrack",SketchHumanBlink.settings(op.getJSONObject("blinkTrack")));}else if(!op.has("poses"))human.put("blinkTrack",SketchHumanBlink.defaults());
        JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id",id+"_rig").put("kind","human").put("human",human).put("frame",0).put("exposure",frames));if(op.optBoolean("fit",false))fitPlacement(doc,object);refreshBounds(doc,object);
        JSONObject candidate=SketchDocument.copy(doc);SketchDocument.layer(candidate,layerId).getJSONArray("objects").put(object);SketchDocument.validate(candidate);layer.getJSONArray("objects").put(object);
    }
    static void validate(JSONObject doc,JSONObject object)throws Exception {
        JSONObject h=object.getJSONObject("human");SketchDocument.fields(h,"settings,poses,durationFrames,fps,renderFrame,speech,parts,sourceFrameOffset,blinkTrack");SketchHumanFigure.settings(h.getJSONObject("settings"));int frames=(int)SketchDocument.integer(h,"durationFrames",24,1200),fps=(int)SketchDocument.integer(h,"fps",24,60);if(!Set.of(24,30,60).contains(fps)||frames/(double)fps>40)throw SketchDocument.bad("Invalid human acting clock");validatePoses(h.getJSONArray("poses"),frames);if(h.has("parts")&&!Set.of("all","without_near_hand","without_far_hand","near_hand","far_hand").contains(h.getString("parts")))throw SketchDocument.bad("Unknown human rendering part");if(h.has("renderFrame"))SketchDocument.integer(h,"renderFrame",0,frames-1);if(h.has("sourceFrameOffset"))SketchDocument.integer(h,"sourceFrameOffset",-1200,1200);if(h.has("blinkTrack"))SketchHumanBlink.settings(h.getJSONObject("blinkTrack"));if(h.has("speech"))SketchSpeechTiming.validate(h.getJSONObject("speech"),frames*1000d/fps);
        if(object.has("commands")||object.has("gradient")||object.has("shapeKeys")||object.has("styleId"))throw SketchDocument.bad("Human acting uses its editable settings and joint poses");
        for(int f=object.getInt("frame");f<object.getInt("frame")+object.getInt("exposure");f++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Human construction cancelled");bounds(object,doc.getInt("width"),doc.getInt("height"),f);}
    }
    static void refreshBounds(JSONObject doc,JSONObject object)throws Exception {
        RectF all=new RectF();for(int f=object.getInt("frame");f<object.getInt("frame")+object.getInt("exposure");f++){RectF b=bounds(object,doc.getInt("width"),doc.getInt("height"),f);if(f==object.getInt("frame"))all.set(b);else all.union(b);}
        double w=doc.getInt("width"),h=doc.getInt("height");object.put("bounds",new JSONObject().put("x",all.left/w).put("y",all.top/h).put("width",all.width()/w).put("height",all.height()/h));
    }
    private static void fitPlacement(JSONObject doc,JSONObject object)throws Exception {
        int w=doc.getInt("width"),h=doc.getInt("height");RectF all=new RectF();for(int f=0;f<doc.getInt("frameCount");f++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Human framing cancelled");RectF b=jointBounds(object,w,h,f);if(f==0)all.set(b);else all.union(b);}
        float margin=4;if(all.width()>w-2*margin||all.height()>h-2*margin)throw SketchDocument.bad("Motion cannot fit at this actor size; use a shorter bounded shot or adjust your scene camera");
        float dx=all.left<margin?margin-all.left:all.right>w-margin?w-margin-all.right:0,dy=all.top<margin?margin-all.top:all.bottom>h-margin?h-margin-all.bottom:0;
        JSONObject settings=object.getJSONObject("human").getJSONObject("settings");settings.put("x",settings.getDouble("x")+dx/w).put("floorY",settings.getDouble("floorY")+dy/h);SketchHumanFigure.settings(settings);
    }
    private static RectF jointBounds(JSONObject object,int width,int height,int frame)throws Exception {
        JSONObject settings=object.getJSONObject("human").getJSONObject("settings");SketchHumanFigure.Skeleton p=SketchHumanFigure.skeleton(settings,pose(object,frame));double scale=Math.min(width/1280d,height/720d)*settings.getDouble("size"),cx=width*settings.getDouble("x"),cy=height*settings.getDouble("floorY");
        double[][] anchors={p.hip,p.leftHip,p.rightHip,p.leftKnee,p.rightKnee,p.leftFoot,p.rightFoot,p.nearShoulder,p.farShoulder,p.nearElbow,p.farElbow,p.nearHand,p.farHand,p.head};RectF b=new RectF();boolean first=true;
        for(double[] anchor:anchors){double[] q=SketchHumanFigure.project(settings,anchor);float x=(float)(cx+q[0]*scale),y=(float)(cy+q[1]*scale),r=(float)(42*scale);RectF part=new RectF(x-r,y-r,x+r,y+r);if(first){b.set(part);first=false;}else b.union(part);}
        return b;
    }
    private static RectF bounds(JSONObject object,int width,int height,int frame)throws Exception {
        RectF b=jointBounds(object,width,height,frame);
        if(b.left<0||b.top<0||b.right>width||b.bottom>height){
            // Near a canvas edge, verify actual ink rather than rejecting a valid
            // sole because a conservative joint disk extends below the floor.
            JSONObject exact=frameDocument(object,width,height,frame);JSONArray objects=exact.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");boolean initial=true;
            for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);RectF part=new RectF();SketchFrameRenderer.path(o.getJSONArray("commands"),width,height).computeBounds(part,true);float pad=o.getBoolean("fill")?0:(float)(o.getDouble("width")*Math.min(width,height)/2);part.inset(-pad,-pad);if(initial){b.set(part);initial=false;}else b.union(part);}
            if(b.left<0||b.top<0||b.right>width||b.bottom>height)throw SketchDocument.bad("Human pose leaves canvas; adjust actor placement, size or anchors");
        }return b;
    }
    static JSONObject frameDocument(JSONObject object,int width,int height,int frame)throws Exception {
        JSONObject h=object.getJSONObject("human"),doc=SketchDocument.create(new JSONObject().put("sketchId","human_native_frame").put("width",width).put("height",height).put("frameCount",1).put("fps",h.getInt("fps")));JSONObject layer=doc.getJSONArray("layers").getJSONObject(0);String inkId=object.getString("id");if(inkId.length()>32)inkId="human_"+SketchDocument.sha256(inkId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,24);SketchHumanFigure.draw(doc,layer,inkId,h.getJSONObject("settings"),pose(object,frame),localFrame(object,frame)/(double)h.getInt("fps"));String part=h.optString("parts","all");if(!part.equals("all")){JSONArray original=layer.getJSONArray("objects"),kept=new JSONArray();String side=part.contains("near")?"near":"far";boolean only=part.equals("near_hand")||part.equals("far_hand");for(int i=0;i<original.length();i++){JSONObject o=original.getJSONObject(i);String name=o.getString("id");boolean hand=name.contains("_"+side+"_hand_")||name.contains("_"+side+"_finger_")||name.contains("_"+side+"_thumb_")||name.contains("_"+side+"_palm_crease_");if(hand==only)kept.put(o);}layer.put("objects",kept);}return doc;
    }
    static void draw(Context context,Canvas canvas,JSONObject object,int width,int height,int frame,double alpha)throws Exception {
        JSONObject d=frameDocument(object,width,height,frame);d.getJSONArray("layers").getJSONObject(0).put("opacity",alpha);try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,d)){renderer.draw(canvas,0,false);}
    }
}
