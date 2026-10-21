package com.rezoxnemesis.videostudio;

import org.json.*;

/** Shares unchanged eye components without changing anatomy, ordering or frame timing. */
final class SketchFacialCelSharing {
    private SketchFacialCelSharing(){}
    static JSONArray eyes(JSONArray source,String eyeId)throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<source.length();i++){JSONObject object=source.getJSONObject(i);String id=object.getString("id");if(!id.startsWith(eyeId+"_left_f")&&!id.startsWith(eyeId+"_right_f"))out.put(object);}
        // Explicit paint order is essential: a held rim must stay above moving
        // iris/pupil fills, including when the first frame is a closed eyelid.
        for(String side:new String[]{"left","right"})for(String part:new String[]{"sclera","iris","pupil","glint","shade","rim"}){
            JSONObject previous=null;String priorSignature=null;
            for(int i=0;i<source.length();i++){
                JSONObject object=source.getJSONObject(i);String id=object.getString("id");if(!id.startsWith(eyeId+"_"+side+"_f")||!id.endsWith("_"+part))continue;
                JSONObject shape=SketchDocument.copy(object);shape.remove("id");shape.remove("frame");shape.remove("exposure");String signature=SketchDocument.canonical(shape);
                if(previous!=null&&previous.getInt("frame")+previous.getInt("exposure")==object.getInt("frame")&&signature.equals(priorSignature))previous.put("exposure",previous.getInt("exposure")+object.getInt("exposure"));
                else {previous=object;priorSignature=signature;out.put(object);}
            }
        }
        return out;
    }
}
