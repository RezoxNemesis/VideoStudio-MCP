package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded, editable 2D acting presets. The output is the existing native rig/pose program,
 * not a baked bitmap or simulated camera motion. Does not invent missing artwork or IK.
 */
final class SketchAnimationMotionLibrary {
    private static final String[] STYLES={"idle","walk","run","strike"};
    private SketchAnimationMotionLibrary(){}

    static JSONObject insert(JSONObject original,String rigId,int start,int duration,String style)throws Exception {
        if(original==null||style==null)throw SketchDocument.bad("Choose a prepared animation and motion");
        String preset=style.trim().toLowerCase(Locale.ROOT);
        boolean supported=false;for(String candidate:STYLES)if(candidate.equals(preset))supported=true;
        if(!supported)throw SketchDocument.bad("Unknown motion preset: "+style);
        int frames=(int)SketchDocument.integer(original,"frameCount",1,SketchDocument.MAX_FRAMES);
        if(start<0||duration<9||duration>360||(long)start+duration>frames)
            throw SketchDocument.bad("Preset needs 9..360 frames fully inside the current animation");
        JSONObject result=SketchDocument.copy(original),rig=SketchAnimationUiModel.rig(result,rigId);
        if(rig.getJSONArray("bindings").length()<2)
            throw SketchDocument.bad("Bind at least two separate artwork parts before applying character motion");
        Set<String> joints=new HashSet<>();JSONArray rest=rig.getJSONArray("joints");
        for(int i=0;i<rest.length();i++)joints.add(rest.getJSONObject(i).getString("id"));
        String[] required="idle".equals(preset)
                ?new String[]{"root","chest","head","arm_left","arm_right"}
                : "strike".equals(preset)
                ?new String[]{"root","chest","arm_left","arm_right","elbow_right","head"}
                :new String[]{"root","chest","arm_left","arm_right","hip_left","hip_right","knee_left","knee_right"};
        for(String id:required)if(!joints.contains(id))
            throw SketchDocument.bad("The "+preset+" preset requires joint "+id+"; rig this character first");
        JSONArray existing=rig.getJSONArray("poses");
        for(int i=0;i<existing.length();i++){
            int current=existing.getJSONObject(i).getInt("frame");
            if(current>=start&&current<start+duration)
                throw SketchDocument.bad("Preset overlaps an authored pose at frame "+(current+1)+". Choose an empty interval");
        }
        JSONObject base=SketchAnimationUiModel.previousPose(rig,Math.max(0,start-1));
        // The shared UI sampler correctly reports an empty rotation map before the
        // first authored pose. A preset must initialize every rest joint to zero.
        JSONObject neutral=new JSONObject();
        JSONObject authored=base.getJSONObject("rotations");
        for(int i=0;i<rest.length();i++){
            String id=rest.getJSONObject(i).getString("id");
            neutral.put(id,authored.optDouble(id,0));
        }
        double bx=base.optDouble("rootX"),by=base.optDouble("rootY");
        int keys="idle".equals(preset)?5:"strike".equals(preset)?7:9;
        for(int i=0;i<keys;i++){
            int frame=start+(int)Math.round((long)(duration-1)*i/(double)(keys-1));
            double t=i/(double)(keys-1),phase=t*Math.PI*4;
            JSONObject angles=SketchDocument.copy(neutral);
            double dx=0,dy=0;
            switch(preset){
                case "idle":
                    dx=.002*Math.sin(t*Math.PI*2);dy=-.004*(1-Math.cos(t*Math.PI*4));
                    angle(angles,"chest",1.6*Math.sin(t*Math.PI*2));
                    angle(angles,"head",-1.4*Math.sin(t*Math.PI*2));
                    angle(angles,"arm_left",2.5*Math.sin(t*Math.PI*2));
                    angle(angles,"arm_right",-2.5*Math.sin(t*Math.PI*2));
                    break;
                case "walk":
                case "run":
                    boolean running="run".equals(preset);double amplitude=running?35:23;
                    double sine=Math.sin(phase),other=-sine;
                    dx=t*(duration/(double)result.getInt("fps"))*(running?.23:.12);
                    dy=-(running?.015:.007)*Math.abs(Math.sin(phase));
                    angle(angles,"hip_left",amplitude*sine);
                    angle(angles,"hip_right",amplitude*other);
                    angle(angles,"knee_left",(running?39:26)*Math.max(0,-sine));
                    angle(angles,"knee_right",(running?39:26)*Math.max(0,sine));
                    angle(angles,"arm_left",-amplitude*.9*sine);
                    angle(angles,"arm_right",amplitude*.9*sine);
                    angle(angles,"chest",running?-8:-3);
                    break;
                case "strike":
                    double[] right={0,-22,-65,-112,-98,-27,0};
                    double[] left={0,12,28,15,10,4,0};
                    double[] lean={0,-7,-18,12,18,6,0};
                    double[] elbow={0,-10,-30,-54,-25,-8,0};
                    double[] advance={0,-.008,.012,.047,.058,.051,.04};
                    angle(angles,"arm_right",right[i]);angle(angles,"arm_left",left[i]);
                    angle(angles,"chest",lean[i]);angle(angles,"head",-lean[i]*.35);
                    angle(angles,"elbow_right",elbow[i]);dx=advance[i];
                    break;
                default:throw new IllegalStateException("Unreachable preset");
            }
            JSONObject pose=new JSONObject().put("frame",frame).put("rootX",bounded(bx+dx,-4,4))
                    .put("rootY",bounded(by+dy,-4,4)).put("rotations",angles)
                    .put("interpolation","strike".equals(preset)&&i==3?"linear":"smooth");
            existing.put(pose);
        }
        rig.put("poses",SketchAnimationUiModel.sortedFrames(existing));
        return result;
    }

    private static double bounded(double x,double min,double max) {
        if(!Double.isFinite(x)||x<min||x>max)throw SketchDocument.bad("Motion exceeds supported pose coordinates; shorten the interval");
        return x;
    }
    private static void angle(JSONObject rotations,String id,double offset)throws Exception {
        if(!rotations.has(id))throw SketchDocument.bad("Missing pose joint: "+id);
        rotations.put(id,bounded(rotations.getDouble(id)+offset,-3600,3600));
    }
}
