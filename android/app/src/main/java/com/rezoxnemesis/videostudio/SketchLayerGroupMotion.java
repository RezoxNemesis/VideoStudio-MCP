package com.rezoxnemesis.videostudio;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Whole-shot rigid canvas motion, composed over detached original per-frame layer samples. */
final class SketchLayerGroupMotion {
    static final int MAX_FRAMES=240,MAX_POSES=32;
    static final String CAPABILITY_JSON="{\"minimumNativeVersion\":\"4.10.19\",\"action\":\"sketch_edit\",\"operation\":\"set_layer_group_motion\",\"minimumLayers\":2,\"maximumLayers\":32,\"maximumFrames\":240,\"maximumPoses\":32,\"singlePoseOnlyForOneFrame\":true,\"poseChannels\":[\"x\",\"y\",\"rotation\"],\"translationRange\":[-4,4],\"rotationRange\":[-3600,3600],\"pivotRange\":[0,1],\"coordinateSpace\":\"normalized canvas world deltas\",\"sourceSampling\":\"Original per-frame layer transforms sampled before any mutation\",\"wholeShotOnly\":true,\"storedKeys\":\"Ordinary dense HOLD layer transform keys\",\"preservesSourcePixels\":true,\"preservesPerFrameAnchorsScalesOpacity\":true,\"unselectedLayersUnchanged\":true,\"correctionSupported\":false,\"newCanonicalAction\":false,\"manualControls\":\"Sketch Studio > Frames / Cels > Group motion\",\"limitations\":\"Authored 2D rigid placement only; no articulated grasp, contact IK, collision forces, inferred anatomy or artistic certification.\"}";
    private SketchLayerGroupMotion(){}
    static JSONObject capabilities()throws Exception{return new JSONObject(CAPABILITY_JSON);}

