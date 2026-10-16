package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns authored walk/run pose keys into explicitly planted sole intervals. Contact targets
 * come from the real native rig solver and the prepared floor position: no guessed
 * camera coordinates, image matching, hidden geometry or destructive rebaking.
 *
 * The solver's hard-contact frames are exact. Small, optional contact blend ramps reduce
 * entry/exit snapping without disguising unreachable hard plants. Large strides and
 * unprepared artwork are rejected rather than fabricating credible-looking results.
 */
final class SketchAnimationFootwork {
    private SketchAnimationFootwork(){}

    static JSONObject plant(JSONObject source,JSONObject program,String rigId,int start,int duration,String motion)throws Exception {
        if(source==null||program==null)throw SketchDocument.bad("Prepare an editable character animation first");
        if(!"walk".equals(motion)&&!"run".equals(motion))
            throw SketchDocument.bad("Automatic foot planting supports authored walk or run cycles only");
        SketchDocument.validate(source);
        int total=(int)SketchDocument.integer(program,"frameCount",1,SketchDocument.MAX_FRAMES);
        if(start<0||duration<24||duration>240||(long)start+duration>total)
            throw SketchDocument.bad("Foot plants need 24..240 frames inside the current animation");
        JSONObject next=SketchDocument.copy(program),rig=SketchAnimationUiModel.rig(next,rigId);
        if(rig.getJSONArray("poses").length()<4)
            throw SketchDocument.bad("Create the walk/run pose keys before planting feet");
        JSONObject leftHip=joint(rig,"hip_left"),leftKnee=joint(rig,"knee_left"),leftFoot=joint(rig,"foot_left");
        JSONObject rightHip=joint(rig,"hip_right"),rightKnee=joint(rig,"knee_right"),rightFoot=joint(rig,"foot_right");
        requireLegChain(leftHip,leftKnee,leftFoot);requireLegChain(rightHip,rightKnee,rightFoot);
        Set<String> attached=new HashSet<>();
        JSONArray bindings=rig.getJSONArray("bindings");
        for(int i=0;i<bindings.length();i++)attached.add(bindings.getJSONObject(i).getString("jointId"));
        if(!attached.contains("foot_left")||!attached.contains("foot_right"))
            throw SketchDocument.bad("Bind a separate left and right foot drawing to their foot joints before planting");
        int width=source.getInt("width"),height=source.getInt("height");
        int steps=Math.max(4,Math.min(12,(int)Math.ceil(duration/12d)));
        if((steps&1)!=0)steps++;
        JSONArray bare=SketchAnimationCompiler.sampleFrames(source,next,start,start+duration-1);
        JSONArray contacts=rig.getJSONArray("ik");
        for(int step=0;step<steps;step++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Foot planting cancelled");
            int groupFirst=start+(int)((long)step*duration/steps);
            int groupLast=start+(int)((long)(step+1)*duration/steps)-1;
            int span=groupLast-groupFirst+1;
            int first=groupFirst+Math.max(1,(int)Math.floor(.17*span));
            int last=groupFirst+Math.max(2,(int)Math.floor(("run".equals(motion)?.58:.70)*span));
            last=Math.min(groupLast,last);
            if(last-first<1)throw SketchDocument.bad("This walk/run cycle is too short for planted-foot timing");
            boolean left=(step&1)==0;
            JSONObject hip=left?leftHip:rightHip,knee=left?leftKnee:rightKnee,foot=left?leftFoot:rightFoot;
            int easeIn=Math.min(2,first-groupFirst),easeOut=Math.min(2,groupLast-last);
            // No two constraints may compete for the same chain or an ancestor
            // while their transition ramps are active.
            for(int i=0;i<contacts.length();i++){
                JSONObject old=contacts.getJSONObject(i);
                int oldStart=old.getInt("start")-old.optInt("blendInFrames",0);
                int oldEnd=old.getInt("end")+old.optInt("blendOutFrames",0);
                boolean sameChain=old.getString("hip").equals(hip.getString("id"))
                        ||old.getString("knee").equals(knee.getString("id"))
                        ||old.getString("ankle").equals(foot.getString("id"));
                if(sameChain&&oldStart<=last+easeOut&&first-easeIn<=oldEnd)
                    throw SketchDocument.bad("Authored foot contact already owns frame "+(Math.max(first,oldStart)+1)+" for "+hip.getString("id"));
            }
            double upper=length(hip,knee,width,height),lower=length(knee,foot,width,height);
            if(upper<2||lower<2)throw SketchDocument.bad("Rig legs need visible nonzero bone lengths");
            double reach=upper+lower-1.0; // one physical source pixel of reach margin
            double floorY=foot.getDouble("y");
            if(floorY<=.01||floorY>=.99)
                throw SketchDocument.bad("Place each rest foot within the visible canvas before planting");
            double feasibleLeft=0,feasibleRight=1;
            for(int f=first;f<=last;f++){
                JSONObject solvedHip=sampleJoint(bare.getJSONObject(f-start),rigId,hip.getString("id"));
                double x=solvedHip.getDouble("x"),dy=(floorY-solvedHip.getDouble("y"))*height;
                double horizontal2=reach*reach-dy*dy;
                if(horizontal2<=0)
                    throw SketchDocument.bad("Foot target is unreachable at frame "+(f+1)+". Lower the character, shorten the stride or adjust the leg rig.");
                double allowance=Math.sqrt(horizontal2)/width;
                feasibleLeft=Math.max(feasibleLeft,x-allowance);
                feasibleRight=Math.min(feasibleRight,x+allowance);
            }
            if(feasibleLeft>feasibleRight)
                throw SketchDocument.bad("Stride is longer than this foot can plant. Reduce root travel or split the cycle.");
            JSONObject firstAnkle=sampleJoint(bare.getJSONObject(first-start),rigId,foot.getString("id"));
            double preferred=firstAnkle.getDouble("x");
            double anchorX=Math.max(feasibleLeft,Math.min(feasibleRight,preferred));
            JSONObject planted=new JSONObject().put("hip",hip.getString("id")).put("knee",knee.getString("id"))
                    .put("ankle",foot.getString("id")).put("start",first).put("end",last)
                    .put("x",anchorX).put("y",floorY).put("bend",left?1:-1).put("rotation",0)
                    .put("blendInFrames",easeIn).put("blendOutFrames",easeOut);
            contacts.put(planted);
        }
        // Fail atomically if any native hard-lock is unreachable or a child/ancestor
        // constraint conflicts with the new plants. Preview, Save and Bake share this solver.
        JSONArray verified=SketchAnimationCompiler.sampleFrames(source,next,start,start+duration-1);
        int hardSamples=0;
        for(int i=0;i<verified.length();i++){
            JSONArray errors=verified.getJSONObject(i).getJSONArray("contactErrors");
            for(int j=0;j<errors.length();j++){
                JSONObject contact=errors.getJSONObject(j);
                if(rigId.equals(contact.getString("rigId"))){
                    if(contact.getDouble("errorPixels")>.02)
                        throw SketchDocument.bad("A planted foot drifted beyond the native contact tolerance");
                    hardSamples++;
                }
            }
        }
        if(hardSamples<steps*2)throw SketchDocument.bad("No stable planted-foot intervals could be verified");
        return next;
    }

