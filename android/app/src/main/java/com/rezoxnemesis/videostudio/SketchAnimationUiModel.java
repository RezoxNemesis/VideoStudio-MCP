package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** Human authoring helpers; values stay in the compiler's native animation schema. */
final class SketchAnimationUiModel {
    private SketchAnimationUiModel() {}

    static JSONObject createProgram(JSONObject source)throws Exception {
        JSONArray layerIds=new JSONArray(),layers=source.getJSONArray("layers");
        for(int i=0;i<layers.length();i++)layerIds.put(layers.getJSONObject(i).getString("id"));
        return new JSONObject().put("schema","videostudio.sketch-animation.v1")
                .put("frameCount",Math.max(100,source.getInt("frameCount"))).put("fps",source.getInt("fps"))
                .put("rigs",new JSONArray().put(humanoid("character_1")))
                .put("camera",new JSONArray()).put("cameraLayerIds",layerIds)
                .put("dialogues",new JSONArray()).put("mouthCels",new JSONArray()).put("interactionCues",new JSONArray());
    }
    static JSONObject humanoid(String id)throws Exception {
        JSONArray joints=new JSONArray();
        joint(joints,"root",null,.50,.56);joint(joints,"chest","root",.50,.38);
        joint(joints,"neck","chest",.50,.28);joint(joints,"head","neck",.50,.19);
        joint(joints,"arm_left","chest",.38,.38);joint(joints,"elbow_left","arm_left",.30,.50);joint(joints,"hand_left","elbow_left",.24,.60);
        joint(joints,"arm_right","chest",.62,.38);joint(joints,"elbow_right","arm_right",.70,.50);joint(joints,"hand_right","elbow_right",.76,.60);
        joint(joints,"hip_left","root",.43,.57);joint(joints,"knee_left","hip_left",.43,.73);joint(joints,"foot_left","knee_left",.43,.91);
        joint(joints,"hip_right","root",.57,.57);joint(joints,"knee_right","hip_right",.57,.73);joint(joints,"foot_right","knee_right",.57,.91);
        return new JSONObject().put("id",id).put("joints",joints).put("bindings",new JSONArray()).put("poses",new JSONArray()).put("ik",new JSONArray());
    }
    private static void joint(JSONArray joints,String id,String parent,double x,double y)throws Exception {
        JSONObject value=new JSONObject().put("id",id).put("x",x).put("y",y);if(parent!=null)value.put("parent",parent);joints.put(value);
    }
    static JSONObject rig(JSONObject program,String id)throws Exception {
        JSONArray rigs=program.getJSONArray("rigs");for(int i=0;i<rigs.length();i++)if(id.equals(rigs.getJSONObject(i).getString("id")))return rigs.getJSONObject(i);
        throw SketchDocument.bad("Select a character first");
    }
    static JSONObject joint(JSONObject rig,String id)throws Exception {
        JSONArray joints=rig.getJSONArray("joints");for(int i=0;i<joints.length();i++)if(id.equals(joints.getJSONObject(i).getString("id")))return joints.getJSONObject(i);
        throw SketchDocument.bad("Select a joint first");
    }
    static JSONObject pose(JSONObject program,String rigId,int frame,String interpolation)throws Exception {
        JSONObject rig=rig(program,rigId);JSONArray poses=rig.getJSONArray("poses");
        for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i).getInt("frame")==frame){JSONObject pose=poses.getJSONObject(i);pose.put("interpolation",interpolation);return pose;}
        JSONObject previous=null;for(int i=0;i<poses.length();i++){JSONObject value=poses.getJSONObject(i);if(value.getInt("frame")<=frame&&(previous==null||value.getInt("frame")>previous.getInt("frame")))previous=value;}
        JSONObject value=previousPose(rig,frame);
        value.put("frame",frame).put("interpolation",interpolation);poses.put(value);
        ArrayList<JSONObject> sorted=new ArrayList<>();for(int i=0;i<poses.length();i++)sorted.add(poses.getJSONObject(i));sorted.sort(Comparator.comparingInt(p->p.optInt("frame")));
        JSONArray ordered=new JSONArray();for(JSONObject item:sorted)ordered.put(item);rig.put("poses",ordered);return value;
    }
    static void setRotation(JSONObject program,String rigId,int frame,String jointId,double degrees,String interpolation)throws Exception {
        if(!Double.isFinite(degrees)||degrees < -3600||degrees>3600)throw SketchDocument.bad("Joint rotation must be −3600..3600 degrees");
        joint(rig(program,rigId),jointId);pose(program,rigId,frame,interpolation).getJSONObject("rotations").put(jointId,degrees);
    }
    static JSONObject previousPose(JSONObject rig,int frame)throws Exception {
        JSONArray poses=rig.getJSONArray("poses"),joints=rig.getJSONArray("joints");
        JSONObject state=new JSONObject().put("rootX",0).put("rootY",0).put("rotations",new JSONObject());
        for(int i=0;i<joints.length();i++)state.getJSONObject("rotations").put(joints.getJSONObject(i).getString("id"),0);
        JSONObject left=null,right=null;
        for(int i=0;i<poses.length();i++){
            JSONObject value=poses.getJSONObject(i),resolved=SketchDocument.copy(state);
            resolved.put("frame",value.getInt("frame")).put("interpolation",value.optString("interpolation","linear"));
            if(value.has("rootX"))resolved.put("rootX",value.getDouble("rootX"));if(value.has("rootY"))resolved.put("rootY",value.getDouble("rootY"));
            JSONObject rotations=value.optJSONObject("rotations");if(rotations!=null){java.util.Iterator<String> ids=rotations.keys();while(ids.hasNext()){String id=ids.next();resolved.getJSONObject("rotations").put(id,rotations.getDouble(id));}}
            state=resolved;if(value.getInt("frame")<=frame)left=resolved;else{right=resolved;break;}
        }
        if(left==null)return new JSONObject().put("rootX",0).put("rootY",0).put("rotations",new JSONObject());
        JSONObject result=SketchDocument.copy(left);if(right!=null){double f=(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame"));String mode=left.optString("interpolation","linear");if("hold".equals(mode))f=0;else if("smooth".equals(mode))f=f*f*(3-2*f);
            result.put("rootX",left.getDouble("rootX")+(right.getDouble("rootX")-left.getDouble("rootX"))*f).put("rootY",left.getDouble("rootY")+(right.getDouble("rootY")-left.getDouble("rootY"))*f);
            JSONObject rotations=result.getJSONObject("rotations");java.util.Iterator<String> ids=rotations.keys();while(ids.hasNext()){String id=ids.next();rotations.put(id,left.getJSONObject("rotations").getDouble(id)+(right.getJSONObject("rotations").getDouble(id)-left.getJSONObject("rotations").getDouble(id))*f);}
        }
        return result;
    }
    static void setVertexBlend(JSONObject binding,int vertices,int vertex,String first,String second,double firstWeight)throws Exception {
        if(vertices<1||vertex<0||vertex>=vertices)throw SketchDocument.bad("Select a valid vector vertex");
        if(!Double.isFinite(firstWeight)||firstWeight<0||firstWeight>1)throw SketchDocument.bad("Vertex weight must be 0..1");
        JSONArray weights=binding.optJSONArray("weights");if(weights==null){weights=new JSONArray();for(int i=0;i<vertices;i++)weights.put(new JSONObject().put(binding.getString("jointId"),1));}
        if(weights.length()!=vertices)throw SketchDocument.bad("Weights must cover every vector vertex");
        JSONObject value=new JSONObject();if(first.equals(second))value.put(first,1);else {if(firstWeight>0)value.put(first,firstWeight);if(firstWeight<1)value.put(second,1-firstWeight);}
        weights.put(vertex,value);binding.put("weights",weights);
    }
    static JSONArray sortedFrames(JSONArray values)throws Exception {ArrayList<JSONObject> list=new ArrayList<>();for(int i=0;i<values.length();i++)list.add(values.getJSONObject(i));list.sort(Comparator.comparingInt(p->p.optInt("frame")));JSONArray result=new JSONArray();for(JSONObject value:list)result.put(value);return result;}

    static double[] canvasToWorld(JSONObject source,JSONObject program,int frame,double x,double y)throws Exception {
        JSONObject camera=new JSONObject().put("x",0).put("y",0).put("zoom",1).put("rotation",0),left=null,right=null;JSONArray keys=program.optJSONArray("camera");
        if(keys!=null)for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i),resolved=SketchDocument.copy(camera);for(String field:new String[]{"x","y","zoom","rotation"})if(key.has(field))resolved.put(field,key.getDouble(field));resolved.put("frame",key.getInt("frame")).put("interpolation",key.optString("interpolation","linear"));camera=resolved;if(key.getInt("frame")<=frame)left=resolved;else{right=resolved;break;}}
        camera=new JSONObject().put("x",0).put("y",0).put("zoom",1).put("rotation",0);
        if(left!=null){camera=SketchDocument.copy(left);if(right!=null){double f=(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame"));String mode=left.optString("interpolation","linear");if("hold".equals(mode))f=0;else if("smooth".equals(mode))f=f*f*(3-2*f);for(String field:new String[]{"x","y","zoom","rotation"})camera.put(field,left.getDouble(field)+(right.getDouble(field)-left.getDouble(field))*f);}}
        double w=source.getDouble("width"),h=source.getDouble("height"),dx=(x-.5-camera.getDouble("x"))*w,dy=(y-.5-camera.getDouble("y"))*h,angle=Math.toRadians(camera.getDouble("rotation")),zoom=camera.getDouble("zoom");
        return new double[]{.5+(Math.cos(angle)*dx+Math.sin(angle)*dy)/(zoom*w),.5+(-Math.sin(angle)*dx+Math.cos(angle)*dy)/(zoom*h)};
    }
    static JSONArray withoutFrame(JSONArray values,int frame)throws Exception {JSONArray next=new JSONArray();for(int i=0;i<values.length();i++)if(values.getJSONObject(i).getInt("frame")!=frame)next.put(values.getJSONObject(i));return next;}
    static double nearestRotation(double target,double current){return Math.max(-3600,Math.min(3600,target+360*Math.rint((current-target)/360)));}
    static double bounded(String text,double low,double high,String label)throws Exception {double value;try{value=Double.parseDouble(text.trim());}catch(NumberFormatException invalid){throw SketchDocument.bad(label+" must be a number");}if(!Double.isFinite(value)||value<low||value>high)throw SketchDocument.bad(label+" must be "+low+".."+high);return value;}
    static int integer(String text,int low,int high,String label)throws Exception {double value=bounded(text,low,high,label);if(value!=Math.rint(value))throw SketchDocument.bad(label+" must be a whole number");return(int)value;}
}
