package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/** Topology-preserving authored curve deformation, shared by touch and native rendering. */
final class SketchShapeKeys {
    static final int MAX_KEYS=240;
    private SketchShapeKeys(){}
    static boolean animated(JSONObject object){JSONArray keys=object.optJSONArray("shapeKeys");return keys!=null&&keys.length()>0;}

    /** Returns additional key and coordinate-pair counts for the existing document budgets. */
    static int[] validate(JSONObject object,int frames)throws Exception {
        if(!object.has("shapeKeys"))return new int[]{0,0};
        if(!"path".equals(object.getString("kind"))||!(object.opt("shapeKeys") instanceof JSONArray))
            throw SketchDocument.bad("Shape keys require an editable Bezier path");
        JSONArray keys=object.getJSONArray("shapeKeys"),base=object.getJSONArray("commands");
        if(keys.length()>MAX_KEYS)throw SketchDocument.bad("Use at most 240 shape keys per path");
        int previous=-1,points=0;
        for(int i=0;i<keys.length();i++){
            JSONObject key=keys.getJSONObject(i);SketchDocument.fields(key,"frame,commands,interpolation,curve");
            int frame=(int)SketchDocument.integer(key,"frame",0,frames-1);
            if(frame<=previous)throw SketchDocument.bad("Shape keys require unique ascending frames");previous=frame;
            key.getString("interpolation");SketchEasing.validate(key);
            JSONArray commands=key.getJSONArray("commands");points+=SketchDocument.validateCommands(commands);
            if(commands.length()!=base.length())throw SketchDocument.bad("Shape keys must preserve the base path topology");
            for(int c=0;c<base.length();c++)if(!base.getJSONArray(c).getString(0).equals(commands.getJSONArray(c).getString(0))
                    ||base.getJSONArray(c).length()!=commands.getJSONArray(c).length())
                throw SketchDocument.bad("Shape keys must preserve the base path topology");
        }
        return new int[]{keys.length(),points};
    }
    static JSONArray commandsAt(JSONObject object,int frame)throws Exception {
        JSONArray base=object.getJSONArray("commands"),keys=object.optJSONArray("shapeKeys");
        JSONObject left=null,right=null;
        if(keys!=null)for(int i=0;i<keys.length();i++){
            JSONObject key=keys.getJSONObject(i);if(key.getInt("frame")<=frame)left=key;else{right=key;break;}
        }
        if(left==null)return new JSONArray(base.toString());
        JSONArray start=left.getJSONArray("commands");
        if(right==null||"hold".equals(left.getString("interpolation")))return new JSONArray(start.toString());
        double t=SketchEasing.sample(left.getString("interpolation"),left.optJSONArray("curve"),
                (frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame")));
        JSONArray end=right.getJSONArray("commands"),result=new JSONArray();
        for(int i=0;i<start.length();i++){
            JSONArray a=start.getJSONArray(i),b=end.getJSONArray(i),out=new JSONArray().put(a.getString(0));
            for(int c=1;c<a.length();c++)out.put(a.getDouble(c)+(b.getDouble(c)-a.getDouble(c))*t);
            result.put(out);
        }
        return result;
    }
    static JSONObject sampled(JSONObject object,int frame)throws Exception {
        // Do not serialize every stored key while sampling one visible shape.
        JSONObject snapshot=new JSONObject();java.util.Iterator<String> fields=object.keys();
        while(fields.hasNext()){String field=fields.next();if(!"shapeKeys".equals(field))snapshot.put(field,object.get(field));}
        JSONObject result=SketchDocument.copy(snapshot);
        if("path".equals(object.getString("kind")))result.put("commands",commandsAt(object,frame));
        result.remove("shapeKeys");return result.has("paintMesh")?SketchPaintMesh.sampled(result,frame):result;
    }
    static void apply(JSONObject object,JSONObject operation,int frames)throws Exception {
        SketchDocument.fields(operation,"type,objectId,frame,commands,interpolation,curve,remove");
        if(!"path".equals(object.getString("kind")))throw SketchDocument.bad("Shape keys require an editable Bezier path");
        int frame=(int)SketchDocument.integer(operation,"frame",0,frames-1);
        boolean remove=operation.has("remove")&&operation.getBoolean("remove");
        JSONObject replacement=null;
        if(!remove){
            replacement=new JSONObject().put("frame",frame).put("commands",new JSONArray(operation.getJSONArray("commands").toString()))
                    .put("interpolation",operation.optString("interpolation","linear"));
            if(operation.has("curve"))replacement.put("curve",new JSONArray(operation.getJSONArray("curve").toString()));
        }
        JSONArray prior=object.optJSONArray("shapeKeys"),next=new JSONArray();boolean inserted=false;
        if(prior!=null)for(int i=0;i<prior.length();i++){
            JSONObject key=prior.getJSONObject(i);
            if(!inserted&&key.getInt("frame")>=frame){if(replacement!=null)next.put(replacement);inserted=true;}
            if(key.getInt("frame")!=frame)next.put(SketchDocument.copy(key));
        }
        if(!inserted&&replacement!=null)next.put(replacement);
        JSONObject candidate=SketchDocument.copy(object).put("shapeKeys",next);validate(candidate,frames);
        object.put("shapeKeys",next);
    }
    static JSONObject editOperation(JSONObject object,int frame,JSONArray commands)throws Exception {
        if(!animated(object))return new JSONObject().put("type","update_object").put("objectId",object.getString("id"))
                .put("patch",new JSONObject().put("commands",new JSONArray(commands.toString())));
        JSONObject operation=new JSONObject().put("type","set_shape_key").put("objectId",object.getString("id"))
                .put("frame",frame).put("commands",new JSONArray(commands.toString())).put("interpolation","linear");
        JSONArray keys=object.getJSONArray("shapeKeys");JSONObject timing=null;
        for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);if(key.getInt("frame")>frame)break;timing=key;}
        if(timing!=null){operation.put("interpolation",timing.getString("interpolation"));if(timing.has("curve"))operation.put("curve",new JSONArray(timing.getJSONArray("curve").toString()));}
        return operation;
    }
    static JSONArray metadata(JSONObject object)throws Exception {
        JSONArray result=new JSONArray(),keys=object.optJSONArray("shapeKeys");
        if(keys!=null)for(int i=0;i<keys.length();i++){
            JSONObject key=SketchDocument.copy(keys.getJSONObject(i));key.put("commandCount",key.getJSONArray("commands").length());key.remove("commands");result.put(key);
        }
        return result;
    }
}