    private static JSONObject joint(JSONObject rig,String id)throws Exception {
        JSONArray joints=rig.getJSONArray("joints");
        for(int i=0;i<joints.length();i++){
            JSONObject found=joints.getJSONObject(i);
            if(id.equals(found.getString("id")))return found;
        }
        throw SketchDocument.bad("Foot planting needs the humanoid joint "+id);
    }
    private static void requireLegChain(JSONObject hip,JSONObject knee,JSONObject ankle)throws Exception {
        if(!hip.getString("id").equals(knee.optString("parent"))||!knee.getString("id").equals(ankle.optString("parent")))
            throw SketchDocument.bad("Foot planting needs each direct hip → knee → ankle chain");
    }
    private static double length(JSONObject a,JSONObject b,int width,int height)throws Exception {
        double dx=(a.getDouble("x")-b.getDouble("x"))*width;
        double dy=(a.getDouble("y")-b.getDouble("y"))*height;
        return Math.hypot(dx,dy);
    }
    private static JSONObject sampleJoint(JSONObject snapshot,String rigId,String jointId)throws Exception {
        JSONArray list=snapshot.getJSONArray("joints");
        for(int i=0;i<list.length();i++){
            JSONObject joint=list.getJSONObject(i);
            if(rigId.equals(joint.getString("rigId"))&&jointId.equals(joint.getString("jointId")))return joint;
        }
        throw SketchDocument.bad("The animation preview did not report joint "+rigId+":"+jointId);
    }
}