    /** Pure JSON/math planning. The caller retains ownership of persistence/CAS/Undo/receipts. */
    static void apply(JSONObject document,JSONObject operation)throws Exception {
        cancelled();
        SketchDocument.fields(operation,"type,layerIds,pivot,poses");
        if(!"set_layer_group_motion".equals(operation.getString("type")))throw SketchDocument.bad("Unknown layer group motion operation");
        int frames=(int)SketchDocument.integer(document,"frameCount",1,MAX_FRAMES);
        int width=document.getInt("width"),height=document.getInt("height");
        JSONArray ids=operation.getJSONArray("layerIds"),pivot=operation.getJSONArray("pivot");
        if(ids.length()<2||ids.length()>SketchDocument.MAX_LAYERS)throw SketchDocument.bad("Group motion requires2..32 distinct layers");
        if(pivot.length()!=2)throw SketchDocument.bad("Group pivot requires normalized canvas[x,y]");
        double px=SketchDocument.scalar(pivot.get(0),0,1,"group pivot x")*width,py=SketchDocument.scalar(pivot.get(1),0,1,"group pivot y")*height;
        List<JSONObject> layers=new ArrayList<>();Set<String> selected=new HashSet<>();
        for(int n=0;n<ids.length();n++){
            cancelled();String id=SketchDocument.id(new JSONObject().put("id",ids.get(n)),"id");
            if(!selected.add(id))throw SketchDocument.bad("Duplicate group layer");
            JSONObject layer=SketchDocument.layer(document,id);SketchEditOps.requireUnlocked(layer);
            if(!layer.getBoolean("visible")||layer.getDouble("opacity")<=0)throw SketchDocument.bad("Group motion requires visible nontransparent layers");layers.add(layer);
        }
        SketchLayeredDrawing.requireOperation(document,operation);
        List<Pose> poses=poses(operation.getJSONArray("poses"),frames);
        // Include all actual existing key families, not just selected layer transform keys.
        long keys=(long)layers.size()*frames;
        JSONArray all=document.getJSONArray("layers");
        for(int n=0;n<all.length();n++){
            cancelled();JSONObject layer=all.getJSONObject(n);if(!selected.contains(layer.getString("id")))keys+=layer.getJSONArray("keys").length();
            JSONArray objects=layer.getJSONArray("objects");for(int j=0;j<objects.length();j++){
                JSONObject object=objects.getJSONObject(j);keys+=object.getJSONArray("keys").length();
                if(object.has("shapeKeys"))keys+=object.getJSONArray("shapeKeys").length();
                if(object.has("paintMesh")&&object.getJSONObject("paintMesh").has("keys"))keys+=object.getJSONObject("paintMesh").getJSONArray("keys").length();
                if("hand".equals(object.getString("kind")))keys+=object.getJSONObject("hand").getJSONArray("poses").length();
                if("human".equals(object.getString("kind")))keys+=object.getJSONObject("human").getJSONArray("poses").length();
            }
        }
        if(keys>SketchDocument.MAX_KEYS)throw SketchDocument.bad("Group motion exceeds the existing4096-key document budget");
        // No selected track is written until every original source frame is detached.
        JSONObject[][] source=new JSONObject[layers.size()][frames];
        for(int n=0;n<layers.size();n++)for(int f=0;f<frames;f++){cancelled();source[n][f]=SketchDocument.transformAt(layers.get(n),f);}
        JSONArray[] planned=new JSONArray[layers.size()];for(int n=0;n<planned.length;n++)planned[n]=new JSONArray();
        int left=0;
        for(int f=0;f<frames;f++){
            cancelled();while(left+1<poses.size()&&poses.get(left+1).frame<=f)left++;
            Pose a=poses.get(left),b=left+1<poses.size()?poses.get(left+1):a;
            double t=a==b?0:SketchEasing.sample(a.interpolation,a.curve,(f-a.frame)/(double)(b.frame-a.frame));
            double dx=(a.x+(b.x-a.x)*t)*width,dy=(a.y+(b.y-a.y)*t)*height;
            // Keep authored unwrapped endpoints for interpolation. Reduce only this
            // integer-frame composition, so360degrees returns exact neutral pixels.
            double angle=Math.IEEEremainder(a.rotation+(b.rotation-a.rotation)*t,360d);
            double radians=Math.toRadians(angle),cos=Math.cos(radians),sin=Math.sin(radians);
            for(int n=0;n<layers.size();n++){
                cancelled();JSONObject base=source[n][f],out=SketchDocument.copy(base);
                if(dx!=0||dy!=0||angle!=0){
                    double ax=base.getDouble("anchorX")*width,ay=base.getDouble("anchorY")*height;
                    double qx=ax+base.getDouble("x")*width-px,qy=ay+base.getDouble("y")*height-py;
                    out.put("x",(px+dx+cos*qx-sin*qy-ax)/width).put("y",(py+dy+sin*qx+cos*qy-ay)/height).put("rotation",base.getDouble("rotation")+angle);
                }
                SketchDocument.validateTransform(out);
                planned[n].put(new JSONObject().put("frame",f).put("transform",out).put("interpolation","hold"));
            }
        }
        // Full validation includes JSON size, procedural bounds, frozen workflow
        // content, all key/point budgets and the unchanged source geometry.
        JSONObject candidate=SketchDocument.copy(document);
        for(int n=0;n<layers.size();n++)SketchDocument.layer(candidate,layers.get(n).getString("id")).put("keys",planned[n]);
        SketchLayeredDrawing.afterOperation(candidate);SketchDocument.validate(candidate);cancelled();
        // No cancellation point inside this final in-memory publication: either
        // every prepared track is assigned, or an earlier failure changed nothing.
        for(int n=0;n<layers.size();n++)layers.get(n).put("keys",planned[n]);
        SketchLayeredDrawing.afterOperation(document);
    }

    private static final class Pose {int frame;double x,y,rotation;String interpolation;JSONArray curve;}
    private static List<Pose> poses(JSONArray input,int frames)throws Exception {
        int minimum=frames==1?1:2;
        if(input.length()<minimum||input.length()>MAX_POSES)throw SketchDocument.bad("Group motion requires"+minimum+"..32 poses");
        List<Pose> result=new ArrayList<>();int previous=-1;
        for(int n=0;n<input.length();n++){
            cancelled();JSONObject value=input.getJSONObject(n);SketchDocument.fields(value,"frame,x,y,rotation,interpolation,curve");Pose pose=new Pose();
            pose.frame=(int)SketchDocument.integer(value,"frame",0,frames-1);if(pose.frame<=previous)throw SketchDocument.bad("Group poses require unique ascending frames");previous=pose.frame;
            pose.x=SketchDocument.number(value,"x",-4,4);pose.y=SketchDocument.number(value,"y",-4,4);pose.rotation=SketchDocument.number(value,"rotation",-3600,3600);SketchEasing.validate(value);
            pose.interpolation=value.optString("interpolation","linear");pose.curve=value.has("curve")?new JSONArray(value.getJSONArray("curve").toString()):null;result.add(pose);
        }
        if(result.get(0).frame!=0||result.get(result.size()-1).frame!=frames-1)throw SketchDocument.bad("Group motion must cover the complete existing shot clock");return result;
    }
    private static void cancelled()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("SKETCH_GROUP_MOTION_CANCELLED: no partial tracks published");}
}
